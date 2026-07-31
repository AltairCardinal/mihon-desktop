package mihon.desktop.i18n

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.ui.settings.MoreRootScreen
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.i18n.MR
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
class MoreSourceExtensionRenderedCopyTest {
    @Test
    fun `More omits extension navigation after browse consolidation`() = runBlocking {
        val downloads = mockk<DesktopDownloadManager> { every { queue } returns MutableStateFlow(emptyList()) }
        val dependencies = mockk<DesktopUiDependencies> {
            every { downloadManager } returns downloads
            every { downloadQueuePort } returns downloads
            every { appPreferences } returns DesktopAppPreferences(InMemoryPreferenceStore())
        }
        val previousLocale = Locale.getDefault()
        try {
            listOf(Locale.forLanguageTag("zh-CN"), Locale.US).forEach { locale ->
                Locale.setDefault(locale)
                val screen = MoreRootScreen()
                val scene = ImageComposeScene(900, 900, coroutineContext = coroutineContext) {}
                try {
                    scene.setContent {
                        CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                            Navigator(screen) { screen.Content() }
                        }
                    }
                    scene.render()
                    assertTrue(MR.strings.label_extensions.localized(locale) !in texts(scene))
                    assertTrue(MR.strings.desktop_more_extensions_summary.localized(locale) !in texts(scene))
                    assertTrue(MR.strings.label_extension_repos.localized(locale) !in texts(scene))
                    assertTrue(MR.strings.label_download_queue.localized(locale) in texts(scene))
                    assertTrue(MR.strings.label_settings.localized(locale) in texts(scene))
                } finally {
                    scene.close()
                }
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    private fun texts(scene: ImageComposeScene) = nodes(scene).flatMap {
        if (it.config.contains(SemanticsProperties.Text)) it.config[SemanticsProperties.Text].map { text -> text.text } else emptyList()
    }
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
