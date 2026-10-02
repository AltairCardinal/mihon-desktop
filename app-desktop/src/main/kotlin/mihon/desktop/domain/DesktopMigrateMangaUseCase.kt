package mihon.desktop.domain

import eu.kanade.tachiyomi.source.model.SChapter
import mihon.domain.migration.models.MigrationFlag
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * Flags controlling which metadata is copied during a migration.
 */
@kotlinx.serialization.Serializable
data class MigrationOptions(
    val copyChapters: Boolean = true,
    val copyCategories: Boolean = true,
    val copyNotes: Boolean = true,
    val copyCustomCover: Boolean = false,
    val removeDownloads: Boolean = false,
)

@kotlinx.serialization.Serializable
data class AcceptedMigration(
    val source: Manga,
    val options: MigrationOptions,
    val replace: Boolean,
    val operationId: String,
    val acceptedAt: Long,
    val checkpointOwner: String? = null,
    @kotlinx.serialization.Transient val files: MigrationFileStaging.Snapshot? = null,
)

/**
 * Executes a manga migration on desktop:
 * 1. Saves [target] manga and chapters through [SaveSourceMangaForDetails]
 * 2. Optionally copies chapter read status (by chapter number)
 * 3. Optionally copies category assignments
 * 4. Optionally copies notes
 * 5. If [replace]=true, removes [sourceManga] from the library
 */
