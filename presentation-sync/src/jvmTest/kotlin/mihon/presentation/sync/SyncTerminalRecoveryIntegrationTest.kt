package mihon.presentation.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.data.sync.auth.SyncDiscoveryProblem
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelController
import mihon.data.sync.runtime.SyncPanelPage
import mihon.data.sync.runtime.SyncPanelRunSource
import mihon.data.sync.runtime.SyncProgressDirection
import mihon.data.sync.runtime.SyncProgressFact
import mihon.data.sync.runtime.SyncProgressHold
import mihon.data.sync.runtime.SyncProgressStage
import mihon.data.sync.runtime.SyncRunPlanBatch
import mihon.data.sync.runtime.SyncRunState
import mihon.data.sync.runtime.SyncRuntime
import mihon.data.sync.runtime.SyncSetupStep
import mihon.domain.sync.runtime.SyncTrigger
import mihon.domain.sync.security.SyncSecureStore
import mihon.domain.sync.transport.SyncRepository
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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
import tachiyomi.i18n.MR
import java.nio.file.Files
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalComposeUiApi::class)
class SyncTerminalRecoveryIntegrationTest {
    @Test fun `R01 disconnected cancelled history click uses setup without changing durable facts`() = fixture {
        handler.await { sync_journalQueries.disconnectSpace("space", 1) }
        open()
        click("sync-now")
        withTimeout(5000) { panel.state.first { it.page == SyncPanelPage.SETUP } }
        assertEquals(SyncSetupStep.SIGN_IN, panel.state.value.setupStep)
        assertEquals(SyncPanelRunSource.LATEST, panel.state.value.runSource)
        assertFalse(runtime.connection()!!.enabled)
        assertFalse(handler.await { sync_journalQueries.getActiveSpace().executeAsOne().exchange_enabled })
        assertFalse(runtime.coordinator.activity.value.running)
        assertEquals(oldRunId, runtime.runStore.latest("space", 1)!!.runId)
        assertEquals(1536, runtime.runStore.get(oldRunId)!!.confirmedItems)
        assertEquals(SyncRunState.CANCELLED, runtime.runStore.get(oldRunId)!!.state)
        assertNull(runtime.runStore.active("space", 1))
    }

    @Test fun `R02 enabled cancelled history creates a separate legal run and preserves old result`() = fixture {
        open()
        now += 1000
        click("sync-now")
        withTimeout(5000) { panel.state.first { it.run != null && it.run!!.runId != oldRunId } }
        val next = runtime.runStore.latest("space", 1)!!
        assertNotEquals(oldRunId, next.runId)
        // Without a secure binding the real coordinator stops at STORAGE, after creating the new round.
        withTimeout(5000) { panel.state.first { it.run?.state == SyncRunState.BLOCKED } }
        assertEquals(0, runtime.runStore.get(next.runId)!!.confirmedItems)
        assertEquals(1536, runtime.runStore.get(oldRunId)!!.confirmedItems)
        assertEquals(SyncRunState.CANCELLED, runtime.runStore.get(oldRunId)!!.state)
        assertNotNull(runtime.runStore.get(oldRunId))
        assertNotNull(runtime.runStore.get(next.runId))
    }

