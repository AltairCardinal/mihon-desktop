package mihon.desktop.ui.library

import coil3.EventListener
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import java.util.Collections

/**
 * The covers of the items below the visible ones are loaded ahead of time, so scrolling
 * does not fill the grid in one cover at a time. The prefetch has to use the very same
 * request as the grid, otherwise the grid would load every cover a second time.
 */
class MangaCoverPrefetcherTest {

    @Test
    fun `the prefetch requests the covers with the same cache key as the grid`() = runBlocking {
        val recorded = recordedRequests()
        val item = libraryManga(id = 42L, thumbnailUrl = "https://example.invalid/42.jpg")

        val requested = MangaCoverPrefetcher.prefetch(
            items = listOf(item),
            resolveCoverModel = { _, url -> url },
            imageLoader = recorded.loader,
            platformContext = PlatformContext.INSTANCE,
        )
        delay(300)

        assertEquals(1, requested)
        val keys = recorded.requests.mapNotNull { it.memoryCacheKey }
        assertTrue(
            keys.contains(mangaCoverRequestKey(42L, "https://example.invalid/42.jpg", 0L)),
            "the prefetch must use the cache key of the grid: $keys",
        )
    }

    @Test
    fun `an item without a cover is skipped`() = runBlocking {
        val recorded = recordedRequests()

        val requested = MangaCoverPrefetcher.prefetch(
            items = listOf(libraryManga(id = 9L, thumbnailUrl = null)),
            resolveCoverModel = { _, url -> url },
            imageLoader = recorded.loader,
            platformContext = PlatformContext.INSTANCE,
        )
        delay(300)

        assertEquals(0, requested)
        assertTrue(recorded.requests.isEmpty(), "no request must be issued: ${recorded.requests.map { it.memoryCacheKey }}")
    }

    @Test
    fun `the prefetch only loads the requested number of covers`() = runBlocking {
        val recorded = recordedRequests()
        val items = (1L..10L).map { libraryManga(id = it, thumbnailUrl = "https://example.invalid/$it.jpg") }

        val requested = MangaCoverPrefetcher.prefetch(
            items = items,
            resolveCoverModel = { _, url -> url },
            imageLoader = recorded.loader,
            platformContext = PlatformContext.INSTANCE,
            count = 3,
        )
        delay(300)

        assertEquals(3, requested)
    }

    private class RecordedRequests(
        val loader: ImageLoader,
        val requests: MutableList<ImageRequest>,
    )

    private fun recordedRequests(): RecordedRequests {
        val requests = Collections.synchronizedList(mutableListOf<ImageRequest>())
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE)
            .mainCoroutineContext { Dispatchers.Unconfined }
            .eventListener(
                object : EventListener() {
                    override fun onStart(request: ImageRequest) {
                        requests += request
                    }
                },
            )
            .build()
        return RecordedRequests(loader, requests)
    }

    private fun libraryManga(id: Long, thumbnailUrl: String?): LibraryManga {
        val manga = Manga.create().copy(
            id = id,
            title = "Manga $id",
            thumbnailUrl = thumbnailUrl,
            source = 1L,
        )
        return LibraryManga(
            manga = manga,
            categories = emptyList(),
            totalChapters = 0,
            readCount = 0,
            bookmarkCount = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = 0,
        )
    }
}
