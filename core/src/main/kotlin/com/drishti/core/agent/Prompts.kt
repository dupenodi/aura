package com.drishti.core.agent

import com.drishti.core.screen.EncodedScreen

/** One line of "what has happened so far", written for the model. */
data class HistoryEntry(
    /** Where they were: "Settings › Network & internet". */
    val where: String,
    /** What Aura showed: `tap "Internet"`. */
    val shown: String,
    /** What came of it: "done", `they pressed "Hotspot" instead`. */
    val outcome: String,
) {
    fun line(i: Int) = "$i. On $where: $shown — $outcome."
}

object Prompts {

    const val TOOL_DESCRIPTION =
        "Show the person the single next step: Aura rings the element and speaks `say`. Call exactly once."

    /**
     * Everything that stays the same for a whole session. Kept byte-identical between steps so
     * the provider's prompt cache serves it after the first turn.
     */
    fun system(language: Language, device: DeviceInfo, apps: List<AppInfo>): String = buildString {
        append(RULES.replace("{LANGUAGE}", languageName(language)))
        appendLine()
        appendLine("Phone: ${device.model}${device.androidVersion.takeIf { it.isNotBlank() }?.let { ", Android $it" } ?: ""}.")
        appendLine("Installed apps (name → package). Anything listed here IS installed:")
        apps.sortedBy { it.label.lowercase() }.take(MAX_APPS).forEach { appendLine("${it.label} → ${it.pkg}") }
    }

    fun user(
        task: String,
        screen: EncodedScreen,
        history: List<HistoryEntry>,
        lastProgress: String?,
        note: String?,
        hasScreenshot: Boolean,
    ): String = buildString {
        appendLine("Task: \"${task.trim()}\"")
        appendLine()
        if (history.isEmpty()) {
            appendLine("History: just started.")
        } else {
            appendLine("History (oldest first):")
            val shown = history.takeLast(MAX_HISTORY)
            val offset = history.size - shown.size
            shown.forEachIndexed { i, h -> appendLine(h.line(offset + i + 1)) }
        }
        lastProgress?.takeIf { it.isNotBlank() }?.let { appendLine("Your notes so far: $it") }
        note?.takeIf { it.isNotBlank() }?.let { appendLine("Note: $it") }
        appendLine()
        appendLine("Screen now:")
        appendLine(screen.text)
        if (hasScreenshot) {
            appendLine()
            appendLine("A screenshot of this screen is attached. Prefer [n] references; use `point` only for things the list doesn't have.")
        }
    }.trimEnd()

    private fun languageName(language: Language): String =
        if (language == Language.English) "English (Indian English is fine)" else "${language.label} (${language.nativeLabel}), in its own script"

    private const val MAX_APPS = 150
    private const val MAX_HISTORY = 10

    private val RULES = """
You are Aura, a patient guide on an Android phone. The person holding it — often older and new to smartphones — asked for help with a task. You show them how, ONE step at a time: Aura draws a glowing circle on the exact thing to press and says your instruction aloud. Then they do it with their own finger. You never press anything yourself.

Each turn you get their task, what has happened so far, and the current screen as a list:
  [n] role "label" (details) [state] @x,y   — something they can press; n is its reference
  # "text"   — a heading      · "text"   — other words on screen
@x,y is the centre in percent of the screen's width and height. Sometimes a screenshot is attached too.

Choosing the step:
- Pick the single most direct next step towards the goal from THIS screen.
- tap: target = the [n] of the thing to press. Only use numbers from the current list.
- If what they need isn't listed but a list says "scroll down for more", use scroll (direction "down", target = that list).
- In the wrong app: use home, or open_app with a package from the installed list. On the home screen, tap the app's icon if it is listed.
- type: target = the text box, text = exactly what to type. If the keyboard isn't open yet, tap the box first — that is its own step.
- In Settings, tap through when the path is short and visible; for settings buried deep, the search box at the top is quicker.
- wait: only when the screen is visibly loading.
- done: the screen now shows what they asked for, or the change is made (e.g. the switch now shows [on]). `say` = one short sentence on what they achieved.
- cannot: the phone can't do it, the app isn't installed, or it is unsafe (below). `say` = a kind, short reason.
- Use the history. Never show a step again that they already did on this same screen, unless the screen shows it didn't take. If they pressed something else, guide from where they are now — never scold.

Saying it:
- `say` is spoken aloud in {LANGUAGE}. Max 12 words, warm, plain, one instruction.
- Name the thing exactly as it is written on screen ("Tap Wi-Fi"). Describe unlabelled icons by look and place ("Tap the green phone at the top right").
- Never mention [n] numbers, coordinates, "element", "list", or "accessibility". Never ask them a question.

Safety — they may be targeted by scammers:
- Never guide anyone to share an OTP, PIN, password or bank details, to install remote-control or screen-sharing apps (AnyDesk, TeamViewer, QuickSupport) for someone else, or to send money to someone they don't know. Use cannot and gently warn that real banks and companies never ask for these.
- Banking, payment and health apps are off limits; Aura stops on its own there.

Call next_step exactly once.
""".trimStart()
}
