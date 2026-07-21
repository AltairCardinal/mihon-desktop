package mihon.desktop.backup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.desktop.domain.GetExcludedScanlators
import mihon.desktop.domain.SetExcludedScanlators
import mihon.desktop.platform.DesktopExternalActionTarget
import mihon.desktop.ui.settings.BackupRestoreScreenModel
import mihon.domain.error.AppError
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import mihon.domain.platform.ExternalAction
import mihon.domain.task.TaskState
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.repository.TrackRepository
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
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
    fun create(): BackupRestoreScreenModel = createModel()

    internal fun create(
        target: DesktopExternalActionTarget.Backup,
        scope: CoroutineScope? = null,
    ): BackupRestoreScreenModel = createModel(scope).also { it.select(target.file) }

    private fun createModel(scope: CoroutineScope? = null): BackupRestoreScreenModel =
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
            scope = scope,
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

    companion object {
        fun resolveExternalAction(action: ExternalAction.RestoreBackup): DesktopExternalActionTarget {
            val file = runCatching { action.uri.toBackupPath() }.getOrNull()
                ?: return DesktopExternalActionTarget.Rejected(
                    DesktopExternalActionTarget.Rejection.InvalidBackupPath,
                )
            return DesktopExternalActionTarget.Backup(file.toFile())
        }

        private fun String.toBackupPath(): Path {
            val path = if (matches(Regex("^[A-Za-z]:[\\\\/].*"))) {
                Path.of(this)
            } else {
                val uri = URI(this)
                when (uri.scheme?.lowercase()) {
                    null -> Path.of(this)
                    "file" -> Path.of(uri)
                    else -> error("unsupported backup URI scheme")
                }
            }.toRealPath(LinkOption.NOFOLLOW_LINKS)
            require(path.fileName.toString().endsWith(".tachibk"))
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            return path
        }
    }
}
