# Aura — from hackathon POC to a product that works on real Android phones

Written 2026-10-01 after a full read of the codebase and Sarvam's current API surface.

**Status (2026-10-01):** decisions made — OpenRouter for models, Sarvam for speech,
screenshots allowed, Pixel 6 as the test phone, distribution later. Speed, the harness and
testing came first, so the work went 0 → 2 → 3 with Phase 1's simulator built alongside:

- Done: `:core` module; compact screen encoder; event-driven guide session (off-path
  recovery, hesitation ladder with pause/resume, "not quite" feedback); OpenRouter/Anthropic
  clients with forced tool calls, caching and fallbacks; Sarvam saaras:v4 realtime STT and
  bulbul:v3 streaming TTS wired into the app; simulated Pixel + 20 golden tasks + fuzzing;
  live eval runner; session recorder; CI workflow; accessibility service rewritten (no more
  250 ms main-thread polling).
- UI: Geist + lowercase design system; onboarding with all three permissions; one-page
  settings. The floating orb is gone: an edge **handle** (tap to type, hold to talk, drag
  along the edge), an **edge glow** in the aura colours while it listens / works / guides,
  and an **instruction dock** above the navigation bar that moves up when the target is at
  the bottom. Aura can be the phone's digital assistant (hold power, corner swipe) and then
  listens hands-free with an energy endpointer.
- Not yet verified on a device: everything in `:app` compiles against the Android 15
  framework, but has not run on a phone. The first real-device pass is the next step.
- Still open: rest of Phase 4 (light theme, localisation, OEM battery killers), keys behind a server, real-device recordings as eval
  fixtures.

---

## 1. What Aura is today

Aura is a floating orb. The user taps it (or holds it and speaks) and says what they want to do
("make the text bigger", "video call my son on WhatsApp"). Aura reads the screen through an
AccessibilityService, asks an LLM for the **single next step**, dims the screen, glides a cursor
onto the thing to press and says the instruction. It waits until the user presses it, then repeats.
It never taps for the user. That *guide-only* principle is the product, and this plan keeps it.

The bones are good. Guide-only is a strong, defensible idea, the overlay work is careful (see
`docs/EDGE_CASES.md`), stopping and cancelling are handled, and provider fallback exists. What's
missing is everything that makes it reliable, fast, multilingual and measurable on real phones.

---

## 2. What I found (ranked by user impact)

| # | Problem | Evidence | Why it matters |
|---|---------|----------|----------------|
| 1 | **Sarvam STT/TTS is never used.** The provider setting is stored, but voice always goes through the platform `SpeechRecognizer` and `TextToSpeech`. | `RemoteSpeech` is referenced only by the settings screen. `VoiceSession` and `SpeechOutput` never call it. | The main reason to use Sarvam (Indian-language accuracy) never reaches the user. |
| 2 | **The dead Sarvam code targets retired APIs.** | Uses `saarika:v2` (deprecated in favour of the Saaras family), `bulbul:v1` + speaker `meera` (not in the current speaker list), and the old `inputs: []` TTS body. | Turning it on as-is would fail. |
| 3 | **Instructions are always English.** | The model is never told the user's language. `ToolExecutor` builds `"Tap $label"` and `"Slide your finger up…"` in English, then speaks them with whatever voice matches the chosen language. | A Hindi user hears English words read in a Hindi voice. This breaks the core promise. |
| 4 | **Step detection is guesswork.** | It polls the tree every 500 ms and calls the step done when 20% of a hashed signature changes on two polls. The service subscribes only to `windowStateChanged/ContentChanged`. | It can't tell "tapped the right thing" from "tapped the wrong thing" or "pulled down the notification shade". Android reports these directly through `TYPE_VIEW_CLICKED` and related events, but Aura ignores them. |
| 5 | **Slow, expensive turns.** | Each turn sends the full nested JSON tree (resourceId, className, bounds strings, every container) plus the last ~3 stale trees. There's no prompt caching, no streaming, and the default model is the old `claude-sonnet-4-20250514`. | Latency is time the user stares at a frozen screen. For older users, a few seconds of nothing reads as "it's broken". |
| 6 | **The session ends on hesitation.** | After 30 s with no change, the session ends with "I'll leave you here". | Older users often take longer than 30 s. They then have to start over from scratch. |
| 7 | **There's no way to measure quality.** | 5 unit tests in total, none of them on the agent loop. No recordings, no replays, no success-rate number. | Every prompt or model change is a guess. This is the single biggest blocker to "works super well". |
| 8 | **Real-world Android blockers are unhandled.** | Onboarding doesn't cover Android 13+ **Restricted settings** (sideloaded apps can't enable accessibility without an extra unlock). Nothing handles OEM battery killers (Xiaomi/HyperOS, Oppo/ColorOS, Vivo, Realme, the dominant brands in India). | On most Indian phones the service will be killed or never enabled. |
| 9 | **The UI has accessibility gaps for the target user.** | Several text tokens fail WCAG contrast on the dark background (e.g. `TextBarely #46445A` and `TextMuted #5E5C72` on `#0B0B13`). There's no light theme. UI and overlay copy is hardcoded English in Kotlin (~95 literal sentences vs 4 `R.string` lookups). | The audience is older, often low-vision, often non-English-first. |
| 10 | **Smaller correctness bugs.** | `ToolDefinitions` ignores its `required` flag, so schemas have no `required` array. `SensitiveApps` keyword matching misses major Indian finance apps (e.g. HDFC `com.snapwork.hdfc`, CRED `com.dreamplug.androidapp`). Odia uses `od-IN` for REST STT/TTS but `or-IN` for realtime STT. API keys are compiled into `BuildConfig`. | Each one is a trust or security bug waiting to happen. |

