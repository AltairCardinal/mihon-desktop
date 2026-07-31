package mihon.desktop.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.Tab
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.DesktopAppRuntime
import mihon.desktop.DesktopRuntimeService
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.DesktopWindowFocusListener
import mihon.desktop.DesktopWindowFocusRegistration
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.bootstrapDesktopRuntime
import mihon.desktop.runProductionOwnerLifecycle
import mihon.desktop.platform.CredentialBackend
import mihon.desktop.platform.CommandResult
import mihon.desktop.platform.CommandRunner
import mihon.desktop.platform.DesktopCredentialStore
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.platform.OperatingSystem
import mihon.desktop.platform.PlatformCredentialBackend
import mihon.desktop.platform.PlatformCredentialUnavailableException
import mihon.desktop.privacy.DesktopPrivacyCapabilities
import mihon.desktop.privacy.DesktopCapabilitySupport
import mihon.desktop.privacy.DesktopWindowPrivacy
import mihon.desktop.privacy.DesktopWindowPrivacyController
import mihon.desktop.security.DesktopAppLock
import mihon.desktop.security.DesktopAppLockLifecycle
import mihon.desktop.security.DesktopPassphraseVerifier
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.settings.ThemeMode
import mihon.desktop.test.http.currentTestStateJson
import mihon.desktop.test.http.nestedTestScreenAction
import mihon.desktop.test.state.applicationState
import mihon.desktop.test.TestArguments
import mihon.desktop.update.DesktopUpdateController
import mihon.desktop.update.InstallCancelled
import mihon.desktop.update.InstallManualOnly
import mihon.desktop.update.ManualOnly
import mihon.desktop.ui.navigatorFixture
import mihon.desktop.ui.security.DesktopProtectedRoot
import mihon.desktop.ui.security.DesktopPasswordField
import mihon.domain.security.AuthenticationResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@OptIn(ExperimentalComposeUiApi::class)
@Isolated
class SecuritySettingsWiringTest {
    private val preferenceNodes = mutableListOf<Preferences>()

    @AfterEach
    fun cleanUp() {
        applicationState.reset()
        preferenceNodes.forEach { runCatching { it.removeNode() } }
    }

