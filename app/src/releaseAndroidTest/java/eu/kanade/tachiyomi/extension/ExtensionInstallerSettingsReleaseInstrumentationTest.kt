package eu.kanade.tachiyomi.extension

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Black-box release UI contract: no Compose test runtime or calls into optimized preference internals. */
class ExtensionInstallerSettingsReleaseInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test
    fun releaseInstallerOffersPrivateAndPersistsSelection() {
        ExtensionReleaseParityInstrumentationTest.verifyReleaseArtifactBeforeChangingFixtures()
        val context = instrumentation.targetContext
        assertEquals(
            "Run this UI contract with the English locale",
            "en",
            context.resources.configuration.locales[0].language,
        )
        val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch)
        click(scrollTo("More"))
        click(scrollTo("Settings"))
        click(scrollTo("Advanced"))
        click(scrollTo("Installer"))

        val original = listOf("Legacy", "PackageInstaller", "Shizuku", "Private")
            .first { label -> findText(label)?.let(::clickableParent)?.isChecked == true }
        val privateOption = awaitText("Private")
        assertNotNull("The real release Installer dialog must offer Private", privateOption)
        var changed = false
        try {
            click(checkNotNull(privateOption))
            changed = true
            // Leave the screen and recreate its preference UI before checking persisted feedback.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            click(scrollTo("Advanced"))
            click(scrollTo("Installer"))
            assertTrue(
                "Reopened release Installer dialog must select Private",
                clickableParent(checkNotNull(awaitText("Private"))).isChecked,
            )
        } finally {
            if (changed) {
                val originalOption = findText(original) ?: run {
                    click(scrollTo("Installer"))
                    checkNotNull(awaitText(original))
                }
                click(originalOption)
            } else {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            }
        }
    }

    private fun awaitText(text: String): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            findText(text)?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        return null
    }

    private fun scrollTo(text: String): AccessibilityNodeInfo {
        awaitText(text)?.let { return it }
        repeat(15) {
            val scroll = nodes(automation.rootInActiveWindow).firstOrNull { it.isScrollable }
            checkNotNull(scroll) { "No scrollable production UI while looking for $text" }
            scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(250)
            findText(text)?.let { return it }
        }
        error("Production UI did not expose $text")
    }

    private fun findText(text: String): AccessibilityNodeInfo? =
        nodes(automation.rootInActiveWindow).firstOrNull {
            it.text?.toString() == text && it.isVisibleToUser &&
                generateSequence(it) { node -> node.parent }.any { node -> node.isClickable }
        }

    private fun clickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable) {
            current = checkNotNull(current.parent) { "No clickable ancestor for ${node.text}" }
        }
        return current
    }

    private fun click(node: AccessibilityNodeInfo) {
        assertTrue(
            "Production UI click must be handled",
            clickableParent(node).performAction(AccessibilityNodeInfo.ACTION_CLICK),
        )
        SystemClock.sleep(300)
    }

    private fun nodes(root: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (root != null) {
            yield(root)
            for (index in 0 until root.childCount) yieldAll(nodes(root.getChild(index)))
        }
    }
}
