package tachiyomi.domain.creator.service

import eu.kanade.tachiyomi.source.AuthorSearchCreatorRole
import eu.kanade.tachiyomi.source.AuthorSearchManga
import eu.kanade.tachiyomi.source.AuthorSearchSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import mihon.domain.error.AppError
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.source.service.SourceMangaSearchRequest
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.toSourceAppError

/** Production adapter from extension catalogue APIs to the bounded discovery source contract. */
class CatalogueCreatorDiscoverySourceAdapter(
    private val enabledSourcesProvider: suspend () -> List<CatalogueSource>,
    private val sourceResolver: (Long) -> CatalogueSource?,
    private val sourceMangaSearchService: SourceMangaSearchService = SourceMangaSearchService(),
    private val languageProfileProvider: (CatalogueSource) -> CreatorSourceReadingLanguageProfile = {
        CreatorSourceReadingLanguageProfile.Unknown
    },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : CreatorDiscoverySourcePort {
    private val mutex = Mutex()
    private var snapshotSources = emptyMap<Long, CatalogueSource>()
    private val searchHits = mutableMapOf<SourceWorkNaturalKey, CachedSearchHit>()

    override suspend fun enabledSourcesSnapshot(): List<EnabledCreatorSource> {
        val sources = enabledSourcesProvider()
            .map { sourceResolver(it.id) ?: it }
            .distinctBy(CatalogueSource::id)
        mutex.withLock {
            snapshotSources = sources.associateBy(CatalogueSource::id)
            searchHits.keys.removeAll { it.sourceId !in snapshotSources }
        }
        return sources.map { source ->
            EnabledCreatorSource(
                sourceId = source.id,
                displayName = source.name,
                capabilities = buildSet {
                    add(CreatorSourceCapability.DETAILS)
                    if (source is AuthorSearchSource) {
                        add(CreatorSourceCapability.AUTHOR_SEARCH)
                    } else {
                        add(CreatorSourceCapability.CATALOGUE_SEARCH_FALLBACK)
                    }
                },
                readingLanguageProfile = languageProfileProvider(source),
                catalogueLanguageTag = source.lang,
            )
        }
    }

    override suspend fun searchPage(request: BoundedAuthorSearchPageRequest): CreatorSourcePageResult {
        val source = mutex.withLock { snapshotSources[request.sourceId] }
            ?: return CreatorSourcePageResult.Failure(CreatorSourceFailure.MissingSource)
        val remainingMillis = request.deadlineAtMillis - clock()
        if (remainingMillis <= 0) return CreatorSourcePageResult.Failure(CreatorSourceFailure.Timeout)

        return try {
            withTimeout(remainingMillis) {
                val page = if (source is AuthorSearchSource) {
                    try {
                        source.getAuthorSearchManga(request.page, request.alias).let { result ->
                            SearchPage(result.mangas, result.hasNextPage)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        sourceMangaSearchService.loadPage(
                            source = source,
                            page = request.page,
                            request = SourceMangaSearchRequest.Search(request.alias, source.getFilterList()),
                        ).toSearchPage()
                    }
                } else {
                    sourceMangaSearchService.loadPage(
                        source = source,
                        page = request.page,
                        request = SourceMangaSearchRequest.Search(request.alias, source.getFilterList()),
                    ).toSearchPage()
                }
                if (page.items.isEmpty()) {
                    CreatorSourcePageResult.Empty
                } else {
                    val works = page.items
                        .map { item -> item.toSnapshot(source.id) }
                        .distinctBy(CreatorSourceWorkSnapshot::key)
                    mutex.withLock {
                        page.items.forEach { item -> searchHits[item.key(source.id)] = item.toCachedHit() }
                    }
                    CreatorSourcePageResult.Content(works, page.hasNextPage)
                }
            }
        } catch (_: TimeoutCancellationException) {
            CreatorSourcePageResult.Failure(CreatorSourceFailure.Timeout)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            CreatorSourcePageResult.Failure(error.toCreatorSourceFailure())
        }
    }

    override suspend fun loadDetails(key: SourceWorkNaturalKey): CreatorSourceDetailsResult {
        val source = mutex.withLock { snapshotSources[key.sourceId] }
            ?: return CreatorSourceDetailsResult.Failure(CreatorSourceFailure.MissingSource)
        val cached = mutex.withLock { searchHits[key] }
        val listedManga = cached?.manga?.copy() ?: SManga.create().also {
            it.url = key.stableSourceUrl
            it.title = key.stableSourceUrl
        }
        return try {
            val details = source.getMangaDetails(listedManga)
            val work = details.toSnapshot(
                sourceId = source.id,
                stableUrlFallback = key.stableSourceUrl,
                structuredMatches = cached?.structuredMatches.orEmpty(),
            )
            CreatorSourceDetailsResult.Content(
                CreatorSourceDetails(
                    work = work,
                    readingLanguageTag = cached?.readingLanguageTag,
                    originalLanguageTag = cached?.originalLanguageTag,
                    metadata = buildMap {
                        details.description?.let { put("description", it) }
                        details.getGenres()?.takeIf(List<String>::isNotEmpty)?.let {
                            put("genres", it.joinToString("\u001f"))
                        }
                    },
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            CreatorSourceDetailsResult.Failure(error.toCreatorSourceFailure())
        }
    }

    private fun MangasPage.toSearchPage(): SearchPage = SearchPage(
        items = mangas.map { AuthorSearchManga(it, emptyList()) },
        hasNextPage = hasNextPage,
    )

    private fun AuthorSearchManga.toSnapshot(sourceId: Long): CreatorSourceWorkSnapshot = manga.toSnapshot(
        sourceId = sourceId,
        structuredMatches = matchedCreators.map { match ->
            CreatorStructuredIdentityMatch(
                displayName = match.displayName,
                role = match.role.toCreatorRole(),
                evidence = match.evidence,
            )
        },
    )

    private fun SManga.toSnapshot(
        sourceId: Long,
        stableUrlFallback: String? = null,
        structuredMatches: List<CreatorStructuredIdentityMatch> = emptyList(),
    ): CreatorSourceWorkSnapshot = CreatorSourceWorkSnapshot(
        key = SourceWorkNaturalKey(
            sourceId = sourceId,
            stableSourceUrl = CreatorSourceWorkKey.stableUrl(
                url = runCatching { url }.getOrDefault(stableUrlFallback.orEmpty()),
                title = title,
                author = author,
                artist = artist,
            ),
        ),
        title = title,
        authorText = author,
        artistText = artist,
        thumbnailUrl = thumbnail_url,
        structuredCreatorMatches = structuredMatches,
    )

    private fun AuthorSearchManga.key(sourceId: Long): SourceWorkNaturalKey = manga.toSnapshot(sourceId).key

    private fun AuthorSearchManga.toCachedHit(): CachedSearchHit = CachedSearchHit(
        manga = manga.copy(),
        structuredMatches = matchedCreators.map { match ->
            CreatorStructuredIdentityMatch(match.displayName, match.role.toCreatorRole(), match.evidence)
        },
        readingLanguageTag = readingLanguageTag,
        originalLanguageTag = originalLanguageTag,
    )

    private fun AuthorSearchCreatorRole.toCreatorRole(): CreatorRole = when (this) {
        AuthorSearchCreatorRole.AUTHOR -> CreatorRole.AUTHOR
        AuthorSearchCreatorRole.ARTIST -> CreatorRole.ARTIST
        AuthorSearchCreatorRole.BOTH -> CreatorRole.BOTH
        AuthorSearchCreatorRole.UNKNOWN -> CreatorRole.UNKNOWN
    }

    private data class SearchPage(
        val items: List<AuthorSearchManga>,
        val hasNextPage: Boolean,
    )

    private data class CachedSearchHit(
        val manga: SManga,
        val structuredMatches: List<CreatorStructuredIdentityMatch>,
        val readingLanguageTag: String?,
        val originalLanguageTag: String?,
    )
}

private fun Throwable.toCreatorSourceFailure(): CreatorSourceFailure = when (val error = toSourceAppError()) {
    is AppError.Authentication, is AppError.Challenge -> CreatorSourceFailure.AuthenticationRequired(null)
    is AppError.RateLimited -> CreatorSourceFailure.RateLimited(error.retryAfterSeconds?.times(1_000))
    is AppError.Server -> CreatorSourceFailure.Http(error.statusCode)
    is AppError.Network -> CreatorSourceFailure.Network(error.cause?.message)
    is AppError.MalformedData -> CreatorSourceFailure.MalformedResponse(error.cause?.message)
    AppError.Cancelled -> throw CancellationException("Source call cancelled", error.cause)
    else -> CreatorSourceFailure.Network(error.cause?.message)
}
