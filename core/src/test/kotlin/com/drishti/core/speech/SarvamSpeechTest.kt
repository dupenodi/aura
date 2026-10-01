package com.drishti.core.speech

import com.drishti.core.agent.Language
import com.drishti.core.llm.LlmException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SarvamSpeechTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    @BeforeTest
    fun start() {
        server = MockWebServer().also { it.start() }
    }

    @AfterTest
    fun stop() = server.shutdown()

    private fun wsBase() = "ws://${server.hostName}:${server.port}"

    /** Speaks Sarvam's realtime protocol: echoes partials while audio comes in, final on speech_end. */
    private inner class FakeRealtime(private val finalText: String, private val language: String? = null) : WebSocketListener() {
        val received = CopyOnWriteArrayList<String>()
        val audio = Buffer()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"event":"session.begin","request_id":"r1","config":{"model":"saaras:v4"}}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val m = Json.parseToJsonElement(text).jsonObject
            val event = m["event"]!!.jsonPrimitive.content
            received += event
            when (event) {
                "audio_input" -> {
                    audio.write(Base64.getDecoder().decode(m["audio"]!!.jsonPrimitive.content))
                    webSocket.send("""{"event":"transcript.partial","utterance_idx":0,"text":"${finalText.take(audio.size.toInt().coerceAtMost(finalText.length))}"}""")
                }
                "speech_end" -> {
                    val lang = language?.let { ""","language":"$it","language_confidence":0.9""" } ?: ""
                    webSocket.send("""{"event":"transcript.final","utterance_idx":0,"text":"$finalText"$lang}""")
                }
                "end" -> webSocket.close(1000, "bye")
            }
        }
    }

    private val pcm = listOf(ByteArray(3200) { 1 }, ByteArray(3200) { 2 }, ByteArray(3200) { 3 })

    @Test
    fun holdToTalkStreamsAudioAndReturnsTheFinal() = runBlocking {
        val fake = FakeRealtime("WhatsApp पर Amma को video call करो")
        server.enqueue(MockResponse().withWebSocketUpgrade(fake))
        val stt = SarvamRealtimeStt({ "k" }, http, wsBase())
        val partials = mutableListOf<String>()
        val result = stt.transcribe(
            flow { pcm.forEach { emit(it); delay(20) } },
            SarvamRealtimeStt.Config(Language.Hindi, keyterms = listOf("WhatsApp", "Amma")),
            onPartial = { partials += it },
        )
        assertEquals("WhatsApp पर Amma को video call करो", result.text)
        assertTrue(partials.isNotEmpty())
        assertEquals(listOf("speech_start", "audio_input", "audio_input", "audio_input", "speech_end"), fake.received.take(5))
        assertContentEquals(pcm.reduce { a, b -> a + b }, fake.audio.readByteArray())

        val path = server.takeRequest().requestUrl!!
        assertEquals("hi-IN", path.queryParameter("language_code"))
        assertEquals("saaras:v4", path.queryParameter("model"))
        assertEquals("manual", path.queryParameter("endpointing"))
        assertEquals("16000", path.queryParameter("sample_rate"))
        assertEquals("""["WhatsApp","Amma"]""", path.queryParameter("keyterms"))
    }

    @Test
    fun odiaUsesTheRealtimeSpellingAndAutoDetectReportsTheLanguage() = runBlocking {
        val stt = SarvamRealtimeStt({ "k" }, http, wsBase())
        assertTrue(stt.url(SarvamRealtimeStt.Config(Language.Odia)).contains("language_code=or-IN"))

        val fake = FakeRealtime("hello", language = "ta-IN")
        server.enqueue(MockResponse().withWebSocketUpgrade(fake))
        val result = stt.transcribe(flow { emit(pcm[0]) }, SarvamRealtimeStt.Config(language = null))
        assertEquals(Language.Tamil, result.language)
        assertEquals("auto", server.takeRequest().requestUrl!!.queryParameter("language_code"))
    }

    @Test
    fun keytermsAreOnlySentToV4() {
        val stt = SarvamRealtimeStt({ "k" }, http, wsBase())
        assertTrue(!stt.url(SarvamRealtimeStt.Config(Language.English, model = "saaras:v3-realtime", keyterms = listOf("x"))).contains("keyterms"))
    }

    @Test
    fun aRejectedKeyFailsSoTheCallerCanFallBack() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))
        val stt = SarvamRealtimeStt({ "bad" }, http, wsBase())
        val e = assertFailsWith<LlmException> {
            stt.transcribe(flow { emit(pcm[0]); delay(200); emit(pcm[1]) }, SarvamRealtimeStt.Config(Language.English))
        }
        assertEquals(LlmException.Kind.Auth, e.kind)
    }

    @Test
    fun noFinalFallsBackToTheLastPartial() = runBlocking {
        val silentFinal = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("audio_input")) webSocket.send("""{"event":"transcript.partial","utterance_idx":0,"text":"turn on wifi"}""")
                if (text.contains("\"end\"")) webSocket.close(1000, "bye")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(silentFinal))
        val stt = SarvamRealtimeStt({ "k" }, http, wsBase())
        val result = stt.transcribe(flow { emit(pcm[0]); delay(100) }, SarvamRealtimeStt.Config(Language.English, finalTimeoutMs = 300))
        assertEquals("turn on wifi", result.text)
    }

    @Test
    fun restSttSendsAWavWithTheRestOdiaCode() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"request_id":"x","transcript":"ବ୍ଲୁଟୁଥ ଚାଲୁ କର","language_code":"od-IN"}"""))
        val stt = SarvamRestStt({ "k" }, http, server.url("/").toString().trimEnd('/'))
        val result = stt.transcribe(pcm[0], 16_000, Language.Odia)
        assertEquals(Language.Odia, result.language)
        val req = server.takeRequest()
        val body = req.body.readUtf8()
        assertEquals("/speech-to-text", req.path)
        assertEquals("k", req.getHeader("api-subscription-key"))
        assertTrue(body.contains("od-IN"))
        assertTrue(body.contains("saaras:v4"))
        assertTrue(body.contains("RIFF"))
    }

    @Test
    fun ttsStreamsPcmAndAsksForBulbulV3Explicitly() = runBlocking {
        val audio = ByteArray(9_999) { (it % 256).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(audio)).setChunkedBody(Buffer().write(audio), 1_000))
        val tts = SarvamTts({ "k" }, http, server.url("/").toString().trimEnd('/'))
        val out = Buffer()
        var chunks = 0
        tts.speak("Wi-Fi दबाइए", Language.Hindi, SarvamTts.VoiceConfig()) { out.write(it); chunks++; assertEquals(0, it.size % 2) }
        // The odd trailing byte of an incomplete sample is never played.
        assertContentEquals(audio.copyOf(audio.size - 1), out.readByteArray())
        val req = server.takeRequest()
        assertEquals("/text-to-speech/stream", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("bulbul:v3", body["model"]!!.jsonPrimitive.content)
        assertEquals("hi-IN", body["language_code"]!!.jsonPrimitive.content)
        assertEquals("linear16", body["output_audio_codec"]!!.jsonPrimitive.content)
        assertEquals("priya", body["speaker"]!!.jsonPrimitive.content)
    }

    @Test
    fun ttsFallsBackToRestWhenStreamingIsRefused() = runBlocking {
        val pcmOut = ByteArray(480) { 7 }
        val wav = Wav.wrap(pcmOut, 24_000)
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"error":"codec"}"""))
        server.enqueue(MockResponse().setBody("""{"request_id":"x","audios":["${Base64.getEncoder().encodeToString(wav)}"]}"""))
        val tts = SarvamTts({ "k" }, http, server.url("/").toString().trimEnd('/'))
        val got = Buffer()
        val rate = tts.speak("ok", Language.English, SarvamTts.VoiceConfig()) { got.write(it) }
        assertEquals(24_000, rate)
        assertContentEquals(pcmOut, got.readByteArray())
        assertEquals("/text-to-speech/stream", server.takeRequest().path)
        assertEquals("/text-to-speech", server.takeRequest().path)
    }

    @Test
    fun wavRoundTrip() {
        val pcm = ByteArray(100) { it.toByte() }
        val wav = Wav.wrap(pcm, 16_000)
        assertEquals(44, Wav.dataOffset(wav))
        assertEquals(16_000, Wav.sampleRate(wav))
        assertContentEquals(pcm, Wav.pcmOf(wav))
        assertEquals(0, Wav.dataOffset(pcm))
    }
}
