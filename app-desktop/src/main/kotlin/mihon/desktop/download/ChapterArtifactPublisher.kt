package mihon.desktop.download

import java.io.File
import java.io.IOException
import java.nio.file.Files

sealed class ChapterPublishException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class ChapterPublishConflictException(
    val stagingDirectory: File,
    val existingFinal: File,
) : ChapterPublishException(
    "Existing chapter final conflicts with the complete private download: ${existingFinal.absolutePath}",
)

class ChapterAtomicPublishException(
    val stagingDirectory: File,
    val targetDirectory: File,
    cause: Throwable? = null,
) : ChapterPublishException(
    "Unable to atomically publish chapter directory: ${targetDirectory.absolutePath}",
    cause,
)

enum class ChapterDirectoryPublishOutcome {
    PUBLISHED,
    ADOPTED_IDENTICAL,
}

data class ChapterCleanupDiagnostic(
    val artifact: File,
    val cause: Throwable,
)

internal class ChapterDirectoryPublisher(
    private val fileOperations: DownloadFileOperations,
) {
    fun publish(stagingDirectory: File, finalDirectory: File): ChapterDirectoryPublishOutcome {
        if (!stagingDirectory.isDirectory) {
            throw ChapterAtomicPublishException(
                stagingDirectory,
                finalDirectory,
                IOException("Private chapter directory is unavailable"),
            )
        }
        existingOutcome(stagingDirectory, finalDirectory)?.let { return it }
        return try {
            if (!fileOperations.renameChapter(stagingDirectory, finalDirectory)) {
                throw ChapterAtomicPublishException(
                    stagingDirectory,
                    finalDirectory,
                    IOException("Atomic chapter move returned false"),
                )
            }
            if (stagingDirectory.exists() || !finalDirectory.isDirectory) {
                throw ChapterAtomicPublishException(
                    stagingDirectory,
                    finalDirectory,
                    IOException("Atomic chapter move did not publish exactly one final directory"),
                )
            }
            ChapterDirectoryPublishOutcome.PUBLISHED
        } catch (error: ChapterPublishException) {
            throw error
        } catch (error: Exception) {
            existingOutcome(stagingDirectory, finalDirectory)?.let { return it }
            throw ChapterAtomicPublishException(stagingDirectory, finalDirectory, error)
        }
    }

    private fun existingOutcome(
        stagingDirectory: File,
        finalDirectory: File,
    ): ChapterDirectoryPublishOutcome? {
        if (!finalDirectory.exists()) return null
        if (directoriesMatch(stagingDirectory, finalDirectory)) {
            return ChapterDirectoryPublishOutcome.ADOPTED_IDENTICAL
        }
        throw ChapterPublishConflictException(stagingDirectory, finalDirectory)
    }

    private fun directoriesMatch(first: File, second: File): Boolean {
        if (!first.isDirectory || !second.isDirectory) return false
        val firstFiles = first.walkTopDown()
            .filter(File::isFile)
            .associateBy { file -> file.relativeTo(first).invariantSeparatorsPath }
        val secondFiles = second.walkTopDown()
            .filter(File::isFile)
            .associateBy { file -> file.relativeTo(second).invariantSeparatorsPath }
        if (firstFiles.keys != secondFiles.keys || firstFiles.isEmpty()) return false
        return firstFiles.all { (relative, file) ->
            val other = secondFiles.getValue(relative)
            file.length() == other.length() && Files.mismatch(file.toPath(), other.toPath()) == -1L
        }
    }
}
