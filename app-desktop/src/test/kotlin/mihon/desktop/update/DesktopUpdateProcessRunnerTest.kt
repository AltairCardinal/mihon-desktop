package mihon.desktop.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class DesktopUpdateProcessRunnerTest {
    @Test
    fun `stdin and simultaneous output are drained without disclosure`(@TempDir directory: Path) = runTest {
        val runner = DesktopUpdateProcessRunner()
        val input = "secret\n".toCharArray()
        val result = runner.run(updaterTestCommand("io", directory), input)
        assertEquals(7, result.exitCode)
        assertEquals("", result.stdout)
        assertEquals("", result.stderr)
        assertEquals(0, runner.activeReaderCount)
    }

    @Test
    fun `normal exit and startup failure are reported without reader leaks`(@TempDir directory: Path) = runTest {
        val runner = DesktopUpdateProcessRunner()
        assertEquals(9, runner.run(updaterTestCommand("io", directory)).exitCode)
        val input = "private".toCharArray()
        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { runner.run(listOf("missing-updater-command"), input) } }
        assertTrue(input.all { it == '\u0000' })
        assertEquals(0, runner.activeReaderCount)
    }

    @Test
    fun `cancellation preserves cause and bounds process plus reader lifetime`(@TempDir directory: Path) = runTest {
        listOf("block", "resist").forEach { mode ->
            val grace = if (mode == "resist") 0L else 50L
            val runner = DesktopUpdateProcessRunner(gracefulExitMillis = grace, forcedExitMillis = 1_000)
            val running = async(Dispatchers.Default) { runner.run(updaterTestCommand(mode, directory)) }
            val pid = awaitUpdaterPid(directory)
            assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive)
            val cancellation = CancellationException("cancel-$mode")
            val completion = CompletableDeferred<Throwable?>()
            running.invokeOnCompletion { completion.complete(it) }
            running.cancel(cancellation)
            runCatching { running.await() }
            assertSame(cancellation, completion.await())
            awaitUpdaterExit(pid)
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
            assertEquals(0, runner.activeReaderCount)
            if (mode == "resist") assertTrue(runner.forcedTerminationCount > 0)
            Files.deleteIfExists(directory.resolve("pid"))
        }
    }

    @Test
    fun `forced termination timeout is attached without replacing cancellation`(@TempDir directory: Path) = runTest {
        var waitCalls = 0
        var aliveChecks = 0
        val runner = DesktopUpdateProcessRunner(
            gracefulExitMillis = 1,
            forcedExitMillis = 1,
            timedWait = { _, _ -> waitCalls++; false },
            processIsAlive = { aliveChecks++; true },
        )
        val running = async(Dispatchers.Default) { runner.run(updaterTestCommand("block", directory)) }
        val pid = awaitUpdaterPid(directory)
        val cancellation = CancellationException("cleanup-timeout")
        val completion = CompletableDeferred<Throwable?>()
        running.invokeOnCompletion { completion.complete(it) }
        running.cancel(cancellation)
        runCatching { running.await() }
        assertSame(cancellation, completion.await())
        assertEquals(2, waitCalls, "both cleanup waits must run")
        assertTrue(aliveChecks > 0, "forced timeout must verify process liveness")
        assertTrue(cancellation.suppressed.any { it.message?.contains("termination timed out") == true })
        assertEquals(0, runner.activeReaderCount)
        awaitUpdaterExit(pid)
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
    }
}

internal suspend fun awaitUpdaterPid(directory: Path): Long = withContext(Dispatchers.IO) {
    withTimeout(UPDATER_HELPER_STARTUP_TIMEOUT_MILLIS) {
        val file = directory.resolve("pid")
        var pid: Long?
        do {
            delay(10)
            pid = runCatching { Files.readString(file).trim().toLongOrNull() }.getOrNull()
        } while (pid == null)
        pid
    }
}

private const val UPDATER_HELPER_STARTUP_TIMEOUT_MILLIS = 10_000L

private suspend fun awaitUpdaterExit(pid: Long) = withContext(Dispatchers.Default) {
    withTimeout(2_000) {
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) delay(10)
    }
}

internal fun updaterTestCommand(mode: String, directory: Path): List<String> {
    val source = directory.resolve("UpdaterProcessTestHelper.java")
    Files.writeString(source, UPDATER_PROCESS_HELPER)
    return listOf(ProcessHandle.current().info().command().orElseThrow(), source.toString(), mode, directory.toString())
}

private val UPDATER_PROCESS_HELPER =
    """
    import java.io.*;
    import java.nio.file.*;
    import java.util.*;
    public class UpdaterProcessTestHelper {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[1], "pid"), Long.toString(ProcessHandle.current().pid())); if (args[0].equals("resist")) Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { Thread.sleep(60_000); } catch (Exception ignored) {} }));
            if (!args[0].equals("io")) Thread.sleep(60_000);
            String input = new BufferedReader(new InputStreamReader(System.in)).readLine(); Thread out = new Thread(() -> write(System.out, 'o')); Thread err = new Thread(() -> write(System.err, 'e'));
            out.start(); err.start(); out.join(); err.join();
            System.exit("secret".equals(input) ? 7 : 9);
        }
        private static void write(PrintStream stream, char value) { char[] output = new char[8192]; Arrays.fill(output, value);
            for (int i = 0; i < 128; i++) stream.print(output); stream.flush();
        }
    }
    """.trimIndent()
