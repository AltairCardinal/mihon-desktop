package mihon.domain.reader.content

import eu.kanade.tachiyomi.util.lang.Hash.md5

enum class ReaderChapterRoute {
    DOWNLOAD,
    LOCAL_DIRECTORY,
    LOCAL_ARCHIVE,
    LOCAL_EPUB,
    ONLINE,
    MISSING_SOURCE,
    UNSUPPORTED,
}

enum class ReaderSourceContentKind {
    LOCAL_DIRECTORY,
    LOCAL_ARCHIVE,
    LOCAL_EPUB,
    ONLINE,
    MISSING_SOURCE,
    UNSUPPORTED,
}

fun interface ReaderChapterRouteResolver {
    fun resolve(downloadLocated: Boolean, sourceKind: ReaderSourceContentKind): ReaderChapterRoute
}

object ReaderChapterContentResolver : ReaderChapterRouteResolver {
    override fun resolve(downloadLocated: Boolean, sourceKind: ReaderSourceContentKind): ReaderChapterRoute {
        if (downloadLocated) return ReaderChapterRoute.DOWNLOAD
        return when (sourceKind) {
            ReaderSourceContentKind.LOCAL_DIRECTORY -> ReaderChapterRoute.LOCAL_DIRECTORY
            ReaderSourceContentKind.LOCAL_ARCHIVE -> ReaderChapterRoute.LOCAL_ARCHIVE
            ReaderSourceContentKind.LOCAL_EPUB -> ReaderChapterRoute.LOCAL_EPUB
            ReaderSourceContentKind.ONLINE -> ReaderChapterRoute.ONLINE
            ReaderSourceContentKind.MISSING_SOURCE -> ReaderChapterRoute.MISSING_SOURCE
            ReaderSourceContentKind.UNSUPPORTED -> ReaderChapterRoute.UNSUPPORTED
        }
    }
}

data class DownloadChapterIdentity(
    val sourceDisplayName: String,
    val mangaTitle: String,
    val chapterName: String,
    val scanlator: String?,
    val chapterUrl: String,
    val disallowNonAsciiFilenames: Boolean,
)

enum class DownloadArtifactKind {
    DIRECTORY,
    CBZ,
}

data class DownloadArtifactCandidate(
    val name: String,
    val kind: DownloadArtifactKind,
)

data class DownloadArtifactMatch(
    val candidate: DownloadArtifactCandidate,
    val opaqueLocation: String,
)

fun interface DownloadArtifactProbe {
    fun probe(identity: DownloadChapterIdentity, candidate: DownloadArtifactCandidate): String?
}

class DownloadArtifactLocator(
    private val probe: DownloadArtifactProbe,
) {
    fun locate(identity: DownloadChapterIdentity): DownloadArtifactMatch? =
        DownloadArtifactNamingPolicy.chapterCandidates(identity).firstNotNullOfOrNull { candidate ->
            probe.probe(identity, candidate)?.let { DownloadArtifactMatch(candidate, it) }
        }
}

/** Pure filename and candidate policy extracted from Android Mihon's DownloadProvider. */
object DownloadArtifactNamingPolicy {
    const val MAX_FILE_NAME_BYTES = 240
    private const val CHAPTER_SUFFIX_BYTES = 11 // underscore + six-char hash + .cbz

    fun sourceDirectoryName(identity: DownloadChapterIdentity): String =
        validFilename(identity.sourceDisplayName, disallowNonAscii = identity.disallowNonAsciiFilenames)

    fun mangaDirectoryName(identity: DownloadChapterIdentity): String =
        validFilename(identity.mangaTitle, disallowNonAscii = identity.disallowNonAsciiFilenames)

    fun currentChapterName(identity: DownloadChapterIdentity): String = buildChapterName(
        identity = identity,
        disallowNonAscii = identity.disallowNonAsciiFilenames,
    )

    fun chapterCandidates(identity: DownloadChapterIdentity): List<DownloadArtifactCandidate> {
        val current = currentChapterName(identity)
        val legacy = validFilename(identity.chapterNameWithScanlator())
        val alternateNonAscii = buildChapterName(
            identity = identity,
            disallowNonAscii = !identity.disallowNonAsciiFilenames,
        )
        return buildList(6) {
            addArtifactPair(current)
            addArtifactPair(legacy)
            addArtifactPair(alternateNonAscii)
        }
    }

    fun validFilename(
        originalName: String,
        maxBytes: Int = MAX_FILE_NAME_BYTES,
        disallowNonAscii: Boolean = false,
    ): String {
        val name = originalName.trim('.', ' ')
        if (name.isEmpty()) return "(invalid)"
        val sanitized = buildString(name.length) {
            name.forEach { character ->
                when {
                    disallowNonAscii && character >= '\u0080' -> append(
                        character.toString().encodeToByteArray().joinToString(separator = "") { byte ->
                            byte.toUByte().toString(radix = 16).padStart(2, '0')
                        },
                    )
                    character.isValidFatFilenameCharacter() -> append(character)
                    else -> append('_')
                }
            }
        }
        return sanitized.truncateUtf8(maxBytes)
    }

