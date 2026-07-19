package mihon.desktop.extension

import com.googlecode.d2j.dex.Dex2jar
import java.io.File
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
        return try {
            val rawJar = File(outputDir, apkFile.nameWithoutExtension + "-raw.jar")
            Dex2jar.from(apkFile)
                .skipDebug()
                .to(rawJar.toPath())
            if (!rawJar.exists() || rawJar.length() == 0L) return null

            // Post-process: recompute stack map frames to fix VerifyErrors from dex2jar output
            val outputJar = File(outputDir, apkFile.nameWithoutExtension + ".jar")
            BytecodeEditor.fixBytecode(rawJar, outputJar)
            rawJar.delete()
            outputJar.takeIf { it.exists() && it.length() > 0 }
        } catch (_: Exception) {
            null
        }
    }

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
