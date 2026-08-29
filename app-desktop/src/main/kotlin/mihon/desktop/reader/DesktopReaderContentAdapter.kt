package mihon.desktop.reader

import kotlinx.coroutines.runInterruptible
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import mihon.domain.reader.content.ReaderImageCandidatePolicy
import mihon.domain.reader.content.ReaderImageSortMode
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderPageDescriptor
import mihon.domain.reader.session.ReaderPageLoadState
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch

internal interface DesktopReaderArchiveOperationProbe {
    fun onStart()
    fun onFinish()

    companion object {
        val None = object : DesktopReaderArchiveOperationProbe {
            override fun onStart() = Unit
            override fun onFinish() = Unit
        }
    }
}

/** Desktop storage adapter for Reader page tables and lazy per-page content. */
class DesktopReaderContentAdapter internal constructor(
    private val archiveOperationProbe: DesktopReaderArchiveOperationProbe = DesktopReaderArchiveOperationProbe.None,
) : AutoCloseable {
    private val lock = Any()
    private val archiveLeases = mutableMapOf<Long, ArchiveLeaseBinding>()

    fun directoryDescriptors(
        directory: File,
        sortMode: ReaderImageSortMode,
    ): List<ReaderPageDescriptor> {
        if (!directory.isDirectory) return emptyList()
        val pages = directory.listFiles()
            .orEmpty()
            .filter { file ->
                file.isFile && ReaderImageCandidatePolicy.accepts(file.name) {
                    file.inputStream().use(DesktopReaderImageSignature::matches)
                }
            }
            .sortedWith { first, second -> ReaderImageCandidatePolicy.compare(sortMode, first.name, second.name) }
        return pages.mapIndexed(::readyFileDescriptor)
    }

    fun archiveDescriptors(
        chapterId: Long,
        archive: File,
        epub: Boolean = false,
    ): List<ReaderPageDescriptor> {
        synchronized(lock) { archiveLeases.remove(chapterId) }
            ?.lease
            ?.releaseAndAwaitClosed()
        val lease = try {
            openArchive(archive, epub)
        } catch (error: AppErrorException) {
            throw error
        } catch (error: Throwable) {
            throw AppErrorException(AppError.Storage(error))
        }
        val snapshot = try {
            lease.snapshot()
        } catch (error: Throwable) {
            lease.release()
            throw error
        }
        val descriptors = snapshot.pageNames.mapIndexed { index, name ->
            ReaderPageDescriptor(
                sourcePageIndex = index,
                url = opaquePageRef(chapterId, index, snapshot.contentFingerprint, name),
            )
        }
        synchronized(lock) {
            archiveLeases.put(
                chapterId,
                ArchiveLeaseBinding(lease, descriptors.map(ReaderPageDescriptor::url)),
            )?.lease?.release()
        }
        return descriptors
    }

    fun owns(descriptor: ReaderPageDescriptor): Boolean = descriptor.url.startsWith(OPAQUE_PREFIX)

    suspend fun copyArchivePage(
        chapterId: Long,
        pageIndex: Int,
        opaquePageRef: String,
        destination: File,
    ): Long = runInterruptible {
        val lease = synchronized(lock) {
            val binding = archiveLeases[chapterId]
                ?: error("Reader archive lease is unavailable for chapter $chapterId")
            check(binding.pageRefs.getOrNull(pageIndex) == opaquePageRef) {
                "Reader archive page generation is stale for chapter $chapterId page $pageIndex"
            }
            binding.lease
        }
        destination.parentFile?.mkdirs()
        try {
            lease.copyPage(pageIndex, destination)
            destination.length()
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    fun releaseChapter(chapterId: Long) {
        synchronized(lock) { archiveLeases.remove(chapterId) }?.lease?.release()
    }

    internal fun hasArchiveLease(chapterId: Long): Boolean = synchronized(lock) {
        chapterId in archiveLeases
    }

    override fun close() {
        val leases = synchronized(lock) { archiveLeases.values.toList().also { archiveLeases.clear() } }
        leases.forEach { it.lease.release() }
    }

    private fun openArchive(file: File, epub: Boolean): ArchiveLease {
        require(file.isFile) { "Archive is missing: ${file.absolutePath}" }
        return when {
            epub || file.extension.equals("epub", ignoreCase = true) ->
                SevenZipArchiveLease(file, archiveOperationProbe, epub = true)
            file.extension.lowercase() in ZIP_EXTENSIONS + RAR_EXTENSIONS ->
                SevenZipArchiveLease(file, archiveOperationProbe, epub = false)
            else -> error("Unsupported local archive: ${file.extension}")
        }
    }

    private fun readyFileDescriptor(index: Int, file: File): ReaderPageDescriptor {
        val ref = EncodedPageRef(file.toURI().toString())
        return ReaderPageDescriptor(
            sourcePageIndex = index,
            url = file.name,
            imageUrl = ref.value,
            encodedPageRef = ref,
            initialLoadState = ReaderPageLoadState.Ready,
        )
    }

    private fun opaquePageRef(
        chapterId: Long,
        pageIndex: Int,
        archiveFingerprint: String,
        pageName: String,
    ): String = "$OPAQUE_PREFIX$chapterId/$pageIndex/${sha256("$archiveFingerprint\u0000$pageName").take(32)}"

    private companion object {
        const val OPAQUE_PREFIX = "desktop-reader-content://"
        val ZIP_EXTENSIONS = setOf("cbz", "zip")
        val RAR_EXTENSIONS = setOf("cbr", "rar")
    }
}

private data class ArchiveSnapshot(
    val pageNames: List<String>,
    val contentFingerprint: String,
)

private data class ArchiveLeaseBinding(
    val lease: ArchiveLease,
    val pageRefs: List<String>,
)

private interface ArchiveLease {
    fun snapshot(): ArchiveSnapshot
    fun copyPage(pageIndex: Int, destination: File)
    fun release()
    fun releaseAndAwaitClosed()
}

private abstract class RefCountedArchiveLease : ArchiveLease {
    private val lifecycleLock = Any()
    private var activeOperations = 0
    private var released = false
    private var closed = false
    private val closedSignal = CountDownLatch(1)

    final override fun snapshot(): ArchiveSnapshot = withLease(::readSnapshot)

    final override fun copyPage(pageIndex: Int, destination: File) = withLease {
        copyPageContent(pageIndex, destination)
    }

    final override fun release() {
        val shouldClose = synchronized(lifecycleLock) {
            released = true
            activeOperations == 0 && !closed
        }
        if (shouldClose) closeOnce()
    }

    final override fun releaseAndAwaitClosed() {
        release()
        closedSignal.await()
    }

    protected abstract fun readSnapshot(): ArchiveSnapshot
    protected abstract fun copyPageContent(pageIndex: Int, destination: File)
    protected abstract fun closeBacking()

    private fun <T> withLease(block: () -> T): T {
        synchronized(lifecycleLock) {
            check(!released) { "Reader archive lease is released" }
            activeOperations++
        }
        return try {
            block()
        } finally {
            val shouldClose = synchronized(lifecycleLock) {
                activeOperations--
                released && activeOperations == 0 && !closed
            }
            if (shouldClose) closeOnce()
        }
    }

    private fun closeOnce() {
        val ownsClose = synchronized(lifecycleLock) {
            if (closed) false else true.also { closed = true }
        }
        if (ownsClose) {
            try {
                closeBacking()
            } finally {
                closedSignal.countDown()
            }
        }
    }
}

private class SevenZipArchiveLease(
    file: File,
    private val operationProbe: DesktopReaderArchiveOperationProbe,
    private val epub: Boolean,
) : RefCountedArchiveLease() {
    private val operationLock = Any()
    private val randomAccessFile = RandomAccessFile(file, "r")
    private val archive: IInArchive = try {
        requireNotNull(SevenZip.openInArchive(null, RandomAccessFileInStream(randomAccessFile))) {
            "Unsupported or malformed archive: ${file.name}"
        }
    } catch (error: Throwable) {
        randomAccessFile.close()
        throw error
    }
    private var pageEntries = emptyList<Pair<Int, String>>()

    override fun readSnapshot(): ArchiveSnapshot = withSerializedArchiveOperation {
        val archiveItems = buildList {
            for (index in 0 until archive.numberOfItems) {
                val isFolder = archive.getProperty(index, PropID.IS_FOLDER) as? Boolean ?: false
                val path = archive.getStringProperty(index, PropID.PATH)
                add(SevenZipItem(index, path.orEmpty(), isFolder, sevenZipItemIdentity(index, path, isFolder)))
            }
        }
        pageEntries = if (epub) {
            epubImages(archiveItems)
        } else {
            archiveItems
                .filter { item ->
                    !item.isFolder && item.path.isNotEmpty() &&
                        ReaderImageCandidatePolicy.accepts(item.path) { probe(item.index) }
                }
                .map { it.index to it.path }
                .sortedWith { first, second ->
                    ReaderImageCandidatePolicy.compare(
                        ReaderImageSortMode.LOCAL_NATURAL_CASE_INSENSITIVE,
                        first.second,
                        second.second,
                    )
                }
        }
        ArchiveSnapshot(
            pageNames = pageEntries.map(Pair<Int, String>::second),
            contentFingerprint = sha256(archiveItems.joinToString(separator = "\u0001", transform = SevenZipItem::identity)),
        )
    }

    override fun copyPageContent(pageIndex: Int, destination: File) {
        withSerializedArchiveOperation {
            val archiveIndex = pageEntries.getOrNull(pageIndex)?.first ?: error("Archive page index is missing: $pageIndex")
            destination.outputStream().buffered().use { output ->
                archive.simpleInterface.archiveItems[archiveIndex].extractSlow(ISequentialOutStream { bytes ->
                    output.write(bytes)
                    bytes.size
                })
            }
        }
    }

    override fun closeBacking() {
        archive.close()
        randomAccessFile.close()
    }

    private fun probe(index: Int): Boolean {
        val header = java.io.ByteArrayOutputStream(32)
        archive.simpleInterface.archiveItems[index].extractSlow(ISequentialOutStream { bytes ->
            if (header.size() < 32) header.write(bytes, 0, minOf(bytes.size, 32 - header.size()))
            bytes.size
        })
        return DesktopReaderImageSignature.matches(header.toByteArray().inputStream())
    }

    private fun epubImages(items: List<SevenZipItem>): List<Pair<Int, String>> {
        val container = items.findPath("META-INF/container.xml")
        val packageReference = container?.let { item ->
            Jsoup.parse(extractBytes(item.index).inputStream(), null, "", Parser.xmlParser())
                .getElementsByTag("rootfile")
                .first()
                ?.attr("full-path")
        }.takeUnless(String?::isNullOrBlank) ?: "OEBPS/content.opf"
        val packagePath = resolveArchivePath("", packageReference)
        val packageItem = requireNotNull(items.findPath(packagePath)) {
            "EPUB package document is missing: $packagePath"
        }
        val packageDocument = Jsoup.parse(
            extractBytes(packageItem.index).inputStream(),
            null,
            "",
            Parser.xmlParser(),
        )
        val manifestPages = packageDocument.select("manifest > item")
            .filter { it.attr("media-type") in EPUB_PAGE_MEDIA_TYPES }
            .associateBy(
                keySelector = { it.attr("id") },
                valueTransform = { EpubPageReference(it.attr("href"), it.attr("media-type")) },
            )
        val packageBase = packagePath.normalizedParentPath()
        return buildList {
            packageDocument.select("spine > itemref")
                .mapNotNull { manifestPages[it.attr("idref")] }
                .forEach { pageReference ->
                    val pagePath = resolveArchivePath(packageBase, pageReference.href)
                    val pageItem = requireNotNull(items.findPath(pagePath)) { "EPUB page is missing: $pagePath" }
                    val document = if (pageReference.mediaType == "image/svg+xml") {
                        Jsoup.parse(extractBytes(pageItem.index).inputStream(), null, "", Parser.xmlParser())
                    } else {
                        Jsoup.parse(extractBytes(pageItem.index).inputStream(), null, "")
                    }
                    val imageBase = pagePath.normalizedParentPath()
                    document.allElements.forEach { element ->
                        val relative = when (element.tagName()) {
                            "img" -> element.attr("src")
                            "image" -> element.attr("href").ifBlank { element.attr("xlink:href") }
                            else -> ""
                        }
                        if (relative.isNotBlank()) {
                            val imagePath = resolveArchivePath(imageBase, relative)
                            val imageItem = requireNotNull(items.findPath(imagePath)) {
                                "EPUB image is missing: $imagePath"
                            }
                            add(imageItem.index to imageItem.path)
                        }
                    }
                }
        }
    }

    private fun extractBytes(index: Int): ByteArray = java.io.ByteArrayOutputStream().also { output ->
        archive.simpleInterface.archiveItems[index].extractSlow(ISequentialOutStream { bytes ->
            output.write(bytes)
            bytes.size
        })
    }.toByteArray()

    private fun List<SevenZipItem>.findPath(path: String): SevenZipItem? {
        val normalized = path.replace('\\', '/').removePrefix("/")
        return firstOrNull { it.path.replace('\\', '/').removePrefix("/") == normalized }
    }

    private fun resolveArchivePath(base: String, relative: String): String {
        val normalizedReference = relative.archiveReferencePath()
        val startsAtRoot = normalizedReference.startsWith('/')
        return buildList {
            if (!startsAtRoot) addAll(base.replace('\\', '/').split('/').filter(String::isNotEmpty))
            normalizedReference.removePrefix("/").split('/').forEach { part ->
                when (part) {
                    "", "." -> Unit
                    ".." -> {
                        check(isNotEmpty()) { "EPUB reference escapes the archive root: $relative" }
                        removeAt(lastIndex)
                    }
                    else -> add(part)
                }
            }
        }.joinToString("/")
    }

    private fun String.archiveReferencePath(): String {
        val normalized = replace('\\', '/')
        val uri = runCatching { URI(normalized) }.getOrElse {
            URI(null, null, normalized.substringBefore('#').substringBefore('?'), null)
        }
        require(uri.scheme == null && uri.authority == null) { "External EPUB reference is unsupported: $this" }
        return uri.path.orEmpty()
    }

    private fun String.normalizedParentPath(): String = replace('\\', '/').substringBeforeLast('/', "")

    private fun sevenZipItemIdentity(index: Int, path: String?, isFolder: Boolean): String = listOf(
        path.orEmpty(),
        isFolder.toString(),
        archive.getProperty(index, PropID.SIZE).stableIdentity(),
        archive.getProperty(index, PropID.PACKED_SIZE).stableIdentity(),
        archive.getProperty(index, PropID.CRC).stableIdentity(),
        archive.getProperty(index, PropID.METHOD).stableIdentity(),
    ).joinToString(separator = "\u0000")

    private fun <T> withSerializedArchiveOperation(block: () -> T): T = synchronized(operationLock) {
        operationProbe.onStart()
        try {
            block()
        } finally {
            operationProbe.onFinish()
        }
    }

    private data class SevenZipItem(
        val index: Int,
        val path: String,
        val isFolder: Boolean,
        val identity: String,
    )

    private data class EpubPageReference(
        val href: String,
        val mediaType: String,
    )

    private companion object {
        val EPUB_PAGE_MEDIA_TYPES = setOf("application/xhtml+xml", "image/svg+xml")
    }
}

private fun Any?.stableIdentity(): String = when (this) {
    null -> ""
    is ByteArray -> joinToString(separator = "") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
    else -> toString()
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString(separator = "") { byte -> byte.toUByte().toString(16).padStart(2, '0') }

internal object DesktopReaderImageSignature {
    private val isoBmffBrands = setOf("avif", "avis", "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1")

    fun matches(input: InputStream): Boolean {
        val header = ByteArray(32)
        input.read(header)
        return header.startsWith(0xFF, 0xD8) ||
            header.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) ||
            header.hasAsciiAt(0, "GIF87a") ||
            header.hasAsciiAt(0, "GIF89a") ||
            (header.hasAsciiAt(0, "RIFF") && header.hasAsciiAt(8, "WEBP")) ||
            header.startsWith(0xFF, 0x0A) ||
            header.startsWith(0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87, 0x0A) ||
            header.startsWith(0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87, 0x0A) ||
            header.startsWith(0xFF, 0x4F, 0xFF, 0x51) ||
            (header.hasAsciiAt(4, "ftyp") && isoBmffBrands.any { brand ->
                header.hasAsciiAt(8, brand) || (16..28 step 4).any { header.hasAsciiAt(it, brand) }
            })
    }

    private fun ByteArray.startsWith(vararg bytes: Int): Boolean =
        bytes.withIndex().all { (index, byte) -> getOrNull(index) == byte.toByte() }

    private fun ByteArray.hasAsciiAt(offset: Int, ascii: String): Boolean =
        ascii.indices.all { index -> getOrNull(offset + index) == ascii[index].code.toByte() }
}
