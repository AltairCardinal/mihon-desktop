package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.transport.SyncPublishStatus
import mihon.domain.sync.transport.SyncRepository
import mihon.domain.sync.transport.SyncSnapshot
import mihon.domain.sync.transport.SyncTransportPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.JvmDatabaseHandler
import java.nio.file.Files
import java.nio.file.Path

class JvmSyncRuntimeStorageContractTest : SyncRuntimeStorageContract() {
    override fun open(): Storage {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return Storage(driver, JvmDatabaseHandler(database(driver), driver))
    }

    @Test
    fun `catch-up discovery remains durable when the file database is reopened`() = runBlocking {
        val databasePath = Files.createTempFile("mihon-sync-catch-up-", ".db").toString()
        SyncGitSafetyContractTest().GitFixture().use { git ->
            val transport = git.transport()
            transport.initialize(repository, "space", 1)
            var storage = open(databasePath, create = true)
            try {
                storage.connect("receiver", repository)
                storage.favorite("/file-local-trigger")
                var observedReads = 0
                val delayedRemoteTransport = object : SyncTransportPort by transport {
                    override suspend fun readCurrentHead(
                        repository: SyncRepository,
                        expectedSpaceId: String,
                        expectedGeneration: Long,
                    ): Result<String> {
                        observedReads++
                        if (observedReads in 1..4) {
                            val index = observedReads
                            val batch = remoteFavoriteBatch(
                                "file-late-batch-$index",
                                "file-late-device-$index",
                                "/file-late-$index",
                            )
                            assertEquals(SyncPublishStatus.PUBLISHED, publishRemoteBatch(transport, batch).status)
                        }
                        return transport.readCurrentHead(repository, expectedSpaceId, expectedGeneration)
                    }
                }

                val bounded = storage.exchange(delayedRemoteTransport, secret, repository)
                assertEquals(SyncRunStatus.PARTIAL, bounded.status)
                assertEquals(1, bounded.uploaded)
                assertEquals(3, bounded.downloaded)
                assertTrue(
                    storage.handler.await {
                        sync_inboxQueries.getDiscoveredBatches("space", 1, 128).executeAsList()
                            .single().contains("file-late-batch-4")
                    },
                )

                storage.close()
                storage = open(databasePath, create = false)
                val resumed = storage.exchange(delayedRemoteTransport, secret, repository)
                assertEquals(SyncRunStatus.SUCCESS, resumed.status)
                assertEquals(1, resumed.downloaded)
                assertTrue(
                    storage.handler.await {
                        sync_inboxQueries.getDiscoveredBatches("space", 1, 128).executeAsList().isEmpty()
                    },
                )
            } finally {
                storage.close()
                Files.deleteIfExists(Path.of(databasePath))
            }
        }
    }

    private fun open(path: String, create: Boolean): Storage {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        return Storage(driver, JvmDatabaseHandler(database(driver, create), driver))
    }
}
