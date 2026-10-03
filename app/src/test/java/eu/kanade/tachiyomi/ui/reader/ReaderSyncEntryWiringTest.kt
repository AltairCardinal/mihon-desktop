package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import cafe.adriel.voyager.core.annotation.InternalVoyagerApi
import cafe.adriel.voyager.core.model.ScreenModelStore
import cafe.adriel.voyager.core.model.screenModelScope
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
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
    @Test
    fun `actual history row opens its selected chapter with within chapter resume intent`() = runBlocking {
        val model = history(chapters[1])
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                MaterialTheme {
                    Navigator(MangaScreen(1)) {
                        eu.kanade.tachiyomi.ui.history.HistoryTab.ContentWithModel(model)
                    }
                }
            }
            compose.onNodeWithTag("history_item_1").performClick()
            val intent = withTimeout(5_000) {
                var started: Intent? = null
                while (started ==
                    null
                ) {
                    started = shadowOf(activity.get()).nextStartedActivity
                    if (started == null) delay(10)
                }
                requireNotNull(started)
            }
            assertEquals(2L, intent.getLongExtra("chapter", -1))
            assertTrue(intent.getBooleanExtra("resumeWithinChapter", false))
            assertFalse(intent.getBooleanExtra("resume", false))
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun `actual history leave and return rejects late selected chapter event`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val visible = mutableStateOf(true)
        val model = history(chapters[1]) {
            entered.complete(Unit)
            release.await()
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                MaterialTheme {
                    Navigator(MangaScreen(1)) {
                        if (visible.value) {
                            eu.kanade.tachiyomi.ui.history.HistoryTab.ContentWithModel(model)
                        } else {
                            androidx.compose.material3.Text("Other tab")
                        }
                    }
                }
            }
            compose.waitForIdle()
            val previousJobs = model.screenModelScope.coroutineContext[Job]!!.children.toSet()
            compose.onNodeWithTag("history_item_1").performClick()
            withContext(Dispatchers.Default) { withTimeout(5_000) { entered.await() } }
            val requests = model.screenModelScope.coroutineContext[Job]!!.children.filter {
                it !in previousJobs
            }.toList()
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            release.complete(Unit)
            withContext(Dispatchers.Default) { withTimeout(5_000) { requests.joinAll() } }
            assertEquals(null, shadowOf(activity.get()).nextStartedActivity)
            compose.runOnIdle { visible.value = true }
            compose.waitForIdle()
            assertEquals(
                "Returning to History must not replay the previous action",
                null,
                shadowOf(activity.get()).nextStartedActivity,
            )
        } finally {
            release.complete(Unit)
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun `actual history repeated row clicks produce only one reader intent`() = runBlocking {
        val entered = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val model = history(chapters[1]) {
            entered.send(Unit)
            release.await()
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                MaterialTheme {
                    Navigator(MangaScreen(1)) {
                        eu.kanade.tachiyomi.ui.history.HistoryTab.ContentWithModel(model)
                    }
                }
            }
            compose.waitForIdle()
            val previousJobs = model.screenModelScope.coroutineContext[Job]!!.children.toSet()
            repeat(2) { compose.onNodeWithTag("history_item_1").performClick() }
            withContext(Dispatchers.Default) { withTimeout(5_000) { repeat(2) { entered.receive() } } }
            val requests = model.screenModelScope.coroutineContext[Job]!!.children.filter {
                it !in previousJobs
            }.toList()
            release.complete(Unit)
            withContext(Dispatchers.Default) { withTimeout(5_000) { requests.joinAll() } }
            compose.waitForIdle()
            assertTrue(shadowOf(activity.get()).nextStartedActivity != null)
            assertEquals(
                "Repeated click must not launch another Reader",
                null,
                shadowOf(activity.get()).nextStartedActivity,
            )
        } finally {
            release.complete(Unit)
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun `actual row job queued before leaving cannot acquire returned page ownership`() = runBlocking {
        val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(scheduler)
        val visible = mutableStateOf(true)
        val model = history(chapters[1], dispatcher)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                MaterialTheme {
                    Navigator(MangaScreen(1)) {
                        if (visible.value) {
                            eu.kanade.tachiyomi.ui.history.HistoryTab.ContentWithModel(model)
                        } else {
                            androidx.compose.material3.Text("Other tab")
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("history_item_1").performClick()
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            compose.runOnIdle { visible.value = true }
            compose.waitForIdle()
            scheduler.runCurrent()
            compose.waitForIdle()
            assertEquals(
                "A click from the previous page lifetime must remain revoked",
                null,
                shadowOf(activity.get()).nextStartedActivity,
            )
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun `actual HistoryTab reselect while detached cannot wait and replay on return`() = runBlocking {
        val visible = mutableStateOf(true)
        val model = history(chapters[1], selectedLatest = chapters[1])
        var navigator: Navigator? = null
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var reselect: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            activity.get().setContent {
                MaterialTheme {
                    Navigator(MangaScreen(1)) { nav ->
                        navigator = nav
                        if (visible.value) {
                            eu.kanade.tachiyomi.ui.history.HistoryTab.ContentWithModel(model)
                        } else {
                            androidx.compose.material3.Text("Other tab")
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            reselect = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                eu.kanade.tachiyomi.ui.history.HistoryTab.onReselect(requireNotNull(navigator))
            }
            assertTrue("Detached tab must reject reselect immediately", reselect.isCompleted)
            compose.runOnIdle { visible.value = true }
            compose.waitForIdle()
            assertEquals(null, shadowOf(activity.get()).nextStartedActivity)
        } finally {
            reselect?.cancel()
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun `actual cover navigation synchronously revokes a queued row request`() = runBlocking {
        queuedNavigationAction("history_cover_1", opensDetail = true)
    }

    @Test
    fun `actual delete dialog synchronously revokes a queued row request`() = runBlocking {
        queuedNavigationAction("history_delete_1", opensDetail = false)
    }

    private suspend fun queuedNavigationAction(tag: String, opensDetail: Boolean) {
        val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        val model = history(chapters[1], kotlinx.coroutines.test.StandardTestDispatcher(scheduler))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var navigator: Navigator? = null
        try {
            activity.get().setContent {
                MaterialTheme {
                    Navigator(MangaScreen(0)) { nav ->
                        navigator = nav
                        eu.kanade.tachiyomi.ui.history.HistoryTab.ContentWithModel(model)
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("history_item_1").performClick()
            val action =
                requireNotNull(
                    compose.onNodeWithTag(
                        tag,
                    ).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action,
                )
            // Execute both callbacks in one UI turn, before navigation can dispose the old page.
            compose.runOnIdle {
                assertTrue(action())
                scheduler.runCurrent()
            }
            compose.waitForIdle()
            if (opensDetail) {
                assertEquals(2, requireNotNull(navigator).items.size)
                assertTrue(requireNotNull(navigator).lastItem is MangaScreen)
            } else {
                assertTrue(model.controller.state.value.dialog is tachiyomi.domain.history.service.HistoryDialog.Delete)
                compose.onNodeWithTag("history_delete_confirm").assertIsDisplayed()
            }
            assertEquals(
                "Old row action must be revoked at the navigation or dialog callback",
                null,
                shadowOf(activity.get()).nextStartedActivity,
            )
        } finally {
            activity.pause().stop().destroy()
        }
    }

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
            addSingleton(eu.kanade.domain.ui.UiPreferences(preferences))
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
        val history = ReaderActivity.newContinueIntent(context, 1, 2)
        val explicit = ReaderActivity.newIntent(context, 1, 2)

        assertTrue(ordinary.getBooleanExtra("resumeWithinChapter", false))
        assertFalse(ordinary.getBooleanExtra("resume", false))
        assertFalse(history.getBooleanExtra("resume", false))
        assertTrue(history.getBooleanExtra("resumeWithinChapter", false))
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
    fun `actual history without a next chapter shows official feedback without launching reader`() = runBlocking {
        val model = history()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                MaterialTheme {
                    Navigator(MangaScreen(1)) {
                        eu.kanade.tachiyomi.ui.history.HistoryTab.ContentWithModel(model)
                    }
                }
            }
            compose.onNodeWithTag("history_item_1").performClick()
            val text = RuntimeEnvironment.getApplication().stringResource(MR.strings.no_next_chapter)
            compose.waitUntil(5_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(androidx.compose.ui.test.hasText(text)).assertIsDisplayed()
            assertEquals(null, shadowOf(activity.get()).nextStartedActivity)
        } finally {
            model.onDispose()
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun `history global selection with no next chapter does not reopen synchronized final chapter`() = runBlocking {
        val model = history()
        try {
            withTimeout(5_000) { model.state.first { !it.list.isNullOrEmpty() } }
            assertEquals(null, model.getNextChapter())
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

    private fun history(
        selectedNext: Chapter? = null,
        readerDispatcher: CoroutineDispatcher = Dispatchers.IO,
        selectedLatest: Chapter? = null,
        beforeSelection: suspend () -> Unit = {},
    ) = ScreenModelStore.getOrPut(modelHolder, "history") {
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
                coEvery { await(1, 2, false) } coAnswers {
                    beforeSelection()
                    listOfNotNull(selectedNext)
                }
                coEvery { await(1, false) } returns chapters
                coEvery { await(false) } returns listOfNotNull(selectedLatest)
            },
            libraryPreferences = LibraryPreferences(preferences),
            removeHistory = mockk(relaxed = true),
            updateMembership = mockk(relaxed = true),
            sourceManager = sourceManager,
            readerActionDispatcher = readerDispatcher,
        )
    }
}
