package mihon.data.sync.journal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffect
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncEffectRef
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncFieldKey
import mihon.domain.sync.SyncMetadataPolicy
import mihon.domain.sync.SyncMutationContext
import mihon.domain.sync.SyncObjectDescriptor
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.transport.SyncRepository
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.Sync_import_entries
import tachiyomi.data.chapter.loadChapterPairing
import tachiyomi.domain.creator.repository.CreatorArchiveBootstrap
import java.util.UUID

data class SyncImportProgress(
    val total: Long,
    val remaining: Long,
    val committedCount: Long,
    val lastCommittedEntryId: Long?,
)

/** A frozen local baseline is materialized separately from subsequent explicit user commands. */
class SyncBaselineStore(private val handler: DatabaseHandler, private val bootstrap: CreatorArchiveBootstrap) {
    suspend fun connectAndImport(
        spaceId: String,
        generation: Long,
        repository: SyncRepository,
        actorId: String,
        epoch: Long,
    ): String {
        bootstrap.awaitReady()
        return handler.await(inTransaction = true) {
            SyncLocalJournal(handler).connect(spaceId, generation, repository, actorId, epoch)
            val existing = sync_importQueries.getInitialImport(spaceId, generation).executeAsOneOrNull()
            if (existing != null) {
                ensureMetadataBaseline(spaceId, generation)
                return@await existing.import_id
            }
            val actor = sync_journalQueries.getActiveActor().executeAsOne()
            val id = UUID.randomUUID().toString()
            sync_importQueries.insertImport(
                id,
                spaceId,
                generation,
                SyncOrigin.INITIAL_IMPORT.name,
                actor.actor_id,
                actor.epoch,
                actor.next_seq,
            )
            sync_importQueries.freezeFavorites(id, null, null)
            sync_importQueries.freezeFollows(id, null)
            sync_importQueries.freezeReading(id, null, null)
            freezeMetadataSnapshots(id)
            sync_importQueries.markMetadataImport(spaceId, generation)
            sync_importQueries.setImportTotal(id)
            id
        }
    }

    /** Activation and the current-state baseline commit together; the durable intent id is the idempotency key. */
    internal suspend fun switchAndImport(
        importId: String,
        oldSpaceId: String,
        oldGeneration: Long,
        spaceId: String,
        generation: Long,
        repository: SyncRepository,
        actorId: String,
        epoch: Long,
    ) {
        bootstrap.awaitReady()
        handler.await(inTransaction = true) {
            val existing = sync_importQueries.getImport(importId).executeAsOneOrNull()
            val active = sync_journalQueries.getActiveSpace().executeAsOneOrNull()
            if (existing != null) {
                require(existing.space_id == spaceId && existing.generation == generation)
                require(active?.space_id == spaceId && active.generation == generation)
                return@await
            }
            require(active?.space_id == oldSpaceId && active.generation == oldGeneration) {
                "sync active space changed"
            }
            val origin = if (sync_importQueries.getInitialImport(spaceId, generation).executeAsOneOrNull() == null) {
                SyncOrigin.INITIAL_IMPORT
            } else {
                SyncOrigin.BACKUP_RESTORE
            }
            SyncLocalJournal(handler).connect(spaceId, generation, repository, actorId, epoch)
            val actor = sync_journalQueries.getActiveActor().executeAsOne()
            sync_importQueries.insertImport(
                importId,
                spaceId,
                generation,
                origin.name,
                actor.actor_id,
                actor.epoch,
                actor.next_seq,
            )
            sync_importQueries.freezeFavorites(importId, null, null)
            sync_importQueries.freezeFollows(importId, null)
            sync_importQueries.freezeReading(importId, null, null)
            freezeMetadataSnapshots(importId)
            if (!sync_importQueries.hasMetadataImport(spaceId, generation).executeAsOne()) {
                sync_importQueries.markMetadataImport(spaceId, generation)
            }
            sync_importQueries.setImportTotal(importId)
        }
    }

