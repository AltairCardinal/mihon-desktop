package mihon.desktop.source

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import mihon.desktop.settings.DesktopAppPreferences
import java.io.Closeable
import java.io.File

/**
 * Background service that maintains a cached list of locally discovered manga.
 *
 * - Scans on [start] if a root directory is already configured.
 * - Watches the file system for changes and rescans after a debounce.
 * - Reacts to preference changes (rootDir, maxDepth) automatically.
 */
class LocalSourceScanService(
    private val prefs: DesktopAppPreferences,
    private val scope: CoroutineScope,
    private val watcherFactory: FileWatcherFactory = DefaultFileWatcherFactory,
) {
    sealed interface ScanState {
        data object Idle : ScanState
        data object Scanning : ScanState
        data object Watching : ScanState
        data class Error(val message: String) : ScanState
    }

    private val _mangaList = MutableStateFlow<List<LocalMangaEntry>>(emptyList())
    val mangaList: StateFlow<List<LocalMangaEntry>> = _mangaList.asStateFlow()

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private var scanJob: Job? = null
    private var prefObserverJob: Job? = null
    private var watcher: Closeable? = null
    private var fsDebounceJob: Job? = null

    fun start() {
        // Observe rootDir preference changes
        prefObserverJob = scope.launch {
            prefs.localSourceRootDir.changes()
                .drop(1) // skip initial value (we handle it below)
                .collectLatest {
                    performScanAndWatch()
                }
        }

        // Initial scan
        scope.launch {
            performScanAndWatch()
        }
    }

    fun stop() {
        scanJob?.cancel()
        scanJob = null
        prefObserverJob?.cancel()
        prefObserverJob = null
        fsDebounceJob?.cancel()
        fsDebounceJob = null
        watcher?.close()
        watcher = null
        _scanState.value = ScanState.Idle
    }

    fun rescan() {
        scope.launch { performScanAndWatch() }
    }

    private suspend fun performScanAndWatch() {
        val rootPath = prefs.localSourceRootDir.get()
        if (rootPath.isEmpty()) {
            _mangaList.value = emptyList()
            _scanState.value = ScanState.Idle
            watcher?.close()
            watcher = null
            return
        }

        val rootDir = File(rootPath)
        if (!rootDir.isDirectory) {
            _mangaList.value = emptyList()
            _scanState.value = ScanState.Error("Directory not found: $rootPath")
            return
        }

        // Cancel any previous scan
        scanJob?.cancel()

        _scanState.value = ScanState.Scanning

        val maxDepth = prefs.localSourceMaxDepth.get()
        val result = LocalSourceReader.discoverMangaRecursive(rootDir, maxDepth)
        _mangaList.value = result

        // Resolve covers asynchronously — update entries as covers are found
        scope.launch {
            val resolved = result.toMutableList()
            for (i in resolved.indices) {
                val entry = resolved[i]
                if (entry.coverFile == null) {
                    val cover = LocalSourceReader.resolveCover(entry)
                    if (cover != null) {
                        resolved[i] = entry.copy(coverFile = cover)
                        _mangaList.value = resolved.toList()
                    }
                }
            }
        }

        // Set up file system watcher
        watcher?.close()
        try {
            watcher = watcherFactory.watch(rootDir) { changedFile ->
                // Debounce FS events — multiple rapid changes trigger one rescan
                fsDebounceJob?.cancel()
                fsDebounceJob = scope.launch {
                    delay(DEBOUNCE_MS)
                    val depth = prefs.localSourceMaxDepth.get()
                    _scanState.value = ScanState.Scanning
                    val updated = LocalSourceReader.discoverMangaRecursive(rootDir, depth)
                    _mangaList.value = updated
                    _scanState.value = ScanState.Watching
                }
            }
            _scanState.value = ScanState.Watching
        } catch (e: Exception) {
            _scanState.value = ScanState.Error("Watch failed: ${e.message}")
        }
    }

    companion object {
        const val DEBOUNCE_MS = 500L
    }
}
