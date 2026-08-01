package com.drishti.accessibility

/** Pure text-input helpers ported from mobilerun-portal (testable without Android runtime). */
object InputTextLogic {
    fun calculateInputText(
        currentText: String?,
        hintText: String?,
        newText: String,
        clear: Boolean,
        selectionStart: Int? = null,
        selectionEnd: Int? = null,
    ): String {
        if (clear) return newText
        val safeCurrentText = currentText.orEmpty()
        if (hintText != null && safeCurrentText == hintText) return newText
        val length = safeCurrentText.length
        val rawStart = selectionStart ?: length
        val rawEnd = selectionEnd ?: rawStart
        val start = rawStart.coerceIn(0, length)
        val end = rawEnd.coerceIn(0, length)
        val replaceStart = minOf(start, end)
        val replaceEnd = maxOf(start, end)
        return safeCurrentText.take(replaceStart) + newText + safeCurrentText.substring(replaceEnd)
    }
}
