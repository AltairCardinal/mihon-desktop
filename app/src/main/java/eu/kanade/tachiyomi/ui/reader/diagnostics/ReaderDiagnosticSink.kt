package eu.kanade.tachiyomi.ui.reader.diagnostics

import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

internal class ReaderDiagnosticSink(
    private val directory: () -> File,
    private val maxBytes: Long = 4L * 1024 * 1024,
    private val fileCount: Int = 4,
    capacity: Int = 512,
    private val onFailure: (Long) -> Unit = {},
) : AutoCloseable {
    val failures = AtomicLong()
    private val dropped = AtomicLong()
    private val queue = ArrayBlockingQueue<String>(capacity)

    @Volatile private var closed = false
    private val worker = thread(name = "reader-diagnostics", isDaemon = true) {
        var reportedFailures = 0L
        while (!closed || queue.isNotEmpty()) {
            val line = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            try {
                val context = line.substringBefore("event=", "")
                val count = dropped.get()
                if (count > 0) {
                    write("${context}event=dropped count=$count")
                    dropped.addAndGet(-count)
                }
                val failed = failures.get()
                if (failed != reportedFailures) {
                    write("${context}event=write_failure count=$failed")
                    reportedFailures = failed
                }
                write(line)
            } catch (_: Exception) {
                val count = failures.incrementAndGet()
                runCatching { onFailure(count) }
            }
        }
    }

    /** No filesystem access or waiting on the caller (including the UI thread). */
    fun offer(line: String) {
        if (closed || !queue.offer(line.take(8190).replace('\n', '_').replace('\r', '_'))) {
            dropped.incrementAndGet()
        }
    }

    private fun write(line: String) {
        val root = directory()
        check(root.isDirectory || root.mkdirs())
        fun file(index: Int) = File(root, "reader-$index.log")
        val limit = (maxBytes - 1).coerceAtMost(8190).toInt()
        val encoded = line.toByteArray(Charsets.UTF_8)
        var end = minOf(encoded.size, limit)
        while (end < encoded.size && end > 0 && encoded[end].toInt() and 0xC0 == 0x80) end--
        val bytes = encoded.copyOf(end) + byteArrayOf(10)
        if (file(0).length() + bytes.size > maxBytes) {
            if (file(fileCount - 1).exists()) check(file(fileCount - 1).delete())
            for (index in fileCount - 2 downTo 0) {
                if (file(index).exists()) check(file(index).renameTo(file(index + 1)))
            }
        }
        file(0).appendBytes(bytes)
    }

    /** Test/shutdown drain only; production reader callbacks never close or wait. */
    override fun close() {
        closed = true
        worker.join(5000)
    }
}
