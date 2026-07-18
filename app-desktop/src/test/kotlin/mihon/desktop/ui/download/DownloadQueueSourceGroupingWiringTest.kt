package mihon.desktop.ui.download

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.download.DownloadItem
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.desktop.source.FakeSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DownloadQueueSourceGroupingWiringTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `queue renders one header per source with source names and a stable missing-source fallback`() = runBlocking {
        val manager = DesktopDownloadManager(DesktopDownloadProvider(tempDir))
        manager.enqueue(download(sourceId = 1, mangaTitle = "Manga A", chapterId = 1))
        manager.enqueue(download(sourceId = 1, mangaTitle = "Manga B", chapterId = 2))
        manager.enqueue(download(sourceId = 2, mangaTitle = "Manga C", chapterId = 3))
        manager.enqueue(download(sourceId = 3, mangaTitle = "Manga D", chapterId = 4))
        val dependencies = mockk<DesktopUiDependencies> {
            every { downloadManager } returns manager
            every { sourceManager } returns FakeDesktopSourceManager(
                listOf(
                    FakeSource(1, "en", "Source One"),
                    FakeSource(2, "ja", "Source Two"),
                ),
            )
        }
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}

        scene.setContent {
            CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                Navigator(DownloadQueueScreen()) { CurrentScreen() }
            }
        }
        scene.render()

        val nodes = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

        assertEquals(1, textNodeCount(nodes, "Source One"))
        assertEquals(1, textNodeCount(nodes, "Source Two"))
        assertEquals(1, textNodeCount(nodes, "Unknown source (3)"))
        assertEquals(0, textNodeCount(nodes, "Manga A"))
        assertEquals(0, textNodeCount(nodes, "Manga B"))
        assertEquals(0, textNodeCount(nodes, "Manga C"))
        assertEquals(0, textNodeCount(nodes, "Manga D"))

        scene.close()
    }

    private fun download(sourceId: Long, mangaTitle: String, chapterId: Long) = DownloadItem(
        sourceId = sourceId,
        mangaTitle = mangaTitle,
        chapterName = "Chapter $chapterId",
        chapterId = chapterId,
    )

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun textNodeCount(nodes: List<SemanticsNode>, label: String): Int = nodes.count { node ->
        node.config.contains(SemanticsProperties.Text) &&
            node.config[SemanticsProperties.Text].any { it.text == label }
    }
}
