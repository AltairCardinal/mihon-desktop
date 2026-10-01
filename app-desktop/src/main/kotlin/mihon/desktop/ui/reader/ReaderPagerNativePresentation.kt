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
import mihon.domain.reader.ReaderDualPageViewportTicket

/** Binds the native presenter's lifetime to the shared owner's original capability. */
internal class ReaderPagerPresentationBinding(val epoch: Any, val model: ReaderScreenModel)

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
) {
    fun selects(index: Int, unit: DisplayUnitId): Boolean =
        units.getOrNull(index) == unit && (target?.let { it == index } ?: (index != origin))

    fun finished(index: Int): Boolean = target?.let { it == index } ?: dragReleased
}

internal class ReaderPagerNativePresentation(
    private val unit: DisplayUnit,
    private val visible: VisiblePageSet,
    private val binding: ReaderPagerPresentationBinding?,
    private val legacyCallback: (VisiblePageSet) -> Unit,
) {
    private var ticket: ReaderDualPageViewportTicket? = null
    private var active = false
    private var idle = false
    private var reported = false
    private val rendered = mutableSetOf<DisplaySlotId>()
    private val expected = unit.slots.filter { it.page != null }.mapTo(mutableSetOf()) { it.id }

    internal fun describe(): String = "active=$active, idle=$idle, reported=$reported, drawn=$rendered/$expected, " +
        "ticket=${ticket?.locationSequence}/${ticket?.layoutRevision}/${ticket?.readingEligible}"

    fun begin(userNavigation: Boolean) {
        active = true
        ticket = binding?.model?.beginPagerViewport(visible, userNavigation)
    }

    fun close() {
        active = false
        ticket?.let { binding?.model?.releaseDualViewport(it) }
        ticket = null
        rendered.clear()
    }

    fun setIdle(value: Boolean) {
        idle = value
        reportIfReady()
    }

    fun drawn(slot: DisplaySlotId) {
        if (!active || slot !in expected) return
        rendered += slot
        reportIfReady()
    }

    private fun reportIfReady() {
        if (!active || !idle || reported || rendered != expected) return
        if (binding != null) {
            val original = ticket ?: return
            binding.model.settleDualPage(visible, original)
        } else {
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
): ReaderPagerNativePresentation {
    // Slot content includes the actual encoded reference and attempt, so old drawn callbacks cannot
    // report replacement pixels even when the stable display unit and logical page remain identical.
    val presenter = remember(unit.id, unit.slots, binding?.epoch) {
        ReaderPagerNativePresentation(unit, visible, binding, onVisiblePagesChanged)
    }
    DisposableEffect(presenter) {
        presenter.begin(userNavigation)
        onDispose { presenter.close() }
    }
    SideEffect { presenter.setIdle(idle) }
    return presenter
}
