package mihon.desktop.ui.settings

import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.platform.DesktopUrlOpener

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.launch
import mihon.domain.extensionrepo.interactor.CreateExtensionRepo
import mihon.domain.extensionrepo.interactor.DeleteExtensionRepo
import mihon.domain.extensionrepo.interactor.GetExtensionRepo
import mihon.domain.extensionrepo.interactor.ReplaceExtensionRepo
import mihon.domain.extensionrepo.interactor.UpdateExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.service.ExtensionRepoAction
import mihon.domain.extensionrepo.service.ExtensionRepoActionResult
import mihon.domain.extensionrepo.service.ExtensionRepoCreateOutcome
import mihon.domain.extensionrepo.service.ExtensionRepoFailure
import mihon.domain.extensionrepo.service.ExtensionRepoService
import mihon.domain.extensionrepo.service.ExtensionRepoValidation
import tachiyomi.i18n.MR
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

internal sealed interface RepoDialog {
    data class Create(val initialUrl: String = "") : RepoDialog
    data class Delete(val baseUrl: String) : RepoDialog
    data class Conflict(val oldRepo: ExtensionRepo, val newRepo: ExtensionRepo) : RepoDialog
}

internal fun interface ExtensionRepoUrlOpener {
    fun open(url: String)
}

internal val LocalExtensionRepoUrlOpener = staticCompositionLocalOf<ExtensionRepoUrlOpener> {
    ExtensionRepoUrlOpener { url -> DesktopUrlOpener.open(url).getOrThrow() }
}

/** 扩展仓库管理页面：添加/删除/刷新仓库。 */
internal class DesktopExtensionRepoActions(
    private val create: suspend (String) -> ExtensionRepoCreateOutcome,
    private val replace: suspend (ExtensionRepo) -> Unit,
    private val delete: suspend (String) -> Unit,
) {
    suspend fun create(url: String, publish: (ExtensionRepoActionResult) -> Unit) =
        ExtensionRepoService.execute(ExtensionRepoAction.CREATE, publish) {
            ExtensionRepoService.create(url, create)
        }

    suspend fun replace(
        oldRepo: ExtensionRepo,
        newRepo: ExtensionRepo,
        publish: (ExtensionRepoActionResult) -> Unit,
    ) = ExtensionRepoService.execute(ExtensionRepoAction.REPLACE, publish) {
        ExtensionRepoService.replace(oldRepo, newRepo, replace)
    }

    suspend fun delete(url: String, publish: (ExtensionRepoActionResult) -> Unit) =
        ExtensionRepoService.execute(ExtensionRepoAction.DELETE, publish) {
            ExtensionRepoService.delete(url, delete)
        }
}

data class ExtensionRepoScreen(val initialUrl: String? = null) : Screen {
    internal fun initialCreatePrompt(): RepoDialog.Create? = initialUrl?.let(RepoDialog::Create)
    internal fun freshCreatePrompt() = RepoDialog.Create()

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        val clipboardManager = LocalClipboardManager.current
        val urlOpener = LocalExtensionRepoUrlOpener.current

        val getExtensionRepo = LocalDesktopUiDependencies.current.getExtensionRepo
        val createExtensionRepo = LocalDesktopUiDependencies.current.createExtensionRepo
        val deleteExtensionRepo = LocalDesktopUiDependencies.current.deleteExtensionRepo
        val replaceExtensionRepo = LocalDesktopUiDependencies.current.replaceExtensionRepo
        val updateExtensionRepo = LocalDesktopUiDependencies.current.updateExtensionRepo
        val actions = remember(createExtensionRepo, deleteExtensionRepo, replaceExtensionRepo) {
            DesktopExtensionRepoActions(
                create = { createExtensionRepo.await(it).toDesktopOutcome() },
                replace = replaceExtensionRepo::await,
                delete = deleteExtensionRepo::await,
            )
        }

        val repos by getExtensionRepo.subscribeAll().collectAsState(initial = emptyList())
        var dialog by remember { mutableStateOf<RepoDialog?>(initialCreatePrompt()) }
        var actionResult by remember { mutableStateOf<ExtensionRepoActionResult?>(null) }
        var actionDetail by remember { mutableStateOf<String?>(null) }
        val addRepoTitle = DesktopSettingsAnchorResources.extensionRepoAdd.localized()
        val deleteRepoTitle = DesktopSettingsAnchorResources.extensionRepoDelete.localized()
        val anchors = buildList {
            add(DesktopSettingsLazyAnchor(addRepoTitle, "add-repo"))
            repos.forEachIndexed { index, repo ->
                add(
                    DesktopSettingsLazyAnchor(
                        deleteRepoTitle,
                        "delete-${repo.baseUrl}",
                        index + if (actionResult == null) 0 else 1,
                    ),
                )
            }
        }
        val anchorHost = rememberDesktopSettingsAnchorLazyListHost(this, anchors)

        fun showSnackbar(message: String) {
            scope.launch { snackbarHostState.showSnackbar(message) }
        }
        val refreshRepos: () -> Unit = { scope.launch { updateExtensionRepo.awaitAll() } }
        val showCreate: () -> Unit = { dialog = freshCreatePrompt() }

