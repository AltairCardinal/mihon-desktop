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
 * Restores concrete allocations that dex2jar emits as `new` of an erased
 * superclass for R8-created no-capture objects. This includes `Object`, Kotlin
 * `Lambda`, and `Enum`. Repairs are accepted only when JVM bytecode supplies
 * one unambiguous expected type and the input JAR contains exactly one matching
 * concrete direct subclass whose constructor was erased.
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
            constructors = node.methods
                .filter { it.name == "<init>" }
                .associate { it.desc to it.access },
        )
    }
    private val allocationRepairs = classNodes.values
        .flatMap(::inferRepairs)
    private val constructorOwnerRepairs = classNodes.values
        .flatMap(::inferConstructorOwnerRepairs)
    private val skippedSuperConstructorRepairs = classNodes.values
        .flatMap(::inferSkippedSuperConstructorRepairs)

    fun repair(classNode: ClassNode) {
        val classAllocationRepairs = inferRepairs(classNode)
        val classConstructorOwnerRepairs = inferConstructorOwnerRepairs(classNode)
        val classSkippedSuperConstructorRepairs = inferSkippedSuperConstructorRepairs(classNode)
        classAllocationRepairs.forEach { repair ->
            repair.newObject.desc = repair.target
            repair.constructor.owner = repair.target
        }
        classConstructorOwnerRepairs.forEach { repair ->
            repair.constructor.owner = repair.target
        }
        classSkippedSuperConstructorRepairs.forEach { repair ->
            repair.constructor.owner = repair.target
        }
        (allocationRepairs.map(AllocationRepair::forwardingConstructor) +
            constructorOwnerRepairs.map(ConstructorOwnerRepair::forwardingConstructor) +
            skippedSuperConstructorRepairs.map(SkippedSuperConstructorRepair::forwardingConstructor))
            .filter { it.target == classNode.name }
            .distinctBy(ForwardingConstructor::descriptor)
            .filterNot { constructor ->
                classNode.methods.any { it.name == "<init>" && it.desc == constructor.descriptor }
            }
            .forEach { constructor ->
                classNode.addForwardingConstructor(
                    superOwner = constructor.erasedOwner,
                    descriptor = constructor.descriptor,
                )
            }
        if (classNode.canReceiveImplicitZeroArgConstructor()) {
            classNode.addForwardingConstructor(
                superOwner = classNode.superName,
                descriptor = ZERO_ARG_CONSTRUCTOR,
            )
        }
    }

    private fun inferRepairs(classNode: ClassNode): List<AllocationRepair> =
        classNode.methods.flatMap { method ->
            method.instructions.iterator().asSequence()
                .filterIsInstance<TypeInsnNode>()
                .filter { it.opcode == Opcodes.NEW }
                .mapNotNull { newObject -> inferRepair(classNode, method, newObject) }
                .toList()
        }

    private fun inferSkippedSuperConstructorRepairs(classNode: ClassNode): List<SkippedSuperConstructorRepair> {
        val directSuper = classNode.superName
        val directSuperInfo = classInfo[directSuper] ?: return emptyList()
        return classNode.methods.asSequence()
            .filter { it.name == "<init>" }
            .flatMap { method -> method.instructions.iterator().asSequence() }
            .filterIsInstance<MethodInsnNode>()
            .filter { constructor ->
                constructor.opcode == Opcodes.INVOKESPECIAL &&
                    constructor.name == "<init>" &&
                    constructor.desc == ZERO_ARG_CONSTRUCTOR &&
                    constructor.owner == directSuperInfo.superName &&
                    constructor.previousInstruction().isThisReference()
            }
            .map { constructor ->
                SkippedSuperConstructorRepair(
                    constructor = constructor,
                    target = directSuper,
                    erasedOwner = constructor.owner,
                )
            }
            .toList()
    }

    private fun inferConstructorOwnerRepairs(classNode: ClassNode): List<ConstructorOwnerRepair> =
        classNode.methods.flatMap { method ->
            method.instructions.iterator().asSequence()
                .filterIsInstance<TypeInsnNode>()
                .filter { it.opcode == Opcodes.NEW }
                .mapNotNull(::inferConstructorOwnerRepair)
                .toList()
        }

    private fun inferConstructorOwnerRepair(newObject: TypeInsnNode): ConstructorOwnerRepair? {
        val target = newObject.desc
        val targetInfo = classInfo[target] ?: return null
        if (targetInfo.access and (Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT) != 0) return null
        val erasedOwner = targetInfo.superName
        val next = newObject.nextInstruction() ?: return null
        val constructor = when (next.opcode) {
            Opcodes.DUP -> next.findConstructor(erasedOwner)
            Opcodes.ASTORE -> {
                val store = next as VarInsnNode
                generateSequence(store.nextInstruction()) { it.nextInstruction() }
                    .filterIsInstance<VarInsnNode>()
                    .filter { it.opcode == Opcodes.ALOAD && it.`var` == store.`var` }
                    .mapNotNull { it.findConstructor(erasedOwner) }
                    .firstOrNull()
            }
            else -> null
        } ?: return null
        if (!targetInfo.canReceiveErasedConstructor(erasedOwner, constructor.desc)) return null
        return ConstructorOwnerRepair(
            constructor = constructor,
            target = target,
            erasedOwner = erasedOwner,
        )
    }

    private fun inferRepair(
        classNode: ClassNode,
        method: MethodNode,
        newObject: TypeInsnNode,
    ): AllocationRepair? {
        val duplicate = newObject.nextInstruction() as? InsnNode ?: return null
        if (duplicate.opcode != Opcodes.DUP) return null
        val constructor = generateSequence(duplicate.nextInstruction()) { it.nextInstruction() }
            .take(MAX_CONSTRUCTOR_SEARCH)
            .filterIsInstance<MethodInsnNode>()
            .firstOrNull { it.isConstructorFor(newObject.desc) }
            ?: return null

        val consumer = constructor.findValueConsumer()
        val consumerDescriptors = when (consumer) {
            is MethodInsnNode -> consumer.referenceArgumentsAndReceiver()
            is FieldInsnNode -> listOfNotNull(consumer.storedType())
            is VarInsnNode -> {
                if (consumer.opcode != Opcodes.ASTORE) return null
                inferStoredExpectedTypes(consumer)
            }
            else -> emptyList()
        }
        val enclosingClassDescriptor = "L${classNode.name};".takeIf {
            consumerDescriptors.isEmpty() &&
            method.name == "<clinit>" &&
                classInfo[classNode.name]?.canReceiveErasedConstructor(
                    erasedOwner = newObject.desc,
                    constructorDescriptor = constructor.desc,
                ) == true
        }
        val expectedDescriptors = (consumerDescriptors + listOfNotNull(enclosingClassDescriptor)).distinct()
        val targets = expectedDescriptors.mapNotNull { descriptor ->
            resolveAllocationTarget(
                descriptor = descriptor,
                erasedOwner = newObject.desc,
                constructorDescriptor = constructor.desc,
            )
        }.distinct()
        return targets.singleOrNull()?.let {
            AllocationRepair(
                newObject = newObject,
                constructor = constructor,
                target = it,
                erasedOwner = newObject.desc,
            )
        }
    }

    private fun inferStoredExpectedTypes(store: VarInsnNode): List<String> {
        val expected = mutableListOf<String>()
        generateSequence(store.nextInstruction()) { it.nextInstruction() }
            .filterIsInstance<VarInsnNode>()
            .filter { it.opcode == Opcodes.ALOAD && it.`var` == store.`var` }
            .forEach { load ->
                when (val next = load.nextInstruction()) {
                    is FieldInsnNode -> {
                        if (next.opcode == Opcodes.PUTSTATIC) next.storedType()?.let(expected::add)
                        if (next.opcode == Opcodes.PUTFIELD) Type.getType(next.desc).referenceDescriptor()
                            ?.let(expected::add)
                    }
                    is MethodInsnNode -> {
                        next.referenceArgumentsAndReceiver().forEach(expected::add)
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

    private fun resolveAllocationTarget(
        descriptor: String,
        erasedOwner: String,
        constructorDescriptor: String,
    ): String? {
        val expected = Type.getType(descriptor)
        if (expected.sort != Type.OBJECT) return null
        val expectedName = expected.internalName

        classInfo[expectedName]?.let { exact ->
            if (exact.canReceiveErasedConstructor(erasedOwner, constructorDescriptor)) return expectedName
        }
        return classInfo.asSequence()
            .filter { (_, info) -> info.canReceiveErasedConstructor(erasedOwner, constructorDescriptor) }
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

    private fun ClassInfo.canReceiveErasedConstructor(
        erasedOwner: String,
        constructorDescriptor: String,
    ): Boolean =
        access and (Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT) == 0 &&
            (constructors.isEmpty() || constructorDescriptor in constructors) &&
            superName == erasedOwner

    private fun ClassNode.addForwardingConstructor(
        superOwner: String,
        descriptor: String,
    ) {
        val arguments = Type.getArgumentTypes(descriptor)
        val access = if (access and Opcodes.ACC_ENUM != 0) Opcodes.ACC_PRIVATE else Opcodes.ACC_PUBLIC
        methods.add(MethodNode(Opcodes.ASM9, access, "<init>", descriptor, null, null).apply {
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            var localIndex = 1
            arguments.forEach { argument ->
                instructions.add(VarInsnNode(argument.getOpcode(Opcodes.ILOAD), localIndex))
                localIndex += argument.size
            }
            instructions.add(
                MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    superOwner,
                    "<init>",
                    descriptor,
                    false,
                ),
            )
            instructions.add(InsnNode(Opcodes.RETURN))
            maxStack = localIndex
            maxLocals = localIndex
        })
    }

    private fun ClassNode.canReceiveImplicitZeroArgConstructor(): Boolean {
        if (access and (Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT) != 0) return false
        if (methods.any { it.name == "<init>" }) return false
        val superInfo = classInfo[superName] ?: return false
        val superConstructorAccess = superInfo.constructors[ZERO_ARG_CONSTRUCTOR] ?: return false
        return when {
            superConstructorAccess and Opcodes.ACC_PRIVATE != 0 -> false
            superConstructorAccess and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0 -> true
            else -> name.substringBeforeLast('/', "") == superName.substringBeforeLast('/', "")
        }
    }

    private fun MethodInsnNode.isConstructorFor(ownerName: String): Boolean =
        opcode == Opcodes.INVOKESPECIAL &&
            owner == ownerName &&
            name == "<init>"

    private fun MethodInsnNode.referenceArgumentsAndReceiver(): List<String> =
        buildList {
            if (opcode != Opcodes.INVOKESTATIC) add("L$owner;")
            Type.getArgumentTypes(desc).mapNotNullTo(this) { it.referenceDescriptor() }
        }

    private fun FieldInsnNode.storedType(): String? =
        if (opcode == Opcodes.PUTSTATIC || opcode == Opcodes.PUTFIELD) {
            Type.getType(desc).referenceDescriptor()
        } else {
            null
        }

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

    private fun AbstractInsnNode.findConstructor(owner: String): MethodInsnNode? =
        generateSequence(nextInstruction()) { it.nextInstruction() }
            .take(MAX_CONSTRUCTOR_SEARCH)
            .filterIsInstance<MethodInsnNode>()
            .firstOrNull { it.isConstructorFor(owner) }

    private fun MethodInsnNode.findValueConsumer(): AbstractInsnNode? =
        generateSequence(nextInstruction()) { it.nextInstruction() }
            .take(MAX_VALUE_CONSUMER_SEARCH)
            .firstOrNull { instruction ->
                instruction is MethodInsnNode ||
                    instruction is FieldInsnNode ||
                    instruction is VarInsnNode && instruction.opcode == Opcodes.ASTORE
            }

    private fun AbstractInsnNode.nextInstruction(): AbstractInsnNode? =
        generateSequence(next) { it.next }.firstOrNull { it.opcode >= 0 }

    private fun AbstractInsnNode.previousInstruction(): AbstractInsnNode? =
        generateSequence(previous) { it.previous }.firstOrNull { it.opcode >= 0 }

    private fun AbstractInsnNode?.isThisReference(): Boolean =
        this is VarInsnNode && opcode == Opcodes.ALOAD && `var` == 0

    private data class AllocationRepair(
        val newObject: TypeInsnNode,
        val constructor: MethodInsnNode,
        val target: String,
        val erasedOwner: String,
    ) {
        val forwardingConstructor: ForwardingConstructor
            get() = ForwardingConstructor(target, erasedOwner, constructor.desc)
    }

    private data class ConstructorOwnerRepair(
        val constructor: MethodInsnNode,
        val target: String,
        val erasedOwner: String,
    ) {
        val forwardingConstructor: ForwardingConstructor
            get() = ForwardingConstructor(target, erasedOwner, constructor.desc)
    }

    private data class SkippedSuperConstructorRepair(
        val constructor: MethodInsnNode,
        val target: String,
        val erasedOwner: String,
    ) {
        val forwardingConstructor: ForwardingConstructor
            get() = ForwardingConstructor(target, erasedOwner, constructor.desc)
    }

    private data class ForwardingConstructor(
        val target: String,
        val erasedOwner: String,
        val descriptor: String,
    )

    private data class ClassInfo(
        val superName: String,
        val interfaces: Set<String>,
        val access: Int,
        val constructors: Map<String, Int>,
    )

    private companion object {
        const val MAX_CONSTRUCTOR_SEARCH = 16
        const val MAX_VALUE_CONSUMER_SEARCH = 12
        const val ZERO_ARG_CONSTRUCTOR = "()V"
    }
}
