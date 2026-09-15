package mihon.domain.extensionrepo.model

data class ExtensionRepo(
    val baseUrl: String,
    val name: String,
    val shortName: String?,
    val website: String,
    val signingKeyFingerprint: String,
    /** The latest catalog URL discovered from repo metadata, if one is known. */
    val indexUrl: String? = null,
    val extensionListUrl: String? = null,
    val contactDiscord: String? = null,
)

/** Compare the signing-key bytes independently of display separators and letter case. */
fun String.normalizedSigningKeyFingerprint(): String = trim().replace(":", "").lowercase()
