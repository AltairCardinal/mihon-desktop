package mihon.desktop.ui.download

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.download.DownloadItem
import mihon.desktop.download.DownloadQueueScreenModel
import mihon.desktop.download.projectDownloadQueueSourceGroups
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.desktop.source.FakeSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import java.io.File

class DownloadQueueSourceGroupingWiringTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `production model initializes without a Main dispatcher`() {
        val manager = DesktopDownloadManager(DesktopDownloadProvider(tempDir))
        lateinit var model: DownloadQueueScreenModel
        assertDoesNotThrow {
            model = DownloadQueueScreenModel(
                manager,
                mockk(relaxed = true),
                FakeDesktopSourceManager(emptyList()),
            )
        }

        assertEquals(emptyList<DownloadItem>(), model.state.value.queue)
        model.onDispose()
    }

    @Test
    fun `persistent source ids remain one group when the source instance is replaced`() {
        val queue = listOf(
            download(sourceId = 1, mangaTitle = "Manga A", chapterId = 1),
            download(sourceId = 1, mangaTitle = "Manga B", chapterId = 2),
        )

        val beforeReplacement = projectDownloadQueueSourceGroups(queue) { "Old source" }
        val afterReplacement = projectDownloadQueueSourceGroups(queue) { "Replacement source" }

        assertEquals(1, beforeReplacement.size)
        assertEquals(listOf(1L, 2L), beforeReplacement.single().items.map(DownloadItem::chapterId))
        assertEquals(1, afterReplacement.size)
        assertEquals("Replacement source", afterReplacement.single().sourceName)
    }

    @Test
    fun `missing source has stable fallback without splitting persisted rows`() {
        val queue = listOf(
            download(sourceId = 9, mangaTitle = "Manga A", chapterId = 1),
            download(sourceId = 9, mangaTitle = "Manga B", chapterId = 2),
        )

        val groups = projectDownloadQueueSourceGroups(queue) { null }

        assertEquals(1, groups.size)
        assertEquals("Unknown source (9)", groups.single().sourceName)
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `queue renders one header per source with source names and a stable missing-source fallback`() = runBlocking {
        val manager = DesktopDownloadManager(DesktopDownloadProvider(tempDir))
        manager.enqueue(download(sourceId = 1, mangaTitle = "Manga A", chapterId = 1))
        manager.enqueue(download(sourceId = 1, mangaTitle = "Manga B", chapterId = 2))
        manager.enqueue(download(sourceId = 2, mangaTitle = "Manga C", chapterId = 3))
        manager.enqueue(download(sourceId = 3, mangaTitle = "Manga D", chapterId = 4))
        val canonicalChapters = mockk<ChapterRepository> {
            coEvery { getChapterById(1) } returns chapter(id = 1, dateUpload = 100)
            coEvery { getChapterById(2) } returns chapter(id = 2, dateUpload = 200)
            coEvery { getChapterById(3) } returns chapter(id = 3, dateUpload = 300)
            coEvery { getChapterById(4) } returns chapter(id = 4, dateUpload = 400)
        }
        val sourceManager = FakeDesktopSourceManager(
            listOf(
                FakeSource(1, "en", "Source One"),
                FakeSource(2, "ja", "Source Two"),
            ),
        )
        val model = DownloadQueueScreenModel(manager, canonicalChapters, sourceManager, this)
        val dependencies = mockk<DesktopUiDependencies> {
            every { createDownloadQueueScreenModel() } returns model
        }
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}

        scene.setContent {
            CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                Navigator(DownloadQueueScreen()) { CurrentScreen() }
            }
        }
        scene.render()
        verify(exactly = 1) { dependencies.createDownloadQueueScreenModel() }

        val nodes = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

        assertEquals(1, textNodeCount(nodes, "Source One"))
        assertEquals(1, textNodeCount(nodes, "Source Two"))
        assertEquals(1, textNodeCount(nodes, "Unknown source (3)"))
        assertEquals(0, textNodeCount(nodes, "Manga A"))
        assertEquals(0, textNodeCount(nodes, "Manga B"))
        assertEquals(0, textNodeCount(nodes, "Manga C"))
        assertEquals(0, textNodeCount(nodes, "Manga D"))

        val sortAction = nodes.first {
            it.config.contains(SemanticsActions.OnClick) && it.config.toString().contains("Sort")
        }
        assertTrue(requireNotNull(sortAction.config[SemanticsActions.OnClick].action).invoke())
        scene.render()
        val menuNodes = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
        assertEquals(1, textNodeCount(menuNodes, "Upload date \u2014 Newest"))
        assertEquals(1, textNodeCount(menuNodes, "Upload date \u2014 Oldest"))
        assertEquals(1, textNodeCount(menuNodes, "Chapter number \u2014 Ascending"))
        assertEquals(1, textNodeCount(menuNodes, "Chapter number \u2014 Descending"))
        val newestAction = menuNodes.first {
            it.config.contains(SemanticsActions.OnClick) && it.config.toString().contains("Upload date \u2014 Newest")
        }
        assertTrue(requireNotNull(newestAction.config[SemanticsActions.OnClick].action).invoke())
        withTimeout(2_000) {
            while (manager.queue.value.map { it.chapterId } != listOf(2L, 1L, 3L, 4L)) {
                scene.render()
                yield()
            }
        }
        assertEquals(listOf(2L, 1L, 3L, 4L), manager.queue.value.map { it.chapterId })

        scene.close()
    }

    private fun download(sourceId: Long, mangaTitle: String, chapterId: Long) = DownloadItem(
        sourceId = sourceId,
        mangaTitle = mangaTitle,
        chapterName = "Chapter $chapterId",
        chapterId = chapterId,
    )

    private fun chapter(id: Long, dateUpload: Long) = Chapter.create().copy(id = id, dateUpload = dateUpload)

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun textNodeCount(nodes: List<SemanticsNode>, label: String): Int = nodes.count { node ->
        node.config.contains(SemanticsProperties.Text) &&
            node.config[SemanticsProperties.Text].any { it.text == label }
    }
}
