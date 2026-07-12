package mihon.desktop.reader

const val EXTERNAL_CHAPTER_URL_PREFIX = "external:"

fun externalChapterUrl(url: String): String = EXTERNAL_CHAPTER_URL_PREFIX + url

fun String.externalChapterUrlOrNull(): String? =
    takeIf { startsWith(EXTERNAL_CHAPTER_URL_PREFIX) }
        ?.removePrefix(EXTERNAL_CHAPTER_URL_PREFIX)
        ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
