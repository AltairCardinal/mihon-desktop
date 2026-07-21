package mihon.desktop.update

import tachiyomi.domain.release.model.Release

enum class CheckFailure { REQUEST_FAILED, OS_TOO_OLD }
enum class UpdateOperation { DOWNLOAD }
enum class InstallStage { VERIFY, HANDOFF }

sealed interface DesktopUpdateState {
    data object Idle : DesktopUpdateState
    data object Checking : DesktopUpdateState
    data object UpToDate : DesktopUpdateState
    data class UpdateAvailable(val release: Release) : DesktopUpdateState
    data object NoCompatiblePackage : DesktopUpdateState
    data class CheckFailed(val reason: CheckFailure, val retryable: Boolean, val cause: Throwable? = null) : DesktopUpdateState
    data class Downloading(val release: Release, val progress: DownloadProgress? = null) : DesktopUpdateState
    data class Verifying(val release: Release, val download: VerifiedDownload) : DesktopUpdateState
    data class ReadyToInstall(val releasePage: String, val preparation: mihon.desktop.update.ReadyToInstall) : DesktopUpdateState
    data class HandingOff(val releasePage: String) : DesktopUpdateState
    data class HandedOff(val releasePage: String) : DesktopUpdateState
    data class InstallFailed(
        val stage: InstallStage,
        val releasePage: String,
        val reason: InstallFailure? = null,
        val cause: Throwable? = null,
    ) : DesktopUpdateState
    data class RetryableFailure(
        val operation: UpdateOperation,
        val releasePage: String,
        val reason: DownloadFailure? = null,
        val cause: Throwable? = null,
    ) : DesktopUpdateState
    data class Cancelled(val releasePage: String?) : DesktopUpdateState
    data class ManualOnly(val releasePage: String) : DesktopUpdateState
}
