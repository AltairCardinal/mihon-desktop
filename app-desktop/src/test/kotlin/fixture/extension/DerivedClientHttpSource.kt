package fixture.extension

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import mihon.desktop.extension.ExtensionNetworkTestBridge
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Test fixture loaded through the production child-first extension classloader. */
class DerivedClientHttpSource : HttpSource() {
    override val id = 42L
    override val name = "Derived client fixture"
    override val lang = "en"
    override val baseUrl = "https://fixture.invalid"
    override val supportsLatest = false

    // Matches real extensions such as Manhuagui: capture a derived client in the constructor.
    override val client: OkHttpClient = ExtensionNetworkTestBridge.networkHelper.client.newBuilder().build()

    override fun popularMangaRequest(page: Int): Request = error("not used")
    override fun popularMangaParse(response: Response): MangasPage = error("not used")
    override fun latestUpdatesRequest(page: Int): Request = error("not used")
    override fun latestUpdatesParse(response: Response): MangasPage = error("not used")
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = error("not used")
    override fun searchMangaParse(response: Response): MangasPage = error("not used")
    override fun mangaDetailsParse(response: Response): SManga = error("not used")
    override fun chapterListParse(response: Response): List<SChapter> = error("not used")
    override fun chapterPageParse(response: Response): SChapter = error("not used")
    override fun pageListParse(response: Response): List<Page> = error("not used")
    override fun imageUrlParse(response: Response): String = error("not used")
}
