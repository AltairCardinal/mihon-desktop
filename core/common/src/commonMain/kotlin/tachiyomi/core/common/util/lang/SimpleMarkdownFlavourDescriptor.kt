package tachiyomi.core.common.util.lang

import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.flavours.commonmark.CommonMarkMarkerProcessor
import org.intellij.markdown.flavours.gfm.table.GitHubTableMarkerProvider
import org.intellij.markdown.parser.MarkerProcessor
import org.intellij.markdown.parser.MarkerProcessorFactory
import org.intellij.markdown.parser.ProductionHolder
import org.intellij.markdown.parser.constraints.CommonMarkdownConstraints
import org.intellij.markdown.parser.constraints.MarkdownConstraints
import org.intellij.markdown.parser.markerblocks.MarkerBlockProvider
import org.intellij.markdown.parser.markerblocks.providers.AtxHeaderProvider
import org.intellij.markdown.parser.markerblocks.providers.BlockQuoteProvider
import org.intellij.markdown.parser.markerblocks.providers.CodeBlockProvider
import org.intellij.markdown.parser.markerblocks.providers.CodeFenceProvider
import org.intellij.markdown.parser.markerblocks.providers.HorizontalRuleProvider
import org.intellij.markdown.parser.markerblocks.providers.ListMarkerProvider
import org.intellij.markdown.parser.markerblocks.providers.SetextHeaderProvider

object SimpleMarkdownFlavourDescriptor : CommonMarkFlavourDescriptor() {
    override val markerProcessorFactory: MarkerProcessorFactory = SimpleMarkdownProcessFactory
}

private object SimpleMarkdownProcessFactory : MarkerProcessorFactory {
    override fun createMarkerProcessor(productionHolder: ProductionHolder): MarkerProcessor<*> {
        return SimpleMarkdownMarkerProcessor(productionHolder, CommonMarkdownConstraints.BASE)
    }
}

/**
 * Like `CommonMarkFlavour`, but with html blocks and reference links removed and
 * table support added
 */
private class SimpleMarkdownMarkerProcessor(
    productionHolder: ProductionHolder,
    constraints: MarkdownConstraints,
) : CommonMarkMarkerProcessor(productionHolder, constraints) {
    private val markerBlockProviders = listOf(
        CodeBlockProvider(),
        HorizontalRuleProvider(),
        CodeFenceProvider(),
        SetextHeaderProvider(),
        BlockQuoteProvider(),
        ListMarkerProvider(),
        AtxHeaderProvider(),
        GitHubTableMarkerProvider(),
    )

    override fun getMarkerBlockProviders(): List<MarkerBlockProvider<StateInfo>> {
        return markerBlockProviders
    }
}
