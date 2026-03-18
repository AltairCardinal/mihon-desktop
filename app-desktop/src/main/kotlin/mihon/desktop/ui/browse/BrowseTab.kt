package mihon.desktop.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.Divider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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

/** Root screen of the Browse tab — lists installed/built-in sources. */
class BrowseSourceListScreen : Screen {

    @Composable
    override fun Content() {
        val sourceManager = remember { Injekt.get<SourceManager>() }
        val sources = remember { sourceManager.getCatalogueSources() }

        if (sources.isEmpty()) {
            EmptySources()
        } else {
            SourceList(sources)
        }
    }
}

@Composable
private fun EmptySources() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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
        }
    }
}

@Composable
private fun SourceList(sources: List<CatalogueSource>) {
    // LocalNavigator here belongs to the nested Navigator — not the TabNavigator.
    val navigator = LocalNavigator.currentOrThrow
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(sources, key = { it.id }) { source ->
            ListItem(
                headlineContent = { Text(source.name) },
                supportingContent = { Text(source.lang.uppercase()) },
                modifier = Modifier.clickable {
                    navigator.push(SourceBrowseScreen(sourceId = source.id))
                },
            )
            Divider(modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}
