package com.drishti.voice

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.drishti.MainActivity
import com.drishti.agent.AgentLoop
import com.drishti.agent.AgentRunStatus
import com.drishti.agent.AgentState
import com.drishti.ai.SarvamClient
import com.drishti.ai.Speaker
import com.drishti.ai.Transcriber
import com.drishti.ai.TranscriptionResult
import com.drishti.debug.RingBufferLogger
import com.drishti.overlay.BubbleState
import com.drishti.overlay.OverlayService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bubble tap → listen → STT → [AgentLoop.run]. Handles interrupt / cancel UX.
 *
 * Push-to-talk: first tap starts listening; second tap stops and sends.
 * States: listening → processing (STT) → thinking (agent).
 * Tap while busy cancels the turn.
 */
@Singleton
class VoiceCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recorder: VoiceRecorder,
    private val transcriber: Transcriber,
    private val speaker: Speaker,
    private val agentLoop: AgentLoop,
    private val agentState: AgentState,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var sessionJob: Job? = null
    /** Ignore cancel taps briefly after overlay menu actions (touch can fall through). */
    @Volatile
    private var ignoreCancelUntilMs: Long = 0L

    /**
     * Suppress bubble-cancel for a short window so PopupMenu selection → dismiss
     * does not immediately cancel a just-started replay/agent job.
     */
    fun suppressCancelBriefly(ms: Long = 1_200L) {
        ignoreCancelUntilMs = System.currentTimeMillis() + ms
        RingBufferLogger.log("voice", "suppress cancel for ${ms}ms")
    }

    fun onBubbleTap() {
        val overlay = OverlayService.instance
        val now = System.currentTimeMillis()
        if (now < ignoreCancelUntilMs) {
            RingBufferLogger.log("voice", "bubble tap ignored (post-menu suppress)")
            return
        }
        val busy = agentLoop.isBusy ||
            agentState.ui.value.status == AgentRunStatus.Running ||
            speaker.isSpeaking ||
            overlay?.bubbleState == BubbleState.Thinking ||
            overlay?.bubbleState == BubbleState.Processing

        when {
            busy -> {
                RingBufferLogger.log("voice", "bubble tap → cancel busy turn")
                cancelAll(reason = "user interrupt")
                overlay?.setListening(false)
                overlay?.setProcessing(false)
                overlay?.setThinking(false)
                toast("Cancelled")
            }
            recorder.isRecording -> {
                RingBufferLogger.log("voice", "bubble tap → stop recording (send)")
                recorder.requestStop()
            }
            else -> startListening()
        }
    }

    fun cancelAll(reason: String) {
        RingBufferLogger.log("voice", "cancelAll reason=$reason")
        recorder.requestStop()
        speaker.interrupt()
        agentLoop.cancelCurrent()
        sessionJob?.cancel()
        sessionJob = null
        OverlayService.instance?.setAudioLevel(0f)
        OverlayService.instance?.setListening(false)
        OverlayService.instance?.setProcessing(false)
        OverlayService.instance?.setThinking(false)
    }

    /** Stop listen/STT without cancelling an in-flight or about-to-start agent/replay. */
    fun stopListeningSession(reason: String = "prepare replay") {
        RingBufferLogger.log("voice", "stopListeningSession reason=$reason")
        recorder.requestStop()
        sessionJob?.cancel()
        sessionJob = null
        OverlayService.instance?.setAudioLevel(0f)
        OverlayService.instance?.setListening(false)
        OverlayService.instance?.setProcessing(false)
    }

    fun shutdown() {
        cancelAll("shutdown")
        scope.cancel()
    }

    private fun startListening() {
        if (!recorder.hasRecordPermission()) {
            RingBufferLogger.log("voice", "RECORD_AUDIO missing")
            toast("Grant microphone permission in Drishti, then tap the bubble")
            runCatching {
                val i = Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_REQUEST_MIC, true)
                context.startActivity(i)
            }
            return
        }
        if (!transcriber.hasApiKey()) {
            toast(
                "Voice needs SARVAM_API_KEY (preferred), DEEPGRAM_API_KEY, or OPENAI_API_KEY " +
                    "in local.properties (rebuild).",
            )
            return
        }

        sessionJob?.cancel()
        speaker.interrupt()
        agentLoop.cancelCurrent()

        sessionJob = scope.launch {
            val overlay = OverlayService.instance
            overlay?.setThinking(false)
            overlay?.setProcessing(false)
            overlay?.setListening(true)
            toast("Listening… tap bubble to send")
            RingBufferLogger.log("voice", "listening… stt=${transcriber.providerHint()}")

            val wavResult = withContext(Dispatchers.IO) {
                recorder.recordToWav { level ->
                    OverlayService.instance?.setAudioLevel(level)
                }
            }
            overlay?.setAudioLevel(0f)
            overlay?.setListening(false)

            val wav = wavResult.getOrElse { e ->
                val msg = e.message ?: "Recording failed"
                RingBufferLogger.log("voice", "record failed: $msg")
                toast(msg)
                return@launch
            }

            if (recorder.lastEndReason == VoiceActivityDetector.EndReason.MaxDuration) {
                toast("Max listening time (30s) — sending…")
            }

            // Listening ended → processing (STT) before agent.
            overlay?.setProcessing(true)
            toast("Processing…")
            RingBufferLogger.log("voice", "processing (STT)…")

            val stt = withContext(Dispatchers.IO) {
                transcriber.transcribe(wav)
            }.getOrElse { e ->
                overlay?.setProcessing(false)
                val msg = e.message ?: "Transcription failed"
                RingBufferLogger.log("voice", "stt failed: $msg")
                toast("Transcription failed: $msg")
                runCatching { wav.delete() }
                return@launch
            }
            runCatching { wav.delete() }

            val transcript = stt.text
            if (transcript.isBlank()) {
                overlay?.setProcessing(false)
                toast("Heard nothing — try again")
                return@launch
            }

            if (stt.languageCode != null) {
                agentState.setLastDetectedLanguage(stt.languageCode)
            }

            // Kannada (and other Indic): bilingual LLM text when useful; TTS stays STT language via speak().
            val llmUtterance = formatUtteranceForLlm(stt)
            RingBufferLogger.log(
                "voice",
                "utterance=$transcript lang=${stt.languageCode ?: agentState.lastDetectedLanguage} " +
                    "llmChars=${llmUtterance.length}",
            )

            overlay?.setProcessing(false)
            overlay?.setThinking(true)

            val agentResult = agentLoop.run(llmUtterance)
            agentResult.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) {
                    RingBufferLogger.log("voice", "agent cancelled")
                } else {
                    val msg = e.message ?: "Agent failed"
                    RingBufferLogger.log("voice", "agent error=$msg")
                    toast(msg)
                }
            }
            overlay?.setThinking(false)
            overlay?.setProcessing(false)
        }
    }

    private fun toast(msg: String) {
        mainHandler.post {
            runCatching {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * When STT detects Kannada, give the planner bilingual context so tool args stay reliable
     * in English while [AgentState.lastDetectedLanguage] keeps speak()/TTS in Kannada.
     */
    private fun formatUtteranceForLlm(stt: TranscriptionResult): String {
        val text = stt.text.trim()
        if (text.isEmpty()) return text
        val lang = SarvamClient.normalizeTtsLanguage(stt.languageCode)
        val isKannada = lang.equals("kn-IN", ignoreCase = true) ||
            lang.startsWith("kn", ignoreCase = true)
        if (!isKannada) return text
        val translation = stt.translatedText?.trim().orEmpty()
        return buildString {
            append("User spoke Kannada.\n")
            append("Kannada: ")
            append(text)
            if (translation.isNotEmpty()) {
                append("\nEnglish translation: ")
                append(translation)
            }
            append(
                "\n(Use English for tool arguments and internal planning if helpful; " +
                    "speak() TTS text must stay in Kannada.)",
            )
        }
    }
}
