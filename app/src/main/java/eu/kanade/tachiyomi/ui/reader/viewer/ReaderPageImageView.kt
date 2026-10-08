package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.AsyncTask
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.annotation.CallSuper
import androidx.annotation.StyleRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.os.postDelayed
import androidx.core.view.isVisible
import coil3.BitmapImage
import coil3.asDrawable
import coil3.dispose
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.ViewSizeResolver
import com.davemorrissey.labs.subscaleview.ImageRotation
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_IN_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
import com.github.chrisbanes.photoview.PhotoView
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.coil.DecodeRequestIdentity
import eu.kanade.tachiyomi.data.coil.DecodeRequestIdentitySource
import eu.kanade.tachiyomi.data.coil.applySharedReaderCachePolicy
import eu.kanade.tachiyomi.data.coil.cropBorders
import eu.kanade.tachiyomi.data.coil.customDecoder
import eu.kanade.tachiyomi.data.coil.readerDecodeIdentity
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonSubsamplingImageView
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.view.isVisibleOnScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import mihon.domain.reader.PageDecodeCachePolicy
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A wrapper view for showing page image.
 *
 * Animated image will be drawn by [PhotoView] while [SubsamplingScaleImageView] will take non-animated image.
 *
 * @param isWebtoon if true, [WebtoonSubsamplingImageView] will be used instead of [SubsamplingScaleImageView]
 * and [AppCompatImageView] will be used instead of [PhotoView]
 */
