package mihon.desktop.extension

import eu.kanade.tachiyomi.source.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class ExtensionSearchTest {
    @Test
    fun `available search matches name package and source`() {
        val extension = DesktopAvailableExtension(
            name = "Manga Reader",
            pkgName = "org.example.reader",
            versionName = "1.0",
            versionCode = 1,
            lang = "en",
            isNsfw = false,
            jarUrl = "https://repo/apk/reader.jar",
            iconUrl = "https://repo/icon/reader.png",
            repoUrl = "https://repo",
            sources = listOf(DesktopAvailableSource(1, "en", "Example Comics", "https://comics.example")),
        )

        assertEquals(listOf(extension), filterAvailableByQuery(listOf(extension), "comics"))
        assertEquals(listOf(extension), filterAvailableByQuery(listOf(extension), "ORG.EXAMPLE"))
        assertEquals(emptyList<DesktopAvailableExtension>(), filterAvailableByQuery(listOf(extension), "missing"))
    }

    @Test
    fun `installed search matches source name`() {
        val source = object : Source {
            override val id = 1L
            override val name = "Example Comics"
            override val lang = "en"
        }
        val extension = InstalledExtension(File("reader.jar"), listOf(source))

        assertEquals(listOf(extension), filterInstalledByQuery(listOf(extension), "example"))
    }
}
