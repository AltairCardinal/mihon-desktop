package eu.kanade.tachiyomi.ui.browse.extension

import android.app.Application
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.DomainModule
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.extension.interactor.GetExtensionsByType
import eu.kanade.domain.extension.model.Extensions
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.test.ScreenModelTestHost
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import mihon.data.extension.ExtensionSuggestionSqlContract
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.suggestion.ExtensionInventory
import mihon.domain.extension.suggestion.ObserveExtensionSuggestions
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
                every { changes() } returns flowOf(BasePreferences.ExtensionInstaller.PACKAGEINSTALLER)
            }
        }
        val getExtensions = mockk<GetExtensionsByType> {
            every { subscribe() } returns flowOf(Extensions(emptyList(), emptyList(), emptyList(), emptyList()))
        }
        val host = ScreenModelTestHost()
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
                every { suggestionCatalog } returns catalog
                every { this@mockk.inventory } returns inventory
            },
        )
        val model = try {
            host.create { Injekt.get<ExtensionsScreenModel>() }
        } catch (error: Throwable) {
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
                Injekt = previous
            },
        )
    }
}
