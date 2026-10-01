package com.drishti.core.agent

/**
 * Languages Aura listens and speaks in.
 *
 * Sarvam is not consistent about Odia: the REST speech and TTS APIs call it `od-IN`, the
 * realtime speech socket calls it `or-IN`. Each use site picks its own code from here.
 */
enum class Language(
    val label: String,
    val nativeLabel: String,
    /** BCP-47 tag for the platform recogniser and TextToSpeech. */
    val tag: String,
    /** Sarvam REST speech-to-text and text-to-speech. */
    val sarvamCode: String,
    /** Sarvam realtime speech-to-text socket. */
    val sarvamRealtimeCode: String,
    /** Bulbul v3 can speak it. */
    val sarvamTts: Boolean = true,
) {
    English("English", "English", "en-IN", "en-IN", "en-IN"),
    Hindi("Hindi", "हिन्दी", "hi-IN", "hi-IN", "hi-IN"),
    Bengali("Bengali", "বাংলা", "bn-IN", "bn-IN", "bn-IN"),
    Telugu("Telugu", "తెలుగు", "te-IN", "te-IN", "te-IN"),
    Marathi("Marathi", "मराठी", "mr-IN", "mr-IN", "mr-IN"),
    Tamil("Tamil", "தமிழ்", "ta-IN", "ta-IN", "ta-IN"),
    Gujarati("Gujarati", "ગુજરાતી", "gu-IN", "gu-IN", "gu-IN"),
    Kannada("Kannada", "ಕನ್ನಡ", "kn-IN", "kn-IN", "kn-IN"),
    Malayalam("Malayalam", "മലയാളം", "ml-IN", "ml-IN", "ml-IN"),
    Punjabi("Punjabi", "ਪੰਜਾਬੀ", "pa-IN", "pa-IN", "pa-IN"),
    Odia("Odia", "ଓଡ଼ିଆ", "or-IN", "od-IN", "or-IN"),
    ;

    companion object {
        fun fromTag(tag: String?): Language =
            entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) } ?: English

        /** Maps a code Sarvam detected (either Odia spelling) back to a language. */
        fun fromSarvam(code: String?): Language? = entries.firstOrNull {
            it.sarvamCode.equals(code, true) || it.sarvamRealtimeCode.equals(code, true)
        }
    }
}
