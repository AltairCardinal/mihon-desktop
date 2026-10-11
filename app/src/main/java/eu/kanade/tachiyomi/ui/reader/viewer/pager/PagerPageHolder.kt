package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import androidx.core.view.isVisible
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
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
import mihon.domain.reader.SharedDualPageSession
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

/**
 * View of the ViewPager that contains a page of a chapter.
 */
@SuppressLint("ViewConstructor")
class PagerPageHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val page: ReaderPage,
    private val regionEncoder: suspend (
        BufferedSource,
        mihon.domain.reader.PixelBounds,
        java.io.File,
    ) -> java.io.File = { source, bounds, directory ->
        eu.kanade.tachiyomi.ui.reader.viewer.encodeReaderPageRegion(
            source,
            android.graphics.Rect(bounds.x, bounds.y, bounds.x + bounds.width, bounds.y + bounds.height),
            directory,
        )
    },
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView {

    /**
     * Item that identifies this view. Needed by the adapter to not recreate views.
     */
    override val item
        get() = page

    /**
     * Loading progress bar to indicate the current progress.
     */
    private var progressIndicator: ReaderProgressIndicator? = null // = ReaderProgressIndicator(readerThemedContext)

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    private val scope = MainScope()

    /**
     * Job for loading the page and processing changes to the page's status.
     */
    private var loadJob: Job? = null
    private var contentJob: Job? = null
    private var attachmentGeneration = 0L
    private var decodeGeneration = 0L
    private var presentationGeneration = 0L
    private var boundContent: String? = null
    private var renderedContent: String? = null
    private var imageCallbackCurrent: () -> Boolean = { false }
    private var displayedRegionFile: java.io.File? = null
    internal var presentationScope: Pair<SharedDualPageSession, ReaderDualPageViewportTicket>? = null
        private set

    internal fun beginPresentation() {
        val next = viewer.capturePresentation(page)
        if (presentationScope?.second === next?.second) return
        presentationScope?.let(viewer::releasePresentation)
        presentationScope = next
    }

    internal fun hasRenderedPage(): Boolean =
        page.status == Page.State.Ready && renderedContent == page.contentIdentity()

    init {
        loadJob = scope.launch { loadPageAndProcessStatus() }
        observeContent()
    }

    /**
     * Called when this view is detached from the window. Unsubscribes any active subscription.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attachmentGeneration++
        renderedContent = null
        boundContent = null
        presentationScope?.let(viewer::releasePresentation)
        presentationScope = null
        loadJob?.cancel()
        loadJob = null
        contentJob?.cancel()
        contentJob = null
        recycle()
    }

    override fun recycle() {
        presentationGeneration++
        recycleImage()
    }

    private fun recycleImage() {
        renderedContent = null
        boundContent = null
        imageCallbackCurrent = { false }
        try {
            super.recycle()
        } finally {
            releaseRegionFile()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        beginPresentation()
        if (loadJob == null) loadJob = scope.launch { loadPageAndProcessStatus() }
        observeContent()
    }

    private fun observeContent() {
        if (contentJob != null) return
        var observed = page.contentFact.value.version
        contentJob = scope.launch {
            page.contentFact.collect { captured ->
                if (observed != captured.version) {
                    observed = captured.version
                    renderedContent = null
                    boundContent = null
                    if (page.status == Page.State.Ready) setImage()
                }
            }
        }
    }

    private fun initProgressIndicator() {
        if (progressIndicator == null) {
            progressIndicator = ReaderProgressIndicator(context)
            addView(progressIndicator)
        }
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus() {
        val loader = page.chapter.pageLoader ?: return

        supervisorScope {
            launchIO {
                loader.loadPage(page)
            }
            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue -> setQueued()
                    Page.State.LoadPage -> setLoading()
                    Page.State.DownloadImage -> {
                        setDownloading()
                        page.progressFlow.collectLatest { value ->
                            progressIndicator?.setProgress(value)
                        }
                    }
                    Page.State.Ready -> setImage()
                    is Page.State.Error -> setError(state.error)
                }
            }
        }
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading.
     */
    private fun setDownloading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is ready.
     */
    private suspend fun setImage() {
        progressIndicator?.setProgress(0)
        renderedContent = null
        beginPresentation()
        val generation = ++decodeGeneration
        val attachment = attachmentGeneration
        val presentation = presentationGeneration
        val encodedContent = page.encodedContentIdentity()
        withIOContext { page.publishHeaderFact() }
        if (generation != decodeGeneration || attachment != attachmentGeneration ||
            presentation != presentationGeneration || page.encodedContentIdentity() != encodedContent
        ) {
            return
        }
        beginPresentation()
        val content = page.contentIdentity()
        val orientation = page.contentFact.value.fact.sourceOrientation
        val streamFn = page.stream ?: return
        val isCurrent = {
            generation == decodeGeneration && attachment == attachmentGeneration &&
                presentation == presentationGeneration && content == page.contentIdentity() &&
                streamFn === page.stream && page.status == Page.State.Ready
        }
        var pendingRegionFile: java.io.File? = null
        try {
            val prepared = withIOContext {
                var source: BufferedSource = streamFn().use { Buffer().readFrom(it) }
                val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                var remainingOrientation = orientation
                var bounds: mihon.domain.reader.PixelBounds? = null
                var splitOriginal = false
                if (orientation == mihon.domain.reader.ReaderImageOrientation.NORMAL) {
                    source = process(item, source)
                } else if (!isAnimated) {
                    val(rawWidth, rawHeight) = ImageUtil.getImageDimensions(source.peek())
                    val size = orientation.orientedSize(rawWidth, rawHeight)
                    val wide = size.width > size.height
                    if (viewer.config.dualPageRotateToFit && wide) {
                        remainingOrientation = orientation.rotatedAfter(
                            if (viewer.config.dualPageRotateToFitInvert) {
                                mihon.domain.reader.PageRotation.COUNTER_CLOCKWISE_90
                            } else {
                                mihon.domain.reader.PageRotation.CLOCKWISE_90
                            },
                        )
                    } else if (viewer.config.dualPageSplit && (page is InsertPage || wide)) {
                        bounds = checkNotNull(
                            orientation.splitBounds(
                                rawWidth,
                                rawHeight,
                                if (splitSide() ==
                                    ImageUtil.Side.LEFT
                                ) {
                                    mihon.domain.reader.PageSplitHalf.LEFT
                                } else {
                                    mihon.domain.reader.PageSplitHalf.RIGHT
                                },
                            ),
                        )
                        splitOriginal = page !is InsertPage
                        if (viewer.config.imageCropBorders) {
                            pendingRegionFile = regionEncoder(source, bounds, context.cacheDir)
                            // The owned PNG already contains this raw region; SSIV crops its local coordinates.
                            bounds = null
                        }
                    }
                }
                val background = if (!isAnimated && viewer.config.automaticBackground) {
                    ImageUtil.chooseBackground(context, source.peek().inputStream())
                } else {
                    null
                }
                PreparedImage(source, isAnimated, background, remainingOrientation, bounds, splitOriginal)
            }
            withUIContext {
                if (!isCurrent()) return@withUIContext
                if (prepared.splitOriginal) onPageSplit(page)
                val imageConfig = Config(
                    zoomDuration = viewer.config.doubleTapAnimDuration,
                    minimumScaleType = viewer.config.imageScaleType,
                    cropBorders = viewer.config.imageCropBorders,
                    zoomStartPosition = viewer.config.imageZoomType,
                    landscapeZoom = viewer.config.landscapeZoom,
                    sourceOrientation = prepared.orientation,
                    sourceBounds = prepared.bounds,
                )
                recycleImage()
                boundContent = content
                imageCallbackCurrent = isCurrent
                val regionFile = pendingRegionFile
                if (regionFile != null) {
                    displayedRegionFile = regionFile
                    pendingRegionFile = null
                    setRegionImage(regionFile, imageConfig, page.index, isCurrent)
                } else {
                    setImage(prepared.source, prepared.animated, imageConfig, page.index, callbackIsCurrent = isCurrent)
                }
                if (!prepared.animated) {
                    pageBackground = prepared.background
                }
                removeErrorLayout()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext {
                if (isCurrent()) setError(e)
            }
        } finally {
            pendingRegionFile?.delete()
        }
    }

    private data class PreparedImage(
        val source: BufferedSource,
        val animated: Boolean,
        val background: android.graphics.drawable.Drawable?,

        val orientation: mihon.domain.reader.ReaderImageOrientation,
        val bounds: mihon.domain.reader.PixelBounds?,
        val splitOriginal: Boolean,
    )

    private fun releaseRegionFile() {
        displayedRegionFile?.delete()
        displayedRegionFile = null
    }

    private fun process(page: ReaderPage, imageSource: BufferedSource): BufferedSource {
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (!viewer.config.dualPageSplit) {
            return imageSource
        }

        if (page is InsertPage) {
            return splitInHalf(imageSource)
        }

        val isDoublePage = ImageUtil.isWideImage(imageSource)
        if (!isDoublePage) {
            return imageSource
        }

        onPageSplit(page)

        return splitInHalf(imageSource)
    }

    private fun rotateDualPage(imageSource: BufferedSource): BufferedSource {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        return if (isDoublePage) {
            val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
            ImageUtil.rotateImage(imageSource, rotation)
        } else {
            imageSource
        }
    }

    private fun splitInHalf(imageSource: BufferedSource): BufferedSource {
        return ImageUtil.splitInHalf(imageSource, splitSide())
    }

    private fun splitSide(): ImageUtil.Side {
        var side = when {
            viewer is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.RIGHT
            viewer !is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.LEFT
            viewer !is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.RIGHT
            else -> error("We should choose a side!")
        }

        if (viewer.config.dualPageInvert) {
            side = when (side) {
                ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
                ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
            }
        }

        return side
    }

    private fun onPageSplit(page: ReaderPage) {
        val newPage = InsertPage(page)
        viewer.onPageSplit(page, newPage)
    }

    /**
     * Called when the page has an error.
     */
    private fun setError(error: Throwable?) {
        if (displayedRegionFile != null) recycle()
        renderedContent = null
        progressIndicator?.hide()
        showErrorLayout(error)
    }

    override fun onImageLoaded() {
        if (!imageCallbackCurrent() || boundContent != page.contentIdentity()) return
        super.onImageLoaded()
        progressIndicator?.hide()
        presentationScope?.let { (owner, ticket) -> owner.preparePage(ticket, owner.identity.pageIds[page.index]) }
        renderedContent = boundContent
        viewer.onHolderDisplayStateChanged(this)
    }

    /**
     * Called when an image fails to decode.
     */
    override fun onImageLoadError(error: Throwable?) {
        if (!imageCallbackCurrent()) return
        super.onImageLoadError(error)
        setError(error)
    }

    /**
     * Called when an image is zoomed in/out.
     */
    override fun onScaleChanged(newScale: Float) {
        super.onScaleChanged(newScale)
        viewer.activity.hideMenu()
    }

    private fun showErrorLayout(error: Throwable?): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), this, true)
            errorLayout?.actionRetry?.viewer = viewer
            errorLayout?.actionRetry?.setOnClickListener {
                page.chapter.pageLoader?.retryPage(page)
            }
        }

        val imageUrl = page.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.viewer = viewer
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val sourceId = viewer.activity.viewModel.manga?.source

                    val intent = WebViewActivity.newIntent(context, imageUrl, sourceId)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)

        errorLayout?.root?.isVisible = true
        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.root?.isVisible = false
        errorLayout = null
    }
}
