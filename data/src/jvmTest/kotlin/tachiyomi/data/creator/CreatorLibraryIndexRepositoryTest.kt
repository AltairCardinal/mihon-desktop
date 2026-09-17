package tachiyomi.data.creator

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga
import tachiyomi.domain.creator.model.ArchiveWatchPolicy
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.DecisionActor
import tachiyomi.domain.creator.model.DiscoveryCommit
import tachiyomi.domain.creator.model.DiscoveryKind
import tachiyomi.domain.creator.model.DiscoveryRunState
import tachiyomi.domain.creator.model.LeaseAcquireResult
import tachiyomi.domain.creator.model.NotificationDeliveryState
import tachiyomi.domain.creator.model.ReviewDisposition
import tachiyomi.domain.creator.model.SourceCheckpointResult
import tachiyomi.domain.creator.model.SourceCheckpointUpdate
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.WatchBaselineState
import tachiyomi.domain.creator.model.WorkDecisionContract
import tachiyomi.domain.creator.model.WorkDecisionState
import tachiyomi.domain.creator.service.BoundedAuthorSearchPageRequest
import tachiyomi.domain.creator.service.CreatorDiscoveryBounds
import tachiyomi.domain.creator.service.CreatorDiscoveryService
import tachiyomi.domain.creator.service.CreatorDiscoverySourcePort
import tachiyomi.domain.creator.service.CreatorSourceCapability
import tachiyomi.domain.creator.service.CreatorSourceDetails
import tachiyomi.domain.creator.service.CreatorSourceDetailsResult
import tachiyomi.domain.creator.service.CreatorSourcePageResult
import tachiyomi.domain.creator.service.CreatorSourceWorkSnapshot
import tachiyomi.domain.creator.service.EnabledCreatorSource
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate

class CreatorLibraryIndexRepositoryTest {

    private lateinit var repository: CreatorRepositoryImpl
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var handler: JvmDatabaseHandler
    private val extract = ExtractCreatorsFromManga()

