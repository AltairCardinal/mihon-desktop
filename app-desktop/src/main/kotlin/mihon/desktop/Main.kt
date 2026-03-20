package mihon.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.SlideTransition
import mihon.desktop.di.initDesktopDI
import mihon.desktop.domain.LibraryUpdateScheduler
import mihon.desktop.ui.home.HomeScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import mihon.desktop.ui.theme.DesktopTheme

fun main() {
    initDesktopDI()
    Injekt.get<LibraryUpdateScheduler>().start()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Mihon Desktop",
            state = rememberWindowState(width = 1024.dp, height = 768.dp),
        ) {
            DesktopTheme {
                Navigator(HomeScreen()) { navigator ->
                    SlideTransition(navigator)
                }
            }
        }
    }
}
