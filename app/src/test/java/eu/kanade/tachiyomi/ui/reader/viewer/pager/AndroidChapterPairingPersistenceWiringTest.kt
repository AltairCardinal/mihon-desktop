package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.databinding.ReaderActivityBinding
import eu.kanade.tachiyomi.di.AppModule
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.AndroidChapterPairingCoordinator
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.publishLoadedPageListForTest
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import mihon.domain.reader.ChapterPairingRepository
import mihon.domain.reader.ChapterPairingSnapshot
import mihon.domain.reader.ReaderAdjacentChapterEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.chapter.ChapterPairingRepositoryImpl
import tachiyomi.data.reader.SqlDelightReadingProgressRepository
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidChapterPairingPersistenceWiringTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var previousInjekt: InjektScope

    @Before
    fun setUp() {
        previousInjekt = Injekt
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Injekt = previousInjekt
        Dispatchers.resetMain()
    }

    @Test
    fun `real adjust button saves chapter boundary and new model viewer restores containing pair`() = runBlocking {
        val file = temporaryFolder.newFile("android-pairing.db")
        openDatabase(file, create = true).use { first ->
            seed(first.driver)
            session(first).use { session ->
                val pages = requireNotNull(session.model.state.value.currentChapter?.pages)
                session.viewer.setChapters(requireNotNull(session.model.state.value.viewerChapters))
                session.viewer.moveToPage(pages[3])
                assertEquals(setOf(3, 4), visiblePair(session.viewer))
                session.host.setContent {
                    MaterialTheme {
                        session.activity.AppBars(session.model.state.value.copy(menuVisible = true))
                    }
                }
                compose.mainClock.advanceTimeBy(500)
                compose.onNodeWithContentDescription(
                    session.host.stringResource(MR.strings.action_adjust_page_pairing),
                ).assertIsDisplayed().performClick()
                withTimeout(5_000) {
                    while (first.repository.load(2, 1).record?.forcedSinglePages != setOf(3)) yield()
                }
                assertEquals(setOf(4, 5), visiblePair(session.viewer))
                assertEquals(5L, first.database.chaptersQueries.getChapterById(2).executeAsOne().last_page_read)
            }
        }
        openDatabase(file).use { reopened ->
            session(reopened).use { session ->
                session.viewer.setChapters(requireNotNull(session.model.state.value.viewerChapters))
                assertEquals(setOf(4, 5), visiblePair(session.viewer))
                assertEquals(5L, reopened.database.chaptersQueries.getChapterById(2).executeAsOne().last_page_read)
            }
        }
    }

    @Test
    fun `Android AppModule resolves production chapter pairing repository and coordinator`() = runBlocking {
        val file = temporaryFolder.newFile("app-module-pairing.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            Injekt = InjektScope(DefaultRegistrar())
            Injekt.importModule(AppModule(RuntimeEnvironment.getApplication() as Application))
            Injekt.addSingleton<DatabaseHandler>(fixture.handler)
            val repository = Injekt.get<ChapterPairingRepository>()
            assertTrue(repository is ChapterPairingRepositoryImpl)
            val coordinator = Injekt.get<AndroidChapterPairingCoordinator>()
            coordinator.submit(2) { replace(2, 1, 0, 8, setOf(3)) }.await()
            assertEquals(setOf(3), repository.load(2, 1).record?.forcedSinglePages)
        }
    }

    @Test
    fun `restored manual boundary survives a late wide image dimension callback`() = runBlocking {
        val file = temporaryFolder.newFile("wide-image-pairing.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            fixture.repository.replace(2, 1, 0, 8, setOf(3))
            session(fixture).use { reader ->
                val pages = requireNotNull(reader.model.state.value.currentChapter?.pages)
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                reader.viewer.adapter.updatePageDimensions(pages[2], 2200, 1000)
                reader.viewer.moveToPage(pages[2])
                assertEquals(setOf(2), visiblePair(reader.viewer))
                assertEquals(setOf(3), reader.viewer.adapter.appliedForcedSinglePages())
                reader.viewer.moveToPage(pages[4])
                assertEquals(setOf(4, 5), visiblePair(reader.viewer))
                assertEquals(setOf(3), fixture.repository.load(2, 1).record?.forcedSinglePages)
            }
        }
    }

    @Test
    fun `adjacent prefetch restores pairing before next chapter first becomes visible`() = runBlocking {
        val file = temporaryFolder.newFile("adjacent-pairing.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            seedAdjacent(fixture.driver)
            fixture.repository.replace(3, 1, 0, 8, setOf(3))
            session(fixture, includeAdjacent = true, reuseLoadedPages = true).use { reader ->
                val currentWindow = requireNotNull(reader.model.state.value.viewerChapters)
                val next = requireNotNull(currentWindow.nextChapter)
                assertEquals(3L, next.chapter.id)
                reader.model.consumeAdjacentChapterEffect(
                    next,
                    ReaderAdjacentChapterEffect.LoadAdjacentChapterPageList,
                )
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                val prefetchedPage = requireNotNull(next.pages)[4]
                val prefetchedUnit = reader.viewer.adapter.items.filterIsInstance<DisplayPage>()
                    .first { it.containsPage(prefetchedPage) }
                assertEquals(setOf(4, 5), prefetchedUnit.visiblePages.map(ReaderPage::index).toSet())
                reader.model.loadNextChapter()
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                reader.viewer.moveToPage(prefetchedPage)
                assertEquals(setOf(4, 5), visiblePair(reader.viewer))
                assertEquals(0L, fixture.database.chaptersQueries.getChapterById(3).executeAsOne().last_page_read)
            }
        }
    }

    @Test
    fun `failed adjacent prefetch preserves current chapter until navigation`() = runBlocking {
        val file = temporaryFolder.newFile("adjacent-read-failure.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            seedAdjacent(fixture.driver)
            fixture.repository.replace(3, 1, 0, 8, setOf(3))
            val failNextRead = AtomicBoolean(true)
            val nextReads = AtomicInteger()
            val repository = object : ChapterPairingRepository by fixture.repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    if (chapterId == 3L) {
                        nextReads.incrementAndGet()
                        if (failNextRead.get()) throw IOException("next pairing unavailable")
                    }
                    return fixture.repository.load(chapterId, mangaId)
                }
            }
            session(fixture, repository, includeAdjacent = true, reuseLoadedPages = true).use { reader ->
                val window = requireNotNull(reader.model.state.value.viewerChapters)
                val current = window.currChapter
                val next = requireNotNull(window.nextChapter)
                val beforePrefetch = nextReads.get()
                reader.model.consumeAdjacentChapterEffect(next, ReaderAdjacentChapterEffect.LoadAdjacentChapterPageList)
                assertTrue(
                    "prefetch did not attempt a pairing read: before=$beforePrefetch, " +
                        "after=${nextReads.get()}, pages=${next.pages?.size}, " +
                        "state=${next.sharedSessionStateFlow.value.activeChapter.loadState}",
                    nextReads.get() > beforePrefetch,
                )
                assertTrue(reader.model.state.value.currentChapter === current)
                assertNull(reader.model.state.value.dialog)
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                assertTrue(
                    reader.viewer.adapter.items.filterIsInstance<DisplayPage>()
                        .none { group -> group.visiblePages.any { it.chapter === next } },
                )

                reader.model.loadNextChapter()
                assertTrue(reader.model.state.value.currentChapter === current)
                assertTrue(reader.model.state.value.dialog is ReaderViewModel.Dialog.PairingRestoreError)
                reader.host.setContent { MaterialTheme { reader.activity.PairingRestoreControls() } }
                failNextRead.set(false)
                compose.onNodeWithText(reader.host.stringResource(MR.strings.action_retry)).performClick()
                withTimeout(5_000) {
                    while (reader.model.state.value.currentChapter !== next) yield()
                }
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                reader.viewer.moveToPage(requireNotNull(next.pages)[4])
                assertEquals(setOf(4, 5), visiblePair(reader.viewer))
            }
        }
    }

    @Test
    fun `explicit adjacent navigation can use default after a failed background pairing read`() = runBlocking {
        val file = temporaryFolder.newFile("adjacent-default.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            seedAdjacent(fixture.driver)
            fixture.repository.replace(3, 1, 0, 8, setOf(3))
            val repository = object : ChapterPairingRepository by fixture.repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    if (chapterId == 3L) throw IOException("next pairing unavailable")
                    return fixture.repository.load(chapterId, mangaId)
                }
            }
            session(fixture, repository, includeAdjacent = true, reuseLoadedPages = true).use { reader ->
                val current = requireNotNull(reader.model.state.value.currentChapter)
                val next = requireNotNull(reader.model.state.value.viewerChapters?.nextChapter)
                reader.model.consumeAdjacentChapterEffect(next, ReaderAdjacentChapterEffect.LoadAdjacentChapterPageList)
                assertTrue(reader.model.state.value.currentChapter === current)
                assertNull(reader.model.state.value.dialog)
                reader.model.loadNextChapter()
                assertTrue(reader.model.state.value.currentChapter === current)
                assertTrue(reader.model.state.value.dialog is ReaderViewModel.Dialog.PairingRestoreError)
                reader.host.setContent { MaterialTheme { reader.activity.PairingRestoreControls() } }
                compose.onNodeWithText(
                    reader.host.stringResource(MR.strings.desktop_reader_pairing_use_default),
                ).performClick()
                withTimeout(5_000) {
                    while (reader.model.state.value.currentChapter !== next) yield()
                }
                assertTrue(reader.model.state.value.pairingReadUnavailable)
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                reader.viewer.moveToPage(requireNotNull(next.pages)[3])
                assertEquals(setOf(3, 4), visiblePair(reader.viewer))
                assertEquals(setOf(3), fixture.repository.load(3, 1).record?.forcedSinglePages)
                reader.host.setContent {
                    val state by reader.model.state.collectAsState()
                    MaterialTheme { reader.activity.AppBars(state.copy(menuVisible = true)) }
                }
                compose.mainClock.advanceTimeBy(500)
                compose.onNodeWithContentDescription(
                    reader.host.stringResource(MR.strings.action_adjust_page_pairing),
                ).assertIsNotEnabled()
            }
        }
        Unit
    }

    @Test
    fun `RTL transition offers recovery when adjacent pages loaded but pairing read failed`() = runBlocking {
        val file = temporaryFolder.newFile("transition-pairing-failure.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            seedAdjacent(fixture.driver)
            fixture.repository.replace(3, 1, 0, 8, setOf(3))
            val failNextRead = AtomicBoolean(true)
            val repository = object : ChapterPairingRepository by fixture.repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    if (chapterId == 3L && failNextRead.get()) throw IOException("next pairing unavailable")
                    return fixture.repository.load(chapterId, mangaId)
                }
            }
            session(fixture, repository, includeAdjacent = true, reuseLoadedPages = true).use { reader ->
                val current = requireNotNull(reader.model.state.value.currentChapter)
                val next = requireNotNull(reader.model.state.value.viewerChapters?.nextChapter)
                reader.model.consumeAdjacentChapterEffect(next, ReaderAdjacentChapterEffect.LoadAdjacentChapterPageList)
                assertTrue(next.pages?.isNotEmpty() == true)
                assertNull(reader.model.state.value.dialog)
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                val transitionIndex = reader.viewer.adapter.items.indexOfFirst {
                    it is ChapterTransition.Next && it.to === next
                }
                assertTrue("unrestored adjacent chapter has no RTL retry transition", transitionIndex >= 0)
                reader.viewer.pager.setCurrentItem(transitionIndex, false)
                withTimeout(5_000) {
                    while (reader.model.state.value.dialog !is ReaderViewModel.Dialog.PairingRestoreError) yield()
                }
                assertTrue(reader.model.state.value.currentChapter === current)
                reader.host.setContent { MaterialTheme { reader.activity.PairingRestoreControls() } }
                failNextRead.set(false)
                compose.onNodeWithText(reader.host.stringResource(MR.strings.action_retry)).performClick()
                withTimeout(5_000) {
                    while (reader.model.state.value.currentChapter !== next) yield()
                }
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                reader.viewer.moveToPage(requireNotNull(next.pages)[4])
                assertEquals(setOf(4, 5), visiblePair(reader.viewer))
            }
        }
    }

    @Test
    fun `restored RTL last spread settles the last source page and marks chapter read`() = runBlocking {
        val file = temporaryFolder.newFile("last-spread.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            fixture.repository.replace(2, 1, 0, 8, setOf(3))
            session(fixture).use { reader ->
                val pages = requireNotNull(reader.model.state.value.currentChapter?.pages)
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                reader.viewer.moveToPage(pages[7])
                val spread = reader.viewer.adapter.items[reader.viewer.pager.currentItem] as DisplayPage
                assertEquals(setOf(6, 7), spread.visiblePages.map(ReaderPage::index).toSet())
                spread.visiblePages.forEach { it.status = Page.State.Ready }
                reader.model.onPageSelected(spread.visiblePages.first(), spread.visiblePages, recordProgress = false)
                reader.model.onDualViewportSettled(spread.visiblePages.first(), spread.visiblePages)
                withTimeout(5_000) {
                    while (!fixture.database.chaptersQueries.getChapterById(2).executeAsOne().read) yield()
                }
                val chapter = fixture.database.chaptersQueries.getChapterById(2).executeAsOne()
                assertTrue(chapter.read)
                assertEquals(7L, chapter.last_page_read)
            }
        }
    }

    @Test
    fun `failed adjustment click keeps old spread and record and emits failure feedback`() = runBlocking {
        val file = temporaryFolder.newFile("save-failure.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            val repository = object : ChapterPairingRepository by fixture.repository {
                override suspend fun replace(
                    chapterId: Long,
                    mangaId: Long,
                    expectedRevision: Long,
                    pageCount: Int,
                    forcedSinglePages: Set<Int>,
                ): ChapterPairingSnapshot = throw IOException("pairing database unavailable")
            }
            session(fixture, repository).use { reader ->
                val pages = requireNotNull(reader.model.state.value.currentChapter?.pages)
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                reader.viewer.moveToPage(pages[3])
                assertEquals(setOf(3, 4), visiblePair(reader.viewer))
                val feedback = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(5_000) {
                        reader.model.eventFlow.first { it == ReaderViewModel.Event.ChapterPairingSaveFailed }
                    }
                }
                reader.host.setContent {
                    val state by reader.model.state.collectAsState()
                    MaterialTheme { reader.activity.AppBars(state.copy(menuVisible = true)) }
                }
                compose.mainClock.advanceTimeBy(500)
                compose.onNodeWithContentDescription(
                    reader.host.stringResource(MR.strings.action_adjust_page_pairing),
                ).assertIsDisplayed().performClick()
                assertEquals(ReaderViewModel.Event.ChapterPairingSaveFailed, feedback.await())
                assertFalse(reader.model.state.value.pairingSaving)
                assertEquals(setOf(3, 4), visiblePair(reader.viewer))
                assertNull(fixture.repository.load(2, 1).record)
                compose.onNodeWithContentDescription(
                    reader.host.stringResource(MR.strings.action_adjust_page_pairing),
                ).assertIsDisplayed().assertIsEnabled()
            }
        }
        Unit
    }

    @Test
    fun `invalid saved page count does not suppress an adjustment matching its old boundary`() = runBlocking {
        val file = temporaryFolder.newFile("invalid-pairing.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            fixture.repository.replace(2, 1, 0, 9, setOf(3))
            val attempts = AtomicInteger()
            val failure = AtomicReference<Throwable?>()
            val observed = object : ChapterPairingRepository by fixture.repository {
                override suspend fun replace(
                    chapterId: Long,
                    mangaId: Long,
                    expectedRevision: Long,
                    pageCount: Int,
                    forcedSinglePages: Set<Int>,
                ): ChapterPairingSnapshot {
                    attempts.incrementAndGet()
                    return try {
                        fixture.repository.replace(chapterId, mangaId, expectedRevision, pageCount, forcedSinglePages)
                    } catch (error: Throwable) {
                        failure.set(error)
                        throw error
                    }
                }
            }
            session(fixture, observed).use { session ->
                val pages = requireNotNull(session.model.state.value.currentChapter?.pages)
                session.viewer.setChapters(requireNotNull(session.model.state.value.viewerChapters))
                session.viewer.moveToPage(pages[3])
                assertEquals(setOf(3, 4), visiblePair(session.viewer))
                assertEquals(
                    setOf(3),
                    requireNotNull(session.viewer.pairingAdjustmentRequest()).adjustment.forcedSinglePages,
                )
                assertEquals(
                    1L,
                    session.model.dualPagePairings.snapshot(
                        requireNotNull(session.model.state.value.currentChapter),
                        pages,
                    )?.revision,
                )
                session.host.setContent {
                    MaterialTheme {
                        session.activity.AppBars(session.model.state.value.copy(menuVisible = true))
                    }
                }
                compose.mainClock.advanceTimeBy(500)
                compose.onNodeWithContentDescription(
                    session.host.stringResource(MR.strings.action_adjust_page_pairing),
                ).assertIsDisplayed().performClick()
                withTimeout(5_000) {
                    while (attempts.get() == 0 || session.model.state.value.pairingSaving) yield()
                }
                assertNull(failure.get()?.stackTraceToString(), failure.get())
                assertEquals(8, fixture.repository.load(2, 1).record?.pageCount)
                assertEquals(setOf(4, 5), visiblePair(session.viewer))
            }
        }
    }

    @Test
    fun `pairing read failure keeps the reader container available for a recovery choice`() = runBlocking {
        val file = temporaryFolder.newFile("read-failure.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            val unreadable = object : ChapterPairingRepository by fixture.repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    throw IOException("pairing database unavailable")
                }
            }
            session(fixture, unreadable).use { reader ->
                assertNull(reader.model.state.value.viewerChapters)
                assertTrue(reader.model.state.value.dialog != null)
            }
        }
    }

    @Test
    fun `reader restore dialog retry click restores saved pairing before first viewer chapters`() = runBlocking {
        val file = temporaryFolder.newFile("retry-pairing.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            fixture.repository.replace(2, 1, 0, 8, setOf(3))
            val failRead = AtomicBoolean(true)
            val repository = object : ChapterPairingRepository by fixture.repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    if (failRead.get()) throw IOException("pairing database unavailable")
                    return fixture.repository.load(chapterId, mangaId)
                }
            }
            session(fixture, repository).use { reader ->
                assertNull(reader.model.state.value.viewerChapters)
                reader.host.setContent { MaterialTheme { reader.activity.PairingRestoreControls() } }
                failRead.set(false)
                compose.onNodeWithText(reader.host.stringResource(MR.strings.action_retry)).performClick()
                withTimeout(5_000) {
                    while (reader.model.state.value.viewerChapters == null) yield()
                }
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                val pages = requireNotNull(reader.model.state.value.currentChapter?.pages)
                reader.viewer.moveToPage(pages[4])
                assertEquals(setOf(4, 5), visiblePair(reader.viewer))
                assertEquals(5L, fixture.database.chaptersQueries.getChapterById(2).executeAsOne().last_page_read)
            }
        }
    }

    @Test
    fun `reader default click permits reading but disables saving until retry click succeeds`() = runBlocking {
        val file = temporaryFolder.newFile("default-pairing.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            fixture.repository.replace(2, 1, 0, 8, setOf(3))
            val failRead = AtomicBoolean(true)
            val repository = object : ChapterPairingRepository by fixture.repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    if (failRead.get()) throw IOException("pairing database unavailable")
                    return fixture.repository.load(chapterId, mangaId)
                }
            }
            session(fixture, repository).use { reader ->
                reader.host.setContent { MaterialTheme { reader.activity.PairingRestoreControls() } }
                compose.onNodeWithText(
                    reader.host.stringResource(MR.strings.desktop_reader_pairing_use_default),
                ).performClick()
                withTimeout(5_000) {
                    while (reader.model.state.value.viewerChapters == null) yield()
                }
                assertTrue(reader.model.state.value.pairingReadUnavailable)
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                val pages = requireNotNull(reader.model.state.value.currentChapter?.pages)
                reader.viewer.moveToPage(pages[3])
                assertEquals(setOf(3, 4), visiblePair(reader.viewer))
                assertEquals(setOf(3), fixture.repository.load(2, 1).record?.forcedSinglePages)
                reader.host.setContent {
                    val state by reader.model.state.collectAsState()
                    MaterialTheme { reader.activity.AppBars(state.copy(menuVisible = true)) }
                }
                compose.mainClock.advanceTimeBy(500)
                compose.onNodeWithContentDescription(
                    reader.host.stringResource(MR.strings.action_adjust_page_pairing),
                ).assertIsNotEnabled()
                failRead.set(false)
                compose.onNodeWithContentDescription(reader.host.stringResource(MR.strings.action_retry)).performClick()
                withTimeout(5_000) {
                    while (reader.model.state.value.pairingReadUnavailable) yield()
                }
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                reader.viewer.moveToPage(requireNotNull(reader.model.state.value.currentChapter?.pages)[4])
                assertEquals(setOf(4, 5), visiblePair(reader.viewer))
                assertEquals(5L, fixture.database.chaptersQueries.getChapterById(2).executeAsOne().last_page_read)
            }
        }
    }

    @Test
    fun `accepted save updates replacement viewer after Activity recreation`() = runBlocking {
        val file = temporaryFolder.newFile("replace-viewer.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val delayed = object : ChapterPairingRepository by fixture.repository {
                override suspend fun replace(
                    chapterId: Long,
                    mangaId: Long,
                    expectedRevision: Long,
                    pageCount: Int,
                    forcedSinglePages: Set<Int>,
                ): ChapterPairingSnapshot {
                    entered.complete(Unit)
                    release.await()
                    return fixture.repository.replace(
                        chapterId,
                        mangaId,
                        expectedRevision,
                        pageCount,
                        forcedSinglePages,
                    )
                }
            }
            session(fixture, delayed).use { reader ->
                val pages = requireNotNull(reader.model.state.value.currentChapter?.pages)
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                reader.viewer.moveToPage(pages[3])
                reader.host.setContent {
                    val state by reader.model.state.collectAsState()
                    MaterialTheme { reader.activity.AppBars(state.copy(menuVisible = true)) }
                }
                compose.mainClock.advanceTimeBy(500)
                compose.onNodeWithContentDescription(
                    reader.host.stringResource(MR.strings.action_adjust_page_pairing),
                ).performClick()
                entered.await()
                try {
                    compose.onNodeWithContentDescription(
                        reader.host.stringResource(MR.strings.action_adjust_page_pairing),
                    ).assertIsNotEnabled().assert(
                        SemanticsMatcher.expectValue(
                            SemanticsProperties.StateDescription,
                            reader.host.stringResource(MR.strings.desktop_reader_pairing_saving),
                        ),
                    )
                } catch (error: Throwable) {
                    release.complete(Unit)
                    throw error
                }
                val replacement = DualPageR2LPagerViewer(reader.activity, reader.model.dualPagePairings)
                try {
                    replacement.config.navigationModeChangedListener = null
                    reader.model.onViewerLoaded(replacement)
                    reader.model.onViewerUnloaded(reader.viewer)
                    assertTrue(reader.model.state.value.viewer === replacement)
                    replacement.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                    replacement.moveToPage(pages[4])
                    assertEquals(setOf(3, 4), visiblePair(replacement))
                    reader.viewer.destroy()
                    release.complete(Unit)
                    withTimeout(5_000) {
                        while (reader.model.state.value.pairingSaving) yield()
                    }
                    assertEquals(setOf(3), fixture.repository.load(2, 1).record?.forcedSinglePages)
                    assertEquals(setOf(4, 5), visiblePair(replacement))
                } finally {
                    release.complete(Unit)
                    replacement.destroy()
                }
            }
        }
    }

    @Test
    fun `accepted save survives old ViewModel cancellation and precedes new ViewModel read`() = runBlocking {
        val file = temporaryFolder.newFile("new-view-model.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val loadedNewPages = CompletableDeferred<Unit>()
            val prematureRead = CompletableDeferred<Unit>()
            val delayed = object : ChapterPairingRepository by fixture.repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    if (entered.isCompleted && !release.isCompleted) prematureRead.complete(Unit)
                    return fixture.repository.load(chapterId, mangaId)
                }

                override suspend fun replace(
                    chapterId: Long,
                    mangaId: Long,
                    expectedRevision: Long,
                    pageCount: Int,
                    forcedSinglePages: Set<Int>,
                ): ChapterPairingSnapshot {
                    entered.complete(Unit)
                    release.await()
                    return fixture.repository.replace(
                        chapterId,
                        mangaId,
                        expectedRevision,
                        pageCount,
                        forcedSinglePages,
                    )
                }
            }
            val coordinator = AndroidChapterPairingCoordinator(delayed)
            session(fixture, delayed, coordinator = coordinator).use { old ->
                val pages = requireNotNull(old.model.state.value.currentChapter?.pages)
                old.viewer.setChapters(requireNotNull(old.model.state.value.viewerChapters))
                old.viewer.moveToPage(pages[3])
                old.host.setContent {
                    MaterialTheme { old.activity.AppBars(old.model.state.value.copy(menuVisible = true)) }
                }
                compose.mainClock.advanceTimeBy(500)
                compose.onNodeWithContentDescription(
                    old.host.stringResource(MR.strings.action_adjust_page_pairing),
                ).performClick()
                entered.await()
                old.model.viewModelScope.cancel()
                val opening = async {
                    session(fixture, delayed, coordinator = coordinator, onPageListLoaded = {
                        loadedNewPages.complete(Unit)
                    })
                }
                try {
                    loadedNewPages.await()
                    assertNull(withTimeoutOrNull(500) { prematureRead.await() })
                    assertFalse(opening.isCompleted)
                    release.complete(Unit)
                    withTimeout(5_000) { opening.await() }.use { fresh ->
                        fresh.viewer.setChapters(requireNotNull(fresh.model.state.value.viewerChapters))
                        assertEquals(setOf(4, 5), visiblePair(fresh.viewer))
                    }
                    assertEquals(setOf(3), fixture.repository.load(2, 1).record?.forcedSinglePages)
                } finally {
                    release.complete(Unit)
                }
            }
        }
    }

    @Test
    fun `late failed save from A cannot notify after A to B to A with reused page objects`() = runBlocking {
        val file = temporaryFolder.newFile("chapter-return.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            seedAdjacent(fixture.driver)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val scheduler = TestCoroutineScheduler()
            val failing = object : ChapterPairingRepository by fixture.repository {
                override suspend fun replace(
                    chapterId: Long,
                    mangaId: Long,
                    expectedRevision: Long,
                    pageCount: Int,
                    forcedSinglePages: Set<Int>,
                ): ChapterPairingSnapshot {
                    entered.complete(Unit)
                    release.await()
                    throw IOException("accepted A save failed")
                }
            }
            session(
                fixture,
                failing,
                includeAdjacent = true,
                reuseLoadedPages = true,
                pairingCallbackDispatcher = StandardTestDispatcher(scheduler),
            ).use { reader ->
                val originalA = requireNotNull(reader.model.state.value.currentChapter)
                val originalPages = requireNotNull(originalA.pages)
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                reader.viewer.moveToPage(originalPages[3])
                reader.host.setContent {
                    MaterialTheme { reader.activity.AppBars(reader.model.state.value.copy(menuVisible = true)) }
                }
                compose.mainClock.advanceTimeBy(500)
                compose.onNodeWithContentDescription(
                    reader.host.stringResource(MR.strings.action_adjust_page_pairing),
                ).performClick()
                entered.await()
                assertEquals(3L, reader.model.state.value.viewerChapters?.nextChapter?.chapter?.id)
                reader.model.loadNextChapter()
                assertEquals(
                    reader.model.state.value.chapterWindow.toString(),
                    3L,
                    reader.model.state.value.currentChapter?.chapter?.id,
                )
                assertFalse(reader.model.state.value.pairingSaving)
                val returnToA = async { reader.model.loadPreviousChapter() }
                release.complete(Unit)
                withTimeout(5_000) { returnToA.await() }
                assertTrue(reader.model.state.value.currentChapter === originalA)
                assertTrue(reader.model.state.value.currentChapter?.pages === originalPages)
                val staleFailure = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(500) {
                        reader.model.eventFlow.first { it == ReaderViewModel.Event.ChapterPairingSaveFailed }
                    }
                }
                scheduler.runCurrent()
                assertFalse(reader.model.state.value.pairingSaving)
                assertNull(staleFailure.await())
                assertNull(fixture.repository.load(2, 1).record)
            }
        }
    }

    private suspend fun session(
        database: DatabaseFixture,
        repository: ChapterPairingRepository = database.repository,
        includeAdjacent: Boolean = false,
        reuseLoadedPages: Boolean = false,
        pairingCallbackDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
        coordinator: AndroidChapterPairingCoordinator = AndroidChapterPairingCoordinator(repository),
        onPageListLoaded: () -> Unit = {},
    ): ReaderSession {
        Injekt = InjektScope(DefaultRegistrar())
        val preferences = InMemoryPreferenceStore()
        val readerPreferences = ReaderPreferences(preferences)
        Injekt.addSingleton<ChapterPairingRepository>(repository)
        Injekt.addSingleton(coordinator)
        Injekt.addSingleton(readerPreferences)
        Injekt.addSingleton(UiPreferences(preferences))
        Injekt.addSingleton(BasePreferences(RuntimeEnvironment.getApplication() as Application, preferences))
        Injekt.addSingleton(mockk<DownloadManager>(relaxed = true))

        val manga = Manga.create().copy(id = 1, source = 7, url = "/", title = "Pairing manga")
        val chapter = Chapter.create().copy(
            id = 2,
            mangaId = 1,
            url = "/chapter",
            name = "Chapter 2",
            lastPageRead = 5,
            sourceOrder = 2,
        )
        val adjacent = Chapter.create().copy(
            id = 3,
            mangaId = 1,
            url = "/chapter-3",
            name = "Chapter 3",
            sourceOrder = 1,
        )
        val source = mockk<Source>()
        val sourceManager = mockk<SourceManager> {
            every { isInitialized } returns MutableStateFlow(true)
            every { getOrStub(7) } returns source
        }
        Injekt.addSingleton(sourceManager)
        val getManga = mockk<GetManga>()
        coEvery { getManga.await(1) } returns manga
        val getChapters = mockk<GetChaptersByMangaId>()
        coEvery { getChapters.await(1, applyScanlatorFilter = true) } returns
            if (includeAdjacent) listOf(chapter, adjacent) else listOf(chapter)
        val loader = mockk<ChapterLoader>()
        coEvery { loader.loadChapter(any()) } coAnswers {
            firstArg<ReaderChapter>().let { current ->
                if (!reuseLoadedPages || current.pages == null) {
                    current.publishLoadedPageListForTest(
                        List(8) { index -> ReaderPage(index).apply { this.chapter = current } },
                    )
                    onPageListLoaded()
                }
            }
        }
        coEvery { loader.loadChapter(any(), any()) } coAnswers {
            firstArg<ReaderChapter>().let { current ->
                if (!reuseLoadedPages || current.pages == null) {
                    current.publishLoadedPageListForTest(
                        List(8) { index -> ReaderPage(index).apply { this.chapter = current } },
                    )
                    onPageListLoaded()
                }
            }
        }
        val downloadPreferences = mockk<DownloadPreferences>(relaxed = true)
        every { downloadPreferences.autoDownloadWhileReading().get() } returns 0
        every { downloadPreferences.removeAfterReadSlots().get() } returns -1
        val basePreferences = mockk<BasePreferences>(relaxed = true)
        every { basePreferences.downloadedOnly().get() } returns false
        val incognito = mockk<GetIncognitoState>()
        every { incognito.await(any()) } returns false
        val libraryPreferences = mockk<LibraryPreferences>(relaxed = true)
        every { libraryPreferences.markDuplicateReadChapterAsRead().get() } returns emptySet()
        val trackPreferences = mockk<TrackPreferences>(relaxed = true)
        every { trackPreferences.autoUpdateTrack().get() } returns false
        val model = ReaderViewModel(
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
            recordReadingProgress = RecordReadingProgress(SqlDelightReadingProgressRepository(database.database)),
            setMangaViewerFlags = mockk(relaxed = true),
            getIncognitoState = incognito,
            progressCoordinator = eu.kanade.tachiyomi.ui.reader.emptyReaderProgressCoordinator(),
            libraryPreferences = libraryPreferences,
            pairingCallbackDispatcher = pairingCallbackDispatcher,
            chapterLoaderFactory = { _: Manga, _: Source -> loader },
        )
        val initialized = model.init(1, 2)
        assertTrue(initialized.exceptionOrNull()?.stackTraceToString(), initialized.isSuccess)
        val activity = Robolectric.buildActivity(ReaderActivity::class.java).get()
        activity.binding = mockk<ReaderActivityBinding>(relaxed = true)
        ReflectionHelpers.setField(activity, "viewModel\$delegate", lazyOf(model))
        val viewer = DualPageR2LPagerViewer(activity, model.dualPagePairings)
        viewer.config.navigationModeChangedListener = null
        model.onViewerLoaded(viewer)
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().get()
        return ReaderSession(model, activity, viewer, host)
    }

    private fun visiblePair(viewer: DualPageR2LPagerViewer): Set<Int> =
        (viewer.adapter.items[viewer.pager.currentItem] as DisplayPage).visiblePages.map(ReaderPage::index).toSet()

    private fun openDatabase(file: File, create: Boolean = false): DatabaseFixture {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        if (create) Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        val handler = AndroidDatabaseHandler(database, driver)
        return DatabaseFixture(driver, database, ChapterPairingRepositoryImpl(handler), handler)
    }

    private fun seed(driver: JdbcSqliteDriver) {
        driver.execute(
            null,
            "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, " +
                "chapter_flags, cover_last_modified, date_added) VALUES (1, 7, '/', 'M', 0, 0, 0, 0, 0, 0, 0)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read, " +
                "chapter_number, source_order, date_fetch, date_upload) " +
                "VALUES (2, 1, '/chapter', 'C', 0, 0, 5, 1, 2, 0, 0)",
            0,
        )
    }

    private fun seedAdjacent(driver: JdbcSqliteDriver) {
        driver.execute(
            null,
            "INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read, " +
                "chapter_number, source_order, date_fetch, date_upload) " +
                "VALUES (3, 1, '/chapter-3', 'C3', 0, 0, 0, 2, 1, 0, 0)",
            0,
        )
    }

    private data class DatabaseFixture(
        val driver: JdbcSqliteDriver,
        val database: Database,
        val repository: ChapterPairingRepository,
        val handler: AndroidDatabaseHandler,
    ) : AutoCloseable {
        override fun close() = driver.close()
    }

    private data class ReaderSession(
        val model: ReaderViewModel,
        val activity: ReaderActivity,
        val viewer: DualPageR2LPagerViewer,
        val host: ComponentActivity,
    ) : AutoCloseable {
        override fun close() {
            viewer.destroy()
            model.viewModelScope.cancel()
            runBlocking {
                model.viewModelScope.coroutineContext[Job]?.children?.toList()?.joinAll()
            }
            host.finish()
        }
    }
}
