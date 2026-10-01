package com.drishti.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.drishti.MainActivity
import com.drishti.R
import com.drishti.agent.DeviceContext
import com.drishti.agent.GuideRunner
import com.drishti.core.agent.Action
import com.drishti.core.agent.Direction
import com.drishti.core.agent.GuideUi
import com.drishti.core.agent.ShownStep
import com.drishti.data.AuraPrefs
import com.drishti.voice.AuraVoice
import com.drishti.voice.HoldToTalk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Aura's presence over other apps, and everything it says.
 *
 * Three pieces, none of which covers the app being used:
 * - the **handle**, a sliver of aura light against the screen edge: tap to type, hold to
 *   talk, drag along the edge to move it. Mid-session a tap stops;
 * - the **edge glow**, light around the whole screen while aura listens, works or guides;
 * - the **dock**, one wide card above the navigation bar carrying the current instruction.
 *   It moves to the top when the control it points at is down there.
 *
 * Aura can also be summoned as the phone's assistant (hold power, or swipe up from a bottom
 * corner): it listens hands-free and sends when they pause.
 */
class BubbleService : Service() {

    /** UI work (views / WindowManager) stays on Main. */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Agent / voice work runs off Main to avoid ANRs. */
    private val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var wm: WindowManager
    private lateinit var prefs: AuraPrefs
    private lateinit var runner: GuideRunner
    private lateinit var voiceOut: AuraVoice
    private lateinit var glow: GlowWindow

    private var handleView: EdgeHandleView? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var handleOnRight = true
    private var composerView: View? = null

    private var voice: HoldToTalk? = null
    private var dockView: DockView? = null
    private var dockParams: WindowManager.LayoutParams? = null
    private var dockHide: Runnable? = null
    private var dockAnimator: ValueAnimator? = null

    /** The ringed target, in screen pixels: the dock keeps out of its way. */
    private var dockAvoid: Rect? = null

    /** True while a guidance session is on, which turns a tap on the handle into Stop. */
    private var running = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * The ring and swipe hint. Made through the accessibility service when it is on, so it
     * doesn't count against the touch-through opacity limit (see [OverlayHost]).
     */
    private var pointerOverlay: PointerOverlay? = null
    private var pointerHost: Context? = null

    private fun pointer(): PointerOverlay {
        val host = OverlayHost.pick(this)
        pointerOverlay?.let { if (pointerHost === host) return it }
        pointerOverlay?.detach()
        return PointerOverlay(host).also {
            pointerOverlay = it
            pointerHost = host
        }
    }

    /**
     * What a guided session shows. Every call can come from a background coroutine, so each
     * one hops to the main thread before touching a window.
     */
    private val guideUi = object : GuideUi {
        override fun thinking(text: String?) = mainHandler.post {
            if (!running) return@post
            glow.mode = EdgeGlowView.Mode.Working
            sayHelping(text ?: "…", 0)
        }.let { }

        override fun show(step: ShownStep) = mainHandler.post {
            if (!running) return@post
            val bounds = step.bounds
            // The dock carries the words; the ring only has to say where.
            dockAvoid = null
            when {
                bounds != null -> {
                    val rect = Rect(bounds.l, bounds.t, bounds.r, bounds.b)
                    dockAvoid = rect
                    pointer().showTargetAt(rect, null, null)
                }
                // To see more below, the finger moves up the screen.
                step.action == Action.Scroll -> pointer().showSwipe(up = step.direction != Direction.Up, label = null)
                else -> pointer().hide()
            }
            glow.mode = EdgeGlowView.Mode.Guiding
            val line = if (step.action == Action.Type && !step.text.isNullOrBlank()) {
                "${step.instruction}\n“${step.text}”"
            } else {
                step.instruction
            }
            sayHelping(line, 0)
        }.let { }

        override fun emphasize() = mainHandler.post { pointer().emphasize() }.let { }

        override fun hide() = mainHandler.post {
            pointer().hide()
            dockAvoid = null
        }.let { }

        override fun paused(message: String) = mainHandler.post {
            pointer().hide()
            dockAvoid = null
            glow.mode = EdgeGlowView.Mode.Guiding
            say(
                text = message,
                durationMs = 0,
                tag = "paused",
                chips = listOf(
                    BubbleChip("carry on", primary = true) { runner.resume() },
                    BubbleChip("stop", primary = false) { stopRun() },
                ),
            )
        }.let { }

        override fun finished(message: String, success: Boolean) = mainHandler.post {
            pointer().hide()
            dockAvoid = null
            running = false
            handleView?.busy = false
            if (!listening) glow.mode = EdgeGlowView.Mode.Off
            if (message.isNotBlank()) say(message, 6000) else say("", 0)
        }.let { }
    }

