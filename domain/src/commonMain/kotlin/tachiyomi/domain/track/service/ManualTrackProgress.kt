package tachiyomi.domain.track.service

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.track.model.Track

/** Manual commands advance progress; they never lower an existing remote value. */
fun manualTrackProgress(chapters: List<Chapter>, tracks: List<Track>): Double? {
    val highest =
        chapters.asSequence().map { it.chapterNumber }.filter { it.isFinite() && it >= 0.0 }.maxOrNull() ?: return null
    return highest.takeIf { target -> tracks.any { readProgressTarget(it, target) > it.lastChapterRead } }
}

/** Completion updates use fresh provider bounds and never lower remote progress. */
fun readProgressTarget(track: Track, requested: Double): Double = maxOf(
    track.lastChapterRead,
    if (track.totalChapters > 0) requested.coerceAtMost(track.totalChapters.toDouble()) else requested,
)
