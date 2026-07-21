package mihon.desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.privacy.DesktopPrivacyCapabilities
import mihon.desktop.security.DesktopPassphraseVerifier
import mihon.desktop.ui.security.DesktopPasswordField
import mihon.domain.security.AuthenticationResult
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

enum class SecurityBackendCapability { Available, Unavailable, Error }

enum class SecuritySettingsFeedback { Saved, PassphraseMismatch, AuthenticationFailed, Cancelled, BackendUnavailable, BackendError }

data class SecuritySettingsState(
    val enabled: Boolean,
    val delayMinutes: Int,
    val backendCapability: SecurityBackendCapability,
    val feedback: SecuritySettingsFeedback? = null,
) {
    val canConfigure: Boolean get() = backendCapability == SecurityBackendCapability.Available
}

internal interface SecuritySettingsPersistence {
    fun readEnabled(): Boolean
    fun writeEnabled(enabled: Boolean)
    fun readDelayMinutes(): Int
    fun writeDelayMinutes(delayMinutes: Int)
}

private class SecurityPreferencesPersistence(
    private val preferences: SecurityPreferences,
) : SecuritySettingsPersistence {
    override fun readEnabled() = preferences.useAuthenticator().get()
    override fun writeEnabled(enabled: Boolean) = preferences.useAuthenticator().set(enabled)
    override fun readDelayMinutes() = preferences.lockAppAfter().get()
    override fun writeDelayMinutes(delayMinutes: Int) = preferences.lockAppAfter().set(delayMinutes)
}

