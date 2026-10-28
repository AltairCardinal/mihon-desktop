package mihon.desktop.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.staticCompositionLocalOf
import mihon.desktop.reader.DesktopReaderPresentationImageOwner
import mihon.desktop.reader.DesktopReaderPresentationImageSlotIdentity
import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.session.ReaderPageSession

internal val LocalReaderPageImageContent = staticCompositionLocalOf<ReaderPageImageContentRegistry?> { null }

/** Moves live image leases and prepared pixels when the pager changes between one and two pages. */
internal class ReaderPageImageContentRegistry {
    private data class Key(
        val identity: DesktopReaderPresentationImageSlotIdentity,
        val decodeKey: ReaderPageDecodeKey,
    )

    private val entries = mutableMapOf<Key, Entry>()

    @Composable
    internal fun Content(arguments: ReaderPageImageContentArguments) {
        val key = Key(
            DesktopReaderPresentationImageSlotIdentity(
                pageId = arguments.page.id,
                generation = arguments.generation,
                attemptGeneration = arguments.page.attemptGeneration,
                splitHalf = arguments.splitHalf,
                sourceBounds = arguments.sourceBounds,
            ),
            readerPresentationDecodeKey(arguments.page, arguments.generation),
        )
        entries.getOrPut(key) { Entry(key) }.content(arguments)
    }

    private inner class Entry(key: Key) {
        val content = movableContentOf<ReaderPageImageContentArguments> { arguments ->
            DisposableEffect(this@Entry) {
                onDispose {
                    // Retain only mounted slots, never an accumulating chapter-wide image cache.
                    if (entries[key] === this@Entry) entries.remove(key)
                }
            }
            ReaderPageImageContentBody(arguments)
        }
    }
}

internal class ReaderPageImageContentArguments(
    val owner: DesktopReaderPresentationImageOwner,
    val page: ReaderPageSession,
    val generation: Long,
    val splitHalf: PageSplitHalf?,
    val sourceBounds: PixelBounds?,
    val cropBorders: Boolean,
    val content: @Composable (ReaderPresentationImage, ReaderPresentationRenderedImage?) -> Unit,
)

@Composable
internal fun ReaderPageImageContent(
    owner: DesktopReaderPresentationImageOwner,
    page: ReaderPageSession,
    generation: Long,
    splitHalf: PageSplitHalf?,
    sourceBounds: PixelBounds?,
    cropBorders: Boolean,
    content: @Composable (ReaderPresentationImage, ReaderPresentationRenderedImage?) -> Unit,
) {
    val arguments = ReaderPageImageContentArguments(
        owner,
        page,
        generation,
        splitHalf,
        sourceBounds,
        cropBorders,
        content,
    )
    val registry = LocalReaderPageImageContent.current
    if (registry == null) {
        ReaderPageImageContentBody(arguments)
    } else {
        registry.Content(arguments)
    }
}

@Composable
private fun ReaderPageImageContentBody(arguments: ReaderPageImageContentArguments) {
    val image = rememberReaderPresentationImage(
        arguments.owner,
        arguments.page,
        arguments.generation,
        arguments.splitHalf,
        arguments.sourceBounds,
    )
    val renderedImage = rememberReaderPresentationRenderedImage(image, arguments.cropBorders)
    arguments.content(image, renderedImage)
}
