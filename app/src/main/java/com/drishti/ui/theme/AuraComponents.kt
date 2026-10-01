package com.drishti.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The aura: soft light in the spectrum's colours, drifting and mixing slowly, like
 * something is quietly thinking. It replaces the old glossy sphere everywhere.
 *
 * [energy] 0..1 speeds it up and brightens it — idle sits near 0.3, listening or working
 * near 1. Each colour orbits on its own path, so the mix never repeats exactly.
 */
@Composable
fun AuraField(
    modifier: Modifier = Modifier,
    energy: Float = 0.35f,
    animate: Boolean = true,
) {
    val e by animateFloatAsState(energy.coerceIn(0f, 1f), tween(600), label = "energy")
    val phase = if (animate) {
        rememberInfiniteTransition(label = "aura").animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing)),
            label = "phase",
        ).value
    } else {
        0.6f
    }
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = center
        // Different speeds per colour so the blend keeps changing.
        val speeds = floatArrayOf(1f, -1.3f, 0.8f, -0.6f)
        val spin = 1f + 1.6f * e
        Aura.Spectrum.forEachIndexed { i, color ->
            val a = phase * speeds[i] * spin + i * (PI.toFloat() / 2f)
            val reach = r * (0.32f + 0.12f * e)
            val p = Offset(c.x + cos(a) * reach, c.y + sin(a * 1.3f) * reach)
            val radius = r * (0.78f + 0.1f * sin(phase * 2 + i))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.55f + 0.35f * e), color.copy(alpha = 0f)),
                    center = p,
                    radius = radius,
                ),
                radius = radius,
                center = p,
                blendMode = BlendMode.Screen,
            )
        }
        // A bright core so it reads as light, not paint.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.10f + 0.18f * e), Color.Transparent),
                center = c,
                radius = r * 0.55f,
            ),
            radius = r * 0.55f,
            center = c,
            blendMode = BlendMode.Screen,
        )
    }
}

/** A small aura in a circle: the app's mark in headers and rows. */
@Composable
fun AuraMark(size: Dp = 32.dp, energy: Float = 0.35f, animate: Boolean = true) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Aura.SurfaceHi),
    ) {
        AuraField(Modifier.fillMaxSize(), energy = energy, animate = animate)
    }
}

/** The one main action on a screen: white, quiet, unmistakable. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(Aura.ButtonShape)
            .background(if (enabled) Aura.Text else Aura.SurfaceHi)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) Aura.Bg else Aura.TextTertiary, textAlign = TextAlign.Center)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(Aura.ButtonShape)
            .background(Aura.Surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = Aura.Text, textAlign = TextAlign.Center)
    }
}

/** Small lowercase heading over a group. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Aura.TextTertiary, modifier = modifier.padding(start = 4.dp, bottom = 8.dp))
}

/** A group of rows on one rounded surface. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(Aura.CardShape)
            .background(Aura.Surface),
    ) { content() }
}

/** One row of a group. Tall enough to hit easily; the whole row is the target. */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color = Aura.TextSecondary,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = Aura.Text)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            value?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = valueColor) }
            trailing?.invoke()
            if (onClick != null && trailing == null) Text("›", style = MaterialTheme.typography.titleLarge, color = Aura.TextTertiary)
        }
        if (divider) {
            Box(
                Modifier
                    .padding(start = 18.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Aura.Line),
            )
        }
    }
}

/** On/off switch. On glows in the aura's colours; off is plain. */
@Composable
fun Toggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val knob by animateFloatAsState(if (checked) 1f else 0f, tween(180), label = "knob")
    val track by animateColorAsState(if (checked) Aura.Violet else Aura.Line, tween(180), label = "track")
    Box(
        modifier
            .size(width = 50.dp, height = 30.dp)
            .clip(Aura.PillShape)
            .background(track)
            .clickable(role = Role.Switch) { onCheckedChange(!checked) },
    ) {
        Box(
            Modifier
                .padding(3.dp)
                .offset(x = 20.dp * knob)
                .size(24.dp)
                .clip(CircleShape)
                .background(Aura.Text),
        )
    }
}

/** A dot plus a few words: "ready", "needs permission". */
@Composable
fun StatusLine(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Aura.TextSecondary)
    }
}

/** Quiet explanatory text on a surface. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Aura.TextSecondary,
        modifier = modifier
            .fillMaxWidth()
            .clip(Aura.CardShape)
            .background(Aura.Surface)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    )
}
