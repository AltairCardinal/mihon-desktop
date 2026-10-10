package eu.kanade.tachiyomi.data.sync

import android.content.Context

/** Changing the persistent selection never changes a live process's Context roots. */
object AndroidRecoverySwitch {
    suspend fun createAndRestart(
        raw: Context,
        stopOldProcess: suspend () -> Boolean,
        startMainProcess: () -> Unit,
    ): Boolean {
        if (!stopOldProcess()) return false
        AndroidRecoveryProfile.createAndSelect(raw)
        startMainProcess()
        return true
    }
}
