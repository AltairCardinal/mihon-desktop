package mihon.domain.reader.content

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderContentPolicyTest {
    @Test
    fun `download artifact wins before every source route`() {
        ReaderSourceContentKind.entries.forEach { sourceKind ->
            assertEquals(
                ReaderChapterRoute.DOWNLOAD,
                ReaderChapterContentResolver.resolve(downloadLocated = true, sourceKind = sourceKind),
            )
        }
    }

    @Test
    fun `source facts retain every original Mihon route`() {
        assertEquals(ReaderChapterRoute.LOCAL_DIRECTORY, route(ReaderSourceContentKind.LOCAL_DIRECTORY))
        assertEquals(ReaderChapterRoute.LOCAL_ARCHIVE, route(ReaderSourceContentKind.LOCAL_ARCHIVE))
        assertEquals(ReaderChapterRoute.LOCAL_EPUB, route(ReaderSourceContentKind.LOCAL_EPUB))
        assertEquals(ReaderChapterRoute.ONLINE, route(ReaderSourceContentKind.ONLINE))
        assertEquals(ReaderChapterRoute.MISSING_SOURCE, route(ReaderSourceContentKind.MISSING_SOURCE))
        assertEquals(ReaderChapterRoute.UNSUPPORTED, route(ReaderSourceContentKind.UNSUPPORTED))
    }

    @Test
    fun `chapter artifact candidates preserve current cbz legacy and alternate non ascii order`() {
        val identity = DownloadChapterIdentity(
            sourceDisplayName = "Source",
            mangaTitle = "漫画",
            chapterName = "第 1 话",
            scanlator = "组",
            chapterUrl = "/chapter/1",
            disallowNonAsciiFilenames = false,
        )
        val candidates = DownloadArtifactNamingPolicy.chapterCandidates(identity)

        assertEquals(
            listOf(
                "组_第 1 话_690668",
                "组_第 1 话_690668.cbz",
                "组_第 1 话",
                "组_第 1 话.cbz",
                "e7bb84_e7acac 1 e8af9d_690668",
                "e7bb84_e7acac 1 e8af9d_690668.cbz",
            ),
            candidates.map(DownloadArtifactCandidate::name),
        )
        assertEquals(
            listOf(
                DownloadArtifactKind.DIRECTORY,
                DownloadArtifactKind.CBZ,
                DownloadArtifactKind.DIRECTORY,
                DownloadArtifactKind.CBZ,
                DownloadArtifactKind.DIRECTORY,
                DownloadArtifactKind.CBZ,
            ),
            candidates.map(DownloadArtifactCandidate::kind),
        )

        val asciiFirst = DownloadArtifactNamingPolicy.chapterCandidates(
            identity.copy(disallowNonAsciiFilenames = true),
        )
        assertEquals(
            listOf(
                "e7bb84_e7acac 1 e8af9d_690668",
                "e7bb84_e7acac 1 e8af9d_690668.cbz",
                "组_第 1 话",
                "组_第 1 话.cbz",
                "组_第 1 话_690668",
                "组_第 1 话_690668.cbz",
            ),
            asciiFirst.map(DownloadArtifactCandidate::name),
        )
        assertTrue(asciiFirst.all { it.name.encodeToByteArray().size <= 240 })
    }

    @Test
    fun `filename policy preserves Android byte truncation invalid names and supplementary characters`() {
        val longChapter = "漫".repeat(80)
        val identity = DownloadChapterIdentity(
            sourceDisplayName = "Source",
            mangaTitle = "Manga",
            chapterName = longChapter,
            scanlator = null,
            chapterUrl = "/chapter/1",
            disallowNonAsciiFilenames = false,
        )

        assertEquals("漫".repeat(76) + "_690668", DownloadArtifactNamingPolicy.currentChapterName(identity))
        assertEquals("(invalid)", DownloadArtifactNamingPolicy.validFilename(" .. "))
        assertEquals("a_b_c", DownloadArtifactNamingPolicy.validFilename("a/b:c"))
        assertEquals("😀", DownloadArtifactNamingPolicy.validFilename("😀😀", maxBytes = 5))
        assertEquals("3f3f", DownloadArtifactNamingPolicy.validFilename("😀", disallowNonAscii = true))
    }

    @Test
    fun `artifact locator owns first match and stops probing later candidates`() {
        val identity = DownloadChapterIdentity(
            sourceDisplayName = "Source",
            mangaTitle = "Manga",
            chapterName = "Chapter 1",
            scanlator = null,
            chapterUrl = "/chapter/1",
            disallowNonAsciiFilenames = false,
        )
        val probed = mutableListOf<DownloadArtifactCandidate>()
        val locator = DownloadArtifactLocator { _, candidate ->
            probed += candidate
            candidate.name.takeIf { it == "Chapter 1.cbz" }?.let { "/opaque/$it" }
        }

        val match = locator.locate(identity)

        assertEquals("Chapter 1.cbz", match?.candidate?.name)
        assertEquals(DownloadArtifactKind.CBZ, match?.candidate?.kind)
        assertEquals("/opaque/Chapter 1.cbz", match?.opaqueLocation)
        assertEquals(
            listOf("Chapter 1_690668", "Chapter 1_690668.cbz", "Chapter 1", "Chapter 1.cbz"),
            probed.map(DownloadArtifactCandidate::name),
        )

        val missing = DownloadArtifactLocator { _, _ -> null }
        assertNull(missing.locate(identity))
    }

    @Test
    fun `route specific image order and probe policy remain distinct`() {
        val names = listOf("10.jpg", "2.jpg", "1.jpg", "A.jpg", "a.jpg", "page")

        assertEquals(
            listOf("1.jpg", "10.jpg", "2.jpg", "A.jpg", "a.jpg", "page"),
            ReaderImageCandidatePolicy.order(ReaderImageSortMode.DOWNLOAD_LEXICAL_CASE_SENSITIVE, names),
        )
        assertEquals(
            listOf("1.jpg", "2.jpg", "10.jpg", "A.jpg", "a.jpg", "page"),
            ReaderImageCandidatePolicy.order(ReaderImageSortMode.LOCAL_NATURAL_CASE_INSENSITIVE, names),
        )
        assertEquals(
            listOf("01.jpg", "1.jpg", "001.jpg"),
            ReaderImageCandidatePolicy.order(
                ReaderImageSortMode.LOCAL_NATURAL_CASE_INSENSITIVE,
                listOf("01.jpg", "1.jpg", "001.jpg"),
            ),
        )
        listOf("avif", "gif", "heif", "jpg", "jp2", "jpx", "jxl", "png", "webp").forEach { extension ->
            assertFalse(ReaderImageCandidatePolicy.requiresContentProbe("001.$extension"), extension)
        }
        assertTrue(ReaderImageCandidatePolicy.requiresContentProbe("001"))
        assertTrue(ReaderImageCandidatePolicy.requiresContentProbe("001.bin"))
        assertFalse(ReaderImageCandidatePolicy.requiresContentProbe("jpg"))
        assertTrue(ReaderImageCandidatePolicy.requiresContentProbe("001.JPG"))

        var probeCount = 0
        assertTrue(
            ReaderImageCandidatePolicy.accepts("001.bin") {
                probeCount++
                true
            },
        )
        assertEquals(1, probeCount)
        assertFalse(
            ReaderImageCandidatePolicy.accepts("002.bin") {
                probeCount++
                false
            },
        )
        assertEquals(2, probeCount)
        assertTrue(
            ReaderImageCandidatePolicy.accepts("001.jpg") {
                probeCount++
                false
            },
        )
        assertEquals(2, probeCount)
    }

    private fun route(kind: ReaderSourceContentKind) = ReaderChapterContentResolver.resolve(
        downloadLocated = false,
        sourceKind = kind,
    )
}
