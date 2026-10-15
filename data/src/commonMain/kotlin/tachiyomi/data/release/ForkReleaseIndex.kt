package tachiyomi.data.release

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import tachiyomi.domain.release.model.ForkUpdateRequest
import tachiyomi.domain.release.model.ForkUpdateRules
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.model.ReleaseAsset
import tachiyomi.domain.release.model.ReleaseChecksum
import tachiyomi.domain.release.model.ReleaseOs
import tachiyomi.domain.release.model.ReleasePackageType
import tachiyomi.domain.release.model.ReleaseTarget
import tachiyomi.domain.release.model.ReleaseVariant
import tachiyomi.domain.release.model.UpdateChannel

internal fun selectForkRelease(index: JsonElement, request: ForkUpdateRequest, platformInfo: PlatformInfo): Release? {
    require(request.platform == platformInfo.releaseOs) { "Update platform does not match this client" }
    require(request.architecture == null || request.architecture == platformInfo.preferredAbi) {
        "Update architecture does not match this client"
    }
    val root = requireNotNull(index as? JsonObject) { "Update feed must be an object" }
    require(root.integer("schemaVersion") == 1L) { "Unsupported update schema" }
    val entries = requireNotNull(root["releases"] as? JsonArray) { "Missing releases" }
    require(entries.size <= 256) { "Too many update entries" }
    val releases = entries.map { parseForkEntry(requireNotNull(it as? JsonObject)) }
    val identities = releases.map { entry ->
        val release = entry.release
        listOf(
            release.asset.target.os,
            release.asset.target.arch,
            entry.channel,
            release.versionCode ?: ForkUpdateRules.desktopVersion(release.version),
        )
    }
    require(identities.distinct().size == identities.size) { "Ambiguous duplicate update identity" }
    if (request.platform == ReleaseOs.ANDROID) {
        require(request.expectedApplicationId == "app.mihon.desktop.fork") { "Missing trusted Android identity" }
        require(request.expectedCertificateSha256?.matches(ForkUpdateRules.sha256) == true) {
            "Missing trusted Android signer"
        }
    }
    return releases.asSequence()
        .filter { request.channel == UpdateChannel.TESTING || it.channel == UpdateChannel.STABLE }
        .filter { it.release.asset.target.os == request.platform }
        .filter { it.release.asset.target.arch == platformInfo.preferredAbi || it.release.asset.target.arch == null }
        .onEach {
            if (request.platform == ReleaseOs.ANDROID) {
                require(
                    it.release.applicationId == request.expectedApplicationId &&
                        it.release.certificateSha256 == request.expectedCertificateSha256,
                ) {
                    "Untrusted Android release identity"
                }
            }
        }
        .maxWithOrNull { first, second ->
            val order = if (request.platform == ReleaseOs.ANDROID) {
                requireNotNull(first.release.versionCode).compareTo(requireNotNull(second.release.versionCode))
            } else {
                ForkUpdateRules.compareDesktopVersions(first.release.version, second.release.version)
            }
            if (order != 0) {
                order
            } else {
                val architectureOrder = (first.release.asset.target.arch != null).compareTo(
                    second.release.asset.target.arch != null,
                )
                if (architectureOrder !=
                    0
                ) {
                    architectureOrder
                } else {
                    (first.channel == UpdateChannel.STABLE).compareTo(second.channel == UpdateChannel.STABLE)
                }
            }
        }?.release
}

private data class IndexedRelease(val channel: UpdateChannel, val release: Release)

