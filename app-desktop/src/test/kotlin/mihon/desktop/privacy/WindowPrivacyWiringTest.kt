package mihon.desktop.privacy

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import java.awt.Window
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.runBlocking
import mihon.desktop.BindDesktopWindowLifecycle
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.security.DesktopAppLockLifecycle
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.ui.settings.DesktopSecureScreenSettings
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Isolated
@OptIn(ExperimentalComposeUiApi::class)
class WindowPrivacyWiringTest {
    private val preferenceNodes = mutableListOf<Preferences>()

    @AfterEach
    fun cleanUpPreferences() {
        preferenceNodes.forEach { runCatching { it.removeNode() } }
        preferenceNodes.clear()
    }

    @Test
    fun `main production composable seam follows policy and clears before detach`() = runBlocking {
        val (security, app) = preferences(SecurityPreferences.SecureScreenMode.INCOGNITO, false)
        val bridge = RecordingBridge()
        val privacy = DesktopWindowPrivacy(bridge)
        val controller = DesktopWindowPrivacyController(security, app, privacy)

        val scene = ImageComposeScene(64, 64, coroutineContext = coroutineContext) {}
        scene.setContent {
            BindDesktopWindowLifecycle(null, NoOpAppLockLifecycle, controller)
        }
        scene.render()
        try {
            assertEquals(listOf(WDA_NONE), bridge.setCalls)
            assertFalse(controller.state.value.shouldProtect)
            assertEquals(false, controller.state.value.appliedProtected)
            app.incognitoMode.set(true)
            assertEquals(listOf(WDA_NONE, WDA_EXCLUDEFROMCAPTURE), bridge.setCalls)
            assertTrue(controller.state.value.shouldProtect)
            assertEquals(true, controller.state.value.appliedProtected)
            security.secureScreen().set(SecurityPreferences.SecureScreenMode.ALWAYS)
            assertEquals(2, bridge.setCalls.size)
            security.secureScreen().set(SecurityPreferences.SecureScreenMode.NEVER)
            assertEquals(listOf(WDA_NONE, WDA_EXCLUDEFROMCAPTURE, WDA_NONE), bridge.setCalls)
            assertFalse(controller.state.value.shouldProtect)
            assertEquals(false, controller.state.value.appliedProtected)
        } finally {
            scene.close()
        }

        assertEquals(listOf(WDA_NONE, WDA_EXCLUDEFROMCAPTURE, WDA_NONE, WDA_NONE), bridge.setCalls)
        assertEquals("clear", bridge.events[bridge.events.lastIndex - 1])
        assertEquals("detach", bridge.events.last())
        val callsAfterDetach = bridge.setCalls.size
        privacy.apply(protected = true)
        assertEquals(callsAfterDetach, bridge.setCalls.size)
    }

    @Test
    fun `state distinguishes requested and applied protection for every policy combination`() {
        val cases = listOf(
            Triple(SecurityPreferences.SecureScreenMode.ALWAYS, false, true),
            Triple(SecurityPreferences.SecureScreenMode.ALWAYS, true, true),
            Triple(SecurityPreferences.SecureScreenMode.INCOGNITO, false, false),
            Triple(SecurityPreferences.SecureScreenMode.INCOGNITO, true, true),
            Triple(SecurityPreferences.SecureScreenMode.NEVER, false, false),
            Triple(SecurityPreferences.SecureScreenMode.NEVER, true, false),
        )
        cases.forEach { (mode, incognito, expected) ->
            val controller = controller(mode, incognito, RecordingBridge())
            controller.applyPolicy(mode, incognito)
            assertEquals(expected, controller.state.value.shouldProtect)
            assertEquals(expected, controller.state.value.appliedProtected)
        }
    }

