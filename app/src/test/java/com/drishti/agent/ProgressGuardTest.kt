package com.drishti.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressGuardTest {
    @Test
    fun repeatedIdenticalStepsTripStuck() {
        val guard = ProgressGuard()
        assertFalse(guard.record("point_at", """{"index":3}"""))
        assertFalse(guard.record("point_at", """{"index":3}"""))
        assertTrue(guard.record("point_at", """{"index":3}"""))
    }

    @Test
    fun differentStepsResetRepeatStreak() {
        val guard = ProgressGuard()
        assertFalse(guard.record("point_at", """{"index":3}"""))
        assertFalse(guard.record("point_at", """{"index":3}"""))
        assertFalse(guard.record("point_at", """{"index":7}"""))
        assertEquals(0, guard.repeatActionStreak)
    }

    @Test
    fun sayingTheSameSentenceOverAndOverTripsStuck() {
        val guard = ProgressGuard()
        assertFalse(guard.record("speak", """{"text":"nearly there"}"""))
        assertFalse(guard.record("speak", """{"text":"nearly there"}"""))
        assertTrue(guard.record("speak", """{"text":"nearly there"}"""))
    }

    @Test
    fun finishingIsNeverALoop() {
        val guard = ProgressGuard()
        repeat(4) { assertFalse(guard.record("done", """{"summary":"ok"}""")) }
        assertEquals(0, guard.repeatActionStreak)
    }
}
