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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.optionslab.app.R

/**
 * An antiquarian's ledger: parchment and iron-gall ink by day, a mahogany
 * study lit by brass by night. Wins are verdigris, losses oxblood - the two
 * colours a Victorian counting-house actually used.
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
)

val Parchment = Palette(
    paper = Color(0xFFF1E6CC), paperDeep = Color(0xFFE2D1A8), card = Color(0xFFF8F0DC),
    ink = Color(0xFF2B1D0E), inkSoft = Color(0xFF5E4630), inkFaint = Color(0xFF9A8263),
    brass = Color(0xFF9C7A3C), gold = Color(0xFFB8912F), oxblood = Color(0xFF7B1E1E),
    verdigris = Color(0xFF2F6B55), amber = Color(0xFFB0671B), rule = Color(0xFFC9B48A),
    seal = Color(0xFF8E2323), dark = false,
)

val Mahogany = Palette(
    paper = Color(0xFF1A110B), paperDeep = Color(0xFF120B07), card = Color(0xFF26190F),
    ink = Color(0xFFF0E2C0), inkSoft = Color(0xFFCDB68C), inkFaint = Color(0xFF8C7652),
    brass = Color(0xFFC9A45C), gold = Color(0xFFE0B94F), oxblood = Color(0xFFD0605A),
    verdigris = Color(0xFF7FC0A2), amber = Color(0xFFE09A48), rule = Color(0xFF4A3622),
    seal = Color(0xFFA83232), dark = true,
)

val LocalPalette = staticCompositionLocalOf { Parchment }

object Fonts {
    val display = FontFamily(Font(R.font.cinzel_regular, FontWeight.Normal), Font(R.font.cinzel_bold, FontWeight.Bold))
    val body = FontFamily(
        Font(R.font.cormorant_medium, FontWeight.Normal),
        Font(R.font.cormorant_medium, FontWeight.Medium),
        Font(R.font.cormorant_bold, FontWeight.Bold),
        Font(R.font.cormorant_italic, FontWeight.Normal, FontStyle.Italic),
    )
    val figures = FontFamily(Font(R.font.special_elite))
}

object Type {
    val masthead = TextStyle(fontFamily = Fonts.display, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 2.sp)
    val title = TextStyle(fontFamily = Fonts.display, fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = 1.5.sp)
    val label = TextStyle(fontFamily = Fonts.display, fontSize = 11.sp, letterSpacing = 1.6.sp)
    val body = TextStyle(fontFamily = Fonts.body, fontSize = 17.sp, lineHeight = 22.sp)
    val bodySmall = TextStyle(fontFamily = Fonts.body, fontSize = 15.sp, lineHeight = 19.sp)
    val italic = TextStyle(fontFamily = Fonts.body, fontStyle = FontStyle.Italic, fontSize = 15.sp, lineHeight = 19.sp)
    val figure = TextStyle(fontFamily = Fonts.figures, fontSize = 15.sp)
    val figureLarge = TextStyle(fontFamily = Fonts.figures, fontSize = 30.sp)
    val figureHuge = TextStyle(fontFamily = Fonts.figures, fontSize = 40.sp)
}

@Composable
fun IraAlgoTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) { "parchment" -> false; "mahogany" -> true; else -> isSystemInDarkTheme() }
    val p = if (dark) Mahogany else Parchment
    val scheme = if (dark) darkColorScheme(
        primary = p.brass, onPrimary = p.paperDeep, secondary = p.verdigris, error = p.oxblood,
        background = p.paper, surface = p.card, onBackground = p.ink, onSurface = p.ink,
        surfaceVariant = p.paperDeep, onSurfaceVariant = p.inkSoft, outline = p.rule,
    ) else lightColorScheme(
        primary = p.brass, onPrimary = p.card, secondary = p.verdigris, error = p.oxblood,
        background = p.paper, surface = p.card, onBackground = p.ink, onSurface = p.ink,
        surfaceVariant = p.paperDeep, onSurfaceVariant = p.inkSoft, outline = p.rule,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