    private fun buildChapterName(identity: DownloadChapterIdentity, disallowNonAscii: Boolean): String {
        val base = validFilename(
            originalName = identity.chapterNameWithScanlator(),
            maxBytes = MAX_FILE_NAME_BYTES - CHAPTER_SUFFIX_BYTES,
            disallowNonAscii = disallowNonAscii,
        )
        return "${base}_${md5(identity.chapterUrl).take(6)}"
    }

    private fun DownloadChapterIdentity.chapterNameWithScanlator(): String {
        val safeChapterName = chapterName.ifBlank { "Chapter" }
        return scanlator?.takeUnless(String::isBlank)?.let { "${it}_$safeChapterName" } ?: safeChapterName
    }

    private fun MutableList<DownloadArtifactCandidate>.addArtifactPair(baseName: String) {
        add(DownloadArtifactCandidate(baseName, DownloadArtifactKind.DIRECTORY))
        add(DownloadArtifactCandidate("$baseName.cbz", DownloadArtifactKind.CBZ))
    }

    private fun Char.isValidFatFilenameCharacter(): Boolean = when {
        this in '\u0000'..'\u001f' -> false
        this in setOf('"', '*', '/', ':', '<', '>', '?', '\\', '|', '\u007f') -> false
        else -> true
    }

    private fun String.truncateUtf8(maxBytes: Int): String {
        if (encodeToByteArray().size <= maxBytes) return this
        val result = StringBuilder(length)
        var byteCount = 0
        var index = 0
        while (index < length) {
            val end = if (
                this[index].isHighSurrogate() &&
                index + 1 < length &&
                this[index + 1].isLowSurrogate()
            ) {
                index + 2
            } else {
                index + 1
            }
            val character = substring(index, end)
            val characterBytes = character.encodeToByteArray().size
            if (byteCount + characterBytes > maxBytes) break
            result.append(character)
            byteCount += characterBytes
            index = end
        }
        return result.toString()
    }
}

enum class ReaderImageSortMode {
    DOWNLOAD_LEXICAL_CASE_SENSITIVE,
    LOCAL_NATURAL_CASE_INSENSITIVE,
}

object ReaderImageCandidatePolicy {
    private val knownExtensions = setOf("avif", "gif", "heif", "jpg", "jp2", "jpx", "jxl", "png", "webp")

    fun requiresContentProbe(name: String): Boolean = name.substringAfterLast('.') !in knownExtensions

    fun accepts(name: String, contentProbe: () -> Boolean): Boolean =
        !requiresContentProbe(name) || contentProbe()

    fun order(mode: ReaderImageSortMode, names: List<String>): List<String> =
        names.sortedWith { left, right -> compare(mode, left, right) }

    fun compare(mode: ReaderImageSortMode, left: String, right: String): Int = when (mode) {
        ReaderImageSortMode.DOWNLOAD_LEXICAL_CASE_SENSITIVE -> left.compareTo(right)
        ReaderImageSortMode.LOCAL_NATURAL_CASE_INSENSITIVE -> compareNaturalCaseInsensitive(left, right)
    }

    private fun compareNaturalCaseInsensitive(left: String, right: String): Int {
        var leftIndex = 0
        var rightIndex = 0
        while (leftIndex < left.length && rightIndex < right.length) {
            val leftCharacter = left[leftIndex]
            val rightCharacter = right[rightIndex]
            val leftIsDigit = leftCharacter.isAsciiDigit()
            val rightIsDigit = rightCharacter.isAsciiDigit()
            if (leftIsDigit != rightIsDigit) return if (leftIsDigit) -1 else 1

            if (leftIsDigit) {
                var leftValue = 0UL
                while (leftIndex < left.length && left[leftIndex].isAsciiDigit()) {
                    leftValue = leftValue * 10UL + (left[leftIndex] - '0').toULong()
                    leftIndex++
                }
                var rightValue = 0UL
                while (rightIndex < right.length && right[rightIndex].isAsciiDigit()) {
                    rightValue = rightValue * 10UL + (right[rightIndex] - '0').toULong()
                    rightIndex++
                }
                val comparison = leftValue.compareTo(rightValue)
                if (comparison != 0) return comparison
                continue
            }

            val comparison = leftCharacter.lowercaseChar().compareTo(rightCharacter.lowercaseChar())
            if (comparison != 0) return comparison
            leftIndex++
            rightIndex++
        }
        return when {
            leftIndex < left.length -> 1
            rightIndex < right.length -> -1
            else -> 0
        }
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}
