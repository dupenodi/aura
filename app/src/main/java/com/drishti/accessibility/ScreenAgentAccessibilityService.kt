package com.drishti.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.drishti.core.agent.Screenshot
import com.drishti.core.screen.Bounds
import com.drishti.core.screen.Screen
import com.drishti.core.screen.UiEvent
import com.drishti.core.screen.UiNode
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Aura's eyes: reads the screen on demand and reports what the user does, as it happens.
 *
 * Read-only by design — nothing here taps, types or scrolls. Two jobs:
 *
 * - **Events.** Clicks, text changes, scrolls and window changes are forwarded the moment
 *   they arrive, with the bounds of the node that was pressed. That is how a guided step is
 *   known to be done: the click landed on what we ringed.
 * - **Snapshots.** The tree is read only when the agent asks, on a background thread, and
 *   copied into plain data. (It used to be re-walked on the main thread every 250ms for as
 *   long as the service was on, which cost battery and frames for nothing.)
 */
class ScreenAgentAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "ScreenAgentA11y"
        private const val MAX_NODES = 2_500
        private const val MAX_DEPTH = 60
        private const val SHOT_WIDTH = 720
        private const val SHOT_QUALITY = 70

        @Volatile
        private var instance: ScreenAgentAccessibilityService? = null

        fun getInstance(): ScreenAgentAccessibilityService? = instance

        /**
         * Everything the platform reports, for as long as Aura runs. Lives outside the service
         * instance so collectors survive the service being restarted by the system.
         */
        private val _events = MutableSharedFlow<UiEvent>(
            extraBufferCapacity = 512,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val events: SharedFlow<UiEvent> = _events

        /**
         * Whether an event says anything about which app is in front.
         *
         * Our own package never does: the orb and the highlight are our windows, and they
         * raise events like anything else. Nor does a content change — the status bar clock
         * ticking over is not the user going somewhere.
         */
        internal fun isForegroundPackageSignal(
            eventPackage: String,
            eventType: Int,
            ownPackage: String,
        ): Boolean = eventPackage.isNotEmpty() &&
            eventPackage != ownPackage &&
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
    }

    private val worker = HandlerThread("aura-a11y").apply { start() }
    private val workerHandler = Handler(worker.looper)
    private val workerDispatcher = workerHandler.asCoroutineDispatcher()
    private val workerExecutor = Executor { workerHandler.post(it) }

    @Volatile
    private var foregroundPackage: String = ""

    @Volatile
    private var foregroundActivity: String? = null

    /** Live nodes from the latest snapshot, by key, so bounds can be re-read just before pointing. */
    private var liveNodes: Map<Int, AccessibilityNodeInfo> = emptyMap()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Amend the manifest config rather than replacing it: a fresh AccessibilityServiceInfo
        // drops flagIncludeNotImportantViews, losing the untagged containers much of the tree
        // hangs off. Touch exploration is deliberately not requested — it would make a tap
        // announce instead of activate, blocking the very step we pointed at.
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            packageNames = null
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        Log.d(TAG, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        // Our own windows (orb, bubble, ring) are never the user doing something.
        if (pkg == packageName) return

        if (isForegroundPackageSignal(pkg, event.eventType, packageName)) {
            foregroundPackage = pkg
            event.className?.toString()?.takeIf { !it.startsWith("android.") }?.let { foregroundActivity = it }
        }

        val type = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> UiEvent.Type.CLICK
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> UiEvent.Type.LONG_CLICK
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> UiEvent.Type.TEXT_CHANGED
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> UiEvent.Type.SCROLLED
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> UiEvent.Type.WINDOW_STATE
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> UiEvent.Type.WINDOWS_CHANGED
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> UiEvent.Type.CONTENT_CHANGED
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> UiEvent.Type.FOCUSED
            AccessibilityEvent.TYPE_VIEW_SELECTED -> UiEvent.Type.SELECTED
            else -> return
        }

        // Only user actions need the pressed node; content changes fire constantly and
        // fetching their source would be wasted IPC.
        val wantsSource = type == UiEvent.Type.CLICK || type == UiEvent.Type.LONG_CLICK ||
            type == UiEvent.Type.FOCUSED || type == UiEvent.Type.SELECTED
        var bounds: Bounds? = null
        var viewId: String? = null
        var srcText: String? = null
        var srcDesc: String? = null
        if (wantsSource) {
            runCatching {
                event.source?.let { src ->
                    val r = Rect()
                    src.getBoundsInScreen(r)
                    bounds = Bounds(r.left, r.top, r.right, r.bottom)
                    viewId = src.viewIdResourceName?.substringAfterLast('/')
                    srcText = src.text?.toString()
                    srcDesc = src.contentDescription?.toString()
                }
            }
        }
        val text = when (type) {
            // The new contents of the field, which is what a typing step compares against.
            UiEvent.Type.TEXT_CHANGED -> event.text?.joinToString("")
            else -> event.text?.joinToString(" ")?.takeIf { it.isNotBlank() } ?: srcText
        }
        _events.tryEmit(
            UiEvent(
                type = type,
                pkg = pkg,
                cls = event.className?.toString()?.substringAfterLast('.'),
                text = text,
                desc = event.contentDescription?.toString() ?: srcDesc,
                viewId = viewId,
                bounds = bounds,
                timeMs = event.eventTime,
            ),
        )
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        worker.quitSafely()
        super.onDestroy()
    }

    // ---- Snapshot ---------------------------------------------------------------------------

    /** Reads the screen the user is looking at. Null if nothing can be read right now. */
    suspend fun snapshot(): Screen? = withContext(workerDispatcher) {
        runCatching { readScreen() }.onFailure { Log.w(TAG, "snapshot failed", it) }.getOrNull()
    }

    /** Where a node from the last snapshot is now — lists settle, keyboards push things up. */
    suspend fun liveBounds(key: Int): Bounds? = withContext(workerDispatcher) {
        val node = liveNodes[key] ?: return@withContext null
        runCatching {
            if (!node.refresh()) return@runCatching null
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.isEmpty || !node.isVisibleToUser) null else Bounds(r.left, r.top, r.right, r.bottom)
        }.getOrNull()
    }

    private fun readScreen(): Screen? {
        val (root, window) = activeRoot() ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        val size = screenSize()
        val nodes = HashMap<Int, AccessibilityNodeInfo>()
        var count = 0

        fun convert(node: AccessibilityNodeInfo, depth: Int): UiNode? {
            if (count >= MAX_NODES || depth > MAX_DEPTH) return null
            if (!node.isVisibleToUser) return null
            val r = Rect()
            node.getBoundsInScreen(r)
            val key = count++
            nodes[key] = node
            val children = ArrayList<UiNode>(node.childCount)
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                convert(child, depth + 1)?.let(children::add)
            }
            return UiNode(
                cls = node.className?.toString()?.substringAfterLast('.').orEmpty(),
                text = node.text?.toString(),
                desc = node.contentDescription?.toString(),
                hint = node.hintText?.toString(),
                viewId = node.viewIdResourceName?.substringAfterLast('/'),
                state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) node.stateDescription?.toString() else null,
                bounds = Bounds(r.left, r.top, r.right, r.bottom),
                flags = flagsOf(node),
                children = children,
                key = key,
            )
        }

        val tree = convert(root, 0) ?: return null
        liveNodes = nodes
        val keyboard = runCatching { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(false)
        val title = window?.title?.toString()?.takeIf { it.isNotBlank() }
            ?: (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) root.paneTitle?.toString() else null)
        return Screen(
            pkg = pkg,
            width = size.x,
            height = size.y,
            roots = listOf(tree),
            appLabel = appLabel(pkg),
            activity = foregroundActivity.takeIf { pkg == foregroundPackage },
            title = title?.takeIf { it != appLabel(pkg) },
            keyboardVisible = keyboard,
            timeMs = android.os.SystemClock.uptimeMillis(),
        )
    }

    /**
     * The window the user is working in. Normally the active window; when that is one of our
     * own (the composer has focus), the topmost application window that isn't ours.
     */
    private fun activeRoot(): Pair<AccessibilityNodeInfo, AccessibilityWindowInfo?>? {
        val all = runCatching { windows }.getOrNull().orEmpty()
        val active = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getRootInActiveWindow(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
            } else {
                rootInActiveWindow
            }
        }.getOrNull()
        if (active != null && active.packageName?.toString() != packageName) {
            return active to all.firstOrNull { it.id == active.windowId }
        }
        val fallback = all
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.type == AccessibilityWindowInfo.TYPE_SYSTEM }
            .sortedByDescending { it.layer }
            .firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName?.toString() != packageName }?.let { it to w } }
        return fallback
    }

    private fun flagsOf(n: AccessibilityNodeInfo): Int {
        var f = 0
        if (n.isClickable) f = f or UiNode.CLICKABLE
        if (n.isLongClickable) f = f or UiNode.LONG_CLICKABLE
        if (n.isEditable) f = f or UiNode.EDITABLE
        if (n.isScrollable) f = f or UiNode.SCROLLABLE
        if (n.isCheckable) f = f or UiNode.CHECKABLE
        if (n.isChecked) f = f or UiNode.CHECKED
        if (n.isSelected) f = f or UiNode.SELECTED
        if (!n.isEnabled) f = f or UiNode.DISABLED
        if (n.isFocused) f = f or UiNode.FOCUSED
        if (n.isPassword) f = f or UiNode.PASSWORD
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && n.isHeading) f = f or UiNode.HEADING
        if (n.isScrollable) {
            val actions = n.actionList
            if (actions.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }) f = f or UiNode.SCROLL_FORWARD
            if (actions.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }) f = f or UiNode.SCROLL_BACKWARD
        }
        return f
    }

    private val labels = HashMap<String, String?>()

    private fun appLabel(pkg: String): String? = labels.getOrPut(pkg) {
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
    }

    private fun screenSize(): android.graphics.Point {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            android.graphics.Point(b.width(), b.height())
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            android.graphics.Point(m.widthPixels, m.heightPixels)
        }
    }

    // ---- Screenshot -------------------------------------------------------------------------

    /**
     * A downscaled JPEG of the app the user is in, for screens the tree can't describe.
     * On Android 14+ only that app's window is captured, so Aura's own orb and ring never
     * appear in the picture the model sees.
     */
    suspend fun screenshot(): Screenshot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val bitmap = suspendCancellableCoroutine<Bitmap?> { cont ->
            val callback = object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bmp = runCatching {
                        result.hardwareBuffer.use { hw ->
                            Bitmap.wrapHardwareBuffer(hw, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        }
                    }.getOrNull()
                    if (cont.isActive) cont.resume(bmp)
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "screenshot failed: $errorCode")
                    if (cont.isActive) cont.resume(null)
                }
            }
            val windowId = runCatching { rootInActiveWindow?.windowId }.getOrNull()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && windowId != null) {
                takeScreenshotOfWindow(windowId, workerExecutor, callback)
            } else {
                takeScreenshot(Display.DEFAULT_DISPLAY, workerExecutor, callback)
            }
        } ?: return null
        return withContext(workerDispatcher) {
            val scale = SHOT_WIDTH.toFloat() / bitmap.width
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, SHOT_WIDTH, (bitmap.height * scale).toInt(), true)
            } else {
                bitmap
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, SHOT_QUALITY, out)
            Screenshot(out.toByteArray(), scaled.width, scaled.height).also {
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
            }
        }
    }
}
