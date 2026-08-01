package com.drishti.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drishti.R
import androidx.compose.ui.unit.sp

/**
 * Aura's visual language, lifted directly from the product design doc.
 *
 * Aura is a dark-only product on purpose: it floats over other apps, and a light
 * chrome would fight whatever is underneath it.
 */
object Aura {
    // Backgrounds — deepest first.
    val Void = Color(0xFF07070B)
    val Bg = Color(0xFF0B0B13)
    val Surface = Color(0xFF10101A)
    val SurfaceAlt = Color(0xFF0E0E16)
    val SurfaceRaised = Color(0xFF11111B)
    val Chip = Color(0xFF14141F)

    // Hairlines.
    val Line = Color(0xFF1A1A26)
    val LineBright = Color(0xFF22222F)
    val LineSoft = Color(0xFF1E1E2C)

    // Text.
    val TextHi = Color(0xFFF2F0FF)
    val TextBody = Color(0xFFECEAF5)
    val TextMid = Color(0xFFDCD9E8)
    val TextDim = Color(0xFF9C99B4)
    val TextFaint = Color(0xFF75738A)
    val TextGhost = Color(0xFF6D6A85)
    val TextMuted = Color(0xFF5E5C72)
    val TextBarely = Color(0xFF46445A)

    // Accents.
    val Cyan = Color(0xFF4DE8FF)
    val CyanBright = Color(0xFF7EF2FF)
    val Purple = Color(0xFFA06BFF)
    val PurpleLight = Color(0xFFC39BFF)
    val PurpleDeep = Color(0xFF6B4DFF)
    val Pink = Color(0xFFFF7AD9)
    val Warning = Color(0xFFFFCF5C)
    val Danger = Color(0xFFFF7A8F)
    val DangerLight = Color(0xFFFF9AA9)

    /** The signature call-to-action wash. */
    val CtaGradient = Brush.linearGradient(listOf(CyanBright, Purple))

    /** Accent sweep used for progress and sliders. */
    val AccentGradient = Brush.linearGradient(listOf(Cyan, Purple))

    val CardShape = RoundedCornerShape(16.dp)
    val CardShapeLarge = RoundedCornerShape(18.dp)
    val ChipShape = RoundedCornerShape(percent = 50)
    val ButtonShape = RoundedCornerShape(15.dp)
}

/** The four selectable orb identities from the presence picker. */
enum class OrbSkin(
    val label: String,
    val inner: Color,
    val mid: Color,
    val outer: Color,
    val halo: Color,
) {
    Aurora("Aurora", Color(0xFF7EF2FF), Color(0xFF6B4DFF), Color(0xFF2A1361), Color(0xFF4DE8FF)),
    Bloom("Bloom", Color(0xFFFFD6F5), Color(0xFFFF5ECB), Color(0xFF4A0D43), Color(0xFFFF5ECB)),
    Fern("Fern", Color(0xFFD8FFD0), Color(0xFF3DDC8F), Color(0xFF0C3D2C), Color(0xFF3DDC8F)),
    Ember("Ember", Color(0xFFFFF0C9), Color(0xFFFFAB2E), Color(0xFF4A2A05), Color(0xFFFFAB2E)),
    ;

    /** Colour stops for the orb's radial gradient, light source at 34%/28%. */
    val stops: List<Color> get() = listOf(inner, mid, outer)

    companion object {
        fun fromOrdinal(i: Int): OrbSkin = entries.getOrElse(i) { Aurora }
    }
}

/** How brightly the orb glows — dimmer is cheaper on battery and on the eyes. */
enum class GlowLevel(val label: String, val fraction: Float, val scale: Float) {
    Subtle("Subtle", 0.30f, 0.45f),
    Balanced("Balanced", 0.62f, 1f),
    FullNeon("Full neon", 1f, 1.45f),
    ;

    companion object {
        fun fromOrdinal(i: Int): GlowLevel = entries.getOrElse(i) { Balanced }
    }
}

/**
 * The design's typefaces, bundled.
 *
 * Space Grotesk ships as a variable font, so each weight is the same file pinned to a
 * different point on the weight axis rather than a separate static cut.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val Display = FontFamily(
    Font(
        R.font.space_grotesk,
        FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.space_grotesk,
        FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.space_grotesk,
        FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    Font(
        R.font.space_grotesk,
        FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

val AuraMono = FontFamily(
    Font(R.font.ibm_plex_mono, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

private val AuraTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-1).sp,
        color = Aura.TextHi,
    ),
    headlineLarge = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 29.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.6).sp,
        color = Aura.TextHi,
    ),
    headlineMedium = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp,
        color = Aura.TextHi,
    ),
    titleLarge = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
        color = Aura.TextHi,
    ),
    titleMedium = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        color = Aura.TextHi,
    ),
    bodyLarge = TextStyle(
        fontFamily = Display,
        fontSize = 15.sp,
        lineHeight = 24.sp,
        color = Aura.TextDim,
    ),
    bodyMedium = TextStyle(
        fontFamily = Display,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        color = Aura.TextDim,
    ),
    bodySmall = TextStyle(
        fontFamily = Display,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = Aura.TextFaint,
    ),
    // The mono "eyebrow" label that heads most sections.
    labelSmall = TextStyle(
        fontFamily = AuraMono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.3.sp,
        color = Aura.TextGhost,
    ),
)

private val AuraColorScheme = darkColorScheme(
    primary = Aura.Cyan,
    onPrimary = Aura.Void,
    secondary = Aura.Purple,
    onSecondary = Aura.Void,
    background = Aura.Bg,
    onBackground = Aura.TextHi,
    surface = Aura.Surface,
    onSurface = Aura.TextMid,
    surfaceVariant = Aura.SurfaceAlt,
    onSurfaceVariant = Aura.TextDim,
    error = Aura.Danger,
    outline = Aura.LineBright,
)

@Composable
fun AuraTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Aura is dark-only by design; the parameter exists so callers read naturally.
    MaterialTheme(
        colorScheme = AuraColorScheme,
        typography = AuraTypography,
        content = content,
    )
}
