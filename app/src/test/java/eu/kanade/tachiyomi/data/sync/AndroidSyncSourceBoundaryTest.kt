package eu.kanade.tachiyomi.data.sync

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.inspectSourceFailure
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import mihon.domain.error.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.sourceFailureIdentity
import tachiyomi.domain.source.service.toSourceAppError
import java.io.IOException

class AndroidSyncSourceBoundaryTest {
    @Test
    fun `recovery preserves requested URL with registered identity and no ID getter`() = runBlocking {
        val source = mockk<Source>()
        every { source.id } throws NoSuchMethodError("ID must not be read")
        val manager = manager(source, 9L)
        val details = SManga.create().apply {
            url = "/remote"
            title = "Recovered"
        }
        coEvery { source.getMangaDetails(any()) } returns details
        var originalInput: String? = null
        val result = fetchSyncRecoveryManga(9L, "/requested", manager, { value ->
            originalInput = details.url
            value.copy(id = 7L)
        })
        assertEquals(9L, result?.source)
        assertEquals("/requested", result?.url)
        assertEquals("/requested", originalInput)
    }

    @Test
    fun `unknown registration never reads an ID or dispatches recovery API`() = runBlocking {
        val source = mockk<Source>()
        every { source.id } throws NoSuchMethodError("unknown ID must not be probed")
        var calls = 0
        coEvery { source.getMangaDetails(any()) } coAnswers {
            calls++
            SManga.create()
        }
        val result =
            fetchSyncRecoveryManga(9L, "/requested", manager(source, null), { error("unexpected persistence") })
        assertNull(result)
        assertEquals(0, calls)
    }

    @Test
    fun `returned fields preserve details incompatibility and frozen owner without persistence`() = runBlocking {
        val observations = mutableListOf<Triple<String, Throwable?, Int>>()
        for (site in listOf("title", "setter", "genres", "memo")) {
            val missing = IOException("wrapped source", NoSuchMethodError("future $site"))
            val base = SManga.create().apply {
                url = "/remote"
                title = "Recovered"
            }
            val details = object : SManga by base {
                override var title: String
                    get() {
                        if (site == "title") throw missing
                        return base.title
                    }
                    set(value) {
                        base.title = value
                    }
                override var url: String
                    get() = base.url
                    set(value) {
                        if (site == "setter") throw missing
                        base.url = value
                    }
                override fun getGenres(): List<String>? {
                    if (site == "genres") throw missing
                    return base.getGenres()
                }
                override var memo: JsonObject
                    get() {
                        if (site == "memo") throw missing
                        return base.memo
                    }
                    set(value) {
                        base.memo = value
                    }
            }
            val source = mockk<Source> { every { id } returns 9L }
            var owners = listOf(installed(source, "1.6.2"))
            coEvery { source.getMangaDetails(any()) } coAnswers {
                owners = listOf(installed(source, "9.9.9"))
                details
            }
            var writes = 0
            val actual = runCatching {
                fetchSyncRecoveryManga(
                    9L,
                    "/requested",
                    manager(source, 9L),
                    {
                        writes++
                        it
                    },
                    { owners },
                )
            }.exceptionOrNull()
            observations += Triple(site, actual, writes)
        }
        for ((site, actual, writes) in observations) {
            assertTrue("$site:$actual", actual is SourceCompatibilityException)
            val marker = actual as SourceCompatibilityException
            assertEquals("future $site", marker.inspectSourceFailure().linkage?.message)
            assertEquals(SourceOperationPhase.DETAILS, marker.phase)
            val mapped = marker.toSourceAppError()
            assertTrue(mapped is AppError.ExtensionIncompatible)
            assertEquals("sync.fixture", mapped.sourceFailureIdentity()?.extensionPackage)
            assertEquals("1.6.2", mapped.sourceFailureIdentity()?.versionName)
            assertEquals(0, writes)
        }
    }

    @Test
    fun `API and persistence wrapped controls propagate while ordinary host IO remains unchanged`() = runBlocking {
        val fatal = object : VirtualMachineError("controlled") {}
        val host = AssertionError("host")
        val cancellation = kotlinx.coroutines.CancellationException("cancelled")
        for (atApi in listOf(true, false)) {
            for ((thrown, expected) in listOf(
                IOException("wrapped", fatal) to fatal,
                IOException("wrapped", host) to host,
                IOException("wrapped", cancellation) to cancellation,
            )) {
                val source = mockk<Source> { every { id } returns 9L }
                coEvery { source.getMangaDetails(any()) } coAnswers {
                    if (atApi) throw thrown
                    SManga.create().apply {
                        title = "Recovered"
                        url = "/remote"
                    }
                }
                val actual = runCatching {
                    fetchSyncRecoveryManga(
                        9L,
                        "/requested",
                        manager(source, 9L),
                        { throw thrown },
                    )
                }.exceptionOrNull()
                if (expected === cancellation) {
                    assertTrue(actual is kotlinx.coroutines.CancellationException)
                    assertEquals(cancellation.message, actual?.message)
                } else {
                    assertSame(expected, actual)
                }
            }
        }
        val source = mockk<Source> { every { id } returns 9L }
        coEvery { source.getMangaDetails(any()) } returns SManga.create().apply {
            title = "Recovered"
            url = "/remote"
        }
        val io = IOException("host IO")
        val actual = runCatching {
            fetchSyncRecoveryManga(9L, "/requested", manager(source, 9L), { throw io })
        }.exceptionOrNull()
        assertSame(io, actual)
    }

    private fun manager(source: Source, id: Long?) = mockk<SourceManager> {
        every { get(9L) } returns source
        every { getRegisteredSourceId(source) } returns id
    }
    private fun installed(source: Source, version: String) = Extension.Installed(
        "Sync extension", "sync.fixture", version, 162L, 1.6, "en", false,
        null, listOf(source), null, isShared = false,
    )
}
