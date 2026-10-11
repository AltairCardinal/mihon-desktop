package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.isVisible
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import mihon.domain.reader.ReaderDualPageViewportTicket
import mihon.domain.reader.ReaderPortraitSingleSlot
import mihon.domain.reader.SharedDualPageSession
import mihon.domain.reader.session.ReaderPageId
import okio.Buffer
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import kotlin.math.min

/**
 * ViewPager page holder for [DualPageR2LPagerViewer].
 *
 * Handles both [DisplayPage.Single] (one image in a full or half viewport) and
 * [DisplayPage.Double] (two portrait images side-by-side, right image first in R2L order).
 *
 * Double pages have permanent physical half-screen slots. Each image fits its slot without
 * stretching, is vertically centred and aligns to the spine, independently of loading order.
 *
 * When both images are loaded the holder reports their dimensions back to the adapter so that
 * the pairing algorithm can reassign pages if necessary.
 *
 * Physical half slots share one viewport gesture owner. Each native image consumes the same
 * camera at its original Fit position, keeping native region decoding aware of the actual zoom.
 */
@SuppressLint("ViewConstructor")
class DualPagerPageHolder(
    private val readerThemedContext: Context,
    val viewer: DualPageR2LPagerViewer,
    displayPage: DisplayPage,
) : FrameLayout(readerThemedContext), ViewPagerAdapter.PositionableView {

    var displayPage: DisplayPage = displayPage
        private set

    override val item: Any get() = displayPage

    // ── Child views ─────────────────────────────────────────────────────────

    /** Container for the page image(s). */
    private val pageContainer = LinearLayout(readerThemedContext).also {
        it.orientation = LinearLayout.HORIZONTAL
        it.layoutDirection = View.LAYOUT_DIRECTION_LTR
        it.clipChildren = false
        it.clipToPadding = false
        addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private var leftSlot: PageSlot? = null
    private var rightSlot: PageSlot? = null

    private var rightHolder: SinglePageSubHolder? = null
    private var leftHolder: SinglePageSubHolder? = null

    // ── Progress / error ────────────────────────────────────────────────────

    private val progressIndicators = mutableMapOf<Side, ReaderProgressIndicator>()
    private val feedbackContainers = mutableMapOf<Side, FrameLayout>()
    private var errorLayout: ReaderErrorBinding? = null
    private var errorPage: ReaderPage? = null
    private val errorLayouts = mutableMapOf<Side, ReaderErrorBinding>()
    private val errorPages = mutableMapOf<Side, ReaderPage>()
    private val renderedPages = mutableMapOf<ReaderPage, String>()
    private val boundContents = mutableMapOf<ReaderPage, String>()
    private val boundEncodedContents = mutableMapOf<ReaderPage, String>()
    private val boundRetries = mutableMapOf<ReaderPage, Long>()
    private val visibleContentLeases = mutableMapOf<ReaderPage, String>()
    private val decodedImages = mutableMapOf<ReaderPage, BoundImage>()
    private val contentReloadJobs = mutableMapOf<ReaderPage, Job>()

    private data class BoundImage(
        val source: Buffer,
        val animated: Boolean,
        val config: ReaderPageImageView.Config,
        val callbackIsCurrent: () -> Boolean,
    )
    private val decodeGenerations = mutableMapOf<ReaderPage, Long>()
    private val presentationDiagnostics = DualPagePresentationDiagnostics()
    internal var presentationScope: Pair<SharedDualPageSession, ReaderDualPageViewportTicket>? =
        viewer.capturePresentation(displayPage)
        private set
    private var attachmentGeneration = 0L

    internal fun beginPresentation() {
        val next = viewer.capturePresentation(displayPage)
        if (presentationScope?.second === next?.second) return
        if (presentationScope?.first !== next?.first ||
            presentationScope?.second?.locationSequence != next?.second?.locationSequence ||
            presentationScope?.second?.frameId?.context != next?.second?.frameId?.context
        ) {
            renderedPages.clear()
        }
        presentationScope?.let(viewer::releasePresentation)
        presentationScope = next
        rightHolder?.invalidateImageDraw()
        leftHolder?.invalidateImageDraw()
    }

    internal fun onViewportMoved() {
        if (isAttachedToWindow && !getGlobalVisibleRect(Rect())) {
            presentationScope?.let(viewer::releasePresentation)
            presentationScope = null
            visibleContentLeases.clear()
            viewer.adapter.releaseContent(displayPage.firstPage.chapter)
            if (!isAttachedToWindow) return
            displayPage.pageSides().forEach { (page, side) ->
                if (boundContents[page] != null && boundContents[page] != page.contentIdentity() &&
                    page.status == Page.State.Ready && contentReloadJobs[page]?.isActive != true
                ) {
                    contentReloadJobs[page] = scope.launch { setImage(page, side) }
                }
            }
            return
        }
        listOfNotNull(rightHolder, leftHolder).forEach { image ->
            if ((presentationScope == null || renderedPages[image.page] != boundContents[image.page]) &&
                image.isShown && image.getGlobalVisibleRect(Rect())
            ) {
                image.invalidateImageDraw()
            }
        }
    }

    internal fun hasRenderedVisiblePages(): Boolean =
        displayPage.visiblePages.all {
            it.status == Page.State.Ready && renderedPages[it] != null && renderedPages[it] == boundContents[it]
        } && errorPage == null

    internal fun renderedPageIds(owner: SharedDualPageSession): Set<ReaderPageId> =
        displayPage.visiblePages.filter { page ->
            page.status == Page.State.Ready && renderedPages[page] != null &&
                renderedPages[page] == boundContents[page] &&
                owner.identity.content.getOrNull(page.index)?.contentVersion == boundEncodedContents[page]
        }.mapTo(mutableSetOf()) { owner.identity.pageIds[it.index] }

    private fun clearRendered(page: ReaderPage): Long {
        renderedPages.remove(page)
        val generation = (decodeGenerations[page] ?: 0L) + 1L
        decodeGenerations[page] = generation
        viewer.onHolderDisplayStateChanged(this)
        return generation
    }

    // ── Coroutines ──────────────────────────────────────────────────────────

    private val scope = MainScope()
    private val pageLoadJobs = mutableMapOf<ReaderPage, Job>()

    // ── Zoom / pan state (all physical half-slot frames) ────────────────────

    private var currentScale = 1f
    private val containerGestures = displayPage is DisplayPage.Double ||
        (displayPage as? DisplayPage.Single)?.slot in
        setOf(ReaderPortraitSingleSlot.LEFT, ReaderPortraitSingleSlot.RIGHT)
    private var translateX = 0f
    private var translateY = 0f
    private var viewportWidth = 0
    private var transformScheduled = false
    private var zoomAnimator: ValueAnimator? = null
    private var previousFocusX = 0f
    private var previousFocusY = 0f

    private val scaleGestureDetector = ScaleGestureDetector(
        readerThemedContext,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                zoomAnimator?.cancel()
                previousFocusX = detector.focusX
                previousFocusY = detector.focusY
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (currentScale * detector.scaleFactor).coerceIn(1f, 5f)
                val ratio = newScale / currentScale
                translateX = (translateX + width / 2f - previousFocusX) * ratio + detector.focusX - width / 2f
                translateY = (translateY + height / 2f - previousFocusY) * ratio + detector.focusY - height / 2f
                previousFocusX = detector.focusX
                previousFocusY = detector.focusY
                currentScale = newScale
                constrainTranslation()
                applyTransform()
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        readerThemedContext,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val targetScale = if (currentScale > 1f) 1f else 2f
                val targetX = if (targetScale == 1f) 0f else (width / 2f - e.x) * targetScale
                val targetY = if (targetScale == 1f) 0f else (height / 2f - e.y) * targetScale
                val startScale = currentScale
                val startX = translateX
                val startY = translateY
                zoomAnimator?.cancel()
                zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = viewer.config.doubleTapAnimDuration.toLong()
                    addUpdateListener {
                        val fraction = it.animatedValue as Float
                        currentScale = startScale + (targetScale - startScale) * fraction
                        translateX = startX + (targetX - startX) * fraction
                        translateY = startY + (targetY - startY) * fraction
                        constrainTranslation()
                        applyTransform()
                    }
                    start()
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (currentScale <= 1f || scaleGestureDetector.isInProgress) return false
                translateX -= distanceX
                translateY -= distanceY
                constrainTranslation()
                applyTransform()
                return true
            }
        },
    )

    init {
        if (containerGestures) {
            leftSlot = PageSlot(readerThemedContext, alignRight = true).also {
                pageContainer.addView(it, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            }
            rightSlot = PageSlot(readerThemedContext, alignRight = false).also {
                pageContainer.addView(it, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            }
        }
        loadPages()
    }

    override fun onDetachedFromWindow() {
        zoomAnimator?.cancel()
        super.onDetachedFromWindow()
        attachmentGeneration++
        presentationScope?.let(viewer::releasePresentation)
        presentationScope = null
        renderedPages.clear()
        visibleContentLeases.clear()
        decodedImages.clear()
        contentReloadJobs.values.forEach(Job::cancel)
        contentReloadJobs.clear()
        pageLoadJobs.values.forEach(Job::cancel)
        pageLoadJobs.clear()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        beginPresentation()
        loadPages()
        viewer.onHolderDisplayStateChanged(this)
    }

    // Receive gestures in the fixed viewport coordinates, never in the transformed image's
    // coordinates: otherwise each scale/translation feeds back into the next pointer event.
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        containerGestures && !hitsErrorAction(event)

    private fun hitsErrorAction(event: MotionEvent): Boolean {
        val bounds = Rect()
        return errorLayouts.values.filter { it.root.isVisible }
            .flatMap { listOf(it.actionRetry, it.actionOpenInWebView) }.any { button ->
                if (!button.isVisible) return@any false
                button.getDrawingRect(bounds)
                offsetDescendantRectToMyCoords(button, bounds)
                bounds.contains(event.x.toInt(), event.y.toInt())
            }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!containerGestures) return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) zoomAnimator?.cancel()
        if (event.pointerCount > 1 || currentScale > 1f) parent?.requestDisallowInterceptTouchEvent(true)
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        zoomAnimator?.cancel()
        constrainTranslation()
        applyTransform()
        feedbackContainers.forEach(::positionFeedback)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        viewportWidth = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    // ── Page loading ─────────────────────────────────────────────────────────

    private fun DisplayPage.pageSides(): Map<ReaderPage, Side> = when (this) {
        is DisplayPage.Double -> mapOf(rightPage to Side.RIGHT, leftPage to Side.LEFT)
        is DisplayPage.Single -> mapOf(
            page to when (slot) {
                ReaderPortraitSingleSlot.LEFT -> Side.LEFT
                ReaderPortraitSingleSlot.RIGHT -> Side.RIGHT
                ReaderPortraitSingleSlot.FULL -> Side.CENTER
            },
        )
    }

    private fun loadPages() {
        displayPage.pageSides().forEach { (page, side) ->
            if (page !in pageLoadJobs) pageLoadJobs[page] = scope.launch { loadSinglePage(page, side) }
        }
    }

    /** A candidate partner can leave or enter without replacing an unchanged physical image slot. */
    internal fun updateDisplayPage(replacement: DisplayPage): Boolean {
        if (displayPage == replacement) return true
        val previous = displayPage.pageSides()
        val next = replacement.pageSides()
        val common = previous.keys.intersect(next.keys)
        if (common.isEmpty() || common.any { previous[it] != next[it] } ||
            renderedPages.keys.any { it !in common } ||
            (Side.CENTER in previous.values) != (Side.CENTER in next.values)
        ) {
            return false
        }
        (previous.keys - next.keys).forEach { page ->
            pageLoadJobs.remove(page)?.cancel()
            contentReloadJobs.remove(page)?.cancel()
            decodedImages.remove(page)
            visibleContentLeases.remove(page)
            clearRendered(page)
            val side = requireNotNull(previous[page])
            val image = if (side == Side.LEFT) leftHolder else rightHolder
            image?.recycle()
            (image?.parent as? ViewGroup)?.removeView(image)
            if (side == Side.LEFT) leftHolder = null else rightHolder = null
            progressIndicators[side]?.hide()
            errorLayouts[side]?.root?.isVisible = false
            errorPages.remove(side)
        }
        displayPage = replacement
        presentationDiagnostics.retainSources(replacement.visiblePages.map { it.index })
        errorPage = errorPages.values.firstOrNull()
        loadPages()
        beginPresentation()
        return true
    }

    private suspend fun loadSinglePage(page: ReaderPage, side: Side) {
        val loader = page.chapter.pageLoader ?: return
        supervisorScope {
            launchIO { loader.loadPage(page) }
            launch {
                var observed = page.contentFact.value.version
                page.contentFact.collect { captured ->
                    if (observed != captured.version) {
                        observed = captured.version
                        if (retainsVisibleContent(page)) return@collect
                        clearRendered(page)
                        beginPresentation()
                        if (page.status == Page.State.Ready) setImage(page, side)
                    }
                    if (boundRetries[page] != null && boundRetries[page] != captured.retrySequence &&
                        page.status == Page.State.Ready
                    ) {
                        setImage(page, side)
                    }
                }
            }
            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue -> {
                        clearRendered(page)
                        setQueued(side)
                    }
                    Page.State.LoadPage -> {
                        clearRendered(page)
                        setLoading(side)
                    }
                    Page.State.DownloadImage -> {
                        clearRendered(page)
                        setDownloading(side)
                        page.progressFlow.collectLatest { value ->
                            progressIndicators[side]?.setProgress(value)
                        }
                    }
                    Page.State.Ready -> setImage(page, side)
                    is Page.State.Error -> setError(state.error, page, side)
                }
            }
        }
    }

    // ── Image display ────────────────────────────────────────────────────────

    private fun retainsVisibleContent(page: ReaderPage): Boolean =
        visibleContentLeases[page] != null && visibleContentLeases[page] == boundContents[page] &&
            boundRetries[page] == page.contentFact.value.retrySequence &&
            isShown && getGlobalVisibleRect(Rect())

    internal fun refreshImageProperties() {
        listOfNotNull(rightHolder, leftHolder).forEach { image ->
            val bound = decodedImages[image.page] ?: return@forEach
            val config = bound.config.copy(
                zoomDuration = viewer.config.doubleTapAnimDuration,
                cropBorders = viewer.config.imageCropBorders,
                zoomStartPosition = if (containerGestures) {
                    ReaderPageImageView.ZoomStartPosition.CENTER
                } else {
                    viewer.config.imageZoomType
                },
            )
            decodedImages[image.page] = bound.copy(config = config)
            renderedPages.remove(image.page)
            image.setImage(bound.source.clone(), bound.animated, config, image.page.index, bound.callbackIsCurrent)
        }
    }

    private suspend fun setImage(page: ReaderPage, side: Side) {
        if (retainsVisibleContent(page)) return
        val generation = clearRendered(page)
        val attachment = attachmentGeneration
        val encodedContent = page.encodedContentIdentity()
        withIOContext { page.publishHeaderFact() }
        if (attachment != attachmentGeneration || page.encodedContentIdentity() != encodedContent ||
            decodeGenerations[page] != generation
        ) {
            return
        }
        beginPresentation()
        val content = page.contentIdentity()
        val orientation = page.contentFact.value.fact.sourceOrientation
        val dimensionsRequest = viewer.adapter.captureDimensions(page)
        val streamFn = page.stream ?: return
        try {
            val (source, isAnimated, background) = withIOContext {
                val source = streamFn().use { Buffer().readFrom(it) }
                val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                val background = if (!isAnimated && viewer.config.automaticBackground) {
                    ImageUtil.chooseBackground(readerThemedContext, source.peek().inputStream())
                } else {
                    null
                }
                Triple(source, isAnimated, background)
            }
            withUIContext {
                if (attachment != attachmentGeneration) return@withUIContext
                // Decode dimensions before displaying
                val (rawWidth, rawHeight) = withIOContext {
                    ImageUtil.getImageDimensions(source.peek())
                }
                val (w, h) = orientation.orientedSize(rawWidth, rawHeight)
                if (attachment != attachmentGeneration || page.contentIdentity() != content) return@withUIContext
                viewer.adapter.acceptDecodedDimensions(page, dimensionsRequest, w, h, orientation)
                if (page !in displayPage.visiblePages || decodeGenerations[page] != generation) return@withUIContext
                onDimensionsDecoded(side, w, h)

                val config = ReaderPageImageView.Config(
                    zoomDuration = viewer.config.doubleTapAnimDuration,
                    minimumScaleType = com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
                        .SCALE_TYPE_CENTER_INSIDE,
                    cropBorders = viewer.config.imageCropBorders,
                    zoomStartPosition = if (containerGestures) {
                        ReaderPageImageView.ZoomStartPosition.CENTER
                    } else {
                        viewer.config.imageZoomType
                    },
                    landscapeZoom = false, // handled by dual-page container zoom
                    sourceOrientation = orientation,
                    pageSideHintEnabled = viewer.config.automaticSpreadMatching && !viewer.config.imageCropBorders,
                )

                // Ready encoded bytes may reach this collector without a visible Queue state.
                // Native base decoding still belongs to this slot's loading feedback.
                setLoading(side)
                val holder = getOrCreateSubHolder(side, page)
                boundContents[page] = content
                boundEncodedContents[page] = encodedContent
                boundRetries[page] = page.contentFact.value.retrySequence
                val callbackIsCurrent = {
                    attachment == attachmentGeneration && boundContents[page] == content &&
                        boundEncodedContents[page] == encodedContent && decodeGenerations[page] == generation &&
                        page.status == Page.State.Ready &&
                        (page.contentIdentity() == content || retainsVisibleContent(page))
                }
                holder.onImageLoaded = {
                    if (callbackIsCurrent()) {
                        if (errorPages[side] === page) {
                            errorLayouts[side]?.root?.isVisible = false
                            errorPages.remove(side)
                            if (errorPage === page) errorPage = errorPages.values.firstOrNull()
                        }
                        holder.invalidate()
                    }
                }
                holder.beforeImageDraw = { drawWidth, drawHeight ->
                    if (!callbackIsCurrent()) {
                        false
                    } else {
                        if (presentationScope == null) beginPresentation()
                        presentationScope?.let { (owner, ticket) ->
                            val pageId = owner.identity.pageIds.getOrNull(page.index)
                            val physicalSlot = when (side) {
                                Side.RIGHT -> ReaderPortraitSingleSlot.RIGHT
                                Side.LEFT -> ReaderPortraitSingleSlot.LEFT
                                Side.CENTER -> (displayPage as? DisplayPage.Single)?.slot
                                    ?: ReaderPortraitSingleSlot.FULL
                            }
                            val beforeFrame = owner.frame(ticket)
                            if (pageId == null || beforeFrame == null ||
                                owner.identity.content[page.index].contentVersion != encodedContent ||
                                beforeFrame.unit.slots.none { it.pageId == pageId && it.position == physicalSlot }
                            ) {
                                presentationDiagnostics.record(
                                    "prepare", owner, ticket, page.index, holder, config,
                                    viewer.config.automaticSpreadMatching, isAnimated, physicalSlot, beforeFrame,
                                    lockedBefore = pageId in beforeFrame?.lockedPageIds.orEmpty(),
                                    prepared = false,
                                    outcome = "NATIVE_CAPABILITY_REJECTED",
                                )
                                false
                            } else if (pageId in beforeFrame.lockedPageIds) {
                                // Keep an actually exposed outgoing image's original physical capability.
                                presentationDiagnostics.record(
                                    "retained_lock", owner, ticket, page.index, holder, config,
                                    viewer.config.automaticSpreadMatching, isAnimated, physicalSlot, beforeFrame,
                                    lockedBefore = true,
                                    prepared = true,
                                    outcome = "ALREADY_LOCKED",
                                )
                                true
                            } else {
                                val beforeRevision = owner.layoutRevision
                                val submittedHint = if (viewer.config.automaticSpreadMatching) {
                                    holder.decodedPageSideHint
                                } else {
                                    mihon.domain.reader.ReaderPageSideHint.UNKNOWN
                                }
                                val accepted = owner.acceptDecodedFact(
                                    ticket,
                                    pageId,
                                    drawWidth,
                                    drawHeight,
                                    submittedHint,
                                )
                                val factFrame = owner.frame(ticket)
                                presentationDiagnostics.record(
                                    "decoded_fact", owner, ticket, page.index, holder, config,
                                    viewer.config.automaticSpreadMatching, isAnimated, physicalSlot, factFrame,
                                    lockedBefore = false,
                                    beforeFrame = beforeFrame,
                                    submittedHint = submittedHint,
                                    accepted = accepted,
                                    outcome = if (accepted) "ACCEPTED" else "FACT_REJECTED",
                                )
                                if (owner.layoutRevision !=
                                    beforeRevision
                                ) {
                                    viewer.adapter.refreshDecodedSide(page, owner)
                                }
                                val frame = owner.frame(ticket)
                                val prepared = accepted && frame != null &&
                                    frame.unit.slots.any { it.pageId == pageId && it.position == physicalSlot } &&
                                    owner.preparePage(ticket, pageId)
                                val outcome = when {
                                    !accepted -> "FACT_REJECTED"
                                    frame == null -> "FRAME_EXPIRED"
                                    frame.unit.slots.none { it.pageId == pageId && it.position == physicalSlot } ->
                                        "SLOT_CHANGED"
                                    !prepared -> "LOCK_REJECTED"
                                    else -> "LOCKED"
                                }
                                presentationDiagnostics.record(
                                    "prepare", owner, ticket, page.index, holder, config,
                                    viewer.config.automaticSpreadMatching, isAnimated, physicalSlot,
                                    owner.frame(ticket) ?: factFrame,
                                    lockedBefore = false,
                                    beforeFrame = beforeFrame,
                                    submittedHint = submittedHint,
                                    accepted = accepted,
                                    prepared = prepared,
                                    outcome = outcome,
                                )
                                prepared
                            }
                        } == true
                    }
                }
                holder.onImageDrawn = {
                    if (callbackIsCurrent()) {
                        presentationScope?.let { (owner, ticket) ->
                            val pageId = owner.identity.pageIds.getOrNull(page.index)
                            if (pageId != null && owner.reportDrawn(ticket, setOf(pageId)) &&
                                renderedPages[page] != content
                            ) {
                                renderedPages[page] = content
                                visibleContentLeases[page] = content
                                removeProgressIndicator(side)
                                post { viewer.onHolderDisplayStateChanged(this@DualPagerPageHolder) }
                            }
                        }
                    }
                }
                holder.onImageLoadError = { error ->
                    if (callbackIsCurrent()) {
                        setError(error, page, side)
                    }
                }
                decodedImages[page] = BoundImage(source.clone(), isAnimated, config, callbackIsCurrent)
                slot(side)?.applyTransform()
                holder.setImage(source, isAnimated, config, page.index, callbackIsCurrent = callbackIsCurrent)
                if (!isAnimated) holder.pageBackground = background
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext {
                if (attachment == attachmentGeneration && page.contentIdentity() == content &&
                    decodeGenerations[page] == generation
                ) {
                    setError(e, page, side)
                }
            }
        }
    }

    // ── Dimension-driven layout ──────────────────────────────────────────────

    /**
     * Called when a page's dimensions are decoded.
     * Reports back to the adapter so it can rebuild pairings if needed.
     * Slot measurement owns geometry; this notification cannot resize its neighbour.
     */
    private fun onDimensionsDecoded(side: Side, width: Int, height: Int) {
        if (side !in displayPage.pageSides().values) return
        slot(side)?.setImageDimensions(width, height)
    }

    private fun constrainTranslation() {
        val maxX = width * (currentScale - 1f) / 2f
        val maxY = height * (currentScale - 1f) / 2f
        translateX = translateX.coerceIn(-maxX, maxX)
        translateY = translateY.coerceIn(-maxY, maxY)
    }

    private fun applyTransform() {
        if (!containerGestures || transformScheduled) return
        transformScheduled = true
        // Several motion samples may arrive in one display frame. Submit only the latest native
        // pose, so SSIV never queues intermediate tile requests that cannot become visible.
        postOnAnimation {
            transformScheduled = false
            leftSlot?.applyTransform()
            rightSlot?.applyTransform()
        }
    }

    // ── Sub-holder management ────────────────────────────────────────────────

    private fun getOrCreateSubHolder(side: Side, page: ReaderPage): SinglePageSubHolder {
        return when (side) {
            Side.RIGHT, Side.CENTER -> {
                rightHolder ?: SinglePageSubHolder(readerThemedContext, page).also { holder ->
                    rightHolder = holder
                    val target = slot(side)
                    if (target != null) {
                        target.addView(holder)
                    } else {
                        pageContainer.addView(
                            holder,
                            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
                        )
                    }
                }
            }
            Side.LEFT -> {
                leftHolder ?: SinglePageSubHolder(readerThemedContext, page).also { holder ->
                    leftHolder = holder
                    leftSlot!!.addView(holder)
                }
            }
        }
    }

    // ── Progress / error helpers ─────────────────────────────────────────────

    private fun slot(side: Side): PageSlot? = when (side) {
        Side.RIGHT -> rightSlot
        Side.LEFT -> leftSlot
        Side.CENTER -> when ((displayPage as? DisplayPage.Single)?.slot) {
            ReaderPortraitSingleSlot.LEFT -> leftSlot
            ReaderPortraitSingleSlot.RIGHT -> rightSlot
            else -> null
        }
    }

    private fun removeProgressIndicator(side: Side) {
        progressIndicators[side]?.hide()
    }

    private fun feedbackContainer(side: Side): FrameLayout = feedbackContainers.getOrPut(side) {
        FrameLayout(readerThemedContext).also {
            addView(it)
            positionFeedback(side, it)
        }
    }

    private fun positionFeedback(side: Side, container: FrameLayout) {
        val physicalSlot = slot(side)
        val gravity = when {
            physicalSlot != null && physicalSlot === leftSlot -> Gravity.LEFT
            physicalSlot != null && physicalSlot === rightSlot -> Gravity.RIGHT
            else -> Gravity.CENTER
        }
        container.layoutParams = LayoutParams(
            if (physicalSlot == null) LayoutParams.MATCH_PARENT else width / 2,
            LayoutParams.MATCH_PARENT,
            gravity,
        )
    }

    private fun setQueued(side: Side) = withProgress(side) { show() }
    private fun setLoading(side: Side) = withProgress(side) { show() }
    private fun setDownloading(side: Side) = withProgress(side) { show() }

    private fun withProgress(side: Side, block: ReaderProgressIndicator.() -> Unit) {
        progressIndicators.getOrPut(side) {
            ReaderProgressIndicator(readerThemedContext).also { feedbackContainer(side).addView(it) }
        }.block()
    }

    private fun setError(error: Throwable?, page: ReaderPage, side: Side) {
        clearRendered(page)
        removeProgressIndicator(side)
        val binding = errorLayouts.getOrPut(side) {
            ReaderErrorBinding.inflate(LayoutInflater.from(readerThemedContext), feedbackContainer(side), true).also {
                it.actionRetry.viewer = viewer
            }
        }
        errorLayouts[side] = binding
        errorPages[side] = page
        errorLayout = binding
        errorPage = page
        binding.actionRetry.setOnClickListener {
            page.chapter.pageLoader?.retryPage(page)
        }
        val imageUrl = page.imageUrl
        binding.actionOpenInWebView.isVisible = imageUrl != null
        if (imageUrl != null && imageUrl.startsWith("http", true)) {
            binding.actionOpenInWebView.viewer = viewer
            binding.actionOpenInWebView.setOnClickListener {
                val sourceId = viewer.activity.viewModel.manga?.source
                val intent = WebViewActivity.newIntent(readerThemedContext, imageUrl, sourceId)
                readerThemedContext.startActivity(intent)
            }
        }
        binding.errorMessage.text = with(readerThemedContext) { error?.formattedMessage }
            ?: readerThemedContext.stringResource(MR.strings.decode_image_error)
        binding.root.isVisible = true
    }

    // ── Internal types ───────────────────────────────────────────────────────

    private enum class Side { RIGHT, LEFT, CENTER }

    /** A stable physical half of the viewport; image availability never controls its size. */
    private inner class PageSlot(context: Context, private val alignRight: Boolean) : ViewGroup(context) {
        private var imageWidth = 0
        private var imageHeight = 0

        init {
            clipChildren = false
            clipToPadding = false
        }

        fun setImageDimensions(width: Int, height: Int) {
            imageWidth = width
            imageHeight = height
            requestLayout()
        }

        override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams =
            ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child is ReaderPageImageView) {
                    child.measure(
                        MeasureSpec.makeMeasureSpec(viewportWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
                    )
                } else {
                    child.measure(
                        MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST),
                    )
                }
            }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                val x = if (child is ReaderPageImageView) {
                    -left
                } else {
                    (width - child.measuredWidth) / 2
                }
                val y = (height - child.measuredHeight) / 2
                child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            }
            applyTransform()
        }

        fun applyTransform() {
            if (imageWidth <= 0 || imageHeight <= 0 || width <= 0 || height <= 0) return
            val fitScale = min(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
            val fitWidth = (imageWidth * fitScale).toInt()
            val fitHeight = (imageHeight * fitScale).toInt()
            val x = left + if (alignRight) width - fitWidth else 0
            val y = (height - fitHeight) / 2
            val fit = RectF(x.toFloat(), y.toFloat(), (x + fitWidth).toFloat(), (y + fitHeight).toFloat())
            for (index in 0 until childCount) {
                (getChildAt(index) as? ReaderPageImageView)?.setViewportTransform(
                    fit,
                    currentScale,
                    translateX,
                    translateY,
                    imageWidth,
                    imageHeight,
                )
            }
        }
    }

    /**
     * A thin wrapper around [ReaderPageImageView] that stores the page reference
     * and exposes a convenience [setImage] forwarding method.
     */
    private inner class SinglePageSubHolder(
        context: Context,
        val page: ReaderPage,
    ) : ReaderPageImageView(context)
}
