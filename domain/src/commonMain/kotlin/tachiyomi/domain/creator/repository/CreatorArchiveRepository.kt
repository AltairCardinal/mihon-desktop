package tachiyomi.domain.creator.repository

import tachiyomi.domain.creator.model.ArchiveAppendOutcome
import tachiyomi.domain.creator.model.ArchiveLanguageSubject
import tachiyomi.domain.creator.model.ArchiveUpsertOutcome
import tachiyomi.domain.creator.model.CreatorIdentityOption
import tachiyomi.domain.creator.model.CreatorLibraryIndexEntry
import tachiyomi.domain.creator.model.CreatorMention
import tachiyomi.domain.creator.model.CreatorRelationOrigin
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.DecisionActor
import tachiyomi.domain.creator.model.LanguageAssertionContract
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.WorkDecisionContract
import tachiyomi.domain.manga.model.Manga

/**
 * Typed mutation boundary for the v2 author archive.
 *
 * Legacy candidate-shaped APIs remain temporarily available through [CreatorRepository], but new
 * indexing and discovery code must use this natural-key contract.
 */
interface CreatorLibraryIndexWriter {
    suspend fun indexLibraryMangaBatch(entries: List<CreatorLibraryIndexEntry>)

    suspend fun indexLibraryManga(manga: Manga, mentions: List<CreatorMention>) {
        indexLibraryMangaBatch(listOf(CreatorLibraryIndexEntry(manga, mentions)))
    }

    suspend fun removeLibraryMangaIndex(mangaId: Long)

    suspend fun removeStaleLibraryMangaIndexes()
}

object NoopCreatorLibraryIndexWriter : CreatorLibraryIndexWriter {
    override suspend fun indexLibraryMangaBatch(entries: List<CreatorLibraryIndexEntry>) = Unit

    override suspend fun removeLibraryMangaIndex(mangaId: Long) = Unit

    override suspend fun removeStaleLibraryMangaIndexes() = Unit
}

interface CreatorLibraryMangaSource {
    suspend fun countLibraryMangaForCreatorIndex(): Long

    suspend fun getLibraryMangaForCreatorIndex(afterId: Long, limit: Long): List<Manga>
}

interface CreatorArchiveRepository : CreatorLibraryIndexWriter {

    suspend fun getCreatorIdentityOptions(
        mangaId: Long,
        mention: CreatorMention,
    ): List<CreatorIdentityOption>

    suspend fun bindMangaCreatorIdentity(
        manga: Manga,
        mention: CreatorMention,
        creatorId: Long,
    )

    suspend fun createAndBindMangaCreatorIdentity(
        manga: Manga,
        mention: CreatorMention,
    ): Long

    suspend fun addManualCreatorAlias(creatorId: Long, alias: String)

    suspend fun getManualCreatorAliases(creatorId: Long): List<String>

    suspend fun removeManualCreatorAlias(creatorId: Long, alias: String)

    suspend fun mergeCreatorIdentities(sourceCreatorId: Long, targetCreatorId: Long)

    suspend fun splitCreatorIdentity(
        sourceCreatorId: Long,
        mangaIds: Set<Long>,
        newDisplayName: String,
        sourceWorks: Set<SourceWorkNaturalKey> = emptySet(),
    ): Long

    suspend fun upsertSourceWork(
        sourceId: Long,
        stableSourceUrl: String,
        mangaId: Long?,
        title: String,
        authorText: String?,
        artistText: String?,
        thumbnailUrl: String?,
        detailsFetchedAt: Long?,
    ): ArchiveUpsertOutcome<SourceWorkNaturalKey>

    suspend fun upsertSourceWorkCreator(
        sourceWork: SourceWorkNaturalKey,
        creatorId: Long,
        role: CreatorRole,
        order: Long,
        origin: CreatorRelationOrigin,
        verification: CreatorRelationVerification,
        sourceText: String?,
        confidence: Double,
        evidence: String,
    ): ArchiveUpsertOutcome<SourceWorkNaturalKey>

    suspend fun appendWorkDecision(
        sourceWork: SourceWorkNaturalKey,
        workId: Long,
        decision: WorkDecisionContract,
        algorithmVersion: String?,
        score: Double?,
        evidence: String,
        decidedAt: Long,
        idempotencyKey: String,
    ): ArchiveAppendOutcome<WorkDecisionContract>

    suspend fun appendLanguageAssertion(
        subject: ArchiveLanguageSubject,
        assertion: LanguageAssertionContract,
        actor: DecisionActor,
        evidencePayload: String,
        algorithmVersion: String?,
        assertedAt: Long,
        idempotencyKey: String,
    ): ArchiveAppendOutcome<LanguageAssertionContract>
}

/**
 * Readiness gate shared by repository calls and platform startup.
 *
 * Implementations must not report ready until the incremental legacy import transaction commits.
 * A failure is propagated so creator reads and mutations fail closed instead of racing stale v1 data.
 */
interface CreatorArchiveBootstrap {
    suspend fun awaitReady()
}

object ReadyCreatorArchiveBootstrap : CreatorArchiveBootstrap {
    override suspend fun awaitReady() = Unit
}
