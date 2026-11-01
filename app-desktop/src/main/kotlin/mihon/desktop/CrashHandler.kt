package mihon.desktop

import mihon.desktop.platform.DesktopPlatformPaths
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import tachiyomi.core.common.feedback.SafeCrashReportCodec

/**
 * Global crash handler that captures unhandled exceptions and writes them to a crash log file.
 * Installed as the default uncaught exception handler for all threads.
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private const val MAX_LOG_BYTES = 2L * 1024L * 1024L
    private const val MAX_ARCHIVES = 3

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        val crashReport = SafeCrashReportCodec.format(exception)
        val logFile = crashLogFile()

        try {
            appendCrashReport(logFile, crashReport)
        } catch (writeFailure: Exception) {
            System.err.println("Failed to persist crash report")
        }

        // Also print to stderr for visibility
        System.err.println(crashReport)

        System.err.flush()

        // Sleep briefly to ensure log is flushed before JVM exits
        try {
            Thread.sleep(100)
        } catch (_: InterruptedException) {
            // Ignore
        }
    }

    internal fun defaultCrashLogDir(): File {
        return DesktopPlatformPaths.current().logsDir
    }

    internal fun crashLogFile(directory: File = defaultCrashLogDir()): File = File(directory, "mihon_crash_safe.log")

    @Synchronized
    internal fun appendCrashReport(
        file: File,
        report: String,
        maxBytes: Long = MAX_LOG_BYTES,
        maxArchives: Int = MAX_ARCHIVES,
    ) {
        val path = file.toPath().toAbsolutePath().normalize()
        fun checkLocation() {
            var ancestor: java.nio.file.Path? = path
            while (ancestor != null) {
                if (Files.isSymbolicLink(ancestor) ||
                    (Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS) &&
                        ancestor.toRealPath() != ancestor.toAbsolutePath().normalize())) {
                    throw IOException("Redirected crash log location")
                }
                ancestor = ancestor.parent
            }
        }
        checkLocation()
        file.parentFile?.mkdirs()
        if (file.exists() && file.length() >= maxBytes) {
            rotateCrashLogs(file, maxArchives)
        }
        checkLocation()
        val posix = Files.getFileStore(path.parent).supportsFileAttributeView("posix")
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (posix) {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            } else Files.createFile(path)
        }
        if (posix) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        Files.newOutputStream(path, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS).use {
            it.write(report.toByteArray(Charsets.UTF_8))
        }
    }

    private fun rotateCrashLogs(file: File, maxArchives: Int) {
        if (maxArchives <= 0) {
            file.delete()
            return
        }

        File("${file.path}.$maxArchives").delete()
        for (index in maxArchives - 1 downTo 1) {
            val source = File("${file.path}.$index")
            if (source.exists()) {
                source.renameTo(File("${file.path}.${index + 1}"))
            }
        }
        file.renameTo(File("${file.path}.1"))
    }

    /**
     * Installs this crash handler as the default uncaught exception handler.
     * Call this early in main() before any other initialization.
     */
    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }
}
