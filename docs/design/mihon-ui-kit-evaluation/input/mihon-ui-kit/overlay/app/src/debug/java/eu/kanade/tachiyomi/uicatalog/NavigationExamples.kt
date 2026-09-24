@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package eu.kanade.tachiyomi.uicatalog

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.R

@Composable
internal fun NavigationExamples(s: DemoState, send: Dispatch, onBack: () -> Unit) {
    when (s.category) {
        1 -> if (s.phase != Phase.READY) {
            ContentState(s, send)
        } else {
            Text(stringResource(R.string.lab_body))
            if (s.scenario == 1) FixtureTabs(s.tab % 3) { send(DemoAction.Tab(it)) }
            Text(stringResource(R.string.lab_depth, s.depth))
            LabButton(R.string.lab_open_child, "open-child") { send(DemoAction.Event("child-content")) }
        }
        2 -> {
            val tablet = isTabletUi()
            if (tablet) {
                Row(Modifier.heightIn(min = 320.dp)) {
                    NavigationRail {
                        repeat(5) { i ->
                            NavigationRailItem(
                                selected = s.tab == i, onClick = { send(DemoAction.Tab(i)) },
                                icon = { BadgedBox(badge = { if (s.secondary && i == 1) Badge { Text("3") } }) { Text((i + 1).toString()) } },
                                label = { Text(stringResource(R.string.lab_tab, i + 1)) },
                                modifier = Modifier.testTag("tab:$i"),
                            )
                        }
                    }
                    Text(stringResource(R.string.lab_root, s.tab + 1), Modifier.padding(16.dp))
                }
            } else {
                Text(stringResource(R.string.lab_root, s.tab + 1))
                NavigationBar {
                    repeat(5) { i ->
                        NavigationBarItem(
                            selected = s.tab == i, onClick = { send(DemoAction.Tab(i)) },
                            icon = {
                                BadgedBox(badge = { if (s.secondary && i == 1) Badge { Text("3") } }) { Text((i + 1).toString()) }
                            },
                            label = { Text(stringResource(R.string.lab_tab, i + 1)) },
                            modifier = Modifier.testTag("tab:$i"),
                        )
                    }
                }
            }
        }
        3 -> {
            Text(stringResource(R.string.lab_body))
            FlowRow {
                LabButton(R.string.lab_search, "open-search") { send(DemoAction.Query("")) }
                LabButton(R.string.lab_select_all, "body-select-all") { send(DemoAction.SelectAll) }
                LabButton(R.string.lab_clear_selection, "clear-selection") { send(DemoAction.ClearSelection) }
            }
            FixtureRows(s, send)
        }
        4 -> {
            Text(stringResource(R.string.lab_depth, s.depth))
            Text(stringResource(R.string.lab_selected, s.selected.size))
            Text("query=${s.query ?: "null"}; overlay=${s.overlay}; depth=${s.overlayDepth}", Modifier.testTag("back-state"))
            // Deliberately a test probe; this delegates to the same handler as the real top bar and system back.
            LabButton(R.string.lab_back, "body-back", onClick = onBack)
            LabButton(R.string.lab_sheet, "open-sheet") { send(DemoAction.Modal(Overlay.SHEET)) }
        }
        5 -> {
            FixtureRows(s, send)
            NameField(s, send)
            LabButton(R.string.lab_save, "bottom-save", s.canCommitName) { send(DemoAction.CommitName) }
        }
        6 -> {
            NameField(s, send)
            FixtureTabs(s.tab % 3) { send(DemoAction.Tab(it)) }
            Text(stringResource(R.string.lab_saved, s.saved), Modifier.testTag("saved-value"))
            FlowRow {
                LabButton(R.string.lab_save, "commit-name", s.canCommitName) { send(DemoAction.CommitName) }
                LabButton(R.string.lab_cancel, "cancel-edit") { send(DemoAction.CancelEdit) }
                LabButton(R.string.lab_button, "unrelated-change") { send(DemoAction.ToggleSecondary) }
            }
            FixtureRows(s, send)
        }
    }
}

@Composable
internal fun FixtureTabs(selected: Int, onSelect: (Int) -> Unit) {
    PrimaryTabRow(selectedTabIndex = selected) {
        repeat(3) { i ->
            Tab(selected = selected == i, onClick = { onSelect(i) }, text = { Text(stringResource(R.string.lab_tab, i + 1)) },
                modifier = Modifier.testTag("content-tab:$i"))
        }
    }
    Text(stringResource(R.string.lab_current, selected + 1))
}
