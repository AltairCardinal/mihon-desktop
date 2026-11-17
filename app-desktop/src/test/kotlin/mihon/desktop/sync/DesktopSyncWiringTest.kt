package mihon.desktop.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mihon.data.sync.runtime.SyncRuntime
import mihon.desktop.DesktopAppRuntime
import mihon.desktop.di.initDesktopDIForTest
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.runtime.SyncTrigger
import mihon.domain.sync.security.SyncSecureStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences

@Isolated
class DesktopSyncWiringTest {
    @Test
    fun `default scheduler wakes a durable retry with periodic synchronization disabled`(
        @TempDir folder: File,
    ) = runBlocking {
        val node = Preferences.userRoot().node("mihon-sync-deadline-di-" + UUID.randomUUID())
        val keys = java.util.concurrent.ConcurrentHashMap<String, CharArray>()
        val context = initDesktopDIForTest(
            folder,
            DesktopPreferenceStore(node),
            credentialBackendFactory = {
                object : mihon.desktop.platform.CredentialBackend {
                    override fun save(account: String, secret: CharArray) {
                        keys[account] = secret.copyOf()
                    }
                    override fun load(account: String): CharArray? = keys[account]?.copyOf()
                    override fun delete(account: String) {
                        keys.remove(account)?.fill('\u0000')
                    }
                }
            },
        )
        try {
            val runtime = Injekt.get<SyncRuntime>()
            runtime.preferences.startup.set(false)
            runtime.preferences.setInterval(0)
            val spaceId = "deadline-space"
            val repository = mihon.domain.sync.transport.SyncRepository("fixture", "mihon-sync", "main")
            mihon.data.sync.journal.SyncLocalJournal(Injekt.get()).connect(spaceId, 1, repository, "local", 1)
            val descriptor = mihon.domain.sync.crypto.SyncSpaceDescriptorCodec.encode(
                mihon.data.sync.crypto.SyncSpaceCrypto.create(spaceId, 1, "").descriptor,
            ).decodeToString()
            val binding = kotlinx.serialization.json.buildJsonObject {
                put("version", kotlinx.serialization.json.JsonPrimitive(2))
                put("accountId", kotlinx.serialization.json.JsonPrimitive(1))
                put("accountLogin", kotlinx.serialization.json.JsonPrimitive("fixture"))
                put("repositoryId", kotlinx.serialization.json.JsonPrimitive(2))
                put("owner", kotlinx.serialization.json.JsonPrimitive(repository.owner))
                put("repository", kotlinx.serialization.json.JsonPrimitive(repository.name))
                put("branch", kotlinx.serialization.json.JsonPrimitive(repository.branch))
                put(
                    "material",
                    kotlinx.serialization.json.buildJsonObject {
                        put("descriptor", kotlinx.serialization.json.JsonPrimitive(descriptor))
                        put("keyHex", kotlinx.serialization.json.JsonNull)
                    },
                )
                put("actorId", kotlinx.serialization.json.JsonPrimitive("local"))
                put("epoch", kotlinx.serialization.json.JsonPrimitive(1))
            }.toString()
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest("1:$spaceId".toByteArray()).joinToString("") { "%02x".format(it) }
            assertTrue(Injekt.get<SyncSecureStore>().compareAndSet("space-$digest", null, binding))
            val run = runtime.runStore.start(spaceId, 1, SyncTrigger.MANUAL)
            assertTrue(runtime.runStore.claim(run.runId, "previous-process", 1))
            runtime.runStore.progress(
                run.runId,
                mihon.data.sync.runtime.SyncRunPhase.CHECKING,
                0,
                0,
                attemptId = 1,
                nextRetryAt = System.currentTimeMillis() + 2_000,
                state = mihon.data.sync.runtime.SyncRunState.WAITING_RETRY,
                reason = "network",
                ownerSession = "previous-process",
            )
            runtime.runStore.finish(
                run.runId,
                mihon.data.sync.runtime.SyncRunState.WAITING_RETRY,
                "network",
                "previous-process",
            )
            assertTrue(runtime.hasResumableRun())
            val scheduler = Injekt.get<DesktopSyncScheduler>()
            scheduler.start()
            kotlinx.coroutines.withTimeout(10_000) {
                while (runtime.coordinator.activity.value.completion == 0L) kotlinx.coroutines.delay(10)
            }
            // No credential was supplied: the real resumed exchange must reach the authorization boundary.
            val resumed = runtime.runStore.latest(spaceId, 1)!!
            assertEquals(run.runId, resumed.runId)
            assertEquals(2L, resumed.attemptId)
            assertEquals(
                mihon.domain.sync.runtime.SyncRunProblem.AUTHORIZATION,
                runtime.coordinator.activity.value.result?.problem,
            )
            assertEquals(0, runtime.preferences.intervalMinutes())
        } finally {
            context.closeAndJoin()
            keys.values.forEach { it.fill('\u0000') }
            node.removeNode()
        }
    }

