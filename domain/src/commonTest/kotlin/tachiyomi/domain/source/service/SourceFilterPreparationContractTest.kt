package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class SourceFilterPreparationContractTest {
    @Test
    fun `default search filters fail visibly before requesting any page`() {
        val source = FilterSource(NoSuchMethodError("future filter API"))
        val error = assertThrows(AppErrorException::class.java) {
            runBlocking { SourceMangaSearchService().searchAllPages(source, "private query") }
        }
        assertInstanceOf(AppError.ExtensionIncompatible::class.java, error.error)
        assertEquals(0, source.searchCalls)
    }

    @Test
    fun `preparation returns typed compatibility failure and retains healthy filter identity`() {
        val healthy = FilterSource()
        assertSame(
            healthy.filters,
            assertInstanceOf(SourceFilterResult.Content::class.java, prepareSourceFilters(healthy)).filters,
        )
        val broken = FilterSource(AbstractMethodError("future filters"))
        val result = assertInstanceOf(SourceFilterResult.Failure::class.java, prepareSourceFilters(broken))
        assertInstanceOf(AppError.ExtensionIncompatible::class.java, result.error)
        assertEquals(0, broken.searchCalls)
    }

    @Test
    fun `preparation preserves control and host Error and maps ordinary source IO`() {
        val fatal = object : VirtualMachineError("controlled") {}
        val cancel = CancellationException("filter cancelled")
        val ordinary = AssertionError("ordinary source bug")
        for (expected in listOf<Throwable>(fatal, cancel, ordinary)) {
            val error = if (expected === ordinary) expected else ExceptionInInitializerError(expected)
            assertSame(expected, assertThrows(expected.javaClass) { prepareSourceFilters(FilterSource(error)) })
        }
        val failure = assertInstanceOf(
            SourceFilterResult.Failure::class.java,
            prepareSourceFilters(FilterSource(IOException("source filter IO"))),
        )
        assertInstanceOf(AppError.Network::class.java, failure.error)
    }

    private class FilterSource(val failure: Throwable? = null) : Source {
        override val id = 601L
        override val name = "Filter source"
        val filters = FilterList()
        var searchCalls = 0
        override fun getFilterList(): FilterList {
            failure?.let { throw it }
            return filters
        }
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            searchCalls++
            return MangasPage(emptyList(), false)
        }
    }
}
