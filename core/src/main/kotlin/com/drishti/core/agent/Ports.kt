package com.drishti.core.agent

import com.drishti.core.screen.Bounds
import com.drishti.core.screen.Screen
import com.drishti.core.screen.UiEvent
import kotlinx.coroutines.flow.Flow

/**
 * The phone, as the agent sees it. The real one is the accessibility service; the eval one
 * is a simulated phone with a scripted person holding it.
 */
interface Device {
    /** Everything the platform reports, as it happens. Hot: only events after collection starts. */
    val events: Flow<UiEvent>

    /** Reads the screen now. Null when it can't be read (permission lost, locked). */
    suspend fun snapshot(): Screen?

    /** A picture of the screen, for screens the tree can't describe. Null if unavailable. */
    suspend fun screenshot(): Screenshot?

    /** Bounds of a node as they are right now — lists settle, keyboards push things up. */
    suspend fun liveBounds(nodeKey: Int): Bounds?

    fun installedApps(): List<AppInfo>

    /** False when Aura has lost the ability to read the screen at all (service switched off). */
    fun canSeeScreen(): Boolean = true

    /** "WhatsApp", "whatsapp", "com.whatsapp" → the installed app, if there is one. */
    fun resolveApp(query: String): AppInfo? = AppMatcher.resolve(installedApps(), query)

    val info: DeviceInfo
}

data class DeviceInfo(
    val model: String = "Android phone",
    val androidVersion: String = "",
    val ownPackage: String = "com.drishti",
    val launcherPackage: String? = null,
)

class Screenshot(
    /** JPEG bytes, already downscaled for the model. */
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
)

/** What the person sees on top of the app they are using. */
interface GuideUi {
    /** Aura is working it out. [text] is null to show activity without words. */
    fun thinking(text: String?)

    /** Ring the thing to press (or show a swipe hint) with the instruction beside it. */
    fun show(step: ShownStep)

    /** They have been looking for a while: make the ring harder to miss. */
    fun emphasize()

    fun hide()

    /** Waiting for them to come back; the session resumes from the orb. */
    fun paused(message: String)

    fun finished(message: String, success: Boolean)
}

data class ShownStep(
    val action: Action,
    val instruction: String,
    /** Ring here; null when the step is a gesture with no single target (back, scroll). */
    val bounds: Bounds?,
    val direction: Direction? = null,
    /** What to type, shown as a chip they can read while typing. */
    val text: String? = null,
    /** open_app: the package they are being asked to open. */
    val app: String? = null,
)

interface Voice {
    /** Speaks [text], replacing anything still being said. Never blocks. */
    fun say(text: String, language: Language)

    fun stop()
}

object AppMatcher {
    fun resolve(apps: List<AppInfo>, query: String): AppInfo? {
        val q = query.trim()
        if (q.isEmpty()) return null
        apps.firstOrNull { it.pkg.equals(q, true) }?.let { return it }
        apps.firstOrNull { it.label.equals(q, true) }?.let { return it }
        val needle = q.substringAfterLast('.').lowercase().ifBlank { q.lowercase() }
        apps.firstOrNull { it.label.equals(needle, true) }?.let { return it }
        // Shortest containment match: "uber" should find Uber, not Uber Driver.
        return apps
            .filter { it.label.lowercase().contains(needle) || it.pkg.lowercase().contains(needle) }
            .minByOrNull { it.label.length }
    }
}
