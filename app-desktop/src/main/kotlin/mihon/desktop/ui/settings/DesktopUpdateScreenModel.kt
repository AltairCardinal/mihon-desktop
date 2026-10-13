package mihon.desktop.ui.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import mihon.desktop.APP_VERSION
import mihon.desktop.platform.DesktopUrlOpener
import mihon.desktop.update.DesktopUpdateController
import mihon.desktop.update.DesktopUpdateState
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.release.service.ApplicationUpdatePreferences
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.model.ForkUpdateRequest
import tachiyomi.domain.release.model.UpdateChannel
import tachiyomi.data.release.DesktopPlatformInfo
import tachiyomi.i18n.MR
import java.util.Locale
import kotlin.coroutines.CoroutineContext

enum class DesktopUpdateIntent { CHECK, DOWNLOAD, CANCEL, RETRY, CONFIRM, DECLINE, MANUAL }
data class DesktopUpdatePresentation(
    val status: String,
    val message: String,
    val actions: Set<DesktopUpdateIntent>,
    val progress: Int? = null,
    val releasePage: String? = null,
    val releaseInfo: String? = null,
    val infoTruncated: Boolean = false,
)
fun DesktopUpdateState.presentation(manualOnly: Boolean = false): DesktopUpdatePresentation = when (this) {
    DesktopUpdateState.Idle -> presentation("idle", MR.strings.desktop_update_idle.localized(), DesktopUpdateIntent.CHECK)
    DesktopUpdateState.Checking -> presentation("checking", MR.strings.desktop_update_checking.localized(), DesktopUpdateIntent.CANCEL)
    DesktopUpdateState.UpToDate -> presentation("up_to_date", MR.strings.update_check_no_new_updates.localized(), DesktopUpdateIntent.CHECK)
    is DesktopUpdateState.UpdateAvailable -> presentation("update_available", MR.strings.desktop_update_available.localized(Locale.getDefault(), release.version), if (manualOnly) DesktopUpdateIntent.CHECK else DesktopUpdateIntent.DOWNLOAD, DesktopUpdateIntent.MANUAL, page = release.releaseLink)
        .copy(releaseInfo = release.info, infoTruncated = release.infoTruncated)
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
    val preferences: ApplicationUpdatePreferences = ApplicationUpdatePreferences(InMemoryPreferenceStore()),
    private val notifyUpdate: suspend (Release) -> Boolean = { false },
    private val manualOnlyUpdates: Boolean = true,
) {
    private val parentContext = parentScope.coroutineContext
    private var ownerJob: Job? = null
    internal val closeCancellation = CancellationException("Desktop updater closed")
    val state = controller.state
    private val mutableFeedback = MutableStateFlow<String?>(null)
    val feedback = mutableFeedback.asStateFlow()
    @Volatile private var job: Job? = null
    private var automaticJob: Job? = null
    private var automaticOwner: Job? = null
    private val automaticOwners = mutableSetOf<Job>()
    @Volatile private var automaticAttempt: Job? = null
    @Volatile private var checkedChannel: UpdateChannel? = null
    internal val operationJob: Job? @Synchronized get() = job
    @Volatile internal var closed = false
        private set
    fun presentation(): DesktopUpdatePresentation = state.value.presentation(manualOnlyUpdates)
    @Synchronized
    fun startAutomaticChecks() {
        if (closed || automaticJob?.isActive == true) return
        val predecessors = automaticOwners.toList()
        val owner = SupervisorJob(parentContext[Job]).also { automaticOwner = it }
        automaticOwners += owner
        owner.invokeOnCompletion { synchronized(this) { automaticOwners.remove(owner) } }
        automaticJob = CoroutineScope(parentContext + owner).launch(executionContext) {
            try {
                // A replaced request may still be releasing its transport and controller lock.
                predecessors.joinAll()
                combine(preferences.enabled().changes(), preferences.testingChannel().changes()) { enabled, testing ->
                    enabled to if (testing) UpdateChannel.TESTING else UpdateChannel.STABLE
                }.distinctUntilChanged().collectLatest { (enabled, channel) ->
                    if (checkedChannel != null && checkedChannel != channel && state.value.canRefreshCheck()) {
                        job?.cancelAndJoin()
                        controller.resetCheck()
                    }
                    checkedChannel = channel
                    if (!enabled) return@collectLatest
                    while (isActive) {
                        if (job?.isActive != true && state.value.canRefreshCheck()) {
                            coroutineScope {
                                val attempt = launch {
                                    val arguments = releaseArguments(channel = channel, forceCheck = false)
                                    if (controller.check(arguments, refresh = true)) {
                                        val release = (state.value as? DesktopUpdateState.UpdateAvailable)?.release
                                        if (release != null) {
                                            val notified = preferences.lastPromptedVersion(requireNotNull(arguments.forkUpdate).platform, channel)
                                            if (notified.get() != release.version) {
                                                val delivered = try {
                                                    notifyUpdate(release)
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (_: Exception) {
                                                    false
                                                }
                                                if (delivered) notified.set(release.version)
                                            }
                                        }
                                    }
                                }
                                automaticAttempt = attempt
                                try {
                                    attempt.join()
                                } finally {
                                    if (automaticAttempt === attempt) automaticAttempt = null
                                }
                            }
                        }
                        delay(CHECK_INTERVAL_MILLIS)
                    }
                }
            } finally {
                owner.complete()
            }
        }
    }
    @Synchronized
    fun stopAutomaticChecks() {
        automaticJob?.cancel()
        automaticOwner?.cancel()
    }
    private fun manualArguments(): GetApplicationRelease.Arguments {
        val channel = if (preferences.testingChannel().get()) UpdateChannel.TESTING else UpdateChannel.STABLE
        checkedChannel = channel
        return releaseArguments(channel = channel)
    }
    @Synchronized
    fun intent(intent: DesktopUpdateIntent): Boolean {
        if (closed) return false
        if (intent !in presentation().actions) return false
        if (intent == DesktopUpdateIntent.CANCEL) {
            val active = job?.takeIf(Job::isActive) ?: automaticAttempt?.takeIf(Job::isActive)
            return active?.let { it.cancel(); true } ?: false
        }
        if (intent == DesktopUpdateIntent.MANUAL) {
            val page = presentation().releasePage ?: return false
            mutableFeedback.value = if (runCatching { openUrl(page) }.getOrDefault(false)) null else MR.strings.desktop_update_open_failed.localized(Locale.getDefault(), page)
            return true
        }
        if (job?.isActive == true) return false
        mutableFeedback.value = null
        val owner = SupervisorJob(parentContext[Job]).also { ownerJob = it }
        job = CoroutineScope(parentContext + owner).launch(executionContext) {
            try {
                when (intent) {
                    DesktopUpdateIntent.CHECK -> controller.check(manualArguments(), refresh = true)
                    DesktopUpdateIntent.DOWNLOAD -> controller.download()
                    DesktopUpdateIntent.RETRY -> if (state.value is DesktopUpdateState.CheckFailed) controller.check(manualArguments(), refresh = true) else controller.retry()
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
        stopAutomaticChecks()
        job?.cancel(closeCancellation)
        ownerJob?.cancel(closeCancellation)
    }
    suspend fun closeAndJoin() {
        close()
        ownerJob?.join()
        synchronized(this) { automaticOwners.toList() }.joinAll()
    }
    companion object {
        private const val CHECK_INTERVAL_MILLIS = 12L * 60 * 60 * 1000
        fun releaseArguments(version: String = APP_VERSION, channel: UpdateChannel = UpdateChannel.TESTING, forceCheck: Boolean = true) = GetApplicationRelease.Arguments(
            isFoss = false,
            isPreview = false,
            commitCount = 0,
            versionName = version,
            repository = ForkUpdateRequest.REPOSITORY,
            forceCheck = forceCheck,
            forkUpdate = ForkUpdateRequest(channel, DesktopPlatformInfo().releaseOs, architecture = DesktopPlatformInfo().preferredAbi),
        )
    }
}
private fun DesktopUpdateState.canRefreshCheck(): Boolean = when (this) {
    DesktopUpdateState.Idle, DesktopUpdateState.Checking, DesktopUpdateState.UpToDate,
    DesktopUpdateState.NoCompatiblePackage, is DesktopUpdateState.CheckFailed,
    is DesktopUpdateState.UpdateAvailable, is DesktopUpdateState.Cancelled -> true
    else -> false
}
private fun openDesktopUpdateUrl(url: String): Boolean = DesktopUrlOpener.open(url).isSuccess
