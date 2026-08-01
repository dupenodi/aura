package com.drishti.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drishti.data.AuraMode
import com.drishti.data.AutoSpeed
import com.drishti.ui.onboarding.ModeOption
import com.drishti.ui.theme.Aura
import com.drishti.ui.theme.AuraCard
import com.drishti.ui.theme.AuraEyebrow
import com.drishti.ui.theme.AuraNote
import com.drishti.ui.theme.AuraOrb
import com.drishti.ui.theme.AuraRow
import com.drishti.ui.theme.AuraToggle
import com.drishti.ui.theme.GlowLevel
import com.drishti.ui.theme.OrbSkin

/** Shared back-header used by every sub-screen. */
@Composable
private fun ScreenHeader(title: String, onBack: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) {
            Text(
                "‹",
                color = Aura.TextGhost,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.clickable(onClick = onBack),
            )
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        AuraEyebrow(title, color = Aura.TextMuted, modifier = Modifier.padding(start = 6.dp))
        AuraCard { content() }
    }
}

@Composable
fun SettingsScreen(
    mode: AuraMode,
    overrideCount: Int,
    autoSpeed: AutoSpeed,
    orbSkin: OrbSkin,
    glow: GlowLevel,
    speakAloud: Boolean,
    hideInFullscreen: Boolean,
    permissionsGranted: Boolean,
    onBack: () -> Unit,
    onOpenMode: () -> Unit,
    onOpenPresence: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onCycleAutoSpeed: () -> Unit,
    onSpeakAloud: (Boolean) -> Unit,
    onHideInFullscreen: (Boolean) -> Unit,
    onOpenPermissions: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Aura.Bg)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        ScreenHeader("Settings", onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsGroup("Behaviour") {
                AuraRow("Default mode", value = mode.label, valueColor = Aura.Cyan, onClick = onOpenMode)
                AuraRow(
                    "Per-app overrides",
                    value = if (overrideCount == 0) "None" else "$overrideCount apps",
                    onClick = onOpenMode,
                )
                AuraRow(
                    "Auto speed",
                    value = autoSpeed.label,
                    divider = false,
                    onClick = onCycleAutoSpeed,
                )
            }

            SettingsGroup("Presence") {
                AuraRow(
                    "Avatar & glow",
                    showChevron = true,
                    onClick = onOpenPresence,
                    trailing = {
                        AuraOrb(size = 22.dp, skin = orbSkin, glow = glow, breathing = false)
                    },
                )
                AuraRow(
                    "Hide in fullscreen apps",
                    showChevron = false,
                    divider = false,
                    trailing = { AuraToggle(hideInFullscreen, onHideInFullscreen) },
                )
            }

            SettingsGroup("Voice & access") {
                AuraRow(
                    "Read steps aloud",
                    showChevron = false,
                    trailing = { AuraToggle(speakAloud, onSpeakAloud) },
                )
                AuraRow(
                    "Permissions",
                    value = if (permissionsGranted) "All granted" else "Needs attention",
                    valueColor = if (permissionsGranted) Aura.Cyan else Aura.Warning,
                    onClick = onOpenPermissions,
                )
                AuraRow("What I can see", divider = false, onClick = onOpenPrivacy)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
fun ModeScreen(
    mode: AuraMode,
    onMode: (AuraMode) -> Unit,
    overrides: List<Pair<String, AuraMode?>>,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Aura.Bg)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        ScreenHeader("Mode", onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ModeOption(
                title = "Guide me",
                body = "Points, waits, never taps.",
                selected = mode == AuraMode.Guide,
                accent = Aura.Cyan,
                onClick = { onMode(AuraMode.Guide) },
            )
            ModeOption(
                title = "Do it for me",
                body = "Taps for you, stops at anything irreversible.",
                selected = mode == AuraMode.Auto,
                accent = Aura.Purple,
                onClick = { onMode(AuraMode.Auto) },
            )

            if (overrides.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                AuraEyebrow("Override per app", color = Aura.TextMuted)
                overrides.forEach { (name, appMode) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Aura.Surface)
                            .border(1.dp, Aura.Line, RoundedCornerShape(14.dp))
                            .padding(horizontal = 15.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    Brush.linearGradient(listOf(Aura.CyanBright, Aura.Purple)),
                                ),
                        )
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Aura.TextMid,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            appMode?.label ?: "Guide only 🔒",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (appMode == null) Aura.TextGhost else Aura.PurpleLight,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            AuraNote(
                "Banking, health and password apps are locked to Guide. That can't be changed.",
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
fun PresenceScreen(
    orbSkin: OrbSkin,
    glow: GlowLevel,
    onOrbSkin: (OrbSkin) -> Unit,
    onGlow: (GlowLevel) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Aura.Bg)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        ScreenHeader("Avatar & glow", onBack)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(20.dp))
            AuraOrb(size = 88.dp, skin = orbSkin, glow = glow)
            Spacer(Modifier.height(34.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OrbSkin.entries.forEach { option ->
                    val selected = option == orbSkin
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
                                2.dp,
                                if (selected) Color.White else Color.Transparent,
                                RoundedCornerShape(26.dp),
                            )
                            .clickable { onOrbSkin(option) },
                    )
                }
            }
            Spacer(Modifier.height(36.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "Glow intensity",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(glow.label, style = MaterialTheme.typography.bodyMedium, color = Aura.Cyan)
            }
            Spacer(Modifier.height(12.dp))
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
            Spacer(Modifier.height(12.dp))
            Text(
                "Dimmer uses less battery and is easier on light-sensitive eyes.",
                style = MaterialTheme.typography.bodySmall,
                color = Aura.TextGhost,
            )
        }
    }
}

@Composable
fun PrivacyScreen(
    paused: Boolean,
    onPaused: (Boolean) -> Unit,
    onDeleteHistory: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Aura.Bg)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        ScreenHeader("What I can see", onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Screen understanding runs on this phone. Nothing is uploaded, stored, " +
                    "or used for training.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
            )

            InfoBlock(
                eyebrow = "Never read",
                eyebrowColor = Aura.Cyan,
                body = "Password & PIN fields · anything in a banking or health app · " +
                    "incognito browsing · one-time codes",
            )
            InfoBlock(
                eyebrow = "Kept for 7 days, on device",
                eyebrowColor = Aura.Purple,
                body = "Task history and the steps taken, so you can see what I did",
            )

            AuraCard {
                AuraRow(
                    "Pause Aura everywhere",
                    showChevron = false,
                    divider = false,
                    trailing = { AuraToggle(paused, onPaused) },
                )
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(15.dp))
                    .background(Aura.Danger.copy(alpha = 0.07f))
                    .border(1.dp, Aura.Danger.copy(alpha = 0.22f), RoundedCornerShape(15.dp))
                    .clickable(onClick = onDeleteHistory)
                    .padding(vertical = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Delete all history",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Aura.DangerLight,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun InfoBlock(eyebrow: String, eyebrowColor: Color, body: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Aura.CardShape)
            .background(Aura.Surface)
            .border(1.dp, Aura.Line, Aura.CardShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        AuraEyebrow(eyebrow, color = eyebrowColor)
        Text(body, style = MaterialTheme.typography.bodySmall, color = Aura.TextDim)
    }
}
