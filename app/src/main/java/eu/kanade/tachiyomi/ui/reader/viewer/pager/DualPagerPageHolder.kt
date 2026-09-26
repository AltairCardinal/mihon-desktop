package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.GestureDetector
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
import mihon.domain.reader.ReaderPortraitSingleSlot
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
 * Zooming/panning in double mode is handled by a container-level gesture detector that applies
 * a shared transform to both child views.
 */
@SuppressLint("ViewConstructor")
class DualPagerPageHolder(
    private val readerThemedContext: Context,
    val viewer: DualPageR2LPagerViewer,
    val displayPage: DisplayPage,
) : FrameLayout(readerThemedContext), ViewPagerAdapter.PositionableView {

    override val item: Any get() = displayPage

    // ── Child views ─────────────────────────────────────────────────────────

    /** Container for the page image(s). */
    private val pageContainer = LinearLayout(readerThemedContext).also {
        it.orientation = LinearLayout.HORIZONTAL
        it.layoutDirection = View.LAYOUT_DIRECTION_LTR
        addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private var leftSlot: PageSlot? = null
    private var rightSlot: PageSlot? = null

    private var rightHolder: SinglePageSubHolder? = null
    private var leftHolder: SinglePageSubHolder? = null

    // ── Progress / error ────────────────────────────────────────────────────

    private var progressIndicator: ReaderProgressIndicator? = null
    private var errorLayout: ReaderErrorBinding? = null
    private var errorPage: ReaderPage? = null
    private val renderedPages = mutableSetOf<ReaderPage>()
    private val decodeGenerations = mutableMapOf<ReaderPage, Long>()

    internal fun hasRenderedVisiblePages(): Boolean =
        displayPage.visiblePages.all { it.status == Page.State.Ready && it in renderedPages } && errorPage == null

    private fun clearRendered(page: ReaderPage): Long {
        renderedPages.remove(page)
        val generation = (decodeGenerations[page] ?: 0L) + 1L
        decodeGenerations[page] = generation
        viewer.onHolderDisplayStateChanged(this)
        return generation
    }

    // ── Coroutines ──────────────────────────────────────────────────────────

    private val scope = MainScope()
    private var loadJob: Job? = null

    // ── Zoom / pan state (double-page mode only) ────────────────────────────

    private var currentScale = 1f
    private var translateX = 0f
    private var translateY = 0f

    private val scaleGestureDetector = ScaleGestureDetector(
        readerThemedContext,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (currentScale * detector.scaleFactor).coerceIn(1f, 5f)
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
                currentScale = if (currentScale > 1f) 1f else 2f
                translateX = 0f
                translateY = 0f
                applyTransform()
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
        if (displayPage is DisplayPage.Double) {
            leftSlot = PageSlot(readerThemedContext, alignRight = true).also {
                pageContainer.addView(it, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            }
            rightSlot = PageSlot(readerThemedContext, alignRight = false).also {
                pageContainer.addView(it, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            }
        }
        when ((displayPage as? DisplayPage.Single)?.slot) {
            ReaderPortraitSingleSlot.LEFT -> {
                leftSlot = PageSlot(readerThemedContext, alignRight = true).also {
                    pageContainer.addView(it, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
                }
                pageContainer.addView(
                    View(readerThemedContext),
                    LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
                )
            }
            ReaderPortraitSingleSlot.RIGHT -> {
                pageContainer.addView(
                    View(readerThemedContext),
                    LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
                )
                rightSlot = PageSlot(readerThemedContext, alignRight = false).also {
                    pageContainer.addView(it, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
                }
            }
            ReaderPortraitSingleSlot.FULL, null -> Unit
        }
        loadJob = scope.launch { loadPages() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadJob?.cancel()
        loadJob = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewer.onHolderDisplayStateChanged(this)
    }

    // Receive gestures in the fixed viewport coordinates, never in the transformed image's
    // coordinates: otherwise each scale/translation feeds back into the next pointer event.
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        displayPage is DisplayPage.Double && !hitsErrorAction(event)

    private fun hitsErrorAction(event: MotionEvent): Boolean {
        val error = errorLayout?.takeIf { it.root.isVisible } ?: return false
        val bounds = Rect()
        return listOf(error.actionRetry, error.actionOpenInWebView).any { button ->
            if (!button.isVisible) return@any false
            button.getDrawingRect(bounds)
            offsetDescendantRectToMyCoords(button, bounds)
            bounds.contains(event.x.toInt(), event.y.toInt())
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (displayPage !is DisplayPage.Double) return super.onTouchEvent(event)
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
        constrainTranslation()
        applyTransform()
    }

    // ── Page loading ─────────────────────────────────────────────────────────

    private suspend fun loadPages() {
        when (displayPage) {
            is DisplayPage.Single -> loadSinglePage(displayPage.page, side = Side.CENTER)
            is DisplayPage.Double -> {
                supervisorScope {
                    launch { loadSinglePage(displayPage.rightPage, side = Side.RIGHT) }
                    launch { loadSinglePage(displayPage.leftPage, side = Side.LEFT) }
                }
            }
        }
    }

    private suspend fun loadSinglePage(page: ReaderPage, side: Side) {
        val loader = page.chapter.pageLoader ?: return
        supervisorScope {
            launchIO { loader.loadPage(page) }
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
                            progressIndicator?.setProgress(value)
                        }
                    }
                    Page.State.Ready -> setImage(page, side)
                    is Page.State.Error -> setError(state.error, page, side)
                }
            }
        }
    }

    // ── Image display ────────────────────────────────────────────────────────

    private suspend fun setImage(page: ReaderPage, side: Side) {
        val generation = clearRendered(page)
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
                // Decode dimensions before displaying
                val (w, h) = withIOContext {
                    ImageUtil.getImageDimensions(source.peek())
                }
                onDimensionsDecoded(side, w, h)

                val config = ReaderPageImageView.Config(
                    zoomDuration = viewer.config.doubleTapAnimDuration,
                    minimumScaleType = com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
                        .SCALE_TYPE_CENTER_INSIDE,
                    cropBorders = viewer.config.imageCropBorders,
                    zoomStartPosition = if (displayPage is DisplayPage.Double) {
                        ReaderPageImageView.ZoomStartPosition.CENTER
                    } else {
                        viewer.config.imageZoomType
                    },
                    landscapeZoom = false, // handled by dual-page container zoom
                )

                val holder = getOrCreateSubHolder(side, page)
                holder.onImageLoaded = {
                    if (decodeGenerations[page] == generation && page.status == Page.State.Ready) {
                        if (errorPage === page) {
                            errorLayout?.root?.isVisible = false
                            errorPage = null
                        }
                        renderedPages.add(page)
                        viewer.onHolderDisplayStateChanged(this@DualPagerPageHolder)
                    }
                }
                holder.onImageLoadError = { error ->
                    if (decodeGenerations[page] == generation) setError(error, page, side)
                }
                holder.setImage(source, isAnimated, config, page.index)
                if (!isAnimated) holder.pageBackground = background
                removeProgressIndicator()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext { setError(e, page, side) }
        }
    }

    // ── Dimension-driven layout ──────────────────────────────────────────────

    /**
     * Called when a page's dimensions are decoded.
     * Reports back to the adapter so it can rebuild pairings if needed.
     * Slot measurement owns geometry; this notification cannot resize its neighbour.
     */
    private fun onDimensionsDecoded(side: Side, width: Int, height: Int) {
        val page = when (side) {
            Side.RIGHT -> (displayPage as? DisplayPage.Double)?.rightPage
                ?: (displayPage as? DisplayPage.Single)?.page
            Side.LEFT -> (displayPage as? DisplayPage.Double)?.leftPage
            Side.CENTER -> (displayPage as? DisplayPage.Single)?.page
        } ?: return

        viewer.adapter.updatePageDimensions(page, width, height)

        val slot = when (side) {
            Side.LEFT -> leftSlot
            Side.RIGHT -> rightSlot
            Side.CENTER -> when ((displayPage as DisplayPage.Single).slot) {
                ReaderPortraitSingleSlot.LEFT -> leftSlot
                ReaderPortraitSingleSlot.RIGHT -> rightSlot
                ReaderPortraitSingleSlot.FULL -> null
            }
        }
        slot?.setImageDimensions(width, height)
    }

    private fun constrainTranslation() {
        val maxX = width * (currentScale - 1f) / 2f
        val maxY = height * (currentScale - 1f) / 2f
        translateX = translateX.coerceIn(-maxX, maxX)
        translateY = translateY.coerceIn(-maxY, maxY)
    }

    private fun applyTransform() {
        pageContainer.pivotX = width / 2f
        pageContainer.pivotY = height / 2f
        pageContainer.scaleX = currentScale
        pageContainer.scaleY = currentScale
        pageContainer.translationX = translateX
        pageContainer.translationY = translateY
    }

    // ── Sub-holder management ────────────────────────────────────────────────

    private fun getOrCreateSubHolder(side: Side, page: ReaderPage): SinglePageSubHolder {
        return when (side) {
            Side.RIGHT, Side.CENTER -> {
                rightHolder ?: SinglePageSubHolder(readerThemedContext, page).also { holder ->
                    rightHolder = holder
                    when (displayPage) {
                        is DisplayPage.Double -> {
                            rightSlot!!.addView(holder)
                        }
                        is DisplayPage.Single -> {
                            when (displayPage.slot) {
                                ReaderPortraitSingleSlot.LEFT -> leftSlot!!.addView(holder)
                                ReaderPortraitSingleSlot.RIGHT -> rightSlot!!.addView(holder)
                                ReaderPortraitSingleSlot.FULL -> {
                                    pageContainer.addView(
                                        holder,
                                        LinearLayout.LayoutParams(
                                            LayoutParams.MATCH_PARENT,
                                            LayoutParams.MATCH_PARENT,
                                        ),
                                    )
                                }
                            }
                        }
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

    private fun initProgressIndicator() {
        if (progressIndicator == null) {
            progressIndicator = ReaderProgressIndicator(readerThemedContext)
            addView(progressIndicator)
        }
    }

    private fun removeProgressIndicator() {
        progressIndicator?.hide()
    }

    private fun setQueued(@Suppress("UNUSED_PARAMETER") side: Side) = withProgress { show() }
    private fun setLoading(@Suppress("UNUSED_PARAMETER") side: Side) = withProgress { show() }
    private fun setDownloading(@Suppress("UNUSED_PARAMETER") side: Side) = withProgress { show() }

    private fun withProgress(block: ReaderProgressIndicator.() -> Unit) {
        initProgressIndicator()
        progressIndicator?.block()
    }

    private fun setError(error: Throwable?, page: ReaderPage, @Suppress("UNUSED_PARAMETER") side: Side) {
        clearRendered(page)
        removeProgressIndicator()
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(readerThemedContext), this, true)
            errorLayout?.actionRetry?.viewer = viewer
        }
        errorPage = page
        errorLayout?.actionRetry?.setOnClickListener {
            page.chapter.pageLoader?.retryPage(page)
        }
        val imageUrl = page.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null && imageUrl.startsWith("http", true)) {
            errorLayout?.actionOpenInWebView?.viewer = viewer
            errorLayout?.actionOpenInWebView?.setOnClickListener {
                val sourceId = viewer.activity.viewModel.manga?.source
                val intent = WebViewActivity.newIntent(readerThemedContext, imageUrl, sourceId)
                readerThemedContext.startActivity(intent)
            }
        }
        errorLayout?.errorMessage?.text = with(readerThemedContext) { error?.formattedMessage }
            ?: readerThemedContext.stringResource(MR.strings.decode_image_error)
        errorLayout?.root?.isVisible = true
    }

    // ── Internal types ───────────────────────────────────────────────────────

    private enum class Side { RIGHT, LEFT, CENTER }

    /** A stable physical half of the viewport; image availability never controls its size. */
    private class PageSlot(context: Context, private val alignRight: Boolean) : ViewGroup(context) {
        private var imageWidth = 0
        private var imageHeight = 0

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
            if (childCount == 0) return
            val scale = if (imageWidth > 0 && imageHeight > 0) {
                min(w.toFloat() / imageWidth, h.toFloat() / imageHeight)
            } else {
                0f
            }
            getChildAt(0).measure(
                MeasureSpec.makeMeasureSpec((imageWidth * scale).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((imageHeight * scale).toInt(), MeasureSpec.EXACTLY),
            )
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            if (childCount == 0) return
            val child = getChildAt(0)
            val x = if (alignRight) width - child.measuredWidth else 0
            val y = (height - child.measuredHeight) / 2
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
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
