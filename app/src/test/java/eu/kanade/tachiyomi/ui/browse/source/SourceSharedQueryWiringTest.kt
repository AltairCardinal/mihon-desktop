package eu.kanade.tachiyomi.ui.browse.source

import eu.kanade.domain.DomainModule
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceMangaSearchService
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class SourceSharedQueryWiringTest {

    @Test
    fun `Android domain DI resolves shared source query service`() {
        Injekt.importModule(DomainModule())

        assertNotNull(Injekt.get<SourceMangaSearchService>())
    }

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
        val screen = productionSource(
            "app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/globalsearch/GlobalSearchScreen.kt",
        )
        val presentation = productionSource(
            "app/src/main/java/eu/kanade/presentation/browse/GlobalSearchScreen.kt",
        )

        assertSharedDelegate(source)
        assertFalse(source.contains("source.getSearchManga("), "direct source search must not bypass shared results")
        assertTrue(
            screen.contains("onErrorAction ="),
            "global search screen must consume shared recovery actions",
        )
        assertTrue(
            screen.countOccurrences("globalSearchRecoveryScreen(") >= 2,
            "OpenLogin must use the behavior-tested WebView recovery helper",
        )
        assertTrue(
            presentation.contains("onErrorAction: (CatalogueSource, SourcePageError) -> Unit"),
            "presentation must preserve SourcePageError until the user action",
        )
    }

    @Test
    fun `Desktop browse delegates production paging to shared query result and reducer`() {
        val source = productionSource(
            "app-desktop/src/main/kotlin/mihon/desktop/ui/browse/SourceBrowseScreen.kt",
        )
        val coordinator = productionSource(
            "app-desktop/src/main/kotlin/mihon/desktop/ui/browse/DesktopSourceQueryCoordinators.kt",
        )

        assertSharedDelegate(source + coordinator)
        assertFalse(source.contains("safeSourceCall {"), "platform error wrapper must not replace shared errors")
        assertTrue(
            source.contains("SourceBrowseQueryCoordinator("),
            "production browse screen must consume the behavior-tested recovery coordinator",
        )
        assertFalse(
            source.contains("SourceQueryReducer()"),
            "production browse screen must not keep a second reducer beside the coordinator",
        )
    }

    @Test
    fun `Desktop global search delegates each production source call to shared query result and reducer`() {
        val source = productionSource(
            "app-desktop/src/main/kotlin/mihon/desktop/ui/browse/GlobalSearchScreen.kt",
        )
        val coordinator = productionSource(
            "app-desktop/src/main/kotlin/mihon/desktop/ui/browse/DesktopSourceQueryCoordinators.kt",
        )

        assertSharedDelegate(source + coordinator)
        assertFalse(source.contains("source.getSearchManga("), "direct source search must not bypass shared results")
        assertTrue(
            source.contains("DesktopGlobalSearchCoordinator("),
            "production global search screen must consume the behavior-tested generation coordinator",
        )
        assertFalse(
            source.contains("SourceQueryReducer()"),
            "production global search screen must not keep a second reducer beside the coordinator",
        )
    }

    private fun assertSharedDelegate(source: String) {
        assertTrue(
            source.contains(".loadPageResult("),
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

    private fun String.countOccurrences(value: String): Int = windowed(value.length).count { it == value }
}
