package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.domain.sync.SyncMutationContext
import org.junit.After
import org.junit.Assert.assertEquals
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
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.BatchUpdateChapters
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.interactor.UpdateLibraryMembership
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.LibraryMembershipUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.reader.model.ReadingResumePosition
import tachiyomi.domain.reader.model.ReadingSyncSnapshot
import tachiyomi.domain.reader.repository.ReadingProgressRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class MangaScreenModelSharedMutationWiringTest {
    @Test
    fun `Android manual download uses raw repository range and stable ascending candidates before limit`() = runTest {
        val original = listOf(
            chapter(1).copy(chapterNumber = 1.0, bookmark = true, read = true),
            chapter(2).copy(chapterNumber = 2.0, bookmark = true),
            chapter(3).copy(chapterNumber = 2.0),
        )
        val current = manga(true).copy(chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_DESC)
        val get = mockk<GetMangaWithChapters> {
            coEvery { subscribe(MANGA_ID, true) } returns flowOf(current to original.drop(1))
            coEvery { awaitManga(MANGA_ID) } returns current
            coEvery { awaitChapters(MANGA_ID, true) } returns original.drop(1)
            coEvery { awaitChapters(MANGA_ID, false) } returns original
        }
        val accepted = Channel<List<Chapter>>(Channel.UNLIMITED)
        val manager = mockk<DownloadManager>(relaxed = true) {
            every { getQueuedDownloadOrNull(any()) } returns null
            every { queueState } returns MutableStateFlow(emptyList())
            every { statusFlow() } returns emptyFlow()
            every { progressFlow() } returns emptyFlow()
            every { isChapterDownloaded(any(), any(), any(), any(), any(), any()) } returns false
            every { downloadChapters(any(), any(), any()) } answers {
                accepted.trySend(secondArg())
                Unit
            }
        }
        for (skip in listOf(false, true)) {
            ReaderPreferences(preferenceStore).skipFiltered().set(skip)
            val model = screenModel(
                manga = current,
                chapters = original.drop(1),
                getMangaWithChaptersOverride = get,
                downloadManagerOverride = manager,
            )
            try {
                awaitSuccess(model)
                model.runDownloadAction(eu.kanade.presentation.manga.DownloadAction.BOOKMARKED_CHAPTERS)
                testScheduler.runCurrent()
                val bookmarks = withContext(Dispatchers.Default) { withTimeout(5000) { accepted.receive() } }
                assertEquals(if (skip) listOf(2L) else listOf(1L, 2L), bookmarks.map { it.id })
                model.runDownloadAction(eu.kanade.presentation.manga.DownloadAction.NEXT_1_CHAPTER)
                testScheduler.runCurrent()
                val next = withContext(Dispatchers.Default) { withTimeout(5000) { accepted.receive() } }
                assertEquals(listOf(2L), next.map { it.id })
            } finally {
                model.onDispose()
            }
        }
    }

    @Test
    fun `Android current defaults calls the shared favorite batch and six authoritative preferences`() = runTest {
        val current = manga(true).copy(
            chapterFlags = Manga.CHAPTER_SHOW_UNREAD or Manga.CHAPTER_SHOW_DOWNLOADED or
                Manga.CHAPTER_SHOW_BOOKMARKED or Manga.CHAPTER_SORTING_NUMBER or
                Manga.CHAPTER_SORT_ASC or Manga.CHAPTER_DISPLAY_NUMBER,
        )
        val other = current.copy(id = MANGA_ID + 1, chapterFlags = 0)
        val updates = Collections.synchronizedList(mutableListOf<tachiyomi.domain.manga.model.MangaUpdate>())
        val batchCompleted = CompletableDeferred<Unit>()
        val repository = mockk<MangaRepository> {
            coEvery { getFavorites() } returns listOf(current, other)
            coEvery { update(any()) } coAnswers {
                val update = firstArg<tachiyomi.domain.manga.model.MangaUpdate>()
                updates += update
                if (update.id == other.id) batchCompleted.complete(Unit)
                true
            }
        }
        val preferences = LibraryPreferences(preferenceStore)
        val sharedDefaults = SetMangaDefaultChapterFlags(
            preferences,
            SetMangaChapterFlags(repository),
            tachiyomi.domain.manga.interactor.GetFavorites(repository),
        )
        val model = screenModel(manga = current, chapters = emptyList(), defaultsOverride = sharedDefaults)
        try {
            awaitSuccess(model)
            model.setCurrentSettingsAsDefault(applyToExisting = true)
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { batchCompleted.await() }
            }
            assertEquals(listOf(current.id, other.id), updates.map { it.id })
            assertTrue(updates.all { it.chapterFlags == current.chapterFlags })
            assertEquals(Manga.CHAPTER_SHOW_UNREAD, preferences.filterChapterByRead().get())
            assertEquals(Manga.CHAPTER_SHOW_DOWNLOADED, preferences.filterChapterByDownloaded().get())
            assertEquals(Manga.CHAPTER_SHOW_BOOKMARKED, preferences.filterChapterByBookmarked().get())
            assertEquals(Manga.CHAPTER_SORTING_NUMBER, preferences.sortChapterBySourceOrNumber().get())
            assertEquals(Manga.CHAPTER_SORT_ASC, preferences.sortChapterByAscendingOrDescending().get())
            assertEquals(Manga.CHAPTER_DISPLAY_NUMBER, preferences.displayChapterByNameOrNumber().get())
        } finally {
            model.onDispose()
        }
    }

    @get:Rule
    val compose = createEmptyComposeRule()
    private val readingRepository = mockk<ReadingProgressRepository>(relaxed = true)

    @Test
    @Config(qualifiers = "w1200dp-h800dp")
    fun `continue reading chooses unread chapter and shows Resume in phone and tablet`() = runBlocking {
        Dispatchers.resetMain()
        val earliestUnread = chapter(1).copy(read = false, lastPageRead = 4)
        val synced = chapter(2).copy(read = true, lastPageRead = 8)
        coEvery { readingRepository.resumePosition(MANGA_ID) } returns
            ReadingResumePosition(synced.id, 1, ReadingSyncSnapshot())
        val model = screenModel(manga = manga(true), chapters = listOf(earliestUnread, synced))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            awaitSuccess(model)
            assertEquals(synced.id, (model.state.value as MangaScreenModel.State.Success).synchronizedResumeId)
            assertEquals(earliestUnread.id, model.getNextUnreadChapter()?.id)
            val tablet = mutableStateOf(false)
            val presentationState = mutableStateOf(model.state.value as MangaScreenModel.State.Success)
            var clicks = 0
            activity.get().setContent {
                MaterialTheme {
                    eu.kanade.presentation.manga.MangaScreen(
                        state = presentationState.value,
                        snackbarHostState = model.snackbarHostState,
                        nextUpdate = null,
                        isTabletUi = tablet.value,
                        chapterSwipeStartAction = LibraryPreferences.ChapterSwipeAction.Disabled,
                        chapterSwipeEndAction = LibraryPreferences.ChapterSwipeAction.Disabled,
                        navigateUp = {}, onChapterClicked = {}, onDownloadChapter = null,
                        onAddToLibraryClicked = {}, onWebViewClicked = null, onWebViewLongClicked = null,
                        onTrackingClicked = {}, onTagSearch = {}, onFilterButtonClicked = {}, onRefresh = {},
                        onContinueReading = { clicks++ }, onSearch = { _, _ -> }, creatorMentions = emptyList(),
                        onCreatorClick = {}, onCoverClicked = {}, onShareClicked = null, onDownloadActionClicked = null,
                        onEditCategoryClicked = null, onEditFetchIntervalClicked = null, onMigrateClicked = null,
                        onEditNotesClicked = {}, onMultiBookmarkClicked = { _, _ -> },
                        onMultiMarkAsReadClicked = { _, _ -> }, onMarkPreviousAsReadClicked = {},
                        onMultiDeleteClicked = {},
                        onChapterSwipe = { _, _ -> }, onChapterSelected = { _, _, _ -> },
                        onAllChapterSelected = {}, onInvertSelection = {},
                    )
                }
            }
            for (isTablet in listOf(false, true)) {
                compose.runOnIdle { tablet.value = isTablet }
                compose.mainClock.advanceTimeBy(500)
                try {
                    val resumeText = activity.get().stringResource(MR.strings.action_resume)
                    compose.onNode(
                        hasClickAction() and hasAnyDescendant(hasText(resumeText)),
                        useUnmergedTree = true,
                    ).assertIsDisplayed().performTouchInput { click() }
                } catch (error: AssertionError) {
                    throw AssertionError(
                        "Resume must be displayed for tablet=$isTablet\n" +
                            compose.onRoot(useUnmergedTree = true).printToString(),
                        error,
                    )
                }
            }
            assertEquals(2, clicks)
            assertEquals(earliestUnread.id, model.getNextUnreadChapter()?.id)

            val selectedItem = presentationState.value.chapters.single { it.chapter.id == earliestUnread.id }
            val onlyTarget = presentationState.value.copy(chapters = listOf(selectedItem), synchronizedResumeId = null)
            for (sameChapterSync in listOf(false, true)) {
                compose.runOnIdle {
                    presentationState.value = onlyTarget.copy(
                        chapters = listOf(
                            selectedItem.copy(
                                chapter = selectedItem.chapter.copy(lastPageRead = if (sameChapterSync) 0 else 4),
                            ),
                        ),
                        synchronizedResumeId = if (sameChapterSync) earliestUnread.id else null,
                    )
                }
                for (isTablet in listOf(false, true)) {
                    compose.runOnIdle { tablet.value = isTablet }
                    compose.mainClock.advanceTimeBy(500)
                    compose.onNode(
                        hasClickAction() and
                            hasAnyDescendant(hasText(activity.get().stringResource(MR.strings.action_resume))),
                        useUnmergedTree = true,
                    ).assertIsDisplayed()
                }
            }
        } finally {
            activity.pause().stop().destroy()
            model.onDispose()
        }
    }

    @Test
    @Config(qualifiers = "w1200dp-h800dp")
    fun `manga screen continue click starts selected unread chapter with within chapter resume`() = runBlocking {
        Dispatchers.resetMain()
        val target = chapter(1).copy(read = false, lastPageRead = 4)
        val oldSynchronized = chapter(2).copy(read = true, lastPageRead = 8)
        coEvery { readingRepository.resumePosition(MANGA_ID) } returns
            ReadingResumePosition(oldSynchronized.id, 1, ReadingSyncSnapshot())
        val model = screenModel(manga = manga(true), chapters = listOf(target, oldSynchronized))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            awaitSuccess(model)
            val route = MangaScreen(MANGA_ID)
            activity.get().setContent {
                MaterialTheme {
                    Navigator(route) { route.ContentWithModel(model) }
                }
            }
            compose.mainClock.advanceTimeBy(500)
            compose.onNode(
                hasClickAction() and hasAnyDescendant(hasText(activity.get().stringResource(MR.strings.action_resume))),
                useUnmergedTree = true,
            ).assertIsDisplayed().performTouchInput { click() }

            val intent = withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    var started = shadowOf(activity.get()).nextStartedActivity
                    while (started == null) {
                        delay(10)
                        started = shadowOf(activity.get()).nextStartedActivity
                    }
                    started
                }
            }
            assertEquals(ReaderActivity::class.java.name, intent.component?.className)
            assertEquals(MANGA_ID, intent.getLongExtra("manga", -1))
            assertEquals(target.id, intent.getLongExtra("chapter", -1))
            assertEquals(false, intent.getBooleanExtra("resume", false))
            assertEquals(true, intent.getBooleanExtra("resumeWithinChapter", false))
        } finally {
            activity.pause().stop().destroy()
            model.onDispose()
        }
    }

    @Test
    @Config(qualifiers = "w1200dp-h800dp")
    fun `all read manga hides continue in phone and tablet despite sync`() = runBlocking {
        Dispatchers.resetMain()
        val synced = chapter(2).copy(read = true, lastPageRead = 8)
        coEvery { readingRepository.resumePosition(MANGA_ID) } returns
            ReadingResumePosition(synced.id, 1, ReadingSyncSnapshot())
        val model = screenModel(manga = manga(true), chapters = listOf(chapter(1).copy(read = true), synced))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            awaitSuccess(model)
            assertEquals(null, model.getNextUnreadChapter())
            val tablet = mutableStateOf(false)
            activity.get().setContent {
                MaterialTheme {
                    eu.kanade.presentation.manga.MangaScreen(
                        state = model.state.value as MangaScreenModel.State.Success,
                        snackbarHostState = model.snackbarHostState,
                        nextUpdate = null,
                        isTabletUi = tablet.value,
                        chapterSwipeStartAction = LibraryPreferences.ChapterSwipeAction.Disabled,
                        chapterSwipeEndAction = LibraryPreferences.ChapterSwipeAction.Disabled,
                        navigateUp = {}, onChapterClicked = {}, onDownloadChapter = null,
                        onAddToLibraryClicked = {}, onWebViewClicked = null, onWebViewLongClicked = null,
                        onTrackingClicked = {}, onTagSearch = {}, onFilterButtonClicked = {}, onRefresh = {},
                        onContinueReading = {}, onSearch = { _, _ -> }, creatorMentions = emptyList(),
                        onCreatorClick = {}, onCoverClicked = {}, onShareClicked = null, onDownloadActionClicked = null,
                        onEditCategoryClicked = null, onEditFetchIntervalClicked = null, onMigrateClicked = null,
                        onEditNotesClicked = {}, onMultiBookmarkClicked = { _, _ -> },
                        onMultiMarkAsReadClicked = { _, _ -> }, onMarkPreviousAsReadClicked = {},
                        onMultiDeleteClicked = {},
                        onChapterSwipe = { _, _ -> }, onChapterSelected = { _, _, _ -> },
                        onAllChapterSelected = {}, onInvertSelection = {},
                    )
                }
            }
            for (isTablet in listOf(false, true)) {
                compose.runOnIdle { tablet.value = isTablet }
                compose.mainClock.advanceTimeBy(500)
                val resumeText = activity.get().stringResource(MR.strings.action_resume)
                compose.onNode(
                    hasClickAction() and hasAnyDescendant(hasText(resumeText)),
                    useUnmergedTree = true,
                ).assertIsNotDisplayed()
            }
        } finally {
            activity.pause().stop().destroy()
            model.onDispose()
        }
    }

    @Test
    fun `chapter mark buttons reach the shared explicit user command`() = runTest {
        val captured = Channel<List<ChapterUpdate>>(Channel.UNLIMITED)
        val repository = mockk<ChapterRepository> {
            coEvery { updateAll(any()) } coAnswers { captured.send(firstArg()) }
        }
        val preferences = mockk<DownloadPreferences> {
            every { removeAfterMarkedAsRead().get() } returns false
        }
        val command = SetReadStatus(preferences, mockk(), mockk(), repository)
        val chapter = chapter(1)
        val model = screenModel(manga = manga(true), chapters = listOf(chapter), setReadStatus = command)
        try {
            awaitSuccess(model)
            for (read in listOf(true, false)) {
                model.markChaptersRead(listOf(chapter), read)
                val update = withContext(Dispatchers.Default) {
                    withTimeout(5_000) { captured.receive().single() }
                }
                assertEquals(read, update.read)
                assertEquals(SyncMutationContext.User, update.syncContext)
            }
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `manual detail refresh reaches combined source update once and persists memo`() = verifyRealRefresh(
        emptyChapters = false,
    )

    @Test
    fun `empty combined chapter response shows localized snackbar and clears refresh state`() = verifyRealRefresh(
        emptyChapters = true,
    )

    private fun verifyRealRefresh(emptyChapters: Boolean) = runTest {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY,
        )
        tachiyomi.data.Database.Schema.create(driver)
        val database = tachiyomi.data.Database(
            driver,
            historyAdapter = tachiyomi.data.History.Adapter(tachiyomi.data.DateColumnAdapter),
            mangasAdapter = tachiyomi.data.Mangas.Adapter(
                tachiyomi.data.StringListColumnAdapter,
                tachiyomi.data.UpdateStrategyColumnAdapter,
            ),
        )
        val handler = tachiyomi.data.AndroidDatabaseHandler(database, driver)
        val mangas = tachiyomi.data.manga.MangaRepositoryImpl(
            handler,
            tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter,
        )
        val chapters = tachiyomi.data.chapter.ChapterRepositoryImpl(handler)
        val stored = mangas.insertNetworkManga(listOf(manga(true).copy(id = -1, url = "/memo"))).single()
        driver.execute(null, "UPDATE mangas SET _id = $MANGA_ID WHERE _id = ${stored.id}", 0)
        val manga = mangas.getMangaById(MANGA_ID)
        val chapter = chapters.addAll(
            listOf(chapter(-1).copy(mangaId = MANGA_ID, url = "/chapter", chapterNumber = 1.0)),
        ).single()
        val memo = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"token":"UI"}""",
        ) as kotlinx.serialization.json.JsonObject
        val called = CompletableDeferred<Unit>()
        coEvery { source.getMangaUpdate(any(), any(), true, true) } answers {
            val sourceManga = firstArg<eu.kanade.tachiyomi.source.model.SManga>().apply { this.memo = memo }
            called.complete(Unit)
            eu.kanade.tachiyomi.source.model.SMangaUpdate(sourceManga, if (emptyChapters) emptyList() else secondArg())
        }
        val getChapters = tachiyomi.domain.chapter.interactor.GetChaptersByMangaId(chapters)
        val update = UpdateManga(mangas, tachiyomi.domain.manga.interactor.FetchInterval(getChapters))
        val sync = eu.kanade.domain.chapter.interactor.SyncChaptersWithSource(
            mockk(relaxed = true), mockk(relaxed = true), chapters,
            tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter(), update, UpdateChapter(chapters), getChapters,
            GetExcludedScanlators(handler), LibraryPreferences(preferenceStore),
        )
        val localizedResources = mockk<Resources>(relaxed = true) {
            every { getString(MR.strings.no_chapters_error.resourceId) } returns "No chapters found"
        }
        val localizedContext = mockk<Context>(relaxed = true) { every { resources } returns localizedResources }
        val model =
            screenModel(
                context = localizedContext,
                manga = manga,
                chapters = listOf(chapter),
                updateManga = update,
                mangaRepository = mangas,
                chapterRepository = chapters,
                syncChaptersWithSource = sync,
                getMangaWithChaptersOverride = GetMangaWithChapters(mangas, chapters),
            )
        try {
            awaitSuccess(model)
            model.fetchAllFromSource()
            testScheduler.runCurrent()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    called.await()
                    while (mangas.getMangaById(MANGA_ID).memo != memo) delay(10)
                }
            }
            coVerify(exactly = 1) { source.getMangaUpdate(any(), any(), true, true) }
            assertEquals(memo, mangas.getMangaById(MANGA_ID).memo)
            coVerify(exactly = 0) { source.getMangaDetails(any()) }
            coVerify(exactly = 0) { source.getChapterList(any()) }
            if (emptyChapters) {
                val snackbar = withContext(Dispatchers.Default) {
                    withTimeout(5_000) {
                        while (model.snackbarHostState.currentSnackbarData == null) {
                            testScheduler.runCurrent()
                            delay(10)
                        }
                        model.snackbarHostState.currentSnackbarData!!
                    }
                }
                assertEquals("No chapters found", snackbar.visuals.message)
                snackbar.dismiss()
                testScheduler.runCurrent()
                assertEquals(false, (model.state.value as MangaScreenModel.State.Success).isRefreshingData)
                assertEquals(listOf(chapter.id), chapters.getChapterByMangaId(MANGA_ID).map { it.id })
            }
        } finally {
            model.onDispose()
            driver.close()
        }
    }

    private lateinit var previousInjekt: InjektScope
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var preferenceStore: AndroidPreferenceStore
    private lateinit var lifecycleOwner: TestLifecycleOwner
    private lateinit var source: Source

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        previousInjekt = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton(RecordReadingProgress(readingRepository))
        val application = RuntimeEnvironment.getApplication()
        sharedPreferences = application.getSharedPreferences(
            "manga-screen-shared-mutations-${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        preferenceStore = AndroidPreferenceStore(application, sharedPreferences)
        Injekt.addSingleton(BasePreferences(application, preferenceStore))
        Injekt.addSingleton(UiPreferences(preferenceStore))
        Injekt.addSingleton(SourcePreferences(preferenceStore))
        Injekt.addSingleton(mockk<ManageCreatorIdentity>(relaxed = true))
        lifecycleOwner = TestLifecycleOwner().also {
            it.registry.currentState = Lifecycle.State.RESUMED
        }
        source = mockk(relaxed = true) {
            every { id } returns SOURCE_ID
            every { name } returns "Fixture source"
        }
        Injekt.addSingleton<SourceManager>(
            mockk {
                every { getOrStub(SOURCE_ID) } returns source
            },
        )
    }

    @After
    fun tearDown() {
        lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED
        Dispatchers.resetMain()
        Injekt = previousInjekt
        sharedPreferences.edit().clear().commit()
    }

    @Test
    fun `Android add with categories delegates one atomic shared membership request`() = runTest {
        val request = CompletableDeferred<LibraryMembershipUpdate>()
        val setMangaCategories = mockk<SetMangaCategories>(relaxed = true)
        val updateManga = mockk<UpdateManga>(relaxed = true)
        val manga = manga(favorite = false)
        val model = screenModel(
            manga = manga,
            chapters = emptyList(),
            setMangaCategories = setMangaCategories,
            updateManga = updateManga,
            updateLibraryMembership = UpdateLibraryMembership { request.complete(it) },
        )
        try {
            awaitSuccess(model)

            model.moveMangaToCategoriesAndAddToLibrary(manga, listOf(7L, 7L, 9L))

            val update = withContext(Dispatchers.Default) {
                withTimeout(5_000) { request.await() }
            }
            assertEquals(MANGA_ID, update.mangaId)
            assertTrue(update.favorite)
            assertTrue(update.dateAdded > 0)
            assertEquals(listOf(7L, 9L), update.categoryIds)
            coVerify(exactly = 0) { setMangaCategories.await(any(), any()) }
            coVerify(exactly = 0) { updateManga.awaitUpdateFavorite(any(), any()) }
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `Android bookmark batch continues after write failure and shows localized result`() = runTest {
        val attempted = Channel<Long>(Channel.UNLIMITED)
        val succeeded = Collections.synchronizedList(mutableListOf<Long>())
        val chapterRepository = mockk<ChapterRepository>(relaxed = true) {
            coEvery { update(any()) } answers {
                val update = firstArg<ChapterUpdate>()
                attempted.trySend(update.id)
                if (update.id == 2L) error("write failed")
                succeeded += update.id
            }
        }
        val chapters = listOf(chapter(1L), chapter(2L), chapter(3L))
        val localizedResources = mockk<Resources>(relaxed = true) {
            every {
                getString(MR.strings.chapter_batch_update_result.resourceId, 2, 1)
            } returns "2 succeeded, 1 failed"
        }
        val localizedContext = mockk<Context>(relaxed = true) {
            every { resources } returns localizedResources
        }
        val model = screenModel(
            context = localizedContext,
            manga = manga(favorite = true),
            chapters = chapters,
            updateChapter = UpdateChapter(chapterRepository),
            batchUpdateChapters = BatchUpdateChapters(),
        )
        try {
            awaitSuccess(model)
            val mutation = model.bookmarkChapters(chapters, bookmarked = true)

            assertEquals(
                listOf(1L, 2L, 3L),
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { List(3) { attempted.receive() } }
                },
            )
            val snackbar = withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    while (model.snackbarHostState.currentSnackbarData == null) delay(10)
                    model.snackbarHostState.currentSnackbarData!!
                }
            }
            assertEquals("2 succeeded, 1 failed", snackbar.visuals.message)
            assertEquals(listOf(1L, 3L), succeeded)
            snackbar.dismiss()
            mutation.join()
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `Android previous actual wrapper preserves stable duplicate prefix in both directions`() = runTest {
        val chapters = listOf(3.0, -1.0, 2.5, 2.5, 7.0, 1.0).mapIndexed { index, number ->
            chapter(index + 1L).copy(chapterNumber = number)
        }
        for (direction in listOf(Manga.CHAPTER_SORT_ASC, Manga.CHAPTER_SORT_DESC)) {
            val captured = Channel<List<ChapterUpdate>>(Channel.UNLIMITED)
            val repository = mockk<ChapterRepository> {
                coEvery { updateAll(any()) } coAnswers { captured.send(firstArg()) }
            }
            val preferences = mockk<DownloadPreferences> {
                every { removeAfterMarkedAsRead().get() } returns false
            }
            val model = screenModel(
                manga = manga(true).copy(chapterFlags = Manga.CHAPTER_SORTING_NUMBER or direction),
                chapters = chapters,
                setReadStatus = SetReadStatus(preferences, mockk(), mockk(), repository),
            )
            try {
                awaitSuccess(model)
                model.markPreviousChapterRead(chapters[2])
                val updates = withContext(Dispatchers.Default) { withTimeout(5000) { captured.receive() } }
                assertEquals(listOf(2L, 6L), updates.map { it.id })
                assertTrue(updates.all { it.read == true && it.syncContext == SyncMutationContext.User })
            } finally {
                model.onDispose()
            }
        }
    }

    @Test
    fun `Android bookmark actual wrapper propagates cancellation without writes or snackbar`() = runTest {
        val attempted = Collections.synchronizedList(mutableListOf<Long>())
        val repository = mockk<ChapterRepository> {
            coEvery { update(any()) } answers {
                attempted += firstArg<ChapterUpdate>().id
                throw kotlinx.coroutines.CancellationException("cancel actual batch")
            }
        }
        val model = screenModel(
            manga = manga(true),
            chapters = listOf(chapter(1), chapter(2)),
            updateChapter = UpdateChapter(repository),
            batchUpdateChapters = BatchUpdateChapters(),
        )
        try {
            awaitSuccess(model)
            val mutation = model.bookmarkChapters(listOf(chapter(1), chapter(2)), true)
            withContext(Dispatchers.Default) { withTimeout(5000) { mutation.join() } }
            assertTrue(mutation.isCancelled)
            assertEquals(listOf(1L), attempted)
            assertEquals(null, model.snackbarHostState.currentSnackbarData)
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `Android previous first valid pointer clears selection with no writes stale pointer does not`() = runTest {
        val chapters = listOf(chapter(1).copy(chapterNumber = 1.0), chapter(2).copy(chapterNumber = 2.0))
        val repository = mockk<ChapterRepository>(relaxed = true)
        val preferences = mockk<DownloadPreferences> { every { removeAfterMarkedAsRead().get() } returns false }
        val model = screenModel(
            manga = manga(true).copy(
                chapterFlags =
                Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_ASC,
            ),
            chapters = chapters,
            setReadStatus = SetReadStatus(preferences, mockk(), mockk(), repository),
        )
        try {
            awaitSuccess(model)
            model.toggleAllSelection(true)
            assertEquals(2, (model.state.value as MangaScreenModel.State.Success).chapters.count { it.selected })
            model.markPreviousChapterRead(chapter(99))
            assertEquals(2, (model.state.value as MangaScreenModel.State.Success).chapters.count { it.selected })
            model.markPreviousChapterRead(chapters[0])
            assertTrue((model.state.value as MangaScreenModel.State.Success).chapters.none { it.selected })
            coVerify(exactly = 0) { repository.updateAll(any()) }
        } finally {
            model.onDispose()
        }
    }

    private fun screenModel(
        context: Context = RuntimeEnvironment.getApplication(),
        manga: Manga,
        chapters: List<Chapter>,
        setMangaCategories: SetMangaCategories = mockk(relaxed = true),
        updateManga: UpdateManga = mockk(relaxed = true) {
            coEvery {
                awaitFromRemote(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns (manga to emptyList())
        },
        updateChapter: UpdateChapter = mockk(relaxed = true),
        updateLibraryMembership: UpdateLibraryMembership = UpdateLibraryMembership { },
        batchUpdateChapters: BatchUpdateChapters = BatchUpdateChapters(),
        setReadStatus: SetReadStatus = mockk(relaxed = true),
        mangaRepository: MangaRepository = mockk(relaxed = true),
        chapterRepository: ChapterRepository = mockk(relaxed = true),
        syncChaptersWithSource: eu.kanade.domain.chapter.interactor.SyncChaptersWithSource = mockk(relaxed = true),
        getMangaWithChaptersOverride: GetMangaWithChapters? = null,
        defaultsOverride: SetMangaDefaultChapterFlags? = null,
        downloadManagerOverride: DownloadManager? = null,
    ): MangaScreenModel {
        Injekt.addSingleton(chapterRepository)
        Injekt.addSingleton(mockk<eu.kanade.tachiyomi.data.cache.CoverCache>(relaxed = true))
        val getMangaWithChapters = getMangaWithChaptersOverride ?: mockk<GetMangaWithChapters> {
            coEvery { subscribe(MANGA_ID, applyScanlatorFilter = true) } returns flowOf(manga to chapters)
            coEvery { awaitManga(MANGA_ID) } returns manga
            coEvery { awaitChapters(MANGA_ID, applyScanlatorFilter = true) } returns chapters
        }
        val availableScanlators = mockk<GetAvailableScanlators> {
            every { subscribe(MANGA_ID) } returns flowOf(emptySet())
            coEvery { await(MANGA_ID) } returns emptySet()
        }
        val excludedScanlators = mockk<GetExcludedScanlators> {
            every { subscribe(MANGA_ID) } returns flowOf(emptySet())
            coEvery { await(MANGA_ID) } returns emptySet()
        }
        val downloadManager = downloadManagerOverride ?: mockk<DownloadManager>(relaxed = true) {
            every { queueState } returns MutableStateFlow(emptyList())
            every { statusFlow() } returns emptyFlow()
            every { progressFlow() } returns emptyFlow()
        }
        val downloadCache = mockk<DownloadCache> {
            every { changes } returns MutableSharedFlow(replay = 1)
        }
        val libraryPreferences = LibraryPreferences(preferenceStore)

        return MangaScreenModel(
            context = context,
            lifecycle = lifecycleOwner.lifecycle,
            mangaId = MANGA_ID,
            isFromSource = false,
            libraryPreferences = libraryPreferences,
            trackPreferences = TrackPreferences(preferenceStore),
            readerPreferences = ReaderPreferences(preferenceStore),
            trackerManager = TrackerManager(emptyList()),
            trackChapter = mockk<TrackChapter>(relaxed = true),
            downloadManager = downloadManager,
            downloadCache = downloadCache,
            getMangaAndChapters = getMangaWithChapters,
            getDuplicateLibraryManga = mockk<GetDuplicateLibraryManga>(relaxed = true),
            getAvailableScanlators = availableScanlators,
            getExcludedScanlators = excludedScanlators,
            setExcludedScanlators = mockk<SetExcludedScanlators>(relaxed = true),
            setMangaChapterFlags = mockk<SetMangaChapterFlags>(relaxed = true),
            setMangaDefaultChapterFlags = defaultsOverride ?: mockk<SetMangaDefaultChapterFlags>(relaxed = true),
            setReadStatus = setReadStatus,
            updateChapter = updateChapter,
            updateManga = updateManga,
            syncChaptersWithSource = syncChaptersWithSource,
            getCategories = mockk<GetCategories>(relaxed = true),
            getTracks = mockk<GetTracks> {
                every { subscribe(MANGA_ID) } returns flowOf(emptyList())
            },
            addTracks = mockk<AddTracks>(relaxed = true),
            setMangaCategories = setMangaCategories,
            mangaRepository = mangaRepository,
            filterChaptersForDownload = mockk(relaxed = true),
            updateLibraryMembership = updateLibraryMembership,
            batchUpdateChapters = batchUpdateChapters,
        )
    }

    private suspend fun awaitSuccess(model: MangaScreenModel) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (model.state.value !is MangaScreenModel.State.Success) delay(10)
            }
        }
    }

    private fun manga(favorite: Boolean): Manga {
        return Manga.create().copy(
            id = MANGA_ID,
            source = SOURCE_ID,
            title = "Fixture manga",
            favorite = favorite,
            initialized = true,
        )
    }

    private fun chapter(id: Long): Chapter {
        return Chapter.create().copy(
            id = id,
            mangaId = MANGA_ID,
            name = "Chapter $id",
            bookmark = false,
        )
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }

    private companion object {
        const val MANGA_ID = 42L
        const val SOURCE_ID = 7L
    }
}
