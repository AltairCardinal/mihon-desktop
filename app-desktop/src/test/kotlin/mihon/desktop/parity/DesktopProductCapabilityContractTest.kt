package mihon.desktop.parity

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DesktopProductCapabilityContractTest {
    @TempDir
    lateinit var tempDir: Path

    private val expectedIds =
        setOf(
            3, 4, 7, 8, 9, 10, 11, 12, 16, 17, 19, 22, 24, 26, 28, 29,
            30, 32, 33, 34, 35, 36, 37, 38, 39, 40, 43, 44, 45, 47, 49, 51,
            53, 54, 56, 57, 59, 61, 62, 64, 66, 67, 68, 69, 70, 71, 72, 73,
            74, 81, 82, 83, 84, 85, 86, 87, 88, 90, 91, 92, 93, 94, 95, 96,
        )
    private val validStatuses = setOf("NOT_STARTED", "CHARACTERIZED", "SHARED", "WIRED", "VERIFIED", "EXEMPT")
    private val validTags =
        setOf(
            "SHARE-DIRECT",
            "SHARE-EXTRACT",
            "PLATFORM-ADAPTER",
            "DESKTOP-PRODUCT",
            "TEMP-COMPAT",
            "PLATFORM-EXEMPT",
        )
    private val desktopProductEvidence =
        setOf(
            "app-desktop/src/test/kotlin/mihon/desktop/ui/authors/AuthorDetailBehaviorTest.kt",
            "app-desktop/src/test/kotlin/mihon/desktop/domain/GetUpcomingMangaTest.kt",
            "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/DualPageLayoutPolicyTest.kt",
            "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/WebtoonAutoScrollTest.kt",
            "app-desktop/src/test/kotlin/mihon/desktop/extension/ApkToJarConverterTest.kt",
            "app-desktop/src/test/kotlin/mihon/desktop/test/navigation/TestNavigationControllerTest.kt",
            "app-desktop/src/test/kotlin/mihon/desktop/test/http/TestHttpServerJsonTest.kt",
        )
    private val expectedCapabilityEvidence =
        mapOf(
            34 to setOf("app-desktop/src/test/kotlin/mihon/desktop/extension/ApkToJarConverterTest.kt"),
            40 to setOf("app-desktop/src/test/kotlin/mihon/desktop/network/FlareSolverrClientTest.kt"),
            43 to
                setOf(
                    "app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReaderSharedParityWiringTest.kt",
                    "app-desktop/src/test/kotlin/mihon/desktop/reader/VirtualPageListTest.kt",
                    "app-desktop/src/test/kotlin/mihon/desktop/reader/EdgePixelMatcherTest.kt",
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/DesktopReaderProductRegressionTest.kt",
                ),
            49 to
                setOf(
                    "app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReaderSharedParityWiringTest.kt",
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/TapZoneTest.kt",
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/ReaderKeyboardNavigationPositionTest.kt",
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/DesktopReaderProductRegressionTest.kt",
                ),
        )
    private val readerBehaviorEvidence =
        mapOf(
            9 to
                mapOf(
                    "app/src/test/java/eu/kanade/tachiyomi/data/coil/AndroidReaderPageDecoderContractTest.kt" to
                        mapOf(
                            "production Tachiyomi decoder forwards reader request identity through decode" to
                                setOf("TachiyomiImageDecoder(", "decoder.decode()"),
                            "Android reader cache adapter keeps tiled pages out of Coil decoded caches" to
                                setOf("mapAndroidReaderCachePolicy", "PageDecodeCachePolicy.TILED_READER"),
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/reader/SkiaImageDecoderTest.kt" to
                        mapOf(
                            "Skia region adapter returns pixels from the requested PNG region" to
                                setOf("SkiaRegionPageDecoder().decodeRegion", "result.value.asSkiaBitmap()"),
                            "byte budgeted page cache rejects oversized decoded values without evicting entries" to
                                setOf("DesktopPageCache", "PageCacheCommitResult.REJECTED_OVERSIZED"),
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/reader/PagePreloaderTest.kt" to
                        mapOf(
                            "large pages are downsampled before entering the ordinary cache" to
                                setOf("PagePreloader(", "preloader.cacheSnapshot()"),
                        ),
                ),
            43 to
                mapOf(
                    "app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReaderSharedParityWiringTest.kt" to
                        mapOf(
                            "Android pairing adapter and shared default produce the same authoritative vectors" to
                                setOf("PagePairingAlgorithm.buildPairings", "ReaderPagePairing.build"),
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/reader/VirtualPageListTest.kt" to
                        mapOf(
                            "LTR single spread produces LEFT then RIGHT" to
                                setOf("buildVirtualPageList", "PageSplitHalf.LEFT", "PageSplitHalf.RIGHT"),
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/DesktopReaderProductRegressionTest.kt" to
                        mapOf(
                            "shared pairing keeps cover edge matching adjust and landscape parity enhancements" to
                                setOf("DualPageState(", "matchedPairs", "forcedSinglePages"),
                        ),
                ),
            44 to
                mapOf(
                    "app/src/test/java/eu/kanade/tachiyomi/data/coil/AndroidReaderPageDecoderContractTest.kt" to
                        mapOf(
                            "Android reader cache adapter keeps tiled pages out of Coil decoded caches" to
                                setOf("mapAndroidReaderCachePolicy", "PageDecodeCachePolicy.TILED_READER"),
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/reader/SkiaImageDecoderTest.kt" to
                        mapOf(
                            "Skia region adapter decodes only the requested tile" to
                                setOf("SkiaRegionPageDecoder().decodeRegion", "PixelBounds"),
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/reader/PagePreloaderTest.kt" to
                        mapOf(
                            "ordinary non large page path still enforces decoded dimension bounds" to
                                setOf("PagePreloader(", "maxDecodedWidth", "cacheSnapshot().usedBytes"),
                        ),
                ),
            45 to
                mapOf(
                    "app/src/test/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoaderIntegrationTest.kt" to
                        mapOf(
                            "rapid zero one two selection cancels stale jobs without error and reorders current first" to
                                setOf("fixture.loader.onPageSelected", "fixture.cancelled"),
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/reader/PagePreloaderTest.kt" to
                        mapOf(
                            "fast page change cancels stale preload and prevents a late cache write" to
                                setOf("preloader.preload", "preloader.cacheSnapshot().keys"),
                            "page change cancels every active or queued old generation request" to
                                setOf("PagePreloader(", "firstOld0Finished", "firstOld1Finished"),
                        ),
                ),
            47 to
                mapOf(
                    "app/src/test/java/eu/kanade/tachiyomi/ui/reader/model/ReaderChapterTransitionIntegrationTest.kt" to
                        mapOf(
                            "previous and next errors retain their own retry target" to
                                setOf("toSharedTransitionModel", "retryCommand()"),
                            "both chapter edges map to explicit shared boundaries without a target" to
                                setOf("ChapterTransition.Prev", "ChapterTransition.Next", "ChapterBoundary"),
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/DesktopReaderChapterTransitionIntegrationTest.kt" to
                        mapOf(
                            "production adjacent chain publishes loading error retry loaded and navigates with loaded pages" to
                                setOf("requestAdjacentChapterTransition", "retryChapterTransition", "destinationForChapterTransition"),
                            "both adjacent boundaries never invoke the production loader" to
                                setOf("requestAdjacentChapterTransition", "ChapterBoundary", "loadCalls"),
                        ),
                ),
            49 to
                mapOf(
                    "app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReaderSharedParityWiringTest.kt" to
                        mapOf(
                            "Android navigation adapters match every shared preset and inversion" to
                                setOf("ReaderNavigation.regions", "adapter.getNormalizedRegions()"),
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/TapZoneTest.kt" to
                        mapOf(
                            "tap regions delegate to the shared navigation presets" to
                                setOf("tapNavRegion", "NavigationMode.RightAndLeft", "TapNavRegion.MENU"),
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/ReaderKeyboardNavigationPositionTest.kt" to
                        mapOf(
                            "all tap presets move logical pages before mapping to LTR pager storage" to
                                setOf("tapNavRegion", "ReaderKeyboardAction.forPagerCommand"),
                        ),
                ),
            51 to
                mapOf(
                    "app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReaderSharedParityWiringTest.kt" to
                        mapOf(
                            "Android grayscale and invert preferences are mapped to the shared filter contract" to
                                setOf("buildAndroidLayerFilterParams", "params.isEffective"),
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/reader/ReaderSettingsModelsTest.kt" to
                        mapOf(
                            "grayscale and invert survive preference round trip" to
                                setOf("ReaderPreferences", "saveColorFilter", "loadColorFilter"),
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/DesktopReaderProductRegressionTest.kt" to
                        mapOf(
                            "grayscale and invert remain effective and persistable reader settings" to
                                setOf("ReaderColorFilter", "readerColorTransform"),
                        ),
                ),
            54 to
                mapOf(
                    "app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReaderSharedParityWiringTest.kt" to
                        mapOf(
                            "Android production chapter pipeline maps real chapter metadata before applying shared skip policy" to
                                setOf("filterAndroidReaderChapters", "ChapterSkipPolicy"),
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/reader/ReaderNavigatorTest.kt" to
                        mapOf(
                            "reader navigator combines read filtered and duplicate skip flags" to
                                setOf("ReaderNavigator(", "skipFilteredChapters", "skipDuplicateChapters"),
                            "reader navigator preserves an explicit boundary after skip rules exhaust candidates" to
                                setOf("nav.result", "ChapterNavigationResult.Boundary"),
                        ),
                ),
        )
    private val readerProductionDelegates =
        mapOf(
            9 to
                mapOf(
                    "domain/src/commonMain/kotlin/mihon/domain/reader/ReaderPageModel.kt" to setOf("interface PageDecoder"),
                    "app/src/main/java/eu/kanade/tachiyomi/data/coil/TachiyomiImageDecoder.kt" to
                        setOf("decodeWithSharedPageDecoder"),
                    "app-desktop/src/main/kotlin/mihon/desktop/reader/PagePreloader.kt" to
                        setOf("SkiaRegionPageDecoder", "DesktopPageCache"),
                ),
            43 to
                mapOf(
                    "domain/src/commonMain/kotlin/mihon/domain/reader/PageTransform.kt" to setOf("object ReaderPagePairing"),
                    "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagePairingAlgorithm.kt" to
                        setOf("ReaderPagePairing.build"),
                    "app-desktop/src/main/kotlin/mihon/desktop/reader/VirtualPageList.kt" to
                        setOf("buildVirtualReaderPages"),
                    "app-desktop/src/main/kotlin/mihon/desktop/reader/DualPageState.kt" to
                        setOf("ReaderPairingState", "PagePairingOptions"),
                ),
            44 to
                mapOf(
                    "domain/src/commonMain/kotlin/mihon/domain/reader/ReaderPageModel.kt" to setOf("interface RegionDecoder"),
                    "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt" to
                        setOf("applySharedReaderCachePolicy(PageDecodeCachePolicy.TILED_READER)"),
                    "app-desktop/src/main/kotlin/mihon/desktop/reader/DesktopPageDecoders.kt" to
                        setOf("class SkiaRegionPageDecoder", "ceilDiv"),
                    "app-desktop/src/main/kotlin/mihon/desktop/reader/PagePreloader.kt" to
                        setOf("regionDecoder.decodeRegion"),
                ),
            45 to
                mapOf(
                    "domain/src/commonMain/kotlin/mihon/domain/reader/ReaderPageModel.kt" to setOf("class ReaderPreloadPlanner"),
                    "app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoader.kt" to
                        setOf("preloadPlanner.moveTo"),
                    "app-desktop/src/main/kotlin/mihon/desktop/reader/PagePreloader.kt" to
                        setOf("planner.moveTo", "activeJobs"),
                ),
            47 to
                mapOf(
                    "domain/src/commonMain/kotlin/mihon/domain/reader/ReaderPageModel.kt" to
                        setOf("data class ReaderChapterTransitionModel"),
                    "app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ReaderChapter.kt" to
                        setOf("mutableSharedStateFlow", "ReaderChapterState.Error"),
                    "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerTransitionHolder.kt" to
                        setOf("chapter.sharedStateFlow"),
                    "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/ReaderScreenModel.kt" to
                        setOf("ReaderChapterTransitionModel("),
                    "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/DesktopReaderScreen.kt" to
                        setOf("ChapterTransitionFeedback(", "retryChapterTransition"),
                    "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/ReaderVisualComponents.kt" to
                        setOf("chapterTransitionPresentation", "Button(onClick = onContinue"),
                ),
            49 to
                mapOf(
                    "domain/src/commonMain/kotlin/mihon/domain/reader/ReaderNavigation.kt" to setOf("object ReaderNavigation"),
                    "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ViewerNavigation.kt" to
                        setOf("ReaderNavigation.regions"),
                    "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/TapZone.kt" to
                        setOf("ReaderNavigation.commandAt"),
                    "app-desktop/src/main/kotlin/mihon/desktop/reader/ReaderKeyboardAction.kt" to
                        setOf("ReaderNavigation.resolvePhysicalPageCommand"),
                ),
            51 to
                mapOf(
                    "domain/src/commonMain/kotlin/mihon/domain/reader/PageTransform.kt" to
                        setOf("data class ReaderColorFilterParams"),
                    "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt" to
                        setOf("buildAndroidReaderColorFilterParams(", "buildAndroidLayerFilterParams"),
                    "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/ReaderSettingsPanel.kt" to
                        setOf("grayscaleEnabled", "invertEnabled"),
                    "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/DesktopReaderScreen.kt" to
                        setOf("readerColorTransform(state.colorFilter)"),
                ),
            54 to
                mapOf(
                    "domain/src/commonMain/kotlin/mihon/domain/reader/ReaderNavigation.kt" to setOf("fun findAdjacentChapter"),
                    "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt" to
                        setOf("filterChaptersForReader("),
                    "app-desktop/src/main/kotlin/mihon/desktop/reader/ReaderNavigator.kt" to
                        setOf("findAdjacentChapter("),
                ),
        )
    private val task3aBehaviorEvidence =
        mapOf(
            16 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/library/LibraryCategoryBehaviorTest.kt" to
                        setOf("category dialog intents perform create rename reorder and delete through production DI"),
                    "app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt" to
                        setOf("测试配置入口使用隔离内存存储并解析实际依赖"),
                ),
            17 to
                mapOf(
                    "app/src/test/java/eu/kanade/tachiyomi/ui/library/LibrarySharedEvaluationWiringTest.kt" to
                        setOf("Android library production model owns the shared evaluator"),
                    "domain/src/commonTest/kotlin/tachiyomi/domain/library/interactor/EvaluateLibraryTest.kt" to
                        setOf(
                            "all tri-state filters preserve Android IS NOT and disabled semantics",
                            "download global local tracking and multiple flags match Android boundaries",
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/library/LibraryScreenModelTest.kt" to
                        setOf(
                            "filter intent cycles include exclude any and immediately changes visible items",
                            "complete filter flags flow from state to visible list including local and tracking boundaries",
                            "production library stream exposes only logged in tracker rows and reacts to login logout",
                            "production library page projection uses ScreenModel context for tracker menu and visible items",
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/library/LibraryPageCompositionTest.kt" to
                        setOf(
                            "LibraryTab page projection follows tracker session local download and multiple flags",
                        ),
                ),
            22 to
                mapOf(
                    "data/src/jvmTest/kotlin/tachiyomi/data/manga/MangaRepositoryMembershipIntegrationTest.kt" to
                        setOf(
                            "membership update commits favorite date and categories together",
                            "invalid category rolls back every manga membership update",
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/domain/DesktopMigrateMangaUseCaseIntegrationTest.kt" to
                        setOf(
                            "category failure rolls back source and target migration membership",
                            "copy categories and replace move real library membership without half state",
                            "copy categories false and replace false preserve source and recalculate target date",
                        ),
                ),
            19 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/library/LibraryParityIntegrationTest.kt" to
                        setOf("library model exposes batch category partial failure to UI"),
                ),
            24 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/library/MangaDetailScreenModelTest.kt" to
                        setOf("selected read action exposes partial failure in state"),
                ),
            26 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/library/MangaDetailScreenModelTest.kt" to
                        setOf(
                            "cover selection cancellation has no side effects",
                            "cover update success exposes feedback and refreshed model",
                            "cover permission failure is visible and does not refresh cache",
                            "cover delete success refreshes model and reports feedback",
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt" to
                        setOf("测试配置入口使用隔离内存存储并解析实际依赖"),
                ),
            66 to
                mapOf(
                    "domain/src/commonTest/kotlin/tachiyomi/domain/library/interactor/AggregateLibraryStatsTest.kt" to
                        setOf("distinct titles aggregate categories sources statuses and chapters"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/more/StatsScreenModelTest.kt" to
                        setOf("state moves from loading to shared aggregation and exposes errors"),
                ),
        )
    private val expectedTags =
        mapOf(
            3 to setOf("SHARE-EXTRACT"),
            4 to setOf("SHARE-EXTRACT"),
            7 to setOf("SHARE-EXTRACT"),
            8 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"), 9 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"),
            10 to setOf("PLATFORM-ADAPTER"), 11 to setOf("PLATFORM-ADAPTER"), 12 to setOf("PLATFORM-ADAPTER"),
            16 to setOf("SHARE-DIRECT", "SHARE-EXTRACT"), 17 to setOf("SHARE-EXTRACT"),
            19 to setOf("SHARE-EXTRACT"), 22 to setOf("SHARE-DIRECT"), 24 to setOf("SHARE-EXTRACT"),
            26 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"), 28 to setOf("SHARE-EXTRACT"),
            29 to setOf("SHARE-DIRECT", "SHARE-EXTRACT"), 30 to setOf("SHARE-DIRECT"),
            32 to setOf("SHARE-DIRECT"), 33 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"),
            34 to setOf("PLATFORM-ADAPTER", "DESKTOP-PRODUCT"),
            35 to setOf("PLATFORM-ADAPTER", "TEMP-COMPAT"), 36 to setOf("PLATFORM-ADAPTER"),
            37 to setOf("SHARE-EXTRACT"), 38 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"),
            39 to setOf("PLATFORM-ADAPTER"), 40 to setOf("PLATFORM-ADAPTER", "DESKTOP-PRODUCT"),
            43 to setOf("SHARE-EXTRACT", "DESKTOP-PRODUCT"), 44 to setOf("PLATFORM-ADAPTER"),
            45 to setOf("SHARE-EXTRACT"), 47 to setOf("SHARE-EXTRACT"),
            49 to setOf("SHARE-DIRECT", "DESKTOP-PRODUCT"), 51 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"),
            53 to setOf("SHARE-DIRECT"), 54 to setOf("SHARE-DIRECT"), 56 to setOf("SHARE-EXTRACT"),
            57 to setOf("SHARE-EXTRACT"), 59 to setOf("SHARE-DIRECT", "PLATFORM-ADAPTER"),
            61 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"), 62 to setOf("SHARE-DIRECT"),
            64 to setOf("SHARE-DIRECT"), 66 to setOf("SHARE-DIRECT", "SHARE-EXTRACT"),
            67 to setOf("SHARE-DIRECT", "SHARE-EXTRACT"), 68 to setOf("SHARE-EXTRACT"),
            69 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"), 70 to setOf("SHARE-DIRECT"),
            71 to setOf("SHARE-EXTRACT"), 72 to setOf("SHARE-EXTRACT"), 73 to setOf("PLATFORM-ADAPTER"),
            74 to setOf("SHARE-DIRECT", "TEMP-COMPAT"), 81 to setOf("PLATFORM-ADAPTER"),
            82 to setOf("PLATFORM-ADAPTER"), 83 to setOf("PLATFORM-ADAPTER"),
            84 to setOf("PLATFORM-ADAPTER", "PLATFORM-EXEMPT"), 85 to setOf("PLATFORM-EXEMPT"),
            86 to setOf("PLATFORM-ADAPTER"), 87 to setOf("SHARE-DIRECT"),
            88 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"), 90 to setOf("SHARE-EXTRACT"),
            91 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"),
            92 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"), 93 to setOf("SHARE-EXTRACT"),
            94 to setOf("SHARE-DIRECT", "PLATFORM-ADAPTER"), 95 to setOf("SHARE-EXTRACT"),
            96 to setOf("PLATFORM-ADAPTER", "TEMP-COMPAT"),
        )

    @Test
    fun `parity manifest defines the exact roadmap contract`() {
        val repositoryRoot = repositoryRoot()
        val items = manifestItems(repositoryRoot)

        desktopProductEvidence.forEach { evidence ->
            assertTrue(Files.isRegularFile(repositoryRoot.resolve(evidence)), "Missing Desktop product evidence $evidence")
        }

        assertEquals(64, items.size)
        val ids = items.map { validatedId(it.jsonObject) }
        assertEquals(ids.size, ids.toSet().size, "Parity IDs must be unique")
        assertEquals(expectedIds, ids.toSet(), "Parity IDs must exactly match the 64-item design set")
        assertEquals(expectedIds, expectedTags.keys, "Every parity ID must have an exact design tag mapping")

        val requiredTextFields =
            setOf(
                "group",
                "status",
                "authoritativeImplementation",
                "desktopImplementation",
                "platformExemptionEvidence",
                "targetVersion",
            )
        items.forEach { element ->
            val item = element.jsonObject
            validateItem(item, repositoryRoot)
            val id = validatedId(item)
            requiredTextFields.forEach { field ->
                assertTrue(item.getValue(field).jsonPrimitive.content.isNotBlank(), "ID ${item["id"]}: $field must not be blank")
            }
            assertTrue(item.getValue("status").jsonPrimitive.content in validStatuses)

            val tags = item.getValue("tags").jsonArray.map { it.jsonPrimitive.content }
            assertTrue(tags.isNotEmpty(), "ID ${item["id"]}: tags must not be empty")
            assertTrue(tags.all { it in validTags }, "ID ${item["id"]}: invalid tag")
            assertEquals(expectedTags.getValue(id), tags.toSet(), "ID $id: tags differ from design A-J tables")

            val protectionTests = item.getValue("protectionTests").jsonArray.map { it.jsonPrimitive.content }
            assertFalse(
                protectionTests.any { it.endsWith("DesktopProductCapabilityContractTest.kt") },
                "ID $id: parity contract cannot be its own protection evidence",
            )
            if ("DESKTOP-PRODUCT" in tags) {
                assertTrue(protectionTests.isNotEmpty(), "ID $id: DESKTOP-PRODUCT protectionTests must not be empty")
                assertEquals(expectedCapabilityEvidence.getValue(id), protectionTests.toSet(), "ID $id: unexpected evidence")
            } else if (item.getValue("status").jsonPrimitive.content == "NOT_STARTED") {
                assertTrue(protectionTests.isEmpty(), "ID $id: unstarted capabilities cannot claim evidence")
            }
            protectionTests.forEach { testEvidence ->
                assertFalse(testEvidence.startsWith("MISSING:"), "ID ${item["id"]}: $testEvidence")
                assertTrue(testEvidence.isNotBlank(), "ID ${item["id"]}: protection test must not be blank")
                val evidencePath = repositoryRoot.resolve(testEvidence)
                assertTrue(evidencePath.exists(), "ID ${item["id"]}: missing protection test $testEvidence")
                assertTrue(Files.isRegularFile(evidencePath), "ID ${item["id"]}: protection evidence must be a file")
            }
        }
        val productIds =
            items
                .filter {
                    "DESKTOP-PRODUCT" in
                        it.jsonObject.getValue("tags").jsonArray.map { tag -> tag.jsonPrimitive.content }
                }.map { it.jsonObject.getValue("id").jsonPrimitive.content.toInt() }
                .toSet()
        assertEquals(expectedCapabilityEvidence.keys, productIds)
        task3aBehaviorEvidence.forEach { (id, evidence) ->
            val item = items.single { validatedId(it.jsonObject) == id }.jsonObject
            val declared = item.getValue("protectionTests").jsonArray.map { it.jsonPrimitive.content }.toSet()
            evidence.forEach { (path, methodNames) ->
                assertTrue(path in declared, "ID $id must declare dedicated behavior evidence $path")
                val source = Files.readString(repositoryRoot.resolve(path))
                methodNames.forEach { methodName ->
                    assertTrue(
                        source.contains("fun `$methodName`"),
                        "ID $id evidence $path must contain behavior test `$methodName`",
                    )
                }
            }
        }
    }

    @Test
    fun `migration and tracking capabilities declare Android production wiring protection`() {
        val items = manifestItems(repositoryRoot())
        val migration = items.single { validatedId(it.jsonObject) == 68 }.jsonObject
        val tracking = items.single { validatedId(it.jsonObject) == 69 }.jsonObject
        val migrationTests = migration.getValue("protectionTests").jsonArray.map { it.jsonPrimitive.content }
        val trackingTests = tracking.getValue("protectionTests").jsonArray.map { it.jsonPrimitive.content }

        assertTrue(
            "app/src/test/java/mihon/feature/migration/list/MigrationListScreenModelBatchWiringTest.kt" in migrationTests,
        )
        assertTrue(
            "app/src/test/java/eu/kanade/tachiyomi/data/track/AndroidTrackerApiIntegrationTest.kt" in trackingTests,
        )
        assertEquals("WIRED", migration.getValue("status").jsonPrimitive.content)
        assertEquals("SHARED", tracking.getValue("status").jsonPrimitive.content)
    }

    @Test
    fun `reader parity entries report only evidenced shared and wired states`() {
        val items = manifestItems(repositoryRoot()).associateBy { validatedId(it.jsonObject) }
        val expectedStatuses = mapOf(
            9 to "WIRED",
            43 to "WIRED",
            44 to "WIRED",
            45 to "WIRED",
            47 to "WIRED",
            49 to "WIRED",
            51 to "WIRED",
            54 to "WIRED",
        )

        expectedStatuses.forEach { (id, expectedStatus) ->
            val item = items.getValue(id).jsonObject
            assertEquals(expectedStatus, item.getValue("status").jsonPrimitive.content, "ID $id status")
            assertTrue(
                item.getValue("authoritativeImplementation").jsonPrimitive.content.contains("domain/src/commonMain"),
                "ID $id must name its shared authoritative implementation",
            )
            assertTrue(
                item.getValue("desktopImplementation").jsonPrimitive.content.contains("app-desktop/src/main"),
                "ID $id must name the production Desktop consumer",
            )
            assertTrue(item.getValue("protectionTests").jsonArray.isNotEmpty(), "ID $id must declare protection evidence")
        }
    }

    @Test
    fun `reader parity protection evidence executes production behavior instead of naming symbols`() {
        val repositoryRoot = repositoryRoot()
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }

        readerBehaviorEvidence.forEach { (id, evidenceByPath) ->
            val declared =
                items.getValue(id).jsonObject.getValue("protectionTests").jsonArray
                    .map { it.jsonPrimitive.content }
                    .toSet()
            evidenceByPath.forEach { (path, behaviorMethods) ->
                assertTrue(path in declared, "ID $id must declare production behavior evidence $path")
                val source = Files.readString(repositoryRoot.resolve(path))
                behaviorMethods.forEach { (methodName, productionMarkers) ->
                    val method = kotlinTestMethod(source, methodName, "ID $id evidence $path")
                    productionMarkers.forEach { marker ->
                        assertTrue(
                            marker in method,
                            "ID $id behavior `$methodName` must execute production marker `$marker` in $path",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `reader parity implementation paths resolve to production delegates`() {
        val repositoryRoot = repositoryRoot()
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }

        readerProductionDelegates.forEach { (id, delegates) ->
            val item = items.getValue(id).jsonObject
            val declaredImplementations =
                item.getValue("authoritativeImplementation").jsonPrimitive.content +
                    "\n" +
                    item.getValue("desktopImplementation").jsonPrimitive.content
            delegates.forEach { (path, delegateMarkers) ->
                assertTrue(path in declaredImplementations, "ID $id must name production delegate $path")
                val productionPath = repositoryRoot.resolve(path)
                assertTrue(Files.isRegularFile(productionPath), "ID $id production delegate must exist: $path")
                val source = Files.readString(productionPath)
                delegateMarkers.forEach { marker ->
                    assertTrue(marker in source, "ID $id production delegate $path must consume `$marker`")
                }
            }
        }
    }

    @Test
    fun `EXEMPT item accepts real evidence and rejects NONE or missing files`() {
        val evidence = Files.createFile(tempDir.resolve("evidence.md"))
        validateItem(syntheticItem(84, "EXEMPT", evidence.toString()), tempDir)

        val noneFailure = assertThrows(AssertionError::class.java) {
            validateItem(syntheticItem(84, "EXEMPT", "NONE"), tempDir)
        }
        assertTrue(noneFailure.message.orEmpty().contains("ID 84"))

        val missingFailure = assertThrows(AssertionError::class.java) {
            validateItem(syntheticItem(84, "EXEMPT", "missing.md"), tempDir)
        }
        assertTrue(missingFailure.message.orEmpty().contains("ID 84"))
    }

    @Test
    fun `missing empty non-integer and wrong-type ids fail with item context`() {
        val invalidItems =
            listOf(
                buildJsonObject { put("status", "NOT_STARTED") },
                syntheticItem(3).toMutableMap().also { it["id"] = Json.parseToJsonElement("\"\"") }.let(::JsonObject),
                syntheticItem(3).toMutableMap().also { it["id"] = Json.parseToJsonElement("\"three\"") }.let(::JsonObject),
                syntheticItem(3).toMutableMap().also { it["id"] = Json.parseToJsonElement("\"3\"") }.let(::JsonObject),
                syntheticItem(3).toMutableMap().also { it["id"] = buildJsonObject { put("value", 3) } }.let(::JsonObject),
            )

        invalidItems.forEach { item ->
            val failure = assertThrows(AssertionError::class.java) { validateItem(item, tempDir) }
            assertTrue(failure.message.orEmpty().contains("ID"), failure.message)
            assertTrue(failure.message.orEmpty().contains("item="), failure.message)
        }
    }

    private fun validateItem(item: JsonObject, repositoryRoot: Path) {
        val id = validatedId(item)
        val status = item["status"]?.jsonPrimitive?.content
        assertTrue(status in validStatuses, "ID $id: status must be one of $validStatuses")
        val exemptionEvidence = item["platformExemptionEvidence"]?.jsonPrimitive?.content
        if (status == "EXEMPT") {
            assertTrue(exemptionEvidence != null && exemptionEvidence != "NONE", "ID $id: EXEMPT requires real platform evidence")
            assertTrue(Files.isRegularFile(repositoryRoot.resolve(exemptionEvidence!!)), "ID $id: missing exemption evidence $exemptionEvidence")
        } else {
            assertEquals("NONE", exemptionEvidence, "ID $id: non-EXEMPT evidence must be NONE")
        }
    }

    private fun validatedId(item: JsonObject): Int {
        val raw = item["id"] ?: throw AssertionError("ID <missing>: item has no id field; item=$item")
        val primitive = try {
            raw.jsonPrimitive
        } catch (_: IllegalArgumentException) {
            throw AssertionError("ID <invalid>: id must be an integer; item=$item")
        }
        val text = primitive.content
        if (primitive.isString) {
            throw AssertionError("ID <$text>: id must be a JSON integer, not a string; item=$item")
        }
        return text.toIntOrNull() ?: throw AssertionError("ID <$text>: id must be a non-empty integer; item=$item")
    }

    private fun syntheticItem(id: Int, status: String = "NOT_STARTED", evidence: String = "NONE") =
        buildJsonObject {
            put("id", id)
            put("status", status)
            put("platformExemptionEvidence", evidence)
        }

    private fun kotlinTestMethod(
        source: String,
        methodName: String,
        context: String,
    ): String {
        val marker = "fun `$methodName`"
        val start = source.indexOf(marker)
        assertTrue(start >= 0, "$context must contain behavior test `$methodName`")
        val nextTest = source.indexOf("\n    @Test", start + marker.length).takeIf { it >= 0 } ?: source.length
        return source.substring(start, nextTest)
    }

    private fun repositoryRoot() =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("docs")) }

    private fun manifestItems(repositoryRoot: Path) =
        repositoryRoot.resolve("app-desktop/src/test/resources/parity/parity-manifest.json").let { resource ->
            require(resource.exists()) { "Missing parity/parity-manifest.json" }
            Json.parseToJsonElement(Files.readString(resource)).jsonArray
        }
}
