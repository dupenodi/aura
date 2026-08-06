package com.drishti

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.drishti.data.AuraPrefs
import com.drishti.data.RoutineStore
import com.drishti.data.TaskHistory
import com.drishti.voice.RemoteSpeech
import com.drishti.voice.SpeechOutput
import com.drishti.overlay.BubbleService
import com.drishti.ui.home.HomeScreen
import com.drishti.ui.onboarding.OnboardingFlow
import com.drishti.ui.onboarding.PermissionState
import com.drishti.ui.settings.PresenceScreen
import com.drishti.ui.settings.PrivacyScreen
import com.drishti.ui.routines.RoutinesScreen
import com.drishti.ui.settings.LanguageScreen
import com.drishti.ui.settings.SettingsScreen
import com.drishti.ui.theme.AuraTheme

/** Where the user currently is inside the app shell. */
private enum class Route { Home, Settings, Presence, Privacy, Language, Routines }

/** Extra carrying a task to run straight away (launcher shortcut / assistant handoff). */
private const val EXTRA_TASK = "task"

/** Set by the overlay when the user tried to talk without granting the microphone. */
private const val EXTRA_REQUEST_MIC = "request_mic"

class MainActivity : ComponentActivity() {

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* re-read on resume */ }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* optional — orb still works without the Pause action */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = AuraPrefs.get(this)
        handleTaskHandoff(intent)
        if (intent?.getBooleanExtra(EXTRA_REQUEST_MIC, false) == true) {
            intent.removeExtra(EXTRA_REQUEST_MIC)
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        val history = TaskHistory.get(this)
        val routineStore = RoutineStore.get(this)
        // Used only to ask which languages have an installed voice.
        val tts = SpeechOutput(this)

        setContent {
            AuraTheme {
                // Permissions are granted in Android's own screens, so re-read every
                // time we come back to the foreground.
                var permissionEpoch by remember { mutableIntStateOf(0) }
                OnResume { permissionEpoch++ }

                val permissions = remember(permissionEpoch) { readPermissions() }
                val orbSkin by prefs.orbSkin.collectAsState()
                val glow by prefs.glow.collectAsState()
                val speakAloud by prefs.speakAloud.collectAsState()
                val paused by prefs.paused.collectAsState()
                val records by history.records.collectAsState()
                val routines by routineStore.routines.collectAsState()
                val language by prefs.language.collectAsState()
                val speechProvider by prefs.speechProvider.collectAsState()

                var onboarded by remember { mutableStateOf(prefs.onboardingComplete) }
                var route by remember { mutableStateOf(Route.Home) }

                // Once set up and permitted, the orb should simply be there.
                LaunchedEffect(onboarded, permissions, paused) {
                    if (onboarded && permissions.accessibility && permissions.overlay && !paused) {
                        ensureNotificationPermission()
                        BubbleService.start(this@MainActivity)
                    } else if (paused) {
                        BubbleService.stop(this@MainActivity)
                    }
                }

                if (!onboarded) {
                    OnboardingFlow(
                        permissions = permissions,
                        orbSkin = orbSkin,
                        glow = glow,
                        onOrbSkin = prefs::setOrbSkin,
                        onGlow = prefs::setGlow,
                        onRequestAccessibility = { openAccessibilitySettings() },
                        onRequestOverlay = { openOverlaySettings() },
                        onFinish = { firstTask ->
                            prefs.onboardingComplete = true
                            onboarded = true
                            // Re-read at call time — the Compose snapshot may still be
                            // the pre-Settings frame when PermissionGuide completes.
                            val now = readPermissions()
                            if (now.accessibility && now.overlay) {
                                ensureNotificationPermission()
                                BubbleService.start(this@MainActivity)
                                if (firstTask != null) {
                                    BubbleService.runTask(this@MainActivity, firstTask)
                                    // Onboarding ends outside the app, watching the orb work.
                                    moveTaskToBack(true)
                                }
                            }
                        },
                    )
                    return@AuraTheme
                }

                BackHandler(enabled = route != Route.Home) {
                    route = when (route) {
                        Route.Settings, Route.Routines -> Route.Home
                        else -> Route.Settings
                    }
                }

                // On Home, Back ends a live session then backgrounds the app — same
                // out-of-band escape as notification Pause, without flipping paused.
                BackHandler(enabled = route == Route.Home) {
                    BubbleService.cancelRun(this@MainActivity)
                    moveTaskToBack(true)
                }

                when (route) {
                    Route.Home -> HomeScreen(
                        records = records,
                        orbSkin = orbSkin,
                        glow = glow,
                        active = !paused && permissions.accessibility && permissions.overlay,
                        onOpenSettings = { route = Route.Settings },
                        onAsk = {
                            if (paused) return@HomeScreen
                            ensureNotificationPermission()
                            BubbleService.start(this@MainActivity)
                            // openComposer cancels any in-flight run first.
                            BubbleService.openComposer(this@MainActivity)
                            moveTaskToBack(true)
                        },
                    )

                    Route.Settings -> SettingsScreen(
                        orbSkin = orbSkin,
                        glow = glow,
                        speakAloud = speakAloud,
                        permissionsGranted = permissions.accessibility && permissions.overlay,
                        onBack = { route = Route.Home },
                        onOpenPresence = { route = Route.Presence },
                        onOpenPrivacy = { route = Route.Privacy },
                        onOpenLanguage = { route = Route.Language },
                        onOpenRoutines = { route = Route.Routines },
                        language = language,
                        onSpeakAloud = prefs::setSpeakAloud,
                        onOpenPermissions = { openAccessibilitySettings() },
                    )

                    Route.Presence -> PresenceScreen(
                        orbSkin = orbSkin,
                        glow = glow,
                        onOrbSkin = prefs::setOrbSkin,
                        onGlow = prefs::setGlow,
                        onBack = { route = Route.Settings },
                    )

                    Route.Language -> LanguageScreen(
                        language = language,
                        provider = speechProvider,
                        providerConfigured = { RemoteSpeech.isConfigured(it) },
                        ttsAvailable = { tts.supports(it) },
                        onLanguage = prefs::setLanguage,
                        onProvider = prefs::setSpeechProvider,
                        onBack = { route = Route.Settings },
                    )

                    Route.Routines -> RoutinesScreen(
                        routines = routines,
                        onRun = { routine ->
                            if (paused) return@RoutinesScreen
                            routineStore.recordRun(routine.id)
                            ensureNotificationPermission()
                            BubbleService.start(this@MainActivity)
                            BubbleService.runTask(this@MainActivity, routine.task)
                            moveTaskToBack(true)
                        },
                        onDelete = { routineStore.delete(it.id) },
                        onAdd = { name, task -> routineStore.add(name, task) },
                        onBack = { route = Route.Home },
                    )

                    Route.Privacy -> PrivacyScreen(
                        paused = paused,
                        onPaused = prefs::setPaused,
                        onDeleteHistory = { history.clear() },
                        onBack = { route = Route.Settings },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A running instance receives deep links here, not through onCreate.
        setIntent(intent)
        handleTaskHandoff(intent)
    }

    /**
     * Runs a task handed over by a launcher shortcut or assistant, then steps aside so
     * the user watches the orb work rather than staring at our app.
     */
    private fun handleTaskHandoff(intent: Intent?) {
        val prefs = AuraPrefs.get(this)
        val task = intent?.getStringExtra(EXTRA_TASK)?.trim().orEmpty()
        if (task.isEmpty() || !prefs.onboardingComplete || prefs.paused.value) return
        intent?.removeExtra(EXTRA_TASK)
        ensureNotificationPermission()
        BubbleService.start(this)
        BubbleService.runTask(this, task)
        moveTaskToBack(true)
    }

    /** So the Pause Aura notification action is visible on Android 13+. */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun readPermissions() = PermissionState(
        accessibility = isAccessibilityEnabled(),
        overlay = Settings.canDrawOverlays(this),
        microphone = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED,
    )

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val expected = "$packageName/${packageName}.accessibility.ScreenAgentAccessibilityService"
        val shortName = "$packageName/.accessibility.ScreenAgentAccessibilityService"
        return enabled.split(':').any { it.equals(expected, true) || it.equals(shortName, true) }
    }
}

/** Runs [block] each time the activity returns to the foreground. */
@Composable
private fun OnResume(block: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) block()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
