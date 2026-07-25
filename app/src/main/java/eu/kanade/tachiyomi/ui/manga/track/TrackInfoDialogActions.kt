package eu.kanade.tachiyomi.ui.manga.track

import android.app.Application
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackerProviderError
import tachiyomi.domain.track.service.TrackerProviderRequest
import tachiyomi.domain.track.service.TrackerProviderResult
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal class TrackInfoDialogActions(
    private val execute: suspend (TrackerProviderRequest) -> TrackerProviderResult = {
        Injekt.get<TrackerManager>().execute(it)
    },
    private val feedback: suspend (TrackerProviderError) -> Unit = {
        withUIContext { Injekt.get<Application>().toast(it.message ?: it.kind.name) }
    },
) {
    suspend fun setPrivate(track: Track, value: Boolean) = edit(track, TrackEdit(private = value))
    suspend fun setStatus(track: Track, value: Long) = edit(track, TrackEdit(status = value))
    suspend fun setChapter(track: Track, value: Double) =
        edit(track, TrackEdit(lastChapterRead = value, didReadChapter = false))
    suspend fun setScore(track: Track, value: Double) = edit(track, TrackEdit(score = value))
    suspend fun setStartDate(track: Track, value: Long) = edit(track, TrackEdit(startDate = value))
    suspend fun setFinishDate(track: Track, value: Long) = edit(track, TrackEdit(finishDate = value))
    suspend fun removeDate(track: Track, start: Boolean) =
        edit(track, if (start) TrackEdit(startDate = 0) else TrackEdit(finishDate = 0))
    suspend fun delete(track: Track) = submit(TrackerProviderRequest.Delete(track))

    private suspend fun edit(track: Track, edit: TrackEdit) = submit(TrackerProviderRequest.Edit(track, edit))

    private suspend fun submit(request: TrackerProviderRequest): TrackerProviderResult {
        val result = execute(request)
        if (result is TrackerProviderResult.Failure) feedback(result.error)
        return result
    }
}

internal val trackInfoDialogActions = TrackInfoDialogActions()
