package tachiyomi.data.chapter

/** Compatibility adapter for existing data callers. */
object ChapterSanitizer {
    fun String.sanitize(title: String): String =
        with(tachiyomi.domain.chapter.service.ChapterSanitizer) { this@sanitize.sanitize(title) }
}
