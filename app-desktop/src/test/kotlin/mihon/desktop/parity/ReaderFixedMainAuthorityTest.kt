package mihon.desktop.parity

import java.nio.file.Files
import java.nio.file.Path
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
import mihon.domain.reader.progress.ReaderProgressPolicy
import mihon.domain.reader.progress.ReaderProgressSignal
import mihon.domain.reader.scheduler.ReaderRequestScheduler
import mihon.domain.reader.scheduler.ReaderSchedulerPolicy
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId

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
            when {
                evidenceRef.startsWith("production:") -> {
                    val artifact = evidenceRef.removePrefix("production:")
                    val parts = artifact.split('#', limit = 2)
                    assertEquals(2, parts.size, "$id production evidence artifact")
                    val source = Files.readString(repositoryRoot.resolve(parts[0]))
                    assertTrue(source.contains("fun `${parts[1]}`"), "$id production evidence method")
                }
                evidenceRef.startsWith("planned:") -> assertEquals("planned:RD-02", evidenceRef)
                else -> {
                    assertEquals("commit", runGit("cat-file", "-t", evidenceRef).single(), "$id evidence type")
                    runGit("merge-base", "--is-ancestor", evidenceRef, "HEAD")
                }
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
        val chapterId = ReaderChapterId(1)

        val penultimate = requireNotNull(
            ReaderProgressPolicy.reduce(
                ReaderProgressSignal.ViewportSettled(
                    activeChapterId = chapterId,
                    chapterId = chapterId,
                    visiblePageIds = setOf(ReaderPageId(chapterId, 8)),
                    totalPages = 10,
                    wasRead = false,
                    sessionId = "authority",
                    settlementSequence = 1,
                ),
            ),
        )
        val last = requireNotNull(
            ReaderProgressPolicy.reduce(
                ReaderProgressSignal.ViewportSettled(
                    activeChapterId = chapterId,
                    chapterId = chapterId,
                    visiblePageIds = setOf(ReaderPageId(chapterId, 9)),
                    totalPages = 10,
                    wasRead = false,
                    sessionId = "authority",
                    settlementSequence = 2,
                ),
            ),
        )

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
    fun `RD01 records the canonical Desktop reader session cutover`() {
        val items = Json.parseToJsonElement(Files.readString(manifestPath)).jsonArray
            .associateBy { it.jsonObject.getValue("id").jsonPrimitive.content.toInt() }
        val desktopCoreContracts =
            mapOf(
                45 to "app-desktop/src/test/kotlin/mihon/desktop/reader/DesktopReaderRuntimeFactoryTest.kt",
                47 to "app-desktop/src/test/kotlin/mihon/desktop/reader/DesktopReaderRuntimeFactoryTest.kt",
                53 to "app-desktop/src/test/kotlin/mihon/desktop/reader/DesktopReaderSessionIntegrationTest.kt",
            )

        assertEquals(READER_PARITY_SLICES.keys, items.keys.intersect(READER_PARITY_SLICES.keys))
        READER_PARITY_SLICES.forEach { (id, verifiedSlice) ->
            val item = items.getValue(id).jsonObject
            val scope = item.getValue("readerCoreMigrationScope").jsonObject

            assertEquals("R0-01", scope.requiredText("task"), "ID $id migration audit owner")
            assertEquals(verifiedSlice, scope.requiredText("verifiedSlice"), "ID $id verified slice")
            assertEquals("WIRED", scope.requiredText("canonicalSessionExecutor"), "ID $id canonical executor state")
            assertEquals("RD-01", scope.requiredText("closureTask"), "ID $id canonical executor closure")
            assertEquals("RD-01", scope.requiredText("desktopCoreCutoverTask"), "ID $id Desktop cutover owner")
            assertEquals("WIRED", scope.requiredText("desktopCanonicalSession"), "ID $id Desktop canonical session")
            assertTrue(
                item.requiredText("verificationScope").contains("Desktop canonical ReaderSessionCore"),
                "ID $id must record the integrated Desktop canonical session evidence",
            )
            assertFalse(item.requiredText("verificationScope").contains("does not prove shared ReaderSessionCore"))
            desktopCoreContracts[id]?.let { desktopCoreContract ->
                assertTrue(item.getValue("protectionTests").jsonArray.any { it.jsonPrimitive.content == desktopCoreContract })
            }
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
        assertEquals(
            setOf("GENERATION_HARDENING", "DESKTOP_FULL_NEXT_CHAPTER_PREFETCH", "HTTP_RETRY_FORCE_DRIFT"),
            preloadDeviations.keys,
        )
        assertEquals(
            "CROSS_PLATFORM_RELIABILITY_ENHANCEMENT",
            preloadDeviations.getValue("GENERATION_HARDENING").requiredText("classification"),
        )
        assertEquals(
            "DESKTOP_PRODUCT_ENHANCEMENT",
            preloadDeviations.getValue("DESKTOP_FULL_NEXT_CHAPTER_PREFETCH").requiredText("classification"),
        )
        assertEquals(
            RD02_PRODUCTION_EVIDENCE,
            preloadDeviations.getValue("DESKTOP_FULL_NEXT_CHAPTER_PREFETCH").requiredText("evidenceRef"),
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
        assertEquals("WIRED", migrationScope.requiredText("desktopMaterializeExecutor"))
        assertEquals("WIRED", migrationScope.requiredText("desktopEncodedStore"))
        assertEquals("RA-01", migrationScope.requiredText("androidCoreCutoverTask"))
        assertEquals("WIRED", migrationScope.requiredText("androidCanonicalSession"))
        assertEquals("REMOVED", migrationScope.requiredText("androidLegacyChapterStateInput"))
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
        assertEquals("RC-01", chapterWindowScope.requiredText("sessionTask"))
        assertEquals("WIRED", chapterWindowScope.requiredText("sharedSessionState"))
        assertEquals("WIRED", chapterWindowScope.requiredText("androidSessionState"))
        assertEquals("WIRED", chapterWindowScope.requiredText("desktopSessionState"))
        assertEquals("WIRED", chapterWindowScope.requiredText("sharedChapterWindow"))
        assertEquals("WIRED", chapterWindowScope.requiredText("androidChapterWindow"))
        assertEquals("WIRED", chapterWindowScope.requiredText("desktopChapterWindow"))
        assertTrue(
            chapterWindowItem.getValue("sharedImplementationPaths").jsonArray.any {
                it.jsonPrimitive.content ==
                    "domain/src/commonMain/kotlin/mihon/domain/reader/session/ReaderChapterWindow.kt"
            },
        )
        assertTrue(
            chapterWindowItem.getValue("sharedImplementationPaths").jsonArray.any {
                it.jsonPrimitive.content == "domain/src/commonMain/kotlin/mihon/domain/reader/session/ReaderSession.kt"
            },
        )
        assertTrue(
            chapterWindowItem.getValue("sharedImplementationPaths").jsonArray.any {
                it.jsonPrimitive.content == "domain/src/commonMain/kotlin/mihon/domain/reader/session/ReaderSessionCore.kt"
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

        val progressItem = items.getValue(53).jsonObject
        val progressScope = progressItem.getValue("readerCoreMigrationScope").jsonObject
        assertEquals("RC-05", progressScope.requiredText("progressTask"))
        assertEquals("WIRED", progressScope.requiredText("sharedProgressPolicy"))
        assertEquals("WIRED", progressScope.requiredText("androidProgressExecutor"))
        assertEquals("WIRED", progressScope.requiredText("desktopProgressExecutor"))
        assertTrue(
            progressItem.getValue("sharedImplementationPaths").jsonArray.any {
                it.jsonPrimitive.content ==
                    "domain/src/commonMain/kotlin/mihon/domain/reader/progress/ReaderProgressPolicy.kt"
            },
        )
        assertTrue(
            progressItem.getValue("currentAndroidConsumerPaths").jsonArray.any {
                it.jsonPrimitive.content ==
                    "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewportSettlementArbiter.kt"
            },
        )
        assertTrue(
            progressItem.getValue("behaviorMethods").jsonObject
                .getValue("app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReaderProgressSettlementRaceTest.kt")
                .jsonArray
                .any {
                    it.jsonPrimitive.content ==
                        "newer current settlement rejects older adjacent activation and progress"
                },
        )
        assertTrue(
            progressItem.getValue("behaviorMethods").jsonObject
                .getValue("app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReaderViewportSettlementArbiterTest.kt")
                .jsonArray
                .any {
                    it.jsonPrimitive.content ==
                        "an in-flight write completes before the latest settlement enters the serialized transaction"
                },
        )
        assertTrue(progressItem.requiredText("verificationScope").contains("latest-settlement"))

        val androidCoreContract =
            "app/src/test/java/eu/kanade/tachiyomi/ui/reader/AndroidReaderCoreProductionContractTest.kt"
        val androidSessionContract =
            "app/src/test/java/eu/kanade/tachiyomi/ui/reader/model/ReaderSessionProductionWiringTest.kt"
        listOf(preloadItem, chapterWindowItem, progressItem).forEach { item ->
            assertTrue(item.getValue("protectionTests").jsonArray.any { it.jsonPrimitive.content == androidCoreContract })
            assertEquals(
                "RA-01",
                item.getValue("readerCoreMigrationScope").jsonObject.requiredText("androidCoreCutoverTask"),
            )
            assertEquals(
                "WIRED",
                item.getValue("readerCoreMigrationScope").jsonObject.requiredText("androidCanonicalSession"),
            )
        }
        assertTrue(
            preloadItem.getValue("behaviorMethods").jsonObject.getValue(androidCoreContract).jsonArray.any {
                it.jsonPrimitive.content ==
                    "online ReaderViewModel executes shared session scheduler encoded cache and progress chain"
            },
        )
        assertTrue(
            chapterWindowItem.getValue("behaviorMethods").jsonObject.getValue(androidCoreContract).jsonArray.any {
                it.jsonPrimitive.content == "legacy Android chapter state is a read only projection"
            },
        )
        assertTrue(
            chapterWindowItem.getValue("behaviorMethods").jsonObject.getValue(androidSessionContract).jsonArray.any {
                it.jsonPrimitive.content == "stale storage reset cannot recycle a newer activation loader"
            },
        )
        assertTrue(
            progressItem.getValue("behaviorMethods").jsonObject.getValue(androidCoreContract).jsonArray.any {
                it.jsonPrimitive.content == "ReaderViewModel initialization preserves cooperative cancellation"
            },
        )

        val entryItem = items.getValue(54).jsonObject
        val entryScope = entryItem.getValue("readerCoreMigrationScope").jsonObject
        assertEquals("RC-05", entryScope.requiredText("entryTask"))
        assertEquals("WIRED", entryScope.requiredText("sharedReaderEntryResolver"))
        assertEquals("WIRED", entryScope.requiredText("androidReaderEntry"))
        assertEquals("WIRED", entryScope.requiredText("desktopReaderEntry"))
        assertTrue(entryItem.requiredText("verificationScope").contains("reader entry selection"))
    }

    @Test
    fun `RD02 records bounded encoded next chapter prefetch as a Desktop policy`() {
        val fixture = Json.parseToJsonElement(Files.readString(fixturePath)).jsonObject
        val deviation = fixture.getValue("deviations").jsonArray
            .map { it.jsonObject }
            .single { it.requiredText("id") == "DESKTOP_FULL_NEXT_CHAPTER_PREFETCH" }
        assertEquals(RD02_PRODUCTION_EVIDENCE, deviation.requiredText("evidenceRef"))
        assertEquals("DESKTOP_PRODUCT_ENHANCEMENT", deviation.requiredText("classification"))
        assertFalse(deviation.requiredText("description").contains("planned", ignoreCase = true))

        val item = Json.parseToJsonElement(Files.readString(manifestPath)).jsonArray
            .single { it.jsonObject.getValue("id").jsonPrimitive.content.toInt() == 45 }
            .jsonObject
        val scope = item.getValue("readerCoreMigrationScope").jsonObject
        assertEquals("RD-02", scope.requiredText("desktopNextChapterPrefetchTask"))
        assertEquals("WIRED", scope.requiredText("desktopNextChapterPrefetch"))
        assertEquals("FULL_NEXT_CHAPTER", scope.requiredText("desktopNextChapterPrefetchDefault"))
        assertEquals("ORIGINAL_LAST_FIVE_PAGE_LIST_ONLY", scope.requiredText("desktopNextChapterPrefetchOffPolicy"))
        assertEquals("ENCODED_ONLY_BOUNDED", scope.requiredText("desktopNextChapterPrefetchStorage"))
        assertEquals("NO_PROGRESS_EFFECT", scope.requiredText("desktopNextChapterPrefetchProgress"))
        assertEquals("POLICY_PLUS_ONE_STALE", scope.requiredText("desktopNextChapterPhysicalRequestBound"))
        assertEquals("TARGET_SEQUENCE_AND_REQUEST_ID", scope.requiredText("desktopNextChapterStorageFailureGuard"))

        val desktopPaths = item.getValue("desktopConsumerAdapterPaths").jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()
        assertTrue("app-desktop/src/main/kotlin/mihon/desktop/reader/ReaderPreferences.kt" in desktopPaths)
        assertTrue("app-desktop/src/main/kotlin/mihon/desktop/ui/settings/ReaderSettingsScreen.kt" in desktopPaths)

        val expectedMethods =
            mapOf(
                "domain/src/commonTest/kotlin/mihon/domain/reader/session/ReaderSessionCoreTest.kt" to
                    setOf(
                        "adjacent background work uses P4 and an active viewport preempts it with P0",
                        "cancelling an adjacent chapter removes its active and pending requests only",
                    ),
                "app-desktop/src/test/kotlin/mihon/desktop/reader/DesktopReaderSessionIntegrationTest.kt" to
                    setOf(
                        "full next chapter waits for every current page then materializes all encoded pages without progress",
                        "first viewport mode materializes only its bounded next chapter prefix",
                        "off mode keeps last five page-list preload but never fetches adjacent images",
                        "switching off cancels a policy-only next chapter page-list request",
                        "activating a prefetched chapter cancels P4 and retries its visible page as P0",
                        "adjacent storage failure stops the remaining background chapter without changing active state",
                        "non cooperative page cancellation keeps physical image requests within policy plus one stale request",
                        "non cooperative target switches keep physical chapter requests within policy plus one stale request",
                        "late storage failure from an old target cannot cancel the new target prefetch",
                    ),
                "app-desktop/src/test/kotlin/mihon/desktop/reader/DesktopReaderRuntimeFactoryTest.kt" to
                    setOf(
                        "production runtime follows persisted next chapter prefetch changes",
                        "production runtime preference changes drive off first viewport and full request sets",
                    ),
                "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/DesktopReaderChapterTransitionIntegrationTest.kt" to
                    setOf("mounted production screen launches next chapter prefetch wiring"),
                "app-desktop/src/test/kotlin/mihon/desktop/reader/ReaderSettingsModelsTest.kt" to
                    setOf("next chapter prefetch defaults to full and persists every policy"),
                "app-desktop/src/test/kotlin/mihon/desktop/ui/settings/DesktopSettingsContentAccessibilityTest.kt" to
                    setOf("Reader Library Download and Backup controls expose one labeled action with role and state"),
            )
        val methods = item.getValue("behaviorMethods").jsonObject
        val protectionTests = item.getValue("protectionTests").jsonArray.map { it.jsonPrimitive.content }.toSet()
        expectedMethods.forEach { (path, expected) ->
            assertTrue(path in protectionTests, "ID 45 must protect RD-02 through $path")
            val actual = methods.getValue(path).jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertTrue(actual.containsAll(expected), "ID 45 RD-02 methods missing from $path: ${expected - actual}")
        }
        assertTrue(item.requiredText("desktopImplementation").contains("P4"))
        assertTrue(item.requiredText("desktopImplementation").contains("encoded-only"))
        assertTrue(item.requiredText("verificationScope").contains("OFF / FIRST_VIEWPORT / FULL_NEXT_CHAPTER"))
    }

    @Test
    fun `RD01 keeps all three Desktop presentation slices on canonical session state`() {
        val item = Json.parseToJsonElement(Files.readString(manifestPath)).jsonArray
            .single { it.jsonObject.getValue("id").jsonPrimitive.content.toInt() == 43 }
            .jsonObject
        val scope = item.getValue("readerCoreMigrationScope").jsonObject

        assertEquals("RP-03", scope.requiredText("presentationTask"))
        assertEquals("WIRED", scope.requiredText("desktopSinglePresentation"))
        assertEquals("WIRED", scope.requiredText("desktopWebtoonPresentation"))
        assertEquals("WIRED", scope.requiredText("desktopDualPresentation"))
        assertEquals("REMOVED", scope.requiredText("legacyDesktopStateAdapter"))
        assertEquals("WIRED", scope.requiredText("canonicalSessionExecutor"))

        val desktopPaths = item.getValue("desktopConsumerAdapterPaths").jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()
        val upstreamSymbols = item.getValue("upstreamSymbols").jsonArray.map { it.jsonObject }
        assertTrue(
            upstreamSymbols.any { symbol ->
                symbol.requiredText("path") ==
                    "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonLayoutManager.kt" &&
                    symbol.requiredText("symbol").contains("findLastEndVisibleItemPosition")
            },
        )
        assertTrue(
            "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/presentation/ReaderPresentation.kt" in desktopPaths,
        )
        assertTrue(
            "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/presentation/SinglePagedPresentation.kt" in desktopPaths,
        )
        assertTrue(
            "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/presentation/WebtoonPresentation.kt" in desktopPaths,
        )
        assertTrue(
            "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/presentation/DualPagedPresentation.kt" in desktopPaths,
        )
        assertTrue(
            "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/ReaderVisualComponents.kt" in desktopPaths,
        )
        assertTrue(
            "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/SinglePagePagerViewer.kt" in desktopPaths,
        )
        assertTrue(
            "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/WebtoonViewer.kt" in desktopPaths,
        )
        assertTrue(
            "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/DualPagePagerViewer.kt" in desktopPaths,
        )

        val behaviorMethods = item.getValue("behaviorMethods").jsonObject
        assertTrue(
            behaviorMethods
                .getValue(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/presentation/SinglePagedPresentationTest.kt",
                )
                .jsonArray
                .any { it.jsonPrimitive.content == "late content and load-state changes preserve display-unit identities" },
        )
        assertTrue(
            behaviorMethods
                .getValue(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/presentation/SinglePagePresentationIdentityTest.kt",
                )
                .jsonArray
                .any { it.jsonPrimitive.content == "production single-page selector mounts the SPI display unit" },
        )
        assertTrue(
            behaviorMethods
                .getValue(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/presentation/WebtoonPresentationTest.kt",
                )
                .jsonArray
                .any { it.jsonPrimitive.content == "viewport reports every visible page and uses fixed-main last end-visible active rule" },
        )
        assertTrue(
            behaviorMethods
                .getValue(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/presentation/WebtoonPresentationIdentityTest.kt",
                )
                .jsonArray
                .any { it.jsonPrimitive.content == "production webtoon selector mounts registry display units with stable lazy identities" },
        )
        assertTrue(
            behaviorMethods
                .getValue(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/presentation/DualPagedPresentationTest.kt",
                )
                .jsonArray
                .any { it.jsonPrimitive.content == "portrait pairs follow physical LTR and RTL slots while reporting both pages" },
        )
        assertTrue(
            behaviorMethods
                .getValue(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/presentation/DualPagePresentationIdentityTest.kt",
                )
                .jsonArray
                .any {
                    it.jsonPrimitive.content ==
                        "mounted cover keeps a centered two-slot frame with the page in the physical left slot"
                },
        )
        assertTrue(item.requiredText("verificationScope").contains("Desktop canonical ReaderSessionCore"))
        assertTrue(item.requiredText("verificationScope").contains("three same-level presentation strategies"))

        val singlePresentation = Files.readString(
            repositoryRoot.resolve(
                "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/presentation/SinglePagedPresentation.kt",
            ),
        )
        val readerState = Files.readString(
            repositoryRoot.resolve("app-desktop/src/main/kotlin/mihon/desktop/ui/reader/ReaderState.kt"),
        )
        val readerScreen = Files.readString(
            repositoryRoot.resolve("app-desktop/src/main/kotlin/mihon/desktop/ui/reader/DesktopReaderScreen.kt"),
        )
        assertFalse(singlePresentation.contains("LegacyDesktopReaderPresentationAdapter"))
        assertFalse(readerState.contains("resolvedUrls"))
        assertTrue(readerScreen.contains("runtimeFactory.createScreenModel("))
        assertFalse(readerScreen.contains("runtimeFactory.createRuntime("))
        assertFalse(readerScreen.contains("runtimeFactory.createModel("))
        assertTrue(
            Files.notExists(
                repositoryRoot.resolve("app-desktop/src/main/kotlin/mihon/desktop/reader/DesktopReaderPageLoader.kt"),
            ),
        )
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
        const val TRACKED_UPSTREAM_REF = "upstream/main@55be95dd5df7ac985bbc68ea62a5a525611a732f"
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
                "DESKTOP_FULL_NEXT_CHAPTER_PREFETCH" to RD02_PRODUCTION_EVIDENCE,
                "HTTP_RETRY_FORCE_DRIFT" to FORK_COMPATIBILITY_BASELINE,
                "DUAL_PAGE_PROGRESS_FIRST_ONLY" to FORK_COMPATIBILITY_BASELINE,
            )
        const val RD02_PRODUCTION_EVIDENCE =
            "production:app-desktop/src/test/kotlin/mihon/desktop/reader/DesktopReaderSessionIntegrationTest.kt#" +
                "full next chapter waits for every current page then materializes all encoded pages without progress"
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
                53 to "PROGRESS_TRANSACTION_CONTRACT",
                54 to "CHAPTER_FILTER_NAVIGATION",
            )
    }
}
