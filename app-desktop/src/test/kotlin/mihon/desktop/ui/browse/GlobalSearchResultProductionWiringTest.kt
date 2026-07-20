package mihon.desktop.ui.browse

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.desktop.ui.library.MangaDetailScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
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
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.i18n.MR
import java.util.prefs.Preferences
import java.util.concurrent.ConcurrentHashMap

private const val ASYNC_TIMEOUT_MS = 15_000L

@OptIn(ExperimentalComposeUiApi::class)
class GlobalSearchResultProductionWiringTest {

    @Test
    fun `only composed cards observe canonical database rows without another search`() = runBlocking {
        Fixture().use { fixture ->
            val searchesA = mutableListOf<String>()
            val searchesB = mutableListOf<String>()
            val listedA = mutableListOf<SManga>()
            val details = DetailProbe()
            val sourceA = source(9, "A", searchesA, "/shared", listedA, details, resultCount = 1)
            val sourceB = source(10, "B", searchesB, "/shared", resultCount = 1)
            val preferences = DesktopAppPreferences(DesktopPreferenceStore(fixture.preferenceRoot)).apply {
                enabledLanguages.set(setOf("en"))
                pinnedSources.set(setOf(sourceA.id.toString(), sourceB.id.toString()))
            }
            val dependencies = mockk<DesktopUiDependencies> {
                every { sourceManager } returns FakeDesktopSourceManager(listOf(sourceA, sourceB))
                every { appPreferences } returns preferences
                every { sourceMangaSearchService } returns SourceMangaSearchService()
                every { saveSourceMangaForDetails } returns fixture.saver
                every { getManga } returns fixture.getManga
                every { sourceLoginSessionFactory } returns mockk(relaxed = true)
            }
            var navigator: Navigator? = null
            val scene = ImageComposeScene(320, 1_400, coroutineContext = coroutineContext) {}
            try {
                scene.setContent {
                    CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                        Navigator(GlobalSearchScreen("observe")) {
                            navigator = LocalNavigator.currentOrThrow
                            CurrentScreen()
                        }
                    }
                }
                withTimeout(ASYNC_TIMEOUT_MS) {
                    while (!text(scene).contains("A listed 0") || !text(scene).contains("B listed 0") ||
                        !fixture.repository.observedRows.contains(Triple(sourceA.id, "/shared", "A listed 0")) ||
                        !fixture.repository.observedRows.contains(Triple(sourceB.id, "/shared", "B listed 0"))
                    ) {
                        scene.render()
                        yield()
                    }
                }
                scene.render()
                yield()
                val sharedKeys = setOf(sourceA.id to "/shared", sourceB.id to "/shared")
                assertTrue(fixture.repository.activeSubscriptions.size in 1..<10 && fixture.repository.activeSubscriptions.containsAll(sharedKeys))
                assertTrue(fixture.repository.seenSubscriptions.size in 1..<10 && fixture.repository.seenSubscriptions.containsAll(sharedKeys))

                val observed = requireNotNull(fixture.mangas.getMangaByUrlAndSourceId("/shared", sourceA.id))
                val untouched = requireNotNull(fixture.mangas.getMangaByUrlAndSourceId("/shared", sourceB.id))
                assertTrue(fixture.mangas.update(MangaUpdate(observed.id, title = "DB updated", thumbnailUrl = "updated-cover", favorite = true)))
                val repositoryUpdated = requireNotNull(fixture.mangas.getMangaByUrlAndSourceId("/shared", sourceA.id))
                assertEquals("updated-cover", repositoryUpdated.thumbnailUrl)
                withTimeout(ASYNC_TIMEOUT_MS) {
                    while (!fixture.repository.observedRows.contains(Triple(sourceA.id, "/shared", "DB updated"))) {
                        scene.render()
                        yield()
                    }
                }
                withTimeout(ASYNC_TIMEOUT_MS) {
                    while (!text(scene).contains("DB updated") || !text(scene).contains(MR.strings.in_library.localized())) {
                        scene.render()
                        yield()
                    }
                }
                assertTrue(nodes(scene, unmerged = true).any {
                    it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == "global-search-cover:updated-cover"
                })
                assertEquals(listOf("B listed 0", "B-cover-0", false), listOf(untouched.title, untouched.thumbnailUrl, untouched.favorite))
                val listed = listedA.single { it.url == "/shared" }
                val action = clickAction(scene, "DB updated")
                val stackSize = requireNotNull(navigator).size
                assertTrue(action.invoke())
                assertTrue(action.invoke())
                assertEquals(stackSize + 1, requireNotNull(navigator).size)
                assertEquals(observed.id, (requireNotNull(navigator).items.last() as MangaDetailScreen).mangaId)
                withTimeout(ASYNC_TIMEOUT_MS) { details.started.await() }
                assertEquals(1, details.inputs.size)
                assertSame(listed, details.inputs.single())
                assertEquals(listOf("/shared", "A listed 0", "A-cover-0"), listOf(listed.url, listed.title, listed.thumbnail_url))
                details.release.complete(Unit)
                withTimeout(ASYNC_TIMEOUT_MS) { details.completed.await() }
                assertEquals(1, details.inputs.size)
                assertEquals(listOf("observe"), searchesA)
                assertEquals(listOf("observe"), searchesB)
            } finally {
                scene.close()
            }
        }
    }

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
                every { getManga } returns fixture.getManga
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
                withTimeout(ASYNC_TIMEOUT_MS) { fixture.repository.oldStarted.await() }
                scene.render()
                assertFalse(text(scene).contains("old canonical"), "raw result must remain gated")

                setText(scene, "new")
                scene.render()
                click(scene, "Search")
                withTimeout(ASYNC_TIMEOUT_MS) {
                    while (!fixture.repository.newFailed.isCompleted) {
                        scene.render()
                        yield()
                    }
                }
                scene.render()
                assertTrue(text(scene).contains(MR.strings.unknown_error.localized()))
                assertFalse(text(scene).contains("new canonical"))
                click(scene, MR.strings.action_retry.localized())
                withTimeout(ASYNC_TIMEOUT_MS) { fixture.repository.newInserted.await() }
                scene.render()
                assertTrue(text(scene).contains("new canonical"))
                assertNotNull(fixture.mangas.getMangaByUrlAndSourceId("/new/0", source.id))

                fixture.repository.releaseOld.complete(Unit)
                withTimeout(ASYNC_TIMEOUT_MS) { fixture.repository.oldInserted.await() }
                scene.render()
                assertTrue(text(scene).contains("new canonical"))
                assertFalse(text(scene).contains("old canonical"))
            } finally {
                scene.close()
            }
        }
    }

    private fun source(
        id: Long = 9L,
        name: String = "Canonical",
        searches: MutableList<String> = mutableListOf(),
        sharedUrl: String? = null,
        listed: MutableList<SManga>? = null,
        details: DetailProbe? = null,
        resultCount: Int = 12,
    ): CatalogueSource = mockk {
        every { this@mockk.id } returns id
        every { this@mockk.name } returns name
        every { lang } returns "en"
        every { getFilterList() } returns FilterList()
        coEvery { getSearchManga(1, any(), any()) } answers {
            val query = secondArg<String>()
            searches += query
            val items = (0 until resultCount).map { index ->
                SManga.create().apply {
                    url = sharedUrl?.takeIf { index == 0 } ?: if (sharedUrl == null) "/$query/$index" else "/$name/$query/$index"
                    title = if (sharedUrl == null) "$query canonical $index" else "$name listed $index"
                    thumbnail_url = "$name-cover-$index"
                }
            }
            listed?.addAll(items)
            MangasPage(items, false)
        }
        coEvery { getMangaDetails(any()) } coAnswers {
            firstArg<SManga>().also { details?.inputs?.add(it); details?.started?.complete(Unit); details?.release?.await() }
        }
        coEvery { getChapterList(any()) } answers { details?.completed?.complete(Unit); emptyList() }
    }

    private fun nodes(scene: ImageComposeScene, unmerged: Boolean = false): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        flatten(if (unmerged) owner.unmergedRootSemanticsNode else owner.rootSemanticsNode)
    }
    private fun text(scene: ImageComposeScene) = nodes(scene).joinToString { it.config.toString() }
    private fun clickAction(scene: ImageComposeScene, label: String) = requireNotNull(
        nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && it.config.toString().contains(label) }
            .config[SemanticsActions.OnClick].action,
    )
    private fun click(scene: ImageComposeScene, label: String) = clickAction(scene, label).invoke()
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
        val getManga = GetManga(repository)
        val saver = SaveSourceMangaForDetails(NetworkToLocalManga(repository), repository, ChapterRepositoryImpl(handler))
        override fun close() {
            driver.close()
            preferenceRoot.clear()
        }
    }

    private class DetailProbe {
        val inputs = mutableListOf<SManga>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
    }

    private class ControlledRepository(private val delegate: MangaRepository) : MangaRepository by delegate {
        val seenSubscriptions = ConcurrentHashMap.newKeySet<Pair<Long, String>>()
        val activeSubscriptions = ConcurrentHashMap.newKeySet<Pair<Long, String>>()
        val observedRows = ConcurrentHashMap.newKeySet<Triple<Long, String, String>>()
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val oldInserted = CompletableDeferred<Unit>()
        val newFailed = CompletableDeferred<Unit>()
        val newInserted = CompletableDeferred<Unit>()
        private var rejectNew = true
        override fun getMangaByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Manga?> =
            (sourceId to url).let { key ->
                delegate.getMangaByUrlAndSourceIdAsFlow(url, sourceId)
                    .onStart { seenSubscriptions += key; activeSubscriptions += key }
                    .onEach { it?.let { manga -> observedRows += Triple(sourceId, url, manga.title) } }
                    .onCompletion { activeSubscriptions -= key }
            }

        override suspend fun insertNetworkManga(manga: List<Manga>): List<Manga> = when {
            manga.first().url.startsWith("/old/") -> withContext(NonCancellable) {
                oldStarted.complete(Unit)
                releaseOld.await()
                delegate.insertNetworkManga(manga).also { oldInserted.complete(Unit) }
            }
            manga.first().url.startsWith("/new/") && rejectNew -> {
                rejectNew = false
                newFailed.complete(Unit)
                error("controlled materialization failure")
            }
            manga.first().url.startsWith("/new/") -> {
                delegate.insertNetworkManga(manga).also { newInserted.complete(Unit) }
            }
            else -> delegate.insertNetworkManga(manga)
        }
    }
}
