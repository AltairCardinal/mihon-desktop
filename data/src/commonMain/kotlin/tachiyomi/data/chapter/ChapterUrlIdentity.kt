package tachiyomi.data.chapter

import tachiyomi.data.Database
import tachiyomi.domain.chapter.model.ChapterUrlIdentity

fun Database.readChapterUrlIdentity(chapterId: Long): ChapterUrlIdentity {
    val chapter = chaptersQueries.getChapterById(chapterId).executeAsOne()
    val aliases = chapter_url_aliasesQueries.getForChapter(chapterId).executeAsList()
    if (aliases.isEmpty()) return ChapterUrlIdentity(chapter.url)
    val canonical = aliases.map { it.canonical_url }.distinct().single()
    require(aliases.all { it.manga_id == chapter.manga_id && it.url.isNotBlank() })
    require(canonical.isNotBlank() && (canonical == chapter.url || aliases.any { it.url == canonical }))
    return ChapterUrlIdentity(canonical, aliases.map { it.url })
}

/** Must run in the caller's chapter/restore transaction, without nested database dispatch. */
fun Database.restoreChapterUrlAliases(mangaId: Long, chapterId: Long, identity: ChapterUrlIdentity) {
    val chapter = chaptersQueries.getChapterById(chapterId).executeAsOne()
    require(chapter.manga_id == mangaId)
    val urls = (identity.aliases + identity.canonicalUrl).distinct()
    require(urls.all { it.isNotBlank() } && identity.canonicalUrl in (identity.aliases + chapter.url))
    val existing = chapter_url_aliasesQueries.getForChapter(chapterId).executeAsList()
    if (existing.isNotEmpty()) require(readChapterUrlIdentity(chapterId).canonicalUrl == identity.canonicalUrl)
    urls.forEach { url ->
        require(!chapter_url_aliasesQueries.hasDifferentDirectOwner(mangaId, url, chapterId).executeAsOne()) {
            "Chapter URL belongs to another chapter"
        }
        val owner = chapter_url_aliasesQueries.getOwner(mangaId, url).executeAsOneOrNull()
        require(owner == null || owner == chapterId) { "Chapter alias belongs to another chapter" }
        if (owner == null) chapter_url_aliasesQueries.insertAlias(chapterId, mangaId, url, identity.canonicalUrl)
    }
}
