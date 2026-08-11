package tachiyomi.domain.creator.service

import eu.kanade.tachiyomi.util.lang.Hash

object CreatorSourceWorkKey {
    private const val BLANK_URL_PREFIX = "blank-manga:"
    private const val LEGACY_LOCAL_PREFIX = "legacy-manga:"

    fun stableUrl(
        url: String,
        title: String,
        author: String?,
        artist: String?,
    ): String {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isNotEmpty()) return trimmedUrl
        return blankUrl(title, author, artist)
    }

    fun portableUrl(
        storedUrl: String,
        title: String,
        author: String?,
        artist: String?,
    ): String {
        val trimmedUrl = storedUrl.trim()
        return if (trimmedUrl.isEmpty() || trimmedUrl.startsWith(LEGACY_LOCAL_PREFIX)) {
            blankUrl(title, author, artist)
        } else {
            trimmedUrl
        }
    }

    fun isBlankFallback(url: String): Boolean = url.startsWith(BLANK_URL_PREFIX)

    private fun blankUrl(title: String, author: String?, artist: String?): String {
        val portableMetadata = listOf(title, author.orEmpty(), artist.orEmpty())
            .joinToString("\u001f") { CreatorNameNormalizer.normalize(it) }
        return BLANK_URL_PREFIX + Hash.sha256(portableMetadata)
    }
}
