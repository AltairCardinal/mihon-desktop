package tachiyomi.data.chapter

import mihon.domain.reader.ChapterPairingRecord
import mihon.domain.reader.ChapterPairingRepository
import mihon.domain.reader.ChapterPairingSnapshot
import mihon.domain.reader.MissingChapterPairingIdentityException
import mihon.domain.reader.StaleChapterPairingException
import tachiyomi.data.DatabaseHandler

class ChapterPairingRepositoryImpl(private val handler: DatabaseHandler) : ChapterPairingRepository {
    override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot =
        handler.await(inTransaction = true) {
            if (!chapter_pairingsQueries.chapterBelongsToManga(chapterId, mangaId).executeAsOne()) {
                throw MissingChapterPairingIdentityException()
            }
            val revision = chapter_pairingsQueries.selectRevision(chapterId).executeAsOneOrNull() ?: 0L
            val record = chapter_pairingsQueries.selectPairing(chapterId).executeAsOneOrNull()?.let { row ->
                val rawBoundaries = chapter_pairingsQueries.selectBoundaries(chapterId).executeAsList()
                val corrupt = row.page_count !in 1L..Int.MAX_VALUE.toLong() ||
                    rawBoundaries.any { it !in 1L..Int.MAX_VALUE.toLong() } ||
                    row.revision != revision
                ChapterPairingRecord(
                    formatVersion = row.format_version,
                    pageCount = row.page_count.toInt(),
                    corrupt = corrupt,
                    forcedSinglePages = rawBoundaries.map(Long::toInt).toSet(),
                )
            }
            ChapterPairingSnapshot(record, revision)
        }

    override suspend fun replace(
        chapterId: Long,
        mangaId: Long,
        expectedRevision: Long,
        pageCount: Int,
        forcedSinglePages: Set<Int>,
    ): ChapterPairingSnapshot {
        require(chapterId > 0 && mangaId > 0 && pageCount > 0)
        require(forcedSinglePages.all { it in 1 until pageCount })
        return handler.await(inTransaction = true) {
            if (!chapter_pairingsQueries.chapterBelongsToManga(chapterId, mangaId).executeAsOne()) {
                throw MissingChapterPairingIdentityException()
            }
            val actualRevision = chapter_pairingsQueries.selectRevision(chapterId).executeAsOneOrNull() ?: 0L
            if (actualRevision != expectedRevision) throw StaleChapterPairingException()
            check(actualRevision < Long.MAX_VALUE) { "Chapter pairing revision exhausted" }
            val revision = actualRevision + 1L
            chapter_pairingsQueries.deleteBoundaries(chapterId)
            chapter_pairingsQueries.deletePairing(chapterId)
            if (actualRevision == 0L) {
                chapter_pairingsQueries.insertRevision(chapterId, revision)
            } else {
                chapter_pairingsQueries.updateRevision(revision, chapterId)
            }
            if (forcedSinglePages.isEmpty()) return@await ChapterPairingSnapshot(null, revision)
            chapter_pairingsQueries.insertPairing(
                chapterId,
                ChapterPairingRecord.FORMAT_VERSION,
                pageCount.toLong(),
                revision,
            )
            forcedSinglePages.sorted().forEach { ordinal ->
                chapter_pairingsQueries.insertBoundary(chapterId, ordinal.toLong())
            }
            ChapterPairingSnapshot(
                ChapterPairingRecord(ChapterPairingRecord.FORMAT_VERSION, pageCount, forcedSinglePages),
                revision,
            )
        }
    }
}
