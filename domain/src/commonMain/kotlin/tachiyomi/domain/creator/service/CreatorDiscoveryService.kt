package tachiyomi.domain.creator.service

import eu.kanade.tachiyomi.source.CatalogueSource
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.DiscoveryCandidate
import tachiyomi.domain.creator.model.DiscoveryCandidateState
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.source.service.SourceMangaSearchService

class CreatorDiscoveryService(
    private val creatorRepository: CreatorRepository,
    private val sourceMangaSearchService: SourceMangaSearchService = SourceMangaSearchService(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    suspend fun discoverDueWatches(sources: List<CatalogueSource>): CreatorDiscoveryResult {
        var newCandidates = 0
        var errors = 0
        val discovered = mutableListOf<DiscoveryCandidate>()

        creatorRepository.getFollowedCreators()
            .filter { it.enabled }
            .forEach { watch ->
                val creator = creatorRepository.getCreator(watch.creatorId) ?: return@forEach
                val result = discoverForCreator(
                    creator = creator,
                    sources = sources,
                    sourceIds = watch.sourceIds,
                    languageTags = watch.languageTags,
                )
                discovered += result.candidates
                newCandidates += result.newCandidateCount
                errors += result.errorCount
                creatorRepository.updateWatchCheckResult(
                    creatorId = creator.id,
                    checkedAt = clock(),
                    success = result.errorCount == 0,
                    error = if (result.errorCount == 0) null else "${result.errorCount} source(s) failed",
                )
            }

        return CreatorDiscoveryResult(
            newCandidateCount = newCandidates,
            errorCount = errors,
            candidates = discovered,
        )
    }

    suspend fun discoverCreator(
        creatorId: Long,
        sources: List<CatalogueSource>,
        sourceIds: List<Long> = emptyList(),
        languageTags: List<String> = emptyList(),
    ): CreatorDiscoveryResult {
        val creator = creatorRepository.getCreator(creatorId)
            ?: return CreatorDiscoveryResult(newCandidateCount = 0, errorCount = 0, candidates = emptyList())
        return discoverForCreator(
            creator = creator,
            sources = sources,
            sourceIds = sourceIds,
            languageTags = languageTags,
        )
    }

    private suspend fun discoverForCreator(
        creator: tachiyomi.domain.creator.model.Creator,
        sources: List<CatalogueSource>,
        sourceIds: List<Long>,
        languageTags: List<String>,
    ): CreatorDiscoveryResult {
        var newCandidates = 0
        var errors = 0
        val discovered = mutableListOf<DiscoveryCandidate>()
        val selectedSources = sources.filter {
            sourceIds.isEmpty() || it.id in sourceIds
        }

        selectedSources.forEach { source ->
            try {
                val searchResults = sourceMangaSearchService.searchAllPages(
                    source = source,
                    query = creator.displayName,
                    filters = source.getFilterList(),
                )
                searchResults.forEach { result ->
                    val details = runCatching { source.getMangaDetails(result.copy()) }.getOrElse { result }
                    val candidateUrl = details.safeUrl(fallback = result.safeUrl())
                    val language = MangaLanguageDetector.detect(
                        sourceLang = source.lang,
                        explicitLanguage = null,
                        title = details.title,
                        description = details.description,
                        genres = details.getGenres().orEmpty(),
                    )
                    if (languageTags.isNotEmpty() && language.tag !in languageTags) {
                        return@forEach
                    }

                    val candidate = creatorRepository.upsertDiscoveryCandidate(
                        source = source.id,
                        url = candidateUrl,
                        title = details.title,
                        authorText = details.author,
                        artistText = details.artist,
                        languageTag = language.tag,
                        languageConfidence = language.confidence,
                        languageEvidence = language.evidence.name,
                        thumbnailUrl = details.thumbnail_url,
                        detailsFetchedAt = clock(),
                        state = DiscoveryCandidateState.NEW,
                    )
                    creatorRepository.linkDiscoveryCandidateCreator(
                        candidateId = candidate.id,
                        creatorId = creator.id,
                        role = resolveRole(details.author, details.artist, creator.displayName),
                        sourceText = details.author ?: details.artist,
                        confidence = 0.8,
                        evidence = "creator discovery search",
                    )
                    discovered += candidate
                    newCandidates += 1
                }
            } catch (e: Exception) {
                errors += 1
            }
        }

        return CreatorDiscoveryResult(
            newCandidateCount = newCandidates,
            errorCount = errors,
            candidates = discovered,
        )
    }

    private fun eu.kanade.tachiyomi.source.model.SManga.safeUrl(fallback: String = ""): String {
        return runCatching { url }.getOrDefault(fallback)
    }

    private fun resolveRole(author: String?, artist: String?, creatorName: String): CreatorRole {
        val normalized = CreatorNameNormalizer.normalize(creatorName)
        val isAuthor = CreatorNameNormalizer.splitNames(author).any {
            CreatorNameNormalizer.normalize(it) == normalized
        }
        val isArtist = CreatorNameNormalizer.splitNames(artist).any {
            CreatorNameNormalizer.normalize(it) == normalized
        }
        return when {
            isAuthor && isArtist -> CreatorRole.BOTH
            isAuthor -> CreatorRole.AUTHOR
            isArtist -> CreatorRole.ARTIST
            else -> CreatorRole.UNKNOWN
        }
    }
}

data class CreatorDiscoveryResult(
    val newCandidateCount: Int,
    val errorCount: Int,
    val candidates: List<DiscoveryCandidate>,
)
