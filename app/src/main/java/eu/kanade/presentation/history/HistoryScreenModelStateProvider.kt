package eu.kanade.presentation.history

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import eu.kanade.tachiyomi.ui.history.HistoryScreenModel
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.service.HistoryUiModel
import tachiyomi.domain.manga.model.MangaCover
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Date
import kotlin.random.Random

class HistoryScreenModelStateProvider : PreviewParameterProvider<tachiyomi.domain.history.service.HistoryState> {

    private val multiPage = tachiyomi.domain.history.service.HistoryState(
        searchQuery = null,
        list =
        listOf(HistoryUiModelExamples.headerToday)
            .asSequence()
            .plus(HistoryUiModelExamples.items().take(3))
            .plus(HistoryUiModelExamples.header { it.minus(1, ChronoUnit.DAYS) })
            .plus(HistoryUiModelExamples.items().take(1))
            .plus(HistoryUiModelExamples.header { it.minus(2, ChronoUnit.DAYS) })
            .plus(HistoryUiModelExamples.items().take(7))
            .toList(),
        dialog = null,
    )

    private val shortRecent = tachiyomi.domain.history.service.HistoryState(
        searchQuery = null,
        list = listOf(
            HistoryUiModelExamples.headerToday,
            HistoryUiModelExamples.items().first(),
        ),
        dialog = null,
    )

    private val shortFuture = tachiyomi.domain.history.service.HistoryState(
        searchQuery = null,
        list = listOf(
            HistoryUiModelExamples.headerTomorrow,
            HistoryUiModelExamples.items().first(),
        ),
        dialog = null,
    )

    private val empty = tachiyomi.domain.history.service.HistoryState(
        searchQuery = null,
        list = listOf(),
        dialog = null,
    )

    private val loadingWithSearchQuery = tachiyomi.domain.history.service.HistoryState(
        searchQuery = "Example Search Query",
    )

    private val loading = tachiyomi.domain.history.service.HistoryState(
        searchQuery = null,
        list = null,
        dialog = null,
    )

    override val values: Sequence<tachiyomi.domain.history.service.HistoryState> = sequenceOf(
        multiPage,
        shortRecent,
        shortFuture,
        empty,
        loadingWithSearchQuery,
        loading,
    )

    private object HistoryUiModelExamples {
        val headerToday = header()
        val headerTomorrow =
            HistoryUiModel.Header(LocalDate.now().plusDays(1))

        fun header(instantBuilder: (Instant) -> Instant = { it }) =
            HistoryUiModel.Header(LocalDate.from(instantBuilder(Instant.now())))

        fun items() = sequence {
            var count = 1
            while (true) {
                yield(randItem { it.copy(title = "Example Title $count") })
                count += 1
            }
        }

        fun randItem(historyBuilder: (HistoryWithRelations) -> HistoryWithRelations = { it }) =
            HistoryUiModel.Item(
                historyBuilder(
                    HistoryWithRelations(
                        id = Random.nextLong(),
                        chapterId = Random.nextLong(),
                        mangaId = Random.nextLong(),
                        title = "Test Title",
                        chapterNumber = Random.nextDouble(),
                        readAt = Date.from(Instant.now()),
                        readDuration = Random.nextLong(),
                        coverData = MangaCover(
                            mangaId = Random.nextLong(),
                            sourceId = Random.nextLong(),
                            isMangaFavorite = Random.nextBoolean(),
                            url = "https://example.com/cover.png",
                            lastModified = Random.nextLong(),
                        ),
                    ),
                ),
            )
    }
}
