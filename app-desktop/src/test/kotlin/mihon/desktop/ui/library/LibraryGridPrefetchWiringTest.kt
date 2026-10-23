package mihon.desktop.ui.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import coil3.EventListener
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The library grid loads the covers of the items below the visible ones ahead of time,
 * so scrolling does not fill the grid in one cover at a time.
 *
 * The grid only builds the cards of the visible items, so a request for an item far
 * below the fold can only come from the prefetch. These tests fail when the prefetch
 * is no longer wired into the grid or when its window does not follow the scroll.
 */
class LibraryGridPrefetchWiringTest {

    private companion object {
        private val TITLE = Regex("Work (\\d+)")
    }

    @Test
    fun `the grid loads the covers of the items below the visible ones`() = runBlocking {
        val requests = CopyOnWriteArrayList<ImageRequest>()
        val loader = recordingLoader(requests)
        val original = SingletonImageLoader.get(PlatformContext.INSTANCE)
        SingletonImageLoader.setUnsafe(loader)
        try {
            val items = (1L..30L).map { libraryManga(it) }
            val scene = ImageComposeScene(1200, 900, coroutineContext = Dispatchers.Unconfined) {}
            try {
                scene.setContent {
                    MaterialTheme {
                        LibraryGrid(
                            items = items,
                            minCardWidth = 600.dp,
                            selectionState = LibrarySelectionState(),
                            onContextMenu = {},
                            onItemClick = { _, _ -> },
                            onItemLongClick = {},
                            onContinueReading = {},
                        )
                    }
                }
                render(scene)

                val keys = requests.mapNotNull { it.memoryCacheKey }.toSet()
                val lastItem = items.last()
                assertTrue(
                    keys.contains(mangaCoverRequestKey(lastItem.id, lastItem.manga.thumbnailUrl, 0L)),
                    "the cover of the last item has to be loaded ahead of time: $keys",
                )
                val known = items.map { mangaCoverRequestKey(it.id, it.manga.thumbnailUrl, 0L) }.toSet()
                assertTrue(
                    keys.all { it in known },
                    "the prefetch must only load the covers of the items of the screen: $keys",
                )
            } finally {
                scene.close()
            }
        } finally {
            SingletonImageLoader.setUnsafe(original)
            loader.shutdown()
        }
    }

    @Test
    fun `the window of the prefetch follows the scroll`() = runBlocking {
        val requests = CopyOnWriteArrayList<ImageRequest>()
        val loader = recordingLoader(requests)
        val original = SingletonImageLoader.get(PlatformContext.INSTANCE)
        SingletonImageLoader.setUnsafe(loader)
        try {
            // More items than the prefetch window holds, so a window that does not move
            // leaves the covers of the lower screens unloaded.
            val items = (1L..200L).map { libraryManga(it) }
            val scene = ImageComposeScene(1200, 900, coroutineContext = Dispatchers.Unconfined) {}
            try {
                scene.setContent {
                    MaterialTheme {
                        LibraryGrid(
                            items = items,
                            minCardWidth = 200.dp,
                            selectionState = LibrarySelectionState(),
                            onContextMenu = {},
                            onItemClick = { _, _ -> },
                            onItemLongClick = {},
                            onContinueReading = {},
                        )
                    }
                }
                render(scene)

                val beforeScroll = requests.mapNotNull { it.memoryCacheKey }.toSet()

                scrollTo(scene, 150)
                render(scene)

                // The covers of the items on the screen are loaded by the cards themselves,
                // so only the items below the fold tell whether the prefetch works.
                val composed = composedItemIds(scene)
                val afterScroll = requests.mapNotNull { it.memoryCacheKey }.toSet()
                val belowTheFold = items.filter { it.id > (composed.maxOrNull() ?: 0L) }
                val loadedAfterScroll = belowTheFold
                    .map { mangaCoverRequestKey(it.id, it.manga.thumbnailUrl, 0L) }
                    .filter { it in afterScroll && it !in beforeScroll }
                assertTrue(
                    loadedAfterScroll.isNotEmpty(),
                    "the covers below the fold have to be loaded after the scroll, " +
                        "composed items: $composed",
                )
            } finally {
                scene.close()
            }
        } finally {
            SingletonImageLoader.setUnsafe(original)
            loader.shutdown()
        }
    }

    private fun recordingLoader(requests: MutableList<ImageRequest>): ImageLoader =
        ImageLoader.Builder(PlatformContext.INSTANCE)
            .mainCoroutineContext { Dispatchers.Unconfined }
            .eventListener(
                object : EventListener() {
                    override fun onStart(request: ImageRequest) {
                        requests += request
                    }
                },
            )
            .build()

    private suspend fun render(scene: ImageComposeScene) {
        repeat(20) {
            scene.render(System.nanoTime())
            delay(15)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun scrollTo(scene: ImageComposeScene, index: Int) {
        val node = nodes(scene).first {
            it.config.contains(SemanticsActions.ScrollToIndex)
        }
        requireNotNull(node.config[SemanticsActions.ScrollToIndex].action).invoke(index)
    }

    @OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
    private fun composedItemIds(scene: ImageComposeScene): Set<Long> =
        nodes(scene).flatMap { node ->
            if (!node.config.contains(SemanticsProperties.ContentDescription)) {
                emptyList()
            } else {
                node.config[SemanticsProperties.ContentDescription].mapNotNull { text ->
                    TITLE.find(text)?.groupValues?.get(1)?.toLong()
                }
            }
        }.toSet()

    @OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap { flatten(it) }

    private fun libraryManga(id: Long): LibraryManga {
        val manga = Manga.create().copy(
            id = id,
            title = "Work $id",
            thumbnailUrl = "http://127.0.0.1:1/$id.jpg",
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
