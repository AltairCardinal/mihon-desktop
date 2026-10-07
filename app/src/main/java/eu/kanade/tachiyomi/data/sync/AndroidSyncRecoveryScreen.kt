package eu.kanade.tachiyomi.data.sync

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.screen.SettingsAdvancedScreen
import eu.kanade.presentation.more.settings.screen.SettingsDataScreen
import eu.kanade.tachiyomi.crash.CrashActivity
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsScreenModel
import eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsScreen
import eu.kanade.tachiyomi.ui.browse.extension.extensionsTab
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncRecoveryPlatformAction
import mihon.data.sync.runtime.SyncRuntime
import mihon.domain.manga.model.toDomainManga
import mihon.domain.sync.SyncObjectType
import mihon.feature.migration.config.MigrationConfigScreen
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class AndroidSyncRecoveryScreen(val requestId: String, val action: SyncRecoveryPlatformAction) : Screen {
    @Composable
    override fun Content() {
        val panel = remember { Injekt.get<SyncRuntime>().panel }
        val outer = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val request = panel.state.value.recoveryPlatformRequest?.takeIf {
            it.requestId == requestId && it.action == action
        }
        var target by remember(requestId) { mutableStateOf<Screen?>(null) }
        var loaded by remember(requestId) { mutableStateOf(false) }
        var blocked by remember(requestId) { mutableStateOf(false) }
        DisposableEffect(requestId) {
            onDispose {
                if (panel.state.value.recoveryPlatformRequest?.requestId == requestId) {
                    panel.dispatch(SyncPanelAction.OpenRecovery)
                    if (blocked) {
                        panel.dispatch(SyncPanelAction.RecoveryPlatformFailed(requestId))
                    } else {
                        panel.dispatch(SyncPanelAction.RecoveryPlatformReturned(requestId))
                    }
                }
            }
        }
        LaunchedEffect(requestId) {
            target = when (action) {
                SyncRecoveryPlatformAction.NETWORK -> SettingsAdvancedScreen
                SyncRecoveryPlatformAction.BACKUP -> SettingsDataScreen
                SyncRecoveryPlatformAction.STORAGE -> {
                    context.startActivity(
                        Intent(
                            context,
                            CrashActivity::class.java,
                        )
                            .putExtra("sync-safe-recovery", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    blocked = true
                    null
                }
                SyncRecoveryPlatformAction.UPDATE -> {
                    blocked = true
                    null
                }
                SyncRecoveryPlatformAction.EXTENSIONS -> {
                    val manager = Injekt.get<ExtensionManager>()
                    val packages = request?.sourceIds.orEmpty().mapNotNull(manager::getExtensionPackage).distinct()
                    if (packages.size == 1) {
                        ExtensionDetailsScreen(packages.single())
                    } else {
                        AndroidRecoveryExtensionsScreen(requestId)
                    }
                }
                SyncRecoveryPlatformAction.MIGRATION, SyncRecoveryPlatformAction.READER -> {
                    val key = request?.objects?.singleOrNull()
                    val source = key?.sourceId?.toLongOrNull()
                    val url = when (key?.type) {
                        SyncObjectType.MANGA -> key.originalUrl
                        SyncObjectType.CHAPTER -> if (action == SyncRecoveryPlatformAction.READER) {
                            key.parentUrl
                        } else {
                            null
                        }
                        else -> null
                    }
                    if (source == null || url == null) {
                        blocked = true
                        null
                    } else {
                        try {
                            val repository = Injekt.get<MangaRepository>()
                            var manga = repository.getMangaByUrlAndSourceId(url, source)
                            if (manga == null && action == SyncRecoveryPlatformAction.READER) {
                                val provider = Injekt.get<SourceManager>().get(source)
                                require(provider != null && provider.id == source)
                                val details = withTimeoutOrNull(30_000) {
                                    provider.getMangaDetails(
                                        eu.kanade.tachiyomi.source.model.SManga.create()
                                            .apply {
                                                this.url = url
                                                title = ""
                                            },
                                    )
                                }
                                if (details != null) {
                                    require(details.title.isNotBlank())
                                    details.url = url
                                    manga = Injekt.get<NetworkToLocalManga>().invoke(details.toDomainManga(source))
                                }
                            }
                            if (manga == null) {
                                blocked = true
                                null
                            } else if (action == SyncRecoveryPlatformAction.MIGRATION) {
                                MigrationConfigScreen(manga.id)
                            } else {
                                MangaScreen(manga.id)
                            }
                        } catch (
                            cancelled: CancellationException,
                        ) {
                            throw cancelled
                        } catch (_: Exception) {
                            blocked = true
                            null
                        }
                    }
                }
            }
            if (request == null) blocked = true
            loaded = true
        }
        if (!loaded) {
            Column(Modifier.fillMaxSize().padding(24.dp)) { CircularProgressIndicator() }
        } else if (target != null) {
            Navigator(listOf(AndroidRecoveryLandingScreen(), target!!)) { CurrentScreen() }
        } else {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val blockedReason = if (action == SyncRecoveryPlatformAction.UPDATE) {
                    MR.strings.sync_recovery_no_compatible_package
                } else {
                    MR.strings.sync_recovery_item_identity
                }
                Text(stringResource(blockedReason))
                Button(onClick = { outer.push(SettingsDataScreen) }) {
                    Text(stringResource(MR.strings.sync_recovery_backup))
                }
                TextButton(onClick = outer::pop, modifier = Modifier.testTag("sync-native-recovery-return")) {
                    Text(stringResource(MR.strings.sync_recovery_platform_return))
                }
            }
        }
    }
}

private class AndroidRecoveryLandingScreen : Screen {
    @Composable
    override fun Content() {
        val navigation = LocalNavigator.currentOrThrow
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text(stringResource(MR.strings.sync_recovery_still_remaining))
            Button(onClick = { navigation.parent?.pop() }, modifier = Modifier.testTag("sync-native-recovery-return")) {
                Text(stringResource(MR.strings.sync_recovery_platform_return))
            }
        }
    }
}

private data class AndroidRecoveryExtensionsScreen(val requestId: String) : Screen {
    @Composable
    override fun Content() {
        val model = rememberScreenModel { Injekt.get<ExtensionsScreenModel>() }
        val tab = extensionsTab(model)
        val snackbar = remember { SnackbarHostState() }
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(MR.strings.sync_recovery_extensions))
                Text(stringResource(MR.strings.sync_recovery_item_source))
                val request = Injekt.get<SyncRuntime>().panel.state.value.recoveryPlatformRequest
                    ?.takeIf { it.requestId == requestId }
                val sources = Injekt.get<SourceManager>()
                request?.sourceIds.orEmpty().take(100).forEach { id ->
                    Text(sources.get(id)?.name ?: stringResource(MR.strings.sync_recovery_affected_content))
                }
                Text(stringResource(MR.strings.sync_recovery_source_alternative))
                val navigation = LocalNavigator.currentOrThrow
                TextButton(onClick = { navigation.push(SettingsDataScreen) }) {
                    Text(stringResource(MR.strings.sync_recovery_backup))
                }
            }
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { tab.content(PaddingValues(), snackbar) }
        }
    }
}
