package mihon.desktop.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import mihon.domain.reader.content.DownloadArtifactCandidate
import mihon.domain.reader.content.DownloadArtifactKind
import mihon.domain.reader.content.DownloadArtifactLocator
import mihon.domain.reader.content.DownloadArtifactLookup
import mihon.domain.reader.content.DownloadArtifactMatch
import mihon.domain.reader.content.DownloadArtifactNamingPolicy
import mihon.domain.reader.content.DownloadArtifactProbe
import mihon.domain.reader.content.DownloadChapterIdentity
import mihon.domain.reader.content.ReaderImageCandidatePolicy
import mihon.domain.reader.content.ReaderImageSortMode
import java.io.File
import java.io.FileNotFoundException

/** One opened removal session, including legacy artifacts without a SQL chapter. */
data class CapturedDownloadFiles(
    val pendingArtifacts: MutableSet<java.io.File>,
    val pendingAttempts: MutableList<mihon.desktop.download.CapturedDownloadAttempt>,
    val queuedArtifacts: Map<Long, Set<java.io.File>>,
    val initialArtifactCount: Int = pendingArtifacts.size,
    var succeeded: Int = 0,
    var skipped: Int = 0,
)

data class CapturedDownloadDeletionResult(
    val failedArtifacts: List<java.io.File>,
    val succeeded: Int,
    val skipped: Int,
    val refusedAttempts: Set<Long> = emptySet(),
)

private val ILLEGAL_CHARS = Regex("""[/\\:*?"<>|]""")
private fun sanitize(name: String): String =
    ILLEGAL_CHARS.replace(name.trim(), "_").take(200)

/**
 * Resolves and queries the on-disk download directory structure:
 *   `<baseDir>/<sourceId>/<mangaTitle>/<chapterName>/`
 *
 * Follows Android Mihon's convention: in-progress downloads use a `_tmp`
 * suffix on the chapter directory. Only directories without the suffix
 * are considered "downloaded".
 */
