package com.clicky.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.clicky.MainActivity
import com.clicky.R
import com.clicky.accessibility.ClickyAccessibilityService
import com.clicky.agent.AgentLoop
import com.clicky.agent.FlowHistoryStore
import com.clicky.agent.RecipeStore
import com.clicky.debug.RingBufferLogger
import com.clicky.voice.VoiceCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Foreground overlay owner: Window A (bubble) + Window B (annotation canvas).
 * Canvas is always behind the bubble and NEVER intercepts touches.
 *
 * System Settings / OEM security may detach TYPE_APPLICATION_OVERLAY windows;
 * we detect that and re-attach while the sticky specialUse FGS stays alive.
 */
@AndroidEntryPoint
class OverlayService : Service() {

    @Inject lateinit var voiceCoordinator: VoiceCoordinator
    @Inject lateinit var agentLoop: AgentLoop
    @Inject lateinit var recipeStore: RecipeStore
    @Inject lateinit var flowHistoryStore: FlowHistoryStore

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var demoJob: Job? = null

    private lateinit var windowManager: WindowManager
    private var bubbleView: BubbleView? = null
    private var canvasView: AnnotationCanvasView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var canvasParams: WindowManager.LayoutParams? = null

    /** Every View we ever addView'd — used to purge duplicates. */
    private val managedViews = linkedSetOf<View>()

    /** True while we intentionally remove windows (stop / rebuild). */
    private var intentionallyDetaching: Boolean = false

    /** Re-entrancy guard: never nested addView / attach. */
    private var attaching: Boolean = false

