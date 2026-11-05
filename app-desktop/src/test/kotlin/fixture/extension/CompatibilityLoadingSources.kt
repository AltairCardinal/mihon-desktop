package fixture.extension

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga

object CompatibilityLoadControl {
    var site = "healthy"
    var failure: Throwable = NoSuchMethodError("controlled future API")
    var entered = mutableSetOf<String>()
    var onCheck: ((String) -> Unit)? = null
    fun check(site: String) {
        entered += site
        onCheck?.invoke(site)
        if (this.site == site) throw failure
    }
}

class CompatibilityConstructedSource : Source {
    init {
        CompatibilityLoadControl.check("construction")
    }
    override val id = 510L
    override val name = "Construction fixture"
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) = MangasPage(
        listOf(
            SManga.create().apply {
                url = "/healthy"
                title = "Healthy search"
            },
        ),
        false,
    )
}

class CompatibilityMetadataSource : Source {
    override val id: Long get() {
        CompatibilityLoadControl.check("id")
        return 511L
    }
    override val name: String get() {
        CompatibilityLoadControl.check("name")
        return "Metadata fixture"
    }
    override val lang: String get() {
        CompatibilityLoadControl.check("lang")
        return "en"
    }
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) = MangasPage(
        listOf(
            SManga.create().apply {
                url = "/healthy"
                title = "Healthy search"
            },
        ),
        false,
    )
}

class CompatibilityLoadingFactory : SourceFactory {
    override fun createSources(): List<Source> {
        CompatibilityLoadControl.check("factory")
        return listOf(CompatibilityConstructedSource(), CompatibilityMetadataSource())
    }
}
