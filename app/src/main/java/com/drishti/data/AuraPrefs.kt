package com.drishti.data

import android.content.Context
import android.content.SharedPreferences
import com.drishti.ui.theme.GlowLevel
import com.drishti.ui.theme.OrbSkin
import com.drishti.voice.AuraLanguage
import com.drishti.voice.SpeechProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How Aura acts on a task. */
enum class AuraMode {
    /** Points at the next step and waits — the user's finger does everything. */
    Guide,

    /** Taps for the user, stopping at anything irreversible. */
    Auto,
    ;

    val label: String get() = if (this == Guide) "Guide me" else "Do it for me"
    val shortLabel: String get() = if (this == Guide) "Guide" else "Auto"
}

/** How quickly Auto works through steps. */
enum class AutoSpeed(val label: String, val stepPauseMs: Long) {
    FollowAlong("Follow-along", 1100L),
    Brisk("Brisk", 550L),
    ;

    companion object {
        fun fromOrdinal(i: Int): AutoSpeed = entries.getOrElse(i) { FollowAlong }
    }
}

/**
 * Everything the user has chosen about how Aura looks and behaves.
 *
 * Backed by plain SharedPreferences and exposed as StateFlows so both Compose
 * screens and the overlay service observe the same source of truth — a mode change
 * in Settings takes effect on the floating orb immediately.
 */
class AuraPrefs private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("aura_prefs", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(readMode())
    val mode: StateFlow<AuraMode> = _mode

    private val _orbSkin = MutableStateFlow(OrbSkin.fromOrdinal(prefs.getInt(KEY_ORB, 0)))
    val orbSkin: StateFlow<OrbSkin> = _orbSkin

    private val _glow = MutableStateFlow(GlowLevel.fromOrdinal(prefs.getInt(KEY_GLOW, 1)))
    val glow: StateFlow<GlowLevel> = _glow

    private val _autoSpeed = MutableStateFlow(AutoSpeed.fromOrdinal(prefs.getInt(KEY_AUTO_SPEED, 0)))
    val autoSpeed: StateFlow<AutoSpeed> = _autoSpeed

    private val _speakAloud = MutableStateFlow(prefs.getBoolean(KEY_SPEAK, true))
    val speakAloud: StateFlow<Boolean> = _speakAloud

    private val _hideInFullscreen = MutableStateFlow(prefs.getBoolean(KEY_HIDE_FULLSCREEN, true))
    val hideInFullscreen: StateFlow<Boolean> = _hideInFullscreen

    private val _paused = MutableStateFlow(prefs.getBoolean(KEY_PAUSED, false))
    val paused: StateFlow<Boolean> = _paused

    private val _language = MutableStateFlow(AuraLanguage.fromTag(prefs.getString(KEY_LANGUAGE, null)))
    val language: StateFlow<AuraLanguage> = _language

    private val _speechProvider =
        MutableStateFlow(SpeechProvider.fromOrdinal(prefs.getInt(KEY_SPEECH_PROVIDER, 0)))
    val speechProvider: StateFlow<SpeechProvider> = _speechProvider

    /** Where the user last parked the orb; -1 means "never moved it". */
    var orbX: Int
        get() = prefs.getInt(KEY_ORB_X, -1)
        set(value) = prefs.edit().putInt(KEY_ORB_X, value).apply()

    var orbY: Int
        get() = prefs.getInt(KEY_ORB_Y, -1)
        set(value) = prefs.edit().putInt(KEY_ORB_Y, value).apply()

    var onboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()

    fun setMode(mode: AuraMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _mode.value = mode
    }

    fun setOrbSkin(skin: OrbSkin) {
        prefs.edit().putInt(KEY_ORB, skin.ordinal).apply()
        _orbSkin.value = skin
    }

    fun setGlow(level: GlowLevel) {
        prefs.edit().putInt(KEY_GLOW, level.ordinal).apply()
        _glow.value = level
    }

    fun setAutoSpeed(speed: AutoSpeed) {
        prefs.edit().putInt(KEY_AUTO_SPEED, speed.ordinal).apply()
        _autoSpeed.value = speed
    }

    fun setSpeakAloud(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPEAK, enabled).apply()
        _speakAloud.value = enabled
    }

    fun setHideInFullscreen(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HIDE_FULLSCREEN, enabled).apply()
        _hideInFullscreen.value = enabled
    }

    fun setPaused(paused: Boolean) {
        prefs.edit().putBoolean(KEY_PAUSED, paused).apply()
        _paused.value = paused
    }

    fun setLanguage(language: AuraLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.tag).apply()
        _language.value = language
    }

    fun setSpeechProvider(provider: SpeechProvider) {
        prefs.edit().putInt(KEY_SPEECH_PROVIDER, provider.ordinal).apply()
        _speechProvider.value = provider
    }

    // ---- Per-app mode overrides -------------------------------------------------

    fun overrideFor(packageName: String): AuraMode? {
        if (LockedToGuide.isLocked(packageName)) return AuraMode.Guide
        val raw = prefs.getString(overrideKey(packageName), null) ?: return null
        return runCatching { AuraMode.valueOf(raw) }.getOrNull()
    }

    fun setOverride(packageName: String, mode: AuraMode?) {
        prefs.edit().apply {
            if (mode == null) remove(overrideKey(packageName)) else putString(overrideKey(packageName), mode.name)
        }.apply()
    }

    fun overrideCount(): Int = prefs.all.keys.count { it.startsWith(PREFIX_OVERRIDE) }

    /**
     * The mode Aura should actually use for [packageName]: a per-app override wins over
     * the global default, and sensitive apps are pinned to Guide no matter what.
     */
    fun effectiveMode(packageName: String): AuraMode =
        overrideFor(packageName) ?: _mode.value

    private fun readMode(): AuraMode =
        runCatching { AuraMode.valueOf(prefs.getString(KEY_MODE, null) ?: AuraMode.Guide.name) }
            .getOrDefault(AuraMode.Guide)

    private fun overrideKey(packageName: String) = "$PREFIX_OVERRIDE$packageName"

    companion object {
        private const val KEY_MODE = "mode"
        private const val KEY_ORB = "orb_skin"
        private const val KEY_GLOW = "glow"
        private const val KEY_AUTO_SPEED = "auto_speed"
        private const val KEY_SPEAK = "speak_aloud"
        private const val KEY_HIDE_FULLSCREEN = "hide_fullscreen"
        private const val KEY_PAUSED = "paused"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_ORB_X = "orb_x"
        private const val KEY_ORB_Y = "orb_y"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_SPEECH_PROVIDER = "speech_provider"
        private const val PREFIX_OVERRIDE = "override_"

        @Volatile
        private var instance: AuraPrefs? = null

        fun get(context: Context): AuraPrefs =
            instance ?: synchronized(this) {
                instance ?: AuraPrefs(context).also { instance = it }
            }
    }
}

/**
 * Apps where Auto is never allowed and screen reading is suspended entirely.
 *
 * This is a product promise ("Banking, health and password apps are locked to Guide.
 * That can't be changed."), so it is enforced in code rather than left to settings.
 */
object LockedToGuide {
    private val KEYWORDS = listOf(
        "bank", "banking", "wallet", "upi", "paytm", "phonepe", "gpay", "paypal",
        "chase", "hsbc", "barclays", "monzo", "revolut", "wise", "coinbase",
        "health", "medical", "patient", "nhs", "insur",
        "password", "authenticator", "keepass", "bitwarden", "1password", "lastpass",
    )

    fun isLocked(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        return KEYWORDS.any { pkg.contains(it) }
    }
}
