package mihon.desktop.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

class DesktopUpdateProcessRunner internal constructor(
    private val gracefulExitMillis: Long = 500,
    private val forcedExitMillis: Long = 1_500,
    private val timedWait: (Process, Long) -> Boolean = { process, timeout -> process.waitFor(timeout, TimeUnit.MILLISECONDS) },
    private val processIsAlive: (Process) -> Boolean = Process::isAlive,
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
            val cancellation = runCatching { currentCoroutineContext().ensureActive() }.exceptionOrNull()
            val propagated = if (error is CancellationException) cancellation ?: error else error
            val cleanupFailures = mutableListOf<Throwable>()
            withContext(NonCancellable) {
                try {
                    terminate(process)
                } catch (cleanupFailure: Throwable) {
                    cleanupFailures += cleanupFailure
                }
                process.outputStream.closeQuietly()
                process.inputStream.closeQuietly()
                process.errorStream.closeQuietly()
                listOf(stdout, stderr).forEach { reader ->
                    try {
                        reader.cancelAndJoin()
                    } catch (cleanupFailure: Throwable) {
                        cleanupFailures += cleanupFailure
                    }
                }
            }
            cleanupFailures.filter { it !== propagated }.forEach(propagated::addSuppressed)
            throw propagated
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
        val exitedGracefully = gracefulExitMillis > 0L && timedWait(process, gracefulExitMillis)
        if (!exitedGracefully) {
            forcedTerminations.incrementAndGet()
            process.destroyForcibly()
            val exitedForcibly = timedWait(process, forcedExitMillis)
            if (!exitedForcibly && processIsAlive(process)) {
                throw DesktopUpdateProcessTerminationException(process.pid(), forcedExitMillis)
            }
        }
        process.inputStream.closeQuietly()
        process.errorStream.closeQuietly()
    }

    private fun AutoCloseable.closeQuietly() {
        runCatching { close() }
    }
}

internal class DesktopUpdateProcessTerminationException(pid: Long, timeoutMillis: Long) :
    IllegalStateException("Updater verifier process $pid termination timed out after ${timeoutMillis}ms")
