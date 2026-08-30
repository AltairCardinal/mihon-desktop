package mihon.desktop.ui.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.desktop.reader.DesktopReaderPresentationImageState
import mihon.desktop.reader.DesktopReaderPresentationImageSlotIdentity
import mihon.domain.reader.PixelBounds

internal data class ReaderPresentationTransformKey(
    val presentationIdentity: DesktopReaderPresentationImageSlotIdentity,
    val cropBorders: Boolean,
    val animationFrameIndex: Int? = null,
)

internal interface ReaderPresentationBitmapLease : AutoCloseable {
    val bitmap: ImageBitmap
    val renderedSourceBounds: PixelBounds
        get() = PixelBounds(0, 0, bitmap.width, bitmap.height)
}

internal sealed interface ReaderPresentationTransformState {
    data object Empty : ReaderPresentationTransformState

    data class Loading(
        val key: ReaderPresentationTransformKey,
    ) : ReaderPresentationTransformState

    data class Ready(
        val key: ReaderPresentationTransformKey,
        val lease: ReaderPresentationBitmapLease,
    ) : ReaderPresentationTransformState {
        val bitmap: ImageBitmap
            get() = lease.bitmap
    }
}

/** Owns one asynchronously produced presentation bitmap and rejects every replaced result. */
internal class ReaderPresentationTransformOwner(
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val lock = Any()
    private val mutableState = MutableStateFlow<ReaderPresentationTransformState>(
        ReaderPresentationTransformState.Empty,
    )
    private var activeJob: Job? = null
    private var readyLease: ReaderPresentationBitmapLease? = null
    private var closed = false

    val state: StateFlow<ReaderPresentationTransformState> = mutableState.asStateFlow()

    fun submit(
        key: ReaderPresentationTransformKey,
        producer: suspend () -> ReaderPresentationBitmapLease,
    ) {
        var previousJob: Job? = null
        var previousLease: ReaderPresentationBitmapLease? = null
        lateinit var replacementJob: Job
        synchronized(lock) {
            check(!closed) { "Reader presentation transform owner is closed" }
            previousJob = activeJob
            previousLease = readyLease
            readyLease = null
            mutableState.value = ReaderPresentationTransformState.Loading(key)
            replacementJob = scope.launch(start = CoroutineStart.LAZY) {
                produce(key, producer)
            }
            activeJob = replacementJob
        }
        previousJob?.cancel()
        previousLease?.close()
        replacementJob.start()
    }

    fun acknowledgeDraw(key: ReaderPresentationTransformKey): Boolean = synchronized(lock) {
        val ready = mutableState.value as? ReaderPresentationTransformState.Ready ?: return false
        ready.key == key && ready.lease === readyLease
    }

    fun clear() {
        val resources = synchronized(lock) {
            if (closed) return
            val result = activeJob to readyLease
            activeJob = null
            readyLease = null
            mutableState.value = ReaderPresentationTransformState.Empty
            result
        }
        resources.first?.cancel()
        resources.second?.close()
    }

    override fun close() {
        val resources = synchronized(lock) {
            if (closed) return
            closed = true
            val result = activeJob to readyLease
            activeJob = null
            readyLease = null
            mutableState.value = ReaderPresentationTransformState.Empty
            result
        }
        resources.first?.cancel()
        resources.second?.close()
    }

    private suspend fun produce(
        key: ReaderPresentationTransformKey,
        producer: suspend () -> ReaderPresentationBitmapLease,
    ) {
        val runningJob = checkNotNull(currentCoroutineContext()[Job])
        var producedLease: ReaderPresentationBitmapLease? = null
        try {
            producedLease = producer()
            val accepted = synchronized(lock) {
                if (!closed && activeJob === runningJob) {
                    readyLease = producedLease
                    mutableState.value = ReaderPresentationTransformState.Ready(key, producedLease)
                    true
                } else {
                    false
                }
            }
            if (accepted) producedLease = null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            synchronized(lock) {
                if (!closed && activeJob === runningJob) {
                    activeJob = null
                    mutableState.value = ReaderPresentationTransformState.Empty
                }
            }
        } finally {
            producedLease?.close()
            synchronized(lock) {
                if (activeJob === runningJob && mutableState.value !is ReaderPresentationTransformState.Ready) {
                    activeJob = null
                }
            }
        }
    }
}

