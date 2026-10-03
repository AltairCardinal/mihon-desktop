package tachiyomi.core.common.util.lang

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.parser.MarkdownParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownFlavourContractTest {
    @Test
    fun `shared source flavour preserves inline links images and table support`() {
        val types = types(
            "[Details](https://example.com) ![Cover](https://example.com/image.png)\n\n" +
                "| Name | Value |\n| --- | --- |\n| A | B |",
        )
        assertTrue(MarkdownElementTypes.INLINE_LINK in types)
        assertTrue(MarkdownElementTypes.IMAGE in types)
        assertTrue(GFMElementTypes.TABLE in types)
    }

    @Test
    fun `shared source flavour does not create HTML blocks or reference definitions`() {
        val types = types("<div>description</div>\n\n[reference]: https://example.com\n\n[reference]")
        assertFalse(MarkdownElementTypes.HTML_BLOCK in types)
        assertFalse(MarkdownElementTypes.LINK_DEFINITION in types)
    }

    @Test
    fun `description fragments preserve disabled image links alt and literal HTML`() {
        val content = "![Illustration](https://example.com/image.png) <b>literal</b>"
        fun flatten(node: ASTNode): List<ASTNode> = listOf(node) + node.children.flatMap(::flatten)
        val nodes = flatten(MarkdownParser(SimpleMarkdownFlavourDescriptor).buildMarkdownTreeFromString(content))
        val image = nodes.single { it.type == MarkdownElementTypes.IMAGE }
        val read: (ASTNode) -> String = { content.substring(it.startOffset, it.endOffset) }
        assertEquals(
            DescriptionMarkdownFragment.ImageLink("https://example.com/image.png", "Illustration"),
            descriptionMarkdownFragment(content, image, false, read),
        )
        assertNull(descriptionMarkdownFragment(content, image, true, read))
        val html = nodes.first { it.type == MarkdownTokenTypes.HTML_TAG }
        assertEquals(
            DescriptionMarkdownFragment.Literal("<b>"),
            descriptionMarkdownFragment(content, html, true, read),
        )
    }

    private fun types(content: String): List<IElementType> {
        fun flatten(node: ASTNode): List<ASTNode> = listOf(node) + node.children.flatMap(::flatten)
        return flatten(MarkdownParser(SimpleMarkdownFlavourDescriptor).buildMarkdownTreeFromString(content)).map {
            it.type
        }
    }
}
