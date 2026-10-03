package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import logcat.LogPriority
import mihon.domain.reader.content.DownloadArtifactNamingPolicy
import mihon.domain.reader.content.DownloadChapterIdentity
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

/**
 * This class is used to provide the directories where the downloads should be saved.
 * It uses the following path scheme: /<root downloads dir>/<source name>/<manga>/<chapter>
 *
 * @param context the application context.
 */
class DownloadProvider(
    private val context: Context,
    private val storageManager: StorageManager = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
) {

    private val downloadsDir: UniFile?
        get() = storageManager.getDownloadsDirectory()

    /** Finite directory-sync rename, with retryable refusal and no queue mutation or target overwrite. */
    internal fun renameDirectoryChapter(
        source: Source,
        phase: tachiyomi.domain.chapter.service.ChapterDirectoryPhase,
        change: tachiyomi.domain.chapter.service.DirectoryFileChange,
    ) {
        check(source.id == phase.effects.sourceId)
        val root = downloadsDir ?: throw IOException("Download storage is unavailable")
        fun identity(
            chapter: tachiyomi.domain.chapter.service.DirectoryFileChapter,
            title: String,
        ) = DownloadChapterIdentity(
            phase.effects.sourceName,
            title,
            chapter.name,
            chapter.scanlator,
            chapter.url,
            phase.effects.disallowNonAsciiFilenames,
        )
        val oldIdentity = identity(change.before, phase.effects.mangaTitle)
        val newIdentity = identity(change.after, phase.currentTitle)
        val sourceDirectory = root.findFile(DownloadArtifactNamingPolicy.sourceDirectoryName(oldIdentity)) ?: return
        val previousName = DownloadArtifactNamingPolicy.mangaDirectoryName(oldIdentity)
        val currentName = DownloadArtifactNamingPolicy.mangaDirectoryName(newIdentity)
        val previous = sourceDirectory.findFile(previousName)
        val current = sourceDirectory.findFile(currentName)
        val mangaDirectory = previous ?: current ?: return
        if (previous != null && previousName != currentName) {
            check(current == null || current.uri == previous.uri) {
                "A different manga download occupies the target directory"
            }
            check(previous.renameTo(currentName)) { "Unable to rename downloaded manga directory" }
        }
        val directory = sourceDirectory.findFile(currentName) ?: mangaDirectory
        val oldDownload = DownloadArtifactNamingPolicy.chapterCandidates(oldIdentity)
            .asSequence().mapNotNull { directory.findFile(it.name) }.firstOrNull() ?: return
        val newName = DownloadArtifactNamingPolicy.currentChapterName(newIdentity) +
            if (oldDownload.isFile && oldDownload.name.orEmpty().endsWith(".cbz", true)) ".cbz" else ""
        if (oldDownload.name == newName) return
        check(directory.findFile(newName) == null) { "A different chapter download occupies the target directory" }
        check(oldDownload.renameTo(newName)) { "Unable to rename downloaded chapter directory" }
    }

    /**
     * Returns the download directory for a manga. For internal use only.
     *
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the manga.
     */
    internal fun getMangaDir(mangaTitle: String, source: Source): Result<UniFile> {
        val downloadsDir = downloadsDir
        if (downloadsDir == null) {
            logcat(LogPriority.ERROR) { "Failed to create download directory" }
            return Result.failure(
                IOException(context.stringResource(MR.strings.storage_failed_to_create_download_directory)),
            )
        }

        val sourceDirName = getSourceDirName(source)
        val sourceDir = downloadsDir.createDirectory(sourceDirName)
        if (sourceDir == null) {
            val displayablePath = downloadsDir.displayablePath + "/$sourceDirName"
            logcat(LogPriority.ERROR) { "Failed to create source download directory: $displayablePath" }
            return Result.failure(
                IOException(context.stringResource(MR.strings.storage_failed_to_create_directory, displayablePath)),
            )
        }

        val mangaDirName = getMangaDirName(mangaTitle)
        val mangaDir = sourceDir.createDirectory(mangaDirName)
        if (mangaDir == null) {
            val displayablePath = sourceDir.displayablePath + "/$mangaDirName"
            logcat(LogPriority.ERROR) { "Failed to create manga download directory: $displayablePath" }
            return Result.failure(
                IOException(context.stringResource(MR.strings.storage_failed_to_create_directory, displayablePath)),
            )
        }

        return Result.success(mangaDir)
    }

    /**
     * Returns the download directory for a source if it exists.
     *
     * @param source the source to query.
     */
    fun findSourceDir(source: Source): UniFile? {
        return downloadsDir?.findFile(getSourceDirName(source))
    }

    /**
     * Returns the download directory for a manga if it exists.
     *
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the manga.
     */
    fun findMangaDir(mangaTitle: String, source: Source): UniFile? {
        val sourceDir = findSourceDir(source)
        return sourceDir?.findFile(getMangaDirName(mangaTitle))
    }

    /**
     * Returns the download directory for a chapter if it exists.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the chapter.
     */
    fun findChapterDir(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        source: Source,
    ): UniFile? {
        val mangaDir = findMangaDir(mangaTitle, source)
        return getValidChapterDirNames(chapterName, chapterScanlator, chapterUrl).asSequence()
            .mapNotNull { mangaDir?.findFile(it) }
            .firstOrNull()
    }

    /**
     * Returns a list of downloaded directories for the chapters that exist.
     *
     * @param chapters the chapters to query.
     * @param manga the manga of the chapter.
     * @param source the source of the chapter.
     */
    fun findChapterDirs(chapters: List<Chapter>, manga: Manga, source: Source): Pair<UniFile?, List<UniFile>> {
        val mangaDir = findMangaDir(manga.title, source) ?: return null to emptyList()
        return mangaDir to chapters.mapNotNull { chapter ->
            getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url).asSequence()
                .mapNotNull { mangaDir.findFile(it) }
                .firstOrNull()
        }
    }

    /**
     * Returns the download directory name for a source.
     *
     * @param source the source to query.
     */
    fun getSourceDirName(source: Source): String {
        return DownloadArtifactNamingPolicy.validFilename(
            originalName = source.toString(),
            disallowNonAscii = libraryPreferences.disallowNonAsciiFilenames().get(),
        )
    }

    /**
     * Returns the download directory name for a manga.
     *
     * @param mangaTitle the title of the manga to query.
     */
    fun getMangaDirName(mangaTitle: String): String {
        return DownloadArtifactNamingPolicy.validFilename(
            originalName = mangaTitle,
            disallowNonAscii = libraryPreferences.disallowNonAsciiFilenames().get(),
        )
    }

    /**
     * Returns the chapter directory name for a chapter.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query.
     * @param chapterUrl url of the chapter to query.
     */
    fun getChapterDirName(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        disallowNonAsciiFilenames: Boolean = libraryPreferences.disallowNonAsciiFilenames().get(),
    ): String = DownloadArtifactNamingPolicy.currentChapterName(
        downloadIdentity(chapterName, chapterScanlator, chapterUrl, disallowNonAsciiFilenames),
    )

    fun isChapterDirNameChanged(oldChapter: Chapter, newChapter: Chapter): Boolean {
        return getChapterDirName(oldChapter.name, oldChapter.scanlator, oldChapter.url) !=
            getChapterDirName(newChapter.name, newChapter.scanlator, newChapter.url)
    }

    /**
     * Returns valid downloaded chapter directory names.
     *
     * @param chapter the domain chapter object.
     */
    fun getValidChapterDirNames(chapterName: String, chapterScanlator: String?, chapterUrl: String): List<String> {
        return DownloadArtifactNamingPolicy.chapterCandidates(
            downloadIdentity(
                chapterName = chapterName,
                chapterScanlator = chapterScanlator,
                chapterUrl = chapterUrl,
                disallowNonAsciiFilenames = libraryPreferences.disallowNonAsciiFilenames().get(),
            ),
        ).map { it.name }
    }

    private fun downloadIdentity(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        disallowNonAsciiFilenames: Boolean,
    ) = DownloadChapterIdentity(
        sourceDisplayName = "",
        mangaTitle = "",
        chapterName = chapterName,
        scanlator = chapterScanlator,
        chapterUrl = chapterUrl,
        disallowNonAsciiFilenames = disallowNonAsciiFilenames,
    )
}
