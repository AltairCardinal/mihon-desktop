package mihon.desktop.backup

import kotlinx.coroutines.CancellationException
import mihon.desktop.backup.models.Backup
import mihon.domain.error.AppError
import mihon.domain.task.TaskState
import java.io.IOException
import java.nio.file.AccessDeniedException

data class BackupPreview(
    val mangaCount: Int,
    val chapterCount: Int,
    val categoryCount: Int,
    val trackingCount: Int,
    val preferenceCount: Int,
    val sourceCount: Int,
    val extensionRepoCount: Int,
)

object BackupWorkflow {
    fun preview(backup: Backup) = BackupPreview(
        mangaCount = backup.backupManga.size,
        chapterCount = backup.backupManga.sumOf { it.chapters.size },
        categoryCount = backup.backupCategories.size,
        trackingCount = backup.backupManga.sumOf { it.tracking.size },
        preferenceCount = backup.backupPreferences.size + backup.backupSourcePreferences.sumOf { it.prefs.size },
        sourceCount = backup.backupSources.size,
        extensionRepoCount = backup.backupExtensionRepo.size,
    )

    suspend fun <T> runCreate(block: suspend () -> T): TaskState<T> = runTask(block)

    suspend fun runRestore(block: suspend () -> DesktopBackupRestorer.RestoreResult): TaskState<DesktopBackupRestorer.RestoreResult> {
        return when (val state = runTask(block)) {
            is TaskState.Success -> {
                val result = state.value
                if (!result.hasErrors) state else TaskState.Failure(
                    AppError.PartialFailure(
                        failures = result.errors.map { (key, _) -> result.errorFor(key) },
                        failedUnits = result.errors.map { (key, _) ->
                            AppError.FailedUnit(key, result.errorFor(key))
                        },
                    ),
                )
            }
            else -> state
        }
    }

    private suspend fun <T> runTask(block: suspend () -> T): TaskState<T> = try {
        TaskState.Success(block())
    } catch (_: CancellationException) {
        TaskState.Cancelled
    } catch (error: IOException) {
        TaskState.Failure(AppError.Storage(error))
    } catch (error: Exception) {
        TaskState.Failure(AppError.MalformedData(error))
    }
}

private fun DesktopBackupRestorer.RestoreResult.errorFor(key: String): AppError =
    when (val cause = failures.lastOrNull { it.key == key }?.cause) {
        is AccessDeniedException, is SecurityException -> AppError.Permission(cause)
        is IOException -> AppError.Storage(cause)
        else -> AppError.MalformedData(cause)
    }
