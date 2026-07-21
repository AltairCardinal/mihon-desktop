package mihon.desktop.ui.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mihon.desktop.APP_VERSION
import mihon.desktop.update.DesktopUpdateController
import mihon.desktop.update.DesktopUpdateState
import tachiyomi.domain.release.interactor.GetApplicationRelease
import java.awt.Desktop
import java.net.URI
import kotlin.coroutines.CoroutineContext

enum class DesktopUpdateIntent { CHECK, DOWNLOAD, CANCEL, RETRY, CONFIRM, DECLINE, MANUAL }
data class DesktopUpdatePresentation(
    val status: String,
    val message: String,
    val actions: Set<DesktopUpdateIntent>,
    val progress: Int? = null,
    val releasePage: String? = null,
)
fun DesktopUpdateState.presentation(): DesktopUpdatePresentation = when (this) {
    DesktopUpdateState.Idle -> presentation("idle", "Check whether a newer Mihon Desktop release is available", DesktopUpdateIntent.CHECK)
    DesktopUpdateState.Checking -> presentation("checking", "Checking for updates…", DesktopUpdateIntent.CANCEL)
    DesktopUpdateState.UpToDate -> presentation("up_to_date", "Mihon Desktop is up to date", DesktopUpdateIntent.CHECK)
    is DesktopUpdateState.UpdateAvailable -> presentation("update_available", "${release.version} is available", DesktopUpdateIntent.DOWNLOAD, DesktopUpdateIntent.MANUAL, page = release.releaseLink)
    DesktopUpdateState.NoCompatiblePackage -> presentation("no_compatible_package", "No compatible package is available", DesktopUpdateIntent.CHECK)
    is DesktopUpdateState.CheckFailed -> presentation("check_failed", "Update check failed: ${reason.name.lowercase()}", if (retryable) DesktopUpdateIntent.RETRY else DesktopUpdateIntent.CHECK)
    is DesktopUpdateState.Downloading -> presentation(
        "downloading", "Downloading ${release.version}…", DesktopUpdateIntent.CANCEL,
        progress = progress?.let { value -> value.totalBytes?.takeIf { it > 0 }?.let { (value.downloadedBytes * 100 / it).toInt().coerceIn(0, 100) } },
        page = release.releaseLink,
    )
    is DesktopUpdateState.Verifying -> presentation("verifying", "Verifying ${release.version}…", DesktopUpdateIntent.CANCEL, page = release.releaseLink)
    is DesktopUpdateState.ReadyToInstall -> presentation("ready", "Ready to install", DesktopUpdateIntent.CONFIRM, DesktopUpdateIntent.DECLINE, DesktopUpdateIntent.MANUAL, page = releasePage)
    is DesktopUpdateState.HandingOff -> presentation("handing_off", "Starting the installer…", DesktopUpdateIntent.CANCEL, page = releasePage)
    is DesktopUpdateState.HandedOff -> presentation("handed_off", "Installer started", DesktopUpdateIntent.MANUAL, page = releasePage)
    is DesktopUpdateState.InstallFailed -> presentation("install_failed", "Install failed during ${stage.name.lowercase()}", DesktopUpdateIntent.RETRY, DesktopUpdateIntent.MANUAL, page = releasePage)
    is DesktopUpdateState.RetryableFailure -> presentation("retryable_failure", "Download failed and can be retried", DesktopUpdateIntent.RETRY, DesktopUpdateIntent.MANUAL, page = releasePage)
    is DesktopUpdateState.Cancelled -> if (releasePage == null) {
        presentation("cancelled", "Update operation cancelled", DesktopUpdateIntent.CHECK)
    } else {
        presentation("cancelled", "Update operation cancelled", DesktopUpdateIntent.CHECK, DesktopUpdateIntent.MANUAL, page = releasePage)
    }
    is DesktopUpdateState.ManualOnly -> presentation("manual_only", "Open the release page to update manually", DesktopUpdateIntent.MANUAL, DesktopUpdateIntent.CHECK, page = releasePage)
}
private fun presentation(
    status: String,
    message: String,
    vararg actions: DesktopUpdateIntent,
    progress: Int? = null,
    page: String? = null,
) = DesktopUpdatePresentation(status, message, actions.toSet(), progress, page)

class DesktopUpdateScreenModel(
    val controller: DesktopUpdateController,
    parentScope: CoroutineScope,
    private val openUrl: (String) -> Boolean = ::openDesktopUpdateUrl,
    private val executionContext: CoroutineContext = Dispatchers.IO,
) {
    private val parentContext = parentScope.coroutineContext
    private var ownerJob: Job? = null
    internal val closeCancellation = CancellationException("Desktop updater closed")
    val state = controller.state
    private val mutableFeedback = MutableStateFlow<String?>(null)
    val feedback = mutableFeedback.asStateFlow()
    private var job: Job? = null
    internal val operationJob: Job? @Synchronized get() = job
    @Volatile internal var closed = false
        private set
    @Synchronized
    fun intent(intent: DesktopUpdateIntent): Boolean {
        if (closed) return false
        if (intent !in state.value.presentation().actions) return false
        if (intent == DesktopUpdateIntent.CANCEL) return job?.takeIf(Job::isActive)?.let { it.cancel(); true } ?: false
        if (intent == DesktopUpdateIntent.MANUAL) {
            val page = state.value.presentation().releasePage ?: return false
            mutableFeedback.value = if (runCatching { openUrl(page) }.getOrDefault(false)) null else "Could not open update page. Copy this URL: $page"
            return true
        }
        if (job?.isActive == true) return false
        mutableFeedback.value = null
        val owner = SupervisorJob(parentContext[Job]).also { ownerJob = it }
        job = CoroutineScope(parentContext + owner).launch(executionContext) {
            try {
                when (intent) {
                    DesktopUpdateIntent.CHECK -> controller.check(releaseArguments())
                    DesktopUpdateIntent.DOWNLOAD -> controller.download()
                    DesktopUpdateIntent.RETRY -> controller.retry()
                    DesktopUpdateIntent.CONFIRM -> controller.handoff(true)
                    DesktopUpdateIntent.DECLINE -> controller.handoff(false)
                    DesktopUpdateIntent.CANCEL, DesktopUpdateIntent.MANUAL -> Unit
                }
            } finally {
                owner.complete()
            }
        }
        return true
    }
    @Synchronized
    fun dispose() = job?.cancel()
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        job?.cancel(closeCancellation)
        ownerJob?.cancel(closeCancellation)
    }
    suspend fun closeAndJoin() {
        close()
        ownerJob?.join()
    }
    companion object {
        fun releaseArguments(version: String = APP_VERSION) = GetApplicationRelease.Arguments(
            isFoss = false,
            isPreview = false,
            commitCount = 0,
            versionName = version.split('.').filter { segment -> segment.all(Char::isDigit) }.take(3).joinToString("."),
            repository = "mihonapp/mihon",
            forceCheck = true,
        )
    }
}
private fun openDesktopUpdateUrl(url: String): Boolean = runCatching {
    check(Desktop.isDesktopSupported())
    Desktop.getDesktop().browse(URI(url))
}.isSuccess
