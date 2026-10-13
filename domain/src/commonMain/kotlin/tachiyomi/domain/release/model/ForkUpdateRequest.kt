package tachiyomi.domain.release.model

enum class UpdateChannel { STABLE, TESTING }

data class ForkUpdateRequest(
    val channel: UpdateChannel,
    val platform: ReleaseOs,
    val installedVersionCode: Long? = null,
    val expectedApplicationId: String? = null,
    val expectedCertificateSha256: String? = null,
    val architecture: String? = null,
) {
    companion object {
        const val INDEX_URL = "https://altaircardinal.github.io/mihon-desktop/updates/v1.json"
        const val REPOSITORY = "AltairCardinal/mihon-desktop"
    }
}
