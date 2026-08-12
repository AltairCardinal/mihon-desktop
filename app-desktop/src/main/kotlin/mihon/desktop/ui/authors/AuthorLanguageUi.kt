package mihon.desktop.ui.authors

import tachiyomi.domain.creator.model.LanguageCertainty

enum class LanguageArchiveFilter {
    ALL,
    CONFIRMED,
    PROBABLE,
    NEEDS_REVIEW,
    ;

    fun accepts(certainty: LanguageCertainty): Boolean = when (this) {
        ALL -> true
        CONFIRMED -> certainty == LanguageCertainty.CONFIRMED
        PROBABLE -> certainty == LanguageCertainty.PROBABLE
        NEEDS_REVIEW -> certainty == LanguageCertainty.UNKNOWN || certainty == LanguageCertainty.CONFLICT
    }
}

data class LanguageFilterSummary(
    val confirmed: Int,
    val probable: Int,
    val needsReview: Int,
) {
    companion object {
        fun from(certainties: Iterable<LanguageCertainty>) = LanguageFilterSummary(
            confirmed = certainties.count { it == LanguageCertainty.CONFIRMED },
            probable = certainties.count { it == LanguageCertainty.PROBABLE },
            needsReview = certainties.count {
                it == LanguageCertainty.UNKNOWN || it == LanguageCertainty.CONFLICT
            },
        )
    }
}
