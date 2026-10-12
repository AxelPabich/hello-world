package de.pabich.buzzer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import de.pabich.buzzer.R
import de.pabich.buzzer.core.Display

/** Farben eines Spielzustands: Hintergrund, Buzzer-Kern, Schrift, Leuchten. */
data class StateColors(val ground: Color, val core: Color, val text: Color, val glow: Color)

/**
 * Ein Design der App. Derzeit gibt es nur „TV-Quizshow“; weitere Designs werden hier als
 * zusätzliche Einträge in [Themes.all] ergänzt und sind dann in den App-Einstellungen wählbar.
 */
data class BuzzerTheme(
    val id: String,
    val name: String,
    val ground: Color,
    val panel: Color,
    val line: Color,
    val accent: Color,
    val accent2: Color,
    val text: Color,
    val muted: Color,
    val logo: Color,
    val good: Color,
    val bad: Color,
    val display: FontFamily,
    val body: FontFamily,
    val states: Map<Display, StateColors>,
) {
    fun state(d: Display): StateColors = states.getValue(d)
}

private val Anton = FontFamily(Font(R.font.anton_regular))
private val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_bold, FontWeight.Bold),
)

object Themes {
    val QUIZSHOW = BuzzerTheme(
        id = "quizshow",
        name = "TV-Quizshow",
        ground = Color(0xFF0A0F2C),
        panel = Color(0xFF141B45),
        line = Color(0xFF2A3570),
        accent = Color(0xFFFFC83D),
        accent2 = Color(0xFF3BE3F5),
        text = Color(0xFFF4F1E8),
        muted = Color(0xFFB9BFE0),
        logo = Color(0xFFE8382F),
        good = Color(0xFF1FA34A),
        bad = Color(0xFFC62828),
        display = Anton,
        body = Barlow,
        states = mapOf(
            Display.BLACK to StateColors(Color(0xFF0A0F2C), Color(0xFF141B45), Color(0xFFFFC83D), Color(0x40FFC83D)),
            Display.BLUE to StateColors(Color(0xFF173A9E), Color(0xFF2F5BE0), Color.White, Color(0x806F9BFF)),
            Display.YELLOW to StateColors(Color(0xFFE0A800), Color(0xFFFFD84A), Color(0xFF1A1300), Color(0xB3FFD84A)),
            Display.PRESSED to StateColors(Color(0xFF5E4B00), Color(0xFF8A7200), Color(0xFFFFE9A3), Color(0x40FFD84A)),
            Display.GREEN to StateColors(Color(0xFF127A39), Color(0xFF1FA34A), Color.White, Color(0x8C50E682)),
            Display.RED to StateColors(Color(0xFF8E1B1B), Color(0xFFC62828), Color.White, Color(0x73FF5A5A)),
            Display.PINK to StateColors(Color(0xFF8E1450), Color(0xFFE91E63), Color.White, Color(0x80FF7AB0)),
            Display.GRAY to StateColors(Color(0xFF2C3150), Color(0xFF4A5075), Color(0xFFD5D8EA), Color(0x30B9BFE0)),
        ),
    )

    val all = listOf(QUIZSHOW)

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: QUIZSHOW
}

val LocalBuzzerTheme = staticCompositionLocalOf { Themes.QUIZSHOW }

@Composable
fun BuzzerThemeProvider(theme: BuzzerTheme, content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = theme.accent,
        onPrimary = theme.ground,
        secondary = theme.accent2,
        onSecondary = theme.ground,
        background = theme.ground,
        onBackground = theme.text,
        surface = theme.panel,
        onSurface = theme.text,
        surfaceVariant = theme.panel,
        onSurfaceVariant = theme.muted,
        surfaceContainerHigh = theme.panel,
        surfaceContainerHighest = theme.line,
        outline = theme.line,
        error = Color(0xFFFF7A7A),
        tertiaryContainer = theme.line,
        onTertiaryContainer = theme.text,
    )
    val base = Typography()
    fun TextStyle.body() = copy(fontFamily = theme.body)
    fun TextStyle.display() = copy(fontFamily = theme.display, fontWeight = FontWeight.Normal)
    val typography = Typography(
        displayLarge = base.displayLarge.display(),
        displayMedium = base.displayMedium.display(),
        displaySmall = base.displaySmall.display(),
        headlineLarge = base.headlineLarge.display(),
        headlineMedium = base.headlineMedium.display(),
        headlineSmall = base.headlineSmall.display(),
        titleLarge = base.titleLarge.body().copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.body().copy(fontWeight = FontWeight.Bold),
        titleSmall = base.titleSmall.body().copy(fontWeight = FontWeight.Bold),
        bodyLarge = base.bodyLarge.body(),
        bodyMedium = base.bodyMedium.body(),
        bodySmall = base.bodySmall.body(),
        labelLarge = base.labelLarge.body().copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.body(),
        labelSmall = base.labelSmall.body(),
    )
    CompositionLocalProvider(LocalBuzzerTheme provides theme) {
        MaterialTheme(colorScheme = colors, typography = typography, content = content)
    }
}
