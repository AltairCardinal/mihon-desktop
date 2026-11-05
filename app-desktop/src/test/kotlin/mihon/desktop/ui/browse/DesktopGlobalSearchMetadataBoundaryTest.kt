package mihon.desktop.ui.browse

import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.inspectSourceFailure
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mihon.desktop.extension.ExtensionMeta
import mihon.desktop.extension.InstalledExtension
import mihon.domain.error.AppError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.GlobalSearchSourceFilter
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourceQueryState
import tachiyomi.domain.source.service.prepareSourceMetadata
import tachiyomi.domain.source.service.sourceFailureIdentity
import java.io.File
import java.io.IOException

class DesktopGlobalSearchMetadataBoundaryTest {
    @Test
    fun `cancelled metadata recovery restores the previous snapshot with the previous failure`() = runBlocking {
        val source = FixtureSource("Recovered").apply { presentationError = IOException("metadata IO") }
        DesktopGlobalSearchCoordinator(SourceMangaSearchService(), {}).use { coordinator ->
            coordinator.searchPrepared(listOf(prepareSourceMetadata(source, 9L)), "query")
            val child = requireNotNull(coordinator.coordinatorFor(9L))
            val failed = assertInstanceOf(SourceQueryState.Failure::class.java, child.state)
            val previous = child.metadata
            val cancellation = kotlinx.coroutines.CancellationException("filter cancelled")
            source.presentationError = null
            source.filterError = cancellation
            val actual = runCatching { child.retry(source, failed.request) }.exceptionOrNull()
            assertInstanceOf(kotlinx.coroutines.CancellationException::class.java, actual)
            assertEquals(cancellation.message, actual?.message)
            assertSame(failed, child.state)
            assertSame(previous, child.metadata)
            source.filterError = null
            coordinator.searchPrepared(listOf(prepareSourceMetadata(source, 9L)), "query")
            assertInstanceOf(SourceQueryState.Empty::class.java, coordinator.state.queryStates[9L])
            assertEquals(1, source.searchCalls)
        }
    }

    @Test
    fun `returned manga URL failure is a completed source error before host reduction`() = runBlocking {
        val source = mockk<Source>()
        val original = NoSuchMethodError("future returned URL")
        val returned = mockk<SManga> { every { url } throws original }
        every { source.getFilterList() } returns FilterList()
        coEvery { source.getSearchManga(1, any(), any()) } returns MangasPage(listOf(returned), false)
        val snapshot = tachiyomi.domain.source.service.SourceMetadataSnapshot(source, 9L, "Returned", "en", null)
        DesktopGlobalSearchCoordinator(SourceMangaSearchService(), {}).use { coordinator ->
            coordinator.searchPrepared(listOf(snapshot), "query")
            val failed = assertInstanceOf(SourceQueryState.Failure::class.java, coordinator.state.queryStates[9L])
            assertInstanceOf(AppError.ExtensionIncompatible::class.java, failed.error)
            assertSame(original, failed.error.cause?.inspectSourceFailure()?.linkage)
            assertFalse(coordinator.state.isSearching)
        }
    }

