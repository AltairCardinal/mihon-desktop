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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import mihon.data.sync.runtime.SyncPanelAction
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.DesktopNotification
import mihon.desktop.platform.DesktopShareResult
import mihon.desktop.platform.toDesktopNotification
import mihon.presentation.sync.SyncPanelContent
import mihon.presentation.sync.SyncToolbarButton
import tachiyomi.i18n.MR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopLibrarySyncAction() {
    val dependencies = LocalDesktopUiDependencies.current
    val panel = dependencies.syncPanel ?: return
    val state by panel.state.collectAsState()
    val toolbarFocus = remember { FocusRequester() }
    var openedFromToolbar by remember { mutableStateOf(false) }
    LaunchedEffect(state.visible) {
        if (!state.visible && openedFromToolbar) {
            toolbarFocus.requestFocus()
            openedFromToolbar = false
        }
    }
    val uriHandler = LocalUriHandler.current
    DisposableEffect(panel) {
        onDispose { panel.dispatch(SyncPanelAction.Close) }
    }
    SyncToolbarButton(state, Modifier.focusRequester(toolbarFocus)) {
        openedFromToolbar = true
        panel.dispatch(SyncPanelAction.Open)
    }
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
                onOpenFailureLog = { path ->
                    if (!DesktopSyncFailureLogOpener.open(path)) {
                        dependencies.notificationService.post(
                            DesktopNotification(
                                MR.strings.sync_title.localized(),
                                MR.strings.sync_failure_log_open_failed.localized(),
                            ),
                        )
                    }
                },
            )
        }
    }
}
