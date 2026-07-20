package mihon.desktop.ui.browse

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.domain.manga.model.toDomainManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourcePageRequest
import tachiyomi.domain.source.service.SourceQuery
import tachiyomi.domain.source.service.SourceQueryState
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.i18n.MR
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
class SourceBrowseCanonicalResultWiringTest {

    @Test
    fun `closing materializer rejects a non cancellable stale publication`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val listed = SManga.create().apply {
            url = "/stale"
            title = "Stale"
        }
        val materializer = SourceResultMaterializer(this) { items, sourceId ->
            withContext(NonCancellable) {
                started.complete(Unit)
                release.await()
                items.map { it.toDomainManga(sourceId) }
            }.also { completed.complete(Unit) }
        }
        materializer.sync(
            generation = 7,
            queryStates = mapOf(
                19L to SourceQueryState.Content(
                    SourcePageRequest(19L, page = 1, generation = 7, query = SourceQuery.Popular),
                    listOf(listed),
                    hasNextPage = false,
                ),
            ),
        )
        withTimeout(15_000) { started.await() }

        materializer.close()
        release.complete(Unit)
        withTimeout(15_000) { completed.await() }
        yield()

        assertTrue(materializer.results.isEmpty(), "closed consumer must reject stale completion")
    }

    @Test
    fun `empty source renders fixed main localized no results copy`() = runBlocking {
        val previousLocale = Locale.getDefault()
        val locale = Locale.SIMPLIFIED_CHINESE
        Locale.setDefault(locale)
        val source = mockk<CatalogueSource> {
            every { id } returns 20L
            every { name } returns "Empty source"
            every { lang } returns "zh"
            every { supportsLatest } returns false
            every { getFilterList() } returns FilterList()
            coEvery { getPopularManga(1) } returns MangasPage(emptyList(), false)
        }
        val dependencies = mockk<DesktopUiDependencies> {
            every { sourceManager } returns FakeDesktopSourceManager(listOf(source))
            every { appPreferences } returns sourceBrowseHistoryPreferences()
            every { extensionManager } returns sourceBrowseExtensionManager()
            every { sourceMangaSearchService } returns SourceMangaSearchService()
            every { saveSourceMangaForDetails } returns mockk(relaxed = true)
            every { getManga } returns mockk(relaxed = true)
            every { sourceLoginSessionFactory } returns mockk(relaxed = true)
        }
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}

        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(SourceBrowseScreen(source.id)) { CurrentScreen() }
                }
            }
            val expected = MR.strings.no_results_found.localized(locale)
            withTimeout(15_000) {
                while (!renderedText(scene).contains(expected)) {
                    scene.render()
                    yield()
                }
            }
            assertFalse(renderedText(scene).contains(MR.strings.source_empty_screen.localized(locale)))
        } finally {
            scene.close()
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `browse persists and observes canonical rows without opening a card`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(Database.Schema::create)
        val handler = JvmDatabaseHandler(
            Database(
                driver,
                tachiyomi.data.History.Adapter(DateColumnAdapter),
                tachiyomi.data.Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            ),
            driver,
        )
        val repository = MangaRepositoryImpl(handler)
        val saver = SaveSourceMangaForDetails(NetworkToLocalManga(repository), repository, ChapterRepositoryImpl(handler))
        val listed = SManga.create().apply {
            url = "/canonical"
            title = "Listed title"
            thumbnail_url = "listed-cover"
        }
        val duplicate = SManga.create().apply {
            url = listed.url
            title = "Duplicate title"
            thumbnail_url = "duplicate-cover"
        }
        val calls = mutableListOf<Int>()
        val source = mockk<CatalogueSource> {
            every { id } returns 19L
            every { name } returns "Canonical browse"
            every { lang } returns "en"
            every { supportsLatest } returns false
            every { getFilterList() } returns FilterList()
            coEvery { getPopularManga(1) } answers {
                calls += 1
                MangasPage(listOf(listed, duplicate), false)
            }
        }
        val dependencies = mockk<DesktopUiDependencies> {
            every { sourceManager } returns FakeDesktopSourceManager(listOf(source))
            every { appPreferences } returns sourceBrowseHistoryPreferences()
            every { extensionManager } returns sourceBrowseExtensionManager()
            every { sourceMangaSearchService } returns SourceMangaSearchService()
            every { saveSourceMangaForDetails } returns saver
            every { getManga } returns GetManga(repository)
            every { sourceLoginSessionFactory } returns mockk(relaxed = true)
        }
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}

        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(SourceBrowseScreen(source.id)) { CurrentScreen() }
                }
            }
            val canonical = withTimeout(15_000) {
                while (repository.getMangaByUrlAndSourceId(listed.url, source.id) == null) {
                    scene.render()
                    yield()
                }
                requireNotNull(repository.getMangaByUrlAndSourceId(listed.url, source.id))
            }
            assertEquals("Listed title", canonical.title)
            assertEquals(listOf(1), calls)

            assertTrue(repository.update(MangaUpdate(canonical.id, title = "DB title", thumbnailUrl = "db-cover", favorite = true)))
            withTimeout(15_000) {
                while (!renderedText(scene).contains("DB title") || !renderedText(scene).contains(MR.strings.in_library.localized())) {
                    scene.render()
                    yield()
                }
            }
            assertTrue(nodes(scene).any {
                it.config.contains(SemanticsProperties.TestTag) &&
                    it.config[SemanticsProperties.TestTag] == "source-browse-cover:db-cover"
            })
            assertEquals(listOf(1), calls, "database projection must not issue another source request")
        } finally {
            scene.close()
            driver.close()
        }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        flatten(owner.unmergedRootSemanticsNode)
    }

    private fun renderedText(scene: ImageComposeScene) = nodes(scene).joinToString { it.config.toString() }
}