    /** Upgrade already-connected spaces before the ordinary exchange drains its durable import queue. */
    suspend fun ensureMetadataBaseline(spaceId: String, generation: Long) = handler.await(inTransaction = true) {
        if (sync_importQueries.hasMetadataImport(spaceId, generation).executeAsOne()) return@await
        val actor = sync_journalQueries.getActiveActor().executeAsOneOrNull() ?: return@await
        require(actor.space_id == spaceId && actor.generation == generation)
        val existing = sync_importQueries.getInitialImport(spaceId, generation).executeAsOneOrNull()
        val importId = existing?.import_id ?: UUID.randomUUID().toString().also {
            sync_importQueries.insertImport(
                it,
                spaceId,
                generation,
                SyncOrigin.INITIAL_IMPORT.name,
                actor.actor_id,
                actor.epoch,
                actor.next_seq,
            )
        }
        val before = sync_importQueries.countImportEntries(importId).executeAsOne()
        freezeMetadataSnapshots(importId)
        val added = sync_importQueries.countImportEntries(importId).executeAsOne() - before
        sync_importQueries.incrementImportTotal(added, importId)
        sync_importQueries.markMetadataImport(spaceId, generation)
    }

    suspend fun process(importId: String, limit: Int = 50): SyncImportProgress {
        require(limit in 1..256)
        return handler.await(inTransaction = true) {
            val import = sync_importQueries.getImport(importId).executeAsOne()
            require(sync_journalQueries.getSpace(import.space_id, import.generation).executeAsOne().active) {
                "import space is inactive"
            }
            // Read one look-ahead row so a full final page can still report exact completion
            // without counting the entire remaining import queue.
            val page = sync_importQueries.getImportEntries(importId, limit.toLong() + 1L).executeAsList()
            val committedPage = page.take(limit)
            committedPage.forEach { row ->
                val material = row.material()
                val parents = material.effects.associate { effect ->
                    SyncFieldKey(effect.objectKey, effect.field) to sync_importQueries.getImportHead(
                        importId,
                        effect.objectKey.stableKey,
                        effect.field.name,
                    ).executeAsOneOrNull()?.let { listOf(Json.decodeFromString<SyncEffectRef>(it)) }.orEmpty()
                }
                val event = requireNotNull(
                    appendSyncOperation(
                        SyncMutationContext(
                            SyncOrigin.valueOf(import.origin),
                            importId = importId,
                            observedHeads = parents,
                        ),
                        SyncCategory.valueOf(row.category),
                        material.effects,
                        row.read_at.coerceAtLeast(0),
                        frozenDescriptions = material.objects,
                    ),
                )
                event.effects.forEach { effect ->
                    sync_importQueries.setImportHead(
                        importId,
                        effect.objectKey.stableKey,
                        effect.field.name,
                        Json.encodeToString(effect.ref(event)),
                    )
                }
                sync_importQueries.removeImportEntry(row.id)
            }
            // A full chunk proves that work remains without scanning the entire queue. Only the
            // final short chunk performs the exact count needed for UI completion.
            val remaining = if (page.size > limit) 1L else 0L
            SyncImportProgress(import.total, remaining, committedPage.size.toLong(), committedPage.lastOrNull()?.id)
        }
    }
}

private data class ImportMaterial(val effects: List<SyncEffect>, val objects: List<SyncObjectDescriptor>)

