package eu.kanade.tachiyomi.data.sync

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** Rendered by the existing error-handler process, without business DI or a database connection. */
@Composable
fun AndroidSafeRecoveryContent() {
    val context = LocalContext.current
    val raw = remember(context) { AndroidRecoveryProfile.raw(context) }
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val origin = remember(raw) { AndroidRecoveryProfile.selectedDatabaseDescription(raw) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(MR.strings.sync_recovery_choose), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(MR.strings.sync_recovery_safe_body))
        Text(origin)
        if (failed) Text(stringResource(MR.strings.sync_recovery_safe_failed), color = MaterialTheme.colorScheme.error)
        Button(enabled = !busy, onClick = { confirming = true }, modifier = Modifier.testTag("sync-safe-new-profile")) {
            Text(stringResource(MR.strings.sync_recovery_safe_new))
        }
        TextButton(enabled = !busy, onClick = {
            confirming = false
        }, modifier = Modifier.testTag("sync-safe-original-profile")) {
            Text(stringResource(MR.strings.sync_recovery_return_original_instance))
        }
    }
    confirming?.let { createNew ->
        val titleResource = if (createNew) {
            MR.strings.sync_recovery_safe_new
        } else {
            MR.strings.sync_recovery_return_original_instance
        }
        AlertDialog(
            onDismissRequest = { if (!busy) confirming = null },
            title = { Text(stringResource(titleResource)) },
            text = { Text(stringResource(MR.strings.sync_recovery_safe_body)) },
            confirmButton = {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val switched = try {
                            val stop: suspend () -> Boolean = { stopMainProcess(raw) }
                            val start = {
                                raw.startActivity(
                                    Intent(raw, MainActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                                )
                            }
                            if (createNew) {
                                AndroidRecoverySwitch.createAndRestart(raw, stop, start)
                            } else if (stop()) {
                                val current = AndroidRecoveryProfile.selected(raw)
                                val previous = current?.let { AndroidRecoveryProfile.previousSelection(raw, it) }
                                val editor = raw.getSharedPreferences(
                                    AndroidRecoveryProfile.CONTROL_PREFERENCES,
                                    0,
                                ).edit()
                                if (previous ==
                                    null
                                ) {
                                    editor.remove("selected")
                                } else {
                                    editor.putString("selected", previous)
                                }
                                check(editor.commit())
                                start()
                                true
                            } else {
                                false
                            }
                        } catch (
                            cancelled: kotlinx.coroutines.CancellationException,
                        ) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                        busy = false
                        failed = !switched
                        confirming = null
                        if (switched) (context as? android.app.Activity)?.finish()
                    }
                }) { Text(stringResource(MR.strings.sync_confirm)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = {
                    confirming = null
                }) { Text(stringResource(MR.strings.sync_cancel)) }
            },
        )
    }
}

private suspend fun stopMainProcess(raw: Context): Boolean = withContext(Dispatchers.IO) {
    val manager = raw.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val main = manager.runningAppProcesses.orEmpty().filter {
        it.processName == BuildConfig.APPLICATION_ID && it.pid != Process.myPid()
    }
    main.forEach { Process.killProcess(it.pid) }
    repeat(100) {
        if (manager.runningAppProcesses.orEmpty().none { process ->
                main.any { it.pid == process.pid }
            }
        ) {
            return@withContext true
        }
        delay(25)
    }
    main.isEmpty()
}
