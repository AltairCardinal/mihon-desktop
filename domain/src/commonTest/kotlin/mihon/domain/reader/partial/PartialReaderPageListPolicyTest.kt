package mihon.domain.reader.partial

import mihon.domain.download.DownloadQueueStatus
import mihon.domain.reader.content.DownloadChapterIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PartialReaderPageListPolicyTest {

    @Test
    fun `complete metadata builds the stable full list without compacting sparse source indexes`() {
        val snapshot = snapshot(
            pageTable = PartialPageTable.complete(
                listOf(
                    PartialPageTableEntry(0, 4, "", "https://img/first.jpg"),
                    PartialPageTableEntry(1, 19, "/page/middle", null),
                    PartialPageTableEntry(2, 41, "/page/last", "https://img/last.jpg"),
                ),
            ),
            committedPages = listOf(committed(0, 4, revision = 7L)),
        )

        val ready = assertInstanceOf(
            PartialReaderPageListEvaluation.Ready::class.java,
            PartialReaderPageListPolicy.evaluate(snapshot),
        )

        assertEquals(PartialReaderPageListOrigin.COMPLETE_METADATA, ready.pageList.origin)
        assertNull(ready.pageList.fallbackReason)
        assertEquals(listOf(0, 1, 2), ready.pageList.pages.map(PartialReaderPage::readerOrdinal))
        assertEquals(listOf(4, 19, 41), ready.pageList.pages.map(PartialReaderPage::sourcePageIndex))
        assertEquals(
            listOf("", "/page/middle", "/page/last"),
            ready.pageList.pages.map(PartialReaderPage::pageUrl),
        )
        assertEquals(
            listOf("https://img/first.jpg", null, "https://img/last.jpg"),
            ready.pageList.pages.map(PartialReaderPage::imageUrl),
        )
        assertEquals(7L, ready.pageList.pages[0].committedCandidate?.committedRevision)
        assertTrue(ready.pageList.pages.drop(1).all { it.committedCandidate == null })
    }

    @Test
    fun `legacy metadata overlays only after exact ordinal url count and source index proof`() {
        val legacy = snapshot(
            pageTable = PartialPageTable.legacy(
                listOf("https://img/first.jpg", "https://img/second.jpg", "https://img/third.jpg"),
            ),
            committedPages = listOf(committed(1, 1, revision = 3L)),
        )
        val online = listOf(
            PartialReaderOnlinePage(0, 4, "/page/first", "https://img/first.jpg"),
            PartialReaderOnlinePage(1, 19, "/page/second", "https://img/second.jpg"),
            PartialReaderOnlinePage(2, 41, "/page/third", "https://img/third.jpg"),
        )

        val verified = PartialReaderPageListPolicy.mergeOnline(legacy, legacy, online)
        val mismatched = PartialReaderPageListPolicy.mergeOnline(
            legacy,
            legacy,
            online.mapIndexed { ordinal, page ->
                if (ordinal == 1) page.copy(imageUrl = "https://img/replaced.jpg") else page
            },
        )

        assertEquals(PartialReaderPageListOrigin.VERIFIED_LEGACY, verified.origin)
        assertEquals(listOf(4, 19, 41), verified.pages.map(PartialReaderPage::sourcePageIndex))
        assertEquals(19, verified.pages[1].committedCandidate?.sourcePageIndex)
        assertEquals(3L, verified.pages[1].committedCandidate?.committedRevision)
        assertEquals(PartialReaderPageListOrigin.ONLINE_FALLBACK, mismatched.origin)
        assertEquals(PartialReaderPageListFallbackReason.SOURCE_MISMATCH, mismatched.fallbackReason)
        assertEquals(listOf(0, 1, 2), mismatched.pages.map(PartialReaderPage::sourcePageIndex))
        assertTrue(mismatched.pages.all { it.committedCandidate == null })
    }

    @Test
    fun `invalid incomplete ambiguous and stale metadata report bounded online fallbacks`() {
        val complete = snapshot(
            pageTable = PartialPageTable.complete(
                listOf(
                    PartialPageTableEntry(0, 4, "/page/first", "https://img/first.jpg"),
                    PartialPageTableEntry(1, 19, "/page/second", "https://img/second.jpg"),
                ),
            ),
            committedPages = listOf(committed(0, 4)),
        )
        val online = listOf(
            PartialReaderOnlinePage(0, 4, "/page/first", "https://img/first.jpg"),
            PartialReaderOnlinePage(1, 19, "/page/second", "https://img/second.jpg"),
        )
        val cases = listOf(
            null to PartialReaderPageListFallbackReason.NO_SNAPSHOT,
            complete.copy(
                pageTable = PartialPageTable.partial(2, complete.pageTable.entries.take(1)),
            ) to PartialReaderPageListFallbackReason.INCOMPLETE_METADATA,
            complete.copy(
                pageTable = complete.pageTable.copy(schemaVersion = PARTIAL_PAGE_TABLE_SCHEMA_VERSION + 1),
            ) to PartialReaderPageListFallbackReason.UNSUPPORTED_VERSION,
            complete.copy(
                pageTable = complete.pageTable.copy(totalPageCount = 3),
            ) to PartialReaderPageListFallbackReason.INVALID_METADATA,
            complete.copy(
                pageTable = PartialPageTable.complete(
                    listOf(
                        PartialPageTableEntry(0, 4, "/page/first", "https://img/first.jpg"),
                        PartialPageTableEntry(1, 4, "/page/second", "https://img/second.jpg"),
                    ),
                ),
            ) to PartialReaderPageListFallbackReason.AMBIGUOUS_SOURCE_INDEX,
        )

        cases.forEach { (candidate, expectedReason) ->
            val evaluation = assertInstanceOf(
                PartialReaderPageListEvaluation.RequiresOnline::class.java,
                PartialReaderPageListPolicy.evaluate(candidate),
            )
            assertEquals(expectedReason, evaluation.reason)
            val fallback = PartialReaderPageListPolicy.mergeOnline(candidate, candidate, online)
            assertEquals(expectedReason, fallback.fallbackReason)
            assertEquals(listOf(0, 1), fallback.pages.map(PartialReaderPage::sourcePageIndex))
            assertTrue(fallback.pages.all { it.committedCandidate == null })
        }

        val duplicateSource = PartialReaderPageListPolicy.mergeOnline(
            snapshot(
                pageTable = PartialPageTable.legacy(listOf("https://img/first.jpg", "https://img/second.jpg")),
                committedPages = listOf(committed(0, 0)),
            ),
            snapshot(
                pageTable = PartialPageTable.legacy(listOf("https://img/first.jpg", "https://img/second.jpg")),
                committedPages = listOf(committed(0, 0)),
            ),
            online.map { it.copy(sourcePageIndex = 4) },
        )
        assertEquals(PartialReaderPageListFallbackReason.AMBIGUOUS_SOURCE_INDEX, duplicateSource.fallbackReason)

        val stale = PartialReaderPageListPolicy.mergeOnline(
            complete,
            complete.copy(attemptGeneration = complete.attemptGeneration + 1),
            online,
        )
        assertEquals(PartialReaderPageListFallbackReason.STALE_SNAPSHOT, stale.fallbackReason)
        assertEquals(listOf(0, 1), stale.pages.map(PartialReaderPage::sourcePageIndex))
        assertTrue(stale.pages.all { it.committedCandidate == null })
    }

    @Test
    fun `legacy metadata source indexes must retain an unambiguous ordinal identity`() {
        val duplicateMetadata = snapshot(
            pageTable = PartialPageTable(
                schemaVersion = 0,
                completeness = PartialPageTableCompleteness.LEGACY_UNPROVEN,
                totalPageCount = 2,
                entries = listOf(
                    PartialPageTableEntry(0, 0, "https://img/first.jpg", "https://img/first.jpg"),
                    PartialPageTableEntry(1, 0, "https://img/second.jpg", "https://img/second.jpg"),
                ),
            ),
            committedPages = listOf(committed(1, 0)),
        )
        val online = listOf(
            PartialReaderOnlinePage(0, 4, "/page/first", "https://img/first.jpg"),
            PartialReaderOnlinePage(1, 19, "/page/second", "https://img/second.jpg"),
        )

        val evaluation = assertInstanceOf(
            PartialReaderPageListEvaluation.RequiresOnline::class.java,
            PartialReaderPageListPolicy.evaluate(duplicateMetadata),
        )
        val fallback = PartialReaderPageListPolicy.mergeOnline(duplicateMetadata, duplicateMetadata, online)

        assertEquals(PartialReaderPageListFallbackReason.AMBIGUOUS_SOURCE_INDEX, evaluation.reason)
        assertEquals(PartialReaderPageListFallbackReason.AMBIGUOUS_SOURCE_INDEX, fallback.fallbackReason)
        assertEquals(listOf(0, 1), fallback.pages.map(PartialReaderPage::sourcePageIndex))
        assertTrue(fallback.pages.all { it.committedCandidate == null })
    }

    private fun snapshot(
        pageTable: PartialPageTable,
        committedPages: List<PartialCommittedPage>,
    ) = PartialDownloadSnapshot(
        chapterId = 7L,
        identity = identity(),
        attemptGeneration = 11L,
        queueStatus = DownloadQueueStatus.DOWNLOADING,
        pageTable = pageTable,
        committedPages = committedPages,
    )

    private fun committed(
        ordinal: Int,
        sourceIndex: Int,
        revision: Long = 1L,
    ) = PartialCommittedPage(
        readerOrdinal = ordinal,
        sourcePageIndex = sourceIndex,
        opaqueLocation = "opaque://partial/${ordinal + 1}.jpg",
        committedRevision = revision,
    )

    private fun identity() = DownloadChapterIdentity(
        sourceDisplayName = "Source",
        mangaTitle = "Manga",
        chapterName = "Chapter",
        scanlator = "Group",
        chapterUrl = "/chapter",
        disallowNonAsciiFilenames = false,
    )
}
