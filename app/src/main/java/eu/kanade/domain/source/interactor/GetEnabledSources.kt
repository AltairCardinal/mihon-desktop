package eu.kanade.domain.source.interactor

import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.domain.source.service.FixedMainSourceMembershipProjection
import tachiyomi.domain.source.service.SourceMembershipCandidate
import tachiyomi.domain.source.service.SourceMembershipPreferences
import tachiyomi.domain.source.service.SourceMembershipProjection
import tachiyomi.source.local.isLocal

class GetEnabledSources(
    private val repository: SourceRepository,
    private val preferences: SourcePreferences,
    private val membershipProjection: SourceMembershipProjection = FixedMainSourceMembershipProjection,
) {

    fun subscribe(): Flow<List<Source>> {
        return combine(
            preferences.pinnedSources().changes(),
            preferences.enabledLanguages().changes(),
            preferences.disabledSources().changes(),
            preferences.lastUsedSource().changes(),
            repository.getSources(),
        ) { pinnedSourceIds, enabledLanguages, disabledSources, lastUsedSource, sources ->
            membershipProjection.project(
                candidates = sources.map { SourceMembershipCandidate(it, isLocal = it.isLocal()) },
                preferences = SourceMembershipPreferences(
                    enabledLanguages = enabledLanguages,
                    disabledSourceIds = disabledSources,
                    pinnedSourceIds = pinnedSourceIds,
                    lastUsedSourceId = lastUsedSource,
                ),
            )
        }
            .distinctUntilChanged()
    }
}
