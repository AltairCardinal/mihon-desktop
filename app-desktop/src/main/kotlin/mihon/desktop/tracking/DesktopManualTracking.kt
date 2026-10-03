package mihon.desktop.tracking

import eu.kanade.domain.track.model.AutoTrackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mihon.desktop.settings.DesktopAppPreferences
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.interactor.ReadingProgressTrackSync
import tachiyomi.domain.track.interactor.TrackerSyncRequest
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.service.DelayedTrackerSyncPersistence
import tachiyomi.domain.track.service.EnhancedTrackerManga
import tachiyomi.domain.track.service.EnhancedTrackerService
import tachiyomi.domain.track.service.TrackerProviderPort
import tachiyomi.domain.track.service.TrackerService
import tachiyomi.domain.track.service.TrackerServiceRegistry
import tachiyomi.domain.track.service.manualTrackProgress
import tachiyomi.domain.track.service.readProgressTarget
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

/** One screen owner's manual-command prompts; retries remain in the existing sync queue. */
class DesktopManualTracking(
    private val preferences: DesktopAppPreferences,
    private val registry: TrackerServiceRegistry,
    private val getManga: GetManga,
    private val getTracks: GetTracks,
    private val insertTrack: InsertTrack,
    private val sync: ReadingProgressTrackSync,
    private val pendingUpdates: DelayedTrackerSyncPersistence? = null,
) {
    val prompts = MutableStateFlow<List<ManualTrackingPrompt>>(emptyList())
    val feedback = MutableStateFlow<String?>(null)
    private val operations = Mutex()

    fun bindingCount(manga: Manga): Flow<Int> {
        val profiles = if (registry.services.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(
                registry.services.map { it.profile },
            ) { it.toList() }
        }
        return combine(getTracks.subscribe(manga.id), profiles) { tracks, active ->
            val serviceIds = active.filter { it.loggedIn && it.unavailableReason == null }.map { it.id }.toSet()
            tracks.count { it.trackerId in serviceIds && registry.get(it.trackerId)?.supports(manga) == true }
        }
    }

    suspend fun afterRead(mangaId: Long, chapters: List<Chapter>) = operations.withLock {
        if (chapters.none { it.chapterNumber.isFinite() && it.chapterNumber >= 0.0 }) return@withLock
        val policy = preferences.autoUpdateTrackOnMarkRead.get()
        if (policy == AutoTrackState.NEVER) return@withLock
        try {
            val manga = getManga.awaitOrThrow(mangaId) ?: return@withLock
            val eligible = eligibleTracks(manga)
            val accepted = mutableListOf<Track>()
            val refreshFailed = mutableListOf<Track>()
            eligible.forEach { track ->
                try {
                    val port = registry.get(track.trackerId) as? TrackerProviderPort
                        ?: error("Tracker refresh is unavailable")
                    val refreshed = port.refresh(track)
                    if (registry.get(track.trackerId)?.supports(manga) == true &&
                        registry.get(track.trackerId)?.profile?.value?.loggedIn == true &&
                        insertTrack.awaitIfMatches(track, refreshed)
                    ) {
                        accepted += refreshed
                    }
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (_: Exception) {
                    refreshFailed += track
                    feedback.value = MR.strings.desktop_tracking_update_failed.localized()
                }
            }
            // SQL REPLACE may change the row ID. Freeze the actual persisted identities.
            val refreshed = eligibleTracks(manga).filter { row ->
                accepted.any {
                    it.trackerId == row.trackerId && it.remoteId == row.remoteId && it.libraryId == row.libraryId
                } || refreshFailed.any {
                    it.id == row.id && it.trackerId == row.trackerId &&
                        it.remoteId == row.remoteId && it.libraryId == row.libraryId
                }
            }
            val progress = manualTrackProgress(chapters, refreshed) ?: return@withLock
            val prompt = ManualTrackingPrompt(UUID.randomUUID().toString(), manga, progress, refreshed)
            if (policy == AutoTrackState.ALWAYS) {
                execute(prompt)
            } else {
                prompts.value = prompts.value.filterNot { it.manga.id == mangaId } + prompt
            }
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: Exception) {
            feedback.value = MR.strings.desktop_tracking_update_failed.localized()
        }
    }

    suspend fun confirm(prompt: ManualTrackingPrompt): Boolean = operations.withLock {
        if (prompt !in prompts.value) return@withLock false
        try {
            execute(prompt)
            cancel(prompt)
            true
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: Exception) {
            feedback.value = MR.strings.desktop_tracking_update_failed.localized()
            false
        }
    }

    suspend fun afterAdded(manga: Manga) = operations.withLock {
        try {
            val existing = getTracks.awaitOrThrow(manga.id).map { it.trackerId }.toSet()
            registry.services.filterIsInstance<EnhancedTrackerService>().filter {
                it.profile.value.loggedIn && it.profile.value.id !in existing && it.supports(manga)
            }.forEach { service ->
                try {
                    val matched = tachiyomi.domain.track.service.EnhancedTrackerWorkflow().bindIfMatched(
                        service,
                        EnhancedTrackerManga(manga.id, manga.source, manga.url, manga.title),
                    )
                    if (matched != null) {
                        insertTrack.awaitOrThrow(matched)
                        feedback.value = MR.strings.desktop_tracking_bound.localized()
                    } else {
                        feedback.value = MR.strings.error_no_match.localized()
                    }
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (_: Exception) {
                    feedback.value = MR.strings.desktop_tracking_bind_failed.localized()
                }
            }
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: Exception) {
            feedback.value = MR.strings.desktop_tracking_bind_failed.localized()
        }
    }

    fun consumeFeedback() {
        feedback.value = null
    }

    fun cancel(prompt: ManualTrackingPrompt) {
        prompts.value = prompts.value.filterNot { it.eventId == prompt.eventId }
    }

    private suspend fun execute(prompt: ManualTrackingPrompt) {
        val current = eligibleTracks(prompt.manga)
        val applicable = prompt.tracks.filter { original ->
            current.any { row ->
                row.id == original.id && row.trackerId == original.trackerId && row.remoteId == original.remoteId &&
                    row.libraryId == original.libraryId && prompt.progress > row.lastChapterRead
            }
        }
        applicable.forEach { track ->
            sync.sync(
                TrackerSyncRequest(
                    prompt.eventId,
                    prompt.manga.id,
                    prompt.progress,
                    track.trackerId,
                    expectedBinding = track,
                ),
            )
        }
        if (applicable.isNotEmpty()) {
            val saved = getTracks.awaitOrThrow(prompt.manga.id)
            val completed = applicable.mapNotNull { old ->
                saved.firstOrNull {
                    it.trackerId == old.trackerId && it.remoteId == old.remoteId && it.libraryId == old.libraryId
                }
            }
            val allSaved = completed.size == applicable.size && completed.all { row ->
                row.lastChapterRead >= readProgressTarget(row, prompt.progress)
            }
            val queued = pendingUpdates?.getItems().orEmpty().any { pending ->
                pending.mangaId == prompt.manga.id && applicable.any {
                    it.id == pending.trackId && it.trackerId == pending.trackerId
                }
            }
            feedback.value = when {
                allSaved -> MR.strings.trackers_updated_summary.localized(
                    java.util.Locale.getDefault(),
                    completed.minOf { it.lastChapterRead }.toInt(),
                )
                queued -> MR.strings.desktop_tracking_updates_pending.localized()
                else -> MR.strings.desktop_tracking_update_failed.localized()
            }
        }
    }

    private suspend fun eligibleTracks(manga: Manga) = getTracks.awaitOrThrow(manga.id).filter { track ->
        registry.get(track.trackerId)?.let { service ->
            service.profile.value.loggedIn && service.profile.value.unavailableReason == null && service.supports(manga)
        } == true
    }

    companion object {
        fun fromInjekt() = DesktopManualTracking(
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
            Injekt.get<DesktopTrackerSyncScheduler>(),
        )
    }
}

private fun TrackerService.supports(manga: Manga): Boolean = this !is EnhancedTrackerService || accept(
    EnhancedTrackerManga(manga.id, manga.source, manga.url, manga.title),
)

data class ManualTrackingPrompt(val eventId: String, val manga: Manga, val progress: Double, val tracks: List<Track>)
