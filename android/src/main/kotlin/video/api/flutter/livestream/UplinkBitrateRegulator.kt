package video.api.flutter.livestream

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import android.util.Log
import io.github.thibaultbee.streampack.core.elements.data.FrameWithCloseable
import io.github.thibaultbee.streampack.core.elements.endpoints.DynamicEndpointFactory
import io.github.thibaultbee.streampack.core.elements.endpoints.IEndpointInternal
import io.github.thibaultbee.streampack.core.pipelines.IDispatcherProvider
import io.github.thibaultbee.streampack.core.pipelines.outputs.encoding.IConfigurableAudioEncodingPipelineOutput
import io.github.thibaultbee.streampack.core.pipelines.outputs.encoding.IConfigurableVideoEncodingPipelineOutput
import io.github.thibaultbee.streampack.core.pipelines.outputs.encoding.IEncodingPipelineOutput
import io.github.thibaultbee.streampack.core.regulator.controllers.IBitrateRegulatorController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Bytes the encoders have handed to the endpoint, counted by [MeteredEndpoint].
 *
 * StreamPack's RTMP endpoint reports no metrics (`metrics` is a TODO) and,
 * when the uplink cannot keep up, drops the oldest frames from a 10-frame
 * queue without a word. What the encoders produced, set against what the
 * phone actually sent, is how much of the stream never left it.
 */
class ProducedBytes {
    private val count = AtomicLong()

    fun add(bytes: Int) {
        count.addAndGet(bytes.toLong())
    }

    val total: Long
        get() = count.get()
}

/**
 * Wraps the real endpoint to count the bytes written to it. Everything else
 * is the wrapped endpoint's own.
 */
class MeteredEndpoint(
    private val delegate: IEndpointInternal,
    private val produced: ProducedBytes,
) : IEndpointInternal by delegate {
    override suspend fun write(closeableFrame: FrameWithCloseable, streamPid: Int) {
        produced.add(closeableFrame.frame.rawBuffer.remaining())
        delegate.write(closeableFrame, streamPid)
    }

    class Factory(
        private val produced: ProducedBytes,
        private val delegate: IEndpointInternal.Factory = DynamicEndpointFactory(),
    ) : IEndpointInternal.Factory {
        override fun create(
            context: Context,
            dispatcherProvider: IDispatcherProvider
        ): IEndpointInternal = MeteredEndpoint(delegate.create(context, dispatcherProvider), produced)
    }
}

/**
 * One second of the uplink, as reported to Dart.
 *
 * @param sufficient whether the uplink keeps up with what the encoders make
 * @param sentBitsPerSecond what actually left the phone in the last second
 * @param videoBitrate the video encoder's current bitrate
 * @param maxVideoBitrate the video bitrate the host chose
 */
data class BandwidthSample(
    val sufficient: Boolean,
    val sentBitsPerSecond: Int,
    val videoBitrate: Int,
    val maxVideoBitrate: Int,
)

/**
 * Watches the uplink once a second while streaming, lowers the video bitrate
 * when it cannot keep up and climbs back once it can — the Android
 * counterpart of the iOS side's `SteadyAdaptiveBitRateStrategy`, with the
 * same rules: nothing is judged in the first [GRACE_SECONDS] of a stream,
 * while TCP ramps up and the first keyframes go out; the bitrate drops only
 * after [LOWER_AFTER] bad verdicts in a row, by a quarter to a half towards
 * what went out; it climbs a tenth of the maximum after [RAISE_AFTER_SECONDS]
 * good ones.
 *
 * "Sent" is the app's own transmitted bytes from [TrafficStats], which also
 * counts the TLS and RTMP overhead and the app's other (small) traffic while
 * live; both make the reading err towards "sufficient". Where the device
 * does not report it, no verdict is reached and nothing is regulated.
 */
