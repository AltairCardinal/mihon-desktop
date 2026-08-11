package tachiyomi.domain.creator.service

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga
import tachiyomi.domain.creator.model.CreatorLibraryIndexEntry
import tachiyomi.domain.creator.repository.CreatorLibraryIndexWriter
import tachiyomi.domain.creator.repository.CreatorLibraryMangaSource
import tachiyomi.domain.manga.model.Manga

class CreatorLibraryIndexerTest {

    @Test
    fun `initial library emission backfills ten thousand manga with visible progress`() = runTest {
        val source = FakeLibrarySource((1L..10_000L).map { manga(it, author = "Author $it") })
        val writer = RecordingWriter()
        val indexer = CreatorLibraryIndexer(
            mangaSource = source,
            indexWriter = writer,
            extractCreators = ExtractCreatorsFromManga(),
            batchSize = 250,
        )

        indexer.start(backgroundScope)
        runCurrent()

        writer.indexedIds.size shouldBe 10_000
        writer.batchSizes.max() shouldBe 250
        writer.cleanupCalls shouldBe 1
        indexer.state.value shouldBe CreatorLibraryIndexState.Ready(indexedManga = 10_000)
    }

    @Test
    fun `empty library performs stale cleanup and exposes a real empty state`() = runTest {
        val source = FakeLibrarySource(emptyList())
        val writer = RecordingWriter()
        val indexer = CreatorLibraryIndexer(
            mangaSource = source,
            indexWriter = writer,
            extractCreators = ExtractCreatorsFromManga(),
        )
        indexer.start(backgroundScope)
        runCurrent()

        writer.indexedIds shouldBe emptyList()
        writer.cleanupCalls shouldBe 1
        indexer.state.value shouldBe CreatorLibraryIndexState.Empty
    }

    @Test
    fun `failure is exposed and retry restarts the production flow`() = runTest {
        val source = FakeLibrarySource(listOf(manga(7L, author = "ONE")))
        val writer = RecordingWriter(failuresRemaining = 1)
        val indexer = CreatorLibraryIndexer(
            mangaSource = source,
            indexWriter = writer,
            extractCreators = ExtractCreatorsFromManga(),
        )
        indexer.start(backgroundScope)
        runCurrent()

        indexer.state.value shouldBe CreatorLibraryIndexState.Failed(
            processedManga = 0,
            totalManga = 1,
            message = "database unavailable",
        )

        indexer.retry()
        runCurrent()

        writer.attempts shouldBe 2
        indexer.state.value shouldBe CreatorLibraryIndexState.Ready(indexedManga = 1)
    }

    @Test
    fun `start is idempotent and does not install duplicate collectors`() = runTest {
        val source = FakeLibrarySource(listOf(manga(1L, author = "ONE")))
        val writer = RecordingWriter()
        val indexer = CreatorLibraryIndexer(
            mangaSource = source,
            indexWriter = writer,
            extractCreators = ExtractCreatorsFromManga(),
        )

        indexer.start(backgroundScope)
        indexer.start(backgroundScope)
        runCurrent()

        writer.indexedIds.shouldContainExactly(1L)
    }

    private class FakeLibrarySource(
        private val mangas: List<Manga>,
    ) : CreatorLibraryMangaSource {
        override suspend fun countLibraryMangaForCreatorIndex(): Long = mangas.size.toLong()

        override suspend fun getLibraryMangaForCreatorIndex(afterId: Long, limit: Long): List<Manga> =
            mangas.filter { it.id > afterId }.take(limit.toInt())
    }

    private class RecordingWriter(
        var failuresRemaining: Int = 0,
    ) : CreatorLibraryIndexWriter {
        val indexedIds = mutableListOf<Long>()
        val batchSizes = mutableListOf<Int>()
        var cleanupCalls = 0
        var attempts = 0

        override suspend fun indexLibraryMangaBatch(entries: List<CreatorLibraryIndexEntry>) {
            attempts += 1
            if (failuresRemaining > 0) {
                failuresRemaining -= 1
                error("database unavailable")
            }
            batchSizes += entries.size
            indexedIds += entries.map { it.manga.id }
        }

        override suspend fun removeLibraryMangaIndex(mangaId: Long) = Unit

        override suspend fun removeStaleLibraryMangaIndexes() {
            cleanupCalls += 1
        }
    }

    private fun manga(id: Long, author: String?) = Manga.create().copy(
        id = id,
        source = 1L,
        url = "/manga/$id",
        title = "Manga $id",
        author = author,
        favorite = true,
        lastModifiedAt = 1L,
    )
}
