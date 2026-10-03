package eu.kanade.tachiyomi.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.publishLoadedPageListForTest
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.domain.sync.SyncMutationContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.reader.model.ReaderChapterIdentity
import tachiyomi.domain.reader.model.ReaderOpenContext
import tachiyomi.domain.reader.model.ReadingProgressEvent
import tachiyomi.domain.reader.model.ReadingSyncScope
import tachiyomi.domain.reader.model.ReadingSyncSnapshot
import tachiyomi.domain.reader.repository.ReadingProgressRepository
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ReaderSyncSessionWiringTest {
    @Test
    fun `chapter activation opens its causal session before the first user progress`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture()
        try {
            assertTrue(fixture.model.init(1, 2).isSuccess)
            val opened = fixture.repository.opened.tryReceive().getOrNull()
            assertNotNull(opened, "The chapter must capture its baseline before presenting pages")
            assertEquals(2L, opened!!.first)
            fixture.model.onPageSelected(fixture.currentPage(1))
            val recorded = awaitValue(fixture.repository.records)
            assertEquals(opened.second, recorded.snapshot)
            assertEquals(SyncMutationContext.User, recorded.event.syncContext)
            assertFalse(recorded.event.recordHistory)
        } finally {
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `switching back to a chapter opens a new causal session`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture()
        try {
            assertTrue(fixture.model.init(1, 2).isSuccess)
            assertNotNull(fixture.repository.opened.tryReceive().getOrNull())
            fixture.model.loadNextChapter()
            val next = awaitValue(fixture.repository.opened)
            assertEquals(3L, next.first)
            fixture.model.loadPreviousChapter()
            val reopened = awaitValue(fixture.repository.opened)
            assertEquals(2L, reopened.first)
            fixture.model.onPageSelected(fixture.currentPage(0))
            assertEquals(reopened.second, awaitValue(fixture.repository.records).snapshot)
            assertEquals(3, fixture.repository.openCount.get())
        } finally {
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `an old completion retains its captured session after the chapter is reopened`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val completing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = Fixture(
            beforeDuplicateLookup = {
                completing.complete(Unit)
                release.await()
            },
        )
        try {
            assertTrue(fixture.model.init(1, 2).isSuccess)
            val original = fixture.repository.opened.tryReceive().getOrNull()
            assertNotNull(original)
            fixture.model.onPageSelected(fixture.currentPage(2))
            withContext(Dispatchers.Default) { withTimeout(5_000) { completing.await() } }
            fixture.model.loadNextChapter()
            awaitValue(fixture.repository.opened)
            fixture.model.loadPreviousChapter()
            val reopened = awaitValue(fixture.repository.opened)
            release.complete(Unit)
            assertEquals(original!!.second, awaitValue(fixture.repository.records).snapshot)
            fixture.model.onPageSelected(fixture.currentPage(0))
            assertEquals(reopened.second, awaitValue(fixture.repository.records).snapshot)
        } finally {
            release.complete(Unit)
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a delayed adjacent session cannot overwrite a newer current settlement`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val opening = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = Fixture(
            beforeOpen = { chapterId ->
                if (chapterId == 3L) {
                    opening.complete(Unit)
                    release.await()
                }
            },
        )
        try {
            assertTrue(fixture.model.init(1, 2).isSuccess)
            val original = fixture.repository.opened.tryReceive().getOrNull()
            assertNotNull(original)
            val next = requireNotNull(fixture.model.state.value.viewerChapters?.nextChapter)
            materialize(next)
            val existingJobs = fixture.model.viewModelScope.coroutineContext[Job]!!.children.toSet()
            fixture.model.onPageSelected(requireNotNull(next.pages)[1])
            withContext(Dispatchers.Default) { withTimeout(5_000) { opening.await() } }
            val activationJobs = fixture.model.viewModelScope.coroutineContext[Job]!!.children
                .filter { it !in existingJobs }.toList()
            fixture.model.onPageSelected(fixture.currentPage(0))
            assertEquals(original!!.second, awaitValue(fixture.repository.records).snapshot)
            fixture.model.loadPreviousChapter()
            val previous = awaitValue(fixture.repository.opened)
            assertEquals(1L, previous.first)
            release.complete(Unit)
            withContext(Dispatchers.Default) { withTimeout(5_000) { activationJobs.joinAll() } }
            assertEquals(3L, awaitValue(fixture.repository.opened).first)
            assertEquals(1L, fixture.model.state.value.currentChapter?.chapter?.id)
            fixture.model.onPageSelected(fixture.currentPage(1))
            val latest = awaitValue(fixture.repository.records)
            assertEquals(1L, latest.event.chapterId)
            assertEquals(previous.second, latest.snapshot)
        } finally {
            release.complete(Unit)
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `incognito neither opens a sync session nor records progress to replay later`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(incognito = true)
        try {
            assertTrue(fixture.model.init(1, 2).isSuccess)
            fixture.model.onPageSelected(fixture.currentPage(2))
            fixture.incognitoState.set(false)
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    while (fixture.model.state.value.currentPage != 3) kotlinx.coroutines.yield()
                }
            }
            fixture.model.loadNextChapter()
            assertEquals(0, fixture.repository.openCount.get())
            assertNull(fixture.repository.records.tryReceive().getOrNull())
            assertFalse(requireNotNull(fixture.model.state.value.viewerChapters?.prevChapter).chapter.read)
        } finally {
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a page accepted after incognito is enabled cannot be replayed when it is disabled`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture()
        try {
            assertTrue(fixture.model.init(1, 2).isSuccess)
            val existingJobs = fixture.model.viewModelScope.coroutineContext[Job]!!.children.toSet()
            fixture.incognitoState.set(true)
            fixture.model.onPageSelected(fixture.currentPage(2))
            fixture.incognitoState.set(false)
            val pageJobs = fixture.model.viewModelScope.coroutineContext[Job]!!.children
                .filter { it !in existingJobs }.toList()
            withContext(Dispatchers.Default) { withTimeout(5_000) { pageJobs.joinAll() } }
            assertEquals(3, fixture.model.state.value.currentPage)
            assertNull(fixture.repository.records.tryReceive().getOrNull())
            assertFalse(requireNotNull(fixture.model.state.value.currentChapter).chapter.read)
        } finally {
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    private class CapturingRepository(
        val beforeOpen: suspend (Long) -> Unit,
        val opening: suspend (Long) -> ReaderOpenContext,
    ) : ReadingProgressRepository {
        override suspend fun openChapter(target: ReaderChapterIdentity) = opening(target.chapterId)

        val openCount = AtomicInteger()
        val opened = Channel<Pair<Long, ReadingSyncSnapshot>>(Channel.UNLIMITED)
        val records = Channel<Recorded>(Channel.UNLIMITED)

        override suspend fun beginSyncSession(chapterId: Long): ReadingSyncSnapshot {
            val serial = openCount.incrementAndGet()
            beforeOpen(chapterId)
            val snapshot = ReadingSyncSnapshot(ReadingSyncScope("session-$serial", 1, "device", 1))
            opened.send(chapterId to snapshot)
            return snapshot
        }

        override suspend fun record(event: ReadingProgressEvent) {
            records.send(Recorded(event, null))
        }

        override suspend fun record(event: ReadingProgressEvent, snapshot: ReadingSyncSnapshot): ReadingSyncSnapshot {
            records.send(Recorded(event, snapshot))
            return snapshot
        }
    }

    private data class Recorded(val event: ReadingProgressEvent, val snapshot: ReadingSyncSnapshot?)

    private class Fixture(
        incognito: Boolean = false,
        beforeOpen: suspend (Long) -> Unit = {},
        beforeDuplicateLookup: (suspend () -> Unit)? = null,
    ) : AutoCloseable {
        val repository: CapturingRepository = CapturingRepository(beforeOpen) { id ->
            ReaderOpenContext(
                manga,
                chapters.first { it.id == id },
                0,
                if (incognitoState.get()) ReadingSyncSnapshot() else repository.beginSyncSession(id),
                false,
            )
        }
        val incognitoState = AtomicBoolean(incognito)
        private val manga = Manga.create().copy(id = 1, source = 7, chapterFlags = Manga.CHAPTER_SORTING_NUMBER)
        private val chapters = (1L..3L).map { id ->
            Chapter.create().copy(id = id, mangaId = 1, name = "Chapter $id", chapterNumber = id.toDouble())
        }
        private val getChapters = mockk<GetChaptersByMangaId> {
            coEvery { await(1, true) } returns chapters
            coEvery { await(1, false) } coAnswers {
                beforeDuplicateLookup?.invoke()
                chapters
            }
        }
        private val effects = AndroidReaderProgressEffects(
            application = mockk(relaxed = true),
            trackChapter = mockk(relaxed = true),
            updateChapter = mockk(relaxed = true),
            getChaptersByMangaId = getChapters,
            downloadManager = mockk(relaxed = true),
        )
        private val source = mockk<Source>()
        private val loader = mockk<ChapterLoader> {
            coEvery { loadChapter(any()) } coAnswers { materialize(firstArg()) }
            coEvery { loadChapter(any(), any()) } coAnswers { materialize(firstArg()) }
        }
        private val readerPreferences = mockk<ReaderPreferences>(relaxed = true) {
            every { skipRead().get() } returns false
            every { skipFiltered().get() } returns false
            every { skipDupe().get() } returns false
        }
        val model = ReaderViewModel(
            savedState = SavedStateHandle(),
            sourceManager = mockk<SourceManager> {
                every { isInitialized } returns MutableStateFlow(true)
                every { getOrStub(7) } returns source
            },
            downloadManager = mockk(relaxed = true),
            downloadProvider = mockk(relaxed = true),
            imageSaver = mockk(relaxed = true),
            readerPreferences = readerPreferences,
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
            getChaptersByMangaId = getChapters,
            getNextChapters = mockk(relaxed = true),
            upsertHistory = mockk(relaxed = true),
            updateChapter = mockk(relaxed = true),
            recordReadingProgress = RecordReadingProgress(repository),
            setMangaViewerFlags = mockk(relaxed = true),
            getIncognitoState = mockk<GetIncognitoState> { every { await(any()) } answers { incognitoState.get() } },
            pairingCoordinator = emptyChapterPairingCoordinator(),
            progressCoordinator = AndroidReaderProgressCoordinator(effects::onCommitted, {}),
            libraryPreferences = mockk<LibraryPreferences>(relaxed = true) {
                every { markDuplicateReadChapterAsRead().get() } returns if (beforeDuplicateLookup == null) {
                    emptySet()
                } else {
                    setOf(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING)
                }
            },
            chapterLoaderFactory = { _, _ -> loader },
        )

        fun currentPage(index: Int): ReaderPage =
            requireNotNull(requireNotNull(model.state.value.currentChapter).pages)[index]

        override fun close() = model.viewModelScope.cancel()
    }

    private companion object {
        suspend fun <T> awaitValue(channel: Channel<T>): T = withContext(Dispatchers.Default) {
            withTimeout(5_000) { channel.receive() }
        }

        fun materialize(chapter: ReaderChapter) {
            if (chapter.state !is ReaderChapter.State.Loaded) {
                chapter.publishLoadedPageListForTest((0..2).map { ReaderPage(it).apply { this.chapter = chapter } })
            }
        }
    }
}
