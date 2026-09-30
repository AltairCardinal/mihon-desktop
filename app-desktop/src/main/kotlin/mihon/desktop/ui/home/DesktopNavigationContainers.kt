package mihon.desktop.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Desktop adapter of presentation-core's Android-only navigation containers. */
@Composable
internal fun DesktopNavigationBar(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    val color = NavigationBarDefaults.containerColor
    Surface(modifier = modifier, color = color, contentColor = contentColorFor(color), tonalElevation = NavigationBarDefaults.Elevation) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(NavigationBarDefaults.windowInsets).height(80.dp).selectableGroup(),
            content = content,
        )
    }
}

@Composable
internal fun DesktopNavigationRail(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val color = NavigationRailDefaults.ContainerColor
    Surface(modifier = modifier, color = color, contentColor = contentColorFor(color), tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxHeight().windowInsetsPadding(NavigationRailDefaults.windowInsets).widthIn(min = 80.dp)
                .padding(vertical = 4.dp).selectableGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            content = content,
        )
    }
}
