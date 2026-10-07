package mihon.desktop.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import mihon.desktop.platform.DesktopFilePickerRequest
import mihon.desktop.platform.DesktopFilePickerResult
import mihon.desktop.platform.DesktopPlatformPaths
import mihon.desktop.platform.DesktopRecoveryProfile
import mihon.desktop.ui.settings.DesktopDirectoryOpener
import mihon.data.sync.runtime.SyncRecoveryPlatformAction
import mihon.data.sync.runtime.SyncPanelAction
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.ui.settings.GeneralSettingsScreen
import mihon.desktop.ui.settings.BackupSettingsScreen
import mihon.desktop.ui.extension.ExtensionListScreen
import mihon.desktop.ui.extension.ExtensionDetailsScreen
import mihon.desktop.ui.library.MangaDetailScreen
import mihon.desktop.ui.migration.MigrationSearchScreen
import mihon.domain.sync.SyncObjectType
import tachiyomi.i18n.MR

internal val LocalDesktopRecoveryRestart = staticCompositionLocalOf<(suspend (File) -> Boolean)?> { null }

data class DesktopSyncRecoveryScreen(val requestId: String, val action: SyncRecoveryPlatformAction) : Screen {
    @Composable
    override fun Content() {
        val dependencies = LocalDesktopUiDependencies.current
        val panel = dependencies.syncPanel
        val outer = LocalNavigator.currentOrThrow
        val request = panel?.state?.value?.recoveryPlatformRequest?.takeIf { it.requestId == requestId && it.action == action }
        var target by remember(requestId) { mutableStateOf<Screen?>(null) }
        var loaded by remember(requestId) { mutableStateOf(false) }
        var blocked by remember(requestId) { mutableStateOf(false) }
        val savedDoh = remember(requestId) { dependencies.appPreferences.dohProvider.get() }
        fun restartRequired(): Boolean = action == SyncRecoveryPlatformAction.NETWORK && runCatching {
            dependencies.appPreferences.globalNetworkMode.get() != dependencies.networkRoutingPort.activeGlobalMode ||
                dependencies.appPreferences.proxyRuntimeConfig() != dependencies.networkRoutingPort.activeGlobalProxy ||
                dependencies.appPreferences.dohProvider.get() != dependencies.networkHelper.activeDohProvider
        }.getOrDefault(dependencies.appPreferences.dohProvider.get() != savedDoh)
        fun returned() {
            if (panel?.state?.value?.recoveryPlatformRequest?.requestId != requestId) return
            panel.dispatch(SyncPanelAction.OpenRecovery)
            if (blocked) panel?.dispatch(SyncPanelAction.RecoveryPlatformFailed(requestId))
            else panel?.dispatch(SyncPanelAction.RecoveryPlatformReturned(requestId, restartRequired()))
        }
        DisposableEffect(requestId) { onDispose { returned() } }
        LaunchedEffect(requestId) {
            target = when (action) {
                SyncRecoveryPlatformAction.NETWORK -> GeneralSettingsScreen()
                SyncRecoveryPlatformAction.EXTENSIONS -> {
                    val packages = request?.sourceIds.orEmpty().mapNotNull(dependencies.extensionManager::getExtensionPackage)
                        .distinct()
                    val installed = dependencies.extensionManager.getInstalledExtensions().singleOrNull {
                        it.jarFile.nameWithoutExtension == packages.singleOrNull()
                    }
                    if (installed != null) ExtensionDetailsScreen(installed.jarFile.absolutePath) else DesktopRecoveryExtensionsScreen(requestId)
                }
                SyncRecoveryPlatformAction.BACKUP -> BackupSettingsScreen()
                SyncRecoveryPlatformAction.UPDATE, SyncRecoveryPlatformAction.STORAGE -> null
                SyncRecoveryPlatformAction.MIGRATION, SyncRecoveryPlatformAction.READER -> {
                    val key = request?.objects?.singleOrNull()
                    val source = key?.sourceId?.toLongOrNull()
                    val url = when (key?.type) {
                        SyncObjectType.MANGA -> key.originalUrl
                        SyncObjectType.CHAPTER -> if (action == SyncRecoveryPlatformAction.READER) key.parentUrl else null
                        else -> null
                    }
                    if (source == null || url == null) {
                        blocked = true
                        null
                    } else try {
                        var manga = dependencies.mangaRepository.getMangaByUrlAndSourceId(url, source)
                        if (manga == null && action == SyncRecoveryPlatformAction.READER) {
                            val provider = dependencies.sourceManager.get(source)
                            require(provider != null && provider.id == source)
                            val listed = eu.kanade.tachiyomi.source.model.SManga.create().apply { this.url = url; title = "" }
                            manga = withTimeoutOrNull(30_000) {
                                dependencies.saveSourceMangaForDetails.awaitFromSource(provider, listed)
                            }
                            if (manga != null) require(manga.title.isNotBlank() && manga.source == source && manga.url == url)
                        }
                        if (manga == null) { blocked = true; null }
                        else if (action == SyncRecoveryPlatformAction.MIGRATION) MigrationSearchScreen(manga.id, manga.title)
                        else MangaDetailScreen(manga.id)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { blocked = true; null }
                }
            }
            if (action == SyncRecoveryPlatformAction.UPDATE || request == null) blocked = true
            loaded = true
        }
        if (!loaded) Column(Modifier.fillMaxSize().padding(24.dp)) { CircularProgressIndicator() }
        else if (action == SyncRecoveryPlatformAction.STORAGE) {
            DesktopInApplicationStorageRecovery(onBack = outer::pop)
        } else if (target != null) {
            Navigator(listOf(DesktopRecoveryLandingScreen(requestId, action), target!!)) { CurrentScreen() }
        } else {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (action == SyncRecoveryPlatformAction.UPDATE) MR.strings.sync_recovery_no_compatible_package.localized()
                    else MR.strings.sync_recovery_item_identity.localized())
                Text(MR.strings.sync_recovery_still_remaining.localized())
                Button(onClick = { outer.push(BackupSettingsScreen()) }) { Text(MR.strings.sync_recovery_backup.localized()) }
                TextButton(onClick = outer::pop) { Text(MR.strings.sync_recovery_platform_return.localized()) }
            }
        }
    }
}

