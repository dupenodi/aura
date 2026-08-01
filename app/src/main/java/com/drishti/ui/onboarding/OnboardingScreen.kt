package com.drishti.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drishti.data.AuraMode
import com.drishti.ui.theme.Aura
import com.drishti.ui.theme.AuraCard
import com.drishti.ui.theme.AuraEyebrow
import com.drishti.ui.theme.AuraGhostButton
import com.drishti.ui.theme.AuraNote
import com.drishti.ui.theme.AuraOrb
import com.drishti.ui.theme.AuraPrimaryButton
import com.drishti.ui.theme.GlowLevel
import com.drishti.ui.theme.OrbSkin

/** Permission state as the onboarding flow needs to see it. */
data class PermissionState(
    val accessibility: Boolean,
    val overlay: Boolean,
    val microphone: Boolean,
)

/**
 * Onboarding: no account, and nothing is asked for before it has been earned.
 * Presence and mode come first so the user owns the thing before granting it power.
 */
@Composable
fun OnboardingFlow(
    permissions: PermissionState,
    orbSkin: OrbSkin,
    glow: GlowLevel,
    mode: AuraMode,
    onOrbSkin: (OrbSkin) -> Unit,
    onGlow: (GlowLevel) -> Unit,
    onMode: (AuraMode) -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestMic: () -> Unit,
    onFinish: (firstTask: String?) -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    val lastStep = 4

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF1B1440), Aura.Bg),
                    radius = 1400f,
                ),
            )
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "onboarding",
        ) { current ->
            when (current) {
                0 -> WelcomeStep(orbSkin, glow) { step = 1 }
                1 -> PresenceStep(orbSkin, glow, onOrbSkin, onGlow) { step = 2 }
                2 -> ModeStep(mode, onMode) { step = 3 }
                3 -> PermissionsStep(
                    permissions = permissions,
                    onRequestAccessibility = onRequestAccessibility,
                    onRequestOverlay = onRequestOverlay,
                    onRequestMic = onRequestMic,
                    onContinue = { step = 4 },
                )
                else -> FirstTaskStep(orbSkin, glow, onFinish)
            }
        }

        if (step in 1..lastStep) {
            StepDots(
                current = step,
                total = lastStep,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp),
            )
        }
    }
}

@Composable
private fun StepDots(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            Box(
                Modifier
                    .height(4.dp)
                    .width(if (i == current - 1) 20.dp else 8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i <= current - 1) Aura.Cyan else Aura.LineBright),
            )
        }
    }
}

@Composable
private fun StepScaffold(
    eyebrow: String,
    title: String,
    subtitle: String,
    footer: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 30.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AuraEyebrow(eyebrow, color = Aura.Cyan)
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(4.dp))
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            content()
        }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 26.dp)) { footer() }
    }
}

@Composable
private fun WelcomeStep(skin: OrbSkin, glow: GlowLevel, onContinue: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AuraOrb(size = 88.dp, skin = skin, glow = glow)
        Spacer(Modifier.height(40.dp))
        Text(
            "Hi. I live on top of your apps.",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Ask me anything you'd do by tapping — I'll point at the next step, " +
                "or take the steps for you.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(48.dp))
        AuraPrimaryButton("Set up in 4 steps", onContinue)
        Spacer(Modifier.height(12.dp))
        Text(
            "Takes about a minute",
            style = MaterialTheme.typography.bodySmall,
            color = Aura.TextGhost,
        )
    }
}

