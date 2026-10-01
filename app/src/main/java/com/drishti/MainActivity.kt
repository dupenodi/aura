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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.drishti.ai.ApiKeyStore
import com.drishti.data.AuraPrefs
import com.drishti.data.SessionRecorder
import com.drishti.data.TaskHistory
import com.drishti.overlay.BubbleService
import com.drishti.ui.home.HomeScreen
import com.drishti.ui.home.HomeStatus
import com.drishti.ui.onboarding.OnboardingFlow
import com.drishti.ui.onboarding.PermissionActions
import com.drishti.ui.onboarding.PermissionState
import com.drishti.ui.settings.LanguageScreen
import com.drishti.ui.settings.PermissionsScreen
import com.drishti.ui.settings.SettingsActions
import com.drishti.ui.settings.SettingsScreen
import com.drishti.ui.settings.SettingsState
import com.drishti.ui.theme.AuraTheme
import com.drishti.voice.SpeechProvider

private enum class Route { Home, Settings, Language, Permissions }

/** Extra carrying a task to run straight away (launcher shortcut / assistant handoff). */
private const val EXTRA_TASK = "task"

/** Set by the overlay when the user tried to talk without granting the microphone. */
private const val EXTRA_REQUEST_MIC = "request_mic"

class MainActivity : ComponentActivity() {

