package ec.future

import aex00.external.v16.V16Source
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import okhttp3.Interceptor
import okhttp3.Response

/** Real external Source dispatch and interceptor/parse sites for later isolation tests. */
class FutureSource : Source by V16Source("en") {
    override val id: Long = 0xEC0001L
    override val name: String = "EC future ABI fixture"

    override fun getFilterList(): FilterList {
        LinkageProbe.missingMethod()
        return FilterList()
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        LinkageProbe.missingClass()
        return MangasPage(emptyList(), false)
    }

    fun parse(response: Response): MangasPage {
        response.use { LinkageProbe.missingMethod() }
        return MangasPage(emptyList(), false)
    }
}

class FutureInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        LinkageProbe.missingMethod()
        return chain.proceed(chain.request())
    }
}
