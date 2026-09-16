package mihon.desktop.sync

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.data.sync.runtime.SyncPanel
import mihon.data.sync.runtime.SyncPanelAction
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.DesktopNotification
import mihon.desktop.platform.DesktopFilePicker
import mihon.desktop.platform.DesktopFilePickerRequest
import mihon.desktop.platform.DesktopFilePickerResult
import mihon.desktop.platform.DesktopShareResult
import mihon.desktop.platform.toDesktopNotification
import mihon.domain.sync.crypto.SyncRecoveryCodec
import mihon.presentation.sync.SyncPanelContent
import mihon.presentation.sync.SyncToolbarButton
import tachiyomi.i18n.MR
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopLibrarySyncAction() {
    val dependencies = LocalDesktopUiDependencies.current
    val panel = dependencies.syncPanel ?: return
    val state by panel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val files = remember(dependencies.filePicker, panel) {
        DesktopSyncRecoveryFiles(dependencies.filePicker) {
            dependencies.notificationService.post(
                DesktopNotification(MR.strings.sync_title.localized(), MR.strings.sync_file_error.localized()),
            )
        }
    }
    DisposableEffect(panel) {
        onDispose { panel.dispatch(SyncPanelAction.Close) }
    }
    SyncToolbarButton(state) { panel.dispatch(SyncPanelAction.Open) }
    if (state.visible) {
        ModalBottomSheet(
            onDismissRequest = { panel.dispatch(SyncPanelAction.Close) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            sheetMaxWidth = 560.dp,
            modifier = Modifier.heightIn(max = 720.dp).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                    panel.dispatch(SyncPanelAction.Back)
                    true
                } else {
                    false
                }
            },
        ) {
            SyncPanelContent(
                panel,
                modifier = Modifier.fillMaxWidth().heightIn(max = 680.dp),
                onOpenBrowser = {
                    try {
                        uriHandler.openUri(it)
                    } catch (_: Exception) {
                        dependencies.notificationService.post(
                            DesktopNotification(
                                MR.strings.sync_title.localized(),
                                MR.strings.unknown_error.localized(),
                            ),
                        )
                    }
                },
                onCopyCode = {
                    val result = dependencies.shareService.copyText(it)
                    if (result is DesktopShareResult.Failed || result is DesktopShareResult.Unavailable) {
                        dependencies.notificationService.post(result.toDesktopNotification())
                    }
                },
                onSaveRecovery = { scope.launch { files.save(panel, it) } },
                onImportRecovery = { scope.launch { files.load(panel) } },
            )
        }
    }
}

class DesktopSyncRecoveryFiles(
    private val picker: DesktopFilePicker,
    private val onFailure: () -> Unit = {},
) {
    suspend fun save(panel: SyncPanel, text: String): Boolean = perform(
        DesktopFilePickerRequest.SaveFile(MR.strings.sync_recovery.localized(), "mihon-sync-recovery.json"),
    ) { result ->
        SyncRecoveryCodec.decode(text).getOrThrow()
        withContext(Dispatchers.IO) {
            FileOutputStream(result.file).use {
                it.write(text.toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
        }
        panel.dispatch(SyncPanelAction.RecoverySaved(text))
    }

    suspend fun load(panel: SyncPanel): Boolean = perform(
        DesktopFilePickerRequest.OpenFile(MR.strings.sync_recovery.localized(), "JSON", setOf("json")),
    ) { result ->
        val text = withContext(Dispatchers.IO) {
            val bytes = result.file.inputStream().use { it.readNBytes(16 * 1024 + 1) }
            require(bytes.size <= 16 * 1024)
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }
        SyncRecoveryCodec.decode(text).getOrThrow()
        panel.dispatch(SyncPanelAction.SetRecovery(text))
    }

    private suspend fun perform(
        request: DesktopFilePickerRequest,
        operation: suspend (DesktopFilePickerResult.Selected) -> Unit,
    ): Boolean = try {
        when (val result = picker.choose(request)) {
            is DesktopFilePickerResult.Selected -> {
                operation(result)
                true
            }
            is DesktopFilePickerResult.Failed -> {
                onFailure()
                false
            }
            DesktopFilePickerResult.Cancelled -> false
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        onFailure()
        false
    }
}
