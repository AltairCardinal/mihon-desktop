package mihon.desktop.ui.library

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownInlineContent
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import mihon.desktop.image.LocalDesktopSourceImageId
import mihon.desktop.image.desktopSourceImageModel
import org.intellij.markdown.MarkdownTokenTypes
import tachiyomi.core.common.util.lang.DESCRIPTION_MARKDOWN_IMAGE_TAG
import tachiyomi.core.common.util.lang.DescriptionMarkdownFragment
import tachiyomi.core.common.util.lang.SimpleMarkdownFlavourDescriptor
import tachiyomi.core.common.util.lang.descriptionMarkdownFragment

internal val DesktopDescriptionImageState =
    androidx.compose.ui.semantics.SemanticsPropertyKey<String>("DesktopDescriptionImageState")

/** Platform adapter for the same renderer, preserving the source's authenticated Coil route. */
@Composable
internal fun DesktopMarkdownDescription(
    content: String,
    sourceId: Long,
    loadImages: Boolean,
    modifier: Modifier = Modifier,
) {
    val linkStyle = getMarkdownLinkStyle().toSpanStyle()
    val annotator =
        remember(loadImages, linkStyle) {
            markdownAnnotator(
                annotate = { text, node ->
                    when (
                        val fragment =
                            descriptionMarkdownFragment(text, node, loadImages) {
                                it.getUnescapedTextInNode(text)
                            }
                    ) {
                        is DescriptionMarkdownFragment.ImageLink -> {
                            withLink(LinkAnnotation.Url(fragment.url)) {
                                pushStyle(linkStyle)
                                appendInlineContent(DESCRIPTION_MARKDOWN_IMAGE_TAG)
                                append(fragment.altText)
                                pop()
                            }
                            true
                        }
                        is DescriptionMarkdownFragment.Literal -> {
                            append(fragment.text)
                            true
                        }
                        null -> {
                            false
                        }
                    }
                },
                config = markdownAnnotatorConfig(eolAsNewLine = true),
            )
        }
    CompositionLocalProvider(LocalDesktopSourceImageId provides sourceId) {
        Markdown(
            markdownState = rememberMarkdownState(content, flavour = SimpleMarkdownFlavourDescriptor, immediate = true),
            components = descriptionComponents,
            inlineContent = descriptionInlineContent(),
            annotator = annotator,
            colors = descriptionColors(),
            typography = descriptionTypography(),
            imageTransformer =
            remember(loadImages) {
                if (loadImages) SourceMarkdownImageTransformer else NoOpImageTransformerImpl()
            },
            modifier = modifier,
        )
    }
}

private object SourceMarkdownImageTransformer : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData {
        val context = LocalPlatformContext.current
        val sourceId = LocalDesktopSourceImageId.current
        val request =
            remember(link, sourceId, context) {
                ImageRequest
                    .Builder(
                        context,
                    ).data(desktopSourceImageModel(link, sourceId))
                    .size(coil3.size.Size.ORIGINAL)
                    .build()
            }
        val painter = rememberAsyncImagePainter(request)
        val state by painter.state.collectAsState()
        return ImageData(
            painter = painter,
            modifier =
            Modifier.semantics {
                this[DesktopDescriptionImageState] =
                    when (state) {
                        is AsyncImagePainter.State.Success -> "ready"
                        is AsyncImagePainter.State.Error -> "failed"
                        else -> "loading"
                    }
            },
        )
    }

    @Composable
    override fun intrinsicSize(painter: Painter): Size = Coil3ImageTransformerImpl.intrinsicSize(painter)
}

@Composable
@ReadOnlyComposable
private fun descriptionColors(): MarkdownColors {
    val codeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    return DefaultMarkdownColors(
        text = MaterialTheme.colorScheme.onSurface,
        codeBackground = codeBackground,
        inlineCodeBackground = codeBackground,
        dividerColor = MaterialTheme.colorScheme.outlineVariant,
        tableBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
    )
}

@Composable
@ReadOnlyComposable
private fun getMarkdownLinkStyle() =
    MaterialTheme.typography.bodyMedium.copy(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )

@Composable
@ReadOnlyComposable
private fun descriptionTypography(): MarkdownTypography {
    val link = getMarkdownLinkStyle()
    return DefaultMarkdownTypography(
        h1 = MaterialTheme.typography.headlineMedium,
        h2 = MaterialTheme.typography.headlineSmall,
        h3 = MaterialTheme.typography.titleLarge,
        h4 = MaterialTheme.typography.titleMedium,
        h5 = MaterialTheme.typography.titleSmall,
        h6 = MaterialTheme.typography.bodyLarge,
        text = MaterialTheme.typography.bodyMedium,
        code = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        inlineCode = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        quote = MaterialTheme.typography.bodyMedium.plus(SpanStyle(fontStyle = FontStyle.Italic)),
        paragraph = MaterialTheme.typography.bodyMedium,
        ordered = MaterialTheme.typography.bodyMedium,
        bullet = MaterialTheme.typography.bodyMedium,
        list = MaterialTheme.typography.bodyMedium,
        textLink = TextLinkStyles(style = link.toSpanStyle()),
        table = MaterialTheme.typography.bodyMedium,
    )
}

private val descriptionComponents =
    markdownComponents(
        custom = { type, model ->
            if (type == MarkdownTokenTypes.HTML_TAG) {
                MarkdownText(
                    content = model.content.substring(model.node.startOffset, model.node.endOffset),
                    style = model.typography.text,
                )
            }
        },
    )

@Composable
@ReadOnlyComposable
private fun descriptionInlineContent() =
    DefaultMarkdownInlineContent(
        inlineContent =
        mapOf(
            DESCRIPTION_MARKDOWN_IMAGE_TAG to
                InlineTextContent(
                    placeholder =
                    Placeholder(
                        width = MaterialTheme.typography.bodyMedium.fontSize * 1.25,
                        height = MaterialTheme.typography.bodyMedium.fontSize * 1.25,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                    ),
                    children = {
                        Icon(
                            Icons.Outlined.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                ),
        ),
    )
