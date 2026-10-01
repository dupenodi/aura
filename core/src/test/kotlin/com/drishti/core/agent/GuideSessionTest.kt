package com.drishti.core.agent

import com.drishti.core.llm.LlmException
import com.drishti.core.sim.GoldenTask
import com.drishti.core.sim.GoldenTasks
import com.drishti.core.sim.OraclePlanner
import com.drishti.core.sim.PixelWorld
import com.drishti.core.sim.RecordingVoice
import com.drishti.core.sim.SimPhone
import com.drishti.core.sim.SimUser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GuideSessionTest {

    private class Run(
        val result: SessionResult,
        val phone: SimPhone,
        val user: SimUser,
        val voice: RecordingVoice,
        val planner: StepPlanner,
    )

    private suspend fun TestScope.run(
        task: GoldenTask,
        behavior: SimUser.Behavior = SimUser.Behavior(),
        planner: ((SimPhone) -> StepPlanner)? = null,
        config: GuideConfig = GuideConfig(),
        language: Language = Language.English,
        phone: SimPhone = GoldenTasks.prepare(task),
    ): Run {
        val user = SimUser(phone, backgroundScope, behavior).also { it.clock = { testScheduler.currentTime } }
        val voice = RecordingVoice()
        val p = planner?.invoke(phone) ?: OraclePlanner(task, phone)
        val session = GuideSession(
            task = task.prompt, language = language, device = phone, planner = p, ui = user, voice = voice,
            config = config, clock = { testScheduler.currentTime },
        )
        return Run(session.run(), phone, user, voice, p)
    }

    @Test
    fun everyGoldenTaskCompletesWithAPerfectGuide() = runTest {
        val failures = mutableListOf<String>()
        for (task in GoldenTasks.all) {
            val r = run(task)
            val ok = task.succeeded(r.phone, r.result)
            if (!ok || r.result.steps > task.optimalSteps + 1) {
                failures += "${task.id}: ok=$ok outcome=${r.result.outcome} steps=${r.result.steps} " +
                    "(optimal ${task.optimalSteps}) at=${r.phone.current.name} history=${r.result.history.map { it.line(0) }}"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun aClickOnTheRingedRowCompletesTheStepImmediately() = runTest {
        val r = run(GoldenTasks.byId("battery_saver"))
        assertEquals(Outcome.Completed, r.result.outcome)
        assertTrue(r.result.history.all { it.outcome == "done" }, r.result.history.toString())
        // Speech is cut the moment they act rather than talking over the next screen.
        assertTrue(r.voice.stops >= r.result.steps)
    }

    @Test
    fun theNextStepAppearsWithinASecondOfTheUserActing() = runTest {
        val r = run(GoldenTasks.byId("dark_mode"))
        // With an instant planner, the gap is all harness: settle + read. It has to be short.
        r.result.metrics.drop(1).forEach { m ->
            assertTrue(m.settleMs <= GuideConfig().settleMaxMs, "settle took ${m.settleMs}ms")
        }
        val acted = r.user.actedAt
        assertTrue(acted.size >= 2)
    }

    @Test
    fun appsWithoutClickEventsAreFollowedByWatchingTheScreen() = runTest {
        // YouTube in the sim raises no click events, like many Compose apps.
        val task = GoldenTasks.byId("yt_search")
        val r = run(task)
        assertTrue(task.succeeded(r.phone, r.result), r.result.history.toString())
        assertTrue(r.result.history.none { it.outcome.contains("paused") })
    }

    @Test
    fun scrollingRevealsRowsBelowTheFold() = runTest {
        val r = run(GoldenTasks.byId("android_version"))
        assertEquals(Outcome.Completed, r.result.outcome)
        assertTrue(r.result.history.any { it.shown.startsWith("scroll") && it.outcome == "they scrolled" }, r.result.history.toString())
    }

    @Test
    fun typingIsDoneWhenTheTextMatches() = runTest {
        val task = GoldenTasks.byId("wa_message_rahul")
        val r = run(task)
        assertTrue(task.succeeded(r.phone, r.result), r.result.history.toString())
        assertTrue(r.result.history.any { it.outcome.startsWith("they typed") })
    }

    @Test
    fun aWrongPressIsFollowedNotScolded() = runTest {
        val task = GoldenTasks.byId("dark_mode")
        val r = run(task, behavior = SimUser.Behavior(wrongTapSteps = setOf(1)))
        assertTrue(r.result.history.first().outcome.startsWith("they pressed"), r.result.history.toString())
        val oracle = r.planner as OraclePlanner
        assertTrue(oracle.notes.any { it?.contains("instead of what you showed") == true })
        assertTrue(task.succeeded(r.phone, r.result), "recovers and finishes: ${r.result.history}")
    }

    @Test
    fun aPressThatMissesAndDoesNothingGetsImmediateHelp() = runTest {
        // Step 2 is "Battery Saver"; the miss lands on "Battery usage" just above it, which
        // is pressable but goes nowhere. (A miss on plain text raises no event at all — on a
        // real phone too — so there is nothing to react to; they simply try again.)
        val task = GoldenTasks.byId("battery_saver")
        val r = run(task, behavior = SimUser.Behavior(wrongTapSteps = setOf(2)))
        assertTrue(r.voice.said.contains(Phrases.get(Phrase.NotQuite, Language.English)), r.voice.said.toString())
        assertEquals(1, r.user.emphasized)
        assertTrue(task.succeeded(r.phone, r.result), r.result.history.toString())
    }

    @Test
    fun hesitationRepeatsThenReassuresThenPausesAndResumes() = runTest {
        val task = GoldenTasks.byId("battery_saver")
        val phone = GoldenTasks.prepare(task)
        val user = SimUser(phone, backgroundScope, SimUser.Behavior(ignoreSteps = setOf(1)))
        val voice = RecordingVoice()
        val session = GuideSession(
            task.prompt, Language.English, phone, OraclePlanner(task, phone), user, voice,
            clock = { testScheduler.currentTime },
        )
        val job = backgroundScope.launch { session.run().also { result = it } }
        advanceTimeBy(13_000)
        assertEquals(2, voice.said.count { it == "Tap Battery" }, "said again after 12s: ${voice.said}")
        advanceTimeBy(20_000)
        assertEquals(1, user.emphasized)
        assertTrue(voice.said.contains(Phrases.get(Phrase.StillThere, Language.English)))
        advanceTimeBy(30_000)
        assertTrue(session.paused)
        assertEquals(1, user.pausedCount)

        session.resume()
        advanceTimeBy(60_000)
        job.join()
        val r = assertNotNull(result)
        assertEquals(Outcome.Completed, r.outcome, r.history.toString())
        assertTrue(phone.state.on("saver"))
    }

    private var result: SessionResult? = null

    @Test
    fun aPausedSessionNobodyReturnsToEndsQuietly() = runTest {
        val task = GoldenTasks.byId("battery_saver")
        val r = run(task, behavior = SimUser.Behavior(ignoreSteps = setOf(1)))
        assertEquals(Outcome.Stopped, r.result.outcome)
        assertNull(r.result.message)
    }

    @Test
    fun anInvalidReferenceIsSentBackAndEscalated() = runTest {
        val task = GoldenTasks.byId("battery_saver")
        val inputs = mutableListOf<PlanInput>()
        val r = run(task, planner = { phone ->
            val oracle = OraclePlanner(task, phone)
            object : StepPlanner {
                override suspend fun plan(input: PlanInput): Planned {
                    inputs += input
                    if (inputs.size == 1) return Planned(Step(Action.Tap, "Tap it", target = 999), "m", 0)
                    return oracle.plan(input)
                }
            }
        })
        assertEquals(Outcome.Completed, r.result.outcome)
        assertTrue(inputs[1].note!!.contains("There is no [999]"))
        assertTrue(inputs[1].hard, "a bad step escalates to the stronger model")
        assertTrue(!inputs[2].hard, "and drops back once things work")
    }

    @Test
    fun threeBadStepsInARowStopWithAPlainMessage() = runTest {
        val r = run(GoldenTasks.byId("battery_saver"), planner = {
            object : StepPlanner {
                override suspend fun plan(input: PlanInput) = Planned(Step(Action.Tap, "Tap", target = 999), "m", 0)
            }
        })
        assertEquals(Outcome.Stopped, r.result.outcome)
        assertEquals(Phrases.get(Phrase.NotSure, Language.English), r.result.message)
        assertEquals(0, r.result.steps)
    }

    @Test
    fun repeatingTheSameStepOnTheSameScreenIsCaught() = runTest {
        // "Battery usage" opens nothing in the sim: pressing it leaves the same screen. A
        // model that keeps pointing at it gets one escalation, then the session stops.
        val task = GoldenTasks.byId("battery_saver")
        val phone = PixelWorld.phone().also { it.startAt("settings", "battery") }
        val r = run(task, phone = phone, planner = {
            object : StepPlanner {
                override suspend fun plan(input: PlanInput): Planned {
                    val t = input.screen.targets.values.first { it.label == "Battery usage" }
                    return Planned(Step(Action.Tap, "Tap Battery usage", target = t.ref), "m", 0)
                }
            }
        })
        assertEquals(Outcome.Stopped, r.result.outcome)
        assertEquals(Phrases.get(Phrase.GoingInCircles, Language.English), r.result.message)
        assertEquals(2, r.result.steps)
    }

    @Test
    fun sensitiveAppsStopBeforeAnythingIsRead() = runTest {
        val task = GoldenTask("pay", "Pay the electricity bill", start = listOf("stub_phonepe"), optimalSteps = 0, succeeded = { _, _ -> true })
        val phone = PixelWorld.phone().also { it.startAt("stub_phonepe") }
        var planned = 0
        val r = run(task, phone = phone, planner = {
            object : StepPlanner {
                override suspend fun plan(input: PlanInput): Planned { planned++; error("must not plan") }
            }
        })
        assertEquals(Outcome.Stopped, r.result.outcome)
        assertEquals(Phrases.get(Phrase.SensitiveApp, Language.English), r.result.message)
        assertEquals(0, planned)
    }

    @Test
    fun aRetryableModelErrorIsRetriedOnTheOtherModel() = runTest {
        val task = GoldenTasks.byId("battery_saver")
        val hardFlags = mutableListOf<Boolean>()
        val r = run(task, planner = { phone ->
            val oracle = OraclePlanner(task, phone)
            object : StepPlanner {
                override suspend fun plan(input: PlanInput): Planned {
                    hardFlags += input.hard
                    if (hardFlags.size == 1) throw LlmException(LlmException.Kind.RateLimit, "429")
                    return oracle.plan(input)
                }
            }
        })
        assertEquals(Outcome.Completed, r.result.outcome)
        assertEquals(listOf(false, true), hardFlags.take(2))
    }

    @Test
    fun aBadKeyStopsWithWordsNotCodes() = runTest {
        val r = run(GoldenTasks.byId("battery_saver"), planner = {
            object : StepPlanner {
                override suspend fun plan(input: PlanInput): Planned = throw LlmException(LlmException.Kind.Auth, "HTTP 401: {}", 401)
            }
        })
        assertEquals(Phrases.get(Phrase.NoModel, Language.English), r.result.message)
        assertTrue(r.voice.said.none { it.contains("401") })
    }

    @Test
    fun fixedLinesAreSpokenInTheChosenLanguage() = runTest {
        val r = run(GoldenTasks.byId("battery_saver"), language = Language.Hindi, planner = {
            object : StepPlanner {
                override suspend fun plan(input: PlanInput): Planned = throw LlmException(LlmException.Kind.Network, "offline")
            }
        })
        assertEquals(Phrases.get(Phrase.Offline, Language.Hindi), r.result.message)
        assertTrue(r.voice.said.last().contains("इंटरनेट"))
    }

    @Test
    fun declinesScamsWithoutGuidingAnywhere() = runTest {
        for (id in listOf("scam_anydesk", "scam_otp")) {
            val task = GoldenTasks.byId(id)
            val r = run(task)
            assertTrue(task.succeeded(r.phone, r.result), id)
            assertTrue(r.user.shown.isEmpty())
        }
    }

    @Test
    fun cancellingMidStepTakesTheRingDown() = runTest {
        val task = GoldenTasks.byId("battery_saver")
        val phone = GoldenTasks.prepare(task)
        var hides = 0
        val user = object : GuideUi by SimUser(phone, backgroundScope, SimUser.Behavior(ignoreSteps = setOf(1))) {
            override fun hide() { hides++ }
        }
        val session = GuideSession(task.prompt, Language.English, phone, OraclePlanner(task, phone), user, RecordingVoice(), clock = { testScheduler.currentTime })
        val job = backgroundScope.launch { session.run() }
        advanceTimeBy(5_000)
        val before = hides
        job.cancel()
        job.join()
        assertTrue(hides > before)
    }

    @Test
    fun screenshotsReachThePlannerWhenAskedFor() = runTest {
        val task = GoldenTasks.byId("battery_saver")
        var shots = 0
        val phone = object : com.drishti.core.agent.Device by GoldenTasks.prepare(task) {
            override suspend fun screenshot(): Screenshot { shots++; return Screenshot(ByteArray(1), 1, 1) }
        }
        val inputs = mutableListOf<PlanInput>()
        val session = GuideSession(
            task.prompt, Language.English, phone,
            object : StepPlanner {
                override suspend fun plan(input: PlanInput): Planned { inputs += input; return Planned(Step(Action.Cannot, "no"), "m", 0) }
            },
            SimUser(GoldenTasks.prepare(task), backgroundScope), RecordingVoice(),
            config = GuideConfig(screenshots = ScreenshotPolicy.Always), clock = { testScheduler.currentTime },
        )
        session.run()
        assertEquals(1, shots)
        assertNotNull(inputs.single().screenshot)
    }
}
