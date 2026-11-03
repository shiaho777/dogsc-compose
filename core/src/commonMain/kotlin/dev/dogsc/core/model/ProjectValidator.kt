package dev.dogsc.core.model

import kotlin.math.max

class ProjectValidationException(message: String) : IllegalArgumentException(message)

object ProjectValidator {
    fun validate(project: RecorderProject) {
        validate(project.canvas)
        validate(project.camera)
        validate(project.audio)
        validate(project.cursorStyle)
        validate(project.motion)
        val opening = project.openingSequence
        if (!opening.duration.isFinite() || opening.duration !in 0.4..8.0) {
            throw ProjectValidationException("开场编排时长必须位于 0.4 到 8 秒。")
        }
        if (!opening.stagger.isFinite() || opening.stagger !in 0.0..1.2) {
            throw ProjectValidationException("开场编排间隔无效。")
        }
        if (opening.stagger > opening.maximumStagger(opening.includedElements.size) + 0.000001) {
            throw ProjectValidationException("开场编排间隔超出总时长。")
        }
        if (opening.includedElements.distinct().size != opening.includedElements.size) {
            throw ProjectValidationException("开场参与元素存在重复。")
        }
        if (opening.elementOrder.toSet() != OpeningSequenceElement.entries.toSet() ||
            opening.elementOrder.size != OpeningSequenceElement.entries.size
        ) {
            throw ProjectValidationException("开场元素顺序无效。")
        }
        validate(project.timeline)
        if (project.title.length > 200) throw ProjectValidationException("项目名称过长。")
        project.media?.let { media ->
            requirePath(media.screen.relativePath)
            media.camera?.let { requirePath(it.relativePath) }
            media.microphone?.let { requirePath(it.relativePath) }
            media.systemAudio?.let { requirePath(it.relativePath) }
            media.pointerEvents?.let { requirePath(it.relativePath) }
        }
    }

    fun validate(canvas: CanvasStyle) {
        val values = listOf(
            canvas.padding to (0.0..1000.0),
            canvas.contentScale to (0.05..8.0),
            canvas.contentPosition.x to (0.0..1.0),
            canvas.contentPosition.y to (0.0..1.0),
            canvas.cornerRadius to (0.0..500.0),
            canvas.borderWidth to (0.0..60.0),
            canvas.shadowStrength to (0.0..1.0),
            canvas.backgroundBlur to (0.0..96.0),
            canvas.insetOpacity to (0.0..1.0),
            canvas.screenFrameScale to (0.6..1.6),
            canvas.screenFrameToolbarScale to (0.65..1.6),
            canvas.patternScale to (0.4..6.0),
            canvas.patternOpacity to (0.0..1.0),
        )
        if (values.any { (v, r) -> !v.isFinite() || v !in r }) {
            throw ProjectValidationException("画布参数包含越界或无效值。")
        }
        canvas.screenFrameOuterCornerRadius?.let {
            if (!it.isFinite() || it !in 0.0..160.0) throw ProjectValidationException("框体外圆角超出范围。")
        }
        canvas.screenFrameContentCornerRadius?.let {
            if (!it.isFinite() || it !in 0.0..160.0) throw ProjectValidationException("内容圆角超出范围。")
        }
        if (canvas.screenFrameTitle.length > 120) throw ProjectValidationException("窗口标题过长。")
        if (canvas.screenFrameBrowserAddress.length > 240) throw ProjectValidationException("浏览器地址过长。")
        if (canvas.crop != canvas.crop.clamped()) throw ProjectValidationException("裁切值无效。")
    }

    fun validate(camera: CameraStyle) {
        val values = listOf(
            camera.position.x to (0.0..1.0),
            camera.position.y to (0.0..1.0),
            camera.size to (0.01..1.0),
            camera.borderWidth to (0.0..18.0),
            camera.shadowStrength to (0.0..1.0),
            camera.roundness to (0.0..1.0),
            camera.scaleDuringZoom to (0.1..2.0),
            camera.contentPosition.x to (0.0..1.0),
            camera.contentPosition.y to (0.0..1.0),
            camera.contentScale to (1.0..4.0),
        )
        if (values.any { (v, r) -> !v.isFinite() || v !in r }) {
            throw ProjectValidationException("摄像头参数超出可渲染范围。")
        }
    }

    fun validate(audio: AudioStyle) {
        if (audio.systemVolume !in 0.0..1.0 || audio.microphoneVolume !in 0.0..1.0) {
            throw ProjectValidationException("音量必须位于 0 到 1。")
        }
    }

    fun validate(cursor: CursorStyle) {
        if (cursor.assetID.rawValue.isEmpty()) throw ProjectValidationException("光标资源无效。")
        if (cursor.size !in 0.25..6.0) throw ProjectValidationException("光标尺寸超出范围。")
        if (cursor.idleDelay !in 0.2..8.0) throw ProjectValidationException("光标静止延迟超出范围。")
        if (cursor.motionTiltStrength !in 2.0..4.0) throw ProjectValidationException("光标摆动强度超出范围。")
    }

