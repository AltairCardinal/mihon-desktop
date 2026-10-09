package eu.kanade.tachiyomi.data.sync

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncRecoveryPlatformAction
import mihon.data.sync.runtime.SyncRecoveryPlatformResult
import mihon.data.sync.runtime.SyncRuntime
import mihon.domain.manga.model.toDomainManga
import mihon.domain.sync.SyncObjectType
import mihon.feature.migration.config.MigrationConfigScreen
import mihon.presentation.sync.SyncCompatibilityContent
import mihon.presentation.sync.SyncCompatibilityFeedback
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal val LocalSyncBackupRecoveryRequest = staticCompositionLocalOf<String?> { null }

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
        var result by remember(requestId) {
            mutableStateOf<SyncRecoveryPlatformResult>(SyncRecoveryPlatformResult.NoChange)
        }
        val networkPreferences = remember(requestId) {
            if (action ==
                SyncRecoveryPlatformAction.NETWORK
            ) {
                Injekt.get<eu.kanade.tachiyomi.network.NetworkPreferences>()
            } else {
                null
            }
        }
        val savedDoh = remember(requestId) { networkPreferences?.dohProvider()?.get() }
        val extensionManager = remember(requestId) {
            if (action == SyncRecoveryPlatformAction.EXTENSIONS) Injekt.get<ExtensionManager>() else null
        }
        val savedExtensions = remember(requestId) { extensionManager?.installedExtensionsFlow?.value }
        DisposableEffect(requestId) {
            onDispose {
                if (panel.state.value.recoveryPlatformRequest?.requestId == requestId) {
                    panel.dispatch(SyncPanelAction.OpenRecovery)
                    val actual = when {
                        blocked -> SyncRecoveryPlatformResult.Failed("TARGET_UNAVAILABLE")
                        networkPreferences != null && networkPreferences.dohProvider().get() != savedDoh -> {
                            SyncRecoveryPlatformResult.RestartRequired
                        }
                        extensionManager != null &&
                            extensionManager.installedExtensionsFlow.value != savedExtensions ->
                            SyncRecoveryPlatformResult.Changed(request?.objects.orEmpty())
                        else -> panel.state.value.recoveryPlatformResult ?: result
                    }
                    panel.dispatch(SyncPanelAction.RecoveryPlatformCompleted(requestId, actual))
                }
            }
        }
        LaunchedEffect(requestId) {
            target = when (action) {
                SyncRecoveryPlatformAction.NETWORK -> SettingsAdvancedScreen
                SyncRecoveryPlatformAction.BACKUP -> SettingsDataScreen
                SyncRecoveryPlatformAction.STORAGE -> {
                    try {
                        context.startActivity(
                            Intent(context, CrashActivity::class.java)
                                .putExtra("sync-safe-recovery", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (_: Exception) {
                        blocked = true
                    }
                    null
                }
                SyncRecoveryPlatformAction.UPDATE -> {
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
        } else if (action == SyncRecoveryPlatformAction.UPDATE && request != null) {
            AndroidRecoveryCompatibilityContent(
                canInstall = !panel.state.value.recoveryPersistenceFailed,
                onResult = { result = it },
                onBack = outer::pop,
            )
        } else if (target != null) {
            CompositionLocalProvider(
                LocalSyncBackupRecoveryRequest provides requestId.takeIf {
                    action == SyncRecoveryPlatformAction.BACKUP
                },
            ) {
                Navigator(listOf(AndroidRecoveryLandingScreen(), target!!)) { CurrentScreen() }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val blockedReason = if (action == SyncRecoveryPlatformAction.UPDATE) {
                    MR.strings.sync_recovery_no_compatible_package
                } else {
                    MR.strings.sync_recovery_item_identity
                }
                Text(stringResource(blockedReason))
                Button(onClick = {
                    panel.dispatch(SyncPanelAction.OpenRecovery)
                    panel.dispatch(SyncPanelAction.OpenRecoveryPlatform(SyncRecoveryPlatformAction.BACKUP))
                    outer.pop()
                }) {
                    Text(stringResource(MR.strings.sync_recovery_backup))
                }
                TextButton(onClick = outer::pop, modifier = Modifier.testTag("sync-native-recovery-return")) {
                    Text(stringResource(MR.strings.sync_recovery_platform_return))
                }
            }
        }
    }
}

@Composable
private fun AndroidRecoveryCompatibilityContent(
    canInstall: Boolean,
    onResult: (SyncRecoveryPlatformResult) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val installer = remember(context) { AndroidRecoveryPackageInstaller(context) }
    val scope = rememberCoroutineScope()
    var feedback by remember { mutableStateOf(SyncCompatibilityFeedback.NOT_CHECKED) }
    var ready by remember { mutableStateOf<AndroidRecoveryPackageVerification.Ready?>(null) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        ready = null
        if (uri == null) {
            feedback = SyncCompatibilityFeedback.CANCELLED
            onResult(SyncRecoveryPlatformResult.Cancelled)
        } else {
            feedback = SyncCompatibilityFeedback.SELECTING
            scope.launch {
                val verified = withContext(Dispatchers.IO) { installer.select(uri) }
                ready = verified as? AndroidRecoveryPackageVerification.Ready
                feedback = if (ready != null) {
                    SyncCompatibilityFeedback.VERIFIED
                } else {
                    SyncCompatibilityFeedback.REJECTED
                }
                if (ready == null) onResult(SyncRecoveryPlatformResult.Failed("PACKAGE_REJECTED"))
            }
        }
    }
    SyncCompatibilityContent(
        version = eu.kanade.tachiyomi.BuildConfig.VERSION_NAME,
        feedback = feedback,
        onCheck = {
            // Fork builds intentionally disable the upstream updater and have no trusted fork release source.
            feedback = SyncCompatibilityFeedback.NO_TRUSTED_SOURCE
            onResult(SyncRecoveryPlatformResult.NoChange)
        },
        onChoose = {
            try {
                chooser.launch(arrayOf("application/vnd.android.package-archive"))
            } catch (_: Exception) {
                feedback = SyncCompatibilityFeedback.FAILED
                onResult(SyncRecoveryPlatformResult.Failed("PACKAGE_PICKER_UNAVAILABLE"))
            }
        },
        onInstall = {
            scope.launch {
                val candidate = ready
                feedback = SyncCompatibilityFeedback.SELECTING
                if (canInstall && candidate != null && installer.install(candidate)) {
                    feedback = SyncCompatibilityFeedback.RESTART_REQUIRED
                    onResult(SyncRecoveryPlatformResult.RestartRequired)
                } else {
                    feedback = SyncCompatibilityFeedback.FAILED
                    onResult(SyncRecoveryPlatformResult.Failed("PACKAGE_INSTALL_FAILED"))
                }
            }
        },
        onBack = onBack,
    )
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
                TextButton(onClick = {
                    val panel = Injekt.get<SyncRuntime>().panel
                    panel.dispatch(SyncPanelAction.OpenRecovery)
                    panel.dispatch(SyncPanelAction.OpenRecoveryPlatform(SyncRecoveryPlatformAction.BACKUP))
                    navigation.parent?.pop()
                }) {
                    Text(stringResource(MR.strings.sync_recovery_backup))
                }
            }
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { tab.content(PaddingValues(), snackbar) }
        }
    }
}
