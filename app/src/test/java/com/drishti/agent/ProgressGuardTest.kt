package com.drishti.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressGuardTest {
    @Test
    fun repeatedIdenticalActionsTripStuck() {
        val guard = ProgressGuard()
        assertFalse(guard.record("swipe", """{"a":1}"""))
        assertFalse(guard.record("swipe", """{"a":1}"""))
        assertTrue(guard.record("swipe", """{"a":1}"""))
    }

    @Test
    fun differentActionsResetRepeatStreak() {
        val guard = ProgressGuard()
        assertFalse(guard.record("swipe", """{"a":1}"""))
        assertFalse(guard.record("swipe", """{"a":1}"""))
        assertFalse(guard.record("tap", """{"index":3}"""))
        assertEquals(0, guard.repeatActionStreak)
    }

    @Test
    fun nonProgressToolsDoNotCount() {
        val guard = ProgressGuard()
        assertFalse(guard.record("speak", """{"text":"hi"}"""))
        assertFalse(guard.record("speak", """{"text":"hi"}"""))
        assertFalse(guard.record("speak", """{"text":"hi"}"""))
        assertEquals(0, guard.repeatActionStreak)
    }

    @Test
    fun fingerprintNoChangeTripsAfterLimit() {
        val guard = ProgressGuard()
        assertFalse(guard.recordFingerprint("a", "tap"))
        assertFalse(guard.recordFingerprint("a", "tap"))
        assertFalse(guard.recordFingerprint("a", "tap"))
        assertTrue(guard.recordFingerprint("a", "tap"))
    }
}
