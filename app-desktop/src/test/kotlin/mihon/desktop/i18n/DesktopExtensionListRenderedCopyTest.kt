package mihon.desktop.i18n

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import dev.mihon.injekt.patchInjekt
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
import mihon.desktop.ui.extension.DesktopExtensionPresentationPort
import mihon.desktop.ui.extension.ExtensionListScreen
import mihon.desktop.ui.extension.ExtensionsScreenModel
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.File
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
class DesktopExtensionListRenderedCopyTest {
    @Test
    fun `extension list filter and uninstall render localized copy`() = runBlocking {
        val extension = InstalledExtension(File("example.jar"), emptyList(), displayName = "Example Extension", language = "en")
        val api = mockk<DesktopExtensionApi> {
            coEvery { refreshCatalog() } returns ExtensionCatalogResult(emptyList(), emptyList())
            every { availableExtensions(any()) } returns emptyList()
            coEvery { loadExtensionIcon(any()) } returns null
        }
        val manager = mockk<DesktopExtensionManager>(relaxed = true)
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(api, manager, MutableStateFlow(listOf(extension))),
            this,
            ExtensionPresentationOptions(false, setOf("en")),
        )
        val dependencies = mockk<DesktopUiDependencies> {
            every { extensionApi } returns api
            every { extensionManager } returns manager
        }
        val locales = listOf(Locale.forLanguageTag("zh-CN"), Locale.US)
        val scene = ImageComposeScene(900, 900, coroutineContext = coroutineContext) {}
        val previousInjekt = Injekt
        val previousLocale = Locale.getDefault()
        try {
            patchInjekt()
            Injekt.addSingleton(model)
            withTimeout(5_000) { model.refresh().join() }
            withTimeout(5_000) { model.state.first { it.presentation?.installed?.singleOrNull()?.installed === extension } }
            locales.forEach { locale ->
                Locale.setDefault(locale)
                val filterTitle = MR.strings.action_filter.localized(locale)
                val filterDescription = MR.strings.desktop_extension_filter_by_language.localized(locale)
                val cancel = MR.strings.action_cancel.localized(locale)
                val uninstall = MR.strings.ext_uninstall.localized(locale)
                val removeBody = MR.strings.desktop_extension_remove_confirmation.localized(locale, extension.name)
                if (locale.language == "zh") {
                    assertNotEquals(MR.strings.action_filter.localized(Locale.US), filterTitle)
                    assertTrue(removeBody.contains(extension.name) && MR.strings.desktop_extension_source_settings.localized(locale, "Example Source").contains("Example Source"))
                }
                scene.setContent {
                    CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                        Navigator(ExtensionListScreen()) { CurrentScreen() }
                    }
                }
                renderUntil(scene, extension.name)
                clickDescription(scene, filterDescription)
                scene.render()
                assertCopy(
                    scene,
                    filterTitle,
                    MR.strings.desktop_extension_show_nsfw.localized(locale),
                    MR.strings.action_apply.localized(locale),
                    MR.strings.action_reset.localized(locale),
                    cancel,
                )
                clickText(scene, cancel)
                scene.render()
                clickDescription(scene, uninstall)
                scene.render()
                assertCopy(scene, MR.strings.ext_confirm_remove.localized(locale), removeBody, uninstall, cancel)
                assertCopy(
                    scene,
                    MR.strings.action_bar_up_description.localized(locale),
                    filterDescription,
                    MR.strings.desktop_extension_reload_installed.localized(locale),
                    descriptions = true,
                )
            }
        } finally {
            scene.close()
            model.closeAndJoin()
            Injekt = previousInjekt
            Locale.setDefault(previousLocale)
        }
    }

    private suspend fun renderUntil(scene: ImageComposeScene, text: String) = withTimeout(5_000) {
        while (text !in copy(scene, false)) { scene.render(); yield() }
    }

    private fun assertCopy(scene: ImageComposeScene, vararg expected: String, descriptions: Boolean = false) {
        val actual = copy(scene, descriptions)
        expected.forEach { assertTrue(it in actual, "Missing '$it': $actual") }
    }

    private fun clickText(scene: ImageComposeScene, vararg labels: String) = click(scene) { node ->
        flatten(node).any { it.config.contains(SemanticsProperties.Text) && it.config[SemanticsProperties.Text].any { text -> text.text in labels } }
    }

    private fun clickDescription(scene: ImageComposeScene, vararg labels: String) = click(scene) {
        it.config.contains(SemanticsProperties.ContentDescription) && it.config[SemanticsProperties.ContentDescription].any { value -> value in labels }
    }

    private fun click(scene: ImageComposeScene, matches: (SemanticsNode) -> Boolean) {
        val node = nodes(scene).single { it.config.contains(SemanticsActions.OnClick) && matches(it) }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun copy(scene: ImageComposeScene, descriptions: Boolean) = nodes(scene).flatMap {
        val key = if (descriptions) SemanticsProperties.ContentDescription else SemanticsProperties.Text
        if (!it.config.contains(key)) emptyList() else it.config[key].map(Any::toString)
    }.toSet()

    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

}
