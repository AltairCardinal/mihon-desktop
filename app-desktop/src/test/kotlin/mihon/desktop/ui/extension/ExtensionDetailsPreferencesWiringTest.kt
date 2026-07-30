package mihon.desktop.ui.extension

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import dev.mihon.injekt.patchInjekt
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.PreferenceScreen
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.preference.EditTextPreference
import eu.kanade.tachiyomi.source.preference.SwitchPreference
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.DesktopExtensionLoader
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.extension.ExtensionOrigin
import mihon.desktop.extension.InstalledExtension
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.network.DesktopPluginNetworkSupport
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.DesktopSourceManager
import mihon.desktop.ui.browse.SourceBrowseScreen
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.prefs.Preferences

class ExtensionDetailsPreferencesWiringTest {
    @TempDir
    lateinit var tempDir: File

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `details waits for authoritative removal and keeps desktop capabilities`() = runBlocking {
        val jar = tempDir.resolve("pkg.details.jar").also { it.writeText("desktop-extension") }
        val source = mockk<HttpSource>(relaxed = true, moreInterfaces = arrayOf(ConfigurableSource::class)) {
            every { id } returns 42L
            every { name } returns "Configurable web source"
            every { lang } returns "en"
            every { baseUrl } returns "https://source.example/path"
        }
        val installed = InstalledExtension(
            jarFile = jar,
            sources = listOf(source),
            displayName = "Details extension",
            versionName = "1.0.0",
            artifactSha256 = "sha-details",
            repoName = "Details repository",
            repoUrl = "https://repo.example/details",
            repoFingerprint = "fingerprint-details",
            origin = ExtensionOrigin.COMPILED_JAR,
        )
        val converted = installed.copy(
            jarFile = tempDir.resolve("pkg.converted.jar").also { it.writeText("converted") },
            displayName = "Converted extension",
            origin = ExtensionOrigin.CONVERTED_APK,
        )
        val installedFlow = MutableStateFlow<List<InstalledExtension>>(emptyList())
        val catalog = ExtensionCatalogResult(emptyList(), emptyList())
        val refreshEntered = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val api = mockk<DesktopExtensionApi> {
            coEvery { refreshCatalog() } coAnswers {
                refreshEntered.complete(Unit)
                releaseRefresh.await()
                catalog
            }
            every { availableExtensions(catalog) } returns emptyList()
            coEvery { loadExtensionIcon(any()) } returns null
        }
        val manager = mockk<DesktopExtensionManager> {
            every { removeExtensionWithMeta(installed) } returnsMany listOf(false, true)
        }
        val preferences = DesktopAppPreferences(
            DesktopPreferenceStore(Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")),
        )
        val sourceExtensionManager = DesktopExtensionManager(
            loader = DesktopExtensionLoader(tempDir.resolve("source-manager-extensions")),
        )
        val sourceManager = DesktopSourceManager(sourceExtensionManager, preferences, emptyList())
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(
                api,
                manager,
                installedFlow,
                sourcePreferences = DesktopExtensionSourcePreferenceAdapter(preferences),
            ),
            this,
            ExtensionPresentationOptions(false, setOf("en")),
        )
        val network = mockk<DesktopNetworkHelper> {
            every { clearCookies(listOf(source)) } returns 2
            every { pluginNetworkSupport(any()) } returns DesktopPluginNetworkSupport.FULL
            every { pluginEffectiveRoute(any()) } returns "DIRECT"
        }
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { extensionApi } returns api
            every { extensionManager } returns manager
            every { appPreferences } returns preferences
            every { this@mockk.sourceManager } returns sourceManager
            every { networkHelper } returns network
            every { networkRoutingPort } returns network
            every { extensionCookiePort } returns network
        }
        val scene = ImageComposeScene(900, 1400, coroutineContext = coroutineContext) {}
        val openedDirectories = mutableListOf<File>()
        val openedUrls = mutableListOf<String>()
        var directoryResult = true
        val platformActions = ExtensionDetailsPlatformActions(
            openDirectory = { openedDirectories += it; directoryResult },
            openUrl = { openedUrls += it; Result.success(Unit) },
        )
        val previous = Injekt
        var navigator: Navigator? = null
        try {
            patchInjekt()
            Injekt.addSingleton(model)
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalExtensionDetailsPlatformActions provides platformActions,
                ) {
                    Navigator(DetailsRoot) { current ->
                        navigator = current
                        LaunchedEffect(Unit) { current.push(ExtensionDetailsScreen(jar.absolutePath)) }
                        CurrentScreen()
                    }
                }
            }
            renderUntil(scene, "mounted refresh") { refreshEntered.isCompleted }
            assertTrue(navigator?.lastItem is ExtensionDetailsScreen)
            assertTrue(nodes(scene).any { it.config.toString().contains(MR.strings.loading.localized()) })
            releaseRefresh.complete(Unit)
            renderUntil(scene, "missing pop") { navigator?.lastItem === DetailsRoot }
            installedFlow.value = listOf(installed, converted)
            renderUntil(scene, "authoritative install") { model.state.value.projection?.installed?.any { it.installed === installed } == true }
            navigator?.push(ExtensionDetailsScreen(jar.absolutePath))
            renderUntil(scene, "details content") {
                navigator?.lastItem is ExtensionDetailsScreen &&
                    nodes(scene).any { it.config.toString().contains("Details extension") }
            }
            val rendered = nodes(scene).joinToString { it.config.toString() }
            listOf("Details extension", jar.absolutePath, MR.strings.desktop_extension_metadata_size.localized(Locale.getDefault(), jar.length()), MR.strings.desktop_extension_metadata_sha256.localized(Locale.getDefault(), "sha-details"), MR.strings.desktop_extension_metadata_repository.localized(Locale.getDefault(), "Details repository"), MR.strings.desktop_extension_metadata_fingerprint.localized(Locale.getDefault(), "fingerprint-details"), MR.strings.desktop_extension_origin_native.localized())
                .forEach { assertTrue(rendered.contains(it), "missing Desktop capability: $it") }
            click(scene, MR.strings.desktop_extension_open_folder.localized())
            assertEquals(jar.parentFile, openedDirectories.single())
            click(scene, MR.strings.action_open_repo.localized())
            assertEquals(installed.repoUrl, openedUrls.single())
            click(scene, MR.strings.desktop_extension_open_source_website.localized(Locale.getDefault(), "Configurable web source"))
            assertEquals("https://source.example/path", openedUrls.last())
            assertEquals(ToggleableState.On, sourceToggleState(scene))
            preferences.disabledSources.set(setOf("42"))
            renderUntil(scene, "external source disable") { sourceToggleState(scene) == ToggleableState.Off }
            preferences.disabledSources.set(emptySet())
            renderUntil(scene, "external source enable") { sourceToggleState(scene) == ToggleableState.On }
            click(scene, MR.strings.action_disable_all.localized())
            assertEquals(setOf("42"), preferences.disabledSources.get())
            renderUntil(scene, "disable all state") { sourceToggleState(scene) == ToggleableState.Off }
            click(scene, MR.strings.action_enable_all.localized())
            assertEquals(emptySet<String>(), preferences.disabledSources.get())
            renderUntil(scene, "enable all state") { sourceToggleState(scene) == ToggleableState.On }
            toggle(scene, 0)
            assertEquals(setOf("42"), preferences.disabledSources.get())
            click(scene, MR.strings.desktop_extension_source_settings.localized(Locale.getDefault(), "Configurable web source"))
            assertTrue((navigator?.lastItem as SourcePreferencesScreen).let { it.sourceId == 42L && it.sourceName == "Configurable web source" })
            navigator?.pop()
            scene.render()
            click(scene, MR.strings.browse.localized())
            assertTrue((navigator?.lastItem as SourceBrowseScreen).sourceId == 42L)
            navigator?.pop()
            scene.render()
            click(scene, MR.strings.desktop_extension_incognito_for.localized(Locale.getDefault(), installed.pkgName))
            assertTrue(installed.pkgName in preferences.incognitoExtensions.get())
            click(scene, MR.strings.pref_clear_cookies.localized())
            verify { network.clearCookies(listOf(source)) }
            renderUntil(scene, "cookie feedback") { nodes(scene).any { it.config.toString().contains(MR.strings.desktop_extension_cookies_cleared.localized(Locale.getDefault(), 2)) } }
            dismissSnackbar(scene)
            scrollToIndex(scene, 5)
            val networkRendered = nodes(scene).joinToString { it.config.toString() }
            listOf(
                MR.strings.desktop_plugin_network_title.localized(),
                MR.strings.desktop_plugin_network_inherit.localized(),
                MR.strings.desktop_network_mode_system.localized(),
                MR.strings.desktop_network_mode_direct.localized(),
                MR.strings.desktop_network_mode_manual.localized(),
                MR.strings.desktop_plugin_network_effective.localized(Locale.getDefault(), "DIRECT"),
                MR.strings.desktop_plugin_domains_declared.localized(),
                MR.strings.desktop_plugin_domains_observed.localized(),
                MR.strings.desktop_plugin_domains_copy_mihomo_suffix.localized(),
                "source.example",
            ).forEach { assertTrue(networkRendered.contains(it), "missing plugin network capability: $it") }
            scrollToIndex(scene, 1)
            directoryResult = false
            click(scene, MR.strings.desktop_extension_open_folder.localized())
            renderUntil(scene, "directory failure feedback") { nodes(scene).any { it.config.toString().contains(MR.strings.desktop_extension_open_folder_failed.localized()) } }
            dismissSnackbar(scene)
            navigator?.push(ExtensionDetailsScreen(converted.jarFile.absolutePath))
            renderUntil(scene, "converted origin") { nodes(scene).any { it.config.toString().contains(MR.strings.desktop_extension_origin_converted.localized()) } }
            navigator?.pop()
            scene.render()

            scrollToIndex(scene, 6)
            uninstall(scene)
            assertTrue(navigator?.lastItem is ExtensionDetailsScreen)
            renderUntil(scene, "uninstall failure") {
                nodes(scene).any { it.config.toString().contains(MR.strings.desktop_extension_uninstall_failed.localized()) }
            }

            uninstall(scene)
            assertTrue(navigator?.lastItem is ExtensionDetailsScreen)
            installedFlow.value = emptyList()
            renderUntil(scene, "uninstall removal") { navigator?.lastItem === DetailsRoot }
            verify(exactly = 2) { manager.removeExtensionWithMeta(installed) }
            assertSame(DetailsRoot, navigator?.lastItem)
        } finally {
            scene.close()
            model.closeAndJoin()
            sourceExtensionManager.close()
            Injekt = previous
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `source preference availability states stay distinct and content persists`() = runBlocking {
        val failure = IllegalStateException("setup exploded")
        val linkageFailure = NoClassDefFoundError("androidx/preference/Missing")
        val item = SwitchPreference("feature_enabled", "Enable feature")
        val failing = mockk<ConfigurableSource>(relaxed = true) {
            every { setupPreferenceScreen(any()) } throws failure
        }
        val empty = mockk<ConfigurableSource>(relaxed = true)
        val content = mockk<ConfigurableSource>(relaxed = true) {
            every { setupPreferenceScreen(any()) } answers { firstArg<PreferenceScreen>().addPreference(item) }
        }
        val linkageFailing = mockk<ConfigurableSource>(relaxed = true) {
            every { setupPreferenceScreen(any()) } throws linkageFailure
        }
        assertTrue(resolveSourcePreferencesState(null) is SourcePreferencesState.Missing)
        assertTrue(resolveSourcePreferencesState(mockk<Source>(relaxed = true)) is SourcePreferencesState.NonConfigurable)
        assertSame(failure, (resolveSourcePreferencesState(failing) as SourcePreferencesState.SetupFailure).error)
        assertTrue(resolveSourcePreferencesState(empty) is SourcePreferencesState.Empty)
        assertTrue(resolveSourcePreferencesState(content) { throw IllegalArgumentException("context unavailable") } is SourcePreferencesState.Content)
        assertTrue(resolveSourcePreferencesState(content) { throw linkageFailure } is SourcePreferencesState.Content)
        assertSame(linkageFailure, (resolveSourcePreferencesState(linkageFailing) as SourcePreferencesState.SetupFailure).error)
        val baseCopy = listOf(
            MR.strings.desktop_source_preferences_missing.localized(Locale.ENGLISH),
            MR.strings.desktop_source_preferences_non_configurable.localized(Locale.ENGLISH),
            MR.strings.desktop_source_preferences_setup_failed.localized(Locale.ENGLISH, failure.message.orEmpty()),
            MR.strings.desktop_source_preferences_empty.localized(Locale.ENGLISH),
        )
        val chineseCopy = listOf(
            MR.strings.desktop_source_preferences_missing.localized(Locale.SIMPLIFIED_CHINESE),
            MR.strings.desktop_source_preferences_non_configurable.localized(Locale.SIMPLIFIED_CHINESE),
            MR.strings.desktop_source_preferences_setup_failed.localized(Locale.SIMPLIFIED_CHINESE, failure.message.orEmpty()),
            MR.strings.desktop_source_preferences_empty.localized(Locale.SIMPLIFIED_CHINESE),
        )
        assertTrue(baseCopy.zip(chineseCopy).all { (base, chinese) -> base.isNotBlank() && chinese.isNotBlank() && base != chinese })

        val sourceId = 987654321L
        val stored = Preferences.userRoot().node("/mihon/source_$sourceId")
        stored.remove(item.key)
        val states = listOf(
            SourcePreferencesState.Missing to MR.strings.desktop_source_preferences_missing.localized(),
            SourcePreferencesState.NonConfigurable to MR.strings.desktop_source_preferences_non_configurable.localized(),
            SourcePreferencesState.SetupFailure(failure) to MR.strings.desktop_source_preferences_setup_failed.localized(Locale.getDefault(), failure.message.orEmpty()),
            SourcePreferencesState.SetupFailure(linkageFailure) to MR.strings.desktop_source_preferences_setup_failed.localized(Locale.getDefault(), linkageFailure.message.orEmpty()),
            SourcePreferencesState.Empty to MR.strings.desktop_source_preferences_empty.localized(),
            SourcePreferencesState.Content(listOf(item)) to item.title,
        )
        val manager = mockk<DesktopExtensionManager>(relaxed = true)
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) { every { extensionManager } returns manager }
        states.forEach { (state, feedback) ->
            val scene = ImageComposeScene(700, 600, coroutineContext = coroutineContext) {}
            try {
                scene.setContent {
                    CompositionLocalProvider(
                        LocalDesktopUiDependencies provides dependencies,
                        LocalSourcePreferencesStateResolver provides { state },
                    ) { Navigator(SourcePreferencesScreen(sourceId, "Source settings")) { CurrentScreen() } }
                }
                scene.render()
                assertTrue(nodes(scene).any { it.config.toString().contains(feedback) }, "missing state feedback: $state")
                if (state is SourcePreferencesState.Content) {
                    toggle(scene, 0)
                    assertTrue(stored.getBoolean(item.key, false))
                }
            } finally {
                scene.close()
            }
        }
        stored.remove(item.key)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `edit text preference dialog preserves its Android dialog title`() = runBlocking {
        val sourceId = 987654322L
        val item = EditTextPreference(
            key = "pref_scanlator_blacklist",
            title = "Scanlator Blacklist",
        ).apply { dialogTitle = "Exclude groups" }
        val stored = Preferences.userRoot().node("/mihon/source_$sourceId")
        stored.remove(item.key)
        val manager = mockk<DesktopExtensionManager>(relaxed = true)
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { extensionManager } returns manager
        }
        val scene = ImageComposeScene(700, 600, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalSourcePreferencesStateResolver provides { SourcePreferencesState.Content(listOf(item)) },
                ) { Navigator(SourcePreferencesScreen(sourceId, "Source settings")) { CurrentScreen() } }
            }
            scene.render()
            click(scene, item.title)
            scene.render()
            assertTrue(
                nodes(scene).any {
                    it.config.contains(SemanticsProperties.Text) &&
                        it.config[SemanticsProperties.Text].any { text -> text.text == "Exclude groups" }
                },
                "EditText dialog did not expose its Android dialog title",
            )
        } finally {
            scene.close()
            stored.remove(item.key)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `edit text preference validator blocks invalid values and exposes feedback`() = runBlocking {
        val sourceId = 987654323L
        val item = EditTextPreference(
            key = "validated_text",
            title = "Validated text",
        ).apply {
            validator = { value -> VALIDATION_ERROR.takeUnless { value == "valid" } }
        }
        val stored = Preferences.userRoot().node("/mihon/source_$sourceId")
        stored.put(item.key, "stored-invalid")
        val manager = mockk<DesktopExtensionManager>(relaxed = true)
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { extensionManager } returns manager
        }
        val scene = ImageComposeScene(700, 600, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalSourcePreferencesStateResolver provides { SourcePreferencesState.Content(listOf(item)) },
                ) { Navigator(SourcePreferencesScreen(sourceId, "Source settings")) { CurrentScreen() } }
            }
            scene.render()
            click(scene, item.title)
            scene.render()
            assertValidationError(scene)
            disabledConfirm(scene)

            replaceText(scene, "invalid")
            scene.render()
            assertValidationError(scene)
            val invalidConfirm = disabledConfirm(scene)
            if (invalidConfirm.config.contains(SemanticsActions.OnClick)) {
                invalidConfirm.config[SemanticsActions.OnClick].action?.invoke()
            }
            scene.render()
            assertEquals("stored-invalid", stored.get(item.key, null))

            replaceText(scene, "valid")
            scene.render()
            assertFalse(nodes(scene).any { it.config.toString().contains(VALIDATION_ERROR) })
            val validConfirm = buttonNode(scene, "OK")
            assertFalse(validConfirm.config.contains(SemanticsProperties.Disabled))
            assertTrue(requireNotNull(validConfirm.config[SemanticsActions.OnClick].action).invoke())
            scene.render()
            assertEquals("valid", stored.get(item.key, null))
        } finally {
            scene.close()
            stored.remove(item.key)
        }
    }

    private fun assertValidationError(scene: ImageComposeScene) {
        assertTrue(
            nodes(scene).any { it.config.toString().contains(VALIDATION_ERROR) },
            "validator feedback must be visible",
        )
    }

    private fun replaceText(scene: ImageComposeScene, value: String) {
        val input = nodes(scene).single { it.config.contains(SemanticsActions.SetText) }
        assertTrue(requireNotNull(input.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
    }

    private fun buttonNode(scene: ImageComposeScene, label: String): SemanticsNode = nodes(scene).last {
        it.config.contains(SemanticsProperties.Text) &&
            it.config[SemanticsProperties.Text].any { text -> text.text == label } &&
            (it.config.contains(SemanticsActions.OnClick) || it.config.contains(SemanticsProperties.Disabled))
    }

    private fun disabledConfirm(scene: ImageComposeScene): SemanticsNode = buttonNode(scene, "OK").also { confirm ->
        assertTrue(
            confirm.config.contains(SemanticsProperties.Disabled) ||
                !confirm.config.contains(SemanticsActions.OnClick),
            "invalid confirmation must be disabled or expose no click action",
        )
    }

    private suspend fun uninstall(scene: ImageComposeScene) {
        click(scene, MR.strings.ext_uninstall.localized())
        scene.render()
        click(scene, MR.strings.ext_uninstall.localized(), last = true)
        scene.render()
    }

    private suspend fun renderUntil(scene: ImageComposeScene, label: String = "condition", condition: () -> Boolean) {
        repeat(50) {
            scene.render()
            if (condition()) return
            yield()
        }
        assertTrue(condition(), label)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun click(scene: ImageComposeScene, label: String, last: Boolean = false) {
        val matches = nodes(scene).filter {
            it.config.contains(SemanticsActions.OnClick) &&
                (it.config.contains(SemanticsProperties.Text) && it.config[SemanticsProperties.Text].any { text -> text.text == label } ||
                    it.config.contains(SemanticsProperties.ContentDescription) && it.config[SemanticsProperties.ContentDescription].contains(label))
        }
        val node = if (last) matches.last() else matches.first()
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun toggle(scene: ImageComposeScene, index: Int) {
        val node = nodes(scene).filter { it.config.contains(SemanticsProperties.ToggleableState) }[index]
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun scrollToIndex(scene: ImageComposeScene, index: Int) {
        val list = nodes(scene).first { it.config.contains(SemanticsActions.ScrollToIndex) }
        assertTrue(requireNotNull(list.config[SemanticsActions.ScrollToIndex].action).invoke(index))
        scene.render()
    }

    private fun sourceToggleState(scene: ImageComposeScene): ToggleableState =
        nodes(scene).first { it.config.contains(SemanticsProperties.ToggleableState) }
            .config[SemanticsProperties.ToggleableState]

    private suspend fun dismissSnackbar(scene: ImageComposeScene) {
        nodes(scene).first { it.config.contains(SemanticsActions.Dismiss) }.config[SemanticsActions.Dismiss]?.action?.invoke()
        renderUntil(scene, "snackbar dismissed") { nodes(scene).none { it.config.contains(SemanticsActions.Dismiss) } }
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private data object DetailsRoot : Screen {
        @Composable
        override fun Content() = Text("Details root")
    }

    private companion object {
        const val VALIDATION_ERROR = "Only valid is accepted"
    }
}
