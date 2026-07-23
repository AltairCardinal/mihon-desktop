package mihon.desktop.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.network.DesktopCookieJar
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.privacy.DesktopPrivacyCapabilities
import mihon.desktop.privacy.DesktopWindowPrivacy
import mihon.desktop.privacy.DesktopWindowPrivacyController
import mihon.desktop.security.DesktopPassphraseVerifier
import mihon.desktop.settings.DesktopAppPreferences
import mihon.domain.security.AuthenticationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.i18n.MR
import java.util.UUID
import java.util.prefs.Preferences

@OptIn(ExperimentalComposeUiApi::class)
@Isolated
class DesktopSettingsSecurityAdvancedAccessibilityTest {
    @Test
    fun `Security controls expose one labeled role state disabled and protected fields`() = runBlocking {
        val fixture = securityFixture(enabled = true, delay = 5)
        try {
            withScene(SecuritySettingsScreen(), fixture.dependencies, height = 1_400) { scene ->
                assertToggle(
                    scene,
                    MR.strings.desktop_security_lock_enabled.localized(),
                    Role.Switch,
                    ToggleableState.On,
                    disabled = false,
                )
                assertSelectedButton(
                    scene,
                    MR.strings.desktop_security_delay_five_minutes.localized(),
                    selected = true,
                    disabled = true,
                )
                val secureMode = fixture.preferences.secureScreen().get()
                assertSelectedButton(scene, secureModeLabel(secureMode), selected = true, disabled = true)

                click(scene, MR.strings.desktop_security_change_passphrase.localized())
                render(scene)
                val fields = nodes(scene, true).filter { it.config.contains(SemanticsActions.SetText) }
                assertEquals(3, fields.size)
                assertTrue(fields.all { it.config.contains(SemanticsProperties.Password) })
                assertKeyCloses(scene, MR.strings.action_cancel.localized(), Key.Enter) {
                    nodes(scene, true).any { it.config.contains(SemanticsProperties.Password) }
                }
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `Advanced fields and dangerous confirmation expose honest keyboard semantics`() = runBlocking {
        val fixture = advancedFixture()
        withScene(
            AdvancedSettingsScreen(),
            fixture.dependencies,
            advancedActions = fixture.actions,
            height = 1_800,
        ) { scene ->
            assertToggle(
                scene,
                MR.strings.desktop_settings_cloudflare_solver_title.localized(),
                Role.Switch,
                ToggleableState.Off,
                disabled = false,
            )
            assertPasswordField(scene, MR.strings.desktop_challenge_manual_cookie.localized(), expected = true)
            assertPasswordField(scene, MR.strings.desktop_settings_cloudflare_domain.localized(), expected = false)
            assertPasswordField(scene, MR.strings.desktop_settings_cloudflare_solver_url.localized(), expected = false)

            click(scene, MR.strings.pref_clear_cookies.localized())
            render(scene)
            assertKeyCloses(scene, MR.strings.desktop_settings_clear_cookies_confirm.localized()) {
                MR.strings.desktop_settings_clear_cookies_warning.localized() in sceneText(scene)
            }
            verify(exactly = 1) { fixture.cookieJar.clear() }

            click(scene, MR.strings.desktop_advanced_clear_network_cache.localized())
            render(scene)
            assertKeyCloses(scene, MR.strings.action_cancel.localized(), Key.NumPadEnter) {
                MR.strings.desktop_advanced_clear_network_cache_warning.localized() in sceneText(scene)
            }
        }
    }

    private fun assertToggle(
        scene: ImageComposeScene,
        label: String,
        role: Role,
        state: ToggleableState,
        disabled: Boolean,
    ) {
        val branch = branch(scene, label, role)
        assertEquals(1, flatten(branch).count { it.config.contains(SemanticsActions.OnClick) }, label)
        assertEquals(state, branch.config[SemanticsProperties.ToggleableState])
        assertEquals(disabled, branch.config.contains(SemanticsProperties.Disabled))
        assertTrue(branch.config.contains(SemanticsProperties.StateDescription))
        assertTrue(branch.config.contains(SemanticsActions.RequestFocus))
    }

    private fun assertSelectedButton(scene: ImageComposeScene, label: String, selected: Boolean, disabled: Boolean) {
        val branch = branch(scene, label, Role.Button)
        assertEquals(selected, branch.config[SemanticsProperties.Selected])
        assertEquals(disabled, branch.config.contains(SemanticsProperties.Disabled))
        assertTrue(branch.config.contains(SemanticsProperties.StateDescription))
        assertEquals(1, flatten(branch).count { it.config.contains(SemanticsActions.OnClick) }, label)
    }

    private fun assertPasswordField(scene: ImageComposeScene, label: String, expected: Boolean) {
        val field = nodes(scene, true)
            .filter { it.config.contains(SemanticsActions.SetText) }
            .single { label in subtreeText(it) }
        assertEquals(expected, field.config.contains(SemanticsProperties.Password), label)
    }

    private suspend fun assertKeyCloses(
        scene: ImageComposeScene,
        label: String,
        key: Key = Key.Spacebar,
        isOpen: () -> Boolean,
    ) {
        val action = action(scene, label)
        assertTrue(requireNotNull(action.config[SemanticsActions.RequestFocus].action).invoke())
        render(scene)
        scene.sendKeyEvent(composeKeyEvent(key, KeyEventType.KeyUp))
        render(scene)
        assertTrue(isOpen())
        scene.sendKeyEvent(composeKeyEvent(key, KeyEventType.KeyDown))
        render(scene)
        assertFalse(isOpen())
        scene.sendKeyEvent(composeKeyEvent(key, KeyEventType.KeyUp))
        render(scene)
        assertFalse(isOpen())
    }

    private suspend fun withScene(
        screen: Screen,
        dependencies: DesktopUiDependencies,
        advancedActions: AdvancedSettingsPlatformActions? = null,
        height: Int = 1_000,
        block: suspend (ImageComposeScene) -> Unit,
    ) {
        val scene = ImageComposeScene(1_000, height, coroutineContext = kotlinx.coroutines.currentCoroutineContext()) {}
        try {
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalAdvancedSettingsPlatformActions provides (advancedActions ?: mockk(relaxed = true)),
                ) {
                    Navigator(screen) { CurrentScreen() }
                }
            }
            render(scene)
            block(scene)
        } finally {
            scene.close()
        }
    }

    private fun securityFixture(
        enabled: Boolean,
        delay: Int,
    ): SecurityFixture {
        val node = Preferences.userRoot().node("mihon-task18a-${UUID.randomUUID()}")
        val preferences = SecurityPreferences(DesktopPreferenceStore(node)).apply {
            useAuthenticator().set(enabled)
            lockAppAfter().set(delay)
        }
        val appPreferences = DesktopAppPreferences(InMemoryPreferenceStore())
        val verifier = mockk<DesktopPassphraseVerifier>(relaxed = true) {
            every { probe() } returns AuthenticationResult.Success
        }
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { securityPreferences } returns preferences
            every { passphraseVerifier } returns verifier
            every { privacyCapabilities } returns DesktopPrivacyCapabilities.production
            every { this@mockk.appPreferences } returns appPreferences
            every { windowPrivacyController } returns DesktopWindowPrivacyController(
                preferences,
                appPreferences,
                DesktopWindowPrivacy(),
            )
        }
        return SecurityFixture(dependencies, preferences, node)
    }

