package com.clicky.debug

import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clicky.BuildConfig
import com.clicky.accessibility.ClickyAccessibilityService
import com.clicky.agent.AgentLoop
import com.clicky.agent.AgentRunStatus
import com.clicky.agent.AgentState
import com.clicky.agent.PreferenceStore
import com.clicky.agent.RecipeStore
import com.clicky.ai.LlmRouter
import com.clicky.ai.OpenAiClient
import com.clicky.overlay.AgentMode
import com.clicky.overlay.OverlayService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Text-input harness for PART 3 agent loop (before voice exists).
 */
@AndroidEntryPoint
class AgentDebugActivity : ComponentActivity() {

    @Inject lateinit var agentLoop: AgentLoop
    @Inject lateinit var agentState: AgentState
    @Inject lateinit var llmRouter: LlmRouter
    @Inject lateinit var preferenceStore: PreferenceStore
    @Inject lateinit var recipeStore: RecipeStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OverlayService.instance?.mode?.let { agentState.setMode(it) }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AgentDebugScreen(
                        agentLoop = agentLoop,
                        agentState = agentState,
                        llmRouter = llmRouter,
                        preferenceStore = preferenceStore,
                        recipeStore = recipeStore,
                        onEnsureReady = { ensureReady() },
                    )
                }
            }
        }
    }

    private fun ensureReady(): Boolean {
        if (ClickyAccessibilityService.instance == null) {
            ClickyAccessibilityService.showEnableToast()
            Toast.makeText(this, "Enable Clicky in Accessibility settings", Toast.LENGTH_LONG).show()
            return false
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant overlay permission first", Toast.LENGTH_LONG).show()
            return false
        }
        if (OverlayService.instance == null) {
            runCatching { OverlayService.start(this) }
            Toast.makeText(this, "Starting overlay…", Toast.LENGTH_SHORT).show()
        }
        return true
    }
}

