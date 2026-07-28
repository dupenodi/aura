package com.clicky.agent

/**
 * Detects user requests to save / promote a prior agent run into Learned flows.
 * Handles English and Kannada cues (including bilingual STT wrappers).
 */
object SaveFlowIntent {

    fun matches(text: String): Boolean {
        val hay = text.lowercase().trim()
        if (hay.isEmpty()) return false

        // Explicit Kannada substrings (no lowercasing needed for these glyphs).
        if (KANNADA_CUES.any { text.contains(it) }) return true

        if (ENGLISH_PHRASES.any { hay.contains(it) }) return true

        // Combined cues: "enough" + save, "save" + flow/this/it, "learn" + flow/this
        val hasSave = hay.contains("save") || hay.contains("ಸೇವ್")
        val hasFlow = hay.contains("flow") || hay.contains("ಫ್ಲೋ") || hay.contains("ಫ್ಲೊ")
        val hasEnough = hay.contains("enough") ||
            hay.contains("stop") ||
            hay.contains("that's all") ||
            hay.contains("thats all") ||
            hay.contains("done until") ||
            hay.contains("until now")
        val hasThis = hay.contains("this") || hay.contains("it") || hay.contains("that")
        val hasLearn = hay.contains("learn") || hay.contains("remember") || hay.contains("memor")

        if (hasSave && (hasFlow || hasThis || hasEnough)) return true
        if (hasLearn && (hasFlow || hasThis)) return true
        if (hasEnough && hasSave) return true

        // Bilingual wrapper: check English translation line specifically.
        val translation = Regex(
            """English translation:\s*(.+)""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.groupValues?.getOrNull(1).orEmpty()
        if (translation.isNotBlank() && translation != text) {
            return matches(translation)
        }
        return false
    }

    /** True when a finish(summary) is asking to persist the prior flow, not claiming task done. */
    fun matchesFinishSummary(summary: String): Boolean = matches(summary)

    private val ENGLISH_PHRASES = listOf(
        "save flow",
        "save the flow",
        "save this flow",
        "save that flow",
        "save this",
        "save it",
        "save the steps",
        "enough save",
        "save what we did",
        "save what you've done",
        "save what you have done",
        "save progress",
        "learn this flow",
        "remember this flow",
        "add to learned",
        "save to learned",
    )

    private val KANNADA_CUES = listOf(
        "ಫ್ಲೋ ಸೇವ್",
        "ಫ್ಲೊ ಸೇವ್",
        "ಸೇವ್ ಮಾಡ್ತಿಡಿ",
        "ಸೇವ್ ಮಾಡ್ತೀನಿ",
        "ಸೇವ್ ಮಾಡಿ",
        "ಸೇವ್ ಮಾಡು",
        "ಸೇವ್ ಮಾಡೋ",
        "ಫ್ಲೋ ನೆನಪಿಡು",
        "ಫ್ಲೋ ಉಳಿಸು",
    )
}
