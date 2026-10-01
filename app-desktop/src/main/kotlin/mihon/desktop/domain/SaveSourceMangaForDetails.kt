package mihon.desktop.domain

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mihon.desktop.extension.SourceCallResult
import mihon.desktop.extension.safeSourceCall
import mihon.domain.error.AppError
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.creator.model.ChapterCatalogCompleteness
import tachiyomi.domain.creator.model.SourceDateExtensionIdentity
import tachiyomi.domain.creator.model.SourceDateField
import tachiyomi.domain.creator.model.SourceDateObservation
import tachiyomi.domain.creator.model.SourceDatePrecision
import tachiyomi.domain.creator.model.SourceDateQualityIdentity
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.service.CreatorSourceWorkKey
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceMangaUpdateService
import tachiyomi.domain.source.service.toSourceManga
import java.util.concurrent.atomic.AtomicBoolean

private const val MAX_REFRESH_STATES = 128

/**
 * Persists a browsed source manga so Browse and Library can share MangaDetailScreen.
 */
class SaveSourceMangaForDetails(
    private val networkToLocalManga: NetworkToLocalManga,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val creatorArchiveRepository: CreatorArchiveRepository? = null,
    private val refreshScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val sourceDateExtensionIdentityProvider: (Long) -> SourceDateExtensionIdentity = {
        SourceDateExtensionIdentity("unknown.extension", "unknown")
    },
    private val catalogWriter: SourceChapterCatalogWriter = SourceChapterCatalogWriter(
        chapterRepository,
        creatorArchiveRepository,
        extensionIdentity = sourceDateExtensionIdentityProvider,
    ),
) {

    private val mutableRefreshStates = MutableStateFlow<Map<SourceMangaRefreshKey, SourceMangaRefreshState>>(emptyMap())
    val refreshStates: StateFlow<Map<SourceMangaRefreshKey, SourceMangaRefreshState>> = mutableRefreshStates.asStateFlow()
    private class RefreshFlight(fetchDetails: Boolean, val expectedManga: Manga?) {
        val wantsDetails = AtomicBoolean(fetchDetails)
        val detailsMutex = Mutex()
        lateinit var execution: Deferred<SourceCallResult<PreparedChapterCatalog>>
        var networkDetails: SManga? = null
        var detailsPersisted = false
    }
    private val inFlight = mutableMapOf<SourceMangaRefreshKey, RefreshFlight>()

    suspend fun awaitSearchResults(results: List<SManga>, sourceId: Long): List<Manga> =
        results.map { it.toDomainManga(sourceId) }
            .distinctBy(Manga::url)
            .let { networkToLocalManga(it) }

    fun refreshFromSource(
        source: Source,
        listedManga: SManga,
    ): Job {
        val execution = refresh(source, listedManga, fetchDetails = true)
        return refreshScope.launch { execution.await() }
    }

    private fun refresh(source: Source, listed: SManga, fetchDetails: Boolean, expectedManga: Manga? = null): Deferred<SourceCallResult<PreparedChapterCatalog>> {
        val key = SourceMangaRefreshKey(source.id, listed.url)
        val flight = synchronized(inFlight) {
            inFlight[key]?.let { existing ->
                if (fetchDetails) existing.wantsDetails.set(true)
                return@synchronized existing
            }
            val next = RefreshFlight(fetchDetails, expectedManga)
            updateRefreshState(key, SourceMangaRefreshState.Loading)
            val execution = refreshScope.async(start = CoroutineStart.LAZY) {
                try {
                    val call = safeSourceCall { fetchFromSource(source, listed, next) }
                    val result = if (call is SourceCallResult.Error && call.error.cause is SourceCatalogStorageException) {
                        SourceCallResult.Error(AppError.Storage(call.error.cause?.cause))
                    } else {
                        call
                    }
                    result.also {
                        when (result) {
                            is SourceCallResult.Success -> updateRefreshState(key, null)
                            is SourceCallResult.Error -> updateRefreshState(key, SourceMangaRefreshState.Failure(result.error))
                            is SourceCallResult.Timeout -> updateRefreshState(key, SourceMangaRefreshState.Failure(result.error))
                        }
                    }
                } finally {
                    synchronized(inFlight) { inFlight.remove(key) }
                }
            }
            next.execution = execution
            inFlight[key] = next
            execution.start()
            next
        }
        if (!fetchDetails) return flight.execution
        return refreshScope.async {
            when (val result = flight.execution.await()) {
                is SourceCallResult.Success -> flight.detailsMutex.withLock {
                    if (flight.detailsPersisted) {
                        result
                    } else {
                        try {
                            val manga = catalogWriter.transaction {
                                persistManga(requireNotNull(flight.networkDetails), source.id, true, result.value.manga)
                            }
                            flight.detailsPersisted = true
                            SourceCallResult.Success(result.value.copy(manga = manga))
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            SourceCallResult.Error(AppError.Storage(error)).also {
                                updateRefreshState(key, SourceMangaRefreshState.Failure(it.error))
                            }
                        }
                    }
                }
                is SourceCallResult.Error -> result
                is SourceCallResult.Timeout -> result
            }
        }
    }

    suspend fun awaitPrepared(source: Source?, manga: Manga): SourceCallResult<PreparedChapterCatalog> {
        val local = try {
            val current = mangaRepository.getMangaById(manga.id)
            check(current.source == manga.source && current.url == manga.url) { "Source manga identity conflict" }
            SourceCallResult.Success(
                if (current.source != 0L && catalogWriter.needsRefresh(current)) {
                    null
                } else {
                    PreparedChapterCatalog(current, chapterRepository.getChapterByMangaId(current.id).sortedBy { it.sourceOrder })
                },
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            SourceCallResult.Error(AppError.Storage(error))
        }
        return when (local) {
            is SourceCallResult.Success -> local.value?.let { SourceCallResult.Success(it) }
                ?: if (source == null) {
                    SourceCallResult.Error(AppError.Unknown(SourceCatalogUnavailableException()))
                } else {
                    check(source.id == manga.source) { "Source manga identity conflict" }
                    refresh(source, manga.toSourceManga(), fetchDetails = false, expectedManga = manga).await()
                }
            is SourceCallResult.Error -> local
            is SourceCallResult.Timeout -> local
        }
    }

    /** Existing detail entry: keep the fixed local identity and publish preparation errors to its refresh UI. */
    suspend fun prepareForDetails(manga: Manga): SourceCallResult<ListedMangaForDetails> {
        return try {
            val current = mangaRepository.getMangaById(manga.id)
            check(current.source == manga.source && current.url == manga.url) { "Source manga identity conflict" }
            SourceCallResult.Success(listedForDetails(current))
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            SourceCallResult.Error(AppError.Storage(error)).also {
                updateRefreshState(SourceMangaRefreshKey(manga.source, manga.url), SourceMangaRefreshState.Failure(it.error))
            }
        }
    }

    private fun updateRefreshState(key: SourceMangaRefreshKey, state: SourceMangaRefreshState?) {
        mutableRefreshStates.update { current ->
            LinkedHashMap(current).apply {
                remove(key)
                if (state != null) put(key, state)
                while (size > MAX_REFRESH_STATES) remove(keys.first())
            }
        }
    }

    suspend fun awaitFromSource(
        source: Source,
        listedManga: SManga,
    ): Manga {
        return when (val result = refresh(source, listedManga, true).await()) {
            is SourceCallResult.Success -> result.value.manga
            is SourceCallResult.Error -> throw (result.error.cause ?: IllegalStateException(result.error.toString()))
            is SourceCallResult.Timeout -> throw (result.error.cause ?: java.net.SocketTimeoutException())
        }
    }

    private suspend fun fetchFromSource(source: Source, listedManga: SManga, flight: RefreshFlight): PreparedChapterCatalog {
        val manga = catalogStorageCall {
            flight.expectedManga ?: mangaRepository.getMangaByUrlAndSourceId(listedManga.url, source.id)
                ?: listedManga.toDomainManga(source.id)
        }
        val known = catalogStorageCall { chapterRepository.getChapterByMangaId(manga.id) }
        val update = SourceMangaUpdateService().await(
            source,
            manga,
            known,
            fetchDetails = true,
            fetchChapters = true,
        )
        val details = mergeSourceMangaDetails(
            original = listedManga,
            details = update.manga,
        )
        check(details.url == listedManga.url) { "Source manga identity conflict" }
        val validated = catalogWriter.validate(update.chapters)
        flight.networkDetails = details
        val saveDetails = flight.wantsDetails.get()
        val saved = catalogStorageCall {
            await(details, source.id, validated, saveDetails, expectedManga = manga.takeIf { it.id > 0 })
        }
        flight.detailsPersisted = saveDetails
        val urls = validated.map { it.url }.toSet()
        return catalogStorageCall {
            PreparedChapterCatalog(saved, chapterRepository.getChapterByMangaId(saved.id).filter { it.url in urls }.sortedBy { it.sourceOrder })
        }
    }

    private suspend fun <T> catalogStorageCall(block: suspend () -> T): T = try {
        block()
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        throw SourceCatalogStorageException(error)
    }

    suspend fun awaitLinkedChapter(
        source: Source,
        listedManga: SManga,
        linkedChapter: SChapter?,
    ): ResolvedSourceChapter {
        val manga = awaitSearchResults(listOf(listedManga), source.id).single()
        val existing = linkedChapter?.let { chapterRepository.getChapterByUrlAndMangaId(it.url, manga.id) }
        if (linkedChapter == null || existing != null) return ResolvedSourceChapter(manga, existing)
        val update = SourceMangaUpdateService().await(
            source,
            manga,
            chapterRepository.getChapterByMangaId(manga.id),
            fetchDetails = false,
            fetchChapters = true,
        )
        val updatedManga = await(update.manga, source.id, update.chapters, fetchDetails = false)
        val chapter = chapterRepository.getChapterByUrlAndMangaId(linkedChapter.url, manga.id)
        return ResolvedSourceChapter(updatedManga, chapter)
    }

    suspend fun awaitListed(
        sManga: SManga,
        sourceId: Long,
    ): Manga {
        mangaRepository.getMangaByUrlAndSourceId(sManga.url, sourceId)?.let { return it }

        val networkManga = Manga.create().copy(
            url = sManga.url,
            title = sManga.title,
            source = sourceId,
            thumbnailUrl = sManga.thumbnail_url,
            initialized = false,
            memo = sManga.memo,
        )

        return networkToLocalManga(networkManga)
    }

    suspend fun awaitListedForDetails(
        sManga: SManga,
        sourceId: Long,
    ): ListedMangaForDetails {
        val manga = awaitListed(sManga, sourceId)
        return when (val prepared = prepareForDetails(manga)) {
            is SourceCallResult.Success -> prepared.value
            is SourceCallResult.Error -> ListedMangaForDetails(manga, needsRefresh = false, preparationError = prepared.error)
            is SourceCallResult.Timeout -> ListedMangaForDetails(manga, needsRefresh = false, preparationError = prepared.error)
        }
    }

    private suspend fun listedForDetails(manga: Manga): ListedMangaForDetails = ListedMangaForDetails(
        manga = manga,
        needsRefresh = !manga.initialized || catalogWriter.needsRefresh(manga),
    )

    suspend fun await(
        sManga: SManga,
        sourceId: Long,
        sChapters: List<SChapter>,
        fetchDetails: Boolean = true,
        expectedManga: Manga? = null,
    ): Manga {
        // Generic callers such as migration may save metadata without receiving a directory.
        // Source refresh callers validate their network response before reaching this method.
        if (sChapters.isNotEmpty()) catalogWriter.validate(sChapters)
        return catalogWriter.transaction {
            val dbManga = persistManga(sManga, sourceId, fetchDetails, expectedManga)
            if (sChapters.isNotEmpty()) catalogWriter.merge(dbManga, sChapters)

            dbManga
        }
    }

    /** Called only inside the catalogue transaction; directory-only visits retain download identity. */
    private suspend fun persistManga(sManga: SManga, sourceId: Long, fetchDetails: Boolean, expectedManga: Manga?): Manga {
        expectedManga?.let { expected ->
            val current = mangaRepository.getMangaById(expected.id)
            check(current.source == expected.source && current.url == expected.url && sourceId == expected.source && sManga.url == expected.url) { "Source manga identity conflict" }
        }
        val existing = mangaRepository.getMangaByUrlAndSourceId(sManga.url, sourceId)
        catalogWriter.validateWorkIdentity(existing ?: Manga.create().copy(source = sourceId, url = sManga.url, title = sManga.title, author = sManga.author, artist = sManga.artist))
        if (!fetchDetails) return requireNotNull(existing)
        val networkManga = Manga.create().copy(
            url = sManga.url,
            title = sManga.title,
            source = sourceId,
            thumbnailUrl = sManga.thumbnail_url,
            author = sManga.author,
            artist = sManga.artist,
            description = sManga.description,
            genre = sManga.genre?.split(", ")?.takeIf { it.isNotEmpty() },
            status = sManga.status.toLong(),
            initialized = true,
            memo = sManga.memo,
        )

        val storedManga = networkToLocalManga(networkManga)
        check(mangaRepository.update(MangaUpdate(storedManga.id, memo = sManga.memo, initialized = true.takeIf { fetchDetails })))
        val dbManga = mangaRepository.getMangaById(storedManga.id)
        return dbManga
    }
}

data class PreparedChapterCatalog(val manga: Manga, val chapters: List<Chapter>)
class SourceCatalogUnavailableException : IllegalStateException("Source unavailable")
private class SourceCatalogStorageException(cause: Throwable) : IllegalStateException("Source catalogue storage failed", cause)

data class SourceMangaRefreshKey(
    val sourceId: Long,
    val mangaUrl: String,
)

sealed interface SourceMangaRefreshState {
    data object Loading : SourceMangaRefreshState
    data class Failure(val error: AppError) : SourceMangaRefreshState
}

/** A stored listing remains navigable on preparation failure; error is also published to refreshStates. */
data class ListedMangaForDetails(
    val manga: Manga,
    val needsRefresh: Boolean,
    val preparationError: AppError? = null,
)

data class ResolvedSourceChapter(
    val manga: Manga,
    val chapter: Chapter?,
)

internal fun mergeSourceMangaDetails(original: SManga, details: SManga): SManga = details.also { d ->
    runCatching { d.url }.onFailure { d.url = original.url }
    runCatching { d.title }.onFailure { d.title = original.title }
    if (d.thumbnail_url.isNullOrBlank()) {
        d.thumbnail_url = original.thumbnail_url
    }
}
