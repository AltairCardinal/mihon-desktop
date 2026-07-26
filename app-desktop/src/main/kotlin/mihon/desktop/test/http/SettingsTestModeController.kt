package mihon.desktop.test.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mihon.desktop.network.DesktopCloudflareCookieImportResult
import mihon.desktop.network.DesktopNetworkMaintenancePort
import mihon.desktop.test.navigation.TestNavigationController
import mihon.desktop.ui.settings.AdvancedSettingsPlatformActions
import mihon.desktop.ui.settings.DesktopSettingsAnchorOwner
import mihon.desktop.ui.settings.DesktopSettingsCatalog
import mihon.desktop.ui.settings.ProductionAdvancedSettingsPlatformActions
import mihon.desktop.ui.settings.SecuritySettingsController
import mihon.domain.security.AuthenticationResult
import mihon.domain.settings.SettingsSearchResult
import cafe.adriel.voyager.core.screen.Screen
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class SettingsTestRow(
    val title: String,
    val breadcrumb: String,
)

@Serializable
data class SettingsTestSnapshot(
    val query: String = "",
    val rows: List<SettingsTestRow> = emptyList(),
    val securityEnabled: Boolean = false,
    val securityDelayMinutes: Int = 0,
    val securityBackend: String = "Available",
    val securityFeedback: String? = null,
    val phase: String = "IDLE",
    val confirmationRequired: String? = null,
    val maintenanceResult: String? = null,
)

@Serializable
enum class SettingsTestFailureCode {
    MISSING_PARAMETER,
    INVALID_PARAMETER,
    ROW_NOT_FOUND,
    CONFIRMATION_REQUIRED,
    AUTHENTICATION_FAILED,
    BACKEND_UNAVAILABLE,
    OPERATION_IN_PROGRESS,
    OPERATION_REJECTED,
    NAVIGATION_REJECTED,
    PORT_FAILURE,
    OWNER_CLOSED,
    UNSUPPORTED_ACTION,
}

@Serializable
data class SettingsTestActionResult(
    val success: Boolean,
    val snapshot: SettingsTestSnapshot,
    val failureCode: SettingsTestFailureCode? = null,
)

internal interface SettingsSearchOwner {
    fun search(query: String): List<SettingsTestRow>
    fun select(index: Int): SettingsSearchSelection
}

internal enum class SettingsSearchSelection { SELECTED, ROW_NOT_FOUND, NAVIGATION_REJECTED }

internal class ProductionSettingsSearchOwner : SettingsSearchOwner {
    private var results: List<SettingsSearchResult<Screen>> = emptyList()

    override fun search(query: String): List<SettingsTestRow> {
        results = DesktopSettingsCatalog.search(query)
        return results.map { SettingsTestRow(it.title, it.breadcrumb) }
    }

    override fun select(index: Int): SettingsSearchSelection {
        val result = results.getOrNull(index) ?: return SettingsSearchSelection.ROW_NOT_FOUND
        DesktopSettingsAnchorOwner.publish(result.route, result.anchorTitle)
        return if (TestNavigationController.navigateToScreen(result.route)) {
            SettingsSearchSelection.SELECTED
        } else {
            SettingsSearchSelection.NAVIGATION_REJECTED
        }
    }
}

