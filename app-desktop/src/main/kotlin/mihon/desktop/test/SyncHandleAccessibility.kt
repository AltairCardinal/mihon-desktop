package mihon.desktop.test

import java.awt.Window
import java.awt.geom.Rectangle2D
import javax.accessibility.Accessible
import javax.accessibility.AccessibleState

/** The default Material handle focuses its parent wrapper. Read only public focus and geometry. */
internal fun focusedSyncAccessibleBounds(roots: List<Accessible>): List<Rectangle2D.Float> {
    val result = mutableListOf<Rectangle2D.Float>()
    var remaining = 4096
    val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Accessible, Boolean>())
    val pending = ArrayDeque<Accessible>()
    roots.take(4096).forEach(pending::addLast)
    while (pending.isNotEmpty() && remaining-- > 0) {
        val accessible = pending.removeFirst()
        if (!visited.add(accessible)) continue
        try {
            val context = accessible.accessibleContext ?: continue
            if (context.accessibleStateSet?.contains(AccessibleState.FOCUSED) == true) {
                context.accessibleComponent?.let { component ->
                    val point = component.locationOnScreen
                    val size = component.size
                    if (size.width > 0 && size.height > 0) {
                        result += Rectangle2D.Float(point.x.toFloat(), point.y.toFloat(), size.width.toFloat(), size.height.toFloat())
                    }
                }
            }
            val budget = (remaining - pending.size).coerceAtLeast(0)
            repeat(context.accessibleChildrenCount.coerceIn(0, budget)) { index ->
                context.getAccessibleChild(index)?.takeUnless { it in visited }?.let(pending::addLast)
            }
        } catch (_: RuntimeException) {
            // Accessible owners can disappear while the modal is being disposed.
        }
    }
    return result
}

internal fun syncFocusWindows(bound: Window): List<Window> =
    Window.getWindows().filter { candidate ->
        if (candidate === bound) true else if (!candidate.isFocused) false else {
            var owner = candidate.owner
            var depth = 0
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Window, Boolean>())
            var owned = false
            while (owner != null && depth++ < 64 && seen.add(owner)) {
                if (owner === bound) {
                    owned = true
                    break
                }
                owner = owner.owner
            }
            owned
        }
    }

internal fun syncHandleFocused(x: Float, y: Float, width: Float, height: Float, focused: List<Rectangle2D.Float>): Boolean =
    focused.any { bounds ->
        // AWT rounds to integer points. Matching width and centre excludes a focused window/root.
        kotlin.math.abs(bounds.width - width) <= 1f &&
            kotlin.math.abs(bounds.centerX - (x + width / 2)) <= 1.0 &&
            kotlin.math.abs(bounds.centerY - (y + height / 2)) <= 1.0 && bounds.height >= height
    }