    @Test
    fun `settings persist only supported or monitor limited results and roll back failures`() {
        val supported = controller(SecurityPreferences.SecureScreenMode.INCOGNITO, false, RecordingBridge())
        assertEquals(
            DesktopWindowPrivacyResult.Supported,
            supported.changeMode(SecurityPreferences.SecureScreenMode.ALWAYS),
        )
        assertEquals(SecurityPreferences.SecureScreenMode.ALWAYS, supported.state.value.mode)
        assertEquals(SecurityPreferences.SecureScreenMode.ALWAYS, supported.securityPreferences.secureScreen().get())

        val limitedBridge = RecordingBridge(queryAffinity = WDA_MONITOR, failExcludeOnly = true)
        val limited = controller(SecurityPreferences.SecureScreenMode.INCOGNITO, false, limitedBridge)
        assertTrue(
            limited.changeMode(SecurityPreferences.SecureScreenMode.ALWAYS) is DesktopWindowPrivacyResult.Limited,
        )
        assertEquals(SecurityPreferences.SecureScreenMode.ALWAYS, limited.state.value.mode)
        assertEquals(SecurityPreferences.SecureScreenMode.ALWAYS, limited.securityPreferences.secureScreen().get())

        val failedBridge = RecordingBridge(setFailure = NativeAffinityCall.failed(5))
        val failed = controller(SecurityPreferences.SecureScreenMode.INCOGNITO, false, failedBridge)
        assertTrue(failed.changeMode(SecurityPreferences.SecureScreenMode.ALWAYS) is DesktopWindowPrivacyResult.Failed)
        assertEquals(SecurityPreferences.SecureScreenMode.INCOGNITO, failed.state.value.mode)
        assertEquals(SecurityPreferences.SecureScreenMode.INCOGNITO, failed.securityPreferences.secureScreen().get())

        val unsupported = controller(
            SecurityPreferences.SecureScreenMode.INCOGNITO,
            false,
            RecordingBridge(unsupportedReasonSlug = "linux_capture_affinity_unavailable"),
        )
        assertTrue(
            unsupported.changeMode(SecurityPreferences.SecureScreenMode.ALWAYS) is DesktopWindowPrivacyResult.Unsupported,
        )
        assertEquals(SecurityPreferences.SecureScreenMode.INCOGNITO, unsupported.state.value.mode)
        assertEquals(
            SecurityPreferences.SecureScreenMode.INCOGNITO,
            unsupported.securityPreferences.secureScreen().get(),
        )

        val notReady = controller(
            SecurityPreferences.SecureScreenMode.INCOGNITO,
            false,
            RecordingBridge(handle = null),
        )
        assertTrue(
            notReady.changeMode(SecurityPreferences.SecureScreenMode.ALWAYS) is DesktopWindowPrivacyResult.Failed,
        )
        assertEquals(SecurityPreferences.SecureScreenMode.INCOGNITO, notReady.securityPreferences.secureScreen().get())
    }

