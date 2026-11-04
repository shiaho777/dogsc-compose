package dev.dogsc.app.android

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import dev.dogsc.core.model.MediaTimelinePlacement
import dev.dogsc.core.model.ProjectMediaReference
import dev.dogsc.core.model.RecorderProject
import dev.dogsc.core.model.TimelineMap
import dev.dogsc.core.model.TimelineMediaPlan
import dev.dogsc.core.model.TimelineMediaSlice
import java.io.File

/**
 * Timeline audio for the editor preview. Cuts are honoured by one clipped ExoPlayer item per
 * timeline slice; a uniform per-segment speed becomes the player's playback rate. Mixed speeds
 * fall back to 1× (documented in PARITY) because ExoPlayer's rate is player-wide.
 */
class AndroidPreviewAudio(
    private val context: Context,
    private val repository: AndroidProjectRepository,
) {
    private var player: ExoPlayer? = null
    private var slices: List<TimelineMediaSlice> = emptyList()

    fun start(project: RecorderProject, sourceDuration: Double, outputTime: Double) {
        val prepared = prepare(project, sourceDuration) ?: return
        val (items, planSlices) = prepared
        val exo = player ?: ExoPlayer.Builder(context).build().also { player = it }
        slices = planSlices
        if (exo.mediaItemCount != items.size || exo.currentMediaItemIndex >= items.size) {
            exo.setMediaItems(items)
            // setMediaItems leaves the player idle; without prepare() playWhenReady does nothing.
            exo.prepare()
        }
        exo.volume = volumeOf(project)
        val rate = uniformRate(planSlices)
        exo.playbackParameters = PlaybackParameters(rate)
        val index = planSlices.indexOfLast { it.outputStart <= outputTime }.coerceAtLeast(0)
        val offset = ((outputTime - planSlices[index].outputStart) * 1000).toLong().coerceAtLeast(0)
        exo.seekTo(index, offset)
        exo.playWhenReady = true
    }

    fun seek(project: RecorderProject, sourceDuration: Double, outputTime: Double) {
        val exo = player ?: return
        if (!exo.playWhenReady) return
        start(project, sourceDuration, outputTime)
    }

    fun pause() {
        player?.playWhenReady = false
    }

    fun release() {
        player?.release()
        player = null
        slices = emptyList()
    }

    private fun volumeOf(project: RecorderProject): Float {
        val system = if (project.audio.isSystemMuted) 0.0 else project.audio.systemVolume
        val mic = if (project.audio.isMicrophoneMuted) 0.0 else project.audio.microphoneVolume
        // One preview stream cannot mix; prefer the louder audible source.
        return maxOf(system, mic).toFloat().coerceIn(0f, 1f)
    }

    private fun uniformRate(slices: List<TimelineMediaSlice>): Float {
        val first = slices.firstOrNull()?.sourceTimeScale ?: 1.0
        return if (slices.all { kotlin.math.abs(it.sourceTimeScale - first) < 0.001 }) {
            first.toFloat().coerceIn(0.25f, 4f)
        } else 1f
    }

    private fun prepare(
        project: RecorderProject,
        sourceDuration: Double,
    ): Pair<List<MediaItem>, List<TimelineMediaSlice>>? {
        val dir = repository.projectDirectory(project.id)
        val system = project.media?.systemAudio?.let { File(dir, it.relativePath) }?.takeIf { it.exists() }
        val microphone = project.media?.microphone?.let { File(dir, it.relativePath) }?.takeIf { it.exists() }
        val screen = project.media?.screen?.let { File(dir, it.relativePath) }?.takeIf { it.exists() }
        val reference: ProjectMediaReference
        val file: File
        val useSystem = system != null && !project.audio.isSystemMuted && project.audio.systemVolume > 0.001
        val useMic = microphone != null && !project.audio.isMicrophoneMuted && project.audio.microphoneVolume > 0.001
        when {
            useSystem -> { file = system!!; reference = project.media!!.systemAudio!! }
            useMic -> { file = microphone!!; reference = project.media!!.microphone!! }
            screen != null -> {
                file = screen
                reference = project.media?.screen ?: return null
            }
            else -> return null
        }
        val timelineMap = runCatching { TimelineMap(project.timeline.sourceSequence, sourceDuration) }.getOrNull()
            ?: return null
        val placement = MediaTimelinePlacement.from(
            reference,
            sourceAvailableDuration = sourceDuration,
            timelineDuration = sourceDuration,
        )
        val plan = TimelineMediaPlan.auxiliary(timelineMap, placement)
        if (plan.slices.isEmpty()) return null
        val items = plan.slices.map { slice ->
            MediaItem.Builder()
                .setUri(Uri.fromFile(file))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs((slice.sourceStart * 1000).toLong().coerceAtLeast(0))
                        .setEndPositionMs((slice.sourceEnd * 1000).toLong().coerceAtLeast(1))
                        .build(),
                )
                .build()
        }
        return items to plan.slices
    }
}
