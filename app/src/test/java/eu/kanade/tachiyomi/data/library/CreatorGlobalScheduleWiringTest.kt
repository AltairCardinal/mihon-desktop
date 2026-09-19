package eu.kanade.tachiyomi.data.library

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.creator.model.ArchiveWatchPolicy
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.service.CreatorDiscoveryPreferences
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class CreatorGlobalScheduleWiringTest {
    @Test fun `real Android worker executes global calendar contract`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val app = RuntimeEnvironment.getApplication()
        val shared = app.getSharedPreferences("ga03", android.content.Context.MODE_PRIVATE)
        shared.edit().clear().commit()
        try {
            tachiyomi.data.creator.verifyCreatorGlobalSchedule(
                AndroidDatabaseHandler(database, driver),
                tachiyomi.core.common.preference.AndroidPreferenceStore(app, shared),
            ) { repository, service ->
                Injekt.addSingleton<CreatorArchiveRepository>(repository)
                Injekt.addSingleton(service)
                Injekt.addSingleton<SourceManager>(testSources(emptyList()))
                val worker = androidx.work.testing.TestListenableWorkerBuilder<CreatorDiscoveryJob>(app).build()
                val wake: suspend () -> Unit = {
                    worker.doWork()
                    Unit
                }
                wake
            }
        } finally {
            Injekt = previous
            driver.close()
            shared.edit().clear().commit()
        }
    }

    @Test fun `Android DI shares frequency in repository and executor`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val app = RuntimeEnvironment.getApplication()
        try {
            Injekt.importModule(eu.kanade.tachiyomi.di.PreferenceModule(app))
            Injekt.importModule(eu.kanade.domain.DomainModule())
            Injekt.addSingleton<DatabaseHandler>(AndroidDatabaseHandler(database, driver))
            val source = mockk<CatalogueSource> {
                every { id } returns 10L
                every { name } returns "Source"
                every { lang } returns "en"
                every { getFilterList() } returns FilterList()
                coEvery { getSearchManga(any(), any(), any()) } returns MangasPage(emptyList(), false)
            }
            Injekt.addSingleton<SourceManager>(testSources(listOf(source)))
            Injekt.addSingleton<ExtensionManager>(mockk(relaxed = true))
            val preferences = Injekt.get<CreatorDiscoveryPreferences>()
            preferences.frequency().set("weekly")
            tachiyomi.data.creator.verifyCreatorBackupReadiness(
                Injekt.get<DatabaseHandler>(),
                Injekt.get<tachiyomi.data.backup.AuthorArchiveBackupContributor>(),
            )
            val repository = Injekt.get<CreatorArchiveRepository>()
            val creator = Injekt.get<CreatorRepository>().upsertCreator("DI author")
            val now = System.currentTimeMillis()
            repository.upsertWatchPolicy(ArchiveWatchPolicy(creator.id, true, 60_000, setOf(10), emptySet()), now)
            val worker = androidx.work.testing.TestListenableWorkerBuilder<CreatorDiscoveryJob>(app).build()
            worker.doWork()
            val success = repository.getSourceCheckpoints(creator.id).single().lastSuccessAt!!
            check(repository.getDueWatchSources(success + 2 * 86_400_000L, 10).isEmpty())
            check(repository.getDueWatchSources(success + 8 * 86_400_000L, 10).size == 1)
        } finally {
            Injekt = previous
            driver.close()
        }
    }
}

private fun testSources(sources: List<CatalogueSource>) = object : SourceManager {
    override val isInitialized = kotlinx.coroutines.flow.MutableStateFlow(true)
    override val catalogueSources = kotlinx.coroutines.flow.flowOf(sources)
    override fun get(sourceKey: Long) = sources.firstOrNull { it.id == sourceKey }
    override fun getOrStub(sourceKey: Long) = requireNotNull(get(sourceKey))
    override fun getOnlineSources() = emptyList<HttpSource>()
    override fun getCatalogueSources() = sources
    override fun getStubSources() = emptyList<StubSource>()
}