private fun Sync_import_entries.material(): ImportMaterial {
    fun label(value: String, fallback: String) = value.trim().take(4096).ifBlank { fallback }
    if (category == SyncCategory.FOLLOW.name) {
        val key = SyncObjectKey(SyncObjectType.AUTHOR, portableKey = requireNotNull(portable_key))
        return ImportMaterial(
            listOf(SyncEffect("follow", key, SyncField.FOLLOWING, SyncEffectKind.ADD)),
            listOf(SyncObjectDescriptor(key, label(title, portable_key))),
        )
    }
    val manga = SyncObjectKey(
        SyncObjectType.MANGA,
        sourceId = requireNotNull(source_id).toString(),
        originalUrl =
        parent_url ?: object_url,
    )
    val mangaDescription = SyncObjectDescriptor(
        manga,
        label(parent_title ?: title, requireNotNull(manga.originalUrl)),
        author?.take(4096),
        artist?.take(4096),
        thumbnail?.take(8192),
    )
    if (category == SyncCategory.FAVORITE.name) {
        return ImportMaterial(
            listOf(
                if (snapshot_field == SyncField.MANGA_CATEGORIES.name) {
                    SyncEffect(
                        "categories",
                        manga,
                        SyncField.MANGA_CATEGORIES,
                        SyncEffectKind.SET_CATEGORIES,
                        payload = Json.decodeFromString<JsonObject>(requireNotNull(snapshot_payload)),
                    )
                } else {
                    SyncEffect("favorite", manga, SyncField.FAVORITE, SyncEffectKind.ADD)
                },
            ),
            listOf(mangaDescription),
        )
    }
    val chapter =
        SyncObjectKey(
            SyncObjectType.CHAPTER,
            sourceId = manga.sourceId,
            originalUrl = object_url,
            parentUrl = manga.originalUrl,
        )
    val effects = buildList {
        if (snapshot_field == SyncField.CHAPTER_PAIRING.name) {
            add(
                SyncEffect(
                    "pairing",
                    chapter,
                    SyncField.CHAPTER_PAIRING,
                    SyncEffectKind.SET_PAIRING,
                    payload = Json.decodeFromString<JsonObject>(requireNotNull(snapshot_payload)),
                ),
            )
        }
        if (is_read != 0L) add(SyncEffect("read", chapter, SyncField.READ_STATUS, SyncEffectKind.MARK_READ))
        if (page_index > 0 || read_at > 0) {
            add(
                SyncEffect(
                    "position",
                    manga,
                    SyncField.RESUME_POSITION,
                    SyncEffectKind.RESUME_POSITION,
                    payload = buildJsonObject {
                        put("chapterKey", chapter.stableKey)
                        put("pageIndex", page_index.coerceAtLeast(0))
                        put("totalPages", 0)
                    },
                ),
            )
        }
        if (read_at > 0) {
            add(
                SyncEffect(
                    "summary",
                    manga,
                    SyncField.READING_SUMMARY,
                    SyncEffectKind.READING_SUMMARY,
                    payload = buildJsonObject {
                        put("chapterKey", chapter.stableKey)
                        put("readAt", read_at)
                    },
                ),
            )
        }
    }
    return ImportMaterial(
        effects,
        listOf(
            mangaDescription,
            SyncObjectDescriptor(
                chapter,
                label(title, requireNotNull(object_url)),
                chapterNumber = chapter_number?.takeIf { it.isFinite() },
                sourceOrder = source_order,
                scanlator = scanlator?.take(4096),
            ),
        ),
    )
}

/** Freeze bounded pages in the caller's transaction. Ordinary absence is never a clear intent. */
internal fun Database.freezeMetadataSnapshots(importId: String, sourceId: Long? = null, mangaUrl: String? = null) {
    var afterId = 0L
    while (true) {
        val ids = sync_importQueries.getCategorySnapshotMangas(afterId, sourceId, mangaUrl, 256).executeAsList()
        if (ids.isEmpty()) break
        ids.forEach { mangaId ->
            val effect = categorySnapshotEffect(mangaId)
            SyncMetadataPolicy.categoryNames(effect.payload)
            sync_importQueries.freezeCategorySnapshot(importId, effect.payload.toString(), mangaId)
        }
        afterId = ids.last()
    }
    afterId = 0L
    while (true) {
        val chapters = sync_importQueries.getPairingSnapshotChapters(afterId, sourceId, mangaUrl, 256).executeAsList()
        if (chapters.isEmpty()) break
        chapters.forEach { chapter ->
            val record = loadChapterPairing(chapter._id, chapter.manga_id).record ?: return@forEach
            if (!record.isValidFor(record.pageCount)) return@forEach
            val (key, _) = readingObjectKeys(chapter._id)
            sync_importQueries.freezePairingSnapshot(
                importId,
                requireNotNull(key.originalUrl),
                SyncMetadataPolicy.pairingPayload(record).toString(),
                chapter._id,
            )
        }
        afterId = chapters.last()._id
    }
}
