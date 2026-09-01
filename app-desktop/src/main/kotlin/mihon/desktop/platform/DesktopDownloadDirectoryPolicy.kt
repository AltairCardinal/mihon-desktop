package mihon.desktop.platform

import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

enum class DesktopDownloadDirectoryAvailability {
    UNKNOWN,
    AVAILABLE,
    MISSING,
    NOT_DIRECTORY,
    NOT_WRITABLE,
    INVALID_SYNTAX,
}

data class DesktopDownloadDirectoryState(
    val defaultDirectory: File,
    val configuredDirectory: File?,
    val activeDirectory: File,
    val pendingDirectory: File,
    val availability: DesktopDownloadDirectoryAvailability,
    val restartRequired: Boolean,
    val cause: Throwable? = null,
)

sealed interface DesktopDownloadDirectorySelection {
    data class ValidCustom(val directory: File) : DesktopDownloadDirectorySelection

    data class UseDefault(val directory: File) : DesktopDownloadDirectorySelection

    data class Invalid(
        val availability: DesktopDownloadDirectoryAvailability,
        val cause: Throwable? = null,
    ) : DesktopDownloadDirectorySelection
}

fun interface DesktopDownloadDirectoryProbe {
    fun inspect(directory: Path): DesktopDownloadDirectoryProbeResult
}

data class DesktopDownloadDirectoryProbeResult(
    val availability: DesktopDownloadDirectoryAvailability,
    val cause: Throwable? = null,
) {
    companion object {
        fun available() = DesktopDownloadDirectoryProbeResult(DesktopDownloadDirectoryAvailability.AVAILABLE)
    }
}

/**
 * Pure startup path resolution plus an explicitly invoked bounded storage probe.
 *
 * [resolveStartup] never touches the configured filesystem path. Callers may invoke
 * [validateSelection] from an IO dispatcher when the user explicitly selects a path.
 */
class DesktopDownloadDirectoryPolicy(
    private val probe: DesktopDownloadDirectoryProbe = NioDesktopDownloadDirectoryProbe(),
) {

    fun resolveStartup(defaultDirectory: File, configuredPath: String?): DesktopDownloadDirectoryState {
        val normalizedDefault = defaultDirectory.toPath().toAbsolutePath().normalize().toFile()
        if (configuredPath == null) {
            return DesktopDownloadDirectoryState(
                defaultDirectory = normalizedDefault,
                configuredDirectory = null,
                activeDirectory = normalizedDefault,
                pendingDirectory = normalizedDefault,
                availability = DesktopDownloadDirectoryAvailability.UNKNOWN,
                restartRequired = false,
            )
        }

        return when (val parsed = parseAbsolute(configuredPath)) {
            is ParsedPath.Valid -> DesktopDownloadDirectoryState(
                defaultDirectory = normalizedDefault,
                configuredDirectory = parsed.path.toFile(),
                activeDirectory = parsed.path.toFile(),
                pendingDirectory = parsed.path.toFile(),
                availability = DesktopDownloadDirectoryAvailability.UNKNOWN,
                restartRequired = false,
            )

            is ParsedPath.Invalid -> DesktopDownloadDirectoryState(
                defaultDirectory = normalizedDefault,
                configuredDirectory = null,
                activeDirectory = normalizedDefault,
                pendingDirectory = normalizedDefault,
                availability = DesktopDownloadDirectoryAvailability.INVALID_SYNTAX,
                restartRequired = false,
                cause = parsed.cause,
            )
        }
    }

    fun validateSelection(rawPath: String): DesktopDownloadDirectorySelection {
        val path = when (val parsed = parseAbsolute(rawPath)) {
            is ParsedPath.Valid -> parsed.path
            is ParsedPath.Invalid -> {
                return DesktopDownloadDirectorySelection.Invalid(
                    availability = DesktopDownloadDirectoryAvailability.INVALID_SYNTAX,
                    cause = parsed.cause,
                )
            }
        }

        val result = try {
            probe.inspect(path)
        } catch (error: Exception) {
            DesktopDownloadDirectoryProbeResult(
                availability = DesktopDownloadDirectoryAvailability.NOT_WRITABLE,
                cause = error,
            )
        }
        return if (result.availability == DesktopDownloadDirectoryAvailability.AVAILABLE) {
            DesktopDownloadDirectorySelection.ValidCustom(path.toFile())
        } else {
            DesktopDownloadDirectorySelection.Invalid(result.availability, result.cause)
        }
    }

    fun useDefault(defaultDirectory: File): DesktopDownloadDirectorySelection.UseDefault {
        return DesktopDownloadDirectorySelection.UseDefault(
            defaultDirectory.toPath().toAbsolutePath().normalize().toFile(),
        )
    }

    private fun parseAbsolute(rawPath: String): ParsedPath {
        return try {
            val path = Paths.get(rawPath)
            if (!path.isAbsolute) {
                ParsedPath.Invalid(IllegalArgumentException("Download directory must be absolute"))
            } else {
                ParsedPath.Valid(path.toAbsolutePath().normalize())
            }
        } catch (error: InvalidPathException) {
            ParsedPath.Invalid(error)
        }
    }

    private sealed interface ParsedPath {
        data class Valid(val path: Path) : ParsedPath
        data class Invalid(val cause: Throwable) : ParsedPath
    }
}

internal interface DesktopDownloadDirectoryFileOperations {
    fun exists(path: Path): Boolean
    fun isDirectory(path: Path): Boolean
    fun isReadable(path: Path): Boolean
    fun isWritable(path: Path): Boolean
    fun createDirectory(path: Path)
    fun createProbeFile(directory: Path): Path
    fun write(path: Path, content: ByteArray)
    fun read(path: Path): ByteArray
    fun delete(path: Path)
    fun deleteIfExists(path: Path): Boolean
}

