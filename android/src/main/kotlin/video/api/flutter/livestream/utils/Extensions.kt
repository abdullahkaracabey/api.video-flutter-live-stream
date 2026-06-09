package video.api.flutter.livestream.utils

import android.media.AudioFormat
import android.util.Size
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig

/**
 * Dart side passes resolution as a label ("720p"). We resolve it to a
 * pixel [Size] here. Kept identical to the 2.x mapping so the Dart
 * wrapper's behaviour is unchanged.
 */
fun String.toResolution(): Size {
    return when (this) {
        "240p" -> Size(426, 240)
        "360p" -> Size(640, 360)
        "480p" -> Size(854, 480)
        "720p" -> Size(1280, 720)
        "1080p" -> Size(1920, 1080)
        else -> throw IllegalArgumentException("Unknown resolution: $this")
    }
}

/**
 * Map the Dart-side method-channel arguments to StreamPack 3.x's
 * [VideoConfig] (typealias for `VideoCodecConfig`).
 *
 * Note: 3.x's [VideoConfig] does NOT take `gopDuration` via the public
 * constructor in the same form as 2.x — it accepts `gopDurationInS`
 * with a default of 1f. The Dart side currently doesn't pass a custom
 * value, so we let it default.
 */
fun Map<String, Any>.toVideoConfig(): VideoConfig {
    return VideoConfig(
        startBitrate = this["bitrate"] as Int,
        resolution = (this["resolution"] as String).toResolution(),
        fps = this["fps"] as Int
    )
}

/**
 * Map the Dart-side method-channel arguments to StreamPack 3.x's
 * [AudioConfig] (typealias for `AudioCodecConfig`).
 *
 * In 2.x the `enableEchoCanceler` / `enableNoiseSuppressor` flags
 * lived on AudioConfig. In 3.x they moved onto the [MicrophoneSourceFactory]
 * (as a `Set<UUID>` of audio effects). We default to the standard
 * `defaultAudioEffects` (AEC + NS) in the view layer — the boolean
 * fields are read by [FlutterLiveStreamView] when constructing the
 * factory.
 */
fun Map<String, Any>.toAudioConfig(): AudioConfig {
    val channel = if (this["channel"] == "stereo") {
        AudioFormat.CHANNEL_IN_STEREO
    } else {
        AudioFormat.CHANNEL_IN_MONO
    }
    return AudioConfig(
        startBitrate = this["bitrate"] as Int,
        sampleRate = this["sampleRate"] as Int,
        channelConfig = channel
    )
}

/**
 * Read the AEC/NS flags from the raw map so [FlutterLiveStreamView]
 * can build the matching `MicrophoneSourceFactory` effects set. Kept
 * here next to [toAudioConfig] for cohesion.
 */
data class AudioEffectFlags(val echoCanceler: Boolean, val noiseSuppressor: Boolean)

fun Map<String, Any>.toAudioEffectFlags(): AudioEffectFlags = AudioEffectFlags(
    echoCanceler = this["enableEchoCanceler"] as? Boolean ?: true,
    noiseSuppressor = this["enableNoiseSuppressor"] as? Boolean ?: true
)

/**
 * Add a slash at the end of a [String] only if it is missing.
 *
 * @return the given string with a trailing slash.
 */
fun String.addTrailingSlashIfNeeded(): String {
    return if (this.endsWith("/")) this else "$this/"
}
