package mihon.domain.extension.service

import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.InstalledExtensionTrustRecord

sealed interface ExtensionTrustDecision {
    data object Trusted : ExtensionTrustDecision
    data class ConfirmationRequired(val reasons: Set<TrustMismatch>) : ExtensionTrustDecision
    data class Rejected(val error: AppError) : ExtensionTrustDecision
}

sealed interface TrustMismatch {
    data object IncomingRepositoryIdentityMissing : TrustMismatch
    data object LegacyMetadataMissingRepositoryIdentity : TrustMismatch
    data object LegacyMetadataMissingArtifactDigest : TrustMismatch

    data class RepositoryIdentityChanged(
        val installedFingerprint: String,
        val incomingFingerprint: String,
    ) : TrustMismatch

    data class InstalledOriginChanged(
        val installedBaseUrl: String,
        val incomingBaseUrl: String,
    ) : TrustMismatch
}

data class ExtensionTrustRequest(
    val incomingArtifact: ExtensionArtifact,
    val downloadedArtifactSha256: String?,
    val installed: InstalledExtensionTrustRecord? = null,
    val installedArtifactSha256: String? = null,
)

class ExtensionTrustPolicy {

    fun evaluate(request: ExtensionTrustRequest): ExtensionTrustDecision {
        validateIncomingDigest(request)?.let { return it }
        validateInstalledDigest(request)?.let { return it }

        val reasons = linkedSetOf<TrustMismatch>()
        val incomingRepository = request.incomingArtifact.repository
        if (incomingRepository.signingKeyFingerprint.isBlank()) {
            reasons += TrustMismatch.IncomingRepositoryIdentityMissing
        }

        request.installed?.let { installed ->
            val installedRepository = installed.repository
            if (installedRepository == null || installedRepository.signingKeyFingerprint.isBlank()) {
                reasons += TrustMismatch.LegacyMetadataMissingRepositoryIdentity
            } else {
                if (!installedRepository.signingKeyFingerprint.equals(
                        incomingRepository.signingKeyFingerprint,
                        ignoreCase = true,
                    )
                ) {
                    reasons += TrustMismatch.RepositoryIdentityChanged(
                        installedRepository.signingKeyFingerprint,
                        incomingRepository.signingKeyFingerprint,
                    )
                }
                if (installedRepository.baseUrl.normalizedRepositoryUrl() !=
                    incomingRepository.baseUrl.normalizedRepositoryUrl()
                ) {
                    reasons += TrustMismatch.InstalledOriginChanged(
                        installedRepository.baseUrl,
                        incomingRepository.baseUrl,
                    )
                }
            }
            if (installed.artifactSha256.isNullOrBlank()) {
                reasons += TrustMismatch.LegacyMetadataMissingArtifactDigest
            }
        }

        return if (reasons.isEmpty()) {
            ExtensionTrustDecision.Trusted
        } else {
            ExtensionTrustDecision.ConfirmationRequired(reasons)
        }
    }

    private fun validateIncomingDigest(request: ExtensionTrustRequest): ExtensionTrustDecision.Rejected? {
        val declared = request.incomingArtifact.declaredSha256?.takeIf { it.isNotBlank() } ?: return null
        val actual = request.downloadedArtifactSha256?.takeIf { it.isNotBlank() } ?: return null
        return if (declared.equals(
                actual,
                ignoreCase = true,
            )
        ) {
            null
        } else {
            rejected("Downloaded extension digest mismatch")
        }
    }

    private fun validateInstalledDigest(request: ExtensionTrustRequest): ExtensionTrustDecision.Rejected? {
        val recorded = request.installed?.artifactSha256?.takeIf { it.isNotBlank() } ?: return null
        val actual = request.installedArtifactSha256?.takeIf { it.isNotBlank() }
            ?: return rejected("Installed extension digest is unavailable")
        return if (recorded.equals(actual, ignoreCase = true)) null else rejected("Installed extension digest mismatch")
    }

    private fun rejected(message: String) = ExtensionTrustDecision.Rejected(
        AppError.MalformedData(IllegalArgumentException(message)),
    )
}

private fun String.normalizedRepositoryUrl(): String {
    val url = trim().removeSuffix("/")
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return url

    val authorityStart = schemeEnd + 3
    val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
        .takeIf { it >= 0 }
        ?: url.length
    val authority = url.substring(authorityStart, authorityEnd)
    val userInfoEnd = authority.lastIndexOf('@')
    val userInfo = authority.take(userInfoEnd + 1)
    val hostAndPort = authority.drop(userInfoEnd + 1)
    val normalizedHostAndPort = when {
        hostAndPort.startsWith('[') -> {
            val hostEnd = hostAndPort.indexOf(']')
            if (hostEnd >= 0) {
                hostAndPort.substring(0, hostEnd + 1).lowercase() + hostAndPort.substring(hostEnd + 1)
            } else {
                hostAndPort.lowercase()
            }
        }
        hostAndPort.count { it == ':' } <= 1 -> {
            val portStart = hostAndPort.lastIndexOf(':').takeIf { it >= 0 } ?: hostAndPort.length
            hostAndPort.substring(0, portStart).lowercase() + hostAndPort.substring(portStart)
        }
        else -> hostAndPort.lowercase()
    }

    return buildString {
        append(url.substring(0, schemeEnd).lowercase())
        append("://")
        append(userInfo)
        append(normalizedHostAndPort)
        append(url.substring(authorityEnd))
    }
}
