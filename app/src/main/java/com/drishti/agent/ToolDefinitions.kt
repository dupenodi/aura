package com.drishti.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * What Aura may do, which is only ever: point at one thing and say what to do with it.
 *
 * Every tool here except [speak] and `done` becomes an on-screen instruction and then
 * waits for the user's own finger. Nothing touches the phone.
 */
object ToolDefinitions {
    val tools = listOf(
        tool(
            "point_at",
            "Point the cursor at the element with this overlay index and tell the user to " +
                "tap it. This is the main tool — use it for buttons, rows, icons and links.",
        ) {
            put("index", numberProp("Overlay index from the accessibility tree"))
            put(
                "label",
                stringProp(
                    "What to call it in the instruction, in the user's words, e.g. \"Wi-Fi\"",
                    required = false,
                ),
            )
        },
        tool(
            "type",
            "Tell the user what to type, pointing at the field they should type it into.",
        ) {
            put("text", stringProp("Exactly what they should type"))
            put("index", numberProp("Overlay index of the text field", required = false))
        },
        tool("scroll", "Tell the user to scroll the screen to reveal more.") {
            put("direction", stringProp("down|up (default down)", required = false))
        },
        tool(
            "open_app",
            "Tell the user to open an app. Points at its icon when it is on screen. " +
                "Prefer point_at when the app or setting is already visible.",
        ) {
            put("package_name", stringProp("Android package name"))
        },
        tool("back", "Tell the user to press Back.") {},
        tool("home", "Tell the user to go to the home screen.") {},
        tool("speak", "Say one short sentence to the user without asking them to do anything.") {
            put("text", stringProp("Short phrase to say"))
        },
        tool("done", "The user has reached what they asked for, or has gone as far as they can.") {
            put("summary", stringProp("One short sentence for the user"))
        },
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
}
