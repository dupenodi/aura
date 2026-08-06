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
import android.graphics.Color
import android.graphics.PixelFormat
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
import com.drishti.agent.AgentOrchestrator
import com.drishti.data.AuraPrefs
import com.drishti.voice.VoiceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The floating orb and everything it says.
 *
 * Interaction model from the design: tap to type, hold to talk, drag to move. The orb
 * parks at a screen edge, and any bubble anchors to whichever side it is resting on.
 */
class BubbleService : Service() {

    /** UI work (views / WindowManager) stays on Main. */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Agent / voice work runs off Main to avoid ANRs. */
    private val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var wm: WindowManager
    private lateinit var prefs: AuraPrefs
    private lateinit var pointerOverlay: PointerOverlay
    private lateinit var orchestrator: AgentOrchestrator

    private var orbView: OrbView? = null
    private var orbParams: WindowManager.LayoutParams? = null
    private var composerView: View? = null

    private var voice: VoiceSession? = null
    private var bubbleView: BubbleCardView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleHide: Runnable? = null
    private var bubbleAnimator: ValueAnimator? = null

    private var orbX = 0
    private var orbY = 0

    /** True while a guidance session is on, which turns the orb into a Stop control. */
    private var running = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val speech by lazy { com.drishti.voice.SpeechOutput(this) }

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
        pointerOverlay = PointerOverlay(this)
        // Status lines during a run carry a Stop chip so stop isn't only "know to tap".
        pointerOverlay.statusListener = { text, duration ->
            if (running && text.isNotBlank()) {
                sayHelping(text, duration)
            } else {
                say(text, duration)
            }
        }
        orchestrator = AgentOrchestrator(
            appContext = applicationContext,
            pointerOverlay = pointerOverlay,
            scope = agentScope,
        )
        orchestrator.onRunStateChanged = { isRunning ->
            mainHandler.post {
                running = isRunning
                orbView?.busy = isRunning
            }
        }

        // Required within the FGS start timeout even if we immediately stopSelf.
        startAsForeground()

