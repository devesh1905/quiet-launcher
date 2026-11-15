package app.quiet.launcher.designsystem

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Colour tokens from DESIGN-NOTES §1. Dark is AMOLED true black. */
@Immutable
data class QuietColors(
    val bg: Color,
    val raised: Color,
    val ink: Color,
    val muted: Color,
    val edge: Color,
    val edgeOnField: Color,
    val line: Color,
    val faint: Color,
    val mat: Color,
    val matBig: Color,
    val wood: Color,
    val onInk: Color,
    val scrim: Color,
    val isDark: Boolean,
)

val DarkColors = QuietColors(
    bg = Color(0xFF000000), raised = Color(0xFF0F0F0E), ink = Color(0xFFEDEBE6), muted = Color(0xFF9A968E),
    edge = Color(0xFF6E6B64), edgeOnField = Color(0xFF8E8A82), line = Color(0xFF1F1E1B), faint = Color(0xFF34322D),
    mat = Color(0xFF141412), matBig = Color(0xFFECE8DF), wood = Color(0xFF2B241D), onInk = Color(0xFF000000),
    scrim = Color(0xB8000000), isDark = true,
)

val LightColors = QuietColors(
    bg = Color(0xFFF3F1EB), raised = Color(0xFFFBFAF6), ink = Color(0xFF141311), muted = Color(0xFF5E5A52),
    edge = Color(0xFF858076), edgeOnField = Color(0xFF77726A), line = Color(0xFFDDD9CF), faint = Color(0xFFCFCAC0),
    mat = Color(0xFFE4E0D6), matBig = Color(0xFFFBFAF6), wood = Color(0xFF3A3129), onInk = Color(0xFFF3F1EB),
    scrim = Color(0x8C141311), isDark = false,
)

val LocalQuietColors = staticCompositionLocalOf { DarkColors }
val LocalReducedMotion = staticCompositionLocalOf { false }

object Q {
    val colors: QuietColors @Composable @ReadOnlyComposable get() = LocalQuietColors.current
    val reducedMotion: Boolean @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

/** System "remove animations" setting. */
@Composable
fun rememberSystemReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

@Composable
fun QuietTheme(dark: Boolean = true, reducedMotion: Boolean = false, content: @Composable () -> Unit) {
    val target = if (dark) DarkColors else LightColors
    // Theme change crossfades 240 ms (DESIGN-NOTES §4).
    val spec = tween<Color>(if (reducedMotion) 0 else 240)
    val colors = target.copy(
        bg = animateColorAsState(target.bg, spec, label = "bg").value,
        ink = animateColorAsState(target.ink, spec, label = "ink").value,
        muted = animateColorAsState(target.muted, spec, label = "muted").value,
    )
    CompositionLocalProvider(
        LocalQuietColors provides colors,
        LocalReducedMotion provides (reducedMotion || rememberSystemReducedMotion()),
        content = content,
    )
}
