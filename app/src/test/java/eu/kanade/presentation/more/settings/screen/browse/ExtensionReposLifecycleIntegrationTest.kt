package eu.kanade.presentation.more.settings.screen.browse

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.test.ScreenModelTestHost
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import mihon.data.repository.ExtensionRepoRepositoryImpl
import mihon.domain.extensionrepo.interactor.CreateExtensionRepo
import mihon.domain.extensionrepo.interactor.DeleteExtensionRepo
import mihon.domain.extensionrepo.interactor.GetExtensionRepo
import mihon.domain.extensionrepo.interactor.ReplaceExtensionRepo
import mihon.domain.extensionrepo.interactor.UpdateExtensionRepo
import mihon.domain.extensionrepo.service.ExtensionRepoAction
import mihon.domain.extensionrepo.service.ExtensionRepoActionResult
import mihon.domain.extensionrepo.service.ExtensionRepoService
import mihon.domain.platform.ExternalAction
import mihon.domain.platform.ExternalActionInput
import mihon.domain.platform.ExternalActionParser
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.i18n.MR
import java.util.concurrent.TimeUnit

class ExtensionReposLifecycleIntegrationTest {
    private val modelHost = ScreenModelTestHost()

    @Test
    fun `both deeplinks drive real screen model discovery confirmation replacement and deletion`() = runBlocking {
        val jdbcDriver = (Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as java.sql.Driver)
            .also(java.sql.DriverManager::registerDriver)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            MockWebServer().also { it.start() }.use { server ->
                JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
                    Database.Schema.create(driver)
                    val database = Database(
                        driver,
                        historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
                        mangasAdapter = Mangas.Adapter(
                            genreAdapter = StringListColumnAdapter,
                            update_strategyAdapter = UpdateStrategyColumnAdapter,
                        ),
                    )
                    val repository = ExtensionRepoRepositoryImpl(AndroidDatabaseHandler(database, driver))
                    val network = mockk<NetworkHelper> { every { client } returns OkHttpClient() }
                    val service = ExtensionRepoService(network, Json { ignoreUnknownKeys = true })
                    val model = modelHost.create {
                        ExtensionReposScreenModel(
                            GetExtensionRepo(repository),
                            CreateExtensionRepo(repository, service),
                            DeleteExtensionRepo(repository),
                            ReplaceExtensionRepo(repository),
                            UpdateExtensionRepo(repository, service),
                            mockk<ExtensionManager>(relaxed = true),
                            service,
                        )
                    }
                    try {
                        withTimeout(5_000) { model.state.first { it is RepoScreenState.Success } }
                        val root = server.url("/").toString().removeSuffix("/")
                        val index = server.url("/catalog/index.json").toString()
                        server.enqueue(
                            MockResponse(
                                body = """{"meta":{"name":"Store",
                            "website":"https://store.example",
                            "signingKeyFingerprint":"trusted"},
                            "index_v2":"$index"}""",
                            ),
                        )
                        val oldLink = addRepository("tachiyomi://add-repo?url=$root/index.min.json")
                        assertSuccess(events(model) { createRepo(oldLink.url) }, ExtensionRepoAction.CREATE)
                        assertEquals("/repo.json", server.takeRequest(5, TimeUnit.SECONDS)?.url?.encodedPath)
                        val old = requireNotNull(repository.getRepo(root))
                        withTimeout(5_000) { model.state.first { it is RepoScreenState.Success && old in it.repos } }

                        server.enqueue(
                            MockResponse(
                                body = """{"name":"Store",
                            "badgeLabel":"New",
                            "signingKey":"trusted",
                            "contact":{"website":"https://store.example"},
                            "extensionList":{"extensions":[]}}""",
                            ),
                        )
                        val newLink = addRepository("mihon://extension-store?url=$index")
                        val conflictEvents = events(model) { createRepo(newLink.url) }
                        assertEquals(MR.strings.ext_pending, conflictEvents.first().stringRes)
                        assertEquals(MR.strings.action_replace_repo_title, conflictEvents.last().stringRes)
                        val conflict = assertInstanceOf(
                            ExtensionRepoActionResult.FingerprintConflict::class.java,
                            conflictEvents.last().result,
                        )
                        assertEquals("/catalog/index.json", server.takeRequest(5, TimeUnit.SECONDS)?.url?.encodedPath)
                        assertEquals(listOf(old), repository.getAll())
                        assertEquals(
                            RepoDialog.Conflict(old, conflict.newRepo),
                            (model.state.value as RepoScreenState.Success).dialog,
                        )
                        model.dismissDialog()
                        assertEquals(listOf(old), repository.getAll())
                        model.showDialog(RepoDialog.Conflict(old, conflict.newRepo))
                        assertSuccess(events(model) { replaceRepo(old, conflict.newRepo) }, ExtensionRepoAction.REPLACE)
                        assertNull(repository.getRepo(root))
                        assertEquals(conflict.newRepo, repository.getRepo(index))
                        withTimeout(5_000) {
                            model.state.first {
                                it is RepoScreenState.Success &&
                                    it.repos.singleOrNull() == conflict.newRepo
                            }
                        }

                        val invalid = events(model) { createRepo("https://user:password@invalid.example") }
                        assertEquals(MR.strings.invalid_repo_name, invalid.last().stringRes)
                        assertEquals(listOf(conflict.newRepo), repository.getAll())
                        assertEquals(2, server.requestCount)
                        model.showDialog(RepoDialog.Delete(index))
                        model.dismissDialog()
                        assertEquals(listOf(conflict.newRepo), repository.getAll())
                        assertSuccess(events(model) { deleteRepo(index) }, ExtensionRepoAction.DELETE)
                        withTimeout(5_000) { model.state.first { it is RepoScreenState.Success && it.isEmpty } }
                        assertEquals(0, repository.getAll().size)
                    } finally {
                        modelHost.close()
                    }
                }
            }
        } finally {
            modelHost.close()
            Dispatchers.resetMain()
            java.sql.DriverManager.deregisterDriver(jdbcDriver)
        }
    }

    private fun addRepository(uri: String) = assertInstanceOf(
        ExternalAction.AddRepository::class.java,
        ExternalActionParser.resolve(ExternalActionInput.ViewUri(uri)),
    )

    private suspend fun events(
        model: ExtensionReposScreenModel,
        action: ExtensionReposScreenModel.() -> Unit,
    ): List<RepoEvent.ActionResult> {
        model.action()
        return withTimeout(5_000) { List(2) { model.events.first() as RepoEvent.ActionResult } }
    }

    private fun assertSuccess(events: List<RepoEvent.ActionResult>, action: ExtensionRepoAction) {
        assertEquals(
            listOf(ExtensionRepoActionResult.Pending(action), ExtensionRepoActionResult.Success(action)),
            events.map { it.result },
        )
        assertEquals(listOf(MR.strings.ext_pending, MR.strings.completed), events.map { it.stringRes })
    }
}
