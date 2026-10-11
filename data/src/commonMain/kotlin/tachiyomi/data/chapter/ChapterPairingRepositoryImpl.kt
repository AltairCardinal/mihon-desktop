package tachiyomi.data.chapter

import mihon.data.sync.journal.appendPairingOperation
import mihon.domain.reader.ChapterPairingRecord
import mihon.domain.reader.ChapterPairingRepository
import mihon.domain.reader.ChapterPairingSnapshot
import mihon.domain.reader.MissingChapterPairingIdentityException
import mihon.domain.reader.ReaderChapterOpeningMode
import mihon.domain.reader.ReaderPairingAdjustment
import mihon.domain.reader.StaleChapterPairingException
import tachiyomi.data.DatabaseHandler

class ChapterPairingRepositoryImpl(private val handler: DatabaseHandler) : ChapterPairingRepository {
    override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot =
        handler.await(inTransaction = true) { loadChapterPairing(chapterId, mangaId) }

    override suspend fun replace(
        chapterId: Long,
        mangaId: Long,
        expectedRevision: Long,
        pageCount: Int,
        forcedSinglePages: Set<Int>,
    ): ChapterPairingSnapshot = replaceLayout(
        chapterId,
        mangaId,
        expectedRevision,
        pageCount,
        ReaderPairingAdjustment(forcedSinglePages, 0),
    )

    override suspend fun replaceLayout(
        chapterId: Long,
        mangaId: Long,
        expectedRevision: Long,
        pageCount: Int,
        adjustment: ReaderPairingAdjustment,
    ): ChapterPairingSnapshot {
        val forcedSinglePages = adjustment.forcedSinglePages
        val record =
            ChapterPairingRecord(
                ChapterPairingRecord.FORMAT_VERSION,
                pageCount,
                forcedSinglePages,
                openingMode = adjustment.openingMode,
                manualPairStarts = adjustment.manualPairStarts,
            )
        val empty =
            forcedSinglePages.isEmpty() && adjustment.manualPairStarts.isEmpty() &&
                adjustment.openingMode == ReaderChapterOpeningMode.AUTO
        require(empty || record.isValidFor(pageCount))
        require(chapterId > 0 && mangaId > 0 && pageCount > 0)
        require(forcedSinglePages.all { it in 1 until pageCount })
        return handler.await(inTransaction = true) {
            if (!chapter_pairingsQueries.chapterBelongsToManga(chapterId, mangaId).executeAsOne()) {
                throw MissingChapterPairingIdentityException()
            }
            val actualRevision = chapter_pairingsQueries.selectRevision(chapterId).executeAsOneOrNull() ?: 0L
            if (actualRevision != expectedRevision) throw StaleChapterPairingException()
            val snapshot = writeChapterPairing(chapterId, record)
            appendPairingOperation(chapterId, record)
            snapshot
        }
    }
}
