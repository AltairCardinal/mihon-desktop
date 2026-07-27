package mihon.desktop.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import mihon.desktop.library.MangaDetailScreenModelFactory

internal val LocalMangaDetailScreenModelFactory = staticCompositionLocalOf<(Long) -> MangaDetailScreenModel> {
    MangaDetailScreenModelFactory::create
}

@Composable
internal fun ProvideMangaDetailScreenModelFactory(
    factory: (Long) -> MangaDetailScreenModel,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalMangaDetailScreenModelFactory provides factory, content = content)
}
