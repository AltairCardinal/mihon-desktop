package mihon.desktop.download

import java.io.File
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

internal class MutableCommittedPageIndex<T> {
    private val records = ConcurrentHashMap<Int, T>()

    internal val storageIdentity: Any
        get() = records

    fun contains(readerOrdinal: Int): Boolean = records.containsKey(readerOrdinal)

    fun get(readerOrdinal: Int): T? = records[readerOrdinal]

    fun put(readerOrdinal: Int, value: T) {
        records[readerOrdinal] = value
    }

    fun valuesSnapshot(): List<T> = records.values.toList()
}

internal class AtomicPageFileMove(
    private val movePath: (Path, Path, Array<out CopyOption>) -> Unit = { source, target, options ->
        Files.move(source, target, *options)
        Unit
    },
) {
    fun publish(staging: File, destination: File) {
        if (!staging.isFile || staging.length() <= 0L) throw java.io.IOException("Unable to finalize empty page")
        movePath(
            staging.toPath(),
            destination.toPath(),
            arrayOf(StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE),
        )
    }
}

interface DownloadEnqueueFileOperations {
    fun isChapterDownloaded(provider: DesktopDownloadProvider, item: DownloadItem): Boolean

    fun cleanupTemporaryDirectory(provider: DesktopDownloadProvider, item: DownloadItem)
}

object DefaultDownloadEnqueueFileOperations : DownloadEnqueueFileOperations {
    override fun isChapterDownloaded(provider: DesktopDownloadProvider, item: DownloadItem): Boolean =
        provider.isChapterDownloaded(item.sourceId, item.mangaTitle, item.chapterName)

    override fun cleanupTemporaryDirectory(provider: DesktopDownloadProvider, item: DownloadItem) {
        provider.cleanupTmpDir(item.sourceId, item.mangaTitle, item.chapterName)
    }
}

interface PartialDownloadIndexFileOperations {
    fun isDirectory(directory: File): Boolean

    fun listFiles(directory: File): List<File>

    fun isValidCommittedPage(provider: DesktopDownloadProvider, file: File): Boolean
}

object DefaultPartialDownloadIndexFileOperations : PartialDownloadIndexFileOperations {
    override fun isDirectory(directory: File): Boolean = directory.isDirectory

    override fun listFiles(directory: File): List<File> = directory.listFiles().orEmpty().toList()

    override fun isValidCommittedPage(provider: DesktopDownloadProvider, file: File): Boolean =
        provider.isValidDownloadedImage(file)
}

enum class DownloadIoOperation {
    ENQUEUE_DOWNLOADED_PROBE,
    ENQUEUE_TMP_CLEANUP,
    SOURCE_PAGE_LIST,
    SOURCE_IMAGE_URL,
    INDEX_DIRECTORY_PROBE,
    INDEX_DIRECTORY_LIST,
    STAGING_CLEANUP,
    NETWORK_REQUEST,
    BODY_READ,
    PAGE_WRITE,
    PAGE_HEADER_PROBE,
    PAGE_MOVE,
    CHAPTER_MOVE,
    CBZ_PACKAGE,
    CBZ_VALIDATE,
    CBZ_PUBLISH,
    CHAPTER_CLEANUP,
    PARTIAL_PAGE_PROBE,
    PARTIAL_PAGE_OPEN,
    PARTIAL_PAGE_COPY,
}

data class DownloadLockState(
    val queueStateLocked: Boolean,
    val indexLocked: Boolean,
    val coordinatorLocked: Boolean,
    val lifecycleLocked: Boolean,
)

data class DownloadIoPageIdentity(
    val attemptGeneration: Long,
    val readerOrdinal: Int,
    val sourcePageIndex: Int,
    val committedRevision: Long,
)

data class DownloadIoEvent(
    val operation: DownloadIoOperation,
    val locks: DownloadLockState,
    val page: DownloadIoPageIdentity? = null,
)

fun interface DownloadIoProbe {
    val enabled: Boolean get() = true

    fun onIo(event: DownloadIoEvent)

    object None : DownloadIoProbe {
        override val enabled: Boolean = false
        override fun onIo(event: DownloadIoEvent) = Unit
    }
}
