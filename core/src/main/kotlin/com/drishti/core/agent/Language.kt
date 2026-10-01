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

        /**
         * The language a request is written in, from its script; null for Latin script
         * (English, or Hindi typed in English letters — the caller decides).
         * Devanagari is Hindi unless it has ळ, which is common in Marathi and rare in Hindi.
         */
        fun detect(text: String): Language? {
            val counts = HashMap<Language, Int>()
            for (ch in text) {
                val lang = when (ch.code) {
                    in 0x0900..0x097F -> Hindi
                    in 0x0980..0x09FF -> Bengali
                    in 0x0A00..0x0A7F -> Punjabi
                    in 0x0A80..0x0AFF -> Gujarati
                    in 0x0B00..0x0B7F -> Odia
                    in 0x0B80..0x0BFF -> Tamil
                    in 0x0C00..0x0C7F -> Telugu
                    in 0x0C80..0x0CFF -> Kannada
                    in 0x0D00..0x0D7F -> Malayalam
                    else -> null
                } ?: continue
                counts[lang] = (counts[lang] ?: 0) + 1
            }
            val top = counts.maxByOrNull { it.value }?.key ?: return null
            return if (top == Hindi && text.contains('ळ')) Marathi else top
        }

        /** Maps a code Sarvam detected (either Odia spelling) back to a language. */
        fun fromSarvam(code: String?): Language? = entries.firstOrNull {
            it.sarvamCode.equals(code, true) || it.sarvamRealtimeCode.equals(code, true)
        }
    }
}
