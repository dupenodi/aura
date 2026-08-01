package com.drishti.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeDiffTest {
    @Test
    fun detectsUnchanged() {
        val fp = "1:Button:OK:[0,0][10,10]|2:TextView:Hi:[0,10][10,20]"
        val s = TreeDiff.summarize(fp, fp)
        assertFalse(s.changed)
        assertEquals(2, s.afterCount)
        assertTrue(TreeDiff.shortLabel(s).contains("unchanged"))
    }

    @Test
    fun detectsAddedAndRemoved() {
        val before = "1:Button:A:[0,0][10,10]|2:TextView:B:[0,10][10,20]"
        val after = "1:Button:A:[0,0][10,10]|3:EditText:C:[0,20][10,30]"
        val s = TreeDiff.summarize(before, after)
        assertTrue(s.changed)
        assertEquals(1, s.removed.size)
        assertTrue(s.added.any { it.startsWith("3:") })
    }

    @Test
    fun detectsContentChangeSameIndex() {
        val before = "1:Button:Open:[0,0][10,10]"
        val after = "1:Button:Close:[0,0][10,10]"
        val s = TreeDiff.summarize(before, after)
        assertTrue(s.changed)
        assertTrue(s.added.any { it.startsWith("Δ") })
    }
}
