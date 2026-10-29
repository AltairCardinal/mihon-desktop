package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import mihon.data.sync.runtime.SyncPanel
import mihon.data.sync.runtime.SyncRuntime
import mihon.domain.sync.crypto.SyncSpaceDescriptor
import mihon.domain.sync.crypto.SyncSpaceDescriptorCodec
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter

/** Narrow test-only bridge to the existing real onboarding server/storage fixtures. */
class SyncPanelUiFixture : AutoCloseable {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = run {
        Database.Schema.create(driver)
        Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
    }
    private val storage = SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database, driver))
    private val fixture = SyncOnboardingFixture(storage)
    private var reopenedPanel: mihon.data.sync.runtime.SyncPanelController? = null
    private var connectionCheckRelease: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val panel: SyncPanel get() = reopenedPanel ?: fixture.panel
    val runtime: SyncRuntime get() = fixture.runtime
    val writes: Int get() = fixture.repositoryWrites
    val bootstrapWrites: Int get() = fixture.git.contentsPutBodies.size
    val requestCount: Int get() = fixture.git.server.requestCount

    suspend fun authorize() = fixture.authorize()
    suspend fun existing(password: String) {
        fixture.existing(password)
    }
    suspend fun favorite() {
        storage.favorite("/ui-fixture-book")
    }

    suspend fun connectExistingPlain() {
        fixture.existing("")
        fixture.authorize()
        fixture.begin()
        fixture.panel.awaitIdle()
    }

    suspend fun reopenWhileCheckingConnection() {
        fixture.panel.stop()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        connectionCheckRelease = release
        fixture.secure.nextSpaceRead = entered to release
        reopenedPanel = mihon.data.sync.runtime.SyncPanelController(runtime, storage.handler)
        kotlinx.coroutines.withTimeout(5_000) { entered.await() }
    }

    suspend fun pendingCancellation(index: Int) {
        val key = mihon.domain.sync.SyncObjectKey(
            mihon.domain.sync.SyncObjectType.MANGA,
            sourceId = "1",
            originalUrl = "/pending-$index",
        )
        val add = mihon.domain.sync.SyncEventEnvelope(
            1, "space", 1, "remote-$index", 1, 1, mihon.domain.sync.SyncCategory.FAVORITE,
            listOf(
                mihon.domain.sync.SyncEffect(
                    "membership",
                    key,
                    mihon.domain.sync.SyncField.FAVORITE,
                    mihon.domain.sync.SyncEffectKind.ADD,
                ),
            ),
            mihon.domain.sync.SyncOrigin.USER,
            batchId = "add-$index",
        )
        val remove = add.copy(
            seq = 2,
            batchId = "remove-$index",
            effects = listOf(
                add.effects.single().copy(
                    kind = mihon.domain.sync.SyncEffectKind.REMOVE,
                    parents = listOf(add.ref("membership")),
                ),
            ),
        )
        val inbox = mihon.data.sync.inbox.SyncInboxStore(storage.handler)
        for (event in listOf(add, remove)) {
            check(
                inbox.ingest(
                    mihon.domain.sync.SyncBatch(
                        1,
                        "space",
                        1,
                        requireNotNull(event.batchId),
                        listOf(event),
                        listOf(mihon.domain.sync.SyncObjectDescriptor(key, "Pending title")),
                    ),
                ).accepted,
            )
            while (runtime.projector.project("space", 1) == 50) Unit
        }
        fixture.panel.dispatch(mihon.data.sync.runtime.SyncPanelAction.Open)
        fixture.panel.awaitIdle()
    }
    fun descriptor(): SyncSpaceDescriptor = SyncSpaceDescriptorCodec.decode(
        requireNotNull(fixture.git.file(fixture.repository.branch, SyncSpaceDescriptorCodec.PATH)),
    ).getOrThrow()

    override fun close() {
        connectionCheckRelease?.complete(Unit)
        kotlinx.coroutines.runBlocking { reopenedPanel?.stop() }
        fixture.close()
        storage.close()
    }
}
