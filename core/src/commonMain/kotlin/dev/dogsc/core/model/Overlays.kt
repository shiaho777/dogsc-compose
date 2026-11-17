package dev.dogsc.core.model

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object ElementMotionEvaluator {
    /**
     * Evaluates a normalized progress value without depending on any render
     * backend. Exit animation passes remaining progress (1 -> 0), so the same
     * curve naturally settles at the target before accelerating away.
     */
    fun progress(linearProgress: Double, curve: ElementMotionCurve): Double {
        val x = linearProgress.coerceIn(0.0, 1.0)
        return when (curve) {
            ElementMotionCurve.SMOOTH -> x * x * x * (x * (x * 6 - 15) + 10)
            ElementMotionCurve.SWIFT -> 1 - (1 - x).pow(4)
            ElementMotionCurve.GENTLE -> 1 - (1 - x).pow(3)
        }
    }
}

@Serializable
data class OverlayTiming(
    val startTime: Double,
    val duration: Double = 3.0,
) {
    init {
        require(startTime >= 0)
        require(duration > 0)
    }

    val endTime: Double get() = startTime + duration
    fun contains(time: Double): Boolean = time >= startTime && time < endTime
}

@Serializable
data class MosaicClip(
    val id: String = randomId(),
    val timing: OverlayTiming,
    val sourceRect: NormalizedOverlayRect = NormalizedOverlayRect.Centered,
    /** Normalized against the shorter side of the redaction rectangle. */
    val cornerRadius: Double = 0.08,
    val style: MosaicEffectStyle = MosaicEffectStyle.BLUR,
    /** Product-facing 0...1 strength; renderers lower it to pixels. */
    val intensity: Double = 0.55,
    /** Black overlay outside a spotlight focus. Ignored by ordinary blur. */
    val spotlightDimming: Double = 0.22,
    val transitionStyle: MosaicTransitionStyle = MosaicTransitionStyle.NONE,
    val transitionInDuration: Double = 0.30,
    val transitionOutDuration: Double = 0.30,
)

@Serializable
data class StickerClip(
    val id: String = randomId(),
    val timing: OverlayTiming,
    val relativePath: String,
    val position: NormalizedPoint = NormalizedPoint.Center,
    /** Width relative to the output canvas. Height follows the image aspect. */
    val width: Double = 0.38,
    val rotationDegrees: Double = 0.0,
    val opacity: Double = 1.0,
    val cornerRadius: Double = 18.0,
    val borderWidth: Double = 0.0,
    val borderColor: HexColor = HexColor.White,
    val shadowOpacity: Double = 0.28,
    val shadowRadius: Double = 24.0,
    val shadowOffsetX: Double = 0.0,
    val shadowOffsetY: Double = 10.0,
    val animation: StickerAnimationPreset = StickerAnimationPreset.POP,
    val animationCurve: ElementMotionCurve = ElementMotionCurve.SWIFT,
    val exitAnimation: StickerAnimationPreset? = null,
    val enterDuration: Double = 0.7,
    val exitDuration: Double = 0.22,
    val backdropBlur: Double = 20.0,
    val backdropBlurIncludesCamera: Boolean = false,
    val hidesScreen: Boolean = false,
    val hidesCamera: Boolean = false,
    val layerIndex: Int = 0,
)

data class FrameStickerBackdropScene(
    val screenSuppression: Double = 0.0,
    val cameraSuppression: Double = 0.0,
    val screenBlur: Double = 0.0,
    val cameraBlur: Double = 0.0,
) {
    fun merged(other: FrameStickerBackdropScene): FrameStickerBackdropScene = FrameStickerBackdropScene(
        screenSuppression = max(screenSuppression, other.screenSuppression),
        cameraSuppression = max(cameraSuppression, other.cameraSuppression),
        screenBlur = max(screenBlur, other.screenBlur),
        cameraBlur = max(cameraBlur, other.cameraBlur),
    )

    fun interpolatedTo(other: FrameStickerBackdropScene, progress: Double): FrameStickerBackdropScene =
        FrameStickerBackdropScene(
            screenSuppression = screenSuppression + (other.screenSuppression - screenSuppression) * progress,
            cameraSuppression = cameraSuppression + (other.cameraSuppression - cameraSuppression) * progress,
            screenBlur = screenBlur + (other.screenBlur - screenBlur) * progress,
            cameraBlur = cameraBlur + (other.cameraBlur - cameraBlur) * progress,
        )

    companion object {
        fun forClip(clip: StickerClip, visibility: Double): FrameStickerBackdropScene =
            FrameStickerBackdropScene(
                screenSuppression = if (clip.hidesScreen) visibility else 0.0,
                cameraSuppression = if (clip.hidesCamera) visibility else 0.0,
                screenBlur = if (clip.hidesScreen) 0.0 else max(clip.backdropBlur, 0.0) * visibility,
                cameraBlur = if (clip.backdropBlurIncludesCamera) max(clip.backdropBlur, 0.0) * visibility else 0.0,
            )
    }
}

