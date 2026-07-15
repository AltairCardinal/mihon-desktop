package eu.kanade.tachiyomi.ui.reader

import androidx.lifecycle.SavedStateHandle
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mihon.domain.reader.ChapterSkipPolicy
import mihon.domain.reader.NavigationInversion
import mihon.domain.reader.NavigationPreset
import mihon.domain.reader.PageLayout
import mihon.domain.reader.ReaderChapterEntry
import mihon.domain.reader.ReaderColorFilterEffect
import mihon.domain.reader.ReaderNavigation
import mihon.domain.reader.ReaderPagePairing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import java.io.File

class ReaderSharedParityWiringTest {

    @Test
    fun `Android pairing adapter and shared default produce the same authoritative vectors`() {
        val android = PagePairingAlgorithm.buildPairings(5, { index -> index == 2 })
        val shared = ReaderPagePairing.build(
            pageCount = 5,
            layoutAt = { index -> if (index == 2) PageLayout.SPREAD else PageLayout.PORTRAIT },
        )

        assertEquals(shared.map(IntArray::toList), android.map(IntArray::toList))
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3, 4)), android.map(IntArray::toList))
    }

    @Test
    fun `Android page adapter exposes platform neutral page metadata`() {
        val shared = ReaderPage(index = 4, url = "/page/4", imageUrl = "https://example/4.jpg").toSharedPageModel()

        assertEquals(4, shared.index)
        assertEquals("/page/4", shared.url)
        assertEquals("https://example/4.jpg", shared.imageUrl)
    }

    @Test
    fun `Android navigation adapters match every shared preset and inversion`() {
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
    fun `Android grayscale and invert preferences are mapped to the shared filter contract`() {
        val params = buildAndroidLayerFilterParams(grayscale = true, invertedColors = true)

        assertTrue(params.grayscaleEnabled)
        assertTrue(params.invertEnabled)
        assertTrue(params.isEffective)
    }

    @Test
    fun `Android maps tint brightness grayscale and invert as independent shared effects`() {
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
    fun `Android ReaderViewModel forwards the logical page selection to the production loader`() = runTest {
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
    fun `Android recomputes duplicates after read and filtered candidates are removed`() {
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
    fun `Android production chapter pipeline maps real chapter metadata before applying shared skip policy`() {
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
    fun `Android ReaderViewModel getChapterList delegates the sorted production list to the shared skip filter`() {
        val readerViewModel = productionSource("app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt")
        val getChapterList = bracedBlock(readerViewModel, "private suspend fun getChapterList()")

        assertEquals(1, occurrenceCount(getChapterList, "filterAndroidReaderChapters("))
        val delegate = callBlock(getChapterList, "filterAndroidReaderChapters(")
        assertTrue(delegate.contains("chapters = sortedChapters"))
        assertTrue(delegate.contains("currentChapterId = chapterId"))
        assertTrue(delegate.contains("skipPolicy = skipPolicy"))
        assertTrue(
            delegate.contains("isFiltered = { chapter -> skipPolicy.filtered && isChapterFiltered(manga, chapter) }"),
        )
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

    private fun productionSource(path: String): String {
        var current: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (current != null && !current.resolve("settings.gradle.kts").isFile) current = current.parentFile
        return requireNotNull(current) { "Repository root not found from ${System.getProperty("user.dir")}" }
            .resolve(path)
            .readText()
    }

    private fun bracedBlock(source: String, marker: String): String {
        val start = source.indexOf(marker)
        require(start >= 0) { "Missing production block: $marker" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unclosed production block: $marker")
    }

    private fun callBlock(source: String, marker: String): String {
        val start = source.indexOf(marker)
        require(start >= 0) { "Missing production call: $marker" }
        val open = source.indexOf('(', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unclosed production call: $marker")
    }

    private fun occurrenceCount(source: String, marker: String): Int = Regex(
        Regex.escape(marker),
    ).findAll(source).count()
}
