package eu.kanade.tachiyomi.data.sync

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import mihon.data.sync.runtime.SyncPanel
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelPage
import mihon.data.sync.runtime.SyncRuntime
import mihon.presentation.sync.SyncPanelContent
import mihon.presentation.sync.SyncToolbarButton
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

@Composable
internal fun AndroidLibrarySyncAction() {
    val panel = remember { Injekt.get<SyncRuntime>().panel }
    val state by panel.state.collectAsState()
    val toolbarFocus = remember { FocusRequester() }
    var openedFromToolbar by remember { mutableStateOf(false) }
    LaunchedEffect(state.visible) {
        if (!state.visible && openedFromToolbar) {
            toolbarFocus.requestFocus()
            openedFromToolbar = false
        }
    }
    SyncToolbarButton(state, Modifier.focusRequester(toolbarFocus)) {
        openedFromToolbar = true
        panel.dispatch(SyncPanelAction.Open)
    }
    if (state.visible) AndroidSyncPanelSheet(panel)
    DisposableEffect(panel) {
        onDispose { panel.dispatch(SyncPanelAction.Close) }
    }
}

@Composable
private fun AndroidSyncPanelSheet(panel: SyncPanel) {
    val context = LocalContext.current
    val state by panel.state.collectAsState()
    val actions = remember(context) { AndroidSyncPanelActions(context) }
    AdaptiveSheet(
        onDismissRequest = { panel.dispatch(SyncPanelAction.Close) },
        modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp).fillMaxHeight(0.92f).imePadding(),
        maxWidth = 560.dp,
        forceBottom = true,
    ) {
        BackHandler(state.page != SyncPanelPage.MAIN) { panel.dispatch(SyncPanelAction.Back) }
        SyncPanelContent(
            panel,
            onOpenBrowser = actions::openBrowser,
            onCopyCode = actions::copyCode,
            onOpenFailureLog = actions::openFailureLog,
        )
    }
}

internal class AndroidSyncPanelActions(private val context: Context) {
    fun openFailureLog(path: String) {
        try {
            val file = File(path).canonicalFile
            val directory = context.filesDir.resolve("sync-failures").canonicalFile
            require(file.parentFile == directory && file.isFile && file.extension.equals("txt", ignoreCase = true))
            val uri = file.getUriCompat(context)
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/plain")
                clipData = ClipData.newRawUri("Sync failure log", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(view)
            } catch (_: ActivityNotFoundException) {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("Sync failure log", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(share, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (_: Exception) {
            context.toast(MR.strings.sync_failure_log_open_failed)
        }
    }

    fun openBrowser(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host != "github.com") return
        context.openInBrowser(uri, forceDefaultBrowser = true)
    }

    fun copyCode(code: String) {
        try {
            val clip = ClipData.newPlainText("GitHub verification code", code)
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        } catch (_: Exception) {
            context.toast(MR.strings.clipboard_copy_error)
        }
    }
}
