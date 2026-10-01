package com.drishti.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.drishti.data.TaskOutcome
import com.drishti.data.TaskRecord
import com.drishti.ui.theme.Aura
import com.drishti.ui.theme.AuraField
import com.drishti.ui.theme.PrimaryButton
import com.drishti.ui.theme.SectionLabel
import com.drishti.ui.theme.StatusLine

enum class HomeStatus { Ready, Paused, NeedsPermission }

/**
 * Home is rarely the point — aura lives over other apps — so it does three things: shows
 * that aura is alive, offers one way to ask, and keeps the record of what it helped with.
 */
@Composable
fun HomeScreen(
    records: List<TaskRecord>,
    status: HomeStatus,
    onAsk: () -> Unit,
    onFix: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Aura.Bg)
            .windowInsetsPadding(WindowInsets.systemBars),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("aura", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(
                    "settings",
                    style = MaterialTheme.typography.labelLarge,
                    color = Aura.TextSecondary,
                    modifier = Modifier
                        .clip(Aura.PillShape)
                        .clickable(role = Role.Button, onClick = onOpenSettings)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
        item {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                AuraField(
                    Modifier.size(240.dp),
                    energy = if (status == HomeStatus.Ready) 0.4f else 0.1f,
                    animate = status == HomeStatus.Ready,
                )
            }
        }
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                when (status) {
                    HomeStatus.Ready -> StatusLine("ready — over every app", Aura.Positive)
                    HomeStatus.Paused -> StatusLine("paused", Aura.TextTertiary)
                    HomeStatus.NeedsPermission -> StatusLine("needs a permission", Aura.Warning)
                }
                Spacer(Modifier.height(20.dp))
                when (status) {
                    HomeStatus.Ready -> PrimaryButton("ask aura", onAsk)
                    HomeStatus.Paused -> PrimaryButton("turn aura back on", onFix)
                    HomeStatus.NeedsPermission -> PrimaryButton("fix it", onFix)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "or hold the light at the edge of your screen and speak — in any language",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
        item { Spacer(Modifier.height(36.dp)) }
        item { SectionLabel("recent") }
        if (records.isEmpty()) {
            item {
                Text(
                    "nothing yet. what you ask for will show up here for a week.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
        } else {
            items(records, key = { it.id }) { HistoryRow(it) }
        }
    }
}

@Composable
private fun HistoryRow(record: TaskRecord) {
    val dot = when (record.outcome) {
        TaskOutcome.Completed -> Aura.Positive
        TaskOutcome.Stopped -> Aura.Warning
        TaskOutcome.Cancelled -> Aura.TextTertiary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(Aura.ButtonShape)
            .background(Aura.Surface)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 8.dp).size(8.dp).clip(CircleShape).background(dot))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(record.task.lowercase(), style = MaterialTheme.typography.bodyLarge, color = Aura.Text)
            Text(record.metaLine().lowercase(), style = MaterialTheme.typography.bodySmall)
        }
    }
}
