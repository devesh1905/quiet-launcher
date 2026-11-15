package app.quiet.launcher.designsystem

import androidx.compose.animation.core.*
import kotlin.math.abs

/**
 * Motion tokens (DESIGN-NOTES §4, §8.4). Gesture-driven motion follows Apple's model:
 * springs are interruptible and inherit the finger's release velocity, rubber-banding past
 * limits, and projected fling targets decide where things land. Fixed-duration easing is
 * used only for fades.
 */
object QMotion {
    /** Card -> player morph: slight overshoot (~2.6%), settles ~450 ms. */
    val Open: SpringSpec<Float> = spring(dampingRatio = 0.72f, stiffness = 380f)

    /** Player -> card: critically damped, ~430 ms. */
    val Close: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 700f)

    /** Lock paused/playing re-layout. */
    val Settle: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 300f)

    /** Snappy UI response (press states). */
    val Snappy: SpringSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 900f)

    /** Unlock / dismiss fling: soft, keeps the release velocity. */
    val Fling: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 260f)

    /** Return after a short drag: a touch of bounce so it feels physical. */
    val Return: SpringSpec<Float> = spring(dampingRatio = 0.78f, stiffness = 420f)

    val EaseOutQuiet = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)
    val EaseInOutSine = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

    fun <T> fade(ms: Int, delay: Int = 0): TweenSpec<T> = tween(ms, delay, EaseOutQuiet)

    /** iOS-style rubber band: resistance grows with distance past the limit. */
    fun rubberBand(offset: Float, dimension: Float, coefficient: Float = 0.55f): Float {
        if (dimension <= 0f) return 0f
        val sign = if (offset < 0) -1f else 1f
        val x = abs(offset)
        return sign * (1f - (1f / ((x * coefficient / dimension) + 1f))) * dimension
    }

    /** Where a fling would come to rest (UIScrollView "normal" deceleration, 0.998 per ms). */
    fun project(velocityPxPerS: Float, decelerationRate: Float = 0.998f): Float =
        (velocityPxPerS / 1000f) * decelerationRate / (1f - decelerationRate)
}
