@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package eu.kanade.tachiyomi.uicatalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import kotlin.math.roundToInt

/** Contract rehearsals. Do not replace production reader, extension or backup integration tests with these examples. */
@Composable
internal fun BusinessExamples(s: DemoState, send: Dispatch) {
    when (s.category) {
        27 -> {
            Text(stringResource(R.string.lab_item, 1), style = MaterialTheme.typography.headlineSmall)
            if (s.phase == Phase.MISSING) Text(stringResource(R.string.lab_missing))
            SelectionControls(s, send)
            FixtureRows(s, send, chapters = true)
        }
        28 -> {
            Text(stringResource(R.string.lab_reader_notice))
            Surface(Modifier.fillMaxWidth().height(260.dp).testTag("reader-content").clickable { send(DemoAction.ToggleBars) },
                color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Box(contentAlignment = Alignment.Center) { Text(stringResource(R.string.lab_page, s.page, s.totalPages)) }
            }
            LabButton(R.string.lab_bars, "toggle-bars") { send(DemoAction.ToggleBars) }
            if (s.barsVisible) {
                Text(stringResource(R.string.lab_page, s.page, s.totalPages), Modifier.testTag("page-counter"))
                CompositionLocalProvider(LocalLayoutDirection provides if (s.descending) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Row {
                        LabButton(R.string.lab_page_prev, "reader-prev", s.page > 1) { send(DemoAction.Page(s.page - 1)) }
                        LabButton(R.string.lab_page_next, "reader-next", s.page < s.totalPages) { send(DemoAction.Page(s.page + 1)) }
                    }
                    Slider(value = s.page.toFloat(), onValueChange = { send(DemoAction.Page(it.roundToInt())) },
                        valueRange = 1f..s.totalPages.toFloat(), steps = s.totalPages - 2, modifier = Modifier.testTag("reader-slider"))
                }
                LabButton(R.string.lab_sheet, "reader-settings") { send(DemoAction.Modal(Overlay.SHEET)) }
            }
        }
        29 -> {
            StateText(s)
            TaskControls(s, send)
            LabButton(R.string.lab_clear_queue, "clear-queue", s.order.isNotEmpty()) { send(DemoAction.ClearQueue) }
            if (s.order.isEmpty()) Text(stringResource(R.string.lab_empty)) else FixtureRows(s, send, chapters = true)
        }
        30 -> {
            Text(stringResource(R.string.lab_permissions, s.permission.toString(), s.trusted.toString()))
            if (!s.permission) LabButton(R.string.lab_permission, "grant-permission") { send(DemoAction.PermissionGranted) }
            if (!s.trusted) LabButton(R.string.lab_trust, "open-trust") { send(DemoAction.Modal(Overlay.TRUST)) }
            if (s.scenario == 1) LabButton(R.string.lab_edit, "extension-settings") { send(DemoAction.Modal(Overlay.SHEET)) }
            StateText(s)
            TaskControls(s, send)
        }
        31 -> {
            Text(stringResource(R.string.lab_bound, s.bound.toString()), Modifier.testTag("bound"))
            if (!s.bound) {
                LabButton(R.string.lab_bind, "bind") { send(DemoAction.Bind) }
            } else {
                if (s.scoringSupported) {
                    Text(stringResource(R.string.lab_score, s.score))
                    Slider(value = s.score.toFloat(), onValueChange = { send(DemoAction.Score(it.roundToInt())) },
                        valueRange = 0f..10f, steps = 9, modifier = Modifier.testTag("score"))
                } else Text(stringResource(R.string.lab_score_unsupported))
                LabButton(R.string.lab_unbind, "open-unbind") { send(DemoAction.Modal(Overlay.CONFIRM)) }
            }
            if (s.phase == Phase.ERROR) {
                Text(stringResource(R.string.lab_error))
                LabButton(R.string.lab_retry, "tracking-retry") { send(DemoAction.Retry) }
            }
        }
        32 -> {
            Text(stringResource(R.string.lab_storage_notice))
            Text(stringResource(R.string.lab_dir, s.directorySelected.toString()), Modifier.testTag("directory"))
            if (!s.permission) Text(stringResource(R.string.lab_permissions, "false", "n/a"))
            if (s.phase == Phase.ERROR) Text(stringResource(R.string.lab_bad_file))
            LabButton(R.string.lab_choose_dir, "open-picker") { send(DemoAction.Modal(Overlay.PICKER)) }
            StateText(s)
            TaskControls(s, send)
        }
    }
}
