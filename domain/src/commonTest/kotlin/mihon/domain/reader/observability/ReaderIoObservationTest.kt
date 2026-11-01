package mihon.domain.reader.observability

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import tachiyomi.core.common.feedback.DiagnosticCategory
import tachiyomi.core.common.feedback.DiagnosticEvents
import tachiyomi.core.common.feedback.DiagnosticPersistence
import tachiyomi.core.common.feedback.DiagnosticSession
import tachiyomi.core.common.feedback.DiagnosticStore

class ReaderIoObservationTest {
    @Test
    fun `normal reader probe records only the explicitly enabled private category`() = kotlinx.coroutines.runBlocking {
        val root = java.nio.file.Files.createTempDirectory("reader-diagnostics-").toFile()
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
        )
        val store = DiagnosticStore(
            root,
            object : DiagnosticPersistence {
                override fun load(): DiagnosticSession? = null
                override fun save(session: DiagnosticSession?) = Unit
            },
            scope,
        )
        DiagnosticEvents.install(store)
        try {
            var probeCalls = 0
            var clockReads = 0
            val reporter = ReaderIoReporter(
                probe = object : ReaderIoProbe {
                    override val enabled = false
                    override fun record(event: ReaderIoEvent) {
                        probeCalls++
                    }
                },
                clock = ReaderMonotonicClock {
                    clockReads++
                    42
                },
            )
            assertFalse(reporter.enabled)
            store.start(DiagnosticCategory.READER)
            org.junit.jupiter.api.Assertions.assertTrue(reporter.enabled)
            reporter.report(
                ReaderIoEventType.DECODE,
                ReaderChapterId(7123456),
                generation = 1,
                purpose = ReaderIoPurpose.VISIBLE_DECODE,
            )
            store.flush()
            val date = store.dates(DiagnosticCategory.READER).single()
            val content = requireNotNull(store.read(DiagnosticCategory.READER, date)).content
            org.junit.jupiter.api.Assertions.assertTrue(content.contains("DECODE"))
            assertFalse(content.contains("7123456"))
            assertEquals(0, probeCalls, "Private diagnostics must not enable a disabled external probe")
            assertEquals(0, clockReads, "Private diagnostics do not need the external probe clock")
            store.stop()
            assertFalse(reporter.enabled)
        } finally {
            DiagnosticEvents.install(null)
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `reporter stamps stable reader identity with the monotonic clock`() {
        val events = mutableListOf<ReaderIoEvent>()
        val reporter = ReaderIoReporter(
            probe = ReaderIoProbe(events::add),
            clock = ReaderMonotonicClock { 42L },
        )
        val pageId = ReaderPageId(ReaderChapterId(7L), 3)

        reporter.report(
            type = ReaderIoEventType.OPEN_PAGE,
            chapterId = pageId.chapterId,
            pageId = pageId,
            generation = 11L,
            purpose = ReaderIoPurpose.CURRENT_PAGE,
        )

        assertEquals(
            listOf(
                ReaderIoEvent(
                    type = ReaderIoEventType.OPEN_PAGE,
                    monotonicNanos = 42L,
                    chapterId = ReaderChapterId(7L),
                    pageId = pageId,
                    generation = 11L,
                    purpose = ReaderIoPurpose.CURRENT_PAGE,
                ),
            ),
            events,
        )
    }

    @Test
    fun `disabled reporter neither reads the clock nor allocates an event`() {
        var clockRead = false
        val reporter = ReaderIoReporter(
            probe = ReaderIoProbe.None,
            clock = ReaderMonotonicClock {
                clockRead = true
                42L
            },
        )

        reporter.report(
            type = ReaderIoEventType.OPEN_READER_INTENT,
            chapterId = ReaderChapterId(7L),
            generation = 1L,
            purpose = ReaderIoPurpose.READER_OPEN,
        )

        assertFalse(clockRead)
    }
}
