package mihon.desktop.ui.more

import tachiyomi.i18n.MR

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import mihon.desktop.ui.settings.MoreRootScreen

object MoreTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val icon = rememberVectorPainter(Icons.Default.MoreHoriz)
            return remember {
                TabOptions(index = 4u, title = MR.strings.label_more.localized(), icon = icon)
            }
        }

    @Composable
    override fun Content() {
        // Nested Navigator — same pattern as LibraryTab — so that push(SettingsScreen) works.
        Navigator(MoreRootScreen()) {
            CurrentScreen()
        }
    }
}
