package com.drishti.ui.routines

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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drishti.data.Routine
import com.drishti.ui.theme.Aura
import com.drishti.ui.theme.AuraEyebrow
import com.drishti.ui.theme.AuraPrimaryButton

/**
 * Saved routines.
 *
 * A routine is just a task worth repeating. What makes it worth saving is that Aura
 * remembers the route that worked last time, so the second run is quicker and steadier
 * than the first — that memory lives entirely on the phone.
 */
@Composable
fun RoutinesScreen(
    routines: List<Routine>,
    onRun: (Routine) -> Unit,
    onDelete: (Routine) -> Unit,
    onAdd: (name: String, task: String) -> Unit,
    onBack: () -> Unit,
) {
    var newTask by remember { mutableStateOf("") }

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
            Text("Routines", style = MaterialTheme.typography.titleLarge)
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(15.dp))
                    .background(Aura.SurfaceRaised)
                    .border(1.dp, Aura.LineBright, RoundedCornerShape(15.dp))
                    .padding(horizontal = 16.dp, vertical = 15.dp),
            ) {
                if (newTask.isEmpty()) {
                    Text(
                        "Something you do often…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Aura.TextGhost,
                    )
                }
                BasicTextField(
                    value = newTask,
                    onValueChange = { newTask = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Aura.TextHi),
                    cursorBrush = SolidColor(Aura.Cyan),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(10.dp))
            AuraPrimaryButton(
                text = "Save as routine",
                enabled = newTask.isNotBlank(),
                onClick = {
                    // The task doubles as the name; people name routines by what they do.
                    onAdd(newTask.trim().take(40), newTask.trim())
                    newTask = ""
                },
            )
        }

        Spacer(Modifier.height(20.dp))
        AuraEyebrow("Saved", modifier = Modifier.padding(horizontal = 22.dp))
        Spacer(Modifier.height(8.dp))

        if (routines.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp, vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Nothing saved yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = Aura.TextDim,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Save a task you repeat — the school run message, the weekly order — " +
                        "and I'll remember how it went.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Aura.TextGhost,
                    textAlign = TextAlign.Center,
                )
            }
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
                items(routines, key = { it.id }) { routine ->
                    RoutineRow(routine, onRun = { onRun(routine) }, onDelete = { onDelete(routine) })
                }
            }
        }
    }
}

@Composable
private fun RoutineRow(routine: Routine, onRun: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Aura.Surface)
            .border(1.dp, Aura.Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onRun)
            .padding(horizontal = 15.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("✦", color = Aura.Purple, style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                routine.name,
                style = MaterialTheme.typography.bodyMedium,
                color = Aura.TextMid,
            )
            Text(
                routine.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Aura.TextMuted,
            )
        }
        Text(
            "Delete",
            style = MaterialTheme.typography.bodySmall,
            color = Aura.TextMuted,
            modifier = Modifier.clickable(onClick = onDelete),
        )
    }
}
