package com.drishti.core.agent

import com.drishti.core.sim.GoldenTasks
import com.drishti.core.sim.OraclePlanner
import com.drishti.core.sim.RecordingVoice
import com.drishti.core.sim.SimUser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Many sessions with people who don't follow instructions: they press the wrong row, ignore
 * a step, take their time. Whatever they do, a session must end on its own, in bounded time,
 * without crashing — and a session where they do follow along must still succeed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideFuzzTest {

    @Test
    fun sessionsAlwaysEndAndRecoverFromWrongPresses() = runTest {
        val rnd = Random(20261001)
        val problems = mutableListOf<String>()
        var recovered = 0
        var wrongRuns = 0
        val failed = mutableListOf<String>()
        repeat(RUNS) { run ->
            val task = GoldenTasks.all.filter { !it.shouldDecline }.random(rnd)
            val phone = GoldenTasks.prepare(task)
            val wrong = (1..6).filter { rnd.nextFloat() < 0.25f }.toSet()
            val ignore = if (rnd.nextFloat() < 0.1f) setOf(rnd.nextInt(1, 5)) else emptySet()
            val behavior = SimUser.Behavior(
                reactionMs = rnd.nextLong(200, 8_000),
                wrongTapSteps = wrong,
                ignoreSteps = ignore,
            )
            val user = SimUser(phone, backgroundScope, behavior)
            val session = GuideSession(
                task.prompt, Language.English, phone, OraclePlanner(task, phone), user, RecordingVoice(),
                config = GuideConfig(pausedGiveUpMs = 1),
                clock = { testScheduler.currentTime },
            )
            val result = runCatching { withTimeout(30 * 60_000) { session.run() } }
            val r = result.getOrNull()
            if (r == null) {
                problems += "#$run ${task.id} $behavior: ${result.exceptionOrNull()}"
                return@repeat
            }
            if (r.steps > GuideConfig().maxSteps) problems += "#$run ${task.id}: ${r.steps} steps"
            if (wrong.isNotEmpty() && ignore.isEmpty()) {
                wrongRuns++
                if (task.succeeded(phone, r)) recovered++
                else if (failed.size < 12) failed += "${task.id} wrong=$wrong → ${r.outcome} '${r.message}' ${r.history.map { it.where + ": " + it.shown + " → " + it.outcome }}"
            }
            if (wrong.isEmpty() && ignore.isEmpty() && !task.succeeded(phone, r)) {
                problems += "#$run ${task.id} clean run failed: ${r.outcome} ${r.history}"
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
        // A perfect guide should get people back on track after a wrong press nearly always.
        assertTrue(recovered >= wrongRuns * 9 / 10, "recovered $recovered of $wrongRuns runs with wrong presses\n" + failed.joinToString("\n"))
    }

    private companion object {
        const val RUNS = 300
    }
}
