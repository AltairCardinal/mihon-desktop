package mihon.domain.manga.model

import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.guardSourceOperation
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.manga.model.Manga

fun SManga.toDomainManga(sourceId: Long): Manga = toDomainManga(sourceId, SourceOperationPhase.METADATA)

/** Read extension-owned fields before constructing the host value; no storage belongs in this guard. */
fun SManga.toDomainManga(sourceId: Long, phase: SourceOperationPhase): Manga {
    val url = guardSourceOperation(phase) { this.url }
    val title = guardSourceOperation(phase) { this.title }
    val artist = guardSourceOperation(phase) { this.artist }
    val author = guardSourceOperation(phase) { this.author }
    val description = guardSourceOperation(phase) { this.description }
    val genre = guardSourceOperation(phase) { getGenres() }
    val status = guardSourceOperation(phase) { this.status }
    val thumbnailUrl = guardSourceOperation(phase) { this.thumbnail_url }
    val updateStrategy = guardSourceOperation(phase) { this.update_strategy }
    val initialized = guardSourceOperation(phase) { this.initialized }
    val memo = guardSourceOperation(phase) { this.memo }
    return Manga.create().copy(
        url = url,
        title = title,
        artist = artist,
        author = author,
        description = description,
        genre = genre,
        status = status.toLong(),
        thumbnailUrl = thumbnailUrl,
        updateStrategy = updateStrategy,
        initialized = initialized,
        memo = memo,
        source = sourceId,
    )
}
