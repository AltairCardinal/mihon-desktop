package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.reader.model.ReadingProgressEvent
import tachiyomi.domain.reader.model.ReadingResumePosition
import tachiyomi.domain.reader.model.ReadingSyncScope
import tachiyomi.domain.reader.model.ReadingSyncSnapshot
import tachiyomi.domain.reader.repository.ReadingProgressRepository
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.CopyOnWriteArrayList

class ReaderSyncResumeWiringTest {
    @Test
    fun `invalid automatic resume waits for explicit confirmation of the same page`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(pageIndex = 8)
        fixture.repository.openedContext = fixture.repository.responses.getValue(1).copy(
            pageIndex = 8,
            snapshot = fixture.original.snapshot,
            resumedWithinChapter = true,
        )
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.model.eventFlow.collect { }
        }
        try {
            assertTrue(fixture.model.init(1, 1).getOrThrow())
            val page = fixture.model.state.value.currentChapter!!.pages!![0]
            fixture.model.onPageSelected(page)
            val automatic = kotlinx.coroutines.withTimeoutOrNull(500) { fixture.repository.records.receive() }
            assertEquals(null, automatic, "an automatic fallback must not become user intent")
            assertTrue(fixture.model.confirmSyncResumePosition(0))
            val record = awaitValue(fixture.repository.records)
            assertEquals(0, record.first.lastPageRead)
            assertEquals(mihon.domain.sync.SyncMutationContext.User, record.first.syncContext)
            assertEquals(fixture.original.snapshot, record.second)
        } finally {
            collector.cancel()
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `post fetch chapter query failure is silent and leaves active reader intact`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val port = tachiyomi.domain.reader.interactor.ReaderCatalogPreparation { emptyList() }
        val fixture = Fixture(catalog = port, failCatalogQuery = true)
        fixture.repository.openedContext = tachiyomi.domain.reader.model.ReaderOpenContext(
            Manga.create().copy(id = 1, source = 7, chapterFlags = Manga.CHAPTER_SORTING_NUMBER),
            fixture.storedChapters.first(),
            1,
            snapshot("catalog-heads"),
            true,
        )
        try {
            assertTrue(fixture.model.init(1, 1).getOrThrow())
            val chapter = fixture.model.state.value.currentChapter
            val pages = chapter!!.pages
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    fixture.queryRefreshStarted.await()
                    val field = ReaderViewModel::class.java.getDeclaredField("catalogJob").apply { isAccessible = true }
                    val job = field.get(fixture.model) as kotlinx.coroutines.Job
                    job.join()
                    org.junit.jupiter.api.Assertions.assertFalse(
                        job.isCancelled,
                        "Background query error escaped Reader failure boundary",
                    )
                }
            }
            org.junit.jupiter.api.Assertions.assertSame(chapter, fixture.model.state.value.currentChapter)
            org.junit.jupiter.api.Assertions.assertSame(pages, chapter.pages)
        } finally {
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `background catalog changes only neighbors and retains active chapter pages window and baseline`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        lateinit var fixture: Fixture
        val port = tachiyomi.domain.reader.interactor.ReaderCatalogPreparation {
            calls++
            entered.complete(Unit)
            release.await()
            fixture.storedChapters =
                (1L..3L).map { id ->
                    Chapter.create().copy(id = id, mangaId = 1, name = "Chapter $id", chapterNumber = id.toDouble())
                }
            fixture.storedChapters
        }
        fixture = Fixture(catalog = port)
        fixture.storedChapters = fixture.storedChapters.take(1)
        fixture.repository.openedContext = tachiyomi.domain.reader.model.ReaderOpenContext(
            Manga.create().copy(id = 1, source = 7, chapterFlags = Manga.CHAPTER_SORTING_NUMBER),
            fixture.storedChapters.single(),
            1,
            snapshot("catalog-heads"),
            true,
        )
        try {
            assertTrue(fixture.model.init(1, 1).getOrThrow())
            val active = requireNotNull(fixture.model.state.value.currentChapter)
            val pages = requireNotNull(active.pages)
            val window = requireNotNull(fixture.model.state.value.chapterWindow)
            fixture.model.onLayoutPageSelected(pages[2])
            withContext(Dispatchers.Default) { withTimeout(5_000) { entered.await() } }
            assertEquals(1, calls)
            assertEquals(null, fixture.model.state.value.viewerChapters!!.nextChapter)
            release.complete(Unit)
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    fixture.model.state.first { it.viewerChapters?.nextChapter?.chapter?.id == 2L }
                }
            }
            org.junit.jupiter.api.Assertions.assertSame(active, fixture.model.state.value.currentChapter)
            org.junit.jupiter.api.Assertions.assertSame(pages, active.pages)
            assertEquals(window.activationSequence, fixture.model.state.value.chapterWindow!!.activationSequence)
            assertEquals(3, fixture.model.state.value.currentPage)
            fixture.model.onPageSelected(pages[2])
            assertEquals(snapshot("catalog-heads"), awaitValue(fixture.repository.records).second)
            assertTrue(fixture.model.init(1, 1).getOrThrow())
            assertEquals(1, calls)
        } finally {
            release.complete(Unit)
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `history selected read chapter cannot reuse its old sync page`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            Fixture(savedState = SavedStateHandle(mapOf("resumeWithinChapter" to true))).use { fixture ->
                fixture.repository.openedContext = tachiyomi.domain.reader.model.ReaderOpenContext(
                    Manga.create().copy(id = 1, source = 7),
                    Chapter.create().copy(id = 2, mangaId = 1, read = true),
                    0,
                    snapshot("current-heads"),
                    false,
                )
                assertTrue(fixture.model.init(1, 2).getOrThrow())
                val chapter = requireNotNull(fixture.model.state.value.currentChapter)
                assertEquals(2L, chapter.chapter.id)
                assertEquals(0, chapter.requestedPage)
                fixture.model.onPageSelected(requireNotNull(chapter.pages)[0])
                assertEquals(snapshot("current-heads"), awaitValue(fixture.repository.records).second)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `ordinary continuation keeps selected unread chapter and its own saved page`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            Fixture(
                savedState = SavedStateHandle(mapOf("resumeWithinChapter" to true)),
                firstChapterLastPageRead = 1,
            ).use { fixture ->
                assertTrue(fixture.model.init(1, 1).getOrThrow())
                val chapter = requireNotNull(fixture.model.state.value.currentChapter)
                assertEquals(1L, chapter.chapter.id)
                assertEquals(1, chapter.requestedPage)
                assertEquals(1, fixture.repository.lookups)
                fixture.model.onPageSelected(requireNotNull(chapter.pages)[1])
                assertEquals(snapshot("current-heads"), awaitValue(fixture.repository.records).second)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `ordinary continuation may adopt synchronized page and snapshot only within selected chapter`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            Fixture(savedState = SavedStateHandle(mapOf("resumeWithinChapter" to true))).use { fixture ->
                fixture.repository.candidate = ReadingResumePosition(1, 2, snapshot("matching-heads"))
                fixture.repository.openedContext = fixture.repository.responses.getValue(
                    1,
                ).copy(pageIndex = 2, snapshot = snapshot("matching-heads"), resumedWithinChapter = true)
                assertTrue(fixture.model.init(1, 1).getOrThrow())
                val chapter = requireNotNull(fixture.model.state.value.currentChapter)
                assertEquals(1L, chapter.chapter.id)
                assertEquals(2, chapter.requestedPage)
                fixture.model.onPageSelected(requireNotNull(chapter.pages)[2])
                assertEquals(snapshot("matching-heads"), awaitValue(fixture.repository.records).second)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `explicit selection of a read chapter starts at first page without adopting sync resume`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            Fixture(savedState = SavedStateHandle(mapOf("resume" to false))).use { fixture ->
                assertTrue(fixture.model.init(1, 2).getOrThrow())
                assertEquals(2L, fixture.model.state.value.currentChapter!!.chapter.id)
                assertEquals(0, fixture.model.state.value.currentChapter!!.requestedPage)
                assertEquals(1, fixture.repository.lookups)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `history preserves selected chapter rather than globally merged read candidate`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture()
        try {
            assertTrue(fixture.model.init(1, 1).getOrThrow())
            val chapter = requireNotNull(fixture.model.state.value.currentChapter)
            assertEquals(1L, chapter.chapter.id)
            assertEquals(0, chapter.requestedPage)
            assertEquals(1, fixture.repository.lookups)
            fixture.model.onPageSelected(requireNotNull(chapter.pages)[1])
            assertEquals(snapshot("current-heads"), awaitValue(fixture.repository.records).second)
        } finally {
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `invalid synchronized page starts at chapter beginning and emits feedback`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(pageIndex = 8)
        fixture.repository.openedContext = fixture.repository.responses.getValue(
            1,
        ).copy(pageIndex = 8, snapshot = fixture.original.snapshot, resumedWithinChapter = true)
        val events = CopyOnWriteArrayList<ReaderViewModel.Event>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.model.eventFlow.collect(events::add)
        }
        try {
            assertTrue(fixture.model.init(1, 1).getOrThrow())
            val chapter = requireNotNull(fixture.model.state.value.currentChapter)
            assertEquals(1L, chapter.chapter.id)
            assertEquals(0, chapter.requestedPage)
            assertEquals(1, events.size, "Invalid synchronized page must produce a user-visible warning")
        } finally {
            collector.cancel()
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `receipt during page loading and active reading cannot replace the adopted session`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(receiveDuringLoad = true)
        fixture.repository.openedContext = fixture.repository.responses.getValue(
            1,
        ).copy(pageIndex = 1, snapshot = fixture.original.snapshot, resumedWithinChapter = true)
        try {
            assertTrue(fixture.model.init(1, 1).getOrThrow())
            val current = requireNotNull(fixture.model.state.value.currentChapter)
            assertEquals(1L, current.chapter.id)
            assertEquals(1, current.requestedPage)
            val mode = fixture.model.manga!!.viewerFlags
            fixture.repository.candidate = ReadingResumePosition(3, 2, snapshot("another-receipt"))
            fixture.model.onPageSelected(requireNotNull(current.pages)[1])
            val record = awaitValue(fixture.repository.records)
            assertEquals(fixture.original.snapshot, record.second)
            assertEquals(1L, record.first.chapterId)
            assertEquals(1, fixture.repository.lookups)
            assertEquals(1L, fixture.model.state.value.currentChapter!!.chapter.id)
            assertEquals(1, current.requestedPage)
            assertEquals(mode, fixture.model.manga!!.viewerFlags)
            assertTrue(fixture.model.init(1, 1).getOrThrow())
            assertEquals(1, fixture.repository.lookups, "Reinitializing a live reader must preserve the active session")
        } finally {
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `explicit chapter opening and saved activity restoration preserve their requested chapter`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            listOf(
                mapOf<String, Any>("resume" to false),
                mapOf<String, Any>("resume" to true, "chapter_id" to 1L, "page_index" to 2),
            ).forEach { state ->
                Fixture(savedState = SavedStateHandle(state)).use { fixture ->
                    assertTrue(fixture.model.init(1, 1).getOrThrow())
                    assertEquals(1L, fixture.model.state.value.currentChapter!!.chapter.id)
                    assertEquals(1, fixture.repository.lookups)
                    if (state.containsKey("page_index")) {
                        assertEquals(2, fixture.model.state.value.currentChapter!!.requestedPage)
                    }
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `missing global synchronized candidate preserves selected chapter without inventing a warning`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture()
        fixture.repository.candidate = ReadingResumePosition(99, 1, snapshot("unavailable"))
        val events = CopyOnWriteArrayList<ReaderViewModel.Event>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.model.eventFlow.collect(events::add)
        }
        try {
            assertTrue(fixture.model.init(1, 1).getOrThrow())
            val current = fixture.model.state.value.currentChapter!!
            assertEquals(1L, current.chapter.id)
            assertTrue(
                events.isEmpty(),
                "An unavailable non-selected global candidate is not a selected reader failure",
            )
            assertEquals(
                1,
                fixture.repository.lookups,
                "Selected opening must stay atomic without a global resume query",
            )
            fixture.model.onPageSelected(requireNotNull(current.pages)[1])
            assertEquals(snapshot("current-heads"), awaitValue(fixture.repository.records).second)
        } finally {
            collector.cancel()
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    internal class Repository(var candidate: ReadingResumePosition?) : ReadingProgressRepository {
        var openedContext: tachiyomi.domain.reader.model.ReaderOpenContext? = null
        var responses: Map<Long, tachiyomi.domain.reader.model.ReaderOpenContext> = emptyMap()
        override suspend fun openChapter(
            target: tachiyomi.domain.reader.model.ReaderChapterIdentity,
        ): tachiyomi.domain.reader.model.ReaderOpenContext? {
            assertEquals(1L, target.mangaId)
            assertEquals(7L, target.sourceId)
            openedContext?.let { assertEquals(it.chapter.id, target.chapterId) }
            lookups++
            return openedContext ?: responses.getValue(target.chapterId)
        }

        var lookups = 0
        val records = Channel<Pair<ReadingProgressEvent, ReadingSyncSnapshot>>(Channel.UNLIMITED)
        override suspend fun resumePosition(mangaId: Long): ReadingResumePosition? {
            assertEquals(1L, mangaId)
            error("Reader must consume atomic selected opening")
        }
        override suspend fun beginSyncSession(chapterId: Long) = snapshot("current-heads")
        override suspend fun record(event: ReadingProgressEvent) = error("Reader must retain its causal session")
        override suspend fun record(event: ReadingProgressEvent, snapshot: ReadingSyncSnapshot): ReadingSyncSnapshot {
            records.send(event to snapshot)
            return snapshot
        }
    }

    internal class Fixture(
        pageIndex: Int = 1,
        receiveDuringLoad: Boolean = false,
        savedState: SavedStateHandle = SavedStateHandle(mapOf("resume" to true)),
        firstChapterLastPageRead: Long = 0,
        catalog: tachiyomi.domain.reader.interactor.ReaderCatalogPreparation? = null,
        failCatalogQuery: Boolean = false,
    ) : AutoCloseable {
        val original = ReadingResumePosition(2, pageIndex, snapshot("adopted-heads"))
        val repository = Repository(original)
        val queryRefreshStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        private val queryCount = java.util.concurrent.atomic.AtomicInteger()
        private val manga = Manga.create().copy(id = 1, source = 7, chapterFlags = Manga.CHAPTER_SORTING_NUMBER)
        var storedChapters = (1L..3L).map { id ->
            Chapter.create().copy(
                id = id,
                mangaId = 1,
                name = "Chapter $id",
                chapterNumber = id.toDouble(),
                read = id == 2L,
                lastPageRead = if (id == 2L) 2 else firstChapterLastPageRead,
            )
        }
        init {
            repository.responses = storedChapters.associate { chapter ->
                chapter.id to
                    tachiyomi.domain.reader.model.ReaderOpenContext(
                        manga,
                        chapter,
                        if (chapter.id == 1L) firstChapterLastPageRead.toInt() else 0,
                        snapshot("current-heads"),
                        false,
                    )
            }
        }
        private val source = mockk<Source>()
        private val loader = ChapterLoader(
            context = mockk<Context>(relaxed = true),
            downloadManager = mockk(relaxed = true),
            downloadProvider = mockk(relaxed = true),
            manga = manga,
            source = source,
            pageLoaderFactory = { chapter ->
                object : PageLoader() {
                    override var isLocal = true
                    override suspend fun getPages(): List<ReaderPage> {
                        if (receiveDuringLoad) {
                            repository.candidate =
                                ReadingResumePosition(3, 2, snapshot("late-heads"))
                        }
                        return (0..2).map { ReaderPage(it).apply { this.chapter = chapter } }
                    }
                }
            },
        )
        val model = ReaderViewModel(
            savedState = savedState,
            sourceManager = mockk<SourceManager> {
                every { isInitialized } returns MutableStateFlow(true)
                every { getOrStub(7) } returns source
            },
            downloadManager = mockk(relaxed = true),
            downloadProvider = mockk(relaxed = true),
            imageSaver = mockk(relaxed = true),
            readerPreferences = mockk<ReaderPreferences>(relaxed = true) {
                every { skipRead().get() } returns false
                every { skipFiltered().get() } returns false
                every { skipDupe().get() } returns false
            },
            basePreferences = mockk<BasePreferences>(relaxed = true) {
                every { downloadedOnly().get() } returns false
            },
            downloadPreferences = mockk<DownloadPreferences>(relaxed = true) {
                every { autoDownloadWhileReading().get() } returns 0
                every { removeAfterReadSlots().get() } returns -1
            },
            trackPreferences = mockk<TrackPreferences>(relaxed = true) {
                every { autoUpdateTrack().get() } returns false
            },
            trackChapter = mockk(relaxed = true),
            getManga = mockk<GetManga> { coEvery { await(1) } returns manga },
            getChaptersByMangaId = mockk<GetChaptersByMangaId> {
                coEvery { await(1, any()) } coAnswers {
                    if (queryCount.incrementAndGet() > 2 && failCatalogQuery) {
                        queryRefreshStarted.complete(Unit)
                        error("chapter database unavailable")
                    }
                    storedChapters
                }
            },
            getNextChapters = mockk(relaxed = true),
            upsertHistory = mockk(relaxed = true),
            updateChapter = mockk(relaxed = true),
            recordReadingProgress = RecordReadingProgress(repository),
            setMangaViewerFlags = mockk(relaxed = true),
            getIncognitoState = mockk(relaxed = true),
            pairingCoordinator = emptyChapterPairingCoordinator(),
            progressCoordinator = emptyReaderProgressCoordinator(),
            libraryPreferences = mockk<LibraryPreferences>(relaxed = true) {
                every { markDuplicateReadChapterAsRead().get() } returns emptySet()
            },
            chapterLoaderFactory = { _, _ -> loader },
            catalogPreparation = { catalog ?: tachiyomi.domain.reader.interactor.ReaderCatalogPreparation { null } },
        )
        override fun close() = model.viewModelScope.cancel()
    }

    private companion object {
        fun snapshot(scope: String) = ReadingSyncSnapshot(ReadingSyncScope(scope, 1, "device", 1))
        suspend fun <T> awaitValue(channel: Channel<T>): T = withContext(Dispatchers.Default) {
            withTimeout(5_000) { channel.receive() }
        }
    }
}
