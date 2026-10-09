package mihon.desktop.ui.history

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.fakes.FakeHistoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.service.HistoryController
import tachiyomi.domain.history.service.HistoryUiModel
import tachiyomi.domain.manga.model.MangaCover
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

class HistoryGroupingTest {
    @Test
    fun `desktop consumes shared headers in repository order including undated rows`() = runTest {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.of(2026, 10, 4)
        fun item(id: Long, day: LocalDate?) = HistoryWithRelations(
            id,
            id,
            id,
            "Manga $id",
            1.0,
            day?.let { Date.from(it.atStartOfDay(zone).toInstant()) },
            0,
            MangaCover(id, 1, false, null, 0),
        )
        val rows = listOf(item(3, today), item(1, today), item(2, today.minusDays(1)), item(4, null))
        val repository = FakeHistoryRepository().apply { rows.forEach(::addHistory) }
        val controller = HistoryController(backgroundScope, GetHistory(repository), RemoveHistory(repository), zone = zone)
        val state = controller.state.first { it.list != null }
        assertEquals(listOf(today, today.minusDays(1)), state.list.orEmpty().filterIsInstance<HistoryUiModel.Header>().map { it.date })
        assertEquals(rows, state.items)
        controller.close()
    }

    @Test
    fun `desktop empty repository uses the shared empty list`() = runTest {
        val repository = FakeHistoryRepository()
        val controller = HistoryController(backgroundScope, GetHistory(repository), RemoveHistory(repository))
        assertEquals(emptyList<HistoryUiModel>(), controller.state.first { it.list != null }.list)
        controller.close()
    }
}
