package com.drishti.agent

object SafetyGate {
    private val KEYWORDS = listOf(
        "payment", "upi", "bank", "otp", "delete", "uninstall",
        "factory reset", "grant permission", "purchase",
        "buy now", "send money", "confirm payment", "pay now",
        "transfer money", "cvv",
    )

    fun isSensitive(toolName: String, argsText: String, targetText: String = ""): Boolean {
        val hay = "$toolName $argsText $targetText".lowercase()
        return KEYWORDS.any { hay.contains(it) }
    }
}
