package com.drishti.core.sim

import com.drishti.core.agent.AppInfo
import com.drishti.core.agent.Device
import com.drishti.core.agent.DeviceInfo
import com.drishti.core.agent.Screenshot
import com.drishti.core.screen.Bounds
import com.drishti.core.screen.Screen
import com.drishti.core.screen.UiEvent
import com.drishti.core.screen.UiNode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** What pressing a simulated element does. */
sealed interface Tap {
    data class Go(val screen: String) : Tap
    data class Toggle(val key: String) : Tap
    data class Focus(val field: String) : Tap
    /** Submits the field's text and goes to [screen] (search, send). */
    data class Submit(val field: String, val screen: String) : Tap
    data class Launch(val pkg: String) : Tap
    data object Back : Tap
    data class Run(val action: (SimState) -> String?) : Tap
}

class SimState {
    val toggles = HashMap<String, Boolean>()
    val text = HashMap<String, String>()
    val scroll = HashMap<String, Int>()
    var focusedField: String? = null
    var keyboard = false
    /** Free-form flags a task's success check can look at ("video_call_started"). */
    val flags = HashSet<String>()

    fun on(key: String, default: Boolean = false) = toggles[key] ?: default
}

/**
 * One screen of a simulated app. [content] lays it out from the current state, so a toggle
 * flipping or text being typed shows up the next time the screen is read.
 */
class SimScreen(
    val name: String,
    val pkg: String,
    val title: String? = null,
    /** Real apps differ in whether a press raises a click event (Compose often doesn't). */
    val clickEvents: Boolean = true,
    val content: ScreenBuilder.(SimState) -> Unit,
) {
    /** Scrolling down here goes to another screen (the launcher's swipe-up to all apps). */
    var scrollTo: String? = null
}

data class SimApp(val label: String, val pkg: String, val home: String)

/**
 * A phone made of [SimScreen]s, close enough to a Pixel for the agent to be run on it.
 *
 * It behaves like the real thing where Aura depends on it: presses raise click events with
 * the pressed node's bounds; new screens raise window events; scrolling reveals rows that
 * were below the fold; the keyboard opens on a field. Nothing here knows about the agent.
 */
