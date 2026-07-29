package mihon.desktop.extension

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class JvmExtensionArtifactAdapterTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `signed JVM artifact adaptation rewrites only platform Page ABI and removes stale signatures`() {
        val input = File(tempDir, "signed-input.jar")
        JarOutputStream(input.outputStream()).use { output ->
            output.entry("Client.class", pageConstructorCaller())
            output.entry("assets/messages.properties", "title=Example".encodeToByteArray())
            output.entry("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n".encodeToByteArray())
            output.entry("META-INF/CERT.SF", "signature".encodeToByteArray())
            output.entry("META-INF/CERT.RSA", "certificate".encodeToByteArray())
        }
        val output = File(tempDir, "runtime.jar")

        val changed = DefaultJvmExtensionArtifactAdapter.adaptIfRequired(input, output)

        assertTrue(changed)
        JarFile(output).use { jar ->
            val names = jar.entries().asSequence().map { it.name }.toSet()
            assertFalse("META-INF/MANIFEST.MF" in names)
            assertFalse("META-INF/CERT.SF" in names)
            assertFalse("META-INF/CERT.RSA" in names)
            assertTrue("assets/messages.properties" in names)
            val descriptor = jar.getInputStream(jar.getJarEntry("Client.class")).use(::pageConstructorDescriptor)
            assertEquals(PAGE_OBJECT_DEFAULT_DESCRIPTOR, descriptor)
        }
    }

    private fun JarOutputStream.entry(name: String, bytes: ByteArray) {
        putNextEntry(JarEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun pageConstructorCaller(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "Client", null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC, "create", "()V", null, null).apply {
            visitCode()
            visitInsn(Opcodes.ICONST_0)
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ICONST_0)
            visitInsn(Opcodes.ACONST_NULL)
            visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                PAGE_OWNER,
                "<init>",
                PAGE_URI_DEFAULT_DESCRIPTOR,
                false,
            )
            visitInsn(Opcodes.RETURN)
            visitMaxs(6, 1)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun pageConstructorDescriptor(bytes: java.io.InputStream): String {
        var foundDescriptor = ""
        ClassReader(bytes).accept(
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
                        owner: String?,
                        name: String?,
                        methodDescriptor: String?,
                        isInterface: Boolean,
                    ) {
                        if (owner == PAGE_OWNER && name == "<init>") {
                            foundDescriptor = methodDescriptor.orEmpty()
                        }
                    }
                }
            },
            0,
        )
        return foundDescriptor
    }

    private companion object {
        const val PAGE_OWNER = "eu/kanade/tachiyomi/source/model/Page"
        const val PAGE_URI_DEFAULT_DESCRIPTOR =
            "(ILjava/lang/String;Ljava/lang/String;Landroid/net/Uri;ILkotlin/jvm/internal/DefaultConstructorMarker;)V"
        const val PAGE_OBJECT_DEFAULT_DESCRIPTOR =
            "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/Object;ILkotlin/jvm/internal/DefaultConstructorMarker;)V"
    }
}
