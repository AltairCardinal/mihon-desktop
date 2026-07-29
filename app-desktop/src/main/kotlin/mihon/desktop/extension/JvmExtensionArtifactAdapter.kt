package mihon.desktop.extension

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

internal object DefaultJvmExtensionArtifactAdapter {
    fun adaptIfRequired(input: File, output: File): Boolean {
        val entries = JarFile(input).use { jar ->
            jar.entries().asSequence().map { entry ->
                val bytes = if (entry.isDirectory) {
                    ByteArray(0)
                } else {
                    jar.getInputStream(entry).use { it.readBytes() }
                }
                val adapted = if (entry.name.endsWith(".class")) {
                    runCatching { adaptClass(bytes) }.getOrElse { AdaptedClass(bytes, false) }
                } else {
                    AdaptedClass(bytes, false)
                }
                AdaptedEntry(entry.name, entry.isDirectory, entry.time, adapted.bytes, adapted.changed)
            }.toList()
        }
        if (entries.none(AdaptedEntry::changed)) return false

        JarOutputStream(output.outputStream()).use { jar ->
            entries.asSequence()
                .filterNot { it.name.isSignatureMetadata() }
                .forEach { entry ->
                    jar.putNextEntry(
                        JarEntry(entry.name).apply {
                            if (entry.time >= 0) time = entry.time
                        },
                    )
                    if (!entry.isDirectory) jar.write(entry.bytes)
                    jar.closeEntry()
                }
        }
        return true
    }

    private fun adaptClass(bytes: ByteArray): AdaptedClass {
        var changed = false
        val reader = ClassReader(bytes)
        val writer = ClassWriter(reader, 0)
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                    return object : MethodVisitor(Opcodes.ASM9, delegate) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String?,
                            name: String?,
                            descriptor: String?,
                            isInterface: Boolean,
                        ) {
                            val adaptedDescriptor = if (owner == PAGE_OWNER && name == "<init>") {
                                when (descriptor) {
                                    PAGE_URI_DESCRIPTOR -> PAGE_OBJECT_DESCRIPTOR
                                    PAGE_URI_DEFAULT_DESCRIPTOR -> PAGE_OBJECT_DEFAULT_DESCRIPTOR
                                    else -> descriptor
                                }
                            } else {
                                descriptor
                            }
                            if (adaptedDescriptor != descriptor) changed = true
                            super.visitMethodInsn(opcode, owner, name, adaptedDescriptor, isInterface)
                        }
                    }
                }
            },
            0,
        )
        return AdaptedClass(if (changed) writer.toByteArray() else bytes, changed)
    }

    private fun String.isSignatureMetadata(): Boolean {
        val normalized = uppercase()
        if (normalized == "META-INF/MANIFEST.MF") return true
        if (!normalized.startsWith("META-INF/")) return false
        val fileName = normalized.substringAfterLast('/')
        return fileName.startsWith("SIG-") ||
            fileName.endsWith(".SF") ||
            fileName.endsWith(".RSA") ||
            fileName.endsWith(".DSA") ||
            fileName.endsWith(".EC")
    }

    private data class AdaptedClass(val bytes: ByteArray, val changed: Boolean)
    private data class AdaptedEntry(
        val name: String,
        val isDirectory: Boolean,
        val time: Long,
        val bytes: ByteArray,
        val changed: Boolean,
    )

    private const val PAGE_OWNER = "eu/kanade/tachiyomi/source/model/Page"
    private const val PAGE_URI_DESCRIPTOR = "(ILjava/lang/String;Ljava/lang/String;Landroid/net/Uri;)V"
    private const val PAGE_OBJECT_DESCRIPTOR = "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V"
    private const val PAGE_URI_DEFAULT_DESCRIPTOR =
        "(ILjava/lang/String;Ljava/lang/String;Landroid/net/Uri;ILkotlin/jvm/internal/DefaultConstructorMarker;)V"
    private const val PAGE_OBJECT_DEFAULT_DESCRIPTOR =
        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/Object;ILkotlin/jvm/internal/DefaultConstructorMarker;)V"
}