class DesktopDownloadProvider(
    private val baseDir: File,
    private val directoryLister: (File) -> Array<File>? = File::listFiles,
) {
    private val _availabilityRevision = MutableStateFlow(0L)
    val availabilityRevision = _availabilityRevision.asStateFlow()

    internal fun notifyAvailabilityChanged() {
        _availabilityRevision.update { it + 1 }
    }

    companion object {
        /** Suffix appended to chapter directories while downloading (mirrors Android Downloader.TMP_DIR_SUFFIX). */
        const val TMP_DIR_SUFFIX = "_tmp"

        private val SUPPORTED_ISO_BMFF_BRANDS = setOf(
            "avif",
            "avis",
            "heic",
            "heix",
            "hevc",
            "hevx",
            "heim",
            "heis",
            "hevm",
            "hevs",
            "mif1",
            "msf1",
        )
    }

    /** Returns the final (non-tmp) chapter directory path. */
    fun chapterDownloadDir(sourceId: Long, mangaTitle: String, chapterName: String): File =
        File(baseDir, "${sanitize(sourceId.toString())}/${sanitize(mangaTitle)}/${sanitize(chapterName)}")

    /** Returns the temporary chapter directory used during downloads. */
    fun chapterTmpDir(sourceId: Long, mangaTitle: String, chapterName: String): File =
        File(
            baseDir,
            "${sanitize(sourceId.toString())}/${sanitize(mangaTitle)}/${sanitize(chapterName)}$TMP_DIR_SUFFIX",
        )

    /** Canonical upstream-style source/manga directory used by new Desktop downloads. */
    fun canonicalMangaDownloadDir(identity: DownloadChapterIdentity): File = File(
        File(baseDir, DownloadArtifactNamingPolicy.sourceDirectoryName(identity)),
        DownloadArtifactNamingPolicy.mangaDirectoryName(identity),
    )

    /** Canonical single-write chapter directory. Existing Desktop paths remain read-only fallbacks. */
    fun canonicalChapterDownloadDir(identity: DownloadChapterIdentity): File =
        File(canonicalMangaDownloadDir(identity), DownloadArtifactNamingPolicy.currentChapterName(identity))

    fun canonicalChapterTmpDir(identity: DownloadChapterIdentity): File =
        File(
            canonicalMangaDownloadDir(identity),
            DownloadArtifactNamingPolicy.currentChapterName(identity) + TMP_DIR_SUFFIX,
        )

    /** Finite canonical aliases plus the historical Desktop `_tmp` path; never scans the download tree. */
    fun partialTmpDirectoryCandidates(sourceId: Long, identity: DownloadChapterIdentity): List<File> =
        (
            DownloadArtifactNamingPolicy.chapterCandidates(identity)
                .asSequence()
                .filter { it.kind == DownloadArtifactKind.DIRECTORY }
                .map { candidate -> File(canonicalMangaDownloadDir(identity), candidate.name + TMP_DIR_SUFFIX) }
                .toList() +
                chapterTmpDir(sourceId, identity.mangaTitle, identity.chapterName)
            ).distinctBy(File::getAbsolutePath)

    /**
     * A chapter is considered downloaded only when the **final** directory
     * (without `_tmp` suffix) exists and contains at least one image file.
     * Temporary directories are explicitly excluded — matching Android's
     * DownloadCache behaviour.
     */
    fun isChapterDownloaded(sourceId: Long, mangaTitle: String, chapterName: String): Boolean {
        val dir = chapterDownloadDir(sourceId, mangaTitle, chapterName)
        return dir.isDirectory && dir.listFiles()?.any { it.isReadableImageFile() } == true
    }

    fun isChapterDownloaded(sourceId: Long, identity: DownloadChapterIdentity): Boolean =
        downloadArtifactLookup(sourceId).locate(identity)?.let { match ->
            val artifact = File(match.opaqueLocation)
            when (match.candidate.kind) {
                DownloadArtifactKind.DIRECTORY ->
                    artifact.isDirectory && artifact.listFiles()?.any { it.isReaderImageCandidate() } == true
                DownloadArtifactKind.CBZ -> artifact.isFile && artifact.length() > 0L
            }
        } == true

    /** Returns true if a `_tmp` directory exists for this chapter (download in progress or abandoned). */
    fun isChapterDownloading(sourceId: Long, mangaTitle: String, chapterName: String): Boolean {
        val tmpDir = chapterTmpDir(sourceId, mangaTitle, chapterName)
        return tmpDir.isDirectory
    }

    fun getDownloadedPages(sourceId: Long, mangaTitle: String, chapterName: String): List<File> {
        val dir = chapterDownloadDir(sourceId, mangaTitle, chapterName)
        return getDownloadedPages(dir)
    }

    fun getDownloadedPages(directory: File): List<File> {
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles()
            ?.filter { it.isReaderImageCandidate() }
            ?.sortedWith { first, second ->
                ReaderImageCandidatePolicy.compare(
                    ReaderImageSortMode.DOWNLOAD_LEXICAL_CASE_SENSITIVE,
                    first.name,
                    second.name,
                )
            }
            ?: emptyList()
    }

    /**
     * RUA-01 compatibility adapter: keeps the existing raw chapter-name directory lookup behind
     * the shared locator seam. Canonical/legacy/CBZ dual-read remains RUA-02.
     */
    fun currentDirectoryArtifactProbe(sourceId: Long): DownloadArtifactProbe {
        var probed = false
        return DownloadArtifactProbe { identity, candidate ->
            if (probed || candidate.kind != DownloadArtifactKind.DIRECTORY) return@DownloadArtifactProbe null
            probed = true
            val directory = chapterDownloadDir(sourceId, identity.mangaTitle, identity.chapterName)
            directory.absolutePath.takeIf { directory.isDirectory }
        }
    }

    /** Maps shared candidates to Desktop files without scanning the download tree. */
    fun canonicalArtifactProbe(): DownloadArtifactProbe = DownloadArtifactProbe { identity, candidate ->
        val artifact = File(canonicalMangaDownloadDir(identity), candidate.name)
        when (candidate.kind) {
            DownloadArtifactKind.DIRECTORY -> artifact.absolutePath.takeIf { artifact.isDirectory }
            DownloadArtifactKind.CBZ -> artifact.absolutePath.takeIf { artifact.isFile }
        }
    }

    /**
     * Production dual-read lookup. Every shared canonical/legacy/non-ASCII candidate is checked first;
     * only then are the historical Desktop raw directory and sibling CBZ considered.
     */
    fun downloadArtifactLookup(
        sourceId: Long,
        candidateProbe: DownloadArtifactProbe = canonicalArtifactProbe(),
    ): DownloadArtifactLookup {
        val shared = DownloadArtifactLocator(candidateProbe)
        return DownloadArtifactLookup { identity ->
            shared.locate(identity) ?: currentDesktopArtifact(sourceId, identity)
        }
    }

    private fun currentDesktopArtifact(
        sourceId: Long,
        identity: DownloadChapterIdentity,
    ): DownloadArtifactMatch? {
        val directory = chapterDownloadDir(sourceId, identity.mangaTitle, identity.chapterName)
        if (directory.isDirectory) {
            return DownloadArtifactMatch(
                DownloadArtifactCandidate(directory.name, DownloadArtifactKind.DIRECTORY),
                directory.absolutePath,
            )
        }
        val cbz = File(directory.parentFile, "${directory.name}.cbz")
        return cbz.takeIf(File::isFile)?.let { artifact ->
            DownloadArtifactMatch(
                DownloadArtifactCandidate(artifact.name, DownloadArtifactKind.CBZ),
                artifact.absolutePath,
            )
        }
    }

    /**
     * Returns true if the manga has at least one fully-downloaded chapter.
     * Temporary (`_tmp`) directories are excluded — same rule as [isChapterDownloaded].
     */
    fun hasMangaDownloads(sourceId: Long, mangaTitle: String): Boolean {
        val mangaDir = File(baseDir, "${sanitize(sourceId.toString())}/${sanitize(mangaTitle)}")
        if (!mangaDir.isDirectory) return false
        return mangaDir.listFiles()?.any { chapterDir ->
            chapterDir.isDirectory &&
                !chapterDir.name.endsWith(TMP_DIR_SUFFIX) &&
                chapterDir.listFiles()?.any { f -> f.isReadableImageFile() } == true
        } == true
    }

    fun hasMangaDownloads(sourceId: Long, identity: DownloadChapterIdentity): Boolean {
        fun File.hasChapterArtifact(): Boolean = listFiles().orEmpty().any { artifact ->
            when {
                artifact.name.endsWith(TMP_DIR_SUFFIX) -> false
                artifact.isDirectory -> artifact.listFiles()?.any { it.isReaderImageCandidate() } == true
                artifact.isFile && artifact.extension.equals("cbz", ignoreCase = true) -> artifact.length() > 0L
                else -> false
            }
        }
        return canonicalMangaDownloadDir(identity).takeIf(File::isDirectory)?.hasChapterArtifact() == true ||
            File(baseDir, "${sanitize(sourceId.toString())}/${sanitize(identity.mangaTitle)}")
                .takeIf(File::isDirectory)
                ?.hasChapterArtifact() == true
    }

    /** Returns the number of completed chapter artifacts for the legacy Desktop layout. */
    fun downloadedChapterCount(sourceId: Long, mangaTitle: String): Int =
        countDownloadedArtifacts(
            File(baseDir, "${sanitize(sourceId.toString())}/${sanitize(mangaTitle)}"),
            readerImagesOnly = true,
        )

    /** Returns the number of completed chapter artifacts across canonical and legacy layouts. */
    fun downloadedChapterCount(sourceId: Long, identity: DownloadChapterIdentity): Int =
        listOf(
            canonicalMangaDownloadDir(identity),
            File(baseDir, "${sanitize(sourceId.toString())}/${sanitize(identity.mangaTitle)}"),
        ).distinctBy(File::getAbsolutePath).sumOf { directory ->
            countDownloadedArtifacts(directory, readerImagesOnly = false)
        }

    /** Deletes the finite canonical and historical directories for one manga only. */
    fun deleteMangaDownloads(sourceId: Long, mangaTitle: String): Boolean = try {
        deleteArtifact(File(baseDir, "${sanitize(sourceId.toString())}/${sanitize(mangaTitle)}"))
    } finally {
        notifyAvailabilityChanged()
    }

    /** Deletes the canonical and historical directories for one resolved manga identity only. */
    fun deleteMangaDownloads(sourceId: Long, mangaTitle: String, identity: DownloadChapterIdentity): Boolean = try {
        listOf(
            File(baseDir, "${sanitize(sourceId.toString())}/${sanitize(mangaTitle)}"),
            canonicalMangaDownloadDir(identity),
        ).distinctBy(File::getAbsolutePath).map(::deleteArtifact).all { it }
    } finally {
        notifyAvailabilityChanged()
    }

    /** Capture only existing chapter artifacts in the two known manga directories. */
    fun captureMangaDownloadArtifacts(
        sourceId: Long,
        mangaTitle: String,
        identity: DownloadChapterIdentity,
    ): List<File> =
        listOf(
            File(baseDir, "${sanitize(sourceId.toString())}/${sanitize(mangaTitle)}"),
            canonicalMangaDownloadDir(identity),
        ).distinctBy(File::getAbsolutePath).flatMap { directory ->
            val artifacts = if (!directory.exists()) {
                emptyArray()
            } else {
                directoryLister(directory)
                    ?: throw java.io.IOException("Unable to read download directory: ${directory.name}")
            }
            artifacts.filter { artifact ->
                (artifact.isDirectory && !artifact.name.endsWith(TMP_DIR_SUFFIX)) ||
                    (artifact.isFile && artifact.extension.equals("cbz", ignoreCase = true))
            }
        }.distinctBy(File::getAbsolutePath)

    /** Finite aliases used both by immediate chapter deletion and an opened removal session. */
    fun chapterDownloadArtifacts(sourceId: Long, identity: DownloadChapterIdentity): List<File> = buildList {
        DownloadArtifactNamingPolicy.chapterCandidates(identity).distinct().forEach { candidate ->
            add(File(canonicalMangaDownloadDir(identity), candidate.name))
        }
        val legacy = chapterDownloadDir(sourceId, identity.mangaTitle, identity.chapterName)
        add(legacy)
        add(File(legacy.parentFile, "${legacy.name}.cbz"))
    }.distinctBy(File::getAbsolutePath)

    /** Return the original paths that still need retry; never re-enumerate the manga directory. */
    fun deleteCapturedDownloadArtifacts(artifacts: Collection<File>): List<File> = try {
        val root = baseDir.toPath().toAbsolutePath().normalize()
        artifacts.filter { artifact ->
            val path = artifact.toPath().toAbsolutePath().normalize()
            !path.startsWith(root) || path == root || !deleteArtifact(artifact)
        }
    } finally {
        notifyAvailabilityChanged()
    }

    /** Returns true when a downloaded image has a supported extension and a matching file signature. */
    fun isValidDownloadedImage(file: File): Boolean = file.isReadableImageFile()

    /** Deletes the chapter download directory and all its contents. */
    fun deleteChapterDownload(sourceId: Long, mangaTitle: String, chapterName: String): Boolean {
        return try {
            deleteArtifact(chapterDownloadDir(sourceId, mangaTitle, chapterName))
        } finally {
            notifyAvailabilityChanged()
        }
    }

    /** Deletes only the finite aliases belonging to this identity; no download-tree scan or migration is performed. */
    fun deleteChapterDownload(sourceId: Long, identity: DownloadChapterIdentity): Boolean {
        val artifacts = chapterDownloadArtifacts(sourceId, identity)
        return try {
            artifacts.map(::deleteArtifact).all { it }
        } finally {
            // A failed deletion can still remove an earlier alias.
            notifyAvailabilityChanged()
        }
    }

    private fun deleteArtifact(artifact: File): Boolean =
        !artifact.exists() || (artifact.deleteRecursively() && !artifact.exists())

    private fun countDownloadedArtifacts(directory: File, readerImagesOnly: Boolean): Int {
        if (!directory.isDirectory) return 0
        return directory.listFiles().orEmpty().count { artifact ->
            when {
                artifact.name.endsWith(TMP_DIR_SUFFIX) -> false
                artifact.isDirectory -> artifact.listFiles().orEmpty().any {
                    if (readerImagesOnly) it.isReadableImageFile() else it.isReaderImageCandidate()
                }
                !readerImagesOnly && artifact.isFile &&
                    artifact.extension.equals("cbz", ignoreCase = true) -> artifact.length() > 0L
                else -> false
            }
        }
    }

    /** Deletes the temporary download directory for a chapter. */
    fun cleanupTmpDir(sourceId: Long, mangaTitle: String, chapterName: String) {
        val tmpDir = chapterTmpDir(sourceId, mangaTitle, chapterName)
        tmpDir.deleteRecursively()
    }

    fun cleanupTmpDir(identity: DownloadChapterIdentity) {
        canonicalChapterTmpDir(identity).deleteRecursively()
    }

    /**
     * Renames the `_tmp` directory to the final chapter directory name.
     * Returns true on success.
     */
    fun renameTmpToFinal(sourceId: Long, mangaTitle: String, chapterName: String): Boolean {
        val tmpDir = chapterTmpDir(sourceId, mangaTitle, chapterName)
        val finalDir = chapterDownloadDir(sourceId, mangaTitle, chapterName)
        if (!tmpDir.isDirectory) return false
        return try {
            // Remove any existing final directory first.
            finalDir.deleteRecursively()
            tmpDir.renameTo(finalDir)
        } finally {
            notifyAvailabilityChanged()
        }
    }

    private fun File.isReadableImageFile(): Boolean {
        val ext = extension.lowercase()
        if (ext !in setOf("jpg", "jpeg", "png", "webp", "gif", "avif")) return false
        if (!isFile || length() <= 0L) return false

        return hasReadableImageSignature(this)
    }

    private fun File.isReaderImageCandidate(): Boolean {
        if (!isFile) return false
        return ReaderImageCandidatePolicy.accepts(name) { hasReadableImageSignature(this) }
    }

    internal fun hasReadableImageSignature(file: File): Boolean {
        val header = try {
            file.inputStream().use { input ->
                ByteArray(32).also { bytes -> input.read(bytes) }
            }
        } catch (error: FileNotFoundException) {
            if (!file.exists()) return false
            throw error
        }

        return header.startsWith(0xFF, 0xD8) ||
            header.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) ||
            header.startsWith("GIF87a") ||
            header.startsWith("GIF89a") ||
            (header.startsWith("RIFF") && header.hasAsciiAt(8, "WEBP")) ||
            header.startsWith(0xFF, 0x0A) ||
            header.startsWith(0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87, 0x0A) ||
            header.startsWith(0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87, 0x0A) ||
            header.startsWith(0xFF, 0x4F, 0xFF, 0x51) ||
            header.hasSupportedIsoBmffBrand()
    }

    private fun ByteArray.startsWith(vararg bytes: Int): Boolean =
        bytes.withIndex().all { (index, byte) -> this.getOrNull(index) == byte.toByte() }

    private fun ByteArray.startsWith(ascii: String): Boolean = hasAsciiAt(0, ascii)

    private fun ByteArray.hasAsciiAt(offset: Int, ascii: String): Boolean =
        ascii.indices.all { index -> getOrNull(offset + index) == ascii[index].code.toByte() }

    private fun ByteArray.hasSupportedIsoBmffBrand(): Boolean {
        if (!hasAsciiAt(4, "ftyp")) return false
        return SUPPORTED_ISO_BMFF_BRANDS.any { brand ->
            hasAsciiAt(8, brand) || (16..28 step 4).any { offset -> hasAsciiAt(offset, brand) }
        }
    }
}
