package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
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
 * Handles both [DisplayPage.Single] (one image, centred) and
 * [DisplayPage.Double] (two portrait images side-by-side, right image first in R2L order).
 *
 * For double pages the holder uses a horizontal [LinearLayout] with the right page on the right
 * side and the left page on the left side.  Both images are scaled so their combined width fits
 * within the screen while their shared height fills as much of the screen as possible
 * (CENTER_INSIDE of the combined bounding box).
 *
 * When both images are loaded the holder reports their dimensions back to the adapter so that
 * the pairing algorithm can reassign pages if necessary.
 *
 * Zooming/panning in double mode is handled by a container-level gesture detector that applies
 * a shared [Matrix] transform to both child views, giving the illusion of a single zoomable image.
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
        addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private var rightHolder: SinglePageSubHolder? = null
    private var leftHolder: SinglePageSubHolder? = null

    // ── Progress / error ────────────────────────────────────────────────────

    private var progressIndicator: ReaderProgressIndicator? = null
    private var errorLayout: ReaderErrorBinding? = null

    // ── Coroutines ──────────────────────────────────────────────────────────

    private val scope = MainScope()
    private var loadJob: Job? = null

    // ── Dimension state ─────────────────────────────────────────────────────

    private var rightDims: Pair<Int, Int>? = null // width × height
    private var leftDims: Pair<Int, Int>? = null

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
                applyTransform()
                return true
            }
        },
    )

    init {
        loadJob = scope.launch { loadPages() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadJob?.cancel()
        loadJob = null
    }

    // ── Touch handling for coordinated zoom ─────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (displayPage is DisplayPage.Double) {
            scaleGestureDetector.onTouchEvent(event)
        }
        return super.onTouchEvent(event)
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
                    Page.State.Queue -> setQueued(side)
                    Page.State.LoadPage -> setLoading(side)
                    Page.State.DownloadImage -> {
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
                    zoomStartPosition = viewer.config.imageZoomType,
                    landscapeZoom = false, // handled by dual-page container zoom
                )

                val holder = getOrCreateSubHolder(side, page)
                holder.setImage(source, isAnimated, config, page.index)
                if (!isAnimated) holder.pageBackground = background
                removeProgressIndicator()
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext { setError(e, page, side) }
        }
    }

    // ── Dimension-driven layout ──────────────────────────────────────────────

    /**
     * Called when a page's dimensions are decoded.
     * Reports back to the adapter so it can rebuild pairings if needed.
     * Also triggers a layout update for the double-page combined view.
     */
    private fun onDimensionsDecoded(side: Side, width: Int, height: Int) {
        val page = when (side) {
            Side.RIGHT -> (displayPage as? DisplayPage.Double)?.rightPage
                ?: (displayPage as? DisplayPage.Single)?.page
            Side.LEFT -> (displayPage as? DisplayPage.Double)?.leftPage
            Side.CENTER -> (displayPage as? DisplayPage.Single)?.page
        } ?: return

        viewer.adapter.updatePageDimensions(page.index, width, height)

        when (side) {
            Side.RIGHT, Side.CENTER -> rightDims = width to height
            Side.LEFT -> leftDims = width to height
        }

        if (displayPage is DisplayPage.Double) {
            layoutDoublePage()
        }
    }

    /**
     * Sizes the two sub-holders so that the combined image pair fills the screen
     * with the same CENTER_INSIDE behaviour as a single page.
     *
     * Given screen W×H and two images (w1,h1) and (w2,h2):
     *   combined display height h = min(H, W * h1*h2 / (w1*h2 + w2*h1))
     *   display widths:  dw1 = h * w1/h1,  dw2 = h * w2/h2
     */
    private fun layoutDoublePage() {
        val (w1, h1) = rightDims ?: return
        val (w2, h2) = leftDims ?: return
        if (w1 <= 0 || h1 <= 0 || w2 <= 0 || h2 <= 0) return

        val screenW = width.takeIf { it > 0 } ?: return
        val screenH = height.takeIf { it > 0 } ?: return

        // Combined height that fits both images side-by-side within screen bounds
        val combinedH = min(
            screenH.toFloat(),
            screenW.toFloat() * h1.toFloat() * h2.toFloat() /
                (w1.toFloat() * h2.toFloat() + w2.toFloat() * h1.toFloat()),
        )
        val dw1 = (combinedH * w1 / h1).toInt()
        val dw2 = (combinedH * w2 / h2).toInt()
        val combinedHi = combinedH.toInt()

        // Centre the combined pair vertically
        val topPad = ((screenH - combinedHi) / 2).coerceAtLeast(0)

        pageContainer.setPadding(0, topPad, 0, 0)

        // Right page (displayed on the right side of the container in R2L)
        rightHolder?.let {
            val lp = it.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(0, 0)
            lp.width = dw1
            lp.height = combinedHi
            it.layoutParams = lp
        }
        // Left page (displayed on the left side of the container in R2L)
        leftHolder?.let {
            val lp = it.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(0, 0)
            lp.width = dw2
            lp.height = combinedHi
            it.layoutParams = lp
        }
    }

    private fun applyTransform() {
        pageContainer.pivotX = pageContainer.width / 2f
        pageContainer.pivotY = pageContainer.height / 2f
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
                            // Right page goes to the RIGHT inside the container
                            pageContainer.addView(
                                holder,
                                LinearLayout.LayoutParams(
                                    LayoutParams.WRAP_CONTENT,
                                    LayoutParams.WRAP_CONTENT,
                                ),
                            )
                        }
                        is DisplayPage.Single -> {
                            // Single: fill the whole container
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
            Side.LEFT -> {
                leftHolder ?: SinglePageSubHolder(readerThemedContext, page).also { holder ->
                    leftHolder = holder
                    // Left page goes to the LEFT (index 0) in the container
                    pageContainer.addView(
                        holder,
                        0,
                        LinearLayout.LayoutParams(
                            LayoutParams.WRAP_CONTENT,
                            LayoutParams.WRAP_CONTENT,
                        ),
                    )
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
        removeProgressIndicator()
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(readerThemedContext), this, true)
            errorLayout?.actionRetry?.viewer = viewer
            errorLayout?.actionRetry?.setOnClickListener {
                page.chapter.pageLoader?.retryPage(page)
            }
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

    /**
     * A thin wrapper around [ReaderPageImageView] that stores the page reference
     * and exposes a convenience [setImage] forwarding method.
     */
    private inner class SinglePageSubHolder(
        context: Context,
        val page: ReaderPage,
    ) : ReaderPageImageView(context)
}
