package mihon.desktop.i18n

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.ui.extension.ExtensionListScreen
import mihon.desktop.ui.settings.MoreRootScreen
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
class MoreSourceExtensionRenderedCopyTest {
    @Test
    fun `source extension entries render localized copy and navigate`() = runBlocking {
        val downloads = mockk<DesktopDownloadManager> { every { queue } returns MutableStateFlow(emptyList()) }
        val dependencies = mockk<DesktopUiDependencies> { every { downloadManager } returns downloads }
        val previousLocale = Locale.getDefault()
        try {
            listOf(Locale.forLanguageTag("zh-CN"), Locale.US).forEach { locale ->
                Locale.setDefault(locale)
                val screen = MoreRootScreen()
                lateinit var navigator: Navigator
                val scene = ImageComposeScene(900, 900, coroutineContext = coroutineContext) {}
                try {
                    scene.setContent {
                        CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                            Navigator(screen) {
                                navigator = LocalNavigator.currentOrThrow
                                screen.Content()
                            }
                        }
                    }
                    scene.render()
                    val expected = listOf(
                        MR.strings.label_extensions.localized(locale),
                        MR.strings.desktop_more_extensions_summary.localized(locale),
                        MR.strings.label_extension_repos.localized(locale),
                        MR.strings.desktop_more_extension_repos_summary.localized(locale),
                    )
                    expected.forEach { assertTrue(it in texts(scene), "Missing '$it': ${texts(scene)}") }
                    click(scene, MR.strings.label_extensions.localized(locale))
                    assertTrue(navigator.lastItem is ExtensionListScreen)
                } finally {
                    scene.close()
                }
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first { candidate -> candidate.config.contains(SemanticsActions.OnClick) &&
            flatten(candidate).any { it.config.contains(SemanticsProperties.Text) && it.config[SemanticsProperties.Text].any { text -> text.text == label } } }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun texts(scene: ImageComposeScene) = nodes(scene).flatMap {
        if (it.config.contains(SemanticsProperties.Text)) it.config[SemanticsProperties.Text].map { text -> text.text } else emptyList()
    }
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
