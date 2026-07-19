package mihon.desktop.ui.extension

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.PreferenceScreen
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.preference.CheckBoxPreference
import eu.kanade.tachiyomi.source.preference.EditTextPreference
import eu.kanade.tachiyomi.source.preference.JvmPreferenceItem
import eu.kanade.tachiyomi.source.preference.ListPreference
import eu.kanade.tachiyomi.source.preference.MultiSelectListPreference
import eu.kanade.tachiyomi.source.preference.PreferenceCategoryItem
import eu.kanade.tachiyomi.source.preference.SwitchPreference
import mihon.desktop.extension.DesktopExtensionManager
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.i18n.MR
import java.util.Locale
import java.util.prefs.Preferences as JvmPrefs

internal sealed interface SourcePreferencesState {
    data object Missing : SourcePreferencesState
    data object NonConfigurable : SourcePreferencesState
    data class SetupFailure(val error: Throwable) : SourcePreferencesState
    data object Empty : SourcePreferencesState
    data class Content(val items: List<JvmPreferenceItem>) : SourcePreferencesState
}

internal fun resolveSourcePreferencesState(
    source: Source?,
    contextFactory: (ClassLoader?) -> Any? = ::createExtensionContext,
): SourcePreferencesState = when (source) {
    null -> SourcePreferencesState.Missing
    !is ConfigurableSource -> SourcePreferencesState.NonConfigurable
    else -> {
        val screen = PreferenceScreen()
        try {
            contextFactory(source::class.java.classLoader)?.let(screen::setContext)
        } catch (_: Exception) {
            // Context is optional compatibility wiring; setup decides actual availability.
        } catch (_: LinkageError) {
            // Missing optional Android compatibility classes must not block JVM descriptors.
        }
        try {
            DesktopAndroidPreferenceAdapter.setupPreferenceScreen(source, screen)
            screen.preferences.takeIf { it.isNotEmpty() }
                ?.let(SourcePreferencesState::Content)
                ?: SourcePreferencesState.Empty
        } catch (error: Exception) {
            SourcePreferencesState.SetupFailure(error)
        } catch (error: LinkageError) {
            SourcePreferencesState.SetupFailure(error)
        }
    }
}

private fun createExtensionContext(classLoader: ClassLoader?): Any? = classLoader
    ?.loadClass("android.content.Context")
    ?.getDeclaredConstructor()
    ?.newInstance()

internal val LocalSourcePreferencesStateResolver = staticCompositionLocalOf<(Source?) -> SourcePreferencesState> {
    { source -> resolveSourcePreferencesState(source) }
}

/**
 * Displays configurable preferences for a single source.
 * Only sources implementing [ConfigurableSource] with JVM-compatible
 * [eu.kanade.tachiyomi.source.preference.JvmPreferenceItem] descriptors will show settings.
 */