        if (prefs.paused.value) {
            stopSelf()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Aura needs permission to draw over apps", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        showOrb()
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
                // Match Privacy → Pause: hide the orb and stay paused until they unpause.
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
            ACTION_RUN_TASK -> intent.getStringExtra(EXTRA_TASK)?.let { task ->
                mainHandler.post {
                    if (isOperable()) startTask(task)
                }
            }
            ACTION_SHOW -> mainHandler.post {
                if (isOperable() && orbView == null) showOrb()
            }
        }
        return START_STICKY
    }

    /** Paused or missing overlay — refuse to show UI or start work. */
    private fun isOperable(): Boolean =
        !prefs.paused.value && Settings.canDrawOverlays(this)

    override fun onDestroy() {
        beginHoldRunnable?.let { mainHandler.removeCallbacks(it) }
        beginHoldRunnable = null
        hideComposer()
        // Tear down voice quietly — don't post a bubble update after the window is gone.
        voice?.cancel()
        listening = false
        speech.shutdown()
        removeBubble()
        orbView?.let { runCatching { wm.removeView(it) } }
        orbView = null
        pointerOverlay.detach()
        runCatching { unregisterReceiver(batteryReceiver) }
        uiScope.cancel()
        agentScope.cancel()
        super.onDestroy()
    }

    /** Presence changes in Settings should reach the orb immediately. */
    private fun observePrefs() {
        uiScope.launch {
            prefs.orbSkin.collect { skin -> orbView?.skin = skin }
        }
        uiScope.launch {
            prefs.glow.collect { level -> orbView?.glow = level }
        }
        uiScope.launch {
            prefs.paused.collect { paused -> if (paused) stopSelf() }
        }
    }

    private fun refreshPowerState() {
        val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager ?: return
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        orbView?.lowPower = level in 1..15
    }

    private fun startAsForeground() {
        val channelId = "aura_orb"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
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
            .addAction(0, "Pause Aura", stop)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ---- The orb ----------------------------------------------------------------

    private fun showOrb() {
        val size = dp(64)
        val view = OrbView(this).apply { applyPrefs(prefs) }

        // Return to where the user last parked it; otherwise rest at the right edge,
        // comfortably above the gesture bar.
        orbX = prefs.orbX.takeIf { it >= 0 } ?: (screenWidth() - size - dp(14))
        orbY = prefs.orbY.takeIf { it >= 0 } ?: (screenHeight() * 0.66f).toInt()
        orbX = orbX.coerceIn(0, (screenWidth() - size).coerceAtLeast(0))
        orbY = orbY.coerceIn(0, (screenHeight() - size).coerceAtLeast(0))

        val params = overlayParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            x = orbX
            y = orbY
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var holding = false
        // The platform's own slop and long-press timeout, so the orb feels like every
        // other control on the phone rather than something with its own idea of a press.
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        val holdTimeout = android.view.ViewConfiguration.getLongPressTimeout().toLong()

        beginHoldRunnable?.let { mainHandler.removeCallbacks(it) }
        val beginHold = Runnable {
            holding = true
            view.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start()
            beginListening()
        }
        beginHoldRunnable = beginHold

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    holding = false
                    // Mid-session the orb is Stop — don't let long-press steal it for voice.
                    if (!running) {
                        mainHandler.postDelayed(beginHold, holdTimeout)
                    }
                    view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(110).start()
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
                        params.x = (startX + dx).coerceIn(0, (screenWidth() - size).coerceAtLeast(0))
                        params.y = (startY + dy).coerceIn(0, (screenHeight() - size).coerceAtLeast(0))
                        orbX = params.x
                        orbY = params.y
                        runCatching { wm.updateViewLayout(view, params) }
                        repositionBubble()
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(beginHold)
                    view.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
                    when {
                        holding -> finishListening()
                        moved -> snapToEdge(view, params, size)
                        // Mid-session the orb is the way out. Nothing else may cover the
                        // app the user is being guided through, and the orb is the one
                        // control they have already been watching.
                        running -> stopRun()
                        else -> showComposer()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(beginHold)
                    view.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
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
        // addView would permanently block retries (orbView != null).
        if (runCatching { wm.addView(view, params) }.isFailure) return
        orbView = view
        orbParams = params
    }

    /** Settles the orb against the nearer side, the way messenger heads behave. */
    private fun snapToEdge(view: View, params: WindowManager.LayoutParams, size: Int) {
        val margin = dp(14)
        val target = if (params.x + size / 2 < screenWidth() / 2) {
            margin
        } else {
            (screenWidth() - size - margin).coerceAtLeast(margin)
        }
        ValueAnimator.ofInt(params.x, target).apply {
            duration = 240
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                params.x = anim.animatedValue as Int
                orbX = params.x
                if (runCatching { wm.updateViewLayout(view, params) }.isFailure) {
                    cancel()
                    return@addUpdateListener
                }
                repositionBubble()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    prefs.orbX = orbX
                    prefs.orbY = orbY
                }
            })
            start()
        }
    }

    // ---- Voice ------------------------------------------------------------------

    private var listening = false

    /**
     * Press and hold: record while the finger is down, send on release.
     *
     * The transcript streams into the bubble as it is recognised so the user can see it
     * being heard — the single thing that makes voice input feel trustworthy.
     */
    private fun beginListening() {
        if (listening) return

        val session = VoiceSession(this)
        if (!session.hasPermission()) {
            say("I need microphone permission — tap to grant it", 5000)
            openMicPermission()
            return
        }

        listening = true
        voice = session
        orbView?.listening = true
        speech.stop()
        say("Listening — release to send", 30_000, tag = "listening", chips = emptyList())

        session.start(
            languageTag = prefs.language.value.tag,
            onPartial = { partial ->
                mainHandler.post {
                    if (listening) say("\u201C$partial\u201D", 30_000, "listening", emptyList())
                }
            },
            onFinal = { text ->
                mainHandler.post {
                    endListening()
                    if (text.isNotBlank()) startTask(text) else say("I didn't catch that", 2500)
                }
            },
            onFailure = { reason ->
                mainHandler.post {
                    endListening()
                    say(
                        when (reason) {
                            VoiceSession.Failure.NoPermission ->
                                "I need microphone permission to listen"
                            VoiceSession.Failure.Unavailable ->
                                "Voice input isn't available on this phone — type instead"
                            VoiceSession.Failure.NoSpeech ->
                                "I didn't catch that — hold me and try again"
                            VoiceSession.Failure.Error ->
                                "Something went wrong listening — type instead"
                        },
                        3500,
                    )
                }
            },
        )
    }

    /** Finger lifted: stop recording; the final transcript arrives via the callback. */
    private fun finishListening() {
        if (!listening) return
        orbView?.listening = false
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
        voice = null
        orbView?.listening = false
    }

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
            setBackgroundColor(Color.parseColor("#B3000000"))
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
                text = "What do you need?"
                textSize = 18f
                typeface = OverlayFonts.display(context)
                setTextColor(Color.parseColor("#F2F0FF"))
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
            hint = "Ask, or hold the orb to talk"
            typeface = OverlayFonts.display(context)
            setTextColor(Color.parseColor("#F2F0FF"))
            setHintTextColor(Color.parseColor("#6D6A85"))
            textSize = 15f
            background = null
            // Wraps over a few lines but keeps a send action — people expect the
            // keyboard's action key to submit, not to insert a newline.
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
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
            text = "Go"
            textSize = 15f
            typeface = OverlayFonts.display(context)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#07070B"))
            setPadding(0, dp(15), 0, dp(15))
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

    private fun startTask(task: String) {
        if (!isOperable()) return
        hideComposer()
        // Optimistic so a quick tap stops even before the agent job posts running=true.
        running = true
        orbView?.busy = true
        sayHelping("Working on it — tap me to stop", 5000)
        orchestrator.runTask(task)
    }

    private fun stopRun() {
        orchestrator.cancel()
        running = false
        orbView?.busy = false
        speech.stop()
        say("Stopped.", 2500)
    }

    // ---- Speech bubble ----------------------------------------------------------

    private fun say(text: String, durationMs: Long) = say(text, durationMs, null, emptyList())

    /** Instruction / status while a session is live — always offers Stop. */
    private fun sayHelping(text: String, durationMs: Long) {
        say(
            text = text,
            durationMs = durationMs,
            tag = "helping",
            chips = listOf(BubbleChip("Stop", primary = true) { stopRun() }),
        )
    }

    /**
     * Shows a single sentence anchored to the orb. Bubbles never stack: a new message
     * replaces the old one, which is what keeps the overlay from becoming a chat log.
     */
    private fun say(
        text: String,
        durationMs: Long,
        tag: String?,
        chips: List<BubbleChip>,
    ) {
        mainHandler.post {
            bubbleHide?.let { mainHandler.removeCallbacks(it) }
            if (text.isBlank()) {
                fadeOutBubble()
                return@post
            }

            val card = bubbleView ?: BubbleCardView(this).also { created ->
                val params = overlayParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }
                if (runCatching { wm.addView(created, params) }.isFailure) return@post
                bubbleView = created
                bubbleParams = params
            }

            // Chips need touch; plain messages must not block the app underneath.
            bubbleParams?.let { params ->
                val notTouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                val want = if (chips.isEmpty()) params.flags or notTouchable
                else params.flags and notTouchable.inv()
                if (params.flags != want) {
                    params.flags = want
                    runCatching { wm.updateViewLayout(card, params) }
                }
            }

            card.bind(text, tag, chips)
            card.visibility = View.VISIBLE
            card.alpha = 0f
            repositionBubble()
            animateBubbleIn()

            // Chips (e.g. Stop) and the listening transcript stay until replaced —
            // auto-hiding would drop Stop mid-guide or vanish while still holding.
            if (chips.isNotEmpty() || tag == "listening") return@post

            val readable = 1400L + text.length * 45L
            val hide = Runnable { fadeOutBubble() }
            bubbleHide = hide
            mainHandler.postDelayed(hide, maxOf(durationMs, readable).coerceAtMost(9000L))
        }
    }

    /**
     * Anchors the card to the orb: above when there's headroom, and on the side with
     * room to spare. The orb must never sit on top of what it is talking about.
     */
    private fun repositionBubble() {
        val card = bubbleView ?: return
        val params = bubbleParams ?: return
        val margin = dp(14)
        val orbSize = dp(64)
        val gap = dp(10)

        card.measure(
            View.MeasureSpec.makeMeasureSpec(screenWidth() - margin * 2, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val w = card.measuredWidth
        val h = card.measuredHeight

        val above = orbY - gap - h >= margin
        params.y = (if (above) orbY - gap - h else orbY + orbSize + gap)
            .coerceIn(margin, (screenHeight() - h - margin).coerceAtLeast(margin))

        val orbCenterX = orbX + orbSize / 2
        val onRight = orbCenterX > screenWidth() / 2
        params.x = if (onRight) {
            (orbX + orbSize - w).coerceAtLeast(margin)
        } else {
            orbX.coerceAtMost((screenWidth() - w - margin).coerceAtLeast(margin))
        }

        card.setTail(onTop = !above, onRight = onRight)
        runCatching { wm.updateViewLayout(card, params) }
    }

    private fun animateBubbleIn() {
        val card = bubbleView ?: return
        bubbleAnimator?.cancel()
        bubbleAnimator = ValueAnimator.ofFloat(card.alpha, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                card.alpha = t
                card.translationY = dp(6) * (1f - t)
            }
            start()
        }
    }

    private fun fadeOutBubble() {
        val card = bubbleView ?: return
        bubbleAnimator?.cancel()
        bubbleAnimator = ValueAnimator.ofFloat(card.alpha, 0f).apply {
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

    private fun removeBubble() {
        bubbleHide?.let { mainHandler.removeCallbacks(it) }
        bubbleAnimator?.cancel()
        bubbleView?.let { runCatching { wm.removeView(it) } }
        bubbleView = null
        bubbleParams = null
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun screenWidth(): Int = resources.displayMetrics.widthPixels
    private fun screenHeight(): Int = resources.displayMetrics.heightPixels

    companion object {
        const val ACTION_STOP = "com.drishti.overlay.STOP"
        const val ACTION_CANCEL_RUN = "com.drishti.overlay.CANCEL_RUN"
        const val ACTION_SHOW = "com.drishti.overlay.SHOW"
        const val ACTION_COMPOSE = "com.drishti.overlay.COMPOSE"
        const val ACTION_RUN_TASK = "com.drishti.overlay.RUN_TASK"
        private const val EXTRA_TASK = "task"
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            if (AuraPrefs.get(context).paused.value) return
            send(context, Intent(context, BubbleService::class.java).setAction(ACTION_SHOW))
        }

        /**
         * Pause Aura: set the pref and tear down without recreating the service
         * just to deliver an intent (which used to flash the orb/notif).
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
        color = Color.parseColor("#F50B0B13")
    }
    private val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4C7EF2FF")
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

/** The cyan→purple call-to-action pill. */
private class GradientPill(private val density: Float) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val rect = android.graphics.RectF()

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        paint.shader = android.graphics.LinearGradient(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
            Color.parseColor("#7EF2FF"),
            Color.parseColor("#A06BFF"),
            android.graphics.Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        val r = 15f * density
        canvas.drawRoundRect(rect, r, r, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
