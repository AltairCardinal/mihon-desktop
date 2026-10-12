package mihon.desktop.ui.browse

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.source.FakeDesktopSourceManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourceResultMetadata
import tachiyomi.i18n.MR

@OptIn(ExperimentalComposeUiApi::class)
class CatalogResultFilterWiringTest {
    @Test
    fun `source genre language is displayed without an inferred suffix`() = runBlocking {
        val metadata = SourceResultMetadata.from(listed("/genre", "Travel diary").apply { genre = "Chinese" })
        val scene = ImageComposeScene(360, 200, coroutineContext = coroutineContext) {}
        try {
            scene.setContent { CatalogResultMetadataLabel(metadata, "/genre") }
            assertTrue(
                text(
                    scene,
                ).contains(java.util.Locale.forLanguageTag("zh").getDisplayName(java.util.Locale.getDefault())),
            )
            assertFalse(text(scene).contains(MR.strings.catalog_result_inferred.localized()))
        } finally {
            scene.close()
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [900, 360])
    fun `source without filters supports Chinese and group filtering without losing query or page boundary`(
        width: Int,
    ) = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(Database.Schema::create)
        val handler = JvmDatabaseHandler(
            Database(
                driver,
                tachiyomi.data.History.Adapter(DateColumnAdapter),
                tachiyomi.data.Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            ),
            driver,
        )
        val repository = MangaRepositoryImpl(handler, NoopCreatorLibraryIndexWriter)
        val saver =
            SaveSourceMangaForDetails(NetworkToLocalManga(repository), repository, ChapterRepositoryImpl(handler))
        val firstPage = (1..40).map { index -> listed("/en-$index", "English edition $index [English]") }
        val secondPage = listOf(
            listed("/zh-a", "中文版本甲 [轟媽無修正版]"),
            listed("/zh-b", "中文版本乙 [Chinese] [白云汉化组]"),
        )
        val calls = mutableListOf<Pair<Int, String>>()
        val source = mockk<Source> {
            every { id } returns 78L
            every { name } returns "Mixed catalogue"
            every { lang } returns "all"
            every { supportsLatest } returns false
            every { getFilterList() } returns FilterList()
            coEvery { getSearchManga(any(), any(), any()) } answers {
                val page = firstArg<Int>()
                calls += page to secondArg<String>()
                MangasPage(if (page == 1) firstPage else secondPage, page == 1)
            }
        }
        val dependencies = mockk<DesktopUiDependencies> {
            every { sourceManager } returns FakeDesktopSourceManager(listOf(source))
            every { appPreferences } returns sourceBrowseHistoryPreferences()
            every { extensionManager } returns sourceBrowseExtensionManager()
            every { sourceExtensionLookup } returns sourceBrowseExtensionManager()
            every { sourceMangaSearchService } returns SourceMangaSearchService()
            every { saveSourceMangaForDetails } returns saver
            every { getManga } returns GetManga(repository)
            every { sourceLoginSessionFactory } returns mockk(relaxed = true)
        }
        val scene = ImageComposeScene(width, 700, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(SourceBrowseScreen(source.id, initialQuery = "author:Alice")) { CurrentScreen() }
                }
            }
            withTimeout(15_000) {
                while (!text(scene).contains("English edition 1")) {
                    scene.render().close()
                    yield()
                }
            }
            assertEquals(listOf(1 to "author:Alice"), calls)
            click(scene, "catalog-result-filter-open")
            click(scene, "catalog-result-language:zh")
            snapshot(scene, "filter-$width")
            click(scene, "catalog-result-filter-cancel")
            assertTrue(text(scene).contains("English edition 1"), "cancel must discard the draft")
            click(scene, "catalog-result-filter-open")
            click(scene, "catalog-result-language:zh")
            click(scene, "catalog-result-filter-apply")
            assertFalse(text(scene).contains("English edition 1"))
            assertEquals(listOf(1 to "author:Alice"), calls, "zero visible results must not start unbounded paging")
            click(scene, "catalog-result-continue")
            withTimeout(15_000) {
                while (!text(scene).contains("中文版本乙")) {
                    scene.render().close()
                    yield()
                }
            }
            assertEquals(listOf(1 to "author:Alice", 2 to "author:Alice"), calls)
            assertTrue(text(scene).contains("白云汉化组"))
            assertTrue(text(scene).contains("中文版本甲"))
            assertTrue(text(scene).contains("轟媽"))
            assertFalse(text(scene).contains(MR.strings.catalog_result_inferred.localized()))
            snapshot(scene, "results-$width")
            click(scene, "catalog-result-filter-open")
            click(scene, "catalog-result-group:白云汉化组")
            click(scene, "catalog-result-filter-apply")
            assertFalse(text(scene).contains("中文版本甲"))
            assertTrue(text(scene).contains("中文版本乙"))
            val hidden = requireNotNull(repository.getMangaByUrlAndSourceId("/zh-a", source.id))
            assertTrue(repository.update(MangaUpdate(hidden.id, title = "修订甲 [Chinese] [白云汉化组]")))
            withTimeout(15_000) {
                while (!text(scene).contains("修订甲")) {
                    yield()
                }
            }
            assertFalse(
                nodes(scene).any {
                    it.config.contains(SemanticsProperties.TestTag) &&
                        it.config[SemanticsProperties.TestTag] == "catalog-result-continue"
                },
            )
            click(scene, "catalog-result-filter-open")
            click(scene, "catalog-result-filter-reset")
            click(scene, "catalog-result-filter-apply")
            withTimeout(5_000) {
                while (!text(scene).contains("English edition 1")) yield()
            }
            assertEquals(2, calls.size, "clearing a local filter must reuse loaded source pages")
        } finally {
            scene.close()
            driver.close()
        }
    }

    private fun listed(url: String, title: String) = SManga.create().apply {
        this.url = url
        this.title = title
    }

    private suspend fun click(scene: ImageComposeScene, tag: String) {
        var target: SemanticsNode? = null
        withTimeout(5_000) {
            while (target == null) {
                scene.render().close()
                target = nodes(scene).firstOrNull {
                    it.config.contains(SemanticsProperties.TestTag) &&
                        it.config[SemanticsProperties.TestTag] == tag && it.config.contains(SemanticsActions.OnClick)
                }
                if (target == null) yield()
            }
        }
        assertTrue(checkNotNull(target).config[SemanticsActions.OnClick].action?.invoke() == true)
        yield()
        scene.render().close()
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        flatten(owner.unmergedRootSemanticsNode)
    }

    private fun text(scene: ImageComposeScene): String {
        scene.render().close()
        return nodes(scene).joinToString { it.config.toString() }
    }

    private fun snapshot(scene: ImageComposeScene, name: String) {
        val directory = java.io.File("build/reports/catalog-result-filter").apply { mkdirs() }
        scene.render().use { image ->
            image.encodeToData()?.use { java.io.File(directory, "$name.png").writeBytes(it.bytes) }
        }
    }
}