class SecuritySettingsController internal constructor(
    private val persistence: SecuritySettingsPersistence,
    private val verifier: DesktopPassphraseVerifier,
) {
    constructor(preferences: SecurityPreferences, verifier: DesktopPassphraseVerifier) :
        this(SecurityPreferencesPersistence(preferences), verifier)

    private var knownEnabled = false
    private var knownDelayMinutes = 0
    private val mutableState = MutableStateFlow(
        SecuritySettingsState(
            enabled = false,
            delayMinutes = 0,
            backendCapability = SecurityBackendCapability.Available,
        ),
    )
    val state = mutableState.asStateFlow()

    init {
        val preferencesReadable = refreshPreferences()
        if (preferencesReadable) {
            publish(verifier.probe(), successFeedback = null)
        } else {
            publish(AuthenticationResult.Error)
        }
    }

    fun enable(passphrase: CharArray?, confirmation: CharArray?): AuthenticationResult = try {
        if (passphrase == null || confirmation == null) return publish(AuthenticationResult.Cancelled)
        if (!state.value.canConfigure) return publish(capabilityResult())
        if (!passphrase.contentEquals(confirmation)) {
            return publish(AuthenticationResult.Failed, SecuritySettingsFeedback.PassphraseMismatch)
        }
        val outcome = verifier.replaceAndCommit(current = null, replacement = passphrase) {
            check(writeEnabledWithRollback(enabled = true, rollback = false))
        }
        if (outcome.result != AuthenticationResult.Success) bestEffortWriteEnabled(false)
        publish(outcome.result)
    } catch (_: RuntimeException) {
        bestEffortWriteEnabled(false)
        publish(AuthenticationResult.Error)
    } finally {
        passphrase?.fill('\u0000')
        confirmation?.fill('\u0000')
    }

    fun disable(currentPassphrase: CharArray?): AuthenticationResult = try {
        if (currentPassphrase == null) return publish(AuthenticationResult.Cancelled)
        if (!state.value.canConfigure) return publish(capabilityResult())
        val verified = verifier.verify(currentPassphrase)
        if (verified != AuthenticationResult.Success) return publish(verified)
        if (!writeEnabledWithRollback(enabled = false, rollback = true)) return publish(AuthenticationResult.Error)
        val outcome = verifier.deleteWithOutcome()
        if (outcome.result != AuthenticationResult.Success) {
            if (outcome.credentialPreserved) {
                if (!bestEffortWriteEnabled(true)) return publish(AuthenticationResult.Error)
            } else {
                bestEffortWriteEnabled(false)
            }
        }
        publish(outcome.result)
    } catch (_: RuntimeException) {
        publish(AuthenticationResult.Error)
    } finally {
        currentPassphrase?.fill('\u0000')
    }

    fun changeDelay(delayMinutes: Int, currentPassphrase: CharArray?): AuthenticationResult = try {
        if (currentPassphrase == null) return publish(AuthenticationResult.Cancelled)
        if (!state.value.canConfigure) return publish(capabilityResult())
        val verified = verifier.verify(currentPassphrase)
        if (verified != AuthenticationResult.Success) return publish(verified)
        val oldDelayMinutes = knownDelayMinutes
        if (!writeDelayWithRollback(delayMinutes, oldDelayMinutes)) return publish(AuthenticationResult.Error)
        publish(AuthenticationResult.Success)
    } catch (_: RuntimeException) {
        publish(AuthenticationResult.Error)
    } finally {
        currentPassphrase?.fill('\u0000')
    }

    fun changePassphrase(
        currentPassphrase: CharArray?,
        replacement: CharArray?,
        confirmation: CharArray?,
    ): AuthenticationResult = try {
        if (currentPassphrase == null || replacement == null || confirmation == null) {
            return publish(AuthenticationResult.Cancelled)
        }
        if (!state.value.canConfigure) return publish(capabilityResult())
        if (!replacement.contentEquals(confirmation)) {
            return publish(AuthenticationResult.Failed, SecuritySettingsFeedback.PassphraseMismatch)
        }
        val wasEnabled = knownEnabled
        if (wasEnabled && !writeEnabledWithRollback(enabled = false, rollback = true)) {
            return publish(AuthenticationResult.Error)
        }
        val outcome = verifier.replaceAndCommit(currentPassphrase, replacement) {
            if (wasEnabled) check(writeEnabledWithRollback(enabled = true, rollback = false))
        }
        if (outcome.result != AuthenticationResult.Success) {
            if (outcome.credentialPreserved && wasEnabled) {
                if (!bestEffortWriteEnabled(true)) return publish(AuthenticationResult.Error)
            } else {
                bestEffortWriteEnabled(false)
            }
        }
        publish(outcome.result)
    } catch (_: RuntimeException) {
        bestEffortWriteEnabled(false)
        publish(AuthenticationResult.Error)
    } finally {
        currentPassphrase?.fill('\u0000')
        replacement?.fill('\u0000')
        confirmation?.fill('\u0000')
    }

    private fun capabilityResult() = when (state.value.backendCapability) {
        SecurityBackendCapability.Available -> AuthenticationResult.Success
        SecurityBackendCapability.Unavailable -> AuthenticationResult.Unavailable
        SecurityBackendCapability.Error -> AuthenticationResult.Error
    }

    private fun writeEnabledWithRollback(enabled: Boolean, rollback: Boolean): Boolean = try {
        persistence.writeEnabled(enabled)
        knownEnabled = enabled
        true
    } catch (failure: RuntimeException) {
        try {
            persistence.writeEnabled(rollback)
            knownEnabled = rollback
        } catch (rollbackFailure: RuntimeException) {
            if (rollbackFailure !== failure) failure.addSuppressed(rollbackFailure)
            refreshPreferences()
        }
        false
    }

    private fun bestEffortWriteEnabled(enabled: Boolean): Boolean = try {
        persistence.writeEnabled(enabled)
        knownEnabled = enabled
        true
    } catch (_: RuntimeException) {
        refreshPreferences()
        false
    }

    private fun writeDelayWithRollback(delayMinutes: Int, rollback: Int): Boolean = try {
        persistence.writeDelayMinutes(delayMinutes)
        knownDelayMinutes = delayMinutes
        true
    } catch (failure: RuntimeException) {
        try {
            persistence.writeDelayMinutes(rollback)
            knownDelayMinutes = rollback
        } catch (rollbackFailure: RuntimeException) {
            if (rollbackFailure !== failure) failure.addSuppressed(rollbackFailure)
            refreshPreferences()
        }
        false
    }

    private fun refreshPreferences(): Boolean = try {
        knownEnabled = persistence.readEnabled()
        knownDelayMinutes = persistence.readDelayMinutes()
        true
    } catch (_: RuntimeException) {
        false
    }

    private fun publish(
        result: AuthenticationResult,
        feedback: SecuritySettingsFeedback? = null,
        successFeedback: SecuritySettingsFeedback? = SecuritySettingsFeedback.Saved,
    ): AuthenticationResult {
        val resolvedResult = if (refreshPreferences()) result else AuthenticationResult.Error
        val capability = when (resolvedResult) {
            AuthenticationResult.Unavailable -> SecurityBackendCapability.Unavailable
            AuthenticationResult.Error -> SecurityBackendCapability.Error
            else -> state.value.backendCapability
        }
        val resolvedFeedback = feedback ?: when (resolvedResult) {
            AuthenticationResult.Success -> successFeedback
            AuthenticationResult.Cancelled -> SecuritySettingsFeedback.Cancelled
            AuthenticationResult.Failed -> SecuritySettingsFeedback.AuthenticationFailed
            AuthenticationResult.Unavailable -> SecuritySettingsFeedback.BackendUnavailable
            AuthenticationResult.Error -> SecuritySettingsFeedback.BackendError
        }
        mutableState.value = SecuritySettingsState(
            enabled = knownEnabled,
            delayMinutes = knownDelayMinutes,
            backendCapability = capability,
            feedback = resolvedFeedback,
        )
        return resolvedResult
    }
}

class SecuritySettingsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val privacyCapabilities = LocalDesktopUiDependencies.current.privacyCapabilities
        val controller = remember {
            SecuritySettingsController(Injekt.get<SecurityPreferences>(), Injekt.get<DesktopPassphraseVerifier>())
        }
        val state by controller.state.collectAsState()
        var action by remember { mutableStateOf<SecurityAction?>(null) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(MR.strings.desktop_security_title.localized()) },
                    navigationIcon = {
                        IconButton(onClick = navigator::pop) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = MR.strings.desktop_security_cancel.localized(),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(MR.strings.desktop_security_lock_enabled.localized())
                        Text(
                            MR.strings.desktop_security_lock_enabled_summary.localized(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = state.enabled,
                        enabled = state.canConfigure,
                        onCheckedChange = { enabled ->
                            action = if (enabled) SecurityAction.Enable else SecurityAction.Disable
                        },
                    )
                }
                if (state.enabled) {
                    Text(
                        MR.strings.desktop_security_lock_delay.localized(),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DelayButton(MR.strings.desktop_security_delay_never.localized(), -1, state, { action = it })
                        DelayButton(MR.strings.desktop_security_delay_immediately.localized(), 0, state, { action = it })
                        DelayButton(MR.strings.desktop_security_delay_five_minutes.localized(), 5, state, { action = it })
                    }
                    Button(
                        onClick = { action = SecurityAction.ChangePassphrase },
                        enabled = state.canConfigure,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Text(MR.strings.desktop_security_change_passphrase.localized())
                    }
                }
                state.feedback?.let { Text(feedbackText(it), modifier = Modifier.padding(horizontal = 16.dp)) }
                DesktopPrivacySettings(
                    capabilities = privacyCapabilities,
                    nativeNotificationControl = { DesktopHideNotificationContentSetting() },
                    telemetryControls = null,
                )
            }
        }
        action?.let { pending ->
            SecurityPassphraseDialog(
                action = pending,
                onDismiss = { action = null },
                onConfirm = { current, replacement, confirmation ->
                    when (pending) {
                        SecurityAction.Enable -> controller.enable(replacement, confirmation)
                        SecurityAction.Disable -> controller.disable(current)
                        SecurityAction.ChangePassphrase -> controller.changePassphrase(current, replacement, confirmation)
                        is SecurityAction.Delay -> controller.changeDelay(pending.minutes, current)
                    }
                    action = null
                },
            )
        }
    }
}

