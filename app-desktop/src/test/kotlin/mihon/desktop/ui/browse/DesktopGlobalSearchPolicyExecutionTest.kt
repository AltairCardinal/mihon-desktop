package mihon.desktop.ui.browse

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.GlobalSearchSourceFilter
import tachiyomi.domain.source.service.SourceMangaSearchService
import java.util.concurrent.atomic.AtomicInteger

class DesktopGlobalSearchPolicyExecutionTest {

    @Test
    fun `same search is reused filter expansion loads only new sources and query change reloads all`() = runBlocking {
        val pinned = RecordingSearchSource(1)
        val unpinned = RecordingSearchSource(2)
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())

        coordinator.search(listOf(pinned.source), "query", sourceFilter = GlobalSearchSourceFilter.PinnedOnly)
        coordinator.search(listOf(pinned.source), "query", sourceFilter = GlobalSearchSourceFilter.PinnedOnly)
        assertEquals(1, pinned.searchCount.get(), "an identical search must not issue another source request")

        coordinator.search(listOf(pinned.source, unpinned.source), "query", sourceFilter = GlobalSearchSourceFilter.All)
        assertEquals(1, pinned.searchCount.get(), "filter expansion must retain completed intersection state")
        assertEquals(1, unpinned.searchCount.get(), "filter expansion must request only newly included sources")

        coordinator.search(listOf(pinned.source, unpinned.source), "different", sourceFilter = GlobalSearchSourceFilter.All)
        assertEquals(2, pinned.searchCount.get())
        assertEquals(2, unpinned.searchCount.get())
    }

    @Test
    fun `global search runs at most five source requests concurrently`() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val fiveStarted = CompletableDeferred<Unit>()
        val sixConcurrent = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sources = (1L..6L).map { id ->
            RecordingSearchSource(id) {
                val now = active.incrementAndGet()
                maximum.updateAndGet { max -> maxOf(max, now) }
                if (now >= 5) fiveStarted.complete(Unit)
                if (now >= 6) sixConcurrent.complete(Unit)
                try { release.await() } finally { active.decrementAndGet() }
            }.source
        }
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())
        val search = async { coordinator.search(sources, "query", sourceFilter = GlobalSearchSourceFilter.All) }

        withTimeout(2_000) { fiveStarted.await() }
        val exceededBeforeRelease = withTimeoutOrNull(250) { sixConcurrent.await() }
        release.complete(Unit)
        withTimeout(2_000) { search.await() }

        assertNull(exceededBeforeRelease, "a sixth request must wait until a permit is released")
        assertTrue(maximum.get() <= 5, "observed ${maximum.get()} concurrent source requests")
    }

    private class RecordingSearchSource(id: Long, beforeResult: suspend () -> Unit = {}) {
        val searchCount = AtomicInteger()
        val source = mockk<CatalogueSource> {
            every { this@mockk.id } returns id
            every { getFilterList() } returns FilterList()
            coEvery { getSearchManga(1, any(), any()) } coAnswers {
                searchCount.incrementAndGet()
                beforeResult()
                MangasPage(emptyList(), false)
            }
        }
    }
}
