package mihon.desktop.ui.library

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import java.awt.image.BufferedImage
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.DesktopNotificationService
import mihon.desktop.reader.DesktopReaderImageAsset
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
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
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
        val nativePort = DelayedNativeSharePort()
        val notifications = DesktopNotificationService()
        val service = DesktopShareService(
            nativeSharePort = nativePort,
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
        val safetyRelease = Executors.newSingleThreadScheduledExecutor()

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
            val fallback = safetyRelease.schedule(nativePort::releaseReady, 2, TimeUnit.SECONDS)
            val startedAt = System.nanoTime()
            click(scene, "Share link")
            val clickMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue(clickMillis < 500, "share click blocked the scene for ${clickMillis}ms")
            assertTrue(nativePort.awaitShareCall())
            assertTrue(nativePort.sessions.isEmpty())

            scene.render()
            click(scene, "Copy link")
            assertEquals("https://example.com/manga", copied)

            val feedback = async(start = CoroutineStart.UNDISPATCHED) {
                notifications.notifications.take(3).map { it.message }.toList()
            }
            nativePort.releaseReady()
            fallback.cancel(false)
            val terminals = listOf(
                DesktopNativeShareTerminal.Shared,
                DesktopNativeShareTerminal.Cancelled,
                DesktopNativeShareTerminal.Failed,
            )
            terminals.forEachIndexed { index, terminal ->
                if (index > 0) click(scene, "Share link")
                withTimeout(5_000) {
                    while (nativePort.sessions.size <= index) delay(10)
                }
                nativePort.sessions[index].complete(terminal)
            }
            assertEquals(
                listOf(
                    MR.strings.completed.localized(),
                    MR.strings.cancelled.localized(),
                    MR.strings.error_sharing_cover.localized(),
                ),
                feedback.await(),
            )
        } finally {
            nativePort.releaseReady()
            safetyRelease.shutdownNow()
            scene.close()
        }

        assertEquals("https://example.com/manga", copied)
        assertEquals(3, nativePort.sessions.size)
    }

    @Test
    fun `real reader menu lazily shares copies saves and preserves set cover callback`() = runBlocking {
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
        val bitmap = Bitmap().apply { allocN32Pixels(1, 1) }
        Canvas(bitmap).clear(Color.RED)
        val imageAsset = DesktopReaderImageAsset(
            bitmap = bitmap.asComposeImageBitmap(),
            sourceWidth = 1,
            sourceHeight = 1,
            estimatedBytes = 4,
            sampled = false,
            disposer = bitmap::close,
        )
        val imageLeaseRequests = AtomicInteger()
        val saveDirectoryRequests = AtomicInteger()
        val setCoverRequests = AtomicInteger()
        val directory = kotlin.io.path.createTempDirectory("mihon-page-actions").toFile()
        val saved = directory.resolve(PageSaveHelper.buildSaveFileName("Manga", "Chapter", 0))
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
                        imageLeaseProvider = {
                            imageLeaseRequests.incrementAndGet()
                            imageAsset.retain()
                        },
                        mangaTitle = "Manga",
                        chapterTitle = "Chapter",
                        pageIndex = 0,
                        scope = this@runBlocking,
                        onSetAsCover = {
                            setCoverRequests.incrementAndGet()
                            Unit
                        },
                        saveDirectoryProvider = {
                            saveDirectoryRequests.incrementAndGet()
                            directory
                        },
                    ) {}
                }
            }
            scene.render()
            assertEquals(4, capturedItems.size)
            assertEquals(0, imageLeaseRequests.get(), "Rendering the menu must not retain or load its image")
            assertEquals(
                0,
                saveDirectoryRequests.get(),
                "Rendering the menu must not resolve or create a save directory",
            )

            capturedItems.take(3).forEach { it.onClick() }
            withTimeout(5_000) {
                while (
                    nativeShares != 1 ||
                    !copied ||
                    !saved.isFile ||
                    imageLeaseRequests.get() != 3 ||
                    saveDirectoryRequests.get() != 1
                ) {
                    delay(10)
                }
            }
            assertEquals(3, imageLeaseRequests.get())
            assertEquals(1, saveDirectoryRequests.get())

            capturedItems.last().onClick()
            assertEquals(1, setCoverRequests.get())
            assertEquals(3, imageLeaseRequests.get(), "Set as cover keeps its existing callback-only contract")
            assertEquals(1, saveDirectoryRequests.get())

            val terminalNotification = async(start = CoroutineStart.UNDISPATCHED) {
                notifications.notifications.first { it.message == MR.strings.cancelled.localized() }
            }
            session.complete(DesktopNativeShareTerminal.Cancelled)
            assertEquals(MR.strings.cancelled.localized(), terminalNotification.await().message)
        } finally {
            scene.close()
            imageAsset.close()
            directory.deleteRecursively()
        }

        assertTrue(nativeContent is DesktopNativeShareContent.LocalFile)
        assertEquals(
            MR.strings.share_page_info.localized(Locale.getDefault(), "Manga", "Chapter", 1),
            (nativeContent as DesktopNativeShareContent.LocalFile).message,
        )
    }

    private class ControlledShareSession : DesktopNativeShareSession {
        private var callback: ((DesktopNativeShareTerminal) -> Unit)? = null
        private val callbackRegistered = CountDownLatch(1)

        override fun onTerminal(callback: (DesktopNativeShareTerminal) -> Unit) {
            this.callback = callback
            callbackRegistered.countDown()
        }

        fun complete(terminal: DesktopNativeShareTerminal) {
            assertTrue(callbackRegistered.await(2, TimeUnit.SECONDS))
            requireNotNull(callback)(terminal)
        }
    }

    private class DelayedNativeSharePort : DesktopNativeSharePort {
        private val shareCalled = CountDownLatch(1)
        private val ready = CountDownLatch(1)
        val sessions = CopyOnWriteArrayList<ControlledShareSession>()

        override fun share(content: DesktopNativeShareContent): DesktopNativeShareOutcome {
            shareCalled.countDown()
            check(ready.await(5, TimeUnit.SECONDS))
            return DesktopNativeShareOutcome.Opened(ControlledShareSession().also(sessions::add))
        }

        fun awaitShareCall(): Boolean = shareCalled.await(1, TimeUnit.SECONDS)
        fun releaseReady() = ready.countDown()
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