@Composable
private fun AgentDebugScreen(
    agentLoop: AgentLoop,
    agentState: AgentState,
    llmRouter: LlmRouter,
    preferenceStore: PreferenceStore,
    recipeStore: RecipeStore,
    onEnsureReady: () -> Boolean,
) {
    val ui by agentState.ui.collectAsState()
    val prefs by preferenceStore.entries.collectAsState()
        val recipes by recipeStore.recipes.collectAsState()
        val recipeCount = recipes.size
        var command by remember { mutableStateOf("open Settings") }
    var prefKey by remember { mutableStateOf("cab_app") }
    var prefValue by remember { mutableStateOf("uber") }
    var logText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val running = ui.status == AgentRunStatus.Running

    LaunchedEffect(Unit) {
        while (true) {
            val lines = RingBufferLogger.snapshot()
                .takeLast(80)
                .joinToString("\n") { it.formatLine() }
            logText = lines
            delay(400)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Agent debug", style = MaterialTheme.typography.headlineSmall)

        ProviderKeyStatus(llmRouter = llmRouter, activeProvider = ui.provider)

        if (!llmRouter.hasAnyKey()) {
            Text(
                text = llmRouter.missingKeysMessage(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Mode:")
            FilterChip(
                selected = ui.mode == AgentMode.Coach,
                onClick = {
                    agentState.setMode(AgentMode.Coach)
                    OverlayService.instance?.setMode(AgentMode.Coach)
                },
                label = { Text("Coach") },
            )
            FilterChip(
                selected = ui.mode == AgentMode.Pilot,
                onClick = {
                    agentState.setMode(AgentMode.Pilot)
                    OverlayService.instance?.setMode(AgentMode.Pilot)
                },
                label = { Text("Pilot") },
            )
        }

        Text(
            text = buildString {
                append("Status: ${ui.statusLine}")
                if (ui.iteration > 0) append(" · iter ${ui.iteration}")
                if (ui.provider.isNotBlank()) append(" · ${ui.provider}")
                if (ui.model.isNotBlank()) {
                    append(" · model=")
                    append(ui.model)
                } else {
                    append(" · model=")
                    append(OpenAiClient.MODEL_PLANNING)
                    append(" (default)")
                }
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        ui.lastToolName?.let {
            Text(
                text = "Last tool: $it → ${ui.lastToolResult.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ui.lastSummary?.let {
            Text(text = "Summary: $it", style = MaterialTheme.typography.bodyMedium)
        }
        ui.lastError?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Command") },
            singleLine = true,
            enabled = !running,
        )

        Button(
            onClick = {
                if (!onEnsureReady()) return@Button
                if (!llmRouter.hasAnyKey()) {
                    return@Button
                }
                val utterance = command
                scope.launch {
                    agentLoop.run(utterance)
                }
            },
            enabled = !running && command.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (running) "Running…" else "Send")
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Chat memory: ${ui.sessionMessageCount} msgs" +
                    (agentState.lastDetectedLanguage?.let { " · STT lang=$it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    agentState.clearHistory()
                    Toast.makeText(context, "Chat history cleared", Toast.LENGTH_SHORT).show()
                },
                enabled = !running,
            ) {
                Text("Clear chat history")
            }
        }

        Text("User preferences (on-device)", style = MaterialTheme.typography.titleSmall)
        Text(
            text = if (prefs.isEmpty()) {
                "(none — e.g. cab_app=uber, food_app=swiggy, language=kn)"
            } else {
                prefs.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
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
                            "Invalid key/value (snake_case key, non-empty value)",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                enabled = !running && prefKey.isNotBlank() && prefValue.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text("Save preference")
            }
            Button(
                onClick = {
                    preferenceStore.clear()
                    Toast.makeText(context, "Preferences cleared", Toast.LENGTH_SHORT).show()
                },
                enabled = !running && prefs.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Text("Clear preferences")
            }
        }

        Text("Action recipes (path cache)", style = MaterialTheme.typography.titleSmall)
        Text(
            text = if (recipeCount == 0) {
                "(none — after a successful multi-step finish, paths like zepto+onion are cached)"
            } else {
                recipeStore.formatAsText()
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                recipeStore.clear()
                Toast.makeText(context, "Recipes cleared", Toast.LENGTH_SHORT).show()
            },
            enabled = !running && recipeCount > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Clear recipes")
        }

        Text("Ring buffer (tool calls)", style = MaterialTheme.typography.titleSmall)
        Text(
            text = logText.ifBlank { "(empty)" },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun ProviderKeyStatus(llmRouter: LlmRouter, activeProvider: String) {
    val openAi = BuildConfig.OPENAI_API_KEY
    val anthropic = BuildConfig.ANTHROPIC_API_KEY
    val deepgram = BuildConfig.DEEPGRAM_API_KEY
    val sarvam = BuildConfig.SARVAM_API_KEY
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = buildString {
                append("Active provider: ")
                append(activeProvider.ifBlank { if (llmRouter.hasOpenAiKey()) "OpenAI (default)" else if (llmRouter.hasAnthropicKey()) "Anthropic (default)" else "none" })
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "OpenAI key: ${maskKey(openAi)}",
            style = MaterialTheme.typography.bodySmall,
            color = if (openAi.isNotBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Text(
            text = "Anthropic key: ${maskKey(anthropic)}",
            style = MaterialTheme.typography.bodySmall,
            color = if (anthropic.isNotBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Text(
            text = "Sarvam key: ${maskKey(sarvam)}",
            style = MaterialTheme.typography.bodySmall,
            color = if (sarvam.isNotBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Text(
            text = "Deepgram key: ${maskKey(deepgram)}",
            style = MaterialTheme.typography.bodySmall,
            color = if (deepgram.isNotBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Text(
            text = "Chat models: planning/vision/escalated=${OpenAiClient.MODEL_PLANNING} · " +
                "fallback=${OpenAiClient.MODEL_FALLBACK} · fast=${OpenAiClient.MODEL_FAST} · " +
                "optional=${OpenAiClient.MODEL_GPT5}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Chat: OpenAI → Anthropic · Voice STT/TTS: Sarvam → Deepgram → OpenAI",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun maskKey(key: String): String {
    if (key.isBlank()) return "missing"
    val suffix = key.takeLast(4)
    return "present (…$suffix)"
}
