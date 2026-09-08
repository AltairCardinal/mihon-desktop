package tachiyomi.domain.library

import tachiyomi.domain.library.model.LibraryManga

/**
 * Matches a library query using the fixed Mihon library search contract.
 *
 * The caller supplies the display name because resolving a source is platform-specific. The
 * prefix extraction intentionally remains case-sensitive after the case-insensitive prefix check,
 * matching the upstream implementation.
 */
fun matchesLibraryQuery(
    libraryManga: LibraryManga,
    query: String,
    sourceName: String,
    localSourceId: Long = 0L,
): Boolean {
    val manga = libraryManga.manga
    if (query.startsWith("id:", ignoreCase = true)) {
        return manga.id == query.substringAfter("id:").toLongOrNull()
    }
    if (query.startsWith("src:", ignoreCase = true)) {
        val sourceQuery = query.substringAfter("src:")
        return if (sourceQuery.equals("local", ignoreCase = true)) {
            manga.source == localSourceId
        } else {
            sourceQuery.toLongOrNull()?.let { manga.source == it } == true
        }
    }

    val textFields = listOfNotNull(
        manga.title,
        manga.author,
        manga.artist,
        manga.description,
    )
    if (textFields.any { it.contains(query, ignoreCase = true) }) return true

    return query.split(",").map(String::trim).all { term ->
        val negated = term.startsWith("-")
        val value = term.removePrefix("-").trimStart()
        val matched = sourceName.contains(value, ignoreCase = true) ||
            manga.genre.orEmpty().any { it.equals(value, ignoreCase = true) }
        if (negated) !matched else matched
    }
}
