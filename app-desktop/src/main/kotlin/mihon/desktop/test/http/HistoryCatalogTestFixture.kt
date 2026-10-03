package mihon.desktop.test.http

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.data.sync.inbox.SyncInboxStore
import mihon.data.sync.journal.SyncLocalJournal
import mihon.data.sync.journal.SyncOutboxStore
import mihon.data.sync.runtime.SyncRuntime
import mihon.desktop.di.createDriver
import mihon.desktop.platform.DesktopPlatformPaths
import mihon.desktop.platform.DesktopTestProfile
import mihon.domain.sync.SyncMutationContext
import mihon.domain.sync.transport.SyncRepository
import okhttp3.OkHttpClient
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.reader.SqlDelightReadingProgressRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.reader.model.ReadingProgressEvent
import java.io.File
import java.nio.file.Files
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

/** Fixed local records only; never accepts caller SQL, remote identities, credentials or paths. */
internal class HistoryCatalogTestFixture(
    private val profile: File,
    private val handler: DatabaseHandler,
    private val runtime: SyncRuntime,
    client: OkHttpClient,
    baseUrl: String,
    private val verifyProfile: () -> Unit = {
        val directory = profile.canonicalFile
        check(DesktopTestProfile.root == directory) { "History fixture requires the startup-isolated test profile" }
        val marker = directory.resolve(".mihon-test-profile")
        check(
            !Files.isSymbolicLink(marker.toPath()) &&
                marker.readText(Charsets.UTF_8) == "mihon-desktop-test-profile-v1\n",
        )
        check(DesktopPlatformPaths.current().databaseFile.canonicalFile.toPath().startsWith(directory.toPath()))
    },
) : AutoCloseable {
    private val mutex = Mutex()

    @Volatile private var directoryGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    suspend fun awaitDirectoryRelease() {
        directoryGate?.await()
    }
    val source = HistoryCatalogTestSource(client, "$baseUrl/test/history/catalog-source")
    val chapterCalls = AtomicInteger()
    val pageCalls = AtomicInteger()
    val imageCalls = AtomicInteger()

    @Volatile var mode = "success"
        private set

    init {
        HistoryCatalogTestSourceBridge.install(source)
    }

    suspend fun execute(step: String, requestedMode: String = "success") = mutex.withLock {
        verifyProfile()
        require(step in setOf("seed", "advance", "mode", "check", "hold", "release"))
        require(
            requestedMode in
                setOf("success", "http403", "http429", "http500", "empty", "malformed", "missing_target", "timeout"),
        )
        if (step == "hold") {
            check(directoryGate?.isCompleted != false) { "Directory is already held" }
            directoryGate = kotlinx.coroutines.CompletableDeferred()
            return@withLock
        }
        if (step == "release") {
            directoryGate?.complete(Unit)
            return@withLock
        }
        if (step == "mode") {
            mode = requestedMode
            return@withLock
        }
        if (step == "check") return@withLock
        handler.await {
            sync_journalQueries.getActiveSpace().executeAsOneOrNull()?.let {
                check(it.space_id == SPACE && it.generation == 1L) { "Another sync space is configured" }
            }
        }
        SyncLocalJournal(
            handler,
        ).connect(SPACE, 1, SyncRepository("history-fixture", "offline", "mihon-sync"), "receiver", 1)
        val senderFile = profile.resolve("history-catalog-fixture/sender.db").apply { parentFile.mkdirs() }
        val driver = createDriver(senderFile)
        val database =
            Database(
                driver,
                History.Adapter(DateColumnAdapter),
                Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            )
        JvmDatabaseHandler(database, driver).use { sender ->
            SyncLocalJournal(
                sender,
            ).connect(SPACE, 1, SyncRepository("history-fixture", "offline", "mihon-sync"), "sender", 1)
            val mangas = MangaRepositoryImpl(sender, NoopCreatorLibraryIndexWriter)
            val manga =
                mangas.getMangaByUrlAndSourceId(HistoryCatalogTestSource.MANGA_URL, HistoryCatalogTestSource.SOURCE_ID)
                    ?: mangas.insertNetworkManga(
                        listOf(
                            Manga.create().copy(
                                source = HistoryCatalogTestSource.SOURCE_ID,
                                url = HistoryCatalogTestSource.MANGA_URL,
                                title = HistoryCatalogTestSource.MANGA_TITLE,
                            ),
                        ),
                    ).single()
            val chapters = ChapterRepositoryImpl(sender)
            val url = if (step == "advance") "/chapter/3" else "/chapter/2"
            val chapter = chapters.getChapterByUrlAndMangaId(url, manga.id)
                ?: chapters.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = url,
                            name = if (step ==
                                "advance"
                            ) {
                                "Ch.3"
                            } else {
                                "Ch.2"
                            },
                            chapterNumber = if (step == "advance") 3.0 else 2.0,
                        ),
                    ),
                ).single()
            SqlDelightReadingProgressRepository(database).record(
                ReadingProgressEvent(
                    chapter.id,
                    if (step == "advance") 2 else 1,
                    4,
                    Date(System.currentTimeMillis() + if (step == "advance") 60_000 else 0),
                    123,
                    idempotencyKey = "history-catalog-$step",
                    syncContext = SyncMutationContext.User,
                ),
            )
            val outbox = SyncOutboxStore(sender)
            while (true) {
                val batch = outbox.nextBatch(SPACE, 1) ?: break
                check(SyncInboxStore(handler).ingest(batch).accepted)
                projectAll()
                sender.await {
                    sync_journalQueries.markOutboxPublished(SPACE, 1, batch.batchId)
                    sync_journalQueries.markBatchPublished(SPACE, 1, batch.batchId)
                }
            }
        }
    }

    private suspend fun projectAll() {
        repeat(30) { if (runtime.projector.project(SPACE, 1) == 0) return }
        error("History fixture projection did not settle")
    }

    suspend fun snapshot() = run {
        verifyProfile()
        val manga = MangaRepositoryImpl(
            handler,
            NoopCreatorLibraryIndexWriter,
        ).getMangaByUrlAndSourceId(HistoryCatalogTestSource.MANGA_URL, HistoryCatalogTestSource.SOURCE_ID)
        val chapters = manga?.let { ChapterRepositoryImpl(handler).getChapterByMangaId(it.id) }.orEmpty()
        val history = tachiyomi.data.history.HistoryRepositoryImpl(handler).getHistory("").first().filter {
            it.mangaId ==
                manga?.id
        }
        val observation = manga?.let {
            handler.await { author_archiveQueries.getArchiveSourceWorkByKey(it.source, it.url).executeAsOneOrNull() }
        }
        val outgoing = SyncLocalJournal(handler).pendingEvents(SPACE, 1)
        buildJsonObject {
            put("directoryHeld", directoryGate?.isCompleted == false)
            put("chapterCalls", chapterCalls.get())
            put("pageCalls", pageCalls.get())
            put("imageCalls", imageCalls.get())
            put("chapterCount", chapters.size)
            put("historyCount", history.size)
            put("historyId", history.firstOrNull()?.id ?: -1)
            put("mangaId", manga?.id ?: -1)
            put("favorite", manga?.favorite == true)
            put("catalogState", observation?.chapter_count_state ?: "UNKNOWN")
            put("catalogCount", observation?.catalog_chapter_count ?: 0)
            put("outgoingUserEvents", outgoing.size)
            put(
                "chapters",
                buildJsonArray {
                    chapters.sortedBy { it.sourceOrder }.forEach { chapter ->
                        add(
                            buildJsonObject {
                                put("id", chapter.id)
                                put("url", chapter.url)
                                put("order", chapter.sourceOrder)
                                put("page", chapter.lastPageRead)
                                put("read", chapter.read)
                                put("bookmark", chapter.bookmark)
                                put("dateFetch", chapter.dateFetch)
                            },
                        )
                    }
                },
            )
        }
    }

    override fun close() {
        directoryGate?.cancel()
        HistoryCatalogTestSourceBridge.clear(source)
    }
    companion object {
        const val SPACE = "history-catalog-acceptance"
    }
}
