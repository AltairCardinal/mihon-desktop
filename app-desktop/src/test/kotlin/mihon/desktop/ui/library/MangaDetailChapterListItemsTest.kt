package mihon.desktop.ui.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import kotlinx.coroutines.runBlocking
import mihon.desktop.download.DownloadItem
import mihon.desktop.download.DownloadStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import java.util.Locale

class MangaDetailChapterListItemsTest {

    @Test
    fun `missing chapter count text is Chinese`() {
        val locale = Locale.forLanguageTag("zh-CN")
        assertEquals("缺少 1 话", missingChapterCountText(1, locale))
        assertEquals("缺少 5 话", missingChapterCountText(5, locale))
    }

    @Test
    fun `failed partial download is presented as retryable error instead of active progress`() {
        val failed = DownloadItem(
            sourceId = 3L,
            mangaTitle = "Manga",
            chapterName = "Chapter",
            chapterId = 7L,
            status = DownloadStatus.ERROR,
            progress = 90,
            pageUrls = List(92) { index -> "https://fixture.invalid/$index.jpg" },
        )

        assertEquals(ChapterDownloadStatus.ERROR, chapterDownloadStatus(failed, isDownloaded = false))
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `failed chapter row exposes a retry action and invokes only retry`() = runBlocking {
        var retryCalls = 0
        var downloadCalls = 0
        var cancelCalls = 0
        val scene = ImageComposeScene(900, 180, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                MaterialTheme {
                    ChapterRow(
                        chapter = Chapter.create().copy(id = 7L, mangaId = 1L, name = "Chapter"),
                        title = "Chapter",
                        downloadStatus = ChapterDownloadStatus.ERROR,
                        downloadProgress = 90f / 92f,
                        onDownload = { downloadCalls++ },
                        onDeleteDownload = {},
                        onCancelDownload = { cancelCalls++ },
                        onRetryDownload = { retryCalls++ },
                        onToggleBookmark = {},
                        onRead = {},
                    )
                }
            }
            scene.render()

            val description = MR.strings.desktop_ui_download_retry_error.localized()
            val retry = nodes(scene).single { node ->
                node.config.contains(SemanticsActions.OnClick) &&
                    node.config.contains(SemanticsProperties.ContentDescription) &&
                    description in node.config[SemanticsProperties.ContentDescription]
            }
            assertTrue(requireNotNull(retry.config[SemanticsActions.OnClick].action).invoke())
            assertEquals(1, retryCalls)
            assertEquals(0, downloadCalls)
            assertEquals(0, cancelCalls)
        } finally {
            scene.close()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { owner -> flatten(owner.rootSemanticsNode) }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
