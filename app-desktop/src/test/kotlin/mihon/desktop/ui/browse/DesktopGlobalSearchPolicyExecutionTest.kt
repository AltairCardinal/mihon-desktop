package mihon.desktop.ui.browse

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mihon.domain.error.AppError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.GlobalSearchSourceFilter
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourceQueryState
import tachiyomi.domain.source.service.SourceRecoveryAction
import tachiyomi.domain.source.service.sourceFailureIdentity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DesktopGlobalSearchPolicyExecutionTest {

    @Test
    fun `routing artifact and matching source id never supply an unconfirmed owner`() = runBlocking {
        val active = RecordingSearchSource(1)
        val other = RecordingSearchSource(1)
        coEvery { active.source.getSearchManga(1, any(), any()) } throws NoSuchMethodError("future API")
        val unknown = mihon.desktop.extension.InstalledExtension(
            java.io.File("routing-name+0.jar"),
            listOf(active.source),
            versionName = "fabricated-version",
        )
        val unrelated = mihon.desktop.extension.InstalledExtension(
            java.io.File("other.jar"),
            listOf(other.source),
            metadataSnapshot = mihon.desktop.extension.ExtensionMeta("other.package", 123L, "1.2.3"),
        )
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())
        try {
            coordinator.search(
                listOf(active.source),
                "query",
                GlobalSearchSourceFilter.All,
                listOf(unrelated, unknown),
            )
            val failure = assertInstanceOf(SourceQueryState.Failure::class.java, coordinator.state.queryStates[1L])
            val identity = requireNotNull(failure.error.sourceFailureIdentity())
            assertEquals(1L, identity.sourceId)
            assertNull(identity.extensionPackage)
            assertNull(identity.extensionName)
            assertNull(identity.versionName)
            assertNull(identity.versionCode)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `replacement source with the same id is searched again for the same query`() = runBlocking {
        val old = RecordingSearchSource(1)
        val replacement = RecordingSearchSource(1)
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())
        try {
            coordinator.search(listOf(old.source), "query")
            coordinator.search(listOf(replacement.source), "query")
            assertEquals(1, old.searchCount.get())
            assertEquals(1, replacement.searchCount.get())
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `cancelled filter retry restores the previous failure`() = runBlocking {
        val source = RecordingSearchSource(1)
        every { source.source.getFilterList() } throws java.io.IOException("temporary filter failure")
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())
        try {
            coordinator.search(listOf(source.source), "query")
            val child = coordinator.coordinatorFor(1L)!!
            val before = child.state
            every { source.source.getFilterList() } throws kotlinx.coroutines.CancellationException("cancel filters")
            val error = runCatching { child.retry(source.source) }.exceptionOrNull()
            assertInstanceOf(kotlinx.coroutines.CancellationException::class.java, error)
            assertEquals(before, child.state)
            assertEquals(0, source.searchCount.get())
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `retry after filter IO failure prepares filters before searching`() = runBlocking {
        val source = RecordingSearchSource(1)
        every { source.source.getFilterList() } throws java.io.IOException("temporary filter failure")
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())
        try {
            coordinator.search(listOf(source.source), "query")
            val failure = assertInstanceOf(SourceQueryState.Failure::class.java, coordinator.state.queryStates[1L])
            assertEquals(SourceRecoveryAction.Retry, failure.recoveryAction)
            val filters = FilterList()
            every { source.source.getFilterList() } returns filters
            coordinator.coordinatorFor(1L)!!.retry(source.source)
            io.mockk.verify(exactly = 2) { source.source.getFilterList() }
            assertEquals(1, source.searchCount.get())
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `incompatible filter preparation fails only its source and next query recovers`() = runBlocking {
        val failed = RecordingSearchSource(1)
        val healthy = RecordingSearchSource(2)
        val missing = NoSuchMethodError("future filter API")
        every { failed.source.getFilterList() } throws missing
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())
        try {
            coordinator.search(listOf(failed.source, healthy.source), "query")
            val failure = assertInstanceOf(SourceQueryState.Failure::class.java, coordinator.state.queryStates[1L])
            assertInstanceOf(AppError.ExtensionIncompatible::class.java, failure.error)
            assertEquals(SourceRecoveryAction.None, failure.recoveryAction)
            assertInstanceOf(SourceQueryState.Empty::class.java, coordinator.state.queryStates[2L])
            assertEquals(false, coordinator.state.isSearching)
            assertEquals(0, failed.searchCount.get())
            assertEquals(1, healthy.searchCount.get())

            every { failed.source.getFilterList() } returns FilterList()
            coordinator.search(listOf(failed.source, healthy.source), "next")
            assertInstanceOf(SourceQueryState.Empty::class.java, coordinator.state.queryStates[1L])
            assertEquals(1, failed.searchCount.get())
            assertEquals(false, coordinator.state.isSearching)
        } finally {
            coordinator.close()
        }
    }

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

        coordinator.search(
            listOf(pinned.source, unpinned.source),
            "different",
            sourceFilter = GlobalSearchSourceFilter.All,
        )
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
                try {
                    release.await()
                } finally {
                    active.decrementAndGet()
                }
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

    @Test
    fun `blocking source search never occupies the caller dispatcher`() = runBlocking {
        val sourceEntered = CountDownLatch(1)
        val releaseSource = CountDownLatch(1)
        val source = RecordingSearchSource(7) {
            sourceEntered.countDown()
            check(releaseSource.await(5, TimeUnit.SECONDS)) { "timed out releasing blocking source" }
        }.source
        val coordinator = DesktopGlobalSearchCoordinator(SourceMangaSearchService())
        val uiDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val search = async(uiDispatcher) {
            coordinator.search(listOf(source), "query", sourceFilter = GlobalSearchSourceFilter.All)
        }

        try {
            assertTrue(sourceEntered.await(2, TimeUnit.SECONDS), "blocking source never started")
            val nextUiTurn = async(uiDispatcher) { Unit }

            val uiStayedResponsive = withTimeoutOrNull(250) { nextUiTurn.await() }

            releaseSource.countDown()
            withTimeout(2_000) { search.await() }
            assertEquals(Unit, uiStayedResponsive, "source work blocked the caller UI dispatcher")
        } finally {
            releaseSource.countDown()
            runCatching { withTimeout(2_000) { search.await() } }
            uiDispatcher.close()
        }
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
