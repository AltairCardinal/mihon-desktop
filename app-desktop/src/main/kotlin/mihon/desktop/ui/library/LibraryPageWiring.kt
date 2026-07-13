package mihon.desktop.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import mihon.desktop.library.LibraryScreenModelFactory

internal data class LibraryPageSnapshot(
    val availableTrackerIds: Set<Long>,
    val visibleItemIds: List<Long>,
)

internal val LocalLibraryScreenModelFactory = staticCompositionLocalOf<() -> LibraryScreenModel> {
    LibraryScreenModelFactory::create
}

internal val LocalLibraryPageProbe = staticCompositionLocalOf<((LibraryPageSnapshot) -> Unit)?> { null }

@Composable
internal fun ProvideLibraryScreenModelFactory(
    factory: () -> LibraryScreenModel,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalLibraryScreenModelFactory provides factory, content = content)
}

@Composable
internal fun ProvideLibraryPageProbe(
    probe: (LibraryPageSnapshot) -> Unit,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalLibraryPageProbe provides probe, content = content)
}
