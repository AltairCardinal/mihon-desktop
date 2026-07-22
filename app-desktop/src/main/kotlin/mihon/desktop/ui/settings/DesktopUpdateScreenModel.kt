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
import tachiyomi.i18n.MR
import java.awt.Desktop
import java.net.URI
import java.util.Locale
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
    DesktopUpdateState.Idle -> presentation("idle", MR.strings.desktop_update_idle.localized(), DesktopUpdateIntent.CHECK)
    DesktopUpdateState.Checking -> presentation("checking", MR.strings.desktop_update_checking.localized(), DesktopUpdateIntent.CANCEL)
    DesktopUpdateState.UpToDate -> presentation("up_to_date", MR.strings.update_check_no_new_updates.localized(), DesktopUpdateIntent.CHECK)
    is DesktopUpdateState.UpdateAvailable -> presentation("update_available", MR.strings.desktop_update_available.localized(Locale.getDefault(), release.version), DesktopUpdateIntent.DOWNLOAD, DesktopUpdateIntent.MANUAL, page = release.releaseLink)
    DesktopUpdateState.NoCompatiblePackage -> presentation("no_compatible_package", MR.strings.desktop_update_no_compatible_package.localized(), DesktopUpdateIntent.CHECK)
    is DesktopUpdateState.CheckFailed -> presentation("check_failed", MR.strings.desktop_update_check_failed.localized(Locale.getDefault(), reason.localized()), if (retryable) DesktopUpdateIntent.RETRY else DesktopUpdateIntent.CHECK)
    is DesktopUpdateState.Downloading -> presentation(
        "downloading", MR.strings.desktop_update_downloading.localized(Locale.getDefault(), release.version), DesktopUpdateIntent.CANCEL,
        progress = progress?.let { value -> value.totalBytes?.takeIf { it > 0 }?.let { (value.downloadedBytes * 100 / it).toInt().coerceIn(0, 100) } },
        page = release.releaseLink,
    )
    is DesktopUpdateState.Verifying -> presentation("verifying", MR.strings.desktop_update_verifying.localized(Locale.getDefault(), release.version), DesktopUpdateIntent.CANCEL, page = release.releaseLink)
    is DesktopUpdateState.ReadyToInstall -> presentation("ready", MR.strings.desktop_update_ready.localized(), DesktopUpdateIntent.CONFIRM, DesktopUpdateIntent.DECLINE, DesktopUpdateIntent.MANUAL, page = releasePage)
    is DesktopUpdateState.HandingOff -> presentation("handing_off", MR.strings.desktop_update_handing_off.localized(), DesktopUpdateIntent.CANCEL, page = releasePage)
    is DesktopUpdateState.HandedOff -> presentation("handed_off", MR.strings.desktop_update_handed_off.localized(), DesktopUpdateIntent.MANUAL, page = releasePage)
    is DesktopUpdateState.InstallFailed -> presentation("install_failed", stage.localizedFailure(), DesktopUpdateIntent.RETRY, DesktopUpdateIntent.MANUAL, page = releasePage)
    is DesktopUpdateState.RetryableFailure -> presentation("retryable_failure", MR.strings.desktop_update_download_retryable.localized(), DesktopUpdateIntent.RETRY, DesktopUpdateIntent.MANUAL, page = releasePage)
    is DesktopUpdateState.Cancelled -> if (releasePage == null) {
        presentation("cancelled", MR.strings.desktop_update_cancelled.localized(), DesktopUpdateIntent.CHECK)
    } else {
        presentation("cancelled", MR.strings.desktop_update_cancelled.localized(), DesktopUpdateIntent.CHECK, DesktopUpdateIntent.MANUAL, page = releasePage)
    }
    is DesktopUpdateState.ManualOnly -> presentation("manual_only", MR.strings.desktop_update_manual.localized(), DesktopUpdateIntent.MANUAL, DesktopUpdateIntent.CHECK, page = releasePage)
}

private fun mihon.desktop.update.CheckFailure.localized() = when (this) {
    mihon.desktop.update.CheckFailure.REQUEST_FAILED -> MR.strings.desktop_update_failure_request_failed.localized()
    mihon.desktop.update.CheckFailure.OS_TOO_OLD -> MR.strings.desktop_update_failure_os_too_old.localized()
}

private fun mihon.desktop.update.InstallStage.localizedFailure() = when (this) {
    mihon.desktop.update.InstallStage.VERIFY -> MR.strings.desktop_update_install_failed_verify.localized()
    mihon.desktop.update.InstallStage.HANDOFF -> MR.strings.desktop_update_install_failed_handoff.localized()
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
            mutableFeedback.value = if (runCatching { openUrl(page) }.getOrDefault(false)) null else MR.strings.desktop_update_open_failed.localized(Locale.getDefault(), page)
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
