package com.drishti.agent

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock

/**
 * What the agent is allowed to know about the phone itself.
 *
 * Without this the model guesses package names from memory and then reports apps as
 * missing when they are installed under a different id — the single biggest source of
 * false "that app isn't installed" answers.
 */
object DeviceContext {

    private const val CACHE_MS = 60_000L

    @Volatile
    private var cached: List<AppEntry> = emptyList()

    @Volatile
    private var cachedAt = 0L

    data class AppEntry(val label: String, val packageName: String)

    /** Launchable apps, alphabetically, cached briefly since this is a slow PM call. */
    fun installedApps(context: Context): List<AppEntry> {
        val now = SystemClock.elapsedRealtime()
        val snapshot = cached
        if (snapshot.isNotEmpty() && now - cachedAt < CACHE_MS) return snapshot

        val pm = context.packageManager
        val apps = runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { AppEntry(pm.getApplicationLabel(it).toString(), it.packageName) }
                .filter { it.label.isNotBlank() }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
                .toList()
        }.getOrDefault(emptyList())

        cached = apps
        cachedAt = now
        return apps
    }

    /**
     * Formatted for a prompt. Capped because a long tail of system packages costs tokens
     * without helping — the model only needs things a person would name out loud.
     */
    fun installedAppsBlock(context: Context, limit: Int = 80): String {
        val apps = installedApps(context)
        if (apps.isEmpty()) return "(unavailable)"
        return apps.take(limit).joinToString("\n") { "${it.label} → ${it.packageName}" }
    }

    /** Resolves a name or package the model produced onto something actually installed. */
    fun resolve(context: Context, requested: String): String? {
        if (requested.isBlank()) return null
        val pm = context.packageManager
        if (runCatching { pm.getLaunchIntentForPackage(requested) }.getOrNull() != null) {
            return requested
        }

        val apps = installedApps(context)
        val needle = requested.substringAfterLast('.').lowercase()
            .ifBlank { requested.lowercase() }

        apps.firstOrNull { it.label.equals(needle, ignoreCase = true) }?.let { return it.packageName }
        apps.firstOrNull { it.packageName.equals(requested, ignoreCase = true) }?.let { return it.packageName }
        // Prefer the shortest containment match: "uber" should win over "uber driver".
        return apps
            .filter {
                it.packageName.lowercase().contains(needle) ||
                    it.label.lowercase().contains(needle)
            }
            .minByOrNull { it.label.length }
            ?.packageName
    }

    /** True when something matching [requested] can actually be launched. */
    fun isInstalled(context: Context, requested: String): Boolean =
        resolve(context, requested) != null
}
