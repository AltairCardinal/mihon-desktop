package mihon.desktop.extension

import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mihon.desktop.di.initDesktopDIForTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import tachiyomi.core.common.preference.DesktopPreferenceStore

/**
 * 对 keiyoushi 仓库中的中文扩展进行实际下载、转换和加载测试，
 * 统计能正确工作的数量，并对比不同平台的可用状况。
 *
 * 运行方式：
 *   ./gradlew :app-desktop:jvmTest --tests "*.KeiyoushiChineseCompatibilityTest"
 *
 * 注意：此测试会进行真实网络请求，耗时较长。
 */
@Tag("integration")
class KeiyoushiChineseCompatibilityTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class ExtEntry(
        val name: String,
        val pkg: String,
        val apk: String,
        val lang: String,
        val code: Long,
        val version: String,
        val nsfw: Int = 0,
        val sources: List<ExtSource>? = null,
    )

    @Serializable
    private data class ExtSource(
        val id: Long = 0L,
        val lang: String = "",
        val name: String = "",
        val baseUrl: String = "",
    )

    @Serializable
    private data class GitHubContent(
        val content: String,
        val encoding: String,
    )

    private enum class Status {
        JVM_JAR,         // 已经是 JVM JAR，直接可用
        CONVERTED_OK,    // APK→JAR 转换成功
        CONVERTED_FAIL,  // APK→JAR 转换失败
        ANDROID_ONLY,    // DEX 但转换器返回 null
        DOWNLOAD_FAIL,   // 下载失败
        FILTERED,        // lib 版本不支持
    }

    private data class ExtResult(
        val name: String,
        val pkg: String,
        val lang: String,
        val version: String,
        val status: Status,
        val detail: String = "",
        val sourcesLoaded: Int = 0,
    )

    private data class SourceLoadResult(
        val count: Int,
        val failure: String? = null,
    )

    @Test
    fun `pinned manhuagui fixture downloads converts and exposes Source`() = runBlocking {
        val indexUrl =
            "https://api.github.com/repos/keiyoushi/extensions/contents/index.min.json?ref=repo"
        val entry = fetchGitHubIndex(indexUrl).singleOrNull {
            it.pkg == PINNED_MANHUAGUI_PACKAGE && it.version == PINNED_MANHUAGUI_VERSION
        }
        assertTrue(
            entry != null,
            "Pinned fixture $PINNED_MANHUAGUI_PACKAGE@$PINNED_MANHUAGUI_VERSION is absent from upstream index",
        )
        val tempDir = kotlin.io.path.createTempDirectory("manhuagui-pin").toFile()
        val diContext = initDesktopDIForTest(
            appDir = File(tempDir, "app"),
            preferenceStore = DesktopPreferenceStore(),
        )
        try {
            val result = testExtension(
                requireNotNull(entry),
                "https://raw.githubusercontent.com/keiyoushi/extensions/repo",
                tempDir,
            )

            assertEquals(PINNED_MANHUAGUI_PACKAGE, result.pkg)
            assertEquals(PINNED_MANHUAGUI_VERSION, result.version)
            assertEquals(Status.CONVERTED_OK, result.status, result.detail)
            assertEquals(0, result.sourcesLoaded)
            assertTrue(
                result.detail.contains("android.app.Application"),
                "Expected the known Task 4 Application compat gap, got: ${result.detail}",
            )
        } finally {
            diContext.closeAndJoin()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `keiyoushi Chinese extension conversion compatibility survey`() = runBlocking {
        val indexUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/main/index.min.json"
        println("\n=== Keiyoushi 中文扩展兼容性测试 ===\n")
        println("正在获取扩展索引：$indexUrl")

        val allEntries = fetchIndex(indexUrl)
        println("索引总扩展数：${allEntries.size}")

        // 中文语言标签集合（keiyoushi 实际使用的格式）
        val chineseLangs = setOf("zh", "zh-Hans", "zh-Hant", "zh_Hans", "zh_Hant")
        val chineseEntries = allEntries.filter { it.lang in chineseLangs }
        println("中文扩展总数：${chineseEntries.size}")

        // 按 lib 版本过滤（1.2–1.5，与 DesktopExtensionApi 相同）
        val supported = chineseEntries.filter { it.extractLibVersion() in 1.2..1.5 }
        val filtered = chineseEntries.size - supported.size
        println("版本兼容（1.2-1.5）：${supported.size}  /  版本不兼容：$filtered\n")

        val tempDir = kotlin.io.path.createTempDirectory("keiyoushi-zh-test").toFile()
        println("临时目录：${tempDir.absolutePath}\n")

        // 并发下载（最多 8 个并行）
        val results: List<ExtResult> = coroutineScope {
            val repoBase = "https://raw.githubusercontent.com/keiyoushi/extensions/main"
            supported.chunked(8).flatMap { chunk ->
                chunk.map { ext ->
                    async(Dispatchers.IO) {
                        testExtension(ext, repoBase, tempDir)
                    }
                }.awaitAll()
            }
        }

        tempDir.deleteRecursively()

        // ── 按状态分组统计 ──
        val byStatus = results.groupBy { it.status }
        val jvmCount = byStatus[Status.JVM_JAR]?.size ?: 0
        val convOk = byStatus[Status.CONVERTED_OK]?.size ?: 0
        val convFail = byStatus[Status.CONVERTED_FAIL]?.size ?: 0
        val androidOnly = byStatus[Status.ANDROID_ONLY]?.size ?: 0
        val dlFail = byStatus[Status.DOWNLOAD_FAIL]?.size ?: 0
        val totalWorking = jvmCount + convOk
        val totalTested = results.size

        println("=" .repeat(60))
        println("扩展名".padEnd(50) + " 状态")
        println("-".repeat(60))
        results.sortedBy { it.status.name }.forEach { r ->
            val label = when (r.status) {
                Status.JVM_JAR       -> "✅ JVM直接可用"
                Status.CONVERTED_OK  -> "✅ APK转换成功"
                Status.CONVERTED_FAIL-> "❌ APK转换失败"
                Status.ANDROID_ONLY  -> "❌ 仅Android"
                Status.DOWNLOAD_FAIL -> "⚠️ 下载失败"
                Status.FILTERED      -> "—  版本过滤"
            }
            val srcInfo = if (r.sourcesLoaded > 0) " [${r.sourcesLoaded}个源]" else ""
            println("${r.name.take(46).padEnd(46)} $label$srcInfo")
            if (r.detail.isNotEmpty()) println("  └─ ${r.detail}")
        }

        println("\n" + "=".repeat(60))
        println("【最终统计】")
        println("=".repeat(60))
        println("测试总数：                   $totalTested")
        println("✅ 可用总数：                $totalWorking  (${pct(totalWorking, totalTested)})")
        println("   其中 JVM JAR：            $jvmCount")
        println("   其中 APK 转换成功：       $convOk")
        println("❌ 不可用总数：              ${totalTested - totalWorking - dlFail}")
        println("   其中 APK 转换失败：       $convFail")
        println("   其中 Android-only：       $androidOnly")
        println("⚠️  下载失败（跳过）：        $dlFail")
        println("—  lib版本不兼容（过滤）：   $filtered")
        println()
        println("【与 Suwayomi 的对比说明】")
        println("-".repeat(60))
        println("Suwayomi 使用 Android ABI 兼容层（Shim），理论上支持几乎所有")
        println("Android APK 扩展（包括转换失败的 DEX 扩展）。")
        println("本 Desktop 实现使用 dex2jar 转换，支持率：${pct(totalWorking, totalTested)}")
        println("不支持的扩展通常是引用了 android.* 系统API（如 ContentResolver）")
        println("或依赖 R8/ProGuard 混淆后的内部类，这些无法被 dex2jar 还原。")
        println()

        // 打印无法安装的扩展列表（供参考）
        val failed = results.filter { it.status in setOf(Status.CONVERTED_FAIL, Status.ANDROID_ONLY) }
        if (failed.isNotEmpty()) {
            println("【无法转换的扩展列表（供参考）】")
            failed.forEach { println("  - ${it.name} (${it.pkg})  ${it.detail}") }
        }
    }

    private suspend fun testExtension(
        ext: ExtEntry,
        repoBase: String,
        tempDir: File,
    ): ExtResult {
        val apkUrl = "$repoBase/apk/${ext.apk}"
        val tmpFile = File(tempDir, "${ext.pkg}.tmp")

        // 1. 下载
        return try {
            val resp = client.newCall(GET(apkUrl)).execute()
            if (!resp.isSuccessful) {
                return ExtResult(ext.displayName(), ext.pkg, ext.lang, ext.version, Status.DOWNLOAD_FAIL,
                    "HTTP ${resp.code}")
            }
            resp.body.byteStream().use { input ->
                tmpFile.outputStream().use { out -> input.copyTo(out) }
            }

            // 2. 检测类型
            val (hasJvmClasses, hasDex) = inspectZip(tmpFile)

            when {
                hasJvmClasses -> {
                    // JVM JAR，直接尝试加载 Sources
                    val jarFile = File(tempDir, "${ext.pkg}.jar")
                    tmpFile.renameTo(jarFile)
                    val loadResult = tryLoadSources(jarFile)
                    jarFile.delete()
                    ExtResult(ext.displayName(), ext.pkg, ext.lang, ext.version,
                        Status.JVM_JAR, loadResult.failure ?: "直接加载 ${loadResult.count} 个源", loadResult.count)
                }
                hasDex -> {
                    // APK，尝试 dex2jar
                    val apkFile = File(tempDir, "${ext.pkg}.apk")
                    tmpFile.renameTo(apkFile)
                    val manifestClass = ManifestClassExtractor.extractFromApk(apkFile)
                    val converter = ApkToJarConverter()
                    val converted = converter.convert(apkFile, tempDir)
                    apkFile.delete()

                    if (converted == null) {
                        ExtResult(ext.displayName(), ext.pkg, ext.lang, ext.version,
                            Status.ANDROID_ONLY, "dex2jar 返回 null")
                    } else {
                        writeExtensionMeta(
                            converted,
                            ExtensionMeta(
                                pkgName = ext.pkg,
                                versionCode = ext.code,
                                versionName = ext.version,
                                source = ExtensionOrigin.CONVERTED_APK,
                                extensionClass = manifestClass,
                            ),
                        )
                        val loadResult = tryLoadSources(converted)
                        deleteExtensionMeta(converted)
                        converted.delete()
                        if (loadResult.count >= 0) {
                            ExtResult(ext.displayName(), ext.pkg, ext.lang, ext.version,
                                Status.CONVERTED_OK,
                                loadResult.failure ?: "转换后加载 ${loadResult.count} 个源",
                                loadResult.count)
                        } else {
                            ExtResult(ext.displayName(), ext.pkg, ext.lang, ext.version,
                                Status.CONVERTED_FAIL, "转换成功但加载失败")
                        }
                    }
                }
                else -> {
                    tmpFile.delete()
                    ExtResult(ext.displayName(), ext.pkg, ext.lang, ext.version,
                        Status.ANDROID_ONLY, "文件类型未知（无.class 无.dex）")
                }
            }
        } catch (e: Exception) {
            tmpFile.delete()
            ExtResult(ext.displayName(), ext.pkg, ext.lang, ext.version,
                Status.DOWNLOAD_FAIL, e.message?.take(80) ?: "unknown")
        }
    }

    /**
     * 尝试通过 production DesktopExtensionLoader 加载 Source 实例。
     * Loader 会先尝试 ServiceLoader，再使用 APK manifest sidecar 或扫描 fallback。
     * 返回成功加载的源数量，-1 表示加载过程发生异常。
     */
    private fun tryLoadSources(jarFile: File): SourceLoadResult {
        return try {
            val loaded = DesktopExtensionLoader(jarFile.parentFile).loadFromSingleJar(jarFile)
            if (loaded.isEmpty()) {
                val className = readExtensionMeta(jarFile)?.extensionClass
                if (className != null) {
                    val classLoader = ExtensionClassLoader(jarFile.toURI().toURL(), javaClass.classLoader)
                    try {
                        classLoader.loadClass(className).getDeclaredConstructor().newInstance()
                        return SourceLoadResult(0)
                    } catch (error: Throwable) {
                        val root = generateSequence(error) { it.cause }.last()
                        return SourceLoadResult(0, "${root::class.qualifiedName}: ${root.message}")
                    } finally {
                        classLoader.close()
                    }
                }
            }
            SourceLoadResult(loaded.size)
        } catch (error: Throwable) {
            val root = generateSequence(error) { it.cause }.last()
            SourceLoadResult(-1, "${root::class.qualifiedName}: ${root.message}")
        }
    }

    private fun inspectZip(file: File): Pair<Boolean, Boolean> {
        return try {
            ZipFile(file).use { zip ->
                var hasClasses = false
                var hasDex = false
                zip.entries().asSequence().forEach { entry ->
                    if (entry.name.endsWith(".class")) hasClasses = true
                    if (entry.name.matches(Regex("classes\\d*\\.dex"))) hasDex = true
                }
                Pair(hasClasses, hasDex)
            }
        } catch (_: Exception) {
            Pair(false, false)
        }
    }

    private fun fetchIndex(url: String): List<ExtEntry> {
        val resp = client.newCall(GET(url)).execute()
        val body = resp.body.string()
        return json.decodeFromString(body)
    }

    private fun fetchGitHubIndex(url: String): List<ExtEntry> {
        val response = client.newCall(GET(url)).execute()
        val metadata = json.decodeFromString<GitHubContent>(response.body.string())
        check(metadata.encoding == "base64") { "Unexpected GitHub content encoding ${metadata.encoding}" }
        val body = Base64.getMimeDecoder().decode(metadata.content).decodeToString()
        return json.decodeFromString(body)
    }

    private fun ExtEntry.extractLibVersion(): Double =
        version.substringBeforeLast('.').toDoubleOrNull() ?: 0.0

    private fun ExtEntry.displayName(): String =
        name.removePrefix("Tachiyomi: ").removePrefix("Mihon: ")

    private fun pct(n: Int, total: Int): String =
        if (total == 0) "N/A" else "%.1f%%".format(n.toDouble() / total * 100)

    private companion object {
        const val PINNED_MANHUAGUI_PACKAGE = "eu.kanade.tachiyomi.extension.zh.manhuagui"
        const val PINNED_MANHUAGUI_VERSION = "1.4.28"
    }
}
