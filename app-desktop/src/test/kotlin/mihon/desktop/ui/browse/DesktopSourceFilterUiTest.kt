package mihon.desktop.ui.browse

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.desktop.source.FakeSource
import mihon.desktop.ui.migration.MigrationSearchScreen
import mihon.desktop.ui.source.desktopSourceErrorMessage
import mihon.domain.error.AppError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.i18n.MR
import java.io.IOException
import java.util.prefs.Preferences

class DesktopSourceFilterUiTest {
    @Test
    fun `browse constructor filter incompatibility is visible and never queries source`() =
        assertFilterFailure { SourceBrowseScreen(931) }

    @Test
    fun `migration constructor filter incompatibility is visible and never queries source`() =
        assertFilterFailure { MigrationSearchScreen(1, "Original manga") }

    @Test
    fun `browse explicit retry prepares filters again and executes healthy query`() =
        assertFilterFailure(recover = true) { SourceBrowseScreen(931) }

    @Test
    fun `migration explicit retry prepares filters again and executes healthy query`() =
        assertFilterFailure(recover = true) { MigrationSearchScreen(1, "Original manga") }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun assertFilterFailure(recover: Boolean = false, screen: () -> Screen) = runBlocking {
        var filterReads = 0
        var queryCalls = 0
        var broken = true
        val source = object : CatalogueSource by FakeSource(931, "en", "Filter fixture") {
            override fun getFilterList(): FilterList {
                filterReads++
                check(filterReads < 16) { "Unexpected filter retry loop" }
                if (!broken) return FilterList()
                if (recover) throw IOException("temporary filter failure")
                throw NoSuchMethodError("future filter ABI")
            }
            override suspend fun getPopularManga(page: Int): MangasPage {
                queryCalls++
                return MangasPage(emptyList(), false)
            }
            override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
                queryCalls++
                return MangasPage(emptyList(), false)
            }
        }
        val preferenceNode = Preferences.userRoot().node("/mihon/ec-filter-ui/${System.nanoTime()}")
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { sourceManager } returns FakeDesktopSourceManager(listOf(source))
            every { sourceMangaSearchService } returns SourceMangaSearchService()
            every { appPreferences } returns DesktopAppPreferences(DesktopPreferenceStore(preferenceNode))
            every { saveSourceMangaForDetails } returns mockk(relaxed = true)
            every { getManga } returns mockk(relaxed = true)
        }
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
        try {
            var escaped: Throwable? = null
            try {
                scene.setContent {
                    CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                        Navigator(screen()) { CurrentScreen() }
                    }
                }
                scene.render()
            } catch (error: Throwable) {
                escaped = error
            }
            assertEquals(null, escaped)
            val expected =
                desktopSourceErrorMessage(if (recover) AppError.Network() else AppError.ExtensionIncompatible())
            withTimeout(3_000) {
                while (scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
                        .none { it.config.toString().contains(expected) }
                ) {
                    scene.render()
                    delay(10)
                }
            }
            assertEquals(1, filterReads)
            assertEquals(0, queryCalls)
            if (recover) {
                broken = false
                val button = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.first {
                    it.config.contains(SemanticsActions.OnClick) &&
                        it.config.toString().contains(MR.strings.action_retry.localized())
                }
                check(requireNotNull(button.config[SemanticsActions.OnClick].action).invoke())
                withTimeout(3_000) {
                    while (queryCalls == 0) {
                        scene.render()
                        delay(10)
                    }
                }
                assertEquals(2, filterReads)
                assertEquals(1, queryCalls)
            }
            repeat(3) { scene.render() }
            assertEquals(if (recover) 2 else 1, filterReads)
            assertEquals(if (recover) 1 else 0, queryCalls)
        } finally {
            scene.close()
            preferenceNode.removeNode()
        }
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
