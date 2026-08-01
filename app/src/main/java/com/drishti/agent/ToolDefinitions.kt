package com.drishti.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object ToolDefinitions {
    val tools = listOf(
        tool("tap", "Tap the element with the given overlay index from the last observe tree.") {
            put("index", numberProp("Overlay index from the accessibility tree"))
        },
        tool("tap_xy", "Tap absolute screen coordinates (for vision / custom-drawn UI).") {
            put("x", numberProp("X in screen pixels"))
            put("y", numberProp("Y in screen pixels"))
        },
        tool(
            "type",
            "Type into an editable field. Prefer passing index of the field. " +
                "Focuses/clicks the field first, then sets text (clipboard paste fallback).",
        ) {
            put("text", stringProp("Text to type"))
            put("clear", boolProp("Clear existing text first", required = false))
            put("index", numberProp("Overlay index of the editable field", required = false))
        },
        tool("swipe", "Swipe from start to end coordinates. Prefer scroll for list navigation.") {
            put("startX", numberProp("Start X"))
            put("startY", numberProp("Start Y"))
            put("endX", numberProp("End X"))
            put("endY", numberProp("End Y"))
            put("durationMs", numberProp("Duration ms", required = false))
        },
        tool(
            "scroll",
            "Scroll the main scrollable region (native ACTION_SCROLL when available). " +
                "Use this to move through Settings lists and RecyclerViews.",
        ) {
            put("direction", stringProp("down|up|left|right (default down)", required = false))
        },
        tool("open_app", "Launch an app by package name and wait until it is foreground.") {
            put("package_name", stringProp("Android package name"))
        },
        tool("wait_for_change", "Wait briefly for the screen to settle, then re-read the tree.") {
            put("ms", numberProp("Milliseconds to wait", required = false))
        },
        tool("speak", "Short spoken/status narration only.") {
            put("text", stringProp("Short phrase to speak"))
        },
        tool("ask_user", "Ask the user a clarifying question and wait (use sparingly).") {
            put("question", stringProp("Question for the user"))
        },
        tool("done", "Mark the task complete.") {
            put("summary", stringProp("One-sentence summary of what was done"))
        },
        tool("back", "Press the system Back button.") {},
        tool("home", "Press the system Home button.") {},
        tool("recents", "Open the Recents / overview screen (system recent apps).") {},
    )

    private fun tool(
        name: String,
        description: String,
        props: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) = buildJsonObject {
        put("name", name)
        put("description", description)
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties", props)
        }
    }

    private fun stringProp(desc: String, required: Boolean = true) = buildJsonObject {
        put("type", "string")
        put("description", desc)
    }

    private fun numberProp(desc: String, required: Boolean = true) = buildJsonObject {
        put("type", "number")
        put("description", desc)
    }

    private fun boolProp(desc: String, required: Boolean = true) = buildJsonObject {
        put("type", "boolean")
        put("description", desc)
    }
}
