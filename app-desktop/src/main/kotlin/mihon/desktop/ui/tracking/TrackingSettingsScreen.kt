package mihon.desktop.ui.tracking

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.platform.DesktopOAuthCallbackServer
import mihon.desktop.tracking.DesktopAuthenticatingTrackerService
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackSearchResult
import tachiyomi.domain.track.service.TrackerAuthentication
import tachiyomi.domain.track.service.TrackerService
import java.awt.Desktop
import java.net.URI
import java.time.Duration
import java.util.UUID

/** Tracker settings when [mangaId] is null; manga binding and editing when it is present. */
data class TrackingSettingsScreen(
    val mangaId: Long? = null,
    val mangaTitle: String? = null,
    val totalChapters: Long? = null,
) : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val dependencies = LocalDesktopUiDependencies.current
        val model = rememberScreenModel {
            TrackingScreenModel(mangaId, mangaTitle, totalChapters, dependencies.trackRepository, dependencies.trackerServiceRegistry)
        }
        val state by model.state.collectAsState()
        var selectedId by remember { mutableStateOf<Long?>(null) }
        var confirmation by remember { mutableStateOf<TrackingConfirmation?>(null) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(model) { model.load() }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (mangaId == null) "Tracking services" else "Manga tracking") },
                    navigationIcon = {
                        IconButton(onClick = navigator::pop) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.services.isEmpty() && state.error == null -> Text(
                        "No tracking services are available for this build.",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        state.error?.let { message ->
                            item { Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                        }
                        state.feedback?.let { message -> item { Text(message, Modifier.padding(16.dp)) } }
                        items(state.services, key = { it.profile.id }) { item ->
                            val profile = item.profile
                            val sourceManaged = dependencies.trackerServiceRegistry.get(profile.id) !is DesktopAuthenticatingTrackerService
                            ListItem(
                                headlineContent = { Text(profile.name) },
                                supportingContent = {
                                    Text(
                                        profile.unavailableReason
                                            ?: when {
                                                item.track != null -> "Bound to ${item.track.title}"
                                                sourceManaged && profile.loggedIn -> "Available through configured source"
                                                profile.loggedIn -> "Logged in${profile.username?.let { " as $it" }.orEmpty()} · Not bound"
                                                else -> "Not logged in"
                                            },
                                    )
                                },
                                trailingContent = {
                                    TextButton(
                                        enabled = profile.unavailableReason == null && (!sourceManaged || mangaId != null),
                                        onClick = {
                                            if (profile.loggedIn && mangaId == null) {
                                                confirmation = TrackingConfirmation.Logout(profile.id, profile.name)
                                            } else {
                                                selectedId = profile.id
                                            }
                                        },
                                    ) {
                                        Text(
                                            when {
                                                sourceManaged && mangaId == null -> "Source managed"
                                                profile.loggedIn && mangaId == null -> "Logout"
                                                profile.loggedIn -> "Manage"
                                                else -> "Login"
                                            },
                                        )
                                    }
                                },
                                modifier = if (profile.unavailableReason == null) {
                                    Modifier.clickable { if (!sourceManaged || mangaId != null) selectedId = profile.id }
                                } else {
                                    Modifier
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

        selectedId?.let { trackerId ->
            val service = dependencies.trackerServiceRegistry.services.firstOrNull { it.profile.value.id == trackerId }
                ?: return@let
            val profile by service.profile.collectAsState()
            when {
                !profile.loggedIn -> LoginDialog(
                    service = service,
                    onDismiss = {
                        selectedId = null
                    },
                    onRun = { operation ->
                        scope.launch {
                            try {
                                operation()
                                model.load()
                            } catch (_: CancellationException) {
                                model.reportError(IllegalStateException("Login cancelled"), "Login cancelled")
                            } catch (error: Throwable) {
                                model.reportError(error, "Login failed")
                            }
                            selectedId = null
                        }
                    },
                )
                mangaId != null -> MangaTrackingDialog(
                    item = state.services.first { it.profile.id == trackerId },
                    model = model,
                    onDismiss = { selectedId = null },
                    onRequestUnbind = { confirmation = TrackingConfirmation.Unbind(trackerId, profile.name) },
                )
                else -> selectedId = null
            }
        }

        confirmation?.let { request ->
            AlertDialog(
                onDismissRequest = { confirmation = null },
                title = { Text(request.title) },
                text = { Text(request.message) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmation = null
                        scope.launch {
                            runCatching {
                                when (request) {
                                    is TrackingConfirmation.Logout -> model.logout(request.trackerId)
                                    is TrackingConfirmation.Unbind -> model.unbind(request.trackerId)
                                }
                            }.onFailure { model.reportError(it, request.failureMessage) }
                            selectedId = null
                        }
                    }) { Text("Confirm") }
                },
                dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } },
            )
        }
    }
}

fun trackingSettingsDestination() = TrackingSettingsScreen()

fun mangaTrackingDestination(mangaId: Long, mangaTitle: String, totalChapters: Long?) =
    TrackingSettingsScreen(mangaId, mangaTitle, totalChapters)

fun pushTrackingSettings(navigator: Navigator) {
    navigator.push(trackingSettingsDestination())
}

fun pushMangaTracking(navigator: Navigator, mangaId: Long, mangaTitle: String, totalChapters: Long?) {
    navigator.push(mangaTrackingDestination(mangaId, mangaTitle, totalChapters))
}

