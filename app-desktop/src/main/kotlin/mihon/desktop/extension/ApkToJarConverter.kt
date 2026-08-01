package mihon.desktop.extension

import com.googlecode.d2j.dex.Dex2jar
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile

enum class ApkConversionStage {
    INSPECT_INPUT,
    PREPARE_WORKSPACE,
    TRANSLATE_DEX,
    REWRITE_BYTECODE,
    COPY_ASSETS,
    PUBLISH_OUTPUT,
    CLEANUP,
}

sealed interface ApkConversionResult {
    data class Success(
        val jar: File,
        val attempts: Int,
    ) : ApkConversionResult

    data class Failure(
        val stage: ApkConversionStage,
        val error: Throwable,
        val attempts: Int,
    ) : ApkConversionResult
}

class ApkConversionException(
    val failure: ApkConversionResult.Failure,
) : Exception(
    buildString {
        append("APK convert failed at ")
        append(failure.stage.name)
        append(" after ")
        append(failure.attempts)
        append(" attempt(s): ")
        append(failure.error.message ?: failure.error::class.simpleName ?: "unknown failure")
    },
    failure.error,
)

/**
 * Converts an Android APK file to a JVM-compatible JAR by extracting and
 * translating its DEX bytecode with dex2jar.
 *
 * The produced JAR can be loaded with a [java.net.URLClassLoader] the same way
 * as pre-compiled desktop extension JARs, so [DesktopExtensionLoader] needs no
 * changes to load APK-sourced extensions.
 */
