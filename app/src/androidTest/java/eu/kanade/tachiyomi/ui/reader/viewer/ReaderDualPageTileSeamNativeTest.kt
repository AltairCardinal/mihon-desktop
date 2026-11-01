package eu.kanade.tachiyomi.ui.reader.viewer

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.SqlDriver
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPagerPageHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real original decoder, SSIV tiles, production holder, and compositor pixels on the isolated AVD. */
class ReaderDualPageTileSeamNativeTest {
    @Test
    fun originalEncodedTilesCoverSharedSpineAcrossOddSizesAndResizeBack() {
        LocalSeamFixture().use { fixture ->
            val encoded = Bitmap.createBitmap(4501, 6401, Bitmap.Config.ARGB_8888).let { bitmap ->
                try {
                    bitmap.eraseColor(Color.RED)
                    ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                } finally {
                    bitmap.recycle()
                }
            }
            fixture.launch(encoded)
            fixture.awaitStable("Both original encoded pages must paint") {
                fixture.currentImages().size == 2 && fixture.containsColor(Color.RED)
            }
            val initialImages = fixture.currentImages()
            lateinit var holder: DualPagerPageHolder
            fixture.onReaderMain { activity ->
                assertTrue(
                    "Seam AVD needs a window at least 1461 by 1001",
                    activity.window.decorView.width >= 1461 && activity.window.decorView.height >= 1001,
                )
                var ancestor = initialImages.first().parent
                while (ancestor !is DualPagerPageHolder) ancestor = ancestor.parent
                holder = ancestor
            }
            for (height in listOf(1000, 1001)) {
                for (width in listOf(1283, 1327, 1393, 1401, 1461, 1400)) {
                    fixture.onReaderMain {
                        val pager = holder.parent as View
                        pager.layoutParams = pager.layoutParams.apply {
                            this.width = width
                            this.height = height
                        }
                        pager.requestLayout()
                    }
                    fixture.awaitStable("Actual holder must resize to ${width}x$height and retain both pages") {
                        holder.width == width && holder.height == height && fixture.currentImages().size == 2
                    }
                    val frame = requireNotNull(fixture.frame())
                    try {
                        var spine = 0
                        var top = 0
                        val mountedImages = fixture.currentImages()
                        fixture.onReaderMain {
                            val images = mountedImages.sortedBy { image ->
                                IntArray(2).also((image.parent as View)::getLocationInWindow)[0]
                            }
                            val leftSlot = images.first().parent as View
                            val location = IntArray(2).also(leftSlot::getLocationInWindow)
                            spine = location[0] + leftSlot.width
                            top = location[1]
                            images.forEach { image ->
                                val native = (image as ReaderPageImageView).getChildAt(0) as SubsamplingScaleImageView
                                assertEquals(4501, native.sWidth)
                                assertEquals(6401, native.sHeight)
                            }
                        }
                        val evidence = requireNotNull(fixture.context.getExternalFilesDir(null))
                            .resolve("tile-seam-${width}x$height.png")
                        evidence.outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        android.util.Log.i(
                            "ReaderTileSeam",
                            "viewport=${width}x$height spine=$spine evidence=${evidence.absolutePath}",
                        )
                        for (y in top + height / 5 until top + height * 4 / 5) {
                            for (x in spine - 2..spine + 2) {
                                val pixel = frame.getPixel(x, y)
                                assertTrue(
                                    "Original tile seam ${width}x$height x=$x y=$y color=$pixel",
                                    Color.red(pixel) > 220 && Color.green(pixel) < 30 && Color.blue(pixel) < 30,
                                )
                            }
                        }
                    } finally {
                        frame.recycle()
                    }
                }
            }
        }
    }

    /** Self-contained fictional local pages through the actual loader, reader and native decoder. */
    private class LocalSeamFixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        private val preferences = context.getSharedPreferences("${context.packageName}_preferences", 0)
        private val settings = mapOf<String, Any>(
            "pref_default_reading_mode_key" to 6,
            "reader_auto_spread_matching" to false,
            "pref_dual_page_split" to false,
            "pref_dual_page_invert" to false,
            "crop_borders" to false,
            "landscape_zoom" to false,
            "pref_image_scale_type_key" to 1,
            "pref_zoom_start_key" to 4,
            "reader_navigation_overlay_new_user" to false,
            "reader_navigation_overlay_on_start" to false,
        )
        private val previousSettings = (settings.keys + "__APP_STATE_storage_dir").associateWith { preferences.all[it] }
        private val directory = File(context.filesDir, "tile-seam-native-${SystemClock.elapsedRealtimeNanos()}")
        private var reader: ReaderActivity? = null
        private val destroyed = CountDownLatch(1)
        private var lifecycle: Application.ActivityLifecycleCallbacks? = null

        init {
            check(Build.HARDWARE == "ranchu") { "Only the task-owned emulator is allowed" }
            check(InstrumentationRegistry.getArguments().getString("tileSeamFixture") == "true")
            check(
                context.packageName == "app.mihon.desktop.fork" || context.packageName == "app.mihon.desktop.fork.dev",
            )
        }