private fun parseForkEntry(entry: JsonObject): IndexedRelease {
    val channel = when (entry.string("channel")) {
        "stable" -> UpdateChannel.STABLE
        "testing" -> UpdateChannel.TESTING
        else -> error("Unsupported update channel")
    }
    val platform = when (entry.string("platform")) {
        "android" -> ReleaseOs.ANDROID
        "windows" -> ReleaseOs.WINDOWS
        "macos" -> ReleaseOs.MACOS
        else -> error("Unsupported update platform")
    }
    val architecture = entry.string("arch")
    val packageName = entry.string("packageType")
    val allowedArchitectures = when (platform) {
        ReleaseOs.ANDROID -> setOf("universal", "arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        ReleaseOs.WINDOWS -> setOf("x86_64")
        else -> setOf("x86_64", "arm64")
    }
    val allowedPackages = when (platform) {
        ReleaseOs.ANDROID -> setOf("apk")
        ReleaseOs.WINDOWS -> setOf("zip", "msi")
        else -> setOf("zip", "dmg")
    }
    require(architecture in allowedArchitectures && packageName in allowedPackages) { "Unsupported update target" }
    val version = entry.string("version")
    require(version.length in 1..120) { "Invalid version name" }
    val android = platform == ReleaseOs.ANDROID
    val versionCode = if (android) entry.integer("versionCode").also { require(it in 1..2_100_000_000L) } else null
    val applicationId = if (android) {
        entry.string("applicationId").also {
            require(it == "app.mihon.desktop.fork")
        }
    } else {
        null
    }
    val certificate = if (android) {
        entry.string("certificateSha256").also {
            require(ForkUpdateRules.sha256.matches(it))
        }
    } else {
        null
    }
    if (android) {
        require(version.matches(Regex("[A-Za-z0-9_.+-]+"))) { "Invalid Android version name" }
    } else {
        ForkUpdateRules.desktopVersion(version)
        require(listOf("versionCode", "applicationId", "certificateSha256").none(entry::containsKey)) {
            "Android identity on Desktop entry"
        }
    }
    val releaseUrl = entry.string("releaseUrl")
    val downloadUrl = entry.string("downloadUrl")
    val tag = ForkUpdateRules.releaseTag(releaseUrl)
    val (assetTag, assetName) = ForkUpdateRules.downloadPath(downloadUrl)
    require(tag == assetTag && assetName.endsWith(".$packageName")) { "Release tag or asset extension mismatch" }
    val hash = entry.string("sha256").also {
        require(ForkUpdateRules.sha256.matches(it)) { "Invalid artifact SHA-256" }
    }
    val size = entry.integer("sizeBytes")
    require(size in 1..if (android) ForkUpdateRules.MAX_ANDROID_BYTES else ForkUpdateRules.MAX_ASSET_BYTES) {
        "Invalid artifact size"
    }
    val notes = entry.string("notes")
    require(
        notes.codePointCount(0, notes.length) <= 32768 && notes.none {
            it.code < 32 && it !in "\n\r\t"
        },
    ) { "Invalid release notes" }
    val packageType = when (packageName) {
        "apk" -> ReleasePackageType.APK
        "msi" -> ReleasePackageType.MSI
        "dmg" -> ReleasePackageType.DMG
        else -> ReleasePackageType.ARCHIVE
    }
    return IndexedRelease(
        channel,
        Release(
            version = version,
            info = notes,
            releaseLink = releaseUrl,
            downloadLink = downloadUrl,
            asset = ReleaseAsset(
                assetName,
                ReleaseTarget(
                    platform,
                    architecture.takeUnless {
                        it == "universal"
                    },
                    packageType,
                    ReleaseVariant.STANDARD,
                ),
                ReleaseChecksum("sha256", hash),
            ),
            versionCode = versionCode,
            sizeBytes = size,
            applicationId = applicationId,
            certificateSha256 = certificate,
        ),
    )
}

private fun JsonObject.string(key: String): String {
    val value = requireNotNull(this[key] as? JsonPrimitive) { "Missing string $key" }
    require(value.isString) { "Expected string $key" }
    return value.content
}

private fun JsonObject.integer(key: String): Long {
    val value = requireNotNull(this[key] as? JsonPrimitive) { "Missing integer $key" }
    require(!value.isString) { "Expected integer $key" }
    return requireNotNull(value.longOrNull) { "Invalid integer $key" }
}
