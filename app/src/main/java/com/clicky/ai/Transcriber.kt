package com.clicky.ai

import com.clicky.BuildConfig
import com.clicky.debug.RingBufferLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speech-to-text fallback order:
 * Sarvam (Saaras v3) → Deepgram Nova-2 → OpenAI `/v1/audio/transcriptions`.
 */
@Singleton
class Transcriber @Inject constructor(
    private val json: Json,
) {
    private val sarvamClient: OkHttpClient by lazy {
        SarvamClient.createOkHttp()
    }

    private val deepgramClient: OkHttpClient by lazy {
        DeepgramClient.createOkHttp()
    }

    private val openAiClient: OkHttpClient by lazy {
        OpenAiClient.createOkHttp(BuildConfig.OPENAI_API_KEY).newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun hasApiKey(): Boolean =
        SarvamClient.hasApiKey() ||
            DeepgramClient.hasApiKey() ||
            BuildConfig.OPENAI_API_KEY.isNotBlank()

    fun providerHint(): String = when {
        SarvamClient.hasApiKey() -> "Sarvam (Saaras v3) → Deepgram → OpenAI"
        DeepgramClient.hasApiKey() -> "Deepgram (Nova-2) → OpenAI fallback"
        BuildConfig.OPENAI_API_KEY.isNotBlank() -> "OpenAI"
        else -> "none"
    }

    suspend fun transcribe(wavFile: File): Result<TranscriptionResult> = withContext(Dispatchers.IO) {
        if (!hasApiKey()) {
            return@withContext Result.failure(
                IllegalStateException(
                    "No STT key — add SARVAM_API_KEY, DEEPGRAM_API_KEY, and/or OPENAI_API_KEY " +
                        "to local.properties and rebuild.",
                ),
            )
        }
        if (!wavFile.exists() || wavFile.length() == 0L) {
            return@withContext Result.failure(IllegalStateException("Empty audio file"))
        }

        RingBufferLogger.log(
            "stt",
            "transcribe file=${wavFile.name} bytes=${wavFile.length()} via=${providerHint()}",
        )

        val errors = mutableListOf<String>()

        if (SarvamClient.hasApiKey()) {
            val sarvam = runCatching {
                val result = SarvamClient.transcribeWav(sarvamClient, wavFile)
                RingBufferLogger.log(
                    "stt",
                    "sarvam transcript=${result.transcript.take(200)} lang=${result.languageCode}",
                )
                TranscriptionResult(
                    text = result.transcript,
                    languageCode = result.languageCode?.let { SarvamClient.normalizeTtsLanguage(it) },
                    translatedText = result.translatedText?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
            if (sarvam.isSuccess) return@withContext sarvam
            val msg = sarvam.exceptionOrNull()?.message ?: "unknown"
            errors += "Sarvam: $msg"
            RingBufferLogger.log("stt", "sarvam failed ($msg); trying next")
        }

        if (DeepgramClient.hasApiKey()) {
            val dg = runCatching {
                val text = DeepgramClient.transcribeWav(deepgramClient, wavFile)
                RingBufferLogger.log("stt", "deepgram transcript=${text.take(200)}")
                TranscriptionResult(text = text, languageCode = null)
            }
            if (dg.isSuccess) return@withContext dg
            val msg = dg.exceptionOrNull()?.message ?: "unknown"
            errors += "Deepgram: $msg"
            RingBufferLogger.log("stt", "deepgram failed ($msg); trying OpenAI")
        }

        if (BuildConfig.OPENAI_API_KEY.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException(
                    buildSttFailureMessage(errors, openAiAvailable = false),
                ),
            )
        }

        runCatching {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", OPENAI_MODEL)
                .addFormDataPart(
                    "file",
                    wavFile.name,
                    wavFile.asRequestBody("audio/wav".toMediaType()),
                )
                .build()
            val request = Request.Builder()
                .url(OpenAiClient.BASE_URL + "v1/audio/transcriptions")
                .post(body)
                .build()
            openAiClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("STT HTTP ${response.code}: ${raw.take(300)}")
                }
                val parsed = json.decodeFromString(TranscriptionResponse.serializer(), raw)
                val text = parsed.text?.trim().orEmpty()
                if (text.isEmpty()) error("STT returned empty transcript")
                RingBufferLogger.log("stt", "openai transcript=${text.take(200)}")
                TranscriptionResult(text = text, languageCode = null)
            }
        }.onFailure {
            errors += "OpenAI: ${it.message ?: "unknown"}"
            RingBufferLogger.log("stt", "error=${it.message}")
        }.recoverCatching {
            throw IllegalStateException(buildSttFailureMessage(errors, openAiAvailable = true))
        }
    }

    private fun buildSttFailureMessage(errors: List<String>, openAiAvailable: Boolean): String {
        val detail = if (errors.isEmpty()) "no provider succeeded" else errors.joinToString("; ")
        return if (!openAiAvailable && !SarvamClient.hasApiKey() && !DeepgramClient.hasApiKey()) {
            "No STT key — add SARVAM_API_KEY (preferred), DEEPGRAM_API_KEY, or OPENAI_API_KEY " +
                "to local.properties and rebuild."
        } else {
            "STT failed ($detail). Ensure SARVAM_API_KEY / DEEPGRAM_API_KEY / OPENAI_API_KEY " +
                "are set in local.properties and rebuild."
        }
    }

    @Serializable
    private data class TranscriptionResponse(
        val text: String? = null,
    )

    companion object {
        const val OPENAI_MODEL = "gpt-4o-mini-transcribe"
    }
}

data class TranscriptionResult(
    val text: String,
    /** Normalized BCP-47 when Sarvam (or similar) detected speech language. */
    val languageCode: String? = null,
    /** Optional English (or other) translation from Sarvam when provided. */
    val translatedText: String? = null,
)
