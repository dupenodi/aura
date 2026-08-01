package com.drishti.agent

/**
 * Turns technical failures into something worth saying out loud.
 *
 * The user should never see an HTTP code or a JSON blob from a provider — it tells them
 * nothing they can act on and makes a normal hiccup feel like a crash. Each case here
 * says what happened and, where there is one, what they can do about it.
 */
object AgentErrors {

    /** A short sentence for the bubble. Never contains raw provider output. */
    fun humanise(raw: String?): String {
        val text = raw.orEmpty()
        return when {
            text.contains("401") || text.contains("UNAUTHENTICATED", true) ||
                text.contains("api key", true) ->
                "My language model key isn't working. Check it in the app settings."

            text.contains("402") || text.contains("credit", true) ||
                text.contains("quota", true) || text.contains("billing", true) ->
                "My language model is out of credit. Top it up and I'll pick straight back up."

            text.contains("403") || text.contains("PERMISSION_DENIED", true) ->
                "My language model refused the request — the account doesn't have access."

            text.contains("429") || text.contains("rate", true) ->
                "I'm being rate limited. Give me a minute and try again."

            text.contains("timeout", true) || text.contains("timed out", true) ->
                "That took too long to come back. Try again in a moment."

            text.contains("Unable to resolve host", true) ||
                text.contains("UnknownHost", true) ||
                text.contains("Failed to connect", true) ||
                text.contains("network", true) ->
                "I can't reach the internet right now."

            text.contains("No LLM providers configured", true) ->
                "No language model is set up yet."

            else -> "Something went wrong on my side. Nothing was changed."
        }
    }
}