    @Test
    fun `login target is read only on recovery and its linkage failure replaces authentication`() = runBlocking {
        val source = mockk<HttpSource>()
        val original = NoSuchMethodError("future login target")
        var urlReads = 0
        every { source.getFilterList() } returns FilterList()
        every { source.baseUrl } answers {
            urlReads++
            throw original
        }
        coEvery { source.getSearchManga(1, any(), any()) } throws eu.kanade.tachiyomi.network.HttpException(401)
        val snapshot = tachiyomi.domain.source.service.SourceMetadataSnapshot(source, 9L, "Login", "en", null)
        val logs = mutableListOf<String>()
        DesktopGlobalSearchCoordinator(SourceMangaSearchService(), logs::add).use { coordinator ->
            coordinator.searchPrepared(listOf(snapshot), "query")
            val child = requireNotNull(coordinator.coordinatorFor(9L))
            val ui = GlobalSearchStateProjector.projectPrepared(coordinator.state.sourceMetadata, coordinator.state)
            assertEquals(
                tachiyomi.domain.source.service.SourceRecoveryAction.OpenLogin,
                ui.results.single().error?.recoveryAction,
            )
            assertEquals(0, urlReads)
            assertEquals(DesktopSourceRecoveryIntent.None, child.recoveryIntent(source))
            val failed = assertInstanceOf(SourceQueryState.Failure::class.java, child.state)
            assertInstanceOf(AppError.ExtensionIncompatible::class.java, failed.error)
            assertSame(original, failed.error.cause?.inspectSourceFailure()?.linkage)
            assertEquals(tachiyomi.domain.source.service.SourceRecoveryAction.None, failed.recoveryAction)
            assertEquals(1, urlReads)
            assertEquals(1, logs.size)
        }
    }

    @Test
    fun `prepared same-name sources never reread ID or render through extension getters`() = runBlocking {
        val broken = FixtureSource("Same header")
        val healthy = FixtureSource("Same header")
        val original = NoSuchMethodError("future search")
        broken.searchError = original
        val metadata = listOf(prepareSourceMetadata(broken, 9L), prepareSourceMetadata(healthy, 10L))
        broken.presentationError = NoSuchMethodError("late metadata")
        healthy.presentationError = NoSuchMethodError("late metadata")
        DesktopGlobalSearchCoordinator(SourceMangaSearchService(), {}).use { coordinator ->
            coordinator.searchPrepared(metadata, "query")
            assertEquals(setOf(9L, 10L), coordinator.state.queryStates.keys)
            val failure = assertInstanceOf(SourceQueryState.Failure::class.java, coordinator.state.queryStates[9L])
            assertSame(original, failure.error.cause?.inspectSourceFailure()?.linkage)
            assertInstanceOf(SourceQueryState.Empty::class.java, coordinator.state.queryStates[10L])
            val ui = GlobalSearchStateProjector.projectPrepared(coordinator.state.sourceMetadata, coordinator.state)
            assertEquals(2, ui.total)
            assertEquals(2, ui.completed)
            assertFalse(ui.loading)
            assertEquals(setOf(9L, 10L), ui.results.map { it.metadata?.sourceId }.toSet())
            listOf(broken, healthy).forEach {
                assertEquals(0, it.idReads)
                assertEquals(1, it.nameReads)
                assertEquals(1, it.languageReads)
                assertEquals(1, it.searchCalls)
            }
        }
    }

    @Test
    fun `metadata failures retain frozen owner before filters and unknown IDs never dispatch API`() = runBlocking {
        val broken = FixtureSource("Broken").apply { presentationError = NoSuchMethodError("name ABI") }
        val unknown = FixtureSource("Unknown")
        val healthy = FixtureSource("Healthy")
        val metadata = listOf(
            prepareSourceMetadata(broken, 9L),
            prepareSourceMetadata(unknown, null),
            prepareSourceMetadata(healthy, 10L),
        )
        val owner = InstalledExtension(
            File("not-an-identity.jar"),
            listOf(broken),
            metadataSnapshot = ExtensionMeta("trusted.package", 123L, "1.2.3", name = "Trusted extension"),
        )
        val logs = mutableListOf<String>()
        DesktopGlobalSearchCoordinator(SourceMangaSearchService(), logs::add).use { coordinator ->
            coordinator.searchPrepared(metadata, "query", installed = listOf(owner))
            val failed = assertInstanceOf(SourceQueryState.Failure::class.java, coordinator.state.queryStates[9L])
            assertInstanceOf(AppError.ExtensionIncompatible::class.java, failed.error)
            assertEquals("trusted.package", failed.error.sourceFailureIdentity()?.extensionPackage)
            assertEquals("1.2.3", failed.error.sourceFailureIdentity()?.versionName)
            assertEquals(setOf(9L, 10L), coordinator.state.queryStates.keys)
            val ui = GlobalSearchStateProjector.projectPrepared(coordinator.state.sourceMetadata, coordinator.state)
            val row = ui.results.single { it.source === unknown }
            assertNull(row.metadata?.sourceId)
            assertInstanceOf(AppError.Unknown::class.java, row.error?.error)
            assertEquals(3, ui.completed)
            assertFalse(ui.loading)
            assertEquals(0, broken.filterCalls)
            assertEquals(0, broken.searchCalls)
            assertEquals(0, unknown.filterCalls)
            assertEquals(0, unknown.searchCalls)
            assertEquals(1, healthy.searchCalls)
            assertTrue(logs.single().contains("operation=${SourceOperationPhase.METADATA}"))
            assertTrue(logs.single().contains("package=trusted.package"))
        }
    }

