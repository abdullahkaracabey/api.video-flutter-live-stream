package video.api.flutter.livestream

import android.Manifest
import android.content.Context
import android.media.audiofx.AudioEffect
import android.util.Log
import android.util.Size
import android.view.Surface
import io.flutter.view.TextureRegistry
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.backCameras
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.cameraManager
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.defaultCameraId
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.externalCameras
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.frontCameras
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.isBackCamera
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.isExternalCamera
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.isFrontCamera
import io.github.thibaultbee.streampack.core.interfaces.setCameraId
import io.github.thibaultbee.streampack.core.interfaces.startPreview
import io.github.thibaultbee.streampack.core.interfaces.startStream
import io.github.thibaultbee.streampack.core.interfaces.stopPreview
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import io.github.thibaultbee.streampack.ext.rtmp.configuration.mediadescriptor.RtmpMediaDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import video.api.flutter.livestream.utils.AudioEffectFlags

/**
 * Bridge between the Dart-side `ApiVideoLiveStreamController` and
 * StreamPack 3.x's [SingleStreamer].
 *
 * The class preserves the public surface from the v1.2.0 plugin
 * (callbacks for connection succeeded / disconnected / failed /
 * generic error / video size change) so the Dart wrapper stays
 * untouched. Internally we wire the 3.x reactive flows
 * ([SingleStreamer.isOpenFlow], [SingleStreamer.throwableFlow])
 * into those callbacks.
 *
 * Key semantic mappings vs. the 2.x [io.github.thibaultbee.streampack.ext.rtmp.streamers.CameraRtmpLiveStreamer]:
 * - `streamer.startStream(descriptor)` (suspend) completes only once
 *   the RTMP handshake succeeds → fire [onConnectionSucceeded]. Throw
 *   → fire [onConnectionFailed] and rethrow.
 * - `isOpenFlow` going `true` → `false` while we did NOT initiate the
 *   stop ourselves is reported as [onDisconnected] (server drop /
 *   network failure).
 * - Non-null emission on `throwableFlow` is reported as
 *   [onGenericError] (encoder / pipeline errors).
 *
 * In 3.x the streamer ships with NO default video source. The 2.x
 * `CameraRtmpLiveStreamer` constructor used to wire the default camera;
 * to keep the Dart side oblivious to this we lazy-attach the default
 * camera on the first [startPreview] or [setCameraPosition] call.
 */
