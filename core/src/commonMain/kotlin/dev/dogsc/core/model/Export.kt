package dev.dogsc.core.model

import kotlin.math.max
import kotlin.math.min

object ExportEncodingPolicy {
    const val AudioBitrate = 192_000

    fun videoBitrate(width: Int, height: Int, frameRate: Int): Int {
        val pixels = max(width * height, 1).toLong()
        val ceiling = when {
            pixels >= 7_000_000 -> 130_000_000L
            pixels >= 3_500_000 -> 100_000_000L
            else -> 65_000_000L
        }
        return min(max(pixels * max(frameRate, 1) / 6, 12_000_000L), ceiling).toInt()
    }
}

data class RequiredProjectMedia(
    val screenVideo: Boolean,
    val systemAudio: Boolean,
    val cameraVideo: Boolean,
    val microphoneAudio: Boolean,
    val backgroundImage: Boolean,
    val backgroundVideo: Boolean,
) {
    companion object {
        fun from(project: RecorderProject): RequiredProjectMedia {
            val segmentIDs = when (val sequence = project.timeline.sourceSequence) {
                SourceSequence.FullRecording -> listOf(TimelineMap.FullRecordingSegmentId)
                is SourceSequence.Edited -> sequence.segments.map { it.id }
            }
            val systemAudio = segmentIDs.any { id ->
                val overrides = project.timeline.primarySegmentAudioOverrides[id]
                val muted = overrides?.isSystemMuted ?: project.audio.isSystemMuted
                val volume = overrides?.systemVolume ?: project.audio.systemVolume
                !muted && volume > 0
            }
            val microphoneAudio = project.media?.microphone != null && segmentIDs.any { id ->
                val overrides = project.timeline.primarySegmentAudioOverrides[id]
                val muted = overrides?.isMicrophoneMuted ?: project.audio.isMicrophoneMuted
                val volume = overrides?.microphoneVolume ?: project.audio.microphoneVolume
                !muted && volume > 0
            }
            return RequiredProjectMedia(
                screenVideo = true,
                systemAudio = systemAudio,
                cameraVideo = project.media?.camera != null && !project.camera.isHidden,
                microphoneAudio = microphoneAudio,
                backgroundImage = project.canvas.backgroundSource.isImage,
                backgroundVideo = project.canvas.backgroundSource.isVideo,
            )
        }
    }
}

data class ExportEstimate(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val durationSeconds: Double,
    val videoBitrate: Int,
    val estimatedBytes: Long,
)

object ExportEstimator {
    fun estimate(project: RecorderProject, durationSeconds: Double, sourceAspectRatio: Double): ExportEstimate {
        val dimensions = project.canvas.pixelDimensions(
            project.exportSettings.resolution,
            sourceAspectRatio,
            null,
        )
        val bitrate = ExportEncodingPolicy.videoBitrate(
            dimensions.width,
            dimensions.height,
            project.exportSettings.frameRate.value,
        )
        val bytes = ((bitrate + ExportEncodingPolicy.AudioBitrate).toDouble() / 8 * durationSeconds).toLong()
        return ExportEstimate(
            dimensions.width,
            dimensions.height,
            project.exportSettings.frameRate.value,
            durationSeconds,
            bitrate,
            bytes,
        )
    }
}
