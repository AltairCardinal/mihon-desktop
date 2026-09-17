package tachiyomi.data.creator

import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.creator.model.ArchiveWatchPolicy
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.service.BoundedAuthorSearchPageRequest
import tachiyomi.domain.creator.service.CreatorCheckFrequency
import tachiyomi.domain.creator.service.CreatorDiscoveryPreferences
import tachiyomi.domain.creator.service.CreatorDiscoverySchedule
import tachiyomi.domain.creator.service.CreatorDiscoveryService
import tachiyomi.domain.creator.service.CreatorDiscoverySourcePort
import tachiyomi.domain.creator.service.CreatorSourceCapability
import tachiyomi.domain.creator.service.CreatorSourceDetailsResult
import tachiyomi.domain.creator.service.CreatorSourceFailure
import tachiyomi.domain.creator.service.CreatorSourcePageResult
import tachiyomi.domain.creator.service.EnabledCreatorSource
import java.time.Instant
import java.time.ZoneId

/** Both platforms execute their real background owner against the same SQL/service contract. */
suspend fun verifyCreatorGlobalSchedule(
    handler: DatabaseHandler,
    store: PreferenceStore,
    bind: (CreatorRepositoryImpl, CreatorDiscoveryService) -> (suspend () -> Unit),
) {
    var now = Instant.parse("2025-01-31T12:00:00Z").toEpochMilli()
    val preferences = CreatorDiscoveryPreferences(store)
    val schedule = CreatorDiscoverySchedule(preferences::current, zone = { ZoneId.of("UTC") })
    val repository = CreatorRepositoryImpl(handler, clock = { now }, discoverySchedule = schedule)
    val creator = repository.upsertCreator("Calendar author")
    repository.upsertWatchPolicy(
        ArchiveWatchPolicy(
            creator.id,
            true,
            60_000,
            setOf(10, 20),
            emptySet(),
        ),
        now,
    )
    val searches = java.util.concurrent.atomic.AtomicInteger()
    var failing = false
    var beforeSearch: suspend () -> Unit = {}
    val port = object : CreatorDiscoverySourcePort {
        override suspend fun enabledSourcesSnapshot() = listOf(10L, 20L).map {
            EnabledCreatorSource(it, "Source $it", setOf(CreatorSourceCapability.CATALOGUE_SEARCH_FALLBACK))
        }
        override suspend fun searchPage(request: BoundedAuthorSearchPageRequest): CreatorSourcePageResult {
            searches.incrementAndGet()
            beforeSearch()
            return if (failing && request.sourceId == 20L) {
                CreatorSourcePageResult.Failure(CreatorSourceFailure.Http(500))
            } else {
                CreatorSourcePageResult.Empty
            }
        }
        override suspend fun loadDetails(key: SourceWorkNaturalKey): CreatorSourceDetailsResult = error("empty source")
    }
    val service = CreatorDiscoveryService(repository, repository, port, clock = { now }, schedule = schedule)
    val wake = bind(repository, service)
    check(repository.getDueWatchSources(now, 20).size == 2) { "first follow must be due immediately" }
    wake()
    check(searches.get() == 2) { "platform background owner did not execute the real service" }
    check(repository.getDueWatchSources(now + 60_000, 20).isEmpty()) { "legacy author period leaked into due path" }
    preferences.frequency().set("weekly")
    now += 86_400_000
    check(repository.getDueWatchSources(now, 20).isEmpty()) { "global weekly setting failed to replan success" }
    now += 6 * 86_400_000L
    failing = true
    wake()
    check(searches.get() == 4)
    val retry = repository.getSourceCheckpoints(creator.id).single { it.sourceId == 20L }.backoffUntil!!
    preferences.frequency().set("monthly")
    check(repository.getDueWatchSources(retry - 1, 20).isEmpty()) { "setting bypassed retry backoff" }
    check(repository.getDueWatchSources(retry, 20).map { it.sourceId } == listOf(20L)) {
        "setting delayed a failed source using an older success"
    }
    now = retry
    failing = false
    wake()
    check(searches.get() == 5)
    check(repository.getSourceCheckpoints(creator.id).all { it.consecutiveFailures == 0L })
    val late = Instant.parse("2025-03-10T12:00:00Z").toEpochMilli()
    now = late
    beforeSearch = { repository.unfollowCreator(creator.id) }
    wake()
    check(repository.getDueWatchSources(now + 366 * 86_400_000L, 20).isEmpty())
    check(repository.getPendingNotificationOutbox(now, 20).isEmpty())
    val stopped = searches.get()
    wake()
    check(searches.get() == stopped) { "unfollow created another periodic request" }
    check(preferences.current() == CreatorCheckFrequency.MONTHLY)
}
