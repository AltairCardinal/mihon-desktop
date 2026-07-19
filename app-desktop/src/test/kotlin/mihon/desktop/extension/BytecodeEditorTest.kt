package mihon.desktop.extension

import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.util.jar.JarFile
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
        java.net.URLClassLoader(arrayOf(output.toURI().toURL())).use { cl ->
            val cls = cl.loadClass("TestClass")
            cls.getDeclaredConstructor().newInstance() // must not throw
        }
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

    @Test
    fun `fixBytecode restores dex2jar underscore to the unique host method name`() {
        val emittedName = "getZERO_UwyO8pc"
        val descriptor = "()J"
        val hostMatches = Class.forName(DURATION_COMPANION.replace('/', '.')).declaredMethods.filter {
            it.name.replace('-', '_') == emittedName && Type.getMethodDescriptor(it) == descriptor
        }
        hostMatches.single().name shouldBe "getZERO-UwyO8pc"
        val input = buildJar(
            "ExternalCaller.class" to buildCaller("ExternalCaller", DURATION_COMPANION, emittedName, descriptor),
        )
        val output = File(tempDir, "output.jar")

        BytecodeEditor.fixBytecode(input, output)

        methodCalls(output, "ExternalCaller").single { it.owner == DURATION_COMPANION }.name shouldBe
            "getZERO-UwyO8pc"
    }

    @Test
    fun `fixBytecode leaves extension owned and unknown external method names unchanged`() {
        val input = buildJar(
            "OwnedCaller.class" to buildCaller("OwnedCaller", "OwnedCaller", "local_name", "()J"),
            "UnknownCaller.class" to buildCaller("UnknownCaller", "missing/Host", "unknown_name", "()J"),
        )
        val output = File(tempDir, "output.jar")

        BytecodeEditor.fixBytecode(input, output)

        methodCalls(output, "OwnedCaller").single { it.owner == "OwnedCaller" }.name shouldBe "local_name"
        methodCalls(output, "UnknownCaller").single { it.owner == "missing/Host" }.name shouldBe "unknown_name"
    }

    @Test
    fun `host exact method name wins over a normalized candidate`() {
        resolveDex2JarMethodName(
            emittedName = "foo_bar",
            descriptor = "()V",
            hostCandidates = listOf("foo_bar" to "()V", "foo-bar" to "()V"),
        ) shouldBe "foo_bar"
    }

    @Test
    fun `ambiguous normalized host method names preserve the emitted name`() {
        resolveDex2JarMethodName(
            emittedName = "foo__bar",
            descriptor = "()V",
            hostCandidates = listOf("foo--bar" to "()V", "foo-_bar" to "()V"),
        ) shouldBe "foo__bar"
    }

    @Test
    fun `constructors and descriptor mismatches preserve the emitted name`() {
        resolveDex2JarMethodName(
            emittedName = "<init>",
            descriptor = "()V",
            hostCandidates = listOf("-init-" to "()V"),
        ) shouldBe "<init>"
        resolveDex2JarMethodName(
            emittedName = "foo_bar",
            descriptor = "()V",
            hostCandidates = listOf("foo-bar" to "()J"),
        ) shouldBe "foo_bar"
    }

    private fun buildCaller(className: String, owner: String, name: String, descriptor: String): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null)
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "call", "()V", null, null)
        method.visitCode()
        method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, descriptor, false)
        method.visitInsn(Opcodes.POP2)
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(2, 0)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun methodCalls(jar: File, className: String): List<MethodCall> {
        val calls = mutableListOf<MethodCall>()
        JarFile(jar).use { input ->
            ClassReader(input.getInputStream(input.getJarEntry("$className.class"))).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String?,
                        descriptor: String?,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            calls += MethodCall(owner, name, descriptor)
                        }
                    }
                },
                0,
            )
        }
        return calls
    }

    private data class MethodCall(val owner: String, val name: String, val descriptor: String)

    private companion object {
        const val DURATION_COMPANION = "kotlin/time/Duration\$Companion"
    }
}
