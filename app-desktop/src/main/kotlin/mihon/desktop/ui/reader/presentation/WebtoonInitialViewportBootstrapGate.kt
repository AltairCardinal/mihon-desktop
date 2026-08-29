package mihon.desktop.ui.reader.presentation

import mihon.domain.reader.session.ReaderPageId

/** One-shot bootstrap used only before LazyColumn can report its first real settled viewport. */
internal class WebtoonInitialViewportBootstrapGate {
    private data class BootstrapIdentity(
        val currentPageId: ReaderPageId,
        val displayUnitId: DisplayUnitId,
    )

    private val emitted = mutableSetOf<BootstrapIdentity>()
    private var settled = false

    fun take(
        presentation: ReaderPresentationSnapshot,
        currentPageId: ReaderPageId,
        currentDisplayUnitId: DisplayUnitId?,
        initialAnchor: WebtoonScrollAnchor?,
    ): WebtoonViewportUpdate? {
        if (settled || currentDisplayUnitId != null || initialAnchor != null || presentation.displayUnits.isEmpty()) {
            return null
        }
        val initialIndex = presentation.firstDisplayUnitIndex(currentPageId)
            .coerceAtLeast(0)
            .coerceIn(presentation.displayUnits.indices)
        val initialUnit = presentation.displayUnits[initialIndex]
        if (!emitted.add(BootstrapIdentity(currentPageId, initialUnit.id))) return null
        val visiblePages = presentation.visiblePages(initialUnit.id)
        return WebtoonViewportUpdate(
            visiblePages = visiblePages.copy(
                activePageId = currentPageId.takeIf { it in visiblePages.pageIds },
            ),
            anchor = WebtoonScrollAnchor(
                displayUnitId = initialUnit.id,
                scrollOffset = 0,
            ),
        )
    }

    fun onSettledViewport(update: WebtoonViewportUpdate) {
        settled = true
    }
}
