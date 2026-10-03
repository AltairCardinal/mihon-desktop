package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rx.Observable

/** Shared AEX-01 contract for the v1.6 Source ABI and old-source bridge. */
class SourceApiBinaryContractTest {

    @Test
    fun `old combined update constructor retains its two argument JVM ABI and complete default`() {
        val manga = SManga.create().apply {
            url = "/legacy"
            title = "Legacy"
        }
        val chapters = listOf(
            SChapter.create().apply {
                url = "/1"
                name = "Chapter 1"
            },
        )
        val constructor = SMangaUpdate::class.java.getConstructor(SManga::class.java, List::class.java)
        val result = constructor.newInstance(manga, chapters)
        assertSame(manga, result.manga)
        assertSame(chapters, result.chapters)
        assertTrue(result.chapterListComplete)
        assertEquals(false, SMangaUpdate(manga, chapters, false).chapterListComplete)
    }

    @Test
    fun `model factories and copies preserve manga and chapter memo`() {
        assertTrue(SManga.create().memo.isEmpty())
        assertTrue(SChapter.create().memo.isEmpty())
        val manga = SManga.create().apply {
            url = "/aex01/manga"
            title = "AEX-01"
            memo = buildJsonObject { put("aex01.manga", "memo") }
        }
        val mangaCopy = manga.copy()
        assertEquals(manga.memo, mangaCopy.memo)
        assertSame(manga.memo, mangaCopy.memo)

        val chapter = SChapter.create().apply {
            url = "/aex01/chapter"
            name = "AEX-01 chapter"
            memo = buildJsonObject { put("aex01.chapter", "memo") }
        }
        val chapterCopy = SChapter.create().apply { copyFrom(chapter) }
        assertEquals(chapter.memo, chapterCopy.memo)
        assertSame(chapter.memo, chapterCopy.memo)
    }

    @Test
    fun `source defaults explicitly reject catalogue operations`() = runTest {
        val source = SourceOnly()
        assertTrue(!source.supportsLatest)
        assertTrue(source.getFilterList().isEmpty())
        assertTrue(runCatching { source.getPopularManga(1) }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { source.getLatestUpdates(1) }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(
            runCatching { source.getSearchManga(1, "query", FilterList()) }
                .exceptionOrNull() is UnsupportedOperationException,
        )
    }

    @Test
    fun `combined update bridge calls only requested legacy operations and preserves identity`() = runTest {
        val source = LegacyBridgeSource()
        val manga = testManga()
        val chapters = listOf(testChapter("existing"))

        listOf(
            false to false,
            false to true,
            true to false,
            true to true,
        ).forEach { (fetchDetails, fetchChapters) ->
            source.reset()
            val result = source.getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)
            if (fetchDetails) {
                assertEquals("updated", result.manga.title)
                assertEquals(1, source.detailsCalls)
            } else {
                assertSame(manga, result.manga)
                assertEquals(0, source.detailsCalls)
            }
            if (fetchChapters) {
                assertEquals(listOf("updated"), result.chapters.map(SChapter::name))
                assertEquals(1, source.chapterCalls)
            } else {
                assertSame(chapters, result.chapters)
                assertEquals(0, source.chapterCalls)
            }
        }
    }

    @Test
    fun `combined update starts both requested legacy operations before awaiting either`() = runTest {
        val source = ConcurrentLegacyBridgeSource()
        val manga = testManga()
        val chapters = listOf(testChapter("existing"))
        val update = async {
            source.getMangaUpdate(manga, chapters, fetchDetails = true, fetchChapters = true)
        }

        source.detailsStarted.await()
        source.chaptersStarted.await()
        source.detailsRelease.complete(Unit)
        source.chaptersRelease.complete(Unit)

        val result = update.await()
        assertEquals("updated", result.manga.title)
        assertEquals(listOf("updated"), result.chapters.map(SChapter::name))
    }

