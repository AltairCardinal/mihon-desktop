package mihon.desktop.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.data.sync.runtime.SyncPanel
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelPage
import mihon.data.sync.runtime.SyncPanelState
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.fakes.FakeCategoryRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.platform.DesktopFilePicker
import mihon.desktop.platform.DesktopFilePickerRequest
import mihon.desktop.platform.DesktopFilePickerResult
import mihon.desktop.ui.library.LibraryRootScreen
import mihon.desktop.ui.library.LibraryScreenModel
import mihon.desktop.ui.library.ProvideLibraryScreenModelFactory
import mihon.domain.sync.crypto.SyncRecoveryCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.manga.interactor.GetLibraryManga
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
class DesktopSyncPanelTest {
    @Test
    fun `actual library root exposes the sync sheet and nested settings without changing navigator`() = runBlocking {
        val panel = TestPanel()
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) { every { syncPanel } returns panel }
        val uriHandler = mockk<UriHandler> {
            every { openUri(any()) } throws IllegalStateException("no system browser")
        }
        every { dependencies.shareService.copyText(any()) } returns
            mihon.desktop.platform.DesktopShareResult.Failed(
                mihon.desktop.platform.DesktopShareFailureReason.CLIPBOARD_BUSY,
            )
        val model = LibraryScreenModel(
            getLibraryManga = GetLibraryManga(FakeMangaRepository()),
            getCategories = GetCategories(FakeCategoryRepository()),
        )
        val scene = ImageComposeScene(1_200, 900, coroutineContext = coroutineContext) {}
        scene.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalUriHandler provides uriHandler,
                ) {
                    ProvideLibraryScreenModelFactory({ model }) {
                        Navigator(LibraryRootScreen()) { CurrentScreen() }
                    }
                }
            }
        }
        suspend fun render() {
            repeat(4) {
                scene.render()
                yield()
            }
        }
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        fun nodes() = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
        fun find(tag: String) = nodes().firstOrNull {
            it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == tag
        }
        suspend fun click(tag: String) {
            withTimeout(2_000) { while (find(tag) == null) render() }
            assertTrue(requireNotNull(find(tag)!!.config[SemanticsActions.OnClick].action).invoke())
            render()
        }
        try {
            click("sync-open")
            assertTrue(panel.state.value.visible)
            click("sync-settings")
            assertEquals(SyncPanelPage.SETTINGS, panel.state.value.page)
            click("sync-back")
            assertEquals(SyncPanelPage.MAIN, panel.state.value.page)
            panel.state.value = panel.state.value.copy(
                page = SyncPanelPage.SETUP,
                deviceCode = mihon.domain.sync.auth.GitHubDeviceCode(
                    "secret",
                    "ABCD-EFGH",
                    "https://github.com/login/device",
                    600,
                    5,
                ),
            )
            click("sync-copy-open")
            verify(exactly = 2) { dependencies.notificationService.post(any()) }
            click("sync-close")
            assertFalse(panel.state.value.visible)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `recovery files use the shared picker and report success only after actual IO`(
        @TempDir directory: File,
    ) = runBlocking {
        val file = directory.resolve("recovery.json")
        val requests = mutableListOf<DesktopFilePickerRequest>()
        val picker = object : DesktopFilePicker {
            override suspend fun choose(request: DesktopFilePickerRequest): DesktopFilePickerResult {
                requests += request
                return DesktopFilePickerResult.Selected(file)
            }
        }
        val panel = TestPanel()
        val codec = DesktopSyncRecoveryFiles(picker)
        val recovery = SyncRecoveryCodec.encode(
            SyncRecoveryCodec.generate("space", 1, "fixture-recovery-id", { ByteArray(32) { 1 } }, 1).data,
        )
        assertTrue(codec.save(panel, recovery))
        assertEquals(recovery, file.readText(Charsets.UTF_8))
        assertEquals(listOf(SyncPanelAction.RecoverySaved(recovery)), panel.actions)
        assertTrue(codec.load(panel))
        assertEquals(SyncPanelAction.SetRecovery(recovery), panel.actions.last())
        file.writeBytes(ByteArray(16 * 1024 + 1))
        val before = panel.actions.size
        assertFalse(codec.load(panel))
        assertEquals(before, panel.actions.size)
        file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertFalse(codec.load(panel))
        assertEquals(4, requests.size)
    }

    private class TestPanel : SyncPanel {
        override val state = MutableStateFlow(SyncPanelState(loaded = true))
        val actions = mutableListOf<SyncPanelAction>()
        override fun dispatch(action: SyncPanelAction) {
            actions += action
            state.value = when (action) {
                SyncPanelAction.Open -> state.value.copy(visible = true)
                SyncPanelAction.Close -> state.value.copy(visible = false)
                is SyncPanelAction.Navigate -> state.value.copy(page = action.page)
                SyncPanelAction.Back -> state.value.copy(page = SyncPanelPage.MAIN)
                else -> state.value
            }
        }
    }
}
