package mihon.desktop.ui.reader

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.desktopReaderSessionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

@OptIn(ExperimentalComposeUiApi::class)
class ReaderKeyboardFocusIntegrationTest {

    @Test
    fun `space toggles reader bars without turning pages in every reading mode`() = runBlocking {
        for (mode in ReadingMode.entries) {
            for (dual in listOf(false, true)) {
                val model = ReaderScreenModel(
                    initialSessionState = desktopReaderSessionState(pageCount = 7, initialPage = 2),
                    dualPageOverride = dual,
                    prefs = ReaderPreferences(InMemoryPreferenceStore()).apply {
                        readingMode = mode
                        isAutoSpreadMatching = false
                    },
                )
                val scene = ImageComposeScene(240, 240, coroutineContext = coroutineContext) {}
                val navigator = mockk<Navigator>()
                var chapterTurns = 0
                try {
                    scene.setContent {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        Box(
                            Modifier.size(240.dp).readerKeyboardFocus(focus) { event ->
                                handleReaderKeyEvent(
                                    event, model.state.value, model, navigator, null,
                                    onPrevChapter = { chapterTurns++ },
                                    onNextChapter = { chapterTurns++ },
                                )
                            },
                        )
                    }
                    render(scene)
                    val initial = model.state.value
                    repeat(2) { press ->
                        assertTrue(scene.sendKeyEvent(composeKeyEvent(Key.Spacebar, KeyEventType.KeyDown)))
                        render(scene)
                        val expectedVisible = if (press == 0) !initial.showUI else initial.showUI
                        assertEquals(expectedVisible, model.state.value.showUI, "$mode dual=$dual press=$press")
                        scene.sendKeyEvent(composeKeyEvent(Key.Spacebar, KeyEventType.KeyUp))
                        render(scene)
                        assertEquals(expectedVisible, model.state.value.showUI, "KeyUp must not toggle bars")
                        assertEquals(initial.currentPage, model.state.value.currentPage, "$mode dual=$dual")
                        assertEquals(initial.session, model.state.value.session, "Space must preserve the chapter")
                        assertEquals(0, chapterTurns)
                    }
                } finally {
                    scene.close()
                    model.onDispose()
                }
            }
        }
    }

    @Test
    fun `mouse press returns keyboard navigation focus to reader viewport`() = runBlocking {
        val scene = ImageComposeScene(240, 240, coroutineContext = coroutineContext) {}
        var pageTurns = 0
        lateinit var competingFocus: FocusRequester
        try {
            scene.setContent {
                val readerFocus = remember { FocusRequester() }
                competingFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { readerFocus.requestFocus() }
                Column {
                    Box(
                        Modifier
                            .size(120.dp)
                            .readerKeyboardFocus(readerFocus) { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                    pageTurns++
                                    true
                                } else {
                                    false
                                }
                            },
                    )
                    Box(
                        Modifier
                            .size(120.dp)
                            .focusRequester(competingFocus)
                            .focusable(),
                    )
                }
            }
            render(scene)

            scene.sendKeyEvent(composeKeyEvent(Key.DirectionRight, KeyEventType.KeyDown))
            assertEquals(1, pageTurns)

            competingFocus.requestFocus()
            render(scene)
            scene.sendKeyEvent(composeKeyEvent(Key.DirectionRight, KeyEventType.KeyDown))
            assertEquals(1, pageTurns)

            scene.sendPointerEvent(PointerEventType.Press, Offset(60f, 60f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(60f, 60f))
            render(scene)
            scene.sendKeyEvent(composeKeyEvent(Key.DirectionRight, KeyEventType.KeyDown))

            assertEquals(2, pageTurns)
        } finally {
            scene.close()
        }
    }

    private fun composeKeyEvent(key: Key, type: KeyEventType): androidx.compose.ui.input.key.KeyEvent {
        val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
        val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            .getMethod(if (type == KeyEventType.KeyDown) "access\$getKeyDown\$cp" else "access\$getKeyUp\$cp")
            .invoke(null)
        val factory = events.declaredMethods.single { it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default") }
        val native = factory.invoke(null, key.keyCode, eventType, 0, false, false, false, false, null)
        return androidx.compose.ui.input.key.KeyEvent(native)
    }

    private suspend fun render(scene: ImageComposeScene) = repeat(6) {
        scene.render()
        yield()
    }
}
