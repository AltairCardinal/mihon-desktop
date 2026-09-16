package eu.kanade.tachiyomi.data.sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.data.sync.runtime.SyncPanel
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelPage
import mihon.data.sync.runtime.SyncRuntime
import mihon.presentation.sync.SyncPanelContent
import mihon.presentation.sync.SyncToolbarButton
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

@Composable
internal fun AndroidLibrarySyncAction() {
    val panel = remember { Injekt.get<SyncRuntime>().panel }
    val state by panel.state.collectAsState()
    SyncToolbarButton(state) { panel.dispatch(SyncPanelAction.Open) }
    if (state.visible) AndroidSyncPanelSheet(panel)
    DisposableEffect(panel) {
        onDispose { panel.dispatch(SyncPanelAction.Close) }
    }
}

@Composable
private fun AndroidSyncPanelSheet(panel: SyncPanel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by panel.state.collectAsState()
    val files = remember(context, panel) { AndroidSyncPanelFiles(context, panel) }
    val actions = remember(context) { AndroidSyncPanelActions(context) }
    var saveMaterial by remember { mutableStateOf<String?>(null) }
    var fileError by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val material = saveMaterial
        saveMaterial = null
        if (uri != null && material != null) scope.launch { fileError = !files.save(uri, material) }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { fileError = !files.import(uri) }
    }
    AdaptiveSheet(
        onDismissRequest = { panel.dispatch(SyncPanelAction.Close) },
        modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp).fillMaxHeight(0.92f).imePadding(),
        maxWidth = 560.dp,
        forceBottom = true,
    ) {
        BackHandler(state.page != SyncPanelPage.MAIN) { panel.dispatch(SyncPanelAction.Back) }
        Column {
            if (fileError) Text(stringResource(MR.strings.sync_file_error), color = MaterialTheme.colorScheme.error)
            SyncPanelContent(
                panel,
                onOpenBrowser = actions::openBrowser,
                onCopyCode = actions::copyCode,
                onSaveRecovery = { material ->
                    saveMaterial = material
                    try {
                        save.launch("mihon-sync-recovery.json")
                    } catch (_: Exception) {
                        saveMaterial = null
                        fileError = true
                    }
                },
                onImportRecovery = {
                    try {
                        import.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    } catch (_: Exception) {
                        fileError = true
                    }
                },
            )
        }
    }
}

internal class AndroidSyncPanelFiles(private val context: Context, private val panel: SyncPanel) {
    suspend fun save(uri: Uri, recovery: String): Boolean = safe {
        withContext(Dispatchers.IO) {
            val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(recovery))
            require(encoded.remaining() <= MAX_BYTES)
            val bytes = ByteArray(encoded.remaining()).also(encoded::get)
            try {
                requireNotNull(context.contentResolver.openAssetFileDescriptor(uri, "wt")).use { descriptor ->
                    descriptor.createOutputStream().use {
                        it.write(bytes)
                        it.flush()
                    }
                }
            } finally {
                bytes.fill(0)
            }
        }
        panel.dispatch(SyncPanelAction.RecoverySaved(recovery))
    }

    suspend fun import(uri: Uri): Boolean = safe {
        val recovery = withContext(Dispatchers.IO) {
            val bytes = ByteArray(MAX_BYTES + 1)
            try {
                var count = 0
                requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                    while (count < bytes.size) {
                        val read = input.read(bytes, count, bytes.size - count)
                        if (read < 0) break
                        check(read > 0)
                        count += read
                    }
                }
                require(count <= MAX_BYTES)
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, count)).toString()
            } finally {
                bytes.fill(0)
            }
        }
        panel.dispatch(SyncPanelAction.SetRecovery(recovery))
    }

    private suspend fun safe(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val MAX_BYTES = 16 * 1024
    }
}

internal class AndroidSyncPanelActions(private val context: Context) {
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
