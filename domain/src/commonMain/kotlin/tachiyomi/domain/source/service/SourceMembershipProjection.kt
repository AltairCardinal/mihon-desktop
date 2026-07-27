package tachiyomi.domain.source.service

import tachiyomi.domain.source.model.Pin
import tachiyomi.domain.source.model.Pins
import tachiyomi.domain.source.model.Source

/**
 * An installed source together with platform knowledge that cannot live in the domain source model.
 *
 * The candidate list is the extension membership boundary: sources absent from it are never projected.
 */
data class SourceMembershipCandidate(
    val source: Source,
    val isLocal: Boolean = false,
)

data class SourceMembershipPreferences(
    val enabledLanguages: Set<String>,
    val disabledSourceIds: Set<String>,
    val pinnedSourceIds: Set<String>,
    val lastUsedSourceId: Long,
)

fun interface SourceMembershipProjection {
    fun project(
        candidates: List<SourceMembershipCandidate>,
        preferences: SourceMembershipPreferences,
    ): List<Source>
}

/**
 * Shared fixed-main source membership semantics used by Android and Desktop production callers.
 */
object FixedMainSourceMembershipProjection : SourceMembershipProjection {
    override fun project(
        candidates: List<SourceMembershipCandidate>,
        preferences: SourceMembershipPreferences,
    ): List<Source> {
        return candidates
            .filter { it.isLocal || it.source.lang in preferences.enabledLanguages }
            .filterNot { it.source.id.toString() in preferences.disabledSourceIds }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.source.name })
            .flatMap { candidate ->
                val pin = if (candidate.source.id.toString() in preferences.pinnedSourceIds) {
                    Pins.pinned
                } else {
                    Pins.unpinned
                }
                val source = candidate.source.copy(pin = pin)
                buildList {
                    add(source)
                    if (source.id == preferences.lastUsedSourceId) {
                        add(source.copy(isUsedLast = true, pin = source.pin - Pin.Actual))
                    }
                }
            }
    }
}
