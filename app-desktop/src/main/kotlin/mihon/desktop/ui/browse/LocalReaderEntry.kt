package mihon.desktop.ui.browse

import mihon.desktop.ui.reader.DesktopReaderScreen
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Builds the real local-reader entry with a deterministic identity derived from its canonical path. */
internal fun localReaderScreen(
    chapterFile: File,
    mangaName: String,
    chapterTitle: String,
): DesktopReaderScreen {
    val canonicalFile = runCatching { chapterFile.canonicalFile }.getOrElse { chapterFile.absoluteFile }
    val canonicalPath = canonicalFile.path
    return DesktopReaderScreen(
        chapterTitle = chapterTitle,
        mangaTitle = mangaName,
        sourceId = LOCAL_SOURCE_ID,
        chapterUrl = canonicalPath,
        chapterId = localReaderChapterId(canonicalPath),
        localChapterPath = canonicalPath,
    )
}

private fun localReaderChapterId(canonicalPath: String): Long {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("mihon-desktop-local-chapter-v1\u0000$canonicalPath".toByteArray(StandardCharsets.UTF_8))
    var value = 0L
    repeat(Long.SIZE_BYTES) { index -> value = (value shl 8) or (digest[index].toLong() and 0xffL) }
    value = value and Long.MAX_VALUE
    return value.takeUnless { it == 0L } ?: 1L
}

private const val LOCAL_SOURCE_ID = 0L
