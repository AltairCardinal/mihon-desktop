package tachiyomi.data.creator

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga
import tachiyomi.domain.creator.service.CreatorDiscoveryPreferences
import tachiyomi.domain.creator.service.CreatorTitleAttributionEditor
import tachiyomi.domain.creator.service.CreatorTitleAttributionPolicy
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Actual platform handler commits on its transaction thread; the caller continuation is held. */
suspend fun verifyCreatorTitleCommitCancellation(
    handler: DatabaseHandler,
    driver: SqlDriver,
    preferences: CreatorDiscoveryPreferences,
    rebuild: Boolean,
) {
    val policy = CreatorTitleAttributionPolicy(preferences)
    val extractor = ExtractCreatorsFromManga(policy)
    val creators = CreatorRepositoryImpl(handler, extractCreators = extractor)
    creators.indexLibraryMangaBatch(emptyList())
    val mangas = MangaRepositoryImpl(handler, creators, extractor)
    val manga = mangas.insertNetworkManga(
        listOf(
            Manga.create().copy(
                source = 550L,
                url = "/cancel",
                favorite = true,
                title = "[Circle (Artist)] Work",
                author = "Uploader",
            ),
        ),
    ).single()
    if (rebuild) preferences.titleAttributionSources().set(setOf("550"))
    val editor = CreatorTitleAttributionEditor(preferences, mangas, mangas, creators, extractor, policy)
    val caller = HeldCreatorCallerDispatcher()
    val scope = CoroutineScope(SupervisorJob() + caller)
    var progress = 0
    val job = scope.launch {
        if (rebuild) editor.reindexLibrary(550L) { progress = it } else editor.setEnabled(manga.id, 550L, true)
    }
    fun titleCommitted(): Boolean = driver.executeQuery(
        null,
        "SELECT COUNT(*) FROM author_archive_manga_links WHERE manga_id = ${manga.id} AND evidence LIKE 'title-attribution:%'",
        { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(cursor.next().value && cursor.getLong(0) == 2L)
        },
        0,
    ).value
    try {
        // Each queued continuation follows a completed dispatcher hop. Hold the hop after COMMIT.
        var continuation = caller.next()
        var hops = 0
        while (!titleCommitted()) {
            check(hops++ < 20) { "Title index did not commit within bounded dispatcher hops" }
            continuation.run()
            continuation = caller.next()
        }
        job.cancel()
        continuation.run()
        while (!job.isCompleted) caller.next().run()
        check(titleCommitted()) { "The fixture must cancel after the actual SQL commit" }
        check(policy.isEnabled(550L)) { "Cancellation after commit must not restore the old preference" }
        if (rebuild) check(progress == 1) { "Committed batch must report progress before cancellation: $progress" }
    } finally {
        scope.cancel()
    }
}

private class HeldCreatorCallerDispatcher : CoroutineDispatcher() {
    private val continuations = LinkedBlockingQueue<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        continuations.put(block)
    }

    fun next(): Runnable = checkNotNull(continuations.poll(5, TimeUnit.SECONDS)) {
        "Database transaction did not return a caller continuation"
    }
}
