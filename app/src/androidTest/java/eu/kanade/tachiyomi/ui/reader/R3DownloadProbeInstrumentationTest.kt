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
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageRegionDecoder
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DisplayPage
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPageR2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPagerPageHolder
import kotlinx.coroutines.Job
import mihon.domain.reader.ReaderPageAnalysisEvidence
import mihon.domain.reader.ReaderSpreadSampleRequest
import mihon.domain.reader.SharedDualPageSession
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
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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

    @Test
    fun directoryDownloadCancelsInFlightOriginalJniAndRecovers() = probe("directory", cancelNative = true)

    @Test
    fun directoryDownloadRejectsReplacedInFlightOriginalAndRecovers() = probe("directory", replaceNative = true)

    private fun probe(
        defaultStorage: String,
        seedOnly: Boolean = false,
        cancelNative: Boolean = false,
        replaceNative: Boolean = false,
    ) {
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
        if (cancelNative || replaceNative) {
            check(fixture == "control-no-reset-176" && storage == "directory" && target == 8)
            check(expected == listOf(8, 9) && args.getString("actions").isNullOrBlank())
            check((args.getString("reopens") ?: "0").toInt() == 0)
        }
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
        val cancellationGate = if (cancelNative || replaceNative) NativeCancellationGate() else null
        var restoreReplacedFile: (() -> Unit)? = null
        var restorePixelReference: (() -> Unit)? = null
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
                            observer = Observer(
                                created,
                                expected,
                                references,
                                started,
                                originalReferences,
                                traceNative = args.getString("traceNative") == "true",
                                traceKeys = args.getString("actionInput") == "page-key",
                                onChapterObserved = cancellationGate?.let { gate ->
                                    { viewer -> gate.install(viewer) }
                                },
                            )
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
            val firstObserver = open()
            if (cancelNative && cancellationGate != null) {
                output.put("cancellationRecovery", cancellationGate.result)
                check(cancellationGate.entered.await(10, TimeUnit.SECONDS)) {
                    "Original JNI did not reach the page2 physical EOF gate: " + cancellationGate.snapshot()
                }
                var matching: Job? = null
                val exiting = requireNotNull(activity)
                try {
                    instrumentation.runOnMainSync {
                        val viewer = actualViewer(exiting)
                        val owner = requireNotNull(viewer.adapter.currentChapter?.let(viewer.adapter::session))
                        matching = fieldOrNull(viewer.adapter, "matchingJob") as? Job
                        val job = requireNotNull(matching)
                        val remaining = owner.factBudget.remainingWaitMillis(SystemClock.uptimeMillis())
                        cancellationGate.result.put("beforeFinish", cancellationGate.jobSnapshot(job))
                            .put("remainingBudgetBeforeFinishMs", remaining)
                        check(job.isActive && !job.isCancelled && !job.isCompleted)
                        check(remaining >= 5000) { "Deadline cancellation cannot establish the exit contract" }
                        check(cancellationGate.nativeCloses.get() == 0)
                        firstObserver.close()
                        cancellationGate.result.put("finishIntentNs", System.nanoTime())
                        exiting.finish()
                    }
                    val job = requireNotNull(matching)
                    val exitDeadline = cancellationGate.enteredNs.get() + TimeUnit.MILLISECONDS.toNanos(2800)
                    while (true) {
                        var cancelledAfterDestroy = false
                        instrumentation.runOnMainSync {
                            cancelledAfterDestroy = exiting.isDestroyed && job.isCancelled
                        }
                        if (cancelledAfterDestroy) break
                        check(System.nanoTime() < exitDeadline) { "Finish did not cancel the in-flight job in time" }
                        Thread.sleep(5)
                    }
                    val pauseMs = (System.nanoTime() - cancellationGate.enteredNs.get()) / 1e6
                    cancellationGate.result.put("afterFinishBeforeRelease", cancellationGate.jobSnapshot(job))
                        .put("activityDestroyed", exiting.isDestroyed)
                        .put("pauseBeforeReleaseMs", pauseMs)
                    check(pauseMs < 3000) { "Exit cancellation exceeded the bounded test pause" }
                    check(!job.isCompleted && cancellationGate.nativeCloses.get() == 0)
                } finally {
                    cancellationGate.release.countDown()
                }
                val job = requireNotNull(matching)
                val drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (
                    !job.isCompleted || AndroidReaderSpreadSampler.optionalWork.value != 0 ||
                    ReaderPageImageView.foregroundWork.value != 0
                ) {
                    check(System.nanoTime() < drainDeadline) { "Cancelled native resources did not exit" }
                    Thread.sleep(10)
                }
                cancellationGate.result.put("afterRelease", cancellationGate.jobSnapshot(job))
                    .put("optionalWorkAfterExit", AndroidReaderSpreadSampler.optionalWork.value)
                    .put("foregroundWorkAfterExit", ReaderPageImageView.foregroundWork.value)
                check(job.isCancelled && cancellationGate.nativeCloses.get() == 1)
                check(cancellationGate.nativeCreates.get() == 1 && cancellationGate.nativeRecycles.get() == 1)
                cancellationGate.requireReleasedResources()
                instrumentation.runOnMainSync { cancellationGate.restore() }
                cancellationGate.result.put("matchedResources", cancellationGate.snapshot())
            }
            val initial = if (replaceNative) {
                val gate = requireNotNull(cancellationGate)
                val replacement = JSONObject().put("stage", "waiting-for-old-native-eof")
                    .put("oldGateSetup", gate.result)
                output.put("contentReplacement", replacement)
                check(gate.entered.await(10, TimeUnit.SECONDS)) {
                    "Old original JNI did not enter the physical EOF gate"
                }
                val oldRecord = records.getJSONObject(1)
                val newRecord = records.getJSONObject(11)
                val destination = File(chapterDirectory, oldRecord.getString("file"))
                val replacementInput = File(File(inputRoot, fixture), newRecord.getString("file"))
                val backup = destination.readBytes()
                val newBytes = replacementInput.readBytes()
                val oldSha = sha256(destination)
                val newSha = sha256(replacementInput)
                check(oldSha == oldRecord.getString("sha256") && newSha == newRecord.getString("sha256"))
                check(oldSha != newSha)
                check(gate.snapshot().getLong("matchedPhysicalBytes") == backup.size.toLong())
                val oldReference = JSONObject(references.getJSONObject("2").toString())
                val newReference = JSONObject(references.getJSONObject("12").toString())
                check(oldReference.getString("sha256") == oldSha && newReference.getString("sha256") == newSha)
                val oldPoints = oldReference.getJSONArray("points")
                val newPoints = newReference.getJSONArray("points")
                fun mandatoryPoints(points: JSONArray): Map<Pair<Int, Int>, Int> {
                    val mandatory = (0 until points.length()).map(points::getJSONArray).filter { it.optBoolean(4) }
                    check(mandatory.size == 8) {
                        "Frozen replacement oracle must contain exactly eight mandatory points"
                    }
                    return mandatory.associate { (it.getInt(0) to it.getInt(1)) to it.getInt(2) }.also {
                        check(it.size == 8) { "Mandatory oracle coordinates must be unique" }
                    }
                }
                val oldMandatory = mandatoryPoints(oldPoints)
                val newMandatory = mandatoryPoints(newPoints)
                check(oldMandatory.keys == newMandatory.keys) { "Old and new mandatory coordinate sets must match" }
                val distinguishingBadgePoints = oldMandatory.count { (coordinate, color) ->
                    color != newMandatory.getValue(coordinate)
                }
                check(distinguishingBadgePoints > 0) { "Frozen mandatory pixels must distinguish the real replacement" }
                replacement.put("oldFileSha256", oldSha).put("newFileSha256", newSha)
                    .put("oldFrozenPixelOracle", oldReference).put("newFrozenPixelOracle", newReference)
                    .put("distinguishingMandatoryPoints", distinguishingBadgePoints)
                    .put("oracleMapping", "readerPage2 now contains frozen fixture readerPage12; readerPage3 unchanged")
                val currentActivity = requireNotNull(activity)
                lateinit var viewer: DualPageR2LPagerViewer
                lateinit var page: ReaderPage
                lateinit var owner: SharedDualPageSession
                instrumentation.runOnMainSync {
                    viewer = actualViewer(currentActivity)
                    val chapter = requireNotNull(viewer.adapter.currentChapter)
                    page = requireNotNull(chapter.pages?.getOrNull(1))
                    owner = requireNotNull(viewer.adapter.session(chapter))
                }
                var oldRequest: ReaderSpreadSampleRequest? = null
                var oldJob: Job? = null
                instrumentation.runOnMainSync {
                    oldRequest = gate.currentOriginalRequest(viewer)
                    oldJob = fieldOrNull(viewer.adapter, "matchingJob") as? Job
                    check(requireNotNull(oldJob).isActive && !requireNotNull(oldJob).isCancelled)
                    check(owner.ownsOriginalAnalysisRequest(requireNotNull(oldRequest)))
                    replacement.put("oldRequestContent", requireNotNull(oldRequest).content.contentVersion)
                        .put("beforeReplacementJob", gate.jobSnapshot(requireNotNull(oldJob)))
                        .put(
                            "remainingBudgetBeforeReplacementMs",
                            owner.factBudget.remainingWaitMillis(
                                SystemClock.uptimeMillis(),
                            ),
                        )
                    check(owner.factBudget.remainingWaitMillis(SystemClock.uptimeMillis()) >= 5000)
                }
                restoreReplacedFile = {
                    destination.writeBytes(backup)
                    check(sha256(destination) == oldSha)
                    replacement.put("ownedFileRestored", true)
                }
                restorePixelReference = { references.put("2", oldReference) }
                var newContent = ""
                try {
                    destination.writeBytes(newBytes)
                    check(sha256(destination) == newSha)
                    replacement.put("physicalFileReplacedNs", System.nanoTime()).put("physicalFileReplaced", true)
                        .put("stage", "waiting-for-store")
                    instrumentation.runOnMainSync {
                        gate.replaceStreamFactory()
                        newContent = page.encodedContentIdentity()
                        check(newContent != requireNotNull(oldRequest).content.contentVersion)
                        replacement.put("newContent", newContent)
                    }
                    val propagationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (true) {
                        var oldOwned = true
                        var currentStoreHasNewContent = false
                        instrumentation.runOnMainSync {
                            oldOwned = owner.ownsOriginalAnalysisRequest(requireNotNull(oldRequest))
                            check(
                                owner.originalAnalysis(requireNotNull(oldRequest).content.pageId) !is
                                    ReaderPageAnalysisEvidence.Computed,
                            )
                            val currentOwner = requireNotNull(
                                viewer.adapter.currentChapter?.let(viewer.adapter::session),
                            )
                            currentStoreHasNewContent = currentOwner.identity.content[1].contentVersion == newContent
                        }
                        if (!oldOwned && currentStoreHasNewContent) break
                        check(System.nanoTime() < propagationDeadline) {
                            "Actual store still owned the old content ticket"
                        }
                        Thread.sleep(5)
                    }
                    replacement.put("oldRequestOwnedAfterPropagation", false)
                        .put("duringReplacement8and9", firstObserver.await(0))
                } finally {
                    gate.release.countDown()
                }
                val completionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!requireNotNull(oldJob).isCompleted) {
                    check(System.nanoTime() < completionDeadline) { "Old native batch did not exit after release" }
                    Thread.sleep(10)
                }
                instrumentation.runOnMainSync {
                    val evidence = owner.originalAnalysis(requireNotNull(oldRequest).content.pageId)
                    check(!owner.ownsOriginalAnalysisRequest(requireNotNull(oldRequest)))
                    check(
                        evidence !is ReaderPageAnalysisEvidence.Computed || evidence.content.contentVersion !=
                            requireNotNull(oldRequest).content.contentVersion,
                    )
                    replacement.put("afterOldBatchEvidence", evidence.toString())
                        .put("afterOldBatchJob", gate.jobSnapshot(requireNotNull(oldJob)))
                        .put("oldMatchedResources", gate.snapshot())
                    check(
                        gate.nativeCloses.get() == 1 && gate.nativeCreates.get() == 1 &&
                            gate.nativeRecycles.get() == 1,
                    )
                    check(gate.snapshot().getInt("matchedPhysicalCloseReturns") == 1)
                    check(!gate.snapshot().getBoolean("gateTimedOut"))
                    references.put("2", newReference)
                    val navigationAt = System.nanoTime()
                    firstObserver.reset(listOf(2, 3), navigationAt)
                    replacement.put("beforeRecoveryNavigation", navigationSelection(viewer))
                        .put("recoveryNavigationIntentNs", navigationAt)
                        .put("recoveryNavigation", "actual viewer.moveToPage to readerPage2; genuine new location")
                    viewer.moveToPage(page)
                }
                val recovered = firstObserver.await(timeout)
                check(recovered.getBoolean("bothCorrect")) {
                    "Actual new content pixels did not match the frozen oracle"
                }
                val recoveredExitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (AndroidReaderSpreadSampler.optionalWork.value != 0 ||
                    ReaderPageImageView.foregroundWork.value != 0
                ) {
                    check(System.nanoTime() < recoveredExitDeadline) { "Recovered content tasks did not settle" }
                    Thread.sleep(10)
                }
                instrumentation.runOnMainSync {
                    val currentOwner = requireNotNull(viewer.adapter.currentChapter?.let(viewer.adapter::session))
                    val evidence = currentOwner.originalAnalysis(currentOwner.identity.pageIds[1]) as?
                        ReaderPageAnalysisEvidence.Computed
                    check(requireNotNull(evidence).content.contentVersion == newContent)
                    check(gate.replacementNativeCreates.get() > 0 && gate.replacementNativeRecycles.get() > 0)
                    val resources = gate.snapshot()
                    check(gate.replacementNativeCreates.get() == gate.replacementNativeRecycles.get())
                    check(
                        resources.getInt("replacementPhysicalCloseCalls") ==
                            resources.getInt("replacementPhysicalCloseReturns"),
                    )
                    check(resources.getLong("replacementNativeBytes") >= newBytes.size.toLong())
                    replacement.put("newComputedEvidence", evidence.toString())
                        .put("afterRecoveryNavigation", navigationSelection(viewer))
                        .put("newMatchedResources", resources)
                        .put("optionalWorkAfterRecovery", AndroidReaderSpreadSampler.optionalWork.value)
                        .put("foregroundWorkAfterRecovery", ReaderPageImageView.foregroundWork.value)
                        .put("recoveredExpectedReaderPages", JSONArray(listOf(2, 3))).put("stage", "recovered-correct")
                }
                recovered
            } else if (cancellationGate == null) {
                firstObserver.await(timeout)
            } else {
                open().await(timeout)
            }
            output.put("initial", initial)
            if (cancelNative && cancellationGate != null) {
                assertTrue("Fresh actual reader did not recover correct pixels", initial.optBoolean("bothCorrect"))
                cancellationGate.result.put("recoveryOutcome", "CORRECT")
            }
            // Explicit source navigation uses the actual Viewer entry; it never reseeds a budget.
            val actions = args.getString("actions").orEmpty().split(";").filter(String::isNotBlank)
            val actionResults = JSONArray()
            var fastPlannedAt = System.nanoTime()
            for (action in actions) {
                val parts = action.split(":")
                check(parts.size == 3) { "actions are target:expectedCSV:delayMs separated by semicolons" }
                val next = parts[0].toInt()
                val pair = parts[1].split(",").map(String::toInt)
                val interval = parts[2].toLong()
                val fast = args.getString("condition") == "fast"
                if (fast) {
                    val waitNs = fastPlannedAt - System.nanoTime()
                    if (waitNs > 0) TimeUnit.NANOSECONDS.sleep(waitNs)
                } else {
                    Thread.sleep(interval)
                }
                var navigationAt = 0L
                val tap = args.getString("actionInput") == "tap"
                val swipe = args.getString("actionInput") == "swipe"
                val pageKey = args.getString("actionInput") == "page-key"
                val tapPoint = FloatArray(4)
                val inputObservation = JSONObject().put("intendedTargetReaderPage", next)
                    .put("plannedIntervalMs", interval).put("timestampsAreObserved", true)
                var keyCode = KeyEvent.KEYCODE_PAGE_DOWN
                instrumentation.runOnMainSync {
                    val viewer = actualViewer(requireNotNull(activity))
                    val page = requireNotNull(viewer.adapter.currentChapter?.pages?.getOrNull(next - 1))
                    navigationAt = System.nanoTime()
                    observer!!.reset(pair, navigationAt)
                    inputObservation.put("beforeSelection", navigationSelection(viewer))
                    if (tap || swipe || pageKey) {
                        val selected = viewer.adapter.items.getOrNull(viewer.pager.currentItem) as? DisplayPage
                        val current = selected?.firstPage?.index ?: viewer.adapter
                            .session(requireNotNull(viewer.adapter.currentChapter))
                            ?.location?.pageId?.sourcePageIndex ?: target - 1
                        val position = IntArray(2).also(viewer.pager::getLocationOnScreen)
                        val forward = next - 1 > current
                        keyCode = if (forward) KeyEvent.KEYCODE_PAGE_DOWN else KeyEvent.KEYCODE_PAGE_UP
                        inputObservation.put("forwardFromActualSelection", forward)
                        val start = if (swipe) {
                            if (forward) 0.25f else 0.75f
                        } else {
                            if (forward) 0.2f else 0.8f
                        }
                        tapPoint[0] = position[0] + viewer.pager.width * start
                        tapPoint[1] = position[1] + viewer.pager.height * 0.5f
                        tapPoint[2] = position[0] + viewer.pager.width * if (forward) 0.75f else 0.25f
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
                } else if (swipe) {
                    val downAt = SystemClock.uptimeMillis()
                    fun send(action: Int, x: Float) {
                        MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, tapPoint[1], 0).also {
                            instrumentation.sendPointerSync(it)
                            it.recycle()
                        }
                    }
                    send(MotionEvent.ACTION_DOWN, tapPoint[0])
                    try {
                        for (step in 1..6) {
                            Thread.sleep(15)
                            send(MotionEvent.ACTION_MOVE, tapPoint[0] + (tapPoint[2] - tapPoint[0]) * step / 6f)
                        }
                    } finally {
                        send(MotionEvent.ACTION_UP, tapPoint[2])
                    }
                } else if (pageKey) {
                    inputObservation.put("keyCode", keyCode).put("downSendObservedNs", System.nanoTime())
                    instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                    inputObservation.put("upSendObservedNs", System.nanoTime())
                    instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                    inputObservation.put("sendKeySyncReturnedObservedNs", System.nanoTime())
                        .put("handlerAcceptance", "NOT_DIRECTLY_OBSERVED; selection change recorded separately")
                }
                instrumentation.runOnMainSync {
                    val after = navigationSelection(actualViewer(requireNotNull(activity)))
                    val before = inputObservation.getJSONObject("beforeSelection")
                    inputObservation.put("afterInputSelection", after).put(
                        "selectionAdvancedObserved",
                        before.optInt("logicalReaderPage") != after.optInt("logicalReaderPage") ||
                            before.getJSONArray("selectedReaderPages").toString() !=
                            after.getJSONArray("selectedReaderPages").toString(),
                    )
                }
                inputObservation.put("intentObservedNs", navigationAt)
                if (fast) {
                    inputObservation.put("plannedIntentNs", fastPlannedAt)
                        .put("intentLatenessNs", (navigationAt - fastPlannedAt).coerceAtLeast(0))
                    fastPlannedAt += TimeUnit.MILLISECONDS.toNanos(interval)
                    val remaining = fastPlannedAt - System.nanoTime()
                    if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining)
                    actionResults.put(observer!!.await(0).put("inputObservation", inputObservation))
                } else {
                    actionResults.put(observer!!.await(timeout).put("inputObservation", inputObservation))
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
                    when (args.getString("actionInput")) {
                        "tap" -> "native-pointer-tap"
                        "swipe" -> "native-pointer-swipe"
                        "page-key" -> "native-hardware-page-key"
                        else -> "viewer.moveToPage"
                    },
                )
            if (args.getString("requireCorrect") != "false") {
                assertTrue("Actual committed pixels did not match frozen input: " + output, allCorrect)
            }
        } catch (error: Throwable) {
            output.put("error", error.javaClass.name + ": " + error.message)
            observer?.let { output.put("lastObservation", it.await(0)) }
            throw error
        } finally {
            cancellationGate?.release?.countDown()
            instrumentation.runOnMainSync {
                cancellationGate?.restore()
                observer?.close()
                activity?.finish()
            }
            restoreReplacedFile?.invoke()
            restorePixelReference?.invoke()
            cancellationGate?.let { output.put("cancellationMatchedResourcesAtFinally", it.snapshot()) }
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

    /** Pause only the actual original factory's physical page2 EOF, never a header read. */
    private class NativeCancellationGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val enteredNs = AtomicLong()
        val nativeCloses = AtomicInteger()
        val nativeCreates = AtomicInteger()
        val nativeRecycles = AtomicInteger()
        val replacementNativeCreates = AtomicInteger()
        val replacementNativeRecycles = AtomicInteger()
        val result = JSONObject().put("readerPage", 2).put("physicalBytesReplaced", false)
            .put("injection", "forwarded-download-stream-physical-eof-inside-original-native-factory")
        private val nativeCloseReturns = AtomicInteger()
        private val nativeBytes = AtomicLong()
        private val nativeRegionCalls = AtomicInteger()
        private val replacementNativeBytes = AtomicLong()
        private val replacementNativeCloses = AtomicInteger()
        private val replacementNativeCloseReturns = AtomicInteger()
        private val replacementNativeRegionCalls = AtomicInteger()
        private val gateTimedOut = AtomicBoolean()
        private val enteredOnce = AtomicBoolean()
        private val nativeScope = ThreadLocal<Boolean>()
        private val nativeMatch = ThreadLocal<NativeStream>()
        private var installed = false
        private var disabled = false
        private var restoreOriginal: (() -> Unit)? = null
        private var replaceOriginal: (() -> Unit)? = null

        // Main-thread callback, before any model observation or original request is injected.
        fun install(viewer: DualPageR2LPagerViewer) {
            if (installed || disabled) return
            val chapter = viewer.adapter.currentChapter ?: return
            val page = chapter.pages?.getOrNull(1) ?: return
            val originalStream = page.stream ?: return
            val owner = viewer.adapter.session(chapter) ?: return
            val id = owner.identity.pageIds.getOrNull(1) ?: return
            val sampleOwner = requireNotNull(fieldOrNull(owner, "spreadSamples"))
            val requests = requireNotNull(fieldOrNull(sampleOwner, "requests") as? Map<*, *>)
            val analysis = owner.originalAnalysis(id)
            val requestAlreadyIssued = requests.containsKey(id)
            result.put("installObservedNs", System.nanoTime()).put("originalAnalysisAtInstall", analysis.toString())
                .put("originalRequestAlreadyIssued", requestAlreadyIssued)
            if (analysis != ReaderPageAnalysisEvidence.NotAnalyzed || requestAlreadyIssued) {
                disabled = true
                result.put("installationFailure", "Page2 was already requested or analyzed")
                return
            }
            val originalFactory = viewer.adapter.spreadDecoderFactory
            if (originalFactory !== AndroidReaderSpreadSampler.nativeDecoder) {
                disabled = true
                result.put("installationFailure", "The production factory was not the actual native factory")
                return
            }
            installed = true
            result.put("installedBeforeOriginalRequest", true).put("nativeFactoryClass", originalFactory.javaClass.name)
                .put("pageClass", page.javaClass.name).put("loaderClass", chapter.pageLoader?.javaClass?.name)
                .put("contentBeforeWrapper", page.encodedContentIdentity())
            // Changing this factory invalidates the capability, while forwarding unchanged bytes.
            page.stream = { NativeStream(originalStream()) }
            result.put("contentAfterWrapper", page.encodedContentIdentity())
            viewer.adapter.spreadDecoderFactory = { input ->
                val priorScope = nativeScope.get()
                val priorMatch = nativeMatch.get()
                nativeScope.set(true)
                nativeMatch.remove()
                try {
                    val decoder = originalFactory(input)
                    val matched = nativeMatch.get()
                    if (matched == null) {
                        decoder
                    } else {
                        val creates = if (matched.replacement) replacementNativeCreates else nativeCreates
                        val recycles = if (matched.replacement) replacementNativeRecycles else nativeRecycles
                        val regions = if (matched.replacement) replacementNativeRegionCalls else nativeRegionCalls
                        creates.incrementAndGet()
                        object : ReaderPageRegionDecoder {
                            override fun decode(region: Rect): Bitmap {
                                regions.incrementAndGet()
                                return decoder.decode(region)
                            }
                            override fun recycle() {
                                decoder.recycle()
                                recycles.incrementAndGet()
                            }
                        }
                    }
                } finally {
                    if (priorScope == null) nativeScope.remove() else nativeScope.set(priorScope)
                    if (priorMatch == null) nativeMatch.remove() else nativeMatch.set(priorMatch)
                }
            }
            restoreOriginal = {
                viewer.adapter.spreadDecoderFactory = originalFactory
                page.stream = originalStream
            }
            replaceOriginal = { page.stream = { NativeStream(originalStream(), replacement = true) } }
        }

        fun currentOriginalRequest(viewer: DualPageR2LPagerViewer): ReaderSpreadSampleRequest {
            val chapter = requireNotNull(viewer.adapter.currentChapter)
            val owner = requireNotNull(viewer.adapter.session(chapter))
            val sampleOwner = requireNotNull(fieldOrNull(owner, "spreadSamples"))
            val requests = requireNotNull(fieldOrNull(sampleOwner, "requests") as? Map<*, *>)
            return requireNotNull(requests[owner.identity.pageIds[1]] as? ReaderSpreadSampleRequest)
        }

        fun replaceStreamFactory() = requireNotNull(replaceOriginal).invoke()

        private inner class NativeStream(
            private val physical: InputStream,
            val replacement: Boolean = false,
        ) : FilterInputStream(physical) {
            private var readByNative = false
            override fun read(): Int = observed(physical.read(), singleByte = true)
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                observed(physical.read(buffer, offset, length), singleByte = false)

            private fun observed(count: Int, singleByte: Boolean): Int {
                if (nativeScope.get() != true) return count
                readByNative = true
                nativeMatch.set(this)
                if (count >= 0) {
                    val bytes = if (replacement) replacementNativeBytes else nativeBytes
                    bytes.addAndGet(if (singleByte) 1L else count.toLong())
                } else if (!replacement && enteredOnce.compareAndSet(false, true)) {
                    enteredNs.set(System.nanoTime())
                    entered.countDown()
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        gateTimedOut.set(true)
                        throw IOException("Test-only physical EOF gate timed out")
                    }
                }
                return count
            }

            override fun close() {
                val closes = if (replacement) replacementNativeCloses else nativeCloses
                val returns = if (replacement) replacementNativeCloseReturns else nativeCloseReturns
                if (readByNative) closes.incrementAndGet()
                physical.close()
                if (readByNative) returns.incrementAndGet()
            }
        }

        fun jobSnapshot(job: Job): JSONObject = JSONObject().put("observedNs", System.nanoTime())
            .put("jobIdentity", System.identityHashCode(job)).put("active", job.isActive)
            .put("cancelled", job.isCancelled).put("completed", job.isCompleted)
            .put("matchedPhysicalCloseCalls", nativeCloses.get())

        fun snapshot(): JSONObject = JSONObject().put("installed", installed).put("disabled", disabled)
            .put("enteredPhysicalEofNs", enteredNs.get()).put("gateTimedOut", gateTimedOut.get())
            .put("matchedPhysicalBytes", nativeBytes.get()).put("matchedPhysicalCloseCalls", nativeCloses.get())
            .put("matchedPhysicalCloseReturns", nativeCloseReturns.get())
            .put("matchedNativeCreates", nativeCreates.get())
            .put("matchedNativeRecycles", nativeRecycles.get()).put("matchedNativeRegionCalls", nativeRegionCalls.get())
            .put("replacementNativeBytes", replacementNativeBytes.get())
            .put("replacementPhysicalCloseCalls", replacementNativeCloses.get())
            .put("replacementPhysicalCloseReturns", replacementNativeCloseReturns.get())
            .put("replacementNativeCreates", replacementNativeCreates.get())
            .put("replacementNativeRecycles", replacementNativeRecycles.get())
            .put("replacementNativeRegionCalls", replacementNativeRegionCalls.get())

        fun requireReleasedResources() {
            check(!gateTimedOut.get() && nativeCloseReturns.get() == 1)
            check(nativeRegionCalls.get() == 0) { "Cancelled sampler still entered native region decoding" }
        }

        fun restore() {
            disabled = true
            restoreOriginal?.invoke()
            restoreOriginal = null
            replaceOriginal = null
        }
    }

    private class Observer(
        private val activity: ReaderActivity,
        expected: List<Int>,
        private val references: JSONObject,
        started: Long,
        private val originalReferences: JSONObject? = null,
        private val traceNative: Boolean = false,
        private val traceKeys: Boolean = false,
        private val onChapterObserved: ((DualPageR2LPagerViewer) -> Unit)? = null,
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
        private var currentOptionalJobsObserved = 0
        private val sampleCacheProbePages = expected.toList()
        private var sequence = 0L
        private var frameKey = ""
        private var pixelChecks = JSONObject()
        private val attachedAt = System.nanoTime()
        private val rejects = linkedMapOf<String, Int>()
        private val nativeHistory = JSONArray()
        private val nativeStates = linkedMapOf<String, String>()
        private val nativeSnapshots = linkedMapOf<String, JSONObject>()
        private val nativeFirstTimes = linkedMapOf<String, JSONObject>()
        private var attachedNativeKeys = emptySet<String>()
        private val firstActualDraws = linkedSetOf<String>()
        private val firstGpuCallbacks = linkedSetOf<String>()
        private var omittedNativeTraceEvents = 0
        private val receivedKeys = JSONArray()
        private var originalKeyCallback: Window.Callback? = null
        private var keyCallback: Window.Callback? = null
        private var lastFactPhaseState = ""

        fun start() {
            if (traceKeys) {
                val original = requireNotNull(activity.window.callback)
                originalKeyCallback = original
                val wrapper = object : Window.Callback by original {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        val observation = JSONObject().put("receivedObservedNs", System.nanoTime())
                            .put("action", event.action).put("keyCode", event.keyCode)
                            .put("keyName", KeyEvent.keyCodeToString(event.keyCode)).put("source", event.source)
                            .put("deviceId", event.deviceId).put("flags", event.flags)
                            .put("repeatCount", event.repeatCount)
                            .put("downTimeMs", event.downTime).put("eventTimeMs", event.eventTime)
                            .put("windowHasFocus", activity.hasWindowFocus()).put("decorHasFocus", root.hasFocus())
                            .put("focusedView", activity.currentFocus?.javaClass?.name ?: "NONE")
                        (activity.viewModel.state.value.viewer as? DualPageR2LPagerViewer)?.let {
                            observation.put("beforeDispatchSelection", navigationSelection(it))
                        }
                        try {
                            return original.dispatchKeyEvent(event).also {
                                observation.put("originalWindowCallbackHandled", it)
                            }
                        } finally {
                            (activity.viewModel.state.value.viewer as? DualPageR2LPagerViewer)?.let {
                                observation.put("afterDispatchSelection", navigationSelection(it))
                            }
                            observation.put("forwardedReturnObservedNs", System.nanoTime())
                            if (receivedKeys.length() < 128) receivedKeys.put(observation)
                        }
                    }
                }
                keyCallback = wrapper
                activity.window.callback = wrapper
            }
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
            onChapterObserved?.invoke(viewer)
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
                if (traceNative) {
                    val target = owner.location?.pageId?.takeIf { it.sourcePageIndex + 1 in expected }
                        ?: owner.identity.pageIds.getOrNull(expected.first() - 1)
                    val marker = lastBudget.toString() + ":" + owner.layoutRevision + ":" +
                        fieldOrNull(owner, "sampleWorkRevision") + ":" + fieldOrNull(owner, "factWorkRevision")
                    if (marker != lastFactPhaseState) {
                        lastFactPhaseState = marker
                        val phase = JSONObject().put("kind", "BUDGET_AND_PURE_CERTIFICATION_OBSERVED")
                            .put("sequence", sequence).put("readerPage", target?.sourcePageIndex?.plus(1))
                            .put("budget", JSONObject(lastBudget.toString()))
                            .put("ownerLayoutRevision", owner.layoutRevision)
                        // SharedDualPageSession.certifyPairing builds a local proof only. Do not call
                        // adapter.requirePairing or captureCertifiedViewport, which start/own work.
                        val certificateQuery = owner.javaClass.methods.firstOrNull {
                            it.name.startsWith("certifyPairing") && it.parameterCount == 1
                        }
                        val queryAt = System.nanoTime()
                        try {
                            val certificate = if (target != null) certificateQuery?.invoke(owner, target) else null
                            phase.put(
                                "certification",
                                certificate?.javaClass?.simpleName ?: "NOT_AVAILABLE_IN_BASELINE",
                            )
                            val needs = certificate?.javaClass?.methods?.firstOrNull {
                                it.name == "getFacts" && it.parameterCount == 0
                            }?.invoke(certificate) as? Collection<*>
                            phase.put("neededFacts", JSONArray(needs?.map { it.toString() }.orEmpty()))
                        } catch (error: Exception) {
                            phase.put("certification", "UNAVAILABLE: " + error.javaClass.simpleName)
                        }
                        phase.put("pureProofQueryNs", System.nanoTime() - queryAt)
                            .put("observedNs", System.nanoTime()).put("jniCompletionMeasured", false)
                        appendNativeTrace(phase)
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
            currentOptionalJobsObserved = counter?.value as? Int ?: 0
            observedOptionalJobs = maxOf(observedOptionalJobs, currentOptionalJobsObserved)
            val attached = linkedSetOf<String>()
            for (image in views(root).filterIsInstance<ReaderPageImageView>()) {
                val page = fieldOrNull(image, "page") as? ReaderPage ?: continue
                val native = views(image).filterIsInstance<SubsamplingScaleImageView>().singleOrNull() ?: continue
                if (traceNative) attached += observeNativeInstance(viewer, image, page, native)
                if (native.isReady && page.index + 1 in expected) {
                    val event = events.getOrPut(page.index + 1) { JSONObject() }
                    if (!event.has("nativeReadyObservedNs")) event.put("nativeReadyObservedNs", System.nanoTime())
                    observeNormalBitmap(image, native, event)
                }
                if (image.onImageDrawn != null && image.onImageDrawn !== hooks[image]) {
                    val original = image.onImageDrawn
                    val hook = {
                        original?.invoke()
                        if (!closed && traceNative) {
                            val bitmap = (fieldOrNull(image, "sampledPageBitmap") as? java.lang.ref.WeakReference<*>)
                                ?.get() as? Bitmap
                            val key = nativeKey(image, page, native) + ":" + page.encodedContentIdentity() + ":" +
                                bitmap?.let(System::identityHashCode)
                            if (firstActualDraws.add(key)) {
                                nativeTrace(
                                    "FIRST_ACTUAL_ON_IMAGE_DRAWN_CALLBACK",
                                    image,
                                    page,
                                    native,
                                    JSONObject().put("originalCallbackForwardedFirst", true),
                                )
                            }
                        }
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
            if (traceNative) {
                for (key in attachedNativeKeys - attached) {
                    val previous = nativeSnapshots[key] ?: continue
                    appendNativeTrace(
                        JSONObject(previous.toString()).put("kind", "INSTANCE_NO_LONGER_IN_TREE_OBSERVED")
                            .put("observedNs", System.nanoTime()).put("sequence", sequence),
                    )
                }
                attachedNativeKeys = attached
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
                    if (!closed && traceNative) {
                        val key = nativeKey(image, page, native) + ":" + content + ":" + frame.id
                        if (firstGpuCallbacks.add(key)) {
                            nativeTrace(
                                "FIRST_GPU_COMMIT_CALLBACK_FOR_CAPTURED_FRAME",
                                image,
                                page,
                                native,
                                JSONObject().put("capturedSequence", operation)
                                    .put("capturedFrame", frame.id.toString()).put("capturedEncodedContent", content)
                                    .put("gpuCallbackObservedNs", committed)
                                    .put("capturedScopeMatchesMainSnapshot", holder.presentationScope == captured)
                                    .put("pixelCorrectnessNotYetVerified", true),
                            )
                        }
                    }
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
            .put("currentOptionalJobsObserved", currentOptionalJobsObserved)
            .put("nativePeakAllocationBytes", "NOT_AVAILABLE").put("streamOpenCloseCounts", "NOT_AVAILABLE")
            .put("verifiedFrameCapability", frameKey).put("pixelChecks", pixelChecks)
            .put("observerRejectCounts", JSONObject(rejects as Map<*, *>))
            .put("actualHolderFrames", holderFrames())
            .put("runtimeOriginalSampleCache", if (traceNative) sampleCacheSnapshot() else "OBSERVATION_DISABLED")
            .put(
                "keyDeliveryTrace",
                JSONObject().put("enabled", traceKeys).put("events", JSONArray(receivedKeys.toString()))
                    .put("windowHasFocusAtSnapshot", activity.hasWindowFocus())
                    .put("windowCallbackClass", activity.window.callback?.javaClass?.name)
                    .put("forwardingHookStillInstalled", traceKeys && activity.window.callback === keyCallback),
            )
            .put(
                "nativeLifecycleTrace",
                JSONObject().put("enabled", traceNative).put("clock", "System.nanoTime")
                    .put("stateTimesAreObservedUpperBounds", true).put("jniDecodeCompletionMeasured", false)
                    .put("firstCallbacksOnly", true).put("omittedEvents", omittedNativeTraceEvents)
                    .put("events", JSONArray(nativeHistory.toString())),
            )

        private fun nativeKey(image: ReaderPageImageView, page: ReaderPage, native: SubsamplingScaleImageView): String =
            page.chapter.chapter.id.toString() + ":" + (page.index + 1) + ":" +
                System.identityHashCode(image) + ":" + System.identityHashCode(native)

        private fun nativeTrace(
            kind: String,
            image: ReaderPageImageView,
            page: ReaderPage,
            native: SubsamplingScaleImageView,
            extra: JSONObject = JSONObject(),
        ): JSONObject {
            val holder = generateSequence(image.parent) { it.parent }
                .filterIsInstance<DualPagerPageHolder>().firstOrNull()
            val scope = holder?.presentationScope
            val frame = scope?.first?.frame(scope.second)
            val key = nativeKey(image, page, native)
            val generation = (holder?.let { fieldOrNull(it, "decodeGenerations") } as? Map<*, *>)?.get(page)
            val boundEncoded = (holder?.let { fieldOrNull(it, "boundEncodedContents") } as? Map<*, *>)?.get(page)
            val currentBinding = boundEncoded == page.encodedContentIdentity()
            val phaseKey = key + ":" + page.encodedContentIdentity() + ":" + generation
            val firstTimes = nativeFirstTimes.getOrPut(phaseKey) { JSONObject() }
            val value = extra.put("kind", kind).put("sequence", sequence)
                .put("instanceKey", key).put("chapterId", page.chapter.chapter.id).put("readerPage", page.index + 1)
                .put("encodedContent", page.encodedContentIdentity()).put("displayContent", page.contentIdentity())
                .put("imageId", System.identityHashCode(image)).put("nativeId", System.identityHashCode(native))
                .put("holderId", holder?.let(System::identityHashCode))
                .put("nativeReady", native.isReady).put("nativeImageLoaded", native.isImageLoaded)
                .put("decodeGeneration", generation).put("phaseKey", phaseKey)
                .put("boundEncodedContent", boundEncoded).put("currentEncodedBinding", currentBinding)
                .put("scope", scope?.second?.toString() ?: "NONE").put("frameId", frame?.id?.toString() ?: "NONE")
                .put("drawnReaderPages", JSONArray(frame?.drawnPageIds?.map { it.sourcePageIndex + 1 }.orEmpty()))
                .put("lockedReaderPages", JSONArray(frame?.lockedPageIds?.map { it.sourcePageIndex + 1 }.orEmpty()))
            val observedAt = System.nanoTime()
            fun first(name: String) {
                if (!firstTimes.has(name)) firstTimes.put(name, observedAt)
            }
            if (value.getBoolean("nativeReady") && currentBinding) first("nativeReadyFirstObservedNs")
            if (extra.optBoolean("computedObserved") && extra.optBoolean("ownerContentMatchesCurrentPage")) {
                first("computedFirstObservedNs")
            }
            if (kind == "FIRST_ACTUAL_ON_IMAGE_DRAWN_CALLBACK" && currentBinding) {
                first("actualDrawCallbackFirstObservedNs")
            }
            // Binding belongs to this later Main snapshot, not to the earlier GPU callback.
            value.put("observedNs", observedAt).put("firstTimesObservedUpper", JSONObject(firstTimes.toString()))
            nativeSnapshots[key] = value
            appendNativeTrace(value)
            return value
        }

        private fun appendNativeTrace(value: JSONObject) {
            if (nativeHistory.length() < 512) nativeHistory.put(value) else omittedNativeTraceEvents++
        }

        private fun observeNativeInstance(
            viewer: DualPageR2LPagerViewer,
            image: ReaderPageImageView,
            page: ReaderPage,
            native: SubsamplingScaleImageView,
        ): String {
            val key = nativeKey(image, page, native)
            val owner = viewer.adapter.session(page.chapter)
            val id = owner?.identity?.pageIds?.getOrNull(page.index)
            val analysis = owner?.javaClass?.methods?.firstOrNull {
                it.name.startsWith("originalAnalysis") && it.parameterCount == 1
            }
            val evidence = if (owner != null && id != null) analysis?.invoke(owner, id)?.toString() else null
            val ownerContent = owner?.identity?.content?.getOrNull(page.index)?.contentVersion
            val bitmap = (fieldOrNull(image, "sampledPageBitmap") as? java.lang.ref.WeakReference<*>)?.get() as? Bitmap
            val bitmapId = bitmap?.let(System::identityHashCode) ?: 0
            val recycled = bitmap?.isRecycled == true
            val state = listOf(
                sequence,
                page.encodedContentIdentity(),
                native.isReady,
                native.isImageLoaded,
                bitmapId,
                recycled,
                evidence,
            ).joinToString("|")
            if (nativeStates[key] != state) {
                nativeStates[key] = state
                val observation = JSONObject().put("analysis", evidence ?: "NOT_AVAILABLE_IN_BASELINE")
                    .put("computedObserved", evidence?.startsWith("Computed") == true)
                    .put("ownerEncodedContent", ownerContent)
                    .put("ownerContentMatchesCurrentPage", ownerContent == page.encodedContentIdentity())
                    .put("bitmapId", bitmapId).put("bitmapRecycled", recycled)
                    .put("bitmapWidth", if (bitmap != null && !recycled) bitmap.width else 0)
                    .put("bitmapHeight", if (bitmap != null && !recycled) bitmap.height else 0)
                    .put("bitmapConfig", if (bitmap != null && !recycled) bitmap.config?.name else "UNAVAILABLE")
                nativeTrace("NATIVE_OR_BITMAP_OR_ANALYSIS_STATE_OBSERVED", image, page, native, observation)
            }
            return key
        }

        private fun reject(reason: String) {
            rejects[reason] = (rejects[reason] ?: 0) + 1
        }

        private fun sampleCacheSnapshot(): JSONObject {
            val viewer = activity.viewModel.state.value.viewer as? DualPageR2LPagerViewer
                ?: return JSONObject().put("status", "NO_VIEWER")
            val owner = viewer.adapter.currentChapter?.let(viewer.adapter::session)
                ?: return JSONObject().put("status", "NO_OWNER")
            val sampler = fieldOrNull(owner, "spreadSamples")
                ?: return JSONObject().put("status", "NOT_AVAILABLE_IN_THIS_HOST")
            val samples = fieldOrNull(sampler, "samples") as? Map<*, *>
                ?: return JSONObject().put("status", "NO_RUNTIME_SAMPLE_MAP")
            val analyses = fieldOrNull(sampler, "analyses") as? Map<*, *>
            val scores = fieldOrNull(sampler, "scores") as? Map<*, *>
            val originals = fieldOrNull(sampler, "originalSamples") as? Set<*>
            fun readerNumbers(keys: Collection<*>): List<Int> = keys.mapNotNull {
                owner.identity.pageIds.indexOfFirst { id -> id == it }.takeIf { index -> index >= 0 }?.plus(1)
            }.sorted()
            val pages = JSONArray(
                sampleCacheProbePages.map { readerPage ->
                    val id = owner.identity.pageIds.getOrNull(readerPage - 1)
                    val analysis = id?.let { analyses?.get(it) }
                    JSONObject().put("readerPage", readerPage).put("vectorPresent", id in samples)
                        .put("originalVectorPresent", originals?.contains(id) ?: false)
                        .put("analysisKind", analysis?.javaClass?.simpleName ?: "NOT_AVAILABLE")
                        .put("analysis", analysis?.toString() ?: "NOT_AVAILABLE")
                },
            )
            val edgeQuery = sampler.javaClass.methods.firstOrNull {
                it.name.startsWith("edgeAnalysis") && it.parameterCount == 3
            }
            val direction = fieldOrNull(owner, "direction")
            val edges = JSONArray(
                sampleCacheProbePages.zipWithNext().map { (first, second) ->
                    val a = owner.identity.pageIds.getOrNull(first - 1)
                    val b = owner.identity.pageIds.getOrNull(second - 1)
                    val evidence = if (a != null && b != null && direction != null) {
                        edgeQuery?.invoke(sampler, a, b, direction)
                    } else {
                        null
                    }
                    JSONObject().put("firstReaderPage", first).put("secondReaderPage", second)
                        .put("kind", evidence?.javaClass?.simpleName ?: "NOT_AVAILABLE")
                        .put("evidence", evidence?.toString() ?: "NOT_AVAILABLE")
                },
            )
            return JSONObject().put("status", "OBSERVED_READ_ONLY").put("observedNs", System.nanoTime())
                .put("capacity", fieldOrNull(sampler, "sampleCapacity"))
                .put("sampleReaderPages", JSONArray(readerNumbers(samples.keys)))
                .put("originalSampleReaderPages", JSONArray(readerNumbers(originals.orEmpty())))
                .put("sampleCount", samples.size).put("analysisCount", analyses?.size)
                .put("computedAnalysisCount", analyses?.values?.count { it?.javaClass?.simpleName == "Computed" })
                .put("scalarEdgeCount", scores?.size).put("probePages", pages).put("probeEdges", edges)
                .put("persistentAnalysisCache", "NOT_IMPLEMENTED; this is runtime vector eviction")
                .put("ordinaryEncodedReadCounts", "NOT_MEASURED; no zero-IO claim")
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
            if (keyCallback != null && activity.window.callback === keyCallback) {
                originalKeyCallback?.let { activity.window.callback = it }
            }
            root.viewTreeObserver.removeOnPreDrawListener(this)
            hooks.forEach { (image, hook) -> if (image.onImageDrawn === hook) image.onImageDrawn = originals[image] }
        }
    }

    private companion object {
        fun navigationSelection(viewer: DualPageR2LPagerViewer): JSONObject {
            val selected = viewer.adapter.items.getOrNull(viewer.pager.currentItem) as? DisplayPage
            val current = fieldOrNull(viewer, "currentPage")
            val owner = viewer.adapter.currentChapter?.let(viewer.adapter::session)
            return JSONObject().put("observedNs", System.nanoTime()).put("pagerItem", viewer.pager.currentItem)
                .put("selectedReaderPages", JSONArray(selected?.visiblePages?.map { it.index + 1 }.orEmpty()))
                .put("logicalReaderPage", owner?.location?.pageId?.sourcePageIndex?.plus(1))
                .put("locationSequence", owner?.location?.sequence)
                .put("viewerClass", viewer.javaClass.name).put("currentPageType", current?.javaClass?.name ?: "NONE")
                .put(
                    "currentPageReaderPages",
                    JSONArray((current as? DisplayPage)?.visiblePages?.map { it.index + 1 }.orEmpty()),
                )
                .put("replacingPairing", fieldOrNull(viewer, "replacingPairing"))
                .put("gestureInProgress", fieldOrNull(viewer, "gestureInProgress"))
                .put("idle", fieldOrNull(viewer, "isIdle"))
        }

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
