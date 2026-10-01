package com.drishti.core.sim

import com.drishti.core.screen.Bounds

/**
 * A Pixel 6 on Android 15, as far as the golden tasks need: the launcher, Settings with its
 * real section names and nesting, WhatsApp, YouTube, Phone and Clock.
 *
 * Settings rows, titles and summaries follow the shipping Pixel build, because the model's
 * prior knowledge of where things live is half of what is being tested. YouTube raises no
 * click events (like many Compose apps), so completion there has to come from the screen.
 */
object PixelWorld {
    const val SETTINGS = "com.android.settings"
    const val WHATSAPP = "com.whatsapp"
    const val YOUTUBE = "com.google.android.youtube"
    const val DIALER = "com.google.android.dialer"
    const val CLOCK = "com.google.android.deskclock"
    const val PLAY = "com.android.vending"
    const val CHROME = "com.android.chrome"
    const val MESSAGES = "com.google.android.apps.messaging"
    const val CAMERA = "com.google.android.GoogleCamera"
    const val PHOTOS = "com.google.android.apps.photos"
    const val GMAIL = "com.google.android.gm"
    const val PHONEPE = "com.phonepe.app"

    val apps = listOf(
        SimApp("Settings", SETTINGS, "settings"),
        SimApp("WhatsApp", WHATSAPP, "wa_chats"),
        SimApp("YouTube", YOUTUBE, "yt_home"),
        SimApp("Phone", DIALER, "dialer"),
        SimApp("Clock", CLOCK, "clock_alarms"),
        SimApp("Play Store", PLAY, "play_home"),
        SimApp("Chrome", CHROME, "stub_chrome"),
        SimApp("Messages", MESSAGES, "stub_messages"),
        SimApp("Camera", CAMERA, "stub_camera"),
        SimApp("Photos", PHOTOS, "stub_photos"),
        SimApp("Gmail", GMAIL, "stub_gmail"),
        SimApp("PhonePe", PHONEPE, "stub_phonepe"),
    )

    fun phone(): SimPhone = SimPhone(screens(), apps)

    private fun screens(): List<SimScreen> = buildList {
        addAll(launcher())
        addAll(settings())
        addAll(whatsapp())
        addAll(youtube())
        addAll(dialer())
        addAll(clock())
        add(SimScreen("play_home", PLAY, "Google Play") { appBar("Google Play", back = false, actions = listOf(IconAction("Search", Tap.Run { null }))); text("Recommended for you") })
        listOf("chrome" to CHROME, "messages" to MESSAGES, "camera" to CAMERA, "photos" to PHOTOS, "gmail" to GMAIL, "phonepe" to PHONEPE)
            .forEach { (n, pkg) -> add(SimScreen("stub_$n", pkg, n.replaceFirstChar { it.uppercase() }) { appBar(n.replaceFirstChar { it.uppercase() }, back = false) }) }
    }

    // ---- Launcher -------------------------------------------------------------------------

    private fun launcher() = listOf(
        SimScreen("launcher", SimPhone.LAUNCHER_PKG, null) {
            text("Wednesday, 1 Oct", h = 150)
            appGrid(
                listOf("Gmail" to GMAIL, "Photos" to PHOTOS, "YouTube" to YOUTUBE, "WhatsApp" to WHATSAPP, "Play Store" to PLAY, "Clock" to CLOCK),
            )
            // Dock, along the bottom as on a Pixel.
            val dockTop = height - 130 - 280
            listOf("Phone" to DIALER, "Messages" to MESSAGES, "Chrome" to CHROME, "Camera" to CAMERA).forEachIndexed { i, (label, pkg) ->
                icon(label, Bounds(i * width / 4, dockTop, (i + 1) * width / 4, dockTop + 260), Tap.Launch(pkg), cls = "TextView")
            }
            icon("Google Search", Bounds(60, dockTop - 220, width - 60, dockTop - 60), Tap.Run { null })
            // Swipe up opens all apps; exposed as a scrollable surface.
            list(reserveBottom = 520, rowHeight = 2000) { row("Home screen", "Swipe up to see all apps", tap = null, icon = false); row("All apps", tap = null, icon = false) }
        }.also { it.scrollTo = "app_drawer" },
        SimScreen("app_drawer", SimPhone.LAUNCHER_PKG, null) {
            searchBar("Search your phone and more", "drawer_q")
            appGrid(apps.sortedBy { it.label }.map { it.label to it.pkg }, columns = 4)
        },
    )

