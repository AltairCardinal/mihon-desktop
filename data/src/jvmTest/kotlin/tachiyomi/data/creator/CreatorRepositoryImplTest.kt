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
import tachiyomi.domain.creator.model.ArchiveAppendOutcome
import tachiyomi.domain.creator.model.ArchiveLanguageSubject
import tachiyomi.domain.creator.model.ArchiveUpsertOutcome
import tachiyomi.domain.creator.model.CreatorRelationOrigin
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.DecisionActor
import tachiyomi.domain.creator.model.DiscoveryCandidateState
import tachiyomi.domain.creator.model.LanguageAssertionContract
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.LanguageEvidenceKind
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.WorkDecisionContract
import tachiyomi.domain.creator.model.WorkDecisionState
import tachiyomi.domain.creator.model.WorkMatchState
import tachiyomi.domain.creator.repository.CreatorArchiveBootstrap

class CreatorRepositoryImplTest {

    private lateinit var repository: CreatorRepositoryImpl
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var handler: JvmDatabaseHandler

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
    fun `ambiguous normalized aliases never silently select or merge an identity`() {
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

        shouldThrow<IllegalStateException> {
            runBlocking { repository.upsertCreator("same") }
        }
        queryLong("SELECT COUNT(*) FROM author_archive_creators") shouldBe 2L
        queryLong("SELECT COUNT(*) FROM creators") shouldBe 0L
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
    fun `typed source work relation reports outcomes and automatic input cannot overwrite user binding`() {
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
    fun `upsertCreator reuses normalized name`() {
        runBlocking {
            val first = repository.upsertCreator(" ONE ")
            val second = repository.upsertCreator("one")

            first.id shouldBe second.id
            repository.getCreator(first.id) shouldBe second
            second.normalizedName shouldBe "one"
            repository.getCreatorsAsFlow().first().size shouldBe 1
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
