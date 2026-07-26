package mihon.desktop.test.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mihon.desktop.backup.BackupRestoreScreenModelFactory
import mihon.desktop.backup.BackupWorkflow
import mihon.desktop.ui.settings.BackupRestoreFailureReason
import mihon.desktop.ui.settings.BackupRestoreScreenModel
import mihon.desktop.ui.settings.BackupRestoreUiState
import mihon.domain.error.StoredAppError
import mihon.domain.error.toStoredAppError
import mihon.domain.task.TaskState
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class BackupTestSnapshot(
    val phase: String = "IDLE",
    val file: String? = null,
    val completed: Int = 0,
    val total: Int = 0,
    val confirmationRequired: Boolean = false,
    val recoverable: Boolean = false,
    val error: StoredAppError? = null,
    val failureDetail: String? = null,
)

@Serializable
enum class BackupTestFailureCode {
    MISSING_PARAMETER,
    INVALID_PARAMETER,
    CONFIRMATION_REQUIRED,
    OPERATION_IN_PROGRESS,
    OPERATION_REJECTED,
    WORKFLOW_FAILED,
    PARTIAL_FAILURE,
    OWNER_CLOSED,
    UNSUPPORTED_ACTION,
}

@Serializable
data class BackupTestActionResult(
    val success: Boolean,
    val snapshot: BackupTestSnapshot,
    val failureCode: BackupTestFailureCode? = null,
)

class BackupTestModeController(
    private val factory: BackupRestoreScreenModelFactory,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val beforeCreateStart: suspend () -> Unit = {},
) {
    private val closed = AtomicBoolean(false)
    private val mutex = Mutex()
    private val restoreModelDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { factory.create(scope) }
    private val restoreModel: BackupRestoreScreenModel by restoreModelDelegate
    private val createState = AtomicReference<BackupTestSnapshot?>(BackupTestSnapshot())
    private val activeCreate = AtomicReference<Deferred<TaskState<File>>?>()

    fun snapshot(): BackupTestSnapshot {
        createState.get()?.let { return it }
        return restoreModel.state.value.toSnapshot()
    }

    suspend fun execute(
        action: String,
        params: Map<String, String>,
    ): BackupTestActionResult {
        if (closed.get()) return failure(BackupTestFailureCode.OWNER_CLOSED)
        if (action == "backup_cancel") {
            activeCreate.getAndSet(null)?.cancelAndJoin()
            if (restoreModelDelegate.isInitialized()) restoreModel.cancel()
            createState.set(BackupTestSnapshot(phase = "CANCELLED"))
            return BackupTestActionResult(true, snapshot())
        }
        if (!mutex.tryLock()) return failure(BackupTestFailureCode.OPERATION_IN_PROGRESS)
        return try {
            when (action) {
                "backup_create" -> create(params)
                "backup_restore" -> restore(params)
                else -> failure(BackupTestFailureCode.UNSUPPORTED_ACTION)
            }
        } finally {
            mutex.unlock()
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (restoreModelDelegate.isInitialized()) restoreModel.onDispose()
        scope.cancel()
        BackupTestModeBridge.clear(this)
    }

    private suspend fun create(params: Map<String, String>): BackupTestActionResult {
        val path = params["directory"] ?: return failure(BackupTestFailureCode.MISSING_PARAMETER)
        val directory = File(path)
        if (!directory.isDirectory) return failure(BackupTestFailureCode.INVALID_PARAMETER)
        createState.set(BackupTestSnapshot(phase = "CREATING", file = directory.absolutePath))
        val deferred = scope.async(start = CoroutineStart.LAZY) {
            BackupWorkflow.runCreate { factory.createBackup(directory) }
        }
        if (!activeCreate.compareAndSet(null, deferred)) {
            deferred.cancelAndJoin()
            return failure(BackupTestFailureCode.OPERATION_IN_PROGRESS)
        }
        val state = try {
            beforeCreateStart()
            deferred.start()
            deferred.await()
        } catch (_: CancellationException) {
            withContext(NonCancellable) {
                deferred.cancelAndJoin()
            }
            TaskState.Cancelled
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                deferred.cancelAndJoin()
            }
            throw error
        } finally {
            activeCreate.compareAndSet(deferred, null)
        }
        val result = when (state) {
            is TaskState.Success -> BackupTestActionResult(
                true,
                BackupTestSnapshot(phase = "CREATED", file = state.value.absolutePath),
            )
            is TaskState.Failure -> BackupTestActionResult(
                false,
                BackupTestSnapshot(phase = "FAILED", file = directory.absolutePath, recoverable = true, error = state.error.toStoredAppError()),
                BackupTestFailureCode.WORKFLOW_FAILED,
            )
            TaskState.Cancelled -> BackupTestActionResult(
                false,
                BackupTestSnapshot(phase = "CANCELLED", file = directory.absolutePath),
                BackupTestFailureCode.OPERATION_REJECTED,
            )
            TaskState.Idle, is TaskState.Running -> failure(BackupTestFailureCode.OPERATION_REJECTED)
        }
        createState.set(result.snapshot)
        return result
    }

    private suspend fun restore(params: Map<String, String>): BackupTestActionResult {
        createState.set(null)
        val confirm = params["confirm"]?.toBooleanStrictOrNull() ?: false
        if (!confirm) {
            val path = params["file"] ?: return failure(BackupTestFailureCode.MISSING_PARAMETER)
            val file = File(path)
            if (!file.isFile) return failure(BackupTestFailureCode.INVALID_PARAMETER)
            restoreModel.select(file)
            restoreModel.state.first { it !is BackupRestoreUiState.Loading }
            return terminalRestoreResult(confirmationIsFailure = true)
        }
        if (restoreModel.state.value !is BackupRestoreUiState.Preview) {
            return failure(BackupTestFailureCode.OPERATION_REJECTED)
        }
        restoreModel.confirmRestore()
        restoreModel.state.first { it !is BackupRestoreUiState.Restoring }
        return terminalRestoreResult(confirmationIsFailure = false)
    }

    private fun terminalRestoreResult(confirmationIsFailure: Boolean): BackupTestActionResult {
        val state = restoreModel.state.value
        val code = when (state) {
            is BackupRestoreUiState.Preview ->
                if (confirmationIsFailure) BackupTestFailureCode.CONFIRMATION_REQUIRED else null
            is BackupRestoreUiState.PartialSuccess -> BackupTestFailureCode.PARTIAL_FAILURE
            is BackupRestoreUiState.Failure -> BackupTestFailureCode.WORKFLOW_FAILED
            BackupRestoreUiState.Cancelled -> BackupTestFailureCode.OPERATION_REJECTED
            else -> null
        }
        return BackupTestActionResult(code == null, state.toSnapshot(), code)
    }

    private fun failure(code: BackupTestFailureCode) = BackupTestActionResult(false, snapshot(), code)
}

