package mihon.desktop.ui.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.center
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.easteregg.AboutPigEasterEggInput
import mihon.desktop.easteregg.DesktopPigEasterEggController
import mihon.desktop.easteregg.KonamiCode
import mihon.desktop.easteregg.KonamiStep
import mihon.desktop.easteregg.PigEasterEggDefaults
import mihon.desktop.easteregg.PIG_EASTER_EGG_RESOURCE
import mihon.desktop.easteregg.gamepad.GamepadKonamiSource
import mihon.desktop.easteregg.key
import mihon.desktop.easteregg.loadPigEasterEggImage
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.platform.DesktopPlatformPaths
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.update.DesktopUpdateState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import kotlin.math.hypot
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalComposeUiApi::class)
@Isolated
class DesktopPigEasterEggOverlayTest {

    @Test
    fun `the konami code on the about screen shows seven spread pigs that disappear again`(
        @TempDir tempDir: java.nio.file.Path,
    ) = runBlocking {
        val fixture = Fixture(tempDir, coroutineContext)
        try {
            settle(fixture.scene)
            assertTrue(fixture.input.attached, "the about screen must listen for the code")
            assertTrue(pigs(fixture.scene).isEmpty(), "nothing may show before the code is entered")

            fixture.enterKonamiWithKeyboard()
            settle(fixture.scene) { pigs(fixture.scene).size == 7 }

            val shown = pigs(fixture.scene)
            assertEquals(7, shown.size)

            val width = SCENE_WIDTH
            val height = SCENE_HEIGHT
            // Rotation grows the reported bounds around each pig, so a pig may reach one pig
            // width past the window while its centre is still planted on screen.
            val rotationTolerance = height * PigEasterEggDefaults.WINDOW_FRACTION
            shown.forEach { pig ->
                val bounds = pig.boundsInRoot
                assertTrue(
                    bounds.left >= -rotationTolerance && bounds.right <= width + rotationTolerance,
                    "pig escaped the window: $bounds",
                )
                assertTrue(
                    bounds.top >= -rotationTolerance && bounds.bottom <= height + rotationTolerance,
                    "pig escaped the window: $bounds",
                )
            }
            val centers = shown.map { it.boundsInRoot.center }
            centers.forEach { center ->
                assertTrue(center.x in 0f..width && center.y in 0f..height, "pig landed outside the window: $center")
            }
            val closest = centers.indices.minOf { first ->
                centers.indices.filter { it != first }.minOf { second ->
                    hypot(centers[first].x - centers[second].x, centers[first].y - centers[second].y)
                }
            }
            assertTrue(closest > 60f, "pigs were only $closest pixels apart")

            settle(fixture.scene, timeout = 20.seconds) { pigs(fixture.scene).isEmpty() }

            fixture.enterKonamiWithKeyboard()
            settle(fixture.scene) { pigs(fixture.scene).size == 7 }
            assertEquals(7, pigs(fixture.scene).size, "the code must be repeatable")
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `gamepad presses trigger the same overlay`(@TempDir tempDir: java.nio.file.Path) = runBlocking {
        val fixture = Fixture(tempDir, coroutineContext)
        try {
            settle(fixture.scene)
            KonamiCode.forEach { fixture.gamepad.press(it) }
            settle(fixture.scene) { pigs(fixture.scene).size == 7 }
            assertEquals(7, pigs(fixture.scene).size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `the code stops working once the about screen is left`(@TempDir tempDir: java.nio.file.Path) = runBlocking {
        val fixture = Fixture(tempDir, coroutineContext)
        try {
            settle(fixture.scene)
            assertTrue(fixture.input.attached)

            fixture.navigate { it.replace(EmptyScreen()) }
            settle(fixture.scene)
            assertFalse(fixture.input.attached, "leaving the about screen must release the code listener")

            fixture.enterKonamiWithKeyboard()
            settle(fixture.scene)
            assertTrue(pigs(fixture.scene).isEmpty(), "the easter egg belongs to the about page only")
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `the pigs zoom in with an accelerating pop that finishes in about a tenth of a second`() {
        val entrance = PigAppearanceSpec.entrance

        assertEquals(100, entrance.durationMillis, "the pop must finish in about 0.1s")
        val easing = entrance.easing
        assertTrue(easing.transform(0.5f) < 0.5f, "an accelerating zoom stays behind the clock at halfway")
        assertTrue(easing.transform(0.75f) < 0.75f, "the zoom is still catching up late in the pop")
        assertEquals(1f, easing.transform(1f), "the pop must land exactly on its target")
        assertTrue(
            easing.transform(0.75f) - easing.transform(0.5f) > easing.transform(0.5f) - easing.transform(0.25f),
            "the last quarter of the pop must cover more ground than the first, that is acceleration",
        )

        val startScale = PigAppearanceSpec.scaleFor(0f)
        val midScale = PigAppearanceSpec.scaleFor(0.5f)
        assertTrue(midScale > startScale && midScale < 1f, "half way through the pop the pigs are still growing")
        assertEquals(1f, PigAppearanceSpec.scaleFor(1f), "the pop must end at full size")
    }

    @Test
    fun `the pigs fade out gently instead of vanishing`() {
        assertTrue(
            PigAppearanceSpec.exit.durationMillis > PigAppearanceSpec.entrance.durationMillis,
            "the exit is a soft fade, the entrance is a quick pop",
        )
        assertTrue(PigAppearanceSpec.exit.easing.transform(0.5f) > 0.5f, "the exit must settle instead of dropping")
        assertEquals(0f, PigAppearanceSpec.alphaFor(0f))
        assertEquals(1f, PigAppearanceSpec.alphaFor(1f))
    }

    @Test
    fun `the parade stays on screen for the whole requested lifetime`() {
        assertEquals(
            LIFETIME_MILLIS,
            PigAppearanceSpec.holdMillis(LIFETIME_MILLIS) + PigAppearanceSpec.exit.durationMillis,
            "hold time plus the exit fade must add up to the lifetime",
        )
        assertEquals(0L, PigAppearanceSpec.holdMillis(10L), "a lifetime shorter than the fade leaves nothing to hold")
    }

    @Test
    fun `the shipped pig image decodes`() {
        val pig = loadPigEasterEggImage()

        assertTrue(pig.width > 0 && pig.height > 0, "the packaged pig image is empty")
        val packaged = requireNotNull(
            javaClass.getResourceAsStream(PIG_EASTER_EGG_RESOURCE),
        ).use { it.readBytes() }
        assertTrue(
            packaged.take(PIG_PNG_SIGNATURE.size).toByteArray().contentEquals(PIG_PNG_SIGNATURE),
            "the easter egg asset must be the packaged PNG",
        )
    }

    private fun pigs(scene: ImageComposeScene) = nodes(scene).filter { tag(it) == DESKTOP_PIG_EASTER_EGG_TAG }

    private fun tag(node: SemanticsNode) =
        if (node.config.contains(SemanticsProperties.TestTag)) node.config[SemanticsProperties.TestTag] else null

    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private suspend fun settle(scene: ImageComposeScene, timeout: kotlin.time.Duration = 10.seconds, ready: () -> Boolean = { true }) {
        withTimeout(timeout) {
            while (true) {
                scene.render()
                yield()
                delay(10)
                if (ready()) {
                    repeat(3) {
                        scene.render()
                        yield()
                    }
                    return@withTimeout
                }
            }
        }
    }

    private class Fixture(tempDir: java.nio.file.Path, coroutineContext: CoroutineContext) {
        val easterEgg = DesktopPigEasterEggController()
        val gamepad = FakeGamepadSource()
        val input = AboutPigEasterEggInput(easterEgg) { gamepad }
        private val dependencies: DesktopUiDependencies
        val scene = ImageComposeScene(SCENE_WIDTH.toInt(), SCENE_HEIGHT.toInt(), coroutineContext = coroutineContext) {}
        private lateinit var navigator: Navigator

        init {
            val extensionManager = DesktopExtensionManager()
            val preferences = DesktopAppPreferences(InMemoryPreferenceStore())
            val network = mockk<DesktopNetworkHelper> {
                every { routeObservations } returns MutableStateFlow(emptyList())
                every { activeGlobalMode } returns preferences.globalNetworkMode.get()
                every { activeGlobalProxy } returns preferences.proxyRuntimeConfig()
            }
            val updateModel = mockk<DesktopUpdateScreenModel> {
                every { state } returns MutableStateFlow(DesktopUpdateState.Idle)
                every { feedback } returns MutableStateFlow(null)
            }
            dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
                every { extensionPresentationService } returns extensionManager
                every { appPreferences } returns preferences
                every { updateScreenModel } returns updateModel
                every { networkHelper } returns network
                every { networkRoutingPort } returns network
            }
            val paths = DesktopPlatformPaths.resolve("Linux", tempDir.toString(), emptyMap())
            scene.setContent {
                MaterialTheme {
                    // Mirrors the production window, which hands every key event to the about-page
                    // listener from the window root. The root has to hold focus for a scene to
                    // deliver key events at all, exactly like the real window root does.
                    val rootFocus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { rootFocus.requestFocus() }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .focusRequester(rootFocus)
                            .focusable()
                            .onPreviewKeyEvent(input::onKeyEvent),
                    ) {
                        CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                            Navigator(AboutScreen(paths, input)) { nav ->
                                navigator = nav
                                CurrentScreen()
                            }
                        }
                        DesktopPigEasterEggOverlay(easterEgg, lifetimeMillis = LIFETIME_MILLIS)
                    }
                }
            }
        }

        suspend fun enterKonamiWithKeyboard() {
            KonamiCode.forEach { step ->
                scene.sendKeyEvent(composeKeyEvent(step.key(), KeyEventType.KeyDown))
                scene.render()
                scene.sendKeyEvent(composeKeyEvent(step.key(), KeyEventType.KeyUp))
            }
        }

        suspend fun navigate(block: (Navigator) -> Unit) {
            block(navigator)
            scene.render()
        }

        fun close() {
            scene.close()
        }
    }

    private class FakeGamepadSource : GamepadKonamiSource {
        private var sink: ((KonamiStep) -> Unit)? = null

        override fun start(onStep: (KonamiStep) -> Unit) {
            sink = onStep
        }

        override fun stop() {
            sink = null
        }

        fun press(step: KonamiStep) {
            sink?.invoke(step)
        }
    }

    private class EmptyScreen : Screen {
        @Composable
        override fun Content() = Unit
    }

    private companion object {
        const val LIFETIME_MILLIS = 500L
        const val SCENE_WIDTH = 1_200f
        const val SCENE_HEIGHT = 800f
        val PIG_PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )

        fun composeKeyEvent(key: Key, type: KeyEventType): KeyEvent {
            val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
            val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
                .getMethod(if (type == KeyEventType.KeyDown) "access\$getKeyDown\$cp" else "access\$getKeyUp\$cp")
                .invoke(null)
            val factory = events.declaredMethods.single { it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default") }
            return KeyEvent(factory.invoke(null, key.keyCode, eventType, 0, false, false, false, false, null))
        }
    }
}
