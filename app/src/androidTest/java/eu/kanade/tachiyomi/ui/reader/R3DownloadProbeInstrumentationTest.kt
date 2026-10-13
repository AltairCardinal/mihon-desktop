package eu.kanade.tachiyomi.ui.reader

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPageR2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPagerPageHolder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Same observer source is compiled against the baseline and candidate. No new pairing API is called.
 * Scalar pixel references are generated off-device. GPU commit and PixelCopy verification are
 * separate clocks: a later buffer is never attributed to an earlier frame commit.
 */
class R3DownloadProbeInstrumentationTest {
    @Test
    fun prepareDownloadFixtureOutsideTheMeasuredProcess() = probe("directory", seedOnly = true)

    @Test
    fun directoryDownloadUsesTheActualLoaderAndCommitsCorrectPixels() = probe("directory")

    @Test
    fun cbzDownloadUsesTheActualLoaderAndCommitsCorrectPixels() = probe("cbz")

    private fun probe(defaultStorage: String, seedOnly: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".dev")) { "Only the isolated Debug identity is supported" }
        val fixture = args.getString("fixture") ?: "control-no-reset-176"
        val storage = args.getString("storage") ?: defaultStorage
        val target = (args.getString("target") ?: "2").toInt()
        val expected = (args.getString("expected") ?: "2,3").split(",").map(String::toInt)
        check(fixture in listOf("vol08-176", "vol09-surrogate-176", "control-no-reset-176"))
        check(storage in listOf("directory", "cbz") && target in 1..176)
        check(expected.size in 1..2 && expected.all { it in 1..176 })
        val inputRoot = File(args.getString("fixtureRoot") ?: File(context.filesDir, "pairing-r3-input").path)
        val manifest = JSONObject(File(inputRoot, "manifest.json").readText(Charsets.UTF_8))
        val chapters = manifest.getJSONArray("chapters")
        val record = (0 until chapters.length()).map(chapters::getJSONObject).single { it.getString("id") == fixture }
        val references = JSONObject(File(inputRoot, "pixel-reference.json").readText(Charsets.UTF_8))
            .getJSONObject("chapters").getJSONObject(fixture)
        val originalReferenceFile = File(inputRoot, "original-point-reference.json")
        val originalReferences = if (originalReferenceFile.isFile) {
            JSONObject(originalReferenceFile.readText(Charsets.UTF_8)).getJSONObject("chapters").optJSONObject(fixture)
        } else {
            null
        }
        val provider = Injekt.get<DownloadProvider>()
        val source = requireNotNull(Injekt.get<SourceManager>().get(LocalSource.ID))
        val fixtureNumber = listOf("vol08-176", "vol09-surrogate-176", "control-no-reset-176").indexOf(fixture)
        val mangaId = 730600L + fixtureNumber * 2 + if (storage == "cbz") 1 else 0
        val chapterId = mangaId + 100
        val title = "R3 isolated " + fixture + " " + storage
        val chapterName = "R3 owned chapter"
        val chapterUrl = "/r3-isolated/" + fixture + "/" + storage
        val storageRoot = File(context.filesDir, "r3-download-storage")
        val mangaDirectory = File(
            File(File(storageRoot, "downloads"), provider.getSourceDirName(source)),
            provider.getMangaDirName(title),
        )
        check(mangaDirectory.mkdirs() || mangaDirectory.isDirectory)
        val chapterDirectory = File(mangaDirectory, provider.getChapterDirName(chapterName, null, chapterUrl))
        val records = record.getJSONArray("pages")
        val marker = File(mangaDirectory, "r3-seed-sha256")
        val seedIdentity = sha256(File(inputRoot, "manifest.json")) + ":" + storage
        val seeded = marker.isFile && marker.readText(Charsets.UTF_8) == seedIdentity
        check(seedOnly || seeded) {
            "Run prepareDownloadFixtureOutsideTheMeasuredProcess, then force-stop before measuring"
        }
        if (seedOnly && storage == "directory") {
            check(chapterDirectory.mkdirs() || chapterDirectory.isDirectory)
            for (index in 0 until records.length()) {
                val page = records.getJSONObject(index)
                val original = File(File(inputRoot, fixture), page.getString("file"))
                check(sha256(original) == page.getString("sha256")) { "Fixture input bytes changed" }
                val destination = File(chapterDirectory, original.name)
                if (!destination.exists() || sha256(destination) != page.getString("sha256")) {
                    original.copyTo(destination, overwrite = true)
                }
            }
        } else if (seedOnly) {
            val original = File(inputRoot, fixture + ".cbz")
            check(sha256(original) == record.getString("archiveSha256"))
            val destination = File(mangaDirectory, chapterDirectory.name + ".cbz")
            if (!destination.exists() || sha256(destination) != record.getString("archiveSha256")) {
                original.copyTo(destination, overwrite = true)
            }
        }
        if (seedOnly) marker.writeText(seedIdentity, Charsets.UTF_8)
        val preferences = context.getSharedPreferences(context.packageName + "_preferences", 0)
        val settings = linkedMapOf<String, Any>(
            "pref_default_reading_mode_key" to 6,
            "reader_auto_spread_matching" to true,
            "pref_dual_page_split" to false,
            "pref_dual_page_invert" to false,
            "crop_borders" to false,
            "pref_image_scale_type_key" to 1,
            "pref_zoom_start_key" to 4,
        )
        val previousSettings = settings.keys.associateWith { preferences.all[it] }
        val storagePreference = Injekt.get<StoragePreferences>().baseStorageDirectory()
        val previousStorage = storagePreference.get()
        var activity: ReaderActivity? = null
        var observer: Observer? = null
        val output = JSONObject().put("schema", 1).put("fixture", fixture).put("storage", storage)
            .put("targetReaderPage", target).put("expectedReaderPages", JSONArray(expected))
            .put("iteration", args.getString("iteration") ?: "0")
            .put("condition", args.getString("condition") ?: "smoke")
            .put("metric", "intent-to-gpu-pixelcopy-verified-upper").put("processId", android.os.Process.myPid())
            .put("osStorageCold", false).put("targetDecodedReferenceBeforeIntent", false)
        try {
            storagePreference.set(Uri.fromFile(storageRoot).toString())
            val storageDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (
                Injekt.get<StorageManager>().getDownloadsDirectory()?.uri?.path != File(storageRoot, "downloads").path
            ) {
                check(System.nanoTime() < storageDeadline) {
                    "Actual StorageManager did not bind the isolated directory"
                }
                Thread.sleep(10)
            }
            preferences.edit().apply {
                for ((key, value) in settings) {
                    when (value) {
                        is Int -> putInt(key, value)
                        is Boolean -> putBoolean(key, value)
                    }
                }
            }.also { check(it.commit()) }
            seed(mangaId, chapterId, title, chapterName, chapterUrl, target - 1)
            check(provider.findChapterDir(chapterName, null, chapterUrl, title, source) != null)
            if (seedOnly) {
                output.put("outcome", "SEEDED_NOT_MEASURED")
                return
            }
            val intent = ReaderActivity.newIntent(context, mangaId, chapterId)
                .putExtra("page_index", target - 1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            fun open(): Observer {
                instrumentation.runOnMainSync {
                    observer?.close()
                    activity?.finish()
                }
                instrumentation.waitForIdleSync()
                val monitor = instrumentation.addMonitor(ReaderActivity::class.java.name, null, false)
                val started = System.nanoTime()
                val application = context.applicationContext as Application
                var installed = false
                val callbacks = object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityCreated(created: Activity, state: Bundle?) {
                        if (created is ReaderActivity) {
                            activity = created
                            observer = Observer(created, expected, references, started, originalReferences)
                            observer!!.start()
                            installed = true
                        }
                    }
                    override fun onActivityStarted(activity: Activity) = Unit
                    override fun onActivityResumed(activity: Activity) = Unit
                    override fun onActivityPaused(activity: Activity) = Unit
                    override fun onActivityStopped(activity: Activity) = Unit
                    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                    override fun onActivityDestroyed(activity: Activity) = Unit
                }
                application.registerActivityLifecycleCallbacks(callbacks)
                try {
                    context.startActivity(intent)
                    activity = monitor.waitForActivityWithTimeout(30_000) as? ReaderActivity
                    check(activity != null && installed) {
                        "A fresh actual ReaderActivity must install the observer before its first frame"
                    }
                    return requireNotNull(observer)
                } finally {
                    application.unregisterActivityLifecycleCallbacks(callbacks)
                    instrumentation.removeMonitor(monitor)
                }
            }
            val timeout = (args.getString("timeoutMs") ?: "20000").toLong()
            val initial = open().await(timeout)
            output.put("initial", initial)
            // Explicit source navigation uses the actual Viewer entry; it never reseeds a budget.
            val actions = args.getString("actions").orEmpty().split(";").filter(String::isNotBlank)
            val actionResults = JSONArray()
            for (action in actions) {
                val parts = action.split(":")
                check(parts.size == 3) { "actions are target:expectedCSV:delayMs separated by semicolons" }
                val next = parts[0].toInt()
                val pair = parts[1].split(",").map(String::toInt)
                val interval = parts[2].toLong()
                val fast = args.getString("condition") == "fast"
                if (!fast) Thread.sleep(interval)
                var navigationAt = 0L
                val tap = args.getString("actionInput") == "tap"
                val tapPoint = FloatArray(2)
                instrumentation.runOnMainSync {
                    val viewer = actualViewer(requireNotNull(activity))
                    val page = requireNotNull(viewer.adapter.currentChapter?.pages?.getOrNull(next - 1))
                    navigationAt = System.nanoTime()
                    observer!!.reset(pair, navigationAt)
                    if (tap) {
                        val current = viewer.adapter.session(requireNotNull(viewer.adapter.currentChapter))
                            ?.location?.pageId?.sourcePageIndex ?: target - 1
                        val position = IntArray(2).also(viewer.pager::getLocationOnScreen)
                        tapPoint[0] = position[0] + viewer.pager.width * if (next - 1 > current) 0.2f else 0.8f
                        tapPoint[1] = position[1] + viewer.pager.height * 0.5f
                    } else {
                        viewer.moveToPage(page)
                    }
                }
                if (tap) {
                    val downAt = SystemClock.uptimeMillis()
                    MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, tapPoint[0], tapPoint[1], 0).also {
                        instrumentation.sendPointerSync(it)
                        it.recycle()
                    }
                    MotionEvent.obtain(downAt, downAt + 20, MotionEvent.ACTION_UP, tapPoint[0], tapPoint[1], 0).also {
                        instrumentation.sendPointerSync(it)
                        it.recycle()
                    }
                }
                if (fast) {
                    val remaining = interval - (System.nanoTime() - navigationAt) / 1_000_000
                    if (remaining > 0) Thread.sleep(remaining)
                    actionResults.put(observer!!.await(0))
                } else {
                    actionResults.put(observer!!.await(timeout))
                }
            }
            val reopens = JSONArray()
            repeat((args.getString("reopens") ?: "0").toInt()) { reopens.put(open().await(timeout)) }
            val allCorrect = initial.getBoolean("bothCorrect") &&
                (0 until actionResults.length()).all { actionResults.getJSONObject(it).getBoolean("bothCorrect") } &&
                (0 until reopens.length()).all { reopens.getJSONObject(it).getBoolean("bothCorrect") }
            output.put("actions", actionResults)
                .put("reopensSameProcess", reopens)
                .put("outcome", if (allCorrect) "CORRECT" else "NOT_CORRECT")
                .put(
                    "actionInput",
                    if (args.getString("actionInput") == "tap") "native-pointer-tap" else "viewer.moveToPage",
                )
            if (args.getString("requireCorrect") != "false") {
                assertTrue("Actual committed pixels did not match frozen input: " + output, allCorrect)
            }
        } catch (error: Throwable) {
            output.put("error", error.javaClass.name + ": " + error.message)
            observer?.let { output.put("lastObservation", it.await(0)) }
            throw error
        } finally {
            instrumentation.runOnMainSync {
                observer?.close()
                activity?.finish()
            }
            output.put("finishedNs", System.nanoTime())
            val name = args.getString("output") ?: "r3-download-probe.json"
            check(name.matches(Regex("[a-zA-Z0-9._-]+")))
            File(context.filesDir, name).writeText(output.toString(2), Charsets.UTF_8)
            println("R3_DOWNLOAD_RESULT " + output)
            preferences.edit().apply {
                for ((key, value) in previousSettings) {
                    when (value) {
                        null -> remove(key)
                        is Int -> putInt(key, value)
                        is Boolean -> putBoolean(key, value)
                    }
                }
            }.commit()
            storagePreference.set(previousStorage)
        }
    }

    private fun seed(manga: Long, chapter: Long, title: String, name: String, url: String, initial: Int) {
        val driver = Injekt.get<SqlDriver>()
        val existing = driver.executeQuery(null, "SELECT url FROM mangas WHERE _id=" + manga, { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
        }, 0).value
        check(existing == null || existing == url) { "Reserved fixture row is owned by different content" }
        fun quote(value: String) = "'" + value.replace("'", "''") + "'"
        driver.execute(
            null,
            "INSERT INTO mangas(_id,source,url,title,status,favorite,initialized,viewer," +
                "chapter_flags,cover_last_modified,date_added) VALUES(" +
                manga + ",0," + quote(url) + "," + quote(title) +
                ",0,0,1,6,0,0,0) ON CONFLICT(_id) DO UPDATE SET viewer=6",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO chapters(_id,manga_id,url,name,read,bookmark,last_page_read," +
                "chapter_number,source_order,date_fetch,date_upload) VALUES(" +
                chapter + "," + manga + "," + quote(url) + "," + quote(name) + ",0,0," + initial +
                ",1,0,0,0) ON CONFLICT(_id) DO UPDATE SET read=0,last_page_read=" + initial,
            0,
        )
    }

    private class Observer(
        private val activity: ReaderActivity,
        expected: List<Int>,
        private val references: JSONObject,
        started: Long,
        private val originalReferences: JSONObject? = null,
    ) : AutoCloseable, ViewTreeObserver.OnPreDrawListener {
        private val root = activity.window.decorView
        private var expected = expected
        private var started = started
        private var done = CountDownLatch(1)
        private var closed = false
        private var copyPending = false
        private val hooks = IdentityHashMap<ReaderPageImageView, () -> Unit>()
        private val originals = IdentityHashMap<ReaderPageImageView, (() -> Unit)?>()
        private val events = linkedMapOf<Int, JSONObject>()
        private val errors = linkedSetOf<String>()
        private val correct = linkedMapOf<Int, Long>()
        private var frameCopies = 0
        private var lastRoute = ""
        private var firstCorrect = 0L
        private var lastCommit = 0L
        private var lastBudget = JSONObject()
        private var observedOptionalJobs = 0
        private var sequence = 0L
        private var frameKey = ""
        private var pixelChecks = JSONObject()
        private val attachedAt = System.nanoTime()
        private val rejects = linkedMapOf<String, Int>()

        fun start() {
            root.viewTreeObserver.addOnPreDrawListener(this)
            root.invalidate()
        }

        fun reset(pages: List<Int>, at: Long) {
            sequence++
            expected = pages
            started = at
            done = CountDownLatch(1)
            events.clear()
            correct.clear()
            errors.clear()
            firstCorrect = 0L
            frameCopies = 0
            frameKey = ""
            pixelChecks = JSONObject()
            rejects.clear()
            root.invalidate()
        }

        override fun onPreDraw(): Boolean {
            if (closed) return true
            val viewer = activity.viewModel.state.value.viewer as? DualPageR2LPagerViewer ?: return true
            val chapter = viewer.adapter.currentChapter ?: return true
            if (chapter.pageLoader == null) return true
            lastRoute = chapter.pageLoader?.javaClass?.name.orEmpty()
            check(chapter.pageLoader is DownloadPageLoader) {
                "Actual ChapterLoader selected a different route: " + lastRoute
            }
            chapter.pages.orEmpty().filter { it.index + 1 in expected }.forEach { page ->
                val event = events.getOrPut(page.index + 1) { JSONObject().put("readerPage", page.index + 1) }
                if (page.status == Page.State.Ready && !event.has("encodedReadyObservedNs")) {
                    event.put("encodedReadyObservedNs", System.nanoTime())
                }
                event.put("content", page.encodedContentIdentity())
                if (page.status is Page.State.Error) errors += "page " + (page.index + 1) + ": " + page.status
            }
            val owner = viewer.adapter.session(chapter)
            if (owner != null) {
                val budget = owner.factBudget
                val budgetLock = requireNotNull(fieldOrNull(budget, "lock"))
                synchronized(budgetLock) {
                    lastBudget = JSONObject().put("locationSequence", owner.location?.sequence)
                    for (name in listOf("attempted", "active", "newlyReadBytes", "startedAt", "closed")) {
                        val value = fieldOrNull(budget, name)
                        lastBudget.put(
                            name,
                            if (value is Set<*>) JSONArray(value.map { it.toString() }) else value ?: JSONObject.NULL,
                        )
                    }
                }
                val analysis = owner.javaClass.methods.firstOrNull {
                    it.name.startsWith("originalAnalysis") && it.parameterCount == 1
                }
                for (page in expected) {
                    val id = owner.identity.pageIds.getOrNull(page - 1) ?: continue
                    val evidence = analysis?.invoke(owner, id)?.toString() ?: "NOT_AVAILABLE_IN_BASELINE"
                    val event = events.getOrPut(page) { JSONObject() }
                    if (evidence.startsWith("Computed") && !event.has("analysisObservedNs")) {
                        event.put("analysisObservedNs", System.nanoTime())
                    }
                    event.put("analysis", evidence)
                }
            }
            val sampler = Class.forName("eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler")
            val counter = sampler.declaredFields.firstOrNull { it.name == "optionalJobs" }
                ?.apply { isAccessible = true }?.get(null) as? kotlinx.coroutines.flow.StateFlow<*>
            observedOptionalJobs = maxOf(observedOptionalJobs, counter?.value as? Int ?: 0)
            for (image in views(root).filterIsInstance<ReaderPageImageView>()) {
                val page = fieldOrNull(image, "page") as? ReaderPage ?: continue
                val native = views(image).filterIsInstance<SubsamplingScaleImageView>().singleOrNull() ?: continue
                if (native.isReady && page.index + 1 in expected) {
                    val event = events.getOrPut(page.index + 1) { JSONObject() }
                    if (!event.has("nativeReadyObservedNs")) event.put("nativeReadyObservedNs", System.nanoTime())
                    observeNormalBitmap(image, native, event)
                }
                if (image.onImageDrawn != null && image.onImageDrawn !== hooks[image]) {
                    val original = image.onImageDrawn
                    val hook = {
                        original?.invoke()
                        if (!closed && page.index + 1 in expected) {
                            events.getOrPut(page.index + 1) { JSONObject() }.put("lastActualDrawNs", System.nanoTime())
                            commit(image, page, native)
                        }
                        Unit
                    }
                    originals[image] = original
                    hooks[image] = hook
                    image.onImageDrawn = hook
                }
                // A real earlier draw can outlive the ticket captured by its first GPU callback.
                // Retry only from current production drawn/locked/bound evidence; never make a draw receipt.
                if (native.isReady && page.index + 1 in expected) commit(image, page, native)
            }
            return true
        }

        private fun commit(image: ReaderPageImageView, page: ReaderPage, native: SubsamplingScaleImageView) {
            if (copyPending || correct.keys.containsAll(expected)) return
            val holder = generateSequence(image.parent) { it.parent }
                .filterIsInstance<DualPagerPageHolder>().firstOrNull() ?: return
            val captured = holder.presentationScope ?: return
            val frame = captured.first.frame(captured.second) ?: return
            val slots = frame.unit.slots
            if (slots.map { it.pageId.sourcePageIndex + 1 } != expected) {
                reject("SOURCE_MEMBERSHIP")
                return
            }
            if (slots.size == 2 && slots.map { it.position.name } != listOf("RIGHT", "LEFT")) {
                reject("PHYSICAL_SLOT")
                return
            }
            val id = slots.firstOrNull { it.pageId.sourcePageIndex == page.index }?.pageId ?: return
            if (id !in frame.drawnPageIds || id !in frame.lockedPageIds || !native.isReady) {
                reject("NO_CURRENT_REAL_DRAW")
                return
            }
            val bound = fieldOrNull(holder, "boundEncodedContents") as? Map<*, *> ?: return
            val rendered = fieldOrNull(holder, "renderedPages") as? Map<*, *> ?: return
            val displayBound = fieldOrNull(holder, "boundContents") as? Map<*, *> ?: return
            if (bound[page] != page.encodedContentIdentity() || rendered[page] == null ||
                rendered[page] != displayBound[page]
            ) {
                reject("CURRENT_BINDING")
                return
            }
            val content = page.encodedContentIdentity()
            check(root.isHardwareAccelerated) { "Native GPU renderer is required" }
            val operation = sequence
            copyPending = true
            root.viewTreeObserver.registerFrameCommitCallback {
                val committed = System.nanoTime()
                lastCommit = committed
                root.post {
                    if (closed || operation != sequence) {
                        copyPending = false
                        return@post
                    }
                    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    val pose = expected.associateWith { readerPage -> capturePoints(holder, readerPage) }
                    val capturedPages = holder.displayPage.visiblePages.associateWith { it.encodedContentIdentity() }
                    val key = frame.id.toString() + ":" + System.identityHashCode(captured.second) + ":" +
                        capturedPages.entries.joinToString { (p, value) -> p.index.toString() + "=" + value }
                    PixelCopy.request(activity.window, bitmap, { result ->
                        try {
                            if (!closed && operation == sequence && result == PixelCopy.SUCCESS &&
                                holder.presentationScope == captured &&
                                page.encodedContentIdentity() == content &&
                                captured.first.frame(captured.second)?.id == frame.id
                            ) {
                                frameCopies++
                                val copyReturnedAt = System.nanoTime()
                                val current = requireNotNull(captured.first.frame(captured.second))
                                val bound = fieldOrNull(holder, "boundEncodedContents") as? Map<*, *>
                                    ?: emptyMap<Any, Any>()
                                val rendered = fieldOrNull(holder, "renderedPages") as? Map<*, *>
                                    ?: emptyMap<Any, Any>()
                                val displayBound = fieldOrNull(holder, "boundContents") as? Map<*, *>
                                    ?: emptyMap<Any, Any>()
                                pixelChecks = JSONObject()
                                val matched = pose.filter { (readerPage, points) ->
                                    val actualPage = capturedPages.keys.firstOrNull { it.index + 1 == readerPage }
                                    val id = actualPage?.let { captured.first.identity.pageIds.getOrNull(it.index) }
                                    val capability = actualPage != null && id != null && id in current.drawnPageIds &&
                                        id in current.lockedPageIds && actualPage.status == Page.State.Ready &&
                                        actualPage.encodedContentIdentity() == capturedPages[actualPage] &&
                                        bound[actualPage] == capturedPages[actualPage] &&
                                        rendered[actualPage] != null && rendered[actualPage] == displayBound[actualPage]
                                    val check = matches(bitmap, points)
                                    pixelChecks.put(
                                        readerPage.toString(),
                                        check.put("currentPhysicalCapability", capability),
                                    )
                                    capability && check.getBoolean("accepted")
                                }.keys
                                val verified = System.nanoTime()
                                pixelChecks.put("pixelVerificationNs", verified - copyReturnedAt)
                                if (matched.isNotEmpty() && firstCorrect == 0L) firstCorrect = verified
                                // Both means two correct pages in this one verified buffer and capability.
                                // Never combine an earlier left page with another ticket's right page.
                                frameKey = key
                                correct.clear()
                                matched.forEach { correct[it] = verified }
                                if (correct.keys.containsAll(expected)) done.countDown()
                            } else {
                                reject("GPU_CALLBACK_CAPABILITY_CHANGED_OR_COPY_FAILED")
                            }
                        } finally {
                            bitmap.recycle()
                            copyPending = false
                            if (!closed && !correct.keys.containsAll(expected)) root.postInvalidateOnAnimation()
                        }
                    }, Handler(Looper.getMainLooper()))
                }
            }
        }

        private fun capturePoints(holder: DualPagerPageHolder, readerPage: Int): List<IntArray>? {
            val page = holder.displayPage.visiblePages.firstOrNull { it.index + 1 == readerPage } ?: return null
            val image = views(holder).filterIsInstance<ReaderPageImageView>()
                .firstOrNull { fieldOrNull(it, "page") === page } ?: return null
            val native = views(image).filterIsInstance<SubsamplingScaleImageView>().singleOrNull() ?: return null
            if (!native.isReady || !image.isShown) return null
            val visible = Rect()
            if (!native.getGlobalVisibleRect(visible)) return null
            val reference = references.getJSONObject(readerPage.toString())
            check(native.sWidth == reference.getInt("width") && native.sHeight == reference.getInt("height"))
            val matrix = Matrix()
            native.transformMatrixToGlobal(matrix)
            val center = native.sourceToViewCoord(native.sWidth / 2f, native.sHeight / 2f) ?: return null
            val centerGlobal = floatArrayOf(center.x, center.y)
            matrix.mapPoints(centerGlobal)
            val holderBounds = Rect()
            if (!holder.getGlobalVisibleRect(holderBounds)) return null
            val scope = holder.presentationScope ?: return null
            val slot = scope.first.frame(scope.second)?.unit?.slots
                ?.firstOrNull { it.pageId.sourcePageIndex == page.index } ?: return null
            if (slot.position.name == "RIGHT" && centerGlobal[0] < holderBounds.centerX()) return null
            if (slot.position.name == "LEFT" && centerGlobal[0] > holderBounds.centerX()) return null
            val rootPosition = IntArray(2).also(root::getLocationOnScreen)
            val samples = reference.getJSONArray("points")
            val capturedPoints = (0 until samples.length()).mapNotNull { index ->
                val sample = samples.getJSONArray(index)
                val point = native.sourceToViewCoord(
                    sample.getDouble(0).toFloat(),
                    sample.getDouble(1).toFloat(),
                ) ?: return@mapNotNull null
                val xy = floatArrayOf(point.x, point.y)
                matrix.mapPoints(xy)
                if (!visible.contains(xy[0].toInt(), xy[1].toInt())) return@mapNotNull null
                intArrayOf(
                    (xy[0] - rootPosition[0]).toInt(),
                    (xy[1] - rootPosition[1]).toInt(),
                    sample.getInt(2),
                    sample.getInt(3),
                    if (sample.optBoolean(4)) 1 else 0,
                )
            }
            val required = (0 until samples.length()).count { samples.getJSONArray(it).optBoolean(4) }
            return capturedPoints.takeIf { it.size >= 24 && it.count { point -> point[4] != 0 } == required }
        }

        private fun matches(bitmap: Bitmap, points: List<IntArray>?): JSONObject {
            points ?: return JSONObject().put("accepted", false).put("reason", "NO_CURRENT_NATIVE_POSE")
            var matched = 0
            var mandatory = 0
            var mandatoryMatched = 0
            for (point in points) {
                val (x, y, color, tolerance, required) = point
                if (required != 0) mandatory++
                val close = x in 1 until bitmap.width - 1 && y in 1 until bitmap.height - 1 &&
                    (-1..1).any { dx ->
                        (-1..1).any { dy ->
                            val actual = bitmap.getPixel(x + dx, y + dy)
                            (0..2).all { channel ->
                                kotlin.math.abs(
                                    ((actual ushr (channel * 8)) and 255) - ((color ushr (channel * 8)) and 255),
                                ) <= tolerance
                            }
                        }
                    }
                if (close) matched++
                if (close && required != 0) mandatoryMatched++
            }
            return JSONObject().put("accepted", matched >= points.size * 0.9 && mandatory == mandatoryMatched)
                .put("points", points.size).put("matched", matched)
                .put("mandatory", mandatory).put("mandatoryMatched", mandatoryMatched)
        }

        fun await(timeout: Long): JSONObject {
            done.await(timeout, TimeUnit.MILLISECONDS)
            val latch = CountDownLatch(1)
            var result: JSONObject? = null
            root.post {
                result = snapshot()
                latch.countDown()
            }
            check(latch.await(5, TimeUnit.SECONDS)) { "Main observer stopped responding" }
            return requireNotNull(result)
        }

        fun snapshot(): JSONObject = JSONObject().put("intentNs", started).put("firstCorrectVerifiedNs", firstCorrect)
            .put("observerAttachedNs", attachedAt)
            .put("bothCorrectVerifiedNs", if (correct.keys.containsAll(expected)) correct.values.maxOrNull() else 0L)
            .put("bothCorrect", correct.keys.containsAll(expected)).put("lastGpuCommitNs", lastCommit)
            .put("verifiedFrameCopies", frameCopies).put("actualLoader", lastRoute)
            .put("pages", JSONArray(events.values.toList())).put("errors", JSONArray(errors.toList()))
            .put("expectedReaderPages", JSONArray(expected)).put("correctReaderPages", JSONArray(correct.keys.toList()))
            .put("factBudget", lastBudget).put("observedPeakOptionalJobs", observedOptionalJobs)
            .put("nativePeakAllocationBytes", "NOT_AVAILABLE").put("streamOpenCloseCounts", "NOT_AVAILABLE")
            .put("verifiedFrameCapability", frameKey).put("pixelChecks", pixelChecks)
            .put("observerRejectCounts", JSONObject(rejects as Map<*, *>))
            .put("actualHolderFrames", holderFrames())

        private fun reject(reason: String) {
            rejects[reason] = (rejects[reason] ?: 0) + 1
        }

        private fun observeNormalBitmap(
            image: ReaderPageImageView,
            native: SubsamplingScaleImageView,
            event: JSONObject,
        ) {
            // This is the app's already observed whole-image weak reference, never SSIV private data.
            val reference = fieldOrNull(image, "sampledPageBitmap") as? java.lang.ref.WeakReference<*> ?: return
            val bitmap = reference.get() as? Bitmap ?: return
            if (bitmap.isRecycled) return
            val config = image.decodedSideConfig ?: return
            val old = event.optJSONObject("normalBitmap")
            val bitmapId = System.identityHashCode(bitmap)
            if (old?.optInt("bitmapId") == bitmapId && old.has("fixedPointComparison")) return
            val dimensionsEqual = bitmap.width == native.sWidth && bitmap.height == native.sHeight
            val observation = JSONObject().put("observedNs", System.nanoTime()).put("bitmapId", bitmapId)
                .put("width", bitmap.width).put("height", bitmap.height)
                .put("originalWidth", native.sWidth).put("originalHeight", native.sHeight)
                .put("config", bitmap.config?.name).put("hardware", bitmap.config == Bitmap.Config.HARDWARE)
                .put("colorSpace", bitmap.colorSpace?.name).put("isSrgb", bitmap.colorSpace?.isSrgb)
                .put("hasAlpha", bitmap.hasAlpha()).put("premultiplied", bitmap.isPremultiplied)
                .put("allocationBytes", bitmap.allocationByteCount)
                .put("sourceOrientation", config.sourceOrientation.toString())
                .put("cropBorders", config.cropBorders).put("hasSourceBounds", config.sourceBounds != null)
                .put("rawDimensionsEqual", dimensionsEqual)
                .put("fixedPointEquivalence", "NOT_TESTED; dimensions alone cannot authorize reuse")
            val original = originalReferences?.optJSONObject(event.optInt("readerPage").toString())
            if (original != null && dimensionsEqual && !config.cropBorders && config.sourceBounds == null &&
                config.sourceOrientation.toString() == "NORMAL" && bitmap.config != Bitmap.Config.HARDWARE &&
                bitmap.colorSpace?.isSrgb == true && original.getBoolean("entireImageOpaque") &&
                !original.getJSONObject("color").getBoolean("embeddedICC")
            ) {
                var count = 0
                var exact = 0
                var opaque = 0
                var maxChannelDifference = 0
                try {
                    for (side in listOf("left", "right")) {
                        val points = original.getJSONArray(side)
                        for (index in 0 until points.length()) {
                            val point = points.getJSONArray(index)
                            val actual = bitmap.getPixel(point.getInt(0), point.getInt(1))
                            val expectedColor = point.getInt(2)
                            count++
                            if (actual == expectedColor) exact++
                            if (actual ushr 24 == 255) opaque++
                            for (channel in 0..3) {
                                maxChannelDifference = maxOf(
                                    maxChannelDifference,
                                    kotlin.math.abs(
                                        ((actual ushr (channel * 8)) and 255) -
                                            ((expectedColor ushr (channel * 8)) and 255),
                                    ),
                                )
                            }
                        }
                    }
                    observation.put(
                        "fixedPointComparison",
                        JSONObject().put("count", count)
                            .put("exact", exact).put("opaque", opaque).put("tolerance", 0)
                            .put("maxChannelDifference", maxChannelDifference)
                            .put("referenceSha256", original.getString("sha256")),
                    )
                    observation.put(
                        "fixedPointEquivalence",
                        if (count == 388 && exact == count && opaque == count) {
                            "OPAQUE_UNPROFILED_REFERENCE_EXACT; reuse not enabled"
                        } else {
                            "NOT_EQUIVALENT; reuse not enabled"
                        },
                    )
                } catch (error: RuntimeException) {
                    observation.put("fixedPointComparison", "UNAVAILABLE: " + error.javaClass.simpleName)
                }
            }
            event.put("normalBitmap", observation)
        }

        private fun holderFrames(): JSONArray = JSONArray(
            views(root).filterIsInstance<DualPagerPageHolder>().map { holder ->
                val scope = holder.presentationScope
                val frame = scope?.first?.frame(scope.second)
                val bounds = Rect().also { holder.getGlobalVisibleRect(it) }
                JSONObject()
                    .put("displayReaderPages", JSONArray(holder.displayPage.visiblePages.map { it.index + 1 }))
                    .put("scope", scope?.second?.toString() ?: "NONE")
                    .put("frameId", frame?.id?.toString() ?: "NONE")
                    .put(
                        "slots",
                        JSONArray(
                            frame?.unit?.slots?.map {
                                (it.pageId.sourcePageIndex + 1).toString() + ":" + it.position.name
                            }.orEmpty(),
                        ),
                    )
                    .put("drawnReaderPages", JSONArray(frame?.drawnPageIds?.map { it.sourcePageIndex + 1 }.orEmpty()))
                    .put("lockedReaderPages", JSONArray(frame?.lockedPageIds?.map { it.sourcePageIndex + 1 }.orEmpty()))
                    .put("bounds", bounds.flattenToString())
            },
        )

        override fun close() {
            closed = true
            root.viewTreeObserver.removeOnPreDrawListener(this)
            hooks.forEach { (image, hook) -> if (image.onImageDrawn === hook) image.onImageDrawn = originals[image] }
        }
    }

    private companion object {
        fun actualViewer(activity: ReaderActivity) = requireNotNull(
            activity.viewModel.state.value.viewer as? DualPageR2LPagerViewer,
        )
        fun views(root: View): List<View> = buildList {
            add(root)
            if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
        }
        fun fieldOrNull(instance: Any, name: String): Any? = generateSequence(instance.javaClass) { it.superclass }
            .mapNotNull { type -> type.declaredFields.firstOrNull { it.name == name } }
            .firstOrNull()?.apply { isAccessible = true }?.get(instance)
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
