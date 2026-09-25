package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.view.children
import androidx.lifecycle.SavedStateHandle
import androidx.viewpager.widget.ViewPager
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.databinding.ReaderActivityBinding
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
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
    private lateinit var chapterLoader: ChapterLoader
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private var host: Activity? = null

    @Before
    fun setUp() = runBlocking {
        previousInjekt = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val preferences = InMemoryPreferenceStore()
        Injekt.addSingleton(ReaderPreferences(preferences))
        Injekt.addSingleton(UiPreferences(preferences))
        Injekt.addSingleton(BasePreferences(RuntimeEnvironment.getApplication() as Application, preferences))
        Injekt.addSingleton(mockk<DownloadManager>(relaxed = true))
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
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
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
        val source = mockk<Source>()
        val sourceManager = mockk<SourceManager> {
            every { isInitialized } returns MutableStateFlow(true)
            every { getOrStub(manga.source) } returns source
        }
        val getManga = mockk<GetManga>()
        coEvery { getManga.await(manga.id) } returns manga
        val getChapters = mockk<GetChaptersByMangaId>()
        coEvery { getChapters.await(manga.id, applyScanlatorFilter = true) } returns chapters
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
        val readerPreferences = mockk<ReaderPreferences>(relaxed = true)
        every { readerPreferences.skipRead().get() } returns false
        every { readerPreferences.skipFiltered().get() } returns false
        every { readerPreferences.skipDupe().get() } returns false
        val basePreferences = mockk<BasePreferences>(relaxed = true)
        every { basePreferences.downloadedOnly().get() } returns false
        val downloadPreferences = mockk<DownloadPreferences>(relaxed = true)
        every { downloadPreferences.autoDownloadWhileReading().get() } returns 0
        every { downloadPreferences.removeAfterReadSlots().get() } returns -1
        val trackPreferences = mockk<TrackPreferences>(relaxed = true)
        every { trackPreferences.autoUpdateTrack().get() } returns false
        val libraryPreferences = mockk<LibraryPreferences>(relaxed = true)
        every { libraryPreferences.markDuplicateReadChapterAsRead().get() } returns emptySet()
        val getIncognitoState = mockk<GetIncognitoState>()
        every { getIncognitoState.await(any()) } returns false
        events = Channel(Channel.UNLIMITED)
        val sqlRepository = SqlDelightReadingProgressRepository(database)
        val repository = object : ReadingProgressRepository by sqlRepository {
            override suspend fun record(
                event: ReadingProgressEvent,
                snapshot: ReadingSyncSnapshot,
            ): ReadingSyncSnapshot {
                val updatedSnapshot = sqlRepository.record(event, snapshot)
                events.send(event)
                return updatedSnapshot
            }
        }
        model = ReaderViewModel(
            savedState = SavedStateHandle(),
            sourceManager = sourceManager,
            downloadManager = mockk(relaxed = true),
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
            libraryPreferences = libraryPreferences,
            chapterLoaderFactory = { _: Manga, _: Source -> chapterLoader },
        )
        assertTrue(model.init(manga.id, initialChapterId = 2).isSuccess)
        current = requireNotNull(model.state.value.currentChapter)
        val activity = Robolectric.buildActivity(ReaderActivity::class.java).get()
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
