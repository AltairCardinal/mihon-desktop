package mihon.domain.reader

/** Device-local manual layout for one stable database chapter. Ordinals are zero based. */
data class ChapterPairingRecord(
    val formatVersion: Long,
    val pageCount: Int,
    val forcedSinglePages: Set<Int>,
    val corrupt: Boolean = false,
) {
    fun isValidFor(currentPageCount: Int): Boolean =
        !corrupt && formatVersion == FORMAT_VERSION &&
            pageCount == currentPageCount &&
            forcedSinglePages.isNotEmpty() &&
            forcedSinglePages.all { it in 1 until currentPageCount }

    companion object {
        const val FORMAT_VERSION = 1L
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
}
