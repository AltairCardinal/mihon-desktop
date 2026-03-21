package mihon.desktop.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.desktop.source.LocalChapterEntry
import mihon.desktop.source.LocalMangaEntry
import mihon.desktop.source.LocalPage
import mihon.desktop.source.LocalSourceReader
import mihon.desktop.ui.reader.DesktopReaderScreen
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.awt.FileDialog
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipFile
import javax.swing.JFileChooser

/**
 * Browse screen for local manga (zip/cbz archives and directories).
 *
 * Shows a directory picker; after selection displays manga found in the root.
 */
class LocalMangaBrowseScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()

        var rootDir by remember { mutableStateOf<File?>(null) }
        val mangaList = remember { mutableStateListOf<LocalMangaEntry>() }
        var isLoading by remember { mutableStateOf(false) }

        fun pickDirectory() {
            // Use the native macOS folder picker — it handles empty directories and
            // parent-navigation correctly. Fall back to JFileChooser on other platforms.
            val dir = if (System.getProperty("os.name").lowercase().contains("mac")) {
                System.setProperty("apple.awt.fileDialogForDirectories", "true")
                try {
                    val dialog = FileDialog(null as java.awt.Frame?, "选择漫画根目录", FileDialog.LOAD)
                    dialog.isVisible = true
                    val d = dialog.directory ?: return
                    val f = dialog.file ?: return
                    File(d, f)
                } finally {
                    System.setProperty("apple.awt.fileDialogForDirectories", "false")
                }
            } else {
                val chooser = JFileChooser().apply {
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    dialogTitle = "Select manga root directory"
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    chooser.selectedFile
                } else {
                    return
                }
            }
            rootDir = dir
            isLoading = true
            mangaList.clear()
            // Disk scan on IO thread; state updates back on scope (Main)
            scope.launch {
                val found = withContext(Dispatchers.IO) { LocalSourceReader.discoverManga(dir) }
                mangaList.addAll(found)
                isLoading = false
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Local Source") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { pickDirectory() }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "Pick directory")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                if (rootDir == null) {
                    // Initial empty state
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Select a directory containing manga",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = { pickDirectory() }) {
                                Text("Pick directory")
                            }
                        }
                    }
                } else if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (mangaList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "No manga found",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "in ${rootDir!!.absolutePath}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(mangaList, key = { it.directory.absolutePath }) { entry ->
                            ListItem(
                                headlineContent = { Text(entry.name) },
                                leadingContent = {
                                    Icon(Icons.Default.Folder, contentDescription = null)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        navigator.push(
                                            LocalChapterScreen(
                                                mangaDirPath = entry.directory.absolutePath,
                                                mangaName = entry.name,
                                            ),
                                        )
                                    },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lists chapters for a single local manga directory.
 */
data class LocalChapterScreen(
    val mangaDirPath: String,
    val mangaName: String,
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val mangaDir = remember { File(mangaDirPath) }

        val chapters = remember { mutableStateListOf<LocalChapterEntry>() }
        var isLoading by remember { mutableStateOf(true) }

        LaunchedEffect(mangaDirPath) {
            // LaunchedEffect runs on Main; IO work is dispatched to IO thread.
            val found = withContext(Dispatchers.IO) { LocalSourceReader.discoverChapters(mangaDir) }
            chapters.addAll(found)
            isLoading = false
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(mangaName) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding ->
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            } else if (chapters.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No chapters found",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(chapters, key = { it.file.absolutePath }) { chapter ->
                        ListItem(
                            headlineContent = { Text(chapter.name) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        val pageUrls = withContext(Dispatchers.IO) {
                                            val pages = LocalSourceReader.readChapter(chapter)
                                            resolvePageUrls(chapter, pages)
                                        }
                                        navigator.push(
                                            DesktopReaderScreen(
                                                chapterTitle = chapter.name,
                                                mangaTitle = mangaName,
                                                pageUrls = pageUrls,
                                            ),
                                        )
                                    }
                                },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/**
 * Converts [LocalPage] list to absolute `file://` URL strings that Coil can load.
 *
 * For archive chapters, entries are extracted to a per-chapter temp directory.
 */
private fun resolvePageUrls(chapter: LocalChapterEntry, pages: List<LocalPage>): List<String> {
    if (pages.isEmpty()) return emptyList()

    return if (chapter.file.isDirectory) {
        // Directory pages — use file:// URIs directly
        pages.map { page -> page.file!!.toURI().toString() }
    } else {
        // Archive pages — extract to a per-chapter temp directory
        val tempDir = File(
            System.getProperty("java.io.tmpdir"),
            "mihon_local_${chapter.file.nameWithoutExtension}_${chapter.file.lastModified()}",
        ).also { it.mkdirs() }

        val ext = chapter.file.extension.lowercase()
        when {
            ext == "rar" || ext == "cbr" -> {
                val raf = RandomAccessFile(chapter.file, "r")
                val inArchive = SevenZip.openInArchive(null, RandomAccessFileInStream(raf))
                try {
                    if (inArchive != null) {
                        val simpleIface = inArchive.getSimpleInterface()
                        for (item in simpleIface.archiveItems) {
                            if (item.isFolder) continue
                            val itemPath = item.path ?: continue
                            val matchPage = pages.find { it.archiveEntry == itemPath } ?: continue
                            val destFile = File(tempDir, File(matchPage.name).name)
                            if (!destFile.exists()) {
                                FileOutputStream(destFile).use { out ->
                                    item.extractSlow(object : ISequentialOutStream {
                                        override fun write(data: ByteArray): Int {
                                            out.write(data)
                                            return data.size
                                        }
                                    })
                                }
                            }
                        }
                    }
                } finally {
                    try { inArchive?.close() } catch (_: Exception) {}
                    try { raf.close() } catch (_: Exception) {}
                }
            }
            else -> {
                ZipFile(chapter.file).use { zip ->
                    for (page in pages) {
                        val destFile = File(tempDir, File(page.name).name)
                        if (!destFile.exists()) {
                            val entry = zip.getEntry(page.archiveEntry!!) ?: continue
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(destFile).use { output -> input.copyTo(output) }
                            }
                        }
                    }
                }
            }
        }
        pages.map { page -> File(tempDir, File(page.name).name).toURI().toString() }
    }
}
