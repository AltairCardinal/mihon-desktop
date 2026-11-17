package mihon.desktop.ui.library

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tachiyomi.domain.chapter.model.Chapter

@OptIn(ExperimentalComposeUiApi::class)
class ChapterSelectionBarLayoutTest {
    @ParameterizedTest
    @CsvSource(
        "710, 1.0, 4, 1",
        "1065, 1.5, 4, 1",
        "1067, 1.5, 4, 1",
        "513, 1.0, 5, 1",
        "513, 1.0, 6, 1",
        "160, 1.0, 4, 2",
    )
    fun `actions only wrap when their minimum widths no longer fit`(
        width: Int,
        density: Float,
        actionCount: Int,
        expectedRows: Int,
    ) = runBlocking {
        val chapters = when (actionCount) {
            4 -> listOf(Chapter.create().copy(read = true), Chapter.create().copy(id = 2, read = true))
            5 -> listOf(Chapter.create(), Chapter.create().copy(id = 2, read = true))
            else -> listOf(Chapter.create().copy(lastPageRead = 1))
        }
        var clicks = 0
        val scene = ImageComposeScene(width, 300, density = Density(density), coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                MaterialTheme {
                    ChapterSelectionBar(
                        selectedCount = chapters.size,
                        selectedChapters = chapters,
                        downloadAction = ChapterSelectionDownloadAction.DOWNLOAD,
                        showDownload = true,
                        showDelete = true,
                        onBookmark = { clicks++ },
                        onMarkRead = { clicks++ },
                        onMarkUnread = { clicks++ },
                        onMarkBelowRead = { clicks++ },
                        onDownloadOrDelete = { clicks++ },
                        onClose = {},
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            scene.render()
            val buttons = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
                .filter { it.config.contains(SemanticsActions.OnClick) }
            assertEquals(actionCount, buttons.size)
            assertEquals(expectedRows, buttons.map { it.boundsInRoot.top }.distinct().size)
            buttons.forEach { button ->
                assertTrue(button.boundsInRoot.width >= 48 * density)
                assertTrue(button.boundsInRoot.right <= width)
                button.config[SemanticsActions.OnClick].action!!.invoke()
            }
            assertEquals(actionCount, clicks)
        } finally {
            scene.close()
        }
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
