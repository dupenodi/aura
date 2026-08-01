package com.drishti.voice

/**
 * Languages Aura can listen and speak in.
 *
 * The tags are BCP-47 so they feed the platform recogniser and TextToSpeech directly;
 * [sarvamCode] is the identifier Sarvam's speech APIs use for the same language.
 */
enum class AuraLanguage(
    val label: String,
    val nativeLabel: String,
    val tag: String,
    val sarvamCode: String,
) {
    English("English", "English", "en-IN", "en-IN"),
    Hindi("Hindi", "हिन्दी", "hi-IN", "hi-IN"),
    Bengali("Bengali", "বাংলা", "bn-IN", "bn-IN"),
    Telugu("Telugu", "తెలుగు", "te-IN", "te-IN"),
    Marathi("Marathi", "मराठी", "mr-IN", "mr-IN"),
    Tamil("Tamil", "தமிழ்", "ta-IN", "ta-IN"),
    Gujarati("Gujarati", "ગુજરાતી", "gu-IN", "gu-IN"),
    Kannada("Kannada", "ಕನ್ನಡ", "kn-IN", "kn-IN"),
    Malayalam("Malayalam", "മലയാളം", "ml-IN", "ml-IN"),
    Punjabi("Punjabi", "ਪੰਜਾਬੀ", "pa-IN", "pa-IN"),
    Odia("Odia", "ଓଡ଼ିଆ", "or-IN", "od-IN"),
    ;

    val locale: java.util.Locale
        get() = java.util.Locale.forLanguageTag(tag)

    companion object {
        fun fromTag(tag: String?): AuraLanguage =
            entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) } ?: English
    }
}

/** Which engine handles speech, when more than one is configured. */
enum class SpeechProvider(val label: String) {
    /** Whatever the phone already has — always available, no key, no network. */
    OnDevice("On-device"),

    /** Sarvam AI — strongest for Indian languages. */
    Sarvam("Sarvam"),

    /** Deepgram — fast streaming transcription. */
    Deepgram("Deepgram"),
    ;

    companion object {
        fun fromOrdinal(i: Int): SpeechProvider = entries.getOrElse(i) { OnDevice }
    }
}
