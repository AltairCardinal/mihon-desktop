package mihon.desktop.ui.browse

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.text.AnnotatedString
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
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.FakeDesktopSourceManager
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.i18n.MR
import java.util.prefs.Preferences

@OptIn(ExperimentalComposeUiApi::class)
class GlobalSearchResultProductionWiringTest {

    @Test
    fun `production search gates canonical rows retries materialization and rejects old completion`() = runBlocking {
        Fixture().use { fixture ->
            val source = source()
            val preferences = DesktopAppPreferences(DesktopPreferenceStore(fixture.preferenceRoot)).apply {
                enabledLanguages.set(setOf("en"))
                pinnedSources.set(setOf(source.id.toString()))
            }
            val dependencies = mockk<DesktopUiDependencies> {
                every { sourceManager } returns FakeDesktopSourceManager(listOf(source))
                every { appPreferences } returns preferences
                every { sourceMangaSearchService } returns SourceMangaSearchService()
                every { saveSourceMangaForDetails } returns fixture.saver
                every { sourceLoginSessionFactory } returns mockk(relaxed = true)
            }
            val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
            try {
                scene.setContent {
                    CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                        Navigator(GlobalSearchScreen("old")) { CurrentScreen() }
                    }
                }
                scene.render()
                withTimeout(2_000) { fixture.repository.oldStarted.await() }
                scene.render()
                assertFalse(text(scene).contains("old canonical"), "raw result must remain gated")

                setText(scene, "new")
                scene.render()
                click(scene, "Search")
                withTimeout(2_000) {
                    while (!fixture.repository.newFailed.isCompleted) {
                        scene.render()
                        yield()
                    }
                }
                scene.render()
                assertTrue(text(scene).contains(MR.strings.unknown_error.localized()))
                assertFalse(text(scene).contains("new canonical"))
                click(scene, MR.strings.action_retry.localized())
                withTimeout(2_000) { fixture.repository.newInserted.await() }
                scene.render()
                assertTrue(text(scene).contains("new canonical"))
                assertNotNull(fixture.mangas.getMangaByUrlAndSourceId("/new", source.id))

                fixture.repository.releaseOld.complete(Unit)
                withTimeout(2_000) { fixture.repository.oldInserted.await() }
                scene.render()
                assertTrue(text(scene).contains("new canonical"))
                assertFalse(text(scene).contains("old canonical"))
            } finally {
                scene.close()
            }
        }
    }

    private fun source(): CatalogueSource = mockk {
        every { id } returns 9L
        every { name } returns "Canonical"
        every { lang } returns "en"
        every { getFilterList() } returns FilterList()
        coEvery { getSearchManga(1, any(), any()) } answers {
            val query = secondArg<String>()
            val item = SManga.create().apply { url = "/$query"; title = "$query canonical" }
            MangasPage(listOf(item, item), false)
        }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        flatten(owner.rootSemanticsNode)
    }
    private fun text(scene: ImageComposeScene) = nodes(scene).joinToString { it.config.toString() }
    private fun click(scene: ImageComposeScene, label: String) = requireNotNull(
        nodes(scene).first { it.config.toString().contains(label) }.config[SemanticsActions.OnClick].action,
    ).invoke()
    private fun setText(scene: ImageComposeScene, value: String) = requireNotNull(
        nodes(scene).first { it.config.contains(SemanticsActions.SetText) }.config[SemanticsActions.SetText].action,
    ).invoke(AnnotatedString(value))

    private class Fixture : AutoCloseable {
        val preferenceRoot = Preferences.userRoot().node("mihon-global-search-${System.nanoTime()}")
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(Database.Schema::create)
        private val handler = JvmDatabaseHandler(
            Database(driver, tachiyomi.data.History.Adapter(DateColumnAdapter), tachiyomi.data.Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter)),
            driver,
        )
        val mangas = MangaRepositoryImpl(handler)
        val repository = ControlledRepository(mangas)
        val saver = SaveSourceMangaForDetails(NetworkToLocalManga(repository), repository, ChapterRepositoryImpl(handler))
        override fun close() {
            driver.close()
            preferenceRoot.clear()
        }
    }

    private class ControlledRepository(private val delegate: MangaRepository) : MangaRepository by delegate {
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val oldInserted = CompletableDeferred<Unit>()
        val newFailed = CompletableDeferred<Unit>()
        val newInserted = CompletableDeferred<Unit>()
        private var rejectNew = true
        override suspend fun insertNetworkManga(manga: List<Manga>): List<Manga> = when (manga.single().url) {
            "/old" -> withContext(NonCancellable) {
                oldStarted.complete(Unit)
                releaseOld.await()
                delegate.insertNetworkManga(manga).also { oldInserted.complete(Unit) }
            }
            else -> if (rejectNew) {
                rejectNew = false
                newFailed.complete(Unit)
                error("controlled materialization failure")
            } else {
                delegate.insertNetworkManga(manga).also { newInserted.complete(Unit) }
            }
        }
    }
}
