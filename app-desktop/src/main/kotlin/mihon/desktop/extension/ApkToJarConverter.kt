package mihon.desktop.extension

import com.googlecode.d2j.dex.Dex2jar
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Converts an Android APK file to a JVM-compatible JAR by extracting and
 * translating its DEX bytecode with dex2jar.
 *
 * The produced JAR can be loaded with a [java.net.URLClassLoader] the same way
 * as pre-compiled desktop extension JARs, so [DesktopExtensionLoader] needs no
 * changes to load APK-sourced extensions.
 */
class ApkToJarConverter {

    /**
     * Converts [apkFile] to a JVM JAR and places the result in [outputDir].
     *
     * @return The output JAR file, or null if conversion was not possible
     *         (e.g. the input is not a valid APK/ZIP, contains no DEX, or the
     *         dex2jar translation fails).
     */
    fun convert(apkFile: File, outputDir: File): File? {
        if (!containsDex(apkFile)) return null
        var workDir: Path? = null
        return try {
            workDir = Files.createTempDirectory(outputDir.toPath(), ".mihon-apk-convert-")
            val rawJar = workDir.resolve("raw.jar").toFile()
            Dex2jar.from(apkFile)
                .skipDebug()
                .to(rawJar.toPath())
            if (!rawJar.exists() || rawJar.length() == 0L) return null

            // Post-process: recompute stack map frames to fix VerifyErrors from dex2jar output
            val editedJar = workDir.resolve("edited.jar").toFile()
            BytecodeEditor.fixBytecode(rawJar, editedJar)
            if (!editedJar.exists() || editedJar.length() == 0L) return null
            copyClasspathAssets(apkFile, editedJar)
            val outputJar = File(outputDir, apkFile.nameWithoutExtension + ".jar")
            Files.move(editedJar.toPath(), outputJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
            outputJar.takeIf { it.exists() && it.length() > 0 }
        } catch (_: Exception) {
            null
        } finally {
            workDir?.let(::deleteWorkDirectory)
        }
    }

    private fun deleteWorkDirectory(workDir: Path) {
        Files.deleteIfExists(workDir.resolve("raw.jar"))
        Files.deleteIfExists(workDir.resolve("edited.jar"))
        Files.deleteIfExists(workDir)
    }

    /** Copies safe APK assets after bytecode editing without replacing generated JAR entries. */
    private fun copyClasspathAssets(apkFile: File, outputJar: File) {
        ZipFile(apkFile).use { apk ->
            FileSystems.newFileSystem(outputJar.toPath()).use { jar ->
                val root = jar.getPath("/")
                val assetsRoot = root.resolve("assets")
                apk.entries().asSequence()
                    .filter { !it.isDirectory && isSafeAssetPath(it.name) }
                    .forEach { entry ->
                        val target = try {
                            root.resolve(entry.name).normalize()
                        } catch (_: InvalidPathException) {
                            return@forEach
                        }
                        if (!target.startsWith(assetsRoot) || Files.exists(target)) return@forEach
                        Files.createDirectories(target.parent)
                        apk.getInputStream(entry).use { input -> Files.copy(input, target) }
                    }
            }
        }
    }

    private fun isSafeAssetPath(name: String): Boolean =
        name.startsWith("assets/") &&
            '\\' !in name &&
            name.split('/').none { it == "." || it == ".." }

    /**
     * Returns true if [apkFile] is a valid ZIP and contains at least one DEX entry.
     */
    private fun containsDex(apkFile: File): Boolean {
        return try {
            ZipFile(apkFile).use { zip ->
                zip.entries().asSequence().any { it.name.matches(Regex("classes\\d*\\.dex")) }
            }
        } catch (_: ZipException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
