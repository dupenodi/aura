package com.drishti.data

import android.content.Context
import android.content.SharedPreferences
import com.drishti.ui.theme.GlowLevel
import com.drishti.ui.theme.OrbSkin
import com.drishti.voice.AuraLanguage
import com.drishti.voice.SpeechProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the user has chosen about how Aura looks and behaves.
 *
 * Backed by plain SharedPreferences and exposed as StateFlows so both Compose
 * screens and the overlay service observe the same source of truth — a change in
 * Settings takes effect on the floating orb immediately.
 */
class AuraPrefs private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("aura_prefs", Context.MODE_PRIVATE)

    private val _orbSkin = MutableStateFlow(OrbSkin.fromOrdinal(prefs.getInt(KEY_ORB, 0)))
    val orbSkin: StateFlow<OrbSkin> = _orbSkin

    private val _glow = MutableStateFlow(GlowLevel.fromOrdinal(prefs.getInt(KEY_GLOW, 1)))
    val glow: StateFlow<GlowLevel> = _glow

    private val _speakAloud = MutableStateFlow(prefs.getBoolean(KEY_SPEAK, true))
    val speakAloud: StateFlow<Boolean> = _speakAloud

    private val _paused = MutableStateFlow(prefs.getBoolean(KEY_PAUSED, false))
    val paused: StateFlow<Boolean> = _paused

    private val _language = MutableStateFlow(AuraLanguage.fromTag(prefs.getString(KEY_LANGUAGE, null)))
    val language: StateFlow<AuraLanguage> = _language

    // Sarvam is the better voice for Indian languages, so it is the default whenever a key
    // was built in; the phone's own engine otherwise.
    private val _speechProvider = MutableStateFlow(
        SpeechProvider.fromOrdinal(
            prefs.getInt(
                KEY_SPEECH_PROVIDER,
                if (com.drishti.BuildConfig.SARVAM_API_KEY.isNotBlank()) SpeechProvider.Sarvam.ordinal else 0,
            ),
        ),
    )
    val speechProvider: StateFlow<SpeechProvider> = _speechProvider

    /** Bulbul v3 speaker. */
    private val _voiceSpeaker = MutableStateFlow(prefs.getString(KEY_VOICE_SPEAKER, null) ?: "priya")
    val voiceSpeaker: StateFlow<String> = _voiceSpeaker

    /** Speaking rate, 0.5–2.0; slightly slow by default for people following along. */
    private val _voicePace = MutableStateFlow(prefs.getFloat(KEY_VOICE_PACE, 0.9f).toDouble())
    val voicePace: StateFlow<Double> = _voicePace

    /** Let Aura look at a screenshot when the screen can't be read as text (games, maps). */
    private val _useScreenshots = MutableStateFlow(prefs.getBoolean(KEY_SCREENSHOTS, true))
    val useScreenshots: StateFlow<Boolean> = _useScreenshots

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

    fun setOrbSkin(skin: OrbSkin) {
        prefs.edit().putInt(KEY_ORB, skin.ordinal).apply()
        _orbSkin.value = skin
    }

    fun setGlow(level: GlowLevel) {
        prefs.edit().putInt(KEY_GLOW, level.ordinal).apply()
        _glow.value = level
    }

    fun setSpeakAloud(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPEAK, enabled).apply()
        _speakAloud.value = enabled
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

    fun setVoiceSpeaker(speaker: String) {
        prefs.edit().putString(KEY_VOICE_SPEAKER, speaker).apply()
        _voiceSpeaker.value = speaker
    }

    fun setVoicePace(pace: Double) {
        prefs.edit().putFloat(KEY_VOICE_PACE, pace.toFloat()).apply()
        _voicePace.value = pace
    }

    fun setUseScreenshots(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCREENSHOTS, enabled).apply()
        _useScreenshots.value = enabled
    }

    companion object {
        private const val KEY_VOICE_SPEAKER = "voice_speaker"
        private const val KEY_VOICE_PACE = "voice_pace"
        private const val KEY_SCREENSHOTS = "use_screenshots"
        private const val KEY_ORB = "orb_skin"
        private const val KEY_GLOW = "glow"
        private const val KEY_SPEAK = "speak_aloud"
        private const val KEY_PAUSED = "paused"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_ORB_X = "orb_x"
        private const val KEY_ORB_Y = "orb_y"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_SPEECH_PROVIDER = "speech_provider"

        @Volatile
        private var instance: AuraPrefs? = null

        fun get(context: Context): AuraPrefs =
            instance ?: synchronized(this) {
                instance ?: AuraPrefs(context).also { instance = it }
            }
    }
}