class DesktopMigrateMangaUseCase(
    private val saveSourceMangaForDetails: SaveSourceMangaForDetails,
    private val mangaRepository: MangaRepository,
    private val migrationFiles: () -> DesktopMigrationFiles? = { null },
    private val checkpointAccepted: suspend (mihon.domain.migration.MigrationReceipt) -> Boolean = { false },
) {
    fun hasDownloads(source: Manga): Boolean = requireNotNull(migrationFiles()).hasDownloads(source)

    private fun acceptedFiles(accepted: AcceptedMigration): MigrationFileStaging.Snapshot? = accepted.files
        ?: migrationFiles()?.staging(accepted.operationId)?.load()

    suspend fun recoverAccepted(accepted: AcceptedMigration, deferAcknowledgement: Boolean = false): Manga? {
        val receipt = mangaRepository.migrationReceipt(accepted.source.id) ?: return null
        check(receipt.request.operationId == accepted.operationId) { "Another migration owns this manga" }
        return finish(receipt.request, acceptedFiles(accepted), deferAcknowledgement)
    }

    suspend fun accept(
        source: Manga,
        options: MigrationOptions,
        replace: Boolean,
        operationId: String = java.util.UUID.randomUUID().toString(),
        checkpointOwner: String? = null,
    ): AcceptedMigration {
        val files = if (options.copyCustomCover || options.removeDownloads) {
            requireNotNull(migrationFiles()) { "Migration file adapter is not available" }
                .capture(source, options, operationId)
        } else {
            null
        }
        return AcceptedMigration(
            source,
            options,
            replace,
            operationId,
            System.currentTimeMillis(),
            checkpointOwner,
            files,
        )
    }

    suspend fun await(
        sourceManga: Manga,
        targetSManga: eu.kanade.tachiyomi.source.model.SManga,
        targetSourceId: Long,
        targetChapters: List<SChapter>,
        options: MigrationOptions = MigrationOptions(),
        replace: Boolean = true,
        accepted: AcceptedMigration? = null,
        deferAcknowledgement: Boolean = false,
    ): Manga {
        val confirmation = accepted ?: accept(sourceManga, options, replace)
        require(
            confirmation.source.id == sourceManga.id && confirmation.options == options &&
                confirmation.replace == replace,
        )
        require(confirmation.source.source != targetSourceId || confirmation.source.url != targetSManga.url) {
            "Cannot migrate onto the same manga"
        }
        val existing = mangaRepository.migrationReceipt(sourceManga.id)
        if (existing != null) {
            check(
                existing.request.operationId == confirmation.operationId &&
                    existing.request.targetSourceId == targetSourceId && existing.request.targetUrl == targetSManga.url,
            ) {
                "The previous migration must be recovered before accepting another target"
            }
            return finish(existing.request, acceptedFiles(confirmation), deferAcknowledgement)
        }
        val persistedTarget = saveSourceMangaForDetails.await(targetSManga, targetSourceId, targetChapters)
        val flags = buildSet {
            if (options.copyChapters) add(MigrationFlag.CHAPTER)
            if (options.copyCategories) add(MigrationFlag.CATEGORY)
            if (options.copyNotes) add(MigrationFlag.NOTES)
            if (options.copyCustomCover) add(MigrationFlag.CUSTOM_COVER)
            if (options.removeDownloads) add(MigrationFlag.REMOVE_DOWNLOAD)
        }
        val coverVersion = if (options.copyCustomCover) {
            Math.max(confirmation.acceptedAt, Math.addExact(persistedTarget.coverLastModified, 1L))
        } else {
            null
        }
        val command = mihon.domain.migration.MigrationCommit(
            confirmation.source.id, confirmation.source.source, confirmation.source.url,
            persistedTarget.id, targetSourceId, targetSManga.url, flags, replace, confirmation.acceptedAt,
            confirmation.operationId,
            previousCoverVersion = if (options.copyCustomCover) persistedTarget.coverLastModified else null,
            coverVersion = coverVersion,
            checkpointOwner = confirmation.checkpointOwner,
        )
        return finish(command, acceptedFiles(confirmation), deferAcknowledgement)
    }

    suspend fun acknowledge(sourceMangaId: Long, operationId: String) {
        val receipt = requireNotNull(mangaRepository.migrationReceipt(sourceMangaId))
        check(receipt.request.operationId == operationId && receipt.committed && receipt.filesComplete)
        check(receipt.request.checkpointOwner == null || checkpointAccepted(receipt)) {
            "Original migration checkpoint is not accepted"
        }
        migrationFiles()?.release(operationId)
        mangaRepository.acknowledgeMigration(receipt.request)
    }

    suspend fun recoverPendingFiles() {
        recoverPreparedFiles()
        for (receipt in mangaRepository.pendingMigrations().filter { it.committed }) {
            recover(receipt.request.sourceMangaId, deferAcknowledgement = receipt.request.checkpointOwner != null)
            if (receipt.request.checkpointOwner != null && checkpointAccepted(receipt)) {
                acknowledge(receipt.request.sourceMangaId, requireNotNull(receipt.request.operationId))
            }
        }
    }

    private fun requireFileSnapshot(
        receipt: mihon.domain.migration.MigrationReceipt,
        snapshot: MigrationFileStaging.Snapshot?,
    ) {
        check(
            receipt.filesComplete || receipt.request.flags.none {
                it == MigrationFlag.CUSTOM_COVER || it == MigrationFlag.REMOVE_DOWNLOAD
            } || snapshot != null,
        ) { "Original migration file recovery record is missing" }
    }

    suspend fun recoverPreparedFiles() {
        for (receipt in mangaRepository.pendingMigrations().filter { !it.committed }) {
            val command = receipt.request
            val target = mangaRepository.getMangaById(command.targetMangaId)
            val source = mangaRepository.getMangaById(command.sourceMangaId)
            check(
                source.source == command.sourceId && source.url == command.sourceUrl &&
                    target.source == command.targetSourceId && target.url == command.targetUrl,
            ) {
                "Migration identity changed"
            }
            check(command.previousCoverVersion == null || target.coverLastModified == command.previousCoverVersion) {
                "Target custom cover changed while migration was interrupted"
            }
            val operationId = requireNotNull(command.operationId)
            if (command.flags.any { it == MigrationFlag.CUSTOM_COVER || it == MigrationFlag.REMOVE_DOWNLOAD }) {
                val files = requireNotNull(migrationFiles())
                val snapshot =
                    requireNotNull(files.staging(operationId).load()) {
                        "Original migration file recovery record is missing"
                    }
                files.restorePrepared(operationId, snapshot, command.targetMangaId)
            }
            mangaRepository.abortPreparedMigration(command)
            migrationFiles()?.release(operationId)
        }
    }

    suspend fun cancelAccepted(accepted: AcceptedMigration) {
        val pending = mangaRepository.migrationReceipt(accepted.source.id)
        if (pending != null) {
            check(pending.request.operationId == accepted.operationId && !pending.committed) {
                "Migration is already committed; finish its original cleanup"
            }
            val snapshot = acceptedFiles(accepted)
            requireFileSnapshot(pending, snapshot)
            snapshot?.let {
                requireNotNull(
                    migrationFiles(),
                ).restorePrepared(accepted.operationId, it, pending.request.targetMangaId)
            }
            mangaRepository.abortPreparedMigration(pending.request)
        }
        migrationFiles()?.release(accepted.operationId)
    }

    suspend fun recover(sourceMangaId: Long, deferAcknowledgement: Boolean = false): Manga? {
        val receipt = mangaRepository.migrationReceipt(sourceMangaId) ?: return null
        val snapshot = migrationFiles()?.staging(requireNotNull(receipt.request.operationId))?.load()
        check(
            receipt.filesComplete || receipt.request.flags.none {
                it == MigrationFlag.CUSTOM_COVER || it == MigrationFlag.REMOVE_DOWNLOAD
            } || snapshot != null,
        ) { "Original migration file recovery record is missing" }
        return finish(receipt.request, snapshot, deferAcknowledgement)
    }

    private suspend fun finish(
        command: mihon.domain.migration.MigrationCommit,
        snapshot: MigrationFileStaging.Snapshot?,
        deferAcknowledgement: Boolean,
    ): Manga {
        val files = if (snapshot != null) requireNotNull(migrationFiles()) else null
        val operationId = requireNotNull(command.operationId)
        val staging = files?.staging(operationId)
        var receipt = mangaRepository.migrationReceipt(command.sourceMangaId)
        check(
            receipt?.filesComplete == true || command.flags.none {
                it == MigrationFlag.CUSTOM_COVER || it == MigrationFlag.REMOVE_DOWNLOAD
            } || snapshot != null,
        ) { "Original migration file recovery record is missing" }
        if (receipt?.committed != true) {
            suspend fun commitPrepared(): Manga {
                receipt = mangaRepository.prepareMigration(command)
                try {
                    if (snapshot != null) {
                        try {
                            requireNotNull(staging).prepare(
                                snapshot,
                                if (snapshot.coverSnapshot !=
                                    null
                                ) {
                                    requireNotNull(files).targetCover(command.targetMangaId)
                                } else {
                                    null
                                },
                            )
                        } catch (
                            error: kotlinx.coroutines.CancellationException,
                        ) {
                            throw error
                        } catch (error: Exception) {
                            throw MigrationFileConfirmationException(error)
                        }
                        mangaRepository.markMigrationFilesReady(command)
                    }
                    return mangaRepository.commitMigration(command)
                } catch (error: Throwable) {
                    val persisted = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        mangaRepository.migrationReceipt(command.sourceMangaId)
                    }
                    if (persisted?.committed != true && snapshot != null) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            requireNotNull(staging).rollback(snapshot)
                            requireNotNull(files).filesChanged()
                        }
                    }
                    throw error
                }
            }
            if (snapshot != null) {
                requireNotNull(files).reserved(operationId, snapshot, command.targetMangaId) { commitPrepared() }
            } else {
                commitPrepared()
            }
        }
        val target = mangaRepository.getMangaById(command.targetMangaId)
        try {
            if (snapshot != null) {
                requireNotNull(staging).complete(snapshot)
                requireNotNull(files).filesChanged()
            }
            mangaRepository.completeMigrationFiles(command)
            if (!deferAcknowledgement) acknowledge(command.sourceMangaId, operationId)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            throw MigrationCommittedCleanupException(target, error)
        }
        return target
    }
}

class MigrationCommittedCleanupException(val target: Manga, cause: Throwable) :
    java.io.IOException("Migration committed; original file cleanup is pending", cause)
