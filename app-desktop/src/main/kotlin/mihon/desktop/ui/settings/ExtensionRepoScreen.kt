package mihon.desktop.ui.settings

import mihon.desktop.LocalDesktopUiDependencies

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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
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
import java.awt.Desktop
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds

private sealed interface RepoDialog {
    data object Create : RepoDialog
    data class Delete(val baseUrl: String) : RepoDialog
    data class Conflict(val oldRepo: ExtensionRepo, val newRepo: ExtensionRepo) : RepoDialog
}

/** 扩展仓库管理页面：添加/删除/刷新仓库。 */
class ExtensionRepoScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        val clipboardManager = LocalClipboardManager.current

        val getExtensionRepo = LocalDesktopUiDependencies.current.getExtensionRepo
        val createExtensionRepo = LocalDesktopUiDependencies.current.createExtensionRepo
        val deleteExtensionRepo = LocalDesktopUiDependencies.current.deleteExtensionRepo
        val replaceExtensionRepo = LocalDesktopUiDependencies.current.replaceExtensionRepo
        val updateExtensionRepo = LocalDesktopUiDependencies.current.updateExtensionRepo

        val repos by getExtensionRepo.subscribeAll().collectAsState(initial = emptyList())
        var dialog by remember { mutableStateOf<RepoDialog?>(null) }
        var pendingRepoUrl by remember { mutableStateOf<String?>(null) }

        fun showSnackbar(message: String) {
            scope.launch { snackbarHostState.showSnackbar(message) }
        }

        // Dialogs
        when (val d = dialog) {
            is RepoDialog.Create -> {
                CreateRepoDialog(
                    existingUrls = repos.map { it.baseUrl }.toSet(),
                    onDismiss = { dialog = null },
                    onCreate = { url ->
                        dialog = null
                        pendingRepoUrl = url
                        scope.launch {
                            when (val result = createExtensionRepo.await(url)) {
                                CreateExtensionRepo.Result.Success -> Unit
                                CreateExtensionRepo.Result.RepoAlreadyExists ->
                                    extensionRepoCreateMessage(result)?.let(::showSnackbar)
                                is CreateExtensionRepo.Result.DuplicateFingerprint ->
                                    dialog = RepoDialog.Conflict(result.oldRepo, result.newRepo)
                                else -> extensionRepoCreateMessage(result)?.let(::showSnackbar)
                            }
                            pendingRepoUrl = null
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
                        scope.launch { deleteExtensionRepo.await(d.baseUrl) }
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
                        scope.launch { replaceExtensionRepo.await(d.newRepo) }
                    },
                )
            }
            null -> Unit
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Extension Repositories") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            scope.launch { updateExtensionRepo.awaitAll() }
                        }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh all repos")
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { dialog = RepoDialog.Create }) {
                    Icon(Icons.Outlined.Add, contentDescription = "Add repository")
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            val pendingUrl = pendingRepoUrl
            if (repos.isEmpty() && pendingUrl == null) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No extension repositories added.\nTap + to add a repository.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pendingUrl?.let { url ->
                        item(key = "pending-repo-$url") {
                            PendingRepoCard(url = url)
                        }
                    }
                    items(repos, key = { it.baseUrl }) { repo ->
                        RepoCard(
                            repo = repo,
                            onOpenWebsite = {
                                runCatching {
                                    Desktop.getDesktop().browse(URI(repo.website))
                                }
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

internal fun extensionRepoPendingTitle(url: String): String = "Checking repository..."

internal fun extensionRepoCreateMessage(result: CreateExtensionRepo.Result): String? {
    return when (result) {
        CreateExtensionRepo.Result.InvalidUrl -> "Repository URL must be HTTPS."
        CreateExtensionRepo.Result.RepositoryUnavailable -> "Could not reach repository. Check the URL or network connection."
        CreateExtensionRepo.Result.InvalidRepository -> "Repository metadata is missing or invalid."
        CreateExtensionRepo.Result.RepoAlreadyExists -> "Repository already exists."
        CreateExtensionRepo.Result.Error -> "Failed to add repository."
        CreateExtensionRepo.Result.Success,
        is CreateExtensionRepo.Result.DuplicateFingerprint,
        -> null
    }
}

@Composable
private fun PendingRepoCard(
    url: String,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator()
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(text = extensionRepoPendingTitle(url), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
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
            IconButton(onClick = onOpenWebsite) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open website")
            }
            IconButton(onClick = onCopyUrl) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy URL")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun CreateRepoDialog(
    existingUrls: Set<String>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val alreadyExists = url.isNotEmpty() && existingUrls.contains(url)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Extension Repository") },
        text = {
            Column {
                Text(
                    text = "Enter the repository base URL or index.min.json URL.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .focusRequester(focusRequester),
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Repository URL") },
                    placeholder = { Text("https://example.com/repo") },
                    supportingText = {
                        if (alreadyExists) Text("Repository already exists")
                        else Text("Required")
                    },
                    isError = alreadyExists,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotEmpty() && !alreadyExists,
                onClick = { onCreate(url) },
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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
        title = { Text("Remove Repository") },
        text = { Text("Remove \"$baseUrl\"? Extensions from this repository will no longer be available.") },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ConflictRepoDialog(
    oldRepo: ExtensionRepo,
    newRepo: ExtensionRepo,
    onDismiss: () -> Unit,
    onReplace: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Repository Conflict") },
        text = {
            Text(
                "\"${newRepo.name}\" has the same signing key as \"${oldRepo.name}\". " +
                    "Replace the existing repository?",
            )
        },
        confirmButton = {
            TextButton(onClick = onReplace) { Text("Replace") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
