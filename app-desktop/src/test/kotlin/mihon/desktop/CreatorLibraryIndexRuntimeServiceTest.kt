package mihon.desktop

import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga
import tachiyomi.domain.creator.model.CreatorLibraryIndexEntry
import tachiyomi.domain.creator.repository.CreatorLibraryIndexWriter
import tachiyomi.domain.creator.repository.CreatorLibraryMangaSource
import tachiyomi.domain.creator.service.CreatorLibraryIndexer
import tachiyomi.domain.manga.model.Manga

class CreatorLibraryIndexRuntimeServiceTest {

    @Test
    fun `desktop runtime service starts production indexer once and stops with runtime`() = runTest {
        val writer = RecordingWriter()
        val indexer = CreatorLibraryIndexer(
            mangaSource = object : CreatorLibraryMangaSource {
                override suspend fun countLibraryMangaForCreatorIndex(): Long = 1L

                override suspend fun getLibraryMangaForCreatorIndex(afterId: Long, limit: Long): List<Manga> =
                    if (afterId < 1L) {
                        listOf(Manga.create().copy(id = 1L, source = 1L, url = "/one", title = "One", favorite = true))
                    } else {
                        emptyList()
                    }
            },
            indexWriter = writer,
            extractCreators = ExtractCreatorsFromManga(),
        )
        val service = CreatorLibraryIndexRuntimeService(indexer, backgroundScope)
        val noop = RecordingService()
        val runtime = DesktopAppRuntime(
            libraryUpdateScheduler = noop,
            localSourceScanService = noop,
            autoBackupScheduler = noop,
            startupCleanup = {},
            creatorLibraryIndexService = service,
            scope = backgroundScope,
        )

        runtime.start()
        runtime.start()
        runCurrent()

        writer.indexedIds.shouldContainExactly(1L)
        runtime.stop()
    }

    private class RecordingWriter : CreatorLibraryIndexWriter {
        val indexedIds = mutableListOf<Long>()

        override suspend fun indexLibraryMangaBatch(entries: List<CreatorLibraryIndexEntry>) {
            indexedIds += entries.map { it.manga.id }
        }

        override suspend fun removeLibraryMangaIndex(mangaId: Long) = Unit

        override suspend fun removeStaleLibraryMangaIndexes() = Unit
    }

    private class RecordingService : DesktopRuntimeService {
        override fun start() = Unit

        override fun stop() = Unit
    }
}
