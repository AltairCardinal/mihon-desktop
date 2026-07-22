package mihon.desktop.ui.settings

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mihon.desktop.backup.BackupPreview
import mihon.desktop.backup.DesktopBackupRestorer
import mihon.desktop.backup.RestoreProgress
import mihon.domain.error.AppError
import mihon.domain.task.TaskState
import java.io.File

sealed interface BackupRestoreUiState {
    data object Empty : BackupRestoreUiState
    data class Loading(val fileName: String) : BackupRestoreUiState
    data class Preview(val file: File, val summary: BackupPreview) : BackupRestoreUiState
    data class Restoring(val fileName: String, val completed: Int, val total: Int) : BackupRestoreUiState {
        val progress: Float get() = if (total == 0) 1f else completed.toFloat() / total
    }
    data class Completed(val restoredItems: Int) : BackupRestoreUiState
    data class PartialSuccess(val error: AppError.PartialFailure) : BackupRestoreUiState
    data class Failure(val reason: BackupRestoreFailureReason, val recoverable: Boolean) : BackupRestoreUiState
    data object Cancelled : BackupRestoreUiState
}

sealed interface BackupRestoreFailureReason {
    data object EmptyBackup : BackupRestoreFailureReason
    data object UnsupportedVersion : BackupRestoreFailureReason
    data object EmptyFile : BackupRestoreFailureReason
    data object MissingData : BackupRestoreFailureReason
    data object Corrupted : BackupRestoreFailureReason
    data object RestoreNotStarted : BackupRestoreFailureReason
    data class Restore(val error: AppError) : BackupRestoreFailureReason
}

class BackupRestoreScreenModel(
    private val loadPreview: suspend (File) -> BackupPreview,
    private val restore: suspend (File, suspend (RestoreProgress) -> Unit) -> TaskState<DesktopBackupRestorer.RestoreResult>,
    scope: CoroutineScope? = null,
) : ScreenModel {
    constructor(
        scope: CoroutineScope,
        loadPreview: suspend (File) -> BackupPreview,
        restore: suspend (File) -> TaskState<DesktopBackupRestorer.RestoreResult>,
    ) : this(loadPreview, { file, _ -> restore(file) }, scope)

    constructor(
        scope: CoroutineScope,
        loadPreview: suspend (File) -> BackupPreview,
        restore: suspend (File, suspend (RestoreProgress) -> Unit) -> TaskState<DesktopBackupRestorer.RestoreResult>,
    ) : this(loadPreview, restore, scope)

    private val scope = scope ?: screenModelScope

    private val mutableState = MutableStateFlow<BackupRestoreUiState>(BackupRestoreUiState.Empty)
    val state: StateFlow<BackupRestoreUiState> = mutableState.asStateFlow()

    private var selectedFile: File? = null
    private var selectedPreview: BackupPreview? = null
    private var job: Job? = null

    fun select(file: File) {
        job?.cancel()
        selectedFile = file
        mutableState.value = BackupRestoreUiState.Loading(file.name)
        job = scope.launch {
            mutableState.value = try {
                val preview = loadPreview(file)
                if (preview.isEmpty()) {
                    BackupRestoreUiState.Failure(BackupRestoreFailureReason.EmptyBackup, recoverable = false)
                } else {
                    selectedPreview = preview
                    BackupRestoreUiState.Preview(file, preview)
                }
            } catch (error: Exception) {
                BackupRestoreUiState.Failure(error.toFailureReason(), recoverable = false)
            }
        }
    }

    fun confirmRestore() = startRestore()

    fun retryRestore() = startRestore()

    private fun startRestore() {
        val file = selectedFile ?: return
        job?.cancel()
        val total = selectedPreview?.let { it.categoryCount + it.mangaCount } ?: 0
        mutableState.value = BackupRestoreUiState.Restoring(file.name, 0, total)
        job = scope.launch {
            val runningJob = coroutineContext[Job]
            val result = restore(file) { progress ->
                if (runningJob?.isActive == true && mutableState.value is BackupRestoreUiState.Restoring) {
                    mutableState.value = BackupRestoreUiState.Restoring(file.name, progress.completed, progress.total)
                }
            }
            if (runningJob?.isActive != true) return@launch
            mutableState.value = when (result) {
                is TaskState.Success -> BackupRestoreUiState.Completed(result.value.successCount)
                is TaskState.Failure -> result.error.toUiState()
                TaskState.Cancelled -> BackupRestoreUiState.Cancelled
                TaskState.Idle -> BackupRestoreUiState.Failure(BackupRestoreFailureReason.RestoreNotStarted, recoverable = true)
                is TaskState.Running -> BackupRestoreUiState.Restoring(file.name, 0, total)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        mutableState.value = BackupRestoreUiState.Cancelled
    }

    override fun onDispose() {
        job?.cancel()
    }
}

private fun BackupPreview.isEmpty(): Boolean =
    mangaCount + chapterCount + categoryCount + trackingCount + preferenceCount + sourceCount + extensionRepoCount == 0

private fun Throwable.toFailureReason(): BackupRestoreFailureReason {
    val detail = message.orEmpty().lowercase()
    return when {
        "version" in detail || "unsupported" in detail -> BackupRestoreFailureReason.UnsupportedVersion
        "empty" in detail -> BackupRestoreFailureReason.EmptyFile
        "missing" in detail || "no manga payload" in detail -> BackupRestoreFailureReason.MissingData
        else -> BackupRestoreFailureReason.Corrupted
    }
}

private fun AppError.toUiState(): BackupRestoreUiState = when (this) {
    is AppError.PartialFailure -> BackupRestoreUiState.PartialSuccess(this)
    AppError.Cancelled -> BackupRestoreUiState.Cancelled
    else -> BackupRestoreUiState.Failure(BackupRestoreFailureReason.Restore(this), recoverable = true)
}
