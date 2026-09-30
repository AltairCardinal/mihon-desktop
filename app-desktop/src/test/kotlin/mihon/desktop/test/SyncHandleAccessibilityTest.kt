package mihon.desktop.test

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Dimension
import java.awt.Point
import javax.accessibility.Accessible
import javax.accessibility.AccessibleComponent
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleState
import javax.accessibility.AccessibleStateSet

class SyncHandleAccessibilityTest {
    @Test
    fun `disposed accessible owner is skipped and cyclic children are visited once`() {
        val disposed = mockk<AccessibleContext> { every { accessibleStateSet } throws IllegalStateException("disposed") }
        val closedRoot = mockk<Accessible> { every { accessibleContext } returns disposed }
        assertTrue(focusedSyncAccessibleBounds(listOf(closedRoot)).isEmpty())
        val context = mockk<AccessibleContext> {
            every { accessibleStateSet } returns AccessibleStateSet()
            every { accessibleChildrenCount } returns 1
        }
        val root = mockk<Accessible> { every { accessibleContext } returns context }
        every { context.getAccessibleChild(0) } returns root
        assertTrue(focusedSyncAccessibleBounds(listOf(root)).isEmpty())
        verify(exactly = 1) { context.accessibleStateSet }
    }

    @Test
    fun `deep accessibility tree has bounded reads without recursive stack growth`() {
        var reads = 0
        fun node(depth: Int): Accessible = Accessible {
            object : AccessibleContext() {
                override fun getAccessibleRole(): javax.accessibility.AccessibleRole = error("role must not be read")
                override fun getAccessibleStateSet(): AccessibleStateSet { reads++; return AccessibleStateSet() }
                override fun getAccessibleIndexInParent() = 0
                override fun getAccessibleChildrenCount() = if (depth < 5000) 1 else 0
                override fun getAccessibleChild(index: Int) = node(depth + 1)
                override fun getLocale() = java.util.Locale.ROOT
            }
        }
        assertTrue(focusedSyncAccessibleBounds(listOf(node(0))).isEmpty())
        assertTrue(reads <= 4096)
    }

    @Test
    fun `handle matches actual focused wrapper without reading names or editable data`() {
        val component = mockk<AccessibleComponent> {
            every { locationOnScreen } returns Point(120, 200)
            every { size } returns Dimension(32, 48)
        }
        val states = AccessibleStateSet(arrayOf(AccessibleState.FOCUSED))
        val context = mockk<AccessibleContext> {
            every { accessibleStateSet } returns states
            every { accessibleComponent } returns component
            every { accessibleChildrenCount } returns 0
        }
        val root = mockk<Accessible> { every { accessibleContext } returns context }
        val bounds = focusedSyncAccessibleBounds(listOf(root))
        assertTrue(syncHandleFocused(120f, 222f, 32f, 4f, bounds))
        assertFalse(syncHandleFocused(121f, 500f, 32f, 4f, bounds))
        assertFalse(syncHandleFocused(0f, 0f, 1024f, 768f, bounds))
        states.remove(AccessibleState.FOCUSED)
        assertTrue(focusedSyncAccessibleBounds(listOf(root)).isEmpty())
        verify(exactly = 0) { context.accessibleName }
        verify(exactly = 0) { context.accessibleValue }
        verify(exactly = 0) { context.accessibleText }
        verify(exactly = 0) { context.accessibleRole }
    }
}
