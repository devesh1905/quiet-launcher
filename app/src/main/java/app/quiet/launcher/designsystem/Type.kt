package app.quiet.launcher.designsystem

import android.content.Context
import android.content.res.AssetManager
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.quiet.launcher.R
import app.quiet.launcher.core.model.LockFont

/**
 * Two families on Home (Fraunces + Geist), lock face + Geist on lock screens (DESIGN-NOTES §2, §8.2).
 * Variable axes are set per role, so each role gets its own FontFamily instance.
 */
@OptIn(ExperimentalTextApi::class)
object Fonts {
    /**
     * Variable fonts load from assets: Android only honours variation axes reliably through
     * Typeface.Builder on asset fonts (resource fonts fell back to the default black instance).
     * Call [init] from Application.onCreate before any UI.
     */
    private lateinit var assets: AssetManager
    fun init(context: Context) { assets = context.applicationContext.assets }

    private fun asset(path: String, weight: FontWeight, style: FontStyle, vararg axes: FontVariation.Setting) =
        Font(path, assets, weight, style, FontVariation.Settings(*axes))

    private fun fraunces(opsz: Float, soft: Float, italic: Boolean = false) = FontFamily(
        asset(
            if (italic) "fonts/fraunces_italic.ttf" else "fonts/fraunces.ttf",
            FontWeight.Light, if (italic) FontStyle.Italic else FontStyle.Normal,
            FontVariation.weight(300), FontVariation.Setting("opsz", opsz),
            FontVariation.Setting("SOFT", soft), FontVariation.Setting("WONK", 0f),
        )
    )

    val FrauncesClock by lazy { fraunces(144f, 70f) }
    val FrauncesNumber by lazy { fraunces(144f, 60f) }
    val FrauncesTitle by lazy { fraunces(72f, 60f) }
    val FrauncesItalic by lazy { fraunces(48f, 100f, italic = true) }

    val Geist by lazy {
        FontFamily(
            asset("fonts/geist.ttf", FontWeight.Normal, FontStyle.Normal, FontVariation.weight(400)),
            asset("fonts/geist.ttf", FontWeight.Medium, FontStyle.Normal, FontVariation.weight(500)),
        )
    }

    private fun bodoni(opsz: Float, italic: Boolean = false) = FontFamily(
        asset(
            if (italic) "fonts/bodoni_moda_italic.ttf" else "fonts/bodoni_moda.ttf",
            FontWeight.Normal, if (italic) FontStyle.Italic else FontStyle.Normal,
            FontVariation.weight(400), FontVariation.Setting("opsz", opsz),
        )
    )

    val BodoniDisplay by lazy { bodoni(96f) }
    val BodoniLabel by lazy { bodoni(24f, italic = true) }
    val Instrument = FontFamily(Font(R.font.instrument_serif, FontWeight.Normal))
    val InstrumentItalic = FontFamily(Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic))
    val Shoulders by lazy {
        FontFamily(asset("fonts/big_shoulders.ttf", FontWeight.Light, FontStyle.Normal, FontVariation.weight(300)))
    }
}

/** The lock face chosen in settings (Bodoni 96, Instrument 108, Shoulders 120 clock sizes). */
data class LockFace(
    val display: FontFamily,
    val label: FontFamily,
    val labelItalic: Boolean,
    val clockSp: Float,
    val posterSp: Float,
    val name: String,
)

fun lockFace(font: LockFont): LockFace = when (font) {
    LockFont.BODONI -> LockFace(Fonts.BodoniDisplay, Fonts.BodoniLabel, true, 96f, 252f, "Bodoni Moda")
    LockFont.INSTRUMENT -> LockFace(Fonts.Instrument, Fonts.InstrumentItalic, true, 108f, 280f, "Instrument Serif")
    LockFont.SHOULDERS -> LockFace(Fonts.Shoulders, Fonts.Shoulders, false, 120f, 290f, "Big Shoulders")
}

private val Trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

object QType {
    fun clock(size: TextUnit = 118.sp) = TextStyle(
        fontFamily = Fonts.FrauncesClock, fontSize = size, lineHeight = size * 0.86f,
        letterSpacing = (-0.035).em, fontFeatureSettings = "tnum, lnum", lineHeightStyle = Trim,
    )

    fun number(size: TextUnit = 44.sp) = TextStyle(
        fontFamily = Fonts.FrauncesNumber, fontSize = size, lineHeight = size, fontFeatureSettings = "tnum, lnum",
        lineHeightStyle = Trim, letterSpacing = (-0.02).em,
    )

    fun title(size: TextUnit = 40.sp) =
        TextStyle(fontFamily = Fonts.FrauncesTitle, fontSize = size, lineHeight = size * 1.05f)

    fun question(size: TextUnit = 34.sp) = TextStyle(
        fontFamily = Fonts.FrauncesItalic, fontStyle = FontStyle.Italic, fontSize = size, lineHeight = size * 1.12f,
    )

    fun labelTitle(size: TextUnit = 24.sp) = TextStyle(
        fontFamily = Fonts.FrauncesItalic, fontStyle = FontStyle.Italic, fontSize = size, lineHeight = size * 1.12f,
    )

    fun favourite(size: TextUnit = 26.sp) =
        TextStyle(fontFamily = Fonts.Geist, fontSize = size, lineHeight = size * 1.2f, letterSpacing = (-0.01).em)

    fun row(size: TextUnit = 20.sp) = TextStyle(fontFamily = Fonts.Geist, fontSize = size, lineHeight = size * 1.25f)
    fun body(size: TextUnit = 17.sp) = TextStyle(fontFamily = Fonts.Geist, fontSize = size, lineHeight = size * 1.4f)
    fun secondary(size: TextUnit = 15.sp) =
        TextStyle(fontFamily = Fonts.Geist, fontSize = size, lineHeight = size * 1.5f, fontFeatureSettings = "tnum")

    fun small(size: TextUnit = 12.sp) =
        TextStyle(fontFamily = Fonts.Geist, fontSize = size, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
}
