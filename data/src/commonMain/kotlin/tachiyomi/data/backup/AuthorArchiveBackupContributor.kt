package tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupAuthorArchiveSection
import eu.kanade.tachiyomi.data.backup.models.BackupAuthorBinding
import eu.kanade.tachiyomi.data.backup.models.BackupAuthorCanonicalWork
import eu.kanade.tachiyomi.data.backup.models.BackupAuthorDiscovery
import eu.kanade.tachiyomi.data.backup.models.BackupAuthorLanguageDecision
import eu.kanade.tachiyomi.data.backup.models.BackupAuthorSourceWork
import eu.kanade.tachiyomi.data.backup.models.BackupAuthorWatch
import eu.kanade.tachiyomi.data.backup.models.BackupAuthorWorkDecision
import eu.kanade.tachiyomi.data.backup.models.BackupCreatorAlias
import eu.kanade.tachiyomi.data.backup.models.BackupCreatorIdentity
import eu.kanade.tachiyomi.data.backup.models.BackupCreatorName
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.creator.ARCHIVE_DATE_ZONE
import tachiyomi.data.creator.frozenArchiveDate
import tachiyomi.data.creator.mergeCreatorIdentityGraph
import tachiyomi.data.creator.reconcileArchiveCanonicalVersion
import tachiyomi.domain.creator.model.ChapterCatalogCompleteness
import tachiyomi.domain.creator.model.CreatorArchiveLanguageTag
import tachiyomi.domain.creator.model.CreatorRelationOrigin
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.DiscoveryKind
import tachiyomi.domain.creator.model.DiscoveryReadState
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.ReviewDisposition
import tachiyomi.domain.creator.model.WorkDecisionState
import tachiyomi.domain.creator.service.CreatorNameNormalizer
import tachiyomi.domain.creator.service.CreatorSourceWorkKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val ARCHIVE_DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")

/**
 * Creates and restores the identity/alias/binding slice of backup field 107.
 *
 * Restore validates the complete section before opening one database transaction. Identity matching
 * preserves portable keys and converges occupied exact names through the shared identity graph merger.
 * Search-normalized aliases never establish identity equality.
 */
interface AuthorArchiveBackupContributor {
    suspend fun createSection(): BackupAuthorArchiveSection?

    suspend fun restoreSection(section: BackupAuthorArchiveSection)
}

