package tachiyomi.domain.creator.model

data class CreatorIdentitySnapshot(
    val id: Long,
    val revision: Long,
    val displayName: String,
    val names: List<String>,
    val representativeTitle: String?,
    val followed: Boolean,
) {
    val aliases: List<String> get() = names.filter { it != displayName }
}

data class CreatorAliasCandidates(
    val target: CreatorIdentitySnapshot,
    val candidates: List<CreatorIdentitySnapshot>,
)

data class AddCreatorAliasesRequest(
    val targetId: Long,
    val targetRevision: Long,
    val selectedRevisions: Map<Long, Long>,
    val idempotencyKey: String,
)

data class SetCreatorDisplayNameRequest(
    val creatorId: Long,
    val revision: Long,
    val name: String,
    val idempotencyKey: String,
)

class StaleCreatorIdentityException : IllegalStateException("作者资料已变化，请刷新后重试")
class CreatorIdentityRequestConflict : IllegalArgumentException("操作已变化，请重新提交")