internal object NioDesktopDownloadDirectoryFileOperations : DesktopDownloadDirectoryFileOperations {
    override fun exists(path: Path): Boolean = Files.exists(path)

    override fun isDirectory(path: Path): Boolean = Files.isDirectory(path)

    override fun isReadable(path: Path): Boolean = Files.isReadable(path)

    override fun isWritable(path: Path): Boolean = Files.isWritable(path)

    override fun createDirectory(path: Path) {
        Files.createDirectory(path)
    }

    override fun createProbeFile(directory: Path): Path {
        return Files.createTempFile(directory, ".mihon-write-probe-", ".tmp")
    }

    override fun write(path: Path, content: ByteArray) {
        Files.write(path, content, StandardOpenOption.TRUNCATE_EXISTING)
    }

    override fun read(path: Path): ByteArray = Files.readAllBytes(path)

    override fun delete(path: Path) {
        Files.delete(path)
    }

    override fun deleteIfExists(path: Path): Boolean = Files.deleteIfExists(path)
}

internal class NioDesktopDownloadDirectoryProbe(
    private val fileOperations: DesktopDownloadDirectoryFileOperations = NioDesktopDownloadDirectoryFileOperations,
) : DesktopDownloadDirectoryProbe {

    override fun inspect(directory: Path): DesktopDownloadDirectoryProbeResult {
        val preparation = prepareDirectory(directory)
        return if (preparation.availability == DesktopDownloadDirectoryAvailability.AVAILABLE) {
            probeDirectory(directory)
        } else {
            preparation
        }
    }

    private fun prepareDirectory(directory: Path): DesktopDownloadDirectoryProbeResult {
        val missingDirectories = try {
            collectMissingDirectories(directory)
        } catch (error: Exception) {
            return failure(DesktopDownloadDirectoryAvailability.NOT_WRITABLE, error)
        }

        // A selected path becomes user-owned as soon as we create it. Never remove these
        // directories during validation: path-based cleanup cannot close replacement races.
        missingDirectories.asReversed().forEach { candidate ->
            if (fileOperations.exists(candidate)) {
                if (!fileOperations.isDirectory(candidate)) {
                    return failure(DesktopDownloadDirectoryAvailability.NOT_DIRECTORY)
                }
                return@forEach
            }

            try {
                fileOperations.createDirectory(candidate)
            } catch (error: FileAlreadyExistsException) {
                if (!fileOperations.isDirectory(candidate)) {
                    return failure(DesktopDownloadDirectoryAvailability.NOT_DIRECTORY, error)
                }
                return@forEach
            } catch (error: AccessDeniedException) {
                return failure(DesktopDownloadDirectoryAvailability.NOT_WRITABLE, error)
            } catch (error: NoSuchFileException) {
                return failure(DesktopDownloadDirectoryAvailability.MISSING, error)
            } catch (error: SecurityException) {
                return failure(DesktopDownloadDirectoryAvailability.NOT_WRITABLE, error)
            } catch (error: IOException) {
                return failure(DesktopDownloadDirectoryAvailability.NOT_WRITABLE, error)
            }
        }

        return when {
            !fileOperations.exists(directory) -> failure(DesktopDownloadDirectoryAvailability.MISSING)
            !fileOperations.isDirectory(directory) -> failure(DesktopDownloadDirectoryAvailability.NOT_DIRECTORY)
            else -> DesktopDownloadDirectoryProbeResult.available()
        }
    }

    private fun collectMissingDirectories(directory: Path): List<Path> {
        val missing = mutableListOf<Path>()
        var candidate: Path? = directory
        while (candidate != null && !fileOperations.exists(candidate)) {
            missing.add(candidate)
            candidate = candidate.parent
        }
        return missing
    }

    private fun probeDirectory(directory: Path): DesktopDownloadDirectoryProbeResult {
        if (!fileOperations.isReadable(directory) || !fileOperations.isWritable(directory)) {
            return failure(DesktopDownloadDirectoryAvailability.NOT_WRITABLE)
        }

        var probeFile: Path? = null
        var result = DesktopDownloadDirectoryProbeResult.available()
        try {
            probeFile = fileOperations.createProbeFile(directory)
            val expected = byteArrayOf(0x4d, 0x48)
            fileOperations.write(probeFile, expected)
            if (!fileOperations.read(probeFile).contentEquals(expected)) {
                result = failure(
                    DesktopDownloadDirectoryAvailability.NOT_WRITABLE,
                    IOException("Download directory probe read did not match its write"),
                )
            } else {
                fileOperations.delete(probeFile)
                probeFile = null
            }
        } catch (error: IOException) {
            result = failure(DesktopDownloadDirectoryAvailability.NOT_WRITABLE, error)
        } catch (error: SecurityException) {
            result = failure(DesktopDownloadDirectoryAvailability.NOT_WRITABLE, error)
        } finally {
            probeFile?.let { path ->
                try {
                    fileOperations.deleteIfExists(path)
                } catch (error: IOException) {
                    result = failure(DesktopDownloadDirectoryAvailability.NOT_WRITABLE, error)
                } catch (error: SecurityException) {
                    result = failure(DesktopDownloadDirectoryAvailability.NOT_WRITABLE, error)
                }
            }
        }
        return result
    }

    private fun failure(
        availability: DesktopDownloadDirectoryAvailability,
        cause: Throwable? = null,
    ) = DesktopDownloadDirectoryProbeResult(availability, cause)
}
