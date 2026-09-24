package tachiyomi.data.creator

import app.cash.sqldelight.Transacter.Transaction
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.creator.model.AddCreatorAliasesRequest
import tachiyomi.domain.creator.model.CreatorCardProjection
import tachiyomi.domain.creator.model.CreatorRelationOrigin
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.DecisionActor
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.WorkDecisionContract
import tachiyomi.domain.creator.model.WorkDecisionState
import tachiyomi.domain.creator.service.WorkPresentationGroupService
import java.nio.file.Files

class CreatorCardProjectionTest {

    private lateinit var driver: ProjectionCountingDriver
    private lateinit var repository: CreatorRepositoryImpl
    private lateinit var database: Database

    @BeforeEach
    fun setup() {
        driver = ProjectionCountingDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        database = Database(
            driver = driver,
            historyAdapter = tachiyomi.data.History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
            ),
        )
        repository = CreatorRepositoryImpl(JvmDatabaseHandler(database, driver))
    }

    @Test
    fun `creator card query pages at fifty and projects real follow favorite history and canonical work data`() =
        runBlocking {
            val followed = repository.upsertCreator("Creator 000")
            val creators = (1..50).map { repository.upsertCreator("Creator ${it.toString().padStart(3, '0')}") }
            repository.followCreator(followed.id)

            seedManga(101L, 11L, "/first", "First edition", favorite = true)
            seedManga(102L, 12L, "/second", "Second edition", favorite = false)
            driver.execute(
                null,
                "UPDATE mangas SET thumbnail_url = 'file:///custom-cover.webp', " +
                    "cover_last_modified = 777 WHERE _id = 101",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read, chapter_number, " +
                    "source_order, date_fetch, date_upload) VALUES (201, 101, '/chapter/1', '1', 1, 0, 0, 1, 1, 0, 0)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO history(_id, chapter_id, last_read, time_read) VALUES (301, 201, 123456, 1)",
                0,
            )

            val canonical = repository.createCanonicalWork("Shared title", followed.id, null)
            val first = SourceWorkNaturalKey(11L, "/first")
            val second = SourceWorkNaturalKey(12L, "/second")
            listOf(first to 101L, second to 102L).forEach { (key, mangaId) ->
                repository.upsertSourceWork(
                    key.sourceId,
                    key.stableSourceUrl,
                    mangaId,
                    "Shared title",
                    "Creator 000",
                    null,
                    "https://source.invalid/${key.sourceId}.jpg",
                    50L,
                )
                repository.upsertSourceWorkCreator(
                    key,
                    followed.id,
                    CreatorRole.AUTHOR,
                    0,
                    CreatorRelationOrigin.USER,
                    CreatorRelationVerification.VERIFIED,
                    "Creator 000",
                    1.0,
                    "verified fixture",
                )
                repository.appendWorkDecision(
                    key,
                    canonical.id,
                    WorkDecisionContract(WorkDecisionState.CONFIRMED, DecisionActor.USER, explicit = true),
                    null,
                    1.0,
                    "same work",
                    60L,
                    "same-work-${key.sourceId}",
                )
            }

            val guess = SourceWorkNaturalKey(13L, "/possible")
            repository.upsertSourceWork(
                13L,
                guess.stableSourceUrl,
                null,
                "Possible guess",
                "Creator 000",
                null,
                null,
                70L,
            )
            repository.upsertSourceWorkCreator(
                guess,
                followed.id,
                CreatorRole.AUTHOR,
                0,
                CreatorRelationOrigin.AUTOMATIC,
                CreatorRelationVerification.POSSIBLE,
                "Creator 000?",
                0.4,
                "search guess",
            )

            driver.resetProjectionQueryCount()
            val firstPage = repository.getCreatorCardProjectionPage(offset = 0, limit = 500, followedOnly = false)
            assertEquals(1, driver.projectionQueryCount)

            driver.resetProjectionQueryCount()
            val followedPage = repository.getCreatorCardProjectionPage(offset = 0, limit = 50, followedOnly = true)
            assertEquals(1, driver.projectionQueryCount)

            assertEquals(50, firstPage.creators.size)
            assertTrue(firstPage.hasMore)
            assertEquals(followed.id, firstPage.creators.first().creator.id)
            assertTrue(firstPage.creators.first().followed)
            assertEquals(1, firstPage.creators.first().uniqueWorkCount)
            assertEquals(
                setOf(CreatorRelationVerification.VERIFIED),
                firstPage.creators.first().representativeWorks.map { it.relationVerification }.toSet(),
            )
            assertEquals(
                1L,
                queryLong(
                    driver,
                    "SELECT COUNT(*) FROM author_archive_source_work_creators SWC " +
                        "JOIN author_archive_source_works SW ON SW._id = SWC.source_work_id " +
                        "WHERE SW.stable_source_url = '/possible' AND SWC.verification = 'POSSIBLE'",
                ),
            )
            val localCover = firstPage.creators.first().representativeWorks.single { it.coverRequest.mangaId == 101L }
            assertTrue(localCover.inLibrary)
            assertEquals(123456L, localCover.lastReadAt)
            assertEquals(11L, localCover.coverRequest.sourceId)
            assertEquals("file:///custom-cover.webp", localCover.coverRequest.url)
            assertEquals(777L, localCover.coverRequest.lastModifiedAt)
            assertEquals(listOf(followed.id), followedPage.creators.map { it.creator.id })

            driver.resetProjectionQueryCount()
            val secondPage = repository.getCreatorCardProjectionPage(offset = 50, limit = 500, followedOnly = false)
            assertEquals(1, driver.projectionQueryCount)
            assertEquals(1, secondPage.creators.size)
            assertFalse(secondPage.hasMore)
            assertEquals(creators.last().id, secondPage.creators.single().creator.id)
        }

    @Test
    fun `card and detail attach a pending title to any canonical member title`() = runBlocking {
        val creator = repository.upsertCreator("Variant Title Author")
        val canonical = repository.createCanonicalWork("Unrelated primary title", creator.id, null)
        val first = SourceWorkNaturalKey(901L, "/canonical-first")
        val second = SourceWorkNaturalKey(902L, "/canonical-second")
        val pending = SourceWorkNaturalKey(903L, "/pending-variant")
        addVerifiedWork(repository, creator.id, first.sourceId, first.stableSourceUrl, "Unrelated primary title")
        addVerifiedWork(repository, creator.id, second.sourceId, second.stableSourceUrl, "詭譎屋")
        addVerifiedWork(repository, creator.id, pending.sourceId, pending.stableSourceUrl, "诡谲屋")
        listOf(first, second).forEach { key ->
            repository.appendWorkDecision(
                key,
                canonical.id,
                WorkDecisionContract(WorkDecisionState.CONFIRMED, DecisionActor.USER, explicit = true),
                null,
                1.0,
                "confirmed edition",
                100L,
                "variant-title-${key.sourceId}",
            )
        }

        val detailGroups = WorkPresentationGroupService.project(repository.getCreatorWorkArchive(creator.id))
        val card = repository.getCreatorCardProjectionPage(0, 50, false).creators.single()

        assertEquals(1, detailGroups.size)
        assertEquals(3, detailGroups.single().members.size)
        assertEquals(1, card.uniqueWorkCount)
    }

    @Test
    fun `card matches canonical primary title when source editions have different titles`() = runBlocking {
        val creator = repository.upsertCreator("Primary Title Author")
        val canonical = repository.createCanonicalWork("詭譎屋", creator.id, null)
        val edition = SourceWorkNaturalKey(911L, "/primary-edition")
        val pending = SourceWorkNaturalKey(912L, "/primary-pending")
        addVerifiedWork(repository, creator.id, edition.sourceId, edition.stableSourceUrl, "Another edition title")
        addVerifiedWork(repository, creator.id, pending.sourceId, pending.stableSourceUrl, "诡谲屋")
        repository.appendWorkDecision(
            edition,
            canonical.id,
            WorkDecisionContract(WorkDecisionState.CONFIRMED, DecisionActor.USER, explicit = true),
            null,
            1.0,
            "confirmed edition",
            100L,
            "primary-title-edition",
        )

        val detailGroups = WorkPresentationGroupService.project(repository.getCreatorWorkArchive(creator.id))
        val card = repository.getCreatorCardProjectionPage(0, 50, false).creators.single()

        assertEquals(1, detailGroups.size)
        assertEquals(2, detailGroups.single().members.size)
        assertEquals(1, card.uniqueWorkCount)
    }

    @Test
    fun `card does not match pending titles through an excluded canonical source`() = runBlocking {
        val creator = repository.upsertCreator("Excluded Title Author")
        val canonical = repository.createCanonicalWork("Other primary", creator.id, null)
        val included = SourceWorkNaturalKey(921L, "/included-edition")
        val excluded = SourceWorkNaturalKey(922L, "/excluded-edition")
        val pending = SourceWorkNaturalKey(923L, "/excluded-pending")
        addVerifiedWork(repository, creator.id, included.sourceId, included.stableSourceUrl, "Other primary")
        addVerifiedWork(repository, creator.id, excluded.sourceId, excluded.stableSourceUrl, "詭譎屋")
        addVerifiedWork(repository, creator.id, pending.sourceId, pending.stableSourceUrl, "诡谲屋")
        listOf(included, excluded).forEach { key ->
            repository.appendWorkDecision(
                key,
                canonical.id,
                WorkDecisionContract(WorkDecisionState.CONFIRMED, DecisionActor.USER, explicit = true),
                null,
                1.0,
                "confirmed edition",
                100L,
                "excluded-title-${key.sourceId}",
            )
        }
        repository.setPresentationExclusion(creator.id, excluded, excluded = true, now = 101L)

        val detailGroups = WorkPresentationGroupService.project(
            repository.getCreatorWorkArchive(creator.id),
            repository.getPresentationExclusions(creator.id),
        )
        val card = repository.getCreatorCardProjectionPage(0, 50, false).creators.single()

        assertEquals(3, detailGroups.size)
        assertEquals(3, card.uniqueWorkCount)
    }

    @Test
    fun `creator card search finds an alias after the first fifty indexed authors`() = runBlocking {
        repeat(50) { index -> repository.upsertCreator("Author ${index.toString().padStart(3, '0')}") }
        val lateCreator = repository.upsertCreator("Late Author", aliases = listOf("Searchable pen name"))

        val page = repository.getCreatorCardProjectionPage(
            offset = 0,
            limit = 50,
            followedOnly = false,
            query = "Searchable pen name",
        )

        assertEquals(listOf(lateCreator.id), page.creators.map { it.creator.id })
        assertFalse(page.hasMore)
    }

    @Test
    fun `public creator card projection exposes only at most three representative works`() = runBlocking {
        val creator = repository.upsertCreator("Projection Contract Author")
        for (sourceId in 1L..8L) {
            addVerifiedWork(repository, creator.id, sourceId, "/work-$sourceId", "Work $sourceId")
        }

        val card = repository.getCreatorCardProjectionPage(offset = 0, limit = 50, followedOnly = false)
            .creators.single()

        assertEquals(3, card.representativeWorks.size)
        assertTrue(card.representativeWorks.size <= 3)
        assertFalse(
            CreatorCardProjection::class.java.declaredMethods.any { it.name == "getWorkCandidates" },
            "The public domain projection must not expose all work candidates to UI consumers",
        )
    }

    @Test
    fun `repository selector prefers an enabled source language for a fresh representative`() = runBlocking {
        val creator = repository.upsertCreator("Source Language Author")
        val canonical = repository.createCanonicalWork("Shared edition", creator.id, null)
        driver.execute(
            null,
            "INSERT INTO sources(_id, lang, name) VALUES (11, 'en', 'English source'), (12, 'fr', 'French source')",
            0,
        )
        listOf(
            SourceWorkNaturalKey(11L, "/edition"),
            SourceWorkNaturalKey(12L, "/edition"),
        ).forEach { naturalKey ->
            repository.upsertSourceWork(
                naturalKey.sourceId,
                naturalKey.stableSourceUrl,
                null,
                "Shared edition",
                "Source Language Author",
                null,
                "https://${naturalKey.sourceId}.example/cover.jpg",
                naturalKey.sourceId,
            )
            repository.upsertSourceWorkCreator(
                naturalKey,
                creator.id,
                CreatorRole.AUTHOR,
                0,
                CreatorRelationOrigin.USER,
                CreatorRelationVerification.VERIFIED,
                "Source Language Author",
                1.0,
                "verified language fixture",
            )
            repository.appendWorkDecision(
                naturalKey,
                canonical.id,
                WorkDecisionContract(WorkDecisionState.CONFIRMED, DecisionActor.USER, explicit = true),
                null,
                1.0,
                "same canonical work",
                60L,
                "language-${naturalKey.sourceId}",
            )
        }

        val card = repository.getCreatorCardProjectionPage(
            offset = 0,
            limit = 50,
            followedOnly = false,
            preferredLanguages = setOf("fr"),
        ).creators.single()

        assertEquals(1, card.representativeWorks.size)
        assertEquals(12L, card.representativeWorks.single().naturalKey.sourceId)
        assertEquals("fr", card.representativeWorks.single().sourceLanguage)
    }

    @Test
    fun `repository selector prefers a custom cover confirmed by the platform adapter`() = runBlocking {
        val creator = repository.upsertCreator("Custom Cover Author")
        val canonical = repository.createCanonicalWork("Shared cover edition", creator.id, null)
        val editions = listOf(
            Triple(11L, 101L, "/edition-en"),
            Triple(22L, 202L, "/edition-fr"),
        )
        editions.forEach { (sourceId, mangaId, url) ->
            seedManga(driver, mangaId, sourceId, url, "Shared cover edition", favorite = false)
            val naturalKey = SourceWorkNaturalKey(sourceId, url)
            repository.upsertSourceWork(
                sourceId,
                url,
                mangaId,
                "Shared cover edition",
                "Custom Cover Author",
                null,
                "https://$sourceId.example/cover.jpg",
                sourceId,
            )
            repository.upsertSourceWorkCreator(
                naturalKey,
                creator.id,
                CreatorRole.AUTHOR,
                0,
                CreatorRelationOrigin.USER,
                CreatorRelationVerification.VERIFIED,
                "Custom Cover Author",
                1.0,
                "verified custom cover fixture",
            )
            repository.appendWorkDecision(
                naturalKey,
                canonical.id,
                WorkDecisionContract(WorkDecisionState.CONFIRMED, DecisionActor.USER, explicit = true),
                null,
                1.0,
                "same canonical work",
                60L,
                "custom-cover-$sourceId",
            )
        }

        val card = repository.getCreatorCardProjectionPage(
            offset = 0,
            limit = 50,
            followedOnly = false,
            customCoverExists = { mangaId -> mangaId == 202L },
        ).creators.single()

        assertEquals(22L, card.representativeWorks.single().naturalKey.sourceId)
        assertTrue(card.representativeWorks.single().hasCustomCover)
    }

    @Test
    fun `representative selection persists across reopen without redundant cache writes`() = runBlocking {
        val path = Files.createTempFile("creator-representative-cache-", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        val firstDriver = ProjectionCountingDriver(
            JdbcSqliteDriver(url),
        )
        try {
            Database.Schema.create(firstDriver)
            firstDriver.execute(null, "PRAGMA foreign_keys = ON", 0)
            val firstRepository = repositoryFor(firstDriver)
            val creator = firstRepository.upsertCreator("Cache Author")
            for (sourceId in 10L..13L) {
                addVerifiedWork(firstRepository, creator.id, sourceId, "/work-$sourceId", "Work $sourceId")
            }

            firstDriver.resetQueryCounts()
            val initial = firstRepository.getCreatorCardProjectionPage(offset = 0, limit = 50, followedOnly = false)
            val initialKeys = initial.creators.single().representativeWorks.map { it.workKey }
            assertEquals(3, initialKeys.size)
            assertEquals(3, initialKeys.distinct().size)
            assertEquals(1, firstDriver.projectionQueryCount)
            assertEquals(1, firstDriver.representativeCacheReadCount)
            assertEquals(1, firstDriver.representativeCacheWriteCount)

            firstDriver.resetQueryCounts()
            val unchanged = firstRepository.getCreatorCardProjectionPage(offset = 0, limit = 50, followedOnly = false)
            assertEquals(initialKeys, unchanged.creators.single().representativeWorks.map { it.workKey })
            assertEquals(1, firstDriver.projectionQueryCount)
            assertEquals(1, firstDriver.representativeCacheReadCount)
            assertEquals(0, firstDriver.representativeCacheWriteCount)

            addVerifiedWork(firstRepository, creator.id, 1L, "/same-tier", "Same-tier addition")
            firstDriver.resetQueryCounts()
            val afterSameTierAddition = firstRepository.getCreatorCardProjectionPage(
                offset = 0,
                limit = 50,
                followedOnly = false,
            )
            assertEquals(initialKeys, afterSameTierAddition.creators.single().representativeWorks.map { it.workKey })
            assertEquals(1, firstDriver.projectionQueryCount)
            assertEquals(1, firstDriver.representativeCacheReadCount)
            assertEquals(0, firstDriver.representativeCacheWriteCount)

            firstDriver.close()
            val reopenedUrl = "jdbc:sqlite:${path.toAbsolutePath()}"
            val reopenedDriver = ProjectionCountingDriver(JdbcSqliteDriver(reopenedUrl))
            try {
                reopenedDriver.execute(null, "PRAGMA foreign_keys = ON", 0)
                val reopenedRepository = repositoryFor(reopenedDriver)
                seedManga(reopenedDriver, 501L, 2L, "/promoted", "Promoted", favorite = true)
                seedReadHistory(reopenedDriver, 501L)
                addVerifiedWork(reopenedRepository, creator.id, 2L, "/promoted", "Promoted", mangaId = 501L)

                reopenedDriver.resetQueryCounts()
                val afterPromotion = reopenedRepository.getCreatorCardProjectionPage(
                    offset = 0,
                    limit = 50,
                    followedOnly = false,
                )
                val promoted = afterPromotion.creators.single().representativeWorks
                assertEquals(3, promoted.size)
                assertEquals(3, promoted.map { it.workKey }.distinct().size)
                assertTrue(promoted.any { it.title == "Promoted" })
                assertEquals(1, reopenedDriver.projectionQueryCount)
                assertEquals(1, reopenedDriver.representativeCacheReadCount)
                assertEquals(1, reopenedDriver.representativeCacheWriteCount)
            } finally {
                reopenedDriver.close()
            }
        } finally {
            runCatching { firstDriver.close() }
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `bad strategy cache is rebuilt per author without replacing another author's selection`() = runBlocking {
        val firstCreator = repository.upsertCreator("Cache Author A")
        val secondCreator = repository.upsertCreator("Cache Author B")
        (100L..102L).forEach { sourceId ->
            addVerifiedWork(repository, firstCreator.id, sourceId, "/a-$sourceId", "A $sourceId")
        }
        (200L..202L).forEach { sourceId ->
            addVerifiedWork(repository, secondCreator.id, sourceId, "/b-$sourceId", "B $sourceId")
        }
        seedCache(driver, firstCreator.id, strategyVersion = 99L, payload = "{")
        val secondSelection = listOf(202L, 201L, 200L)
        val secondPayload = secondSelection.joinToString(",") { sourceId ->
            "{\"workKey\":\"source:$sourceId:/b-$sourceId\"," +
                "\"sourceId\":$sourceId,\"stableSourceUrl\":\"/b-$sourceId\"}"
        }.let { "{\"selected\":[$it]}" }
        seedCache(driver, secondCreator.id, strategyVersion = 1L, payload = secondPayload)

        driver.resetQueryCounts()
        val page = repository.getCreatorCardProjectionPage(offset = 0, limit = 50, followedOnly = false)
        val cardsById = page.creators.associateBy { it.creator.id }

        assertEquals(1, driver.projectionQueryCount)
        assertEquals(1, driver.representativeCacheReadCount)
        assertEquals(
            listOf("source:202:/b-202", "source:201:/b-201", "source:200:/b-200"),
            cardsById.getValue(secondCreator.id).representativeWorks.map { it.workKey },
        )
        assertEquals(3, cardsById.getValue(firstCreator.id).representativeWorks.size)
        assertEquals(1, driver.representativeCacheWriteCount)
        assertEquals(
            1L,
            queryLong(
                driver,
                "SELECT strategy_version FROM author_archive_representative_work_cache " +
                    "WHERE creator_id = ${firstCreator.id}",
            ),
        )
        assertEquals(
            secondPayload,
            queryString(
                driver,
                "SELECT payload FROM author_archive_representative_work_cache " +
                    "WHERE creator_id = ${secondCreator.id}",
            ),
        )
    }

    @Test
    fun `page cache updates for multiple authors share one transaction`() = runBlocking {
        val firstCreator = repository.upsertCreator("Batch Author A")
        val secondCreator = repository.upsertCreator("Batch Author B")
        addVerifiedWork(repository, firstCreator.id, 701L, "/batch-a", "Batch A")
        addVerifiedWork(repository, secondCreator.id, 702L, "/batch-b", "Batch B")

        driver.resetQueryCounts()
        val page = repository.getCreatorCardProjectionPage(offset = 0, limit = 50, followedOnly = false)

        assertEquals(2, page.creators.size)
        assertEquals(1, driver.projectionQueryCount)
        assertEquals(1, driver.representativeCacheReadCount)
        assertEquals(2, driver.representativeCacheWriteCount)
        assertEquals(1, driver.representativeCacheBatchTransactionCount)
    }

    @Test
    fun `stale page cache write is skipped after its creator merges`() = runBlocking {
        val target = repository.upsertCreator("Merge target")
        val source = repository.upsertCreator("Stale source")
        (801L..803L).forEach { sourceId ->
            addVerifiedWork(repository, source.id, sourceId, "/stale-$sourceId", "Stale $sourceId")
        }
        driver.beforeRepresentativeCacheWrite = {
            database.mergeCreatorIdentityGraph(source.id, target.id, now = 900L)
        }

        val page = repository.getCreatorCardProjectionPage(offset = 0, limit = 50, followedOnly = false)

        assertTrue(page.creators.any { it.creator.id == source.id })
        assertEquals(
            "MERGED",
            queryString(
                driver,
                "SELECT status FROM author_archive_creators WHERE _id = ${source.id}",
            ),
        )
        assertEquals(
            0L,
            queryLong(
                driver,
                "SELECT COUNT(*) FROM author_archive_representative_work_cache WHERE creator_id = ${source.id}",
            ),
        )
    }

    @Test
    fun `merging creators removes source cache while retaining eligible target selections`() = runBlocking {
        val target = repository.upsertCreator("Target Root")
        val source = repository.upsertCreator("Source Root")
        (400L..402L).forEach { sourceId ->
            addVerifiedWork(repository, target.id, sourceId, "/target-$sourceId", "Target $sourceId")
        }
        (500L..502L).forEach { sourceId ->
            addVerifiedWork(repository, source.id, sourceId, "/source-$sourceId", "Source $sourceId")
        }
        val targetPayload =
            "{\"selected\":[{\"workKey\":\"source:400:/target-400\",\"sourceId\":400," +
                "\"stableSourceUrl\":\"/target-400\"}]}"
        seedCache(
            driver,
            target.id,
            strategyVersion = 1L,
            payload = targetPayload,
        )
        seedCache(
            driver,
            source.id,
            strategyVersion = 1L,
            payload = "{\"selected\":[" + (500L..502L).joinToString(",") { sourceId ->
                "{\"workKey\":\"source:$sourceId:/source-$sourceId\"," +
                    "\"sourceId\":$sourceId,\"stableSourceUrl\":\"/source-$sourceId\"}"
            } + "]}",
        )

        repository.mergeCreatorIdentities(source.id, target.id)

        assertEquals(
            0L,
            queryLong(
                driver,
                "SELECT COUNT(*) FROM author_archive_representative_work_cache WHERE creator_id = ${source.id}",
            ),
        )
        assertEquals(
            1L,
            queryLong(
                driver,
                "SELECT COUNT(*) FROM author_archive_representative_work_cache WHERE creator_id = ${target.id}",
            ),
        )
        val card = repository.getCreatorCardProjectionPage(offset = 0, limit = 50, followedOnly = false)
            .creators.single()
        assertEquals(target.id, card.creator.id)
        assertEquals(3, card.representativeWorks.size)
        assertTrue(card.representativeWorks.any { it.workKey == "source:400:/target-400" })
        assertTrue(card.representativeWorks.none { it.workKey == "source:500:/source-500" })
    }

    @Test
    fun `adding creator aliases removes losing root cache through identity graph merge`() = runBlocking {
        val target = repository.upsertCreator("Alias target")
        val source = repository.upsertCreator("Alias source")
        (800L..802L).forEach { sourceId ->
            addVerifiedWork(repository, source.id, sourceId, "/alias-$sourceId", "Alias $sourceId")
        }

        repository.getCreatorCardProjectionPage(offset = 0, limit = 50, followedOnly = false)
        assertEquals(
            1L,
            queryLong(
                driver,
                "SELECT COUNT(*) FROM author_archive_representative_work_cache WHERE creator_id = ${source.id}",
            ),
        )

        val candidates = repository.getAliasCandidates(target.id)
        val selected = candidates.candidates.single { it.id == source.id }
        repository.addCreatorAliases(
            AddCreatorAliasesRequest(
                target.id,
                candidates.target.revision,
                mapOf(source.id to selected.revision),
                "add-alias-cache-cleanup",
            ),
        )

        assertEquals(
            0L,
            queryLong(
                driver,
                "SELECT COUNT(*) FROM author_archive_representative_work_cache WHERE creator_id = ${source.id}",
            ),
        )
        assertEquals(target.id, repository.getIdentitySnapshot(source.id).id)
    }

    private fun repositoryFor(targetDriver: ProjectionCountingDriver): CreatorRepositoryImpl {
        val database = Database(
            driver = targetDriver,
            historyAdapter = tachiyomi.data.History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
            ),
        )
        return CreatorRepositoryImpl(JvmDatabaseHandler(database, targetDriver))
    }

    private suspend fun addVerifiedWork(
        targetRepository: CreatorRepositoryImpl,
        creatorId: Long,
        sourceId: Long,
        url: String,
        title: String,
        mangaId: Long? = null,
    ) {
        val naturalKey = SourceWorkNaturalKey(sourceId, url)
        targetRepository.upsertSourceWork(
            sourceId,
            url,
            mangaId,
            title,
            "Cache Author",
            null,
            null,
            sourceId,
        )
        targetRepository.upsertSourceWorkCreator(
            naturalKey,
            creatorId,
            CreatorRole.AUTHOR,
            0,
            CreatorRelationOrigin.USER,
            CreatorRelationVerification.VERIFIED,
            "Cache Author",
            1.0,
            "cache fixture",
        )
    }

    private fun seedManga(id: Long, source: Long, url: String, title: String, favorite: Boolean) {
        seedManga(driver, id, source, url, title, favorite)
    }

    private fun seedManga(
        targetDriver: ProjectionCountingDriver,
        id: Long,
        source: Long,
        url: String,
        title: String,
        favorite: Boolean,
    ) {
        targetDriver.execute(
            null,
            "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, chapter_flags, " +
                "cover_last_modified, date_added) VALUES (?, ?, ?, ?, 0, ?, 0, 0, 0, 10, 0)",
            5,
        ) {
            bindLong(0, id)
            bindLong(1, source)
            bindString(2, url)
            bindString(3, title)
            bindLong(4, if (favorite) 1L else 0L)
        }
    }

    private fun seedReadHistory(targetDriver: ProjectionCountingDriver, mangaId: Long) {
        targetDriver.execute(
            null,
            "INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read, chapter_number, " +
                "source_order, date_fetch, date_upload) VALUES (601, ?, '/promoted/chapter', '1', 1, 0, 0, 1, 1, 0, 0)",
            1,
        ) {
            bindLong(0, mangaId)
        }
        targetDriver.execute(
            null,
            "INSERT INTO history(_id, chapter_id, last_read, time_read) VALUES (701, 601, 123456, 1)",
            0,
        )
    }

    private fun seedCache(
        targetDriver: ProjectionCountingDriver,
        creatorId: Long,
        strategyVersion: Long,
        payload: String,
    ) {
        targetDriver.execute(
            null,
            "INSERT INTO author_archive_representative_work_cache(creator_id, strategy_version, payload) " +
                "VALUES (?, ?, ?)",
            3,
        ) {
            bindLong(0, creatorId)
            bindLong(1, strategyVersion)
            bindString(2, payload)
        }
    }

    private fun queryLong(targetDriver: ProjectionCountingDriver, sql: String): Long = targetDriver.executeQuery(
        null,
        sql,
        { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0)!!)
        },
        0,
    ).value

    private fun queryString(targetDriver: ProjectionCountingDriver, sql: String): String = targetDriver.executeQuery(
        null,
        sql,
        { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getString(0)!!)
        },
        0,
    ).value
}

