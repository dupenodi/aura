package com.drishti.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.drishti.R
import com.drishti.accessibility.model.UiNode
import com.drishti.debug.RingBufferLogger
import com.drishti.overlay.OverlayService
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton AccessibilityService. Other components talk to Drishti only through [instance].
 * Every callback is wrapped in [runCatching] — this service must never crash.
 */
class DrishtiAccessibilityService : AccessibilityService() {

    val screenReader: ScreenReader by lazy { ScreenReader { instance } }
    val screenCapturer: ScreenCapturer by lazy { ScreenCapturer { instance } }
    val gestureExecutor: GestureExecutor by lazy { GestureExecutor { instance } }

    /** Stable id → WeakReference from the last [ScreenReader.captureContext] call. */
    val nodeCache: ConcurrentHashMap<Int, WeakReference<AccessibilityNodeInfo>> =
        ConcurrentHashMap()

    /**
     * Lightweight copy of the last capture (bounds/labels) so tap_node can fall back
     * when a WeakReference is dead, and prefer smaller text/button targets.
     */
    @Volatile
    var lastNodes: List<UiNode> = emptyList()
        private set

    /** Foreground package from the last successful capture (for open_app short-circuit). */
    @Volatile
    var lastPackageName: String = ""
        private set

    /** Compact signature for "did the screen change after tap?" checks. */
    @Volatile
    var lastScreenSignature: String = ""
        private set

    private var lastReattachPkg: String? = null
    private var lastReattachAtMs: Long = 0L

    fun updateNodeCache(nodes: List<UiNode>, packageName: String = "", signature: String = "") {
        runCatching {
            // Drop previous retained nodes.
            nodeCache.values.forEach { ref ->
                runCatching { ref.get()?.recycle() }
            }
            nodeCache.clear()
            for (n in nodes) {
                nodeCache[n.id] = n.nodeRef
            }
            lastNodes = nodes
            if (packageName.isNotBlank()) lastPackageName = packageName
            if (signature.isNotBlank()) lastScreenSignature = signature
        }
    }

    override fun onServiceConnected() {
        runCatching {
            super.onServiceConnected()
            instance = this
            RingBufferLogger.log("a11y", "onServiceConnected")
            // Accessibility coming online is a good moment to restore overlays.
            OverlayService.instance?.requestReattachIfNeeded("a11y connected")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        runCatching {
            if (event == null) return@runCatching
            val type = event.eventType
            // Settings / system UI often strip TYPE_APPLICATION_OVERLAY; re-attach after
            // window transitions instead of flooding the ring buffer with every event.
            if (
                type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            ) {
                val pkg = event.packageName?.toString().orEmpty()
                val now = System.currentTimeMillis()
                if (pkg != lastReattachPkg || now - lastReattachAtMs > 800L) {
                    lastReattachPkg = pkg
                    lastReattachAtMs = now
                    OverlayService.instance?.requestReattachIfNeeded("a11y window pkg=$pkg")
                }
            }
        }
    }

    override fun onInterrupt() {
        runCatching {
            RingBufferLogger.log("a11y", "onInterrupt")
        }
    }

    override fun onDestroy() {
        runCatching {
            RingBufferLogger.log("a11y", "onDestroy")
            nodeCache.values.forEach { ref ->
                runCatching { ref.get()?.recycle() }
            }
            nodeCache.clear()
            lastNodes = emptyList()
            lastPackageName = ""
            lastScreenSignature = ""
            if (instance === this) {
                instance = null
            }
            super.onDestroy()
        }
    }

    companion object {
        @Volatile
        var instance: DrishtiAccessibilityService? = null
            private set

        private val mainHandler = Handler(Looper.getMainLooper())

        fun showEnableToast() {
            runCatching {
                val ctx = instance?.applicationContext
                    ?: com.drishti.DrishtiApp.appContextOrNull()
                    ?: return
                mainHandler.post {
                    runCatching {
                        Toast.makeText(
                            ctx,
                            ctx.getString(R.string.enable_accessibility_toast),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }
}
