package tachiyomi.domain.creator.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.creator.model.ArchiveDiscovery
import tachiyomi.domain.creator.model.ArchiveLanguageSubject
import tachiyomi.domain.creator.model.ArchiveUnreadWork
import tachiyomi.domain.creator.model.CanonicalWork
import tachiyomi.domain.creator.model.ChapterCatalogCompleteness
import tachiyomi.domain.creator.model.CreatorCardProjectionPage
import tachiyomi.domain.creator.model.CreatorWorkArchive
import tachiyomi.domain.creator.model.DueWatchSource
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.NewCanonicalWorkDecision
import tachiyomi.domain.creator.model.ReviewDisposition
import tachiyomi.domain.creator.model.SourceCheckpoint
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.WorkDecisionState
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.service.ChapterVariantRecord
import tachiyomi.domain.creator.service.WorkTitleNormalizer

/** UI-safe author archive boundary; repository composition remains outside presentation code. */
class CreatorArchive(
    private val creators: CreatorRepository,
    private val archive: CreatorArchiveRepository,
) {
    fun observe(creatorId: Long): Flow<CreatorWorkArchive> = archive.observeCreatorWorkArchive(creatorId)

    fun observeCheckpoints(creatorId: Long): Flow<List<SourceCheckpoint>> = archive.observeSourceCheckpoints(creatorId)

    fun observeUnread(limit: Long): Flow<List<ArchiveDiscovery>> = archive.observeUnreadDiscoveries(limit)

    fun observeUnreadWorks(limit: Long): Flow<List<ArchiveUnreadWork>> = archive.observeUnreadWorkDiscoveries(limit)

    suspend fun getUnreadWorks(limit: Long): List<ArchiveUnreadWork> = archive.getUnreadWorkDiscoveries(limit)

    fun observeDiscoveries(limit: Long): Flow<List<ArchiveDiscovery>> = archive.observeDiscoveries(limit)

    suspend fun markSeen(discoveryId: Long, now: Long) = archive.markDiscoverySeen(discoveryId, now)

    suspend fun markWorkSeen(sourceWork: SourceWorkNaturalKey, now: Long) = archive.markWorkSeen(sourceWork, now)

    suspend fun markPresentationGroupSeen(creatorId: Long, selectedSourceWork: SourceWorkNaturalKey, now: Long) =
        archive.markPresentationGroupSeen(creatorId, selectedSourceWork, now)

    suspend fun review(discoveryId: Long, disposition: ReviewDisposition, now: Long) =
        archive.setDiscoveryReview(discoveryId, disposition, now)

    suspend fun get(creatorId: Long): CreatorWorkArchive = archive.getCreatorWorkArchive(creatorId)

    suspend fun getCreatorCardProjectionPage(
        offset: Int,
        limit: Int,
        followedOnly: Boolean,
        preferredLanguages: Set<String> = emptySet(),
        customCoverExists: (Long) -> Boolean = { false },
        query: String = "",
        preferredDisplayScript: WorkTitleNormalizer.DisplayScript? = null,
    ): CreatorCardProjectionPage = archive.getCreatorCardProjectionPage(
        offset = offset,
        limit = limit,
        followedOnly = followedOnly,
        preferredLanguages = preferredLanguages,
        customCoverExists = customCoverExists,
        query = query,
        preferredDisplayScript = preferredDisplayScript,
    )

    suspend fun getPresentationExclusions(creatorRootId: Long): Set<SourceWorkNaturalKey> =
        archive.getPresentationExclusions(creatorRootId)

    suspend fun setPresentationExclusion(
        creatorRootId: Long,
        sourceWork: SourceWorkNaturalKey,
        excluded: Boolean,
        now: Long,
    ) = archive.setPresentationExclusion(creatorRootId, sourceWork, excluded, now)

    suspend fun importPresentationExclusions(
        entries: Map<Long, Set<SourceWorkNaturalKey>>,
    ): Map<Long, Set<SourceWorkNaturalKey>> =
        archive.importPresentationExclusions(entries)

    suspend fun getDueWatchSources(now: Long, limit: Long): List<DueWatchSource> =
        archive.getDueWatchSources(now, limit)

    suspend fun createWork(title: String, creatorId: Long, originalLanguage: String?): CanonicalWork =
        creators.createCanonicalWork(title, creatorId, originalLanguage)

    suspend fun decide(
        sourceWork: SourceWorkNaturalKey,
        workId: Long,
        state: WorkDecisionState,
        expectedDecidedAt: Long?,
        score: Double,
        evidence: String,
        decidedAt: Long,
        idempotencyKey: String,
    ) = archive.appendUserWorkDecisionIfCurrent(
        sourceWork,
        workId,
        state,
        expectedDecidedAt,
        score,
        evidence,
        decidedAt,
        idempotencyKey,
    )

    suspend fun createCanonicalWorkWithUserWorkDecisions(
        primaryTitle: String,
        creatorId: Long?,
        decisions: List<NewCanonicalWorkDecision>,
    ) = archive.createCanonicalWorkWithUserWorkDecisions(primaryTitle, creatorId, decisions)

    suspend fun setLanguage(subject: ArchiveLanguageSubject, dimension: LanguageDimension, tag: String, now: Long) =
        archive.setManualLanguage(subject, dimension, tag, now)

    suspend fun withdrawLanguage(subject: ArchiveLanguageSubject, dimension: LanguageDimension, now: Long) =
        archive.withdrawManualLanguage(subject, dimension, now)

    suspend fun getChapterVariants(sourceWork: SourceWorkNaturalKey): List<ChapterVariantRecord> =
        archive.getChapterVariants(sourceWork)

    suspend fun updateSourceWorkCatalog(
        sourceWork: SourceWorkNaturalKey,
        chapterCount: Long,
        completeness: ChapterCatalogCompleteness,
        latestChapterAt: Long?,
        observedAt: Long,
        mangaId: Long? = null,
    ) = archive.updateSourceWorkCatalog(sourceWork, chapterCount, completeness, latestChapterAt, observedAt, mangaId)

    suspend fun replaceChapterVariants(
        sourceWork: SourceWorkNaturalKey,
        variants: List<ChapterVariantRecord>,
        now: Long,
    ) = archive.replaceChapterVariants(sourceWork, variants, now)
}
