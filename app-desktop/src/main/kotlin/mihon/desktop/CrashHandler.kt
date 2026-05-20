package mihon.desktop

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Global crash handler that captures unhandled exceptions and writes them to a crash log file.
 * Installed as the default uncaught exception handler for all threads.
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private val crashLogDir = File("/tmp")
    private val crashLogFile = File(crashLogDir, "mihon_crash.log")

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        val crashReport = buildCrashReport(thread, exception)

        // Write to crash log file
        crashLogFile.appendText(crashReport)

        // Also print to stderr for visibility
        System.err.println(crashReport)

        // Print stack trace to stderr
        exception.printStackTrace()

        // Sleep briefly to ensure log is flushed before JVM exits
        try {
            Thread.sleep(100)
        } catch (_: InterruptedException) {
            // Ignore
        }
    }

    private fun buildCrashReport(thread: Thread, exception: Throwable): String {
        val sw = StringWriter()
        exception.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()

        // Limit stack trace length to avoid huge logs
        val truncatedStack = if (stackTrace.length > 3000) {
            stackTrace.take(3000) + "\n... (truncated, full trace in console)"
        } else {
            stackTrace
        }

        return buildString {
            appendLine("=".repeat(80))
            appendLine("MIHON DESKTOP CRASH REPORT")
            appendLine("Timestamp: ${timestampFormat.format(Date())}")
            appendLine("Java Version: ${System.getProperty("java.version")}")
            appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
            appendLine("App Version: ${APP_VERSION}")
            appendLine("Thread: ${thread.name}")
            appendLine("Exception Type: ${exception::class.java.name}")
            appendLine("Message: ${exception.message ?: "(no message)"}")
            appendLine()
            appendLine("Stack Trace:")
            appendLine(truncatedStack)

            // Add suppressed exceptions if any
            if (exception.suppressed.isNotEmpty()) {
                appendLine()
                appendLine("Suppressed Exceptions:")
                exception.suppressed.forEach { suppressed ->
                    appendLine("  - ${suppressed::class.java.name}: ${suppressed.message}")
                }
            }

            // Add cause if any
            if (exception.cause != null && exception.cause != exception) {
                appendLine()
                appendLine("Caused by: ${exception.cause!!::class.java.name}: ${exception.cause!!.message}")
                val causeSw = StringWriter()
                exception.cause!!.printStackTrace(PrintWriter(causeSw))
                appendLine(causeSw.toString().take(500))
            }

            appendLine("=".repeat(80))
            appendLine()
        }
    }

    /**
     * Installs this crash handler as the default uncaught exception handler.
     * Call this early in main() before any other initialization.
     */
    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }
}
