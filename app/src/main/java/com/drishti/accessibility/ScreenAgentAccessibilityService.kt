package com.drishti.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Accessibility service core — read-only tree collection and index resolution.
 * Algorithms ported from mobilerun-portal MobilerunAccessibilityService.
 */
class ScreenAgentAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "ScreenAgentA11y"
        private const val MIN_ELEMENT_SIZE = 5
        internal const val VISIBLE_ELEMENTS_STALE_GRACE_MS = 750L
        private const val REFRESH_INTERVAL_MS = 250L
        private const val MIN_FRAME_TIME_MS = 16L

        @Volatile
        private var instance: ScreenAgentAccessibilityService? = null

        fun getInstance(): ScreenAgentAccessibilityService? = instance

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
    @Volatile
    private var currentPackageName: String = ""

    @Volatile
    private var currentActivityName: String = ""

    /**
     * Package owning the active window, read from the tree itself rather than from events.
     * Events also arrive from keyboards, the status bar and our own overlay windows, so
     * they are a poor answer to "which app is the user actually in".
     */
    @Volatile
    private var activeWindowPackageName: String = ""
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
        // Amend the manifest config rather than replacing it. A fresh
        // AccessibilityServiceInfo dropped flagIncludeNotImportantViews, losing the
        // untagged containers that much of the tree hangs off.
        //
        // Touch exploration is deliberately not requested: it makes a single tap announce
        // rather than activate, which would stop the user completing the very step we just
        // pointed them at.
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            packageNames = null
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        refreshScreenBounds()
        startPeriodicUpdates()
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventPackage = event.packageName?.toString() ?: ""
        val eventClassName = event.className?.toString() ?: ""

        // Our own overlays raise events like any other window. Letting them through flipped
        // the tracked package to com.drishti the instant a highlight was drawn, which the
        // guidance loop read as "they navigated somewhere" and moved on without them.
        if (!isForegroundPackageSignal(eventPackage, event.eventType, packageName)) return

        if (eventPackage != currentPackageName && currentPackageName.isNotEmpty()) {
            synchronized(visibleElements) { clearVisibleElementSnapshot() }
        }
        currentPackageName = eventPackage
        if (eventClassName.isNotEmpty() && !eventClassName.startsWith("android.")) {
            currentActivityName = eventClassName
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

    /**
     * The app the user is actually looking at.
     *
     * Prefers the active window's own package. Event packages are a fallback only: they
     * also come from keyboards, the status bar and our own overlay, and trusting them here
     * both defeated the sensitive-app check and made the guidance loop believe the user had
     * navigated away.
     */
    fun currentPackage(): String = activeWindowPackageName.ifEmpty { currentPackageName }

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
        activeRoot?.let {
            noteActiveWindowPackage(it)
            return listOf(it to 0)
        }

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
                    if (root != null) {
                        if (out.isEmpty()) noteActiveWindowPackage(root)
                        out.add(root to window.layer)
                    }
                }
        } finally {
            windows.forEach { it.recycle() }
        }
        return out
    }

    /**
     * Records which app owns the tree we are about to read. Our own package is ignored: the
     * orb and the highlight are ours, and neither means the user left the app they are in.
     */
    private fun noteActiveWindowPackage(root: AccessibilityNodeInfo) {
        val pkg = try {
            root.packageName?.toString().orEmpty()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to read active window package: ${e.message}")
            ""
        }
        if (pkg.isNotEmpty() && pkg != packageName) activeWindowPackageName = pkg
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