/**
 * Prepared once per sticker edit, then shared by paused preview, playback and
 * export. Crossing stickers keep individual motion but bridge shared backdrop
 * treatment.
 */
class StickerTransitionTrack(clips: List<StickerClip>) {
    private data class Entry(
        val clip: StickerClip,
        val enterDuration: Double,
        val exitDuration: Double,
        var entryJoin: Int? = null,
        var exitJoin: Int? = null,
    ) {
        val joinEnterDuration: Double get() = if (clip.animation == StickerAnimationPreset.NONE) 0.0 else enterDuration
        val joinExitDuration: Double
            get() = if ((clip.exitAnimation ?: clip.animation.automaticExit) == StickerAnimationPreset.NONE) 0.0 else exitDuration
    }

    private data class Endpoint(val time: Double, val index: Int, val isStart: Boolean)

    private data class Join(
        val start: Double,
        val end: Double,
        val outgoingBackdrop: FrameStickerBackdropScene,
        val incomingBackdrop: FrameStickerBackdropScene,
        val curve: ElementMotionCurve,
    ) {
        fun contains(time: Double): Boolean = time >= start && time < end
        fun progress(at: Double): Double = if (end <= start) {
            if (at >= end) 1.0 else 0.0
        } else {
            ((at - start) / (end - start)).coerceIn(0.0, 1.0)
        }
    }

    private data class BackdropBridge(
        val start: Double,
        val end: Double,
        val treatment: FrameStickerBackdropScene,
    ) {
        fun contains(time: Double): Boolean = time >= start && time < end
    }

    data class Sample(
        val clip: StickerClip,
        val visibility: Double,
        val preset: StickerAnimationPreset,
        val progress: Double,
    )

    private val entries: MutableList<Entry>
    private val joins = mutableListOf<Join>()
    private val backdropBridges = mutableListOf<BackdropBridge>()

    init {
        entries = clips
            .filter { it.timing.startTime.isFinite() && it.timing.duration.isFinite() && it.timing.duration > 0 }
            .sortedWith(compareBy({ it.layerIndex }, { it.id }))
            .map { clip ->
                val enter = max(clip.enterDuration, 0.0)
                val exit = max(clip.exitDuration, 0.0)
                val total = enter + exit
                val scale = if (total > clip.timing.duration && total > 0) clip.timing.duration / total else 1.0
                Entry(clip, enter * scale, exit * scale)
            }
            .toMutableList()
        buildJoins()
        buildBackdropBridges()
    }

    fun hasJoinedEntry(id: String): Boolean = entries.any { it.clip.id == id && it.entryJoin != null }

    fun sample(at: Double, suppressesInitialEntry: Boolean): Pair<List<Sample>, FrameStickerBackdropScene> {
        var backdrop = FrameStickerBackdropScene()
        for (bridge in backdropBridges) {
            if (bridge.contains(at)) backdrop = backdrop.merged(bridge.treatment)
        }
        for (join in joins) {
            if (join.contains(at)) {
                backdrop = backdrop.merged(
                    join.outgoingBackdrop.interpolatedTo(
                        join.incomingBackdrop,
                        ElementMotionEvaluator.progress(join.progress(at), join.curve),
                    ),
                )
            }
        }
        val samples = entries.mapNotNull { entry ->
            val clip = entry.clip
            val entryJoin = entry.entryJoin?.let { joins[it] }
            val exitJoin = entry.exitJoin?.let { joins[it] }
            val joinedEntryIsActive = entryJoin?.contains(at) ?: false
            val joinedExitIsActive = exitJoin?.contains(at) ?: false
            if (!clip.timing.contains(at) && !joinedEntryIsActive && !joinedExitIsActive) return@mapNotNull null
            val localTime = max(at - clip.timing.startTime, 0.0)
            val remaining = max(clip.timing.endTime - at, 0.0)
            val suppressesEntry = suppressesInitialEntry && clip.timing.startTime <= 0.0001
            val linearEnter = entryJoin?.progress(at)
                ?: if (suppressesEntry) 1.0
                else if (entry.enterDuration > 0) min(localTime / entry.enterDuration, 1.0) else 1.0
            val linearExit = exitJoin?.let { 1 - it.progress(at) }
                ?: if (entry.exitDuration > 0) min(remaining / entry.exitDuration, 1.0) else 1.0
            val enterProgress = ElementMotionEvaluator.progress(linearEnter, clip.animationCurve)
            val exitProgress = ElementMotionEvaluator.progress(linearExit, clip.animationCurve)
            val exitPreset = clip.exitAnimation ?: clip.animation.automaticExit
            val visibility = min(
                if (clip.animation == StickerAnimationPreset.NONE) 1.0 else enterProgress,
                if (exitPreset == StickerAnimationPreset.NONE) 1.0 else exitProgress,
            )
            val isExiting = exitJoin?.let { at >= it.start }
                ?: (entry.exitDuration > 0 && remaining < entry.exitDuration)
            if (!joinedEntryIsActive && !joinedExitIsActive) {
                backdrop = backdrop.merged(FrameStickerBackdropScene.forClip(clip, visibility))
            }
            Sample(
                clip = clip,
                visibility = visibility,
                preset = if (isExiting) exitPreset else clip.animation,
                progress = if (isExiting) exitProgress else enterProgress,
            )
        }
        return samples to backdrop
    }

