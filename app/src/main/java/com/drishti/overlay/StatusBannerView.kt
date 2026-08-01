package com.drishti.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The "I am doing something" banner.
 *
 * Without a persistent indicator, an assistant that opens apps by itself feels like the
 * phone misbehaving. This sits at the top for the whole run, names the current step, and
 * always offers a way out — so nothing Aura does is ever a surprise.
 */
class StatusBannerView(context: Context) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    private val headline: TextView
    private val detail: TextView
    private val stopButton: TextView
    private val progress: ProgressStripe

    var onStop: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        background = BannerBackground(density)
        setPadding(dp(14f), dp(11f), dp(14f), dp(12f))
        setLayerType(LAYER_TYPE_SOFTWARE, null)

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        headline = TextView(context).apply {
            textSize = 10.5f
            letterSpacing = 0.12f
            typeface = OverlayFonts.mono(context)
            setTextColor(Color.parseColor("#C39BFF"))
        }
        row.addView(
            headline,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )

        stopButton = TextView(context).apply {
            text = "Stop"
            textSize = 12.5f
            typeface = OverlayFonts.display(context)
            setTextColor(Color.parseColor("#FF9AA9"))
            setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
            background = StopPill(density)
            setOnClickListener { onStop?.invoke() }
        }
        row.addView(stopButton)
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        detail = TextView(context).apply {
            textSize = 13.5f
            typeface = OverlayFonts.display(context)
            setTextColor(Color.parseColor("#ECEAF5"))
        }
        addView(
            detail,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(5f)
            },
        )

        progress = ProgressStripe(context)
        addView(
            progress,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(3f)).apply { topMargin = dp(9f) },
        )
    }

    /** [step] of 0 means "still working out what to do". */
    fun bind(mode: String, step: Int, message: String) {
        headline.text = if (step > 0) {
            "$mode · step $step".uppercase()
        } else {
            "$mode · thinking".uppercase()
        }
        detail.text = message
    }

    fun setIndeterminate(running: Boolean) = progress.setRunning(running)

    /** The sweeping bar that shows the run is alive. */
    private class ProgressStripe(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF22222F")
        }
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var phase = 0f
        private var animator: ValueAnimator? = null

        fun setRunning(running: Boolean) {
            if (running) start() else stop()
        }

        private fun start() {
            if (animator?.isRunning == true) return
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1700
                repeatCount = ValueAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        private fun stop() {
            animator?.cancel()
            animator = null
        }

        override fun onDetachedFromWindow() {
            stop()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val r = h / 2f
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, r, r, track)

            val barW = w * 0.28f
            val left = -barW + (w + barW * 2) * phase
            bar.shader = LinearGradient(
                left, 0f, left + barW, 0f,
                Color.parseColor("#4DE8FF"), Color.parseColor("#A06BFF"),
                Shader.TileMode.CLAMP,
            )
            rect.set(left, 0f, left + barW, h)
            canvas.drawRoundRect(rect, r, r, bar)
        }
    }
}

/** Dark glass panel with a violet hairline. */
private class BannerBackground(private val density: Float) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F00B0B13")
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DA06BFF")
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val r = 14f * density
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        canvas.drawRoundRect(rect, r, r, fill)
        canvas.drawRoundRect(rect, r, r, stroke)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Outlined danger pill for the stop affordance. */
private class StopPill(private val density: Float) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1AFF7A8F")
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#59FF7A8F")
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
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
