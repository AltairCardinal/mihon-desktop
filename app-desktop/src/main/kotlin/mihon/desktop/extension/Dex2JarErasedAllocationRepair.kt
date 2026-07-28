package mihon.desktop.extension

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode

/**
 * Restores concrete allocations that dex2jar emits as `new Object()` for
 * R8-created no-capture objects. Repairs are accepted only when JVM bytecode
 * supplies one unambiguous expected type and the input JAR contains exactly one
 * matching concrete class whose constructor was erased.
 */
internal class Dex2JarErasedAllocationRepair(inputClasses: Map<String, ByteArray>) {

    private val classNodes = inputClasses.mapNotNull { (name, bytes) ->
        try {
            name to ClassNode(Opcodes.ASM9).also { ClassReader(bytes).accept(it, ClassReader.SKIP_FRAMES) }
        } catch (_: Exception) {
            null
        }
    }.toMap()
    private val classInfo = classNodes.mapValues { (_, node) ->
        ClassInfo(
            superName = node.superName,
            interfaces = node.interfaces.toSet(),
            access = node.access,
            constructors = node.methods.filter { it.name == "<init>" }.map { it.desc }.toSet(),
        )
    }
    private val allocationTargets = classNodes.values
        .flatMap(::inferRepairs)
        .mapTo(mutableSetOf()) { it.target }

    fun repair(classNode: ClassNode) {
        inferRepairs(classNode).forEach { repair ->
            repair.newObject.desc = repair.target
            repair.constructor.owner = repair.target
        }
        if (classNode.name in allocationTargets && classNode.methods.none { it.name == "<init>" }) {
            classNode.addDefaultConstructor()
        }
    }

    private fun inferRepairs(classNode: ClassNode): List<AllocationRepair> =
        classNode.methods.flatMap { method ->
            method.instructions.iterator().asSequence()
                .filterIsInstance<TypeInsnNode>()
                .filter { it.opcode == Opcodes.NEW && it.desc == OBJECT_OWNER }
                .mapNotNull { newObject -> inferRepair(method, newObject) }
                .toList()
        }

    private fun inferRepair(method: MethodNode, newObject: TypeInsnNode): AllocationRepair? {
        val duplicate = newObject.nextInstruction() as? InsnNode ?: return null
        val constructor = duplicate.nextInstruction() as? MethodInsnNode ?: return null
        if (duplicate.opcode != Opcodes.DUP || !constructor.isObjectConstructor()) return null

        val expectedDescriptors = when (val consumer = constructor.nextInstruction()) {
            is MethodInsnNode -> listOfNotNull(consumer.lastReferenceArgument())
            is FieldInsnNode -> listOfNotNull(consumer.staticStoredType())
            is VarInsnNode -> {
                if (consumer.opcode != Opcodes.ASTORE) return null
                inferStoredExpectedTypes(consumer)
            }
            else -> emptyList()
        }
        val targets = expectedDescriptors.mapNotNull(::resolveAllocationTarget).distinct()
        return targets.singleOrNull()?.let { AllocationRepair(newObject, constructor, it) }
    }

    private fun inferStoredExpectedTypes(store: VarInsnNode): List<String> {
        val expected = mutableListOf<String>()
        generateSequence(store.nextInstruction()) { it.nextInstruction() }
            .filterIsInstance<VarInsnNode>()
            .filter { it.opcode == Opcodes.ALOAD && it.`var` == store.`var` }
            .forEach { load ->
                when (val next = load.nextInstruction()) {
                    is FieldInsnNode -> {
                        if (next.opcode == Opcodes.PUTSTATIC) next.staticStoredType()?.let(expected::add)
                        if (next.opcode == Opcodes.PUTFIELD) Type.getType(next.desc).referenceDescriptor()
                            ?.let(expected::add)
                    }
                    is MethodInsnNode -> {
                        next.lastReferenceArgument()?.let(expected::add)
                            ?: next.receiverType()?.let(expected::add)
                    }
                }

                load.instructionsUntilBoundary()
                    .filterIsInstance<FieldInsnNode>()
                    .firstOrNull { it.opcode == Opcodes.PUTFIELD }
                    ?.let { field ->
                        val descriptor = if (load.nextInstruction() === field) {
                            Type.getType(field.desc).referenceDescriptor()
                        } else {
                            "L${field.owner};"
                        }
                        descriptor?.let(expected::add)
                    }
            }
        return expected.distinct()
    }