    // ---- Settings -------------------------------------------------------------------------

    private fun settings(): List<SimScreen> = listOf(
        SimScreen("settings", SETTINGS, "Settings") {
            bigTitle("Settings")
            searchButton("Search Settings", Tap.Go("settings_search"))
            list {
                row("Network & internet", "Mobile, Wi‑Fi, hotspot", Tap.Go("network"))
                row("Connected devices", "Bluetooth, pairing", Tap.Go("connected"))
                row("Apps", "Assistant, recent apps, default apps", Tap.Go("apps"))
                row("Notifications", "Notification history, conversations", Tap.Go("stub_settings_notifications"))
                row("Battery", "86% - Should last until about 11:30 PM", Tap.Go("battery"))
                row("Storage", "34% used - 84.20 GB free", Tap.Go("stub_settings_storage"))
                row("Sound & vibration", "Volume, haptics, Do Not Disturb", Tap.Go("sound"))
                row("Display & touch", "Dark theme, font size, touch", Tap.Go("display"))
                row("Wallpaper & style", "Colours, themed icons, app grid", Tap.Go("stub_settings_wallpaper"))
                row("Accessibility", "Display, interaction, audio", Tap.Go("accessibility"))
                row("Security & privacy", "App security, device lock, permissions", Tap.Go("stub_settings_security"))
                row("Location", "On - 6 apps have access to location", Tap.Go("stub_settings_location"))
                row("Safety & emergency", "Emergency SOS, medical info, alerts", Tap.Go("stub_settings_safety"))
                row("Passwords, passkeys & accounts", "Saved passwords, autofill, synced accounts", Tap.Go("stub_settings_accounts"))
                row("Digital Wellbeing & parental controls", "Screen time, app timers, bedtime schedules", Tap.Go("stub_settings_wellbeing"))
                row("Google", "Services & preferences", Tap.Go("stub_settings_google"))
                row("System", "Languages, gestures, time, backup", Tap.Go("system"))
                row("About phone", "Pixel 6", Tap.Go("about"))
                row("Tips & support", "Help articles, phone & chat", Tap.Go("stub_settings_tips"))
            }
        },
        SimScreen("settings_search", SETTINGS, null) { st ->
            searchBar("Search Settings", "settings_q")
            val q = st.text["settings_q"].orEmpty().lowercase()
            if (q.isNotBlank()) {
                list {
                    val results = listOf(
                        Triple("Font size", "Display & touch > Display size and text", "display_size"),
                        Triple("Dark theme", "Display & touch", "dark"),
                        Triple("Wi‑Fi", "Network & internet > Internet", "internet"),
                        Triple("Bluetooth", "Connected devices > Connection preferences", "bluetooth"),
                        Triple("Battery Saver", "Battery", "saver"),
                        Triple("Do Not Disturb", "Sound & vibration", "dnd"),
                        Triple("Airplane mode", "Network & internet", "network"),
                        Triple("Ringtone", "Sound & vibration", "ringtone"),
                    ).filter { (t, s, _) ->
                        val words = (t + " " + s).lowercase().replace("‑", "-")
                        q.replace("‑", "-").split(" ").all { it.isBlank() || words.contains(it) || (it == "wifi" && words.contains("wi-fi")) }
                    }
                    results.forEach { (t, s, go) -> row(t, s, Tap.Go(go)) }
                }
            }
        },
        SimScreen("network", SETTINGS, "Network & internet") {
            appBar("Network & internet")
            list {
                row("Internet", "HomeWiFi", Tap.Go("internet"))
                row("Calls & SMS", "Jio", Tap.Go("stub_settings_calls"))
                row("SIMs", "Jio", Tap.Go("stub_settings_sims"))
                switchRow("Airplane mode", "airplane")
                row("Hotspot & tethering", "Off", Tap.Go("hotspot"))
                row("Data Saver", "Off", Tap.Go("stub_settings_datasaver"))
                row("VPN", "None", Tap.Go("stub_settings_vpn"))
                row("Private DNS", "Automatic", Tap.Go("stub_settings_dns"))
                row("Adaptive connectivity", null, Tap.Go("stub_settings_adaptive"))
            }
        },
        SimScreen("internet", SETTINGS, "Internet") { st ->
            appBar("Internet")
            list {
                switchRow("Wi‑Fi", "wifi", default = true)
                if (st.on("wifi", true)) {
                    row("HomeWiFi", "Connected", Tap.Run { null })
                    row("Neighbours_5G", "Saved", Tap.Run { null })
                    row("JioFiber-2.4G", null, Tap.Run { null })
                    row("Add network", null, Tap.Run { null })
                }
                row("Saved networks", "2 networks", Tap.Run { null })
                row("Network preferences", "Wi‑Fi turns back on automatically", Tap.Run { null })
            }
        },
        SimScreen("hotspot", SETTINGS, "Hotspot & tethering") {
            appBar("Hotspot & tethering")
            list {
                row("Wi‑Fi hotspot", "Off", Tap.Run { null })
                switchRow("USB tethering", "usb_tether")
                switchRow("Bluetooth tethering", "bt_tether")
            }
        },
        SimScreen("connected", SETTINGS, "Connected devices") {
            appBar("Connected devices")
            list {
                row("Pair new device", null, Tap.Go("stub_settings_pair"))
                header("Saved devices")
                row("Galaxy Buds", null, Tap.Run { null })
                row("See all", null, Tap.Run { null })
                row("Connection preferences", "Bluetooth, Android Auto, NFC", Tap.Go("connprefs"))
            }
        },
        SimScreen("connprefs", SETTINGS, "Connection preferences") { st ->
            appBar("Connection preferences")
            list {
                row("Bluetooth", if (st.on("bluetooth")) "On" else "Off", Tap.Go("bluetooth"))
                row("NFC", "On", Tap.Run { null })
                row("Cast", null, Tap.Run { null })
                row("Printing", "1 print service on", Tap.Run { null })
                row("Quick Share", "Off", Tap.Run { null })
                row("Android Auto", "Use apps on your car screen", Tap.Run { null })
            }
        },
        SimScreen("bluetooth", SETTINGS, "Bluetooth") { st ->
            appBar("Bluetooth")
            list {
                switchRow("Use Bluetooth", "bluetooth")
                if (st.on("bluetooth")) {
                    row("Pair new device", null, Tap.Go("stub_settings_pair"))
                    row("Device name", "Pixel 6", Tap.Run { null })
                }
            }
        },
        SimScreen("display", SETTINGS, "Display & touch") { st ->
            appBar("Display & touch")
            list {
                header("Brightness")
                row("Brightness level", "60%", Tap.Run { null }, icon = false)
                switchRow("Adaptive brightness", "adaptive_brightness", default = true)
                header("Appearance")
                row("Dark theme", if (st.on("dark")) "On" else "Will never turn on automatically", Tap.Go("dark"))
                row("Display size and text", null, Tap.Go("display_size"))
                switchRow("Lock screen", "lockscreen_info", summary = "What to show on the lock screen", default = true)
                row("Screen timeout", "After 30 seconds of inactivity", Tap.Go("timeout"))
                switchRow("Auto-rotate screen", "autorotate")
                switchRow("Smooth display", "smooth", default = true)
                switchRow("Increase touch sensitivity", "touch_sens")
            }
        },
        SimScreen("dark", SETTINGS, "Dark theme") {
            appBar("Dark theme")
            list {
                switchRow("Use Dark theme", "dark")
                row("Schedule", "None", Tap.Run { null })
            }
        },
        SimScreen("display_size", SETTINGS, "Display size and text") { st ->
            appBar("Display size and text")
            val font = st.text["font_level"]?.toIntOrNull() ?: 2
            text("Preview", h = 500)
            text("Font size", h = 100, heading = true)
            text("Make your text bigger or smaller", h = 90)
            icon("Smaller font size", Bounds(40, 1100, 180, 1240), Tap.Run { it.text["font_level"] = (font - 1).coerceAtLeast(0).toString(); null }, cls = "ImageView")
            nodesSlider("Font size", font, 6, Bounds(200, 1100, width - 200, 1240))
            icon("Larger font size", Bounds(width - 180, 1100, width - 40, 1240), Tap.Run { it.text["font_level"] = (font + 1).coerceAtMost(6).toString(); it.flags += "font_larger"; null }, cls = "ImageView")
        },
        SimScreen("timeout", SETTINGS, "Screen timeout") {
            appBar("Screen timeout")
            list {
                listOf("15 seconds", "30 seconds", "1 minute", "2 minutes", "5 minutes", "10 minutes", "30 minutes").forEach { t ->
                    row(t, null, Tap.Run { s -> s.text["timeout"] = t; null }, icon = false)
                }
            }
        },
        SimScreen("sound", SETTINGS, "Sound & vibration") { st ->
            appBar("Sound & vibration")
            list {
                row("Media volume", "70%", Tap.Run { null }, icon = false)
                row("Call volume", "80%", Tap.Run { null }, icon = false)
                row("Ring & notification volume", "60%", Tap.Run { null }, icon = false)
                row("Do Not Disturb", if (st.flags.contains("dnd")) "On" else "Off", Tap.Go("dnd"))
                row("Phone ringtone", st.text["ringtone"] ?: "Your New Adventure", Tap.Go("ringtone"))
                switchRow("Live Caption", "live_caption")
                row("Vibration & haptics", "On", Tap.Run { null })
            }
        },
        SimScreen("dnd", SETTINGS, "Do Not Disturb") { st ->
            appBar("Do Not Disturb")
            text("Only get notified by important people and apps", h = 120)
            list(reserveBottom = 200) {
                row("People", "Some conversations", Tap.Run { null })
                row("Apps", "None can interrupt", Tap.Run { null })
                row("Schedules", "1 schedule can turn on automatically", Tap.Run { null })
            }
            button(if (st.flags.contains("dnd")) "Turn off now" else "Turn on now", Tap.Run { s -> if (!s.flags.remove("dnd")) s.flags += "dnd"; null })
        },
        SimScreen("ringtone", SETTINGS, "Phone ringtone") {
            appBar("Phone ringtone")
            list {
                listOf("Your New Adventure", "Copycat", "Dance Party", "Flutterby", "Hey Hey", "Lollipop", "Zen Too").forEach { t ->
                    row(t, null, Tap.Run { s -> s.text["ringtone"] = t; null }, icon = false)
                }
            }
        },
        SimScreen("battery", SETTINGS, "Battery") {
            appBar("Battery")
            list {
                row("Battery usage", "View usage for past 24 hours", Tap.Run { null })
                row("Battery Saver", "Off", Tap.Go("saver"))
                row("Adaptive Battery", "On", Tap.Run { null })
                switchRow("Battery percentage", "battery_pct", summary = "Show battery percentage in status bar")
            }
        },
        SimScreen("saver", SETTINGS, "Battery Saver") {
            appBar("Battery Saver")
            list {
                switchRow("Use Battery Saver", "saver")
                row("Set a schedule", "No schedule", Tap.Run { null })
                switchRow("Turn off when charged", "saver_off_charged", default = true)
            }
        },
        SimScreen("accessibility", SETTINGS, "Accessibility") {
            appBar("Accessibility")
            list {
                row("Display size and text", null, Tap.Go("display_size"))
                row("Colour and motion", null, Tap.Run { null })
                row("Extra dim", "Off", Tap.Run { null })
                row("TalkBack", "Off", Tap.Run { null })
                row("Select to Speak", "Off", Tap.Run { null })
                row("Live Transcribe", null, Tap.Run { null })
            }
        },
        SimScreen("apps", SETTINGS, "Apps") {
            appBar("Apps")
            list {
                row("See all 64 apps", null, Tap.Run { null })
                row("Default apps", "Assistant, browser and caller ID", Tap.Run { null })
                row("Screen time", "2 hours today", Tap.Run { null })
                row("Unused apps", "0 unused apps", Tap.Run { null })
            }
        },
        SimScreen("system", SETTINGS, "System") {
            appBar("System")
            list {
                row("Languages", "Gboard, English (India)", Tap.Run { null })
                row("Keyboard", "Gboard", Tap.Run { null })
                row("Gestures", null, Tap.Run { null })
                row("Date & time", "GMT+05:30 India Standard Time", Tap.Run { null })
                row("Backup", "On", Tap.Run { null })
                row("System update", "Updated to Android 15", Tap.Run { null })
                row("Reset options", null, Tap.Run { null })
            }
        },
        SimScreen("about", SETTINGS, "About phone") {
            appBar("About phone")
            list {
                row("Device name", "Pixel 6", Tap.Run { null }, icon = false)
                row("Phone number (sim slot 1)", "+91 98••• •••21", Tap.Run { null }, icon = false)
                row("Emergency information", null, Tap.Run { null }, icon = false)
                row("Legal information", null, Tap.Run { null }, icon = false)
                row("Model", "Pixel 6", Tap.Run { null }, icon = false)
                row("Android version", "15", Tap.Go("android_version"), icon = false)
                row("Build number", "AP4A.250105.002", Tap.Run { null }, icon = false)
            }
        },
        SimScreen("android_version", SETTINGS, "Android version") {
            appBar("Android version")
            list {
                row("Android version", "15", null, icon = false)
                row("Android security update", "5 January 2025", Tap.Run { null }, icon = false)
                row("Google Play system update", "1 December 2024", Tap.Run { null }, icon = false)
            }
        },
    ) + listOf(
        "notifications", "storage", "wallpaper", "security", "location", "safety", "accounts", "wellbeing",
        "google", "tips", "calls", "sims", "datasaver", "vpn", "dns", "adaptive", "pair",
    ).map { n -> SimScreen("stub_settings_$n", SETTINGS, n.replaceFirstChar { it.uppercase() }) { appBar(n.replaceFirstChar { it.uppercase() }); text("Nothing to change here for now.") } }

