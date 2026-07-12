package mihon.desktop.ui.settings

import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.model.ScreenModelStore
import cafe.adriel.voyager.navigator.tab.Tab
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import mihon.desktop.backup.BackupPreview
import mihon.desktop.backup.DesktopBackupRestorer
import mihon.desktop.backup.RestoreProgress
import mihon.domain.error.AppError
import mihon.domain.task.TaskState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class BackupRestoreScreenModelTest {
    @Test
    fun `backup settings registers restore model with voyager lifecycle`() {
        val source = File("src/main/kotlin/mihon/desktop/ui/settings/BackupSettingsScreen.kt").readText()

        assertTrue(source.contains("rememberScreenModel { backupFactory.create() }"))
        assertFalse(source.contains("remember { backupFactory.create("))
    }

    @Test
    fun `popping backup screen disposes running restore and ignores late progress`() {
        val scope = TestScope(StandardTestDispatcher())
        val restoreStarted = CompletableDeferred<Unit>()
        val restoreCancelled = CompletableDeferred<Unit>()
        var lateProgress: (suspend (RestoreProgress) -> Unit)? = null
        val screen = BackupSettingsScreen()
        val model = BackupRestoreScreenModel(scope, { preview }) { _, onProgress ->
                lateProgress = onProgress
                restoreStarted.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                    error("restore unexpectedly resumed")
                } catch (error: CancellationException) {
                    restoreCancelled.complete(Unit)
                    throw error
                }
        }
        @Suppress("UNCHECKED_CAST")
        val storedModels = ScreenModelStore::class.java.getMethod("getScreenModels")
            .invoke(ScreenModelStore) as MutableMap<String, cafe.adriel.voyager.core.model.ScreenModel>
        storedModels["${screen.key}:restore"] = model

        model.select(File("library.tachibk"))
        scope.advanceUntilIdle()
        model.confirmRestore()
        scope.runCurrent()
        assertTrue(restoreStarted.isCompleted)

        ScreenModelStore.onDispose(screen)
        scope.advanceUntilIdle()

        assertTrue(restoreCancelled.isCompleted)
        assertTrue(model.state.value is BackupRestoreUiState.Restoring)
        scope.launch { lateProgress?.invoke(RestoreProgress(2, 3)) }
        scope.advanceUntilIdle()
        assertEquals(0, (model.state.value as BackupRestoreUiState.Restoring).completed)
    }

    @Test
    fun `restore exposes determinate monotonic progress and reaches total`() {
        val scope = TestScope(StandardTestDispatcher())
        val model = BackupRestoreScreenModel(scope, { preview }) { _, onProgress ->
            onProgress(RestoreProgress(1, 3))
            yield()
            onProgress(RestoreProgress(2, 3))
            yield()
            onProgress(RestoreProgress(3, 3))
            yield()
            TaskState.Success(DesktopBackupRestorer.RestoreResult())
        }
        val observed = mutableListOf<BackupRestoreUiState>()
        val collection = scope.backgroundScope.launch { model.state.toList(observed) }

        model.select(File("library.tachibk"))
        scope.advanceUntilIdle()
        model.confirmRestore()
        scope.advanceUntilIdle()

        val restoring = observed.filterIsInstance<BackupRestoreUiState.Restoring>()
        assertEquals(listOf(0, 1, 2, 3), restoring.map { it.completed })
        assertTrue(restoring.all { it.total == 3 })
        assertTrue(model.state.value is BackupRestoreUiState.Completed)
        collection.cancel()
    }
    @Test
    fun `backup settings is a regular screen reachable from more settings`() {
        val screen = BackupSettingsScreen()
        assertInstanceOf(Screen::class.java, screen)
        assertFalse(screen is Tab)
        assertTrue(MoreRootScreen.backupSettingsDestination() is BackupSettingsScreen)
    }

    @Test
    fun `selecting a valid backup exposes preview before restore`() {
        val scope = TestScope(StandardTestDispatcher())
        val model = BackupRestoreScreenModel(
            scope = scope,
            loadPreview = { preview },
            restore = { error("restore must require confirmation") },
        )

        model.select(File("library.tachibk"))
        scope.advanceUntilIdle()

        val state = model.state.value
        assertTrue(state is BackupRestoreUiState.Preview)
        assertTrue((state as BackupRestoreUiState.Preview).summary == preview)
    }

    @Test
    fun `restore shows partial item results and storage error remains recoverable`() {
        val scope = TestScope(StandardTestDispatcher())
        var outcome: TaskState<DesktopBackupRestorer.RestoreResult> = TaskState.Failure(AppError.Storage())
        val model = BackupRestoreScreenModel(scope, { preview }, { outcome })
        model.select(File("library.tachibk"))
        scope.advanceUntilIdle()

        model.confirmRestore()
        scope.advanceUntilIdle()
        val storage = model.state.value as BackupRestoreUiState.Failure
        assertTrue(storage.recoverable)
        assertTrue(storage.message.contains("磁盘"))

        outcome = TaskState.Failure(
            AppError.PartialFailure(
                failures = listOf(AppError.MalformedData()),
                failedUnits = listOf(AppError.FailedUnit("manga:/broken", AppError.MalformedData())),
            ),
        )
        model.retryRestore()
        scope.advanceUntilIdle()
        val partial = model.state.value as BackupRestoreUiState.PartialSuccess
        assertTrue(partial.failedUnits.single().contains("manga:/broken"))
    }

    @Test
    fun `empty damaged unknown or missing backup has explicit error and running restore can cancel`() {
        val scope = TestScope(StandardTestDispatcher())
        val gate = CompletableDeferred<TaskState<DesktopBackupRestorer.RestoreResult>>()
        val model = BackupRestoreScreenModel(
            scope,
            loadPreview = { throw IllegalArgumentException("Unsupported backup version") },
            restore = { gate.await() },
        )
        model.select(File("unknown.tachibk"))
        scope.advanceUntilIdle()
        assertTrue((model.state.value as BackupRestoreUiState.Failure).message.contains("未知版本"))

        var lateProgress: (suspend (RestoreProgress) -> Unit)? = null
        val cancellable = BackupRestoreScreenModel(scope, { preview }) { _, onProgress ->
            lateProgress = onProgress
            gate.await()
        }
        cancellable.select(File("valid.tachibk"))
        scope.advanceUntilIdle()
        cancellable.confirmRestore()
        scope.advanceUntilIdle()
        assertTrue(cancellable.state.value is BackupRestoreUiState.Restoring)
        cancellable.cancel()
        scope.advanceUntilIdle()
        assertTrue(cancellable.state.value is BackupRestoreUiState.Cancelled)
        scope.launch { lateProgress?.invoke(RestoreProgress(1, 2)) }
        scope.advanceUntilIdle()
        assertTrue(cancellable.state.value is BackupRestoreUiState.Cancelled)
    }

    private companion object {
        val preview = BackupPreview(2, 8, 1, 1, 3, 2, 1)
    }
}
