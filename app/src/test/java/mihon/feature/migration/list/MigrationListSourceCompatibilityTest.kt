package mihon.feature.migration.list

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.test.ScreenModelTestHost
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.domain.error.AppError
import mihon.feature.migration.list.models.MigratingManga
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.sourceFailureIdentity
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.IOException

class MigrationListSourceCompatibilityTest {
    private val host = ScreenModelTestHost()
    private val previous = Injekt

    @BeforeEach
    fun setup() {
        Injekt = InjektScope(DefaultRegistrar())
    }

    @AfterEach
    fun close() {
        host.close()
        Injekt = previous
        Dispatchers.resetMain()
    }

    @Test
    fun `actual migration keeps source ABI failures visible instead of not found or successful candidates`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (site in listOf("filters", "search", "title", "chapters", "details")) {
            val bad = source(11L, site)
            val repository = mockk<MangaRepository>()
            coEvery { repository.insertNetworkManga(any()) } answers
                { firstArg<List<Manga>>().map { it.copy(id = 111L) } }
            val model = model(listOf(11L to bad), repository)
            val item = settled(model)
            io.mockk.verify(atLeast = 1) { bad.getFilterList() }
            val result = item.searchResult.value
            assertEquals("Failure", result::class.simpleName, site)
            val error = result.javaClass.getDeclaredField("error").apply { isAccessible = true }.get(result)
            assertInstanceOf(AppError.ExtensionIncompatible::class.java, error, site)
            if (site in listOf("filters", "search", "title")) {
                coVerify(exactly = 0) { repository.insertNetworkManga(any()) }
            }
            assertEquals(1, model.items.size, site)
        }
    }

    @Test
    fun `actual migration retains healthy candidates after a broken source in both selection modes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (prioritize in listOf(false, true)) {
            val broken = source(11L, "search")
            val healthy = source(22L)
            val owner = Extension.Installed(
                "Bad owner", "trusted.bad", "1.2.3", 123L, 1.6, "en", false,
                null, listOf(broken), null, isShared = false,
            )
            val installed = MutableStateFlow(listOf(owner))
            val records = java.util.Collections.synchronizedList(mutableListOf<String>())
            coEvery { broken.getSearchManga(any(), any(), any()) } coAnswers {
                installed.value = emptyList()
                throw NoSuchMethodError("private broken source")
            }
            val repository = mockk<MangaRepository>()
            coEvery { repository.insertNetworkManga(any()) } answers
                { firstArg<List<Manga>>().map { it.copy(id = 122L) } }
            val result = settled(
                model(
                    listOf(11L to broken, 22L to healthy),
                    repository,
                    prioritize,
                    installed = installed,
                    log = records::add,
                ),
            ).searchResult.value
            val success = assertInstanceOf(MigratingManga.SearchResult.Success::class.java, result)
            assertEquals(22L, success.manga.source)
            coVerify(exactly = 1) { repository.insertNetworkManga(match { batch -> batch.any { it.source == 22L } }) }
            coVerify(exactly = 0) { repository.insertNetworkManga(match { batch -> batch.any { it.source == 11L } }) }
            assertEquals(1, records.size)
            org.junit.jupiter.api.Assertions.assertTrue(records.single().contains("package=trusted.bad"))
            org.junit.jupiter.api.Assertions.assertTrue(records.single().contains("version=1.2.3"))
        }
    }

    @Test
    fun `actual migration uses registered candidate IDs without evaluating extension ID getters`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val candidate = source(22L)
        every { candidate.id } throws IOException("ID is not needed", NoSuchMethodError("future ID"))
        val repository = mockk<MangaRepository>()
        coEvery { repository.insertNetworkManga(any()) } answers { firstArg<List<Manga>>().map { it.copy(id = 122L) } }
        val result = settled(model(listOf(22L to candidate), repository)).searchResult.value
        assertEquals(22L, assertInstanceOf(MigratingManga.SearchResult.Success::class.java, result).manga.source)
    }

    @Test
    fun `selected details ABI failure continues the original remaining candidate strategy`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (prioritize in listOf(false, true)) {
            val bad = source(11L, "details")
            val healthy = source(22L)
            val repository = mockk<MangaRepository>()
            coEvery { repository.insertNetworkManga(any()) } answers
                { firstArg<List<Manga>>().map { it.copy(id = 122L) } }
            val result = settled(model(listOf(11L to bad, 22L to healthy), repository, prioritize)).searchResult.value
            assertEquals(22L, assertInstanceOf(MigratingManga.SearchResult.Success::class.java, result).manga.source)
            coVerify(exactly = 1) { bad.getMangaDetails(any()) }
            coVerify(exactly = 0) { healthy.getMangaDetails(any()) }
        }
    }

    @Test
    fun `details snapshot preserves absent title and comma genre without reading unused remote fields`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val candidate = source(22L, "details")
        val base = SManga.create()
        val remote = object : SManga by base {
            override var url: String
                get() = throw AssertionError("unused remote URL")
                set(value) {
                    base.url = value
                }
            override var initialized: Boolean
                get() = throw AssertionError("unused initialized")
                set(value) {
                    base.initialized = value
                }
            override fun getGenres() = listOf("science,fiction")
        }
        coEvery { candidate.getMangaDetails(any()) } returns remote
        var received: SManga? = null
        val update = mockk<UpdateManga>()
        coEvery { update.awaitUpdateFromSource(any(), any(), true, any(), any(), any()) } answers {
            received = secondArg()
            true
        }
        val repository = mockk<MangaRepository>()
        coEvery { repository.insertNetworkManga(any()) } answers { firstArg<List<Manga>>().map { it.copy(id = 122L) } }
        val result = settled(model(listOf(22L to candidate), repository, update = update)).searchResult.value
        assertInstanceOf(MigratingManga.SearchResult.Success::class.java, result)
        assertEquals("/candidate/22", received?.url)
        assertEquals(listOf("science,fiction"), received?.getGenres())
        assertThrows(UninitializedPropertyAccessException::class.java) { received!!.title }
    }

    @Test
    fun `manual selection keeps chapter details and label incompatibility with its pre call owner`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (site in listOf("chapters", "details", "label")) {
            val candidate = source(22L, site)
            coEvery { candidate.getSearchManga(any(), any(), any()) } returns MangasPage(emptyList(), false)
            every { candidate.id } throws AssertionError("host ID must be used")
            val owner = Extension.Installed(
                "Migration owner", "trusted.migration", "1.2.3", 123L, 1.6, "en",
                false, null, listOf(candidate), null, isShared = false,
            )
            val installed = MutableStateFlow(listOf(owner))
            when (site) {
                "chapters" -> coEvery { candidate.getChapterList(any()) } coAnswers {
                    installed.value = emptyList()
                    throw NoSuchMethodError("late chapters")
                }
                "details" -> coEvery { candidate.getMangaDetails(any()) } coAnswers {
                    installed.value = emptyList()
                    throw NoSuchMethodError("late details")
                }
                else -> coEvery { candidate.getMangaDetails(any()) } coAnswers {
                    installed.value = emptyList()
                    SManga.create().apply { title = "Candidate" }
                }
            }
            if (site == "label") every { candidate.name } throws NoSuchMethodError("late label")
            val repository = mockk<MangaRepository>()
            val model = model(listOf(22L to candidate), repository, installed = installed)
            settled(model)
            var missingChapters = 0
            model.useMangaForMigration(1L, 122L) { missingChapters++ }
            val result = withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    model.items.single().searchResult.first { it is MigratingManga.SearchResult.Failure }
                }
            }
            val failure = assertInstanceOf(MigratingManga.SearchResult.Failure::class.java, result)
            assertEquals("trusted.migration", failure.error.sourceFailureIdentity()?.extensionPackage)
            assertEquals("1.2.3", failure.error.sourceFailureIdentity()?.versionName)
            assertEquals(0, missingChapters)
        }
    }

    @Test
    fun `initial source label incompatibility is accepted and diagnosed once without candidate search`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val current = mockk<Source> {
            every { lang } returns "en"
            every { name } throws NoSuchMethodError("private initial label")
        }
        val candidate = source(22L)
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val model = model(listOf(22L to candidate), mockk(), originalSource = current, log = logs::add)
        val result = settled(model).searchResult.value
        assertInstanceOf(MigratingManga.SearchResult.Failure::class.java, result)
        assertEquals(1, logs.size)
        coVerify(exactly = 0) { candidate.getSearchManga(any(), any(), any()) }
    }

    @Test
    fun `removed migration item rejects a non cooperative late incompatible result and diagnostics`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val candidate = source(22L)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { candidate.getSearchManga(any(), any(), any()) } coAnswers {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            throw NoSuchMethodError("late result")
        }
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val model = model(listOf(22L to candidate), mockk(), log = logs::add)
        withContext(Dispatchers.Default) { withTimeout(5_000) { started.await() } }
        val item = model.items.single()
        model.removeManga(1L)
        withContext(Dispatchers.Default) { withTimeout(5_000) { model.state.first { it.items.isEmpty() } } }
        release.complete(Unit)
        withContext(Dispatchers.Default) { withTimeout(5_000) { item.migrationScope.coroutineContext[Job]!!.join() } }
        assertEquals(MigratingManga.SearchResult.Searching, item.searchResult.value)
        assertEquals(0, logs.size)
    }

    private fun source(id: Long, site: String? = null): CatalogueSource {
        val missing = IOException("private $site", NoSuchMethodError("future $site"))
        val source = mockk<CatalogueSource>()
        every { source.id } returns id
        every { source.name } returns "Candidate"
        every { source.lang } returns "en"
        every { source.getFilterList() } answers { if (site == "filters") throw missing else FilterList() }
        val base = SManga.create().apply {
            url = "/candidate/$id"
            title = "Candidate"
            thumbnail_url =
                "https://cover.invalid"
        }
        val returned = if (site == "title") {
            object : SManga by base {
                override var title: String
                    get() = throw missing
                    set(value) {
                        base.title = value
                    }
            }
        } else {
            base
        }
        if (site == "details") returned.thumbnail_url = null
        coEvery { source.getSearchManga(any(), any(), any()) } coAnswers {
            if (site == "search") throw missing
            MangasPage(listOf(returned), false)
        }
        coEvery { source.getChapterList(any()) } coAnswers {
            if (site == "chapters") throw missing
            emptyList()
        }
        coEvery { source.getMangaDetails(any()) } coAnswers {
            if (site == "details") throw missing
            returned
        }
        return source
    }

    private fun model(
        sources: List<Pair<Long, CatalogueSource>>,
        repository: MangaRepository,
        prioritize: Boolean = false,
        update: UpdateManga = mockk(relaxed = true),
        installed: MutableStateFlow<List<Extension.Installed>> = MutableStateFlow(emptyList()),
        originalSource: Source? = null,
        log: (String) -> Unit = {},
    ): MigrationListScreenModel {
        val preferences = SourcePreferences(
            InMemoryPreferenceStore(
                sequenceOf(
                    InMemoryPreference("migration_sources", sources.map { it.first }, emptyList<Long>()),
                    InMemoryPreference("migration_prioritize_by_chapters", prioritize, false),
                    InMemoryPreference("source_languages", setOf("en"), emptySet<String>()),
                ),
            ),
        )
        assertEquals(sources.map { it.first }, preferences.migrationSources().get())
        assertEquals(prioritize, preferences.migrationPrioritizeByChapters().get())
        Injekt.addSingleton(preferences)
        Injekt.addSingleton<eu.kanade.tachiyomi.data.cache.CoverCache>(mockk(relaxed = true))
        Injekt.addSingleton<tachiyomi.domain.library.service.LibraryPreferences>(mockk(relaxed = true))
        Injekt.addSingleton<eu.kanade.tachiyomi.data.download.DownloadManager>(mockk(relaxed = true))
        val original = Manga.create().copy(id = 1L, source = 1L, url = "/original", title = "Candidate")
        val current = originalSource ?: mockk<Source> {
            every { name } returns "Original"
            every { lang } returns "en"
        }
        val manager = mockk<SourceManager> {
            sources.forEach { (id, source) ->
                every { get(id) } returns source
                every { getOrStub(id) } returns source
                every { getRegisteredSourceId(source) } returns id
            }
            every { getOrStub(1L) } returns current
        }
        return host.create {
            MigrationListScreenModel(
                listOf(1L), null, preferences, manager,
                getManga = mockk {
                    coEvery { await(1L) } returns original
                    coEvery { await(122L) } returns original.copy(id = 122L, source = 22L, url = "/target")
                },
                networkToLocalManga = NetworkToLocalManga(repository),
                updateManga = update, syncChaptersWithSource = mockk(relaxed = true),
                getChaptersByMangaId = mockk {
                    coEvery { await(any()) } answers {
                        listOf(Chapter.create().copy(mangaId = firstArg(), chapterNumber = 1.0))
                    }
                },
                migrateManga = mockk(relaxed = true), batchMigrationRunner = mockk(relaxed = true),
                extensionManager = mockk { every { installedExtensionsFlow } returns installed },
                sourceFailureLog = log,
            )
        }
    }

    private suspend fun settled(model: MigrationListScreenModel): MigratingManga = withContext(Dispatchers.Default) {
        withTimeout(5_000) { model.state.first { it.finishedCount == 1 }.items.single() }
    }
}