    // ---- WhatsApp -------------------------------------------------------------------------

    private val contacts = listOf("Amma" to "Call me when you're free", "Rahul" to "See you tomorrow", "Family" to "Priya: Photo", "Dr. Mehta Clinic" to "Your appointment is at 5")

    private fun whatsapp(): List<SimScreen> = buildList {
        add(
            SimScreen("wa_chats", WHATSAPP, "WhatsApp") {
                appBar("WhatsApp", back = false, actions = listOf(IconAction("More options", Tap.Run { null }), IconAction("Camera", Tap.Run { null })))
                searchButton("Ask Meta AI or Search", Tap.Go("wa_search"))
                list(reserveBottom = 200) {
                    contacts.forEach { (name, last) -> item(name, last, Tap.Go("wa_chat_${slug(name)}")) }
                }
                fab("New chat", Tap.Run { null })
                bottomTabs(listOf("Chats" to Tap.Run { null }, "Updates" to Tap.Run { null }, "Communities" to Tap.Run { null }, "Calls" to Tap.Run { null }), selected = 0)
            },
        )
        add(
            SimScreen("wa_search", WHATSAPP, null) { st ->
                searchBar("Search…", "wa_q")
                val q = st.text["wa_q"].orEmpty().lowercase()
                list {
                    contacts.filter { q.isBlank() || it.first.lowercase().contains(q) }.forEach { (name, last) -> item(name, last, Tap.Go("wa_chat_${slug(name)}")) }
                }
            },
        )
        contacts.forEach { (name, _) ->
            val s = slug(name)
            add(
                SimScreen("wa_chat_$s", WHATSAPP, name) { st ->
                    appBar(
                        name,
                        actions = listOf(
                            IconAction("More options", Tap.Run { null }),
                            IconAction("Voice call", Tap.Run { it.flags += "voice_call_$s"; "wa_call" }),
                            IconAction("Video call", Tap.Run { it.flags += "video_call_$s"; "wa_call" }),
                        ),
                    )
                    list(reserveBottom = 200) {
                        st.flags.filter { it.startsWith("submitted:msg_$s=") }.forEach { row(it.substringAfter('='), "Sent", null, icon = false) }
                    }
                    val top = height - 130 - (if (st.keyboard) 900 else 0) - 190
                    icon("Emoji", Bounds(20, top + 20, 140, top + 160), Tap.Run { null })
                    val typed = st.text["msg_$s"]
                    val focused = st.focusedField == "msg_$s"
                    fieldAt("Message", "msg_$s", typed, focused, Bounds(150, top + 10, width - 420, top + 170))
                    icon("Attach", Bounds(width - 410, top + 20, width - 290, top + 160), Tap.Run { null })
                    if (typed.isNullOrBlank()) {
                        icon("Camera", Bounds(width - 280, top + 20, width - 160, top + 160), Tap.Run { null })
                        icon("Voice message", Bounds(width - 150, top + 20, width - 20, top + 160), Tap.Run { null }, cls = "ImageButton")
                    } else {
                        icon("Send", Bounds(width - 150, top + 20, width - 20, top + 160), Tap.Run { st2 ->
                            st2.flags += "submitted:msg_$s=${st2.text["msg_$s"]}"
                            st2.text.remove("msg_$s")
                            null
                        }, cls = "ImageButton")
                    }
                },
            )
        }
        add(
            SimScreen("wa_call", WHATSAPP, "Calling") {
                text("Calling…", h = 200, heading = true)
                icon("End call", Bounds(width / 2 - 120, height - 500, width / 2 + 120, height - 260), Tap.Back, cls = "ImageButton")
            },
        )
    }

