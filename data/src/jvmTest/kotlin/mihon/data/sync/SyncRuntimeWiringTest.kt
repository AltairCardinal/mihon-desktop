package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mihon.data.sync.http.InMemorySyncMetrics
import mihon.data.sync.http.NoopSyncMetrics
import mihon.data.sync.http.SyncMetrics
import mihon.data.sync.runtime.StoredSyncMaterial
import mihon.data.sync.runtime.StoredSyncSetup
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncRunState
import mihon.data.sync.runtime.SyncRuntime
import mihon.data.sync.transport.GitHubSyncTransport
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
import mihon.domain.sync.auth.GitHubAccessToken
import mihon.domain.sync.auth.GitHubAuthEndpoints
import mihon.domain.sync.crypto.SyncRecoveryCodec
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.runtime.SyncTrigger
import mihon.domain.sync.security.SyncSecureStore
import mihon.domain.sync.security.SyncSecureStoreException
import mihon.domain.sync.transport.SyncRepository
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences

class SyncRuntimeWiringTest {
    @Test
    fun `new exchange transport reads changed head through direct subtree objects`() = runBlocking {
        Fixture().use { fixture ->
            SyncOnboardingFixture(fixture.storage, fixture.preferences, fixture.client).use { setup ->
                val material = setup.existing("")
                setup.authorize()
                val runtime = setup.runtime()
                try {
                    val space = (runtime.onboarding.discover() as mihon.data.sync.auth.SyncSpaceDiscovery.Found).space
                    runtime.onboarding.resume(runtime.onboarding.join(space, material))
                    assertTrue(setup.git.recursiveTreeRequests >= 1)
                    assertEquals(SyncRunStatus.SUCCESS, runtime.coordinator.synchronize(SyncTrigger.MANUAL).status)
                    setup.git.replaceFile(setup.repository.branch, "unrelated-note.txt", "new head".encodeToByteArray())
                    val beforeRecursive = setup.git.recursiveTreeRequests
                    val beforeTrees = setup.git.treeRequests
                    assertEquals(SyncRunStatus.SUCCESS, runtime.coordinator.synchronize(SyncTrigger.MANUAL).status)
                    assertEquals(beforeRecursive, setup.git.recursiveTreeRequests)
                    assertTrue(setup.git.treeRequests - beforeTrees >= 1)
                } finally {
                    runtime.stopPanel()
                }
            }
        }
    }