    /** Cleared on destroy so a pending long-press can't fire after teardown. */
    private var beginHoldRunnable: Runnable? = null

    /** Battery saver degrades the glow before it degrades the help. */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshPowerState()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = AuraPrefs.get(this)
        glow = GlowWindow(this)
        voiceOut = AuraVoice(applicationContext)
        runner = GuideRunner(applicationContext, guideUi, voiceOut, agentScope)
        runner.onRunStateChanged = { isRunning ->
            mainHandler.post {
                running = isRunning
                handleView?.busy = isRunning
            }
        }

        // Required within the FGS start timeout even if we immediately stopSelf.
        startAsForeground()

        if (prefs.paused.value) {
            stopSelf()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "aura needs permission to show over other apps", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        showHandle()
        observePrefs()
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refreshPowerState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Match Privacy → Pause: hide everything and stay paused until they unpause.
                prefs.setPaused(true)
                if (running) stopRun()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_RUN -> mainHandler.post {
                if (running) stopRun()
            }
            ACTION_COMPOSE -> mainHandler.post {
                if (!isOperable()) return@post
                // Asking mid-run replaces the session — stop first so Stop isn't orphaned.
                if (running) stopRun()
                showComposer()
            }
            ACTION_LISTEN -> mainHandler.post {
                if (!isOperable()) return@post
                if (handleView == null) showHandle()
                if (listening) return@post
                if (running) stopRun()
                hideComposer()
                beginListening(handsFree = true)
            }
            ACTION_RUN_TASK -> intent.getStringExtra(EXTRA_TASK)?.let { task ->
                mainHandler.post {
                    if (isOperable()) startTask(task)
                }
            }
            ACTION_SHOW -> mainHandler.post {
                if (isOperable() && handleView == null) showHandle()
            }
        }
        return START_STICKY
    }

    /** Paused or missing overlay — refuse to show UI or start work. */
    private fun isOperable(): Boolean =
        !prefs.paused.value && Settings.canDrawOverlays(this)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation or a new display size: keep the handle on its edge and the dock in reach.
        placeHandle()
        placeDock()
    }

    override fun onDestroy() {
        beginHoldRunnable?.let { mainHandler.removeCallbacks(it) }
        beginHoldRunnable = null
        hideComposer()
        // Tear down voice quietly — don't post a dock update after the window is gone.
        voice?.cancel()
        listening = false
        runner.cancel()
        voiceOut.shutdown()
        removeDock()
        handleView?.let { runCatching { wm.removeView(it) } }
        handleView = null
        glow.remove()
        pointerOverlay?.detach()
        runCatching { unregisterReceiver(batteryReceiver) }
        uiScope.cancel()
        agentScope.cancel()
        super.onDestroy()
    }

    /** Pausing from the app or the notification takes the overlay down at once. */
    private fun observePrefs() {
        uiScope.launch {
            prefs.paused.collect { paused -> if (paused) stopSelf() }
        }
    }

    private fun refreshPowerState() {
        val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager ?: return
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        handleView?.lowPower = level in 1..15
    }

    private var notification: Notification? = null

    /**
     * Adds (or drops) the microphone service type for the length of a hold-to-talk.
     * Returns false if the system won't allow it, in which case the phone's own recogniser
     * — which records in another process — is used instead.
     */
    private fun foregroundForMic(on: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val n = notification ?: return false
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            (if (on) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        return runCatching { startForeground(NOTIF_ID, n, type) }
            .onFailure { android.util.Log.w("BubbleService", "microphone service type refused: ${it.message}") }
            .isSuccess
    }

    private fun startAsForeground() {
        val channelId = "aura_orb"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    getString(R.string.overlay_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.overlay_channel_description) },
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .addAction(0, "pause aura", stop)
            .setOngoing(true)
            .build()
        this.notification = notification

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ---- The handle -------------------------------------------------------------

    private fun handleWidth() = dp(28)
    private fun handleHeight() = dp(112)

    private fun showHandle() {
        val view = EdgeHandleView(this)

        // Return to where the user last left it; otherwise rest on the right edge, a little
        // below the middle, where a right thumb already is.
        handleOnRight = prefs.orbX.let { it < 0 || it > screenWidth() / 2 }
        view.onRight = handleOnRight
        val params = absoluteParams(handleWidth(), handleHeight()).apply {
            y = prefs.orbY.takeIf { it >= 0 } ?: (realHeight() * 0.58f).toInt()
        }

        // On gesture navigation a swipe in from the edge is Back. Keep that gesture off the
        // handle itself (Android allows up to 200dp of each edge to be excluded).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                v.systemGestureExclusionRects = listOf(Rect(0, 0, v.width, v.height))
            }
        }

        var downX = 0f
        var downY = 0f
        var startY = 0
        var moved = false
        var holding = false
        // The platform's own slop and long-press timeout, so the handle feels like every
        // other control on the phone rather than something with its own idea of a press.
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        val holdTimeout = android.view.ViewConfiguration.getLongPressTimeout().toLong()

        beginHoldRunnable?.let { mainHandler.removeCallbacks(it) }
        val beginHold = Runnable {
            holding = true
            beginListening(handsFree = false)
        }
        beginHoldRunnable = beginHold

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startY = params.y
                    moved = false
                    holding = false
                    view.touched = true
                    // Mid-session the handle is Stop — don't let long-press steal it for voice.
                    if (!running) mainHandler.postDelayed(beginHold, holdTimeout)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    val past = kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop
                    if (!moved && past) {
                        moved = true
                        mainHandler.removeCallbacks(beginHold)
                        // Drag after hold cancels the recording — don't send a half-said ask.
                        if (holding) {
                            holding = false
                            cancelListening()
                        }
                    }
                    if (moved) {
                        // Slides along the edge; carried past the middle, it changes sides.
                        params.y = startY + dy
                        val right = event.rawX > screenWidth() / 2f
                        if (right != handleOnRight) {
                            handleOnRight = right
                            view.onRight = right
                        }
                        placeHandle()
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(beginHold)
                    view.touched = false
                    when {
                        holding -> finishListening()
                        moved -> {
                            prefs.orbX = if (handleOnRight) screenWidth() else 0
                            prefs.orbY = params.y
                        }
                        // A paused session picks up again from the handle they were told to tap.
                        running && runner.isPaused -> runner.resume()
                        // Mid-session the handle is the way out, the one control they have
                        // been seeing all along. The dock offers stop as well.
                        running -> stopRun()
                        // Hands-free listening: a tap is "i'm done".
                        listening -> finishListening()
                        else -> showComposer()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(beginHold)
                    view.touched = false
                    if (holding) {
                        holding = false
                        cancelListening()
                    }
                    true
                }

                else -> false
            }
        }

        // Only keep the reference if the window actually attached — otherwise a failed
        // addView would permanently block retries (handleView != null).
        if (runCatching { wm.addView(view, params) }.isFailure) return
        handleView = view
        handleParams = params
        placeHandle()
    }

    /** Flush against its edge, kept clear of the status bar and the navigation bar. */
    private fun placeHandle() {
        val view = handleView ?: return
        val params = handleParams ?: return
        val h = handleHeight()
        params.x = if (handleOnRight) screenWidth() - handleWidth() else 0
        val top = statusBarHeight() + dp(8)
        val bottom = (realHeight() - navBarHeight() - h - dp(8)).coerceAtLeast(top)
        params.y = params.y.coerceIn(top, bottom)
        runCatching { wm.updateViewLayout(view, params) }
    }

    // ---- Voice ------------------------------------------------------------------

    private var listening = false
    private var handsFree = false

    /**
     * Hold the handle: record while the finger is down, send on release. Or [handsFree]
     * (summoned as the assistant): send when they pause, or when they tap "done".
     *
     * The transcript streams into the dock as it is recognised so they can see it being
     * heard — the single thing that makes voice input feel trustworthy.
     */
    private fun beginListening(handsFree: Boolean) {
        if (listening) return

        // Sarvam needs our own process to record; if the system won't grant that right now,
        // the phone's recogniser (recording in its own process) still works.
        val provider = prefs.speechProvider.value.takeIf { it != com.drishti.voice.SpeechProvider.Sarvam || foregroundForMic(true) }
            ?: com.drishti.voice.SpeechProvider.OnDevice
        val session = HoldToTalk.create(this, agentScope, provider) { keyterms() }
        if (!session.hasPermission()) {
            foregroundForMic(false)
            say("i need the microphone to hear you — opening aura so you can allow it", 5000)
            openMicPermission()
            return
        }

        listening = true
        this.handsFree = handsFree
        voice = session
        handleView?.listening = true
        glow.mode = EdgeGlowView.Mode.Listening
        dockAvoid = null
        voiceOut.stop()
        // Open the model connection while they talk, so the first step isn't waiting on TLS.
        runner.warmUp()
        val prompt = if (handsFree) "listening… ask for anything" else "listening… let go when you're done"
        say(prompt, 30_000, tag = "listening", chips = listeningChips())

        session.start(
            // Null: work the language out from what they say.
            languageTag = if (prefs.autoLanguage.value) null else prefs.language.value.tag,
            onPartial = { partial ->
                mainHandler.post {
                    if (listening) say("“$partial”", 30_000, "listening", listeningChips())
                }
            },
            onFinal = { text, tag ->
                mainHandler.post {
                    endListening()
                    if (text.isNotBlank()) {
                        startTask(text, tag?.let { com.drishti.core.agent.Language.fromTag(it) })
                    } else {
                        say("i didn't catch that", 2500)
                    }
                }
            },
            onFailure = { reason ->
                mainHandler.post {
                    endListening()
                    say(
                        when (reason) {
                            HoldToTalk.Failure.NoPermission ->
                                "i need the microphone to hear you"
                            HoldToTalk.Failure.Unavailable ->
                                "voice isn't available on this phone — tap the glow at the edge to type instead"
                            HoldToTalk.Failure.NoSpeech ->
                                if (handsFree) "i didn't hear anything — call me again when you're ready"
                                else "i didn't catch that — hold the glow at the edge and try again"
                            HoldToTalk.Failure.Error ->
                                "something went wrong listening — tap the glow at the edge to type instead"
                        },
                        3500,
                    )
                }
            },
            handsFree = handsFree,
            onLevel = { level -> mainHandler.post { if (listening) glow.level(level) } },
        )
    }

    /** Hands-free there is no finger to lift, so offer the same choices as chips. */
    private fun listeningChips(): List<BubbleChip> =
        if (!handsFree) {
            emptyList()
        } else {
            listOf(
                BubbleChip("done", primary = true) { finishListening() },
                BubbleChip("cancel", primary = false) { cancelListening() },
            )
        }

    /** Finger lifted (or "done"): stop recording; the final transcript arrives via the callback. */
    private fun finishListening() {
        if (!listening) return
        handleView?.listening = false
        glow.mode = EdgeGlowView.Mode.Working
        say("one moment…", 30_000, tag = "helping", chips = emptyList())
        voice?.stop()
    }

    private fun cancelListening() {
        if (!listening) return
        voice?.cancel()
        endListening()
        say("", 0)
    }

    private fun endListening() {
        listening = false
        handsFree = false
        voice = null
        handleView?.listening = false
        glow.level(0f)
        glow.mode = if (running) EdgeGlowView.Mode.Working else EdgeGlowView.Mode.Off
        foregroundForMic(false)
    }

    /**
     * Words to bias speech recognition towards: the names of their apps, and the setting
     * names people ask about. "WhatsApp" said in a Hindi sentence should come back as
     * WhatsApp, not वाट्सएप or "what's up".
     */
    private fun keyterms(): List<String> =
        (COMMON_TERMS + DeviceContext.installedApps(this).map { it.label }).distinct().take(50)

    /** Microphone is granted in the app, so send the user there with an explanation. */
    private fun openMicPermission() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("request_mic", true),
            )
        }
    }

    // ---- Composer ---------------------------------------------------------------

    /**
     * The type-a-task sheet.
     *
     * It is a real modal: a full-screen scrim catches outside taps so the sheet can never
     * be left stranded over another app, and the window takes focus so the keyboard
     * actually opens — an overlay window that cannot be typed into is worse than none.
     */
    private fun showComposer() {
        if (!isOperable()) return
        if (composerView != null) {
            hideComposer()
            return
        }

        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            setOnClickListener { hideComposer() }
            // TYPE_APPLICATION_OVERLAY windows don't honour SOFT_INPUT_ADJUST_RESIZE, so
            // lift the sheet by the keyboard's real height instead of hoping it resizes.
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
                val nav = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.navigationBars(),
                ).bottom
                view.setPadding(0, 0, 0, maxOf(ime, nav))
                insets
            }
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
            background = ComposerBackground(resources.displayMetrics.density)
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            // Swallow taps so they don't fall through to the dismissing scrim.
            isClickable = true
        }
        panel.addView(
            TextView(this).apply {
                text = "what do you need?"
                textSize = 20f
                typeface = OverlayFonts.medium(context)
                setTextColor(Color.parseColor("#F4F4F5"))
            },
        )
        val submit = { text: String ->
            val task = text.trim()
            if (task.isNotEmpty()) {
                hideComposer()
                startTask(task)
            }
        }

        val input = EditText(this).apply {
            hint = "ask in any language"
            typeface = OverlayFonts.display(context)
            setTextColor(Color.parseColor("#F4F4F5"))
            setHintTextColor(Color.parseColor("#8B8B94"))
            textSize = 17f
            background = null
            // Wraps over a few lines but keeps a send action — people expect the
            // keyboard's action key to submit, not to insert a newline.
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            setHorizontallyScrolling(false)
            maxLines = 4
            minLines = 2
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                    submit(v.text?.toString().orEmpty())
                    true
                } else {
                    false
                }
            }
        }
        panel.addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        val send = TextView(this).apply {
            text = "show me"
            textSize = 16f
            typeface = OverlayFonts.medium(context)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#09090B"))
            setPadding(0, dp(17), 0, dp(17))
            background = GradientPill(resources.displayMetrics.density)
            setOnClickListener { submit(input.text?.toString().orEmpty()) }
        }
        panel.addView(
            send,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) },
        )

        root.addView(
            panel,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )

        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        ).apply {
            gravity = Gravity.BOTTOM
            // Focusable (no FLAG_NOT_FOCUSABLE) so the IME will attach to our EditText.
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        }
        if (runCatching { wm.addView(root, params) }.isFailure) return
        composerView = root

        // Back should dismiss the sheet rather than leaking to the app underneath.
        root.isFocusableInTouchMode = true
        root.requestFocus()
        root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                event.action == android.view.KeyEvent.ACTION_UP
            ) {
                hideComposer()
                true
            } else {
                false
            }
        }

        input.requestFocus()
        input.post {
            (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideComposer() {
        composerView?.let { view ->
            (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
            runCatching { wm.removeView(view) }
        }
        composerView = null
    }

    private fun startTask(task: String, spoken: com.drishti.core.agent.Language? = null) {
        if (!isOperable()) return
        hideComposer()
        // Optimistic so a quick tap stops even before the agent job posts running=true.
        running = true
        handleView?.busy = true
        glow.mode = EdgeGlowView.Mode.Working
        sayHelping("“${task.trim()}”", 0)
        runner.runTask(task, spoken)
    }

    private fun stopRun() {
        runner.cancel()
        running = false
        handleView?.busy = false
        pointerOverlay?.hide()
        dockAvoid = null
        if (!listening) glow.mode = EdgeGlowView.Mode.Off
        voiceOut.stop()
        say("stopped", 2000)
    }

    // ---- The dock ---------------------------------------------------------------

    private fun say(text: String, durationMs: Long) = say(text, durationMs, null, emptyList())

    /** Instruction / status while a session is live — always offers Stop. */
    private fun sayHelping(text: String, durationMs: Long) {
        say(
            text = text,
            durationMs = durationMs,
            tag = "helping",
            chips = listOf(BubbleChip("stop", primary = false) { stopRun() }),
        )
    }

    /**
     * Shows one sentence in the dock. Messages never stack: a new one replaces the old,
     * which is what keeps the overlay from becoming a chat log.
     */
    private fun say(
        text: String,
        durationMs: Long,
        tag: String?,
        chips: List<BubbleChip>,
    ) {
        mainHandler.post {
            dockHide?.let { mainHandler.removeCallbacks(it) }
            if (text.isBlank()) {
                fadeOutDock()
                return@post
            }

            val card = dockView ?: DockView(this).also { created ->
                val params = absoluteParams(
                    screenWidth() - dp(DOCK_MARGIN_DP) * 2,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                ).apply {
                    flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }
                if (runCatching { wm.addView(created, params) }.isFailure) return@post
                dockView = created
                dockParams = params
            }

            // Chips need touch; plain messages must not block the app underneath.
            dockParams?.let { params ->
                val notTouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                val want = if (chips.isEmpty()) params.flags or notTouchable
                else params.flags and notTouchable.inv()
                if (params.flags != want) {
                    params.flags = want
                    runCatching { wm.updateViewLayout(card, params) }
                }
            }

            val wasShowing = card.visibility == View.VISIBLE && card.alpha > 0.5f
            card.bind(text, tag, chips)
            card.visibility = View.VISIBLE
            placeDock()
            // Live text (a transcript, the next step) swaps in place; only a fresh dock rises.
            if (!wasShowing) animateDockIn()

            // Chips (e.g. Stop) and the listening transcript stay until replaced —
            // auto-hiding would drop Stop mid-guide or vanish while still talking.
            if (chips.isNotEmpty() || tag == "listening" || tag == "helping") return@post

            val readable = 1400L + text.length * 45L
            val hide = Runnable { fadeOutDock() }
            dockHide = hide
            mainHandler.postDelayed(hide, maxOf(durationMs, readable).coerceAtMost(9000L))
        }
    }

    /**
     * Rests the dock just above the navigation bar, full width. If the ringed control is
     * down there, the dock moves to the top instead: it must never cover what it points at.
     */
    private fun placeDock() {
        val card = dockView ?: return
        val params = dockParams ?: return
        val margin = dp(DOCK_MARGIN_DP)
        val width = screenWidth() - margin * 2
        card.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val h = card.measuredHeight
        val bottomY = realHeight() - navBarHeight() - margin - h
        val topY = statusBarHeight() + margin
        val avoid = dockAvoid
        val gap = dp(12)
        val coversBottom = avoid != null && avoid.bottom > bottomY - gap && avoid.top < bottomY + h
        val coversTop = avoid != null && avoid.top < topY + h + gap && avoid.bottom > topY
        params.width = width
        params.x = margin
        params.y = if (coversBottom && !coversTop) topY else bottomY.coerceAtLeast(topY)
        runCatching { wm.updateViewLayout(card, params) }
    }

    private fun animateDockIn() {
        val card = dockView ?: return
        dockAnimator?.cancel()
        card.alpha = 0f
        dockAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                card.alpha = t
                card.translationY = dp(10) * (1f - t)
            }
            start()
        }
    }

    private fun fadeOutDock() {
        val card = dockView ?: return
        dockAnimator?.cancel()
        dockAnimator = ValueAnimator.ofFloat(card.alpha, 0f).apply {
            duration = 200
            addUpdateListener { anim -> card.alpha = anim.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled) card.visibility = View.GONE
                }
            })
            start()
        }
    }

    private fun removeDock() {
        dockHide?.let { mainHandler.removeCallbacks(it) }
        dockAnimator?.cancel()
        dockView?.let { runCatching { wm.removeView(it) } }
        dockView = null
        dockParams = null
    }

    // ---- Plumbing ---------------------------------------------------------------

    private fun overlayParams(width: Int, height: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            width,
            height,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
    }

    /**
     * Placed in physical screen pixels, (0, 0) at the top-left of the glass, whatever the
     * system bars are doing — so the handle and dock land exactly where they are computed.
     */
    private fun absoluteParams(width: Int, height: Int): WindowManager.LayoutParams =
        overlayParams(width, height).apply {
            gravity = Gravity.TOP or Gravity.START
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun screenWidth(): Int = OverlayHost.realScreenSize(this).x
    private fun realHeight(): Int = OverlayHost.realScreenSize(this).y

    private fun systemDimen(name: String, fallbackDp: Int): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id != 0) resources.getDimensionPixelSize(id) else dp(fallbackDp)
    }

    private fun statusBarHeight() = systemDimen("status_bar_height", 24)
    private fun navBarHeight() = systemDimen("navigation_bar_height", 24)

    companion object {
        const val ACTION_STOP = "com.drishti.overlay.STOP"
        const val ACTION_CANCEL_RUN = "com.drishti.overlay.CANCEL_RUN"
        const val ACTION_SHOW = "com.drishti.overlay.SHOW"
        const val ACTION_COMPOSE = "com.drishti.overlay.COMPOSE"
        const val ACTION_LISTEN = "com.drishti.overlay.LISTEN"
        const val ACTION_RUN_TASK = "com.drishti.overlay.RUN_TASK"
        private const val EXTRA_TASK = "task"
        private const val NOTIF_ID = 42
        private const val DOCK_MARGIN_DP = 12
        private val COMMON_TERMS = listOf(
            "Wi-Fi", "Bluetooth", "WhatsApp", "YouTube", "Settings", "Google Pay", "PhonePe",
            "hotspot", "airplane mode", "dark mode", "font size", "ringtone", "alarm",
        )

        fun start(context: Context) {
            if (AuraPrefs.get(context).paused.value) return
            send(context, Intent(context, BubbleService::class.java).setAction(ACTION_SHOW))
        }

        /**
         * Pause Aura: set the pref and tear down without recreating the service
         * just to deliver an intent (which used to flash the overlay/notif).
         */
        fun stop(context: Context) {
            AuraPrefs.get(context).setPaused(true)
            context.stopService(Intent(context, BubbleService::class.java))
        }

        /** Stops the current guided session without pausing Aura globally. */
        fun cancelRun(context: Context) =
            send(context, Intent(context, BubbleService::class.java).setAction(ACTION_CANCEL_RUN))

        fun openComposer(context: Context) {
            if (AuraPrefs.get(context).paused.value) return
            send(context, Intent(context, BubbleService::class.java).setAction(ACTION_COMPOSE))
        }

        /** Summoned as the assistant: listen hands-free. */
        fun listen(context: Context) {
            if (AuraPrefs.get(context).paused.value) return
            send(context, Intent(context, BubbleService::class.java).setAction(ACTION_LISTEN))
        }

        fun runTask(context: Context, task: String) {
            if (AuraPrefs.get(context).paused.value) return
            send(
                context,
                Intent(context, BubbleService::class.java)
                    .setAction(ACTION_RUN_TASK)
                    .putExtra(EXTRA_TASK, task),
            )
        }

        private fun send(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

/** Rounded dark sheet for the composer. */
private class ComposerBackground(private val density: Float) : android.graphics.drawable.Drawable() {
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FA141417")
    }
    private val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#26262C")
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val rect = android.graphics.RectF()

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        val r = 24f * density
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom + r)
        canvas.drawRoundRect(rect, r, r, fill)
        canvas.drawRoundRect(rect, r, r, stroke)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** The composer's main button. */
private class GradientPill(private val density: Float) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val rect = android.graphics.RectF()

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        // Plain light button: the aura's colour is kept for the aura itself.
        paint.color = Color.parseColor("#F4F4F5")
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        val r = 16f * density
        canvas.drawRoundRect(rect, r, r, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
