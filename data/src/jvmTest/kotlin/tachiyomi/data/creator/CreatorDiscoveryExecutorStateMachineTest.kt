package tachiyomi.data.creator

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.creator.model.ArchiveWatchPolicy
import tachiyomi.domain.creator.model.CreatorRelationOrigin
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.DecisionActor
import tachiyomi.domain.creator.model.DiscoveryCommit
import tachiyomi.domain.creator.model.DiscoveryKind
import tachiyomi.domain.creator.model.DiscoveryRunState
import tachiyomi.domain.creator.model.LanguageAssertionContract
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.LanguageEvidenceKind
import tachiyomi.domain.creator.model.ReviewDisposition
import tachiyomi.domain.creator.model.SourceCheckpointResult
import tachiyomi.domain.creator.model.SourceCheckpointUpdate
import tachiyomi.domain.creator.model.SourceDiscoveryObservation
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.WatchBaselineState
import tachiyomi.domain.creator.service.BoundedAuthorSearchPageRequest
import tachiyomi.domain.creator.service.CreatorDiscoveryBackoff
import tachiyomi.domain.creator.service.CreatorDiscoveryBounds
import tachiyomi.domain.creator.service.CreatorDiscoveryService
import tachiyomi.domain.creator.service.CreatorDiscoverySourcePort
import tachiyomi.domain.creator.service.CreatorSourceCapability
import tachiyomi.domain.creator.service.CreatorSourceDetails
import tachiyomi.domain.creator.service.CreatorSourceDetailsResult
import tachiyomi.domain.creator.service.CreatorSourceFailure
import tachiyomi.domain.creator.service.CreatorSourcePageResult
import tachiyomi.domain.creator.service.CreatorSourceWorkSnapshot
import tachiyomi.domain.creator.service.EnabledCreatorSource

/**
 * AA2-02 executor contract against the real SQLDelight repository.
 *
 * Every scenario drives the production CreatorDiscoveryService through the production
 * CreatorRepositoryImpl and observes the persisted run/checkpoint/discovery/outbox tables, so a
 * broken executor, broken repository wiring or a bypassed state machine fails these tests.
 */
