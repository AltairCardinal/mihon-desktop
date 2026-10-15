package eu.kanade.tachiyomi.ui.reader

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Opt-in synthetic data in a task-owned emulator; original DI, loader, dialog and Activity. */
class ReaderMissingSourceExitNativeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val missingSourceId = 987654321012345L

    @Test
    fun missingSourceCanBeRetriedThenClosedWithoutRestarting() = withMissingSourceReader { reader ->
        val originalError = reader.viewModel.state.value.dialog
        click(context.stringResource(MR.strings.action_retry))
        await("retry must return a new source error") {
            val current = reader.viewModel.state.value.dialog
            current is ReaderViewModel.Dialog.ChapterError && current !== originalError
        }
        assertFalse(reader.isFinishing)
        click(context.stringResource(MR.strings.action_close))
        await("Close must finish the failed reader") { reader.isFinishing }
    }

    @Test
    fun systemBackExitsMissingSourceDialogAndReader() = withMissingSourceReader { reader ->
        await("the actual error dialog must own focus") {
            nodes().any { it.text?.toString() == context.stringResource(MR.strings.action_retry) }
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await("system Back must finish the failed reader") { reader.isFinishing }
    }

    private fun withMissingSourceReader(action: (ReaderActivity) -> Unit) = runBlocking {
        check(context.packageName == "app.mihon.desktop.fork.dev")
        check(Build.HARDWARE == "ranchu") { "Only run this synthetic fixture in an isolated emulator" }
        check(InstrumentationRegistry.getArguments().getString("readerMissingSourceFixture") == "true")
        val sources = Injekt.get<SourceManager>()
        withTimeout(15_000) { sources.isInitialized.first { it } }
        assertNull(sources.get(missingSourceId))
        val manga = Injekt.get<MangaRepository>().insertNetworkManga(
            listOf(
                Manga.create().copy(
                    source = missingSourceId,
                    url = "/missing-source-exit/${SystemClock.elapsedRealtimeNanos()}",
                    title = "Missing source exit fixture",
                    initialized = true,
                ),
            ),
        ).single()
        val chapter = Injekt.get<ChapterRepository>().addAll(
            listOf(
                Chapter.create().copy(
                    mangaId = manga.id,
                    url = "${manga.url}/chapter-1",
                    name = "Missing source chapter",
                    chapterNumber = 1.0,
                ),
            ),
        ).single()
        val reader = instrumentation.startActivitySync(
            ReaderActivity.newIntent(context, manga.id, chapter.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as ReaderActivity
        try {
            await("production chapter loading must report the missing source") {
                reader.viewModel.state.value.dialog is ReaderViewModel.Dialog.ChapterError &&
                    nodes().any { it.text?.contains(missingSourceId.toString()) == true }
            }
            assertNull(reader.viewModel.state.value.currentChapter)
            action(reader)
            assertTrue(reader.isFinishing)
        } finally {
            instrumentation.runOnMainSync { if (!reader.isFinishing) reader.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun click(label: String) {
        await("dialog button must be visible: $label") { nodes().any { it.text?.toString() == label } }
        var target = nodes().single { it.text?.toString() == label }
        while (!target.isClickable) target = checkNotNull(target.parent)
        assertTrue(target.isEnabled)
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        if (root.packageName?.toString() != context.packageName) return emptyList()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        val result = mutableListOf<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty() && result.size < 256) {
            val node = pending.removeFirst()
            result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::add)
        }
        return result
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(50)
        }
        assertTrue(message, predicate())
    }
}
