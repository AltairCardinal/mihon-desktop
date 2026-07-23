package eu.kanade.tachiyomi.ui.library

import eu.kanade.tachiyomi.data.download.DownloadManager
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.objenesis.ObjenesisStd
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.interactor.EvaluateLibrary
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

class LibrarySharedEvaluationWiringTest {
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
            .invoke(model, listOf(unread, read), emptyMap<Long, List<Any>>(), emptyMap<Long, TriState>(), preferences)
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
                LibraryScreenModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(model, value)
            }
        }
    }

    private fun item(id: Long, title: String, total: Long, read: Long, sourceManager: SourceManager) = LibraryItem(
        LibraryManga(Manga.create().copy(id = id, title = title, source = 7L), listOf(1L), total, read, 0L, 0L, 0L, 0L),
        sourceManager = sourceManager,
    )
}
