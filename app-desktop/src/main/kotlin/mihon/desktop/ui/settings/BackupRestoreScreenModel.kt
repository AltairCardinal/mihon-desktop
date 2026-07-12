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
    data class PartialSuccess(val failedUnits: List<String>) : BackupRestoreUiState
    data class Failure(val message: String, val recoverable: Boolean) : BackupRestoreUiState
    data object Cancelled : BackupRestoreUiState
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
                    BackupRestoreUiState.Failure("备份中没有可恢复的数据", recoverable = false)
                } else {
                    selectedPreview = preview
                    BackupRestoreUiState.Preview(file, preview)
                }
            } catch (error: Exception) {
                error.toUiFailure()
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
                TaskState.Idle -> BackupRestoreUiState.Failure("恢复任务未启动", recoverable = true)
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

private fun Throwable.toUiFailure(): BackupRestoreUiState.Failure {
    val detail = message.orEmpty().lowercase()
    val message = when {
        "version" in detail || "unsupported" in detail -> "未知版本或不受支持的备份"
        "empty" in detail -> "备份文件为空"
        "missing" in detail || "no manga payload" in detail -> "备份缺少必要数据"
        else -> "备份文件已损坏或无法读取"
    }
    return BackupRestoreUiState.Failure(message, recoverable = false)
}

private fun AppError.toUiState(): BackupRestoreUiState = when (this) {
    is AppError.PartialFailure -> BackupRestoreUiState.PartialSuccess(
        failedUnits.map { "${it.unitId}：${it.error.label()}" }.ifEmpty { listOf("部分数据恢复失败") },
    )
    is AppError.Storage -> BackupRestoreUiState.Failure("磁盘空间不足或无法写入，请释放空间后重试", true)
    is AppError.Permission -> BackupRestoreUiState.Failure("没有写入数据的权限，请检查权限后重试", true)
    AppError.Cancelled -> BackupRestoreUiState.Cancelled
    else -> BackupRestoreUiState.Failure(label(), true)
}

private fun AppError.label(): String = when (this) {
    is AppError.MalformedData -> "数据损坏或缺失"
    is AppError.Storage -> "存储失败"
    is AppError.Permission -> "权限不足"
    else -> "恢复失败"
}
