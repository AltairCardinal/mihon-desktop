package mihon.desktop.ui.library

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.DesktopNotificationService
import mihon.desktop.reader.PageSaveHelper
import mihon.desktop.platform.DesktopClipboardPort
import mihon.desktop.platform.DesktopNativeShareOutcome
import mihon.desktop.platform.DesktopNativeSharePort
import mihon.desktop.platform.DesktopNativeShareContent
import mihon.desktop.platform.DesktopNativeShareSession
import mihon.desktop.platform.DesktopNativeShareTerminal
import mihon.desktop.platform.DesktopRevealPort
import mihon.desktop.platform.DesktopShareService
import mihon.desktop.ui.reader.PageContextMenu
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import io.mockk.every
import io.mockk.mockk

@OptIn(ExperimentalComposeUiApi::class)
class MangaShareWiringTest {

    @Test
    fun `desktop UI dependencies expose the share service`() {
        val binding: (DesktopUiDependencies) -> DesktopShareService = { dependencies ->
            dependencies.shareService
        }

        assertNotNull(binding)
    }

    @Test
    fun `rendered manga actions bind copy and share through the desktop share service`() = runBlocking {
        var copied: String? = null
        var nativeCalls = 0
        val sessions = mutableListOf<ControlledShareSession>()
        val notifications = DesktopNotificationService()
        val service = DesktopShareService(
            nativeSharePort = DesktopNativeSharePort {
                nativeCalls++
                DesktopNativeShareOutcome.Opened(ControlledShareSession().also(sessions::add))
            },
            clipboardPort = object : DesktopClipboardPort {
                override fun copyText(text: String) { copied = text }
                override fun copyImage(image: BufferedImage) = Unit
            },
            isHeadless = { false },
            revealPort = DesktopRevealPort {},
        )
        val dependencies = mockk<DesktopUiDependencies> {
            every { shareService } returns service
            every { notificationService } returns notifications
        }
        val scene = ImageComposeScene(1_200, 300, coroutineContext = coroutineContext) {}

        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    MangaDetailActionRow(
                        manga = Manga.create().copy(id = 1, title = "Manga"),
                        mangaUrl = "https://example.com/manga",
                        hasUnreadChapters = false,
                        onToggleLibrary = {},
                        onEditCategories = {},
                        onEditFetchInterval = {},
                        onTracking = {},
                        onOpenInBrowser = {},
                    )
                }
            }
            scene.render()
            click(scene, "Copy link")
            val terminalNotification = async(start = CoroutineStart.UNDISPATCHED) {
                notifications.notifications.first { it.message == MR.strings.completed.localized() }
            }
            click(scene, "Share link")
            delay(50)
            assertTrue(!terminalNotification.isCompleted)
            sessions.single().complete(DesktopNativeShareTerminal.Shared)
            assertEquals(MR.strings.completed.localized(), terminalNotification.await().message)
            val failedNotification = async(start = CoroutineStart.UNDISPATCHED) {
                notifications.notifications.first { it.message == MR.strings.error_sharing_cover.localized() }
            }
            click(scene, "Share link")
            sessions.last().complete(DesktopNativeShareTerminal.Failed)
            assertEquals(MR.strings.error_sharing_cover.localized(), failedNotification.await().message)
        } finally {
            scene.close()
        }

        assertEquals("https://example.com/manga", copied)
        assertEquals(2, nativeCalls)
    }

    @Test
    fun `real reader menu callbacks share copy save and publish feedback through injected service`() = runBlocking {
        var copied = false
        var nativeShares = 0
        var nativeContent: DesktopNativeShareContent? = null
        val session = ControlledShareSession()
        val notifications = DesktopNotificationService()
        val service = DesktopShareService(
            nativeSharePort = DesktopNativeSharePort {
                nativeShares++
                nativeContent = it
                DesktopNativeShareOutcome.Opened(session)
            },
            clipboardPort = object : DesktopClipboardPort {
                override fun copyText(text: String) = Unit
                override fun copyImage(image: BufferedImage) { copied = true }
            },
            isHeadless = { false },
            revealPort = DesktopRevealPort {},
        )
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val directory = kotlin.io.path.createTempDirectory("mihon-page-actions").toFile()
        val source = File(directory, "source.png")
        val saved = File(directory, PageSaveHelper.buildSaveFileName("Manga", "Chapter", 0))
        PageSaveHelper.saveImageToFile(image, source)
        val dependencies = mockk<DesktopUiDependencies> {
            every { shareService } returns service
            every { notificationService } returns notifications
        }
        lateinit var capturedItems: List<ContextMenuItem>
        val representation = object : ContextMenuRepresentation {
            @Composable
            override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
                capturedItems = items()
            }
        }
        val scene = ImageComposeScene(200, 200, coroutineContext = coroutineContext) {}

        try {
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalContextMenuRepresentation provides representation,
                ) {
                    PageContextMenu(
                        source.toURI().toString(), "Manga", "Chapter", 0, this@runBlocking, null,
                        saveDirectory = directory,
                    ) {}
                }
            }
            scene.render()
            capturedItems.forEach { it.onClick() }
            withTimeout(5_000) {
                while (nativeShares != 1 || !copied || !saved.isFile) delay(10)
            }
            val terminalNotification = async(start = CoroutineStart.UNDISPATCHED) {
                notifications.notifications.first { it.message == MR.strings.cancelled.localized() }
            }
            session.complete(DesktopNativeShareTerminal.Cancelled)
            assertEquals(MR.strings.cancelled.localized(), terminalNotification.await().message)
        } finally {
            scene.close()
            directory.deleteRecursively()
        }

        assertEquals(3, capturedItems.size)
        assertTrue(nativeContent is DesktopNativeShareContent.LocalFile)
        assertEquals(
            MR.strings.share_page_info.localized(Locale.getDefault(), "Manga", "Chapter", 1),
            (nativeContent as DesktopNativeShareContent.LocalFile).message,
        )
    }

    private class ControlledShareSession : DesktopNativeShareSession {
        private var callback: ((DesktopNativeShareTerminal) -> Unit)? = null

        override fun onTerminal(callback: (DesktopNativeShareTerminal) -> Unit) {
            this.callback = callback
        }

        fun complete(terminal: DesktopNativeShareTerminal) = requireNotNull(callback)(terminal)
    }

    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first { candidate ->
            candidate.config.contains(SemanticsActions.OnClick) && flatten(candidate).any {
                it.config.contains(SemanticsProperties.ContentDescription) &&
                    label in it.config[SemanticsProperties.ContentDescription]
            }
        }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