private data class DesktopRecoveryLandingScreen(val requestId: String, val action: SyncRecoveryPlatformAction) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(MR.strings.sync_recovery_still_remaining.localized())
            Button(onClick = { navigator.parent?.pop() }, modifier = Modifier.testTag("sync-native-recovery-return")) {
                Text(MR.strings.sync_recovery_platform_return.localized())
            }
        }
    }
}

@Composable
private fun DesktopInApplicationStorageRecovery(onBack: () -> Unit) {
    val dependencies = LocalDesktopUiDependencies.current
    val restart = LocalDesktopRecoveryRestart.current
    val original = remember { DesktopPlatformPaths.current(createDirectories = false) }
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    DesktopStartupRecoveryContent(original.configDir.path, destination?.path, busy, failed,
        onChoose = {
            scope.launch {
                when (val choice = dependencies.filePicker.choose(DesktopFilePickerRequest.Directory(
                    MR.strings.sync_recovery_safe_choose.localized()))) {
                    is DesktopFilePickerResult.Selected -> destination = choice.file
                    is DesktopFilePickerResult.Failed -> failed = true
                    DesktopFilePickerResult.Cancelled -> Unit
                }
            }
        },
        onOpenOriginal = {
            failed = !original.configDir.isDirectory || !DesktopDirectoryOpener.open(original.configDir)
        }, onClose = onBack)
    destination?.let { selected ->
        AlertDialog(onDismissRequest = { if (!busy) destination = null },
            title = { Text(MR.strings.sync_recovery_safe_new.localized()) },
            text = { Text(MR.strings.sync_recovery_safe_confirm.localized(
                java.util.Locale.getDefault(), original.configDir.path, selected.path)) },
            confirmButton = {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val launched = runCatching {
                            val root = DesktopRecoveryProfile.create(selected, original)
                            requireNotNull(restart).invoke(root)
                        }.getOrDefault(false)
                        busy = false
                        failed = !launched
                        destination = null
                    }
                }) { Text(MR.strings.sync_confirm.localized()) }
            }, dismissButton = {
                TextButton(enabled = !busy, onClick = { destination = null }) { Text(MR.strings.sync_cancel.localized()) }
            })
    }
}

private data class DesktopRecoveryExtensionsScreen(val requestId: String) : Screen {
    @Composable
    override fun Content() {
        val dependencies = LocalDesktopUiDependencies.current
        val request = dependencies.syncPanel?.state?.value?.recoveryPlatformRequest
            ?.takeIf { it.requestId == requestId }
        val navigator = LocalNavigator.currentOrThrow
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(MR.strings.sync_recovery_extensions.localized(), style = MaterialTheme.typography.titleMedium)
                Text(MR.strings.sync_recovery_item_source.localized())
                request?.sourceIds.orEmpty().take(100).forEach { id ->
                    Text(dependencies.sourceManager.get(id)?.name ?: MR.strings.sync_recovery_affected_content.localized())
                }
                Text(MR.strings.sync_recovery_source_alternative.localized())
                TextButton(onClick = { navigator.push(BackupSettingsScreen()) }) {
                    Text(MR.strings.sync_recovery_backup.localized())
                }
            }
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { ExtensionListScreen().Content() }
        }
    }
}