    @Test
    fun `isolated graph factory consumes the exact test target`(@TempDir folder: File) = runBlocking {
        val node = Preferences.userRoot().node("mihon-sync-acceptance-di-" + UUID.randomUUID())
        val scope = mihon.data.sync.auth.SyncRepositoryScope.acceptance("mihon-sync-acceptance-fixture-none")
        val context = initDesktopDIForTest(folder, DesktopPreferenceStore(node), syncRepositoryScope = scope)
        try {
            val runtime = Injekt.get<SyncRuntime>()
            assertSame(scope, runtime.repositoryScope)
            assertEquals(scope.repositoryName, runtime.repositoryScope.repositoryName)
            assertSame(runtime.panel, mihon.desktop.DesktopUiDependencies.fromInjekt().syncPanel)
            assertEquals(SyncRunStatus.SKIPPED, runtime.coordinator.synchronize(SyncTrigger.MANUAL).status)
            val snapshot = runtime.diagnostics.capture(mihon.data.sync.runtime.SyncPanelState())
            val exported = requireNotNull(runtime.diagnostics.export(snapshot))
            val json = kotlinx.serialization.json.Json.parseToJsonElement(File(exported).readText())
                as kotlinx.serialization.json.JsonObject
            val journal = json["journal"] as? kotlinx.serialization.json.JsonObject
            assertTrue(journal != null, "production Desktop DI must export its durable sync journal")
            val events = journal?.get("events") as? kotlinx.serialization.json.JsonArray
            assertTrue(events?.isNotEmpty() == true, "a real coordinator skip must leave a durable reason")
            assertTrue(folder.resolve("logs/sync-diagnostics-journal").isDirectory)
        } finally {
            context.closeAndJoin()
            node.removeNode()
        }
    }