---

## 3. Research: the underlying tech

### 3.1 Screen understanding (AccessibilityService)

- **The tree is the right primary signal.** It's cheap, needs no screenshot permission and works
  offline, and it gives exact bounds for the cursor. The weak spots are WebViews, Flutter/Unity/game
  surfaces and Compose views without semantics. There the tree is sparse or unlabeled.
- **Events are the right "did they do it" signal.** `TYPE_VIEW_CLICKED`, `TYPE_VIEW_LONG_CLICKED`,
  `TYPE_VIEW_TEXT_CHANGED`, `TYPE_VIEW_SCROLLED`, `TYPE_WINDOWS_CHANGED` and
  `TYPE_WINDOW_STATE_CHANGED` carry the **source node**, so we can compare it with the node we pointed
  at. Polling becomes a fallback rather than the mechanism.
- **Settling** should be event-driven. Wait until content-change events go quiet for ~250 ms (capped
  at ~1.5 s), not a fixed 400 ms delay.
- **Screenshots** (`AccessibilityService.takeScreenshot`, API 30+) are the only fix for sparse
  trees. The README currently promises "no screenshots", so this is a product decision (§8, Q2).

### 3.2 Sarvam speech (current API, verified against the official `sarvamai` SDK v0.1.35 of 2026-09-29)

I couldn't reach `docs.sarvam.ai` from this environment because the egress proxy blocks it. The
Python SDK is generated from Sarvam's API spec, so I read it directly, and it matches public reports
of Saaras v4 (released late Sept 2026) and Bulbul v3.

**Speech-to-text, realtime (the one we want for hold-to-talk):**
- `wss://api.sarvam.ai/speech-to-text-realtime/ws`, auth header `Api-Subscription-Key`.
- Query: `language_code` (required; `auto` for detection, 23 codes incl. `or-IN` for Odia),
  `model` = `saaras:v3-realtime` (default) | `saaras:v4`, `stream_type` = `fast` | `balanced` |
  `simulated`, `endpointing` = `vad` | `manual`, `encoding` = `linear16`, `sample_rate` = `16000`,
  `mode` = `transcribe` | `translate` | `verbatim` | `translit` | `codemix`, `prompt`, and
  `keyterms` (JSON array, ≤50 terms, **v4 only**).
- Client → server: `{"event":"audio_input","audio":"<b64 pcm>"}`, `speech_start` / `speech_end`
  (manual endpointing), `flush`, `config.update`, `ping`, `end`.
- Server → client: `session.begin`, `vad.speech_start/end`,
  `transcript.partial {utterance_idx, text}`, `transcript.final {text, language, …}`,
  `error {code, is_fatal}`, `session.end`. Close codes: `1003` key/quota, `1008` idle, `1011`
  server, `4000` bad config.
