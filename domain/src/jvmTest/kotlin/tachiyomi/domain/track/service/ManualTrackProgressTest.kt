package tachiyomi.domain.track.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.track.model.Track

class ManualTrackProgressTest {
    @Test
    fun `known service cap already reached never asks while unknown total may advance`() {
        val chapter = Chapter.create().copy(chapterNumber = 50.0)
        val track = Track(1, 1, 2, 11, 44, "Title", 10.0, 10, 1, 80.0, "url", 0, 0, false)
        assertNull(manualTrackProgress(listOf(chapter), listOf(track)))
        assertEquals(50.0, manualTrackProgress(listOf(chapter), listOf(track.copy(totalChapters = 0))))
    }

    @Test
    fun `highest valid chapter excludes unknown and non finite numbers and only moves forward`() {
        val chapters = listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 2.5, 9.0).map {
            Chapter.create().copy(chapterNumber = it)
        }
        val track = Track(1, 1, 2, 11, 44, "Title", 1.0, 100, 1, 80.0, "url", 0, 0, false)
        assertEquals(9.0, manualTrackProgress(chapters, listOf(track)))
        assertNull(manualTrackProgress(chapters, listOf(track.copy(lastChapterRead = 10.0))))
        assertNull(manualTrackProgress(emptyList(), listOf(track)))
        assertNull(manualTrackProgress(chapters, emptyList()))
        assertNull(manualTrackProgress(listOf(Chapter.create().copy(chapterNumber = -1.0)), listOf(track)))
    }
}
