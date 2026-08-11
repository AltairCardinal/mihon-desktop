package tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupAuthorArchiveSection
import eu.kanade.tachiyomi.data.backup.models.BackupAuthorBinding
import eu.kanade.tachiyomi.data.backup.models.BackupAuthorSourceWork
import eu.kanade.tachiyomi.data.backup.models.BackupCreatorAlias
import eu.kanade.tachiyomi.data.backup.models.BackupCreatorIdentity
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.creator.model.CreatorRelationOrigin
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.service.CreatorNameNormalizer
import tachiyomi.domain.creator.service.CreatorSourceWorkKey

/**
 * Creates and restores the identity/alias/binding slice of backup field 107.
 *
 * Restore validates the complete section before opening one database transaction. Identity matching
 * uses only portable keys; equal normalized aliases are deliberately allowed across identities.
 */
interface AuthorArchiveBackupContributor {
    suspend fun createSection(): BackupAuthorArchiveSection?

    suspend fun restoreSection(section: BackupAuthorArchiveSection)
}

class SqlDelightAuthorArchiveBackupContributor(
    private val handler: DatabaseHandler,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AuthorArchiveBackupContributor {

    override suspend fun createSection(): BackupAuthorArchiveSection? = handler.await(inTransaction = true) {
        val aliases = author_archiveQueries.getArchiveAliasesForBackup().executeAsList()
            .groupBy { it.creator_portable_key }
        val bindings = author_archiveQueries.getArchiveSourceWorkBindingsForBackup().executeAsList()
            .groupBy { it.source_id to it.stable_source_url }
        val creators = author_archiveQueries.getArchiveCreatorsForBackup().executeAsList().map { creator ->
            BackupCreatorIdentity(
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
                versions.first().copy(
                    bindings = versions.flatMap(BackupAuthorSourceWork::bindings)
                        .groupBy(BackupAuthorBinding::creatorPortableKey)
                        .values
                        .map { candidates -> candidates.maxBy { relationOriginRank(it.origin) } },
                )
            }
        BackupAuthorArchiveSection(creators = creators, sourceWorks = sourceWorks)
            .takeIf { it.creators.isNotEmpty() || it.sourceWorks.isNotEmpty() }
    }

    override suspend fun restoreSection(section: BackupAuthorArchiveSection) {
        val validated = validate(section)
        val now = clock()
        handler.await(inTransaction = true) {
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
            }
            validated.creators.forEach { creator ->
                val creatorId = creatorIds.getValue(creator.portableKey)
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
            validated.sourceWorks.forEach { work ->
                restoreSourceWork(work, creatorIds, now)
            }
            validated.creators.filter { it.status == STATUS_MERGED }.forEach { creator ->
                author_archiveQueries.markArchiveCreatorMergedFromBackup(
                    targetCreatorId = creatorIds.getValue(checkNotNull(creator.mergedIntoPortableKey)),
                    lastModifiedAt = now,
                    sourceCreatorId = creatorIds.getValue(creator.portableKey),
                )
            }
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
        val sourceWorkId = if (existing == null) {
            author_archiveQueries.insertArchiveSourceWork(
                sourceId = work.sourceId,
                stableSourceUrl = work.stableSourceUrl,
                mangaId = mangaId,
                title = work.title,
                normalizedTitle = CreatorNameNormalizer.normalize(work.title),
                authorText = work.authorText,
                artistText = work.artistText,
                thumbnailUrl = work.thumbnailUrl,
                firstSeenAt = now,
                lastSeenAt = now,
                detailsFetchedAt = null,
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
        work.bindings.forEach { binding ->
            val creatorId = creatorIds.getValue(binding.creatorPortableKey)
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
            if (mangaId != null) {
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
            }
        }
    }

    private fun validate(section: BackupAuthorArchiveSection): BackupAuthorArchiveSection {
        require(section.version == BackupAuthorArchiveSection.CURRENT_VERSION) {
            "Unsupported author archive backup version: ${section.version}"
        }
        val normalizedSection = section.copy(
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

        val naturalKeys = normalizedSection.sourceWorks.map { it.sourceId to it.stableSourceUrl }
        require(naturalKeys.distinct().size == naturalKeys.size) { "Duplicate source work natural key" }
        normalizedSection.sourceWorks.forEach { work ->
            require(work.stableSourceUrl.isNotBlank()) { "Source work URL must not be blank" }
            require(work.title.isNotBlank()) { "Source work title must not be blank" }
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
