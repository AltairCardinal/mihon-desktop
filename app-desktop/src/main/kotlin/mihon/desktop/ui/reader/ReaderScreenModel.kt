package mihon.desktop.ui.reader

import cafe.adriel.voyager.core.model.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import mihon.desktop.reader.ReaderBackgroundTheme
import mihon.desktop.reader.ReaderColorFilter
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ScaleType
import mihon.desktop.reader.WebtoonSidePadding
import mihon.desktop.reader.ZoomState
import mihon.desktop.reader.dualPageFromViewerFlags
import mihon.desktop.reader.readingModeFromViewerFlags
import mihon.domain.error.AppError
import mihon.domain.reader.ReaderChapterModel
import mihon.domain.reader.ReaderChapterState
import mihon.domain.reader.ReaderChapterTransitionModel
import mihon.domain.reader.ReaderNavigationCommand
import mihon.domain.reader.ReaderPageModel
import mihon.domain.reader.ReaderTransitionDirection

fun interface AdjacentChapterLoader {
    suspend fun load(chapter: ReaderChapterModel): ReaderChapterState
}

/**
 * Voyager ScreenModel for [DesktopReaderScreen].
 *
 * Owns all reader state and exposes it as [StateFlow<ReaderState>].
 * All state transitions go through explicit mutation methods, enabling
 * JVM unit tests to exercise state logic without Compose or DI.
 *
 * The Composable [DesktopReaderScreen.Content] only reads from [state]
 * and dispatches events to these methods — it does not hold any mutable
 * Compose state for reader-logic concerns.
 */
