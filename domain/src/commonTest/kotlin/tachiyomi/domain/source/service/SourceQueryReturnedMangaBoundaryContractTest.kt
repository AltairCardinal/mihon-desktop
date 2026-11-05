package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import mihon.domain.error.AppError
import mihon.domain.manga.model.FutureSourceManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.io.IOException

class SourceQueryReturnedMangaBoundaryContractTest {
    @Test
    fun `real query reduction isolates returned URL linkage for each operation and retains previous page`() {
        for ((query, phase) in listOf(
            SourceQuery.Popular to SourceOperationPhase.POPULAR,
            SourceQuery.Latest to SourceOperationPhase.LATEST,
            SourceQuery.Search("query", FilterList()) to SourceOperationPhase.SEARCH,
        )) {
            val request = SourcePageRequest(9L, 1, 1L, query)
            val original = NoSuchMethodError("future URL")
            val result = SourcePageResult.Content(request, listOf(FutureSourceManga("url", original)), false)
            val reducer = SourceQueryReducer()
            val failure =
                assertInstanceOf(SourceQueryState.Failure::class.java, reducer.reduce(reducer.start(request), result))
            assertInstanceOf(AppError.ExtensionIncompatible::class.java, failure.error)
            val marker = assertInstanceOf(SourceCompatibilityException::class.java, failure.error.cause)
            assertSame(original, marker.cause)
            assertEquals(phase, marker.phase)
            assertEquals(SourceRecoveryAction.None, failure.recoveryAction)
            val healthy = SManga.create().apply {
                url = "/previous"
                title = "Previous"
            }
            val next = request.copy(page = 2)
            val previous = SourceQueryState.Content(request, listOf(healthy), true)
            val append = assertInstanceOf(
                SourceQueryState.Content::class.java,
                reducer.reduce(reducer.start(next, previous), result.copy(request = next)),
            )
            assertEquals(listOf(healthy), append.items)
            assertEquals(SourceRecoveryAction.None, append.pageError?.recoveryAction)
        }
    }

    @Test
    fun `returned URL controls and host errors still escape query reduction`() {
        val request = SourcePageRequest(9L, 1, 1L, SourceQuery.Popular)
        val fatal = object : VirtualMachineError("controlled") {}
        val cancel = kotlinx.coroutines.CancellationException("cancelled")
        val host = AssertionError("host")
        for ((thrown, expected) in listOf(
            IOException("wrapped", fatal) to fatal,
            ExceptionInInitializerError(cancel) to cancel,
            host to host,
        )) {
            val reducer = SourceQueryReducer()
            val result = SourcePageResult.Content(request, listOf(FutureSourceManga("url", thrown)), false)
            val actual = runCatching { reducer.reduce(reducer.start(request), result) }.exceptionOrNull()
            assertSame(expected, actual)
        }
    }
}
