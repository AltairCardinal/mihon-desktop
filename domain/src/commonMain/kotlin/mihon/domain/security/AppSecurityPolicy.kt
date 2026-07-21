package mihon.domain.security

import eu.kanade.tachiyomi.core.security.SecurityPreferences

data class AppLockState(val requiresUnlock: Boolean, val lastClosedAtMillis: Long? = null)

enum class AuthenticationResult { Success, Cancelled, Failed, Unavailable, Error }

object AppLockPolicy {
    fun initial(lockEnabled: Boolean) = AppLockState(requiresUnlock = lockEnabled)

    fun onApplicationStopped(
        state: AppLockState,
        lockEnabled: Boolean,
        delayMinutes: Int,
        nowMillis: Long,
        isAuthenticating: Boolean = false,
    ): AppLockState =
        if (!lockEnabled || isAuthenticating || state.requiresUnlock ||
            delayMinutes <= 0
        ) {
            state
        } else {
            state.copy(lastClosedAtMillis = nowMillis)
        }

    fun onApplicationStart(
        state: AppLockState,
        lockEnabled: Boolean,
        delayMinutes: Int,
        nowMillis: Long,
        isAuthenticating: Boolean = false,
    ): AppLockState {
        if (!lockEnabled) return AppLockState(requiresUnlock = false)
        if (state.requiresUnlock || isAuthenticating) return state.copy(lastClosedAtMillis = null)
        val requiresUnlock = when (delayMinutes) {
            -1 -> false
            0 -> true
            else -> state.lastClosedAtMillis?.let { it + delayMinutes * MILLIS_PER_MINUTE <= nowMillis } ?: true
        }
        return AppLockState(requiresUnlock)
    }

    /** Only an unlock attempt may clear a lock; settings confirmation consumes its result separately. */
    fun onUnlockAuthentication(state: AppLockState, result: AuthenticationResult) =
        if (result == AuthenticationResult.Success) state.copy(requiresUnlock = false) else state

    private const val MILLIS_PER_MINUTE = 60_000L
}

object SecureScreenPolicy {
    fun isProtected(mode: SecurityPreferences.SecureScreenMode, incognito: Boolean): Boolean =
        mode == SecurityPreferences.SecureScreenMode.ALWAYS ||
            (mode == SecurityPreferences.SecureScreenMode.INCOGNITO && incognito)
}
