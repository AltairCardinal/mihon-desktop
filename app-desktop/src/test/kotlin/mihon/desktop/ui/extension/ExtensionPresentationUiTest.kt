package mihon.desktop.ui.extension

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import dev.mihon.injekt.patchInjekt
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.LocalExtensionScreenModel
import mihon.desktop.extension.DesktopAvailableExtension
import mihon.desktop.extension.DesktopAvailableSource
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.DesktopExtensionInstallStart
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.extension.InstalledExtension
import mihon.desktop.settings.DesktopAppPreferences
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.model.RepositoryCatalogFailure
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import mihon.domain.extension.presentation.ExtensionPresentationInstallStep
import mihon.domain.extension.service.ExtensionInstallState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import tachiyomi.core.common.preference.DesktopPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.prefs.Preferences

class ExtensionPresentationUiTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `details opened from the list return to the list when the installed extension disappears`() = runBlocking {
        val installed = InstalledExtension(File("removed-details.jar"), emptyList(), displayName = "Removed details")
        val installedFlow = MutableStateFlow(listOf(installed))
        val catalog = ExtensionCatalogResult(emptyList(), emptyList())
        val api = mockk<DesktopExtensionApi> {
            coEvery { refreshCatalog() } returns catalog
            every { availableExtensions(catalog) } returns emptyList()
            coEvery { loadExtensionIcon(any()) } returns null
        }
        val manager = mockk<DesktopExtensionManager>(relaxed = true)
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(api, manager, installedFlow),
            this,
            ExtensionPresentationOptions(false, setOf("en")),
        )
        val dependencies = mockk<DesktopUiDependencies> {
            every { extensionApi } returns api
            every { extensionManager } returns manager
            every { appPreferences } returns DesktopAppPreferences(
                DesktopPreferenceStore(Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")),
            )
        }
        val scene = ImageComposeScene(900, 900, coroutineContext = coroutineContext) {}
        var navigator: Navigator? = null
        try {
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalExtensionScreenModel provides { model },
                ) {
                    Navigator(ExtensionListScreen()) { current ->
                        navigator = current
                        CurrentScreen()
                    }
                }
            }
            withTimeout(5_000) {
                while (navigator?.lastItem !is ExtensionListScreen) {
                    scene.render()
                    yield()
                }
            }
            navigator!!.push(ExtensionDetailsScreen(installed.jarFile.absolutePath))
            withTimeout(5_000) {
                while (navigator?.lastItem !is ExtensionDetailsScreen) {
                    scene.render()
                    yield()
                }
            }
            scene.render()
            assertTrue(navigator?.lastItem is ExtensionDetailsScreen)
            awaitText(scene, installed.name)

            installedFlow.value = emptyList()
            withTimeout(5_000) {
                while (navigator?.lastItem !is ExtensionListScreen) {
                    scene.render()
                    yield()
                }
            }
            assertTrue(navigator?.lastItem is ExtensionListScreen)
        } finally {
            scene.close()
            model.closeAndJoin()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `first catalog failure keeps local extensions visible with retry feedback`() = runBlocking {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        val installed = InstalledExtension(File("local.jar"), emptyList(), displayName = "Local extension")
        val api = mockk<DesktopExtensionApi> {
            coEvery { refreshCatalog() } throws IllegalStateException("catalog offline")
            coEvery { loadExtensionIcon(any()) } returns null
        }
        val manager = mockk<DesktopExtensionManager>(relaxed = true)
        val dependencies = mockk<DesktopUiDependencies> {
            every { extensionApi } returns api
            every { extensionManager } returns manager
        }
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(api, manager, MutableStateFlow(listOf(installed))),
            this,
            ExtensionPresentationOptions(false, setOf("en")),
        )
        val scene = ImageComposeScene(900, 900, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    ExtensionListContent(model)
                }
            }

            awaitText(scene, installed.name)
            awaitText(scene, "catalog offline")
            assertFalse(nodes(scene).any { it.config.toString().contains(extensionListCopy().loading) })

            click(scene, MR.strings.action_retry.localized())
            withTimeout(5_000) { coVerify(atLeast = 2) { api.refreshCatalog() } }
        } finally {
            scene.close()
            model.closeAndJoin()
            Locale.setDefault(previousLocale)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `installed details render before catalog completes and missing details offer navigation back`() = runBlocking {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        val installed = InstalledExtension(File("local-details.jar"), emptyList(), displayName = "Local details")
        val catalogs = Channel<ExtensionCatalogResult>(Channel.UNLIMITED)
        val api = mockk<DesktopExtensionApi> {
            coEvery { refreshCatalog() } coAnswers { catalogs.receive() }
            coEvery { loadExtensionIcon(any()) } returns null
        }
        val manager = mockk<DesktopExtensionManager>(relaxed = true)
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(api, manager, MutableStateFlow(listOf(installed))),
            this,
            ExtensionPresentationOptions(false, setOf("en")),
        )
        val dependencies = mockk<DesktopUiDependencies> {
            every { extensionApi } returns api
            every { extensionManager } returns manager
            every { appPreferences } returns DesktopAppPreferences(
                DesktopPreferenceStore(Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")),
            )
        }
        val scene = ImageComposeScene(900, 900, coroutineContext = coroutineContext) {}
        fun mount(path: String) = scene.setContent {
            CompositionLocalProvider(
                LocalDesktopUiDependencies provides dependencies,
                LocalExtensionScreenModel provides { model },
            ) { Navigator(ExtensionDetailsScreen(path)) { CurrentScreen() } }
        }
        try {
            mount(installed.jarFile.absolutePath)
            awaitText(scene, installed.name)

            mount("missing.jar")
            awaitText(scene, "This extension is not installed")
            assertTrue(
                nodes(scene).any {
                    it.config.contains(SemanticsActions.OnClick) &&
                        it.config.toString().contains(MR.strings.action_bar_up_description.localized())
                },
            )
        } finally {
            catalogs.trySend(ExtensionCatalogResult(emptyList(), emptyList()))
            scene.close()
            model.closeAndJoin()
            Locale.setDefault(previousLocale)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `both extension screens consume the local model instead of the global singleton`() = runBlocking {
        val localState = MutableStateFlow(DesktopExtensionsState(options = ExtensionPresentationOptions(false, emptySet())))
        val localModel = mockk<ExtensionsScreenModel>(relaxed = true)
        val globalModel = mockk<ExtensionsScreenModel>(relaxed = true)
        every { localModel.state } returns localState
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true)
        val scene = ImageComposeScene(900, 900, coroutineContext = coroutineContext) {}
        val previous = Injekt
        fun mount(screen: cafe.adriel.voyager.core.screen.Screen) {
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalExtensionScreenModel provides { localModel },
                ) { Navigator(screen) { CurrentScreen() } }
            }
            scene.render()
        }
        try {
            patchInjekt()
            Injekt.addSingleton(globalModel)
            mount(ExtensionListScreen())
            verify(atLeast = 1) { localModel.state }
            verify(exactly = 0) { globalModel.state }
            clearMocks(localModel, answers = false, recordedCalls = true)
            mount(ExtensionDetailsScreen("local-model.jar"))
            verify(atLeast = 1) { localModel.state }
            verify(exactly = 0) { globalModel.state }
        } finally {
            scene.close()
            Injekt = previous
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `search input filters through model state and survives content remount`() = runBlocking {
        val alpha = InstalledExtension(File("alpha.jar"), emptyList(), displayName = "Alpha Extension")
        val beta = InstalledExtension(File("beta.jar"), emptyList(), displayName = "Beta Extension")
        val api = mockk<DesktopExtensionApi> {
            coEvery { refreshCatalog() } returns ExtensionCatalogResult(emptyList(), emptyList())
            every { availableExtensions(any()) } returns emptyList()
            coEvery { loadExtensionIcon(any()) } returns null
        }
        val manager = mockk<DesktopExtensionManager>(relaxed = true)
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(api, manager, MutableStateFlow(listOf(alpha, beta))),
            this,
            ExtensionPresentationOptions(false, setOf("en")),
        )
        val dependencies = mockk<DesktopUiDependencies> { every { extensionApi } returns api; every { extensionManager } returns manager }
        val scene = ImageComposeScene(900, 900, coroutineContext = coroutineContext) {}
        fun mount() = scene.setContent {
            CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) { ExtensionListContent(model) }
        }
        try {
            model.refresh().join()
            mount()
            scene.render()
            setText(scene, "Beta")
            assertEquals("Beta", model.state.value.searchQuery)
            awaitExtensionNames(scene, visible = beta.name, hidden = alpha.name)
            mount()
            awaitExtensionNames(scene, visible = beta.name, hidden = alpha.name)
            val input = nodes(scene).single { it.config.contains(SemanticsProperties.EditableText) }
            assertEquals(AnnotatedString("Beta"), input.config[SemanticsProperties.EditableText])
            assertFalse(nodes(scene).any { it.config.toString().contains(alpha.name) })
            model.search("Alpha")
            assertEquals("Alpha", model.state.value.searchQuery)
            awaitExtensionNames(scene, visible = alpha.name, hidden = beta.name)
        } finally {
            scene.close()
            model.closeAndJoin()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `production content renders local empty data with failure and retries`() = runBlocking {
        val candidate = extension(
            "Update extension", "pkg.visible",
            listOf(source(1, "en", "English source"), source(2, "fr", "French source")),
        )
        val available = extension(
            "Available extension", "pkg.available",
            listOf(source(4, "en", "Alpha available source"), source(5, "fr", "Beta available source"), source(6, "es", "Filtered available source")),
        )
        val firstAvailable = available.copy(name = "Stale available extension", repoUrl = "https://stale", repoFingerprint = "stale")
        val installed = InstalledExtension(
            File("pkg.visible.jar"),
            listOf(mockk { every { id } returns 3; every { name } returns "German source"; every { lang } returns "de" }),
            versionCode = 1, versionName = "1.5.0", displayName = candidate.name, language = "ja",
        )
        val failure = RepositoryCatalogFailure(RepositoryIdentity("https://failed", "Failed repository", "key"), AppError.Network())
        val partial = ExtensionCatalogResult(emptyList(), listOf(failure))
        val catalogs = Channel<ExtensionCatalogResult>(Channel.UNLIMITED)
        var refreshCalls = 0
        val api = mockk<DesktopExtensionApi> {
            coEvery { refreshCatalog() } coAnswers { refreshCalls++; catalogs.receive() }
            every { availableExtensions(any()) } answers {
                if (firstArg<ExtensionCatalogResult>() === partial) listOf(candidate, firstAvailable, available) else emptyList()
            }
            coEvery { loadExtensionIcon(any()) } returns null
        }
        val manager = mockk<DesktopExtensionManager>(relaxed = true)
        val installedFlow = MutableStateFlow<List<InstalledExtension>>(emptyList())
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(api, manager, installedFlow), this, ExtensionPresentationOptions(false, setOf("en", "fr")),
        )
        val dependencies = mockk<DesktopUiDependencies> { every { extensionApi } returns api; every { extensionManager } returns manager }
        val scene = ImageComposeScene(900, 900, coroutineContext = coroutineContext) {}
        val previous = Injekt
        try {
            patchInjekt()
            Injekt.addSingleton(model)
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) { Navigator(ExtensionListScreen()) { CurrentScreen() } }
            }
            awaitText(scene, extensionListCopy().emptyInstalled)
            withTimeout(5_000) { while (refreshCalls < 1) yield() }
            val initialRefresh = model.refresh()
            catalogs.send(ExtensionCatalogResult(emptyList(), emptyList()))
            initialRefresh.join()
            scene.render()
            assertTrue(nodes(scene).any { it.config.toString().contains(extensionListCopy().emptyInstalled) })
            installedFlow.value = listOf(installed)
            val partialRefresh = model.refresh()
            catalogs.send(partial)
            partialRefresh.join()
            val projectedAvailable = model.state.value.projection?.available.orEmpty().filter { it.operationPackageName == available.pkgName }
            assertEquals(1, projectedAvailable.size)
            assertSame(available, projectedAvailable.single().available)
            val availableUi = model.state.value.presentation?.available.orEmpty()
            assertEquals(2, availableUi.size)
            assertEquals(2, availableUi.map { it.presentation.packageName }.toSet().size)
            assertTrue(availableUi.all { it.available === available })
            scene.render()
            click(scene, extensionListCopy().available)
            scene.render()
            val rendered = nodes(scene).joinToString { it.config.toString() }
            assertSame(failure, model.state.value.projection?.failures?.single())
            listOf(candidate.name, "Alpha available source", "Beta available source", "${MR.strings.ext_update_all.localized()} (1)", MR.strings.ext_update.localized(), "Failed repository").forEach {
                assertTrue(rendered.contains(it), "missing production extension UI: $it")
            }
            assertEquals(1, nodes(scene).count { it.config.contains(SemanticsProperties.Text) && it.config[SemanticsProperties.Text].any { text -> text.text == "1" } })
            assertEquals(2, nodes(scene).count { it.config.contains(SemanticsProperties.ContentDescription) && it.config[SemanticsProperties.ContentDescription].any { it.startsWith("${MR.strings.action_install.localized()} ") } })
            val updateError = AppError.Storage(IllegalStateException("update failed"))
            coEvery { api.beginInstall(candidate, manager) } returnsMany listOf(
                DesktopExtensionInstallStart.Started(flowOf(ExtensionInstallState.Failed(updateError))),
                DesktopExtensionInstallStart.Started(flow { emit(ExtensionInstallState.Preparing); awaitCancellation() }),
                trust("update-all-trust"),
                DesktopExtensionInstallStart.Started(flowOf(ExtensionInstallState.Failed(AppError.Cancelled))),
            )
            val actionError = AppError.Network(IllegalStateException("offline"))
            coEvery { api.beginInstall(available, manager) } returnsMany listOf(
                DesktopExtensionInstallStart.Started(flowOf(ExtensionInstallState.Failed(actionError))),
                DesktopExtensionInstallStart.Started(flow { emit(ExtensionInstallState.Preparing); awaitCancellation() }),
                trust("trust-confirm"),
                trust("trust-dismiss"),
            )
            every { api.confirmTrust("trust-confirm", manager) } returns flowOf(ExtensionInstallState.Installed(mockk()))
            every { api.discardTrust("trust-dismiss") } returns true
            every { api.discardTrust("update-all-trust") } returns true
            click(scene, "${MR.strings.ext_update.localized()} ${candidate.name}")
            withTimeout(5_000) { coVerify(exactly = 1) { api.beginInstall(candidate, manager) } }
            withTimeout(5_000) { model.state.first { it.installErrors[candidate.pkgName] === updateError } }
            val preserved = model.state.value.projection?.installed?.single { it.operationPackageName == candidate.pkgName }
            assertSame(installed, preserved?.installed)
            assertEquals(installed.versionCode, preserved?.installed?.versionCode)
            scene.render()
            assertTrue(nodes(scene).any { it.config.toString().contains(extensionInstallErrorCopy(candidate.name, updateError)) })
            click(scene, "${MR.strings.action_retry.localized()} ${candidate.name}")
            withTimeout(5_000) {
                model.state.first { it.actions.installSteps[candidate.pkgName] == ExtensionPresentationInstallStep.Downloading }
            }
            scene.render()
            assertTrue(nodes(scene).any { it.config.toString().contains(MR.strings.ext_downloading.localized()) })
            click(scene, "${MR.strings.action_cancel.localized()} ${candidate.name}")
            withTimeout(5_000) { model.state.first { candidate.pkgName !in it.actions.installSteps } }
            scene.render()
            click(scene, "${MR.strings.action_install.localized()} Alpha available source")
            withTimeout(5_000) { model.state.first { available.pkgName in it.installErrors } }
            scene.render()
            val errorFeedback = nodes(scene).joinToString { it.config.toString() }
            assertTrue(errorFeedback.contains(candidate.name) && errorFeedback.contains(extensionInstallErrorCopy("Alpha available source", actionError)))
            click(scene, "${MR.strings.action_retry.localized()} Alpha available source")
            withTimeout(5_000) {
                model.state.first { it.actions.installSteps[available.pkgName] == ExtensionPresentationInstallStep.Downloading }
            }
            scene.render()
            assertTrue(nodes(scene).any { it.config.toString().contains(MR.strings.ext_downloading.localized()) })
            click(scene, "${MR.strings.action_cancel.localized()} Alpha available source")
            withTimeout(5_000) { model.state.first { available.pkgName !in it.actions.installSteps } }
            scene.render()
            click(scene, "${MR.strings.action_install.localized()} Alpha available source")
            withTimeout(5_000) { model.state.first { it.pendingTrust?.request?.requestId == "trust-confirm" } }
            scene.render()
            click(scene, MR.strings.ext_trust.localized())
            withTimeout(5_000) { model.state.first { it.pendingTrust == null } }
            verify(exactly = 1) { api.confirmTrust("trust-confirm", manager) }
            scene.render()
            click(scene, "${MR.strings.action_install.localized()} Alpha available source")
            withTimeout(5_000) { model.state.first { it.pendingTrust?.request?.requestId == "trust-dismiss" } }
            scene.render()
            click(scene, "${MR.strings.action_cancel.localized()} ${MR.strings.untrusted_extension.localized()}")
            withTimeout(5_000) { model.state.first { it.pendingTrust == null && available.pkgName !in it.actions.installSteps } }
            verify(exactly = 1) { api.discardTrust("trust-dismiss") }
            scene.render()
            click(scene, "${MR.strings.ext_update_all.localized()} (1)")
            withTimeout(5_000) { model.state.first { it.pendingTrust?.request?.requestId == "update-all-trust" } }
            scene.render()
            val successSummary = MR.strings.desktop_extension_updated_message.localized(java.util.Locale.getDefault(), 1, 1)
            assertFalse(nodes(scene).any { it.config.toString().contains(successSummary) })
            click(scene, "${MR.strings.action_cancel.localized()} ${MR.strings.untrusted_extension.localized()}")
            withTimeout(5_000) { model.state.first { it.pendingTrust == null && candidate.pkgName !in it.actions.installSteps } }
            scene.render()
            assertTrue(nodes(scene).any { it.config.toString().contains("${MR.strings.ext_update.localized()} ${candidate.name}") })
            click(scene, "${MR.strings.ext_update_all.localized()} (1)")
            withTimeout(5_000) { coVerify(exactly = 4) { api.beginInstall(candidate, manager) } }
            withTimeout(5_000) { model.state.first { candidate.pkgName !in it.actions.installSteps } }
            scene.render()
            assertFalse(nodes(scene).any { it.config.toString().contains(successSummary) })
            assertTrue(nodes(scene).any { it.config.toString().contains("${MR.strings.ext_update.localized()} ${candidate.name}") })
            click(scene, MR.strings.desktop_extension_filter_by_language.localized())
            scene.render()
            assertTrue(nodes(scene).any { it.config.toString().contains("French (fr)") })
            toggle(scene, 2)
            toggle(scene, 0)
            click(scene, MR.strings.action_apply.localized())
            assertEquals(setOf("en", "es", "fr"), model.state.value.options.enabledLanguages)
            assertTrue(model.state.value.options.showNsfw)
            click(scene, MR.strings.desktop_extension_filter_by_language.localized())
            scene.render()
            click(scene, MR.strings.action_reset.localized())
            assertEquals(setOf("de", "en", "es", "fr", "ja"), model.state.value.options.enabledLanguages)
            click(scene, MR.strings.action_retry.localized())
            catalogs.send(partial)
            scene.render()
            withTimeout(5_000) { while (refreshCalls < 3) yield() }
            assertEquals(3, refreshCalls)
        } finally {
            scene.close()
            model.closeAndJoin()
            Injekt = previous
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `installed actions route through screen model and failures stay visible`() = runBlocking {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val extension = InstalledExtension(File("pkg.routed.jar"), emptyList(), displayName = "Routed extension")
        val api = mockk<DesktopExtensionApi> {
            coEvery { refreshCatalog() } returns ExtensionCatalogResult(emptyList(), emptyList())
            every { availableExtensions(any()) } returns emptyList()
            coEvery { loadExtensionIcon(any()) } returns null
        }
        val authorityManager = mockk<DesktopExtensionManager>()
        every { authorityManager.removeExtensionWithMeta(extension) } returns false
        every { authorityManager.reloadAll() } throws IllegalStateException("reload failed")
        val bypassManager = mockk<DesktopExtensionManager>(relaxed = true)
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(api, authorityManager, MutableStateFlow(listOf(extension))),
            this,
            ExtensionPresentationOptions(false, setOf("en")),
        )
        val dependencies = mockk<DesktopUiDependencies> {
            every { extensionApi } returns api
            every { extensionManager } returns bypassManager
        }
        val scene = ImageComposeScene(900, 900, coroutineContext = coroutineContext) {}
        try {
            model.refresh().join()
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    ExtensionListContent(model)
                }
            }
            awaitText(scene, extension.name)

            click(scene, MR.strings.ext_uninstall.localized())
            scene.render()
            click(scene, MR.strings.ext_uninstall.localized(), last = true)
            awaitText(scene, MR.strings.desktop_extension_uninstall_failed.localized())
            verify(exactly = 1) { authorityManager.removeExtensionWithMeta(match { it === extension }) }
            verify(exactly = 0) { bypassManager.removeExtensionWithMeta(any()) }
            val dismiss = nodes(scene).first { it.config.contains(SemanticsActions.Dismiss) }
            assertTrue(requireNotNull(dismiss.config[SemanticsActions.Dismiss].action).invoke())
            withTimeout(5_000) {
                while (nodes(scene).any { it.config.contains(SemanticsActions.Dismiss) }) {
                    scene.render()
                    yield()
                }
            }

            click(scene, MR.strings.desktop_extension_reload_installed.localized())
            awaitText(
                scene,
                MR.strings.desktop_extension_reload_failed.localized(Locale.SIMPLIFIED_CHINESE, "reload failed"),
            )
            verify(exactly = 1) { authorityManager.reloadAll() }
            verify(exactly = 0) { bypassManager.reloadAll() }
        } finally {
            scene.close()
            model.closeAndJoin()
            Locale.setDefault(previousLocale)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun click(scene: ImageComposeScene, label: String, last: Boolean = false) {
        val matches = nodes(scene).filter {
            it.config.contains(SemanticsActions.OnClick) &&
                (it.config.contains(SemanticsProperties.Text) && it.config[SemanticsProperties.Text].any { text -> text.text == label } ||
                    it.config.contains(SemanticsProperties.ContentDescription) && it.config[SemanticsProperties.ContentDescription].contains(label))
        }
        val node = if (last) matches.last() else matches.single()
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }
    private fun toggle(scene: ImageComposeScene, index: Int) {
        val node = nodes(scene).filter { it.config.contains(SemanticsProperties.ToggleableState) }[index]
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }
    private fun setText(scene: ImageComposeScene, value: String) {
        val input = nodes(scene).single { it.config.contains(SemanticsActions.SetText) }
        assertTrue(requireNotNull(input.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
    }

    private suspend fun awaitExtensionNames(scene: ImageComposeScene, visible: String, hidden: String) = withTimeout(5_000) {
        while (true) {
            scene.render()
            val content = nodes(scene).map { it.config.toString() }
            if (content.any { visible in it } && content.none { hidden in it }) return@withTimeout
            yield()
        }
    }

    private suspend fun awaitText(scene: ImageComposeScene, expected: String) = withTimeout(5_000) {
        while (true) {
            scene.render()
            if (nodes(scene).any { expected in it.config.toString() }) return@withTimeout
            yield()
        }
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun source(id: Long, lang: String, name: String) = DesktopAvailableSource(id, lang, name, "https://$id")
    private fun extension(name: String, pkg: String, sources: List<DesktopAvailableSource>) = DesktopAvailableExtension(
        name, pkg, "1.4.0", 2, 1.5, "en", false, "https://repo/$pkg.jar", "", "https://repo", sources = sources,
    )
    private fun trust(id: String) = DesktopExtensionInstallStart.TrustRequired(id, "old", "new", emptySet(), mockk())
}
