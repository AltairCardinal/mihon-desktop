package mihon.desktop.ui.browse

import tachiyomi.i18n.MR
import java.util.Locale

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.LocalChapterEntry
import mihon.desktop.source.LocalSourceReader
import mihon.desktop.source.LocalSourceScanService
import java.awt.FileDialog
import java.io.File
import javax.swing.JFileChooser

/**
 * Browse screen for local manga.
 *
 * Consumes [LocalSourceScanService] StateFlows for the manga list and scan state.
 * The scan service runs in the background from application startup, so the list
 * is available immediately when the user navigates here.
 */
class LocalMangaBrowseScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val prefs = LocalDesktopUiDependencies.current.appPreferences
        val scanService = LocalDesktopUiDependencies.current.localSourceScanService

        val mangaList by scanService.mangaList.collectAsState()
        val scanState by scanService.scanState.collectAsState()
        val rootDir = remember { prefs.localSourceRootDir.get() }

        fun pickDirectory() {
            val dir = if (System.getProperty("os.name").lowercase().contains("mac")) {
                System.setProperty("apple.awt.fileDialogForDirectories", "true")
                try {
                val dialog = FileDialog(
                    null as java.awt.Frame?,
                    MR.strings.desktop_ui_select_manga_root_directory.localized(),
                    FileDialog.LOAD,
                )
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
                    dialogTitle = MR.strings.desktop_ui_select_manga_root_directory.localized()
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    chooser.selectedFile
                } else {
                    return
                }
            }
            prefs.localSourceRootDir.set(dir.absolutePath)
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(MR.strings.desktop_ui_local_source.localized()) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = MR.strings.action_bar_up_description.localized())
                        }
                    },
                    actions = {
                        IconButton(onClick = { pickDirectory() }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = MR.strings.desktop_ui_pick_directory.localized())
                        }
                        IconButton(onClick = { navigator.push(LocalSourceSettingsScreen()) }) {
                            Icon(Icons.Default.Settings, contentDescription = MR.strings.label_settings.localized())
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                // Progress indicator while scanning (non-blocking)
                if (scanState is LocalSourceScanService.ScanState.Scanning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                if (rootDir.isEmpty()) {
                    // No directory configured — prompt user
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
                                MR.strings.desktop_ui_select_a_directory_containing_manga.localized(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = { pickDirectory() }) {
                                Text(MR.strings.desktop_ui_pick_directory.localized())
                            }
                        }
                    }
                } else if (mangaList.isEmpty() && scanState !is LocalSourceScanService.ScanState.Scanning) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                MR.strings.desktop_ui_no_manga_found.localized(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                MR.strings.desktop_ui_in_directory.localized(Locale.getDefault(), rootDir),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 120.dp),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(mangaList, key = { it.directory.absolutePath }) { entry ->
                            LocalMangaCard(
                                name = entry.name,
                                coverFile = entry.coverFile,
                                onClick = {
                                    if (entry.directory.isFile) {
                                        navigator.push(
                                            localReaderScreen(
                                                chapterFile = entry.directory,
                                                mangaName = entry.name,
                                                chapterTitle = entry.name,
                                            ),
                                        )
                                    } else {
                                        navigator.push(
                                            LocalChapterScreen(
                                                mangaDirPath = entry.directory.absolutePath,
                                                mangaName = entry.name,
                                            ),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalMangaCard(name: String, coverFile: File?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Box {
            if (coverFile != null) {
                AsyncImage(
                    model = coverFile,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.7f),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.7f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Gradient overlay so the title is readable over the cover
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                            startY = 0.5f,
                        ),
                    ),
            )
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
            )
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
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = MR.strings.action_bar_up_description.localized())
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
                        MR.strings.no_chapters_error.localized(),
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
                                    navigator.push(
                                        localReaderScreen(
                                            chapterFile = chapter.file,
                                            mangaName = mangaName,
                                            chapterTitle = chapter.name,
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
