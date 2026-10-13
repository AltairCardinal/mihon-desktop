package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.children
import androidx.recyclerview.widget.LinearLayoutManager
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SettingsReaderScreen
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.databinding.ReaderActivityBinding
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.model.completePageListForTest
import eu.kanade.tachiyomi.ui.reader.model.openPageListForTest
import eu.kanade.tachiyomi.ui.reader.model.publishLoadedPageListForTest
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.RecordingReaderActivity
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonAdapter
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import logcat.LogcatLogger
import mihon.domain.reader.ReaderPortraitSingleSlot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.chapter.model.Chapter
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView as NativeImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.OnImageEventListener as NativeImageListener

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, qualifiers = "w480dp-h1040dp-xxhdpi")
class DualPagerPageHolderLayoutTest {
    private lateinit var previousInjekt: InjektScope
    private lateinit var viewer: DualPageR2LPagerViewer
    private val hosts = mutableListOf<ComponentActivity>()
    private var previousFramePause = false
    private lateinit var previousFrameDelay: Duration
    private val diagnosticMessages = mutableListOf<String>()
    private var loggerWasInstalled = false
    private val diagnosticLogger = object : LogcatLogger {
        override fun isLoggable(priority: LogPriority) = priority == LogPriority.INFO
        override fun isLoggable(priority: LogPriority, tag: String) = isLoggable(priority)
        override fun log(priority: LogPriority, tag: String, message: String) {
            if (message.startsWith("DualPagePresentation ")) diagnosticMessages += message
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `actual decoded Android bitmap provides shared gutter side before locking the current slot`() = runTest {
        val bitmap = Bitmap.createBitmap(128, 256, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(40, 40, 40))
        for (x in 122 until 128) for (y in 0 until 256) bitmap.setPixel(x, y, android.graphics.Color.WHITE)
        val encoded = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val chapter = ReaderChapter(Chapter.create().copy(id = 219, mangaId = 1))
        val pages = List(6) { index ->
            ReaderPage(index, stream = { encoded.inputStream() }).apply {
                this.chapter = chapter
                status = Page.State.Ready
            }
        }
        chapter.publishLoadedPageListForTest(pages)
        Injekt.get<ReaderPreferences>().automaticSpreadMatching().set(true)
        runCurrent()
        viewer.setChapters(ViewerChapters(chapter, null, null))
        viewer.moveToPage(pages[3])
        val holder = DualPagerPageHolder(
            viewer.activity,
            viewer,
            viewer.adapter.items.filterIsInstance<DisplayPage>().single { it.containsPage(pages[3]) },
        )
        try {
            load(holder, pages[3], "RIGHT")
            measure(holder, 1440, 3120)
            val image = descendants(holder).filterIsInstance<ReaderPageImageView>().first()
            val currentConfig = ReflectionHelpers.getField<ReaderPageImageView.Config>(image, "config")
            val decoded = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(encoded, 0, encoded.size))
            image.setImage(android.graphics.drawable.BitmapDrawable(image.resources, decoded), currentConfig)
            val output = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            try {
                image.draw(android.graphics.Canvas(output))
            } finally {
                output.recycle()
            }
            val owner = requireNotNull(viewer.adapter.session(chapter))
            val id = owner.identity.pageIds[3]
            assertEquals(mihon.domain.reader.ReaderPageSideHint.LEFT, owner.pageFact(id)!!.sideHint)
            assertTrue(
                diagnosticMessages.toString(),
                diagnosticMessages.any {
                    "event=decoded_fact" in it && "source=3" in it && "observedHint=LEFT" in it &&
                        "sample=ORIGINAL_DECODED" in it && "accepted=true" in it && "actual=RIGHT" in it
                },
            )
            assertTrue(
                diagnosticMessages.toString(),
                diagnosticMessages.any {
                    "event=prepare" in it && "source=3" in it && "prepared=false" in it &&
                        "outcome=FRAME_EXPIRED" in it && "reason=UNAVAILABLE" in it &&
                        "proposedBefore=3:RIGHT" in it && "submittedHint=LEFT" in it
                },
            )
            assertTrue(
                owner.displayedHistory.none { frame ->
                    id in frame.lockedPageIds && frame.unit.slots.any { slot ->
                        slot.pageId == id && slot.position == ReaderPortraitSingleSlot.RIGHT
                    }
                },
            )
            val corrected = DualPagerPageHolder(
                viewer.activity,
                viewer,
                viewer.adapter.items.filterIsInstance<DisplayPage>().single { it.containsPage(pages[3]) },
            )
            try {
                load(corrected, pages[3], "LEFT")
                paint(corrected, pages[3])
                val ticket = requireNotNull(corrected.presentationScope).second
                val frame = requireNotNull(owner.frame(ticket))
                assertTrue(id in frame.drawnPageIds)
                assertEquals(ReaderPortraitSingleSlot.LEFT, frame.unit.slots.single { it.pageId == id }.position)
                assertTrue(
                    diagnosticMessages.any {
                        "event=prepare" in it && "source=3" in it && "prepared=true" in it &&
                            "actual=LEFT" in it && "locked=3:LEFT" in it
                    },
                )
            } finally {
                detach(corrected)
            }
        } finally {
            detach(holder)
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `diagnostics distinguish sampled unknown first page from retained lock without frame spam`() =
        runTest {
            Injekt.get<ReaderPreferences>().automaticSpreadMatching().set(true)
            runCurrent()
            val encoded = readerTestPng(64, 128)
            val chapter = ReaderChapter(Chapter.create().copy(id = 9001, mangaId = 1, name = "private-title"))
            val pages = List(3) { index ->
                ReaderPage(index, stream = { encoded.inputStream() }).apply {
                    this.chapter = chapter
                    status = Page.State.Ready
                }
            }
            chapter.publishLoadedPageListForTest(pages)
            viewer.setChapters(ViewerChapters(chapter, null, null))
            val holder = DualPagerPageHolder(
                viewer.activity,
                viewer,
                viewer.adapter.items.filterIsInstance<DisplayPage>().single { it.containsPage(pages[0]) },
            )
            try {
                load(holder, pages[0], "CENTER")
                measure(holder, 1440, 3120)
                val image = descendants(holder).filterIsInstance<ReaderPageImageView>().first()
                paintReaderImage(image, encoded)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                val output = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                try {
                    repeat(3) { image.draw(android.graphics.Canvas(output)) }
                } finally {
                    output.recycle()
                }
                assertTrue(
                    diagnosticMessages.any {
                        "event=decoded_fact" in it && "source=0" in it && "observedHint=UNKNOWN" in it &&
                            "sample=ORIGINAL_DECODED" in it && "matching=true" in it &&
                            "sideEnabled=true" in it && "crop=false" in it && "animated=false" in it &&
                            "reason=FIRST_PAGE" in it && "sequence=" in it && "neighbors=" in it
                    },
                )
                assertEquals(1, diagnosticMessages.count { "event=prepare" in it && "source=0" in it })
                assertEquals(1, diagnosticMessages.count { "event=retained_lock" in it && "source=0" in it })
                assertTrue(diagnosticMessages.none { "private-title" in it || "9001" in it || "http" in it })
            } finally {
                detach(holder)
            }
        }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `release presentation diagnostics expose a late known hint skipped by an already locked slot`() = runTest {
        Injekt.get<ReaderPreferences>().automaticSpreadMatching().set(true)
        runCurrent()
        val bitmap = Bitmap.createBitmap(128, 256, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(40, 40, 40))
        for (x in 122 until 128) for (y in 0 until 256) bitmap.setPixel(x, y, android.graphics.Color.WHITE)
        val encoded = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val chapter = ReaderChapter(Chapter.create().copy(id = 9002, mangaId = 1))
        val pages = List(6) { index ->
            ReaderPage(index, stream = { encoded.inputStream() }).apply {
                this.chapter = chapter
                status = Page.State.Ready
            }
        }
        chapter.publishLoadedPageListForTest(pages)
        viewer.setChapters(ViewerChapters(chapter, null, null))
        viewer.moveToPage(pages[3])
        val holder = DualPagerPageHolder(
            viewer.activity,
            viewer,
            viewer.adapter.items.filterIsInstance<DisplayPage>().single { it.containsPage(pages[3]) },
        )
        try {
            load(holder, pages[3], "RIGHT")
            measure(holder, 1440, 3120)
            val image = descendants(holder).filterIsInstance<ReaderPageImageView>().first()
            // The real native preparation callback arrives before an eligible whole bitmap sample.
            assertTrue(requireNotNull(image.beforeImageDraw).invoke(128, 256))
            assertTrue(
                diagnosticMessages.any {
                    "event=prepare" in it && "source=3" in it && "sample=NOT_OBSERVED" in it &&
                        "submittedHint=UNKNOWN" in it && "prepared=true" in it && "lockedBefore=false" in it
                },
            )
            paintReaderImage(image, encoded)
            assertTrue(
                diagnosticMessages.any {
                    "event=retained_lock" in it && "source=3" in it && "observedHint=LEFT" in it &&
                        "sample=ORIGINAL_DECODED" in it && "storedHint=UNKNOWN" in it &&
                        "submittedHint=NOT_SUBMITTED" in it && "lockedBefore=true" in it &&
                        "actual=RIGHT" in it && "context=" in it && "serial=" in it && "ownerSequence=" in it
                },
            )
            val owner = requireNotNull(viewer.adapter.session(chapter))
            assertEquals(
                mihon.domain.reader.ReaderPageSideHint.UNKNOWN,
                owner.pageFact(owner.identity.pageIds[3])!!.sideHint,
            )
        } finally {
            detach(holder)
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `actual dual holder consumes each confirmed encoded direction instead of default raw pixels`() = runTest {
        val raw = requireNotNull(javaClass.classLoader?.getResourceAsStream("reader/exif-corners-raw.jpg")).use {
            it.readBytes()
        }
        mihon.domain.reader.ReaderImageOrientation.entries.forEach { orientation ->
            val exif = "45786966000049492a0008000000010012010300010000000100000000000000"
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray().also { it[24] = orientation.exifValue.toByte() }
            val encoded = raw.copyOfRange(0, 2) + byteArrayOf(-1, -31, 0, (exif.size + 2).toByte()) + exif +
                raw.copyOfRange(2, raw.size)
            val chapter = ReaderChapter(Chapter.create().copy(id = 201, mangaId = 1))
            val page = ReaderPage(0, stream = { encoded.inputStream() }).apply {
                this.chapter = chapter
                status = Page.State.Ready
            }
            chapter.publishLoadedPageListForTest(listOf(page))
            page.publishHeaderFact()
            assertEquals(orientation, page.pageFact.value.sourceOrientation)
            viewer.setChapters(ViewerChapters(chapter, null, null))
            runCurrent()
            val holder = DualPagerPageHolder(
                viewer.activity,
                viewer,
                viewer.adapter.items.filterIsInstance<DisplayPage>().single(),
            )
            try {
                load(holder, page, "CENTER")
                val image = descendants(holder).filterIsInstance<ReaderPageImageView>().single()
                assertEquals(
                    "Production dual holder must pass the encoded direction",
                    orientation,
                    ReflectionHelpers.getField<ReaderPageImageView.Config>(image, "config").sourceOrientation,
                )
                assertEquals(orientation, viewer.adapter.session(chapter)!!.identity.content.single().sourceOrientation)
                val native =
                    descendants(image).filterIsInstance<NativeImageView>().single()
                assertEquals(if (orientation.mirrorHorizontally) -1f else 1f, native.scaleX, 0.001f)
            } finally {
                detach(holder)
            }
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `actual ordinary holder consumes encoded direction before its native image binding`() = runTest {
        val raw = requireNotNull(javaClass.classLoader?.getResourceAsStream("reader/exif-corners-raw.jpg")).use {
            it.readBytes()
        }
        val exif = "45786966000049492a0008000000010012010300010000000100000000000000"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray().also { it[24] = 6 }
        val encoded = raw.copyOfRange(0, 2) + byteArrayOf(-1, -31, 0, (exif.size + 2).toByte()) + exif +
            raw.copyOfRange(2, raw.size)
        val chapter = ReaderChapter(Chapter.create().copy(id = 202, mangaId = 1))
        val page = ReaderPage(0, stream = { encoded.inputStream() }).apply {
            this.chapter = chapter
            status = Page.State.Ready
        }
        chapter.publishLoadedPageListForTest(listOf(page))
        page.publishHeaderFact()
        val ordinary = R2LPagerViewer(viewer.activity).apply { config.navigationModeChangedListener = null }
        try {
            ordinary.setChapters(ViewerChapters(chapter, null, null))
            val holder = PagerPageHolder(viewer.activity, ordinary, page)
            val method = PagerPageHolder::class.java.declaredMethods.single {
                it.name == "setImage" &&
                    it.parameterCount == 1
            }.apply { isAccessible = true }
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                val result = method.invoke(holder, continuation)
                if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else Unit
            }
            assertEquals(
                mihon.domain.reader.ReaderImageOrientation.CLOCKWISE,
                ReflectionHelpers.getField<ReaderPageImageView.Config>(holder, "config").sourceOrientation,
            )
            ReflectionHelpers.callInstanceMethod<Unit>(holder, "onDetachedFromWindow")
        } finally {
            ordinary.destroy()
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `ordinary mirrored fit composes after EXIF while original source classification remains wide`() = runTest {
        Injekt.get<ReaderPreferences>().dualPageRotateToFit().set(true)
        val raw = requireNotNull(javaClass.classLoader?.getResourceAsStream("reader/exif-corners-raw.jpg")).use {
            it.readBytes()
        }
        val exif = "45786966000049492a0008000000010012010300010000000100000000000000"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray().also { it[24] = 2 }
        val encoded = raw.copyOfRange(0, 2) + byteArrayOf(-1, -31, 0, (exif.size + 2).toByte()) + exif +
            raw.copyOfRange(2, raw.size)
        val chapter = ReaderChapter(Chapter.create().copy(id = 203, mangaId = 1))
        val page = ReaderPage(0, stream = { encoded.inputStream() }).apply {
            this.chapter = chapter
            status = Page.State.Ready
        }
        chapter.publishLoadedPageListForTest(listOf(page))
        page.publishHeaderFact()
        val ordinary = R2LPagerViewer(viewer.activity).apply { config.navigationModeChangedListener = null }
        try {
            runCurrent()
            assertTrue(ordinary.config.dualPageRotateToFit)
            ordinary.setChapters(ViewerChapters(chapter, null, null))
            val holder = PagerPageHolder(viewer.activity, ordinary, page)
            bindOrdinary(holder)
            assertEquals(
                mihon.domain.reader.ReaderImageOrientation.TRANSVERSE,
                ReflectionHelpers.getField<ReaderPageImageView.Config>(holder, "config").sourceOrientation,
            )
            assertEquals(mihon.domain.reader.ReaderPageFactState.SPREAD, page.pageFact.value.state)
            assertEquals(
                mihon.domain.reader.ReaderImageOrientation.MIRROR_HORIZONTAL,
                page.pageFact.value.sourceOrientation,
            )
            ReflectionHelpers.callInstanceMethod<Unit>(holder, "onDetachedFromWindow")
        } finally {
            ordinary.destroy()
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `ordinary mirrored odd split binds raw source region and keeps every center pixel`() = runTest {
        Injekt.get<ReaderPreferences>().dualPageSplitPaged().set(true)
        val png = readerTestPng(61, 41)
        val tiff = "49492a0008000000010012010300010000000200000000000000".chunked(2).map {
            it.toInt(16).toByte()
        }.toByteArray()
        val type = "eXIf".toByteArray(Charsets.US_ASCII)
        val crc = java.util.zip.CRC32().apply {
            update(type)
            update(tiff)
        }.value.toInt()
        val chunk = java.nio.ByteBuffer.allocate(
            12 +
                tiff.size,
        ).putInt(tiff.size).put(type).put(tiff).putInt(crc).array()
        val encoded = png.copyOfRange(0, 33) + chunk + png.copyOfRange(33, png.size)
        val chapter = ReaderChapter(Chapter.create().copy(id = 204, mangaId = 1))
        val page = ReaderPage(0, stream = { encoded.inputStream() }).apply {
            this.chapter = chapter
            status = Page.State.Ready
        }
        chapter.publishLoadedPageListForTest(listOf(page))
        page.publishHeaderFact()
        assertEquals(
            mihon.domain.reader.ReaderImageOrientation.MIRROR_HORIZONTAL,
            page.pageFact.value.sourceOrientation,
        )
        val ordinary = R2LPagerViewer(viewer.activity).apply { config.navigationModeChangedListener = null }
        try {
            runCurrent()
            assertTrue(ordinary.config.dualPageSplit)
            ordinary.setChapters(ViewerChapters(chapter, null, null))
            val holder = PagerPageHolder(viewer.activity, ordinary, page)
            bindOrdinary(holder)
            val native =
                descendants(holder).filterIsInstance<NativeImageView>().single()
            assertEquals(Rect(0, 0, 31, 41), ReflectionHelpers.getField<Rect?>(native, "sRegion"))
            assertEquals(61, page.pageFact.value.width)
            ReflectionHelpers.callInstanceMethod<Unit>(holder, "onDetachedFromWindow")
        } finally {
            ordinary.destroy()
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `owned crop region files follow the actual holder replacement error and recycle lifecycle`() = runTest {
        val preferences = Injekt.get<ReaderPreferences>()
        preferences.dualPageSplitPaged().set(true)
        preferences.cropBorders().set(true)
        val raw = requireNotNull(javaClass.classLoader?.getResourceAsStream("reader/exif-corners-raw.jpg")).use {
            it.readBytes()
        }
        val exif = "45786966000049492a0008000000010012010300010000000200000000000000"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val encoded = raw.copyOfRange(0, 2) + byteArrayOf(-1, -31, 0, (exif.size + 2).toByte()) + exif +
            raw.copyOfRange(2, raw.size)
        val chapter = ReaderChapter(Chapter.create().copy(id = 205, mangaId = 1))
        val page = ReaderPage(0, stream = { encoded.inputStream() }).apply {
            this.chapter = chapter
            status = Page.State.Ready
        }
        chapter.publishLoadedPageListForTest(listOf(page))
        page.publishHeaderFact()
        val ordinary = R2LPagerViewer(viewer.activity).apply { config.navigationModeChangedListener = null }
        val files = mutableListOf<File>()
        val deletions = mutableListOf<Pair<String, String>>()
        var holder: PagerPageHolder? = null
        try {
            runCurrent()
            assertTrue(ordinary.config.dualPageSplit && ordinary.config.imageCropBorders)
            ordinary.setChapters(ViewerChapters(chapter, null, null))
            val current = PagerPageHolder(viewer.activity, ordinary, page) { _, bounds, directory ->
                assertEquals(mihon.domain.reader.PixelBounds(0, 0, 30, 40), bounds)
                val created = File.createTempFile("holder-half-", ".png", directory)
                created.writeBytes(readerTestPng(bounds.width, bounds.height))
                object : File(created.path) {
                    override fun delete(): Boolean {
                        val result = runCatching { java.nio.file.Files.delete(toPath()) }
                        deletions += name to result.fold(
                            onSuccess = { "released" },
                            onFailure = { "${it.javaClass.simpleName}: ${it.message}" },
                        )
                        return result.isSuccess
                    }
                }.also { files += it }
            }
            holder = current
            bindOrdinary(current)
            val original = ReflectionHelpers.getField<File>(current, "displayedRegionFile")
            assertTrue(original.isFile)
            val configuration = ReflectionHelpers.getField<ReaderPageImageView.Config>(current, "config")
            assertNull(configuration.sourceBounds)
            assertTrue(configuration.cropBorders)
            assertEquals(mihon.domain.reader.ReaderImageOrientation.MIRROR_HORIZONTAL, configuration.sourceOrientation)
            fun listener(): NativeImageListener {
                val native =
                    descendants(current).filterIsInstance<NativeImageView>().single()
                return NativeImageView::class.java.declaredFields
                    .single { it.type == NativeImageListener::class.java }
                    .apply {
                        isAccessible =
                            true
                    }.get(native) as NativeImageListener
            }
            val oldListener = listener()
            page.stream = { encoded.inputStream() }
            page.publishHeaderFact()
            assertEquals(
                mihon.domain.reader.ReaderImageOrientation.MIRROR_HORIZONTAL,
                page.pageFact.value.sourceOrientation,
            )
            val replacementDeadline = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < replacementDeadline) {
                runCurrent()
                val file = ReflectionHelpers.getField<File?>(current, "displayedRegionFile")
                if (file?.isFile == true && file != original) break
                withContext(Dispatchers.Default) { delay(10) }
            }
            val replacement = ReflectionHelpers.getField<File>(current, "displayedRegionFile")
            assertTrue(
                "The actual content collector must first bind replacement region pixels",
                replacement.isFile &&
                    replacement != original,
            )
            val released = !original.exists()
            assertTrue(
                "Replacing the presentation releases its original temporary encoded region; " +
                    "original=${original.name}; replacement=${replacement.name}; " +
                    "created=${files.map { it.name to it.exists() }}; firstDelete=$deletions",
                released,
            )
            assertTrue(deletions.contains(original.name to "released"))
            oldListener.onImageLoadError(java.io.IOException("old content callback"))
            assertTrue("An old child failure cannot delete the new presentation's file", replacement.isFile)
            listener().onImageLoadError(java.io.IOException("current region callback"))
            assertTrue("A current decode failure releases the owned file immediately", !replacement.exists())
            val pendingInitializations = mutableListOf<Runnable>()
            val native = descendants(current).filterIsInstance<NativeImageView>().single()
            native.setExecutor { pendingInitializations += it }
            bindOrdinary(current)
            val recycled = ReflectionHelpers.getField<File>(current, "displayedRegionFile")
            assertTrue(recycled.isFile)
            assertTrue("The original native initialization is still queued", pendingInitializations.isNotEmpty())
            val originalStream = ReflectionHelpers.getField<java.io.InputStream>(current, "regionInputStream")
            assertTrue(
                "The presentation owns its original handle before initialization",
                originalStream.available() > 0,
            )
            val recycledListener = listener()
            current.recycle()
            org.junit.Assert.assertThrows(java.io.IOException::class.java) { originalStream.read() }
            org.junit.Assert.assertThrows(java.io.IOException::class.java) { originalStream.available() }
            assertNull(ReflectionHelpers.getField<java.io.InputStream?>(current, "regionInputStream"))
            assertTrue("Explicit holder recycle must release its original temporary encoded region", !recycled.exists())
            assertTrue(!current.hasRenderedPage())
            recycledListener.onReady()
            assertTrue(
                "The original native listener cannot resurrect pixels after explicit holder recycle",
                !current.hasRenderedPage(),
            )
            assertNull(ReflectionHelpers.getField<ReaderErrorBinding?>(current, "errorLayout"))
            recycledListener.onImageLoadError(java.io.IOException("recycled old callback"))
            assertNull(
                "The original native error callback cannot recreate errors after holder recycle",
                ReflectionHelpers.getField<ReaderErrorBinding?>(current, "errorLayout"),
            )
        } finally {
            holder?.let { ReflectionHelpers.callInstanceMethod<Unit>(it, "onDetachedFromWindow") }
            ordinary.destroy()
            files.forEach { it.delete() }
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `recycled holder never takes ownership of a region still being encoded by its original work`() = runTest {
        val preferences = Injekt.get<ReaderPreferences>()
        preferences.dualPageSplitPaged().set(true)
        preferences.cropBorders().set(true)
        val raw = requireNotNull(javaClass.classLoader?.getResourceAsStream("reader/exif-corners-raw.jpg")).use {
            it.readBytes()
        }
        val exif = "45786966000049492a0008000000010012010300010000000200000000000000"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val encoded = raw.copyOfRange(0, 2) + byteArrayOf(-1, -31, 0, (exif.size + 2).toByte()) + exif +
            raw.copyOfRange(2, raw.size)
        val chapter = ReaderChapter(Chapter.create().copy(id = 206, mangaId = 1))
        val page = ReaderPage(0, stream = { encoded.inputStream() }).apply {
            this.chapter = chapter
            status = Page.State.Ready
        }
        chapter.publishLoadedPageListForTest(listOf(page))
        page.publishHeaderFact()
        val ordinary = R2LPagerViewer(viewer.activity).apply { config.navigationModeChangedListener = null }
        val entered = kotlinx.coroutines.CompletableDeferred<File>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var holder: PagerPageHolder? = null
        try {
            runCurrent()
            ordinary.setChapters(ViewerChapters(chapter, null, null))
            val current = PagerPageHolder(viewer.activity, ordinary, page) { _, bounds, directory ->
                val file = File.createTempFile(
                    "holder-pending-half-",
                    ".png",
                    directory,
                ).apply { writeBytes(readerTestPng(bounds.width, bounds.height)) }
                entered.complete(file)
                release.await()
                file
            }
            holder = current
            val work = async { bindOrdinary(current) }
            val deadline = System.nanoTime() + 2_000_000_000L
            while (!entered.isCompleted && System.nanoTime() < deadline) {
                runCurrent()
                withContext(Dispatchers.Default) { delay(10) }
            }
            assertTrue(
                "The actual original encoder must be in flight before the holder is recycled",
                entered.isCompleted,
            )
            val original = entered.await()
            assertTrue(original.isFile)
            current.recycle()
            release.complete(Unit)
            work.await()
            assertNull(ReflectionHelpers.getField<File?>(current, "displayedRegionFile"))
            assertTrue(
                "No presentation owner can receive work retired while its original encoder was in flight",
                !original.exists(),
            )
            assertTrue(!current.hasRenderedPage())
        } finally {
            release.complete(Unit)
            holder?.let { ReflectionHelpers.callInstanceMethod<Unit>(it, "onDetachedFromWindow") }
            ordinary.destroy()
            if (entered.isCompleted) entered.getCompleted().delete()
        }
    }

    private suspend fun bindOrdinary(holder: PagerPageHolder) {
        val method = PagerPageHolder::class.java.declaredMethods.single {
            it.name == "setImage" &&
                it.parameterCount == 1
        }.apply { isAccessible = true }
        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            val result = method.invoke(holder, continuation)
            if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else Unit
        }
    }

    @Test
    fun `global settings composition offers automatic but never recursive default`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var preferences: List<Preference> = emptyList()
        try {
            controller.get().setContent {
                preferences = SettingsReaderScreen.getPreferences()
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            val mode = preferences.first() as Preference.PreferenceItem.ListPreference<*>
            assertEquals(7, mode.preference.get())
            assertTrue(mode.entries.containsKey(7))
            assertTrue(!mode.entries.containsKey(0))
            assertTrue(mode.entries[7] != mode.entries[2])
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `new activity retains current and pairing while global changes update the real viewer`() = runTest {
        val preferences = Injekt.get<ReaderPreferences>()
        val chapter = loadedChapter(11, 9)
        withContext(Dispatchers.IO) {
            listOf(chapter).flatMap { it.pages.orEmpty() }.forEach { it.publishHeaderFact() }
        }
        chapter.chapter.last_page_read = 7
        val model = mockk<ReaderViewModel>(relaxed = true)
        val store = DualPagePairingStore()
        val state = MutableStateFlow(ReaderViewModel.State(viewerChapters = ViewerChapters(chapter, null, null)))
        var current: ReaderPage? = requireNotNull(chapter.pages)[2]
        var automaticDual: Boolean? = null
        val reports = mutableListOf<ReaderPage>()
        every { model.state } returns state
        every { model.dualPagePairings } returns store
        every { model.currentReaderPage } answers { current }
        every { model.automaticDualPage } answers { automaticDual }
        every { model.automaticDualPage = any() } answers { automaticDual = firstArg() }
        every { model.getMangaReadingMode(any()) } answers {
            if (firstArg<Boolean>()) preferences.defaultReadingMode().get() else ReadingMode.DEFAULT.flagValue
        }
        every { model.getMangaOrientation(any()) } returns 0
        every { model.onViewerLoaded(any()) } answers { state.value = state.value.copy(viewer = firstArg()) }
        fun select(page: ReaderPage) {
            current = page
            chapter.requestedPage = page.index
            state.value = state.value.copy(currentPage = page.number)
        }
        every { model.onLayoutPageSelected(any()) } answers { select(firstArg()) }
        every { model.onPageSelected(any(), any(), false) } answers { select(firstArg()) }
        every { model.onPageSelected(any()) } answers {
            reports += firstArg<ReaderPage>()
            select(firstArg())
        }
        val update = ReaderActivity::class.java.getDeclaredMethod("updateViewer").apply { isAccessible = true }
        fun activity(width: Int): ReaderActivity = Robolectric.buildActivity(ReaderActivity::class.java).get().also {
            it.setTheme(eu.kanade.tachiyomi.R.style.Theme_Tachiyomi)
            it.binding = ReaderActivityBinding.inflate(it.layoutInflater)
            ReflectionHelpers.setField(it, "viewModel\$delegate", lazyOf(model))
            measure(it.binding.viewerContainer, width, 1000)
            update.invoke(it)
        }
        val first = activity(1400)
        runCurrent()
        val oldViewer = state.value.viewer as DualPageR2LPagerViewer
        assertEquals("The original logical second page must survive the first paired viewport", 2, current?.index)
        val priorUnit = requireNotNull(oldViewer.adapter.session(chapter)?.targetUnit)
        assertEquals(listOf(2, 3), priorUnit.pageIds.map { it.sourcePageIndex })
        oldViewer.adjustPagePairing()
        val adjustedTarget = priorUnit.pageIds.maxOf { it.sourcePageIndex }
        assertEquals(adjustedTarget, current?.index)
        val second = activity(1300)
        runCurrent()
        assertTrue(state.value.viewer is DualPageR2LPagerViewer)
        assertEquals(adjustedTarget, current?.index)
        val restored = state.value.viewer as DualPageR2LPagerViewer
        val pair = restored.adapter.items[restored.pager.currentItem] as DisplayPage.Double
        assertEquals(adjustedTarget, pair.rightPage.index)
        first.onViewerPageSelected(oldViewer, requireNotNull(chapter.pages)[8])
        assertEquals(adjustedTarget, current?.index)
        preferences.defaultReadingMode().set(ReadingMode.RIGHT_TO_LEFT.flagValue)
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertTrue(state.value.viewer is R2LPagerViewer)
        assertEquals(adjustedTarget, current?.index)
        assertTrue(reports.isEmpty())
        assertEquals(7, chapter.chapter.last_page_read)
        preferences.defaultReadingMode().set(ReadingMode.AUTO.flagValue)
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        measure(second.binding.viewerContainer, 1500, 1000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(151))
        assertTrue(state.value.viewer is DualPageR2LPagerViewer)
        state.value.viewer?.destroy()
    }

    @Test
    fun `loaded transition becomes real adjacent page and still reports navigation`() = runTest {
        val current = loadedChapter(11, 3)
        val next = ReaderChapter(Chapter.create().copy(id = 12, mangaId = 1))
        val chapters = ViewerChapters(current, null, next)
        viewer.setChapters(chapters)
        val transition = viewer.adapter.items.indexOfFirst { it is ChapterTransition.Next }
        viewer.pager.setCurrentItem(transition, false)
        val pages = List(3) { ReaderPage(it).apply { chapter = next } }
        next.publishLoadedPageListForTest(pages)
        val activity = viewer.activity as RecordingReaderActivity
        activity.selectedAnchors.clear()
        viewer.setChapters(chapters)
        assertTrue(activity.selectedAnchors.any { it === pages[0] })
        assertTrue("An unpainted adjacent page is only a navigation anchor", activity.selectedPages.isEmpty())

        // An old selected transition can finish loading just as its entire chapter window is
        // replaced. Its entry must never override the new owner's original logical position.
        val staleNext = ReaderChapter(Chapter.create().copy(id = 13, mangaId = 1))
        viewer.setChapters(ViewerChapters(current, null, staleNext))
        val staleTransition = viewer.adapter.items.indexOfFirst { it is ChapterTransition.Next }
        viewer.pager.setCurrentItem(staleTransition, false)
        staleNext.publishLoadedPageListForTest(List(3) { ReaderPage(it).apply { chapter = staleNext } })
        val replacement = loadedChapter(21, 3).apply { requestedPage = 2 }
        activity.selectedAnchors.clear()
        viewer.setChapters(ViewerChapters(replacement, null, null))
        val selected = viewer.adapter.items[viewer.pager.currentItem]
        assertTrue(
            "Old transition must not displace the replacement owner: $selected",
            selected is DisplayPage && selected.containsPage(requireNotNull(replacement.pages)[2]),
        )
        assertTrue(activity.selectedAnchors.any { it === requireNotNull(replacement.pages)[2] })
    }

    @Test
    fun `portrait cover keeps a left half slot while unique current remains the cover`() = runTest {
        val chapter = loadedChapter(11, 3)
        val page = requireNotNull(chapter.pages)[0]
        val holder =
            DualPagerPageHolder(viewer.activity, viewer, DisplayPage.Single(page, ReaderPortraitSingleSlot.LEFT))
        load(holder, page, "CENTER")
        measure(holder, 1400, 1000)
        val image = descendants(holder).filterIsInstance<ReaderPageImageView>().single()
        assertEquals(700, (image.parent as View).width)
        assertEquals(0, (image.parent as View).left)
        assertEquals(1400, image.width)
        assertEquals(500f, requireNotNull(image.viewportFitBounds).width())
        assertEquals(700f, requireNotNull(image.viewportFitBounds).right)
        assertEquals(page, holder.displayPage.firstPage)
        detach(holder)
    }

    @Test
    fun `four portrait pages mount first on left and trailing single on right`() = runTest {
        val chapter = loadedChapter(11, 4)
        viewer.adapter.setChapters(ViewerChapters(chapter, null, null), false)
        for (page in requireNotNull(chapter.pages)) decodeForAdapter(page)
        viewer.adapter.adjustPairing()
        val units = viewer.adapter.items.filterIsInstance<DisplayPage>().sortedBy { it.firstPage.index }
        assertEquals(listOf(0, 1, 3), units.map { it.firstPage.index })
        assertTrue(units[1] is DisplayPage.Double)

        for ((unit, expectedLeft) in listOf(units.first() to 0, units.last() to 700)) {
            val single = unit as DisplayPage.Single
            val holder = DualPagerPageHolder(viewer.activity, viewer, single)
            load(holder, single.page, "CENTER")
            measure(holder, 1400, 1000)
            val image = descendants(holder).filterIsInstance<ReaderPageImageView>().single()
            val slot = image.parent as View
            assertEquals(700, slot.width)
            assertEquals(expectedLeft, slot.left)
            detach(holder)
        }
    }

    @Test
    fun `one portrait page keeps a centered full viewport`() = runTest {
        val chapter = loadedChapter(11, 1)
        viewer.adapter.setChapters(ViewerChapters(chapter, null, null), false)
        decodeForAdapter(requireNotNull(chapter.pages).single())
        viewer.adapter.adjustPairing()
        val single = viewer.adapter.items.filterIsInstance<DisplayPage>().single() as DisplayPage.Single
        val holder = DualPagerPageHolder(viewer.activity, viewer, single)
        load(holder, single.page, "CENTER")
        measure(holder, 1400, 1000)
        val image = descendants(holder).filterIsInstance<ReaderPageImageView>().single()
        assertEquals(1400, image.parent.let { it as View }.width)
        detach(holder)
    }

    @Test
    fun `decoded wide trailing page keeps its existing full viewport`() = runTest {
        val chapter = loadedChapter(11, 4)
        val wideBytes = ByteArrayOutputStream().also {
            Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val lastPage = requireNotNull(chapter.pages)[3]
        lastPage.stream = { wideBytes.inputStream() }
        viewer.adapter.setChapters(ViewerChapters(chapter, null, null), false)
        for (page in requireNotNull(chapter.pages)) decodeForAdapter(page)
        val single = viewer.adapter.items.filterIsInstance<DisplayPage.Single>().single { it.page === lastPage }
        assertEquals(ReaderPortraitSingleSlot.FULL, single.slot)
        val holder = DualPagerPageHolder(viewer.activity, viewer, single)
        load(holder, single.page, "CENTER")
        measure(holder, 1400, 1000)
        val image = descendants(holder).filterIsInstance<ReaderPageImageView>().single()
        assertEquals(1400, (image.parent as View).width)
        detach(holder)
    }

    @Test
    fun `dual cover is unique and repeated pairing adjustment keeps a right current page`() = runTest {
        val chapter = loadedChapter(11, 9)
        viewer.setChapters(ViewerChapters(chapter, null, null))
        val pages = requireNotNull(chapter.pages)
        val cover = viewer.adapter.items.filterIsInstance<DisplayPage>().first { it.containsPage(pages[0]) }
        assertTrue("Cover must not pair with page one", cover is DisplayPage.Single)
    }

    @Test
    fun `pairing adjustment keeps paired right page and does not report layout progress`() = runTest {
        val chapter = loadedChapter(11, 9)
        withContext(Dispatchers.IO) {
            listOf(chapter).flatMap { it.pages.orEmpty() }.forEach { it.publishHeaderFact() }
        }
        viewer.setChapters(ViewerChapters(chapter, null, null))
        viewer.moveToPage(requireNotNull(chapter.pages)[1])
        val activity = viewer.activity as RecordingReaderActivity
        activity.selectedPages.clear()
        repeat(6) { iteration ->
            viewer.adjustPagePairing()
            val pair = viewer.adapter.items[viewer.pager.currentItem] as DisplayPage
            assertTrue("Adjusting a normal pair must not leave a lone page", pair is DisplayPage.Double)
            assertEquals(if (iteration % 2 == 0) 2 else 1, pair.firstPage.index)
            assertTrue("Layout changes must not mark pages read", activity.selectedPages.isEmpty())
        }
    }

    @Test
    fun `global default is adaptive and real reader container resize changes viewer`() = runTest {
        val preferences = ReaderPreferences(InMemoryPreferenceStore())
        assertEquals(ReadingMode.AUTO.flagValue, preferences.defaultReadingMode().get())
        val activity = viewer.activity
        activity.setTheme(eu.kanade.tachiyomi.R.style.Theme_Tachiyomi)
        activity.binding = ReaderActivityBinding.inflate(activity.layoutInflater)
        val model = mockk<ReaderViewModel>(relaxed = true)
        every { model.dualPagePairings } returns DualPagePairingStore()
        val state = MutableStateFlow(ReaderViewModel.State())
        every { model.state } returns state
        var selectedMode = ReadingMode.DEFAULT.flagValue
        every { model.getMangaReadingMode(any()) } answers {
            if (firstArg<Boolean>() && selectedMode == 0) ReadingMode.AUTO.flagValue else selectedMode
        }
        every { model.getMangaOrientation(any()) } returns 0
        every { model.onViewerLoaded(any()) } answers { state.value = state.value.copy(viewer = firstArg()) }
        ReflectionHelpers.setField(activity, "viewModel\$delegate", lazyOf(model))
        val update = ReaderActivity::class.java.getDeclaredMethod("updateViewer").apply { isAccessible = true }
        val container = activity.binding.viewerContainer
        measure(container, 900, 1000)
        update.invoke(activity)
        assertTrue(state.value.viewer is R2LPagerViewer)
        (state.value.viewer as R2LPagerViewer).config.navigationModeChangedListener?.invoke()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        activity.binding.navigationOverlay.visibility = android.view.View.GONE
        measure(container, 1400, 1000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(149))
        assertTrue(state.value.viewer is R2LPagerViewer)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2))
        assertTrue(state.value.viewer is DualPageR2LPagerViewer)
        (state.value.viewer as DualPageR2LPagerViewer).config.navigationModeChangedListener?.invoke()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(android.view.View.GONE, activity.binding.navigationOverlay.visibility)
        measure(container, 1200, 1000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(151))
        assertTrue(state.value.viewer is R2LPagerViewer)
        ShadowToast.reset()
        selectedMode = ReadingMode.AUTO.flagValue
        update.invoke(activity)
        assertTrue(
            "Choosing explicit AUTO from inherited AUTO is a manual change",
            ShadowToast.getTextOfLatestToast() != null,
        )
        (state.value.viewer as R2LPagerViewer).config.navigationModeChangedListener?.invoke()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(View.VISIBLE, activity.binding.navigationOverlay.visibility)
        ShadowToast.reset()
        measure(container, 1400, 1000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(151))
        (state.value.viewer as DualPageR2LPagerViewer).config.navigationModeChangedListener?.invoke()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertNull(ShadowToast.getTextOfLatestToast())
        assertEquals(View.GONE, activity.binding.navigationOverlay.visibility)
        measure(container, 900, 1000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(151))
        for (manual in listOf(ReadingMode.RIGHT_TO_LEFT, ReadingMode.AUTO)) {
            ShadowToast.reset()
            selectedMode = manual.flagValue
            update.invoke(activity)
            assertTrue(state.value.viewer is R2LPagerViewer)
            assertTrue(ShadowToast.getTextOfLatestToast() != null)
            activity.binding.navigationOverlay.visibility = View.GONE
            (state.value.viewer as R2LPagerViewer).config.navigationModeChangedListener?.invoke()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            assertEquals(View.VISIBLE, activity.binding.navigationOverlay.visibility)
        }
        state.value.viewer?.destroy()
    }

    @Test
    fun `mounted pagers restore loaded next to first page and previous to last page`() = runTest {
        for (rtl in listOf(true, false)) {
            for (previous in listOf(true, false)) {
                val candidate: PagerViewer = if (rtl) {
                    R2LPagerViewer(viewer.activity)
                } else {
                    L2RPagerViewer(viewer.activity)
                }
                candidate.config.navigationModeChangedListener = null
                val current = loadedChapter(11)
                val adjacent = ReaderChapter(Chapter.create().copy(id = 12, mangaId = 1))
                val chapters = if (previous) {
                    ViewerChapters(current, adjacent, null)
                } else {
                    ViewerChapters(current, null, adjacent)
                }
                try {
                    candidate.setChapters(chapters)
                    Robolectric.buildActivity(ComponentActivity::class.java)
                        .setup().visible().get().setContentView(candidate.pager)
                    measure(candidate.pager, 1440, 3120)
                    val adapter = candidate.pager.adapter as PagerViewerAdapter
                    val loadingIndex = adapter.items.indexOfFirst { it is ChapterTransition && it.to === adjacent }
                    candidate.pager.setCurrentItem(loadingIndex, false)
                    val pages = List(3) { ReaderPage(it).apply { chapter = adjacent } }
                    adjacent.publishLoadedPageListForTest(pages)
                    candidate.setChapters(chapters)
                    measure(candidate.pager, 1440, 3120)
                    assertEquals(
                        "rtl=$rtl previous=$previous must preserve logical boundary entry",
                        if (previous) pages.last() else pages.first(),
                        adapter.items[candidate.pager.currentItem],
                    )
                } finally {
                    candidate.destroy()
                }
            }
        }
    }

    @Test
    fun `mounted webtoon restores loaded boundary to the adjacent entry page`() = runTest {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().get()
        for (previous in listOf(true, false)) {
            val candidate = WebtoonViewer(viewer.activity)
            candidate.config.navigationModeChangedListener = null
            val current = loadedChapter(11)
            val adjacent = ReaderChapter(Chapter.create().copy(id = 12, mangaId = 1))
            val chapters = if (previous) {
                ViewerChapters(current, adjacent, null)
            } else {
                ViewerChapters(current, null, adjacent)
            }
            try {
                candidate.setChapters(chapters)
                host.setContentView(candidate.getView())
                measure(candidate.recycler, 1440, 3120)
                val adapter = candidate.recycler.adapter as WebtoonAdapter
                val loadingIndex = adapter.items.indexOfFirst { it is ChapterTransition && it.to === adjacent }
                val manager = candidate.recycler.layoutManager as LinearLayoutManager
                manager.scrollToPositionWithOffset(loadingIndex, 0)
                measure(candidate.recycler, 1440, 3120)
                candidate.onScrolled(loadingIndex)
                val pages = List(8) { ReaderPage(it).apply { chapter = adjacent } }
                adjacent.publishLoadedPageListForTest(pages)
                candidate.setChapters(chapters)
                measure(candidate.recycler, 1440, 3120)
                assertEquals(
                    "previous=$previous must restore boundary entry at top",
                    if (previous) pages.last() else pages.first(),
                    adapter.items[manager.findFirstVisibleItemPosition()],
                )
            } finally {
                candidate.destroy()
            }
        }
    }

    @Test
    fun `mounted dual dimension regroup keeps selected source without transient wrong progress`() = runTest {
        val chapter = loadedChapter(11, 6)
        viewer.setChapters(ViewerChapters(chapter, loadedChapter(10), loadedChapter(12)))
        Robolectric.buildActivity(ComponentActivity::class.java)
            .setup().visible().get().setContentView(viewer.pager)
        measure(viewer.pager, 1440, 3120)
        val selected = requireNotNull(chapter.pages)[4]
        viewer.moveToPage(selected)
        measure(viewer.pager, 1440, 3120)
        val activity = viewer.activity as RecordingReaderActivity
        activity.selectedPages.clear()
        viewer.adapter.updatePageDimensions(requireNotNull(chapter.pages)[0], 400, 200)
        measure(viewer.pager, 1440, 3120)
        val actual = viewer.adapter.items[viewer.pager.currentItem] as DisplayPage
        assertTrue(actual.containsPage(selected))
        assertTrue("Regroup must not publish an unrelated page", activity.selectedPages.all { actual.containsPage(it) })
    }

    @Test
    fun `next page input visits unpainted source pages and crosses chapters without consuming candidates`() = runTest {
        val chapter = loadedChapter(11, 4)
        val next = loadedChapter(12)
        withContext(Dispatchers.IO) {
            listOf(chapter, next).flatMap { it.pages.orEmpty() }.forEach { it.publishHeaderFact() }
        }
        viewer.adapter.setChapters(ViewerChapters(chapter, loadedChapter(10), next), false)
        viewer.moveToPage(requireNotNull(chapter.pages)[0])
        for ((source, expected) in listOf(1 to listOf(1, 2), 3 to listOf(3))) {
            viewer.handleKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PAGE_DOWN))
            val owner = requireNotNull(viewer.adapter.session(chapter))
            assertEquals(source, owner.location?.pageId?.sourcePageIndex)
            val displayed = viewer.adapter.items[viewer.pager.currentItem] as DisplayPage
            assertEquals(expected, displayed.visiblePages.map { it.index })
            assertEquals(expected, owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
            io.mockk.verify(exactly = 0) { viewer.activity.viewModel.onDualViewportSettled(any(), any()) }
        }
        viewer.handleKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(next, (viewer.adapter.items[viewer.pager.currentItem] as DisplayPage).firstPage.chapter)
        assertEquals(0, viewer.adapter.session(next)?.location?.pageId?.sourcePageIndex)
        viewer.handleKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PAGE_UP))
        val returned = viewer.adapter.items[viewer.pager.currentItem] as DisplayPage
        assertTrue(returned.containsPage(requireNotNull(chapter.pages)[3]))
        assertEquals(3, viewer.adapter.session(chapter)?.location?.pageId?.sourcePageIndex)
    }

    @Test
    fun `invalidated page list cannot leave old paired pages in the visible window`() = runTest {
        val chapter = loadedChapter(11, 4)
        val chapters = ViewerChapters(chapter, null, null)
        viewer.adapter.setChapters(chapters, false)
        chapter.openPageListForTest()
        viewer.adapter.setChapters(chapters, false)
        assertEquals(0, viewer.adapter.items.filterIsInstance<DisplayPage>().size)
    }

    @Test
    fun `unknown image sizes retain provisional slots and every source until actual facts arrive`() = runTest {
        val chapter = loadedChapter(11, 4)
        viewer.adapter.setChapters(ViewerChapters(chapter, null, null), false)
        val display = viewer.adapter.items.filterIsInstance<DisplayPage>()
        assertEquals(1, display.filterIsInstance<DisplayPage.Double>().size)
        assertEquals(listOf(0, 1, 2, 3), display.flatMap { it.visiblePages }.map { it.index }.sorted())
        assertTrue(display.filterIsInstance<DisplayPage.Single>().none { it.slot == ReaderPortraitSingleSlot.FULL })
        val owner = requireNotNull(viewer.adapter.session(chapter))
        assertTrue(owner.units.all { !it.certain })
        assertTrue(
            owner.identity.pageIds.all {
                (owner.pageFact(it)?.state ?: mihon.domain.reader.ReaderPageFactState.UNKNOWN) ==
                    mihon.domain.reader.ReaderPageFactState.UNKNOWN
            },
        )
    }

    @Test
    fun `entering the production reader does not show an automatic reading mode toast`() = runTest {
        val activity = viewer.activity
        activity.setTheme(eu.kanade.tachiyomi.R.style.Theme_Tachiyomi)
        activity.binding = ReaderActivityBinding.inflate(activity.layoutInflater)
        val model = mockk<ReaderViewModel>(relaxed = true)
        every { model.dualPagePairings } returns DualPagePairingStore()
        every { model.state } returns MutableStateFlow(ReaderViewModel.State())
        every { model.getMangaReadingMode(any()) } returns ReadingMode.DUAL_PAGE_R2L.flagValue
        every { model.getMangaOrientation(any()) } returns 0
        ReflectionHelpers.setField(activity, "viewModel\$delegate", lazyOf(model))
        ShadowToast.reset()
        val update = ReaderActivity::class.java.getDeclaredMethod("updateViewer").apply { isAccessible = true }
        update.invoke(activity)
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `same mode rebuild and restored viewer are silent while explicit mode changes give feedback`() = runTest {
        val activity = viewer.activity
        activity.setTheme(eu.kanade.tachiyomi.R.style.Theme_Tachiyomi)
        activity.binding = ReaderActivityBinding.inflate(activity.layoutInflater)
        val model = mockk<ReaderViewModel>(relaxed = true)
        every { model.dualPagePairings } returns DualPagePairingStore()
        val state = MutableStateFlow(ReaderViewModel.State(viewer = viewer))
        every { model.state } returns state
        var mode = ReadingMode.DUAL_PAGE_R2L.flagValue
        every { model.getMangaReadingMode(any()) } answers { mode }
        every { model.getMangaOrientation(any()) } returns 0
        every { model.onViewerLoaded(any()) } answers { state.value = state.value.copy(viewer = firstArg()) }
        ReflectionHelpers.setField(activity, "viewModel\$delegate", lazyOf(model))
        val update = ReaderActivity::class.java.getDeclaredMethod("updateViewer").apply { isAccessible = true }
        try {
            ShadowToast.reset()
            update.invoke(activity)
            assertNull("Activity restored with a viewer must remain silent", ShadowToast.getTextOfLatestToast())
            update.invoke(activity)
            assertNull("Same-mode metadata refresh must remain silent", ShadowToast.getTextOfLatestToast())
            mode = ReadingMode.WEBTOON.flagValue
            update.invoke(activity)
            assertTrue(ShadowToast.getTextOfLatestToast() != null)
            ShadowToast.reset()
            mode = ReadingMode.CONTINUOUS_VERTICAL.flagValue
            update.invoke(activity)
            assertTrue(
                "Different modes of the same viewer class must give feedback",
                ShadowToast.getTextOfLatestToast() != null,
            )
        } finally {
            state.value.viewer?.destroy()
        }
    }

    @Test
    fun `whole chapter window follows R2L order before and after adjacent dimensions arrive`() = runTest {
        val previous = loadedChapter(10, 3)
        val current = loadedChapter(11, 3)
        val next = loadedChapter(12, 3)
        val chapters = ViewerChapters(current, previous, next)
        viewer.adapter.setChapters(chapters, false)
        fun chapterIds() = viewer.adapter.items.filterIsInstance<DisplayPage>().map { it.firstPage.chapter.chapter.id }
        assertEquals(listOf(12L, 12L, 11L, 11L, 10L, 10L), chapterIds())
        val wideBytes = ByteArrayOutputStream().also {
            Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        requireNotNull(next.pages)[1].stream = { wideBytes.inputStream() }
        for (page in requireNotNull(next.pages)) decodeForAdapter(page)
        assertEquals(listOf(12L, 12L, 12L, 11L, 11L, 10L, 10L), chapterIds())
        assertEquals(
            1,
            viewer.adapter.items.filterIsInstance<DisplayPage.Double>().count {
                it.firstPage.chapter ==
                    current
            },
        )
        for (page in requireNotNull(current.pages)) decodeForAdapter(page)
        assertEquals(listOf(12L, 12L, 12L, 11L, 11L, 10L, 10L), chapterIds())
        assertEquals(
            1,
            viewer.adapter.items.filterIsInstance<DisplayPage.Double>().count {
                it.firstPage.chapter ==
                    current
            },
        )
    }

    @Test
    fun `chapter window refresh preserves decoded pairings and manual pairing offset`() = runTest {
        val chapter = loadedChapter(11, 4)
        val chapters = ViewerChapters(chapter, null, null)
        viewer.adapter.setChapters(chapters, false)
        for (page in requireNotNull(chapter.pages)) decodeForAdapter(page)
        viewer.adapter.adjustPairing()
        val before = viewer.adapter.items.filterIsInstance<DisplayPage>()
        viewer.adapter.setChapters(chapters, false)
        assertEquals(before, viewer.adapter.items.filterIsInstance<DisplayPage>())
    }

    @Test
    fun `restoring either member of a pair selects its display unit not a chapter boundary`() = runTest {
        val chapter = loadedChapter(11, 3)
        viewer.adapter.setChapters(ViewerChapters(chapter, null, null), false)
        for (page in requireNotNull(chapter.pages)) decodeForAdapter(page)
        viewer.pager.clearOnPageChangeListeners()
        viewer.moveToPage(requireNotNull(chapter.pages)[1])
        assertTrue(viewer.adapter.items[viewer.pager.currentItem] is DisplayPage.Double)
    }

    private fun loadedChapter(id: Long, count: Int = 2): ReaderChapter {
        val chapter = ReaderChapter(Chapter.create().copy(id = id, mangaId = 1, chapterNumber = id.toDouble()))
        val bytes = ByteArrayOutputStream().also {
            Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        chapter.publishLoadedPageListForTest(
            List(count) { index ->
                ReaderPage(index, stream = { bytes.inputStream() }).apply { this.chapter = chapter }
            },
        )
        return chapter
    }

    @Test
    fun `leaving a zoomed group resets its camera before returning through the real pager`() = runTest {
        val chapter = loadedChapter(61014, 8)
        val pages = requireNotNull(chapter.pages)
        viewer.setChapters(ViewerChapters(chapter, null, null))
        pages.forEach { viewer.adapter.updatePageDimensions(it, 100, 200) }
        viewer.moveToPage(pages[1])
        measure(viewer.pager, 1440, 3120)
        val original = viewer.pager.children.filterIsInstance<DualPagerPageHolder>()
            .first { it.displayPage.containsPage(pages[1]) }
        assertTrue(original.displayPage is DisplayPage.Double)
        val display = original.displayPage as DisplayPage.Double
        load(original, display.rightPage, "RIGHT")
        load(original, display.leftPage, "LEFT")
        measure(viewer.pager, 1440, 3120)
        val image = descendants(original).filterIsInstance<ReaderPageImageView>().first()
        doubleTap(viewer.pager, SystemClock.uptimeMillis(), x = 400f)
        advanceGestureAnimation()
        assertEquals(2f, ReflectionHelpers.getField<Float>(original, "currentScale"), 0.001f)
        assertEquals(2f, image.viewportScale, 0.001f)
        assertTrue(ReflectionHelpers.getField<Float>(original, "translateX") != 0f)

        viewer.moveToPage(pages[2])
        measure(viewer.pager, 1440, 3120)
        assertEquals("Positioning inside the same group must retain zoom", 2f, image.viewportScale, 0.001f)

        viewer.moveToPage(pages[3])
        assertEquals(
            "Selecting the next group must not reset the still-visible outgoing camera",
            2f,
            ReflectionHelpers.getField<Float>(original, "currentScale"),
            0.001f,
        )
        assertEquals(
            "The outgoing group must retain zoom while it is still visible in the page transition",
            2f,
            image.viewportScale,
            0.001f,
        )
        viewer.moveToPage(pages[1])
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            viewer.pager.computeScroll()
        }
        assertEquals(
            "Returning before the outgoing animation completes must cancel its pending reset",
            2f,
            ReflectionHelpers.getField<Float>(original, "currentScale"),
            0.001f,
        )
        viewer.moveToPage(pages[3])
        measure(viewer.pager, 1440, 3120)
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            viewer.pager.computeScroll()
        }
        assertEquals(
            "The non-current retained group must forget zoom",
            1f,
            ReflectionHelpers.getField<Float>(original, "currentScale"),
            0.001f,
        )
        viewer.moveToPage(pages[1])
        measure(viewer.pager, 1440, 3120)
        val restored = viewer.pager.children.filterIsInstance<DualPagerPageHolder>()
            .first { it.displayPage.containsPage(pages[1]) }
        assertTrue("The regression must cover a cached holder, not a newly created one", restored === original)
        assertEquals(1f, ReflectionHelpers.getField<Float>(restored, "currentScale"), 0.001f)
        assertEquals(0f, ReflectionHelpers.getField<Float>(restored, "translateX"), 0.001f)
        assertEquals(0f, ReflectionHelpers.getField<Float>(restored, "translateY"), 0.001f)
        assertEquals("Reset must reach the real image viewport", 1f, image.viewportScale, 0.001f)
        assertEquals(0f, image.viewportOffsetX, 0.001f)
        assertEquals(0f, image.viewportOffsetY, 0.001f)
    }

    private suspend fun decodeForAdapter(page: ReaderPage) {
        val holder = DualPagerPageHolder(viewer.activity, viewer, DisplayPage.Single(page))
        load(holder, page, "CENTER")
    }

    @Before
    fun setUp() {
        loggerWasInstalled = LogcatLogger.isInstalled
        if (!loggerWasInstalled) LogcatLogger.install()
        LogcatLogger.loggers += diagnosticLogger
        previousFramePause = ShadowChoreographer.isPaused()
        previousFrameDelay = ShadowChoreographer.getFrameDelay()
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        previousInjekt = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val application = RuntimeEnvironment.getApplication()
        val preferences = AndroidPreferenceStore(
            application,
            application.getSharedPreferences("reader-layout-${System.nanoTime()}", 0),
        )
        Injekt.addSingleton(ReaderPreferences(preferences))
        Injekt.addSingleton(UiPreferences(preferences))
        Injekt.addSingleton(mockk<DownloadManager>(relaxed = true))
        Injekt.addSingleton(BasePreferences(RuntimeEnvironment.getApplication() as Application, preferences))
        // Native format sniffing is unavailable in a Windows JVM; dimensions still use
        // the real BitmapFactory and the production ReaderPageImageView is mounted.
        mockkObject(ImageUtil)
        every { ImageUtil.isAnimatedAndSupported(any()) } returns false
        Dispatchers.setMain(StandardTestDispatcher())
        val activity = Robolectric.buildActivity(RecordingReaderActivity::class.java).get()
        activity.binding = mockk<ReaderActivityBinding>(relaxed = true)
        val model = mockk<ReaderViewModel>(relaxed = true)
        every { model.dualPagePairings } returns DualPagePairingStore()
        every { model.state } returns MutableStateFlow(ReaderViewModel.State())
        every { model.onPageSelected(any(), any(), false) } answers
            { activity.selectedAnchors += firstArg<ReaderPage>() }
        every { model.onLayoutPageSelected(any()) } answers { activity.selectedAnchors += firstArg<ReaderPage>() }
        ReflectionHelpers.setField(activity, "viewModel\$delegate", lazyOf(model))
        viewer = DualPageR2LPagerViewer(activity)
        // This fixture mounts the reader viewport, not the activity's navigation help overlay.
        viewer.config.navigationModeChangedListener = null
    }

    @After
    fun tearDown() {
        LogcatLogger.loggers -= diagnosticLogger
        if (!loggerWasInstalled) LogcatLogger.uninstall()
        if (::viewer.isInitialized) viewer.destroy()
        hosts.forEach { it.finish() }
        hosts.clear()
        ShadowChoreographer.setFrameDelay(previousFrameDelay)
        ShadowChoreographer.setPaused(previousFramePause)
        Dispatchers.resetMain()
        Injekt = previousInjekt
        unmockkObject(ImageUtil)
    }

    @Test
    fun `images ready before first measurement occupy equal centered halves`() = runTest {
        val (holder, right, left) = pair()
        load(holder, right, "RIGHT")
        load(holder, left, "LEFT")
        measure(holder, 1440, 3120)
        assertPair(holder, 1440, 3120, 720, 1024)
    }

    @Test
    fun `either late image order preserves centered size and resize recomputes fit`() = runTest {
        for (rightFirst in listOf(true, false)) {
            val (holder, right, left) = pair()
            measure(holder, 1440, 3120)
            load(holder, if (rightFirst) right else left, if (rightFirst) "RIGHT" else "LEFT")
            measure(holder, 1440, 3120)
            load(holder, if (rightFirst) left else right, if (rightFirst) "LEFT" else "RIGHT")
            measure(holder, 1440, 3120)
            assertPair(holder, 1440, 3120, 720, 1024)
            measure(holder, 3120, 1440)
            assertPair(holder, 3120, 1440, 1012, 1440)
        }
    }

    @Test
    fun `shared Android Desktop Fit vectors preserve physical slot bounds and image aspect`() = runTest {
        val vectors = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "domain/src/commonTest/resources/reader/dual-page-fit.csv") }
            .first { it.isFile }
            .readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split(',').map(String::toInt) }
        for (v in vectors) {
            val (holder, right, left) = pair(v[2], v[3], v[4], v[5])
            try {
                load(holder, right, "RIGHT")
                load(holder, left, "LEFT")
                measure(holder, v[0], v[1])
                val images = descendants(holder).filterIsInstance<ReaderPageImageView>().toList()
                assertEquals(2, images.size)
                images.forEachIndexed { index, image ->
                    val offset = 6 + index * 4
                    val slot = image.parent as View
                    assertEquals(v[0] / 2, slot.width)
                    assertEquals(v[1], slot.height)
                    val fit = requireNotNull(image.viewportFitBounds)
                    assertEquals(v[0], image.width)
                    assertEquals(v[1], image.height)
                    assertEquals(v[offset], fit.left.toInt())
                    assertEquals(v[offset + 1], fit.top.toInt())
                    assertEquals(v[offset + 2], fit.width().toInt())
                    assertEquals(v[offset + 3], fit.height().toInt())
                }
            } finally {
                descendants(holder).filterIsInstance<ReaderPageImageView>().forEach { it.recycle() }
                detach(holder)
            }
        }
    }

    @Test
    fun `Ready status from the real holder collector mounts both images without direct binding`() = runTest {
        val (unused, right, left) = pair()
        val chapter = right.chapter
        right.status = Page.State.Ready
        left.status = Page.State.Ready
        chapter.pageLoader = object : PageLoader() {
            override var isLocal = true
            override suspend fun getPages() = listOf(right, left)
        }
        val holder = DualPagerPageHolder(viewer.activity, viewer, DisplayPage.Double(right, left))
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                while (descendants(holder).filterIsInstance<ReaderPageImageView>().count() != 2) {
                    delay(10)
                }
            }
        }
        measure(holder, 1440, 3120)
        assertPair(holder, 1440, 3120, 720, 1024)
        detach(holder)
        detach(unused)
    }

    @Test
    fun `double tap zoom and reset share native cameras without transforming feedback`() = runTest {
        val (holder, right, left) = pair()
        load(holder, right, "RIGHT")
        load(holder, left, "LEFT")
        mountInPager(holder)
        var pagerTaps = 0
        viewer.pager.tapListener = { pagerTaps++ }
        val container = holder.getChildAt(0)
        val image = descendants(holder).filterIsInstance<ReaderPageImageView>().first()
        doubleTap(viewer.pager, SystemClock.uptimeMillis(), x = 720f)
        advanceGestureAnimation()
        assertEquals(2f, image.viewportScale, 0.001f)
        assertEquals(1f, container.scaleX, 0.001f)
        measure(holder, 3120, 1440)
        assertEquals("Resize must update the original native viewport immediately", 3120, image.width)
        assertEquals(1440, image.height)
        measure(holder, 1440, 3120)
        descendants(holder).filterIsInstance<ReaderPageImageView>().forEach {
            assertEquals(1f, it.scaleX, 0.001f)
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(0, pagerTaps)
        measure(viewer.pager, 1440, 3120)
        measure(holder, 1440, 3120)
        val panStart = SystemClock.uptimeMillis()
        val drag = listOf(
            Triple(MotionEvent.ACTION_DOWN, 400f, panStart),
            Triple(MotionEvent.ACTION_MOVE, 700f, panStart + 30),
            Triple(MotionEvent.ACTION_MOVE, 900f, panStart + 60),
            Triple(MotionEvent.ACTION_UP, 900f, panStart + 80),
        )
        for ((action, x, time) in drag) {
            MotionEvent.obtain(panStart, time, action, x, 1400f, 0).also {
                viewer.pager.dispatchTouchEvent(it)
                it.recycle()
            }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        assertTrue("Zoomed drag pans the shared native camera", image.viewportOffsetX > 0f)
        assertEquals("Zoomed drag cannot turn the outer pager", 1, viewer.pager.currentItem)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        measure(viewer.pager, 1440, 3120)
        measure(holder, 1440, 3120)
        doubleTap(viewer.pager, SystemClock.uptimeMillis())
        advanceGestureAnimation()
        assertEquals(1f, image.viewportScale, 0.001f)
        assertEquals(0f, image.viewportOffsetX, 0.001f)
        assertEquals(1f, container.scaleX, 0.001f)
        assertEquals(0f, container.translationX, 0.001f)
    }

    @Test
    fun `continuous two finger pinch zooms the group and late page keeps its transform`() = runTest {
        val (holder, right, left) = pair()
        load(holder, right, "RIGHT")
        load(holder, left, "LEFT")
        val originalStream = left.stream
        left.stream = { error("fixture image unavailable") }
        load(holder, left, "LEFT")
        left.stream = originalStream
        mountInPager(holder)
        val start = SystemClock.uptimeMillis()
        MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, 300f, 1400f, 0).also {
            viewer.pager.dispatchTouchEvent(it)
            it.recycle()
        }
        val properties = Array(2) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        fun send(action: Int, time: Long, leftX: Float, rightX: Float) {
            val coordinates = arrayOf(leftX, rightX).map { xValue ->
                MotionEvent.PointerCoords().apply {
                    x = xValue
                    y = 1400f
                    pressure = 1f
                    size = 1f
                }
            }.toTypedArray()
            MotionEvent.obtain(
                start, time, action, 2, properties, coordinates, 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0,
            ).also {
                viewer.pager.dispatchTouchEvent(it)
                it.recycle()
            }
        }
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), start + 20, 300f, 900f)
        val image = descendants(holder).filterIsInstance<ReaderPageImageView>().first()
        var previousScale = 1f
        for ((index, span) in listOf(800f, 1000f, 1200f).withIndex()) {
            send(MotionEvent.ACTION_MOVE, start + 40 + index * 20, 600f - span / 2, 600f + span / 2)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            assertTrue("An expanding physical pinch cannot shrink the group", image.viewportScale >= previousScale)
            previousScale = image.viewportScale
        }
        assertTrue(image.viewportScale > 1f)
        for ((index, span) in listOf(1100f, 1000f).withIndex()) {
            send(MotionEvent.ACTION_MOVE, start + 100 + index * 20, 600f - span / 2, 600f + span / 2)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            assertTrue("A contracting physical pinch cannot enlarge the group", image.viewportScale <= previousScale)
            previousScale = image.viewportScale
        }
        assertTrue(image.viewportScale > 1f)
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), start + 160, 100f, 1100f)
        MotionEvent.obtain(start, start + 180, MotionEvent.ACTION_UP, 100f, 1400f, 0).also {
            viewer.pager.dispatchTouchEvent(it)
            it.recycle()
        }
        load(holder, left, "LEFT")
        measure(holder, 1440, 3120)
        assertEquals(previousScale, image.viewportScale, 0.001f)
        assertEquals(1f, holder.getChildAt(0).scaleX, 0.001f)
        assertEquals(1, viewer.pager.currentItem)
    }

    @Test
    fun `retry button uses failed page loader and Ready collector clears its error`() = runTest {
        val (unused, right, left) = pair()
        val retried = mutableListOf<ReaderPage>()
        right.status = Page.State.Ready
        left.status = Page.State.Error(IllegalStateException("fixture image unavailable"))
        right.chapter.pageLoader = object : PageLoader() {
            override var isLocal = false
            override suspend fun getPages() = listOf(right, left)
            override fun retryPage(page: ReaderPage) {
                retried += page
                page.status = Page.State.Ready
            }
        }
        val holder = DualPagerPageHolder(viewer.activity, viewer, DisplayPage.Double(right, left))
        val errorField = DualPagerPageHolder::class.java.getDeclaredField("errorLayout").apply { isAccessible = true }
        suspend fun waitUntil(predicate: () -> Boolean) = withContext(Dispatchers.Default) {
            withTimeout(10_000) { while (!predicate()) delay(10) }
        }
        try {
            waitUntil {
                errorField.get(holder) != null &&
                    descendants(holder).filterIsInstance<ReaderPageImageView>().count() == 1
            }
            val initialError = errorField.get(holder) as ReaderErrorBinding
            assertEquals(View.VISIBLE, initialError.root.visibility)
            mountInPager(holder)
            measure(holder, 1440, 3120)
            val error = errorField.get(holder) as ReaderErrorBinding
            val buttonBounds = Rect()
            error.actionRetry.getDrawingRect(buttonBounds)
            holder.offsetDescendantRectToMyCoords(error.actionRetry, buttonBounds)
            assertTrue("The original Retry button must be laid out in its LEFT feedback slot", !buttonBounds.isEmpty)
            assertTrue(buttonBounds.right <= holder.width / 2)
            assertTrue(error.actionRetry.performClick())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            assertEquals(listOf(left), retried)
            waitUntil { descendants(holder).filterIsInstance<ReaderPageImageView>().count() == 2 }
            ReflectionHelpers.getField<ReaderPageImageView>(holder, "leftHolder").onImageLoaded()
            waitUntil { error.root.visibility == View.GONE }
            measure(holder, 1440, 3120)
            assertPair(holder, 1440, 3120, 720, 1024)
        } finally {
            detach(holder)
            detach(unused)
        }
    }

    @Test
    fun `content replaced during native dimensions cannot install the old decoded image`() = runTest {
        val (_, page, _) = pair()
        val holder = DualPagerPageHolder(viewer.activity, viewer, DisplayPage.Single(page))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        every { ImageUtil.getImageDimensions(any<okio.BufferedSource>()) } answers {
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            720 to 1024
        }
        try {
            val binding = async { load(holder, page, "CENTER") }
            withContext(Dispatchers.IO) {
                check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            page.stream = { byteArrayOf(1, 2, 3).inputStream() }
            release.countDown()
            binding.await()
            assertEquals(
                "The old decoded pixels must never enter the actual native view after replacement",
                0,
                descendants(holder).filterIsInstance<ReaderPageImageView>().count(),
            )
        } finally {
            release.countDown()
            unmockkObject(ImageUtil)
            detach(holder)
        }
    }

    @Test
    fun `displayed content retains its original lease until release and cannot certify replacement bytes`() = runTest {
        val chapter = loadedChapter(18, 3)
        val pages = requireNotNull(chapter.pages)
        pages.forEach {
            it.publishHeaderFact()
            it.status = Page.State.Ready
        }
        chapter.requestedPage = 1
        viewer.setChapters(ViewerChapters(chapter, null, null))
        measure(viewer.pager, 1440, 3120)
        runCurrent()
        measure(viewer.pager, 1440, 3120)
        val holder = viewer.pager.children.filterIsInstance<DualPagerPageHolder>()
            .first { it.displayPage.containsPage(pages[1]) }
        val originalBytes = requireNotNull(pages[1].stream).invoke().use { it.readBytes() }
        load(holder, pages[1], "RIGHT")
        load(holder, pages[2], "LEFT")
        paint(holder, pages[1], originalBytes)
        paint(holder, pages[2])
        assertTrue(holder.hasRenderedVisiblePages())
        val (owner, ticket) = requireNotNull(holder.presentationScope)
        val originalIdentity = owner.identity.content[1].contentVersion
        pages[1].stream = { readerTestPng(900, 500).inputStream() }
        runCurrent()
        assertTrue("The already exposed original pixels keep their content lease", holder.hasRenderedVisiblePages())
        assertEquals(originalIdentity, owner.identity.content[1].contentVersion)
        assertTrue(originalIdentity != pages[1].encodedContentIdentity())
        paint(holder, pages[1], originalBytes)
        assertEquals(ticket.frameId, holder.presentationScope?.second?.frameId)
        detach(holder)
        assertTrue("Released old pixels cannot settle a replacement holder", !holder.hasRenderedVisiblePages())
    }

    @Test
    fun `failed old stream cannot install an error after the same page content was replaced`() = runTest {
        val (_, page, _) = pair()
        val replacement = requireNotNull(page.stream)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        page.stream = {
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            throw java.io.IOException("old content read failed")
        }
        val holder = DualPagerPageHolder(viewer.activity, viewer, DisplayPage.Single(page))
        try {
            val binding = async { runCatching { load(holder, page, "CENTER") } }
            withContext(Dispatchers.IO) { check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            page.stream = replacement
            release.countDown()
            val result = binding.await()
            assertNull(
                "A late failure from old content cannot show an error for its replacement",
                ReflectionHelpers.getField<ReaderErrorBinding?>(holder, "errorLayout"),
            )
            assertTrue(result.isSuccess)
        } finally {
            release.countDown()
            detach(holder)
        }
    }

    @Test
    fun `known one page and wide cover retain reading cause when stable full slot refreshes`() = runTest {
        for (wideCover in listOf(false, true)) {
            val chapter = loadedChapter(if (wideCover) 12 else 11, if (wideCover) 3 else 1)
            val page = requireNotNull(chapter.pages).first()
            if (wideCover) page.stream = pair(rightWidth = 1440, rightHeight = 720).second.stream
            viewer.setChapters(ViewerChapters(chapter, null, null))
            val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
            try {
                host.get().setContentView(viewer.pager)
                measure(viewer.pager, 1440, 3120)
                runCurrent()
                measure(viewer.pager, 1440, 3120)
                val holder = viewer.pager.children.filterIsInstance<DualPagerPageHolder>()
                    .first { it.displayPage.containsPage(page) }
                val previous = requireNotNull(holder.presentationScope).second
                page.status = Page.State.Ready
                load(holder, page, "CENTER")
                measure(viewer.pager, 1440, 3120)
                val preparedHolder = viewer.pager.children.filterIsInstance<DualPagerPageHolder>()
                    .first { it.displayPage.containsPage(page) }
                load(preparedHolder, page, "CENTER")
                paint(preparedHolder, page)
                val (owner, ticket) = requireNotNull(preparedHolder.presentationScope)
                assertTrue(owner.frame(ticket) != null)
                io.mockk.verify(exactly = 1) { viewer.activity.viewModel.onDualViewportSettled(page, listOf(page)) }
                assertEquals(0, viewer.adapter.session(chapter)?.location?.pageId?.sourcePageIndex)
                assertEquals(0L, viewer.adapter.session(chapter)?.location?.sequence)
                assertEquals(previous.locationSequence, preparedHolder.presentationScope?.second?.locationSequence)
            } finally {
                host.get().setContentView(View(host.get()))
                host.pause().stop().destroy()
            }
        }
    }

    @Test
    fun `late tail slot resolution preserves pending navigation but cannot repeat consumed reading`() = runTest {
        val chapter = loadedChapter(13, 4)
        val pages = requireNotNull(chapter.pages)
        val target = pages.last()
        viewer.setChapters(ViewerChapters(chapter, null, null))
        pages.dropLast(1).forEach { decodeForAdapter(it) }
        viewer.moveToPage(target)
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            host.get().setContentView(viewer.pager)
            measure(viewer.pager, 1440, 3120)
            runCurrent()
            measure(viewer.pager, 1440, 3120)
            val original = viewer.pager.children.filterIsInstance<DualPagerPageHolder>()
                .first { it.displayPage.containsPage(target) }
            assertEquals(ReaderPortraitSingleSlot.RIGHT, (original.displayPage as DisplayPage.Single).slot)
            val sequence = viewer.adapter.session(chapter)?.location?.sequence
            target.status = Page.State.Ready
            load(original, target, "CENTER")
            measure(viewer.pager, 1440, 3120)
            val resolved = viewer.pager.children.filterIsInstance<DualPagerPageHolder>()
                .first { it.displayPage.containsPage(target) }
            assertEquals(ReaderPortraitSingleSlot.RIGHT, (resolved.displayPage as DisplayPage.Single).slot)
            load(resolved, target, "CENTER")
            paint(resolved, target)
            io.mockk.verify(exactly = 1) { viewer.activity.viewModel.onDualViewportSettled(target, listOf(target)) }
            assertEquals(sequence, viewer.adapter.session(chapter)?.location?.sequence)
            assertEquals(3, viewer.adapter.session(chapter)?.location?.pageId?.sourcePageIndex)
            resolved.beginPresentation()
            paint(resolved, target)
            io.mockk.verify(exactly = 1) { viewer.activity.viewModel.onDualViewportSettled(target, listOf(target)) }
        } finally {
            host.pause().stop().destroy()
        }
    }

    @Test
    fun `offscreen neighboring chapter cannot revoke the visible pending presentation`() = runTest {
        val chapter = loadedChapter(14, 1)
        val adjacent = loadedChapter(15, 1)
        val page = requireNotNull(chapter.pages).single()
        viewer.setChapters(ViewerChapters(chapter, null, adjacent))
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            host.get().setContentView(viewer.pager)
            measure(viewer.pager, 1440, 3120)
            runCurrent()
            measure(viewer.pager, 1440, 3120)
            val visible = viewer.pager.children.filterIsInstance<DualPagerPageHolder>()
                .first { it.displayPage.containsPage(page) }
            page.status = Page.State.Ready
            load(visible, page, "CENTER")
            val original = requireNotNull(visible.presentationScope)
            val offscreen = DualPagerPageHolder(
                viewer.activity,
                viewer,
                DisplayPage.Single(requireNotNull(adjacent.pages).single()),
            )
            assertTrue(requireNotNull(offscreen.presentationScope).first !== original.first)
            paint(visible, page)
            io.mockk.verify(exactly = 1) { viewer.activity.viewModel.onDualViewportSettled(page, listOf(page)) }
            val adjacentTicket = requireNotNull(offscreen.presentationScope)
            viewer.destroy()
            assertTrue(original.first.captureViewport() !== original.second)
            assertTrue(adjacentTicket.first.captureViewport() !== adjacentTicket.second)
        } finally {
            host.get().setContentView(View(host.get()))
            host.pause().stop().destroy()
        }
    }

    @Test
    fun `explicit jump inside a retained full item starts its new presentation before pixels settle`() = runTest {
        val chapter = loadedChapter(17, 1)
        val page = requireNotNull(chapter.pages).single()
        viewer.setChapters(ViewerChapters(chapter, null, null))
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            host.get().setContentView(viewer.pager)
            measure(viewer.pager, 1440, 3120)
            runCurrent()
            measure(viewer.pager, 1440, 3120)
            val holder = viewer.pager.children.filterIsInstance<DualPagerPageHolder>().single()
            page.status = Page.State.Ready
            load(holder, page, "CENTER")
            val original = requireNotNull(holder.presentationScope).second
            viewer.moveToPage(page)
            assertEquals(original.locationSequence + 1, viewer.adapter.session(chapter)?.location?.sequence)
            assertEquals(
                viewer.adapter.session(chapter)?.location?.sequence,
                holder.presentationScope?.second?.locationSequence,
            )
            paint(holder, holder.displayPage.firstPage)
            io.mockk.verify(exactly = 1) { viewer.activity.viewModel.onDualViewportSettled(page, listOf(page)) }
        } finally {
            host.get().setContentView(View(host.get()))
            host.pause().stop().destroy()
        }
    }

    @Test
    fun `cold real B1 dimensions flow from HTTP encoded headers to late tail native slot`() = runBlocking {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        val directory = File.createTempFile("reader-http-b1-", ".tmp").apply {
            delete()
            mkdir()
        }
        val wideEntered = java.util.concurrent.CountDownLatch(1)
        val releaseWide = java.util.concurrent.CountDownLatch(1)
        val portrait = readerTestPng(1127, 1600)
        val wide = readerTestPng(1500, 1067)
        val requestedPaths = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = mockwebserver3.MockWebServer().apply {
            dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse {
                    val path = request.url.encodedPath
                    requestedPaths += path
                    if (path == "/chapter") return mockwebserver3.MockResponse(body = "pages:17")
                    if (path == "/image/10") {
                        wideEntered.countDown()
                        check(releaseWide.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    return mockwebserver3.MockResponse.Builder().body(
                        okio.Buffer().write(
                            if (path == "/image/10") wide else portrait,
                        ),
                    ).build()
                }
            }
            start()
        }
        val cache = mockk<eu.kanade.tachiyomi.data.cache.ChapterCache>(relaxed = true)
        fun encodedFile(url: String) = File(directory, "${url.substringAfterLast('/')}.png")
        every { cache.getPageListFromCache(any()) } throws java.io.IOException("cold actual cache")
        every { cache.getImageFile(any()) } answers { encodedFile(firstArg()) }
        every { cache.isImageInCache(any()) } answers { encodedFile(firstArg()).length() > 0 }
        every { cache.putImageToCache(any(), any()) } answers {
            val file = encodedFile(firstArg())
            secondArg<okhttp3.Response>().use { response ->
                file.outputStream().use { output -> response.body.byteStream().use { it.copyTo(output) } }
            }
            true
        }
        val chapter = ReaderChapter(Chapter.create().copy(id = 31, mangaId = 1, url = "/chapter")).apply {
            requestedPage = 16
        }
        val loader = eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader(
            chapter,
            eu.kanade.tachiyomi.ui.reader.loader.HttpReaderMaterializeAdapterIntegrationTest.TestHttpSource(server),
            cache,
        )
        val pageListGeneration = chapter.openPageListForTest()
        assertTrue(chapter.installPageLoader(pageListGeneration, loader))
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        suspend fun pumpUntil(message: String, condition: () -> Boolean) {
            try {
                withTimeout(10_000) {
                    while (!condition()) {
                        main.scheduler.runCurrent()
                        shadowOf(Looper.getMainLooper()).idle()
                        measure(viewer.pager, 1440, 3120)
                        delay(10)
                    }
                }
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError(
                    "$message; paths=$requestedPaths, location=${viewer.adapter.session(chapter)?.location}, " +
                        "deps=${viewer.adapter.session(chapter)?.factDependencies()}, " +
                        "remainingBudget=${viewer.adapter.session(
                            chapter,
                        )?.factBudget?.remainingWaitMillis(System.nanoTime() / 1_000_000L)}, " +
                        "virtualTime=${main.scheduler.currentTime}, selected=${viewer.pager.currentItem}, " +
                        "pages=${chapter.pages?.map { it.index to (it.status to it.pageFact.value.state) }}",
                    error,
                )
            }
            assertTrue(message, condition())
        }
        try {
            val port = eu.kanade.tachiyomi.ui.reader.loader.AndroidReaderChapterContentPort(chapter, loader)
            withContext(Dispatchers.IO) {
                port.loadChapterContent(
                    mihon.domain.reader.materialize.ReaderChapterContentRequest(
                        mihon.domain.reader.session.ReaderChapterId(31),
                        1,
                    ),
                )
            }
            assertEquals(17, port.materializedPages.size)
            assertTrue(
                port.materializedPages.all {
                    it.pageFact.value.state ==
                        mihon.domain.reader.ReaderPageFactState.UNKNOWN
                },
            )
            chapter.completePageListForTest(pageListGeneration, port.materializedPages)
            assertTrue(
                "The real chapter generation must retain its live installed HTTP loader",
                chapter.pageLoader === loader && !loader.isRecycled,
            )
            viewer.setChapters(ViewerChapters(chapter, null, null))
            host.get().setContentView(viewer.pager)
            val tail = port.materializedPages[16]
            loader.onPageSelected(tail)
            pumpUntil("The original normal current request must finish without remote prefix reads") {
                tail.status == Page.State.Ready
            }
            assertEquals("No optional wide-prefix I/O is admitted", 1L, wideEntered.count)
            assertTrue(
                "The remote wide source is not requested merely to classify the tail",
                "/image/10" !in requestedPaths,
            )
            assertEquals(
                mihon.domain.reader.ReaderPageFactState.UNKNOWN,
                port.materializedPages[9].pageFact.value.state,
            )
            val owner = requireNotNull(viewer.adapter.session(chapter))
            assertEquals(16, owner.location?.pageId?.sourcePageIndex)
            assertEquals(
                mihon.domain.reader.ReaderPortraitSingleSlot.RIGHT,
                requireNotNull(owner.targetUnit).slots.single { it.pageId.sourcePageIndex == 16 }.position,
            )
            val holder =
                viewer.pager.children.filterIsInstance<DualPagerPageHolder>().first {
                    it.displayPage.containsPage(tail)
                }
            val decoding = async { load(holder, tail, "CENTER") }
            pumpUntil("The actual encoded tail must reach its native image view") { decoding.isCompleted }
            decoding.await()
            measure(viewer.pager, 1440, 3120)
            val image = descendants(holder).filterIsInstance<ReaderPageImageView>().single()
            assertEquals(1440, image.width)
            assertEquals(720f, requireNotNull(image.viewportFitBounds).width())
            assertEquals(720f, requireNotNull(image.viewportFitBounds).left)
        } finally {
            releaseWide.countDown()
            viewer.destroy()
            main.scheduler.runCurrent()
            loader.recycle()
            host.get().setContentView(View(host.get()))
            host.pause().stop().destroy()
            server.close()
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun `real B2 dimension fixture travels through directory materialization into measured native slots`() = runTest {
        val directory = File.createTempFile("reader-real-b2-", ".tmp").apply {
            delete()
            mkdir()
        }
        val loader = eu.kanade.tachiyomi.ui.reader.loader.DirectoryPageLoader(
            requireNotNull(com.hippo.unifile.UniFile.fromFile(directory)),
        )
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            val dimensions =
                requireNotNull(javaClass.classLoader?.getResourceAsStream("reader/shared-dual-page-real-b2.csv"))
                    .bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.filter { it.isNotBlank() && !it.startsWith("#") }
                            .map { row -> row.split(',').map(String::toInt).let { it[1] to it[2] } }.toList()
                    }
            val images = mutableMapOf<Pair<Int, Int>, ByteArray>()
            dimensions.forEachIndexed { index, dimension ->
                // Synthetic encoded pixels carry only the independently acquired real dimensions.
                val bytes = images.getOrPut(dimension) {
                    readerTestPng(dimension.first, dimension.second)
                }
                File(directory, "%02d.png".format(index + 1)).writeBytes(bytes)
            }
            val chapter = ReaderChapter(Chapter.create().copy(id = 16, mangaId = 1)).apply {
                pageLoader = loader
                requestedPage = 1
            }
            val port = eu.kanade.tachiyomi.ui.reader.loader.AndroidReaderChapterContentPort(chapter, loader)
            withContext(Dispatchers.IO) {
                port.loadChapterContent(
                    mihon.domain.reader.materialize.ReaderChapterContentRequest(
                        mihon.domain.reader.session.ReaderChapterId(16),
                        1,
                    ),
                )
            }
            dimensions.forEachIndexed { index, size ->
                assertEquals(
                    "Encoded fixture header must match real CSV page ${index + 1}",
                    mihon.domain.reader.ReaderPageFact.dimensions(size.first, size.second),
                    port.materializedPages[index].pageFact.value,
                )
            }
            chapter.publishLoadedPageListForTest(port.materializedPages)
            viewer.setChapters(ViewerChapters(chapter, null, null))
            host.get().setContentView(viewer.pager)
            for ((index, expectedWidth, expectedLeft) in listOf(Triple(1, 720, 0), Triple(19, 1440, 0))) {
                val page = port.materializedPages[index]
                viewer.moveToPage(page)
                measure(viewer.pager, 1440, 3120)
                runCurrent()
                measure(viewer.pager, 1440, 3120)
                val holder = viewer.pager.children.filterIsInstance<DualPagerPageHolder>()
                    .first { it.displayPage.containsPage(page) }
                load(holder, page, "CENTER")
                measure(viewer.pager, 1440, 3120)
                val image = descendants(holder).filterIsInstance<ReaderPageImageView>().single()
                var left = image.left
                var parent = image.parent
                while (parent is View && parent !== holder) {
                    left += parent.left
                    parent = parent.parent
                }
                assertEquals(
                    "Real B2 page ${index + 1} physical image width",
                    expectedWidth,
                    image.viewportFitBounds?.width()?.toInt() ?: image.width,
                )
                assertEquals(
                    "Real B2 page ${index + 1} physical image left",
                    expectedLeft,
                    image.viewportFitBounds?.left?.toInt() ?: left,
                )
                assertEquals(index, viewer.adapter.session(chapter)?.location?.pageId?.sourcePageIndex)
            }
        } finally {
            host.get().setContentView(View(host.get()))
            host.pause().stop().destroy()
            loader.recycle()
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun `old native child listener cannot report replacement pixels through the new holder`() = runTest {
        val chapter = ReaderChapter(Chapter.create().copy(id = 28, mangaId = 1))
        val first = readerTestPng(40, 60)
        val replacement = readerTestPng(50, 70)
        val page = ReaderPage(0, stream = { first.inputStream() }).apply {
            this.chapter = chapter
            status = Page.State.Ready
        }
        chapter.publishLoadedPageListForTest(listOf(page))
        viewer.setChapters(ViewerChapters(chapter, null, null))
        runCurrent()
        val holder = DualPagerPageHolder(
            viewer.activity,
            viewer,
            viewer.adapter.items.filterIsInstance<DisplayPage>().single(),
        )
        try {
            load(holder, page, "CENTER")
            val child = descendants(holder).filterIsInstance<ReaderPageImageView>().single()
            val native =
                descendants(child).filterIsInstance<NativeImageView>().single()
            val listenerField = NativeImageView::class.java.declaredFields
                .single { it.type == NativeImageListener::class.java }
                .apply { isAccessible = true }
            val original =
                listenerField.get(native) as NativeImageListener
            page.stream = { replacement.inputStream() }
            page.publishHeaderFact()
            runCurrent()
            load(holder, page, "CENTER")
            assertTrue(!holder.hasRenderedVisiblePages())
            original.onReady()
            assertTrue(
                "Saved old native listener cannot invoke the replacement holder's new rendered callback",
                !holder.hasRenderedVisiblePages(),
            )
            original.onImageLoadError(IllegalStateException("fixture stale native decode"))
            assertTrue(ReflectionHelpers.getField<ReaderErrorBinding?>(holder, "errorLayout") == null)
            val current =
                listenerField.get(native) as NativeImageListener
            current.onReady()
            assertTrue(
                "Current metadata readiness also cannot substitute for actual painting",
                !holder.hasRenderedVisiblePages(),
            )
            paint(holder, page, replacement)
            assertTrue(holder.hasRenderedVisiblePages())
        } finally {
            detach(holder)
        }
    }

    @Test
    fun `existing native error retry renews fact work without manufacturing a navigation`() = runTest {
        val chapter = ReaderChapter(Chapter.create().copy(id = 27, mangaId = 1))
        val page = ReaderPage(0, stream = { error("fixture image unavailable") }).apply {
            this.chapter = chapter
            status = Page.State.Error(IllegalStateException("fixture image unavailable"))
        }
        chapter.publishLoadedPageListForTest(listOf(page))
        var retryCalls = 0
        chapter.pageLoader = object : PageLoader() {
            override var isLocal = false
            override suspend fun getPages() = listOf(page)
            override fun retryPage(page: ReaderPage) {
                super.retryPage(page)
                retryCalls++
            }
        }
        viewer.setChapters(ViewerChapters(chapter, null, null))
        runCurrent()
        val display = viewer.adapter.items.filterIsInstance<DisplayPage>().single()
        val holder = DualPagerPageHolder(viewer.activity, viewer, display)
        try {
            load(holder, page, "CENTER")
            runCurrent()
            val errorField = DualPagerPageHolder::class.java.getDeclaredField("errorLayout").apply {
                isAccessible =
                    true
            }
            withContext(Dispatchers.Default) {
                withTimeout(5000) { while (errorField.get(holder) == null) delay(10) }
            }
            val owner = requireNotNull(viewer.adapter.session(chapter))
            val location = owner.location
            val originalBudget = owner.factBudget
            val originalRequest = owner.requestFact(owner.identity.pageIds.single())
            val binding = ReflectionHelpers.getField<ReaderErrorBinding>(holder, "errorLayout")
            assertTrue(binding.actionRetry.performClick())
            runCurrent()
            assertEquals(1, retryCalls)
            assertEquals(location, owner.location)
            assertTrue("Actual retry event must renew only optional fact work", originalBudget !== owner.factBudget)
            assertTrue(!owner.acceptFact(originalRequest, mihon.domain.reader.ReaderPageFact.dimensions(20, 40)))
        } finally {
            detach(holder)
        }
    }

    @Test
    fun `single page keeps full viewport and its own image touch target`() = runTest {
        val (_, page, _) = pair()
        val holder = DualPagerPageHolder(viewer.activity, viewer, DisplayPage.Single(page))
        load(holder, page, "CENTER")
        measure(holder, 1440, 3120)
        val image = descendants(holder).filterIsInstance<ReaderPageImageView>().single()
        assertEquals(1440, image.width)
        assertEquals(3120, image.height)
        var imageTouches = 0
        image.getChildAt(0).setOnTouchListener { _, _ ->
            imageTouches++
            true
        }
        doubleTap(holder, SystemClock.uptimeMillis() + 1000)
        assertTrue(imageTouches > 0)
        assertEquals(1f, holder.getChildAt(0).scaleX, 0.001f)
    }

    @Test
    fun `error retry preserves the surviving image slot and clears error after success`() = runTest {
        val (holder, right, left) = pair()
        load(holder, right, "RIGHT")
        measure(holder, 1440, 3120)
        val originalStream = left.stream
        left.stream = { error("fixture image unavailable") }
        load(holder, left, "LEFT")
        measure(holder, 1440, 3120)
        val errorField = DualPagerPageHolder::class.java.getDeclaredField("errorLayout").apply { isAccessible = true }
        val error = errorField.get(holder) as ReaderErrorBinding
        assertEquals(View.VISIBLE, error.root.visibility)
        load(holder, right, "RIGHT")
        assertEquals("A successful neighbour cannot dismiss this page's error", View.VISIBLE, error.root.visibility)
        val surviving = descendants(holder).filterIsInstance<ReaderPageImageView>().single()
        assertEquals(1440, surviving.width)
        assertEquals(720f, requireNotNull(surviving.viewportFitBounds).width())
        var childTouches = 0
        surviving.getChildAt(0).setOnTouchListener { _, _ ->
            childTouches++
            true
        }
        doubleTap(holder, SystemClock.uptimeMillis(), x = 1080f)
        advanceGestureAnimation()
        assertEquals("An error must not enable independent image gestures", 0, childTouches)
        assertEquals(2f, surviving.viewportScale, 0.001f)
        assertEquals(1f, holder.getChildAt(0).scaleX, 0.001f)
        left.stream = originalStream
        left.status = Page.State.Ready
        load(holder, left, "LEFT")
        ReflectionHelpers.getField<ReaderPageImageView>(holder, "leftHolder").onImageLoaded()
        measure(holder, 1440, 3120)
        assertPair(holder, 1440, 3120, 720, 1024)
        assertEquals(error.errorMessage.text.toString(), View.GONE, error.root.visibility)
    }

    private fun mountInPager(holder: DualPagerPageHolder) {
        viewer.pager.visibility = View.VISIBLE
        Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().get().also {
            hosts += it
            it.setContentView(viewer.pager)
        }
        // Finish the original window's initial traversal before installing this gesture-only adapter.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        viewer.pager.clearOnPageChangeListeners()
        viewer.pager.adapter = object : ViewPagerAdapter() {
            override fun getCount(): Int = 3
            override fun createView(container: ViewGroup, position: Int): View =
                if (position == 1) holder else View(container.context)
        }
        viewer.pager.currentItem = 1
        measure(viewer.pager, 1440, 3120)
        measure(holder, 1440, 3120)
    }

    private fun doubleTap(target: View, time: Long, x: Float = 400f) {
        for (down in listOf(time, time + 100)) {
            for ((action, eventTime) in listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 20)) {
                MotionEvent.obtain(down, eventTime, action, x, 1400f, 0).also {
                    target.dispatchTouchEvent(it)
                    it.recycle()
                }
            }
        }
    }

    private fun advanceGestureAnimation() {
        // The paused Choreographer needs individual display frames. One large idleFor jump only
        // starts ValueAnimator at its first frame; it does not reproduce a continuous animation.
        repeat(viewer.config.doubleTapAnimDuration / 16 + 3) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
    }

    private fun detach(holder: DualPagerPageHolder) {
        val method = DualPagerPageHolder::class.java.getDeclaredMethod("onDetachedFromWindow")
        method.isAccessible = true
        method.invoke(holder)
    }

    private fun pair(
        leftWidth: Int = 1125,
        leftHeight: Int = 1600,
        rightWidth: Int = 1125,
        rightHeight: Int = 1600,
    ): Triple<DualPagerPageHolder, ReaderPage, ReaderPage> {
        val chapter = ReaderChapter(Chapter.create().copy(id = 1, mangaId = 1))
        val rightBytes = readerTestPng(rightWidth, rightHeight)
        val leftBytes = readerTestPng(leftWidth, leftHeight)
        val right = ReaderPage(0, stream = { rightBytes.inputStream() }).apply { this.chapter = chapter }
        val left = ReaderPage(1, stream = { leftBytes.inputStream() }).apply { this.chapter = chapter }
        return Triple(DualPagerPageHolder(viewer.activity, viewer, DisplayPage.Double(right, left)), right, left)
    }

    // Exercise the production byte decode and image binding entry, controlling completion order
    // without a network loader or a copied layout implementation.
    private suspend fun load(holder: DualPagerPageHolder, page: ReaderPage, sideName: String) {
        val sideType = DualPagerPageHolder::class.java.declaredClasses.single { it.simpleName == "Side" }
        val side = if (sideName == "CENTER") {
            ReflectionHelpers.callInstanceMethod<Map<ReaderPage, Any>>(
                holder,
                "pageSides",
                ReflectionHelpers.ClassParameter.from(DisplayPage::class.java, holder.displayPage),
            ).getValue(page)
        } else {
            sideType.enumConstants.single { it.toString() == sideName }
        }
        val method = DualPagerPageHolder::class.java.declaredMethods.single { it.name == "setImage" }
        method.isAccessible = true
        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            val result = method.invoke(holder, page, side, continuation)
            if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else Unit
        }
        val errorField = DualPagerPageHolder::class.java.getDeclaredField("errorLayout").apply { isAccessible = true }
        val error = errorField.get(holder) as? ReaderErrorBinding
        if (error != null && error.root.visibility == View.VISIBLE) {
            assertTrue(
                "Unexpected image binding failure: ${error.errorMessage.text}",
                error.errorMessage.text.contains("fixture image unavailable"),
            )
        }
    }

    private fun paint(
        holder: DualPagerPageHolder,
        page: ReaderPage,
        encoded: ByteArray = requireNotNull(page.stream).invoke().use { it.readBytes() },
    ) {
        measure(holder, holder.width.takeIf { it > 0 } ?: 1440, holder.height.takeIf { it > 0 } ?: 3120)
        val field = if ((holder.displayPage as? DisplayPage.Double)?.leftPage === page) "leftHolder" else "rightHolder"
        paintReaderImage(ReflectionHelpers.getField(holder, field), encoded)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
    }

    private fun measure(holder: View, width: Int, height: Int) {
        // Slot feedback is real Compose content and needs the actual window lifecycle.
        if (!holder.isAttachedToWindow) {
            val root = generateSequence(holder) { it.parent as? View }.last()
            if (root.parent == null) {
                Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().get().also {
                    hosts += it
                    it.setContentView(root, FrameLayout.LayoutParams(width, height))
                }
            }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        // These explicit measurements must consume layout params changed during the previous layout.
        holder.forceLayout()
        holder.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        holder.layout(holder.left, holder.top, holder.left + width, holder.top + height)
    }

    private fun assertPair(holder: DualPagerPageHolder, width: Int, height: Int, imageWidth: Int, imageHeight: Int) {
        val images = descendants(holder).filterIsInstance<ReaderPageImageView>().toList()
        assertEquals("Both production images must be mounted", 2, images.size)
        images.forEachIndexed { index, image ->
            assertEquals("image $index native viewport width", width, image.width)
            assertEquals("image $index native viewport height", height, image.height)
            val fit = requireNotNull(image.viewportFitBounds)
            assertEquals("image $index Fit width", imageWidth, fit.width().toInt())
            assertEquals("image $index Fit height", imageHeight, fit.height().toInt())
            assertEquals(
                "image $index vertically centered in $width x $height",
                (height - imageHeight) / 2,
                fit.top.toInt(),
            )
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
