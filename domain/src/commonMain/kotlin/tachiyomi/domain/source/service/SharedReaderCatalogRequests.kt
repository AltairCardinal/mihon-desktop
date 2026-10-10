package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/** Opt-in sharing for Reader completion and the existing Android detail update. */
internal object SharedReaderCatalogRequests {
    private data class Key(val source: Source, val sourceId: Long, val mangaUrl: String)
    private class Flight(val fetchDetails: Boolean) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var catalog: Deferred<Result<SMangaUpdate>>
        var details: Deferred<Result<SMangaUpdate>>? = null
        var waiters = 0
    }
    private val flights = mutableMapOf<Key, Flight>()

    suspend fun await(source: Source, manga: Manga, chapters: List<Chapter>, fetchDetails: Boolean): SMangaUpdate {
        val key = Key(source, manga.source, manga.url)
        val flight = synchronized(flights) {
            flights.getOrPut(key) {
                Flight(fetchDetails).also { next ->
                    next.catalog = next.scope.async(start = CoroutineStart.LAZY) {
                        runCatching {
                            SourceMangaUpdateService().await(
                                source,
                                manga,
                                chapters,
                                fetchDetails,
                                fetchChapters = true,
                            )
                        }
                    }
                }
            }.also { it.waiters++ }
        }
        flight.catalog.start()
        try {
            // Preserve the source exception itself across Deferred stack-trace recovery.
            val catalog = flight.catalog.await().getOrThrow()
            if (!fetchDetails || flight.fetchDetails) return catalog
            val details = synchronized(flights) {
                flight.details ?: flight.scope.async(start = CoroutineStart.LAZY) {
                    runCatching {
                        source.getMangaUpdate(
                            catalog.manga,
                            catalog.chapters,
                            fetchDetails = true,
                            fetchChapters = false,
                        )
                    }
                }.also { flight.details = it }
            }
            details.start()
            return SMangaUpdate(details.await().getOrThrow().manga, catalog.chapters)
        } finally {
            val lastWaiter = synchronized(flights) {
                flight.waiters--
                if (flight.waiters == 0) {
                    if (flights[key] === flight) flights.remove(key)
                    true
                } else {
                    false
                }
            }
            if (lastWaiter) flight.scope.cancel()
        }
    }
}