- **Fit for Aura:** use `endpointing=manual`. Press sends `speech_start`, release sends `speech_end`,
  and the final arrives. That maps exactly onto the existing hold-to-talk gesture, and partials
  stream into the bubble as they do today. Pass installed app labels plus words like Wi-Fi and
  Bluetooth as `keyterms`, so "वॉट्सऐप पर वीडियो कॉल" resolves to WhatsApp. `mode=codemix` keeps
  brand names in Latin script.

**Speech-to-text, REST (fallback):** `POST https://api.sarvam.ai/speech-to-text` (multipart).
`model` = `saaras:v3` (default) | `saaras:v4`, the same `mode` values, ≤30 s audio, `language_code`
including `unknown`. Odia is `od-IN` here, not `or-IN`.

**Text-to-speech:**
- REST `POST /text-to-speech`: `text` (≤2500 chars on v3), `language_code`, `model=bulbul:v3`,
  `speaker` (37 v3 voices; default `shubh`; lowercase), `pace` 0.5–2.0, `temperature` 0.01–2.0
  (default 0.6), `speech_sample_rate` up to 48 kHz, `output_audio_codec`, `dict_id` (pronunciation
  dictionary). `pitch` and `loudness` are **v2 only**.
- HTTP streaming `POST /text-to-speech/stream`: same body, audio bytes stream back.
- WebSocket `wss://api.sarvam.ai/text-to-speech/ws?model=bulbul:v3&send_completion_event=true`.
  **The default model there is still `bulbul:v2`, so we must pass `bulbul:v3` explicitly.** Send a
  `config` message once (language, speaker, pace, sample rate, mp3 bitrate), then `text` + `flush`
  per utterance. You get back `audio {content_type, audio(b64)}` chunks and a `final` event.
- **TTS covers 11 languages** (bn, en, gu, hi, kn, ml, mr, od, pa, ta, te). STT covers 23. For the
  other STT languages, fall back to the platform voice and say so in settings.
- **Fit for Aura:** keep one TTS socket warm for the whole guide session, so each step's
  instruction starts playing in a few hundred ms. Default `pace` around 0.9 for older listeners.
  Add an on-disk LRU cache for repeated phrases.

### 3.3 The LLM loop

- **Context.** A compact, line-per-element screen format (`[12] button "Wi-Fi" on · row 3`) is
  typically 5–10× smaller than the current JSON. Only the *current* screen needs to be there in full.
  History can be a short step log.
- **Caching.** System prompt, tool definitions and the installed-apps block are stable for a
  session, so they're ideal for Anthropic `cache_control`. Gemini and OpenAI cache automatically
  when the prefix is stable.
- **Model tiering.** Use a fast model per step (Claude Haiku 4.5 or Gemini Flash) and escalate to a
  stronger model (Claude Sonnet 5.5) after an off-path tap, a stale target or a repeated step.
  Exact defaults will be picked from eval numbers, not guessed.
- **Structured step output.** Each step returns `{target, instruction (in the user's language),
  expects (what should appear next), notes (short running plan)}`. `expects` lets the verifier and
  the next turn check that the step worked.

### 3.4 Android platform realities (2026)

- Target **SDK 36**, which Play requires for updates. That means edge-to-edge, predictive back, and
  the current foreground-service rules (`specialUse` needs a Play declaration).
- **Restricted settings (Android 13+).** Sideloaded installs must go through App info → ⋮ → *Allow
  restricted settings* before accessibility can be enabled. Onboarding has to walk through it.
- **OEM battery killers.** We need per-OEM deep links (autostart, battery "no restrictions"),
  detection of when the service has been killed, and a gentle re-enable flow.
- **Play policy risk.** `isAccessibilityTool="true"` is meant for apps whose primary purpose is
  helping people with disabilities. Aura is close to that, but it needs a clear Play declaration and
  a prominent disclosure screen. Worth deciding before we design onboarding copy (§8, Q4).

---

## 4. Target architecture

```
:core      (pure Kotlin/JVM, no android.*)  ← testable here and in CI, no emulator
  screen/    ScreenState model, compact serializer, element matching
  agent/     loop state machine, prompt builder, step schema, verifier, progress guard
  llm/       ChatBackend + Anthropic / OpenAI-compatible clients, router, caching, retries
  speech/    Sarvam STT/TTS protocol clients (OkHttp WebSocket), message codecs
  eval/      screen-graph simulator, scorers, replay runner
:app       (Android)
  a11y/      AccessibilityService → ScreenState; event stream → Verifier signals
  audio/     AudioRecord capture (16 kHz PCM16, NS/AGC), AudioTrack/MediaCodec playback, focus
  overlay/   orb, bubble, pointer (Views — overlay windows can't host Compose cheaply)
  ui/        Compose app: onboarding, home, settings, routines, history
  data/      prefs, history, routines, key storage (Keystore-backed)
```

