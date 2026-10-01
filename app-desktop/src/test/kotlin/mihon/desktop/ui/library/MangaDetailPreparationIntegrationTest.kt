package mihon.desktop.ui.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.inMemoryDesktopPreferenceStore
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.domain.SourceMangaRefreshKey
import mihon.desktop.domain.SourceMangaRefreshState
import mihon.desktop.history.HistoryCatalogHttpIntegrationTest
import mihon.desktop.test.http.HistoryCatalogTestSource
import mihon.desktop.test.http.HistoryCatalogTestSourceBridge
import mihon.desktop.test.http.historyCatalogFeed
import mihon.domain.error.AppError
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@Isolated
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class MangaDetailPreparationIntegrationTest {
    @Test
    fun `real detail preparation reports identity failure retains sparse chapters and retry repairs directory`(@TempDir folder: File) = runBlocking {
        val context = initDesktopDIForTest(folder, inMemoryDesktopPreferenceStore())
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val calls = AtomicInteger()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (request.url.encodedPath.endsWith("/feed")) {
                    calls.incrementAndGet()
                    MockResponse(body = historyCatalogFeed())
                } else {
                    MockResponse(body = HistoryCatalogHttpIntegrationTest.DETAILS)
                }
            }
            start()
        }
        val source = HistoryCatalogTestSource(Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>().client, server.url("/").toString().trimEnd('/'))
        HistoryCatalogTestSourceBridge.install(source)
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val compositionJob = SupervisorJob()
        val scene = ImageComposeScene(900, 1100, coroutineContext = coroutineContext + compositionJob + CoroutineExceptionHandler { _, error -> uncaught += error }) {}
        try {
            val mangas = Injekt.get<MangaRepository>()
            val manga = mangas.insertNetworkManga(listOf(Manga.create().copy(source = source.id, url = HistoryCatalogTestSource.MANGA_URL, title = "Detail preparation", initialized = true))).single()
            val chapters = Injekt.get<ChapterRepository>()
            val chapter = chapters.addAll(listOf(Chapter.create().copy(mangaId = manga.id, url = "/chapter/2", name = "Persisted chapter 2", chapterNumber = 2.0, bookmark = true, lastPageRead = 2))).single()
            Injekt.get<UpsertHistory>().await(HistoryUpdate(chapter.id, Date(), 123))
            val historyBefore = Injekt.get<GetHistory>().await(manga.id)
            val other = mangas.insertNetworkManga(listOf(Manga.create().copy(source = source.id, url = "/other", title = "Other"))).single()
            val archive = Injekt.get<CreatorArchiveRepository>()
            archive.upsertSourceWork(source.id, manga.url, other.id, "Other", null, null, null, detailsFetchedAt = null)
            val owner = Injekt.get<SaveSourceMangaForDetails>()
            val key = SourceMangaRefreshKey(source.id, manga.url)
            val dependencies = DesktopUiDependencies.fromInjekt()
            scene.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                        Navigator(MangaDetailScreen(manga.id)) { CurrentScreen() }
                    }
                }
            }
            settle(scene) { owner.refreshStates.value[key] is SourceMangaRefreshState.Failure || uncaught.isNotEmpty() }
            val failed = assertInstanceOf(SourceMangaRefreshState.Failure::class.java, owner.refreshStates.value[key], "Initial detail catalogue check must surface its failure through existing refresh state; uncaught=$uncaught")
            assertInstanceOf(AppError.Storage::class.java, failed.error)
            settle(scene) { nodes(scene).any { labels(it).contains(MR.strings.history_chapter_identity_conflict.localized()) } }
            assertTrue(uncaught.isEmpty(), "Preparation exception must not escape the detail effect")
            assertTrue(nodes(scene).any { labels(it).contains(chapter.name) }, "Sparse persisted chapter remains visible")
            assertEquals(0, calls.get(), "Identity precheck must fail before requesting source")
            assertEquals(listOf(chapter), chapters.getChapterByMangaId(manga.id))
            assertEquals(manga, mangas.getMangaById(manga.id))
            assertEquals(historyBefore, Injekt.get<GetHistory>().await(manga.id))
            // A manual refresh uses the same failure feedback and may not change the foreign binding.
            click(scene, MR.strings.check_for_updates.localized())
            settle(scene) { calls.get() == 1 && owner.refreshStates.value[key] is SourceMangaRefreshState.Failure }
            assertTrue(nodes(scene).any { labels(it).contains(MR.strings.history_chapter_identity_conflict.localized()) })
            assertEquals(listOf(chapter), chapters.getChapterByMangaId(manga.id))
            assertEquals(historyBefore, Injekt.get<GetHistory>().await(manga.id))
            // Correct the independently changed association, then invoke the existing real Retry button.
            archive.upsertSourceWork(source.id, manga.url, manga.id, manga.title, null, null, null, detailsFetchedAt = null)
            click(scene, MR.strings.action_retry.localized())
            settle(scene) { calls.get() == 2 && owner.refreshStates.value[key] == null }
            settle(scene) { nodes(scene).any { labels(it).contains(chapter.name) } }
            assertEquals(3, chapters.getChapterByMangaId(manga.id).size)
            val retained = requireNotNull(chapters.getChapterById(chapter.id))
            assertTrue(retained.bookmark)
            assertEquals(2, retained.lastPageRead)
            assertEquals(chapter.name, retained.name)
            assertEquals(historyBefore, Injekt.get<GetHistory>().await(manga.id))
            assertTrue(nodes(scene).none { labels(it).contains(MR.strings.history_chapter_identity_conflict.localized()) })
            assertTrue(uncaught.isEmpty())
        } finally {
            scene.close()
            compositionJob.cancel()
            HistoryCatalogTestSourceBridge.clear(source)
            server.close()
            context.closeAndJoin()
            Dispatchers.resetMain()
        }
    }

    private suspend fun settle(scene: ImageComposeScene, condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) {
            scene.render().close()
            delay(10)
        }
        scene.render().close()
    }
    private fun click(scene: ImageComposeScene, label: String) {
        val target = nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && flatten(it).any { child -> labels(child).contains(label) } }
        assertTrue(requireNotNull(target.config[SemanticsActions.OnClick].action).invoke())
    }
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun labels(node: SemanticsNode) = (if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text].map { it.text } else emptyList()) + (if (node.config.contains(SemanticsProperties.ContentDescription)) node.config[SemanticsProperties.ContentDescription] else emptyList())
}
