package eu.kanade.tachiyomi.ui.library

import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.objenesis.ObjenesisStd
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.interactor.EvaluateLibrary
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

class LibrarySharedEvaluationWiringTest {
    @Test
    fun `Android query grouping toolbar and selection consumers execute shared library behavior`() {
        val previousInjekt = Injekt
        val source = mockk<Source> {
            every { id } returns 7L
            every { name } returns "Remote source"
            every { lang } returns "en"
        }
        val sourceManager = mockk<SourceManager> {
            every { getOrStub(any()) } returns source
        }
        val sourcePreferences = mockk<SourcePreferences> {
            every { enabledLanguages() } returns preference(setOf("en"))
        }
        Injekt = InjektScope(DefaultRegistrar()).apply { addSingleton<SourcePreferences>(sourcePreferences) }
        try {
            val first = item(1L, "Alpha", total = 2L, read = 1L, sourceManager)
            val second = item(2L, "Beta", total = 2L, read = 2L, sourceManager)
            val category = Category(1L, "Reading", 0L, LibrarySort.default.flag)

            assertTrue(first.matches("alpha"))
            assertTrue(first.matches("remote source"))
            assertFalse(first.matches("missing"))

            val model = allocateModel()

            @Suppress("UNCHECKED_CAST")
            val grouped = LibraryScreenModel::class.java.declaredMethods.single { it.name == "applyGrouping" }
                .apply { isAccessible = true }
                .invoke(model, listOf(first, second), listOf(category), false) as Map<Category, List<Long>>
            assertEquals(listOf(1L, 2L), grouped[category])

            val state = LibraryScreenModel.State::class.java.declaredConstructors
                .single { it.parameterCount == 12 }
                .apply { isAccessible = true }
                .newInstance(
                    true,
                    false,
                    null,
                    emptySet<Long>(),
                    false,
                    false,
                    true,
                    false,
                    null,
                    LibraryScreenModel.LibraryData(
                        categories = listOf(category),
                        favorites = listOf(first, second),
                    ),
                    0,
                    grouped,
                ) as LibraryScreenModel.State
            val title = state.getToolbarTitle("Library", "Default", 0)
            assertEquals("Reading", title.text)
            assertEquals(2, title.numberOfManga)

            val stateFlow = MutableStateFlow(state)
            val stateScreenModel = generateSequence<Class<*>>(LibraryScreenModel::class.java) { it.superclass }
                .first { type -> type.declaredFields.any { it.name == "mutableState" } }
            listOf("mutableState", "state").forEach { name ->
                stateScreenModel.getDeclaredField(name)
                    .apply { isAccessible = true }
                    .set(model, stateFlow)
            }
            model.toggleSelection(category, first.libraryManga)
            assertEquals(setOf(1L), model.state.value.selection)
            model.toggleRangeSelection(category, second.libraryManga)
            assertEquals(setOf(1L, 2L), model.state.value.selection)
            model.invertSelection()
            assertEquals(emptySet<Long>(), model.state.value.selection)
            model.selectAll()
            assertEquals(setOf(1L, 2L), model.state.value.selection)
        } finally {
            Injekt = previousInjekt
        }
    }

    @Test
    fun `Android favorites projection gates real badge fields independently`() = runBlocking {
        val previousInjekt = Injekt
        val local = LibraryManga(
            Manga.create().copy(id = 10L, title = "Local", source = 0L),
            emptyList(),
            4L,
            1L,
            0L,
            0L,
            0L,
            0L,
        )
        val remote = LibraryManga(
            Manga.create().copy(id = 11L, title = "Remote", source = 42L),
            emptyList(),
            5L,
            2L,
            0L,
            0L,
            0L,
            0L,
        )
        val getLibraryManga = mockk<GetLibraryManga> {
            every { subscribe() } returns flowOf(listOf(local, remote))
        }
        val downloadManager = mockk<DownloadManager> { every { getDownloadCount(any<Manga>()) } returns 7 }
        val downloadChanges = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
        val downloadCache = mockk<DownloadCache> { every { changes } returns downloadChanges }
        val remoteSource = mockk<Source> { every { lang } returns "fr" }
        val sourceManager = mockk<SourceManager> { every { getOrStub(any()) } returns remoteSource }
        Injekt = InjektScope(DefaultRegistrar()).apply { addSingleton<SourceManager>(sourceManager) }
        try {
            val withLocal = allocateModel(
                "getLibraryManga" to getLibraryManga,
                "downloadManager" to downloadManager,
                "downloadCache" to downloadCache,
                "sourceManager" to sourceManager,
                "preferences" to basePreferences(),
                "libraryPreferences" to badgePreferences(
                    download = true,
                    unread = true,
                    local = true,
                    language = false,
                ),
            )
            val localProjection = favorites(withLocal)
            assertEquals(7L, localProjection.first().downloadCount)
            assertEquals(3L, localProjection.first().unreadCount)
            assertTrue(localProjection.first().isLocal)
            assertEquals("", localProjection.last().sourceLanguage)

            val withLanguage = allocateModel(
                "getLibraryManga" to getLibraryManga,
                "downloadManager" to downloadManager,
                "downloadCache" to downloadCache,
                "sourceManager" to sourceManager,
                "preferences" to basePreferences(),
                "libraryPreferences" to badgePreferences(
                    download = false,
                    unread = false,
                    local = false,
                    language = true,
                ),
            )
            val languageProjection = favorites(withLanguage)
            assertEquals(0L, languageProjection.first().downloadCount)
            assertEquals(0L, languageProjection.first().unreadCount)
            assertFalse(languageProjection.first().isLocal)
            assertEquals("fr", languageProjection.last().sourceLanguage)
        } finally {
            Injekt = previousInjekt
        }
    }

