package mihon.desktop.parity

import java.nio.file.Files
import java.nio.file.Path
import java.util.Date
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import mihon.domain.reader.scheduler.ReaderRequestScheduler
import mihon.domain.reader.scheduler.ReaderSchedulerPolicy
import mihon.domain.reader.session.ReaderChapterId
import tachiyomi.domain.reader.model.ReadingProgressEvent

class ReaderFixedMainAuthorityTest {
    private val repositoryRoot = repositoryRoot()
    private val fixturePath = repositoryRoot.resolve(READER_AUTHORITY_FIXTURE)
    private val inventoryPath = repositoryRoot.resolve(FIXED_MAIN_INVENTORY)
    private val manifestPath = repositoryRoot.resolve(PARITY_MANIFEST)

    @Test
    fun `reader authority fixture binds every fixed loader state transition and progress symbol`() {
        validateFixture(Files.readString(fixturePath))
    }

    @Test
    fun `fixed reader symbols and tracked changes resolve to exact git objects`() {
        val fixture = Json.parseToJsonElement(Files.readString(fixturePath)).jsonObject
        val fixedCommit = FIXED_MAIN_REF.substringAfterLast('@')
        val lineageBase = FIXED_UPSTREAM_LINEAGE_BASE.substringAfterLast('@')
        val trackedCommit = TRACKED_UPSTREAM_REF.substringAfterLast('@')

        assertEquals(lineageBase, runGit("rev-parse", "$fixedCommit^2").single())
        fixture.getValue("symbols").jsonArray.map { it.jsonObject }.forEach { symbol ->
            val path = symbol.requiredText("path")
            val expectedBlob = symbol.requiredText("blobId")
            assertEquals(expectedBlob, runGit("rev-parse", "$fixedCommit:$path").single(), path)
            assertEquals(expectedBlob, runGit("rev-parse", "$lineageBase:$path").single(), "$path lineage")

            val source = runGit("cat-file", "blob", expectedBlob).joinToString("\n")
            symbol.getValue("markers").jsonArray.forEach { marker ->
                assertTrue(source.contains(marker.jsonPrimitive.content), "$path is missing ${marker.jsonPrimitive.content}")
            }
        }
        fixture.getValue("trackedUpstreamChanges").jsonArray.map { it.jsonObject }.forEach { change ->
            runGit("merge-base", "--is-ancestor", change.requiredText("commit"), trackedCommit)
        }

        val deviations = fixture.getValue("deviations").jsonArray.map { it.jsonObject }
            .associateBy { it.requiredText("id") }
        REQUIRED_DEVIATION_EVIDENCE.forEach { (id, expectedRef) ->
            val evidenceRef = deviations.getValue(id).requiredText("evidenceRef")
            assertEquals(expectedRef, evidenceRef, "$id evidence ref")
            if (evidenceRef.startsWith("planned:")) {
                assertEquals("planned:RD-02", evidenceRef)
            } else {
                assertEquals("commit", runGit("cat-file", "-t", evidenceRef).single(), "$id evidence type")
                runGit("merge-base", "--is-ancestor", evidenceRef, "HEAD")
            }
        }
        DEVIATION_INTRODUCTION_PATHS.forEach { (id, requiredPaths) ->
            val changedPaths = runGit("show", "--format=", "--name-only", deviations.getValue(id).requiredText("evidenceRef")).toSet()
            assertTrue(changedPaths.containsAll(requiredPaths), "$id evidence commit is missing $requiredPaths")
        }
        DEVIATION_BASELINE_PATHS.forEach { (id, path) ->
            val source = sourceAt(deviations.getValue(id).requiredText("evidenceRef"), path)
            DEVIATION_BASELINE_REQUIRED_MARKERS.getValue(id).forEach { marker ->
                assertTrue(source.contains(marker), "$id baseline is missing $marker")
            }
            DEVIATION_BASELINE_FORBIDDEN_MARKERS.getValue(id).forEach { marker ->
                assertFalse(source.contains(marker), "$id baseline unexpectedly contains $marker")
            }
        }
    }

    @Test
    fun `reader authority mutations reject wrong ref blob path behavior and classification`() {
        val fixture = Files.readString(fixturePath)
        val mutations = mutableListOf(
            fixture.replaceFirst(FIXED_MAIN_REF, "main@${"0".repeat(40)}"),
            fixture.replaceFirst(FIXED_UPSTREAM_LINEAGE_BASE, "upstream/main@${"0".repeat(40)}"),
            fixture.replaceFirst("CURRENT_PLUS_FOUR", "REMOVED_CURRENT_PLUS_FOUR"),
            fixture.replaceFirst("CROSS_PLATFORM_RELIABILITY_ENHANCEMENT", "FIXED_ORIGINAL"),
        )
        REQUIRED_AUTHORITY_BLOBS.forEach { (path, blobId) ->
            mutations += fixture.replaceFirst(path, "app/src/main/java/missing/${path.substringAfterLast('/')}")
            mutations += fixture.replaceFirst(blobId, "0".repeat(40))
            mutations += fixture.replaceFirst(REQUIRED_AUTHORITY_MARKERS.getValue(path), "REMOVED_${path.substringAfterLast('/')}")
        }
        REQUIRED_BEHAVIOR_MARKERS.values.flatten().forEach { marker ->
            mutations += fixture.replaceFirst(marker, "REMOVED_${marker.hashCode()}")
        }
        REQUIRED_DEVIATION_EVIDENCE.values.forEach { evidenceRef ->
            mutations += fixture.replaceFirst(evidenceRef, "invalid-evidence-ref")
        }

        mutations.forEach { mutation ->
            assertThrows(AssertionError::class.java) { validateFixture(mutation) }
        }
    }

