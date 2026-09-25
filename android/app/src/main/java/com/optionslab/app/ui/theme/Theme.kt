package com.optionslab.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * IraAlgo's look: monochrome and minimal. Black on white by day, white on
 * true black by night; colour is used only for what it means - green for a
 * gain, red for a loss, amber for a warning.
 *
 * The property names are the ones every screen already uses:
 * [brass] = primary action colour (black / white), [gold] = emphasis (same),
 * [verdigris] = gain, [oxblood] = loss, [amber] = warning, [paper] = page,
 * [paperDeep] = bars, [card] = card surface, [ink]/[inkSoft]/[inkFaint] =
 * primary/secondary/tertiary text, [rule] = borders and dividers,
 * [onPrimary] = text on a filled primary control.
 */
@Immutable
data class Palette(
    val paper: Color,
    val paperDeep: Color,
    val card: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    val brass: Color,
    val gold: Color,
    val oxblood: Color,
    val verdigris: Color,
    val amber: Color,
    val rule: Color,
    val seal: Color,
    val dark: Boolean,
    val onPrimary: Color,
    /** A quiet fill for chips, inputs and pressed rows. */
    val chip: Color,
) {
    val primary: Color get() = brass
    val gain: Color get() = verdigris
    val loss: Color get() = oxblood
}

val Light = Palette(
    paper = Color(0xFFFFFFFF), paperDeep = Color(0xFFFFFFFF), card = Color(0xFFFFFFFF),
    ink = Color(0xFF000000), inkSoft = Color(0xFF5F6368), inkFaint = Color(0xFF6E6E73),
    brass = Color(0xFF000000), gold = Color(0xFF000000), oxblood = Color(0xFFE0322B),
    verdigris = Color(0xFF00A86B), amber = Color(0xFFB45309), rule = Color(0xFFECECEC),
    seal = Color(0xFFE0322B), dark = false, onPrimary = Color(0xFFFFFFFF), chip = Color(0xFFF2F2F2),
)

val Dark = Palette(
    paper = Color(0xFF000000), paperDeep = Color(0xFF000000), card = Color(0xFF111111),
    ink = Color(0xFFFFFFFF), inkSoft = Color(0xFFA1A1A6), inkFaint = Color(0xFF8E8E93),
    brass = Color(0xFFFFFFFF), gold = Color(0xFFFFFFFF), oxblood = Color(0xFFFF5A52),
    verdigris = Color(0xFF1FCC84), amber = Color(0xFFF5B942), rule = Color(0xFF262626),
    seal = Color(0xFFFF5A52), dark = true, onPrimary = Color(0xFF000000), chip = Color(0xFF1C1C1E),
)

val LocalPalette = staticCompositionLocalOf { Light }

/** The platform sans-serif throughout; figures use tabular digits so columns line up. */
object Fonts {
    val display: FontFamily = FontFamily.SansSerif
    val body: FontFamily = FontFamily.SansSerif
    val figures: FontFamily = FontFamily.SansSerif
}

private const val TABULAR = "tnum"

object Type {
    val masthead = TextStyle(fontFamily = Fonts.display, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.3).sp)
    val title = TextStyle(fontFamily = Fonts.display, fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = (-0.1).sp)
    val label = TextStyle(fontFamily = Fonts.display, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.2.sp)
    val body = TextStyle(fontFamily = Fonts.body, fontSize = 15.sp, lineHeight = 21.sp)
    val bodySmall = TextStyle(fontFamily = Fonts.body, fontSize = 13.sp, lineHeight = 18.sp)
    /** Secondary explanatory text (plain, not italic). */
    val italic = TextStyle(fontFamily = Fonts.body, fontSize = 13.sp, lineHeight = 18.sp)
    val figure = TextStyle(fontFamily = Fonts.figures, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, fontFeatureSettings = TABULAR)
    val figureLarge = TextStyle(fontFamily = Fonts.figures, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.5).sp, fontFeatureSettings = TABULAR)
    val figureHuge = TextStyle(fontFamily = Fonts.figures, fontWeight = FontWeight.Bold, fontSize = 36.sp, letterSpacing = (-0.8).sp, fontFeatureSettings = TABULAR)
}

/** [mode]: "light", "dark" or "system" (older saved values "parchment"/"mahogany" map to light/dark). */
@Composable
fun IraAlgoTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) { "light", "parchment" -> false; "dark", "mahogany" -> true; else -> isSystemInDarkTheme() }
    val p = if (dark) Dark else Light
    val scheme = if (dark) darkColorScheme(
        primary = p.brass, onPrimary = p.onPrimary, secondary = p.inkSoft, error = p.oxblood,
        background = p.paper, surface = p.card, onBackground = p.ink, onSurface = p.ink,
        surfaceVariant = p.chip, onSurfaceVariant = p.inkSoft, outline = p.rule,
    ) else lightColorScheme(
        primary = p.brass, onPrimary = p.onPrimary, secondary = p.inkSoft, error = p.oxblood,
        background = p.paper, surface = p.card, onBackground = p.ink, onSurface = p.ink,
        surfaceVariant = p.chip, onSurfaceVariant = p.inkSoft, outline = p.rule,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
