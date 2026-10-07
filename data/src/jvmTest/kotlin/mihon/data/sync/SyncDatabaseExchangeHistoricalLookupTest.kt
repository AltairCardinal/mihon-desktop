package mihon.data.sync

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import mihon.data.sync.inbox.SyncDiscoveryStore
import mihon.data.sync.inbox.SyncInboxExchange
import mihon.data.sync.inbox.SyncInboxStore
import mihon.data.sync.transport.SyncBatchSyncService
import mihon.data.sync.transport.SyncSnapshotManifestBinding
import mihon.data.sync.transport.SyncSnapshotManifestStore
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffect
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.crypto.SyncSecret
import mihon.domain.sync.transport.SyncBatchIndexEntry
import mihon.domain.sync.transport.SyncGitTree
import mihon.domain.sync.transport.SyncGitTreeEntry
import mihon.domain.sync.transport.SyncInitializationResult
import mihon.domain.sync.transport.SyncPreparedUpload
import mihon.domain.sync.transport.SyncPublishResult
import mihon.domain.sync.transport.SyncRepository
import mihon.domain.sync.transport.SyncSnapshot
import mihon.domain.sync.transport.SyncTransportPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

class SyncDatabaseExchangeHistoricalLookupTest {
    @Test
    fun `cached snapshot consumes only its newest fence once`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database, driver)).use { storage ->
            val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
            storage.connect("receiver", repository)
            SyncGitSafetyContractTest().GitFixture().use { git ->
                val transport = git.transport()
                transport.initialize(repository, "space", 1)
                val guard = SyncInboxStore(storage.handler).remoteGuard
                transport.installSnapshotFenceProvider { spaceId, generation ->
                    guard.captureFence(spaceId, generation)
                }
                val first = transport.readSnapshot(repository, "space", 1).getOrThrow()
                val second = transport.readSnapshot(repository, "space", 1).getOrThrow()
                assertSame(first, second)
                val newestFence = requireNotNull(transport.takeSnapshotFence(second))
                guard.observe(second, newestFence)
                assertNull(transport.takeSnapshotFence(first))
                guard.observe(first)
                assertFalse(
                    storage.handler.await {
                        sync_remote_guardQueries.getGuard("space", 1).executeAsOne().blocked
                    },
                )
            }
        }
    }

    @Test
    fun `fallback snapshot cache retains only the latest head`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            val first = transport.readSnapshot(repository, "space", 1).getOrThrow()
            val firstHead = first.head
            assertSame(first, transport.readSnapshot(repository, "space", 1).getOrThrow())

            git.replaceFile(repository.branch, "README.md", "second head".encodeToByteArray())
            val second = transport.readSnapshot(repository, "space", 1).getOrThrow()
            assertTrue(second.head != firstHead)
            assertSame(second, transport.readSnapshot(repository, "space", 1).getOrThrow())

            // Re-pointing the fixture ref only probes cache capacity. Guard rollback rejection
            // remains covered by the remote-history safety contract.
            git.resetRef(repository.branch, firstHead)
            val reread = transport.readSnapshot(repository, "space", 1).getOrThrow()
            assertTrue(reread !== first, "an older head must be revalidated after eviction")
            assertSame(reread, transport.readSnapshot(repository, "space", 1).getOrThrow())
        }
    }

    @Test
    fun `deep wide tree cache stays within a byte budget and returns complete paths`() = runBlocking {
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
            val transport = git.transport()
            assertTrue(transport.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)

            val depth = 48
            val width = 1_200
            val prefix = (0 until depth).joinToString("/") { "level-$it" }
            git.replaceFiles(
                "mihon-sync",
                (0 until width).associate { "$prefix/file-$it.txt" to byteArrayOf(it.toByte()) },
            )
            val snapshot = transport.readSnapshot(repository, "space", 1).getOrThrow()

            assertFalse(snapshot.tree.truncated)
            assertEquals(width, snapshot.tree.entries.count { it.path.startsWith("$prefix/") })
            assertTrue(
                cachedTreeWeightBytes(transport) <= TREE_CACHE_BUDGET_BYTES,
                "cached Git tree material must stay within the fixed byte budget",
            )
        }
    }

    @Test
    fun `known historical ref rollback is blocked after a legal forward head`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database, driver)).use { storage ->
            val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
            storage.connect("receiver", repository)
            SyncGitSafetyContractTest().GitFixture().use { git ->
                val transport = git.transport()
                assertTrue(transport.initialize(repository, "space", 1) is SyncInitializationResult.Initialized)
                val inbox = SyncInboxStore(storage.handler)

                val headA = git.head("mihon-sync")
                val snapshotA = transport.readSnapshot(repository, "space", 1).getOrThrow()
                inbox.observeSnapshot(snapshotA)

                git.replaceFile("mihon-sync", "README.md", "outside sync namespace".encodeToByteArray())
                val snapshotB = transport.readSnapshot(repository, "space", 1).getOrThrow()
                assertFalse(snapshotB.head == headA)
                inbox.observeSnapshot(snapshotB)

                git.resetRef("mihon-sync", headA)
                val rolledBackSnapshotA = transport.readSnapshot(repository, "space", 1).getOrThrow()
                val rollback = runCatching { inbox.observeSnapshot(rolledBackSnapshotA) }

                assertTrue(rollback.isFailure, "a known historical ref must be rejected after head B was observed")
                assertTrue(
                    storage.handler.await {
                        sync_remote_guardQueries.getGuard("space", 1).executeAsOne().blocked
                    },
                )
            }
        }
    }

    private fun cachedTreeWeightBytes(transport: Any): Long {
        val cacheField = transport.javaClass.getDeclaredField("treeCache").apply { isAccessible = true }
        val values = (cacheField.get(transport) as Map<*, *>).values
        return values.sumOf { cached ->
            val entriesField = requireNotNull(cached).javaClass.getDeclaredField("entries").apply {
                isAccessible = true
            }
            val entries = entriesField.get(cached) as List<SyncGitTreeEntry>
            64L + entries.sumOf { entry ->
                64L + entry.path.encodeToByteArray().size + entry.mode.length + entry.type.length + entry.sha.length
            }
        }
    }

    private companion object {
        const val TREE_CACHE_BUDGET_BYTES = 4L * 1024 * 1024
    }

    @Test
    fun `persistent warm admission skips evidence and a blocked guard forces full validation`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database, driver)).use { storage ->
            val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
            storage.connect("receiver", repository)
            SyncGitSafetyContractTest().GitFixture(realTreeOids = true).use { git ->
                val transport = git.transport()
                transport.installSnapshotManifestStore(
                    SyncSnapshotManifestStore(storage.handler),
                    SyncSnapshotManifestBinding(1, 99, "stable-revision"),
                )
                transport.initialize(repository, "space", 1)
                val inbox = SyncInboxStore(storage.handler)
                val discovery = SyncDiscoveryStore(storage.handler)
                val cold = transport.readSnapshot(repository, "space", 1).getOrThrow()
                inbox.observeSnapshot(cold, discovery, transport.takeSnapshotManifest(cold))
                storage.handler.await {
                    val manifest = sync_remote_guardQueries.getSnapshotManifest("space", 1).executeAsOne()
                    assertEquals("", manifest.manifest_json)
                    assertEquals(cold.tree.sha, manifest.tree_sha)
                    assertEquals(
                        cold.tree.entries.size,
                        sync_remote_guardQueries
                            .getSnapshotManifestEntries("space", 1).executeAsList().size,
                    )
                }
                val evidenceAfterCold = inbox.remoteGuard.evidenceEvaluations

                val warm = transport.readSnapshot(repository, "space", 1).getOrThrow()
                val warmAdmission = requireNotNull(transport.takeWarmAdmission(warm))
                assertTrue(inbox.confirmWarmSnapshot(warm, warmAdmission))
                assertEquals(evidenceAfterCold, inbox.remoteGuard.evidenceEvaluations)

                // Snapshot lists are caller-owned: a transport caller can change them after
                // readSnapshot returns, while admission still points to that same object.
                val mutableEntries = warm.tree.entries as MutableList
                val removedEntry = mutableEntries.removeAt(mutableEntries.lastIndex)
                assertFalse(inbox.confirmWarmSnapshot(warm, warmAdmission))
                mutableEntries.add(removedEntry)

                storage.handler.await(inTransaction = true) {
                    sync_remote_guardQueries.blockGuard("space", 1)
                }
                val commitsBeforeBlockedRead = git.commitReads
                val blockedFallback = transport.readSnapshot(repository, "space", 1).getOrThrow()
                assertEquals(null, transport.takeWarmAdmission(blockedFallback))
                val candidate = transport.takeSnapshotManifest(blockedFallback)
                assertTrue(runCatching { inbox.observeSnapshot(blockedFallback, discovery, candidate) }.isFailure)
                assertTrue(inbox.remoteGuard.evidenceEvaluations > evidenceAfterCold)
                assertTrue(git.commitReads > commitsBeforeBlockedRead)
                assertTrue(
                    storage.handler.await {
                        sync_remote_guardQueries.getGuard("space", 1).executeAsOne().blocked
                    },
                )
            }
        }
    }

    @Test
    fun `new head updates only changed normalized manifest rows`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database, driver)).use { storage ->
            val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
            storage.connect("receiver", repository)
            SyncGitSafetyContractTest().GitFixture(realTreeOids = true).use { git ->
                val transport = git.transport()
                transport.installSnapshotManifestStore(
                    SyncSnapshotManifestStore(storage.handler),
                    SyncSnapshotManifestBinding(1, 99, "stable-revision"),
                )
                transport.initialize(repository, "space", 1)
                val inbox = SyncInboxStore(storage.handler)
                val first = transport.readSnapshot(repository, "space", 1).getOrThrow()
                inbox.observeSnapshot(first, discovery = null, manifest = transport.takeSnapshotManifest(first))
                driver.execute(null, "CREATE TABLE manifest_row_audit(op TEXT NOT NULL)", 0)
                driver.execute(
                    null,
                    "CREATE TRIGGER manifest_entry_insert AFTER INSERT ON sync_snapshot_manifest_entries " +
                        "BEGIN INSERT INTO manifest_row_audit VALUES ('insert'); END",
                    0,
                )
                driver.execute(
                    null,
                    "CREATE TRIGGER manifest_entry_update AFTER UPDATE ON sync_snapshot_manifest_entries " +
                        "BEGIN INSERT INTO manifest_row_audit VALUES ('update'); END",
                    0,
                )
                driver.execute(
                    null,
                    "CREATE TRIGGER manifest_entry_delete AFTER DELETE ON sync_snapshot_manifest_entries " +
                        "BEGIN INSERT INTO manifest_row_audit VALUES ('delete'); END",
                    0,
                )

                git.replaceFile("mihon-sync", "README.md", "changed outside sync namespace".encodeToByteArray())
                val second = transport.readSnapshot(repository, "space", 1).getOrThrow()
                inbox.observeSnapshot(second, discovery = null, manifest = transport.takeSnapshotManifest(second))

                assertEquals(1L, scalar(driver, "SELECT COUNT(*) FROM manifest_row_audit"))
                assertEquals(1L, scalar(driver, "SELECT COUNT(*) FROM manifest_row_audit WHERE op = 'update'"))
                assertEquals(
                    2L,
                    storage.handler.await {
                        sync_remote_guardQueries.getGuard("space", 1).executeAsOne().revision
                    },
                )
            }
        }
    }

    @Test
    fun `new head preserves BMP and supplementary plane manifest paths`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database, driver)).use { storage ->
            val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
            storage.connect("receiver", repository)
            SyncGitSafetyContractTest().GitFixture(realTreeOids = true).use { git ->
                val transport = git.transport()
                transport.installSnapshotManifestStore(
                    SyncSnapshotManifestStore(storage.handler),
                    SyncSnapshotManifestBinding(1, 99, "stable-revision"),
                )
                transport.initialize(repository, "space", 1)
                val bmpPath = "\uE000.txt"
                val supplementaryPath = "\uD800\uDC00.txt"
                git.replaceFiles(
                    "mihon-sync",
                    mapOf(bmpPath to byteArrayOf(1), supplementaryPath to byteArrayOf(2)),
                )
                val inbox = SyncInboxStore(storage.handler)
                val first = transport.readSnapshot(repository, "space", 1).getOrThrow()
                inbox.observeSnapshot(first, discovery = null, manifest = transport.takeSnapshotManifest(first))

                git.replaceFile("mihon-sync", "README.md", "unrelated head advance".encodeToByteArray())
                val second = transport.readSnapshot(repository, "space", 1).getOrThrow()
                inbox.observeSnapshot(second, discovery = null, manifest = transport.takeSnapshotManifest(second))

                val persistedPaths = storage.handler.await {
                    sync_remote_guardQueries.getSnapshotManifestEntries("space", 1).executeAsList().map {
                        it.path
                    }.toSet()
                }
                assertTrue(bmpPath in persistedPaths)
                assertTrue(supplementaryPath in persistedPaths)
                assertEquals(second.tree.entries.size, persistedPaths.size)
            }
        }
    }

    @Test
    fun `legacy full json manifest is a cache miss and is rebuilt without clearing the guard`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database, driver)).use { storage ->
            val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
            storage.connect("receiver", repository)
            SyncGitSafetyContractTest().GitFixture(realTreeOids = true).use { git ->
                val binding = SyncSnapshotManifestBinding(1, 99, "stable-revision")
                val first = git.transport().also {
                    it.installSnapshotManifestStore(SyncSnapshotManifestStore(storage.handler), binding)
                    it.initialize(repository, "space", 1)
                }
                val inbox = SyncInboxStore(storage.handler)
                val snapshot = first.readSnapshot(repository, "space", 1).getOrThrow()
                inbox.observeSnapshot(snapshot, discovery = null, manifest = first.takeSnapshotManifest(snapshot))
                storage.handler.await(inTransaction = true) {
                    driver.execute(null, "UPDATE sync_snapshot_manifests SET manifest_json = '{\"legacy\":true}'", 0)
                }

                val commitReadsBeforeRebuild = git.commitReads
                val restarted = git.transport().also {
                    it.installSnapshotManifestStore(SyncSnapshotManifestStore(storage.handler), binding)
                }
                val coldFallback = restarted.readSnapshot(repository, "space", 1).getOrThrow()

                assertTrue(restarted.takeWarmAdmission(coldFallback) == null)
                assertTrue(git.commitReads > commitReadsBeforeRebuild)
                assertFalse(
                    storage.handler.await {
                        sync_remote_guardQueries.getGuard("space", 1).executeAsOne().blocked
                    },
                )
                inbox.observeSnapshot(
                    coldFallback,
                    discovery = null,
                    manifest = restarted.takeSnapshotManifest(coldFallback),
                )
                assertEquals(
                    "",
                    storage.handler.await {
                        sync_remote_guardQueries.getSnapshotManifest("space", 1).executeAsOne().manifest_json
                    },
                )
            }
        }
    }

    private fun scalar(driver: SqlDriver, sql: String): Long = driver.executeQuery(
        identifier = null,
        sql = sql,
        parameters = 0,
        mapper = { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
        },
    ).value

    @Test
    fun `invalid discovered batch is removed exactly once`() = runBlocking {
        val baseDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val counting = CountingSqlDriver(baseDriver)
        val driver = counting.driver
        Database.Schema.create(driver)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database, driver)).use { storage ->
            val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
            storage.connect("receiver", repository)
            val snapshot = historicalSnapshot(repository, historySize = 1)
            val inbox = SyncInboxStore(storage.handler)
            inbox.observeSnapshot(snapshot)
            SyncDiscoveryStore(storage.handler).observe(snapshot)
            val entry = snapshot.batches.single()
            val invalid = SyncBatch(1, "space", 1, entry.batchId, emptyList())
            counting.resetDiscoveredBatchDeletes()

            val result = inbox.ingest(invalid, snapshot, entry)

            assertEquals("BATCH_TOO_LARGE: batch must contain 1..256 events", result.error)
            assertEquals(1, counting.discoveredBatchDeletes.get())
            assertTrue(SyncDiscoveryStore(storage.handler).pending("space", 1).isEmpty())
        }
    }

    @Test
    fun `receiving a valid discovered batch deletes its queue row once`() = runBlocking {
        val baseDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val counting = CountingSqlDriver(baseDriver)
        val driver = counting.driver
        Database.Schema.create(driver)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database, driver)).use { storage ->
            val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
            storage.connect("receiver", repository)
            SyncGitSafetyContractTest().GitFixture().use { git ->
                val transport = git.transport()
                transport.initialize(repository, "space", 1)
                val service = SyncBatchSyncService(transport, SyncSecret.fromBytes(ByteArray(32) { (it + 1).toByte() }))
                val before = transport.readSnapshot(repository, "space", 1).getOrThrow()
                val batch = validBatch()
                service.upload(
                    repository,
                    before,
                    batch,
                    ".mihon-sync/batches/sender/1/${batch.batchId}.json",
                    persist = {},
                )
                val published = transport.readSnapshot(repository, "space", 1).getOrThrow()
                val inbox = SyncInboxStore(storage.handler)
                counting.resetDiscoveredBatchDeletes()

                val result = SyncInboxExchange(inbox, service).receive(published, published.batches.single())

                assertTrue(result.accepted)
                assertEquals(1, counting.discoveredBatchDeletes.get())
                assertEquals(1, inbox.status("space", 1).receivedBatches)

                counting.resetDiscoveredBatchDeletes()
                val duplicate = SyncInboxExchange(inbox, service).receive(published, published.batches.single())

                assertTrue(duplicate.accepted && duplicate.duplicate)
                assertEquals(1, counting.discoveredBatchDeletes.get())
            }
        }
    }

    @Test
    fun `stable snapshot does not query receipt status once per historical batch`() = runBlocking {
        val baseDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val counting = CountingSqlDriver(baseDriver)
        val driver = counting.driver
        Database.Schema.create(driver)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database, driver)).use { storage ->
            val repository = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
            storage.connect("local", repository)
            val snapshot = historicalSnapshot(repository, historySize = 256)
            SyncInboxStore(storage.handler).observeSnapshot(snapshot)
            storage.handler.await(inTransaction = true) {
                snapshot.batches.forEach { entry ->
                    sync_inboxQueries.saveInboxBatch("space", 1, entry.batchId, "RECEIVED", "{}", null)
                }
            }

            val transport = FixedSnapshotTransport(snapshot)
            counting.resetReceivedBatchLookups()
            val result = storage.exchange(
                transport,
                SyncSecret.fromBytes(ByteArray(32) { it.toByte() }),
                repository,
            )

            assertEquals(mihon.domain.sync.runtime.SyncRunStatus.SUCCESS, result.status)
            assertEquals(0, counting.receivedBatchLookups.get())
        }
    }

    private fun historicalSnapshot(repository: SyncRepository, historySize: Int): SyncSnapshot {
        val batches = (0 until historySize).map { index ->
            SyncBatchIndexEntry(
                batchId = "history-$index",
                path = ".mihon-sync/batches/device/1/history-$index.json",
                digestHex = "a".repeat(64),
                firstSeq = index + 1L,
                lastSeq = index + 1L,
                actorId = "device",
                epoch = 1,
                indexPath = ".mihon-sync/index/device/1.bin",
                indexCiphertextDigestHex = "b".repeat(64),
            )
        }
        val entries = batches.mapIndexed { index, batch ->
            SyncGitTreeEntry(batch.path, "100644", "blob", index.toString(16).padStart(40, '0'))
        } + SyncGitTreeEntry(".mihon-sync/index/device/1.bin", "100644", "blob", "f".repeat(40))
        return SyncSnapshot(
            repository = repository,
            head = "e".repeat(40),
            tree = SyncGitTree("d".repeat(40), entries, truncated = false),
            spaceId = "space",
            generation = 1,
            batches = batches,
        )
    }

    private fun validBatch(): SyncBatch {
        val batchId = "sender-batch-1"
        return SyncBatch(
            protocolVersion = 1,
            spaceId = "space",
            generation = 1,
            batchId = batchId,
            events = listOf(
                SyncEventEnvelope(
                    protocolVersion = 1,
                    spaceId = "space",
                    generation = 1,
                    actorId = "sender",
                    epoch = 1,
                    seq = 1,
                    category = SyncCategory.FAVORITE,
                    effects = listOf(
                        SyncEffect(
                            effectId = "favorite",
                            objectKey = SyncObjectKey(SyncObjectType.MANGA, sourceId = "1", originalUrl = "/manga"),
                            field = SyncField.FAVORITE,
                            kind = SyncEffectKind.ADD,
                        ),
                    ),
                    origin = SyncOrigin.USER,
                    batchId = batchId,
                ),
            ),
        )
    }

    private class FixedSnapshotTransport(private val snapshot: SyncSnapshot) : SyncTransportPort {
        override fun prepare(
            snapshot: SyncSnapshot,
            encryptedBatch: mihon.domain.sync.crypto.SyncEncryptedBatch,
        ): SyncPreparedUpload = error("no local uploads expected")

        override suspend fun readSnapshot(
            repository: SyncRepository,
            expectedSpaceId: String,
            expectedGeneration: Long,
        ): Result<SyncSnapshot> = Result.success(snapshot)

        override suspend fun publish(
            repository: SyncRepository,
            snapshot: SyncSnapshot,
            upload: SyncPreparedUpload,
            observeSnapshot: suspend (SyncSnapshot) -> Unit,
        ): SyncPublishResult = error("no local uploads expected")

        override suspend fun initialize(
            repository: SyncRepository,
            spaceId: String,
            generation: Long,
        ): SyncInitializationResult = error("space is already connected")
    }

    private class CountingSqlDriver(delegate: SqlDriver) {
        val receivedBatchLookups = AtomicInteger()
        val discoveredBatchDeletes = AtomicInteger()
        val driver: SqlDriver = Proxy.newProxyInstance(
            SqlDriver::class.java.classLoader,
            arrayOf(SqlDriver::class.java),
        ) { _, method, arguments ->
            val sql = arguments?.getOrNull(1) as? String
            if (method.name == "executeQuery" &&
                sql?.contains("SELECT body_json FROM sync_inbox_batches") == true &&
                sql.contains("status = 'RECEIVED'")
            ) {
                receivedBatchLookups.incrementAndGet()
            }
            if (method.name == "execute" && sql?.contains("DELETE FROM sync_inbox_batches") == true &&
                sql.contains("status = 'DISCOVERED'")
            ) {
                discoveredBatchDeletes.incrementAndGet()
            }
            try {
                method.invoke(delegate, *(arguments ?: emptyArray()))
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        } as SqlDriver

        fun resetReceivedBatchLookups() {
            receivedBatchLookups.set(0)
        }

        fun resetDiscoveredBatchDeletes() {
            discoveredBatchDeletes.set(0)
        }
    }
}
