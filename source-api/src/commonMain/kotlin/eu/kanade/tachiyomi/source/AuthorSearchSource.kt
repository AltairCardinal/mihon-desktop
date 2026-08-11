package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.SManga

/**
 * Optional structured author-search capability for extension sources.
 *
 * Existing [CatalogueSource] implementations remain compatible and use normal catalogue search as
 * a lower-confidence fallback. Implementations must return creator matches that are supported by
 * source metadata; echoing the query without evidence is not a structured match.
 */
interface AuthorSearchSource : CatalogueSource {
    suspend fun getAuthorSearchManga(page: Int, authorQuery: String): AuthorSearchPage
}

data class AuthorSearchPage(
    val mangas: List<AuthorSearchManga>,
    val hasNextPage: Boolean,
)

data class AuthorSearchManga(
    val manga: SManga,
    val matchedCreators: List<AuthorSearchCreatorMatch>,
    val readingLanguageTag: String? = null,
    val originalLanguageTag: String? = null,
)

data class AuthorSearchCreatorMatch(
    val displayName: String,
    val role: AuthorSearchCreatorRole,
    val evidence: String,
) {
    init {
        require(displayName.isNotBlank())
        require(evidence.isNotBlank())
    }
}

enum class AuthorSearchCreatorRole {
    AUTHOR,
    ARTIST,
    BOTH,
    UNKNOWN,
}
