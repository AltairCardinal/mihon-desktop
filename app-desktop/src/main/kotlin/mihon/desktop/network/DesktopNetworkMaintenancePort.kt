package mihon.desktop.network

sealed interface DesktopCloudflareCookieImportResult {
    data class Imported(val host: String) : DesktopCloudflareCookieImportResult
    data object InvalidDomain : DesktopCloudflareCookieImportResult
    data object InvalidValue : DesktopCloudflareCookieImportResult
    data object DomainParseFailed : DesktopCloudflareCookieImportResult
}

interface DesktopNetworkMaintenancePort {
    fun importCloudflareCookie(domain: String, value: String): DesktopCloudflareCookieImportResult

    fun clearCookies()
}
