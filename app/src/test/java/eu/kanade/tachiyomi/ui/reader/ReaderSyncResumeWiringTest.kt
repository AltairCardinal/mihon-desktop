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
                assertEquals(0, fixture.repository.lookups)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `continue reading selects the merged chapter and early page even when it is already read`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture()
        try {
            assertTrue(fixture.model.init(1, 1).getOrThrow())
            val chapter = requireNotNull(fixture.model.state.value.currentChapter)
            assertEquals(2L, chapter.chapter.id)
            assertEquals(1, chapter.requestedPage)
            assertEquals(1, fixture.repository.lookups)
            fixture.model.onPageSelected(requireNotNull(chapter.pages)[1])
            assertEquals(fixture.original.snapshot, awaitValue(fixture.repository.records).second)
        } finally {
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `invalid synchronized page starts at chapter beginning and emits feedback`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(pageIndex = 8)
        val events = CopyOnWriteArrayList<ReaderViewModel.Event>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.model.eventFlow.collect(events::add)
        }
        try {
            assertTrue(fixture.model.init(1, 1).getOrThrow())
            val chapter = requireNotNull(fixture.model.state.value.currentChapter)
            assertEquals(2L, chapter.chapter.id)
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
        try {
            assertTrue(fixture.model.init(1, 1).getOrThrow())
            val current = requireNotNull(fixture.model.state.value.currentChapter)
            assertEquals(2L, current.chapter.id)
            assertEquals(1, current.requestedPage)
            val mode = fixture.model.manga!!.viewerFlags
            fixture.repository.candidate = ReadingResumePosition(3, 2, snapshot("another-receipt"))
            fixture.model.onPageSelected(requireNotNull(current.pages)[1])
            val record = awaitValue(fixture.repository.records)
            assertEquals(fixture.original.snapshot, record.second)
            assertEquals(2L, record.first.chapterId)
            assertEquals(1, fixture.repository.lookups)
            assertEquals(2L, fixture.model.state.value.currentChapter!!.chapter.id)
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
                    assertEquals(0, fixture.repository.lookups)
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
    fun `missing exact synchronized chapter retains normal continuation without guessing`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture()
        fixture.repository.candidate = ReadingResumePosition(99, 1, snapshot("unavailable"))
        try {
            assertTrue(fixture.model.init(1, 1).getOrThrow())
            val current = fixture.model.state.value.currentChapter!!
            assertEquals(1L, current.chapter.id)
            fixture.model.onPageSelected(requireNotNull(current.pages)[1])
            assertEquals(snapshot("current-heads"), awaitValue(fixture.repository.records).second)
        } finally {
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    private class Repository(var candidate: ReadingResumePosition?) : ReadingProgressRepository {
        var lookups = 0
        val records = Channel<Pair<ReadingProgressEvent, ReadingSyncSnapshot>>(Channel.UNLIMITED)
        override suspend fun resumePosition(mangaId: Long): ReadingResumePosition? {
            assertEquals(1L, mangaId)
            lookups++
            return candidate
        }
        override suspend fun beginSyncSession(chapterId: Long) = snapshot("current-heads")
        override suspend fun record(event: ReadingProgressEvent) = error("Reader must retain its causal session")
        override suspend fun record(event: ReadingProgressEvent, snapshot: ReadingSyncSnapshot): ReadingSyncSnapshot {
            records.send(event to snapshot)
            return snapshot
        }
    }

    private class Fixture(
        pageIndex: Int = 1,
        receiveDuringLoad: Boolean = false,
        savedState: SavedStateHandle = SavedStateHandle(mapOf("resume" to true)),
        firstChapterLastPageRead: Long = 0,
    ) : AutoCloseable {
        val original = ReadingResumePosition(2, pageIndex, snapshot("adopted-heads"))
        val repository = Repository(original)
        private val manga = Manga.create().copy(id = 1, source = 7, chapterFlags = Manga.CHAPTER_SORTING_NUMBER)
        private val chapters = (1L..3L).map { id ->
            Chapter.create().copy(
                id = id,
                mangaId = 1,
                name = "Chapter $id",
                chapterNumber = id.toDouble(),
                read = id == 2L,
                lastPageRead = if (id == 2L) 2 else firstChapterLastPageRead,
            )
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
                coEvery { await(1, any()) } returns chapters
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
