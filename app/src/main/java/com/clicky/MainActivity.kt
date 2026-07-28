package com.clicky

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.clicky.agent.AgentLoop
import com.clicky.agent.AgentState
import com.clicky.agent.FlowHistoryStore
import com.clicky.agent.PreferenceStore
import com.clicky.agent.RecipeStore
import com.clicky.ui.ClickyHomeScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Clicky Insights home — preferences, learned flows, recent activity, and setup.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var agentLoop: AgentLoop
    @Inject lateinit var agentState: AgentState
    @Inject lateinit var preferenceStore: PreferenceStore
    @Inject lateinit var recipeStore: RecipeStore
    @Inject lateinit var flowHistoryStore: FlowHistoryStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ClickyHomeScreen(
                        agentLoop = agentLoop,
                        agentState = agentState,
                        preferenceStore = preferenceStore,
                        recipeStore = recipeStore,
                        flowHistoryStore = flowHistoryStore,
                        requestMicOnLaunch = intent?.getBooleanExtra(EXTRA_REQUEST_MIC, false) == true,
                        highlightLastFlow = intent?.getBooleanExtra(EXTRA_SHOW_LAST_FLOW, false) == true,
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_REQUEST_MIC = "com.clicky.REQUEST_MIC"
        const val EXTRA_SHOW_LAST_FLOW = "com.clicky.SHOW_LAST_FLOW"
    }
}
