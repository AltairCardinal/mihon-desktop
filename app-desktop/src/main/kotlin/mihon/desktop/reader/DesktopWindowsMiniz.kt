package mihon.desktop.reader

import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** A process-wide optional optimization. Reader opens only observe the state; they never wait for loading. */
internal class DesktopWindowsMinizLibrary(
    private val supportedPlatform: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true) &&
        System.getProperty("os.arch").lowercase() in setOf("amd64", "x86_64"),
    private val resourceDirectory: () -> File? = {
        System.getProperty("compose.application.resources.dir")?.let(::File)
    },
    private val load: (String) -> Unit = System::load,
) {
    enum class State { NOT_STARTED, LOADING, READY, UNAVAILABLE }

    private val currentState = AtomicReference(State.NOT_STARTED)
    val state: State get() = currentState.get()

    fun startAsync() {
        if (!currentState.compareAndSet(State.NOT_STARTED, State.LOADING)) return
        if (!supportedPlatform) {
            currentState.set(State.UNAVAILABLE)
            return
        }
        Thread({
            try {
                val directory = checkNotNull(resourceDirectory()) { "Packaged resources are unavailable" }.canonicalFile
                val library = directory.resolve("mihon-reader-miniz.dll").canonicalFile
                check(library.parentFile == directory && library.isFile) { "Packaged miniz library is missing" }
                load(library.absolutePath)
                check(DesktopWindowsMinizNative.version() == VERSION) {
                    "Unsupported reader miniz ABI or source version"
                }
                currentState.set(State.READY)
            } catch (_: Exception) {
                currentState.set(State.UNAVAILABLE)
            } catch (_: LinkageError) {
                currentState.set(State.UNAVAILABLE)
            }
        }, "mihon-reader-miniz-load").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    fun openIfReady(file: File): Long {
        if (Thread.currentThread().isInterrupted) {
            throw kotlinx.coroutines.CancellationException(
                "Reader archive open interrupted",
            )
        }
        return if (state == State.READY) DesktopWindowsMinizNative.open(file.absolutePath) else 0L
    }

    companion object {
        const val VERSION = "miniz/174573d60290f447c13a2b1b3405de2b96e27d6c;abi=1;x64"
    }
}

internal object DesktopWindowsMiniz {
    val library = DesktopWindowsMinizLibrary()

    fun startAsync() = library.startAsync()
}

/** Stable ABI: positive registry IDs, bounded ASCII metadata, and streaming file copies. No Kotlin model ABI crosses JNI. */
internal object DesktopWindowsMinizNative {
    @JvmStatic external fun version(): String

    @JvmStatic external fun open(path: String): Long

    @JvmStatic external fun snapshot(handle: Long): Array<String>

    @JvmStatic external fun copy(handle: Long, pageIndex: Int, destination: String)

    @JvmStatic external fun close(handle: Long)
}
