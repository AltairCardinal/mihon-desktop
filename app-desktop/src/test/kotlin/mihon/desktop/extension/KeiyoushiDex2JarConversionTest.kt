package mihon.desktop.extension

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import uy.kohesive.injekt.api.addSingleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 对 keiyoushi 全部 45 个中文扩展进行真实 dex2jar 转换测试。
 *
 * 由于已知全部都是 Android APK（DEX 格式），跳过类型检测，
 * 直接调用 ApkToJarConverter.convert() 并尝试 ServiceLoader 加载。
 *
 * 运行：./gradlew :app-desktop:jvmTest --tests "*.KeiyoushiDex2JarConversionTest" \
 *   -PincludeIntegrationTests=true -PincludeLiveNetworkTests=true -PincludeNetworkSurveyTests=true
 */
@Tag("integration")
class KeiyoushiDex2JarConversionTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val converter = ApkToJarConverter()

    @Serializable
    private data class ExtEntry(
        val name: String,
        val pkg: String,
        val apk: String,
        val lang: String,
        val code: Long,
        val version: String,
        val nsfw: Int = 0,
    )

    private data class ConvResult(
        val name: String,
        val pkg: String,
        val status: String,     // CONVERTED / CONVERT_FAIL / DOWNLOAD_FAIL
        val classesLoaded: Int = 0,
        val detail: String = "",
        val failReasons: List<String> = emptyList(),
    )

    @Test
    @Tag("live-network")
    @Tag("network-survey")
    fun `keiyoushi Chinese APK dex2jar conversion and source loading test`() = runBlocking {
        val indexUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/main/index.min.json"
        val repoBase = "https://raw.githubusercontent.com/keiyoushi/extensions/main"

        println("\n=== Keiyoushi 中文扩展 dex2jar 转换测试 ===\n")
        println("获取索引: $indexUrl")

        val response = client.newCall(GET(indexUrl)).execute()
        val allEntries = json.decodeFromString<List<ExtEntry>>(response.body.string())

        val chineseLangs = setOf("zh", "zh-Hans", "zh-Hant", "zh_Hans", "zh_Hant")
        val chinese = allEntries.filter { it.lang in chineseLangs }
        println("中文扩展总数: ${chinese.size}\n")

        val tmpDir = kotlin.io.path.createTempDirectory("keiyoushi-dex2jar").toFile()

        // 并发下载+转换（最多 6 并行）
        val results: List<ConvResult> = coroutineScope {
            chinese.chunked(6).flatMap { chunk ->
                chunk.map { ext ->
                    async(Dispatchers.IO) {
                        convertExtension(ext, repoBase, tmpDir)
                    }
                }.awaitAll()
            }
        }

        tmpDir.deleteRecursively()

        // 输出结果
        val converted   = results.filter { it.status == "CONVERTED" }
        val convFail    = results.filter { it.status == "CONVERT_FAIL" }
        val dlFail      = results.filter { it.status == "DOWNLOAD_FAIL" }
        val withSources = converted.filter { it.classesLoaded > 0 }
        val emptySrc    = converted.filter { it.classesLoaded == 0 }
        val total       = results.size

        println("\n" + "=".repeat(65))
        println("【dex2jar 转换结果详情】")
        println("-".repeat(65))
        results.forEach { r ->
            val icon = when (r.status) {
                "CONVERTED"     -> if (r.classesLoaded > 0) "✅" else "⚠️"
                "CONVERT_FAIL"  -> "❌"
                "DOWNLOAD_FAIL" -> "⚠️"
                else            -> "?"
            }
            val extra = when {
                r.classesLoaded > 0 -> " [${r.classesLoaded} sources]"
                r.detail.isNotEmpty() -> " — ${r.detail.take(60)}"
                else -> ""
            }
            println("$icon ${r.name.take(45).padEnd(45)}$extra")
        }

        // 输出加载失败原因（仅针对转换成功但 source 数为 0 的 APK）
        val lowSuccessRate = converted.filter { it.classesLoaded == 0 && it.failReasons.isNotEmpty() }
        if (lowSuccessRate.isNotEmpty()) {
            println()
            println("【转换成功但 Source 加载失败的前5条原因（每个扩展）】")
            println("-".repeat(65))
            lowSuccessRate.forEach { r ->
                println("  扩展: ${r.name}")
                r.failReasons.take(5).forEach { reason ->
                    println("    - $reason")
                }
            }
        }

        println("\n" + "=".repeat(65))
        println("【Keiyoushi 中文扩展转换统计汇总】")
        println("=".repeat(65))
        println("测试总数:                    $total")
        println("✅ 转换成功 + 能加载Source:  ${withSources.size}  (${pct(withSources.size, total)})")
        println("⚠️  转换成功但无Source:       ${emptySrc.size}  (${pct(emptySrc.size, total)})")
        println("❌ dex2jar 转换失败:         ${convFail.size}  (${pct(convFail.size, total)})")
        println("⚠️  下载失败:                ${dlFail.size}  (${pct(dlFail.size, total)})")
        println()
        println("【与 Suwayomi 对比】")
        println("-".repeat(65))
        println("Suwayomi（Android ABI 兼容层）: 理论支持 $total 个  ≈ 100%")
        println("Desktop（dex2jar 转换）: 可用 ${withSources.size} 个  ${pct(withSources.size, total)}")
        println("不可用（转换失败）: ${convFail.size} 个")
        if (convFail.isNotEmpty()) {
            println()
            println("转换失败的扩展（这些在 Suwayomi 可用但 Desktop 不可用）:")
            convFail.forEach { println("  ❌ ${it.name}  —  ${it.detail.take(80)}") }
        }
    }

    private fun convertExtension(ext: ExtEntry, repoBase: String, tmpDir: File): ConvResult {
        val name = ext.name.removePrefix("Tachiyomi: ").removePrefix("Mihon: ")
        val apkUrl = "$repoBase/apk/${ext.apk}"

        // 1. 下载 APK
        val data = try {
            client.newCall(GET(apkUrl)).execute().body.bytes()
        } catch (e: Exception) {
            return ConvResult(name, ext.pkg, "DOWNLOAD_FAIL", detail = e.message?.take(60) ?: "unknown")
        }

        val apkFile = File(tmpDir, "${ext.pkg}.apk")
        val extTmpDir = File(tmpDir, ext.pkg).also { it.mkdirs() }
        apkFile.writeBytes(data)

        // 2. dex2jar 转换
        val convertedJar = try {
            converter.convert(apkFile, extTmpDir)
        } catch (e: Exception) {
            apkFile.delete()
            extTmpDir.deleteRecursively()
            return ConvResult(name, ext.pkg, "CONVERT_FAIL",
                detail = "${e.javaClass.simpleName}: ${e.message?.take(60)}")
        }
        apkFile.delete()

        if (convertedJar == null) {
            extTmpDir.deleteRecursively()
            return ConvResult(name, ext.pkg, "CONVERT_FAIL", detail = "convert() returned null")
        }

        // 3. 尝试加载 Source
        val (sourcesLoaded, failReasons) = tryLoadSources(convertedJar)
        extTmpDir.deleteRecursively()
        return ConvResult(name, ext.pkg, "CONVERTED", classesLoaded = maxOf(0, sourcesLoaded), failReasons = failReasons)
    }

    private fun tryLoadSources(jarFile: File): Pair<Int, List<String>> {
        // 注册最小 DI 防止 Injekt.get() 崩溃
        try {
            val minClient = OkHttpClient()
            uy.kohesive.injekt.Injekt.addSingleton(minClient)
            uy.kohesive.injekt.Injekt.addSingleton(NetworkHelper(minClient))
            // Note: addSingleton extension function requires import uy.kohesive.injekt.api.addSingleton
        } catch (_: Exception) {}

        val failReasons = mutableListOf<String>()
        var successCount = 0

        try {
            val cl = ExtensionClassLoader(jarFile.toURI().toURL(), javaClass.classLoader)
            // 扫描 JAR 中所有候选类名
            val jar = java.util.jar.JarFile(jarFile)
            val classNames = jar.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") }
                .map { it.name.removeSuffix(".class").replace('/', '.') }
                .filter { !it.contains("$") }
                .toList()
            jar.close()

            for (className in classNames) {
                try {
                    val clazz = cl.loadClass(className)
                    if (Source::class.java.isAssignableFrom(clazz) &&
                        !java.lang.reflect.Modifier.isAbstract(clazz.modifiers) &&
                        !clazz.isInterface
                    ) {
                        successCount++
                    }
                } catch (e: Throwable) {
                    failReasons.add("$className: ${e.javaClass.simpleName}: ${e.message?.take(80) ?: "null"}")
                }
            }
        } catch (e: Throwable) {
            failReasons.add("JAR_LOAD: ${e.javaClass.simpleName}: ${e.message?.take(80) ?: "null"}")
        }

        return Pair(successCount, failReasons)
    }

    private fun pct(n: Int, total: Int) =
        if (total == 0) "N/A" else "%.1f%%".format(n.toDouble() / total * 100)
}
