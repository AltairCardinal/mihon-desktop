package mihon.desktop.history

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mihon.desktop.di.inMemoryDesktopPreferenceStore
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.extension.SourceCallResult
import mihon.desktop.test.http.HistoryCatalogTestSource
import mihon.desktop.test.http.historyCatalogFeed
import mihon.domain.error.AppError
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Isolated
class HistoryCatalogHttpIntegrationTest {
    @Test
    fun `history catalog preserves actual downloaded identity while a concurrent manual refresh keeps detail semantics`(@TempDir folder: File) = runBlocking {
        val context = initDesktopDIForTest(folder, inMemoryDesktopPreferenceStore())
        val server = MockWebServer().apply {
            dispatcher = dispatcher { MockResponse(body = historyCatalogFeed()) }
            start()
        }
        val source = HistoryCatalogTestSource(Injekt.get<NetworkHelper>().client, server.url("/").toString().trimEnd('/'))
        mihon.desktop.test.http.HistoryCatalogTestSourceBridge.install(source)
        try {
            val owner = Injekt.get<SaveSourceMangaForDetails>()
            val manga = owner.awaitListed(
                SManga.create().apply {
                    url = HistoryCatalogTestSource.MANGA_URL
                    title = "Original downloaded title"
                },
                source.id,
            )
            val repository = Injekt.get<ChapterRepository>()
            val chapter = repository.addAll(listOf(Chapter.create().copy(mangaId = manga.id, url = "/chapter/2", name = "Original chapter name", bookmark = true, lastPageRead = 2))).single()
            val resolver = Injekt.get<mihon.desktop.download.DesktopDownloadIdentityResolver>()
            val provider = Injekt.get<mihon.desktop.download.DesktopDownloadProvider>()
            val manager = Injekt.get<mihon.desktop.download.DesktopDownloadManager>()
            val identity = resolver.resolve(manga, chapter)
            val directory = provider.canonicalChapterDownloadDir(identity).apply { mkdirs() }
            val image = directory.resolve("001.png")
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(16, 24, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", image)
            val bytes = image.readBytes()
            assertTrue(manager.isDownloaded(manga.source, identity))
            assertTrue(owner.awaitPrepared(source, manga) is SourceCallResult.Success)
            val after = Injekt.get<MangaRepository>().getMangaById(manga.id)
            val same = requireNotNull(repository.getChapterById(chapter.id))
            assertEquals(manga.title, after.title, "History preparation must preserve existing download identity")
            assertTrue(manager.isDownloaded(after.source, resolver.resolve(after, same)))
            assertArrayEquals(bytes, image.readBytes())
            assertFalse(after.initialized)
            assertTrue(owner.awaitListedForDetails(after.toSourceMangaForTest(), source.id).needsRefresh, "Complete chapter evidence does not initialize details")
            // A manual intent arriving while the same history fetch waits must still persist details.
            repository.update(ChapterUpdate(chapter.id, dateFetch = 0))
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val gated = object : Source by source {
                override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): eu.kanade.tachiyomi.source.model.SMangaUpdate {
                    reached.complete(Unit)
                    release.await()
                    return source.getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)
                }
            }
            val history = async { owner.awaitPrepared(gated, after) }
            reached.await()
            val manual = owner.refreshFromSource(gated, after.toSourceMangaForTest())
            release.complete(Unit)
            assertTrue(history.await() is SourceCallResult.Success)
            manual.join()
            assertEquals(HistoryCatalogTestSource.MANGA_TITLE, Injekt.get<MangaRepository>().getMangaById(manga.id).title)
            assertEquals(4, server.requestCount, "Two directory preparations each fetch details and feed once")
        } finally {
            mihon.desktop.test.http.HistoryCatalogTestSourceBridge.clear(source)
            server.close()
            context.closeAndJoin()
        }
    }

    @Test
    fun `real parser rejects forbidden rate limit server empty missing malformed offline and timeout without partial catalog then retries`(@TempDir folder: File) = runBlocking {
        val responses = listOf(
            MockResponse(code = 403, body = "{}"),
            MockResponse(code = 429, body = "{}"),
            MockResponse(code = 500, body = "{}"),
            MockResponse(body = """{"result":"ok","data":[],"total":0}"""),
            MockResponse(body = """{"result":"ok"}"""),
            MockResponse(body = "{"),
            MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build(),
            MockResponse.Builder().onResponseStart(SocketEffect.Stall).build(),
        )
        responses.forEachIndexed { index, response ->
            val context = initDesktopDIForTest(folder.resolve("case$index"), inMemoryDesktopPreferenceStore())
            val responseOwner = AtomicReference(response)
            val server = MockWebServer().apply {
                dispatcher = dispatcher { responseOwner.get() }
                start()
            }
            try {
                val client = Injekt.get<NetworkHelper>().client.newBuilder().retryOnConnectionFailure(false).readTimeout(400, TimeUnit.MILLISECONDS).build()
                val source = HistoryCatalogTestSource(client, server.url("/").toString().trimEnd('/'))
                val owner = Injekt.get<SaveSourceMangaForDetails>()
                val manga = owner.awaitListed(
                    SManga.create().apply {
                        url = HistoryCatalogTestSource.MANGA_URL
                        title = HistoryCatalogTestSource.MANGA_TITLE
                    },
                    source.id,
                )
                val chapter = Injekt.get<ChapterRepository>().addAll(listOf(Chapter.create().copy(mangaId = manga.id, url = "/chapter/2", name = "Ch.2", lastPageRead = 2, bookmark = true))).single()
                Injekt.get<UpsertHistory>().await(HistoryUpdate(chapter.id, Date(), 123))

                val result = owner.awaitPrepared(source, manga)
                assertTrue(result is SourceCallResult.Error || result is SourceCallResult.Timeout, "case $index: $result")
                val error = when (result) {
                    is SourceCallResult.Error -> result.error
                    is SourceCallResult.Timeout -> result.error
                    else -> error("Expected source failure")
                }
                assertFalse(error is AppError.Storage, "source response case $index must retain source failure classification: $error")
                assertEquals(chapter, Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id).single())
                assertNotEquals("COMPLETE", Injekt.get<DatabaseHandler>().await { author_archiveQueries.getArchiveSourceWorkByKey(source.id, manga.url).executeAsOneOrNull()?.chapter_count_state })
                responseOwner.set(MockResponse(body = historyCatalogFeed()))
                val retried = owner.awaitPrepared(source, manga)
                assertTrue(retried is SourceCallResult.Success, "case $index retry: $retried")
                val preserved = requireNotNull(Injekt.get<ChapterRepository>().getChapterById(chapter.id))
                assertTrue(preserved.bookmark)
                assertEquals(2, preserved.lastPageRead)
                assertEquals(3, Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id).size)
            } finally {
                server.close()
                context.closeAndJoin()
            }
        }
    }

    @Test
    fun `source wait merges only metadata and cannot resurrect a deleted work or switch response identity`(@TempDir folder: File) = runBlocking {
        val context = initDesktopDIForTest(folder, inMemoryDesktopPreferenceStore())
        val server = MockWebServer().apply {
            dispatcher = dispatcher { MockResponse(body = historyCatalogFeed()) }
            start()
        }
        try {
            val delegate = HistoryCatalogTestSource(Injekt.get<NetworkHelper>().client, server.url("/").toString().trimEnd('/'))
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val source = object : Source by delegate {
                override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): eu.kanade.tachiyomi.source.model.SMangaUpdate {
                    reached.complete(Unit)
                    release.await()
                    return delegate.getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)
                }
            }
            val owner = Injekt.get<SaveSourceMangaForDetails>()
            val manga = owner.awaitListed(
                SManga.create().apply {
                    url = HistoryCatalogTestSource.MANGA_URL
                    title = HistoryCatalogTestSource.MANGA_TITLE
                },
                source.id,
            )
            val chapter = Injekt.get<ChapterRepository>().addAll(listOf(Chapter.create().copy(mangaId = manga.id, url = "/chapter/2", name = "Ch.2"))).single()

            val pending = async { owner.awaitPrepared(source, manga) }
            reached.await()
            Injekt.get<ChapterRepository>().update(ChapterUpdate(chapter.id, read = true, bookmark = true, lastPageRead = 3))
            release.complete(Unit)
            assertTrue(pending.await() is SourceCallResult.Success)
            val after = requireNotNull(Injekt.get<ChapterRepository>().getChapterById(chapter.id))
            assertTrue(after.read && after.bookmark)
            assertEquals(3, after.lastPageRead)
            val bad = object : Source by delegate {
                override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean) = eu.kanade.tachiyomi.source.model.SMangaUpdate(manga.apply { url = "/manga/other" }, chapters)
            }

            // Explicit source refresh verifies the returned identity even for a cached directory.
            assertThrows(Exception::class.java) { runBlocking { owner.awaitFromSource(bad, manga.toSourceMangaForTest()) } }
            assertNull(Injekt.get<MangaRepository>().getMangaByUrlAndSourceId("/manga/other", source.id))
            val bindingReached = CompletableDeferred<Unit>()
            val bindingRelease = CompletableDeferred<Unit>()
            val rebound = object : Source by delegate {
                override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): eu.kanade.tachiyomi.source.model.SMangaUpdate {
                    bindingReached.complete(Unit)
                    bindingRelease.await()
                    return delegate.getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)
                }
            }
            val bindingRefresh = async { runCatching { owner.awaitFromSource(rebound, manga.toSourceMangaForTest()) } }
            bindingReached.await()
            val other = Injekt.get<MangaRepository>().insertNetworkManga(listOf(tachiyomi.domain.manga.model.Manga.create().copy(source = source.id, url = "/different", title = "Different"))).single()
            val archive = Injekt.get<tachiyomi.domain.creator.repository.CreatorArchiveRepository>()
            archive.upsertSourceWork(source.id, manga.url, other.id, "Rebound", null, null, null, detailsFetchedAt = null)
            val bindingBefore = Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id)
            bindingRelease.complete(Unit)
            assertTrue(bindingRefresh.await().isFailure, "Network wait must not overwrite an independently rebound catalogue identity")
            assertEquals(bindingBefore, Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id))
            assertEquals(other.id, Injekt.get<DatabaseHandler>().await { author_archiveQueries.getArchiveSourceWorkByKey(source.id, manga.url).executeAsOne().manga_id })
            archive.upsertSourceWork(source.id, manga.url, manga.id, manga.title, null, null, null, detailsFetchedAt = null)
            val deletionReached = CompletableDeferred<Unit>()
            val deletionRelease = CompletableDeferred<Unit>()
            val deleted = object : Source by delegate {
                override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): eu.kanade.tachiyomi.source.model.SMangaUpdate {
                    deletionReached.complete(Unit)
                    deletionRelease.await()
                    return delegate.getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)
                }
            }
            val refresh = async { runCatching { owner.awaitFromSource(deleted, manga.toSourceMangaForTest()) } }
            deletionReached.await()
            Injekt.get<DatabaseHandler>().await(inTransaction = true) { mangasQueries.deleteNonLibraryManga(listOf(source.id), 0) }
            deletionRelease.complete(Unit)
            assertTrue(refresh.await().isFailure)
            assertNull(Injekt.get<MangaRepository>().getMangaByUrlAndSourceId(manga.url, source.id), "Refresh must never recreate a deleted work")
        } finally {
            server.close()
            context.closeAndJoin()
        }
    }

    private fun dispatcher(feed: () -> MockResponse) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = if (request.url.encodedPath.endsWith("/feed")) feed() else MockResponse(body = DETAILS)
    }
    private fun tachiyomi.domain.manga.model.Manga.toSourceMangaForTest() = SManga.create().apply {
        url = this@toSourceMangaForTest.url
        title = this@toSourceMangaForTest.title
    }
    companion object {
        const val DETAILS = """{"result":"ok","data":{"id":"history-catalog","attributes":{"title":{"en":"History catalogue acceptance"},"description":{},"status":"ongoing","tags":[]},"relationships":[]}}"""
    }
}