data class SourcePreferencesScreen(
    val sourceId: Long,
    val sourceName: String,
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val manager = LocalDesktopUiDependencies.current.extensionManager

        val source = remember(sourceId) { manager.getSource(sourceId) }
        val stateResolver = LocalSourcePreferencesStateResolver.current
        val state = remember(sourceId, source, stateResolver) { stateResolver(source) }

        val prefStore = remember(sourceId) {
            DesktopPreferenceStore(
                JvmPrefs.userRoot().node("/mihon/source_$sourceId"),
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(sourceName) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding ->
            when (state) {
                SourcePreferencesState.Missing -> SourcePreferencesStatus(MR.strings.desktop_source_preferences_missing.localized(), Modifier.padding(padding))
                SourcePreferencesState.NonConfigurable -> SourcePreferencesStatus(MR.strings.desktop_source_preferences_non_configurable.localized(), Modifier.padding(padding))
                is SourcePreferencesState.SetupFailure -> SourcePreferencesStatus(
                    MR.strings.desktop_source_preferences_setup_failed.localized(Locale.getDefault(), state.error.message.orEmpty()),
                    Modifier.padding(padding),
                )
                SourcePreferencesState.Empty -> SourcePreferencesStatus(MR.strings.desktop_source_preferences_empty.localized(), Modifier.padding(padding))
                is SourcePreferencesState.Content -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                ) {
                    items(state.items, key = { "${it::class.simpleName}:${it.key}:${it.title}" }) { item ->
                        PreferenceItemRow(item = item, prefStore = prefStore)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcePreferencesStatus(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PreferenceItemRow(
    item: JvmPreferenceItem,
    prefStore: DesktopPreferenceStore,
) {
    if (!item.isVisible) return

    when (item) {
        is PreferenceCategoryItem -> CategoryHeader(item)
        is SwitchPreference -> SwitchRow(item, prefStore)
        is CheckBoxPreference -> CheckBoxRow(item, prefStore)
        is EditTextPreference -> EditTextRow(item, prefStore)
        is ListPreference -> ListRow(item, prefStore)
        is MultiSelectListPreference -> MultiSelectRow(item, prefStore)
    }
}

@Composable
private fun CategoryHeader(item: PreferenceCategoryItem) {
    Column {
        HorizontalDivider()
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SwitchRow(item: SwitchPreference, prefStore: DesktopPreferenceStore) {
    val pref = remember(item.key) { prefStore.getBoolean(item.key, item.defaultValue) }
    var checked by remember(item.key) { mutableStateOf(pref.get()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.isEnabled) {
                checked = !checked
                pref.set(checked)
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge)
            item.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(
            checked = checked,
            onCheckedChange = { v ->
                checked = v
                pref.set(v)
            },
            enabled = item.isEnabled,
        )
    }
}

@Composable
private fun CheckBoxRow(item: CheckBoxPreference, prefStore: DesktopPreferenceStore) {
    val pref = remember(item.key) { prefStore.getBoolean(item.key, item.defaultValue) }
    var checked by remember(item.key) { mutableStateOf(pref.get()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.isEnabled) {
                checked = !checked
                pref.set(checked)
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge)
            item.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Checkbox(
            checked = checked,
            onCheckedChange = { v ->
                checked = v
                pref.set(v)
            },
            enabled = item.isEnabled,
        )
    }
}

@Composable
private fun EditTextRow(item: EditTextPreference, prefStore: DesktopPreferenceStore) {
    val pref = remember(item.key) { prefStore.getString(item.key, item.defaultValue) }
    var showDialog by remember { mutableStateOf(false) }
    var storedValue by remember(item.key) { mutableStateOf(pref.get()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.isEnabled) { showDialog = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge)
            val hint = item.summary ?: storedValue.takeIf { it.isNotEmpty() }
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    if (showDialog) {
        var draft by remember { mutableStateOf(storedValue) }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(item.title) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    storedValue = draft
                    pref.set(draft)
                    showDialog = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ListRow(item: ListPreference, prefStore: DesktopPreferenceStore) {
    val pref = remember(item.key) { prefStore.getString(item.key, item.defaultValue ?: "") }
    var showDialog by remember { mutableStateOf(false) }
    var selected by remember(item.key) { mutableStateOf(pref.get()) }

    val displayValue = remember(selected) {
        val idx = item.entryValues.indexOf(selected)
        if (idx >= 0) item.entries.getOrNull(idx) else selected
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.isEnabled) { showDialog = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge)
            val hint = item.summary ?: displayValue
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(item.title) },
            text = {
                LazyColumn {
                    items(item.entries.zip(item.entryValues)) { (entry, value) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = value
                                    pref.set(value)
                                    showDialog = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == value, onClick = null)
                            Text(entry, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun MultiSelectRow(item: MultiSelectListPreference, prefStore: DesktopPreferenceStore) {
    val pref = remember(item.key) { prefStore.getStringSet(item.key, item.defaultValue) }
    var showDialog by remember { mutableStateOf(false) }
    val selected = remember(item.key) { mutableStateMapOf<String, Boolean>().also { map -> pref.get().forEach { map[it] = true } } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.isEnabled) { showDialog = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge)
            item.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(item.title) },
            text = {
                LazyColumn {
                    items(item.entries.zip(item.entryValues)) { (entry, value) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected[value] = !(selected[value] ?: false) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = selected[value] ?: false,
                                onCheckedChange = { v -> selected[value] = v },
                            )
                            Text(entry, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pref.set(selected.filter { it.value }.keys.toSet())
                    showDialog = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            },
        )
    }
}