class SettingsTestModeController internal constructor(
    private val searchOwner: SettingsSearchOwner,
    private val security: SecuritySettingsController?,
    private val networkMaintenance: DesktopNetworkMaintenancePort?,
    private val platformActions: AdvancedSettingsPlatformActions?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val beforeMaintenanceStart: suspend () -> Unit = {},
) {
    constructor(search: (String) -> List<SettingsTestRow>) : this(
        searchOwner = object : SettingsSearchOwner {
            override fun search(query: String) = search(query)
            override fun select(index: Int) = SettingsSearchSelection.ROW_NOT_FOUND
        },
        security = null,
        networkMaintenance = null,
        platformActions = null,
    )

    internal constructor(
        security: SecuritySettingsController,
        networkMaintenance: DesktopNetworkMaintenancePort,
        platformActions: AdvancedSettingsPlatformActions = ProductionAdvancedSettingsPlatformActions,
    ) : this(ProductionSettingsSearchOwner(), security, networkMaintenance, platformActions)

    private val closed = AtomicBoolean(false)
    private val mutex = Mutex()
    private val activeMaintenance = AtomicReference<Deferred<Boolean>?>()
    private val mutableSnapshot = AtomicReference(SettingsTestSnapshot())

    fun snapshot(): SettingsTestSnapshot {
        val current = mutableSnapshot.get()
        val state = security?.state?.value ?: return current
        return current.copy(
            securityEnabled = state.enabled,
            securityDelayMinutes = state.delayMinutes,
            securityBackend = state.backendCapability.name,
            securityFeedback = state.feedback?.name,
        )
    }

    suspend fun execute(
        action: String,
        params: Map<String, String>,
    ): SettingsTestActionResult {
        if (closed.get()) return failure(SettingsTestFailureCode.OWNER_CLOSED)
        if (action == "setting_cancel") {
            activeMaintenance.getAndSet(null)?.cancelAndJoin()
            update { it.copy(phase = "CANCELLED", confirmationRequired = null) }
            return SettingsTestActionResult(true, snapshot())
        }
        if (!mutex.tryLock()) return failure(SettingsTestFailureCode.OPERATION_IN_PROGRESS)
        return try {
            when (action) {
                "setting_search" -> search(params)
                "setting_search_select" -> select(params)
                "setting_security_enable" -> securityEnable(params)
                "setting_security_disable" -> securityDisable(params)
                "setting_security_delay" -> securityDelay(params)
                "setting_security_change_passphrase" -> securityChangePassphrase(params)
                "setting_import_cloudflare_cookie" -> importCookie(params)
                "setting_clear_cookies" -> clearCookies(params)
                "setting_clear_network_cache" -> clearNetworkCache(params)
                "setting_open_crash_logs" -> openCrashLogs()
                else -> failure(SettingsTestFailureCode.UNSUPPORTED_ACTION)
            }
        } catch (_: Exception) {
            update { it.copy(phase = "FAILED") }
            failure(SettingsTestFailureCode.PORT_FAILURE)
        } finally {
            mutex.unlock()
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeMaintenance.getAndSet(null)?.cancel()
        scope.cancel()
        SettingsTestModeBridge.clear(this)
    }

    private fun search(params: Map<String, String>): SettingsTestActionResult {
        val query = params["query"] ?: return failure(SettingsTestFailureCode.MISSING_PARAMETER)
        val rows = searchOwner.search(query)
        update { it.copy(query = query, rows = rows, phase = "COMPLETED", maintenanceResult = null) }
        return SettingsTestActionResult(true, snapshot())
    }

    private fun select(params: Map<String, String>): SettingsTestActionResult {
        val index = params["index"]?.toIntOrNull() ?: return failure(SettingsTestFailureCode.MISSING_PARAMETER)
        return when (searchOwner.select(index)) {
            SettingsSearchSelection.SELECTED -> SettingsTestActionResult(true, snapshot())
            SettingsSearchSelection.ROW_NOT_FOUND -> failure(SettingsTestFailureCode.ROW_NOT_FOUND)
            SettingsSearchSelection.NAVIGATION_REJECTED -> failure(SettingsTestFailureCode.NAVIGATION_REJECTED)
        }
    }

    private fun securityEnable(params: Map<String, String>): SettingsTestActionResult {
        val passphrase = params["passphrase"] ?: return failure(SettingsTestFailureCode.MISSING_PARAMETER)
        val confirmation = params["confirmation"] ?: return failure(SettingsTestFailureCode.MISSING_PARAMETER)
        return authentication {
        security?.enable(passphrase.toCharArray(), confirmation.toCharArray())
            ?: AuthenticationResult.Unavailable
        }
    }

    private fun securityDisable(params: Map<String, String>): SettingsTestActionResult {
        val current = params["currentPassphrase"] ?: return failure(SettingsTestFailureCode.MISSING_PARAMETER)
        return authentication {
            security?.disable(current.toCharArray()) ?: AuthenticationResult.Unavailable
        }
    }

    private fun securityDelay(params: Map<String, String>): SettingsTestActionResult {
        val rawMinutes = params["minutes"] ?: return failure(SettingsTestFailureCode.MISSING_PARAMETER)
        val minutes = rawMinutes.toIntOrNull() ?: return failure(SettingsTestFailureCode.INVALID_PARAMETER)
        val current = params["currentPassphrase"] ?: return failure(SettingsTestFailureCode.MISSING_PARAMETER)
        return authentication {
            security?.changeDelay(minutes, current.toCharArray()) ?: AuthenticationResult.Unavailable
        }
    }

    private fun securityChangePassphrase(params: Map<String, String>): SettingsTestActionResult {
        val current = params["currentPassphrase"] ?: return failure(SettingsTestFailureCode.MISSING_PARAMETER)
        val replacement = params["replacement"] ?: return failure(SettingsTestFailureCode.MISSING_PARAMETER)
        val confirmation = params["confirmation"] ?: return failure(SettingsTestFailureCode.MISSING_PARAMETER)
        return authentication {
            security?.changePassphrase(
                current.toCharArray(),
                replacement.toCharArray(),
                confirmation.toCharArray(),
            ) ?: AuthenticationResult.Unavailable
        }
    }

    private fun authentication(block: () -> AuthenticationResult): SettingsTestActionResult {
        val code = when (block()) {
            AuthenticationResult.Success -> null
            AuthenticationResult.Failed -> SettingsTestFailureCode.AUTHENTICATION_FAILED
            AuthenticationResult.Cancelled -> SettingsTestFailureCode.OPERATION_REJECTED
            AuthenticationResult.Unavailable -> SettingsTestFailureCode.BACKEND_UNAVAILABLE
            AuthenticationResult.Error -> SettingsTestFailureCode.OPERATION_REJECTED
        }
        update { it.copy(phase = if (code == null) "COMPLETED" else "FAILED", confirmationRequired = null) }
        return code?.let(::failure) ?: SettingsTestActionResult(true, snapshot())
    }

    private fun importCookie(params: Map<String, String>): SettingsTestActionResult {
        val domain = params["domain"] ?: return failure(SettingsTestFailureCode.MISSING_PARAMETER)
        val value = params["value"] ?: return failure(SettingsTestFailureCode.MISSING_PARAMETER)
        val result = networkMaintenance?.importCloudflareCookie(domain, value)
            ?: return failure(SettingsTestFailureCode.OPERATION_REJECTED)
        return when (result) {
            is DesktopCloudflareCookieImportResult.Imported -> {
                update { it.copy(phase = "COMPLETED", maintenanceResult = result.host) }
                SettingsTestActionResult(true, snapshot())
            }
            DesktopCloudflareCookieImportResult.InvalidDomain,
            DesktopCloudflareCookieImportResult.InvalidValue,
            DesktopCloudflareCookieImportResult.DomainParseFailed,
            -> failure(SettingsTestFailureCode.INVALID_PARAMETER)
        }
    }

    private fun clearCookies(params: Map<String, String>): SettingsTestActionResult {
        if (params["confirm"]?.toBooleanStrictOrNull() != true) return confirmation("clear_cookies")
        val port = networkMaintenance ?: return failure(SettingsTestFailureCode.OPERATION_REJECTED)
        port.clearCookies()
        update { it.copy(phase = "COMPLETED", confirmationRequired = null, maintenanceResult = "cookies_cleared") }
        return SettingsTestActionResult(true, snapshot())
    }

    private suspend fun clearNetworkCache(params: Map<String, String>): SettingsTestActionResult {
        if (params["confirm"]?.toBooleanStrictOrNull() != true) return confirmation("clear_network_cache")
        return runMaintenance("network_cache_cleared") { platformActions?.clearNetworkCache() == true }
    }

    private suspend fun openCrashLogs() =
        runMaintenance("crash_logs_opened") { platformActions?.openCrashLogFolder() == true }

    private suspend fun runMaintenance(
        result: String,
        block: suspend () -> Boolean,
    ): SettingsTestActionResult {
        update { it.copy(phase = "RUNNING", confirmationRequired = null, maintenanceResult = null) }
        val deferred = scope.async(start = CoroutineStart.LAZY) { block() }
        if (!activeMaintenance.compareAndSet(null, deferred)) {
            deferred.cancelAndJoin()
            return failure(SettingsTestFailureCode.OPERATION_IN_PROGRESS)
        }
        val succeeded = try {
            beforeMaintenanceStart()
            deferred.start()
            deferred.await()
        } catch (_: CancellationException) {
            withContext(NonCancellable) {
                deferred.cancelAndJoin()
            }
            update { it.copy(phase = "CANCELLED") }
            return failure(SettingsTestFailureCode.OPERATION_REJECTED)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                deferred.cancelAndJoin()
            }
            throw error
        } finally {
            activeMaintenance.compareAndSet(deferred, null)
        }
        if (!succeeded) {
            update { it.copy(phase = "FAILED") }
            return failure(SettingsTestFailureCode.OPERATION_REJECTED)
        }
        update { it.copy(phase = "COMPLETED", maintenanceResult = result) }
        return SettingsTestActionResult(true, snapshot())
    }

    private fun confirmation(action: String): SettingsTestActionResult {
        update { it.copy(phase = "AWAITING_CONFIRMATION", confirmationRequired = action) }
        return failure(SettingsTestFailureCode.CONFIRMATION_REQUIRED)
    }

    private fun update(block: (SettingsTestSnapshot) -> SettingsTestSnapshot) {
        mutableSnapshot.updateAndGet(block)
    }

    private fun failure(code: SettingsTestFailureCode) = SettingsTestActionResult(false, snapshot(), code)
}

object SettingsTestModeBridge {
    private val value = AtomicReference<SettingsTestModeController?>()
    val controller: SettingsTestModeController? get() = value.get()
    fun install(controller: SettingsTestModeController) { value.set(controller) }
    fun clear(expected: SettingsTestModeController): Boolean = value.compareAndSet(expected, null)
}
