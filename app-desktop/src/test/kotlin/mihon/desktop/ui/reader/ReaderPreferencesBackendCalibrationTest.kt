package mihon.desktop.ui.reader

import mihon.desktop.platform.DesktopTestProfile
import mihon.desktop.platform.IsolatedDesktopPreferences
import mihon.desktop.platform.IsolatedDesktopPreferencesFactory
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.desktopReaderSessionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.awt.EventQueue
import java.io.File
import java.io.FileInputStream
import java.net.URLClassLoader
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.prefs.AbstractPreferences
import java.util.prefs.Preferences
import kotlin.system.measureNanoTime

/** End-to-end component calibration. Every backend probe runs in its own JVM. */
class ReaderPreferencesBackendCalibrationTest {
    @Test
    fun `archived reader preferences produce the same real initial and reread state on both backends`(
        @TempDir directory: File,
    ) {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "The registry backend exists only on Windows")
        val timed = listOf("file", "registry", "registry", "file").mapIndexed { index, backend ->
            runProbe(directory, backend, "timed-$index", instrumentReads = false)
        }
        val counted = listOf("file", "registry").map { backend ->
            runProbe(directory, backend, "counted-$backend", instrumentReads = true)
        }

        assertEquals(4, timed.size)
        assertEquals(2, counted.size)
        (timed + counted).forEach { result ->
            assertEquals("906004", result.getProperty("chapterId"))
            assertEquals("187", result.getProperty("pageCount"))
            assertEquals("4", result.getProperty("currentPage"))
            assertEquals("true", result.getProperty("rereadAccepted"))
            assertEquals("true", result.getProperty("sameReaderState"))
            assertEquals("RTL", result.getProperty("readingMode"))
            assertEquals("false", result.getProperty("dualPageMode"))
            assertEquals("false", result.getProperty("autoSplitPages"))
            assertEquals("false", result.getProperty("autoSpreadMatching"))
            assertEquals("false", result.getProperty("cropBordersPager"))
            assertEquals("128", result.getProperty("filterAlpha"))
            assertEquals("17", result.getProperty("seededKeys"))
            assertTrue(result.getProperty("processPid").isNotBlank())
            assertTrue(result.getProperty("parentPid").isNotBlank())
            assertTrue(result.getProperty("parentPid") != "unknown")
            assertEquals("true", result.getProperty("headless"))
        }
        assertEquals(6, (timed + counted).map { it.getProperty("processPid") }.toSet().size)
        assertTrue(timed.all { it.getProperty("measurementMode") == "timed" })
        assertTrue(counted.all { it.getProperty("measurementMode") == "counted" })
        (timed + counted).forEach { result ->
            assertEquals(timed.first().getProperty("stateSignature"), result.getProperty("stateSignature"))
        }
        assertEquals("IsolatedDesktopPreferences", timed.first().getProperty("rootType"))
        assertEquals(IsolatedDesktopPreferencesFactory::class.java.name, timed.first().getProperty("factoryClassName"))
        assertTrue(timed[1].getProperty("rootType").contains("WindowsPreferences")) {
            "Expected the default Windows JDK registry backend, got ${timed[1].getProperty("rootType")}"
        }
        assertTrue(timed[1].getProperty("factoryClassName").contains("WindowsPreferencesFactory"))
        assertEquals("17", timed.first().getProperty("seededKeys"))
        (timed + counted).forEach { result ->
            assertFalse(result.getProperty("rootNode").startsWith("/mihon/"))
            assertTrue(result.getProperty("cleanup", "false").toBoolean())
        }
        counted.forEach { result ->
            listOf(
                "initialStateThreads",
                "initialRepeatThreads",
                "rereadThreads",
                "rereadRepeatThreads",
            ).forEach { key ->
                assertTrue(result.getProperty(key).startsWith("AWT-EventQueue-")) {
                    "$key did not execute on AWT EventQueue: ${result.getProperty(key)}"
                }
            }
            assertTrue(result.getProperty("constructorReads").toInt() > 0)
            assertTrue(result.getProperty("initialStateReads").toInt() > 0)
            assertTrue(result.getProperty("initialRepeatReads").toInt() > 0)
            assertTrue(result.getProperty("rereadReads").toInt() > 0)
            assertTrue(result.getProperty("rereadRepeatReads").toInt() > 0)
            assertEquals("main", result.getProperty("constructorThreads"))
            assertTrue(result.getProperty("initialStateThreads").isNotBlank())
            assertTrue(result.getProperty("rereadThreads").isNotBlank())
            assertCountMatches(result, "constructor")
            assertCountMatches(result, "initialState")
            assertCountMatches(result, "reread")
            assertCountMatches(result, "initialRepeat")
            assertCountMatches(result, "rereadRepeat")
        }