    @Test
    fun `fixed current plus four fixture executes through the shared scheduler`() {
        val vector = behaviorVector("CURRENT_PLUS_FOUR")
        val forwardWindow = vector.requiredText("value").toInt()

        val plan = ReaderRequestScheduler(
            ReaderSchedulerPolicy(
                nearbyForward = forwardWindow,
                nearbyBackward = 0,
                maxConcurrentRequests = 1,
            ),
        ).moveTo(ReaderChapterId(1), currentPage = 2, pageCount = 10)

        assertEquals(listOf(2, 3, 4, 5, 6), plan.requests.map { it.pageIndex })
        assertEquals("FIXED_ORIGINAL", vector.requiredText("classification"))
    }

    @Test
    fun `fixed last page completion fixture executes through the current progress event`() {
        val vector = behaviorVector("LAST_PAGE_COMPLETION")
        assertEquals("LAST_LOGICAL_PAGE", vector.requiredText("value"))

        val penultimate = ReadingProgressEvent(1, 8, 10, Date(0), 0, idempotencyKey = "penultimate")
        val last = ReadingProgressEvent(1, 9, 10, Date(0), 0, idempotencyKey = "last")

        assertFalse(penultimate.isRead)
        assertTrue(last.isRead)
    }

    @Test
    fun `fixed adjacent preload trace is page list only`() {
        val vector = behaviorVector("ADJACENT_PAGE_LIST_THRESHOLD")

        assertEquals(
            ADJACENT_PAGE_LIST_PATH_CHAIN,
            vector.getValue("requiredPathChain").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            ADJACENT_PAGE_LIST_FORBIDDEN_OPERATIONS,
            vector.getValue("forbiddenOperations").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )

        val pagerBody = fixedFunctionBody(PAGER_VIEWER_PATH, "private fun onReaderPageSelected")
        val webtoonBody = fixedFunctionBody(WEBTOON_VIEWER_PATH, "private fun onPageSelected")
        val activityBody = fixedFunctionBody(READER_ACTIVITY_PATH, "fun requestPreloadChapter")
        val viewModelBody = fixedFunctionBody(READER_VIEW_MODEL_PATH, "suspend fun preload")
        val chapterLoaderBody = fixedFunctionBody(CHAPTER_LOADER_PATH, "suspend fun loadChapter")

        assertTrue(pagerBody.contains("pages.size - page.number < 5"))
        assertTrue(pagerBody.contains("adapter.nextTransition?.to?.let(activity::requestPreloadChapter)"))
        assertTrue(webtoonBody.contains("pages.size - page.number < 5"))
        assertTrue(webtoonBody.contains("activity.requestPreloadChapter(transitionChapter)"))
        assertTrue(activityBody.contains("viewModel.preload(chapter)"))
        assertTrue(viewModelBody.contains("loader.loadChapter(chapter)"))
        assertTrue(chapterLoaderBody.contains("loader.getPages()"))

        listOf(pagerBody, webtoonBody, activityBody, viewModelBody, chapterLoaderBody).forEach { body ->
            ACTUAL_IMAGE_MATERIALIZATION_CALLS.forEach { forbiddenCall ->
                assertFalse(body.contains(forbiddenCall), "Adjacent page-list call body unexpectedly contains $forbiddenCall")
            }
        }
    }

    @Test
    fun `fixed transition holders expose loading and retry without continue cancel or dismiss actions`() {
        listOf("PAGER_TRANSITION", "WEBTOON_TRANSITION").forEach { vectorId ->
            val source = fixedSource(behaviorVector(vectorId).requiredText("sourcePath"))
            TRANSITION_REQUIRED_MARKERS.forEach { marker ->
                assertTrue(source.contains(marker), "$vectorId is missing $marker")
            }
            TRANSITION_FORBIDDEN_ACTION_MARKERS.forEach { marker ->
                assertFalse(source.contains(marker), "$vectorId unexpectedly exposes $marker")
            }
        }
    }

