package mihon.domain.reader.partial

import kotlinx.serialization.Serializable
import mihon.domain.download.DownloadQueueStatus
import mihon.domain.reader.content.DownloadChapterIdentity

const val PARTIAL_PAGE_TABLE_SCHEMA_VERSION = 1
private const val LEGACY_PAGE_TABLE_SCHEMA_VERSION = 0

@Serializable
enum class PartialPageTableCompleteness {
    COMPLETE,
    PARTIAL,
    LEGACY_UNPROVEN,
}

@Serializable
data class PartialPageTableEntry(
    val readerOrdinal: Int,
    val sourcePageIndex: Int,
    val pageUrl: String,
    val imageUrl: String?,
)

@Serializable
data class PartialPageTable(
    val schemaVersion: Int,
    val completeness: PartialPageTableCompleteness,
    val totalPageCount: Int,
    val entries: List<PartialPageTableEntry>,
) {
    companion object {
        fun complete(entries: List<PartialPageTableEntry>) = PartialPageTable(
            schemaVersion = PARTIAL_PAGE_TABLE_SCHEMA_VERSION,
            completeness = PartialPageTableCompleteness.COMPLETE,
            totalPageCount = entries.size,
            entries = entries.toList(),
        )

        fun partial(totalPageCount: Int, entries: List<PartialPageTableEntry>) = PartialPageTable(
            schemaVersion = PARTIAL_PAGE_TABLE_SCHEMA_VERSION,
            completeness = PartialPageTableCompleteness.PARTIAL,
            totalPageCount = totalPageCount,
            entries = entries.toList(),
        )

        fun legacy(pageUrls: List<String>) = PartialPageTable(
            schemaVersion = LEGACY_PAGE_TABLE_SCHEMA_VERSION,
            completeness = PartialPageTableCompleteness.LEGACY_UNPROVEN,
            totalPageCount = pageUrls.size,
            entries = pageUrls.mapIndexed { ordinal, url ->
                PartialPageTableEntry(
                    readerOrdinal = ordinal,
                    sourcePageIndex = ordinal,
                    pageUrl = url,
                    imageUrl = url,
                )
            },
        )
    }
}

enum class PartialPageTableValidation {
    COMPLETE,
    PARTIAL,
    LEGACY_UNPROVEN,
    UNSUPPORTED_VERSION,
    INVALID,
}

object PartialPageTablePolicy {
    fun validate(table: PartialPageTable): PartialPageTableValidation {
        if (
            table.schemaVersion != PARTIAL_PAGE_TABLE_SCHEMA_VERSION &&
            table.schemaVersion != LEGACY_PAGE_TABLE_SCHEMA_VERSION
        ) {
            return PartialPageTableValidation.UNSUPPORTED_VERSION
        }
        if (table.totalPageCount < 0) return PartialPageTableValidation.INVALID
        if (table.entries.any { it.readerOrdinal !in 0 until table.totalPageCount }) {
            return PartialPageTableValidation.INVALID
        }
        if (table.entries.map(PartialPageTableEntry::readerOrdinal).distinct().size != table.entries.size) {
            return PartialPageTableValidation.INVALID
        }

        return when (table.completeness) {
            PartialPageTableCompleteness.COMPLETE -> {
                val expectedOrdinals = (0 until table.totalPageCount).toList()
                if (
                    table.schemaVersion == PARTIAL_PAGE_TABLE_SCHEMA_VERSION &&
                    table.totalPageCount > 0 &&
                    table.entries.size == table.totalPageCount &&
                    table.entries.map(PartialPageTableEntry::readerOrdinal).sorted() == expectedOrdinals
                ) {
                    PartialPageTableValidation.COMPLETE
                } else {
                    PartialPageTableValidation.INVALID
                }
            }
            PartialPageTableCompleteness.PARTIAL -> {
                if (table.schemaVersion == PARTIAL_PAGE_TABLE_SCHEMA_VERSION) {
                    PartialPageTableValidation.PARTIAL
                } else {
                    PartialPageTableValidation.INVALID
                }
            }
            PartialPageTableCompleteness.LEGACY_UNPROVEN -> {
                if (
                    table.schemaVersion == LEGACY_PAGE_TABLE_SCHEMA_VERSION &&
                    table.entries.size == table.totalPageCount
                ) {
                    PartialPageTableValidation.LEGACY_UNPROVEN
                } else {
                    PartialPageTableValidation.INVALID
                }
            }
        }
    }

    fun canBuildReaderPageList(table: PartialPageTable): Boolean =
        validate(table) == PartialPageTableValidation.COMPLETE

    fun normalizeLegacyPageUrls(table: PartialPageTable, pageUrls: List<String>): PartialPageTable {
        if (validate(table) != PartialPageTableValidation.LEGACY_UNPROVEN) return table
        val storedUrls = table.entries
            .sortedBy(PartialPageTableEntry::readerOrdinal)
            .map { entry -> entry.imageUrl?.takeIf(String::isNotBlank) ?: entry.pageUrl }
        return if (storedUrls == pageUrls) table else PartialPageTable.legacy(pageUrls)
    }
}

data class PartialCommittedPage(
    val readerOrdinal: Int,
    val sourcePageIndex: Int,
    val opaqueLocation: String,
    val committedRevision: Long,
)

data class PartialDownloadSnapshot(
    val chapterId: Long,
    val identity: DownloadChapterIdentity,
    val attemptGeneration: Long,
    val queueStatus: DownloadQueueStatus,
    val pageTable: PartialPageTable,
    val committedPages: List<PartialCommittedPage>,
)

fun interface PartialDownloadSnapshotLookup {
    fun snapshot(chapterId: Long, identity: DownloadChapterIdentity): PartialDownloadSnapshot?

    /** O(1) in production; the default keeps lightweight fakes source-compatible. */
    fun committedPageCandidate(
        chapterId: Long,
        identity: DownloadChapterIdentity,
        readerOrdinal: Int,
        sourcePageIndex: Int,
    ): PartialReaderPageCandidate? {
        val snapshot = snapshot(chapterId, identity)
            ?.takeIf { it.chapterId == chapterId && it.identity == identity }
            ?: return null
        if (!PartialPageTablePolicy.canBuildReaderPageList(snapshot.pageTable)) return null
        val entry = snapshot.pageTable.entries.singleOrNull { it.readerOrdinal == readerOrdinal }
            ?.takeIf { it.sourcePageIndex == sourcePageIndex }
            ?: return null
        val committed = snapshot.committedPages.singleOrNull { it.readerOrdinal == readerOrdinal }
            ?.takeIf {
                it.sourcePageIndex == entry.sourcePageIndex &&
                    it.opaqueLocation.isNotBlank() &&
                    it.committedRevision > 0L
            }
            ?: return null
        return PartialReaderPageCandidate(
            attemptGeneration = snapshot.attemptGeneration,
            readerOrdinal = readerOrdinal,
            sourcePageIndex = sourcePageIndex,
            opaqueLocation = committed.opaqueLocation,
            committedRevision = committed.committedRevision,
        )
    }
}

object DisabledPartialDownloadSnapshotLookup : PartialDownloadSnapshotLookup {
    override fun snapshot(chapterId: Long, identity: DownloadChapterIdentity): PartialDownloadSnapshot? = null
}
