package mihon.desktop.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import mihon.desktop.reader.DesktopReaderPresentationImageHolder
import mihon.desktop.reader.DesktopReaderPresentationImageOwner
import mihon.desktop.reader.DesktopReaderPresentationImageSlotIdentity
import mihon.desktop.reader.DesktopReaderPresentationImageState
import mihon.desktop.reader.DesktopReaderAnimatedPresentationImageHolder
import mihon.desktop.reader.DesktopReaderAnimatedPresentationImageSnapshot
import mihon.desktop.reader.DesktopReaderAnimationDrawToken
import mihon.desktop.reader.DesktopReaderImageAssetLease
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.session.ReaderPageSession

internal data class ReaderPresentationImage(
    val holder: DesktopReaderPresentationImageHolder,
    val state: DesktopReaderPresentationImageState,
    val animatedHolder: DesktopReaderAnimatedPresentationImageHolder? = null,
    val animatedState: DesktopReaderAnimatedPresentationImageSnapshot? = null,
) {
    val animationDrawToken: DesktopReaderAnimationDrawToken?
        get() = animatedState?.drawToken

    val renderedAnimationFrameIndex: Int?
        get() = animatedState?.readyKey?.frameIndex
}

internal data class ReaderPageContextMenuBinding(
    val imageLeaseProvider: () -> DesktopReaderImageAssetLease?,
    val splitHalf: PageSplitHalf?,
    val sourceBounds: PixelBounds?,
)

internal fun readerPageContextMenuBinding(
    presentationImage: ReaderPresentationImage,
): ReaderPageContextMenuBinding {
    val identity = presentationImage.holder.identity
    return ReaderPageContextMenuBinding(
        imageLeaseProvider = {
            presentationImage.holder.retainReadyAssetForRender(identity)
        },
        splitHalf = identity.splitHalf,
        sourceBounds = identity.sourceBounds,
    )
}

/** Binds one composed presentation slot to the runtime's stable decoded-image lease. */
@Composable
internal fun rememberReaderPresentationImage(
    owner: DesktopReaderPresentationImageOwner,
    page: ReaderPageSession,
    generation: Long,
    splitHalf: PageSplitHalf? = null,
    sourceBounds: PixelBounds? = null,
): ReaderPresentationImage {
    val identity = remember(page.id, generation, splitHalf, sourceBounds) {
        DesktopReaderPresentationImageSlotIdentity(
            pageId = page.id,
            generation = generation,
            splitHalf = splitHalf,
            sourceBounds = sourceBounds,
        )
    }
    val decodeKey = remember(page.id, page.encodedPageRef, generation) {
        ReaderPageDecodeKey(
            contentKey = ReaderPageContentOpenRequest(
                pageId = page.id,
                generation = generation,
                encodedPageRef = requireNotNull(page.encodedPageRef) {
                    "Ready reader page has no encoded content: ${page.id}"
                },
            ),
            purpose = PageDecodePurpose.FULL_PAGE,
            maxWidth = PRESENTATION_DECODE_BOUND,
            maxHeight = PRESENTATION_DECODE_BOUND,
        )
    }
    val holder = remember(owner, identity, decodeKey) {
        owner.createHolder(identity, decodeKey)
    }
    val state by holder.state.collectAsState()
    DisposableEffect(holder) {
        holder.acquire()
        onDispose(holder::close)
    }
    val animationMetadata = (state as? DesktopReaderPresentationImageState.Ready)?.asset?.animationMetadata
    val animatedHolder = remember(holder, animationMetadata) {
        animationMetadata?.let { holder.createAnimatedHolder() }
    }
    val animatedState = if (animatedHolder == null) {
        null
    } else {
        val current by animatedHolder.state.collectAsState()
        current
    }
    DisposableEffect(animatedHolder, animationMetadata) {
        if (animatedHolder != null && animationMetadata != null) {
            animatedHolder.startPlayback(animationMetadata)
        }
        onDispose { animatedHolder?.close() }
    }
    return ReaderPresentationImage(holder, state, animatedHolder, animatedState)
}

private const val PRESENTATION_DECODE_BOUND = 2_048
