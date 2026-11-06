package mihon.desktop.ui.browse

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mihon.domain.error.AppError
import mihon.domain.manga.model.toDomainManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceMetadataSnapshot
import tachiyomi.domain.source.service.SourcePageRequest
import tachiyomi.domain.source.service.SourceQuery
import tachiyomi.domain.source.service.SourceQueryState
import java.io.IOException

class SourceResultMaterializerFailureBoundaryTest {
    @Test
    fun `wrapped cancellation never becomes a materialization failure result`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val cancellation = kotlinx.coroutines.CancellationException("cancelled persistence")
        val materializer = SourceResultMaterializer(scope) { _, _ -> throw IOException("wrapped", cancellation) }
        try {
            materializer.sync(1, mapOf(9L to content(9L, manga())))
            assertTrue(materializer.results.isEmpty())
        } finally {
            materializer.close()
            scope.cancel()
        }
    }

    @Test
    fun `late returned URL failure stays within its source while healthy materialization completes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var persistedBroken = false
        val original = NoSuchMethodError("future late URL")
        val base = SManga.create().apply {
            url = "/broken"
            title = "Broken"
        }
        val broken = object : SManga by base {
            override var url: String
                get() = throw original
                set(value) {
                    base.url = value
                }
        }
        val healthy = SManga.create().apply {
            url = "/healthy"
            title = "Healthy"
        }
        val materializer = SourceResultMaterializer(scope) { items, id ->
            if (id == 9L) persistedBroken = true
            items.map { it.toDomainManga(id) }
        }
        try {
            val state = DesktopGlobalSearchState(
                1,
                queryStates = mapOf(
                    9L to content(9L, broken),
                    10L to content(10L, healthy),
                ),
            )
            assertNull(runCatching { materializer.sync(state) }.exceptionOrNull())
            val source = mockk<Source>()
            val ui = GlobalSearchStateProjector.projectPrepared(
                listOf(
                    SourceMetadataSnapshot(source, 9L, "Broken", "en", null),
                    SourceMetadataSnapshot(mockk(), 10L, "Healthy", "en", null),
                ),
                state,
                materializer.results,
            )
            assertInstanceOf(
                AppError.ExtensionIncompatible::class.java,
                ui.results.single {
                    it.metadata?.sourceId == 9L
                }.error?.error,
            )
            assertFalse(persistedBroken)
            assertEquals(listOf("Healthy"), ui.results.single { it.metadata?.sourceId == 10L }.results.map { it.title })
            assertFalse(ui.loading)
        } finally {
            materializer.close()
            scope.cancel()
        }
    }

    @Test
    fun `host storage Errors and wrapped fatal causes propagate through the owned coroutine handler`() = runBlocking {
        val fatal = object : VirtualMachineError("controlled") {}
        val host = AssertionError("host")
        val missing = NoSuchMethodError("host storage API")
        for ((thrown, expected) in listOf(
            IOException("wrapped", fatal) to fatal,
            IOException("wrapped", host) to host,
            missing to missing,
        )) {
            val captured = CompletableDeferred<Throwable>()
            val scope = CoroutineScope(
                SupervisorJob() + Dispatchers.Unconfined +
                    CoroutineExceptionHandler { _, error -> captured.complete(error) },
            )
            val materializer = SourceResultMaterializer(scope) { _, _ -> throw thrown }
            try {
                materializer.sync(1, mapOf(9L to content(9L, manga())))
                assertFalse(materializer.results.containsKey(9L), "host Error must not become a source result")
                assertTrue(captured.isCompleted)
                assertSame(expected, captured.await())
            } finally {
                materializer.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun `ordinary storage IO remains unknown and retryable instead of extension incompatible`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ordinary = IOException("storage")
        val materializer = SourceResultMaterializer(scope) { _, _ -> throw ordinary }
        try {
            val state = DesktopGlobalSearchState(1, queryStates = mapOf(9L to content(9L, manga())))
            materializer.sync(state)
            val ui = GlobalSearchStateProjector.projectPrepared(
                listOf(SourceMetadataSnapshot(mockk(), 9L, "Source", "en", null)),
                state,
                materializer.results,
            )
            assertInstanceOf(AppError.Unknown::class.java, ui.results.single().error?.error)
            assertSame(ordinary, ui.results.single().error?.error?.cause)
            assertTrue(ui.results.single().retryMaterialization)
        } finally {
            materializer.close()
            scope.cancel()
        }
    }

    private fun manga() = SManga.create().apply {
        url = "/work"
        title = "Work"
    }
    private fun content(id: Long, manga: SManga) = SourceQueryState.Content(
        SourcePageRequest(id, 1, 1, SourceQuery.Search("query", FilterList())),
        listOf(manga),
        false,
    )
}
