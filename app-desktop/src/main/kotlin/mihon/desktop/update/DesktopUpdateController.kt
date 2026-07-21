package mihon.desktop.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import java.util.concurrent.atomic.AtomicLong

class DesktopUpdateController(
    private val checkRelease: suspend (GetApplicationRelease.Arguments) -> GetApplicationRelease.Result,
    private val downloadRelease: suspend (Release, (DownloadProgress) -> Unit) -> DesktopUpdateDownloadResult,
    private val prepareInstall: suspend (VerifiedDownload, String) -> InstallPreparation,
    private val handoffInstall: suspend (ReadyToInstall, Boolean) -> InstallHandoffResult,
) {
    constructor(checker: GetApplicationRelease, downloader: DesktopUpdateDownloader, installer: DesktopUpdateInstaller) : this(
        checker::await,
        downloader::download,
        installer::prepare,
        installer::handoff,
    )

    private val mutableState = MutableStateFlow<DesktopUpdateState>(DesktopUpdateState.Idle)
    val state: StateFlow<DesktopUpdateState> = mutableState.asStateFlow()
    private val operationLock = Mutex()
    private val downloadGeneration = AtomicLong()
    private val activeDownload = AtomicLong()
    private var retryAction: RetryAction? = null
    suspend fun check(arguments: GetApplicationRelease.Arguments) = operate {
        if (retryAction != null || mutableState.value is DesktopUpdateState.UpdateAvailable || mutableState.value is DesktopUpdateState.ReadyToInstall) return@operate false
        checkNow(arguments)
        true
    }
    suspend fun download() = operate {
        val release = (mutableState.value as? DesktopUpdateState.UpdateAvailable)?.release ?: return@operate false
        downloadNow(release)
        true
    }
    suspend fun handoff(confirmed: Boolean) = operate {
        val ready = mutableState.value as? DesktopUpdateState.ReadyToInstall ?: return@operate false
        handoffNow(ready.releasePage, ready.preparation, confirmed)
        true
    }
    suspend fun retry() = operate {
        when (val action = retryAction) {
            is RetryAction.Check -> checkNow(action.arguments)
            is RetryAction.Download -> downloadNow(action.release)
            is RetryAction.Verify -> verifyNow(action.release, action.download)
            is RetryAction.Handoff -> handoffNow(action.releasePage, action.ready, true)
            null -> return@operate false
        }
        true
    }
    private suspend fun checkNow(arguments: GetApplicationRelease.Arguments) {
        mutableState.value = DesktopUpdateState.Checking
        val result = try {
            checkRelease(arguments)
        } catch (error: CancellationException) {
            cancel(null)
            throw error
        } catch (error: Exception) {
            retryAction = RetryAction.Check(arguments)
            mutableState.value = DesktopUpdateState.CheckFailed(CheckFailure.REQUEST_FAILED, true, error)
            return
        }
        retryAction = null
        mutableState.value = when (result) {
            is GetApplicationRelease.Result.NewUpdate -> DesktopUpdateState.UpdateAvailable(result.release)
            GetApplicationRelease.Result.NoNewUpdate -> DesktopUpdateState.UpToDate
            GetApplicationRelease.Result.NoCompatiblePackage -> DesktopUpdateState.NoCompatiblePackage
            GetApplicationRelease.Result.OsTooOld -> DesktopUpdateState.CheckFailed(CheckFailure.OS_TOO_OLD, false)
        }
    }
    private suspend fun downloadNow(release: Release) {
        val generation = downloadGeneration.incrementAndGet()
        activeDownload.set(generation)
        mutableState.value = DesktopUpdateState.Downloading(release)
        val result = try {
            downloadRelease(release) { progress ->
                mutableState.update { current ->
                    if (activeDownload.get() == generation && current is DesktopUpdateState.Downloading) current.copy(progress = progress) else current
                }
            }
        } catch (error: CancellationException) {
            activeDownload.compareAndSet(generation, 0)
            cancel(release.releaseLink)
            throw error
        } catch (error: Exception) {
            activeDownload.compareAndSet(generation, 0)
            downloadFailed(release, cause = error)
            return
        }
        activeDownload.compareAndSet(generation, 0)
        when (result) {
            is VerifiedDownload -> verifyNow(release, result)
            is ManualOnly -> manual(result.releasePage)
            is DownloadFailed -> downloadFailed(release, result.reason)
        }
    }

    private suspend fun verifyNow(release: Release, download: VerifiedDownload) {
        mutableState.value = DesktopUpdateState.Verifying(release, download)
        val result = try {
            val prepared = prepareInstall(download, release.version)
            currentCoroutineContext().ensureActive()
            prepared
        } catch (error: CancellationException) {
            cancel(release.releaseLink)
            throw error
        } catch (error: Exception) {
            cancellationCheckpoint(release.releaseLink)
            installFailed(InstallStage.VERIFY, release.releaseLink, RetryAction.Verify(release, download), cause = error)
            return
        }
        when (result) {
            is ReadyToInstall -> {
                retryAction = null
                mutableState.value = DesktopUpdateState.ReadyToInstall(release.releaseLink, result)
            }
            InstallManualOnly -> manual(release.releaseLink)
            is InstallRejected -> installFailed(InstallStage.VERIFY, release.releaseLink, RetryAction.Verify(release, download), result.reason)
        }
    }
    private suspend fun handoffNow(releasePage: String, ready: ReadyToInstall, confirmed: Boolean) {
        mutableState.value = DesktopUpdateState.HandingOff(releasePage)
        val result = try {
            val handedOff = handoffInstall(ready, confirmed)
            currentCoroutineContext().ensureActive()
            handedOff
        } catch (error: CancellationException) {
            cancel(releasePage)
            throw error
        } catch (error: Exception) {
            cancellationCheckpoint(releasePage)
            installFailed(InstallStage.HANDOFF, releasePage, RetryAction.Handoff(releasePage, ready), cause = error)
            return
        }
        if (!confirmed || result == InstallCancelled) return cancel(releasePage)
        if (result == InstallHandedOff) {
            retryAction = null
            mutableState.value = DesktopUpdateState.HandedOff(releasePage)
        } else installFailed(InstallStage.HANDOFF, releasePage, RetryAction.Handoff(releasePage, ready), (result as InstallHandoffFailed).reason)
    }
    private fun downloadFailed(release: Release, reason: DownloadFailure? = null, cause: Throwable? = null) {
        retryAction = RetryAction.Download(release)
        mutableState.value = DesktopUpdateState.RetryableFailure(UpdateOperation.DOWNLOAD, release.releaseLink, reason, cause)
    }
    private fun installFailed(
        stage: InstallStage,
        releasePage: String,
        retry: RetryAction,
        reason: InstallFailure? = null,
        cause: Throwable? = null,
    ) {
        retryAction = retry
        mutableState.value = DesktopUpdateState.InstallFailed(stage, releasePage, reason, cause)
    }
    private fun manual(releasePage: String) {
        retryAction = null
        mutableState.value = DesktopUpdateState.ManualOnly(releasePage)
    }
    private fun cancel(releasePage: String?) {
        retryAction = null
        mutableState.value = DesktopUpdateState.Cancelled(releasePage)
    }
    private suspend fun cancellationCheckpoint(releasePage: String?) {
        try {
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            cancel(releasePage)
            throw cancelled
        }
    }
    private suspend fun operate(block: suspend () -> Boolean): Boolean {
        if (!operationLock.tryLock()) return false
        return try {
            block()
        } finally {
            operationLock.unlock()
        }
    }
    private sealed interface RetryAction {
        data class Check(val arguments: GetApplicationRelease.Arguments) : RetryAction
        data class Download(val release: Release) : RetryAction
        data class Verify(val release: Release, val download: VerifiedDownload) : RetryAction
        data class Handoff(val releasePage: String, val ready: ReadyToInstall) : RetryAction
    }
}
