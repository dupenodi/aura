package com.drishti

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.drishti.data.AuraPrefs
import com.drishti.overlay.BubbleService

/**
 * What runs when aura is the phone's assistant and someone holds the power button or swipes
 * up from a bottom corner. It has no screen of its own: it wakes the overlay, which lights
 * the edges and listens hands-free, and gets out of the way.
 *
 * It stays (invisibly) for a moment on purpose. Android 14 only lets a service start using
 * the microphone while the app is in front, and this activity is what puts it there.
 */
class AssistActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = AuraPrefs.get(this)
        if (!prefs.onboardingComplete || !Settings.canDrawOverlays(this)) {
            // Not set up yet: the app itself is the only useful place to go.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        if (prefs.paused.value) prefs.setPaused(false)
        BubbleService.start(this)
        BubbleService.listen(this)
        handler.postDelayed({ finish() }, HOLD_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val HOLD_MS = 900L
    }
}