private class ProjectionCountingDriver(
    private val delegate: SqlDriver,
) : SqlDriver by delegate {

    var beforeRepresentativeCacheWrite: (() -> Unit)? = null

    var projectionQueryCount: Int = 0
        private set

    var representativeCacheReadCount: Int = 0
        private set

    var representativeCacheWriteCount: Int = 0
        private set

    var representativeCacheBatchTransactionCount: Int = 0
        private set

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        if (sql.contains("page_creators")) projectionQueryCount += 1
        if (sql.contains("author_archive_representative_work_cache")) representativeCacheReadCount += 1
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        if (sql.contains("INSERT INTO author_archive_representative_work_cache", ignoreCase = true)) {
            representativeCacheWriteCount += 1
            beforeRepresentativeCacheWrite?.also {
                beforeRepresentativeCacheWrite = null
                it()
            }
        }
        return delegate.execute(identifier, sql, parameters, binders)
    }

    override fun newTransaction(): QueryResult<Transaction> {
        representativeCacheBatchTransactionCount += 1
        return delegate.newTransaction()
    }

    fun resetProjectionQueryCount() {
        projectionQueryCount = 0
    }

    fun resetQueryCounts() {
        projectionQueryCount = 0
        representativeCacheReadCount = 0
        representativeCacheWriteCount = 0
        representativeCacheBatchTransactionCount = 0
    }
}
