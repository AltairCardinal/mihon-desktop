package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import mihon.domain.error.AppError
import mihon.domain.reader.ReaderDualPageFactLimits
import mihon.domain.reader.ReaderPageFact
import mihon.domain.reader.ReaderPageFactState
import mihon.domain.reader.ReaderPageModel
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderPageLoadState
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import okio.source
import tachiyomi.core.common.util.system.ImageUtil
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

internal data class ReaderPageContentFact(
    val version: Long,
    val fact: ReaderPageFact,
    val retrySequence: Long = 0,
    val encodedVersion: Long = 0,
)

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null) {

    open lateinit var chapter: ReaderChapter

    private var sharedStatePublisher: ((ReaderPageLoadState) -> Unit)? = null
    private var sharedMaterializeError: AppError? = null

    private val contentLock = Any()
    private val contentSourceId = nextContentSourceId.incrementAndGet()
    private var retrySequence = 0L
    private var encodedVersion = 0L
    private var confirmedOrientation = mihon.domain.reader.ReaderImageOrientation.NORMAL
    private val mutableContentVersion = MutableStateFlow(0L)
    val contentVersion = mutableContentVersion.asStateFlow()
    private val mutablePageFact = MutableStateFlow(ReaderPageFact(ReaderPageFactState.UNKNOWN))
    val pageFact = mutablePageFact.asStateFlow()
    private val mutableContentFact = MutableStateFlow(ReaderPageContentFact(0L, mutablePageFact.value))
    internal val contentFact = mutableContentFact.asStateFlow()

    var stream: (() -> InputStream)? = stream
        set(value) = synchronized(contentLock) {
            if (field != null && field !== value) invalidateContent()
            field = value
        }

    var encodedPageRef: EncodedPageRef? = null
        set(value) = synchronized(contentLock) {
            if (field != null && field != value) invalidateContent()
            field = value
        }

    private fun invalidateContent() {
        encodedVersion++
        confirmedOrientation = mihon.domain.reader.ReaderImageOrientation.NORMAL
        mutablePageFact.value = ReaderPageFact(ReaderPageFactState.UNKNOWN)
        mutableContentVersion.value++
        mutableContentFact.value =
            ReaderPageContentFact(mutableContentVersion.value, mutablePageFact.value, retrySequence, encodedVersion)
    }

    internal fun contentIdentity(): String = synchronized(contentLock) {
        "$contentSourceId:${mutableContentVersion.value}"
    }

    internal fun encodedContentIdentity(): String = synchronized(contentLock) { "$contentSourceId:$encodedVersion" }

    /** Capture the loader's original factory before sample I/O, without resolving or materializing content. */
    internal fun captureEncodedStream(version: String): (() -> InputStream)? = synchronized(contentLock) {
        stream.takeIf { version == "$contentSourceId:$encodedVersion" }
    }

    /** Identity and metadata must be sampled together before a presentation owner is created. */
    internal fun captureContentFact(): Pair<String, ReaderPageContentFact> = synchronized(contentLock) {
        "$contentSourceId:$encodedVersion" to mutableContentFact.value
    }

    /** Consumes only a current cached fact already admitted by its original pre-I/O stream snapshot. */
    internal fun consumeContentFact(
        captured: Pair<String, ReaderPageContentFact>,
        consume: () -> Unit,
    ): Boolean = synchronized(contentLock) {
        if (captured.first != "$contentSourceId:$encodedVersion" ||
            captured.second != mutableContentFact.value
        ) {
            return@synchronized false
        }
        consume()
        true
    }

    internal fun retryHeaderFact() = synchronized(contentLock) {
        retrySequence++
        if (mutablePageFact.value.state == ReaderPageFactState.FAILED) {
            mutablePageFact.value =
                ReaderPageFact(ReaderPageFactState.UNKNOWN, sourceOrientation = confirmedOrientation)
        }
        mutableContentFact.value =
            ReaderPageContentFact(mutableContentVersion.value, mutablePageFact.value, retrySequence, encodedVersion)
    }

    /** Called by the existing materialization worker, while its encoded stream remains owned by the loader. */
    internal fun publishHeaderFact() {
        val captured = synchronized(contentLock) {
            HeaderSnapshot(
                stream ?: return,
                encodedVersion,
                mutableContentVersion.value,
                retrySequence,
                confirmedOrientation,
            )
        }
        val openStream = captured.openStream
        val fact = try {
            val bounded = object : ForwardingSource(openStream().source()) {
                private var remaining = ReaderDualPageFactLimits.MAX_HEADER_BYTES
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (remaining == 0L) return -1
                    return super.read(sink, minOf(byteCount, remaining)).also { if (it > 0) remaining -= it }
                }
            }
            bounded.buffer().use { source ->
                val orientation = mihon.domain.reader.readImageOrientation(source.peek().readByteArray())
                val (rawWidth, rawHeight) = ImageUtil.getImageDimensions(source.peek())
                val (width, height) = orientation.orientedSize(rawWidth, rawHeight)
                if (width > 0 && height > 0) {
                    ReaderPageFact.dimensions(width, height, sourceOrientation = orientation)
                } else {
                    ReaderPageFact(ReaderPageFactState.FAILED, sourceOrientation = captured.orientation)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Metadata failure keeps the ordinary image route readable and retryable.
            ReaderPageFact(ReaderPageFactState.FAILED, sourceOrientation = captured.orientation)
        }
        synchronized(contentLock) {
            if (mutableContentVersion.value == captured.presentationVersion && stream === openStream &&
                encodedVersion == captured.encodedVersion && retrySequence == captured.retrySequence
            ) {
                if (fact.width > 0 && fact.height > 0 && confirmedOrientation != fact.sourceOrientation) {
                    mutableContentVersion.value++
                    confirmedOrientation = fact.sourceOrientation
                }
                mutablePageFact.value = fact
                mutableContentFact.value =
                    ReaderPageContentFact(mutableContentVersion.value, fact, captured.retrySequence, encodedVersion)
            }
        }
    }

    private data class HeaderSnapshot(
        val openStream: () -> InputStream,
        val encodedVersion: Long,
        val presentationVersion: Long,
        val retrySequence: Long,
        val orientation: mihon.domain.reader.ReaderImageOrientation,
    )

    override var status: State
        get() = super.status
        set(value) {
            if (value !is State.Error) {
                sharedMaterializeError = null
            }
            super.status = value
            publishSharedState()
        }

    override var progress: Int
        get() = super.progress
        set(value) {
            super.progress = value
            if (status is State.DownloadImage) {
                publishSharedState()
            }
        }

    internal fun bindSharedState(publisher: (ReaderPageLoadState) -> Unit) {
        sharedStatePublisher = publisher
        publishSharedState()
    }

    internal fun unbindSharedState() {
        sharedStatePublisher = null
    }

    internal fun toSharedLoadState(): ReaderPageLoadState = when (val current = status) {
        State.Queue -> ReaderPageLoadState.Queued
        State.LoadPage -> ReaderPageLoadState.ResolvingImage
        State.DownloadImage -> ReaderPageLoadState.Downloading(progress.takeIf { it in 0..100 })
        State.Ready -> ReaderPageLoadState.Ready
        is State.Error -> ReaderPageLoadState.Error(sharedMaterializeError ?: AppError.Unknown(current.error))
    }

    internal fun failMaterialization(error: AppError, cause: Throwable) {
        sharedMaterializeError = error
        status = State.Error(cause)
    }

    private fun publishSharedState() {
        sharedStatePublisher?.invoke(toSharedLoadState())
    }

    private companion object {
        val nextContentSourceId = AtomicLong()
    }
}

fun ReaderPage.toSharedPageModel(): ReaderPageModel = ReaderPageModel(
    index = index,
    url = url,
    imageUrl = imageUrl,
)
