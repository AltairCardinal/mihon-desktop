package tachiyomi.domain.creator.model

/**
 * One person mention extracted from a manga's author/artist bibliography.
 *
 * [sourceTexts] retains the exact source values used as evidence while [normalizedName] is only a
 * lookup key. Equal normalized names are not, by themselves, proof that two people are identical.
 */
data class CreatorMention(
    val displayName: String,
    val normalizedName: String,
    val role: CreatorRole,
    val order: Long,
    val evidence: List<CreatorMentionEvidence>,
) {
    val sourceTexts: List<String>
        get() = evidence.map(CreatorMentionEvidence::rawToken)
}

enum class CreatorMetadataField {
    AUTHOR,
    ARTIST,
}

data class CreatorMentionEvidence(
    val field: CreatorMetadataField,
    val rawField: String,
    val rawToken: String,
    val tokenIndex: Int,
    val parserVersion: Int = CURRENT_PARSER_VERSION,
) {
    companion object {
        const val CURRENT_PARSER_VERSION = 1
    }
}

data class CreatorLibraryIndexEntry(
    val manga: tachiyomi.domain.manga.model.Manga,
    val mentions: List<CreatorMention>,
)

data class CreatorIdentityOption(
    val id: Long,
    val portableKey: CreatorPortableKey,
    val displayName: String,
    val aliases: List<String>,
    val needsReview: Boolean,
    val currentlyBound: Boolean,
)

sealed interface CreatorMentionResolution {
    data class Resolved(val creatorId: Long) : CreatorMentionResolution

    data class Ambiguous(
        val mention: CreatorMention,
        val options: List<CreatorIdentityOption>,
    ) : CreatorMentionResolution
}
