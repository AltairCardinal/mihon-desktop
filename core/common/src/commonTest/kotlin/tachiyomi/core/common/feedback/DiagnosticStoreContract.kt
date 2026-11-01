package tachiyomi.core.common.feedback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId

abstract class DiagnosticStoreContract {
    @Test
    fun `temporary session has one shared strict metadata serialization`() {
        val session = DiagnosticSession("123e4567-e89b-42d3-a456-426614174111", DiagnosticCategory.READER, 42, true)
        val encoded = Json.encodeToString(session)
        assertEquals(session, Json.decodeFromString<DiagnosticSession>(encoded))
        assertFalse(encoded.contains("description"))
        assertThrows(Exception::class.java) {
            Json.decodeFromString<DiagnosticSession>(encoded.dropLast(1) + ",\"body\":\"private\"}")
        }
    }

    @Test
    fun `safe crash codec preserves actual timestamps and refuses raw mixed or credential reports`() {
        val error = IllegalStateException("private message")
        error.stackTrace = arrayOf(StackTraceElement("mihon.Reader", "decode", "private.jpg", 4))
        val first = SafeCrashReportCodec.format(error, 42)
        val second = SafeCrashReportCodec.format(error, 43)
        assertEquals(listOf(42L, 43L), SafeCrashReportCodec.read(first + second).map { it.atMillis })
        assertFalse(first.contains("private"))
        assertThrows(IllegalArgumentException::class.java) { SafeCrashReportCodec.read("old raw message\n" + first) }
        assertThrows(IllegalArgumentException::class.java) {
            SafeCrashReportCodec.read(first.replace("mihon.Reader", "github_pat_fixture_secret"))
        }
        assertThrows(IllegalArgumentException::class.java) { SafeCrashReportCodec.read(first.dropLast(2)) }
    }

    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("mihon-private-diagnostics-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var time = Instant.parse("2026-10-17T01:00:00Z").toEpochMilli()
        var zone = ZoneId.of("Asia/Shanghai")
        var saved: DiagnosticSession? = null
        var failSave = false
        val persistence = object : DiagnosticPersistence {
            override fun load() = saved
            override fun save(session: DiagnosticSession?) {
                if (failSave) throw IOException("fixture")
                saved = session
            }
        }
        fun store(
            limits: DiagnosticLimits = DiagnosticLimits(),
        ) = DiagnosticStore(root, persistence, scope, { time }, { zone }, limits)
        override fun close() {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private val readerEvent =
        DiagnosticEvent(DiagnosticCategory.READER, DiagnosticOperation.READER_IMAGE, DiagnosticStage.DECODE, count = 2)

    @Test
    fun `default is silent and active category alone persists structured events`() = runBlocking {
        Fixture().use { f ->
            val store = f.store()
            store.record(readerEvent)
            store.flush()
            assertTrue(store.dates(DiagnosticCategory.READER).isEmpty())
            assertTrue(store.start(DiagnosticCategory.READER))
            store.record(readerEvent)
            store.record(readerEvent.copy(category = DiagnosticCategory.SOURCE_NETWORK))
            store.flush()
            assertEquals(listOf("2026-10-17"), store.dates(DiagnosticCategory.READER))
            assertTrue(store.dates(DiagnosticCategory.SOURCE_NETWORK).isEmpty())
            val log = requireNotNull(store.read(DiagnosticCategory.READER, "2026-10-17"))
            assertTrue(log.content.contains("READER_IMAGE"))
            assertEquals(setOf(28800), log.offsetSeconds)
            assertFalse(log.content.contains(f.root.path))
            assertEquals(1, log.content.lines().count { it.isNotBlank() })
        }
    }

    @Test
    fun `active session restores and durable stop removes only that session`() = runBlocking {
        Fixture().use { f ->
            val first = f.store()
            assertTrue(first.start(DiagnosticCategory.READER))
            first.record(readerEvent)
            first.flush()
            val restored = f.store()
            assertEquals(first.state.value.session, restored.state.value.session)
            assertTrue(restored.start(DiagnosticCategory.SOURCE_NETWORK))
            restored.record(readerEvent.copy(category = DiagnosticCategory.SOURCE_NETWORK))
            restored.flush()
            assertTrue(restored.stop())
            assertNull(f.saved)
            assertNull(f.store().state.value.session)
            assertTrue(restored.dates(DiagnosticCategory.SOURCE_NETWORK).isEmpty())
            assertEquals(listOf("2026-10-17"), restored.dates(DiagnosticCategory.READER))
        }
    }

    @Test
    fun `failed persistence cannot claim recording started or stopped`() = runBlocking {
        Fixture().use { f ->
            val store = f.store()
            f.failSave = true
            assertFalse(store.start(DiagnosticCategory.READER))
            assertNull(store.state.value.session)
            assertEquals(DiagnosticError.STORAGE, store.state.value.error)
            f.failSave = false
            assertTrue(store.start(DiagnosticCategory.READER))
            val active = store.state.value.session
            f.failSave = true
            assertFalse(store.stop())
            assertEquals(active, store.state.value.session)
            assertEquals(active, f.saved)
            f.failSave = false
            assertTrue(store.stop())
        }
    }

    @Test
    fun `cleanup failure preserves durable stop and unrelated files`() = runBlocking {
        Fixture().use { f ->
            val store = f.store()
            assertTrue(store.start(DiagnosticCategory.READER))
            store.record(readerEvent)
            store.flush()
            val unrelated = f.root.resolve("reader/unexpected-user-file.txt").apply { writeText("fictional") }
            assertTrue(store.stop())
            assertNull(f.saved)
            assertNull(store.state.value.session)
            assertEquals(DiagnosticError.STORAGE, store.state.value.error)
            assertEquals("fictional", unrelated.readText())
            assertNull(f.store().state.value.session)
            store.record(readerEvent)
            store.flush()
            assertNull(store.state.value.session)
        }
    }

    @Test
    fun `capacity pauses explicitly and never truncates or deletes older records`() = runBlocking {
        Fixture().use { f ->
            val store = f.store(DiagnosticLimits(sessionBytes = 1))
            assertTrue(store.start(DiagnosticCategory.READER))
            store.record(readerEvent)
            store.flush()
            assertEquals(DiagnosticError.CAPACITY, store.state.value.error)
            assertTrue(requireNotNull(store.state.value.session).paused)
            assertTrue(requireNotNull(f.saved).paused)
            assertTrue(store.dates(DiagnosticCategory.READER).isEmpty())
        }
    }

    @Test
    fun `dates follow event timezone while old private records expire after seven days`() = runBlocking {
        Fixture().use { f ->
            val store = f.store()
            assertTrue(store.start(DiagnosticCategory.READER))
            store.record(readerEvent)
            store.flush()
            f.time += 24 * 60 * 60 * 1000L
            f.zone = ZoneId.of("America/Los_Angeles")
            store.record(readerEvent)
            store.flush()
            val original = requireNotNull(store.read(DiagnosticCategory.READER, "2026-10-17"))
            assertEquals(setOf(28800, -25200), original.offsetSeconds)
            f.time += 8 * 24 * 60 * 60 * 1000L
            assertTrue(store.dates(DiagnosticCategory.READER).isEmpty())
        }
    }

    @Test
    fun `malformed own records and preview overflow are explicit read failures`() = runBlocking {
        Fixture().use { f ->
            val store = f.store(DiagnosticLimits(previewBytes = 1))
            assertTrue(store.start(DiagnosticCategory.READER))
            store.record(readerEvent)
            store.flush()
            assertThrows(IOException::class.java) {
                runBlocking { store.read(DiagnosticCategory.READER, "2026-10-17") }
            }
            val file = f.root.walkTopDown().first { it.isFile }
            file.writeText("not valid event\n", Charsets.UTF_8)
            assertThrows(IOException::class.java) {
                runBlocking { f.store().read(DiagnosticCategory.READER, "2026-10-17") }
            }
            Unit
        }
    }

    @Test
    fun `directory links cannot redirect private diagnostics outside their root`() = runBlocking {
        Fixture().use { f ->
            val outside = Files.createTempDirectory("mihon-diagnostic-link-target-")
            val link = f.root.toPath().resolve(DiagnosticCategory.READER.id)
            try {
                if (System.getProperty("os.name").lowercase().contains("win")) {
                    fun quoted(value: String) = "'${value.replace("'", "''")}'"
                    val command = "New-Item -ItemType Junction -Path ${quoted(
                        link.toString(),
                    )} -Target ${quoted(outside.toString())} | Out-Null"
                    val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
                        .redirectErrorStream(true).start()
                    val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    assertEquals(0, process.waitFor(), output)
                } else {
                    Files.createSymbolicLink(link, outside)
                }
                val store = f.store()
                assertFalse(store.start(DiagnosticCategory.READER))
                assertEquals(DiagnosticError.STORAGE, store.state.value.error)
                assertEquals(0, outside.toFile().listFiles()!!.size)
            } finally {
                // Delete the link itself; never recursively follow it into the other fixture.
                Files.deleteIfExists(link)
                outside.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `restart cleans owned interrupted cleanup files without deleting unrelated files`() = runBlocking {
        Fixture().use { f ->
            val interrupted = f.root.resolve("diagnostic-cleanup-123456.tmp")
            interrupted.writeText("fictional private event", Charsets.UTF_8)
            val unrelated = f.root.resolve("keep-user-file.txt")
            unrelated.writeText("fixture owner file", Charsets.UTF_8)
            val store = f.store()
            assertTrue(store.start(DiagnosticCategory.READER))
            assertFalse(interrupted.exists())
            assertEquals("fixture owner file", unrelated.readText(Charsets.UTF_8))
        }
    }

    @Test
    fun `credential-shaped stack identifiers are omitted from private crash summaries`() {
        val error = IllegalStateException("not copied")
        error.stackTrace = arrayOf(
            StackTraceElement("github_pat_fixture_secret", "decode", "ignored", 4),
            StackTraceElement("mihon.Reader", "ghp_fixture_secret", "ignored", 4),
        )
        val report = safeCrashReport(error, 42)
        assertFalse(report.contains("github_pat_"))
        assertFalse(report.contains("ghp_"))
    }

    @Test
    fun `crash formatter excludes messages file names and secrets even from hostile frames`() {
        val error = IllegalStateException("TOKEN=fixture-secret https://private.test/?key=x C:\\private\\library")
        error.stackTrace = arrayOf(
            StackTraceElement("mihon.Reader", "decode", "C:\\private\\book.jpg", 4),
            StackTraceElement("https://private.test/?token=secret", "bad/value", "secret", 3),
        )
        val report = safeCrashReport(error, 42)
        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("mihon.Reader.decode:4"))
        listOf("TOKEN", "fixture-secret", "private", "book.jpg", "https", "bad/value").forEach {
            assertFalse(report.contains(it))
        }
    }
}