    // ---- YouTube (no click events) ----------------------------------------------------------

    private fun youtube(): List<SimScreen> = listOf(
        SimScreen("yt_home", YOUTUBE, null, clickEvents = false) {
            appBar(null, back = false, actions = listOf(IconAction("Search", Tap.Go("yt_search")), IconAction("Notifications", Tap.Run { null }), IconAction("Cast", Tap.Run { null })))
            list(reserveBottom = 200, rowHeight = 700) {
                item("Morning yoga for beginners - 20 min", "Yoga With Ritu · 2M views", Tap.Go("yt_watch"))
                item("Top 10 cricket moments 2026", "Sports Hub · 900K views", Tap.Go("yt_watch"))
                item("Easy dal tadka recipe", "Kitchen Diaries · 4M views", Tap.Go("yt_watch"))
            }
            bottomTabs(listOf("Home" to Tap.Run { null }, "Shorts" to Tap.Run { null }, "Create" to Tap.Run { null }, "Subscriptions" to Tap.Run { null }, "You" to Tap.Run { null }), selected = 0)
        },
        SimScreen("yt_search", YOUTUBE, null, clickEvents = false) { st ->
            val q = st.text["yt_q"].orEmpty()
            appBar(null, back = true)
            searchBar("Search YouTube", "yt_q")
            if (q.isNotBlank()) {
                list {
                    item(q, null, Tap.Submit("yt_q", "yt_results"))
                    item("$q songs", null, Tap.Run { s -> s.text["yt_q"] = "$q songs"; s.flags += "submitted:yt_q=$q songs"; "yt_results" })
                    item("$q live", null, Tap.Run { s -> s.text["yt_q"] = "$q live"; s.flags += "submitted:yt_q=$q live"; "yt_results" })
                }
            }
        },
        SimScreen("yt_results", YOUTUBE, null, clickEvents = false) { st ->
            appBar(null, back = true, actions = listOf(IconAction("Search", Tap.Go("yt_search"))))
            text(st.text["yt_q"].orEmpty(), h = 100)
            list(rowHeight = 700) {
                item("${st.text["yt_q"]} - Best of collection", "Saregama · 12M views", Tap.Go("yt_watch"))
                item("${st.text["yt_q"]} jukebox", "Music India · 5M views", Tap.Go("yt_watch"))
            }
        },
        SimScreen("yt_watch", YOUTUBE, null, clickEvents = false) { st ->
            st.flags += "yt_playing"
            appBar(null, back = true)
            text("Now playing", h = 600)
            button("Subscribe", Tap.Run { null })
        },
    )

