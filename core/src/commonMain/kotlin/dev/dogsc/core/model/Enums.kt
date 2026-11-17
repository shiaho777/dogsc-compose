package dev.dogsc.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CanvasAspectRatio(val displayName: String, val fixedRatio: Double?) {
    @SerialName("adaptive") ADAPTIVE("适应", null),
    @SerialName("16:9") LANDSCAPE("16:9", 16.0 / 9.0),
    @SerialName("4:3") STANDARD("4:3", 4.0 / 3.0),
    @SerialName("9:16") PORTRAIT("9:16", 9.0 / 16.0),
    @SerialName("1:1") SQUARE("1:1", 1.0),
    @SerialName("3:4") STANDARD_PORTRAIT("3:4", 3.0 / 4.0),
    @SerialName("2.39:1") CINEMA("2.39:1", 2.39),
    @SerialName("1:2.39") CINEMA_PORTRAIT("1:2.39", 1.0 / 2.39),
    @SerialName("custom") CUSTOM("自定义", null);

    companion object {
        val choices: List<CanvasAspectRatio> = entries
    }
}

@Serializable
enum class CanvasResolution(val displayName: String, val shortEdge: Int?, val longEdge: Int?) {
    @SerialName("source") SOURCE("原始素材", null, null),
    @SerialName("1080p") FULL_HD("1080p", 1080, 1920),
    @SerialName("1440p") QUAD_HD("1440p", 1440, 2560),
    @SerialName("4K") ULTRA_HD("4K", 2160, 3840);

    companion object {
        val choices: List<CanvasResolution> = entries
    }
}

@Serializable
enum class ScreenFrameStyle(val displayName: String, val isDevice: Boolean = false) {
    @SerialName("none") NONE("无边框"),
    @SerialName("windowLight") WINDOW_LIGHT("macOS 窗口·浅色"),
    @SerialName("windowDark") WINDOW_DARK("macOS 窗口·深色"),
    @SerialName("browserLight") BROWSER_LIGHT("浏览器·浅色"),
    @SerialName("browserDark") BROWSER_DARK("浏览器·深色"),
    @SerialName("devicePhone") DEVICE_PHONE("手机样机", isDevice = true),
    @SerialName("deviceTablet") DEVICE_TABLET("平板样机", isDevice = true);

    val isBrowser: Boolean get() = this == BROWSER_LIGHT || this == BROWSER_DARK
    val isWindow: Boolean get() = this == WINDOW_LIGHT || this == WINDOW_DARK
    val isDark: Boolean
        get() = this == WINDOW_DARK || this == BROWSER_DARK ||
            this == DEVICE_PHONE || this == DEVICE_TABLET

    val defaultOuterCornerRadius: Double
        get() = when (this) {
            WINDOW_LIGHT, WINDOW_DARK -> 12.0
            BROWSER_LIGHT, BROWSER_DARK -> 14.0
            DEVICE_PHONE, DEVICE_TABLET -> 0.0
            NONE -> 0.0
        }

    val defaultContentCornerRadius: Double
        get() = when (this) {
            WINDOW_LIGHT, WINDOW_DARK -> 8.0
            BROWSER_LIGHT, BROWSER_DARK -> 8.0
            DEVICE_PHONE, DEVICE_TABLET -> 0.0
            NONE -> 0.0
        }

    companion object {
        val pickerChoices: List<ScreenFrameStyle> = entries
    }
}

@Serializable
enum class OpeningSequencePreset(val displayName: String) {
    @SerialName("converge") CONVERGE("聚拢"),
    @SerialName("sideSlide") SIDE_SLIDE("侧滑"),
    @SerialName("light3D") LIGHT_3D("轻 3D"),
}

@Serializable
enum class OpeningSequenceElement(val displayName: String) {
    @SerialName("screen") SCREEN("屏幕"),
    @SerialName("camera") CAMERA("摄像头"),
    @SerialName("stickers") STICKERS("贴图"),
}

@Serializable
enum class BackgroundPatternPreset(val displayName: String) {
    @SerialName("obsidianGrid") OBSIDIAN_GRID("黑曜石网格"),
    @SerialName("engineeringWhiteGrid") ENGINEERING_WHITE_GRID("工程白网格"),
    @SerialName("midnightDots") MIDNIGHT_DOTS("暗夜星阵"),
    @SerialName("architecturalDots") ARCHITECTURAL_DOTS("建筑极简点"),
    @SerialName("isometricMesh") ISOMETRIC_MESH("立体等角网"),
}

@Serializable
enum class DynamicBackgroundPreset(val displayName: String) {
    @SerialName("cyberDriftGrid") CYBER_DRIFT_GRID("流金缓移网格"),
    @SerialName("starfieldDots") STARFIELD_DOTS("暖星漫步点阵"),
    @SerialName("auroraFluid") AURORA_FLUID("蓝白流体云幕"),
}