    /** Bumped whenever a permission may have changed, so the UI re-reads them. */
    private var permissionEpoch by mutableIntStateOf(0)

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionEpoch++ }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* optional — the glow works without the pause action */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = AuraPrefs.get(this)
        handleTaskHandoff(intent)
        handleMicRequest(intent)
        val history = TaskHistory.get(this)

        val permissionActions = PermissionActions(
            openAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            openAppInfo = {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            },
            openOverlay = {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            },
            requestMicrophone = { requestMicrophone() },
        )

        setContent {
            AuraTheme {
                // Permissions are granted in Android's own screens; re-read on every return.
                OnResume { permissionEpoch++ }
                val permissions = remember(permissionEpoch) { readPermissions() }
                val speakAloud by prefs.speakAloud.collectAsState()
                val useScreenshots by prefs.useScreenshots.collectAsState()
                val paused by prefs.paused.collectAsState()
                val records by history.records.collectAsState()
                val language by prefs.language.collectAsState()
                val autoLanguage by prefs.autoLanguage.collectAsState()
                val speechProvider by prefs.speechProvider.collectAsState()

                var onboarded by remember { mutableStateOf(prefs.onboardingComplete) }
                var route by remember { mutableStateOf(Route.Home) }

                // Once set up and permitted, the glow should simply be there.
                LaunchedEffect(onboarded, permissions, paused) {
                    if (onboarded && permissions.essentials && !paused) {
                        ensureNotificationPermission()
                        BubbleService.start(this@MainActivity)
                    } else if (paused) {
                        BubbleService.stop(this@MainActivity)
                    }
                }

                if (!onboarded) {
                    OnboardingFlow(
                        permissions = permissions,
                        actions = permissionActions,
                        onFinish = { firstTask ->
                            prefs.onboardingComplete = true
                            onboarded = true
                            val now = readPermissions()
                            if (now.essentials) {
                                ensureNotificationPermission()
                                BubbleService.start(this@MainActivity)
                                if (firstTask != null) {
                                    BubbleService.runTask(this@MainActivity, firstTask)
                                    // The first task happens out in the phone, not in our app.
                                    moveTaskToBack(true)
                                }
                            }
                        },
                    )
                    return@AuraTheme
                }

                BackHandler(enabled = route != Route.Home) {
                    route = if (route == Route.Settings) Route.Home else Route.Settings
                }
                // On home, back ends a live session and steps aside, without pausing aura.
                BackHandler(enabled = route == Route.Home) {
                    BubbleService.cancelRun(this@MainActivity)
                    moveTaskToBack(true)
                }

                when (route) {
                    Route.Home -> HomeScreen(
                        records = records,
                        status = when {
                            paused -> HomeStatus.Paused
                            !permissions.essentials -> HomeStatus.NeedsPermission
                            else -> HomeStatus.Ready
                        },
                        onAsk = {
                            ensureNotificationPermission()
                            BubbleService.start(this@MainActivity)
                            BubbleService.openComposer(this@MainActivity)
                            moveTaskToBack(true)
                        },
                        onFix = {
                            if (paused) prefs.setPaused(false) else route = Route.Permissions
                        },
                        onOpenSettings = { route = Route.Settings },
                    )

                    Route.Settings -> SettingsScreen(
                        state = SettingsState(
                            autoLanguage = autoLanguage,
                            language = language,
                            speakAloud = speakAloud,
                            naturalVoice = speechProvider == SpeechProvider.Sarvam,
                            naturalVoiceAvailable = ApiKeyStore.resolve("sarvam").isNotBlank(),
                            useScreenshots = useScreenshots,
                            paused = paused,
                            permissions = permissions,
                            isAssistant = remember(permissionEpoch) { isDefaultAssistant() },
                        ),
                        actions = SettingsActions(
                            onBack = { route = Route.Home },
                            onOpenLanguage = { route = Route.Language },
                            onSpeakAloud = prefs::setSpeakAloud,
                            onNaturalVoice = { on ->
                                prefs.setSpeechProvider(if (on) SpeechProvider.Sarvam else SpeechProvider.OnDevice)
                            },
                            onUseScreenshots = prefs::setUseScreenshots,
                            onPaused = prefs::setPaused,
                            onOpenPermissions = { route = Route.Permissions },
                            onDeleteHistory = {
                                history.clear()
                                SessionRecorder.clear(this@MainActivity)
                            },
                            onOpenAssistant = { openAssistantSettings() },
                        ),
                    )

                    Route.Language -> LanguageScreen(
                        auto = autoLanguage,
                        language = language,
                        onAuto = { prefs.setAutoLanguage(true) },
                        onLanguage = { lang ->
                            prefs.setAutoLanguage(false)
                            prefs.setLanguage(lang)
                        },
                        onBack = { route = Route.Settings },
                    )

                    Route.Permissions -> PermissionsScreen(
                        permissions = permissions,
                        actions = permissionActions,
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
        handleMicRequest(intent)
    }

    private fun handleMicRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REQUEST_MIC, false) != true) return
        intent.removeExtra(EXTRA_REQUEST_MIC)
        requestMicrophone()
    }

    /**
     * Asks for the microphone. If Android won't show the dialog any more (denied twice),
     * the only place left to grant it is the app's settings page, so go there.
     */
    private fun requestMicrophone() {
        val denied = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        val askedBefore = getSharedPreferences("aura_prefs", MODE_PRIVATE).getBoolean("mic_asked", false)
        if (denied && askedBefore && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            return
        }
        getSharedPreferences("aura_prefs", MODE_PRIVATE).edit().putBoolean("mic_asked", true).apply()
        micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    /**
     * Runs a task handed over by a launcher shortcut or assistant, then steps aside so the
     * user watches aura work rather than staring at our app.
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

    /** So the pause action in the notification is visible on Android 13+. */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Whether aura is the phone's digital assistant (hold power / corner swipe). */
    private fun isDefaultAssistant(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = getSystemService(android.app.role.RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT)) {
                return roles.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
            }
        }
        val assistant = Settings.Secure.getString(contentResolver, "assistant").orEmpty()
        return assistant.startsWith("$packageName/")
    }

    /**
     * The assistant can't be requested with a dialog; it is chosen in Android's default-apps
     * screen. Try the assistant page itself, then the default apps list.
     */
    private fun openAssistantSettings() {
        val attempts = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in attempts) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
    }

    private fun readPermissions() = PermissionState(
        accessibility = isAccessibilityEnabled(),
        overlay = Settings.canDrawOverlays(this),
        microphone = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED,
    )

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
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) block()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
