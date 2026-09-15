package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.source.model.StubSource

interface SourceManager {

    val isInitialized: StateFlow<Boolean>

    /** Legacy projection for consumers awaiting migration to the Source update API. */
    val catalogueSources: Flow<List<CatalogueSource>>

    /** Query-capable registered sources, including Source-only extensions. */
    val querySources: Flow<List<Source>>
        get() = catalogueSources

    fun get(sourceKey: Long): Source?

    fun getOrStub(sourceKey: Long): Source

    fun getOnlineSources(): List<HttpSource>

    fun getCatalogueSources(): List<CatalogueSource>

    /** Snapshot of the same registration authority exposed by [querySources]. */
    fun getQuerySources(): List<Source> = getCatalogueSources()

    fun getStubSources(): List<StubSource>
}
