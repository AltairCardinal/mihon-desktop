package mihon.desktop.ui.download

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.launch
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DownloadItem
import mihon.desktop.download.DownloadStatus
import mihon.domain.error.AppError
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

class DownloadQueueScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val manager = LocalDesktopUiDependencies.current.downloadManager
        val queue by manager.queue.collectAsState()
        val isPaused by manager.isPaused.collectAsState()
        val scope = rememberCoroutineScope()

        // Group downloads by manga title (mirrors Android's by-source grouping)
        val grouped = remember(queue) { queue.groupBy { it.mangaTitle } }

        // Two separate menus — mirrors Android's separate Sort + Overflow pattern
        var showSortMenu by remember { mutableStateOf(false) }
        var showOverflowMenu by remember { mutableStateOf(false) }

        val hasErrors = queue.any { it.status == DownloadStatus.ERROR }

        val lazyListState = rememberLazyListState()
        val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
            // Keys for chapter items are Long chapterId; header keys are strings
            val fromId = from.key as? Long ?: return@rememberReorderableLazyListState
            val toId = to.key as? Long ?: return@rememberReorderableLazyListState
            val currentQueue = queue
            val fromIdx = currentQueue.indexOfFirst { it.chapterId == fromId }
            val toIdx = currentQueue.indexOfFirst { it.chapterId == toId }
            if (fromIdx >= 0 && toIdx >= 0) {
                scope.launch { manager.reorderItem(fromIdx, toIdx) }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Download Queue")
                            if (queue.isNotEmpty()) {
                                Text(
                                    text = "  (${queue.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (queue.isNotEmpty()) {
                            // Sort button — direct action icon (mirrors Android AppBar.Action)
                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                                }
                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Sort A \u2192 Z") },
                                        onClick = {
                                            manager.sortQueue { it.mangaTitle }
                                            showSortMenu = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sort Z \u2192 A") },
                                        onClick = {
                                            manager.sortQueue { it.mangaTitle }
                                            manager.reverseQueue()
                                            showSortMenu = false
                                        },
                                    )
                                }
                            }

                            // Overflow menu (⋮) — mirrors Android AppBar.OverflowAction
                            Box {
                                IconButton(onClick = { showOverflowMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "More options")
                                }
                                DropdownMenu(
                                    expanded = showOverflowMenu,
                                    onDismissRequest = { showOverflowMenu = false },
                                ) {
                                    if (hasErrors) {
                                        DropdownMenuItem(
                                            text = { Text("Retry failed") },
                                            leadingIcon = {
                                                Icon(Icons.Default.Refresh, contentDescription = null)
                                            },
                                            onClick = {
                                                manager.retryErrors()
                                                showOverflowMenu = false
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Clear errors") },
                                            leadingIcon = {
                                                Icon(Icons.Default.Close, contentDescription = null)
                                            },
                                            onClick = {
                                                manager.clearErrors()
                                                showOverflowMenu = false
                                            },
                                        )
                                        HorizontalDivider()
                                    }
                                    DropdownMenuItem(
                                        text = {
                                            Text("Cancel all", color = MaterialTheme.colorScheme.error)
                                        },
                                        onClick = {
                                            manager.cancelAll()
                                            showOverflowMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            },
            // FAB — identical to Android's SmallExtendedFloatingActionButton
            floatingActionButton = {
                if (queue.isNotEmpty()) {
                    ExtendedFloatingActionButton(
                        text = { Text(if (isPaused) "Resume" else "Pause") },
                        icon = {
                            Icon(
                                imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = null,
                            )
                        },
                        onClick = { if (isPaused) manager.resumeAll() else manager.pauseAll() },
                    )
                }
            },
        ) { paddingValues ->
            if (queue.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "No downloads in queue",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                    // Paused banner
                    if (isPaused) {
                        Text(
                            text = "Downloads paused",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        HorizontalDivider()
                    }

                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        grouped.forEach { (mangaTitle, chapterItems) ->
                            // Manga title group header (non-draggable, string key)
                            item(key = "hdr_$mangaTitle") {
                                Text(
                                    text = mangaTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(
                                        start = 4.dp,
                                        top = 8.dp,
                                        bottom = 4.dp,
                                    ),
                                )
                            }

                            items(chapterItems, key = { it.chapterId }) { item ->
                                ReorderableItem(reorderableLazyListState, key = item.chapterId) { isDragging ->
                                    DownloadItemCard(
                                        item = item,
                                        manager = manager,
                                        isDragging = isDragging,
                                        dragHandle = {
                                            Icon(
                                                imageVector = Icons.Default.DragHandle,
                                                contentDescription = "Drag to reorder",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.draggableHandle(),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadItemCard(
    item: DownloadItem,
    manager: DesktopDownloadManager,
    isDragging: Boolean = false,
    dragHandle: (@Composable () -> Unit)? = null,
) {
    val statusLabel = when (item.status) {
        DownloadStatus.QUEUED -> "Waiting\u2026"
        DownloadStatus.DOWNLOADING ->
            if (item.pageUrls.isNotEmpty()) {
                "Downloading ${item.progress} / ${item.pageUrls.size} pages"
            } else {
                "Downloading\u2026"
            }
        DownloadStatus.DONE -> "Done"
        DownloadStatus.ERROR -> item.failure?.let(::downloadFailureMessage) ?: "下载失败，请重试"
        DownloadStatus.CANCELLED -> "Cancelled"
    }
    val statusColor = when (item.status) {
        DownloadStatus.QUEUED -> MaterialTheme.colorScheme.onSurfaceVariant
        DownloadStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
        DownloadStatus.DONE -> MaterialTheme.colorScheme.tertiary
        DownloadStatus.ERROR -> MaterialTheme.colorScheme.error
        DownloadStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val cardColors = when {
        isDragging -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        item.status == DownloadStatus.ERROR -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
        )
        else -> CardDefaults.cardColors()
    }

    Card(modifier = Modifier.fillMaxWidth(), colors = cardColors) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Drag handle (only for QUEUED items — active downloads can't be moved)
                if (dragHandle != null && item.status == DownloadStatus.QUEUED) {
                    dragHandle()
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(text = item.chapterName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                // Action button varies by status (mirrors Android per-item menu)
                when (item.status) {
                    DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> {
                        IconButton(onClick = { manager.cancel(item.chapterId) }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cancel this download",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    DownloadStatus.ERROR -> {
                        IconButton(onClick = { manager.retryItem(item.chapterId) }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Retry",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    else -> {}
                }
            }

            // Progress bar — indeterminate when queued, determinate when downloading
            when (item.status) {
                DownloadStatus.QUEUED -> {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
                DownloadStatus.DOWNLOADING -> {
                    if (item.pageUrls.isNotEmpty()) {
                        LinearProgressIndicator(
                            progress = { item.progress.toFloat() / item.pageUrls.size },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
                else -> {}
            }
        }
    }
}

internal fun downloadFailureMessage(error: AppError): String = when (error) {
    is AppError.MalformedData -> "\u6e90\u8fd4\u56de\u7684\u9875\u9762\u6570\u636e\u65e0\u6548\uff0c\u8bf7\u66f4\u65b0\u6e90\u6216\u7a0d\u540e\u91cd\u8bd5"
    is AppError.Unknown -> "\u6e90\u4e0d\u53ef\u7528\u6216\u53d1\u751f\u672a\u77e5\u9519\u8bef\uff0c\u8bf7\u68c0\u67e5\u6e90\u8bbe\u7f6e\u540e\u91cd\u8bd5"
    is AppError.Network -> "网络连接失败，请检查网络后重试"
    is AppError.Authentication -> "服务器拒绝访问，请检查登录或源设置后重试"
    is AppError.RateLimited -> error.retryAfterSeconds?.let { "请求过于频繁，请在 ${it} 秒后重试" } ?: "请求过于频繁，请稍后重试"
    is AppError.Server -> "服务器错误（HTTP ${error.statusCode}），请稍后重试"
    is AppError.Permission -> "没有写入权限，请检查下载路径后重试"
    is AppError.Storage -> "磁盘空间不足或无法写入，请检查下载路径后重试"
    else -> "下载失败，请重试"
}
