package mihon.desktop.domain

import mihon.desktop.download.DesktopDownloadIdentityResolver
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DesktopDownloadProvider
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.security.MessageDigest

/** A platform adapter for the fixed files selected by a migration confirmation. */
class DesktopMigrationFiles(
    private val profileDirectory: File,
    private val provider: DesktopDownloadProvider,
    private val manager: DesktopDownloadManager,
    private val covers: DesktopCustomCoverStore,
    private val identities: DesktopDownloadIdentityResolver,
    private val chapters: GetChaptersByMangaId,
) {
    private val generations = java.util.concurrent.ConcurrentHashMap<
        String,
        Map<Long, mihon.desktop.download.CapturedDownloadGeneration>,
        >()
    fun staging(operationId: String): MigrationFileStaging {
        val key = MessageDigest.getInstance("SHA-256").digest(operationId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return MigrationFileStaging(File(profileDirectory, "migration-files/$key"))
    }

    fun hasDownloads(source: Manga) =
        provider.captureMangaDownloadArtifacts(source.source, source.title, identities.resolve(source)).isNotEmpty()

    suspend fun capture(source: Manga, options: MigrationOptions, operationId: String): MigrationFileStaging.Snapshot {
        val sourceChapters = if (options.removeDownloads) {
            chapters.awaitOrThrow(source.id, applyScanlatorFilter = false)
        } else {
            emptyList()
        }
        val acceptedGenerations = manager.captureMigrationGenerations(sourceChapters.mapTo(mutableSetOf()) { it.id })
        val originals = if (options.removeDownloads) {
            provider.captureMangaDownloadArtifacts(source.source, source.title, identities.resolve(source))
        } else {
            emptyList()
        }
        val chapterIds = if (originals.isNotEmpty()) {
            sourceChapters.filter { chapter ->
                provider.chapterDownloadArtifacts(source.source, identities.resolve(source, chapter)).any { alias ->
                    originals.any { it.absoluteFile.normalize() == alias.absoluteFile.normalize() }
                }
            }.mapTo(mutableSetOf()) { it.id }
        } else {
            emptySet()
        }
        val originalCover = if (options.copyCustomCover) {
            covers.getCustomCoverFile(source.id).also { check(it.isFile) { "Source custom cover no longer exists" } }
        } else {
            null
        }
        return covers.reserveMigrationCovers(if (originalCover != null) setOf(source.id) else emptySet()).use {
            staging(operationId).capture(originals, originalCover, chapterIds).also {
                generations[operationId] = acceptedGenerations.filterKeys { it in chapterIds }
            }
        }
    }

    suspend fun <T> reserved(
        operationId: String,
        snapshot: MigrationFileStaging.Snapshot,
        targetId: Long,
        operation: suspend () -> T,
    ): T {
        var operationStarted = false
        suspend fun covered(): T = covers.reserveMigrationCovers(
            if (snapshot.coverSnapshot != null) setOf(targetId) else emptySet(),
        ).use {
            operationStarted = true
            operation()
        }
        try {
            return if (snapshot.downloads.isEmpty()) {
                covered()
            } else {
                val artifacts = snapshot.downloads.flatMap { listOf(File(it.original), File(it.staged)) }
                val accepted = requireNotNull(generations[operationId]) {
                    "Interrupted migration must restore its original files before a new confirmation"
                }
                manager.withMigrationArtifacts(snapshot.chapterIds, artifacts, accepted) { covered() }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: tachiyomi.domain.chapter.service.ChapterDirectoryDownloadConflictException) {
            throw error
        } catch (error: Exception) {
            if (!operationStarted) throw MigrationFileConfirmationException(error)
            throw error
        }
    }

    suspend fun restorePrepared(operationId: String, snapshot: MigrationFileStaging.Snapshot, targetId: Long) {
        val originalPaths = snapshot.downloads.flatMap { listOf(File(it.original), File(it.staged)) }
        manager.withMigrationRollback(snapshot.chapterIds, originalPaths) {
            covers.reserveMigrationCovers(if (snapshot.coverSnapshot != null) setOf(targetId) else emptySet()).use {
                staging(operationId).rollback(snapshot)
                filesChanged()
            }
        }
    }

    fun targetCover(targetId: Long) = covers.getCustomCoverFile(targetId)

    fun filesChanged() = provider.notifyAvailabilityChanged()

    fun release(operationId: String) {
        staging(operationId).discardPrivateRecord()
        generations.remove(operationId)
    }
}

class MigrationFileConfirmationException(cause: Throwable) : java.io.IOException(
    tachiyomi.i18n.MR.strings.desktop_migration_files_changed.localized(),
    cause,
)
