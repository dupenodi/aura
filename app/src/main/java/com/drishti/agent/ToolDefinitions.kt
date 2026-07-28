package com.drishti.agent

import com.drishti.ai.FunctionSpec
import com.drishti.ai.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OpenAI function schemas with strict: true.
 */
object ToolDefinitions {

    val all: List<ToolSpec> by lazy {
        listOf(
            tool(
                name = "observe_screen",
                description = "Capture the current UI accessibility tree. Default to include_image=false (tree-only). Set include_image=true only when truly needed: ambiguous similar rows, after open_app on a new screen, after type_text search, or before a risky checkout/pay tap — not between routine actions.",
                properties = buildJsonObject {
                    put(
                        "include_image",
                        prop(
                            "boolean",
                            "If true, include a JPEG for vision (ambiguous rows / post-search / post-open / risky pay). Prefer false.",
                        ),
                    )
                },
                required = listOf("include_image"),
            ),
            tool(
                name = "move_cursor",
                description = "Animate the on-screen cursor to absolute pixel coordinates.",
                properties = buildJsonObject {
                    put("x", prop("number", "Absolute X in device pixels"))
                    put("y", prop("number", "Absolute Y in device pixels"))
                    put("reason", prop("string", "Short reason for the move"))
                },
                required = listOf("x", "y", "reason"),
            ),
            tool(
                name = "tap",
                description = "Move cursor to (x,y), pulse, and dispatch a tap. Prefer tap_node when a node id exists.",
                properties = buildJsonObject {
                    put("x", prop("number", "Absolute X in device pixels"))
                    put("y", prop("number", "Absolute Y in device pixels"))
                },
                required = listOf("x", "y"),
            ),
            tool(
                name = "tap_node",
                description = "Tap a node from the latest observe_screen tree by id. Preferred over coordinate tap. For search fields / Edit/Search / small ambiguous bars: call annotate(node_id) on the same id first, then tap_node. On Swiggy/Zomato food-search goals, never tap the top address chip (e.g. Salarpuria Arena) — tap [SEARCH_FIELD] / Search for … instead. Runtime hard-redirects address taps to Search when possible.",
                properties = buildJsonObject {
                    put("node_id", prop("integer", "Node id from observe_screen tree"))
                },
                required = listOf("node_id"),
            ),
            tool(
                name = "long_press",
                description = "Long-press at absolute coordinates.",
                properties = buildJsonObject {
                    put("x", prop("number", "Absolute X"))
                    put("y", prop("number", "Absolute Y"))
                },
                required = listOf("x", "y"),
            ),
            tool(
                name = "swipe",
                description = "Swipe from (x1,y1) to (x2,y2).",
                properties = buildJsonObject {
                    put("x1", prop("number", "Start X"))
                    put("y1", prop("number", "Start Y"))
                    put("x2", prop("number", "End X"))
                    put("y2", prop("number", "End Y"))
                    put("duration_ms", prop("integer", "Duration in milliseconds"))
                },
                required = listOf("x1", "y1", "x2", "y2", "duration_ms"),
            ),
            tool(
                name = "scroll",
                description = "Scroll the focused/scrollable area.",
                properties = buildJsonObject {
                    put(
                        "direction",
                        enumProp("up", "down", "left", "right", description = "Scroll direction"),
                    )
                },
                required = listOf("direction"),
            ),
            tool(
                name = "type_text",
                description = "Type text into the focused editable field. After typing a search query, wait for the auto observation of results before tapping; prefer Accounts/People rows, never Reel thumbnails for profile goals.",
                properties = buildJsonObject {
                    put("text", prop("string", "Text to type"))
                },
                required = listOf("text"),
            ),
            tool(
                name = "press_key",
                description = "Press a system key / global action.",
                properties = buildJsonObject {
                    put(
                        "key",
                        enumProp(
                            "back",
                            "home",
                            "recents",
                            "notifications",
                            description = "System key",
                        ),
                    )
                },
                required = listOf("key"),
            ),
            tool(
                name = "open_app",
                description = "Open an installed app by fuzzy-matching its launcher label.",
                properties = buildJsonObject {
                    put("query", prop("string", "App name query, e.g. Settings"))
                },
                required = listOf("query"),
            ),
            tool(
                name = "annotate",
                description = "Draw a coaching annotation on the overlay canvas (allowed in COACH and PILOT). Prefer node_id from observe_screen so the highlight uses exact accessibility bounds. Before tapping search/Edit fields or similar bars, annotate that node_id first, then tap_node with the same id.",
                properties = buildJsonObject {
                    put(
                        "type",
                        enumProp(
                            "circle",
                            "arrow",
                            "highlight",
                            "callout",
                            "ripple",
                            description = "Annotation type",
                        ),
                    )
                    put(
                        "node_id",
                        nullableInteger(
                            "Preferred: node id from observe_screen. When set, overrides x/y with exact on-screen bounds/center.",
                        ),
                    )
                    put(
                        "x",
                        nullableNumber(
                            "Absolute screen X (circle/callout/ripple center, arrow from-X, highlight left). Nullable if node_id set.",
                        ),
                    )
                    put(
                        "y",
                        nullableNumber(
                            "Absolute screen Y (circle/callout/ripple center, arrow from-Y, highlight top). Nullable if node_id set.",
                        ),
                    )
                    put("x2", nullableNumber("Optional secondary X / to-X / right"))
                    put("y2", nullableNumber("Optional secondary Y / to-Y / bottom"))
                    put("label", nullableString("Optional label or callout text"))
                },
                required = listOf("type", "node_id", "x", "y", "x2", "y2", "label"),
            ),
            tool(
                name = "speak",
                description = "Speak/narrate a short status BEFORE each major action (open_app, tap, type_text, scroll). Returns immediately while TTS plays in the background — you may continue planning/acting while narrating. Put text in the user's language (e.g. Kannada if they spoke Kannada). Optional language BCP-47 (kn-IN, en-IN, hi-IN, …); null uses last detected STT language.",
                properties = buildJsonObject {
                    put("text", prop("string", "What to say (same language as the user when possible)"))
                    put(
                        "language",
                        nullableString(
                            "Optional BCP-47 TTS language, e.g. kn-IN, en-IN, hi-IN, ta-IN, te-IN. Null = last detected speech language.",
                        ),
                    )
                },
                required = listOf("text", "language"),
            ),
            tool(
                name = "get_preferences",
                description = "Return saved on-device user preferences (cab_app, food_app, language, notes). Call when booking cab/food without a named app, or before assuming defaults.",
                properties = buildJsonObject {},
                required = emptyList(),
            ),
            tool(
                name = "set_preference",
                description = "Save an on-device user preference that should persist across sessions. Call when the user explicitly prefers something (e.g. always use Uber → key=cab_app value=uber; use Swiggy → food_app=swiggy; speak Kannada → language=kn).",
                properties = buildJsonObject {
                    put(
                        "key",
                        prop(
                            "string",
                            "Preference key snake_case, e.g. cab_app, food_app, language, note",
                        ),
                    )
                    put(
                        "value",
                        prop(
                            "string",
                            "Preference value, e.g. uber, ola, swiggy, zomato, kn, en",
                        ),
                    )
                },
                required = listOf("key", "value"),
            ),
            tool(
                name = "wait",
                description = "Wait for a short duration before the next action.",
                properties = buildJsonObject {
                    put("ms", prop("integer", "Milliseconds to wait (capped)"))
                },
                required = listOf("ms"),
            ),
            tool(
                name = "finish",
                description = "End the agent turn with a short summary. Do NOT call after cart alone on Zepto/Blinkit/Swiggy — continue to address and pay first. Allowed on payment screen or when blocked on UPI/OTP/password.",
                properties = buildJsonObject {
                    put("summary", prop("string", "What was done or why you stopped"))
                },
                required = listOf("summary"),
            ),
        )
    }

