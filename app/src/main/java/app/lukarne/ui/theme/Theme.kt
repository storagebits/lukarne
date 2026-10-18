package app.lukarne.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.lukarne.R

/** Night sky, city lights. Ink is the ground; webcams are the warm lights on it. */
object Palette {
    val Ink = Color(0xFF05070C)
    val Deep = Color(0xFF0A1020)
    val Ocean = Color(0xFF0C1528)
    val Land = Color(0xFF3B4A66)
    val Rim = Color(0xFF6E8BC2)
    val Fg = Color(0xFFF1ECE2)
    val FgMuted = Color(0xB3F1ECE2)
    val FgFaint = Color(0x66F1ECE2)
    val Light = Color(0xFFF2B35B)
    val LightHot = Color(0xFFFFE2B0)
    val Dead = Color(0xFF8A8F99)
}

@Suppress("OPT_IN_USAGE")
object Fonts {
    private fun inter(w: Int) = Font(
        R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)),
    )

    val Inter = FontFamily(inter(300), inter(400), inter(500), inter(600))
    val Serif = FontFamily(
        Font(R.font.instrument_serif, FontWeight.Normal),
        Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
    )
}

object Type {
    /** Place names: the voice of the app. */
    fun place(size: TextUnit, color: Color = Palette.Fg) =
        TextStyle(fontFamily = Fonts.Serif, fontSize = size, color = color, letterSpacing = (-0.01).em)

    /** Small caps-like labels: country, source, hints. */
    fun label(size: TextUnit = 13.sp, color: Color = Palette.FgMuted, weight: Int = 500) = TextStyle(
        fontFamily = Fonts.Inter, fontWeight = FontWeight(weight), fontSize = size, color = color,
        letterSpacing = 0.08.em,
    )

    fun numeric(size: TextUnit, color: Color = Palette.Fg, weight: Int = 400) = TextStyle(
        fontFamily = Fonts.Inter, fontWeight = FontWeight(weight), fontSize = size, color = color,
        fontFeatureSettings = "tnum",
    )
}
