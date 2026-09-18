package eu.kanade.tachiyomi.ui.browse.extension

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.ExtensionScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.more.settings.screen.browse.ExtensionReposScreen
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.suggestion.SuggestionBatchPause
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun extensionsTab(
    extensionsScreenModel: ExtensionsScreenModel,
): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checkedMessage = stringResource(MR.strings.extension_suggestions_checked)

    val state by extensionsScreenModel.state.collectAsState()
    val systemPause = state.suggestionBatch.items.asSequence()
        .filter { state.suggestionBatch.running && it.result == null }
        .mapNotNull { state.pendingSystemPauses[it.artifact.packageName] }
        .firstOrNull() ?: state.suggestionBatch.pauseReason
    val pauseExplanation = when (systemPause) {
        SuggestionBatchPause.SERVICE -> stringResource(MR.strings.ext_installer_shizuku_stopped)
        SuggestionBatchPause.PERMISSION -> stringResource(MR.strings.extension_batch_permission)
        SuggestionBatchPause.APP_FOREGROUND -> stringResource(MR.strings.extension_batch_foreground)
        SuggestionBatchPause.INSTALLER_CHANGED -> stringResource(MR.strings.extension_batch_installer_changed)
        else -> null
    }
    var privateExtensionToUninstall by remember { mutableStateOf<Extension?>(null) }

    return TabContent(
        titleRes = MR.strings.label_extensions,
        badgeNumber = state.updates.takeIf { it > 0 },
        searchEnabled = true,
        actions = persistentListOf(
            AppBar.OverflowAction(
                title = stringResource(MR.strings.action_filter),
                onClick = { navigator.push(ExtensionFilterScreen()) },
            ),
            AppBar.OverflowAction(
                title = stringResource(MR.strings.label_extension_repos),
                onClick = { navigator.push(ExtensionReposScreen()) },
            ),
        ),
        content = { contentPadding, snackbarHostState ->
            BackHandler(enabled = state.searchQuery != null) {
                extensionsScreenModel.search(null)
            }

            ExtensionScreen(
                state = state,
                contentPadding = contentPadding,
                searchQuery = state.searchQuery,
                onLongClickItem = { extension ->
                    when (extension) {
                        is Extension.Available -> extensionsScreenModel.installExtension(extension)
                        else -> {
                            if (context.isPackageInstalled(extension.pkgName)) {
                                extensionsScreenModel.uninstallExtension(extension)
                            } else {
                                privateExtensionToUninstall = extension
                            }
                        }
                    }
                },
                onClickItemCancel = extensionsScreenModel::cancelInstallUpdateExtension,
                onClickUpdateAll = extensionsScreenModel::updateAllExtensions,
                onOpenWebView = { extension ->
                    extension.sources.getOrNull(0)?.let {
                        navigator.push(
                            WebViewScreen(
                                url = it.baseUrl,
                                initialTitle = it.name,
                                sourceId = it.id,
                            ),
                        )
                    }
                },
                onInstallExtension = extensionsScreenModel::installExtension,
                onOpenExtension = { navigator.push(ExtensionDetailsScreen(it.pkgName)) },
                onTrustExtension = { extensionsScreenModel.trustExtension(it) },
                onUninstallExtension = { extensionsScreenModel.uninstallExtension(it) },
                onUpdateExtension = extensionsScreenModel::updateExtension,
                onRefresh = extensionsScreenModel::findAvailableExtensions,
                suggestionController = extensionsScreenModel.suggestionPanel,
                onInstallSuggestion = extensionsScreenModel::installSuggestion,
                onRequestBatchStart = { extensionsScreenModel.requestSuggestionBatch() },
                onRequestBatchResume = {
                    extensionsScreenModel.requestSuggestionBatch(ExtensionsScreenModel.BatchReviewMode.RESUME)
                },

                onRequestBatchRetry = {
                    extensionsScreenModel.requestSuggestionBatch(ExtensionsScreenModel.BatchReviewMode.RETRY)
                },

                onStopBatch = { extensionsScreenModel.suggestionBatch?.stop() },
                batchPauseExplanation = pauseExplanation,
                onResolveBatchPause = systemPause?.takeIf {
                    it == SuggestionBatchPause.SERVICE ||
                        it == SuggestionBatchPause.PERMISSION ||
                        it == SuggestionBatchPause.INSTALLER_CHANGED
                }?.let { reason ->
                    {
                        val intent = batchPauseSettingsIntent(context, reason, state.installer)
                        if (intent == null) {
                            navigator.push(eu.kanade.presentation.more.settings.screen.SettingsAdvancedScreen)
                        } else {
                            context.startActivity(intent)
                        }
                    }
                },
                onSuggestionWebsite = { source -> suggestionWebsiteDestination(source)?.let(navigator::push) },
                onSuggestionMigration = { navigator.push(suggestionMigrationDestination(it)) },
                onSuggestionDiagnose = {
                    scope.launch {
                        extensionsScreenModel.recheckInstalledInventory().join()
                        snackbarHostState.showSnackbar(checkedMessage)
                    }
                },
            )

            state.batchConfirmation?.let { confirmation ->
                eu.kanade.presentation.browse.ExtensionSuggestionBatchDialog(
                    confirmation,
                    extensionsScreenModel::confirmSuggestionBatch,
                    extensionsScreenModel::selectBatchReplacement,
                    extensionsScreenModel::dismissBatchReview,
                )
            }

            state.originConfirmations.firstOrNull()?.let { request ->
                AlertDialog(
                    onDismissRequest = { extensionsScreenModel.answerOriginConfirmation(request.id, false) },
                    title = { Text(stringResource(MR.strings.ext_confirm_origin_title)) },
                    text = {
                        Text(
                            stringResource(
                                MR.strings.ext_confirm_origin_message,
                                request.artifact.name,
                                request.artifact.versionName,
                                request.artifact.repository.name,
                                request.artifact.repository.baseUrl,
                                request.artifact.repository.signingKeyFingerprint,
                            ),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { extensionsScreenModel.answerOriginConfirmation(request.id, true) }) {
                            Text(stringResource(MR.strings.ext_trust))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { extensionsScreenModel.answerOriginConfirmation(request.id, false) }) {
                            Text(stringResource(MR.strings.action_cancel))
                        }
                    },
                )
            }

            privateExtensionToUninstall?.let { extension ->
                ExtensionUninstallConfirmation(
                    extensionName = extension.name,
                    onClickConfirm = {
                        extensionsScreenModel.uninstallExtension(extension)
                    },
                    onDismissRequest = {
                        privateExtensionToUninstall = null
                    },
                )
            }
        },
    )
}

