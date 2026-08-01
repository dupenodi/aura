package com.drishti.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentErrorsTest {

    @Test
    fun `credit exhaustion is explained in plain words`() {
        val message = AgentErrors.humanise(
            """openrouter HTTP 402: {"error":{"message":"Insufficient credits."}}""",
        )
        assertTrue(message.contains("credit", ignoreCase = true))
        assertNoTechnicalDetail(message)
    }

    @Test
    fun `permission denied is explained without the payload`() {
        val message = AgentErrors.humanise(
            """gemini HTTP 403: [{"error":{"code":403,"status":"PERMISSION_DENIED"}}]""",
        )
        assertTrue(message.contains("access", ignoreCase = true))
        assertNoTechnicalDetail(message)
    }

    @Test
    fun `missing key points at settings`() {
        val message = AgentErrors.humanise("HTTP 401 invalid api key")
        assertTrue(message.contains("key", ignoreCase = true))
        assertNoTechnicalDetail(message)
    }

    @Test
    fun `network loss is stated as network loss`() {
        val message = AgentErrors.humanise("java.net.UnknownHostException: Unable to resolve host")
        assertTrue(message.contains("internet", ignoreCase = true))
    }

    @Test
    fun `unconfigured router is its own message`() {
        val message = AgentErrors.humanise("No LLM providers configured. Set keys.")
        assertEquals("No language model is set up yet.", message)
    }

    @Test
    fun `unknown failures never leak the original text`() {
        val raw = "kotlin.IllegalStateException: totally unexpected internal detail 0xDEADBEEF"
        val message = AgentErrors.humanise(raw)
        assertFalse(message.contains("0xDEADBEEF"))
        assertFalse(message.contains("IllegalStateException"))
        assertNoTechnicalDetail(message)
    }

    @Test
    fun `null failure still produces a sentence`() {
        assertTrue(AgentErrors.humanise(null).isNotBlank())
    }

    /** Nothing user-facing should carry an HTTP code, JSON or a stack-trace fragment. */
    private fun assertNoTechnicalDetail(message: String) {
        listOf("HTTP", "{", "}", "[", "]", "Exception", "403", "402", "401").forEach {
            assertFalse("leaked \"$it\" in: $message", message.contains(it))
        }
    }
}
