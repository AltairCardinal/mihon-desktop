package mihon.desktop.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import mihon.desktop.APP_VERSION
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.platform.DesktopPlatformPaths
import mihon.desktop.update.DesktopUpdateState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.release.service.ApplicationUpdatePreferences
import java.nio.file.Path
import java.io.File
import tachiyomi.i18n.MR
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
@Isolated
class AboutFeedbackWiringTest {
    @Test
    fun `feedback dialog traps keyboard focus closes with Escape and stays reachable at 320dp double font`() = runBlocking {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        var copies = 0
        var opens = 0
        val clipboard = mockk<ClipboardManager> { every { setText(any()) } answers { copies++; Unit } }
        val uri = object : UriHandler { override fun openUri(uri: String) { opens++ } }
        try {
            for (dark in listOf(false, true)) {
                val scene = ImageComposeScene(320, 900, coroutineContext = coroutineContext) {}
                try {
                    scene.setContent {
                        CompositionLocalProvider(
                            LocalClipboardManager provides clipboard,
                            LocalUriHandler provides uri,
                            LocalDensity provides Density(1f, 2f),
                        ) {
                            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                                AboutFeedbackSection()
                            }
                        }
                    }
                    render(scene)
                    click(scene, "Report a problem")
                    render(scene)
                    savePreview(scene, dark, "top")
                    assertEquals(2, scene.semanticsOwners.size)
                    val dialog = scene.semanticsOwners.last()
                    fun dialogNodes() = flatten(dialog.unmergedRootSemanticsNode)
                    val copyButton = dialogNodes().single { it.config.contains(SemanticsActions.OnClick) && "Copy safe summary" in text(it) }
                    assertTrue(requireNotNull(copyButton.config[SemanticsActions.RequestFocus].action).invoke())
                    render(scene)
                    val controls = setOf("Copy safe summary", "Open GitHub issue form", "Close")
                    for (shift in listOf(false, true)) {
                        val visited = mutableSetOf<String>()
                        repeat(3) {
                            scene.sendKeyEvent(keyEvent(Key.Tab, KeyEventType.KeyDown, shift))
                            scene.sendKeyEvent(keyEvent(Key.Tab, KeyEventType.KeyUp, shift))
                            render(scene)
                            val active = dialogNodes().single {
                                it.config.contains(SemanticsProperties.Focused) && it.config[SemanticsProperties.Focused]
                            }
                            visited += controls.single { it in text(active) }
                            assertTrue(scene.semanticsOwners.last() === dialog)
                        }
                        assertEquals(controls, visited)
                    }
                    val scroller = dialogNodes().single { it.config.contains(SemanticsActions.ScrollBy) }
                    assertTrue(requireNotNull(scroller.config[SemanticsActions.ScrollBy].action).invoke(0f, -10_000f))
                    settleScroll(scene)
                    val range = scroller.config[SemanticsProperties.VerticalScrollAxisRange]
                    assertEquals(0f, range.value())
                    assertTrue(requireNotNull(scroller.config[SemanticsActions.ScrollBy].action).invoke(0f, 10_000f))
                    settleScroll(scene)
                    savePreview(scene, dark, "bottom")
                    assertTrue(range.value() > 0f, "Safe summary scroll=${range.value()}/${range.maxValue()} at double font")
                    for (label in controls) {
                        val button = dialogNodes().single { it.config.contains(SemanticsActions.OnClick) && label in text(it) }
                        val bounds = button.boundsInRoot
                        assertTrue(bounds.width > 0f && bounds.height > 0f, label)
                        assertTrue(bounds.left >= 0f && bounds.right <= 320f && bounds.top >= 0f && bounds.bottom <= 900f, "$label: $bounds")
                    }
                    val beforeCopies = copies
                    val beforeOpens = opens
                    scene.sendKeyEvent(keyEvent(Key.Escape, KeyEventType.KeyDown))
                    scene.sendKeyEvent(keyEvent(Key.Escape, KeyEventType.KeyUp))
                    render(scene)
                    assertEquals(1, scene.semanticsOwners.size)
                    assertEquals(beforeCopies, copies)
                    assertEquals(beforeOpens, opens)
                    assertTrue(nodes(scene).any {
                        it.config.contains(SemanticsProperties.Focused) && it.config[SemanticsProperties.Focused] &&
                            MR.strings.feedback_report_problem.localized() in text(it)
                    })
                    click(scene, "Report a problem")
                    render(scene)
                    scene.sendPointerEvent(
                        PointerEventType.Press,
                        Offset(1f, 1f),
                        buttons = PointerButtons(isPrimaryPressed = true),
                        button = PointerButton.Primary,
                    )
                    scene.sendPointerEvent(
                        PointerEventType.Release,
                        Offset(1f, 1f),
                        buttons = PointerButtons(isPrimaryPressed = false),
                        button = PointerButton.Primary,
                    )
                    render(scene)
                    assertEquals(1, scene.semanticsOwners.size)
                    assertEquals(beforeCopies, copies)
                    assertEquals(beforeOpens, opens)
                    assertTrue(nodes(scene).any {
                        it.config.contains(SemanticsProperties.Focused) && it.config[SemanticsProperties.Focused] &&
                            MR.strings.feedback_report_problem.localized() in text(it)
                    })
                } finally {
                    scene.close()
                }
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `About update switches persist to shared preferences and survive reopening`(@TempDir root: Path) = runBlocking {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val preferences = ApplicationUpdatePreferences(InMemoryPreferenceStore())
        val update = mockk<DesktopUpdateScreenModel>(relaxed = true) {
            every { state } returns MutableStateFlow(DesktopUpdateState.Idle)
            every { feedback } returns MutableStateFlow(null)
            every { this@mockk.preferences } returns preferences
            every { presentation() } returns DesktopUpdateState.Idle.presentation()
        }
        val extensions = DesktopExtensionManager()
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { extensionPresentationService } returns extensions
            every { updateScreenModel } returns update
        }
        val paths = DesktopPlatformPaths.resolve("Linux", root.toString(), emptyMap())
        var navigator: Navigator? = null
        val scene = ImageComposeScene(900, 1_600, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    MaterialTheme {
                        Navigator(AboutScreen(paths)) { navigator = it; CurrentScreen() }
                    }
                }
            }
            render(scene)
            click(scene, "Check for app updates automatically")
            click(scene, "Include testing releases")
            render(scene)
            assertFalse(preferences.enabled().get())
            assertFalse(preferences.testingChannel().get())
            navigator!!.replace(AboutScreen(paths))
            render(scene)
            click(scene, "Check for app updates automatically")
            click(scene, "Include testing releases")
            render(scene)
            assertTrue(preferences.enabled().get())
            assertTrue(preferences.testingChannel().get())
        } finally {
            scene.close()
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `real About previews and copies safe diagnostics before explicit project issue action`(@TempDir root: Path) = runBlocking {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val copied = mutableListOf<String>()
        val opened = mutableListOf<String>()
        var copyAllowed = false
        var openAllowed = false
        val clipboard = mockk<ClipboardManager> {
            every { setText(any()) } answers {
                check(copyAllowed) { "Private clipboard implementation detail" }
                copied += firstArg<AnnotatedString>().text
            }
        }
        val uri = object : UriHandler {
            override fun openUri(uri: String) {
                check(openAllowed) { "Private browser implementation detail" }
                opened += uri
            }
        }
        val update = mockk<DesktopUpdateScreenModel>(relaxed = true) {
            every { state } returns MutableStateFlow(DesktopUpdateState.Idle)
            every { feedback } returns MutableStateFlow(null)
            every { preferences } returns ApplicationUpdatePreferences(InMemoryPreferenceStore())
            every { presentation() } returns DesktopUpdateState.Idle.presentation()
        }
        val extensions = DesktopExtensionManager()
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { extensionPresentationService } returns extensions
            every { updateScreenModel } returns update
        }
        val scene = ImageComposeScene(900, 1_600, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalClipboardManager provides clipboard,
                    LocalUriHandler provides uri,
                ) {
                    MaterialTheme {
                        Navigator(AboutScreen(DesktopPlatformPaths.resolve("Linux", root.toString(), emptyMap()))) {
                            CurrentScreen()
                        }
                    }
                }
            }
            render(scene)
            click(scene, "Report a problem")
            render(scene)
            assertTrue(labels(scene).any { "GitHub" in it && "public" in it })
            assertEquals(emptyList<String>(), opened)
            assertEquals(emptyList<String>(), copied)
            click(scene, "Copy safe summary")
            render(scene)
            assertTrue("Could not copy the summary. Try again." in labels(scene))
            assertFalse(labels(scene).any { "Private clipboard" in it })
            copyAllowed = true
            click(scene, "Copy safe summary")
            render(scene)
            val summary = copied.single()
            assertTrue(APP_VERSION in summary)
            assertTrue(summary in labels(scene))
            assertFalse(root.toString() in summary)
            assertFalse("Installation ID" in summary)
            assertTrue("Safe summary copied" in labels(scene))
            click(scene, "Open GitHub issue form")
            render(scene)
            assertTrue("Could not open GitHub. Try again or open the project Issues page in your browser." in labels(scene))
            assertFalse(labels(scene).any { "Private browser" in it })
            assertEquals(emptyList<String>(), opened)
            openAllowed = true
            click(scene, "Open GitHub issue form")
            render(scene)
            assertEquals(listOf("https://github.com/AltairCardinal/mihon-desktop/issues/new?template=2_report_issue.yml"), opened)
            click(scene, "Close")
            render(scene)
            assertFalse("Copy safe summary" in labels(scene))
            assertEquals(1, copied.size)
            assertEquals(1, opened.size)
        } finally {
            scene.close()
            Locale.setDefault(previousLocale)
        }
    }

    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun text(node: SemanticsNode): List<String> = flatten(node).flatMap {
        if (it.config.contains(SemanticsProperties.Text)) it.config[SemanticsProperties.Text].map(AnnotatedString::text) else emptyList()
    }
    private fun labels(scene: ImageComposeScene) = nodes(scene).flatMap(::text)
    private fun click(scene: ImageComposeScene, label: String) {
        val action = nodes(scene).single { it.config.contains(SemanticsActions.OnClick) && label in text(it) }
        assertTrue(requireNotNull(action.config[SemanticsActions.OnClick].action).invoke())
    }
    private suspend fun render(scene: ImageComposeScene) {
        scene.render(System.nanoTime()).close()
        yield()
        scene.render(System.nanoTime()).close()
        yield()
    }
    private suspend fun settleScroll(scene: ImageComposeScene) = repeat(24) {
        scene.render(System.nanoTime()).close()
        delay(16)
    }
    private fun savePreview(scene: ImageComposeScene, dark: Boolean, position: String) {
        val output = File(System.getenv("MIHON_FEEDBACK_VISUAL_DIR") ?: "build/reports/feedback-visual").apply { mkdirs() }
        scene.render(System.nanoTime()).use { image ->
            File(output, "feedback-${if (dark) "dark" else "light"}-320-font200-$position.png").writeBytes(
                requireNotNull(image.encodeToData()).bytes,
            )
        }
    }
    private fun keyEvent(key: Key, type: KeyEventType, shift: Boolean = false): androidx.compose.ui.input.key.KeyEvent {
        val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
        val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            .getMethod(if (type == KeyEventType.KeyDown) "access\$getKeyDown\$cp" else "access\$getKeyUp\$cp")
            .invoke(null)
        val factory = events.declaredMethods.single { it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default") }
        return androidx.compose.ui.input.key.KeyEvent(factory.invoke(null, key.keyCode, eventType, 0, false, false, false, shift, null))
    }
}
