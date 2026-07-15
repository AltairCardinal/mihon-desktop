package mihon.domain.extension.service

import mihon.domain.extension.model.isExtensionUpdateAvailable

fun interface ExtensionUpdatePolicy {
    fun isUpdateAvailable(
        availableVersionCode: Long,
        availableLibVersion: Double,
        installedVersionCode: Long,
        installedLibVersion: Double,
    ): Boolean
}

data object SharedExtensionUpdatePolicy : ExtensionUpdatePolicy {
    override fun isUpdateAvailable(
        availableVersionCode: Long,
        availableLibVersion: Double,
        installedVersionCode: Long,
        installedLibVersion: Double,
    ): Boolean = isExtensionUpdateAvailable(
        availableVersionCode = availableVersionCode,
        availableLibVersion = availableLibVersion,
        installedVersionCode = installedVersionCode,
        installedLibVersion = installedLibVersion,
    )
}