@Composable
private fun PresenceStep(
    skin: OrbSkin,
    glow: GlowLevel,
    onSkin: (OrbSkin) -> Unit,
    onGlow: (GlowLevel) -> Unit,
    onContinue: () -> Unit,
) {
    StepScaffold(
        eyebrow = "Step 1 of 4",
        title = "Pick a presence",
        subtitle = "How the orb looks and how loudly it glows.",
        footer = { AuraPrimaryButton("Continue", onContinue) },
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            AuraOrb(size = 78.dp, skin = skin, glow = glow)
            Spacer(Modifier.height(30.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OrbSkin.entries.forEach { option ->
                    val selected = option == skin
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(
                                Brush.radialGradient(
                                    colorStops = arrayOf(
                                        0f to option.inner,
                                        0.56f to option.mid,
                                        1f to option.outer,
                                    ),
                                    center = Offset(18f, 15f),
                                    radius = 100f,
                                ),
                            )
                            .border(
                                width = 2.dp,
                                color = if (selected) Color.White else Color.Transparent,
                                shape = RoundedCornerShape(26.dp),
                            )
                            .clickable { onSkin(option) },
                    )
                }
            }

            Spacer(Modifier.height(34.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "Glow intensity",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        glow.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Aura.Cyan,
                    )
                }
                // Tapping the bar cycles the three levels — matches the design's control.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF1C1C28))
                        .clickable {
                            onGlow(GlowLevel.fromOrdinal((glow.ordinal + 1) % GlowLevel.entries.size))
                        },
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(glow.fraction)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Aura.AccentGradient),
                    )
                }
                Text(
                    "Dimmer uses less battery and is easier on light-sensitive eyes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Aura.TextGhost,
                )
            }
        }
    }
}

@Composable
private fun ModeStep(mode: AuraMode, onMode: (AuraMode) -> Unit, onContinue: () -> Unit) {
    StepScaffold(
        eyebrow = "Step 2 of 4",
        title = "How it should behave",
        subtitle = "You can change this any time in Settings, or per app.",
        footer = { AuraPrimaryButton("Continue", onContinue) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ModeOption(
                title = "Guide me",
                body = "The cursor points at the next thing to tap and waits. " +
                    "Your finger does everything.",
                selected = mode == AuraMode.Guide,
                accent = Aura.Cyan,
                onClick = { onMode(AuraMode.Guide) },
            )
            ModeOption(
                title = "Do it for me",
                body = "The cursor taps for you, slowly enough to follow. " +
                    "Payments and deletions always ask first.",
                selected = mode == AuraMode.Auto,
                accent = Aura.Purple,
                onClick = { onMode(AuraMode.Auto) },
            )
            Spacer(Modifier.height(4.dp))
            AuraNote("Most people start on Guide, then switch. You can change it whenever.")
        }
    }
}

@Composable
fun ModeOption(
    title: String,
    body: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) {
                    Brush.linearGradient(
                        listOf(accent.copy(alpha = 0.14f), Aura.Purple.copy(alpha = 0.06f)),
                    )
                } else {
                    Brush.linearGradient(listOf(Aura.Surface, Aura.Surface))
                },
            )
            .border(1.5.dp, if (selected) accent else Aura.LineBright, shape)
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .border(
                        2.dp,
                        if (selected) accent else Color(0xFF3A3A4C),
                        RoundedCornerShape(11.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(accent),
                    )
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) Aura.TextHi else Aura.TextMid,
            )
        }
        Text(body, style = MaterialTheme.typography.bodySmall, color = Aura.TextDim)
    }
}

