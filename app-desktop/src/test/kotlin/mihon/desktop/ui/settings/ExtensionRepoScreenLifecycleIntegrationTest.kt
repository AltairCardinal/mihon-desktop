package mihon.desktop.ui.settings

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mihon.data.repository.ExtensionRepoRepositoryImpl
import mihon.domain.extensionrepo.interactor.CreateExtensionRepo
import mihon.domain.extensionrepo.interactor.DeleteExtensionRepo
import mihon.domain.extensionrepo.interactor.ReplaceExtensionRepo
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
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.util.concurrent.TimeUnit

class ExtensionRepoScreenLifecycleIntegrationTest {
    @Test
    fun `deeplink prompts use production screen actions HTTP persistence and feedback`() = runBlocking {
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
                val repository = ExtensionRepoRepositoryImpl(JvmDatabaseHandler(database, driver))
                val network = mockk<NetworkHelper> { every { client } returns OkHttpClient() }
                val service = ExtensionRepoService(network, Json { ignoreUnknownKeys = true })
                val root = server.url("/").toString().removeSuffix("/")
                val index = server.url("/catalog/index.json").toString()
                val oldScreen = screenFor("tachiyomi://add-repo?url=$root/index.min.json")
                val actions = oldScreen.createActions(
                    CreateExtensionRepo(repository, service),
                    ReplaceExtensionRepo(repository),
                    DeleteExtensionRepo(repository),
                )
                val events = mutableListOf<ExtensionRepoActionResult>()
                server.enqueue(MockResponse(body = """{"meta":{"name":"Store",
                    "website":"https://store.example",
                    "signingKeyFingerprint":"trusted"},
                    "index_v2":"$index"}"""))
                actions.create(requireNotNull(oldScreen.initialCreatePrompt()).initialUrl, events::add)
                assertSuccess(events, ExtensionRepoAction.CREATE)
                assertEquals("/repo.json", server.takeRequest(5, TimeUnit.SECONDS)?.url?.encodedPath)
                val old = requireNotNull(repository.getRepo(root))
                assertEquals(listOf(old), repository.subscribeAll().first())

                val newScreen = screenFor("mihon://extension-store?url=$index")
                server.enqueue(MockResponse(body = """{"name":"Store",
                    "badgeLabel":"New",
                    "signingKey":"trusted",
                    "contact":{"website":"https://store.example"},
                    "extensionList":{"extensions":[]}}"""))
                events.clear()
                val conflict = assertInstanceOf(
                    ExtensionRepoActionResult.FingerprintConflict::class.java,
                    actions.create(requireNotNull(newScreen.initialCreatePrompt()).initialUrl, events::add),
                )
                assertEquals(ExtensionRepoActionResult.Pending(ExtensionRepoAction.CREATE), events.first())
                assertEquals("Signing Key Fingerprint Already Exists", extensionRepoActionMessage(events.last()))
                assertEquals("/catalog/index.json", server.takeRequest(5, TimeUnit.SECONDS)?.url?.encodedPath)
                assertEquals(listOf(old), repository.getAll())
                val confirmation = RepoDialog.Conflict(conflict.oldRepo, conflict.newRepo)
                events.clear()
                actions.replace(confirmation.oldRepo, confirmation.newRepo, events::add)
                assertSuccess(events, ExtensionRepoAction.REPLACE)
                assertNull(repository.getRepo(root))
                assertEquals(listOf(conflict.newRepo), repository.subscribeAll().first())

                events.clear()
                actions.create("https://user:password@invalid.example", events::add)
                assertEquals("Repository URL must be HTTPS.", extensionRepoActionMessage(events.last()))
                assertEquals(listOf(conflict.newRepo), repository.getAll())
                assertEquals(2, server.requestCount)
                val deleteConfirmation = RepoDialog.Delete(index)
                events.clear()
                actions.delete(deleteConfirmation.baseUrl, events::add)
                assertSuccess(events, ExtensionRepoAction.DELETE)
                assertEquals(0, repository.subscribeAll().first().size)
            }
        }
    }

    private fun screenFor(uri: String): ExtensionRepoScreen {
        val action = assertInstanceOf(
            ExternalAction.AddRepository::class.java,
            ExternalActionParser.resolve(ExternalActionInput.ViewUri(uri)),
        )
        return ExtensionRepoScreen(action.url)
    }

    private fun assertSuccess(events: List<ExtensionRepoActionResult>, action: ExtensionRepoAction) {
        assertEquals(
            listOf(ExtensionRepoActionResult.Pending(action), ExtensionRepoActionResult.Success(action)),
            events,
        )
        assertEquals("Completed", extensionRepoActionMessage(events.last()))
    }
}
