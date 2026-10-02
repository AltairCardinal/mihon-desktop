package tachiyomi.domain.extension

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mihon.domain.manga.model.toDomainManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class SourceUpdateMemoContractTest {
    @Test
    fun `parent cancellation stops combined update`() = kotlinx.coroutines.runBlocking<Unit> {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        var completed = false
        var calls = 0
        val source = object : eu.kanade.tachiyomi.source.Source {
            override val id = 42L
            override val name = "Suspended"
            override suspend fun getMangaUpdate(
                manga: SManga,
                chapters: List<eu.kanade.tachiyomi.source.model.SChapter>,
                fetchDetails: Boolean,
                fetchChapters: Boolean,
            ): eu.kanade.tachiyomi.source.model.SMangaUpdate {
                calls++
                entered.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val job = launch {
            tachiyomi.domain.source.service.SourceMangaUpdateService().await(
                source,
                Manga.create(),
                emptyList(),
                true,
                true,
            )
            completed = true
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(1, calls)
        assertEquals(false, completed)
        assertEquals(true, job.isCancelled)
    }

    @Test
    fun `source error and cancellation propagate without retry`() = kotlinx.coroutines.runBlocking<Unit> {
        for (failure in listOf(
            IllegalStateException("broken response"),
            kotlinx.coroutines.CancellationException("cancelled"),
        )) {
            var calls = 0
            val source = object : eu.kanade.tachiyomi.source.Source {
                override val id = 42L
                override val name = "Failure"
                override suspend fun getMangaUpdate(
                    manga: SManga,
                    chapters: List<eu.kanade.tachiyomi.source.model.SChapter>,
                    fetchDetails: Boolean,
                    fetchChapters: Boolean,
                ): eu.kanade.tachiyomi.source.model.SMangaUpdate {
                    calls++
                    throw failure
                }
            }
            val actual = runCatching {
                tachiyomi.domain.source.service.SourceMangaUpdateService().await(
                    source,
                    Manga.create(),
                    emptyList(),
                    true,
                    true,
                )
            }.exceptionOrNull()
            org.junit.jupiter.api.Assertions.assertSame(failure, actual)
            assertEquals(1, calls)
        }
    }

    @Test
    fun `empty complete remote directory is rejected without changing known chapters`() = runBlocking<Unit> {
        var calls = 0
        val known =
            listOf(
                tachiyomi.domain.chapter.model.Chapter.create().copy(
                    id = 7,
                    read = true,
                    bookmark = true,
                    lastPageRead = 8,
                ),
            )
        val source = object : eu.kanade.tachiyomi.source.Source {
            override val id = 42L
            override val name = "Empty"
            override suspend fun getMangaUpdate(
                manga: SManga,
                chapters: List<eu.kanade.tachiyomi.source.model.SChapter>,
                fetchDetails: Boolean,
                fetchChapters: Boolean,
            ): eu.kanade.tachiyomi.source.model.SMangaUpdate {
                calls++
                return eu.kanade.tachiyomi.source.model.SMangaUpdate(manga, emptyList())
            }
        }
        val failure = runCatching {
            tachiyomi.domain.source.service.SourceMangaUpdateService().await(source, Manga.create(), known, false, true)
        }.exceptionOrNull()
        assertEquals(tachiyomi.domain.chapter.model.NoChaptersException::class, failure!!::class)
        assertEquals(1, calls)
        assertEquals(7L, known.single().id)
        assertEquals(true, known.single().read)
        assertEquals(true, known.single().bookmark)
        assertEquals(8L, known.single().lastPageRead)
    }

    @Test
    fun `flags and ordered memo reach combined update once`() = kotlinx.coroutines.runBlocking {
        for ((details, requestedChapters) in listOf(true to false, false to true, true to true, false to false)) {
            var calls = 0
            val memo = Json.parseToJsonElement("""{"opaque":[null,"中文",123]}""").jsonObject
            val manga = Manga.create().copy(source = 42, url = "/m", title = "Title", memo = memo)
            val known = listOf(2L, 0L, 1L).map { order ->
                tachiyomi.domain.chapter.model.Chapter.create().copy(
                    url = "/$order",
                    name = "Chapter $order",
                    sourceOrder = order,
                    memo = memo,
                )
            }
            val source = object : eu.kanade.tachiyomi.source.Source {
                override val id = 42L
                override val name = "Combined"
                override suspend fun getMangaUpdate(
                    manga: SManga,
                    chapters: List<eu.kanade.tachiyomi.source.model.SChapter>,
                    fetchDetails: Boolean,
                    fetchChapters: Boolean,
                ): eu.kanade.tachiyomi.source.model.SMangaUpdate {
                    calls++
                    assertEquals(details, fetchDetails)
                    assertEquals(requestedChapters, fetchChapters)
                    assertEquals(memo, manga.memo)
                    assertEquals(listOf("/0", "/1", "/2"), chapters.map { it.url })
                    assertEquals(listOf(memo, memo, memo), chapters.map { it.memo })
                    return eu.kanade.tachiyomi.source.model.SMangaUpdate(manga, chapters)
                }
            }
            val result = tachiyomi.domain.source.service.SourceMangaUpdateService().await(
                source,
                manga,
                known,
                details,
                requestedChapters,
            )
            assertEquals(if (details || requestedChapters) 1 else 0, calls)
            assertEquals(memo, result.manga.memo)
            assertEquals(listOf("/0", "/1", "/2"), result.chapters.map { it.url })
        }
    }

    @Test
    fun `source manga mapping preserves opaque structured memo`() {
        val memo = Json.parseToJsonElement(
            """{"token":"中文","nested":{"id":9223372036854775807},"items":[null,true,2.5]}""",
        ).jsonObject
        val sourceManga = SManga.create().apply {
            url = "/manga"
            title = "Source title"
            this.memo = memo
        }
        val manga = sourceManga.toDomainManga(42)
        assertEquals(memo, manga.memo)
        assertEquals(42, manga.source)
    }

    @Test
    fun `navigation serialization retains memo and user state`() {
        val manga = Manga.create().copy(
            id = 72,
            source = 42,
            title = "User title",
            favorite = true,
            notes = "User notes",
            memo = Json.parseToJsonElement("""{"nested":[null,{"token":"中文"}]}""").jsonObject,
        )
        val bytes = ByteArrayOutputStream().also { output ->
            ObjectOutputStream(output).use { it.writeObject(manga) }
        }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
        assertEquals(manga, restored)
    }
}
