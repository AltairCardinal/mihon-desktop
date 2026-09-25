package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import cafe.adriel.voyager.core.annotation.InternalVoyagerApi
import cafe.adriel.voyager.core.model.ScreenModelStore
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.library.components.LibraryComfortableGrid
import eu.kanade.presentation.library.components.LibraryCompactGrid
import eu.kanade.presentation.library.components.LibraryList
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.history.HistoryScreenModel
import eu.kanade.tachiyomi.ui.library.LibraryScreenModel
import eu.kanade.tachiyomi.ui.library.LibrarySettingsScreenModel
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mihon.data.sync.runtime.SyncPanel
import mihon.data.sync.runtime.SyncPanelState
import mihon.data.sync.runtime.SyncRuntime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.reader.model.ReadingResumePosition
import tachiyomi.domain.reader.model.ReadingSyncSnapshot
import tachiyomi.domain.reader.repository.ReadingProgressRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(InternalVoyagerApi::class)
class ReaderSyncEntryWiringTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private lateinit var previous: InjektScope
    private lateinit var preferences: PreferenceStore
    private lateinit var modelHolder: String
    private val manga = Manga.create().copy(id = 1, source = 7, title = "Synced manga", favorite = true)
    private val chapters = (1L..2L).map { id ->
        Chapter.create().copy(id = id, mangaId = 1, name = "Chapter $id", read = true)
    }
    private val position = ReadingResumePosition(2, 1, ReadingSyncSnapshot())
    private val reading = mockk<ReadingProgressRepository>(relaxed = true) {
        coEvery { resumePosition(1) } returns position
    }
    private val sourceManager = mockk<SourceManager> {
        every { getOrStub(7) } returns mockk<Source>(relaxed = true)
    }

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        modelHolder = "reader-sync-${System.nanoTime()}"
        previous = Injekt
        val application = RuntimeEnvironment.getApplication()
        preferences = AndroidPreferenceStore(
            application,
            application.getSharedPreferences("reader-sync-entry-${System.nanoTime()}", 0),
        )
        Injekt = InjektScope(DefaultRegistrar()).apply {
            addSingleton(sourceManager)
            addSingleton(RecordReadingProgress(reading))
            addSingleton(BasePreferences(RuntimeEnvironment.getApplication(), preferences))
            addSingleton(
                mockk<SyncRuntime> {
                    every { panel } returns mockk<SyncPanel>(relaxed = true) {
                        every { state } returns MutableStateFlow(SyncPanelState())
                    }
                },
            )
        }
    }

    @After
    fun teardown() {
        ScreenModelStore.onDisposeNavigator(modelHolder)
        Injekt = previous
        Dispatchers.resetMain()
    }

    @Test
    fun `reader intents distinguish ordinary continuation from explicit and history entry`() {
        val context = RuntimeEnvironment.getApplication()
        val ordinary = ReaderActivity.newContinueIntent(context, 1, 1)
        val history = ReaderActivity.newIntent(context, 1, 2, resume = true)
        val explicit = ReaderActivity.newIntent(context, 1, 2)

        assertTrue(ordinary.getBooleanExtra("resumeWithinChapter", false))
        assertFalse(ordinary.getBooleanExtra("resume", false))
        assertTrue(history.getBooleanExtra("resume", false))
        assertFalse(history.getBooleanExtra("resumeWithinChapter", false))
        assertFalse(explicit.getBooleanExtra("resume", false))
        assertFalse(explicit.getBooleanExtra("resumeWithinChapter", false))
    }

    @Test
    fun `all library layouts continue earliest unread chapter before old synchronized read chapter`() = runBlocking {
        val first = chapters[0].copy(read = false, lastPageRead = 4)
        val model = library(chapterCandidates = listOf(first, chapters[1]), unreadCount = 1)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val item = withTimeout(5_000) {
                model.state.first { it.libraryData.favorites.isNotEmpty() }.libraryData.favorites.single()
            }
            val layout = mutableIntStateOf(0)
            var clicks = 0
            activity.get().setContent {
                MaterialTheme {
                    when (layout.intValue) {
                        0 -> LibraryCompactGrid(
                            listOf(item), true, 1, PaddingValues(), emptySet(), {}, {}, { clicks++ }, null, {},
                        )
                        1 -> LibraryComfortableGrid(
                            listOf(item), 1, PaddingValues(), emptySet(), {}, {}, { clicks++ }, null, {},
                        )
                        else -> LibraryList(
                            listOf(item),
                            PaddingValues(),
                            emptySet(),
                            {},
                            {},
                            { clicks++ },
                            null,
                            {},
                        )
                    }
                }
            }
            for (index in 0..2) {
                compose.runOnIdle { layout.intValue = index }
                compose.onNodeWithContentDescription(activity.get().stringResource(MR.strings.action_resume))
                    .assertIsDisplayed().performClick()
            }
            assertEquals(3, clicks)
            assertEquals(first.id, model.getNextUnreadChapter(manga)?.id)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun `library tab continue click starts unread chapter with within chapter resume`() = runBlocking {
        val first = chapters[0].copy(read = false, lastPageRead = 4)
        val model = library(
            chapterCandidates = listOf(first, chapters[1]),
            unreadCount = 1,
            categories = listOf(Category(0, "Default", 0, 0)),
        )
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val ready = withTimeoutOrNull(5_000) {
                model.state.first {
                    !it.isLoading && it.showMangaContinueButton && it.displayedCategories.isNotEmpty()
                }
            }
            check(ready != null) { "Library state not ready: ${model.state.value}" }
            activity.get().setContent {
                MaterialTheme {
                    Navigator(MangaScreen(manga.id)) {
                        LibraryTab.ContentWithModels(model, mockk<LibrarySettingsScreenModel>(relaxed = true))
                    }
                }
            }
            try {
                compose.onNodeWithContentDescription(activity.get().stringResource(MR.strings.action_resume))
                    .assertIsDisplayed().performClick()
            } catch (error: AssertionError) {
                throw AssertionError(compose.onRoot(useUnmergedTree = true).printToString(), error)
            }

            val intent = withTimeout(5_000) {
                var started: Intent? = null
                while (started == null) {
                    started = shadowOf(activity.get()).nextStartedActivity
                    if (started == null) delay(10)
                }
                requireNotNull(started)
            }
            assertEquals(ReaderActivity::class.java.name, intent.component?.className)
            assertEquals(manga.id, intent.getLongExtra("manga", -1))
            assertEquals(first.id, intent.getLongExtra("chapter", -1))
            assertFalse(intent.getBooleanExtra("resume", false))
            assertTrue(intent.getBooleanExtra("resumeWithinChapter", false))
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun `all read library manga does not expose synchronized rereading as ordinary continuation`() = runBlocking {
        val model = library()
        val item = withTimeout(5_000) {
            model.state.first { it.libraryData.favorites.isNotEmpty() }.libraryData.favorites.single()
        }
        assertFalse(item.hasSynchronizedResume)
        assertEquals(null, model.getNextUnreadChapter(manga))
    }

    @Test
    fun `history row resumes synchronized rereading when no unread successor exists`() = runBlocking {
        val model = history()
        try {
            model.getNextChapterForManga(1, 2)
            val event = withTimeout(5_000) { model.events.first() } as HistoryScreenModel.Event.OpenChapter
            assertEquals(2L, event.chapter?.id)
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `history tab reselection resumes the most recent manga even after the final chapter was read`() = runBlocking {
        val model = history()
        try {
            withTimeout(5_000) { model.state.first { !it.list.isNullOrEmpty() } }
            assertEquals(2L, model.getNextChapter()?.id)
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `disabled library continuation does not query synchronized candidates`() = runBlocking {
        val model = library(showContinue = false)
        val item = withTimeout(5_000) {
            model.state.first { it.libraryData.favorites.isNotEmpty() }.libraryData.favorites.single()
        }
        assertFalse(item.hasSynchronizedResume)
        coVerify(exactly = 0) { reading.resumePosition(any()) }
    }

    @Test
    fun `library does not guess a chapter when the synchronized identity is unavailable`() = runBlocking {
        coEvery { reading.resumePosition(1) } returns position.copy(chapterId = 99)
        val model = library()
        val item = withTimeout(5_000) {
            model.state.first { it.libraryData.favorites.isNotEmpty() }.libraryData.favorites.single()
        }
        assertFalse(item.hasSynchronizedResume)
        assertEquals(null, model.getNextUnreadChapter(manga))
    }

    private fun library(
        showContinue: Boolean = true,
        chapterCandidates: List<Chapter> = chapters,
        unreadCount: Long = 0,
        categories: List<Category> = emptyList(),
    ): LibraryScreenModel {
        val libraryPreferences = LibraryPreferences(preferences)
        libraryPreferences.showContinueReadingButton().set(showContinue)
        val libraryManga = LibraryManga(manga, listOf(0), 2, 2, unreadCount, 0, 0, 1)
        return ScreenModelStore.getOrPut(modelHolder, "library") {
            LibraryScreenModel(
                getLibraryManga = mockk { every { subscribe() } returns flowOf(listOf(libraryManga)) },
                getCategories = mockk { every { subscribe() } returns flowOf(categories) },
                getTracksPerManga = mockk { every { subscribe() } returns flowOf(emptyMap()) },
                getNextChapters = mockk(relaxed = true),
                getChaptersByMangaId = mockk { coEvery { await(1, true) } returns chapterCandidates },
                getBookmarkedChaptersByMangaId = mockk(relaxed = true),
                setReadStatus = mockk(relaxed = true),
                updateManga = mockk(relaxed = true),
                setMangaCategories = mockk(relaxed = true),
                preferences = BasePreferences(RuntimeEnvironment.getApplication(), preferences),
                libraryPreferences = libraryPreferences,
                coverCache = mockk(relaxed = true),
                sourceManager = sourceManager,
                downloadManager = mockk(relaxed = true),
                downloadCache = mockk { every { changes } returns MutableStateFlow(Unit) },
                trackerManager = mockk<TrackerManager> { every { loggedInTrackersFlow() } returns flowOf(emptyList()) },
            )
        }
    }

    private fun history() = ScreenModelStore.getOrPut(modelHolder, "history") {
        HistoryScreenModel(
            addTracks = mockk(relaxed = true),
            getCategories = mockk(relaxed = true),
            getDuplicateLibraryManga = mockk(relaxed = true),
            getHistory = mockk {
                every { subscribe(any()) } returns flowOf(
                    listOf(
                        HistoryWithRelations(1, 2, 1, manga.title, 2.0, Date(1), 0, MangaCover(1, 7, true, null, 0)),
                    ),
                )
            },
            getManga = mockk { coEvery { await(1) } returns manga },
            getNextChapters = mockk {
                coEvery { await(1, 2, false) } returns emptyList()
                coEvery { await(1, false) } returns chapters
                coEvery { await(false) } returns emptyList()
            },
            libraryPreferences = LibraryPreferences(preferences),
            removeHistory = mockk(relaxed = true),
            setMangaCategories = mockk(relaxed = true),
            updateManga = mockk(relaxed = true),
            sourceManager = sourceManager,
        )
    }
}
