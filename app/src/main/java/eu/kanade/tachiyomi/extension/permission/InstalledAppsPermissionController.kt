package eu.kanade.tachiyomi.extension.permission

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

enum class InstalledAppsPermissionStatus { CHECKING, NOT_REQUIRED, GRANTED, DENIED, UNKNOWN }

data class InstalledAppsPermissionState(
    val status: InstalledAppsPermissionStatus = InstalledAppsPermissionStatus.CHECKING,
    val isRefreshing: Boolean = false,
    val scanFailed: Boolean = false,
)

/** Extra package visibility permission supplied by some Android systems, independent of QUERY_ALL_PACKAGES. */
class AndroidInstalledAppsPermissionDetector(private val context: Context) {
    fun check(): InstalledAppsPermissionStatus = try {
        val pm = context.packageManager
        val permissionExists = try {
            pm.getPermissionInfo(PERMISSION, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (!permissionExists) {
            InstalledAppsPermissionStatus.NOT_REQUIRED
        } else {
            val requested = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
            when {
                requested == null || PERMISSION !in requested -> InstalledAppsPermissionStatus.NOT_REQUIRED
                pm.checkPermission(PERMISSION, context.packageName) == PackageManager.PERMISSION_GRANTED ->
                    InstalledAppsPermissionStatus.GRANTED
                else -> InstalledAppsPermissionStatus.DENIED
            }
        }
    } catch (_: Exception) {
        InstalledAppsPermissionStatus.UNKNOWN
    }

    fun settingsIntent(): Intent? = resolve(
        Intent("android.intent.action.MANAGE_APP_PERMISSIONS")
            .putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName),
    ) ?: applicationSettingsIntent()

    fun applicationSettingsIntent(): Intent? = resolve(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
    )

    private fun resolve(intent: Intent): Intent? = try {
        intent.takeIf { it.resolveActivity(context.packageManager) != null }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val PERMISSION = "com.android.permission.GET_INSTALLED_APPS"
    }
}

/** One controller shared by startup, foreground and the sources screen. It never launches activities. */
class InstalledAppsPermissionController(
    private val detector: suspend () -> InstalledAppsPermissionStatus,
    private val settings: () -> Intent? = { null },
    private val applicationSettings: () -> Intent? = settings,
    private val executionContext: CoroutineContext = EmptyCoroutineContext,
) {
    private val mutableState = MutableStateFlow(InstalledAppsPermissionState())
    val state: StateFlow<InstalledAppsPermissionState> = mutableState.asStateFlow()
    private val refreshLock = Mutex()
    private var scannedStatus: InstalledAppsPermissionStatus? = null
    internal var scan: (suspend (InstalledAppsPermissionStatus) -> Unit)? = null

    internal suspend fun checkStatus(): InstalledAppsPermissionStatus = try {
        detector()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        InstalledAppsPermissionStatus.UNKNOWN
    }

    suspend fun refresh() = withContext(executionContext) {
        refreshLock.withLock {
            while (true) {
                val status = checkStatus()
                val needsScan = scan != null && (scannedStatus != status || state.value.scanFailed)
                mutableState.value = InstalledAppsPermissionState(status, isRefreshing = needsScan)
                if (!needsScan) return@withLock
                val scanFailed = try {
                    scan?.invoke(status)
                    scannedStatus = status
                    false
                } catch (e: CancellationException) {
                    mutableState.value = InstalledAppsPermissionState(status)
                    throw e
                } catch (_: Exception) {
                    true
                }
                val latest = checkStatus()
                mutableState.value = InstalledAppsPermissionState(latest, scanFailed = scanFailed)
                // A permission change during discovery requires a fresh scan, never the old grant result.
                if (latest == status) return@withLock
            }
        }
    }

    fun settingsIntent(): Intent? = settings()
    fun applicationSettingsIntent(): Intent? = applicationSettings()

    companion object {
        fun android(context: Context): InstalledAppsPermissionController {
            val adapter = AndroidInstalledAppsPermissionDetector(context)
            return InstalledAppsPermissionController(
                detector = { adapter.check() },
                settings = adapter::settingsIntent,
                applicationSettings = adapter::applicationSettingsIntent,
                executionContext = Dispatchers.IO,
            )
        }
    }
}
