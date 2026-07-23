package mihon.domain.license.model

data class DependencyNoticeMetadata(
    val name: String,
    val website: String?,
    val licenses: List<String>,
)

data class DependencyNotice(
    val name: String,
    val website: String?,
    val license: String?,
)

enum class LicenseNoticeFailureReason {
    MALFORMED_METADATA,
}

sealed interface LicenseNoticeResult {
    data class Success(
        val notices: List<DependencyNotice>,
    ) : LicenseNoticeResult

    data class Failure(
        val reason: LicenseNoticeFailureReason,
    ) : LicenseNoticeResult
}
