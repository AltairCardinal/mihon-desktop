package mihon.desktop.ui.browse

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Divider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.tachiyomi.source.CatalogueSource
import mihon.desktop.ui.extension.ExtensionListScreen
import mihon.desktop.source.selectEnabledCatalogueSourceCandidates
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR

object BrowseTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val icon = rememberVectorPainter(Icons.Default.Explore)
            return remember {
                TabOptions(
                    index = 1u,
                    title = "Browse",
                    icon = icon,
                )
            }
        }

    @Composable
    override fun Content() {
        // Wrap in a nested Navigator so that push(SourceBrowseScreen) works.
        // Inside TabNavigator, LocalNavigator only accepts Tab objects — the nested
        // Navigator provides a regular Screen stack for the Browse tab.
        Navigator(BrowseSourceListScreen()) {
            CurrentScreen()
        }
    }
}

/** Root screen of the Browse tab — lists installed/built-in sources with language filter. */
class BrowseSourceListScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val dependencies = LocalDesktopUiDependencies.current
        val sourceManager = dependencies.sourceManager
        val appPreferences = dependencies.appPreferences
        val installedSources = remember(sourceManager) { sourceManager.getCatalogueSources() }
        val enabledLanguages by appPreferences.enabledLanguages.changes().collectAsState(
            initial = appPreferences.enabledLanguages.get(),
        )
        val disabledSources by appPreferences.disabledSources.changes().collectAsState(
            initial = appPreferences.disabledSources.get(),
        )
        val pinnedSourceIds by appPreferences.pinnedSources.changes().collectAsState(
            initial = appPreferences.pinnedSources.get(),
        )
        val allSources = remember(installedSources, enabledLanguages, disabledSources) {
            selectEnabledCatalogueSourceCandidates(installedSources, enabledLanguages, disabledSources)
        }

        var selectedLang by remember { mutableStateOf<String?>(null) }
        var showLanguageFilter by remember { mutableStateOf(false) }

        val languages = remember(allSources) {
            allSources.map { it.lang }.distinct().sorted()
        }
        val effectiveSelectedLang = selectedLang?.takeIf { it in languages }
        val installedLanguages = remember(installedSources) {
            installedSources.map { it.lang }.distinct().sorted()
        }

        val displayedSources = remember(allSources, effectiveSelectedLang, pinnedSourceIds) {
            val filtered = if (effectiveSelectedLang == null) {
                allSources
            } else {
                allSources.filter { it.lang == effectiveSelectedLang }
            }
            val sorted = filtered.sortedBy { it.name.lowercase() }
            val pinned = sorted.filter { it.id.toString() in pinnedSourceIds }
            val rest = sorted.filter { it.id.toString() !in pinnedSourceIds }
            pinned + rest
        }

        fun togglePin(sourceId: Long) {
            appPreferences.pinnedSources.getAndSet { pinned ->
                val id = sourceId.toString()
                if (id in pinned) pinned - id else pinned + id
            }
        }

        if (showLanguageFilter) {
            AlertDialog(
                onDismissRequest = { showLanguageFilter = false },
                title = { Text(MR.strings.label_sources.localized()) },
                text = {
                    Column {
                        installedLanguages.forEach { language ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        appPreferences.enabledLanguages.getAndSet { enabled ->
                                            if (language in enabled) enabled - language else enabled + language
                                        }
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = language in enabledLanguages,
                                    onCheckedChange = null,
                                )
                                Text(language.uppercase())
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLanguageFilter = false }) {
                        Text(MR.strings.action_close.localized())
                    }
                },
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Browse") },
                    actions = {
                        IconButton(onClick = { showLanguageFilter = true }) {
                            Icon(
                                Icons.Default.FilterList,
                                contentDescription = MR.strings.action_filter.localized(),
                            )
                        }
                        IconButton(onClick = { navigator.push(GlobalSearchScreen()) }) {
                            Icon(Icons.Default.Search, contentDescription = "Global search")
                        }
                    },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                // Language filter chips
                if (languages.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        item {
                            FilterChip(
                                selected = effectiveSelectedLang == null,
                                onClick = { selectedLang = null },
                                label = { Text("All") },
                            )
                        }
                        items(languages) { lang ->
                            FilterChip(
                                selected = effectiveSelectedLang == lang,
                                onClick = { selectedLang = if (selectedLang == lang) null else lang },
                                label = { Text(lang.uppercase()) },
                            )
                        }
                    }
                    Divider()
                }

                // ── Local Source entry (always shown first) ──────────────────
                ListItem(
                    headlineContent = { Text("Local source") },
                    supportingContent = { Text("Read manga from local files") },
                    leadingContent = {
                        Icon(Icons.Default.Folder, contentDescription = null)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navigator.push(LocalMangaBrowseScreen()) },
                )
                HorizontalDivider()

                if (displayedSources.isEmpty()) {
                    EmptySources(onExtensionsClick = { navigator.push(ExtensionListScreen()) })
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(displayedSources, key = { it.id }) { source ->
                            val isPinned = source.id.toString() in pinnedSourceIds
                            ListItem(
                                headlineContent = { Text(source.name) },
                                supportingContent = { Text(source.lang.uppercase()) },
                                trailingContent = {
                                    IconButton(onClick = {
                                        togglePin(source.id)
                                    }) {
                                        Icon(
                                            if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                            contentDescription =
                                                "${if (isPinned) MR.strings.action_unpin.localized() else MR.strings.action_pin.localized()} ${source.name}",
                                            tint = if (isPinned) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.outline
                                            },
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            navigator.push(SourceBrowseScreen(sourceId = source.id))
                                        },
                                        onLongClick = {
                                            togglePin(source.id)
                                        },
                                    ),
                            )
                            Divider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptySources(onExtensionsClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No sources installed",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Place extension JARs in ~/.mihon/extensions/",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = onExtensionsClick) {
                Text(MR.strings.label_extensions.localized())
            }
        }
    }
}
