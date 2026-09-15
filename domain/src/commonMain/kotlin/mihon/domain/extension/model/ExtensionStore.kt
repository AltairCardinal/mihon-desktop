package mihon.domain.extension.model

/**
 * The stable identity and discovery metadata for a v2 extension repository.
 *
 * [indexUrl] is the URL that produced this store, while [extensionListUrl] is
 * an optional separate catalog payload. Neither URL changes the repository's
 * trusted identity, which remains the repository URL and signing key.
 */
data class ExtensionStore(
    val indexUrl: String,
    val name: String,
    val badgeLabel: String?,
    val signingKey: String,
    val contact: Contact,
    val isLegacy: Boolean,
    val extensionListUrl: String?,
) {
    data class Contact(
        val website: String,
        val discord: String?,
    )
}