    fun validate(motion: MotionStyle) {
        motion.defaultZoomScale?.let {
            if (it !in 1.0..6.0) throw ProjectValidationException("新动画倍率必须在 1 到 6 倍之间。")
        }
        val values = listOf(
            motion.screenSpringMass to (0.01..20.0),
            motion.screenSpringStiffness to (1.0..5000.0),
            motion.screenSpringDamping to (0.1..1000.0),
            motion.cursorSpringMass to (0.01..20.0),
            motion.cursorSpringStiffness to (1.0..5000.0),
            motion.cursorSpringDamping to (0.1..1000.0),
            motion.defaultZoomTransitionDuration to (0.0..5.0),
        )
        if (values.any { (v, r) -> !v.isFinite() || v !in r }) {
            throw ProjectValidationException("弹簧或默认过渡参数无法稳定求值。")
        }
    }

    fun validate(timeline: ProjectTimeline) {
        val ids = mutableSetOf<String>()
        fun unique(id: String, label: String) {
            if (!ids.add("$label:$id")) throw ProjectValidationException("时间线存在重复的${label}片段。")
        }
        val zoom = timeline.zoomClips.sortedWith(compareBy({ it.startTime }, { it.id }))
        for (clip in zoom) {
            unique(clip.id, "缩放")
            if (!clip.startTime.isFinite() || !clip.endTime.isFinite() || clip.endTime <= clip.startTime) {
                throw ProjectValidationException("缩放片段包含无效时间范围。")
            }
            if (clip.scale < 1.0 || !clip.scale.isFinite()) throw ProjectValidationException("缩放倍率无效。")
        }
        for ((previous, next) in zoom.zipWithNext()) {
            if (next.startTime < previous.endTime - 0.0001) {
                throw ProjectValidationException("缩放片段的生效区间发生重叠。")
            }
        }
        for (clip in timeline.screenMotionClips) {
            unique(clip.id, "屏幕")
            validateTiming(clip.timing)
            validateMotionTarget(clip.target)
        }
        for (clip in timeline.cameraMotionClips) {
            unique(clip.id, "摄像头")
            validateTiming(clip.timing)
            if (clip.target.size !in 0.01..1.0) throw ProjectValidationException("摄像头动画大小无效。")
        }
        for (clip in timeline.mosaicClips) {
            unique(clip.id, "打码")
            if (clip.timing.duration <= 0) throw ProjectValidationException("打码片段时长无效。")
        }
        for (clip in timeline.stickerClips) {
            unique(clip.id, "贴图")
            if (clip.timing.duration <= 0) throw ProjectValidationException("贴图片段时长无效。")
            requirePath(clip.relativePath)
        }
        val segments = timeline.sourceSequence.segmentsOrEmpty
        val segmentIDs = mutableSetOf<String>()
        var lastSourceStart = -1.0
        for (segment in segments) {
            if (!segmentIDs.add(segment.id)) throw ProjectValidationException("时间线存在重复的主片段。")
            if (!segment.sourceStart.isFinite() || segment.sourceStart < lastSourceStart - 0.0001) {
                throw ProjectValidationException("主片段顺序无效。")
            }
            if (!segment.sourceDuration.isFinite() || segment.sourceDuration <= 0) {
                throw ProjectValidationException("主片段时长无效。")
            }
            if (segment.playbackRate !in RecordingSegment.MinimumPlaybackRate..RecordingSegment.MaximumPlaybackRate) {
                throw ProjectValidationException("主片段变速超出 1×–100×。")
            }
            lastSourceStart = segment.sourceStart
        }
    }

    private fun validateTiming(timing: TransitionTiming) {
        if (!timing.startTime.isFinite() || timing.startTime < 0 ||
            !timing.duration.isFinite() || timing.duration <= 0
        ) {
            throw ProjectValidationException("动画片段包含无效时间范围。")
        }
    }

    private fun validateMotionTarget(target: ScreenMotionState) {
        if (target.position.x !in 0.0..1.0 || target.position.y !in 0.0..1.0) {
            throw ProjectValidationException("屏幕位置超出范围。")
        }
        if (target.scale <= 0 || !target.scale.isFinite()) throw ProjectValidationException("屏幕大小无效。")
        if (target.perspective < 0 || target.perspective > 2) throw ProjectValidationException("透视强度无效。")
    }

    private fun requirePath(path: String) {
        if (path.isBlank() || path.contains("..") || path.startsWith("/")) {
            throw ProjectValidationException("项目素材路径无效。")
        }
    }
}

fun RecorderProject.validated(): RecorderProject {
    ProjectValidator.validate(this)
    return this
}

fun RecorderProject.outputDuration(sourceDuration: Double): Double = max(
    TimelineMath.outputDuration(timeline.sourceSequence, sourceDuration),
    0.0,
)
