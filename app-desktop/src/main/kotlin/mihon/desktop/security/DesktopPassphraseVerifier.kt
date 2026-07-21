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
    fun reset(current: CharArray, replacement: CharArray): AuthenticationResult = useInput(current) { candidate ->
        val verified = verifyLoaded(candidate)
        if (verified != AuthenticationResult.Success) {
            replacement.fill('\u0000')
            verified
        } else {
            set(replacement)
        }
    }

    @Synchronized
    fun delete(): AuthenticationResult = credentialResult {
        credentialStore.delete(ACCOUNT)
        AuthenticationResult.Success
    }

    private fun verifyLoaded(candidate: CharArray): AuthenticationResult = credentialResult {
        credentialStore.withSecret(ACCOUNT) { expected ->
            if (expected == null) return@withSecret AuthenticationResult.Failed
            var difference = expected.size xor candidate.size
            repeat(maxOf(expected.size, candidate.size)) { index ->
                difference = difference or
                    (expected.getOrElse(index) { '\u0000' }.code xor candidate.getOrElse(index) { '\u0000' }.code)
            }
            if (difference == 0) AuthenticationResult.Success else AuthenticationResult.Failed
        }
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
