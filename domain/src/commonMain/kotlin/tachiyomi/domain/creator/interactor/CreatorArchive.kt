package tachiyomi.domain.creator.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.creator.model.ArchiveDiscovery
import tachiyomi.domain.creator.model.ArchiveLanguageSubject
import tachiyomi.domain.creator.model.CanonicalWork
import tachiyomi.domain.creator.model.CreatorWorkArchive
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.SourceCheckpoint
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.WorkDecisionState
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.service.ChapterVariantRecord

/** UI-safe author archive boundary; repository composition remains outside presentation code. */
class CreatorArchive(
    private val creators: CreatorRepository,
    private val archive: CreatorArchiveRepository,
) {
    fun observe(creatorId: Long): Flow<CreatorWorkArchive> = archive.observeCreatorWorkArchive(creatorId)

    fun observeCheckpoints(creatorId: Long): Flow<List<SourceCheckpoint>> = archive.observeSourceCheckpoints(creatorId)

    fun observeUnread(limit: Long): Flow<List<ArchiveDiscovery>> = archive.observeUnreadDiscoveries(limit)

    suspend fun get(creatorId: Long): CreatorWorkArchive = archive.getCreatorWorkArchive(creatorId)

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

    suspend fun setLanguage(subject: ArchiveLanguageSubject, dimension: LanguageDimension, tag: String, now: Long) =
        archive.setManualLanguage(subject, dimension, tag, now)

    suspend fun withdrawLanguage(subject: ArchiveLanguageSubject, dimension: LanguageDimension, now: Long) =
        archive.withdrawManualLanguage(subject, dimension, now)

    suspend fun getChapterVariants(sourceWork: SourceWorkNaturalKey): List<ChapterVariantRecord> =
        archive.getChapterVariants(sourceWork)

    suspend fun replaceChapterVariants(sourceWork: SourceWorkNaturalKey, variants: List<ChapterVariantRecord>, now: Long) =
        archive.replaceChapterVariants(sourceWork, variants, now)
}