internal class CloseableReaderPresentationBitmapLease(
    override val bitmap: ImageBitmap,
    override val renderedSourceBounds: PixelBounds = PixelBounds(0, 0, bitmap.width, bitmap.height),
    private val disposer: () -> Unit,
) : ReaderPresentationBitmapLease {
    private val closed = AtomicBoolean()

    override fun close() {
        if (closed.compareAndSet(false, true)) disposer()
    }
}

internal data class ReaderPresentationRenderedImage(
    val key: ReaderPresentationTransformKey,
    val bitmap: ImageBitmap,
    val renderedSourceBounds: PixelBounds,
    val acknowledgeDraw: () -> Boolean,
)

/** Retains the base asset through transform and owns any derived native bitmap through detach. */
@Composable
internal fun rememberReaderPresentationRenderedImage(
    presentationImage: ReaderPresentationImage,
    cropBorders: Boolean,
): ReaderPresentationRenderedImage? {
    val identity = presentationImage.holder.identity
    val ready = presentationImage.state as? DesktopReaderPresentationImageState.Ready
    val animatedReady = presentationImage.animatedState?.takeIf { state ->
        state.readyKey != null && state.readyAsset != null && !state.closed
    }
    val animationReadyKey = animatedReady?.readyKey
    val animationFrameIndex = animationReadyKey?.frameIndex
    val sourceAsset = animatedReady?.readyAsset ?: ready?.asset
    val key = remember(identity, cropBorders, animationFrameIndex) {
        ReaderPresentationTransformKey(identity, cropBorders, animationFrameIndex)
    }
    val scope = rememberCoroutineScope()
    val transformOwner = remember(scope, key, sourceAsset) {
        ReaderPresentationTransformOwner(scope)
    }
    DisposableEffect(transformOwner) {
        onDispose(transformOwner::close)
    }
    LaunchedEffect(transformOwner, sourceAsset) {
        if (ready == null || sourceAsset == null) {
            transformOwner.clear()
            return@LaunchedEffect
        }
        transformOwner.submit(key) {
            val baseLease = if (animationFrameIndex == null) {
                presentationImage.holder.retainReadyAssetForRender(identity)
            } else {
                presentationImage.animatedHolder?.retainReadyFrameForRender(requireNotNull(animationReadyKey))
            }
            requireNotNull(baseLease) { "Ready presentation image lost its render lease: $identity" }
            val baseBitmap = baseLease.asset.bitmap
            val baseSourceBounds = identity.sourceBounds
                ?: identity.splitHalf?.let { half ->
                    splitBounds(baseLease.asset.sourceWidth, baseLease.asset.sourceHeight, half)
                }
                ?: PixelBounds(0, 0, baseLease.asset.sourceWidth, baseLease.asset.sourceHeight)
            if (identity.splitHalf == null && identity.sourceBounds == null && !cropBorders) {
                return@submit CloseableReaderPresentationBitmapLease(
                    bitmap = baseBitmap,
                    renderedSourceBounds = baseSourceBounds,
                    disposer = baseLease::close,
                )
            }
            try {
                val transformed = withContext(Dispatchers.Default + NonCancellable) {
                    transformCachedPageBitmapWithSourceBounds(
                        bitmap = baseBitmap,
                        splitHalf = identity.splitHalf,
                        sourceBounds = identity.sourceBounds,
                        cropBorders = cropBorders,
                        sourceWidth = baseLease.asset.sourceWidth,
                        sourceHeight = baseLease.asset.sourceHeight,
                    )
                }
                if (transformed.bitmap === baseBitmap) {
                    CloseableReaderPresentationBitmapLease(
                        bitmap = baseBitmap,
                        renderedSourceBounds = transformed.renderedSourceBounds,
                        disposer = baseLease::close,
                    )
                } else {
                    baseLease.close()
                    CloseableReaderPresentationBitmapLease(
                        bitmap = transformed.bitmap,
                        renderedSourceBounds = transformed.renderedSourceBounds,
                        disposer = transformed.bitmap.asSkiaBitmap()::close,
                    )
                }
            } catch (error: Throwable) {
                baseLease.close()
                throw error
            }
        }
    }
    val transformedState by transformOwner.state.collectAsState()
    val transformed = transformedState as? ReaderPresentationTransformState.Ready ?: return null
    if (transformed.key != key) return null
    return ReaderPresentationRenderedImage(
        key = key,
        bitmap = transformed.bitmap,
        renderedSourceBounds = transformed.lease.renderedSourceBounds,
        acknowledgeDraw = {
            transformOwner.acknowledgeDraw(key) && presentationImage.holder.acknowledgeDraw(identity)
        },
    )
}
