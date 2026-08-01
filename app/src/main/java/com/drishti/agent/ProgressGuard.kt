package com.drishti.agent

/**
 * Tracks lack of progress: identical tool retries and unchanged tree fingerprints.
 * Shared by the orchestrator so stuck detection is unit-testable.
 */
class ProgressGuard(
    private val repeatLimit: Int = 2,
    private val noChangeLimit: Int = 3,
    private val nonProgressTools: Set<String> = setOf(
        "speak",
        "ask_user",
        "done",
        "wait_for_change",
    ),
) {
    var repeatActionStreak: Int = 0
        private set
    var noChangeStreak: Int = 0
        private set

    private var lastActionKey: String = ""
    private var lastFp: String = ""

    /** @return true when stuck due to repeated identical actions */
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

    /** @return true when stuck due to unchanged fingerprints */
    fun recordFingerprint(fingerprintAfter: String, name: String): Boolean {
        if (fingerprintAfter.isEmpty() || name in nonProgressTools) return false
        if (lastFp.isNotEmpty() && fingerprintAfter == lastFp) {
            noChangeStreak++
        } else {
            noChangeStreak = 0
        }
        lastFp = fingerprintAfter
        return noChangeStreak >= noChangeLimit
    }
}
