package mihon.desktop.ui.refresh

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.nativeKeyLocation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.domain.library.service.TwoStageRefreshGesture.Stage
import java.awt.event.MouseWheelEvent
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
@Isolated
class DesktopRefreshGestureInputTest {
    @Test
    fun `content focus departure revokes armed wheel without refreshing`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            InputScene(coroutineContext, focusControls = true).use { s ->
                s.render()
                s.contentFocus.requestFocus()
                s.render()
                s.wheel(-4f)
                s.render()
                assertEquals(Stage.ARMED, s.controller.stage)
                s.key(androidx.compose.ui.input.key.Key.Home)
                s.render()
                assertEquals(Stage.IDLE, s.controller.stage)
                s.wheel(-4f)
                s.render()
                assertEquals(Stage.ARMED, s.controller.stage)
                s.key(androidx.compose.ui.input.key.Key.PageUp)
                s.render()
                assertEquals(Stage.IDLE, s.controller.stage)
                s.wheel(-4f)
                s.render()
                assertEquals(Stage.ARMED, s.controller.stage)
                s.outsideFocus.requestFocus()
                s.render()
                assertEquals(Stage.IDLE, s.controller.stage, "Focus leaves the actual content subtree")
                assertEquals(0, s.requests)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `real platform wheel normalizes density viewport and fractional AWT input`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            for (density in listOf(1f, 2f)) {
                InputScene(coroutineContext, density).use { s ->
                    s.render()
                    repeat(3) {
                        s.wheel(-1f)
                        s.render()
                    }
                    assertEquals(Stage.HINTING, s.controller.stage, "Three native unit events remain below 80dp")
                    s.wheel(-1f)
                    s.render()
                    assertEquals(Stage.ARMED, s.controller.stage)
                    s.controller.cancel()
                    repeat(8) {
                        s.wheel(-.25f)
                        s.render()
                    }
                    assertEquals(Stage.HINTING, s.controller.stage, "Precision deltas are not rounded to whole notches")
                    s.wheel(-2f)
                    s.render()
                    assertEquals(Stage.ARMED, s.controller.stage)
                    assertEquals(0, s.requests)
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `middle to top wheel modifiers and consumed panel never arm content`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            InputScene(coroutineContext, longList = true).use { s ->
                s.render()
                s.list.scrollToItem(20)
                s.render()
                s.wheel(-100f)
                kotlinx.coroutines.withTimeout(2000) {
                    while (s.list.canScrollBackward) {
                        s.render()
                        delay(10)
                    }
                }
                s.render()
                assertFalse(s.list.canScrollBackward)
                assertEquals(Stage.IDLE, s.controller.stage, "The wheel that first reaches the top cannot arm")
                s.wheel(-4f)
                s.render()
                assertEquals(Stage.ARMED, s.controller.stage)
                for (mod in listOf(
                    PointerKeyboardModifiers(isCtrlPressed = true),
                    PointerKeyboardModifiers(isAltPressed = true),
                    PointerKeyboardModifiers(isShiftPressed = true),
                )) {
                    s.wheel(-4f, mod)
                    s.render()
                    assertEquals(Stage.IDLE, s.controller.stage)
                }
                s.wheel(-4f, horizontal = true)
                s.render()
                assertEquals(Stage.IDLE, s.controller.stage)
                s.consume = true
                s.render()
                s.wheel(-4f)
                s.render()
                assertEquals(Stage.IDLE, s.controller.stage, "Consumed panel wheel stays with the panel")
                s.consume = false
                s.render()
                s.wheel(-4f)
                s.render()
                assertEquals(Stage.ARMED, s.controller.stage)
                s.enabled = false
                s.render()
                assertEquals(Stage.IDLE, s.controller.stage)
                assertEquals(0, s.requests)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `actual programmatic content movement extends post completion quiet time`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            InputScene(coroutineContext, longList = true).use { s ->
                s.render()
                s.wheel(-4f)
                s.render()
                s.now = 500
                s.wheel(-3f)
                s.render()
                assertEquals(1, s.requests)
                s.controller.complete()
                s.now = 1200
                s.list.scrollToItem(10)
                s.render()
                s.now = 1300
                s.controller.tick()
                assertEquals(Stage.COOLDOWN, s.controller.stage, "A real position change restarts the 800ms silence")
                s.now = 1999
                s.controller.tick()
                assertEquals(Stage.COOLDOWN, s.controller.stage)
                s.now = 2000
                s.controller.tick()
                assertEquals(Stage.IDLE, s.controller.stage)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class InputScene(
        context: CoroutineContext,
        density: Float = 1f,
        longList: Boolean = false,
        focusControls: Boolean = false,
    ) : AutoCloseable {
        var now = 0L
        var requests = 0
        var enabled by mutableStateOf(true)
        var consume by mutableStateOf(false)
        val contentFocus = FocusRequester()
        val outsideFocus = FocusRequester()
        lateinit var list: LazyListState
        val controller = RefreshGestureController(now = { now }) { requests++ }
        private val canvas = Canvas(ImageBitmap(900, 900))
        private val size = IntSize((400 * density).toInt(), (400 * density).toInt())
        private val scene = CanvasLayersComposeScene(size = size, coroutineContext = context, invalidate = {})
        init {
            scene.setContent {
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    MaterialTheme {
                        list = rememberLazyListState()
                        Box(Modifier.fillMaxSize()) {
                            RefreshGestureContent(
                                controller,
                                enabled,
                                !list.canScrollBackward,
                                "Native content",
                                Modifier.fillMaxSize(),
                                activityKey = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset,
                            ) {
                                Box(
                                    Modifier.fillMaxSize().pointerInput(consume) {
                                        if (consume) {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach {
                                                        it.consume()
                                                    }
                                                }
                                            }
                                        }
                                    },
                                ) {
                                    LazyColumn(Modifier.fillMaxSize(), state = list) {
                                        items(if (longList) 100 else 1) {
                                            Text(
                                                "Chapter $it",
                                                Modifier.height(40.dp).then(
                                                    if (focusControls &&
                                                        it == 0
                                                    ) {
                                                        Modifier.focusRequester(contentFocus).focusable()
                                                    } else {
                                                        Modifier
                                                    },
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                            if (focusControls) Text("Outside action", Modifier.focusRequester(outsideFocus).focusable())
                        }
                    }
                }
            }
        }
        suspend fun render() {
            repeat(3) {
                scene.render(canvas, System.nanoTime())
                delay(10)
            }
        }
        fun wheel(
            delta: Float,
            mods: PointerKeyboardModifiers = PointerKeyboardModifiers(),
            horizontal: Boolean = false,
        ) {
            val native = MouseWheelEvent(
                java.awt.Canvas(), java.awt.event.MouseEvent.MOUSE_WHEEL, 0, 0,
                100, 100, 100, 100, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, delta.toInt(), delta.toDouble(),
            )
            scene.sendPointerEvent(
                PointerEventType.Scroll,
                Offset(200f, 200f),
                scrollDelta = if (horizontal) Offset(delta, 0f) else Offset(0f, delta),
                keyboardModifiers = mods,
                nativeEvent = native,
            )
        }
        fun key(key: androidx.compose.ui.input.key.Key) {
            val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
            val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            val factory = events.declaredMethods.single {
                it.name.startsWith("KeyEvent-") &&
                    !it.name.endsWith("\$default")
            }
            for (type in listOf("access\$getKeyDown\$cp", "access\$getKeyUp\$cp")) {
                val value = eventType.getMethod(type).invoke(null)
                scene.sendKeyEvent(
                    androidx.compose.ui.input.key.KeyEvent(
                        factory.invoke(
                            null, key.keyCode, value, key.nativeKeyLocation,
                            false, false, false, false, null,
                        ),
                    ),
                )
            }
        }
        override fun close() = scene.close()
    }
}
