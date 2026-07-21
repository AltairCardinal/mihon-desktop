package mihon.desktop.security

import mihon.desktop.platform.DesktopCredentialStore
import mihon.desktop.platform.PlatformCredentialUnavailableException
import mihon.domain.security.AuthenticationResult

class DesktopPassphraseVerifier(
    private val credentialStore: DesktopCredentialStore,
) {
    @Synchronized
    fun set(passphrase: CharArray): AuthenticationResult = useInput(passphrase) {
        if (it.isEmpty()) return@useInput AuthenticationResult.Failed
        credentialStore.save(ACCOUNT, it)
        AuthenticationResult.Success
    }

    @Synchronized
    fun verify(passphrase: CharArray): AuthenticationResult = useInput(passphrase, ::verifyLoaded)

    @Synchronized
    fun probe(): AuthenticationResult = credentialResult {
        credentialStore.withSecret(ACCOUNT) { secret ->
            if (secret == null) AuthenticationResult.Failed else AuthenticationResult.Success
        }
    }

    @Synchronized
    fun reset(current: CharArray, replacement: CharArray): AuthenticationResult =
        resetWithOutcome(current, replacement).result

    @Synchronized
    internal fun resetWithOutcome(current: CharArray, replacement: CharArray): PassphraseResetOutcome =
        replaceAndCommit(current, replacement) {}

    @Synchronized
    internal fun replaceAndCommit(
        current: CharArray?,
        replacement: CharArray,
        commit: () -> Unit,
    ): PassphraseResetOutcome = try {
        if (replacement.isEmpty()) return PassphraseResetOutcome(AuthenticationResult.Failed, credentialPreserved = true)
        try {
            credentialStore.withSecret(ACCOUNT) { expected ->
                if (current != null && (expected == null || !matches(expected, current))) {
                    return@withSecret PassphraseResetOutcome(AuthenticationResult.Failed, credentialPreserved = true)
                }
                val backup = expected?.copyOf()
                try {
                    try {
                        credentialStore.save(ACCOUNT, replacement)
                        commit()
                        PassphraseResetOutcome(AuthenticationResult.Success, credentialPreserved = true)
                    } catch (failure: RuntimeException) {
                        val restored = restoreCredential(backup, failure)
                        PassphraseResetOutcome(failure.toAuthenticationResult(), credentialPreserved = restored)
                    }
                } finally {
                    backup?.fill('\u0000')
                }
            }
        } catch (failure: RuntimeException) {
            PassphraseResetOutcome(failure.toAuthenticationResult(), credentialPreserved = true)
        }
    } finally {
        current?.fill('\u0000')
        replacement.fill('\u0000')
    }

    @Synchronized
    internal fun deleteWithOutcome(): PassphraseResetOutcome = try {
        credentialStore.withSecret(ACCOUNT) { expected ->
            val backup = expected?.copyOf()
            try {
                try {
                    credentialStore.delete(ACCOUNT)
                    PassphraseResetOutcome(AuthenticationResult.Success, credentialPreserved = false)
                } catch (failure: RuntimeException) {
                    val restored = restoreCredential(backup, failure)
                    PassphraseResetOutcome(failure.toAuthenticationResult(), credentialPreserved = restored)
                }
            } finally {
                backup?.fill('\u0000')
            }
        }
    } catch (failure: RuntimeException) {
        PassphraseResetOutcome(failure.toAuthenticationResult(), credentialPreserved = true)
    }

    @Synchronized
    fun delete(): AuthenticationResult = credentialResult {
        credentialStore.delete(ACCOUNT)
        AuthenticationResult.Success
    }

    private fun verifyLoaded(candidate: CharArray): AuthenticationResult = credentialResult {
        credentialStore.withSecret(ACCOUNT) { expected ->
            if (expected == null) return@withSecret AuthenticationResult.Failed
            if (matches(expected, candidate)) AuthenticationResult.Success else AuthenticationResult.Failed
        }
    }

    private fun matches(expected: CharArray, candidate: CharArray): Boolean {
        var difference = expected.size xor candidate.size
        repeat(maxOf(expected.size, candidate.size)) { index ->
            difference = difference or
                (expected.getOrElse(index) { '\u0000' }.code xor candidate.getOrElse(index) { '\u0000' }.code)
        }
        return difference == 0
    }

    private fun restoreCredential(backup: CharArray?, failure: RuntimeException): Boolean = try {
        if (backup == null) credentialStore.delete(ACCOUNT) else credentialStore.save(ACCOUNT, backup)
        true
    } catch (rollbackFailure: RuntimeException) {
        if (rollbackFailure !== failure) failure.addSuppressed(rollbackFailure)
        false
    }

    private inline fun useInput(input: CharArray, operation: (CharArray) -> AuthenticationResult) = try {
        credentialResult { operation(input) }
    } finally {
        input.fill('\u0000')
    }

    private inline fun credentialResult(operation: () -> AuthenticationResult) = try {
        operation()
    } catch (_: PlatformCredentialUnavailableException) {
        AuthenticationResult.Unavailable
    } catch (_: RuntimeException) {
        AuthenticationResult.Error
    }

    private companion object {
        const val ACCOUNT = "desktop-app-lock"
    }
}

internal data class PassphraseResetOutcome(
    val result: AuthenticationResult,
    val credentialPreserved: Boolean,
)

private fun RuntimeException.toAuthenticationResult() =
    if (this is PlatformCredentialUnavailableException) AuthenticationResult.Unavailable else AuthenticationResult.Error
