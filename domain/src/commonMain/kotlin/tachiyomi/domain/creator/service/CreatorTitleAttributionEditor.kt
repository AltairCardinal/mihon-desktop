package tachiyomi.domain.creator.service

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga
import tachiyomi.domain.creator.model.CreatorLibraryIndexEntry
import tachiyomi.domain.creator.model.CreatorMention
import tachiyomi.domain.creator.repository.CreatorLibraryIndexWriter
import tachiyomi.domain.creator.repository.CreatorLibraryMangaSource
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

data class CreatorTitleAttributionChange(val enabled: Boolean, val reindexedCount: Int)

class CreatorTitleAttributionEditor(
    private val preferences: CreatorDiscoveryPreferences,
    private val mangaRepository: MangaRepository,
    private val mangaSource: CreatorLibraryMangaSource,
    private val indexWriter: CreatorLibraryIndexWriter,
    private val extractor: ExtractCreatorsFromManga,
    val policy: CreatorTitleAttributionPolicy,
) {
    private val mutex = Mutex()
    val enabledSourcesFlow: Flow<Set<String>> = preferences.titleAttributionSources().changes()

    fun extract(manga: Manga): List<CreatorMention> = extractor.await(manga)

    fun preview(manga: Manga): List<CreatorMention> = TitleCreditParser.parse(manga.title)

    /** Once accepted, finish this one-manga commit; genuine write failures restore the preference. */
    suspend fun setEnabled(
        mangaId: Long,
        sourceId: Long,
        enabled: Boolean,
    ): CreatorTitleAttributionChange = mutex.withLock {
        val manga = mangaRepository.getMangaById(mangaId)
        check(manga.source == sourceId) { "作品图源已变化，请重新打开标题署名设置" }
        currentCoroutineContext().ensureActive()
        val preference = preferences.titleAttributionSources()
        val before = preference.get()
        val after = if (enabled) before + sourceId.toString() else before - sourceId.toString()
        withContext(NonCancellable) {
            try {
                preference.set(after)
                if (manga.favorite) {
                    indexWriter.indexLibraryMangaBatch(
                        listOf(CreatorLibraryIndexEntry(manga, extract(manga))),
                    )
                }
                CreatorTitleAttributionChange(enabled, if (manga.favorite) 1 else 0)
            } catch (failure: Throwable) {
                try {
                    preference.set(before)
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
                throw failure
            }
        }
    }

    /** Explicit, source-scoped small batches. Completed batches survive cancellation and can be retried. */
    suspend fun reindexLibrary(sourceId: Long, onProgress: (Int) -> Unit = {}): Int = mutex.withLock {
        var afterId = Long.MIN_VALUE
        var count = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = mangaSource.getLibraryMangaForCreatorIndex(afterId, 250L)
            if (page.isEmpty()) break
            check(page.first().id > afterId && page.zipWithNext().all { (a, b) -> a.id < b.id }) {
                "书架作者整理未能继续分页，请稍后重试"
            }
            val selected = page.filter { it.source == sourceId }
            if (selected.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    indexWriter.indexLibraryMangaBatch(selected.map { CreatorLibraryIndexEntry(it, extract(it)) })
                    count += selected.size
                    onProgress(count)
                }
            }
            currentCoroutineContext().ensureActive()
            afterId = page.last().id
            if (page.size < 250) break
        }
        count
    }
}
