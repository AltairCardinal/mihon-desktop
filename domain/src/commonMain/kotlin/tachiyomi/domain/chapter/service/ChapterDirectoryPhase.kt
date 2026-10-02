package tachiyomi.domain.chapter.service

import kotlinx.serialization.Serializable
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository

/** Local, single-manga post-commit work. This record is neither a backup nor a sync wire object. */
@Serializable
data class ChapterDirectoryEffects(
    val sourceId: Long,
    val sourceName: String,
    val mangaUrl: String,
    val mangaTitle: String,
    val origin: String,
    val observedAt: Long,
    val extensionPackage: String = "unknown.extension",
    val extensionVersion: String = "unknown",
    val workNaturalKey: String = mangaUrl,
    val dates: List<DirectoryChapterDate> = emptyList(),
    val downloadEnabled: Boolean = false,
    val downloadUnreadOnly: Boolean = false,
    val observe: Boolean = false,
    val disallowNonAsciiFilenames: Boolean = false,
    val taskReceipt: DirectoryTaskReceipt? = null,
    val prediction: DirectoryPrediction? = null,
)

/** A device-local task checkpoint handshake; never a persistent queue or sync protocol identity. */
@Serializable
data class DirectoryTaskReceipt(val occurrenceKey: String, val unitId: Long)

/** The original SOURCE prediction inputs for this single local post-commit operation. */
@Serializable
data class DirectoryPrediction(val dateTime: String, val windowLower: Long, val windowUpper: Long)

@Serializable
data class DirectoryChapterDate(val url: String, val date: Long)

@Serializable
data class DirectoryFileChapter(val id: Long, val url: String, val name: String, val scanlator: String?) {
    fun chapter(mangaId: Long): Chapter = Chapter.create().copy(
        id = id,
        mangaId = mangaId,
        url = url,
        name = name,
        scanlator = scanlator,
    )

    companion object {
        fun from(chapter: Chapter) = DirectoryFileChapter(chapter.id, chapter.url, chapter.name, chapter.scanlator)
    }
}

@Serializable
data class DirectoryFileChange(val before: DirectoryFileChapter, val after: DirectoryFileChapter)

@Serializable
data class ChapterDirectoryPhase(
    val id: String,
    val mangaId: Long,
    val effects: ChapterDirectoryEffects,
    val currentTitle: String,
    val files: List<DirectoryFileChange>,
    val addedIds: List<Long>,
    val downloadIds: List<Long>,
    val observationPending: Boolean,
    val downloadsPending: Boolean,
    val checkpointPending: Boolean = effects.taskReceipt != null,
    val predictionPending: Boolean = false,
    val migrationReceipt: mihon.domain.migration.MigrationReceipt? = null,
) {
    val effectsComplete: Boolean get() = migrationReceipt == null && files.isEmpty() && !observationPending &&
        !downloadsPending &&
        !predictionPending
    val complete: Boolean get() = effectsComplete && !checkpointPending
}

/** Each acknowledgement follows the actual platform operation; a failed acknowledgement is safely retried. */
suspend fun ChapterRepository.finishDirectoryPhase(
    phase: ChapterDirectoryPhase,
    rename: suspend (ChapterDirectoryPhase, DirectoryFileChange) -> Unit,
    observe: suspend (ChapterDirectoryPhase) -> Unit,
    download: suspend (ChapterDirectoryPhase, List<Chapter>) -> Unit,
) {
    var pending = finishDirectoryFiles(phase, rename)
    if (pending.observationPending) {
        observe(pending)
        pending = pending.copy(observationPending = false)
        acknowledgeDirectoryPhase(pending)
    }
    if (pending.downloadsPending) {
        val chapters = pending.downloadIds.map { id ->
            requireNotNull(getChapterById(id)).also { check(it.mangaId == pending.mangaId) }
        }
        download(pending, chapters)
        pending = pending.copy(downloadsPending = false)
        acknowledgeDirectoryPhase(pending)
    }
}

/** The original file stage runs under the platform download reservation, before accepting new work. */
suspend fun ChapterRepository.finishDirectoryFiles(
    phase: ChapterDirectoryPhase,
    rename: suspend (ChapterDirectoryPhase, DirectoryFileChange) -> Unit,
): ChapterDirectoryPhase {
    check(phase.migrationReceipt == null) { "A migration must be recovered by its original confirmation" }
    var pending = phase
    for (change in phase.files) {
        val current = requireNotNull(getChapterById(change.after.id)) { "Pending directory chapter no longer exists" }
        check(
            current.mangaId == phase.mangaId && current.url == change.after.url && current.name == change.after.name &&
                current.scanlator == change.after.scanlator,
        ) { "Pending directory chapter identity changed" }
        rename(pending, change)
        pending = pending.copy(files = pending.files.filterNot { it.after.id == change.after.id })
        acknowledgeDirectoryPhase(pending)
    }
    return pending
}
