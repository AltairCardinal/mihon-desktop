package tachiyomi.domain.creator.service

import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.source.AuthorSearchManga
import eu.kanade.tachiyomi.source.AuthorSearchPage
import eu.kanade.tachiyomi.source.AuthorSearchSource
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import mihon.domain.manga.model.FutureSourceManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.model.SourceWorkNaturalKey

class CatalogueCreatorMangaResultBoundaryTest {
    @Test
    fun `structured author results isolate delayed fields and cache copy from healthy parallel source`() =
        runBlocking<Unit> {
            for (field in listOf("url", "title", "author", "artist", "thumbnail_url", "genre", "copy")) {
                val missing = java.io.IOException("delayed result", NoSuchMethodError("future $field"))
                val broken = Fixture(FutureSourceManga(field, missing))
                val healthy = Fixture(
                    SManga.create().apply {
                        url = "/healthy"
                        title = "Healthy"
                    },
                )
                val badPort = adapter(broken, 9L)
                val goodPort = adapter(healthy, 10L)
                badPort.enabledSourcesSnapshot()
                goodPort.enabledSourcesSnapshot()
                val results = coroutineScope {
                    val bad = async { runCatching { badPort.searchPage(request(9L)) } }
                    val good = async { goodPort.searchPage(request(10L)) }
                    bad.await() to good.await()
                }
                assertNull(results.first.exceptionOrNull(), field)
                val failure = assertInstanceOf(
                    CreatorSourcePageResult.Failure::class.java,
                    results.first.getOrThrow(),
                ).error
                val compatibility = assertInstanceOf(CreatorSourceFailure.ExtensionIncompatible::class.java, failure)
                val marker = assertInstanceOf(SourceCompatibilityException::class.java, compatibility.cause)
                assertSame(missing, marker.cause)
                assertEquals(SourceOperationPhase.SEARCH, marker.phase)
                assertInstanceOf(CreatorSourcePageResult.Content::class.java, results.second)
                assertEquals(1, healthy.searches)
            }
        }

    @Test
    fun `detail returned fields classify incompatibility and preserve wrapped controls`() =
        runBlocking<Unit> {
            val fatal = object : VirtualMachineError("controlled") {}
            val cancel = kotlinx.coroutines.CancellationException("cancelled")
            val host = AssertionError("host")
            val missing = java.io.IOException("returned detail", NoSuchMethodError("future description"))
            for (failure in listOf<Throwable>(
                missing,
                java.io.IOException("wrapped", fatal),
                ExceptionInInitializerError(cancel),
                host,
            )) {
                val source = Fixture(FutureSourceManga("description", failure))
                val port = adapter(source, 9L)
                port.enabledSourcesSnapshot()
                val actual = runCatching { port.loadDetails(SourceWorkNaturalKey(9L, "/work")) }
                if (failure === missing) {
                    assertNull(actual.exceptionOrNull())
                    val result = assertInstanceOf(CreatorSourceDetailsResult.Failure::class.java, actual.getOrThrow())
                    val typed = assertInstanceOf(CreatorSourceFailure.ExtensionIncompatible::class.java, result.error)
                    val marker = assertInstanceOf(SourceCompatibilityException::class.java, typed.cause)
                    assertSame(missing, marker.cause)
                    assertEquals(SourceOperationPhase.DETAILS, marker.phase)
                } else {
                    val expected = when (failure) {
                        is java.io.IOException -> fatal
                        is ExceptionInInitializerError -> cancel
                        else -> host
                    }
                    assertSame(expected, actual.exceptionOrNull())
                }
            }
        }

    private fun adapter(source: Fixture, id: Long) = CatalogueCreatorDiscoverySourceAdapter(
        enabledSourcesProvider = { listOf(source) },
        sourceResolver = { source },
        registeredSourceIdProvider = { id },
        clock = { 1_000L },
    )
    private fun request(id: Long) = BoundedAuthorSearchPageRequest(id, "Author", 1, 1, 2_000L)
    private class Fixture(private val returned: SManga) : AuthorSearchSource {
        override val id = 9L
        override val name = "Returned manga fixture"
        override val lang = "en"
        override val supportsLatest = false
        override fun getFilterList() = eu.kanade.tachiyomi.source.model.FilterList()
        var searches = 0
        override suspend fun getAuthorSearchManga(page: Int, authorQuery: String): AuthorSearchPage {
            searches++
            return AuthorSearchPage(listOf(AuthorSearchManga(returned, emptyList())), false)
        }
        override suspend fun getMangaDetails(manga: SManga) = returned
    }
}
