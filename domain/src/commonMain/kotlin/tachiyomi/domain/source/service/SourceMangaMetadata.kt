package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.guardSourceOperation
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate

/** SOURCE metadata policy; cover file/cache operations stay in each platform adapter. */
fun sourceMangaMetadata(
    stored: Manga,
    remote: SManga,
    updateFavoriteTitles: Boolean,
    coverLastModified: Long? = null,
): MangaUpdate = sourceMangaMetadata(
    stored,
    remote,
    updateFavoriteTitles,
    coverLastModified,
    SourceOperationPhase.DETAILS,
)

fun sourceMangaMetadata(
    stored: Manga,
    remote: SManga,
    updateFavoriteTitles: Boolean,
    coverLastModified: Long?,
    phase: SourceOperationPhase,
): MangaUpdate {
    val title = try {
        guardSourceOperation(phase) { remote.title }.takeIf {
            it.isNotEmpty() &&
                (!stored.favorite || updateFavoriteTitles)
        }
    } catch (_: UninitializedPropertyAccessException) {
        null
    }
    return MangaUpdate(
        id = stored.id,
        title = title,
        coverLastModified = coverLastModified,
        author = guardSourceOperation(phase) { remote.author },
        artist = guardSourceOperation(phase) { remote.artist },
        description = guardSourceOperation(phase) { remote.description },
        genre = guardSourceOperation(phase) { remote.getGenres() },
        thumbnailUrl = guardSourceOperation(phase) { remote.thumbnail_url }?.takeIf(String::isNotEmpty),
        status = guardSourceOperation(phase) { remote.status }.toLong(),
        updateStrategy = guardSourceOperation(phase) { remote.update_strategy },
        initialized = true,
        memo = guardSourceOperation(phase) { remote.memo },
    )
}