    private fun buildJoins() {
        val endpoints = entries.indices.flatMap { index ->
            listOf(
                Endpoint(entries[index].clip.timing.startTime, index, true),
                Endpoint(entries[index].clip.timing.endTime, index, false),
            )
        }.sortedBy { it.time }
        var cursor = 0
        while (cursor < endpoints.size) {
            val boundary = endpoints[cursor].time
            val outgoing = mutableListOf<Int>()
            val incoming = mutableListOf<Int>()
            do {
                val endpoint = endpoints[cursor]
                if (endpoint.isStart) incoming += endpoint.index else outgoing += endpoint.index
                cursor++
            } while (cursor < endpoints.size && endpoints[cursor].time - boundary <= EndpointTolerance)
            val shared = outgoing.intersect(incoming.toSet())
            outgoing.removeAll(shared)
            incoming.removeAll(shared)
            if (outgoing.isEmpty() || incoming.isEmpty()) continue
            val before = outgoing.minOf { entries[it].joinExitDuration }
            val after = incoming.minOf { entries[it].joinEnterDuration }
            var outgoingBackdrop = FrameStickerBackdropScene()
            var incomingBackdrop = FrameStickerBackdropScene()
            for (index in outgoing) {
                outgoingBackdrop = outgoingBackdrop.merged(
                    FrameStickerBackdropScene.forClip(entries[index].clip, 1.0),
                )
            }
            for (index in incoming) {
                incomingBackdrop = incomingBackdrop.merged(
                    FrameStickerBackdropScene.forClip(entries[index].clip, 1.0),
                )
            }
            val joinIndex = joins.size
            joins += Join(
                start = (outgoing.minOfOrNull { entries[it].clip.timing.endTime } ?: boundary) - before,
                end = (incoming.maxOfOrNull { entries[it].clip.timing.startTime } ?: boundary) + after,
                outgoingBackdrop = outgoingBackdrop,
                incomingBackdrop = incomingBackdrop,
                curve = entries[incoming.max()].clip.animationCurve,
            )
            for (index in outgoing) entries[index].exitJoin = joinIndex
            for (index in incoming) entries[index].entryJoin = joinIndex
        }
    }

    private fun buildBackdropBridges() {
        val ordered = entries.indices.sortedWith { a, b ->
            val lhs = entries[a].clip.timing
            val rhs = entries[b].clip.timing
            if (lhs.startTime == rhs.startTime) lhs.endTime.compareTo(rhs.endTime) else lhs.startTime.compareTo(rhs.startTime)
        }
        for ((offset, outgoingIndex) in ordered.withIndex()) {
            val outgoing = entries[outgoingIndex]
            for (incomingIndex in ordered.drop(offset + 1)) {
                val incoming = entries[incomingIndex]
                if (incoming.clip.timing.startTime >= outgoing.clip.timing.endTime - EndpointTolerance) break
                if (incoming.clip.timing.endTime <= outgoing.clip.timing.endTime) continue
                val start = outgoing.clip.timing.endTime - outgoing.joinExitDuration
                val end = incoming.clip.timing.startTime + incoming.joinEnterDuration
                if (end <= start) continue
                val from = FrameStickerBackdropScene.forClip(outgoing.clip, 1.0)
                val to = FrameStickerBackdropScene.forClip(incoming.clip, 1.0)
                val treatment = FrameStickerBackdropScene(
                    screenSuppression = min(from.screenSuppression, to.screenSuppression),
                    cameraSuppression = min(from.cameraSuppression, to.cameraSuppression),
                    screenBlur = min(from.screenBlur, to.screenBlur),
                    cameraBlur = min(from.cameraBlur, to.cameraBlur),
                )
                if (treatment != FrameStickerBackdropScene()) {
                    backdropBridges += BackdropBridge(start, end, treatment)
                }
            }
        }
    }

    companion object {
        private const val EndpointTolerance = 0.0000001
    }
}
