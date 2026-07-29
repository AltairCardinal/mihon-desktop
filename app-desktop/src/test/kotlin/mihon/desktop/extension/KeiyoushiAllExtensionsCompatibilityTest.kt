package mihon.desktop.extension

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.desktop.BuildInfo
import mihon.desktop.di.initDesktopDIForTest
import mihon.domain.extensionrepo.model.ExtensionRepo
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import uy.kohesive.injekt.Injekt
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Downloads and loads every signed JVM artifact in the current Keiyoushi v2 index.
 *
 * The JAR cache and an in-progress report survive Gradle worker restarts. A report is only
 * resumed when it was produced by the same index, Git revision, and operating system.
 *
 * Run on Windows with:
 * `./gradlew.bat :app-desktop:jvmTest
 * --tests "mihon.desktop.extension.KeiyoushiAllExtensionsCompatibilityTest"
 * -PincludeIntegrationTests=true -PincludeLiveNetworkTests=true -PincludeNetworkSurveyTests=true`
 */
@Isolated
@Tag("integration")
@Tag("live-network")
@Tag("network-survey")
class KeiyoushiAllExtensionsCompatibilityTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Test
    fun `every current Keiyoushi extension loads on Windows`() = runBlocking {
        assertTrue(
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
            "This survey is Windows evidence and must run on Windows",
        )

        val surveyRoot = File(
            System.getProperty("mihon.keiyoushiSurveyDir")
                ?: File(System.getProperty("java.io.tmpdir"), "keiyoushi-all").absolutePath,
        ).apply { mkdirs() }
        val indexResponse = fetchIndex()
        val planned = KeiyoushiAllExtensionsSurvey.plan(indexResponse.entries)
        val reportFile = File(surveyRoot, "windows-jar-report.json")
        val resumed = readResumableResults(
            reportFile = reportFile,
            indexSha256 = indexResponse.sha256,
            expectedCount = planned.size,
        )
        val resultsById = resumed.associateByTo(linkedMapOf()) { it.artifactId }
        val pending = planned.filterNot { artifactId(it) in resultsById }

        println(
            "Keiyoushi Windows survey: ${planned.size} total, " +
                "${resultsById.size} resumed, ${pending.size} pending",
        )
        println("Cache/report directory: ${surveyRoot.absolutePath}")

        val downloads = downloadArtifacts(pending, File(surveyRoot, "jar"))
        val preferences = IsolatedDesktopPreferenceStore.create()
        val previousInjekt = Injekt
        val diContext = initDesktopDIForTest(
            appDir = File(surveyRoot, "app"),
            preferenceStore = preferences.store,
        )
        try {
            pending.forEachIndexed { index, entry ->
                val result = when (val download = downloads.getValue(artifactId(entry))) {
                    is DownloadResult.Failed -> KeiyoushiSurveyResult.failure(
                        entry = entry,
                        status = KeiyoushiSurveyStatus.DOWNLOAD_FAILED,
                        detail = download.detail,
                    )
                    is DownloadResult.Ready -> testArtifact(entry, download.jar, surveyRoot)
                }
                resultsById[result.artifactId] = result

                if ((index + 1) % REPORT_FLUSH_INTERVAL == 0 || index == pending.lastIndex) {
                    writeReport(
                        reportFile = reportFile,
                        indexSha256 = indexResponse.sha256,
                        planned = planned,
                        results = resultsById.values.toList(),
                    )
                    println(
                        "Processed ${index + 1}/${pending.size}; " +
                            "compatible=${resultsById.values.count { it.isCompatible }}",
                    )
                }
            }
        } finally {
            diContext.closeAndJoin()
            Injekt = previousInjekt
            preferences.close()
        }

        val orderedResults = planned.mapNotNull { resultsById[artifactId(it)] }
        writeReport(
            reportFile = reportFile,
            indexSha256 = indexResponse.sha256,
            planned = planned,
            results = orderedResults,
        )
        val incompatible = orderedResults.filterNot(KeiyoushiSurveyResult::isCompatible)

        assertTrue(
            KeiyoushiAllExtensionsSurvey.hasCompleteCoverage(planned, orderedResults),
            "Survey report does not account for every index artifact: ${reportFile.absolutePath}",
        )
        assertTrue(
            incompatible.isEmpty(),
            buildString {
                appendLine(
                    "${incompatible.size}/${planned.size} Keiyoushi artifacts failed Windows compatibility.",
                )
                appendLine("Full report: ${reportFile.absolutePath}")
                incompatible.take(50).forEach {
                    appendLine("${it.pkg} ${it.status}: ${it.detail}")
                }
            },
        )
    }

    private suspend fun downloadArtifacts(
        entries: List<KeiyoushiSurveyEntry>,
        cacheDirectory: File,
    ): Map<String, DownloadResult> {
        cacheDirectory.mkdirs()
        return coroutineScope {
            entries.chunked(DOWNLOAD_CONCURRENCY).flatMap { chunk ->
                chunk.map { entry ->
                    async(Dispatchers.IO) {
                        artifactId(entry) to downloadArtifact(entry, cacheDirectory)
                    }
                }.awaitAll()
            }.toMap()
        }
    }

    private fun downloadArtifact(
        entry: KeiyoushiSurveyEntry,
        cacheDirectory: File,
    ): DownloadResult {
        val identityHash = sha256(artifactId(entry).toByteArray()).take(16)
        val target = File(cacheDirectory, "$identityHash-${entry.artifactUrl.substringAfterLast('/')}")
        if (isReadableArchive(target)) {
            return DownloadResult.Ready(target)
        }
        target.delete()

        val url = entry.artifactUrl
        var lastFailure = "download did not run"
        repeat(NETWORK_ATTEMPTS) { attempt ->
            val partial = File(target.parentFile, "${target.name}.part")
            partial.delete()
            try {
                client.newCall(GET(url)).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}")
                    }
                    response.body.byteStream().use { input ->
                        partial.outputStream().buffered().use(input::copyTo)
                    }
                }
                if (!isReadableArchive(partial)) {
                    throw IOException("Downloaded artifact is not a readable JAR")
                }
                Files.move(
                    partial.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
                return DownloadResult.Ready(target)
            } catch (error: Throwable) {
                partial.delete()
                lastFailure = "attempt ${attempt + 1}/$NETWORK_ATTEMPTS: ${rootMessage(error)}"
            }
        }
        return DownloadResult.Failed("$url — $lastFailure")
    }

    private fun testArtifact(
        entry: KeiyoushiSurveyEntry,
        jar: File,
        surveyRoot: File,
    ): KeiyoushiSurveyResult {
        val workDirectory = File(surveyRoot, "work/${sha256(artifactId(entry).toByteArray()).take(16)}")
        workDirectory.deleteRecursively()
        workDirectory.mkdirs()
        try {
            try {
                DefaultDesktopArtifactAuthenticator.authenticate(
                    jar,
                    KEIYOUSHI_SIGNING_KEY,
                    isApk = false,
                )
            } catch (error: Throwable) {
                return KeiyoushiSurveyResult.failure(
                    entry,
                    KeiyoushiSurveyStatus.LOAD_FAILED,
                    rootMessage(error),
                )
            }
            val adaptedJar = File(workDirectory, "runtime.jar")
            val runtimeJar = if (DefaultJvmExtensionArtifactAdapter.adaptIfRequired(jar, adaptedJar)) {
                adaptedJar
            } else {
                jar
            }
            val loader = DesktopExtensionLoader(runtimeJar.parentFile)
            val loaded = try {
                loader.loadFromSingleJar(runtimeJar)
            } catch (error: Throwable) {
                return KeiyoushiSurveyResult.failure(
                    entry,
                    KeiyoushiSurveyStatus.LOAD_FAILED,
                    rootMessage(error),
                )
            }
            val result = try {
                if (loaded.isNotEmpty()) {
                    KeiyoushiSurveyResult.success(entry, loaded.size)
                } else {
                    val diagnostic = loader.diagnostics.lastOrNull()
                    KeiyoushiSurveyResult.failure(
                        entry,
                        KeiyoushiSurveyStatus.LOAD_FAILED,
                        diagnostic?.let { "${it.errorType}: ${it.message}" }
                            ?: diagnoseEmptyLoad(runtimeJar),
                    )
                }
            } finally {
                loaded.map(LoadedExtension::classLoader)
                    .distinct()
                    .filterIsInstance<AutoCloseable>()
                    .forEach { runCatching(it::close) }
            }
            retainFailedJarForDiagnosis(
                entry = entry,
                jar = runtimeJar,
                surveyRoot = surveyRoot,
                retain = !result.isCompatible,
            )
            return result
        } catch (error: Throwable) {
            return KeiyoushiSurveyResult.failure(
                entry,
                KeiyoushiSurveyStatus.LOAD_FAILED,
                rootMessage(error),
            )
        } finally {
            workDirectory.deleteRecursively()
        }
    }

    private fun fetchIndex(): IndexResponse {
        var lastFailure: Throwable? = null
        repeat(NETWORK_ATTEMPTS) {
            try {
                val bytes = client.newCall(GET(INDEX_URL)).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}")
                    }
                    response.body.bytes()
                }
                return IndexResponse(
                    entries = DesktopExtensionRepoV2Catalog.decode(bytes, KEIYOUSHI_REPOSITORY)
                        .map { catalogEntry ->
                            val artifact = catalogEntry.artifact
                            KeiyoushiSurveyEntry(
                                name = artifact.name,
                                pkg = artifact.packageName,
                                artifactUrl = artifact.downloadUrl,
                                lang = artifact.language,
                                code = artifact.versionCode,
                                version = artifact.versionName,
                                nsfw = if (artifact.isNsfw) 1 else 0,
                            )
                        },
                    sha256 = sha256(bytes),
                )
            } catch (error: Throwable) {
                lastFailure = error
            }
        }
        throw IOException("Unable to fetch $INDEX_URL after $NETWORK_ATTEMPTS attempts", lastFailure)
    }

    private fun retainFailedJarForDiagnosis(
        entry: KeiyoushiSurveyEntry,
        jar: File,
        surveyRoot: File,
        retain: Boolean,
    ) {
        val target = File(surveyRoot, "failed-jars/${entry.pkg}-${entry.code}.jar")
        if (retain) {
            target.parentFile.mkdirs()
            Files.copy(jar.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } else {
            target.delete()
        }
    }

    private fun diagnoseEmptyLoad(
        jar: File,
    ): String {
        ExtensionClassLoader(jar.toURI().toURL(), javaClass.classLoader).use { classLoader ->
            val classNames = ZipFile(jar).use { archive ->
                archive.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") && '$' !in it.name }
                    .map { it.name.removeSuffix(".class").replace('/', '.') }
                    .toList()
            }
            return classNames.mapNotNull { className ->
                val candidate = runCatching { classLoader.loadClass(className) }.getOrNull()
                    ?: return@mapNotNull null
                when {
                    Source::class.java.isAssignableFrom(candidate) &&
                        !candidate.isInterface &&
                        !java.lang.reflect.Modifier.isAbstract(candidate.modifiers) -> {
                        runCatching {
                            candidate.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                            "$className constructed as Source but production loader discarded it"
                        }.getOrElse { "$className -> ${rootMessage(it)}" }
                    }
                    SourceFactory::class.java.isAssignableFrom(candidate) &&
                        !candidate.isInterface &&
                        !java.lang.reflect.Modifier.isAbstract(candidate.modifiers) -> {
                        runCatching {
                            val factory = candidate.getDeclaredConstructor()
                                .apply { isAccessible = true }
                                .newInstance() as SourceFactory
                            val sources = factory.createSources()
                            "$className factory created ${sources.size} source(s) but production loader discarded them"
                        }.getOrElse { "$className -> ${rootMessage(it)}" }
                    }
                    else -> null
                }
            }.joinToString(separator = "; ")
                .ifBlank { "No concrete Source or SourceFactory could be instantiated" }
                .take(MAX_DIAGNOSTIC_LENGTH)
        }
    }

    private fun readResumableResults(
        reportFile: File,
        indexSha256: String,
        expectedCount: Int,
    ): List<KeiyoushiSurveyResult> {
        if (!reportFile.isFile) return emptyList()
        val report = runCatching {
            json.decodeFromString<KeiyoushiSurveyReport>(reportFile.readText(Charsets.UTF_8))
        }.getOrNull() ?: return emptyList()
        return report.results.filter(KeiyoushiSurveyResult::isCompatible).takeIf {
            report.schemaVersion == REPORT_SCHEMA_VERSION &&
                report.indexSha256 == indexSha256 &&
                report.testedGitHash == BuildInfo.GIT_HASH &&
                report.operatingSystem == System.getProperty("os.name") &&
                report.totalEntries == expectedCount
        } ?: emptyList()
    }

    private fun writeReport(
        reportFile: File,
        indexSha256: String,
        planned: List<KeiyoushiSurveyEntry>,
        results: List<KeiyoushiSurveyResult>,
    ) {
        val report = KeiyoushiSurveyReport(
            schemaVersion = REPORT_SCHEMA_VERSION,
            indexUrl = INDEX_URL,
            indexSha256 = indexSha256,
            testedGitHash = BuildInfo.GIT_HASH,
            operatingSystem = System.getProperty("os.name"),
            javaVersion = System.getProperty("java.version"),
            totalEntries = planned.size,
            results = results.sortedWith(compareBy({ it.lang }, { it.name }, { it.pkg })),
        )
        reportFile.parentFile.mkdirs()
        val temporary = File(reportFile.parentFile, "${reportFile.name}.tmp")
        temporary.writeText(json.encodeToString(report), Charsets.UTF_8)
        try {
            Files.move(
                temporary.toPath(),
                reportFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                reportFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun isReadableArchive(file: File): Boolean =
        file.isFile && file.length() > 0L && runCatching {
            ZipFile(file).use { archive -> archive.entries().hasMoreElements() }
        }.getOrDefault(false)

    private fun artifactId(entry: KeiyoushiSurveyEntry): String =
        "${entry.pkg}:${entry.code}:${entry.artifactUrl}"

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun rootMessage(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        val stack = root.stackTrace
            .take(MAX_DIAGNOSTIC_STACK_FRAMES)
            .joinToString(separator = "\n") { "  at $it" }
        return buildString {
            append("${root.javaClass.name}: ${root.message ?: root.javaClass.simpleName}")
            if (stack.isNotEmpty()) {
                append('\n')
                append(stack)
            }
        }
    }

    private data class IndexResponse(
        val entries: List<KeiyoushiSurveyEntry>,
        val sha256: String,
    )

    private sealed interface DownloadResult {
        data class Ready(val jar: File) : DownloadResult
        data class Failed(val detail: String) : DownloadResult
    }

    private companion object {
        const val INDEX_URL =
            "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
        const val KEIYOUSHI_SIGNING_KEY =
            "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
        val KEIYOUSHI_REPOSITORY = ExtensionRepo(
            baseUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo",
            name = "Keiyoushi",
            shortName = "KEI",
            website = "https://keiyoushi.github.io",
            signingKeyFingerprint = KEIYOUSHI_SIGNING_KEY,
        )
        const val DOWNLOAD_CONCURRENCY = 8
        const val NETWORK_ATTEMPTS = 2
        const val REPORT_FLUSH_INTERVAL = 10
        const val REPORT_SCHEMA_VERSION = 2
        const val MAX_DIAGNOSTIC_LENGTH = 2_000
        const val MAX_DIAGNOSTIC_STACK_FRAMES = 8
    }
}