    @Test
    fun `security UI exposes modes and structured window feedback`() = runBlocking {
        val controller = controller(
            SecurityPreferences.SecureScreenMode.INCOGNITO,
            false,
            RecordingBridge(unsupportedReasonSlug = "linux_capture_affinity_unavailable"),
        )
        controller.changeMode(SecurityPreferences.SecureScreenMode.ALWAYS)
        val scene = ImageComposeScene(720, 420, coroutineContext = coroutineContext) {}
        try {
            scene.setContent { DesktopSecureScreenSettings(controller) }
            scene.render()
            val nodes = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
            val text = nodes.flatMap {
                if (it.config.contains(SemanticsProperties.Text)) it.config[SemanticsProperties.Text] else emptyList()
            }.joinToString("\n") { it.text }

            assertTrue(text.contains("Screen capture protection"))
            assertTrue(text.contains("not available on this platform"))
            assertTrue(
                nodes.count {
                    it.config.contains(SemanticsProperties.Role) && it.config[SemanticsProperties.Role] == Role.Button
                } >= 3,
            )
            assertTrue(nodes.count { it.config.contains(SemanticsActions.OnClick) } >= 2)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `security UI distinguishes active protection from a successful clear`() = runBlocking {
        val cleared = controller(SecurityPreferences.SecureScreenMode.NEVER, true, RecordingBridge())
        cleared.applyPolicy(SecurityPreferences.SecureScreenMode.NEVER, true)
        val clearedText = renderSettingsText(cleared)
        assertTrue(clearedText.contains("Screen capture protection is off"))
        assertFalse(clearedText.contains("setting is active"))

        val protected = controller(SecurityPreferences.SecureScreenMode.ALWAYS, false, RecordingBridge())
        protected.applyPolicy(SecurityPreferences.SecureScreenMode.ALWAYS, false)
        assertTrue(renderSettingsText(protected).contains("setting is active"))
    }

    @Test
    fun `desktop DI shares one privacy adapter and controller with real preferences`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val context = initDesktopDIForTest(tempDir, DesktopPreferenceStore())
        try {
            val privacy = Injekt.get<DesktopWindowPrivacy>()
            val controller = Injekt.get<DesktopWindowPrivacyController>()
            assertSame(privacy, controller.windowPrivacy)
            assertSame(Injekt.get<SecurityPreferences>(), controller.securityPreferences)
            assertSame(Injekt.get<DesktopAppPreferences>(), controller.appPreferences)
        } finally {
            context.closeAndJoin()
        }
    }

    private fun controller(
        mode: SecurityPreferences.SecureScreenMode,
        incognito: Boolean,
        bridge: RecordingBridge,
    ): DesktopWindowPrivacyController {
        val (security, app) = preferences(mode, incognito)
        return DesktopWindowPrivacyController(security, app, DesktopWindowPrivacy(bridge)).also { it.attach(null) }
    }

    private fun preferences(
        mode: SecurityPreferences.SecureScreenMode,
        incognito: Boolean,
    ): Pair<SecurityPreferences, DesktopAppPreferences> {
        val node = Preferences.userRoot().node("/mihon-test/window-privacy/${UUID.randomUUID()}")
        preferenceNodes += node
        val store = DesktopPreferenceStore(node)
        return SecurityPreferences(store).apply { secureScreen().set(mode) } to
            DesktopAppPreferences(store).apply { incognitoMode.set(incognito) }
    }

    private suspend fun renderSettingsText(controller: DesktopWindowPrivacyController): String {
        val scene = ImageComposeScene(720, 420, coroutineContext = kotlinx.coroutines.currentCoroutineContext()) {}
        return try {
            scene.setContent { DesktopSecureScreenSettings(controller) }
            scene.render()
            scene.semanticsOwners
                .flatMap { flatten(it.rootSemanticsNode) }
                .flatMap {
                    if (it.config.contains(SemanticsProperties.Text)) {
                        it.config[SemanticsProperties.Text]
                    } else {
                        emptyList()
                    }
                }
                .joinToString("\n") { it.text }
        } finally {
            scene.close()
        }
    }

    private data object NoOpAppLockLifecycle : DesktopAppLockLifecycle {
        override fun onApplicationStarted() = Unit
        override fun onApplicationStopped() = Unit
    }

    private class RecordingBridge(
        override val unsupportedReasonSlug: String? = null,
        private val queryAffinity: Int = WDA_EXCLUDEFROMCAPTURE,
        private val setFailure: NativeAffinityCall? = null,
        private val handle: Long? = 9L,
        private val failExcludeOnly: Boolean = false,
    ) : DesktopWindowPrivacyBridge {
        val setCalls = mutableListOf<Int>()
        val events = mutableListOf<String>()
        override fun windowHandle(window: Window?): Long? = handle
        override fun setAffinity(handle: Long, affinity: Int): NativeAffinityCall {
            setCalls += affinity
            events += if (affinity == WDA_NONE) "clear" else "apply"
            if (affinity == WDA_EXCLUDEFROMCAPTURE && failExcludeOnly) return NativeAffinityCall.failed(5)
            return setFailure.also { if (it != null) return it } ?: NativeAffinityCall.success()
        }
        override fun queryAffinity(handle: Long) = NativeAffinityQuery.success(
            if (setCalls.lastOrNull() == WDA_NONE) WDA_NONE else queryAffinity,
        )
        override fun detached(handle: Long) {
            events += "detach"
        }
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
