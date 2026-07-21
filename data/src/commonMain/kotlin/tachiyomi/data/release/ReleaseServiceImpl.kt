package tachiyomi.data.release

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.model.ReleaseVariant
import tachiyomi.domain.release.service.ReleaseService

class ReleaseServiceImpl(
    private val client: OkHttpClient,
    private val json: Json,
    private val platformInfo: PlatformInfo,
    private val apiBaseUrl: String = "https://api.github.com",
) : ReleaseService {

    override suspend fun latest(arguments: GetApplicationRelease.Arguments): Release? {
        val release = with(json) {
            client
                .newCall(GET("${apiBaseUrl.trimEnd('/')}/repos/${arguments.repository}/releases/latest"))
                .awaitSuccess()
                .parseAs<GithubRelease>()
        }

        val asset = getAsset(release, platformInfo.releaseTarget(arguments.isFoss)) ?: return null

        return Release(
            version = release.version,
            info = release.info.substringBeforeLast("<!-->").replace(gitHubUsernameMentionRegex) { mention ->
                "[${mention.value}](https://github.com/${mention.value.substring(1)})"
            },
            releaseLink = release.releaseLink,
            downloadLink = asset.downloadLink,
            asset = asset.metadata,
        )
    }

    private fun getAsset(release: GithubRelease, target: tachiyomi.domain.release.model.ReleaseTarget): ParsedAsset? {
        val assets = release.assets.mapNotNull { it.parse(release.version) }
        return assets.firstOrNull { it.metadata.target == target }
            ?: target.arch
                ?.takeIf { target.variant == ReleaseVariant.STANDARD }
                ?.let { assets.firstOrNull { asset -> asset.metadata.target == target.copy(arch = null) } }
    }

    companion object {
        private val gitHubUsernameMentionRegex = """\B@([a-z0-9](?:-(?=[a-z0-9])|[a-z0-9]){0,38}(?<=[a-z0-9]))"""
            .toRegex(RegexOption.IGNORE_CASE)
    }
}