private fun BackupRestoreUiState.toSnapshot(): BackupTestSnapshot = when (this) {
    BackupRestoreUiState.Empty -> BackupTestSnapshot()
    is BackupRestoreUiState.Loading -> BackupTestSnapshot(phase = "LOADING_PREVIEW", file = fileName)
    is BackupRestoreUiState.Preview -> BackupTestSnapshot(
        phase = "AWAITING_CONFIRMATION",
        file = file.absolutePath,
        total = summary.categoryCount + summary.mangaCount,
        confirmationRequired = true,
    )
    is BackupRestoreUiState.Restoring -> BackupTestSnapshot(
        phase = "RESTORING",
        file = fileName,
        completed = completed,
        total = total,
    )
    is BackupRestoreUiState.Completed -> BackupTestSnapshot(phase = "COMPLETED", completed = restoredItems, total = restoredItems)
    is BackupRestoreUiState.PartialSuccess -> BackupTestSnapshot(
        phase = "PARTIAL_FAILURE",
        recoverable = true,
        error = error.toStoredAppError(),
    )
    is BackupRestoreUiState.Failure -> BackupTestSnapshot(
        phase = "FAILED",
        recoverable = recoverable,
        error = (reason as? BackupRestoreFailureReason.Restore)?.error?.toStoredAppError(),
        failureDetail = reason::class.simpleName,
    )
    BackupRestoreUiState.Cancelled -> BackupTestSnapshot(phase = "CANCELLED")
}

object BackupTestModeBridge {
    private val value = AtomicReference<BackupTestModeController?>()
    val controller: BackupTestModeController? get() = value.get()
    fun install(controller: BackupTestModeController) { value.set(controller) }
    fun clear(expected: BackupTestModeController): Boolean = value.compareAndSet(expected, null)
}
