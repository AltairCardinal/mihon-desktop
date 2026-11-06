package mihon.feature.migration.list

import android.util.Log
import androidx.annotation.FloatRange
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceFailureIdentity
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.guardSourceOperation
import eu.kanade.tachiyomi.network.inspectSourceFailure
import eu.kanade.tachiyomi.network.rethrowSourceControlFailure
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import mihon.domain.error.AppError
import mihon.domain.migration.BatchMigrationEvent
import mihon.domain.migration.usecases.MigrateMangaUseCase
import mihon.feature.migration.list.models.MigratingManga
import mihon.feature.migration.list.models.MigratingManga.SearchResult
import mihon.feature.migration.list.search.SmartSourceSearchEngine
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SOURCE_COMPATIBILITY_LOG_TAG
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.recordSourceFailureDiagnostics
import tachiyomi.domain.source.service.toSourceAppError
import tachiyomi.domain.source.service.withSourceFailureIdentity
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MigrationListScreenModel(
    mangaIds: Collection<Long>,
    extraSearchQuery: String?,
    private val preferences: SourcePreferences = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val syncChaptersWithSource: SyncChaptersWithSource = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val migrateManga: MigrateMangaUseCase = Injekt.get(),
    private val batchMigrationRunner: AndroidBatchMigrationRunner<MigratingManga> = AndroidBatchMigrationRunner(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val sourceFailureLog: (String) -> Unit = { Log.e(SOURCE_COMPATIBILITY_LOG_TAG, it) },
) : StateScreenModel<MigrationListScreenModel.State>(State()) {

    private val smartSearchEngine = SmartSourceSearchEngine(extraSearchQuery)

    val items
        inline get() = state.value.items

    private val hideUnmatched = preferences.migrationHideUnmatched().get()
    private val hideWithoutUpdates = preferences.migrationHideWithoutUpdates().get()

    private val navigateBackChannel = Channel<Unit>()
    val navigateBackEvent = navigateBackChannel.receiveAsFlow()

    private var migrateJob: Job? = null

    init {
        screenModelScope.launchIO {
            val manga = mangaIds
                .map {
                    async {
                        val manga = getManga.await(it) ?: return@async null
                        val chapterInfo = getChapterInfo(it)
                        val source = sourceManager.getOrStub(manga.source)
                        val installed = extensionManager.installedExtensionsFlow.value
                        val identity = sourceIdentity(manga.source, source, installed)
                        var failure: AppError? = null
                        val label = try {
                            sourceLabel(source)
                        } catch (error: Exception) {
                            failure = error.compatibilityFailure(identity) ?: throw error
                            ""
                        }
                        MigratingManga(
                            manga = manga,
                            chapterCount = chapterInfo.chapterCount,
                            latestChapter = chapterInfo.latestChapter,
                            source = label,
                            parentContext = screenModelScope.coroutineContext,
                        ).also { item -> failure?.let { item.searchResult.value = SearchResult.Failure(it) } }
                    }
                }
                .awaitAll()
                .filterNotNull()
            currentCoroutineContext().ensureActive()
            mutableState.update { it.copy(items = manga.toImmutableList()) }
            manga.forEach { item ->
                if (owns(item)) {
                    (item.searchResult.value as? SearchResult.Failure)?.error
                        ?.recordSourceFailureDiagnostics(sourceFailureLog)
                }
            }
            if (manga.any { it.searchResult.value is SearchResult.Failure }) updateMigrationProgress()
            runMigrations(manga)
        }
    }

    private suspend fun getChapterInfo(id: Long) = getChaptersByMangaId.await(id).let { chapters ->
        ChapterInfo(
            latestChapter = chapters.maxOfOrNull { it.chapterNumber },
            chapterCount = chapters.size,
        )
    }

    private suspend fun Manga.toSuccessSearchResult(label: String): SearchResult.Success {
        val chapterInfo = getChapterInfo(id)
        return SearchResult.Success(
            manga = this,
            chapterCount = chapterInfo.chapterCount,
            latestChapter = chapterInfo.latestChapter,
            source = label,
        )
    }

    private fun sourceIdentity(id: Long, source: Source, installed: List<Extension.Installed>): SourceFailureIdentity {
        val owner = installed.firstOrNull { it.sources.any { registered -> registered === source } }
        return SourceFailureIdentity(id, owner?.pkgName, owner?.name, owner?.versionName, owner?.versionCode)
    }

    private fun sourceLabel(source: Source): String {
        val languages = preferences.enabledLanguages().get().filterNot { it == "all" || it == "other" }
        val language = guardSourceOperation(SourceOperationPhase.METADATA) { source.lang }
        return if (languages.size == 1 && language in languages) {
            guardSourceOperation(SourceOperationPhase.METADATA) { source.name }
        } else {
            guardSourceOperation(SourceOperationPhase.METADATA) { source.toString() }
        }
    }

    private fun Throwable.compatibilityFailure(identity: SourceFailureIdentity): AppError? {
        rethrowSourceControlFailure()
        if (inspectSourceFailure().causes.none { it is SourceCompatibilityException }) return null
        return toSourceAppError().withSourceFailureIdentity(identity)
    }

    private class Candidate(val id: Long, val source: Source, val identity: SourceFailureIdentity)
    private class Attempt(
        val candidate: Candidate,
        val value: Pair<Manga, ChapterInfo>? = null,
        val error: AppError? = null,
    )
    private class Selection(val result: SearchResult, val failures: List<AppError>)

    private suspend fun owns(item: MigratingManga): Boolean {
        currentCoroutineContext().ensureActive()
        return item.migrationScope.isActive && state.value.items.any { it === item }
    }

    private suspend fun runMigrations(mangas: List<MigratingManga>) {
        val prioritizeByChapters = preferences.migrationPrioritizeByChapters().get()
        val deepSearchMode = preferences.migrationDeepSearchMode().get()

        val installed = extensionManager.installedExtensionsFlow.value
        val sources = preferences.migrationSources().get().mapNotNull { id ->
            val source = sourceManager.get(id) as? CatalogueSource ?: return@mapNotNull null
            Candidate(id, source, sourceIdentity(id, source, installed))
        }

        for (manga in mangas) {
            if (!currentCoroutineContext().isActive) break
            if (manga.manga.id !in state.value.mangaIds) continue
            if (manga.searchResult.value != SearchResult.Searching) continue
            if (!manga.migrationScope.isActive) continue

            val selection = try {
                manga.migrationScope.async {
                    val failures = mutableListOf<AppError>()
                    if (prioritizeByChapters) {
                        val sourceSemaphore = Semaphore(5)
                        sources.map { source ->
                            async innerAsync@{
                                sourceSemaphore.withPermit {
                                    searchSource(manga.manga, source, deepSearchMode)
                                }
                            }
                        }
                            .map { it.await() }
                            .also { attempts -> failures += attempts.mapNotNull { it.error } }
                            .filter { it.value != null && it.value.second.chapterCount > 0 }
                            .sortedByDescending { it.value?.second?.latestChapter ?: 0.0 }
                            .forEach { attempt ->
                                when (val selected = finishCandidate(attempt)) {
                                    is SearchResult.Success -> return@async Selection(selected, failures.toList())
                                    is SearchResult.Failure -> failures += selected.error
                                    else -> Unit
                                }
                            }
                    } else {
                        sources.forEach { source ->
                            val attempt = searchSource(manga.manga, source, deepSearchMode)
                            attempt.error?.let(failures::add)
                            if (attempt.value != null) {
                                when (val selected = finishCandidate(attempt)) {
                                    is SearchResult.Success -> return@async Selection(selected, failures.toList())
                                    is SearchResult.Failure -> failures += selected.error
                                    else -> Unit
                                }
                            }
                        }
                    }
                    Selection(
                        failures.firstOrNull()?.let { SearchResult.Failure(it) } ?: SearchResult.NotFound,
                        failures.toList(),
                    )
                }
                    .await()
            } catch (_: CancellationException) {
                continue
            }

            if (!owns(manga)) continue
            val result = selection.result
            manga.searchResult.value = result
            selection.failures.forEach { if (owns(manga)) it.recordSourceFailureDiagnostics(sourceFailureLog) }

            if (result == SearchResult.NotFound && hideUnmatched) {
                removeManga(manga)
            }
            if (result is SearchResult.Success &&
                hideWithoutUpdates &&
                (result.latestChapter ?: 0.0) <= (manga.latestChapter ?: 0.0)
            ) {
                removeManga(manga)
            }

            updateMigrationProgress()
        }
    }

    private suspend fun searchSource(
        manga: Manga,
        candidate: Candidate,
        deepSearchMode: Boolean,
    ): Attempt {
        val source = candidate.source as CatalogueSource
        return try {
            val searchResult = if (deepSearchMode) {
                smartSearchEngine.deepSearch(source, candidate.id, manga.title)
            } else {
                smartSearchEngine.regularSearch(source, candidate.id, manga.title)
            }

            if (searchResult == null || (searchResult.url == manga.url && candidate.id == manga.source)) {
                return Attempt(candidate)
            }

            val localManga = networkToLocalManga(searchResult)
            try {
                val input = localManga.toSManga()
                val chapters = guardSourceOperation(SourceOperationPhase.CHAPTERS) { source.getChapterList(input) }
                syncChaptersWithSource.await(chapters, localManga, source)
            } catch (e: Exception) {
                e.compatibilityFailure(candidate.identity)?.let { return Attempt(candidate, error = it) }
                logcat(LogPriority.ERROR, e)
            }
            Attempt(candidate, localManga to getChapterInfo(localManga.id))
        } catch (error: Exception) {
            Attempt(candidate, error = error.compatibilityFailure(candidate.identity))
        }
    }

    private suspend fun finishCandidate(attempt: Attempt, forceDetails: Boolean = false): SearchResult {
        val manga = checkNotNull(attempt.value).first
        val candidate = attempt.candidate
        if (forceDetails || manga.thumbnailUrl == null) {
            val input = manga.toSManga()
            try {
                val remote = guardSourceOperation(SourceOperationPhase.DETAILS) {
                    candidate.source.getMangaDetails(input)
                }
                val prepared = snapshotDetails(remote, manga.url)
                updateManga.awaitUpdateFromSource(manga, prepared, true)
            } catch (error: Exception) {
                error.compatibilityFailure(candidate.identity)?.let { return SearchResult.Failure(it) }
            }
        }
        val label = try {
            sourceLabel(candidate.source)
        } catch (error: Exception) {
            return SearchResult.Failure(error.compatibilityFailure(candidate.identity) ?: throw error)
        }
        return manga.toSuccessSearchResult(label)
    }

    private fun snapshotDetails(remote: SManga, url: String): SManga {
        val title = try {
            guardSourceOperation(SourceOperationPhase.DETAILS) { remote.title }
        } catch (_: UninitializedPropertyAccessException) {
            null
        }
        val thumbnail = guardSourceOperation(SourceOperationPhase.DETAILS) { remote.thumbnail_url }
        val author = guardSourceOperation(SourceOperationPhase.DETAILS) { remote.author }
        val artist = guardSourceOperation(SourceOperationPhase.DETAILS) { remote.artist }
        val description = guardSourceOperation(SourceOperationPhase.DETAILS) { remote.description }
        val genres = guardSourceOperation(SourceOperationPhase.DETAILS) { remote.getGenres() }
        val status = guardSourceOperation(SourceOperationPhase.DETAILS) { remote.status }
        val strategy = guardSourceOperation(SourceOperationPhase.DETAILS) { remote.update_strategy }
        val memo = guardSourceOperation(SourceOperationPhase.DETAILS) { remote.memo }
        val host = SManga.create().apply {
            this.url = url
            title?.let { this.title = it }
            thumbnail_url = thumbnail
            this.author = author
            this.artist = artist
            this.description = description
            this.status = status
            update_strategy = strategy
            this.memo = memo
        }
        return object : SManga by host {
            override fun getGenres(): List<String>? = genres
        }
    }

    private suspend fun updateMigrationProgress() {
        mutableState.update { state ->
            state.copy(
                finishedCount = items.count { it.searchResult.value != SearchResult.Searching },
                migrationComplete = migrationComplete(),
            )
        }
        if (items.isEmpty()) {
            navigateBack()
        }
    }

    private fun migrationComplete() = items.all { it.searchResult.value != SearchResult.Searching } &&
        items.any { it.searchResult.value is SearchResult.Success }

    fun useMangaForMigration(current: Long, target: Long, onMissingChapters: () -> Unit) {
        val migratingManga = items.find { it.manga.id == current } ?: return
        migratingManga.searchResult.value = SearchResult.Searching
        screenModelScope.launchIO {
            val result = migratingManga.migrationScope.async {
                val manga = getManga.await(target) ?: return@async SearchResult.NotFound
                val source = sourceManager.get(manga.source) ?: return@async SearchResult.NotFound
                val candidate = Candidate(
                    manga.source,
                    source,
                    sourceIdentity(manga.source, source, extensionManager.installedExtensionsFlow.value),
                )
                try {
                    val input = manga.toSManga()
                    val chapters = guardSourceOperation(SourceOperationPhase.CHAPTERS) { source.getChapterList(input) }
                    syncChaptersWithSource.await(chapters, manga, source)
                } catch (error: Exception) {
                    return@async error.compatibilityFailure(candidate.identity)?.let { SearchResult.Failure(it) }
                        ?: SearchResult.NotFound
                }
                finishCandidate(Attempt(candidate, manga to getChapterInfo(manga.id)), forceDetails = true)
            }
                .await()

            if (!owns(migratingManga)) return@launchIO
            if (result == SearchResult.NotFound) {
                migratingManga.searchResult.value = SearchResult.NotFound
                withUIContext { onMissingChapters() }
                return@launchIO
            }

            migratingManga.searchResult.value = result
            if (result is SearchResult.Failure) result.error.recordSourceFailureDiagnostics(sourceFailureLog)
            updateMigrationProgress()
        }
    }

    fun migrateMangas() {
        migrateMangas(replace = true)
    }

    fun copyMangas() {
        migrateMangas(replace = false)
    }

    private fun migrateMangas(
        replace: Boolean,
        batchItems: List<MigratingManga> = items,
    ) {
        migrateJob = screenModelScope.launchIO {
            mutableState.update { it.copy(dialog = Dialog.Progress(0f)) }
            val failures = mutableListOf<BatchFailure>()
            try {
                batchMigrationRunner.run(batchItems) { manga ->
                    ensureActive()
                    val target = (manga.searchResult.value as? SearchResult.Success)?.manga
                    if (target != null) {
                        migrateManga(current = manga.manga, target = target, replace = replace).getOrThrow()
                    }
                }.collect { event ->
                    when (event) {
                        is BatchMigrationEvent.Succeeded -> updateBatchProgress(event.index + 1, batchItems.size)
                        is BatchMigrationEvent.Failed -> {
                            logcat(LogPriority.WARN) { event.message }
                            failures += BatchFailure(
                                manga = event.item,
                                title = event.item.manga.title,
                                reason = event.message,
                            )
                            updateBatchProgress(event.index + 1, batchItems.size)
                        }
                        is BatchMigrationEvent.WaitingForUser -> updateBatchProgress(event.index, batchItems.size)
                        is BatchMigrationEvent.Completed -> {
                            if (failures.isEmpty()) {
                                navigateBack()
                            } else {
                                mutableState.update { it.copy(dialog = Dialog.Failures(failures.toList(), replace)) }
                            }
                        }
                    }
                }
            } finally {
                mutableState.update { state ->
                    if (state.dialog is Dialog.Progress) state.copy(dialog = null) else state
                }
                migrateJob = null
            }
        }
    }

    fun retryFailedMigrations() {
        val dialog = state.value.dialog as? Dialog.Failures ?: return
        migrateMangas(dialog.replace, dialog.failures.map { it.manga })
    }

    fun finishFailedMigrations() {
        screenModelScope.launchIO {
            mutableState.update { it.copy(dialog = null) }
            navigateBack()
        }
    }

    private fun updateBatchProgress(completed: Int, total: Int) {
        mutableState.update {
            it.copy(dialog = Dialog.Progress((completed.toFloat() / total).coerceAtMost(1f)))
        }
    }

    fun cancelMigrate() {
        migrateJob?.cancel()
        migrateJob = null
    }

    private suspend fun navigateBack() {
        navigateBackChannel.send(Unit)
    }

    fun migrateNow(mangaId: Long, replace: Boolean) {
        screenModelScope.launchIO {
            val manga = items.find { it.manga.id == mangaId } ?: return@launchIO
            val target = (manga.searchResult.value as? SearchResult.Success)?.manga ?: return@launchIO
            migrateManga(current = manga.manga, target = target, replace = replace)

            removeManga(mangaId)
        }
    }

    fun removeManga(mangaId: Long) {
        screenModelScope.launchIO {
            val item = items.find { it.manga.id == mangaId } ?: return@launchIO
            removeManga(item)
            item.migrationScope.cancel()
            updateMigrationProgress()
        }
    }

    private fun removeManga(item: MigratingManga) {
        mutableState.update { it.copy(items = items.toPersistentList().remove(item)) }
    }

    override fun onDispose() {
        super.onDispose()
        items.forEach {
            it.migrationScope.cancel()
        }
    }

    fun showMigrateDialog(copy: Boolean) {
        mutableState.update { state ->
            state.copy(
                dialog = Dialog.Migrate(
                    copy = copy,
                    totalCount = items.size,
                    skippedCount = items.count {
                        it.searchResult.value == SearchResult.NotFound || it.searchResult.value is SearchResult.Failure
                    },
                ),
            )
        }
    }

    fun showExitDialog() {
        mutableState.update {
            it.copy(dialog = Dialog.Exit)
        }
    }

    fun dismissDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    data class ChapterInfo(
        val latestChapter: Double?,
        val chapterCount: Int,
    )

    data class BatchFailure(
        val manga: MigratingManga,
        val title: String,
        val reason: String,
    )

    sealed interface Dialog {
        data class Migrate(val copy: Boolean, val totalCount: Int, val skippedCount: Int) : Dialog
        data class Progress(@FloatRange(0.0, 1.0) val progress: Float) : Dialog
        data class Failures(val failures: List<BatchFailure>, val replace: Boolean) : Dialog
        data object Exit : Dialog
    }

    data class State(
        val items: ImmutableList<MigratingManga> = persistentListOf(),
        val finishedCount: Int = 0,
        val migrationComplete: Boolean = false,
        val dialog: Dialog? = null,
    ) {
        val mangaIds: List<Long> = items.map { it.manga.id }
    }
}
