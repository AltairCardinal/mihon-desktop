package tachiyomi.presentation.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.LifecycleCoroutineScope
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.updates.interactor.GetUpdates

class WidgetManager internal constructor(private val privacyConsumer: WidgetPrivacyConsumer) {
    constructor(getUpdates: GetUpdates, securityPreferences: SecurityPreferences) : this(
        WidgetPrivacyConsumer(WidgetPrivacyDataSource(getUpdates, securityPreferences)),
    )

    internal fun refreshes(afterMillis: Long = BaseUpdatesGridGlanceWidget.DateLimit.toEpochMilli()) =
        privacyConsumer.refreshes(afterMillis)

    fun Context.init(scope: LifecycleCoroutineScope) {
        refreshes()
            .onEach {
                try {
                    UpdatesGridGlanceWidget().updateAll(this)
                    UpdatesGridCoverScreenGlanceWidget().updateAll(this)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to update widget" }
                }
            }
            .flowOn(Dispatchers.Default)
            .launchIn(scope)
    }
}