    // ---- Phone ------------------------------------------------------------------------------

    private fun dialer(): List<SimScreen> = listOf(
        SimScreen("dialer", DIALER, "Phone") {
            searchButton("Search contacts & places", Tap.Go("dialer_contacts"))
            list(reserveBottom = 400) {
                header("Favourites")
                item("Amma", "Mobile", Tap.Go("dialer_contact_amma"))
                item("Rahul", "Mobile", Tap.Go("dialer_contact_rahul"))
            }
            fab("keypad", Tap.Run { null })
            bottomTabs(listOf("Favourites" to Tap.Run { null }, "Recents" to Tap.Run { null }, "Contacts" to Tap.Go("dialer_contacts")), selected = 0)
        },
        SimScreen("dialer_contacts", DIALER, "Contacts") {
            appBar("Contacts")
            list {
                listOf("Amma", "Dr. Mehta Clinic", "Gas booking", "Rahul", "Sunita").forEach { item(it, null, Tap.Go("dialer_contact_${slug(it)}")) }
            }
        },
    ) + listOf("Amma", "Rahul", "Dr. Mehta Clinic", "Gas booking", "Sunita").map { name ->
        val s = slug(name)
        SimScreen("dialer_contact_$s", DIALER, name) {
            appBar(name)
            text("Mobile +91 98765 43210", h = 120)
            button("Call", Tap.Run { it.flags += "call_$s"; "dialer_incall" })
            button("Text", Tap.Run { null })
            button("Video", Tap.Run { null })
        }
    } + SimScreen("dialer_incall", DIALER, "Calling") {
        text("Calling…", h = 200, heading = true)
        icon("End call", Bounds(width / 2 - 120, height - 500, width / 2 + 120, height - 260), Tap.Back, cls = "ImageButton")
    }

