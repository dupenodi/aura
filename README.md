# Drishti

On-device Android screen assistant POC. Aura **shows** the user how to do things: it reads the accessibility tree (indexing ported in-process from [mobilerun-portal](https://github.com/droidrun/mobilerun-portal) — **not** a droidrun dependency), and an LLM loop picks the single next step, moves a cursor onto it, and waits for the user's own finger. It never taps, types or swipes for them.

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

- **Tap** → type what you need help with
- **Hold** → say it instead; release to send
- **Drag** → park the orb at either edge

Each step dims the screen, rings the one thing to press, glides the cursor onto it and says
what to do. The next step only comes once you have done it.

## Architecture (short)

| Layer | Role |
|-------|------|
| `ScreenAgentAccessibilityService` | Portal-style tree indexing, read-only (no gestures, no screenshots) |
| `LlmRouter` | Multi-provider chat (local + cloud) |
| `AgentOrchestrator` | One step per turn: observe → ask the model → show it (cap 20 steps) |
| `ToolExecutor` | Turns a step into cursor + instruction, then waits for the user |
| `PointerOverlay` | Spotlight, neon ring and cursor drawn in absolute screen coordinates |
| `TreeJson.movedOn` | Decides whether the user actually did it, ignoring cosmetic redraws |

## Explicit non-goals

No multilingual support, no cloud sync, no Play Store packaging, no third-party mobile-automation frameworks.
