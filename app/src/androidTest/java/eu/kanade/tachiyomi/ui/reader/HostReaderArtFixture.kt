package eu.kanade.tachiyomi.ui.reader

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import mihon.domain.reader.scheduler.ReaderPageMaterializeRunner
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

/** Calls the installed candidate through Android component/View and retained extension APIs. */
internal class HostReaderArtFixture(
    private val kind: String,
    originalArchive: String? = null,
    private val performancePageCount: Int? = null,
) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val cacheDiagnostic = InstrumentationRegistry.getArguments()
        .getString("performanceCacheDiagnostic") == "true"
    private val diagnosticCache by lazy { Injekt.get<ChapterCache>() }
    private val diagnosticRequestSequence = AtomicInteger()
    val context = instrumentation.targetContext
    private val originalContent = originalArchive?.let { name ->
        val expected = when (name) {
            "real-b1-original.cbz" ->
                "fec7ffef2dc6773c0c917b5e9961fff745cca5eb9eaccc82deee2dffe26b522b" to 17
            "real-b2-original.cbz" ->
                "760c81b12dee475288833b29b1f965fa097d4dbafbc36b6cc62f3e2d5a9ac439" to 22
            else -> error("Only the two independently verified original archives are accepted")
        }
        val archive = staged(name)
        check(byteIdentity(archive).substringAfter("sha256=") == expected.first)
        val entries = ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            buildList {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) add(entry.name to zip.readBytes())
                }
            }.sortedBy { it.first }
        }
        check(entries.size == expected.second)
        byteIdentity(archive) to entries
    }
    val originalArchiveIdentity: String? get() = originalContent?.first
    fun originalEncodedPage(index: Int): ByteArray {
        val entries = requireNotNull(originalContent).second
        return entries[if (performancePageCount == null) index else index % entries.size].second
    }

    // AndroidPreferenceStore consumes this same default SharedPreferences file. Use its public Android
    // storage boundary rather than host interfaces that R8 has devirtualized into concrete classes.
    private val preferences = context.getSharedPreferences("${context.packageName}_preferences", 0)
    private val input = JSONArray(staged("source-inputs.json").toString(Charsets.UTF_8)).let { rows ->
        (0 until rows.length()).map(rows::getJSONObject).single {
            it.getLong("chapterId") == if (kind == "copymanga") 15652L else 15640L
        }
    }
    val success = if (kind == "copymanga") {
        staged("copy/success.json").let { captured ->
            if (performancePageCount == null) {
                captured
            } else {
                check(originalContent != null && performancePageCount == 300)
                val document = JSONObject(captured.toString(Charsets.UTF_8))
                val chapter = document.getJSONObject("results").getJSONObject("chapter")
                val prototype = chapter.getJSONArray("contents").getJSONObject(0)
                chapter.put(
                    "contents",
                    JSONArray(
                        (0 until performancePageCount).map { index ->
                            JSONObject(prototype.toString()).put(
                                "url",
                                android.net.Uri.parse(prototype.getString("url")).buildUpon()
                                    .path("/sp01-performance/$index.jpg").build().toString(),
                            )
                        },
                    ),
                )
                chapter.put("words", JSONArray((0 until performancePageCount).toList()))
                document.toString().toByteArray(Charsets.UTF_8)
            }
        }
    } else {
        instrumentation.context.assets.open("shared-reader-manhuagui-packed.html").use {
            it.readBytes()
        }.let { packed ->
            originalContent?.let { (_, entries) ->
                check(entries.size == 17)
                val files = JSONArray(entries.map { it.first }).toString()
                val expanded = packed.toString(Charsets.UTF_8).replace("\"files\":[\"001.jpg\"]", "\"files\":$files")
                check(expanded != packed.toString(Charsets.UTF_8))
                expanded.toByteArray(Charsets.UTF_8)
            } ?: packed
        }
    }
    val response = AtomicReference(200 to success)
    val imageRequests = AtomicInteger()
    val responsePayloadBytes = AtomicLong()
    val virtualErrorBody = AtomicReference<ByteArray?>(null)
    val chapterRequests = AtomicInteger()
    val image = AtomicReference(png(Color.CYAN))
    private val returnedBodies = Collections.synchronizedList(mutableListOf<String>())
    private val privateRequestPaths = Collections.synchronizedList(mutableListOf<String>())
    val images = AtomicReference(
        originalContent?.second?.let { entries ->
            List(performancePageCount ?: entries.size) { entries[it % entries.size].second }
        },
    )
    val beforeImageRequest = AtomicReference<(Int) -> Unit>({})
    val imageStatuses = AtomicReference<Map<Int, Int>>(emptyMap())
    private val imagePaths = if (kind == "copymanga") {
        JSONObject(success.toString(Charsets.UTF_8)).getJSONObject("results").getJSONObject("chapter").also { chapter ->
            val words = chapter.getJSONArray("words")
            check((0..3).map(words::getInt) == listOf(0, 1, 2, 3)) {
                "Original Copy first four image identities must retain source order"
            }
        }.let { chapter ->
            val rows = chapter.getJSONArray("contents")
            val words = chapter.getJSONArray("words")
            val indices = (0 until rows.length()).let { indices ->
                if (originalContent == null) indices.toList() else indices.sortedBy(words::getInt)
            }
            if (originalContent != null) check(indices.map(words::getInt) == (0 until rows.length()).toList())
            indices.map {
                android.net.Uri.parse(rows.getJSONObject(it).getString("url")).path
            }
        }
    } else {
        originalContent?.second?.map { "/comic/123/${it.first}" } ?: listOf("/comic/123/001.jpg")
    }
    val imagePageRequests = AtomicIntegerArray(maxOf(22, imagePaths.size))
    private val chapterPath = if (kind == "copymanga") {
        val parts = input.getString("chapterUrl").split('/')
        check(parts.size == 3 && parts[1] == "chapter")
        "/api/v3/comic/${parts[0]}/chapter2/${parts[2]}"
    } else {
        android.net.Uri.parse(input.getString("chapterUrl")).path
    }
    private val preferenceKeys = listOf(
        "pref_default_reading_mode_key",
        "reader_auto_spread_matching",
        "pref_dual_page_split",
        "pref_dual_page_invert",
        "landscape_zoom",
        "pref_image_scale_type_key",
        "pref_zoom_start_key",
        "crop_borders",
    )
    private val previousPreferences = preferenceKeys.associateWith { preferences.all[it] }

    private val failure = AtomicReference<Throwable?>()
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val network = Injekt.get<NetworkHelper>()

    @Suppress("DEPRECATION")
    private fun clients() = listOf(network.client, network.cloudflareClient, network.nonCloudflareClient)
    private val originalClients = clients()
    private val fields = network.javaClass.declaredFields.filter { field ->
        if (!OkHttpClient::class.java.isAssignableFrom(field.type)) {
            false
        } else {
            field.isAccessible = true
            originalClients.any { it === field.get(network) }
        }
    }
    private val originals = fields.map { it.get(network) }
    private var activity: Activity? = null
    private val worker = Thread {
        try {
            while (!server.isClosed) {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    // A cancelled/speculative connection can close without an HTTP request.
                    val request = reader.readLine() ?: return@use
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    val path = request.substringAfter(' ').substringBefore(' ').substringBefore('?')
                    privateRequestPaths.add(request.substringAfter(' ').substringBefore(' '))
                    val isImage = path in imagePaths
                    val isChapter = path == chapterPath
                    val (status, body) = if (isImage) {
                        imageRequests.incrementAndGet()
                        imagePaths.indexOf(path).takeIf { it >= 0 }?.let { imagePageRequests.incrementAndGet(it) }
                        val index = imagePaths.indexOf(path)
                        (imageStatuses.get()[index] ?: 200) to (images.get()?.getOrNull(index) ?: image.get())
                    } else if (isChapter) {
                        chapterRequests.incrementAndGet()
                        response.get()
                    } else {
                        failure.set(
                            IllegalStateException(
                                "Unexpected original source request pathHash=" +
                                    byteIdentity(path.toByteArray(Charsets.UTF_8)).substringAfter("sha256="),
                            ),
                        )
                        404 to "Unknown fixture endpoint".toByteArray(Charsets.US_ASCII)
                    }
                    responsePayloadBytes.addAndGet(body.size.toLong())
                    val contentType = if (isImage) {
                        if (originalContent != null) {
                            body.inputStream().use(tachiyomi.core.common.util.system.ImageUtil::findImageType)?.mime
                                ?: error("Original encoded image has no supported type")
                        } else {
                            "image/png"
                        }
                    } else if (!isChapter) {
                        "text/plain"
                    } else if (kind == "copymanga") {
                        "application/json"
                    } else {
                        "text/html"
                    }
                    val pathHash = byteIdentity(path.toByteArray(Charsets.UTF_8)).substringAfter("sha256=")
                    returnedBodies.add(
                        "$status image=$isImage originalImageIndex=${imagePaths.indexOf(path)} " +
                            "requestPathHash=$pathHash " +
                            "body=${byteIdentity(body)}",
                    )
                    try {
                        socket.getOutputStream().use { output ->
                            output.write(
                                (
                                    "HTTP/1.1 $status Acceptance\r\nContent-Type: $contentType\r\n" +
                                        "Content-Length: ${body.size}\r\n" +
                                        "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
                                    )
                                    .toByteArray(Charsets.US_ASCII),
                            )
                            output.write(body)
                        }
                    } catch (error: SocketException) {
                        if (error.message != "Broken pipe" && error.message != "Connection reset") throw error
                    }
                }
            }
        } catch (error: Throwable) {
            if (!server.isClosed) failure.set(error)
        }
    }.apply {
        isDaemon = true
        start()
    }

    init {
        try {
            check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
            check(fields.isNotEmpty())
            val route = Interceptor { chain ->
                val request = chain.request()
                imagePaths.indexOf(request.url.encodedPath).takeIf { it >= 0 }
                    ?.let { beforeImageRequest.get().invoke(it) }
                // Original Copy TextInterceptor consumes this virtual origin later in the same client chain.
                // Changing its host would bypass the extension's real error/comment image renderer.
                if (request.url.host == "tachiyomi-lib-textinterceptor") {
                    val rendered = chain.proceed(request)
                    check(rendered.isSuccessful && rendered.body.contentType()?.type == "image") {
                        "Original virtual interceptor must return its own image response"
                    }
                    virtualErrorBody.set(rendered.peekBody(2L * 1024 * 1024).bytes())
                    return@Interceptor rendered
                }
                val local = request.url.newBuilder().scheme("http").host("127.0.0.1").port(server.localPort).build()
                val response = chain.proceed(request.newBuilder().url(local).build())
                if (cacheDiagnostic && response.body.contentType()?.type == "image") {
                    observeCacheResponse(request.url.toString(), response)
                } else {
                    response
                }
            }
            fields.zip(originals).forEach { (field, original) ->
                val client = original as OkHttpClient
                field.set(
                    network,
                    client.newBuilder().addInterceptor(route).dns { hostname ->
                        check(hostname != "tachiyomi-lib-textinterceptor") {
                            "Original TextInterceptor must consume virtual origin before DNS"
                        }
                        client.dns.lookup(hostname)
                    }.build(),
                )
            }
            check(clients().all { it.interceptors.any { interceptor -> interceptor === route } })
            val packageName = "eu.kanade.tachiyomi.extension.zh.$kind"
            install(kind)
            val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signer = requireNotNull(info.signingInfo).signingCertificateHistory.last().toByteArray()
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(signer).joinToString("") {
                "%02x".format(it.toInt() and 255)
            }
            val trustKey = "__APP_STATE_trusted_extensions"
            val trust = requireNotNull(preferences.getStringSet(trustKey, emptySet()))
            check(
                preferences.edit().putStringSet(trustKey, trust + "$packageName:${info.longVersionCode}:$fingerprint")
                    .commit(),
            )
            // PackageManager emits the real replacement event. The normal host registry reloads the trusted
            // artifact after routing, including original extensions that eagerly captured their HTTP client.
            install(kind)
            check(
                preferences.edit().putInt("pref_default_reading_mode_key", 1)
                    .putBoolean("reader_auto_spread_matching", false)
                    .putBoolean("pref_dual_page_split", false)
                    .putBoolean("landscape_zoom", false).commit(),
            )
            seed()
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun install(name: String) {
        val result = shell("pm install -r /data/local/tmp/udp-art-native/$name.apk").toString(Charsets.UTF_8)
        check(result.contains("Success")) { "Original extension installation failed" }
    }

    /** Read-only actual cache/editor state; never call get/edit or change journal membership. */
    private fun cacheDiagnosticState(url: String? = null): String {
        val cache = diagnosticCache
        val disk = ChapterCache::class.java.getDeclaredField("diskCache")
            .apply { isAccessible = true }.get(cache)
        val key = url?.let { eu.kanade.tachiyomi.util.storage.DiskUtil.hashKeyForDisk(it) }
        val (active, keyEditor) = synchronized(disk) {
            val entries = disk.javaClass.getDeclaredField("lruEntries").apply { isAccessible = true }
                .get(disk) as Map<*, *>
            fun editorIdentity(entry: Any?): Int? {
                if (entry == null) return null
                val editor = entry.javaClass.getDeclaredField("currentEditor")
                    .apply { isAccessible = true }.get(entry)
                return editor?.let(System::identityHashCode)
            }
            entries.mapNotNull { (entryKey, entry) ->
                editorIdentity(entry)?.let { "$entryKey:$it" }
            }.take(8) to editorIdentity(entries[key])
        }
        return "cache=${System.identityHashCode(cache)} key=$key keyEditor=$keyEditor editors=$active " +
            "file=${url?.let { cache.getImageFile(it).exists() }} " +
            "normal=${ReaderPageMaterializeRunner.processPhysicalWorkCount.value} " +
            "native=${ReaderPageImageView.foregroundWork.value}"
    }

    /** Actual disk journal accounting, including historical orphan records from earlier raw fixture resets. */
    fun cacheJournalState(): Pair<Long, Set<String>> {
        val disk = ChapterCache::class.java.getDeclaredField("diskCache")
            .apply { isAccessible = true }.get(diagnosticCache)
        return synchronized(disk) {
            val entries = disk.javaClass.getDeclaredField("lruEntries").apply { isAccessible = true }
                .get(disk) as Map<*, *>
            val bytes = disk.javaClass.getMethod("size").invoke(disk) as Long
            bytes to entries.keys.filterIsInstance<String>().toSet()
        }
    }

    private fun observeCacheResponse(url: String, response: Response): Response {
        val requestId = diagnosticRequestSequence.incrementAndGet()
        val original = response.body
        val bytes = AtomicLong()
        val closed = AtomicBoolean()
        val observed = object : ForwardingSource(original.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val result = super.read(sink, byteCount)
                if (result > 0 && bytes.getAndAdd(result) == 0L) {
                    android.util.Log.i(
                        "ReaderCacheDiagnostic",
                        "request=$requestId firstRead time=${System.nanoTime()} ${cacheDiagnosticState(url)}",
                    )
                }
                return result
            }

            override fun close() {
                try {
                    super.close()
                } finally {
                    if (closed.compareAndSet(false, true)) {
                        android.util.Log.i(
                            "ReaderCacheDiagnostic",
                            "request=$requestId close bytes=${bytes.get()} expected=${original.contentLength()} " +
                                "time=${System.nanoTime()} ${cacheDiagnosticState(url)}",
                        )
                    }
                }
            }
        }.buffer()
        return response.newBuilder().body(object : ResponseBody() {
            override fun contentType() = original.contentType()
            override fun contentLength() = original.contentLength()
            override fun source() = observed
        }).build()
    }

    fun configure(
        mode: Int = 1,
        matching: Boolean = false,
        split: Boolean = false,
        invert: Boolean = false,
        crop: Boolean = false,
        scale: Int = 1,
    ) {
        check(
            preferences.edit().putInt("pref_default_reading_mode_key", mode)
                .putBoolean("reader_auto_spread_matching", matching).putBoolean("pref_dual_page_split", split)
                .putBoolean("pref_dual_page_invert", invert).putBoolean("crop_borders", crop)
                .putInt("pref_image_scale_type_key", scale).putInt("pref_zoom_start_key", 4).commit(),
        )
    }

    private fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
        (0 until node.childCount).mapNotNull(node::getChild).flatMap(::nodes)

    private fun retryNode(): AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow?.let {
        nodes(it).firstOrNull { node -> node.text?.toString()?.lowercase() in listOf("retry", "重试") }
    }

    fun hasChapterError(): Boolean = retryNode() != null && currentImages().isEmpty()

    fun retry() {
        var node = requireNotNull(retryNode())
        while (!node.isClickable) node = requireNotNull(node.parent)
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Actual ChapterError Retry must accept click" }
    }

    /** Compare the original extension-generated PNG with the currently painted public window. */
    fun paintedVirtualError(): Boolean {
        val bytes = virtualErrorBody.get() ?: context.cacheDir.resolve("chapter_disk_cache")
            .listFiles().orEmpty().also {
                check(it.size <= 48) { "Isolated source case must retain bounded cache entries" }
            }
            .filter { it.extension == "0" && it.length() in 9..(2L * 1024 * 1024) }
            .singleOrNull { file ->
                file.inputStream().use { it.readNBytes(8) }
                    .contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            }?.readBytes() ?: return false
        val expected = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        val painted = frame() ?: return false.also { expected.recycle() }
        var scaled: Bitmap? = null
        try {
            // The original text interceptor image is white with black text, surrounded by black reader bars.
            // Find its full-width white band, excluding the separate page counter below the image.
            val rows = (0 until painted.height).filter { y ->
                // Text may occupy most of a row; the interceptor's own white outer margins remain intact.
                closeColor(painted.getPixel(painted.width / 100, y), Color.WHITE) &&
                    closeColor(painted.getPixel(painted.width - painted.width / 100 - 1, y), Color.WHITE)
            }
            val runs = mutableListOf<IntRange>()
            var first = -1
            var last = -2
            for (row in rows) {
                if (row != last + 1) {
                    if (first >= 0) runs.add(first..last)
                    first = row
                }
                last = row
            }
            if (first >= 0) runs.add(first..last)
            val band = runs.maxByOrNull { it.last - it.first } ?: return false
            if (band.last - band.first < 50) return false
            val ratio = painted.width.toDouble() / (band.last - band.first + 1)
            if (kotlin.math.abs(ratio - expected.width.toDouble() / expected.height) > 0.1) return false
            val reference = Bitmap.createScaledBitmap(expected, painted.width, band.last - band.first + 1, true)
            scaled = reference
            var dark = 0
            var matching = 0
            for (y in 0 until 30) {
                for (x in 0 until 80) {
                    val px = reference.width * (x * 2 + 1) / 160
                    val sy = reference.height * (y * 2 + 1) / 60
                    val a = reference.getPixel(px, sy)
                    if (closeColor(a, Color.BLACK)) {
                        dark++
                        val py = band.first + sy
                        // SSIV's subpixel scaling can shift a thin font stroke by one physical pixel.
                        if ((-1..1).any { dx ->
                                (-1..1).any { dy -> closeColor(painted.getPixel(px + dx, py + dy), Color.BLACK) }
                            }
                        ) {
                            matching++
                        }
                    }
                }
            }
            return dark >= 10 && matching >= dark * 0.95
        } finally {
            if (scaled !== expected) scaled?.recycle()
            expected.recycle()
            painted.recycle()
        }
    }

    private fun clickUi(label: String, scroll: Boolean = false) {
        await("Actual settings UI must expose $label") {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@await false
            val all = nodes(root)
            var node = all.firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
            if (node == null) {
                if (scroll) {
                    all.firstOrNull {
                        it.isScrollable
                    }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
                false
            } else {
                while (node != null && !node.isClickable) node = node.parent
                node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
        }
    }

    /** Recovery uses the same visible cache clearing action offered to users by the extension's error image. */
    fun clearChapterCacheThroughSettings() {
        finish()
        activity = instrumentation.startActivitySync(
            Intent().setClassName(context.packageName, "eu.kanade.tachiyomi.ui.main.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        clickUi("More")
        clickUi("Settings")
        clickUi("Data and storage", scroll = true)
        clickUi("Clear chapter cache", scroll = true)
        await("Real clear chapter cache action must evict original encoded/page-table cache") {
            context.cacheDir.resolve("chapter_disk_cache").listFiles().orEmpty().none { it.extension == "0" }
        }
    }

    fun matching(enabled: Boolean) {
        check(preferences.edit().putBoolean("reader_auto_spread_matching", enabled).commit())
    }

    fun awaitStable(message: String, condition: () -> Boolean) {
        var since = 0L
        await(message) {
            val now = SystemClock.uptimeMillis()
            if (!condition()) {
                since = 0L
                false
            } else {
                if (since == 0L) since = now
                now - since >= 500
            }
        }
    }

    fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
    }

    fun touch(action: Int, down: Long, time: Long, x: Float, y: Float) {
        instrumentation.runOnMainSync {
            val view = currentImageOnMain()
            val offset = IntArray(2).also(view::getLocationInWindow)
            MotionEvent.obtain(down, time, action, x - offset[0], y - offset[1], 0).let { event ->
                try {
                    view.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
        }
    }

    private fun currentImageOnMain(): View = views(requireNotNull(activity).window.decorView).first {
        ReaderPageImageView::class.java.isInstance(it) && it.isShown && it.getGlobalVisibleRect(Rect())
    }

    fun quadrants(colors: List<Int>): Boolean {
        val bitmap = frame() ?: return false
        try {
            val bounds = colorBounds(bitmap, colors)
            if (bounds.width() < 8 || bounds.height() < 8) return false
            return listOf(1 to 1, 1 to 3, 3 to 1, 3 to 3).zip(colors).all { (point, color) ->
                closeColor(
                    bitmap.getPixel(
                        bounds.left + bounds.width() * point.first / 4,
                        bounds.top + bounds.height() * point.second / 4,
                    ),
                    color,
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    fun containsColor(color: Int): Boolean {
        val bitmap = frame() ?: return false
        try {
            for (y in 0 until bitmap.height step 8) {
                for (x in 0 until bitmap.width step 8) {
                    if (closeColor(bitmap.getPixel(x, y), color)) return true
                }
            }
            return false
        } finally {
            bitmap.recycle()
        }
    }

    fun coloredBounds(colors: List<Int>): Rect {
        val bitmap = requireNotNull(frame())
        try {
            return colorBounds(bitmap, colors)
        } finally {
            bitmap.recycle()
        }
    }

    private fun colorBounds(bitmap: Bitmap, colors: List<Int>): Rect {
        val result = Rect(bitmap.width, bitmap.height, 0, 0)
        for (y in 0 until bitmap.height step 4) {
            for (x in 0 until bitmap.width step 4) {
                if (colors.any { closeColor(bitmap.getPixel(x, y), it) }) {
                    result.left = minOf(result.left, x)
                    result.top = minOf(result.top, y)
                    result.right = maxOf(result.right, x + 1)
                    result.bottom = maxOf(result.bottom, y + 1)
                }
            }
        }
        return result
    }

    fun staged(name: String): ByteArray {
        check(name.matches(Regex("[a-zA-Z0-9/._-]+")) && !name.contains(".."))
        return shell("cat /data/local/tmp/udp-art-native/$name")
    }

    /** Same captured shape and ordering; only fixture body cache identities receive a unique query. */
    fun successWithImageIdentity(identity: String): ByteArray {
        check(identity.matches(Regex("[a-zA-Z0-9_-]+")))
        if (kind == "copymanga") {
            val root = JSONObject(success.toString(Charsets.UTF_8))
            val contents = root.getJSONObject("results").getJSONObject("chapter").getJSONArray("contents")
            for (index in 0 until contents.length()) {
                val content = contents.getJSONObject(index)
                content.put(
                    "url",
                    android.net.Uri.parse(content.getString("url")).buildUpon()
                        .appendQueryParameter("art_fixture", identity).build().toString(),
                )
            }
            return root.toString().toByteArray(Charsets.UTF_8)
        }
        error("Unique query fixture requires the original Copy contents URL shape")
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).use { it.readBytes() }

    private fun seed() {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath("tachiyomi.db").path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            val manga = ContentValues().apply {
                put("_id", input.getLong("mangaId"))
                put("source", input.getLong("sourceId"))
                put("url", input.getString("mangaUrl"))
                put("title", input.getString("mangaTitle"))
                put("status", 0)
                put("favorite", 0)
                put("initialized", 1)
                put("viewer", 0)
                put("chapter_flags", 0)
                put("cover_last_modified", 0)
                put("date_added", 0)
            }
            database.insertWithOnConflict("mangas", null, manga, SQLiteDatabase.CONFLICT_REPLACE)
            val chapter = ContentValues().apply {
                put("_id", input.getLong("chapterId"))
                put("manga_id", input.getLong("mangaId"))
                put("url", input.getString("chapterUrl"))
                put("name", input.getString("chapterName"))
                put("read", 0)
                put("bookmark", 0)
                put("last_page_read", 0)
                put("chapter_number", 1.0)
                put("source_order", 0)
                put("date_fetch", 0)
                put("date_upload", 0)
            }
            database.insertWithOnConflict("chapters", null, chapter, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun launch(initialPage: Int = 0) {
        finish()
        val monitor = instrumentation.addMonitor(ReaderActivity::class.java.name, null, false)
        try {
            context.startActivity(
                Intent().apply {
                    setClassName(context.packageName, "eu.kanade.tachiyomi.ui.reader.ReaderActivity")
                    putExtra("manga", input.getLong("mangaId"))
                    putExtra("chapter", input.getLong("chapterId"))
                    putExtra("resume", false)
                    putExtra("resumeWithinChapter", false)
                    // Existing Android SavedState input fixes the initial logical page, never final grouping.
                    putExtra("page_index", initialPage)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
            activity = requireNotNull(monitor.waitForActivityWithTimeout(30_000)) {
                "The real reader Activity must enter its lifecycle"
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    fun currentImages(): List<View> {
        var result = emptyList<View>()
        instrumentation.runOnMainSync {
            result = views(requireNotNull(activity).window.decorView).filter {
                ReaderPageImageView::class.java.isInstance(it) && it.isShown &&
                    it.getGlobalVisibleRect(Rect()) && it.width > 0 && it.height > 0
            }
        }
        return result
    }

    fun nativePresentationState(): String {
        var state = ""
        onReaderMain { activity ->
            val viewer = activity.viewModel.state.value.viewer as?
                eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPageR2LPagerViewer
            if (cacheDiagnostic) {
                val loader = viewer?.adapter?.currentChapter?.pageLoader
                if (loader is eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader) {
                    val actual = loader.javaClass.getDeclaredField("chapterCache")
                        .apply { isAccessible = true }.get(loader)
                    check(actual === diagnosticCache) { "Cache observer must use the actual loader cache" }
                }
            }
            val holders = views(activity.window.decorView)
                .filterIsInstance<eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPagerPageHolder>()
            state = "selected=${viewer?.pager?.currentItem} " + holders.joinToString { holder ->
                val presentation = holder.presentationScope?.let { (owner, ticket) ->
                    "target=${owner.location?.pageId?.sourcePageIndex} seq=${owner.location?.sequence}" +
                        " ticketSeq=${ticket.locationSequence} frame=${owner.frame(ticket)}"
                }
                val children = views(
                    holder,
                ).filterIsInstance<com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView>()
                    .joinToString { "${it.sWidth}x${it.sHeight}/ready=${it.isReady}/base=${it.isImageLoaded}" }
                val errors = holder.javaClass.getDeclaredField("errorPages").apply { isAccessible = true }
                    .get(holder) as Map<*, *>
                val statuses = holder.displayPage.visiblePages.joinToString { page ->
                    val cause = (page.status as? eu.kanade.tachiyomi.source.model.Page.State.Error)?.error
                    "${page.index}:${page.status}/${cause?.stackTraceToString()}"
                }
                val messages = views(holder).filterIsInstance<android.widget.TextView>()
                    .filter { it.id == eu.kanade.tachiyomi.R.id.error_message && it.isShown }
                    .map { it.text.toString() }
                "unit=${holder.displayPage.visiblePages.map { it.index }} $presentation native=[$children]" +
                    " statuses=[$statuses] errors=${errors.mapValues { (_, value) -> (value as ReaderPage).index }}" +
                    " messages=$messages normal=${viewer?.adapter?.currentChapter?.pageLoader?.hasForegroundWork}" +
                    " nativeBusy=${ReaderPageImageView.foregroundWork.value}" +
                    " optionalBusy=" +
                    eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value
            }
        }
        return state
    }

    data class SlotObservation(
        val content: Rect,
        val imageBounds: Rect,
        val mount: View,
        val container: View,
        val scaleX: Float,
        val scaleY: Float,
        val translationX: Float,
        val translationY: Float,
    )

    /** PixelCopy and actual public View identities; no presentation or progress callbacks are manufactured. */
    fun observeSlot(color: Int): SlotObservation {
        val content = coloredBounds(listOf(color))
        check(!content.isEmpty)
        var observation: SlotObservation? = null
        instrumentation.runOnMainSync {
            val image = views(requireNotNull(activity).window.decorView)
                .filterIsInstance<ReaderPageImageView>().first { view ->
                    val transformed = Rect()
                    view.isShown && view.getGlobalVisibleRect(transformed) &&
                        transformed.contains(content.centerX(), content.centerY())
                }
            val transformed = Rect().also { check(image.getGlobalVisibleRect(it)) }
            val container = (image.parent as View).parent as View
            observation = SlotObservation(
                content,
                transformed,
                image,
                container,
                container.scaleX,
                container.scaleY,
                container.translationX,
                container.translationY,
            )
        }
        return requireNotNull(observation)
    }

    fun onReaderMain(action: (ReaderActivity) -> Unit) {
        instrumentation.runOnMainSync { action(activity as ReaderActivity) }
    }

    fun logicalPageIndex(): Int? {
        var index: Int? = null
        onReaderMain { activity ->
            val viewer = activity.viewModel.state.value.viewer as?
                eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPageR2LPagerViewer
            val chapter = activity.viewModel.state.value.viewerChapters?.currChapter
            if (viewer != null && chapter != null) {
                index = viewer.adapter.session(chapter)?.location?.pageId?.sourcePageIndex
            }
        }
        return index
    }

    fun touchWindow(action: Int, down: Long, time: Long, x: Float, y: Float) {
        instrumentation.runOnMainSync {
            MotionEvent.obtain(down, time, action, x, y, 0).let { event ->
                try {
                    requireNotNull(activity).window.decorView.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
        }
    }

    fun windowWidth(): Int {
        var width = 0
        instrumentation.runOnMainSync { width = requireNotNull(activity).window.decorView.width }
        return width
    }

    fun retryBounds(): Rect? = retryNode()?.let { Rect().also(it::getBoundsInScreen) }

    fun feedbackBounds(viewId: Int): Pair<Rect, Rect> {
        var bounds: Pair<Rect, Rect>? = null
        onReaderMain { activity ->
            val view = views(activity.window.decorView).first { it.id == viewId && it.isShown }
            val visible = Rect().also { check(view.getGlobalVisibleRect(it)) }
            val matrix = android.graphics.Matrix().also(view::transformMatrixToGlobal)
            val full = android.graphics.RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
            matrix.mapRect(full)
            bounds = Rect().also(full::roundOut) to visible
        }
        return requireNotNull(bounds)
    }

    fun loadingBounds(pageIndex: Int? = null): Rect? {
        var bounds: Rect? = null
        onReaderMain { activity ->
            val root = pageIndex?.let { index ->
                views(activity.window.decorView)
                    .filterIsInstance<eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPagerPageHolder>()
                    .first { holder -> holder.displayPage.visiblePages.any { it.index == index } }
            } ?: activity.window.decorView
            bounds = views(root)
                .filterIsInstance<eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator>()
                .mapNotNull { indicator -> Rect().takeIf { indicator.isShown && indicator.getGlobalVisibleRect(it) } }
                .firstOrNull()
        }
        return bounds
    }

    /** Observes the original loader and actual shared adapter classification, without publishing facts. */
    fun hasDecodedSpread(index: Int): Boolean {
        var decoded = false
        instrumentation.runOnMainSync {
            val state = (activity as? ReaderActivity)?.viewModel?.state?.value ?: return@runOnMainSync
            val chapter = state.viewerChapters?.currChapter ?: return@runOnMainSync
            val page = chapter.pages?.getOrNull(index) ?: return@runOnMainSync
            val viewer = state.viewer as? eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPageR2LPagerViewer
                ?: return@runOnMainSync
            val owner = viewer.adapter.session(chapter) ?: return@runOnMainSync
            decoded = page.status == eu.kanade.tachiyomi.source.model.Page.State.Ready &&
                owner.pageFact(owner.identity.pageIds[index])?.state == mihon.domain.reader.ReaderPageFactState.SPREAD
        }
        return decoded
    }

    fun progress(): Pair<Long, Long> = SQLiteDatabase.openDatabase(
        context.getDatabasePath("tachiyomi.db").path,
        null,
        SQLiteDatabase.OPEN_READONLY,
    ).use { database ->
        val id = input.getLong("chapterId").toString()
        val page = database.rawQuery("SELECT last_page_read FROM chapters WHERE _id=?", arrayOf(id)).use {
            check(it.moveToFirst())
            it.getLong(0)
        }
        val events = database.rawQuery("SELECT COUNT(*) FROM reading_events WHERE chapter_id=?", arrayOf(id)).use {
            check(it.moveToFirst())
            it.getLong(0)
        }
        page to events
    }

    fun frame(): Bitmap? {
        if (currentImages().isEmpty()) return null
        val latch = CountDownLatch(1)
        var result = PixelCopy.ERROR_SOURCE_NO_DATA
        lateinit var bitmap: Bitmap
        instrumentation.runOnMainSync {
            val window = requireNotNull(activity).window
            bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(window, bitmap, {
                result = it
                latch.countDown()
            }, Handler(Looper.getMainLooper()))
        }
        check(latch.await(5, TimeUnit.SECONDS)) { "Actual reader window PixelCopy timed out" }
        if (result != PixelCopy.SUCCESS) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    fun paintedColor(color: Int): Boolean {
        val bitmap = frame() ?: return false
        try {
            return closeColor(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2), color)
        } finally {
            bitmap.recycle()
        }
    }

    /** Only the dedicated emulator's cached fixture bodies; never alter grouping or pairing records. */
    fun cold() {
        if (cacheDiagnostic) android.util.Log.i("ReaderCacheDiagnostic", "cold beforeFinish ${cacheDiagnosticState()}")
        finish()
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (ReaderPageMaterializeRunner.processPhysicalWorkCount.value != 0 ||
            ReaderPageImageView.foregroundWork.value != 0
        ) {
            check(SystemClock.uptimeMillis() < deadline) {
                "Original foreground work must finish before fixture cache reset: ${cacheDiagnosticState()}"
            }
            SystemClock.sleep(20)
        }
        if (cacheDiagnostic) android.util.Log.i("ReaderCacheDiagnostic", "cold beforeDelete ${cacheDiagnosticState()}")
        val directory = context.cacheDir.resolve("chapter_disk_cache")
        val currentKeys = directory.listFiles().orEmpty()
            .filter { it.extension == "0" }.map { it.nameWithoutExtension }.toSet()
        diagnosticCache.clear()
        check(directory.listFiles().orEmpty().none { it.extension == "0" }) {
            "Original ChapterCache.clear must remove the current fixture image files"
        }
        val remainingKeys = cacheJournalState().second
        check(currentKeys.none { it in remainingKeys }) {
            "Original ChapterCache.clear must remove current physical entries from the journal"
        }
        if (cacheDiagnostic) android.util.Log.i("ReaderCacheDiagnostic", "cold afterDelete ${cacheDiagnosticState()}")
    }

    fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (!condition()) {
            failure.get()?.let { throw AssertionError("Controlled HTTP boundary failed; ${failureEvidence()}", it) }
            if (SystemClock.uptimeMillis() >= deadline) {
                var mounted = emptyList<String>()
                instrumentation.runOnMainSync {
                    activity?.window?.decorView?.let { root ->
                        mounted = views(root).take(80).map {
                            "${it.javaClass.simpleName}:${it.width}x${it.height} shown=${it.isShown}"
                        }
                    }
                }
                throw AssertionError(
                    "$message; chapterRequests=${chapterRequests.get()} imageRequests=${imageRequests.get()} " +
                        "mounted=$mounted; ${nativePresentationState()}; ${failureEvidence()}",
                )
            }
            SystemClock.sleep(20)
        }
    }

    private fun byteIdentity(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
        return "bytes=${bytes.size} prefix=${bytes.take(12).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }} sha256=$digest"
    }

    /** Failure-only actual host cache bytes and public window geometry; never emits source URLs. */
    private fun failureEvidence(): String {
        val directory = requireNotNull(context.getExternalFilesDir("art-observations"))
            .resolve("${kind}_${SystemClock.uptimeMillis()}").also { check(it.mkdirs()) }
        directory.resolve(
            "requests-private.json",
        ).writeText(JSONArray(privateRequestPaths.toList()).toString(), Charsets.UTF_8)
        context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("reader-half-") && it.extension == "png" }
            .forEach { file -> file.copyTo(directory.resolve(file.name)) }
        val cached = context.cacheDir.resolve("chapter_disk_cache").listFiles().orEmpty()
            .filter { it.extension == "0" }.take(64).map { file ->
                val bytes = file.readBytes()
                directory.resolve(file.name).writeBytes(bytes)
                "${file.name}: ${byteIdentity(bytes)}"
            }
        val bitmap = frame()
        val colors = if (bitmap != null) {
            try {
                directory.resolve("window.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).map { color ->
                    colorBounds(bitmap, listOf(color))
                }
            } finally {
                bitmap.recycle()
            }
        } else {
            emptyList()
        }
        val report = "returned=${returnedBodies.toList()}; cached=$cached; colorBounds=$colors"
        directory.resolve("bytes-and-window.txt").writeText(report, Charsets.UTF_8)
        return report
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
    } else {
        emptyList()
    }

    fun finish() {
        activity?.let { previous ->
            instrumentation.runOnMainSync { previous.finish() }
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (!previous.isDestroyed) {
                check(SystemClock.uptimeMillis() < deadline) {
                    "Previous fixture Activity must finish before cache reset"
                }
                SystemClock.sleep(20)
            }
        }
        activity = null
    }

    override fun close() {
        try {
            finish()
        } finally {
            try {
                fields.zip(originals).forEach { (field, original) -> field.set(network, original) }
                check(clients().zip(originalClients).all { (actual, original) -> actual === original })
                val editor = preferences.edit()
                previousPreferences.forEach { (key, value) ->
                    when (value) {
                        is Int -> editor.putInt(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        null -> editor.remove(key)
                        else -> error("Unexpected reader preference type")
                    }
                }
                check(editor.commit())
            } finally {
                server.close()
                worker.join(2_000)
            }
        }
    }

    companion object {
        fun closeColor(pixel: Int, expected: Int) = listOf(0, 8, 16).all {
            kotlin.math.abs((pixel ushr it and 255) - (expected ushr it and 255)) < 35
        }
        fun png(color: Int): ByteArray = png(80, 60, color)

        fun png(width: Int, height: Int, color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                return ByteArrayOutputStream().also {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }
    }
}
