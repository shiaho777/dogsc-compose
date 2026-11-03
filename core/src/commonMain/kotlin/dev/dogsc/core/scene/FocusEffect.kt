package dev.dogsc.core.scene

import dev.dogsc.core.model.FocusEffect
import dev.dogsc.core.model.FocusEffectShape
import dev.dogsc.core.model.FocusEffectTarget
import dev.dogsc.core.model.MotionStyle
import dev.dogsc.core.model.NormalizedCrop
import dev.dogsc.core.model.NormalizedPoint
import dev.dogsc.core.model.ScreenMotionClip
import dev.dogsc.core.model.TransitionTiming
import dev.dogsc.core.model.ZoomAnimationClip
import dev.dogsc.core.model.ZoomInterpolator
import kotlin.math.max
import kotlin.math.min

object FocusEffectEvaluator {
    fun scene(
        zoom: ZoomAnimationClip?,
        screen: ScreenMotionClip?,
        zoomFocus: NormalizedPoint,
        crop: NormalizedCrop,
        pointer: NormalizedPoint?,
        time: Double,
        motion: MotionStyle,
    ): FrameFocusScene? {
        val effect: FocusEffect
        val anchor: NormalizedPoint
        val timing: TransitionTiming
        val holds: Boolean
        if (screen != null && screen.focusEffect != null) {
            var linear = screen.focusEffect
            if (linear.shape == null) {
                linear = linear.copy(
                    shape = FocusEffectShape.LINEAR,
                    target = FocusEffectTarget.FIXED,
                    dimming = 0.0,
                )
            }
            effect = linear
            anchor = NormalizedPoint(
                crop.x + screen.target.position.x * crop.width,
                crop.y + screen.target.position.y * crop.height,
            )
            timing = screen.timing
            holds = timing.returnDuration == 0.0
        } else if (zoom != null && zoom.focusEffect != null) {
            effect = zoom.focusEffect
            anchor = zoomFocus
            timing = TransitionTiming(
                startTime = zoom.startTime,
                duration = zoom.duration,
                leadInDuration = zoom.enterDuration,
                easing = zoom.easing,
                customCurve = zoom.customCurve,
                returnDuration = zoom.exitDuration,
                leadInProgressOffset = zoom.enterProgressOffset,
                returnProgressOffset = zoom.exitProgressOffset,
            )
            holds = false
        } else {
            return null
        }
        if (!effect.isValid || (effect.blur <= 0 && effect.dimming <= 0)) return null
        val amount = progress(timing, time, holds, motion)
        if (amount <= 0) return null
        val center = when (effect.target) {
            FocusEffectTarget.ANIMATION -> anchor
            FocusEffectTarget.POINTER -> pointer ?: anchor
            FocusEffectTarget.FIXED -> effect.center
        }
        return FrameFocusScene(effect, center.constrained(0.0), amount)
    }

    private fun progress(timing: TransitionTiming, time: Double, holds: Boolean, motion: MotionStyle): Double {
        if (time < timing.startTime) return 0.0
        val enter = min(timing.leadInDuration, timing.duration)
        val linear: Double
        val exiting: Boolean
        if (enter > 0 && time < timing.startTime + enter) {
            linear = timing.leadInProgressOffset +
                (1 - timing.leadInProgressOffset) * (time - timing.startTime) / enter
            exiting = false
        } else if (time < timing.endTime) {
            return 1.0
        } else if (timing.returnDuration > 0) {
            linear = timing.returnProgressOffset +
                (1 - timing.returnProgressOffset) * (time - timing.endTime) / timing.returnDuration
            exiting = true
        } else {
            return if (holds) 1.0 else 0.0
        }
        val eased = ZoomInterpolator.easedProgress(
            linear.coerceIn(0.0, 1.0),
            timing.easing,
            timing.customCurve,
            motion,
        )
        return if (exiting) (1 - eased).coerceIn(0.0, 1.0) else eased.coerceIn(0.0, 1.0)
    }
}
