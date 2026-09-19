package tachiyomi.data.creator

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.creator.model.ArchiveAppendOutcome
import tachiyomi.domain.creator.model.ArchiveLanguageSubject
import tachiyomi.domain.creator.model.ArchiveUpsertOutcome
import tachiyomi.domain.creator.model.ArchiveWatchPolicy
import tachiyomi.domain.creator.model.ChapterCatalogCompleteness
import tachiyomi.domain.creator.model.CreatorRelationOrigin
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.DecisionActor
import tachiyomi.domain.creator.model.DiscoveryCandidateState
import tachiyomi.domain.creator.model.DiscoveryCommit
import tachiyomi.domain.creator.model.DiscoveryKind
import tachiyomi.domain.creator.model.DiscoveryReadState
import tachiyomi.domain.creator.model.LanguageAssertionContract
import tachiyomi.domain.creator.model.LanguageCertainty
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.LanguageEvidenceKind
import tachiyomi.domain.creator.model.NotificationDeliveryState
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.StaleWorkDecisionException
import tachiyomi.domain.creator.model.WorkDecisionContract
import tachiyomi.domain.creator.model.WorkDecisionState
import tachiyomi.domain.creator.model.WorkMatchState
import tachiyomi.domain.creator.repository.CreatorArchiveBootstrap
import tachiyomi.domain.creator.service.ChapterVariantRecord
import tachiyomi.domain.creator.service.ChapterVariantType
import tachiyomi.domain.creator.service.CreatorSourceWorkKey
import java.nio.file.Files

class CreatorRepositoryImplTest {

    private lateinit var repository: CreatorRepositoryImpl
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var handler: JvmDatabaseHandler

    @Test
    fun `unread work projection suppresses late sources after read`() = runBlocking<Unit> {
        val creator = repository.upsertCreator("Unread Author")
        val watchPolicy = ArchiveWatchPolicy(
            creatorId = creator.id,
            enabled = true,
            periodMillis = 1_000L,
            sourceIds = setOf(10L, 11L, 12L),
            readingLanguageTags = emptySet(),
        )
        repository.upsertWatchPolicy(
            watchPolicy,
            now = 1L,
        )
        val first = SourceWorkNaturalKey(10L, "/same-a")
        val second = SourceWorkNaturalKey(11L, "/same-b")
        val late = SourceWorkNaturalKey(12L, "/same-c")
        listOf(first, second, late).forEachIndexed { index, key ->
            repository.upsertSourceWork(
                key.sourceId,
                key.stableSourceUrl,
                null,
                "Same Work",
                "Unread Author",
                null,
                null,
                10L + index,
            )
            repository.upsertSourceWorkCreator(
                sourceWork = key,
                creatorId = creator.id,
                role = CreatorRole.AUTHOR,
                order = 0L,
                origin = CreatorRelationOrigin.AUTOMATIC,
                verification = CreatorRelationVerification.VERIFIED,
                sourceText = "Unread Author",
                confidence = 1.0,
                evidence = "fixture",
            )
        }
        val canonical = repository.createCanonicalWork("Same Work", creator.id, null)
        listOf(first, second, late).forEachIndexed { index, key ->
            repository.appendWorkDecision(
                sourceWork = key,
                workId = canonical.id,
                decision = tachiyomi.domain.creator.model.WorkDecisionContract(
                    state = WorkDecisionState.CONFIRMED,
                    actor = DecisionActor.USER,
                    explicit = true,
                ),
                algorithmVersion = null,
                score = 1.0,
                evidence = "fixture",
                decidedAt = 20L + index,
                idempotencyKey = "unread-decision-${key.sourceId}",
            )
        }
        suspend fun commit(key: SourceWorkNaturalKey, at: Long) = repository.commitDiscovery(
            DiscoveryCommit(
                creatorId = creator.id,
                sourceWork = key,
                kind = DiscoveryKind.NEW_WORK_CANDIDATE,
                reason = "fixture",
                baselineGeneration = 1L,
                discoveredAt = at,
                outboxChannel = "TEST",
                idempotencyKey = "unread-discovery-${key.sourceId}",
            ),
        )

        commit(second, 101L).state.deliveryState shouldBe NotificationDeliveryState.PENDING
        commit(first, 100L)
        val unreadWork = repository.getUnreadWorkDiscoveries(10L).single()
        unreadWork.sourceWork shouldBe first
        unreadWork.firstDiscoveredAt shouldBe 100L
        repository.getCreatorWorkArchive(creator.id).works.single().versions.all { it.unread } shouldBe true
        repository.getCreatorCardProjectionPage(0, 10, followedOnly = true).creators.single().unreadWorkCount shouldBe 1
        repository.upsertWatchPolicy(watchPolicy.copy(enabled = false), now = 150L)
        repository.getUnreadDiscoveries(10L) shouldBe emptyList()
        repository.getUnreadWorkDiscoveries(10L) shouldBe emptyList()
        repository.getDiscoveries(10L).filter { it.state.readState == DiscoveryReadState.UNSEEN } shouldBe emptyList()
        repository.getCreatorCardProjectionPage(0, 10, followedOnly = false)
            .creators.single().unreadWorkCount shouldBe 0
        repository.upsertWatchPolicy(watchPolicy, now = 160L)
        repository.getUnreadWorkDiscoveries(10L).single().sourceWork shouldBe first
        repository.markWorkSeen(first, 200L)
        repository.getUnreadWorkDiscoveries(10L) shouldBe emptyList()
        repository.getCreatorWorkArchive(creator.id).works.single().versions.any { it.unread } shouldBe false
        repository.getCreatorCardProjectionPage(0, 10, followedOnly = true).creators.single().unreadWorkCount shouldBe 0

        val restarted = CreatorRepositoryImpl(handler)
        restarted.getUnreadWorkDiscoveries(10L) shouldBe emptyList()
        restarted.commitDiscovery(
            DiscoveryCommit(
                creatorId = creator.id,
                sourceWork = late,
                kind = DiscoveryKind.NEW_WORK_CANDIDATE,
                reason = "fixture",
                baselineGeneration = 1L,
                discoveredAt = 300L,
                outboxChannel = "TEST",
                idempotencyKey = "unread-discovery-${late.sourceId}",
            ),
        )
        restarted.getUnreadDiscoveries(10L).filter { it.sourceWork == late } shouldBe emptyList()
        restarted.getUnreadWorkDiscoveries(10L) shouldBe emptyList()
        queryLong("SELECT COUNT(*) FROM author_archive_discoveries WHERE read_state = 'SEEN'") shouldBe 3L
        queryLong("SELECT COUNT(*) FROM author_archive_notification_outbox") shouldBe 2L
    }

    @Test
    fun `uncanonical unread source work is projected into the author archive`() = runBlocking<Unit> {
        val creator = repository.upsertCreator("Uncanonical Author")
        val watchPolicy = ArchiveWatchPolicy(
            creatorId = creator.id,
            enabled = true,
            periodMillis = 1_000L,
            sourceIds = setOf(31L),
            readingLanguageTags = emptySet(),
        )
        repository.upsertWatchPolicy(watchPolicy, now = 1L)
        val sourceWork = SourceWorkNaturalKey(31L, "/uncanonical")
        repository.upsertSourceWork(
            31L,
            sourceWork.stableSourceUrl,
            null,
            "Uncanonical Work",
            creator.displayName,
            null,
            null,
            10L,
        )
        repository.upsertSourceWorkCreator(
            sourceWork = sourceWork,
            creatorId = creator.id,
            role = CreatorRole.AUTHOR,
            order = 0L,
            origin = CreatorRelationOrigin.AUTOMATIC,
            verification = CreatorRelationVerification.VERIFIED,
            sourceText = creator.displayName,
            confidence = 1.0,
            evidence = "fixture",
        )
        repository.commitDiscovery(
            DiscoveryCommit(
                creatorId = creator.id,
                sourceWork = sourceWork,
                kind = DiscoveryKind.NEW_WORK_CANDIDATE,
                reason = "fixture",
                baselineGeneration = 1L,
                discoveredAt = 100L,
                outboxChannel = "TEST",
                idempotencyKey = "uncanonical-discovery",
            ),
        )

        val pending = repository.getCreatorWorkArchive(creator.id).pending.single()
        pending.unread shouldBe true
        pending.unreadFirstDiscoveredAt shouldBe 100L
        repository.getCreatorCardProjectionPage(0, 10, followedOnly = false)
            .creators.single().unreadWorkCount shouldBe 1

        repository.markWorkSeen(sourceWork, 200L)
        repository.getCreatorWorkArchive(creator.id).pending.single().unread shouldBe false
    }

