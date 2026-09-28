package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.view.children
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.viewpager.widget.ViewPager
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.databinding.ReaderActivityBinding
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.AndroidReaderProgressCoordinator
import eu.kanade.tachiyomi.ui.reader.AndroidReaderProgressEffects
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.emptyChapterPairingCoordinator
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.model.publishLoadedPageListForTest
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mihon.data.reader.AcceptedReaderProgressContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.reader.SqlDelightReadingProgressRepository
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.reader.model.ReadingProgressEvent
import tachiyomi.domain.reader.model.ReadingSyncSnapshot
import tachiyomi.domain.reader.repository.ReadingProgressRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DualPageProgressProductionWiringTest {
    private lateinit var previousInjekt: InjektScope
    private lateinit var viewer: DualPageR2LPagerViewer
    private lateinit var events: Channel<ReadingProgressEvent>
    private lateinit var current: ReaderChapter
    private lateinit var model: ReaderViewModel
    private lateinit var newModel: () -> ReaderViewModel
    private lateinit var getMangaUseCase: GetManga
    private lateinit var getChaptersUseCase: GetChaptersByMangaId
    private lateinit var readerActivity: ReaderActivity
    private lateinit var readerController: ActivityController<ReaderActivity>
    private lateinit var chapterLoader: ChapterLoader
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var dbFile: File
    private lateinit var database: Database
    private lateinit var sqlRepository: SqlDelightReadingProgressRepository
    private lateinit var progressCoordinator: AndroidReaderProgressCoordinator
    private lateinit var downloadManager: DownloadManager
    private var host: Activity? = null
    private var releaseMiddleWrite: CompletableDeferred<Unit>? = null
    private var holdBeforeRecordPage: Int? = null
    private var pendingWriteStarted: CompletableDeferred<Unit>? = null
    private var releasePendingWrite: CompletableDeferred<Unit>? = null
    private var failWritePage: Int? = null
    private var failWriteChapterId: Long? = null
    private var removeAfterReadSlots = -1

    @Before
    fun setUp() = runBlocking {
        previousInjekt = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val preferences = InMemoryPreferenceStore()
        val readerPreferences = ReaderPreferences(preferences).apply { skipFiltered().set(false) }
        Injekt.addSingleton(readerPreferences)
        Injekt.addSingleton(UiPreferences(preferences))
        Injekt.addSingleton(BasePreferences(RuntimeEnvironment.getApplication() as Application, preferences))
        Injekt.addSingleton(SecurityPreferences(preferences))
        Injekt.addSingleton(RuntimeEnvironment.getApplication() as Application)
        downloadManager = mockk(relaxed = true)
        every { downloadManager.getQueuedDownloadOrNull(any()) } returns null
        Injekt.addSingleton(downloadManager)
        mockkObject(ImageUtil)
        every { ImageUtil.isAnimatedAndSupported(any()) } returns false
        Dispatchers.setMain(UnconfinedTestDispatcher())

        val manga = Manga.create().copy(
            id = 1,
            source = 7,
            title = "Dual page progress",
            chapterFlags = Manga.CHAPTER_SORTING_NUMBER,
        )
        val chapters = (1L..3L).map { id ->
            Chapter.create().copy(id = id, mangaId = manga.id, name = "Chapter $id", chapterNumber = id.toDouble())
        }
        dbFile = File.createTempFile("reader-progress-", ".sqlite")
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        Database.Schema.create(driver)
        database = Database(
            driver,
            historyAdapter = tachiyomi.data.History.Adapter(DateColumnAdapter),
            mangasAdapter = tachiyomi.data.Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        driver.execute(
            null,
            "INSERT INTO mangas(_id, source, url, artist, author, description, genre, title, status, thumbnail_url, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, notes, is_syncing) VALUES (1, 7, '/', NULL, NULL, NULL, NULL, 'Dual page progress', 0, NULL, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, NULL, 0, '', 0)",
            0,
        )
        chapters.forEach { chapter ->
            driver.execute(
                null,
                "INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read, chapter_number, source_order, date_fetch, date_upload) VALUES (${chapter.id}, 1, '/${chapter.id}', 'Chapter ${chapter.id}', NULL, 0, 0, 0, ${chapter.id}, 0, 0, 0)",
                0,
            )
        }
        driver.execute(
            null,
            "INSERT INTO sync_spaces(space_id, generation, repository_owner, repository_name, " +
                "repository_branch, active) VALUES ('reader-space', 1, 'owner', 'repo', 'main', 1)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO sync_actors(space_id, generation, actor_id, epoch, local_current) " +
                "VALUES ('reader-space', 1, 'reader-device', 1, 1)",
            0,
        )
        val source = mockk<Source>()
        val sourceManager = mockk<SourceManager> {
            every { isInitialized } returns MutableStateFlow(true)
            every { getOrStub(manga.source) } returns source
        }
        Injekt.addSingleton<SourceManager>(sourceManager)
        val getManga = mockk<GetManga>()
        getMangaUseCase = getManga
        coEvery { getManga.await(manga.id) } returns manga
        val getChapters = mockk<GetChaptersByMangaId>()
        getChaptersUseCase = getChapters
        fun persistedChapters() = chapters.map { chapter ->
            val stored = database.chaptersQueries.getChapterById(chapter.id).executeAsOne()
            chapter.copy(read = stored.read, lastPageRead = stored.last_page_read)
        }
        coEvery { getChapters.await(manga.id, applyScanlatorFilter = true) } coAnswers { persistedChapters() }
        coEvery { getChapters.await(manga.id, applyScanlatorFilter = false) } coAnswers { persistedChapters() }
        coEvery { getChapters.awaitOrThrow(manga.id, applyScanlatorFilter = false) } coAnswers { persistedChapters() }
        chapterLoader = mockk<ChapterLoader>()
        val load: (ReaderChapter) -> Unit = { chapter ->
            if (chapter.state !is ReaderChapter.State.Loaded) {
                chapter.publishLoadedPageListForTest(
                    (0..4).map { index -> ReaderPage(index).apply { this.chapter = chapter } },
                )
            }
        }
        coEvery { chapterLoader.loadChapter(any()) } coAnswers { load(firstArg()) }
        coEvery { chapterLoader.loadChapter(any(), any()) } coAnswers { load(firstArg()) }
        val basePreferences = mockk<BasePreferences>(relaxed = true)
        every { basePreferences.downloadedOnly().get() } returns false
        val downloadPreferences = mockk<DownloadPreferences>(relaxed = true)
        every { downloadPreferences.autoDownloadWhileReading().get() } returns 0
        every { downloadPreferences.removeAfterReadSlots().get() } answers { removeAfterReadSlots }
        val trackPreferences = mockk<TrackPreferences>(relaxed = true)
        every { trackPreferences.autoUpdateTrack().get() } returns false
        val libraryPreferences = mockk<LibraryPreferences>(relaxed = true)
        every { libraryPreferences.markDuplicateReadChapterAsRead().get() } returns emptySet()
        val getIncognitoState = mockk<GetIncognitoState>()
        every { getIncognitoState.await(any()) } returns false
        events = Channel(Channel.UNLIMITED)
        sqlRepository = SqlDelightReadingProgressRepository(database)
        val repository = object : ReadingProgressRepository by sqlRepository {
            override suspend fun record(
                event: ReadingProgressEvent,
                snapshot: ReadingSyncSnapshot,
            ): ReadingSyncSnapshot {
                if (event.lastPageRead == holdBeforeRecordPage) {
                    pendingWriteStarted?.complete(Unit)
                    releasePendingWrite?.await()
                }
                if (event.lastPageRead == failWritePage &&
                    (failWriteChapterId == null || event.chapterId == failWriteChapterId)
                ) {
                    error("injected transaction failure")
                }
                val updatedSnapshot = sqlRepository.record(event, snapshot)
                events.send(event)
                if (event.chapterId == 2L && event.lastPageRead == 2) releaseMiddleWrite?.await()
                return updatedSnapshot
            }
        }
        val effects = AndroidReaderProgressEffects(
            RuntimeEnvironment.getApplication() as Application,
            mockk(relaxed = true),
            mockk(relaxed = true),
            getChapters,
            downloadManager,
        )
        progressCoordinator = AndroidReaderProgressCoordinator(effects::onCommitted, {
            downloadManager.deletePendingChapters()
        })
        Injekt.addSingleton(progressCoordinator)
        newModel = {
            ReaderViewModel(
                savedState = SavedStateHandle(),
                sourceManager = sourceManager,
                downloadManager = downloadManager,
                downloadProvider = mockk(relaxed = true),
                imageSaver = mockk(relaxed = true),
                readerPreferences = readerPreferences,
                basePreferences = basePreferences,
                downloadPreferences = downloadPreferences,
                trackPreferences = trackPreferences,
                trackChapter = mockk(relaxed = true),
                getManga = getManga,
                getChaptersByMangaId = getChapters,
                getNextChapters = mockk(relaxed = true),
                upsertHistory = mockk(relaxed = true),
                updateChapter = mockk(relaxed = true),
                recordReadingProgress = RecordReadingProgress(repository),
                setMangaViewerFlags = mockk(relaxed = true),
                getIncognitoState = getIncognitoState,
                pairingCoordinator = emptyChapterPairingCoordinator(),
                libraryPreferences = libraryPreferences,
                chapterLoaderFactory = { _: Manga, _: Source -> chapterLoader },
            )
        }
        model = newModel()
        assertTrue(model.init(manga.id, initialChapterId = 2).isSuccess)
        current = requireNotNull(model.state.value.currentChapter)
        readerController = Robolectric.buildActivity(ReaderActivity::class.java)
        val activity = readerController.get()
        readerActivity = activity
        activity.binding = mockk<ReaderActivityBinding>(relaxed = true)
        ReflectionHelpers.setField(activity, "viewModel\$delegate", lazyOf(model))
        viewer = DualPageR2LPagerViewer(activity)
        viewer.config.navigationModeChangedListener = null
    }

    @After
    fun tearDown() {
        if (::viewer.isInitialized) viewer.destroy()
        host?.finish()
        if (::driver.isInitialized) driver.close()
        if (::dbFile.isInitialized) dbFile.delete()
        Dispatchers.resetMain()
        Injekt = previousInjekt
        unmockkObject(ImageUtil)
    }

    @Test
    fun `queued dual viewport does not persist progress before both images are displayed`() = runBlocking {
        val pages = requireNotNull(current.pages)
        viewer.onDisplayPageSelected(DisplayPage.Double(pages[3], pages[4]))

        assertNull(withTimeoutOrNull(200) { events.receive() })
        assertFalse(current.chapter.read)
        assertPersistedProgress(0, false)
        assertEquals(0L, database.reading_eventsQueries.countByChapter(2).executeAsOne())
    }

    @Test
    fun `unsettled dual viewport does not persist last group during a reversing drag`() = runBlocking {
        val pages = requireNotNull(current.pages)
        pages[3].status = Page.State.Ready
        pages[4].status = Page.State.Ready
        val listener = ReflectionHelpers.getField<ViewPager.SimpleOnPageChangeListener>(viewer, "pagerListener")
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_DRAGGING)
        viewer.onDisplayPageSelected(DisplayPage.Double(pages[3], pages[4]))

        assertNull(withTimeoutOrNull(200) { events.receive() })
        assertFalse(current.chapter.read)
        assertPersistedProgress(0, false)
        assertEquals(0L, database.reading_eventsQueries.countByChapter(2).executeAsOne())
    }

    @Test
    fun `settled dual viewport records last visible page and completes only at actual end`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pages = requireNotNull(current.pages)
        mountReadyViewer()
        val middle = awaitRenderedEvent() ?: error(
            "No middle viewport commit: current=${ReflectionHelpers.getField<Any>(viewer, "currentPage")}, " +
                "position=${viewer.pager.currentItem}, idle=${ReflectionHelpers.getField<Boolean>(
                    viewer,
                    "isIdle",
                )}, " +
                "holders=${viewer.pager.children.filterIsInstance<DualPagerPageHolder>().map { holder ->
                    "${holder.displayPage.visiblePages.map { it.index }}:" +
                        "${holder.hasRenderedVisiblePages()}:" +
                        "rendered=${ReflectionHelpers.getField<Set<ReaderPage>>(holder, "renderedPages").map {
                            it.index
                        }}:" +
                        "error=${ReflectionHelpers.getField<ReaderPage?>(holder, "errorPage")?.index}:" +
                        "message=${ReflectionHelpers.getField<eu.kanade.tachiyomi.databinding.ReaderErrorBinding?>(
                            holder,
                            "errorLayout",
                        )?.errorMessage?.text}:" +
                        "right=${ReflectionHelpers.getField<Any?>(holder, "rightHolder") != null}:" +
                        "left=${ReflectionHelpers.getField<Any?>(holder, "leftHolder") != null}"
                }.toList()}",
        )
        assertEquals(2, middle.lastPageRead)
        assertFalse(middle.isRead)
        assertFalse(current.chapter.read)
        assertPersistedProgress(2, false)
        assertEquals(1L, database.reading_eventsQueries.countByChapter(2).executeAsOne())

        selectGroup(pages[3])
        val last = requireNotNull(awaitRenderedEvent())
        assertEquals(4, last.lastPageRead)
        assertTrue(last.isRead)
        assertTrue(current.chapter.read)
        assertEquals(4, current.chapter.last_page_read)
        assertEquals(3, viewer.activity.viewModel.currentReaderPage?.index)
        assertPersistedProgress(4, true)
        assertEquals(2L, database.reading_eventsQueries.countByChapter(2).executeAsOne())

        selectGroup(pages[1])
        assertTrue(requireNotNull(awaitRenderedEvent()).isRead)
        assertTrue(current.chapter.read)
        assertTrue(database.chaptersQueries.getChapterById(2).executeAsOne().read)
    }

    @Test
    fun `rendered last pair queued behind earlier write survives immediate next chapter selection`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>().also { releaseMiddleWrite = it }
        try {
            val next = prepareNextChapter()
            mountReadyViewer(nextChapter = next)
            assertEquals(2, requireNotNull(awaitRenderedEvent()).lastPageRead)

            // Keep the earlier transaction in flight while the actual mounted final pair settles.
            selectGroup(requireNotNull(current.pages)[3])
            val pending = ReflectionHelpers.getField<Any>(model, "pendingDualViewport")
            assertTrue(ReflectionHelpers.getField<Boolean>(pending, "settled"))
            assertPersistedProgress(2, false)

            selectGroup(requireNotNull(next.pages)[0], decode = false)
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { model.state.first { it.currentChapter === next } }
            }
            release.complete(Unit)
            awaitNextChapterEvent()
            model.onActivityFinish()

            // Regression expectation: an accepted rendered final pair must not be discarded.
            assertPersistedProgress(4, true)
            assertEquals(2L, database.reading_eventsQueries.countByChapter(2).executeAsOne())
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `rendered last pair queued behind earlier write completes when next selection waits`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>().also { releaseMiddleWrite = it }
        try {
            val next = prepareNextChapter()
            mountReadyViewer(nextChapter = next)
            assertEquals(2, requireNotNull(awaitRenderedEvent()).lastPageRead)
            selectGroup(requireNotNull(current.pages)[3])
            val pending = ReflectionHelpers.getField<Any>(model, "pendingDualViewport")
            assertTrue(ReflectionHelpers.getField<Boolean>(pending, "settled"))

            release.complete(Unit)
            val completion = requireNotNull(awaitRenderedEvent())
            assertEquals(4, completion.lastPageRead)
            assertTrue(completion.isRead)
            selectGroup(requireNotNull(next.pages)[0], decode = false)
            awaitNextChapterEvent()
            model.onActivityFinish()

            assertPersistedProgress(4, true)
            assertEquals(2L, database.reading_eventsQueries.countByChapter(2).executeAsOne())
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `accepted last pair followed by a real back turn keeps read while saving the lower position`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>().also { releaseMiddleWrite = it }
        try {
            mountReadyViewer()
            assertEquals(2, requireNotNull(awaitRenderedEvent()).lastPageRead)

            val pages = requireNotNull(current.pages)
            selectGroup(pages[3])
            assertTrue(
                ReflectionHelpers.getField<Boolean>(
                    ReflectionHelpers.getField<Any>(model, "pendingDualViewport"),
                    "settled",
                ),
            )
            selectGroup(pages[1])
            release.complete(Unit)

            val completion = requireNotNull(awaitRenderedEvent())
            val backTurn = requireNotNull(awaitRenderedEvent())
            assertEquals(4, completion.lastPageRead)
            assertTrue(completion.isRead)
            assertEquals(2, backTurn.lastPageRead)
            assertTrue(backTurn.wasRead)
            assertPersistedProgress(2, true)
            assertEquals(3L, database.reading_eventsQueries.countByChapter(2).executeAsOne())
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `accepted last pair survives finish ViewModel clear page recycle and database reopen`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>().also { releaseMiddleWrite = it }
        try {
            mountReadyViewer()
            assertEquals(2, requireNotNull(awaitRenderedEvent()).lastPageRead)
            selectGroup(requireNotNull(current.pages)[3])
            assertTrue(
                ReflectionHelpers.getField<Boolean>(
                    ReflectionHelpers.getField<Any>(model, "pendingDualViewport"),
                    "settled",
                ),
            )

            model.onActivityFinish()
            ViewModelStore().also { store ->
                store.put("reader", model)
                store.clear()
            }
            assertNull(current.pages)
            release.complete(Unit)
            withContext(Dispatchers.Default) { withTimeout(10_000) { progressCoordinator.awaitAccepted(1) } }

            driver.close()
            driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
            database = Database(
                driver,
                historyAdapter = tachiyomi.data.History.Adapter(DateColumnAdapter),
                mangasAdapter = tachiyomi.data.Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            )
            assertPersistedProgress(4, true)
            assertEquals(2L, database.reading_eventsQueries.countByChapter(2).executeAsOne())
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `new Reader initialization waits for the prior accepted write before reading the manga`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>().also { releaseMiddleWrite = it }
        try {
            mountReadyViewer()
            requireNotNull(awaitRenderedEvent())
            selectGroup(requireNotNull(current.pages)[3])
            model.onActivityFinish()
            ViewModelStore().also { store ->
                store.put("old-reader", model)
                store.clear()
            }
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

            val reopened = newModel()
            val opening = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                reopened.init(1, 2)
            }
            assertFalse(opening.isCompleted)
            coVerify(exactly = 1) { getMangaUseCase.await(1) }
            coVerify(exactly = 1) { getChaptersUseCase.await(1, applyScanlatorFilter = true) }
            release.complete(Unit)
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { progressCoordinator.awaitAccepted(1) }
            }
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!opening.isCompleted && System.nanoTime() < deadline) {
                runCurrent()
                withContext(Dispatchers.Default) { delay(20) }
            }
            val completed = opening.isCompleted
            if (!completed) opening.cancel()
            assertTrue("The reopened Reader must finish after the accepted writer drains", completed)
            opening.await().getOrThrow()
            coVerify(exactly = 2) { getMangaUseCase.await(1) }
            coVerify(exactly = 2) {
                getChaptersUseCase.await(1, applyScanlatorFilter = true)
            }
            assertEquals(4L, database.chaptersQueries.getChapterById(2).executeAsOne().last_page_read)
            assertTrue(requireNotNull(reopened.state.value.currentChapter).chapter.read)
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `transaction failure reports once without completing and a later back turn can save`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mountReadyViewer()
        assertEquals(2, requireNotNull(awaitRenderedEvent()).lastPageRead)
        failWritePage = 4
        val failure = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            withTimeout(10_000) { model.eventFlow.first { it == ReaderViewModel.Event.ProgressSaveFailed } }
        }
        selectGroup(requireNotNull(current.pages)[3])
        assertEquals(ReaderViewModel.Event.ProgressSaveFailed, failure.await())
        assertPersistedProgress(2, false)
        assertFalse(current.chapter.read)
        coVerify(exactly = 0) { downloadManager.enqueueChaptersToDelete(any(), any()) }

        selectGroup(requireNotNull(current.pages)[1])
        val recovered = requireNotNull(awaitRenderedEvent())
        assertEquals(2, recovered.lastPageRead)
        assertTrue(recovered.wasRead)
        assertPersistedProgress(2, true)
    }

    @Test
    fun `failed accepted completion restores its cancelled download after reader close`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val started = CompletableDeferred<Unit>().also { pendingWriteStarted = it }
        val release = CompletableDeferred<Unit>().also { releasePendingWrite = it }
        holdBeforeRecordPage = 4
        failWritePage = 4
        val queuedCurrent = mockk<Download>(relaxed = true) {
            every { chapter } returns Chapter.create().copy(id = 2, mangaId = 1)
        }
        try {
            mountReadyViewer()
            assertEquals(2, requireNotNull(awaitRenderedEvent()).lastPageRead)
            ReflectionHelpers.setField(model, "chapterToDownload", queuedCurrent)
            selectGroup(requireNotNull(current.pages)[3])
            withContext(Dispatchers.Default) { withTimeout(10_000) { started.await() } }
            assertSame(queuedCurrent, ReflectionHelpers.getField<Download?>(model, "chapterToDownload"))
            model.onActivityFinish()
            ViewModelStore().also { store ->
                store.put("reader", model)
                store.clear()
            }
            release.complete(Unit)
            withContext(Dispatchers.Default) { withTimeout(10_000) { progressCoordinator.awaitAccepted(1) } }

            assertPersistedProgress(2, false)
            verify(exactly = 1) { downloadManager.addDownloadsToStartOfQueue(listOf(queuedCurrent)) }
            coVerify(exactly = 0) { downloadManager.enqueueChaptersToDelete(any(), any()) }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `successful accepted completion does not restore its cancelled download after reader close`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val started = CompletableDeferred<Unit>().also { pendingWriteStarted = it }
        val release = CompletableDeferred<Unit>().also { releasePendingWrite = it }
        holdBeforeRecordPage = 4
        val queuedCurrent = mockk<Download>(relaxed = true) {
            every { chapter } returns Chapter.create().copy(id = 2, mangaId = 1)
        }
        try {
            mountReadyViewer()
            assertEquals(2, requireNotNull(awaitRenderedEvent()).lastPageRead)
            ReflectionHelpers.setField(model, "chapterToDownload", queuedCurrent)
            selectGroup(requireNotNull(current.pages)[3])
            withContext(Dispatchers.Default) { withTimeout(10_000) { started.await() } }
            assertSame(queuedCurrent, ReflectionHelpers.getField<Download?>(model, "chapterToDownload"))
            model.onActivityFinish()
            ViewModelStore().also { store ->
                store.put("reader", model)
                store.clear()
            }
            release.complete(Unit)
            withContext(Dispatchers.Default) { withTimeout(10_000) { progressCoordinator.awaitAccepted(1) } }

            assertPersistedProgress(4, true)
            verify(exactly = 0) { downloadManager.addDownloadsToStartOfQueue(any()) }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `active ReaderActivity consumes storage failure event and shows the save failure toast`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        ShadowToast.reset()
        readerController.setup()
        runCurrent()
        failWritePage = 4
        model.onPageSelected(requireNotNull(current.pages)[4])

        val deadline = System.nanoTime() + 10_000_000_000L
        while (ShadowToast.getTextOfLatestToast() == null && System.nanoTime() < deadline) {
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            withContext(Dispatchers.Default) { delay(20) }
        }
        assertEquals(
            "Could not save reading progress. Please try again later.",
            ShadowToast.getTextOfLatestToast(),
        )
        assertFalse(database.chaptersQueries.getChapterById(2).executeAsOne().read)
    }

    @Test
    fun `delayed A completion deletes A and preserves B download`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>().also { releaseMiddleWrite = it }
        val actions = mutableListOf<String>()
        removeAfterReadSlots = 0
        coEvery { downloadManager.enqueueChaptersToDelete(any(), any()) } coAnswers {
            actions += "enqueue:${firstArg<List<Chapter>>().single().id}"
        }
        every { downloadManager.deletePendingChapters() } answers { actions += "close" }
        val queuedNext = mockk<Download>(relaxed = true) {
            every { chapter } returns Chapter.create().copy(id = 3, mangaId = 1)
        }
        every { downloadManager.getQueuedDownloadOrNull(3) } returns queuedNext
        try {
            val next = prepareNextChapter()
            mountReadyViewer(nextChapter = next)
            assertEquals(2, requireNotNull(awaitRenderedEvent()).lastPageRead)
            selectGroup(requireNotNull(current.pages)[3])
            selectGroup(requireNotNull(next.pages)[0], decode = false)
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { model.state.first { it.currentChapter === next } }
            }
            assertSame(queuedNext, ReflectionHelpers.getField<Download?>(model, "chapterToDownload"))
            model.onActivityFinish()
            release.complete(Unit)
            withContext(Dispatchers.Default) { withTimeout(10_000) { progressCoordinator.awaitAccepted(1) } }

            assertPersistedProgress(4, true)
            assertEquals(listOf("enqueue:2", "close"), actions)
            assertSame(queuedNext, ReflectionHelpers.getField<Download?>(model, "chapterToDownload"))
            verify(exactly = 1) { downloadManager.addDownloadsToStartOfQueue(listOf(queuedNext)) }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `B completion deletes already accepted A after both same manga writes commit`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val started = CompletableDeferred<Unit>().also { pendingWriteStarted = it }
        val release = CompletableDeferred<Unit>().also { releasePendingWrite = it }
        holdBeforeRecordPage = 4
        removeAfterReadSlots = 1
        try {
            val next = prepareNextChapter()
            mountReadyViewer(nextChapter = next)
            assertEquals(2, requireNotNull(awaitRenderedEvent()).lastPageRead)
            selectGroup(requireNotNull(current.pages)[3])
            withContext(Dispatchers.Default) { withTimeout(10_000) { started.await() } }
            selectGroup(requireNotNull(next.pages)[0], decode = false)
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { model.state.first { it.currentChapter === next } }
            }
            requireNotNull(next.pages).forEach { it.status = Page.State.Ready }
            selectGroup(requireNotNull(next.pages)[3])
            release.complete(Unit)
            withContext(Dispatchers.Default) { withTimeout(10_000) { progressCoordinator.awaitAccepted(1) } }

            assertPersistedProgress(4, true)
            assertTrue(database.chaptersQueries.getChapterById(3).executeAsOne().read)
            coVerify(exactly = 1) {
                downloadManager.enqueueChaptersToDelete(match { it.single().id == 2L }, match { it.id == 1L })
            }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `B completion does not delete A when A accepted write fails`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val started = CompletableDeferred<Unit>().also { pendingWriteStarted = it }
        val release = CompletableDeferred<Unit>().also { releasePendingWrite = it }
        holdBeforeRecordPage = 4
        failWritePage = 4
        failWriteChapterId = 2
        removeAfterReadSlots = 1
        try {
            val next = prepareNextChapter()
            mountReadyViewer(nextChapter = next)
            assertEquals(2, requireNotNull(awaitRenderedEvent()).lastPageRead)
            selectGroup(requireNotNull(current.pages)[3])
            withContext(Dispatchers.Default) { withTimeout(10_000) { started.await() } }
            selectGroup(requireNotNull(next.pages)[0], decode = false)
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { model.state.first { it.currentChapter === next } }
            }
            requireNotNull(next.pages).forEach { it.status = Page.State.Ready }
            selectGroup(requireNotNull(next.pages)[3])
            release.complete(Unit)
            withContext(Dispatchers.Default) { withTimeout(10_000) { progressCoordinator.awaitAccepted(1) } }

            assertPersistedProgress(2, false)
            assertTrue(database.chaptersQueries.getChapterById(3).executeAsOne().read)
            coVerify(exactly = 0) { downloadManager.enqueueChaptersToDelete(any(), any()) }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `Android mounted reader satisfies the shared accepted progress contract`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mountReadyViewer()
        requireNotNull(awaitRenderedEvent())
        val committed = mutableListOf<AcceptedReaderProgressContract.Observation>()
        AcceptedReaderProgressContract.verifyCompletionBackTurnAndClose(
            object : AcceptedReaderProgressContract.Adapter {
                override suspend fun settle(page: Int) {
                    selectGroup(requireNotNull(current.pages)[if (page == 4) 3 else 1])
                    val event = requireNotNull(awaitRenderedEvent())
                    committed += AcceptedReaderProgressContract.Observation(
                        event.lastPageRead,
                        event.totalPages,
                        event.wasRead,
                        event.isRead,
                        event.idempotencyKey,
                    )
                }

                override suspend fun closeAndDrain() {
                    model.onActivityFinish()
                    withContext(Dispatchers.Default) {
                        withTimeout(10_000) { progressCoordinator.awaitAccepted(1) }
                    }
                }

                override fun committed() = committed.toList()
            },
        )
        assertPersistedProgress(2, true)
    }

    @Test
    fun `Android mounted reader drains an accepted blocked write after close through the shared contract`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val started = CompletableDeferred<Unit>().also { pendingWriteStarted = it }
        val release = CompletableDeferred<Unit>().also { releasePendingWrite = it }
        holdBeforeRecordPage = 4
        try {
            mountReadyViewer()
            requireNotNull(awaitRenderedEvent())
            AcceptedReaderProgressContract.verifyPendingWriteSurvivesClose(
                object : AcceptedReaderProgressContract.PendingCloseAdapter {
                    override suspend fun accept() {
                        selectGroup(requireNotNull(current.pages)[3])
                    }

                    override suspend fun awaitWriterStarted() {
                        withContext(Dispatchers.Default) { withTimeout(10_000) { started.await() } }
                    }

                    override suspend fun close() {
                        model.onActivityFinish()
                        ViewModelStore().also { store ->
                            store.put("reader", model)
                            store.clear()
                        }
                    }

                    override suspend fun releaseWrite() {
                        release.complete(Unit)
                    }

                    override suspend fun drain() {
                        withContext(Dispatchers.Default) {
                            withTimeout(10_000) { progressCoordinator.awaitAccepted(1) }
                        }
                    }

                    override fun committedCount(): Int =
                        database.reading_eventsQueries.countByChapter(2).executeAsOne().toInt() - 1
                },
            )
            assertPersistedProgress(4, true)
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `same manga A completion then B viewport preserves B resume and both real sync journal operations`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>().also { releaseMiddleWrite = it }
        try {
            val next = prepareNextChapter()
            mountReadyViewer(nextChapter = next)
            assertEquals(2, requireNotNull(awaitRenderedEvent()).lastPageRead)
            selectGroup(requireNotNull(current.pages)[3])
            selectGroup(requireNotNull(next.pages)[1], decode = false)
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { model.state.first { it.currentChapter === next } }
            }
            release.complete(Unit)
            assertEquals(4, requireNotNull(awaitRenderedEvent()).lastPageRead)
            requireNotNull(next.pages).forEach { it.status = Page.State.Ready }
            decodeAndSignal(requireNotNull(next.pages)[1])
            awaitNextChapterEvent()
            withContext(Dispatchers.Default) { withTimeout(10_000) { progressCoordinator.awaitAccepted(1) } }

            assertPersistedProgress(4, true)
            assertEquals(2L, database.chaptersQueries.getChapterById(3).executeAsOne().last_page_read)
            assertFalse(database.chaptersQueries.getChapterById(3).executeAsOne().read)
            assertEquals(3L, sqlRepository.resumePosition(1)?.chapterId)
            assertEquals(2, sqlRepository.resumePosition(1)?.pageIndex)
            assertEquals(4L, database.sync_journalQueries.getActiveActor().executeAsOne().next_seq)
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `unrendered last pair followed by next chapter does not complete outgoing chapter`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val next = prepareNextChapter()
        mountReadyViewer(nextChapter = next)
        assertEquals(2, requireNotNull(awaitRenderedEvent()).lastPageRead)

        selectGroup(requireNotNull(current.pages)[3], decode = false)
        val pending = ReflectionHelpers.getField<Any>(model, "pendingDualViewport")
        assertFalse(ReflectionHelpers.getField<Boolean>(pending, "settled"))
        selectGroup(requireNotNull(next.pages)[0], decode = false)
        awaitNextChapterEvent()
        model.onActivityFinish()

        assertPersistedProgress(2, false)
        assertEquals(1L, database.reading_eventsQueries.countByChapter(2).executeAsOne())
    }

    private fun prepareNextChapter(): ReaderChapter =
        requireNotNull(model.state.value.viewerChapters?.nextChapter).also { next ->
            next.publishLoadedPageListForTest(
                (0..4).map { ReaderPage(it).apply { chapter = next } },
            )
        }

    private suspend fun awaitNextChapterEvent() {
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                while (events.receive().chapterId != 3L) Unit
            }
        }
        assertEquals(1L, database.reading_eventsQueries.countByChapter(3).executeAsOne())
    }

    @Test
    fun `mounted last pair completes after adjacent chapter window rebuild and decoder callbacks`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pages = requireNotNull(current.pages)
        mountReadyViewer()
        assertEquals(2, requireNotNull(awaitRenderedEvent()).lastPageRead)

        selectGroup(pages[3], decode = false)
        val next = requireNotNull(model.state.value.viewerChapters?.nextChapter)
        next.publishLoadedPageListForTest(
            (0..4).map { ReaderPage(it).apply { chapter = next } },
        )
        viewer.setChapters(ViewerChapters(current, null, next))
        assertNull(withTimeoutOrNull(200) { events.receive() })
        decodeAndSignal(pages[3])
        val displayedHolder = viewer.pager.children.filterIsInstance<DualPagerPageHolder>()
            .first { it.displayPage.containsPage(pages[3]) }
        val selectedDisplay = viewer.adapter.items[viewer.pager.currentItem]
        assertEquals(selectedDisplay, displayedHolder.displayPage)
        assertFalse(selectedDisplay === displayedHolder.displayPage)
        assertTrue(displayedHolder.hasRenderedVisiblePages())
        assertTrue(ReflectionHelpers.getField<Boolean>(viewer, "isIdle"))

        val last = requireNotNull(awaitRenderedEvent())
        assertEquals(4, last.lastPageRead)
        assertTrue(last.isRead)
        assertPersistedProgress(4, true)
    }

    @Test
    fun `image property refresh does not create a second reading event for the same selected group`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pages = requireNotNull(current.pages)
        mountReadyViewer()
        assertEquals(2, requireNotNull(awaitRenderedEvent()).lastPageRead)

        viewer.config.imagePropertyChangedListener?.invoke()
        viewer.pager.measure(
            View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(3120, View.MeasureSpec.EXACTLY),
        )
        viewer.pager.layout(0, 0, 1440, 3120)
        decodeAndSignal(pages[1])

        assertNull(withTimeoutOrNull(200) { events.receive() })
        assertEquals(1L, database.reading_eventsQueries.countByChapter(2).executeAsOne())
        assertPersistedProgress(2, false)
    }

    @Test
    fun `ready source pages with a failed displayed image cannot complete chapter`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pages = requireNotNull(current.pages)
        mountReadyViewer()
        assertEquals(2, requireNotNull(awaitRenderedEvent()).lastPageRead)

        selectGroup(pages[3], failLeft = true)
        assertEquals(Page.State.Ready, pages[4].status)
        assertNull(withTimeoutOrNull(200) { events.receive() })
        assertFalse(current.chapter.read)
        assertPersistedProgress(2, false)
        assertEquals(1L, database.reading_eventsQueries.countByChapter(2).executeAsOne())

        decodeAndSignal(pages[3])
        assertEquals(4, requireNotNull(awaitRenderedEvent()).lastPageRead)
        assertPersistedProgress(4, true)
    }

    @Test
    fun `reversing drag never records the unshown last group`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pages = requireNotNull(current.pages)
        mountReadyViewer()
        assertEquals(2, requireNotNull(awaitRenderedEvent()).lastPageRead)
        val listener = ReflectionHelpers.getField<ViewPager.SimpleOnPageChangeListener>(viewer, "pagerListener")

        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_DRAGGING)
        selectGroup(pages[3])
        assertNull(withTimeoutOrNull(200) { events.receive() })
        selectGroup(pages[1])
        listener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_IDLE)

        val returned = withTimeoutOrNull(200) { events.receive() }
        assertTrue(returned == null || returned.lastPageRead == 2)
        assertFalse(current.chapter.read)
        assertPersistedProgress(2, false)
        assertTrue(database.reading_eventsQueries.countByChapter(2).executeAsOne() in 1L..2L)
    }

    @Test
    fun `neighbor dual group activates before images display and records only after successful retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val next = requireNotNull(model.state.value.viewerChapters?.nextChapter)
        next.publishLoadedPageListForTest(
            (0..4).map { ReaderPage(it).apply { chapter = next } },
        )
        val nextPages = requireNotNull(next.pages)
        mountReadyViewer(renderFirstGroup = false, nextChapter = next)
        selectGroup(nextPages[1], decode = false)

        withContext(Dispatchers.Default) {
            withTimeout(10_000) { model.state.first { it.currentChapter === next } }
        }
        coVerify(exactly = 1) { chapterLoader.loadChapter(next, any()) }
        assertEquals(0L, database.reading_eventsQueries.countByChapter(3).executeAsOne())
        assertEquals(0L, database.chaptersQueries.getChapterById(3).executeAsOne().last_page_read)

        nextPages.forEach { it.status = Page.State.Ready }
        decodeAndSignal(nextPages[1], failLeft = true)
        assertNull(withTimeoutOrNull(200) { events.receive() })
        assertEquals(0L, database.reading_eventsQueries.countByChapter(3).executeAsOne())

        decodeAndSignal(nextPages[1])
        val progress = requireNotNull(awaitRenderedEvent())
        assertEquals(3L, progress.chapterId)
        assertEquals(2, progress.lastPageRead)
        assertFalse(progress.isRead)
        assertEquals(2L, database.chaptersQueries.getChapterById(3).executeAsOne().last_page_read)
        coVerify(exactly = 1) { chapterLoader.loadChapter(next, any()) }
    }

    @Test
    fun `layout only pairing never records progress and a single page reports itself`() = runBlocking {
        val pages = requireNotNull(current.pages)
        viewer.onDisplayPageSelected(DisplayPage.Double(pages[3], pages[4]), layoutOnly = true)
        assertTrue(events.tryReceive().isFailure)
        assertFalse(current.chapter.read)
        assertPersistedProgress(0, false)
        assertEquals(0L, database.reading_eventsQueries.countByChapter(2).executeAsOne())

        viewer.onDisplayPageSelected(DisplayPage.Single(pages[0]))
        val single = withTimeout(5_000) { events.receive() }
        assertEquals(0, single.lastPageRead)
        assertFalse(single.isRead)
        assertPersistedProgress(0, false)
        assertEquals(1L, database.reading_eventsQueries.countByChapter(2).executeAsOne())
    }

    @Test
    fun `either failed image and stale neighbor cannot record the dual viewport`() = runBlocking {
        val pages = requireNotNull(current.pages)
        pages[4].status = Page.State.Error(IllegalStateException("image failed"))
        viewer.onDisplayPageSelected(DisplayPage.Double(pages[3], pages[4]))
        assertNull(withTimeoutOrNull(1_000) { events.receive() })

        pages[4].status = Page.State.Queue
        pages[3].status = Page.State.Error(IllegalStateException("image failed"))
        viewer.onDisplayPageSelected(DisplayPage.Double(pages[3], pages[4]))
        assertNull(withTimeoutOrNull(1_000) { events.receive() })
        pages[3].status = Page.State.Queue

        val staleNeighbor = ReaderChapter(
            Chapter.create().copy(id = 9, mangaId = 1, name = "Neighbor"),
        )
        val stalePage = ReaderPage(4).apply { chapter = staleNeighbor }
        viewer.onDisplayPageSelected(DisplayPage.Double(pages[3], stalePage))
        assertNull(withTimeoutOrNull(1_000) { events.receive() })
        assertFalse(current.chapter.read)
        assertPersistedProgress(0, false)
        assertEquals(0L, database.reading_eventsQueries.countByChapter(2).executeAsOne())
    }

    private fun assertPersistedProgress(page: Int, read: Boolean) {
        database.chaptersQueries.getChapterById(2).executeAsOne().let { chapter ->
            assertEquals(page.toLong(), chapter.last_page_read)
            assertEquals(read, chapter.read)
        }
    }

    private suspend fun mountReadyViewer(renderFirstGroup: Boolean = true, nextChapter: ReaderChapter? = null) {
        val pages = requireNotNull(current.pages)
        val bytes = ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(1125, 1600, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }.toByteArray()
        pages.forEach { page ->
            page.stream = { bytes.inputStream() }
            page.status = Page.State.Ready
        }
        nextChapter?.pages?.forEach { page -> page.stream = { bytes.inputStream() } }
        current.requestedPage = 1
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().get().also {
            it.setContentView(viewer.pager)
        }
        viewer.setChapters(ViewerChapters(current, null, nextChapter))
        val width = View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(3120, View.MeasureSpec.EXACTLY)
        viewer.pager.measure(width, height)
        viewer.pager.layout(0, 0, 1440, 3120)
        assertTrue(
            "Current group holder must be mounted: current=${viewer.pager.currentItem}, " +
                "items=${viewer.adapter.items}, children=${viewer.pager.children.toList()}",
            viewer.pager.children.filterIsInstance<DualPagerPageHolder>()
                .any { it.displayPage.containsPage(pages[1]) },
        )
        if (renderFirstGroup) decodeAndSignal(pages[1])
    }

    private suspend fun selectGroup(page: ReaderPage, failLeft: Boolean = false, decode: Boolean = true) {
        val position = viewer.adapter.items.indexOfFirst { item ->
            item is DisplayPage && item.containsPage(page)
        }
        assertTrue(position >= 0)
        viewer.pager.setCurrentItem(position, false)
        viewer.pager.layout(0, 0, 1440, 3120)
        if (decode) decodeAndSignal(page, failLeft)
    }

    /** Bind the mounted image views, then simulate their external decode completion callbacks. */
    private suspend fun decodeAndSignal(page: ReaderPage, failLeft: Boolean = false) {
        val holder = viewer.pager.children.filterIsInstance<DualPagerPageHolder>()
            .first { it.displayPage.containsPage(page) }
        val sideType = DualPagerPageHolder::class.java.declaredClasses.single { it.simpleName == "Side" }
        val bind = DualPagerPageHolder::class.java.declaredMethods.single { it.name == "setImage" }
            .apply { isAccessible = true }
        for ((visiblePage, sideName) in holder.displayPage.visiblePages.zip(listOf("RIGHT", "LEFT"))) {
            val side = requireNotNull(sideType.enumConstants).single { it.toString() == sideName }
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                val result = bind.invoke(holder, visiblePage, side, continuation)
                if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else Unit
            }
            val subHolder = ReflectionHelpers.getField<ReaderPageImageView>(holder, "${sideName.lowercase()}Holder")
            if (failLeft && sideName == "LEFT") {
                subHolder.onImageLoadError(IllegalStateException("fixture decode failed"))
            } else {
                subHolder.onImageLoaded()
            }
        }
    }

    private suspend fun awaitRenderedEvent(): ReadingProgressEvent? {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            events.tryReceive().getOrNull()?.let { return it }
            withContext(Dispatchers.Default) { delay(20) }
        }
        return null
    }
}
