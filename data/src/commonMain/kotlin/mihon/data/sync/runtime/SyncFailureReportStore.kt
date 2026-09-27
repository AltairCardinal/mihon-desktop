package mihon.data.sync.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncObjectDescriptor
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import okio.BufferedSink
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import tachiyomi.data.DatabaseHandler
import java.util.UUID

/** A local, read-only explanation of durable projection failures in one run's pending downloads. */
class SyncFailureReportStore(
    private val handler: DatabaseHandler,
    private val directory: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    fun exists(path: String): Boolean = fileSystem.metadataOrNull(path.toPath())?.isRegularFile == true

    suspend fun generate(run: SyncRunSnapshot): SyncFailureLogStatus? {
        val (fieldCount, invalidCount) = handler.await {
            sync_runtimeQueries.countFailureReportFields(run.spaceId, run.generation, run.runId).executeAsOne() to
                sync_runtimeQueries.countFailureReportInvalidEvents(run.spaceId, run.generation, run.runId)
                    .executeAsOne()
        }
        val count = fieldCount + invalidCount
        if (count == 0L) return null
        val name = "sync-failure-${run.runId.encodeUtf8().sha256().hex()}.txt"
        val destination = directory.resolve(name)
        val temporary = directory.resolve("$name.${UUID.randomUUID()}.tmp")
        try {
            fileSystem.createDirectories(directory)
            fileSystem.sink(temporary, mustCreate = true).buffer().use { sink ->
                sink.writeUtf8("Mihon 同步失败报告 / Sync failure report\n")
                sink.writeUtf8(
                    "运行 / Run: ${run.runId}\n空间 / Space: ${run.spaceId}\n代数 / Generation: ${run.generation}\n",
                )
                sink.writeUtf8("失败事件或字段项 / Failed event or field entries: $count\n")
                sink.writeUtf8("仅记录本次运行待确认下载批次中的持久失败，不代表失败漫画本数。\n")
                sink.writeUtf8("待人工决定和仍在检查的字段不算失败。\n\n")
                writeFields(run, sink)
                writeInvalidEvents(run, sink)
            }
            fileSystem.atomicMove(temporary, destination)
            return SyncFailureLogStatus.Ready(run.runId, destination.toString(), count)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SyncFailureLogStatus.SaveFailed(run.runId, count)
        } finally {
            runCatching { fileSystem.delete(temporary, mustExist = false) }
        }
    }

    private suspend fun writeFields(run: SyncRunSnapshot, sink: BufferedSink) {
        var offset = 0L
        while (true) {
            val parentTitles = mutableMapOf<String, String?>()
            val rows = handler.await {
                sync_runtimeQueries.getFailureReportFields(
                    spaceId = run.spaceId,
                    generation = run.generation,
                    runId = run.runId,
                    pageSize = PAGE_SIZE,
                    pageOffset = offset,
                ).executeAsList()
            }
            if (rows.isEmpty()) return
            rows.forEach { row ->
                val key = runCatching { Json.decodeFromString<SyncObjectKey>(row.object_json) }.getOrNull()
                val descriptor = row.description_json?.let {
                    runCatching { Json.decodeFromString<SyncObjectDescriptor>(it) }.getOrNull()
                }
                val event = runCatching { Json.decodeFromString<SyncEventEnvelope>(row.event_json) }.getOrNull()
                val effect = event?.effects?.firstOrNull {
                    it.objectKey == key && it.field.name == row.field_name
                }
                val parentTitle = key?.takeIf { it.type == SyncObjectType.CHAPTER }?.let { chapter ->
                    val parent = SyncObjectKey(
                        SyncObjectType.MANGA,
                        sourceId = chapter.sourceId,
                        originalUrl = chapter.parentUrl,
                    )
                    parentTitles.getOrPut(parent.stableKey) {
                        handler.await {
                            sync_inboxQueries.getDescription(run.spaceId, run.generation, parent.stableKey)
                                .executeAsOneOrNull()
                        }?.let { runCatching { Json.decodeFromString<SyncObjectDescriptor>(it) }.getOrNull()?.title }
                    }
                }
                sink.writeUtf8("[字段 / FIELD] 批次 / Batch: ${row.batch_id}; 事件 / Event: ${row.event_key}\n")
                sink.writeUtf8("原因 / Reason: ${reasonLabel(row.failure_reason)}; 字段 / Field: ${row.field_name}\n")
                sink.writeUtf8(
                    "漫画 / Manga: ${if (key?.type == SyncObjectType.MANGA) descriptor?.title ?: MISSING else parentTitle ?: MISSING}\n",
                )
                sink.writeUtf8(
                    "章节 / Chapter: ${if (key?.type == SyncObjectType.CHAPTER) descriptor?.title ?: MISSING else NOT_APPLICABLE}\n",
                )
                sink.writeUtf8("源 ID / Source ID: ${key?.sourceId ?: MISSING}\n")
                sink.writeUtf8("原始 URL / Original URL: ${key?.originalUrl ?: MISSING}\n")
                sink.writeUtf8("父 URL / Parent URL: ${key?.parentUrl ?: NOT_APPLICABLE}\n")
                sink.writeUtf8(
                    "待应用数据 / Value: ${effect?.kind?.name ?: MISSING}; payload: ${effect?.payload ?: MISSING}\n\n",
                )
            }
            offset += rows.size
        }
    }

    private suspend fun writeInvalidEvents(run: SyncRunSnapshot, sink: BufferedSink) {
        var offset = 0L
        while (true) {
            val rows = handler.await {
                sync_runtimeQueries.getFailureReportInvalidEvents(
                    spaceId = run.spaceId,
                    generation = run.generation,
                    runId = run.runId,
                    pageSize = PAGE_SIZE,
                    pageOffset = offset,
                ).executeAsList()
            }
            if (rows.isEmpty()) return
            rows.forEach { row ->
                sink.writeUtf8("[无效事件 / INVALID EVENT] 批次 / Batch: ${row.batch_id}; 事件 / Event: ${row.event_key}\n")
                sink.writeUtf8("原因 / Reason: ${row.failure_reason.take(MAX_REASON_LENGTH).replace('\n', ' ')}\n")
                val event = runCatching { Json.decodeFromString<SyncEventEnvelope>(row.event_json) }.getOrNull()
                if (event == null || event.effects.isEmpty()) {
                    sink.writeUtf8("漫画、章节、源、URL 和待应用数据：$MISSING\n\n")
                } else {
                    event.effects.forEach { effect ->
                        val key = effect.objectKey
                        val description = handler.await {
                            sync_inboxQueries.getDescription(run.spaceId, run.generation, key.stableKey)
                                .executeAsOneOrNull()
                        }?.let { runCatching { Json.decodeFromString<SyncObjectDescriptor>(it) }.getOrNull() }
                        sink.writeUtf8("漫画或章节 / Manga or chapter: ${description?.title ?: MISSING}\n")
                        sink.writeUtf8("源 ID / Source ID: ${key.sourceId ?: MISSING}\n")
                        sink.writeUtf8("原始 URL / Original URL: ${key.originalUrl ?: MISSING}\n")
                        sink.writeUtf8("父 URL / Parent URL: ${key.parentUrl ?: NOT_APPLICABLE}\n")
                        sink.writeUtf8(
                            "字段 / Field: ${effect.field.name}; 待应用数据 / Value: ${effect.kind.name}; payload: ${effect.payload}\n",
                        )
                    }
                    sink.writeUtf8("\n")
                }
            }
            offset += rows.size
        }
    }

    private companion object {
        const val PAGE_SIZE = 128L
        const val MAX_REASON_LENGTH = 200
        const val MISSING = "（缺失或无法解析）"
        const val NOT_APPLICABLE = "（不适用）"
    }

    private fun reasonLabel(reason: String): String = when (reason) {
        "SOURCE" -> "SOURCE（上次尝试时漫画源不可用；重试可重新检查）"
        "DESCRIPTION" -> "DESCRIPTION（描述缺失或无效）"
        "IDENTITY" -> "IDENTITY（身份信息不匹配）"
        else -> reason
    }
}
