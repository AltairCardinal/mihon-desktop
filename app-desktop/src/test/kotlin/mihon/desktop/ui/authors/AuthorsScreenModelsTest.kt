package mihon.desktop.ui.authors

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.model.ChapterCatalogCompleteness
import tachiyomi.domain.creator.model.LanguageCertainty
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.LanguageEvidenceKind
import tachiyomi.domain.creator.model.LanguageProjectionContract
import tachiyomi.domain.creator.model.SourceWorkArchiveVersion
import tachiyomi.domain.creator.model.SourceWorkNaturalKey

class AuthorsScreenModelsTest {
    @Test
    fun `unknown chapter count is omitted while a complete zero count is retained`() {
        val unknown = version(ChapterCatalogCompleteness.UNKNOWN)
        val complete = version(ChapterCatalogCompleteness.COMPLETE)

        assertEquals(null, chapterCountForWorkMatching(unknown))
        assertEquals(0, chapterCountForWorkMatching(complete))
    }

    private fun version(completeness: ChapterCatalogCompleteness) = SourceWorkArchiveVersion(
        sourceWorkId = 1L,
        naturalKey = SourceWorkNaturalKey(sourceId = 1L, stableSourceUrl = "/work"),
        mangaId = null,
        title = "Work",
        readingLanguage = LanguageProjectionContract(
            dimension = LanguageDimension.READING,
            tag = "und",
            certainty = LanguageCertainty.UNKNOWN,
            evidenceKind = LanguageEvidenceKind.UNKNOWN,
        ),
        chapterCount = 0L,
        inLibrary = false,
        detailsFetchedAt = null,
        lastSeenAt = 1L,
        decision = null,
        chapterCompleteness = completeness,
    )
}
