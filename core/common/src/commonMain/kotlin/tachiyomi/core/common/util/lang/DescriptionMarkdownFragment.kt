package tachiyomi.core.common.util.lang

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType

const val DESCRIPTION_MARKDOWN_IMAGE_TAG = "MARKDOWN_INLINE_IMAGE"

sealed interface DescriptionMarkdownFragment {
    data class ImageLink(val url: String, val altText: String) : DescriptionMarkdownFragment
    data class Literal(val text: String) : DescriptionMarkdownFragment
}

/** Description-only AST policy shared by both platform annotators. */
fun descriptionMarkdownFragment(
    content: String,
    node: ASTNode,
    loadImages: Boolean,
    nodeText: (ASTNode) -> String,
): DescriptionMarkdownFragment? {
    if (!loadImages && node.type == MarkdownElementTypes.IMAGE) {
        val link = node.findChildOfType(MarkdownElementTypes.INLINE_LINK)
        val destination = link?.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)
            ?: link?.findChildOfType(MarkdownElementTypes.AUTOLINK)
                ?.findChildOfType(MarkdownTokenTypes.AUTOLINK)
            ?: return null
        val text = link?.findChildOfType(MarkdownElementTypes.LINK_TITLE)
            ?: link?.findChildOfType(MarkdownElementTypes.LINK_TEXT)
        val alt = text?.findChildOfType(MarkdownTokenTypes.TEXT)?.let(nodeText).orEmpty()
        return DescriptionMarkdownFragment.ImageLink(nodeText(destination), alt)
    }
    if (node.type == MarkdownTokenTypes.HTML_TAG) {
        return DescriptionMarkdownFragment.Literal(content.substring(node.startOffset, node.endOffset))
    }
    return null
}
