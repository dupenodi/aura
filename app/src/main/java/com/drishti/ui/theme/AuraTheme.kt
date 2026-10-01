package com.drishti.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drishti.R

/**
 * Aura's visual language: quiet and clean, so the one thing with colour — the aura — is
 * where the eye goes.
 *
 * Near-black surfaces, a single neutral type family (Geist), lowercase throughout, and
 * the aura spectrum used only for the living gradient and for "this is on". Every text
 * colour here meets WCAG AA (4.5:1) on [Bg]; the audience includes people with tired eyes.
 */
object Aura {
    val Bg = Color(0xFF09090B)
    val Surface = Color(0xFF141417)
    val SurfaceHi = Color(0xFF1C1C21)
    val Line = Color(0xFF26262C)

    val Text = Color(0xFFF4F4F5)
    val TextSecondary = Color(0xFFB4B4BC)
    val TextTertiary = Color(0xFF8B8B94)

    // The aura spectrum.
    val Violet = Color(0xFFA78BFA)
    val Indigo = Color(0xFF818CF8)
    val Blue = Color(0xFF60A5FA)
    val Teal = Color(0xFF5EEAD4)
    val Pink = Color(0xFFF0ABFC)

    val Positive = Color(0xFF86EFAC)
    val Warning = Color(0xFFFCD34D)
    val Danger = Color(0xFFFCA5A5)

    val Spectrum = listOf(Violet, Blue, Teal, Pink)

    val CardShape = RoundedCornerShape(20.dp)
    val ButtonShape = RoundedCornerShape(16.dp)
    val PillShape = RoundedCornerShape(percent = 50)
}

val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
)

val GeistMono = FontFamily(Font(R.font.geist_mono, FontWeight.Normal))

/**
 * Sizes start larger than most apps: body copy is 16sp, and everything scales with the
 * phone's font setting, which many of Aura's users have turned up.
 */
private val AuraTypography = Typography(
    displayLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1.2).sp, color = Aura.Text),
    headlineLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.8).sp, color = Aura.Text),
    headlineMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.5).sp, color = Aura.Text),
    titleLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp, color = Aura.Text),
    titleMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp, color = Aura.Text),
    bodyLarge = TextStyle(fontFamily = Geist, fontSize = 17.sp, lineHeight = 26.sp, color = Aura.TextSecondary),
    bodyMedium = TextStyle(fontFamily = Geist, fontSize = 16.sp, lineHeight = 24.sp, color = Aura.TextSecondary),
    bodySmall = TextStyle(fontFamily = Geist, fontSize = 14.sp, lineHeight = 20.sp, color = Aura.TextTertiary),
    labelLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 20.sp, color = Aura.Text),
    labelMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp, color = Aura.TextTertiary),
    labelSmall = TextStyle(fontFamily = GeistMono, fontSize = 12.sp, lineHeight = 16.sp, color = Aura.TextTertiary),
)

private val AuraColorScheme = darkColorScheme(
    primary = Aura.Text,
    onPrimary = Aura.Bg,
    secondary = Aura.Violet,
    onSecondary = Aura.Bg,
    background = Aura.Bg,
    onBackground = Aura.Text,
    surface = Aura.Surface,
    onSurface = Aura.Text,
    surfaceVariant = Aura.SurfaceHi,
    onSurfaceVariant = Aura.TextSecondary,
    error = Aura.Danger,
    outline = Aura.Line,
)

/** Aura is dark by design: it floats over other apps, and light chrome would fight them. */
@Composable
fun AuraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AuraColorScheme, typography = AuraTypography, content = content)
}
