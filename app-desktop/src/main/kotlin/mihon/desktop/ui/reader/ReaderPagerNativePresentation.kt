package mihon.desktop.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsPropertyKey
import mihon.desktop.ui.reader.presentation.DisplaySlotId
import mihon.desktop.ui.reader.presentation.DisplayUnit
import mihon.desktop.ui.reader.presentation.DisplayUnitId
import mihon.desktop.ui.reader.presentation.VisiblePageSet
import mihon.domain.reader.ReaderDualPageLocationIntent
import mihon.domain.reader.ReaderDualPageViewportTicket
import mihon.domain.reader.ReaderPageSideHint
import mihon.domain.reader.session.ReaderPageSession

/** Binds the native presenter's lifetime to the shared owner's original capability. */
internal class ReaderPagerPresentationBinding(
    val epoch: Any,
    val model: ReaderScreenModel,
    val geometryRevision: Long = model.state.value.dualGeometryRevision,
) {
    val currentGeometry get() = geometryRevision == model.state.value.dualGeometryRevision
    val logicalLocation get() = model.state.value.dualPageLocation
}

internal val ReaderPagerNativePresentationKey =
    SemanticsPropertyKey<ReaderPagerNativePresentation>("ReaderPagerNativePresentation")

internal val LocalReaderNativeWindow = staticCompositionLocalOf<java.awt.Window?> { null }

@Composable
internal fun Modifier.readerMountedGeometry(binding: ReaderPagerPresentationBinding?): Modifier {
    val density = LocalDensity.current.density
    val window = LocalReaderNativeWindow.current
    val publisher = remember(binding?.model) { Any() }
    DisposableEffect(binding?.model, publisher) {
        onDispose {
            binding?.model?.let { mihon.desktop.test.state.readerState.clearGeometry(it, publisher) }
        }
    }
    return onGloballyPositioned { coordinates ->
        binding?.model?.let { model ->
            mihon.desktop.test.state.readerState.updateGeometry(
                model,
                publisher,
                mihon.desktop.test.state.ReaderMountedGeometry(coordinates.boundsInWindow(), density, window),
            )
        }
    }
}

/** A real interaction may navigate only through the native units captured when it began. */
internal data class ReaderPagerNavigationIntent(
    val units: List<DisplayUnitId>,
    val origin: Int,
    val target: Int? = null,
    val dragReleased: Boolean = false,
    val logicalLocation: ReaderDualPageLocationIntent? = null,
) {
    fun selects(
        index: Int,
        unit: DisplayUnitId,
        currentLocation: ReaderDualPageLocationIntent? = null,
    ): Boolean =
        logicalLocation == currentLocation &&
            units.getOrNull(index) == unit && (target?.let { it == index } ?: (index != origin))

    fun finished(index: Int): Boolean = target?.let { it == index } ?: dragReleased
}

