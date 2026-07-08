package mihon.desktop.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class DualPageContextMenuWiringTest {

    @Test
    fun `every dual page image box receives context menu wiring`() {
        val source = readerSourceFile("DualPagePagerViewer.kt").readText()
        val blocks = zoomablePageBoxArgumentBlocks(source)

        assertTrue(blocks.isNotEmpty())
        assertEquals(
            blocks.size,
            blocks.count { "contextMenuScope = contextMenuScope" in it },
            "Every DualPagePagerViewer ZoomablePageBox must be wrapped by PageContextMenu when a scope is available.",
        )
    }

    private fun readerSourceFile(name: String): File {
        val cwd = File(System.getProperty("user.dir"))
        val repoRoot = if (File(cwd, "app-desktop").exists()) cwd else cwd.parentFile
        return File(repoRoot, "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/$name")
    }

    private fun zoomablePageBoxArgumentBlocks(source: String): List<String> {
        val marker = "ZoomablePageBox("
        return Regex(Regex.escape(marker)).findAll(source).map { match ->
            val start = match.range.first
            var depth = 0
            var end = source.length
            for (index in start until source.length) {
                when (source[index]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            end = index + 1
                            break
                        }
                    }
                }
            }
            source.substring(start, end)
        }.toList()
    }
}