    @Test
    fun `reader parity entries do not overclaim a shared session executor`() {
        val items = Json.parseToJsonElement(Files.readString(manifestPath)).jsonArray
            .associateBy { it.jsonObject.getValue("id").jsonPrimitive.content.toInt() }

        assertEquals(READER_PARITY_SLICES.keys, items.keys.intersect(READER_PARITY_SLICES.keys))
        READER_PARITY_SLICES.forEach { (id, verifiedSlice) ->
            val item = items.getValue(id).jsonObject
            val scope = item.getValue("readerCoreMigrationScope").jsonObject

            assertEquals("R0-01", scope.requiredText("task"), "ID $id migration audit owner")
            assertEquals(verifiedSlice, scope.requiredText("verifiedSlice"), "ID $id verified slice")
            assertEquals("NOT_WIRED", scope.requiredText("canonicalSessionExecutor"), "ID $id canonical executor state")
            assertEquals("RD-01", scope.requiredText("closureTask"), "ID $id canonical executor closure")
            assertTrue(
                item.requiredText("verificationScope").contains("does not prove shared ReaderSessionCore"),
                "ID $id must explicitly exclude the canonical session executor from its current VERIFIED scope",
            )
        }

        val preloadItem = items.getValue(45).jsonObject
        val preloadDecisionScope = "SHARED_PRIORITY_SCHEDULER_AND_ENCODED_STORE"
        val materializeExecutorPath =
            "domain/src/commonMain/kotlin/mihon/domain/reader/materialize/ReaderMaterializeExecutor.kt"
        val schedulerPath =
            "domain/src/commonMain/kotlin/mihon/domain/reader/scheduler/ReaderRequestScheduler.kt"
        assertEquals(
            setOf(45),
            items.filterValues { item ->
                item.jsonObject["sharedImplementationPaths"]?.jsonArray?.any {
                    it.jsonPrimitive.content == materializeExecutorPath
                } == true
            }.keys,
            "The RC-02 materialize executor must belong only to reader capability ID 45",
        )
        assertTrue(
            preloadItem.getValue("roleEvidence").jsonObject.getValue("SHARED_OR_ADAPTER").jsonArray.any { evidence ->
                val entry = evidence.jsonObject
                entry.requiredText("path") == materializeExecutorPath &&
                    entry.requiredText("symbol") == "object CanonicalReaderMaterializeExecutor"
            },
            "ID 45 must bind the shared materialize executor symbol",
        )
        assertEquals(
            setOf(45),
            items.filterValues { item ->
                item.jsonObject["sharedImplementationPaths"]?.jsonArray?.any {
                    it.jsonPrimitive.content == schedulerPath
                } == true
            }.keys,
            "The RC-03 priority scheduler must belong only to reader capability ID 45",
        )
        assertTrue(
            preloadItem.getValue("roleEvidence").jsonObject.getValue("SHARED_OR_ADAPTER").jsonArray.any { evidence ->
                val entry = evidence.jsonObject
                entry.requiredText("path") == schedulerPath &&
                    entry.requiredText("symbol") == "class ReaderRequestScheduler"
            },
            "ID 45 must bind the shared priority scheduler symbol",
        )
        assertEquals(
            preloadDecisionScope,
            preloadItem.getValue("statusDecision").jsonObject.requiredText("scope"),
        )
        assertEquals(
            setOf(45),
            items.filterValues { item ->
                item.jsonObject["statusDecision"]?.jsonObject?.get("scope")?.jsonPrimitive?.content == preloadDecisionScope
            }.keys,
            "Reader preload decision scope must not leak into another capability",
        )
        val preloadDeviations = preloadItem.getValue("deviations").jsonArray.map { it.jsonObject }
            .associateBy { it.requiredText("id") }
        assertEquals(setOf("GENERATION_HARDENING", "HTTP_RETRY_FORCE_DRIFT"), preloadDeviations.keys)
        assertEquals(
            "CROSS_PLATFORM_RELIABILITY_ENHANCEMENT",
            preloadDeviations.getValue("GENERATION_HARDENING").requiredText("classification"),
        )
        val retryDeviation = preloadDeviations.getValue("HTTP_RETRY_FORCE_DRIFT")
        assertEquals("PRODUCT_GAP", retryDeviation.requiredText("classification"))
        assertEquals("RC-02", retryDeviation.requiredText("closureTask"))
        assertEquals("CLOSED", retryDeviation.requiredText("resolutionStatus"))
        val retryResolution = retryDeviation.getValue("resolutionEvidence").jsonObject
        assertEquals(
            "domain/src/commonMain/kotlin/mihon/domain/reader/materialize/ReaderMaterializeExecutor.kt",
            retryResolution.requiredText("sharedExecutor"),
        )
        assertEquals(
            "app/src/test/java/eu/kanade/tachiyomi/ui/reader/loader/ReaderMaterializeProductionWiringTest.kt#HTTP page request runs through canonical executor and retry forces redownload",
            retryResolution.requiredText("productionBehaviorTest"),
        )
        val migrationScope = preloadItem.getValue("readerCoreMigrationScope").jsonObject
        assertEquals("WIRED", migrationScope.requiredText("androidMaterializeExecutor"))
        assertEquals("RC-03", migrationScope.requiredText("schedulerTask"))
        assertEquals("WIRED", migrationScope.requiredText("androidScheduler"))
        assertEquals("WIRED", migrationScope.requiredText("desktopSchedulerAdapter"))
        assertEquals("WIRED", migrationScope.requiredText("androidEncodedStore"))
        assertEquals(
            emptySet<String>(),
            migrationScope.getValue("openProductGaps").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertEquals(
            setOf("HTTP_RETRY_FORCE_DRIFT"),
            migrationScope.getValue("closedProductGaps").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertTrue(preloadItem.requiredText("verificationScope").contains("explicit Retry forces a fresh fetch"))

        val chapterWindowItem = items.getValue(47).jsonObject
        val chapterWindowScope = chapterWindowItem.getValue("readerCoreMigrationScope").jsonObject
        assertEquals("RC-04", chapterWindowScope.requiredText("windowTask"))
        assertEquals("WIRED", chapterWindowScope.requiredText("sharedChapterWindow"))
        assertEquals("WIRED", chapterWindowScope.requiredText("androidChapterWindow"))
        assertEquals("NOT_WIRED", chapterWindowScope.requiredText("desktopChapterWindow"))
        assertTrue(
            chapterWindowItem.getValue("sharedImplementationPaths").jsonArray.any {
                it.jsonPrimitive.content ==
                    "domain/src/commonMain/kotlin/mihon/domain/reader/session/ReaderChapterWindow.kt"
            },
        )
        assertTrue(
            chapterWindowItem.getValue("behaviorMethods").jsonObject
                .getValue(
                    "app/src/test/java/eu/kanade/tachiyomi/ui/reader/loader/" +
                        "ChapterLoaderWindowEffectIntegrationTest.kt",
                ).jsonArray
                .any {
                    it.jsonPrimitive.content ==
                        "stale prefetch effect cannot restart a chapter after it leaves the retained window"
                },
        )
        assertTrue(chapterWindowItem.requiredText("verificationScope").contains("retain-before-release"))
        assertTrue(chapterWindowItem.requiredText("verificationScope").contains("stale window effects"))
    }

    @Test
    fun `shared scheduler is the sole preload policy consumed by Android and Desktop adapters`() {
        val sharedScheduler = Files.readString(
            repositoryRoot.resolve("domain/src/commonMain/kotlin/mihon/domain/reader/scheduler/ReaderRequestScheduler.kt"),
        )
        val legacyPageModel = Files.readString(
            repositoryRoot.resolve("domain/src/commonMain/kotlin/mihon/domain/reader/ReaderPageModel.kt"),
        )
        val androidAdapter = Files.readString(
            repositoryRoot.resolve("app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoader.kt"),
        )
        val desktopAdapter = Files.readString(
            repositoryRoot.resolve("app-desktop/src/main/kotlin/mihon/desktop/reader/PagePreloader.kt"),
        )

        assertTrue(sharedScheduler.contains("class ReaderRequestScheduler"))
        assertFalse(legacyPageModel.contains("ReaderPreloadPlanner"))
        assertTrue(androidAdapter.contains("requestScheduler.pollNext()"))
        assertFalse(androidAdapter.contains("PriorityBlockingQueue"))
        assertTrue(desktopAdapter.contains("requestScheduler.pollNext()"))
        assertFalse(desktopAdapter.contains("ReaderPreloadPlanner"))
    }

    private fun validateFixture(source: String) {
        val fixture = Json.parseToJsonElement(source).jsonObject
        assertEquals(
            setOf(
                "fixedOriginalRef",
                "fixedOriginalUpstreamLineageBase",
                "trackedUpstreamRef",
                "forkCompatibilityBaseline",
                "symbols",
                "behaviorVectors",
                "deviations",
                "trackedUpstreamChanges",
            ),
            fixture.keys,
        )
        assertEquals(FIXED_MAIN_REF, fixture.requiredText("fixedOriginalRef"))
        assertEquals(FIXED_UPSTREAM_LINEAGE_BASE, fixture.requiredText("fixedOriginalUpstreamLineageBase"))
        assertEquals(TRACKED_UPSTREAM_REF, fixture.requiredText("trackedUpstreamRef"))
        assertEquals(FORK_COMPATIBILITY_BASELINE, fixture.requiredText("forkCompatibilityBaseline"))

        val inventory = inventory()
        assertEquals(REQUIRED_AUTHORITY_BLOBS.keys, REQUIRED_AUTHORITY_MARKERS.keys)
        val symbols = fixture.getValue("symbols").jsonArray.map { it.jsonObject }
        val paths = symbols.map { it.requiredText("path") }
        assertEquals(REQUIRED_AUTHORITY_PATHS, paths.toSet())
        assertEquals(paths.size, paths.toSet().size, "Reader authority paths must be unique")
        symbols.forEach { symbol ->
            val path = symbol.requiredText("path")
            assertEquals(REQUIRED_AUTHORITY_BLOBS.getValue(path), inventory[path], "Wrong inventory blob for $path")
            assertEquals(REQUIRED_AUTHORITY_BLOBS.getValue(path), symbol.requiredText("blobId"), "Wrong fixture blob for $path")
            assertTrue(symbol.requiredText("responsibility").isNotBlank())
            val markers = symbol.getValue("markers").jsonArray.map { it.jsonPrimitive.content }
            assertTrue(markers.isNotEmpty(), "$path must pin at least one symbol marker")
            assertTrue(markers.all(String::isNotBlank), "$path contains a blank symbol marker")
            assertTrue(REQUIRED_AUTHORITY_MARKERS.getValue(path) in markers, "$path is missing its canonical symbol marker")
            REQUIRED_BEHAVIOR_MARKERS[path].orEmpty().forEach { marker ->
                assertTrue(marker in markers, "$path is missing required behavior marker $marker")
            }
        }

        val vectors = fixture.getValue("behaviorVectors").jsonArray.map { it.jsonObject }
        val vectorIds = vectors.associateBy { it.requiredText("id") }
        assertEquals(REQUIRED_FIXED_BEHAVIOR_VECTORS, vectorIds.keys)
        vectorIds.values.forEach { vector ->
            assertEquals("FIXED_ORIGINAL", vector.requiredText("classification"))
            assertTrue(vector.requiredText("sourcePath") in REQUIRED_AUTHORITY_PATHS)
            assertTrue(vector.requiredText("contract").isNotBlank())
        }
        assertEquals("4", vectorIds.getValue("CURRENT_PLUS_FOUR").requiredText("value"))
        assertEquals("5", vectorIds.getValue("ADJACENT_PAGE_LIST_THRESHOLD").requiredText("value"))
        assertEquals("LAST_LOGICAL_PAGE", vectorIds.getValue("LAST_PAGE_COMPLETION").requiredText("value"))

        val deviations = fixture.getValue("deviations").jsonArray.map { it.jsonObject }
            .associateBy { it.requiredText("id") }
        assertEquals(REQUIRED_DEVIATIONS, deviations.keys)
        assertEquals(REQUIRED_DEVIATION_EVIDENCE.keys, deviations.keys)
        REQUIRED_DEVIATION_EVIDENCE.forEach { (id, evidenceRef) ->
            assertEquals(evidenceRef, deviations.getValue(id).requiredText("evidenceRef"), "$id evidence ref")
        }
        assertEquals(
            "CROSS_PLATFORM_RELIABILITY_ENHANCEMENT",
            deviations.getValue("GENERATION_HARDENING").requiredText("classification"),
        )
        assertEquals(
            "CROSS_PLATFORM_PRODUCT_ENHANCEMENT",
            deviations.getValue("ADJACENT_PORTRAIT_PAIRING").requiredText("classification"),
        )
        assertEquals("PRODUCT_GAP", deviations.getValue("HTTP_RETRY_FORCE_DRIFT").requiredText("classification"))
        assertEquals("PRODUCT_GAP", deviations.getValue("DUAL_PAGE_PROGRESS_FIRST_ONLY").requiredText("classification"))
        deviations.values.forEach { deviation ->
            assertTrue(deviation.requiredText("description").isNotBlank())
            assertFalse(deviation.requiredText("evidenceRef").startsWith(FIXED_MAIN_REF))
        }

        val trackedChanges = fixture.getValue("trackedUpstreamChanges").jsonArray.map { it.jsonObject }
            .associateBy { it.requiredText("commit") }
        assertEquals(REQUIRED_TRACKED_UPSTREAM_CHANGES, trackedChanges.keys)
        trackedChanges.values.forEach { entry ->
            assertTrue(entry.requiredText("commit").matches(Regex("[0-9a-f]{40}")))
            assertTrue(
                entry.requiredText("classification") in
                    setOf("NO_SEMANTIC_CHANGE", "UPSTREAM_READER_FIX", "UPSTREAM_REFACTOR", "UPSTREAM_READER_FEATURE"),
            )
            assertTrue(
                entry.requiredText("coreContractImpact") in
                    setOf("NONE", "LIFECYCLE_REVIEW", "PRESENTATION_REVIEW", "MATERIALIZATION_REVIEW"),
            )
            assertTrue(entry.requiredText("summary").isNotBlank())
        }
    }

    private fun inventory(): Map<String, String> {
        val root = Json.parseToJsonElement(Files.readString(inventoryPath)).jsonObject
        assertEquals(FIXED_MAIN_REF, root.requiredText("upstreamRef"))
        return root.getValue("paths").jsonArray.associate { entry ->
            entry.jsonObject.let { it.requiredText("path") to it.requiredText("blobId") }
        }
    }

    private fun behaviorVector(id: String): JsonObject =
        Json.parseToJsonElement(Files.readString(fixturePath)).jsonObject
            .getValue("behaviorVectors").jsonArray
            .map { it.jsonObject }
            .single { it.requiredText("id") == id }

    private fun JsonObject.requiredText(key: String): String =
        getValue(key).jsonPrimitive.content.also { assertTrue(it.isNotBlank(), "$key must not be blank") }

    private fun fixedSource(path: String): String =
        runGit("cat-file", "blob", REQUIRED_AUTHORITY_BLOBS.getValue(path)).joinToString("\n")

    private fun sourceAt(commit: String, path: String): String =
        runGit("show", "$commit:$path").joinToString("\n")

    private fun fixedFunctionBody(path: String, signature: String): String = functionBody(fixedSource(path), signature)

    private fun functionBody(source: String, signature: String): String {
        val signatureStart = source.indexOf(signature)
        assertTrue(signatureStart >= 0, "Missing function signature $signature")
        val bodyStart = source.indexOf('{', signatureStart)
        assertTrue(bodyStart >= 0, "Missing function body for $signature")
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(bodyStart, index + 1)
                }
            }
        }
        throw AssertionError("Unclosed function body for $signature")
    }

    private fun runGit(vararg arguments: String): List<String> {
        val process = ProcessBuilder("git", *arguments)
            .directory(repositoryRoot.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readLines() }
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")} failed:\n${output.joinToString("\n")}")
        return output
    }

    private fun repositoryRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("docs")) }

    private companion object {
        const val FIXED_MAIN_REF = "main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8"
        const val FIXED_UPSTREAM_LINEAGE_BASE = "upstream/main@8e0c911f93e60db35dcbe2a9103ac6ea0d803e29"
        const val TRACKED_UPSTREAM_REF = "upstream/main@d7f3ceef5c75294306d0d9495e9ebc5ffca96302"
        const val FORK_COMPATIBILITY_BASELINE = "9111d70a85565e20940fa4736c97eea8c1a44a0d"
        const val READER_AUTHORITY_FIXTURE = "app-desktop/src/test/resources/parity/fixed-main-reader-fixtures.json"
        const val FIXED_MAIN_INVENTORY = "app-desktop/src/test/resources/parity/fixed-main-path-inventory.json"
        const val PARITY_MANIFEST = "app-desktop/src/test/resources/parity/parity-manifest.json"
        const val READER_VIEW_MODEL_PATH = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt"
        const val READER_ACTIVITY_PATH = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt"
        const val CHAPTER_LOADER_PATH = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/ChapterLoader.kt"
        const val HTTP_PAGE_LOADER_PATH = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoader.kt"
        const val PAGER_VIEWER_PATH = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerViewer.kt"
        const val WEBTOON_VIEWER_PATH = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonViewer.kt"
        const val DUAL_PAGE_VIEWER_PATH =
            "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/DualPageR2LPagerViewer.kt"

        val ACTUAL_IMAGE_MATERIALIZATION_CALLS = setOf("loadPage(", "getImageUrl(", "getImage(")
        val TRANSITION_REQUIRED_MARKERS =
            setOf(
                "is ReaderChapter.State.Loading -> setLoading()",
                "is ReaderChapter.State.Error -> setError(",
                "is ReaderChapter.State.Wait, is ReaderChapter.State.Loaded",
                "// No additional view is added",
                "MR.strings.action_retry",
            )
        val TRANSITION_FORBIDDEN_ACTION_MARKERS =
            setOf("MR.strings.action_continue", "MR.strings.action_cancel", "MR.strings.action_dismiss")

        val REQUIRED_DEVIATION_EVIDENCE =
            mapOf(
                "GENERATION_HARDENING" to "83c5a97f67fcea33367f5f79c09397bb215a2f6f",
                "ADJACENT_PORTRAIT_PAIRING" to "bef51fc6924c6a9de185fa0fb2a56ce76309dc19",
                "DESKTOP_FULL_NEXT_CHAPTER_PREFETCH" to "planned:RD-02",
                "HTTP_RETRY_FORCE_DRIFT" to FORK_COMPATIBILITY_BASELINE,
                "DUAL_PAGE_PROGRESS_FIRST_ONLY" to FORK_COMPATIBILITY_BASELINE,
            )
        val DEVIATION_INTRODUCTION_PATHS =
            mapOf(
                "GENERATION_HARDENING" to
                    setOf("domain/src/commonMain/kotlin/mihon/domain/reader/ReaderPageModel.kt"),
                "ADJACENT_PORTRAIT_PAIRING" to
                    setOf(
                        "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagePairingAlgorithm.kt",
                        "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PairingState.kt",
                        "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/DualPageViewerAdapter.kt",
                    ),
            )
        val DEVIATION_BASELINE_PATHS =
            mapOf(
                "HTTP_RETRY_FORCE_DRIFT" to HTTP_PAGE_LOADER_PATH,
                "DUAL_PAGE_PROGRESS_FIRST_ONLY" to DUAL_PAGE_VIEWER_PATH,
            )
        val DEVIATION_BASELINE_REQUIRED_MARKERS =
            mapOf(
                "HTTP_RETRY_FORCE_DRIFT" to
                    setOf(
                        "if (!chapterCache.isImageInCache(imageUrl))",
                        "val imageResponse = source.getImage(page)",
                    ),
                "DUAL_PAGE_PROGRESS_FIRST_ONLY" to
                    setOf("val page = displayPage.firstPage", "activity.onPageSelected(page)"),
            )
        val DEVIATION_BASELINE_FORBIDDEN_MARKERS =
            mapOf(
                "HTTP_RETRY_FORCE_DRIFT" to setOf("force = it.priority == PriorityPage.RETRY"),
                "DUAL_PAGE_PROGRESS_FIRST_ONLY" to setOf("activity.onPageSelected(displayPage"),
            )

        val REQUIRED_AUTHORITY_BLOBS =
            mapOf(
                "app/src/main/java/eu/kanade/tachiyomi/data/cache/ChapterCache.kt" to "1d47f5b7d7acbc89e4a9722cd1caf0ebbf1e78fd",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt" to "4c227636a85e44c8abb35e6b73ec524a54931b41",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt" to "830c6ccc245326150a008a990d954bdbe8378296",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/ArchivePageLoader.kt" to "9332c8a0ca8fd7aad9fe35862cc597c1fd30db9b",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/ChapterLoader.kt" to "761c212a12a2b4f202b077494f33ffde8746f4e2",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/DirectoryPageLoader.kt" to "adb92c1b4fb2d3f6ee0fb8823dea775403e64072",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/DownloadPageLoader.kt" to "026faac3e3672cd6b71f9014f8df1dc3a1fce5e7",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/EpubPageLoader.kt" to "5139fe2b7d15872217d12bfb97bed84fa5d8fb08",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoader.kt" to "81530afa36ad08b037af44275777d37da608faaf",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/PageLoader.kt" to "164de6bda4d710c12e228437f0e311c4ee05d168",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ChapterTransition.kt" to "2da46e5b0a5ff4f3c2fe1eb3e615ca528917d366",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/InsertPage.kt" to "40bb702f142bf800231f77001710d8bdbdf0de45",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ReaderChapter.kt" to "cb3bca256878a56612f43980ee837c313c742b5c",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ReaderPage.kt" to "6602b96185a2c1f8cedc9163907c9fef1f35f816",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ViewerChapters.kt" to "6fb5905c3356ca694d6a8f33215cf331e59b1ce1",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerTransitionHolder.kt" to "4569f43700281b6b24694e9e859f30c5e7f47fe9",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerViewer.kt" to "1d39507b31cc56c178110800a7d8257e53d25a26",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerViewerAdapter.kt" to "0fef0b8bc0c42b13b704ebd0c5de77b1cce26426",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonAdapter.kt" to "29727f696845dae7b8d1df9b1581b84aeb8d5671",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonTransitionHolder.kt" to "57036d070d058fe3bed425d117deffebd87c3075",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonViewer.kt" to "33d21d1eba913728ca1b9770855400166fafee78",
                "source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/model/Page.kt" to "1119850439480370948570b61d61055b7a546562",
            )
        val REQUIRED_AUTHORITY_MARKERS =
            mapOf(
                "app/src/main/java/eu/kanade/tachiyomi/data/cache/ChapterCache.kt" to "class ChapterCache(",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt" to "class ReaderViewModel @JvmOverloads constructor(",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt" to "class ReaderActivity : BaseActivity()",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/ArchivePageLoader.kt" to "internal class ArchivePageLoader",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/ChapterLoader.kt" to "class ChapterLoader(",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/DirectoryPageLoader.kt" to "internal class DirectoryPageLoader",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/DownloadPageLoader.kt" to "internal class DownloadPageLoader(",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/EpubPageLoader.kt" to "internal class EpubPageLoader",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoader.kt" to "internal class HttpPageLoader(",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/PageLoader.kt" to "abstract class PageLoader",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ChapterTransition.kt" to "sealed class ChapterTransition",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/InsertPage.kt" to "class InsertPage(val parent: ReaderPage)",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ReaderChapter.kt" to "data class ReaderChapter(val chapter: Chapter)",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ReaderPage.kt" to "open class ReaderPage(",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ViewerChapters.kt" to "data class ViewerChapters(",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerTransitionHolder.kt" to "class PagerTransitionHolder(",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerViewer.kt" to "abstract class PagerViewer(val activity: ReaderActivity)",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerViewerAdapter.kt" to "class PagerViewerAdapter(private val viewer: PagerViewer)",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonAdapter.kt" to "class WebtoonAdapter(val viewer: WebtoonViewer)",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonTransitionHolder.kt" to "class WebtoonTransitionHolder(",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonViewer.kt" to "class WebtoonViewer(val activity: ReaderActivity, val isContinuous: Boolean = true)",
                "source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/model/Page.kt" to "open class Page(",
            )
        val REQUIRED_BEHAVIOR_MARKERS =
            mapOf(
                READER_VIEW_MODEL_PATH to
                    setOf(
                        "suspend fun preload(chapter: ReaderChapter)",
                        "loader.loadChapter(chapter)",
                        "private suspend fun updateChapterProgress(",
                        "readerChapter.pages?.lastIndex == pageIndex",
                    ),
                READER_ACTIVITY_PATH to
                    setOf(
                        "fun requestPreloadChapter(chapter: ReaderChapter)",
                        "viewModel.preload(chapter)",
                    ),
                CHAPTER_LOADER_PATH to
                    setOf(
                        "chapter.state = ReaderChapter.State.Loading",
                        "val pages = loader.getPages()",
                        "chapter.state = ReaderChapter.State.Loaded(pages)",
                        "chapter.state = ReaderChapter.State.Error(e)",
                    ),
                HTTP_PAGE_LOADER_PATH to
                    setOf(
                        "private val queue = PriorityBlockingQueue<PriorityPage>()",
                        "private val preloadSize = 4",
                        "queuedPages += preloadNextPages(page, preloadSize)",
                        "force = it.priority == PriorityPage.RETRY",
                        "if (force || !chapterCache.isImageInCache(imageUrl))",
                    ),
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ReaderChapter.kt" to
                    setOf(
                        "fun ref()",
                        "fun unref()",
                        "data object Wait : State",
                        "data object Loading : State",
                        "data class Error(val error: Throwable) : State",
                        "data class Loaded(val pages: List<ReaderPage>) : State",
                    ),
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ViewerChapters.kt" to
                    setOf("val currChapter: ReaderChapter", "fun ref()", "fun unref()"),
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerTransitionHolder.kt" to
                    setOf(
                        "is ReaderChapter.State.Loading -> setLoading()",
                        "is ReaderChapter.State.Error -> setError(state.error)",
                        "is ReaderChapter.State.Wait, is ReaderChapter.State.Loaded",
                        "// No additional view is added",
                        "MR.strings.action_retry",
                    ),
                PAGER_VIEWER_PATH to
                    setOf(
                        "val inPreloadRange = pages.size - page.number < 5",
                        "adapter.nextTransition?.to?.let(activity::requestPreloadChapter)",
                    ),
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerViewerAdapter.kt" to
                    setOf("nextTransition = ChapterTransition.Next", "chapters.nextChapter?.pages?.let(newItems::addAll)"),
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonAdapter.kt" to
                    setOf("newItems.add(ChapterTransition.Next", "chapters.nextChapter?.pages?.let(newItems::addAll)"),
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonTransitionHolder.kt" to
                    setOf(
                        "is ReaderChapter.State.Loading -> setLoading()",
                        "is ReaderChapter.State.Error -> setError(state.error, transition)",
                        "is ReaderChapter.State.Wait, is ReaderChapter.State.Loaded",
                        "// No additional view is added",
                        "MR.strings.action_retry",
                    ),
                WEBTOON_VIEWER_PATH to
                    setOf(
                        "val inPreloadRange = pages.size - page.number < 5",
                        "activity.requestPreloadChapter(transitionChapter)",
                    ),
                "source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/model/Page.kt" to
                    setOf(
                        "data object Queue : State",
                        "data object LoadPage : State",
                        "data object DownloadImage : State",
                        "data object Ready : State",
                        "data class Error(val error: Throwable) : State",
                    ),
            )
        val REQUIRED_AUTHORITY_PATHS = REQUIRED_AUTHORITY_BLOBS.keys
        val REQUIRED_FIXED_BEHAVIOR_VECTORS =
            setOf(
                "CURRENT_PLUS_FOUR",
                "ADJACENT_PAGE_LIST_THRESHOLD",
                "LAST_PAGE_COMPLETION",
                "PAGE_STATE_MACHINE",
                "CHAPTER_WINDOW",
                "PAGER_TRANSITION",
                "WEBTOON_TRANSITION",
            )
        val ADJACENT_PAGE_LIST_PATH_CHAIN =
            listOf(
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerViewer.kt",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonViewer.kt",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/ChapterLoader.kt",
                "app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/PageLoader.kt",
            )
        val ADJACENT_PAGE_LIST_FORBIDDEN_OPERATIONS = setOf("PageLoader.loadPage", "HttpSource.getImageUrl", "HttpSource.getImage")
        val REQUIRED_DEVIATIONS =
            setOf(
                "GENERATION_HARDENING",
                "ADJACENT_PORTRAIT_PAIRING",
                "DESKTOP_FULL_NEXT_CHAPTER_PREFETCH",
                "HTTP_RETRY_FORCE_DRIFT",
                "DUAL_PAGE_PROGRESS_FIRST_ONLY",
            )
        val REQUIRED_TRACKED_UPSTREAM_CHANGES =
            setOf(
                "6d69903a561b3b9445578eb9b2479f064f4926e8",
                "9c3cc1ca5f0011c4b3638d8f5272080d219bfb2e",
                "430b13bb81c8f51331109fa3ff35296f8bde9d27",
                "4c37f4c764afc47e0be6c63b1389b54f240b03f5",
                "d8c3440d3793573c1ea52b85d00a6ced03983668",
                "6981185f4ba1cf92ad1f84e7721b670ce1c36716",
                "47d7c0f2ea3349c586008db4da3ae1fd571d28ab",
                "98bb731b4ddbba9743debeb4442e9346bac48d68",
                "c3b99aea0723bc9b34a4c71afb2b12d654c09ef7",
                "b4635c41a8dd5e30edf480b0c9bdc80d0fda0520",
                "44780e74b5579dbb23a648f7e1b5c6cda6454d9f",
                "80541831bb02f893f4ff56670a738b25452ea17d",
                "a6f0cec08383fb8c6720b8633badd045e77793a0",
                "6ae79d255dbb581c06cd0d637e1e255ac81d54f2",
                "4b9974e20a88c3acc3be229089c0eada71b71a47",
                "509eee5dfb81266ea7056587f695910506248a8a",
                "a330258f6a26a57ceca9a318270b5db3b4653e24",
                "bc7f7e70a1de65f1f966e2e31f97457f8ac16ce6",
            )
        val READER_PARITY_SLICES =
            mapOf(
                9 to "PLATFORM_DECODE_CONTRACT",
                43 to "PRESENTATION_TRANSFORM_CONTRACT",
                44 to "PLATFORM_DECODE_BUDGET_CONTRACT",
                45 to "PRIORITY_SCHEDULER_AND_ENCODED_STORE_CONTRACT",
                47 to "CHAPTER_WINDOW_AND_TRANSITION",
                49 to "INPUT_NAVIGATION_CONTRACT",
                51 to "COLOR_FILTER_CONTRACT",
                54 to "CHAPTER_FILTER_NAVIGATION",
            )
    }
}
