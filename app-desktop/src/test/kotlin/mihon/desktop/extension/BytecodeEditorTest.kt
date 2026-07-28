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

    @Test
    fun `fixBytecode rewrites only fixed-main Page constructor descriptors`() {
        val calls = listOf(
            Invocation("PagePrimary", PAGE_OWNER, "<init>", PAGE_URI_DESCRIPTOR),
            Invocation("PageDefault", PAGE_OWNER, "<init>", PAGE_URI_DEFAULT_DESCRIPTOR),
            Invocation("OtherOwner", "extension/model/Page", "<init>", PAGE_URI_DESCRIPTOR),
            Invocation("OtherMethod", PAGE_OWNER, "factory", PAGE_URI_DESCRIPTOR),
            Invocation("OtherDescriptor", PAGE_OWNER, "<init>", "(ILjava/lang/String;)V"),
        )
        val input = buildJar(
            *calls.map { call ->
                "${call.className}.class" to buildInvocationCaller(call)
            }.toTypedArray(),
        )
        val output = File(tempDir, "output.jar")

        BytecodeEditor.fixBytecode(input, output)

        methodCalls(output, "PagePrimary").single().descriptor shouldBe PAGE_OBJECT_DESCRIPTOR
        methodCalls(output, "PageDefault").single().descriptor shouldBe PAGE_OBJECT_DEFAULT_DESCRIPTOR
        calls.drop(2).forEach { call ->
            methodCalls(output, call.className).single() shouldBe
                MethodCall(call.owner, call.name, call.descriptor)
        }
    }

    @Test
    fun `fixBytecode restores uniquely typed erased allocations without rewriting real Objects`() {
        val input = buildJar(
            "SharedPreferencesLazy.class" to buildErasedLazySupplier(
                "SharedPreferencesLazy",
                "android/content/SharedPreferences",
            ),
            "ErasedSupplier.class" to buildErasedFunctionSupplier("ErasedSupplier"),
            "RealObject.class" to buildRealObjectHolder("RealObject"),
        )
        val output = File(tempDir, "output.jar")

        BytecodeEditor.fixBytecode(input, output)

        newTypes(output, "SharedPreferencesLazy") shouldBe listOf("ErasedSupplier")
        newTypes(output, "RealObject") shouldBe listOf("java/lang/Object")
        java.net.URLClassLoader(arrayOf(output.toURI().toURL()), javaClass.classLoader).use { loader ->
            loader.loadClass("SharedPreferencesLazy").getDeclaredConstructor().newInstance()
        }
    }

    @Test
    fun `fixBytecode restores erased Lambda singleton allocation and forwarding constructor`() {
        val input = buildJar(
            "ErasedLambda.class" to buildErasedLambdaSingleton("ErasedLambda"),
        )
        val output = File(tempDir, "output.jar")

        BytecodeEditor.fixBytecode(input, output)

        java.net.URLClassLoader(arrayOf(output.toURI().toURL()), javaClass.classLoader).use { loader ->
            val type = loader.loadClass("ErasedLambda")
            val instance = type.getField("INSTANCE").get(null)
            instance.javaClass shouldBe type
        }
    }

    @Test
    fun `fixBytecode restores erased Enum allocations staged through locals`() {
        val input = buildJar(
            "ErasedEnum.class" to buildErasedEnumValues("ErasedEnum"),
        )
        val output = File(tempDir, "output.jar")

        BytecodeEditor.fixBytecode(input, output)

        java.net.URLClassLoader(arrayOf(output.toURI().toURL()), javaClass.classLoader).use { loader ->
            val type = loader.loadClass("ErasedEnum")
            val values = type.getField("\$VALUES").get(null) as Array<*>
            values.single()?.javaClass shouldBe type
        }
    }

    @Test
    fun `fixBytecode restores erased superclass allocation when target constructor exists`() {
        val input = buildJar(
            "ConcreteMap.class" to buildConcreteMap("ConcreteMap"),
            "MapHolder.class" to buildErasedMapHolder("MapHolder", "ConcreteMap"),
        )
        val output = File(tempDir, "output.jar")

        BytecodeEditor.fixBytecode(input, output)

        java.net.URLClassLoader(arrayOf(output.toURI().toURL()), javaClass.classLoader).use { loader ->
            val holder = loader.loadClass("MapHolder").getDeclaredConstructor().newInstance()
            val value = holder.javaClass.getField("value").get(holder)
            value.javaClass.name shouldBe "ConcreteMap"
        }
    }

    @Test
    fun `fixBytecode restores erased constructor owner after uninitialized local staging`() {
        val input = buildJar(
            "ErasedAction.class" to buildErasedAction("ErasedAction"),
            "ActionHolder.class" to buildStagedErasedConstructorHolder("ActionHolder", "ErasedAction"),
        )
        val output = File(tempDir, "output.jar")

        BytecodeEditor.fixBytecode(input, output)

        java.net.URLClassLoader(arrayOf(output.toURI().toURL()), javaClass.classLoader).use { loader ->
            val holder = loader.loadClass("ActionHolder")
            val instance = holder.getField("INSTANCE").get(null)
            instance.javaClass.name shouldBe "ErasedAction"
        }
    }

    @Test
    fun `fixBytecode restores missing constructor on concrete subclass with zero arg super`() {
        val input = buildJar(
            "AbstractBase.class" to buildAbstractBase("AbstractBase"),
            "ConcreteChild.class" to buildConstructorlessChild("ConcreteChild", "AbstractBase"),
        )
        val output = File(tempDir, "output.jar")

        BytecodeEditor.fixBytecode(input, output)

        java.net.URLClassLoader(arrayOf(output.toURI().toURL()), javaClass.classLoader).use { loader ->
            loader.loadClass("ConcreteChild").getDeclaredConstructor().newInstance()
        }
    }

    @Test
    fun `fixBytecode restores skipped abstract superclass constructor`() {
        val input = buildJar(
            "ErasedAbstractBase.class" to buildConstructorlessAbstractClass(
                "ErasedAbstractBase",
                "java/lang/Object",
            ),
            "ConcreteDto.class" to buildConstructorCallingErasedAncestor(
                "ConcreteDto",
                "ErasedAbstractBase",
                "java/lang/Object",
            ),
        )
        val output = File(tempDir, "output.jar")

        BytecodeEditor.fixBytecode(input, output)

        java.net.URLClassLoader(arrayOf(output.toURI().toURL()), javaClass.classLoader).use { loader ->
            loader.loadClass("ConcreteDto").getDeclaredConstructor().newInstance()
        }
    }

    @Test
    fun `fixBytecode infers erased allocation before trailing scalar method arguments`() {
        val input = buildJar(
            "TrailingFunction.class" to buildErasedFunctionOne("TrailingFunction"),
            "TrailingArgumentHolder.class" to buildTrailingArgumentHolder(
                "TrailingArgumentHolder",
                "TrailingFunction",
            ),
        )
        val output = File(tempDir, "output.jar")

        BytecodeEditor.fixBytecode(input, output)

        java.net.URLClassLoader(arrayOf(output.toURI().toURL()), javaClass.classLoader).use { loader ->
            val holder = loader.loadClass("TrailingArgumentHolder")
            holder.getField("RECEIVED").get(null).javaClass.name shouldBe "TrailingFunction"
        }
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

    private fun buildInvocationCaller(call: Invocation): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, call.className, null, "java/lang/Object", null)
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "call", "()V", null, null)
        method.visitCode()
        if (call.name == "<init>") {
            method.visitTypeInsn(Opcodes.NEW, call.owner)
            method.visitInsn(Opcodes.DUP)
        }
        Type.getArgumentTypes(call.descriptor).forEach { type ->
            method.visitInsn(if (type.sort == Type.INT) Opcodes.ICONST_0 else Opcodes.ACONST_NULL)
        }
        method.visitMethodInsn(
            if (call.name == "<init>") Opcodes.INVOKESPECIAL else Opcodes.INVOKESTATIC,
            call.owner,
            call.name,
            call.descriptor,
            false,
        )
        if (call.name == "<init>") method.visitInsn(Opcodes.POP)
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun buildErasedLazySupplier(className: String, valueType: String): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PRIVATE, "value", "Lkotlin/Lazy;", null, null).visitEnd()

        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitTypeInsn(Opcodes.NEW, "java/lang/Object")
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "kotlin/LazyKt",
                "lazy",
                "(Lkotlin/jvm/functions/Function0;)Lkotlin/Lazy;",
                false,
            )
            visitVarInsn(Opcodes.ASTORE, 1)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitFieldInsn(Opcodes.PUTFIELD, className, "value", "Lkotlin/Lazy;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        writer.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/Object;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, className, "value", "Lkotlin/Lazy;")
            visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "kotlin/Lazy",
                "getValue",
                "()Ljava/lang/Object;",
                true,
            )
            visitTypeInsn(Opcodes.CHECKCAST, valueType)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun buildErasedFunctionSupplier(className: String): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        writer.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            className,
            null,
            "java/lang/Object",
            arrayOf("kotlin/jvm/functions/Function0"),
        )
        writer.visitMethod(Opcodes.ACC_PUBLIC, "invoke", "()Ljava/lang/Object;", null, null).apply {
            visitCode()
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun buildRealObjectHolder(className: String): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PRIVATE, "value", "Ljava/lang/Object;", null, null).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitTypeInsn(Opcodes.NEW, "java/lang/Object")
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitFieldInsn(Opcodes.PUTFIELD, className, "value", "Ljava/lang/Object;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun buildErasedLambdaSingleton(className: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            className,
            null,
            "kotlin/jvm/internal/Lambda",
            arrayOf("kotlin/jvm/functions/Function0"),
        )
        writer.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "INSTANCE",
            "L$className;",
            null,
            null,
        ).visitEnd()
        writer.visitMethod(
            Opcodes.ACC_STATIC,
            "<clinit>",
            "()V",
            null,
            null,
        ).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "kotlin/jvm/internal/Lambda")
            visitInsn(Opcodes.DUP)
            visitInsn(Opcodes.ICONST_0)
            visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "kotlin/jvm/internal/Lambda",
                "<init>",
                "(I)V",
                false,
            )
            visitFieldInsn(Opcodes.PUTSTATIC, className, "INSTANCE", "L$className;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(3, 0)
            visitEnd()
        }
        writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "invoke",
            "()Ljava/lang/Object;",
            null,
            null,
        ).apply {
            visitCode()
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun buildErasedEnumValues(className: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_ENUM,
            className,
            null,
            "java/lang/Enum",
            null,
        )
        writer.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC,
            "\$VALUES",
            "[L$className;",
            null,
            null,
        ).visitEnd()
        writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/lang/Enum")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("ONE")
            visitInsn(Opcodes.ICONST_0)
            visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/lang/Enum",
                "<init>",
                "(Ljava/lang/String;I)V",
                false,
            )
            visitVarInsn(Opcodes.ASTORE, 0)
            visitInsn(Opcodes.ICONST_1)
            visitTypeInsn(Opcodes.ANEWARRAY, className)
            visitInsn(Opcodes.DUP)
            visitInsn(Opcodes.ICONST_0)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitInsn(Opcodes.AASTORE)
            visitFieldInsn(Opcodes.PUTSTATIC, className, "\$VALUES", "[L$className;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(4, 1)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun buildConcreteMap(className: String): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, className, null, "java/util/LinkedHashMap", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/util/LinkedHashMap",
                "<init>",
                "()V",
                false,
            )
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun buildErasedMapHolder(
        className: String,
        mapClassName: String,
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PUBLIC, "value", "L$mapClassName;", null, null).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitTypeInsn(Opcodes.NEW, "java/util/LinkedHashMap")
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/LinkedHashMap", "<init>", "()V", false)
            visitFieldInsn(Opcodes.PUTFIELD, className, "value", "L$mapClassName;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(3, 1)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun buildErasedAction(className: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            className,
            null,
            "java/lang/Object",
            arrayOf("java/lang/Runnable"),
        )
        writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null).apply {
            visitCode()
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 1)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun buildStagedErasedConstructorHolder(
        className: String,
        actionClassName: String,
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null)
        writer.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "INSTANCE",
            "L$actionClassName;",
            null,
            null,
        ).visitEnd()
        writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, actionClassName)
            visitVarInsn(Opcodes.ASTORE, 0)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.PUTSTATIC, className, "INSTANCE", "L$actionClassName;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun buildAbstractBase(className: String): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        writer.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
            className,
            null,
            "java/lang/Object",
            null,
        )
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun buildConstructorlessChild(
        className: String,
        superName: String,
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, className, null, superName, null)
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun buildConstructorlessAbstractClass(
        className: String,
        superName: String,
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, className, null, superName, null)
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun buildConstructorCallingErasedAncestor(
        className: String,
        superName: String,
        erasedAncestor: String,
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, className, null, superName, null)
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, erasedAncestor, "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun buildErasedFunctionOne(className: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            className,
            null,
            "java/lang/Object",
            arrayOf("kotlin/jvm/functions/Function1"),
        )
        writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "invoke",
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            null,
            null,
        ).apply {
            visitCode()
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun buildTrailingArgumentHolder(
        className: String,
        functionClassName: String,
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null)
        writer.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "RECEIVED",
            "Lkotlin/jvm/functions/Function1;",
            null,
            null,
        ).visitEnd()
        writer.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "accept",
            "(Lkotlin/jvm/functions/Function1;ILjava/lang/Object;)V",
            null,
            null,
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(
                Opcodes.PUTSTATIC,
                className,
                "RECEIVED",
                "Lkotlin/jvm/functions/Function1;",
            )
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 3)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/lang/Object")
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.ICONST_1)
            visitInsn(Opcodes.ACONST_NULL)
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                className,
                "accept",
                "(Lkotlin/jvm/functions/Function1;ILjava/lang/Object;)V",
                false,
            )
            visitInsn(Opcodes.RETURN)
            visitMaxs(3, 0)
            visitEnd()
        }
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

    private fun newTypes(jar: File, className: String): List<String> {
        val types = mutableListOf<String>()
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
                        override fun visitTypeInsn(opcode: Int, type: String) {
                            if (opcode == Opcodes.NEW) types += type
                        }
                    }
                },
                0,
            )
        }
        return types
    }

    private data class MethodCall(val owner: String, val name: String, val descriptor: String)

    private data class Invocation(
        val className: String,
        val owner: String,
        val name: String,
        val descriptor: String,
    )

    private companion object {
        const val DURATION_COMPANION = "kotlin/time/Duration\$Companion"
        const val PAGE_OWNER = "eu/kanade/tachiyomi/source/model/Page"
        const val PAGE_URI_DESCRIPTOR = "(ILjava/lang/String;Ljava/lang/String;Landroid/net/Uri;)V"
        const val PAGE_OBJECT_DESCRIPTOR = "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V"
        const val PAGE_URI_DEFAULT_DESCRIPTOR =
            "(ILjava/lang/String;Ljava/lang/String;Landroid/net/Uri;ILkotlin/jvm/internal/DefaultConstructorMarker;)V"
        const val PAGE_OBJECT_DEFAULT_DESCRIPTOR =
            "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/Object;ILkotlin/jvm/internal/DefaultConstructorMarker;)V"
    }
}
