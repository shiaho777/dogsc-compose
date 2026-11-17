package dev.dogsc.app.android

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
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
 * timeline slice. System and microphone are separate players so the preview mixes the same two
 * sources with the same per-segment gains as the export mixer.
 */
class AndroidPreviewAudio(
    private val context: Context,
    private val repository: AndroidProjectRepository,
) {
    private enum class TrackKind { SYSTEM, MICROPHONE }

    private inner class Track(val kind: TrackKind) {
        var player: ExoPlayer? = null
        var slices: List<TimelineMediaSlice> = emptyList()
        var rates: List<Float> = emptyList()
        var signature: String? = null
    }

    private data class PreparedTrack(
        val items: List<MediaItem>,
        val slices: List<TimelineMediaSlice>,
        val rates: List<Float>,
        val signature: String,
    )

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val systemTrack = Track(TrackKind.SYSTEM)
    private val microphoneTrack = Track(TrackKind.MICROPHONE)
    private var activeProject: RecorderProject? = null
    private var wantsPlayback = false
    private var focusRequest: AudioFocusRequest? = null
    private var ducked = false
    private var focusLost = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                focusLost = true
                systemTrack.player?.pause()
                microphoneTrack.player?.pause()
                // Permanent loss has no matching gain event; re-request on the next play/seek.
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Keep wantsPlayback so the audio can resume when focus returns.
                focusLost = true
                systemTrack.player?.pause()
                microphoneTrack.player?.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                ducked = true
                applyVolumes()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                ducked = false
                focusLost = false
                if (wantsPlayback) {
                    systemTrack.player?.play()
                    microphoneTrack.player?.play()
                }
                applyVolumes()
            }
        }
    }

    fun start(project: RecorderProject, sourceDuration: Double, outputTime: Double) {
        val timelineMap = runCatching {
            TimelineMap(project.timeline.sourceSequence, sourceDuration)
        }.getOrNull() ?: return
        val prepared = prepare(project, sourceDuration, timelineMap)
        if (prepared.first == null && prepared.second == null) {
            systemTrack.player?.pause()
            microphoneTrack.player?.pause()
            return
        }
        activeProject = project
        wantsPlayback = true
        requestAudioFocus()
        applyTrack(systemTrack, prepared.first, outputTime)
        applyTrack(microphoneTrack, prepared.second, outputTime)
    }

    fun seek(project: RecorderProject, sourceDuration: Double, outputTime: Double) {
        if (!wantsPlayback) return
        start(project, sourceDuration, outputTime)
    }

    fun refreshVolume(project: RecorderProject, outputTime: Double) {
        activeProject = project
        applyVolumes()
    }

    fun pause() {
        wantsPlayback = false
        focusLost = false
        systemTrack.player?.pause()
        microphoneTrack.player?.pause()
        abandonAudioFocus()
    }

    fun release() {
        wantsPlayback = false
        abandonAudioFocus()
        releaseTrack(systemTrack)
        releaseTrack(microphoneTrack)
        activeProject = null
    }

    private fun applyTrack(track: Track, prepared: PreparedTrack?, outputTime: Double) {
        if (prepared == null) {
            track.player?.let { player ->
                player.pause()
                runCatching { player.stop() }
                runCatching { player.clearMediaItems() }
            }
            track.slices = emptyList()
            track.rates = emptyList()
            track.signature = null
            return
        }
        val exo = ensurePlayer(track)
        track.slices = prepared.slices
        track.rates = prepared.rates
        // Compare the actual clip plan, not just the item count: retiming a cut can keep the
        // same number of slices while every source range (and therefore every item) changes.
        if (prepared.signature != track.signature || exo.mediaItemCount != prepared.items.size) {
            exo.setMediaItems(prepared.items)
            exo.prepare()
            track.signature = prepared.signature
        }
        val index = prepared.slices.indexOfLast { it.outputStart <= outputTime }.coerceAtLeast(0)
        exo.playbackParameters = PlaybackParameters(prepared.rates.getOrElse(index) { 1f })
        exo.volume = gain(activeProject, prepared.slices.getOrNull(index), track.kind)
        val offset = ((outputTime - prepared.slices[index].outputStart) * 1000).toLong().coerceAtLeast(0)
        exo.seekTo(index, offset)
        exo.playWhenReady = wantsPlayback && !focusLost
    }

    private fun ensurePlayer(track: Track): ExoPlayer {
        track.player?.let { return it }
        val created = ExoPlayer.Builder(context).build().also { exo ->
            exo.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                false,
            )
            exo.setHandleAudioBecomingNoisy(true)
            exo.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val index = exo.currentMediaItemIndex
                    track.rates.getOrNull(index)?.let { exo.playbackParameters = PlaybackParameters(it) }
                    exo.volume = gain(
                        activeProject,
                        track.slices.getOrNull(index),
                        track.kind,
                    )
                }
            })
        }
        track.player = created
        return created
    }

    private fun releaseTrack(track: Track) {
        track.player?.release()
        track.player = null
        track.slices = emptyList()
        track.rates = emptyList()
        track.signature = null
    }

    private fun applyVolumes() {
        val project = activeProject ?: return
        for (track in listOf(systemTrack, microphoneTrack)) {
            val exo = track.player ?: continue
            val index = exo.currentMediaItemIndex
            exo.volume = gain(project, track.slices.getOrNull(index), track.kind)
        }
    }

    private fun gain(
        project: RecorderProject?,
        slice: TimelineMediaSlice?,
        kind: TrackKind,
    ): Float {
        if (project == null || slice == null) return 0f
        val overrides = project.timeline.primarySegmentAudioOverrides[slice.segmentID]
        val muted = if (kind == TrackKind.SYSTEM) {
            overrides?.isSystemMuted ?: project.audio.isSystemMuted
        } else {
            overrides?.isMicrophoneMuted ?: project.audio.isMicrophoneMuted
        }
        val volume = if (kind == TrackKind.SYSTEM) {
            overrides?.systemVolume ?: project.audio.systemVolume
        } else {
            overrides?.microphoneVolume ?: project.audio.microphoneVolume
        }
        if (muted) return 0f
        return (volume.coerceIn(0.0, 1.0) * if (ducked) 0.2 else 1.0).toFloat()
    }

    private fun requestAudioFocus() {
        if (focusRequest != null) return
        focusLost = false
        val attributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper()))
            .build()
        if (audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = request
        }
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        ducked = false
    }

    private fun prepare(
        project: RecorderProject,
        sourceDuration: Double,
        timelineMap: TimelineMap,
    ): Pair<PreparedTrack?, PreparedTrack?> {
        val dir = repository.projectDirectory(project.id)
        val systemFile = project.media?.systemAudio?.let { File(dir, it.relativePath) }
            ?.takeIf { it.exists() }
        val microphoneFile = project.media?.microphone?.let { File(dir, it.relativePath) }
            ?.takeIf { it.exists() }
        val screenFile = project.media?.screen?.let { File(dir, it.relativePath) }
            ?.takeIf { it.exists() }

        val system = if (systemFile != null) {
            makeTrack(systemFile, project.media?.systemAudio, sourceDuration, timelineMap, false)
        } else if (screenFile != null) {
            makeTrack(screenFile, project.media?.screen, sourceDuration, timelineMap, true)
        } else {
            null
        }
        val microphone = if (microphoneFile != null && microphoneFile != systemFile) {
            makeTrack(microphoneFile, project.media?.microphone, sourceDuration, timelineMap, false)
        } else {
            null
        }
        return system to microphone
    }

    private fun makeTrack(
        file: File,
        reference: ProjectMediaReference?,
        sourceDuration: Double,
        timelineMap: TimelineMap,
        primary: Boolean,
    ): PreparedTrack? {
        val plan = if (primary) {
            TimelineMediaPlan.primary(timelineMap)
        } else {
            val placement = MediaTimelinePlacement.from(
                reference ?: ProjectMediaReference(file.name),
                sourceAvailableDuration = sourceDuration,
                timelineDuration = sourceDuration,
            )
            TimelineMediaPlan.auxiliary(
                timelineMap,
                placement,
                syncAnchors = reference?.syncAnchors ?: emptyList(),
            )
        }
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
        val rates = plan.slices.map { it.sourceTimeScale.toFloat().coerceIn(0.25f, 8f) }
        val signature = buildString {
            append(file.absolutePath).append('|').append(primary)
            plan.slices.forEach {
                append('|').append(it.sourceStart).append(':').append(it.sourceEnd)
                    .append(':').append(it.sourceTimeScale)
            }
        }
        return PreparedTrack(items, plan.slices, rates, signature)
    }
}