private sealed interface TrackingConfirmation {
    val trackerId: Long
    val title: String
    val message: String
    val failureMessage: String

    data class Logout(override val trackerId: Long, val serviceName: String) : TrackingConfirmation {
        override val title = "Log out of $serviceName?"
        override val message = "The saved account session will be removed. Existing manga bindings remain local."
        override val failureMessage = "Logout failed"
    }

    data class Unbind(override val trackerId: Long, val serviceName: String) : TrackingConfirmation {
        override val title = "Remove $serviceName tracking?"
        override val message = "This removes the local binding. It does not delete the remote list entry."
        override val failureMessage = "Unable to remove tracking"
    }
}

@Composable
private fun LoginDialog(
    service: TrackerService,
    onDismiss: () -> Unit,
    onRun: (suspend () -> Unit) -> Unit,
) {
    val authenticating = service as? DesktopAuthenticatingTrackerService
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val method = service.profile.value.authentication
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Login to ${service.profile.value.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (authenticating == null) Text("This service is unavailable on this platform.")
                if (method == TrackerAuthentication.USERNAME_PASSWORD) {
                    OutlinedTextField(username, { username = it }, label = { Text("Username") })
                    OutlinedTextField(password, { password = it }, label = { Text("Password") })
                }
                if (method == TrackerAuthentication.API_KEY) {
                    OutlinedTextField(password, { password = it }, label = { Text("API key") })
                }
                if (method == TrackerAuthentication.OAUTH) Text("Your browser will open for authorization.")
            }
        },
        confirmButton = {
            Button(
                enabled = authenticating != null,
                onClick = {
                    onRun {
                        when (method) {
                            TrackerAuthentication.USERNAME_PASSWORD -> authenticating!!.login(username, password)
                            TrackerAuthentication.API_KEY -> authenticating!!.loginWithApiKey(password)
                            TrackerAuthentication.OAUTH -> oauthLogin(authenticating!!)
                        }
                    }
                },
            ) { Text("Login") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private suspend fun oauthLogin(service: DesktopAuthenticatingTrackerService) {
    DesktopOAuthCallbackServer().use { callback ->
        val state = UUID.randomUUID().toString()
        val session = callback.start(state, Duration.ofMinutes(2))
        Desktop.getDesktop().browse(URI(service.authorizationUrl(session.redirectUri, state)))
        service.finishOAuth(session.awaitCode(), session.redirectUri)
    }
}

@Composable
private fun MangaTrackingDialog(
    item: TrackingServiceState,
    model: TrackingScreenModel,
    onDismiss: () -> Unit,
    onRequestUnbind: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<TrackSearchResult>?>(null) }
    val bound = item.track
    var status by remember(bound) { mutableStateOf(bound?.status) }
    var score by remember(bound) { mutableStateOf(bound?.score) }
    var chapter by remember(bound) { mutableStateOf(bound?.lastChapterRead ?: 0.0) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Track ${model.mangaTitle ?: "manga"} with ${item.profile.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (bound == null) {
                    OutlinedTextField(query, { query = it }, label = { Text("Search title") })
                    Button(enabled = !working, onClick = {
                        scope.launch {
                            working = true
                            runCatching { model.search(item.profile.id, query) }
                                .onSuccess { results = it; error = null }
                                .onFailure { error = it.message ?: "Search failed" }
                            working = false
                        }
                    }) { Text("Search") }
                    when {
                        working -> CircularProgressIndicator()
                        results == null -> Unit
                        results!!.isEmpty() -> Text("No results")
                        else -> results!!.forEach { result ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(result.title, Modifier.weight(1f))
                                TextButton(onClick = {
                                    scope.launch {
                                        working = true
                                        runCatching { model.bind(item.profile.id, result) }
                                            .onSuccess { onDismiss() }
                                            .onFailure { error = it.message ?: "Binding failed" }
                                        working = false
                                    }
                                }) { Text("Bind") }
                            }
                        }
                    }
                } else {
                    ChoiceField("Status", item.statuses, status) { status = it }
                    if (item.scores.isNotEmpty()) ChoiceField("Score", item.scores.map { it to it.toString() }, score) { score = it }
                    ChapterStepper(chapter, model.totalChapters?.takeIf { it > 0 } ?: bound.totalChapters.takeIf { it > 0 }) { chapter = it }
                    Button(enabled = !working, onClick = {
                        scope.launch {
                            working = true
                            runCatching {
                                model.update(item.profile.id, TrackEdit(status, score, chapter))
                            }.onSuccess { onDismiss() }
                                .onFailure { error = it.message ?: "Update failed" }
                            working = false
                        }
                    }) { Text("Update") }
                    TextButton(onClick = onRequestUnbind) { Text("Remove tracking") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun <T> ChoiceField(label: String, choices: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("$label: ${choices.firstOrNull { it.first == selected }?.second ?: "Choose"}")
        }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { (value, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}

@Composable
private fun ChapterStepper(value: Double, maximum: Long?, onChange: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Chapter")
        TextButton(onClick = { onChange((value - 1.0).coerceAtLeast(0.0)) }, enabled = value > 0) { Text("−") }
        Text(if (maximum == null) value.toString() else "$value / $maximum")
        TextButton(
            onClick = { onChange(value + 1.0) },
            enabled = maximum == null || value < maximum,
        ) { Text("+") }
    }
}
