package com.drishti.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class InputTextLogicTest {
    @Test
    fun clearReplacesEntireField() {
        val result = InputTextLogic.calculateInputText(
            currentText = "old",
            hintText = null,
            newText = "new",
            clear = true,
        )
        assertEquals("new", result)
    }

    @Test
    fun hintTreatedAsEmptyOnAppend() {
        val result = InputTextLogic.calculateInputText(
            currentText = "Search",
            hintText = "Search",
            newText = "hello",
            clear = false,
        )
        assertEquals("hello", result)
    }

    @Test
    fun appendUsesSelection() {
        val result = InputTextLogic.calculateInputText(
            currentText = "abcd",
            hintText = null,
            newText = "X",
            clear = false,
            selectionStart = 2,
            selectionEnd = 2,
        )
        assertEquals("abXcd", result)
    }
}
