package mihon.desktop.ui.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.launchApplication
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.desktopWindowGeometryStore
import mihon.desktop.di.inMemoryDesktopPreferenceStore
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.platform.DesktopMainWindow
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.LocalSourceReader
import mihon.desktop.test.http.ProductionReaderBinding
import mihon.desktop.test.http.ProductionReaderTestModeBridge
import mihon.desktop.test.http.ReaderIoTestModeBridge
import mihon.desktop.test.http.ReaderTestModeController
import mihon.desktop.ui.browse.localReaderScreen
import mihon.domain.reader.ReaderChapterOpeningMode
import mihon.domain.reader.ReaderDualPageViewportTicket
import mihon.domain.reader.ReaderPairingCertification
import mihon.domain.reader.SharedDualPageSession
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.awt.event.WindowEvent
import java.io.File
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/** Real reader draw regressions against the frozen canonical layout; no performance experiment. */
@Isolated
@Tag("native-window")
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "mihon.cp1.vol08.pages", matches = ".+")
class DesktopReaderCanonicalNativeWindowTest {
    @TempDir
    lateinit var tempDir: File

    private var oracleLayouts: Map<Set<Int>, ReaderSmokeUnitLayout> = emptyMap()

    private val nativeUnhandled = CopyOnWriteArrayList<NativeUnhandledError>()
    private val nativePhase = AtomicReference("test setup")
    private var originalUncaughtHandler: Thread.UncaughtExceptionHandler? = null
    private val pendingNativeCleanupGroups = AtomicInteger()
    private var currentNativeTestMethod = "uninitialized"

