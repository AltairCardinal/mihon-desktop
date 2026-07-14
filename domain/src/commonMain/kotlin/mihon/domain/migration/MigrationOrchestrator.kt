package mihon.domain.migration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import mihon.domain.migration.models.MigrationFlag

data class MigrationChapter(
    val id: Long,
    val chapterNumber: Double,
    val read: Boolean = false,
    val bookmark: Boolean = false,
    val dateFetch: Long = 0,
) {
    val isRecognizedNumber: Boolean get() = chapterNumber >= 0.0
}

data class MigrationChapterUpdate(
    val id: Long,
    val read: Boolean? = null,
    val bookmark: Boolean? = null,
    val dateFetch: Long? = null,
)

data class MigrationMangaMetadata(
    val mangaId: Long,
    val categoryIds: List<Long>,
    val chapterFlags: Long,
    val viewerFlags: Long,
    val dateAdded: Long,
    val notes: String?,
)

data class MigrationLibraryPlan(
    val targetMangaId: Long,
    val targetCategoryIds: List<Long>,
    val targetChapterFlags: Long,
    val targetViewerFlags: Long,
    val targetDateAdded: Long,
    val targetNotes: String?,
    val removeCurrentFromLibrary: Boolean,
)

/** Android's authoritative platform-independent migration rules. */
class MigrationOrchestrator {
    fun chapterUpdates(
        current: List<MigrationChapter>,
        target: List<MigrationChapter>,
    ): List<MigrationChapterUpdate> {
        val maxChapterRead = current.filter { it.read }.maxOfOrNull { it.chapterNumber }
        return target.map { chapter ->
            val matching = current.find { it.isRecognizedNumber && it.chapterNumber == chapter.chapterNumber }
            val shouldMarkRead = chapter.isRecognizedNumber &&
                maxChapterRead != null &&
                chapter.chapterNumber <= maxChapterRead
            MigrationChapterUpdate(
                id = chapter.id,
                read = true.takeIf { shouldMarkRead && !chapter.read },
                bookmark = matching?.bookmark,
                dateFetch = matching?.dateFetch,
            )
        }
    }

    fun libraryPlan(
        current: MigrationMangaMetadata,
        targetMangaId: Long,
        flags: Set<MigrationFlag>,
        replace: Boolean,
        now: Long,
    ) = MigrationLibraryPlan(
        targetMangaId = targetMangaId,
        targetCategoryIds = if (MigrationFlag.CATEGORY in flags) current.categoryIds.distinct() else emptyList(),
        targetChapterFlags = current.chapterFlags,
        targetViewerFlags = current.viewerFlags,
        targetDateAdded = if (replace) current.dateAdded else now,
        targetNotes = current.notes.takeIf { MigrationFlag.NOTES in flags },
        removeCurrentFromLibrary = replace,
    )
}

sealed interface BatchMigrationEvent<out T> {
    data class Succeeded<T>(val index: Int, val item: T, val result: Any?) : BatchMigrationEvent<T>
    data class Failed<T>(val index: Int, val item: T, val message: String) : BatchMigrationEvent<T>
    data class WaitingForUser<T>(val index: Int, val item: T) : BatchMigrationEvent<T>
    data class Completed(val nextIndex: Int) : BatchMigrationEvent<Nothing>
}

class BatchMigrationWaitingForUserException : Exception()

/** Runs items independently while preserving cancellation and a durable next-index checkpoint. */
class BatchMigrationOrchestrator<T> {
    fun run(
        items: List<T>,
        startIndex: Int = 0,
        migrate: suspend (T) -> Any?,
    ): Flow<BatchMigrationEvent<T>> = flow {
        require(startIndex in 0..items.size) { "startIndex must be within the batch" }
        items.drop(startIndex).forEachIndexed { offset, item ->
            val index = startIndex + offset
            try {
                emit(BatchMigrationEvent.Succeeded(index, item, migrate(item)))
            } catch (_: BatchMigrationWaitingForUserException) {
                emit(BatchMigrationEvent.WaitingForUser(index, item))
                emit(BatchMigrationEvent.Completed(index))
                return@flow
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                emit(BatchMigrationEvent.Failed(index, item, error.message ?: error::class.simpleName.orEmpty()))
            }
        }
        emit(BatchMigrationEvent.Completed(items.size))
    }
}
