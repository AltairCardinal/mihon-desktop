package tachiyomi.domain.creator.service

import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.source.AuthorSearchPage
import eu.kanade.tachiyomi.source.AuthorSearchSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.model.SourceWorkNaturalKey

class CatalogueCreatorCompatibilityContractTest {
    @Test
    fun `adapter keeps filter search and details ABI failures distinct from network`() = runTest {
        val observations = mutableListOf<String>()
        for (site in listOf("filters", "search", "details")) {
            val missing = NoSuchMethodError("private future $site ABI")
            val source = Fixture(site, missing)
            val adapter = adapter(source)
            adapter.enabledSourcesSnapshot()
            var escaped: Throwable? = null
            val result = try {
                if (site == "details") {
                    (
                        adapter.loadDetails(
                            SourceWorkNaturalKey(source.id, "/work"),
                        ) as CreatorSourceDetailsResult.Failure
                        ).error
                } else {
                    (adapter.searchPage(request(source.id)) as CreatorSourcePageResult.Failure).error
                }
            } catch (error: Throwable) {
                escaped = error
                null
            }
            val observedType = escaped?.let { "escaped:${it.javaClass.simpleName}" } ?: result?.javaClass?.simpleName
            observations += "$site:$observedType:${source.calls}"
            if (result is CreatorSourceFailure.ExtensionIncompatible) {
                val transport = result.cause as SourceCompatibilityException
                assertSame(missing, transport.cause)
                assertEquals(
                    when (site) {
                        "filters" -> SourceOperationPhase.FILTERS
                        "search" -> SourceOperationPhase.SEARCH
                        else -> SourceOperationPhase.DETAILS
                    },
                    transport.phase,
                )
            }
        }
        assertEquals(
            listOf(
                "filters:ExtensionIncompatible:1",
                "search:ExtensionIncompatible:1",
                "details:ExtensionIncompatible:1",
            ),
            observations,
        )
    }

    @Test
    fun `incompatible structured author hook never silently falls back to catalogue search`() = runTest {
        val base = Fixture("none", null)
        var authorCalls = 0
        val source = object : AuthorSearchSource, CatalogueSource by base {
            override suspend fun getAuthorSearchManga(page: Int, author: String): AuthorSearchPage {
                authorCalls++
                throw NoSuchMethodError("private future author ABI")
            }
        }
        val adapter = adapter(source)
        adapter.enabledSourcesSnapshot()
        var escaped: Throwable? = null
        val result = try {
            adapter.searchPage(request(source.id))
        } catch (error: Throwable) {
            escaped = error
            null
        }
        assertNull(escaped)
        assertEquals(
            "ExtensionIncompatible",
            (result as? CreatorSourcePageResult.Failure)?.error?.javaClass?.simpleName,
        )
        assertEquals(1, authorCalls)
        assertEquals(0, base.searchCalls)
    }

    @Test
    fun `adapter preserves wrapped control and unrelated host Errors at the actual hook`() = runTest {
        val fatal = object : VirtualMachineError("controlled creator fatal") {}
        for (expected in listOf<Throwable>(
            fatal,
            CancellationException("creator cancelled"),
            AssertionError("host failure"),
        )) {
            val source = Fixture("filters", java.io.IOException("wrapped hook", expected))
            val adapter = adapter(source)
            adapter.enabledSourcesSnapshot()
            val actual = runCatching { adapter.searchPage(request(source.id)) }.exceptionOrNull()
            if (expected is CancellationException) {
                assertTrue(
                    actual === expected || actual?.cause === expected,
                    "Coroutine stacktrace recovery may copy cancellation",
                )
            } else {
                assertSame(expected, actual)
            }
            assertEquals(1, source.calls)
        }
    }

    private fun adapter(source: CatalogueSource) = CatalogueCreatorDiscoverySourceAdapter(
        enabledSourcesProvider = { listOf(source) },
        sourceResolver = { source },
        clock = { 1_000 },
    )

    private fun request(id: Long) = BoundedAuthorSearchPageRequest(id, "Artist", 1, 1, 2_000)

    private class Fixture(private val site: String, private val failure: Throwable?) : CatalogueSource {
        override val id = 81L
        override val name = "Creator fixture"
        override val lang = "en"
        override val supportsLatest = false
        var calls = 0
        var searchCalls = 0
        private fun fail(at: String) {
            if (site == at) {
                calls++
                failure?.let { throw it }
            }
        }
        override fun getFilterList(): FilterList {
            fail("filters")
            return FilterList()
        }
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            searchCalls++
            fail("search")
            return MangasPage(emptyList(), false)
        }
        override suspend fun getMangaDetails(manga: SManga): SManga {
            fail("details")
            return manga
        }
        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()
        override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()
    }
}
