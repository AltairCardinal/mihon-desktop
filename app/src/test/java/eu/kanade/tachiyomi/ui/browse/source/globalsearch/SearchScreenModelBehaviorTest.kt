package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.inspectSourceFailure
import eu.kanade.tachiyomi.source.DelayedMangaFixture
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import mihon.domain.error.AppError
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourcePageResult
import tachiyomi.domain.source.service.SourceQuery
import tachiyomi.domain.source.service.SourceRecoveryAction
import tachiyomi.domain.source.service.sourceFailureIdentity

class SearchScreenModelBehaviorTest {
    @Test
    fun `delayed manga fields fail only their accepted global source before persistence`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            for (field in listOf("title", "memo", "url")) {
                val original = java.io.IOException("returned field", NoSuchMethodError("future $field"))
                val returned = DelayedMangaFixture(field, original, if (field == "url") 1 else 0)
                val broken = object : BaseCatalogueSource() {
                    override val name = "Broken returned fields"
                    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) =
                        MangasPage(listOf(returned), false)
                }
                val healthy = object : BaseCatalogueSource() {
                    override val id = 10L
                    override val name = "Healthy returned fields"
                    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) =
                        MangasPage(listOf(manga("/healthy", "Healthy")), false)
                }
                val workerErrors = mutableListOf<Throwable>()
                val owner = SupervisorJob()
                val scope = CoroutineScope(
                    owner + StandardTestDispatcher(testScheduler) +
                        CoroutineExceptionHandler { _, error -> workerErrors += error },
                )
                val saved = mutableListOf<tachiyomi.domain.manga.model.Manga>()
                val repository = mockk<MangaRepository>()
                coEvery { repository.insertNetworkManga(any()) } answers {
                    firstArg<List<tachiyomi.domain.manga.model.Manga>>().also { saved += it }
                }
                val records = mutableListOf<String>()
                try {
                    val model = screenModel(
                        broken,
                        additionalSources = listOf(healthy),
                        ownedScope = scope,
                        mangaRepository = repository,
                        sourceFailureLog = records::add,
                    )
                    model.updateSearchQuery("query")
                    model.search()
                    runCurrent()
                    assertEquals(emptyList<Throwable>(), workerErrors)
                    val failed = assertInstanceOf(SearchItemResult.Error::class.java, model.state.value.items[broken])
                    val error = assertInstanceOf(AppError.ExtensionIncompatible::class.java, failed.pageError.error)
                    assertEquals("selected.extension", error.sourceFailureIdentity()?.extensionPackage)
                    assertSame(original.cause, error.cause?.inspectSourceFailure()?.linkage)
                    assertEquals(SourceRecoveryAction.None, failed.pageError.recoveryAction)
                    assertInstanceOf(SearchItemResult.Success::class.java, model.state.value.items[healthy])
                    assertEquals(listOf(10L), saved.map { it.source })
                    assertEquals(1, records.size)
                } finally {
                    owner.cancel()
                }
            }
        }

    @Test
    fun `login feedback accepts only the captured source error generation and reports once`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val source = object : BaseCatalogueSource() {
                override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
                    throw HttpException(401)
            }
            val records = mutableListOf<String>()
            val model = screenModel(source, sourceFailureLog = records::add)
            model.updateSearchQuery("old")
            model.search()
            runCurrent()
            val old = (model.state.value.items[source] as SearchItemResult.Error).pageError
            val oldGeneration = model.state.value.generation
            model.updateSearchQuery("new")
            model.search()
            runCurrent()
            val current = (model.state.value.items[source] as SearchItemResult.Error).pageError
            val generation = model.state.value.generation
            val cause = NoSuchMethodError("login target")
            val transport = SourceCompatibilityException(cause, SourceOperationPhase.METADATA)
            val error = AppError.ExtensionIncompatible(transport)
            assertEquals(false, model.isCurrentSourceError(source, old, oldGeneration))
            assertEquals(false, model.reportSourceRecoveryFailure(source, old, oldGeneration, error))
            assertSame(current, (model.state.value.items[source] as SearchItemResult.Error).pageError)
            assertEquals(0, records.size)
            assertEquals(true, model.reportSourceRecoveryFailure(source, current, generation, error))
            val accepted = (model.state.value.items[source] as SearchItemResult.Error).pageError
            assertInstanceOf(AppError.ExtensionIncompatible::class.java, accepted.error)
            assertEquals(SourceRecoveryAction.None, accepted.recoveryAction)
            assertEquals(false, model.reportSourceRecoveryFailure(source, current, generation, error))
            assertEquals(1, records.size)
        }

    @Test
    fun `known hidden sources and excluded languages do not execute unselected metadata`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val hidden = MetadataSource(9L, "Hidden").apply { nameError = AssertionError("unselected hidden name") }
            val excluded = MetadataSource(10L, "Excluded", languageTag = "fr").apply {
                nameError = AssertionError("unselected language name")
            }
            val healthy = MetadataSource(11L, "Selected")
            val model = screenModel(
                hidden,
                additionalSources = listOf(excluded, healthy),
                disabledSourceIds = setOf("9"),
            )
            model.updateSearchQuery("query")
            model.search()
            runCurrent()
            assertEquals(setOf(healthy), model.state.value.items.keys)
            assertEquals(0, hidden.nameReads)
            assertEquals(0, hidden.languageReads)
            assertEquals(0, excluded.nameReads)
            assertEquals(1, excluded.languageReads)
            assertEquals(0, hidden.searchCalls)
            assertEquals(0, excluded.searchCalls)
            assertEquals(1, healthy.searchCalls)
        }

    @Test
    fun `registered IDs keep equal headers distinct without another extension ID getter`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val broken = MetadataSource(9L, "Same header").apply { searchError = NoSuchMethodError("future search") }
            val healthy = MetadataSource(10L, "Same header")
            val model = screenModel(broken, additionalSources = listOf(healthy))
            val reads = listOf(broken.idReads, healthy.idReads)
            broken.idBroken = true
            healthy.idBroken = true
            model.updateSearchQuery("query")
            model.search()
            runCurrent()
            assertEquals(2, model.state.value.items.size)
            assertEquals(2, model.state.value.progress)
            assertInstanceOf(SearchItemResult.Error::class.java, model.state.value.items[broken])
            assertInstanceOf(SearchItemResult.Success::class.java, model.state.value.items[healthy])
            assertEquals(reads, listOf(broken.idReads, healthy.idReads))
            assertEquals(listOf(1, 1), listOf(broken.nameReads, healthy.nameReads))
            assertEquals(listOf(1, 1), listOf(broken.languageReads, healthy.languageReads))
        }

    @Test
    fun `metadata linkage fails only its source before filters and explicit retry rechecks`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            for (field in listOf("name", "language")) {
                val broken = MetadataSource(9L, "Broken", field)
                val healthy = MetadataSource(10L, "Healthy")
                val model = screenModel(broken, additionalSources = listOf(healthy))
                broken.metadataBroken = true
                model.updateSearchQuery("query")
                model.search()
                runCurrent()
                val failed = assertInstanceOf(SearchItemResult.Error::class.java, model.state.value.items[broken])
                val error = assertInstanceOf(AppError.ExtensionIncompatible::class.java, failed.pageError.error)
                val marker = assertInstanceOf(
                    SourceCompatibilityException::class.java,
                    error.cause,
                )
                assertEquals(SourceOperationPhase.METADATA, marker.phase)
                assertEquals(9L, marker.identity?.sourceId)
                assertEquals("selected.extension", marker.identity?.extensionPackage)
                assertInstanceOf(SearchItemResult.Success::class.java, model.state.value.items[healthy])
                assertEquals(0, broken.filterCalls)
                assertEquals(0, broken.searchCalls)
                assertEquals(1, healthy.searchCalls)
                broken.metadataBroken = false
                if (field == "name") model.setSourceFilter(SourceFilter.All) else model.retry()
                runCurrent()
                assertEquals(null, model.state.value.metadata.first { it.source === broken }.error)
                assertInstanceOf(SearchItemResult.Success::class.java, model.state.value.items[broken])
                assertEquals(1, broken.filterCalls)
                assertEquals(1, broken.searchCalls)
                assertEquals(if (field == "name") 1 else 2, healthy.searchCalls)
                assertEquals(2, model.state.value.progress)
            }
        }

    @Test
    fun `unknown registration never probes ID or dispatches source API while healthy source completes`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val unknown = MetadataSource(9L, "Unknown registration").apply { idBroken = true }
            val healthy = MetadataSource(10L, "Healthy")
            val model = screenModel(
                unknown,
                additionalSources = listOf(healthy),
                registeredSourceIds = listOf(healthy to 10L),
            )
            model.updateSearchQuery("query")
            model.search()
            runCurrent()
            val failed = assertInstanceOf(SearchItemResult.Error::class.java, model.state.value.items[unknown])
            assertInstanceOf(AppError.Unknown::class.java, failed.pageError.error)
            assertInstanceOf(SearchItemResult.Success::class.java, model.state.value.items[healthy])
            assertEquals(0, unknown.idReads)
            assertEquals(0, unknown.filterCalls)
            assertEquals(0, unknown.searchCalls)
            assertEquals(2, model.state.value.progress)
        }

    @Test
    fun `diagnostic writer IO failure preserves broken source and healthy search result`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val original = NoSuchMethodError("private-query token=credential")
            val broken = object : BaseCatalogueSource() {
                override val id = 9L
                override val name = "Broken"
                override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
                    throw original
            }
            val healthy = object : BaseCatalogueSource() {
                override val id = 10L
                override val name = "Healthy"
                override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) =
                    MangasPage(emptyList(), false)
            }
            val records = mutableListOf<String>()
            var stateAtWrite: SearchItemResult? = null
            lateinit var model: SearchScreenModel
            model = screenModel(broken, additionalSources = listOf(healthy), sourceFailureLog = {
                records += it
                stateAtWrite = model.state.value.items[broken]
                throw java.io.IOException("controlled diagnostic sink failure")
            })
            model.updateSearchQuery("private-query")
            model.search()
            runCurrent()
            assertEquals(1, records.size)
            val error = assertInstanceOf(SearchItemResult.Error::class.java, model.state.value.items[broken])
            assertSame(error, assertInstanceOf(SearchItemResult.Error::class.java, stateAtWrite))
            assertInstanceOf(AppError.ExtensionIncompatible::class.java, error.pageError.error)
            org.junit.jupiter.api.Assertions.assertTrue(
                generateSequence(error.pageError.error.cause) { it.cause }.any { it === original },
            )
            assertInstanceOf(SearchItemResult.Success::class.java, model.state.value.items[healthy])
            org.junit.jupiter.api.Assertions.assertFalse(records.single().contains("private-query"))
            org.junit.jupiter.api.Assertions.assertFalse(records.single().contains("credential"))
        }

    @Test
    fun `equal source objects never exchange their frozen extension owners`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val firstRelease = CompletableDeferred<Unit>()
            val secondRelease = CompletableDeferred<Unit>()
            val starts = mutableListOf<Long>()
            fun source(id: Long, release: CompletableDeferred<Unit>) = object : BaseCatalogueSource() {
                override val id = id
                override val name = "Source $id"
                override fun equals(other: Any?) = other is Source
                override fun hashCode() = 7
                override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
                    starts += id
                    release.await()
                    throw NoSuchMethodError("future API $id")
                }
            }
            val first = source(101L, firstRelease)
            val second = source(202L, secondRelease)
            fun owner(source: Source, pkg: String) = mockk<Extension.Installed> {
                every { pkgName } returns pkg
                every { name } returns pkg
                every { versionName } returns "1.0.0"
                every { versionCode } returns 1L
                every { sources } returns listOf(source)
            }
            val installed = MutableStateFlow(listOf(owner(first, "first.package"), owner(second, "second.package")))
            val model = screenModel(first, additionalSources = listOf(second), installedExtensions = installed)
            model.updateSearchQuery("query")
            model.search()
            runCurrent()
            assertEquals(setOf(101L, 202L), starts.toSet())
            secondRelease.complete(Unit)
            runCurrent()
            firstRelease.complete(Unit)
            runCurrent()
            val failure = assertInstanceOf(SearchItemResult.Error::class.java, model.state.value.items[first])
            val diagnostics = generateSequence(failure.pageError.error.cause) { it.cause }
                .mapNotNull { it.message }.joinToString("\n")
            org.junit.jupiter.api.Assertions.assertTrue(diagnostics.contains("first.package"), diagnostics)
            org.junit.jupiter.api.Assertions.assertFalse(diagnostics.contains("second.package"), diagnostics)
        }

    @Test
    fun `failure diagnostics retain the installed owner from before extension replacement`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val source = object : BaseCatalogueSource() {
                override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
                    calls++
                    started.complete(Unit)
                    release.await()
                    throw NoSuchMethodError("future search API")
                }
            }
            fun installed(version: String) = mockk<eu.kanade.tachiyomi.extension.model.Extension.Installed> {
                every { pkgName } returns "trusted.extension"
                every { name } returns "Installed extension"
                every { versionName } returns version
                every { versionCode } returns if (version == "1.2.3") 123L else 999L
                every { sources } returns listOf(source)
            }
            val installedFlow = MutableStateFlow(listOf(installed("1.2.3")))
            val model = screenModel(source, installedExtensions = installedFlow)
            model.updateSearchQuery("private-query-must-not-be-recorded")
            model.search()
            runCurrent()
            started.await()
            installedFlow.value = listOf(installed("9.9.9"))
            release.complete(Unit)
            runCurrent()

            val failure = assertInstanceOf(SearchItemResult.Error::class.java, model.state.value.items[source])
            val error = assertInstanceOf(AppError.ExtensionIncompatible::class.java, failure.pageError.error)
            val diagnostics = generateSequence(error.cause) { it.cause }.mapNotNull { it.message }.joinToString("\n")
            org.junit.jupiter.api.Assertions.assertTrue(diagnostics.contains("trusted.extension"), diagnostics)
            org.junit.jupiter.api.Assertions.assertTrue(diagnostics.contains("1.2.3"), diagnostics)
            org.junit.jupiter.api.Assertions.assertFalse(diagnostics.contains("9.9.9"), diagnostics)
            org.junit.jupiter.api.Assertions.assertFalse(diagnostics.contains("private-query"), diagnostics)
            assertEquals(1, calls)
            model.search()
            runCurrent()
            assertEquals(2, calls)
            val replaced = assertInstanceOf(SearchItemResult.Error::class.java, model.state.value.items[source])
            assertEquals("9.9.9", replaced.pageError.error.sourceFailureIdentity()?.versionName)
        }

    @Test
    fun `incompatible filters fail only their source and explicit retry prepares again`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val missing = NoSuchMethodError("future filter API")
            var incompatible = true
            var filterCalls = 0
            var searchCalls = 0
            val failed = object : BaseCatalogueSource() {
                override fun getFilterList(): FilterList {
                    filterCalls++
                    if (incompatible) throw missing
                    return FilterList()
                }
                override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
                    searchCalls++
                    return MangasPage(emptyList(), false)
                }
            }
            val healthy = object : BaseCatalogueSource() {
                override val id = 10L
                override val name = "Healthy source"
                override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) =
                    MangasPage(listOf(manga("/healthy", "Healthy")), false)
            }
            val model = screenModel(failed, additionalSources = listOf(healthy))
            model.updateSearchQuery("query")
            model.search()
            runCurrent()

            val failure = assertInstanceOf(SearchItemResult.Error::class.java, model.state.value.items[failed])
            assertInstanceOf(AppError.ExtensionIncompatible::class.java, failure.pageError.error)
            assertEquals(SourceRecoveryAction.None, failure.pageError.recoveryAction)
            assertInstanceOf(SearchItemResult.Success::class.java, model.state.value.items[healthy])
            assertEquals(2, model.state.value.progress)
            assertEquals(1, filterCalls)
            assertEquals(0, searchCalls)

            incompatible = false
            model.retry()
            runCurrent()
            assertInstanceOf(SearchItemResult.Success::class.java, model.state.value.items[failed])
            assertEquals(2, filterCalls)
            assertEquals(1, searchCalls)
        }

    @Test
    fun `source only extension filter and partial failures preserve successful results`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            var successfulCalls = 0
            val successful = object : Source {
                override val id = 10L
                override val name = "Successful Source only"
                override val lang = "en"
                override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
                    successfulCalls++
                    assertEquals("extension query", query)
                    return MangasPage(listOf(manga("/success", "Successful")), false)
                }
            }
            val failed = RetrySearchSource()
            val filtered =
                screenModel(successful, additionalSources = listOf(failed), extensionPackage = "selected.extension")
            filtered.updateSearchQuery("extension query")
            filtered.search()
            runCurrent()
            assertEquals(1, successfulCalls)
            assertEquals(0, failed.requestCount)
            assertEquals(setOf(successful), filtered.state.value.items.keys)

            val all = screenModel(successful, additionalSources = listOf(failed))
            all.updateSearchQuery("extension query")
            all.search()
            runCurrent()
            val content = assertInstanceOf(SearchItemResult.Success::class.java, all.state.value.items[successful])
            assertEquals(listOf("/success"), content.result.map { it.url })
            assertInstanceOf(SearchItemResult.Error::class.java, all.state.value.items[failed])
        }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `late old generation cannot replace or close the current loading result`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val source = HangingSearchSource()
            val model = screenModel(source)

            model.updateSearchQuery("old")
            model.search()
            runCurrent()
            source.oldStarted.await()

            model.updateSearchQuery("new")
            model.search()
            runCurrent()
            source.newStarted.await()

            source.oldResult.complete(MangasPage(listOf(manga("/old", "Old")), false))
            runCurrent()
            assertEquals(SearchItemResult.Loading, model.state.value.items[source])

            source.newResult.complete(MangasPage(listOf(manga("/new", "New")), false))
            runCurrent()

            val result = assertInstanceOf(SearchItemResult.Success::class.java, model.state.value.items[source])
            assertEquals(listOf("/new"), result.result.map { it.url })
        }

    @Test
    fun `retry performs the same ScreenModel query again`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val source = RetrySearchSource()
            val model = screenModel(source)

            model.updateSearchQuery("retry-me")
            model.search()
            runCurrent()

            val failed = assertInstanceOf(SearchItemResult.Error::class.java, model.state.value.items[source])
            assertInstanceOf(AppError.Server::class.java, failed.pageError.error)
            assertEquals(SourceRecoveryAction.Retry, failed.pageError.recoveryAction)

            model.retry()
            runCurrent()

            val retried = assertInstanceOf(SearchItemResult.Success::class.java, model.state.value.items[source])
            assertEquals(true, retried.isEmpty)
            assertEquals(2, source.requestCount)
        }

    @Test
    fun `production global search uses shared failure and recovery without direct source call`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val source = DirectSearchRejectingSource()
            val service = mockk<SourceMangaSearchService>()
            val sentinelError = AppError.MalformedData(IllegalStateException("shared-service-sentinel"))
            coEvery { service.loadPageResult(source, any()) } answers {
                SourcePageResult.Failure(secondArg(), sentinelError, SourceRecoveryAction.OpenLogin)
            }
            val model = screenModel(source, service)

            model.updateSearchQuery("shared-only")
            model.search()
            runCurrent()

            val failure = assertInstanceOf(SearchItemResult.Error::class.java, model.state.value.items[source])
            assertSame(sentinelError, failure.pageError.error)
            assertEquals(SourceRecoveryAction.OpenLogin, failure.pageError.recoveryAction)
            coVerify(exactly = 1) {
                service.loadPageResult(
                    source,
                    match {
                        it.sourceId == source.id &&
                            it.page == 1 &&
                            (it.query as? SourceQuery.Search)?.query == "shared-only"
                    },
                )
            }
        }

    private fun kotlinx.coroutines.test.TestScope.screenModel(
        source: Source,
        sourceMangaSearchService: SourceMangaSearchService = SourceMangaSearchService(),
        additionalSources: List<Source> = emptyList(),
        extensionPackage: String? = null,
        installedExtensions: MutableStateFlow<List<Extension.Installed>>? = null,
        sourceFailureLog: (String) -> Unit = {},
        registeredSourceIds: List<Pair<Source, Long>>? = null,
        disabledSourceIds: Set<String> = emptySet(),
        ownedScope: CoroutineScope? = null,
        mangaRepository: MangaRepository? = null,
    ): SearchScreenModel {
        val preferences = preferences(disabledSourceIds)
        val registered = registeredSourceIds ?: (listOf(source) + additionalSources).map { it to it.id }
        val repository = mangaRepository ?: mockk<MangaRepository>().also { repo ->
            coEvery { repo.insertNetworkManga(any()) } answers { firstArg() }
        }
        return object : SearchScreenModel(
            sourcePreferences = preferences,
            sourceManager = object : SourceManager by mockk(relaxed = true) {
                override fun getQuerySources() = listOf(source) + additionalSources
                override fun getRegisteredSourceId(source: Source): Long? =
                    registered.firstOrNull { it.first === source }?.second
            },
            extensionManager = mockk<ExtensionManager> {
                every { installedExtensionsFlow } returns
                    (
                        installedExtensions ?: kotlinx.coroutines.flow.MutableStateFlow(
                            listOf(
                                mockk<eu.kanade.tachiyomi.extension.model.Extension.Installed> {
                                    every { pkgName } returns "selected.extension"
                                    every { name } returns "Selected extension"
                                    every { versionName } returns "1.0.0"
                                    every { versionCode } returns 1L
                                    every { sources } returns listOf(source)
                                },
                            ),
                        )
                        )
            },
            networkToLocalManga = NetworkToLocalManga(repository),
            getManga = mockk<GetManga>(),
            preferences = preferences,
            sourceMangaSearchService = sourceMangaSearchService,
            workerScope = ownedScope ?: this,
            coroutineDispatcher = StandardTestDispatcher(testScheduler),
            sourceFailureLog = sourceFailureLog,
        ) {
            init {
                extensionFilter = extensionPackage
            }
        }
    }

    private fun preferences(disabledSourceIds: Set<String> = emptySet()): SourcePreferences {
        val setPreference = { value: Set<String> ->
            mockk<Preference<Set<String>>> {
                every { get() } returns value
            }
        }
        val booleanPreference = mockk<Preference<Boolean>> {
            every { get() } returns false
            every { changes() } returns flowOf(false)
        }
        return mockk {
            every { enabledLanguages() } returns setPreference(setOf("en"))
            every { disabledSources() } returns setPreference(disabledSourceIds)
            every { pinnedSources() } returns setPreference(emptySet())
            every { globalSearchFilterState() } returns booleanPreference
        }
    }

    private class HangingSearchSource : BaseCatalogueSource() {
        val oldStarted = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val oldResult = CompletableDeferred<MangasPage>()
        val newResult = CompletableDeferred<MangasPage>()

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            val (started, result) = if (query == "old") oldStarted to oldResult else newStarted to newResult
            started.complete(Unit)
            return withContext(NonCancellable) { result.await() }
        }
    }

    private class MetadataSource(
        private val registeredId: Long,
        private val label: String,
        private val brokenField: String? = null,
        private val languageTag: String = "en",
    ) : BaseCatalogueSource() {
        var idBroken = false
        var metadataBroken = false
        var searchError: Throwable? = null
        var nameError: Throwable? = null
        var idReads = 0
        var nameReads = 0
        var languageReads = 0
        var filterCalls = 0
        var searchCalls = 0
        override val id: Long get() {
            idReads++
            if (idBroken) throw NoSuchMethodError("late ID getter")
            return registeredId
        }
        override val name: String get() {
            nameReads++
            nameError?.let { throw it }
            if (metadataBroken && brokenField == "name") throw NoSuchMethodError("late name getter")
            return label
        }
        override val lang: String get() {
            languageReads++
            if (metadataBroken && brokenField == "language") throw NoSuchMethodError("late language getter")
            return languageTag
        }
        override fun getFilterList(): FilterList {
            filterCalls++
            return FilterList()
        }
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            searchCalls++
            searchError?.let { throw it }
            return MangasPage(emptyList(), false)
        }
    }

    private class RetrySearchSource : BaseCatalogueSource() {
        var requestCount = 0

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            requestCount += 1
            if (requestCount == 1) throw HttpException(500)
            return MangasPage(emptyList(), false)
        }
    }

    private class DirectSearchRejectingSource : BaseCatalogueSource() {
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
            error("Production global search bypassed SourceMangaSearchService")
    }

    private abstract class BaseCatalogueSource : Source {
        override val id = 9L
        override val name = "Behavior source"
        override val lang = "en"
        override val supportsLatest = false

        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override fun getFilterList() = FilterList()
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }

    private companion object {
        fun manga(url: String, title: String) = SManga.create().apply {
            this.url = url
            this.title = title
            initialized = true
        }
    }
}
