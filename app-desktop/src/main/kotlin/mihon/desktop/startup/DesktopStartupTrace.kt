package mihon.desktop.startup

import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** A single startup phase milestone. */
data class StartupMark(val phase: String, val epochMs: Long, val sinceProcessStartMs: Long)

/**
 * Records startup phase milestones so desktop cold-start cost can be attributed to a
 * concrete phase instead of guessed.
 *
 * The trace is intentionally cheap: every [mark] is a single nanoTime read plus an
 * append to a copy-on-write list. The snapshot is written once, when the first frame
 * has been drawn.
 */
object DesktopStartupTrace {

    private val marks = CopyOnWriteArrayList<StartupMark>()
    private val lock = Any()

    @Volatile
    private var processStartEpochMs: Long = -1L

    @Volatile
    private var jvmStartEpochMs: Long = -1L

    /** Epoch millis of the OS process start, or -1 when the platform cannot report it. */
    val processStart: Long
        get() {
            if (processStartEpochMs < 0) {
                synchronized(lock) {
                    if (processStartEpochMs < 0) {
                        processStartEpochMs = runCatching {
                            ProcessHandle.current().info().startInstant()
                                .map { it.toEpochMilli() }
                                .orElse(-1L)
                        }.getOrDefault(-1L)
                    }
                }
            }
            return processStartEpochMs
        }

    /** Epoch millis of the JVM start, as reported by the JVM itself. */
    val jvmStart: Long
        get() {
            if (jvmStartEpochMs < 0) {
                synchronized(lock) {
                    if (jvmStartEpochMs < 0) {
                        jvmStartEpochMs = runCatching {
                            java.lang.management.ManagementFactory.getRuntimeMXBean().startTime
                        }.getOrDefault(-1L)
                    }
                }
            }
            return jvmStartEpochMs
        }

    /** Records a startup phase boundary. */
    fun mark(phase: String) {
        val now = System.currentTimeMillis()
        val origin = processStart
        marks.add(StartupMark(phase, now, if (origin > 0) now - origin else -1L))
        // Persist on every boundary: the file has to survive a forced termination
        // that happens before the first frame is drawn.
        persist()
    }

    /** Directory the trace file is written to; attached once the platform paths resolve. */
    @Volatile
    private var logDirectory: File? = null

    /** Sets the directory that receives the trace file. */
    fun attachLogDirectory(directory: File) {
        logDirectory = directory
        persist()
    }

    private fun persist() {
        val directory = logDirectory ?: return
        writeTo(directory)
    }

    /** Ordered list of recorded phases with their offsets from the process start. */
    fun recorded(): List<StartupMark> = marks.toList()

    /** Human readable one-line-per-phase rendering, used by logs and diagnostics. */
    fun render(): String = buildString {
        recorded().forEach { mark ->
            val offset = if (mark.sinceProcessStartMs >= 0) "${mark.sinceProcessStartMs}ms" else "n/a"
            appendLine("  ${mark.phase}: $offset (epoch ${mark.epochMs})")
        }
    }

    /**
     * Writes the trace as JSON next to the application logs.
     *
     * The file is overwritten on every start so external measurement tooling can read
     * the most recent run without having to guess file names.
     */
    fun writeTo(logsDirectory: File, fileName: String = "startup-trace.json"): File? = runCatching {
        if (!logsDirectory.exists()) logsDirectory.mkdirs()
        val target = File(logsDirectory, fileName)
        target.writeText(renderJson(), Charsets.UTF_8)
        target
    }.getOrNull()
    private fun renderJson(): String = buildString {
        appendLine("{")
        appendLine("  \"processStartEpochMs\": $processStart,")
        appendLine("  \"jvmStartEpochMs\": $jvmStart,")
        appendLine("  \"loadedClassCount\": ${loadedClassCount()},")
        appendLine("  \"recordedAt\": \"${Instant.now()}\",")
        appendLine("  \"marks\": [")
        recorded().forEachIndexed { index, mark ->
            val comma = if (index == recorded().lastIndex) "" else ","
            appendLine(
                "    {\"phase\": \"${mark.phase.escapeJson()}\", " +
                    "\"epochMs\": ${mark.epochMs}, " +
                    "\"sinceProcessStartMs\": ${mark.sinceProcessStartMs}}$comma",
            )
        }
        appendLine("  ]")
        appendLine("}")
    }

    /** Number of classes the virtual machine has loaded so far. */
    private fun loadedClassCount(): Long = runCatching {
        java.lang.management.ManagementFactory.getClassLoadingMXBean().loadedClassCount.toLong()
    }.getOrDefault(-1L)

    private fun String.escapeJson(): String = buildString {
        this@escapeJson.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
    }
}
