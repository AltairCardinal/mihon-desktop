package tachiyomi.data.chapter

import mihon.domain.reader.ChapterPairingRecord
import mihon.domain.reader.ChapterPairingSnapshot
import mihon.domain.reader.MissingChapterPairingIdentityException
import mihon.domain.reader.ReaderChapterOpeningMode
import mihon.domain.sync.SyncMetadataPolicy
import tachiyomi.data.Database

internal fun Database.loadChapterPairing(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
    if (!chapter_pairingsQueries.chapterBelongsToManga(chapterId, mangaId).executeAsOne()) {
        throw MissingChapterPairingIdentityException()
    }
    val revision = chapter_pairingsQueries.selectRevision(chapterId).executeAsOneOrNull() ?: 0L
    val record = chapter_pairingsQueries.selectPairing(chapterId).executeAsOneOrNull()?.let { row ->
        val rawBoundaries = chapter_pairingsQueries.selectBoundaries(chapterId).executeAsList()
        val opening = ReaderChapterOpeningMode.entries.find { it.name == row.opening_mode }
        val rawPairs = chapter_pairingsQueries.selectPairs(chapterId).executeAsList()
        val corrupt =
            opening == null || rawPairs.any { it !in 1L..Int.MAX_VALUE.toLong() } ||
                row.page_count !in 1L..Int.MAX_VALUE.toLong() ||
                rawBoundaries.any { it !in 1L..Int.MAX_VALUE.toLong() } ||
                row.revision != revision
        ChapterPairingRecord(
            formatVersion = row.format_version,
            pageCount = row.page_count.toInt(),
            corrupt = corrupt,
            forcedSinglePages = rawBoundaries.map(Long::toInt).toSet(),
            openingMode = opening ?: ReaderChapterOpeningMode.AUTO,
            manualPairStarts = rawPairs.map(Long::toInt).toSet(),
        )
    }
    return ChapterPairingSnapshot(record, revision)
}

/** The caller owns the business transaction and provenance; revisions never leave this device. */
internal fun Database.writeChapterPairing(chapterId: Long, record: ChapterPairingRecord): ChapterPairingSnapshot {
    require(SyncMetadataPolicy.isClear(record) || record.isValidFor(record.pageCount))
    val actualRevision = chapter_pairingsQueries.selectRevision(chapterId).executeAsOneOrNull() ?: 0L
    check(actualRevision < Long.MAX_VALUE) { "Chapter pairing revision exhausted" }
    val revision = actualRevision + 1
    chapter_pairingsQueries.deletePairs(chapterId)
    chapter_pairingsQueries.deleteBoundaries(chapterId)
    chapter_pairingsQueries.deletePairing(chapterId)
    if (actualRevision == 0L) {
        chapter_pairingsQueries.insertRevision(chapterId, revision)
    } else {
        chapter_pairingsQueries.updateRevision(revision, chapterId)
    }
    if (SyncMetadataPolicy.isClear(record)) return ChapterPairingSnapshot(null, revision)
    chapter_pairingsQueries.insertPairing(
        chapterId,
        ChapterPairingRecord.FORMAT_VERSION,
        record.pageCount.toLong(),
        revision,
    )
    chapter_pairingsQueries.setOpeningMode(record.openingMode.name, chapterId)
    record.manualPairStarts.sorted().forEach { chapter_pairingsQueries.insertPair(chapterId, it.toLong()) }
    record.forcedSinglePages.sorted().forEach { chapter_pairingsQueries.insertBoundary(chapterId, it.toLong()) }
    return ChapterPairingSnapshot(record, revision)
}
