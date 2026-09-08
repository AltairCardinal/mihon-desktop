package mihon.desktop.ui.library

import tachiyomi.domain.library.model.LibraryManga

internal data class LibraryRemovalPolicy(
    val canDeleteDownloads: Boolean,
) {
    fun canConfirm(removeFromLibrary: Boolean, deleteDownloads: Boolean): Boolean =
        removeFromLibrary || (canDeleteDownloads && deleteDownloads)
}

internal fun libraryRemovalPolicy(items: List<LibraryManga>): LibraryRemovalPolicy =
    LibraryRemovalPolicy(
        canDeleteDownloads = items.isNotEmpty() && items.none { it.manga.source == 0L },
    )