        timed.forEachIndexed { index, result ->
            println(
                "P0_READER_PREF backend=${result.getProperty(
                    "backend",
                )} order=${index + 1} pid=${result.getProperty("processPid")} " +
                    "parent=${result.getProperty("parentPid")} jvm=${result.getProperty("javaVersion")} " +
                    "vendor=${result.getProperty("javaVendor")} os=${result.getProperty("osName")} " +
                    "headless=${result.getProperty("headless")} profile=${result.getProperty("profilePath")} " +
                    "node=${result.getProperty("rootNode")} mode=${result.getProperty("measurementMode")} " +
                    "root=${result.getProperty("rootClassName")} factory=${result.getProperty("factoryClassName")} " +
                    "constructorNs=${result.getProperty(
                        "constructorNs",
                    )} initialFirstNs=${result.getProperty("initialStateNs")} " +
                    "initialRepeatNs=${result.getProperty(
                        "initialRepeatNs",
                    )} rereadFirstNs=${result.getProperty("rereadNs")} " +
                    "rereadRepeatNs=${result.getProperty("rereadRepeatNs")} " +
                    "state=${result.getProperty("stateSignature")}",
            )
        }
        counted.forEach { result ->
            println(
                "P0_READER_PREF_COUNT backend=${result.getProperty(
                    "backend",
                )} pid=${result.getProperty("processPid")} " +
                    "parent=${result.getProperty("parentPid")} jvm=${result.getProperty("javaVersion")} " +
                    "vendor=${result.getProperty("javaVendor")} os=${result.getProperty("osName")} " +
                    "headless=${result.getProperty("headless")} profile=${result.getProperty("profilePath")} " +
                    "node=${result.getProperty("rootNode")} mode=${result.getProperty("measurementMode")} " +
                    "root=${result.getProperty("rootClassName")} " +
                    "constructorReads=${result.getProperty("constructorReads")} " +
                    "initialStateReads=${result.getProperty(
                        "initialStateReads",
                    )} initialRepeatReads=${result.getProperty("initialRepeatReads")} " +
                    "rereadReads=${result.getProperty(
                        "rereadReads",
                    )} rereadRepeatReads=${result.getProperty("rereadRepeatReads")} " +
                    "constructorByKey=${result.getProperty("constructorByKey")} " +
                    "initialByKey=${result.getProperty(
                        "initialStateByKey",
                    )} rereadByKey=${result.getProperty("rereadByKey")} " +
                    "threads=${result.getProperty("initialStateThreads")}/${result.getProperty("rereadThreads")}",
            )
        }
    }

    private fun assertCountMatches(result: Properties, prefix: String) {
        val entries = result.getProperty("${prefix}ByKey").split(',').filter(String::isNotBlank)
        assertEquals(result.getProperty("${prefix}Reads").toInt(), entries.sumOf { it.substringAfter('=').toInt() })
        assertTrue(entries.all { it.startsWith("store:") || it.startsWith("legacy:") })
        assertTrue(entries.any { it.startsWith("store:") })
    }

    private fun runProbe(directory: File, backend: String, label: String, instrumentReads: Boolean): Properties {
        val resultFile = File(directory, "$label.properties")
        val argsFile = File(directory, "$label.args")
        val outputFile = File(directory, "$label.log")
        val classpath = generateSequence(javaClass.classLoader) { it.parent }
            .filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }
            .map { File(it.toURI()).absolutePath }
            .plus(System.getProperty("java.class.path").split(File.pathSeparator))
            .distinct().joinToString(File.pathSeparator)
        fun quote(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        val profile = File(directory, "$label-profile")
        val options = if (backend == "file") {
            listOf(
                "-Djava.util.prefs.PreferencesFactory=${IsolatedDesktopPreferencesFactory::class.java.name}",
                "-Duser.home=${File(directory, "$label-home").absolutePath}",
                "-Djava.awt.headless=true",
            )
        } else {
            listOf("-Djava.awt.headless=true")
        }
        argsFile.writeText(
            (
                options + listOf(
                    "-cp",
                    classpath,
                    ReaderPreferencesBackendCalibrationProbe::class.java.name,
                    backend,
                    profile.absolutePath,
                    resultFile.absolutePath,
                    instrumentReads.toString(),
                )
                ).joinToString("\n", transform = ::quote),
            Charsets.UTF_8,
        )
        val java = File(System.getProperty("java.home"), "bin/java${if (File.separatorChar == '\\') ".exe" else ""}")
        val process = ProcessBuilder(java.absolutePath, "@${argsFile.absolutePath}")
            .redirectErrorStream(true).redirectOutput(outputFile).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Reader preferences $backend probe timed out")
            assertEquals(0, process.exitValue(), outputFile.readText(Charsets.UTF_8))
            assertTrue(resultFile.isFile, "Probe did not write result: ${outputFile.readText(Charsets.UTF_8)}")
            return Properties().also { properties -> FileInputStream(resultFile).use(properties::load) }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

/** Runs after Preferences has selected its backend; this type must never call the ordinary /mihon node. */
object ReaderPreferencesBackendCalibrationProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val backend = args[0]
        val profile = File(args[1]).canonicalFile
        val resultFile = File(args[2])
        val instrumentReads = args[3].toBoolean()
        val unique = "/mihon-reader-p0-probe-${UUID.randomUUID()}"
        var root: Preferences? = null
        var result: Properties? = null
        var failure: Throwable? = null
        try {
            if (backend == "file") {
                DesktopTestProfile.configure(arrayOf("--test-mode", "--test-profile=${profile.path}"))
            } else {
                check(System.getProperty("os.name").startsWith("Windows")) { "Registry calibration requires Windows" }
                check(DesktopTestProfile.root == null)
            }
            val preferenceRoot = Preferences.userRoot()
            root = preferenceRoot
            check(
                if (backend ==
                    "file"
                ) {
                    preferenceRoot is IsolatedDesktopPreferences
                } else {
                    preferenceRoot !is IsolatedDesktopPreferences
                },
            )
            result = runCalibration(preferenceRoot, unique, profile, backend, instrumentReads)
        } catch (error: Throwable) {
            failure = error
        } finally {
            val cleanupErrors = mutableListOf<String>()
            root?.let { preferencesRoot ->
                runCatching {
                    preferencesRoot.node(unique).removeNode()
                    preferencesRoot.flush()
                }
                    .onFailure { cleanupErrors += "removeNode=${it.javaClass.simpleName}:${it.message}" }
                val nodeRemoved = runCatching { !preferencesRoot.nodeExists(unique) }
                    .onFailure { cleanupErrors += "nodeExists=${it.javaClass.simpleName}:${it.message}" }
                    .getOrDefault(false)
                if (!nodeRemoved) cleanupErrors += "privatePreferenceNodeStillExists"
            }
            if (backend == "file") {
                runCatching { profile.deleteRecursively() }
                    .onFailure { cleanupErrors += "profileDelete=${it.javaClass.simpleName}:${it.message}" }
                if (profile.exists()) {
                    cleanupErrors +=
                        "privateProfileStillExists:${profile.walkTopDown().take(8).map {
                            it.relativeTo(profile).path
                        }.toList()}"
                }
            }
            if (cleanupErrors.isNotEmpty()) {
                val cleanupFailure = IllegalStateException(
                    "Private calibration preferences/profile cleanup failed: ${cleanupErrors.joinToString("; ")}",
                )
                failure?.let(cleanupFailure::addSuppressed)
                throw cleanupFailure
            }
        }
        failure?.let { throw it }
        val completed = requireNotNull(result)
        val rootClassName = requireNotNull(root).javaClass.name
        completed.setProperty("rootClassName", rootClassName)
        completed.setProperty("cleanup", "true")
        resultFile.writeText(
            completed.stringPropertyNames().sorted().joinToString("\n") { "$it=${completed.getProperty(it)}" },
            Charsets.UTF_8,
        )
    }

    private fun runCalibration(
        root: Preferences,
        unique: String,
        profile: File,
        backend: String,
        instrumentReads: Boolean,
    ): Properties {
        val rootClassName = root.javaClass.name
        val factoryClassName = if (backend == "file") {
            requireNotNull(System.getProperty("java.util.prefs.PreferencesFactory"))
        } else {
            "JDK-default (java.util.prefs.WindowsPreferencesFactory)"
        }
        check(backend != "registry" || rootClassName.contains("WindowsPreferences")) {
            "Expected the Windows registry backend, got $rootClassName"
        }
        val backing = root.node(unique)
        val legacyBacking = root.node("$unique/legacy")
        val fixture = Properties().apply {
            ReaderPreferencesBackendCalibrationProbe::class.java
                .getResourceAsStream("/reader-p0-calibration/FC-C-reread1-values.properties")
                .use { stream -> checkNotNull(stream) { "Archived profile fixture is missing" }.use(::load) }
        }
        fixture.stringPropertyNames().forEach { key -> backing.put(key, fixture.getProperty(key)) }
        check(fixture.size == 17) { "Expected all 17 archived persisted settings, found ${fixture.size}" }

        val counter = if (instrumentReads) PreferenceReadCounter() else null
        val storePreferences = counter?.let { CountingPreferences(null, "", "store", backing, it) } ?: backing
        val legacyPreferences =
            counter?.let { CountingPreferences(null, "", "legacy", legacyBacking, it) } ?: legacyBacking
        counter?.clear()
        val store = DesktopPreferenceStore(storePreferences)
        lateinit var preferences: ReaderPreferences
        val constructorNs = measureNanoTime { preferences = ReaderPreferences(store, legacyPreferences) }
        val constructorReads = counter?.snapshot() ?: ReadSnapshot.Empty
        val reader = desktopReaderSessionState(chapterId = 906004L, generation = 1L, pageCount = 187, initialPage = 4)
            .let { state -> state.copy(context = state.context.copy(isDownloaded = true)) }

        val initial = onAwt(counter) { initialReaderState(preferences, reader, false, 0L, null, false) }
        val reread = onAwt(counter) {
            requireNotNull(rereadFirstCompositionState(initial.value, reader, preferences, false, 0L, null)) {
                "Existing reread settings check rejected the unchanged production state"
            }
        }
        val initialRepeat = onAwt(counter) { initialReaderState(preferences, reader, false, 0L, null, false) }
        val rereadRepeat = onAwt(counter) {
            requireNotNull(rereadFirstCompositionState(initialRepeat.value, reader, preferences, false, 0L, null)) {
                "Repeated reread settings check rejected the unchanged production state"
            }
        }
        check(
            initial.value == initialRepeat.value && reread.value == rereadRepeat.value && initial.value == reread.value,
        )

        return Properties().apply {
            setProperty("backend", backend)
            setProperty("factoryClassName", factoryClassName)
            setProperty("rootType", root.javaClass.simpleName)
            setProperty("rootClassName", rootClassName)
            setProperty("rootNode", unique)
            setProperty("processPid", ProcessHandle.current().pid().toString())
            setProperty("parentPid", ProcessHandle.current().parent().map { it.pid().toString() }.orElse("unknown"))
            setProperty("javaVersion", System.getProperty("java.version"))
            setProperty("javaVendor", System.getProperty("java.vendor"))
            setProperty("osName", System.getProperty("os.name"))
            setProperty("headless", System.getProperty("java.awt.headless", "false"))
            setProperty("profilePath", if (backend == "file") profile.absolutePath else "registry-private-node")
            setProperty("measurementMode", if (instrumentReads) "counted" else "timed")
            setProperty("seededKeys", fixture.size.toString())
            setProperty("fixtureKeyNames", fixture.stringPropertyNames().sorted().joinToString(","))
            setProperty("chapterId", initial.value.context.chapterId.toString())
            setProperty("pageCount", initial.value.session.activeChapter.pages.size.toString())
            setProperty("currentPage", initial.value.currentPage.toString())
            setProperty("readingMode", initial.value.readingMode.name)
            setProperty("dualPageMode", initial.value.dualPageMode.toString())
            setProperty("autoSplitPages", initial.value.autoSplitPages.toString())
            setProperty("autoSpreadMatching", initial.value.autoSpreadMatching.toString())
            setProperty("cropBordersPager", initial.value.cropBordersPager.toString())
            setProperty("filterAlpha", initial.value.colorFilter.alpha.toString())
            setProperty("rereadAccepted", (initial.value == reread.value).toString())
            setProperty(
                "sameReaderState",
                (initial.value == initialRepeat.value && reread.value == rereadRepeat.value).toString(),
            )
            setProperty("stateSignature", stateSignature(initial.value))
            setProperty("constructorNs", constructorNs.toString())
            setProperty("constructorReads", constructorReads.count.toString())
            setProperty("constructorThreads", constructorReads.threads)
            setProperty("constructorByKey", constructorReads.byKey)
            initial.putInto(this, "initialState")
            reread.putInto(this, "reread")
            initialRepeat.putInto(this, "initialRepeat")
            rereadRepeat.putInto(this, "rereadRepeat")
        }
    }

    private fun stateSignature(state: ReaderState): String = listOf(
        state.context.chapterId, state.context.initialPage, state.currentPage, state.readingMode,
        state.automaticLayout, state.dualPageMode, state.autoSplitPages, state.autoSpreadMatching,
        state.backgroundTheme, state.navigationMode, state.cropBordersPager, state.cropBordersWebtoon,
        state.scaleType, state.colorFilter, state.skipReadChapters, state.skipFilteredChapters,
        state.skipDuplicateChapters,
    ).joinToString("|")

    private fun <T : Any> onAwt(counter: PreferenceReadCounter?, action: () -> T): CalibrationPhase<T> {
        var phase: CalibrationPhase<T>? = null
        EventQueue.invokeAndWait {
            counter?.clear()
            lateinit var value: T
            val duration = measureNanoTime { value = action() }
            phase = CalibrationPhase(value, duration, counter?.snapshot() ?: ReadSnapshot.Empty)
        }
        return requireNotNull(phase)
    }
}