    @BeforeEach
    fun observeOwnedNativeErrors(testInfo: TestInfo) {
        nativeUnhandled.clear()
        currentNativeTestMethod = testInfo.testMethod.orElseThrow().name
        nativePhase.set(testInfo.displayName + ":setup")
        val chained = Thread.getDefaultUncaughtExceptionHandler()
        originalUncaughtHandler = chained
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val record = NativeUnhandledError(thread.name, System.nanoTime(), nativePhase.get(), error)
            nativeUnhandled += record
            System.err.println(
                "CP1_NATIVE_UNHANDLED thread=${record.thread} phase=${record.phase} at=${record.atNanos}",
            )
            error.printStackTrace(System.err)
            chained?.uncaughtException(thread, error)
        }
    }

    @AfterEach
    fun verifyOwnedNativeErrors() {
        try {
            onEdt { Unit }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(originalUncaughtHandler)
        }
        val errors = nativeUnhandled.toList()
        assertEquals(0, pendingNativeCleanupGroups.get(), "All owned window, scope and DI cleanup must finish")
        assertTrue(
            errors.isEmpty(),
            "Owned native reader must not leave uncaught draw or observer errors: " +
                errors.joinToString { "${it.phase} @${it.atNanos}: ${it.error}" },
        )
    }

    @Test
    fun `fresh native T170 agrees with the complete oracle without locking a different pair`() = runBlocking {
        val pagesDirectory = File(requireNotNull(System.getProperty("mihon.cp1.vol08.pages")))
        val manifest = File(requireNotNull(System.getProperty("mihon.cp1.vol08.manifest")))
        val archive = File(requireNotNull(System.getProperty("mihon.cp1.vol08.archive")))
        val oracle = File(requireNotNull(System.getProperty("mihon.cp1.vol08.oracle")))
        verifyManifestInput(pagesDirectory, manifest)
        val units = verifyCanonicalOracle(oracle, archive, manifest)
        val session = openNativeSession("focused-t170", pagesDirectory)
        try {
            val row = measureLocationAction(
                session = session,
                sampleId = "focused-t170",
                scenario = "focused T170 correctness (not formal)",
                action = "ProductionReaderBinding.page(170)",
                expectedUnit = oracleUnitForPage(units, T170_INDEX + 1).expectedReaderPages,
            ) { session.binding.page(T170_INDEX) }
            // Diagnostic queries are deliberately after the bounded measurement, never warming the intent.
            val diagnostic = synchronized(session.lifecycleLock) {
                val owner = requireNotNull(session.ownerField.get(session.model) as? SharedDualPageSession)
                val target = owner.identity.pageIds[T170_INDEX]
                val historyBeforeQuery = owner.displayedHistory.filter { target in it.unit.pageIds }.map { frame ->
                    "layout=${readerSmokeUnitLayout(frame.unit).toJson()} " +
                        "locked=${frame.lockedPageIds.map { it.sourcePageIndex + 1 }} " +
                        "drawn=${frame.drawnPageIds.map { it.sourcePageIndex + 1 }}"
                }
                val proof = owner.certifyPairing(target)
                val certified = (proof as? ReaderPairingCertification.Certified)?.segment
                "location=${owner.location} proof=${proof::class.simpleName} " +
                    "certified=${certified?.unit?.let(::readerSmokeUnitLayout)?.toJson()} " +
                    "proofFirst=${certified?.firstIndex} " +
                    "canonical=${owner.canonicalTargetUnit?.let(::readerSmokeUnitLayout)?.toJson()} " +
                    "presentation=${owner.targetUnit?.let(::readerSmokeUnitLayout)?.toJson()} " +
                    "historyBeforeQuery=$historyBeforeQuery"
            }
            val expectedLayout = requireNotNull(row.expectedLayout)
            val expectedPages = expectedLayout.slots.map { it.sourcePageIndex }.toSet()
            val expectedTargetSlot = expectedLayout.slots.single { it.sourcePageIndex == T170_INDEX }
            val targetFrameReceipts = session.drawReceipts.filter {
                it.runtimeEpoch == session.runtimeEpoch &&
                    it.monotonicNanos >= requireNotNull(row.startedAtNanos) &&
                    T170_INDEX in it.unitPageIndexes
            }
            val targetDraws = targetFrameReceipts.filter { it.sourcePageIndex == T170_INDEX }
            assertTrue(targetDraws.isNotEmpty(), "T170 must have an actual accepted target draw")
            targetDraws.forEach { receipt ->
                assertEquals(
                    expectedTargetSlot,
                    requireNotNull(receipt.unitLayout).slots.single { it.sourcePageIndex == T170_INDEX },
                    "Every actual T170 draw must keep its oracle slot: $receipt",
                )
            }
            targetFrameReceipts.forEach { receipt ->
                assertTrue(
                    expectedPages.containsAll(receipt.drawnPageIndexes),
                    "A frame containing T170 must never draw a different partner: $receipt",
                )
            }
            println("CP1_FOCUSED_T170 " + row.toJsonLine() + " diagnostic=" + diagnostic)
            assertTrue(
                row.failure == null && row.completeUnitLatencyNanos != null,
                "The actual T170 frame must match the full oracle: ${row.failure}; $diagnostic",
            )
        } finally {
            session.close()
        }
        val reopened = captureTargetSample(
            sampleId = "focused-t170-reopen",
            scenario = "focused T170 reopen correctness (not formal)",
            targetIndex = T170_INDEX,
            expectedUnit = oracleUnitForPage(units, T170_INDEX + 1).expectedReaderPages,
            pagesDirectory = pagesDirectory,
            reopen = true,
        )
        println("CP1_FOCUSED_T170_REOPEN " + reopened.toJsonLine())
        assertTrue(
            reopened.failure == null && reopened.completeUnitLatencyNanos != null,
            "T170 priming and reopen must both reach the full oracle: $reopened",
        )
        assertEquals(reopened.expectedLayout, reopened.actualLayout)
    }

    @Test
    fun `native T30 reopen and ten next units agree with the complete oracle`() = runBlocking {
        val pagesDirectory = File(requireNotNull(System.getProperty("mihon.cp1.vol08.pages")))
        val manifest = File(requireNotNull(System.getProperty("mihon.cp1.vol08.manifest")))
        val archive = File(requireNotNull(System.getProperty("mihon.cp1.vol08.archive")))
        val oracle = File(requireNotNull(System.getProperty("mihon.cp1.vol08.oracle")))
        verifyManifestInput(pagesDirectory, manifest)
        val units = verifyCanonicalOracle(oracle, archive, manifest)
        val reopened = captureTargetSample(
            sampleId = "focused-t30-reopen",
            scenario = "focused T30 reopen correctness (not formal)",
            targetIndex = TARGET_INDEX,
            expectedUnit = oracleUnitForPage(units, TARGET_READER_PAGE).expectedReaderPages,
            pagesDirectory = pagesDirectory,
            reopen = true,
        )
        val nextUnits = captureSequenceRound(0, pagesDirectory, units)
        assertEquals(SEQUENCE_ACTIONS_PER_ROUND, nextUnits.size)
        (listOf(reopened) + nextUnits).forEach { row ->
            println("CP1_FOCUSED_REOPEN_NEXT " + row.toJsonLine())
            assertTrue(
                row.failure == null && row.completeUnitLatencyNanos != null,
                "Every focused reopen or next-unit draw must match the full oracle: $row",
            )
            assertEquals(requireNotNull(row.expectedLayout), row.actualLayout)
        }
    }

    private data class NativeUnhandledError(
        val thread: String,
        val atNanos: Long,
        val phase: String,
        val error: Throwable,
    )

    private fun verifyManifestInput(pagesDirectory: File, manifest: File): Int {
        assertTrue(manifest.isFile, "The frozen 176-page input manifest must exist")
        val rows = Regex(
            """\{\s*"file"\s*:\s*"([^"]+\.webp)"\s*,\s*""" +
                """"bytes"\s*:\s*(\d+)\s*,\s*"sha256"\s*:\s*"([a-fA-F0-9]{64})"\s*\}""",
        ).findAll(manifest.readText(Charsets.UTF_8)).toList()
        assertEquals(176, rows.size, "The frozen input manifest must contain 176 WebP hashes")
        rows.forEach { row ->
            val file = pagesDirectory.resolve(row.groupValues[1])
            assertEquals(row.groupValues[2].toLong(), file.length(), "Input size changed: ${file.name}")
            assertEquals(row.groupValues[3].lowercase(), sha256(file), "Input hash changed: ${file.name}")
        }
        assertEquals(176, LocalSourceReader.readDirectory(pagesDirectory).size)
        return rows.size
    }

    private fun verifyCanonicalOracle(oracleFile: File, archive: File, manifest: File): List<CanonicalOracleUnit> {
        assertTrue(oracleFile.isFile, "The separately generated canonical-plan oracle must exist")
        assertTrue(archive.isFile, "The original source archive must exist")
        val json = oracleFile.readText(Charsets.UTF_8)
        assertTrue(json.contains("\"schema\": \"reader-canonical-plan-oracle-v2\""))
        assertTrue(json.contains("\"pageCount\": 176"))
        assertTrue(json.contains("\"sourceArchiveSha256\": \"${sha256(archive)}\""))
        assertTrue(json.contains("\"sourceManifestSha256\": \"${sha256(manifest)}\""))
        assertTrue(
            json.contains(
                "\"settings\": {\"readingDirection\":\"RTL\",\"autoSpreadMatching\":true," +
                    "\"openingMode\":\"AUTO\",\"forcedSingleReaderPages\":[],\"manualPairStartReaderPages\":[]," +
                    "\"split\":false,\"invert\":false,\"rotationDegrees\":0}",
            ),
            "Oracle settings must match the explicitly isolated native reader profile",
        )
        val units = Json.parseToJsonElement(json).jsonObject.getValue("units").jsonArray.map { value ->
            val unit = value.jsonObject
            val layout = unit.getValue("layout").jsonObject
            CanonicalOracleUnit(
                unitIndex = unit.getValue("unitIndex").jsonPrimitive.int,
                expectedReaderPages = unit.getValue("expectedReaderPages").jsonArray.map {
                    it.jsonPrimitive.int
                }.toSet(),
                layout = ReaderSmokeUnitLayout(
                    slots = layout.getValue("slots").jsonArray.map { slotValue ->
                        val slot = slotValue.jsonObject
                        ReaderSmokeUnitSlot(
                            slot.getValue("sourcePageIndex").jsonPrimitive.int,
                            slot.getValue("position").jsonPrimitive.content,
                        )
                    },
                    certain = layout.getValue("certain").jsonPrimitive.boolean,
                    reason = layout.getValue("reason").jsonPrimitive.contentOrNull,
                ),
            )
        }
        oracleLayouts = units.associate { unit ->
            val indexes = unit.expectedReaderPages.map { it - 1 }.toSet()
            assertEquals(indexes, unit.layout.slots.map { it.sourcePageIndex }.toSet())
            assertTrue(unit.layout.certain, "The full-content oracle cannot be provisional")
            indexes to unit.layout
        }
        val allPages = units.flatMap(CanonicalOracleUnit::expectedReaderPages)
        assertEquals(176, allPages.size, "Oracle units must cover 176 pages")
        assertEquals((1..176).toList(), allPages.sorted(), "Oracle units must cover every page exactly once")
        return units
    }

    private suspend fun captureTargetSample(
        sampleId: String,
        scenario: String,
        targetIndex: Int,
        expectedUnit: Set<Int>,
        pagesDirectory: File,
        reopen: Boolean,
    ): NativePerformanceSample {
        var setupFailure: String? = null
        var primingDurationNanos: Long? = null
        val primingStartedAtNanos = if (reopen) System.nanoTime() else null
        if (reopen) {
            var priming: NativeReaderSession? = null
            try {
                val activePriming = openNativeSession(sampleId + "-prime", pagesDirectory)
                priming = activePriming
                val primed = measureLocationAction(
                    session = activePriming,
                    sampleId = sampleId + "-prime",
                    scenario = "reader reopen priming (not measured)",
                    action = "ProductionReaderBinding.page(" + (targetIndex + 1) + ")",
                    expectedUnit = expectedUnit,
                ) { activePriming.binding.page(targetIndex) }
                if (primed.failure != null || primed.completeUnitLatencyNanos == null) {
                    setupFailure = "Reopen priming did not reach the expected full unit: " + primed.failure
                }
            } catch (failure: Throwable) {
                rethrowFatal(failure)
                setupFailure = "Reopen priming failed: " + (failure.message ?: failure::class.simpleName)
            } finally {
                runCatching { priming?.close() }.exceptionOrNull()?.let {
                    setupFailure = listOfNotNull(setupFailure, "Reopen priming close failed: " + it.message)
                        .joinToString("; ")
                }
            }
        }

        if (reopen) primingDurationNanos = System.nanoTime() - requireNotNull(primingStartedAtNanos)
        var session: NativeReaderSession? = null
        var row = NativePerformanceSample.failed(
            sampleId = sampleId,
            scenario = scenario,
            action = "ProductionReaderBinding.page(" + (targetIndex + 1) + ")",
            expectedUnit = expectedUnit,
            failure = setupFailure ?: "Reader session did not start",
        )
        try {
            if (setupFailure == null) {
                val activeSession = openNativeSession(sampleId, pagesDirectory)
                session = activeSession
                row = measureLocationAction(
                    session = activeSession,
                    sampleId = sampleId,
                    scenario = scenario,
                    action = "ProductionReaderBinding.page(" + (targetIndex + 1) + ")",
                    expectedUnit = expectedUnit,
                ) { activeSession.binding.page(targetIndex) }
            }
        } catch (failure: Throwable) {
            rethrowFatal(failure)
            row = row.copy(
                failure = listOfNotNull(
                    setupFailure,
                    "Sample failed: " + (failure.message ?: failure::class.simpleName),
                )
                    .joinToString("; "),
            )
        } finally {
            runCatching { session?.close() }.exceptionOrNull()?.let {
                row = row.copy(
                    failure = listOfNotNull(row.failure, "Reader close failed: " + it.message).joinToString("; "),
                )
            }
        }
        return row.copy(reopenPrimingDurationNanos = primingDurationNanos)
    }

    private suspend fun captureSequenceRound(
        roundIndex: Int,
        pagesDirectory: File,
        oracleUnits: List<CanonicalOracleUnit>,
    ): List<NativePerformanceSample> {
        val prefix = "sequence-round-" + (roundIndex + 1)
        var session: NativeReaderSession? = null
        val rows = mutableListOf<NativePerformanceSample>()
        try {
            val activeSession = openNativeSession(prefix, pagesDirectory)
            session = activeSession
            val startExpected = oracleUnitForPage(oracleUnits, SEQUENCE_START_INDEX + 1).expectedReaderPages
            val positioned = measureLocationAction(
                session = activeSession,
                sampleId = prefix + "-position",
                scenario = "sequence positioning (not measured)",
                action = "ProductionReaderBinding.page(" + (SEQUENCE_START_INDEX + 1) + ")",
                expectedUnit = startExpected,
            ) { activeSession.binding.page(SEQUENCE_START_INDEX) }
            check(positioned.failure == null && positioned.completeUnitLatencyNanos != null) {
                "Could not establish the production sequence starting unit: " + positioned.failure
            }

            val selectedOracleUnits = mutableSetOf<Set<Int>>()
            repeat(SEQUENCE_ACTIONS_PER_ROUND) { turnIndex ->
                val sampleId = prefix + "-turn-" + (turnIndex + 1)
                val before = activeSession.model.state.value
                val beforeUnit = before.dualPageUnits.singleOrNull { unit ->
                    unit.pageIds.any { it.sourcePageIndex == before.currentPage }
                }
                val actualBefore = beforeUnit?.pageIds?.map { it.sourcePageIndex + 1 }?.toSet()
                val currentOracleIndex = oracleUnits.indexOfFirst { it.expectedReaderPages == actualBefore }
                val frozenExpectedNext = oracleUnits.getOrNull(currentOracleIndex + 1)?.expectedReaderPages
                if (frozenExpectedNext == null) {
                    rows += NativePerformanceSample.failed(
                        sampleId = sampleId,
                        scenario = "same-session next-unit sequence",
                        action = "ReaderScreenModel.navigateDualPage(true)",
                        expectedUnit = null,
                        actualUnit = actualBefore,
                        failure = "No next oracle unit follows the actual current unit " + actualBefore,
                    )
                    return@repeat
                }
                val expectedUnitIsDistinct = selectedOracleUnits.add(frozenExpectedNext)
                val priorSequence = before.dualPageLocation?.sequence
                var row = NativePerformanceSample.failed(
                    sampleId = sampleId,
                    scenario = "same-session next-unit sequence",
                    action = "ReaderScreenModel.navigateDualPage(true)",
                    expectedUnit = frozenExpectedNext,
                    failure = "Navigation was not performed",
                )
                try {
                    row = measureLocationAction(
                        session = activeSession,
                        sampleId = sampleId,
                        scenario = "same-session next-unit sequence",
                        action = "ReaderScreenModel.navigateDualPage(true)",
                        expectedUnit = frozenExpectedNext,
                        requiredSequenceChangeFrom = priorSequence,
                    ) {
                        withContext(Dispatchers.Swing) { activeSession.model.navigateDualPage(forward = true) }
                    }
                } catch (failure: Throwable) {
                    rethrowFatal(failure)
                    row = row.copy(
                        failure = "Sequence action failed: " + (failure.message ?: failure::class.simpleName),
                    )
                }
                if (!expectedUnitIsDistinct) {
                    row = row.copy(
                        failure = listOfNotNull(row.failure, "Frozen expected oracle unit repeated within the round")
                            .joinToString("; "),
                    )
                }
                rows += row
            }
            if (selectedOracleUnits.size != SEQUENCE_ACTIONS_PER_ROUND) {
                val last = rows.lastIndex
                if (last >= 0) {
                    rows[last] = rows[last].copy(
                        failure = listOfNotNull(
                            rows[last].failure,
                            "Only " + selectedOracleUnits.size + " distinct expected units were selected",
                        ).joinToString("; "),
                    )
                }
            }
        } catch (failure: Throwable) {
            rethrowFatal(failure)
            while (rows.size < SEQUENCE_ACTIONS_PER_ROUND) {
                val missingIndex = rows.size + 1
                rows += NativePerformanceSample.failed(
                    sampleId = prefix + "-turn-" + missingIndex,
                    scenario = "same-session next-unit sequence",
                    action = "ReaderScreenModel.navigateDualPage(true)",
                    expectedUnit = null,
                    failure = "Sequence session failed before this action: " +
                        (failure.message ?: failure::class.simpleName),
                )
            }
        } finally {
            runCatching { session?.close() }.exceptionOrNull()?.let {
                if (rows.isNotEmpty()) {
                    rows[0] = rows[0].copy(
                        failure = listOfNotNull(rows[0].failure, "Sequence reader close failed: " + it.message)
                            .joinToString("; "),
                    )
                }
            }
        }
        return rows.take(SEQUENCE_ACTIONS_PER_ROUND)
    }

    private suspend fun measureLocationAction(
        session: NativeReaderSession,
        sampleId: String,
        scenario: String,
        action: String,
        expectedUnit: Set<Int>?,
        requiredSequenceChangeFrom: Long? = null,
        performAction: suspend () -> Boolean,
    ): NativePerformanceSample {
        nativePhase.set("$sampleId:measure-intent")
        val startedAtNanos = System.nanoTime()
        check(session.setupStartedAtNanos <= session.setupReadyAtNanos && session.setupReadyAtNanos <= startedAtNanos)
        val accepted = performAction()
        if (!accepted) {
            return NativePerformanceSample.failed(
                sampleId = sampleId,
                scenario = scenario,
                action = action,
                expectedUnit = expectedUnit,
                failure = "Production action was rejected",
                runtimeEpoch = session.runtimeEpoch,
                startedAtNanos = startedAtNanos,
            )
        }
        val state = session.model.state.value
        val location = state.dualPageLocation
        if (location == null) {
            return NativePerformanceSample.failed(
                sampleId = sampleId,
                scenario = scenario,
                action = action,
                expectedUnit = expectedUnit,
                failure = "Production action did not publish a dual-page location",
                runtimeEpoch = session.runtimeEpoch,
                generation = state.session.generation,
                startedAtNanos = startedAtNanos,
            )
        }
        val targetPageIndex = location.pageId.sourcePageIndex
        val failureReasons = mutableListOf<String>()
        if (requiredSequenceChangeFrom != null && location.sequence == requiredSequenceChangeFrom) {
            failureReasons += "Production next action did not change location sequence"
        }
        if (expectedUnit != null && targetPageIndex + 1 !in expectedUnit) {
            failureReasons += "Actual navigation target " + (targetPageIndex + 1) +
                " is outside frozen expected unit " + expectedUnit
        }
        val observation = awaitNativeDraw(
            session = session,
            startedAtNanos = startedAtNanos,
            generation = state.session.generation,
            locationSequence = location.sequence,
            targetPageIndex = targetPageIndex,
            expectedUnitPageIndexes = expectedUnit?.map { it - 1 }?.toSet().orEmpty(),
        )
        val actualUnit = observation.actualUnit
        if (expectedUnit != null && actualUnit != expectedUnit) {
            failureReasons +=
                "Actual production unit " + actualUnit + " does not match frozen oracle unit " + expectedUnit
        }
        if (observation.certification != "Certified") {
            failureReasons += "Current production certification was " + (observation.certification ?: "unobserved")
        }
        if (observation.frameComplete != true) {
            failureReasons += "No complete single-frame draw acknowledgement before the bounded timeout"
        }
        return NativePerformanceSample(
            sampleId = sampleId,
            scenario = scenario,
            action = action,
            targetPage = targetPageIndex + 1,
            expectedUnit = expectedUnit,
            actualUnit = actualUnit,
            runtimeEpoch = session.runtimeEpoch,
            generation = state.session.generation,
            locationSequence = location.sequence,
            targetTicketToken = observation.target?.ticketToken,
            targetFrameId = observation.target?.frameId?.toString(),
            completeUnitTicketToken = observation.completeUnit?.ticketToken,
            completeUnitFrameId = observation.completeUnit?.frameId?.toString(),
            startedAtNanos = startedAtNanos,
            targetAckAtNanos = observation.target?.let { startedAtNanos + it.elapsedNanos },
            completeUnitAckAtNanos = observation.completeUnit?.let { startedAtNanos + it.elapsedNanos },
            certification = observation.certification,
            certificationDependencies = observation.certificationDependencies,
            certificationRounds = observation.certificationRounds,
            roundsScope = "actual model counter at the measured location; not reset or normalized by this harness",
            timeoutNanos = DRAW_ACK_TIMEOUT_NANOS,
            targetTimedOut = observation.target == null,
            completeUnitTimedOut = observation.completeUnit == null,
            failure = failureReasons.takeIf { it.isNotEmpty() }?.joinToString("; "),
            setupStartedAtNanos = session.setupStartedAtNanos,
            setupReadyAtNanos = session.setupReadyAtNanos,
            expectedLayout = expectedUnit?.map { it - 1 }?.toSet()?.let { requireNotNull(oracleLayouts[it]) },
            actualLayout = observation.actualLayout,
        )
    }

    private suspend fun awaitNativeDraw(
        session: NativeReaderSession,
        startedAtNanos: Long,
        generation: Long,
        locationSequence: Long,
        targetPageIndex: Int,
        expectedUnitPageIndexes: Set<Int>,
    ): NativeDrawObservation {
        val deadline = System.nanoTime() + DRAW_ACK_TIMEOUT_NANOS
        var selectedTarget: ReaderSmokeLatency? = null
        var selectedComplete: ReaderSmokeLatency? = null
        var actualUnit: Set<Int>? = null
        var actualLayout: ReaderSmokeUnitLayout? = null
        var certification: String? = null
        var certificationDependencies: Set<Int>? = null
        var certificationRounds: Int? = null
        while (System.nanoTime() < deadline) {
            val receipts = session.drawReceipts.toList()
            selectedTarget = consumeReaderSmokeLatency(
                startedAtNanos = startedAtNanos,
                runtimeEpoch = session.runtimeEpoch,
                generation = generation,
                locationSequence = locationSequence,
                sourcePageIndex = targetPageIndex,
                receipts = receipts,
            )
            if (selectedTarget != null) {
                val targetReceipt = receipts.firstOrNull {
                    it.runtimeEpoch == selectedTarget.runtimeEpoch &&
                        it.generation == selectedTarget.generation &&
                        it.locationSequence == selectedTarget.locationSequence &&
                        it.ticketToken == selectedTarget.ticketToken &&
                        it.frameId == selectedTarget.frameId &&
                        it.sourcePageIndex == targetPageIndex &&
                        it.monotonicNanos == startedAtNanos + selectedTarget.elapsedNanos
                }
                if (targetReceipt != null) {
                    actualUnit = targetReceipt.unitPageIndexes.map { it + 1 }.toSet()
                    actualLayout = targetReceipt.unitLayout
                }
            }
            selectedComplete = consumeExpectedCompleteReaderSmokeFrameLatency(
                startedAtNanos = startedAtNanos,
                runtimeEpoch = session.runtimeEpoch,
                generation = generation,
                locationSequence = locationSequence,
                expectedUnitPageIndexes = expectedUnitPageIndexes,
                receipts = receipts,
                expectedLayout = requireNotNull(oracleLayouts[expectedUnitPageIndexes]),
            )
            if (selectedComplete != null) {
                val completeReceipt = receipts.firstOrNull {
                    it.runtimeEpoch == selectedComplete.runtimeEpoch &&
                        it.generation == selectedComplete.generation &&
                        it.locationSequence == selectedComplete.locationSequence &&
                        it.ticketToken == selectedComplete.ticketToken &&
                        it.frameId == selectedComplete.frameId &&
                        it.sourcePageIndex == selectedComplete.sourcePageIndex &&
                        it.monotonicNanos == startedAtNanos + selectedComplete.elapsedNanos
                }
                actualUnit = completeReceipt?.unitPageIndexes?.map { it + 1 }?.toSet()
                actualLayout = completeReceipt?.unitLayout
                val proof = currentProof(
                    session.model,
                    session.model.state.value.session.activeChapter.pages[targetPageIndex].id,
                )
                certification = proof.certification::class.simpleName
                certificationDependencies = proof.dependentPageIds
                    .map { it.sourcePageIndex + 1 }.toSet()
                certificationRounds = session.model.certificationRounds
                val selectedTicket = synchronized(session.ticketsByToken) {
                    session.ticketsByToken[selectedComplete.ticketToken]
                }
                val currentFrame = selectedTicket?.let { ticket ->
                    synchronized(session.lifecycleLock) {
                        session.ownerField.get(session.model) as? SharedDualPageSession
                    }?.frame(ticket)
                }
                val drawn = currentFrame?.drawnPageIds?.map { it.sourcePageIndex }?.toSet()
                val frameComplete = currentFrame != null && completeReceipt != null &&
                    currentFrame.id == selectedComplete.frameId &&
                    currentFrame.unit.pageIds.map { it.sourcePageIndex }.toSet() == expectedUnitPageIndexes &&
                    drawn?.containsAll(expectedUnitPageIndexes) == true &&
                    readerSmokeUnitLayout(currentFrame.unit) ==
                    requireNotNull(oracleLayouts[expectedUnitPageIndexes])
                if (!frameComplete) certification = "FrameAcknowledgementNoLongerCurrent"
                return NativeDrawObservation(
                    target = selectedTarget,
                    completeUnit = selectedComplete,
                    actualUnit = actualUnit,
                    certification = certification,
                    certificationDependencies = certificationDependencies,
                    certificationRounds = certificationRounds,
                    frameComplete = frameComplete,
                    actualLayout = actualLayout,
                )
            }
            delay(DRAW_POLL_INTERVAL_MS)
        }
        return NativeDrawObservation(
            target = selectedTarget,
            completeUnit = selectedComplete,
            actualUnit = actualUnit,
            certification = certification,
            certificationDependencies = certificationDependencies,
            certificationRounds = certificationRounds,
            frameComplete = false,
            actualLayout = actualLayout,
        )
    }

    private suspend fun openNativeSession(
        sessionId: String,
        pagesDirectory: File,
        pageTurnAnimationOverride: Boolean? = null,
    ): NativeReaderSession {
        nativePhase.set("$sessionId:open")
        val setupStartedAtNanos = System.nanoTime()
        val controller = ReaderTestModeController()
        controller.beginScenario(0)
        ReaderIoTestModeBridge.install(controller)
        val preferenceStore = inMemoryDesktopPreferenceStore()
        val geometryStore = desktopWindowGeometryStore(preferenceStore)
        val di = initDesktopDIForTest(
            appDir = tempDir.resolve("w2-profile-" + sessionId),
            preferenceStore = preferenceStore,
            startDownloadWorker = false,
        )
        pendingNativeCleanupGroups.incrementAndGet()
        val readerPrefs = Injekt.get<ReaderPreferences>().apply {
            readingMode = ReadingMode.RTL
            isDualPage = true
            isAutoSpreadMatching = true
            autoSplitPages = false
            colorFilterInvert = false
        }
        pageTurnAnimationOverride?.let { enabled ->
            Injekt.get<DesktopAppPreferences>().pageTurnAnimation.set(enabled)
        }
        val windowReady = CompletableDeferred<ComposeWindow>()
        val windowScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val windowJob = windowScope.launchApplication {
            DesktopMainWindow(
                geometryStore = geometryStore,
                onCloseRequest = { exitApplication() },
                title = "Mihon isolated CP1 W2 " + sessionId,
            ) {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt(),
                ) {
                    val screen = localReaderScreen(pagesDirectory, "CP1 W2", "176 WebP")
                    MaterialTheme { Navigator(screen) { screen.Content() } }
                }
                LaunchedEffect(window) { windowReady.complete(window) }
            }
        }
        var frame: ComposeWindow? = null
        var model: ReaderScreenModel? = null
        var modelSpyInstalled = false
        try {
            frame = withTimeout(WINDOW_START_TIMEOUT_MS) { windowReady.await() }
            awaitCondition("production binding with the complete local chapter") {
                ProductionReaderTestModeBridge.binding?.snapshot()?.totalPages == 176
            }
            val binding = requireNotNull(ProductionReaderTestModeBridge.binding)
            assertEquals(176, binding.snapshot().totalPages)
            val observedModel = bindingModel(binding)
            model = observedModel
            val observedRuntime = requireNotNull(observedModel.runtime) {
                "The production reader runtime must be attached"
            }
            val runtimeEpoch = runtimeEpochCounter.incrementAndGet()
            val drawReceipts = CopyOnWriteArrayList<ReaderSmokeDrawReceipt>()
            val ticketTokens = IdentityHashMap<ReaderDualPageViewportTicket, Long>()
            val ticketsByToken = mutableMapOf<Long, ReaderDualPageViewportTicket>()
            var nextTicketToken = 0L
            val lifecycleLock = ReaderScreenModel::class.java.getDeclaredField("productionRuntimeLifecycleLock")
                .apply { isAccessible = true }.get(observedModel)
            val ownerField = ReaderScreenModel::class.java.getDeclaredField("dualSession")
                .apply { isAccessible = true }
            mockkObject(observedModel)
            modelSpyInstalled = true
            every { observedModel.drawDualViewportPage(any(), any()) } answers {
                val accepted = callOriginal()
                if (accepted && observedModel.runtime === observedRuntime) {
                    val drawTime = System.nanoTime()
                    val ticket = firstArg<ReaderDualPageViewportTicket>()
                    val page = secondArg<mihon.domain.reader.session.ReaderPageId>()
                    val ticketToken = synchronized(ticketTokens) {
                        ticketTokens[ticket] ?: (++nextTicketToken).also {
                            ticketTokens[ticket] = it
                            ticketsByToken[it] = ticket
                        }
                    }
                    val receipt = synchronized(lifecycleLock) {
                        val stateSnapshot = observedModel.state.value
                        val currentLocationSequence = stateSnapshot.dualPageLocation?.sequence
                        if (ticket.identity.generation != stateSnapshot.session.generation ||
                            ticket.locationSequence != currentLocationSequence
                        ) {
                            return@synchronized null
                        }
                        val owner = ownerField.get(observedModel) as? SharedDualPageSession
                            ?: return@synchronized null
                        val snapshot = owner.frame(ticket) ?: return@synchronized null
                        val framePageIds = snapshot.unit.pageIds
                        val ticketPageIds = ticket.unitId.slots.map { it.pageId }
                        if (snapshot.id != ticket.frameId || framePageIds != ticketPageIds) {
                            return@synchronized null
                        }
                        ReaderSmokeDrawReceipt(
                            runtimeEpoch = runtimeEpoch,
                            generation = ticket.identity.generation,
                            locationSequence = ticket.locationSequence,
                            ticketToken = ticketToken,
                            frameId = snapshot.id,
                            unitPageIndexes = framePageIds.map { it.sourcePageIndex }.toSet(),
                            drawnPageIndexes = snapshot.drawnPageIds.map { it.sourcePageIndex }.toSet(),
                            sourcePageIndex = page.sourcePageIndex,
                            monotonicNanos = drawTime,
                            unitLayout = readerSmokeUnitLayout(snapshot.unit),
                        )
                    }
                    if (receipt != null) drawReceipts += receipt
                }
                accepted
            }
            assertEquals(ReadingMode.RTL, readerPrefs.readingMode)
            assertTrue(readerPrefs.isDualPage)
            assertTrue(readerPrefs.isAutoSpreadMatching)
            assertEquals(
                0,
                observedModel.state.value.currentPage,
                "A new isolated reader must only prepare initial page zero",
            )
            return NativeReaderSession(
                sessionId = sessionId,
                di = di,
                readerPrefs = readerPrefs,
                controller = controller,
                windowScope = windowScope,
                windowJob = windowJob,
                frame = requireNotNull(frame),
                binding = binding,
                model = observedModel,
                runtimeEpoch = runtimeEpoch,
                drawReceipts = drawReceipts,
                ticketsByToken = ticketsByToken,
                lifecycleLock = lifecycleLock,
                ownerField = ownerField,
                setupStartedAtNanos = setupStartedAtNanos,
                setupReadyAtNanos = System.nanoTime(),
            )
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                nativePhase.set("$sessionId:open-failure-cleanup")
                var cleanupSucceeded = true
                runCatching {
                    if (frame == null) {
                        windowJob.cancel()
                    } else {
                        onEdt { frame?.dispatchEvent(WindowEvent(requireNotNull(frame), WindowEvent.WINDOW_CLOSING)) }
                    }
                    withTimeout(WINDOW_CLOSE_TIMEOUT_MS) { windowJob.join() }
                }.onFailure {
                    cleanupSucceeded = false
                    failure.addSuppressed(it)
                }
                runCatching { windowScope.coroutineContext[Job]?.cancelAndJoin() }
                    .onFailure {
                        cleanupSucceeded = false
                        failure.addSuppressed(it)
                    }
                ProductionReaderTestModeBridge.reset()
                ReaderIoTestModeBridge.clear(controller)
                readerPrefs.isDualPage = false
                readerPrefs.isAutoSpreadMatching = false
                runCatching { di.closeAndJoin() }
                    .onFailure {
                        cleanupSucceeded = false
                        failure.addSuppressed(it)
                    }
                if (cleanupSucceeded) {
                    if (modelSpyInstalled) {
                        runCatching { unmockkObject(requireNotNull(model)) }
                            .onFailure {
                                cleanupSucceeded = false
                                failure.addSuppressed(it)
                            }
                    }
                    if (cleanupSucceeded) pendingNativeCleanupGroups.decrementAndGet()
                }
            }
            throw failure
        }
    }

    private fun bindingModel(binding: ProductionReaderBinding): ReaderScreenModel {
        val field = binding.javaClass.getDeclaredField("model").apply { isAccessible = true }
        return field.get(binding) as ReaderScreenModel
    }

    private fun oracleUnitForPage(units: List<CanonicalOracleUnit>, oneBasedPage: Int): CanonicalOracleUnit =
        units.single { oneBasedPage in it.expectedReaderPages }

    private fun rethrowFatal(failure: Throwable) {
        if (failure is kotlinx.coroutines.CancellationException ||
            failure is VirtualMachineError ||
            failure is ThreadDeath
        ) {
            throw failure
        }
    }

    private data class NativeDrawObservation(
        val target: ReaderSmokeLatency?,
        val completeUnit: ReaderSmokeLatency?,
        val actualUnit: Set<Int>?,
        val certification: String?,
        val certificationDependencies: Set<Int>?,
        val certificationRounds: Int?,
        val frameComplete: Boolean?,
        val actualLayout: ReaderSmokeUnitLayout?,
    )

    private data class NativePerformanceSample(
        val sampleId: String,
        val scenario: String,
        val action: String,
        val targetPage: Int?,
        val expectedUnit: Set<Int>?,
        val actualUnit: Set<Int>?,
        val runtimeEpoch: Long?,
        val generation: Long?,
        val locationSequence: Long?,
        val targetTicketToken: Long?,
        val targetFrameId: String?,
        val completeUnitTicketToken: Long?,
        val completeUnitFrameId: String?,
        val startedAtNanos: Long?,
        val targetAckAtNanos: Long?,
        val completeUnitAckAtNanos: Long?,
        val certification: String?,
        val certificationDependencies: Set<Int>?,
        val certificationRounds: Int?,
        val roundsScope: String?,
        val timeoutNanos: Long,
        val targetTimedOut: Boolean,
        val completeUnitTimedOut: Boolean,
        val failure: String?,
        val setupStartedAtNanos: Long? = null,
        val setupReadyAtNanos: Long? = null,
        val reopenPrimingDurationNanos: Long? = null,
        val expectedLayout: ReaderSmokeUnitLayout? = null,
        val actualLayout: ReaderSmokeUnitLayout? = null,
    ) {
        val targetLatencyNanos: Long?
            get() = if (startedAtNanos != null && targetAckAtNanos != null) targetAckAtNanos - startedAtNanos else null
        val completeUnitLatencyNanos: Long?
            get() = if (startedAtNanos != null && completeUnitAckAtNanos != null) {
                completeUnitAckAtNanos - startedAtNanos
            } else {
                null
            }

        fun toJsonLine(): String = buildString {
            append("{\"sampleId\":")
            append(jsonString(sampleId))
            append(",\"scenario\":")
            append(jsonString(scenario))
            append(",\"action\":")
            append(jsonString(action))
            append(",\"targetPage\":")
            append(targetPage?.toString() ?: "null")
            append(",\"expectedReaderPages\":")
            append(expectedUnit?.sorted()?.joinToString(prefix = "[", postfix = "]") ?: "null")
            append(",\"actualReaderPages\":")
            append(actualUnit?.sorted()?.joinToString(prefix = "[", postfix = "]") ?: "null")
            append(",\"runtimeEpoch\":")
            append(runtimeEpoch?.toString() ?: "null")
            append(",\"generation\":")
            append(generation?.toString() ?: "null")
            append(",\"locationSequence\":")
            append(locationSequence?.toString() ?: "null")
            append(",\"targetTicketToken\":")
            append(targetTicketToken?.toString() ?: "null")
            append(",\"targetFrameId\":")
            append(targetFrameId?.let(::jsonString) ?: "null")
            append(",\"completeUnitTicketToken\":")
            append(completeUnitTicketToken?.toString() ?: "null")
            append(",\"completeUnitFrameId\":")
            append(completeUnitFrameId?.let(::jsonString) ?: "null")
            append(",\"startedAtNanos\":")
            append(startedAtNanos?.toString() ?: "null")
            append(",\"targetAckAtNanos\":")
            append(targetAckAtNanos?.toString() ?: "null")
            append(",\"completeUnitAckAtNanos\":")
            append(completeUnitAckAtNanos?.toString() ?: "null")
            append(",\"targetLatencyNanos\":")
            append(targetLatencyNanos?.toString() ?: "null")
            append(",\"completeUnitLatencyNanos\":")
            append(completeUnitLatencyNanos?.toString() ?: "null")
            append(",\"certification\":")
            append(certification?.let(::jsonString) ?: "null")
            append(",\"certificationDependencies\":")
            append(certificationDependencies?.sorted()?.joinToString(prefix = "[", postfix = "]") ?: "null")
            append(",\"certificationRounds\":")
            append(certificationRounds?.toString() ?: "null")
            append(",\"roundsScope\":")
            append(roundsScope?.let(::jsonString) ?: "null")
            append(",\"timeoutNanos\":")
            append(timeoutNanos)
            append(",\"targetTimedOut\":")
            append(targetTimedOut)
            append(",\"completeUnitTimedOut\":")
            append(completeUnitTimedOut)
            append(",\"setupStartedAtNanos\":")
            append(setupStartedAtNanos?.toString() ?: "null")
            append(",\"setupReadyAtNanos\":")
            append(setupReadyAtNanos?.toString() ?: "null")
            append(",\"setupDurationNanos\":")
            append(
                if (setupStartedAtNanos != null && setupReadyAtNanos != null) {
                    (setupReadyAtNanos - setupStartedAtNanos).toString()
                } else {
                    "null"
                },
            )
            append(",\"setupToCompleteUnitNanos\":")
            append(
                if (setupStartedAtNanos != null && completeUnitAckAtNanos != null) {
                    (completeUnitAckAtNanos - setupStartedAtNanos).toString()
                } else {
                    "null"
                },
            )
            append(",\"reopenPrimingDurationNanos\":")
            append(reopenPrimingDurationNanos?.toString() ?: "null")
            append(",\"expectedUnitLayout\":")
            append(expectedLayout?.toJson() ?: "null")
            append(",\"actualUnitLayout\":")
            append(actualLayout?.toJson() ?: "null")
            append(",\"failure\":")
            append(failure?.let(::jsonString) ?: "null")
            append("}")
        }

        companion object {
            fun failed(
                sampleId: String,
                scenario: String,
                action: String,
                expectedUnit: Set<Int>?,
                failure: String,
                actualUnit: Set<Int>? = null,
                runtimeEpoch: Long? = null,
                generation: Long? = null,
                startedAtNanos: Long? = null,
            ) = NativePerformanceSample(
                sampleId = sampleId,
                scenario = scenario,
                action = action,
                targetPage = null,
                expectedUnit = expectedUnit,
                actualUnit = actualUnit,
                runtimeEpoch = runtimeEpoch,
                generation = generation,
                locationSequence = null,
                targetTicketToken = null,
                targetFrameId = null,
                completeUnitTicketToken = null,
                completeUnitFrameId = null,
                startedAtNanos = startedAtNanos,
                targetAckAtNanos = null,
                completeUnitAckAtNanos = null,
                certification = null,
                certificationDependencies = null,
                certificationRounds = null,
                roundsScope = null,
                timeoutNanos = DRAW_ACK_TIMEOUT_NANOS,
                targetTimedOut = false,
                completeUnitTimedOut = false,
                failure = failure,
            )
        }
    }

    private inner class NativeReaderSession(
        val sessionId: String,
        private val di: mihon.desktop.di.DesktopTestDIContext,
        private val readerPrefs: ReaderPreferences,
        val controller: ReaderTestModeController,
        private val windowScope: CoroutineScope,
        private val windowJob: Job,
        private val frame: ComposeWindow,
        val binding: ProductionReaderBinding,
        val model: ReaderScreenModel,
        val runtimeEpoch: Long,
        val drawReceipts: CopyOnWriteArrayList<ReaderSmokeDrawReceipt>,
        val ticketsByToken: MutableMap<Long, ReaderDualPageViewportTicket>,
        val lifecycleLock: Any,
        val ownerField: java.lang.reflect.Field,
        val setupStartedAtNanos: Long,
        val setupReadyAtNanos: Long,
    ) {
        private var closed = false

        suspend fun close() {
            if (closed) return
            closed = true
            nativePhase.set("$sessionId:window-close")
            withContext(NonCancellable) {
                try {
                    if (windowJob.isActive) {
                        onEdt { frame.dispatchEvent(WindowEvent(frame, WindowEvent.WINDOW_CLOSING)) }
                    }
                    withTimeout(WINDOW_CLOSE_TIMEOUT_MS) { windowJob.join() }
                } finally {
                    windowScope.coroutineContext[Job]?.cancelAndJoin()
                    ProductionReaderTestModeBridge.reset()
                    ReaderIoTestModeBridge.clear(controller)
                    readerPrefs.isDualPage = false
                    readerPrefs.isAutoSpreadMatching = false
                    di.closeAndJoin()
                    unmockkObject(model)
                    pendingNativeCleanupGroups.decrementAndGet()
                }
            }
        }
    }

    private fun currentProof(
        model: ReaderScreenModel,
        target: mihon.domain.reader.session.ReaderPageId,
    ): NativeCertificationTrace {
        val lockField = ReaderScreenModel::class.java.getDeclaredField("productionRuntimeLifecycleLock")
            .apply { isAccessible = true }
        val ownerField = ReaderScreenModel::class.java.getDeclaredField("dualSession").apply { isAccessible = true }
        val lock = lockField.get(model)
        return synchronized(lock) {
            val owner = requireNotNull(ownerField.get(model) as? SharedDualPageSession)
            val certification = owner.certifyPairing(target)
            val dependencies = when (certification) {
                is ReaderPairingCertification.Certified -> certification.segment.dependentPageIds
                is ReaderPairingCertification.NeedFacts -> certification.facts.flatMapTo(linkedSetOf()) {
                    listOfNotNull(it.pageId, it.secondPageId)
                }
                is ReaderPairingCertification.RetryableBlocked -> certification.facts.flatMapTo(linkedSetOf()) {
                    listOfNotNull(it.pageId, it.secondPageId)
                }
            }
            NativeCertificationTrace(certification, dependencies)
        }
    }

    private suspend fun awaitCondition(description: String, predicate: () -> Boolean) {
        withTimeout(20_000) {
            while (!predicate()) delay(20)
        }
        assertTrue(predicate(), description)
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private data class NativeCertificationTrace(
        val certification: ReaderPairingCertification,
        val dependentPageIds: Set<mihon.domain.reader.session.ReaderPageId>,
    )

    private data class CanonicalOracleUnit(
        val unitIndex: Int,
        val expectedReaderPages: Set<Int>,
        val layout: ReaderSmokeUnitLayout,
    )

    private fun onEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) return action()
        val task = FutureTask(Callable(action))
        SwingUtilities.invokeAndWait(task)
        task.get()
    }

    private companion object {
        const val TARGET_INDEX = 29
        const val TARGET_READER_PAGE = TARGET_INDEX + 1
        const val T170_INDEX = 169
        const val SEQUENCE_ACTIONS_PER_ROUND = 10
        const val SEQUENCE_START_INDEX = 25
        const val WINDOW_START_TIMEOUT_MS = 20_000L
        const val WINDOW_CLOSE_TIMEOUT_MS = 10_000L
        const val DRAW_ACK_TIMEOUT_NANOS = 15_000_000_000L
        const val DRAW_POLL_INTERVAL_MS = 10L

        private fun jsonString(value: String): String = "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""

        val runtimeEpochCounter = AtomicLong(0L)
    }
}
