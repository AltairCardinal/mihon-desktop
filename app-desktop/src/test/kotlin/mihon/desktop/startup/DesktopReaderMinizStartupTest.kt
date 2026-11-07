package mihon.desktop.startup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import io.mockk.mockk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.DesktopOwnerStartup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DesktopReaderMinizStartupTest {
    @Test
    fun `optional backend starts once after real owner content draws and never from boot screens`() = runTest {
        val events = mutableListOf<String>()
        var permitDraw by mutableStateOf(false)
        val ready = DesktopOwnerBootstrapState.Ready(mockk(), mockk(), mockk<DesktopOwnerStartup>())
        val scene = ImageComposeScene(320, 240, coroutineContext = currentCoroutineContext()) {}
        try {
            listOf(DesktopOwnerBootstrapState.Preparing, DesktopOwnerBootstrapState.Failed(Exception("fixture")))
                .forEach { state ->
                    scene.setContent {
                        MaterialTheme {
                            DesktopOwnerBootstrapContent(state, onFirstUiDrawn = { events += "start" }) {}
                        }
                    }
                    scene.render().close()
                    runCurrent()
                    assertEquals(emptyList<String>(), events)
                }
            scene.setContent {
                Box(Modifier.fillMaxSize().drawWithContent { if (permitDraw) drawContent() }) {
                    DesktopOwnerBootstrapContent(ready, onFirstUiDrawn = { events += "start" }) {
                        Box(
                            Modifier.fillMaxSize().drawWithContent {
                                drawContent()
                                events += "draw"
                            },
                        )
                    }
                }
            }
            runCurrent()
            assertEquals(emptyList<String>(), events, "Composing ready UI must not start loading")
            repeat(3) {
                scene.render().close()
                runCurrent()
            }
            assertEquals(emptyList<String>(), events, "Frames without real UI draw must not start loading")
            permitDraw = true
            scene.render().close()
            runCurrent()
            assertEquals(listOf("draw", "start"), events, "Loading must follow the real content draw")
            repeat(3) {
                scene.render().close()
                runCurrent()
            }
            assertEquals(1, events.count { it == "start" })
        } finally {
            scene.close()
        }
    }
}
