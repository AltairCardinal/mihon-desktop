package mihon.desktop.ui.authors

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.model.LanguageCertainty

class AuthorLanguageUiTest {

    @Test
    fun `language filter counts certainty buckets and can reveal review items`() {
        val summary = LanguageFilterSummary.from(
            listOf(
                LanguageCertainty.CONFIRMED,
                LanguageCertainty.CONFIRMED,
                LanguageCertainty.PROBABLE,
                LanguageCertainty.UNKNOWN,
                LanguageCertainty.CONFLICT,
            ),
        )

        assertEquals(2, summary.confirmed)
        assertEquals(1, summary.probable)
        assertEquals(2, summary.needsReview)
        assertTrue(LanguageArchiveFilter.NEEDS_REVIEW.accepts(LanguageCertainty.UNKNOWN))
        assertTrue(LanguageArchiveFilter.NEEDS_REVIEW.accepts(LanguageCertainty.CONFLICT))
    }

    @Test
    fun `confirmed language filter does not present probable items as certain`() {
        assertTrue(LanguageArchiveFilter.CONFIRMED.accepts(LanguageCertainty.CONFIRMED))
        assertTrue(!LanguageArchiveFilter.CONFIRMED.accepts(LanguageCertainty.PROBABLE))
        assertTrue(!LanguageArchiveFilter.CONFIRMED.accepts(LanguageCertainty.UNKNOWN))
    }
}