class SimPhone(
    screens: List<SimScreen>,
    val apps: List<SimApp>,
    private val launcher: String = "launcher",
    val width: Int = 1080,
    val height: Int = 2400,
) : Device {
    private val byName = screens.associateBy { it.name }
    val state = SimState()
    private val stack = ArrayDeque<String>().apply { add(launcher) }
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 256)
    private var clockMs = 0L
    private var nextKey = 0

    /** Behaviour of every node on the screen as last rendered, by node key. */
    private var handlers: Map<Int, Tap> = emptyMap()
    private var fieldByKey: Map<Int, String> = emptyMap()
    private var lastRendered: Screen? = null

    var snapshots = 0
        private set

    override val events: SharedFlow<UiEvent> get() = _events
    override val info = DeviceInfo(model = "Pixel 6", androidVersion = "15", ownPackage = "com.drishti", launcherPackage = LAUNCHER_PKG)

    val current: SimScreen get() = byName.getValue(stack.last())

    override fun installedApps(): List<AppInfo> = apps.map { AppInfo(it.label, it.pkg) }

    override suspend fun snapshot(): Screen {
        snapshots++
        return render()
    }

    override suspend fun screenshot(): Screenshot? = null

    override suspend fun liveBounds(nodeKey: Int): Bounds? {
        val screen = lastRendered ?: render()
        var found: Bounds? = null
        screen.roots.forEach { r -> r.walk { if (it.key == nodeKey) found = it.bounds } }
        return found
    }

    fun render(): Screen {
        val s = current
        val builder = ScreenBuilder(width, height, state, s.name) { nextKey++ }
        builder.statusBar()
        s.content(builder, state)
        if (state.keyboard) builder.keyboard()
        handlers = builder.handlers
        fieldByKey = builder.fields
        val screen = Screen(
            pkg = s.pkg,
            width = width,
            height = height,
            roots = listOf(builder.root()),
            title = s.title,
            keyboardVisible = state.keyboard,
            timeMs = clockMs,
        )
        lastRendered = screen
        return screen
    }

    // ---- What the person does ---------------------------------------------------------------

    /** A finger lands at (x, y). Returns the label of what was pressed, if anything. */
    fun tapAt(x: Int, y: Int): String? {
        val screen = render()
        val hit = deepestPressable(screen, x, y) ?: return null
        val tap = handlers[hit.key]
        if (current.clickEvents) {
            emit(UiEvent(UiEvent.Type.CLICK, current.pkg, hit.cls, hit.text ?: childText(hit), hit.desc, hit.viewId, hit.bounds))
        }
        tap?.let { apply(it, hit) }
        return hit.text ?: hit.desc ?: childText(hit)
    }

    fun type(text: String) {
        val field = state.focusedField ?: return
        state.text[field] = text
        emit(UiEvent(UiEvent.Type.TEXT_CHANGED, current.pkg, "EditText", text))
    }

    fun scroll(down: Boolean) {
        current.scrollTo?.takeIf { down }?.let { go(it); return }
        val list = current.name
        val now = state.scroll[list] ?: 0
        state.scroll[list] = (now + if (down) 1 else -1).coerceAtLeast(0)
        emit(UiEvent(UiEvent.Type.SCROLLED, current.pkg))
        emit(UiEvent(UiEvent.Type.CONTENT_CHANGED, current.pkg))
    }

    fun back() {
        if (state.keyboard) {
            state.keyboard = false
            emit(UiEvent(UiEvent.Type.WINDOWS_CHANGED, current.pkg))
            return
        }
        if (stack.size > 1) {
            stack.removeLast()
            changed()
        }
    }

    fun home() {
        stack.clear()
        stack.add(launcher)
        state.keyboard = false
        changed()
    }

    fun launch(pkg: String) {
        val app = apps.firstOrNull { it.pkg == pkg } ?: return
        stack.clear()
        stack.add(launcher)
        stack.add(app.home)
        state.keyboard = false
        changed()
    }

    fun go(screen: String) {
        require(screen in byName) { "no screen $screen" }
        stack.add(screen)
        state.keyboard = false
        state.focusedField = null
        changed()
    }

    /** Puts the phone on [screen] directly, e.g. to start a task mid-app. */
    fun startAt(vararg path: String) {
        stack.clear()
        stack.add(launcher)
        path.forEach { stack.add(it) }
    }

    /** Where the phone is and what is set on it — equal before and after a press that did nothing. */
    fun fingerprint(): String =
        "${stack.joinToString("/")}|${state.toggles}|${state.text}|${state.scroll}|${state.flags}|${state.focusedField}|${state.keyboard}"

    fun advanceTime(ms: Long) {
        clockMs += ms
    }

    private fun apply(tap: Tap, node: UiNode) {
        when (tap) {
            is Tap.Go -> go(tap.screen)
            is Tap.Toggle -> {
                state.toggles[tap.key] = !state.on(tap.key)
                emit(UiEvent(UiEvent.Type.CONTENT_CHANGED, current.pkg))
            }
            is Tap.Focus -> {
                state.focusedField = tap.field
                if (!state.keyboard) {
                    state.keyboard = true
                    emit(UiEvent(UiEvent.Type.FOCUSED, current.pkg, "EditText", bounds = node.bounds))
                    emit(UiEvent(UiEvent.Type.WINDOWS_CHANGED, current.pkg))
                }
            }
            is Tap.Submit -> {
                state.flags += "submitted:${tap.field}=${state.text[tap.field].orEmpty()}"
                go(tap.screen)
            }
            is Tap.Launch -> launch(tap.pkg)
            Tap.Back -> back()
            is Tap.Run -> {
                val next = tap.action(state)
                if (next != null) go(next) else emit(UiEvent(UiEvent.Type.CONTENT_CHANGED, current.pkg))
            }
        }
    }

    private fun changed() {
        emit(UiEvent(UiEvent.Type.WINDOW_STATE, current.pkg))
        emit(UiEvent(UiEvent.Type.CONTENT_CHANGED, current.pkg))
    }

    private fun emit(e: UiEvent) {
        _events.tryEmit(e.copy(timeMs = clockMs))
    }

    private fun deepestPressable(screen: Screen, x: Int, y: Int): UiNode? {
        var best: UiNode? = null
        fun visit(n: UiNode) {
            if (!n.bounds.contains(x, y)) return
            if ((n.clickable || n.editable) && n.bounds.intersects(screen.bounds)) best = n
            n.children.forEach(::visit)
        }
        screen.roots.forEach(::visit)
        return best
    }

    private fun childText(n: UiNode): String? {
        var t: String? = null
        n.walk { if (t == null && !it.text.isNullOrBlank() && it !== n) t = it.text }
        return t
    }

    companion object {
        const val LAUNCHER_PKG = "com.google.android.apps.nexuslauncher"
    }
}
