package mihon.domain.extension.service

import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact

data class ExtensionInstallRequest(
    val artifact: ExtensionArtifact,
)

@JvmInline
value class PreparedExtensionInstallToken(val value: String)

@JvmInline
value class ExtensionInstallRollbackToken(val value: String)

class ExtensionInstallFailure(val error: AppError) : RuntimeException(error.cause)

interface ExtensionInstallPort {
    suspend fun prepare(request: ExtensionInstallRequest): PreparedExtensionInstallToken

    /**
     * Validates the prepared artifact and snapshots the installed artifact and metadata.
     * The returned token must be sufficient to restore both, and must exist before [commit] has any side effect.
     */
    suspend fun validate(token: PreparedExtensionInstallToken): ExtensionInstallRollbackToken

    /** Applies the prepared artifact and metadata using a snapshot already returned by [validate]. */
    suspend fun commit(token: PreparedExtensionInstallToken)

    suspend fun reload(packageName: String)

    suspend fun rollback(token: ExtensionInstallRollbackToken)

    suspend fun cleanup(token: PreparedExtensionInstallToken)
}