private class PreferenceReadCounter {
    private val keys = mutableMapOf<String, Int>()
    private val threads = mutableSetOf<String>()

    @Synchronized fun record(source: String, key: String) {
        keys["$source:$key"] = (keys["$source:$key"] ?: 0) + 1
        threads += Thread.currentThread().name
    }

    @Synchronized fun clear() {
        keys.clear()
        threads.clear()
    }

    @Synchronized fun snapshot() = ReadSnapshot(
        count = keys.values.sum(),
        threads = threads.sorted().joinToString(","),
        byKey = keys.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" },
    )
}

private data class ReadSnapshot(val count: Int, val threads: String, val byKey: String) {
    companion object {
        val Empty = ReadSnapshot(0, "", "")
    }
}

private data class CalibrationPhase<T>(val value: T, val durationNs: Long, val reads: ReadSnapshot) {
    fun putInto(properties: Properties, prefix: String) {
        properties.setProperty("${prefix}Ns", durationNs.toString())
        properties.setProperty("${prefix}Reads", reads.count.toString())
        properties.setProperty("${prefix}Threads", reads.threads)
        properties.setProperty("${prefix}ByKey", reads.byKey)
    }
}

private class CountingPreferences(
    parent: AbstractPreferences?,
    private val nodeName: String,
    private val source: String,
    private val delegate: Preferences,
    private val counter: PreferenceReadCounter,
) : AbstractPreferences(parent, nodeName) {
    override fun putSpi(key: String, value: String) = delegate.put(key, value)
    override fun getSpi(key: String): String? {
        counter.record(source, key)
        return delegate.get(key, null)
    }
    override fun removeSpi(key: String) = delegate.remove(key)
    override fun keysSpi(): Array<String> = delegate.keys()
    override fun childrenNamesSpi(): Array<String> = delegate.childrenNames()
    override fun childSpi(
        name: String,
    ): AbstractPreferences = CountingPreferences(this, name, source, delegate.node(name), counter)
    override fun removeNodeSpi() = delegate.removeNode()
    override fun syncSpi() = delegate.sync()
    override fun flushSpi() = delegate.flush()
}
