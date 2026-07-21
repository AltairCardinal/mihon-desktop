package tachiyomi.presentation.widget

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations

internal sealed interface WidgetPrivacyData {
    data object Locked : WidgetPrivacyData

    data class Content(val updates: List<UpdatesWithRelations>) : WidgetPrivacyData
}

internal data class WidgetRefreshIdentity(
    val locked: Boolean,
    val chapterIds: Set<Long>,
)

internal fun WidgetPrivacyData.refreshIdentity() = when (this) {
    WidgetPrivacyData.Locked -> WidgetRefreshIdentity(locked = true, chapterIds = emptySet())
    is WidgetPrivacyData.Content -> WidgetRefreshIdentity(
        locked = false,
        chapterIds = updates.mapTo(mutableSetOf()) { it.chapterId },
    )
}

internal class WidgetPrivacyDataSource(
    private val getUpdates: GetUpdates,
    private val lockState: Flow<Boolean>,
) {
    constructor(getUpdates: GetUpdates, preferences: SecurityPreferences) :
        this(getUpdates, preferences.useAuthenticator().changes())

    @OptIn(ExperimentalCoroutinesApi::class)
    fun subscribe(afterMillis: Long): Flow<WidgetPrivacyData> = lockState
        .distinctUntilChanged()
        .flatMapLatest { locked ->
            if (locked) {
                flowOf(WidgetPrivacyData.Locked)
            } else {
                getUpdates.subscribe(read = false, after = afterMillis)
                    .map(WidgetPrivacyData::Content)
            }
        }
}

internal class WidgetPrivacyConsumer(private val dataSource: WidgetPrivacyDataSource) {
    fun subscribe(afterMillis: Long) = dataSource.subscribe(afterMillis)

    fun refreshes(afterMillis: Long): Flow<WidgetRefreshIdentity> = subscribe(afterMillis)
        .map(WidgetPrivacyData::refreshIdentity)
        .distinctUntilChanged()
}
