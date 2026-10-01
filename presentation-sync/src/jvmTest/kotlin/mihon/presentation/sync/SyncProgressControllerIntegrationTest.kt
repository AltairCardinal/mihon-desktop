package mihon.presentation.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.data.sync.inbox.SyncInboxStore
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncProgressDirection
import mihon.data.sync.runtime.SyncProgressFact
import mihon.data.sync.runtime.SyncProgressHold
import mihon.data.sync.runtime.SyncProgressStage
import mihon.data.sync.runtime.SyncRunPhase
import mihon.data.sync.runtime.SyncRunPlanBatch
import mihon.data.sync.runtime.SyncRunState
import mihon.data.sync.runtime.SyncRuntime
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncCancellationDecision
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffect
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncMutationContext
import mihon.domain.sync.SyncObjectDescriptor
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.runtime.SyncTrigger
import mihon.domain.sync.security.SyncSecureStore
import mihon.domain.sync.transport.SyncRepository
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.creator.CreatorArchiveLegacyBootstrap
import tachiyomi.data.creator.CreatorArchiveLegacyBridge
import tachiyomi.data.creator.CreatorRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import java.nio.file.Files

@OptIn(ExperimentalComposeUiApi::class)
class SyncProgressControllerIntegrationTest {
    @Test fun `D06 D07 controller preserves independent pauses and reopens durable result`() = runBlocking {
        val directory = Files.createTempDirectory("sync-display-contract")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("sync.db")}")
        Database.Schema.create(driver)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        val handler = JvmDatabaseHandler(database, driver)
        val bootstrap = CreatorArchiveLegacyBootstrap(CreatorArchiveLegacyBridge(handler))
        val creators = CreatorRepositoryImpl(handler, bootstrap = bootstrap)
        val manga = MangaRepositoryImpl(handler, creators)
        val item = manga.insertNetworkManga(
            listOf(Manga.create().copy(source = 1, url = "/fixture", title = "Fixture")),
        ).single()
        manga.update(MangaUpdate(item.id, favorite = true, syncContext = SyncMutationContext.User))
        val secure = object : SyncSecureStore {
            override suspend fun read(key: String): String? = null
            override suspend fun compareAndSet(key: String, expected: String?, value: String?) = true
        }
        val preferenceNode = java.util.prefs.Preferences.userRoot().node(
            "/mihon-sync-tests/${java.util.UUID.randomUUID()}",
        )
        val preferences = DesktopPreferenceStore(preferenceNode)
        val client = OkHttpClient()
        fun runtime() = SyncRuntime(
            handler, bootstrap, creators, creators, { true }, secure, preferences, client, clock = { 1000 },
        )
        var runtime = runtime()
        val scene = ImageComposeScene(400, 800, coroutineContext = coroutineContext) {}
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        fun node(tag: String) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.firstOrNull {
            it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == tag
        }
        suspend fun render() {
            repeat(4) {
                scene.render()
                yield()
            }
        }
        suspend fun click(tag: String) {
            try {
                withTimeout(5000) {
                    while (node(tag) == null) {
                        if (tag == "sync-pause-run") {
                            val stream =
                                runtime.liveProgress as kotlinx.coroutines.flow.MutableStateFlow<SyncProgressFact?>
                            stream.value = stream.value?.copy(
                                elapsedSeconds = (stream.value?.elapsedSeconds ?: 0) + 1,
                            )
                        }
                        render()
                    }
                }
            } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("Missing $tag", failure)
            }
            assertTrue(requireNotNull(node(tag)!!.config[SemanticsActions.OnClick].action).invoke())
            render()
        }
        fun mount() {
            val panel = runtime.panel
            scene.setContent {
                MaterialTheme { SyncPanelContent(panel, onOpenBrowser = {}, onCopyCode = {}) }
            }
        }
        try {
            runtime.baseline.connectAndImport("space", 1, SyncRepository("fixture", "sync", "main"), "actor", 1)
            val run = runtime.runStore.start("space", 1, SyncTrigger.MANUAL)
            runtime.runStore.progress(run.runId, SyncRunPhase.UPLOADING, 0, 1)
            assertTrue(runtime.runStore.claim(run.runId, "fixture-owner", 1))
            manga.update(MangaUpdate(item.id, favorite = false, syncContext = SyncMutationContext.User))
            val uploadRound = mihon.data.sync.journal.SyncOutboxStore(handler).freezeRound("space", 1)
            assertEquals(1L, uploadRound.totalItems)
            mount()
            runtime.panel.dispatch(SyncPanelAction.Open)
            withTimeout(5000) { runtime.panel.state.first { it.loaded && it.run?.runId == run.runId } }
            withTimeout(5000) { while (node("sync-round-time") == null) render() }
            assertNotNull(node("sync-round-time"))
            assertNull(node("sync-progress-track"))
            runtime.runStore.freezePlan(
                run.runId,
                "fixture-owner",
                uploadRound.batchItems.map { (id, count) -> SyncRunPlanBatch(SyncProgressDirection.UPLOAD, id, count) },
            )
            withTimeout(5000) { runtime.panel.state.first { it.run?.plannedItems == 1L } }
            withTimeout(5000) { while (node("sync-progress-track") == null) render() }
            assertEquals(0f, node("sync-progress-track")!!.config[SemanticsProperties.ProgressBarRangeInfo].current)
            assertEquals(0L, runtime.panel.state.value.run!!.confirmedItems)
            assertTrue(node("sync-progress-status")!!.config[SemanticsProperties.Text].single().text.contains("0/1"))
            // Feed the production runtime observation stream, retaining the actual controller and database.
            @Suppress("UNCHECKED_CAST")
            val observations = runtime.liveProgress as kotlinx.coroutines.flow.MutableStateFlow<SyncProgressFact?>
            observations.value = SyncProgressFact(
                "${run.runId}:fixture", SyncProgressStage.TRANSFERRING,
                SyncProgressDirection.UPLOAD, 0, 1, 0, 0, 100, 0, SyncProgressHold.ACTIVE, null, null,
            )
            click("sync-pause-run")
            withTimeout(5000) { runtime.panel.state.first { it.run?.state == SyncRunState.PAUSED_USER } }
            assertEquals(SyncRunState.PAUSED_USER, runtime.runStore.get(run.runId)!!.state)
            click("sync-pause-import")
            withTimeout(5000) { runtime.panel.state.first { it.importPaused } }
            assertTrue(runtime.preferences.importPaused.get())
            assertEquals(SyncRunState.PAUSED_USER, runtime.runStore.get(run.runId)!!.state)
            click("sync-resume-import")
            withTimeout(5000) { runtime.panel.state.first { !it.importPaused } }
            assertFalse(runtime.preferences.importPaused.get())
            assertEquals(SyncRunState.PAUSED_USER, runtime.runStore.get(run.runId)!!.state)
            // A secondary-task resume must not bypass the durable pause or create a new round.
            withTimeout(5000) { runtime.coordinator.activity.first { !it.running } }
            assertEquals(run.runId, runtime.runStore.latest("space", 1)!!.runId)
            assertEquals(SyncRunState.PAUSED_USER, runtime.runStore.get(run.runId)!!.state)
            val inbox = SyncInboxStore(handler)
            val additions = (0 until 400).map { index ->
                val key = SyncObjectKey(SyncObjectType.MANGA, sourceId = "1", originalUrl = "/bulk-$index")
                SyncEventEnvelope(
                    1, "space", 1, "bulk-fixture", 1, index.toLong() + 1, SyncCategory.FAVORITE,
                    listOf(SyncEffect("membership", key, SyncField.FAVORITE, SyncEffectKind.ADD)), SyncOrigin.USER,
                    batchId = "bulk-add",
                )
            }
            additions.chunked(200).forEachIndexed { index, events ->
                val id = "bulk-add-$index"
                val reception = inbox.ingest(
                    SyncBatch(
                        1,
                        "space",
                        1,
                        id,
                        events.map { it.copy(batchId = id) },
                        events.map { SyncObjectDescriptor(it.effects.single().objectKey, "Bulk") },
                    ),
                )
                assertTrue(reception.accepted, reception.toString())
            }
            while (runtime.projector.project("space", 1) == 50) Unit
            val removals = additions.map { add ->
                add.copy(
                    seq = add.seq + 400,
                    batchId = "bulk-remove",
                    effects = listOf(
                        add.effects.single().copy(
                            kind = SyncEffectKind.REMOVE,
                            parents = listOf(add.ref("membership")),
                        ),
                    ),
                )
            }
            removals.chunked(200).forEachIndexed { index, events ->
                val id = "bulk-remove-$index"
                val reception = inbox.ingest(SyncBatch(1, "space", 1, id, events.map { it.copy(batchId = id) }))
                assertTrue(reception.accepted, reception.toString())
            }

            while (runtime.projector.project("space", 1) == 50) Unit
            val job = runtime.projector.startBulk("space", 1, SyncCancellationDecision.KEEP_LOCAL)
            runtime.preferences.activeBulkJob("space", 1).set(job)
            runtime.panel.dispatch(SyncPanelAction.Open)
            withTimeout(5000) { runtime.panel.state.first { it.bulk?.jobId == job } }
            click("sync-resume-bulk")
            click("sync-pause-bulk")
            withTimeout(5000) { runtime.panel.state.first { it.bulk?.running == false } }
            assertTrue(runtime.panel.state.value.bulk!!.remaining > 0)
            assertEquals(SyncRunState.PAUSED_USER, runtime.runStore.get(run.runId)!!.state)
            assertFalse(runtime.preferences.importPaused.get())
            click("sync-resume-bulk")
            withTimeout(10000) { runtime.panel.state.first { it.bulk?.remaining == 0L } }
            assertEquals(0L, runtime.projector.bulkProgress(job).queued)
            assertEquals(SyncRunState.PAUSED_USER, runtime.runStore.get(run.runId)!!.state)
            assertFalse(runtime.preferences.importPaused.get())
            click("sync-resume-run")
            withTimeout(5000) { runtime.panel.state.first { it.run?.state == SyncRunState.BLOCKED } }
            assertEquals(SyncRunState.BLOCKED, runtime.runStore.get(run.runId)!!.state)
            runtime.panel.dispatch(SyncPanelAction.Close)
            withTimeout(5000) { runtime.panel.state.first { !it.visible } }
            runtime.stopPanel()
            runtime = runtime()
            mount()
            runtime.panel.dispatch(SyncPanelAction.Open)
            withTimeout(5000) { runtime.panel.state.first { it.loaded && it.run?.runId == run.runId } }
            render()
            assertEquals(SyncRunState.BLOCKED, runtime.panel.state.value.run!!.state)
            assertNull(node("sync-progress-details"))
            assertEquals(mihon.domain.sync.runtime.SyncRunProblem.STORAGE, runtime.panel.state.value.problem)
            withTimeout(5000) { while (node("sync-reenter-password") == null) render() }
            assertNotNull(node("sync-reenter-password"))
        } finally {
            scene.close()
            runtime.coordinator.cancelAndJoin()
            runtime.stopPanel()
            handler.close()
            preferenceNode.removeNode()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            Files.walk(directory).use { paths -> paths.forEach { it.toFile().deleteOnExit() } }
        }
    }
}
