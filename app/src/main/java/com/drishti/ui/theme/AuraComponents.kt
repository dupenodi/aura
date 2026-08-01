package com.drishti.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The orb — Aura's whole identity. A radial gradient sphere with the light source
 * up and to the left, wrapped in a soft halo that breathes.
 *
 * [breathing] drives the idle float/pulse; turn it off for static contexts such as
 * list rows, battery saver, or screenshots.
 */
@Composable
fun AuraOrb(
    modifier: Modifier = Modifier,
    size: Dp = 88.dp,
    skin: OrbSkin = OrbSkin.Aurora,
    glow: GlowLevel = GlowLevel.Balanced,
    breathing: Boolean = true,
) {
    val pulse = if (breathing) {
        val transition = rememberInfiniteTransition(label = "orb")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(3400),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "orbPulse",
        ).value
    } else {
        0.5f
    }

    val haloAlpha = (0.55f + 0.35f * pulse) * glow.fraction
    val haloScale = 1f + 0.12f * pulse * glow.scale.coerceAtMost(1f)

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        // Halo bleeds well past the sphere, so the drawing box is deliberately larger.
        Box(
            Modifier
                .size(size * 1.55f)
                .drawBehind {
                    val r = (this.size.minDimension / 2f) * haloScale
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                skin.halo.copy(alpha = haloAlpha),
                                Color.Transparent,
                            ),
                            center = center,
                            radius = r,
                        ),
                        radius = r,
                    )
                },
        )
        Box(
            Modifier
                .size(size)
                .drawBehind {
                    val r = this.size.minDimension / 2f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to skin.inner,
                                0.56f to skin.mid,
                                1f to skin.outer,
                            ),
                            // Light source at 34%/28%, sized to the farthest corner so the
                            // stops land where CSS puts them — anything tighter and the
                            // highlight collapses to a dot.
                            center = Offset(this.size.width * 0.34f, this.size.height * 0.28f),
                            radius = r * 1.95f,
                        ),
                        radius = r,
                    )
                    // Inner rim light keeps the sphere from reading flat.
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.16f)),
                            center = center,
                            radius = r,
                        ),
                        radius = r,
                    )
                },
        )
    }
}

/** The mono uppercase eyebrow that titles nearly every block in the design. */
@Composable
fun AuraEyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Aura.TextGhost,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier,
    )
}

/** Primary action: the cyan→purple gradient slab. */
@Composable
fun AuraPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(Aura.ButtonShape)
            .background(if (enabled) Aura.CtaGradient else Brush.linearGradient(listOf(Aura.LineBright, Aura.LineBright)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) Aura.Void else Aura.TextFaint,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/** Secondary action: outlined, quiet, never competes with the gradient. */
@Composable
fun AuraGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Aura.TextDim,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(Aura.ButtonShape)
            .border(1.dp, Aura.LineBright, Aura.ButtonShape)
            .clickable(onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = color, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Grouped-list container: one rounded card holding hairline-separated rows. */
@Composable
fun AuraCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(Aura.CardShape)
            .background(Aura.Surface)
            .border(1.dp, Aura.Line, Aura.CardShape),
    ) {
        content()
    }
}

/** A settings row: label, optional value, optional chevron. */
@Composable
fun AuraRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    valueColor: Color = Aura.TextFaint,
    showChevron: Boolean = true,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 15.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = Aura.TextMid,
                modifier = Modifier.weight(1f),
            )
            if (value != null) {
                Text(text = value, style = MaterialTheme.typography.bodySmall, color = valueColor)
            }
            trailing?.invoke()
            if (showChevron) {
                Text("›", color = Aura.TextBarely, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (divider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 15.dp)
                    .background(Color(0xFF16161F))
                    .size(height = 1.dp, width = Dp.Unspecified),
            )
        }
    }
}

/** Pill toggle matching the design's gradient-on / grey-off switch. */
@Composable
fun AuraToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(width = 40.dp, height = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) Aura.AccentGradient else Brush.linearGradient(listOf(Aura.LineBright, Aura.LineBright)))
            .clickable { onCheckedChange(!checked) },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 3.dp)
                .size(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (checked) Color.White else Aura.TextGhost),
        )
    }
}

/** Callout used for warnings and "good to know" notes. */
@Composable
fun AuraNote(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = Aura.Warning,
    marker: String = "◆",
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.07f))
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(marker, color = accent, style = MaterialTheme.typography.bodySmall)
        Text(text, style = MaterialTheme.typography.bodySmall, color = Aura.TextDim)
    }
}
