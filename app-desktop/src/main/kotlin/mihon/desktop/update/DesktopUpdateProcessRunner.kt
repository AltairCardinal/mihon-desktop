package mihon.desktop.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import mihon.desktop.platform.CommandResult
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

fun interface DesktopUpdateCommandRunner {
    suspend fun run(arguments: List<String>, stdin: CharArray?): CommandResult
}

class DesktopUpdateProcessRunner(
    private val gracefulExitMillis: Long = 500,
    private val forcedExitMillis: Long = 1_500,
) : DesktopUpdateCommandRunner {
    private val readers = AtomicInteger()
    private val forcedTerminations = AtomicInteger()
    internal val activeReaderCount: Int get() = readers.get()
    internal val forcedTerminationCount: Int get() = forcedTerminations.get()
    suspend fun run(arguments: List<String>): CommandResult = run(arguments, null)

    override suspend fun run(arguments: List<String>, stdin: CharArray?): CommandResult = coroutineScope {
        require(arguments.isNotEmpty())
        val process = try {
            withContext(Dispatchers.IO) { ProcessBuilder(arguments).start() }
        } catch (error: Throwable) {
            stdin?.fill('\u0000')
            throw error
        }
        val stdout = drain(process.inputStream)
        val stderr = drain(process.errorStream)
        try {
            withContext(Dispatchers.IO) {
                process.outputStream.writer(StandardCharsets.UTF_8).use { writer ->
                    if (stdin != null) writer.write(stdin)
                }
            }
            val exitCode = runInterruptible(Dispatchers.IO) { process.waitFor() }
            joinAll(stdout, stderr)
            CommandResult(exitCode, "", "")
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                terminate(process)
                stdout.cancelAndJoin()
                stderr.cancelAndJoin()
            }
            throw error
        } finally {
            stdin?.fill('\u0000')
            process.outputStream.closeQuietly()
            process.inputStream.closeQuietly()
            process.errorStream.closeQuietly()
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.drain(stream: InputStream) = launch(Dispatchers.IO) {
        readers.incrementAndGet()
        try {
            stream.use { it.transferTo(java.io.OutputStream.nullOutputStream()) }
        } catch (_: Exception) {
            // Closing streams is part of cancellation; verifier output is intentionally discarded.
        } finally {
            readers.decrementAndGet()
        }
    }

    private suspend fun terminate(process: Process) = withContext(Dispatchers.IO) {
        process.outputStream.closeQuietly()
        process.destroy()
        if (gracefulExitMillis == 0L || !process.waitFor(gracefulExitMillis, TimeUnit.MILLISECONDS)) {
            forcedTerminations.incrementAndGet()
            process.destroyForcibly()
            process.waitFor(forcedExitMillis, TimeUnit.MILLISECONDS)
        }
        process.inputStream.closeQuietly()
        process.errorStream.closeQuietly()
    }

    private fun AutoCloseable.closeQuietly() {
        runCatching { close() }
    }
}
