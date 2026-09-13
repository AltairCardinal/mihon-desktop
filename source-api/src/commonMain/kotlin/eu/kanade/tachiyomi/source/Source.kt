package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.awaitSingle
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import rx.Observable

/**
 * A basic interface for creating a source. It could be an online source, a local source, etc.
 */
interface Source {

    /**
     * ID for the source. Must be unique.
     */
    val id: Long

    /**
     * Name of the source.
     */
    val name: String

    val lang: String
        get() = ""

    /**
     * Whether this source provides a latest-updates listing.
     *
     * Sources that predate the combined Source API do not provide catalogue operations and must
     * explicitly opt in by implementing this property (normally through [CatalogueSource]).
     * @since tachiyomix 1.6
     */
    val supportsLatest: Boolean
        get() = false

    /**
     * Returns the list of filters for the source.
     * @since tachiyomix 1.6
     */
    fun getFilterList(): FilterList = FilterList()

    /**
     * Returns a page of popular manga. A source without catalogue support must not manufacture an
     * empty successful page, so the default fails explicitly.
     * @since tachiyomix 1.6
     */
    suspend fun getPopularManga(page: Int): MangasPage =
        throw UnsupportedOperationException("Source does not support popular manga")

    /**
     * Returns a page of latest manga updates. A source without catalogue support must not
     * manufacture an empty successful page, so the default fails explicitly.
     * @since tachiyomix 1.6
     */
    suspend fun getLatestUpdates(page: Int): MangasPage =
        throw UnsupportedOperationException("Source does not support latest updates")

    /**
     * Returns a page of search results. A source without catalogue support must not manufacture an
     * empty successful page, so the default fails explicitly.
     * @since tachiyomix 1.6
     */
    suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage =
        throw UnsupportedOperationException("Source does not support manga search")

    /**
     * Get the updated details for a manga.
     *
     * @since extensions-lib 1.5
     * @param manga the manga to update.
     * @return the updated manga.
     */
    @Suppress("DEPRECATION")
    suspend fun getMangaDetails(manga: SManga): SManga {
        return fetchMangaDetails(manga).awaitSingle()
    }

    /**
     * Get all the available chapters for a manga.
     *
     * @since extensions-lib 1.5
     * @param manga the manga to update.
     * @return the chapters for the manga.
     */
    @Suppress("DEPRECATION")
    suspend fun getChapterList(manga: SManga): List<SChapter> {
        return fetchChapterList(manga).awaitSingle()
    }

    /**
     * Fetches the requested details and/or chapter updates through the legacy suspend bridge.
     *
     * The flags are intentionally handled in one compatibility adapter. Unrequested values are
     * preserved by identity, while cancellation and source exceptions are allowed to propagate.
     * @since tachiyomix 1.6
     */
    suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = supervisorScope {
        val mangaUpdate = if (fetchDetails) async { getMangaDetails(manga) } else null
        val chapterUpdate = if (fetchChapters) async { getChapterList(manga) } else null
        SMangaUpdate(
            manga = mangaUpdate?.await() ?: manga,
            chapters = chapterUpdate?.await() ?: chapters,
        )
    }

    /**
     * Get the list of pages a chapter has. Pages should be returned
     * in the expected order; the index is ignored.
     *
     * @since extensions-lib 1.5
     * @param chapter the chapter.
     * @return the pages for the chapter.
     */
    @Suppress("DEPRECATION")
    suspend fun getPageList(chapter: SChapter): List<Page> {
        return fetchPageList(chapter).awaitSingle()
    }

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getMangaDetails"),
    )
    fun fetchMangaDetails(manga: SManga): Observable<SManga> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getChapterList"),
    )
    fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getPageList"),
    )
    fun fetchPageList(chapter: SChapter): Observable<List<Page>> =
        throw IllegalStateException("Not used")
}
