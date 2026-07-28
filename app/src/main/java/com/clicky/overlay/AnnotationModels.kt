package com.clicky.overlay

import android.graphics.RectF
import java.util.UUID

/**
 * On-screen coaching annotations drawn by [AnnotationCanvasView].
 * Each instance fades in, then auto-expires after [EXPIRE_MS].
 */
sealed class Annotation {
    abstract val id: String
    abstract val createdAtMs: Long

    data class Circle(
        override val id: String = newId(),
        override val createdAtMs: Long = System.currentTimeMillis(),
        val rect: RectF,
        val color: Int,
        val strokeWidth: Float,
        val label: String? = null,
    ) : Annotation()

    data class Arrow(
        override val id: String = newId(),
        override val createdAtMs: Long = System.currentTimeMillis(),
        val fromX: Float,
        val fromY: Float,
        val toX: Float,
        val toY: Float,
        val label: String? = null,
        val color: Int = DEFAULT_ACCENT,
    ) : Annotation()

    data class Highlight(
        override val id: String = newId(),
        override val createdAtMs: Long = System.currentTimeMillis(),
        val rect: RectF,
    ) : Annotation()

    data class Callout(
        override val id: String = newId(),
        override val createdAtMs: Long = System.currentTimeMillis(),
        val x: Float,
        val y: Float,
        val text: String,
    ) : Annotation()

    data class Ripple(
        override val id: String = newId(),
        override val createdAtMs: Long = System.currentTimeMillis(),
        val x: Float,
        val y: Float,
    ) : Annotation()

    companion object {
        const val FADE_IN_MS = 280L
        const val EXPIRE_MS = 6_000L
        const val FADE_OUT_MS = 400L

        /** Soft teal accent used by default arrows / ripples. */
        const val DEFAULT_ACCENT = 0xFF2EC4B6.toInt()

        const val HIGHLIGHT_COLOR = 0x66FFEB3B.toInt()
        const val LABEL_BG = 0xFF1A1A1A.toInt()
        const val LABEL_FG = 0xFFFFFFFF.toInt()

        fun newId(): String = UUID.randomUUID().toString()
    }
}

enum class AgentMode {
    Coach,
    Pilot,
}

enum class BubbleState {
    Idle,
    Listening,
    Processing,
    Thinking,
}
