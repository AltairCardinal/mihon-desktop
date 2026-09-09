package mihon.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.test.http.ReaderIoTestEvent
import mihon.domain.reader.observability.ReaderIoEventType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
class ReaderPresentationPipelineProductionTest {

    @TempDir
    lateinit var tempDir: File

    @ParameterizedTest(name = "{0}")
    @MethodSource("presentationCases")
    fun `mounted production presentation keeps visible and adjacent pager slots within one decode window`(
        case: MountedReaderPresentationCase,
    ) = runTest {
        val fixture = MountedReaderPresentationFixture(
            root = tempDir.resolve(case.toString()),
            coroutineContext = currentCoroutineContext(),
            case = case,
        )
        try {
            runCurrent()
            assertEquals(
                0,
                fixture.events().countType(ReaderIoEventType.FIRST_PAGE_PRESENTED),
                "FIRST_PAGE_PRESENTED must not be reported before ImageComposeScene performs a draw pass",
            )
            fixture.releaseBackgroundGates()

            var renderPumps = 0
            while (!fixture.hasPresentedAndMaterializedEveryVisibleSlot() && renderPumps < MAX_RENDER_PUMPS) {
                fixture.scene.render()
                runCurrent()
                Thread.sleep(RENDER_PUMP_SCHEDULING_MILLIS)
                renderPumps += 1
            }
            assertTrue(
                fixture.hasPresentedAndMaterializedEveryVisibleSlot(),
                "$case did not present and materialize every visible slot after $renderPumps render pumps; " +
                    "events=${fixture.events().summary()}",
            )

            val events = fixture.events()
            val firstPresented = events.first { it.type == ReaderIoEventType.FIRST_PAGE_PRESENTED.name }
            val presentationGeneration = requireNotNull(firstPresented.generation)
            fixture.visiblePageIndices.forEach { pageIndex ->
                assertEquals(
                    1,
                    events.countPageEvent(ReaderIoEventType.OPEN_PAGE, pageIndex, presentationGeneration),
                    "$case must OPEN visible page $pageIndex exactly once",
                )
                assertEquals(
                    1,
                    events.countPageEvent(ReaderIoEventType.DECODE, pageIndex, presentationGeneration),
                    "$case must DECODE visible page $pageIndex exactly once",
                )
            }

            (fixture.allPageIndices - fixture.mountedPageIndices).forEach { pageIndex ->
                assertEquals(
                    0,
                    events.countPageEvent(ReaderIoEventType.OPEN_PAGE, pageIndex, presentationGeneration),
                    "$case must not OPEN an unrelated page $pageIndex",
                )
                assertEquals(
                    0,
                    events.countPageEvent(ReaderIoEventType.DECODE, pageIndex, presentationGeneration),
                    "$case must not DECODE an unrelated page $pageIndex",
                )
            }

            (fixture.mountedPageIndices - fixture.visiblePageIndices).forEach { pageIndex ->
                assertTrue(
                    events.countPageEvent(ReaderIoEventType.OPEN_PAGE, pageIndex, presentationGeneration) <= 1,
                    "$case must not OPEN adjacent page $pageIndex more than once",
                )
                assertTrue(
                    events.countPageEvent(ReaderIoEventType.DECODE, pageIndex, presentationGeneration) <= 1,
                    "$case must not DECODE adjacent page $pageIndex more than once",
                )
            }

            assertTrue(
                firstPresented.pageIndex in fixture.visiblePageIndices,
                "$case presented a page outside the visible slots: ${firstPresented.pageIndex}",
            )
            val presentedDecodeIndex = events.indexOfFirst {
                it.type == ReaderIoEventType.DECODE.name &&
                    it.chapterId == MountedReaderPresentationFixture.CHAPTER_ID &&
                    it.pageIndex == firstPresented.pageIndex &&
                    it.generation == firstPresented.generation
            }
            assertTrue(presentedDecodeIndex >= 0, "$case presented a page without a decoded asset")
            assertTrue(
                presentedDecodeIndex < events.indexOf(firstPresented),
                "$case reported FIRST_PAGE_PRESENTED before its decoded asset participated in draw",
            )
        } finally {
            fixture.close()
        }
    }

    private fun MountedReaderPresentationFixture.hasPresentedAndMaterializedEveryVisibleSlot(): Boolean {
        val events = events()
        val firstPresented = events.firstOrNull { it.type == ReaderIoEventType.FIRST_PAGE_PRESENTED.name }
            ?: return false
        val generation = firstPresented.generation ?: return false
        return visiblePageIndices.all { pageIndex ->
            events.countPageEvent(ReaderIoEventType.OPEN_PAGE, pageIndex, generation) >= 1 &&
                events.countPageEvent(ReaderIoEventType.DECODE, pageIndex, generation) >= 1
        }
    }

    private fun List<ReaderIoTestEvent>.countType(type: ReaderIoEventType): Int =
        count { it.type == type.name }

    private fun List<ReaderIoTestEvent>.countPageEvent(
        type: ReaderIoEventType,
        pageIndex: Int,
        generation: Long,
    ): Int =
        count {
            it.type == type.name &&
                it.chapterId == MountedReaderPresentationFixture.CHAPTER_ID &&
                it.pageIndex == pageIndex &&
                it.generation == generation
        }

    private fun List<ReaderIoTestEvent>.summary(): String =
        joinToString(prefix = "[", postfix = "]") { event ->
            "${event.type}(chapter=${event.chapterId},page=${event.pageIndex},generation=${event.generation})"
        }

    companion object {
        private const val MAX_RENDER_PUMPS = 200
        private const val RENDER_PUMP_SCHEDULING_MILLIS = 1L

        @JvmStatic
        fun presentationCases(): List<MountedReaderPresentationCase> =
            MountedReaderPresentationMode.entries.flatMap { mode ->
                MountedReaderContentRoute.entries.map { route ->
                    MountedReaderPresentationCase(mode, route)
                }
            }
    }
}
