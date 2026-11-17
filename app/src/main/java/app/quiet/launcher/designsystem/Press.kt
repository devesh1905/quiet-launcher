package app.quiet.launcher.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch

/**
 * Apple-style press feedback instead of a ripple: the element dims and settles slightly
 * smaller under the finger, then springs back. Long press gives a light haptic tick.
 */
fun Modifier.pressable(
    pressedScale: Float = 0.97f,
    pressedAlpha: Float = 0.55f,
    onLongPress: (() -> Unit)? = null,
    onDoubleTap: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val anim = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    this
        .graphicsLayer {
            val p = anim.value
            scaleX = 1f - (1f - pressedScale) * p
            scaleY = scaleX
            alpha = 1f - (1f - pressedAlpha) * p
        }
        .pointerInput(onClick, onLongPress, onDoubleTap) {
            detectTapGestures(
                onPress = {
                    scope.launch { anim.animateTo(1f, spring(dampingRatio = 1f, stiffness = 1400f)) }
                    tryAwaitRelease()
                    scope.launch { anim.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 500f)) }
                },
                onTap = { onClick() },
                onDoubleTap = onDoubleTap?.let { { _ -> it() } },
                onLongPress = onLongPress?.let {
                    { _ ->
                        haptic.play(Feel.Heavy)
                        it()
                    }
                },
            )
        }
}
