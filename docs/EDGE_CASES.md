# Aura — edge cases

Where trust is won or lost. Each case lists what the user sees, where it is handled, and the
test that pins it (`core/src/test/...` unless noted).

## Understanding the request

| # | Case | What Aura does | Where | Test |
|---|------|----------------|-------|------|
| 1 | Vague request ("make text bigger") | Never asks a question — takes its best next step from the screen | `Prompts.RULES` | golden `font_bigger` |
| 2 | App named differently from its package | Resolved by label, then package, then shortest containment match | `AppMatcher.resolve` | golden `call_amma` |
| 3 | Model claims an installed app is missing | Installed apps are in the (cached) system prompt with "anything listed IS installed" | `Prompts.system` | — |
| 4 | App genuinely isn't installed | Model is told so and must guide another way or decline | `StepProblem.UnknownApp` | — |
| 5 | Scam patterns (share OTP, install AnyDesk for "the bank") | Declines kindly, guides nowhere | `Prompts.RULES` safety | golden `scam_anydesk`, `scam_otp` |
| 6 | Hindi/Tamil/… speaker | Model writes `say` in that language; fixed lines come from `Phrases` | `Step.toolSchema`, `Phrases` | `fixedLinesAreSpokenInTheChosenLanguage` |

## Showing a step

