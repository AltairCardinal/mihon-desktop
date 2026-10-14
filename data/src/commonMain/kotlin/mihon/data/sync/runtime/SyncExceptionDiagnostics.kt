package mihon.data.sync.runtime

import okio.FileSystem
import okio.Path
import okio.buffer
import java.util.UUID

/** Optional local exception observations never participate in synchronization decisions. */
class SyncExceptionDiagnostics(private val directory: Path) {
    fun report(failure: Exception) {
        val temporary = directory.resolve("sync-exception-${UUID.randomUUID()}.tmp")
        try {
            val lines = mutableListOf("Sync exception (type and stack only; messages omitted)")
            var current: Throwable? = failure
            repeat(4) {
                val error = current ?: return@repeat
                lines += error.javaClass.name.take(512)
                error.stackTrace.take(48).forEach { frame ->
                    lines += "  at ${frame.className.take(256)}.${frame.methodName.take(256)}" +
                        "(${frame.fileName?.substringAfterLast(
                            '/',
                        )?.substringAfterLast('\\')?.take(128)}:${frame.lineNumber})"
                }
                current = error.cause.takeUnless { it === error }
            }
            val bounded = buildString {
                var bytes = 0
                for (line in lines) {
                    val clean = line.replace('\r', ' ').replace('\n', ' ') + "\n"
                    val size = clean.encodeToByteArray().size
                    if (bytes + size > 32 * 1024) break
                    append(clean)
                    bytes += size
                }
            }
            FileSystem.SYSTEM.createDirectories(directory)
            FileSystem.SYSTEM.sink(temporary).buffer().use { it.writeUtf8(bounded) }
            FileSystem.SYSTEM.atomicMove(temporary, directory.resolve("sync-exception-latest.txt"))
        } catch (_: Exception) {
            // Diagnostics must not change the result or retry policy of the exchange.
        } finally {
            runCatching { FileSystem.SYSTEM.delete(temporary, mustExist = false) }
        }
    }
}
