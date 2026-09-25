package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import mihon.data.sync.runtime.SyncRuntime
import mihon.data.sync.transport.GitHubSyncTransport
import mihon.data.sync.transport.SyncUploadArtifactCodec
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.runtime.SyncTrigger
import mihon.domain.sync.transport.SyncPreparedUpload
import mihon.domain.sync.transport.SyncPublishStatus
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okio.Path.Companion.toPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Date
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One realistic, paired artifact comparison. Input and encrypted output remain in ignored local paths. */
@Timeout(value = 40, unit = TimeUnit.MINUTES)
class SyncGitCompareAcceptanceTest {
    @Test
    fun `production first import and same prepared artifacts replay`() = runBlocking {
        val inputName = System.getenv("SYNC_COMPARE_INPUT")
        val outputName = System.getenv("SYNC_COMPARE_OUTPUT")
        val countName = System.getenv("SYNC_COMPARE_COUNT")
        assumeTrue(inputName != null && outputName != null && countName != null, "explicit local comparison only")
        val input = Path.of(requireNotNull(inputName))
        val output = Path.of(requireNotNull(outputName)).toAbsolutePath().normalize()
        require(!Files.exists(output)) { "comparison output must be a fresh directory" }
        val records = Json.parseToJsonElement(Files.readString(input, StandardCharsets.UTF_8))
            .jsonObject.getValue("records").jsonArray
        val expectedCount = requireNotNull(countName).toInt()
        assertEquals(expectedCount, records.size)
        Files.createDirectories(output)
        val dbFile = Files.createTempFile("mihon-sync-git-compare-", ".db")
        val driver = JdbcSqliteDriver(
            "jdbc:sqlite:$dbFile",
            Properties().apply { setProperty("foreign_keys", "true") },
        )
        Database.Schema.create(driver)
        val database = Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        val storage = SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database, driver))
        try {
            val populateStart = System.nanoTime()
            val mangas = records.map { record ->
                val item = record.jsonObject.getValue("manga").jsonObject
                Manga.create().copy(
                    source = item.getValue("source").jsonPrimitive.long,
                    url = item.getValue("url").jsonPrimitive.content,
                    title = item.getValue("title").jsonPrimitive.content,
                    author = item.textOrNull("author"),
                    artist = item.textOrNull("artist"),
                    thumbnailUrl = item.textOrNull("thumbnail_url"),
                    favorite = true,
                )
            }
            assertEquals(records.size, mangas.map { it.source to it.url }.toSet().size)
            val inserted = storage.manga.insertNetworkManga(mangas)
            assertEquals(records.size, inserted.size)
            mangas.zip(inserted).forEach { (expected, actual) ->
                assertEquals(expected.source to expected.url, actual.source to actual.url)
            }
            val chapterInputs = records.zip(inserted).flatMap { (record, manga) ->
                record.jsonObject.getValue("chapters").jsonArray.map { raw ->
                    val chapter = raw.jsonObject
                    val lastRead = chapter.longOrZero("last_read")
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = chapter.getValue("url").jsonPrimitive.content,
                        name = chapter.getValue("name").jsonPrimitive.content,
                        chapterNumber = chapter.getValue("chapter_number").jsonPrimitive.content.toDouble(),
                        sourceOrder = chapter.longOrZero("source_order"),
                        scanlator = chapter.textOrNull("scanlator"),
                        read = chapter.getValue("read").jsonPrimitive.content.toBooleanStrict(),
                        lastPageRead = chapter.longOrZero("last_page_read"),
                    ) to lastRead
                }
            }
            val insertedChapters = ChapterRepositoryImpl(storage.handler).addAll(chapterInputs.map { it.first })
            assertEquals(chapterInputs.size, insertedChapters.size)
            storage.handler.await(inTransaction = true) {
                insertedChapters.zip(chapterInputs).forEach { (chapter, inputChapter) ->
                    if (inputChapter.second > 0) historyQueries.upsert(chapter.id, Date(inputChapter.second), 0)
                }
            }
            val populateMs = elapsedMs(populateStart)
            val expectedReading = chapterInputs.count { (chapter, lastRead) ->
                chapter.read || chapter.lastPageRead > 0 || lastRead > 0
            }
            SyncOnboardingFixture(storage).use { setup ->
                val password = "synthetic-git-compare-space-password"
                val material = setup.existing(password)
                assertTrue(material.secret != null, "comparison requires encrypted space material")
                setup.authorize()
                val initialHead = setup.git.head(setup.repository.branch)
                val initialTreeOid = setup.git.treeSha(setup.repository.branch)
                val initialFiles = setup.git.files(setup.repository.branch)
                writeFiles(output, "initial", initialFiles)
                val runtime = setup.runtime(
                    persistentObjectCacheDirectory = output.resolve("l2-cache").toString().toPath(),
                )
                try {
                    val space = requireNotNull(
                        (runtime.onboarding.discover() as mihon.data.sync.auth.SyncSpaceDiscovery.Found).space,
                    )
                    val intent = runtime.onboarding.join(space, material)
                    val requestBytes = AtomicLong()
                    val responseBytes = AtomicLong()
                    val delegate = setup.git.server.dispatcher
                    setup.git.server.dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val response = delegate.dispatch(request)
                            val upload = request.body?.size?.toLong() ?: 0L
                            requestBytes.addAndGet(upload)
                            responseBytes.addAndGet(response.body?.contentLength ?: 0L)
                            val uploadMillis = (upload + 1_249L) / 1_250L
                            if (uploadMillis > 0) Thread.sleep(uploadMillis)
                            return response.newBuilder()
                                .headersDelay(50, TimeUnit.MILLISECONDS)
                                .throttleBody(12_500, 10, TimeUnit.MILLISECONDS)
                                .build()
                        }
                    }
                    val requestsBefore = setup.git.server.requestCount
                    val refsBefore = setup.git.forceFlags.size
                    val acceptedAt = System.nanoTime()
                    runtime.onboarding.resume(intent)
                    val freezeMs = elapsedMs(acceptedAt)
                    val importComplete = async(Dispatchers.IO) {
                        withTimeout(2_100_000) {
                            storage.handler.subscribeToOne {
                                sync_importQueries.countPendingImports("space", 1)
                            }.first { it == 0L }
                            elapsedMs(acceptedAt)
                        }
                    }
                    val firstConfirmed = async(Dispatchers.IO) {
                        withTimeout(2_100_000) {
                            storage.handler.subscribeToOne {
                                sync_journalQueries.countPublishedEvents("space", 1)
                            }.first { it > 0L }
                            elapsedMs(acceptedAt)
                        }
                    }
                    val result = runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                    val totalMs = elapsedMs(acceptedAt)
                    assertEquals(SyncRunStatus.SUCCESS, result.status, result.toString())
                    assertEquals(expectedCount + expectedReading, result.uploaded)
                    val runtimeRequests = setup.git.server.requestCount - requestsBefore
                    val runtimeRequestBytes = requestBytes.get()
                    val runtimeResponseBytes = responseBytes.get()
                    val runtimeRefs = setup.git.forceFlags.size - refsBefore
                    val importMs = importComplete.await()
                    val firstMs = firstConfirmed.await()
                    val finalFiles = setup.git.files(setup.repository.branch)
                    val finalManifest = writeFiles(output, "final", finalFiles)
                    val finalHead = setup.git.head(setup.repository.branch)
                    val finalTreeOid = setup.git.treeSha(setup.repository.branch)
                    val batchIds = setup.git.pathWrites.keys.asSequence()
                        .filter { it.startsWith(".mihon-sync/batches/") && it.endsWith(".json") }
                        .map { it.substringAfterLast('/').removeSuffix(".json") }.distinct().toList()
                    val artifacts = storage.handler.await {
                        batchIds.map { id ->
                            val batch = sync_journalQueries.getBatch("space", 1, id).executeAsOne()
                            requireNotNull(batch.prepared_upload).let(SyncUploadArtifactCodec::decode) to
                                batch.event_count
                        }
                    }.sortedBy { it.first.encryptedBatch.firstSeq }
                    assertEquals(runtimeRefs, artifacts.size)
                    assertEquals(result.uploaded.toLong(), artifacts.sumOf { it.second })
                    val artifactStats = buildJsonArray {
                        artifacts.forEachIndexed { index, (artifact, count) ->
                            add(
                                buildJsonObject {
                                    put("ordinal", index + 1)
                                    put("events", count)
                                    put("batchBytes", artifact.encryptedBatch.ciphertext.bytes.size)
                                    put("indexBytes", artifact.indexCiphertext.bytes.size)
                                    put("headBytes", artifact.headCiphertext.bytes.size)
                                },
                            )
                        }
                    }
                    setup.git.resetRef(setup.repository.branch, initialHead)
                    val replay = GitHubSyncTransport(
                        setup.client,
                        { "synthetic-token" },
                        setup.git.baseUrl,
                        spaceMaterial = material,
                    )
                    val replayRequestsBefore = setup.git.server.requestCount
                    val replayRequestBytesBefore = requestBytes.get()
                    val replayResponseBytesBefore = responseBytes.get()
                    val replayRefsBefore = setup.git.forceFlags.size
                    val replayStarted = System.nanoTime()
                    var snapshot = replay.readSnapshot(setup.repository, "space", 1).getOrThrow()
                    val replayInitialReadMs = elapsedMs(replayStarted)
                    val replayBatchStats = mutableListOf<JsonObject>()
                    artifacts.forEachIndexed { ordinal, (artifact, _) ->
                        val batchStarted = System.nanoTime()
                        val batchRequestsBefore = setup.git.server.requestCount
                        val batchRequestBytesBefore = requestBytes.get()
                        val batchResponseBytesBefore = responseBytes.get()
                        val publish = replay.publish(setup.repository, snapshot, artifact)
                        assertEquals(SyncPublishStatus.PUBLISHED, publish.status, publish.toString())
                        snapshot = requireNotNull(publish.confirmedSnapshot)
                        replayBatchStats += buildJsonObject {
                            put("ordinal", ordinal + 1)
                            put("publishAndConfirmMillis", elapsedMs(batchStarted))
                            put("httpRequests", setup.git.server.requestCount - batchRequestsBefore)
                            put("requestBodyBytes", requestBytes.get() - batchRequestBytesBefore)
                            put("responseBodyBytes", responseBytes.get() - batchResponseBytesBefore)
                        }
                    }
                    val replayMs = elapsedMs(replayStarted)
                    assertEquals(finalManifest, fileManifest(setup.git.files(setup.repository.branch)))
                    assertTrue(setup.git.head(setup.repository.branch) != initialHead)
                    val summary = buildJsonObject {
                        put("inputRecords", records.size)
                        put("inputChapters", chapterInputs.size)
                        put("inputReadingEntries", expectedReading)
                        put("events", result.uploaded)
                        put("batches", artifacts.size)
                        put("populateMillisExcluded", populateMs)
                        put("freezeMillisSinceAccepted", freezeMs)
                        put("importGeneratedMillisSinceAccepted", importMs)
                        put("firstConfirmedMillisSinceAccepted", firstMs)
                        put("allConfirmedMillisSinceAccepted", totalMs)
                        put("runtimeHttpRequests", runtimeRequests)
                        put("runtimeRequestBodyBytes", runtimeRequestBytes)
                        put("runtimeResponseBodyBytes", runtimeResponseBytes)
                        put("runtimeRefUpdates", runtimeRefs)
                        put("frozenReplayMillis", replayMs)
                        put("frozenReplayInitialReadMillis", replayInitialReadMs)
                        put("frozenReplayHttpRequests", setup.git.server.requestCount - replayRequestsBefore)
                        put("frozenReplayRequestBodyBytes", requestBytes.get() - replayRequestBytesBefore)
                        put("frozenReplayResponseBodyBytes", responseBytes.get() - replayResponseBytesBefore)
                        put("frozenReplayRefUpdates", setup.git.forceFlags.size - replayRefsBefore)
                        put("initialHeadLength", initialHead.length)
                        put("finalHeadLength", finalHead.length)
                        put("initialTreeOid", initialTreeOid)
                        put("finalTreeOid", finalTreeOid)
                        put("artifacts", artifactStats)
                        put("frozenReplayBatches", buildJsonArray { replayBatchStats.forEach(::add) })
                    }
                    Files.writeString(output.resolve("summary.json"), summary.toString(), StandardCharsets.UTF_8)
                    println(
                        "SYNC_GIT_COMPARE records=${records.size} events=${result.uploaded} batches=${artifacts.size} runtimeMs=$totalMs replayMs=$replayMs output=$output",
                    )
                } finally {
                    runtime.stopPanel()
                }
            }
        } finally {
            storage.close()
            runCatching { Files.deleteIfExists(dbFile) }.onFailure { dbFile.toFile().deleteOnExit() }
        }
    }

    private fun writeFiles(output: Path, name: String, files: Map<String, ByteArray>): JsonObject {
        val root = output.resolve(name)
        files.forEach { (path, bytes) ->
            require(path.isNotEmpty() && path.split('/').all { it != ".." && it != "." && it.isNotEmpty() })
            val target = root.resolve(path).normalize()
            require(target.startsWith(root))
            Files.createDirectories(target.parent)
            Files.write(target, bytes)
        }
        val manifest = fileManifest(files)
        Files.writeString(output.resolve("$name-manifest.json"), manifest.toString(), StandardCharsets.UTF_8)
        return manifest
    }

    private fun fileManifest(files: Map<String, ByteArray>): JsonObject = buildJsonObject {
        put(
            "files",
            buildJsonArray {
                files.toSortedMap().forEach { (path, bytes) ->
                    add(
                        buildJsonObject {
                            put("path", path)
                            put("bytes", bytes.size)
                            put(
                                "sha256",
                                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                                    "%02x".format(it.toInt() and 255)
                                },
                            )
                        },
                    )
                }
            },
        )
    }

    private fun JsonObject.textOrNull(name: String): String? = this[name]?.takeUnless {
        it == JsonNull
    }?.jsonPrimitive?.content

    private fun JsonObject.longOrZero(name: String): Long = this[name]?.takeUnless { it == JsonNull }
        ?.jsonPrimitive?.longOrNull ?: 0L

    private fun elapsedMs(start: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
}