class SqlDelightAuthorArchiveBackupContributor(
    private val handler: DatabaseHandler,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val awaitIdentityReady: suspend () -> Unit = {},
) : AuthorArchiveBackupContributor {

    override suspend fun createSection(): BackupAuthorArchiveSection? {
        awaitIdentityReady()
        return handler.await(inTransaction = true) {
            val names = author_archiveQueries.getArchiveIdentityNames().executeAsList().groupBy { it.creator_id }
            val aliases = author_archiveQueries.getArchiveAliasesForBackup().executeAsList()
                .groupBy { it.creator_portable_key }
            val bindings = author_archiveQueries.getArchiveSourceWorkBindingsForBackup().executeAsList()
                .groupBy { it.source_id to it.stable_source_url }
            val creators = author_archiveQueries.getArchiveCreatorsForBackup().executeAsList().map { creator ->
                val creatorId = author_archiveQueries.getArchiveCreatorIdByPortableKey(creator.portable_key)
                    .executeAsOne()
                BackupCreatorIdentity(
                    names = names[creatorId].orEmpty().map { BackupCreatorName(it.name_text, it.origin) },
                    portableKey = creator.portable_key,
                    displayName = creator.display_name,
                    normalizedName = creator.normalized_name,
                    sortName = creator.sort_name,
                    status = creator.status,
                    mergedIntoPortableKey = creator.merged_into_portable_key,
                    needsReview = creator.needs_review,
                    aliases = aliases[creator.portable_key].orEmpty().map { alias ->
                        BackupCreatorAlias(
                            rawAlias = alias.raw_alias,
                            normalizedAlias = alias.normalized_alias,
                            source = alias.source,
                            evidence = alias.evidence,
                            confidence = alias.confidence,
                            isManual = alias.is_manual,
                        )
                    },
                )
            }
            val sourceWorks = author_archiveQueries.getArchiveSourceWorksForBackup().executeAsList().map { work ->
                BackupAuthorSourceWork(
                    sourceId = work.source_id,
                    stableSourceUrl = CreatorSourceWorkKey.portableUrl(
                        work.stable_source_url,
                        work.title,
                        work.author_text,
                        work.artist_text,
                    ),
                    title = work.title,
                    authorText = work.author_text,
                    artistText = work.artist_text,
                    thumbnailUrl = work.thumbnail_url,
                    firstSeenAt = work.first_seen_at,
                    firstSeenDate = work.first_seen_date.takeIf(String::isNotBlank),
                    firstSeenZone = work.first_seen_zone,
                    chapterCount = work.catalog_chapter_count,
                    chapterCompleteness = work.chapter_count_state,
                    latestChapterAt = work.latest_chapter_at,
                    publishedDateAt = work.published_date_snapshot_at,
                    publishedDateBasis = work.published_date_snapshot_basis,
                    publishedDateReason = work.published_date_snapshot_reason,
                    bindings = bindings[work.source_id to work.stable_source_url].orEmpty().map { binding ->
                        BackupAuthorBinding(
                            creatorPortableKey = binding.creator_portable_key,
                            role = binding.role,
                            order = binding.creator_order,
                            origin = binding.origin,
                            verification = binding.verification,
                            sourceText = binding.source_text,
                            confidence = binding.confidence,
                            evidence = binding.evidence,
                        )
                    },
                )
            }.groupBy { it.sourceId to it.stableSourceUrl }
                .map { (_, versions) ->
                    mergeSourceWorkFacts(versions).copy(
                        bindings = versions.flatMap(BackupAuthorSourceWork::bindings)
                            .groupBy(BackupAuthorBinding::creatorPortableKey)
                            .values
                            .map { candidates ->
                                candidates.maxBy { relationOriginRank(it.origin) }.copy(
                                    role = candidates.map { it.role }.reduce(::unionRole),
                                )
                            },
                    )
                }
            val watchSources = author_archiveQueries.getArchiveWatchSourcesForBackup().executeAsList()
                .groupBy({ it.creator_portable_key }, { it.source_id })
            val watchLanguages = author_archiveQueries.getArchiveWatchLanguagesForBackup().executeAsList()
                .groupBy({ it.creator_portable_key }, { it.language_tag })
            val watches = author_archiveQueries.getArchiveWatchPoliciesForBackup().executeAsList().map { watch ->
                BackupAuthorWatch(
                    creatorPortableKey = watch.creator_portable_key,
                    enabled = watch.enabled,
                    periodMillis = watch.period_millis,
                    sourceIds = watchSources[watch.creator_portable_key].orEmpty(),
                    readingLanguageTags = watchLanguages[watch.creator_portable_key].orEmpty(),
                    includeProbable = watch.include_probable,
                    includeUnknown = watch.include_unknown,
                    notifyProbable = watch.notify_probable,
                    notifyUnknown = watch.notify_unknown,
                )
            }
            val discoveries = author_archiveQueries.getArchiveDiscoveriesForBackup().executeAsList().map { discovery ->
                BackupAuthorDiscovery(
                    creatorPortableKey = discovery.creator_portable_key,
                    sourceId = discovery.source_id,
                    stableSourceUrl = discovery.stable_source_url,
                    kind = discovery.kind,
                    reason = discovery.reason,
                    baselineGeneration = discovery.baseline_generation,
                    readState = discovery.read_state,
                    reviewDisposition = discovery.review_disposition,
                    firstDiscoveredAt = discovery.first_discovered_at,
                )
            }
            val canonicalWorks = author_archiveQueries.getArchiveCanonicalWorksForBackup().executeAsList().map { work ->
                BackupAuthorCanonicalWork(work.portable_key, work.primary_title)
            }
            val workDecisions = author_archiveQueries.getArchiveWorkDecisionsForBackup()
                .executeAsList().map { decision ->
                    BackupAuthorWorkDecision(
                        sourceId = decision.source_id,
                        stableSourceUrl = decision.stable_source_url,
                        workPortableKey = decision.portable_key,
                        state = decision.state,
                        score = decision.score,
                        evidence = decision.evidence,
                        decidedAt = decision.decided_at,
                    )
                }
            val languageDecisions = author_archiveQueries.getArchiveManualLanguageDecisionsForBackup().executeAsList()
                .map { decision ->
                    BackupAuthorLanguageDecision(
                        subjectType = decision.subject_type,
                        subjectKey = decision.subject_key,
                        dimension = decision.dimension,
                        languageTag = decision.language_tag,
                        withdrawn = decision.withdrawn,
                        assertedAt = decision.asserted_at,
                    )
                }
            BackupAuthorArchiveSection(
                version = BackupAuthorArchiveSection.CURRENT_VERSION,
                creators = creators,
                sourceWorks = sourceWorks,
                watches = watches,
                discoveries = discoveries,
                canonicalWorks = canonicalWorks,
                workDecisions = workDecisions,
                languageDecisions = languageDecisions,
            ).takeIf {
                it.creators.isNotEmpty() || it.sourceWorks.isNotEmpty() || it.watches.isNotEmpty() ||
                    it.discoveries.isNotEmpty() || it.canonicalWorks.isNotEmpty() || it.workDecisions.isNotEmpty() ||
                    it.languageDecisions.isNotEmpty()
            }
        }
    }

    override suspend fun restoreSection(section: BackupAuthorArchiveSection) {
        val now = clock()
        val validated = validate(section, now)
        awaitIdentityReady()
        handler.await(inTransaction = true) {
            val localPrimaries = author_archiveQueries.getArchiveCreatorsForBackup().executeAsList()
                .filter { it.status == STATUS_ACTIVE }.associate { creator ->
                    val id = author_archiveQueries.getArchiveCreatorIdByPortableKey(creator.portable_key).executeAsOne()
                    id to creator.display_name
                }.filter { (id, name) ->
                    author_archiveQueries.getArchiveCreatorIdByExactName(name).executeAsOneOrNull() == id
                }
            validated.creators.forEach { creator ->
                author_archiveQueries.upsertArchiveCreatorFromBackup(
                    portableKey = creator.portableKey,
                    displayName = creator.displayName,
                    normalizedName = creator.normalizedName,
                    sortName = creator.sortName,
                    needsReview = creator.needsReview,
                    createdAt = now,
                    lastModifiedAt = now,
                )
            }
            val creatorIds = validated.creators.associate { creator ->
                creator.portableKey to author_archiveQueries
                    .getArchiveCreatorIdByPortableKey(creator.portableKey)
                    .executeAsOne()
            }.toMutableMap()
            validated.creators.forEach { creator ->
                var creatorId = resolveActiveCreatorRoot(creatorIds.getValue(creator.portableKey))
                creatorIds[creator.portableKey] = creatorId
                creator.names.forEach { acceptedName ->
                    val name = acceptedName.text
                    val owner = author_archiveQueries.getArchiveCreatorIdByExactName(name).executeAsOneOrNull()
                    if (owner != null && owner != creatorId) {
                        val target = preferredRestoreRoot(creatorId, owner, localPrimaries.keys)
                        val source = if (target == creatorId) owner else creatorId
                        mergeCreatorIdentityGraph(source, target, now)
                        creatorIds.entries.filter { it.value == source }.forEach { it.setValue(target) }
                        creatorId = target
                        creatorIds[creator.portableKey] = target
                    }
                    author_archiveQueries.registerArchiveIdentityNameIfAbsent(
                        name,
                        creatorId,
                        acceptedName.origin,
                        now,
                        now,
                    )
                }
                creator.aliases.forEach { alias ->
                    author_archiveQueries.upsertArchiveAlias(
                        creatorId = creatorId,
                        rawAlias = alias.rawAlias,
                        normalizedAlias = alias.normalizedAlias,
                        source = alias.source,
                        evidence = alias.evidence,
                        confidence = alias.confidence,
                        isManual = alias.isManual,
                        createdAt = now,
                        lastModifiedAt = now,
                    )
                }
            }
            validated.creators.filter { it.status == STATUS_MERGED }.forEach { creator ->
                var targetId = resolveActiveCreatorRoot(
                    creatorIds.getValue(checkNotNull(creator.mergedIntoPortableKey)),
                )
                var sourceId = resolveActiveCreatorRoot(creatorIds.getValue(creator.portableKey))
                if (preferredRestoreRoot(sourceId, targetId, localPrimaries.keys) == sourceId) {
                    val swap = targetId
                    targetId = sourceId
                    sourceId = swap
                }
                if (sourceId != targetId) {
                    mergeCreatorIdentityGraph(sourceId, targetId, now)
                    creatorIds.entries.filter { it.value == sourceId }.forEach { it.setValue(targetId) }
                }
            }
            creatorIds.keys.toList().forEach { key ->
                creatorIds[key] = resolveActiveCreatorRoot(creatorIds.getValue(key))
            }
            validated.sourceWorks.forEach { work ->
                restoreSourceWork(work, creatorIds, now)
            }
            validated.canonicalWorks.forEach { work ->
                author_archiveQueries.upsertArchiveCanonicalWorkFromBackup(
                    portableKey = work.portableKey,
                    primaryTitle = work.primaryTitle,
                    normalizedTitle = CreatorNameNormalizer.normalize(work.primaryTitle),
                    createdAt = now,
                    lastModifiedAt = now,
                )
            }
            validated.workDecisions.forEach { decision ->
                val restoreKey = listOf(
                    "restore",
                    decision.sourceId,
                    decision.stableSourceUrl,
                    decision.workPortableKey,
                    decision.state,
                ).joinToString(":")
                val sourceWorkId = author_archiveQueries.getArchiveSourceWorkByKey(
                    decision.sourceId,
                    decision.stableSourceUrl,
                ).executeAsOne()._id
                val workId = author_archiveQueries.getArchiveCanonicalWorkIdByPortableKey(
                    decision.workPortableKey,
                ).executeAsOne()
                author_archiveQueries.upsertArchiveWorkDecision(
                    sourceWorkId = sourceWorkId,
                    workId = workId,
                    state = decision.state,
                    actor = "RESTORE",
                    explicit = true,
                    algorithmVersion = null,
                    score = decision.score,
                    evidence = decision.evidence,
                    decidedAt = decision.decidedAt,
                    idempotencyKey = restoreKey,
                )
                reconcileArchiveCanonicalVersion(sourceWorkId)
            }
            validated.languageDecisions.forEach { decision ->
                val subjectKey = if (decision.subjectType == "CREATOR") {
                    val rootId = creatorIds.getValue(decision.subjectKey.removePrefix("creator:"))
                    val rootKey = author_archiveQueries.getArchiveCreatorIdentityRecord(rootId)
                        .executeAsOne().portable_key
                    "creator:$rootKey"
                } else {
                    decision.subjectKey
                }
                author_archiveQueries.upsertArchiveLanguageAssertion(
                    subjectType = decision.subjectType,
                    subjectKey = subjectKey,
                    dimension = decision.dimension,
                    languageTag = decision.languageTag,
                    confidence = 1.0,
                    evidenceKind = "MANUAL",
                    evidencePayload = "restored-manual-language",
                    actor = "RESTORE",
                    algorithmVersion = null,
                    withdrawn = decision.withdrawn,
                    assertedAt = decision.assertedAt,
                    idempotencyKey = buildString {
                        append("restore-language:")
                        append(decision.subjectType)
                        append(':')
                        append(subjectKey)
                        append(':')
                        append(decision.dimension)
                        append(':')
                        append(decision.assertedAt)
                    },
                )
            }
            validated.watches.groupBy { creatorIds.getValue(it.creatorPortableKey) }.forEach { (id, watches) ->
                restoreWatch(
                    watches.first().copy(
                        enabled = watches.any { it.enabled },
                        periodMillis = watches.minOf { it.periodMillis },
                        sourceIds = watches.flatMap { it.sourceIds }.distinct(),
                        readingLanguageTags = watches.flatMap { it.readingLanguageTags }.distinct(),
                        includeProbable = watches.any { it.includeProbable },
                        includeUnknown = watches.any { it.includeUnknown },
                        notifyProbable = watches.any { it.notifyProbable },
                        notifyUnknown = watches.any { it.notifyUnknown },
                    ),
                    id,
                    now,
                )
            }
            validated.discoveries.groupBy {
                Triple(creatorIds.getValue(it.creatorPortableKey), it.sourceId, it.stableSourceUrl)
            }.values.map(::mergeRestoredDiscoveries).forEach { discovery ->
                author_archiveQueries.restoreArchiveDiscoveryState(
                    kind = discovery.kind,
                    reason = discovery.reason,
                    baselineGeneration = discovery.baselineGeneration,
                    readState = discovery.readState,
                    reviewDisposition = discovery.reviewDisposition,
                    firstDiscoveredAt = discovery.firstDiscoveredAt,
                    now = now,
                    sourceId = discovery.sourceId,
                    stableSourceUrl = discovery.stableSourceUrl,
                    creatorPortableKey = author_archiveQueries.getArchiveCreatorIdentityRecord(
                        creatorIds.getValue(discovery.creatorPortableKey),
                    ).executeAsOne().portable_key,
                )
            }
        }
    }

    private fun Database.preferredRestoreRoot(source: Long, target: Long, localPrimaries: Set<Long>): Long =
        listOf(source, target).filter { it in localPrimaries }.minByOrNull {
            author_archiveQueries.getArchiveCreatorIdentityRecord(it).executeAsOne().portable_key
        } ?: target

    /** Same precedence as mergeArchiveDuplicateDiscoveries; only merges collisions inside this backup. */
    private fun mergeRestoredDiscoveries(discoveries: List<BackupAuthorDiscovery>): BackupAuthorDiscovery {
        if (discoveries.size == 1) return discoveries.single()
        return discoveries.first().copy(
            kind = if (discoveries.any { it.kind == "NEW_WORK_CANDIDATE" }) {
                "NEW_WORK_CANDIDATE"
            } else {
                "NEW_SOURCE_VERSION"
            },
            reason = discoveries.map { it.reason }.distinct().sorted().joinToString("\n"),
            baselineGeneration = discoveries.maxOf { it.baselineGeneration },
            readState = if (discoveries.any { it.readState == "UNSEEN" }) "UNSEEN" else "SEEN",
            reviewDisposition = when {
                discoveries.any { it.reviewDisposition == "PENDING" } -> "PENDING"
                discoveries.any { it.reviewDisposition == "ACCEPTED" } -> "ACCEPTED"
                else -> "IGNORED"
            },
            firstDiscoveredAt = discoveries.minOf { it.firstDiscoveredAt },
        )
    }

    private fun Database.resolveActiveCreatorRoot(creatorId: Long): Long =
        author_archiveQueries.resolveArchiveCreatorRootId(creatorId).executeAsOneOrNull()
            ?: error("Creator identity redirect does not terminate at an active root: $creatorId")

    private fun Database.restoreWatch(watch: BackupAuthorWatch, creatorId: Long, now: Long) {
        author_archiveQueries.upsertArchiveWatchPolicyCommand(
            creatorId = creatorId,
            enabled = watch.enabled,
            periodMillis = watch.periodMillis,
            now = now,
        )
        val watchId = author_archiveQueries.getArchiveWatchIdByCreator(creatorId).executeAsOne()
        val existingSources = author_archiveQueries.getArchiveWatchSourceIds(watchId).executeAsList().toSet()
        (existingSources - watch.sourceIds.toSet()).forEach { sourceId ->
            author_archiveQueries.deleteArchiveWatchSource(watchId, sourceId)
            author_archiveQueries.deleteArchiveSourceCheckpoint(watchId, sourceId)
        }
        watch.sourceIds.forEach { sourceId ->
            author_archiveQueries.upsertArchiveWatchSourceCommand(watchId, sourceId, now, now)
        }
        author_archiveQueries.upsertArchiveWatchPolicy(watchId, now, now)
        author_archiveQueries.updateArchiveWatchResultPolicyCommand(
            includeProbable = watch.includeProbable,
            includeUnknown = watch.includeUnknown,
            notifyProbable = watch.notifyProbable,
            notifyUnknown = watch.notifyUnknown,
            now = now,
            watchId = watchId,
        )
        val policyId = author_archiveQueries.getArchiveWatchPolicyId(watchId).executeAsOne()
        author_archiveQueries.replaceArchiveWatchLanguages(policyId)
        watch.readingLanguageTags.forEach { language ->
            author_archiveQueries.insertArchiveWatchLanguage(policyId, language)
        }
    }

    private fun Database.restoreSourceWork(
        work: BackupAuthorSourceWork,
        creatorIds: Map<String, Long>,
        now: Long,
    ) {
        val mangaId = if (CreatorSourceWorkKey.isBlankFallback(work.stableSourceUrl)) {
            author_archiveQueries.getArchiveBlankUrlMangaSeeds(work.sourceId).executeAsList()
                .firstOrNull { manga ->
                    CreatorSourceWorkKey.stableUrl(manga.url, manga.title, manga.author, manga.artist) ==
                        work.stableSourceUrl
                }
                ?._id
        } else {
            author_archiveQueries
                .getArchiveMangaIdByNaturalKey(work.sourceId, work.stableSourceUrl)
                .executeAsOneOrNull()
        }
        val existing = author_archiveQueries
            .getArchiveSourceWorkByKey(work.sourceId, work.stableSourceUrl)
            .executeAsOneOrNull()
            ?: mangaId?.let { id ->
                author_archiveQueries.getArchiveSourceWorksByManga(id).executeAsList()
                    .firstOrNull { sourceWork ->
                        sourceWork.source_id == work.sourceId &&
                            sourceWork.stable_source_url.startsWith("legacy-manga:")
                    }
            }
        val sourceWorkId = if (existing == null) {
            val firstSeenAt = work.firstSeenAt ?: now
            author_archiveQueries.insertArchiveSourceWork(
                sourceId = work.sourceId,
                stableSourceUrl = work.stableSourceUrl,
                mangaId = mangaId,
                title = work.title,
                normalizedTitle = CreatorNameNormalizer.normalize(work.title),
                authorText = work.authorText,
                artistText = work.artistText,
                thumbnailUrl = work.thumbnailUrl,
                firstSeenAt = firstSeenAt,
                firstSeenDate = work.firstSeenDate ?: frozenArchiveDate(firstSeenAt),
                firstSeenZone = work.firstSeenZone?.takeIf(String::isNotBlank) ?: ARCHIVE_DATE_ZONE,
                lastSeenAt = now,
                detailsFetchedAt = null,
                chapterCountState = work.chapterCompleteness ?: ChapterCatalogCompleteness.UNKNOWN.name,
                catalogChapterCount = work.chapterCount ?: 0L,
                latestChapterAt = work.latestChapterAt,
                legacyReviewSnapshot = null,
            )
            author_archiveQueries.selectArchiveLastInsertedRowId().executeAsOne()
        } else {
            author_archiveQueries.updateArchiveSourceWorkMetadata(
                mangaId = mangaId,
                title = work.title,
                normalizedTitle = CreatorNameNormalizer.normalize(work.title),
                authorText = work.authorText,
                artistText = work.artistText,
                thumbnailUrl = work.thumbnailUrl,
                lastSeenAt = now,
                detailsFetchedAt = null,
                id = existing._id,
            )
            existing._id
        }
        if (existing != null) {
            val firstSeenAt = work.firstSeenAt ?: existing.first_seen_at
            author_archiveQueries.restoreArchiveSourceWorkFirstSeen(
                firstSeenAt = firstSeenAt,
                firstSeenDate = work.firstSeenDate ?: frozenArchiveDate(firstSeenAt),
                firstSeenZone = work.firstSeenZone?.takeIf(String::isNotBlank) ?: ARCHIVE_DATE_ZONE,
                id = existing._id,
            )
            if (work.chapterCompleteness != null || work.chapterCount != null || work.latestChapterAt != null) {
                val chapterCountState = work.chapterCompleteness ?: existing.chapter_count_state
                val catalogChapterCount = work.chapterCount ?: existing.catalog_chapter_count
                val latestChapterAt = work.latestChapterAt ?: existing.latest_chapter_at
                author_archiveQueries.updateArchiveSourceWorkCatalog(
                    chapterCountState = chapterCountState,
                    catalogChapterCount = catalogChapterCount,
                    latestChapterAt = latestChapterAt,
                    lastSeenAt = now,
                    sourceId = work.sourceId,
                    stableSourceUrl = work.stableSourceUrl,
                    mangaId = existing.manga_id,
                )
            }
        }
        work.publishedDateAt?.let { publishedDateAt ->
            author_archiveQueries.restoreWorkPublicationDate(
                publishedDateAt = publishedDateAt,
                basis = work.publishedDateBasis,
                reason = work.publishedDateReason,
                sourceWorkId = sourceWorkId,
            )
        }
        work.bindings.forEach { binding ->
            val creatorId = creatorIds.getValue(binding.creatorPortableKey)
            val previousRole = author_archiveQueries.getArchiveSourceWorkCreator(sourceWorkId, creatorId)
                .executeAsOneOrNull()?.role
            author_archiveQueries.upsertArchiveSourceWorkCreator(
                sourceWorkId = sourceWorkId,
                creatorId = creatorId,
                role = binding.role,
                creatorOrder = binding.order,
                origin = binding.origin,
                verification = binding.verification,
                sourceText = binding.sourceText,
                confidence = binding.confidence,
                evidence = binding.evidence,
                createdAt = now,
                lastModifiedAt = now,
            )
            previousRole?.let { author_archiveQueries.unionArchiveSourceWorkCreatorRole(it, sourceWorkId, creatorId) }
            if (mangaId != null) {
                val previousMangaRole = author_archiveQueries.getArchiveMangaLink(mangaId, creatorId)
                    .executeAsOneOrNull()?.role
                author_archiveQueries.upsertArchiveMangaLink(
                    mangaId = mangaId,
                    creatorId = creatorId,
                    role = binding.role,
                    creatorOrder = binding.order,
                    origin = binding.origin,
                    sourceText = binding.sourceText,
                    confidence = binding.confidence,
                    evidence = binding.evidence,
                    createdAt = now,
                    lastModifiedAt = now,
                )
                previousMangaRole?.let { author_archiveQueries.unionArchiveMangaLinkRole(it, mangaId, creatorId) }
            }
        }
    }

    private fun unionRole(left: String, right: String): String = when {
        left == right || right == "UNKNOWN" -> left
        left == "UNKNOWN" -> right
        else -> "BOTH"
    }

    private fun mergeSourceWorkFacts(versions: List<BackupAuthorSourceWork>): BackupAuthorSourceWork {
        val first = versions.first()
        val firstSeenAt = versions.mapNotNull { it.firstSeenAt?.takeIf { value -> value > 0L } }.minOrNull()
        val firstSeenRecord = firstSeenAt?.let { value -> versions.firstOrNull { it.firstSeenAt == value } }
        val completeness = versions.mapNotNull { it.chapterCompleteness }
            .maxByOrNull { chapterCompletenessRank(it) }
        val chapterCount = completeness?.let { state ->
            versions.filter { it.chapterCompleteness == state }
                .mapNotNull { it.chapterCount }
                .maxOrNull()
        } ?: versions.mapNotNull { it.chapterCount }.maxOrNull()
        return first.copy(
            firstSeenAt = firstSeenAt ?: first.firstSeenAt,
            firstSeenDate = if (firstSeenRecord != null) firstSeenRecord.firstSeenDate else first.firstSeenDate,
            firstSeenZone = if (firstSeenRecord != null) firstSeenRecord.firstSeenZone else first.firstSeenZone,
            chapterCount = chapterCount,
            chapterCompleteness = completeness,
            latestChapterAt = versions.mapNotNull { it.latestChapterAt }.maxOrNull(),
            publishedDateAt = versions.mapNotNull { it.publishedDateAt }.minOrNull(),
            publishedDateBasis = versions.filter { it.publishedDateAt != null }
                .minByOrNull { it.publishedDateAt!! }?.publishedDateBasis,
            publishedDateReason = versions.filter { it.publishedDateAt != null }
                .minByOrNull { it.publishedDateAt!! }?.publishedDateReason,
        )
    }

    private fun chapterCompletenessRank(value: String): Int = when (value) {
        ChapterCatalogCompleteness.COMPLETE.name -> 2
        ChapterCatalogCompleteness.PARTIAL.name -> 1
        ChapterCatalogCompleteness.UNKNOWN.name -> 0
        else -> -1
    }

    private fun validate(section: BackupAuthorArchiveSection, now: Long? = null): BackupAuthorArchiveSection {
        require(section.version in 1..BackupAuthorArchiveSection.CURRENT_VERSION) {
            "Unsupported author archive backup version: ${section.version}"
        }
        val portableUrls = section.sourceWorks.associate { work ->
            (work.sourceId to work.stableSourceUrl) to CreatorSourceWorkKey.portableUrl(
                work.stableSourceUrl,
                work.title,
                work.authorText,
                work.artistText,
            )
        }
        val normalizedSection = section.copy(
            creators = section.creators.map { creator ->
                if (section.version >= 5) {
                    creator
                } else {
                    creator.copy(
                        names = (listOf(creator.displayName) + creator.aliases.map { it.rawAlias })
                            .map(String::trim).filter(String::isNotBlank).distinct()
                            .map { BackupCreatorName(it, "RESTORE") },
                    )
                }
            },
            sourceWorks = section.sourceWorks.map { work ->
                work.copy(
                    stableSourceUrl = CreatorSourceWorkKey.portableUrl(
                        work.stableSourceUrl,
                        work.title,
                        work.authorText,
                        work.artistText,
                    ),
                )
            },
            discoveries = section.discoveries.map { discovery ->
                discovery.copy(
                    stableSourceUrl = portableUrls[discovery.sourceId to discovery.stableSourceUrl]
                        ?: discovery.stableSourceUrl.trim(),
                )
            },
            workDecisions = section.workDecisions.map { decision ->
                decision.copy(
                    stableSourceUrl = portableUrls[decision.sourceId to decision.stableSourceUrl]
                        ?: decision.stableSourceUrl.trim(),
                )
            },
        )
        val creators = normalizedSection.creators.associateBy(BackupCreatorIdentity::portableKey)
        require(creators.size == normalizedSection.creators.size) { "Duplicate creator portable key" }
        normalizedSection.creators.forEach { creator ->
            require(creator.portableKey.isNotBlank()) { "Creator portable key must not be blank" }
            require(creator.displayName.isNotBlank()) { "Creator display name must not be blank" }
            require(creator.normalizedName.isNotBlank()) { "Creator normalized name must not be blank" }
            require(creator.status == STATUS_ACTIVE || creator.status == STATUS_MERGED) {
                "Unsupported creator status: ${creator.status}"
            }
            require((creator.status == STATUS_MERGED) == (creator.mergedIntoPortableKey != null)) {
                "Creator merge redirect is inconsistent: ${creator.portableKey}"
            }
            creator.mergedIntoPortableKey?.let { target ->
                require(target != creator.portableKey && target in creators) {
                    "Invalid creator merge target: ${creator.portableKey} -> $target"
                }
            }
            require(creator.aliases.distinctBy(BackupCreatorAlias::normalizedAlias).size == creator.aliases.size) {
                "Duplicate normalized alias for creator ${creator.portableKey}"
            }
            creator.aliases.forEach { alias ->
                require(alias.rawAlias.isNotBlank() && alias.normalizedAlias.isNotBlank()) {
                    "Creator alias must not be blank"
                }
                require(alias.confidence in 0.0..1.0) { "Creator alias confidence is invalid" }
            }
        }
        validateRedirects(creators)
        if (section.version >= 5) {
            val names = mutableSetOf<String>()
            normalizedSection.creators.forEach { creator ->
                require(
                    creator.names.all {
                        it.text.isNotBlank() && it.text == it.text.trim() && it.origin.isNotBlank()
                    },
                ) {
                    "Invalid exact creator name"
                }
                require(creator.names.all { names.add(it.text) }) { "Duplicate exact creator name ownership" }
                require(creator.status != STATUS_ACTIVE || creator.names.any { it.text == creator.displayName }) {
                    "Creator primary name is missing from exact names"
                }
                require(creator.status != STATUS_MERGED || creator.names.isEmpty()) {
                    "Merged creator cannot own exact names"
                }
            }
        }

        val naturalKeys = normalizedSection.sourceWorks.map { it.sourceId to it.stableSourceUrl }
        require(naturalKeys.distinct().size == naturalKeys.size) { "Duplicate source work natural key" }
        normalizedSection.sourceWorks.forEach { work ->
            require(work.stableSourceUrl.isNotBlank()) { "Source work URL must not be blank" }
            require(work.title.isNotBlank()) { "Source work title must not be blank" }
            require(work.firstSeenAt == null || work.firstSeenAt > 0L) {
                "Source work first-seen time must be positive"
            }
            val firstSeenZone = work.firstSeenZone?.let { zone ->
                runCatching { ZoneId.of(zone) }.getOrNull()
            }
            require(work.firstSeenZone == null || firstSeenZone != null) {
                "Source work first-seen zone is invalid"
            }
            var parsedFirstSeenDate: LocalDate? = null
            work.firstSeenDate?.let { date ->
                require(ARCHIVE_DATE_PATTERN.matches(date)) {
                    "Source work first-seen date must use YYYY-MM-DD"
                }
                parsedFirstSeenDate =
                    runCatching { LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
                require(parsedFirstSeenDate != null) { "Source work first-seen date is invalid" }
                now?.let { observedAt ->
                    require(
                        !parsedFirstSeenDate!!.isAfter(
                            Instant.ofEpochMilli(observedAt)
                                .atZone(firstSeenZone ?: ZoneOffset.UTC)
                                .toLocalDate(),
                        ),
                    ) {
                        "Source work first-seen date must not be in the future"
                    }
                }
            }
            if (parsedFirstSeenDate != null && work.firstSeenAt != null) {
                require(
                    parsedFirstSeenDate == Instant.ofEpochMilli(work.firstSeenAt)
                        .atZone(firstSeenZone ?: ZoneOffset.UTC)
                        .toLocalDate(),
                ) {
                    "Source work first-seen date does not match its timestamp"
                }
            }
            now?.let { observedAt ->
                require(work.firstSeenAt == null || work.firstSeenAt <= observedAt) {
                    "Source work first-seen time must not be in the future"
                }
            }
            require(work.firstSeenZone == null || work.firstSeenZone.isNotBlank()) {
                "Source work first-seen zone must not be blank"
            }
            require(work.chapterCount == null || work.chapterCount >= 0L) {
                "Source work chapter count must not be negative"
            }
            work.chapterCompleteness?.let { enumValueOf<ChapterCatalogCompleteness>(it) }
            require(work.latestChapterAt == null || work.latestChapterAt > 0L) {
                "Source work latest chapter time must be positive"
            }
            require(work.publishedDateAt == null || work.publishedDateAt > 0L) {
                "Source work publication date must be positive"
            }
            require(
                work.publishedDateAt != null || (work.publishedDateBasis == null && work.publishedDateReason == null),
            ) {
                "Source work publication evidence requires a date"
            }
            require(work.bindings.distinctBy(BackupAuthorBinding::creatorPortableKey).size == work.bindings.size) {
                "Duplicate creator binding for source work"
            }
            work.bindings.forEach { binding ->
                require(binding.creatorPortableKey in creators) { "Binding references an unknown creator" }
                enumValueOf<CreatorRole>(binding.role)
                enumValueOf<CreatorRelationOrigin>(binding.origin)
                enumValueOf<CreatorRelationVerification>(binding.verification)
                require(binding.order >= 0) { "Creator binding order must not be negative" }
                require(binding.confidence in 0.0..1.0) { "Creator binding confidence is invalid" }
                require(binding.evidence.isNotBlank()) { "Creator binding evidence must not be blank" }
            }
        }
        require(
            normalizedSection.watches.distinctBy(BackupAuthorWatch::creatorPortableKey).size ==
                normalizedSection.watches.size,
        ) {
            "Duplicate creator watch"
        }
        normalizedSection.watches.forEach { watch ->
            require(watch.creatorPortableKey in creators) { "Watch references an unknown creator" }
            require(watch.periodMillis > 0) { "Watch period must be positive" }
            require(watch.sourceIds.all { it >= 0 } && watch.sourceIds.distinct().size == watch.sourceIds.size) {
                "Watch source IDs are invalid"
            }
            require(watch.readingLanguageTags.all { CreatorArchiveLanguageTag.normalize(it) == it && it != "und" }) {
                "Watch language tags must be normalized"
            }
            require(watch.readingLanguageTags.distinct().size == watch.readingLanguageTags.size) {
                "Duplicate watch language tag"
            }
        }
        val watchCreators = normalizedSection.watches.map(BackupAuthorWatch::creatorPortableKey).toSet()
        val sourceWorkKeys = normalizedSection.sourceWorks.map { it.sourceId to it.stableSourceUrl }.toSet()
        require(
            normalizedSection.discoveries.distinctBy {
                Triple(it.creatorPortableKey, it.sourceId, it.stableSourceUrl)
            }.size == normalizedSection.discoveries.size,
        ) { "Duplicate author discovery" }
        normalizedSection.discoveries.forEach { discovery ->
            require(discovery.creatorPortableKey in watchCreators) { "Discovery references an unknown watch" }
            require(discovery.sourceId to discovery.stableSourceUrl in sourceWorkKeys) {
                "Discovery references an unknown source work"
            }
            enumValueOf<DiscoveryKind>(discovery.kind)
            enumValueOf<DiscoveryReadState>(discovery.readState)
            enumValueOf<ReviewDisposition>(discovery.reviewDisposition)
            require(discovery.reason.isNotBlank()) { "Discovery reason must not be blank" }
            require(discovery.baselineGeneration >= 0) { "Discovery baseline generation is invalid" }
        }
        val canonicalKeys = normalizedSection.canonicalWorks.map(BackupAuthorCanonicalWork::portableKey)
        require(canonicalKeys.distinct().size == canonicalKeys.size) { "Duplicate canonical work portable key" }
        normalizedSection.canonicalWorks.forEach { work ->
            require(work.portableKey.isNotBlank()) { "Canonical work portable key must not be blank" }
            require(work.primaryTitle.isNotBlank()) { "Canonical work title must not be blank" }
        }
        require(
            normalizedSection.workDecisions.distinctBy {
                Triple(it.sourceId to it.stableSourceUrl, it.workPortableKey, it.state)
            }.size == normalizedSection.workDecisions.size,
        ) { "Duplicate work decision" }
        normalizedSection.workDecisions.forEach { decision ->
            require(decision.sourceId to decision.stableSourceUrl in sourceWorkKeys) {
                "Work decision references an unknown source work"
            }
            require(decision.workPortableKey in canonicalKeys) { "Work decision references an unknown canonical work" }
            require(
                WorkDecisionState.valueOf(decision.state) in
                    setOf(WorkDecisionState.CONFIRMED, WorkDecisionState.REJECTED),
            ) {
                "Only confirmed or rejected manual work decisions are portable"
            }
            require(decision.score == null || decision.score in 0.0..1.0) { "Work decision score is invalid" }
            require(decision.evidence.isNotBlank()) { "Work decision evidence must not be blank" }
        }
        val portableSubjectKeys = buildSet {
            normalizedSection.sourceWorks.forEach { add("source:${it.sourceId}:${it.stableSourceUrl}") }
            normalizedSection.canonicalWorks.forEach { add("canonical:${it.portableKey}") }
            normalizedSection.creators.forEach { add("creator:${it.portableKey}") }
        }
        require(
            normalizedSection.languageDecisions.distinctBy { it.subjectKey to it.dimension }.size ==
                normalizedSection.languageDecisions.size,
        ) { "Duplicate manual language decision" }
        normalizedSection.languageDecisions.forEach { decision ->
            require(decision.subjectType in setOf("SOURCE_WORK", "CANONICAL_WORK", "CREATOR")) {
                "Unsupported language subject type"
            }
            require(decision.subjectKey in portableSubjectKeys) { "Language decision references an unknown subject" }
            enumValueOf<LanguageDimension>(decision.dimension)
            require(CreatorArchiveLanguageTag.normalize(decision.languageTag) == decision.languageTag) {
                "Language decision tag must be normalized"
            }
        }
        return normalizedSection
    }

    private fun validateRedirects(creators: Map<String, BackupCreatorIdentity>) {
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(key: String) {
            if (key in visited) return
            require(visiting.add(key)) { "Creator merge redirect contains a cycle" }
            creators.getValue(key).mergedIntoPortableKey?.let(::visit)
            visiting.remove(key)
            visited += key
        }
        creators.keys.forEach(::visit)
    }

    private companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_MERGED = "MERGED"

        fun relationOriginRank(origin: String): Int = when (origin) {
            CreatorRelationOrigin.USER.name -> 3
            CreatorRelationOrigin.RESTORE.name -> 2
            CreatorRelationOrigin.AUTOMATIC.name -> 1
            else -> 0
        }
    }
}