@Composable
private fun ExtensionUninstallConfirmation(
    extensionName: String,
    onClickConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        title = {
            Text(text = stringResource(MR.strings.ext_confirm_remove))
        },
        text = {
            Text(text = stringResource(MR.strings.remove_private_extension_message, extensionName))
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onClickConfirm()
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.ext_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        onDismissRequest = onDismissRequest,
    )
}

internal fun suggestionWebsiteDestination(source: ExtensionSourceDescriptor): WebViewScreen? =
    if (mihon.domain.extension.suggestion.isValidSuggestionWebsite(source.baseUrl)) {
        // Suggested sources are not loaded; let the existing WebView use its ordinary browser headers.
        WebViewScreen(url = source.baseUrl, initialTitle = source.name)
    } else {
        null
    }

internal fun suggestionMigrationDestination(sourceId: Long) =
    eu.kanade.tachiyomi.ui.browse.migration.manga.MigrateMangaScreen(sourceId)

internal fun batchPauseSettingsIntent(
    context: android.content.Context,
    reason: SuggestionBatchPause,
    installer: eu.kanade.domain.base.BasePreferences.ExtensionInstaller?,
): android.content.Intent? = when {
    reason == SuggestionBatchPause.SERVICE ||
        (
            reason == SuggestionBatchPause.PERMISSION &&
                installer == eu.kanade.domain.base.BasePreferences.ExtensionInstaller.SHIZUKU
            ) -> {
        context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            ?: android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:moe.shizuku.privileged.api"),
            )
    }
    reason == SuggestionBatchPause.PERMISSION ->
        android.content.Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:${context.packageName}"),
        )
    else -> null
}
