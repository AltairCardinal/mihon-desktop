package eu.kanade.tachiyomi.ui.browse.extension

import android.app.Application
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.DomainModule
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.extension.interactor.GetExtensionsByType
import eu.kanade.domain.extension.model.Extensions
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.test.ScreenModelTestHost
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import mihon.data.extension.ExtensionSuggestionSqlContract
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.suggestion.ExtensionInventory
import mihon.domain.extension.suggestion.ObserveExtensionSuggestions
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.source.SourceRepositoryImpl
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar

class AndroidExtensionSuggestionSqlTest : ExtensionSuggestionSqlContract() {
    @Test
    fun `production domain module shares application batch across screen owners`() = kotlinx.coroutines.test.runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val host = ScreenModelTestHost()
        try {
            val store = InMemoryPreferenceStore()
            Injekt.importModule(DomainModule())
            Injekt.addSingleton<PreferenceStore>(store)
            Injekt.addSingleton<Application>(mockk(relaxed = true))
            Injekt.addSingleton<SourceManager>(object : SourceManager {
                override val isInitialized = MutableStateFlow(true)
                override val catalogueSources = flowOf(emptyList<CatalogueSource>())
                override val querySources = flowOf(emptyList<eu.kanade.tachiyomi.source.Source>())
                override fun get(sourceKey: Long): eu.kanade.tachiyomi.source.Source? = null
                override fun getOrStub(sourceKey: Long): eu.kanade.tachiyomi.source.Source = error("No source fixture")
                override fun getOnlineSources() = emptyList<eu.kanade.tachiyomi.source.online.HttpSource>()
                override fun getCatalogueSources() = emptyList<CatalogueSource>()
                override fun getStubSources() = emptyList<tachiyomi.domain.source.model.StubSource>()
            })
            Injekt.addSingleton<tachiyomi.domain.source.repository.SourceRepository>(
                mockk {
                    every { getSourcesWithFavoriteCount() } returns flowOf(emptyList())
                },
            )
            Injekt.addSingleton<SourcePreferences>(
                mockk {
                    every { showNsfwSource() } returns store.getBoolean("show_nsfw", true)
                    every { extensionUpdatesCount() } returns store.getInt("extension_updates", 0)
                },
            )
            Injekt.addSingleton<BasePreferences>(
                mockk {
                    every { extensionInstaller() } returns mockk {
                        every { get() } returns BasePreferences.ExtensionInstaller.PRIVATE
                        every { changes() } returns flowOf(BasePreferences.ExtensionInstaller.PRIVATE)
                    }
                },
            )
            Injekt.addSingleton<GetExtensionsByType>(
                mockk {
                    every { subscribe() } returns flowOf(Extensions(emptyList(), emptyList(), emptyList(), emptyList()))
                },
            )
            Injekt.addSingleton<ExtensionManager>(
                mockk(relaxed = true) {
                    every { scope } returns backgroundScope
                    every { installArbiter } returns mihon.domain.extension.service.ExtensionInstallArbiter()
                    every { suggestionCatalog } returns MutableStateFlow<ExtensionCatalogResult?>(null)
                    every { inventory } returns MutableStateFlow(ExtensionInventory(initialized = true))
                    every { installErrors } returns MutableStateFlow(emptyMap())
                    every { originConfirmations } returns MutableStateFlow(emptyList())
                },
            )
            val batch = Injekt.get<eu.kanade.tachiyomi.extension.AndroidExtensionSuggestionBatch>()
            val first = host.create { Injekt.get<ExtensionsScreenModel>() }
            val second = host.create { Injekt.get<ExtensionsScreenModel>() }
            Assertions.assertSame(batch, first.suggestionBatch)
            Assertions.assertSame(batch, second.suggestionBatch)
            Assertions.assertSame(
                batch,
                Injekt.get<eu.kanade.tachiyomi.extension.AndroidExtensionSuggestionBatch>(),
            )
            val artifact = mihon.domain.extension.model.ExtensionArtifact(
                "Reader", "pkg.reader", "1.6.1", 1, "en", false, emptyList(),
                mihon.domain.extension.model.RepositoryIdentity("https://repo.example", "Repo", "key"),
                "https://repo.example/a.apk", "", null,
            )
            Assertions.assertTrue(batch.start(listOf(artifact)))
            runCurrent()
            Assertions.assertEquals(batch.state.value, first.state.value.suggestionBatch)
            host.close()
            ScreenModelTestHost().use { recreated ->
                val next = recreated.create { Injekt.get<ExtensionsScreenModel>() }
                runCurrent()
                Assertions.assertSame(batch, next.suggestionBatch)
                Assertions.assertEquals(batch.state.value, next.state.value.suggestionBatch)
            }
        } finally {
            host.close()
            Injekt = previous
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `production domain module resolves suggestion observer from real source repository binding`() {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        try {
            Injekt.addSingleton<DatabaseHandler>(mockk(relaxed = true))
            Injekt.addSingleton<SourceManager>(mockk(relaxed = true))
            Injekt.importModule(DomainModule())
            assertNotNull(Injekt.get<ObserveExtensionSuggestions>())
        } finally {
            Injekt = previous
        }
    }

    override fun open(
        driver: JdbcSqliteDriver,
        database: Database,
        manager: SourceManager,
        catalog: MutableStateFlow<ExtensionCatalogResult?>,
        inventory: MutableStateFlow<ExtensionInventory>,
    ): Session {
        val handler = AndroidDatabaseHandler(database, driver)
        val preferences = mockk<SourcePreferences> {
            every { extensionUpdatesCount() } returns mockk { every { changes() } returns flowOf(0) }
            every { showNsfwSource() } returns mockk { every { changes() } returns flowOf(true) }
        }
        val basePreferences = mockk<BasePreferences> {
            every { extensionInstaller() } returns mockk {
                every { get() } returns BasePreferences.ExtensionInstaller.PACKAGEINSTALLER
                every { changes() } returns flowOf(BasePreferences.ExtensionInstaller.PACKAGEINSTALLER)
            }
        }
        val getExtensions = mockk<GetExtensionsByType> {
            every { subscribe() } returns flowOf(Extensions(emptyList(), emptyList(), emptyList(), emptyList()))
        }
        val host = ScreenModelTestHost()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.importModule(DomainModule())
        Injekt.addSingleton<DatabaseHandler>(handler)
        Injekt.addSingleton<SourceManager>(manager)
        Injekt.addSingleton<PreferenceStore>(InMemoryPreferenceStore())
        Injekt.addSingleton(preferences)
        Injekt.addSingleton(basePreferences)
        Injekt.addSingleton<Application>(mockk(relaxed = true))
        Injekt.addSingleton(getExtensions)
        Injekt.addSingleton<ExtensionManager>(
            mockk(relaxed = true) {
                every { scope } returns appScope
                every { installArbiter } returns mihon.domain.extension.service.ExtensionInstallArbiter()
                every { suggestionCatalog } returns catalog
                every { this@mockk.inventory } returns inventory
            },
        )
        val model = try {
            host.create { Injekt.get<ExtensionsScreenModel>() }
        } catch (error: Throwable) {
            appScope.cancel()
            host.close()
            Injekt = previous
            throw error
        }
        return Session(
            handler,
            model.state.map { it.suggestions },
            model.state.map { it.suggestionPanel },
            model.suggestionPanel,
            {},
            {
                host.close()
                appScope.cancel()
                Injekt = previous
            },
        )
    }
}
