package mihon.domain.reader

enum class ReaderChapterOpeningMode { AUTO, FIRST_SINGLE, FIRST_PAIR }

/** Locally persisted manual layout; synchronization carries its value with portable chapter identity, not revision. */
data class ChapterPairingRecord(
    val formatVersion: Long,
    val pageCount: Int,
    val forcedSinglePages: Set<Int>,
    val corrupt: Boolean = false,
    val openingMode: ReaderChapterOpeningMode = ReaderChapterOpeningMode.AUTO,
    val manualPairStarts: Set<Int> = emptySet(),
) {
    fun isValidFor(currentPageCount: Int): Boolean =
        currentPageCount > 0 && !corrupt && formatVersion in 1L..FORMAT_VERSION && pageCount == currentPageCount &&
            (formatVersion != 1L || (openingMode == ReaderChapterOpeningMode.AUTO && manualPairStarts.isEmpty())) &&
            hasManualChoice() &&
            forcedSinglePages.all { it in 1 until currentPageCount } &&
            openingCompatible() &&
            manualPairStarts.all {
                it in 1 until currentPageCount - 1 && it !in forcedSinglePages &&
                    it + 1 !in forcedSinglePages && it + 1 !in manualPairStarts
            }

    private fun hasManualChoice() = forcedSinglePages.isNotEmpty() || manualPairStarts.isNotEmpty() ||
        openingMode != ReaderChapterOpeningMode.AUTO

    private fun openingCompatible() = openingMode != ReaderChapterOpeningMode.FIRST_PAIR ||
        (1 !in forcedSinglePages && 1 !in manualPairStarts)

    companion object {
        const val FORMAT_VERSION = 2L
    }
}

/** Revision metadata survives clearing the user-visible record, so stale empty reads are detectable. */
data class ChapterPairingSnapshot(val record: ChapterPairingRecord?, val revision: Long)

/** A stale revision must be reloaded before retrying; it must never overwrite another session. */
class StaleChapterPairingException : IllegalStateException("Chapter pairing changed in another session")

class MissingChapterPairingIdentityException : IllegalStateException("Chapter is not in this manga's database")

/** All calls include manga identity so a stale reader cannot attach a record to a reused chapter ID. */
interface ChapterPairingRepository {
    suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot

    suspend fun replace(
        chapterId: Long,
        mangaId: Long,
        expectedRevision: Long,
        pageCount: Int,
        forcedSinglePages: Set<Int>,
    ): ChapterPairingSnapshot

    suspend fun replaceLayout(
        chapterId: Long,
        mangaId: Long,
        expectedRevision: Long,
        pageCount: Int,
        adjustment: ReaderPairingAdjustment,
    ): ChapterPairingSnapshot {
        require(adjustment.openingMode == ReaderChapterOpeningMode.AUTO && adjustment.manualPairStarts.isEmpty())
        return replace(chapterId, mangaId, expectedRevision, pageCount, adjustment.forcedSinglePages)
    }
}
