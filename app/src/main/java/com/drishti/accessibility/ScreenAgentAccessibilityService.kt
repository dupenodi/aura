package com.drishti.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.delay
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Accessibility service core — tree collection, typing, screenshots.
 * Algorithms ported from mobilerun-portal MobilerunAccessibilityService.
 */
class ScreenAgentAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "ScreenAgentA11y"
        private const val MIN_ELEMENT_SIZE = 5
        internal const val VISIBLE_ELEMENTS_STALE_GRACE_MS = 750L
        private const val REFRESH_INTERVAL_MS = 250L
        private const val MIN_FRAME_TIME_MS = 16L
        private const val FOCUS_SETTLE_MS = 200L

        @Volatile
        private var instance: ScreenAgentAccessibilityService? = null

        fun getInstance(): ScreenAgentAccessibilityService? = instance

        fun calculateInputText(
            currentText: String?,
            hintText: String?,
            newText: String,
            clear: Boolean,
            selectionStart: Int? = null,
            selectionEnd: Int? = null,
        ): String = InputTextLogic.calculateInputText(
            currentText, hintText, newText, clear, selectionStart, selectionEnd,
        )

        internal fun shouldReuseVisibleElementsSnapshot(
            cachedElementCount: Int,
            snapshotTimeMs: Long,
            nowMs: Long,
            snapshotPackageName: String,
            currentPackageName: String,
            snapshotActivityName: String,
            currentActivityName: String,
            snapshotScreenWidth: Int,
            currentScreenWidth: Int,
            snapshotScreenHeight: Int,
            currentScreenHeight: Int,
        ): Boolean {
            val snapshotAgeMs = nowMs - snapshotTimeMs
            return cachedElementCount > 0 &&
                snapshotTimeMs > 0L &&
                snapshotAgeMs in 0L..VISIBLE_ELEMENTS_STALE_GRACE_MS &&
                snapshotPackageName == currentPackageName &&
                snapshotActivityName == currentActivityName &&
                snapshotScreenWidth == currentScreenWidth &&
                snapshotScreenHeight == currentScreenHeight
        }

        internal fun updateScreenBounds(bounds: Rect, width: Int, height: Int): Boolean {
            val safeWidth = width.coerceAtLeast(0)
            val safeHeight = height.coerceAtLeast(0)
            val changed = bounds.left != 0 ||
                bounds.top != 0 ||
                bounds.right != safeWidth ||
                bounds.bottom != safeHeight
            bounds.left = 0
            bounds.top = 0
            bounds.right = safeWidth
            bounds.bottom = safeHeight
            return changed
        }
    }

    private val screenBounds = Rect()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isProcessing = AtomicBoolean(false)
    private var lastUpdateTime = 0L
    private var currentPackageName: String = ""
    private var currentActivityName: String = ""
    private val visibleElements = mutableListOf<ElementNode>()
    private var visibleElementsSnapshotTimeMs = 0L
    private var visibleElementsSnapshotPackageName = ""
    private var visibleElementsSnapshotActivityName = ""
    private var visibleElementsSnapshotScreenWidth = 0
    private var visibleElementsSnapshotScreenHeight = 0

    /**
     * Overlay indexes last published to the agent. Tap/type by index resolve against this map so
     * actions stay consistent with the tree the model saw, even if the live tree reindexes.
     */
    private val publishedBounds = mutableMapOf<Int, Rect>()
    private var publishedPackageName: String = ""

    /** Optional hook so overlays can hide drawing during screenshots. */
    var overlayDrawingController: OverlayDrawingController? = null

    interface OverlayDrawingController {
        fun isDrawingEnabled(): Boolean
        fun setDrawingEnabled(enabled: Boolean)
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastUpdate = currentTime - lastUpdateTime
            if (timeSinceLastUpdate >= MIN_FRAME_TIME_MS) {
                refreshVisibleElements()
                lastUpdateTime = currentTime
            }
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            packageNames = null
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_2_FINGER_PASSTHROUGH
            }
        }
        refreshScreenBounds()
        startPeriodicUpdates()
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackage = event?.packageName?.toString() ?: ""
        val eventClassName = event?.className?.toString() ?: ""

        if (eventPackage.isNotEmpty() &&
            eventPackage != currentPackageName &&
            currentPackageName.isNotEmpty()
        ) {
            clearVisibleElementSnapshot()
        }
        if (eventPackage.isNotEmpty()) {
            currentPackageName = eventPackage
        }
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (eventClassName.isNotEmpty() && !eventClassName.startsWith("android.")) {
                currentActivityName = eventClassName
            }
        }
        // Tree refresh is periodic (250ms), not event-driven — portal behavior.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        stopPeriodicUpdates()
        clearVisibleElementSnapshot()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        stopPeriodicUpdates()
        clearVisibleElementSnapshot()
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    fun currentPackage(): String = currentPackageName

    fun currentActivity(): String = currentActivityName

    fun screenSize(): Rect = refreshScreenBounds()

    fun getVisibleElements(): MutableList<ElementNode> = getVisibleElementsInternal()

    /**
     * Refresh the tree and pin overlay indexes for subsequent agent actions.
     * Always call this when building the observe payload for the model.
     */
    fun captureAgentTree(): MutableList<ElementNode> {
        val elements = getVisibleElementsInternal()
        val map = mutableMapOf<Int, Rect>()
        for (root in elements) {
            for (node in root.flatten()) {
                if (node.overlayIndex > 0) {
                    map[node.overlayIndex] = Rect(node.rect)
                }
            }
        }
        synchronized(visibleElements) {
            publishedBounds.clear()
            publishedBounds.putAll(map)
            publishedPackageName = currentPackageName
        }
        return elements
    }

    /**
     * Resolve an overlay index without rebuilding the tree.
     * Prefers the live cached node; falls back to bounds from the last [captureAgentTree].
     */
    fun findElementByIndex(index: Int): ElementNode? {
        synchronized(visibleElements) {
            for (root in visibleElements) {
                val found = root.findByOverlayIndex(index)
                if (found != null) return found
            }
        }
        return null
    }

    fun publishedBoundsForIndex(index: Int): Rect? {
        synchronized(visibleElements) {
            return publishedBounds[index]?.let { Rect(it) }
        }
    }

    data class TapTarget(val bounds: Rect, val element: ElementNode?)

    fun resolveTapTarget(index: Int): TapTarget? {
        val live = findElementByIndex(index)
        if (live != null) return TapTarget(freshBoundsOf(live), live)
        synchronized(visibleElements) {
            if (publishedPackageName.isNotEmpty() &&
                publishedPackageName != currentPackageName
            ) {
                return null
            }
            val published = publishedBounds[index] ?: return null
            return TapTarget(Rect(published), null)
        }
    }

    /**
     * Bounds captured while scanning the tree go stale as soon as the UI moves — a list
     * settling, a keyboard opening, a transition finishing. Acting on those coordinates
     * draws the highlight (and taps) in the wrong place, so re-read them from the live
     * node and only fall back to the scan-time rect if that fails.
     */
    private fun freshBoundsOf(element: ElementNode): Rect {
        return try {
            element.nodeInfo.refresh()
            val refreshed = Rect()
            element.nodeInfo.getBoundsInScreen(refreshed)
            if (refreshed.isEmpty) Rect(element.rect) else refreshed
        } catch (e: Exception) {
            Log.w(TAG, "Bounds refresh failed for index ${element.overlayIndex}: ${e.message}")
            Rect(element.rect)
        }
    }

    private fun startPeriodicUpdates() {
        lastUpdateTime = System.currentTimeMillis()
        mainHandler.postDelayed(updateRunnable, REFRESH_INTERVAL_MS)
    }

    private fun stopPeriodicUpdates() {
        mainHandler.removeCallbacks(updateRunnable)
    }

    private fun refreshVisibleElements() {
        if (!isProcessing.compareAndSet(false, true)) return
        try {
            if (currentPackageName.isEmpty()) {
                clearVisibleElementSnapshot()
                return
            }
            getVisibleElementsInternal()
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing visible elements: ${e.message}", e)
        } finally {
            isProcessing.set(false)
        }
    }

    private fun getVisibleElementsInternal(): MutableList<ElementNode> {
        val elements = mutableListOf<ElementNode>()
        val indexCounter = IndexCounter(1)
        val screenBoundsSnapshot = refreshScreenBounds()

        val rootCandidates = collectRootCandidates()
        if (rootCandidates.isEmpty()) {
            synchronized(visibleElements) {
                if (shouldReuseVisibleElementsSnapshot(
                        cachedElementCount = visibleElements.size,
                        snapshotTimeMs = visibleElementsSnapshotTimeMs,
                        nowMs = SystemClock.elapsedRealtime(),
                        snapshotPackageName = visibleElementsSnapshotPackageName,
                        currentPackageName = currentPackageName,
                        snapshotActivityName = visibleElementsSnapshotActivityName,
                        currentActivityName = currentActivityName,
                        snapshotScreenWidth = visibleElementsSnapshotScreenWidth,
                        currentScreenWidth = screenBoundsSnapshot.width(),
                        snapshotScreenHeight = visibleElementsSnapshotScreenHeight,
                        currentScreenHeight = screenBoundsSnapshot.height(),
                    )
                ) {
                    return visibleElements.toMutableList()
                }
                clearVisibleElementSnapshot()
                return mutableListOf()
            }
        }

        try {
            for ((rootNode, layer) in rootCandidates) {
                collectVisibleElements(
                    rootNode,
                    layer,
                    null,
                    elements,
                    indexCounter,
                    screenBoundsSnapshot,
                )
            }
        } finally {
            rootCandidates.forEach { (node, _) -> node.recycle() }
        }

        synchronized(visibleElements) {
            clearVisibleElementSnapshot()
            visibleElements.addAll(elements)
            visibleElementsSnapshotTimeMs = SystemClock.elapsedRealtime()
            visibleElementsSnapshotPackageName = currentPackageName
            visibleElementsSnapshotActivityName = currentActivityName
            visibleElementsSnapshotScreenWidth = screenBoundsSnapshot.width()
            visibleElementsSnapshotScreenHeight = screenBoundsSnapshot.height()
        }
        return elements
    }

    private fun collectRootCandidates(): List<Pair<AccessibilityNodeInfo, Int>> {
        val activeRoot = try {
            rootInActiveWindow
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unable to read active accessibility root: ${e.message}", e)
            null
        }
        activeRoot?.let { return listOf(it to 0) }

        val windows = try {
            windows
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unable to read accessibility windows: ${e.message}", e)
            null
        } ?: return emptyList()

        val out = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        try {
            windows.sortedWith(
                compareBy<AccessibilityWindowInfo> { fallbackWindowTypePriority(it) }
                    .thenByDescending { it.layer },
            )
                .filter { isUserFacingWindow(it) }
                .forEach { window ->
                    val root = try {
                        window.root
                    } catch (e: RuntimeException) {
                        Log.e(TAG, "Unable to read window root: ${e.message}", e)
                        null
                    }
                    if (root != null) out.add(root to window.layer)
                }
        } finally {
            windows.forEach { it.recycle() }
        }
        return out
    }

    private fun isUserFacingWindow(window: AccessibilityWindowInfo): Boolean =
        window.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
            window.type == AccessibilityWindowInfo.TYPE_SYSTEM

    private fun fallbackWindowTypePriority(window: AccessibilityWindowInfo): Int =
        when (window.type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> 0
            AccessibilityWindowInfo.TYPE_SYSTEM -> 1
            else -> 2
        }

    private fun collectVisibleElements(
        node: AccessibilityNodeInfo,
        windowLayer: Int,
        parent: ElementNode?,
        rootElements: MutableList<ElementNode>,
        indexCounter: IndexCounter,
        screenBoundsSnapshot: Rect,
        depth: Int = 0,
        activeNodePath: MutableSet<AccessibilityNodeInfo> = mutableSetOf(),
    ) {
        try {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val nodeKey = AccessibilityTraversalGuard.createTraversalKey(node, rect)

            if (AccessibilityTraversalGuard.isTooDeep(depth)) {
                Log.w(TAG, "Skipping subtree deeper than max depth: $nodeKey")
                return
            }
            if (!AccessibilityTraversalGuard.enterActivePath(node, activeNodePath)) {
                Log.w(TAG, "Skipping cyclic accessibility node: $nodeKey")
                return
            }

            try {
                val isInScreen = Rect.intersects(rect, screenBoundsSnapshot)
                val hasSize = rect.width() > MIN_ELEMENT_SIZE && rect.height() > MIN_ELEMENT_SIZE
                var currentElement: ElementNode? = null

                if (isInScreen && hasSize) {
                    val text = node.text?.toString() ?: ""
                    val contentDesc = node.contentDescription?.toString() ?: ""
                    val className = node.className?.toString() ?: ""
                    val viewId = node.viewIdResourceName ?: ""
                    val displayText = when {
                        text.isNotEmpty() -> text
                        contentDesc.isNotEmpty() -> contentDesc
                        viewId.isNotEmpty() -> viewId.substringAfterLast('/')
                        else -> className.substringAfterLast('.')
                    }
                    val id = ElementNode.createId(rect, className.substringAfterLast('.'), displayText)
                    val nodeCopy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        AccessibilityNodeInfo(node)
                    } else {
                        @Suppress("DEPRECATION")
                        AccessibilityNodeInfo.obtain(node)
                    }
                    currentElement = ElementNode(
                        nodeCopy,
                        Rect(rect),
                        displayText,
                        className.substringAfterLast('.'),
                        windowLayer,
                        System.currentTimeMillis(),
                        id,
                    )
                    currentElement.overlayIndex = indexCounter.getNext()
                    if (parent != null) {
                        parent.addChild(currentElement)
                    } else {
                        rootElements.add(currentElement)
                    }
                }

                val childParent = currentElement ?: parent
                val childCount = try {
                    node.childCount
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Unable to read child count: ${e.message}", e)
                    0
                }
                for (i in 0 until childCount) {
                    val childNode = try {
                        node.getChild(i)
                    } catch (e: RuntimeException) {
                        Log.e(TAG, "Unable to read child $i: ${e.message}", e)
                        null
                    } ?: continue

                    if (childNode === node) continue
                    if (AccessibilityTraversalGuard.isActiveNodeReference(childNode, activeNodePath)) {
                        continue
                    }
                    try {
                        collectVisibleElements(
                            childNode,
                            windowLayer,
                            childParent,
                            rootElements,
                            indexCounter,
                            screenBoundsSnapshot,
                            depth + 1,
                            activeNodePath,
                        )
                    } finally {
                        childNode.recycle()
                    }
                }
            } finally {
                AccessibilityTraversalGuard.leaveActivePath(node, activeNodePath)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in collectVisibleElements: ${e.message}", e)
        }
    }

    /**
     * Focus an editable field (optional overlay index), then ACTION_SET_TEXT.
     * If SET_TEXT fails, fall back to clipboard + ACTION_PASTE — the standard a11y path
     * when apps reject direct set-text without a focused IME target.
     */
    suspend fun inputText(
        text: String,
        clear: Boolean = true,
        overlayIndex: Int? = null,
    ): Boolean {
        val root = rootInActiveWindow ?: return false
        var ownedTarget: AccessibilityNodeInfo? = null
        var focusedAfterClick: AccessibilityNodeInfo? = null
        var writeOwned: AccessibilityNodeInfo? = null
        try {
            var targetNode: AccessibilityNodeInfo? = null
            if (overlayIndex != null) {
                val element = findElementByIndex(overlayIndex)
                val fromElement = element?.nodeInfo
                if (fromElement != null) {
                    ownedTarget = AccessibilityNodeInfo.obtain(fromElement)
                    targetNode = ownedTarget
                }
            }
            if (targetNode == null) {
                targetNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.also { ownedTarget = it }
            }
            if (targetNode == null) {
                targetNode = findEditableNode(root)?.also { ownedTarget = it }
            }
            if (targetNode == null) return false

            ensureEditableFocused(targetNode)
            delay(FOCUS_SETTLE_MS)

            focusedAfterClick = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val writeTarget = when {
                focusedAfterClick != null && focusedAfterClick.isEditable -> focusedAfterClick
                targetNode.isEditable -> targetNode
                else -> findEditableNode(root)?.also { writeOwned = it } ?: return false
            }

            val setOk = setTextOnNode(writeTarget, text, clear)
            if (setOk) return true

            val pasteOk = pasteViaClipboard(writeTarget, text, clear)
            if (pasteOk) return true

            Log.w(TAG, "inputText failed SET_TEXT and PASTE for len=${text.length}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error setting text: ${e.message}")
            return false
        } finally {
            recycleQuietly(writeOwned)
            recycleQuietly(focusedAfterClick)
            recycleQuietly(ownedTarget)
            recycleQuietly(root)
        }
    }

    private fun recycleQuietly(node: AccessibilityNodeInfo?) {
        if (node == null) return
        try {
            node.recycle()
        } catch (_: Exception) {
        }
    }

    fun clickElement(element: ElementNode): Boolean {
        val clicked = runCatching {
            element.nodeInfo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
        if (clicked) return true
        return false
    }

    /**
     * Prefer native scroll on the largest scrollable node; gesture swipe is the fallback.
     * @return true if a scroll action was accepted
     */
    suspend fun scroll(direction: String): Boolean {
        val forward = when (direction.lowercase()) {
            "up", "backward", "left" -> false
            else -> true
        }
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val root = rootInActiveWindow
        if (root != null) {
            try {
                val scrollable = findLargestScrollable(root)
                if (scrollable != null) {
                    val ok = runCatching { scrollable.performAction(action) }.getOrDefault(false)
                    if (scrollable !== root) {
                        runCatching { scrollable.recycle() }
                    }
                    if (ok) return true
                }
            } finally {
                runCatching { root.recycle() }
            }
        }

        val bounds = refreshScreenBounds()
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val delta = (minOf(bounds.width(), bounds.height()) * 0.35f).toInt()
        return if (forward) {
            // Finger moves up → content scrolls down
            GestureController.swipe(cx, cy + delta, cx, cy - delta, 350)
        } else {
            GestureController.swipe(cx, cy - delta, cx, cy + delta, 350)
        }
    }

    private fun ensureEditableFocused(target: AccessibilityNodeInfo) {
        runCatching { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        runCatching { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    private fun setTextOnNode(
        targetNode: AccessibilityNodeInfo,
        text: String,
        clear: Boolean,
    ): Boolean {
        val currentText = targetNode.text?.toString()
        val hintText = targetNode.hintText?.toString()
        val effectiveCurrent =
            if (!hintText.isNullOrEmpty() && currentText == hintText) ""
            else currentText.orEmpty()
        val currentLength = effectiveCurrent.length
        val rawStart = targetNode.textSelectionStart
        val rawEnd = targetNode.textSelectionEnd
        val selectionStart =
            if (rawStart >= 0) rawStart.coerceIn(0, currentLength) else currentLength
        val selectionEnd =
            if (rawEnd >= 0) rawEnd.coerceIn(0, currentLength) else selectionStart
        val replaceStart = minOf(selectionStart, selectionEnd)

        val finalText = calculateInputText(
            currentText = currentText,
            hintText = hintText,
            newText = text,
            clear = clear,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
        )
        val desiredSelection =
            if (clear) finalText.length
            else (replaceStart + text.length).coerceIn(0, finalText.length)

        val arguments = Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            finalText,
        )
        val setTextSuccess =
            targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        if (!setTextSuccess) return false
        setSelectionOnFocusedInput(targetNode, desiredSelection)
        return true
    }

    private fun pasteViaClipboard(
        targetNode: AccessibilityNodeInfo,
        text: String,
        clear: Boolean,
    ): Boolean {
        return try {
            if (clear) {
                val clearArgs = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        "",
                    )
                }
                targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("drishti", text))
            ensureEditableFocused(targetNode)
            targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Exception) {
            Log.e(TAG, "pasteViaClipboard failed: ${e.message}")
            false
        }
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findLargestScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        fun visit(n: AccessibilityNodeInfo) {
            if (n.isScrollable) {
                val r = Rect()
                n.getBoundsInScreen(r)
                val area = abs(r.width() * r.height())
                if (area > bestArea) {
                    best?.let { if (it !== node) runCatching { it.recycle() } }
                    best = AccessibilityNodeInfo.obtain(n)
                    bestArea = area
                }
            }
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                visit(child)
                child.recycle()
            }
        }
        visit(node)
        return best
    }

    private fun setSelectionOnFocusedInput(
        targetNode: AccessibilityNodeInfo,
        selection: Int,
    ): Boolean {
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, selection)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, selection)
        }
        if (targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)) {
            return true
        }
        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return try {
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        } finally {
            try {
                if (focusedNode != targetNode) focusedNode.recycle()
            } catch (_: Exception) {
            }
        }
    }

    fun takeScreenshotBase64(hideOverlay: Boolean = true): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        val controller = overlayDrawingController
        val wasEnabled = if (hideOverlay && controller != null) {
            val enabled = controller.isDrawingEnabled()
            controller.setDrawingEnabled(false)
            enabled
        } else {
            true
        }

        try {
            if (hideOverlay) {
                mainHandler.postDelayed({
                    performScreenshotCapture(future, wasEnabled, hideOverlay)
                }, 100)
            } else {
                performScreenshotCapture(future, wasEnabled, hideOverlay)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot", e)
            future.complete("error: Failed to take screenshot: ${e.message}")
            if (hideOverlay) controller?.setDrawingEnabled(wasEnabled)
        }
        return future
    }

    private fun performScreenshotCapture(
        future: CompletableFuture<String>,
        wasOverlayDrawingEnabled: Boolean,
        hideOverlay: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                AccessibilityScreenshotApi30.takeScreenshot(
                    service = this,
                    tag = TAG,
                    onSuccess = { base64 ->
                        try {
                            future.complete(base64)
                        } finally {
                            if (hideOverlay) {
                                overlayDrawingController?.setDrawingEnabled(wasOverlayDrawingEnabled)
                            }
                        }
                    },
                    onFailure = { message ->
                        try {
                            future.complete("error: $message")
                        } finally {
                            if (hideOverlay) {
                                overlayDrawingController?.setDrawingEnabled(wasOverlayDrawingEnabled)
                            }
                        }
                    },
                )
            } catch (e: Exception) {
                future.complete("error: Failed to take screenshot: ${e.message}")
                if (hideOverlay) {
                    overlayDrawingController?.setDrawingEnabled(wasOverlayDrawingEnabled)
                }
            }
        } else {
            // API 26–29: MediaProjection path (requires prior user consent).
            MediaProjectionScreenshotter.capture(this) { result ->
                try {
                    future.complete(result)
                } finally {
                    if (hideOverlay) {
                        overlayDrawingController?.setDrawingEnabled(wasOverlayDrawingEnabled)
                    }
                }
            }
        }
    }

    private fun refreshScreenBounds(): Rect {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            updateScreenBounds(screenBounds, bounds.width(), bounds.height())
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            updateScreenBounds(screenBounds, metrics.widthPixels, metrics.heightPixels)
        }
        return Rect(screenBounds)
    }

    private fun clearElementList() {
        for (element in visibleElements) {
            try {
                element.nodeInfo.recycle()
            } catch (_: Exception) {
            }
        }
        visibleElements.clear()
    }

    private fun clearVisibleElementSnapshot() {
        clearElementList()
        visibleElementsSnapshotTimeMs = 0L
        visibleElementsSnapshotPackageName = ""
        visibleElementsSnapshotActivityName = ""
        visibleElementsSnapshotScreenWidth = 0
        visibleElementsSnapshotScreenHeight = 0
    }

    private class IndexCounter(private var current: Int = 1) {
        fun getNext(): Int = current++
    }
}
