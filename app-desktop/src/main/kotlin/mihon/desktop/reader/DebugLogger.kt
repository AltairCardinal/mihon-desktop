package mihon.desktop.reader

import mihon.desktop.CrashHandler
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Debug logger that writes to the platform log directory.
 */
object DebugLogger {
    private val logFile = defaultLogFile().also {
        it.parentFile?.mkdirs()
        it.delete()
    }

    internal fun defaultLogFile(): File = File(CrashHandler.defaultCrashLogDir(), "mihon_reader_debug.log")

    fun log(msg: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS").format(Date())
        val line = "[$timestamp] $msg"
        logFile.parentFile?.mkdirs()
        logFile.appendText("$line\n")
        println(line)
    }
}
