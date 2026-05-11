package mihon.desktop.ui.browse

import tachiyomi.domain.manga.model.MangaWithChapterCount

/** Returns true when there are library entries with the same title as the manga being added. */
fun shouldShowDuplicateWarning(duplicates: List<MangaWithChapterCount>): Boolean =
    duplicates.isNotEmpty()