| # | Case | What Aura does | Where | Test |
|---|------|----------------|-------|------|
| 7 | Every step | Ring + label on the one thing to press, instruction spoken and in the dock (moved to the top if the target is at the bottom) with Stop. Nothing is ever pressed for them | `GuideSession.show`, `PointerOverlay` | — |
| 8 | List or keyboard moved things since the screen was read | Bounds re-read from the live node just before drawing | `Device.liveBounds` | — |
| 9 | Target below the fold | Scroll step with an animated swipe hint; the list says which way it can scroll | `ScreenEncoder` scroll lines, `PointerOverlay.showSwipe` | `scrollingRevealsRowsBelowTheFold` |
| 10 | Model returns a reference that isn't on screen | Sent back with the reason, next attempt on the stronger model; three in a row ends politely | `StepResolver`, `GuideSession` problems | `anInvalidReferenceIsSentBackAndEscalated`, `threeBadStepsInARowStopWithAPlainMessage` |
| 11 | Model repeats the same step on the same screen | Third time: escalate once with that spelled out; then stop | repeat guard in `GuideSession.loop` | `repeatingTheSameStepOnTheSameScreenIsCaught` |
| 12 | Unreadable screen (game, map, Flutter, WebView) | Screenshot of the app window only (never Aura's overlays) goes with the step; can be switched off | `ScreenEncoder.sparse`, `ScreenshotPolicy`, `takeScreenshotOfWindow` | `screenshotsReachThePlannerWhenAskedFor` |

## Knowing they did it

| # | Case | What Aura does | Where | Test |
|---|------|----------------|-------|------|
| 13 | They press the ringed thing | Click event on that node (or inside it / the row around it) completes the step at once; speech stops | `GuideSession.matchesTarget` | `aClickOnTheRingedRowCompletesTheStepImmediately` |
| 14 | App raises no click events (Compose, games) | Screen re-read on window/content events; a new screen, a flipped toggle, or the keyboard opening on the field counts | `GuideSession.checkScreen`, `toggled` | `appsWithoutClickEventsAreFollowedByWatchingTheScreen` |
| 15 | Clock ticks, animation redraws | Not progress: a quarter of the listed items must change, or the app/title | `ScreenEncoder.movedOn` | `movedOnIgnoresSmallRedrawsButNotNewScreens` |
| 16 | They press something else that goes somewhere | Follow them: next step is planned from where they are, the model is told what they pressed, no scolding | `WatchOutcome.OffTarget` | `aWrongPressIsFollowedNotScolded`, `GuideFuzzTest` |
| 17 | They press something pressable that does nothing | "Not quite — tap inside the glowing circle", once per step, ring emphasised | `GuideSession.watch` | `aPressThatMissesAndDoesNothingGetsImmediateHelp` |
| 18 | Typing step | Done when the field holds the text, or they stop typing for 3 s | `WatchOutcome.Typed` | `typingIsDoneWhenTheTextMatches` |
| 19 | They hesitate | Repeat at 12 s, reassure + bigger ring at 30 s, pause at 60 s. The edge handle (or "carry on") resumes from wherever they are; untouched for 10 min it ends quietly | `GuideConfig`, `pauseUntilResumed` | `hesitationRepeatsThenReassuresThenPausesAndResumes`, `aPausedSessionNobodyReturnsToEndsQuietly` |
| 20 | A read fails mid-transition | Retried with backoff; only "can't see" if the service is really gone | `observeWithRetry` | — |
| 21 | Our own handle/glow/dock/ring raise events | Ignored by package, in the service and in the session | `ScreenAgentAccessibilityService`, `GuideSession.run` | `app/.../ForegroundPackageSignalTest` |

## Privacy and safety

| # | Case | What Aura does | Where | Test |
|---|------|----------------|-------|------|
| 22 | Banking / payment / health / password app in front | Stops before reading anything; explicit package list (HDFC, SBI, PhonePe, CRED, …) plus keywords | `SensitiveApps` | `sensitiveAppsStopBeforeAnythingIsRead` |
| 23 | Password fields | Value never sent | `ScreenEncoder.describe` | `passwordsAreNeverSent` |
| 24 | Anything the user does not want done | Impossible: no gesture dispatch, no click or set-text actions anywhere | accessibility config | — |
| 25 | Session recordings | Last 30 kept on the phone only; deleted with "Delete all history" | `SessionRecorder` | — |

## Voice

| # | Case | What Aura does | Where | Test |
|---|------|----------------|-------|------|
| 26 | Hold to talk | Sarvam saaras:v4 realtime, manual endpointing: release = final transcript, partials shown live, app names as keyterms | `SarvamHoldToTalk`, `SarvamRealtimeStt` | `holdToTalkStreamsAudioAndReturnsTheFinal` |
| 27 | Socket refused or dropped while they talk | Recording continues; the whole clip goes to Sarvam REST on release | `SarvamHoldToTalk` | `aRejectedKeyFailsSoTheCallerCanFallBack`, `restSttSendsAWavWithTheRestOdiaCode` |
| 28 | Sarvam returns no final | Last partial is used | `SarvamRealtimeStt` | `noFinalFallsBackToTheLastPartial` |
| 29 | Speaking | Bulbul v3 streamed as PCM (first audio as soon as synthesised); repeats served from cache; phone TTS if Sarvam fails | `AuraVoice`, `SarvamTts` | `ttsStreamsPcmAndAsksForBulbulV3Explicitly`, `ttsFallsBackToRestWhenStreamingIsRefused` |
| 30 | Odia | `or-IN` on the realtime socket, `od-IN` on REST and TTS | `Language` | `odiaUsesTheRealtimeSpellingAndAutoDetectReportsTheLanguage` |
| 30a | Summoned as the assistant (hold power / corner swipe) | Edges light up and it listens hands-free; a 1.2 s pause after speech sends, 6 s of nothing stops, 15 s is the cap; "done" / "cancel" in the dock | `AssistActivity`, `Endpointer`, `SarvamHoldToTalk` | `EndpointerTest` |
| 30b | Glow and ring both over the app being guided | Added as accessibility overlays (trusted), so taps still reach the app; an app-overlay fallback is capped at 0.8 opacity | `OverlayHost`, `GlowWindow` | device |

## Model and network

| # | Case | What Aura does | Where | Test |
|---|------|----------------|-------|------|
| 31 | Overloaded / rate limited / timed out | One retry on the other model; OpenRouter also fails over itself (`models`) | `GuideSession.planWithRetry`, `OpenAiCompatClient` | `aRetryableModelErrorIsRetriedOnTheOtherModel`, `slowResponsesTimeOut` |
| 32 | Bad key, no credit, offline | Plain sentence in their language, never a code | `LlmException.phrase` | `aBadKeyStopsWithWordsNotCodes` |
| 33 | Model can't switch reasoning off | Retried without it, and not sent again that session | `OpenAiCompatClient.call` | `aModelThatMustReasonIsRetriedWithoutTheSetting` |
| 34 | Model answers in prose | JSON recovered from the text | `OpenAiCompatClient.parse` | `argumentsGivenInProseAreStillRecovered` |
| 35 | Stop pressed mid-call | The HTTP call is cancelled, ring and speech go at once | `Http.await`, `GuideRunner.cancel` | `cancellingMidStepTakesTheRingDown` |