    @Test
    fun `same-time multi-author unread work chooses the stable creator key`() = runBlocking<Unit> {
        val firstCreator = repository.upsertCreator("First Stable Author")
        val secondCreator = repository.upsertCreator("Second Stable Author")
        val sourceWork = SourceWorkNaturalKey(32L, "/multi-author")
        listOf(firstCreator, secondCreator).forEach { creator ->
            repository.upsertWatchPolicy(
                ArchiveWatchPolicy(
                    creatorId = creator.id,
                    enabled = true,
                    periodMillis = 1_000L,
                    sourceIds = setOf(sourceWork.sourceId),
                    readingLanguageTags = emptySet(),
                ),
                now = 1L,
            )
        }
        repository.upsertSourceWork(
            32L,
            sourceWork.stableSourceUrl,
            null,
            "Shared Stable Work",
            "First Stable Author, Second Stable Author",
            null,
            null,
            10L,
        )
        listOf(firstCreator, secondCreator).forEachIndexed { index, creator ->
            repository.upsertSourceWorkCreator(
                sourceWork = sourceWork,
                creatorId = creator.id,
                role = CreatorRole.AUTHOR,
                order = index.toLong(),
                origin = CreatorRelationOrigin.AUTOMATIC,
                verification = CreatorRelationVerification.VERIFIED,
                sourceText = creator.displayName,
                confidence = 1.0,
                evidence = "fixture",
            )
        }
        val canonical = repository.createCanonicalWork("Shared Stable Work", firstCreator.id, null)
        repository.appendWorkDecision(
            sourceWork = sourceWork,
            workId = canonical.id,
            decision = WorkDecisionContract(
                state = WorkDecisionState.CONFIRMED,
                actor = DecisionActor.USER,
                explicit = true,
            ),
            algorithmVersion = null,
            score = 1.0,
            evidence = "fixture",
            decidedAt = 20L,
            idempotencyKey = "multi-author-decision",
        )
        val expectedCreator = queryLong(
            "SELECT _id FROM author_archive_creators ORDER BY portable_key LIMIT 1",
        )
        val firstCommitCreator = if (expectedCreator == firstCreator.id) secondCreator else firstCreator
        val secondCommitCreator = if (expectedCreator == firstCreator.id) firstCreator else secondCreator
        listOf(firstCommitCreator, secondCommitCreator).forEachIndexed { index, creator ->
            repository.commitDiscovery(
                DiscoveryCommit(
                    creatorId = creator.id,
                    sourceWork = sourceWork,
                    kind = DiscoveryKind.NEW_WORK_CANDIDATE,
                    reason = "fixture",
                    baselineGeneration = 1L,
                    discoveredAt = 100L,
                    outboxChannel = "TEST",
                    idempotencyKey = "multi-author-discovery-$index",
                ),
            )
        }

        val unread = repository.getUnreadWorkDiscoveries(10L).single()
        unread.creatorId shouldBe expectedCreator
        unread.creatorIds.toSet() shouldBe setOf(firstCreator.id, secondCreator.id)
    }

