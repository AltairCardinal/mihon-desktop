package tachiyomi.domain.release.model

import java.net.URI

/** The published v1 feed and both clients share this deliberately narrow artifact contract. */
object ForkUpdateRules {
    const val MAX_INDEX_BYTES = 512L * 1024
    const val MAX_ANDROID_BYTES = 512L * 1024 * 1024
    const val MAX_ASSET_BYTES = 2L * 1024 * 1024 * 1024 - 1
    val sha256 = Regex("[0-9a-f]{64}")
    private val segment = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,179}")
    private val desktop = Regex("([0-9]+)\\.([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:\\.([0-9a-fA-F]{7,40}))?")

    fun desktopVersion(version: String): List<Int> {
        require(version.length <= 120) { "Invalid Desktop version" }
        val match = requireNotNull(desktop.matchEntire(version)) { "Invalid Desktop version" }
        return (1..4).map {
            requireNotNull(match.groupValues[it].toIntOrNull()) { "Invalid Desktop version component" }
        }
    }

    fun compareDesktopVersions(first: String, second: String): Int {
        val left = desktopVersion(first)
        val right = desktopVersion(second)
        for (index in left.indices) {
            left[index].compareTo(right[index]).takeIf { it != 0 }?.let { return it }
        }
        return 0
    }

    fun releaseTag(url: String): String = path(url, "tag", 1).single()

    fun downloadPath(url: String): Pair<String, String> = path(url, "download", 2).let { it[0] to it[1] }

    private fun path(url: String, kind: String, count: Int): List<String> {
        require(url.length <= 1024) { "Release URL is too long" }
        val uri = URI(url)
        require(uri.scheme == "https" && uri.rawAuthority == "github.com") { "Untrusted release origin" }
        require(uri.rawQuery == null && uri.rawFragment == null) { "Release URL must not have a query or fragment" }
        val prefix = "/${ForkUpdateRequest.REPOSITORY}/releases/$kind/"
        require(uri.rawPath.startsWith(prefix)) { "Release URL belongs to another project" }
        return uri.rawPath.removePrefix(prefix).split('/').also { parts ->
            require(parts.size == count && parts.all { segment.matches(it) && ".." !in it }) { "Unsafe release path" }
        }
    }
}