    @Test
    fun `More pushes Security as a regular Screen and automation maps the same destination`() = runBlocking {
        val fixture = navigatorFixture()
        try {
            MoreRootScreen().onSecurity(fixture.navigator)

            assertTrue(fixture.navigator.lastItem is SecuritySettingsScreen)
            assertTrue(fixture.navigator.lastItem is Screen)
            assertFalse(fixture.navigator.lastItem is Tab)
            assertEquals("open_security_settings", nestedTestScreenAction("SecuritySettingsScreen"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `catalog anchor preserves native toggle and unsupported capability boundaries`() = runBlocking {
        securityAnchorFixture().use { fixture ->
            val title = MR.strings.desktop_secure_screen_title.localized()
            val result = DesktopSettingsCatalog.search(title).single {
                it.route is SecuritySettingsScreen && it.anchorTitle == title
            }
            DesktopSettingsAnchorOwner.publish(result.route, result.anchorTitle)
            fixture.navigator.replace(result.route)
            val copy = renderAnchor(fixture.scene)
            val highlighted = anchorNodes(fixture.scene, true).single {
                it.config.contains(DesktopSettingsAnchorHighlighted) && it.config[DesktopSettingsAnchorHighlighted]
            }
            assertTrue(flatten(highlighted).any { title in anchorText(it) })
            assertTrue(highlighted.boundsInRoot.height > 0f)
            assertTrue(anchorScroll(fixture.scene).value() > 0f)
            assertTrue(MR.strings.desktop_privacy_telemetry_unavailable.localized() in copy)
            assertTrue(MR.strings.desktop_privacy_widget_unavailable_updates_available.localized() in copy)
            assertFalse(MR.strings.desktop_privacy_native_notifications_unavailable.localized() in copy)
            assertEquals(2, anchorNodes(fixture.scene, true).count { it.config.contains(SemanticsProperties.ToggleableState) })

            val nativeTitle = MR.strings.hide_notification_content.localized()
            val nativeToggle = anchorNodes(fixture.scene, true).single {
                it.config.contains(SemanticsProperties.ToggleableState) &&
                    it.config.contains(SemanticsProperties.ContentDescription) &&
                    nativeTitle in it.config[SemanticsProperties.ContentDescription]
            }
            assertTrue(requireNotNull(nativeToggle.config[SemanticsActions.OnClick].action).invoke())
            renderAnchor(fixture.scene)
            assertTrue(fixture.preferences.hideNotificationContent().get())

            fixture.navigator.replace(EmptyAnchorScreen())
            renderAnchor(fixture.scene)
            fixture.navigator.replace(SecuritySettingsScreen())
            renderAnchor(fixture.scene)
            assertNoSecurityAnchor(fixture.scene)

            DesktopSettingsAnchorOwner.publish(result.route, result.anchorTitle)
            fixture.navigator.replace(GeneralSettingsScreen())
            renderAnchor(fixture.scene)
            assertNoSecurityAnchor(fixture.scene)
            DesktopSettingsAnchorOwner.publish(SecuritySettingsScreen(), "missing-title")
            fixture.navigator.replace(SecuritySettingsScreen())
            renderAnchor(fixture.scene)
            assertNoSecurityAnchor(fixture.scene)
        }
    }

    @Test
    fun `unavailable protected backend disables configuration without changing preferences`() {
        val preferences = preferences(enabled = false, delay = 5)
        val backend = MemoryCredentialBackend().apply {
            failure = PlatformCredentialUnavailableException(OperatingSystem.WINDOWS)
        }
        val controller = controller(preferences, backend)

        assertEquals(SecurityBackendCapability.Unavailable, controller.state.value.backendCapability)
        assertFalse(controller.state.value.canConfigure)
        assertEquals(SecuritySettingsFeedback.BackendUnavailable, controller.state.value.feedback)
        assertEquals(
            AuthenticationResult.Unavailable,
            controller.enable("new secret".toCharArray(), "new secret".toCharArray()),
        )
        assertFalse(preferences.useAuthenticator().get())
        assertEquals(null, backend.secret)
    }

    @Test
    fun `enable requires matching confirmation and successful credential save before preference`() {
        val preferences = preferences(enabled = false, delay = 0)
        val backend = MemoryCredentialBackend()
        val controller = controller(preferences, backend)
        val mismatch = "different".toCharArray()
        val candidate = "candidate".toCharArray()

        assertEquals(AuthenticationResult.Failed, controller.enable(candidate, mismatch))
        assertTrue(candidate.all { it == '\u0000' })
        assertTrue(mismatch.all { it == '\u0000' })
        assertFalse(preferences.useAuthenticator().get())

        backend.saveFailuresRemaining = 1
        assertEquals(
            AuthenticationResult.Error,
            controller.enable("new secret".toCharArray(), "new secret".toCharArray()),
        )
        assertFalse(preferences.useAuthenticator().get())

        val recoveredController = controller(preferences, backend)
        assertEquals(
            AuthenticationResult.Success,
            recoveredController.enable("saved secret".toCharArray(), "saved secret".toCharArray()),
        )
        assertTrue(preferences.useAuthenticator().get())
        assertEquals("saved secret", backend.secret?.concatToString())
    }

    @Test
    fun `disable and delay changes require current passphrase while cancel preserves old values`() {
        val preferences = preferences(enabled = true, delay = 5)
        val backend = MemoryCredentialBackend("current".toCharArray())
        val controller = controller(preferences, backend)

        assertEquals(AuthenticationResult.Cancelled, controller.changeDelay(0, null))
        assertEquals(5, preferences.lockAppAfter().get())
        assertEquals(AuthenticationResult.Failed, controller.disable("wrong".toCharArray()))
        assertTrue(preferences.useAuthenticator().get())
        assertEquals("current", backend.secret?.concatToString())
        assertEquals(AuthenticationResult.Failed, controller.changeDelay(0, "wrong".toCharArray()))
        assertEquals(5, preferences.lockAppAfter().get())

        backend.failNextDelete = true
        assertEquals(AuthenticationResult.Error, controller.disable("current".toCharArray()))
        assertTrue(preferences.useAuthenticator().get())
        assertEquals("current", backend.secret?.concatToString())

        val recoveredController = controller(preferences, backend)
        assertEquals(AuthenticationResult.Success, recoveredController.changeDelay(0, "current".toCharArray()))
        assertEquals(0, preferences.lockAppAfter().get())
        assertEquals(AuthenticationResult.Success, recoveredController.disable("current".toCharArray()))
        assertFalse(preferences.useAuthenticator().get())
        assertEquals(null, backend.secret)
    }

    @Test
    fun `disable deletes credential before disabled commit and passphrase change never disables`() {
        val disableEvents = mutableListOf<String>()
        val disablePersistence = FailingSecuritySettingsPersistence(true, 0, disableEvents)
        val disableBackend = MemoryCredentialBackend("current".toCharArray(), disableEvents)
        assertEquals(
            AuthenticationResult.Success,
            controller(disablePersistence, disableBackend).disable("current".toCharArray()),
        )
        assertEquals(listOf("credential:delete", "preference:false"), disableEvents)

        val changeEvents = mutableListOf<String>()
        val changePersistence = FailingSecuritySettingsPersistence(true, 0, changeEvents)
        val changeBackend = MemoryCredentialBackend("old".toCharArray(), changeEvents)
        assertEquals(
            AuthenticationResult.Success,
            controller(changePersistence, changeBackend).changePassphrase(
                "old".toCharArray(),
                "new".toCharArray(),
                "new".toCharArray(),
            ),
        )
        assertTrue(changePersistence.enabled)
        assertEquals(listOf("credential:save"), changeEvents)
    }

    @Test
    fun `passphrase change rejects mismatch and rolls back failed replacement`() {
        val preferences = preferences(enabled = true, delay = 0)
        val backend = MemoryCredentialBackend("old secret".toCharArray())
        val verifier = DesktopPassphraseVerifier(DesktopCredentialStore(backend))
        val controller = SecuritySettingsController(preferences, verifier)
        assertEquals(0, backend.saveCalls)
        assertEquals(0, backend.deleteCalls)
        assertEquals("old secret", backend.secret?.concatToString())

        assertEquals(
            AuthenticationResult.Failed,
            controller.changePassphrase(
                "old secret".toCharArray(),
                "new secret".toCharArray(),
                "mismatch".toCharArray(),
            ),
        )
        assertEquals("old secret", backend.secret?.concatToString())

        backend.saveFailuresRemaining = 1
        assertEquals(
            AuthenticationResult.Error,
            controller.changePassphrase(
                "old secret".toCharArray(),
                "new secret".toCharArray(),
                "new secret".toCharArray(),
            ),
        )
        assertEquals("old secret", backend.secret?.concatToString())
        assertTrue(preferences.useAuthenticator().get())

        val recoveredController = SecuritySettingsController(preferences, verifier)
        assertEquals(
            AuthenticationResult.Success,
            recoveredController.changePassphrase(
                "old secret".toCharArray(),
                "new secret".toCharArray(),
                "new secret".toCharArray(),
            ),
        )
        assertEquals(AuthenticationResult.Failed, verifier.verify("old secret".toCharArray()))
        assertEquals(AuthenticationResult.Success, verifier.verify("new secret".toCharArray()))
    }

    @Test
    fun `failed passphrase rollback keeps lock enabled with restored verifier`() {
        val preferences = preferences(enabled = true, delay = 0)
        val backend = MemoryCredentialBackend("old secret".toCharArray()).apply { saveFailuresRemaining = 2 }
        val controller = controller(preferences, backend)

        assertEquals(
            AuthenticationResult.Error,
            controller.changePassphrase(
                "old secret".toCharArray(),
                "new secret".toCharArray(),
                "new secret".toCharArray(),
            ),
        )

        assertTrue(preferences.useAuthenticator().get())
        assertEquals("old secret", backend.secret?.concatToString())
        assertEquals(SecuritySettingsFeedback.BackendError, controller.state.value.feedback)
    }

    @Test
    fun `production bootstrap finalizes real lock state before test mode opens`() {
        assertBootstrapSnapshot(
            enabledAtConstruction = false,
            enabledAtStart = true,
            delayMinutes = 0,
            expectedLocked = true,
        )
        applicationState.reset()
        assertBootstrapSnapshot(
            enabledAtConstruction = true,
            enabledAtStart = false,
            delayMinutes = 5,
            expectedLocked = false,
        )
    }

    @Test
    fun `production bootstrap closes runtime and lock binding when test mode start fails`() {
        val preferences = preferences(enabled = true, delay = 0)
        val appLock = DesktopAppLock(
            preferences,
            DesktopPassphraseVerifier(DesktopCredentialStore(MemoryCredentialBackend("secret".toCharArray()))),
        )
        var stops = 0
        val service = object : DesktopRuntimeService {
            override fun start() = Unit
            override fun stop() {
                stops++
            }
        }
        val runtime = DesktopAppRuntime(service, service, service, startupCleanup = {}, appLock = appLock)

        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            bootstrapDesktopRuntime(runtime, appLock, applicationState) {
                throw IllegalStateException("test mode failed")
            }
        }

        assertFalse(runtime.isRunning)
        assertEquals(3, stops)
        assertTrue(applicationState.appLocked.value)
        preferences.useAuthenticator().set(false)
        appLock.onApplicationStarted()
        assertTrue(applicationState.appLocked.value)
    }

    @Test
    fun `production GUI close application return and headless lifecycle wait for updater cleanup`() = runBlocking {
        listOf("gui-close", "application-return", "headless").forEach { path ->
            val headless = path == "headless"
            val checkStarted = CompletableDeferred<Unit>()
            val cleanupStarted = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val terminationReached = CompletableDeferred<Unit>()
            val releaseTermination = CompletableDeferred<Unit>()
            val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = DesktopUpdateScreenModel(
                DesktopUpdateController(
                    {
                        checkStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                cleanupStarted.complete(Unit)
                                releaseCleanup.await()
                            }
                        }
                    },
                    { release, _ -> ManualOnly(release.releaseLink) },
                    { _, _ -> InstallManualOnly },
                    { _, _ -> InstallCancelled },
                ),
                parentScope,
            )
            val preferences = preferences(enabled = false, delay = 0)
            val appLock = DesktopAppLock(
                preferences,
                DesktopPassphraseVerifier(DesktopCredentialStore(MemoryCredentialBackend("secret".toCharArray()))),
            )
            val service = object : DesktopRuntimeService {
                override fun start() = Unit
                override fun stop() = Unit
            }
            val runtime = DesktopAppRuntime(service, service, service, startupCleanup = {}, scope = parentScope, updateScreenModel = model)
            assertTrue(model.intent(DesktopUpdateIntent.CHECK))
            withTimeout(1_000) { checkStarted.await() }
            val closing = async(Dispatchers.Default) {
                runProductionOwnerLifecycle(
                    testArgs = TestArguments(testMode = headless, headless = headless),
                    runtime = runtime,
                    appLock = appLock,
                    testState = applicationState,
                    startTestMode = {},
                    awaitTestModeTermination = {
                        terminationReached.complete(Unit)
                        runBlocking { releaseTermination.await() }
                    },
                    stopTestMode = {},
                    runApplication = { closeAndJoin -> if (path == "gui-close") closeAndJoin() },
                )
                true
            }
            try {
                if (headless) {
                    withTimeout(1_000) { terminationReached.await() }
                    assertFalse(closing.isCompleted)
                    releaseTermination.complete(Unit)
                }
                withTimeout(1_000) { cleanupStarted.await() }
                assertFalse(closing.isCompleted)
            } finally {
                releaseTermination.complete(Unit)
                releaseCleanup.complete(Unit)
            }
            assertTrue(withTimeout(1_000) { closing.await() })
        }
    }

    @Test
    fun `production owner lifecycle waits for cleanup before propagating application failure`() = runBlocking {
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = DesktopUpdateScreenModel(
            DesktopUpdateController(
                {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleanupStarted.complete(Unit)
                            releaseCleanup.await()
                        }
                    }
                },
                { release, _ -> ManualOnly(release.releaseLink) },
                { _, _ -> InstallManualOnly },
                { _, _ -> InstallCancelled },
            ),
            parentScope,
        )
        val appLock = DesktopAppLock(
            preferences(enabled = false, delay = 0),
            DesktopPassphraseVerifier(DesktopCredentialStore(MemoryCredentialBackend("secret".toCharArray()))),
        )
        val service = object : DesktopRuntimeService {
            override fun start() = Unit
            override fun stop() = Unit
        }
        val failure = IllegalStateException("application failed")
        val closing = async(Dispatchers.Default) {
            org.junit.jupiter.api.assertThrows<IllegalStateException> {
                runBlocking {
                    runProductionOwnerLifecycle(
                        testArgs = TestArguments(testMode = false, headless = false),
                        runtime = DesktopAppRuntime(service, service, service, startupCleanup = {}, scope = parentScope, updateScreenModel = model),
                        appLock = appLock,
                        testState = applicationState,
                        startTestMode = {},
                        awaitTestModeTermination = {},
                        stopTestMode = {},
                        runApplication = { throw failure },
                    )
                }
            }
        }
        try {
            withTimeout(1_000) { cleanupStarted.await() }
            assertFalse(closing.isCompleted)
        } finally {
            releaseCleanup.complete(Unit)
        }
        closing.await()
    }

    @Test
    fun `production lifecycle preserves stage failures and cleanup suppression`() = runBlocking {
        val appLock = DesktopAppLock(
            preferences(enabled = false, delay = 0),
            DesktopPassphraseVerifier(DesktopCredentialStore(MemoryCredentialBackend("secret".toCharArray()))),
        )
        listOf("runtime", "test-start", "test-await", "application").forEach { stage ->
            val primary = IllegalStateException(stage)
            val cleanup = IllegalArgumentException("cleanup-$stage")
            var stopCalls = 0
            val service = object : DesktopRuntimeService {
                override fun start() { if (stage == "runtime") throw primary }
                override fun stop() = Unit
            }
            val runtime = DesktopAppRuntime(
                service,
                service,
                service,
                startupCleanup = {},
                closeUpdater = { throw cleanup },
                awaitUpdater = {},
            )
            val testMode = stage.startsWith("test-")
            val thrown = runCatching {
                runProductionOwnerLifecycle(
                    TestArguments(testMode = testMode, headless = stage == "test-await"),
                    runtime,
                    appLock,
                    applicationState,
                    startTestMode = { if (stage == "test-start") throw primary },
                    awaitTestModeTermination = { if (stage == "test-await") throw primary },
                    stopTestMode = { stopCalls++ },
                    runApplication = { if (stage == "application") throw primary },
                )
            }.exceptionOrNull()
            assertSame(primary, thrown, stage)
            assertEquals(listOf(cleanup), thrown!!.suppressed.toList(), stage)
            assertEquals(if (testMode) 1 else 0, stopCalls, stage)
        }

        listOf("normal", "test-stop", "gui-close", "close", "join").forEach { stage ->
            val stageFailure = IllegalStateException(stage)
            var closeCalls = 0
            val service = object : DesktopRuntimeService {
                override fun start() = Unit
                override fun stop() = Unit
            }
            val runtime = DesktopAppRuntime(
                service,
                service,
                service,
                startupCleanup = {},
                closeUpdater = { if (stage in setOf("gui-close", "close") && ++closeCalls == 1) throw stageFailure },
                awaitUpdater = { if (stage == "join") throw stageFailure },
            )
            val thrown = runCatching {
                runProductionOwnerLifecycle(
                    TestArguments(testMode = stage == "test-stop", headless = stage == "test-stop"),
                    runtime,
                    appLock,
                    applicationState,
                    startTestMode = {},
                    awaitTestModeTermination = {},
                    stopTestMode = { if (stage == "test-stop") throw stageFailure },
                    runApplication = { closeAndJoin -> if (stage == "gui-close") closeAndJoin() },
                )
            }.exceptionOrNull()
            if (stage == "normal") assertEquals(null, thrown) else assertSame(stageFailure, thrown, stage)
        }
    }

    @Test
    fun `enable rolls credential and preference back when enabling preference write throws`() {
        val persistence = FailingSecuritySettingsPersistence(enabled = false, delayMinutes = 5).apply {
            failEnabledValue = true
        }
        val backend = MemoryCredentialBackend()
        val controller = controller(persistence, backend)
        val candidate = "new secret".toCharArray()
        val confirmation = "new secret".toCharArray()

        assertEquals(AuthenticationResult.Error, controller.enable(candidate, confirmation))

        assertFalse(persistence.enabled)
        assertEquals(null, backend.secret)
        assertEquals(SecuritySettingsFeedback.BackendError, controller.state.value.feedback)
        assertTrue(candidate.all { it == '\u0000' })
        assertTrue(confirmation.all { it == '\u0000' })
    }

    @Test
    fun `disable restores enabled preference when disabling preference write throws`() {
        val persistence = FailingSecuritySettingsPersistence(enabled = true, delayMinutes = 5).apply {
            failEnabledValue = false
        }
        val backend = MemoryCredentialBackend("current".toCharArray())
        val controller = controller(persistence, backend)
        val current = "current".toCharArray()

        assertEquals(AuthenticationResult.Error, controller.disable(current))

        assertTrue(persistence.enabled)
        assertEquals("current", backend.secret?.concatToString())
        assertEquals(SecuritySettingsFeedback.BackendError, controller.state.value.feedback)
        assertTrue(current.all { it == '\u0000' })
    }

    @Test
    fun `delay restores previous value when preference write throws`() {
        val persistence = FailingSecuritySettingsPersistence(enabled = true, delayMinutes = 5).apply {
            failDelayWrite = true
        }
        val backend = MemoryCredentialBackend("current".toCharArray())
        val controller = controller(persistence, backend)
        val current = "current".toCharArray()

        assertEquals(AuthenticationResult.Error, controller.changeDelay(0, current))

        assertEquals(5, persistence.delayMinutes)
        assertEquals("current", backend.secret?.concatToString())
        assertEquals(SecuritySettingsFeedback.BackendError, controller.state.value.feedback)
        assertTrue(current.all { it == '\u0000' })
    }

    @Test
    fun `passphrase change does not rewrite enabled preference`() {
        val persistence = FailingSecuritySettingsPersistence(enabled = true, delayMinutes = 0).apply {
            failEnabledValue = true
        }
        val backend = MemoryCredentialBackend("old secret".toCharArray())
        val controller = controller(persistence, backend)
        val current = "old secret".toCharArray()
        val replacement = "new secret".toCharArray()
        val confirmation = "new secret".toCharArray()

        assertEquals(
            AuthenticationResult.Success,
            controller.changePassphrase(current, replacement, confirmation),
        )

        assertTrue(persistence.enabled)
        assertEquals("new secret", backend.secret?.concatToString())
        assertEquals(SecuritySettingsFeedback.Saved, controller.state.value.feedback)
        assertTrue(current.all { it == '\u0000' })
        assertTrue(replacement.all { it == '\u0000' })
        assertTrue(confirmation.all { it == '\u0000' })
    }

    @Test
    fun `malformed replacement keeps lock enabled and clears controller inputs`() {
        val persistence = FailingSecuritySettingsPersistence(enabled = true, delayMinutes = 0)
        val root = Preferences.userRoot().node("/mihon-test/security/${UUID.randomUUID()}")
        preferenceNodes += root
        val platformBackend = PlatformCredentialBackend(
            OperatingSystem.WINDOWS,
            object : CommandRunner {
                override fun run(arguments: List<String>, stdin: CharArray?) = CommandResult(0, "encrypted", "")
            },
            preferencesRoot = root,
        )
        var stored = "old secret".toCharArray()
        val backend = object : CredentialBackend {
            override fun save(account: String, secret: CharArray) {
                platformBackend.save(account, secret)
                stored.fill('\u0000')
                stored = secret.copyOf()
            }

            override fun load(account: String) = stored.copyOf()

            override fun delete(account: String) {
                stored.fill('\u0000')
            }
        }
        val controller = controller(persistence, backend)
        val current = "old secret".toCharArray()
        val replacement = charArrayOf('n', 'e', 'w', '\uD800')
        val confirmation = replacement.copyOf()

        assertEquals(
            AuthenticationResult.Error,
            controller.changePassphrase(current, replacement, confirmation),
        )

        assertTrue(persistence.enabled)
        assertEquals("old secret", stored.concatToString())
        assertEquals(SecuritySettingsFeedback.BackendError, controller.state.value.feedback)
        assertTrue(current.all { it == '\u0000' })
        assertTrue(replacement.all { it == '\u0000' })
        assertTrue(confirmation.all { it == '\u0000' })
    }

    @Test
    fun `double rollback failure converges to disabled structured error`() {
        val persistence = FailingSecuritySettingsPersistence(enabled = false, delayMinutes = 0).apply {
            enabledFailuresRemaining = 2
        }
        val backend = MemoryCredentialBackend().apply { deleteFailuresAfterWriteRemaining = 1 }
        val controller = controller(persistence, backend)

        assertEquals(
            AuthenticationResult.Error,
            controller.enable("new secret".toCharArray(), "new secret".toCharArray()),
        )

        assertFalse(persistence.enabled)
        assertEquals(null, backend.secret)
        assertFalse(controller.state.value.enabled)
        assertEquals(SecuritySettingsFeedback.BackendError, controller.state.value.feedback)
    }

    @Test
    fun `locked root never constructs protected content and only successful unlock restores it`() = runBlocking {
        val preferences = preferences(enabled = true, delay = 0)
        val verifier = DesktopPassphraseVerifier(DesktopCredentialStore(MemoryCredentialBackend()))
        assertEquals(AuthenticationResult.Success, verifier.set("secret".toCharArray()))
        val appLock = DesktopAppLock(preferences, verifier)
        var protectedConstructions = 0
        val scene = ImageComposeScene(640, 480, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                DesktopProtectedRoot(appLock) {
                    protectedConstructions++
                    Unit
                }
            }
            scene.render()
            assertEquals(0, protectedConstructions)
            assertEquals(AuthenticationResult.Failed, appLock.authenticate("wrong".toCharArray()))
            scene.render()
            assertEquals(0, protectedConstructions)

            assertEquals(AuthenticationResult.Success, appLock.authenticate("secret".toCharArray()))
            withTimeout(1_000) {
                while (protectedConstructions == 0) {
                    scene.render()
                    yield()
                }
            }
            assertTrue(protectedConstructions > 0)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `missing credential keeps root locked and exposes profile recovery action`(@TempDir appDir: java.io.File) = runBlocking {
        val node = Preferences.userRoot().node("mihon-recovery-${UUID.randomUUID()}").also(preferenceNodes::add)
        val opened = mutableListOf<java.io.File>()
        val context = mihon.desktop.di.initDesktopDIForTest(
            appDir,
            DesktopPreferenceStore(node),
            credentialBackendFactory = { MemoryCredentialBackend() },
            profileDirectoryOpener = { opened += it; false },
        )
        val preferences = Injekt.get<SecurityPreferences>().also { it.useAuthenticator().set(true) }
        val appLock = Injekt.get<DesktopAppLock>()
        appLock.onApplicationStarted()
        var protectedConstructions = 0
        val scene = ImageComposeScene(640, 480, coroutineContext = coroutineContext) {}
        try {
            scene.setContent { DesktopProtectedRoot(appLock) { protectedConstructions++ } }
            scene.render()
            val nodes = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
            fun text(node: SemanticsNode) = if (node.config.contains(SemanticsProperties.Text)) {
                node.config[SemanticsProperties.Text].joinToString()
            } else {
                ""
            }
            assertEquals(0, protectedConstructions)
            assertTrue(nodes.any { text(it) == MR.strings.desktop_unlock_recovery_summary.localized() })
            val action = nodes.first { text(it) == MR.strings.desktop_unlock_recovery_open_profile.localized() }
                .config[SemanticsActions.OnClick]
            assertTrue(requireNotNull(action).action?.invoke() == true)
            scene.render()
            assertTrue(scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.any { text(it) == MR.strings.desktop_unlock_recovery_open_failed.localized() })
            assertEquals(listOf(appDir), opened)
            assertTrue(preferences.useAuthenticator().get())
            assertTrue(appLock.state.value.requiresUnlock)
        } finally {
            scene.close()
            context.closeAndJoin()
        }
    }

    @Test
    fun `deleted recovery profile clears the same preferences before production restart`(@TempDir appDir: java.io.File) = runBlocking {
        val node = Preferences.userRoot().node("mihon-reset-${UUID.randomUUID()}").also(preferenceNodes::add)
        val store = DesktopPreferenceStore(node)
        appDir.mkdirs()
        node.node("desktop/app").put("theme_mode", "DARK")
        node.node("desktop/reader").put("readingMode", "WEBTOON")
        var first = mihon.desktop.di.initDesktopDIForTest(appDir, store, credentialBackendFactory = { MemoryCredentialBackend() })
        Injekt.get<SecurityPreferences>().useAuthenticator().set(true)
        val locked = Injekt.get<DesktopAppLock>().also(DesktopAppLock::onApplicationStarted)
        assertTrue(locked.state.value.requiresUnlock)
        first.closeAndJoin()
        appDir.deleteRecursively()

        val second = mihon.desktop.di.initDesktopDIForTest(appDir, store, credentialBackendFactory = { MemoryCredentialBackend() })
        try {
            val freshLock = Injekt.get<DesktopAppLock>().also(DesktopAppLock::onApplicationStarted)
            var constructed = 0
            val scene = ImageComposeScene(300, 200, coroutineContext = coroutineContext) {}
            try {
                scene.setContent { DesktopProtectedRoot(freshLock) { constructed++ } }
                scene.render()
                assertTrue(appDir.isDirectory)
                assertFalse(Injekt.get<SecurityPreferences>().useAuthenticator().get())
                assertEquals(ThemeMode.SYSTEM, Injekt.get<DesktopAppPreferences>().themeMode.get())
                assertEquals(mihon.desktop.reader.ReadingMode.RTL, Injekt.get<ReaderPreferences>().readingMode)
                assertTrue(node.node("desktop/app").keys().isEmpty())
                assertTrue(node.node("desktop/reader").keys().isEmpty())
                assertTrue(constructed > 0)
            } finally { scene.close() }
        } finally { second.closeAndJoin() }
    }

    @Test
    fun `window focus listener forwards one lost and gained lifecycle event`() {
        val events = mutableListOf<String>()
        val listener = DesktopWindowFocusListener(object : DesktopAppLockLifecycle {
            override fun onApplicationStarted() {
                events += "gained"
            }
            override fun onApplicationStopped() {
                events += "lost"
            }
        })

        listener.windowLostFocus(null)
        listener.windowGainedFocus(null)

        assertEquals(listOf("lost", "gained"), events)
    }

    @Test
    fun `window focus registration installs once and removes the same listener once`() {
        val listeners = mutableListOf<java.awt.event.WindowFocusListener>()
        val lifecycle = object : DesktopAppLockLifecycle {
            override fun onApplicationStarted() = Unit
            override fun onApplicationStopped() = Unit
        }
        val registration = DesktopWindowFocusRegistration(
            lifecycle = lifecycle,
            addListener = listeners::add,
            removeListener = listeners::remove,
        )

        assertEquals(1, listeners.size)
        registration.close()
        registration.close()
        assertTrue(listeners.isEmpty())
    }

    @Test
    fun `shared desktop passphrase field exposes password semantics`() = runBlocking {
        val scene = ImageComposeScene(400, 200, coroutineContext = coroutineContext) {}
        try {
            scene.setContent { DesktopPasswordField("not-visible", {}, "Passphrase") }
            scene.render()
            assertTrue(
                scene.semanticsOwners
                    .flatMap { flatten(it.rootSemanticsNode) }
                    .any { it.config.contains(SemanticsProperties.Password) },
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun `unsupported desktop privacy integrations show info without rendering controls`() = runBlocking {
        var nativeNotificationControls = 0
        var telemetryControls = 0
        val scene = ImageComposeScene(720, 480, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                DesktopPrivacySettings(
                    capabilities = DesktopPrivacyCapabilities.production,
                    nativeNotificationControl = { nativeNotificationControls++ },
                    telemetryControls = { telemetryControls++ },
                )
            }
            scene.render()
            val nodes = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
            val visibleText = nodes
                .flatMap {
                    if (it.config.contains(SemanticsProperties.Text)) {
                        it.config[SemanticsProperties.Text]
                    } else {
                        emptyList()
                    }
                }
                .joinToString("\n") { it.text }

            assertEquals(0, nativeNotificationControls)
            assertEquals(0, telemetryControls)
            assertFalse(nodes.any { it.config.contains(SemanticsProperties.ToggleableState) })
            assertTrue(visibleText.contains(MR.strings.desktop_privacy_native_notifications_unavailable.localized()))
            assertTrue(visibleText.contains(MR.strings.desktop_privacy_telemetry_unavailable.localized()))
            assertTrue(visibleText.contains(MR.strings.desktop_privacy_widget_unavailable_updates_available.localized()))

            scene.setContent {
                DesktopPrivacySettings(
                    capabilities = DesktopPrivacyCapabilities.production.copy(
                        nativeSystemNotifications = DesktopPrivacyCapabilities.production.nativeSystemNotifications.copy(
                            support = DesktopCapabilitySupport.Supported,
                        ),
                        telemetryRuntime = DesktopPrivacyCapabilities.production.telemetryRuntime.copy(
                            support = DesktopCapabilitySupport.Supported,
                        ),
                    ),
                    nativeNotificationControl = { nativeNotificationControls++ },
                    telemetryControls = { telemetryControls++ },
                )
            }
            scene.render()
            assertEquals(1, nativeNotificationControls)
            assertEquals(1, telemetryControls)
        } finally {
            scene.close()
        }
    }

    private suspend fun securityAnchorFixture(): SecurityAnchorFixture {
        val preferences = preferences(enabled = false, delay = 0)
        val appPreferences = DesktopAppPreferences(InMemoryPreferenceStore())
        val capabilities = DesktopPrivacyCapabilities.production.copy(
            nativeSystemNotifications = DesktopPrivacyCapabilities.production.nativeSystemNotifications.copy(
                support = DesktopCapabilitySupport.Supported,
            ),
        )
        val network = mockk<DesktopNetworkHelper> {
            every { routeObservations } returns MutableStateFlow(emptyList())
            every { activeGlobalMode } returns appPreferences.globalNetworkMode.get()
            every { activeGlobalProxy } returns appPreferences.proxyRuntimeConfig()
        }
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { this@mockk.appPreferences } returns appPreferences
            every { securityPreferences } returns preferences
            every { passphraseVerifier } returns DesktopPassphraseVerifier(DesktopCredentialStore(MemoryCredentialBackend()))
            every { privacyCapabilities } returns capabilities
            every { windowPrivacyController } returns DesktopWindowPrivacyController(
                preferences,
                appPreferences,
                DesktopWindowPrivacy(),
            )
            every { networkHelper } returns network
            every { networkRoutingPort } returns network
        }
        val scene = ImageComposeScene(900, 170) {}
        lateinit var navigator: Navigator
        scene.setContent {
            CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                Navigator(EmptyAnchorScreen()) { nav -> navigator = nav; CurrentScreen() }
            }
        }
        renderAnchor(scene)
        return SecurityAnchorFixture(scene, navigator, preferences)
    }

    private suspend fun renderAnchor(scene: ImageComposeScene): Set<String> {
        repeat(6) { scene.render(); yield() }
        return anchorNodes(scene).flatMap(::anchorText).toSet()
    }

    private fun assertNoSecurityAnchor(scene: ImageComposeScene) {
        assertFalse(anchorNodes(scene, true).any { it.config.contains(DesktopSettingsAnchorHighlighted) })
        assertEquals(0f, anchorScroll(scene).value())
    }

    private fun anchorScroll(scene: ImageComposeScene) = anchorNodes(scene, true)
        .first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
        .config[SemanticsProperties.VerticalScrollAxisRange]

    private fun anchorText(node: SemanticsNode) = if (node.config.contains(SemanticsProperties.Text)) {
        node.config[SemanticsProperties.Text].map { it.text }
    } else {
        emptyList()
    }

    private fun anchorNodes(scene: ImageComposeScene, unmerged: Boolean = false) = scene.semanticsOwners.flatMap {
        flatten(if (unmerged) it.unmergedRootSemanticsNode else it.rootSemanticsNode)
    }

    private data class SecurityAnchorFixture(
        val scene: ImageComposeScene,
        val navigator: Navigator,
        val preferences: SecurityPreferences,
    ) : AutoCloseable {
        override fun close() = scene.close()
    }

    private class EmptyAnchorScreen : Screen {
        @androidx.compose.runtime.Composable
        override fun Content() = Unit
    }

    private fun preferences(enabled: Boolean, delay: Int): SecurityPreferences {
        val node = Preferences.userRoot().node("/mihon-test/security-settings/${UUID.randomUUID()}")
        preferenceNodes += node
        return SecurityPreferences(DesktopPreferenceStore(node)).apply {
            useAuthenticator().set(enabled)
            lockAppAfter().set(delay)
        }
    }

    private fun controller(preferences: SecurityPreferences, backend: CredentialBackend) =
        SecuritySettingsController(preferences, DesktopPassphraseVerifier(DesktopCredentialStore(backend)))

    private fun assertBootstrapSnapshot(
        enabledAtConstruction: Boolean,
        enabledAtStart: Boolean,
        delayMinutes: Int,
        expectedLocked: Boolean,
    ) {
        val preferences = preferences(enabled = enabledAtConstruction, delay = delayMinutes)
        val appLock = DesktopAppLock(
            preferences,
            DesktopPassphraseVerifier(DesktopCredentialStore(MemoryCredentialBackend("secret".toCharArray()))),
        )
        preferences.useAuthenticator().set(enabledAtStart)
        val service = object : DesktopRuntimeService {
            override fun start() = Unit
            override fun stop() = Unit
        }
        val runtime = DesktopAppRuntime(service, service, service, startupCleanup = {}, appLock = appLock)
        var testModeStarts = 0

        bootstrapDesktopRuntime(runtime, appLock, applicationState) {
            testModeStarts++
            assertTrue(runtime.isRunning)
            assertEquals(expectedLocked, appLock.state.value.requiresUnlock)
            assertEquals(expectedLocked, applicationState.appLocked.value)
            assertEquals(
                expectedLocked,
                Json.parseToJsonElement(currentTestStateJson()).jsonObject["appLocked"]!!.jsonPrimitive.boolean,
            )
        }.use {}

        assertEquals(1, testModeStarts)
        assertFalse(runtime.isRunning)
    }

    private fun controller(persistence: SecuritySettingsPersistence, backend: CredentialBackend) =
        SecuritySettingsController(persistence, DesktopPassphraseVerifier(DesktopCredentialStore(backend)))

    private class FailingSecuritySettingsPersistence(
        var enabled: Boolean,
        var delayMinutes: Int,
        private val events: MutableList<String>? = null,
    ) : SecuritySettingsPersistence {
        var failEnabledValue: Boolean? = null
        var enabledFailuresRemaining = 0
        var failDelayWrite = false

        override fun readEnabled() = enabled

        override fun writeEnabled(enabled: Boolean) {
            events?.add("preference:$enabled")
            this.enabled = enabled
            if (failEnabledValue == enabled) {
                failEnabledValue = null
                throw IllegalStateException("enabled write failed after persistence")
            }
            if (enabledFailuresRemaining > 0) {
                enabledFailuresRemaining--
                throw IllegalStateException("enabled write failed after persistence")
            }
        }

        override fun readDelayMinutes() = delayMinutes

        override fun writeDelayMinutes(delayMinutes: Int) {
            this.delayMinutes = delayMinutes
            if (failDelayWrite) {
                failDelayWrite = false
                throw IllegalStateException("delay write failed after persistence")
            }
        }
    }

    private class MemoryCredentialBackend(
        initial: CharArray? = null,
        private val events: MutableList<String>? = null,
    ) : CredentialBackend {
        var secret = initial?.copyOf()
        var failure: RuntimeException? = null
        var saveFailuresRemaining = 0
        var failNextDelete = false
        var deleteFailuresAfterWriteRemaining = 0
        var saveCalls = 0
        var deleteCalls = 0

        override fun save(account: String, secret: CharArray) {
            events?.add("credential:save")
            failure?.let { throw it }
            saveCalls++
            this.secret?.fill('\u0000')
            this.secret = secret.copyOf()
            if (saveFailuresRemaining > 0) {
                saveFailuresRemaining--
                throw IllegalStateException("save failed after write")
            }
        }

        override fun load(account: String): CharArray? {
            failure?.let { throw it }
            return secret?.copyOf()
        }

        override fun delete(account: String) {
            events?.add("credential:delete")
            failure?.let { throw it }
            deleteCalls++
            if (failNextDelete) {
                failNextDelete = false
                throw IllegalStateException("delete failed")
            }
            secret?.fill('\u0000')
            secret = null
            if (deleteFailuresAfterWriteRemaining > 0) {
                deleteFailuresAfterWriteRemaining--
                throw IllegalStateException("delete failed after write")
            }
        }
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
