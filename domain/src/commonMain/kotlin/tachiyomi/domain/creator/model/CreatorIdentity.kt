package tachiyomi.domain.creator.model

/**
 * One effective creator credit extracted from source bibliography or an opted-in title rule.
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
    TITLE_CREATOR,
    TITLE_CIRCLE,
}

/** Exact LF-delimited markers; explanations and lookalike substrings never select a mode. */
object CreatorTitleEvidence {
    const val CREATOR = "title-attribution:v1:TITLE_CREATOR"
    const val CIRCLE = "title-attribution:v1:TITLE_CIRCLE"
    const val CREATOR_AND_CIRCLE = "title-attribution:v1:TITLE_CREATOR+TITLE_CIRCLE"
    const val CIRCLE_AND_CREATOR = "title-attribution:v1:TITLE_CIRCLE+TITLE_CREATOR"
}

/** Persisted in the existing free-form relation evidence column and portable backup field. */
fun List<CreatorMention>.titleAttributionEvidence(): String? = flatMap { it.evidence }
    .map { it.field }
    .filter { it == CreatorMetadataField.TITLE_CREATOR || it == CreatorMetadataField.TITLE_CIRCLE }
    .distinct()
    .takeIf { it.isNotEmpty() }
    ?.joinToString(prefix = "title-attribution:v1:", separator = "+") { it.name }

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
