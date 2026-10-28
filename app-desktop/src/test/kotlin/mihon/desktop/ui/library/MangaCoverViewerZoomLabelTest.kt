package mihon.desktop.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The cover viewer labels its zoom slider with the shared `desktop_ui_zoom` resource. A wrong
 * translation of that resource is user visible: the label must read as a zoom affordance instead of
 * an unrelated word.
 */
class MangaCoverViewerZoomLabelTest {
    @Test
    fun `cover viewer zoom label is translated as 缩放 in simplified Chinese`() {
        assertEquals("缩放", stringResource("zh-rCN", "desktop_ui_zoom"))
    }

    @Test
    fun `cover viewer zoom label is translated as 縮放 in traditional Chinese`() {
        assertEquals("縮放", stringResource("zh-rTW", "desktop_ui_zoom"))
    }

    @Test
    fun `zoom label resource means zoom in the base locale`() {
        assertEquals("Zoom", stringResource("base", "desktop_ui_zoom"))
    }

    @Test
    fun `cover viewer renders the shared zoom resource for its slider label`() {
        val source = repositoryRoot()
            .resolve("app-desktop/src/main/kotlin/mihon/desktop/ui/library/MangaCoverViewerDialog.kt")
            .toFile()
            .readText(Charsets.UTF_8)

        assertTrue(
            source.contains("MR.strings.desktop_ui_zoom.localized()"),
            "cover viewer must label the zoom slider with the shared desktop_ui_zoom resource",
        )
    }

    private fun stringResource(locale: String, name: String): String {
        val xml = repositoryRoot()
            .resolve("i18n/src/commonMain/moko-resources/$locale/strings.xml")
            .toFile()
            .readText(Charsets.UTF_8)
        val match = Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(xml)

        assertNotNull(match, "missing string resource '$name' in locale '$locale'")

        return match!!.groupValues[1]
    }

    private fun repositoryRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("domain")) }
}
