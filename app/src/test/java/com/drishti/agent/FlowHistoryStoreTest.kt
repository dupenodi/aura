package com.drishti.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowHistoryStoreTest {
    @Test
    fun flowRecordDefaultsAreDemoFriendly() {
        val record = FlowRecord(
            userUtterance = "zepto onion garlic",
            mode = "Pilot",
            summary = "Added items",
            steps = listOf(
                FlowHistoryStep("open_app", """{"query":"zepto"}""", true),
                FlowHistoryStep("tap", """{"x":100,"y":200}""", true),
            ),
            packageName = "com.zepto",
            durationMs = 12_000,
            completed = true,
        )
        assertTrue(record.id.isNotBlank())
        assertEquals(2, record.steps.size)
        assertTrue(record.completed)
        assertFalse(record.timestampMs <= 0L)
    }

    @Test
    fun maxFlowsConstantIsThirty() {
        assertEquals(30, FlowHistoryStore.MAX_FLOWS)
    }

    @Test
    fun meaningfulActionCountIgnoresObserveSpeakFinish() {
        val record = FlowRecord(
            userUtterance = "zepto onion",
            mode = "Pilot",
            summary = "Cancelled",
            steps = listOf(
                FlowHistoryStep("observe_screen", "{}", true),
                FlowHistoryStep("speak", """{"text":"hi"}""", true),
                FlowHistoryStep("open_app", """{"query":"zepto"}""", true),
                FlowHistoryStep("tap", """{"x":1,"y":2}""", true),
                FlowHistoryStep("type_text", """{"text":"onion"}""", true),
                FlowHistoryStep("tap_node", """{"node_id":3}""", true),
                FlowHistoryStep("finish", """{"summary":"done"}""", true),
            ),
            completed = false,
        )
        assertEquals(4, FlowHistoryStore.actionStepCount(record))
        assertTrue(FlowHistoryStore.hasMeaningfulActions(record, minSteps = 4))
        assertFalse(FlowHistoryStore.hasMeaningfulActions(record, minSteps = 5))
    }

    @Test
    fun metaSaveSummaryDetected() {
        assertTrue(FlowHistoryStore.isMetaSaveSummary("Saved to learned flows by user"))
        assertTrue(FlowHistoryStore.isMetaSaveSummary("Saved prior flow to learned flows: zepto"))
        assertFalse(FlowHistoryStore.isMetaSaveSummary("Cancelled"))
    }
}