@Composable
internal fun DesktopPrivacySettings(
    capabilities: DesktopPrivacyCapabilities,
    nativeNotificationControl: (@Composable () -> Unit)?,
    telemetryControls: (@Composable () -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(MR.strings.desktop_privacy_capabilities_title.localized(), style = MaterialTheme.typography.titleSmall)
        if (capabilities.nativeSystemNotifications.isSupported) {
            nativeNotificationControl?.invoke()
        } else {
            Text(MR.strings.desktop_privacy_native_notifications_unavailable.localized())
        }
        if (capabilities.telemetryRuntime.isSupported) {
            telemetryControls?.invoke()
        } else {
            Text(MR.strings.desktop_privacy_telemetry_unavailable.localized())
        }
        if (!capabilities.systemWidgetProvider.isSupported) {
            Text(
                if (capabilities.sharedUpdatesData.isSupported) {
                    MR.strings.desktop_privacy_widget_unavailable_updates_available.localized()
                } else {
                    MR.strings.desktop_privacy_widget_unavailable.localized()
                },
            )
        }
    }
}

@Composable
private fun DesktopHideNotificationContentSetting() {
    val preference = remember { Injekt.get<SecurityPreferences>().hideNotificationContent() }
    val hidden by preference.changes().collectAsState(initial = preference.get())
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(MR.strings.desktop_security_hide_notification_content.localized(), modifier = Modifier.weight(1f))
        Switch(checked = hidden, onCheckedChange = preference::set)
    }
}

private sealed interface SecurityAction {
    data object Enable : SecurityAction
    data object Disable : SecurityAction
    data object ChangePassphrase : SecurityAction
    data class Delay(val minutes: Int) : SecurityAction
}

@Composable
private fun DelayButton(
    text: String,
    minutes: Int,
    state: SecuritySettingsState,
    onAction: (SecurityAction) -> Unit,
) {
    Button(onClick = { onAction(SecurityAction.Delay(minutes)) }, enabled = state.canConfigure && state.delayMinutes != minutes) {
        Text(text)
    }
}

@Composable
private fun SecurityPassphraseDialog(
    action: SecurityAction,
    onDismiss: () -> Unit,
    onConfirm: (CharArray?, CharArray?, CharArray?) -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val needsCurrent = action != SecurityAction.Enable
    val needsReplacement = action == SecurityAction.Enable || action == SecurityAction.ChangePassphrase
    val title = when (action) {
        SecurityAction.Enable -> MR.strings.desktop_security_enable.localized()
        SecurityAction.Disable -> MR.strings.desktop_security_disable.localized()
        SecurityAction.ChangePassphrase -> MR.strings.desktop_security_change_passphrase.localized()
        is SecurityAction.Delay -> MR.strings.desktop_security_lock_delay.localized()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (needsCurrent) DesktopPasswordField(current, { current = it }, MR.strings.desktop_security_current_passphrase.localized())
                if (needsReplacement) {
                    DesktopPasswordField(replacement, { replacement = it }, MR.strings.desktop_security_new_passphrase.localized())
                    DesktopPasswordField(confirmation, { confirmation = it }, MR.strings.desktop_security_confirm_passphrase.localized())
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        current.takeIf { needsCurrent }?.toCharArray(),
                        replacement.takeIf { needsReplacement }?.toCharArray(),
                        confirmation.takeIf { needsReplacement }?.toCharArray(),
                    )
                    current = ""
                    replacement = ""
                    confirmation = ""
                },
            ) { Text(MR.strings.desktop_security_save.localized()) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(MR.strings.desktop_security_cancel.localized()) }
        },
    )
}

@Composable
private fun feedbackText(feedback: SecuritySettingsFeedback): String = when (feedback) {
    SecuritySettingsFeedback.Saved -> MR.strings.desktop_security_saved.localized()
    SecuritySettingsFeedback.PassphraseMismatch -> MR.strings.desktop_security_passphrase_mismatch.localized()
    SecuritySettingsFeedback.AuthenticationFailed -> MR.strings.desktop_security_authentication_failed.localized()
    SecuritySettingsFeedback.Cancelled -> MR.strings.desktop_security_cancelled.localized()
    SecuritySettingsFeedback.BackendUnavailable -> MR.strings.desktop_security_backend_unavailable.localized()
    SecuritySettingsFeedback.BackendError -> MR.strings.desktop_security_backend_error.localized()
}
