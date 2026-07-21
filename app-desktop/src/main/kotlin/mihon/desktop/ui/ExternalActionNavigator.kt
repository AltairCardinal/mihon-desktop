package mihon.desktop.ui

import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mihon.desktop.library.MangaDetailScreenModelFactory
import mihon.desktop.platform.DesktopExternalActionTarget
import mihon.desktop.test.state.TestState
import mihon.desktop.test.state.applicationState
import mihon.desktop.ui.browse.GlobalSearchScreen
import mihon.desktop.ui.library.MangaDetailScreen
import mihon.desktop.ui.reader.DesktopReaderScreen
import mihon.desktop.ui.settings.BackupSettingsScreen
import mihon.desktop.ui.settings.ExtensionRepoScreen
import mihon.domain.platform.ExternalActionInput
import tachiyomi.i18n.MR
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

class ExternalActionNavigator internal constructor(
    private val resolveTarget: suspend (ExternalActionInput) -> DesktopExternalActionTarget,
    private val chapterDestination: suspend (DesktopExternalActionTarget.Chapter) -> Screen = ::chapterDestination,
    private val testState: TestState = applicationState,
) {
    private val pending = ArrayDeque<ExternalActionInput>()
    private val pendingLock = Any()
    private val consumerMutex = Mutex()
    private val generation = AtomicLong()
    private val _pendingSignals = MutableStateFlow(0L)

    internal val hasPendingAction: Boolean get() = synchronized(pendingLock) { pending.isNotEmpty() }

    fun submit(input: ExternalActionInput) {
        synchronized(pendingLock) { pending.addLast(input) }
        testState.recordExternalAction("Pending")
        signalPending()
    }

    suspend fun consumePending(
        navigator: Navigator,
        showFeedback: suspend (String) -> Unit,
    ) = consumerMutex.withLock {
        while (true) {
            val input = synchronized(pendingLock) { pending.pollFirst() } ?: return@withLock
            try {
                when (val target = resolveTarget(input)) {
                    is DesktopExternalActionTarget.Rejected -> {
                        testState.recordExternalAction("Rejected", target.reason.name)
                        showFeedback(rejectionFeedback(target))
                    }
                    else -> {
                        val destination = destination(target)
                        navigator.push(destination)
                        testState.setCurrentScreen(destination::class.simpleName ?: destination.key)
                        testState.recordExternalAction("Succeeded", destination::class.simpleName)
                    }
                }
            } catch (cancelled: CancellationException) {
                synchronized(pendingLock) { pending.addFirst(input) }
                signalPending()
                throw cancelled
            } catch (_: Exception) {
                testState.recordExternalAction("Failed")
                showFeedback(MR.strings.unknown_error.localized())
            }
        }
    }

    suspend fun consumeSignals(navigator: Navigator, showFeedback: suspend (String) -> Unit) {
        _pendingSignals.collect { consumePending(navigator, showFeedback) }
    }

    private fun signalPending() {
        _pendingSignals.value = generation.incrementAndGet()
    }

    internal suspend fun destination(target: DesktopExternalActionTarget): Screen = when (target) {
        is DesktopExternalActionTarget.GlobalSearch -> GlobalSearchScreen(target.query)
        is DesktopExternalActionTarget.Manga -> MangaDetailScreen(target.mangaId)
        is DesktopExternalActionTarget.Chapter -> chapterDestination(target)
        is DesktopExternalActionTarget.Backup -> BackupSettingsScreen(target.file)
        is DesktopExternalActionTarget.ExtensionRepo -> ExtensionRepoScreen(target.url)
        is DesktopExternalActionTarget.Rejected -> error("Rejected actions do not have destinations")
    }
}

private fun rejectionFeedback(target: DesktopExternalActionTarget.Rejected): String = when (target.reason) {
    DesktopExternalActionTarget.Rejection.InvalidBackupPath -> MR.strings.invalid_backup_file.localized()
    DesktopExternalActionTarget.Rejection.NoAction,
    DesktopExternalActionTarget.Rejection.ParserRejected,
    DesktopExternalActionTarget.Rejection.SourceResolutionFailed,
    -> MR.strings.error_no_match.localized()
}

private suspend fun chapterDestination(target: DesktopExternalActionTarget.Chapter): Screen {
    val model = MangaDetailScreenModelFactory.create(target.mangaId)
    val (manga, chapters) = model.mangaWithChaptersFlow().first()
    val chapter = chapters.firstOrNull { it.id == target.chapterId } ?: error("Chapter is unavailable")
    val request = model.readerRequest(manga, chapters, chapter) ?: error("Chapter cannot be opened in the reader")
    return DesktopReaderScreen(
        chapterTitle = request.chapterTitle,
        mangaId = request.mangaId,
        mangaTitle = request.mangaTitle,
        pageUrls = emptyList(),
        isWebtoon = false,
        sourceId = request.sourceId,
        chapterUrl = request.chapterUrl,
        chapterId = request.chapterId,
        chapters = request.chapters,
        currentChapterIndex = request.currentChapterIndex,
        initialPage = request.initialPage,
        mangaViewerFlags = request.mangaViewerFlags,
    )
}