@Composable
private fun PermissionsStep(
    permissions: PermissionState,
    onRequestAccessibility: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestMic: () -> Unit,
    onContinue: () -> Unit,
) {
    // Accessibility and overlay are what make Aura work at all; the mic is optional
    // because typing is a genuine alternative.
    val essentialsGranted = permissions.accessibility && permissions.overlay

    StepScaffold(
        eyebrow = "Step 3 of 4",
        title = "What I need",
        subtitle = "Each one is asked in context. The next screen is Android's, not mine.",
        footer = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AuraPrimaryButton(
                    text = if (essentialsGranted) "Continue" else "Grant to continue",
                    onClick = onContinue,
                    enabled = essentialsGranted,
                )
                if (!essentialsGranted) {
                    Text(
                        "Without these I can't see the screen or draw on top of it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Aura.TextGhost,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PermissionCard(
                title = "Let me read the screen",
                body = "Android calls this an Accessibility service. It's how I know what " +
                    "buttons are on screen so I can point at the right one.",
                bullets = listOf(
                    true to "Reads button labels and layout, on your phone only",
                    true to "Nothing is uploaded, stored, or used for training",
                    false to "Password fields and banking apps are skipped automatically",
                ),
                granted = permissions.accessibility,
                onGrant = onRequestAccessibility,
            )
            PermissionCard(
                title = "Let me draw on top",
                body = "This is how the orb, the cursor and the bubbles appear over your apps.",
                bullets = listOf(true to "Only draws — it can't touch your apps on its own"),
                granted = permissions.overlay,
                onGrant = onRequestOverlay,
            )
            PermissionCard(
                title = "Let me listen",
                body = "So you can hold the orb and just say what you want.",
                bullets = listOf(true to "Only while you're holding the orb — never in the background"),
                granted = permissions.microphone,
                optional = true,
                onGrant = onRequestMic,
            )
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    body: String,
    bullets: List<Pair<Boolean, String>>,
    granted: Boolean,
    onGrant: () -> Unit,
    optional: Boolean = false,
) {
    AuraCard {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (granted) {
                    Text("Granted", style = MaterialTheme.typography.bodySmall, color = Aura.Cyan)
                } else if (optional) {
                    Text("Optional", style = MaterialTheme.typography.bodySmall, color = Aura.TextGhost)
                }
            }
            Text(body, style = MaterialTheme.typography.bodySmall, color = Aura.TextDim)
            bullets.forEach { (positive, line) ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (positive) "✓" else "✕",
                        color = if (positive) Aura.Cyan else Aura.Danger,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(line, style = MaterialTheme.typography.bodySmall, color = Aura.TextDim)
                }
            }
            if (!granted) {
                AuraGhostButton(
                    text = if (optional) "Allow microphone" else "Open Android settings",
                    onClick = onGrant,
                    color = Aura.Cyan,
                )
            }
        }
    }
}

@Composable
private fun FirstTaskStep(
    skin: OrbSkin,
    glow: GlowLevel,
    onFinish: (String?) -> Unit,
) {
    val suggestions = listOf(
        "Turn off my notification sounds",
        "Make text bigger everywhere",
        "Open my most recent photo",
    )

    StepScaffold(
        eyebrow = "Step 4 of 4",
        title = "Try one out loud",
        subtitle = "Hold the orb and say it, or tap a suggestion.",
        footer = {
            AuraGhostButton("Skip for now", { onFinish(null) })
        },
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(20.dp))
            ListeningOrb(skin, glow)
            Spacer(Modifier.height(28.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                AuraEyebrow("Try", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                suggestions.forEach { s ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Aura.SurfaceRaised)
                            .border(1.dp, Aura.LineBright, RoundedCornerShape(14.dp))
                            .clickable { onFinish(s) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Text(s, style = MaterialTheme.typography.bodyMedium, color = Aura.TextMid)
                    }
                }
            }
        }
    }
}

/** The orb with expanding rings — the design's "ready to listen" state. */
@Composable
private fun ListeningOrb(skin: OrbSkin, glow: GlowLevel) {
    val transition = rememberInfiniteTransition(label = "listen")
    val ring by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(2400), repeatMode = RepeatMode.Restart),
        label = "ring",
    )

    Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
        listOf(ring, (ring + 0.5f) % 1f).forEach { phase ->
            val scale = 0.8f + phase * 1.1f
            Box(
                Modifier
                    .size((74 * scale).dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .border(
                        1.5.dp,
                        Aura.Cyan.copy(alpha = (1f - phase) * 0.5f),
                        RoundedCornerShape(percent = 50),
                    ),
            )
        }
        AuraOrb(size = 74.dp, skin = skin, glow = glow)
    }
}
