package mihon.desktop.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.desktop.platform.DesktopFilePickerRequest
import mihon.desktop.platform.DesktopFilePickerResult
import mihon.desktop.platform.DesktopPlatformPaths
import mihon.desktop.platform.DesktopRecoveryLauncher
import mihon.desktop.platform.DesktopRecoveryProfile
import mihon.desktop.platform.DesktopUriSchemeRegistration
import mihon.desktop.platform.OperatingSystem
import mihon.desktop.platform.SwingDesktopFilePicker
import mihon.desktop.ui.settings.DesktopDirectoryOpener
import tachiyomi.i18n.MR

/** Needs neither DI nor an open database, so it remains available after startup storage failures. */
object DesktopStartupRecovery {
    fun show(failure: Throwable, requestedProfile: String? = null) {
        // The diagnostic exception is deliberately not rendered: it can contain credentials or raw payloads.
        val requested = requestedProfile ?: System.getenv(DesktopRecoveryProfile.ENVIRONMENT_KEY)
        val original = if (!requested.isNullOrBlank()) {
            DesktopPlatformPaths.preservedRecoveryPaths(File(requested).absoluteFile)
        } else runCatching { DesktopPlatformPaths.current(createDirectories = false) }.getOrElse {
            DesktopPlatformPaths.resolve(System.getProperty("os.name"), System.getProperty("user.home"),
                System.getenv() - DesktopRecoveryProfile.ENVIRONMENT_KEY, createDirectories = false)
        }
        application {
            Window(onCloseRequest = ::exitApplication, title = MR.strings.sync_recovery_choose.localized(),
                state = rememberWindowState(width = 600.dp, height = 620.dp)) {
                var selected by remember { mutableStateOf<File?>(null) }
                var confirm by remember { mutableStateOf(false) }
                var busy by remember { mutableStateOf(false) }
                var failed by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                val picker = remember { SwingDesktopFilePicker() }
                MaterialTheme {
                    Surface(Modifier.fillMaxSize()) {
                        DesktopStartupRecoveryContent(
                            original.configDir.path, selected?.path, busy, failed,
                            onChoose = {
                                scope.launch {
                                    when (val result = picker.choose(DesktopFilePickerRequest.Directory(
                                        MR.strings.sync_recovery_safe_choose.localized()))) {
                                        is DesktopFilePickerResult.Selected -> { selected = result.file; confirm = true }
                                        is DesktopFilePickerResult.Failed -> failed = true
                                        DesktopFilePickerResult.Cancelled -> Unit
                                    }
                                }
                            },
                            onOpenOriginal = {
                                failed = !original.configDir.isDirectory || !DesktopDirectoryOpener.open(original.configDir)
                            },
                            onClose = ::exitApplication,
                        )
                    }
                    if (confirm && selected != null) {
                        AlertDialog(
                            onDismissRequest = { if (!busy) confirm = false },
                            title = { Text(MR.strings.sync_recovery_safe_new.localized()) },
                            text = { Text(MR.strings.sync_recovery_safe_confirm.localized(
                                java.util.Locale.getDefault(), original.configDir.path, selected!!.path)) },
                            confirmButton = {
                                Button(enabled = !busy, onClick = {
                                    val destination = selected ?: return@Button
                                    busy = true
                                    failed = false
                                    scope.launch {
                                        val launched = withContext(Dispatchers.IO) {
                                            runCatching {
                                                val executable = DesktopUriSchemeRegistration.currentExecutable()
                                                require(DesktopUriSchemeRegistration.isPackagedExecutable(
                                                    OperatingSystem.detect(), executable))
                                                val root = DesktopRecoveryProfile.create(destination, original)
                                                DesktopRecoveryLauncher.launch(root, executable)
                                            }.getOrDefault(false)
                                        }
                                        busy = false
                                        failed = !launched
                                        confirm = false
                                        if (launched) exitApplication()
                                    }
                                }) { Text(MR.strings.sync_confirm.localized()) }
                            },
                            dismissButton = {
                                TextButton(enabled = !busy, onClick = { confirm = false }) {
                                    Text(MR.strings.sync_cancel.localized())
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun DesktopStartupRecoveryContent(
    original: String,
    selected: String?,
    busy: Boolean,
    failed: Boolean,
    onChoose: () -> Unit,
    onOpenOriginal: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(MR.strings.sync_recovery_choose.localized(), style = MaterialTheme.typography.headlineSmall)
        Text(MR.strings.sync_recovery_safe_body.localized())
        Text(original)
        selected?.let { Text(it) }
        if (failed) Text(MR.strings.sync_recovery_safe_failed.localized(), color = MaterialTheme.colorScheme.error)
        Button(onClick = onChoose, enabled = !busy, modifier = Modifier.testTag("sync-startup-recovery-new")) {
            Text(MR.strings.sync_recovery_safe_new.localized())
        }
        TextButton(onClick = onOpenOriginal, enabled = !busy,
            modifier = Modifier.testTag("sync-startup-recovery-original")) {
            Text(MR.strings.sync_recovery_safe_original.localized())
        }
        TextButton(onClick = onClose, enabled = !busy) { Text(MR.strings.sync_close.localized()) }
    }
}