    @Test
    fun `metadata IO retry prepares fresh fields while preserving the frozen source ID`() = runBlocking {
        val source = FixtureSource("Recovered").apply { presentationError = IOException("transient metadata") }
        DesktopGlobalSearchCoordinator(SourceMangaSearchService(), {}).use { coordinator ->
            coordinator.searchPrepared(listOf(prepareSourceMetadata(source, 9L)), "query")
            val child = requireNotNull(coordinator.coordinatorFor(9L))
            val failed = assertInstanceOf(SourceQueryState.Failure::class.java, child.state)
            assertEquals(0, source.searchCalls)
            source.presentationError = null
            child.retry(source, failed.request)
            assertInstanceOf(SourceQueryState.Empty::class.java, child.state)
            assertEquals(1, source.searchCalls)
            assertEquals(0, source.idReads)
        }
    }

    @Test
    fun `host source selection skips hidden and unpinned getters and excluded language names`() {
        val hidden = FixtureSource("Hidden").apply { presentationError = AssertionError("hidden getter") }
        val excluded = FixtureSource("Excluded", "fr").apply { nameError = AssertionError("excluded name") }
        val unpinned = FixtureSource("Unpinned").apply { presentationError = AssertionError("unpinned getter") }
        val healthy = FixtureSource("Healthy")
        val candidates = listOf(hidden, excluded, unpinned, healthy)
        val manager = object : SourceManager by mockk(relaxed = true) {
            override fun getQuerySources(): List<Source> = candidates
            override fun getRegisteredSourceId(source: Source): Long? =
                candidates.indexOfFirst { it === source }.takeIf { it >= 0 }?.let { 9L + it }
        }
        val selected = prepareGlobalSearchSources(
            manager,
            setOf("en"),
            setOf("9"),
            setOf("9", "10", "12"),
            GlobalSearchSourceFilter.PinnedOnly,
        )
        assertEquals(1, selected.size)
        assertSame(healthy, selected.single().source)
        assertEquals(0, hidden.nameReads)
        assertEquals(0, hidden.languageReads)
        assertEquals(0, unpinned.nameReads)
        assertEquals(0, unpinned.languageReads)
        assertEquals(0, excluded.nameReads)
        assertEquals(1, excluded.languageReads)
    }

    private class FixtureSource(private val label: String, private val language: String = "en") : Source {
        var presentationError: Throwable? = null
        var nameError: Throwable? = null
        var searchError: Throwable? = null
        var filterError: Throwable? = null
        var idReads = 0
        var nameReads = 0
        var languageReads = 0
        var filterCalls = 0
        var searchCalls = 0
        override val id: Long get() {
            idReads++
            throw NoSuchMethodError("registered ID must not be read")
        }
        override val name: String get() {
            nameReads++
            (nameError ?: presentationError)?.let { throw it }
            return label
        }
        override val lang: String get() {
            languageReads++
            presentationError?.let { throw it }
            return language
        }
        override fun getFilterList(): FilterList {
            filterCalls++
            filterError?.let { throw it }
            return FilterList()
        }
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            searchCalls++
            searchError?.let { throw it }
            return MangasPage(emptyList(), false)
        }
    }
}
