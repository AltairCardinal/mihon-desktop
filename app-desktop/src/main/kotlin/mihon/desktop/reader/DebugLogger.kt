package mihon.desktop.reader

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Debug logger that writes to /tmp/mihon_reader_debug.log
 */
object DebugLogger {
    private val logFile = File("/tmp/mihon_reader_debug.log").also { it.delete() }

    fun log(msg: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS").format(Date())
        val line = "[$timestamp] $msg"
        logFile.appendText("$line\n")
        println(line)
    }
}