    private fun tool(
        name: String,
        description: String,
        properties: JsonObject,
        required: List<String>,
    ): ToolSpec = ToolSpec(
        type = "function",
        function = FunctionSpec(
            name = name,
            description = description,
            strict = true,
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", properties)
                put(
                    "required",
                    buildJsonArray {
                        required.forEach { add(JsonPrimitive(it)) }
                    },
                )
                put("additionalProperties", false)
            },
        ),
    )

    private fun prop(type: String, description: String): JsonObject = buildJsonObject {
        put("type", type)
        put("description", description)
    }

    private fun enumProp(vararg values: String, description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
        put(
            "enum",
            JsonArray(values.map { JsonPrimitive(it) }),
        )
    }

    private fun nullableNumber(description: String): JsonObject = buildJsonObject {
        put("type", buildJsonArray {
            add(JsonPrimitive("number"))
            add(JsonPrimitive("null"))
        })
        put("description", description)
    }

    private fun nullableString(description: String): JsonObject = buildJsonObject {
        put("type", buildJsonArray {
            add(JsonPrimitive("string"))
            add(JsonPrimitive("null"))
        })
        put("description", description)
    }

    private fun nullableInteger(description: String): JsonObject = buildJsonObject {
        put("type", buildJsonArray {
            add(JsonPrimitive("integer"))
            add(JsonPrimitive("null"))
        })
        put("description", description)
    }
}
