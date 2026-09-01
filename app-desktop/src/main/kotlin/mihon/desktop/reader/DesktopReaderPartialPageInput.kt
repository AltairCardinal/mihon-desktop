package mihon.desktop.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.domain.reader.content.DownloadChapterIdentity
import mihon.domain.reader.partial.DisabledPartialDownloadSnapshotLookup
import mihon.domain.reader.partial.PartialDownloadSnapshotLookup
import mihon.domain.reader.partial.PartialReaderPageCandidate
import mihon.desktop.download.DirectPartialPageReadLeaseSource
import mihon.desktop.download.DownloadIoEvent
import mihon.desktop.download.DownloadIoOperation
import mihon.desktop.download.DownloadIoPageIdentity
import mihon.desktop.download.DownloadIoProbe
import mihon.desktop.download.DownloadLockState
import mihon.desktop.download.PartialPageReadLocation
import mihon.desktop.download.PartialPageReadLeaseSource
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

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

internal data class DesktopReaderPartialPageCopyHooks(
    val afterInitialProbe: (File) -> Unit = {},
    val afterInputOpened: (File) -> Unit = {},
)

internal class DesktopReaderPartialPageFileCopyPort(
    private val leaseSource: PartialPageReadLeaseSource = DirectPartialPageReadLeaseSource,
    private val hooks: DesktopReaderPartialPageCopyHooks = DesktopReaderPartialPageCopyHooks(),
    private val ioProbe: DownloadIoProbe = DownloadIoProbe.None,
) : DesktopReaderPartialPageCopyPort {
    override suspend fun copy(candidate: PartialReaderPageCandidate, destination: File): Long =
        withContext(Dispatchers.IO) {
            val lease = leaseSource.acquire(candidate)
                ?: throw DesktopReaderPartialPageUnavailableException("Committed partial page lease is unavailable")
            lease.use {
                val initial = lease.initialLocation
                if (initial.isReaderStagingEntry()) {
                    throw DesktopReaderPartialPageUnavailableException("Downloader staging files are not reader inputs")
                }
                val probed = probeBounded(initial, candidate) { lease.reProbeLocation() }
                hooks.afterInitialProbe(probed.location.displayFile())
                destination.parentFile?.mkdirs()
                try {
                    val opened = openBounded(probed, candidate) { lease.reProbeLocation() }
                    opened.use {
                        opened.input.buffered().use { input ->
                            hooks.afterInputOpened(opened.location.displayFile())
                            emitIo(DownloadIoOperation.PARTIAL_PAGE_COPY, leaseSource, candidate)
                            destination.outputStream().buffered().use(input::copyTo)
                        }
                    }
                } catch (error: CancellationException) {
                    destination.delete()
                    throw error
                } catch (error: IOException) {
                    destination.delete()
                    throw DesktopReaderPartialPageUnavailableException("Unable to copy committed partial page", error)
                }
                    .also { copiedBytes ->
                        if (
                            copiedBytes <= 0L ||
                            copiedBytes != probed.expectedBytes ||
                            destination.length() != copiedBytes
                        ) {
                            destination.delete()
                            throw DesktopReaderPartialPageUnavailableException("Committed partial page changed while copying")
                        }
                    }
            }
        }

    private fun probeBounded(
        initial: PartialPageReadLocation,
        candidate: PartialReaderPageCandidate,
        reProbe: () -> PartialPageReadLocation?,
    ): ProbedPartialPage {
        probe(initial, candidate)?.let { expectedBytes ->
            return ProbedPartialPage(initial, expectedBytes, fallbackUsed = false)
        }
        val alternate = reProbe()?.takeIf { it != initial }
            ?: throw DesktopReaderPartialPageUnavailableException("Committed partial page is unavailable")
        if (alternate.isReaderStagingEntry()) {
            throw DesktopReaderPartialPageUnavailableException("Downloader staging files are not reader inputs")
        }
        val expectedBytes = probe(alternate, candidate)
            ?: throw DesktopReaderPartialPageUnavailableException("Committed partial page is unavailable")
        return ProbedPartialPage(alternate, expectedBytes, fallbackUsed = true)
    }

    private fun probe(location: PartialPageReadLocation, candidate: PartialReaderPageCandidate): Long? {
        emitIo(DownloadIoOperation.PARTIAL_PAGE_PROBE, leaseSource, candidate)
        return when (location) {
            is PartialPageReadLocation.FilePage -> location.file.takeIf(File::isFile)?.length()?.takeIf { it > 0L }
            is PartialPageReadLocation.CbzEntry -> runCatching {
                ZipFile(location.archive).use { archive ->
                    archive.getEntry(location.entryName)
                        ?.takeUnless { it.isDirectory }
                        ?.size
                        ?.takeIf { it > 0L }
                }
            }.getOrNull()
        }
    }

    private fun openBounded(
        probed: ProbedPartialPage,
        candidate: PartialReaderPageCandidate,
        reProbe: () -> PartialPageReadLocation?,
    ): OpenedPartialPage {
        try {
            emitIo(DownloadIoOperation.PARTIAL_PAGE_OPEN, leaseSource, candidate)
            return open(probed.location)
        } catch (initialError: IOException) {
            val alternate = (
                if (probed.fallbackUsed) null else reProbe()?.takeIf { it != probed.location }
                ) ?: throw initialError
            if (alternate.isReaderStagingEntry()) throw initialError
            emitIo(DownloadIoOperation.PARTIAL_PAGE_OPEN, leaseSource, candidate)
            return try {
                open(alternate)
            } catch (alternateError: IOException) {
                alternateError.addSuppressed(initialError)
                throw alternateError
            }
        }
    }

    private fun open(location: PartialPageReadLocation): OpenedPartialPage = when (location) {
        is PartialPageReadLocation.FilePage -> OpenedPartialPage(
            location = location,
            input = location.file.inputStream(),
        )
        is PartialPageReadLocation.CbzEntry -> {
            val archive = ZipFile(location.archive)
            try {
                val entry = archive.getEntry(location.entryName)
                    ?.takeUnless { it.isDirectory }
                    ?: throw IOException("Published CBZ entry is unavailable: ${location.entryName}")
                OpenedPartialPage(
                    location = location,
                    input = archive.getInputStream(entry),
                    owner = archive,
                )
            } catch (error: Exception) {
                archive.close()
                throw error
            }
        }
    }

    private fun emitIo(
        operation: DownloadIoOperation,
        leaseSource: PartialPageReadLeaseSource,
        candidate: PartialReaderPageCandidate,
    ) {
        if (!ioProbe.enabled) return
        ioProbe.onIo(
            DownloadIoEvent(
                operation = operation,
                locks = DownloadLockState(
                    queueStateLocked = false,
                    indexLocked = false,
                    coordinatorLocked = (leaseSource as? mihon.desktop.download.PartialDownloadArtifactLifecycleCoordinator)
                        ?.holdsLockByCurrentThread() == true,
                    lifecycleLocked = false,
                ),
                page = DownloadIoPageIdentity(
                    attemptGeneration = candidate.attemptGeneration,
                    readerOrdinal = candidate.readerOrdinal,
                    sourcePageIndex = candidate.sourcePageIndex,
                    committedRevision = candidate.committedRevision,
                ),
            ),
        )
    }

    private data class ProbedPartialPage(
        val location: PartialPageReadLocation,
        val expectedBytes: Long,
        val fallbackUsed: Boolean,
    )

    private class OpenedPartialPage(
        val location: PartialPageReadLocation,
        val input: java.io.InputStream,
        private val owner: Closeable? = null,
    ) : Closeable {
        override fun close() {
            runCatching(input::close)
            owner?.close()
        }
    }

    private fun PartialPageReadLocation.isReaderStagingEntry(): Boolean = when (this) {
        is PartialPageReadLocation.FilePage -> file.name.isReaderStagingFileName()
        is PartialPageReadLocation.CbzEntry -> entryName.isReaderStagingFileName()
    }

    private fun PartialPageReadLocation.displayFile(): File = when (this) {
        is PartialPageReadLocation.FilePage -> file
        is PartialPageReadLocation.CbzEntry -> archive
    }

    private fun String.isReaderStagingFileName(): Boolean =
        endsWith(".tmp", ignoreCase = true) || endsWith(".part", ignoreCase = true)
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
