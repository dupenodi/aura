package com.drishti.overlay

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.drishti.R

/**
 * Geist for the overlay's plain Android views (Compose reads the font resources directly).
 * Loaded once and cached: the dock is rebound on every message.
 */
object OverlayFonts {

    @Volatile
    private var regular: Typeface? = null

    @Volatile
    private var medium: Typeface? = null

    fun display(context: Context): Typeface =
        regular ?: load(context, R.font.geist_regular, Typeface.SANS_SERIF).also { regular = it }

    fun medium(context: Context): Typeface =
        medium ?: load(context, R.font.geist_medium, Typeface.DEFAULT_BOLD).also { medium = it }

    private fun load(context: Context, resId: Int, fallback: Typeface): Typeface =
        runCatching { ResourcesCompat.getFont(context, resId) }.getOrNull() ?: fallback
}
