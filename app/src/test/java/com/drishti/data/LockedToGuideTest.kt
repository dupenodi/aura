package com.drishti.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Guide-only lock is a promise made on the privacy screen, so it is worth pinning
 * down: these packages must never be automatable, whatever the settings say.
 */
class LockedToGuideTest {

    @Test
    fun `banking apps are locked`() {
        listOf(
            "com.monzo.android",
            "com.chase.sig.android",
            "net.one97.paytm",
            "com.phonepe.app",
            "com.google.android.apps.walletnfcrel",
            "com.revolut.revolut",
        ).forEach { assertTrue("$it should be locked", LockedToGuide.isLocked(it)) }
    }

    @Test
    fun `health and password apps are locked`() {
        listOf(
            "com.google.android.apps.health",
            "com.bitwarden.authenticator",
            "com.agilebits.onepassword",
            "org.thoughtcrime.passwordvault",
        ).forEach { assertTrue("$it should be locked", LockedToGuide.isLocked(it)) }
    }

    @Test
    fun `everyday apps are not locked`() {
        listOf(
            "com.whatsapp",
            "org.telegram.messenger",
            "com.android.settings",
            "com.rapido.passenger",
            "com.ubercab",
        ).forEach { assertFalse("$it should not be locked", LockedToGuide.isLocked(it)) }
    }

    @Test
    fun `matching is case insensitive`() {
        assertTrue(LockedToGuide.isLocked("com.MyBANK.App"))
    }
}
