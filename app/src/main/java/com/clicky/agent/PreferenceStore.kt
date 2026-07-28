package com.clicky.agent

import android.content.Context
import android.content.SharedPreferences
import com.clicky.debug.RingBufferLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device user preference memory (SharedPreferences — not Supabase / RAG).
 *
 * Example keys:
 * - `cab_app` = uber | ola
 * - `food_app` = swiggy | zomato
 * - `language` = kn | en | hi | …
 * - freeform notes via any other key
 */
@Singleton
class PreferenceStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _entries = MutableStateFlow(readAll())
    val entries: StateFlow<Map<String, String>> = _entries.asStateFlow()

    fun getAll(): Map<String, String> = _entries.value

    fun get(key: String): String? {
        val k = normalizeKey(key) ?: return null
        return _entries.value[k]
    }

    fun set(key: String, value: String): Boolean {
        val k = normalizeKey(key) ?: return false
        val v = value.trim()
        if (v.isEmpty()) return false
        prefs.edit().putString(k, v.take(MAX_VALUE_CHARS)).apply()
        refresh()
        RingBufferLogger.log("prefs", "set $k=$v")
        return true
    }

    fun remove(key: String): Boolean {
        val k = normalizeKey(key) ?: return false
        if (!prefs.contains(k)) return false
        prefs.edit().remove(k).apply()
        refresh()
        RingBufferLogger.log("prefs", "removed $k")
        return true
    }

    fun clear() {
        prefs.edit().clear().apply()
        refresh()
        RingBufferLogger.log("prefs", "cleared all preferences")
    }

    /** Human-readable dump for the get_preferences tool / debug UI. */
    fun formatAsText(): String {
        val map = getAll()
        if (map.isEmpty()) return "(no preferences saved)"
        return map.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }
    }

    /** Block injected into the system prompt each turn. */
    fun formatForPrompt(): String {
        val map = getAll()
        if (map.isEmpty()) {
            return """
User preferences (on-device; empty):
(none yet — if the user states a lasting preference like "always use Uber", call set_preference)
""".trimIndent()
        }
        return buildString {
            appendLine("User preferences (on-device, persist across sessions):")
            for ((k, v) in map.entries.sortedBy { it.key }) {
                append(k)
                append('=')
                append(v)
                append('\n')
            }
            appendLine("Use these when the user does not specify an app/option (e.g. cab_app → open that cab app).")
        }.trimEnd()
    }

    private fun refresh() {
        _entries.value = readAll()
    }

    private fun readAll(): Map<String, String> {
        @Suppress("UNCHECKED_CAST")
        val raw = prefs.all as Map<String, Any?>
        return raw.mapNotNull { (k, v) ->
            val value = v?.toString()?.trim().orEmpty()
            if (k.isBlank() || value.isEmpty()) null else k to value
        }.toMap()
    }

    private fun normalizeKey(key: String): String? {
        val k = key.trim().lowercase().replace(' ', '_')
        if (k.isEmpty() || k.length > MAX_KEY_CHARS) return null
        if (!KEY_REGEX.matches(k)) return null
        return k
    }

    companion object {
        private const val PREFS_NAME = "clicky_user_preferences"
        private const val MAX_KEY_CHARS = 64
        private const val MAX_VALUE_CHARS = 500
        private val KEY_REGEX = Regex("^[a-z0-9_]+$")
    }
}
