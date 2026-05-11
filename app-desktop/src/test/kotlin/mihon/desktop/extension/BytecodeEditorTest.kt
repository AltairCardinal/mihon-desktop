package mihon.desktop.extension

import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

/**
 * Tests for BytecodeEditor (27.2).
 *
 * Red phase: these tests define the contract before implementation.
 */
class BytecodeEditorTest {

    @TempDir
    lateinit var tempDir: File

    /** Builds a minimal valid class bytecode via ASM. */
    private fun buildSimpleClass(className: String = "TestClass"): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(1, 1)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** Packages bytecode into a JAR. */
    private fun buildJar(vararg entries: Pair<String, ByteArray>): File {
        val jar = File(tempDir, "input.jar")
        JarOutputStream(jar.outputStream()).use { jos ->
            for ((name, bytes) in entries) {
                jos.putNextEntry(ZipEntry(name))
                jos.write(bytes)
                jos.closeEntry()
            }
        }
        return jar
    }

    @Test
    fun `fixBytecode copies class entries to output`() {
        val input = buildJar("TestClass.class" to buildSimpleClass())
        val output = File(tempDir, "output.jar")

        BytecodeEditor.fixBytecode(input, output)

        output.exists().shouldBeTrue()
        assertTrue(output.length() > 0L)
    }

    @Test
    fun `fixBytecode preserves non-class entries unchanged`() {
        val manifest = "Manifest-Version: 1.0\n".toByteArray()
        val input = buildJar(
            "META-INF/MANIFEST.MF" to manifest,
            "TestClass.class" to buildSimpleClass(),
        )
        val output = File(tempDir, "output.jar")

        BytecodeEditor.fixBytecode(input, output)

        val result = java.util.jar.JarFile(output)
        val entry = result.getEntry("META-INF/MANIFEST.MF")
        val bytes = result.getInputStream(entry).readBytes()
        result.close()
        bytes shouldBe manifest
    }

    @Test
    fun `fixBytecode produces loadable class files`() {
        val input = buildJar("TestClass.class" to buildSimpleClass())
        val output = File(tempDir, "output.jar")

        BytecodeEditor.fixBytecode(input, output)

        // Must be loadable without VerifyError
        val cl = java.net.URLClassLoader(arrayOf(output.toURI().toURL()))
        val cls = cl.loadClass("TestClass")
        cls.getDeclaredConstructor().newInstance() // must not throw
    }

    @Test
    fun `fixBytecode handles class with missing supertype gracefully`() {
        // Simulate a dex2jar output class that extends a missing android type
        val cw = ClassWriter(0) // NO COMPUTE_FRAMES — raw output like dex2jar produces
        cw.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC,
            "SomeSource",
            null,
            "android/some/MissingBase", // supertype not in classpath
            null,
        )
        cw.visitEnd()
        val badBytes = cw.toByteArray()

        val input = buildJar("SomeSource.class" to badBytes)
        val output = File(tempDir, "output.jar")

        // Should NOT throw — graceful fallback on missing supertype
        BytecodeEditor.fixBytecode(input, output)
        output.exists().shouldBeTrue()
    }

    @Test
    fun `fixBytecode handles empty JAR`() {
        val input = buildJar() // no entries
        val output = File(tempDir, "output.jar")

        BytecodeEditor.fixBytecode(input, output)
        output.exists().shouldBeTrue()
    }
}
