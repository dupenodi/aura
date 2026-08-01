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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.drishti.ui.theme.Aura
import com.drishti.ui.theme.AuraCard
import com.drishti.ui.theme.AuraEyebrow
import com.drishti.ui.theme.AuraNote
import com.drishti.ui.theme.AuraRow
import com.drishti.voice.AuraLanguage
import com.drishti.voice.SpeechProvider

/**
 * Language and voice engine.
 *
 * The on-device engine is always offered first because it needs no key and no network;
 * the cloud engines are opt-in and clearly marked when they aren't set up, so nobody
 * picks an option that silently does nothing.
 */
@Composable
fun LanguageScreen(
    language: AuraLanguage,
    provider: SpeechProvider,
    providerConfigured: (SpeechProvider) -> Boolean,
    ttsAvailable: (AuraLanguage) -> Boolean,
    onLanguage: (AuraLanguage) -> Unit,
    onProvider: (SpeechProvider) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Aura.Bg)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "‹",
                color = Aura.TextGhost,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.clickable(onClick = onBack),
            )
            Text("Language & voice", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                AuraEyebrow(
                    "Speak and listen in",
                    color = Aura.TextMuted,
                    modifier = Modifier.padding(start = 6.dp),
                )
                AuraCard {
                    AuraLanguage.entries.forEachIndexed { index, option ->
                        val selected = option == language
                        val hasVoice = ttsAvailable(option)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onLanguage(option) }
                                .padding(horizontal = 15.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(18.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .border(
                                        2.dp,
                                        if (selected) Aura.Cyan else Aura.LineBright,
                                        RoundedCornerShape(9.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) {
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Aura.Cyan),
                                    )
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    option.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (selected) Aura.TextHi else Aura.TextMid,
                                )
                                if (option.nativeLabel != option.label) {
                                    Text(
                                        option.nativeLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Aura.TextGhost,
                                    )
                                }
                            }
                            // Being honest about a missing voice beats silently
                            // reading a regional language in an English accent.
                            if (!hasVoice) {
                                Text(
                                    "No voice installed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Aura.TextMuted,
                                )
                            }
                        }
                        if (index != AuraLanguage.entries.lastIndex) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 15.dp)
                                    .height(1.dp)
                                    .background(Aura.Line),
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                AuraEyebrow(
                    "Speech engine",
                    color = Aura.TextMuted,
                    modifier = Modifier.padding(start = 6.dp),
                )
                AuraCard {
                    SpeechProvider.entries.forEachIndexed { index, option ->
                        val configured = providerConfigured(option)
                        AuraRow(
                            label = option.label,
                            value = when {
                                option == provider -> "In use"
                                !configured -> "Needs a key"
                                else -> null
                            },
                            valueColor = if (option == provider) Aura.Cyan else Aura.TextMuted,
                            showChevron = false,
                            divider = index != SpeechProvider.entries.lastIndex,
                            onClick = { if (configured) onProvider(option) },
                        )
                    }
                }
            }

            AuraNote(
                "On-device works offline with no account. Sarvam and Deepgram are optional " +
                    "and only used once you add a key.",
                accent = Aura.Cyan,
                marker = "◆",
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}
