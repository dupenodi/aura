package com.drishti.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.drishti.ui.onboarding.PermissionActions
import com.drishti.ui.onboarding.PermissionList
import com.drishti.ui.onboarding.PermissionState
import com.drishti.ui.theme.Aura
import com.drishti.ui.theme.Group
import com.drishti.ui.theme.Note
import com.drishti.ui.theme.SectionLabel
import com.drishti.ui.theme.SettingRow
import com.drishti.ui.theme.Toggle
import com.drishti.voice.AuraLanguage

/** Everything adjustable, on one page. */
class SettingsState(
    val autoLanguage: Boolean,
    val language: AuraLanguage,
    val speakAloud: Boolean,
    val naturalVoice: Boolean,
    val naturalVoiceAvailable: Boolean,
    val useScreenshots: Boolean,
    val paused: Boolean,
    val permissions: PermissionState,
    val isAssistant: Boolean,
)

class SettingsActions(
    val onBack: () -> Unit,
    val onOpenLanguage: () -> Unit,
    val onSpeakAloud: (Boolean) -> Unit,
    val onNaturalVoice: (Boolean) -> Unit,
    val onUseScreenshots: (Boolean) -> Unit,
    val onPaused: (Boolean) -> Unit,
    val onOpenPermissions: () -> Unit,
    val onDeleteHistory: () -> Unit,
    val onOpenAssistant: () -> Unit,
)

@Composable
fun SettingsScreen(state: SettingsState, actions: SettingsActions) {
    var confirmDelete by remember { mutableStateOf(false) }
    Page("settings", actions.onBack) {
        SectionLabel("speaking")
        Group {
            SettingRow(
                "language",
                value = if (state.autoLanguage) "automatic" else state.language.nativeLabel.lowercase(),
                onClick = actions.onOpenLanguage,
            )
            SettingRow("read steps aloud", trailing = { Toggle(state.speakAloud, actions.onSpeakAloud) })
            SettingRow(
                "natural indian voice",
                subtitle = if (state.naturalVoiceAvailable) "sarvam — clearer in every indian language" else "needs a sarvam key",
                divider = false,
                trailing = { Toggle(state.naturalVoice && state.naturalVoiceAvailable, { if (state.naturalVoiceAvailable) actions.onNaturalVoice(it) }) },
            )
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("calling aura")
        Group {
            SettingRow(
                "hold power to call aura",
                subtitle = if (state.isAssistant) "or swipe up from a bottom corner" else "choose aura as the digital assistant app",
                value = if (state.isAssistant) "on" else "set up",
                valueColor = if (state.isAssistant) Aura.Positive else Aura.TextSecondary,
                divider = false,
                onClick = actions.onOpenAssistant,
            )
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("privacy")
        Group {
            SettingRow(
                "use a screen picture when needed",
                subtitle = "only when the words on screen aren't enough",
                trailing = { Toggle(state.useScreenshots, actions.onUseScreenshots) },
            )
            SettingRow("pause aura", subtitle = "hides the glow everywhere", trailing = { Toggle(state.paused, actions.onPaused) })
            SettingRow(
                if (confirmDelete) "tap again to delete" else "delete history",
                divider = false,
                onClick = {
                    if (confirmDelete) {
                        actions.onDeleteHistory()
                        confirmDelete = false
                    } else {
                        confirmDelete = true
                    }
                },
                trailing = {},
            )
        }
        Spacer(Modifier.height(10.dp))
        Note(
            "to work out your next step, aura sends the words on your screen — and a picture only when needed — to its ai model. " +
                "banking, payment and health apps are never read. nothing is kept off your phone.",
        )

        Spacer(Modifier.height(24.dp))
        SectionLabel("permissions")
        Group {
            SettingRow(
                "permissions",
                value = if (state.permissions.all) "all on" else "needs attention",
                valueColor = if (state.permissions.all) Aura.Positive else Aura.Warning,
                divider = false,
                onClick = actions.onOpenPermissions,
            )
        }
    }
}

/** Automatic, or one language always. */
@Composable
fun LanguageScreen(
    auto: Boolean,
    language: AuraLanguage,
    onAuto: () -> Unit,
    onLanguage: (AuraLanguage) -> Unit,
    onBack: () -> Unit,
) {
    Page("language", onBack) {
        Group {
            Choice("automatic", "answers in the language you speak or type", selected = auto, divider = false, onClick = onAuto)
        }
        Spacer(Modifier.height(24.dp))
        SectionLabel("always use")
        Group {
            AuraLanguage.entries.forEachIndexed { i, lang ->
                Choice(
                    title = lang.nativeLabel.lowercase(),
                    subtitle = lang.label.lowercase().takeIf { it != lang.nativeLabel.lowercase() },
                    selected = !auto && lang == language,
                    divider = i != AuraLanguage.entries.lastIndex,
                    onClick = { onLanguage(lang) },
                )
            }
        }
    }
}

@Composable
fun PermissionsScreen(permissions: PermissionState, actions: PermissionActions, onBack: () -> Unit) {
    Page("permissions", onBack) {
        PermissionList(permissions, actions)
    }
}

@Composable
private fun Choice(title: String, subtitle: String?, selected: Boolean, divider: Boolean, onClick: () -> Unit) {
    SettingRow(
        title,
        subtitle = subtitle,
        divider = divider,
        onClick = onClick,
        trailing = {
            if (selected) Text("✓", style = MaterialTheme.typography.titleMedium, color = Aura.Violet)
        },
    )
}

/** A titled, scrolling page with a back control. */
@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Aura.Bg)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "‹",
                style = MaterialTheme.typography.headlineMedium,
                color = Aura.Text,
                modifier = Modifier
                    .clip(Aura.PillShape)
                    .clickable(role = Role.Button, onClick = onBack)
                    .padding(horizontal = 14.dp, vertical = 4.dp),
            )
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            content()
            Spacer(Modifier.height(32.dp))
        }
    }
}
