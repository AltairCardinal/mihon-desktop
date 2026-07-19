package mihon.desktop.extension

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

internal fun resolveDex2JarMethodName(
    emittedName: String,
    descriptor: String,
    hostCandidates: List<Pair<String, String>>,
): String {
    if (emittedName.startsWith('<')) return emittedName
    val matchingDescriptor = hostCandidates.filter { (_, actualDescriptor) -> actualDescriptor == descriptor }
    if (matchingDescriptor.any { (actualName) -> actualName == emittedName }) return emittedName
    return matchingDescriptor.asSequence()
        .map { (actualName) -> actualName }
        .filter { actualName -> actualName.replace('-', '_') == emittedName }
        .distinct()
        .singleOrNull()
        ?: emittedName
}

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
            val inputClassNames = jar.entries().asSequence()
                .filter { it.name.endsWith(".class") }
                .map { it.name.removeSuffix(".class") }
                .toSet()
            val methodNameResolver = HostMethodNameResolver(inputClassNames)
            JarOutputStream(outputJar.outputStream()).use { out ->
                jar.entries().asSequence().forEach { entry ->
                    val inputBytes = jar.getInputStream(entry).readBytes()
                    val outputBytes = if (entry.name.endsWith(".class")) {
                        fixClass(inputBytes, methodNameResolver)
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

    private fun fixClass(bytes: ByteArray, methodNameResolver: HostMethodNameResolver): ByteArray {
        return try {
            val reader = ClassReader(bytes)
            val writer = LenientClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
            val visitor = MethodCallRepairingClassVisitor(writer, methodNameResolver)
            // SKIP_FRAMES: don't validate existing frames — let COMPUTE_FRAMES redo them
            reader.accept(visitor, ClassReader.SKIP_FRAMES)
            writer.toByteArray()
        } catch (_: Exception) {
            // If ASM fails (e.g. malformed class), keep the original bytes so the
            // extension JAR is still usable for non-broken classes.
            bytes
        }
    }

    private class MethodCallRepairingClassVisitor(
        writer: ClassWriter,
        private val methodNameResolver: HostMethodNameResolver,
    ) : ClassVisitor(Opcodes.ASM9, writer) {
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
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean,
                ) {
                    val resolvedName = methodNameResolver.resolve(owner, name, descriptor)
                    val resolvedDescriptor = resolvePageConstructorDescriptor(owner, name, descriptor)
                    super.visitMethodInsn(opcode, owner, resolvedName, resolvedDescriptor, isInterface)
                }
            }
        }
    }

    private fun resolvePageConstructorDescriptor(owner: String, name: String, descriptor: String): String {
        if (owner != PAGE_OWNER || name != "<init>") return descriptor
        return when (descriptor) {
            PAGE_URI_DESCRIPTOR -> PAGE_OBJECT_DESCRIPTOR
            PAGE_URI_DEFAULT_DESCRIPTOR -> PAGE_OBJECT_DEFAULT_DESCRIPTOR
            else -> descriptor
        }
    }

    private class HostMethodNameResolver(private val inputClassNames: Set<String>) {
        private val cache = mutableMapOf<MethodCall, String?>()

        fun resolve(owner: String, emittedName: String, descriptor: String): String {
            if (owner in inputClassNames || emittedName.startsWith('<')) return emittedName
            val call = MethodCall(owner, emittedName, descriptor)
            if (!cache.containsKey(call)) cache[call] = findHostMethodName(call)
            return cache[call] ?: emittedName
        }

        private fun findHostMethodName(call: MethodCall): String? {
            val ownerClass = try {
                Class.forName(call.owner.replace('/', '.'), false, BytecodeEditor::class.java.classLoader)
            } catch (_: ClassNotFoundException) {
                return null
            } catch (_: LinkageError) {
                return null
            } catch (_: SecurityException) {
                return null
            }
            return try {
                val candidates = ownerClass.declaredMethods.map { method ->
                    method.name to Type.getMethodDescriptor(method)
                }
                resolveDex2JarMethodName(call.emittedName, call.descriptor, candidates)
                    .takeIf { it != call.emittedName }
            } catch (_: LinkageError) {
                null
            } catch (_: SecurityException) {
                null
            }
        }
    }

    private data class MethodCall(
        val owner: String,
        val emittedName: String,
        val descriptor: String,
    )

    private const val PAGE_OWNER = "eu/kanade/tachiyomi/source/model/Page"
    private const val PAGE_URI_DESCRIPTOR = "(ILjava/lang/String;Ljava/lang/String;Landroid/net/Uri;)V"
    private const val PAGE_OBJECT_DESCRIPTOR = "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V"
    private const val PAGE_URI_DEFAULT_DESCRIPTOR =
        "(ILjava/lang/String;Ljava/lang/String;Landroid/net/Uri;ILkotlin/jvm/internal/DefaultConstructorMarker;)V"
    private const val PAGE_OBJECT_DEFAULT_DESCRIPTOR =
        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/Object;ILkotlin/jvm/internal/DefaultConstructorMarker;)V"

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
