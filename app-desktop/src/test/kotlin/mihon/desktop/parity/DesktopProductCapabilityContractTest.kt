package mihon.desktop.parity

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

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
    private val platformProvenanceBatchOneIds = setOf(3, 4, 7, 8)
    private val platformProvenanceBatchOneStatuses =
        mapOf(
            3 to "CHARACTERIZED",
            4 to "WIRED",
            7 to "WIRED",
            8 to "SHARED",
        )
    private val structuredProvenanceIds =
        platformProvenanceBatchOneIds + setOf(28, 29, 30, 32, 33, 34, 35, 36, 37, 38, 39, 40, 43, 67, 68, 69, 70, 87)
    private val sourceExtensionParityStatuses =
        mapOf(
            28 to "WIRED",
            29 to "WIRED",
            30 to "WIRED",
            32 to "NOT_STARTED",
            33 to "WIRED",
            34 to "WIRED",
            35 to "WIRED",
            36 to "WIRED",
            37 to "WIRED",
            38 to "WIRED",
            39 to "WIRED",
            40 to "WIRED",
            87 to "SHARED",
        )
    private val allowedDeviationClassifications =
        setOf(
            "PLATFORM_ADAPTER",
            "SECURITY_ENHANCEMENT",
            "DESKTOP_PRODUCT",
            "CROSS_PLATFORM_PRODUCT_ENHANCEMENT",
            "CORRECTNESS_BUGFIX",
            "MIGRATION_OUTPUT",
            "UNCLASSIFIED_DEBT",
            "CROSS_PLATFORM_RELIABILITY_ENHANCEMENT",
            "DESKTOP_PRODUCT_ENHANCEMENT",
        )
    private val fixedOriginalMihonRef =
        "main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8"
    private val forkOnlyReaderPairingPaths =
        setOf(
            "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagePairingAlgorithm.kt",
            "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PairingState.kt",
            "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/DualPageViewerAdapter.kt",
            "app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/DualPagePairingTest.kt",
            "app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/DualPageViewerAdapterTest.kt",
        )
    private val fixedMainPathInventoryResource =
        "app-desktop/src/test/resources/parity/fixed-main-path-inventory.json"
    private val exactAuthorityPaths =
        mapOf(
            3 to setOf("app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/SourcesScreenModel.kt"),
            4 to
                setOf(
                    "app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt",
                    "app/src/main/java/eu/kanade/domain/DomainModule.kt",
                ),
            7 to
                setOf(
                    "core/common/src/main/kotlin/tachiyomi/core/common/preference/PreferenceStore.kt",
                    "core/common/src/main/kotlin/tachiyomi/core/common/preference/AndroidPreferenceStore.kt",
                ),
            8 to
                setOf(
                    "core/common/src/main/kotlin/eu/kanade/tachiyomi/network/NetworkHelper.kt",
                    "core/common/src/main/kotlin/eu/kanade/tachiyomi/network/OkHttpExtensions.kt",
                ),
            28 to
                setOf(
                    "app/src/main/java/eu/kanade/domain/source/interactor/GetEnabledSources.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/SourcesScreenModel.kt",
                ),
            29 to
                setOf(
                    "app/src/main/java/eu/kanade/domain/source/interactor/GetIncognitoState.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreenModel.kt",
                    "data/src/main/java/tachiyomi/data/source/SourcePagingSource.kt",
                    "domain/src/main/java/mihon/domain/manga/model/SManga.kt",
                    "domain/src/main/java/tachiyomi/domain/manga/interactor/NetworkToLocalManga.kt",
                ),
            30 to
                setOf(
                    "app/src/main/java/eu/kanade/presentation/browse/GlobalSearchScreen.kt",
                    "app/src/main/java/eu/kanade/presentation/browse/components/GlobalSearchCardRow.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/globalsearch/GlobalSearchScreen.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/globalsearch/GlobalSearchScreenModel.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/globalsearch/SearchScreenModel.kt",
                    "domain/src/main/java/mihon/domain/manga/model/SManga.kt",
                    "domain/src/main/java/tachiyomi/domain/manga/interactor/NetworkToLocalManga.kt",
                ),
            37 to
                setOf(
                    "app/src/main/java/eu/kanade/domain/extension/interactor/GetExtensionSources.kt",
                    "app/src/main/java/eu/kanade/domain/extension/interactor/GetExtensionsByType.kt",
                    "app/src/main/java/eu/kanade/presentation/browse/ExtensionDetailsScreen.kt",
                    "app/src/main/java/eu/kanade/presentation/browse/ExtensionsScreen.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/extension/ExtensionManager.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/ui/browse/extension/ExtensionsScreenModel.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/ui/browse/extension/ExtensionsTab.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/ui/browse/extension/details/ExtensionDetailsScreen.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/ui/browse/extension/details/ExtensionDetailsScreenModel.kt",
                ),
            67 to setOf("app/src/main/java/mihon/domain/migration/usecases/MigrateMangaUseCase.kt"),
            68 to
                setOf(
                    "app/src/main/java/mihon/feature/migration/list/MigrationListScreenModel.kt",
                    "app/src/main/java/mihon/feature/migration/list/components/MigrationProgressDialog.kt",
                ),
            69 to
                setOf(
                    "app/src/main/java/eu/kanade/tachiyomi/data/track/TrackerManager.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/ui/manga/track/TrackInfoDialog.kt",
                    "app/src/main/java/eu/kanade/presentation/track/TrackerSearch.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/data/track/myanimelist/MyAnimeList.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/data/track/myanimelist/MyAnimeListApi.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/data/track/EnhancedTracker.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/data/track/suwayomi/Suwayomi.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/data/track/suwayomi/SuwayomiApi.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/data/track/anilist/Anilist.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/data/track/anilist/AnilistApi.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/data/track/kitsu/Kitsu.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/data/track/kitsu/KitsuApi.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/data/track/shikimori/Shikimori.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/data/track/shikimori/ShikimoriApi.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/data/track/bangumi/Bangumi.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/data/track/bangumi/BangumiApi.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/data/track/mangaupdates/MangaUpdates.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/data/track/mangaupdates/MangaUpdatesApi.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/data/track/komga/Komga.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/data/track/komga/KomgaApi.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/data/track/kavita/Kavita.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/data/track/kavita/KavitaApi.kt",
                ),
            70 to
                setOf(
                    "app/src/main/java/eu/kanade/domain/track/interactor/TrackChapter.kt",
                    "app/src/main/java/eu/kanade/domain/track/store/DelayedTrackingStore.kt",
                    "app/src/main/java/eu/kanade/domain/track/service/DelayedTrackingUpdateJob.kt",
                    "app/src/main/java/eu/kanade/domain/source/interactor/GetIncognitoState.kt",
                    "app/src/main/java/eu/kanade/domain/source/interactor/ToggleIncognito.kt",
                    "app/src/main/java/eu/kanade/domain/source/service/SourcePreferences.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/ui/browse/extension/details/ExtensionDetailsScreenModel.kt",
                ),
            87 to
                setOf(
                    "app/src/main/java/eu/kanade/presentation/more/settings/screen/appearance/AppLanguageScreen.kt",
                    "app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsAppearanceScreen.kt",
                    "core/common/src/main/kotlin/tachiyomi/core/common/i18n/Localize.kt",
                ),
        )
    private val exactAuthorityBlobIds =
        mapOf(
            "app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/SourcesScreenModel.kt" to "0f777f7e58b653e458bba240127f8446b0e2ae28",
            "app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt" to "9828155df3a543165f8b52a71bda27653e90fc5c",
            "app/src/main/java/eu/kanade/domain/DomainModule.kt" to "6d3ddc35b91e5baf8a02c05490a678ed3f83dbe9",
            "core/common/src/main/kotlin/tachiyomi/core/common/preference/PreferenceStore.kt" to "2016f3d442c73947e75463c7149c97373f9364fd",
            "core/common/src/main/kotlin/tachiyomi/core/common/preference/AndroidPreferenceStore.kt" to "78f98f2042964fcd5162e5cd68fe02055415324f",
            "core/common/src/main/kotlin/eu/kanade/tachiyomi/network/NetworkHelper.kt" to "202c39063fcd2cb5e1847955265cb2c445fc896b",
            "core/common/src/main/kotlin/eu/kanade/tachiyomi/network/OkHttpExtensions.kt" to "072c50c81e788143385a07a6874c037ae503b0eb",
            "app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt" to "0fa40ba9d693e0495d3b21e8db6c37b63f5ee350",
            "data/src/main/java/tachiyomi/data/source/SourcePagingSource.kt" to "bd34a91b5d13d1900b16aeb3b2f2b80df48c7dbe",
            "domain/src/main/java/tachiyomi/domain/manga/interactor/NetworkToLocalManga.kt" to "69137d9e9688175ee4edf4992c3345727268bb2d",
            "domain/src/main/java/mihon/domain/manga/model/SManga.kt" to "033f313d06a8427aefa2a2158e906ca4836013dc",
            "app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/globalsearch/GlobalSearchScreen.kt" to "44164f49b7ea223cae94f1f4494fc68d35640f69",
            "app/src/main/java/eu/kanade/presentation/browse/GlobalSearchScreen.kt" to "8d949235a95fac8ca0b2df16d03690fd1385f7ba",
            "app/src/main/java/eu/kanade/presentation/browse/components/GlobalSearchCardRow.kt" to "456269b7c604a18009c6d17bee615a547492b37d",
            "app/src/main/java/eu/kanade/domain/extension/interactor/GetExtensionsByType.kt" to "c2bfd49f8e576cbca178ecc1664f7997b9c4a766",
            "app/src/main/java/eu/kanade/domain/extension/interactor/GetExtensionSources.kt" to "76d2abe014b6583d46cdc322aaa37f37d4683697",
            "app/src/main/java/eu/kanade/presentation/browse/ExtensionDetailsScreen.kt" to "c2219c395532ab5efc1bfbce6615adf42ad53861",
            "app/src/main/java/eu/kanade/presentation/browse/ExtensionsScreen.kt" to "e460467861a925bce4e00613de4a9be47c632ba9",
            "app/src/main/java/eu/kanade/tachiyomi/ui/browse/extension/ExtensionsTab.kt" to "30561baeda7ccd06cd4ce41844cc6185759dd365",
            "app/src/main/java/eu/kanade/tachiyomi/ui/browse/extension/details/ExtensionDetailsScreen.kt" to "8cac9c5cceb94cb53cff3f8f1074e30e45a376c2",
        )
    private val exactBatchOneSharedPaths =
        mapOf(
            3 to
                setOf(
                    "domain/src/commonMain/kotlin/mihon/domain/error/AppError.kt",
                    "domain/src/commonMain/kotlin/mihon/domain/task/TaskState.kt",
                ),
            4 to emptySet<String>(),
            7 to
                setOf(
                    "core/common/src/commonMain/kotlin/tachiyomi/core/common/preference/Preference.kt",
                    "core/common/src/commonMain/kotlin/tachiyomi/core/common/preference/PreferenceStore.kt",
                ),
            8 to
                setOf(
                    "domain/src/commonMain/kotlin/mihon/domain/error/AppError.kt",
                    "domain/src/commonMain/kotlin/mihon/domain/network/NetworkErrorMapper.kt",
                ),
        )
    private val exactBatchOneAndroidPaths =
        mapOf(
            3 to setOf("app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/SourcesScreenModel.kt"),
            4 to
                setOf(
                    "app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt",
                    "app/src/main/java/eu/kanade/domain/DomainModule.kt",
                ),
            7 to setOf("app/src/main/java/eu/kanade/tachiyomi/di/PreferenceModule.kt"),
            8 to setOf("app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt"),
        )
    private val exactBatchOneDesktopPaths =
        mapOf(
            3 to setOf("app-desktop/src/main/kotlin/mihon/desktop/history/HistoryScreenModel.kt"),
            4 to setOf("app-desktop/src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt"),
            7 to
                setOf(
                    "core/common/src/jvmMain/kotlin/tachiyomi/core/common/preference/DesktopPreferenceStore.kt",
                    "app-desktop/src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt",
                ),
            8 to
                setOf(
                    "app-desktop/src/main/kotlin/mihon/desktop/source/MangaDexSource.kt",
                    "app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopNetworkHelper.kt",
                    "app-desktop/src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt",
                ),
        )
    private val exactBatchOneDeviationClassifications =
        mapOf(
            3 to setOf("MIGRATION_OUTPUT", "UNCLASSIFIED_DEBT"),
            4 to setOf("PLATFORM_ADAPTER", "UNCLASSIFIED_DEBT"),
            7 to setOf("PLATFORM_ADAPTER", "MIGRATION_OUTPUT"),
            8 to setOf("PLATFORM_ADAPTER", "MIGRATION_OUTPUT"),
        )
    private val requiredAuthorityBoundaryTerms =
        mapOf(
            67 to
                mapOf(
                    "shared migration plan" to "MIGRATION_OUTPUT",
                    "metadata/category/source removal" to "DESKTOP_PRODUCT_ENHANCEMENT",
                    "persisted target reread" to "DESKTOP_PRODUCT_ENHANCEMENT",
                ),
            68 to
                mapOf(
                    "startIndex" to "CROSS_PLATFORM_RELIABILITY_ENHANCEMENT",
                    "Failure summary and targeted retry" to "CROSS_PLATFORM_RELIABILITY_ENHANCEMENT",
                    "queue targets/options/status/errors" to "DESKTOP_PRODUCT_ENHANCEMENT",
                ),
            69 to
                mapOf(
                    "OS credential" to "SECURITY_ENHANCEMENT",
                    "persistent event/checkpoint" to "DESKTOP_PRODUCT_ENHANCEMENT",
                    "production provider configuration" to "UNCLASSIFIED_DEBT",
                    "bind-existing/new-entry" to "UNCLASSIFIED_DEBT",
                    "refresh-before-update" to "UNCLASSIFIED_DEBT",
                    "reading status/date" to "UNCLASSIFIED_DEBT",
                    "MAL error" to "UNCLASSIFIED_DEBT",
                    "search model" to "UNCLASSIFIED_DEBT",
                    "private/date/delete" to "UNCLASSIFIED_DEBT",
                    "enhanced auto-match" to "UNCLASSIFIED_DEBT",
                    "Suwayomi delete" to "UNCLASSIFIED_DEBT",
                    "provider-specific fixed-main status replay" to "MIGRATION_OUTPUT",
                    "provider error classification/retry" to "UNCLASSIFIED_DEBT",
                    "Komga DNS" to "UNCLASSIFIED_DEBT",
                    "Kitsu/MangaUpdates request shape" to "UNCLASSIFIED_DEBT",
                ),
            70 to
                mapOf(
                    "refresh-before-update" to "UNCLASSIFIED_DEBT",
                    "login/progress filtering" to "UNCLASSIFIED_DEBT",
                    "parallel provider updates" to "UNCLASSIFIED_DEBT",
                    "highest progress" to "UNCLASSIFIED_DEBT",
                    "network constraint" to "UNCLASSIFIED_DEBT",
                    "unique work" to "UNCLASSIFIED_DEBT",
                    "exponential backoff" to "UNCLASSIFIED_DEBT",
                    "bounded retry" to "UNCLASSIFIED_DEBT",
                    "queue cleanup" to "UNCLASSIFIED_DEBT",
                    "persistent checkpoint" to "DESKTOP_PRODUCT_ENHANCEMENT",
                ),
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
            34 to
                setOf(
                    "domain/src/jvmTest/kotlin/mihon/domain/extension/ExtensionInstallCoordinatorTest.kt",
                    "app-desktop/src/test/kotlin/mihon/desktop/extension/ApkToJarConverterTest.kt",
                    "app-desktop/src/test/kotlin/mihon/desktop/extension/DesktopExtensionInstallTransactionTest.kt",
                ),
            40 to
                setOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/network/DesktopChallengeRecoveryPolicyTest.kt",
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/cloudflare/DesktopChallengeLoginWiringTest.kt",
                    "app-desktop/src/test/kotlin/mihon/desktop/network/CloudflareCookieImportTest.kt",
                    "app-desktop/src/test/kotlin/mihon/desktop/network/FlareSolverrClientTest.kt",
                ),
            43 to
                setOf(
                    "app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReaderSharedParityWiringTest.kt",
                    "domain/src/commonTest/kotlin/mihon/domain/reader/ReaderParityContractTest.kt",
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
                            "fork-added pairing adapter and shared core produce the same enhancement vectors" to
                                setOf("PagePairingAlgorithm.buildPairings", "ReaderPagePairing.build"),
                        ),
                    "domain/src/commonTest/kotlin/mihon/domain/reader/ReaderParityContractTest.kt" to
                        mapOf(
                            "fork-added shared portrait pairing enhancement groups adjacent pages" to
                                setOf("ReaderPagePairing.build", "PageLayout.PORTRAIT"),
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
                            "pager and webtoon transition holders subscribe to every shared production state" to
                                setOf(
                                    "PagerTransitionHolder.kt",
                                    "WebtoonTransitionHolder.kt",
                                    "chapter.sharedStateFlow",
                                    ".collectLatest { state ->",
                                    "ReaderChapterState.Loading",
                                    "ReaderChapterState.Error",
                                    "ReaderChapterState.Loaded",
                                ),
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
                            "current Android consumer navigation adapters match every shared preset and inversion" to
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
                            "current Android consumer grayscale and invert preferences map to the shared filter contract" to
                                setOf("buildAndroidLayerFilterParams", "params.isEffective"),
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/reader/ReaderSettingsModelsTest.kt" to
                        mapOf(
                            "grayscale and invert survive preference round trip" to
                                setOf("ReaderPreferences", "saveColorFilter", "loadColorFilter"),
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/ReaderColorMatrixTest.kt" to
                        mapOf(
                            "disabled grayscale invert and combined color matrices transform pixels" to
                                setOf("readerColorMatrix", "transform("),
                            "reader color matrix production chain delegates through the tested helper" to
                                setOf(
                                    "ReaderVisualComponents.kt",
                                    "DesktopReaderScreen.kt",
                                    "readerColorMatrix(colorFilter)",
                                    "readerColorTransform(state.colorFilter)",
                                ),
                        ),
                ),
            54 to
                mapOf(
                    "app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReaderSharedParityWiringTest.kt" to
                        mapOf(
                            "current Android consumer ReaderViewModel delegates the sorted chapter list to the shared skip filter" to
                                setOf(
                                    "filterAndroidReaderChapters(",
                                    "chapters = sortedChapters",
                                    "currentChapterId = chapterId",
                                    "skipPolicy = skipPolicy",
                                    "isFiltered =",
                                ),
                            "current Android consumer chapter pipeline maps metadata before applying shared skip policy" to
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
                    "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonTransitionHolder.kt" to
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
                    "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/ReaderVisualComponents.kt" to
                        setOf("val matrix = readerColorMatrix(colorFilter) ?: return this"),
                    "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/DesktopReaderScreen.kt" to
                        setOf("readerColorTransform(state.colorFilter)"),
                ),
            54 to
                mapOf(
                    "domain/src/commonMain/kotlin/mihon/domain/reader/ReaderNavigation.kt" to setOf("fun findAdjacentChapter"),
                    "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt" to
                        setOf("filterAndroidReaderChapters("),
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
        val fixedMainPathInventory = fixedMainPathInventory(repositoryRoot)

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

            if (id in structuredProvenanceIds) {
                validateSourceExtensionProvenance(item, repositoryRoot, fixedMainPathInventory)
            }

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
    fun `extension install and Cloudflare product protection tests are real files`() {
        val repositoryRoot = repositoryRoot()
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }

        setOf(34, 40).forEach { id ->
            val declared =
                items.getValue(id).jsonObject.getValue("protectionTests").jsonArray
                    .map { it.jsonPrimitive.content }
                    .toSet()
            assertEquals(expectedCapabilityEvidence.getValue(id), declared, "ID $id: unexpected product protection")
            declared.forEach { path ->
                val testFile = repositoryRoot.resolve(path)
                assertTrue(Files.isRegularFile(testFile), "ID $id: missing product protection test $path")
                assertTrue(Files.readString(testFile).contains("@Test"), "ID $id: product protection must be a test file")
            }
        }
    }

    @Test
    fun `source extension and i18n parity states reflect only closed evidence`() {
        val items = manifestItems(repositoryRoot()).associateBy { validatedId(it.jsonObject) }

        assertEquals(
            sourceExtensionParityStatuses.keys,
            items.keys.intersect(sourceExtensionParityStatuses.keys),
            "the source-extension parity ID set must stay exact; ID 31 is not in the design set",
        )
        sourceExtensionParityStatuses.forEach { (id, status) ->
            val item = items.getValue(id).jsonObject
            assertEquals(status, item.getValue("status").jsonPrimitive.content, "ID $id status")
            if (status in setOf("SHARED", "WIRED", "VERIFIED")) {
                assertTrue(item.getValue("protectionTests").jsonArray.isNotEmpty(), "ID $id needs production protection")
            }
        }
    }

    @Test
    fun `active source extension authority language distinguishes fixed main from current consumers`() {
        val repositoryRoot = repositoryRoot()
        val manifest = Files.readString(repositoryRoot.resolve("app-desktop/src/test/resources/parity/parity-manifest.json"))
        val proposal = Files.readString(repositoryRoot.resolve("openspec/changes/align-sources-extensions/proposal.md"))

        listOf("Android authoritative", "Android original", "Android 原版").forEach { ambiguousAuthority ->
            assertFalse(manifest.contains(ambiguousAuthority), "Active manifest must not use ambiguous authority term: $ambiguousAuthority")
            assertFalse(proposal.contains(ambiguousAuthority), "Active proposal must not use ambiguous authority term: $ambiguousAuthority")
        }
        manifestItems(repositoryRoot)
            .filter { validatedId(it.jsonObject) in sourceExtensionParityStatuses && validatedId(it.jsonObject) != 87 }
            .forEach { item ->
                val id = validatedId(item.jsonObject)
                assertTrue(
                    item.jsonObject.getValue("authoritativeImplementation").jsonPrimitive.content.startsWith(
                        "Fixed-main original Mihon capability #$id:",
                    ),
                    "ID $id must name fixed-main original Mihon as authority, not a current consumer or adapter",
                )
            }
    }

    @Test
    fun `source extension provenance rejects an invalid second consumer path`() {
        createSyntheticConsumerFiles()
        val item = syntheticSourceExtensionItem(
            currentAndroidConsumerPaths = listOf("app/src/main/Current.kt", "app/src/main/Missing.kt"),
        )

        val failure = assertThrows(AssertionError::class.java) {
            validateSourceExtensionProvenance(item, tempDir, fixedMainPathInventory(buildFixedMainPathInventory()))
        }

        assertTrue(failure.message.orEmpty().contains("app/src/main/Missing.kt"), failure.message)
    }

    @Test
    fun `source extension provenance rejects a platform scoped shared path`() {
        createSyntheticConsumerFiles()
        val item = syntheticSourceExtensionItem(sharedImplementationPaths = listOf("app/src/main/Current.kt"))
        val failure = assertThrows(AssertionError::class.java) {
            validateSourceExtensionProvenance(item, tempDir, fixedMainPathInventory(buildFixedMainPathInventory()))
        }
        assertTrue(failure.message.orEmpty().contains("commonMain"), failure.message)
    }

    @Test
    fun `source extension provenance rejects an unclassified deviation tail item`() {
        createSyntheticConsumerFiles()
        val item = syntheticSourceExtensionItem(
            deviations =
                listOf(
                    "PLATFORM_ADAPTER" to "The first deviation is classified.",
                    null to "The second deviation has no classification.",
                ),
        )

        val failure = assertThrows(AssertionError::class.java) {
            validateSourceExtensionProvenance(item, tempDir, fixedMainPathInventory(buildFixedMainPathInventory()))
        }

        assertTrue(failure.message.orEmpty().contains("deviations[1].classification"), failure.message)
    }

    @Test
    fun `source extension provenance rejects an unknown fixed-main inventory path`() {
        createSyntheticConsumerFiles()

        val failure = assertThrows(AssertionError::class.java) {
            validateSourceExtensionProvenance(
                syntheticSourceExtensionItem(),
                tempDir,
                fixedMainPathInventory(
                    buildFixedMainPathInventory(
                        paths = listOf("app/src/main/Known.kt" to "0".repeat(40)),
                    ),
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("fixed-main inventory does not contain"), failure.message)
    }

    @ParameterizedTest(name = "rejects fork-only reader path {0}")
    @ValueSource(
        strings = [
            "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagePairingAlgorithm.kt",
            "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PairingState.kt",
            "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/DualPageViewerAdapter.kt",
            "app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/DualPagePairingTest.kt",
            "app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/DualPageViewerAdapterTest.kt",
        ],
    )
    fun `reader provenance rejects fork-added pairing paths as fixed-main symbols`(forkPairingPath: String) {
        createSyntheticConsumerFiles()
        val item = syntheticSourceExtensionItem(id = 43, upstreamPath = forkPairingPath)

        val failure = assertThrows(AssertionError::class.java) {
            validateSourceExtensionProvenance(
                item,
                tempDir,
                fixedMainPathInventory(
                    buildFixedMainPathInventory(paths = listOf(forkPairingPath to "0".repeat(40))),
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("fork-only reader pairing path"), failure.message)
    }

    @Test
    fun `fixed-main inventory rejects mismatched ref and malformed blob ids`() {
        val refFailure = assertThrows(AssertionError::class.java) {
            fixedMainPathInventory(
                buildFixedMainPathInventory(
                    ref = "main@0000000000000000000000000000000000000000",
                ),
            )
        }
        assertTrue(refFailure.message.orEmpty().contains("exact fixed original Mihon ref"), refFailure.message)

        val blobFailure = assertThrows(AssertionError::class.java) {
            fixedMainPathInventory(
                buildFixedMainPathInventory(
                    paths = listOf("app/src/main/Upstream.kt" to "not-a-blob"),
                ),
            )
        }
        assertTrue(blobFailure.message.orEmpty().contains("lowercase 40-hex"), blobFailure.message)

        val wrongKnownBlobFailure = assertThrows(AssertionError::class.java) {
            fixedMainPathInventory(
                buildFixedMainPathInventory(
                    paths = listOf("app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt" to "0".repeat(40)),
                ),
            )
        }
        assertTrue(wrongKnownBlobFailure.message.orEmpty().contains("wrong blob"), wrongKnownBlobFailure.message)
    }

    @Test
    fun `platform provenance batch rejects wrong layer missing consumer and debt reclassification`() {
        val repositoryRoot = repositoryRoot()
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }
        val inventory = fixedMainPathInventory(repositoryRoot)

        val wrongLayer =
            JsonObject(
                items.getValue(8).jsonObject.toMutableMap().apply {
                    put(
                        "upstreamSymbols",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("path", "app-desktop/src/main/kotlin/mihon/desktop/source/MangaDexSource.kt")
                                    put("symbol", "current Desktop mapper consumer")
                                },
                            )
                        },
                    )
                },
            )
        val layerFailure = assertThrows(AssertionError::class.java) {
            validateSourceExtensionProvenance(
                wrongLayer,
                repositoryRoot,
                inventory + ("app-desktop/src/main/kotlin/mihon/desktop/source/MangaDexSource.kt" to "0".repeat(40)),
            )
        }
        assertTrue(layerFailure.message.orEmpty().contains("exact fixed-main authority paths"), layerFailure.message)

        val missingConsumer =
            JsonObject(
                items.getValue(8).jsonObject.toMutableMap().apply {
                    put(
                        "desktopConsumerAdapterPaths",
                        buildJsonArray {
                            add("app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopNetworkHelper.kt")
                            add("app-desktop/src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt")
                        },
                    )
                },
            )
        val consumerFailure = assertThrows(AssertionError::class.java) {
            validateSourceExtensionProvenance(missingConsumer, repositoryRoot, inventory)
        }
        assertTrue(consumerFailure.message.orEmpty().contains("exact desktopConsumerAdapterPaths"), consumerFailure.message)

        val debtAsEnhancement =
            JsonObject(
                items.getValue(3).jsonObject.toMutableMap().apply {
                    put(
                        "deviations",
                        buildJsonArray {
                            items.getValue(3).jsonObject.getValue("deviations").jsonArray.forEach { element ->
                                val deviation = element.jsonObject
                                add(
                                    if (deviation.getValue("classification").jsonPrimitive.content == "UNCLASSIFIED_DEBT") {
                                        JsonObject(
                                            deviation.toMutableMap().apply {
                                                put("classification", Json.parseToJsonElement("\"CROSS_PLATFORM_RELIABILITY_ENHANCEMENT\""))
                                            },
                                        )
                                    } else {
                                        deviation
                                    },
                                )
                            }
                        },
                    )
                },
            )
        val classificationFailure = assertThrows(AssertionError::class.java) {
            validateSourceExtensionProvenance(debtAsEnhancement, repositoryRoot, inventory)
        }
        assertTrue(classificationFailure.message.orEmpty().contains("exact deviation classifications"), classificationFailure.message)

        val promotedState =
            JsonObject(
                items.getValue(3).jsonObject.toMutableMap().apply {
                    put("status", Json.parseToJsonElement("\"SHARED\""))
                },
            )
        val stateFailure = assertThrows(AssertionError::class.java) {
            validateSourceExtensionProvenance(promotedState, repositoryRoot, inventory)
        }
        assertTrue(stateFailure.message.orEmpty().contains("exact evidence status"), stateFailure.message)
    }

    @Test
    fun `migration and tracker authority rejects wrong ref consumer confusion and omitted debt`() {
        val repositoryRoot = repositoryRoot()
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }
        val inventory = fixedMainPathInventory(repositoryRoot)

        val wrongRef =
            JsonObject(
                items.getValue(67).jsonObject.toMutableMap().apply {
                    put("upstreamRef", Json.parseToJsonElement("\"main@0000000000000000000000000000000000000000\""))
                },
            )
        val refFailure = assertThrows(AssertionError::class.java) {
            validateSourceExtensionProvenance(wrongRef, repositoryRoot, inventory)
        }
        assertTrue(refFailure.message.orEmpty().contains("exact fixed original Mihon ref"), refFailure.message)

        val currentConsumerAsAuthority =
            JsonObject(
                items.getValue(69).jsonObject.toMutableMap().apply {
                    put(
                        "upstreamSymbols",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put(
                                        "path",
                                        "domain/src/commonMain/kotlin/tachiyomi/domain/track/service/TrackerProviderProtocol.kt",
                                    )
                                    put("symbol", "Current shared migration output")
                                },
                            )
                        },
                    )
                },
            )
        val consumerFailure = assertThrows(AssertionError::class.java) {
            validateSourceExtensionProvenance(
                currentConsumerAsAuthority,
                repositoryRoot,
                inventory +
                    ("domain/src/commonMain/kotlin/tachiyomi/domain/track/service/TrackerProviderProtocol.kt" to
                        "0".repeat(40)),
            )
        }
        assertTrue(consumerFailure.message.orEmpty().contains("exact fixed-main authority paths"), consumerFailure.message)

        val tracker = items.getValue(69).jsonObject
        val omittedDebt =
            JsonObject(
                tracker.toMutableMap().apply {
                    put(
                        "deviations",
                        buildJsonArray {
                            tracker.getValue("deviations").jsonArray
                                .filterNot { it.jsonObject.getValue("description").jsonPrimitive.content.contains("Suwayomi delete") }
                                .forEach(::add)
                        },
                    )
                },
            )
        val debtFailure = assertThrows(AssertionError::class.java) {
            validateSourceExtensionProvenance(omittedDebt, repositoryRoot, inventory)
        }
        assertTrue(debtFailure.message.orEmpty().contains("Suwayomi delete"), debtFailure.message)
    }

    @Test
    fun `tracker debt cannot be reclassified as an enhancement`() {
        val repositoryRoot = repositoryRoot()
        val tracker = manifestItems(repositoryRoot).single { validatedId(it.jsonObject) == 69 }.jsonObject
        val reclassifiedDebt =
            JsonObject(
                tracker.toMutableMap().apply {
                    put(
                        "deviations",
                        buildJsonArray {
                            tracker.getValue("deviations").jsonArray.forEach { element ->
                                val deviation = element.jsonObject
                                if (deviation.getValue("description").jsonPrimitive.content.contains("Suwayomi delete")) {
                                    add(
                                        JsonObject(
                                            deviation.toMutableMap().apply {
                                                put(
                                                    "classification",
                                                    Json.parseToJsonElement("\"DESKTOP_PRODUCT_ENHANCEMENT\""),
                                                )
                                            },
                                        ),
                                    )
                                } else {
                                    add(element)
                                }
                            }
                        },
                    )
                },
            )

        val failure = assertThrows(AssertionError::class.java) {
            validateSourceExtensionProvenance(
                reclassifiedDebt,
                repositoryRoot,
                fixedMainPathInventory(repositoryRoot),
            )
        }

        assertTrue(failure.message.orEmpty().contains("UNCLASSIFIED_DEBT"), failure.message)
        assertTrue(failure.message.orEmpty().contains("Suwayomi delete"), failure.message)
    }

    @Test
    fun `source extension provenance validates from a snapshot without a Git directory`() {
        createSyntheticConsumerFiles()
        val inventoryResource = tempDir.resolve(fixedMainPathInventoryResource)
        Files.createDirectories(inventoryResource.parent)
        Files.writeString(inventoryResource, buildFixedMainPathInventory().toString())

        assertFalse(Files.exists(tempDir.resolve(".git")))
        validateSourceExtensionProvenance(
            syntheticSourceExtensionItem(),
            tempDir,
            fixedMainPathInventory(tempDir),
        )
    }

    @Test
    fun `single manga migration records fixed-main Desktop replay without remaining debt`() {
        val item = manifestItems(repositoryRoot()).single { validatedId(it.jsonObject) == 67 }.jsonObject
        val desktopImplementation = item.getValue("desktopImplementation").jsonPrimitive.content
        val deviations = item.getValue("deviations").jsonArray.map { it.jsonObject }
        val protectionTests = item.getValue("protectionTests").jsonArray.map { it.jsonPrimitive.content }.toSet()

        assertEquals("WIRED", item.getValue("status").jsonPrimitive.content)
        assertTrue(desktopImplementation.contains(fixedOriginalMihonRef))
        assertTrue(desktopImplementation.contains("shared migration plan"))
        assertTrue(desktopImplementation.contains("Desktop consumer"))
        assertTrue(desktopImplementation.contains("end-to-end"))
        assertFalse(desktopImplementation.contains("atomic commit"))
        assertFalse(desktopImplementation.contains("returned-object reread"))
        assertFalse(
            deviations.any { it.getValue("classification").jsonPrimitive.content == "UNCLASSIFIED_DEBT" },
        )
        assertTrue(
            deviations.any {
                it.getValue("classification").jsonPrimitive.content == "MIGRATION_OUTPUT" &&
                    it.getValue("description").jsonPrimitive.content.contains("not original-Mihon authority")
            },
        )
        assertTrue(
            deviations.any {
                it.getValue("classification").jsonPrimitive.content == "DESKTOP_PRODUCT_ENHANCEMENT" &&
                    it.getValue("description").jsonPrimitive.content.contains("metadata/category/source removal") &&
                    it.getValue("description").jsonPrimitive.content.contains("atomic transaction")
            },
        )
        assertTrue(
            deviations.any {
                it.getValue("classification").jsonPrimitive.content == "DESKTOP_PRODUCT_ENHANCEMENT" &&
                    it.getValue("description").jsonPrimitive.content.contains("persisted target reread")
            },
        )
        assertTrue(
            "app-desktop/src/test/kotlin/mihon/desktop/domain/DesktopMigrateMangaUseCaseIntegrationTest.kt" in
                protectionTests,
        )
        assertTrue(
            "data/src/jvmTest/kotlin/tachiyomi/data/manga/MangaRepositoryMembershipIntegrationTest.kt" in
                protectionTests,
        )
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
        assertTrue(
            "domain/src/commonTest/kotlin/tachiyomi/domain/track/service/TrackerProviderContractTest.kt" in trackingTests,
        )
        assertEquals("WIRED", migration.getValue("status").jsonPrimitive.content)
        assertEquals("CHARACTERIZED", tracking.getValue("status").jsonPrimitive.content)
    }

    @Test
    fun `reader tracker trigger parity records fixed-main replay and its production protection`() {
        val item = manifestItems(repositoryRoot()).single { validatedId(it.jsonObject) == 70 }.jsonObject
        val deviations = item.getValue("deviations").jsonArray.map { it.jsonObject }
        val replay = deviations.single {
            it.getValue("classification").jsonPrimitive.content == "MIGRATION_OUTPUT" &&
                it.getValue("description").jsonPrimitive.content.contains("incognito") &&
                it.getValue("description").jsonPrimitive.content.contains("auto-update preference") &&
                it.getValue("description").jsonPrimitive.content.contains("non-cancellable completion")
        }
        val tests = item.getValue("protectionTests").jsonArray.map { it.jsonPrimitive.content }

        assertTrue(replay.getValue("description").jsonPrimitive.content.contains("fixed-main reader trigger"))
        assertTrue(replay.getValue("description").jsonPrimitive.content.contains("source extension package"))
        assertTrue("app-desktop/src/test/kotlin/mihon/desktop/domain/ReaderProgressTrackerTest.kt" in tests)
        assertTrue(
            "app-desktop/src/test/kotlin/mihon/desktop/tracking/TrackingAutoSyncPreferenceWiringTest.kt" in tests,
        )
        assertTrue("app-desktop/src/test/kotlin/mihon/desktop/extension/ExtensionManagerTest.kt" in tests)
        assertTrue(
            "app-desktop/src/test/kotlin/mihon/desktop/extension/ExtensionIncognitoPreferenceWiringTest.kt" in tests,
        )
        assertTrue("app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt" in tests)
        assertEquals("CHARACTERIZED", item.getValue("status").jsonPrimitive.content)
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
            if (id in structuredProvenanceIds) {
                assertTrue(
                    item.getValue("sharedImplementationPaths").jsonArray.any {
                        it.jsonPrimitive.content.startsWith("domain/src/commonMain/")
                    },
                    "ID $id must name its shared implementation separately from fixed-main provenance",
                )
                assertTrue(
                    item.getValue("desktopConsumerAdapterPaths").jsonArray.any {
                        it.jsonPrimitive.content.startsWith("app-desktop/src/main/")
                    },
                    "ID $id must name the production Desktop consumer separately from fixed-main provenance",
                )
            } else {
                assertTrue(
                    item.getValue("authoritativeImplementation").jsonPrimitive.content.contains("domain/src/commonMain"),
                    "ID $id must name its shared implementation",
                )
                assertTrue(
                    item.getValue("desktopImplementation").jsonPrimitive.content.contains("app-desktop/src/main"),
                    "ID $id must name the production Desktop consumer",
                )
            }
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
                    item.getValue("desktopImplementation").jsonPrimitive.content +
                    "\n" +
                    listOf("sharedImplementationPaths", "currentAndroidConsumerPaths", "desktopConsumerAdapterPaths")
                        .flatMap { field -> item[field]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty() }
                        .joinToString("\n")
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

    private fun validateSourceExtensionProvenance(
        item: JsonObject,
        repositoryRoot: Path,
        fixedMainPathInventory: Map<String, String>,
    ) {
        val id = validatedId(item)
        val upstreamRef = requiredText(item, "upstreamRef", id)
        assertEquals(fixedOriginalMihonRef, upstreamRef, "ID $id must use the exact fixed original Mihon ref")
        if (id in platformProvenanceBatchOneIds) {
            assertEquals(
                platformProvenanceBatchOneStatuses.getValue(id),
                requiredText(item, "status", id),
                "ID $id must retain its exact evidence status",
            )
            val authority = requiredText(item, "authoritativeImplementation", id)
            assertTrue(
                authority.startsWith("Fixed-main original Mihon capability #$id:"),
                "ID $id authority must name fixed-main original Mihon",
            )
            listOf("current android", "current desktop", "/src/commonmain/", "/src/jvmmain/", "shared implementation").forEach { marker ->
                assertFalse(authority.lowercase().contains(marker), "ID $id authority must not promote current/shared code: $marker")
            }
        }

        val upstreamSymbols = item["upstreamSymbols"]?.jsonArray
            ?: throw AssertionError("ID $id: upstreamSymbols must be an explicit array")
        assertTrue(upstreamSymbols.isNotEmpty(), "ID $id: upstreamSymbols must not be empty")
        upstreamSymbols.forEachIndexed { index, element ->
            val symbol = element.jsonObject
            val path = requiredText(symbol, "path", id, "upstreamSymbols[$index]")
            requiredText(symbol, "symbol", id, "upstreamSymbols[$index]")
            if (id == 43) {
                assertFalse(
                    path in forkOnlyReaderPairingPaths,
                    "ID 43: fork-only reader pairing path $path must not be listed as a fixed-main upstream symbol",
                )
            }
            assertTrue(
                path.endsWith(".kt") && !Path.of(path).isAbsolute && path.split('/').none { it == ".." },
                "ID $id: incomplete upstream path $path",
            )
            assertTrue(
                path in fixedMainPathInventory,
                "ID $id: fixed-main inventory does not contain upstream path $path",
            )
            assertFalse(
                "/src/test/" in path,
                "ID $id: current tests cannot be used as fixed-main upstream evidence: $path",
            )
        }
        exactAuthorityPaths[id]?.let { expected ->
            val actual = upstreamSymbols.map { it.jsonObject.getValue("path").jsonPrimitive.content }.toSet()
            assertEquals(expected, actual, "ID $id must declare the exact fixed-main authority paths")
        }

        validateCurrentPaths(item, "sharedImplementationPaths", id, repositoryRoot, allowEmpty = true)
        val androidRoots =
            when (id) {
                29 -> setOf("app/src/main/", "data/src/androidMain/")
                40 -> setOf("app/src/main/", "core/common/src/androidMain/")
                else -> setOf("app/src/main/")
            }
        validateCurrentPaths(item, "currentAndroidConsumerPaths", id, repositoryRoot, requiredPrefixes = androidRoots)
        val desktopRoots =
            if (id == 7) setOf("app-desktop/src/main/", "core/common/src/jvmMain/") else setOf("app-desktop/src/main/")
        validateCurrentPaths(item, "desktopConsumerAdapterPaths", id, repositoryRoot, requiredPrefixes = desktopRoots)
        exactBatchOneSharedPaths[id]?.let { assertExactPaths(item, "sharedImplementationPaths", id, it) }
        exactBatchOneAndroidPaths[id]?.let { assertExactPaths(item, "currentAndroidConsumerPaths", id, it) }
        exactBatchOneDesktopPaths[id]?.let { assertExactPaths(item, "desktopConsumerAdapterPaths", id, it) }

        val deviations = item["deviations"]?.jsonArray
            ?: throw AssertionError("ID $id: deviations must be an explicit array")
        deviations.forEachIndexed { index, element ->
            val deviation = element.jsonObject
            val context = "deviations[$index]"
            val classification = requiredText(deviation, "classification", id, context)
            assertTrue(
                classification in allowedDeviationClassifications,
                "ID $id: $context.classification must be one of $allowedDeviationClassifications",
            )
            requiredText(deviation, "description", id, context)
        }
        exactBatchOneDeviationClassifications[id]?.let { expected ->
            val actual = deviations.map { it.jsonObject.getValue("classification").jsonPrimitive.content }.toSet()
            assertEquals(expected, actual, "ID $id must retain exact deviation classifications")
        }
        requiredAuthorityBoundaryTerms[id]?.forEach { (requiredTerm, expectedClassification) ->
            val matches =
                deviations.filter {
                    it.jsonObject.getValue("description").jsonPrimitive.content.contains(requiredTerm)
                }
            assertTrue(
                matches.isNotEmpty(),
                "ID $id deviations must explicitly record `$requiredTerm`",
            )
            assertTrue(
                matches.all {
                    it.jsonObject.getValue("classification").jsonPrimitive.content == expectedClassification
                },
                "ID $id deviations containing `$requiredTerm` must be classified as $expectedClassification",
            )
        }
    }

    private fun assertExactPaths(item: JsonObject, field: String, id: Int, expected: Set<String>) {
        val actual = item.getValue(field).jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(expected, actual, "ID $id must retain exact $field")
    }

    private fun validateCurrentPaths(
        item: JsonObject,
        field: String,
        id: Int,
        repositoryRoot: Path,
        requiredPrefixes: Set<String>? = null,
        allowEmpty: Boolean = false,
    ) {
        val paths = item[field]?.jsonArray
            ?: throw AssertionError("ID $id: $field must be an explicit array")
        if (!allowEmpty) assertTrue(paths.isNotEmpty(), "ID $id: $field must not be empty")
        paths.forEachIndexed { index, element ->
            val path = element.jsonPrimitive.content
            assertTrue(path.isNotBlank(), "ID $id: $field[$index] must not be blank")
            if (field == "sharedImplementationPaths") assertTrue("/src/commonMain/" in path, "ID $id: $field[$index] must be commonMain: $path")
            if (requiredPrefixes != null) {
                assertTrue(requiredPrefixes.any(path::startsWith), "ID $id: $field[$index] must start with one of $requiredPrefixes")
            }
            assertTrue(Files.isRegularFile(repositoryRoot.resolve(path)), "ID $id: missing $field path $path")
        }
    }

    private fun requiredText(item: JsonObject, field: String, id: Int, context: String? = null): String {
        val value = item[field]?.jsonPrimitive?.content
        assertTrue(value?.isNotBlank() == true, "ID $id: ${context?.plus(".").orEmpty()}$field must not be blank")
        return value!!
    }

    private fun fixedMainPathInventory(repositoryRoot: Path): Map<String, String> {
        val resource = repositoryRoot.resolve(fixedMainPathInventoryResource)
        assertTrue(Files.isRegularFile(resource), "Missing fixed-main path inventory $fixedMainPathInventoryResource")
        return fixedMainPathInventory(Json.parseToJsonElement(Files.readString(resource)).jsonObject)
    }

    private fun fixedMainPathInventory(inventory: JsonObject): Map<String, String> {
        val ref = inventory["upstreamRef"]?.jsonPrimitive?.content
        assertEquals(fixedOriginalMihonRef, ref, "Fixed-main inventory must use the exact fixed original Mihon ref")

        val entries = inventory["paths"]?.jsonArray
            ?: throw AssertionError("Fixed-main inventory paths must be an explicit array")
        assertTrue(entries.isNotEmpty(), "Fixed-main inventory paths must not be empty")
        return buildMap {
            entries.forEachIndexed { index, element ->
                val entry = element.jsonObject
                val path = entry["path"]?.jsonPrimitive?.content
                assertTrue(path?.isNotBlank() == true, "Fixed-main inventory paths[$index].path must not be blank")
                assertTrue(
                    path!!.split('/').all { it.isNotBlank() && it != "." && it != ".." } &&
                        !Path.of(path).isAbsolute &&
                        '\\' !in path,
                    "Fixed-main inventory paths[$index].path must be repository-relative: $path",
                )
                val blobId = entry["blobId"]?.jsonPrimitive?.content
                assertTrue(
                    blobId?.matches(Regex("[0-9a-f]{40}")) == true,
                    "Fixed-main inventory paths[$index].blobId must be lowercase 40-hex",
                )
                assertTrue(put(path, blobId!!) == null, "Fixed-main inventory path must be unique: $path")
            }
            exactAuthorityBlobIds.forEach { (path, expectedBlobId) ->
                get(path)?.let { actualBlobId ->
                    assertEquals(expectedBlobId, actualBlobId, "Fixed-main inventory has the wrong blob for $path")
                }
            }
        }
    }

    private fun buildFixedMainPathInventory(
        ref: String = fixedOriginalMihonRef,
        paths: List<Pair<String, String>> = listOf("app/src/main/Upstream.kt" to "0".repeat(40)),
    ) =
        buildJsonObject {
            put("upstreamRef", ref)
            put(
                "paths",
                buildJsonArray {
                    paths.forEach { (path, blobId) ->
                        add(
                            buildJsonObject {
                                put("path", path)
                                put("blobId", blobId)
                            },
                        )
                    }
                },
            )
        }

    private fun createSyntheticConsumerFiles() {
        listOf("app/src/main/Current.kt", "app-desktop/src/main/Desktop.kt").forEach { path ->
            val file = tempDir.resolve(path)
            Files.createDirectories(file.parent)
            if (!Files.exists(file)) Files.createFile(file)
        }
    }

    private fun syntheticSourceExtensionItem(
        id: Int = 32,
        upstreamPath: String = "app/src/main/Upstream.kt",
        sharedImplementationPaths: List<String> = emptyList(),
        currentAndroidConsumerPaths: List<String> = listOf("app/src/main/Current.kt"),
        deviations: List<Pair<String?, String>> = listOf("PLATFORM_ADAPTER" to "Platform-specific behavior."),
    ) =
        buildJsonObject {
            put("id", id)
            put("upstreamRef", fixedOriginalMihonRef)
            put(
                "upstreamSymbols",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("path", upstreamPath)
                            put("symbol", "Upstream")
                        },
                    )
                },
            )
            put("sharedImplementationPaths", buildJsonArray { sharedImplementationPaths.forEach { add(it) } })
            put("currentAndroidConsumerPaths", buildJsonArray { currentAndroidConsumerPaths.forEach { add(it) } })
            put("desktopConsumerAdapterPaths", buildJsonArray { add("app-desktop/src/main/Desktop.kt") })
            put(
                "deviations",
                buildJsonArray {
                    deviations.forEach { (classification, description) ->
                        add(
                            buildJsonObject {
                                classification?.let { put("classification", it) }
                                put("description", description)
                            },
                        )
                    }
                },
            )
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
