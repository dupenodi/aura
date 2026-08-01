# Aura — edge cases

Where trust is won or lost. Each case lists what the user sees, and where it is handled
in code so the behaviour can be re-checked when things change.

## Understanding the request

| # | Case | What Aura does | Where |
|---|------|----------------|-------|
| 1 | Request is ambiguous ("cancel my gym membership" with two gyms) | Asks once, offering the real candidates as chips — never "I didn't get that" | `PromptImprover` returns `clarifying_question`; `AgentOrchestrator.resolveTask`; `ConfirmPromptOverlay.ask(options)` |
| 2 | Request is vague but actionable ("make text bigger") | Rewritten to the operation as Android labels it, no question asked | `PromptImprover.SYSTEM` |
| 3 | Prompt improver is slow, errors, or has no key | Silently uses the user's own words — never blocks a task | `PromptImprover.improve` (6s timeout, fail-open) |
| 4 | User names an app that is installed under a different package | Resolved by label and package containment before giving up | `DeviceContext.resolve`, used by `open_app` |
| 5 | Model claims an installed app is missing | Prevented: the installed-app list is in the first message, with an explicit instruction never to say that | `AgentOrchestrator.runLoop` first message |
| 6 | App genuinely isn't installed | Says so once and stops; does not offer to install | `ToolExecutor.openApp` |

## Acting on the screen

| # | Case | What Aura does | Where |
|---|------|----------------|-------|
| 7 | Guide mode | Cannot act at all — every acting tool becomes an instruction plus a highlight | `ToolExecutor.ACTING_TOOLS` gate → `ToolExecutor.guide` |
| 8 | User doesn't follow the instruction within 20s | Reports honestly, tells the model not to retry, ends the run leaving them in place | `ToolExecutor.guide` timeout branch |
| 9 | Screen moved between observing and acting (list settles, keyboard opens) | Bounds are re-read from the live node before pointing or tapping | `ScreenAgentAccessibilityService.freshBoundsOf` |
| 10 | App accepts `ACTION_SET_TEXT` then ignores it | Verified by re-reading the field; falls back to clipboard, then a real touch and retry | `setTextOnNode` + `verifyTextLanded` + touch retry in `inputText` |
| 11 | Same action repeated with no effect | Run stops with "couldn't find the next step" rather than looping | `ProgressGuard`, `markStuck` |
| 12 | Irreversible action (payment, delete) | Stops for confirmation in both modes, as chips | `SafetyGate.isSensitive` → `ConfirmPromptOverlay.confirm` |
| 13 | Step limit reached | Stops and says so; recorded as "took too many steps" | `AGENT_MAX_STEPS`, `stoppedDetail` |

## Privacy and safety

| # | Case | What Aura does | Where |
|---|------|----------------|-------|
| 14 | Banking / health / password app in the foreground | Stops reading the screen entirely and says so; Auto is impossible there | `LockedToGuide`, checked each loop in `AgentOrchestrator.runLoop` |
| 15 | Per-app override tries to enable Auto for a locked app | Ignored — `overrideFor` returns Guide for locked packages | `AuraPrefs.overrideFor` |
| 16 | Screenshots | Only captured when the vision fallback needs one, never written to disk | `AgentOrchestrator` passes `screenshotBase64 = null` |
| 17 | History retention | Dropped after seven days on read, and deletable at any time | `TaskHistory.RETENTION_MS`, Privacy screen |
| 18 | User pauses Aura everywhere | Overlay service stops immediately | `AuraPrefs.paused` observed in `BubbleService.observePrefs` |

## The overlay itself

| # | Case | What Aura does | Where |
|---|------|----------------|-------|
| 19 | Orb dragged near an edge | Snaps to the nearer side, clamped on-screen | `BubbleService.snapToEdge` |
| 20 | Bubble would run off screen | Anchored to the orb's side and clamped, tail follows | `BubbleService.repositionBubble` |
| 21 | Highlight target near the bottom of the screen | Overlay window sized to the real display, not `MATCH_PARENT` (which stopped 63px short) | `PointerOverlay.realScreenSize` |
| 22 | Overlay window not positioned at screen origin | Drawing translated by `getLocationOnScreen`, so highlights never drift | `PointerOverlay.PointerView.onDraw` |
| 23 | Screenshot taken for vision fallback | Overlay hides itself instantly so it never appears in the capture | `PointerOverlay.setDrawingEnabled` → `hideImmediate` |
| 24 | Battery below 15% | Glow drops and animation stops; capability unchanged | `OrbView.lowPower`, `BubbleService.refreshPowerState` |
| 25 | Composer open when the user leaves | Full-screen scrim dismisses it on any outside tap; back closes it | `BubbleService.showComposer` |
| 26 | Keyboard covers the composer | Sheet lifts by the real IME inset (overlay windows ignore `ADJUST_RESIZE`) | `ViewCompat.setOnApplyWindowInsetsListener` in `showComposer` |

## Voice

| # | Case | What Aura does | Where |
|---|------|----------------|-------|
| 27 | Hold to talk without microphone permission | Says what it needs and opens the app to grant it | `BubbleService.beginListening` → `openMicPermission` |
| 28 | User drags instead of holding | Recording is cancelled, not sent | `cancelListening` on move past slop |
| 29 | Nothing said, or not understood | "I didn't catch that" — never an empty task | `VoiceSession.Failure.NoSpeech` |
| 30 | Recogniser unavailable on the device | Points the user at typing instead | `VoiceSession.Failure.Unavailable` |
| 31 | Chosen language has no installed TTS voice | Marked "No voice installed" in settings; speech falls back rather than failing | `SpeechOutput.applyLanguage`, `LanguageScreen` |
| 32 | Cloud speech engine selected without a key | Cannot be selected — shown as "Needs a key" | `RemoteSpeech.isConfigured`, `LanguageScreen` |

## The model and the network

| # | Case | What Aura does | Where |
|---|------|----------------|-------|
| 33 | Provider is out of credit / denied / rate limited | Plain sentence about what happened, never raw JSON or an HTTP code | `AgentErrors.humanise` |
| 34 | One provider fails | Falls through to the next configured provider before giving up | `LlmRouter.createMessage` chain |
| 35 | No provider configured at all | "No language model is set up yet" | `AgentErrors.humanise` |
| 36 | Model replies with prose and no tool call | Asked once to use a tool, then the run ends cleanly | `forcedToolRetry` in `runLoop` |
| 37 | Any message the model produces | Capped and single-line in the bubble, so nothing can flood the screen | `BubbleCardView.MAX_MESSAGE_CHARS` |

## Runs and routines

| # | Case | What Aura does | Where |
|---|------|----------------|-------|
| 38 | User wants to stop mid-run | Stop is on the run banner for the whole run | `StatusBannerView.onStop` → `orchestrator.cancel` |
| 39 | Aura opens an app unexpectedly | Can't be silent: the banner names the mode, step and current action throughout | `AgentOrchestrator.onProgress` → `StatusBannerView.bind` |
| 40 | Accessibility service switched off mid-task | Says permission was lost and how to restore it | `runLoop` service null branch |
| 41 | Routine run a second time | Replays the route that worked, with instructions to verify each step | `RoutineStore.learnedRoute`, `AgentOrchestrator.knownRoute` |
| 42 | Saved route has gone stale (app redesigned) | Route is a hint, not a script — the model is told to verify and fall back to looking | first-message wording in `runLoop` |