    @Test
    fun `combined update cancellation cancels both requested legacy operations`() = runTest {
        val source = ConcurrentLegacyBridgeSource()
        val update = async {
            source.getMangaUpdate(testManga(), listOf(testChapter("existing")), true, true)
        }

        source.detailsStarted.await()
        source.chaptersStarted.await()
        update.cancelAndJoin()

        source.detailsFinished.await()
        source.chaptersFinished.await()
    }

    @Test
    fun `combined update bridge propagates source exception and cancellation`() = runTest {
        val manga = testManga()
        val chapters = listOf(testChapter("existing"))
        val failure = runCatching {
            LegacyBridgeSource(detailsFailure = true).getMangaUpdate(manga, chapters, true, false)
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("details failed", failure?.message)

        val cancellation = runCatching {
            LegacyBridgeSource(chaptersFailure = true).getMangaUpdate(manga, chapters, false, true)
        }.exceptionOrNull()
        assertTrue(cancellation is CancellationException)
    }

    @Test
    fun `catalogue defaults preserve legacy pagination and filter dispatch`() = runTest {
        val source = LegacyCatalogueSource()
        assertEquals("popular-2", source.getPopularManga(2).mangas.single().title)
        assertEquals("latest-1", source.getLatestUpdates(1).mangas.single().title)
        assertEquals(
            "search-query-3",
            source.getSearchManga(3, "query", FilterList(Filter.Header("AEX-01"))).mangas.single().title,
        )
        assertEquals(1, source.lastFilterSize)

        listOf(
            false to false,
            false to true,
            true to false,
            true to true,
        ).forEach { (fetchDetails, fetchChapters) ->
            source.resetUpdateCalls()
            val result = source.getMangaUpdate(
                testManga(),
                listOf(testChapter("existing")),
                fetchDetails,
                fetchChapters,
            )
            if (fetchDetails) {
                assertEquals("catalogue-updated", result.manga.title)
                assertEquals(1, source.detailsCalls)
            } else {
                assertEquals(0, source.detailsCalls)
            }
            if (fetchChapters) {
                assertEquals(listOf("catalogue-updated"), result.chapters.map(SChapter::name))
                assertEquals(1, source.chapterCalls)
            } else {
                assertEquals(0, source.chapterCalls)
            }
        }
    }

    @Test
    fun `HttpSource inherits the legacy update bridge for every flag combination`() = runTest {
        val source = LegacyHttpSource()
        val manga = testManga()
        val chapters = listOf(testChapter("existing"))

        listOf(
            false to false,
            false to true,
            true to false,
            true to true,
        ).forEach { (fetchDetails, fetchChapters) ->
            source.reset()
            val result = source.getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)
            if (fetchDetails) {
                assertEquals("http-updated", result.manga.title)
                assertEquals(1, source.detailsCalls)
            } else {
                assertSame(manga, result.manga)
                assertEquals(0, source.detailsCalls)
            }
            if (fetchChapters) {
                assertEquals(listOf("http-updated"), result.chapters.map(SChapter::name))
                assertEquals(1, source.chapterCalls)
            } else {
                assertSame(chapters, result.chapters)
                assertEquals(0, source.chapterCalls)
            }
        }
    }

    private class SourceOnly : Source {
        override val id = 0xAE0101L
        override val name = "AEX-01 source-only"
    }

    private class LegacyBridgeSource(
        private val detailsFailure: Boolean = false,
        private val chaptersFailure: Boolean = false,
    ) : Source {
        override val id = 0xAE0102L
        override val name = "AEX-01 legacy bridge"
        var detailsCalls = 0
        var chapterCalls = 0

        fun reset() {
            detailsCalls = 0
            chapterCalls = 0
        }

        @Suppress("DEPRECATION")
        override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
            detailsCalls++
            if (detailsFailure) throw IllegalStateException("details failed")
            return Observable.just(manga.copy().also { it.title = "updated" })
        }

