package mihon.domain.reader.partial

import mihon.domain.download.DownloadQueueStatus
import mihon.domain.reader.content.DownloadChapterIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PartialDownloadSnapshotPolicyTest {

    @Test
    fun `complete page table preserves reader order source indexes urls and nullable image urls`() {
        val table = PartialPageTable.complete(
            listOf(
                PartialPageTableEntry(0, 4, "/page/first", "https://img/first.jpg"),
                PartialPageTableEntry(1, 19, "/page/middle", null),
                PartialPageTableEntry(2, 41, "/page/last", "https://img/last.jpg"),
            ),
        )

        assertEquals(PARTIAL_PAGE_TABLE_SCHEMA_VERSION, table.schemaVersion)
        assertEquals(PartialPageTableCompleteness.COMPLETE, table.completeness)
        assertEquals(3, table.totalPageCount)
        assertEquals(listOf(0, 1, 2), table.entries.map(PartialPageTableEntry::readerOrdinal))
        assertEquals(listOf(4, 19, 41), table.entries.map(PartialPageTableEntry::sourcePageIndex))
        assertEquals(
            listOf("/page/first", "/page/middle", "/page/last"),
            table.entries.map(PartialPageTableEntry::pageUrl),
        )
        assertEquals(
            listOf("https://img/first.jpg", null, "https://img/last.jpg"),
            table.entries.map(PartialPageTableEntry::imageUrl),
        )
        assertEquals(PartialPageTableValidation.COMPLETE, PartialPageTablePolicy.validate(table))
    }

    @Test
    fun `legacy lists remain unproven while malformed and future tables are rejected`() {
        val legacy = PartialPageTable.legacy(listOf("https://img/1.jpg", "https://img/2.jpg"))
        val duplicateOrdinal = PartialPageTable.complete(
            listOf(
                PartialPageTableEntry(0, 7, "/one", null),
                PartialPageTableEntry(0, 8, "/two", null),
            ),
        )
        val missingOrdinal = PartialPageTable(
            schemaVersion = PARTIAL_PAGE_TABLE_SCHEMA_VERSION,
            completeness = PartialPageTableCompleteness.COMPLETE,
            totalPageCount = 3,
            entries = listOf(
                PartialPageTableEntry(0, 7, "/one", null),
                PartialPageTableEntry(2, 9, "/three", null),
            ),
        )
        val mismatchedTotal = PartialPageTable.complete(
            listOf(PartialPageTableEntry(0, 7, "/one", null)),
        ).copy(totalPageCount = 2)
        val emptyComplete = PartialPageTable.complete(emptyList())
        val future = legacy.copy(
            schemaVersion = PARTIAL_PAGE_TABLE_SCHEMA_VERSION + 1,
            completeness = PartialPageTableCompleteness.COMPLETE,
        )

        assertEquals(PartialPageTableValidation.LEGACY_UNPROVEN, PartialPageTablePolicy.validate(legacy))
        assertEquals(listOf(0, 1), legacy.entries.map(PartialPageTableEntry::readerOrdinal))
        assertFalse(PartialPageTablePolicy.canBuildReaderPageList(legacy))
        assertEquals(
            listOf("https://replacement/1.jpg"),
            PartialPageTablePolicy.normalizeLegacyPageUrls(legacy, listOf("https://replacement/1.jpg"))
                .entries
                .mapNotNull(PartialPageTableEntry::imageUrl),
        )
        assertEquals(PartialPageTableValidation.INVALID, PartialPageTablePolicy.validate(duplicateOrdinal))
        assertEquals(PartialPageTableValidation.INVALID, PartialPageTablePolicy.validate(missingOrdinal))
        assertEquals(PartialPageTableValidation.INVALID, PartialPageTablePolicy.validate(mismatchedTotal))
        assertEquals(PartialPageTableValidation.INVALID, PartialPageTablePolicy.validate(emptyComplete))
        assertFalse(PartialPageTablePolicy.canBuildReaderPageList(emptyComplete))
        assertEquals(PartialPageTableValidation.UNSUPPORTED_VERSION, PartialPageTablePolicy.validate(future))
    }

    @Test
    fun `snapshot is identity and generation bound and disabled lookup is a constant miss`() {
        val identity = identity()
        val snapshot = PartialDownloadSnapshot(
            chapterId = 7L,
            identity = identity,
            attemptGeneration = 11L,
            queueStatus = DownloadQueueStatus.ERROR,
            pageTable = PartialPageTable.complete(
                listOf(PartialPageTableEntry(0, 3, "/page", "https://img/page.jpg")),
            ),
            committedPages = listOf(
                PartialCommittedPage(
                    readerOrdinal = 0,
                    sourcePageIndex = 3,
                    opaqueLocation = "opaque://partial/001.jpg",
                    committedRevision = 5L,
                ),
            ),
        )

        assertEquals(identity, snapshot.identity)
        assertEquals(11L, snapshot.attemptGeneration)
        assertEquals(5L, snapshot.committedPages.single().committedRevision)
        assertTrue(snapshot.committedPages.single().opaqueLocation.startsWith("opaque://"))
        assertNull(DisabledPartialDownloadSnapshotLookup.snapshot(7L, identity))
    }

    @Test
    fun `live lookup never exposes an unverified legacy artifact`() {
        val identity = identity()
        val legacy = PartialDownloadSnapshot(
            chapterId = 8L,
            identity = identity,
            attemptGeneration = 12L,
            queueStatus = DownloadQueueStatus.ERROR,
            pageTable = PartialPageTable.legacy(listOf("https://img/legacy.jpg")),
            committedPages = listOf(
                PartialCommittedPage(
                    readerOrdinal = 0,
                    sourcePageIndex = 0,
                    opaqueLocation = "opaque://partial/001.jpg",
                    committedRevision = 6L,
                ),
            ),
        )
        val lookup = PartialDownloadSnapshotLookup { _, _ -> legacy }

        assertNull(lookup.committedPageCandidate(8L, identity, readerOrdinal = 0, sourcePageIndex = 0))
    }

    private fun identity() = DownloadChapterIdentity(
        sourceDisplayName = "Source",
        mangaTitle = "Manga",
        chapterName = "Chapter",
        scanlator = "Group",
        chapterUrl = "/chapter",
        disallowNonAsciiFilenames = false,
    )
}
