package mihon.history.fixture

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray

/** Installed through the normal extension loader; only the task's fixed loopback fixture is reachable. */
class HistoryFormalSource : HttpSource() {
    override val id = 9_876_543_210L
    override val name = "HP02 local history fixture"
    override val lang = "en"
    override val supportsLatest = false
    override val baseUrl = "http://127.0.0.1:18464"

    override fun chapterPageParse(response: Response): SChapter =
        error("The fixed fixture supplies page lists directly; chapter-page parsing is not supported")

    override suspend fun getMangaDetails(manga: SManga): SManga {
        require(manga.url in WORK_URLS)
        return manga
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        require(manga.url in WORK_URLS)
        val response = client.newCall(Request.Builder().url("$baseUrl${manga.url}/catalog").build()).execute()
        response.use {
            check(it.isSuccessful) { "Fixture catalogue HTTP ${it.code}" }
            val data = JSONArray(checkNotNull(it.body).string())
            return (0 until data.length()).map { index ->
                val item = data.getJSONObject(index)
                SChapter.create().apply {
                    url = item.getString("url")
                    name = item.getString("name")
                    chapter_number = item.getDouble("number").toFloat()
                }
            }
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        require(WORK_URLS.any { chapter.url.startsWith("$it/chapter/") })
        require(chapter.url.substringAfterLast('/').toInt() in 1..3)
        return (0..3).map { Page(it, imageUrl = "$baseUrl/image/${it + 1}.png") }
    }

    companion object {
        val WORK_URLS = setOf("/hp02-history/success", "/hp02-history/failure")
    }
}
