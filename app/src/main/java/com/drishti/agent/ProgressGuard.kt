package com.drishti.agent

/**
 * Catches the model repeating itself.
 *
 * Being pointed at the same button — or told the same sentence — three times is how a
 * guided session turns from help into nagging, so the session stops instead.
 */
class ProgressGuard(
    private val repeatLimit: Int = 2,
    /** `done` ends the session anyway, so it can never be a loop. */
    private val nonProgressTools: Set<String> = setOf("done"),
) {
    var repeatActionStreak: Int = 0
        private set

    private var lastActionKey: String = ""

    /** @return true when the same step has been shown too many times. */
    fun record(name: String, args: String): Boolean {
        if (name in nonProgressTools) return false
        val key = "$name:$args"
        if (key == lastActionKey) {
            repeatActionStreak++
        } else {
            repeatActionStreak = 0
            lastActionKey = key
        }
        return repeatActionStreak >= repeatLimit
    }
}
