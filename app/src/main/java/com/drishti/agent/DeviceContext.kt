package com.drishti.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.drishti.accessibility.ScreenAgentAccessibilityService
import com.drishti.core.agent.AppInfo
import com.drishti.core.agent.Device
import com.drishti.core.agent.DeviceInfo
import com.drishti.core.agent.Screenshot
import com.drishti.core.screen.Bounds
import com.drishti.core.screen.Screen
import com.drishti.core.screen.UiEvent
import kotlinx.coroutines.flow.Flow

/**
 * What the agent is allowed to know about the phone itself.
 *
 * Without the installed-app list the model guesses package names from memory and then
 * reports apps as missing when they are installed under a different id.
 */
object DeviceContext {

    private const val CACHE_MS = 60_000L

    @Volatile
    private var cached: List<AppInfo> = emptyList()

    @Volatile
    private var cachedAt = 0L

    /** Launchable apps, alphabetically, cached briefly since this is a slow PM call. */
    fun installedApps(context: Context): List<AppInfo> {
        val now = SystemClock.elapsedRealtime()
        val snapshot = cached
        if (snapshot.isNotEmpty() && now - cachedAt < CACHE_MS) return snapshot

        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcher, 0)
                .asSequence()
                .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .filter { it.label.isNotBlank() && it.pkg != context.packageName }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
                .toList()
        }.getOrDefault(emptyList())

        cached = apps
        cachedAt = now
        return apps
    }

    fun launcherPackage(context: Context): String? = runCatching {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }.getOrNull()

    fun deviceInfo(context: Context) = DeviceInfo(
        model = phoneName(),
        androidVersion = Build.VERSION.RELEASE.orEmpty(),
        ownPackage = context.packageName,
        launcherPackage = launcherPackage(context),
    )

    /** "Google Pixel 6" rather than "google oriole". */
    private fun phoneName(): String {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
    }
}

/** The real phone, as the guide agent sees it. */
class AndroidDevice(private val context: Context) : Device {
    private val service get() = ScreenAgentAccessibilityService.getInstance()

    override val events: Flow<UiEvent> = ScreenAgentAccessibilityService.events

    override val info: DeviceInfo = DeviceContext.deviceInfo(context)

    override suspend fun snapshot(): Screen? = service?.snapshot()

    override suspend fun screenshot(): Screenshot? = service?.screenshot()

    override suspend fun liveBounds(nodeKey: Int): Bounds? = service?.liveBounds(nodeKey)

    override fun installedApps(): List<AppInfo> = DeviceContext.installedApps(context)

    override fun canSeeScreen(): Boolean = service != null
}
