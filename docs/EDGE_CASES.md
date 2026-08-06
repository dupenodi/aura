# Aura — edge cases

Where trust is won or lost. Each case lists what the user sees, and where it is handled
in code so the behaviour can be re-checked when things change.

## Understanding the request

| # | Case | What Aura does | Where |
|---|------|----------------|-------|
| 1 | Request is vague ("make text bigger") | Never asks — looks at the screen and takes its best next step. Being interrogated is worse than a step the user can ignore | `SystemPrompt` ("Never ask them a question"), no `ask_user` tool exists |
| 2 | User names an app installed under a different package | Resolved by label and package containment before giving up | `DeviceContext.resolve`, used by `open_app` |
| 3 | Model claims an installed app is missing | Prevented: the installed-app list is in the first message, with an explicit instruction never to say that | `AgentOrchestrator.runLoop` first message |
| 4 | App genuinely isn't installed | Says so once and stops; does not offer to install | `ToolExecutor` `open_app` branch |

## Showing the next step

| # | Case | What Aura does | Where |
|---|------|----------------|-------|
| 5 | Every step | Cursor glides onto one element, screen dims around it, instruction is spoken and shown. Nothing is ever tapped for the user | `ToolExecutor.guide`, `PointerOverlay` |
| 6 | User doesn't follow the instruction within 30s | Repeats it once aloud at 18s, then ends the session in place ("I'll leave you here") — no retry, no alternative route, and history records it as stopped rather than done | `ToolExecutor.guide` timeout branch |
| 7 | Screen still settling when a step is shown (list bouncing, keyboard opening) | Waits for it to settle, then re-reads bounds from the live node before pointing | `ToolExecutor.GUIDING_TOOLS` settle, `ScreenAgentAccessibilityService.freshBoundsOf` |
| 8 | A clock ticks over or an animation redraws while waiting | Not mistaken for the user acting: a fifth of the screen has to change, on two consecutive polls | `TreeJson.movedOn`, `ToolExecutor.CONFIRM_POLLS`, `ScreenChangeTest` |
| 8a | Our own orb or highlight raises accessibility events | Ignored — they are our windows, and treating them as the foreground app made every step complete itself ~500ms after it was shown | `isForegroundPackageSignal`, `ForegroundPackageSignalTest` |
| 8b | The tree read momentarily comes back empty | Treated as "cannot see", not as the user acting; the wait continues | `TreeJson.movedOn` empty guard, `ScreenChangeTest` |
| 8c | Keyboard or status bar raises a window while waiting | A package change alone no longer counts — the screen contents have to differ too | `ToolExecutor.guide` `switchedApp` |
| 9 | Model points at the same thing twice running | Run stops rather than nagging | `ProgressGuard` |
| 10 | Model returns several steps in one turn | Only the first is shown — the rest were decided against a screen the user hasn't reached | `runLoop` takes `toolUses.first()` |
| 11 | Element index is stale by the time it is used | Says so back to the model and asks for something that is on screen now | `resolveTapTarget` null branch |
| 12 | Step limit reached | Stops and says so | `AGENT_MAX_STEPS` |

## Privacy and safety

| # | Case | What Aura does | Where |
|---|------|----------------|-------|
| 13 | Banking / health / password app in the foreground | Stops reading the screen entirely and says so | `SensitiveApps`, checked each loop in `AgentOrchestrator.runLoop` |
| 14 | Screenshots | None. The service does not request the screenshot capability at all | `accessibility_service_config.xml` |
| 15 | Anything the user does not want done | Structurally impossible — the app has no way to tap, type or swipe | no `dispatchGesture`, no `ACTION_CLICK`, no `ACTION_SET_TEXT` anywhere |
| 16 | History retention | Dropped after seven days on read, and deletable at any time | `TaskHistory.RETENTION_MS`, Privacy screen |
| 17 | User pauses Aura everywhere | Overlay service stops immediately | `AuraPrefs.paused` observed in `BubbleService.observePrefs` |

## The overlay itself

