package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import mihon.data.sync.journal.SyncLocalJournal
import mihon.data.sync.runtime.SyncRunState
import mihon.data.sync.runtime.SyncRunStore
import mihon.domain.sync.runtime.SyncTrigger
import mihon.domain.sync.transport.SyncInitializationResult
import mihon.domain.sync.transport.SyncRepository
import mihon.domain.sync.transport.SyncTransportPort
import mockwebserver3.MockResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.util.Collections
import java.util.Random
import mihon.domain.sync.runtime.SyncRunStatus as DomainSyncRunStatus

/** Fixed-seed fault interleavings over the real database exchange, not a UI/scheduler state-machine test. */
class SyncFaultInterleavingAcceptanceTest {
    private val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
    private val secret = mihon.domain.sync.crypto.SyncSecret.fromBytes(ByteArray(32) { (it + 1).toByte() })

    @Test
    fun `one hundred fixed seeds eventually converge through bounded exchange faults`() = runBlocking {
        val gitSafety = SyncGitSafetyContractTest()
        gitSafety.GitFixture(empty = false, repositoryOverride = repository).use { git ->
            val transport = git.transport()
            val initialized = transport.initialize(repository, "space", 1)
            assertTrue(
                initialized is SyncInitializationResult.Initialized || initialized is SyncInitializationResult.Adopted,
                "fixture initialization failed: $initialized",
            )
            openStorage().use { first ->
                openStorage().use { second ->
                    val actors = listOf("seed-device-a", "seed-device-b")
                    val stores = listOf(first, second)
                    stores.forEachIndexed { index, storage -> storage.connect(actors[index], repository) }
                    val expectedFavorites = linkedSetOf<String>()
                    var nextFavorite = 0
                    val replaySeed = System.getProperty("mihon.sync.t75.seed")?.toIntOrNull()
                    require(replaySeed == null || replaySeed in 0 until SEED_COUNT) {
                        "mihon.sync.t75.seed must be between 0 and ${SEED_COUNT - 1}"
                    }
                    // Replaying seed K must recreate the shared remote/database history that precedes K.
                    val seeds = 0..(replaySeed ?: (SEED_COUNT - 1))

                    for (seed in seeds) {
                        val random = Random(seed.toLong())
                        val steps = Step.values().toMutableList()
                        Collections.shuffle(steps, random)
                        val trace = mutableListOf<String>()
                        val conflictsBeforeSeed = git.conflicts.get()
                        var interruptionObserved = false
                        var interruptionPending = false
                        try {
                            for (step in steps) {
                                val selected = random.nextInt(stores.size)
                                val other = 1 - selected
                                val url = "/fault-seed-$seed-item-${nextFavorite++}"
                                trace += "$step(node=$selected,url=$url)"
                                when (step) {
                                    Step.ADD -> {
                                        stores[selected].favorite(url)
                                        expectedFavorites += url
                                    }
                                    Step.RECEIVE -> {
                                        stores[other].favorite(url)
                                        expectedFavorites += url
                                        trace += "exchange(node=$other,receive=$url)"
                                        runExchangeWithRetry(stores[other], transport, git, seed, trace) {
                                            if (interruptionPending && git.nextReadFailure == null) {
                                                interruptionObserved = true
                                                interruptionPending = false
                                            }
                                        }
                                        trace += "exchange(node=$selected,receive=$url)"
                                        runExchangeWithRetry(stores[selected], transport, git, seed, trace) {
                                            if (interruptionPending && git.nextReadFailure == null) {
                                                interruptionObserved = true
                                                interruptionPending = false
                                            }
                                        }
                                    }
                                    Step.PAUSE_RESUME -> {
                                        stores[selected].favorite(url)
                                        expectedFavorites += url
                                        val runs = SyncRunStore(stores[selected].handler)
                                        val run = runs.start("space", 1, SyncTrigger.MANUAL)
                                        runs.pause(run.runId)
                                        assertEquals(SyncRunState.PAUSED_USER, runs.get(run.runId)?.state)
                                        assertTrue(
                                            SyncLocalJournal(stores[selected].handler)
                                                .pendingEvents("space", 1).isNotEmpty(),
                                            "paused work must remain queued",
                                        )
                                        assertTrue(runs.resumeIfAllowed(run.runId))
                                        runs.finish(run.runId, SyncRunState.SUCCEEDED)
                                    }
                                    Step.DISCONNECT_RECONNECT -> {
                                        stores[selected].favorite(url)
                                        expectedFavorites += url
                                        SyncLocalJournal(stores[selected].handler).disconnect("space", 1)
                                        val requestsBefore = git.server.requestCount
                                        val skipped = stores[selected].exchange(transport, secret, repository)
                                        assertEquals(DomainSyncRunStatus.SKIPPED, skipped.status)
                                        assertEquals(requestsBefore, git.server.requestCount)
                                        stores[selected].connect(actors[selected], repository)
                                    }
                                    Step.CONFLICT -> {
                                        stores[selected].favorite(url)
                                        expectedFavorites += url
                                        git.competingWrites = 1
                                    }
                                    Step.INTERRUPTION -> {
                                        stores[selected].favorite(url)
                                        expectedFavorites += url
                                        git.nextReadFailure = MockResponse(code = 500, body = "seeded interruption")
                                        interruptionPending = true
                                    }
                                }
                            }

                            // Drain both directions twice; a failed network attempt is retried from durable work.
                            repeat(2) {
                                stores.forEach { storage ->
                                    val result = storage.exchange(transport, secret, repository)
                                    if (interruptionPending && git.nextReadFailure == null) {
                                        interruptionObserved = true
                                        interruptionPending = false
                                    }
                                    if (result.status != DomainSyncRunStatus.SUCCESS) {
                                        val retry = storage.exchange(transport, secret, repository)
                                        assertEquals(
                                            DomainSyncRunStatus.SUCCESS,
                                            retry.status,
                                            "seed=$seed trace=$trace first=${result.problem} retry=${retry.problem}",
                                        )
                                    }
                                }
                            }
                            assertTrue(
                                interruptionObserved,
                                "seed=$seed trace=$trace did not reach the interruption point",
                            )
                            assertTrue(
                                git.conflicts.get() > conflictsBeforeSeed,
                                "seed=$seed trace=$trace did not exercise the competing ref conflict",
                            )
                            stores.forEachIndexed { index, storage ->
                                assertEquals(
                                    expectedFavorites,
                                    storage.manga.getLibraryManga().map { it.manga.url }.toSet(),
                                    "seed=$seed trace=$trace node=$index",
                                )
                                assertTrue(
                                    SyncLocalJournal(storage.handler).pendingEvents("space", 1).isEmpty(),
                                    "seed=$seed trace=$trace node=$index left unsent events",
                                )
                                val favorites = storage.manga.getLibraryManga().map { it.manga.url }
                                assertEquals(
                                    favorites.size,
                                    favorites.toSet().size,
                                    "seed=$seed trace=$trace node=$index duplicated projection",
                                )
                            }
                        } catch (failure: Throwable) {
                            throw AssertionError("T75 replay seed=$seed actionPrefix=$trace", failure)
                        }
                    }

                    val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()
                    assertEquals(snapshot.batches.map { it.batchId }.distinct().size, snapshot.batches.size)
                    assertTrue(expectedFavorites.isNotEmpty())
                    println(
                        "{\"scenario\":\"T75-fixed-seeds\",\"seeds\":${seeds.count()}," +
                            "\"favorites\":${expectedFavorites.size},\"remoteBatches\":${snapshot.batches.size}}",
                    )
                }
            }
        }
    }

    private suspend fun runExchangeWithRetry(
        storage: SyncRuntimeStorageContract.Storage,
        transport: SyncTransportPort,
        git: SyncGitSafetyContractTest.GitFixture,
        seed: Int,
        trace: MutableList<String>,
        onExchange: () -> Unit,
    ) {
        var result = storage.exchange(transport, secret, repository)
        onExchange()
        if (result.status != DomainSyncRunStatus.SUCCESS) {
            result = storage.exchange(transport, secret, repository)
            onExchange()
        }
        assertEquals(
            DomainSyncRunStatus.SUCCESS,
            result.status,
            "seed=$seed trace=$trace retry=${result.problem}",
        )
    }

    private fun openStorage(): SyncRuntimeStorageContract.Storage {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        return SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database, driver))
    }

    private enum class Step {
        ADD,
        RECEIVE,
        PAUSE_RESUME,
        DISCONNECT_RECONNECT,
        CONFLICT,
        INTERRUPTION,
    }

    private companion object {
        const val SEED_COUNT = 100
    }
}
