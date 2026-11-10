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
    fun `warm session tokens and full decode identity respect disabled observation`() = runTest {
        fun session() = DesktopReaderSession(
            initialContext = desktopReaderSessionState(chapterId = 41, pageCount = 1).context,
            core = mihon.domain.reader.session.ReaderSessionCore(
                mihon.domain.reader.session.ReaderChapterId(41),
                sessionId = "diagnostic-warm",
            ),
            encodedPageStore = DesktopReaderEncodedPageStore(tempDir),
            chapterContentPortFactory = io.mockk.mockk(),
            pageFetchPortFactory = io.mockk.mockk(),
            progressPort = io.mockk.mockk(),
            parentScope = this,
        )
        val first = session()
        val second = session()
        try {
            assertEquals(first.state.value.snapshot.generation, second.state.value.snapshot.generation)
            org.junit.jupiter.api.Assertions.assertNotEquals(
                first.startupObservationToken,
                second.startupObservationToken,
            )
            val page = readerChapterSession(41, 1, 1) { mihon.domain.reader.session.ReaderPageLoadState.Ready }
                .pages.single().copy(encodedPageRef = mihon.domain.reader.session.EncodedPageRef("diagnostic-ref"))
            val key = mihon.desktop.ui.reader.readerPresentationDecodeKey(page, 1)
            val observation = ReaderStartupObservation()
            ReaderStartupTrace.install(observation)
            ReaderStartupTrace.clear(observation)
            ReaderStartupTrace.markDecode("off", key, openingToken = first.startupObservationToken)
            assertTrue(observation.snapshot().events.isEmpty())
            ReaderStartupTrace.install(observation)
            try {
                ReaderStartupTrace.markDecode("on", key, openingToken = second.startupObservationToken)
                val event = observation.snapshot().events.single()
                assertEquals(second.startupObservationToken, event.openingToken)
                assertEquals(key.toString(), event.contentIdentity)
                assertEquals(page.attemptGeneration, event.attemptGeneration)
            } finally {
                ReaderStartupTrace.clear(observation)
            }
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `natural mounted production chain records read decode holder and apply boundaries`() = runTest {
        val fixture = MountedReaderPresentationFixture(
            tempDir,
            currentCoroutineContext(),
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
            listOf(
                "ENCODED_FIND_BEGIN",
                "ENCODED_FIND_RETURN",
                "ENCODED_FETCH_BEGIN",
                "ENCODED_STORE_CALL",
                "ENCODED_WRITER_BEGIN",
                "COMPOSE_SLOT_APPLY",
                "HOLDER_ACQUIRE_REQUEST",
                "CONTENT_IO_BEGIN",
                "CONTENT_IO_END",
                "DECODER_CALL_BEGIN",
                "DECODER_CALL_END",
                "HOLDER_READY",
                "FIRST_PAGE_PRESENTED",
            ).forEach {
                assertTrue(it in stages, "Missing real production stage $it: $stages")
            }
            val writer = snapshot.events.first { it.stage == "ENCODED_WRITER_BEGIN" }
            assertTrue(writer.openingToken != null)
            assertTrue(writer.runnerRequestId != null)
            assertTrue(writer.contentIdentity != null)
            val taken = snapshot.events.single {
                it.stage == "RUNNER_WORK_TAKEN" && it.runnerRequestId == writer.runnerRequestId &&
                    it.openingToken == writer.openingToken
            }
            assertEquals(taken.generation, writer.generation)
            assertEquals(taken.attemptGeneration, writer.attemptGeneration)
            assertEquals(taken.pageIndex, writer.pageIndex)
            val route = snapshot.events.single {
                it.stage == "ENCODED_ARCHIVE_ROUTE" && it.runnerRequestId == writer.runnerRequestId &&
                    it.openingToken == writer.openingToken
            }
            assertTrue(route.contentIdentity!!.contains(tempDir.toURI().toString()))
            assertTrue(route.contentIdentity.endsWith("Chapter%201.cbz"))
            val boundaries = listOf(
                "ENCODED_FIND_BEGIN",
                "ENCODED_FIND_RETURN",
                "ENCODED_FETCH_BEGIN",
                "ENCODED_STORE_CALL",
                "ENCODED_WRITER_BEGIN",
                "ENCODED_STORE_RETURN",
            ).map { stage ->
                snapshot.events.single {
                    it.stage == stage && it.runnerRequestId == writer.runnerRequestId &&
                        it.openingToken == writer.openingToken
                }.monotonicNanos
            }
            assertEquals(boundaries.sorted(), boundaries)
            assertTrue(stages.indexOf("CONTENT_IO_BEGIN") < stages.indexOf("CONTENT_IO_END"))
            assertTrue(stages.indexOf("DECODER_CALL_BEGIN") < stages.indexOf("DECODER_CALL_END"))
            assertTrue(stages.indexOf("DECODER_CALL_END") < stages.indexOf("FIRST_PAGE_PRESENTED"))
        } finally {
            fixture.close()
        }
    }
}
