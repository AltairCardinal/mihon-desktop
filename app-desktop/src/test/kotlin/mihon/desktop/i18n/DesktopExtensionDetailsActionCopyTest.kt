package mihon.desktop.i18n

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.Navigator
import dev.mihon.injekt.patchInjekt
import eu.kanade.tachiyomi.network.DesktopCookieJar
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.extension.InstalledExtension
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.ui.extension.DesktopExtensionPresentationPort
import mihon.desktop.ui.extension.ExtensionDetailsPlatformActions
import mihon.desktop.ui.extension.ExtensionDetailsScreen
import mihon.desktop.ui.extension.ExtensionsScreenModel
import mihon.desktop.ui.extension.LocalExtensionDetailsPlatformActions
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.prefs.Preferences

@OptIn(ExperimentalComposeUiApi::class)
class DesktopExtensionDetailsActionCopyTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `extension detail actions and feedback follow locale`() = runBlocking {
        val jar = directory.resolve("actions.jar").also { it.writeText("desktop-extension") }
        val source = mockk<HttpSource>(relaxed = true) { every { baseUrl } returns "https://source.example/path" }
        val extension = InstalledExtension(
            jar, listOf(source), displayName = "Raw Extension", versionName = "1.0",
            repoUrl = "https://repo.example/details",
        )
        val api = mockk<DesktopExtensionApi> {
            coEvery { refreshCatalog() } returns ExtensionCatalogResult(emptyList(), emptyList())
            every { availableExtensions(any()) } returns emptyList()
            coEvery { loadExtensionIcon(any()) } returns null
        }
        val manager = mockk<DesktopExtensionManager> { every { removeExtensionWithMeta(extension) } returns false }
        val model = ExtensionsScreenModel(DesktopExtensionPresentationPort(api, manager, MutableStateFlow(listOf(extension))), this, ExtensionPresentationOptions(false, setOf("en")))
        val cookies = mockk<DesktopCookieJar> { every { clearDomains(setOf("source.example")) } returns 2 }
        val network = mockk<DesktopNetworkHelper> { every { cookieJar } returns cookies }
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { extensionApi } returns api
            every { networkHelper } returns network
            every { sourceManager } returns mockk<SourceManager>()
            every { appPreferences } returns DesktopAppPreferences(DesktopPreferenceStore(Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")))
        }
        val actions = ExtensionDetailsPlatformActions(openDirectory = { false }, openUrl = { Result.success(Unit) })
        val scene = ImageComposeScene(900, 2000, coroutineContext = coroutineContext) {}
        val previousInjekt = Injekt
        val previousLocale = Locale.getDefault()
        try {
            patchInjekt()
            Injekt.addSingleton(model)
            withTimeout(5_000) { model.refresh().join() }
            withTimeout(5_000) { model.state.first { it.projection?.installed?.singleOrNull()?.installed === extension } }
            listOf(Locale.forLanguageTag("zh-CN"), Locale.US).forEach { locale ->
                Locale.setDefault(locale)
                scene.setContent {
                    CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies, LocalExtensionDetailsPlatformActions provides actions) {
                        Navigator(ExtensionDetailsScreen(jar.absolutePath)) { ExtensionDetailsScreen(jar.absolutePath).Content() }
                    }
                }
                awaitText(scene, extension.name)
                listOf(MR.strings.desktop_extension_open_folder.localized(locale), MR.strings.action_open_repo.localized(locale),
                    MR.strings.pref_clear_cookies.localized(locale), MR.strings.ext_uninstall.localized(locale))
                    .forEach { assertTrue(it in texts(scene), "Missing '$it': ${texts(scene)}") }

                click(scene, MR.strings.desktop_extension_open_folder.localized(locale))
                awaitText(scene, MR.strings.desktop_extension_open_folder_failed.localized(locale))
                dismissSnackbar(scene)
                click(scene, MR.strings.pref_clear_cookies.localized(locale))
                awaitText(scene, MR.strings.desktop_extension_cookies_cleared.localized(locale, 2))
                dismissSnackbar(scene)

                click(scene, MR.strings.ext_uninstall.localized(locale))
                scene.render()
                listOf(MR.strings.ext_confirm_remove.localized(locale), MR.strings.desktop_extension_remove_metadata_confirmation.localized(locale, extension.name),
                    MR.strings.ext_uninstall.localized(locale), MR.strings.action_cancel.localized(locale))
                    .forEach { assertTrue(it in texts(scene), "Missing dialog '$it': ${texts(scene)}") }
                click(scene, MR.strings.ext_uninstall.localized(locale), last = true)
                awaitText(scene, MR.strings.desktop_extension_uninstall_failed.localized(locale))
                dismissSnackbar(scene)
            }
        } finally {
            scene.close()
            model.closeAndJoin()
            Injekt = previousInjekt
            Locale.setDefault(previousLocale)
        }
    }

    private suspend fun awaitText(scene: ImageComposeScene, text: String) = withTimeout(5_000) {
        while (text !in texts(scene)) { scene.render(); yield() }
    }

    private fun click(scene: ImageComposeScene, label: String, last: Boolean = false) {
        val matches = nodes(scene).filter { it.config.contains(SemanticsActions.OnClick) &&
            it.config.contains(SemanticsProperties.Text) && it.config[SemanticsProperties.Text].any { text -> text.text == label } }
        val node = if (last) matches.last() else matches.first()
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private suspend fun dismissSnackbar(scene: ImageComposeScene) {
        val dismiss = nodes(scene).first { it.config.contains(SemanticsActions.Dismiss) }
        dismiss.config[SemanticsActions.Dismiss].action?.invoke()
        withTimeout(5_000) { while (nodes(scene).any { it.config.contains(SemanticsActions.Dismiss) }) { scene.render(); yield() } }
    }

    private fun texts(scene: ImageComposeScene) = nodes(scene).flatMap { if (it.config.contains(SemanticsProperties.Text)) it.config[SemanticsProperties.Text].map { text -> text.text } else emptyList() }
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
