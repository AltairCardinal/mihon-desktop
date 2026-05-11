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
import mihon.desktop.reader.readingModeFromViewerFlags

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
    private val isWebtoon: Boolean = false,
    val sourceId: Long = 0L,
    val chapterUrl: String = "",
    val mangaViewerFlags: Long = 0L,
    prefs: ReaderPreferences = ReaderPreferences(),
) : ScreenModel {

    private val _state = MutableStateFlow(buildInitialState(prefs))
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private fun buildInitialState(prefs: ReaderPreferences): ReaderState {
        val resolvedMode = when {
            isWebtoon -> ReadingMode.WEBTOON
            else -> readingModeFromViewerFlags(mangaViewerFlags) ?: prefs.readingMode
        }
        return ReaderState(
            currentPage = initialPage.coerceAtLeast(0),
            resolvedUrls = pageUrls,
            isLoadingPages = pageUrls.isEmpty() && sourceId != 0L && chapterUrl.isNotBlank(),
            readingMode = resolvedMode,
            dualPageMode = prefs.isDualPage,
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
            )
        }
    }

    fun appendLoadedPage(index: Int, url: String) {
        _state.update { s ->
            val newUrls = s.resolvedUrls.toMutableList()
            while (newUrls.size <= index) newUrls.add("")
            newUrls[index] = url
            val wasLoading = s.isLoadingPages
            val firstArrived = wasLoading && url.isNotEmpty()
            s.copy(
                resolvedUrls = newUrls,
                isLoadingPages = if (firstArrived) false else s.isLoadingPages,
                currentPage = if (firstArrived) {
                    s.currentPage.coerceIn(0, (newUrls.size - 1).coerceAtLeast(0))
                } else {
                    s.currentPage
                },
            )
        }
    }

    fun setLoadError(message: String) {
        _state.update { it.copy(isLoadingPages = false, errorMessage = message) }
    }

    fun setLoadingDone() {
        _state.update { it.copy(isLoadingPages = false) }
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
}
