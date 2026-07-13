package mihon.desktop.ui.library

import mihon.desktop.ui.authors.AuthorDetailScreen

internal fun authorNavigationNameOrNull(author: String?): String? =
    author?.trim()?.takeIf(String::isNotEmpty)

internal fun authorDetailScreenOrNull(author: String?, creatorId: Long): AuthorDetailScreen? =
    authorNavigationNameOrNull(author)?.let { AuthorDetailScreen(creatorId, collectOnOpen = true) }
