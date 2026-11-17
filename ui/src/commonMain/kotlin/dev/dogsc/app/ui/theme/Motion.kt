package dev.dogsc.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable

/**
 * Unified motion language, ported from upstream `SpringMotion` / `EditorDesignTokens`.
 *
 * Every animation in the app should pick a named duration from here instead of
 * inventing local tweens, so the whole product feels like one instrument.
 * All springs are critically damped (ratio 1) like upstream: no overshoot wobble
 * on UI chrome, motion reads as precise rather than bouncy.
 */
object DogScMotion {
    /** Quick state flips: segment selection, toggle wash. Upstream snappy .24 */
    const val SnappyMs = 240

    /** Direct-manipulation follow: drag handles, thumb tracking. Upstream interactive .3 */
    const val InteractiveMs = 300

    /** Panels and groups settling into place. Upstream settle .38 */
    const val SettleMs = 380

    /** Page-level and sheet transitions. Upstream fluid .45 */
    const val FluidMs = 450

    /** Large decorative motion: onboarding, tour spotlight. Upstream gentle .55 */
    const val GentleMs = 550

    /** Hover / press wash fade. Upstream easeOut .15 */
    const val HoverMs = 150

    /** Clip chrome state stroke crossfade (upstream EditorTimelineClipChrome). */
    const val ClipChromeMs = 140

    /** Minimum visible press feedback so fast taps still register (upstream AppChoiceFeedback). */
    const val MinPressMs = 100

    /** Magnetic snap pulse ring lifetime (upstream EditorTimelineMagneticSnap). */
    const val SnapPulseMs = 450

    val EaseOut = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    val EaseInOut = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    fun <T> snappy(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 1700f)
    fun <T> interactive(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 1100f)
    fun <T> settle(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 700f)
    fun <T> fluid(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 480f)
    fun <T> gentle(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 330f)

    fun <T> hoverTween(): FiniteAnimationSpec<T> = tween(HoverMs, easing = EaseOut)
    fun <T> clipChromeTween(): FiniteAnimationSpec<T> = tween(ClipChromeMs, easing = EaseOut)
    fun <T> snappyTween(): FiniteAnimationSpec<T> = tween(SnappyMs, easing = EaseInOut)
    fun <T> settleTween(): FiniteAnimationSpec<T> = tween(SettleMs, easing = EaseInOut)
    fun <T> fluidTween(): FiniteAnimationSpec<T> = tween(FluidMs, easing = EaseInOut)
}

/**
 * Whether the user asked the platform to reduce motion. When true, callers should
 * collapse animations to instant state changes (duration 0) rather than removing
 * the state change itself. Android reads Settings.Global.ANIMATOR_DURATION_SCALE;
 * other targets default to full motion.
 */
@Composable
expect fun rememberReducedMotion(): Boolean

/** Duration helper: collapses to 0 when the user prefers reduced motion. */
fun motionDuration(reduced: Boolean, fullMs: Int): Int = if (reduced) 0 else fullMs