        // Dialogs
        when (val d = dialog) {
            is RepoDialog.Create -> {
                CreateRepoDialog(
                    initialUrl = d.initialUrl,
                    existingUrls = repos.map { it.baseUrl }.toSet(),
                    onDismiss = { dialog = null },
                    onCreate = { url ->
                        dialog = null
                        actionDetail = url
                        scope.launch {
                            when (val result = actions.create(url) { actionResult = it }) {
                                is ExtensionRepoActionResult.FingerprintConflict ->
                                    dialog = RepoDialog.Conflict(result.oldRepo, result.newRepo)
                                else -> extensionRepoActionMessage(result, Locale.getDefault()).let(::showSnackbar)
                            }
                        }
                    },
                )
            }
            is RepoDialog.Delete -> {
                DeleteRepoDialog(
                    baseUrl = d.baseUrl,
                    onDismiss = { dialog = null },
                    onDelete = {
                        dialog = null
                        actionDetail = d.baseUrl
                        scope.launch {
                            val result = actions.delete(d.baseUrl) { actionResult = it }
                            showSnackbar(extensionRepoActionMessage(result, Locale.getDefault()))
                        }
                    },
                )
            }
            is RepoDialog.Conflict -> {
                ConflictRepoDialog(
                    oldRepo = d.oldRepo,
                    newRepo = d.newRepo,
                    onDismiss = { dialog = null },
                    onReplace = {
                        dialog = null
                        actionDetail = d.newRepo.baseUrl
                        scope.launch {
                            val result = actions.replace(d.oldRepo, d.newRepo) { actionResult = it }
                            showSnackbar(extensionRepoActionMessage(result, Locale.getDefault()))
                        }
                    },
                )
            }
            null -> Unit
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(MR.strings.label_extension_repos.localized()) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = MR.strings.action_bar_up_description.localized())
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = refreshRepos,
                            modifier = Modifier.desktopSettingsActivationKeys(Role.Button, onClick = refreshRepos),
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = MR.strings.action_webview_refresh.localized())
                        }
                    },
                )
            },
            floatingActionButton = {
                ExtensionRepoAddButton(
                    onClick = showCreate,
                    modifier = Modifier
                        .desktopSettingsAnchor(addRepoTitle, "add-repo", anchorHost),
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            val result = actionResult
            val showEmpty = repos.isEmpty() &&
                (result == null || result is ExtensionRepoActionResult.Success && result.action == ExtensionRepoAction.DELETE)
            if (showEmpty) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = MR.strings.information_empty_repos.localized(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = MR.strings.desktop_extension_repo_empty_hint.localized(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = anchorHost.listState,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    result?.let {
                        item(key = "repo-action-${it.action}") {
                            RepoActionCard(result = it, detail = actionDetail)
                        }
                    }
                    items(repos, key = { it.baseUrl }) { repo ->
                        RepoCard(
                            repo = repo,
                            modifier = Modifier.desktopSettingsAnchor(
                                deleteRepoTitle,
                                "delete-${repo.baseUrl}",
                                anchorHost,
                            ),
                            onOpenWebsite = {
                                runCatching { urlOpener.open(repo.website) }
                            },
                            onCopyUrl = {
                                clipboardManager.setText(AnnotatedString("${repo.baseUrl}/index.min.json"))
                            },
                            onDelete = { dialog = RepoDialog.Delete(repo.baseUrl) },
                        )
                    }
                }
            }
        }
    }
}

internal fun extensionRepoPendingTitle(@Suppress("UNUSED_PARAMETER") url: String, locale: Locale = Locale.US): String =
    MR.strings.desktop_extension_repo_pending.localized(locale)

internal fun extensionRepoActionMessage(result: ExtensionRepoActionResult, locale: Locale = Locale.US): String {
    return when (result) {
        is ExtensionRepoActionResult.Pending -> MR.strings.desktop_extension_repo_pending.localized(locale)
        is ExtensionRepoActionResult.Success -> MR.strings.completed.localized(locale)
        is ExtensionRepoActionResult.FingerprintConflict -> MR.strings.action_replace_repo_title.localized(locale)
        is ExtensionRepoActionResult.Validation -> when (result.reason) {
            ExtensionRepoValidation.INVALID_URL -> MR.strings.desktop_extension_repo_https_required.localized(locale)
            ExtensionRepoValidation.ALREADY_EXISTS -> MR.strings.error_repo_exists.localized(locale)
            ExtensionRepoValidation.FINGERPRINT_CHANGED -> MR.strings.action_replace_repo_title.localized(locale)
        }
        is ExtensionRepoActionResult.Failure -> when (result.reason) {
            ExtensionRepoFailure.REPOSITORY_UNAVAILABLE -> MR.strings.desktop_extension_repo_unavailable.localized(locale)
            ExtensionRepoFailure.INVALID_REPOSITORY -> MR.strings.desktop_extension_repo_invalid_metadata.localized(locale)
            ExtensionRepoFailure.UNKNOWN -> if (result.action == ExtensionRepoAction.CREATE) {
                MR.strings.desktop_extension_repo_add_failed.localized(locale)
            } else {
                MR.strings.unknown_error.localized(locale)
            }
        }
    }
}