class ReaderScreenModel(
    private val chapterTitle: String = "",
    val pageUrls: List<String> = emptyList(),
    val initialPage: Int = 0,
    private val chapterId: Long = 0L,
    private val isWebtoon: Boolean = false,
    val sourceId: Long = 0L,
    val chapterUrl: String = "",
    val mangaViewerFlags: Long = 0L,
    private val dualPageOverride: Boolean? = null,
    prefs: ReaderPreferences = ReaderPreferences(),
    private val persistViewerFlags: suspend (mangaId: Long, flags: Long) -> Unit = { _, _ -> },
    private val adjacentChapterLoader: AdjacentChapterLoader = AdjacentChapterLoader { chapter ->
        ReaderChapterState.Error(
            error = AppError.Unknown(IllegalStateException("Adjacent chapter loader is unavailable")),
            retryTargetChapterId = chapter.id,
        )
    },
) : ScreenModel {

    private data class TransitionLoadKey(val targetChapterId: Long, val generation: Long)

    private val transitionLoadLock = Any()
    private var transitionGeneration = 0L
    private val transitionLoadsInFlight = mutableSetOf<TransitionLoadKey>()

    private val _state = MutableStateFlow(buildInitialState(prefs))
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private fun buildInitialState(prefs: ReaderPreferences): ReaderState {
        val resolvedMode = when {
            isWebtoon -> ReadingMode.WEBTOON
            else -> readingModeFromViewerFlags(mangaViewerFlags) ?: prefs.readingMode
        }
        val chapterState = when {
            pageUrls.isNotEmpty() -> ReaderChapterState.Loaded(pageUrls.toReaderPages())
            pageUrls.isEmpty() && sourceId != 0L && chapterUrl.isNotBlank() -> ReaderChapterState.Loading
            else -> ReaderChapterState.Wait
        }
        return ReaderState(
            currentPage = initialPage.coerceAtLeast(0),
            resolvedUrls = pageUrls,
            isLoadingPages = pageUrls.isEmpty() && sourceId != 0L && chapterUrl.isNotBlank(),
            chapterState = chapterState,
            readingMode = resolvedMode,
            dualPageMode = dualPageFromViewerFlags(mangaViewerFlags) ?: dualPageOverride ?: prefs.isDualPage,
            autoSplitPages = prefs.autoSplitPages,
            autoSpreadMatching = prefs.isAutoSpreadMatching,
            backgroundTheme = prefs.backgroundTheme,
            navigationMode = prefs.navigationMode,
            cropBordersPager = prefs.cropBordersPager,
            cropBordersWebtoon = prefs.cropBordersWebtoon,
            webtoonSidePadding = prefs.webtoonSidePadding,
            webtoonAutoScroll = prefs.webtoonAutoScroll,
            webtoonAutoScrollSpeed = prefs.webtoonAutoScrollSpeed,
            scaleType = prefs.scaleType,
            colorFilter = prefs.loadColorFilter(),
            skipReadChapters = prefs.skipReadChapters,
            skipFilteredChapters = prefs.skipFilteredChapters,
            skipDuplicateChapters = prefs.skipDuplicateChapters,
        )
    }

    // ── Page navigation ──────────────────────────────────────────────────────

    fun goToPage(page: Int) {
        _state.update { s ->
            val max = (s.resolvedUrls.size - 1).coerceAtLeast(0)
            s.copy(currentPage = page.coerceIn(0, max))
        }
    }

    fun setLoadedPages(urls: List<String>, initialPage: Int = 0) {
        _state.update { s ->
            val page = initialPage.coerceIn(0, (urls.size - 1).coerceAtLeast(0))
            s.copy(
                resolvedUrls = urls,
                isLoadingPages = false,
                currentPage = page,
                errorMessage = null,
                chapterState = ReaderChapterState.Loaded(urls.toReaderPages()),
            )
        }
    }

    fun setLoadingPageSlots(totalPages: Int, initialPage: Int = 0) {
        _state.update { s ->
            val pageCount = totalPages.coerceAtLeast(0)
            s.copy(
                resolvedUrls = List(pageCount) { "" },
                isLoadingPages = pageCount > 0,
                currentPage = initialPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                errorMessage = null,
                chapterState = ReaderChapterState.Loading,
            )
        }
    }

    fun appendLoadedPage(index: Int, url: String) {
        _state.update { s ->
            val newUrls = s.resolvedUrls.toMutableList()
            while (newUrls.size <= index) newUrls.add("")
            newUrls[index] = url
            val firstArrived = s.isLoadingPages && url.isNotBlank()
            s.copy(
                resolvedUrls = newUrls,
                isLoadingPages = if (firstArrived) false else s.isLoadingPages,
                currentPage = s.currentPage.coerceIn(0, (newUrls.size - 1).coerceAtLeast(0)),
            )
        }
    }

    fun hasLoadedPage(): Boolean = state.value.resolvedUrls.any { it.isNotBlank() }

    fun setLoadError(message: String) {
        _state.update {
            it.copy(
                isLoadingPages = false,
                errorMessage = message,
                chapterState = ReaderChapterState.Error(
                    error = AppError.Unknown(IllegalStateException(message)),
                    retryTargetChapterId = chapterId,
                ),
            )
        }
    }

    fun setLoadingDone() {
        _state.update {
            it.copy(
                isLoadingPages = false,
                chapterState = ReaderChapterState.Loaded(it.resolvedUrls.toReaderPages()),
            )
        }
    }

    fun requestRetry() {
        _state.update {
            it.copy(
                isLoadingPages = true,
                errorMessage = null,
                chapterState = ReaderChapterState.Loading,
                loadGeneration = it.loadGeneration + 1,
            )
        }
    }

    fun showChapterBoundary(
        direction: ReaderTransitionDirection,
        chapterId: Long,
        chapterUrl: String,
        chapterName: String,
        chapterNumber: Double,
    ) {
        val chapter = ReaderChapterModel(chapterId, chapterUrl, chapterName, chapterNumber)
        synchronized(transitionLoadLock) {
            transitionGeneration++
            _state.update {
                it.copy(
                    chapterTransition = ReaderChapterTransitionModel(
                        direction = direction,
                        from = chapter,
                        to = null,
                    ),
                )
            }
        }
    }

    fun showChapterTransition(
        direction: ReaderTransitionDirection,
        from: ReaderChapterModel,
        to: ReaderChapterModel,
        missingChapterCount: Int,
    ) {
        synchronized(transitionLoadLock) {
            val current = _state.value.chapterTransition
            val duplicatesActiveRequest =
                current?.direction == direction &&
                    current.from.id == from.id &&
                    current.to?.id == to.id &&
                    current.state == ReaderChapterState.Loading
            if (duplicatesActiveRequest) return

            transitionGeneration++
            _state.update {
                it.copy(
                    chapterTransition = ReaderChapterTransitionModel(
                        direction = direction,
                        from = from,
                        to = to,
                        missingChapterCount = missingChapterCount,
                        state = ReaderChapterState.Loading,
                    ),
                )
            }
        }
    }

    fun clearChapterTransition() {
        synchronized(transitionLoadLock) {
            transitionGeneration++
            _state.update { it.copy(chapterTransition = null) }
        }
    }

    fun setChapterTransitionState(state: ReaderChapterState) {
        _state.update { current ->
            current.copy(chapterTransition = current.chapterTransition?.copy(state = state))
        }
    }

    fun chapterTransitionCommand(): ReaderNavigationCommand? =
        state.value.chapterTransition?.retryCommand()

    suspend fun loadChapterTransition(targetChapterId: Long? = null): ReaderChapterState? {
        val request = synchronized(transitionLoadLock) {
            val transition = _state.value.chapterTransition ?: return null
            val target = transition.to ?: return null
            if (targetChapterId != null && target.id != targetChapterId) return null
            val key = TransitionLoadKey(target.id, transitionGeneration)
            if (!transitionLoadsInFlight.add(key)) return transition.state
            _state.update { current ->
                current.copy(chapterTransition = current.chapterTransition?.copy(state = ReaderChapterState.Loading))
            }
            key to target
        }
        val (loadKey, target) = request
        val result = try {
            adjacentChapterLoader.load(target)
        } catch (error: Exception) {
            ReaderChapterState.Error(
                error = AppError.Unknown(error),
                retryTargetChapterId = target.id,
            )
        }
        synchronized(transitionLoadLock) {
            transitionLoadsInFlight.remove(loadKey)
            _state.update { current ->
                val activeTransition = current.chapterTransition
                if (
                    loadKey.generation == transitionGeneration &&
                    activeTransition?.to?.id == target.id
                ) {
                    current.copy(chapterTransition = activeTransition.copy(state = result))
                } else {
                    current
                }
            }
        }
        return result
    }

    suspend fun retryChapterTransition(): ReaderNavigationCommand? {
        val command = chapterTransitionCommand()
        if (command is ReaderNavigationCommand.RetryChapter) {
            loadChapterTransition(command.chapterId)
        }
        return command
    }

    // ── UI visibility ─────────────────────────────────────────────────────────

    fun toggleSettings() {
        _state.update { it.copy(showSettings = !it.showSettings) }
    }

    fun closeSettings() {
        _state.update { it.copy(showSettings = false) }
    }

    fun toggleUI() {
        _state.update { it.copy(showUI = !it.showUI) }
    }

    // ── Reading mode ──────────────────────────────────────────────────────────

    /** Changes reading mode. For webtoon-flagged chapters this is a no-op. */
    fun setReadingMode(mode: ReadingMode, prefs: ReaderPreferences? = null) {
        if (isWebtoon) return
        _state.update { it.copy(readingMode = mode) }
        if (mode != ReadingMode.WEBTOON) prefs?.readingMode = mode
    }

    // ── Dual-page & spread management ─────────────────────────────────────────

    fun setDualPageMode(on: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { s ->
            s.copy(
                dualPageMode = on,
                forcedSinglePages = if (!on) emptySet() else s.forcedSinglePages,
            )
        }
        prefs?.isDualPage = on
    }

    fun setAutoSplitPages(on: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(autoSplitPages = on) }
        prefs?.autoSplitPages = on
    }

    fun setAutoSpreadMatching(on: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(autoSpreadMatching = on) }
        prefs?.isAutoSpreadMatching = on
    }

    fun setSpreadPages(pages: Set<Int>) {
        _state.update { it.copy(spreadPages = pages) }
    }

    fun setForcedSinglePages(pages: Set<Int>) {
        _state.update { it.copy(forcedSinglePages = pages) }
    }

    fun setMatchedPairs(pairs: Set<Pair<Int, Int>>) {
        _state.update { it.copy(matchedPairs = pairs) }
    }

    fun setVirtualPages(pages: List<mihon.desktop.reader.VirtualPage>?) {
        _state.update { it.copy(virtualPages = pages) }
    }

    // ── Display settings ──────────────────────────────────────────────────────

    fun setBackgroundTheme(theme: ReaderBackgroundTheme, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(backgroundTheme = theme) }
        prefs?.backgroundTheme = theme
    }

    fun setNavigationMode(mode: NavigationMode, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(navigationMode = mode) }
        prefs?.navigationMode = mode
    }

    fun setCropBordersPager(on: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(cropBordersPager = on) }
        prefs?.cropBordersPager = on
    }

    fun setCropBordersWebtoon(on: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(cropBordersWebtoon = on) }
        prefs?.cropBordersWebtoon = on
    }

    fun setWebtoonSidePadding(padding: WebtoonSidePadding, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(webtoonSidePadding = padding) }
        prefs?.webtoonSidePadding = padding
    }

    fun setWebtoonAutoScroll(on: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(webtoonAutoScroll = on) }
        prefs?.webtoonAutoScroll = on
    }

    fun setWebtoonAutoScrollSpeed(speed: WebtoonAutoScrollSpeed, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(webtoonAutoScrollSpeed = speed) }
        prefs?.webtoonAutoScrollSpeed = speed
    }

    fun setScaleType(type: ScaleType, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(scaleType = type) }
        prefs?.scaleType = type
    }

    fun setColorFilter(filter: ReaderColorFilter, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(colorFilter = filter) }
        prefs?.saveColorFilter(filter)
    }

    fun setZoomState(zoom: ZoomState) {
        _state.update { it.copy(zoomState = zoom) }
    }

    fun setSkipReadChapters(skip: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(skipReadChapters = skip) }
        prefs?.skipReadChapters = skip
    }

    fun setSkipFilteredChapters(skip: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(skipFilteredChapters = skip) }
        prefs?.skipFilteredChapters = skip
    }

    fun setSkipDuplicateChapters(skip: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(skipDuplicateChapters = skip) }
        prefs?.skipDuplicateChapters = skip
    }

    suspend fun persistViewerFlags(mangaId: Long, flags: Long) {
        if (mangaId == 0L) return
        persistViewerFlags.invoke(mangaId, flags)
    }
}

private fun List<String>.toReaderPages(): List<ReaderPageModel> =
    mapIndexed { index, url -> ReaderPageModel(index = index, url = url, imageUrl = url.takeIf(String::isNotBlank)) }