    @Test
    fun `cold recursive truncation and oversized success fall back to bounded direct traversal`() = runBlocking {
        Fixture().use { fixture ->
            SyncOnboardingFixture(fixture.storage, fixture.preferences, fixture.client).use { setup ->
                val material = setup.existing("")
                val root = setup.git.treeSha(setup.repository.branch)
                fun transport() = GitHubSyncTransport(
                    fixture.client,
                    { "synthetic-token" },
                    setup.git.baseUrl,
                    spaceMaterial = material,
                ).also {
                    it.installSnapshotManifestStore(
                        SyncSnapshotManifestStore(fixture.storage.handler),
                        SyncSnapshotManifestBinding(1, 99, "cold-fallback-contract"),
                    )
                }
                setup.git.overrideNextTreeResponse(root, """{"sha":"$root","truncated":true,"tree":[]}""")
                val beforeTruncated = setup.git.treeRequests
                assertTrue(transport().readSnapshot(setup.repository, "space", 1).isSuccess)
                assertTrue(setup.git.treeRequests - beforeTruncated > 1)

                val delegate = setup.git.server.dispatcher
                val oversized = AtomicInteger()
                setup.git.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.url.encodedPath.contains("/git/trees/") &&
                            request.url.queryParameter("recursive") == "1"
                        ) {
                            oversized.incrementAndGet()
                            return MockResponse(body = "x".repeat(2 * 1024 * 1024 + 1))
                        }
                        return delegate.dispatch(request)
                    }
                }
                val beforeOversized = setup.git.treeRequests
                assertTrue(transport().readSnapshot(setup.repository, "space", 1).isSuccess)
                assertEquals(1, oversized.get())
                assertTrue(setup.git.treeRequests - beforeOversized > 0)
            }
        }
    }

    @Test
    fun `cold recursive rate limit and server failures never fall back to direct tree reads`() = runBlocking {
        Fixture().use { fixture ->
            SyncOnboardingFixture(fixture.storage, fixture.preferences, fixture.client).use { setup ->
                val material = setup.existing("")
                val delegate = setup.git.server.dispatcher
                val recursive = AtomicInteger()
                var failureCode = 429
                setup.git.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.url.encodedPath.contains("/git/trees/") &&
                            request.url.queryParameter("recursive") == "1"
                        ) {
                            recursive.incrementAndGet()
                            return MockResponse(code = failureCode, body = "failed")
                        }
                        return delegate.dispatch(request)
                    }
                }
                for (code in listOf(429, 500)) {
                    failureCode = code
                    val transport = GitHubSyncTransport(
                        fixture.client,
                        { "synthetic-token" },
                        setup.git.baseUrl,
                        spaceMaterial = material,
                    )
                    transport.installSnapshotManifestStore(
                        SyncSnapshotManifestStore(fixture.storage.handler),
                        SyncSnapshotManifestBinding(1, 99, "cold-failure-contract"),
                    )
                    val beforeDirect = setup.git.treeRequests
                    assertTrue(transport.readSnapshot(setup.repository, "space", 1).isFailure)
                    assertEquals(beforeDirect, setup.git.treeRequests)
                }
                assertEquals(2, recursive.get())
            }
        }
    }

    @Test
    fun `cold recursive tree rejects depth directory and UTF8 boundary violations`() = runBlocking {
        Fixture().use { fixture ->
            SyncOnboardingFixture(fixture.storage, fixture.preferences, fixture.client).use { setup ->
                val material = setup.existing("")
                val root = setup.git.treeSha(setup.repository.branch)
                val oid = "0".repeat(40)
                val empty = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
                fun tree(path: String, sha: String = empty) =
                    """{"path":"$path","mode":"040000","type":"tree","sha":"$sha"}"""
                val cases = listOf(
                    "tree depth" to "[${tree("d/".repeat(256) + "end")}]",
                    "directory exceeds limit" to "[${tree("a")},${tree("b")},${tree("c")}]",
                    "path is invalid" to "[${tree("\\uD800")}]",
                    "parent is missing" to "[${tree("a/b")}]",
                    "hash does not match" to "[${tree("a")},${tree("b")}]",
                    "hash does not match" to "[${tree("a", oid)}]",
                )
                for ((reason, entries) in cases) {
                    val transport = GitHubSyncTransport(
                        fixture.client,
                        { "synthetic-token" },
                        setup.git.baseUrl,
                        maxTreeEntries = 2,
                        spaceMaterial = material,
                    )
                    transport.installSnapshotManifestStore(
                        SyncSnapshotManifestStore(fixture.storage.handler),
                        SyncSnapshotManifestBinding(1, 99, "cold-parser-contract"),
                    )
                    setup.git.overrideNextTreeResponse(
                        root,
                        """{"sha":"$root","truncated":false,"tree":$entries}""",
                    )
                    val failure = transport.readSnapshot(setup.repository, "space", 1).exceptionOrNull()
                    assertTrue(failure?.message?.contains(reason) == true, "$reason: $failure")
                }
            }
        }
    }

    @Test
    fun `first production connection reads complete tree with one verified recursive request`() = runBlocking {
        Fixture().use { fixture ->
            SyncOnboardingFixture(fixture.storage, fixture.preferences, fixture.client).use { setup ->
                val material = setup.existing("")
                setup.authorize()
                val runtime = setup.runtime()
                try {
                    val space = (runtime.onboarding.discover() as mihon.data.sync.auth.SyncSpaceDiscovery.Found).space
                    val intent = runtime.onboarding.join(space, material)
                    val beforeTrees = setup.git.treeRequests
                    val beforeRecursive = setup.git.recursiveTreeRequests
                    runtime.onboarding.resume(intent)
                    assertEquals(1, setup.git.treeRequests - beforeTrees)
                    assertEquals(1, setup.git.recursiveTreeRequests - beforeRecursive)
                } finally {
                    runtime.stopPanel()
                }
            }
        }
    }

    @Test
    fun `fresh run skips totals reconciliation and recovery reconciles durable progress once`() = runBlocking {
        Fixture().use { f ->
            SyncOnboardingFixture(f.storage, f.preferences, f.client).use { setup ->
                setup.existing("")
                setup.authorize()
                setup.begin()

                val metrics = InMemorySyncMetrics()
                val runtime = setup.runtime(metrics)
                setup.git.nextReadFailure = MockResponse(code = 500, body = "temporary outage")

                val first = runtime.coordinator.synchronize(SyncTrigger.MANUAL)

                assertEquals(SyncRunStatus.FAILED, first.status)
                assertEquals(0, metrics.snapshot().totalsReconciliations)

                setup.now += 10_000
                val recovered = runtime.coordinator.synchronize(SyncTrigger.RECOVERY)

                assertEquals(SyncRunStatus.SUCCESS, recovered.status)
                assertEquals(1, metrics.snapshot().totalsReconciliations)
            }
        }
    }

    @Test
    fun `production cold snapshot rolls guard manifest and discovery back together`() = runBlocking {
        Fixture().use { f ->
            SyncOnboardingFixture(f.storage, f.preferences, f.client).use { setup ->
                val material = setup.existing("")
                val transport = GitHubSyncTransport(
                    f.client,
                    { "synthetic-token" },
                    setup.git.baseUrl,
                    spaceMaterial = material,
                )
                val service = SyncBatchSyncService(transport, spaceMaterial = material)
                val before = transport.readSnapshot(setup.repository, "space", 1).getOrThrow()
                val batch = remoteBatch()
                service.upload(
                    setup.repository,
                    before,
                    batch,
                    ".mihon-sync/batches/sender/1/${batch.batchId}.json",
                    persist = {},
                )
                setup.authorize()
                val storedSetup = StoredSyncSetup(
                    accountId = setup.accountId,
                    accountLogin = setup.accountLogin,
                    attemptId = "manifest-atomicity-check",
                    newSpace = false,
                    material = StoredSyncMaterial.from(material),
                    repositoryId = 99,
                    owner = setup.repository.owner,
                    repository = setup.repository.name,
                    branch = setup.repository.branch,
                )
                setup.runtime.bindSetup(storedSetup)
                f.storage.driver.execute(
                    null,
                    """
                    CREATE TRIGGER reject_snapshot_manifest BEFORE INSERT ON sync_snapshot_manifests
                    BEGIN SELECT RAISE(ABORT, 'injected manifest commit failure'); END
                    """.trimIndent(),
                    0,
                )

                val failed = setup.runtime.coordinator.synchronize(SyncTrigger.MANUAL)

                assertEquals(SyncRunStatus.FAILED, failed.status)
                assertNull(
                    f.storage.handler.await {
                        sync_remote_guardQueries.getGuard("space", 1).executeAsOneOrNull()
                    },
                )
                assertNull(
                    f.storage.handler.await {
                        sync_remote_guardQueries.getSnapshotManifest("space", 1).executeAsOneOrNull()
                    },
                )
                assertTrue(
                    f.storage.handler.await {
                        sync_inboxQueries.getDiscoveredBatches("space", 1, 128).executeAsList().isEmpty()
                    },
                )

                f.storage.driver.execute(null, "DROP TRIGGER reject_snapshot_manifest", 0)
                assertEquals(SyncRunStatus.SUCCESS, setup.runtime.coordinator.synchronize(SyncTrigger.MANUAL).status)
                assertNotNull(
                    f.storage.handler.await {
                        sync_remote_guardQueries.getSnapshotManifest("space", 1).executeAsOneOrNull()
                    },
                )
                assertEquals(
                    1L,
                    f.storage.handler.await {
                        sync_inboxQueries.countInboxBatches("space", 1)
                            .executeAsList().single { it.status == "RECEIVED" }.count
                    },
                )
            }
        }
    }

    @Test
    fun `production runtime rejects snapshot from a run whose owner expired during ref read`() = runBlocking {
        Fixture().use { f ->
            SyncOnboardingFixture(f.storage, f.preferences, f.client).use { setup ->
                setup.existing("")
                setup.authorize()
                setup.begin()
                val runtime = setup.runtime
                val connection = requireNotNull(runtime.connection())
                val guardBeforeRead = f.storage.handler.await {
                    sync_remote_guardQueries.getGuard(connection.spaceId, connection.generation).executeAsOneOrNull()
                }
                val manifestBeforeRead = f.storage.handler.await {
                    sync_remote_guardQueries.getSnapshotManifest(connection.spaceId, connection.generation)
                        .executeAsOneOrNull()
                }
                val discoveryBeforeRead = f.storage.handler.await {
                    sync_inboxQueries.getDiscoveredBatches(connection.spaceId, connection.generation, 128)
                        .executeAsList()
                }
                val run = runtime.runStore.start(connection.spaceId, connection.generation, SyncTrigger.MANUAL)
                val refReadStarted = CountDownLatch(1)
                val releaseRefRead = CountDownLatch(1)
                setup.git.nextRefReadBarrier = refReadStarted to releaseRefRead

                val expiredOwnerAttempt = async(Dispatchers.IO) {
                    runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                }
                try {
                    assertTrue(refReadStarted.await(5, TimeUnit.SECONDS), "snapshot ref read did not reach the barrier")
                    val ownerBeforeRecovery = requireNotNull(runtime.runStore.get(run.runId)?.ownerSession)
                    assertTrue(runtime.runStore.releaseForRecovery(run.runId))
                    releaseRefRead.countDown()

                    val staleResult = expiredOwnerAttempt.await()

                    assertEquals(SyncRunProblem.REMOTE_CHANGED, staleResult.problem)
                    val afterStaleRead = requireNotNull(runtime.runStore.get(run.runId))
                    assertEquals(SyncRunState.WAITING_SYSTEM, afterStaleRead.state)
                    assertEquals(null, afterStaleRead.ownerSession)
                    assertTrue(ownerBeforeRecovery.isNotBlank())
                    assertEquals(
                        guardBeforeRead,
                        f.storage.handler.await {
                            sync_remote_guardQueries.getGuard(
                                connection.spaceId,
                                connection.generation,
                            ).executeAsOneOrNull()
                        },
                    )
                    assertEquals(
                        manifestBeforeRead,
                        f.storage.handler.await {
                            sync_remote_guardQueries.getSnapshotManifest(connection.spaceId, connection.generation)
                                .executeAsOneOrNull()
                        },
                    )
                    assertEquals(
                        discoveryBeforeRead,
                        f.storage.handler.await {
                            sync_inboxQueries.getDiscoveredBatches(connection.spaceId, connection.generation, 128)
                                .executeAsList()
                        },
                    )

                    val recovered = setup.runtime()
                    try {
                        assertEquals(
                            SyncRunStatus.SUCCESS,
                            recovered.coordinator.synchronize(SyncTrigger.RECOVERY).status,
                        )
                        assertNotNull(
                            f.storage.handler.await {
                                sync_remote_guardQueries.getGuard(
                                    connection.spaceId,
                                    connection.generation,
                                ).executeAsOneOrNull()
                            },
                        )
                        assertNotNull(
                            f.storage.handler.await {
                                sync_remote_guardQueries.getSnapshotManifest(connection.spaceId, connection.generation)
                                    .executeAsOneOrNull()
                            },
                        )
                    } finally {
                        recovered.stopPanel()
                    }
                } finally {
                    releaseRefRead.countDown()
                    if (!expiredOwnerAttempt.isCompleted) expiredOwnerAttempt.cancel()
                }
            }
        }
    }

    @Test
    fun `production runtime reopens a validated snapshot and falls back after cache context damage`() = runBlocking {
        Fixture().use { f ->
            SyncOnboardingFixture(f.storage, f.preferences, f.client).use { setup ->
                setup.existing("")
                setup.authorize()
                setup.begin()
                val firstRuntime = setup.runtime

                assertEquals(SyncRunStatus.SUCCESS, firstRuntime.coordinator.synchronize(SyncTrigger.MANUAL).status)
                assertNotNull(
                    f.storage.handler.await {
                        sync_remote_guardQueries.getSnapshotManifest("space", 1).executeAsOneOrNull()
                    },
                )
                firstRuntime.stopPanel()
                val refsBeforeWarm = setup.git.snapshotReads
                val commitsBeforeWarm = setup.git.commitReads
                val treesBeforeWarm = setup.git.treeRequests
                val blobsBeforeWarm = setup.git.blobReads
                val reopened = setup.runtime()
                try {
                    assertEquals(SyncRunStatus.SUCCESS, reopened.coordinator.synchronize(SyncTrigger.MANUAL).status)
                    assertEquals(refsBeforeWarm + 1, setup.git.snapshotReads)
                    assertEquals(commitsBeforeWarm, setup.git.commitReads)
                    assertEquals(treesBeforeWarm, setup.git.treeRequests)
                    assertEquals(blobsBeforeWarm, setup.git.blobReads)

                    f.storage.driver.execute(
                        null,
                        "UPDATE sync_snapshot_manifests SET checksum = 'damaged' WHERE space_id = 'space' AND generation = 1",
                        0,
                    )
                    val commitsBeforeFallback = setup.git.commitReads
                    val treesBeforeFallback = setup.git.treeRequests
                    val fallback = setup.runtime()
                    try {
                        assertEquals(SyncRunStatus.SUCCESS, fallback.coordinator.synchronize(SyncTrigger.MANUAL).status)
                    } finally {
                        fallback.stopPanel()
                    }
                    assertTrue(setup.git.commitReads > commitsBeforeFallback)
                    assertTrue(setup.git.treeRequests > treesBeforeFallback)
                    assertFalse(
                        f.storage.handler.await {
                            sync_remote_guardQueries.getGuard("space", 1).executeAsOne().blocked
                        },
                    )

                    for ((column, value) in listOf(
                        "api_origin" to "'https://other.example'",
                        "validator_version" to "'other-validator'",
                        "validation_scope" to "'other-scope'",
                        "connection_revision" to "'other-connection'",
                        "account_id" to "2",
                        "repository_id" to "100",
                    )) {
                        f.storage.driver.execute(
                            null,
                            "UPDATE sync_snapshot_manifests SET $column = $value " +
                                "WHERE space_id = 'space' AND generation = 1",
                            0,
                        )
                        val treesBeforeContextMiss = setup.git.treeRequests
                        val isolated = setup.runtime()
                        try {
                            assertEquals(
                                SyncRunStatus.SUCCESS,
                                isolated.coordinator.synchronize(SyncTrigger.MANUAL).status,
                            )
                        } finally {
                            isolated.stopPanel()
                        }
                        assertTrue(setup.git.treeRequests > treesBeforeContextMiss, "context mismatch: $column")
                    }
                } finally {
                    reopened.stopPanel()
                }
            }
        }
    }

    @Test
    fun `temporary token refresh failures retain credentials and report retryable network failure`() = runBlocking {
        Fixture().use { f ->
            mockwebserver3.MockWebServer().use { auth ->
                auth.start()
                SyncOnboardingFixture(f.storage, f.preferences, f.client, auth.url("/token").toString()).use { setup ->
                    setup.existing("")
                    setup.authorize()
                    setup.begin()
                    val runtime = setup.runtime
                    val expiring = runtime.credentials.replace(
                        runtime.credentials.read()!!.revision,
                        GitHubAccessToken("access-secret", "refresh-secret", "bearer", emptySet(), 1000, 1_000_000),
                    )
                    for (code in listOf(500, 429)) {
                        auth.enqueue(mockwebserver3.MockResponse(code = code, body = "private diagnostic"))
                        val result = runtime.coordinator.synchronize(SyncTrigger.PERIODIC)
                        assertEquals(SyncRunProblem.NETWORK, result.problem)
                        assertEquals(expiring, runtime.credentials.read())
                        assertFalse(runtime.preferences.history.get().contains("private diagnostic"))
                        assertEquals(SyncRunState.WAITING_RETRY, runtime.runStore.active("space", 1)?.state)
                        assertEquals(
                            if (code == 500) 1L else 2L,
                            runtime.runStore.active("space", 1)?.networkFailureCount,
                        )
                        if (code == 500) {
                            assertEquals(
                                SyncRunStatus.SKIPPED,
                                runtime.coordinator.synchronize(SyncTrigger.PERIODIC).status,
                            )
                            assertEquals(1L, runtime.runStore.active("space", 1)?.networkFailureCount)
                        }
                        setup.now = if (code == 500) 11_000L else 41_000L
                    }
                    auth.enqueue(mockwebserver3.MockResponse(code = 500, body = "private diagnostic"))
                    assertEquals(SyncRunProblem.NETWORK, runtime.coordinator.synchronize(SyncTrigger.PERIODIC).problem)
                    assertEquals(3L, runtime.runStore.active("space", 1)?.networkFailureCount)
                    setup.now = 161_000L
                    auth.enqueue(mockwebserver3.MockResponse(code = 500, body = "private diagnostic"))
                    assertEquals(SyncRunProblem.NETWORK, runtime.coordinator.synchronize(SyncTrigger.PERIODIC).problem)
                    assertEquals(SyncRunState.FAILED, runtime.runStore.latest("space", 1)?.state)
                    assertEquals("retry_exhausted", runtime.runStore.latest("space", 1)?.stopReason)
                    assertEquals(4L, runtime.runStore.latest("space", 1)?.networkFailureCount)
                    assertEquals(null, runtime.runStore.active("space", 1))
                }
            }
        }
    }

    @Test
    fun `configured graph uses credentials production client database exchange and local history`() = runBlocking {
        Fixture().use { f ->
            SyncOnboardingFixture(f.storage, f.preferences, f.client).use { setup ->
                setup.existing("")
                setup.authorize("access-secret")
                setup.begin()
                val runtime = setup.runtime
                setup.now = 2_000L
                f.storage.favorite("/runtime")
                val result = runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                assertEquals(SyncRunStatus.SUCCESS, result.status)
                assertEquals(1, result.uploaded)
                val completedRun = requireNotNull(runtime.runStore.latest("space", 1))
                val completedLogs = runtime.runStore.logs(completedRun.runId)
                assertTrue(
                    completedLogs.any {
                        it.title == "/runtime" && it.detail.contains("收藏") && it.detail.contains("已确认上传")
                    },
                    completedLogs.toString(),
                )
                assertFalse(completedLogs.any { it.detail.contains("已接收") }, completedLogs.toString())
                assertTrue(f.networkCalls > 0)
                assertEquals(2_000L, runtime.preferences.lastSuccess.get())
                assertTrue(runtime.preferences.history.get().contains("SUCCESS"))
                assertTrue(f.preferences.getAll().keys.all { Preference.isAppState(it) })
                assertFalse(f.preferences.getAll().toString().contains("access-secret"))
                val reopened = setup.runtime()
                assertEquals("access-secret", reopened.accessToken())
                assertEquals(0, reopened.coordinator.synchronize(SyncTrigger.STARTUP).uploaded)
                f.storage.favorite("/runtime-next")
                assertEquals(1, reopened.coordinator.synchronize(SyncTrigger.MANUAL).uploaded)
                runtime.disconnect()
                f.storage.favorite("/after-disconnect")
                val before = f.networkCalls
                assertEquals(SyncRunStatus.SKIPPED, reopened.coordinator.synchronize(SyncTrigger.MANUAL).status)
                assertEquals(before, f.networkCalls)
                assertNull(runtime.credentials.read())
            }
        }
    }

    @Test
    fun `manual continuation reuses the resumed run instead of creating a second run`() = runBlocking {
        Fixture().use { f ->
            SyncOnboardingFixture(f.storage, f.preferences, f.client).use { setup ->
                setup.existing("")
                setup.authorize("access-secret")
                setup.begin()
                val runtime = setup.runtime
                val connection = requireNotNull(runtime.connection())
                val run = runtime.runStore.start(connection.spaceId, connection.generation, SyncTrigger.MANUAL)
                runtime.runStore.pause(run.runId)
                runtime.runStore.resumeIfAllowed(run.runId)

                assertEquals(SyncRunStatus.SUCCESS, runtime.coordinator.synchronize(SyncTrigger.MANUAL).status)
                assertEquals(SyncRunState.SUCCEEDED, runtime.runStore.get(run.runId)?.state)
            }
        }
    }

    @Test
    fun `system recovery reclaims an orphaned run while user pause stays paused`() = runBlocking {
        Fixture().use { f ->
            SyncOnboardingFixture(f.storage, f.preferences, f.client).use { setup ->
                setup.existing("")
                setup.authorize("access-secret")
                setup.begin()
                val runtime = setup.runtime
                val connection = requireNotNull(runtime.connection())
                val orphan = runtime.runStore.start(connection.spaceId, connection.generation, SyncTrigger.RECOVERY)
                assertTrue(runtime.runStore.claim(orphan.runId, "dead-process", 1))
                assertTrue(runtime.resumeIfNeeded())
                assertEquals(SyncRunState.SUCCEEDED, runtime.runStore.get(orphan.runId)?.state)

                val paused = runtime.runStore.start(connection.spaceId, connection.generation, SyncTrigger.MANUAL)
                runtime.runStore.pause(paused.runId)
                assertFalse(runtime.resumeIfNeeded())
                assertEquals(SyncRunState.PAUSED_USER, runtime.runStore.get(paused.runId)?.state)
            }
        }
    }

    @Test
    fun `system recovery reclaims an owner left in waiting retry`() = runBlocking {
        Fixture().use { f ->
            SyncOnboardingFixture(f.storage, f.preferences, f.client).use { setup ->
                setup.existing("")
                setup.authorize("access-secret")
                setup.begin()
                val runtime = setup.runtime
                val connection = requireNotNull(runtime.connection())
                val orphan = runtime.runStore.start(connection.spaceId, connection.generation, SyncTrigger.RECOVERY)
                assertTrue(runtime.runStore.claim(orphan.runId, "dead-process", 1))
                runtime.runStore.progress(
                    orphan.runId,
                    mihon.data.sync.runtime.SyncRunPhase.UPLOADING,
                    processed = 1,
                    total = 2,
                    state = SyncRunState.WAITING_RETRY,
                    ownerSession = "dead-process",
                )

                assertTrue(runtime.resumeIfNeeded())
                assertEquals(SyncRunState.SUCCEEDED, runtime.runStore.get(orphan.runId)?.state)
            }
        }
    }

    @Test
    fun `startup never replaces a user paused run`() = runBlocking {
        Fixture().use { f ->
            SyncOnboardingFixture(f.storage, f.preferences, f.client).use { setup ->
                setup.existing("")
                setup.authorize("access-secret")
                setup.begin()
                val runtime = setup.runtime
                val connection = requireNotNull(runtime.connection())
                val paused = runtime.runStore.start(connection.spaceId, connection.generation, SyncTrigger.MANUAL)
                runtime.runStore.pause(paused.runId)

                assertEquals(SyncRunStatus.SKIPPED, runtime.coordinator.synchronize(SyncTrigger.STARTUP).status)
                assertEquals(
                    SyncRunState.PAUSED_USER,
                    runtime.runStore.active(connection.spaceId, connection.generation)?.state,
                )
                assertEquals(paused.runId, runtime.runStore.latest(connection.spaceId, connection.generation)?.runId)
            }
        }
    }

    @Test
    fun `startup does not reset an exhausted automatic run`() = runBlocking {
        Fixture().use { f ->
            SyncOnboardingFixture(f.storage, f.preferences, f.client).use { setup ->
                setup.existing("")
                setup.authorize("access-secret")
                setup.begin()
                val runtime = setup.runtime
                val connection = requireNotNull(runtime.connection())
                val exhausted = runtime.runStore.start(connection.spaceId, connection.generation, SyncTrigger.RECOVERY)
                runtime.runStore.finish(exhausted.runId, SyncRunState.FAILED, "retry_exhausted")

                assertEquals(SyncRunStatus.SKIPPED, runtime.coordinator.synchronize(SyncTrigger.STARTUP).status)
                assertEquals(exhausted.runId, runtime.runStore.latest(connection.spaceId, connection.generation)?.runId)
            }
        }
    }

    @Test
    fun `reopened panel restores retry exhausted and blocked runs`() = runBlocking {
        Fixture().use { f ->
            SyncOnboardingFixture(f.storage, f.preferences, f.client).use { setup ->
                setup.existing("")
                setup.authorize("access-secret")
                setup.begin()
                val runtime = setup.runtime
                val connection = requireNotNull(runtime.connection())

                setup.panel.act(SyncPanelAction.Close)
                val exhausted = runtime.runStore.start(connection.spaceId, connection.generation, SyncTrigger.RECOVERY)
                runtime.runStore.finish(exhausted.runId, SyncRunState.FAILED, "retry_exhausted")
                setup.panel.act(SyncPanelAction.Open)
                assertEquals(SyncRunState.FAILED, setup.panel.state.value.run?.state)
                assertEquals(SyncRunProblem.NETWORK, setup.panel.state.value.problem)

                setup.panel.act(SyncPanelAction.Close)
                val blocked = runtime.runStore.start(connection.spaceId, connection.generation, SyncTrigger.MANUAL)
                runtime.runStore.finish(blocked.runId, SyncRunState.BLOCKED, "AUTHORIZATION")
                setup.panel.act(SyncPanelAction.Open)
                assertEquals(SyncRunState.BLOCKED, setup.panel.state.value.run?.state)
                assertEquals(SyncRunProblem.AUTHORIZATION, setup.panel.state.value.problem)
            }
        }
    }

    @Test
    fun `reopened panel keeps the latest successful run available for review`() = runBlocking {
        Fixture().use { f ->
            SyncOnboardingFixture(f.storage, f.preferences, f.client).use { setup ->
                setup.existing("")
                setup.authorize("access-secret")
                setup.begin()
                val runtime = setup.runtime

                setup.panel.act(SyncPanelAction.Close)
                assertEquals(SyncRunStatus.SUCCESS, runtime.coordinator.synchronize(SyncTrigger.MANUAL).status)
                setup.panel.act(SyncPanelAction.Open)

                assertEquals(SyncRunState.SUCCEEDED, setup.panel.state.value.run?.state)
            }
        }
    }

    @Test
    fun `manual retry supersedes blocked run so periodic sync can continue`() = runBlocking {
        Fixture().use { f ->
            SyncOnboardingFixture(f.storage, f.preferences, f.client).use { setup ->
                setup.existing("")
                setup.authorize("access-secret")
                setup.begin()
                val runtime = setup.runtime
                val connection = requireNotNull(runtime.connection())
                val blocked = runtime.runStore.start(connection.spaceId, connection.generation, SyncTrigger.MANUAL)
                runtime.runStore.finish(blocked.runId, SyncRunState.BLOCKED, "AUTHORIZATION")

                assertEquals(SyncRunStatus.SUCCESS, runtime.coordinator.synchronize(SyncTrigger.MANUAL).status)
                assertEquals(SyncRunState.CANCELLED, runtime.runStore.get(blocked.runId)?.state)
                assertEquals(SyncRunStatus.SUCCESS, runtime.coordinator.synchronize(SyncTrigger.PERIODIC).status)
            }
        }
    }

    @Test
    fun `missing authorization and unavailable secure storage are distinct safe failures`() = runBlocking {
        Fixture().use { f ->
            SyncOnboardingFixture(f.storage).use { setup ->
                setup.existing("")
                setup.authorize()
                setup.begin()
                val runtime = setup.runtime
                runtime.credentials.clear()
                assertEquals(SyncRunProblem.AUTHORIZATION, runtime.coordinator.synchronize(SyncTrigger.MANUAL).problem)
                assertEquals(SyncRunState.BLOCKED, runtime.runStore.active("space", 1)?.state)
                setup.secure.fail = true
                assertEquals(SyncRunProblem.STORAGE, runtime.coordinator.synchronize(SyncTrigger.MANUAL).problem)
            }
        }
    }

    @Test
    fun `unconfigured runtime does not access credentials or network and invalid periods are rejected`() = runBlocking {
        Fixture().use { f ->
            val runtime = f.runtime("https://example.invalid")
            f.secure.fail = true
            assertEquals(SyncRunStatus.SKIPPED, runtime.coordinator.synchronize(SyncTrigger.STARTUP).status)
            assertEquals(0, f.networkCalls)
            assertEquals(60, runtime.preferences.intervalMinutes())
            assertThrows(IllegalArgumentException::class.java) { runtime.preferences.setInterval(7) }
            runtime.preferences.setInterval(0)
            assertEquals(0, runtime.preferences.intervalMinutes())
        }
    }

    @Test
    fun `restoring a database without this devices secure binding renews actor before reconnecting`() = runBlocking {
        Fixture().use { f ->
            SyncOnboardingFixture(f.storage).use { setup ->
                setup.existing("")
                setup.authorize()
                f.storage.connect("old-device", setup.repository)
                setup.begin()
                val first = f.storage.handler.await { sync_journalQueries.getActiveActor().executeAsOne() }
                assertNotEquals("old-device", first.actor_id)
                assertEquals(2L, first.epoch)
                setup.begin()
                assertEquals(first, f.storage.handler.await { sync_journalQueries.getActiveActor().executeAsOne() })
            }
        }
    }

    private fun token() = GitHubAccessToken("access-secret", null, "bearer", emptySet(), null, null)

    private fun remoteBatch() = SyncBatch(
        protocolVersion = 1,
        spaceId = "space",
        generation = 1,
        batchId = "manifest-remote-batch",
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
                        objectKey = SyncObjectKey(SyncObjectType.MANGA, sourceId = "1", originalUrl = "/remote"),
                        field = SyncField.FAVORITE,
                        kind = SyncEffectKind.ADD,
                    ),
                ),
                origin = SyncOrigin.USER,
                batchId = "manifest-remote-batch",
            ),
        ),
    )

    private class MemorySecureStore : SyncSecureStore {
        val values = mutableMapOf<String, String>()
        var fail = false
        override suspend fun read(key: String): String? {
            if (fail) throw SyncSecureStoreException()
            return values[key]
        }
        override suspend fun compareAndSet(key: String, expected: String?, value: String?): Boolean {
            if (read(key) != expected) return false
            if (value == null) values.remove(key) else values[key] = value
            return true
        }
    }

    private class Fixture : AutoCloseable {
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        private val database = run {
            Database.Schema.create(driver)
            Database(
                driver,
                History.Adapter(DateColumnAdapter),
                Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            )
        }
        val storage = SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database, driver))
        val secure = MemorySecureStore()
        var now = 1_000L
        private val node = Preferences.userRoot().node("mihon-sync-runtime-test-" + UUID.randomUUID())
        val preferences = DesktopPreferenceStore(node)
        var networkCalls = 0
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) {
                networkCalls++
            }
        }).build()
        fun runtime(
            baseUrl: String,
            tokenUrl: String = "https://github.com/login/oauth/access_token",
            metrics: SyncMetrics = NoopSyncMetrics,
        ) = SyncRuntime(
            storage.handler, storage.bootstrap, storage.creators, storage.creators, { true }, secure,
            preferences, client, GitHubAuthEndpoints(accessTokenUrl = tokenUrl, apiBaseUrl = baseUrl), { now },
            syncMetrics = metrics,
        )
        override fun close() {
            storage.close()
            node.removeNode()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
