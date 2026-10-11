package tachiyomi.domain.source.service

/** Language selections are OR; group selections are OR; the two dimensions are AND. */
data class CatalogResultFilter(
    val languageTags: Set<String> = emptySet(),
    val translationGroupKeys: Set<String> = emptySet(),
    val includeUnknownLanguage: Boolean = false,
    val includeUnknownTranslationGroup: Boolean = false,
) {
    val isActive: Boolean
        get() = languageTags.isNotEmpty() || translationGroupKeys.isNotEmpty() ||
            includeUnknownLanguage || includeUnknownTranslationGroup

    fun matches(metadata: SourceResultMetadata): Boolean {
        val hasLanguageSelection = languageTags.isNotEmpty() || includeUnknownLanguage
        val knownLanguages = metadata.readingLanguageTags.takeUnless { metadata.language.conflict }.orEmpty()
        val languageMatches = !hasLanguageSelection || if (knownLanguages.isEmpty()) {
            includeUnknownLanguage
        } else {
            languageTags.any { selected ->
                val tag = selected.trim().lowercase().replace('_', '-')
                knownLanguages.any { known -> known == tag || known.startsWith("$tag-") }
            }
        }
        val hasGroupSelection = translationGroupKeys.isNotEmpty() || includeUnknownTranslationGroup
        val groupsMatch = !hasGroupSelection || if (metadata.translationGroups.isEmpty()) {
            includeUnknownTranslationGroup
        } else {
            metadata.translationGroups.any { it.key in translationGroupKeys }
        }
        return languageMatches && groupsMatch
    }
}
