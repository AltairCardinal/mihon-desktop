package eu.kanade.tachiyomi.ui.browse.migration.search

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchItemResult
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchScreenModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceMangaSearchService
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.util.concurrent.atomic.AtomicInteger

class MigrateSearchMetadataWiringTest {
    @Test
    fun `actual migration model uses the supplied registration and preserves explicit source order`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        var model: MigrateSearchScreenModel? = null
        try {
            val first = RegisteredSource()
            val second = RegisteredSource()
            val sources = listOf(first, second)
            val manager = object : SourceManager by mockk(relaxed = true) {
                override fun get(sourceKey: Long): Source? = when (sourceKey) {
                    10L -> first
                    20L -> second
                    else -> null
                }
                override fun getRegisteredSourceId(source: Source): Long? = when {
                    source === first -> 10L
                    source === second -> 20L
                    else -> null
                }
            }
            val preferences = mockk<SourcePreferences> {
                every { migrationSources() } returns preference(listOf(20L, 10L))
                every { enabledLanguages() } returns preference(emptySet<String>())
                every { disabledSources() } returns preference(setOf("10", "20"))
                every { pinnedSources() } returns preference(emptySet<String>())
                every { globalSearchFilterState() } returns preference(false)
            }
            val getManga = mockk<GetManga> {
                coEvery { await(1L) } returns Manga.create().copy(id = 1L, source = 30L, title = "migration")
            }
            Injekt.addSingleton(preferences)
            Injekt.addSingleton(getManga)
            Injekt.addSingleton<SourceManager>(object : SourceManager by mockk(relaxed = true) {
                override fun getRegisteredSourceId(source: Source): Long? = null
            })
            Injekt.addSingleton<ExtensionManager>(
                mockk {
                    every { installedExtensionsFlow } returns MutableStateFlow(emptyList<Extension.Installed>())
                },
            )
            Injekt.addSingleton<NetworkToLocalManga>(mockk())
            Injekt.addSingleton(SourceMangaSearchService())
            val active = MigrateSearchScreenModel(1L, getManga, manager, preferences)
            model = active
            runCurrent()
            val state = withContext(Dispatchers.Default) {
                withTimeout(5_000) { active.state.first { it.total == 2 && it.progress == 2 } }
            }
            sources.forEach {
                assertInstanceOf(SearchItemResult.Success::class.java, state.items[it])
                assertEquals(1, it.searchCalls.get())
                assertEquals(0, it.idReads.get())
            }
            assertEquals(listOf(second, first), state.items.keys.toList())
        } finally {
            model?.let {
                val dispatcher = SearchScreenModel::class.java.getDeclaredField("coroutineDispatcher")
                    .apply { isAccessible = true }.get(it) as AutoCloseable
                dispatcher.close()
            }
            Injekt = previous
            Dispatchers.resetMain()
        }
    }

    private fun <T> preference(value: T): Preference<T> = mockk {
        every { get() } returns value
        every { changes() } returns flowOf(value)
    }

    private class RegisteredSource : CatalogueSource {
        val idReads = AtomicInteger()
        val searchCalls = AtomicInteger()
        override val id: Long get() {
            idReads.incrementAndGet()
            throw NoSuchMethodError("Registered ID getter must not be re-read")
        }
        override val name = "Same migration name"
        override val lang = "en"
        override val supportsLatest = false
        override fun getFilterList() = FilterList()
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            searchCalls.incrementAndGet()
            return MangasPage(emptyList(), false)
        }
    }
}
