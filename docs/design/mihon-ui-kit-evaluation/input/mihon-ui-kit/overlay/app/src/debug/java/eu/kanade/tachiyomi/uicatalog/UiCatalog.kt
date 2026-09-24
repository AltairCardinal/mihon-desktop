@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package eu.kanade.tachiyomi.uicatalog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.R
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.util.shouldExpandFAB

typealias Dispatch = (DemoAction) -> Unit
private val DemoStateSaver = listSaver<DemoState, String>(save = { it.snapshot() }, restore = { restoreSnapshot(it) })

@Composable
fun UiCatalog() {
    var category by rememberSaveable { mutableIntStateOf(0) }
    val holder = rememberSaveableStateHolder()
    if (category == 0) {
        holder.SaveableStateProvider("index") { CatalogIndex { category = it } }
    } else {
        holder.SaveableStateProvider("category:$category") {
            CatalogPage(category, onExit = { category = 0 }, onNavigate = { category = it })
        }
    }
}

@Composable
private fun CatalogIndex(onOpen: (Int) -> Unit) {
    Scaffold(topBar = { behavior ->
        AppBar(title = stringResource(R.string.lab_title), scrollBehavior = behavior)
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("catalog-list"),
            state = rememberLazyListState(), contentPadding = padding,
        ) {
            item { Text(stringResource(R.string.lab_notice), Modifier.padding(MaterialTheme.padding.medium)) }
            items(catalogCategories, key = { it.id }) { category ->
                TextButton(
                    onClick = { onOpen(category.id) },
                    modifier = Modifier.fillMaxWidth().testTag("open:${category.id}"),
                ) {
                    Text(stringResource(category.titleRes), Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun CatalogPage(category: Int, onExit: () -> Unit, onNavigate: (Int) -> Unit) {
    val spec = catalogCategories.first { it.id == category }
    var state by rememberSaveable(category, stateSaver = DemoStateSaver) { mutableStateOf(fixture(category)) }
    var showControls by rememberSaveable { mutableStateOf(true) }
    var epoch by rememberSaveable { mutableIntStateOf(0) }
    var showCases by remember { mutableStateOf(false) }
    val dispatch: Dispatch = { action ->
        state = reduce(state, action)
        if (action is DemoAction.Scenario) epoch++
    }
    val onBack = {
        val result = back(state)
        state = result.state
        if (result.exitCategory) onExit()
    }
    BackHandler(onBack = onBack)
    val scroll = rememberLazyListState()
    val host = remember { SnackbarHostState() }
    val title = if (category == 3 && state.scenario == 1) stringResource(R.string.lab_title_long) else stringResource(spec.titleRes)
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("page:$category"),
        topBar = { behavior ->
            if (category != 28 || state.barsVisible) {
                val actions: @Composable RowScope.() -> Unit = {
                    AppBarActions(persistentListOf(AppBar.Action(
                        title = stringResource(R.string.lab_help), icon = Icons.Outlined.Info,
                        onClick = { showControls = !showControls },
                    )))
                }
                if (state.query != null && state.selected.isEmpty()) {
                    SearchToolbar(
                        modifier = Modifier.testTag("appbar"),
                        titleContent = { AppBarTitle(title) },
                        searchQuery = state.query,
                        onChangeSearchQuery = { dispatch(DemoAction.Query(it)) },
                        navigateUp = onBack,
                        onSearch = { dispatch(DemoAction.SubmitSearch) },
                        actions = actions, scrollBehavior = behavior,
                    )
                } else {
                    AppBar(
                        modifier = Modifier.testTag("appbar"), title = title,
                        subtitle = if (category == 3 && state.scenario == 1) stringResource(R.string.lab_subtitle) else null,
                        navigateUp = onBack, actions = actions,
                        actionModeCounter = state.selected.size,
                        onCancelActionMode = { dispatch(DemoAction.ClearSelection) },
                        actionModeActions = {
                            LabButton(R.string.lab_select_all, "select-all") { dispatch(DemoAction.SelectAll) }
                            LabButton(R.string.lab_invert, "invert") { dispatch(DemoAction.InvertSelection) }
                        },
                        scrollBehavior = behavior,
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(host) },
        floatingActionButton = {
            val visible = when (category) {
                7, 27 -> state.canContinue && state.phase != Phase.RUNNING
                5 -> state.secondary
                24 -> true
                29 -> state.order.isNotEmpty()
                else -> false
            }
            if (visible) {
                SmallExtendedFloatingActionButton(
                    onClick = { dispatch(if (category == 29) {
                        if (state.phase == Phase.RUNNING) DemoAction.Pause else DemoAction.Start
                    } else DemoAction.ContinueReading) },
                    text = { Text(stringResource(if (category == 29) {
                        if (state.phase == Phase.RUNNING) R.string.lab_pause else R.string.lab_start
                    } else R.string.lab_continue)) },
                    icon = { Icon(if (category == 29 && state.phase == Phase.RUNNING) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, contentDescription = null) },
                    expanded = scroll.shouldExpandFAB(),
                    modifier = Modifier.testTag("primary-action"),
                )
            }
        },
        bottomBar = {
            if (showControls) {
                FlowRow(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp)) {
                    LabButton(R.string.lab_previous, "previous-category", category > 1) { onNavigate(category - 1) }
                    LabButton(R.string.lab_exit, "exit-category", onClick = onExit)
                    LabButton(R.string.lab_next, "next-category", category < 32) { onNavigate(category + 1) }
                }
            }
        },
    ) { contentPadding ->
        Column(Modifier.fillMaxSize().consumeWindowInsets(contentPadding).imePadding()) {
            // The outer Scaffold owns all insets. The two children split consumption: panel=top/sides; body=bottom.
            if (showControls) {
                Column(Modifier.padding(
                    top = contentPadding.calculateTopPadding(),
                    start = contentPadding.calculateStartPadding(androidx.compose.ui.platform.LocalLayoutDirection.current),
                    end = contentPadding.calculateEndPadding(androidx.compose.ui.platform.LocalLayoutDirection.current),
                ).padding(horizontal = 12.dp)) {
                    FlowRow(verticalArrangement = Arrangement.Center) {
                        Box {
                            LabButton(R.string.lab_cases, "choose-case") { showCases = true }
                            DropdownMenu(expanded = showCases, onDismissRequest = { showCases = false }) {
                                spec.cases.forEachIndexed { index, res ->
                                    DropdownMenuItem(
                                        modifier = Modifier.testTag("case:$index"),
                                        text = { Text(stringResource(res)) },
                                        onClick = { dispatch(DemoAction.Scenario(index)); showCases = false },
                                    )
                                }
                            }
                        }
                        LabButton(R.string.lab_reset, "reset-case") { dispatch(DemoAction.Scenario(state.scenario)) }
                        LabButton(R.string.lab_back, "trigger-back", onClick = onBack)
                    }
                    Text(stringResource(spec.expectations[state.scenario]), style = MaterialTheme.typography.bodySmall,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.lab_event, state.lastEvent), style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.testTag("fixture-event"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val bodyPadding = if (showControls) PaddingValues(bottom = contentPadding.calculateBottomPadding()) else contentPadding
            key(category, epoch) {
                if (category == 15) {
                    Box(Modifier.weight(1f)) { PreferenceExamples(state, dispatch, bodyPadding) }
                } else {
                    LazyColumn(
                        state = scroll,
                        modifier = Modifier.weight(1f).fillMaxWidth().testTag("page-body"),
                        contentPadding = bodyPadding,
                    ) {
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(MaterialTheme.padding.medium),
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                            ) {
                                if (category >= 27) Text(stringResource(R.string.lab_models_only), style = MaterialTheme.typography.labelMedium)
                                when (category) {
                                    in 1..6 -> NavigationExamples(state, dispatch, onBack)
                                    in 7..12 -> CollectionExamples(state, dispatch)
                                    in 13..17 -> DialogExamples(state, dispatch)
                                    in 18..21 -> AsyncExamples(state, dispatch, host)
                                    in 22..26 -> VisualExamples(state, dispatch)
                                    else -> BusinessExamples(state, dispatch)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    CatalogOverlay(state, dispatch, onBack)
}

@Composable
internal fun LabButton(label: Int, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) {
        Text(stringResource(label))
    }
}

@Composable
internal fun StateText(s: DemoState) {
    Text(stringResource(R.string.lab_state, s.phase.name), Modifier.testTag("phase"))
    Text(stringResource(R.string.lab_counts, s.starts, s.commits), Modifier.testTag("counts"))
}
