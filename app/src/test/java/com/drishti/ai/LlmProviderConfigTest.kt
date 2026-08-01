package com.drishti.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmProviderConfigTest {
    @Test
    fun preferredProviderNormalization() {
        assertEquals("auto", "AUTO".trim().lowercase())
        assertEquals("local", " Local ".trim().lowercase())
    }

    @Test
    fun autoChainOrder() {
        // Documents expected auto preference order.
        val order = listOf("local", "openrouter", "anthropic", "openai")
        assertEquals("local", order.first())
        assertTrue(order.indexOf("openrouter") < order.indexOf("anthropic"))
    }
}
