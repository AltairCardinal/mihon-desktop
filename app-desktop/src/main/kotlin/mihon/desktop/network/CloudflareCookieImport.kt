package mihon.desktop.network

/** Name of the Cloudflare clearance cookie. */
const val CF_CLEARANCE_COOKIE_NAME = "cf_clearance"

sealed interface CookieImportResult {
    data class Valid(val domain: String, val value: String) : CookieImportResult
    data object InvalidDomain : CookieImportResult
    data object InvalidValue : CookieImportResult
}

/**
 * Validates and normalizes the inputs for a manual Cloudflare cookie import.
 *
 * Strips scheme (http/https) and trailing paths from [rawDomain] to produce
 * a bare hostname. Returns [CookieImportResult.Valid] if both inputs are usable.
 */
fun validateCloudflareCookieInput(rawDomain: String, value: String): CookieImportResult {
    if (value.isBlank()) return CookieImportResult.InvalidValue

    val cleaned = rawDomain.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore("/")
        .trim()

    if (cleaned.isBlank()) return CookieImportResult.InvalidDomain

    return CookieImportResult.Valid(domain = cleaned, value = value.trim())
}
