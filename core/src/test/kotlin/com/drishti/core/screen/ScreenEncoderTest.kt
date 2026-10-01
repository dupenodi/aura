package com.drishti.core.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenEncoderTest {

    private fun text(t: String, b: Bounds, heading: Boolean = false) =
        UiNode(cls = "TextView", text = t, bounds = b, flags = if (heading) UiNode.HEADING else 0)

    /** A Pixel Settings row: the LinearLayout is clickable, its words are in child TextViews. */
    private fun row(title: String, summary: String?, top: Int, key: Int, switchOn: Boolean? = null): UiNode {
        val b = Bounds(0, top, 1080, top + 200)
        val kids = buildList {
            add(text(title, Bounds(160, top + 40, 900, top + 100)))
            summary?.let { add(text(it, Bounds(160, top + 110, 900, top + 160))) }
            switchOn?.let {
                add(
                    UiNode(
                        cls = "Switch",
                        viewId = "switch_widget",
                        bounds = Bounds(920, top + 60, 1040, top + 140),
                        flags = UiNode.CHECKABLE or (if (it) UiNode.CHECKED else 0),
                    ),
                )
            }
        }
        return UiNode(cls = "LinearLayout", bounds = b, flags = UiNode.CLICKABLE, children = kids, key = key)
    }

    private fun settingsScreen(): Screen {
        val list = UiNode(
            cls = "RecyclerView",
            viewId = "recycler_view",
            bounds = Bounds(0, 300, 1080, 2400),
            flags = UiNode.SCROLLABLE or UiNode.SCROLL_FORWARD,
            children = listOf(
                row("Internet", "Wi-Fi network", 300, key = 10),
                row("Airplane mode", null, 500, key = 11, switchOn = false),
                row("Hotspot & tethering", "Off", 700, key = 12),
                // Pushed below the fold by the list: must not be listed.
                row("Data Saver", "Off", 2600, key = 13),
            ),
        )
        val root = UiNode(
            cls = "FrameLayout",
            bounds = Bounds(0, 0, 1080, 2400),
            children = listOf(
                UiNode(
                    cls = "ImageButton",
                    desc = "Navigate up",
                    bounds = Bounds(0, 120, 150, 270),
                    flags = UiNode.CLICKABLE,
                    key = 1,
                ),
                text("Network & internet", Bounds(170, 140, 900, 250), heading = true),
                list,
            ),
        )
        return Screen(pkg = "com.android.settings", appLabel = "Settings", width = 1080, height = 2400, roots = listOf(root))
    }

    @Test
    fun rowsCarryTheirChildWordsAndSwitchState() {
        val enc = ScreenEncoder.encode(settingsScreen())
        val text = enc.text
        assertTrue(text.startsWith("App: Settings (com.android.settings) · Title: Network & internet"), text)
        assertTrue(text.contains("item \"Internet\" (Wi-Fi network)"), text)
        assertTrue(text.contains("switch \"Airplane mode\" [off]"), text)
        assertTrue(text.contains("button \"Navigate up\""), text)
        assertFalse(text.contains("Data Saver"), "off-screen rows are not listed:\n$text")
        assertFalse(text.contains("TextView"), "no class names leak:\n$text")
        // The heading is the title, so it isn't repeated as a context line.
        assertEquals(1, Regex("Network & internet").findAll(text).count(), text)
    }

    @Test
    fun referencesPointAtTheClickableRowNotItsText() {
        val enc = ScreenEncoder.encode(settingsScreen())
        val internet = enc.targets.values.first { it.label == "Internet" }
        assertEquals(10, internet.nodeKey)
        assertEquals(Bounds(0, 300, 1080, 500), internet.bounds)
        // The switch inside the Airplane row is not a separate target: the row is.
        assertFalse(enc.targets.values.any { it.role == "switch" && it.nodeKey == -1 })
    }

    @Test
    fun readingOrderAndScrollHint() {
        val enc = ScreenEncoder.encode(settingsScreen())
        val labels = enc.targets.values.map { it.label }
        assertEquals(listOf("Navigate up", "list", "Internet", "Airplane mode", "Hotspot & tethering"), labels)
        assertTrue(enc.text.contains("list (scroll down for more)"), enc.text)
    }

    @Test
    fun isMuchSmallerThanTheRawTree() {
        val enc = ScreenEncoder.encode(settingsScreen())
        assertTrue(enc.text.length < 500, "encoded ${enc.text.length} chars:\n${enc.text}")
    }

    @Test
    fun fieldsShowHintAsLabelAndTextAsValue() {
        val field = UiNode(
            cls = "EditText",
            text = "wifi",
            hint = "Search settings",
            bounds = Bounds(100, 200, 900, 320),
            flags = UiNode.EDITABLE or UiNode.CLICKABLE or UiNode.FOCUSED,
        )
        val enc = ScreenEncoder.encode(Screen("com.x", 1080, 2400, listOf(field)))
        assertTrue(enc.text.contains("field \"Search settings\" = \"wifi\" [typing here]"), enc.text)
    }

    @Test
    fun passwordsAreNeverSent() {
        val field = UiNode(
            cls = "EditText",
            text = "hunter2",
            hint = "Password",
            bounds = Bounds(100, 200, 900, 320),
            flags = UiNode.EDITABLE or UiNode.PASSWORD,
        )
        val enc = ScreenEncoder.encode(Screen("com.x", 1080, 2400, listOf(field)))
        assertFalse(enc.text.contains("hunter2"), enc.text)
    }

    @Test
    fun blindSurfacesMarkTheScreenSparse() {
        val game = UiNode(cls = "SurfaceView", bounds = Bounds(0, 0, 1080, 2400))
        assertTrue(ScreenEncoder.encode(Screen("com.game", 1080, 2400, listOf(game))).sparse)
        assertFalse(ScreenEncoder.encode(settingsScreen()).sparse)
    }

    @Test
    fun movedOnIgnoresSmallRedrawsButNotNewScreens() {
        val a = ScreenEncoder.encode(settingsScreen())
        val same = ScreenEncoder.encode(settingsScreen())
        assertFalse(ScreenEncoder.movedOn(a, same))

        val other = Screen(
            pkg = "com.android.settings",
            width = 1080,
            height = 2400,
            title = "Internet",
            roots = listOf(UiNode(cls = "TextView", text = "Wi-Fi", bounds = Bounds(0, 300, 1080, 500))),
        )
        assertTrue(ScreenEncoder.movedOn(a, ScreenEncoder.encode(other)))
    }

    @Test
    fun generatedIdsAreNotUsedAsLabels() {
        val icon = UiNode(cls = "ImageView", viewId = "a3", bounds = Bounds(0, 0, 100, 100), flags = UiNode.CLICKABLE)
        val enc = ScreenEncoder.encode(Screen("com.x", 1080, 2400, listOf(icon)))
        assertTrue(enc.text.contains("unlabelled ImageView"), enc.text)
    }
}
