@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package mihon.desktop.backup

import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.desktop.backup.models.Backup
import mihon.desktop.backup.models.BackupCategory
import mihon.desktop.backup.models.BackupChapter
import mihon.desktop.backup.models.BackupHistory
import mihon.desktop.backup.models.BackupManga
import mihon.desktop.backup.models.BackupSource
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.repository.MangaRepository
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Creates and reads `.tachibk` backup files in the same protobuf+gzip format
 * as the Android version, enabling cross-platform backup compatibility.
 */
object DesktopBackupCreator {

    private val proto = ProtoBuf

    private val filenameDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")

    // ── Encode / decode ────────────────────────────────────────────────────────

    /**
     * Encodes [backup] to gzip-compressed protobuf bytes.
     */
    fun encodeToBytes(backup: Backup): ByteArray {
        val protoBytes = proto.encodeToByteArray(backup)
        val buf = java.io.ByteArrayOutputStream()
        GZIPOutputStream(buf).use { it.write(protoBytes) }
        return buf.toByteArray()
    }

    /**
     * Decodes gzip-compressed protobuf [bytes] back to a [Backup] object.
     */
    fun decodeFromBytes(bytes: ByteArray): Backup {
        val protoBytes = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        return proto.decodeFromByteArray(protoBytes)
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
        if (!file.exists() || file.length() == 0L) return null
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

        return Backup(
            backupManga = backupMangas,
            backupCategories = backupCategories,
            backupSources = backupSources,
        )
    }
}
