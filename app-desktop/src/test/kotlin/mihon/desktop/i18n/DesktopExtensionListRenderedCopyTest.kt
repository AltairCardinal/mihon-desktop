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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
        val copies = listOf(
            Copy(Locale.forLanguageTag("zh-CN"), "返回", "按语言筛选", "重新加载已安装扩展", "筛选扩展", "显示成人扩展", "应用", "重置", "取消", "卸载", "确定删除？", "移除“Example Extension”及其所有来源？此操作无法撤销。"),
            Copy(Locale.US, "Navigate up", "Filter by language", "Reload installed extensions", "Filter extensions", "Show NSFW extensions", "Apply", "Reset", "Cancel", "Uninstall", "Remove Extension?", "Remove “Example Extension” and all its sources? This cannot be undone."),
        )
        val scene = ImageComposeScene(900, 900, coroutineContext = coroutineContext) {}
        val previousInjekt = Injekt
        val previousLocale = Locale.getDefault()
        try {
            patchInjekt()
            Injekt.addSingleton(model)
            withTimeout(5_000) { model.refresh().join() }
            withTimeout(5_000) { model.state.first { it.presentation?.installed?.singleOrNull()?.installed === extension } }
            copies.forEach { copy ->
                Locale.setDefault(copy.locale)
                scene.setContent {
                    CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                        Navigator(ExtensionListScreen()) { CurrentScreen() }
                    }
                }
                renderUntil(scene, extension.name)
                clickDescription(scene, copy.filter, "Filter by language")
                scene.render()
                assertCopy(scene, copy.filterTitle, copy.nsfw, copy.apply, copy.reset, copy.cancel)
                clickText(scene, copy.cancel, "Cancel")
                scene.render()
                clickDescription(scene, copy.uninstall, "Uninstall")
                scene.render()
                assertCopy(scene, copy.removeTitle, copy.removeBody, copy.uninstall, copy.cancel)
                assertCopy(scene, copy.up, copy.filter, copy.reload, descriptions = true)
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

    private data class Copy(
        val locale: Locale, val up: String, val filter: String, val reload: String, val filterTitle: String, val nsfw: String,
        val apply: String, val reset: String, val cancel: String, val uninstall: String, val removeTitle: String, val removeBody: String,
    )
}