internal class ReaderPagerNativePresentation(
    private var unit: DisplayUnit,
    private var visible: VisiblePageSet,
    private var binding: ReaderPagerPresentationBinding?,
    private val legacyCallback: (VisiblePageSet) -> Unit,
) {
    private var ticket: ReaderDualPageViewportTicket? = null
    private var active = false
    private var idle = false
    private var reported = false
    private val rendered = mutableSetOf<DisplaySlotId>()
    private val expected get() = unit.slots.filter { it.page != null }.mapTo(mutableSetOf()) { it.id }
    private var selected = true
    private var userNavigation = false
    private var navigationLocation: ReaderDualPageLocationIntent? = binding?.logicalLocation
    private var exposed = true
    private var reportedPages: Set<mihon.domain.reader.session.ReaderPageId> = emptySet()
    val frameId get() = ticket?.frameId
    private val observationMountId = if (mihon.desktop.test.state.applicationState.testMode) {
        mihon.desktop.test.state.readerState.nextDrawMountId()
    } else {
        0L
    }

    internal fun canSampleGeometry(capture: Long): Boolean = binding?.model?.let {
        mihon.desktop.test.state.readerState.acceptsSlotDraw(it, capture)
    } == true

    internal fun observeImageDraw(
        slot: DisplaySlotId,
        command: ReaderBaseImageDraw,
        identity: mihon.desktop.reader.DesktopReaderPresentationImageSlotIdentity,
        decodeKey: mihon.domain.reader.ReaderPageDecodeKey,
    ) {
        val current = binding ?: return
        val capability = ticket ?: return
        if (!active || !exposed || slot !in expected || slot.pageId != identity.pageId) return
        val state = current.model.state.value
        val index = unit.slots.indexOfFirst { it.id == slot }
        val position = if (unit.slots.size == 1) {
            "FULL"
        } else if (index == 0) {
            "LEFT"
        } else {
            "RIGHT"
        }
        mihon.desktop.test.state.readerState.recordSlotDraw(
            current.model,
            command.capture,
            mihon.desktop.test.state.ReaderSlotDrawRecord(
                sourcePageIndex = identity.pageId.sourcePageIndex,
                chapterId = identity.pageId.chapterId.value,
                generation = identity.generation,
                readerGeneration = state.session.generation,
                frameMatchesAssetGeneration = capability.frameId.generation == identity.generation,
                locationSequence = state.dualPageLocation?.sequence,
                ticketLocationSequence = capability.locationSequence,
                frameId = capability.frameId.toString(),
                slot = position,
                nativeMountId = observationMountId,
                mountId = command.mountId,
                assetIdentity = command.bitmapIdentity,
                attemptGeneration = identity.attemptGeneration,
                contentIdentity = decodeKey.contentKey.hashCode(),
                sourceRect = command.sourceRect,
                destinationRect = command.destinationRect,
                rootQuad = command.rootQuad,
                imageLayoutQuad = command.layoutQuad,
                clipCanvasTransform = command.relativeTransform,
                monotonicNanos = command.monotonicNanos,
                level = command.level,
                canvasScope = "image-clip-layer-local",
                coordinatePhase = command.coordinatePhase,
                composeFrameNanos = command.composeFrameNanos,
                lastActualCommand = command.lastActualCommand,
                lastLayoutCallbackMonotonicNanos = command.lastLayoutCallbackMonotonicNanos,
            ),
        )
    }

    fun update(unit: DisplayUnit, visible: VisiblePageSet, binding: ReaderPagerPresentationBinding?) {
        this.unit = unit
        this.visible = visible
        this.binding = binding
        rendered.retainAll(expected)
    }

    fun select(
        userNavigation: Boolean,
        selected: Boolean,
        logicalLocation: ReaderDualPageLocationIntent? = binding?.logicalLocation,
    ) {
        if (!selected) {
            this.selected = false
            return
        }
        // A logical request precedes the native pager's movement. Keep the outgoing image's
        // geometry capability until its actual viewport leaves; its old sequence cannot read.
        if (!userNavigation && binding?.logicalLocation?.pageId !in visible.pageIds) return
        if (active && (!this.selected || ticket?.locationSequence != binding?.logicalLocation?.sequence)) {
            begin(userNavigation, selected, logicalLocation)
        }
    }

    internal fun describe(): String = "active=$active, exposed=$exposed, selected=$selected, idle=$idle, " +
        "reported=$reported, drawn=$rendered/$expected, " +
        "ticket=${ticket?.locationSequence}/${ticket?.layoutRevision}/${ticket?.readingEligible}, " +
        "geometry=${binding?.geometryRevision}/${binding?.model?.state?.value?.dualGeometryRevision}, " +
        "location=${binding?.logicalLocation}"

    fun begin(
        userNavigation: Boolean,
        selected: Boolean = true,
        logicalLocation: ReaderDualPageLocationIntent? = binding?.logicalLocation,
    ) {
        active = true
        this.userNavigation = userNavigation
        this.selected = selected
        navigationLocation = logicalLocation
        reportedPages = emptySet()
        val previous = ticket
        val next = binding?.takeIf { it.currentGeometry }
            ?.let { it.model.beginPagerViewport(visible, userNavigation, logicalLocation, selected) }
        if (next == null && previous?.let { binding?.model?.isCurrentDualViewport(it) } == true) return
        ticket = next
        if (previous != ticket) previous?.let { binding?.model?.releaseDualViewport(it) }
    }

    fun close() {
        active = false
        ticket?.let { binding?.model?.releaseDualViewport(it) }
        ticket = null
        rendered.clear()
    }

    fun setExposed(value: Boolean) {
        if (exposed == value) return
        exposed = value
        if (!value) {
            ticket?.let { binding?.model?.releaseDualViewport(it) }
            ticket = null
            rendered.clear()
        }
    }

    fun setIdle(value: Boolean) {
        idle = value
        reportIfReady()
    }

    fun drawn(slot: DisplaySlotId) {
        if (!active || !exposed || slot !in expected) return
        val page = slot.pageId ?: return
        if (binding != null && ticket?.let { binding?.model?.drawDualViewportPage(it, page) } != true) return
        rendered += slot
        reportIfReady()
    }

    /** The image invokes this before its first pixel, never from prefetch completion. */
    fun prepare(
        slot: DisplaySlotId,
        content: ReaderPageSession? = null,
        width: Int = 0,
        height: Int = 0,
        sideHint: ReaderPageSideHint = ReaderPageSideHint.UNKNOWN,
    ): Boolean {
        if (!active || !exposed || slot !in expected) return false
        val model = binding?.model ?: return true
        val page = slot.pageId ?: return false
        if (content != null && !model.isCurrentDualContent(content)) return false
        if (binding?.currentGeometry != true) {
            // Resizing revokes progress capabilities immediately, but current pixels need not
            // disappear while the next composition mounts its replacement geometry.
            return content != null && slot in rendered
        }
        if (ticket?.let { model.isDualViewportPageLocked(it, page) } == true) return true
        fun prepared(): Boolean = ticket?.let {
            if (content == null) {
                model.prepareDualViewportPage(it, page)
            } else {
                model.prepareDecodedDualViewportPage(it, content, width, height, sideHint)
            }
        } == true
        if (prepared()) return true
        if (ticket?.let(model::isCurrentDualViewport) == true) return false
        if (ticket != null && !selected && ticket?.locationSequence != binding?.logicalLocation?.sequence) return false
        val previous = ticket
        // Claim the successor's frame before retiring the old mount's release-only handle.
        ticket = model.beginPagerViewport(visible, userNavigation, navigationLocation, selected)
        if (previous != ticket) previous?.let(model::releaseDualViewport)
        return prepared()
    }

    private fun reportIfReady() {
        if (!active || !selected || !idle || rendered.isEmpty()) return
        if (binding != null) {
            val original = ticket ?: return
            val actual = rendered.mapNotNullTo(linkedSetOf()) { it.pageId }
            if (actual == reportedPages) return
            binding?.model?.settleDualPage(visible.copy(pageIds = actual, activePageId = actual.first()), original)
            reportedPages = actual
        } else {
            if (reported || rendered != expected) return
            legacyCallback(visible)
        }
        reported = true
    }
}

@Composable
internal fun rememberPagerNativePresentation(
    unit: DisplayUnit,
    visible: VisiblePageSet,
    binding: ReaderPagerPresentationBinding?,
    userNavigation: Boolean,
    idle: Boolean,
    onVisiblePagesChanged: (VisiblePageSet) -> Unit,
    selected: Boolean = true,
    navigationLocation: ReaderDualPageLocationIntent? = binding?.logicalLocation,
): ReaderPagerNativePresentation {
    // A pending partner's replacement must retain the mounted frame. Each preparation validates
    // its actual content identity, while binding changes revoke previous viewport capabilities.
    val anchor = unit.slots.mapNotNull { it.page?.id }.minByOrNull { it.sourcePageIndex }
    val presenter = remember(unit.id.mode, anchor ?: unit.id, binding?.epoch) {
        ReaderPagerNativePresentation(unit, visible, binding, onVisiblePagesChanged)
    }
    DisposableEffect(presenter) {
        presenter.begin(userNavigation, selected, navigationLocation)
        onDispose { presenter.close() }
    }
    SideEffect {
        presenter.update(unit, visible, binding)
        presenter.select(userNavigation, selected, navigationLocation)
        presenter.setIdle(idle)
    }
    return presenter
}
