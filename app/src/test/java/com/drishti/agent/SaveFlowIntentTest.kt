package com.drishti.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveFlowIntentTest {
    @Test
    fun matchesEnglishSavePhrases() {
        assertTrue(SaveFlowIntent.matches("save the flow done until now"))
        assertTrue(SaveFlowIntent.matches("enough, save this"))
        assertTrue(SaveFlowIntent.matches("Save flow please"))
        assertTrue(SaveFlowIntent.matches("add to learned flows"))
    }

    @Test
    fun matchesKannadaCues() {
        assertTrue(SaveFlowIntent.matches("ಫ್ಲೋ ಸೇವ್ ಮಾಡಿ"))
        assertTrue(SaveFlowIntent.matches("ಸೇವ್ ಮಾಡ್ತಿಡಿ"))
    }

    @Test
    fun matchesBilingualWrapper() {
        val utterance = """
            User spoke Kannada.
            Kannada: ಸಾಕು ಫ್ಲೋ ಸೇವ್ ಮಾಡಿ
            English translation: enough, save the flow done until now
            (Use English for tool arguments…)
        """.trimIndent()
        assertTrue(SaveFlowIntent.matches(utterance))
    }

    @Test
    fun rejectsNormalTaskUtterances() {
        assertFalse(SaveFlowIntent.matches("order onion garlic from zepto"))
        assertFalse(SaveFlowIntent.matches("open swiggy and search biryani"))
        assertFalse(SaveFlowIntent.matches("continue"))
    }
}
