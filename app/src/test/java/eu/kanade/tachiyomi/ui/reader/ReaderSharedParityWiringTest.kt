package eu.kanade.tachiyomi.ui.reader

import androidx.lifecycle.SavedStateHandle
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.toSharedPageModel
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.EdgeNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.KindlishNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.LNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagePairingAlgorithm
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mihon.domain.reader.ChapterSkipPolicy
import mihon.domain.reader.NavigationInversion
import mihon.domain.reader.NavigationPreset
import mihon.domain.reader.PageLayout
import mihon.domain.reader.PagePairingOptions
import mihon.domain.reader.ReaderChapterEntry
import mihon.domain.reader.ReaderColorFilterEffect
import mihon.domain.reader.ReaderNavigation
import mihon.domain.reader.ReaderPagePairing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

class ReaderSharedParityWiringTest {

    @Test
    fun `fork-added pairing adapter and shared core produce the same enhancement vectors`() {
        val android = PagePairingAlgorithm.buildPairings(5, { index -> index == 2 })
        val shared = ReaderPagePairing.build(
            pageCount = 5,
            layoutAt = { index -> if (index == 2) PageLayout.SPREAD else PageLayout.PORTRAIT },
            options = PagePairingOptions(pairAdjacentPortraitPages = true),
        )

        assertEquals(shared.map(IntArray::toList), android.map(IntArray::toList))
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3, 4)), android.map(IntArray::toList))
    }

    @Test
    fun `current Android consumer page adapter exposes platform neutral page metadata`() {
        val shared = ReaderPage(index = 4, url = "/page/4", imageUrl = "https://example/4.jpg").toSharedPageModel()

        assertEquals(4, shared.index)
        assertEquals("/page/4", shared.url)
        assertEquals("https://example/4.jpg", shared.imageUrl)
    }

    @Test
    fun `current Android consumer navigation adapters match every shared preset and inversion`() {
        val adapters = listOf(
            RightAndLeftNavigation() to NavigationPreset.RIGHT_AND_LEFT,
            KindlishNavigation() to NavigationPreset.KINDLE,
            LNavigation() to NavigationPreset.L,
            EdgeNavigation() to NavigationPreset.EDGE,
            DisabledNavigation() to NavigationPreset.DISABLED,
        )
        val inversions = listOf(
            ReaderPreferences.TappingInvertMode.NONE to NavigationInversion.NONE,
            ReaderPreferences.TappingInvertMode.HORIZONTAL to NavigationInversion.HORIZONTAL,
            ReaderPreferences.TappingInvertMode.VERTICAL to NavigationInversion.VERTICAL,
            ReaderPreferences.TappingInvertMode.BOTH to NavigationInversion.BOTH,
        )

        adapters.forEach { (adapter, preset) ->
            inversions.forEach { (androidInversion, sharedInversion) ->
                adapter.invertMode = androidInversion
                val expected = ReaderNavigation.regions(preset).map { it.inverted(sharedInversion) }
                assertEquals(expected, adapter.getNormalizedRegions())
            }
        }
    }

    @Test
    fun `current Android consumer grayscale and invert preferences map to the shared filter contract`() {
        val params = buildAndroidLayerFilterParams(grayscale = true, invertedColors = true)

        assertTrue(params.grayscaleEnabled)
        assertTrue(params.invertEnabled)
        assertTrue(params.isEffective)
    }

    @Test
    fun `current Android consumer maps tint brightness grayscale and invert as independent shared effects`() {
        val tintOnly = buildAndroidReaderColorFilterParams(tintEnabled = true, alpha = 128)
        val brightnessOnly = buildAndroidReaderColorFilterParams(brightnessEnabled = true, brightness = -0.5f)
        val grayscaleOnly = buildAndroidReaderColorFilterParams(grayscaleEnabled = true)
        val invertOnly = buildAndroidReaderColorFilterParams(invertEnabled = true)

        assertEquals(listOf(ReaderColorFilterEffect.TINT), tintOnly.activeEffects)
        assertEquals(listOf(ReaderColorFilterEffect.BRIGHTNESS), brightnessOnly.activeEffects)
        assertEquals(listOf(ReaderColorFilterEffect.GRAYSCALE), grayscaleOnly.activeEffects)
        assertEquals(listOf(ReaderColorFilterEffect.INVERT), invertOnly.activeEffects)
    }

    @Test
    fun `current Android consumer forwards logical page selection to production loader`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val downloadPreferences = mockk<DownloadPreferences>(relaxed = true)
            every { downloadPreferences.autoDownloadWhileReading().get() } returns 0
            val viewModel = ReaderViewModel(
                savedState = SavedStateHandle(),
                sourceManager = mockk(relaxed = true),
                downloadManager = mockk(relaxed = true),
                downloadProvider = mockk(relaxed = true),
                imageSaver = mockk(relaxed = true),
                readerPreferences = mockk(relaxed = true),
                basePreferences = mockk(relaxed = true),
                downloadPreferences = downloadPreferences,
                trackPreferences = mockk(relaxed = true),
                trackChapter = mockk(relaxed = true),
                getManga = mockk(relaxed = true),
                getChaptersByMangaId = mockk(relaxed = true),
                getNextChapters = mockk(relaxed = true),
                upsertHistory = mockk(relaxed = true),
                updateChapter = mockk(relaxed = true),
                recordReadingProgress = mockk(relaxed = true),
                setMangaViewerFlags = mockk(relaxed = true),
                getIncognitoState = mockk(relaxed = true),
                libraryPreferences = mockk(relaxed = true),
            )
            val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1))
            val page = ReaderPage(index = 2).apply { this.chapter = chapter }
            var selectedPage: ReaderPage? = null
            chapter.pageLoader = object : PageLoader() {
                override var isLocal = false
                override suspend fun getPages() = emptyList<ReaderPage>()
                override fun onPageSelected(page: ReaderPage) {
                    selectedPage = page
                }
            }

            viewModel.onPageSelected(page)

            assertSame(page, selectedPage)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `current Android consumer recomputes duplicates after read and filtered candidates are removed`() {
        val entries = listOf(
            ReaderChapterEntry(1, isRead = true, chapterNumber = 4.0, scanlator = "A"),
            ReaderChapterEntry(2, chapterNumber = 4.0, scanlator = "B"),
            ReaderChapterEntry(3, chapterNumber = 3.0, scanlator = "A"),
        )

        val result = filterAndroidReaderChapterEntries(
            entries = entries,
            currentChapterId = 3,
            skipPolicy = ChapterSkipPolicy(read = true, duplicate = true),
        )

        assertEquals(setOf(2L, 3L), result.mapTo(mutableSetOf(), ReaderChapterEntry::id))
    }

    @Test
    fun `current Android consumer chapter pipeline maps metadata before applying shared skip policy`() {
        val chapters = listOf(
            chapter(id = 41, number = 4.0, scanlator = "A", read = true),
            chapter(id = 42, number = 4.0, scanlator = "B"),
            chapter(id = 31, number = 3.0, scanlator = "A"),
            chapter(id = 32, number = 3.0, scanlator = "B"),
            chapter(id = 21, number = 2.0, scanlator = "A"),
        )

        val result = filterAndroidReaderChapters(
            chapters = chapters,
            currentChapterId = 42,
            skipPolicy = ChapterSkipPolicy(read = true, filtered = true, duplicate = true),
            isFiltered = { it.id == 31L },
        )

        assertEquals(listOf(42L, 32L, 21L), result.map(Chapter::id))
    }

    @Test
    fun `current Android ReaderViewModel applies shared skip policy before exposing adjacent chapters`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val manga = Manga.create().copy(
                id = 1,
                source = 7,
                title = "Reader parity",
                chapterFlags = Manga.CHAPTER_SORTING_NUMBER,
            )
            val chapters = listOf(
                chapter(id = 1, number = 1.0, scanlator = "A", read = true),
                chapter(id = 2, number = 2.0, scanlator = "A"),
                chapter(id = 3, number = 3.0, scanlator = "A"),
                chapter(id = 4, number = 4.0, scanlator = "A", read = true),
            )
            val source = mockk<Source>()
            val sourceManager = mockk<SourceManager> {
                every { isInitialized } returns MutableStateFlow(true)
                every { getOrStub(manga.source) } returns source
            }
            val getManga = mockk<GetManga>()
            coEvery { getManga.await(manga.id) } returns manga
            val getChapters = mockk<GetChaptersByMangaId>()
            coEvery { getChapters.await(manga.id, applyScanlatorFilter = true) } returns chapters
            val readerPreferences = mockk<ReaderPreferences>(relaxed = true)
            every { readerPreferences.skipRead().get() } returns true
            every { readerPreferences.skipFiltered().get() } returns false
            every { readerPreferences.skipDupe().get() } returns false
            val basePreferences = mockk<BasePreferences>(relaxed = true)
            every { basePreferences.downloadedOnly().get() } returns false
            val downloadPreferences = mockk<DownloadPreferences>(relaxed = true)
            every { downloadPreferences.autoDownloadWhileReading().get() } returns 0
            val chapterLoader = mockk<eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader>(relaxed = true)
            val viewModel = ReaderViewModel(
                savedState = SavedStateHandle(),
                sourceManager = sourceManager,
                downloadManager = mockk(relaxed = true),
                downloadProvider = mockk(relaxed = true),
                imageSaver = mockk(relaxed = true),
                readerPreferences = readerPreferences,
                basePreferences = basePreferences,
                downloadPreferences = downloadPreferences,
                trackPreferences = mockk(relaxed = true),
                trackChapter = mockk(relaxed = true),
                getManga = getManga,
                getChaptersByMangaId = getChapters,
                getNextChapters = mockk(relaxed = true),
                upsertHistory = mockk(relaxed = true),
                updateChapter = mockk(relaxed = true),
                recordReadingProgress = mockk(relaxed = true),
                setMangaViewerFlags = mockk(relaxed = true),
                getIncognitoState = mockk(relaxed = true),
                libraryPreferences = mockk(relaxed = true),
                chapterLoaderFactory = { _: Manga, _: Source -> chapterLoader },
            )

            assertTrue(viewModel.init(manga.id, initialChapterId = 3).isSuccess)

            val visible = requireNotNull(viewModel.state.value.viewerChapters)
            assertEquals(3L, visible.currChapter.chapter.id)
            assertEquals(2L, visible.prevChapter?.chapter?.id)
            assertEquals(null, visible.nextChapter)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun chapter(
        id: Long,
        number: Double,
        scanlator: String,
        read: Boolean = false,
    ) = Chapter.create().copy(
        id = id,
        mangaId = 1,
        read = read,
        chapterNumber = number,
        scanlator = scanlator,
    )
}
