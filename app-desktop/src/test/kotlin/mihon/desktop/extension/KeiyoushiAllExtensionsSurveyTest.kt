package mihon.desktop.extension

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeiyoushiAllExtensionsSurveyTest {

    @Test
    fun `survey plan preserves every index entry regardless of language or api version`() {
        val entries = listOf(
            KeiyoushiSurveyEntry(
                name = "English",
                pkg = "ext.english",
                artifactUrl = "https://repo.example/jar/english.jar",
                lang = "en",
                code = 1,
                version = "1.4.1",
            ),
            KeiyoushiSurveyEntry(
                name = "Chinese",
                pkg = "ext.chinese",
                artifactUrl = "https://repo.example/jar/chinese.jar",
                lang = "zh",
                code = 2,
                version = "1.4.2",
            ),
            KeiyoushiSurveyEntry(
                name = "New API",
                pkg = "ext.new-api",
                artifactUrl = "https://repo.example/jar/new-api.jar",
                lang = "all",
                code = 3,
                version = "1.6.0",
            ),
        )

        val planned = KeiyoushiAllExtensionsSurvey.plan(entries)

        assertEquals(entries, planned)
        assertEquals(setOf("en", "zh", "all"), planned.mapTo(mutableSetOf()) { it.lang })
    }

    @Test
    fun `coverage requires exactly one result for every planned artifact`() {
        val entries = listOf(
            KeiyoushiSurveyEntry("One", "ext.one", "https://repo.example/jar/one.jar", "en", 1, "1.4.1"),
            KeiyoushiSurveyEntry("Two", "ext.two", "https://repo.example/jar/two.jar", "ja", 2, "1.4.2"),
        )

        assertFalse(
            KeiyoushiAllExtensionsSurvey.hasCompleteCoverage(
                entries,
                listOf(KeiyoushiSurveyResult.success(entries.first(), sourcesLoaded = 1)),
            ),
        )
        assertTrue(
            KeiyoushiAllExtensionsSurvey.hasCompleteCoverage(
                entries,
                entries.map { KeiyoushiSurveyResult.success(it, sourcesLoaded = 1) },
            ),
        )
    }

    @Test
    fun `zero loaded sources is not a successful compatibility result`() {
        val entry = KeiyoushiSurveyEntry(
            "Empty",
            "ext.empty",
            "https://repo.example/jar/empty.jar",
            "en",
            1,
            "1.4.1",
        )

        assertFalse(KeiyoushiSurveyResult.success(entry, sourcesLoaded = 0).isCompatible)
        assertTrue(KeiyoushiSurveyResult.success(entry, sourcesLoaded = 1).isCompatible)
    }
}
