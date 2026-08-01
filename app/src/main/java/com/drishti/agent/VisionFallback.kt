package com.drishti.agent

import com.drishti.accessibility.ScreenAgentAccessibilityService
import com.drishti.accessibility.TreeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Sparse-tree / package blocklist heuristic for vision fallback. */
object VisionFallback {
    private const val MIN_ACTIONABLE = 3
    private const val SCREENSHOT_TIMEOUT_MS = 8_000L

    private val BLOCKLIST = setOf(
        "com.google.android.webview",
    )

    fun shouldUseVision(packageName: String, roots: List<com.drishti.accessibility.ElementNode>): Boolean {
        if (packageName in BLOCKLIST) return true
        return TreeJson.actionableCount(roots) < MIN_ACTIONABLE
    }

    /** Never call from the main thread — awaits the a11y screenshot future. */
    suspend fun captureScreenshotBase64(): String? = withContext(Dispatchers.IO) {
        val service = ScreenAgentAccessibilityService.getInstance() ?: return@withContext null
        withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
            val result = service.takeScreenshotBase64(hideOverlay = true).await()
            if (result.startsWith("error:")) null else result
        }
    }
}
