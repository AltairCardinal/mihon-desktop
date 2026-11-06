package mihon.desktop.ui.browse

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.MangasPage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.error.AppError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourceQuery
import tachiyomi.domain.source.service.SourceQueryState
import java.io.IOException

class SourceFailureDiagnosticsProductionTest {
    @Test
    fun `browse logs its known request ID with unknown owner and survives ordinary writer failure`() = runBlocking {
        val original = NoSuchMethodError("private original message")
        val source = mockk<Source> { every { id } returns 9L }
        coEvery { source.getPopularManga(1) } throws original
        val records = mutableListOf<String>()
        val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService(), null) {
            records += it
            throw IOException("controlled sink failure")
        }
        val result = coordinator.load(source, 1, SourceQuery.Popular)
        val failed = assertInstanceOf(SourceQueryState.Failure::class.java, result)
        assertInstanceOf(AppError.ExtensionIncompatible::class.java, failed.error)
        assertTrue(generateSequence(failed.error.cause) { it.cause }.any { it === original })
        val report = records.single()
        assertTrue(report.contains("source=9, package=unknown"), report)
        assertTrue(report.contains("operation=POPULAR"), report)
        assertSame(result, coordinator.state)
    }

    @Test
    fun `late rejected failure writes no diagnostic for a superseded request`() = runBlocking {
        withTimeout(5_000) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val source = mockk<Source> { every { id } returns 9L }
            coEvery { source.getPopularManga(1) } coAnswers {
                started.complete(Unit)
                release.await()
                throw NoSuchMethodError("late error")
            }
            coEvery { source.getLatestUpdates(1) } returns MangasPage(emptyList(), false)
            val records = mutableListOf<String>()
            val coordinator = SourceBrowseQueryCoordinator(SourceMangaSearchService(), null, records::add)
            val old = async { coordinator.load(source, 1, SourceQuery.Popular) }
            try {
                started.await()
                val current = coordinator.load(source, 1, SourceQuery.Latest)
                release.complete(Unit)
                old.await()
                assertSame(current, coordinator.state)
                assertEquals(0, records.size)
            } finally {
                release.complete(Unit)
                old.cancel()
            }
        }
    }
}
