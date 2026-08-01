package com.drishti.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Confirmations and questions, in Aura's voice.
 *
 * The design is explicit that irreversible actions stop with *chips, not a dialog* — so a
 * confirmation is the same glass card the orb always speaks through, with the choices
 * offered as pills. Only a genuinely open question (a name, an amount) falls back to a
 * text field, and even then it reads as the assistant asking rather than a system prompt.
 */
class ConfirmPromptOverlay(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null

    suspend fun confirm(message: String): Boolean = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<Boolean>()
        showCard(
            tag = "just checking",
            message = message,
            accent = Color.parseColor("#FFCF5C"),
            chips = listOf(
                BubbleChip("Yes, do it", primary = true) {
                    dismissInternal()
                    deferred.complete(true)
                },
                BubbleChip("Stop", primary = false) {
                    dismissInternal()
                    deferred.complete(false)
                },
            ),
        )
        deferred.await()
    }

    /**
     * Asks an open question. [options] renders as chips when the caller knows the real
     * candidates; otherwise the user types an answer.
     */
    suspend fun ask(
        question: String,
        options: List<String> = emptyList(),
        timeoutMs: Long = 60_000L,
    ): String = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String>()
        if (options.isNotEmpty()) {
            showCard(
                tag = "which one?",
                message = question,
                accent = Color.parseColor("#7EF2FF"),
                chips = options.map { option ->
                    BubbleChip(option, primary = true) {
                        dismissInternal()
                        deferred.complete(option)
                    }
                },
            )
        } else {
            showTextPrompt(question, deferred)
        }
        withTimeoutOrNull(timeoutMs) { deferred.await() } ?: run {
            dismissInternal()
            ""
        }
    }

    /** The glass card with pills, anchored above the resting orb. */
    private fun showCard(
        tag: String,
        message: String,
        accent: Int,
        chips: List<BubbleChip>,
    ) {
        dismissInternal()
        val card = BubbleCardView(context).apply {
            setAccent(accent)
            setTail(onTop = false, onRight = true)
            bind(message, tag, chips)
        }
        val container = FrameLayout(context).apply {
            setPadding(dp(16), 0, dp(16), dp(120))
            addView(
                card,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END,
                ),
            )
        }
        addWindow(container, focusable = false)
    }

    /** Open question with a text field, styled like the composer sheet. */
    private fun showTextPrompt(question: String, deferred: CompletableDeferred<String>) {
        dismissInternal()

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#B3000000"))
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                v.setPadding(0, 0, 0, maxOf(ime, nav))
                insets
            }
        }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
            background = PromptSheetBackground(context.resources.displayMetrics.density)
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            isClickable = true
        }
        panel.addView(
            TextView(context).apply {
                text = question
                textSize = 16f
                typeface = OverlayFonts.display(context)
                setTextColor(Color.parseColor("#F2F0FF"))
                setLineSpacing(dp(4).toFloat(), 1f)
            },
        )

        val input = EditText(context).apply {
            hint = "Your answer"
            textSize = 15f
            typeface = OverlayFonts.display(context)
            background = null
            setTextColor(Color.parseColor("#F2F0FF"))
            setHintTextColor(Color.parseColor("#6D6A85"))
        }
        panel.addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) },
        )

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            pill("Send", primary = true) {
                val answer = input.text?.toString()?.trim().orEmpty()
                dismissInternal()
                deferred.complete(answer)
            },
        )
        row.addView(
            pill("Not now", primary = false) {
                dismissInternal()
                deferred.complete("")
            },
        )
        panel.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) },
        )

        root.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )

        addWindow(root, focusable = true)

        root.isFocusableInTouchMode = true
        root.requestFocus()
        root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismissInternal()
                deferred.complete("")
                true
            } else {
                false
            }
        }
        input.requestFocus()
        input.post {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun pill(label: String, primary: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 13f
        typeface = OverlayFonts.display(context)
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(11), dp(18), dp(11))
        setTextColor(
            if (primary) Color.parseColor("#7EF2FF") else Color.parseColor("#9C99B4"),
        )
        background = PillBackground(
            context.resources.displayMetrics.density,
            if (primary) Color.parseColor("#667EF2FF") else Color.parseColor("#FF2A2A3A"),
        )
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = dp(8) }
    }

    private fun addWindow(content: View, focusable: Boolean) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        }
        if (runCatching { wm.addView(content, params) }.isSuccess) view = content
    }

    fun detach() {
        handler.post { dismissInternal() }
    }

    private fun dismissInternal() {
        view?.let { v ->
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(v.windowToken, 0)
            runCatching { wm.removeView(v) }
        }
        view = null
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}

/** Rounded dark sheet matching the composer. */
private class PromptSheetBackground(private val density: Float) :
    android.graphics.drawable.Drawable() {

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

/** Chip pill background. */
private class PillBackground(private val density: Float, border: Int) :
    android.graphics.drawable.Drawable() {

    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EB0B0B13")
    }
    private val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = border
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val rect = android.graphics.RectF()

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        val r = rect.height() / 2f
        canvas.drawRoundRect(rect, r, r, fill)
        canvas.drawRoundRect(rect, r, r, stroke)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
