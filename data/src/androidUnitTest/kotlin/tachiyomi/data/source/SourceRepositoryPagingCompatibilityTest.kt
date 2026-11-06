package tachiyomi.data.source

import androidx.paging.PagingSource
import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.IOException

class SourceRepositoryPagingCompatibilityTest {
    @Test
    fun `actual repository paging isolates hooks and returned fields using the requested host ID`() = runBlocking {
        for (phase in listOf(SourceOperationPhase.SEARCH, SourceOperationPhase.POPULAR, SourceOperationPhase.LATEST)) {
            Fixture(poisonId = true).use { fixture ->
                val original = NoSuchMethodError("future $phase")
                fixture.apiFailure = original
                val paging = fixture.paging(phase)
                val failed = runCatching { paging.load(refresh()) }
                assertNull(failed.exceptionOrNull())
                val cause = assertInstanceOf(PagingSource.LoadResult.Error::class.java, failed.getOrThrow()).throwable
                val marker = assertInstanceOf(SourceCompatibilityException::class.java, cause)
                assertSame(original, marker.cause)
                assertEquals(phase, marker.phase)
                assertEquals(0, fixture.saved.size)
                fixture.apiFailure = null
                fixture.fieldFailure = original
                val fieldError = assertInstanceOf(
                    PagingSource.LoadResult.Error::class.java,
                    paging.load(refresh()),
                ).throwable
                assertEquals(phase, assertInstanceOf(SourceCompatibilityException::class.java, fieldError).phase)
                assertEquals(0, fixture.saved.size)
                fixture.fieldFailure = null
                val healthy = assertInstanceOf(PagingSource.LoadResult.Page::class.java, paging.load(refresh()))
                assertEquals(1, healthy.data.size)
                assertEquals(9L, fixture.saved.single().source)
                assertEquals(0, fixture.idReads)
            }
        }
    }

    @Test
    fun `paging propagates cancellation and wrapped host Errors while retaining ordinary storage IO`() = runBlocking {
        val fatal = object : VirtualMachineError("controlled") {}
        val host = AssertionError("host storage")
        val cancellation = kotlinx.coroutines.CancellationException("cancelled storage")
        for ((thrown, expected) in listOf(
            IOException("wrapped", fatal) to fatal,
            IOException("wrapped", host) to host,
            IOException("wrapped", cancellation) to cancellation,
        )) {
            Fixture(poisonId = false).use { fixture ->
                fixture.storageFailure = thrown
                val actual = runCatching {
                    fixture.paging(SourceOperationPhase.SEARCH).load(refresh())
                }.exceptionOrNull()
                if (expected === cancellation) {
                    assertInstanceOf(kotlinx.coroutines.CancellationException::class.java, actual)
                    assertEquals(cancellation.message, actual?.message)
                } else {
                    assertSame(expected, actual)
                }
            }
        }
        Fixture(poisonId = false).use { fixture ->
            val ordinary = IOException("storage IO")
            fixture.storageFailure = ordinary
            val result = fixture.paging(SourceOperationPhase.SEARCH).load(refresh())
            assertSame(ordinary, assertInstanceOf(PagingSource.LoadResult.Error::class.java, result).throwable)
        }
    }

    private fun refresh() = PagingSource.LoadParams.Refresh<Long>(null, 25, false)

    private class Fixture(poisonId: Boolean) : AutoCloseable {
        private val previous = Injekt
        var apiFailure: Throwable? = null
        var fieldFailure: Throwable? = null
        var storageFailure: Throwable? = null
        var idReads = 0
        val saved = mutableListOf<Manga>()
        private val base = SManga.create().apply {
            url = "/work"
            title = "Work"
        }
        private val returned = object : SManga by base {
            override fun getGenres(): List<String>? {
                fieldFailure?.let { throw it }
                return base.getGenres()
            }
        }
        private val source = mockk<Source> {
            every { id } answers {
                idReads++
                if (poisonId) throw NoSuchMethodError("host ID must not be reread")
                9L
            }
            coEvery { getSearchManga(any(), any(), any()) } coAnswers { page() }
            coEvery { getPopularManga(any()) } coAnswers { page() }
            coEvery { getLatestUpdates(any()) } coAnswers { page() }
        }
        private val manager = mockk<SourceManager> { every { get(9L) } returns source }
        private val repository: SourceRepositoryImpl
        init {
            Injekt = InjektScope(DefaultRegistrar())
            val network = mockk<NetworkToLocalManga>()
            coEvery { network.invoke(any<List<Manga>>()) } coAnswers {
                storageFailure?.let { throw it }
                firstArg<List<Manga>>().also { saved += it }
            }
            Injekt.addSingleton(network)
            repository = SourceRepositoryImpl(manager, mockk<DatabaseHandler>())
        }
        private fun page(): MangasPage {
            apiFailure?.let { throw it }
            return MangasPage(listOf(returned), false)
        }
        fun paging(phase: SourceOperationPhase) = when (phase) {
            SourceOperationPhase.SEARCH -> repository.search(9L, "query", FilterList())
            SourceOperationPhase.POPULAR -> repository.getPopular(9L)
            SourceOperationPhase.LATEST -> repository.getLatest(9L)
            else -> error("invalid test phase")
        }
        override fun close() {
            Injekt = previous
        }
    }
}