| # | Case | What Aura does | Where |
|---|------|----------------|-------|
| 18 | Orb dragged near an edge | Snaps to the nearer side, clamped on-screen | `BubbleService.snapToEdge` |
| 19 | Bubble would run off screen | Anchored to the orb's side and clamped, tail follows | `BubbleService.repositionBubble` |
| 20 | Highlight target near the bottom of the screen | Overlay window sized to the real display, not `MATCH_PARENT` (which stopped 63px short) | `PointerOverlay.realScreenSize` |
| 21 | Overlay window not positioned at screen origin | Drawing translated by `getLocationOnScreen`, so highlights never drift | `PointerOverlay.PointerView.onDraw` |
| 22 | Highlight replaced while an older one is fading out | Generation counter stops the stale hide from clearing the new target | `PointerOverlay.showGeneration` |
| 23 | Battery below 15% | Glow drops and animation stops; capability unchanged | `OrbView.lowPower`, `BubbleService.refreshPowerState` |
| 24 | Composer open when the user leaves | Full-screen scrim dismisses it on any outside tap; back closes it | `BubbleService.showComposer` |
| 25 | Keyboard covers the composer | Sheet lifts by the real IME inset (overlay windows ignore `ADJUST_RESIZE`) | `ViewCompat.setOnApplyWindowInsetsListener` in `showComposer` |

## Voice

| # | Case | What Aura does | Where |
|---|------|----------------|-------|
| 26 | Hold to talk without microphone permission | Says what it needs and opens the app to grant it | `BubbleService.beginListening` → `openMicPermission` |
| 27 | User drags instead of holding | Recording is cancelled, not sent | `cancelListening` on move past slop |
| 28 | Nothing said, or not understood | "I didn't catch that" — never an empty task | `VoiceSession.Failure.NoSpeech` |
| 29 | Recogniser unavailable on the device | Points the user at typing instead | `VoiceSession.Failure.Unavailable` |
| 30 | Chosen language has no installed TTS voice | Marked "No voice installed" in settings; speech falls back rather than failing | `SpeechOutput.applyLanguage`, `LanguageScreen` |
| 31 | Cloud speech engine selected without a key | Cannot be selected — shown as "Needs a key" | `RemoteSpeech.isConfigured`, `LanguageScreen` |

## The model and the network

| # | Case | What Aura does | Where |
|---|------|----------------|-------|
| 32 | Provider is out of credit / denied / rate limited | Plain sentence about what happened, never raw JSON or an HTTP code | `AgentErrors.humanise` |
| 33 | One provider fails | Falls through to the next configured provider before giving up | `LlmRouter.createMessage` chain |
| 34 | No provider configured at all | "No language model is set up yet" | `AgentErrors.humanise` |
| 35 | Model replies with prose and no tool call | Asked once to use a tool, then the run ends cleanly | `forcedToolRetry` in `runLoop` |
| 36 | Any message the model produces | Capped and single-line in the bubble, so nothing can flood the screen | `BubbleCardView.MAX_MESSAGE_CHARS` |

## Sessions and routines

| # | Case | What Aura does | Where |
|---|------|----------------|-------|
| 37 | User wants to stop mid-session | Tap the orb **or** the Stop chip on the helping bubble. Speech stops immediately. Hold-to-talk is disabled while a session is live so long-press can't steal Stop | `BubbleService.stopRun`, `sayHelping` Stop chip, orb `ACTION_UP` while `running`, `AgentOrchestrator.cancel` → `speechOutput.stop` |
| 37a | User pauses from the notification | "Pause Aura" sets `paused=true` (same as Privacy), stops the current run, and tears down the overlay until they unpause | `ACTION_STOP` in `BubbleService` |
| 37b | User pauses from Privacy | Overlay service stops immediately; stays off until unpaused | `AuraPrefs.paused` → `observePrefs` / `MainActivity` |
| 38 | User wonders what is going on | The bubble carries the current instruction (with Stop), and the orb pulses while the session is live | `sayHelping`, `OrbView.busy` |
| 39 | Accessibility service switched off mid-session | Says permission was lost and how to restore it — checked at each loop turn **and** every poll inside a guide wait | `runLoop` service null branch, `ToolExecutor.guide` mid-wait check |
| 40 | Routine run again | Runs the saved wording as a fresh session; the counter is bumped | `RoutineStore.recordRun`, `BubbleService.runTask` |

### Stopping — other outs

| # | Case | What Aura does | Where |
|---|------|----------------|-------|
| 41 | Stop while an LLM HTTP call is in flight | Coroutine cancel cancels the OkHttp `Call`; router does not fall through to the next provider | `HttpCalls.cancellableCall`, `LlmRouter` rethrows `CancellationException` |
| 42 | System Back on the Home screen | Cancels the live session (without pausing Aura), then backgrounds the app | `MainActivity` BackHandler → `BubbleService.cancelRun` |
| 43 | Composer opened from Home mid-run | Current run is stopped first, then the composer opens | `ACTION_COMPOSE` → `stopRun` then `showComposer` |
