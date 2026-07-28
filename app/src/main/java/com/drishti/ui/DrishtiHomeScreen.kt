package com.drishti.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.drishti.R
import com.drishti.accessibility.DrishtiAccessibilityService
import com.drishti.agent.AgentLoop
import com.drishti.agent.AgentRunStatus
import com.drishti.agent.AgentState
import com.drishti.agent.FlowHistoryStore
import com.drishti.agent.FlowRecord
import com.drishti.agent.PreferenceStore
import com.drishti.agent.RecipeStore
import com.drishti.agent.SaveFlowIntent
import com.drishti.agent.StoredRecipe
import com.drishti.debug.AgentDebugActivity
import com.drishti.overlay.AgentMode
import com.drishti.overlay.OverlayService
import java.text.DateFormat
import java.util.Date

private val HeroBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0xFF0F766E),
        Color(0xFF134E4A),
        Color(0xFF0B1220),
    ),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DrishtiHomeScreen(
    agentLoop: AgentLoop,
    agentState: AgentState,
    preferenceStore: PreferenceStore,
    recipeStore: RecipeStore,
    flowHistoryStore: FlowHistoryStore,
    requestMicOnLaunch: Boolean = false,
    highlightLastFlow: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val ui by agentState.ui.collectAsState()
    val prefs by preferenceStore.entries.collectAsState()
    val recipes by recipeStore.recipes.collectAsState()
    val flows by flowHistoryStore.flows.collectAsState()
    val running = ui.status == AgentRunStatus.Running

    var a11yOn by remember { mutableStateOf(DrishtiAccessibilityService.instance != null) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var pendingOverlayDemo by remember { mutableStateOf(false) }
    val pendingDemo by rememberUpdatedState(pendingOverlayDemo)
    var micPrompted by remember { mutableStateOf(false) }
    var prefKey by remember { mutableStateOf("food_app") }
    var prefValue by remember { mutableStateOf("zepto") }
    var expandedFlowId by remember {
        mutableStateOf(if (highlightLastFlow) flows.firstOrNull()?.id else null)
    }
    var showSetup by remember { mutableStateOf(false) }

    fun refreshPermissions() {
        a11yOn = DrishtiAccessibilityService.instance != null
        overlayGranted = Settings.canDrawOverlays(context)
        micGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun openOverlayPermissionSettings(forDemo: Boolean) {
        pendingOverlayDemo = forDemo
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        Toast.makeText(
            context,
            context.getString(R.string.overlay_enable_for_drishti_toast, context.packageName),
            Toast.LENGTH_LONG,
        ).show()
        context.startActivity(intent)
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        refreshPermissions()
        if (!overlayGranted && pendingOverlayDemo) {
            Toast.makeText(
                context,
                context.getString(R.string.overlay_still_denied_toast, context.packageName),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        refreshPermissions()
        proceedOverlayStart(
            context = context,
            overlayGranted = Settings.canDrawOverlays(context),
            onNeedOverlay = { intent ->
                pendingOverlayDemo = true
                Toast.makeText(
                    context,
                    context.getString(R.string.overlay_enable_for_drishti_toast, context.packageName),
                    Toast.LENGTH_LONG,
                ).show()
                overlayPermissionLauncher.launch(intent)
            },
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
        refreshPermissions()
        Toast.makeText(
            context,
            if (granted) {
                context.getString(R.string.mic_granted_toast)
            } else {
                context.getString(R.string.mic_denied_toast)
            },
            Toast.LENGTH_SHORT,
        ).show()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                a11yOn = DrishtiAccessibilityService.instance != null
                val granted = Settings.canDrawOverlays(context)
                overlayGranted = granted
                micGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                OverlayService.instance?.mode?.let { agentState.setMode(it) }
                if (granted && pendingDemo) {
                    pendingOverlayDemo = false
                    startOverlay(context)
                }
                if (requestMicOnLaunch && !micGranted && !micPrompted) {
                    micPrompted = true
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                if (highlightLastFlow && expandedFlowId == null) {
                    expandedFlowId = flowHistoryStore.latest()?.id
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(HeroBrush)
                .padding(horizontal = 20.dp, vertical = 28.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Drishti",
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "On-screen AI that lives over every app — voice in your language, " +
                        "sees the screen, moves a cursor. Coach teaches; Pilot taps. " +
                        "Learns Uber/Swiggy prefs and remembers successful flows so repeats get faster.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.92f),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusPill(
                        label = if (a11yOn) "A11y on" else "A11y off",
                        ok = a11yOn,
                    )
                    StatusPill(
                        label = if (overlayGranted) "Overlay on" else "Overlay off",
                        ok = overlayGranted,
                    )
                    StatusPill(
                        label = if (micGranted) "Mic on" else "Mic off",
                        ok = micGranted,
                    )
                    StatusPill(
                        label = ui.mode.name,
                        ok = true,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Controls", style = MaterialTheme.typography.titleMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Mode", style = MaterialTheme.typography.bodyMedium)
                FilterChip(
                    selected = ui.mode == AgentMode.Coach,
                    onClick = {
                        agentState.setMode(AgentMode.Coach)
                        OverlayService.instance?.setMode(AgentMode.Coach)
                    },
                    label = { Text("Coach") },
                    enabled = !running,
                )
                FilterChip(
                    selected = ui.mode == AgentMode.Pilot,
                    onClick = {
                        agentState.setMode(AgentMode.Pilot)
                        OverlayService.instance?.setMode(AgentMode.Pilot)
                    },
                    label = { Text("Pilot") },
                    enabled = !running,
                )
            }
            Text(
                text = when (ui.mode) {
                    AgentMode.Coach -> "Coach: highlight + teach without committing taps"
                    AgentMode.Pilot -> "Pilot: Drishti actually taps and types for you"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ui.status != AgentRunStatus.Idle) {
                Text(
                    text = "Status: ${ui.statusLine}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        refreshPermissions()
                        if (DrishtiAccessibilityService.instance == null) {
                            DrishtiAccessibilityService.showEnableToast()
                        }
                        ensureNotificationThenStart(
                            context = context,
                            overlayGranted = overlayGranted,
                            requestNotifications = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermissionLauncher.launch(
                                        Manifest.permission.POST_NOTIFICATIONS,
                                    )
                                }
                            },
                            requestOverlay = { intent ->
                                pendingOverlayDemo = true
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.overlay_enable_for_drishti_toast,
                                        context.packageName,
                                    ),
                                    Toast.LENGTH_LONG,
                                ).show()
                                overlayPermissionLauncher.launch(intent)
                            },
                        )
                    },
                ) {
                    Text("Wake overlay")
                }
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(context, AgentDebugActivity::class.java))
                    },
                ) {
                    Text("Agent debug")
                }
                OutlinedButton(onClick = { showSetup = !showSetup }) {
                    Text(if (showSetup) "Hide setup" else "Permissions")
                }
            }

            AnimatedVisibility(visible = showSetup, enter = fadeIn(), exit = fadeOut()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Setup", style = MaterialTheme.typography.titleSmall)
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.open_accessibility_settings))
                        }
                        OutlinedButton(
                            onClick = {
                                refreshPermissions()
                                if (overlayGranted) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.overlay_already_granted),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    openOverlayPermissionSettings(forDemo = false)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.open_overlay_settings))
                        }
                        OutlinedButton(
                            onClick = {
                                refreshPermissions()
                                if (micGranted) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.mic_already_granted),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.grant_microphone))
                        }
                        TextButton(onClick = { refreshPermissions() }) {
                            Text(stringResource(R.string.refresh_status))
                        }
                    }
                }
            }

            InsightsSectionTitle("Preferences")
            Text(
                text = "Remembered apps & language (Uber, Swiggy, Zepto, kn/en…)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (prefs.isEmpty()) {
                EmptyHint("No preferences yet — save food_app=zepto or cab_app=uber")
            } else {
                prefs.entries.sortedBy { it.key }.forEach { (k, v) ->
                    PreferenceRow(
                        key = k,
                        value = v,
                        enabled = !running,
                        onDelete = {
                            preferenceStore.remove(k)
                            Toast.makeText(context, "Removed $k", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = prefKey,
                    onValueChange = { prefKey = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("key") },
                    singleLine = true,
                    enabled = !running,
                )
                OutlinedTextField(
                    value = prefValue,
                    onValueChange = { prefValue = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("value") },
                    singleLine = true,
                    enabled = !running,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (preferenceStore.set(prefKey, prefValue)) {
                            Toast.makeText(
                                context,
                                "Saved ${prefKey.trim()}=${prefValue.trim()}",
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            Toast.makeText(
                                context,
                                "Use snake_case key + non-empty value",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    enabled = !running && prefKey.isNotBlank() && prefValue.isNotBlank(),
                ) {
                    Text("Save")
                }
                TextButton(
                    onClick = {
                        preferenceStore.clear()
                        Toast.makeText(context, "Preferences cleared", Toast.LENGTH_SHORT).show()
                    },
                    enabled = !running && prefs.isNotEmpty(),
                ) {
                    Text("Clear prefs")
                }
            }

            InsightsSectionTitle("Learned flows")
            Text(
                text = "Successful paths cached after use — Replay skips most LLM planning",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (recipes.isEmpty()) {
                EmptyHint(
                    "Complete a multi-step task, or cancel mid-flow and say “save the flow” " +
                        "(or tap Save to learned on Recent activity)",
                )
            } else {
                recipes.forEach { recipe ->
                    LearnedFlowCard(
                        recipe = recipe,
                        running = running,
                        onReplay = replay@{
                            if (DrishtiAccessibilityService.instance == null) {
                                DrishtiAccessibilityService.showEnableToast()
                                return@replay
                            }
                            if (!Settings.canDrawOverlays(context)) {
                                Toast.makeText(
                                    context,
                                    "Grant overlay permission first",
                                    Toast.LENGTH_LONG,
                                ).show()
                                return@replay
                            }
                            if (OverlayService.instance == null) {
                                runCatching { OverlayService.start(context) }
                            }
                            if (agentLoop.isBusy) {
                                Toast.makeText(
                                    context,
                                    "Agent busy — cancel first",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@replay
                            }
                            Toast.makeText(
                                context,
                                "Replaying ${recipe.intentLabel}",
                                Toast.LENGTH_SHORT,
                            ).show()
                            if (!agentLoop.launchReplay(recipe)) {
                                Toast.makeText(
                                    context,
                                    "Agent busy — cancel first",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onDelete = {
                            recipeStore.delete(recipe.key)
                            Toast.makeText(context, "Deleted learned flow", Toast.LENGTH_SHORT)
                                .show()
                        },
                    )
                }
            }
            TextButton(
                onClick = {
                    recipeStore.clear()
                    Toast.makeText(context, "Learned flows cleared", Toast.LENGTH_SHORT).show()
                },
                enabled = !running && recipes.isNotEmpty(),
            ) {
                Text("Clear learned flows")
            }

            InsightsSectionTitle("Recent activity")
            Text(
                text = "Last ${FlowHistoryStore.MAX_FLOWS} agent runs — expand for tool steps",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (flows.isEmpty()) {
                EmptyHint("No runs yet — wake the overlay and speak a command")
            } else {
                flows.forEach { flow ->
                    val canSaveToLearned = !flow.completed &&
                        FlowHistoryStore.hasMeaningfulActions(flow) &&
                        !SaveFlowIntent.matches(flow.userUtterance)
                    FlowHistoryCard(
                        flow = flow,
                        expanded = expandedFlowId == flow.id,
                        onToggle = {
                            expandedFlowId = if (expandedFlowId == flow.id) null else flow.id
                        },
                        onSaveToLearned = if (canSaveToLearned) {
                            {
                                when (agentLoop.promoteFlowToLearned(flow.id)) {
                                    is AgentLoop.PromoteResult.Saved -> {
                                        Toast.makeText(
                                            context,
                                            "Saved to learned flows",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                    else -> {
                                        Toast.makeText(
                                            context,
                                            "Could not save this flow",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            }
            TextButton(
                onClick = {
                    flowHistoryStore.clear()
                    expandedFlowId = null
                    Toast.makeText(context, "History cleared", Toast.LENGTH_SHORT).show()
                },
                enabled = flows.isNotEmpty(),
            ) {
                Text("Clear history")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InsightsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(12.dp),
    )
}

@Composable
private fun StatusPill(label: String, ok: Boolean) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = if (ok) {
                Color.White.copy(alpha = 0.18f)
            } else {
                Color(0x55B45309)
            },
            disabledLabelColor = Color.White,
        ),
    )
}

@Composable
private fun PreferenceRow(
    key: String,
    value: String,
    enabled: Boolean,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(key, fontWeight = FontWeight.Medium)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onDelete, enabled = enabled) {
            Text("Delete")
        }
    }
}

@Composable
private fun LearnedFlowCard(
    recipe: StoredRecipe,
    running: Boolean,
    onReplay: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = recipe.intentLabel.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${recipe.steps.size} steps · last used ${formatRelative(recipe.lastUsedAtMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LearnedBadge()
            }
            if (recipe.sourceUtterance.isNotBlank()) {
                Text(
                    text = "“${recipe.sourceUtterance.take(120)}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onReplay, enabled = !running) {
                    Text("Replay")
                }
                TextButton(onClick = onDelete, enabled = !running) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun LearnedBadge() {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = "Learned after use",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun FlowHistoryCard(
    flow: FlowRecord,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSaveToLearned: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (flow.completed) {
                                Color(0xFF059669)
                            } else {
                                Color(0xFFD97706)
                            },
                        ),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = flow.userUtterance.ifBlank { "(no utterance)" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = buildString {
                    append(formatTime(flow.timestampMs))
                    append(" · ")
                    append(flow.mode)
                    append(" · ")
                    append(flow.steps.size)
                    append(" steps · ")
                    append("${flow.durationMs / 1000}s")
                    if (flow.packageName != null) {
                        append(" · ")
                        append(flow.packageName.substringAfterLast('.'))
                    }
                    append(if (flow.completed) " · complete" else " · incomplete")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = flow.summary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) 8 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (onSaveToLearned != null) {
                OutlinedButton(
                    onClick = onSaveToLearned,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save to learned")
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Steps",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (flow.steps.isEmpty()) {
                        Text(
                            "(no tools recorded)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        flow.steps.forEachIndexed { i, step ->
                            Text(
                                text = "${i + 1}. ${if (step.success) "✓" else "✗"} " +
                                    "${step.name}(${step.args.take(80)})",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (step.success) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

private fun formatRelative(ms: Long): String {
    val delta = System.currentTimeMillis() - ms
    val mins = delta / 60_000L
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 60 * 24 -> "${mins / 60}h ago"
        else -> "${mins / (60 * 24)}d ago"
    }
}

private fun ensureNotificationThenStart(
    context: android.content.Context,
    overlayGranted: Boolean,
    requestNotifications: () -> Unit,
    requestOverlay: (Intent) -> Unit,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotifications()
            return
        }
    }
    proceedOverlayStart(context, overlayGranted, requestOverlay)
}

private fun proceedOverlayStart(
    context: android.content.Context,
    overlayGranted: Boolean,
    onNeedOverlay: (Intent) -> Unit,
) {
    if (!overlayGranted) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        onNeedOverlay(intent)
        return
    }
    startOverlay(context)
}

private fun startOverlay(context: android.content.Context) {
    runCatching {
        if (DrishtiAccessibilityService.instance == null) {
            DrishtiAccessibilityService.showEnableToast()
        }
        OverlayService.start(context)
        Toast.makeText(context, "Overlay awake — tap the bubble to talk", Toast.LENGTH_SHORT)
            .show()
    }.onFailure {
        Toast.makeText(context, "Failed to start overlay: ${it.message}", Toast.LENGTH_LONG).show()
    }
}