    @Test
    fun `all read final chapters stop library detail and official history continuation`(
        @TempDir folder: File,
    ) = runBlocking {
        val node = Preferences.userRoot().node("mihon-sync-resume-di-" + UUID.randomUUID())
        val context = initDesktopDIForTest(folder, DesktopPreferenceStore(node))
        try {
            mihon.data.sync.journal.SyncLocalJournal(Injekt.get()).connect(
                "space",
                1,
                mihon.domain.sync.transport.SyncRepository("owner", "sync", "sync"),
                "reader",
                1,
            )
            val manga = Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>().insertNetworkManga(
                listOf(tachiyomi.domain.manga.model.Manga.create().copy(source = 42, url = "/manga", title = "Resume")),
            ).single()
            val chapter = Injekt.get<tachiyomi.domain.chapter.repository.ChapterRepository>().addAll(
                listOf(
                    tachiyomi.domain.chapter.model.Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/chapter",
                        read = true,
                    ),
                ),
            ).single()
            Injekt.get<tachiyomi.domain.reader.interactor.RecordReadingProgress>().await(
                tachiyomi.domain.reader.model.ReadingProgressEvent(
                    chapter.id,
                    2,
                    10,
                    java.util.Date(1000),
                    0,
                    wasRead = true,
                    syncContext = mihon.domain.sync.SyncMutationContext.User,
                ),
            )
            val item = tachiyomi.domain.library.model.LibraryManga(manga, emptyList(), 1, 1, 0, 0, 0, 0)
            val library = mihon.desktop.library.LibraryScreenModelFactory.create().continueReadingRequest(item)
            val detail = mihon.desktop.library.MangaDetailScreenModelFactory.create(manga.id)
                .continueReadingRequest(manga, listOf(chapter))
            assertEquals(null, library)
            assertEquals(null, detail)
            val history = Injekt.get<tachiyomi.domain.history.interactor.GetHistory>().subscribe("").first().single()
            val model = mihon.desktop.history.HistoryScreenModelFactory.create()
            assertEquals(null, model.readerRequestFor(history))
            model.onDispose()
        } finally {
            context.closeAndJoin()
            node.removeNode()
        }
    }

    @Test
    fun `default graph exposes one sync runtime and lifecycle reaches the same coordinator`(
        @TempDir folder: File,
    ) = runBlocking {
        val node = Preferences.userRoot().node("mihon-sync-di-test-" + UUID.randomUUID())
        val context = initDesktopDIForTest(folder, DesktopPreferenceStore(node))
        try {
            val runtime = Injekt.get<SyncRuntime>()
            val cacheField = runtime.javaClass.getDeclaredField("persistentObjectCacheDirectory")
                .apply { isAccessible = true }
            assertEquals(
                File(folder, "cache/network/mihon-sync-objects").absolutePath.replace('\\', '/'),
                cacheField.get(runtime).toString().replace('\\', '/'),
            )
            val longSpace = "space".repeat(25)
            val reportDirectory = runtime.javaClass.getDeclaredField("failureLogDirectory")
                .apply { isAccessible = true }
            assertEquals(
                File(folder, "logs/sync-failures").absolutePath.replace('\\', '/'),
                reportDirectory.get(runtime).toString().replace('\\', '/'),
            )
            runtime.preferences.activeBulkJob(longSpace, 1).set("paused-a")
            runtime.preferences.activeBulkJob("other", 1).set("paused-b")
            val reopened = mihon.domain.sync.runtime.SyncPreferences(DesktopPreferenceStore(node))
            assertEquals("paused-a", reopened.activeBulkJob(longSpace, 1).get())
            assertEquals("paused-b", reopened.activeBulkJob("other", 1).get())
            assertEquals("", reopened.activeBulkJob(longSpace, 2).get())
            assertSame(runtime, Injekt.get<SyncRuntime>())
            assertSame(runtime.panel, mihon.desktop.DesktopUiDependencies.fromInjekt().syncPanel)
            assertTrue(mihon.desktop.DesktopUiDependencies.fromInjekt().recoveryPackageInstaller != null)
            assertSame(
                Injekt.get<mihon.desktop.update.InstallerTrust>(),
                Injekt.get<mihon.desktop.update.DesktopUpdateInstaller>().trust,
            )
            assertTrue(Injekt.get<SyncSecureStore>() is DesktopSyncSecureStore)
            assertSame(runtime.coordinator, Injekt.get<DesktopSyncScheduler>().coordinator)
            assertEquals(SyncRunStatus.SKIPPED, runtime.coordinator.synchronize(SyncTrigger.MANUAL).status)
            val before = runtime.coordinator.activity.value.completion
            Injekt.get<DesktopAppRuntime>().start()
            kotlinx.coroutines.withTimeout(5000) {
                while (runtime.coordinator.activity.value.completion == before) kotlinx.coroutines.delay(10)
            }
            assertEquals(SyncRunStatus.SKIPPED, runtime.coordinator.activity.value.result?.status)
            context.closeAndJoin()
            runtime.panel.dispatch(mihon.data.sync.runtime.SyncPanelAction.Open)
            assertEquals(null, kotlinx.coroutines.withTimeoutOrNull(500) { runtime.panel.state.first { it.visible } })
        } finally {
            context.closeAndJoin()
            node.removeNode()
        }
    }
}
