package mihon.history.fixture

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Rect
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.AndroidReaderProgressCoordinator
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerPageHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import mihon.data.sync.inbox.SyncInboxStore
import mihon.data.sync.runtime.SyncRuntime
import mihon.domain.sync.SyncBatchCodec
import mihon.domain.sync.SyncBatchDecodeResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.core.common.Constants
import tachiyomi.data.DatabaseHandler
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real native history entry and mounted reader; no replacement DI, reader or SQL mutation. */
class HistoryFormalReaderInstrumentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)

    @Test
    fun syncedSparseChapterIsReadableBeforeCatalogueAndRetainsActivation() = runBlocking {
        HistoryFormalInstrumentationTest().formalHistoryFixtureAbiIsCallable()
        val modePreference = Injekt.get<ReaderPreferences>().defaultReadingMode()
        val previous = modePreference.get()
        val wasSet = modePreference.isSet()
        try {
            // A fixed single-page fixture viewport; restore this profile's previous choice afterwards.
            modePreference.set(1)
            runScenario()
        } finally {
            if (wasSet) modePreference.set(previous) else modePreference.delete()
        }
    }

    private suspend fun runScenario() {
        val scenario = checkNotNull(InstrumentationRegistry.getArguments().getString("historyScenario"))
        require(scenario in setOf("success", "failure", "cache", "navigation"))
        control("/control/scenario/${if (scenario == "navigation") "cache" else scenario}")
        val mode = if (scenario in setOf("cache", "navigation")) "success" else scenario
        val work = "/hp02-history/$mode"
        val title = "Android sparse $mode"
        if (scenario == "navigation") {
            runNativeNavigation(work, title)
            return
        }
        if (scenario != "cache") seed(mode, title)
        val receipt = context.getSharedPreferences("history-hp02-formal-observation", Context.MODE_PRIVATE)
        if (scenario == "cache") {
            check(receipt.contains("successProcess")) { "A successful earlier native process is required" }
            check(
                receipt.getInt("successProcess", -1) != Process.myPid() ||
                    receipt.getLong("successProcessStart", -1) != Process.getStartElapsedRealtime(),
            ) {
                "Cache acceptance must run in another actual ART process"
            }
        }
        val before = database(work)
        assertEquals(if (scenario == "cache") 3 else 1, before.getInt("chapterCount"))
        assertEquals(if (scenario == "cache") "COMPLETE" else "UNKNOWN", before.getString("catalogState"))
        assertEquals(false, before.getBoolean("favorite"))
        assertEquals(1, before.getInt("historyCount"))
        assertEquals(false, before.getBoolean("read"))
        assertEquals(if (scenario == "cache") 2 else 1, before.getInt("page"))
        observe("$scenario-seeded", before)

        val monitor = instrumentation.addMonitor(ReaderActivity::class.java.name, null, false)
        var activity: ReaderActivity? = null
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    action = Constants.SHORTCUT_HISTORY
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
            val row = device.wait(Until.findObject(By.text(title)), 15_000)
            checkNotNull(row) { "Native history row did not appear: ${device.currentPackageName}" }
            row.click()
            activity = monitor.waitForActivityWithTimeout(15_000) as? ReaderActivity
            val reader = checkNotNull(activity) { "Actual HistoryTab did not start ReaderActivity" }
            val model = reader.viewModel
            await("selected reader page", 15_000) {
                model.state.value.currentChapter?.pages?.size == 4 &&
                    model.state.value.currentPage == before.getInt("page") + 1
            }
            val current = checkNotNull(model.state.value.currentChapter)
            val pages = checkNotNull(current.pages)
            assertEquals(before.getLong("chapterId"), current.chapter.id)
            assertEquals("$work/chapter/2", current.chapter.url)
            val opened = checkNotNull(field(model, "initialOpenContext"))
            assertEquals(before.getInt("page"), field(opened, "pageIndex"))
            val openedBaseline = checkNotNull(field(opened, "snapshot"))
            val openedHeads = field(openedBaseline, "heads") as Map<*, *>
            assertEquals(before.getInt("headCount"), openedHeads.values.sumOf { (it as List<*>).size })
            if (scenario != "cache") {
                openedHeads.values.flatMap { it as List<*> }.forEach { reference ->
                    val event = checkNotNull(field(checkNotNull(reference), "eventId"))
                    assertEquals("android-sender-$mode", field(event, "actorId"))
                }
            }
            val activation = checkNotNull(field(model, "readingActivation"))
            val session = checkNotNull(field(activation, "session"))
            assertReadableDraw(reader, pages[before.getInt("page")])
            if (scenario != "cache") {
                await("actual catalogue request entered", 5_000) {
                    control("/control/state", post = false).getInt("catalogCalls") >= 1
                }
            }
            val first = readerSnapshot(model, work)
            assertEquals(if (scenario == "cache") 0 else 1, first.getInt("catalogCalls"))
            if (scenario != "cache") {
                assertEquals(true, first.getBoolean("directoryHeld"))
                assertTrue(model.state.value.viewerChapters?.prevChapter == null)
                assertTrue(model.state.value.viewerChapters?.nextChapter == null)
            }
            observe("$scenario-current-readable", first)

            if (scenario != "cache") {
                // Native pager event, never model.onPageSelected or a direct navigation callback.
                device.click(device.displayWidth * 9 / 10, device.displayHeight / 2)
                await("native turn to page index 2", 10_000) { model.state.value.currentPage == 3 }
                assertReadableDraw(reader, pages[2])
                Injekt.get<AndroidReaderProgressCoordinator>().awaitAccepted(before.getLong("mangaId"))
                assertEquals(2, database(work).getInt("page"))
                observe("$scenario-turned-while-held", readerSnapshot(model, work))
                val sessionBaseline = field(session, "snapshot")
                control("/control/release")
                await("bounded one-shot catalogue completion", 10_000) {
                    (field(model, "catalogJob") as? Job)?.isCompleted == true
                }
                assertSame(activation, field(model, "readingActivation"))
                assertSame(session, field(checkNotNull(field(model, "readingActivation")), "session"))
                assertSame(sessionBaseline, field(session, "snapshot"))
                assertSame(current, model.state.value.currentChapter)
                assertSame(pages, model.state.value.currentChapter?.pages)
                assertSame(opened, field(model, "initialOpenContext"))
                assertSame(openedBaseline, field(opened, "snapshot"))
                assertEquals(3, model.state.value.currentPage)
            }
            val result = readerSnapshot(model, work)
            val window = checkNotNull(model.state.value.viewerChapters)
            if (scenario == "failure") {
                assertEquals(1, result.getInt("chapterCount"))
                assertEquals("UNKNOWN", result.getString("catalogState"))
                assertTrue(window.prevChapter == null && window.nextChapter == null)
            } else {
                assertEquals(3, result.getInt("chapterCount"))
                assertEquals("COMPLETE", result.getString("catalogState"))
                assertEquals("$work/chapter/1", window.prevChapter?.chapter?.url)
                assertEquals("$work/chapter/3", window.nextChapter?.chapter?.url)
            }
            assertEquals(if (scenario == "cache") 0 else 1, result.getInt("catalogCalls"))
            observe("$scenario-finished", result)
            if (scenario == "success") {
                check(
                    receipt.edit().putInt("successProcess", Process.myPid())
                        .putLong("successProcessStart", Process.getStartElapsedRealtime()).commit(),
                )
            }
            if (scenario == "cache") {
                // Only after cold-cache evidence: normal chapter navigation may legitimately change progress.
                navigateChapter(reader, work, "上一章", 1)
                assertNativeBoundary(reader, work, "上一章", "first")
                navigateChapter(reader, work, "下一章", 2)
                navigateChapter(reader, work, "下一章", 3)
                assertNativeBoundary(reader, work, "下一章", "last")
                navigateChapter(reader, work, "上一章", 2)
                Injekt.get<AndroidReaderProgressCoordinator>().awaitAccepted(before.getLong("mangaId"))
                assertEquals(0, control("/control/state", post = false).getInt("catalogCalls"))
                observe("native-navigation-finished", readerSnapshot(model, work))
            }
        } finally {
            control("/control/release")
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            instrumentation.removeMonitor(monitor)
        }
    }

    private suspend fun seed(mode: String, title: String) {
        val work = "/hp02-history/$mode"
        check(database(work).getLong("mangaId") == -1L) { "Fixed fixture already exists; never reseed previous data" }
        val key = JSONObject().put("type", "MANGA").put("sourceId", "9876543210").put("originalUrl", work)
        val chapter = JSONObject().put("type", "CHAPTER").put("sourceId", "9876543210")
            .put("originalUrl", "$work/chapter/2").put("parentUrl", work)
        // Frozen protocol key, independently encoded fixture data, not a replacement reducer/journal.
        val chapterKey = "7:CHAPTER10:98765432100:31:$work/chapter/221:$work"
        val now = System.currentTimeMillis()
        val position = JSONObject().put("chapterKey", chapterKey).put("pageIndex", 1).put("totalPages", 4)
        val effects = JSONArray()
            .put(
                JSONObject().put("effectId", "position").put("objectKey", key)
                    .put("field", "RESUME_POSITION").put("kind", "RESUME_POSITION")
                    .put("payload", position),
            )
            .put(
                JSONObject().put("effectId", "summary").put("objectKey", key)
                    .put("field", "READING_SUMMARY").put("kind", "READING_SUMMARY")
                    .put("payload", JSONObject().put("chapterKey", chapterKey).put("readAt", now)),
            )
        val batchId = "android-history-$mode"
        val event = JSONObject().put("protocolVersion", 1).put("spaceId", SPACE).put("generation", 1)
            .put("actorId", "android-sender-$mode").put("epoch", 1).put("seq", 1)
            .put("category", "READING").put("origin", "USER").put("occurredAt", now)
            .put("batchId", batchId).put("effects", effects)
        val body = JSONObject().put("protocolVersion", 1).put("spaceId", SPACE).put("generation", 1)
            .put("batchId", batchId).put("events", JSONArray().put(event))
            .put(
                "objects",
                JSONArray().put(JSONObject().put("objectKey", key).put("title", title))
                    .put(
                        JSONObject().put("objectKey", chapter).put("title", "Ch.2")
                            .put("chapterNumber", 2.0).put("sourceOrder", 1),
                    ),
            )
        val decoded = SyncBatchCodec.decode(body.toString())
        check(decoded is SyncBatchDecodeResult.Accepted) { "Fixed remote protocol rejected: $decoded" }
        check(SyncInboxStore(Injekt.get<DatabaseHandler>()).ingest(decoded.batch).accepted)
        val projector = Injekt.get<SyncRuntime>().projector
        repeat(10) {
            if (projector.project(SPACE, 1, 50, {}, { _, _ -> }) == 0) return
        }
        error("Production inbox projection did not settle")
    }

    private fun database(work: String): JSONObject {
        val state = JSONObject().put("mangaId", -1)
        SQLiteDatabase.openDatabase(context.getDatabasePath("tachiyomi.db").path, null, SQLiteDatabase.OPEN_READONLY)
            .use { db ->
                db.rawQuery("SELECT _id, favorite FROM mangas WHERE source = 9876543210 AND url = ?", arrayOf(work))
                    .use { manga ->
                        if (!manga.moveToFirst()) return state
                        val id = manga.getLong(0)
                        state.put("mangaId", id).put("favorite", manga.getInt(1) != 0)
                        check(!manga.moveToNext()) { "Duplicate fixture identity" }
                        db.rawQuery(
                            "SELECT _id, read, last_page_read FROM chapters WHERE manga_id = ? AND url = ?",
                            arrayOf(id.toString(), "$work/chapter/2"),
                        ).use {
                            check(it.moveToFirst())
                            state.put("chapterId", it.getLong(0))
                                .put("read", it.getInt(1) != 0).put("page", it.getInt(2))
                        }
                        db.rawQuery("SELECT count(*) FROM chapters WHERE manga_id = ?", arrayOf(id.toString())).use {
                            check(it.moveToFirst())
                            state.put("chapterCount", it.getInt(0))
                        }
                        db.rawQuery(
                            "SELECT count(*) FROM history JOIN chapters ON chapter_id = chapters._id " +
                                "WHERE manga_id = ?",
                            arrayOf(id.toString()),
                        ).use {
                            check(it.moveToFirst())
                            state.put("historyCount", it.getInt(0))
                        }
                        db.rawQuery(
                            "SELECT chapter_count_state FROM author_archive_source_works " +
                                "WHERE source_id = 9876543210 AND stable_source_url = ?",
                            arrayOf(work),
                        ).use {
                            state.put("catalogState", if (it.moveToFirst()) it.getString(0) else "UNKNOWN")
                        }
                        val mangaKey = "5:MANGA10:98765432100:21:$work" + "0:"
                        db.rawQuery(
                            "SELECT refs_json FROM sync_object_heads WHERE space_id = ? AND " +
                                "generation = 1 AND object_key = ?",
                            arrayOf(SPACE, mangaKey),
                        ).use {
                            var count = 0
                            while (it.moveToNext()) count += JSONArray(it.getString(0)).length()
                            state.put("headCount", count)
                        }
                    }
            }
        return state
    }

    private fun readerSnapshot(model: ReaderViewModel, work: String): JSONObject = database(work).apply {
        val gate = control("/control/state", post = false)
        put("catalogCalls", gate.getInt("catalogCalls"))
        put("directoryHeld", gate.getBoolean("directoryHeld"))
        put("currentPage", model.state.value.currentPage)
        put("currentChapter", model.state.value.currentChapter?.chapter?.url)
        put("previousChapter", model.state.value.viewerChapters?.prevChapter?.chapter?.url)
        put("nextChapter", model.state.value.viewerChapters?.nextChapter?.chapter?.url)
        put("activationIdentity", System.identityHashCode(field(model, "readingActivation")))
        put("openedIdentity", System.identityHashCode(field(model, "initialOpenContext")))
    }

    private fun nativeChapterButton(label: String): androidx.test.uiautomator.UiObject2 {
        if (device.findObject(By.desc(label)) == null) {
            device.click(device.displayWidth / 2, device.displayHeight / 2)
        }
        var node = checkNotNull(device.wait(Until.findObject(By.desc(label)), 5_000)) {
            "Actual Reader ChapterNavigator button $label is absent"
        }
        // The icon owns the description; the clickable parent owns enabled/disabled semantics.
        while (!node.isClickable) {
            node = checkNotNull(node.parent) { "Actual $label clickable button ancestor is absent" }
        }
        return node
    }

    private suspend fun runNativeNavigation(work: String, title: String) {
        val before = database(work)
        assertEquals(3, before.getInt("chapterCount"))
        assertEquals("COMPLETE", before.getString("catalogState"))
        observe("native-navigation-started", before)
        val monitor = instrumentation.addMonitor(ReaderActivity::class.java.name, null, false)
        var activity: ReaderActivity? = null
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    action = Constants.SHORTCUT_HISTORY
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
            checkNotNull(device.wait(Until.findObject(By.text(title)), 15_000)).click()
            activity = monitor.waitForActivityWithTimeout(15_000) as? ReaderActivity
            val reader = checkNotNull(activity)
            await("native navigation reader", 15_000) { reader.viewModel.state.value.currentChapter?.pages?.size == 4 }
            when (reader.viewModel.state.value.currentChapter?.chapter?.url) {
                "$work/chapter/1" -> navigateChapter(reader, work, "下一章", 2)
                "$work/chapter/2" -> Unit
                "$work/chapter/3" -> navigateChapter(reader, work, "上一章", 2)
                else -> error("History did not select a chapter from the fixed fixture")
            }
            assertEquals(before.getLong("chapterId"), reader.viewModel.state.value.currentChapter?.chapter?.id)
            navigateChapter(reader, work, "上一章", 1)
            assertNativeBoundary(reader, work, "上一章", "first")
            navigateChapter(reader, work, "下一章", 2)
            navigateChapter(reader, work, "下一章", 3)
            assertNativeBoundary(reader, work, "下一章", "last")
            navigateChapter(reader, work, "上一章", 2)
            Injekt.get<AndroidReaderProgressCoordinator>().awaitAccepted(before.getLong("mangaId"))
            assertEquals(0, control("/control/state", post = false).getInt("catalogCalls"))
            observe("native-navigation-finished", readerSnapshot(reader.viewModel, work))
        } finally {
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun navigateChapter(reader: ReaderActivity, work: String, label: String, number: Int) {
        await("actual $label button enabled", 5_000) { nativeChapterButton(label).isEnabled }
        val button = nativeChapterButton(label)
        assertTrue("Actual $label button must be enabled", button.isEnabled)
        button.click()
        val model = reader.viewModel
        await("native $label to chapter $number", 15_000) {
            model.state.value.currentChapter?.chapter?.url == "$work/chapter/$number" &&
                model.state.value.currentChapter?.pages?.size == 4 && model.state.value.currentPage in 1..4
        }
        val current = checkNotNull(model.state.value.currentChapter)
        assertReadableDraw(reader, checkNotNull(current.pages)[model.state.value.currentPage - 1])
        observe("native-navigation-chapter-$number", readerSnapshot(model, work))
    }

    private fun assertNativeBoundary(reader: ReaderActivity, work: String, label: String, boundary: String) {
        await("actual $boundary boundary button disabled", 5_000) { !nativeChapterButton(label).isEnabled }
        val button = nativeChapterButton(label)
        assertEquals("Actual $boundary boundary button must be disabled", false, button.isEnabled)
        val current = reader.viewModel.state.value.currentChapter
        button.click()
        device.waitForIdle()
        assertSame(current, reader.viewModel.state.value.currentChapter)
        observe("native-$boundary-boundary", readerSnapshot(reader.viewModel, work))
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.get(owner)

    private fun assertReadableDraw(activity: ReaderActivity, expectedPage: ReaderPage) {
        val draw = CountDownLatch(1)
        val root = activity.window.decorView
        val listener = ViewTreeObserver.OnDrawListener {
            if (drawnPageAtCenter(root, expectedPage)) draw.countDown()
        }
        instrumentation.runOnMainSync {
            root.viewTreeObserver.addOnDrawListener(listener)
            root.invalidate()
        }
        try {
            assertTrue("Current mounted viewport did not draw a decoded image", draw.await(10, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { root.viewTreeObserver.removeOnDrawListener(listener) }
        }
    }

    private fun drawnPageAtCenter(view: View, expectedPage: ReaderPage): Boolean {
        if (view is PagerPageHolder && view.page === expectedPage) {
            val rect = Rect()
            if (view.getGlobalVisibleRect(rect) && rect.contains(device.displayWidth / 2, device.displayHeight / 2)) {
                return hasDecodedImage(view)
            }
        }
        if (view is ViewGroup) {
            return (0 until view.childCount).any { drawnPageAtCenter(view.getChildAt(it), expectedPage) }
        }
        return false
    }

    private fun hasDecodedImage(view: View): Boolean {
        if (view is SubsamplingScaleImageView) return view.isReady
        if (view is ViewGroup) return (0 until view.childCount).any { hasDecodedImage(view.getChildAt(it)) }
        return false
    }

    private fun await(label: String, timeout: Long, condition: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            check(android.os.SystemClock.elapsedRealtime() < end) { "$label timed out" }
            Thread.sleep(50)
        }
    }

    private fun control(path: String, post: Boolean = true, body: String = "{}"): JSONObject {
        require(path.startsWith("/control/"))
        val connection = URI("http://127.0.0.1:18464$path").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 3_000
        try {
            if (post) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            check(connection.responseCode == 200) { "External fixture ${connection.responseCode}" }
            return JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun observe(stage: String, actual: JSONObject) {
        actual.put("stage", stage)
        actual.put("processId", Process.myPid()).put("processStartElapsed", Process.getStartElapsedRealtime())
        control("/control/observe", body = actual.toString())
        println("HP02_ANDROID_NATIVE_OBSERVATION $actual")
    }

    private companion object {
        const val SPACE = "history-android-formal-hp02"
    }
}
