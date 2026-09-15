package mihon.data.sync.journal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffect
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncEffectRef
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncFieldKey
import mihon.domain.sync.SyncMutationContext
import mihon.domain.sync.SyncObjectDescriptor
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.transport.SyncRepository
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.Sync_import_entries
import tachiyomi.domain.creator.repository.CreatorArchiveBootstrap
import java.util.UUID

data class SyncImportProgress(val total: Long, val remaining: Long)

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
            if (existing != null) return@await existing.import_id
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
            sync_importQueries.setImportTotal(id)
            id
        }
    }

    suspend fun process(importId: String, limit: Int = 50): SyncImportProgress {
        require(limit in 1..256)
        return handler.await(inTransaction = true) {
            val import = sync_importQueries.getImport(importId).executeAsOne()
            require(sync_journalQueries.getSpace(import.space_id, import.generation).executeAsOne().active) {
                "import space is inactive"
            }
            sync_importQueries.getImportEntries(importId, limit.toLong()).executeAsList().forEach { row ->
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
            SyncImportProgress(import.total, sync_importQueries.countImportEntries(importId).executeAsOne())
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
            listOf(SyncEffect("favorite", manga, SyncField.FAVORITE, SyncEffectKind.ADD)),
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
