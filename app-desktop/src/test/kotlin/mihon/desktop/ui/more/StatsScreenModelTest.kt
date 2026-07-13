package mihon.desktop.ui.more

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

@OptIn(ExperimentalCoroutinesApi::class)
class StatsScreenModelTest {
    @Test
    fun `state moves from loading to shared aggregation and exposes errors`() {
        val scope = TestScope(StandardTestDispatcher())
        val snapshots = MutableStateFlow<List<LibraryManga>>(emptyList())
        val model = StatsScreenModel(snapshots, scope)
        assertInstanceOf(StatsUiState.Loading::class.java, model.state.value)

        scope.advanceUntilIdle()
        snapshots.value = listOf(item())
        scope.advanceUntilIdle()
        val content = assertInstanceOf(StatsUiState.Content::class.java, model.state.value)
        assertEquals(mapOf(9L to 1), content.stats.byCategory)

        model.reportFailure(IllegalStateException("db"))
        assertInstanceOf(StatsUiState.Error::class.java, model.state.value)
    }

    private fun item() = LibraryManga(
        Manga.create().copy(id = 1, source = 2, status = 3), listOf(9), 4, 1, 0, 0, 0, 0,
    )
}
