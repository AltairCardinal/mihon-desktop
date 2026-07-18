package mihon.desktop.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import mihon.desktop.domain.ReaderProgressTracker

@Composable
internal fun ReaderLifecycleEffect(
    state: ReaderState,
    scope: CoroutineScope,
    tracker: ReaderProgressTracker,
    chapterId: Long,
    sourceId: Long,
    mangaId: Long,
    chapterNumber: Double,
    exitEventId: String,
) {
    val latestPage by rememberUpdatedState(readerProgressPageForTracking(state))
    val latestUrls by rememberUpdatedState(state.resolvedUrls)
    DisposableEffect(Unit) {
        ReaderModeState.isInReaderMode = true
        onDispose {
            ReaderModeState.isInReaderMode = false
            if (chapterId != 0L && latestUrls.isNotEmpty()) {
                scope.launch(NonCancellable) {
                    tracker.track(
                        exitEventId,
                        chapterId,
                        latestPage,
                        latestUrls.size,
                        sourceId = sourceId,
                        mangaId = mangaId,
                        chapterNumber = chapterNumber,
                    )
                }
            }
        }
    }
}