    @Test fun `R04 incompatible binding with cancelled history exposes guarded error without exchange`() = fixture {
        val key = "space-" + java.security.MessageDigest.getInstance("SHA-256")
            .digest("1:space".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val legacy = """{"recovery":{"rawKeyset":"fixture-legacy"},"actorId":"actor","epoch":1}"""
        secure.values[key] = legacy
        open()
        assertTrue(runtime.connection()!!.unsupportedFormat)
        click("sync-view-reason")
        withTimeout(5000) { panel.state.first { it.page == SyncPanelPage.SETUP } }
        assertEquals(SyncSetupStep.ERROR, panel.state.value.setupStep)
        assertEquals(SyncDiscoveryProblem.INCOMPATIBLE, panel.state.value.setupProblem)
        awaitNode("sync-setup-error")
        assertTrue(texts().contains(MR.strings.sync_setup_incompatible.localized(Locale.getDefault())))
        assertEquals(legacy, secure.values[key])
        assertEquals(oldRunId, runtime.runStore.latest("space", 1)!!.runId)
        assertFalse(runtime.coordinator.activity.value.running)
    }

    @Test fun `current blocked and unresolved partial runs retain current recovery context`() = fixture {
        now += 1000
        val run = runtime.runStore.start("space", 1, SyncTrigger.MANUAL)
        runtime.runStore.finish(run.runId, SyncRunState.BLOCKED, "STORAGE")
        open()
        assertEquals(run.runId, runtime.runStore.active("space", 1)!!.runId)
        assertTrue(texts().contains(MR.strings.sync_blocked.localized(Locale.getDefault())))
        assertFalse(texts().contains("上次同步需要处理"))
        assertEquals(SyncPanelRunSource.ACTIVE, panel.state.value.runSource)
        now += 1000
        val partial = runtime.runStore.start("space", 1, SyncTrigger.MANUAL)
        assertTrue(runtime.runStore.claim(partial.runId, "fixture-owner", 1))
        runtime.runStore.expectDownload(partial.runId, "fixture-owner", "receipt", 3)
        runtime.runStore.finish(partial.runId, SyncRunState.PARTIAL, "projection_pending")
        open()
        assertEquals(partial.runId, runtime.runStore.active("space", 1)!!.runId)
        assertTrue(texts().contains(MR.strings.sync_terminal_partial.localized(Locale.getDefault())))
        assertFalse(texts().contains("上次同步已结束，部分数据待处理"))
    }

    @Test fun `R05 disconnected owned run keeps pause retry and recovery without creating another round`() = fixture {
        now += 1000
        val run = runtime.runStore.start("space", 1, SyncTrigger.MANUAL)
        assertTrue(runtime.runStore.claim(run.runId, "owned-fixture", 1))
        runtime.runStore.freezePlan(
            run.runId,
            "owned-fixture",
            listOf(SyncRunPlanBatch(SyncProgressDirection.UPLOAD, "owned-batch", 100)),
        )
        runtime.runStore.pause(run.runId)
        handler.await { sync_journalQueries.disconnectSpace("space", 1) }
        open()
        awaitNode("sync-resume-run")
        assertTrue(node("sync-resume-run")!!.config.contains(SemanticsProperties.Disabled))
        assertNull(node("sync-now"))
        assertNull(node("sync-retry-run"))
        runtime.runStore.progress(
            run.runId,
            mihon.data.sync.runtime.SyncRunPhase.UPLOADING,
            0,
            100,
            nextRetryAt = now + 60_000,
            state = SyncRunState.WAITING_RETRY,
            reason = "network",
        )
        open()
        awaitNode("sync-wait")
        assertTrue(node("sync-wait")!!.config.contains(SemanticsProperties.Disabled))
        assertEquals(now + 60_000, panel.state.value.run!!.nextRetryAt)
        assertTrue(texts().any { it.startsWith("已安排重试，已完成") })
        assertNull(node("sync-retry-countdown"))
        assertEquals(run.runId, runtime.runStore.latest("space", 1)!!.runId)
        assertFalse(runtime.coordinator.activity.value.running)
        assertNotNull(runtime.runStore.get(oldRunId))
        assertEquals(SyncRunState.CANCELLED, runtime.runStore.get(oldRunId)!!.state)
    }

    @Test fun `R07 slow old connection check cannot overwrite a newer space after refresh`() = fixture {
        open()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        secure.nextSpaceRead = entered to release
        panel.dispatch(SyncPanelAction.Open)
        withTimeout(5000) { entered.await() }
        runtime.baseline.connectAndImport("other-space", 2, SyncRepository("fixture", "sync", "main"), "actor2", 1)
        val newRun = runtime.runStore.start("other-space", 2, SyncTrigger.MANUAL)
        runtime.runStore.pause(newRun.runId)
        val observations = mutableListOf<String?>()
        val scope = CoroutineScope(currentCoroutineContext())
        val collecting = scope.launch { panel.state.collect { observations += it.connection?.spaceId } }
        try {
            release.complete(Unit)
            withTimeout(5000) {
                panel.state.first {
                    it.connection?.spaceId == "other-space" &&
                        it.run?.runId == newRun.runId
                }
            }
            open()
            delay(100)
            render()
            val firstNew = observations.indexOf("other-space")
            assertTrue(firstNew >= 0)
            assertTrue(observations.drop(firstNew).all { it == "other-space" })
            assertEquals(newRun.runId, panel.state.value.run!!.runId)
            assertEquals(SyncPanelRunSource.ACTIVE, panel.state.value.runSource)
            assertNotNull(node("sync-resume-run"))
            assertNull(node("sync-now"))
        } finally {
            collecting.cancel()
            release.complete(Unit)
        }
    }

    @Test fun `R06 R07 reopen reads newest identity and ignores late old progress`() = fixture {
        open()
        assertTrue(texts().any { it.contains("用时291:54") })
        now += 60000
        panel.dispatch(SyncPanelAction.Close)
        withTimeout(5000) { panel.state.first { !it.visible } }
        open()
        assertTrue(texts().any { it.contains("用时291:54") })
        assertNull(panel.state.value.notice)
        runtime.baseline.connectAndImport("other-space", 2, SyncRepository("fixture", "sync", "main"), "actor2", 1)
        val newRun = runtime.runStore.start("other-space", 2, SyncTrigger.MANUAL)
        runtime.runStore.pause(newRun.runId)
        open()
        @Suppress("UNCHECKED_CAST")
        val observations = runtime.liveProgress as MutableStateFlow<SyncProgressFact?>
        observations.value = SyncProgressFact(
            "$oldRunId:late", SyncProgressStage.TRANSFERRING, SyncProgressDirection.UPLOAD,
            1536, 2000, 99, 99, 100, 99, SyncProgressHold.ACTIVE, 1, 1, confirmedThisRun = 1536,
        )
        delay(100)
        render()
        assertEquals("other-space", panel.state.value.connection!!.spaceId)
        assertEquals(newRun.runId, panel.state.value.run!!.runId)
        assertEquals(SyncPanelRunSource.ACTIVE, panel.state.value.runSource)
        assertEquals(SyncRunState.PAUSED_USER, panel.state.value.run!!.state)
        assertEquals(0, runtime.runStore.get(newRun.runId)!!.confirmedItems)
        assertFalse(texts().contains("本次已确认 1536 条"))
        assertNotNull(node("sync-resume-run"))
        assertNull(node("sync-now"))
    }

    @Test fun `diagnostics settings entry collects actual history then returns through settings`() = fixture {
        handler.await { sync_journalQueries.disconnectSpace("space", 1) }
        open()
        click("sync-settings")
        click("sync-settings-diagnostics")
        click("sync-diagnostic-capture")
        withTimeout(5000) { panel.state.first { it.diagnosticSnapshot != null } }
        scrollDiagnostics(5)
        awaitNode("sync-diagnostic-details")
        assertTrue(texts().any { it.contains("CANCELLED") })
        assertTrue(texts().any { it.contains("1536") })
        assertFalse(runtime.connection()!!.enabled)
        assertEquals(oldRunId, runtime.runStore.latest("space", 1)!!.runId)
        click("sync-back")
        awaitNode("sync-settings-list")
        assertEquals(SyncPanelPage.SETTINGS, panel.state.value.page)
        click("sync-back")
        awaitNode("sync-progress-card")
        assertEquals(SyncPanelPage.MAIN, panel.state.value.page)
    }

    @Test fun `diagnostics exports actual local JSON and session cache ends without changing sync`() = fixture {
        open()
        click("sync-settings")
        click("sync-settings-diagnostics")
        click("sync-diagnostic-details-toggle")
        scrollDiagnostics(2)
        click("sync-diagnostic-session")
        withTimeout(5000) { panel.state.first { it.diagnosticSnapshot?.crossProcessComparable == true } }
        click("sync-diagnostic-session")
        withTimeout(5000) { panel.state.first { it.diagnosticSnapshot?.crossProcessComparable == false } }
        scrollDiagnostics(4)
        click("sync-diagnostic-export")
        withTimeout(5000) { panel.state.first { it.diagnosticPath != null } }
        click("sync-diagnostic-open")
        val path = java.nio.file.Path.of(requireNotNull(openedDiagnostic))
        assertTrue(Files.exists(path))
        val output = Files.readString(path)
        assertTrue(output.contains("1536"))
        assertFalse(output.contains(oldRunId))
        assertFalse(output.contains("fixture-owner"))
        assertFalse(Files.exists(directory.resolve("diagnostics/private/session.json")))
        assertEquals(oldRunId, runtime.runStore.latest("space", 1)!!.runId)
        panel.dispatch(SyncPanelAction.Close)
        withTimeout(5000) { panel.state.first { !it.visible } }
        assertNull(panel.state.value.diagnosticSnapshot)
        assertNull(panel.state.value.diagnosticFeedback)
        assertNull(panel.state.value.diagnosticPath)
    }

    @Test fun `diagnostics file save failure is visible and collection can be retried`() = fixture {
        Files.writeString(directory.resolve("diagnostics"), "blocked-file")
        open()
        click("sync-settings")
        click("sync-settings-diagnostics")
        click("sync-diagnostic-capture")
        withTimeout(5000) { panel.state.first { it.diagnosticSnapshot != null } }
        scrollDiagnostics(4)
        click("sync-diagnostic-export")
        withTimeout(5000) {
            panel.state.first {
                it.diagnosticFeedback == mihon.data.sync.runtime.SyncDiagnosticFeedback.SAVE_FAILED
            }
        }
        assertNull(panel.state.value.diagnosticPath)
        assertFalse(panel.state.value.diagnosticBusy)
        scrollDiagnostics(1)
        assertTrue(texts().any { it.contains("failed") || it.contains("失败") })
        click("sync-diagnostic-capture")
        withTimeout(5000) {
            panel.state.first {
                it.diagnosticFeedback == mihon.data.sync.runtime.SyncDiagnosticFeedback.CAPTURED
            }
        }
        assertEquals(oldRunId, runtime.runStore.latest("space", 1)!!.runId)
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture(ImageComposeScene(560, 720, coroutineContext = currentCoroutineContext()) {})
        try {
            fixture.initialize()
            fixture.block()
        } finally {
            fixture.close()
        }
    }

    private class MemorySecureStore : SyncSecureStore {
        val values = java.util.concurrent.ConcurrentHashMap<String, String>()

        @Volatile var nextSpaceRead: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null
        override suspend fun read(key: String): String? {
            val gate = nextSpaceRead?.takeIf { key.startsWith("space-") }
            if (gate != null) {
                nextSpaceRead = null
                gate.first.complete(Unit)
                gate.second.await()
            }
            return values[key]
        }
        override suspend fun compareAndSet(key: String, expected: String?, value: String?): Boolean {
            if (values[key] != expected) return false
            if (value == null) values.remove(key) else values[key] = value
            return true
        }
    }

    private class Fixture(val scene: ImageComposeScene) {
        val directory = Files.createTempDirectory("sync-terminal-recovery")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("sync.db")}")
        val database = run {
            Database.Schema.create(driver)
            Database(
                driver,
                History.Adapter(DateColumnAdapter),
                Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            )
        }
        val handler = JvmDatabaseHandler(database, driver)
        val bootstrap = CreatorArchiveLegacyBootstrap(CreatorArchiveLegacyBridge(handler))
        val creators = CreatorRepositoryImpl(handler, bootstrap = bootstrap)
        val secure = MemorySecureStore()
        val preferenceNode = java.util.prefs.Preferences.userRoot().node("/mihon-sync-tests/${UUID.randomUUID()}")
        val client = OkHttpClient()
        var now = 1000L
        val runtime = SyncRuntime(
            handler, bootstrap, creators, creators, { true }, secure,
            DesktopPreferenceStore(preferenceNode), client, clock = { now },
            diagnosticDirectory = directory.resolve("diagnostics").toString().toPath(),
        )
        val panel get() = runtime.panel as SyncPanelController
        var openedDiagnostic: String? = null
        lateinit var oldRunId: String

        suspend fun initialize() {
            runtime.baseline.connectAndImport("space", 1, SyncRepository("fixture", "sync", "main"), "actor", 1)
            oldRunId = runtime.runStore.start("space", 1, SyncTrigger.MANUAL).runId
            assertTrue(runtime.runStore.claim(oldRunId, "fixture-owner", 1))
            runtime.runStore.freezePlan(
                oldRunId,
                "fixture-owner",
                listOf(SyncRunPlanBatch(SyncProgressDirection.UPLOAD, "batch", 1536)),
            )
            runtime.runStore.confirmed(oldRunId, "fixture-owner", SyncProgressDirection.UPLOAD, "batch", 1536)
            now = 17_515_000
            runtime.runStore.cancel(oldRunId)
            scene.setContent {
                MaterialTheme {
                    SyncPanelContent(
                        panel,
                        onOpenBrowser = {},
                        onCopyCode = {},
                        onOpenDiagnostic = { openedDiagnostic = it },
                    )
                }
            }
        }
        suspend fun open() {
            val connection = runtime.connection()!!
            val expectedRun = runtime.runStore.active(connection.spaceId, connection.generation)
                ?: runtime.runStore.latest(connection.spaceId, connection.generation)
            panel.dispatch(SyncPanelAction.Open)
            withTimeout(5000) {
                panel.state.first {
                    it.visible && it.page == SyncPanelPage.MAIN && it.loaded &&
                        it.connection == connection && it.run == expectedRun
                }
            }
            render()
            awaitNode("sync-progress-card")
        }
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        fun nodes() = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
        fun node(tag: String) = nodes().firstOrNull {
            it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == tag
        }
        fun texts() = nodes().flatMap {
            if (it.config.contains(SemanticsProperties.Text)) {
                it.config[SemanticsProperties.Text].map { text -> text.text }
            } else {
                emptyList()
            }
        }
        suspend fun render() {
            repeat(4) {
                scene.render()
                yield()
            }
        }
        suspend fun awaitNode(tag: String) {
            withTimeout(5000) {
                while (node(tag) == null) {
                    render()
                    delay(10)
                }
            }
        }
        suspend fun click(tag: String) {
            awaitNode(tag)
            withTimeout(5000) {
                while (node(tag)?.config?.contains(SemanticsProperties.Disabled) != false) {
                    render()
                    delay(10)
                }
            }
            assertFalse(node(tag)!!.config.contains(SemanticsProperties.Disabled), "$tag must be executable")
            assertTrue(requireNotNull(node(tag)!!.config[SemanticsActions.OnClick].action).invoke())
            render()
        }
        suspend fun scrollDiagnostics(index: Int) {
            render()
            awaitNode("sync-diagnostics-list")
            if (index >= 2 && node("sync-diagnostic-session") == null) click("sync-diagnostic-details-toggle")
            requireNotNull(node("sync-diagnostics-list")!!.config[SemanticsActions.ScrollToIndex].action).invoke(
                if (index >=
                    2
                ) {
                    index + 2
                } else {
                    index
                },
            )
            render()
        }
        suspend fun close() {
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
