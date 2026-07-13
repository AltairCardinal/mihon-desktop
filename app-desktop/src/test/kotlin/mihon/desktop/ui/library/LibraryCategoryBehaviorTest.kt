package mihon.desktop.ui.library

import java.io.File
import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.runBlocking
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.library.LibraryScreenModelFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore

@Isolated
class LibraryCategoryBehaviorTest {
    @Test
    fun `category dialog intents perform create rename reorder and delete through production DI`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val preferencesNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val context = initDesktopDIForTest(
            tempDir,
            DesktopPreferenceStore(preferencesNode),
            startDownloadWorker = false,
        )
        try {
            val model = LibraryScreenModelFactory.create()
            model.setShowCategoryDialog(true)

            model.createCategory("  First  ")
            model.createCategory("Second")
            assertEquals(listOf("First", "Second"), model.userCategories().map { it.name })

            val first = model.userCategories().first()
            val second = model.userCategories().last()
            model.renameCategory(first.id, "  Renamed  ")
            model.reorderCategory(second.id, 0)
            assertEquals(listOf("Second", "Renamed"), model.userCategories().map { it.name })
            assertEquals(listOf(0L, 1L), model.userCategories().map { it.order })

            model.deleteCategory(second.id)
            assertEquals(listOf("Renamed"), model.userCategories().map { it.name })
            assertEquals(
                model.state.value.categories.indices.map(Int::toLong),
                model.state.value.categories.map { it.order },
            )

            model.setShowCategoryDialog(false)
            assertFalse(model.state.value.showCategoryDialog)
            assertTrue(model.state.value.categories.none { it.id == second.id })
        } finally {
            context.closeAndJoin()
            preferencesNode.removeNode()
        }
    }

    private fun LibraryScreenModel.userCategories() = state.value.categories.filter { it.name.isNotBlank() }
}
