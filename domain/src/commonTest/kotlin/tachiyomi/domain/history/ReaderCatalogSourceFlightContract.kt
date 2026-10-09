package tachiyomi.domain.history

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceMangaUpdateService
import java.util.concurrent.atomic.AtomicInteger

abstract class ReaderCatalogSourceFlightContract {
    @Test
    fun `reader first catalog is shared and later detail fetch preserves its opaque chapter memo`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val flags = java.util.Collections.synchronizedList(mutableListOf<Pair<Boolean, Boolean>>())
        val source = mockk<Source>()
        coEvery { source.getMangaUpdate(any(), any(), any(), any()) } coAnswers {
            val request = thirdArg<Boolean>() to arg<Boolean>(3)
            flags.add(request)
            if (request.second) {
                entered.complete(Unit)
                release.await()
                update()
            } else {
                assertEquals("opaque", secondArg<List<SChapter>>().single().memo["source"].toString().trim('"'))
                SMangaUpdate(firstArg<SManga>().apply { title = "Detail title" }, emptyList())
            }
        }
        val service = SourceMangaUpdateService()
        val manga = Manga.create().copy(source = 88773, url = "/reader-first", title = "Reader first")
        val reader =
            async(start = CoroutineStart.UNDISPATCHED) { service.awaitSharedCatalog(source, manga, emptyList(), false) }
        withTimeout(5_000) { entered.await() }
        val detail =
            async(start = CoroutineStart.UNDISPATCHED) { service.awaitSharedCatalog(source, manga, emptyList(), true) }
        try {
            assertEquals(listOf(false to true), flags.toList())
            release.complete(Unit)
            assertEquals(1, reader.await().chapters.size)
            val result = detail.await()
            assertEquals("Detail title", result.manga.title)
            assertEquals("opaque", result.chapters.single().memo["source"].toString().trim('"'))
            assertEquals(listOf(false to true, true to false), flags.toList())
        } finally {
            release.complete(Unit)
            reader.cancelAndJoin()
            detail.cancelAndJoin()
        }
    }

    @Test
    fun `reader cancellation preserves detail waiter and fetches one combined catalog`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val source = mockk<Source>()
        coEvery { source.getMangaUpdate(any(), any(), any(), any()) } coAnswers {
            if (calls.incrementAndGet() == 1) {
                assertTrue(thirdArg<Boolean>())
                assertTrue(arg<Boolean>(3))
            }
            entered.complete(Unit)
            try {
                release.await()
                update()
            } finally {
                stopped.complete(Unit)
            }
        }
        val service = SourceMangaUpdateService()
        val manga = Manga.create().copy(source = 88771, url = "/one-flight", title = "One flight")
        val detail =
            async(start = CoroutineStart.UNDISPATCHED) { service.awaitSharedCatalog(source, manga, emptyList(), true) }
        withTimeout(5_000) { entered.await() }
        val reader =
            async(start = CoroutineStart.UNDISPATCHED) { service.awaitSharedCatalog(source, manga, emptyList(), false) }
        try {
            assertEquals(1, calls.get())
            reader.cancelAndJoin()
            assertFalse(stopped.isCompleted)
            release.complete(Unit)
            assertEquals("opaque", detail.await().chapters.single().memo["source"].toString().trim('"'))
        } finally {
            release.complete(Unit)
            reader.cancelAndJoin()
            detail.cancelAndJoin()
        }
    }

    @Test
    fun `last catalog waiter cancellation stops source and releases the flight`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val source = mockk<Source>()
        coEvery { source.getMangaUpdate(any(), any(), any(), any()) } coAnswers {
            if (calls.incrementAndGet() == 1) {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            } else {
                update()
            }
        }
        val service = SourceMangaUpdateService()
        val manga = Manga.create().copy(source = 88772, url = "/cancel-flight", title = "Cancel flight")
        val reader =
            async(start = CoroutineStart.UNDISPATCHED) { service.awaitSharedCatalog(source, manga, emptyList(), false) }
        withTimeout(5_000) { entered.await() }
        reader.cancelAndJoin()
        withTimeout(5_000) { stopped.await() }
        assertEquals(1, service.awaitSharedCatalog(source, manga, emptyList(), false).chapters.size)
        assertEquals(2, calls.get())
    }

    private fun update() = SMangaUpdate(
        SManga.create().apply {
            url = "/one-flight"
            title = "Fetched"
        },
        listOf(
            SChapter.create().apply {
                url = "/1"
                name = "Chapter 1"
                memo =
                    kotlinx.serialization.json.buildJsonObject {
                        put("source", kotlinx.serialization.json.JsonPrimitive("opaque"))
                    }
            },
        ),
    )
}
