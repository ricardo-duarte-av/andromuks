package net.vrkknn.andromuks.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * Central, runtime-tunable control over animation speed across the whole app.
 *
 * Compose has exactly two timing mechanisms, and every animation we own maps onto one of them:
 *
 *  - **Tween / keyframes** carry an explicit `durationMillis`. [tweenFactor] scales that duration
 *    (lower = snappier). Covers `animate*AsState`, explicit `tween(...)`, and any `AnimatedVisibility`
 *    that passes an explicit tween spec.
 *  - **Spring** has no duration — its speed comes from `stiffness` (higher = snappier), with
 *    `dampingRatio` controlling bounce only. [stiffnessFactor] scales `stiffness`. Covers explicit
 *    `spring(...)` and `AnimatedVisibility` left on its (spring-based) default transition, which we
 *    convert to explicit scaled springs so the slider can reach them.
 *
 * Note the asymmetry: for [tweenFactor], **smaller = faster**; for [stiffnessFactor], **larger =
 * faster**. Both default to 1.0 (no change). `infiniteRepeatable` loops (spinners, pulses) and
 * spring `dampingRatio` are intentionally NOT scaled.
 *
 * The factors are snapshot state, so reads inside composition recompose when a settings slider
 * changes. They live in this object (not [net.vrkknn.andromuks.AppViewModel]) so the scaling helpers
 * can be called from anywhere in the UI tree without a ViewModel reference. Persistence and loading
 * are handled by `SettingsCoordinator`.
 */
object AnimationSpeed {
    const val MIN_FACTOR = 0.1f
    const val MAX_FACTOR = 2.0f
    const val DEFAULT_FACTOR = 1.0f

    var tweenFactor by mutableFloatStateOf(DEFAULT_FACTOR)
    var stiffnessFactor by mutableFloatStateOf(DEFAULT_FACTOR)
}

/** Scale a tween/keyframe duration (ms) by [AnimationSpeed.tweenFactor]. Never negative. */
fun scaledTweenMs(baseMillis: Int): Int =
    (baseMillis * AnimationSpeed.tweenFactor).roundToInt().coerceAtLeast(0)

/** Scale a spring stiffness by [AnimationSpeed.stiffnessFactor]. Clamped to a sane minimum. */
fun scaledStiffness(baseStiffness: Float): Float =
    (baseStiffness * AnimationSpeed.stiffnessFactor).coerceAtLeast(1f)

/**
 * A [tween] whose duration (and delay) are scaled by [AnimationSpeed.tweenFactor]. Convenience for
 * call sites that prefer not to wrap the literal by hand; equivalent to
 * `tween(scaledTweenMs(durationMillis), scaledTweenMs(delayMillis), easing)`.
 */
fun <T> scaledTween(
    durationMillis: Int,
    delayMillis: Int = 0,
    easing: Easing = FastOutSlowInEasing
): TweenSpec<T> = tween(scaledTweenMs(durationMillis), scaledTweenMs(delayMillis), easing)

/**
 * A [spring] whose stiffness is scaled by [AnimationSpeed.stiffnessFactor]. Used both for explicit
 * springs and for re-expressing `AnimatedVisibility`'s default (spring) transitions so they fall
 * under slider control while keeping the same feel at factor 1.0.
 */
fun <T> scaledSpring(
    dampingRatio: Float = Spring.DampingRatioNoBouncy,
    stiffness: Float = Spring.StiffnessMedium,
    visibilityThreshold: T? = null
): SpringSpec<T> = spring(dampingRatio, scaledStiffness(stiffness), visibilityThreshold)

/**
 * Re-creates `AnimatedVisibility`'s default *ColumnScope* enter transition — `fadeIn() +
 * expandVertically()` — with spring stiffness scaled by [AnimationSpeed.stiffnessFactor]. At factor
 * 1.0 it matches Compose's default ([Spring.StiffnessMediumLow]), so the feel is preserved; the
 * stiffness slider then reaches transitions that were previously implicit. Pair with
 * [scaledColumnExit]. (Use these only for vertically-laid-out AnimatedVisibility — the value matches
 * the ColumnScope default, not the Row/Box ones.)
 */
fun scaledColumnEnter(): EnterTransition =
    fadeIn(animationSpec = scaledSpring(stiffness = Spring.StiffnessMediumLow)) +
        expandVertically(animationSpec = scaledSpring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntSize(1, 1)))

fun scaledColumnExit(): ExitTransition =
    shrinkVertically(animationSpec = scaledSpring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntSize(1, 1))) +
        fadeOut(animationSpec = scaledSpring(stiffness = Spring.StiffnessMediumLow))
