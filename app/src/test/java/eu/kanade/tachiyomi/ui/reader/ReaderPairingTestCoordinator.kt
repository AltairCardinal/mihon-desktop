package eu.kanade.tachiyomi.ui.reader

import io.mockk.coEvery
import io.mockk.mockk
import mihon.domain.reader.ChapterPairingRepository
import mihon.domain.reader.ChapterPairingSnapshot

/** Reader tests unrelated to pairing still supply the required production constructor seam. */
internal fun emptyChapterPairingCoordinator(): AndroidChapterPairingCoordinator {
    val repository = mockk<ChapterPairingRepository>()
    coEvery { repository.load(any(), any()) } returns ChapterPairingSnapshot(null, 0)
    return AndroidChapterPairingCoordinator(repository)
}
