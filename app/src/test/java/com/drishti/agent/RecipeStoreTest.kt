package com.drishti.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeStoreTest {
    @Test
    fun intentKeyExtractsAppAndGoalKeywords() {
        val key = RecipeStore.intentKey("Order onion from Zepto", preferredApp = null)
        assertTrue(key.contains("zepto"))
        assertTrue(key.contains("onion"))
    }

    @Test
    fun intentKeyIncludesPreferredApp() {
        val key = RecipeStore.intentKey("book me a ride", preferredApp = "uber")
        assertTrue(key.contains("uber"))
        assertTrue(key.contains("book") || key.contains("ride"))
    }

    @Test
    fun intentLabelIsReadable() {
        val label = RecipeStore.intentLabel("zepto onion 1kg", null)
        assertTrue(label.contains("zepto"))
        assertTrue(label.contains("onion"))
        assertEquals(label, RecipeStore.intentKey("zepto onion 1kg", null).replace('+', ' '))
    }

    @Test
    fun realUiActionsRequired() {
        val metaOnly = listOf(
            RecipeStep("scroll", """{"direction":"down"}"""),
            RecipeStep("swipe", """{"direction":"up"}"""),
        )
        assertFalse(metaOnly.any { it.name in RecipeStore.REAL_UI_ACTIONS })

        val withTap = metaOnly + RecipeStep("tap", """{"x":10,"y":20}""")
        assertTrue(withTap.any { it.name in RecipeStore.REAL_UI_ACTIONS })
        assertTrue(
            RecipeStore.REAL_UI_ACTIONS.containsAll(
                listOf("open_app", "tap", "tap_node", "type_text"),
            ),
        )
    }

    @Test
    fun parseCoordsFromTapNodeResult() {
        val coords = RecipeStore.parseCoordsFromResult(
            """tapped node 12 at 240.0,880.5 label="Add 200 g"""",
        )
        assertNotNull(coords)
        assertEquals(240.0f, coords!!.first, 0.01f)
        assertEquals(880.5f, coords.second, 0.01f)
    }

    @Test
    fun parseCoordsFromCoordinateTapResult() {
        val coords = RecipeStore.parseCoordsFromResult("tapped 100,200")
        assertNotNull(coords)
        assertEquals(100f, coords!!.first, 0.01f)
        assertEquals(200f, coords.second, 0.01f)
        assertNull(RecipeStore.parseCoordsFromResult("typed 5 chars"))
    }

    @Test
    fun mergeCoordsIntoArgsEmbedsXyForPromotion() {
        val merged = RecipeStore.mergeCoordsIntoArgs(
            """{"node_id":12}""",
            x = 240f,
            y = 880.5f,
            nodeId = 12,
        )
        assertTrue(merged.contains("\"x\""))
        assertTrue(merged.contains("240"))
        assertTrue(merged.contains("880.5") || merged.contains("880.5"))
        assertTrue(merged.contains("node_id"))
    }

    @Test
    fun replayInvocationPrefersCoordinateTapOverTapNode() {
        val step = RecipeStep(
            name = "tap_node",
            args = """{"node_id":7,"x":111.0,"y":222.0}""",
            x = 111f,
            y = 222f,
            nodeId = 7,
        )
        val (name, args) = RecipeStore.replayInvocationOf(step)
        assertEquals("tap", name)
        assertTrue(args.contains("111"))
        assertTrue(args.contains("222"))
        assertFalse(args.contains("node_id"))
    }

    @Test
    fun historyResultTextRecoversCoordsForLearnedSave() {
        val step = FlowHistoryStep(
            name = "tap_node",
            args = """{"node_id":3}""",
            success = true,
            resultText = "tapped node 3 at 50,60",
        )
        val coords = RecipeStore.parseCoordsFromResult(step.resultText)
        assertNotNull(coords)
        val merged = RecipeStore.mergeCoordsIntoArgs(
            step.args,
            coords!!.first,
            coords.second,
            nodeId = 3,
        )
        assertTrue(merged.contains("\"x\""))
        assertTrue(merged.contains("50"))
        assertTrue(merged.contains("60"))
    }
}
