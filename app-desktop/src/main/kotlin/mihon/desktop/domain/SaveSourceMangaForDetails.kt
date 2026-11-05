package mihon.desktop.domain

import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.guardSourceOperation
import eu.kanade.tachiyomi.network.inspectSourceFailure
import eu.kanade.tachiyomi.network.rethrowSourceControlFailure
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
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.ChapterDirectoryDownloadConflictException
import tachiyomi.domain.chapter.service.finishDirectoryPhase
import tachiyomi.domain.chapter.service.observeDirectoryPhase
import tachiyomi.domain.creator.model.SourceDateExtensionIdentity
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.service.CreatorSourceWorkKey
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceMangaUpdateService
import tachiyomi.domain.source.service.toSourceAppError
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
    private val initialChapterFlags: () -> Long = { 0L },
    private val finishPendingDirectory: (
        suspend (Manga, tachiyomi.domain.chapter.service.ChapterDirectoryPhase) -> Unit
    )? = null,
    private val downloadPolicy: suspend (Manga) -> mihon.domain.chapter.interactor.DownloadNewChapterPolicy = {
        mihon.domain.chapter.interactor.DownloadNewChapterPolicy(false, false)
    },
    private val disallowNonAsciiFilenames: () -> Boolean = { false },
    private val directoryCommit: suspend (Manga, tachiyomi.domain.chapter.service.ChapterDirectoryCommit) ->
    tachiyomi.domain.chapter.service.ChapterDirectoryResult = { _, request ->
        chapterRepository.syncDirectory(request)
    },
    private val catalogWriter: SourceChapterCatalogWriter = SourceChapterCatalogWriter(
        chapterRepository,
        creatorArchiveRepository,
        extensionIdentity = sourceDateExtensionIdentityProvider,
    ),
) {

    private val mutableRefreshStates = MutableStateFlow<Map<SourceMangaRefreshKey, SourceMangaRefreshState>>(emptyMap())
    val refreshStates: StateFlow<Map<SourceMangaRefreshKey, SourceMangaRefreshState>> = mutableRefreshStates
        .asStateFlow()
    private class RefreshFlight(fetchDetails: Boolean, val expectedManga: Manga?, origin: String) {
        @Volatile var origin: String = origin
        var networkChapters: List<SChapter> = emptyList()
        var networkComplete = false
        val wantsDetails = AtomicBoolean(fetchDetails)
        val detailsMutex = Mutex()
        lateinit var execution: Deferred<SourceCallResult<PreparedChapterCatalog>>
        var networkDetails: SManga? = null
        var detailsPersisted = false
    }
    private val inFlight = mutableMapOf<SourceMangaRefreshKey, RefreshFlight>()

    suspend fun awaitSearchResults(results: List<SManga>, sourceId: Long): List<Manga> =
        results.map {
            val mapped = it.toDomainManga(sourceId, SourceOperationPhase.SEARCH)
            mapped.copy(chapterFlags = initialChapterFlags())
        }
            .distinctBy(Manga::url)
            .let { networkToLocalManga(it) }

    fun refreshFromSource(
        source: Source,
        listedManga: SManga,
        origin: String = "BROWSE",
    ): Job {
        val execution = refresh(source, listedManga, fetchDetails = true, origin = origin)
        return refreshScope.launch { execution.await() }
    }

    private fun refresh(
        source: Source,
        listed: SManga,
        fetchDetails: Boolean,
        expectedManga: Manga? = null,
        origin: String = "BROWSE",
    ): Deferred<SourceCallResult<PreparedChapterCatalog>> {
        val listedUrl = guardSourceOperation(SourceOperationPhase.DETAILS) { listed.url }
        val key = SourceMangaRefreshKey(source.id, listedUrl)
        val flight = synchronized(inFlight) {
            inFlight[key]?.let { existing ->
                if (fetchDetails) {
                    if (origin == "DETAIL_REFRESH") existing.origin = origin
                    existing.wantsDetails.set(true)
                }
                return@synchronized existing
            }
            val next = RefreshFlight(fetchDetails, expectedManga, origin)
            updateRefreshState(key, SourceMangaRefreshState.Loading)
            val execution = refreshScope.async(start = CoroutineStart.LAZY) {
                try {
                    val call = safeSourceCall { fetchFromSource(source, listed, next) }
                    val result = if (call is SourceCallResult.Error && call.error
                            .cause is SourceCatalogStorageException
                    ) {
                        SourceCallResult.Error(AppError.Storage(call.error.cause?.cause))
                    } else {
                        call
                    }
                    result.also {
                        when (result) {
                            is SourceCallResult.Success -> updateRefreshState(key, null)
                            is SourceCallResult.Error -> updateRefreshState(
                                key,
                                SourceMangaRefreshState
                                    .Failure(result.error),
                            )
                            is SourceCallResult.Timeout -> updateRefreshState(
                                key,
                                SourceMangaRefreshState
                                    .Failure(result.error),
                            )
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
                            val manga = await(
                                requireNotNull(flight.networkDetails), source.id, flight.networkChapters, true,
                                complete = flight.networkComplete, sourceName = source.toString(),
                                origin =
                                flight.origin,
                                expectedManga = result.value.manga,
                                prepareChapter = { chapter ->
                                    if (source is eu.kanade.tachiyomi.source.online.HttpSource) {
                                        source
                                            .prepareNewChapter(chapter, requireNotNull(flight.networkDetails))
                                    }
                                },
                            )
                            flight.detailsPersisted = true
                            SourceCallResult.Success(result.value.copy(manga = manga))
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            error.rethrowSourceControlFailure()
                            SourceCallResult.Error(
                                if (error is ChapterDirectoryDownloadConflictException ||
                                    error.hasSourceCompatibilityMarker()
                                ) {
                                    error.toSourceAppError()
                                } else {
                                    AppError.Storage(error)
                                },
                            ).also {
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
                    PreparedChapterCatalog(
                        current,
                        chapterRepository.getChapterByMangaId(current.id)
                            .sortedBy { it.sourceOrder },
                    )
                },
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            error.rethrowSourceControlFailure()
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
            error.rethrowSourceControlFailure()
            SourceCallResult.Error(AppError.Storage(error)).also {
                updateRefreshState(
                    SourceMangaRefreshKey(manga.source, manga.url),
                    SourceMangaRefreshState
                        .Failure(it.error),
                )
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
        origin: String = "BROWSE",
    ): Manga {
        val listedUrl = guardSourceOperation(SourceOperationPhase.DETAILS) { listedManga.url }
        val current = mangaRepository.getMangaByUrlAndSourceId(listedUrl, source.id)
        current?.let { chapterRepository.pendingDirectoryPhase(it.id) }?.let { phase ->
            completePhase(requireNotNull(current), phase)
            return mangaRepository.getMangaById(current.id)
        }
        return when (val result = refresh(source, listedManga, true, origin = origin).await()) {
            is SourceCallResult.Success -> result.value.manga
            is SourceCallResult.Error -> throw (result.error.cause ?: IllegalStateException(result.error.toString()))
            is SourceCallResult.Timeout -> throw (result.error.cause ?: java.net.SocketTimeoutException())
        }
    }

    private suspend fun fetchFromSource(
        source: Source,
        listedManga: SManga,
        flight: RefreshFlight,
    ): PreparedChapterCatalog {
        val manga = catalogStorageCall {
            flight.expectedManga ?: run {
                val listedUrl = guardSourceOperation(SourceOperationPhase.DETAILS) { listedManga.url }
                mangaRepository.getMangaByUrlAndSourceId(listedUrl, source.id)
                    ?: listedManga.toDomainManga(source.id, SourceOperationPhase.DETAILS)
            }
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
        val detailsUrl = guardSourceOperation(SourceOperationPhase.DETAILS) { details.url }
        val listedUrl = guardSourceOperation(SourceOperationPhase.DETAILS) { listedManga.url }
        check(detailsUrl == listedUrl) { "Source manga identity conflict" }
        val validated = catalogWriter.validate(update.chapters)
        flight.networkDetails = details
        flight.networkChapters = validated
        flight.networkComplete = update.chapterListComplete
        val saveDetails = flight.wantsDetails.get()
        val saved = catalogStorageCall {
            if (saveDetails) {
                await(
                    details, source.id, validated, true, complete = update.chapterListComplete,
                    sourceName = source.toString(), origin = flight.origin, expectedManga = manga.takeIf { it.id > 0 },
                    prepareChapter = { chapter ->
                        if (source is eu.kanade.tachiyomi.source.online.HttpSource) {
                            source.prepareNewChapter(chapter, details)
                        }
                    },
                )
            } else {
                awaitReaderCatalog(details, source.id, validated, false, expectedManga = manga.takeIf { it.id > 0 })
            }
        }
        flight.detailsPersisted = saveDetails
        val urls = validated.map { it.url }.toSet()
        return catalogStorageCall {
            PreparedChapterCatalog(
                saved,
                chapterRepository.getChapterByMangaId(saved.id).filter {
                    it
                        .url in urls
                }.sortedBy { it.sourceOrder },
            )
        }
    }

    private suspend fun <T> catalogStorageCall(block: suspend () -> T): T = try {
        block()
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: ChapterDirectoryDownloadConflictException) {
        throw error
    } catch (error: Exception) {
        error.rethrowSourceControlFailure()
        if (error.hasSourceCompatibilityMarker()) throw error
        throw SourceCatalogStorageException(error)
    }

    suspend fun awaitLinkedChapter(
        source: Source,
        listedManga: SManga,
        linkedChapter: SChapter?,
    ): ResolvedSourceChapter {
        val listedUrl = guardSourceOperation(SourceOperationPhase.DETAILS) { listedManga.url }
        mangaRepository.getMangaByUrlAndSourceId(listedUrl, source.id)?.let { existingManga ->
            chapterRepository.pendingDirectoryPhase(existingManga.id)?.let { phase ->
                completePhase(existingManga, phase)
                return ResolvedSourceChapter(
                    mangaRepository.getMangaById(existingManga.id),
                    linkedChapter?.let { chapterRepository.getChapterByUrlAndMangaId(it.url, existingManga.id) },
                )
            }
        }
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
        val updatedManga =
            await(
                update.manga,
                source.id,
                update.chapters,
                fetchDetails = false,
                complete = update.chapterListComplete,
                sourceName = source.toString(),
                prepareChapter = { chapter ->
                    if (source is eu.kanade.tachiyomi.source.online.HttpSource) {
                        source.prepareNewChapter(
                            chapter,
                            update.manga,
                        )
                    }
                },
            )
        val chapter = chapterRepository.getChapterByUrlAndMangaId(linkedChapter.url, manga.id)
        return ResolvedSourceChapter(updatedManga, chapter)
    }

    suspend fun awaitListed(
        sManga: SManga,
        sourceId: Long,
    ): Manga {
        val url = guardSourceOperation(SourceOperationPhase.DETAILS) { sManga.url }
        mangaRepository.getMangaByUrlAndSourceId(url, sourceId)?.let { return it }
        val title = guardSourceOperation(SourceOperationPhase.DETAILS) { sManga.title }
        val thumbnail = guardSourceOperation(SourceOperationPhase.DETAILS) { sManga.thumbnail_url }
        val memo = guardSourceOperation(SourceOperationPhase.DETAILS) { sManga.memo }
        val networkManga = Manga.create().copy(
            url = url,
            title = title,
            source = sourceId,
            thumbnailUrl = thumbnail,
            initialized = false,
            memo = memo,
            chapterFlags = initialChapterFlags(),
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
            is SourceCallResult.Error -> ListedMangaForDetails(
                manga,
                needsRefresh = false,
                preparationError =
                prepared.error,
            )
            is SourceCallResult.Timeout -> ListedMangaForDetails(
                manga,
                needsRefresh = false,
                preparationError = prepared.error,
            )
        }
    }

    private suspend fun listedForDetails(manga: Manga): ListedMangaForDetails {
        val needsCatalog = if (manga.source != 0L && creatorArchiveRepository != null) {
            catalogWriter.needsRefresh(manga)
        } else {
            chapterRepository.getChapterByMangaId(manga.id).isEmpty()
        }
        return ListedMangaForDetails(manga, needsRefresh = !manga.initialized || needsCatalog)
    }

    suspend fun await(
        sManga: SManga,
        sourceId: Long,
        sChapters: List<SChapter>,
        fetchDetails: Boolean = true,
        complete: Boolean = true,
        prepareChapter: (SChapter) -> Unit = {},
        sourceName: String = sourceId.toString(),
        origin: String = "BROWSE",
        expectedManga: Manga? = null,
    ): Manga {
        require(complete) { "Source chapter directory is incomplete" }
        // Migration can supply metadata without a directory. Network callers validate empty responses first.
        if (sChapters.isEmpty() && sourceId != 0L) {
            return awaitReaderCatalog(sManga, sourceId, sChapters, fetchDetails, expectedManga)
        }
        val url = guardSourceOperation(SourceOperationPhase.DETAILS) { sManga.url }
        val remote = if (sChapters.isEmpty()) sChapters else catalogWriter.validate(sChapters)
        val existing = if (expectedManga != null) {
            val current = mangaRepository.getMangaById(expectedManga.id)
            check(
                current.source == expectedManga.source && current.url == expectedManga.url &&
                    sourceId == current.source && url == current.url,
            ) { "Source manga identity conflict" }
            current
        } else {
            mangaRepository.getMangaByUrlAndSourceId(url, sourceId)
        }
        existing?.let { manga ->
            chapterRepository.pendingDirectoryPhase(manga.id)?.let { phase ->
                completePhase(manga, phase)
                return mangaRepository.getMangaById(manga.id)
            }
        }
        val identity = existing ?: captureForSave(sManga, sourceId, details = false, includeIdentity = true)
            .copy(chapterFlags = initialChapterFlags())
        val now = System.currentTimeMillis()
        val extension = sourceDateExtensionIdentityProvider(sourceId)
        val disallowNonAscii = disallowNonAsciiFilenames()
        val prepared = tachiyomi.domain.chapter.service.ChapterDirectoryPlan.prepare(identity, remote, prepareChapter)
        val policy = if (origin == "DETAIL_REFRESH") {
            downloadPolicy(identity)
        } else {
            mihon.domain.chapter.interactor.DownloadNewChapterPolicy(false, false)
        }
        val metadata = captureForSave(sManga, sourceId, fetchDetails, includeIdentity = false)
        fun request(manga: Manga) = tachiyomi.domain.chapter.service.ChapterDirectoryCommit(
            mangaId = manga.id,
            source = prepared.map { it.copy(chapter = it.chapter.copy(mangaId = manga.id)) },
            now = now,
            allowEmpty = sourceId == 0L,
            complete = complete,
            mangaMetadata = if (fetchDetails) {
                MangaUpdate(
                    id = manga.id, title = metadata.title, author = metadata.author, updateAuthor = true,
                    artist = metadata.artist, updateArtist = true, description = metadata.description,
                    genre = metadata.genre,
                    status = metadata.status, thumbnailUrl = metadata.thumbnailUrl,
                    updateStrategy = metadata.updateStrategy, initialized = true, memo = metadata.memo,
                )
            } else {
                MangaUpdate(manga.id, memo = metadata.memo)
            },
            metadataOnlyForNonFavorites = true,
            effects = tachiyomi.domain.chapter.service.ChapterDirectoryEffects(
                sourceId, sourceName, manga.url, manga.title, origin, now,
                extension.packageName, extension.version,
                CreatorSourceWorkKey.stableUrl(manga.url, manga.title, manga.author, manga.artist),
                prepared.map {
                    tachiyomi.domain.chapter.service.DirectoryChapterDate(it.chapter.url, it.originalUploadDate)
                },
                downloadEnabled = policy.enabled, downloadUnreadOnly = policy.unreadOnly,
                observe = creatorArchiveRepository != null,
                disallowNonAsciiFilenames = disallowNonAscii,
            ),
        )
        val (storedManga, committed) = if (existing != null) {
            existing to directoryCommit(existing, request(existing))
        } else {
            catalogWriter.transaction {
                check(mangaRepository.getMangaByUrlAndSourceId(url, sourceId) == null) {
                    "Source manga identity conflict"
                }
                catalogWriter.validateWorkIdentity(identity)
                val created = networkToLocalManga(identity)
                created to catalogWriter.commitDirectory(created, request(created))
            }
        }
        val persisted = mangaRepository.getMangaById(storedManga.id)
        committed.phase?.let { completePhase(persisted, it) }
        return persisted
    }

    private suspend fun completePhase(manga: Manga, phase: tachiyomi.domain.chapter.service.ChapterDirectoryPhase) {
        val consumer = finishPendingDirectory
        if (consumer != null) {
            consumer(manga, phase)
        } else {
            chapterRepository.finishDirectoryPhase(
                phase,
                rename = { _, _ -> },
                observe = { requireNotNull(creatorArchiveRepository).observeDirectoryPhase(it) },
                download = { _, chapters ->
                    check(chapters.isEmpty()) { "Browse cannot replace an accepted download policy" }
                },
            )
        }
    }

    private fun captureForSave(
        value: SManga,
        sourceId: Long,
        details: Boolean,
        includeIdentity: Boolean,
        readMemo: Boolean = true,
        readStrategy: Boolean = true,
    ): Manga {
        val phase = SourceOperationPhase.DETAILS
        val url = guardSourceOperation(phase) { value.url }
        val title = if (details || includeIdentity) guardSourceOperation(phase) { value.title } else ""
        val thumbnail = if (details || includeIdentity) guardSourceOperation(phase) { value.thumbnail_url } else null
        val memo = if (readMemo) guardSourceOperation(phase) { value.memo } else Manga.create().memo
        if (!details) {
            return Manga.create().copy(
                source = sourceId,
                url = url,
                title = title,
                thumbnailUrl = thumbnail,
                memo = memo,
            )
        }
        val author = guardSourceOperation(phase) { value.author }
        val artist = guardSourceOperation(phase) { value.artist }
        val description = guardSourceOperation(phase) { value.description }
        val genre = guardSourceOperation(phase) { value.genre }
        val status = guardSourceOperation(phase) { value.status }
        val strategy = if (readStrategy) {
            guardSourceOperation(phase) { value.update_strategy }
        } else {
            eu.kanade.tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE
        }
        return Manga.create().copy(
            source = sourceId, url = url, title = title, thumbnailUrl = thumbnail, memo = memo,
            author = author, artist = artist, description = description,
            genre = genre?.split(", ")?.takeIf { it.isNotEmpty() }, status = status.toLong(), updateStrategy = strategy,
            initialized = true,
        )
    }

    private suspend fun awaitReaderCatalog(
        sManga: SManga,
        sourceId: Long,
        sChapters: List<SChapter>,
        fetchDetails: Boolean = true,
        expectedManga: Manga? = null,
    ): Manga {
        // Generic callers such as migration may save metadata without receiving a directory.
        // Source refresh callers validate their network response before reaching this method.
        if (sChapters.isNotEmpty()) catalogWriter.validate(sChapters)
        val metadata = captureForSave(
            sManga,
            sourceId,
            fetchDetails,
            includeIdentity = false,
            readMemo = fetchDetails,
            readStrategy = false,
        )
        return catalogWriter.transaction {
            val dbManga = persistManga(metadata, sourceId, fetchDetails, expectedManga)
            if (sChapters.isNotEmpty()) catalogWriter.merge(dbManga, sChapters)

            dbManga
        }
    }

    /** Called only inside the catalogue transaction; directory-only visits retain download identity. */
    private suspend fun persistManga(
        metadata: Manga,
        sourceId: Long,
        fetchDetails: Boolean,
        expectedManga: Manga?,
    ): Manga {
        expectedManga?.let { expected ->
            val current = mangaRepository.getMangaById(expected.id)
            check(
                current.source == expected.source && current.url == expected.url && sourceId == expected
                    .source && metadata.url == expected.url,
            ) { "Source manga identity conflict" }
        }
        val existing = mangaRepository.getMangaByUrlAndSourceId(metadata.url, sourceId)
        catalogWriter.validateWorkIdentity(
            existing ?: Manga.create().copy(
                source = sourceId,
                url = metadata
                    .url,
                title = metadata.title,
                author = metadata.author,
                artist = metadata.artist,
            ),
        )
        if (!fetchDetails) return requireNotNull(existing)
        val networkManga = Manga.create().copy(
            url = metadata.url,
            title = metadata.title,
            source = sourceId,
            thumbnailUrl = metadata.thumbnailUrl,
            author = metadata.author,
            artist = metadata.artist,
            description = metadata.description,
            genre = metadata.genre,
            status = metadata.status,
            initialized = true,
            memo = metadata.memo,
        )

        val storedManga = networkToLocalManga(networkManga)
        check(
            mangaRepository.update(
                MangaUpdate(
                    storedManga.id,
                    memo = metadata.memo,
                    initialized = true
                        .takeIf { fetchDetails },
                ),
            ),
        )
        val dbManga = mangaRepository.getMangaById(storedManga.id)
        return dbManga
    }
}

data class PreparedChapterCatalog(val manga: Manga, val chapters: List<Chapter>)
class SourceCatalogUnavailableException : IllegalStateException("Source unavailable")
private class SourceCatalogStorageException(cause: Throwable) : IllegalStateException(
    "Source catalogue storage failed",
    cause,
)

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

internal fun mergeSourceMangaDetails(original: SManga, details: SManga): SManga = details.also { value ->
    val phase = SourceOperationPhase.DETAILS
    try {
        guardSourceOperation(phase) { value.url }
    } catch (_: UninitializedPropertyAccessException) {
        val url = guardSourceOperation(phase) { original.url }
        guardSourceOperation(phase) { value.url = url }
    }
    try {
        guardSourceOperation(phase) { value.title }
    } catch (_: UninitializedPropertyAccessException) {
        val title = guardSourceOperation(phase) { original.title }
        guardSourceOperation(phase) { value.title = title }
    }
    val thumbnail = guardSourceOperation(phase) { value.thumbnail_url }
    if (thumbnail.isNullOrBlank()) {
        val originalThumbnail = guardSourceOperation(phase) { original.thumbnail_url }
        guardSourceOperation(phase) { value.thumbnail_url = originalThumbnail }
    }
}

private fun Throwable.hasSourceCompatibilityMarker(): Boolean =
    inspectSourceFailure().causes.any { it is SourceCompatibilityException }