    // ---- Clock ------------------------------------------------------------------------------

    private fun clock(): List<SimScreen> = listOf(
        SimScreen("clock_alarms", CLOCK, "Alarm") {
            appBar("Alarm", back = false, actions = listOf(IconAction("More options", Tap.Run { null })))
            list(reserveBottom = 400) {
                switchRow("6:00 AM", "alarm_6", summary = "Mon, Tue, Wed, Thu, Fri")
                switchRow("7:00 AM", "alarm_7", summary = "Tomorrow")
                switchRow("9:30 PM", "alarm_930", summary = "Medicine")
            }
            fab("Add alarm", Tap.Run { null })
            bottomTabs(listOf("Alarm" to Tap.Run { null }, "Clock" to Tap.Run { null }, "Timer" to Tap.Run { null }, "Stopwatch" to Tap.Run { null }, "Bedtime" to Tap.Run { null }), selected = 0)
        },
    )

    private fun slug(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
}

/** A slider node: SeekBar with a "N of M" state, as Settings reports it. */
private fun ScreenBuilder.nodesSlider(label: String, value: Int, max: Int, b: Bounds) {
    icon("$label slider", b, Tap.Run { null }, cls = "SeekBar")
    // State is carried in the label; good enough for the agent to read the level.
    text("$label: ${value + 1} of ${max + 1}", h = 60)
}

/** A text field at a fixed place (chat composers sit above the keyboard). */
private fun ScreenBuilder.fieldAt(hint: String, field: String, value: String?, focused: Boolean, b: Bounds) {
    fieldNode(hint, field, value, focused, b)
}