    private fun resolveAllocationTarget(descriptor: String): String? {
        val expected = Type.getType(descriptor)
        if (expected.sort != Type.OBJECT) return null
        val expectedName = expected.internalName

        classInfo[expectedName]?.let { exact ->
            if (exact.canReceiveErasedConstructor()) return expectedName
        }
        return classInfo.asSequence()
            .filter { (_, info) -> info.canReceiveErasedConstructor() }
            .map { (name) -> name }
            .filter { candidate -> isAssignableTo(candidate, expectedName) }
            .distinct()
            .singleOrNull()
    }

    private fun isAssignableTo(candidate: String, expected: String, visited: MutableSet<String> = mutableSetOf()): Boolean {
        if (candidate == expected) return true
        if (!visited.add(candidate)) return false
        val info = classInfo[candidate] ?: return false
        return info.interfaces.any { it == expected || isAssignableTo(it, expected, visited) } ||
            info.superName == expected ||
            isAssignableTo(info.superName, expected, visited)
    }

    private fun ClassInfo.canReceiveErasedConstructor(): Boolean =
        access and (Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT) == 0 &&
            constructors.isEmpty() &&
            superName == OBJECT_OWNER

    private fun ClassNode.addDefaultConstructor() {
        methods.add(MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false))
            instructions.add(InsnNode(Opcodes.RETURN))
            maxStack = 1
            maxLocals = 1
        })
    }

    private fun MethodInsnNode.isObjectConstructor(): Boolean =
        opcode == Opcodes.INVOKESPECIAL &&
            owner == OBJECT_OWNER &&
            name == "<init>" &&
            desc == "()V"

    private fun MethodInsnNode.lastReferenceArgument(): String? =
        Type.getArgumentTypes(desc).lastOrNull()?.referenceDescriptor()

    private fun MethodInsnNode.receiverType(): String? =
        if (opcode == Opcodes.INVOKESTATIC) null else "L$owner;"

    private fun FieldInsnNode.staticStoredType(): String? =
        if (opcode == Opcodes.PUTSTATIC) Type.getType(desc).referenceDescriptor() else null

    private fun Type.referenceDescriptor(): String? =
        descriptor.takeIf { sort == Type.OBJECT || sort == Type.ARRAY }

    private fun AbstractInsnNode.instructionsUntilBoundary(): Sequence<AbstractInsnNode> =
        generateSequence(nextInstruction()) { current ->
            current.nextInstruction()?.takeUnless {
                it.opcode in setOf(
                    Opcodes.GOTO,
                    Opcodes.ATHROW,
                    Opcodes.IRETURN,
                    Opcodes.LRETURN,
                    Opcodes.FRETURN,
                    Opcodes.DRETURN,
                    Opcodes.ARETURN,
                    Opcodes.RETURN,
                )
            }
        }.take(6)

    private fun AbstractInsnNode.nextInstruction(): AbstractInsnNode? =
        generateSequence(next) { it.next }.firstOrNull { it.opcode >= 0 }

    private data class AllocationRepair(
        val newObject: TypeInsnNode,
        val constructor: MethodInsnNode,
        val target: String,
    )

    private data class ClassInfo(
        val superName: String,
        val interfaces: Set<String>,
        val access: Int,
        val constructors: Set<String>,
    )

    private companion object {
        const val OBJECT_OWNER = "java/lang/Object"
    }
}
