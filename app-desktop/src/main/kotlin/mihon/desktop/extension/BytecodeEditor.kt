package mihon.desktop.extension

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import java.io.File
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

/**
 * Post-processes a JAR produced by dex2jar to fix common bytecode issues.
 *
 * dex2jar sometimes emits incorrect stack map frames (the JVM verifier rejects
 * them with VerifyError on JVM 8+). This editor re-reads each class through ASM
 * with COMPUTE_FRAMES so all frame tables are recomputed from scratch.
 *
 * The ClassWriter uses a lenient getCommonSuperClass() that falls back to
 * java/lang/Object when a supertype is not on the classpath — which is safe
 * and avoids failures for classes that extend android.* types only present as
 * our stub layer.
 */
object BytecodeEditor {

    /**
     * Reads [inputJar], re-processes every `.class` entry through ASM with
     * COMPUTE_FRAMES, and writes the result to [outputJar].
     *
     * Non-class entries (resources, manifests, etc.) are copied unchanged.
     * If reprocessing a specific class fails, the original bytes are kept.
     */
    fun fixBytecode(inputJar: File, outputJar: File) {
        JarFile(inputJar).use { jar ->
            JarOutputStream(outputJar.outputStream()).use { out ->
                jar.entries().asSequence().forEach { entry ->
                    val inputBytes = jar.getInputStream(entry).readBytes()
                    val outputBytes = if (entry.name.endsWith(".class")) {
                        fixClass(inputBytes)
                    } else {
                        inputBytes
                    }
                    val newEntry = ZipEntry(entry.name)
                    out.putNextEntry(newEntry)
                    out.write(outputBytes)
                    out.closeEntry()
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Internal
    // ──────────────────────────────────────────────────────────────

    private fun fixClass(bytes: ByteArray): ByteArray {
        return try {
            val reader = ClassReader(bytes)
            val writer = LenientClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
            // SKIP_FRAMES: don't validate existing frames — let COMPUTE_FRAMES redo them
            reader.accept(writer, ClassReader.SKIP_FRAMES)
            writer.toByteArray()
        } catch (_: Exception) {
            // If ASM fails (e.g. malformed class), keep the original bytes so the
            // extension JAR is still usable for non-broken classes.
            bytes
        }
    }

    /**
     * ClassWriter that falls back to java/lang/Object when a type hierarchy
     * lookup fails. Required because extension classes extend android.* types
     * that are only available as stubs and may not satisfy the ClassWriter's
     * class-loading requirements.
     */
    private class LenientClassWriter(
        reader: ClassReader,
        flags: Int,
    ) : ClassWriter(reader, flags) {

        override fun getCommonSuperClass(type1: String, type2: String): String {
            return try {
                super.getCommonSuperClass(type1, type2)
            } catch (_: Exception) {
                "java/lang/Object"
            }
        }
    }
}
