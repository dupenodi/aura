package com.drishti.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guidance loop decides "the user did it" from these comparisons, so a clock ticking
 * over must not read as a screen change, and a new screen must never be missed.
 */
class ScreenChangeTest {

    private fun screen(vararg rows: String) = rows.toSet()

    @Test
    fun identicalScreensHaveNotMovedOn() {
        val s = screen("a", "b", "c", "d", "e")
        assertFalse(TreeJson.movedOn(s, s))
    }

    @Test
    fun oneDetailRedrawnIsStillTheSameScreen() {
        val before = screen("clock:10:04", "a", "b", "c", "d", "e", "f", "g", "h", "i")
        val after = screen("clock:10:05", "a", "b", "c", "d", "e", "f", "g", "h", "i")
        assertFalse(TreeJson.movedOn(before, after))
    }

    @Test
    fun aNewScreenHasMovedOn() {
        val before = screen("settings", "wifi", "sound", "display")
        val after = screen("wifi on", "network 1", "network 2", "network 3")
        assertTrue(TreeJson.movedOn(before, after))
    }

    @Test
    fun aDialogOpeningOverTheScreenHasMovedOn() {
        val before = screen("a", "b", "c", "d")
        val after = screen("a", "b", "c", "d", "Delete?", "Cancel", "OK")
        assertTrue(TreeJson.movedOn(before, after))
    }

    @Test
    fun anEmptyReadIsNotMistakenForProgress() {
        assertFalse(TreeJson.movedOn(emptySet(), emptySet()))
    }

    @Test
    fun aFailedReadIsNotMistakenForProgress() {
        val before = screen("settings", "wifi", "sound", "display")
        // The tree momentarily comes back empty. Nothing happened; we just cannot see.
        assertFalse(TreeJson.movedOn(before, emptySet()))
    }

    @Test
    fun theFirstReadableScreenIsNotMistakenForProgress() {
        val after = screen("settings", "wifi", "sound", "display")
        assertFalse(TreeJson.movedOn(emptySet(), after))
    }
}
