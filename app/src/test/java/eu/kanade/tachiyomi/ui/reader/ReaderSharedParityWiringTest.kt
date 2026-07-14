package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.ui.reader.loader.cancelAndroidPreloadJob
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.toSharedPageModel
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagePairingAlgorithm
import kotlinx.coroutines.Job
import mihon.domain.reader.ChapterSkipPolicy
import mihon.domain.reader.PageLayout
import mihon.domain.reader.PreloadJobKey
import mihon.domain.reader.ReaderChapterEntry
import mihon.domain.reader.ReaderColorFilterEffect
import mihon.domain.reader.ReaderPagePairing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
    fun `Android transition holders consume shared chapter state flow`() {
        val pager = source("app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerTransitionHolder.kt")
        val webtoon =
            source("app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonTransitionHolder.kt")
        val navigation = source("app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ViewerNavigation.kt")

        assertTrue(pager.contains("sharedStateFlow"))
        assertTrue(webtoon.contains("sharedStateFlow"))
        assertTrue(pager.contains("state.retryCommand()"))
        assertTrue(webtoon.contains("state.retryCommand()"))
        assertTrue(navigation.contains("mihon.domain.reader.ReaderNavigation"))
    }

    @Test
    fun `Android HTTP page loader consumes the shared forward preload planner`() {
        val loader = source("app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoader.kt")

        assertTrue(loader.contains("ReaderPreloadPlanner"))
        assertTrue(loader.contains("backwardWindowSize = 0"))
        assertTrue(loader.contains("cancelRequests"))
        assertTrue(loader.contains("request.jobKey"))
        assertTrue(!loader.contains("private fun preloadNextPages"))
    }

    @Test
    fun `Android cancels an active preload job only when its generation key is stale`() {
        val key = PreloadJobKey(pageIndex = 2, generation = 4)
        val active = Job()

        assertTrue(cancelAndroidPreloadJob(key, active, setOf(key)))
        assertTrue(active.isCancelled)

        val retained = Job()
        assertTrue(!cancelAndroidPreloadJob(key, retained, setOf(key.copy(generation = 3))))
        assertTrue(retained.isActive)
    }

    @Test
    fun `Android grayscale and invert preferences are mapped to the shared filter contract`() {
        val params = buildAndroidLayerFilterParams(grayscale = true, invertedColors = true)
        val activity = source("app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt")

        assertTrue(params.grayscaleEnabled)
        assertTrue(params.invertEnabled)
        assertTrue(params.isEffective)
        assertTrue(activity.contains("buildAndroidLayerFilterParams"))
        assertTrue(activity.contains("params.isEffective"))
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
        val activity = source("app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt")
        assertTrue(activity.contains("buildAndroidReaderColorFilterParams"))
    }

    @Test
    fun `Android reader chapter pipeline delegates read filtered and duplicate skipping to shared policy`() {
        val viewModel = source("app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt")

        assertTrue(viewModel.contains("filterChaptersForReader"))
        assertTrue(viewModel.contains("markDuplicateChapters"))
        assertTrue(viewModel.contains("ChapterSkipPolicy("))
        assertTrue(viewModel.contains("chapters = markedEntries"))
        assertTrue(!viewModel.contains("readerPreferences.skipRead().get() && it.read -> true"))
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

    private fun source(path: String): String {
        val cwd = File(System.getProperty("user.dir"))
        val root = if (File(cwd, "app").exists()) cwd else requireNotNull(cwd.parentFile)
        return File(root, path).readText()
    }
}