        @Suppress("DEPRECATION")
        override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
            chapterCalls++
            if (chaptersFailure) throw CancellationException("chapters cancelled")
            return Observable.just(listOf(testChapter("updated")))
        }
    }

    private class ConcurrentLegacyBridgeSource : Source {
        override val id = 0xAE0104L
        override val name = "AEX-01 concurrent legacy bridge"
        val detailsStarted = CompletableDeferred<Unit>()
        val chaptersStarted = CompletableDeferred<Unit>()
        val detailsRelease = CompletableDeferred<Unit>()
        val chaptersRelease = CompletableDeferred<Unit>()
        val detailsFinished = CompletableDeferred<Unit>()
        val chaptersFinished = CompletableDeferred<Unit>()

        override suspend fun getMangaDetails(manga: SManga): SManga {
            detailsStarted.complete(Unit)
            try {
                detailsRelease.await()
                return manga.copy().also { it.title = "updated" }
            } finally {
                detailsFinished.complete(Unit)
            }
        }

        override suspend fun getChapterList(manga: SManga): List<SChapter> {
            chaptersStarted.complete(Unit)
            try {
                chaptersRelease.await()
                return listOf(testChapter("updated"))
            } finally {
                chaptersFinished.complete(Unit)
            }
        }
    }

    private class LegacyCatalogueSource : CatalogueSource {
        override val id = 0xAE0103L
        override val name = "AEX-01 catalogue bridge"
        override val lang = "en"
        override val supportsLatest = true
        var lastFilterSize = -1

        override fun getFilterList() = FilterList()

        var detailsCalls = 0
        var chapterCalls = 0

        fun resetUpdateCalls() {
            detailsCalls = 0
            chapterCalls = 0
        }

        @Suppress("DEPRECATION")
        override fun fetchPopularManga(page: Int): Observable<MangasPage> =
            Observable.just(MangasPage(listOf(testManga("popular-$page")), page < 3))

        @Suppress("DEPRECATION")
        override fun fetchLatestUpdates(page: Int): Observable<MangasPage> =
            Observable.just(MangasPage(listOf(testManga("latest-$page")), false))

        @Suppress("DEPRECATION")
        override fun fetchSearchManga(
            page: Int,
            query: String,
            filters: FilterList,
        ): Observable<MangasPage> {
            lastFilterSize = filters.size
            return Observable.just(MangasPage(listOf(testManga("search-$query-$page")), false))
        }

        @Suppress("DEPRECATION")
        override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
            detailsCalls++
            return Observable.just(manga.copy().also { it.title = "catalogue-updated" })
        }

        @Suppress("DEPRECATION")
        override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
            chapterCalls++
            return Observable.just(listOf(testChapter("catalogue-updated")))
        }
    }

    private class LegacyHttpSource : eu.kanade.tachiyomi.source.online.HttpSource() {
        override val baseUrl = "https://aex01.invalid"
        override val name = "AEX-01 HTTP bridge"
        override val lang = "en"
        override val supportsLatest = true

        var detailsCalls = 0
        var chapterCalls = 0

        fun reset() {
            detailsCalls = 0
            chapterCalls = 0
        }

        override fun getFilterList() = FilterList()

        @Suppress("DEPRECATION")
        override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
            detailsCalls++
            return Observable.just(manga.copy().also { it.title = "http-updated" })
        }

        @Suppress("DEPRECATION")
        override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
            chapterCalls++
            return Observable.just(listOf(testChapter("http-updated")))
        }

        override fun chapterPageParse(response: okhttp3.Response): SChapter = testChapter("unused")
    }

    private companion object {
        fun testManga() = SManga.create().apply {
            url = "/aex01/manga"
            title = "original"
        }

        fun testChapter(name: String) = SChapter.create().apply {
            url = "/aex01/$name"
            this.name = name
        }

        fun testManga(title: String): SManga = SManga.create().apply {
            url = "/$title"
            this.title = title
        }
    }
}
