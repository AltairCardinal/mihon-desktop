package mihon.desktop.ui.library

/** Returns the id of a random manga from the given id list, or null if the list is empty. */
fun pickRandomMangaId(ids: List<Long>): Long? = ids.randomOrNull()
