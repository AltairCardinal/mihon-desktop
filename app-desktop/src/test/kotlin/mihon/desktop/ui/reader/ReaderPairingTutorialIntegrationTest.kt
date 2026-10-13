package mihon.desktop.ui.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.reader.DesktopReaderImageAsset
import mihon.desktop.reader.DesktopReaderPageContentOwner
import mihon.desktop.reader.DesktopReaderPageImageDecoder
import mihon.desktop.reader.DesktopReaderPageImagePipeline
import mihon.desktop.reader.DesktopReaderPresentationImageOwner
import mihon.desktop.reader.ReaderPageIoObserver
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.desktopReaderSessionState
import mihon.desktop.settings.DesktopAppPreferences
import mihon.domain.reader.ReaderPageFact
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.io.File
import java.util.Locale
import java.util.prefs.Preferences

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ReaderPairingTutorialIntegrationTest {
    @Test
    fun `tutorial leaves the exposed page edge available for normal reading`() = runTest {
        withFixture { fixture ->
            fixture.mount()
            assertTrue(fixture.hasBubble())
            fixture.click(Offset(880f, 120f))
            assertEquals(3, fixture.model.state.value.currentPage)
            assertTrue(fixture.hasBubble(), "Reading outside the bubble must neither block nor acknowledge teaching")
        }
    }

    @Test
    fun `C dispatches to real shared pairing owner`() = runTest {
        withFixture { fixture ->
            fixture.mount()
            fixture.key()
            assertEquals(setOf(1), fixture.model.state.value.forcedSinglePages)
        }
    }

    @Test
    fun `real viewport C adjusts existing pairing once and remembers tutorial across reader instances`() = runTest {
        val root = Preferences.userRoot().node("/mihon/pairing-tutorial/${System.nanoTime()}")
        try {
            Fixture(this, root).use { fixture ->
                fixture.mount()
                assertTrue(fixture.hasBubble())
                assertFalse(fixture.model.state.value.showUI, "Tutorial must be visible with controls hidden")
                fixture.key()
                assertEquals(setOf(1), fixture.model.state.value.forcedSinglePages)
                fixture.key(down = false)
                assertEquals(
                    setOf(1),
                    fixture.model.state.value.forcedSinglePages,
                    "Key up must not toggle pairing again",
                )
                assertFalse(fixture.hasBubble())
            }
            Fixture(this, root).use { fixture ->
                fixture.mount()
                assertFalse(fixture.hasBubble(), "Acknowledgement must survive new preferences and reader instances")
            }
        } finally {
            root.removeNode()
        }
    }

    @Test
    fun `shortcut ignores modifiers settings single page loading and saving`() = runTest {
        withFixture { fixture ->
            fixture.mount()
            for (modifier in 0..3) {
                fixture.key(modifier = modifier)
                assertEquals(emptySet<Int>(), fixture.model.state.value.forcedSinglePages)
                assertTrue(fixture.hasBubble())
            }
            val disabledStates: List<(ReaderState) -> ReaderState> = listOf(
                { it.copy(showSettings = true) },
                { it.copy(dualPageMode = false) },
                { it.copy(readingMode = ReadingMode.WEBTOON) },
                { it.copy(pairingLoad = PairingLoad.LOADING) },
                { it.copy(pairingLoad = PairingLoad.ERROR) },
                { it.copy(pairingSaving = true) },
                { it.copy(resumePageUnavailable = true) },
            )
            for (disabled in disabledStates) {
                fixture.transform = disabled
                fixture.frames()
                assertFalse(fixture.hasBubble())
                fixture.key()
                assertEquals(emptySet<Int>(), fixture.model.state.value.forcedSinglePages)
            }
            fixture.transform = { it }
            fixture.frames()
            assertTrue(fixture.hasBubble(), "Temporary ineligibility must not acknowledge tutorial")
        }
    }

    @Test
    fun `bubble confirms in narrow and wide light and dark readers without changing pages`() = runTest {
        for (dark in listOf(false, true)) {
            for (width in listOf(360, 900)) {
                withFixture(width, dark) { fixture ->
                    fixture.mount()
                    assertTrue(fixture.hasBubble())
                    fixture.capture("tutorial-$width-${if (dark) "dark" else "light"}.png")
                    val confirm = fixture.node("desktop-tutorial-confirm")
                    val bounds = confirm.boundsInRoot
                    assertTrue(bounds.left >= 0f && bounds.right <= width)
                    assertTrue(bounds.top >= 0f && bounds.bottom <= 600f)
                    fixture.click(bounds.center)
                    assertFalse(fixture.hasBubble())
                    assertEquals(emptySet<Int>(), fixture.model.state.value.forcedSinglePages)
                    fixture.key()
                    assertEquals(
                        setOf(1),
                        fixture.model.state.value.forcedSinglePages,
                        "Confirm must restore reader focus",
                    )
                }
            }
        }
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            withFixture(width = 360) { fixture ->
                fixture.mount()
                assertTrue(fixture.hasBubble())
                fixture.capture("tutorial-360-chinese.png")
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `existing adjust button uses same action and completes tutorial`() = runTest {
        withFixture { fixture ->
            fixture.model.toggleUI()
            fixture.mount()
            assertTrue(fixture.hasBubble())
            val action = fixture.nodes().first { node ->
                node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                    .any { it.contains("Adjust spread", ignoreCase = true) || it.contains("调整跨页") }
            }
            fixture.click(action.boundsInRoot.center)
            assertEquals(setOf(1), fixture.model.state.value.forcedSinglePages)
            assertFalse(fixture.hasBubble())
        }
    }

    @Test
    fun `bubble action performs actual pairing and returns keyboard focus`() = runTest {
        withFixture { fixture ->
            fixture.mount()
            fixture.click(fixture.node("desktop-tutorial-action").boundsInRoot.center)
            assertEquals(setOf(1), fixture.model.state.value.forcedSinglePages)
            assertFalse(fixture.hasBubble())
            fixture.key()
            assertEquals(emptySet<Int>(), fixture.model.state.value.forcedSinglePages)
        }
    }

    @Test
    fun `C on a wide spread does not acknowledge an action that cannot change pairing`() = runTest {
        val root = Preferences.userRoot().node("/mihon/pairing-tutorial/${System.nanoTime()}")
        try {
            Fixture(this, root, widePages = true).use { fixture ->
                fixture.mount()
                assertTrue(fixture.hasBubble())
                fixture.key()
                assertEquals(emptySet<Int>(), fixture.model.state.value.forcedSinglePages)
                assertTrue(fixture.hasBubble())
            }
        } finally {
            root.removeNode()
        }
    }

    private fun TestScope.withFixture(width: Int = 900, dark: Boolean = false, block: (Fixture) -> Unit) {
        val root = Preferences.userRoot().node("/mihon/pairing-tutorial/${System.nanoTime()}")
        try {
            Fixture(this, root, width, dark).use(block)
        } finally {
            root.removeNode()
        }
    }

    private class Fixture(
        val scope: TestScope,
        root: Preferences,
        width: Int = 900,
        val dark: Boolean = false,
        widePages: Boolean = false,
    ) : AutoCloseable {
        val prefs = ReaderPreferences(DesktopPreferenceStore(root.node("reader")), root.node("legacy-reader")).apply {
            readingMode = ReadingMode.LTR
            isDualPage = true
        }
        val model =
            ReaderScreenModel(
                prefs = prefs,
                initialSessionState = desktopReaderSessionState(pageCount = 6, initialPage = 1),
            )
        private val reporter = ReaderIoReporter(ReaderIoProbe.None, ReaderMonotonicClock { 0L })
        private val content = DesktopReaderPageContentOwner(scope, { byteArrayOf(1) }, reporter)
        private val imageWidth = if (widePages) 20 else 10
        private val pipeline = DesktopReaderPageImagePipeline(
            scope,
            content,
            reporter,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                val bitmap = Bitmap().apply {
                    allocN32Pixels(imageWidth, 14)
                    erase(0xFFE0D4C0.toInt())
                }
                DesktopReaderImageAsset(
                    bitmap.asComposeImageBitmap(),
                    imageWidth,
                    14,
                    imageWidth * 56L,
                    false,
                    disposer = bitmap::close,
                )
            },
        )
        private val owner = DesktopReaderPresentationImageOwner(scope, pipeline, ReaderPageIoObserver(reporter))
        private val appPrefs = DesktopAppPreferences(DesktopPreferenceStore(root.node("app")), root.node("legacy-app"))
        private val dependencies = mockk<DesktopUiDependencies> { every { appPreferences } returns appPrefs }
        val scene = ImageComposeScene(width, 600, coroutineContext = scope.coroutineContext) {}
        var transform by mutableStateOf<(ReaderState) -> ReaderState>({ it })

        init {
            owner.beginGeneration(1L)
            model.state.value.session.activeChapter.pages.forEach { page ->
                model.acceptDualFact(
                    requireNotNull(model.captureDualFact(page.id)),
                    ReaderPageFact.dimensions(imageWidth, 14),
                )
            }
        }

        fun mount() {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                        Navigator(EmptyScreen) { navigator ->
                            val state by model.state.collectAsState()
                            val focus = remember { FocusRequester() }
                            LaunchedEffect(Unit) { focus.requestFocus() }
                            ReaderViewport(
                                transform(
                                    state,
                                ),
                                model, navigator, focus, scope, "Fixture", "Chapter", owner, null, {
                                }, {},
                            )
                        }
                    }
                }
            }
            frames()
        }

        fun frames() = repeat(20) {
            scene.render(scope.testScheduler.currentTime * 1_000_000).close()
            scope.runCurrent()
            scope.advanceTimeBy(16)
        }

        fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
        fun node(tag: String) = nodes().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == tag }
        fun hasBubble() = nodes().any {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" } ==
                "desktop-tutorial-reader-adjust-spread"
        }
        fun click(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, point, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
            frames()
        }

        fun key(down: Boolean = true, modifier: Int? = null) {
            val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
            val type = Class.forName("androidx.compose.ui.input.key.KeyEventType")
                .getMethod(if (down) "access\$getKeyDown\$cp" else "access\$getKeyUp\$cp").invoke(null)
            val factory = events.declaredMethods.single {
                it.name.startsWith("KeyEvent-") &&
                    !it.name.endsWith("\$default")
            }
            val native = factory.invoke(
                null, Key.C.keyCode, type, 0, modifier == 0, modifier == 1, modifier == 2,
                modifier == 3, null,
            )
            scene.sendKeyEvent(KeyEvent(native))
            frames()
        }

        fun capture(name: String) {
            val directory = File("build/reports/tutorial").apply { mkdirs() }
            scene.render(scope.testScheduler.currentTime * 1_000_000).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { File(directory, name).writeBytes(it.bytes) }
            }
        }

        override fun close() {
            scene.close()
            owner.close()
            pipeline.close()
            content.close()
            model.onDispose()
        }
    }

    private object EmptyScreen : Screen {
        @Composable override fun Content() = Unit
    }

    companion object {
        private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    }
}
