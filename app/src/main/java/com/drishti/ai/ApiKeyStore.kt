package com.drishti.ai

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.drishti.BuildConfig

/**
 * API key resolution for the POC.
 *
 * Prefer EncryptedSharedPreferences override, else BuildConfig from local.properties.
 * TODO: Move behind a backend proxy before any real distribution.
 */
object ApiKeyStore {
    private const val PREFS = "drishti_secure"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun resolve(provider: String): String {
        val keyName = prefKey(provider)
        val ctx = appContext
        if (ctx != null) {
            val stored = prefs(ctx).getString(keyName, null)
            if (!stored.isNullOrBlank()) return stored
        }
        return when (provider.lowercase()) {
            "anthropic" -> BuildConfig.ANTHROPIC_API_KEY
            "openai" -> BuildConfig.OPENAI_API_KEY
            "openrouter" -> BuildConfig.OPENROUTER_API_KEY
            "gemini" -> BuildConfig.GEMINI_API_KEY
            "local" -> BuildConfig.LOCAL_LLM_API_KEY
            else -> ""
        }
    }

    fun setOverride(context: Context, provider: String, key: String) {
        prefs(context).edit().putString(prefKey(provider), key.trim()).apply()
    }

    fun clearOverride(context: Context, provider: String) {
        prefs(context).edit().remove(prefKey(provider)).apply()
    }

    private fun prefKey(provider: String) = "${provider.lowercase()}_api_key"

    private fun prefs(context: Context) =
        EncryptedSharedPreferences.create(
            context,
            PREFS,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
}