/**
 * The one active background source for a canvas. Project-relative media and
 * system-absolute references stay distinct so user files can travel with a
 * project while system-managed media is never copied into the app.
 */
@Serializable
sealed class BackgroundSource {
    @Serializable
    @SerialName("pattern")
    data class Pattern(val preset: BackgroundPatternPreset = BackgroundPatternPreset.OBSIDIAN_GRID) : BackgroundSource()

    @Serializable
    @SerialName("dynamicFlow")
    data class DynamicFlow(val preset: DynamicBackgroundPreset = DynamicBackgroundPreset.CYBER_DRIFT_GRID) : BackgroundSource()

    @Serializable
    @SerialName("projectImage")
    data class ProjectImage(val relativePath: String) : BackgroundSource()

    @Serializable
    @SerialName("systemImage")
    data class SystemImage(val absolutePath: String) : BackgroundSource()

    @Serializable
    @SerialName("projectVideo")
    data class ProjectVideo(val relativePath: String) : BackgroundSource()

    @Serializable
    @SerialName("systemVideo")
    data class SystemVideo(val absolutePath: String) : BackgroundSource()

    val isImage: Boolean get() = this is ProjectImage || this is SystemImage
    val isVideo: Boolean get() = this is ProjectVideo || this is SystemVideo
    val usesWallpaperMedia: Boolean get() = isImage || isVideo

    companion object {
        val SafeFallback: BackgroundSource = Pattern(BackgroundPatternPreset.OBSIDIAN_GRID)
    }
}

@Serializable
enum class CameraShape(val displayName: String) {
    @SerialName("square") SQUARE("方形"),
    @SerialName("horizontal") HORIZONTAL("横向"),
    @SerialName("vertical") VERTICAL("纵向"),
    @SerialName("original") ORIGINAL("原始比例"),
    @SerialName("circle") CIRCLE("圆形"),
}

@Serializable
enum class ScreenMotionStyle(val displayName: String) {
    @SerialName("focused") FOCUSED("聚焦"),
    @SerialName("smooth") SMOOTH("平滑"),
}

@Serializable
enum class CursorMotionStyle(val displayName: String) {
    @SerialName("smooth") SMOOTH("平滑"),
    @SerialName("medium") MEDIUM("适中"),
    @SerialName("rapid") RAPID("快速"),
    @SerialName("none") NONE("无"),
}

@Serializable
enum class CursorAppearance(val displayName: String) {
    @SerialName("arrow") ARROW("系统箭头"),
    @SerialName("pointingHand") POINTING_HAND("指向手势"),
    @SerialName("crosshair") CROSSHAIR("十字准星"),
    @SerialName("hidden") HIDDEN("隐藏"),
}

/**
 * Stable cursor resource identity persisted in project.json. Display names and
 * artwork live in the app-side catalog so labels can evolve without
 * invalidating existing projects.
 */
@Serializable
@JvmInline
value class CursorAssetID(val rawValue: String) {
    companion object {
        val SystemArrow = CursorAssetID("system.arrow")
        val Automatic = CursorAssetID("system.automatic")
        val SystemPointingHand = CursorAssetID("system.pointing-hand")
        val SystemCrosshair = CursorAssetID("system.crosshair")
        val SystemIBeam = CursorAssetID("system.ibeam")
        val SystemOpenHand = CursorAssetID("system.open-hand")
        val SystemClosedHand = CursorAssetID("system.closed-hand")
        val SystemNotAllowed = CursorAssetID("system.not-allowed")
        val TouchDot = CursorAssetID("system.touch-dot")
        val Hidden = CursorAssetID("system.hidden")
    }
}

@Serializable
enum class CursorClickEffectStyle(val displayName: String, val duration: Double) {
    @SerialName("ripple") RIPPLE("涟漪扩散", 0.36),
    @SerialName("glow") GLOW("柔和光晕", 0.32),
    @SerialName("pulse") PULSE("弹性双环", 0.38),
    @SerialName("burst") BURST("聚焦微闪", 0.22),
    @SerialName("none") NONE("无效果", 0.0),
}

@Serializable
enum class ZoomEasingPreset(val displayName: String, val shortName: String) {
    @SerialName("linear") LINEAR("线性", "线性"),
    @SerialName("quadratic") QUADRATIC("二次方缓动", "二次"),
    @SerialName("cubic") CUBIC("三次方缓动", "三次"),
    @SerialName("quintic") QUINTIC("五次平滑", "五次"),
    @SerialName("spring") SPRING("弹簧", "弹簧"),
    @SerialName("custom") CUSTOM("自定义曲线", "自定义");

    val curve: ZoomBezierCurve
        get() = when (this) {
            LINEAR -> ZoomBezierCurve.Linear
            QUADRATIC -> ZoomBezierCurve.Quadratic
            CUBIC -> ZoomBezierCurve.Cubic
            QUINTIC -> ZoomBezierCurve.Quintic
            SPRING, CUSTOM -> ZoomBezierCurve.Cubic
        }
}

