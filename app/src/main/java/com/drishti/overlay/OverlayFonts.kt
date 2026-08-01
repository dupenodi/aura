package com.drishti.overlay

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.drishti.R

/**
 * The design's typefaces for the overlay's plain Android views.
 *
 * Compose reads the font resources directly; these windows are built from Views, so they
 * need Typeface instances. Loaded once and cached — the overlay rebuilds its bubble on
 * every message and re-reading the font each time would be wasteful.
 */
object OverlayFonts {

    @Volatile
    private var display: Typeface? = null

    @Volatile
    private var mono: Typeface? = null

    fun display(context: Context): Typeface =
        display ?: load(context, R.font.space_grotesk, Typeface.SANS_SERIF).also { display = it }

    fun mono(context: Context): Typeface =
        mono ?: load(context, R.font.ibm_plex_mono, Typeface.MONOSPACE).also { mono = it }

    private fun load(context: Context, resId: Int, fallback: Typeface): Typeface =
        runCatching { ResourcesCompat.getFont(context, resId) }.getOrNull() ?: fallback
}
