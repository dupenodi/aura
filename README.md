# Aura (Drishti)

Aura is a glow at the edge of an Android screen that **shows** people how to do things on their
own phone. Ask it — by voice in any of 11 Indian languages (detected automatically), or by
typing — and it rings the one thing to press, says what to do, and waits for the person's own
finger. It never taps, types or swipes
for them. When they press the wrong thing it carries on from wherever they ended up; when they
hesitate it repeats, reassures, then waits instead of giving up.

## Build and install

```bash
cp local.properties.example local.properties   # add OPENROUTER_API_KEY and SARVAM_API_KEY
./gradlew installDebug
```

Then open aura; onboarding walks through the three permissions — accessibility (if its switch
is greyed out on a sideloaded build: app info → ⋮ → *allow restricted settings*), display over
other apps, and the microphone.

## How it works

```
:core  (plain Kotlin — builds and tests anywhere, no Android SDK)
  screen/   ScreenEncoder: accessibility tree → ~100-token list of pressable things, by reference
  agent/    GuideSession: observe → plan one step → show → watch the user → repeat
            StepResolver, Prompts, Phrases (fixed lines in 11 languages), SensitiveApps
  llm/      OpenRouter / OpenAI-compatible and Anthropic clients: one forced tool call per step,
            prompt caching, provider-side fallback, timeouts
  speech/   Sarvam realtime STT (saaras:v4, hold-to-talk) and streaming TTS (bulbul:v3)
  sim/      A simulated Pixel 6 (Settings, WhatsApp, YouTube, Phone, Clock), a scripted user,
            golden tasks — the test bed for everything above
:app   (Android)
  accessibility/  event stream + on-demand snapshots + screenshots, read-only
  agent/          GuideRunner, AndroidDevice
  overlay/        the aura glow, speech bubble, ring/cursor/swipe hint
  ui/             onboarding, home, settings (Compose, Geist, lowercase)
  voice/          AuraVoice (Sarvam → on-device TTS), hold-to-talk (Sarvam realtime → REST → on-device)
```

A step is done when the platform reports a click on the node Aura ringed (or, for apps that
report no clicks, when the screen changes accordingly). Each step is one small model call:
the system prompt (rules, language, installed apps) is identical all session and cached, and
the per-step message is the task, a short history and the current screen.

## Testing

```bash
./gradlew :core:test        # 52 tests, ~10 s, no SDK or network needed
./gradlew :core:eval        # real model on the simulated phone; prints a scorecard
./gradlew :core:eval --args="--models google/gemini-2.5-flash,openai/gpt-4.1-mini --runs 3"
./gradlew :core:eval --args="--language Hindi --tasks bluetooth_on,wa_video_amma"
./gradlew :core:eval --args="--speech"   # Sarvam TTS → realtime STT round trip
./gradlew :core:eval --args="--dump"     # every simulated screen as the model sees it
./gradlew :core:eval --args="--list-models"   # OpenRouter models that take tools + images
```

`:core:test` covers the encoder, every golden task end to end with a perfect guide, the
wire path through a fake model server, Sarvam's socket protocol against a fake server,
and a fuzz run of 300 sessions with people who press the wrong thing or wander off.

On the phone, every session is recorded to `files/sessions/*.jsonl` — each screen as the
model saw it, each step, timings and tokens:

```bash
adb shell run-as com.drishti ls files/sessions
adb shell run-as com.drishti cat files/sessions/<file>.jsonl
```

CI (`.github/workflows/ci.yml`) runs the core tests, builds the APK and lint on every push,
and runs the live eval on demand when `OPENROUTER_API_KEY` is set as a repository secret.

## Privacy

Screens in banking, payment, health and password apps are never read (enforced in code,
every step). Screen text — and a screenshot only when the text isn't enough, which can be
switched off — goes to the configured model to decide the next step; Aura stores nothing
off the phone. Keys are compiled into debug builds from `local.properties`; move them behind
a server before distributing the app to anyone else.
