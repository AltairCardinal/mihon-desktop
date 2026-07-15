package eu.kanade.tachiyomi.ui.browse.source

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SourceSharedQueryWiringTest {

    @Test
    fun `Android browse production paging delegates to shared query result and reducer`() {
        val source = productionSource(
            "app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreenModel.kt",
        )

        assertSharedDelegate(source)
        assertFalse(source.contains("getRemoteManga(sourceId"), "old paging delegate must not bypass shared results")
    }

    @Test
    fun `Android global search delegates each production source call to shared query result and reducer`() {
        val source = productionSource(
            "app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/globalsearch/SearchScreenModel.kt",
        )

        assertSharedDelegate(source)
        assertFalse(source.contains("source.getSearchManga("), "direct source search must not bypass shared results")
    }

    @Test
    fun `Desktop browse delegates production paging to shared query result and reducer`() {
        val source = productionSource(
            "app-desktop/src/main/kotlin/mihon/desktop/ui/browse/SourceBrowseScreen.kt",
        )

        assertSharedDelegate(source)
        assertFalse(source.contains("safeSourceCall {"), "platform error wrapper must not replace shared errors")
    }

    @Test
    fun `Desktop global search delegates each production source call to shared query result and reducer`() {
        val source = productionSource(
            "app-desktop/src/main/kotlin/mihon/desktop/ui/browse/GlobalSearchScreen.kt",
        )

        assertSharedDelegate(source)
        assertFalse(source.contains("source.getSearchManga("), "direct source search must not bypass shared results")
        assertTrue(
            source.indexOf("queryStates.putAll(") in 0 until source.indexOf("coroutineScope {"),
            "new generation states must be registered before old source results can finish",
        )
        assertTrue(
            source.contains("if (generation == requestGeneration) isSearching = false"),
            "an old generation must not finish the current search indicator",
        )
    }

    private fun assertSharedDelegate(source: String) {
        assertTrue(
            source.contains("sourceMangaSearchService.loadPageResult("),
            "production source request must delegate to SourceMangaSearchService.loadPageResult",
        )
        assertTrue(
            source.contains("SourceQueryReducer"),
            "production source result must pass through SourceQueryReducer",
        )
    }

    private fun productionSource(path: String): String {
        var current: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (current != null && !current.resolve("settings.gradle.kts").isFile) current = current.parentFile
        return requireNotNull(current) { "Repository root not found from ${System.getProperty("user.dir")}" }
            .resolve(path)
            .readText()
    }
}
