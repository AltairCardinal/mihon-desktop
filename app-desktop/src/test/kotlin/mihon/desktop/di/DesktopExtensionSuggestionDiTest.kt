package mihon.desktop.di

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.ui.extension.ExtensionsScreenModel
import mihon.domain.extension.suggestion.ObserveExtensionSuggestions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class DesktopExtensionSuggestionDiTest {
    @Test
    fun `production graph carries real library changes into extension suggestion state`(@TempDir directory: File) = runBlocking {
        val context = initDesktopDIForTest(directory, isolatedDesktopPreferenceStore())
        try {
            assertNotNull(Injekt.get<ObserveExtensionSuggestions>())
            val model = Injekt.get<ExtensionsScreenModel>()
            Injekt.get<MangaRepository>().insertNetworkManga(listOf(Manga.create().copy(
                source = 9007199254740993L, favorite = true, title = "Book", url = "/book",
            )))
            val result = withTimeout(10_000) {
                model.state.first { it.suggestions.unmatched.any { source -> source.sourceId == 9007199254740993L } }
            }
            assertEquals(1L, result.suggestions.unmatched.single().count)
        } finally {
            context.closeAndJoin()
        }
    }
}