    private fun advancedFixture(): AdvancedFixture {
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore())
        val cookieJar = mockk<DesktopCookieJar>(relaxed = true)
        val networkHelper = mockk<DesktopNetworkHelper>(relaxed = true) {
            every { this@mockk.cookieJar } returns cookieJar
        }
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns preferences
            every { this@mockk.networkHelper } returns networkHelper
        }
        val actions = mockk<AdvancedSettingsPlatformActions> {
            coEvery { loadNetworkCacheSize() } returns "0 B"
            coEvery { openCrashLogFolder() } returns false
        }
        return AdvancedFixture(dependencies, actions, cookieJar)
    }

    private fun branch(scene: ImageComposeScene, label: String, role: Role) = nodes(scene, true)
        .filter { it.config.contains(SemanticsProperties.Role) && it.config[SemanticsProperties.Role] == role }
        .single { label in subtreeText(it) }

    private fun action(scene: ImageComposeScene, label: String) = nodes(scene, true)
        .filter { it.config.contains(SemanticsActions.OnClick) && it.config.contains(SemanticsActions.RequestFocus) }
        .single { label in subtreeText(it) }

    private fun click(scene: ImageComposeScene, label: String) {
        val action = nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && label in subtreeText(it) }
        assertTrue(requireNotNull(action.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun secureModeLabel(mode: SecurityPreferences.SecureScreenMode) = when (mode) {
        SecurityPreferences.SecureScreenMode.ALWAYS -> MR.strings.lock_always.localized()
        SecurityPreferences.SecureScreenMode.INCOGNITO -> MR.strings.pref_incognito_mode.localized()
        SecurityPreferences.SecureScreenMode.NEVER -> MR.strings.lock_never.localized()
    }

    private fun composeKeyEvent(key: Key, type: KeyEventType): androidx.compose.ui.input.key.KeyEvent {
        val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
        val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            .getMethod(if (type == KeyEventType.KeyDown) "access\$getKeyDown\$cp" else "access\$getKeyUp\$cp")
            .invoke(null)
        val factory = events.declaredMethods.single { it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default") }
        val native = factory.invoke(null, key.keyCode, eventType, 0, false, false, false, false, null)
        return androidx.compose.ui.input.key.KeyEvent(native)
    }

    private suspend fun render(scene: ImageComposeScene) = repeat(8) {
        scene.render()
        yield()
    }

    private fun sceneText(scene: ImageComposeScene) = nodes(scene).flatMap(::subtreeText).joinToString()
    private fun subtreeText(node: SemanticsNode) = flatten(node).flatMap {
        if (it.config.contains(SemanticsProperties.Text)) it.config[SemanticsProperties.Text].map { text -> text.text } else emptyList()
    }
    private fun nodes(scene: ImageComposeScene, unmerged: Boolean = false) =
        scene.semanticsOwners.flatMap { flatten(if (unmerged) it.unmergedRootSemanticsNode else it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private data class SecurityFixture(
        val dependencies: DesktopUiDependencies,
        val preferences: SecurityPreferences,
        val node: Preferences,
    ) : AutoCloseable {
        override fun close() = node.removeNode()
    }

    private data class AdvancedFixture(
        val dependencies: DesktopUiDependencies,
        val actions: AdvancedSettingsPlatformActions,
        val cookieJar: DesktopCookieJar,
    )
}
