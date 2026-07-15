package mihon.domain.extension.model

import mihon.domain.error.AppError

sealed interface ExtensionCompatibility {
    data object Compatible : ExtensionCompatibility

    data class UnsupportedLib(
        val libVersion: Double,
        val minimum: Double,
        val maximum: Double,
    ) : ExtensionCompatibility

    data class MissingPlatformApi(val apiName: String) : ExtensionCompatibility
    data class CorruptArtifact(val error: AppError) : ExtensionCompatibility
    data class UntrustedArtifact(val error: AppError) : ExtensionCompatibility
}

data class ExtensionCatalogEntry(
    val artifact: ExtensionArtifact,
    val compatibility: ExtensionCompatibility,
)

data class ExtensionCatalogResult(
    val entries: List<ExtensionCatalogEntry>,
    val failures: List<RepositoryCatalogFailure>,
) {
    val isCompleteEmpty: Boolean = entries.isEmpty() && failures.isEmpty()
}

data class RepositoryCatalogFailure(
    val repository: RepositoryIdentity,
    val error: AppError,
)
