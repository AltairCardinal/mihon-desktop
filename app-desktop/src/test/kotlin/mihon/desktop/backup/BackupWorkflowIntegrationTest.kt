package mihon.desktop.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import mihon.desktop.backup.models.Backup
import mihon.desktop.backup.models.BackupManga
import mihon.domain.error.AppError
import mihon.domain.task.TaskState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.nio.file.AccessDeniedException
import java.io.IOException

class BackupWorkflowIntegrationTest {
    @Test
    fun `repository access denied remains retryable permission error`() = runTest {
        val result = DesktopBackupRestorer.RestoreResult().apply {
            addError("manga:/denied", AccessDeniedException("mihon.db"))
        }

        val failure = assertInstanceOf(TaskState.Failure::class.java, BackupWorkflow.runRestore { result })
        val partial = assertInstanceOf(AppError.PartialFailure::class.java, failure.error)
        assertInstanceOf(AppError.Permission::class.java, partial.failedUnits.single().error)
    }

    @Test
    fun `repository io failure remains retryable storage error`() = runTest {
        val result = DesktopBackupRestorer.RestoreResult().apply { addError("chapters:1", java.io.IOException("ENOSPC")) }

        val failure = assertInstanceOf(TaskState.Failure::class.java, BackupWorkflow.runRestore { result })
        val partial = assertInstanceOf(AppError.PartialFailure::class.java, failure.error)
        assertInstanceOf(AppError.Storage::class.java, partial.failedUnits.single().error)
    }
    @Test
    fun `preview exposes counts before restore`() {
        val preview = BackupWorkflow.preview(Backup(listOf(BackupManga(1, "/a"), BackupManga(2, "/b"))))
        assertEquals(2, preview.mangaCount)
    }

    @Test
    fun `partial restore is reported as recoverable partial failure`() = runTest {
        val state = BackupWorkflow.runRestore {
            DesktopBackupRestorer.RestoreResult().apply {
                incrementSuccess()
                addError("/failed", "bad chapter")
            }
        }
        val failure = assertInstanceOf(TaskState.Failure::class.java, state)
        assertInstanceOf(AppError.PartialFailure::class.java, failure.error)
    }

    @Test
    fun `cancellation remains cancellation`() = runTest {
        val state = BackupWorkflow.runRestore { throw CancellationException("cancel") }
        assertEquals(TaskState.Cancelled, state)
    }

    @Test
    fun `disk full is a recoverable storage error`() = runTest {
        val state = BackupWorkflow.runCreate { throw IOException("There is not enough space on the disk") }
        assertInstanceOf(AppError.Storage::class.java, assertInstanceOf(TaskState.Failure::class.java, state).error)
    }
}
