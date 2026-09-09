package mihon.desktop.ui.reader

import mihon.desktop.reader.ReaderBackgroundTheme
import mihon.desktop.reader.DesktopReaderChapterContext
import mihon.desktop.reader.ReaderColorFilter
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.ScaleType
import mihon.desktop.reader.VirtualPage
import mihon.desktop.reader.WebtoonSidePadding
import mihon.desktop.reader.ZoomState
import mihon.desktop.ui.reader.presentation.DisplayUnitId
import mihon.desktop.ui.reader.presentation.WebtoonScrollAnchor
import mihon.domain.reader.ReaderChapterTransitionModel
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderSessionSnapshot

/**
 * All reader UI and settings state, owned by [ReaderScreenModel].
 *
 * This is a pure data class with no Compose dependencies — it can be
 * constructed and asserted in plain JVM unit tests.
 */
data class ReaderState(
    // ── Page data ────────────────────────────────────────────────────────────
    val context: DesktopReaderChapterContext = DesktopReaderChapterContext(
        chapterId = 0L,
        sourceId = 0L,
        chapterUrl = "",
        mangaTitle = "",
        chapterTitle = "",
        chapterNumber = 0.0,
        chapterIndex = 0,
        initialPage = 0,
        wasRead = false,
    ),
    val session: ReaderSessionSnapshot = ReaderSessionSnapshot.initial(ReaderChapterId(context.chapterId)),
    val currentPage: Int = 0,
    val currentDisplayUnitId: DisplayUnitId? = null,
    val visiblePageIds: Set<ReaderPageId> = emptySet(),
    val webtoonScrollAnchor: WebtoonScrollAnchor? = null,
    val chapterTransition: ReaderChapterTransitionModel? = null,

    // ── Reading mode ─────────────────────────────────────────────────────────
    val readingMode: ReadingMode = ReadingMode.LTR,
    val dualPageMode: Boolean = false,
    val autoSplitPages: Boolean = false,
    val autoSpreadMatching: Boolean = false,

    // ── Spread / virtual page mappings ────────────────────────────────────────
    val forcedSinglePages: Set<Int> = emptySet(),
    val spreadPages: Set<Int> = emptySet(),
    /** Edge-pixel matched pairs for auto spread matching in dual-page mode. */
    val matchedPairs: Set<Pair<Int, Int>> = emptySet(),
    val virtualPages: List<VirtualPage>? = null,

    // ── Display settings ─────────────────────────────────────────────────────
    val backgroundTheme: ReaderBackgroundTheme = ReaderBackgroundTheme.DEFAULT,
    val navigationMode: NavigationMode = NavigationMode.RightAndLeft,
    val cropBordersPager: Boolean = false,
    val cropBordersWebtoon: Boolean = false,
    val webtoonSidePadding: WebtoonSidePadding = WebtoonSidePadding.DEFAULT,
    val webtoonAutoScroll: Boolean = false,
    val webtoonAutoScrollSpeed: WebtoonAutoScrollSpeed = WebtoonAutoScrollSpeed.Normal,
    val scaleType: ScaleType = ScaleType.DEFAULT,
    val colorFilter: ReaderColorFilter = ReaderColorFilter(),
    val zoomState: ZoomState = ZoomState(),
    val skipReadChapters: Boolean = false,
    val skipFilteredChapters: Boolean = false,
    val skipDuplicateChapters: Boolean = false,

    // ── UI overlay state ─────────────────────────────────────────────────────
    val showSettings: Boolean = false,
    val showUI: Boolean = false,
)
