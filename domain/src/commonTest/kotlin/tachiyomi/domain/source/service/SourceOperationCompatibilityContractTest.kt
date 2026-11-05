package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import mihon.domain.error.AppError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class SourceOperationCompatibilityContractTest {
    @Test
    fun `popular latest and suspended search linkage become typed source failures`() = runBlocking {
        val source = BrokenSource()
        listOf(SourceQuery.Popular, SourceQuery.Latest, SourceQuery.Search("query", FilterList())).forEach { query ->
            val result = SourceMangaSearchService().loadPageResult(source, SourcePageRequest(7L, 1, 2L, query))
            val failure = assertInstanceOf(SourcePageResult.Failure::class.java, result)
            assertInstanceOf(AppError.ExtensionIncompatible::class.java, failure.error)
            assertEquals(SourceRecoveryAction.None, failure.recoveryAction)
        }
    }

    @Test
    fun `details and chapters guard third party execution while validation stays ordinary`() = runBlocking {
        val manga = Manga.create().copy(source = 7L, url = "/book", title = "book")
        val service = SourceMangaUpdateService()
        listOf(true to false, false to true, true to true).forEach { (details, chapters) ->
            val source = BrokenSource()
            val error = assertThrows(SourceCompatibilityException::class.java) {
                runBlocking { service.await(source, manga, emptyList(), details, chapters) }
            }
            assertSame(source.failure, error.cause)
        }
        val incomplete = object : BrokenSource() {
            override suspend fun getMangaUpdate(
                manga: SManga,
                chapters: List<SChapter>,
                fetchDetails: Boolean,
                fetchChapters: Boolean,
            ) =
                SMangaUpdate(manga, chapters, chapterListComplete = false)
        }
        val ordinary = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.await(incomplete, manga, emptyList(), false, true) }
        }
        assertEquals("Source chapter directory is incomplete", ordinary.message)
    }

    @Test
    fun `shared broken catalogue keeps deduplication and healthy independent source completes`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val broken = object : BrokenSource() {
            override suspend fun getMangaUpdate(
                manga: SManga,
                chapters: List<SChapter>,
                fetchDetails: Boolean,
                fetchChapters: Boolean,
            ): SMangaUpdate {
                calls++
                entered.complete(Unit)
                release.await()
                throw failure
            }
        }
        val manga = Manga.create().copy(source = 7L, url = "/book", title = "book")
        val service = SourceMangaUpdateService()
        val first =
            async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { service.awaitSharedCatalog(broken, manga, emptyList(), false) }
            }
        entered.await()
        val second =
            async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { service.awaitSharedCatalog(broken, manga, emptyList(), false) }
            }
        val healthy = object : Source {
            override val id = 8L
            override val name = "healthy"
            override suspend fun getMangaUpdate(
                manga: SManga,
                chapters: List<SChapter>,
                fetchDetails: Boolean,
                fetchChapters: Boolean,
            ) =
                SMangaUpdate(manga, listOf(SChapter.create().apply { url = "/healthy" }))
        }
        val healthyFlight = async { service.awaitSharedCatalog(healthy, manga.copy(source = 8L), emptyList(), false) }
        assertEquals("/healthy", healthyFlight.await().chapters.single().url)
        yield()
        assertFalse(first.isCompleted)
        release.complete(Unit)
        assertInstanceOf(SourceCompatibilityException::class.java, first.await().exceptionOrNull())
        assertInstanceOf(SourceCompatibilityException::class.java, second.await().exceptionOrNull())
        assertEquals(1, calls)
    }

    private open class BrokenSource : Source {
        val failure = NoSuchMethodError("controlled future ABI")
        override val id = 7L
        override val name = "broken fixture"
        override suspend fun getPopularManga(page: Int): MangasPage = throw failure
        override suspend fun getLatestUpdates(page: Int): MangasPage = throw failure
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            yield()
            throw failure
        }
        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): SMangaUpdate =
            throw failure
    }
}
