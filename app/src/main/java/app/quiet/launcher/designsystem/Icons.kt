package app.quiet.launcher.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Line icons in the Lucide stroke style (1.5 stroke, round caps), drawn on a 24-unit grid. */
enum class QIcon { Camera, Phone, Prev, Next, Play, Pause, ChevronDown, ChevronLeft, ChevronRight, Search, Settings }

@Composable
fun QIconView(icon: QIcon, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp, stroke: Float = 1.5f) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val st = Stroke(width = stroke * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(vararg pts: Float, close: Boolean = false) = Path().apply {
            moveTo(pts[0] * u, pts[1] * u)
            var i = 2
            while (i < pts.size) { lineTo(pts[i] * u, pts[i + 1] * u); i += 2 }
            if (close) close()
        }
        when (icon) {
            QIcon.Camera -> {
                drawRoundRect(tint, Offset(2.5f * u, 6.5f * u), Size(19f * u, 13.5f * u), CornerRadius(2.5f * u), style = st)
                drawPath(p(8f, 6.5f, 9.5f, 4f, 14.5f, 4f, 16f, 6.5f), tint, style = st)
                drawCircle(tint, 3.5f * u, Offset(12f * u, 13f * u), style = st)
            }
            QIcon.Phone -> {
                val path = Path().apply {
                    moveTo(21.5f * u, 16.9f * u)
                    lineTo(21.5f * u, 19.9f * u)
                    cubicTo(21.5f * u, 21f * u, 20.6f * u, 21.9f * u, 19.4f * u, 21.8f * u)
                    cubicTo(10f * u, 21f * u, 3f * u, 14f * u, 2.2f * u, 4.6f * u)
                    cubicTo(2.1f * u, 3.4f * u, 3f * u, 2.5f * u, 4.1f * u, 2.5f * u)
                    lineTo(7.1f * u, 2.5f * u)
                    lineTo(8.6f * u, 7f * u)
                    lineTo(6.6f * u, 9f * u)
                    cubicTo(8f * u, 11.8f * u, 12.2f * u, 16f * u, 15f * u, 17.4f * u)
                    lineTo(17f * u, 15.4f * u)
                    close()
                }
                drawPath(path, tint, style = st)
            }
            QIcon.Prev -> {
                drawPath(p(19f, 5f, 9f, 12f, 19f, 19f, close = true), tint, style = st)
                drawLine(tint, Offset(5f * u, 5f * u), Offset(5f * u, 19f * u), st.width, StrokeCap.Round)
            }
            QIcon.Next -> {
                drawPath(p(5f, 5f, 15f, 12f, 5f, 19f, close = true), tint, style = st)
                drawLine(tint, Offset(19f * u, 5f * u), Offset(19f * u, 19f * u), st.width, StrokeCap.Round)
            }
            QIcon.Play -> drawPath(p(7f, 4.5f, 19f, 12f, 7f, 19.5f, close = true), tint, style = st)
            QIcon.Pause -> {
                drawLine(tint, Offset(9f * u, 5f * u), Offset(9f * u, 19f * u), st.width, StrokeCap.Round)
                drawLine(tint, Offset(15f * u, 5f * u), Offset(15f * u, 19f * u), st.width, StrokeCap.Round)
            }
            QIcon.ChevronDown -> drawPath(p(6f, 9f, 12f, 15f, 18f, 9f), tint, style = st)
            QIcon.ChevronLeft -> drawPath(p(15f, 6f, 9f, 12f, 15f, 18f), tint, style = st)
            QIcon.ChevronRight -> drawPath(p(9f, 6f, 15f, 12f, 9f, 18f), tint, style = st)
            QIcon.Search -> {
                drawCircle(tint, 7f * u, Offset(11f * u, 11f * u), style = st)
                drawLine(tint, Offset(16.2f * u, 16.2f * u), Offset(21f * u, 21f * u), st.width, StrokeCap.Round)
            }
            QIcon.Settings -> drawGear(tint, u, st)
        }
    }
}

private fun DrawScope.drawGear(tint: Color, u: Float, st: Stroke) {
    drawCircle(tint, 3f * u, Offset(12f * u, 12f * u), style = st)
    val path = Path()
    val teeth = 8
    for (i in 0 until teeth * 2) {
        val a = Math.PI * i / teeth
        val r = if (i % 2 == 0) 9.5f else 7.6f
        val x = 12f + r * kotlin.math.cos(a).toFloat()
        val y = 12f + r * kotlin.math.sin(a).toFloat()
        if (i == 0) path.moveTo(x * u, y * u) else path.lineTo(x * u, y * u)
    }
    path.close()
    drawPath(path, tint, style = st)
}
