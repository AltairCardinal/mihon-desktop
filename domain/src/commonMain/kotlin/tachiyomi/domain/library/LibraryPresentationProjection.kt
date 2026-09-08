package tachiyomi.domain.library

data class LibraryBadgeProjection(
    val downloadCount: Long,
    val unreadCount: Long,
    val isLocal: Boolean,
    val sourceLanguage: String,
)

fun projectLibraryBadges(
    downloadCount: () -> Long,
    unreadCount: () -> Long,
    isLocal: () -> Boolean,
    sourceLanguage: () -> String,
    showDownloadBadge: Boolean,
    showUnreadBadge: Boolean,
    showLocalBadge: Boolean,
    showLanguageBadge: Boolean,
) = LibraryBadgeProjection(
    downloadCount = if (showDownloadBadge) downloadCount() else 0L,
    unreadCount = if (showUnreadBadge) unreadCount() else 0L,
    isLocal = showLocalBadge && isLocal(),
    sourceLanguage = if (showLanguageBadge) sourceLanguage() else "",
)

data class LibraryToolbarProjection(val title: String, val count: Int?)

fun projectLibraryToolbar(
    libraryTitle: String,
    defaultCategoryTitle: String,
    categoryName: String?,
    isSystemCategory: Boolean,
    showCategoryTabs: Boolean,
    showMangaCount: Boolean,
    categoryCount: Int,
    libraryCount: Int,
): LibraryToolbarProjection {
    if (categoryName == null) return LibraryToolbarProjection(libraryTitle, null)
    val title = if (showCategoryTabs) {
        libraryTitle
    } else if (isSystemCategory) {
        defaultCategoryTitle
    } else {
        categoryName
    }
    val count = when {
        !showMangaCount -> null
        showCategoryTabs -> libraryCount
        else -> categoryCount
    }
    return LibraryToolbarProjection(title, count)
}

fun showLibraryIntervalFilter(isNonReleaseBuild: Boolean, restrictionEnabled: Boolean) =
    isNonReleaseBuild && restrictionEnabled
