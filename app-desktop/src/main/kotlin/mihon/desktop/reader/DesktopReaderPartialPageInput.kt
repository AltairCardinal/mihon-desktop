package mihon.desktop.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.domain.reader.content.DownloadChapterIdentity
import mihon.domain.reader.partial.DisabledPartialDownloadSnapshotLookup
import mihon.domain.reader.partial.PartialDownloadSnapshotLookup
import mihon.domain.reader.partial.PartialReaderPageCandidate
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal data class DesktopReaderPartialPageRevisionKey(
    val chapterId: Long,
    val downloadAttemptGeneration: Long,
    val readerOrdinal: Int,
    val sourcePageIndex: Int,
    val committedRevision: Long,
)

class DesktopReaderPartialPageFallbackCoordinator {
    private val rejectedAtReaderAttempt = ConcurrentHashMap<DesktopReaderPartialPageRevisionKey, Long>()

    fun isRejected(chapterId: Long, candidate: PartialReaderPageCandidate): Boolean =
        rejectedAtReaderAttempt.containsKey(candidate.revisionKey(chapterId))

    fun reject(
        chapterId: Long,
        candidate: PartialReaderPageCandidate,
        readerAttemptGeneration: Long,
    ) {
        require(readerAttemptGeneration >= 0L) { "readerAttemptGeneration must be non-negative" }
        rejectedAtReaderAttempt.merge(candidate.revisionKey(chapterId), readerAttemptGeneration, ::maxOf)
    }

    internal fun rejectedAttempt(
        chapterId: Long,
        candidate: PartialReaderPageCandidate,
    ): Long? = rejectedAtReaderAttempt[candidate.revisionKey(chapterId)]

    private fun PartialReaderPageCandidate.revisionKey(chapterId: Long) = DesktopReaderPartialPageRevisionKey(
        chapterId = chapterId,
        downloadAttemptGeneration = attemptGeneration,
        readerOrdinal = readerOrdinal,
        sourcePageIndex = sourcePageIndex,
        committedRevision = committedRevision,
    )
}

fun interface DesktopReaderPartialPageCopyPort {
    suspend fun copy(candidate: PartialReaderPageCandidate, destination: File): Long
}

internal object DesktopReaderPartialPageFileCopyPort : DesktopReaderPartialPageCopyPort {
    override suspend fun copy(candidate: PartialReaderPageCandidate, destination: File): Long =
        withContext(Dispatchers.IO) {
            val source = File(candidate.opaqueLocation)
            if (
                !source.isFile ||
                source.length() <= 0L ||
                source.name.endsWith(".tmp", ignoreCase = true) ||
                source.name.endsWith(".part", ignoreCase = true)
            ) {
                throw DesktopReaderPartialPageUnavailableException("Committed partial page is unavailable")
            }
            val expectedBytes = source.length()
            destination.parentFile?.mkdirs()
            val copiedBytes = try {
                source.inputStream().buffered().use { input ->
                    destination.outputStream().buffered().use(input::copyTo)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                throw DesktopReaderPartialPageUnavailableException("Unable to copy committed partial page", error)
            }
            if (copiedBytes <= 0L || copiedBytes != expectedBytes || destination.length() != copiedBytes) {
                throw DesktopReaderPartialPageUnavailableException("Committed partial page changed while copying")
            }
            copiedBytes
        }
}

internal class DesktopReaderPartialPageUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal class DesktopReaderPartialPageInput(
    private val chapterId: Long,
    private val identity: DownloadChapterIdentity,
    private val readerOrdinal: Int?,
    private val sourcePageIndex: Int,
    private val initialCandidate: PartialReaderPageCandidate?,
    private val lookup: PartialDownloadSnapshotLookup,
    private val fallbackCoordinator: DesktopReaderPartialPageFallbackCoordinator,
    private val copyPort: DesktopReaderPartialPageCopyPort,
) {
    private var lookupPerformed = false
    private var liveCandidate: PartialReaderPageCandidate? = null

    fun initialCandidate(): PartialReaderPageCandidate? = initialCandidate
        ?.takeIf(::matchesPage)
        ?.takeUnless { fallbackCoordinator.isRejected(chapterId, it) }

    fun preferredCandidate(): PartialReaderPageCandidate? {
        val ordinal = readerOrdinal ?: return null
        if (!lookupPerformed && lookup !== DisabledPartialDownloadSnapshotLookup) {
            liveCandidate = lookup.committedPageCandidate(
                chapterId = chapterId,
                identity = identity,
                readerOrdinal = ordinal,
                sourcePageIndex = sourcePageIndex,
            )
            lookupPerformed = true
        }
        return listOfNotNull(initialCandidate, liveCandidate)
            .asSequence()
            .filter(::matchesPage)
            .filterNot { fallbackCoordinator.isRejected(chapterId, it) }
            .maxWithOrNull(compareBy(PartialReaderPageCandidate::attemptGeneration, PartialReaderPageCandidate::committedRevision))
    }

    suspend fun copy(candidate: PartialReaderPageCandidate, destination: File): Long {
        if (!matchesPage(candidate) || fallbackCoordinator.isRejected(chapterId, candidate)) {
            throw DesktopReaderPartialPageUnavailableException("Committed partial page revision is not eligible")
        }
        val source = File(candidate.opaqueLocation)
        if (
            source.name.endsWith(".tmp", ignoreCase = true) ||
            source.name.endsWith(".part", ignoreCase = true)
        ) {
            throw DesktopReaderPartialPageUnavailableException("Downloader staging files are not reader inputs")
        }
        return copyPort.copy(candidate, destination)
    }

    private fun matchesPage(candidate: PartialReaderPageCandidate): Boolean =
        candidate.readerOrdinal == readerOrdinal &&
            candidate.sourcePageIndex == sourcePageIndex &&
            candidate.attemptGeneration >= 0L &&
            candidate.committedRevision > 0L &&
            candidate.opaqueLocation.isNotBlank()
}
