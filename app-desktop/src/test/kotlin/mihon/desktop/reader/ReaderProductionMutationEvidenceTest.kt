package mihon.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Color
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.observability.ReaderIoEventType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
class ReaderProductionMutationEvidenceTest {

    @TempDir
    lateinit var tempDir: File

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutationCases")
    fun `mounted production presentation consumes only the injected canonical decoder`(
        case: MountedReaderPresentationCase,
    ) = runTest {
        val decodeKeys = CopyOnWriteArrayList<ReaderPageDecodeKey>()
        val delegate = SkiaDesktopReaderPageImageDecoder()
        val fixture = MountedReaderPresentationFixture(
            root = tempDir.resolve("canonical-${case}"),
            coroutineContext = currentCoroutineContext(),
            case = case,
            pageImageDecoder = DesktopReaderPageImageDecoder { bytes, key ->
                decodeKeys += key
                delegate.decode(bytes, key)
            },
        )
        try {
            fixture.releaseBackgroundGates()
            pumpUntil(fixture) {
                fixture.events().any { it.type == ReaderIoEventType.FIRST_PAGE_PRESENTED.name } &&
                    decodeKeys.map(ReaderPageDecodeKey::pageIndex).containsAll(fixture.visiblePageIndices)
            }

            assertEquals(
                fixture.visiblePageIndices,
                decodeKeys.map(ReaderPageDecodeKey::pageIndex).toSet(),
                "$case must decode only visible slots through the injected production decoder",
            )
            fixture.visiblePageIndices.forEach { pageIndex ->
                assertEquals(
                    1,
                    decodeKeys.count { it.pageIndex == pageIndex },
                    "$case must not regain a second decoder invocation for visible page $pageIndex",
                )
            }
            assertTrue(
                fixture.renderContainsFixturePixels(),
                "$case reported presentation without drawing the bytes accepted by the injected decoder",
            )
        } finally {
            fixture.close()
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("presentationModesOnDirectory")
    fun `mounted presentation cannot bypass a rejecting canonical decoder`(
        case: MountedReaderPresentationCase,
    ) = runTest {
        val decodeKeys = CopyOnWriteArrayList<ReaderPageDecodeKey>()
        val fixture = MountedReaderPresentationFixture(
            root = tempDir.resolve("rejecting-${case}"),
            coroutineContext = currentCoroutineContext(),
            case = case,
            pageImageDecoder = DesktopReaderPageImageDecoder { _, key ->
                decodeKeys += key
                null
            },
        )
        try {
            fixture.releaseBackgroundGates()
            pumpUntil(fixture) {
                decodeKeys.map(ReaderPageDecodeKey::pageIndex).containsAll(fixture.visiblePageIndices)
            }
            var bypassPixelsDrawn = false
            repeat(POST_REJECTION_RENDER_PUMPS) {
                bypassPixelsDrawn = bypassPixelsDrawn || fixture.renderContainsFixturePixels()
                runCurrent()
            }

            assertTrue(
                fixture.events().none { it.type == ReaderIoEventType.FIRST_PAGE_PRESENTED.name },
                "$case presented content after the canonical decoder rejected it; " +
                    "a private File/URI/image chain bypassed the owner",
            )
            assertTrue(
                !bypassPixelsDrawn,
                "$case drew source pixels after the canonical decoder rejected them; " +
                    "a private File/URI/image chain bypassed the owner",
            )
        } finally {
            fixture.close()
        }
    }

    private suspend fun TestScope.pumpUntil(
        fixture: MountedReaderPresentationFixture,
        complete: () -> Boolean,
    ) {
        var attempts = 0
        while (!complete() && attempts < MAX_RENDER_PUMPS) {
            fixture.scene.render()
            runCurrent()
            Thread.sleep(RENDER_PUMP_SCHEDULING_MILLIS)
            attempts += 1
        }
        assertTrue(complete(), "production mutation fixture did not reach its observation point after $attempts pumps")
    }

    private fun MountedReaderPresentationFixture.renderContainsFixturePixels(): Boolean {
        val rendered = scene.render().toComposeImageBitmap().asSkiaBitmap()
        return try {
            (0 until rendered.height).any { y ->
                (0 until rendered.width).any { x -> sameRgb(rendered.getColor(x, y), Color.BLUE.rgb) }
            }
        } finally {
            rendered.close()
        }
    }

    private fun sameRgb(first: Int, second: Int): Boolean = (first and RGB_MASK) == (second and RGB_MASK)

    companion object {
        private const val MAX_RENDER_PUMPS = 200
        private const val POST_REJECTION_RENDER_PUMPS = 10
        private const val RENDER_PUMP_SCHEDULING_MILLIS = 1L
        private const val RGB_MASK = 0x00FFFFFF

        @JvmStatic
        fun mutationCases(): List<MountedReaderPresentationCase> = listOf(
            mutationCase(MountedReaderPresentationMode.SINGLE, MountedReaderContentRoute.DIRECTORY),
            mutationCase(MountedReaderPresentationMode.DUAL, MountedReaderContentRoute.DIRECTORY),
            mutationCase(MountedReaderPresentationMode.WEBTOON, MountedReaderContentRoute.DIRECTORY),
            mutationCase(MountedReaderPresentationMode.SINGLE, MountedReaderContentRoute.CBZ),
            mutationCase(MountedReaderPresentationMode.SINGLE, MountedReaderContentRoute.ONLINE),
        )

        @JvmStatic
        fun presentationModesOnDirectory(): List<MountedReaderPresentationCase> =
            MountedReaderPresentationMode.entries.map { mode ->
                MountedReaderPresentationCase(mode, MountedReaderContentRoute.DIRECTORY)
            }

        private fun mutationCase(
            mode: MountedReaderPresentationMode,
            route: MountedReaderContentRoute,
        ) = MountedReaderPresentationCase(mode, route)
    }
}
