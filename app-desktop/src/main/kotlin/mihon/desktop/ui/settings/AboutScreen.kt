package mihon.desktop.ui.settings

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.platform.DesktopPlatformPaths
import mihon.desktop.APP_VERSION
import tachiyomi.i18n.MR
import java.util.Locale

class AboutScreen(internal val platformPaths: DesktopPlatformPaths = DesktopPlatformPaths.current()) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val paths = platformPaths
        val appDir = remember(paths) { paths.configDir }
        val cacheDir = remember(paths) { paths.networkCacheDir }
        val dbFile = remember(paths) { paths.databaseFile }
        val extensionsDir = remember(paths) { paths.extensionsDir }
        val extensionManager = LocalDesktopUiDependencies.current.extensionManager
        val updateModel = requireNotNull(LocalDesktopUiDependencies.current.updateScreenModel)
        val updateState by updateModel.state.collectAsState()
        val updateFeedback by updateModel.feedback.collectAsState()

        var cacheSizeText by remember {
            mutableStateOf(formatBytes(cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }))
        }
        var cacheCleared by remember { mutableStateOf(false) }

        // Gather debug info once
        val dbSize = remember { formatBytes(dbFile.length()) }
        val dbPath = remember { dbFile.absolutePath }
        val extensionCount = remember { extensionManager.getInstalledExtensions().size }
        val javaVersion = remember { System.getProperty("java.version") ?: MR.strings.desktop_about_unknown.localized() }
        val javaVendor = remember { System.getProperty("java.vendor") ?: "" }
        val osName = remember { System.getProperty("os.name") ?: MR.strings.desktop_about_unknown.localized() }
        val osVersion = remember { System.getProperty("os.version") ?: "" }
        val appDirPath = remember { appDir.absolutePath }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(MR.strings.pref_category_about.localized()) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = MR.strings.action_bar_up_description.localized())
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Mihon Desktop",
                    style = MaterialTheme.typography.headlineMedium,
                )
                AboutUpdateSection(APP_VERSION, updateState.presentation(), updateFeedback, updateModel::intent)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = MR.strings.desktop_about_description.localized(),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = MR.strings.desktop_about_based_on.localized(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                InfoRow(label = MR.strings.website.localized(), value = "https://github.com/mihonapp/mihon")

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text(MR.strings.desktop_about_storage.localized(), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                InfoRow(label = MR.strings.desktop_about_app_data_directory.localized(), value = appDirPath)
                InfoRow(label = MR.strings.desktop_about_database.localized(), value = "$dbPath ($dbSize)")
                InfoRow(label = MR.strings.desktop_about_network_cache.localized(), value = cacheSizeText)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    cacheDir.deleteRecursively()
                    cacheSizeText = "0 B"
                    cacheCleared = true
                }) {
                    Text(MR.strings.desktop_advanced_clear_network_cache.localized())
                }
                if (cacheCleared) Text(MR.strings.desktop_about_network_cache_cleared.localized())

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text(MR.strings.label_extensions.localized(), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                InfoRow(label = MR.strings.desktop_about_installed_extensions.localized(), value = extensionCount.toString())
                InfoRow(label = MR.strings.desktop_about_extensions_directory.localized(), value = extensionsDir.absolutePath)

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text(MR.strings.desktop_about_environment.localized(), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                InfoRow(label = MR.strings.desktop_about_java_version.localized(), value = "$javaVersion ($javaVendor)".trimEnd('(', ' ').trim())
                InfoRow(label = MR.strings.desktop_about_operating_system.localized(), value = "$osName $osVersion".trim())
            }
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

@Composable
internal fun AboutUpdateSection(
    version: String,
    presentation: DesktopUpdatePresentation,
    feedback: String?,
    onIntent: (DesktopUpdateIntent) -> Unit,
) {
    Spacer(modifier = Modifier.height(8.dp))
    Text(MR.strings.desktop_about_version_value.localized(Locale.getDefault(), MR.strings.version.localized(), version), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(modifier = Modifier.height(8.dp))
    Text(presentation.message, style = MaterialTheme.typography.bodyMedium)
    feedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    presentation.progress?.let { LinearProgressIndicator(progress = { it / 100f }) }
    if (presentation.status == "ready") {
        AlertDialog(
            onDismissRequest = { onIntent(DesktopUpdateIntent.DECLINE) },
            title = { Text(presentation.message) },
            text = {
                Column {
                    Text(MR.strings.desktop_update_install_prompt.localized())
                    Button(onClick = { onIntent(DesktopUpdateIntent.MANUAL) }) { Text(MR.strings.update_check_open.localized()) }
                }
            },
            confirmButton = { Button(onClick = { onIntent(DesktopUpdateIntent.CONFIRM) }) { Text(MR.strings.action_install.localized()) } },
            dismissButton = { Button(onClick = { onIntent(DesktopUpdateIntent.DECLINE) }) { Text(MR.strings.action_not_now.localized()) } },
        )
    } else {
        Row {
            presentation.actions.forEach { intent ->
                Button(onClick = { onIntent(intent) }) { Text(intent.label()) }
            }
        }
    }
}

private fun DesktopUpdateIntent.label() = when (this) {
    DesktopUpdateIntent.CHECK -> MR.strings.check_for_updates.localized()
    DesktopUpdateIntent.DOWNLOAD -> MR.strings.update_check_confirm.localized()
    DesktopUpdateIntent.CANCEL, DesktopUpdateIntent.DECLINE -> MR.strings.action_cancel.localized()
    DesktopUpdateIntent.RETRY -> MR.strings.action_retry.localized()
    DesktopUpdateIntent.MANUAL -> MR.strings.update_check_open.localized()
    DesktopUpdateIntent.CONFIRM -> MR.strings.action_install.localized()
}

@Composable
private fun InfoRow(label: String, value: String) {
    androidx.compose.foundation.layout.Row(
        modifier = androidx.compose.ui.Modifier.padding(vertical = 2.dp),
    ) {
        Text(
            text = MR.strings.desktop_about_info_row.localized(Locale.getDefault(), label, value),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