        fun launch(encoded: ByteArray) {
            val title = "Tile seam synthetic"
            val chapterDirectory = File(directory, "local/$title/Chapter")
            check(chapterDirectory.mkdirs())
            repeat(4) { File(chapterDirectory, "%03d.png".format(it)).writeBytes(encoded) }
            // Instantiate the real observer before changing its preference. Its creation of these
            // sibling directories confirms actual rebinding without assuming a Release-only getter ABI.
            Injekt.get<StorageManager>()
            preferences.edit().apply {
                putString("__APP_STATE_storage_dir", Uri.fromFile(directory).toString())
                settings.forEach { (key, value) ->
                    when (value) {
                        is Int -> putInt(key, value)
                        is Boolean -> putBoolean(key, value)
                    }
                }
            }.also { check(it.commit()) }
            awaitStable("Production storage observer must bind the owned directory") {
                File(directory, "autobackup").isDirectory && File(directory, "downloads").isDirectory
            }
            val manga = 7911101001L
            val chapter = 7911101002L
            val driver = Injekt.get<SqlDriver>()
            // The production DI driver and its retained execute ABI, never a second SQLite connection.
            // Pass binders explicitly: Release R8 removes SqlDriver.DefaultImpls default-argument helpers.
            driver.execute(
                null,
                "INSERT INTO mangas(_id,source,url,title,status,favorite,initialized,viewer," +
                    "chapter_flags,cover_last_modified,date_added) VALUES(" +
                    "$manga,0,'$title','$title',0,0,1,6,0,0,0) ON CONFLICT(_id) DO UPDATE SET viewer=6 " +
                    "WHERE source=0 AND url='$title'",
                0,
                null,
            )
            driver.execute(
                null,
                "INSERT INTO chapters(_id,manga_id,url,name,read,bookmark,last_page_read," +
                    "chapter_number,source_order,date_fetch,date_upload) VALUES(" +
                    "$chapter,$manga,'$title/Chapter','Chapter',0,0,1,1,0,0,0) " +
                    "ON CONFLICT(_id) DO UPDATE SET read=0,last_page_read=1 " +
                    "WHERE manga_id=$manga AND url='$title/Chapter'",
                0,
                null,
            )
            val resumed = CountDownLatch(1)
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: Bundle?) {
                    if (activity is ReaderActivity) reader = activity
                }
                override fun onActivityResumed(activity: Activity) {
                    if (activity === reader) resumed.countDown()
                }
                override fun onActivityDestroyed(activity: Activity) {
                    if (activity === reader) destroyed.countDown()
                }
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            }
            lifecycle = callbacks
            instrumentation.runOnMainSync {
                (context.applicationContext as Application).registerActivityLifecycleCallbacks(callbacks)
            }
            // A reader can keep drawing; wait for its real lifecycle rather than global queue idleness.
            context.startActivity(
                Intent().setClassName(context.packageName, "eu.kanade.tachiyomi.ui.reader.ReaderActivity")
                    .putExtra("manga", manga)
                    .putExtra("chapter", chapter)
                    .putExtra("resume", false)
                    .putExtra("resumeWithinChapter", false)
                    .putExtra("page_index", 1)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            assertTrue("The actual ReaderActivity must resume", resumed.await(30, TimeUnit.SECONDS))
        }

        fun onReaderMain(action: (ReaderActivity) -> Unit) =
            instrumentation.runOnMainSync { action(requireNotNull(reader)) }

        private fun views(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

        fun currentImages(): List<View> {
            var images = emptyList<View>()
            onReaderMain { activity ->
                images = views(activity.window.decorView).filter {
                    it is ReaderPageImageView && it.isShown && it.getGlobalVisibleRect(Rect()) && it.width > 0
                }
            }
            return images
        }

        fun frame(): Bitmap? {
            var bitmap: Bitmap? = null
            val latch = CountDownLatch(1)
            var result = PixelCopy.ERROR_UNKNOWN
            onReaderMain { activity ->
                val decor = activity.window.decorView
                if (decor.width > 0 && decor.height > 0) {
                    bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                    PixelCopy.request(activity.window, requireNotNull(bitmap), { code ->
                        result = code
                        latch.countDown()
                    }, Handler(Looper.getMainLooper()))
                } else {
                    latch.countDown()
                }
            }
            check(latch.await(10, TimeUnit.SECONDS)) { "Native PixelCopy timed out" }
            if (result == PixelCopy.SUCCESS) return bitmap
            bitmap?.recycle()
            return null
        }

        fun containsColor(color: Int): Boolean = frame()?.let { bitmap ->
            try {
                (0 until bitmap.width step 8).any { x -> bitmap.getPixel(x, bitmap.height / 2) == color }
            } finally {
                bitmap.recycle()
            }
        } ?: false

        fun awaitStable(message: String, predicate: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 30_000
            var stable = 0
            while (SystemClock.uptimeMillis() < deadline) {
                stable = if (predicate()) stable + 1 else 0
                if (stable >= 3) return
                SystemClock.sleep(100)
            }
            assertTrue(message, predicate())
        }

        override fun close() {
            reader?.let { activity ->
                instrumentation.runOnMainSync { activity.finish() }
                check(destroyed.await(10, TimeUnit.SECONDS)) { "The owned ReaderActivity must be destroyed" }
            }
            lifecycle?.let { callbacks ->
                instrumentation.runOnMainSync {
                    (context.applicationContext as Application).unregisterActivityLifecycleCallbacks(callbacks)
                }
            }
            preferences.edit().apply {
                previousSettings.forEach { (key, value) ->
                    when (value) {
                        null -> remove(key)
                        is Int -> putInt(key, value)
                        is Boolean -> putBoolean(key, value)
                        is String -> putString(key, value)
                    }
                }
            }.also { check(it.commit()) }
            check(directory.canonicalFile.parentFile == context.filesDir.canonicalFile)
            if (directory.exists()) check(directory.deleteRecursively())
        }
    }
}
