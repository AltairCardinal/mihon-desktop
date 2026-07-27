package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.BatchUpdateChapters
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.interactor.UpdateLibraryMembership
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.LibraryMembershipUpdate
import tachiyomi.domain.manga.repository.MangaRepository
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
        val application = RuntimeEnvironment.getApplication()
        sharedPreferences = application.getSharedPreferences(
            "manga-screen-shared-mutations-${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        preferenceStore = AndroidPreferenceStore(application, sharedPreferences)
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

    private fun screenModel(
        context: Context = RuntimeEnvironment.getApplication(),
        manga: Manga,
        chapters: List<Chapter>,
        setMangaCategories: SetMangaCategories = mockk(relaxed = true),
        updateManga: UpdateManga = mockk(relaxed = true),
        updateChapter: UpdateChapter = mockk(relaxed = true),
        updateLibraryMembership: UpdateLibraryMembership = UpdateLibraryMembership { },
        batchUpdateChapters: BatchUpdateChapters = BatchUpdateChapters(),
    ): MangaScreenModel {
        val getMangaWithChapters = mockk<GetMangaWithChapters> {
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
        val downloadManager = mockk<DownloadManager>(relaxed = true) {
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
            setMangaDefaultChapterFlags = mockk<SetMangaDefaultChapterFlags>(relaxed = true),
            setReadStatus = mockk<SetReadStatus>(relaxed = true),
            updateChapter = updateChapter,
            updateManga = updateManga,
            syncChaptersWithSource = mockk(relaxed = true),
            getCategories = mockk<GetCategories>(relaxed = true),
            getTracks = mockk<GetTracks> {
                every { subscribe(MANGA_ID) } returns flowOf(emptyList())
            },
            addTracks = mockk<AddTracks>(relaxed = true),
            setMangaCategories = setMangaCategories,
            mangaRepository = mockk<MangaRepository>(relaxed = true),
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
