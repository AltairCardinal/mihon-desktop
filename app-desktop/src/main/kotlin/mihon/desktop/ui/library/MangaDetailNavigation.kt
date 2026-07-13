package mihon.desktop.ui.library

internal fun authorNavigationNameOrNull(author: String?): String? =
    author?.trim()?.takeIf(String::isNotEmpty)
