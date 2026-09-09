package mihon.desktop.ui.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
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
    @OptIn(ExperimentalComposeUiApi::class)
    fun `chapter title and row whitespace open the chapter in normal mode`() = runBlocking {
        var readCalls = 0
        var selectCalls = 0
        val scene = ImageComposeScene(900, 180, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                MaterialTheme {
                    ChapterRow(
                        chapter = Chapter.create().copy(id = 7L, mangaId = 1L, name = "Chapter"),
                        title = "Chapter",
                        downloadStatus = ChapterDownloadStatus.NOT_DOWNLOADED,
                        downloadProgress = null,
                        onSelect = { selectCalls++ },
                        onDownload = {},
                        onDeleteDownload = {},
                        onCancelDownload = {},
                        onRetryDownload = {},
                        onToggleBookmark = {},
                        onRead = { readCalls++ },
                    )
                }
            }
            scene.render()

            val title = nodes(scene).first { node -> node.hasText("Chapter") }
            // Click inside the headline at the row's start; merged semantics can span the whole row.
            tap(scene, Offset(32f, title.boundsInRoot.center.y))
            tap(scene, Offset(450f, title.boundsInRoot.center.y))

            assertEquals(2, readCalls)
            assertEquals(0, selectCalls)
            assertTrue(
                nodes(scene).none { node ->
                    node.config.contains(SemanticsProperties.Role) &&
                        node.config[SemanticsProperties.Role] == Role.Checkbox
                },
            )
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `selected chapter row click toggles selection instead of opening`() = runBlocking {
        var readCalls = 0
        var selectCalls = 0
        val scene = ImageComposeScene(900, 180, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                MaterialTheme {
                    ChapterRow(
                        chapter = Chapter.create().copy(id = 8L, mangaId = 1L, name = "Selected chapter"),
                        title = "Selected chapter",
                        downloadStatus = ChapterDownloadStatus.NOT_DOWNLOADED,
                        downloadProgress = null,
                        isSelected = true,
                        onSelect = { selectCalls++ },
                        onDownload = {},
                        onDeleteDownload = {},
                        onCancelDownload = {},
                        onRetryDownload = {},
                        onToggleBookmark = {},
                        onRead = { readCalls++ },
                    )
                }
            }
            scene.render()

            val title = nodes(scene).first { node -> node.hasText("Selected chapter") }
            tap(scene, title.boundsInRoot.center)

            assertEquals(0, readCalls)
            assertEquals(1, selectCalls)
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `chapter long press enters selection without opening`() = runBlocking {
        var readCalls = 0
        var selectCalls = 0
        val scene = ImageComposeScene(900, 180, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                MaterialTheme {
                    ChapterRow(
                        chapter = Chapter.create().copy(id = 9L, mangaId = 1L, name = "Long press chapter"),
                        title = "Long press chapter",
                        downloadStatus = ChapterDownloadStatus.NOT_DOWNLOADED,
                        downloadProgress = null,
                        onSelect = { selectCalls++ },
                        onDownload = {},
                        onDeleteDownload = {},
                        onCancelDownload = {},
                        onRetryDownload = {},
                        onToggleBookmark = {},
                        onRead = { readCalls++ },
                    )
                }
            }
            scene.render()

            val longClick = nodes(scene).single { node ->
                node.config.contains(SemanticsActions.OnLongClick) && node.hasText("Long press chapter")
            }
            assertTrue(requireNotNull(longClick.config[SemanticsActions.OnLongClick].action).invoke())

            assertEquals(0, readCalls)
            assertEquals(1, selectCalls)
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `chapter action buttons do not bubble into opening the row`() = runBlocking {
        var readCalls = 0
        var bookmarkCalls = 0
        var downloadCalls = 0
        val scene = ImageComposeScene(900, 180, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                MaterialTheme {
                    ChapterRow(
                        chapter = Chapter.create().copy(id = 10L, mangaId = 1L, name = "Button chapter"),
                        title = "Button chapter",
                        downloadStatus = ChapterDownloadStatus.NOT_DOWNLOADED,
                        downloadProgress = null,
                        onSelect = {},
                        onDownload = { downloadCalls++ },
                        onDeleteDownload = {},
                        onCancelDownload = {},
                        onRetryDownload = {},
                        onToggleBookmark = { bookmarkCalls++ },
                        onRead = { readCalls++ },
                    )
                }
            }
            scene.render()

            val bookmark = nodes(scene).single { node ->
                node.config.contains(SemanticsActions.OnClick) &&
                    node.config.contains(SemanticsProperties.ContentDescription) &&
                    MR.strings.action_bookmark.localized() in node.config[SemanticsProperties.ContentDescription]
            }
            tap(scene, bookmark.boundsInRoot.center)

            val download = nodes(scene).single { node ->
                node.config.contains(SemanticsActions.OnClick) &&
                    node.config.contains(SemanticsProperties.ContentDescription) &&
                    MR.strings.action_download.localized() in node.config[SemanticsProperties.ContentDescription]
            }
            tap(scene, download.boundsInRoot.center)

            assertEquals(1, bookmarkCalls)
            assertEquals(1, downloadCalls)
            assertEquals(0, readCalls)
        } finally {
            scene.close()
        }
    }

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

    private fun SemanticsNode.hasText(text: String): Boolean =
        config.contains(SemanticsProperties.Text) && config[SemanticsProperties.Text].any { it.text == text }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun tap(scene: ImageComposeScene, position: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, position)
        scene.sendPointerEvent(PointerEventType.Release, position)
        scene.render()
    }
}