@Serializable
enum class ZoomKeyframeOrigin { @SerialName("automatic") AUTOMATIC, @SerialName("manual") MANUAL }

@Serializable
enum class ElementMotionCurve(val displayName: String) {
    @SerialName("smooth") SMOOTH("顺滑"),
    @SerialName("swift") SWIFT("利落"),
    @SerialName("gentle") GENTLE("柔和"),
}

@Serializable
enum class MosaicEffectStyle { @SerialName("blur") BLUR, @SerialName("spotlight") SPOTLIGHT }

@Serializable
enum class MosaicTransitionStyle { @SerialName("none") NONE, @SerialName("linear") LINEAR, @SerialName("smooth") SMOOTH }

@Serializable
enum class StickerAnimationPreset {
    @SerialName("none") NONE,
    @SerialName("fade") FADE,
    @SerialName("pop") POP,
    @SerialName("slideLeft") SLIDE_LEFT,
    @SerialName("slideRight") SLIDE_RIGHT,
    @SerialName("slideUp") SLIDE_UP,
    @SerialName("slideDown") SLIDE_DOWN,
    @SerialName("slideTopLeft") SLIDE_TOP_LEFT,
    @SerialName("slideTopRight") SLIDE_TOP_RIGHT,
    @SerialName("slideBottomLeft") SLIDE_BOTTOM_LEFT,
    @SerialName("slideBottomRight") SLIDE_BOTTOM_RIGHT;

    val automaticExit: StickerAnimationPreset
        get() = when (this) {
            SLIDE_LEFT -> SLIDE_RIGHT
            SLIDE_RIGHT -> SLIDE_LEFT
            SLIDE_UP -> SLIDE_DOWN
            SLIDE_DOWN -> SLIDE_UP
            SLIDE_TOP_LEFT -> SLIDE_BOTTOM_RIGHT
            SLIDE_TOP_RIGHT -> SLIDE_BOTTOM_LEFT
            SLIDE_BOTTOM_LEFT -> SLIDE_TOP_RIGHT
            SLIDE_BOTTOM_RIGHT -> SLIDE_TOP_LEFT
            NONE, FADE, POP -> this
        }

    val displayName: String
        get() = when (this) {
            NONE -> "无动画"; FADE -> "淡入"; POP -> "弹出"
            SLIDE_LEFT -> "从左"; SLIDE_RIGHT -> "从右"; SLIDE_UP -> "从上"; SLIDE_DOWN -> "从下"
            SLIDE_TOP_LEFT -> "左上"; SLIDE_TOP_RIGHT -> "右上"
            SLIDE_BOTTOM_LEFT -> "左下"; SLIDE_BOTTOM_RIGHT -> "右下"
        }
}

@Serializable
enum class FocusEffectTarget { @SerialName("animation") ANIMATION, @SerialName("pointer") POINTER, @SerialName("fixed") FIXED }

@Serializable
enum class FocusEffectShape { @SerialName("radial") RADIAL, @SerialName("linear") LINEAR }

@Serializable
enum class OutputFrameRate(val value: Int) {
    @SerialName("30") FPS_30(30),
    @SerialName("60") FPS_60(60);

    val label: String get() = "$value FPS"
    val frameDuration: Double get() = 1.0 / value

    companion object {
        val choices: List<OutputFrameRate> = entries
    }
}

@Serializable
enum class CaptureSource(val displayName: String) {
    @SerialName("display") DISPLAY("整块屏幕"),
    @SerialName("area") AREA("自选区域"),
    @SerialName("imported") IMPORTED("导入素材"),
}

@Serializable
enum class CaptureCodec(val displayName: String) {
    @SerialName("h264") H264("H.264"),
    @SerialName("hevc") HEVC("HEVC");
}

@Serializable
enum class CaptureResolutionLimit(val displayName: String) {
    @SerialName("native") NATIVE("原始分辨率"),
    @SerialName("4k") UHD("最高 4K"),
    @SerialName("1080p") FULL_HD("最高 1080p"),
}

@Serializable
enum class SystemAudioScope(val displayName: String) {
    @SerialName("all") ALL("全部系统声音"),
    @SerialName("selectedApplication") SELECTED_APPLICATION("所选应用"),
}

@Serializable
enum class PointerEventKind { @SerialName("move") MOVE, @SerialName("leftClick") LEFT_CLICK, @SerialName("rightClick") RIGHT_CLICK, @SerialName("down") DOWN, @SerialName("up") UP }

@Serializable
enum class PointerModifier { @SerialName("command") COMMAND, @SerialName("option") OPTION, @SerialName("control") CONTROL, @SerialName("shift") SHIFT }

/** A capture-time placement correction for an auxiliary track. */
@Serializable
data class MediaSyncAnchor(
    val id: String = randomId(),
    val sourceTime: Double,
    val offset: Double,
) {
    init {
        require(sourceTime >= 0)
    }
}
