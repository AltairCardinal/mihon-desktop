package mihon.desktop.ui.reader.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import mihon.desktop.reader.ReaderStartupObservation
import mihon.desktop.reader.ReaderStartupTrace
import mihon.desktop.reader.ZoomState
import mihon.desktop.reader.readerChapterSession
import mihon.desktop.ui.reader.LocalReaderPageImageContent
import mihon.desktop.ui.reader.ReaderFirstCompositionHost
import mihon.desktop.ui.reader.ReaderPageImageContentRegistry
import mihon.desktop.ui.reader.ZoomablePagerViewer
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderPageLoadState
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalComposeUiApi::class)
class ReaderFirstCompositionTest {
    @Test
    fun `first composition consumes published target before constructing full model`() = runTest {
        val chapter = readerChapterSession(41, 11, 187) { ReaderPageLoadState.Ready }.let { chapter ->
            chapter.copy(
                pages = chapter.pages.map {
                    it.copy(encodedPageRef = EncodedPageRef("page-${it.id.sourcePageIndex}"))
                },
            )
        }
        val target = chapter.pages[4].id
        val observation = ReaderStartupObservation()
        ReaderStartupTrace.install(observation)
        val fixture = PresentationImageOwnerFixture(this)
        fixture.owner.beginGeneration(11)
        val registry = ReaderPageImageContentRegistry()
        var startup by mutableStateOf(true)
        val scene = ImageComposeScene(640, 480, coroutineContext = currentCoroutineContext()) {}
        try {
            scene.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalReaderPageImageContent provides registry) {
                        ReaderFirstCompositionHost(useStartup = startup, startupContent = {
                            ZoomablePagerViewer(
                                chapter = chapter, currentPage = 4, isRtl = true, isDualPage = false,
                                zoomState = ZoomState(), presentationImageOwner = fixture.owner,
                                onPageChange = {
                                }, onZoomChange = {}, allowAdjacentViewport = false, startupSingleViewport = true,
                            )
                        }) {
                            ReaderStartupTrace.mark("TEST_FULL_MODEL_CREATE", target, 11)
                        }
                    }
                }
            }
            scene.render().close()
            startup = false
            repeat(8) {
                scene.render().close()
                yield()
            }
            val events = observation.snapshot().events
            val holder = events.indexOfFirst { it.stage == "COMPOSE_SLOT_APPLY" && it.pageIndex == 4 }
            val model = events.indexOfFirst { it.stage == "TEST_FULL_MODEL_CREATE" }
            assertTrue(
                holder >= 0 && model > holder,
                "The real target holder must apply before full-model construction, got holder=$holder model=$model",
            )
        } finally {
            scene.close()
            fixture.close()
            ReaderStartupTrace.clear(observation)
        }
    }
}
