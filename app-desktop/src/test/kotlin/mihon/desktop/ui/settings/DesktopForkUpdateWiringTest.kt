package mihon.desktop.ui.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.first
import mihon.desktop.domain.DesktopNotification
import mihon.desktop.domain.DesktopNotificationService
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.di.inMemoryDesktopPreferenceStore
import mihon.desktop.DesktopUiDependencies
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.file.Path
import mihon.desktop.DesktopAppRuntime
import mihon.desktop.DesktopRuntimeService
import mihon.desktop.update.DesktopUpdateController
import mihon.desktop.update.DesktopUpdateState
import mihon.desktop.update.DownloadFailed
import mihon.desktop.update.DownloadFailure
import mihon.desktop.update.ReadyToInstall
import mihon.desktop.update.VerifiedDownload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.release.service.ApplicationUpdatePreferences
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.model.UpdateChannel

@OptIn(ExperimentalCoroutinesApi::class)
@Isolated
class DesktopForkUpdateWiringTest {
    @Test
    fun `production DI shares persisted update preferences and routes notices to home`(@TempDir directory: Path) = runBlocking {
        val store = inMemoryDesktopPreferenceStore()
        val context = initDesktopDIForTest(directory.toFile(), store)
        var mockedChecker: GetApplicationRelease? = null
        try {
            val preferences = Injekt.get<ApplicationUpdatePreferences>()
            val model = requireNotNull(DesktopUiDependencies.fromInjekt().updateScreenModel)
            assertSame(preferences, model.preferences)
            preferences.enabled().set(false)
            assertFalse(ApplicationUpdatePreferences(store).enabled().get())
            preferences.enabled().set(true)
            val checker = Injekt.get<GetApplicationRelease>()
            mockedChecker = checker
            mockkObject(checker)
            coEvery { checker.await(any()) } returns GetApplicationRelease.Result.NewUpdate(
                Release("0.11.20.1.abcdef12", "Notes", "https://github.com/AltairCardinal/mihon-desktop/releases/tag/test", "unused"),
            )
            val observed = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) { Injekt.get<DesktopNotificationService>().notifications.first() }
            }
            model.startAutomaticChecks()
            assertTrue(observed.await().message.contains("0.11.20.1.abcdef12"))
        } finally {
            context.closeAndJoin()
            mockedChecker?.let { unmockkObject(it) }
        }
    }

    @Test
    fun `restart waits for previous transport cleanup instead of losing the first check`() = runTest {
        val releaseCleanup = CompletableDeferred<Unit>()
        var checks = 0
        val model = DesktopUpdateScreenModel(controller {
            checks++
            if (checks == 1) {
                try { awaitCancellation() } finally { withContext(NonCancellable) { releaseCleanup.await() } }
            }
            GetApplicationRelease.Result.NoNewUpdate
        }, this, executionContext = StandardTestDispatcher(testScheduler))
        try {
            model.startAutomaticChecks()
            runCurrent()
            model.stopAutomaticChecks()
            model.startAutomaticChecks()
            runCurrent()
            assertEquals(1, checks)
            releaseCleanup.complete(Unit)
            runCurrent()
            assertEquals(2, checks, "Restart must check immediately after old cleanup, not twelve hours later")
        } finally {
            releaseCleanup.complete(Unit)
            model.closeAndJoin()
        }
    }

    @Test
    fun `automatic check preserves generic installation confirmation and download retry state`() = runTest {
        val release = Release("0.11.20.1.abcdef12", "Notes", "https://github.com/AltairCardinal/mihon-desktop/releases/tag/test", "unused")
        listOf(false, true).forEach { downloadFails ->
            val download = VerifiedDownload(Path.of("fixture.msi"), release.asset, "a".repeat(64), 1)
            val controller = DesktopUpdateController(
                checkRelease = { GetApplicationRelease.Result.NewUpdate(release) },
                downloadRelease = { _, _ -> if (downloadFails) DownloadFailed(DownloadFailure.HTTP) else download },
                prepareInstall = { _, _ -> ReadyToInstall(download, release.version, emptyList()) },
                handoffInstall = { _, _ -> error("Must not install") },
            )
            controller.check(DesktopUpdateScreenModel.releaseArguments())
            controller.download()
            val pending = controller.state.value
            assertTrue(pending is DesktopUpdateState.ReadyToInstall || pending is DesktopUpdateState.RetryableFailure)
            val model = DesktopUpdateScreenModel(controller, this, executionContext = StandardTestDispatcher(testScheduler), manualOnlyUpdates = false)
            try {
                model.startAutomaticChecks()
                runCurrent()
                assertEquals(pending, controller.state.value)
            } finally {
                model.closeAndJoin()
            }
        }
    }

    @Test
    fun `startup update notice waits until the home notification collector is ready`() = runTest {
        val service = DesktopNotificationService()
        val notification = DesktopNotification("Update available", "Open About")
        val delivery = async { service.postWhenObserved(notification) }
        try {
            runCurrent()
            assertFalse(delivery.isCompleted, "Do not consume dedup state before any UI can observe the notice")
            val observed = async { service.notifications.first() }
            runCurrent()
            assertEquals(notification, observed.await())
            assertTrue(delivery.await())
        } finally {
            delivery.cancel()
        }
    }

    @Test
    fun `unobserved startup notice times out without recording delivery`() = runTest {
        val service = DesktopNotificationService()
        val delivery = async { service.postWhenObserved(DesktopNotification("Update", "About")) }
        runCurrent()
        assertFalse(delivery.isCompleted)
        advanceTimeBy(30_000)
        runCurrent()
        assertFalse(delivery.await())
    }

    @Test
    fun `cancel action cancels an automatic check without changing user preferences`() = runTest {
        var cancelled = false
        val model = DesktopUpdateScreenModel(controller {
            try { awaitCancellation() } finally { cancelled = true }
        }, this, executionContext = StandardTestDispatcher(testScheduler))
        try {
            model.startAutomaticChecks()
            runCurrent()
            assertTrue(model.intent(DesktopUpdateIntent.CANCEL))
            runCurrent()
            assertTrue(cancelled)
            assertTrue(model.preferences.enabled().get())
        } finally {
            model.closeAndJoin()
        }
    }
    @Test
    fun `desktop checks its own repository and keeps the build identity`() {
        val arguments = DesktopUpdateScreenModel.releaseArguments("0.11.19.92.abcdef12")

        assertEquals("AltairCardinal/mihon-desktop", arguments.repository)
        assertEquals("0.11.19.92.abcdef12", arguments.versionName)
    }

    @Test
    fun `production runtime starts the injected updater and closes its work`() = runTest {
        var checks = 0
        val controller = DesktopUpdateController(
            checkRelease = {
                checks++
                GetApplicationRelease.Result.NoNewUpdate
            },
            downloadRelease = { _, _ -> error("Startup must not download") },
            prepareInstall = { _, _ -> error("Startup must not install") },
            handoffInstall = { _, _ -> error("Startup must not install") },
        )
        val model = DesktopUpdateScreenModel(controller, this, executionContext = StandardTestDispatcher(testScheduler))
        val service = object : DesktopRuntimeService {
            override fun start() = Unit
            override fun stop() = Unit
        }
        val runtime = DesktopAppRuntime(
            libraryUpdateScheduler = service,
            localSourceScanService = service,
            autoBackupScheduler = service,
            startupCleanup = {},
            updateScreenModel = model,
        )
        try {
            runtime.start()
            runCurrent()
            assertEquals(1, checks)
            runtime.start()
            runCurrent()
            assertEquals(1, checks, "Repeated runtime start must not duplicate update work")
        } finally {
            runtime.closeAndJoin()
        }
    }

    @Test
    fun `automatic preferences channel refresh and notification dedup use the real controller`() = runTest {
        val preferences = ApplicationUpdatePreferences(InMemoryPreferenceStore())
        val requests = mutableListOf<GetApplicationRelease.Arguments>()
        val notices = mutableListOf<String>()
        val release = Release("0.11.20.1.abcdef12", "Notes", "https://github.com/AltairCardinal/mihon-desktop/releases/tag/test", "unused")
        val controller = controller {
            requests += it
            if (it.forkUpdate?.channel == UpdateChannel.TESTING) GetApplicationRelease.Result.NewUpdate(release)
            else GetApplicationRelease.Result.NoNewUpdate
        }
        val model = DesktopUpdateScreenModel(controller, this, executionContext = StandardTestDispatcher(testScheduler),
            preferences = preferences, notifyUpdate = { notices += it.version; true })
        try {
            model.startAutomaticChecks()
            runCurrent()
            assertEquals(1, requests.size)
            assertFalse(requests.single().forceCheck)
            assertEquals(listOf(release.version), notices)
            assertEquals(setOf(DesktopUpdateIntent.CHECK, DesktopUpdateIntent.MANUAL), model.presentation().actions)
            assertFalse(model.intent(DesktopUpdateIntent.DOWNLOAD))

            advanceTimeBy(12 * 60 * 60 * 1000L)
            runCurrent()
            assertEquals(2, requests.size)
            assertEquals(1, notices.size, "Known version must not notify repeatedly")

            preferences.enabled().set(false)
            runCurrent()
            preferences.testingChannel().set(false)
            runCurrent()
            assertEquals(2, requests.size, "Disabled automatic checks must not request on channel switch")
            assertEquals(setOf(DesktopUpdateIntent.CHECK), model.presentation().actions, "Old testing candidate must disappear")
            assertTrue(model.intent(DesktopUpdateIntent.CHECK))
            runCurrent()
            assertEquals(UpdateChannel.STABLE, requests.last().forkUpdate?.channel)
            assertTrue(requests.last().forceCheck)
        } finally {
            model.closeAndJoin()
        }
    }

    @Test
    fun `stopping automatic lifecycle cancels in flight transport`() = runTest {
        var cancelled = false
        val model = DesktopUpdateScreenModel(controller {
            try { awaitCancellation() } finally { cancelled = true }
        }, this, executionContext = StandardTestDispatcher(testScheduler))
        try {
            model.startAutomaticChecks()
            runCurrent()
            model.stopAutomaticChecks()
            runCurrent()
            assertTrue(cancelled)
        } finally {
            model.closeAndJoin()
        }
    }

    private fun controller(check: suspend (GetApplicationRelease.Arguments) -> GetApplicationRelease.Result) = DesktopUpdateController(
        checkRelease = check,
        downloadRelease = { _, _ -> error("Must not download") },
        prepareInstall = { _, _ -> error("Must not install") },
        handoffInstall = { _, _ -> error("Must not install") },
    )
}