    @BeforeEach
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        val database = Database(
            driver = driver,
            historyAdapter = tachiyomi.data.History.Adapter(
                last_readAdapter = DateColumnAdapter,
            ),
            mangasAdapter = tachiyomi.data.Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
            ),
        )
        handler = JvmDatabaseHandler(database, driver)
        repository = CreatorRepositoryImpl(handler)
    }

    @Test
    fun `archive rejects redirect cycle and resubscription retries repaired graph`() = runBlocking<Unit> {
        val first = repository.upsertCreator("First")
        val second = repository.upsertCreator("Second")
        driver.execute(
            null,
            "UPDATE author_archive_creators " +
                "SET status = 'MERGED', merged_into_creator_id = ${second.id} WHERE _id = ${first.id}",
            0,
        )
        driver.execute(
            null,
            "UPDATE author_archive_creators " +
                "SET status = 'MERGED', merged_into_creator_id = ${first.id} WHERE _id = ${second.id}",
            0,
        )
        shouldThrow<IllegalStateException> { repository.observeCreatorWorkArchive(first.id).first() }
        driver.execute(
            null,
            "UPDATE author_archive_creators " +
                "SET status = 'ACTIVE', merged_into_creator_id = NULL WHERE _id = ${second.id}",
            0,
        )
        val key = SourceWorkNaturalKey(81L, "/repaired")
        repository.upsertSourceWork(81L, key.stableSourceUrl, null, "Repaired", "Second", null, null, 1L)
        repository.upsertSourceWorkCreator(
            key, second.id, CreatorRole.AUTHOR, 0, CreatorRelationOrigin.USER,
            CreatorRelationVerification.VERIFIED, "Second", 1.0, "fixture",
        )
        repository.observeCreatorWorkArchive(first.id).first().pending.single().naturalKey shouldBe key
    }

    @Test
    fun `catalog refresh preserves frozen first seen facts and records completeness`() = runBlocking<Unit> {
        val key = SourceWorkNaturalKey(91L, "/catalog")
        val firstSeenAt = 1_736_294_400_000L
        val refreshedAt = 1_800_000_000_000L
        var now = firstSeenAt
        val timedRepository = CreatorRepositoryImpl(handler, clock = { now })
        timedRepository.upsertSourceWork(91L, key.stableSourceUrl, null, "Catalog", null, null, null, null)
        now = refreshedAt
        timedRepository.upsertSourceWork(91L, key.stableSourceUrl, null, "Catalog renamed", null, null, null, null)

        timedRepository.updateSourceWorkCatalog(
            sourceWork = key,
            chapterCount = 3L,
            completeness = ChapterCatalogCompleteness.PARTIAL,
            latestChapterAt = 1_790_000_000_000L,
            observedAt = refreshedAt,
        )

        handler.await(inTransaction = true) {
            val row = author_archiveQueries.getArchiveSourceWorkByKey(91L, key.stableSourceUrl).executeAsOne()
            row.first_seen_at shouldBe firstSeenAt
            row.first_seen_date shouldBe "2025-01-08"
            row.first_seen_zone shouldBe "UTC"
            row.chapter_count_state shouldBe ChapterCatalogCompleteness.PARTIAL.name
            row.catalog_chapter_count shouldBe 3L
            row.latest_chapter_at shouldBe 1_790_000_000_000L
        }
    }

    @Test
    fun `catalog refresh updates a migrated blank-url source work`() = runBlocking<Unit> {
        seedManga(id = 123L, source = 91L, url = "", title = "Legacy Catalog")
        val legacyUrl = "legacy-manga:123"
        repository.upsertSourceWork(
            sourceId = 91L,
            stableSourceUrl = legacyUrl,
            mangaId = 123L,
            title = "Legacy Catalog",
            authorText = null,
            artistText = null,
            thumbnailUrl = null,
            detailsFetchedAt = null,
        )

        repository.updateSourceWorkCatalog(
            sourceWork = SourceWorkNaturalKey(
                sourceId = 91L,
                stableSourceUrl = CreatorSourceWorkKey.stableUrl("", "Legacy Catalog", null, null),
            ),
            chapterCount = 4L,
            completeness = ChapterCatalogCompleteness.COMPLETE,
            latestChapterAt = 1_800L,
            observedAt = 2_000L,
            mangaId = 123L,
        )

        handler.await(inTransaction = true) {
            val row = author_archiveQueries.getArchiveSourceWorkByKey(91L, legacyUrl).executeAsOne()
            row.chapter_count_state shouldBe ChapterCatalogCompleteness.COMPLETE.name
            row.catalog_chapter_count shouldBe 4L
            row.latest_chapter_at shouldBe 1_800L
        }
    }

    @Test
    fun `three relation types replace metadata roles and protect USER and RESTORE authority`() = runBlocking<Unit> {
        seedManga(601L, 6L, "/authority", "Authority")
        val creator = repository.upsertCreator("Authority")
        val key = SourceWorkNaturalKey(6L, "/authority")
        repository.upsertSourceWork(6L, key.stableSourceUrl, 601L, "Authority", "Authority", null, null, 1L)
        val canonical = repository.createCanonicalWork("Authority", creator.id, null)
        handler.await(inTransaction = true) {
            val sourceId = author_archiveQueries.getArchiveSourceWorkByKey(6L, key.stableSourceUrl).executeAsOne()._id
            fun write(role: String, origin: String, text: String) {
                author_archiveQueries.upsertArchiveMangaLink(601L, creator.id, role, 0, origin, text, 1.0, text, 1, 2)
                author_archiveQueries.upsertArchiveSourceWorkCreator(
                    sourceId, creator.id, role, 0, origin, "VERIFIED", text, 1.0, text, 1, 2,
                )
                author_archiveQueries.upsertArchiveCanonicalCreator(
                    canonical.id,
                    creator.id,
                    role,
                    0,
                    if (origin == "AUTOMATIC") "ALGORITHM" else origin,
                    text,
                )
            }
            fun roles() = listOf(
                author_archiveQueries.getArchiveMangaLink(601L, creator.id).executeAsOne().role,
                author_archiveQueries.getArchiveSourceWorkCreator(sourceId, creator.id).executeAsOne().role,
                author_archiveQueries.getArchiveCanonicalCreatorsForCreator(creator.id).executeAsList().single().role,
            )
            // Canonical creation is user-authored; reset the fixture before metadata assertions.
            author_archiveQueries.deleteArchiveCanonicalCreator(canonical.id, creator.id)
            write("BOTH", "AUTOMATIC", "metadata")
            write("AUTHOR", "AUTOMATIC", "refreshed")
            roles() shouldBe listOf("AUTHOR", "AUTHOR", "AUTHOR")
            for (authority in listOf("USER", "RESTORE")) {
                author_archiveQueries.deleteArchiveMangaLink(601L, creator.id)
                author_archiveQueries.deleteArchiveSourceWorkCreator(sourceId, creator.id)
                author_archiveQueries.deleteArchiveCanonicalCreator(canonical.id, creator.id)
                write("ARTIST", authority, "protected raw signature")
                write("AUTHOR", "AUTOMATIC", "metadata replacement")
                roles() shouldBe listOf("ARTIST", "ARTIST", "ARTIST")
                author_archiveQueries.getArchiveMangaLink(601L, creator.id).executeAsOne().source_text shouldBe
                    "protected raw signature"
                author_archiveQueries.getArchiveSourceWorkCreator(
                    sourceId,
                    creator.id,
                ).executeAsOne().source_text shouldBe
                    "protected raw signature"
                author_archiveQueries.getArchiveCanonicalCreatorsForCreator(
                    creator.id,
                ).executeAsList().single().evidence shouldBe
                    "protected raw signature"
            }
        }
    }

    @Test
    fun `same root alias tokens keep combined roles during repeated automatic resolution`() = runBlocking<Unit> {
        seedManga(602L, 6L, "/aliases", "Aliases")
        val creator = repository.upsertCreator("Writer")
        repository.addManualCreatorAlias(creator.id, "Artist Alias")
        val manga = tachiyomi.domain.manga.model.Manga.create().copy(
            id = 602L,
            source = 6L,
            url = "/aliases",
            title = "Aliases",
            author = "Writer",
            artist = "Artist Alias",
        )
        val manage = tachiyomi.domain.creator.interactor.ManageCreatorIdentity(repository)
        val mentions = tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga().await(manga)
        mentions.forEach { manage.resolve(manga, it) }
        val revision = queryLong("SELECT identity_revision FROM author_archive_creators")
        mentions.reversed().forEach { manage.resolve(manga, it) }
        queryLong("SELECT identity_revision FROM author_archive_creators") shouldBe revision
        queryString("SELECT role FROM author_archive_manga_links") shouldBe "BOTH"
        queryString("SELECT role FROM author_archive_source_work_creators") shouldBe "BOTH"
    }

    @Test
    fun `migration recovery retains full premerge graph after failure and file reopen`() = runBlocking<Unit> {
        val path = Files.createTempFile("creator-full-recovery", ".db")
        val firstDriver = JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}")
        Database.Schema.create(firstDriver)
        firstDriver.execute(null, "PRAGMA foreign_keys = ON", 0)
        val firstHandler = JvmDatabaseHandler(databaseFor(firstDriver), firstDriver)
        fun seed(sql: String) {
            firstDriver.execute(null, sql, 0)
        }
        seed("INSERT INTO author_archive_creators VALUES (1,'a','Same','same','Same','ACTIVE',NULL,1,7,101,1,2)")
        seed("INSERT INTO author_archive_creators VALUES (2,'b','Same','same','Same','ACTIVE',NULL,0,9,102,3,4)")
        seed(
            "INSERT INTO author_archive_creators VALUES " +
                "(3,'unrelated','Other','other','Other','ACTIVE',NULL,0,0,NULL,1,1)",
        )
        seed(
            "INSERT INTO author_archive_creators VALUES " +
                "(4,'older-key','Previous','previous','Previous','MERGED',2,0,5,104,1,2)",
        )
        seed("INSERT INTO author_archive_identity_names VALUES ('Variant',2,'RESTORE',3,4)")
        seed("INSERT INTO author_archive_aliases VALUES (1,2,'Same','same','USER','raw alias',0.8,1,3,4)")
        seed(
            "INSERT INTO " +
                "mangas(_id,source,url,title,status,favorite,initialized,viewer,chapter_flags," +
                "cover_last_modified,date_added) VALUES (1,77,'/raw','Work',0,0,0,0,0,0,0)",
        )
        seed("INSERT INTO author_archive_manga_links VALUES (1,1,1,'AUTHOR',0,'USER','Same',1.0,'author evidence',1,2)")
        seed(
            "INSERT INTO author_archive_manga_links VALUES " +
                "(2,1,2,'ARTIST',1,'RESTORE','Variant',0.9,'artist evidence',3,4)",
        )
        seed(
            "INSERT INTO author_archive_source_works(" +
                "_id,source_id,stable_source_url,manga_id,title,normalized_title,author_text,artist_text," +
                "thumbnail_url,first_seen_at,last_seen_at,details_fetched_at,legacy_candidate_id," +
                "legacy_review_snapshot) VALUES " +
                "(1,77,'/raw',1,'Work','work','Same','Variant',NULL,1,2,3,201,'ACCEPTED')",
        )
        seed(
            "INSERT INTO author_archive_source_work_creators VALUES " +
                "(1,1,1,'AUTHOR',0,'USER','VERIFIED','Same',1.0,'a',1,2)",
        )
        seed(
            "INSERT INTO author_archive_source_work_creators VALUES " +
                "(2,1,2,'ARTIST',1,'RESTORE','POSSIBLE','Variant',0.7,'b',3,4)",
        )
        seed("INSERT INTO author_archive_canonical_works VALUES (1,'work-key','Work','work','ACTIVE',301,1,2)")
        seed("INSERT INTO author_archive_canonical_creators VALUES (1,1,1,'AUTHOR',0,'USER','canonical a')")
        seed("INSERT INTO author_archive_canonical_creators VALUES (2,1,2,'ARTIST',1,'RESTORE','canonical b')")
        for (id in 1..2) {
            seed("INSERT INTO author_archive_watches VALUES ($id,$id,1,86400000,NULL,NULL,10,9,'prior',1,2)")
            seed("INSERT INTO author_archive_watch_sources VALUES ($id,$id,77,'BASELINED',$id,80,1,2)")
            seed("INSERT INTO author_archive_watch_result_policies VALUES ($id,$id,1,0,0,1,1,2)")
            seed("INSERT INTO author_archive_watch_languages VALUES ($id,$id,'${if (id == 1) "ja" else "en"}')")
            seed("INSERT INTO author_archive_runs VALUES ($id,'run-$id',$id,'FAILED',0,1,1,'code','message',1,2,3)")
            seed(
                "INSERT INTO author_archive_source_checkpoints VALUES " +
                    "($id,$id,77,'cursor-$id','FAILED',2,90,10,9,'code','message')",
            )
            seed(
                "INSERT INTO author_archive_discoveries VALUES " +
                    "($id,$id,1,'NEW_WORK_CANDIDATE','reason-$id',$id,'UNSEEN','PENDING',1,2)",
            )
            seed(
                "INSERT INTO author_archive_notification_outbox VALUES " +
                    "($id,$id,'DESKTOP','outbox-$id',2,'FAILED','delivery error',90,1,2,NULL)",
            )
        }
        seed(
            "INSERT INTO author_archive_language_assertions VALUES " +
                "(1,'CREATOR','creator:b','ORIGINAL','ja',0.8,'MANUAL','original payload','USER',NULL,0,4,'lang-key')",
        )
        val tables = listOf(
            "creators", "identity_names", "aliases", "manga_links", "source_works", "source_work_creators",
            "canonical_works", "canonical_creators", "watches", "watch_sources", "watch_result_policies",
            "watch_languages", "runs", "source_checkpoints", "discoveries",
            "notification_outbox", "language_assertions",
        )
        val expected = tables.associateWith { table ->
            recoveryRows(firstDriver, "author_archive_$table", if (table == "creators") "WHERE _id IN (1,2,4)" else "")
        }
        val interrupted = CreatorRepositoryImpl(
            firstHandler,
            identityMutationHook = { error("injected merge failure") },
        )
        shouldThrow<IllegalStateException> { interrupted.resolveCreatorIdByExactName("Same") }
        tables.forEach { table ->
            recoveryRows(
                firstDriver,
                "author_archive_$table",
                if (table == "creators") "WHERE _id IN (1,2,4)" else "",
            ) shouldBe expected.getValue(table)
        }
        firstHandler.close()
        val reopened = JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}")
        val reopenedHandler = JvmDatabaseHandler(databaseFor(reopened), reopened)
        try {
            CreatorRepositoryImpl(reopenedHandler).resolveCreatorIdByExactName("Same") shouldBe 1L
            val encoded = reopened.executeQuery(
                null,
                "SELECT recovery_graph FROM author_archive_identity_migration_components WHERE " +
                    "component_key = '1,2' AND state = 'COMPLETED'",
                { cursor ->
                    cursor.next()
                    app.cash.sqldelight.db.QueryResult.Value(cursor.getString(0)!!)
                },
                0,
            ).value
            val decoded = kotlinx.serialization.json.Json.parseToJsonElement(encoded).jsonObject
            decoded.getValue("version").jsonPrimitive.content shouldBe "1"
            val graph = decoded.getValue("tables").jsonObject
            expected.forEach { (table, rows) ->
                graph.getValue(table).jsonArray.map { row ->
                    row.jsonObject.mapValues { (_, value) ->
                        when (value.jsonPrimitive.content) {
                            "true" -> "1"
                            "false" -> "0"
                            else -> value.jsonPrimitive.contentOrNull
                        }
                    }
                } shouldBe rows
            }
            queryLong(reopened, "SELECT COUNT(*) FROM author_archive_notification_outbox") shouldBe 2L
            queryLong(reopened, "SELECT COUNT(*) FROM author_archive_creators WHERE status = 'ACTIVE'") shouldBe 2L
        } finally {
            reopenedHandler.close()
            Files.deleteIfExists(path)
        }
    }

    private fun recoveryRows(sqlDriver: JdbcSqliteDriver, table: String, where: String): List<Map<String, String?>> {
        val columns = sqlDriver.executeQuery(null, "PRAGMA table_info($table)", { cursor ->
            val names = mutableListOf<String>()
            while (cursor.next().value) names += cursor.getString(1)!!
            app.cash.sqldelight.db.QueryResult.Value(names)
        }, 0).value
        return sqlDriver.executeQuery(null, "SELECT * FROM $table $where ORDER BY ${columns.first()}", { cursor ->
            val rows = mutableListOf<Map<String, String?>>()
            while (cursor.next().value) {
                rows += columns.mapIndexed { index, column -> column to cursor.getString(index) }.toMap()
            }
            app.cash.sqldelight.db.QueryResult.Value(rows)
        }, 0).value
    }

    @Test
    fun `repeated real resolve is revision idempotent and automatic binding is replaceable`() = runBlocking<Unit> {
        seedManga(501L, 5L, "/resolve", "Resolve")
        val manga = tachiyomi.domain.manga.model.Manga.create().copy(
            id = 501L,
            source = 5L,
            url = "/resolve",
            title = "Resolve",
            author = "Exact",
            favorite = false,
        )
        val manage = tachiyomi.domain.creator.interactor.ManageCreatorIdentity(repository)
        val mention = tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga().await(manga).single()
        manage.resolve(manga, mention)
        val before = queryLong("SELECT identity_revision FROM author_archive_creators")
        manage.resolve(manga, mention)
        queryLong("SELECT identity_revision FROM author_archive_creators") shouldBe before
        queryString("SELECT origin FROM author_archive_manga_links") shouldBe "AUTOMATIC"
    }

    @Test
    fun `live old root archive subscription follows subsequent merge and root work updates`() = runBlocking<Unit> {
        val source = repository.upsertCreator("Old")
        val target = repository.upsertCreator("Current")
        val emissions = kotlinx.coroutines.channels.Channel<tachiyomi.domain.creator.model.CreatorWorkArchive>(10)
        val collector = kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
            repository.observeCreatorWorkArchive(source.id).collect { emissions.send(it) }
        }
        try {
            kotlinx.coroutines.withTimeout(5000) { emissions.receive() }
            repository.mergeCreatorIdentities(source.id, target.id)
            val key = SourceWorkNaturalKey(77L, "/live-root")
            repository.upsertSourceWork(77L, key.stableSourceUrl, null, "Live", "Current", null, null, 1L)
            repository.upsertSourceWorkCreator(
                key, target.id, CreatorRole.AUTHOR, 0, CreatorRelationOrigin.USER,
                CreatorRelationVerification.VERIFIED, "Current", 1.0, "fixture",
            )
            kotlinx.coroutines.withTimeout(5000) {
                while (emissions.receive().pending.none { it.naturalKey == key }) { }
            }
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `repository waits for archive bootstrap before the first mutation`() {
        var readinessCalls = 0
        val gatedRepository = CreatorRepositoryImpl(
            handler = handler,
            bootstrap = object : CreatorArchiveBootstrap {
                override suspend fun awaitReady() {
                    readinessCalls += 1
                }
            },
        )

        runBlocking { gatedRepository.upsertCreator("ONE") }

        readinessCalls shouldBe 1
        queryLong("SELECT COUNT(*) FROM author_archive_creators") shouldBe 1L
    }

    @Test
    fun `repository fails closed without mutating when archive bootstrap fails`() {
        val gatedRepository = CreatorRepositoryImpl(
            handler = handler,
            bootstrap = object : CreatorArchiveBootstrap {
                override suspend fun awaitReady() {
                    error("legacy import failed")
                }
            },
        )

        shouldThrow<IllegalStateException> {
            runBlocking { gatedRepository.upsertCreator("ONE") }
        }
        queryLong("SELECT COUNT(*) FROM author_archive_creators") shouldBe 0L
    }

    @Test
    fun `concurrent exact name creation leaves one root and no orphan`() {
        runBlocking {
            val ids = List(20) { async { repository.upsertCreator("冈本伦").id } }.awaitAll()

            ids.distinct().size shouldBe 1
            queryLong("SELECT COUNT(*) FROM author_archive_creators WHERE status = 'ACTIVE'") shouldBe 1L
            queryLong("SELECT COUNT(*) FROM author_archive_identity_names WHERE name_text = '冈本伦'") shouldBe 1L
        }
    }

    @Test
    fun `independent database connections serialize an exact-name uniqueness race`() {
        runBlocking {
            val path = Files.createTempFile("creator-exact-race", ".db")
            Files.deleteIfExists(path)
            val firstDriver = JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}")
            Database.Schema.create(firstDriver)
            val secondDriver = JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}")
            fun database(driver: JdbcSqliteDriver) = Database(
                driver = driver,
                historyAdapter = tachiyomi.data.History.Adapter(last_readAdapter = DateColumnAdapter),
                mangasAdapter = tachiyomi.data.Mangas.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = UpdateStrategyColumnAdapter,
                ),
            )
            val firstHandler = JvmDatabaseHandler(database(firstDriver), firstDriver)
            val secondHandler = JvmDatabaseHandler(database(secondDriver), secondDriver)
            try {
                val ids = listOf(
                    async { CreatorRepositoryImpl(firstHandler).upsertCreator("Exact Race").id },
                    async { CreatorRepositoryImpl(secondHandler).upsertCreator("Exact Race").id },
                ).awaitAll()

                ids.distinct().size shouldBe 1
            } finally {
                firstHandler.close()
                secondHandler.close()
                Files.deleteIfExists(path)
            }
        }
    }

    @Test
    fun `failure after exact name registration rolls back creator alias and registry`() {
        val failing = CreatorRepositoryImpl(
            handler = handler,
            identityMutationHook = { error("injected identity failure") },
        )

        shouldThrow<IllegalStateException> { runBlocking { failing.upsertCreator("冈本伦") } }

        queryLong("SELECT COUNT(*) FROM author_archive_creators") shouldBe 0L
        queryLong("SELECT COUNT(*) FROM author_archive_aliases") shouldBe 0L
        queryLong("SELECT COUNT(*) FROM author_archive_identity_names") shouldBe 0L
    }

    @Test
    fun `creator mutations use archive identity and alias tables without writing legacy rows`() {
        runBlocking {
            val creator = repository.upsertCreator(" ONE ", aliases = listOf("One sensei"))

            repository.getCreator(creator.id) shouldBe creator
            queryLong("SELECT COUNT(*) FROM author_archive_creators") shouldBe 1L
            queryLong("SELECT COUNT(*) FROM author_archive_aliases WHERE creator_id = ${creator.id}") shouldBe 2L
            queryLong("SELECT COUNT(*) FROM creators") shouldBe 0L
        }
    }

    @Test
    fun `all production creator mutations succeed when every legacy table rejects writes`() {
        seedManga(id = 99L, source = 7L, url = "/guarded", title = "Guarded Work")
        installLegacyWriteGuards()

        runBlocking {
            val creator = repository.upsertCreator("ONE")
            repository.linkMangaCreator(
                mangaId = 99L,
                creatorId = creator.id,
                role = CreatorRole.AUTHOR,
                sourceText = "ONE",
                confidence = 1.0,
                evidence = "guarded manga relation",
            )
            val candidate = repository.upsertDiscoveryCandidate(
                source = 7L,
                url = "/guarded-candidate",
                title = "Guarded Candidate",
                authorText = "ONE",
                artistText = null,
                languageTag = "ja",
                languageConfidence = 1.0,
                languageEvidence = "SOURCE_LANGUAGE",
                thumbnailUrl = null,
                detailsFetchedAt = 1L,
                state = DiscoveryCandidateState.NEW,
            )
            repository.linkDiscoveryCandidateCreator(
                candidateId = candidate.id,
                creatorId = creator.id,
                role = CreatorRole.AUTHOR,
                sourceText = "ONE",
                confidence = 1.0,
                evidence = "guarded source relation",
            )
            repository.followCreator(creator.id, sourceIds = listOf(7L), languageTags = listOf("ja"))
            repository.updateWatchCheckResult(creator.id, checkedAt = 10L, success = true, error = null)
            repository.unfollowCreator(creator.id)
            val work = repository.createCanonicalWork("Guarded Work", creator.id, "ja")
            repository.upsertMangaWorkMatch(
                mangaId = 99L,
                workId = work.id,
                confidence = 1.0,
                matchReason = "guarded decision",
                state = WorkMatchState.CONFIRMED,
                manuallyConfirmed = true,
            )
        }

        LEGACY_CREATOR_TABLES.forEach { table ->
            queryLong("SELECT COUNT(*) FROM $table") shouldBe 0L
        }
    }

    @Test
    fun `historical exact duplicates converge while case variants remain distinct`() {
        driver.execute(
            null,
            "INSERT INTO author_archive_creators(portable_key, display_name, normalized_name, sort_name, status, " +
                "created_at, last_modified_at) VALUES ('identity-a', 'Same', 'same', 'Same', 'ACTIVE', 1, 1), " +
                "('identity-b', 'Same', 'same', 'Same', 'ACTIVE', 1, 1)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO author_archive_aliases(" +
                "creator_id, raw_alias, normalized_alias, source, evidence, confidence, " +
                "is_manual, created_at, last_modified_at) " +
                "VALUES (1, 'Same', 'same', 'PRIMARY', 'fixture', 1, 0, 1, 1), " +
                "(2, 'Same', 'same', 'PRIMARY', 'fixture', 1, 0, 1, 1)",
            0,
        )

        runBlocking { repository.upsertCreator("same") }
        queryLong("SELECT COUNT(*) FROM author_archive_creators WHERE status = 'ACTIVE'") shouldBe 2L
        queryLong("SELECT COUNT(*) FROM author_archive_creators WHERE status = 'MERGED'") shouldBe 1L
        queryLong("SELECT COUNT(*) FROM creators") shouldBe 0L
    }

    @Test
    fun `multi-group migration resumes from completed component after an injected interruption`() {
        driver.execute(
            null,
            "INSERT INTO author_archive_creators(portable_key, display_name, normalized_name, sort_name, status, " +
                "created_at, last_modified_at) VALUES " +
                "('a-1', 'A', 'a', 'A', 'ACTIVE', 1, 1), ('a-2', 'A', 'a', 'A', 'ACTIVE', 1, 1), " +
                "('b-1', 'B', 'b', 'B', 'ACTIVE', 1, 1), ('b-2', 'B', 'b', 'B', 'ACTIVE', 1, 1)",
            0,
        )
        var component = 0
        val interrupted = CreatorRepositoryImpl(
            handler,
            identityMutationHook = {
                component += 1
                if (component == 2) error("injected component interruption")
            },
        )

        shouldThrow<IllegalStateException> { runBlocking { interrupted.resolveCreatorIdByExactName("A") } }

        queryLong(
            "SELECT COUNT(*) FROM author_archive_identity_migration_components WHERE state = 'COMPLETED'",
        ) shouldBe 1L
        queryLong(
            "SELECT COUNT(*) FROM author_archive_identity_migration_components WHERE state = 'PREPARED'",
        ) shouldBe 1L
        queryLong("SELECT COUNT(*) FROM author_archive_creators WHERE status = 'ACTIVE'") shouldBe 3L

        val restarted = CreatorRepositoryImpl(handler)
        runBlocking { restarted.resolveCreatorIdByExactName("B") }

        queryLong(
            "SELECT COUNT(*) FROM author_archive_identity_migration_components WHERE state = 'COMPLETED'",
        ) shouldBe 2L
        queryLong("SELECT COUNT(*) FROM author_archive_creators WHERE status = 'ACTIVE'") shouldBe 2L
    }

    @Test
    fun `prepared migration component survives database close and resumes after reopen`() {
        val path = Files.createTempFile("creator-migration-restart", ".db")
        Files.deleteIfExists(path)
        val firstDriver = JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}")
        Database.Schema.create(firstDriver)
        firstDriver.execute(
            null,
            "INSERT INTO author_archive_creators(portable_key, display_name, normalized_name, sort_name, status, " +
                "created_at, last_modified_at) VALUES " +
                "('restart-1', 'Restart', 'restart', 'Restart', 'ACTIVE', 1, 1), " +
                "('restart-2', 'Restart', 'restart', 'Restart', 'ACTIVE', 1, 1)",
            0,
        )
        val firstHandler = JvmDatabaseHandler(databaseFor(firstDriver), firstDriver)
        val interrupted = CreatorRepositoryImpl(firstHandler, identityMutationHook = { error("stop") })
        shouldThrow<IllegalStateException> {
            runBlocking { interrupted.resolveCreatorIdByExactName("Restart") }
        }
        firstHandler.close()

        val reopenedDriver = JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}")
        val reopenedHandler = JvmDatabaseHandler(databaseFor(reopenedDriver), reopenedDriver)
        try {
            runBlocking { CreatorRepositoryImpl(reopenedHandler).resolveCreatorIdByExactName("Restart") }
            queryLong(reopenedDriver, "SELECT COUNT(*) FROM author_archive_creators WHERE status = 'ACTIVE'") shouldBe
                1L
            queryLong(
                reopenedDriver,
                "SELECT COUNT(*) FROM author_archive_identity_migration_components WHERE state = 'COMPLETED'",
            ) shouldBe 1L
        } finally {
            reopenedHandler.close()
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `creator projection preserves aliases containing the former list separator`() {
        runBlocking {
            val alias = "ONE\u001FTWO"
            val creator = repository.upsertCreator("Primary", aliases = listOf(alias))

            repository.getCreator(creator.id)!!.aliases shouldContainExactly listOf(alias)
            repository.getCreatorsAsFlow().first().single().aliases shouldContainExactly listOf(alias)
        }
    }

    @Test
    fun `exact alias registry preserves variants collapsed by search normalization`() {
        runBlocking {
            val creator = repository.upsertCreator("Same", aliases = listOf("Ｓａｍｅ", "same!"))

            repository.getCreator(creator.id)!!.aliases.shouldContainExactly("same!", "Ｓａｍｅ")
            repository.resolveCreatorIdByExactName("Same") shouldBe creator.id
            repository.resolveCreatorIdByExactName("Ｓａｍｅ") shouldBe creator.id
            repository.resolveCreatorIdByExactName("same!") shouldBe creator.id
            repository.resolveCreatorIdByExactName("same") shouldBe null
        }
    }

    @Test
    fun `nonblank punctuation can be an exact identity despite blank search key`() {
        runBlocking {
            val creator = repository.upsertCreator("!!!")

            repository.resolveCreatorIdByExactName("!!!") shouldBe creator.id
            creator.normalizedName shouldBe ""
        }
    }

    @Test
    fun `manual punctuation alias is accepted and remains exactly resolvable`() {
        runBlocking {
            val creator = repository.upsertCreator("Primary")

            repository.addManualCreatorAlias(creator.id, "!!!")

            repository.resolveCreatorIdByExactName("!!!") shouldBe creator.id
            repository.getManualCreatorAliases(creator.id).shouldContainExactly("!!!")
        }
    }

    @Test
    fun `merged creator id opens the active root and its complete work archive`() {
        runBlocking {
            val source = repository.upsertCreator("Source")
            val target = repository.upsertCreator("Target")
            val work = SourceWorkNaturalKey(77L, "/redirected")
            repository.upsertSourceWork(77L, work.stableSourceUrl, null, "Redirected", "Source", null, null, 1L)
            repository.upsertSourceWorkCreator(
                work,
                source.id,
                CreatorRole.AUTHOR,
                0,
                CreatorRelationOrigin.USER,
                CreatorRelationVerification.VERIFIED,
                "Source",
                1.0,
                "fixture",
            )

            repository.mergeCreatorIdentities(source.id, target.id)

            repository.getCreator(source.id)!!.id shouldBe target.id
            repository.getCreatorWorkArchive(source.id).pending.single().naturalKey.stableSourceUrl shouldBe
                "/redirected"
        }
    }

    @Test
    fun `identity readiness is persisted and repeated reads do not rewrite its checkpoint`() {
        runBlocking {
            var now = 10L
            val timed = CreatorRepositoryImpl(handler, clock = { now++ })
            timed.upsertCreator("Ready")
            val completedAt = queryLong(
                "SELECT updated_at FROM author_archive_identity_migrations WHERE migration_key = 'global-exact-name-v1'",
            )

            repeat(3) { timed.getCreator(1L) }

            queryLong(
                "SELECT updated_at FROM author_archive_identity_migrations WHERE migration_key = 'global-exact-name-v1'",
            ) shouldBe completedAt
        }
    }

    @Test
    fun `relationship revision changes only for a semantic graph change`() {
        runBlocking {
            seedManga(id = 500L, source = 5L, url = "/revision", title = "Revision")
            val creator = repository.upsertCreator("Revision Author")
            val before = queryLong("SELECT identity_revision FROM author_archive_creators WHERE _id = ${creator.id}")

            repository.linkMangaCreator(500L, creator.id, CreatorRole.AUTHOR, "Revision Author", 1.0, "first")
            val afterChange =
                queryLong("SELECT identity_revision FROM author_archive_creators WHERE _id = ${creator.id}")
            repository.linkMangaCreator(500L, creator.id, CreatorRole.AUTHOR, "Revision Author", 1.0, "first")

            (afterChange > before) shouldBe true
            queryLong("SELECT identity_revision FROM author_archive_creators WHERE _id = ${creator.id}") shouldBe
                afterChange
        }
    }

    @Test
    fun `source work upsert reports inserted unchanged and updated using the normalized natural key`() {
        runBlocking {
            val key = SourceWorkNaturalKey(7L, "/stable/work")
            repository.upsertSourceWork(
                sourceId = 7L,
                stableSourceUrl = "  /stable/work  ",
                mangaId = null,
                title = "Stable Work",
                authorText = "ONE",
                artistText = null,
                thumbnailUrl = null,
                detailsFetchedAt = 10L,
            ).shouldBeInstanceOf<ArchiveUpsertOutcome.Inserted<SourceWorkNaturalKey>>().value shouldBe key
            repository.upsertSourceWork(
                sourceId = 7L,
                stableSourceUrl = "/stable/work",
                mangaId = null,
                title = "Stable Work",
                authorText = "ONE",
                artistText = null,
                thumbnailUrl = null,
                detailsFetchedAt = 10L,
            ).shouldBeInstanceOf<ArchiveUpsertOutcome.Unchanged<SourceWorkNaturalKey>>().value shouldBe key
            repository.upsertSourceWork(
                sourceId = 7L,
                stableSourceUrl = "/stable/work",
                mangaId = null,
                title = "Stable Work Updated",
                authorText = "ONE",
                artistText = null,
                thumbnailUrl = null,
                detailsFetchedAt = 20L,
            ).shouldBeInstanceOf<ArchiveUpsertOutcome.Updated<SourceWorkNaturalKey>>().value shouldBe key

            queryLong("SELECT COUNT(*) FROM author_archive_source_works") shouldBe 1L
            queryLong("SELECT COUNT(*) FROM discovery_candidates") shouldBe 0L
        }
    }

    @Test
    fun `typed source work metadata never creates or changes a legacy review snapshot`() {
        runBlocking {
            repository.upsertSourceWork(
                sourceId = 7L,
                stableSourceUrl = "/typed/review-boundary",
                mangaId = null,
                title = "Typed Work",
                authorText = "ONE",
                artistText = null,
                thumbnailUrl = null,
                detailsFetchedAt = 10L,
            )
            queryLong(
                "SELECT legacy_review_snapshot IS NULL FROM author_archive_source_works " +
                    "WHERE source_id = 7 AND stable_source_url = '/typed/review-boundary'",
            ) shouldBe 1L

            repository.upsertSourceWork(
                sourceId = 7L,
                stableSourceUrl = "/typed/review-boundary",
                mangaId = null,
                title = "Typed Work Updated",
                authorText = "ONE",
                artistText = null,
                thumbnailUrl = null,
                detailsFetchedAt = 20L,
            )
            queryLong(
                "SELECT legacy_review_snapshot IS NULL FROM author_archive_source_works " +
                    "WHERE source_id = 7 AND stable_source_url = '/typed/review-boundary'",
            ) shouldBe 1L
        }
    }

    @Test
    fun `metadata refresh preserves ignored review state and does not write a legacy candidate`() {
        runBlocking {
            val ignored = repository.upsertDiscoveryCandidate(
                source = 7L,
                url = "/ignored",
                title = "Ignored",
                authorText = "ONE",
                artistText = null,
                languageTag = "BL",
                languageConfidence = 0.9,
                languageEvidence = "SOURCE_LANGUAGE",
                thumbnailUrl = null,
                detailsFetchedAt = 10L,
                state = DiscoveryCandidateState.IGNORED,
            )
            val refreshed = repository.upsertDiscoveryCandidate(
                source = 7L,
                url = "/ignored",
                title = "Ignored Updated",
                authorText = "ONE",
                artistText = null,
                languageTag = "BL",
                languageConfidence = 0.9,
                languageEvidence = "SOURCE_LANGUAGE",
                thumbnailUrl = null,
                detailsFetchedAt = 20L,
                state = DiscoveryCandidateState.NEW,
            )

            refreshed.id shouldBe ignored.id
            refreshed.state shouldBe DiscoveryCandidateState.IGNORED
            refreshed.languageTag shouldBe "und"
            queryLong("SELECT COUNT(*) FROM discovery_candidates") shouldBe 0L
        }
    }

    @Test
    fun `automatic work refresh cannot overwrite a manual confirmed decision`() {
        runBlocking {
            seedManga(id = 99L, source = 7L, url = "/work/99", title = "One Work")
            val creator = repository.upsertCreator("ONE")
            val work = repository.createCanonicalWork("One Work", creator.id, "ja")
            repository.upsertMangaWorkMatch(
                mangaId = 99L,
                workId = work.id,
                confidence = 1.0,
                matchReason = "manual",
                state = WorkMatchState.CONFIRMED,
                manuallyConfirmed = true,
            )

            val refreshed = repository.upsertMangaWorkMatch(
                mangaId = 99L,
                workId = work.id,
                confidence = 0.4,
                matchReason = "algorithm refresh",
                state = WorkMatchState.CANDIDATE,
                manuallyConfirmed = false,
            )

            refreshed.state shouldBe WorkMatchState.CONFIRMED
            refreshed.manuallyConfirmed shouldBe true
            queryLong("SELECT COUNT(*) FROM manga_work_matches") shouldBe 0L
            queryLong("SELECT COUNT(*) FROM author_archive_canonical_versions") shouldBe 1L
        }
    }

    @Test
    fun `typed source work metadata preserves user authority including roles`() {
        runBlocking {
            val creator = repository.upsertCreator("ONE")
            val key = SourceWorkNaturalKey(7L, "/typed/relation")
            repository.upsertSourceWork(7L, key.stableSourceUrl, null, "Typed", "ONE", null, null, 1L)

            repository.upsertSourceWorkCreator(
                sourceWork = key,
                creatorId = creator.id,
                role = CreatorRole.AUTHOR,
                order = 0,
                origin = CreatorRelationOrigin.USER,
                verification = CreatorRelationVerification.VERIFIED,
                sourceText = "ONE",
                confidence = 1.0,
                evidence = "user binding",
            ).shouldBeInstanceOf<ArchiveUpsertOutcome.Inserted<SourceWorkNaturalKey>>()
            repository.upsertSourceWorkCreator(
                sourceWork = key,
                creatorId = creator.id,
                role = CreatorRole.UNKNOWN,
                order = 0,
                origin = CreatorRelationOrigin.AUTOMATIC,
                verification = CreatorRelationVerification.POSSIBLE,
                sourceText = "ONE?",
                confidence = 0.3,
                evidence = "scan refresh",
            ).shouldBeInstanceOf<ArchiveUpsertOutcome.Unchanged<SourceWorkNaturalKey>>()
            repository.upsertSourceWorkCreator(
                sourceWork = key,
                creatorId = creator.id,
                role = CreatorRole.ARTIST,
                order = 1,
                origin = CreatorRelationOrigin.RESTORE,
                verification = CreatorRelationVerification.VERIFIED,
                sourceText = "restored stale binding",
                confidence = 1.0,
                evidence = "restore must not replace user",
            ).shouldBeInstanceOf<ArchiveUpsertOutcome.Unchanged<SourceWorkNaturalKey>>()

            queryString("SELECT role || ':' || origin FROM author_archive_source_work_creators") shouldBe "AUTHOR:USER"
            queryLong("SELECT COUNT(*) FROM author_archive_source_work_creators") shouldBe 1L
        }
    }

    @Test
    fun `migration relation cannot overwrite a restored binding or advance its timestamp`() {
        runBlocking {
            val creator = repository.upsertCreator("ONE")
            val key = SourceWorkNaturalKey(7L, "/typed/restored-relation")
            repository.upsertSourceWork(7L, key.stableSourceUrl, null, "Typed", "ONE", null, null, 1L)
            repository.upsertSourceWorkCreator(
                sourceWork = key,
                creatorId = creator.id,
                role = CreatorRole.AUTHOR,
                order = 0,
                origin = CreatorRelationOrigin.RESTORE,
                verification = CreatorRelationVerification.VERIFIED,
                sourceText = "ONE",
                confidence = 1.0,
                evidence = "restored binding",
            )
            driver.execute(
                null,
                "UPDATE author_archive_source_work_creators SET last_modified_at = 10",
                0,
            )

            repository.upsertSourceWorkCreator(
                sourceWork = key,
                creatorId = creator.id,
                role = CreatorRole.UNKNOWN,
                order = 1,
                origin = CreatorRelationOrigin.MIGRATION,
                verification = CreatorRelationVerification.POSSIBLE,
                sourceText = "ONE?",
                confidence = 0.2,
                evidence = "stale migration",
            ).shouldBeInstanceOf<ArchiveUpsertOutcome.Unchanged<SourceWorkNaturalKey>>()

            queryString(
                "SELECT role || ':' || origin || ':' || last_modified_at " +
                    "FROM author_archive_source_work_creators",
            ) shouldBe "AUTHOR:RESTORE:10"
        }
    }

    @Test
    fun `typed decision append is immutable and reports idempotency conflicts`() {
        runBlocking {
            val key = SourceWorkNaturalKey(7L, "/typed/decision")
            repository.upsertSourceWork(7L, key.stableSourceUrl, null, "Typed", null, null, null, 1L)
            val work = repository.createCanonicalWork("Typed", null, null)
            val decision = WorkDecisionContract(WorkDecisionState.CONFIRMED, DecisionActor.USER, explicit = true)

            repository.appendWorkDecision(
                sourceWork = key,
                workId = work.id,
                decision = decision,
                algorithmVersion = null,
                score = 1.0,
                evidence = "explicit confirmation",
                decidedAt = 10L,
                idempotencyKey = "decision-1",
            ).shouldBeInstanceOf<ArchiveAppendOutcome.Inserted<WorkDecisionContract>>()
            repository.appendWorkDecision(
                sourceWork = key,
                workId = work.id,
                decision = decision,
                algorithmVersion = null,
                score = 1.0,
                evidence = "explicit confirmation",
                decidedAt = 10L,
                idempotencyKey = "decision-1",
            ).shouldBeInstanceOf<ArchiveAppendOutcome.Unchanged<WorkDecisionContract>>()
            repository.appendWorkDecision(
                sourceWork = key,
                workId = work.id,
                decision = decision,
                algorithmVersion = null,
                score = 0.5,
                evidence = "different payload",
                decidedAt = 11L,
                idempotencyKey = "decision-1",
            ).shouldBeInstanceOf<ArchiveAppendOutcome.Conflict<WorkDecisionContract>>()

            queryLong("SELECT COUNT(*) FROM author_archive_work_decisions") shouldBe 1L
            queryString("SELECT evidence FROM author_archive_work_decisions") shouldBe "explicit confirmation"
        }
    }

    @Test
    fun `work decision projection returns the effective manual decision by natural key`() = runBlocking<Unit> {
        val key = SourceWorkNaturalKey(7L, "/typed/projection")
        repository.upsertSourceWork(7L, key.stableSourceUrl, null, "Projected", null, null, null, 1L)
        val work = repository.createCanonicalWork("Canonical projected work", null, null)
        repository.appendWorkDecision(
            sourceWork = key,
            workId = work.id,
            decision = WorkDecisionContract(WorkDecisionState.SUGGESTED, DecisionActor.ALGORITHM, explicit = false),
            algorithmVersion = "v1",
            score = 0.91,
            evidence = "algorithm suggestion",
            decidedAt = 1L,
            idempotencyKey = "projection-algorithm",
        )
        repository.appendWorkDecision(
            sourceWork = key,
            workId = work.id,
            decision = WorkDecisionContract(WorkDecisionState.REJECTED, DecisionActor.USER, explicit = true),
            algorithmVersion = null,
            score = 0.91,
            evidence = "user kept separate",
            decidedAt = 2L,
            idempotencyKey = "projection-user",
        )

        val projected = repository.getWorkDecisions(key).single()

        projected.workId shouldBe work.id
        projected.workTitle shouldBe "Canonical projected work"
        projected.decision shouldBe
            WorkDecisionContract(WorkDecisionState.REJECTED, DecisionActor.USER, explicit = true)
        projected.evidence shouldBe "user kept separate"
    }

    @Test
    fun `creator archive groups confirmed pending and rejected source works reactively`() {
        runBlocking {
            val creator = repository.upsertCreator("Jane")
            val confirmed = SourceWorkNaturalKey(7L, "/grouped/confirmed")
            val pending = SourceWorkNaturalKey(8L, "/grouped/pending")
            val rejected = SourceWorkNaturalKey(9L, "/grouped/rejected")
            listOf(confirmed, pending, rejected).forEachIndexed { index, sourceWork ->
                repository.upsertSourceWork(
                    sourceWork.sourceId,
                    sourceWork.stableSourceUrl,
                    null,
                    "Work ${index + 1}",
                    "Jane",
                    null,
                    null,
                    index.toLong(),
                )
                repository.upsertSourceWorkCreator(
                    sourceWork,
                    creator.id,
                    CreatorRole.AUTHOR,
                    0L,
                    CreatorRelationOrigin.USER,
                    CreatorRelationVerification.VERIFIED,
                    "Jane",
                    1.0,
                    "manual identity",
                )
            }
            val work = repository.createCanonicalWork("Grouped work", creator.id, null)
            repository.appendWorkDecision(
                confirmed,
                work.id,
                WorkDecisionContract(WorkDecisionState.CONFIRMED, DecisionActor.USER, true),
                null,
                1.0,
                "same work",
                10L,
                "grouped-confirmed",
            )
            repository.appendWorkDecision(
                rejected,
                work.id,
                WorkDecisionContract(WorkDecisionState.REJECTED, DecisionActor.USER, true),
                null,
                0.8,
                "keep separate",
                11L,
                "grouped-rejected",
            )
            listOf("en", "ja").forEach { tag ->
                repository.appendLanguageAssertion(
                    ArchiveLanguageSubject.SourceWork(pending),
                    LanguageAssertionContract(
                        LanguageDimension.READING,
                        tag,
                        1.0,
                        LanguageEvidenceKind.STRUCTURED_METADATA,
                    ),
                    DecisionActor.ALGORITHM,
                    "conflicting structured metadata",
                    "v1",
                    9L,
                    "grouped-language-$tag",
                )
            }

            val archive = repository.observeCreatorWorkArchive(creator.id).first()

            archive.works.single().versions.single().naturalKey shouldBe confirmed
            archive.pending.single().naturalKey shouldBe pending
            archive.pending.single().readingLanguage.certainty shouldBe LanguageCertainty.CONFLICT
            archive.rejected.single().naturalKey shouldBe rejected
            repository.getCreatorWorkArchive(creator.id) shouldBe archive
            val reviewed = repository.appendUserWorkDecisionIfCurrent(
                sourceWork = confirmed,
                workId = work.id,
                state = WorkDecisionState.REJECTED,
                expectedDecidedAt = 10L,
                score = 1.0,
                evidence = "new rejection",
                decidedAt = 12L,
                idempotencyKey = "grouped-new-rejection",
            )
            repository.appendUserWorkDecisionIfCurrent(
                sourceWork = confirmed,
                workId = work.id,
                state = WorkDecisionState.REJECTED,
                expectedDecidedAt = 10L,
                score = 1.0,
                evidence = "new rejection",
                decidedAt = 12L,
                idempotencyKey = "grouped-new-rejection",
            ) shouldBe reviewed
            shouldThrow<StaleWorkDecisionException> {
                repository.appendUserWorkDecisionIfCurrent(
                    sourceWork = confirmed,
                    workId = work.id,
                    state = WorkDecisionState.REJECTED,
                    expectedDecidedAt = 9L,
                    score = 1.0,
                    evidence = "stale rejection",
                    decidedAt = 12L,
                    idempotencyKey = "stale-grouped-rejection",
                )
            }
        }
    }

    @Test
    fun `canonical version follows effective decisions instead of late lower authority events`() {
        runBlocking {
            val sourceWork = SourceWorkNaturalKey(7L, "/decision/effective")
            repository.upsertSourceWork(
                sourceId = sourceWork.sourceId,
                stableSourceUrl = sourceWork.stableSourceUrl,
                mangaId = null,
                title = "Effective Decision",
                authorText = null,
                artistText = null,
                thumbnailUrl = null,
                detailsFetchedAt = null,
            )
            val workA = repository.createCanonicalWork("Work A", null, null)
            val workB = repository.createCanonicalWork("Work B", null, null)

            repository.appendWorkDecision(
                sourceWork = sourceWork,
                workId = workA.id,
                decision = WorkDecisionContract(WorkDecisionState.CONFIRMED, DecisionActor.RESTORE, explicit = true),
                algorithmVersion = null,
                score = 1.0,
                evidence = "first restore confirmed A",
                decidedAt = 1L,
                idempotencyKey = "first-restore-confirm-a",
            )
            repository.appendWorkDecision(
                sourceWork = sourceWork,
                workId = workA.id,
                decision = WorkDecisionContract(WorkDecisionState.REJECTED, DecisionActor.RESTORE, explicit = true),
                algorithmVersion = null,
                score = 1.0,
                evidence = "later restore with an older timestamp rejected A",
                decidedAt = 0L,
                idempotencyKey = "later-restore-reject-a",
            )
            queryLong("SELECT work_id FROM author_archive_canonical_versions") shouldBe workA.id

            repository.appendWorkDecision(
                sourceWork = sourceWork,
                workId = workA.id,
                decision = WorkDecisionContract(WorkDecisionState.CONFIRMED, DecisionActor.USER, explicit = true),
                algorithmVersion = null,
                score = 1.0,
                evidence = "user confirmed A",
                decidedAt = 10L,
                idempotencyKey = "user-confirm-a",
            )
            queryLong("SELECT work_id FROM author_archive_canonical_versions") shouldBe workA.id

            repository.appendWorkDecision(
                sourceWork = sourceWork,
                workId = workA.id,
                decision = WorkDecisionContract(WorkDecisionState.REJECTED, DecisionActor.RESTORE, explicit = true),
                algorithmVersion = null,
                score = 1.0,
                evidence = "late restore rejected A",
                decidedAt = 20L,
                idempotencyKey = "restore-reject-a",
            )
            queryLong("SELECT work_id FROM author_archive_canonical_versions") shouldBe workA.id

            repository.appendWorkDecision(
                sourceWork = sourceWork,
                workId = workB.id,
                decision = WorkDecisionContract(WorkDecisionState.CONFIRMED, DecisionActor.RESTORE, explicit = true),
                algorithmVersion = null,
                score = 1.0,
                evidence = "late restore confirmed B",
                decidedAt = 30L,
                idempotencyKey = "restore-confirm-b",
            )
            queryLong("SELECT work_id FROM author_archive_canonical_versions") shouldBe workA.id

            repository.appendWorkDecision(
                sourceWork = sourceWork,
                workId = workA.id,
                decision = WorkDecisionContract(WorkDecisionState.REJECTED, DecisionActor.USER, explicit = true),
                algorithmVersion = null,
                score = 1.0,
                evidence = "user rejected A",
                decidedAt = 40L,
                idempotencyKey = "user-reject-a",
            )
            queryLong("SELECT work_id FROM author_archive_canonical_versions") shouldBe workB.id

            repository.appendWorkDecision(
                sourceWork = sourceWork,
                workId = workB.id,
                decision = WorkDecisionContract(WorkDecisionState.REJECTED, DecisionActor.USER, explicit = true),
                algorithmVersion = null,
                score = 1.0,
                evidence = "user rejected B",
                decidedAt = 50L,
                idempotencyKey = "user-reject-b",
            )
            repository.appendWorkDecision(
                sourceWork = sourceWork,
                workId = workB.id,
                decision = WorkDecisionContract(WorkDecisionState.CONFIRMED, DecisionActor.RESTORE, explicit = true),
                algorithmVersion = null,
                score = 1.0,
                evidence = "later restore confirmed B again",
                decidedAt = 60L,
                idempotencyKey = "restore-confirm-b-again",
            )
            queryLong("SELECT COUNT(*) FROM author_archive_canonical_versions") shouldBe 0L
        }
    }

    @Test
    fun `typed language append is immutable and uses a portable source work subject key`() {
        runBlocking {
            val key = SourceWorkNaturalKey(7L, "/typed/language")
            repository.upsertSourceWork(7L, key.stableSourceUrl, null, "Typed", null, null, null, 1L)
            val assertion = LanguageAssertionContract(
                dimension = LanguageDimension.READING,
                tag = "pt-BR",
                confidence = 1.0,
                evidenceKind = LanguageEvidenceKind.MANUAL,
            )

            repository.appendLanguageAssertion(
                subject = ArchiveLanguageSubject.SourceWork(key),
                assertion = assertion,
                actor = DecisionActor.USER,
                evidencePayload = "manual language",
                algorithmVersion = null,
                assertedAt = 10L,
                idempotencyKey = "language-1",
            ).shouldBeInstanceOf<ArchiveAppendOutcome.Inserted<LanguageAssertionContract>>()
            repository.appendLanguageAssertion(
                subject = ArchiveLanguageSubject.SourceWork(key),
                assertion = assertion.copy(tag = "ja"),
                actor = DecisionActor.USER,
                evidencePayload = "different payload",
                algorithmVersion = null,
                assertedAt = 11L,
                idempotencyKey = "language-1",
            ).shouldBeInstanceOf<ArchiveAppendOutcome.Conflict<LanguageAssertionContract>>()

            queryLong("SELECT COUNT(*) FROM author_archive_language_assertions") shouldBe 1L
            queryString("SELECT subject_key FROM author_archive_language_assertions") shouldBe
                "source:7:/typed/language"
            queryString("SELECT language_tag FROM author_archive_language_assertions") shouldBe "pt-br"
        }
    }

    @Test
    fun `manual language override survives rescans and withdrawal restores automatic evidence`() {
        runBlocking {
            val key = SourceWorkNaturalKey(7L, "/typed/manual-language")
            repository.upsertSourceWork(7L, key.stableSourceUrl, null, "Language", null, null, null, 1L)
            val subject = ArchiveLanguageSubject.SourceWork(key)
            repository.appendLanguageAssertion(
                subject,
                LanguageAssertionContract(
                    LanguageDimension.READING,
                    "ja",
                    1.0,
                    LanguageEvidenceKind.STRUCTURED_METADATA,
                ),
                DecisionActor.ALGORITHM,
                "structured metadata",
                "v1",
                1L,
                "language-automatic-ja",
            )
            repository.setManualLanguage(subject, LanguageDimension.READING, "zh-Hant", 2L)
            repository.appendLanguageAssertion(
                subject,
                LanguageAssertionContract(
                    LanguageDimension.READING,
                    "ko",
                    0.8,
                    LanguageEvidenceKind.TEXT_DETECTION,
                ),
                DecisionActor.ALGORITHM,
                "later text scan",
                "v2",
                3L,
                "language-automatic-ko",
            )

            repository.getLanguageProjection(subject, LanguageDimension.READING).tag shouldBe "zh-hant"

            repository.withdrawManualLanguage(subject, LanguageDimension.READING, 4L)
            val restored = repository.getLanguageProjection(subject, LanguageDimension.READING)
            restored.tag shouldBe "ja"
            restored.certainty shouldBe LanguageCertainty.CONFIRMED
        }
    }

    @Test
    fun `creator archive projects reading and original language independently`() {
        runBlocking {
            val creator = repository.upsertCreator("Language Author")
            val work = SourceWorkNaturalKey(17L, "/language-work")
            repository.upsertSourceWork(17L, work.stableSourceUrl, null, "Language Work", null, null, null, 2L)
            repository.upsertSourceWorkCreator(
                work,
                creator.id,
                CreatorRole.AUTHOR,
                0,
                CreatorRelationOrigin.USER,
                CreatorRelationVerification.VERIFIED,
                "Language Author",
                1.0,
                "test",
            )
            repository.appendLanguageAssertion(
                ArchiveLanguageSubject.SourceWork(work),
                LanguageAssertionContract(
                    LanguageDimension.READING,
                    "en",
                    1.0,
                    LanguageEvidenceKind.STRUCTURED_METADATA,
                ),
                DecisionActor.ALGORITHM,
                "reading",
                "test-v1",
                3L,
                "reading-language",
            )
            repository.appendLanguageAssertion(
                ArchiveLanguageSubject.SourceWork(work),
                LanguageAssertionContract(
                    LanguageDimension.ORIGINAL,
                    "ja",
                    1.0,
                    LanguageEvidenceKind.STRUCTURED_METADATA,
                ),
                DecisionActor.ALGORITHM,
                "original",
                "test-v1",
                4L,
                "original-language",
            )

            val version = repository.getCreatorWorkArchive(creator.id).pending.single()

            version.readingLanguage.tag shouldBe "en"
            version.originalLanguage.tag shouldBe "ja"
            version.originalLanguage.dimension shouldBe LanguageDimension.ORIGINAL
        }
    }

    @Test
    fun `chapter variants replace atomically and preserve split and raw names`() {
        runBlocking {
            val work = SourceWorkNaturalKey(17L, "/chapter-work")
            repository.upsertSourceWork(17L, work.stableSourceUrl, null, "Chapter Work", null, null, null, 1L)
            val split = ChapterVariantRecord(
                naturalKey = "/chapter-1-part-2",
                rawName = "Ch. 1 Part 2",
                scanlator = "A",
                volumeNumber = null,
                chapterNumber = 1.0,
                partNumber = 2.0,
                type = ChapterVariantType.SPLIT,
                confidence = 0.9,
                evidence = "part token",
            )

            repository.replaceChapterVariants(work, listOf(split), 2L)
            repository.getChapterVariants(work).single().copy(scanlator = "A", confidence = 0.9) shouldBe split

            repository.replaceChapterVariants(work, emptyList(), 3L)
            repository.getChapterVariants(work) shouldBe emptyList()
        }
    }

    @Test
    fun `upsertCreator uses exact name rather than search normalization`() {
        runBlocking {
            val first = repository.upsertCreator(" ONE ")
            val second = repository.upsertCreator("one")

            (first.id != second.id) shouldBe true
            repository.getCreator(first.id) shouldBe first
            second.normalizedName shouldBe "one"
            repository.getCreatorsAsFlow().first().size shouldBe 2
        }
    }

    @Test
    fun `upsertCreator returns a readable CJK author immediately after insert`() {
        runBlocking {
            val creator = repository.upsertCreator("藤本树")

            repository.getCreator(creator.id) shouldBe creator
            creator.displayName shouldBe "藤本树"
        }
    }

    @Test
    fun `followed creators persist source and language filters`() {
        runBlocking {
            val creator = repository.upsertCreator("Inio Asano")
            repository.followCreator(creator.id, sourceIds = listOf(2L, 1L), languageTags = listOf("ja", "en"))

            val watch = repository.getFollowedCreators().single()
            watch.creatorId shouldBe creator.id
            watch.sourceIds.shouldContainExactly(1L, 2L)
            watch.languageTags.shouldContainExactly("en", "ja")
        }
    }

    @Test
    fun `discovery candidates are deduped by source and url`() {
        runBlocking {
            val creator = repository.upsertCreator("ONE")
            val first = repository.upsertDiscoveryCandidate(
                source = 1L,
                url = "/manga/one",
                title = "One Work",
                authorText = "ONE",
                artistText = null,
                languageTag = "en",
                languageConfidence = 0.65,
                languageEvidence = "SOURCE_LANGUAGE",
                thumbnailUrl = null,
                detailsFetchedAt = null,
            )
            repository.linkDiscoveryCandidateCreator(first.id, creator.id, CreatorRole.AUTHOR, "ONE", 1.0, "test")
            val second = repository.upsertDiscoveryCandidate(
                source = 1L,
                url = "/manga/one",
                title = "One Work Updated",
                authorText = "ONE",
                artistText = null,
                languageTag = "ja",
                languageConfidence = 1.0,
                languageEvidence = "EXPLICIT_METADATA",
                thumbnailUrl = "https://example.com/cover.jpg",
                detailsFetchedAt = 20L,
            )

            first.id shouldBe second.id
            second.title shouldBe "One Work Updated"
            second.languageTag shouldBe "ja"
            repository.getDiscoveryCandidatesForCreator(creator.id).single().id shouldBe first.id
            repository.getMangaCreatorsForCreator(creator.id).size shouldBe 0
        }
    }

    @Test
    fun `discovery candidate can be loaded by id for comparison`() {
        runBlocking {
            val candidate = repository.upsertDiscoveryCandidate(
                source = 7L,
                url = "/manga/compare",
                title = "Compare Work",
                authorText = "ONE",
                artistText = "Yusuke Murata",
                languageTag = "en",
                languageConfidence = 0.75,
                languageEvidence = "SOURCE_LANGUAGE",
                thumbnailUrl = "https://example.com/cover.jpg",
                detailsFetchedAt = 200L,
            )

            repository.getDiscoveryCandidate(candidate.id) shouldBe candidate
        }
    }

    @Test
    fun `manga creator links can be listed by creator`() {
        runBlocking {
            seedManga(id = 11L, source = 1L, url = "/manga/11", title = "Eleven")
            seedManga(id = 12L, source = 1L, url = "/manga/12", title = "Twelve")
            val creator = repository.upsertCreator("ONE")
            repository.linkMangaCreator(11L, creator.id, CreatorRole.UNKNOWN, "ONE", 0.4, "search")
            repository.linkMangaCreator(11L, creator.id, CreatorRole.AUTHOR, "ONE", 1.0, "manual")
            repository.linkMangaCreator(12L, creator.id, CreatorRole.ARTIST, "ONE", 0.8, "details")

            val links = repository.getMangaCreatorsForCreator(creator.id)

            links.map { it.mangaId }.shouldContainExactly(11L, 12L)
            links.map { it.role }.shouldContainExactly(CreatorRole.AUTHOR, CreatorRole.ARTIST)
            repository.getMangaTitlesForCreator(creator.id) shouldBe mapOf(11L to "Eleven", 12L to "Twelve")
            queryLong("SELECT COUNT(*) FROM author_archive_manga_links WHERE manga_id = 11") shouldBe 1L
            queryLong("SELECT COUNT(*) FROM manga_creators") shouldBe 0L
        }
    }

    @Test
    fun `discovery candidate links can be listed by creator without polluting manga links`() {
        runBlocking {
            val creator = repository.upsertCreator("ONE")
            val candidate = repository.upsertDiscoveryCandidate(
                source = 1L,
                url = "/manga/candidate",
                title = "Candidate Work",
                authorText = "ONE",
                artistText = null,
                languageTag = "en",
                languageConfidence = 0.75,
                languageEvidence = "SOURCE_LANGUAGE",
                thumbnailUrl = null,
                detailsFetchedAt = null,
            )

            repository.linkDiscoveryCandidateCreator(candidate.id, creator.id, CreatorRole.AUTHOR, "ONE", 0.8, "search")

            repository.getDiscoveryCandidateCreatorsForCreator(creator.id).single().candidateId shouldBe candidate.id
            repository.getDiscoveryCandidatesForCreator(creator.id).single().id shouldBe candidate.id
            repository.getMangaCreatorsForCreator(creator.id).size shouldBe 0
        }
    }

    @Test
    fun `canonical work match can be confirmed`() {
        runBlocking {
            seedManga(id = 99L, source = 7L, url = "/manga/99", title = "One Work")
            val creator = repository.upsertCreator("ONE")
            val work = repository.createCanonicalWork("One Work", creator.id, "ja")

            val match = repository.upsertMangaWorkMatch(
                mangaId = 99L,
                workId = work.id,
                confidence = 0.95,
                matchReason = "manual",
                state = WorkMatchState.CONFIRMED,
                manuallyConfirmed = true,
            )

            match.workId shouldBe work.id
            match.state shouldBe WorkMatchState.CONFIRMED
            match.manuallyConfirmed shouldBe true
        }
    }

    @Test
    fun `candidate state defaults to new`() {
        runBlocking {
            val candidate = repository.upsertDiscoveryCandidate(
                source = 1L,
                url = "/manga/new",
                title = "New Work",
                authorText = null,
                artistText = null,
                languageTag = "unknown",
                languageConfidence = 0.0,
                languageEvidence = "UNKNOWN",
                thumbnailUrl = null,
                detailsFetchedAt = null,
            )

            candidate.state shouldBe DiscoveryCandidateState.NEW
        }
    }

    private fun seedManga(id: Long, source: Long, url: String, title: String) {
        driver.execute(
            null,
            "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, chapter_flags, " +
                "cover_last_modified, date_added) VALUES (?, ?, ?, ?, 0, 1, 0, 0, 0, 0, 0)",
            4,
        ) {
            bindLong(0, id)
            bindLong(1, source)
            bindString(2, url)
            bindString(3, title)
        }
    }

    private fun queryLong(sql: String): Long = driver.executeQuery(
        null,
        sql,
        { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else -1L) },
        0,
    ).value

    private fun queryString(sql: String): String? = driver.executeQuery(
        null,
        sql,
        { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
        0,
    ).value

    private fun databaseFor(sqlDriver: JdbcSqliteDriver) = Database(
        driver = sqlDriver,
        historyAdapter = tachiyomi.data.History.Adapter(last_readAdapter = DateColumnAdapter),
        mangasAdapter = tachiyomi.data.Mangas.Adapter(
            genreAdapter = StringListColumnAdapter,
            update_strategyAdapter = UpdateStrategyColumnAdapter,
        ),
    )

    private fun queryLong(sqlDriver: JdbcSqliteDriver, sql: String): Long = sqlDriver.executeQuery(
        null,
        sql,
        { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else -1L) },
        0,
    ).value

    private fun installLegacyWriteGuards() {
        LEGACY_CREATOR_TABLES.forEach { table ->
            listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                val trigger = "reject_${table}_${operation.lowercase()}"
                driver.execute(
                    null,
                    "CREATE TRIGGER $trigger BEFORE $operation ON $table " +
                        "BEGIN SELECT RAISE(FAIL, 'legacy creator write rejected'); END",
                    0,
                )
            }
        }
    }

    private companion object {
        val LEGACY_CREATOR_TABLES = listOf(
            "creators",
            "manga_creators",
            "discovery_candidate_creators",
            "creator_watches",
            "canonical_works",
            "manga_work_matches",
            "discovery_candidates",
        )
    }
}
