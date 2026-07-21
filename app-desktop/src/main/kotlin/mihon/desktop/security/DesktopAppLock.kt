package mihon.desktop.security

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import mihon.domain.security.AppLockPolicy
import mihon.domain.security.AppLockState
import mihon.domain.security.AuthenticationResult
import java.io.File

interface DesktopAppLockLifecycle {
    fun onApplicationStarted()
    fun onApplicationStopped()
}

class DesktopAppLock(
    private val preferences: SecurityPreferences,
    private val verifier: DesktopPassphraseVerifier,
    private val profileDirectory: File? = null,
    private val openProfileDirectory: (File) -> Boolean = { false },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : DesktopAppLockLifecycle {
    private val mutableState = MutableStateFlow(AppLockPolicy.initial(preferences.useAuthenticator().get()))
    val state = mutableState.asStateFlow()
    private val mutableRecovery = MutableStateFlow<AuthenticationResult?>(null)
    val recovery = mutableRecovery.asStateFlow()

    fun openRecoveryProfile() = profileDirectory?.let(openProfileDirectory) ?: false

    @Synchronized
    override fun onApplicationStarted() {
        val enabled = preferences.useAuthenticator().get()
        val probe = if (enabled) verifier.probe() else AuthenticationResult.Success
        mutableRecovery.value = probe.takeUnless { it == AuthenticationResult.Success }
        val persisted = preferences.lastAppClosed().get().takeIf { it > 0 }
        mutableState.value = AppLockPolicy.onApplicationStart(
            mutableState.value.copy(lastClosedAtMillis = persisted),
            enabled,
            preferences.lockAppAfter().get(),
            nowMillis(),
        )
        if (probe == AuthenticationResult.Failed) mutableState.value = mutableState.value.copy(requiresUnlock = true)
        preferences.lastAppClosed().delete()
    }

    @Synchronized
    override fun onApplicationStopped() {
        val next = AppLockPolicy.onApplicationStopped(
            mutableState.value,
            preferences.useAuthenticator().get(),
            preferences.lockAppAfter().get(),
            nowMillis(),
        )
        mutableState.value = next
        next.lastClosedAtMillis?.let(preferences.lastAppClosed()::set) ?: preferences.lastAppClosed().delete()
    }

    @Synchronized
    fun authenticate(passphrase: CharArray?): AuthenticationResult {
        val result = passphrase?.let(verifier::verify) ?: AuthenticationResult.Cancelled
        mutableState.value = AppLockPolicy.onUnlockAuthentication(mutableState.value, result)
        if (result == AuthenticationResult.Success) preferences.lastAppClosed().delete()
        return result
    }
}
