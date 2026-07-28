package com.clicky.agent

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.clicky.ClickyApp
import com.clicky.accessibility.ClickyAccessibilityService
import com.clicky.accessibility.GestureExecutor
import com.clicky.accessibility.model.UiNode
import com.clicky.ai.Speaker
import com.clicky.debug.RingBufferLogger
import com.clicky.overlay.Annotation
import com.clicky.overlay.AgentMode
import com.clicky.overlay.OverlayService
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton

data class ToolResult(
    val text: String,
    val success: Boolean,
    val finished: Boolean = false,
    val summary: String? = null,
    val stateChanging: Boolean = false,
    /** Optional JPEG base64 when observe_screen included an image (for vision message). */
    val imageBase64: String? = null,
    /** Settle delay before auto-observe after this tool (ms). */
    val settleMs: Long = 0L,
    /**
     * After this tool, auto-observe once with include_image=true (search/type disambiguation).
     * AgentLoop should only honor this once per turn.
     */
    val preferVisionObserve: Boolean = false,
    /** Exact tap coordinates when a tap/tap_node succeeded (for recipe persistence). */
    val tapX: Float? = null,
    val tapY: Float? = null,
)

@Singleton
class ToolRegistry @Inject constructor(
    private val speaker: Speaker,
    private val agentState: AgentState,
    private val preferenceStore: PreferenceStore,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun execute(name: String, argumentsJson: String, mode: AgentMode): ToolResult {
        return runCatching {
            val args = runCatching {
                json.parseToJsonElement(argumentsJson).jsonObject
            }.getOrDefault(JsonObject(emptyMap()))

            if (mode == AgentMode.Coach && name in COACH_BLOCKED) {
                val blocked = "blocked: coach mode, use annotate + speak instead"
                RingBufferLogger.tool(name, blocked)
                return ToolResult(blocked, success = false)
            }

            val result = when (name) {
                "observe_screen" -> observeScreen(args)
                "move_cursor" -> moveCursor(args)
                "tap" -> tap(args)
                "tap_node" -> tapNode(args)
                "long_press" -> longPress(args)
                "swipe" -> swipe(args)
                "scroll" -> scroll(args)
                "type_text" -> typeText(args)
                "press_key" -> pressKey(args)
                "open_app" -> openApp(args)
                "annotate" -> annotate(args)
                "speak" -> speak(args)
                "get_preferences" -> getPreferences()
                "set_preference" -> setPreference(args)
                "wait" -> waitMs(args)
                "finish" -> finish(args)
                else -> ToolResult("error: unknown tool $name", success = false)
            }
            trackToolSideEffects(name, result)
            RingBufferLogger.tool(name, result.text.take(500))
            result
        }.getOrElse { e ->
            val msg = "error: ${e.message}"
            RingBufferLogger.tool(name, msg)
            ToolResult(msg, success = false)
        }
    }

    suspend fun observeScreenTreeOnly(): ToolResult =
        observeScreen(includeImage = false)

    suspend fun observeScreenWithImage(): ToolResult =
        observeScreen(includeImage = true)

    private suspend fun observeScreen(args: JsonObject): ToolResult {
        val includeImage = args.bool("include_image") ?: false
        return observeScreen(includeImage)
    }

    private suspend fun observeScreen(includeImage: Boolean): ToolResult {
        val service = a11yOrNull() ?: return missingA11y()
        val ctx = service.screenReader.captureContext()
            ?: return ToolResult("error: could not capture screen tree", success = false)

        var image: String? = null
        if (includeImage) {
            image = service.screenCapturer.captureBase64()
            RingBufferLogger.log(
                "vlm",
                if (image != null) {
                    "image included b64Len=${image.length}"
                } else {
                    "image requested but unavailable"
                },
            )
        }
        val body = buildString {
            append(ctx.asPromptText)
            if (includeImage) {
                append("\n[image=")
                append(if (image != null) "attached" else "unavailable")
                append(']')
            }
        }
        agentState.recordScreenContext(body)
        // Observe proves current UI — allow re-tapping a node that failed earlier this turn.
        agentState.onObserveCompleted()
        val text = if (agentState.needsNarrationNudge()) {
            "Narrate with speak() before next action.\n$body"
        } else {
            body
        }
        return ToolResult(
            text = text,
            success = true,
            stateChanging = false,
            imageBase64 = image,
        )
    }

    private suspend fun moveCursor(args: JsonObject): ToolResult {
        val x = args.float("x") ?: return badArgs("x")
        val y = args.float("y") ?: return badArgs("y")
        val reason = args.string("reason").orEmpty()
        val ok = animateCursor(x, y)
        return ToolResult(
            text = if (ok) "moved cursor to $x,$y ($reason)" else "error: overlay not running",
            success = ok,
        )
    }

    private suspend fun tap(args: JsonObject): ToolResult {
        val x = args.float("x") ?: return badArgs("x")
        val y = args.float("y") ?: return badArgs("y")
        val service = a11yOrNull() ?: return missingA11y()
        val (sx, sy) = clampOnScreen(service, x, y)
        val nearLabel = labelNearPoint(service, sx, sy)
        val hitNode = nodeNearPoint(service, sx, sy)
        // HARD: food-app address chip → auto-redirect to Search (same tool call).
        redirectAddressTapToSearch(service, hitNode, nearLabel, sy)?.let { return it }
        val looksToggle = AgentState.isToggleLikeLabel(nearLabel)
        agentState.isDuplicateTap(
            nodeId = null,
            x = sx,
            y = sy,
            label = nearLabel,
            looksToggle = looksToggle,
        )?.let { blocked ->
            RingBufferLogger.log("tap", blocked)
            return ToolResult(blocked, success = false, stateChanging = false)
        }
        maybeAutoAnnotateSearchTarget(service, hitNode)
        return executeCoordinateTap(
            service = service,
            sx = sx,
            sy = sy,
            label = nearLabel,
            looksToggle = looksToggle,
        )
    }

    private suspend fun tapNode(args: JsonObject): ToolResult {
        val id = args.int("node_id") ?: return badArgs("node_id")
        val service = a11yOrNull() ?: return missingA11y()
        val resolved = resolveTapNodePoint(service, id)
            ?: return ToolResult("tap_node failed id=$id (no bounds)", success = false)
        val (tapId, sx, sy) = resolved
        val label = nodeLabelForId(service, tapId) ?: nodeLabelForId(service, id)
        val targetNode = service.lastNodes.firstOrNull { it.id == tapId }
            ?: service.lastNodes.firstOrNull { it.id == id }
        // HARD: food-app address chip → auto-redirect to Search (same tool call).
        redirectAddressTapToSearch(service, targetNode, label, sy)?.let { return it }
        val looksToggle = AgentState.isToggleLikeLabel(label)
        agentState.isDuplicateTap(
            nodeId = tapId,
            x = sx,
            y = sy,
            label = label,
            looksToggle = looksToggle,
        )?.let { blocked ->
            RingBufferLogger.log("tap_node", blocked)
            return ToolResult(blocked, success = false, stateChanging = false)
        }
        // Highlight-before-tap: auto-annotate search/edit targets if model skipped annotate.
        maybeAutoAnnotateSearchTarget(service, targetNode)
        return executeNodeTap(
            service = service,
            tapId = tapId,
            requestedId = id,
            sx = sx,
            sy = sy,
            label = label,
            looksToggle = looksToggle,
        )
    }

    private fun nodeLabelForId(service: ClickyAccessibilityService, id: Int): String? {
        val meta = service.lastNodes.firstOrNull { it.id == id } ?: return null
        return nodeLabel(meta).takeIf { it.isNotBlank() }
    }

    /** Best-effort label for a coordinate tap from nearby clickable nodes. */
    private fun labelNearPoint(
        service: ClickyAccessibilityService,
        x: Float,
        y: Float,
    ): String? = nodeNearPoint(service, x, y)?.let { nodeLabel(it) }?.takeIf { it.isNotBlank() }

    private fun nodeNearPoint(
        service: ClickyAccessibilityService,
        x: Float,
        y: Float,
    ): UiNode? {
        return service.lastNodes
            .filter { (it.isClickable || it.isEditable) && it.bounds.contains(x.toInt(), y.toInt()) }
            .minByOrNull { it.area }
            ?: service.lastNodes
                .filter { it.isClickable || it.isEditable }
                .minByOrNull { n ->
                    val dx = n.centerX - x
                    val dy = n.centerY - y
                    dx * dx + dy * dy
                }
    }

    /**
     * HARD food-app guard: when the model taps the top address/location chip during a
     * search/restaurant goal, **auto-redirect** to the best Search field in the same
     * tool call (annotate + tap). Soft "blocked, please retry" was not enough for demos.
     *
     * @return non-null [ToolResult] when the original tap was intercepted.
     */
    private suspend fun redirectAddressTapToSearch(
        service: ClickyAccessibilityService,
        target: UiNode?,
        label: String?,
        tapY: Float,
    ): ToolResult? {
        if (!shouldApplyFoodSearchAddressGuard(service)) return null
        val text = (label ?: target?.let { nodeLabel(it) }).orEmpty().trim()
        if (text.contains("search", ignoreCase = true)) return null
        if (target != null && looksLikeSearchField(target)) return null

        val screenH = service.resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val topBandPx = screenH * ADDRESS_TOP_BAND_FRAC
        val inTopBand = tapY < topBandPx ||
            (target != null && target.bounds.top < topBandPx)
        val onFoodApp = isFoodDeliveryPackage(service.lastPackageName)
        // Strong address tokens (Arena/Road/Layout/…) block anywhere; short locality
        // titles only count in the top address band so restaurant rows aren't redirected.
        val strongAddress = matchesStrongAddressPattern(text)
        val shortLocalityInTop = inTopBand && matchesShortLocalityTitle(text)
        // On Swiggy/Zomato: top-band taps that are not explicitly Search → block when a
        // search candidate exists (covers address chips + unlabeled location hits).
        val topBandNonSearch = onFoodApp &&
            inTopBand &&
            !text.contains("search", ignoreCase = true) &&
            (text.isBlank() || !isNonAddressChrome(text))
        val shouldBlock = strongAddress || shortLocalityInTop || topBandNonSearch
        if (!shouldBlock) return null

        val search = findBestSearchNode(service)
        if (search == null) {
            val msg = "blocked: address/location chip (not search) y=${tapY.toInt()} " +
                "label=\"${text.take(48)}\". No Search field in tree — observe again."
            RingBufferLogger.log("tap_guard", msg)
            return ToolResult(msg, success = false, stateChanging = false)
        }

        RingBufferLogger.log(
            "tap_guard",
            "HARD redirect address→search: blocked y=${tapY.toInt()} " +
                "label=\"${text.take(48)}\" → id=${search.id} " +
                "center=${search.centerX},${search.centerY} " +
                "\"${nodeLabel(search).take(48)}\"",
        )
        maybeAutoAnnotateSearchTarget(service, search)
        val density = service.resources.displayMetrics.density
        val (sx, sy) = service.gestureExecutor.preciseTapPoint(search.bounds, density)
        val result = executeNodeTap(
            service = service,
            tapId = search.id,
            requestedId = search.id,
            sx = sx,
            sy = sy,
            label = nodeLabel(search),
            looksToggle = false,
        )
        val redirectNote =
            "REDIRECTED: blocked address/location tap " +
                "(y=${tapY.toInt()} \"${text.take(40)}\") → " +
                "tapped SEARCH id=${search.id} center=${search.centerX},${search.centerY} " +
                "\"${nodeLabel(search).take(48)}\". ${result.text}"
        RingBufferLogger.log("tap_guard", redirectNote)
        return result.copy(
            text = redirectNote,
            success = result.success,
            stateChanging = true,
            settleMs = SETTLE_TAP_MS,
            preferVisionObserve = result.success,
        )
    }

    /**
     * Prefer isEditable, then clickable with Search in text/cd.
     * Exclude top address band (y < 0.18*H) unless text contains Search.
     */
    private fun findBestSearchNode(service: ClickyAccessibilityService): UiNode? {
        val screenH = service.resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val topBandPx = screenH * ADDRESS_TOP_BAND_FRAC
        fun hasSearchLabel(n: UiNode): Boolean =
            nodeLabel(n).contains("search", ignoreCase = true)

        fun eligible(n: UiNode): Boolean {
            if (!looksLikeSearchField(n) && !hasSearchLabel(n)) return false
            // Keep mid-screen search; allow top-band only when explicitly labeled Search.
            if (n.centerY < topBandPx && !hasSearchLabel(n)) return false
            return true
        }

        val pool = service.lastNodes.filter(::eligible)
        if (pool.isEmpty()) {
            // Fallback: any node with Search in label, even in top band.
            return service.lastNodes
                .filter { hasSearchLabel(it) && (it.isClickable || it.isEditable) }
                .minByOrNull { it.centerY }
        }
        return pool.sortedWith(
            compareByDescending<UiNode> { it.isEditable }
                .thenByDescending { hasSearchLabel(it) }
                .thenByDescending { it.isClickable }
                .thenBy { kotlin.math.abs(it.centerY - screenH * 0.28f) },
        ).firstOrNull()
    }

    private fun shouldApplyFoodSearchAddressGuard(service: ClickyAccessibilityService): Boolean {
        val goal = agentState.currentUserGoal
        if (!goalLooksLikeFoodOrSearch(goal)) return false
        val onFoodApp = isFoodDeliveryPackage(service.lastPackageName)
        val goalNamesFoodApp = FOOD_APP_NAME_HINTS.any { goal.lowercase().contains(it) }
        return onFoodApp || goalNamesFoodApp
    }

    private fun isFoodDeliveryPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        if (p.isBlank()) return false
        return FOOD_APP_PACKAGES.any { p.contains(it) }
    }

    private fun matchesStrongAddressPattern(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase().trim()
        if (lower.contains("search")) return false
        if (isNonAddressChrome(lower)) return false
        if (ADDRESS_BLOCKLIST.any { lower.contains(it) }) return true
        return lower.contains(',') && lower.length in 8..80
    }

    /** Short building/locality title like "Salarpuria Arena" (top-band only). */
    private fun matchesShortLocalityTitle(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase().trim()
        if (lower.contains("search")) return false
        if (isNonAddressChrome(lower)) return false
        val words = lower.split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.size in 1..5 &&
            words.all { w -> w.length >= 2 && w.any { ch -> ch.isLetter() } } &&
            words.none { it in NON_ADDRESS_WORDS }
    }

    private fun isNonAddressChrome(text: String): Boolean {
        val lower = text.lowercase()
        return NON_ADDRESS_WORDS.any {
            lower == it || lower.startsWith("$it ") || (it.length > 4 && lower.contains(it))
        }
    }

    /**
     * Auto-highlight search/edit targets before tap when the model skipped annotate
     * (Pilot teaching overlay; improves accuracy on ambiguous bars).
     */
    private suspend fun maybeAutoAnnotateSearchTarget(
        service: ClickyAccessibilityService,
        target: UiNode?,
    ) {
        val node = target ?: return
        if (!looksLikeSearchField(node) && !node.isEditable) return
        if (agentState.wasAnnotated(node.id)) return
        val bounds = Rect(node.bounds)
        if (bounds.isEmpty) return
        val overlay = OverlayService.instance ?: run {
            ensureOverlayStarted()
            delay(150)
            OverlayService.instance
        } ?: return
        val density = overlay.resources.displayMetrics.density
        val pad = 6f * density
        val rect = RectF(bounds)
        rect.inset(-pad, -pad)
        overlay.showAnnotation(
            Annotation.Circle(
                rect = rect,
                color = Annotation.DEFAULT_ACCENT,
                strokeWidth = 4f * density,
                label = "search",
            ),
        )
        agentState.recordAnnotate(node.id)
        RingBufferLogger.log("overlay", "auto-annotate search node_id=${node.id}")
        delay(220)
    }

    private suspend fun executeCoordinateTap(
        service: ClickyAccessibilityService,
        sx: Float,
        sy: Float,
        label: String?,
        looksToggle: Boolean,
    ): ToolResult {
        animateCursor(sx, sy)
        pulseCursor()
        delay(80)
        // Single gesture tap only — never auto-retry with jitter (double-adds / wrong screens).
        val ok = service.gestureExecutor.tap(sx, sy)
        if (ok) {
            agentState.recordTap(
                nodeId = null,
                x = sx,
                y = sy,
                label = label,
                wasToggle = looksToggle,
            )
        }
        val msg = buildString {
            append(if (ok) "tapped $sx,$sy" else "tap failed at $sx,$sy")
            if (looksToggle) append(" (toggle — observe once; do not tap again)")
        }
        return ToolResult(
            text = msg,
            success = ok,
            stateChanging = true,
            settleMs = if (looksToggle) SETTLE_TOGGLE_MS else SETTLE_TAP_MS,
            preferVisionObserve = ok && looksToggle,
            tapX = if (ok) sx else null,
            tapY = if (ok) sy else null,
        )
    }

    private suspend fun executeNodeTap(
        service: ClickyAccessibilityService,
        tapId: Int,
        requestedId: Int,
        sx: Float,
        sy: Float,
        label: String?,
        looksToggle: Boolean,
    ): ToolResult {
        animateCursor(sx, sy)
        pulseCursor()
        delay(80)
        // clickNode is ACTION_CLICK XOR gesture — never both for one attempt.
        // Only if clickNode could not click at all, try one gesture at the resolved point.
        var ok = service.gestureExecutor.clickNode(tapId)
        if (!ok) {
            RingBufferLogger.log("tap_node", "clickNode miss — single gesture at $sx,$sy")
            ok = service.gestureExecutor.tap(sx, sy)
        }
        if (ok) {
            agentState.recordTap(
                nodeId = tapId,
                x = sx,
                y = sy,
                label = label,
                wasToggle = looksToggle,
            )
        }
        val msg = buildString {
            append(if (ok) "tapped node $tapId at $sx,$sy" else "tap_node failed id=$requestedId")
            if (label != null) append(" label=\"$label\"")
            if (looksToggle) append(" (toggle — observe once; do not tap again)")
            if (tapId != requestedId) append(" (preferred smaller target over $requestedId)")
        }
        return ToolResult(
            text = msg,
            success = ok,
            stateChanging = true,
            settleMs = if (looksToggle) SETTLE_TOGGLE_MS else SETTLE_TAP_MS,
            preferVisionObserve = ok && looksToggle,
            tapX = if (ok) sx else null,
            tapY = if (ok) sy else null,
        )
    }

    private fun goalLooksLikeFoodOrSearch(goal: String): Boolean {
        if (goal.isBlank()) return false
        val g = goal.lowercase()
        return SEARCH_GOAL_HINTS.any { g.contains(it) }
    }

    private fun looksLikeSearchField(n: UiNode): Boolean {
        val label = nodeLabel(n).lowercase()
        val cls = n.className.orEmpty()
        if (label.contains("search")) return true
        if (n.isEditable || cls.contains("EditText", ignoreCase = true)) {
            // Editable that looks like a delivery address field is not search.
            if (ADDRESS_BLOCKLIST.any { label.contains(it) } && !label.contains("search")) {
                return false
            }
            return true
        }
        return false
    }

    private suspend fun longPress(args: JsonObject): ToolResult {
        val x = args.float("x") ?: return badArgs("x")
        val y = args.float("y") ?: return badArgs("y")
        val service = a11yOrNull() ?: return missingA11y()
        animateCursor(x, y)
        pulseCursor()
        val ok = service.gestureExecutor.longPress(x, y)
        return ToolResult(
            text = if (ok) "long_pressed $x,$y" else "long_press failed",
            success = ok,
            stateChanging = true,
            settleMs = SETTLE_TAP_MS,
        )
    }

    private suspend fun swipe(args: JsonObject): ToolResult {
        val x1 = args.float("x1") ?: return badArgs("x1")
        val y1 = args.float("y1") ?: return badArgs("y1")
        val x2 = args.float("x2") ?: return badArgs("x2")
        val y2 = args.float("y2") ?: return badArgs("y2")
        val duration = (args.int("duration_ms") ?: 350).toLong()
        val service = a11yOrNull() ?: return missingA11y()
        animateCursor(x1, y1)
        val ok = service.gestureExecutor.swipe(x1, y1, x2, y2, duration)
        if (ok) animateCursor(x2, y2)
        return ToolResult(
            text = if (ok) "swiped ($x1,$y1)->($x2,$y2)" else "swipe failed",
            success = ok,
            stateChanging = true,
            settleMs = SETTLE_NAV_MS,
        )
    }

    private suspend fun scroll(args: JsonObject): ToolResult {
        val dirRaw = args.string("direction")?.lowercase() ?: return badArgs("direction")
        val direction = when (dirRaw) {
            "up" -> GestureExecutor.ScrollDirection.UP
            "down" -> GestureExecutor.ScrollDirection.DOWN
            "left" -> GestureExecutor.ScrollDirection.LEFT
            "right" -> GestureExecutor.ScrollDirection.RIGHT
            else -> return ToolResult("error: invalid direction $dirRaw", success = false)
        }
        val service = a11yOrNull() ?: return missingA11y()
        val ok = service.gestureExecutor.scroll(direction)
        return ToolResult(
            text = if (ok) "scrolled $dirRaw" else "scroll failed",
            success = ok,
            stateChanging = true,
            settleMs = SETTLE_NAV_MS,
        )
    }

    private suspend fun typeText(args: JsonObject): ToolResult {
        val text = args.string("text") ?: return badArgs("text")
        val service = a11yOrNull() ?: return missingA11y()
        val ok = service.gestureExecutor.typeText(text)
        // Settle so search suggestions populate; AgentLoop may attach one vision observe.
        return ToolResult(
            text = if (ok) "typed ${text.length} chars" else "type_text failed (no focused editable?)",
            success = ok,
            stateChanging = true,
            settleMs = SETTLE_TYPE_MS,
            preferVisionObserve = ok,
        )
    }

    private suspend fun pressKey(args: JsonObject): ToolResult {
        val key = args.string("key")?.lowercase() ?: return badArgs("key")
        val service = a11yOrNull() ?: return missingA11y()
        val ok = when (key) {
            "back" -> service.gestureExecutor.back()
            "home" -> service.gestureExecutor.home()
            "recents" -> service.gestureExecutor.recents()
            "notifications" -> service.gestureExecutor.notifications()
            else -> return ToolResult("error: unknown key $key", success = false)
        }
        return ToolResult(
            text = if (ok) "pressed $key" else "press_key $key failed",
            success = ok,
            stateChanging = true,
            settleMs = SETTLE_NAV_MS,
        )
    }

    private suspend fun openApp(args: JsonObject): ToolResult {
        val query = args.string("query")?.trim().orEmpty()
        if (query.isEmpty()) return badArgs("query")
        val service = a11yOrNull() ?: return missingA11y()
        val match = fuzzyFindApp(service.packageManager, query)
            ?: return ToolResult("error: no app matching \"$query\"", success = false)

        // Short-circuit: already foreground — do not relaunch (demo double-open fix).
        val currentPkg = currentForegroundPackage(service)
        val foodApp = isFoodDeliveryPackage(match.packageName)
        if (packagesMatch(currentPkg, match.packageName)) {
            RingBufferLogger.log(
                "open_app",
                "already open pkg=$currentPkg target=${match.packageName}",
            )
            // Food apps: one vision observe helps address vs search; other apps stay tree-only.
            return ToolResult(
                text = "already open: package=${match.packageName} (skipped relaunch)",
                success = true,
                stateChanging = foodApp,
                settleMs = if (foodApp) SETTLE_TAP_MS else 0L,
                preferVisionObserve = foodApp,
            )
        }

        val ok = service.gestureExecutor.openApp(match.packageName)
        val needsVision = ok && needsVisionAfterOpen(match.packageName, match.label)
        return ToolResult(
            text = if (ok) {
                "opened ${match.label} (${match.packageName})"
            } else {
                "open_app failed for ${match.packageName}"
            },
            success = ok,
            stateChanging = true,
            // Keep longer settle for Swiggy/Uber/Instagram home draw.
            settleMs = SETTLE_OPEN_APP_MS,
            preferVisionObserve = needsVision,
        )
    }

    /** Vision after open only for apps where the home screen is often ambiguous. */
    private fun needsVisionAfterOpen(packageName: String, label: String): Boolean {
        val blob = "$packageName $label".lowercase()
        return VISION_AFTER_OPEN_HINTS.any { blob.contains(it) }
    }

    private suspend fun annotate(args: JsonObject): ToolResult {
        val overlay = OverlayService.instance
        if (overlay == null) {
            ensureOverlayStarted()
        }
        val ov = OverlayService.instance
            ?: return ToolResult("error: overlay not running", success = false)

        val type = args.string("type")?.lowercase() ?: return badArgs("type")
        val nodeId = args.int("node_id")
        val nodeBounds = resolveAnnotateBounds(nodeId)
        val x2 = args.floatOrNull("x2")
        val y2 = args.floatOrNull("y2")
        val label = args.stringOrNull("label")
        val density = ov.resources.displayMetrics.density

        // Prefer exact a11y bounds when node_id is present (fixes coach highlight offset).
        val centerX = nodeBounds?.exactCenterX()
        val centerY = nodeBounds?.exactCenterY()
        val x = centerX ?: args.float("x")
        val y = centerY ?: args.float("y")
        if (x == null || y == null) {
            return badArgs("x/y or node_id")
        }

        val annotation: Annotation = when (type) {
            "circle" -> {
                if (nodeBounds != null && !nodeBounds.isEmpty) {
                    val pad = 6f * density
                    val rect = RectF(nodeBounds)
                    rect.inset(-pad, -pad)
                    Annotation.Circle(
                        rect = rect,
                        color = Annotation.DEFAULT_ACCENT,
                        strokeWidth = 4f * density,
                        label = label,
                    )
                } else {
                    val r = 48f * density
                    Annotation.Circle(
                        rect = RectF(x - r, y - r, x + r, y + r),
                        color = Annotation.DEFAULT_ACCENT,
                        strokeWidth = 4f * density,
                        label = label,
                    )
                }
            }
            "arrow" -> {
                // Tip prefers exact node center; shaft starts at model x/y or just above the tip.
                val toX = when {
                    nodeBounds != null -> nodeBounds.exactCenterX()
                    else -> x2 ?: (x + 80f * density)
                }
                val toY = when {
                    nodeBounds != null -> nodeBounds.exactCenterY()
                    else -> y2 ?: y
                }
                val fromX = args.float("x") ?: (toX - 40f * density)
                val fromY = args.float("y") ?: (toY - 72f * density)
                Annotation.Arrow(
                    fromX = fromX,
                    fromY = fromY,
                    toX = toX,
                    toY = toY,
                    label = label,
                )
            }
            "highlight" -> {
                if (nodeBounds != null && !nodeBounds.isEmpty) {
                    val pad = 4f * density
                    val rect = RectF(nodeBounds)
                    rect.inset(-pad, -pad)
                    Annotation.Highlight(rect = rect)
                } else {
                    val left = minOf(x, x2 ?: (x + 120f * density))
                    val top = minOf(y, y2 ?: (y + 48f * density))
                    val right = maxOf(x, x2 ?: (x + 120f * density))
                    val bottom = maxOf(y, y2 ?: (y + 48f * density))
                    Annotation.Highlight(rect = RectF(left, top, right, bottom))
                }
            }
            "callout" -> Annotation.Callout(
                x = x,
                y = y,
                text = label?.takeIf { it.isNotBlank() } ?: "Look here",
            )
            "ripple" -> Annotation.Ripple(x = x, y = y)
            else -> return ToolResult("error: unknown annotate type $type", success = false)
        }
        ov.showAnnotation(annotation)
        agentState.recordAnnotate(nodeId)
        val where = if (nodeId != null && nodeBounds != null) {
            "node_id=$nodeId bounds=${nodeBounds.flattenToString()}"
        } else {
            "$x,$y"
        }
        RingBufferLogger.log("overlay", "annotate $type $where")
        return ToolResult("annotated $type at $where", success = true)
    }

    /** Exact screen bounds for annotate(node_id) from the last observe_screen tree. */
    private fun resolveAnnotateBounds(nodeId: Int?): Rect? {
        if (nodeId == null) return null
        val service = ClickyAccessibilityService.instance ?: return null
        val stored = service.lastNodes.firstOrNull { it.id == nodeId }
        if (stored != null && !stored.bounds.isEmpty) {
            return Rect(stored.bounds)
        }
        val live = service.nodeCache[nodeId]?.get() ?: return null
        val bounds = Rect()
        live.getBoundsInScreen(bounds)
        return if (bounds.isEmpty) null else bounds
    }

    private suspend fun speak(args: JsonObject): ToolResult {
        val text = args.string("text") ?: return badArgs("text")
        val languageArg = args.stringOrNull("language")
        val language = languageArg
            ?: agentState.lastDetectedLanguage
            ?: preferenceStore.get("language")
        RingBufferLogger.log("speak", "lang=${language ?: "default"} text=$text")
        // Non-blocking: kick off TTS (~200ms) and return so the agent can plan/act while narrating.
        val result = speaker.speakAsync(text, language)
        return result.fold(
            onSuccess = {
                agentState.recordSpeak()
                ToolResult(
                    text = "spoke (async): $text (lang=${language ?: "en-IN"})",
                    success = true,
                )
            },
            onFailure = { e ->
                val msg = e.message ?: "TTS failed"
                // Still surface text so demos aren't silent on key/network issues.
                toast(text)
                // Kickoff failure must not block the next tool (e.g. open_app).
                agentState.recordSpeak()
                ToolResult(
                    text = "speak error: $msg (showed toast)",
                    success = false,
                )
            },
        )
    }

    private fun getPreferences(): ToolResult {
        val text = preferenceStore.formatAsText()
        RingBufferLogger.log("prefs", "get_preferences → ${text.take(200)}")
        return ToolResult(text = text, success = true)
    }

    private fun setPreference(args: JsonObject): ToolResult {
        val key = args.string("key") ?: return badArgs("key")
        val value = args.string("value") ?: return badArgs("value")
        if (!preferenceStore.set(key, value)) {
            return ToolResult(
                text = "error: invalid preference key/value " +
                    "(key: lowercase snake_case a-z0-9_, non-empty value)",
                success = false,
            )
        }
        return ToolResult(
            text = "saved preference ${key.trim().lowercase()}=${value.trim()}",
            success = true,
        )
    }

    private suspend fun waitMs(args: JsonObject): ToolResult {
        val ms = (args.int("ms") ?: 300).coerceIn(0, 10_000)
        delay(ms.toLong())
        return ToolResult("waited ${ms}ms", success = true)
    }

    private fun finish(args: JsonObject): ToolResult {
        val summary = args.string("summary") ?: "done"
        refreshScreenContextForCheckoutGuard()
        checkoutIncompleteBlockReason()?.let { blocked ->
            RingBufferLogger.log("finish", blocked)
            return ToolResult(
                text = blocked,
                success = false,
                finished = false,
            )
        }
        return ToolResult(
            text = "finished: $summary",
            success = true,
            finished = true,
            summary = summary,
        )
    }

    /**
     * Grocery/food checkout apps: cart/address without a pay attempt is not done.
     * Returns a blocked tool result text, or null if finish is allowed.
     */
    private fun checkoutIncompleteBlockReason(): String? {
        if (agentState.attemptedPayThisTurn) return null
        val screen = agentState.lastScreenContextText
        if (screen.isBlank()) return null
        val pkg = Regex("""packageName=([^\s]+)""")
            .find(screen)?.groupValues?.getOrNull(1).orEmpty()
        if (!isCheckoutAppPackage(pkg) && !isCheckoutAppPackage(screen)) return null
        val lower = screen.lowercase()
        val showsCartOrAddress = CHECKOUT_MIDFLOW_KEYWORDS.any { lower.contains(it) }
        if (!showsCartOrAddress) return null
        return "blocked: checkout incomplete — continue to address and pay"
    }

    private fun refreshScreenContextForCheckoutGuard() {
        val service = a11yOrNull() ?: return
        val ctx = runCatching { service.screenReader.captureContext() }.getOrNull() ?: return
        agentState.recordScreenContext(ctx.asPromptText)
    }

    private fun isCheckoutAppPackage(text: String): Boolean {
        val t = text.lowercase()
        return CHECKOUT_APP_PACKAGES.any { t.contains(it) }
    }

    private fun trackToolSideEffects(name: String, result: ToolResult) {
        if (name == "speak") return // recordSpeak handled inside speak()
        if (result.stateChanging) {
            agentState.recordStateChangingTool()
        }
        if (result.success && (name == "tap" || name == "tap_node") &&
            looksLikePayAction(result.text)
        ) {
            agentState.recordPayAttempt()
            RingBufferLogger.log("checkout", "pay attempt recorded from $name")
        }
    }

    private fun looksLikePayAction(toolResultText: String): Boolean {
        val lower = toolResultText.lowercase()
        return PAY_ACTION_KEYWORDS.any { lower.contains(it) }
    }

    private suspend fun animateCursor(x: Float, y: Float): Boolean {
        val overlay = OverlayService.instance ?: run {
            ensureOverlayStarted()
            delay(200)
            OverlayService.instance
        } ?: return false
        return overlay.animateCursorTo(x, y)
    }

    private suspend fun pulseCursor() {
        withContext(Dispatchers.Main.immediate) {
            OverlayService.instance?.cursorClickPulse()
        }
        delay(120)
    }

    private fun a11yOrNull(): ClickyAccessibilityService? {
        val s = ClickyAccessibilityService.instance
        if (s == null) ClickyAccessibilityService.showEnableToast()
        return s
    }

    private fun missingA11y(): ToolResult =
        ToolResult("error: accessibility service not enabled", success = false)

    private fun badArgs(field: String): ToolResult =
        ToolResult("error: missing/invalid argument $field", success = false)

    private fun currentForegroundPackage(service: ClickyAccessibilityService): String {
        if (service.lastPackageName.isNotBlank()) return service.lastPackageName
        return runCatching {
            service.rootInActiveWindow?.packageName?.toString().orEmpty()
        }.getOrDefault("")
    }

    private fun packagesMatch(current: String, target: String): Boolean {
        if (current.isBlank() || target.isBlank()) return false
        val c = current.lowercase()
        val t = target.lowercase()
        return c == t || c.startsWith("$t.") || t.startsWith("$c.")
    }

    /**
     * Resolve tap point for a node id: live precise center preferred, else stored bounds.
     * If the requested node is a huge video-like target and a smaller clickable text/button
     * with the same label exists nearby, prefer that smaller node.
     */
    private fun resolveTapNodePoint(
        service: ClickyAccessibilityService,
        id: Int,
    ): Triple<Int, Float, Float>? {
        val meta = service.lastNodes
        val requested = meta.firstOrNull { it.id == id }
        val preferred = preferSmallerTextTarget(requested, meta) ?: requested
        val tapId = preferred?.id ?: id
        val density = service.resources.displayMetrics.density
        val executor = service.gestureExecutor

        val live = service.nodeCache[tapId]?.get()
        if (live != null) {
            val bounds = Rect()
            live.getBoundsInScreen(bounds)
            if (boundsOnScreen(service, bounds)) {
                val (sx, sy) = executor.preciseTapPoint(bounds, density)
                return Triple(tapId, sx, sy)
            }
        }
        val stored = preferred ?: meta.firstOrNull { it.id == id }
        if (stored != null && boundsOnScreen(service, stored.bounds)) {
            val (sx, sy) = executor.preciseTapPoint(stored.bounds, density)
            return Triple(stored.id, sx, sy)
        }
        // Last resort: any live cached node for original id.
        val origLive = service.nodeCache[id]?.get()
        if (origLive != null) {
            val bounds = Rect()
            origLive.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                val (sx, sy) = executor.preciseTapPoint(bounds, density)
                return Triple(id, sx, sy)
            }
        }
        return null
    }

    private fun preferSmallerTextTarget(
        requested: UiNode?,
        all: List<UiNode>,
    ): UiNode? {
        if (requested == null) return null
        if (!looksHugeMedia(requested)) return requested
        val label = nodeLabel(requested)
        if (label.isBlank()) return requested
        val nearby = all.filter { n ->
            n.id != requested.id &&
                n.isClickable &&
                n.area < requested.area * 0.45f &&
                n.area in 400..(requested.area / 2) &&
                labelsMatch(nodeLabel(n), label) &&
                centersNear(requested, n, maxDistPx = 220f)
        }
        val better = nearby.minByOrNull { it.area }
        if (better != null) {
            RingBufferLogger.log(
                "tap_node",
                "prefer smaller id=${better.id} area=${better.area} over huge id=${requested.id} area=${requested.area}",
            )
            return better
        }
        return requested
    }

    private fun looksHugeMedia(n: UiNode): Boolean {
        val cls = n.className.orEmpty()
        val mediaLike = cls.contains("Image", ignoreCase = true) ||
            cls.contains("Video", ignoreCase = true) ||
            cls.contains("Texture", ignoreCase = true) ||
            (n.contentDescription?.contains("reel", ignoreCase = true) == true) ||
            (n.contentDescription?.contains("video", ignoreCase = true) == true)
        // Large clickable covering a big fraction of a typical phone screen.
        return n.area >= 180_000 || (mediaLike && n.area >= 80_000)
    }

    private fun nodeLabel(n: UiNode): String {
        val text = n.text?.trim().orEmpty()
        val desc = n.contentDescription?.trim().orEmpty()
        return when {
            text.isNotEmpty() && desc.isNotEmpty() ->
                if (text.length >= desc.length) text else desc
            text.isNotEmpty() -> text
            else -> desc
        }
    }

    private fun labelsMatch(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        val x = a.lowercase()
        val y = b.lowercase()
        return x == y || x.contains(y) || y.contains(x)
    }

    private fun centersNear(a: UiNode, b: UiNode, maxDistPx: Float): Boolean {
        val dx = (a.centerX - b.centerX).toFloat()
        val dy = (a.centerY - b.centerY).toFloat()
        return dx * dx + dy * dy <= maxDistPx * maxDistPx
    }

    private fun boundsOnScreen(service: ClickyAccessibilityService, bounds: Rect): Boolean {
        if (bounds.isEmpty || bounds.width() <= 0 || bounds.height() <= 0) return false
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        return bounds.right > 0 && bounds.bottom > 0 &&
            bounds.left < w && bounds.top < h
    }

    private fun clampOnScreen(
        service: ClickyAccessibilityService,
        x: Float,
        y: Float,
    ): Pair<Float, Float> {
        val dm = service.resources.displayMetrics
        val maxX = (dm.widthPixels - 1).coerceAtLeast(0)
        val maxY = (dm.heightPixels - 1).coerceAtLeast(0)
        // Round then clamp so taps use stable integer pixels (no float truncation drift).
        val rx = x.roundToInt().coerceIn(0, maxX).toFloat()
        val ry = y.roundToInt().coerceIn(0, maxY).toFloat()
        return rx to ry
    }

    private fun ensureOverlayStarted() {
        val ctx = ClickyApp.appContextOrNull() ?: return
        runCatching { OverlayService.start(ctx) }
    }

    private fun toast(msg: String) {
        mainHandler.post {
            runCatching {
                val ctx = ClickyApp.appContextOrNull() ?: return@runCatching
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private data class AppMatch(val label: String, val packageName: String)

    private fun fuzzyFindApp(pm: PackageManager, query: String): AppMatch? {
        val q = query.trim().lowercase()
            .removePrefix("open ")
            .removePrefix("launch ")
            .removePrefix("start ")
            .trim()
        if (q.isEmpty()) return null

        // Hard aliases for common apps (hackathon reliability).
        APP_ALIASES[q]?.let { pkg ->
            val launch = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
            if (launch != null) {
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(q)
                RingBufferLogger.log("open_app", "alias $q → $pkg")
                return AppMatch(label, pkg)
            }
        }

        // Exact package name.
        if (q.contains('.')) {
            val launch = runCatching { pm.getLaunchIntentForPackage(q) }.getOrNull()
            if (launch != null) {
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(q, 0)).toString()
                }.getOrDefault(q)
                return AppMatch(label, q)
            }
        }

        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }.getOrDefault(emptyList())

        data class Scored(val match: AppMatch, val score: Int)

        val scored = apps.mapNotNull { ri ->
            val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty()
            if (label.isEmpty()) return@mapNotNull null
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            val l = label.lowercase()
            val p = pkg.lowercase()
            val score = when {
                l == q -> 1000
                p == q -> 980
                p.endsWith(".$q") || p.contains(".$q.") -> 950
                p.contains(q) -> 900
                l.startsWith(q) -> 800
                l.contains(q) -> 600
                q.contains(l) && l.length >= 3 -> 400
                else -> {
                    val dist = levenshtein(l, q)
                    if (dist <= 2) 300 - dist else return@mapNotNull null
                }
            }
            Scored(AppMatch(label, pkg), score)
        }
        val best = scored.maxByOrNull { it.score }?.match
        if (best != null) {
            RingBufferLogger.log("open_app", "fuzzy \"$q\" → ${best.packageName} (${best.label})")
        } else {
            RingBufferLogger.log("open_app", "no match for \"$q\"")
        }
        return best
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in a.indices) {
            cur[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1, prev[j] + cost)
            }
            for (j in prev.indices) prev[j] = cur[j]
        }
        return prev[b.length]
    }

    private fun JsonObject.string(key: String): String? {
        val el = this[key] ?: return null
        if (el is JsonNull) return null
        return el.jsonPrimitive.contentOrNull
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val el = this[key] ?: return null
        if (el is JsonNull) return null
        return el.jsonPrimitive.contentOrNull
    }

    private fun JsonObject.float(key: String): Float? {
        val el = this[key] ?: return null
        if (el is JsonNull) return null
        return el.jsonPrimitive.floatOrNull
    }

    private fun JsonObject.floatOrNull(key: String): Float? = float(key)

    private fun JsonObject.int(key: String): Int? {
        val el = this[key] ?: return null
        if (el is JsonNull) return null
        return el.jsonPrimitive.intOrNull
    }

    private fun JsonObject.bool(key: String): Boolean? {
        val el = this[key] ?: return null
        if (el is JsonNull) return null
        return el.jsonPrimitive.booleanOrNull
    }

    companion object {
        /** Settle after typing so search results / suggestions can appear (~27% tighter). */
        const val SETTLE_TYPE_MS = 400L
        /** Settle after open_app — keep longer for Swiggy/Uber/Instagram home draw. */
        const val SETTLE_OPEN_APP_MS = 1_050L
        /** Settle after tap / long-press that may change screens (~29% tighter). */
        const val SETTLE_TAP_MS = 320L
        /** Extra settle after Follow/Unfollow/Like/Save so label can flip before observe. */
        const val SETTLE_TOGGLE_MS = 520L
        /** Settle after swipe / scroll / system keys (~28% tighter). */
        const val SETTLE_NAV_MS = 360L

        private val COACH_BLOCKED = setOf(
            "tap",
            "tap_node",
            "long_press",
            "swipe",
            "scroll",
            "type_text",
            "press_key",
        )

        /** Top ~18% of screen = address/location chip band on Swiggy Food home. */
        private const val ADDRESS_TOP_BAND_FRAC = 0.18f

        private val SEARCH_GOAL_HINTS = listOf(
            "search", "biryani", "restaurant", "food", "order", "book",
            "swiggy", "zomato", "meghana", "deliver", "dish", "cuisine",
            "find food", "order from", "sweets", "order me",
            "zepto", "blinkit", "instamart", "grocery",
        )

        private val FOOD_APP_PACKAGES = listOf(
            "swiggy", "zomato", "in.swiggy", "application.zomato",
            "foodpanda", "ubereats", "dunzo",
            "zepto", "blinkit", "grofers", "instamart",
        )

        /** Home screens where a single post-open vision pass is worth the latency. */
        private val VISION_AFTER_OPEN_HINTS = listOf(
            "swiggy", "zomato", "zepto", "blinkit", "grofers", "instamart",
            "instagram", "uber", "ola", "olacabs",
        )

        private val FOOD_APP_NAME_HINTS = listOf(
            "swiggy", "zomato", "foodpanda", "ubereats", "dunzo",
            "zepto", "blinkit", "instamart", "grocery",
        )

        /** Zepto / Blinkit / Instamart / Swiggy / Zomato — cart is not done. */
        private val CHECKOUT_APP_PACKAGES = listOf(
            "zepto", "com.zeptoconsumerapp",
            "blinkit", "grofers", "com.grofers",
            "swiggy", "in.swiggy", "instamart",
            "zomato", "application.zomato",
        )

        private val CHECKOUT_MIDFLOW_KEYWORDS = listOf(
            "cart", "my cart", "basket",
            "address", "delivery address", "select address", "change address",
            "deliver to", "delivery to",
            "proceed to pay", "place order", "checkout",
        )

        private val PAY_ACTION_KEYWORDS = listOf(
            "pay", "place order", "proceed to pay", "proceed to payment",
            "make payment", "confirm & pay", "confirm and pay",
            "checkout", "continue to pay", "pay now",
        )

        private val ADDRESS_BLOCKLIST = listOf(
            "deliver to", "delivery to", "location", "address",
            "home", "work", "other", "current location", "choose location",
            "salarpur", "arena", "apartment", "flat", "society", "layout", "nagar",
            "sector", "phase", "colony", "road", "street", "avenue", "locality",
            "bengaluru", "bangalore", "mumbai", "delhi", "hyderabad",
        )

        /** UI chrome / actions that sit in the top band but are not addresses. */
        private val NON_ADDRESS_WORDS = setOf(
            "search", "cart", "menu", "offers", "offer", "filter", "filters",
            "profile", "account", "back", "close", "skip", "login", "sign",
            "buy", "one", "get", "free", "help", "wallet", "money", "pay",
            "notif", "notification", "bell", "food", "instamart", "dineout",
            "genie", "gourmet", "categories", "reorder",
        )

        private val APP_ALIASES = mapOf(
            "instagram" to "com.instagram.android",
            "insta" to "com.instagram.android",
            "ig" to "com.instagram.android",
            "swiggy" to "in.swiggy.android",
            "instamart" to "in.swiggy.android",
            "zomato" to "com.application.zomato",
            "zepto" to "com.zeptoconsumerapp",
            "blinkit" to "com.grofers.customerapp",
            "grofers" to "com.grofers.customerapp",
            "chrome" to "com.android.chrome",
            "youtube" to "com.google.android.youtube",
            "yt" to "com.google.android.youtube",
            "maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "whatsapp" to "com.whatsapp",
            "settings" to "com.android.settings",
            "camera" to "com.android.camera",
            "photos" to "com.google.android.apps.photos",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "tiktok" to "com.zhiliaoapp.musically",
            "spotify" to "com.spotify.music",
            "messages" to "com.google.android.apps.messaging",
            "phone" to "com.google.android.dialer",
        )
    }
}
