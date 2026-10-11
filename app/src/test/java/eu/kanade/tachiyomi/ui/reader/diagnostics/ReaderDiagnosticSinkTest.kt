package eu.kanade.tachiyomi.ui.reader.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReaderDiagnosticSinkTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun utf8LimitPreservesCompleteCharactersAndCannotInjectLines() {
        val directory = temporary.newFolder()
        ReaderDiagnosticSink({ directory }, maxBytes = 32).use { it.offer("中文".repeat(20) + "\nforged") }
        val file = directory.listFiles()!!.single()
        val decoded = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(file.readBytes())).toString()
        assertTrue(file.length() <= 32)
        assertTrue(decoded.startsWith("中文"))
        assertTrue(decoded.count { it == '\n' } == 1)
    }

    @Test
    fun sessionAndSafeFieldsAreCapturedAtEmission() {
        val directory = temporary.newFolder()
        ReaderDiagnosticSink({ directory }).use { sink ->
            ReaderDiagnostics.sink = sink
            try {
                ReaderDiagnostics.record("read", 1, 2, mapOf("url" to "secret", "reason" to "bad\nforged"), "old")
                ReaderDiagnostics.record("reader_start", session = "new")
                ReaderDiagnostics.record("drawn", 1, 2, session = "old")
            } finally {
                ReaderDiagnostics.sink = null
            }
        }
        val lines = directory.listFiles()!!.single().readLines()
        assertTrue(lines.size == 3)
        assertTrue(lines.first().contains("reason=REDACTED"))
        assertTrue(lines.none { it.contains("secret") || it.contains("forged") })
        assertTrue(lines.last().contains("session=old"))
        assertTrue(lines.last().contains("chapter=1 sourceIndex=2"))
    }

    @Test
    fun appendsAcrossRestartAndRotatesWithinBudget() {
        val directory = temporary.newFolder()
        repeat(2) { run ->
            ReaderDiagnosticSink({ directory }, maxBytes = 512, fileCount = 4).use { sink ->
                repeat(30) { sink.offer("event=read run=$run index=$it " + "x".repeat(60)) }
            }
        }
        val files = directory.listFiles()!!.filter { it.extension == "log" }
        assertTrue("Diagnostic events must survive process restart", files.isNotEmpty())
        assertTrue(files.size <= 4)
        assertTrue(files.all { it.length() <= 512 })
        assertTrue(files.joinToString { it.readText() }.contains("run=1 index=29"))
        ReaderDiagnosticSink({ directory }, maxBytes = 512, fileCount = 4).use { it.offer("event=restart") }
        assertTrue(directory.listFiles()!!.any { it.readText().contains("event=restart") })
        assertTrue(directory.listFiles()!!.any { it.readText().contains("run=1 index=29") })
    }

    @Test
    fun saturatedQueueDoesNotWaitAndReportsDroppedEvents() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val directory = temporary.newFolder()
        val sink = ReaderDiagnosticSink({
            entered.countDown()
            release.await()
            directory
        }, capacity = 2)
        sink.offer("event=first")
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val start = System.nanoTime()
            repeat(10000) { sink.offer("event=queued") }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000)
        } finally {
            release.countDown()
            sink.close()
        }
        assertTrue(directory.listFiles()!!.joinToString { it.readText() }.contains("event=dropped"))
    }

    @Test
    fun filesystemFailureIsIsolatedAndCounted() {
        val file = temporary.newFile()
        ReaderDiagnosticSink({ File(file, "impossible") }).use { sink ->
            sink.offer("event=read")
            sink.close()
            assertTrue(sink.failures.get() > 0)
        }
    }

    @Test
    fun writerRecoversAfterDirectoryFailureAndReportsTheGap() {
        val failed = CountDownLatch(1)
        val directory = temporary.newFolder()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        ReaderDiagnosticSink(
            directory = { if (calls.getAndIncrement() == 0) error("unavailable") else directory },
            onFailure = { failed.countDown() },
        ).use { sink ->
            sink.offer("event=first")
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            sink.offer("event=recovered")
        }
        val text = directory.listFiles()!!.joinToString { it.readText() }
        assertTrue(text.contains("event=write_failure count=1"))
        assertTrue(text.contains("event=recovered"))
    }
}
