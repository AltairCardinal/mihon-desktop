package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate

/** SOURCE metadata policy; cover file/cache operations stay in each platform adapter. */
fun sourceMangaMetadata(
    stored: Manga,
    remote: SManga,
    updateFavoriteTitles: Boolean,
    coverLastModified: Long? = null,
): MangaUpdate {
    val title = try {
        remote.title.takeIf { it.isNotEmpty() && (!stored.favorite || updateFavoriteTitles) }
    } catch (_: UninitializedPropertyAccessException) {
        null
    }
    return MangaUpdate(
        id = stored.id,
        title = title,
        coverLastModified = coverLastModified,
        author = remote.author,
        artist = remote.artist,
        description = remote.description,
        genre = remote.getGenres(),
        thumbnailUrl = remote.thumbnail_url?.takeIf(String::isNotEmpty),
        status = remote.status.toLong(),
        updateStrategy = remote.update_strategy,
        initialized = true,
        memo = remote.memo,
    )
}