**Guide session state machine (replaces today's `while` loop):**

`Observe → Decide → Show → Await → (Done | Next | OffPath | Hesitating → Paused)`

- **Await** watches the event stream. A click on the target node (or its clickable ancestor) means
  *Next*. A click elsewhere means *OffPath*: no scolding, replan from wherever they are. A screen
  change with no click (back gesture, notification) is also *Next* or *OffPath*, depending on
  whether `expects` matches.
- **Hesitating** works as a ladder instead of a cliff: repeat the instruction at ~12 s, enlarge the
  ring and rephrase at ~30 s, ask "still there?" at ~60 s, then **pause** with the session resumable
  from the orb. It never silently throws away progress.
- **Barge-in.** TTS stops the instant the user acts or touches the orb.

---

## 5. Phased plan

Each phase ships on its own and ends with something you can install and try.

### Phase 0 — Foundations (no visible change)
- Upgrade AGP, Kotlin, Compose BOM and AndroidX. Move to `compileSdk`/`targetSdk` 36 with
  edge-to-edge and predictive back.
- Extract `:core` (pure Kotlin) and move the existing pure logic and its tests there.
- Add GitHub Actions CI: build debug APK, unit tests, Android lint, ktlint. Upload the APK as a
  build artifact so every push is installable.
- Fix the small bugs: tool `required` arrays, Odia code mapping.
- **Done when:** CI is green, the APK installs, behaviour is unchanged.

### Phase 1 — The measuring stick (eval harness)
- **Recorder** (debug builds only): saves each session as JSONL, with the ScreenState per step,
  accessibility events, model I/O, token counts and timings. Exportable from a debug screen.
- **Screen-graph simulator** in `:core`. Recorded screens become nodes and taps become edges. The
  real agent loop runs against it on the JVM, with the user played by a scripted "follows the
  instruction / taps wrong thing / hesitates" policy.
- **Golden task set**, ~40 tasks to start: Settings (font size, Wi-Fi, Bluetooth, brightness,
  ringtone), WhatsApp (video call, send photo), YouTube, Camera, Gallery, Phone, Contacts. Recorded
  on at least 2 OEM skins (Samsung One UI + one of Xiaomi/Oppo/Vivo).
- **Scorecard:** task success %, steps vs optimal, wrong-target rate, p50/p95 decision latency,
  tokens and cost per task. One command: `./gradlew :core:eval`.
- **Done when:** we have a baseline number for today's agent.

### Phase 2 — Harness rebuild (reliability + speed)
- Compact ScreenState serializer: roles, labels from text/contentDescription/hint/resource-id,
  states (checked/selected/disabled), list and section context, scroll affordances, dialogs, IME.
- Event-driven verifier and settle detection (§3.1). Add the event types to the service config.
- State machine with off-path recovery and the hesitation ladder (§4).
- Structured step schema with `instruction` written **in the user's language** and `expects`.
- Prompt caching, model tiering with escalation, per-provider timeouts, retries with jitter and a
  circuit breaker. Defaults updated to current models.
- Context: current screen in full plus a compact step log, with no stale trees.
- **App/OEM knowledge packs:** short, versioned hints retrieved by package and task, e.g. "on
  HyperOS font size lives under Display → Text size". This is where most real failures are.
- Safety additions for an elderly audience: a curated finance/health package list (India-first, not
  just keyword matching). Detect scam patterns, such as being asked to read out an OTP or install
  AnyDesk or TeamViewer "because someone on the phone said so", and warn in plain words.
- **Done when:** the eval beats baseline on success rate *and* p50 latency, with the target
  numbers set after Phase 1.

### Phase 3 — Sarvam voice, done properly
- `SarvamRealtimeStt` over WebSocket: `saaras:v4`, `endpointing=manual`, `mode=codemix`, app-name
  `keyterms`, live partials in the bubble. 16 kHz mono PCM16 from `AudioRecord`
  (`VOICE_RECOGNITION` source, with noise suppression and AGC where available).
- Fallback chain: Sarvam realtime → Sarvam REST (`saaras:v4`, ≤30 s WAV) → platform recogniser
  (offline).
- `SarvamTts`: `bulbul:v3` over a warm WebSocket during a session, with REST as fallback and the
  platform voice as the last resort. Phrase cache. A speaker picker with audio previews, and a
  pace setting.
- Audio focus, ducking, barge-in, Bluetooth headset routing, and a hands-free option
  (`endpointing=vad`) for people who can't hold the orb.
- Tests: protocol codecs and the reconnect/fallback logic against OkHttp `MockWebServer`.
- **Done when:** a Hindi or Tamil speaker can hold, speak, and hear every step back in their
  language. Time from release to the first audible word is measured and logged.

### Phase 4 — UI that's top notch (and kind to older eyes)
- **Design system v2:** keep the Aura identity (orb, glow, Space Grotesk), but every text/background
  pair meets WCAG AA (≥4.5:1). Add a light theme, body text ≥18sp, touch targets ≥56dp, layouts that
  hold up at 200% font scale, TalkBack labels, and reduced-motion support.
- **Localisation:** move all copy into `strings.xml` and ship the 11 Sarvam-TTS languages. Any
  language not covered by human review is marked "beta".
- **Onboarding:** language first (shown in its own script), then voice-narrated setup, the restricted
  settings walkthrough, the OEM battery/autostart walkthrough, and a "try it now" practice task. A
  service-health check re-runs whenever Aura notices it was killed.
- **Overlay polish:** spring-physics cursor and a ring that hugs the element's shape. An animated
  swipe hint for scroll steps, and an arrow when the target is under the keyboard or off screen. The
  instruction card never covers the target. Haptics on step change. Pause/resume from the orb.
- **Home:** one big "Ask Aura" button, localised suggestion chips, history with a "do it again"
  option, and routines.
- Verified with Compose screenshot tests (light, dark, 200% font scale, Hindi, Tamil) in CI.

### Phase 5 — Ship-ready
- Keys out of the APK (see §8, Q1), plus R8 and baseline profiles for release builds.
- On-device privacy review: what leaves the phone, retention, and a one-tap "forget everything".
- Play readiness: accessibility and FGS declarations, a prominent disclosure, and a data-safety
  form draft.

---

## 6. How each phase is verified

- **This container** can't download the Android SDK (`dl.google.com` is blocked), so it can't build
  the APK. It can run everything in `:core`: unit tests, Sarvam protocol tests and the eval
  simulator. That's a main reason `:core` is pure Kotlin.
- **CI (GitHub Actions)** builds the APK, runs lint and tests, and publishes the APK artifact on
  every push.
- **Real-device testing is on you** (or a device farm). I'll give each phase a short checklist of
  what to try, and the recorder makes your sessions useful as new eval fixtures.

---

## 7. What I am deliberately *not* proposing

- Making Aura tap or type for the user. Guide-only stays.
- A third-party automation framework. The in-process tree indexing is fine.
- Fully on-device LLMs for now. Phones in the target price band can't run a model good enough at
  this task fast enough yet. The `local` provider stays for development.

---

## 8. Decisions I need from you

1. **API keys.** (a) a thin backend proxy (e.g. a Cloudflare Worker) that holds the LLM and Sarvam
   keys and issues per-install tokens, *recommended for anything beyond your own phone*; or
   (b) bring-your-own-key, stored in Android Keystore and entered in settings. (b) is faster to
   ship, (a) is required before strangers use it.
2. **Screenshots as an opt-in fallback** for screens whose tree is empty (WebViews, Flutter, games).
   This changes the "no screenshots" promise in the README. My recommendation: off by default, never
   on sensitive apps, never stored, and clearly disclosed.
3. **Default LLM provider.** Which keys do you actually have? I'll pick defaults from eval results,
   but I need at least one cloud provider configured in CI secrets to run evals.
4. **Distribution.** Play Store, or sideload/APK only? This decides how much of Phase 5 matters and
   how onboarding handles restricted settings.
5. **Test devices.** Which phones (brand/model/Android version) do you have? Golden recordings
   should come from those.
6. **Order.** I'd do 0 → 1 → 2 → 3 → 4 → 5. If you want voice in users' hands sooner, Phase 3 can
   run right after Phase 0, but then we'd tune the harness without numbers.
