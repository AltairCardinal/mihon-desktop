@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package mihon.desktop.backup

import mihon.desktop.backup.models.Backup
import mihon.desktop.backup.models.BackupCategory
import mihon.desktop.backup.models.BackupChapter
import mihon.desktop.backup.models.BackupHistory
import mihon.desktop.backup.models.BackupManga
import mihon.desktop.backup.models.BackupPreference
import mihon.desktop.backup.models.BackupSource
import mihon.desktop.backup.models.BackupSourcePreferences
import mihon.desktop.backup.models.BooleanPreferenceValue
import mihon.desktop.backup.models.FloatPreferenceValue
import mihon.desktop.backup.models.IntPreferenceValue
import mihon.desktop.backup.models.LongPreferenceValue
import mihon.desktop.backup.models.StringPreferenceValue
import mihon.desktop.backup.models.StringSetPreferenceValue
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.data.backup.BackupCodec
import eu.kanade.tachiyomi.data.backup.models.backupExtensionReposMapper
import eu.kanade.tachiyomi.data.backup.models.backupTrackMapper
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.prefs.Preferences

/**
 * Creates and reads `.tachibk` backup files in the same protobuf+gzip format
 * as the Android version, enabling cross-platform backup compatibility.
 */
object DesktopBackupCreator {

    private val filenameDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")

    // ── Encode / decode ────────────────────────────────────────────────────────

    /**
     * Encodes [backup] to gzip-compressed protobuf bytes.
     */
    fun encodeToBytes(backup: Backup): ByteArray {
        return BackupCodec.encode(Backup.serializer(), backup)
    }

    /**
     * Decodes gzip-compressed protobuf [bytes] back to a [Backup] object.
     */
    fun decodeFromBytes(bytes: ByteArray): Backup {
        return BackupCodec.decode(Backup.serializer(), bytes)
    }

    // ── File I/O ──────────────────────────────────────────────────────────────

    /**
     * Writes [backup] as a `.tachibk` file inside [directory].
     * Filename: `mihon_YYYY-MM-DD_HH-mm-ss-SSS.tachibk`
     */
    @Synchronized
    fun writeBackupFile(backup: Backup, directory: File): File {
        directory.mkdirs()
        val timestamp = LocalDateTime.now().format(filenameDateFormat)
        val file = nextAvailableBackupFile(directory, timestamp)
        file.writeBytes(encodeToBytes(backup))
        return file
    }

    private fun nextAvailableBackupFile(directory: File, timestamp: String): File {
        val baseName = "mihon_$timestamp"
        var file = File(directory, "$baseName.tachibk")
        var suffix = 2
        while (file.exists()) {
            file = File(directory, "${baseName}_$suffix.tachibk")
            suffix += 1
        }
        return file
    }

    /**
     * Reads and decodes a `.tachibk` backup file.
     * Returns null if the file cannot be decoded (corrupted, wrong format, empty).
     */
    fun readBackupFile(file: File): Backup? = try {
        if (!file.exists() || file.length() == 0L || file.extension != "tachibk") return null
        decodeFromBytes(file.readBytes())
    } catch (_: Exception) {
        null
    }

    // ── Database export ───────────────────────────────────────────────────────

