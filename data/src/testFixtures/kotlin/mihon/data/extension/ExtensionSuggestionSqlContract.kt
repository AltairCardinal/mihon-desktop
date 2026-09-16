package mihon.data.extension

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionCatalogEntry
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.model.ExtensionCompatibility
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.suggestion.ExtensionInventory
import mihon.domain.extension.suggestion.ExtensionPresence
import mihon.domain.extension.suggestion.ExtensionSuggestions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager

/** One behavior contract, executed through each platform's real SQL repository and screen model. */
abstract class ExtensionSuggestionSqlContract {
    protected abstract fun open(
        driver: JdbcSqliteDriver,
        database: Database,
        manager: SourceManager,
        catalog: MutableStateFlow<ExtensionCatalogResult?>,
        inventory: MutableStateFlow<ExtensionInventory>,
    ): Session

    protected class Session(
        val handler: DatabaseHandler,
        val state: Flow<ExtensionSuggestions>,
        val refresh: suspend () -> Unit,
        val close: suspend () -> Unit,
    )

    @Test
    fun `SQL favorites migration removal and uninstall reach both screens`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val jdbcDriver = Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as java.sql.Driver
        java.sql.DriverManager.registerDriver(jdbcDriver)
        try {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
                Database.Schema.create(driver)
                val database = Database(
                    driver,
                    historyAdapter = History.Adapter(DateColumnAdapter),
                    mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
                )
                val registered = MutableStateFlow(emptyList<eu.kanade.tachiyomi.source.Source>())
                val manager = object : SourceManager {
                    override val isInitialized = MutableStateFlow(true)
                    override val querySources = registered
                    override val catalogueSources = registered.map { sources ->
                        sources.filterIsInstance<CatalogueSource>()
                    }
                    override fun get(sourceKey: Long) = registered.value.find { it.id == sourceKey }
                    override fun getOrStub(sourceKey: Long) = get(sourceKey) ?: StubSource(sourceKey, "en", "Same name")
                    override fun getOnlineSources() = registered.value.filterIsInstance<HttpSource>()
                    override fun getCatalogueSources() = registered.value.filterIsInstance<CatalogueSource>()
                    override fun getStubSources() = emptyList<StubSource>()
                }
                val firstId = 9007199254740993L
                val secondId = firstId + 1
                val firstArtifact = artifact("pkg.first", firstId)
                val secondArtifact = artifact("pkg.second", secondId)
                val catalog = MutableStateFlow<ExtensionCatalogResult?>(
                    ExtensionCatalogResult(
                        listOf(firstArtifact, secondArtifact).map {
                            ExtensionCatalogEntry(it, ExtensionCompatibility.Compatible)
                        },
                        emptyList(),
                    ),
                )
                val inventory = MutableStateFlow(ExtensionInventory(true))
                val session = open(driver, database, manager, catalog, inventory)
                try {
                    session.refresh()
                    suspend fun awaitPackages(vararg packages: String): ExtensionSuggestions = withTimeout(10_000) {
                        session.state.first { state ->
                            !state.isLoading &&
                                state.suggestions.map { it.artifact.packageName }.toSet() == packages.toSet()
                        }
                    }
                    awaitPackages()
                    val mangas = MangaRepositoryImpl(session.handler, mockk(relaxed = true))
                    val manga = mangas.insertNetworkManga(
                        listOf(
                            Manga.create().copy(
                                source = firstId,
                                favorite = true,
                                title = "Book",
                                url = "/book",
                            ),
                        ),
                    ).single()
                    assertEquals(1L, awaitPackages("pkg.first").suggestions.single().mangaCount)
                    mangas.update(MangaUpdate(manga.id, source = secondId))
                    assertEquals(secondId, awaitPackages("pkg.second").suggestions.single().sources.single().source.id)
                    inventory.value = ExtensionInventory(true, mapOf("pkg.second" to ExtensionPresence.LOAD_FAILED))
                    awaitPackages()
                    inventory.value = ExtensionInventory(true)
                    awaitPackages("pkg.second")
                    registered.value = listOf(
                        mockk {
                            every { id } returns secondId
                            every { name } returns "Registered source"
                            every { lang } returns "en"
                        },
                    )
                    awaitPackages()
                    registered.value = emptyList()
                    awaitPackages("pkg.second")
                    mangas.update(MangaUpdate(manga.id, favorite = false))
                    awaitPackages()
                    assertEquals(secondId, mangas.getMangaById(manga.id).source)
                } finally {
                    session.close()
                }
            }
        } finally {
            java.sql.DriverManager.deregisterDriver(jdbcDriver)
            Dispatchers.resetMain()
        }
    }

    private fun artifact(packageName: String, id: Long) = ExtensionArtifact(
        "Same name", packageName, "1.6.1", 1, "en", false,
        listOf(ExtensionSourceDescriptor(id, "en", "Same name", "https://source.example")),
        RepositoryIdentity("https://repo.example", "Repo", "key"), "https://repo.example/$packageName.apk", "", null,
    )
}