    @BeforeEach
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        val database = Database(
            driver = driver,
            historyAdapter = tachiyomi.data.History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = tachiyomi.data.Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
            ),
        )
        handler = JvmDatabaseHandler(database, driver)
        repository = CreatorRepositoryImpl(
            handler = handler,
            clock = { 100L },
            portableKeyFactory = sequentialKeys(),
        )
    }

    @Test
    fun `ordinary metadata refresh replaces BOTH with AUTHOR for manga and source work`() = runBlocking<Unit> {
        val original = manga(id = 1L, author = "ONE", artist = "ONE")
        seedManga(original)
        repository.indexLibraryManga(original, extract.await(original))
        queryLong("SELECT COUNT(*) FROM author_archive_manga_links WHERE role = 'BOTH'") shouldBe 1L
        val refreshed = original.copy(artist = null)
        repository.indexLibraryManga(refreshed, extract.await(refreshed))
        queryLong("SELECT COUNT(*) FROM author_archive_manga_links WHERE role = 'AUTHOR'") shouldBe 1L
        queryLong("SELECT COUNT(*) FROM author_archive_source_work_creators WHERE role = 'AUTHOR'") shouldBe 1L
    }

    @Test
    fun `library backfill splits people folds overlap and is idempotent`() {
        runBlocking {
            val manga = manga(
                id = 1L,
                author = "ONE，村田雄介",
                artist = "村田雄介 / Boichi",
            )
            seedManga(manga)

            repository.indexLibraryManga(manga, extract.await(manga))
            repository.indexLibraryManga(manga, extract.await(manga))

            queryLong("SELECT COUNT(*) FROM author_archive_creators WHERE status = 'ACTIVE'") shouldBe 3L
            queryLong("SELECT COUNT(*) FROM author_archive_manga_links WHERE manga_id = 1") shouldBe 3L
            queryLong("SELECT COUNT(*) FROM author_archive_source_work_creators") shouldBe 3L
            queryStrings(
                "SELECT role FROM author_archive_manga_links WHERE manga_id = 1 ORDER BY creator_order",
            ).shouldContainExactly("AUTHOR", "BOTH", "ARTIST")
            queryLong("SELECT COUNT(*) FROM creators") shouldBe 0L
            queryLong("SELECT COUNT(*) FROM manga_creators") shouldBe 0L
        }
    }

    @Test
    fun `metadata replacement removes stale automatic bindings but preserves user selection`() {
        runBlocking {
            val original = manga(id = 2L, author = "ONE / Murata", artist = null)
            seedManga(original)
            repository.indexLibraryManga(original, extract.await(original))
            val murataId = queryLong(
                "SELECT creator_id FROM author_archive_aliases WHERE normalized_alias = 'murata'",
            )
            repository.bindMangaCreatorIdentity(
                manga = original,
                mention = extract.await(original).last(),
                creatorId = murataId,
            )

            val changed = original.copy(author = "ONE", lastModifiedAt = 2L)
            repository.indexLibraryManga(changed, extract.await(changed))

            queryLong("SELECT COUNT(*) FROM author_archive_manga_links WHERE manga_id = 2") shouldBe 2L
            queryLong(
                "SELECT COUNT(*) FROM author_archive_manga_links " +
                    "WHERE manga_id = 2 AND creator_id = $murataId AND origin = 'USER'",
            ) shouldBe 1L
            queryLong(
                "SELECT COUNT(*) FROM author_archive_source_work_creators " +
                    "WHERE creator_id = $murataId AND origin = 'USER'",
            ) shouldBe 1L
        }
    }

    @Test
    fun `historical exact same name roots converge before indexing`() {
        runBlocking {
            seedIdentity(1L, "identity-a", "Same")
            seedIdentity(2L, "identity-b", "Same")
            val manga = manga(id = 3L, author = "Same", artist = null)
            seedManga(manga)

            repository.indexLibraryManga(manga, extract.await(manga))
            repository.indexLibraryManga(manga, extract.await(manga))

            val boundId = queryLong("SELECT creator_id FROM author_archive_manga_links WHERE manga_id = 3")
            queryLong("SELECT COUNT(*) FROM author_archive_creators WHERE status = 'ACTIVE'") shouldBe 1L
            queryLong("SELECT COUNT(*) FROM author_archive_creators WHERE status = 'MERGED'") shouldBe 1L
            queryLong("SELECT COUNT(*) FROM author_archive_identity_names WHERE name_text = 'Same'") shouldBe 1L

            val options = repository.getCreatorIdentityOptions(3L, extract.await(manga).single())
            options.single().id shouldBe boundId
        }
    }

    @Test
    fun `transitive historical names converge idempotently and survive repository restart`() = runBlocking<Unit> {
        seedIdentity(1L, "identity-c", "First")
        seedIdentity(2L, "identity-a", "Second")
        seedIdentity(3L, "identity-b", "Third")
        executeSql(
            "INSERT INTO author_archive_aliases(creator_id, raw_alias, normalized_alias, source, evidence, " +
                "confidence, is_manual, created_at, last_modified_at) VALUES " +
                "(1, 'Bridge A', 'bridge a', 'USER', 'fixture', 1, 1, 1, 1), " +
                "(2, 'Bridge A', 'bridge a', 'USER', 'fixture', 1, 1, 1, 1), " +
                "(2, 'Bridge B', 'bridge b', 'USER', 'fixture', 1, 1, 1, 1), " +
                "(3, 'Bridge B', 'bridge b', 'USER', 'fixture', 1, 1, 1, 1)",
        )

        repository.getCreatorsAsFlow().first().single().id shouldBe 2L
        val restarted = CreatorRepositoryImpl(handler, clock = { 200L }, portableKeyFactory = sequentialKeys())
        restarted.resolveCreatorIdByExactName("First") shouldBe 2L
        restarted.resolveCreatorIdByExactName("Third") shouldBe 2L

        queryLong("SELECT COUNT(*) FROM author_archive_creators WHERE status = 'ACTIVE'") shouldBe 1L
        queryLong("SELECT COUNT(*) FROM author_archive_creators WHERE status = 'MERGED'") shouldBe 2L
        queryLong("SELECT COUNT(*) FROM author_archive_identity_names") shouldBe 5L
        queryStrings("SELECT state FROM author_archive_identity_migrations")
            .shouldContainExactly("COMPLETED")
    }

    @Test
    fun `corrupt redirect cycle fails closed before creator access`() {
        seedIdentity(1L, "identity-a", "First")
        seedIdentity(2L, "identity-b", "Second")
        executeSql("UPDATE author_archive_creators SET status = 'MERGED', merged_into_creator_id = 2 WHERE _id = 1")
        executeSql("UPDATE author_archive_creators SET status = 'MERGED', merged_into_creator_id = 1 WHERE _id = 2")

        shouldThrow<IllegalStateException> { runBlocking { repository.getCreatorsAsFlow().first() } }

        queryLong("SELECT COUNT(*) FROM author_archive_identity_names") shouldBe 0L
        queryLong("SELECT COUNT(*) FROM author_archive_identity_migrations") shouldBe 0L
    }

    @Test
    fun `exact comparison keeps width and case variants distinct`() {
        runBlocking {
            val first = manga(id = 30L, author = "Same", artist = null)
            val second = manga(id = 31L, author = "Ｓａｍｅ", artist = null)
            seedManga(first)
            seedManga(second)

            repository.indexLibraryManga(first, extract.await(first))
            repository.indexLibraryManga(second, extract.await(second))
            repository.indexLibraryManga(first, extract.await(first))
            repository.indexLibraryManga(second, extract.await(second))

            queryLong("SELECT COUNT(*) FROM author_archive_creators WHERE normalized_name = 'same'") shouldBe 2L
            queryLong(
                "SELECT COUNT(DISTINCT creator_id) FROM author_archive_manga_links WHERE manga_id IN (30, 31)",
            ) shouldBe 2L
            queryStrings("SELECT name_text FROM author_archive_identity_names ORDER BY name_text")
                .shouldContainExactly("Same", "Ｓａｍｅ")
        }
    }

    @Test
    fun `same exact name across sources reuses one root`() {
        runBlocking {
            val first = manga(id = 33L, author = "冈本伦", artist = null).copy(source = 1L, url = "/a")
            val second = manga(id = 34L, author = "冈本伦", artist = null).copy(source = 9L, url = "/b")
            seedManga(first)
            seedManga(second)

            repository.indexLibraryManga(first, extract.await(first))
            repository.indexLibraryManga(second, extract.await(second))

            queryLong("SELECT COUNT(*) FROM author_archive_creators WHERE status = 'ACTIVE'") shouldBe 1L
            queryLong("SELECT COUNT(DISTINCT creator_id) FROM author_archive_manga_links") shouldBe 1L
            queryLong("SELECT COUNT(*) FROM author_archive_manga_links") shouldBe 2L
        }
    }

    @Test
    fun `batch payload preserves percent and control separator characters`() {
        runBlocking {
            val rawName = "100%1E\u001eUnit\u001fTail\u0000End"
            val manga = manga(id = 32L, author = rawName, artist = null)
            seedManga(manga)

            repository.indexLibraryManga(manga, extract.await(manga))

            queryStrings(
                "SELECT display_name FROM author_archive_creators " +
                    "JOIN author_archive_manga_links ON creator_id = author_archive_creators._id " +
                    "WHERE manga_id = 32",
            ).single() shouldBe rawName
        }
    }

    @Test
    fun `clearing all bibliography keeps source work metadata and removes stale automatic links`() {
        runBlocking {
            val original = manga(id = 33L, author = "ONE", artist = null)
            seedManga(original)
            repository.indexLibraryManga(original, extract.await(original))

            MangaRepositoryImpl(handler, repository).update(
                MangaUpdate(id = original.id, author = null, updateAuthor = true),
            ) shouldBe true

            queryLong("SELECT COUNT(*) FROM author_archive_manga_links WHERE manga_id = 33") shouldBe 0L
            queryLong(
                "SELECT COUNT(*) FROM author_archive_source_work_creators " +
                    "WHERE source_work_id = (SELECT _id FROM author_archive_source_works WHERE manga_id = 33)",
            ) shouldBe 0L
            queryLong(
                "SELECT COUNT(*) FROM author_archive_source_works WHERE manga_id = 33 AND author_text IS NULL",
            ) shouldBe 1L
        }
    }

    @Test
    fun `exact name owner cannot be reassigned by an obsolete identity selection`() {
        runBlocking {
            seedIdentity(1L, "identity-a", "Same")
            seedIdentity(2L, "identity-b", "Same")
            val first = manga(id = 34L, author = "Same", artist = null)
            val second = manga(id = 35L, author = "Same", artist = null)
            seedManga(first)
            seedManga(second)

            repository.bindMangaCreatorIdentity(first, extract.await(first).single(), 1L)
            repository.bindMangaCreatorIdentity(first, extract.await(first).single(), 2L)
            repository.bindMangaCreatorIdentity(second, extract.await(second).single(), 1L)
            executeSql("UPDATE author_archive_manga_links SET origin = 'RESTORE' WHERE manga_id = 35")
            executeSql(
                "UPDATE author_archive_source_work_creators SET origin = 'RESTORE' " +
                    "WHERE source_work_id = (SELECT _id FROM author_archive_source_works WHERE manga_id = 35)",
            )
            repository.bindMangaCreatorIdentity(second, extract.await(second).single(), 2L)

            queryStrings(
                "SELECT creator_id || ':' || origin FROM author_archive_manga_links " +
                    "WHERE manga_id IN (34, 35) ORDER BY manga_id",
            ).shouldContainExactly("1:USER", "1:USER")
            queryStrings(
                "SELECT SWC.creator_id || ':' || SWC.origin FROM author_archive_source_work_creators SWC " +
                    "JOIN author_archive_source_works SW ON SW._id = SWC.source_work_id " +
                    "WHERE SW.manga_id IN (34, 35) ORDER BY SW.manga_id",
            ).shouldContainExactly("1:USER", "1:USER")
        }
    }

    @Test
    fun `source and url changes detach historical source works and keep one current attachment`() {
        runBlocking {
            val original = manga(id = 36L, author = "ONE", artist = null)
            seedManga(original)
            repository.indexLibraryManga(original, extract.await(original))
            val mangaRepository = MangaRepositoryImpl(handler, repository)

            mangaRepository.update(MangaUpdate(id = 36L, url = "/moved")) shouldBe true
            mangaRepository.update(MangaUpdate(id = 36L, source = 11L)) shouldBe true

            queryLong(
                "SELECT COUNT(*) FROM author_archive_source_works WHERE manga_id = 36",
            ) shouldBe 1L
            queryStrings(
                "SELECT source_id || ':' || stable_source_url FROM author_archive_source_works " +
                    "WHERE manga_id = 36",
            ).shouldContainExactly("11:/moved")
            queryLong(
                "SELECT COUNT(*) FROM author_archive_source_works " +
                    "WHERE manga_id IS NULL AND (stable_source_url = '/manga/36' OR stable_source_url = '/moved')",
            ) shouldBe 2L
            queryLong(
                "SELECT COUNT(*) FROM author_archive_source_work_creators",
            ) shouldBe 3L
        }
    }

    @Test
    fun `removing a library manga detaches archive source work and only deletes automatic links`() {
        runBlocking {
            val manga = manga(id = 4L, author = "ONE / Murata", artist = null)
            seedManga(manga)
            repository.indexLibraryManga(manga, extract.await(manga))
            val one = repository.getCreatorIdentityOptions(4L, extract.await(manga).first()).single()
            repository.bindMangaCreatorIdentity(manga, extract.await(manga).first(), one.id)

            repository.removeLibraryMangaIndex(4L)

            queryLong(
                "SELECT COUNT(*) FROM author_archive_manga_links WHERE manga_id = 4 AND origin = 'AUTOMATIC'",
            ) shouldBe 0L
            queryLong(
                "SELECT COUNT(*) FROM author_archive_manga_links WHERE manga_id = 4 AND origin = 'USER'",
            ) shouldBe 1L
            queryLong("SELECT COUNT(*) FROM author_archive_source_works WHERE manga_id = 4") shouldBe 0L
        }
    }

    @Test
    fun `manual alias and merge remap bindings canonical creator and follow without duplicates`() {
        runBlocking {
            val first = manga(id = 5L, author = "ONE", artist = null)
            val second = manga(id = 6L, author = "One Sensei", artist = null)
            seedManga(first)
            seedManga(second)
            val sourceId = repository.createAndBindMangaCreatorIdentity(first, extract.await(first).single())
            val targetId = repository.createAndBindMangaCreatorIdentity(second, extract.await(second).single())
            repository.addManualCreatorAlias(targetId, "ONE-sensei")
            repository.followCreator(sourceId, sourceIds = listOf(1L, 2L), languageTags = listOf("ja"))
            repository.followCreator(targetId, sourceIds = listOf(2L, 3L), languageTags = listOf("en"))
            repository.createCanonicalWork("Merged Work", sourceId, "ja")

            repository.mergeCreatorIdentities(sourceCreatorId = sourceId, targetCreatorId = targetId)

            repository.getCreator(sourceId)!!.id shouldBe targetId
            repository.getMangaCreatorsForCreator(targetId).map { it.mangaId }.sorted()
                .shouldContainExactly(5L, 6L)
            repository.getFollowedCreatorsAsFlow().first().single().let { watch ->
                watch.creatorId shouldBe targetId
                watch.sourceIds.shouldContainExactly(1L, 2L, 3L)
                watch.languageTags.shouldContainExactly("en", "ja")
            }
            queryLong(
                "SELECT COUNT(*) FROM author_archive_canonical_creators WHERE creator_id = $targetId",
            ) shouldBe 1L
            queryLong("SELECT COUNT(*) FROM author_archive_aliases WHERE creator_id = $targetId") shouldBe 3L
            queryLong(
                "SELECT COUNT(*) FROM author_archive_creators " +
                    "WHERE _id = $sourceId AND status = 'MERGED' AND merged_into_creator_id = $targetId",
            ) shouldBe 1L
        }
    }

    @Test
    fun `merge preserves author and artist role union on the same work`() = runBlocking<Unit> {
        val work = manga(id = 61L, author = "Author Name", artist = "Artist Name")
        seedManga(work)
        val mentions = extract.await(work)
        val authorId = repository.createAndBindMangaCreatorIdentity(work, mentions[0])
        val artistId = repository.createAndBindMangaCreatorIdentity(work, mentions[1])
        driver.execute(
            null,
            "INSERT INTO author_archive_canonical_works(portable_key, primary_title, normalized_title, status, " +
                "created_at, last_modified_at) VALUES " +
                "('role-union-work', 'Role union', 'role union', 'ACTIVE', 1, 1)",
            0,
        )
        val canonicalWorkId = queryLong(
            "SELECT _id FROM author_archive_canonical_works WHERE portable_key = 'role-union-work'",
        )
        driver.execute(
            null,
            "INSERT INTO author_archive_canonical_creators(work_id, creator_id, role, creator_order, origin, " +
                "evidence) VALUES ($canonicalWorkId, $authorId, 'AUTHOR', 0, 'USER', 'author'), " +
                "($canonicalWorkId, $artistId, 'ARTIST', 1, 'RESTORE', 'artist')",
            0,
        )

        repository.mergeCreatorIdentities(artistId, authorId)

        queryStrings("SELECT role FROM author_archive_manga_links WHERE manga_id = 61")
            .shouldContainExactly("BOTH")
        queryStrings(
            "SELECT role FROM author_archive_source_work_creators " +
                "WHERE source_work_id = (SELECT _id FROM author_archive_source_works WHERE manga_id = 61)",
        ).shouldContainExactly("BOTH")
        queryStrings("SELECT role FROM author_archive_canonical_creators WHERE work_id = $canonicalWorkId")
            .shouldContainExactly("BOTH")
        queryStrings("SELECT author || ':' || artist FROM mangas WHERE _id = 61")
            .shouldContainExactly("Author Name:Artist Name")
        queryStrings("SELECT author_text || ':' || artist_text FROM author_archive_source_works WHERE manga_id = 61")
            .shouldContainExactly("Author Name:Artist Name")
        queryStrings(
            "SELECT origin || ':' || evidence FROM author_archive_canonical_creators WHERE work_id = $canonicalWorkId",
        )
            .shouldContainExactly("USER:author")
    }

    @Test
    fun `merge preserves watch runs discoveries notifications and newest checkpoint`() {
        runBlocking {
            val first = manga(id = 50L, author = "Source", artist = null)
            val second = manga(id = 51L, author = "Target", artist = null)
            seedManga(first)
            seedManga(second)
            val sourceId = repository.createAndBindMangaCreatorIdentity(first, extract.await(first).single())
            val targetId = repository.createAndBindMangaCreatorIdentity(second, extract.await(second).single())
            repository.followCreator(sourceId, sourceIds = listOf(10L), languageTags = listOf("ja"))
            repository.followCreator(targetId, sourceIds = listOf(10L), languageTags = listOf("en"))
            val sourceWatchId = queryLong(
                "SELECT _id FROM author_archive_watches WHERE creator_id = $sourceId",
            )
            val targetWatchId = queryLong(
                "SELECT _id FROM author_archive_watches WHERE creator_id = $targetId",
            )
            val sharedWorkId = queryLong(
                "SELECT _id FROM author_archive_source_works WHERE manga_id = 50",
            )
            val sourceOnlyWorkId = queryLong(
                "SELECT _id FROM author_archive_source_works WHERE manga_id = 51",
            )

            executeSql(
                "UPDATE author_archive_watch_sources SET baseline_state = 'BASELINED', " +
                    "baseline_generation = 4, next_due_at = 400, last_modified_at = 200 " +
                    "WHERE watch_id = $sourceWatchId AND source_id = 10",
            )
            executeSql(
                "INSERT INTO author_archive_runs(run_key, watch_id, state, completed_sources, total_sources, " +
                    "truncated, queued_at, finished_at) VALUES " +
                    "('source-run', $sourceWatchId, 'SUCCEEDED', 1, 1, 0, 10, 20), " +
                    "('target-run', $targetWatchId, 'FAILED', 0, 1, 0, 30, 40)",
            )
            executeSql(
                "INSERT INTO author_archive_source_checkpoints(watch_id, source_id, cursor, result_state, " +
                    "consecutive_failures, last_checked_at, last_success_at, error_code, error_message) VALUES " +
                    "($sourceWatchId, 10, 'new-cursor', 'SUCCESS', 0, 200, 200, NULL, NULL), " +
                    "($targetWatchId, 10, 'old-cursor', 'FAILED', 2, 100, NULL, 'old', 'old failure')",
            )
            executeSql(
                "INSERT INTO author_archive_discoveries(watch_id, source_work_id, kind, reason, " +
                    "baseline_generation, read_state, review_disposition, first_discovered_at, last_modified_at) " +
                    "VALUES " +
                    "($sourceWatchId, $sharedWorkId, 'NEW_WORK_CANDIDATE', 'source reason', 4, 'UNSEEN', " +
                    "'PENDING', 10, 200), " +
                    "($targetWatchId, $sharedWorkId, 'NEW_SOURCE_VERSION', 'target reason', 2, 'SEEN', " +
                    "'ACCEPTED', 20, 100), " +
                    "($sourceWatchId, $sourceOnlyWorkId, 'NEW_SOURCE_VERSION', 'source only', 4, 'UNSEEN', " +
                    "'PENDING', 30, 200)",
            )
            val sourceSharedDiscovery = queryLong(
                "SELECT _id FROM author_archive_discoveries " +
                    "WHERE watch_id = $sourceWatchId AND source_work_id = $sharedWorkId",
            )
            val targetSharedDiscovery = queryLong(
                "SELECT _id FROM author_archive_discoveries " +
                    "WHERE watch_id = $targetWatchId AND source_work_id = $sharedWorkId",
            )
            val sourceOnlyDiscovery = queryLong(
                "SELECT _id FROM author_archive_discoveries " +
                    "WHERE watch_id = $sourceWatchId AND source_work_id = $sourceOnlyWorkId",
            )
            executeSql(
                "INSERT INTO author_archive_notification_outbox(discovery_id, channel, idempotency_key, " +
                    "attempt_count, state, created_at) VALUES " +
                    "($sourceSharedDiscovery, 'DESKTOP', 'source-shared', 1, 'DELIVERED', 10), " +
                    "($targetSharedDiscovery, 'DESKTOP', 'target-shared', 2, 'FAILED', 20), " +
                    "($sourceOnlyDiscovery, 'DESKTOP', 'source-only', 0, 'PENDING', 30)",
            )

            repository.mergeCreatorIdentities(sourceCreatorId = sourceId, targetCreatorId = targetId)

            queryLong("SELECT COUNT(*) FROM author_archive_watches WHERE creator_id = $sourceId") shouldBe 0L
            queryLong("SELECT COUNT(*) FROM author_archive_runs WHERE watch_id = $targetWatchId") shouldBe 2L
            queryLong("SELECT COUNT(*) FROM author_archive_discoveries WHERE watch_id = $targetWatchId") shouldBe 2L
            queryLong("SELECT COUNT(*) FROM author_archive_notification_outbox") shouldBe 3L
            queryStrings(
                "SELECT cursor FROM author_archive_source_checkpoints " +
                    "WHERE watch_id = $targetWatchId AND source_id = 10",
            ).shouldContainExactly("new-cursor")
            queryStrings(
                "SELECT baseline_state || ':' || baseline_generation FROM author_archive_watch_sources " +
                    "WHERE watch_id = $targetWatchId AND source_id = 10",
            ).shouldContainExactly("BASELINED:4")
        }
    }

    @Test
    fun `split rejects recreating an exact name already owned by the source root`() {
        runBlocking {
            val first = manga(id = 7L, author = "Same", artist = null)
            val second = manga(id = 8L, author = "Same", artist = null)
            seedManga(first)
            seedManga(second)
            repository.indexLibraryManga(first, extract.await(first))
            repository.indexLibraryManga(second, extract.await(second))
            val originalId = queryLong("SELECT creator_id FROM author_archive_manga_links WHERE manga_id = 7")
            repository.followCreator(originalId, sourceIds = listOf(9L), languageTags = listOf("ja"))

            shouldThrow<IllegalStateException> {
                repository.splitCreatorIdentity(
                    sourceCreatorId = originalId,
                    mangaIds = setOf(8L),
                    newDisplayName = "Same",
                )
            }
            queryLong("SELECT creator_id FROM author_archive_manga_links WHERE manga_id = 7") shouldBe originalId
            queryLong("SELECT creator_id FROM author_archive_manga_links WHERE manga_id = 8") shouldBe originalId
        }
    }

    @Test
    fun `split by source work key moves detached discovery outbox and canonical decision atomically`() {
        runBlocking {
            val manga = manga(id = 60L, author = "Same", artist = null)
            seedManga(manga)
            repository.indexLibraryManga(manga, extract.await(manga))
            val sourceCreatorId = queryLong(
                "SELECT creator_id FROM author_archive_manga_links WHERE manga_id = 60",
            )
            val sourceWorkId = queryLong(
                "SELECT _id FROM author_archive_source_works WHERE manga_id = 60",
            )
            val sourceWork = SourceWorkNaturalKey(10L, "/manga/60")
            repository.followCreator(sourceCreatorId, sourceIds = listOf(10L), languageTags = listOf("ja"))
            val sourceWatchId = queryLong(
                "SELECT _id FROM author_archive_watches WHERE creator_id = $sourceCreatorId",
            )
            val canonical = repository.createCanonicalWork("Same Work", sourceCreatorId, "ja")
            repository.appendWorkDecision(
                sourceWork = sourceWork,
                workId = canonical.id,
                decision = WorkDecisionContract(WorkDecisionState.CONFIRMED, DecisionActor.USER, explicit = true),
                algorithmVersion = null,
                score = null,
                evidence = "user confirmed",
                decidedAt = 10L,
                idempotencyKey = "split-decision",
            )
            executeSql(
                "INSERT INTO author_archive_discoveries(watch_id, source_work_id, kind, reason, " +
                    "baseline_generation, read_state, review_disposition, first_discovered_at, last_modified_at) " +
                    "VALUES ($sourceWatchId, $sourceWorkId, 'NEW_WORK_CANDIDATE', 'split me', 1, 'UNSEEN', " +
                    "'PENDING', 10, 10)",
            )
            val discoveryId = queryLong(
                "SELECT _id FROM author_archive_discoveries WHERE watch_id = $sourceWatchId",
            )
            executeSql(
                "INSERT INTO author_archive_notification_outbox(discovery_id, channel, idempotency_key, " +
                    "attempt_count, state, created_at) VALUES " +
                    "($discoveryId, 'DESKTOP', 'split-outbox', 1, 'DELIVERED', 10)",
            )
            executeSql("UPDATE author_archive_source_works SET manga_id = NULL WHERE _id = $sourceWorkId")

            val creatorCount = queryLong("SELECT COUNT(*) FROM author_archive_creators")
            shouldThrow<IllegalStateException> {
                repository.splitCreatorIdentity(
                    sourceCreatorId = sourceCreatorId,
                    mangaIds = emptySet(),
                    newDisplayName = "Same split",
                    sourceWorks = setOf(SourceWorkNaturalKey(10L, "/missing")),
                )
            }
            queryLong("SELECT COUNT(*) FROM author_archive_creators") shouldBe creatorCount

            val splitId = repository.splitCreatorIdentity(
                sourceCreatorId = sourceCreatorId,
                mangaIds = emptySet(),
                newDisplayName = "Same split",
                sourceWorks = setOf(sourceWork),
            )
            val splitWatchId = queryLong(
                "SELECT _id FROM author_archive_watches WHERE creator_id = $splitId",
            )

            queryLong(
                "SELECT creator_id FROM author_archive_source_work_creators WHERE source_work_id = $sourceWorkId",
            ) shouldBe splitId
            queryLong(
                "SELECT COUNT(*) FROM author_archive_work_decisions WHERE source_work_id = $sourceWorkId",
            ) shouldBe 1L
            queryLong(
                "SELECT COUNT(*) FROM author_archive_canonical_versions " +
                    "WHERE source_work_id = $sourceWorkId AND work_id = ${canonical.id}",
            ) shouldBe 1L
            queryLong(
                "SELECT COUNT(*) FROM author_archive_canonical_creators " +
                    "WHERE work_id = ${canonical.id} AND creator_id = $splitId",
            ) shouldBe 1L
            queryLong(
                "SELECT COUNT(*) FROM author_archive_discoveries " +
                    "WHERE watch_id = $splitWatchId AND source_work_id = $sourceWorkId",
            ) shouldBe 1L
            queryLong("SELECT COUNT(*) FROM author_archive_notification_outbox") shouldBe 1L
        }
    }

    @Test
    fun `manual alias can be listed and removed without deleting bibliography aliases`() {
        runBlocking {
            val manga = manga(id = 61L, author = "ONE", artist = null)
            seedManga(manga)
            val creatorId = repository.createAndBindMangaCreatorIdentity(manga, extract.await(manga).single())
            repository.addManualCreatorAlias(creatorId, "One-sensei")

            repository.getManualCreatorAliases(creatorId).shouldContainExactly("One-sensei")
            repository.removeManualCreatorAlias(creatorId, "One-sensei")

            repository.getManualCreatorAliases(creatorId) shouldBe emptyList()
            queryLong(
                "SELECT COUNT(*) FROM author_archive_aliases " +
                    "WHERE creator_id = $creatorId AND normalized_alias = 'one'",
            ) shouldBe 1L
        }
    }

    @Test
    fun `watch lease run checkpoint discovery and outbox survive repository restart independently`() {
        runBlocking {
            val manga = manga(id = 70L, author = "Watched Author", artist = null)
            seedManga(manga)
            repository.indexLibraryManga(manga, extract.await(manga))
            val creatorId = queryLong("SELECT creator_id FROM author_archive_manga_links WHERE manga_id = 70")
            val sourceWork = SourceWorkNaturalKey(manga.source, manga.url)
            val watchPolicy = ArchiveWatchPolicy(
                creatorId = creatorId,
                enabled = true,
                periodMillis = 1_000L,
                sourceIds = setOf(10L, 20L),
                readingLanguageTags = setOf("en", "ja"),
                includeProbable = true,
                notifyProbable = true,
            )
            repository.upsertWatchPolicy(watchPolicy, now = 1_000L)

            repository.getDueWatchSources(1_000L, 10L).map { it.sourceId }
                .shouldContainExactly(10L, 20L)
            repository.acquireWatchLease(creatorId, "worker-a", 2_000L, 1_000L)
                .shouldBeInstanceOf<LeaseAcquireResult.Acquired>()
            repository.acquireWatchLease(creatorId, "worker-b", 2_000L, 1_000L)
                .shouldBeInstanceOf<LeaseAcquireResult.Busy>()

            repository.createDiscoveryRun("run-1", creatorId, 2L, 1_000L)
            repository.updateDiscoveryRun("run-1", DiscoveryRunState.RUNNING, 0L, false, null, null, 1_010L)
            repository.updateSourceCheckpoint(
                SourceCheckpointUpdate(
                    creatorId = creatorId,
                    sourceId = 10L,
                    cursor = "page-2",
                    result = SourceCheckpointResult.FAILED,
                    consecutiveFailures = 1L,
                    backoffUntil = 3_000L,
                    checkedAt = 1_020L,
                    successAt = null,
                    errorCode = "HTTP_500",
                    errorMessage = "temporary",
                    nextDueAt = 3_000L,
                    baselineState = WatchBaselineState.NEEDS_BASELINE,
                    baselineGeneration = 0L,
                ),
            )
            repository.updateSourceCheckpoint(
                SourceCheckpointUpdate(
                    creatorId = creatorId,
                    sourceId = 20L,
                    cursor = null,
                    result = SourceCheckpointResult.SUCCESS,
                    consecutiveFailures = 0L,
                    backoffUntil = null,
                    checkedAt = 1_025L,
                    successAt = 1_025L,
                    nextDueAt = 5_000L,
                    baselineState = WatchBaselineState.BASELINED,
                    baselineGeneration = 1L,
                ),
            )
            repository.updateDiscoveryRun(
                "run-1",
                DiscoveryRunState.PARTIAL,
                1L,
                false,
                "PARTIAL_SOURCE_FAILURE",
                "one source failed",
                1_030L,
            )
            repository.createDiscoveryRun("run-2", creatorId, 2L, 1_040L)
            repository.updateDiscoveryRun("run-2", DiscoveryRunState.RUNNING, 0L, false, null, null, 1_050L)
            repository.createDiscoveryRun("run-cancelled", creatorId, 2L, 1_045L)
            repository.updateDiscoveryRun(
                "run-cancelled",
                DiscoveryRunState.CANCELLED,
                0L,
                false,
                null,
                null,
                1_050L,
            )
            repository.upsertWatchPolicy(watchPolicy, now = 1_060L)

            val restarted = CreatorRepositoryImpl(handler, clock = { 2_000L }, portableKeyFactory = sequentialKeys())
            restarted.getRecoverableDiscoveryRuns().map { it.runKey }.shouldContainExactly("run-2")
            queryLong(
                "SELECT COUNT(*) FROM author_archive_watch_sources " +
                    "WHERE source_id = 20 AND baseline_state = 'BASELINED' AND baseline_generation = 1",
            ) shouldBe 1L
            queryLong(
                "SELECT COUNT(*) FROM author_archive_source_checkpoints " +
                    "WHERE result_state = 'FAILED' AND backoff_until = 3000",
            ) shouldBe 1L
            restarted.getSourceCheckpoints(creatorId).single { it.sourceId == 10L }.errorCode shouldBe "HTTP_500"
            restarted.observeSourceCheckpoints(creatorId).first().size shouldBe 2

            val discovery = restarted.commitDiscovery(
                DiscoveryCommit(
                    creatorId = creatorId,
                    sourceWork = sourceWork,
                    kind = DiscoveryKind.NEW_WORK_CANDIDATE,
                    reason = "verified creator relation",
                    baselineGeneration = 1L,
                    discoveredAt = 2_000L,
                    outboxChannel = "DESKTOP",
                    idempotencyKey = "discovery-70",
                ),
            )
            restarted.getUnreadDiscoveries(10L).single().id shouldBe discovery.id
            restarted.observeUnreadDiscoveries(10L).first().single().id shouldBe discovery.id
            restarted.getDiscovery(discovery.id)?.id shouldBe discovery.id
            restarted.getDiscoveries(10L).single().id shouldBe discovery.id
            restarted.observeDiscoveries(10L).first().single().id shouldBe discovery.id
            val outbox = restarted.getPendingNotificationOutbox(2_000L, 10L).single()
            restarted.observePendingNotificationOutbox(2_000L, 10L).first().single().id shouldBe outbox.id
            restarted.markDiscoveriesSeen(setOf(discovery.id), 2_010L)
            restarted.setDiscoveryReview(discovery.id, ReviewDisposition.ACCEPTED, 2_020L)
            restarted.updateNotificationDelivery(
                outbox.id,
                NotificationDeliveryState.FAILED,
                "offline",
                3_000L,
                2_030L,
            )

            restarted.getUnreadDiscoveries(10L) shouldBe emptyList()
            queryLong(
                "SELECT COUNT(*) FROM author_archive_discoveries " +
                    "WHERE read_state = 'SEEN' AND review_disposition = 'ACCEPTED'",
            ) shouldBe 1L
            queryLong(
                "SELECT COUNT(*) FROM author_archive_notification_outbox " +
                    "WHERE state = 'FAILED' AND next_attempt_at = 3000",
            ) shouldBe 1L

            restarted.unfollowCreator(creatorId)
            queryLong("SELECT COUNT(*) FROM author_archive_discoveries") shouldBe 1L
            queryLong("SELECT COUNT(*) FROM author_archive_notification_outbox") shouldBe 1L
            restarted.getDueWatchSources(4_000L, 10L) shouldBe emptyList()
            restarted.deleteReviewedDiscoveries(3_000L)
            queryLong("SELECT COUNT(*) FROM author_archive_discoveries") shouldBe 0L
            queryLong("SELECT COUNT(*) FROM author_archive_notification_outbox") shouldBe 0L
        }
    }

    @Test
    fun `discovery and outbox commit rolls back on idempotency payload conflict`() {
        runBlocking {
            val first = manga(id = 71L, author = "Atomic Author", artist = null)
            val second = manga(id = 72L, author = "Atomic Author", artist = null)
            seedManga(first)
            seedManga(second)
            repository.indexLibraryManga(first, extract.await(first))
            repository.indexLibraryManga(second, extract.await(second))
            val creatorId = queryLong("SELECT creator_id FROM author_archive_manga_links WHERE manga_id = 71")
            repository.upsertWatchPolicy(
                ArchiveWatchPolicy(creatorId, true, 1_000L, setOf(10L), emptySet()),
                now = 1_000L,
            )
            fun commit(manga: Manga) = DiscoveryCommit(
                creatorId = creatorId,
                sourceWork = SourceWorkNaturalKey(manga.source, manga.url),
                kind = DiscoveryKind.NEW_WORK_CANDIDATE,
                reason = "atomic",
                baselineGeneration = 1L,
                discoveredAt = manga.id,
                outboxChannel = "DESKTOP",
                idempotencyKey = "same-key",
            )

            repository.commitDiscovery(commit(first))
            shouldThrow<IllegalStateException> { repository.commitDiscovery(commit(second)) }

            queryLong("SELECT COUNT(*) FROM author_archive_discoveries") shouldBe 1L
            queryLong("SELECT COUNT(*) FROM author_archive_notification_outbox") shouldBe 1L
        }
    }

    @Test
    fun `bounded discovery production service persists verified and possible relations without duplicate urls`() {
        runBlocking {
            val creator = repository.upsertCreator("ONE")
            repository.upsertWatchPolicy(
                ArchiveWatchPolicy(
                    creatorId = creator.id,
                    enabled = true,
                    periodMillis = 1_000L,
                    sourceIds = setOf(10L),
                    readingLanguageTags = setOf("en"),
                ),
                now = 1L,
            )
            val verified = CreatorSourceWorkSnapshot(
                key = SourceWorkNaturalKey(10L, "/verified"),
                title = "Verified",
                authorText = "ONE",
                artistText = null,
                thumbnailUrl = null,
            )
            val possible = CreatorSourceWorkSnapshot(
                key = SourceWorkNaturalKey(10L, "/possible"),
                title = "Possible",
                authorText = null,
                artistText = null,
                thumbnailUrl = null,
            )
            val port = RecordingDiscoveryPort(listOf(verified, possible, verified))
            val service = CreatorDiscoveryService(
                creatorRepository = repository,
                archiveRepository = repository,
                sourcePort = port,
                bounds = CreatorDiscoveryBounds(
                    maxAliases = 1,
                    maxPagesPerAlias = 1,
                    maxTotalPagesPerSource = 1,
                    maxConcurrentSources = 1,
                    sourceTimeoutMillis = 10_000,
                ),
                clock = { 100L },
            )

            val result = service.discoverCreator(creator.id)

            // First successful scan establishes the source baseline and archives relations, so it
            // produces no discovery events or outbox rows.
            result.newCandidateCount shouldBe 0
            result.sourceResults.single().possibleCount shouldBe 1
            result.sourceResults.single().notificationEligibleCount shouldBe 1
            result.sourceResults.single().truncated shouldBe true
            port.requestedPages.shouldContainExactly(1)
            queryLong("SELECT COUNT(*) FROM author_archive_source_works") shouldBe 2L
            queryStrings(
                "SELECT verification FROM author_archive_source_work_creators ORDER BY source_work_id",
            ).shouldContainExactly("VERIFIED", "POSSIBLE")
        }
    }

    private fun manga(id: Long, author: String?, artist: String?) = Manga.create().copy(
        id = id,
        source = 10L,
        url = "/manga/$id",
        title = "Manga $id",
        author = author,
        artist = artist,
        favorite = true,
        thumbnailUrl = "https://example.invalid/$id.jpg",
        lastModifiedAt = 1L,
        version = 1L,
    )

    private fun seedManga(manga: Manga) {
        driver.execute(
            null,
            "INSERT INTO mangas(_id, source, url, title, author, artist, thumbnail_url, status, favorite, " +
                "initialized, viewer, chapter_flags, cover_last_modified, date_added, last_modified_at, version) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 0, 1, 0, 0, 0, 0, 0, ?, ?)",
            9,
        ) {
            bindLong(0, manga.id)
            bindLong(1, manga.source)
            bindString(2, manga.url)
            bindString(3, manga.title)
            bindString(4, manga.author)
            bindString(5, manga.artist)
            bindString(6, manga.thumbnailUrl)
            bindLong(7, manga.lastModifiedAt)
            bindLong(8, manga.version)
        }
    }

    private fun seedIdentity(id: Long, portableKey: String, displayName: String) {
        driver.execute(
            null,
            "INSERT INTO author_archive_creators(_id, portable_key, display_name, normalized_name, sort_name, " +
                "status, needs_review, created_at, last_modified_at) VALUES (?, ?, ?, 'same', ?, 'ACTIVE', 0, 1, 1)",
            4,
        ) {
            bindLong(0, id)
            bindString(1, portableKey)
            bindString(2, displayName)
            bindString(3, displayName)
        }
        driver.execute(
            null,
            "INSERT INTO author_archive_aliases(creator_id, raw_alias, normalized_alias, source, evidence, " +
                "confidence, is_manual, created_at, last_modified_at) VALUES (?, ?, 'same', 'MANUAL', " +
                "'fixture', 1, 1, 1, 1)",
            2,
        ) {
            bindLong(0, id)
            bindString(1, displayName)
        }
    }

    private fun queryLong(sql: String): Long = driver.executeQuery(
        null,
        sql,
        { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else -1L) },
        0,
    ).value

    private fun queryStrings(sql: String): List<String> = driver.executeQuery(
        null,
        sql,
        { cursor ->
            val values = mutableListOf<String>()
            while (cursor.next().value) values += cursor.getString(0)!!
            app.cash.sqldelight.db.QueryResult.Value(values)
        },
        0,
    ).value

    private fun executeSql(sql: String) {
        driver.execute(null, sql, 0)
    }

    private fun sequentialKeys(): () -> String {
        var next = 1L
        return { "generated-${next++}" }
    }
}

private class RecordingDiscoveryPort(
    private val works: List<CreatorSourceWorkSnapshot>,
) : CreatorDiscoverySourcePort {
    val requestedPages = mutableListOf<Int>()

    override suspend fun enabledSourcesSnapshot() = listOf(
        EnabledCreatorSource(
            sourceId = 10L,
            displayName = "Fixture",
            capabilities = setOf(CreatorSourceCapability.CATALOGUE_SEARCH_FALLBACK, CreatorSourceCapability.DETAILS),
        ),
    )

    override suspend fun searchPage(request: BoundedAuthorSearchPageRequest): CreatorSourcePageResult {
        requestedPages += request.page
        return CreatorSourcePageResult.Content(works, hasNextPage = true)
    }

    override suspend fun loadDetails(key: SourceWorkNaturalKey): CreatorSourceDetailsResult {
        val work = works.first { it.key == key }
        return CreatorSourceDetailsResult.Content(
            CreatorSourceDetails(
                work = work,
                readingLanguageTag = "en",
                originalLanguageTag = null,
                metadata = emptyMap(),
            ),
        )
    }
}
