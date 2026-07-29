package mihon.desktop.ui.updates

import tachiyomi.i18n.MR

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import mihon.desktop.ui.library.MangaDetailScreen
import mihon.domain.upcoming.interactor.GetUpcomingManga
import tachiyomi.domain.manga.model.Manga
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

class UpcomingScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val getUpcomingManga = LocalDesktopUiDependencies.current.getUpcomingManga
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()

        var selectedYearMonth by remember { mutableStateOf(YearMonth.now()) }
        var upcomingManga by remember { mutableStateOf<List<Manga>>(emptyList()) }

        LaunchedEffect(Unit) {
            getUpcomingManga.subscribe().collect { upcomingManga = it }
        }

        val groupedByDate = remember(upcomingManga) {
            upcomingManga
                .filter { it.expectedNextUpdate != null }
                .groupBy { it.expectedNextUpdate!!.atZone(ZoneId.systemDefault()).toLocalDate() }
                .toSortedMap()
        }

        val events: Map<LocalDate, Int> = remember(groupedByDate) {
            groupedByDate.mapValues { it.value.size }
        }

        val listItems = remember(groupedByDate) {
            buildList<UpcomingListItem> {
                groupedByDate.forEach { (date, mangaList) ->
                    add(UpcomingListItem.Header(date, mangaList.size))
                    mangaList.forEach { add(UpcomingListItem.MangaItem(it)) }
                }
            }
        }

        val headerIndexes = remember(listItems) {
            listItems.mapIndexedNotNull { index, item ->
                if (item is UpcomingListItem.Header) item.date to index else null
            }.toMap()
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(MR.strings.label_upcoming.localized()) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = MR.strings.action_bar_up_description.localized())
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                item(key = "calendar") {
                    UpcomingCalendar(
                        selectedYearMonth = selectedYearMonth,
                        events = events,
                        onPrevMonth = { selectedYearMonth = selectedYearMonth.minusMonths(1) },
                        onNextMonth = { selectedYearMonth = selectedYearMonth.plusMonths(1) },
                        onClickDay = { date ->
                            headerIndexes[date]?.let { idx ->
                                scope.launch { listState.animateScrollToItem(idx + 1) }
                            }
                        },
                    )
                    HorizontalDivider()
                }

                if (listItems.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = MR.strings.desktop_ui_no_upcoming_releases_from_your_library.localized(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    items(
                        items = listItems,
                        key = { item ->
                            when (item) {
                                is UpcomingListItem.Header -> "header-${item.date}"
                                is UpcomingListItem.MangaItem -> "manga-${item.manga.id}"
                            }
                        },
                        contentType = { item ->
                            when (item) {
                                is UpcomingListItem.Header -> "header"
                                is UpcomingListItem.MangaItem -> "manga"
                            }
                        },
                    ) { item ->
                        when (item) {
                            is UpcomingListItem.Header -> UpcomingDateHeader(item.date, item.count)
                            is UpcomingListItem.MangaItem -> UpcomingMangaItem(
                                manga = item.manga,
                                onClick = { navigator.push(MangaDetailScreen(item.manga.id)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private sealed interface UpcomingListItem {
    data class Header(val date: LocalDate, val count: Int) : UpcomingListItem
    data class MangaItem(val manga: Manga) : UpcomingListItem
}

@Composable
private fun UpcomingCalendar(
    selectedYearMonth: YearMonth,
    events: Map<LocalDate, Int>,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onClickDay: (LocalDate) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevMonth) {
                Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = MR.strings.desktop_ui_previous_month.localized())
            }
            Text(
                text = selectedYearMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) +
                    " ${selectedYearMonth.year}",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = onNextMonth) {
                Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = MR.strings.desktop_ui_next_month.localized())
            }
        }

        val localeFirstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek.value
        val weekDays = (0 until 7).map { DayOfWeek.of((localeFirstDayOfWeek - 1 + it) % 7 + 1) }

        Row(modifier = Modifier.fillMaxWidth()) {
            weekDays.forEach { day ->
                Text(
                    text = day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val firstDayOfMonth = selectedYearMonth.atDay(1).dayOfWeek
        val emptyBefore = weekDays.indexOf(firstDayOfMonth)
        val daysInMonth = selectedYearMonth.lengthOfMonth()
        val totalCells = emptyBefore + daysInMonth
        val rows = (totalCells + 6) / 7

        for (row in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val cellIndex = row * 7 + col
                    val dayNumber = cellIndex - emptyBefore + 1
                    if (dayNumber < 1 || dayNumber > daysInMonth) {
                        Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val date = selectedYearMonth.atDay(dayNumber)
                        CalendarDayCell(
                            day = dayNumber,
                            hasEvent = (events[date] ?: 0) > 0,
                            isToday = date == LocalDate.now(),
                            modifier = Modifier.weight(1f),
                            onClick = { onClickDay(date) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    day: Int,
    hasEvent: Boolean,
    isToday: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(CircleShape)
            .then(if (isToday) Modifier.background(primaryContainer) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = day.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = if (isToday) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (hasEvent) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@Composable
private fun UpcomingDateHeader(date: LocalDate, count: Int) {
    val today = LocalDate.now()
    val label = when (date) {
        today -> MR.strings.relative_time_today.localized()
        today.plusDays(1) -> MR.strings.desktop_ui_tomorrow.localized()
        else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Badge(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Text("$count")
        }
    }
    HorizontalDivider()
}

@Composable
private fun UpcomingMangaItem(manga: Manga, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = manga.thumbnailUrl,
            contentDescription = manga.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp, 68.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = manga.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            manga.author?.let { author ->
                Text(
                    text = author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
