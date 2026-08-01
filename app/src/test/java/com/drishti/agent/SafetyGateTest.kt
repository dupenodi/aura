package com.drishti.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyGateTest {
    @Test
    fun flagsPaymentKeywords() {
        assertTrue(SafetyGate.isSensitive("tap", "Confirm payment"))
        assertTrue(SafetyGate.isSensitive("type", "123456", "Enter OTP"))
        assertTrue(SafetyGate.isSensitive("tap", "", "Uninstall"))
        assertTrue(SafetyGate.isSensitive("tap", "", "Grant permission"))
    }

    @Test
    fun allowsOrdinaryNavigation() {
        assertFalse(SafetyGate.isSensitive("tap", "index=3", "Chats"))
        assertFalse(SafetyGate.isSensitive("open_app", "com.whatsapp"))
        assertFalse(SafetyGate.isSensitive("tap", "", "Allow notifications later"))
    }
}
