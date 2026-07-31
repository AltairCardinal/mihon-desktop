package mihon.desktop.ui.browse

import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.LocalExtensionScreenModel

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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.tachiyomi.source.CatalogueSource
import mihon.desktop.ui.extension.ExtensionListContent
import mihon.desktop.ui.extension.pushExtensionDetails
import mihon.desktop.ui.extension.pushExtensionRepository
import mihon.desktop.ui.extension.pushSourcePreferences
import mihon.domain.source.model.SourceScreenContent
import mihon.domain.source.model.SourceScreenEvent
import mihon.domain.source.model.SourceScreenState
import tachiyomi.domain.source.model.Pin
import tachiyomi.i18n.MR
import java.util.Locale

sealed interface DesktopSourceGroupKey {
    data object LastUsed : DesktopSourceGroupKey
    data object Pinned : DesktopSourceGroupKey
    data class Language(val code: String) : DesktopSourceGroupKey
}

data class DesktopSourceListItem(
    val source: CatalogueSource,
    val isUsedLast: Boolean = false,
    val isPinned: Boolean = false,
)

data class DesktopSourceListGroup(val key: DesktopSourceGroupKey, val items: List<DesktopSourceListItem>)

object DesktopSourceGroupLabeler {
    fun displayName(key: DesktopSourceGroupKey, defaultLocale: Locale = Locale.getDefault()): String = when (key) {
        DesktopSourceGroupKey.LastUsed -> MR.strings.last_used_source.localized(defaultLocale)
        DesktopSourceGroupKey.Pinned -> MR.strings.pinned_sources.localized(defaultLocale)
        is DesktopSourceGroupKey.Language -> when (key.code) {
            "other" -> MR.strings.other_source.localized(defaultLocale)
            "all" -> MR.strings.multi_lang.localized(defaultLocale)
            else -> key.code.sourceLocale(defaultLocale)
                .let { it.getDisplayName(it).replaceFirstChar { character -> character.uppercase(it) } }
        }
    }
}

private fun String.sourceLocale(defaultLocale: Locale): Locale = when (this) {
    "" -> defaultLocale
    "zh-CN" -> Locale.forLanguageTag("zh-Hans")
    "zh-TW" -> Locale.forLanguageTag("zh-Hant")
    else -> Locale.forLanguageTag(this)
}

object DesktopSourceListProjector {
    fun project(
        sourceState: SourceScreenState,
        catalogueSources: List<CatalogueSource>,
        selectedLanguage: String? = null,
    ): List<DesktopSourceListGroup> {
        val sources = (sourceState.content as? SourceScreenContent.Content)?.sources.orEmpty()
            .filter { selectedLanguage == null || it.lang == selectedLanguage }
        val catalogueById = catalogueSources.associateBy(CatalogueSource::id)
        val items = sources.mapNotNull { source ->
            catalogueById[source.id]?.let {
                DesktopSourceListItem(it, source.isUsedLast, Pin.Actual in source.pin)
            }
        }
        return buildList {
            items.filter(DesktopSourceListItem::isUsedLast).takeIf(List<DesktopSourceListItem>::isNotEmpty)?.let {
                add(DesktopSourceListGroup(DesktopSourceGroupKey.LastUsed, it))
            }
            items.filter { it.isPinned && !it.isUsedLast }.takeIf(List<DesktopSourceListItem>::isNotEmpty)?.let {
                add(DesktopSourceListGroup(DesktopSourceGroupKey.Pinned, it))
            }
            items.filterNot { it.isPinned || it.isUsedLast }
                .groupBy { it.source.lang }
                .toSortedMap(compareBy<String> { it.isEmpty() }.thenBy { it })
                .forEach { (language, languageSources) ->
                    add(
                        DesktopSourceListGroup(
                            DesktopSourceGroupKey.Language(language),
                            languageSources,
                        ),
                    )
                }
        }
    }
}

object BrowseTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val icon = rememberVectorPainter(Icons.Default.Explore)
            return remember {
                TabOptions(
                    index = 1u,
                    title = MR.strings.browse.localized(),
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
        var selectedSection by rememberSaveable { mutableIntStateOf(BROWSE_SOURCES_SECTION) }

        if (selectedSection == BROWSE_EXTENSIONS_SECTION) {
            ExtensionListContent(
                model = LocalExtensionScreenModel.current(),
                title = MR.strings.browse.localized(),
                showBackButton = false,
                onRepositories = navigator::pushExtensionRepository,
                onOpen = navigator::pushExtensionDetails,
                onSettings = navigator::pushSourcePreferences,
                primaryNavigation = {
                    BrowseSectionTabs(
                        selectedSection = selectedSection,
                        onSectionSelected = { selectedSection = it },
                    )
                },
            )
            return
        }

        val dependencies = LocalDesktopUiDependencies.current
        val sourceManager = dependencies.sourceManager
        val appPreferences = dependencies.appPreferences
        val model = rememberScreenModel { DesktopSourcesScreenModel(sourceManager, appPreferences) }
        val state by model.state.collectAsState()
        val contentSources = (state.sourceState.content as? SourceScreenContent.Content)?.sources.orEmpty()
        val snackbar = remember { SnackbarHostState() }
        val event = state.sourceState.pendingEvent
        LaunchedEffect(event?.id) {
            event?.let {
                snackbar.showSnackbar(sourceEventMessage(it, state.catalogueSources))
                model.consumeEvent(it.id)
            }
        }

        var selectedLang by remember { mutableStateOf<String?>(null) }
        var showLanguageFilter by remember { mutableStateOf(false) }

        val languages = remember(contentSources) {
            contentSources.map { it.lang }.distinct().sorted()
        }
        val effectiveSelectedLang = selectedLang?.takeIf { it in languages }
        val installedLanguages = remember(state.catalogueSources) {
            state.catalogueSources.map { it.lang }.distinct().sorted()
        }

        val displayedSourceGroups = remember(state, effectiveSelectedLang) {
            DesktopSourceListProjector.project(state.sourceState, state.catalogueSources, effectiveSelectedLang)
        }

        fun togglePin(sourceId: Long) {
            state.catalogueSources.firstOrNull { it.id == sourceId }?.let(model::togglePin)
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
                                        model.toggleLanguage(language)
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = language in state.enabledLanguages,
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
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text(MR.strings.browse.localized()) },
                        actions = {
                            IconButton(onClick = { showLanguageFilter = true }) {
                                Icon(
                                    Icons.Default.FilterList,
                                    contentDescription = MR.strings.action_filter.localized(),
                                )
                            }
                            IconButton(onClick = { navigator.push(GlobalSearchScreen()) }) {
                                Icon(Icons.Default.Search, contentDescription = MR.strings.action_global_search.localized())
                            }
                        },
                    )
                    BrowseSectionTabs(
                        selectedSection = selectedSection,
                        onSectionSelected = { selectedSection = it },
                    )
                }
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
                                label = { Text(MR.strings.all.localized()) },
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
                    headlineContent = { Text(MR.strings.local_source.localized()) },
                    supportingContent = { Text(MR.strings.desktop_ui_read_manga_from_local_files.localized()) },
                    leadingContent = {
                        Icon(Icons.Default.Folder, contentDescription = null)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navigator.push(LocalMangaBrowseScreen()) },
                )
                HorizontalDivider()

                val content = state.sourceState.content
                when {
                    content is SourceScreenContent.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(MR.strings.desktop_ui_loading_sources.localized())
                    }
                    content is SourceScreenContent.Failure -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(content.message)
                            TextButton(onClick = model::retry) { Text(MR.strings.action_retry.localized()) }
                        }
                    }
                    displayedSourceGroups.isEmpty() ->
                        EmptySources(onExtensionsClick = { selectedSection = BROWSE_EXTENSIONS_SECTION })
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        displayedSourceGroups.forEach { group ->
                            item(key = "source-header-${group.key}") {
                                Text(
                                    text = DesktopSourceGroupLabeler.displayName(group.key),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            items(
                                items = group.items,
                                key = { "${group.key}-${it.source.id}-${it.isUsedLast}" },
                            ) { entry ->
                                val source = entry.source
                                val isPinned = entry.isPinned
                                ListItem(
                                    headlineContent = { Text(source.name) },
                                    supportingContent = { Text(source.lang.uppercase()) },
                                    trailingContent = {
                                        IconButton(onClick = { togglePin(source.id) }) {
                                            Icon(
                                                if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                                contentDescription =
                                                    "${if (isPinned) MR.strings.action_unpin.localized() else MR.strings.action_pin.localized()} ${source.name}",
                                                tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = { navigator.push(SourceBrowseScreen(sourceId = source.id)) },
                                            onLongClick = { togglePin(source.id) },
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
}

private const val BROWSE_SOURCES_SECTION = 0
private const val BROWSE_EXTENSIONS_SECTION = 1

@Composable
private fun BrowseSectionTabs(
    selectedSection: Int,
    onSectionSelected: (Int) -> Unit,
) {
    TabRow(selectedTabIndex = selectedSection) {
        Tab(
            selected = selectedSection == BROWSE_SOURCES_SECTION,
            onClick = { onSectionSelected(BROWSE_SOURCES_SECTION) },
            text = { Text(MR.strings.label_sources.localized()) },
        )
        Tab(
            selected = selectedSection == BROWSE_EXTENSIONS_SECTION,
            onClick = { onSectionSelected(BROWSE_EXTENSIONS_SECTION) },
            text = { Text(MR.strings.label_extensions.localized()) },
        )
    }
}

internal fun sourceEventMessage(event: SourceScreenEvent, sources: List<CatalogueSource>): String {
    val name = sources.firstOrNull { it.id == (event as? SourceScreenEvent.Pinned)?.sourceId }?.name.orEmpty()
    return when (event) {
        is SourceScreenEvent.Pinned -> "${if (event.pinned) "Pinned" else "Unpinned"} $name"
        is SourceScreenEvent.Disabled -> "${if (event.disabled) "Disabled" else "Enabled"} source"
        is SourceScreenEvent.ActionFailed -> event.message
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
                text = MR.strings.desktop_ui_no_sources_installed.localized(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = MR.strings.desktop_ui_place_extension_jars_in_mihon_extensions.localized(),
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
