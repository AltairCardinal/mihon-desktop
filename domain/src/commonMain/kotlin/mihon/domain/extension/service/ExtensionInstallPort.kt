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

    suspend fun validate(token: PreparedExtensionInstallToken)

    suspend fun commit(token: PreparedExtensionInstallToken): ExtensionInstallRollbackToken

    suspend fun reload(packageName: String)

    suspend fun rollback(token: ExtensionInstallRollbackToken)

    suspend fun cleanup(token: PreparedExtensionInstallToken)
}
