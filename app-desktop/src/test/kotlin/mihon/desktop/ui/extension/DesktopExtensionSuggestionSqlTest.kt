package mihon.desktop.ui.extension

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import mihon.data.extension.ExtensionSuggestionSqlContract
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.source.DesktopSourceRepository
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import mihon.domain.extension.suggestion.ExtensionInventory
import mihon.domain.extension.suggestion.ObserveExtensionSuggestions
import tachiyomi.data.Database
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.domain.source.service.SourceManager

class DesktopExtensionSuggestionSqlTest : ExtensionSuggestionSqlContract() {
    override fun open(
        driver: JdbcSqliteDriver,
        database: Database,
        manager: SourceManager,
        catalog: MutableStateFlow<ExtensionCatalogResult?>,
        inventory: MutableStateFlow<ExtensionInventory>,
    ): Session {
        val handler = JvmDatabaseHandler(database, driver)
        val observer = ObserveExtensionSuggestions(DesktopSourceRepository(manager, handler), manager)
        val api = mockk<DesktopExtensionApi> {
            coEvery { refreshCatalog() } answers { requireNotNull(catalog.value) }
            every { availableExtensions(any()) } returns emptyList()
        }
        val port = DesktopExtensionPresentationPort(api, mockk(), MutableStateFlow(emptyList()), inventory = inventory)
        val model = ExtensionsScreenModel(port, initialOptions = ExtensionPresentationOptions(true, emptySet()),
            suggestionObserver = observer)
        return Session(handler, model.state.map { it.suggestions }, { model.refresh().join() }, model::closeAndJoin)
    }
}
