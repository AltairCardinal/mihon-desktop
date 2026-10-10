package tachiyomi.domain.reader.interactor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.reader.model.ReaderChapterIdentity
import tachiyomi.domain.reader.model.ReaderOpenContext

fun interface ReaderCatalogPreparation {
    suspend fun prepare(target: ReaderChapterIdentity): List<Chapter>?
}

/** One attempt belongs to the initially opened Reader, never to a recomposition or chapter transition. */
class ReaderCatalogCompletion(
    private val opened: ReaderOpenContext,
    private val preparation: ReaderCatalogPreparation,
) {
    private val mutex = Mutex()
    private var attempted = false
    suspend fun await(): List<Chapter>? {
        if (!opened.resumedWithinChapter || opened.snapshot.scope == null) return null
        if (!mutex.withLock {
                if (attempted) {
                    false
                } else {
                    attempted = true
                    true
                }
            }
        ) {
            return null
        }
        val target = ReaderChapterIdentity(
            opened.manga.id,
            opened.manga.source,
            opened.manga.url,
            opened.chapter.id,
            opened.chapter.url,
        )
        return try {
            withTimeoutOrNull(30_000) { preparation.prepare(target) }
        } catch (
            cancelled: CancellationException,
        ) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}
