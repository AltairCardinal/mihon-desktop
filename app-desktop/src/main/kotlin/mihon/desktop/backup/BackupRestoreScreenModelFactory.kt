package mihon.desktop.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.desktop.domain.GetExcludedScanlators
import mihon.desktop.domain.SetExcludedScanlators
import mihon.desktop.ui.settings.BackupRestoreScreenModel
import mihon.domain.error.AppError
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import mihon.domain.task.TaskState
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.repository.TrackRepository
import java.io.File
import java.util.prefs.Preferences

class BackupRestoreScreenModelFactory(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val categoryRepository: CategoryRepository,
    private val historyRepository: HistoryRepository,
    private val getExcludedScanlators: GetExcludedScanlators,
    private val setExcludedScanlators: SetExcludedScanlators,
    private val trackRepository: TrackRepository,
    private val preferenceStore: PreferenceStore,
    private val extensionRepoRepository: ExtensionRepoRepository,
) {
    fun create(): BackupRestoreScreenModel =
        BackupRestoreScreenModel(
            loadPreview = { file ->
                val backup = withContext(Dispatchers.IO) { DesktopBackupCreator.readBackupFile(file) }
                    ?: error("empty backup")
                BackupWorkflow.preview(backup)
            },
            restore = { file, onProgress ->
                val backup = withContext(Dispatchers.IO) { DesktopBackupCreator.readBackupFile(file) }
                    ?: return@BackupRestoreScreenModel TaskState.Failure(AppError.MalformedData())
                val restorer = DesktopBackupRestorer(
                    mangaRepository,
                    chapterRepository,
                    categoryRepository,
                    historyRepository,
                    setExcludedScanlatorsForManga = { mangaId, excluded ->
                        setExcludedScanlators.await(mangaId, excluded.toSet())
                    },
                    trackRepository = trackRepository,
                    preferenceStore = preferenceStore,
                    sourcePreferenceStore = { sourceId ->
                        DesktopPreferenceStore(Preferences.userRoot().node("/mihon/source_$sourceId"))
                    },
                    extensionRepoRepository = extensionRepoRepository,
                )
                BackupWorkflow.runRestore {
                    withContext(Dispatchers.IO) { restorer.restore(backup, onProgress) }
                }
            },
        )

    suspend fun createBackup(directory: File): File {
        val backup = withContext(Dispatchers.IO) {
            DesktopBackupCreator.createFromDatabase(
                mangaRepository,
                chapterRepository,
                categoryRepository,
                historyRepository,
                excludedScanlatorsForManga = { mangaId ->
                    getExcludedScanlators.await(mangaId).toList()
                },
            )
        }
        return withContext(Dispatchers.IO) { DesktopBackupCreator.writeBackupFile(backup, directory) }
    }
}