class CreatorDiscoveryExecutorStateMachineTest {

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
            portableKeyFactory = { "key-${System.nanoTime()}-${nextKey++}" },
        )
    }

    private var nextKey = 0L

    @Test
    fun `first successful scan archives and completes source baseline without any discovery or outbox`() {
        runBlocking {
            val now = MutableClock(1_000L)
            val creatorId = seedWatch(now)
            val port = ScriptedDiscoveryPort().apply {
                put(10L, work("/a", "Work A", "ONE"))
            }
            val service = service(port, now)

            val result = service.discoverDueWatches()

            result.newCandidateCount shouldBe 0
            result.errorCount shouldBe 0
            result.runState shouldBe DiscoveryRunState.SUCCEEDED
            result.leaseBusy shouldBe false
            result.skipped shouldBe false
            result.sourceResults.single().failure shouldBe null
            repository.getUnreadDiscoveries(10L) shouldBe emptyList()
            repository.getPendingNotificationOutbox(now.value, 10L) shouldBe emptyList()
            queryLong("SELECT COUNT(*) FROM author_archive_discoveries") shouldBe 0L
            queryLong("SELECT COUNT(*) FROM author_archive_notification_outbox") shouldBe 0L
            queryLong("SELECT COUNT(*) FROM author_archive_source_works") shouldBe 1L
            queryLong(
                "SELECT COUNT(*) FROM author_archive_source_work_creators WHERE verification = 'VERIFIED'",
            ) shouldBe 1L
            queryStrings("SELECT baseline_state FROM author_archive_watch_sources WHERE source_id = 10")
                .shouldContainExactly("BASELINED")
            queryLong("SELECT baseline_generation FROM author_archive_watch_sources WHERE source_id = 10") shouldBe 1L
            queryStrings("SELECT result_state FROM author_archive_source_checkpoints WHERE source_id = 10")
                .shouldContainExactly("SUCCESS")
            queryStrings("SELECT state FROM author_archive_runs").shouldContainExactly("SUCCEEDED")
        }
    }

    @Test
    fun `second identical scan produces no events and no duplicate run`() {
        runBlocking {
            val now = MutableClock(1_000L)
            val creatorId = seedWatch(now)
            val port = ScriptedDiscoveryPort().apply {
                put(10L, work("/a", "Work A", "ONE"))
            }
            val service = service(port, now)

            service.discoverDueWatches()
            now.advance(60_000L)
            val second = service.discoverDueWatches()

            second.newCandidateCount shouldBe 0
            repository.getUnreadDiscoveries(10L) shouldBe emptyList()
            repository.getPendingNotificationOutbox(now.value, 10L) shouldBe emptyList()
            queryLong("SELECT COUNT(*) FROM author_archive_discoveries") shouldBe 0L
            queryLong("SELECT COUNT(*) FROM author_archive_notification_outbox") shouldBe 0L
            queryLong("SELECT COUNT(*) FROM author_archive_source_work_creators") shouldBe 1L
            // One run record per invocation, never two for the same scan.
            queryLong("SELECT COUNT(*) FROM author_archive_runs") shouldBe 2L
        }
    }

    @Test
    fun `new qualified relation after baseline creates exactly one discovery and outbox`() {
        runBlocking {
            val now = MutableClock(1_000L)
            val creatorId = seedWatch(now)
            val port = ScriptedDiscoveryPort().apply {
                put(10L, work("/a", "Work A", "ONE"))
            }
            val service = service(port, now)
            service.discoverDueWatches()

            port.put(10L, work("/b", "Work B", "ONE"))
            now.advance(60_000L)
            val result = service.discoverDueWatches()

            result.sourceResults.single().failure shouldBe null
            result.newCandidateCount shouldBe 1
            result.runState shouldBe DiscoveryRunState.SUCCEEDED
            val unread = repository.getUnreadDiscoveries(10L)
            unread.size shouldBe 1
            unread.single().sourceWork.stableSourceUrl shouldBe "/b"
            unread.single().kind shouldBe DiscoveryKind.NEW_WORK_CANDIDATE
            repository.getPendingNotificationOutbox(now.value, 10L).size shouldBe 1
            queryLong("SELECT COUNT(*) FROM author_archive_discoveries") shouldBe 1L
            queryLong("SELECT COUNT(*) FROM author_archive_notification_outbox") shouldBe 1L
        }
    }

    @Test
    fun `metadata refresh of an existing work produces no events`() {
        runBlocking {
            val now = MutableClock(1_000L)
            val creatorId = seedWatch(now)
            val port = ScriptedDiscoveryPort().apply {
                put(10L, work("/a", "Work A", "ONE"))
            }
            val service = service(port, now)
            service.discoverDueWatches()

            port.put(10L, work("/a", "Work A (updated)", "ONE", thumbnailUrl = "https://example.invalid/new.jpg"))
            now.advance(60_000L)
            val result = service.discoverDueWatches()

            result.newCandidateCount shouldBe 0
            queryLong("SELECT COUNT(*) FROM author_archive_discoveries") shouldBe 0L
            queryLong("SELECT COUNT(*) FROM author_archive_notification_outbox") shouldBe 0L
            queryLong("SELECT COUNT(*) FROM author_archive_source_work_creators") shouldBe 1L
        }
    }

    @Test
    fun `global source work existing for another watch is first relation for the new watch`() {
        runBlocking {
            val now = MutableClock(1_000L)
            val port = ScriptedDiscoveryPort().apply {
                put(10L, work("/a", "Work A", "ONE"))
            }
            val service = service(port, now)
            val creatorA = seedWatch(now, creatorName = "ONE", sourceIds = setOf(10L))
            val creatorB = seedWatch(now, creatorName = "TWO", sourceIds = setOf(10L))

            service.discoverDueWatches()
            service.discoverDueWatches()

            queryLong("SELECT COUNT(*) FROM author_archive_discoveries") shouldBe 0L

            port.put(10L, work("/c", "Work C", "ONE, TWO"))
            now.advance(60_000L)
            service.discoverDueWatches()
            service.discoverDueWatches()

            // Each watch sees the globally-known source work exactly once.
            queryLong("SELECT COUNT(*) FROM author_archive_discoveries") shouldBe 2L
            val unread = repository.getUnreadDiscoveries(10L)
            unread.map { it.creatorId }.toSet() shouldBe setOf(creatorA, creatorB)
            unread.map { it.sourceWork.stableSourceUrl }.toSet() shouldBe setOf("/c")
            queryLong("SELECT COUNT(*) FROM author_archive_notification_outbox") shouldBe 2L
        }
    }

    @Test
    fun `ignored discovery review is not rolled back by a rescan`() {
        runBlocking {
            val now = MutableClock(1_000L)
            val creatorId = seedWatch(now)
            val port = ScriptedDiscoveryPort().apply {
                put(10L, work("/a", "Work A", "ONE"))
            }
            val service = service(port, now)
            service.discoverDueWatches()
            port.put(10L, work("/b", "Work B", "ONE"))
            now.advance(60_000L)
            service.discoverDueWatches()
            val discovery = repository.getUnreadDiscoveries(10L).single()
            repository.setDiscoveryReview(discovery.id, ReviewDisposition.IGNORED, now.value)
            repository.markDiscoverySeen(discovery.id, now.value)

            now.advance(60_000L)
            val result = service.discoverDueWatches()

            result.newCandidateCount shouldBe 0
            queryLong("SELECT COUNT(*) FROM author_archive_discoveries") shouldBe 1L
            queryLong("SELECT COUNT(*) FROM author_archive_notification_outbox") shouldBe 1L
            queryStrings("SELECT review_disposition FROM author_archive_discoveries")
                .shouldContainExactly("IGNORED")
            queryStrings("SELECT read_state FROM author_archive_discoveries").shouldContainExactly("SEEN")
        }
    }

    @Test
    fun `single source failure keeps other sources committing and run is partial`() {
        runBlocking {
            val now = MutableClock(1_000L)
            val creatorId = seedWatch(now, sourceIds = setOf(10L, 20L))
            val port = ScriptedDiscoveryPort().apply {
                put(10L, work("/a", "Work A", "ONE"))
                fail(20L, CreatorSourceFailure.Http(500))
            }
            val service = service(port, now)

            val result = service.discoverDueWatches()

            result.errorCount shouldBe 1
            result.runState shouldBe DiscoveryRunState.PARTIAL
            val source10 = result.sourceResults.single { it.sourceId == 10L }
            source10.failure shouldBe null
            val source20 = result.sourceResults.single { it.sourceId == 20L }
            source20.failure shouldBe CreatorSourceFailure.Http(500)
            queryStrings("SELECT result_state FROM author_archive_source_checkpoints WHERE source_id = 10")
                .shouldContainExactly("SUCCESS")
            queryStrings("SELECT baseline_state FROM author_archive_watch_sources WHERE source_id = 10")
                .shouldContainExactly("BASELINED")
            queryStrings("SELECT result_state FROM author_archive_source_checkpoints WHERE source_id = 20")
                .shouldContainExactly("FAILED")
            queryLong(
                "SELECT consecutive_failures FROM author_archive_source_checkpoints WHERE source_id = 20",
            ) shouldBe
                1L
            queryLong(
                "SELECT backoff_until FROM author_archive_source_checkpoints WHERE source_id = 20",
            ) shouldBe CreatorDiscoveryBackoff.backoffUntilMillis(1_000L, 1)
            queryLong("SELECT next_due_at FROM author_archive_watch_sources WHERE source_id = 20") shouldBe
                CreatorDiscoveryBackoff.backoffUntilMillis(1_000L, 1)
            queryLong("SELECT next_due_at FROM author_archive_watch_sources WHERE source_id = 10") shouldBe
                61_000L
            queryStrings("SELECT state FROM author_archive_runs").shouldContainExactly("PARTIAL")
        }
    }

    @Test
    fun `all sources failed marks the run failed with per-source backoff`() {
        runBlocking {
            val now = MutableClock(1_000L)
            val creatorId = seedWatch(now, sourceIds = setOf(10L, 20L))
            val port = ScriptedDiscoveryPort().apply {
                fail(10L, CreatorSourceFailure.RateLimited(1_000L))
                fail(20L, CreatorSourceFailure.Timeout)
            }
            val service = service(port, now)

            val result = service.discoverDueWatches()

            result.errorCount shouldBe 2
            result.runState shouldBe DiscoveryRunState.FAILED
            queryLong("SELECT COUNT(*) FROM author_archive_source_checkpoints WHERE result_state = 'FAILED'") shouldBe
                2L
            queryLong(
                "SELECT COUNT(*) FROM author_archive_watch_sources WHERE baseline_state = 'NEEDS_BASELINE'",
            ) shouldBe
                2L
            queryStrings("SELECT state FROM author_archive_runs").shouldContainExactly("FAILED")
            queryLong("SELECT COUNT(*) FROM author_archive_discoveries") shouldBe 0L
        }
    }

    @Test
    fun `truncated bounded scan records truncated checkpoint and still baselines`() {
        runBlocking {
            val now = MutableClock(1_000L)
            val creatorId = seedWatch(now)
            val port = ScriptedDiscoveryPort().apply {
                put(10L, work("/a", "Work A", "ONE"))
                hasNextPage = true
            }
            val service = service(
                port,
                now,
                bounds = CreatorDiscoveryBounds(
                    maxAliases = 1,
                    maxPagesPerAlias = 1,
                    maxTotalPagesPerSource = 1,
                    maxConcurrentSources = 1,
                    sourceTimeoutMillis = 10_000,
                ),
            )

            val result = service.discoverDueWatches()

            result.sourceResults.single().truncated shouldBe true
            result.runState shouldBe DiscoveryRunState.SUCCEEDED
            queryStrings("SELECT result_state FROM author_archive_source_checkpoints WHERE source_id = 10")
                .shouldContainExactly("TRUNCATED")
            queryStrings("SELECT baseline_state FROM author_archive_watch_sources WHERE source_id = 10")
                .shouldContainExactly("BASELINED")
            queryLong("SELECT COUNT(*) FROM author_archive_discoveries") shouldBe 0L
        }
    }

    @Test
    fun `backoff grows across consecutive failures and resets after success`() {
        runBlocking {
            val now = MutableClock(1_000L)
            val creatorId = seedWatch(now)
            val port = ScriptedDiscoveryPort().apply {
                put(10L, work("/a", "Work A", "ONE"))
                fail(10L, CreatorSourceFailure.Http(500))
            }
            val service = service(port, now)

            service.discoverDueWatches()
            queryLong(
                "SELECT consecutive_failures FROM author_archive_source_checkpoints WHERE source_id = 10",
            ) shouldBe
                1L
            queryLong("SELECT backoff_until FROM author_archive_source_checkpoints WHERE source_id = 10") shouldBe
                CreatorDiscoveryBackoff.backoffUntilMillis(1_000L, 1)

            now.advance(CreatorDiscoveryBackoff.DELAY_MILLIS[0])
            service.discoverDueWatches()
            queryLong(
                "SELECT consecutive_failures FROM author_archive_source_checkpoints WHERE source_id = 10",
            ) shouldBe
                2L
            queryLong("SELECT backoff_until FROM author_archive_source_checkpoints WHERE source_id = 10") shouldBe
                CreatorDiscoveryBackoff.backoffUntilMillis(now.value, 2)

            now.advance(CreatorDiscoveryBackoff.DELAY_MILLIS[1])
            service.discoverDueWatches()
            queryLong(
                "SELECT consecutive_failures FROM author_archive_source_checkpoints WHERE source_id = 10",
            ) shouldBe
                3L
            queryLong("SELECT backoff_until FROM author_archive_source_checkpoints WHERE source_id = 10") shouldBe
                CreatorDiscoveryBackoff.backoffUntilMillis(now.value, 3)

            port.clearFailure(10L)
            now.advance(CreatorDiscoveryBackoff.DELAY_MILLIS[2])
            val result = service.discoverDueWatches()

            result.runState shouldBe DiscoveryRunState.SUCCEEDED
            queryLong(
                "SELECT consecutive_failures FROM author_archive_source_checkpoints WHERE source_id = 10",
            ) shouldBe
                0L
            queryLong("SELECT backoff_until FROM author_archive_source_checkpoints WHERE source_id = 10") shouldBe -1L
            queryStrings("SELECT result_state FROM author_archive_source_checkpoints WHERE source_id = 10")
                .shouldContainExactly("SUCCESS")
        }
    }

    @Test
    fun `reentrant concurrent request is reported busy and does not create a second run`() {
        runBlocking {
            val now = MutableClock(1_000L)
            val creatorId = seedWatch(now)
            val port = ScriptedDiscoveryPort().apply {
                put(10L, work("/a", "Work A", "ONE"))
            }
            val service = service(port, now)
            // A concurrent worker holds the lease and is mid-run; a second request must not start
            // another run for the same watch.
            repository.acquireWatchLease(creatorId, "other-worker", 10_000L, now.value)
            repository.createDiscoveryRun("active-run", creatorId, 1L, now.value)
            repository.updateDiscoveryRun("active-run", DiscoveryRunState.RUNNING, 0L, false, null, null, now.value)

            val result = service.discoverDueWatches()

            result.leaseBusy shouldBe true
            result.runState shouldBe null
            queryLong("SELECT COUNT(*) FROM author_archive_runs") shouldBe 1L
            queryLong("SELECT COUNT(*) FROM author_archive_discoveries") shouldBe 0L
        }
    }

    @Test
    fun `expired lease is reclaimed and the run completes`() {
        runBlocking {
            val now = MutableClock(1_000L)
            val creatorId = seedWatch(now)
            val port = ScriptedDiscoveryPort().apply {
                put(10L, work("/a", "Work A", "ONE"))
            }
            val service = service(port, now)
            repository.acquireWatchLease(creatorId, "stale-worker", 1_500L, now.value)

            now.advance(2_000L)
            val result = service.discoverDueWatches()

            result.leaseBusy shouldBe false
            result.runState shouldBe DiscoveryRunState.SUCCEEDED
            queryStrings("SELECT state FROM author_archive_runs").shouldContainExactly("SUCCEEDED")
            queryLong(
                "SELECT COUNT(*) FROM author_archive_watches WHERE creator_id = $creatorId AND lease_owner IS NULL",
            ) shouldBe 1L
        }
    }

    @Test
    fun `process recovery resumes an interrupted run exactly once without duplicates`() {
        runBlocking {
            val now = MutableClock(1_000L)
            val creatorId = seedWatch(now, sourceIds = setOf(10L, 20L))
            val port = ScriptedDiscoveryPort().apply {
                put(10L, work("/a", "Work A", "ONE"))
                put(20L, work("/x", "Work X", "ONE", sourceId = 20L))
            }
            val service = service(port, now)
            service.discoverDueWatches()
            queryLong("SELECT COUNT(*) FROM author_archive_discoveries") shouldBe 0L

            // A new work appears and the process crashes after committing source 10's discovery.
            port.put(10L, work("/b", "Work B", "ONE"))
            port.put(20L, work("/y", "Work Y", "ONE", sourceId = 20L))
            now.advance(60_000L)
            repository.upsertSourceWork(
                sourceId = 10L,
                stableSourceUrl = "/b",
                mangaId = null,
                title = "Work B",
                authorText = "ONE",
                artistText = null,
                thumbnailUrl = null,
                detailsFetchedAt = now.value,
            )
            repository.upsertSourceWorkCreator(
                sourceWork = SourceWorkNaturalKey(10L, "/b"),
                creatorId = creatorId,
                role = tachiyomi.domain.creator.model.CreatorRole.AUTHOR,
                order = 0,
                origin = tachiyomi.domain.creator.model.CreatorRelationOrigin.AUTOMATIC,
                verification = tachiyomi.domain.creator.model.CreatorRelationVerification.VERIFIED,
                sourceText = "ONE",
                confidence = 0.95,
                evidence = "exact creator metadata field",
            )
            repository.commitDiscovery(
                DiscoveryCommit(
                    creatorId = creatorId,
                    sourceWork = SourceWorkNaturalKey(10L, "/b"),
                    kind = DiscoveryKind.NEW_WORK_CANDIDATE,
                    reason = "verified creator relation",
                    baselineGeneration = 1L,
                    discoveredAt = now.value,
                    outboxChannel = "DESKTOP",
                    idempotencyKey = "crash-run:/b",
                ),
            )
            repository.createDiscoveryRun("crash-run", creatorId, 2L, now.value)
            repository.updateDiscoveryRun("crash-run", DiscoveryRunState.RUNNING, 0L, false, null, null, now.value)
            repository.updateSourceCheckpoint(
                SourceCheckpointUpdate(
                    creatorId = creatorId,
                    sourceId = 10L,
                    cursor = null,
                    result = SourceCheckpointResult.SUCCESS,
                    consecutiveFailures = 0L,
                    backoffUntil = null,
                    checkedAt = now.value,
                    successAt = now.value,
                    nextDueAt = now.value + 60_000L,
                    baselineState = WatchBaselineState.BASELINED,
                    baselineGeneration = 1L,
                ),
            )
            repository.getRecoverableDiscoveryRuns().map { it.runKey } shouldBe listOf("crash-run")

            now.advance(1_000L)
            val result = service.discoverDueWatches()

            result.runState shouldBe DiscoveryRunState.SUCCEEDED
            // The initial scan's run plus the crashed run; the crashed run is resumed exactly once
            // and no new auto run is created for the same due work.
            queryLong("SELECT COUNT(*) FROM author_archive_runs") shouldBe 2L
            queryLong("SELECT COUNT(*) FROM author_archive_runs WHERE run_key = 'crash-run'") shouldBe 1L
            queryLong("SELECT COUNT(*) FROM author_archive_runs WHERE run_key LIKE 'auto:%'") shouldBe 1L
            queryStrings("SELECT state FROM author_archive_runs").shouldContainExactly("SUCCEEDED", "SUCCEEDED")
            // The crashed run's discovery plus the resumed run's new relation: exactly one per watch/work.
            queryLong("SELECT COUNT(*) FROM author_archive_discoveries") shouldBe 2L
            queryLong("SELECT COUNT(*) FROM author_archive_notification_outbox") shouldBe 2L
            queryLong("SELECT COUNT(*) FROM author_archive_source_checkpoints WHERE result_state = 'SUCCESS'") shouldBe
                2L
        }
    }

    @Test
    fun `cancellation propagates and leaves committed sources consistent without cancelled writes`() {
        runBlocking {
            val now = MutableClock(1_000L)
            val creatorId = seedWatch(now, sourceIds = setOf(10L, 20L))
            val port = ScriptedDiscoveryPort().apply {
                put(10L, work("/a", "Work A", "ONE"))
                put(20L, work("/x", "Work X", "ONE", sourceId = 20L))
                cancelOnSearchFor = 20L
            }
            val service = service(
                port,
                now,
                bounds = CreatorDiscoveryBounds(
                    maxAliases = 1,
                    maxPagesPerAlias = 1,
                    maxTotalPagesPerSource = 1,
                    maxConcurrentSources = 1,
                    sourceTimeoutMillis = 10_000,
                ),
            )

            shouldThrow<CancellationException> { service.discoverDueWatches() }

            queryStrings("SELECT state FROM author_archive_runs").shouldContainExactly("CANCELLED")
            // Only source 10 committed; the cancelled source 20 got no checkpoint at all.
            queryLong("SELECT COUNT(*) FROM author_archive_source_checkpoints") shouldBe 1L
            queryStrings("SELECT result_state FROM author_archive_source_checkpoints WHERE source_id = 10")
                .shouldContainExactly("SUCCESS")
            queryStrings("SELECT baseline_state FROM author_archive_watch_sources WHERE source_id = 10")
                .shouldContainExactly("BASELINED")
            queryLong("SELECT COUNT(*) FROM author_archive_discoveries") shouldBe 0L
            queryLong("SELECT COUNT(*) FROM author_archive_notification_outbox") shouldBe 0L
            queryLong(
                "SELECT COUNT(*) FROM author_archive_watches WHERE creator_id = $creatorId AND lease_owner IS NULL",
            ) shouldBe 1L
        }
    }

    @Test
    fun `manual force bypasses backoff but honours the concurrent lease`() {
        runBlocking {
            val now = MutableClock(1_000L)
            val creatorId = seedWatch(now)
            val port = ScriptedDiscoveryPort().apply {
                put(10L, work("/a", "Work A", "ONE"))
                fail(10L, CreatorSourceFailure.Http(500))
            }
            val service = service(port, now)
            service.discoverDueWatches()
            queryLong(
                "SELECT consecutive_failures FROM author_archive_source_checkpoints WHERE source_id = 10",
            ) shouldBe
                1L

            // Source is in backoff (not due), but manual force still runs and succeeds.
            port.clearFailure(10L)
            val forced = service.discoverCreator(creatorId)
            forced.runState shouldBe DiscoveryRunState.SUCCEEDED
            forced.leaseBusy shouldBe false
            queryLong(
                "SELECT consecutive_failures FROM author_archive_source_checkpoints WHERE source_id = 10",
            ) shouldBe
                0L
            queryLong("SELECT backoff_until FROM author_archive_source_checkpoints WHERE source_id = 10") shouldBe -1L

            // A concurrent worker holding the lease is never bypassed.
            repository.acquireWatchLease(creatorId, "other-worker", now.value + 60_000L, now.value)
            val busy = service.discoverCreator(creatorId)
            busy.leaseBusy shouldBe true
            queryLong("SELECT COUNT(*) FROM author_archive_runs") shouldBe 2L
        }
    }

    @Test
    fun `scan with no enabled sources is skipped without creating a run`() {
        runBlocking {
            val now = MutableClock(1_000L)
            val creatorId = seedWatch(now)
            val port = ScriptedDiscoveryPort() // no sources at all
            val service = service(port, now)

            val result = service.discoverDueWatches()

            result.skipped shouldBe true
            result.runState shouldBe null
            queryLong("SELECT COUNT(*) FROM author_archive_runs") shouldBe 0L
        }
    }

    @Test
    fun `catalogue cache alone is not treated as library membership`() {
        runBlocking {
            val key = SourceWorkNaturalKey(10L, "/cached")
            driver.execute(
                null,
                """
                INSERT INTO mangas(
                    _id, source, url, artist, author, description, genre, title, status,
                    thumbnail_url, favorite, last_update, next_update, initialized, viewer,
                    chapter_flags, cover_last_modified, date_added, update_strategy,
                    calculate_interval, last_modified_at, favorite_modified_at, version,
                    is_syncing, notes
                ) VALUES (
                    900, 10, '/cached', NULL, 'ONE', NULL, NULL, 'Cached', 0,
                    NULL, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, NULL, 0, 0, ''
                )
                """.trimIndent(),
                0,
            )

            repository.sourceWorkIsInLibraryOrHistory(key) shouldBe false
            driver.execute(null, "UPDATE mangas SET favorite = 1 WHERE _id = 900", 0)
            repository.sourceWorkIsInLibraryOrHistory(key) shouldBe true
        }
    }

    @Test
    fun `source observation rolls back all writes when outbox idempotency conflicts`() {
        runBlocking {
            val now = MutableClock(1_000L)
            val creatorId = seedWatch(now)
            repository.commitSourceDiscoveryObservation(
                observation(creatorId, SourceWorkNaturalKey(10L, "/first"), "shared-outbox", now.value),
            )

            shouldThrow<IllegalStateException> {
                repository.commitSourceDiscoveryObservation(
                    observation(creatorId, SourceWorkNaturalKey(10L, "/second"), "shared-outbox", now.value),
                )
            }

            queryLong(
                "SELECT COUNT(*) FROM author_archive_source_works " +
                    "WHERE source_id = 10 AND stable_source_url = '/second'",
            ) shouldBe 0L
            queryLong("SELECT COUNT(*) FROM author_archive_discoveries") shouldBe 1L
            queryLong("SELECT COUNT(*) FROM author_archive_notification_outbox") shouldBe 1L
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────

    private suspend fun seedWatch(
        now: MutableClock,
        creatorName: String = "ONE",
        sourceIds: Set<Long> = setOf(10L),
    ): Long {
        val creator = repository.upsertCreator(creatorName)
        repository.upsertWatchPolicy(
            ArchiveWatchPolicy(
                creatorId = creator.id,
                enabled = true,
                periodMillis = 60_000L,
                sourceIds = sourceIds,
                readingLanguageTags = setOf("en"),
            ),
            now = now.value,
        )
        return creator.id
    }

    private fun service(
        port: ScriptedDiscoveryPort,
        now: MutableClock,
        bounds: CreatorDiscoveryBounds = CreatorDiscoveryBounds(
            maxAliases = 1,
            maxPagesPerAlias = 2,
            maxTotalPagesPerSource = 2,
            maxConcurrentSources = 2,
            sourceTimeoutMillis = 10_000,
        ),
    ): CreatorDiscoveryService = CreatorDiscoveryService(
        creatorRepository = repository,
        archiveRepository = repository,
        sourcePort = port,
        bounds = bounds,
        clock = { now.value },
    )

    private fun work(
        url: String,
        title: String,
        author: String,
        sourceId: Long = 10L,
        thumbnailUrl: String? = null,
    ) = CreatorSourceWorkSnapshot(
        key = SourceWorkNaturalKey(sourceId, url),
        title = title,
        authorText = author,
        artistText = null,
        thumbnailUrl = thumbnailUrl,
    )

    private fun observation(
        creatorId: Long,
        key: SourceWorkNaturalKey,
        outboxIdempotencyKey: String,
        now: Long,
    ) = SourceDiscoveryObservation(
        sourceWork = key,
        title = key.stableSourceUrl,
        authorText = "ONE",
        artistText = null,
        thumbnailUrl = null,
        detailsFetchedAt = now,
        creatorId = creatorId,
        role = CreatorRole.AUTHOR,
        order = 0,
        origin = CreatorRelationOrigin.AUTOMATIC,
        verification = CreatorRelationVerification.VERIFIED,
        sourceText = "ONE",
        confidence = 1.0,
        relationEvidence = "exact author",
        languageAssertion = LanguageAssertionContract(
            dimension = LanguageDimension.READING,
            tag = "en",
            confidence = 1.0,
            evidenceKind = LanguageEvidenceKind.STRUCTURED_METADATA,
        ),
        languageActor = DecisionActor.ALGORITHM,
        languageEvidencePayload = "test metadata",
        languageAlgorithmVersion = "test-v1",
        languageAssertedAt = now,
        languageIdempotencyKey = "language:${key.sourceId}:${key.stableSourceUrl}",
        notificationsEnabled = true,
        baselineState = WatchBaselineState.BASELINED,
        discoveryReason = "verified creator relation",
        baselineGeneration = 1,
        discoveredAt = now,
        outboxChannel = "DESKTOP",
        discoveryIdempotencyKey = outboxIdempotencyKey,
    )

    private fun queryLong(sql: String): Long = driver.executeQuery(
        null,
        sql,
        { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: -1L else -1L)
        },
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

    private class MutableClock(initial: Long) {
        var value: Long = initial
            private set

        fun advance(delta: Long) {
            value += delta
        }
    }
}

private class ScriptedDiscoveryPort : CreatorDiscoverySourcePort {
    private val sources = linkedMapOf<Long, MutableList<CreatorSourceWorkSnapshot>>()
    private val failures = mutableMapOf<Long, CreatorSourceFailure>()
    var hasNextPage: Boolean = false
    var cancelOnSearchFor: Long? = null
    val requestedPages = mutableListOf<Pair<Long, Int>>()

    fun put(sourceId: Long, work: CreatorSourceWorkSnapshot) {
        sources.getOrPut(sourceId) { mutableListOf() }.removeAll { it.key == work.key }
        sources.getValue(sourceId).add(work)
    }

    fun fail(sourceId: Long, failure: CreatorSourceFailure) {
        failures[sourceId] = failure
        sources.getOrPut(sourceId) { mutableListOf() }
    }

    fun clearFailure(sourceId: Long) {
        failures.remove(sourceId)
    }

    override suspend fun enabledSourcesSnapshot(): List<EnabledCreatorSource> =
        sources.keys.sorted().map { sourceId ->
            EnabledCreatorSource(
                sourceId = sourceId,
                displayName = "Source-$sourceId",
                capabilities = setOf(
                    CreatorSourceCapability.CATALOGUE_SEARCH_FALLBACK,
                    CreatorSourceCapability.DETAILS,
                ),
            )
        }

    override suspend fun searchPage(request: BoundedAuthorSearchPageRequest): CreatorSourcePageResult {
        requestedPages += request.sourceId to request.page
        if (cancelOnSearchFor == request.sourceId) throw CancellationException("user cancelled")
        failures[request.sourceId]?.let { return CreatorSourcePageResult.Failure(it) }
        val works = sources[request.sourceId].orEmpty()
        if (works.isEmpty()) return CreatorSourcePageResult.Empty
        return CreatorSourcePageResult.Content(works, hasNextPage)
    }

    override suspend fun loadDetails(key: SourceWorkNaturalKey): CreatorSourceDetailsResult {
        val work = sources[key.sourceId]?.firstOrNull { it.key == key }
            ?: return CreatorSourceDetailsResult.Failure(CreatorSourceFailure.MissingSource)
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
