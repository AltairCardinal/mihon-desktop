package mihon.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ReaderStartupObservationTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `observation is bounded and disabled bridge records nothing`() {
        val observation = ReaderStartupObservation(2)
        repeat(4) { observation.record("event-$it") }
        assertEquals(2L, observation.snapshot().dropped)
        assertEquals(listOf("event-0", "event-1"), observation.snapshot().events.map { it.stage })
        ReaderStartupTrace.install(observation)
        ReaderStartupTrace.clear(observation)
        ReaderStartupTrace.mark("disabled")
        assertEquals(2L, observation.snapshot().dropped)
    }

    @Test
    fun `natural mounted production chain records read decode holder and apply boundaries`() = runTest {
        val fixture = MountedReaderPresentationFixture(
            tempDir, currentCoroutineContext(),
            MountedReaderPresentationCase(MountedReaderPresentationMode.SINGLE, MountedReaderContentRoute.CBZ),
            observeStartup = true,
        )
        try {
            fixture.releaseBackgroundGates()
            var pumps = 0
            while (fixture.startupSnapshot()!!.events.none { it.stage == "FIRST_PAGE_PRESENTED" } && pumps++ < 200) {
                fixture.scene.render().close()
                runCurrent()
                Thread.sleep(1)
            }
            val snapshot = fixture.startupSnapshot()!!
            assertEquals(0L, snapshot.dropped)
            val stages = snapshot.events.map { it.stage }
            listOf("COMPOSE_SLOT_APPLY", "HOLDER_ACQUIRE_REQUEST", "CONTENT_IO_BEGIN", "CONTENT_IO_END",
                "DECODER_CALL_BEGIN", "DECODER_CALL_END", "HOLDER_READY", "FIRST_PAGE_PRESENTED").forEach {
                assertTrue(it in stages, "Missing real production stage $it: $stages")
            }
            assertTrue(stages.indexOf("CONTENT_IO_BEGIN") < stages.indexOf("CONTENT_IO_END"))
            assertTrue(stages.indexOf("DECODER_CALL_BEGIN") < stages.indexOf("DECODER_CALL_END"))
            assertTrue(stages.indexOf("DECODER_CALL_END") < stages.indexOf("FIRST_PAGE_PRESENTED"))
        } finally {
            fixture.close()
        }
    }
}