class FlutterLiveStreamView(
    private val context: Context,
    textureRegistry: TextureRegistry,
    private val permissionsManager: PermissionsManager,
    private val onConnectionSucceeded: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onConnectionFailed: (String) -> Unit,
    private val onGenericError: (Exception) -> Unit,
    private val onVideoSizeChanged: (Size) -> Unit,
) {
    private val flutterTexture = textureRegistry.createSurfaceTexture()
    val textureId: Long
        get() = flutterTexture.id()

    private val streamer = SingleStreamer(
        context = context,
        withAudio = true,
        withVideo = true
    )

    /**
     * Scope used to collect StreamPack 3.x flows. SupervisorJob keeps
     * the collectors independent — a throw in one doesn't cancel the
     * others. Cancelled in [dispose].
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Set to true around our own [stopStream] call so the `isOpenFlow`
     * collector knows the resulting `false` transition is intentional
     * and should NOT be reported as a disconnect.
     */
    @Volatile
    private var userInitiatedStop = false

    /**
     * True between a successful [startStream] and the next stop —
     * gates the `isOpenFlow` transition logic so we don't fire
     * `onDisconnected` for the initial false state on first construction.
     */
    @Volatile
    private var hasBeenOpened = false

    private var _isPreviewing = false
    private var _isStreaming = false
    val isStreaming: Boolean
        get() = _isStreaming

    private var _videoConfig: VideoConfig? = null
    val videoConfig: VideoConfig
        get() = _videoConfig!!

    private var _audioConfig: AudioConfig? = null
    private var _audioEffects: AudioEffectFlags = AudioEffectFlags(echoCanceler = true, noiseSuppressor = true)

    /**
     * Cached camera id. In 3.x the camera id lives on the
     * [ICameraSource], not on the streamer itself.
     */
    private var _currentCameraId: String? = null
    val camera: String
        get() = _currentCameraId
            ?: ((streamer.videoInput?.sourceFlow?.value as? ICameraSource)?.cameraId
                ?: context.cameraManager.defaultCameraId)

    init {
        // isOpenFlow: track connection lifecycle. Drop the initial
        // value so the first emission (false, before we ever opened)
        // is ignored.
        scope.launch {
            streamer.isOpenFlow
                .drop(1)
                .collect { isOpen ->
                    if (isOpen) {
                        hasBeenOpened = true
                    } else if (hasBeenOpened && !userInitiatedStop) {
                        _isStreaming = false
                        onDisconnected()
                        hasBeenOpened = false
                    }
                }
        }
        // throwableFlow: surface pipeline / encoder errors.
        scope.launch {
            streamer.throwableFlow
                .filterNotNull()
                .collect { t ->
                    _isStreaming = false
                    val ex = (t as? Exception)
                        ?: Exception(t.message ?: t::class.java.simpleName, t)
                    onGenericError(ex)
                }
        }
    }

    fun setVideoConfig(
        videoConfig: VideoConfig,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (isStreaming) {
            onError(UnsupportedOperationException("You have to stop streaming first"))
            return
        }

        onVideoSizeChanged(videoConfig.resolution)

        val wasPreviewing = _isPreviewing
        if (wasPreviewing) {
            stopPreview()
        }

        scope.launch {
            try {
                streamer.setVideoConfig(videoConfig)
                _videoConfig = videoConfig
                if (wasPreviewing) {
                    startPreviewInternal(onSuccess, onError)
                } else {
                    onSuccess()
                }
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    /**
     * Update the audio effects requested by Dart (AEC / NS). Stored
     * separately so the next [setAudioConfig] can rebuild the
     * microphone source factory with the right effects set.
     */
    fun setAudioEffects(flags: AudioEffectFlags) {
        _audioEffects = flags
    }

    fun setAudioConfig(
        audioConfig: AudioConfig,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (isStreaming) {
            onError(UnsupportedOperationException("You have to stop streaming first"))
            return
        }

        permissionsManager.requestPermission(
            Manifest.permission.RECORD_AUDIO,
            onGranted = {
                scope.launch {
                    try {
                        // (Re)attach the microphone source so the
                        // effects set reflects the latest Dart-side
                        // AEC/NS flags.
                        streamer.setAudioSource(MicrophoneSourceFactory(effects = buildEffectsSet()))
                        streamer.setAudioConfig(audioConfig)
                        _audioConfig = audioConfig
                        onSuccess()
                    } catch (e: Exception) {
                        onError(e)
                    }
                }
            },
            onShowPermissionRationale = { _ ->
                onError(SecurityException("Missing permission Manifest.permission.RECORD_AUDIO"))
            },
            onDenied = {
                onError(SecurityException("Missing permission Manifest.permission.RECORD_AUDIO"))
            })
    }

    private fun buildEffectsSet(): Set<java.util.UUID> {
        val effects = mutableSetOf<java.util.UUID>()
        if (_audioEffects.echoCanceler) effects.add(AudioEffect.EFFECT_TYPE_AEC)
        if (_audioEffects.noiseSuppressor) effects.add(AudioEffect.EFFECT_TYPE_NS)
        return effects
    }

    var isMuted: Boolean
        get() = streamer.audioInput?.isMuted ?: false
        set(value) {
            streamer.audioInput?.isMuted = value
        }

    fun setCamera(camera: String, onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        permissionsManager.requestPermission(
            Manifest.permission.CAMERA,
            onGranted = {
                scope.launch {
                    try {
                        if (streamer.videoInput?.sourceFlow?.value == null) {
                            streamer.setVideoSource(CameraSourceFactory(camera))
                        } else {
                            streamer.setCameraId(camera)
                        }
                        _currentCameraId = camera
                        onSuccess()
                    } catch (e: Exception) {
                        onError(e)
                    }
                }
            },
            onShowPermissionRationale = { _ ->
                onError(SecurityException("Missing permission Manifest.permission.CAMERA"))
            },
            onDenied = {
                onError(SecurityException("Missing permission Manifest.permission.CAMERA"))
            })
    }

    val cameraPosition: String
        get() {
            val current = camera
            val cm = context.cameraManager
            return when {
                cm.isFrontCamera(current) -> "front"
                cm.isBackCamera(current) -> "back"
                cm.isExternalCamera(current) -> "other"
                else -> throw IllegalArgumentException("Invalid camera position for camera $current")
            }
        }

    fun setCameraPosition(position: String, onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        val cm = context.cameraManager
        val cameraList = when (position) {
            "front" -> cm.frontCameras
            "back" -> cm.backCameras
            "other" -> cm.externalCameras
            else -> {
                onError(IllegalArgumentException("Invalid camera position: $position"))
                return
            }
        }
        if (cameraList.isEmpty()) {
            onError(IllegalStateException("No camera available for position: $position"))
            return
        }
        setCamera(cameraList.first(), onSuccess, onError)
    }

    fun dispose() {
        scope.launch {
            try {
                if (_isStreaming) {
                    userInitiatedStop = true
                    streamer.stopStream()
                    streamer.close()
                }
                try {
                    streamer.stopPreview()
                } catch (_: Throwable) {
                }
                streamer.release()
            } catch (e: Exception) {
                Log.w(TAG, "dispose error", e)
            } finally {
                try {
                    flutterTexture.release()
                } catch (_: Throwable) {
                }
                scope.cancel()
            }
        }
    }

    fun startStream(url: String) {
        runBlocking {
            try {
                userInitiatedStop = false
                val descriptor = RtmpMediaDescriptor.fromUrl(url)
                streamer.startStream(descriptor)
                _isStreaming = true
                hasBeenOpened = true
                onConnectionSucceeded()
            } catch (e: Exception) {
                _isStreaming = false
                try {
                    streamer.close()
                } catch (_: Throwable) {
                }
                onConnectionFailed("Failed to start stream: ${e.message}")
                throw e
            }
        }
    }

    fun stopStream() {
        runBlocking {
            val wasOpen = streamer.isOpenFlow.value
            userInitiatedStop = true
            try {
                streamer.stopStream()
                streamer.close()
            } catch (e: Exception) {
                Log.w(TAG, "stopStream failed", e)
            } finally {
                _isStreaming = false
                // Preserve the legacy contract where stopStream
                // synchronously fires onDisconnected so the Dart
                // wrapper can transition state without waiting for the
                // async isOpenFlow emission.
                if (wasOpen) {
                    onDisconnected()
                }
                userInitiatedStop = false
                hasBeenOpened = false
            }
        }
    }

    fun startPreview(onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        permissionsManager.requestPermission(
            Manifest.permission.CAMERA,
            onGranted = {
                scope.launch {
                    startPreviewInternal(onSuccess, onError)
                }
            },
            onShowPermissionRationale = { _ ->
                onError(SecurityException("Missing permission Manifest.permission.CAMERA"))
            },
            onDenied = {
                onError(SecurityException("Missing permission Manifest.permission.CAMERA"))
            })
    }

    private suspend fun startPreviewInternal(
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            val resolution = _videoConfig?.resolution
                ?: throw IllegalStateException("Video has not been configured!")
            // Lazy-attach the camera source on first preview if Dart
            // never explicitly called setCamera / setCameraPosition.
            // The 2.x CameraRtmpLiveStreamer wired the default camera
            // in its constructor; SingleStreamer doesn't.
            if (streamer.videoInput?.sourceFlow?.value == null) {
                val cameraId = _currentCameraId ?: context.cameraManager.defaultCameraId
                streamer.setVideoSource(CameraSourceFactory(cameraId))
                _currentCameraId = cameraId
            }
            streamer.startPreview(getSurface(resolution))
            _isPreviewing = true
            onSuccess()
        } catch (e: Exception) {
            onError(e)
        }
    }

    fun stopPreview() {
        scope.launch {
            try {
                streamer.stopPreview()
            } catch (e: Exception) {
                Log.w(TAG, "stopPreview failed", e)
            } finally {
                _isPreviewing = false
            }
        }
    }

    private fun getSurface(resolution: Size): Surface {
        val surfaceTexture = flutterTexture.surfaceTexture().apply {
            setDefaultBufferSize(
                resolution.width,
                resolution.height
            )
        }
        return Surface(surfaceTexture)
    }

    companion object {
        private const val TAG = "FlutterLiveStreamView"
    }
}
