@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package eu.kanade.tachiyomi.uicatalog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.R

@Composable
internal fun VisualExamples(s: DemoState, send: Dispatch) {
    when (s.category) {
        22 -> {
            Text(stringResource(R.string.lab_theme_note))
            Text(stringResource(R.string.lab_body), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.lab_body), style = MaterialTheme.typography.bodyMedium)
            val scheme = MaterialTheme.colorScheme
            listOf(scheme.primary to scheme.onPrimary, scheme.secondaryContainer to scheme.onSecondaryContainer,
                scheme.errorContainer to scheme.onErrorContainer).forEach { (bg, fg) ->
                Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.lab_button), Modifier.padding(16.dp))
                }
            }
            Button(onClick = { send(DemoAction.Event("primary")) }, enabled = s.enabled) { Text(stringResource(R.string.lab_button)) }
            OutlinedButton(onClick = { send(DemoAction.Event("secondary")) }, enabled = s.enabled) { Text(stringResource(R.string.lab_secondary)) }
            NameField(s, send)
            if (s.phase == Phase.ERROR) Text(stringResource(R.string.lab_error), color = scheme.error)
        }
        23 -> {
            Box(Modifier.width(120.dp).aspectRatio(0.7f).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center) { Text(if (s.scenario == 2) stringResource(R.string.lab_cover) else stringResource(R.string.lab_item, 1), Modifier.padding(8.dp)) }
            Text(stringResource(R.string.lab_long_text), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (s.scenario == 3) Text(stringResource(R.string.lab_unknown))
            LabButton(R.string.lab_expand, "expand-description") { send(DemoAction.ToggleSecondary) }
            AnimatedVisibility(s.secondary) { Text(stringResource(R.string.lab_long_text).repeat(4)) }
            FlowRow { repeat(4) { id -> AssistChip(onClick = { send(DemoAction.Event("tag:$id")) },
                label = { Text(stringResource(R.string.lab_item, id + 1)) }) } }
        }
        24 -> {
            LabButton(R.string.lab_motion, "toggle-motion") { send(DemoAction.ToggleSecondary) }
            AnimatedVisibility(s.secondary) { Text(stringResource(R.string.lab_long_text)) }
            FlowRow {
                LabButton(R.string.lab_add, "add-animated-item") { send(DemoAction.AddFixture) }
                LabButton(R.string.lab_remove, "remove-animated-item", s.order.isNotEmpty()) { send(DemoAction.RemoveFixture) }
            }
            LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                items(s.order, key = { it }) { id ->
                    ListItem(headlineContent = { Text(stringResource(R.string.lab_item, id)) }, modifier = Modifier.animateItem())
                }
            }
        }
        25 -> {
            val tablet = isTabletUi()
            Text(stringResource(R.string.lab_adaptive, stringResource(if (tablet) R.string.lab_tablet else R.string.lab_phone)))
            Text(stringResource(R.string.lab_environment))
            if (tablet) Row(Modifier.fillMaxWidth()) {
                Surface(Modifier.weight(1f).padding(end = 8.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Text(stringResource(R.string.lab_info), Modifier.padding(16.dp))
                }
                Column(Modifier.weight(1f)) { NameField(s, send) }
            } else NameField(s, send)
            FixtureRows(s, send, longTitle = true)
        }
        26 -> {
            Text(stringResource(R.string.lab_environment))
            LabeledToggle(R.string.lab_accessibility, s.secondary, "accessible-toggle", enabled = s.enabled) {
                send(DemoAction.ToggleSecondary)
            }
            Button(onClick = { send(DemoAction.Event("accessible-action")) }, enabled = s.enabled,
                modifier = Modifier.testTag("accessible-action")) { Text(stringResource(R.string.lab_button)) }
            NameField(s, send)
        }
    }
}
