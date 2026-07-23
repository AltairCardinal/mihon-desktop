package mihon.domain.license.service

import mihon.domain.license.model.DependencyNotice
import mihon.domain.license.model.DependencyNoticeMetadata
import mihon.domain.license.model.LicenseNoticeFailureReason
import mihon.domain.license.model.LicenseNoticeResult

object LicenseNoticePolicy {

    private val displayNameOrder = compareBy<DependencyNoticeMetadata> { it.name.lowercase() }
        .thenBy { it.name }

    fun create(metadata: Result<List<DependencyNoticeMetadata>>): LicenseNoticeResult {
        return metadata.fold(
            onSuccess = ::create,
            onFailure = { LicenseNoticeResult.Failure(LicenseNoticeFailureReason.MALFORMED_METADATA) },
        )
    }

    private fun create(metadata: List<DependencyNoticeMetadata>): LicenseNoticeResult {
        if (metadata.any { it.name.isBlank() }) {
            return LicenseNoticeResult.Failure(LicenseNoticeFailureReason.MALFORMED_METADATA)
        }
        return LicenseNoticeResult.Success(
            notices = metadata
                .sortedWith(displayNameOrder)
                .map { dependency ->
                    DependencyNotice(
                        name = dependency.name,
                        website = dependency.website?.takeUnless(String::isBlank),
                        license = dependency.licenses.firstOrNull(),
                    )
                },
        )
    }
}