    /**
     * Reads all library data from repositories and assembles a [Backup].
     */
    suspend fun createFromDatabase(
        mangaRepository: MangaRepository,
        chapterRepository: ChapterRepository,
        categoryRepository: CategoryRepository,
        historyRepository: HistoryRepository,
        excludedScanlatorsForManga: suspend (Long) -> List<String> = { emptyList() },
        trackRepository: TrackRepository = Injekt.get(),
        preferenceStore: PreferenceStore = Injekt.get(),
        sourcePreferenceStore: (Long) -> PreferenceStore = { sourceId ->
            DesktopPreferenceStore(Preferences.userRoot().node("/mihon/source_$sourceId"))
        },
        extensionRepoRepository: ExtensionRepoRepository = Injekt.get(),
    ): Backup {
        val mangas = mangaRepository.getFavorites()
        val allCategories = categoryRepository.getAll()

        // Build category index map: category.id → position in backup list (0-based)
        val categoryIdToIndex = allCategories.mapIndexed { index, cat -> cat.id to index.toLong() }.toMap()

        val backupCategories = allCategories.mapIndexed { index, cat ->
            BackupCategory(name = cat.name, order = index.toLong(), id = cat.id, flags = cat.flags)
        }

        val backupMangas = mangas.map { manga ->
            val chapters = chapterRepository.getChapterByMangaId(manga.id)
            val history = historyRepository.getHistoryByMangaId(manga.id)
            val tracking = trackRepository.getTracksByMangaId(manga.id)
            val mangaCategories = categoryRepository.getCategoriesByMangaId(manga.id)

            val backupChapters = chapters.map { ch ->
                BackupChapter(
                    url = ch.url,
                    name = ch.name,
                    scanlator = ch.scanlator,
                    read = ch.read,
                    bookmark = ch.bookmark,
                    lastPageRead = ch.lastPageRead,
                    dateFetch = ch.dateFetch,
                    dateUpload = ch.dateUpload,
                    chapterNumber = ch.chapterNumber.toFloat(),
                    sourceOrder = ch.sourceOrder,
                    lastModifiedAt = ch.lastModifiedAt,
                    version = ch.version,
                )
            }

            val chapterIdToUrl = chapters.associate { it.id to it.url }
            val backupHistory = history.mapNotNull { hist ->
                val url = chapterIdToUrl[hist.chapterId] ?: return@mapNotNull null
                BackupHistory(url = url, lastRead = hist.readAt?.time ?: 0L)
            }

            // Category indices: positions in backupCategories list
            val categoryIndices = mangaCategories
                .mapNotNull { cat -> categoryIdToIndex[cat.id] }

            BackupManga(
                source = manga.source,
                url = manga.url,
                title = manga.title,
                artist = manga.artist,
                author = manga.author,
                description = manga.description,
                genre = manga.genre ?: emptyList(),
                status = manga.status.toInt(),
                thumbnailUrl = manga.thumbnailUrl,
                dateAdded = manga.dateAdded,
                viewer = manga.viewerFlags.toInt(),
                favorite = manga.favorite,
                chapterFlags = manga.chapterFlags.toInt(),
                chapters = backupChapters,
                history = backupHistory,
                tracking = tracking.map { track ->
                    backupTrackMapper(
                        track.id,
                        track.mangaId,
                        track.trackerId,
                        track.remoteId,
                        track.libraryId,
                        track.title,
                        track.lastChapterRead,
                        track.totalChapters,
                        track.status,
                        track.score,
                        track.remoteUrl,
                        track.startDate,
                        track.finishDate,
                        track.private,
                    )
                },
                categories = categoryIndices,
                updateStrategy = manga.updateStrategy,
                lastModifiedAt = manga.lastModifiedAt,
                favoriteModifiedAt = manga.favoriteModifiedAt,
                excludedScanlators = excludedScanlatorsForManga(manga.id),
                version = manga.version,
                notes = manga.notes,
                initialized = manga.initialized,
            )
        }

        val sourceIds = mangas.map { it.source }.distinct()
        val backupSources = sourceIds.map { id -> BackupSource(sourceId = id) }
        val backupSourcePreferences = sourceIds.mapNotNull { sourceId ->
            sourcePreferenceStore(sourceId).getAll().toBackupPreferences()
                .takeIf { it.isNotEmpty() }
                ?.let { BackupSourcePreferences(sourceId.toString(), it) }
        }

        return Backup(
            backupManga = backupMangas,
            backupCategories = backupCategories,
            backupSources = backupSources,
            backupPreferences = preferenceStore.getAll().toBackupPreferences(),
            backupSourcePreferences = backupSourcePreferences,
            backupExtensionRepo = extensionRepoRepository.getAll().map(backupExtensionReposMapper),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, *>.toBackupPreferences(): List<BackupPreference> = mapNotNull { (key, value) ->
        when (value) {
            is Int -> BackupPreference(key, IntPreferenceValue(value))
            is Long -> BackupPreference(key, LongPreferenceValue(value))
            is Float -> BackupPreference(key, FloatPreferenceValue(value))
            is String -> BackupPreference(key, StringPreferenceValue(value))
            is Boolean -> BackupPreference(key, BooleanPreferenceValue(value))
            is Set<*> -> (value as? Set<String>)?.let { BackupPreference(key, StringSetPreferenceValue(it)) }
            else -> null
        }
    }
}
