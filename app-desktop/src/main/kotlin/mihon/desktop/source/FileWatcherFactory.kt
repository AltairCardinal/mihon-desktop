package mihon.desktop.source

import io.methvin.watcher.DirectoryWatcher
import java.io.Closeable
import java.io.File

/**
 * Abstraction over file system watching for testability.
 *
 * [watch] begins monitoring [directory] recursively.  Whenever a file
 * is created, modified, or deleted, [onChange] is called with the affected path.
 * The returned [Closeable] stops the watcher when closed.
 */
fun interface FileWatcherFactory {
    fun watch(directory: File, onChange: (File) -> Unit): Closeable
}

/**
 * Production implementation backed by `io.methvin:directory-watcher`.
 *
 * On macOS this uses FSEvents; on Linux, inotify; on Windows,
 * ReadDirectoryChangesW.  All are recursive by default.
 */
object DefaultFileWatcherFactory : FileWatcherFactory {
    override fun watch(directory: File, onChange: (File) -> Unit): Closeable {
        val watcher = DirectoryWatcher.builder()
            .path(directory.toPath())
            .listener { event -> onChange(event.path().toFile()) }
            .build()
        watcher.watchAsync()
        return Closeable { watcher.close() }
    }
}