class ApkToJarConverter internal constructor(
    private val outputPublisher: (source: Path, destination: Path) -> Unit,
    private val retryDelay: () -> Unit,
) {

    constructor() : this(
        outputPublisher = { source, destination ->
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        },
        retryDelay = { Thread.sleep(TRANSIENT_FILE_RETRY_DELAY_MILLIS) },
    )

    /**
     * Converts [apkFile] to a JVM JAR and places the result in [outputDir].
     *
     * @return The output JAR file, or null if conversion was not possible
     *         (e.g. the input is not a valid APK/ZIP, contains no DEX, or the
     *         dex2jar translation fails).
     */
    fun convert(apkFile: File, outputDir: File): File? {
        return (convertDetailed(apkFile, outputDir) as? ApkConversionResult.Success)?.jar
    }

    fun convertDetailed(apkFile: File, outputDir: File): ApkConversionResult {
        inspectInput(apkFile)?.let { return it }
        var lastFailure: ApkConversionResult.Failure? = null
        repeat(MAX_CONVERSION_ATTEMPTS) { attemptIndex ->
            val attempt = attemptIndex + 1
            when (val result = convertOnce(apkFile, outputDir)) {
                is ApkConversionResult.Success -> return result.copy(attempts = attempt)
                is ApkConversionResult.Failure -> {
                    lastFailure = result.copy(attempts = attempt)
                    if (attempt == MAX_CONVERSION_ATTEMPTS || !result.error.isTransientFileAccessFailure()) {
                        return checkNotNull(lastFailure)
                    }
                    retryDelay()
                }
            }
        }
        return checkNotNull(lastFailure)
    }

    private fun convertOnce(apkFile: File, outputDir: File): ApkConversionResult {
        var workDir: Path? = null
        var stage = ApkConversionStage.PREPARE_WORKSPACE
        var cancellationFailure: CancellationException? = null
        val result = try {
            workDir = Files.createTempDirectory(outputDir.toPath(), ".mihon-apk-convert-")
            val rawJar = workDir.resolve("raw.jar").toFile()
            stage = ApkConversionStage.TRANSLATE_DEX
            Dex2jar.from(apkFile)
                .skipDebug()
                .to(rawJar.toPath())
            check(rawJar.isFile && rawJar.length() > 0L) { "dex2jar produced no JVM bytecode" }

            // Post-process: recompute stack map frames to fix VerifyErrors from dex2jar output
            val editedJar = workDir.resolve("edited.jar").toFile()
            stage = ApkConversionStage.REWRITE_BYTECODE
            BytecodeEditor.fixBytecode(rawJar, editedJar)
            check(editedJar.isFile && editedJar.length() > 0L) { "Bytecode rewrite produced no JAR" }
            stage = ApkConversionStage.COPY_ASSETS
            copyClasspathResources(apkFile, editedJar)
            val outputJar = File(outputDir, apkFile.nameWithoutExtension + ".jar")
            stage = ApkConversionStage.PUBLISH_OUTPUT
            outputPublisher(editedJar.toPath(), outputJar.toPath())
            check(outputJar.isFile && outputJar.length() > 0L) { "Published conversion output is missing or empty" }
            ApkConversionResult.Success(outputJar, attempts = 1)
        } catch (error: CancellationException) {
            cancellationFailure = error
            ApkConversionResult.Failure(stage, error, attempts = 1)
        } catch (error: Exception) {
            ApkConversionResult.Failure(stage, error, attempts = 1)
        }
        val cleanupFailure = workDir?.let { runCatching { deleteWorkDirectory(it) }.exceptionOrNull() }
        cancellationFailure?.let { cancellation ->
            cleanupFailure?.let(cancellation::addSuppressed)
            throw cancellation
        }
        return when {
            cleanupFailure == null -> result
            result is ApkConversionResult.Failure -> result.also { it.error.addSuppressed(cleanupFailure) }
            else -> ApkConversionResult.Failure(ApkConversionStage.CLEANUP, cleanupFailure, attempts = 1)
        }
    }

    private fun deleteWorkDirectory(workDir: Path) {
        Files.deleteIfExists(workDir.resolve("raw.jar"))
        Files.deleteIfExists(workDir.resolve("edited.jar"))
        Files.deleteIfExists(workDir)
    }

    /**
     * Copies safe JVM resources after bytecode editing without replacing generated JAR entries.
     *
     * Android packages Java resources at the APK root rather than below `assets/`. Converted
     * extensions still load those files through `Class.getResourceAsStream`, so preserving only
     * `assets/` produces valid bytecode with an incomplete runtime classpath.
     */
    private fun copyClasspathResources(apkFile: File, outputJar: File) {
        ZipFile(apkFile).use { apk ->
            FileSystems.newFileSystem(outputJar.toPath()).use { jar ->
                val root = jar.getPath("/")
                apk.entries().asSequence()
                    .filter { !it.isDirectory && isSafeClasspathResourcePath(it.name) }
                    .forEach { entry ->
                        val target = try {
                            root.resolve(entry.name).normalize()
                        } catch (_: InvalidPathException) {
                            return@forEach
                        }
                        if (!target.startsWith(root) || Files.exists(target)) return@forEach
                        Files.createDirectories(target.parent)
                        apk.getInputStream(entry).use { input -> Files.copy(input, target) }
                    }
            }
        }
    }

    private fun isSafeClasspathResourcePath(name: String): Boolean {
        if (name.isBlank() || name.startsWith('/') || '\\' in name) return false
        val segments = name.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return false
        if (segments.first() in AndroidContainerDirectories) return false
        if (name in AndroidContainerFiles || name.matches(AndroidDexEntry)) return false
        if (name.endsWith(".class", ignoreCase = true)) return false
        return true
    }

    private fun inspectInput(apkFile: File): ApkConversionResult.Failure? {
        return try {
            ZipFile(apkFile).use { zip ->
                if (zip.entries().asSequence().none { it.name.matches(Regex("classes\\d*\\.dex")) }) {
                    ApkConversionResult.Failure(
                        stage = ApkConversionStage.INSPECT_INPUT,
                        error = IllegalArgumentException("APK contains no DEX bytecode"),
                        attempts = 1,
                    )
                } else {
                    null
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ApkConversionResult.Failure(ApkConversionStage.INSPECT_INPUT, error, attempts = 1)
        }
    }

    private fun Throwable.isTransientFileAccessFailure(): Boolean =
        generateSequence(this) { it.cause }.any { cause ->
            cause is AccessDeniedException ||
                cause is FileSystemException &&
                cause !is AtomicMoveNotSupportedException &&
                cause !is DirectoryNotEmptyException &&
                cause !is FileAlreadyExistsException &&
                cause !is NoSuchFileException &&
                cause !is NotDirectoryException
        }

    private companion object {
        val AndroidContainerDirectories = setOf("META-INF", "lib", "res")
        val AndroidContainerFiles = setOf("AndroidManifest.xml", "resources.arsc")
        val AndroidDexEntry = Regex("classes\\d*\\.dex")
        const val MAX_CONVERSION_ATTEMPTS = 2
        const val TRANSIENT_FILE_RETRY_DELAY_MILLIS = 10L
    }
}