open class ReaderPageImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttrs: Int = 0,
    @StyleRes defStyleRes: Int = 0,
    private val isWebtoon: Boolean = false,
) : FrameLayout(context, attrs, defStyleAttrs, defStyleRes) {

    companion object {
        // These original process-shared decoder tasks can outlive a recycled view or reader.
        private val decoderTasks = MutableStateFlow(0)
        internal val foregroundWork: StateFlow<Int> = decoderTasks.asStateFlow()

        internal fun beginForegroundWork(): () -> Unit {
            val finished = AtomicBoolean()
            decoderTasks.update { it + 1 }
            return {
                if (finished.compareAndSet(false, true)) decoderTasks.update { it - 1 }
            }
        }
    }

    private val alwaysDecodeLongStripWithSSIV by lazy {
        Injekt.get<BasePreferences>().alwaysDecodeLongStripWithSSIV().get()
    }

    private var pageView: View? = null

    private var config: Config? = null
    private val decodeIdentitySource = DecodeRequestIdentitySource()
    private var imageCallbackIsCurrent: () -> Boolean = { true }
    private var animatedDrawableIsCurrent: () -> Boolean = { false }
    private var regionInputStream: java.io.InputStream? = null

    internal var decodedPageSideHint = mihon.domain.reader.ReaderPageSideHint.UNKNOWN
        private set
    private var sampledPageBitmap: java.lang.ref.WeakReference<Bitmap>? = null
    private var sampledOrientation: mihon.domain.reader.ReaderImageOrientation? = null

    private fun resetDecodedSideHint() {
        sampledPageBitmap = null
        sampledOrientation = null
        decodedPageSideHint = mihon.domain.reader.ReaderPageSideHint.UNKNOWN
    }

    private fun decodedSideEnabled(): Boolean = config?.let {
        it.pageSideHintEnabled && !it.cropBorders && it.sourceBounds == null
    } == true

    private fun observeDecodedSide(native: SubsamplingScaleImageView, bitmap: Bitmap, matrix: Matrix) {
        val current = config ?: return
        if (!current.pageSideHintEnabled || current.cropBorders || current.sourceBounds != null ||
            bitmap.isRecycled || bitmap.config == Bitmap.Config.HARDWARE
        ) {
            return
        }
        if (sampledPageBitmap?.get() === bitmap && sampledOrientation == current.sourceOrientation) return
        // The same source-to-view geometry distinguishes a whole base image from a region tile.
        val bounds = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        matrix.mapRect(bounds)
        val fullSize = current.sourceOrientation.orientedSize(native.sWidth, native.sHeight)
        val first = native.sourceToViewCoord(0f, 0f) ?: return
        val last = native.sourceToViewCoord(fullSize.width.toFloat(), fullSize.height.toFloat()) ?: return
        if (kotlin.math.abs(bounds.left - first.x) > 2f || kotlin.math.abs(bounds.top - first.y) > 2f ||
            kotlin.math.abs(bounds.right - last.x) > 2f || kotlin.math.abs(bounds.bottom - last.y) > 2f
        ) {
            return
        }
        val region = Rect()
        native.viewToFileRect(
            Rect(
                kotlin.math.floor(bounds.left).toInt() - 1,
                kotlin.math.floor(bounds.top).toInt() - 1,
                kotlin.math.ceil(bounds.right).toInt() + 1,
                kotlin.math.ceil(bounds.bottom).toInt() + 1,
            ),
            region,
        )
        if (region.left > 0 || region.top > 0 || region.right < native.sWidth || region.bottom < native.sHeight) return
        val orientation = current.sourceOrientation
        val size = orientation.orientedSize(bitmap.width, bitmap.height)
        decodedPageSideHint = mihon.domain.reader.ReaderPageSideHints.classify(size.width, size.height) { x, y ->
            val (rawX, rawY) = orientation.sourcePixel(x, y, bitmap.width, bitmap.height)
            bitmap.getPixel(rawX, rawY)
        }
        sampledPageBitmap = java.lang.ref.WeakReference(bitmap)
        sampledOrientation = orientation
    }

    var onImageLoaded: (() -> Unit)? = null

    /** Acquires physical geometry immediately before the child can expose its first pixels. */
    var beforeImageDraw: ((width: Int, height: Int) -> Boolean)? = null

    /** A visible, actually loaded child has completed its native drawing pass. */
    var onImageDrawn: (() -> Unit)? = null
    var onImageLoadError: ((Throwable?) -> Unit)? = null
    var onScaleChanged: ((newScale: Float) -> Unit)? = null
    var onViewClicked: (() -> Unit)? = null

    /**
     * For automatic background. Will be set as background color when [onImageLoaded] is called.
     */
    var pageBackground: Drawable? = null

    private fun prepareNativeImageDraw(width: Int, height: Int): Boolean {
        if (beforeImageDraw == null && onImageDrawn == null) return true
        return imageCallbackIsCurrent() && width > 0 && height > 0 && getGlobalVisibleRect(Rect()) &&
            beforeImageDraw?.invoke(width, height) != false
    }

    private fun nativeImageDrawn() {
        if (imageCallbackIsCurrent()) onImageDrawn?.invoke()
    }

    /** A new viewport capability must repaint an already decoded child RenderNode. */
    internal fun invalidateImageDraw() {
        pageView?.invalidate()
    }

    @CallSuper
    open fun onImageLoaded() {
        onImageLoaded?.invoke()
        background = pageBackground
    }

    @CallSuper
    open fun onImageLoadError(error: Throwable?) {
        onImageLoadError?.invoke(error)
    }

    @CallSuper
    open fun onScaleChanged(newScale: Float) {
        onScaleChanged?.invoke(newScale)
    }

    @CallSuper
    open fun onViewClicked() {
        onViewClicked?.invoke()
    }

    open fun onPageSelected(forward: Boolean) {
        with(pageView as? SubsamplingScaleImageView) {
            if (this == null) return
            if (isReady) {
                landscapeZoom(forward)
            } else {
                val callbackIsCurrent = imageCallbackIsCurrent
                setOnImageEventListener(
                    object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onReady() {
                            if (!callbackIsCurrent()) return
                            setupZoom(config)
                            landscapeZoom(forward)
                            this@ReaderPageImageView.onImageLoaded()
                        }

                        override fun onImageLoadError(e: Exception) {
                            if (!callbackIsCurrent()) return
                            onImageLoadError(e)
                        }
                    },
                )
            }
        }
    }

    private fun SubsamplingScaleImageView.landscapeZoom(forward: Boolean) {
        val effective = config?.sourceOrientation?.orientedSize(
            sWidth,
            sHeight,
        ) ?: mihon.domain.reader.ReaderPageSize(sWidth, sHeight)
        if (
            config != null &&
            config!!.landscapeZoom &&
            config!!.minimumScaleType == SCALE_TYPE_CENTER_INSIDE &&
            effective.width > effective.height &&
            scale == minScale
        ) {
            val expectedConfig = config
            val callbackIsCurrent = imageCallbackIsCurrent
            handler?.postDelayed(500) {
                if (config !== expectedConfig || !callbackIsCurrent()) return@postDelayed
                val point = when (config!!.zoomStartPosition) {
                    ZoomStartPosition.LEFT -> PointF(physicalEdge(!forward, effective.width.toFloat()), 0F)
                    ZoomStartPosition.RIGHT -> PointF(physicalEdge(forward, effective.width.toFloat()), 0F)
                    ZoomStartPosition.CENTER -> center
                }

                val targetScale = height.toFloat() / effective.height.toFloat()
                animateScaleAndCenter(targetScale, point)!!
                    .withDuration(500)
                    .withEasing(EASE_IN_OUT_QUAD)
                    .withInterruptible(true)
                    .start()
            }
        }
    }

    fun setImage(drawable: Drawable, config: Config) {
        val identity = decodeIdentitySource.next(-1)
        imageCallbackIsCurrent = identity::isCurrent
        resetDecodedSideHint()
        this.config = config
        if (drawable is Animatable) {
            prepareAnimatedImageView()
            setAnimatedImage(drawable, config, identity, identity::isCurrent)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(drawable, config, identity, identity::isCurrent)
        }
    }

    fun setImage(
        source: BufferedSource,
        isAnimated: Boolean,
        config: Config,
        pageIndex: Int,
        callbackIsCurrent: () -> Boolean = { true },
    ) {
        imageCallbackIsCurrent = callbackIsCurrent
        resetDecodedSideHint()
        this.config = config
        val identity = decodeIdentitySource.next(pageIndex)
        if (isAnimated) {
            prepareAnimatedImageView()
            setAnimatedImage(source, config, identity, callbackIsCurrent)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(source, config, identity, callbackIsCurrent)
        }
    }

    internal fun setRegionImage(
        file: java.io.File,
        config: Config,
        pageIndex: Int,
        callbackIsCurrent: () -> Boolean,
    ) {
        imageCallbackIsCurrent = callbackIsCurrent
        resetDecodedSideHint()
        this.config = config
        val identity = decodeIdentitySource.next(pageIndex)
        prepareNonAnimatedImageView()
        // Own the original handle even while SSIV's tile initialization has not installed its decoder.
        val stream = file.inputStream()
        regionInputStream = stream
        setNonAnimatedImage(stream, config, identity, callbackIsCurrent)
    }

    open fun recycle() {
        resetDecodedSideHint()
        decodeIdentitySource.invalidate()
        try {
            pageView?.let {
                when (it) {
                    is SubsamplingScaleImageView -> it.recycle()
                    is AppCompatImageView -> it.dispose()
                }
                it.isVisible = false
            }
        } finally {
            val stream = regionInputStream
            regionInputStream = null
            stream?.close()
        }
    }

    /**
     * Check if the image can be panned to the left
     */
    fun canPanLeft(): Boolean = canPan { it.left }

    /**
     * Check if the image can be panned to the right
     */
    fun canPanRight(): Boolean = canPan { it.right }

    /**
     * Check whether the image can be panned.
     * @param fn a function that returns the direction to check for
     */
    private fun canPan(fn: (RectF) -> Float): Boolean {
        (pageView as? SubsamplingScaleImageView)?.let { view ->
            RectF().let {
                view.getPanRemaining(it)
                if (config?.sourceOrientation?.mirrorHorizontally == true) {
                    val left = it.left
                    it.left = it.right
                    it.right = left
                }
                return fn(it) > 1
            }
        }
        return false
    }

    /**
     * Pans the image to the left by a screen's width worth.
     */
    fun panLeft() {
        pan { center, view -> center.also { it.x -= view.width / view.scale * horizontalDirection() } }
    }

    /**
     * Pans the image to the right by a screen's width worth.
     */
    fun panRight() {
        pan { center, view -> center.also { it.x += view.width / view.scale * horizontalDirection() } }
    }

    private fun horizontalDirection() = if (config?.sourceOrientation?.mirrorHorizontally == true) -1f else 1f
    private fun physicalEdge(right: Boolean, width: Float): Float =
        if (right != (config?.sourceOrientation?.mirrorHorizontally == true)) width else 0f

    /**
     * Pans the image.
     * @param fn a function that computes the new center of the image
     */
    private fun pan(fn: (PointF, SubsamplingScaleImageView) -> PointF) {
        (pageView as? SubsamplingScaleImageView)?.let { view ->

            val target = fn(view.center ?: return, view)
            view.animateCenter(target)!!
                .withEasing(EASE_OUT_QUAD)
                .withDuration(250)
                .withInterruptible(true)
                .start()
        }
    }

    private fun prepareNonAnimatedImageView() {
        if (pageView is SubsamplingScaleImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            WebtoonSubsamplingImageView(context)
        } else {
            object : SubsamplingScaleImageView(context) {
                override fun onDraw(canvas: Canvas) {
                    if (decodedSideEnabled()) {
                        val observed = ReaderDecodedPageCanvas(canvas) { bitmap, matrix ->
                            observeDecodedSide(this, bitmap, matrix)
                            val dimensions = config?.sourceOrientation?.orientedSize(sWidth, sHeight)
                                ?: mihon.domain.reader.ReaderPageSize(sWidth, sHeight)
                            prepareNativeImageDraw(dimensions.width, dimensions.height)
                        }
                        super.onDraw(observed)
                        if (observed.drewBitmap && isReady) nativeImageDrawn()
                    } else {
                        val drawable = isReady || isImageLoaded
                        if (drawable) {
                            val dimensions = config?.sourceOrientation?.orientedSize(sWidth, sHeight)
                                ?: mihon.domain.reader.ReaderPageSize(sWidth, sHeight)
                            if (!prepareNativeImageDraw(dimensions.width, dimensions.height)) return
                        }
                        // A metadata-only pass initializes normal base tiles without locking geometry.
                        super.onDraw(canvas)
                        if (drawable && isReady) nativeImageDrawn()
                    }
                }
            }
        }.apply {
            // This SSIV version defaults to the same AsyncTask pool; only observe its original tasks.
            setExecutor(ReaderImageForegroundExecutor(AsyncTask.THREAD_POOL_EXECUTOR))
            setMaxTileSize(ImageUtil.hardwareBitmapThreshold)
            setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
            setMinimumTileDpi(180)
            setOnStateChangedListener(
                object : SubsamplingScaleImageView.OnStateChangedListener {
                    override fun onScaleChanged(newScale: Float, origin: Int) {
                        this@ReaderPageImageView.onScaleChanged(newScale)
                    }

                    override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                        // Not used
                    }
                },
            )
            setOnClickListener { this@ReaderPageImageView.onViewClicked() }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun SubsamplingScaleImageView.setupZoom(config: Config?) {
        // 5x zoom
        maxScale = scale * MAX_ZOOM_SCALE
        setDoubleTapZoomScale(scale * 2)

        val effectiveWidth = config?.sourceOrientation?.orientedSize(
            sWidth,
            sHeight,
        )?.width?.toFloat() ?: sWidth.toFloat()
        when (config?.zoomStartPosition) {
            ZoomStartPosition.LEFT -> setScaleAndCenter(scale, PointF(physicalEdge(false, effectiveWidth), 0F))
            ZoomStartPosition.RIGHT -> setScaleAndCenter(scale, PointF(physicalEdge(true, effectiveWidth), 0F))
            ZoomStartPosition.CENTER -> setScaleAndCenter(scale, center)
            null -> {}
        }
    }

    private fun setNonAnimatedImage(
        data: Any,
        config: Config,
        identity: DecodeRequestIdentity? = null,
        callbackIsCurrent: () -> Boolean = { true },
    ) = (pageView as? SubsamplingScaleImageView)?.apply {
        // Retain the original software decode instead of making its optional hardware copy.
        // Ordinary stream inputs restore this library version's original hardware default.
        if (data !is BufferedSource) setHardwareConfig(!decodedSideEnabled())
        imageRotation = when (config.sourceOrientation.rotation) {
            mihon.domain.reader.PageRotation.NONE -> ImageRotation.ROTATION_0
            mihon.domain.reader.PageRotation.CLOCKWISE_90 -> ImageRotation.ROTATION_90
            mihon.domain.reader.PageRotation.HALF_TURN -> ImageRotation.ROTATION_180
            mihon.domain.reader.PageRotation.COUNTER_CLOCKWISE_90 -> ImageRotation.ROTATION_270
        }
        scaleX = if (config.sourceOrientation.mirrorHorizontally) -1f else 1f
        setDoubleTapZoomDuration(config.zoomDuration.getSystemScaledDuration())
        setMinimumScaleType(config.minimumScaleType)
        setMinimumDpi(1) // Just so that very small image will be fit for initial load
        setCropBorders(config.cropBorders)
        setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    if (!callbackIsCurrent()) return
                    setupZoom(config)
                    if (isVisibleOnScreen()) landscapeZoom(true)
                    this@ReaderPageImageView.onImageLoaded()
                }

                override fun onImageLoadError(e: Exception) {
                    if (!callbackIsCurrent()) return
                    this@ReaderPageImageView.onImageLoadError(e)
                }
            },
        )

        when (data) {
            is BitmapDrawable -> {
                setImage(boundedSource(ImageSource.bitmap(data.bitmap), config))
                isVisible = true
            }
            is java.io.InputStream -> {
                setImage(boundedSource(ImageSource.inputStream(data), config))
                isVisible = true
            }
            is BufferedSource -> {
                if (!isWebtoon || alwaysDecodeLongStripWithSSIV) {
                    setHardwareConfig(ImageUtil.canUseHardwareBitmap(data) && !decodedSideEnabled())
                    setImage(boundedSource(ImageSource.inputStream(data.inputStream()), config))
                    isVisible = true
                    return@apply
                }

                ImageRequest.Builder(context)
                    .data(data)
                    .apply { identity?.let(::readerDecodeIdentity) }
                    .applySharedReaderCachePolicy(PageDecodeCachePolicy.TILED_READER)
                    .target(
                        onSuccess = { result ->
                            if (!callbackIsCurrent()) return@target
                            val image = result as BitmapImage
                            setImage(ImageSource.bitmap(image.bitmap))
                            isVisible = true
                        },
                    )
                    .listener(
                        onError = { _, result ->
                            if (!callbackIsCurrent()) return@listener
                            onImageLoadError(result.throwable)
                        },
                    )
                    .size(ViewSizeResolver(this@ReaderPageImageView))
                    .precision(Precision.INEXACT)
                    .cropBorders(config.cropBorders)
                    .customDecoder(true)
                    .crossfade(false)
                    .build()
                    .let { request -> observeReaderImageRequest { context.imageLoader.enqueue(request) } }
            }
            else -> {
                throw IllegalArgumentException("Not implemented for class ${data::class.simpleName}")
            }
        }
    }

    private fun boundedSource(source: ImageSource, config: Config): ImageSource {
        val bounds = config.sourceBounds ?: return source
        require(!config.cropBorders) { "Raw source regions with border cropping require the owned half-page adapter" }
        return source.region(
            android.graphics.Rect(
                bounds.x,
                bounds.y,
                bounds.x + bounds.width,
                bounds.y +
                    bounds.height,
            ),
        )
    }

    private fun prepareAnimatedImageView() {
        if (pageView is AppCompatImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            AppCompatImageView(context)
        } else {
            object : PhotoView(context) {
                override fun onDraw(canvas: Canvas) {
                    val image = drawable
                    val current = image != null && animatedDrawableIsCurrent()
                    if (current && !prepareNativeImageDraw(image!!.intrinsicWidth, image.intrinsicHeight)) return
                    super.onDraw(canvas)
                    if (current) nativeImageDrawn()
                }
            }
        }.apply {
            adjustViewBounds = true

            if (this is PhotoView) {
                setScaleLevels(1F, 2F, MAX_ZOOM_SCALE)
                // Force 2 scale levels on double tap
                setOnDoubleTapListener(
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (scale > 1F) {
                                setScale(1F, e.x, e.y, true)
                            } else {
                                setScale(2F, e.x, e.y, true)
                            }
                            return true
                        }

                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            this@ReaderPageImageView.onViewClicked()
                            return super.onSingleTapConfirmed(e)
                        }
                    },
                )
                setOnScaleChangeListener { _, _, _ ->
                    this@ReaderPageImageView.onScaleChanged(scale)
                }
            }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun setAnimatedImage(
        data: Any,
        config: Config,
        identity: DecodeRequestIdentity? = null,
        callbackIsCurrent: () -> Boolean = { true },
    ) = (pageView as? AppCompatImageView)?.apply {
        if (this is PhotoView) {
            setZoomTransitionDuration(config.zoomDuration.getSystemScaledDuration())
        }

        val request = ImageRequest.Builder(context)
            .data(data)
            .apply { identity?.let(::readerDecodeIdentity) }
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(
                onSuccess = { result ->
                    if (!callbackIsCurrent() || identity?.isCurrent() == false) return@target
                    val drawable = result.asDrawable(context.resources)
                    animatedDrawableIsCurrent = { callbackIsCurrent() && identity?.isCurrent() != false }
                    setImageDrawable(drawable)
                    (drawable as? Animatable)?.start()
                    isVisible = true
                    this@ReaderPageImageView.onImageLoaded()
                },
            )
            .listener(
                onError = { _, result ->
                    if (!callbackIsCurrent() || identity?.isCurrent() == false) return@listener
                    onImageLoadError(result.throwable)
                },
            )
            .crossfade(false)
            .build()
        observeReaderImageRequest { context.imageLoader.enqueue(request) }
    }

    private fun Int.getSystemScaledDuration(): Int {
        return (this * context.animatorDurationScale).toInt().coerceAtLeast(1)
    }

    /**
     * All of the config except [zoomDuration] will only be used for non-animated image.
     */
    data class Config(
        val zoomDuration: Int,
        val minimumScaleType: Int = SCALE_TYPE_CENTER_INSIDE,
        val cropBorders: Boolean = false,
        val zoomStartPosition: ZoomStartPosition = ZoomStartPosition.CENTER,
        val landscapeZoom: Boolean = false,
        val sourceOrientation: mihon.domain.reader.ReaderImageOrientation =
            mihon.domain.reader.ReaderImageOrientation.NORMAL,
        val sourceBounds: mihon.domain.reader.PixelBounds? = null,
        val pageSideHintEnabled: Boolean = false,
    )

    enum class ZoomStartPosition {
        LEFT,
        CENTER,
        RIGHT,
    }
}

/** Initial Coil work remains observed until its original deferred actually completes, including cancellation. */
internal fun observeReaderImageRequest(enqueue: () -> Disposable): Disposable {
    val finished = ReaderPageImageView.beginForegroundWork()
    return try {
        enqueue().also { it.job.invokeOnCompletion { finished() } }
    } catch (failure: Throwable) {
        finished()
        throw failure
    }
}

private const val MAX_ZOOM_SCALE = 5F

/** Keeps accepted queued/running native work busy until its original Runnable actually exits. */
internal class ReaderImageForegroundExecutor(private val delegate: Executor) : Executor {
    override fun execute(command: Runnable) {
        val finished = ReaderPageImageView.beginForegroundWork()
        try {
            delegate.execute {
                try {
                    command.run()
                } finally {
                    finished()
                }
            }
        } catch (failure: Throwable) {
            // Also handles rejected submission or a synchronous delegate; completion is exactly once.
            finished()
            throw failure
        }
    }
}
