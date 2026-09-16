package mihon.data.sync.journal

import mihon.data.sync.inbox.isSyncHistoryBaselineSuppressed
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.creator.repository.CreatorArchiveBootstrap
import java.util.Date
import java.util.UUID

enum class SyncRestoreOutcome { COMPLETED, PARTIAL, CANCELLED, FAILED }

/** Ordinary backups contribute low-priority baselines, never cloned sync state or new user intent. */
interface BackupRestoreSync {
    suspend fun begin(): String?
    suspend fun restoreManga(id: String?, sourceId: Long, url: String, restore: suspend () -> Unit)
    suspend fun restoreAuthors(id: String?, portableKeys: List<String>, restore: suspend () -> Unit)
    suspend fun finish(id: String?, outcome: SyncRestoreOutcome)
}

object NoopBackupRestoreSync : BackupRestoreSync {
    override suspend fun begin(): String? = null
    override suspend fun restoreManga(id: String?, sourceId: Long, url: String, restore: suspend () -> Unit) =
        restore()
    override suspend fun restoreAuthors(id: String?, portableKeys: List<String>, restore: suspend () -> Unit) =
        restore()
    override suspend fun finish(id: String?, outcome: SyncRestoreOutcome) = Unit
}

class SyncBackupRestorer(
    private val handler: DatabaseHandler,
    private val bootstrap: CreatorArchiveBootstrap,
) : BackupRestoreSync {
    override suspend fun begin(): String? {
        bootstrap.awaitReady()
        return handler.await(inTransaction = true) {
            val actor = sync_journalQueries.getActiveActor().executeAsOneOrNull() ?: return@await null
            val id = UUID.randomUUID().toString()
            sync_importQueries.insertImport(
                id,
                actor.space_id,
                actor.generation,
                SyncOrigin.BACKUP_RESTORE.name,
                actor.actor_id,
                actor.epoch,
                actor.next_seq,
            )
            sync_restoreQueries.startRun(id)
            id
        }
    }

    override suspend fun restoreManga(id: String?, sourceId: Long, url: String, restore: suspend () -> Unit) {
        val key = SyncObjectKey(SyncObjectType.MANGA, sourceId = sourceId.toString(), originalUrl = url)
        restoreUnit(id, key.stableKey, {
            // Keep history that was visible before this restore; an old backup cannot undo a local clear.
            val visible = sync_restoreQueries.getMangaHistory(sourceId, url).executeAsList()
                .groupBy { it.chapter_url }
                .mapValues { (_, rows) -> rows.mapNotNull { it.last_read }.maxByOrNull { it.time } ?: Date(0) }
            restore()
            sync_restoreQueries.getMangaHistory(sourceId, url).executeAsList().forEach { chapter ->
                val chapterKey = SyncObjectKey(
                    SyncObjectType.CHAPTER,
                    sourceId = sourceId.toString(),
                    originalUrl = chapter.chapter_url,
                    parentUrl = url,
                )
                if (isSyncHistoryBaselineSuppressed(chapterKey)) {
                    sync_restoreQueries.restoreVisibleHistory(
                        visible[chapter.chapter_url] ?: Date(0),
                        chapter.chapter_id,
                    )
                }
            }
        }) {
            sync_importQueries.freezeFavorites(id!!, sourceId, url)
            sync_importQueries.freezeReading(id, sourceId, url)
        }
    }

    override suspend fun restoreAuthors(id: String?, portableKeys: List<String>, restore: suspend () -> Unit) {
        restoreUnit(id, "authors", { restore() }) {
            portableKeys.distinct().forEach { sync_importQueries.freezeFollows(id!!, it) }
        }
    }

    override suspend fun finish(id: String?, outcome: SyncRestoreOutcome) {
        if (id != null) handler.await { sync_restoreQueries.finishRun(outcome.name, id) }
    }

    private suspend fun restoreUnit(
        id: String?,
        key: String,
        restore: suspend Database.() -> Unit,
        capture: Database.() -> Unit,
    ) {
        handler.await(inTransaction = true) {
            if (id != null && sync_restoreQueries.hasUnit(id, key).executeAsOne() != 0L) return@await
            if (id != null) check(sync_restoreQueries.getRun(id).executeAsOne().state == "RUNNING")
            restore()
            if (id == null) return@await
            val import = sync_importQueries.getImport(id).executeAsOne()
            val actor = sync_journalQueries.getActiveActor().executeAsOneOrNull()
            if (actor?.space_id == import.space_id && actor.generation == import.generation) {
                val before = sync_importQueries.countImportEntries(id).executeAsOne()
                capture()
                val added = sync_importQueries.countImportEntries(id).executeAsOne() - before
                sync_importQueries.incrementImportTotal(added, id)
            }
            sync_restoreQueries.recordUnit(id, key)
        }
    }
}
