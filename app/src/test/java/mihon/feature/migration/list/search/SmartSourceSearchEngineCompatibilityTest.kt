package mihon.feature.migration.list.search

import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.inspectSourceFailure
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.io.IOException

class SmartSourceSearchEngineCompatibilityTest {
    @Test
    fun `real smart search classifies filters API title and legacy ID reads with their phase`() = runBlocking {
        val engine = SmartSourceSearchEngine(null)
        for (site in listOf("filters", "search", "title", "id")) {
            val missing = NoSuchMethodError("private $site")
            val source = source()
            when (site) {
                "filters" -> every { source.getFilterList() } throws missing
                "search" -> coEvery { source.getSearchManga(any(), any(), any()) } throws missing
                "id" -> every { source.id } throws missing
                else -> {
                    val base = SManga.create().apply {
                        url = "/bad"
                        title = "Candidate"
                    }
                    val bad = object : SManga by base {
                        override var title: String
                            get() = throw missing
                            set(value) {
                                base.title = value
                            }
                    }
                    coEvery { source.getSearchManga(any(), any(), any()) } returns MangasPage(listOf(bad, base), false)
                }
            }
            val failure = runCatching { engine.regularSearch(source, "Candidate") }.exceptionOrNull()
            val marker = assertInstanceOf(SourceCompatibilityException::class.java, failure, site)
            assertSame(missing, marker.inspectSourceFailure().linkage, site)
            assertEquals(
                if (site == "filters") {
                    SourceOperationPhase.FILTERS
                } else if (site == "id") {
                    SourceOperationPhase.METADATA
                } else {
                    SourceOperationPhase.SEARCH
                },
                marker.phase,
                site,
            )
        }
    }

    @Test
    fun `smart search propagates wrapped controls and host errors and keeps healthy matching`() = runBlocking {
        val engine = SmartSourceSearchEngine(null)
        val fatal = object : VirtualMachineError("controlled") {}
        val cancelled = kotlinx.coroutines.CancellationException("controlled")
        val host = AssertionError("host")
        for (expected in listOf(fatal, cancelled, host)) {
            for (atId in listOf(false, true)) {
                val source = source()
                if (atId) {
                    every { source.id } throws IOException("wrapper", expected)
                } else {
                    every { source.getFilterList() } throws IOException("wrapper", expected)
                }
                val actual = runCatching { engine.regularSearch(source, "Candidate") }.exceptionOrNull()
                if (expected === cancelled) {
                    assertInstanceOf(kotlinx.coroutines.CancellationException::class.java, actual)
                    assertEquals(cancelled.message, actual?.message)
                } else {
                    assertSame(expected, actual)
                }
            }
        }
        val result = SmartSourceSearchEngine("extra").regularSearch(source(), "Candidate")
        assertEquals("/candidate", result?.url)
        assertEquals(9L, result?.source)
    }

    private fun source() = mockk<CatalogueSource> {
        every { id } returns 9L
        every { getFilterList() } returns FilterList()
        coEvery { getSearchManga(any(), any(), any()) } returns MangasPage(
            listOf(
                SManga.create().apply {
                    url = "/candidate"
                    title = "Candidate"
                },
            ),
            false,
        )
    }
}
