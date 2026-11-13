package mihon.desktop.download

import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** One download graph's finite completed-artifact reader/deletion authority. No filesystem scan. */
internal class DesktopDownloadArtifactUsage {
    private val lock = Any()
    private val readers = mutableMapOf<Path, Int>()
    private val deleting = mutableSetOf<Path>()

    fun acquire(artifact: File): AutoCloseable {
        val path = artifact.toPath().toAbsolutePath().normalize()
        synchronized(lock) {
            check(deleting.none { overlaps(path, it) }) { "Download artifact is being deleted" }
            readers[path] = readers.getOrDefault(path, 0) + 1
        }
        return once {
            synchronized(lock) {
                val remaining = readers.getValue(path) - 1
                if (remaining == 0) readers.remove(path) else readers[path] = remaining
            }
        }
    }

    fun reserveDeletion(artifacts: Collection<File>): AutoCloseable? {
        val paths = artifacts.map { it.toPath().toAbsolutePath().normalize() }.toSet()
        synchronized(lock) {
            if (paths.any { candidate ->
                    readers.keys.any { overlaps(candidate, it) } ||
                        deleting.any { overlaps(candidate, it) }
                }
            ) {
                return null
            }
            deleting += paths
        }
        return once { synchronized(lock) { deleting.removeAll(paths) } }
    }

    private fun overlaps(first: Path, second: Path) = first.startsWith(second) || second.startsWith(first)
    private fun once(action: () -> Unit): AutoCloseable {
        val closed = AtomicBoolean()
        return AutoCloseable { if (closed.compareAndSet(false, true)) action() }
    }
}