    /** True while [forceResetOverlays] is clearing + re-adding. */
    private var resetting: Boolean = false

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            runCatching {
                if (attaching || intentionallyDetaching) return@runCatching
                if (hasDuplicateOverlays()) {
                    RingBufferLogger.log("overlay", "watchdog → duplicate purge + reset")
                    forceResetOverlays("watchdog duplicates")
                } else if (!windowsHealthy()) {
                    RingBufferLogger.log("overlay", "watchdog tick → reattach")
                    ensureWindowsAttached()
                }
            }
            mainHandler.postDelayed(this, WATCHDOG_MS)
        }
    }

    var mode: AgentMode = AgentMode.Coach
        private set

    var bubbleState: BubbleState = BubbleState.Idle
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runCatching {
            instance = this
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            ensureNotificationChannel()
            startAsForeground()
            ensureWindowsAttached()
            scheduleWatchdog()
            RingBufferLogger.log("overlay", "OverlayService.onCreate")
        }.onFailure {
            RingBufferLogger.log("overlay", "onCreate failed: ${it.message}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            // Re-assert FGS + windows on every start (OEM may have stripped overlays).
            startAsForeground()
            when (intent?.action) {
                ACTION_STOP -> {
                    stopSelfSafely()
                }
                ACTION_DEBUG_DEMO -> {
                    if (ClickyAccessibilityService.instance == null) {
                        ClickyAccessibilityService.showEnableToast()
                    }
                    ensureWindowsAttached()
                    runDebugDemo()
                }
                ACTION_REATTACH -> {
                    RingBufferLogger.log("overlay", "ACTION_REATTACH")
                    ensureWindowsAttached()
                }
                else -> {
                    ensureWindowsAttached()
                    if (ClickyAccessibilityService.instance == null) {
                        ClickyAccessibilityService.showEnableToast()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        RingBufferLogger.log("overlay", "onConfigurationChanged → reattach check")
        requestReattachIfNeeded("config change")
    }

    override fun onDestroy() {
        runCatching {
            mainHandler.removeCallbacks(watchdogRunnable)
            demoJob?.cancel()
            intentionallyDetaching = true
            detachWindows()
            scope.cancel()
            if (instance === this) {
                instance = null
            }
            RingBufferLogger.log("overlay", "OverlayService.onDestroy")
        }
        super.onDestroy()
    }

    // region Public API (used by debug + agent + voice)

    fun setListening(listening: Boolean) {
        mainHandler.post {
            runCatching {
                // Thinking/Processing take visual priority until cleared.
                if (
                    (bubbleState == BubbleState.Thinking || bubbleState == BubbleState.Processing) &&
                    !listening
                ) {
                    return@runCatching
                }
                bubbleState = if (listening) BubbleState.Listening else BubbleState.Idle
                bubbleView?.bubbleState = bubbleState
                if (!listening) {
                    bubbleView?.audioLevel = 0f
                }
            }
        }
    }

    /** Live mic amplitude 0..1 while listening (visual only). */
    fun setAudioLevel(level: Float) {
        mainHandler.post {
            runCatching {
                bubbleView?.audioLevel = level
            }
        }
    }

    fun setProcessing(processing: Boolean) {
        mainHandler.post {
            runCatching {
                bubbleState = when {
                    processing -> BubbleState.Processing
                    bubbleState == BubbleState.Thinking -> BubbleState.Thinking
                    else -> BubbleState.Idle
                }
                bubbleView?.bubbleState = bubbleState
            }
        }
    }

    fun setThinking(thinking: Boolean) {
        mainHandler.post {
            runCatching {
                bubbleState = when {
                    thinking -> BubbleState.Thinking
                    else -> BubbleState.Idle
                }
                bubbleView?.bubbleState = bubbleState
            }
        }
    }

    fun showAnnotation(annotation: Annotation) {
        mainHandler.post {
            runCatching {
                ensureWindowsAttached()
                canvasView?.addAnnotation(annotation)
            }
        }
    }

    fun clearAnnotations() {
        mainHandler.post {
            runCatching { canvasView?.clearAnnotations() }
        }
    }

    fun cursorShow() {
        mainHandler.post {
            runCatching {
                ensureWindowsAttached()
                canvasView?.cursor?.show()
                canvasView?.ensureTicking()
            }
        }
    }

    fun cursorHide() {
        mainHandler.post {
            runCatching { canvasView?.cursor?.hide() }
        }
    }

    fun cursorAnimateTo(x: Float, y: Float, durationMs: Long = 450L, onEnd: (() -> Unit)? = null) {
        mainHandler.post {
            runCatching {
                ensureWindowsAttached()
                canvasView?.cursor?.animateTo(x, y, durationMs, onEnd)
                canvasView?.ensureTicking()
            }
        }
    }

    fun cursorClickPulse() {
        mainHandler.post {
            runCatching {
                canvasView?.cursor?.clickPulse()
                canvasView?.ensureTicking()
            }
        }
    }

    fun setMode(newMode: AgentMode) {
        mainHandler.post {
            runCatching {
                mode = newMode
                RingBufferLogger.log("overlay", "mode=$newMode")
            }
        }
    }

    /**
     * Called when Accessibility reports a window change (e.g. Settings opened).
     * Debounced re-attach if the system stripped our overlays.
     */
    fun requestReattachIfNeeded(reason: String) {
        mainHandler.post {
            runCatching {
                if (attaching || intentionallyDetaching) return@runCatching
                if (hasDuplicateOverlays()) {
                    RingBufferLogger.log("overlay", "reattach → duplicate purge reason=$reason")
                    forceResetOverlays("reattach duplicates: $reason")
                    return@runCatching
                }
                if (windowsHealthy()) return@runCatching
                RingBufferLogger.log("overlay", "reattach needed reason=$reason")
                mainHandler.postDelayed({
                    runCatching {
                        if (attaching || intentionallyDetaching) return@runCatching
                        if (hasDuplicateOverlays()) {
                            forceResetOverlays("reattach delayed duplicates: $reason")
                            return@runCatching
                        }
                        if (!windowsHealthy()) {
                            RingBufferLogger.log("overlay", "reattach execute reason=$reason")
                            ensureWindowsAttached()
                        }
                    }
                }, REATTACH_DEBOUNCE_MS)
            }
        }
    }

    /**
     * Suspend until the cursor finishes animating to ([x], [y]).
     * Starts the overlay windows if needed. Returns false if canvas unavailable.
     */
    suspend fun animateCursorTo(x: Float, y: Float): Boolean {
        return withContext(Dispatchers.Main.immediate) {
            if (!ensureWindowsAttached()) return@withContext false
            val canvas = canvasView ?: return@withContext false
            val cursor = canvas.cursor
            cursor.show()
            val density = resources.displayMetrics.density
            val dur = CursorSprite.durationForDistance(cursor.x, cursor.y, x, y, density)
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                cursor.animateTo(x, y, dur) {
                    if (cont.isActive) cont.resume(true)
                }
                canvas.ensureTicking()
            }
        }
    }

    fun runDebugDemo() {
        demoJob?.cancel()
        demoJob = scope.launch {
            runCatching {
                // Do not hard-bail solely on canDrawOverlays(): some OEMs (MIUI/HyperOS,
                // ColorOS, OneUI) return stale/false negatives. Try attach; surface real errors.
                if (!ensureWindowsAttached()) {
                    toast(
                        if (!Settings.canDrawOverlays(this@OverlayService)) {
                            "Grant overlay permission for Clicky (com.clicky) first"
                        } else {
                            "Could not create overlay — check OEM display-over-apps / pop-up restrictions"
                        },
                    )
                    return@runCatching
                }
                val canvas = canvasView ?: return@runCatching
                val dm = resources.displayMetrics
                val w = dm.widthPixels.toFloat()
                val h = dm.heightPixels.toFloat()
                val m = 72f * dm.density

                clearAnnotations()
                canvas.cursor.setPosition(m, m)
                canvas.cursor.show(immediate = true)
                canvas.ensureTicking()
                setThinking(true)

                val corners = listOf(
                    m to m,
                    w - m to m,
                    w - m to h - m,
                    m to h - m,
                    m to m,
                )
                for (i in 1 until corners.size) {
                    val (tx, ty) = corners[i]
                    val (sx, sy) = corners[i - 1]
                    val dur = CursorSprite.durationForDistance(sx, sy, tx, ty, dm.density)
                    var done = false
                    canvas.cursor.animateTo(tx, ty, dur) { done = true }
                    canvas.ensureTicking()
                    val start = System.currentTimeMillis()
                    while (!done && System.currentTimeMillis() - start < dur + 200) {
                        delay(16)
                    }
                    canvas.cursor.clickPulse()
                    delay(280)
                }

                // One of each annotation type.
                val cx = w / 2f
                val cy = h / 2f
                showAnnotation(
                    Annotation.Circle(
                        rect = RectF(cx - 80f * dm.density, cy - 80f * dm.density,
                            cx + 80f * dm.density, cy + 80f * dm.density),
                        color = 0xFF2EC4B6.toInt(),
                        strokeWidth = 4f * dm.density,
                        label = "Circle",
                    ),
                )
                showAnnotation(
                    Annotation.Arrow(
                        fromX = m,
                        fromY = cy,
                        toX = cx - 100f * dm.density,
                        toY = cy - 40f * dm.density,
                        label = "Arrow",
                    ),
                )
                showAnnotation(
                    Annotation.Highlight(
                        rect = RectF(
                            cx - 40f * dm.density,
                            h - m - 90f * dm.density,
                            cx + 140f * dm.density,
                            h - m - 20f * dm.density,
                        ),
                    ),
                )
                showAnnotation(
                    Annotation.Callout(
                        x = w - m,
                        y = m + 40f * dm.density,
                        text = "Callout tip",
                    ),
                )
                showAnnotation(
                    Annotation.Ripple(
                        x = m + 40f * dm.density,
                        y = h - m,
                    ),
                )

                setThinking(false)
                setListening(true)
                delay(2_500)
                setListening(false)
                delay(4_000)
                canvas.cursor.hide()
                RingBufferLogger.log("overlay", "debug demo complete")
            }.onFailure {
                RingBufferLogger.log("overlay", "debug demo failed: ${it.message}")
            }
        }
    }

    // endregion

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.overlay_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.overlay_channel_description)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.overlay_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun scheduleWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_MS)
    }

    private fun windowsHealthy(): Boolean {
        val canvas = canvasView
        val bubble = bubbleView
        return canvas != null &&
            bubble != null &&
            canvas.isAttachedToWindow &&
            bubble.isAttachedToWindow &&
            managedViews.contains(canvas) &&
            managedViews.contains(bubble) &&
            !hasDuplicateOverlays()
    }

    private fun hasDuplicateOverlays(): Boolean {
        val bubbles = managedViews.count { it is BubbleView }
        val canvases = managedViews.count { it is AnnotationCanvasView }
        return bubbles > 1 || canvases > 1 || managedViews.size > 2
    }

    /**
     * @return true when both overlay windows are attached.
     *
     * OEM note: [Settings.canDrawOverlays] can lie (stale false after grant, or true when
     * MIUI "Display pop-up windows" / battery restrictions still block TYPE_APPLICATION_OVERLAY).
     * Prefer attempting WindowManager.addView and surfacing the real failure to the caller.
     *
     * CRITICAL: never addView a second bubble/canvas while one is already managed/attached.
     */
    private fun attachWindows(): Boolean {
        val apiSaysGranted = Settings.canDrawOverlays(this)
        if (!apiSaysGranted) {
            RingBufferLogger.log(
                "overlay",
                "canDrawOverlays=false; still attempting attach (OEM quirk workaround)",
            )
        }
        purgeStaleManagedViews()
        if (hasDuplicateOverlays() && !resetting) {
            forceResetOverlays("attachWindows duplicates")
            return windowsHealthy()
        }
        val canvasOk = attachCanvasWindow()
        val bubbleOk = attachBubbleWindow()
        assertCanvasNotTouchable()
        if (!canvasOk || !bubbleOk) {
            RingBufferLogger.log("overlay", "window attach failed canvas=$canvasOk bubble=$bubbleOk")
        } else {
            RingBufferLogger.log(
                "overlay",
                "windows attached ok managed=${managedViews.size} " +
                    "bubble=${bubbleParams?.width}x${bubbleParams?.height}",
            )
        }
        return canvasOk && bubbleOk
    }

    private fun ensureWindowsAttached(): Boolean {
        if (attaching || resetting) return windowsHealthy()
        if (windowsHealthy()) {
            assertCanvasNotTouchable()
            return true
        }
        if (hasDuplicateOverlays()) {
            RingBufferLogger.log("overlay", "ensureWindowsAttached: duplicates → force reset")
            forceResetOverlays("ensureWindowsAttached duplicates")
            return windowsHealthy()
        }
        RingBufferLogger.log(
            "overlay",
            "ensureWindowsAttached: unhealthy " +
                "canvas=${canvasView != null}/${canvasView?.isAttachedToWindow} " +
                "bubble=${bubbleView != null}/${bubbleView?.isAttachedToWindow} " +
                "managed=${managedViews.size}",
        )
        attaching = true
        return try {
            purgeStaleManagedViews()
            attachWindows()
        } finally {
            attaching = false
        }
    }

    /**
     * Remove views that are no longer attached — ALWAYS via WindowManager.remove first so we
     * never orphan a live overlay window by nulling the Kotlin ref alone (that caused duplicates).
     */
    private fun purgeStaleManagedViews() {
        val stale = managedViews.filter { !it.isAttachedToWindow }
        for (v in stale) {
            RingBufferLogger.log("overlay", "purging stale managed ${v.javaClass.simpleName}")
            removeManagedView(v)
        }
        val canvas = canvasView
        if (canvas != null && (!managedViews.contains(canvas) || !canvas.isAttachedToWindow)) {
            removeManagedView(canvas)
            canvasView = null
            canvasParams = null
        }
        val bubble = bubbleView
        if (bubble != null && (!managedViews.contains(bubble) || !bubble.isAttachedToWindow)) {
            removeManagedView(bubble)
            bubbleView = null
            bubbleParams = null
        }
    }

    private fun removeManagedView(view: View?) {
        if (view == null) return
        runCatching { windowManager.removeViewImmediate(view) }
        managedViews.remove(view)
        if (canvasView === view) {
            canvasView = null
            canvasParams = null
        }
        if (bubbleView === view) {
            bubbleView = null
            bubbleParams = null
        }
    }

    /** Nuclear recovery: strip every Clicky overlay we manage, then add exactly one pair. */
    private fun forceResetOverlays(reason: String) {
        if (resetting) return
        RingBufferLogger.log("overlay", "forceResetOverlays reason=$reason")
        resetting = true
        intentionallyDetaching = true
        attaching = true
        try {
            val snapshot = managedViews.toList()
            for (v in snapshot) {
                runCatching { windowManager.removeViewImmediate(v) }
            }
            managedViews.clear()
            bubbleView = null
            canvasView = null
            bubbleParams = null
            canvasParams = null
        } finally {
            intentionallyDetaching = false
            attaching = false
        }
        // Re-add exactly one pair (do not call ensureWindowsAttached — attaching guard races).
        attaching = true
        try {
            attachWindows()
        } finally {
            attaching = false
            resetting = false
        }
    }

    private fun assertCanvasNotTouchable() {
        val p = canvasParams ?: return
        val canvas = canvasView ?: return
        var dirty = false
        val need =
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (p.flags and need != need) {
            RingBufferLogger.log("overlay", "canvas flags drifted — restoring NOT_TOUCHABLE")
            p.flags = p.flags or need
            dirty = true
        }
        // Keep canvas sized to the real display so annotations stay aligned with a11y bounds.
        val (sw, sh) = OverlayScreen.sizePx(this)
        if (p.width != sw || p.height != sh || p.x != 0 || p.y != 0) {
            RingBufferLogger.log("overlay", "canvas resize ${p.width}x${p.height}@${p.x},${p.y} → ${sw}x${sh}@0,0")
            p.width = sw
            p.height = sh
            p.x = 0
            p.y = 0
            dirty = true
        }
        if (dirty && canvas.isAttachedToWindow) {
            runCatching { windowManager.updateViewLayout(canvas, p) }
        }
    }

    private fun installDetachWatcher(view: View, label: String) {
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                RingBufferLogger.log("overlay", "$label attached")
            }

            override fun onViewDetachedFromWindow(v: View) {
                RingBufferLogger.log(
                    "overlay",
                    "$label detached intentional=$intentionallyDetaching",
                )
                if (intentionallyDetaching) return
                // System/OEM stripped the overlay. Ensure WM removal + drop refs (no orphan).
                when (label) {
                    "canvas" -> {
                        if (canvasView === v) {
                            removeManagedView(v)
                        } else {
                            managedViews.remove(v)
                        }
                    }
                    "bubble" -> {
                        if (bubbleView === v) {
                            removeManagedView(v)
                        } else {
                            managedViews.remove(v)
                        }
                    }
                }
                requestReattachIfNeeded("$label detached by system")
            }
        })
    }

    /** @return true if the canvas is attached (already was, or newly added). */
    private fun attachCanvasWindow(): Boolean {
        val existing = canvasView
        if (existing != null && existing.isAttachedToWindow && managedViews.contains(existing)) {
            assertCanvasNotTouchable()
            return true
        }
        // Tear down any prior canvas instance before addView (single-instance guard).
        managedViews.filterIsInstance<AnnotationCanvasView>().toList().forEach { removeManagedView(it) }
        canvasView = null
        canvasParams = null

        return runCatching {
            val view = AnnotationCanvasView(this)
            // HARD RULE — canvas overlay MUST never intercept touches:
            // FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE (asserted here).
            val flags = (
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                )
            check(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0) {
                "Annotation canvas must be FLAG_NOT_TOUCHABLE"
            }
            check(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) {
                "Annotation canvas must be FLAG_NOT_FOCUSABLE"
            }
            // Exact full-display pixels (not MATCH_PARENT) so a11y screen coords map 1:1.
            // MATCH_PARENT can sit below the status bar / miss cutouts on some OEMs → offset highlights.
            val (sw, sh) = OverlayScreen.sizePx(this)
            val params = WindowManager.LayoutParams(
                sw,
                sh,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                title = WINDOW_TITLE_CANVAS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            installDetachWatcher(view, "canvas")
            // Skip if somehow already parented.
            if (view.isAttachedToWindow) {
                runCatching { windowManager.removeViewImmediate(view) }
            }
            windowManager.addView(view, params)
            managedViews.add(view)
            canvasView = view
            canvasParams = params
            RingBufferLogger.log("overlay", "canvas attached ${sw}x${sh} (screen coords 1:1)")
            true
        }.getOrElse {
            RingBufferLogger.log("overlay", "attachCanvas failed: ${it.message}")
            false
        }
    }

    /** @return true if the bubble is attached (already was, or newly added). */
    private fun attachBubbleWindow(): Boolean {
        val existing = bubbleView
        if (existing != null && existing.isAttachedToWindow && managedViews.contains(existing)) {
            // Keep bubble small — never let layout drift to MATCH_PARENT.
            val p = bubbleParams
            val size = existing.preferredSizePx()
            if (p != null && (p.width != size || p.height != size)) {
                p.width = size
                p.height = size
                runCatching { windowManager.updateViewLayout(existing, p) }
            }
            return true
        }
        // Preserve last known position across recreate when possible.
        val prevX = bubbleParams?.x
        val prevY = bubbleParams?.y
        managedViews.filterIsInstance<BubbleView>().toList().forEach { removeManagedView(it) }
        bubbleView = null
        bubbleParams = null

        return runCatching {
            val view = BubbleView(this)
            val size = view.preferredSizePx()
            val params = WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                width = size
                height = size
                x = prevX ?: 0
                y = prevY ?: (resources.displayMetrics.heightPixels * 0.35f).toInt()
                title = WINDOW_TITLE_BUBBLE
            }
            view.onToggleListening = {
                runCatching {
                    voiceCoordinator.onBubbleTap()
                }.onFailure {
                    RingBufferLogger.log("overlay", "bubble tap failed: ${it.message}")
                }
            }
            view.onLongPressMenu = { showBubbleMenu(view) }
            // Drag: ONLY update LayoutParams.x/y — never create windows.
            view.onDrag = { dx, dy ->
                runCatching {
                    val p = bubbleParams ?: return@runCatching
                    if (bubbleView !== view || !view.isAttachedToWindow) return@runCatching
                    p.x = (p.x + dx.toInt())
                    p.y = (p.y + dy.toInt())
                    p.width = size
                    p.height = size
                    windowManager.updateViewLayout(view, p)
                }
            }
            view.onDragEnd = {
                runCatching {
                    val p = bubbleParams ?: return@runCatching
                    if (bubbleView !== view || !view.isAttachedToWindow) return@runCatching
                    val dm = resources.displayMetrics
                    BubbleView.snapToNearestEdge(p, size, dm.widthPixels, dm.heightPixels)
                    p.width = size
                    p.height = size
                    windowManager.updateViewLayout(view, p)
                }
            }
            installDetachWatcher(view, "bubble")
            if (view.isAttachedToWindow) {
                runCatching { windowManager.removeViewImmediate(view) }
            }
            // Add AFTER canvas so bubble stays above in z-order.
            windowManager.addView(view, params)
            managedViews.add(view)
            bubbleView = view
            bubbleParams = params
            view.bubbleState = bubbleState
            true
        }.getOrElse {
            RingBufferLogger.log("overlay", "attachBubble failed: ${it.message}")
            false
        }
    }

    private fun showBubbleMenu(anchor: BubbleView) {
        runCatching {
            // Temporarily allow focus for PopupMenu; restore after.
            val p = bubbleParams ?: return@runCatching
            val previousFlags = p.flags
            p.flags = previousFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            windowManager.updateViewLayout(anchor, p)

            val popup = PopupMenu(this, anchor)
            popup.menu.add(0, 1, 0, "Mode: Coach").isChecked = mode == AgentMode.Coach
            popup.menu.add(0, 2, 1, "Mode: Pilot").isChecked = mode == AgentMode.Pilot
            popup.menu.add(0, 4, 2, "Open Clicky home")
            val lastFlow = flowHistoryStore.latest()
            val lastLabel = lastFlow?.userUtterance?.take(28)?.let { "Last flow: $it" }
                ?: "Last flow"
            popup.menu.add(0, 5, 3, lastLabel)
            popup.menu.add(0, 6, 4, "Replay last learned flow")
            popup.menu.add(0, 3, 5, "Stop Clicky overlay")
            popup.menu.setGroupCheckable(0, true, true)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        mode = AgentMode.Coach
                        toast("Coach mode")
                        true
                    }
                    2 -> {
                        mode = AgentMode.Pilot
                        toast("Pilot mode")
                        true
                    }
                    3 -> {
                        stopSelfSafely()
                        true
                    }
                    4 -> {
                        openHome(showLastFlow = false)
                        true
                    }
                    5 -> {
                        openHome(showLastFlow = true)
                        true
                    }
                    6 -> {
                        // Menu dismiss touch can fall through to the bubble and cancel replay.
                        voiceCoordinator.suppressCancelBriefly(1_200L)
                        replayLastLearnedFlow()
                        true
                    }
                    else -> false
                }
            }
            popup.setOnDismissListener {
                runCatching {
                    p.flags = previousFlags
                    if (bubbleView != null && bubbleView!!.isAttachedToWindow) {
                        windowManager.updateViewLayout(anchor, p)
                    }
                }
            }
            popup.show()
        }.onFailure {
            RingBufferLogger.log("overlay", "menu failed: ${it.message}")
        }
    }

    private fun detachWindows() {
        intentionallyDetaching = true
        try {
            val snapshot = managedViews.toList()
            for (v in snapshot) {
                runCatching { windowManager.removeViewImmediate(v) }
            }
            managedViews.clear()
            // Belt-and-suspenders for any refs that slipped out of managedViews.
            runCatching { bubbleView?.let { windowManager.removeViewImmediate(it) } }
            runCatching { canvasView?.let { windowManager.removeViewImmediate(it) } }
            bubbleView = null
            canvasView = null
            bubbleParams = null
            canvasParams = null
        } finally {
            intentionallyDetaching = false
        }
    }

    private fun stopSelfSafely() {
        runCatching {
            demoJob?.cancel()
            runCatching { voiceCoordinator.cancelAll("overlay stop") }
            detachWindows()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun openHome(showLastFlow: Boolean) {
        runCatching {
            val i = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (showLastFlow) {
                    putExtra(MainActivity.EXTRA_SHOW_LAST_FLOW, true)
                }
            }
            startActivity(i)
        }.onFailure {
            RingBufferLogger.log("overlay", "open home failed: ${it.message}")
        }
    }

    private fun replayLastLearnedFlow() {
        val recipe = recipeStore.listAll().firstOrNull()
        if (recipe == null) {
            RingBufferLogger.log("overlay", "replay skipped — no learned flows")
            toast("No learned flows yet — complete or save a flow first")
            return
        }
        if (agentLoop.isBusy) {
            RingBufferLogger.log("overlay", "replay skipped — agent busy")
            toast("Agent busy — cancel first")
            return
        }
        // Menu dismiss can fall through to the bubble; suppress cancel + stop listen only.
        voiceCoordinator.suppressCancelBriefly(1_200L)
        voiceCoordinator.stopListeningSession("prepare replay")
        RingBufferLogger.log(
            "overlay",
            "replay start key=${recipe.key} steps=${recipe.steps.size} " +
                "label=${recipe.intentLabel}",
        )
        toast("Replaying ${recipe.intentLabel}")
        if (!agentLoop.launchReplay(recipe)) {
            RingBufferLogger.log("overlay", "launchReplay refused (busy race)")
            toast("Agent busy — cancel first")
        }
    }

    private fun toast(msg: String) {
        mainHandler.post {
            runCatching {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val ACTION_START = "com.clicky.overlay.START"
        const val ACTION_STOP = "com.clicky.overlay.STOP"
        const val ACTION_DEBUG_DEMO = "com.clicky.overlay.DEBUG_DEMO"
        const val ACTION_REATTACH = "com.clicky.overlay.REATTACH"

        private const val CHANNEL_ID = "clicky_overlay"
        private const val NOTIFICATION_ID = 42
        private const val REATTACH_DEBOUNCE_MS = 280L
        private const val WATCHDOG_MS = 2_000L
        private const val WINDOW_TITLE_CANVAS = "ClickyAnnotations"
        private const val WINDOW_TITLE_BUBBLE = "ClickyBubble"

        @Volatile
        var instance: OverlayService? = null
            private set

        fun start(context: Context) {
            val i = Intent(context, OverlayService::class.java).setAction(ACTION_START)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, OverlayService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }

        fun startDebugDemo(context: Context) {
            val i = Intent(context, OverlayService::class.java).setAction(ACTION_DEBUG_DEMO)
            context.startForegroundService(i)
        }

        fun requestReattach(context: Context, reason: String) {
            instance?.requestReattachIfNeeded(reason)
                ?: runCatching {
                    val i = Intent(context, OverlayService::class.java).setAction(ACTION_REATTACH)
                    context.startForegroundService(i)
                }
        }
    }
}
