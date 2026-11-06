package tachiyomi.domain.creator.service

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import kotlinx.coroutines.runBlocking
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class CatalogueCreatorMetadataBoundaryTest {
    @Test
    fun `registered creator metadata keeps poisoned ID usable and isolates name language failures per source`() =
        runBlocking<Unit> {
            val observed = mutableListOf<String>()
            for (site in listOf("id", "name", "language")) {
                val bad = Fixture(10L, site)
                val healthy = Fixture(20L)
                val adapter = adapter(bad, healthy)
                val attempt = runCatching { adapter.enabledSourcesSnapshot() }
                val failure = attempt.exceptionOrNull()
                if (failure != null) {
                    observed += "$site:escaped:${failure.javaClass.simpleName}"
                    continue
                }
                assertEquals(listOf(10L, 20L), attempt.getOrThrow().map { it.sourceId })
                val badResult = adapter.searchPage(request(10L))
                val healthyResult = adapter.searchPage(request(20L))
                val kind = (badResult as? CreatorSourcePageResult.Failure)?.error?.javaClass?.simpleName
                    ?: badResult.javaClass.simpleName
                observed += "$site:$kind:${healthyResult.javaClass.simpleName}:${bad.idReads}:${healthy.idReads}"
                assertEquals(1, bad.nameReads)
                assertEquals(1, bad.languageReads)
                assertEquals(if (site == "id") 1 else 0, bad.searches)
                assertEquals(1, healthy.searches)
            }
            assertEquals(
                listOf(
                    "id:Empty:Empty:0:0",
                    "name:ExtensionIncompatible:Empty:0:0",
                    "language:ExtensionIncompatible:Empty:0:0",
                ),
                observed,
            )
        }

    @Test
    fun `unknown creator registration is visible without probing ID or inventing a source result`() =
        runBlocking<Unit> {
            val observed = mutableListOf<String>()
            for (site in listOf("id", "name", "language")) {
                val source = Fixture(10L, site)
                val adapter = CatalogueCreatorDiscoverySourceAdapter(
                    enabledSourcesProvider = { listOf(source) },
                    sourceResolver = { source },
                    registeredSourceIdProvider = { null },
                    clock = { 1_000L },
                )
                val failure = runCatching { adapter.enabledSourcesSnapshot() }.exceptionOrNull()
                val mapped = (failure as? AppErrorException)?.error
                val kind = mapped?.javaClass?.simpleName ?: failure?.javaClass?.simpleName
                observed += "$site:$kind:${source.idReads}:${source.searches}"
            }
            assertEquals(
                listOf(
                    "id:MalformedData:0:0",
                    "name:ExtensionIncompatible:0:0",
                    "language:ExtensionIncompatible:0:0",
                ),
                observed,
            )
        }

    @Test
    fun `creator metadata cancellation fatal and host Error remain control before another getter`() =
        runBlocking<Unit> {
            val cancelled = kotlinx.coroutines.CancellationException("cancelled")
            val fatal = object : VirtualMachineError("controlled") {}
            val host = AssertionError("host Error")
            for ((thrown, expected) in listOf(
                java.io.IOException("wrapped", cancelled) to cancelled,
                ExceptionInInitializerError(fatal) to fatal,
                host to host,
            )) {
                val source = Fixture(10L, "id").apply { nameFailure = thrown }
                val failure = runCatching { adapter(source).enabledSourcesSnapshot() }.exceptionOrNull()
                assertSame(expected, failure)
                assertEquals(0, source.idReads)
                assertEquals(0, source.languageReads)
            }
        }

    @Test
    fun `creator operation captures owner before metadata and refreshes host snapshot on next call`() =
        runBlocking<Unit> {
            val source = Fixture(10L, "name")
            var version = "old"
            var factories = 0
            val adapter = CatalogueCreatorDiscoverySourceAdapter(
                enabledSourcesProvider = { listOf(source) },
                sourceResolver = { source },
                registeredSourceIdProvider = { 10L },
                clock = { 1_000L },
                identitySnapshotProvider = {
                    factories++
                    val frozen = version
                    val lookup: CreatorSourceIdentityLookup = { _, id ->
                        eu.kanade.tachiyomi.network.SourceFailureIdentity(id, "trusted.creator", "Creator", frozen, 1L)
                    }
                    lookup
                },
            )
            source.beforeName = { version = "new" }
            assertEquals("old+1", adapter.enabledSourcesSnapshot().single().extensionVersion)
            val first = (adapter.searchPage(request(10L)) as CreatorSourcePageResult.Failure).error
                as CreatorSourceFailure.ExtensionIncompatible
            val marker = first.cause as eu.kanade.tachiyomi.network.SourceCompatibilityException
            assertEquals("old", marker.identity?.versionName)
            assertEquals("new+1", adapter.enabledSourcesSnapshot().single().extensionVersion)
            assertEquals(2, factories)
            assertEquals(0, source.idReads)
            assertEquals(0, source.searches)
        }

    @Test
    fun `unknown excluded language does not fail selected healthy creator source`() =
        runBlocking<Unit> {
            val excluded = Fixture(10L, "id").apply { language = "fr" }
            val healthy = Fixture(20L)
            val adapter = CatalogueCreatorDiscoverySourceAdapter(
                enabledSourcesProvider = { listOf(excluded, healthy) },
                sourceResolver = { null },
                registeredSourceIdProvider = { if (it === healthy) 20L else null },
                languageEligibilityProvider = { { language -> language == "en" } },
                clock = { 1_000L },
            )
            assertEquals(listOf(20L), adapter.enabledSourcesSnapshot().map { it.sourceId })
            assertEquals(CreatorSourcePageResult.Empty, adapter.searchPage(request(20L)))
            assertEquals(0, excluded.idReads)
            assertEquals(0, excluded.nameReads)
            assertEquals(1, excluded.languageReads)
            assertEquals(0, excluded.searches)
            assertEquals(1, healthy.searches)
        }

    @Test
    fun `explicit creator candidates never resolve to another configured source with the same ID`() =
        runBlocking<Unit> {
            val explicit = Fixture(10L)
            val configured = Fixture(10L)
            val adapter = CatalogueCreatorDiscoverySourceAdapter(
                enabledSourcesProvider = { listOf(configured) },
                sourceResolver = { configured },
                clock = { 1_000L },
            ).forExplicitSources(
                listOf(explicit),
                tachiyomi.domain.source.service.SourceMangaSearchService(),
                { 1_000L },
                CreatorTitleAttributionPolicy.Disabled,
            )
            assertEquals(listOf(10L), adapter.enabledSourcesSnapshot().map { it.sourceId })
            assertEquals(CreatorSourcePageResult.Empty, adapter.searchPage(request(10L)))
            assertEquals(1, explicit.searches)
            assertEquals(0, configured.searches)
        }

    private fun adapter(vararg sources: Fixture) = CatalogueCreatorDiscoverySourceAdapter(
        enabledSourcesProvider = { sources.toList() },
        sourceResolver = { id -> sources.firstOrNull { it.registration == id } },
        registeredSourceIdProvider = { candidate -> sources.firstOrNull { it === candidate }?.registration },
        clock = { 1_000L },
    )
    private fun request(id: Long) = BoundedAuthorSearchPageRequest(id, "Artist", 1, 1, 2_000L)

    private class Fixture(val registration: Long, private val site: String? = null) : CatalogueSource {
        var idReads = 0
        var nameReads = 0
        var languageReads = 0
        var searches = 0
        var nameFailure: Throwable? = null
        var beforeName: () -> Unit = {}
        var language = "en"
        override val id: Long get() {
            idReads++
            if (site == "id") throw NoSuchMethodError("future ID")
            return registration
        }
        override val name: String get() {
            nameReads++
            beforeName()
            nameFailure?.let { throw it }
            if (site == "name") throw NoSuchMethodError("future name")
            return "Creator fixture"
        }
        override val lang: String get() {
            languageReads++
            if (site == "language") throw NoSuchMethodError("future language")
            return language
        }
        override val supportsLatest = false
        override fun getFilterList() = FilterList()
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            searches++
            return MangasPage(emptyList(), false)
        }
    }
}
