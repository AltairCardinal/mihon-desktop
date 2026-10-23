package mihon.desktop.ui.library

import coil3.ImageLoader
import coil3.PlatformContext
import tachiyomi.domain.library.model.LibraryManga

/**
 * Loads the covers of the library items below the visible ones.
 *
 * While the user scrolls, the grid would otherwise fill in one cover at a time, because
 * every cover is read on its own. Loading the covers of the next screens ahead of time
 * means they are already decoded when the grid reaches them.
 *
 * The prefetch follows the items of the library screen: the current category and the
 * current order. The requests carry the same memory cache key as the grid, so a cover
 * that the grid asks for later is reused instead of being loaded a second time.
 */
internal object MangaCoverPrefetcher {

    /** How many covers are loaded ahead of the visible ones. */
    const val PREFETCH_COUNT = 60

    /**
     * Loads the covers of the given items and returns how many were requested.
     *
     * The items without a cover are skipped. The requests never fail the screen: the
     * image loader reports a missing cover on its own.
     */
    fun prefetch(
        items: List<LibraryManga>,
        resolveCoverModel: (Long, String?) -> String?,
        imageLoader: ImageLoader,
        platformContext: PlatformContext,
        count: Int = PREFETCH_COUNT,
    ): Int {
        var requested = 0
        for (item in items.take(count)) {
            val coverModel = resolveCoverModel(item.id, item.manga.thumbnailUrl) ?: continue
            val request = buildMangaCoverRequest(
                platformContext = platformContext,
                mangaId = item.id,
                sourceId = item.manga.source,
                coverModel = coverModel,
                coverVersion = item.manga.coverLastModified,
            )
            imageLoader.enqueue(request)
            requested++
        }
        return requested
    }
}
