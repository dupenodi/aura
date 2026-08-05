package com.drishti.accessibility

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guidance loop decides "the user did it" partly from which app is in front, so an
 * event from one of our own overlay windows must never look like the user navigating.
 * When it did, every step completed itself about half a second after it was shown.
 */
class ForegroundPackageSignalTest {

    private val own = "com.drishti"

    private fun signal(pkg: String, type: Int = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) =
        ScreenAgentAccessibilityService.isForegroundPackageSignal(pkg, type, own)

    @Test
    fun anotherAppComingToTheFrontIsASignal() {
        assertTrue(signal("com.android.settings"))
    }

    @Test
    fun ourOwnOverlayIsNotASignal() {
        assertFalse(signal(own))
    }

    @Test
    fun aContentChangeIsNotASignal() {
        assertFalse(
            signal("com.android.systemui", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED),
        )
    }

    @Test
    fun anEventWithoutAPackageIsNotASignal() {
        assertFalse(signal(""))
    }
}
