package mihon.desktop.backup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

/**
 * Periodically creates backups, pruning old ones.
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
) {
    private val scope: CoroutineScope = scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var schedulerJob: Job? = null

    val isRunning: Boolean get() = schedulerJob?.isActive == true

    fun start() {
        if (schedulerJob?.isActive == true) return
        schedulerJob = scope.launch {
            var lastRun = 0L
            while (true) {
                delay(CHECK_INTERVAL_MS)
                val intervalMs = AutoBackupInterval.valueOf(
                    appPreferences.autoBackupInterval.get(),
                ).toMillis()
                if (intervalMs <= 0) continue
                val now = System.currentTimeMillis()
                if (lastRun == 0L || now - lastRun >= intervalMs) {
                    lastRun = now
                    runAutoBackup()
                }
            }
        }
    }

    fun stop() {
        schedulerJob?.cancel()
        schedulerJob = null
    }

    private suspend fun runAutoBackup() {
        try {
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
        } catch (_: Exception) {
            // Silently ignore to keep the scheduler alive.
        }
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