private fun CreateExtensionRepo.Result.toDesktopOutcome() = when (this) {
    CreateExtensionRepo.Result.Success -> ExtensionRepoCreateOutcome.Success
    CreateExtensionRepo.Result.InvalidUrl -> ExtensionRepoCreateOutcome.InvalidUrl
    CreateExtensionRepo.Result.RepoAlreadyExists -> ExtensionRepoCreateOutcome.AlreadyExists
    is CreateExtensionRepo.Result.DuplicateFingerprint -> ExtensionRepoCreateOutcome.Conflict(oldRepo, newRepo)
    CreateExtensionRepo.Result.RepositoryUnavailable -> ExtensionRepoCreateOutcome.RepositoryUnavailable
    CreateExtensionRepo.Result.InvalidRepository -> ExtensionRepoCreateOutcome.InvalidRepository
    CreateExtensionRepo.Result.Error -> ExtensionRepoCreateOutcome.Failure
}

@Composable
internal fun ExtensionRepoAddButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.desktopSettingsActivationKeys(Role.Button, onClick = onClick),
    ) {
        Icon(Icons.Outlined.Add, contentDescription = MR.strings.action_add_repo.localized())
    }
}

@Composable
private fun RepoActionCard(
    result: ExtensionRepoActionResult,
    detail: String?,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (result is ExtensionRepoActionResult.Pending) CircularProgressIndicator()
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(text = extensionRepoActionMessage(result, Locale.getDefault()), style = MaterialTheme.typography.titleSmall)
                detail?.let {
                    Text(text = it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun RepoCard(
    repo: ExtensionRepo,
    onOpenWebsite: () -> Unit,
    onCopyUrl: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Label,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(text = repo.name, style = MaterialTheme.typography.titleSmall)
                repo.shortName?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = repo.baseUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = onOpenWebsite, modifier = Modifier.desktopSettingsActivationKeys(Role.Button, onClick = onOpenWebsite)) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = MR.strings.action_open_in_browser.localized())
            }
            IconButton(onClick = onCopyUrl, modifier = Modifier.desktopSettingsActivationKeys(Role.Button, onClick = onCopyUrl)) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = MR.strings.action_copy_link.localized())
            }
            IconButton(onClick = onDelete, modifier = Modifier.desktopSettingsActivationKeys(Role.Button, onClick = onDelete)) {
                Icon(Icons.Outlined.Delete, contentDescription = MR.strings.action_delete_repo.localized(), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
internal fun CreateRepoDialog(
    initialUrl: String,
    existingUrls: Set<String>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    val focusRequester = remember { FocusRequester() }
    val alreadyExists = url.isNotEmpty() && existingUrls.contains(url)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(MR.strings.action_add_repo.localized()) },
        text = {
            Column {
                Text(
                    text = MR.strings.desktop_extension_repo_add_message.localized(),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .focusRequester(focusRequester),
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(MR.strings.label_add_repo_input.localized()) },
                    placeholder = { Text("https://example.com/repo") },
                    supportingText = {
                        if (alreadyExists) Text(MR.strings.error_repo_exists.localized())
                        else Text(MR.strings.information_required_plain.localized())
                    },
                    isError = alreadyExists,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            DesktopSettingsTextButton(
                enabled = url.isNotEmpty() && !alreadyExists,
                onClick = { onCreate(url) },
            ) { Text(MR.strings.action_add.localized()) }
        },
        dismissButton = {
            DesktopSettingsTextButton(onClick = onDismiss) { Text(MR.strings.action_cancel.localized()) }
        },
    )

    LaunchedEffect(focusRequester) {
        kotlinx.coroutines.delay(100.milliseconds)
        focusRequester.requestFocus()
    }
}

@Composable
private fun DeleteRepoDialog(
    baseUrl: String,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(MR.strings.action_delete_repo.localized()) },
        text = {
            Column {
                Text(MR.strings.delete_repo_confirmation.localized(Locale.getDefault(), baseUrl))
                Text(MR.strings.desktop_extension_repo_delete_consequence.localized())
            }
        },
        confirmButton = {
            DesktopSettingsTextButton(onClick = onDelete) {
                Text(MR.strings.action_remove.localized(), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            DesktopSettingsTextButton(onClick = onDismiss) { Text(MR.strings.action_cancel.localized()) }
        },
    )
}

@Composable
internal fun ConflictRepoDialog(
    oldRepo: ExtensionRepo,
    newRepo: ExtensionRepo,
    onDismiss: () -> Unit,
    onReplace: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(MR.strings.action_replace_repo_title.localized()) },
        text = {
            Text(MR.strings.action_replace_repo_message.localized(Locale.getDefault(), newRepo.name, oldRepo.name))
        },
        confirmButton = {
            DesktopSettingsTextButton(onClick = onReplace) { Text(MR.strings.action_replace_repo.localized()) }
        },
        dismissButton = {
            DesktopSettingsTextButton(onClick = onDismiss) { Text(MR.strings.action_cancel.localized()) }
        },
    )
}
