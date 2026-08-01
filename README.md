# Drishti

On-device Android screen assistant POC. Accessibility tree + gestures follow the same device-side automation logic as [mobilerun-portal](https://github.com/droidrun/mobilerun-portal) (ported in-process — **not** a droidrun dependency). An LLM tool-use loop plans and executes multi-step tasks.

> **POC only.** API keys live on-device (`local.properties` → BuildConfig, optional EncryptedSharedPreferences). Move keys behind a backend proxy before any real distribution.

## LLM providers

Set in `local.properties` (see `local.properties.example`):

| Key | Purpose |
|-----|---------|
| `LLM_PROVIDER` | `auto` (default), or force `local` / `openrouter` / `anthropic` / `openai` |
| `LOCAL_LLM_BASE_URL` | OpenAI-compatible base, e.g. `http://127.0.0.1:11434/v1` |
| `LOCAL_LLM_MODEL` | Model id for local server |
| `OPENROUTER_API_KEY` / `OPENROUTER_MODEL` | OpenRouter |
| `ANTHROPIC_API_KEY` / `ANTHROPIC_MODEL` | Anthropic Messages API |
| `OPENAI_API_KEY` / `OPENAI_MODEL` | OpenAI Chat Completions |

**Auto** tries, in order: **local → openrouter → anthropic → openai** (skips anything not configured). Failures fall through to the next provider.

### Local inference (Ollama / LM Studio)

```bash
# On the host
ollama serve   # or start LM Studio server on :1234

# USB phone → host port (recommended)
adb reverse tcp:11434 tcp:11434

# local.properties
LOCAL_LLM_BASE_URL=http://127.0.0.1:11434/v1
LOCAL_LLM_MODEL=llama3.2
LLM_PROVIDER=local   # or leave as auto
```

Emulator: use `http://10.0.2.2:11434/v1`. Same Wi‑Fi: use your machine’s LAN IP.

## Run history / analysis

Every agent task writes a folder under app-private storage:

```
files/runs/<runId>/
  manifest.json      # task, status, step counts
  events.jsonl       # observe / llm / tool / stuck timeline
  summary.txt        # human-readable timeline
  trees/observe_NNN.json
  shots/observe_NNN.png
```

Open **Run history** from the home screen or long-press the bubble → **Run history / snapshots**.

Pull the latest run to your machine:

```bash
adb shell run-as com.drishti ls files/runs
adb exec-out run-as com.drishti tar -c files/runs | tar -x -C /tmp/drishti-runs
```

Use these snapshots to compare tree changes across commands before changing automation reliability.

## Build & install

```bash
./gradlew installDebug
```

## Permissions

1. **Accessibility** — Settings → Accessibility → Drishti → On  
2. **Display over other apps** — grant for `com.drishti`  
3. **Microphone** — for voice input  

Then open Drishti → **Show summon bubble**.

## Using the bubble

- **Tap** → text field, **Speak task**, canned task: *Open WhatsApp and go to the first chat*
- **Long-press** → settings + last-run action log
- Before each tap, a highlight shows the target for ~500ms

## Architecture (short)

| Layer | Role |
|-------|------|
| `ScreenAgentAccessibilityService` | Portal-style tree indexing, `inputText`, screenshots |
| `GestureController` | Coordinate tap / swipe / global via `dispatchGesture` |
| `LlmRouter` | Multi-provider chat (local + cloud) |
| `AgentOrchestrator` | Tool loop (cap 20 steps, stuck detection) |
| `VisionFallback` | Sparse-tree → attach screenshot |
| `SafetyGate` | Confirm for payment / delete / OTP / etc. |

## Explicit non-goals

No multilingual support, no cloud sync, no Play Store packaging, no third-party mobile-automation frameworks.