class UplinkBitrateRegulatorController(
    private val pipelineOutput: IEncodingPipelineOutput,
    private val produced: ProducedBytes,
    dispatcher: CoroutineDispatcher,
    private val onSample: (BandwidthSample) -> Unit,
    /** Logs every sample, for testing on a throttled connection. Debug builds only. */
    private val debugLogging: Boolean = false,
) : IBitrateRegulatorController {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null

    override fun start() {
        job?.cancel()
        job = scope.launch {
            val window = Window()
            var lastProduced = produced.total
            var lastSent = TrafficStats.getUidTxBytes(Process.myUid())
            var sufficient = true
            var insufficientRun = 0
            var sufficientSeconds = 0
            var lastLoweredAt = 0L
            var tick = 0L
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                tick++
                val producedNow = produced.total
                val sentNow = TrafficStats.getUidTxBytes(Process.myUid())
                val producedBits = (producedNow - lastProduced) * 8
                lastProduced = producedNow
                if (sentNow == TrafficStats.UNSUPPORTED.toLong() || lastSent == TrafficStats.UNSUPPORTED.toLong()) {
                    lastSent = sentNow
                    continue
                }
                val sentBits = (sentNow - lastSent) * 8
                lastSent = sentNow

                val videoEncoder =
                    (pipelineOutput as? IConfigurableVideoEncodingPipelineOutput)?.videoEncoder
                        ?: continue
                val audioBitrate =
                    (pipelineOutput as? IConfigurableAudioEncodingPipelineOutput)?.audioEncoder?.bitrate
                        ?: 0
                val maxVideoBitrate = videoEncoder.startBitrate
                val inGrace = tick <= GRACE_SECONDS

                if (inGrace) {
                    // Judged from what comes after, not the ramp-up.
                    window.clear()
                } else {
                    window.add(producedBits, sentBits)
                    if (window.isFull) {
                        if (window.sent < window.produced * INSUFFICIENT_RATIO) {
                            sufficient = false
                            insufficientRun++
                        } else if (window.sent >= window.produced * SUFFICIENT_RATIO) {
                            sufficient = true
                            insufficientRun = 0
                        }
                    }
                }

                try {
                    if (inGrace) {
                        sufficientSeconds = 0
                    } else if (!sufficient) {
                        sufficientSeconds = 0
                        // After a change, the next window still holds the old
                        // rate, so wait one out.
                        if (insufficientRun >= LOWER_AFTER && tick - lastLoweredAt >= WINDOW_SECONDS) {
                            val current = videoEncoder.bitrate
                            val towards = (sentBits - audioBitrate).toInt().coerceAtLeast(current / 2)
                            val target = minOf(current * 3 / 4, towards)
                                .coerceAtLeast(maxVideoBitrate / 10)
                                .coerceAtLeast(MIN_VIDEO_BITRATE)
                            if (target < current) {
                                videoEncoder.bitrate = target
                                lastLoweredAt = tick
                                insufficientRun = 0
                                window.clear()
                            }
                        }
                    } else if (videoEncoder.bitrate < maxVideoBitrate) {
                        sufficientSeconds++
                        if (sufficientSeconds >= RAISE_AFTER_SECONDS) {
                            videoEncoder.bitrate =
                                (videoEncoder.bitrate + maxVideoBitrate / 10).coerceAtMost(maxVideoBitrate)
                            sufficientSeconds = 0
                            window.clear()
                        }
                    } else {
                        sufficientSeconds = 0
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Bitrate change failed", t)
                }

                if (debugLogging) {
                    Log.d(
                        TAG,
                        "[bandwidth] sent=$sentBits produced=$producedBits video=${videoEncoder.bitrate}/$maxVideoBitrate " +
                            "sufficient=$sufficient grace=$inGrace"
                    )
                }
                onSample(
                    BandwidthSample(
                        sufficient = inGrace || sufficient,
                        sentBitsPerSecond = sentBits.toInt(),
                        videoBitrate = videoEncoder.bitrate,
                        maxVideoBitrate = maxVideoBitrate,
                    )
                )
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    /** The last [WINDOW_SECONDS] seconds, summed: one second is too jumpy (a keyframe). */
    private class Window {
        private val producedBits = ArrayDeque<Long>()
        private val sentBits = ArrayDeque<Long>()

        fun add(produced: Long, sent: Long) {
            producedBits.addLast(produced)
            sentBits.addLast(sent)
            if (producedBits.size > WINDOW_SECONDS) {
                producedBits.removeFirst()
                sentBits.removeFirst()
            }
        }

        fun clear() {
            producedBits.clear()
            sentBits.clear()
        }

        val isFull: Boolean
            get() = producedBits.size >= WINDOW_SECONDS

        val produced: Double
            get() = producedBits.sum().toDouble()

        val sent: Double
            get() = sentBits.sum().toDouble()
    }

    class Factory(
        private val produced: ProducedBytes,
        private val onSample: (BandwidthSample) -> Unit,
        private val debugLogging: Boolean = false,
    ) : IBitrateRegulatorController.Factory {
        override fun newBitrateRegulatorController(
            pipelineOutput: IEncodingPipelineOutput,
            coroutineDispatcher: CoroutineDispatcher
        ): IBitrateRegulatorController =
            UplinkBitrateRegulatorController(
                pipelineOutput,
                produced,
                coroutineDispatcher,
                onSample,
                debugLogging
            )
    }

    companion object {
        private const val TAG = "UplinkBitrateRegulator"
        private const val SAMPLE_INTERVAL_MS = 1000L

        /** Seconds summed for a verdict. */
        private const val WINDOW_SECONDS = 3

        /** Seconds after the stream starts that are not judged: TCP ramping up and the first keyframes. */
        private const val GRACE_SECONDS = 8

        /** Bad verdicts in a row before the bitrate drops; the app's warning waits for as many. */
        private const val LOWER_AFTER = 2

        /** Below this share of what was produced getting out, the uplink is not keeping up. */
        private const val INSUFFICIENT_RATIO = 0.85

        /** At or above it, it is again. In between, the last verdict stands. */
        private const val SUFFICIENT_RATIO = 0.95

        /** Seconds of keeping up before the bitrate climbs a tenth of the way back. HaishinKit's 15. */
        private const val RAISE_AFTER_SECONDS = 15

        /** The floor: below it, the picture is not worth sending. */
        private const val MIN_VIDEO_BITRATE = 300_000
    }
}