    @Test
    fun `Android library production model owns the shared evaluator`() {
        val evaluatorFields = LibraryScreenModel::class.java.declaredFields.filter {
            it.type == EvaluateLibrary::class.java
        }

        assertEquals(
            1,
            evaluatorFields.size,
            "Android LibraryScreenModel must keep one production dependency on the common evaluator",
        )
    }

    @Test
    fun `Android production filter and sort consumers execute shared evaluation behavior`() {
        val downloadManager = mockk<DownloadManager>()
        every { downloadManager.getDownloadCount(any<Manga>()) } returns 0
        val seed = mockk<Preference<Int>>()
        every { seed.get() } returns 0
        val libraryPreferences = mockk<LibraryPreferences>()
        every { libraryPreferences.randomSortSeed() } returns seed
        val model = allocateModel(
            "evaluateLibrary" to EvaluateLibrary(),
            "downloadManager" to downloadManager,
            "libraryPreferences" to libraryPreferences,
        )
        val sourceManager = mockk<SourceManager>(relaxed = true)
        val unread = item(1L, "Alpha", total = 2L, read = 1L, sourceManager)
        val read = item(2L, "Zulu", total = 2L, read = 2L, sourceManager)
        val preferencesType = LibraryScreenModel::class.java.declaredClasses.single {
            it.simpleName == "ItemPreferences"
        }
        val preferences = preferencesType.declaredConstructors.single().run {
            isAccessible = true
            newInstance(
                false, false, false, false, false, false,
                TriState.DISABLED, TriState.ENABLED_IS, TriState.DISABLED,
                TriState.DISABLED, TriState.DISABLED, TriState.DISABLED,
            )
        }
        val filter = LibraryScreenModel::class.java.declaredMethods.single { it.name == "applyFilters" }
            .apply { isAccessible = true }
            .invoke(
                model,
                listOf(unread, read),
                emptyMap<Long, List<Any>>(),
                emptyMap<Long, TriState>(),
                preferences,
            )
            as List<*>
        assertEquals(listOf(1L), filter.map { (it as LibraryItem).id })

        val category =
            Category(
                1L,
                "Default",
                0L,
                LibrarySort(LibrarySort.Type.Alphabetical, LibrarySort.Direction.Descending).flag,
            )
        val sort = LibraryScreenModel::class.java.declaredMethods.single { it.name == "applySort" }
            .apply { isAccessible = true }
            .invoke(
                model,
                mapOf(category to listOf(1L, 2L)),
                listOf(unread, read).associateBy {
                    it.id
                },
                emptyMap<Long, List<Any>>(),
                emptySet<Long>(),
            )
            as Map<*, *>
        assertEquals(listOf(2L, 1L), sort[category])
    }

    private fun allocateModel(vararg fields: Pair<String, Any>): LibraryScreenModel {
        return ObjenesisStd().newInstance(LibraryScreenModel::class.java).also { model ->
            fields.forEach { (name, value) ->
                LibraryScreenModel::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }
                    .set(model, value)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun favorites(model: LibraryScreenModel): List<LibraryItem> =
        (
            LibraryScreenModel::class.java.getDeclaredMethod("getFavoritesFlow").apply { isAccessible = true }
                .invoke(model) as kotlinx.coroutines.flow.Flow<List<LibraryItem>>
            ).first()

    private fun basePreferences() = mockk<BasePreferences> {
        every { downloadedOnly() } returns preference(false)
    }

    private fun badgePreferences(download: Boolean, unread: Boolean, local: Boolean, language: Boolean) =
        mockk<LibraryPreferences> {
            every { downloadBadge() } returns preference(download)
            every { unreadBadge() } returns preference(unread)
            every { localBadge() } returns preference(local)
            every { languageBadge() } returns preference(language)
            every { autoUpdateMangaRestrictions() } returns preference(emptySet())
            every { filterDownloaded() } returns preference(TriState.DISABLED)
            every { filterUnread() } returns preference(TriState.DISABLED)
            every { filterStarted() } returns preference(TriState.DISABLED)
            every { filterBookmarked() } returns preference(TriState.DISABLED)
            every { filterCompleted() } returns preference(TriState.DISABLED)
            every { filterIntervalCustom() } returns preference(TriState.DISABLED)
        }

    private fun <T> preference(value: T) = mockk<Preference<T>> {
        every { get() } returns value
        every { changes() } returns flowOf(value)
    }

    private fun item(
        id: Long,
        title: String,
        total: Long,
        read: Long,
        sourceManager: SourceManager,
    ) = LibraryItem(
        LibraryManga(
            Manga.create().copy(id = id, title = title, source = 7L),
            listOf(1L),
            total,
            read,
            0L,
            0L,
            0L,
            0L,
        ),
        sourceManager = sourceManager,
    )
}
