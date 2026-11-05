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
import kotlinx.coroutines.flow.MutableStateFlow
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
import tachiyomi.domain.creator.service.CreatorDiscoveryService
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
    @Test fun `Android Creator DI metadata uses registered identity and keeps healthy discovery independent`() =
        runBlocking<Unit> {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver)
            val database = Database(
                driver,
                historyAdapter = History.Adapter(DateColumnAdapter),
                mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            )
            val previous = Injekt
            val app = RuntimeEnvironment.getApplication()
            val observations = mutableListOf<String>()
            try {
                for (site in listOf("id", "name", "language")) {
                    Injekt = InjektScope(DefaultRegistrar())
                    Injekt.importModule(eu.kanade.tachiyomi.di.PreferenceModule(app))
                    Injekt.importModule(eu.kanade.domain.DomainModule())
                    Injekt.addSingleton<DatabaseHandler>(AndroidDatabaseHandler(database, driver))
                    val broken = CreatorMetadataSource(10L).apply { brokenAt = site }
                    val healthy = CreatorMetadataSource(20L)
                    Injekt.addSingleton<SourceManager>(registeredCreatorSources(listOf(10L to broken, 20L to healthy)))
                    val installed = eu.kanade.tachiyomi.extension.model.Extension.Installed(
                        name = "Creator metadata fixture", pkgName = "trusted.creator", versionName = "1.0",
                        versionCode = 1L, libVersion = 1.4, lang = "en", isNsfw = false, pkgFactory = null,
                        sources = listOf(broken, healthy), icon = null, isShared = false,
                    )
                    Injekt.addSingleton<ExtensionManager>(
                        mockk(relaxed = true) {
                            every { installedExtensionsFlow } returns MutableStateFlow(listOf(installed))
                            every { getExtensionPackage(any()) } returns "trusted.creator"
                            every { getExtensionVersion(any()) } returns "1.0+1"
                        },
                    )
                    val creator = Injekt.get<CreatorRepository>().upsertCreator("Metadata $site")
                    val repository = Injekt.get<CreatorArchiveRepository>()
                    repository.upsertWatchPolicy(
                        ArchiveWatchPolicy(
                            creator.id,
                            true,
                            60_000L,
                            setOf(10L, 20L),
                            emptySet(),
                            includeUnknown = true,
                        ),
                        System.currentTimeMillis(),
                    )
                    val result = runCatching {
                        Injekt.get<CreatorDiscoveryService>().discoverCreator(creator.id)
                    }
                    val failure = result.exceptionOrNull()
                    if (failure != null) {
                        observations += "$site:escaped:${failure.javaClass.simpleName}"
                        continue
                    }
                    val byId = result.getOrThrow().sourceResults.associateBy { it.sourceId }
                    check(byId.keys == setOf(10L, 20L))
                    val kind = byId.getValue(10L).failure?.javaClass?.simpleName ?: "Success"
                    observations += "$site:$kind:${byId.getValue(20L).failure}:${broken.idReads}:${healthy.idReads}"
                    check(broken.nameReads == 1 && broken.languageReads == 1)
                    check(healthy.searches == 1)
                    check(broken.searches == if (site == "id") 1 else 0)
                    val healthyCheckpoint = repository.getSourceCheckpoints(creator.id).single { it.sourceId == 20L }
                    check(healthyCheckpoint.lastSuccessAt != null)
                }
                org.junit.Assert.assertEquals(
                    listOf(
                        "id:Success:null:0:0",
                        "name:ExtensionIncompatible:null:0:0",
                        "language:ExtensionIncompatible:null:0:0",
                    ),
                    observations,
                )
            } finally {
                Injekt = previous
                driver.close()
            }
        }

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
            Injekt.addSingleton<ExtensionManager>(
                mockk(relaxed = true) {
                    every { installedExtensionsFlow } returns MutableStateFlow(emptyList())
                },
            )
            val preferences = Injekt.get<CreatorDiscoveryPreferences>()
            preferences.titleAttributionSources().set(setOf("10"))
            val titleEditor = Injekt.get<tachiyomi.domain.creator.service.CreatorTitleAttributionEditor>()
            check(titleEditor.policy === Injekt.get<tachiyomi.domain.creator.service.CreatorTitleAttributionPolicy>())
            val titleManga = tachiyomi.domain.manga.model.Manga.create().copy(
                source = 10L,
                title = "[Circle (Artist)] Work",
                author = "Uploader",
                artist = "Uploader",
            )
            check(
                Injekt.get<tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga>().await(titleManga).map {
                    it.displayName
                } ==
                    listOf("Artist", "Circle"),
            )
            check(titleEditor.extract(titleManga).map { it.displayName } == listOf("Artist", "Circle"))
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

private fun testSources(sources: List<CatalogueSource>) =
    registeredCreatorSources(sources.map { it.id to it })

private class CreatorMetadataSource(private val registeredId: Long) : CatalogueSource {
    var brokenAt: String? = null
    var idReads = 0
    var nameReads = 0
    var languageReads = 0
    var searches = 0
    override val id: Long get() {
        idReads++
        if (brokenAt == "id") throw NoSuchMethodError("future ID")
        return registeredId
    }
    override val name: String get() {
        nameReads++
        if (brokenAt == "name") throw NoSuchMethodError("future name")
        return "Creator metadata source"
    }
    override val lang: String get() {
        languageReads++
        if (brokenAt == "language") throw NoSuchMethodError("future language")
        return "en"
    }
    override val supportsLatest = false
    override fun getFilterList() = FilterList()
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        searches++
        return MangasPage(emptyList(), false)
    }
}

private fun registeredCreatorSources(registrations: List<Pair<Long, CatalogueSource>>) = object : SourceManager {
    override val isInitialized = MutableStateFlow(true)
    override val catalogueSources = kotlinx.coroutines.flow.flowOf(registrations.map { it.second })
    override fun getRegisteredSourceId(source: eu.kanade.tachiyomi.source.Source) =
        registrations.firstOrNull { it.second === source }?.first
    override fun get(sourceKey: Long) = registrations.firstOrNull { it.first == sourceKey }?.second
    override fun getOrStub(sourceKey: Long) = requireNotNull(get(sourceKey))
    override fun getOnlineSources() = emptyList<HttpSource>()
    override fun getCatalogueSources() = registrations.map { it.second }
    override fun getStubSources() = emptyList<StubSource>()
}
