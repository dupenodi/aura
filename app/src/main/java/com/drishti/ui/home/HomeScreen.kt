package com.drishti.ui.home

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drishti.data.TaskOutcome
import com.drishti.data.TaskRecord
import com.drishti.ui.theme.Aura
import com.drishti.ui.theme.AuraEyebrow
import com.drishti.ui.theme.AuraOrb
import com.drishti.ui.theme.GlowLevel
import com.drishti.ui.theme.OrbSkin

/**
 * Home is deliberately rarely opened: the product lives in the overlay. What it does
 * offer is the record of what Aura actually did, which is where trust is won.
 */
@Composable
fun HomeScreen(
    records: List<TaskRecord>,
    orbSkin: OrbSkin,
    glow: GlowLevel,
    active: Boolean,
    onOpenSettings: () -> Unit,
    onAsk: () -> Unit,
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
                .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AuraOrb(size = 38.dp, skin = orbSkin, glow = glow, breathing = active)
            Column(Modifier.weight(1f)) {
                Text("Aura", style = MaterialTheme.typography.titleLarge)
                AuraEyebrow(
                    text = if (active) "Ready to help" else "Paused",
                    color = if (active) Aura.Cyan else Aura.TextGhost,
                )
            }
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Aura.Chip)
                    .border(1.dp, Aura.LineBright, RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                Text("⚙", color = Aura.TextDim, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // The ask affordance mirrors the overlay: this is a shortcut, not the main way in.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Aura.SurfaceRaised)
                .border(1.dp, Aura.LineBright, RoundedCornerShape(15.dp))
                .clickable(enabled = active, onClick = onAsk)
                .padding(horizontal = 16.dp, vertical = 15.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("◉", color = Aura.TextGhost, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (active) "Ask, or hold the orb anywhere" else "Paused — unpause in Privacy",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Aura.TextGhost,
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        AuraEyebrow("This week", modifier = Modifier.padding(horizontal = 22.dp))
        Spacer(Modifier.height(8.dp))

        if (records.isEmpty()) {
            EmptyHistory(Modifier.weight(1f))
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(records, key = { it.id }) { record -> HistoryRow(record) }
            }
        }
    }
}

@Composable
private fun EmptyHistory(modifier: Modifier = Modifier) {
    // Sits just under the section heading rather than floating in the middle of an
    // otherwise empty screen.
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Nothing yet",
            style = MaterialTheme.typography.titleMedium,
            color = Aura.TextDim,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Hold the orb anywhere and say what you need. Everything I do will show up here.",
            style = MaterialTheme.typography.bodySmall,
            color = Aura.TextGhost,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HistoryRow(record: TaskRecord) {
    val (dotBg, dotFg, icon) = when (record.outcome) {
        TaskOutcome.Completed -> Triple(Aura.Cyan.copy(alpha = 0.16f), Aura.Cyan, "✓")
        TaskOutcome.Stopped -> Triple(Aura.Warning.copy(alpha = 0.18f), Aura.Warning, "!")
        TaskOutcome.Cancelled -> Triple(Color(0x22FFFFFF), Aura.TextGhost, "–")
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Aura.Surface)
            .border(1.dp, Aura.Line, RoundedCornerShape(14.dp))
            .padding(horizontal = 15.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(dotBg),
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, color = dotFg, style = MaterialTheme.typography.labelSmall)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                record.task,
                style = MaterialTheme.typography.bodyMedium,
                color = Aura.TextMid,
            )
            Text(
                record.metaLine(),
                style = MaterialTheme.typography.labelSmall,
                color = Aura.TextMuted,
            )
        }
    }
}
