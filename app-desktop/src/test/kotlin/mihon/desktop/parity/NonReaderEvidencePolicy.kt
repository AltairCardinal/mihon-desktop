package mihon.desktop.parity

internal class ManualInventoryEvidencePolicy {
    fun validate(action: ManualActionDecision) {
        require(action.scope == InventoryScope.NON_READER) {
            "${action.id}: reader-internal action is outside non-reader inventory"
        }
        require(action.expectedBehavior.isNotBlank()) { "${action.id}: expected behavior must be handwritten" }

        action.roleDecisions.forEach { (role, decision) ->
            require(decision.mode == DecisionMode.MANUAL) {
                if (decision.status == ImplementationStatus.EXEMPT) {
                    "${action.id}/$role: EXEMPT must be a manual decision"
                } else {
                    "${action.id}/$role: implementation status must be a manual decision"
                }
            }
            require(decision.reason.isNotBlank()) { "${action.id}/$role: status decision needs a reason" }
        }

        if (action.migrationTag == MigrationTag.DESKTOP_PRODUCT) {
            listOf(AuthorityRole.FIXED_ORIGINAL, AuthorityRole.CURRENT_ANDROID).forEach { role ->
                require(action.roleDecisions[role]?.status != ImplementationStatus.PRESENT) {
                    "${action.id}: desktop-product cannot claim ${role.label} PRESENT"
                }
            }
        }

        val seenEvidenceIds = mutableSetOf<String>()
        action.evidence.forEach { evidence ->
            require(seenEvidenceIds.add(evidence.id)) { "${action.id}: duplicate evidence ${evidence.id}" }
            require(evidence.kind == EvidenceKind.PRODUCTION) {
                "${action.id}/${evidence.id}: ${evidence.kind} is forbidden as semantic evidence"
            }
            require(evidence.behavior == action.expectedBehavior) {
                "${action.id}/${evidence.id}: behavior contract mismatch; " +
                    "expected ${action.expectedBehavior}, evidence proves ${evidence.behavior}"
            }
            if (evidence.ownerCapabilityId != action.ownerCapabilityId) {
                val sharedUse = evidence.sharedUses[action.ownerCapabilityId]
                require(sharedUse != null) {
                    "${action.id}/${evidence.id}: missing reviewed shared-use consumer for capability " +
                        action.ownerCapabilityId
                }
                require(
                    sharedUse.consumerPath.isNotBlank() &&
                        sharedUse.consumerLocator.isNotBlank() &&
                        sharedUse.reason.isNotBlank(),
                ) {
                    "${action.id}/${evidence.id}: reviewed shared-use consumer is incomplete"
                }
            }
        }

        val provenRoles = action.evidence.flatMapTo(mutableSetOf()) { it.roles }
        val missingRoles = action.requiredRoles - provenRoles
        require(missingRoles.isEmpty()) { "${action.id}: missing production evidence roles $missingRoles" }

        action.trackedReview?.let { tracked ->
            require(tracked.declared == tracked.observed) {
                "${action.id}: tracked ${tracked.declared} contradicts observed ${tracked.observed}"
            }
            require(tracked.reason.isNotBlank()) { "${action.id}: tracked review needs a manual reason" }
        }
    }
}

internal data class ManualActionDecision(
    val id: String,
    val ownerCapabilityId: Int,
    val scope: InventoryScope,
    val migrationTag: MigrationTag,
    val expectedBehavior: String,
    val requiredRoles: Set<EvidenceRole>,
    val evidence: List<ManualEvidence>,
    val roleDecisions: Map<AuthorityRole, RoleDecision>,
    val trackedReview: TrackedReview? = null,
)

internal data class ManualEvidence(
    val id: String,
    val ownerCapabilityId: Int,
    val kind: EvidenceKind,
    val behavior: String,
    val roles: Set<EvidenceRole>,
    val sharedUses: Map<Int, SharedUseReview> = emptyMap(),
)

internal data class SharedUseReview(
    val consumerPath: String,
    val consumerLocator: String,
    val reason: String,
)

internal data class RoleDecision(
    val status: ImplementationStatus,
    val mode: DecisionMode = DecisionMode.MANUAL,
    val reason: String = "Handwritten fixture decision.",
)

internal data class TrackedReview(
    val declared: TrackedDisposition,
    val observed: TrackedDisposition,
    val sourcePathStillExists: Boolean,
    val reason: String,
)

internal enum class InventoryScope {
    NON_READER,
    READER_INTERNAL,
}

internal enum class MigrationTag {
    SHARED_EXECUTOR,
    PLATFORM_ADAPTER,
    DESKTOP_PRODUCT,
}

internal enum class EvidenceRole {
    ENTRY,
    EFFECT,
    PERSISTENCE,
    CONFIRMATION,
    FEEDBACK,
}

internal enum class EvidenceKind {
    PRODUCTION,
    GENERIC_CONTAINER_CONTROL,
    DECLARATION_ONLY,
    PREFERENCE_DECLARATION_ONLY,
    TEST_OR_PREVIEW,
    READ_ONLY_ACCESSOR,
}

internal enum class AuthorityRole(val label: String) {
    FIXED_ORIGINAL("fixed-original"),
    CURRENT_ANDROID("current-Android"),
    DESKTOP("Desktop"),
}

internal enum class ImplementationStatus {
    PRESENT,
    PARTIAL,
    GAP,
    EXEMPT,
}

internal enum class DecisionMode {
    MANUAL,
    AUTOMATIC,
}

internal enum class TrackedDisposition {
    UNCHANGED,
    CONTEXT_CHANGED,
    MOVED,
    REMOVED,
    SEMANTIC_CHANGED,
}
