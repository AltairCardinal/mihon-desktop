package eu.kanade.domain.track.store

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.track.service.DelayedTrackerSyncItem
import tachiyomi.domain.track.service.DelayedTrackerSyncPersistence

class DelayedTrackingStore private constructor(
    private val readAll: () -> Map<String, *>,
    private val write: (String, String?) -> Unit,
) : DelayedTrackerSyncPersistence {
    private val mutex = Mutex()

    constructor(context: Context) : this(context.getSharedPreferences("tracking_queue", Context.MODE_PRIVATE))

    private constructor(preferences: SharedPreferences) : this(
        readAll = { preferences.all },
        write = { key, value ->
            preferences.edit {
                if (value == null) remove(key) else putString(key, value)
            }
        },
    )

    internal constructor(values: MutableMap<String, Any>) : this(
        readAll = { values.toMap() },
        write = { key, value -> if (value == null) values.remove(key) else values[key] = value },
    )

    override suspend fun upsertMax(item: DelayedTrackerSyncItem): DelayedTrackerSyncItem = mutex.withLock {
        val current = readItem(item.trackId)
        val merged = if (current == null || item.lastChapterRead >= current.lastChapterRead) item else current
        if (merged != current) writeItem(merged)
        merged
    }

    override suspend fun removeUpTo(trackId: Long, lastChapterRead: Double): Boolean = mutex.withLock {
        val current = readItem(trackId) ?: return@withLock false
        if (current.lastChapterRead > lastChapterRead) return@withLock false
        write(trackId.toString(), null)
        true
    }

    override suspend fun getItems(): List<DelayedTrackerSyncItem> = mutex.withLock {
        readAll().mapNotNull { (key, value) ->
            key.toLongOrNull()?.let { trackId -> value?.let { decode(trackId, it) } }
        }
    }

    private fun readItem(trackId: Long) = readAll()[trackId.toString()]?.let { decode(trackId, it) }

    private fun writeItem(item: DelayedTrackerSyncItem) {
        write(
            item.trackId.toString(),
            listOf(item.lastChapterRead, item.mangaId, item.trackerId, item.failureReason.orEmpty()).joinToString("|"),
        )
    }

    private fun decode(trackId: Long, value: Any): DelayedTrackerSyncItem? {
        if (value is Number) return DelayedTrackerSyncItem(trackId, 0, 0, value.toDouble())
        val fields = value.toString().split("|", limit = 4)
        return DelayedTrackerSyncItem(
            trackId = trackId,
            mangaId = fields.getOrNull(1)?.toLongOrNull() ?: 0,
            trackerId = fields.getOrNull(2)?.toLongOrNull() ?: 0,
            lastChapterRead = fields.firstOrNull()?.toDoubleOrNull() ?: return null,
            failureReason = fields.getOrNull(3)?.takeIf(String::isNotBlank),
        )
    }
}
