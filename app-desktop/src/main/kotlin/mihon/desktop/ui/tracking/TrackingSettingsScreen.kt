package mihon.desktop.ui.tracking

import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.platform.DesktopUrlOpener
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.tracking.DesktopAuthenticatingTrackerService
import mihon.desktop.tracking.DesktopTrackerOAuthCallbackBroker
import mihon.desktop.ui.security.DesktopPasswordField
import mihon.desktop.ui.settings.DesktopSettingsAnchorResources
import mihon.desktop.ui.settings.DesktopSettingsButton
import mihon.desktop.ui.settings.DesktopSettingsLazyAnchor
import mihon.desktop.ui.settings.DesktopSettingsTextButton
import mihon.desktop.ui.settings.SwitchSettingsItem
import mihon.desktop.ui.settings.desktopSettingsActivationKeys
import mihon.desktop.ui.settings.desktopSettingsAction
import mihon.desktop.ui.settings.desktopSettingsAnchor
import mihon.desktop.ui.settings.rememberDesktopSettingsAnchorLazyListHost
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackSearchResult
import tachiyomi.domain.track.service.TrackerAuthentication
import tachiyomi.domain.track.service.TrackerProviderService
import tachiyomi.domain.track.service.TrackerService
import tachiyomi.i18n.MR
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

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
            TrackingScreenModel(
                mangaId = mangaId,
                mangaTitle = mangaTitle,
                totalChapters = totalChapters,
                repository = dependencies.trackRepository,
                getChaptersByMangaId = dependencies.getChaptersByMangaId,
                registry = dependencies.trackerServiceRegistry,
                getManga = dependencies.getManga,
            )
        }
        val state by model.state.collectAsState()
        var selectedId by remember { mutableStateOf<Long?>(null) }
        var confirmation by remember { mutableStateOf<TrackingConfirmation?>(null) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(model) { model.load() }
        val autoSyncTitle = DesktopSettingsAnchorResources.trackingAutoSync.localized()
        val loginTitle = DesktopSettingsAnchorResources.trackingLogin.localized()
        val serviceStartIndex = listOf(state.error, state.feedback).count { it != null }
        val anchors = buildList {
            if (mangaId == null) add(DesktopSettingsLazyAnchor(autoSyncTitle, "tracking-auto-sync"))
            state.services.forEachIndexed { index, item ->
                val sourceManaged =
                    dependencies.trackerServiceRegistry.get(item.profile.id) !is DesktopAuthenticatingTrackerService
                if (item.track == null && !item.profile.loggedIn && (!sourceManaged || mangaId != null)) {
                    add(
                        DesktopSettingsLazyAnchor(
                            loginTitle,
                            "tracking-login-${item.profile.id}",
                            serviceStartIndex + index,
                        ),
                    )
                }
            }
        }
        val anchorHost = rememberDesktopSettingsAnchorLazyListHost(this, anchors)

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (mangaId == null) {
                                MR.strings.pref_category_tracking.localized()
                            } else {
                                MR.strings.manga_tracking_tab.localized()
                            },
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = navigator::pop) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, MR.strings.action_bar_up_description.localized())
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (mangaId == null) {
                    TrackingAutoSyncPreference(
                        dependencies.appPreferences,
                        autoSyncTitle,
                        Modifier.desktopSettingsAnchor(autoSyncTitle, "tracking-auto-sync", anchorHost),
                    )
                    HorizontalDivider()
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        state.services.isEmpty() && state.error == null -> Text(
                            MR.strings.desktop_tracking_empty.localized(),
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> LazyColumn(Modifier.fillMaxSize(), state = anchorHost.listState) {
                            state.error?.let { message ->
                                item {
                                    Text(
                                        trackingMessageText(message),
                                        Modifier.padding(16.dp),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            state.feedback?.let { message -> item { Text(trackingMessageText(message), Modifier.padding(16.dp)) } }
                            items(state.services, key = { it.profile.id }) { item ->
                                val profile = item.profile
                                val sourceManaged =
                                    dependencies.trackerServiceRegistry.get(profile.id) !is DesktopAuthenticatingTrackerService
                                val boundManga = mangaId != null && item.track != null
                                val actionEnabled =
                                    boundManga ||
                                        (profile.unavailableReason == null && (!sourceManaged || mangaId != null))
                                val serviceAction = {
                                    if (profile.loggedIn && mangaId == null) {
                                        confirmation = TrackingConfirmation.Logout(profile.id, profile.name)
                                    } else {
                                        selectedId = profile.id
                                    }
                                }
                                val actionLabel = when {
                                    sourceManaged && mangaId == null -> MR.strings.desktop_tracking_source_managed.localized()
                                    profile.loggedIn && mangaId == null -> MR.strings.logout.localized()
                                    boundManga || profile.loggedIn -> MR.strings.desktop_tracking_manage.localized()
                                    else -> loginTitle
                                }
                                ListItem(
                                    headlineContent = { Text(profile.name) },
                                    supportingContent = {
                                        Text(
                                            profile.unavailableReason
                                                ?: when {
                                                    item.track != null -> MR.strings.desktop_tracking_bound_to.localized(
                                                        Locale.getDefault(),
                                                        item.track.title,
                                                    )
                                                    sourceManaged && profile.loggedIn -> MR.strings.desktop_tracking_source_available.localized()
                                                    profile.loggedIn ->
                                                        profile.username?.let {
                                                            MR.strings.desktop_tracking_logged_in_as_not_bound.localized(Locale.getDefault(), it)
                                                        } ?: MR.strings.desktop_tracking_logged_in_not_bound.localized()
                                                    else -> MR.strings.desktop_tracking_not_logged_in.localized()
                                                },
                                        )
                                    },
                                    trailingContent = {
                                        Text(actionLabel)
                                    },
                                    modifier = (if (item.track == null && !profile.loggedIn && (!sourceManaged || mangaId != null)) {
                                        Modifier.desktopSettingsAnchor(
                                            loginTitle,
                                            "tracking-login-${profile.id}",
                                            anchorHost,
                                        )
                                    } else {
                                        Modifier
                                    }).then(if (actionEnabled) {
                                        Modifier.desktopSettingsAction(Role.Button, serviceAction)
                                    } else {
                                        Modifier.semantics { disabled() }
                                    }),
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }

        selectedId?.let { trackerId ->
            val service = dependencies.trackerServiceRegistry.services.firstOrNull { it.profile.value.id == trackerId }
                ?: return@let
            val profile by service.profile.collectAsState()
            val selectedItem = state.services.first { it.profile.id == trackerId }
            when {
                mangaId != null && selectedItem.track != null -> MangaTrackingDialog(
                    item = selectedItem,
                    model = model,
                    onDismiss = { selectedId = null },
                    onRequestUnbind = {
                        confirmation = TrackingConfirmation.Unbind(
                            trackerId,
                            profile.name,
                            (service as? TrackerProviderService)?.configuration?.supportsDelete == true,
                        )
                    },
                )
                !profile.loggedIn -> LoginDialog(
                    service = service,
                    oauthCallbackBroker = dependencies.trackerOAuthCallbackBroker,
                    onDismiss = {
                        selectedId = null
                    },
                    onRun = { operation ->
                        scope.launch {
                            try {
                                operation()
                                model.load()
                            } catch (_: CancellationException) {
                                model.reportError(CancellationException(), TrackingMessage.LoginCancelled)
                            } catch (error: Throwable) {
                                model.reportError(error, TrackingMessage.LoginFailed)
                            }
                            selectedId = null
                        }
                    },
                )
                mangaId != null -> MangaTrackingDialog(
                    item = selectedItem,
                    model = model,
                    onDismiss = { selectedId = null },
                    onRequestUnbind = {
                        confirmation = TrackingConfirmation.Unbind(
                            trackerId,
                            profile.name,
                            (service as? TrackerProviderService)?.configuration?.supportsDelete == true,
                        )
                    },
                )
                else -> selectedId = null
            }
        }

        confirmation?.let { request ->
            var removeRemoteTrack by remember(request) { mutableStateOf(false) }
            TrackingConfirmationDialog(
                request.title,
                request.message,
                when (request) {
                    is TrackingConfirmation.Logout -> MR.strings.logout.localized()
                    is TrackingConfirmation.Unbind -> MR.strings.action_remove.localized()
                },
                onConfirm = {
                    confirmation = null
                    scope.launch {
                        runCatching {
                            when (request) {
                                is TrackingConfirmation.Logout -> model.logout(request.trackerId)
                                is TrackingConfirmation.Unbind ->
                                    model.unbind(request.trackerId, removeRemoteTrack)
                            }
                        }.onFailure { model.reportError(it, request.failure) }
                        selectedId = null
                    }
                },
                onDismiss = { confirmation = null },
                remoteDeleteLabel = (request as? TrackingConfirmation.Unbind)
                    ?.takeIf { it.supportsRemoteDelete }
                    ?.let { MR.strings.track_delete_remote_text.localized(Locale.getDefault(), it.serviceName) },
                removeRemoteTrack = removeRemoteTrack,
                onRemoveRemoteChange = { removeRemoteTrack = it },
            )
        }
    }
}

@Composable
internal fun TrackingConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    remoteDeleteLabel: String? = null,
    removeRemoteTrack: Boolean = false,
    onRemoveRemoteChange: (Boolean) -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                remoteDeleteLabel?.let { label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .desktopSettingsActivationKeys(Role.Checkbox) {
                                onRemoveRemoteChange(!removeRemoteTrack)
                            }
                            .toggleable(
                                value = removeRemoteTrack,
                                role = Role.Checkbox,
                                onValueChange = onRemoveRemoteChange,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = removeRemoteTrack,
                            onCheckedChange = null,
                        )
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            DesktopSettingsTextButton(onClick = onConfirm) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            DesktopSettingsTextButton(onClick = onDismiss) {
                Text(MR.strings.action_cancel.localized())
            }
        },
    )
}

internal fun trackingMessageText(message: TrackingMessage, locale: Locale = Locale.getDefault()): String = when (message) {
    TrackingMessage.LoadFailed -> MR.strings.desktop_tracking_load_failed.localized(locale)
    TrackingMessage.Bound -> MR.strings.desktop_tracking_bound.localized(locale)
    TrackingMessage.Updated -> MR.strings.desktop_tracking_updated.localized(locale)
    TrackingMessage.Removed -> MR.strings.desktop_tracking_removed.localized(locale)
    TrackingMessage.LoggedOut -> MR.strings.logout_success.localized(locale)
    TrackingMessage.SearchTitleEmpty -> MR.strings.desktop_tracking_search_title_empty.localized(locale)
    TrackingMessage.MangaRequired -> MR.strings.desktop_tracking_manga_required.localized(locale)
    TrackingMessage.NotBound -> MR.strings.desktop_tracking_not_bound.localized(locale)
    is TrackingMessage.UnsupportedStatus ->
        MR.strings.desktop_tracking_unsupported_status.localized(locale, message.service)
    is TrackingMessage.UnsupportedScore ->
        MR.strings.desktop_tracking_unsupported_score.localized(locale, message.service)
    TrackingMessage.NegativeChapter -> MR.strings.desktop_tracking_negative_chapter.localized(locale)
    is TrackingMessage.ChapterOutOfRange ->
        MR.strings.desktop_tracking_chapter_out_of_range.localized(locale, message.maximum)
    TrackingMessage.UnknownService -> MR.strings.desktop_tracking_unknown_service.localized(locale)
    TrackingMessage.ServiceUnavailable -> MR.strings.desktop_tracking_service_unavailable.localized(locale)
    TrackingMessage.LoginRequired -> MR.strings.desktop_tracking_login_required.localized(locale)
    TrackingMessage.LoginCancelled -> MR.strings.desktop_tracking_login_cancelled.localized(locale)
    TrackingMessage.LoginFailed -> MR.strings.desktop_tracking_login_failed.localized(locale)
    TrackingMessage.LogoutFailed -> MR.strings.desktop_tracking_logout_failed.localized(locale)
    TrackingMessage.UnbindFailed -> MR.strings.desktop_tracking_unbind_failed.localized(locale)
    TrackingMessage.EnhancedNoMatch -> MR.strings.error_no_match.localized(locale)
    is TrackingMessage.External -> message.text
}

@Composable
internal fun TrackingAutoSyncPreference(
    preferences: DesktopAppPreferences,
    title: String = MR.strings.pref_auto_update_manga_sync.localized(),
    modifier: Modifier = Modifier,
) {
    val autoUpdateTrack by preferences.autoUpdateTrack.changes().collectAsState(
        initial = preferences.autoUpdateTrack.get(),
    )
    SwitchSettingsItem(
        title = title,
        subtitle = MR.strings.desktop_tracking_auto_update_summary.localized(),
        checked = autoUpdateTrack,
        onCheckedChange = preferences.autoUpdateTrack::set,
        modifier = modifier,
    )
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
    val failure: TrackingMessage

    data class Logout(override val trackerId: Long, val serviceName: String) : TrackingConfirmation {
        override val title = MR.strings.logout_title.localized(Locale.getDefault(), serviceName)
        override val message = MR.strings.desktop_tracking_logout_consequence.localized()
        override val failure = TrackingMessage.LogoutFailed
    }

    data class Unbind(
        override val trackerId: Long,
        val serviceName: String,
        val supportsRemoteDelete: Boolean,
    ) : TrackingConfirmation {
        override val title = MR.strings.track_delete_title.localized(Locale.getDefault(), serviceName)
        override val message = MR.strings.track_delete_text.localized()
        override val failure = TrackingMessage.UnbindFailed
    }
}

@Composable
internal fun LoginDialog(
    service: TrackerService,
    onDismiss: () -> Unit,
    oauthCallbackBroker: DesktopTrackerOAuthCallbackBroker = DesktopTrackerOAuthCallbackBroker(),
    openUrl: (String) -> Result<Unit> = DesktopUrlOpener::open,
    onRun: (suspend () -> Unit) -> Unit,
) {
    val authenticating = service as? DesktopAuthenticatingTrackerService
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val method = service.profile.value.authentication
    val credentialsValid = method == TrackerAuthentication.OAUTH ||
        password.isNotBlank() && (method != TrackerAuthentication.USERNAME_PASSWORD || username.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(MR.strings.login_title.localized(Locale.getDefault(), service.profile.value.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (authenticating == null) Text(MR.strings.desktop_tracking_platform_unavailable.localized())
                if (method == TrackerAuthentication.USERNAME_PASSWORD) {
                    OutlinedTextField(username, { username = it }, label = { Text(MR.strings.username.localized()) })
                    DesktopPasswordField(password, { password = it }, MR.strings.password.localized())
                }
                if (method == TrackerAuthentication.API_KEY) {
                    DesktopPasswordField(password, { password = it }, MR.strings.desktop_tracking_api_key.localized())
                }
                if (method == TrackerAuthentication.OAUTH) Text(MR.strings.desktop_tracking_oauth_browser.localized())
            }
        },
        confirmButton = {
            DesktopSettingsButton(
                enabled = authenticating != null && credentialsValid,
                onClick = {
                    onRun {
                        when (method) {
                            TrackerAuthentication.USERNAME_PASSWORD -> authenticating!!.login(username, password)
                            TrackerAuthentication.API_KEY -> authenticating!!.loginWithApiKey(password)
                            TrackerAuthentication.OAUTH -> oauthLogin(
                                authenticating!!,
                                oauthCallbackBroker,
                                openUrl = openUrl,
                            )
                        }
                    }
                },
            ) { Text(MR.strings.login.localized()) }
        },
        dismissButton = { DesktopSettingsTextButton(onClick = onDismiss) { Text(MR.strings.action_cancel.localized()) } },
    )
}

internal suspend fun oauthLogin(
    service: DesktopAuthenticatingTrackerService,
    callbackBroker: DesktopTrackerOAuthCallbackBroker,
    timeout: Duration = Duration.ofMinutes(2),
    openUrl: (String) -> Result<Unit> = DesktopUrlOpener::open,
) {
    val provider = checkNotNull(service.oauthProvider) {
        "${service.profile.value.name} does not expose a Desktop OAuth callback"
    }
    callbackBroker.begin(provider, timeout).use { session ->
        openUrl(service.authorizationUrl(session.redirectUri, session.state)).getOrThrow()
        service.finishOAuth(session.awaitCredential(), session.redirectUri)
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
    var privateTracking by remember(bound) { mutableStateOf(bound?.private ?: false) }
    var startDate by remember(bound) { mutableStateOf(bound?.startDate ?: 0) }
    var finishDate by remember(bound) { mutableStateOf(bound?.finishDate ?: 0) }
    var datePickerTarget by remember { mutableStateOf<TrackingDateTarget?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                MR.strings.desktop_tracking_dialog_title.localized(
                    Locale.getDefault(),
                    model.mangaTitle ?: MR.strings.manga.localized(),
                    item.profile.name,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (bound == null) {
                    OutlinedTextField(query, { query = it }, label = { Text(MR.strings.action_search_hint.localized()) })
                    Button(enabled = !working, onClick = {
                        scope.launch {
                            working = true
                            runCatching { model.search(item.profile.id, query) }
                                .onSuccess { results = it; error = null }
                                .onFailure {
                                    error = (it as? TrackingMessageException)?.trackingMessage?.let(::trackingMessageText)
                                        ?: it.message ?: MR.strings.desktop_tracking_search_failed.localized()
                                }
                            working = false
                        }
                    }) { Text(MR.strings.action_search.localized()) }
                    when {
                        working -> CircularProgressIndicator()
                        results == null -> Unit
                        results!!.isEmpty() -> Text(MR.strings.no_results_found.localized())
                        else -> results!!.forEach { result ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(result.title, Modifier.weight(1f))
                                TextButton(onClick = {
                                    scope.launch {
                                        working = true
                                        runCatching { model.bind(item.profile.id, result) }
                                            .onSuccess { onDismiss() }
                                            .onFailure {
                                                error = (it as? TrackingMessageException)?.trackingMessage?.let(::trackingMessageText)
                                                    ?: it.message ?: MR.strings.desktop_tracking_bind_failed.localized()
                                            }
                                        working = false
                                    }
                                }) { Text(MR.strings.action_track.localized()) }
                            }
                        }
                    }
                } else {
                    ChoiceField(MR.strings.status.localized(), item.statuses, status) { status = it }
                    if (item.scores.isNotEmpty()) {
                        ChoiceField(MR.strings.score.localized(), item.scores.map { it to it.toString() }, score) { score = it }
                    }
                    ChapterStepper(chapter, model.totalChapters?.takeIf { it > 0 } ?: bound.totalChapters.takeIf { it > 0 }) { chapter = it }
                    if (item.providerConfiguration?.supportsPrivateTracking == true) {
                        SwitchSettingsItem(
                            title = MR.strings.tracked_privately.localized(),
                            subtitle = null,
                            checked = privateTracking,
                            onCheckedChange = { privateTracking = it },
                        )
                    }
                    if (item.providerConfiguration?.supportsReadingDates == true) {
                        TrackingDateField(
                            label = MR.strings.track_started_reading_date.localized(),
                            epochMillis = startDate,
                            onClick = { datePickerTarget = TrackingDateTarget.START },
                        )
                        TrackingDateField(
                            label = MR.strings.track_finished_reading_date.localized(),
                            epochMillis = finishDate,
                            onClick = { datePickerTarget = TrackingDateTarget.FINISH },
                        )
                    }
                    Button(enabled = !working, onClick = {
                        scope.launch {
                            working = true
                            runCatching {
                                val supportsDates = item.providerConfiguration?.supportsReadingDates == true
                                model.update(
                                    item.profile.id,
                                    TrackEdit(
                                        status = status,
                                        score = score,
                                        lastChapterRead = chapter,
                                        startDate = startDate.takeIf { supportsDates },
                                        finishDate = finishDate.takeIf { supportsDates },
                                        private = privateTracking.takeIf {
                                            item.providerConfiguration?.supportsPrivateTracking == true
                                        },
                                    ),
                                )
                            }.onSuccess { onDismiss() }
                                .onFailure {
                                    error = (it as? TrackingMessageException)?.trackingMessage?.let(::trackingMessageText)
                                        ?: it.message ?: MR.strings.desktop_tracking_update_failed.localized()
                                }
                            working = false
                        }
                    }) { Text(MR.strings.desktop_tracking_update.localized()) }
                    TextButton(onClick = onRequestUnbind) { Text(MR.strings.action_remove.localized()) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(MR.strings.action_close.localized()) } },
    )
    datePickerTarget?.let { target ->
        val currentDate = if (target == TrackingDateTarget.START) startDate else finishDate
        TrackingDatePickerDialog(
            title = if (target == TrackingDateTarget.START) {
                MR.strings.track_started_reading_date.localized()
            } else {
                MR.strings.track_finished_reading_date.localized()
            },
            selectingStart = target == TrackingDateTarget.START,
            currentDate = currentDate,
            startDate = startDate,
            finishDate = finishDate,
            onSelected = { selected ->
                if (target == TrackingDateTarget.START) startDate = selected else finishDate = selected
                datePickerTarget = null
            },
            onRemove = {
                if (target == TrackingDateTarget.START) startDate = 0 else finishDate = 0
                datePickerTarget = null
            },
            onDismiss = { datePickerTarget = null },
        )
    }
}

private enum class TrackingDateTarget { START, FINISH }

@Composable
private fun TrackingDateField(
    label: String,
    epochMillis: Long,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            Text(
                epochMillis.takeIf { it > 0 }
                    ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() }
                    ?: MR.strings.none.localized(),
            )
        },
        modifier = Modifier.desktopSettingsAction(Role.Button, onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackingDatePickerDialog(
    title: String,
    selectingStart: Boolean,
    currentDate: Long,
    startDate: Long,
    finishDate: Long,
    onSelected: (Long) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val initialSelection = remember(currentDate, zone) {
        currentDate.takeIf { it > 0 }
            ?.let { trackingDatePickerSelection(it, zone) }
            ?: LocalDate.now(zone).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
    val selectableDates = remember(selectingStart, startDate, finishDate, zone) {
        TrackingSelectableDates(selectingStart, startDate, finishDate, zone)
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialSelection,
        selectableDates = selectableDates,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            DesktopSettingsTextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let { onSelected(trackingDateFromPickerSelection(it, zone)) }
                },
            ) {
                Text(MR.strings.action_ok.localized())
            }
        },
        dismissButton = {
            Row {
                if (currentDate > 0) {
                    DesktopSettingsTextButton(onClick = onRemove) {
                        Text(MR.strings.action_remove.localized())
                    }
                }
                DesktopSettingsTextButton(onClick = onDismiss) {
                    Text(MR.strings.action_cancel.localized())
                }
            }
        },
    ) {
        DatePicker(
            state = state,
            title = { Text(title) },
        )
    }
}

internal fun trackingDatePickerSelection(localEpochMillis: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(localEpochMillis)
        .atZone(zone)
        .toLocalDate()
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()

internal fun trackingDateFromPickerSelection(utcEpochMillis: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(utcEpochMillis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()

@OptIn(ExperimentalMaterial3Api::class)
internal class TrackingSelectableDates(
    private val selectingStart: Boolean,
    startDate: Long,
    finishDate: Long,
    zone: ZoneId,
    private val today: LocalDate = LocalDate.now(zone),
) : SelectableDates {
    private val start = startDate.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    private val finish = finishDate.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    override fun isSelectableDate(utcTimeMillis: Long): Boolean {
        val target = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
        if (target > today) return false
        return if (selectingStart) finish == null || target <= finish else start == null || target >= start
    }

    override fun isSelectableYear(year: Int): Boolean {
        if (year > today.year) return false
        return if (selectingStart) finish == null || year <= finish.year else start == null || year >= start.year
    }
}

@Composable
private fun <T> ChoiceField(label: String, choices: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("$label: ${choices.firstOrNull { it.first == selected }?.second ?: MR.strings.desktop_tracking_choice.localized()}")
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
        Text(MR.strings.chapters.localized())
        TextButton(onClick = { onChange((value - 1.0).coerceAtLeast(0.0)) }, enabled = value > 0) { Text("−") }
        Text(if (maximum == null) value.toString() else "$value / $maximum")
        TextButton(
            onClick = { onChange(value + 1.0) },
            enabled = maximum == null || value < maximum,
        ) { Text("+") }
    }
}
