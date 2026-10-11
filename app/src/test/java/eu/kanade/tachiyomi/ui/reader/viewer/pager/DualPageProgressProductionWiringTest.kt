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
import kotlinx.coroutines.test.TestScope
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
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers
import tachiyomi.core.common.preference.AndroidPreferenceStore
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
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, qualifiers = "w480dp-h1040dp-xxhdpi")
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
    private var previousFramePause = false
    private lateinit var previousFrameDelay: Duration
    private var removeAfterReadSlots = -1

    @Before
    fun setUp() = runBlocking<Unit> {
        // Automatic Robolectric vsync advances its own clock while idle drains messages;
        // a visible Compose loading spinner otherwise schedules frames without an end.
        previousFramePause = ShadowChoreographer.isPaused()
        previousFrameDelay = ShadowChoreographer.getFrameDelay()
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        previousInjekt = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val application = RuntimeEnvironment.getApplication() as Application
        val sharedPreferences = application.getSharedPreferences("dual-page-progress-fixture", 0)
        sharedPreferences.edit().clear().commit()
        val preferences = AndroidPreferenceStore(application, sharedPreferences)
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
            url = "/",
            title = "Dual page progress",
            chapterFlags = Manga.CHAPTER_SORTING_NUMBER,
        )
        val chapters = (1L..3L).map { id ->
            Chapter.create().copy(
                id = id,
                mangaId = manga.id,
                url = "/$id",
                name = "Chapter $id",
                chapterNumber = id.toDouble(),
            )
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
            "INSERT INTO mangas(_id, source, url, artist, author, description, genre, title, status, thumbnail_url, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, notes, is_syncing) VALUES (1, 7, '/', NULL, NULL, NULL, NULL, 'Dual page progress', 0, NULL, 0, 0, 0, 0, 0, ${Manga.CHAPTER_SORTING_NUMBER}, 0, 0, 0, 0, 0, NULL, 0, '', 0)",
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
        ShadowChoreographer.setFrameDelay(previousFrameDelay)
        ShadowChoreographer.setPaused(previousFramePause)
    }

    @Test
    fun `real delayed adjacent activation retains both partial callbacks on the original token`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val next = prepareNextChapter()
        mountReadyViewer(renderFirstGroup = false, nextChapter = next)
        val pages = requireNotNull(next.pages)
        pages[1].status = Page.State.Ready
        pages[2].status = Page.State.Ready
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { chapterLoader.loadChapter(next, any()) } coAnswers {
            entered.complete(Unit)
            release.await()
        }
        try {
            model.onPageSelected(pages[1], listOf(pages[1]), recordProgress = false)
            withContext(Dispatchers.Default) { withTimeout(5000) { entered.await() } }
            val pending = ReflectionHelpers.getField<Any>(model, "pendingDualViewport")
            model.onDualViewportSettled(pages[1], listOf(pages[1]))
            runCurrent()
            assertTrue(ReflectionHelpers.getField<Boolean>(pending, "settling"))
            model.onDualViewportSettled(pages[1], listOf(pages[1], pages[2]))
            release.complete(Unit)
            val event = requireNotNull(awaitRenderedEvent())
            assertEquals(3L, event.chapterId)
            assertEquals("The second actual callback arrived while activation was still pending", 2, event.lastPageRead)
            withContext(Dispatchers.IO) { progressCoordinator.awaitAccepted(1) }
            runCurrent()
            assertSame(pending, ReflectionHelpers.getField<Any>(model, "pendingDualViewport"))
            assertEquals(2L, database.chaptersQueries.getChapterById(3).executeAsOne().last_page_read)
            assertEquals(1L, database.reading_eventsQueries.countByChapter(3).executeAsOne())
            coVerify(exactly = 1) { chapterLoader.loadChapter(next, any()) }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `actual partner callback can persist while logical target has an image error`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mountReadyViewer(renderFirstGroup = false)
        val pages = requireNotNull(current.pages)
        pages[1].status = Page.State.Error(java.io.IOException("fixture image unavailable"))
        model.onDualViewportSettled(pages[1], listOf(pages[2]))
        assertEquals(2, requireNotNull(awaitRenderedEvent()).lastPageRead)
        withContext(Dispatchers.IO) { progressCoordinator.awaitAccepted(1) }
        runCurrent()
        assertPersistedProgress(2, false)
        assertEquals(1L, database.reading_eventsQueries.countByChapter(2).executeAsOne())
    }

    @Test
    fun `actual VM appends partial viewport progress without replaying page selection`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mountReadyViewer(renderFirstGroup = false)
        val pages = requireNotNull(current.pages)
        val pending = ReflectionHelpers.getField<Any>(model, "pendingDualViewport")
        model.onDualViewportSettled(pages[1], listOf(pages[1]))
        assertEquals(1, requireNotNull(awaitRenderedEvent()).lastPageRead)
        withContext(Dispatchers.IO) { progressCoordinator.awaitAccepted(1) }
        runCurrent()
        assertPersistedProgress(1, false)
        model.onDualViewportSettled(pages[1], listOf(pages[1], pages[2]))
        assertEquals(2, requireNotNull(awaitRenderedEvent()).lastPageRead)
        withContext(Dispatchers.IO) { progressCoordinator.awaitAccepted(1) }
        runCurrent()
        assertSame(
            "Appending pixels must not manufacture a new page selection",
            pending,
            ReflectionHelpers.getField<Any>(model, "pendingDualViewport"),
        )
        assertPersistedProgress(2, false)
        model.onDualViewportSettled(pages[1], listOf(pages[1], pages[2]))
        runCurrent()
        assertEquals(2L, database.reading_eventsQueries.countByChapter(2).executeAsOne())
        model.onPageSelected(pages[3], listOf(pages[3]), recordProgress = false)
        model.onDualViewportSettled(pages[1], listOf(pages[1], pages[2]))
        runCurrent()
        assertEquals(2L, database.reading_eventsQueries.countByChapter(2).executeAsOne())
    }

    @Test
    fun `mounted ready dual holder has no geometry commitment before actual native drawing`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mountReadyViewer(renderFirstGroup = false)
        runCurrent()
        val holder = viewer.pager.children.filterIsInstance<DualPagerPageHolder>()
            .single { it.displayPage.containsPage(requireNotNull(current.pages)[1]) }
        val (owner, ticket) = requireNotNull(holder.presentationScope)
        assertTrue(
            "Mounted ready bytes are not yet actual pixel preparation",
            requireNotNull(owner.frame(ticket)).lockedPageIds.isEmpty(),
        )
        assertTrue(requireNotNull(owner.frame(ticket)).drawnPageIds.isEmpty())
        assertEquals(0L, database.reading_eventsQueries.countByChapter(2).executeAsOne())
    }

    @Test
    fun `ordinary native RTL pager keeps unrendered and failed images out of actual VM progress`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val single = R2LPagerViewer(readerActivity)
        try {
            val holder = mountSingle(single)
            runCurrent()
            assertNull(
                "Selection before image presentation cannot create a reading event",
                withContext(Dispatchers.Default) { withTimeoutOrNull(200) { events.receive() } },
            )
            assertPersistedProgress(0, false)
            holder.onImageLoadError(IllegalStateException("fixture decode failed"))
            runCurrent()
            assertNull(withContext(Dispatchers.Default) { withTimeoutOrNull(200) { events.receive() } })
            assertEquals(0L, database.reading_eventsQueries.countByChapter(2).executeAsOne())
            bindSingle(holder)
            holder.onImageLoaded()
            assertSinglePresented(holder)
            assertEquals(1, requireNotNull(awaitRenderedEvent()).lastPageRead)
            withContext(Dispatchers.IO) { progressCoordinator.awaitAccepted(1) }
            runCurrent()
            assertPersistedProgress(1, false)
            holder.onImageLoaded()
            runCurrent()
            assertEquals(1L, database.reading_eventsQueries.countByChapter(2).executeAsOne())
        } finally {
            single.destroy()
        }
    }

    @Test
    fun `ordinary loaded single replaces content through a fresh identity without reusing the old frame`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val single = L2RPagerViewer(readerActivity)
        try {
            val holder = mountSingle(single)
            bindSingle(holder)
            holder.onImageLoaded()
            assertEquals(1, requireNotNull(awaitRenderedEvent()).lastPageRead)
            val (owner, ticket) = requireNotNull(holder.presentationScope)
            val page = holder.page
            val replacement = readerTestPng(60, 40)
            page.stream = { replacement.inputStream() }
            page.publishHeaderFact()
            runCurrent()
            bindSingle(holder)
            val next = requireNotNull(holder.presentationScope)
            assertFalse("The original Single replacement cannot reuse the old content owner", owner === next.first)
            assertNull(owner.frame(ticket))
            assertEquals(page.encodedContentIdentity(), next.first.identity.content[page.index].contentVersion)
            holder.onImageLoaded()
            assertSinglePresented(holder, expectedReadingEligibility = false)
            assertEquals(1L, database.reading_eventsQueries.countByChapter(2).executeAsOne())
        } finally {
            single.destroy()
        }
    }

    @Test
    fun `ordinary native LTR pager rejects old image completion after actual content replacement`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val single = L2RPagerViewer(readerActivity)
        try {
            val holder = mountSingle(single)
            bindSingle(holder)
            val page = holder.page
            val replacement = readerTestPng(40, 60)
            page.stream = { replacement.inputStream() }
            page.publishHeaderFact()
            runCurrent()
            holder.onImageLoaded()
            runCurrent()
            assertNull(
                "Old decoded pixels cannot commit newly bound content",
                withContext(Dispatchers.Default) { withTimeoutOrNull(200) { events.receive() } },
            )
            assertPersistedProgress(0, false)
            bindSingle(holder)
            holder.onImageLoaded()
            assertSinglePresented(holder)
            assertEquals(1, requireNotNull(awaitRenderedEvent()).lastPageRead)
        } finally {
            single.destroy()
        }
    }

    @Test
    fun `explicit second logical page survives real VM mode change after dual rearrangement`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mountReadyViewer(renderFirstGroup = false, imageBytes = readerTestPng(1125, 1600))
        val pages = requireNotNull(current.pages)
        withContext(Dispatchers.IO) { pages.forEach { it.publishHeaderFact() } }
        runCurrent()
        viewer.pager.measure(
            View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(3120, View.MeasureSpec.EXACTLY),
        )
        viewer.pager.layout(0, 0, 1440, 3120)
        assertTrue(
            "Fixture must expose the paired second logical page",
            viewer.adapter.items.any {
                it is DisplayPage.Double && it.visiblePages.map(ReaderPage::index) == listOf(3, 4)
            },
        )
        viewer.moveToPage(pages[4])
        runCurrent()
        val selected = viewer.adapter.items[viewer.pager.currentItem] as DisplayPage
        assertEquals(
            "Actual selected viewport must be group four-five",
            listOf(3, 4),
            selected.visiblePages.map(ReaderPage::index),
        )
        assertSame(
            "VM must retain explicit logical page five, even inside group four-five",
            pages[4],
            model.currentReaderPage,
        )
        val internalIdleEvents = mutableListOf<String>()
        viewer.pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager.SCROLL_STATE_IDLE &&
                    ReflectionHelpers.getField<Boolean>(viewer, "replacingPairing") && internalIdleEvents.size < 8
                ) {
                    internalIdleEvents +=
                        "owner=${viewer.adapter.session(current)?.location?.pageId?.sourcePageIndex}" +
                        ",native=${viewer.pager.currentItem}"
                }
            }
        })
        viewer.adapter.updatePageDimensions(pages[0], 1800, 1000)
        runCurrent()
        assertSame(
            "Explicit source=${pages[4].index}, VM=${model.currentReaderPage?.index}, " +
                "owner=${viewer.adapter.session(current)?.location?.pageId?.sourcePageIndex}, " +
                "native=${(viewer.adapter.items[viewer.pager.currentItem] as? DisplayPage)?.visiblePages?.map {
                    it.index
                }}, " +
                "internalIdle=$internalIdleEvents",
            pages[4],
            model.currentReaderPage,
        )
        assertTrue("Regrouping must cover native idle reentrancy during replacement", internalIdleEvents.isNotEmpty())
        val rtl = eu.kanade.tachiyomi.ui.reader.setting.ReadingMode.RIGHT_TO_LEFT
        coEvery { getMangaUseCase.await(1) } returns
            requireNotNull(model.manga).copy(viewerFlags = rtl.flagValue.toLong())
        model.setMangaReadingMode(rtl)
        withContext(Dispatchers.IO) {
            withTimeout(5000) {
                model.state.first {
                    it.manga?.viewerFlags ==
                        rtl.flagValue.toLong()
                }
            }
        }
        assertEquals(4, current.requestedPage)
        val single = eu.kanade.tachiyomi.ui.reader.setting.ReadingMode.toViewer(
            model.getMangaReadingMode(),
            readerActivity,
        )
        try {
            model.onViewerLoaded(single)
            single.setChapters(requireNotNull(model.state.value.viewerChapters))
            assertSame(pages[4], model.currentReaderPage)
        } finally {
            single.destroy()
        }
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
                        "rendered=${ReflectionHelpers.getField<Map<ReaderPage, String>>(
                            holder,
                            "renderedPages",
                        ).keys.map {
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
        // The repository emits before its receipt resumes the Main-thread UI update.
        withContext(Dispatchers.Default) { withTimeout(10_000) { progressCoordinator.awaitAccepted(1) } }
        runCurrent()
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

            selectGroup(requireNotNull(next.pages)[0])
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
            selectGroup(requireNotNull(next.pages)[0])
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
            coVerify(exactly = 2) { getChaptersUseCase.await(1, applyScanlatorFilter = true) }
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
            coVerify(exactly = 4) {
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
        val installed = model.state.value.viewer as PagerViewer
        mountSingle(installed, positionWithoutAnimation = true)
        val last = requireNotNull(current.pages)[4]
        val actualAdapter = installed.pager.adapter as PagerViewerAdapter
        installed.pager.setCurrentItem(actualAdapter.items.indexOf(last), false)
        installed.pager.measure(
            View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(3120, View.MeasureSpec.EXACTLY),
        )
        installed.pager.layout(0, 0, 1440, 3120)
        val holder = installed.pager.children.filterIsInstance<PagerPageHolder>().single { it.page === last }
        bindSingle(holder)
        holder.onImageLoaded()
        assertSinglePresented(holder)

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
    fun `Android mounted reader drains accepted writes after close through the shared contract`() = runTest {
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
    fun `same manga A completion preserves B resume and both real sync operations`() = runTest {
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
        selectGroup(requireNotNull(next.pages)[0])
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
    fun `actual viewport resize revokes geometry capabilities while retaining source history and progress`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mountReadyViewer()
        assertEquals(2, requireNotNull(awaitRenderedEvent()).lastPageRead)
        val page = requireNotNull(current.pages)[1]
        val originalHolder = viewer.pager.children.filterIsInstance<DualPagerPageHolder>()
            .first { it.displayPage.containsPage(page) }
        val (owner, ticket) = requireNotNull(originalHolder.presentationScope)
        val pages = requireNotNull(owner.frame(ticket)).unit.pageIds
        val location = owner.location
        viewer.pager.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY),
        )
        viewer.pager.layout(0, 0, 1000, 1800)
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("Old geometry cannot prepare pixels in a different viewport", owner.frame(ticket))
        val nextHolder = viewer.pager.children.filterIsInstance<DualPagerPageHolder>()
            .first { it.displayPage.containsPage(page) }
        val next = requireNotNull(nextHolder.presentationScope)
        assertEquals(pages, requireNotNull(next.first.frame(next.second)).unit.pageIds)
        assertEquals(location, next.first.location)
        assertEquals(1L, database.reading_eventsQueries.countByChapter(2).executeAsOne())
    }

    @Test
    fun `original crop setting starts a new valid geometry context without a reading event`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mountReadyViewer()
        assertEquals(2, requireNotNull(awaitRenderedEvent()).lastPageRead)
        val page = requireNotNull(current.pages)[1]
        val holder = viewer.pager.children.filterIsInstance<DualPagerPageHolder>()
            .first { it.displayPage.containsPage(page) }
        val (owner, ticket) = requireNotNull(holder.presentationScope)
        val originalPages = requireNotNull(owner.frame(ticket)).unit.pageIds
        val location = owner.location
        Injekt.get<ReaderPreferences>().cropBorders().set(true)
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "The original preference event must reach the production PagerConfig",
            viewer.config.imageCropBorders,
        )
        assertNull("The original setting must revoke the old geometry ticket", owner.frame(ticket))
        val replacement = viewer.pager.children.filterIsInstance<DualPagerPageHolder>()
            .first { it.displayPage.containsPage(page) }
        val next = requireNotNull(replacement.presentationScope)
        assertTrue(ticket.frameId.context != next.second.frameId.context)
        assertEquals(originalPages, requireNotNull(next.first.frame(next.second)).unit.pageIds)
        assertEquals(location, next.first.location)
        assertEquals(1L, database.reading_eventsQueries.countByChapter(2).executeAsOne())
    }

    @Test
    fun `normal content snapshot replacement retains the visible original owner and decoded lease`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mountReadyViewer()
        assertEquals(2, requireNotNull(awaitRenderedEvent()).lastPageRead)
        val page = requireNotNull(current.pages)[1]
        val holder = viewer.pager.children.filterIsInstance<DualPagerPageHolder>()
            .first { it.displayPage.containsPage(page) }
        val scope = requireNotNull(holder.presentationScope)
        val originalIdentity = scope.first.identity
        val image = ReflectionHelpers.getField<ReaderPageImageView>(holder, "rightHolder")
        val native = ReflectionHelpers.getField<com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView>(
            image,
            "pageView",
        )
        val replacement = readerTestPng(900, 600)
        page.stream = { replacement.inputStream() }
        withContext(Dispatchers.IO) { page.publishHeaderFact() }
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(scope.first, viewer.adapter.session(current))
        assertEquals(originalIdentity, scope.first.identity)
        assertSame(image, ReflectionHelpers.getField<ReaderPageImageView>(holder, "rightHolder"))
        assertEquals(1125, native.sWidth)
        assertEquals(1600, native.sHeight)
        assertEquals(1L, database.reading_eventsQueries.countByChapter(2).executeAsOne())
        viewer.pager.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY),
        )
        viewer.pager.layout(0, 0, 1000, 1800)
        paintReaderImage(image, readerTestPng(1125, 1600))
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        val next = requireNotNull(holder.presentationScope)
        assertEquals(originalIdentity, next.first.identity)
        assertTrue(next.second.frameId.context != scope.second.frameId.context)
        assertTrue(next.first.identity.pageIds[1] in requireNotNull(next.first.frame(next.second)).drawnPageIds)
        assertEquals(1L, database.reading_eventsQueries.countByChapter(2).executeAsOne())
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
    fun `healthy partial image records itself while failed final partner cannot complete chapter`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pages = requireNotNull(current.pages)
        mountReadyViewer()
        assertEquals(2, requireNotNull(awaitRenderedEvent()).lastPageRead)

        selectGroup(pages[3], failLeft = true)
        assertEquals(Page.State.Ready, pages[4].status)
        assertEquals(3, requireNotNull(awaitRenderedEvent()).lastPageRead)
        assertFalse(current.chapter.read)
        assertPersistedProgress(3, false)
        assertEquals(2L, database.reading_eventsQueries.countByChapter(2).executeAsOne())

        decodeAndSignal(pages[3])
        assertEquals(4, requireNotNull(awaitRenderedEvent()).lastPageRead)
        assertPersistedProgress(4, true)
        assertEquals(3L, database.reading_eventsQueries.countByChapter(2).executeAsOne())
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
    fun `neighbor dual group activates before paint and appends the successful partner retry`() = runTest {
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
        val partial = requireNotNull(awaitRenderedEvent())
        assertEquals(3L, partial.chapterId)
        assertEquals(1, partial.lastPageRead)
        assertFalse(partial.isRead)
        assertEquals(1L, database.reading_eventsQueries.countByChapter(3).executeAsOne())

        decodeAndSignal(nextPages[1])
        val progress = requireNotNull(awaitRenderedEvent())
        assertEquals(3L, progress.chapterId)
        assertEquals(2, progress.lastPageRead)
        assertFalse(progress.isRead)
        assertEquals(2L, database.chaptersQueries.getChapterById(3).executeAsOne().last_page_read)
        assertEquals(2L, database.reading_eventsQueries.countByChapter(3).executeAsOne())
        coVerify(exactly = 1) { chapterLoader.loadChapter(next, any()) }
    }

    @Test
    fun `layout only pairing never records progress and a single page reports itself`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mountReadyViewer(renderFirstGroup = false)
        val pages = requireNotNull(current.pages)
        viewer.onDisplayPageSelected(DisplayPage.Double(pages[3], pages[4]), layoutOnly = true)
        assertTrue(events.tryReceive().isFailure)
        assertFalse(current.chapter.read)
        assertPersistedProgress(0, false)
        assertEquals(0L, database.reading_eventsQueries.countByChapter(2).executeAsOne())

        selectGroup(pages[0], decode = false)
        assertTrue(events.tryReceive().isFailure)
        decodeAndSignal(pages[0])
        val single = requireNotNull(awaitRenderedEvent())
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

    private suspend fun TestScope.mountSingle(
        single: PagerViewer,
        positionWithoutAnimation: Boolean = false,
    ): PagerPageHolder {
        val pages = requireNotNull(current.pages)
        val bytes = readerTestPng(40, 60)
        pages.forEach { page ->
            page.stream = { bytes.inputStream() }
            page.status = Page.State.Ready
            page.publishHeaderFact()
        }
        current.requestedPage = 1
        model.onViewerLoaded(single)
        single.config.navigationModeChangedListener = null
        if (single.pager.parent == null) {
            host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().get().also {
                it.setContentView(single.pager)
            }
        }
        single.setChapters(ViewerChapters(current, null, null))
        repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        single.pager.measure(
            View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(3120, View.MeasureSpec.EXACTLY),
        )
        single.pager.layout(0, 0, 1440, 3120)
        // Initial preference emissions refresh the real adapter. Capture its attached holder only
        // after that initialization, rather than reporting pixels through a disposed predecessor.
        runCurrent()
        // An already-installed Activity has a real restored owner; requestedPage alone cannot
        // replace its logical position. Use the actual viewer action for this fixture's target.
        if (positionWithoutAnimation) {
            val actualAdapter = single.pager.adapter as PagerViewerAdapter
            single.pager.setCurrentItem(actualAdapter.items.indexOf(pages[1]), false)
        } else {
            single.moveToPage(pages[1])
        }
        single.pager.measure(
            View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(3120, View.MeasureSpec.EXACTLY),
        )
        single.pager.layout(0, 0, 1440, 3120)
        // Smooth navigation needs actual ViewRoot frames after automatic vsync is paused.
        var frames = 0
        while (single.pager.children.none { it is PagerPageHolder && it.page === pages[1] } && frames++ < 64) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        assertSame(pages[1], model.currentReaderPage)
        return single.pager.children.filterIsInstance<PagerPageHolder>().single { it.page === pages[1] }
    }

    private suspend fun bindSingle(holder: PagerPageHolder) {
        val method = PagerPageHolder::class.java.declaredMethods.single {
            it.name == "setImage" &&
                it.parameterCount == 1
        }
            .apply { isAccessible = true }
        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            val result = method.invoke(holder, continuation)
            if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else Unit
        }
    }

    private fun assertSinglePresented(holder: PagerPageHolder, expectedReadingEligibility: Boolean = true) {
        val error = ReflectionHelpers.getField<eu.kanade.tachiyomi.databinding.ReaderErrorBinding?>(
            holder,
            "errorLayout",
        )
        assertTrue(
            "Actual single pixels must be rendered, error=${error?.errorMessage?.text}",
            holder.hasRenderedPage(),
        )
        assertTrue(
            "The actual selected holder must carry its original presentation scope",
            holder.presentationScope != null,
        )
        assertSame(holder.page, ReflectionHelpers.getField<ReaderPage>(holder.viewer, "currentPage"))
        assertFalse(
            "Actual native presenter must remain alive",
            ReflectionHelpers.getField<Boolean>(holder.viewer, "destroyed"),
        )
        val actualAdapter = ReflectionHelpers.getField<PagerViewerAdapter>(holder.viewer, "adapter")
        assertSame(
            "Actual current adapter position must identify these rendered pixels",
            holder.page,
            actualAdapter.items.getOrNull(holder.viewer.pager.currentItem),
        )
        assertSame(
            "Actual callback must use the attached selected holder",
            holder,
            holder.viewer.pager.children.filterIsInstance<PagerPageHolder>().firstOrNull { it.item == holder.page },
        )
        assertTrue(
            "The actual single pager must be idle before it reports rendering",
            ReflectionHelpers.getField<Boolean>(holder.viewer, "isIdle"),
        )
        val (owner, ticket) = requireNotNull(holder.presentationScope)
        assertSame(
            "Selected native scope must retain the actual store owner",
            model.dualPagePairings.chapters[holder.page.chapter]?.session,
            owner,
        )
        assertFalse("Selected native owner must still be open", ReflectionHelpers.getField<Boolean>(owner, "closed"))
        assertEquals("Selected native callback must retain its active frame", ticket.frameId, owner.frame(ticket)?.id)
        assertEquals(owner.layoutRevision, ticket.layoutRevision)
        assertEquals(owner.location!!.sequence, ticket.locationSequence)
        assertEquals(owner.targetUnit!!.id, ticket.unitId)
        assertEquals(expectedReadingEligibility, ticket.readingEligible)
        assertTrue(
            "Shared owner must have accepted the original actual single callback; " +
                "layout=${owner.layoutRevision}/${ticket.layoutRevision}, location=${owner.location}, " +
                "VMpending=${ReflectionHelpers.getField<Any?>(model, "pendingDualViewport")}",
            owner.identity.pageIds[holder.page.index] in requireNotNull(owner.frame(ticket)).drawnPageIds,
        )
    }

    private suspend fun mountReadyViewer(
        renderFirstGroup: Boolean = true,
        nextChapter: ReaderChapter? = null,
        imageBytes: ByteArray? = null,
    ) {
        val pages = requireNotNull(current.pages)
        val bytes = imageBytes ?: readerTestPng(1125, 1600)
        pages.forEach { page ->
            page.stream = { bytes.inputStream() }
            page.status = Page.State.Ready
        }
        withContext(Dispatchers.IO) { pages.forEach { it.publishHeaderFact() } }
        // The adjacent fixture has cached encoded content but remains Queued/unrendered. Real
        // bounded header facts establish its groups without manufacturing a displayed image.
        nextChapter?.pages?.let { adjacent ->
            adjacent.forEach { page -> page.stream = { bytes.inputStream() } }
            withContext(Dispatchers.IO) { adjacent.forEach { it.publishHeaderFact() } }
        }
        current.requestedPage = 1
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().get().also {
            it.setContentView(viewer.pager)
        }
        viewer.setChapters(ViewerChapters(current, null, nextChapter))
        repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
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
        if (decode) {
            val displayed = viewer.adapter.items[position] as DisplayPage
            displayed.visiblePages.forEach { it.status = Page.State.Ready }
            decodeAndSignal(page, failLeft)
        }
    }

    /** Bind original encoded content, then paint the mounted production SSIV children. */
    private suspend fun decodeAndSignal(page: ReaderPage, failLeft: Boolean = false) {
        val holder = viewer.pager.children.filterIsInstance<DualPagerPageHolder>()
            .first { it.displayPage.containsPage(page) }
        val bind = DualPagerPageHolder::class.java.declaredMethods.single { it.name == "setImage" }
            .apply { isAccessible = true }
        val readyImages = mutableListOf<Pair<ReaderPageImageView, ByteArray>>()
        val sides = ReflectionHelpers.callInstanceMethod<Map<ReaderPage, Any>>(
            holder,
            "pageSides",
            ReflectionHelpers.ClassParameter.from(DisplayPage::class.java, holder.displayPage),
        )
        for ((visiblePage, side) in sides) {
            val sideName = side.toString()
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                val result = bind.invoke(holder, visiblePage, side, continuation)
                if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else Unit
            }
            val fieldName = if (sideName == "LEFT") "leftHolder" else "rightHolder"
            val subHolder = ReflectionHelpers.getField<ReaderPageImageView>(holder, fieldName)
            if (failLeft && sideName == "LEFT") {
                subHolder.onImageLoadError(IllegalStateException("fixture decode failed"))
            } else {
                readyImages += subHolder to requireNotNull(visiblePage.stream).invoke().use { it.readBytes() }
            }
        }
        viewer.pager.measure(
            View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(3120, View.MeasureSpec.EXACTLY),
        )
        viewer.pager.layout(0, 0, 1440, 3120)
        readyImages.forEach { (image, encoded) -> paintReaderImage(image, encoded) }
        // Drive the actual ViewRoot traversal so its sync barrier releases the image's
        // original posted presentation callback; draining only due messages cannot do that.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
    }

    private suspend fun awaitRenderedEvent(): ReadingProgressEvent? {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            events.tryReceive().getOrNull()?.let { return it }
            withContext(Dispatchers.Default) { delay(20) }
        }
        return null
    }
}
