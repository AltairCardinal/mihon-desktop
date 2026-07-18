package mihon.desktop.ui.download

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

        val semantics = scene.semanticsOwners
            .flatMap { flatten(it.rootSemanticsNode) }
            .joinToString { it.config.toString() }

        assertTrue(semantics.contains("Source One"))
        assertTrue(semantics.contains("Source Two"))
        assertTrue(semantics.contains("Unknown source (3)"))
        assertFalse(semantics.contains("Manga A"))
        assertFalse(semantics.contains("Manga B"))
        assertFalse(semantics.contains("Manga C"))
        assertFalse(semantics.contains("Manga D"))

        scene.close()
    }

    private fun download(sourceId: Long, mangaTitle: String, chapterId: Long) = DownloadItem(
        sourceId = sourceId,
        mangaTitle = mangaTitle,
        chapterName = "Chapter $chapterId",
        chapterId = chapterId,
    )

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
