package tachiyomi.domain.creator.interactor

import tachiyomi.domain.creator.model.CreatorMention
import tachiyomi.domain.creator.model.CreatorMentionEvidence
import tachiyomi.domain.creator.model.CreatorMetadataField
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.service.CreatorNameNormalizer
import tachiyomi.domain.manga.model.Manga

class ExtractCreatorsFromManga {

    fun await(manga: Manga): List<CreatorMention> {
        val ordered = linkedMapOf<String, MutableMention>()
        addNames(ordered, manga.author, CreatorRole.AUTHOR, CreatorMetadataField.AUTHOR)
        addNames(ordered, manga.artist, CreatorRole.ARTIST, CreatorMetadataField.ARTIST)
        return ordered.values.mapIndexed { index, mention ->
            CreatorMention(
                displayName = mention.displayName,
                normalizedName = mention.normalizedName,
                role = mention.role,
                order = index.toLong(),
                evidence = mention.evidence.toList(),
            )
        }
    }

    private fun addNames(
        mentions: LinkedHashMap<String, MutableMention>,
        sourceValue: String?,
        role: CreatorRole,
        field: CreatorMetadataField,
    ) {
        val rawField = sourceValue ?: return
        CreatorNameNormalizer.tokenizeNames(sourceValue).forEach { token ->
            val displayName = token.rawToken
            val normalizedName = CreatorNameNormalizer.normalize(displayName)
            if (normalizedName.isBlank()) return@forEach
            val existing = mentions[normalizedName]
            if (existing == null) {
                mentions[normalizedName] = MutableMention(
                    displayName = displayName,
                    normalizedName = normalizedName,
                    role = role,
                    evidence = mutableListOf(
                        CreatorMentionEvidence(field, rawField, displayName, token.tokenIndex),
                    ),
                )
            } else {
                existing.role = when {
                    existing.role == role -> role
                    existing.role == CreatorRole.BOTH || role == CreatorRole.BOTH -> CreatorRole.BOTH
                    else -> CreatorRole.BOTH
                }
                existing.evidence += CreatorMentionEvidence(field, rawField, displayName, token.tokenIndex)
            }
        }
    }

    private data class MutableMention(
        val displayName: String,
        val normalizedName: String,
        var role: CreatorRole,
        val evidence: MutableList<CreatorMentionEvidence>,
    )
}
