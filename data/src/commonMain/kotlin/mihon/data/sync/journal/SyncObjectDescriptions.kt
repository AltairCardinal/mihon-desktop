package mihon.data.sync.journal

import kotlinx.serialization.json.Json
import mihon.domain.sync.SyncObjectDescriptor
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import tachiyomi.data.Database

internal object SyncObjectDescriptions {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }
    fun encode(objects: List<SyncObjectDescriptor>): String = json.encodeToString(objects)
    fun decode(encoded: String): List<SyncObjectDescriptor> = json.decodeFromString(encoded)

    // Empty descriptions retain the pre-descriptor wire format; a nonempty field also adds ,"objects":.
    fun batchBytes(encoded: String): Int = if (encoded == "[]") 0 else encoded.encodeToByteArray().size + 11
}

/** Called inside the operation transaction, before source refresh or local deletion can change descriptions. */
internal fun Database.describeSyncObjects(keys: List<SyncObjectKey>): List<SyncObjectDescriptor> {
    val completeKeys = buildSet {
        keys.forEach { key ->
            if (key.type == SyncObjectType.CHAPTER) {
                add(SyncObjectKey(SyncObjectType.MANGA, sourceId = key.sourceId, originalUrl = key.parentUrl))
            }
            add(key)
        }
    }
    return completeKeys.map { key ->
        when (key.type) {
            SyncObjectType.MANGA -> {
                val manga = sync_journalQueries.getSyncMangaDescription(
                    requireNotNull(key.originalUrl),
                    requireNotNull(key.sourceId).toLong(),
                ).executeAsOne()
                SyncObjectDescriptor(
                    key,
                    displayLabel(manga.title, requireNotNull(key.originalUrl)),
                    manga.author?.take(4096),
                    manga.artist?.take(4096),
                    manga.thumbnail_url?.take(8192),
                )
            }
            SyncObjectType.CHAPTER -> {
                val chapter = sync_journalQueries.getSyncChapterDescription(
                    requireNotNull(key.originalUrl),
                    requireNotNull(key.parentUrl),
                    requireNotNull(key.sourceId).toLong(),
                ).executeAsOne()
                SyncObjectDescriptor(
                    key,
                    displayLabel(chapter.name, requireNotNull(key.originalUrl)),
                    chapterNumber = chapter.chapter_number.takeIf { it.isFinite() },
                    sourceOrder = chapter.source_order,
                    scanlator = chapter.scanlator?.take(4096),
                )
            }
            SyncObjectType.AUTHOR -> {
                val id = author_archiveQueries.getArchiveCreatorIdByPortableKey(requireNotNull(key.portableKey))
                    .executeAsOne()
                val author = author_archiveQueries.getArchiveCreatorIdentityRecord(id).executeAsOne()
                SyncObjectDescriptor(key, displayLabel(author.display_name, requireNotNull(key.portableKey)))
            }
        }
    }
}

private fun displayLabel(value: String, fallback: String): String = value.trim().take(4096).ifBlank { fallback }
