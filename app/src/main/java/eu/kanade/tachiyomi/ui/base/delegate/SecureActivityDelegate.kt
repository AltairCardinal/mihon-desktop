package eu.kanade.tachiyomi.ui.base.delegate

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.ui.security.UnlockActivity
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.isAuthenticationSupported
import eu.kanade.tachiyomi.util.view.setSecureScreen
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import mihon.domain.security.AppLockPolicy
import mihon.domain.security.AppLockState
import mihon.domain.security.AuthenticationResult
import mihon.domain.security.SecureScreenPolicy
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

interface SecureActivityDelegate {
    fun registerSecureActivity(activity: AppCompatActivity)

    companion object {
        /**
         * Set to true if we need the first activity to authenticate.
         *
         * Always require unlock if app is killed.
         */
        private val appLockLifecycle = AndroidAppLockLifecycleConsumer(lockEnabled = true)

        var requireUnlock: Boolean
            get() = appLockLifecycle.state.requiresUnlock
            set(value) = appLockLifecycle.overrideRequiresUnlock(value)

        fun onApplicationStopped() {
            val preferences = Injekt.get<SecurityPreferences>()
            if (!preferences.useAuthenticator().get()) return

            appLockLifecycle.onApplicationStopped(
                lockEnabled = true,
                delayMinutes = preferences.lockAppAfter().get(),
                nowMillis = System.currentTimeMillis(),
                isAuthenticating = AuthenticatorUtil.isAuthenticating,
                persistLastClosed = preferences.lastAppClosed()::set,
            )
        }

        /**
         * Checks if unlock is needed when app comes foreground.
         */
        fun onApplicationStart() {
            val preferences = Injekt.get<SecurityPreferences>()
            if (!preferences.useAuthenticator().get()) return

            val lastClosedPref = preferences.lastAppClosed()
            appLockLifecycle.onApplicationStart(
                lockEnabled = true,
                delayMinutes = preferences.lockAppAfter().get(),
                lastClosedAtMillis = lastClosedPref.get().takeIf { lastClosedPref.isSet() },
                nowMillis = System.currentTimeMillis(),
                isAuthenticating = AuthenticatorUtil.isAuthenticating,
                deleteLastClosed = lastClosedPref::delete,
            )
        }

        fun unlock() {
            appLockLifecycle.onUnlockAuthentication(AuthenticationResult.Success)
        }
    }
}

class SecureActivityDelegateImpl : SecureActivityDelegate, DefaultLifecycleObserver {

    private lateinit var activity: AppCompatActivity

    private val preferences: BasePreferences by injectLazy()
    private val securityPreferences: SecurityPreferences by injectLazy()

    override fun registerSecureActivity(activity: AppCompatActivity) {
        this.activity = activity
        activity.lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        setSecureScreen()
    }

    override fun onResume(owner: LifecycleOwner) {
        setAppLock()
    }

    private fun setSecureScreen() {
        val secureScreenFlow = securityPreferences.secureScreen().changes()
        val incognitoModeFlow = preferences.incognitoMode().changes()
        val consumer = AndroidSecureScreenConsumer(activity.window::setSecureScreen)
        combine(secureScreenFlow, incognitoModeFlow) { secureScreen, incognitoMode ->
            secureScreen to incognitoMode
        }
            .onEach { (mode, incognito) -> consumer.apply(mode, incognito) }
            .launchIn(activity.lifecycleScope)
    }

    private fun setAppLock() {
        if (!securityPreferences.useAuthenticator().get()) return
        if (activity.isAuthenticationSupported()) {
            if (!SecureActivityDelegate.requireUnlock) return
            activity.startActivity(Intent(activity, UnlockActivity::class.java))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                activity.overridePendingTransition(0, 0)
            }
        } else {
            securityPreferences.useAuthenticator().set(false)
        }
    }
}

internal class AndroidAppLockLifecycleConsumer(lockEnabled: Boolean) {
    var state: AppLockState = AppLockPolicy.initial(lockEnabled)
        private set

    fun onApplicationStopped(
        lockEnabled: Boolean,
        delayMinutes: Int,
        nowMillis: Long,
        isAuthenticating: Boolean,
        persistLastClosed: (Long) -> Unit = {},
    ): AppLockState {
        val previous = state.lastClosedAtMillis
        state = AppLockPolicy.onApplicationStopped(state, lockEnabled, delayMinutes, nowMillis, isAuthenticating)
        state.lastClosedAtMillis?.takeIf { it != previous }?.let(persistLastClosed)
        return state
    }

    fun onApplicationStart(
        lockEnabled: Boolean,
        delayMinutes: Int,
        lastClosedAtMillis: Long?,
        nowMillis: Long,
        isAuthenticating: Boolean,
        deleteLastClosed: () -> Unit = {},
    ): AppLockState {
        state = AppLockPolicy.onApplicationStart(
            state.copy(lastClosedAtMillis = lastClosedAtMillis),
            lockEnabled,
            delayMinutes,
            nowMillis,
            isAuthenticating,
        )
        deleteLastClosed()
        return state
    }

    fun onUnlockAuthentication(result: AuthenticationResult) =
        AppLockPolicy.onUnlockAuthentication(state, result).also { state = it }

    fun overrideRequiresUnlock(value: Boolean) {
        state = state.copy(requiresUnlock = value)
    }
}

internal class AndroidSecureScreenConsumer(private val applyProtected: (Boolean) -> Unit) {
    fun apply(mode: SecurityPreferences.SecureScreenMode, incognito: Boolean) {
        applyProtected(SecureScreenPolicy.isProtected(mode, incognito))
    }
}
