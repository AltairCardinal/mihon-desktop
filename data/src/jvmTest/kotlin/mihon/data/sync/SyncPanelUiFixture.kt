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
    val panel: SyncPanel get() = fixture.panel
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
    fun descriptor(): SyncSpaceDescriptor = SyncSpaceDescriptorCodec.decode(
        requireNotNull(fixture.git.file(fixture.repository.branch, SyncSpaceDescriptorCodec.PATH)),
    ).getOrThrow()

    override fun close() {
        fixture.close()
        storage.close()
    }
}
