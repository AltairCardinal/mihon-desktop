package mihon.desktop.backup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mihon.desktop.platform.DesktopPlatformPaths
import mihon.desktop.settings.DesktopAppPreferences
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.repository.MangaRepository
import java.io.File

/** Interval for automatic backups. */
enum class AutoBackupInterval(val hours: Long) {
    OFF(0),
    EVERY_6H(6),
    EVERY_12H(12),
    EVERY_24H(24),
    EVERY_48H(48),
    WEEKLY(168),
    ;

    fun toMillis(): Long = hours * 3_600_000L
}

sealed interface AutoBackupRunResult {
    data object Disabled : AutoBackupRunResult
    data class NotDue(val nextRunAt: Long) : AutoBackupRunResult
    data object Success : AutoBackupRunResult
    data class Failure(val message: String) : AutoBackupRunResult
}

/**
 * Periodically creates backups, pruning old ones.
 *
 * Desktop cannot ask a mobile WorkManager equivalent to wake a terminated process. Instead, the
 * successful-run deadline is persisted and checked immediately on the next application start.
 */
class AutoBackupScheduler(
    private val appPreferences: DesktopAppPreferences,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val categoryRepository: CategoryRepository,
    private val historyRepository: HistoryRepository,
    private val excludedScanlatorsForManga: suspend (Long) -> List<String> = { emptyList() },
    private val defaultBackupDir: File = DesktopPlatformPaths.current().backupsDir,
    scope: CoroutineScope? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val backupAction: (suspend () -> Unit)? = null,
    private val waitForNextCheck: suspend (Long) -> Unit = { delay(it) },
) {
    private val scope: CoroutineScope = scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var schedulerJob: Job? = null

    val isRunning: Boolean get() = schedulerJob?.isActive == true

    fun start() {
        if (schedulerJob?.isActive == true) return
        schedulerJob = scope.launch {
            while (true) {
                runDueBackup()
                waitForNextCheck(CHECK_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        schedulerJob?.cancel()
        schedulerJob = null
    }

    internal suspend fun runDueBackup(): AutoBackupRunResult {
        val interval = runCatching {
            AutoBackupInterval.valueOf(appPreferences.autoBackupInterval.get())
        }.getOrDefault(AutoBackupInterval.OFF)
        val intervalMillis = interval.toMillis()
        if (intervalMillis <= 0) return AutoBackupRunResult.Disabled

        val now = nowMillis()
        val lastSuccess = appPreferences.autoBackupLastSuccessAt.get()
        if (lastSuccess > 0L && now - lastSuccess < intervalMillis) {
            return AutoBackupRunResult.NotDue(lastSuccess + intervalMillis)
        }

        return try {
            backupAction?.invoke() ?: createAndPruneBackup()
            appPreferences.autoBackupLastSuccessAt.set(nowMillis())
            appPreferences.autoBackupLastError.set("")
            AutoBackupRunResult.Success
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message = error.message?.takeIf(String::isNotBlank)
                ?: error::class.simpleName?.takeIf(String::isNotBlank)
                ?: "Unknown error"
            appPreferences.autoBackupLastError.set(message)
            AutoBackupRunResult.Failure(message)
        }
    }

    private suspend fun createAndPruneBackup() {
        val backupDir = File(
            appPreferences.autoBackupDir.get().ifBlank {
                defaultBackupDir.path
            },
        )
        backupDir.mkdirs()

        val backup = DesktopBackupCreator.createFromDatabase(
            mangaRepository,
            chapterRepository,
            categoryRepository,
            historyRepository,
            excludedScanlatorsForManga = excludedScanlatorsForManga,
        )
        DesktopBackupCreator.writeBackupFile(backup, backupDir)

        val maxBackups = appPreferences.autoBackupMaxFiles.get()
        pruneOldBackups(backupDir, maxBackups)
    }

    companion object {
        const val CHECK_INTERVAL_MS = 60_000L
    }
}

/**
 * Deletes oldest .tachibk files in [dir] until at most [maxBackups] remain.
 */
fun pruneOldBackups(dir: File, maxBackups: Int) {
    val backups = dir.listFiles()
        ?.filter { it.extension == "tachibk" }
        ?.sortedByDescending { it.lastModified() }
        ?: return
    if (backups.size <= maxBackups) return
    backups.drop(maxBackups).forEach { it.delete() }
}
