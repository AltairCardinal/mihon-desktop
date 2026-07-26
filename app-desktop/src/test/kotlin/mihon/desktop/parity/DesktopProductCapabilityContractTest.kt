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
import org.junit.jupiter.api.Tag
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
    private val validStatuses = setOf("NOT_STARTED", "CHARACTERIZED", "SHARED", "WIRED", "VERIFIED", "CANDIDATE", "EXEMPT")
    private val terminalStatuses = setOf("VERIFIED", "EXEMPT")
    private val requiredTerminalEvidenceRoles =
        setOf("FIXED_ORIGINAL", "CURRENT_ANDROID", "SHARED_OR_ADAPTER", "DESKTOP_CONSUMER", "FIXTURE")
    private val task2ProvenanceStatuses =
        mapOf(9 to "VERIFIED", 10 to "VERIFIED", 11 to "WIRED", 12 to "VERIFIED", 16 to "SHARED", 17 to "SHARED", 19 to "WIRED", 22 to "SHARED")
    private val task2BehaviorMethods =
        mapOf(
            9 to mapOf("app-desktop/src/test/kotlin/mihon/desktop/reader/SkiaImageDecoderTest.kt" to setOf("decode JPEG returns correct dimensions")),
            10 to mapOf("app-desktop/src/test/kotlin/mihon/desktop/task/DesktopTaskSchedulerIntegrationTest.kt" to setOf("checkpoint and cancellation obey legal terminal transitions")),
            11 to mapOf("app-desktop/src/test/kotlin/mihon/desktop/domain/DesktopSystemNotifierTest.kt" to setOf("falls back to in-app notification when system delivery is unavailable")),
            12 to mapOf("app-desktop/src/test/kotlin/mihon/desktop/CrashHandlerTest.kt" to setOf("appendCrashReport creates parent directory and writes report")),
            16 to mapOf("app-desktop/src/test/kotlin/mihon/desktop/ui/library/LibraryCategoryBehaviorTest.kt" to setOf("category dialog intents perform create rename reorder and delete through production DI")),
            17 to mapOf("app-desktop/src/test/kotlin/mihon/desktop/ui/library/LibraryScreenModelTest.kt" to setOf("complete filter flags flow from state to visible list including local and tracking boundaries")),
            19 to mapOf("app-desktop/src/test/kotlin/mihon/desktop/ui/library/LibraryParityIntegrationTest.kt" to setOf("batch category assignment reports partial failure and continues", "library model exposes batch category partial failure to UI")),
            22 to
                mapOf(
                    "domain/src/commonTest/kotlin/tachiyomi/domain/manga/interactor/UpdateLibraryMembershipTest.kt" to
                        setOf("adding favorite synchronizes selected categories", "removing favorite clears category links"),
                    "data/src/jvmTest/kotlin/tachiyomi/data/manga/MangaRepositoryMembershipIntegrationTest.kt" to
                        setOf("membership update commits favorite date and categories together", "invalid category rolls back every manga membership update"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/library/MangaDetailScreenModelTest.kt" to
                        setOf("toggleLibrary clears favorite date and categories when removing"),
                ),
        )
    private val task3ProvenanceStatuses =
        mapOf(24 to "SHARED", 26 to "WIRED", 44 to "VERIFIED", 45 to "VERIFIED", 47 to "VERIFIED", 49 to "VERIFIED", 51 to "VERIFIED", 53 to "VERIFIED")
    private val task3BehaviorMethods =
        mapOf(
            24 to
                mapOf(
                    "domain/src/commonTest/kotlin/tachiyomi/domain/chapter/interactor/BatchUpdateChaptersTest.kt" to
                        setOf("continues after failure and reports each item"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/library/MangaDetailScreenModelTest.kt" to
                        setOf("selected read action exposes partial failure in state"),
                ),
            26 to
                mapOf(
                    "domain/src/commonTest/kotlin/tachiyomi/domain/manga/interactor/UpdateCustomCoverTest.kt" to
                        setOf("successful write invalidates cover cache timestamp"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/library/MangaDetailScreenModelTest.kt" to
                        setOf("cover permission failure is visible and does not refresh cache"),
                ),
            44 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/reader/SkiaImageDecoderTest.kt" to
                        setOf("Skia region adapter decodes only the requested tile"),
                ),
            45 to
                mapOf(
                    "domain/src/commonTest/kotlin/mihon/domain/reader/ReaderParityContractTest.kt" to
                        setOf("fixed original Mihon preload window keeps forward-only behavior"),
                    "app-desktop/src/test/kotlin/mihon/desktop/reader/PagePreloaderTest.kt" to
                        setOf("page change cancels every active or queued old generation request"),
                ),
            47 to
                mapOf(
                    "domain/src/commonTest/kotlin/mihon/domain/reader/ReaderParityContractTest.kt" to
                        setOf("chapter transition exposes wait loading loaded error missing count and retry command"),
                    "app/src/test/java/eu/kanade/tachiyomi/ui/reader/model/ReaderChapterTransitionIntegrationTest.kt" to
                        setOf(
                            "pager holder production observer executes loading error and loaded states",
                            "webtoon holder production observer executes loading error and loaded states",
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/DesktopReaderChapterTransitionIntegrationTest.kt" to
                        setOf("production adjacent chain publishes loading error retry loaded and navigates with loaded pages"),
                ),
            49 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/TapZoneTest.kt" to
                        setOf("tap regions delegate to the shared navigation presets"),
                ),
            51 to
                mapOf(
                    "app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReaderSharedParityWiringTest.kt" to
                        setOf("current Android consumer grayscale and invert preferences map to the shared filter contract"),
                    "app-desktop/src/test/kotlin/mihon/desktop/reader/ReaderSettingsModelsTest.kt" to
                        setOf("grayscale and invert are effective shared filter modes"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/ReaderColorMatrixTest.kt" to
                        setOf("mounted reader viewport color layer renders disabled grayscale and invert pixels"),
                ),
            53 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/domain/ReaderProgressTrackerTest.kt" to
                        setOf("reading to last page marks shared event as read"),
                ),
        )
    private val task4ProvenanceStatuses =
        mapOf(54 to "WIRED", 56 to "WIRED", 57 to "VERIFIED", 59 to "VERIFIED", 61 to "VERIFIED", 62 to "VERIFIED", 64 to "VERIFIED", 66 to "SHARED")
    private val task4BehaviorMethods =
        mapOf(
            54 to
                mapOf(
                    "domain/src/commonTest/kotlin/mihon/domain/reader/ReaderParityContractTest.kt" to
                        setOf("read filtered and duplicate skip rules can be combined"),
                    "app-desktop/src/test/kotlin/mihon/desktop/reader/ReaderNavigatorTest.kt" to
                        setOf("reader navigator combines read filtered and duplicate skip flags"),
                ),
            56 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/download/DownloadQueueSourceGroupingWiringTest.kt" to
                        setOf("queue renders one header per source with source names and a stable missing-source fallback"),
                ),
            57 to
                mapOf(
                    "domain/src/commonTest/kotlin/mihon/domain/download/DownloadQueueStateMachineTest.kt" to
                        setOf(
                            "scheduler is fair between sources while preserving per source order",
                            "retry uses Android exponential backoff and stops after three retries",
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/download/DownloadManagerReactivityTest.kt" to
                        setOf("three items enqueued after start all leave QUEUED state"),
                    "app-desktop/src/test/kotlin/mihon/desktop/download/DesktopDownloadRetryIntegrationTest.kt" to
                        setOf("HTTP failures use 2 4 8 retry policy without sleeping"),
                ),
            59 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/domain/FilterChaptersForDownloadIntegrationTest.kt" to
                        setOf("开启后保留候选数量与上游排序且正确处理空列表"),
                    "app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt" to
                        setOf("reinitializing test DI replaces every binding and scheduler context"),
                ),
            61 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/domain/LibraryUpdateRecoveryIntegrationTest.kt" to
                        setOf("real update emits progress and one terminal success event"),
                ),
            62 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/updates/UpdatesScreenModelTest.kt" to
                        setOf("loadUpdates applies downloaded filter without losing raw items", "markAllRead marks unread visible items and closes dialog"),
                ),
            64 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/history/HistoryScreenModelTest.kt" to
                        setOf("loadHistory updates search query and items", "removeHistory removes one item and refreshes current query"),
                ),
            66 to
                mapOf(
                    "domain/src/commonTest/kotlin/tachiyomi/domain/library/interactor/AggregateLibraryStatsTest.kt" to
                        setOf("distinct titles aggregate categories sources statuses and chapters"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/more/StatsScreenModelTest.kt" to
                        setOf("state moves from loading to shared aggregation and exposes errors"),
                ),
        )
    private val task5ProvenanceStatuses =
        mapOf(71 to "WIRED", 72 to "WIRED", 73 to "WIRED", 74 to "VERIFIED", 93 to "NOT_STARTED", 95 to "NOT_STARTED", 96 to "NOT_STARTED")
    private val task5BehaviorMethods =
        mapOf(
            71 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/backup/DesktopBackupCreatorTest.kt" to
                        setOf("createFromDatabase collects tracking app preferences source preferences and extension repositories"),
                ),
            72 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/backup/DesktopBackupRestorerTest.kt" to
                        setOf("first Desktop protobuf fixture follows the current restore chain"),
                    "app-desktop/src/test/kotlin/mihon/desktop/backup/BackupWorkflowIntegrationTest.kt" to
                        setOf("partial restore is reported as recoverable partial failure"),
                ),
            73 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/backup/AutoBackupSchedulerTest.kt" to
                        setOf("pruneOldBackups keeps only maxBackups files"),
                ),
            74 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/backup/DesktopBackupCompatibilityTest.kt" to
                        setOf(
                            "canonical writer preserves every Android backup section",
                            "first Desktop protobuf writer fixture restores every historical field",
                        ),
                ),
            93 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/settings/DesktopSettingsSecurityAdvancedAccessibilityTest.kt" to
                        setOf("Advanced fields and dangerous confirmation expose honest keyboard semantics"),
                ),
            95 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/architecture/DesktopArchitectureGuardTest.kt" to
                        setOf(
                            "desktop ui DI and repository debt does not grow beyond baseline",
                            "android main source must not depend on desktop runtime or awt swing",
                        ),
                ),
            96 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/extension/ExtensionCompatibilityTest.kt" to
                        setOf("loader loads minimal source from ServiceLoader JAR"),
                    "app-desktop/src/test/kotlin/mihon/desktop/compat/AndroidCompatTest.kt" to
                        setOf("Application and ContextWrapper preserve Android Context inheritance"),
                ),
        )
    private val task6Statuses =
        linkedMapOf(
            9 to "VERIFIED",
            3 to "CHARACTERIZED",
            4 to "VERIFIED",
            7 to "WIRED",
            8 to "SHARED",
            10 to "VERIFIED",
            11 to "WIRED",
            12 to "VERIFIED",
        )
    private val task6FollowUps =
        mapOf(
            3 to "Task 14",
            4 to "Task 14",
            7 to "Task 6 evidence gap: current Android PreferenceStore adapter behavior contract",
            8 to "Task 16C",
            9 to "NONE",
            10 to "Task 16B",
            11 to "Task 17",
            12 to "NONE",
        )
    private val task6BehaviorMethods =
        mapOf(
            3 to
                mapOf(
                    "domain/src/commonTest/kotlin/mihon/domain/error/AppErrorTest.kt" to
                        setOf("partial failure identifies each failed unit"),
                    "domain/src/commonTest/kotlin/mihon/domain/task/TaskStateTest.kt" to
                        setOf("任务状态表达进度结果失败与取消"),
                ),
            4 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt" to
                        setOf(
                            "测试配置入口使用隔离内存存储并解析实际依赖",
                            "reinitializing while scheduler runs joins old work and closes old database",
                        ),
                ),
            7 to
                mapOf(
                    "core/common/src/jvmTest/kotlin/tachiyomi/core/common/preference/DesktopPreferenceStoreTest.kt" to
                        setOf(
                            "getString persists and reads value",
                            "changes emits current value on subscribe",
                            "delete removes value and reverts to default",
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/settings/DesktopPreferenceMigrationTest.kt" to
                        setOf(
                            "legacy explicit dual-page enhancement remains enabled when current value is absent",
                            "legacy explicit LTR reader direction remains enabled when current value is absent",
                        ),
                ),
            8 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/network/NetworkErrorContractTest.kt" to
                        setOf(
                            "real source parses successful response",
                            "429 preserves retry after",
                        ),
                ),
            9 to
                mapOf(
                    "app/src/test/java/eu/kanade/tachiyomi/data/coil/AndroidReaderPageDecoderContractTest.kt" to
                        setOf(
                            "production Tachiyomi decoder forwards reader request identity through decode",
                            "production Tachiyomi decoder rejects a stale shared result before image submission",
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/reader/SkiaImageDecoderTest.kt" to
                        setOf(
                            "decode JPEG returns correct dimensions",
                            "peekSize returns null for invalid bytes",
                        ),
                ),
            10 to
                mapOf(
                    "domain/src/commonTest/kotlin/mihon/domain/task/BackgroundTaskContractTest.kt" to
                        setOf("notification events cover every terminal task state"),
                    "app-desktop/src/test/kotlin/mihon/desktop/task/DesktopTaskSchedulerIntegrationTest.kt" to
                        setOf(
                            "checkpoint and cancellation obey legal terminal transitions",
                            "corrupt store is quarantined and startup remains available",
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/domain/LibraryUpdateRecoveryIntegrationTest.kt" to
                        setOf(
                            "real update emits progress and one terminal success event",
                            "outer failure records failed state instead of being swallowed",
                        ),
                ),
            11 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/domain/DesktopSystemNotifierTest.kt" to
                        setOf(
                            "falls back to in-app notification when system delivery is unavailable",
                            "falls back when system adapter throws",
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/domain/LibraryUpdateRecoveryIntegrationTest.kt" to
                        setOf("real update emits progress and one terminal success event"),
                ),
            12 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/CrashHandlerTest.kt" to
                        setOf(
                            "appendCrashReport creates parent directory and writes report",
                            "appendCrashReport rotates existing oversized log before writing",
                            "install registers the production default handler",
                            "uncaught handler reports original exception when crash log write fails",
                        ),
                ),
        )
    private val task7ChildPlan = "docs/superpowers/plans/2026-07-24-task-7a-library-batch-action-parity.md"
    private val task7Statuses =
        mapOf(17 to "VERIFIED", 29 to "VERIFIED", 16 to "SHARED", 19 to "VERIFIED", 22 to "SHARED", 24 to "SHARED", 26 to "WIRED", 28 to "WIRED")
    private val task7FollowUps =
        mapOf(
            16 to "Task 7 evidence gap: category failure behavior and current Android contract",
            17 to "NONE",
            19 to "NONE",
            22 to "Task 14",
            24 to "Task 7 evidence gap: current Android shared chapter batch consumption",
            26 to "Task 7 evidence gap: current Android shared cover workflow consumption",
            28 to "Task 7 evidence gap: shared source membership projection",
            29 to "NONE",
        )
    private val task7BehaviorMethods =
        mapOf(
            16 to mapOf("app-desktop/src/test/kotlin/mihon/desktop/ui/library/LibraryCategoryBehaviorTest.kt" to setOf("category dialog intents perform create rename reorder and delete through production DI")),
            17 to
                mapOf(
                    "app/src/test/java/eu/kanade/tachiyomi/ui/library/LibrarySharedEvaluationWiringTest.kt" to setOf("Android production filter and sort consumers execute shared evaluation behavior"),
                    "domain/src/commonTest/kotlin/tachiyomi/domain/library/interactor/EvaluateLibraryTest.kt" to setOf("all tri-state filters preserve Android IS NOT and disabled semantics", "every sort type supports both directions and final title collator tie-break"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/library/LibraryScreenModelTest.kt" to setOf("complete filter flags flow from state to visible list including local and tracking boundaries"),
                ),
            19 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/library/LibraryParityIntegrationTest.kt" to setOf("invert selection toggles only visible manga", "selection action bar exposes invert download and migrate entries", "library model exposes batch category partial failure to UI"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/library/LibraryScreenModelTest.kt" to setOf("markMangaRead updates every chapter for the manga", "removeFromLibrary clears favorite flag for each manga", "batch download preserves all six fixed-main chapter selections", "batch download skips queued downloading and downloaded chapters then continues after failure"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/NavigationContractTest.kt" to setOf("library migration queue and item search use the nested Screen navigator"),
                ),
            22 to
                mapOf(
                    "domain/src/commonTest/kotlin/tachiyomi/domain/manga/interactor/UpdateLibraryMembershipTest.kt" to setOf("adding favorite synchronizes selected categories", "removing favorite clears category links"),
                    "data/src/jvmTest/kotlin/tachiyomi/data/manga/MangaRepositoryMembershipIntegrationTest.kt" to setOf("membership update commits favorite date and categories together", "invalid category rolls back every manga membership update"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/library/MangaDetailScreenModelTest.kt" to setOf("toggleLibrary clears favorite date and categories when removing"),
                ),
            24 to
                mapOf(
                    "domain/src/commonTest/kotlin/tachiyomi/domain/chapter/interactor/BatchUpdateChaptersTest.kt" to setOf("continues after failure and reports each item"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/library/MangaDetailScreenModelTest.kt" to setOf("selected read action exposes partial failure in state"),
                ),
            26 to
                mapOf(
                    "domain/src/commonTest/kotlin/tachiyomi/domain/manga/interactor/UpdateCustomCoverTest.kt" to setOf("successful write invalidates cover cache timestamp", "write failure is structured and does not invalidate cache", "successful delete invalidates cover cache timestamp"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/library/MangaCoverAdapterTest.kt" to setOf("selected bytes use shared workflow and preserve structured failure"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/library/MangaDetailScreenModelTest.kt" to setOf("cover update success exposes feedback and refreshed model", "cover permission failure is visible and does not refresh cache", "cover delete success refreshes model and reports feedback"),
                ),
            28 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/browse/DesktopSourceListProjectorTest.kt" to setOf("last used is a first-group copy while pinned and language originals keep fixed-main order"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/browse/SourceMembershipReactiveWiringTest.kt" to setOf("mounted browse list reacts to installed reloaded and uninstalled extension sources"),
                ),
            29 to
                mapOf(
                    "app/src/test/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreenModelBehaviorTest.kt" to setOf("late old Pager generation cannot replace or pollute the current listing Pager", "production Pager publishes shared service content without calling source directly"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/browse/SourceLastUsedWiringTest.kt" to setOf("real navigation records last used outside incognito and the same mounted list reorders reactively", "real navigation records last used except for matching global or extension incognito"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/browse/SourceBrowseCanonicalResultWiringTest.kt" to setOf("closing materializer rejects a non cancellable stale publication", "empty source renders fixed main localized no results copy", "browse persists and observes canonical rows without opening a card"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/browse/SourceSharedStateWiringTest.kt" to setOf("source projector preserves content while a later page loads and fails"),
                ),
        )
    private val task8Statuses =
        mapOf(30 to "VERIFIED", 32 to "WIRED", 33 to "VERIFIED", 34 to "VERIFIED", 35 to "VERIFIED", 36 to "VERIFIED", 37 to "VERIFIED", 38 to "WIRED")
    private val task8FollowUps =
        mapOf(30 to "NONE", 32 to "Task 14", 33 to "NONE", 34 to "NONE", 35 to "Task 16A", 36 to "NONE", 37 to "NONE", 38 to "Task 18")
    private val task8BehaviorMethods =
        mapOf(
            30 to
                mapOf(
                    "app/src/test/java/eu/kanade/tachiyomi/ui/browse/source/globalsearch/SearchScreenModelBehaviorTest.kt" to setOf("production global search uses shared failure and recovery without direct source call"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/browse/GlobalSearchResultProductionWiringTest.kt" to setOf("production search gates canonical rows retries materialization and rejects old completion"),
                ),
            32 to emptyMap(),
            33 to
                mapOf(
                    "app/src/test/java/eu/kanade/tachiyomi/extension/api/ExtensionApiSharedCatalogTest.kt" to setOf("Android production API preserves successful repository when another repository fails", "Android production API maps malformed and HTTP repository failures"),
                    "app-desktop/src/test/kotlin/mihon/desktop/extension/DesktopExtensionApiSharedCatalogTest.kt" to setOf("Desktop production API preserves successful repository when another repository fails", "Desktop production API maps malformed and HTTP repository failures"),
                ),
            34 to
                mapOf(
                    "domain/src/jvmTest/kotlin/mihon/domain/extension/ExtensionInstallCoordinatorTest.kt" to setOf("successful install emits stages in order and only installs after reload", "reload failure rolls back artifact and metadata then verifies old runtime"),
                    "app/src/test/java/eu/kanade/tachiyomi/extension/AndroidExtensionInstallSecurityRollbackTest.kt" to setOf("downloaded digest repository continuity and signer are enforced", "download HTTP taxonomy remains distinct"),
                    "app-desktop/src/test/kotlin/mihon/desktop/extension/DesktopExtensionInstallTransactionTest.kt" to setOf("jvm jar installs through production api loader and manager", "http 404 maps to Server with status code"),
                ),
            35 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/extension/RealExtensionCompatEvidenceTest.kt" to setOf("immutable ManHuaGui APK loads through the production converter and loader"),
                ),
            36 to
                mapOf(
                    "domain/src/jvmTest/kotlin/mihon/domain/extension/ExtensionSharedContractTest.kt" to setOf("legacy sidecar without repository identity requires explicit confirmation"),
                    "app/src/test/java/eu/kanade/tachiyomi/extension/AndroidExtensionInstallSecurityRollbackTest.kt" to setOf("untrusted confirmation remains a failed terminal state"),
                    "app-desktop/src/test/kotlin/mihon/desktop/extension/DesktopExtensionApiSharedCatalogTest.kt" to setOf("Desktop existing extension with legacy sidecar missing identity requires trust before download"),
                ),
            37 to
                mapOf(
                    "app/src/test/java/eu/kanade/tachiyomi/ui/browse/extension/ExtensionPresentationWiringTest.kt" to setOf("android get extensions consumes shared classification and source projection"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/extension/ExtensionPresentationUiTest.kt" to setOf("search input filters through model state and survives content remount", "production content renders local empty data with failure and retries"),
                ),
            38 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/extension/RealExtensionMangaDexFactoryCompatTest.kt" to setOf("real MangaDex factory verifier links Android text callback descriptors"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/extension/ExtensionDetailsPreferencesWiringTest.kt" to setOf("source preference availability states stay distinct and content persists"),
                ),
        )
    private val task9Statuses =
        mapOf(39 to "VERIFIED", 40 to "VERIFIED", 43 to "VERIFIED", 44 to "VERIFIED", 45 to "VERIFIED", 47 to "VERIFIED", 49 to "VERIFIED", 51 to "VERIFIED")
    private val task9FollowUps =
        mapOf(39 to "Task 14", 40 to "NONE", 43 to "NONE", 44 to "NONE", 45 to "NONE", 47 to "NONE", 49 to "NONE", 51 to "NONE")
    private val task9BehaviorMethods =
        mapOf(
            39 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/network/DesktopBrowserLoginAdapterTest.kt" to setOf(
                        "opens external browser then commits controlled completion through the real jar",
                        "production committer late persistence failure publishes commit failed and restores old jar and file",
                    ),
                ),
            40 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/network/DesktopChallengeRecoveryPolicyTest.kt" to setOf(
                        "production client exposes upstream cloudflare interceptor runtime contract",
                        "successful recovery retries the intercepted request at most once",
                        "solver failure empty cookies and malformed response preserve old credentials",
                    ),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/cloudflare/DesktopChallengeLoginWiringTest.kt" to setOf(
                        "controller manual solver cancel and retry actions obey state and current resolver",
                    ),
                ),
            43 to
                mapOf(
                    "app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReaderSharedParityWiringTest.kt" to setOf("fork-added pairing adapter and shared core produce the same enhancement vectors"),
                    "domain/src/commonTest/kotlin/mihon/domain/reader/ReaderParityContractTest.kt" to setOf("fork-added shared portrait pairing enhancement groups adjacent pages"),
                    "app-desktop/src/test/kotlin/mihon/desktop/reader/VirtualPageListTest.kt" to setOf("LTR single spread produces LEFT then RIGHT"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/DesktopReaderProductRegressionTest.kt" to setOf("shared pairing keeps cover edge matching adjust and landscape parity enhancements"),
                ),
            44 to
                mapOf(
                    "app/src/test/java/eu/kanade/tachiyomi/data/coil/AndroidReaderPageDecoderContractTest.kt" to setOf("Android reader cache adapter keeps tiled pages out of Coil decoded caches"),
                    "app-desktop/src/test/kotlin/mihon/desktop/reader/SkiaImageDecoderTest.kt" to setOf("Skia region adapter decodes only the requested tile"),
                    "app-desktop/src/test/kotlin/mihon/desktop/reader/PagePreloaderTest.kt" to setOf("ordinary non large page path still enforces decoded dimension bounds"),
                ),
            45 to
                mapOf(
                    "app/src/test/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoaderIntegrationTest.kt" to setOf("rapid zero one two selection cancels stale jobs without error and reorders current first"),
                    "app-desktop/src/test/kotlin/mihon/desktop/reader/PagePreloaderTest.kt" to setOf(
                        "fast page change cancels stale preload and prevents a late cache write",
                        "page change cancels every active or queued old generation request",
                    ),
                ),
            47 to
                mapOf(
                    "app/src/test/java/eu/kanade/tachiyomi/ui/reader/model/ReaderChapterTransitionIntegrationTest.kt" to setOf(
                        "pager holder production observer executes loading error and loaded states",
                        "webtoon holder production observer executes loading error and loaded states",
                    ),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/DesktopReaderChapterTransitionIntegrationTest.kt" to setOf(
                        "production adjacent chain publishes loading error retry loaded and navigates with loaded pages",
                        "both adjacent boundaries never invoke the production loader",
                    ),
                ),
            49 to
                mapOf(
                    "app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReaderSharedParityWiringTest.kt" to setOf("current Android consumer navigation adapters match every shared preset and inversion"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/TapZoneTest.kt" to setOf("tap regions delegate to the shared navigation presets"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/ReaderKeyboardNavigationPositionTest.kt" to setOf("all tap presets move logical pages before mapping to LTR pager storage"),
                ),
            51 to
                mapOf(
                    "app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReaderSharedParityWiringTest.kt" to setOf("current Android consumer grayscale and invert preferences map to the shared filter contract"),
                    "app-desktop/src/test/kotlin/mihon/desktop/reader/ReaderSettingsModelsTest.kt" to setOf("grayscale and invert survive preference round trip"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/reader/ReaderColorMatrixTest.kt" to setOf(
                        "mounted reader viewport color layer renders disabled grayscale and invert pixels",
                    ),
                ),
        )
    private val task10Statuses =
        mapOf(
            53 to "VERIFIED",
            54 to "WIRED",
            56 to "WIRED",
            57 to "VERIFIED",
            59 to "VERIFIED",
            61 to "VERIFIED",
            62 to "VERIFIED",
            64 to "VERIFIED",
        )
    private val task10FollowUps =
        mapOf(53 to "NONE", 54 to "Task 14", 56 to "Task 14", 57 to "NONE", 59 to "NONE", 61 to "NONE", 62 to "NONE", 64 to "NONE")
    private val task10BehaviorMethods =
        mapOf(
            53 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/domain/ReaderProgressTrackerTest.kt" to
                        setOf("reading to last page marks shared event as read"),
                ),
            54 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/reader/ReaderNavigatorTest.kt" to
                        setOf("reader navigator combines read filtered and duplicate skip flags"),
                ),
            56 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/download/DownloadQueueSourceGroupingWiringTest.kt" to
                        setOf("queue renders one header per source with source names and a stable missing-source fallback"),
                ),
            57 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/download/DownloadManagerReactivityTest.kt" to
                        setOf("three items enqueued after start all leave QUEUED state"),
                    "app-desktop/src/test/kotlin/mihon/desktop/download/DesktopDownloadRetryIntegrationTest.kt" to
                        setOf("HTTP failures use 2 4 8 retry policy without sleeping"),
                ),
            59 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/domain/FilterChaptersForDownloadIntegrationTest.kt" to
                        setOf("开启后保留候选数量与上游排序且正确处理空列表"),
                    "app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt" to
                        setOf("reinitializing test DI replaces every binding and scheduler context"),
                ),
            61 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/domain/LibraryUpdateRecoveryIntegrationTest.kt" to
                        setOf("real update emits progress and one terminal success event"),
                ),
            62 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/updates/UpdatesScreenModelTest.kt" to
                        setOf("loadUpdates applies downloaded filter without losing raw items", "markAllRead marks unread visible items and closes dialog"),
                ),
            64 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/history/HistoryScreenModelTest.kt" to
                        setOf("loadHistory updates search query and items", "removeHistory removes one item and refreshes current query"),
                ),
        )
    private val task11Statuses =
        mapOf(
            66 to "SHARED",
            67 to "VERIFIED",
            68 to "VERIFIED",
            69 to "CHARACTERIZED",
            70 to "CHARACTERIZED",
            71 to "WIRED",
            72 to "WIRED",
            73 to "WIRED",
        )
    private val task11FollowUps =
        mapOf(
            66 to "Task 14",
            67 to "NONE",
            68 to "NONE",
            69 to "Task 14",
            70 to "Task 14",
            71 to "Task 14",
            72 to "Task 14",
            73 to "Task 14",
        )
    private val task11BehaviorMethods =
        mapOf(
            66 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/more/StatsScreenModelTest.kt" to
                        setOf("state moves from loading to shared aggregation and exposes errors"),
                ),
            67 to
                mapOf(
                    "domain/src/commonTest/kotlin/mihon/domain/migration/MigrationOrchestratorTest.kt" to
                        setOf("library plan copies categories notes reading flags and keeps source for copy"),
                    "app/src/test/java/mihon/domain/migration/usecases/MigrateMangaUseCaseChapterAdapterTest.kt" to
                        setOf("Android chapter adapter only writes read when shared patch changes target"),
                    "app-desktop/src/test/kotlin/mihon/desktop/domain/DesktopMigrateMangaUseCaseIntegrationTest.kt" to
                        setOf("copy categories and replace move real library membership without half state"),
                ),
            68 to
                mapOf(
                    "app/src/test/java/mihon/feature/migration/list/MigrationListScreenModelBatchWiringTest.kt" to
                        setOf("batch failure stays visible with title reason and retries only failed manga"),
                    "app-desktop/src/test/kotlin/mihon/desktop/migration/DesktopBatchMigrationControllerTest.kt" to
                        setOf("queue persists waiting selection options failures and continues other items"),
                ),
            69 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/tracking/DesktopProviderTrackerServiceTest.kt" to
                        setOf("production registry contains every public Android tracker with isolated credentials"),
                ),
            70 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/domain/ReaderProgressTrackerTest.kt" to
                        setOf("eligible tracker sync completes after caller cancellation"),
                ),
            71 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/backup/DesktopBackupCreatorTest.kt" to
                        setOf("createFromDatabase collects tracking app preferences source preferences and extension repositories"),
                ),
            72 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/backup/DesktopBackupRestorerTest.kt" to
                        setOf("first Desktop protobuf fixture follows the current restore chain"),
                    "app-desktop/src/test/kotlin/mihon/desktop/backup/BackupWorkflowIntegrationTest.kt" to
                        setOf("partial restore is reported as recoverable partial failure"),
                ),
            73 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/backup/AutoBackupSchedulerTest.kt" to
                        setOf("pruneOldBackups keeps only maxBackups files"),
                ),
        )
    private val task12Statuses =
        mapOf(
            74 to "VERIFIED",
            81 to "CANDIDATE",
            82 to "CANDIDATE",
            83 to "CANDIDATE",
            84 to "CANDIDATE",
            85 to "EXEMPT",
            86 to "CANDIDATE",
            87 to "SHARED",
        )
    private val task12FollowUps =
        mapOf(
            74 to "NONE",
            81 to "Task 15",
            82 to "Task 15",
            83 to "Task 15",
            84 to "Task 15",
            85 to "NONE",
            86 to "Task 15",
            87 to "Task 14",
        )
    private val task12BehaviorMethods =
        mapOf(
            74 to
                mapOf(
                    "data/src/commonTest/kotlin/tachiyomi/data/backup/BackupCodecContractTest.kt" to
                        setOf("fixed-main Android full fixture decodes and reencodes with canonical schema"),
                    "app/src/test/java/eu/kanade/tachiyomi/data/backup/BackupAndroidCodecIntegrationTest.kt" to
                        setOf("decoder reads common codec backup through content uri"),
                    "app-desktop/src/test/kotlin/mihon/desktop/backup/DesktopBackupCompatibilityTest.kt" to
                        setOf("canonical writer preserves every Android backup section", "first Desktop protobuf writer fixture restores every historical field"),
                ),
            81 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/DesktopAppRuntimeTest.kt" to
                        setOf("macOS open URI bridge drains queued events once and uses the shared ViewUri ingress"),
                ),
            82 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/library/MangaShareWiringTest.kt" to
                        setOf("rendered manga actions bind copy and share through the desktop share service"),
                ),
            83 to
                mapOf(
                    "domain/src/commonTest/kotlin/mihon/domain/security/AppSecurityPolicyTest.kt" to
                        setOf("enabled app first process starts locked"),
                    "app/src/test/java/eu/kanade/tachiyomi/ui/security/AndroidSecuritySharedPolicyTest.kt" to
                        setOf("first process activity requires unlock when app lock is enabled"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/settings/SecuritySettingsWiringTest.kt" to
                        setOf("locked root never constructs protected content and only successful unlock restores it"),
                ),
            84 to
                mapOf(
                    "domain/src/commonTest/kotlin/mihon/domain/security/AppSecurityPolicyTest.kt" to
                        setOf("secure screen matrix only protects always and incognito modes"),
                    "app/src/test/java/eu/kanade/tachiyomi/ui/security/AndroidSecuritySharedPolicyTest.kt" to
                        setOf("secure screen adapter covers always incognito and never"),
                    "app-desktop/src/test/kotlin/mihon/desktop/privacy/WindowPrivacyWiringTest.kt" to
                        setOf("main production composable seam follows policy and clears before detach", "security UI exposes modes and structured window feedback"),
                ),
            85 to
                mapOf(
                    "presentation-widget/src/test/java/tachiyomi/presentation/widget/WidgetPrivacyProductionWiringTest.kt" to
                        setOf("base widget default constructor observes injected security preferences", "locked production data source never queries updates"),
                    "app-desktop/src/test/kotlin/mihon/desktop/parity/WidgetPrivacyBoundaryTest.kt" to
                        setOf("desktop exposes shared updates but no system widget provider"),
                ),
            86 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/update/DesktopUpdateControllerTest.kt" to
                        setOf("successful flow publishes progress and invokes every delegate in order"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/settings/AboutUpdateWiringTest.kt" to
                        setOf("about renders full version and routes ready confirmation intents"),
                ),
            87 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/i18n/DesktopExtensionListRenderedCopyTest.kt" to
                        setOf("extension list filter and uninstall render localized copy"),
                    "app-desktop/src/test/kotlin/mihon/desktop/i18n/MoreSourceExtensionRenderedCopyTest.kt" to
                        setOf("source extension entries render localized copy and navigate"),
                ),
        )
    private val task13Statuses =
        mapOf(
            88 to "VERIFIED",
            90 to "VERIFIED",
            91 to "VERIFIED",
            92 to "CANDIDATE",
            93 to "WIRED",
            94 to "VERIFIED",
            95 to "WIRED",
            96 to "VERIFIED",
        )
    private val task13FollowUps =
        mapOf(
            88 to "Task 14",
            90 to "NONE",
            91 to "NONE",
            92 to "Task 15",
            93 to "Task 14",
            94 to "NONE",
            95 to "Task 16C",
            96 to "Task 16A",
        )
    private val task13BehaviorMethods =
        mapOf(
            88 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/settings/DesktopSettingsContentAccessibilityTest.kt" to
                        setOf(
                            "Backup production button activates once on key down and respects disabled state",
                            "Library checkbox production row activates once on key down",
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/settings/DesktopSettingsAccessibilityContractTest.kt" to
                        setOf("General and Appearance rows expose one action role state and disabled semantics"),
                ),
            90 to
                mapOf(
                    "domain/src/commonTest/kotlin/mihon/domain/settings/SettingsSearchPolicyTest.kt" to
                        setOf("fixed main excludes disabled blank info and disabled or blank groups"),
                    "app/src/test/java/eu/kanade/presentation/more/settings/screen/SettingsSearchConsumerBehaviorTest.kt" to
                        setOf("real Android preference projection is searched by shared policy"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/settings/DesktopSettingsSearchWiringTest.kt" to
                        setOf(
                            "catalog delegates search to shared policy",
                            "search has feedback focus submission keys and result navigation",
                            "More search entry opens the production search screen",
                        ),
                ),
            91 to
                mapOf(
                    "presentation-theme/src/commonTest/kotlin/eu/kanade/presentation/theme/colorscheme/AppThemeColorSchemeTest.kt" to
                        setOf(
                            "every static app theme selects its fixed main palette",
                            "amoled only changes dark static colors and preserves fixed containers",
                        ),
                    "app/src/test/java/eu/kanade/presentation/theme/AndroidSharedPaletteWiringTest.kt" to
                        setOf("android theme consumer delegates static selection to shared selector"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/settings/DesktopSettingsSearchWiringTest.kt" to
                        setOf(
                            "desktop theme consumes shared static theme and amoled preferences",
                            "appearance selects static theme and amoled while preserving grid",
                        ),
                ),
            92 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/privacy/DesktopPrivacyCapabilitiesTest.kt" to
                        setOf("production capabilities report only real desktop integrations"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/settings/SecuritySettingsWiringTest.kt" to
                        setOf(
                            "catalog anchor preserves native toggle and unsupported capability boundaries",
                            "unsupported desktop privacy integrations show info without rendering controls",
                        ),
                ),
            93 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/settings/AdvancedSettingsScreenTest.kt" to
                        setOf("catalog result anchors once and preserves platform success and failure feedback"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/settings/DesktopSettingsSecurityAdvancedAccessibilityTest.kt" to
                        setOf("Advanced fields and dangerous confirmation expose honest keyboard semantics"),
                ),
            94 to
                mapOf(
                    "domain/src/commonTest/kotlin/mihon/domain/license/service/LicenseNoticePolicyTest.kt" to
                        setOf("first license is selected without later entries overwriting it"),
                    "app/src/test/java/eu/kanade/presentation/more/settings/screen/about/OpenSourceLicensesConsumerBehaviorTest.kt" to
                        setOf(
                            "real Android license candidates are delegated to shared policy in order",
                            "blank first Android license does not fall through to later content",
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/license/DesktopDependencyNoticeProviderTest.kt" to
                        setOf("provider maps metadata through the notice policy and caches the result"),
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/settings/AboutUpdateWiringTest.kt" to
                        setOf(
                            "about routes real injected dependency notices to their first license content",
                            "about renders full version and routes ready confirmation intents",
                        ),
                ),
            95 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/ui/library/LibraryScreenModelTest.kt" to
                        setOf(
                            "production library stream exposes only logged in tracker rows and reacts to login logout",
                        ),
                    "app-desktop/src/test/kotlin/mihon/desktop/architecture/DesktopArchitectureGuardTest.kt" to
                        setOf(
                            "desktop ui DI and repository debt does not grow beyond baseline",
                            "android main source must not depend on desktop runtime or awt swing",
                        ),
                ),
            96 to
                mapOf(
                    "app-desktop/src/test/kotlin/mihon/desktop/extension/CompatEvidenceContractTest.kt" to
                        setOf("inventory covers the complete public compat adapter surface"),
                    "app-desktop/src/test/kotlin/mihon/desktop/extension/RealExtensionBuildCompatTest.kt" to
                        setOf("real MangaDex headers use host version and Android release ABI"),
                    "app-desktop/src/test/kotlin/mihon/desktop/extension/RealExtensionWebViewUnsupportedCompatTest.kt" to
                        setOf("real Comix WebView path fails fast with the explicit desktop boundary"),
                ),
        )
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
            4 to "VERIFIED",
            7 to "WIRED",
            8 to "SHARED",
        )
    private val settingsParityIds = setOf(88, 90, 91, 94)
    private val structuredProvenanceIds =
        platformProvenanceBatchOneIds + setOf(28, 29, 30, 32, 33, 34, 35, 36, 37, 38, 39, 40, 43, 67, 68, 69, 70, 87) + settingsParityIds
    private val sourceExtensionParityStatuses =
        mapOf(
            28 to "WIRED",
            29 to "VERIFIED",
            30 to "VERIFIED",
            32 to "WIRED",
            33 to "VERIFIED",
            34 to "VERIFIED",
            35 to "VERIFIED",
            36 to "VERIFIED",
            37 to "VERIFIED",
            38 to "WIRED",
            39 to "VERIFIED",
            40 to "VERIFIED",
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
            "PRODUCT_GAP",
            "CROSS_PLATFORM_RELIABILITY_ENHANCEMENT",
            "DESKTOP_PRODUCT_ENHANCEMENT",
        )
    private val fixedOriginalMihonRef =
        "main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8"
    private val platformCapabilityEvidenceIds = setOf(81, 82, 83, 84, 86, 92)
    private val platformCapabilityFixedMainPaths = setOf(
        "app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt",
        "app/src/main/java/eu/kanade/tachiyomi/util/system/IntentExtensions.kt",
        "app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSecurityScreen.kt",
        "presentation-widget/src/main/java/tachiyomi/presentation/widget/BaseUpdatesGridGlanceWidget.kt",
        "app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt",
        "app/src/main/java/eu/kanade/presentation/more/NewUpdateScreen.kt",
        "domain/src/main/java/tachiyomi/domain/release/interactor/GetApplicationRelease.kt",
    )
    private val exactPlatformCapabilitySymbols = mapOf(
        81 to setOf("MainActivity.handleIntentAction ACTION_VIEW tachibk/add-repo"),
        82 to setOf("shareIntent"),
        83 to setOf("SettingsSecurityScreen"),
        84 to setOf("SettingsSecurityScreen secureScreen"),
        86 to setOf("AppUpdateChecker", "NewUpdateScreen", "GetApplicationRelease.await isNewVersion"),
        92 to setOf("SettingsSecurityScreen getSecurityGroup/getFirebaseGroup"),
    )
    private val exactPlatformCapabilityUpstream = mapOf(
        81 to setOf("app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt" to "MainActivity.handleIntentAction ACTION_VIEW tachibk/add-repo"),
        82 to setOf("app/src/main/java/eu/kanade/tachiyomi/util/system/IntentExtensions.kt" to "shareIntent"),
        83 to setOf("app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSecurityScreen.kt" to "SettingsSecurityScreen"),
        84 to setOf("app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSecurityScreen.kt" to "SettingsSecurityScreen secureScreen"),
        86 to setOf("app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt" to "AppUpdateChecker", "app/src/main/java/eu/kanade/presentation/more/NewUpdateScreen.kt" to "NewUpdateScreen", "domain/src/main/java/tachiyomi/domain/release/interactor/GetApplicationRelease.kt" to "GetApplicationRelease.await isNewVersion"),
        92 to setOf("app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSecurityScreen.kt" to "SettingsSecurityScreen getSecurityGroup/getFirebaseGroup"),
    )
    private val exactPlatformCapabilityShared = mapOf(
        81 to setOf("domain/src/commonMain/kotlin/mihon/domain/platform/ExternalAction.kt"), 82 to setOf("domain/src/commonMain/kotlin/mihon/domain/platform/ExternalShare.kt"),
        83 to setOf("core/common/src/commonMain/kotlin/eu/kanade/tachiyomi/core/security/SecurityPreferences.kt", "domain/src/commonMain/kotlin/mihon/domain/security/AppSecurityPolicy.kt"),
        84 to setOf("core/common/src/commonMain/kotlin/eu/kanade/tachiyomi/core/security/SecurityPreferences.kt", "domain/src/commonMain/kotlin/mihon/domain/security/AppSecurityPolicy.kt"),
        85 to setOf("domain/src/commonMain/kotlin/tachiyomi/domain/updates/interactor/GetUpdates.kt"),
        86 to setOf("domain/src/commonMain/kotlin/tachiyomi/domain/release/interactor/GetApplicationRelease.kt"),
        92 to setOf("core/common/src/commonMain/kotlin/eu/kanade/tachiyomi/core/security/SecurityPreferences.kt", "domain/src/commonMain/kotlin/mihon/domain/security/AppSecurityPolicy.kt"),
    )
    private val exactPlatformCapabilityAndroid = mapOf(
        81 to setOf("app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt"), 82 to setOf("app/src/main/java/eu/kanade/tachiyomi/util/system/IntentExtensions.kt"),
        83 to setOf("app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSecurityScreen.kt", "app/src/main/java/eu/kanade/tachiyomi/ui/base/delegate/SecureActivityDelegate.kt"),
        84 to setOf("app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSecurityScreen.kt", "app/src/main/java/eu/kanade/tachiyomi/ui/base/delegate/SecureActivityDelegate.kt"),
        85 to setOf("presentation-widget/src/main/java/tachiyomi/presentation/widget/BaseUpdatesGridGlanceWidget.kt", "presentation-widget/src/main/java/tachiyomi/presentation/widget/WidgetPrivacyDataSource.kt", "presentation-widget/src/main/java/tachiyomi/presentation/widget/WidgetManager.kt"),
        86 to setOf("app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt"),
        92 to setOf("app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSecurityScreen.kt", "app/src/main/java/eu/kanade/tachiyomi/ui/base/delegate/SecureActivityDelegate.kt"),
    )
    private val exactPlatformCapabilityConsumers = mapOf(
        81 to setOf("app-desktop/src/main/kotlin/mihon/desktop/Main.kt", "app-desktop/src/main/kotlin/mihon/desktop/DesktopAppRuntime.kt", "app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopOpenUriEventPort.kt", "app-desktop/src/main/kotlin/mihon/desktop/ui/ExternalActionNavigator.kt"),
        82 to setOf("app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopShareService.kt", "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/PageContextMenu.kt", "app-desktop/src/main/kotlin/mihon/desktop/ui/library/MangaDetailComponents.kt"),
        83 to setOf("app-desktop/src/main/kotlin/mihon/desktop/Main.kt", "app-desktop/src/main/kotlin/mihon/desktop/security/DesktopAppLock.kt", "app-desktop/src/main/kotlin/mihon/desktop/ui/security/DesktopUnlockSurface.kt", "app-desktop/src/main/kotlin/mihon/desktop/ui/settings/SecuritySettingsScreen.kt"),
        84 to setOf("app-desktop/src/main/kotlin/mihon/desktop/privacy/DesktopWindowPrivacy.kt", "app-desktop/src/main/kotlin/mihon/desktop/ui/settings/SecuritySettingsScreen.kt"),
        85 to setOf("app-desktop/src/main/kotlin/mihon/desktop/updates/UpdatesScreenModel.kt", "app-desktop/src/main/kotlin/mihon/desktop/privacy/DesktopPrivacyCapabilities.kt"),
        86 to setOf("app-desktop/src/main/kotlin/mihon/desktop/ui/settings/AboutScreen.kt", "app-desktop/src/main/kotlin/mihon/desktop/ui/settings/DesktopUpdateScreenModel.kt", "app-desktop/src/main/kotlin/mihon/desktop/update/DesktopUpdateController.kt", "app-desktop/src/main/kotlin/mihon/desktop/update/DesktopUpdateDownloader.kt", "app-desktop/src/main/kotlin/mihon/desktop/update/DesktopUpdateInstaller.kt", "app-desktop/src/main/kotlin/mihon/desktop/update/DesktopUpdateProcessRunner.kt", "app-desktop/src/main/kotlin/mihon/desktop/DesktopAppRuntime.kt", "app-desktop/src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt"),
        92 to setOf("app-desktop/src/main/kotlin/mihon/desktop/security/DesktopAppLock.kt", "app-desktop/src/main/kotlin/mihon/desktop/privacy/DesktopWindowPrivacy.kt", "app-desktop/src/main/kotlin/mihon/desktop/ui/settings/SecuritySettingsScreen.kt"),
    )
    private val exactPlatformCapabilityProtection: Map<Int, Map<String, Map<String, Set<String>>>> = mapOf(
        81 to mapOf(
            "app-desktop/src/test/kotlin/mihon/desktop/DesktopAppRuntimeTest.kt" to mapOf(
                "macOS open URI bridge drains queued events once and uses the shared ViewUri ingress" to setOf("wireDesktopOpenUriEvents", "ExternalActionInput.ViewUri"),
            ),
        ),
        82 to mapOf(
            "app-desktop/src/test/kotlin/mihon/desktop/ui/library/MangaShareWiringTest.kt" to mapOf(
                "rendered manga actions bind copy and share through the desktop share service" to setOf("MangaDetailActionRow", "DesktopShareService"),
            ),
        ),
        83 to mapOf(
            "domain/src/commonTest/kotlin/mihon/domain/security/AppSecurityPolicyTest.kt" to mapOf(
                "enabled app first process starts locked" to setOf("AppLockPolicy.initial"),
            ),
            "app/src/test/java/eu/kanade/tachiyomi/ui/security/AndroidSecuritySharedPolicyTest.kt" to mapOf(
                "first process activity requires unlock when app lock is enabled" to setOf("AndroidAppLockLifecycleConsumer"),
            ),
            "app-desktop/src/test/kotlin/mihon/desktop/ui/settings/SecuritySettingsWiringTest.kt" to mapOf(
                "locked root never constructs protected content and only successful unlock restores it" to setOf("DesktopAppLock", "DesktopProtectedRoot"),
            ),
        ),
        84 to mapOf(
            "domain/src/commonTest/kotlin/mihon/domain/security/AppSecurityPolicyTest.kt" to mapOf(
                "secure screen matrix only protects always and incognito modes" to setOf("SecureScreenPolicy.isProtected"),
            ),
            "app/src/test/java/eu/kanade/tachiyomi/ui/security/AndroidSecuritySharedPolicyTest.kt" to mapOf(
                "secure screen adapter covers always incognito and never" to setOf("AndroidSecureScreenConsumer"),
            ),
            "app-desktop/src/test/kotlin/mihon/desktop/privacy/WindowPrivacyWiringTest.kt" to mapOf(
                "main production composable seam follows policy and clears before detach" to setOf("BindDesktopWindowLifecycle", "DesktopWindowPrivacyController"),
                "security UI exposes modes and structured window feedback" to setOf("DesktopSecureScreenSettings", "not available on this platform"),
            ),
        ),
        85 to mapOf(
            "presentation-widget/src/test/java/tachiyomi/presentation/widget/WidgetPrivacyProductionWiringTest.kt" to mapOf(
                "base widget default constructor observes injected security preferences" to setOf("BaseUpdatesGridGlanceWidget", "WidgetPrivacyConsumer"),
                "locked production data source never queries updates" to setOf("WidgetPrivacyDataSource", "verify(exactly = 0)"),
                "base and manager consumers replace content when lock state changes" to setOf("WidgetPrivacyConsumer", "WidgetManager"),
            ),
            "app-desktop/src/test/kotlin/mihon/desktop/updates/UpdatesScreenModelTest.kt" to mapOf(
                "loadUpdates applies downloaded filter without losing raw items" to setOf("model.loadUpdates", "model.state.value.items"),
            ),
            "app-desktop/src/test/kotlin/mihon/desktop/parity/WidgetPrivacyBoundaryTest.kt" to mapOf(
                "desktop exposes shared updates but no system widget provider" to setOf("DesktopPrivacyCapabilities.production", "systemWidgetProvider", "sharedUpdatesData"),
            ),
        ),
        86 to mapOf(
            "app-desktop/src/test/kotlin/mihon/desktop/update/DesktopUpdateDownloaderTest.kt" to mapOf(
                "missing or invalid checksum is manual only without a request or verified file" to setOf("ReleaseChecksum", "ManualOnly"),
                "known and streaming size limits fail and clean partial files" to setOf("DownloadFailure.TOO_LARGE", "assertEmpty"),
                "redirect limit and scheme changes are rejected and cleaned" to setOf("DownloadFailure.REDIRECT_LIMIT", "DownloadFailure.CROSS_SCHEME_REDIRECT"),
            ),
            "app-desktop/src/test/kotlin/mihon/desktop/update/DesktopUpdateInstallerTest.kt" to mapOf(
                "Windows requires valid signature and exact configured publisher" to setOf("InstallFailure.SIGNATURE_INVALID", "InstallFailure.PUBLISHER_MISMATCH"),
                "macOS requires exact team requirement and notarization policy" to setOf("InstallFailure.SIGNATURE_INVALID", "InstallFailure.NOTARIZATION_FAILED"),
            ),
            "app-desktop/src/test/kotlin/mihon/desktop/update/DesktopUpdateProcessRunnerTest.kt" to mapOf(
                "cancellation preserves cause and bounds process plus reader lifetime" to setOf("DesktopUpdateProcessRunner", "forcedTerminationCount"),
            ),
            "app-desktop/src/test/kotlin/mihon/desktop/update/DesktopUpdateControllerTest.kt" to mapOf(
                "successful flow publishes progress and invokes every delegate in order" to setOf("DesktopUpdateController", "DesktopUpdateState.HandedOff"),
            ),
            "app-desktop/src/test/kotlin/mihon/desktop/DesktopAppRuntimeTest.kt" to mapOf(
                "runtime close permanently owns an active updater job" to setOf("DesktopAppRuntime", "DesktopUpdateController", "runtime.close"),
            ),
            "app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt" to mapOf(
                "desktop DI shares the production updater controller with UI" to setOf("DesktopUpdateController", "DesktopUpdateScreenModel", "DesktopUiDependencies.fromInjekt"),
            ),
            "app-desktop/src/test/kotlin/mihon/desktop/ui/settings/AboutUpdateWiringTest.kt" to mapOf(
                "about renders full version and routes ready confirmation intents" to setOf("AboutUpdateSection", "DesktopUpdateIntent.CONFIRM"),
            ),
        ),
        92 to mapOf(
            "domain/src/commonTest/kotlin/mihon/domain/security/AppSecurityPolicyTest.kt" to mapOf(
                "enabled app first process starts locked" to setOf("AppLockPolicy.initial"),
                "secure screen matrix only protects always and incognito modes" to setOf("SecureScreenPolicy.isProtected"),
            ),
            "app/src/test/java/eu/kanade/tachiyomi/ui/security/AndroidSecuritySharedPolicyTest.kt" to mapOf(
                "first process activity requires unlock when app lock is enabled" to setOf("AndroidAppLockLifecycleConsumer"),
                "secure screen adapter covers always incognito and never" to setOf("AndroidSecureScreenConsumer"),
            ),
            "app-desktop/src/test/kotlin/mihon/desktop/privacy/DesktopPrivacyCapabilitiesTest.kt" to mapOf(
                "production capabilities report only real desktop integrations" to
                    setOf("DesktopPrivacyCapabilities.production", "DesktopCapabilitySupport.Unsupported"),
            ),
            "app-desktop/src/test/kotlin/mihon/desktop/ui/settings/SecuritySettingsWiringTest.kt" to mapOf(
                "locked root never constructs protected content and only successful unlock restores it" to setOf("DesktopAppLock", "DesktopProtectedRoot"),
                "unsupported desktop privacy integrations show info without rendering controls" to setOf("DesktopPrivacyCapabilities.production", "nativeNotificationControls", "telemetryControls"),
            ),
            "app-desktop/src/test/kotlin/mihon/desktop/privacy/WindowPrivacyWiringTest.kt" to mapOf(
                "main production composable seam follows policy and clears before detach" to setOf("BindDesktopWindowLifecycle", "DesktopWindowPrivacyController"),
            ),
        ),
    )
    private val exactPlatformCapabilityProtectionPaths =
        exactPlatformCapabilityProtection.mapValues { it.value.keys }
    private val exactSettingsStatuses = mapOf(88 to "VERIFIED", 90 to "VERIFIED", 91 to "VERIFIED", 94 to "VERIFIED")
    private val exactSettingsUpstream =
        mapOf(
            88 to setOf(
                "presentation-core/src/main/java/tachiyomi/presentation/core/util/Modifier.kt" to "Modifier.runOnEnterKeyPressed",
                "presentation-core/src/main/java/tachiyomi/presentation/core/components/LabeledCheckbox.kt" to "LabeledCheckbox role Checkbox minimum 48dp",
                "presentation-core/src/main/java/tachiyomi/presentation/core/components/material/Surface.kt" to "Surface minimumInteractiveComponentSize role Button",
            ),
            90 to setOf(
                "app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSearchScreen.kt" to "SettingsSearchScreen SearchResult getIndex breadcrumb highlight replace",
                "app/src/main/java/eu/kanade/presentation/more/settings/screen/SearchableSettings.kt" to "SearchableSettings.highlightKey",
                "app/src/main/java/eu/kanade/presentation/more/settings/PreferenceScreen.kt" to "PreferenceScreen.findHighlightedIndex animateScrollToItem",
            ),
            91 to setOf(
                "core/common/src/main/kotlin/tachiyomi/core/common/preference/PreferenceStore.kt" to "PreferenceStore.getEnum",
                "app/src/main/java/eu/kanade/domain/ui/UiPreferences.kt" to "UiPreferences themeMode appTheme themeDarkAmoled",
                "app/src/main/java/eu/kanade/domain/ui/model/AppTheme.kt" to "AppTheme canonical and deprecated themes",
                "app/src/main/java/eu/kanade/presentation/more/settings/widget/AppThemePreferenceWidget.kt" to "AppThemePreferenceWidget selectable themes",
                "app/src/main/java/eu/kanade/presentation/theme/TachiyomiTheme.kt" to "TachiyomiTheme.getThemeColorScheme",
                "app/src/main/java/eu/kanade/presentation/theme/colorscheme/BaseColorScheme.kt" to "BaseColorScheme light dark amoled",
                "app/src/main/java/eu/kanade/presentation/more/settings/screen/appearance/AppLanguageScreen.kt" to "AppLanguageScreen application locales",
            ),
            94 to setOf(
                "app/build.gradle.kts" to "aboutLibraries Gradle plugin and compose dependency",
                "app/src/main/java/eu/kanade/presentation/more/settings/screen/about/AboutScreen.kt" to "AboutScreen.getVersionName and licenses navigation",
                "app/src/main/java/eu/kanade/presentation/more/settings/screen/about/OpenSourceLicensesScreen.kt" to "OpenSourceLicensesScreen produceLibraries first license",
                "app/src/main/java/eu/kanade/presentation/more/settings/screen/about/OpenSourceLibraryLicenseScreen.kt" to "OpenSourceLibraryLicenseScreen website and HTML license",
            ),
        )
    private val exactSettingsShared = mapOf(
        88 to emptySet(),
        90 to setOf("domain/src/commonMain/kotlin/mihon/domain/settings/SettingsSearchPolicy.kt", "domain/src/commonMain/kotlin/mihon/domain/settings/SearchablePreference.kt"),
        91 to setOf("presentation-theme/src/commonMain/kotlin/eu/kanade/domain/ui/model/AppTheme.kt", "presentation-theme/src/commonMain/kotlin/eu/kanade/domain/ui/model/ThemeMode.kt", "presentation-theme/src/commonMain/kotlin/eu/kanade/presentation/theme/colorscheme/AppThemeColorScheme.kt", "presentation-theme/src/commonMain/kotlin/eu/kanade/presentation/theme/colorscheme/BaseColorScheme.kt"),
        94 to setOf("domain/src/commonMain/kotlin/mihon/domain/license/model/DependencyNotice.kt", "domain/src/commonMain/kotlin/mihon/domain/license/service/LicenseNoticePolicy.kt"),
    )
    private val exactSettingsAndroid = mapOf(
        88 to setOf("presentation-core/src/main/java/tachiyomi/presentation/core/util/Modifier.kt", "presentation-core/src/main/java/tachiyomi/presentation/core/components/LabeledCheckbox.kt", "presentation-core/src/main/java/tachiyomi/presentation/core/components/material/Surface.kt"),
        90 to setOf("app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSearchScreen.kt", "app/src/main/java/eu/kanade/presentation/more/settings/screen/SearchableSettings.kt", "app/src/main/java/eu/kanade/presentation/more/settings/PreferenceScreen.kt"),
        91 to setOf("app/src/main/java/eu/kanade/domain/ui/UiPreferences.kt", "app/src/main/java/eu/kanade/presentation/more/settings/widget/AppThemePreferenceWidget.kt", "app/src/main/java/eu/kanade/presentation/theme/TachiyomiTheme.kt"),
        94 to setOf("app/src/main/java/eu/kanade/presentation/more/settings/screen/about/AboutScreen.kt", "app/src/main/java/eu/kanade/presentation/more/settings/screen/about/OpenSourceLicensesScreen.kt", "app/src/main/java/eu/kanade/presentation/more/settings/screen/about/OpenSourceLibraryLicenseScreen.kt"),
    )
    private val exactSettingsDesktop = mapOf(
        88 to setOf("app-desktop/src/main/kotlin/mihon/desktop/ui/settings/SettingsComposables.kt", "app-desktop/src/main/kotlin/mihon/desktop/ui/settings/GeneralSettingsScreen.kt", "app-desktop/src/main/kotlin/mihon/desktop/ui/settings/AppearanceSettingsScreen.kt"),
        90 to setOf("app-desktop/src/main/kotlin/mihon/desktop/ui/settings/DesktopSettingsCatalog.kt", "app-desktop/src/main/kotlin/mihon/desktop/ui/settings/SettingsSearchScreen.kt", "app-desktop/src/main/kotlin/mihon/desktop/ui/settings/DesktopSettingsAnchor.kt", "app-desktop/src/main/kotlin/mihon/desktop/ui/settings/MoreRootScreen.kt"),
        91 to setOf("app-desktop/src/main/kotlin/mihon/desktop/settings/DesktopAppPreferences.kt", "app-desktop/src/main/kotlin/mihon/desktop/ui/theme/DesktopTheme.kt", "app-desktop/src/main/kotlin/mihon/desktop/ui/settings/AppearanceSettingsScreen.kt"),
        94 to setOf("app-desktop/src/main/kotlin/mihon/desktop/license/DesktopDependencyNoticeProvider.kt", "app-desktop/src/main/kotlin/mihon/desktop/ui/settings/AboutScreen.kt", "app-desktop/src/main/kotlin/mihon/desktop/ui/settings/LicenseListScreen.kt", "app-desktop/src/main/kotlin/mihon/desktop/ui/settings/LicenseDetailScreen.kt", "app-desktop/src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt"),
    )
    private val exactSettingsBehavior = mapOf(
        88 to mapOf(
            "app-desktop/src/test/kotlin/mihon/desktop/ui/settings/DesktopSettingsContentAccessibilityTest.kt" to setOf("Backup production button activates once on key down and respects disabled state", "Library checkbox production row activates once on key down"),
            "app-desktop/src/test/kotlin/mihon/desktop/ui/settings/DesktopSettingsAccessibilityContractTest.kt" to setOf("General and Appearance rows expose one action role state and disabled semantics"),
        ),
        90 to mapOf(
            "domain/src/commonTest/kotlin/mihon/domain/settings/SettingsSearchPolicyTest.kt" to setOf("fixed main excludes disabled blank info and disabled or blank groups"),
            "app/src/test/java/eu/kanade/presentation/more/settings/screen/SettingsSearchConsumerBehaviorTest.kt" to setOf("real Android preference projection is searched by shared policy"),
            "app-desktop/src/test/kotlin/mihon/desktop/ui/settings/DesktopSettingsSearchWiringTest.kt" to setOf("catalog delegates search to shared policy", "search has feedback focus submission keys and result navigation", "More search entry opens the production search screen"),
        ),
        91 to mapOf(
            "presentation-theme/src/commonTest/kotlin/eu/kanade/presentation/theme/colorscheme/AppThemeColorSchemeTest.kt" to setOf("every static app theme selects its fixed main palette", "amoled only changes dark static colors and preserves fixed containers"),
            "app/src/test/java/eu/kanade/presentation/theme/AndroidSharedPaletteWiringTest.kt" to setOf("android theme consumer delegates static selection to shared selector"),
            "app-desktop/src/test/kotlin/mihon/desktop/ui/settings/DesktopSettingsSearchWiringTest.kt" to setOf("desktop theme consumes shared static theme and amoled preferences", "appearance selects static theme and amoled while preserving grid"),
        ),
        94 to mapOf(
            "domain/src/commonTest/kotlin/mihon/domain/license/service/LicenseNoticePolicyTest.kt" to setOf("first license is selected without later entries overwriting it"),
            "app/src/test/java/eu/kanade/presentation/more/settings/screen/about/OpenSourceLicensesConsumerBehaviorTest.kt" to setOf("real Android license candidates are delegated to shared policy in order", "blank first Android license does not fall through to later content"),
            "app-desktop/src/test/kotlin/mihon/desktop/license/DesktopDependencyNoticeProviderTest.kt" to setOf("provider maps metadata through the notice policy and caches the result"),
            "app-desktop/src/test/kotlin/mihon/desktop/ui/settings/AboutUpdateWiringTest.kt" to setOf("about routes real injected dependency notices to their first license content", "about renders full version and routes ready confirmation intents"),
        ),
    )
    private val exactSettingsOwners = exactSettingsUpstream.flatMap { (id, paths) -> paths.map { it.first to id } }.toMap()
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
            88 to exactSettingsUpstream.getValue(88).map { it.first }.toSet(),
            90 to exactSettingsUpstream.getValue(90).map { it.first }.toSet(),
            91 to exactSettingsUpstream.getValue(91).map { it.first }.toSet(),
            94 to exactSettingsUpstream.getValue(94).map { it.first }.toSet(),
        )
    private val exactAuthorityBlobIds =
        mapOf(
            "app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt" to "e80e5c947829ebdba73bfbc263c4e8f16196056a",
            "app/src/main/java/eu/kanade/tachiyomi/util/system/IntentExtensions.kt" to "65acbed18fc489e5cb088bf119ac04ce22ecb902",
            "app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSecurityScreen.kt" to "69a5993263fb36aead01ce75d2cff8160fba8fce",
            "presentation-core/src/main/java/tachiyomi/presentation/core/util/Modifier.kt" to "857674a5c94065a2065f2140418fe9463f8d7a12",
            "presentation-core/src/main/java/tachiyomi/presentation/core/components/LabeledCheckbox.kt" to "a66bf0d184acbac6f80bfc4fcce0a33a60f62aac",
            "presentation-core/src/main/java/tachiyomi/presentation/core/components/material/Surface.kt" to "0e857ef75c4c420f69af3c9b21ea0c61bcb56aa1",
            "app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSearchScreen.kt" to "b5a9ac937af81a5eb1feb13ca7963bec64cc72bc",
            "app/src/main/java/eu/kanade/presentation/more/settings/screen/SearchableSettings.kt" to "5652ace76c752bce59d4ee82f436c63f8d436d58",
            "app/src/main/java/eu/kanade/presentation/more/settings/PreferenceScreen.kt" to "e0938738fb6191234a283c7ffb82d52ffccad26d",
            "app/src/main/java/eu/kanade/domain/ui/UiPreferences.kt" to "84e405dee827b4d9fd47f2ca6cba7938c31b5cb4",
            "app/src/main/java/eu/kanade/domain/ui/model/AppTheme.kt" to "2394c5a429031312d4192989dc5bc3920133acc7",
            "app/src/main/java/eu/kanade/presentation/more/settings/widget/AppThemePreferenceWidget.kt" to "5e3f76efe6e106e108eb7bf1598d78a52a41c07b",
            "app/src/main/java/eu/kanade/presentation/theme/TachiyomiTheme.kt" to "71ee3d988c388d35676de91607dd43225e4aeffe",
            "app/src/main/java/eu/kanade/presentation/theme/colorscheme/BaseColorScheme.kt" to "4ad2bfb807563c6d10c46b51bc02fa72d2fe4005",
            "app/src/main/java/eu/kanade/presentation/more/settings/screen/appearance/AppLanguageScreen.kt" to "b59b26acaccce2612ce34904c704a4930ec99dc3",
            "app/build.gradle.kts" to "cdaa6f9604cd9bdada2c1e0359aba2e60ac156a1",
            "app/src/main/java/eu/kanade/presentation/more/settings/screen/about/AboutScreen.kt" to "01e35c1ecccc8b86d41d24137fd6ec0b94dfb063",
            "app/src/main/java/eu/kanade/presentation/more/settings/screen/about/OpenSourceLicensesScreen.kt" to "3385f7430b005c021f9c8469964755557a7da7a4",
            "app/src/main/java/eu/kanade/presentation/more/settings/screen/about/OpenSourceLibraryLicenseScreen.kt" to "725ed640788b217d6d71a731d798d7b5f1c25f01",
            "presentation-widget/src/main/java/tachiyomi/presentation/widget/BaseUpdatesGridGlanceWidget.kt" to "17b8d41fc85bd891fae6fd09db9c44fd05211957",
            "app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt" to "c6781277e453d4c5a6e15e7d62d3e0f721c97bd6",
            "app/src/main/java/eu/kanade/presentation/more/NewUpdateScreen.kt" to "f6b5f4b0b52a5af959866cc63c80ca2bd5af6399",
            "domain/src/main/java/tachiyomi/domain/release/interactor/GetApplicationRelease.kt" to "b95173688eded70cc10803c33d553c5af018cc3f",
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
            8 to
                setOf(
                    "app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/network/AndroidNetworkResponseAdapter.kt",
                    "app/src/main/java/eu/kanade/tachiyomi/extension/api/ExtensionApi.kt",
                ),
        )
    private val exactBatchOneDesktopPaths =
        mapOf(
            3 to setOf("app-desktop/src/main/kotlin/mihon/desktop/history/HistoryScreenModel.kt"),
            4 to setOf("app-desktop/src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt", "app-desktop/src/main/kotlin/mihon/desktop/Main.kt"),
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
            3 to setOf("MIGRATION_OUTPUT"),
            4 to setOf("PLATFORM_ADAPTER"),
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
                    "production provider configuration" to "PRODUCT_GAP",
                    "bind-existing/new-entry" to "PRODUCT_GAP",
                    "refresh-before-update" to "PRODUCT_GAP",
                    "reading status/date" to "PRODUCT_GAP",
                    "MAL error" to "PRODUCT_GAP",
                    "search model" to "PRODUCT_GAP",
                    "private/date/delete" to "PRODUCT_GAP",
                    "enhanced auto-match" to "PRODUCT_GAP",
                    "Suwayomi delete" to "PRODUCT_GAP",
                    "provider-specific fixed-main status replay" to "MIGRATION_OUTPUT",
                    "provider error classification/retry" to "PRODUCT_GAP",
                    "Komga DNS" to "PRODUCT_GAP",
                    "Kitsu/MangaUpdates request shape" to "PRODUCT_GAP",
                ),
            70 to
                mapOf(
                    "refresh-before-update" to "PRODUCT_GAP",
                    "login/progress filtering" to "PRODUCT_GAP",
                    "parallel provider updates" to "PRODUCT_GAP",
                    "highest progress" to "PRODUCT_GAP",
                    "network constraint" to "PRODUCT_GAP",
                    "unique work" to "PRODUCT_GAP",
                    "exponential backoff" to "PRODUCT_GAP",
                    "bounded retry" to "PRODUCT_GAP",
                    "queue cleanup" to "PRODUCT_GAP",
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
                    "app/src/test/java/eu/kanade/tachiyomi/extension/AndroidExtensionInstallSecurityRollbackTest.kt",
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
                            "pager holder production observer executes loading error and loaded states" to
                                setOf("observePagerTransitionState"),
                            "webtoon holder production observer executes loading error and loaded states" to
                                setOf("observeWebtoonTransitionState"),
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
                            "mounted reader viewport color layer renders disabled grayscale and invert pixels" to
                                setOf("renderColorLayer(", "assertPixel("),
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
                        setOf("observePagerTransitionState(scope, chapter)", "chapter.sharedStateFlow.collectLatest"),
                    "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonTransitionHolder.kt" to
                        setOf("observeWebtoonTransitionState(scope, chapter)", "chapter.sharedStateFlow.collectLatest"),
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
                        setOf("val matrix = readerColorMatrix(colorFilter) ?: return this", "internal fun readerColorMatrix"),
                    "app-desktop/src/main/kotlin/mihon/desktop/ui/reader/DesktopReaderScreen.kt" to
                        setOf("ReaderViewportColorLayer(state.colorFilter)", "readerColorTransform(colorFilter)"),
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
            4 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"),
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
            92 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"),
            93 to setOf("SHARE-EXTRACT", "PLATFORM-ADAPTER"),
            94 to setOf("SHARE-DIRECT", "PLATFORM-ADAPTER"), 95 to setOf("SHARE-EXTRACT"),
            96 to setOf("PLATFORM-ADAPTER", "TEMP-COMPAT"),
        )

    @Test
    fun `parity manifest defines the exact roadmap contract`() {
        val repositoryRoot = repositoryRoot()
        val items = manifestItems(repositoryRoot)
        val fixedMainPathInventory = fixedMainPathInventory(repositoryRoot)
        platformCapabilityFixedMainPaths.forEach { path ->
            assertEquals(
                exactAuthorityBlobIds.getValue(path),
                fixedMainPathInventory[path],
                "Platform capability fixed-main inventory has the wrong or missing blob for $path",
            )
        }

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
            if (item.getValue("status").jsonPrimitive.content in terminalStatuses) {
                validateRoleEvidence(item, repositoryRoot, fixedMainPathInventory)
            }
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
            } else if (item.getValue("status").jsonPrimitive.content == "NOT_STARTED" && item["roleEvidence"] == null) {
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

    @Tag("final-parity-audit")
    @Test
    fun `final parity audit requires every roadmap capability to be terminal`() {
        val repositoryRoot = repositoryRoot()
        val items = manifestItems(repositoryRoot).map { it.jsonObject }
        val fixedMainPathInventory = fixedMainPathInventory(repositoryRoot)
        assertEquals(64, items.size, "Final parity audit requires exactly 64 capabilities")
        val ids = items.map(::validatedId)
        assertEquals(ids.size, ids.toSet().size, "Final parity audit capability IDs must be unique")
        assertEquals(expectedIds, ids.toSet(), "Final parity audit capability IDs must match the roadmap")

        items.filter { it.getValue("status").jsonPrimitive.content in terminalStatuses }.forEach { item ->
            val id = validatedId(item)
            validateItem(item, repositoryRoot)
            validateRoleEvidence(item, repositoryRoot, fixedMainPathInventory)
            val protectionTests = item.getValue("protectionTests").jsonArray.map { it.jsonPrimitive.content }
            assertTrue(protectionTests.isNotEmpty(), "ID $id: terminal capability requires protection tests")
            protectionTests.forEach { path ->
                assertFalse(path.endsWith("DesktopProductCapabilityContractTest.kt"), "ID $id: final audit cannot protect itself")
                assertTrue(Files.isRegularFile(repositoryRoot.resolve(path)), "ID $id: missing terminal protection test $path")
            }
        }

        val nonTerminalIds =
            items
                .filter { it.getValue("status").jsonPrimitive.content !in terminalStatuses }
                .map(::validatedId)
                .sorted()
        println("FINAL_PARITY_AUDIT_NON_TERMINAL_IDS=${nonTerminalIds.joinToString(",")}")
        assertTrue(
            nonTerminalIds.isEmpty(),
            "Final parity audit has ${nonTerminalIds.size} non-terminal IDs: ${nonTerminalIds.joinToString(",")}",
        )
    }

    @Tag("parity-governance")
    @Test
    fun `task 2 provenance batch resolves fixed and current role evidence`() {
        val repositoryRoot = repositoryRoot()
        val inventory = fixedMainPathInventory(repositoryRoot)
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }
        task2ProvenanceStatuses.forEach { (id, expectedStatus) ->
            val item = items.getValue(id).jsonObject
            val expectedCurrentStatus = task7Statuses[id] ?: expectedStatus
            assertEquals(expectedCurrentStatus, requiredText(item, "status", id), "Later audited status must supersede Task 2")
            assertEquals(fixedOriginalMihonRef, requiredText(item, "upstreamRef", id))
            assertEquals(":app-desktop:task2ParityVerification", requiredText(item, "behaviorVerificationTask", id))
            val behaviorMethods =
                item.getValue("behaviorMethods").jsonObject.mapValues { (_, methods) ->
                    methods.jsonArray.map { it.jsonPrimitive.content }.toSet()
                }
            val expectedBehaviorMethods = task7BehaviorMethods[id] ?: task2BehaviorMethods.getValue(id)
            assertEquals(expectedBehaviorMethods, behaviorMethods, "ID $id behavior methods")
            task2BehaviorMethods.getValue(id).forEach { (path, methods) ->
                val source = Files.readString(repositoryRoot.resolve(path))
                methods.forEach { method ->
                    assertTrue(
                        kotlinTestMethod(source, method, "ID $id behavior method $path#$method").contains("assert"),
                        "ID $id behavior method must execute assertions: $path#$method",
                    )
                }
            }
            validateRoleEvidence(item, repositoryRoot, inventory)
            val upstream = item.getValue("upstreamSymbols").jsonArray.map { it.jsonObject }
            val fixedEntries = item.getValue("roleEvidence").jsonObject.getValue("FIXED_ORIGINAL").jsonArray.map { it.jsonObject }
            fixedEntries.forEach { fixed ->
                assertTrue(
                    upstream.any {
                        it.getValue("path").jsonPrimitive.content == fixed.getValue("path").jsonPrimitive.content &&
                            it.getValue("symbol").jsonPrimitive.content == fixed.getValue("symbol").jsonPrimitive.content
                    },
                    "ID $id: fixed role path/symbol must match upstreamSymbols",
                )
            }
        }
    }

    @Tag("parity-governance")
    @Test
    fun `task 3 provenance batch resolves fixed and current role evidence`() {
        val repositoryRoot = repositoryRoot()
        val inventory = fixedMainPathInventory(repositoryRoot)
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }
        task3ProvenanceStatuses.forEach { (id, expectedStatus) ->
            val item = items.getValue(id).jsonObject
            assertEquals(expectedStatus, requiredText(item, "status", id), "Task 3 must not change capability status")
            assertEquals(fixedOriginalMihonRef, requiredText(item, "upstreamRef", id))
            assertEquals(":app-desktop:task3ParityVerification", requiredText(item, "behaviorVerificationTask", id))
            val behaviorMethods =
                item.getValue("behaviorMethods").jsonObject.mapValues { (_, methods) ->
                    methods.jsonArray.map { it.jsonPrimitive.content }.toSet()
                }
            assertEquals(task3BehaviorMethods.getValue(id), behaviorMethods, "ID $id behavior methods")
            behaviorMethods.forEach { (path, methods) ->
                val source = Files.readString(repositoryRoot.resolve(path))
                methods.forEach { method ->
                    assertTrue(
                        kotlinTestMethod(source, method, "ID $id behavior method $path#$method").contains("assert"),
                        "ID $id behavior method must execute assertions: $path#$method",
                    )
                }
            }
            validateRoleEvidence(item, repositoryRoot, inventory)
            val upstream = item.getValue("upstreamSymbols").jsonArray.map { it.jsonObject }
            val fixedEntries = item.getValue("roleEvidence").jsonObject.getValue("FIXED_ORIGINAL").jsonArray.map { it.jsonObject }
            fixedEntries.forEach { fixed ->
                assertTrue(
                    upstream.any {
                        it.getValue("path").jsonPrimitive.content == fixed.getValue("path").jsonPrimitive.content &&
                            it.getValue("symbol").jsonPrimitive.content == fixed.getValue("symbol").jsonPrimitive.content
                    },
                    "ID $id: fixed role path/symbol must match upstreamSymbols",
                )
            }
        }
    }

    @Tag("parity-governance")
    @Test
    fun `task 4 provenance batch resolves fixed and current role evidence`() {
        val repositoryRoot = repositoryRoot()
        val inventory = fixedMainPathInventory(repositoryRoot)
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }
        task4ProvenanceStatuses.forEach { (id, expectedStatus) ->
            val item = items.getValue(id).jsonObject
            assertEquals(expectedStatus, requiredText(item, "status", id), "Task 4 must not change capability status")
            assertEquals(fixedOriginalMihonRef, requiredText(item, "upstreamRef", id))
            assertEquals(":app-desktop:task4ParityVerification", requiredText(item, "behaviorVerificationTask", id))
            val behaviorMethods =
                item.getValue("behaviorMethods").jsonObject.mapValues { (_, methods) ->
                    methods.jsonArray.map { it.jsonPrimitive.content }.toSet()
                }
            assertEquals(task4BehaviorMethods.getValue(id), behaviorMethods, "ID $id behavior methods")
            behaviorMethods.forEach { (path, methods) ->
                val source = Files.readString(repositoryRoot.resolve(path))
                methods.forEach { method ->
                    val methodSource = kotlinTestMethod(source, method, "ID $id behavior method $path#$method")
                    assertTrue(
                        listOf("assert", " shouldBe ", ".shouldContain").any(methodSource::contains),
                        "ID $id behavior method must execute assertions: $path#$method",
                    )
                }
            }
            validateRoleEvidence(item, repositoryRoot, inventory)
            val upstream = item.getValue("upstreamSymbols").jsonArray.map { it.jsonObject }
            val fixedEntries = item.getValue("roleEvidence").jsonObject.getValue("FIXED_ORIGINAL").jsonArray.map { it.jsonObject }
            fixedEntries.forEach { fixed ->
                assertTrue(
                    upstream.any {
                        it.getValue("path").jsonPrimitive.content == fixed.getValue("path").jsonPrimitive.content &&
                            it.getValue("symbol").jsonPrimitive.content == fixed.getValue("symbol").jsonPrimitive.content
                    },
                    "ID $id: fixed role path/symbol must match upstreamSymbols",
                )
            }
        }
    }

    @Tag("parity-governance")
    @Test
    fun `task 5 provenance batch resolves fixed current and historical role evidence`() {
        val repositoryRoot = repositoryRoot()
        val inventory = fixedMainPathInventory(repositoryRoot)
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }
        task5ProvenanceStatuses.forEach { (id, expectedStatus) ->
            val item = items.getValue(id).jsonObject
            val auditedStatus = task13Statuses[id] ?: expectedStatus
            assertEquals(auditedStatus, requiredText(item, "status", id), "ID $id must retain its latest audited status")
            assertEquals(fixedOriginalMihonRef, requiredText(item, "upstreamRef", id))
            assertEquals(":app-desktop:task5ParityVerification", requiredText(item, "behaviorVerificationTask", id))
            val behaviorMethods =
                item.getValue("behaviorMethods").jsonObject.mapValues { (_, methods) ->
                    methods.jsonArray.map { it.jsonPrimitive.content }.toSet()
                }
            val auditedBehaviorMethods =
                if (id == 96) {
                    task13BehaviorMethods.getValue(id) +
                        mapOf(
                            "app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt" to
                                setOf("desktop DI binds the started Android compat Application exact type"),
                        )
                } else {
                    task13BehaviorMethods[id] ?: task5BehaviorMethods.getValue(id)
                }
            assertEquals(auditedBehaviorMethods, behaviorMethods, "ID $id behavior methods")
            behaviorMethods.forEach { (path, methods) ->
                val source = Files.readString(repositoryRoot.resolve(path))
                methods.forEach { method ->
                    val methodSource = kotlinTestMethod(source, method, "ID $id behavior method $path#$method")
                    assertTrue(
                        listOf("assert", " shouldBe ", ".shouldContain").any(methodSource::contains),
                        "ID $id behavior method must execute assertions: $path#$method",
                    )
                }
            }
            validateRoleEvidence(item, repositoryRoot, inventory)
            val upstream = item.getValue("upstreamSymbols").jsonArray.map { it.jsonObject }
            val fixedEntries = item.getValue("roleEvidence").jsonObject.getValue("FIXED_ORIGINAL").jsonArray.map { it.jsonObject }
            fixedEntries.forEach { fixed ->
                assertTrue(
                    upstream.any {
                        it.getValue("path").jsonPrimitive.content == fixed.getValue("path").jsonPrimitive.content &&
                            it.getValue("symbol").jsonPrimitive.content == fixed.getValue("symbol").jsonPrimitive.content
                    },
                    "ID $id: fixed role path/symbol must match upstreamSymbols",
                )
            }
        }

        val tracker = Files.readString(repositoryRoot.resolve("docs/desktop-parity/PARITY_TRACKER.md"))
        assertFalse("## 71–74 备份对齐进展" in tracker, "Tracker must not duplicate the manifest's current status table")
        assertFalse(
            Regex("""(?m)^\|\s*(?:71|72|73|74)\b""").containsMatchIn(tracker),
            "Tracker must not duplicate current manifest status rows for IDs 71–74",
        )
    }

    @Tag("parity-governance")
    @Test
    fun `task 6 status batch keeps gaps and promotes only complete production evidence`() {
        val repositoryRoot = repositoryRoot()
        val inventory = fixedMainPathInventory(repositoryRoot)
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }

        task6Statuses.forEach { (id, expectedStatus) ->
            val item = items.getValue(id).jsonObject
            assertEquals(expectedStatus, requiredText(item, "status", id), "ID $id: Task 6 status decision")
            assertEquals(fixedOriginalMihonRef, requiredText(item, "upstreamRef", id))

            val decision = statusDecisionForTask(item, id, "Task 6")
            val expectedDecision =
                when (id) {
                    9, 12 -> "PROMOTE_VERIFIED"
                    else -> "KEEP_GAP"
                }
            assertEquals("Task 6", requiredText(decision, "task", id, "statusDecision"))
            assertEquals(expectedDecision, requiredText(decision, "decision", id, "statusDecision"))
            assertEquals(task6FollowUps.getValue(id), requiredText(decision, "followUp", id, "statusDecision"))
            val gap = requiredText(decision, "gap", id, "statusDecision")
            if (id in setOf(9, 12)) {
                assertEquals("NONE", gap, "ID $id: verified capability has no remaining evidence gap")
                validateRoleEvidence(item, repositoryRoot, inventory)
            } else {
                assertTrue(gap != "NONE", "ID $id: non-terminal decision must name the remaining gap")
            }

            val behaviorMethods =
                decision.getValue("behaviorMethods").jsonObject.mapValues { (_, methods) ->
                    methods.jsonArray.map { it.jsonPrimitive.content }.toSet()
                }
            assertEquals(task6BehaviorMethods.getValue(id), behaviorMethods, "ID $id Task 6 behavior methods")
            val protectionTests = item.getValue("protectionTests").jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertTrue(behaviorMethods.keys.all(protectionTests::contains), "ID $id Task 6 behavior methods must be declared protection tests")
            behaviorMethods.forEach { (path, methods) ->
                val source = Files.readString(repositoryRoot.resolve(path))
                methods.forEach { method ->
                    val methodSource = kotlinTestMethod(source, method, "ID $id Task 6 behavior method $path#$method")
                    assertTrue(
                        listOf("assert", " shouldBe ", ".shouldContain").any(methodSource::contains),
                        "ID $id Task 6 behavior method must execute assertions: $path#$method",
                    )
                }
            }
        }

        val parentPlanPath = "docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md"
        val plan = Files.readString(repositoryRoot.resolve(parentPlanPath))
        assertTrue(
            markdownFrontmatter(plan)["active-task"] in setOf("Task 7", "Task 7A child plan", "Task 7R2 replan", "Task 8", "Task 9", "Task 9A child plan", "Task 9R replan", "Task 10", "Task 11", "Task 12", "Task 13", "Task 14", "Task 14B", "Task 14C", "Task 15", "Task 16A", "Task 16B", "Task 16C", "Task 16D", "Task 17"),
            "Completed Task 6 must advance to Task 7, its active child plan, or the next completed-batch task",
        )
        val childPlanPath = repositoryRoot.resolve("docs/superpowers/plans/2026-07-24-task-6a-desktop-crash-log-failure-boundary.md")
        assertTrue(Files.isRegularFile(childPlanPath), "Task 6A crash-log child plan must exist")
        val childPlan = Files.readString(childPlanPath)
        val childMetadata = markdownFrontmatter(childPlan)
        assertEquals(parentPlanPath, childMetadata["parent-plan"], "Task 6A parent-plan")
        assertEquals("Task 6", childMetadata["parent-task"], "Task 6A parent-task")
        assertEquals("12", childMetadata["capability-id"], "Task 6A capability-id")
        assertEquals("completed", childMetadata["status"], "Task 6A status")
        setOf("app-desktop/src/test/kotlin/mihon/desktop/CrashHandlerTest.kt", "app-desktop/src/main/kotlin/mihon/desktop/CrashHandler.kt")
            .forEach { assertTrue(childPlan.contains("Modify: `$it`"), "Task 6A missing product scope: $it") }
        assertTrue(
            Regex("""(?m)^- \[x] Task 6[：:]""").containsMatchIn(plan),
            "Task 6 must be checked after the parent completes its final status decision",
        )
    }

    @Tag("parity-governance")
    @Test
    fun `task 7 status batch promotes only complete library detail and source evidence`() {
        val repositoryRoot = repositoryRoot()
        val inventory = fixedMainPathInventory(repositoryRoot)
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }
        val rawManifest = Files.readString(repositoryRoot.resolve("app-desktop/src/test/resources/parity/parity-manifest.json"))
        assertEquals(emptyList<String>(), duplicateJsonPropertyNames(rawManifest), "Manifest JSON properties must be unique within each object")
        assertEquals(
            listOf("statusDecision"),
            duplicateJsonPropertyNames("""[{"statusDecision":{},"statusDecision":{}}]"""),
            "Copying a manifest property must be detected before last-wins parsing",
        )
        assertEquals(
            task7Statuses.keys,
            items.filterValues { it.jsonObject["statusDecision"]?.jsonObject?.get("task")?.jsonPrimitive?.content == "Task 7" }.keys,
            "Task 7 status decisions must belong only to its eight target IDs",
        )

        task7Statuses.forEach { (id, expectedStatus) ->
            val item = items.getValue(id).jsonObject
            assertEquals(expectedStatus, requiredText(item, "status", id), "ID $id: Task 7 status decision")
            assertEquals(fixedOriginalMihonRef, requiredText(item, "upstreamRef", id))

            val decision = item.getValue("statusDecision").jsonObject
            val expectedDecision =
                when (id) {
                    17, 19, 29 -> "PROMOTE_VERIFIED"
                    else -> "KEEP_GAP"
                }
            assertEquals("Task 7", requiredText(decision, "task", id, "statusDecision"))
            assertEquals(expectedDecision, requiredText(decision, "decision", id, "statusDecision"))
            assertEquals(task7FollowUps.getValue(id), requiredText(decision, "followUp", id, "statusDecision"))
            val gap = requiredText(decision, "gap", id, "statusDecision")
            if (id in setOf(17, 19, 29)) {
                assertEquals("NONE", gap, "ID $id: verified capability has no remaining evidence gap")
                validateRoleEvidence(item, repositoryRoot, inventory)
            } else {
                assertTrue(gap != "NONE", "ID $id: non-terminal decision must name the remaining gap")
            }

            val behaviorMethods =
                decision.getValue("behaviorMethods").jsonObject.mapValues { (_, methods) ->
                    methods.jsonArray.map { it.jsonPrimitive.content }.toSet()
                }
            assertEquals(task7BehaviorMethods.getValue(id), behaviorMethods, "ID $id Task 7 behavior methods")
            val protectionTests = item.getValue("protectionTests").jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertTrue(behaviorMethods.keys.all(protectionTests::contains), "ID $id Task 7 behavior methods must be declared protection tests")
            behaviorMethods.forEach { (path, methods) ->
                val source = Files.readString(repositoryRoot.resolve(path))
                methods.forEach { method ->
                    val methodSource = kotlinTestMethod(source, method, "ID $id Task 7 behavior method $path#$method")
                    assertTrue(
                        "assert" in methodSource || Regex("""\bshould[A-Z]""").containsMatchIn(methodSource),
                        "ID $id Task 7 behavior method must execute assertions: $path#$method",
                    )
                }
            }
        }

        val plan = Files.readString(repositoryRoot.resolve("docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md"))
        assertTrue(markdownFrontmatter(plan)["active-task"] in setOf("Task 8", "Task 9", "Task 9A child plan", "Task 9R replan", "Task 10", "Task 11", "Task 12", "Task 13", "Task 14", "Task 14B", "Task 14C", "Task 15", "Task 16A", "Task 16B", "Task 16C", "Task 16D", "Task 17"), "Completed Task 7 must advance to Task 8 or later")
        assertTrue(Regex("""(?m)^- \[x] Task 7[：:]""").containsMatchIn(plan), "Completed Task 7 must be checked")
        assertTrue("6fb82074adeceda25be2f3a12621ce510fd0423c" in plan, "Task 7 closeout must retain R1 evidence")
        assertTrue("af9c522ec9f5c7032ebe3503bab6f9a6a1659e6f" in plan, "Task 7 closeout must retain R2 evidence")
        assertTrue("f9cbea69a5185f2c2ee663b4a8c023a0dbc82fce" in plan, "Task 7 closeout must record R3 evidence")
        val childPlan = Files.readString(repositoryRoot.resolve(task7ChildPlan))
        val childMetadata = markdownFrontmatter(childPlan)
        assertEquals("docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md", childMetadata["parent-plan"])
        assertEquals("Task 7", childMetadata["parent-task"])
        assertEquals("19", childMetadata["capability-id"])
        assertEquals("17,19", childMetadata["related-capability-ids"])
        assertEquals("completed", childMetadata["status"])
        assertTrue(listOf("1/5/10/25", "全部未读", "书签").all(childPlan::contains), "Task 7A must preserve every fixed-main download option")
        assertTrue("跳过已排队/下载中/已下载" in childPlan, "Task 7A must preserve active-download deduplication")
        assertFalse("NavigationTypeSafetyTest" in childPlan, "Task 7A must not reference the removed navigation test name")
        assertTrue(
            listOf("DesktopBatchMigrationController.submit()", "MigrationBatchQueueScreen", "MigrationSearchScreen", "NavigationContractTest.kt").all(childPlan::contains),
            "Task 7A must reuse the existing batch migration chain and protect its navigation",
        )
        assertTrue(
            Files.isRegularFile(repositoryRoot.resolve("app-desktop/src/test/kotlin/mihon/desktop/ui/NavigationContractTest.kt")),
            "Task 7A navigation protection must resolve to a real test file",
        )
        assertTrue("仅输出 R2 hash/status 交给 R3" in childPlan, "Task 7A must leave audit closeout to R3")
        assertTrue("删除调用时失败" in childPlan, "Task 7A must add an Android production consumer mutation test for ID 17")
        val replan = Files.readString(repositoryRoot.resolve("docs/superpowers/plans/2026-07-24-task-7-status-b-replan.md"))
        assertEquals("completed", markdownFrontmatter(replan)["status"])
        assertTrue("6fb82074adeceda25be2f3a12621ce510fd0423c" in replan, "Task 7R2 must record R1")
        assertTrue("af9c522ec9f5c7032ebe3503bab6f9a6a1659e6f" in replan, "Task 7R2 must record R2")
    }

    @Tag("parity-governance")
    @Test
    fun `task 8 status batch promotes only closed source and extension evidence`() {
        val repositoryRoot = repositoryRoot()
        val inventory = fixedMainPathInventory(repositoryRoot)
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }
        val task8DecisionIds =
            items.filterValues { item ->
                statusDecisionTasks(item.jsonObject, validatedId(item.jsonObject)).contains("Task 8")
            }.keys
        assertEquals(task8Statuses.keys, task8DecisionIds, "Task 8 capability set")
        task8Statuses.forEach { (id, expectedStatus) ->
            val item = items.getValue(id).jsonObject
            assertEquals(expectedStatus, requiredText(item, "status", id), "ID $id Task 8 status")
            val decision = statusDecisionForTask(item, id, "Task 8")
            assertEquals("Task 8", requiredText(decision, "task", id, "statusDecision"))
            val verified = id in setOf(30, 33, 34, 36, 37)
            assertEquals(if (verified) "PROMOTE_VERIFIED" else "KEEP_GAP", requiredText(decision, "decision", id, "statusDecision"))
            assertEquals(task8FollowUps.getValue(id), requiredText(decision, "followUp", id, "statusDecision"))
            val gap = requiredText(decision, "gap", id, "statusDecision")
            if (verified) {
                assertEquals("NONE", gap, "ID $id verified gap")
                validateRoleEvidence(item, repositoryRoot, inventory)
            } else {
                assertTrue(gap != "NONE", "ID $id must retain a concrete gap")
            }
            val behaviorMethods =
                decision.getValue("behaviorMethods").jsonObject.mapValues { (_, methods) ->
                    methods.jsonArray.map { it.jsonPrimitive.content }.toSet()
                }
            assertEquals(task8BehaviorMethods.getValue(id), behaviorMethods, "ID $id Task 8 behavior methods")
            val protectionTests = item.getValue("protectionTests").jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertTrue(behaviorMethods.keys.all(protectionTests::contains), "ID $id Task 8 behavior methods must be protection tests")
            behaviorMethods.forEach { (path, methods) ->
                val source = Files.readString(repositoryRoot.resolve(path))
                methods.forEach { method ->
                    assertTrue("assert" in kotlinTestMethod(source, method, "ID $id Task 8 behavior $path#$method"))
                }
            }
        }

        val compat = items.getValue(35).jsonObject
        assertTrue("TEMP-COMPAT" in compat.getValue("tags").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(requiredText(statusDecisionForTask(compat, 35, "Task 8"), "gap", 35, "statusDecision").contains("fixture"))
        val plan = Files.readString(repositoryRoot.resolve("docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md"))
        assertTrue(markdownFrontmatter(plan)["active-task"] in setOf("Task 9", "Task 9A child plan", "Task 9R replan", "Task 10", "Task 11", "Task 12", "Task 13", "Task 14", "Task 14B", "Task 14C", "Task 15", "Task 16A", "Task 16B", "Task 16C", "Task 16D", "Task 17"), "Completed Task 8 must advance to Task 9 or later")
        assertTrue(Regex("""(?m)^- \[x] Task 8[：:]""").containsMatchIn(plan), "Completed Task 8 must be checked")
    }

    @Tag("parity-governance")
    @Test
    fun `task 9 status batch promotes only closed login challenge and reader evidence`() {
        val repositoryRoot = repositoryRoot()
        val inventory = fixedMainPathInventory(repositoryRoot)
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }
        val task9DecisionIds =
            items.filterValues { item ->
                statusDecisionTasks(item.jsonObject, validatedId(item.jsonObject)).contains("Task 9")
            }.keys
        assertEquals(task9Statuses.keys, task9DecisionIds, "Task 9 capability set")
        task9Statuses.forEach { (id, expectedStatus) ->
            val item = items.getValue(id).jsonObject
            assertEquals(expectedStatus, requiredText(item, "status", id), "ID $id Task 9 status")
            val decision = statusDecisionForTask(item, id, "Task 9")
            assertEquals("Task 9", requiredText(decision, "task", id, "statusDecision"))
            val verified = id != 39
            assertEquals(if (verified) "PROMOTE_VERIFIED" else "KEEP_GAP", requiredText(decision, "decision", id, "statusDecision"))
            assertEquals(task9FollowUps.getValue(id), requiredText(decision, "followUp", id, "statusDecision"))
            val gap = requiredText(decision, "gap", id, "statusDecision")
            if (verified) {
                assertEquals("NONE", gap, "ID $id verified gap")
                validateRoleEvidence(item, repositoryRoot, inventory)
            } else {
                assertTrue(gap != "NONE", "ID $id must retain a concrete gap")
            }
            val behaviorMethods =
                decision.getValue("behaviorMethods").jsonObject.mapValues { (_, methods) ->
                    methods.jsonArray.map { it.jsonPrimitive.content }.toSet()
                }
            assertEquals(task9BehaviorMethods.getValue(id), behaviorMethods, "ID $id Task 9 behavior methods")
            val protectionTests = item.getValue("protectionTests").jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertTrue(behaviorMethods.keys.all(protectionTests::contains), "ID $id Task 9 behavior methods must be protection tests")
            behaviorMethods.forEach { (path, methods) ->
                val source = Files.readString(repositoryRoot.resolve(path))
                methods.forEach { method ->
                    assertTrue("assert" in kotlinTestMethod(source, method, "ID $id Task 9 behavior $path#$method"))
                }
            }
        }

        val login = items.getValue(39).jsonObject
        val loginClassifications =
            login.getValue("deviations").jsonArray
                .map { requiredText(it.jsonObject, "classification", 39, "deviations") }
        assertTrue("UNCLASSIFIED_DEBT" !in loginClassifications, "ID 39 Task 14A must classify the external-browser boundary")
        assertEquals("adapter", requiredText(login.getValue("unclassifiedDebtResolution").jsonObject, "decision", 39))
        val colorScanMethod = "reader color matrix production chain delegates through the tested helper"
        val colorTopLevelMethods = items.getValue(51).jsonObject.getValue("behaviorMethods").jsonObject.values.flatMap { it.jsonArray }.map { it.jsonPrimitive.content }
        assertTrue(colorScanMethod !in colorTopLevelMethods, "ID 51 top-level behaviorMethods must reject source scan evidence")
        assertTrue(task3BehaviorMethods.getValue(51).values.none { colorScanMethod in it }, "ID 51 Task 3 history must reject source scan evidence")
        assertTrue(readerBehaviorEvidence.getValue(51).values.none { colorScanMethod in it }, "ID 51 reader behavior map must reject source scan evidence")

        val dualPage = items.getValue(43).jsonObject
        val fixedPaths =
            dualPage.getValue("roleEvidence").jsonObject
                .getValue("FIXED_ORIGINAL").jsonArray
                .map { requiredText(it.jsonObject, "path", 43, "roleEvidence.FIXED_ORIGINAL") }
        assertTrue(fixedPaths.none(forkOnlyReaderPairingPaths::contains), "ID 43 fork pairing must not masquerade as fixed original")
        assertTrue(
            "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerViewerAdapter.kt" in fixedPaths,
            "ID 43 fixed role must retain original pager viewer authority",
        )
        val classifications =
            dualPage.getValue("deviations").jsonArray
                .map { requiredText(it.jsonObject, "classification", 43, "deviations") }
        assertTrue("CROSS_PLATFORM_PRODUCT_ENHANCEMENT" in classifications, "ID 43 portrait pairing must stay an explicit enhancement")

        val plan = Files.readString(repositoryRoot.resolve("docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md"))
        val activeTask = markdownFrontmatter(plan)["active-task"]
        assertTrue(activeTask in setOf("Task 10", "Task 11", "Task 12", "Task 13", "Task 14", "Task 14B", "Task 14C", "Task 15", "Task 16A", "Task 16B", "Task 16C", "Task 16D", "Task 17"), "Task 9 closeout must advance to Task 10 or later")
        assertTrue(Regex("""(?m)^- \[x] Task 9[：:]""").containsMatchIn(plan), "Completed Task 9 must be checked")
        assertTrue(
            Regex(if (activeTask == "Task 10") """(?m)^- \[ ] Task 10[：:]""" else """(?m)^- \[x] Task 10[：:]""").containsMatchIn(plan),
            "Task 10 checkbox must match whether Task 10 is active or complete",
        )
        assertTrue("84add84daad5606a20ac9793d39349b7bbb0a744" in plan, "Task 9 closeout must record the S3 commit")
        val child = Files.readString(repositoryRoot.resolve("docs/superpowers/plans/2026-07-24-task-9a-reader-wiring-protection.md"))
        assertEquals("completed", markdownFrontmatter(child)["status"])
        assertEquals("Task 9", markdownFrontmatter(child)["parent"])
        assertEquals("[47, 51]", markdownFrontmatter(child)["capability-ids"])
        assertTrue("d0311eb381a45d323bc28cd1ee4ac010e312fc2d" in child, "Task 9A must record the Stage A commit")
        assertTrue("14/14" in child && "21/21" in child && "0 skipped" in child, "Task 9A must record zero-skip executable verification")
        assertTrue("adb45f9979871ada2230f9599baefa82a5d80ec4" in child, "Task 9A must record the pager mutation")
        assertTrue("f7c2be665c461a039ecc28216c5b9373a35c3e90" in child, "Task 9A must record the webtoon mutation")
        assertTrue("28c3968724488eb283d454a02829fae4bb73f10b" in child, "Task 9A must record the Desktop mutation")
        val replan = Files.readString(repositoryRoot.resolve("docs/superpowers/plans/2026-07-24-task-9-status-d-replan.md"))
        assertEquals("completed", markdownFrontmatter(replan)["status"])
        assertEquals("Task 9", markdownFrontmatter(replan)["parent-task"])
        assertTrue("84add84daad5606a20ac9793d39349b7bbb0a744" in replan, "Task 9 replan must record the S3 commit")
    }

    @Tag("parity-governance")
    @Test
    fun `task 10 status batch promotes only closed progress download update and history evidence`() {
        val repositoryRoot = repositoryRoot()
        val inventory = fixedMainPathInventory(repositoryRoot)
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }
        val task10DecisionIds =
            items.filterValues { item ->
                item.jsonObject["statusDecision"]?.jsonObject?.get("task")?.jsonPrimitive?.content == "Task 10"
            }.keys
        assertEquals(task10Statuses.keys, task10DecisionIds, "Task 10 capability set")
        task10Statuses.forEach { (id, expectedStatus) ->
            val item = items.getValue(id).jsonObject
            assertEquals(expectedStatus, requiredText(item, "status", id), "ID $id Task 10 status")
            validateRoleEvidence(item, repositoryRoot, inventory)
            val decision = item.getValue("statusDecision").jsonObject
            assertEquals("Task 10", requiredText(decision, "task", id, "statusDecision"))
            val verified = id !in setOf(54, 56)
            assertEquals(if (verified) "PROMOTE_VERIFIED" else "KEEP_GAP", requiredText(decision, "decision", id, "statusDecision"))
            assertEquals(task10FollowUps.getValue(id), requiredText(decision, "followUp", id, "statusDecision"))
            val gap = requiredText(decision, "gap", id, "statusDecision")
            if (verified) {
                assertEquals("NONE", gap, "ID $id verified gap")
            } else {
                assertTrue(gap != "NONE", "ID $id must retain a concrete gap")
            }
            val behaviorMethods =
                decision.getValue("behaviorMethods").jsonObject.mapValues { (_, methods) ->
                    methods.jsonArray.map { it.jsonPrimitive.content }.toSet()
                }
            assertEquals(task10BehaviorMethods.getValue(id), behaviorMethods, "ID $id Task 10 behavior methods")
            val protectionTests = item.getValue("protectionTests").jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertTrue(behaviorMethods.keys.all(protectionTests::contains), "ID $id Task 10 behavior methods must be protection tests")
            behaviorMethods.forEach { (path, methods) ->
                val source = Files.readString(repositoryRoot.resolve(path))
                methods.forEach { method ->
                    kotlinTestMethod(source, method, "ID $id Task 10 behavior $path#$method")
                }
            }
        }

        val grouping = items.getValue(56).jsonObject
        val groupingDecision = grouping.getValue("statusDecision").jsonObject
        val groupingGap = requiredText(groupingDecision, "gap", 56, "statusDecision")
        assertTrue("source object" in groupingGap && "sourceId" in groupingGap, "ID 56 gap must preserve the grouping semantic difference")
        assertTrue(
            grouping.getValue("deviations").jsonArray.any {
                requiredText(it.jsonObject, "classification", 56, "deviations") == "MIGRATION_OUTPUT"
            },
            "ID 56 sourceId projection must remain an explicit migration output",
        )
        val navigationGap = requiredText(items.getValue(54).jsonObject.getValue("statusDecision").jsonObject, "gap", 54, "statusDecision")
        assertTrue(
            "source scan" in navigationGap && "production wiring" in navigationGap,
            "ID 54 gap must name the non-killable current Android wiring evidence",
        )

        val plan = Files.readString(repositoryRoot.resolve("docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md"))
        assertTrue(markdownFrontmatter(plan)["active-task"] in setOf("Task 11", "Task 12", "Task 13", "Task 14", "Task 14B", "Task 14C", "Task 15", "Task 16A", "Task 16B", "Task 16C", "Task 16D", "Task 17"), "Task 10 closeout must advance to Task 11 or later")
        assertTrue(Regex("""(?m)^- \[x] Task 10[：:]""").containsMatchIn(plan), "Completed Task 10 must be checked")
        assertTrue(Regex("""(?m)^- \[[x ]\] Task 11[：:]""").containsMatchIn(plan), "Task 11 must remain tracked")
        assertTrue("ID 56" in plan && "source object" in plan && "sourceId" in plan && "Task 14" in plan, "Task 10 must record the finite ID 56 follow-up")
    }

    @Tag("parity-governance")
    @Test
    fun `task 11 status batch promotes only closed stats migration tracking and backup evidence`() {
        val repositoryRoot = repositoryRoot()
        val inventory = fixedMainPathInventory(repositoryRoot)
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }
        val task11DecisionIds =
            items.filterValues { item ->
                statusDecisionTasks(item.jsonObject, validatedId(item.jsonObject)).contains("Task 11")
            }.keys
        assertEquals(task11Statuses.keys, task11DecisionIds, "Task 11 capability set")
        task11Statuses.forEach { (id, expectedStatus) ->
            val item = items.getValue(id).jsonObject
            assertEquals(expectedStatus, requiredText(item, "status", id), "ID $id Task 11 status")
            validateRoleEvidence(item, repositoryRoot, inventory)
            val decision = statusDecisionForTask(item, id, "Task 11")
            assertEquals("Task 11", requiredText(decision, "task", id, "statusDecision"))
            val verified = id in setOf(67, 68)
            assertEquals(if (verified) "PROMOTE_VERIFIED" else "KEEP_GAP", requiredText(decision, "decision", id, "statusDecision"))
            assertEquals(task11FollowUps.getValue(id), requiredText(decision, "followUp", id, "statusDecision"))
            val gap = requiredText(decision, "gap", id, "statusDecision")
            if (verified) {
                assertEquals("NONE", gap, "ID $id verified gap")
            } else {
                assertTrue(gap != "NONE", "ID $id must retain a concrete gap")
            }
            val behaviorMethods =
                decision.getValue("behaviorMethods").jsonObject.mapValues { (_, methods) ->
                    methods.jsonArray.map { it.jsonPrimitive.content }.toSet()
                }
            assertEquals(task11BehaviorMethods.getValue(id), behaviorMethods, "ID $id Task 11 behavior methods")
            val protectionTests = item.getValue("protectionTests").jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertTrue(behaviorMethods.keys.all(protectionTests::contains), "ID $id Task 11 behavior methods must be protection tests")
            behaviorMethods.forEach { (path, methods) ->
                val source = Files.readString(repositoryRoot.resolve(path))
                methods.forEach { method ->
                    kotlinTestMethod(source, method, "ID $id Task 11 behavior $path#$method")
                }
            }
        }

        val trackerGap = requiredText(statusDecisionForTask(items.getValue(69).jsonObject, 69, "Task 11"), "gap", 69, "statusDecision")
        listOf(
            "production provider configuration",
            "bind-existing/new-entry",
            "refresh-before-update",
            "reading status/date",
            "MAL error",
            "search model",
            "private/date/delete",
            "enhanced auto-match",
            "Suwayomi delete",
            "provider error classification/retry",
            "Komga DNS",
            "Kitsu/MangaUpdates request shape",
        ).forEach { term -> assertTrue(term in trackerGap, "ID 69 gap must preserve `$term`") }
        val triggerGap = requiredText(statusDecisionForTask(items.getValue(70).jsonObject, 70, "Task 11"), "gap", 70, "statusDecision")
        listOf(
            "refresh-before-update",
            "login/progress filtering",
            "parallel provider updates",
            "highest progress",
            "network constraint",
            "unique work",
            "exponential backoff",
            "bounded retry",
            "queue cleanup",
        ).forEach { term -> assertTrue(term in triggerGap, "ID 70 gap must preserve `$term`") }
        val statsGap = requiredText(items.getValue(66).jsonObject.getValue("statusDecision").jsonObject, "gap", 66, "statusDecision")
        assertTrue("current Android" in statsGap && "production behavior" in statsGap, "ID 66 gap must name the unprotected Android aggregation")
        val creator = items.getValue(71).jsonObject
        assertTrue(
            creator.getValue("behaviorMethods").jsonObject.values.flatMap { it.jsonArray }.none {
                it.jsonPrimitive.content == "Android fixture generator consumes fixed original Mihon ref"
            },
            "ID 71 must not treat generator source scanning as production behavior",
        )
        assertTrue(
            "fixed-original artifact fixture" in requiredText(creator.getValue("statusDecision").jsonObject, "gap", 71, "statusDecision"),
            "ID 71 gap must name the missing fixed-original artifact fixture",
        )
        val schedulerGap = requiredText(items.getValue(73).jsonObject.getValue("statusDecision").jsonObject, "gap", 73, "statusDecision")
        assertTrue("exit" in schedulerGap && "periodic scheduling" in schedulerGap, "ID 73 gap must name the process-lifetime scheduling difference")
        val restoreGap = requiredText(items.getValue(72).jsonObject.getValue("statusDecision").jsonObject, "gap", 72, "statusDecision")
        assertTrue(
            "fixed-original Android artifact" in restoreGap && "current Android BackupRestorer" in restoreGap,
            "ID 72 gap must name both missing Android restore boundaries",
        )

        val plan = Files.readString(repositoryRoot.resolve("docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md"))
        assertTrue(markdownFrontmatter(plan)["active-task"] in setOf("Task 12", "Task 13", "Task 14", "Task 14B", "Task 14C", "Task 15", "Task 16A", "Task 16B", "Task 16C", "Task 16D", "Task 17"), "Task 11 closeout must advance to Task 12 or later")
        assertTrue(Regex("""(?m)^- \[x] Task 11[：:]""").containsMatchIn(plan), "Completed Task 11 must be checked")
        assertTrue(Regex("""(?m)^- \[[x ]\] Task 12[：:]""").containsMatchIn(plan), "Task 12 must remain tracked")
        assertTrue("ID 69" in plan && "ID 70" in plan && "Task 14" in plan, "Task 11 must record the finite tracking follow-up")
    }

    @Tag("parity-governance")
    @Test
    fun `task 12 status batch promotes only closed backup and preserves platform decisions`() {
        val repositoryRoot = repositoryRoot()
        val inventory = fixedMainPathInventory(repositoryRoot)
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }
        val task12DecisionIds =
            items.filterValues { item ->
                statusDecisionTasks(item.jsonObject, validatedId(item.jsonObject)).contains("Task 12")
            }.keys
        assertEquals(task12Statuses.keys, task12DecisionIds, "Task 12 capability set")
        task12Statuses.forEach { (id, expectedStatus) ->
            val item = items.getValue(id).jsonObject
            assertEquals(expectedStatus, requiredText(item, "status", id), "ID $id Task 12 status")
            validateRoleEvidence(item, repositoryRoot, inventory)
            val decision = statusDecisionForTask(item, id, "Task 12")
            assertEquals("Task 12", requiredText(decision, "task", id, "statusDecision"))
            val expectedDecision =
                when (id) {
                    74 -> "PROMOTE_VERIFIED"
                    85 -> "KEEP_EXEMPT"
                    else -> "KEEP_GAP"
                }
            assertEquals(expectedDecision, requiredText(decision, "decision", id, "statusDecision"))
            assertEquals(task12FollowUps.getValue(id), requiredText(decision, "followUp", id, "statusDecision"))
            val gap = requiredText(decision, "gap", id, "statusDecision")
            if (id in setOf(74, 85)) {
                assertEquals("NONE", gap, "ID $id terminal gap")
            } else {
                assertTrue(gap != "NONE", "ID $id must retain a concrete gap")
            }
            val behaviorMethods =
                decision.getValue("behaviorMethods").jsonObject.mapValues { (_, methods) ->
                    methods.jsonArray.map { it.jsonPrimitive.content }.toSet()
                }
            assertEquals(task12BehaviorMethods.getValue(id), behaviorMethods, "ID $id Task 12 behavior methods")
            val protectionTests = item.getValue("protectionTests").jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertTrue(behaviorMethods.keys.all(protectionTests::contains), "ID $id Task 12 behavior methods must be protection tests")
            behaviorMethods.forEach { (path, methods) ->
                val source = Files.readString(repositoryRoot.resolve(path))
                methods.forEach { method -> kotlinTestMethod(source, method, "ID $id Task 12 behavior $path#$method") }
            }
        }

        val backup = items.getValue(74).jsonObject
        val fixtures =
            backup.getValue("roleEvidence").jsonObject.getValue("FIXTURE").jsonArray
                .map { requiredText(it.jsonObject, "artifact", 74, "roleEvidence.FIXTURE") }
        assertTrue(
            fixtures.any { it.contains("BackupCodecContractTest.kt#fixed-main Android full fixture") },
            "ID 74 terminal fixture must execute the fixed-main Android artifact",
        )
        val expectedGaps =
            mapOf(
                81 to setOf("application bundle", "cold", "running"),
                82 to setOf("Host share"),
                83 to setOf("OS credential"),
                84 to setOf("capture acceptance"),
                86 to setOf("signed release artifact", "OS installer handoff"),
                87 to setOf("app-language selector", "unlocalized copy"),
            )
        expectedGaps.forEach { (id, terms) ->
            val gap = requiredText(statusDecisionForTask(items.getValue(id).jsonObject, id, "Task 12"), "gap", id, "statusDecision")
            terms.forEach { term -> assertTrue(term in gap, "ID $id gap must preserve `$term`") }
        }
        val widget = items.getValue(85).jsonObject
        val approval = widget.getValue("exemptionApproval").jsonObject
        assertEquals(
            "docs/superpowers/specs/2026-07-12-mihon-desktop-upstream-parity-design.md:217",
            requiredText(approval, "approvalSource", 85, "exemptionApproval"),
        )
        assertEquals("2026-07-12", requiredText(approval, "approvalDate", 85, "exemptionApproval"))
        assertEquals(
            "app-desktop/src/test/kotlin/mihon/desktop/parity/WidgetPrivacyBoundaryTest.kt",
            requiredText(widget, "platformExemptionEvidence", 85),
        )

        val plan = Files.readString(repositoryRoot.resolve("docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md"))
        assertTrue(markdownFrontmatter(plan)["active-task"] in setOf("Task 13", "Task 14", "Task 14B", "Task 14C", "Task 15", "Task 16A", "Task 16B", "Task 16C", "Task 16D", "Task 17"), "Task 12 closeout must advance to Task 13 or later")
        assertTrue(Regex("""(?m)^- \[x] Task 12[：:]""").containsMatchIn(plan), "Completed Task 12 must be checked")
        assertTrue(Regex("""(?m)^- \[[x ]\] Task 13[：:]""").containsMatchIn(plan), "Task 13 must remain tracked")
        assertTrue("ID 85" in plan && "217" in plan && "Task 15" in plan, "Task 12 must preserve the approved exemption and OS follow-up")
    }

    @Tag("parity-governance")
    @Test
    fun `task 13 status batch preserves terminal settings and records finite architecture gaps`() {
        val repositoryRoot = repositoryRoot()
        val inventory = fixedMainPathInventory(repositoryRoot)
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }
        val task13DecisionIds =
            items.filterValues { item ->
                statusDecisionTasks(item.jsonObject, validatedId(item.jsonObject)).contains("Task 13")
            }.keys
        assertEquals(task13Statuses.keys, task13DecisionIds, "Task 13 capability set")
        task13Statuses.forEach { (id, expectedStatus) ->
            val item = items.getValue(id).jsonObject
            assertEquals(expectedStatus, requiredText(item, "status", id), "ID $id Task 13 status")
            validateRoleEvidence(item, repositoryRoot, inventory)
            val decision = statusDecisionForTask(item, id, "Task 13")
            assertEquals("Task 13", requiredText(decision, "task", id, "statusDecision"))
            val expectedDecision =
                when (id) {
                    90, 91, 94 -> "KEEP_VERIFIED"
                    93, 95, 96 -> "PROMOTE_WIRED"
                    else -> "KEEP_GAP"
                }
            assertEquals(expectedDecision, requiredText(decision, "decision", id, "statusDecision"))
            assertEquals(task13FollowUps.getValue(id), requiredText(decision, "followUp", id, "statusDecision"))
            val gap = requiredText(decision, "gap", id, "statusDecision")
            if (id in setOf(90, 91, 94)) {
                assertEquals("NONE", gap, "ID $id verified gap")
            } else {
                assertTrue(gap != "NONE", "ID $id must retain a concrete gap")
            }
            val behaviorMethods =
                decision.getValue("behaviorMethods").jsonObject.mapValues { (_, methods) ->
                    methods.jsonArray.map { it.jsonPrimitive.content }.toSet()
                }
            assertEquals(task13BehaviorMethods.getValue(id), behaviorMethods, "ID $id Task 13 behavior methods")
            val protectionTests = item.getValue("protectionTests").jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertTrue(behaviorMethods.keys.all(protectionTests::contains), "ID $id Task 13 behavior methods must be protection tests")
            behaviorMethods.forEach { (path, methods) ->
                val source = Files.readString(repositoryRoot.resolve(path))
                methods.forEach { method -> kotlinTestMethod(source, method, "ID $id Task 13 behavior $path#$method") }
            }
        }

        val invalidLibraryPageFixture =
            "app-desktop/src/test/kotlin/mihon/desktop/ui/library/LibraryPageCompositionTest.kt"
        val invalidLibraryPageReferences =
            buildList {
                items.forEach { (id, item) ->
                    val itemObject = item.jsonObject
                    itemObject["protectionTests"]?.jsonArray?.forEach { path ->
                        if (path.jsonPrimitive.content == invalidLibraryPageFixture) add("ID $id protectionTests")
                    }
                    itemObject["roleEvidence"]?.jsonObject?.get("FIXTURE")?.jsonArray?.forEach { fixture ->
                        if (
                            requiredText(fixture.jsonObject, "artifact", id, "roleEvidence.FIXTURE")
                                .substringBefore("#") == invalidLibraryPageFixture
                        ) {
                            add("ID $id roleEvidence.FIXTURE")
                        }
                    }
                    listOfNotNull(
                        itemObject["behaviorMethods"]?.jsonObject,
                        itemObject["statusDecision"]?.jsonObject?.get("behaviorMethods")?.jsonObject,
                    ).forEach { methods ->
                        if (invalidLibraryPageFixture in methods.keys) add("ID $id behaviorMethods")
                    }
                }
                task3aBehaviorEvidence.forEach { (id, evidence) ->
                    if (invalidLibraryPageFixture in evidence.keys) add("ID $id task3aBehaviorEvidence")
                }
            }
        assertEquals(
            emptyList<String>(),
            invalidLibraryPageReferences.sorted(),
            "LibraryPageCompositionTest has no DesktopUiDependencies provider and must not remain completion evidence",
        )

        val accessibility = items.getValue(88).jsonObject
        assertTrue(
            accessibility.getValue("roleEvidence").jsonObject.getValue("SHARED_OR_ADAPTER").jsonArray
                .map { requiredText(it.jsonObject, "kind", 88, "roleEvidence.SHARED_OR_ADAPTER") }
                .all { it == "PLATFORM_ADAPTER" },
            "ID 88 must not relabel the Desktop accessibility adapter as shared",
        )
        val expectedGaps =
            mapOf(
                88 to setOf("commonMain", "cross-platform"),
                92 to setOf("OS credential", "capture acceptance"),
                93 to setOf("shared maintenance use case", "database", "WebView"),
                95 to setOf("app-desktop", "Task 16C"),
                96 to setOf("TEMP-COMPAT", "Task 16A"),
            )
        expectedGaps.forEach { (id, terms) ->
            val gap = requiredText(statusDecisionForTask(items.getValue(id).jsonObject, id, "Task 13"), "gap", id, "statusDecision")
            terms.forEach { term -> assertTrue(term in gap, "ID $id gap must preserve `$term`") }
        }

        val security = items.getValue(92).jsonObject
        val privacyCapabilities = security.getValue("privacyCapabilities").jsonObject
        setOf("nativeNotificationContent", "telemetry").forEach { capability ->
            val boundary = privacyCapabilities.getValue(capability).jsonObject
            assertEquals("UNSUPPORTED", requiredText(boundary, "status", 92, "privacyCapabilities.$capability"))
            assertTrue(requiredText(boundary, "uiBehavior", 92, "privacyCapabilities.$capability").isNotBlank())
        }
        val moduleBoundaryFixtures =
            items.getValue(95).jsonObject.getValue("roleEvidence").jsonObject.getValue("FIXTURE").jsonArray
                .map { requiredText(it.jsonObject, "artifact", 95, "roleEvidence.FIXTURE") }
        assertTrue(
            moduleBoundaryFixtures.any { it.contains("LibraryScreenModelTest.kt#") },
            "ID 95 must execute a real Desktop consumer of domain use cases, not only a source or line-count guard",
        )
        val compat = items.getValue(96).jsonObject
        assertTrue("TEMP-COMPAT" in compat.getValue("tags").jsonArray.map { it.jsonPrimitive.content })
        val compatFixtures =
            compat.getValue("roleEvidence").jsonObject.getValue("FIXTURE").jsonArray
                .map { requiredText(it.jsonObject, "artifact", 96, "roleEvidence.FIXTURE") }
        assertTrue(compatFixtures.any { it.contains("RealExtensionBuildCompatTest.kt#") })
        assertTrue(compatFixtures.any { it.contains("RealExtensionWebViewUnsupportedCompatTest.kt#") })

        val plan = Files.readString(repositoryRoot.resolve("docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md"))
        assertTrue(markdownFrontmatter(plan)["active-task"] in setOf("Task 14", "Task 14B", "Task 14C", "Task 15", "Task 16A", "Task 16B", "Task 16C", "Task 16D", "Task 17"), "Task 13 closeout must advance to Task 14")
        assertTrue(Regex("""(?m)^- \[x] Task 13[：:]""").containsMatchIn(plan), "Completed Task 13 must be checked")
        assertTrue(Regex("""(?m)^- \[[x ]\] Task 14[：:]""").containsMatchIn(plan), "Task 14 must remain tracked")
        assertTrue("ID 95" in plan && "Task 16C" in plan && "ID 96" in plan && "Task 16A" in plan, "Task 13 must retain finite architecture follow-ups")
    }

    @Tag("parity-governance")
    @Test
    fun `Task 14A resolves every unclassified debt and records the finite product child plan`() {
        val repositoryRoot = repositoryRoot()
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }
        val decisions =
            linkedMapOf(
                3 to "extract",
                4 to "adapter",
                32 to "reuse",
                39 to "adapter",
                69 to "extract",
                70 to "extract",
                87 to "adapter",
                88 to "adapter",
            )
        val productStatuses =
            mapOf(
                3 to "CHARACTERIZED",
                32 to "WIRED",
                69 to "CHARACTERIZED",
                70 to "CHARACTERIZED",
                87 to "SHARED",
            )
        decisions.forEach { (id, expectedDecision) ->
            val item = items.getValue(id).jsonObject
            assertFalse("task14StatusDecision" in item, "ID $id must not retain a second Task 14 decision authority")
            assertEquals(if (id == 3) "Task 16D" else "Task 14A", requiredText(item.getValue("statusDecision").jsonObject, "task", id, "statusDecision"))
            assertEquals(
                setOf("Task ${mapOf(3 to 6, 4 to 6, 32 to 8, 39 to 9, 69 to 11, 70 to 11, 87 to 12, 88 to 13).getValue(id)}") + if (id == 3) setOf("Task 14A") else emptySet(),
                item.getValue("statusDecisionHistory").jsonArray
                    .map { requiredText(it.jsonObject, "task", id, "statusDecisionHistory") }
                    .toSet(),
                "ID $id must preserve its prior task decision only as history",
            )
            val resolution = item.getValue("unclassifiedDebtResolution").jsonObject
            assertEquals(expectedDecision, requiredText(resolution, "decision", id, "unclassifiedDebtResolution"))
            assertEquals(fixedOriginalMihonRef, requiredText(resolution, "fixedMainRef", id, "unclassifiedDebtResolution"))
            listOf("reason", "userEntry", "userFeedback").forEach { field ->
                requiredText(resolution, field, id, "unclassifiedDebtResolution")
            }
            val callPath = resolution.getValue("productionCallPath").jsonObject
            listOf("currentAndroid", "sharedOrAdapter", "desktop").forEach { role ->
                assertTrue(
                    callPath.getValue(role).jsonArray.map { it.jsonPrimitive.content }.all(String::isNotBlank),
                    "ID $id $role production call path must be non-empty",
                )
                assertTrue(callPath.getValue(role).jsonArray.isNotEmpty(), "ID $id $role production call path is required")
            }
            val protection = resolution.getValue("protectionEvidence").jsonArray.map { it.jsonPrimitive.content }
            assertTrue(protection.isNotEmpty(), "ID $id executable protection evidence is required")
            protection.forEach { artifact ->
                val path = artifact.substringBefore("#")
                val method = artifact.substringAfter("#", "")
                assertTrue(method.isNotBlank(), "ID $id protection evidence must name an executable test method")
                val testFile = repositoryRoot.resolve(path)
                assertTrue(Files.isRegularFile(testFile), "ID $id missing protection file $path")
                assertTrue(Files.readString(testFile).contains("fun `$method`"), "ID $id missing test method $method")
            }
            assertTrue(
                item.getValue("deviations").jsonArray.none {
                    it.jsonObject.getValue("classification").jsonPrimitive.content == "UNCLASSIFIED_DEBT"
                },
                "ID $id must remove UNCLASSIFIED_DEBT after the unique decision",
            )
        }

        setOf(4, 39, 88).forEach { id ->
            val item = items.getValue(id).jsonObject
            assertEquals("VERIFIED", requiredText(item, "status", id))
            val statusDecision = item.getValue("statusDecision").jsonObject
            assertEquals("PROMOTE_VERIFIED", requiredText(statusDecision, "decision", id, "statusDecision"))
            assertEquals("NONE", requiredText(statusDecision, "followUp", id, "statusDecision"))
            assertEquals("NONE", requiredText(statusDecision, "gap", id, "statusDecision"))
            assertEquals(
                setOf("FIXED_ORIGINAL", "CURRENT_ANDROID", "SHARED_OR_ADAPTER", "DESKTOP_CONSUMER", "FIXTURE"),
                item.getValue("roleEvidence").jsonObject.keys,
                "ID $id terminal role evidence",
            )
        }
        productStatuses.forEach { (id, status) ->
            val item = items.getValue(id).jsonObject
            assertEquals(status, requiredText(item, "status", id))
            val statusDecision =
                if (id == 3) {
                    statusDecisionForTask(item, id, "Task 14A")
                } else {
                    item.getValue("statusDecision").jsonObject
                }
            assertEquals("KEEP_GAP", requiredText(statusDecision, "decision", id, "statusDecision"))
            assertTrue(
                requiredText(statusDecision, "followUp", id, "statusDecision")
                    .startsWith("docs/superpowers/plans/2026-07-24-task-14-product-parity-closure.md#task-"),
            )
            assertTrue(requiredText(statusDecision, "gap", id, "statusDecision") != "NONE")
        }

        val plan = Files.readString(repositoryRoot.resolve("docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md"))
        assertTrue(markdownFrontmatter(plan)["active-task"] in setOf("Task 14C", "Task 15", "Task 16A", "Task 16B", "Task 16C", "Task 16D", "Task 17"))
        assertTrue(Regex("""(?m)^- \[[x ]\] Task 14[：:]""").containsMatchIn(plan))
        assertTrue("Task 14A" in plan && "Task 14B" in plan && "Task 14C" in plan)
    }

    @Tag("parity-governance")
    @Test
    fun `Task 14B creates one finite product closure plan and advances parent handoff`() {
        val repositoryRoot = repositoryRoot()
        val childRelative = "docs/superpowers/plans/2026-07-24-task-14-product-parity-closure.md"
        val childPath = repositoryRoot.resolve(childRelative)
        val parent = Files.readString(repositoryRoot.resolve("docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md"))
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }
        val followUps =
            mapOf(
                3 to "$childRelative#task-141-a1-id-3-shared-screen-state",
                32 to "$childRelative#task-143-a3-id-32-android-extension-repository-wiring",
                69 to "$childRelative#task-145-b1-id-69-provider-neutral-core",
                70 to "$childRelative#task-147-b3-id-70-delayed-tracker-sync",
                87 to "$childRelative#task-149-c1-id-87-desktop-language",
            )
        val handoffProblems = mutableListOf<String>()
        if (!Files.isRegularFile(childPath)) handoffProblems += "consolidated child plan is missing"
        followUps.forEach { (id, expected) ->
            val actual = requiredText(items.getValue(id).jsonObject.getValue("statusDecision").jsonObject, "followUp", id, "statusDecision")
            if (actual != expected) handoffProblems += "ID $id followUp is still `$actual`"
        }
        if (markdownFrontmatter(parent)["active-task"] !in setOf("Task 14C", "Task 15", "Task 16A", "Task 16B", "Task 16C", "Task 16D", "Task 17")) handoffProblems += "parent active-task has not advanced to Task 14C or later"
        if (!Regex("""(?m)^  - \[x] Task 14B[：:]""").containsMatchIn(parent)) handoffProblems += "Task 14B is not checked"
        assertTrue(handoffProblems.isEmpty(), handoffProblems.joinToString("; "))

        val child = Files.readString(childPath)
        val metadata = markdownFrontmatter(child)
        assertEquals("docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md", metadata["parent-plan"])
        assertEquals("Task 14", metadata["parent-task"])
        assertEquals(fixedOriginalMihonRef, metadata["original-ref"])
        assertTrue(metadata["status"] in setOf("planned", "completed"))
        assertFalse("TBD" in child || "扫描" in child, "child plan must name finite files and decisions")

        val boundaries =
            mapOf(
                "141 A1" to "shared+android",
                "142 A2" to "shared+desktop",
                "143 A3" to "shared+android",
                "144 A4" to "shared+desktop",
                "145A B1a" to "shared",
                "145B1 B1b-1" to "android",
                "145B2 B1b-2" to "android",
                "146A B2a" to "shared+desktop",
                "146B B2b" to "desktop-platform+desktop",
                "146C B2c" to "desktop",
                "146D B2d" to "shared+desktop",
                "147 B3" to "shared+android",
                "148 B4" to "shared+desktop",
                "149 C1" to "desktop",
            )
        val allowedBoundaries =
            setOf(
                "shared",
                "android",
                "desktop",
                "shared+android",
                "shared+desktop",
                "desktop-platform+desktop",
                "verification",
                "docs",
                "tooling",
            )
        val overviewTasks =
            Regex("""(?m)^- \[[ xX]] Task (\d+)[：:]([A-Z]\d)""").findAll(child)
                .map { "${it.groupValues[1]} ${it.groupValues[2]}" }
                .toSet()
        val splitAwareOverviewTasks =
            Regex("""(?m)^- \[[ xX]] Task (\d+[A-Z]?\d*)[^A-Za-z0-9]+([A-Z]\d[a-z]?(?:-\d)?)""").findAll(child)
                .map { "${it.groupValues[1]} ${it.groupValues[2]}" }
                .toSet()
        assertEquals(boundaries.keys, splitAwareOverviewTasks, "child overview must track every product Task")
        boundaries.forEach { (task, boundary) ->
            val section = child.substringAfter("### Task $task ", "").substringBefore("\n### Task ")
            assertTrue(section.isNotBlank(), "Task $task section is required")
            assertEquals(1, Regex("""(?m)^\*\*Risk axis:\*\* .+$""").findAll(section).count(), "Task $task must have one risk axis")
            val declaredBoundary = Regex("""\*\*Platform boundary:\*\* (\S+)""").find(section)?.groupValues?.get(1)
            assertTrue(declaredBoundary in allowedBoundaries && declaredBoundary == boundary, "Task $task platform boundary")
            val scope = Regex("""\*\*Estimated scope:\*\* (\d+) files, (\d+) lines""").find(section)
                ?: throw AssertionError("Task $task estimated scope is required")
            assertTrue(scope.groupValues[1].toInt() > 0 && scope.groupValues[2].toInt() > 0, "Task $task scope must be a positive review hint")
            listOf("**Verification:**", "**RED:**", "**GREEN:**", "**Mutation:**", "**User entry:**", "**Feedback:**", "**Desktop zero-regression:**")
                .forEach { marker -> assertTrue(marker in section, "Task $task missing $marker") }
            val plannedFiles = Regex("""(?m)^- (?:Create|Modify): `([^`]+)`$""").findAll(section).map { it.groupValues[1] }.toList()
            assertTrue(plannedFiles.isNotEmpty(), "Task $task must name candidate files")
            plannedFiles.forEach { path -> assertFalse(path.contains("*") || path.contains("TBD"), "Task $task has a non-finite file path") }
            assertFalse(
                plannedFiles.any { it.startsWith("app/src/") } && plannedFiles.any { it.startsWith("app-desktop/src/") },
                "Task $task must not mix Android and Desktop consumers",
            )
        }
        val b3 = child.substringAfter("### Task 147 B3 ").substringBefore("\n### Task ")
        setOf(
            "app/src/main/java/eu/kanade/domain/track/service/DelayedTrackingUpdateJob.kt",
            "app/src/main/java/eu/kanade/domain/track/store/DelayedTrackingStore.kt",
            "app/src/main/java/eu/kanade/domain/track/interactor/TrackChapter.kt",
            "app/src/test/java/eu/kanade/domain/track/service/DelayedTrackingUpdateJobSharedQueueTest.kt",
        ).forEach { assertTrue("`$it`" in b3, "Task B3 must execute Android production owner $it") }
        assertFalse("cross-platform" in child || "platform-adapter" in child || "desktop-product" in child)
        assertTrue(Regex("""(?m)^- \[[x ]\] Task 14[：:]""").containsMatchIn(parent), "Task 14 remains tracked")
        assertTrue(Regex("""(?m)^  - \[[x ]\] Task 14C[：:]""").containsMatchIn(parent), "Task 14C remains tracked")
    }

    @Tag("parity-governance")
    @Test
    fun `Task 14C records the governance snapshot and closes the parent decision task`() {
        val repositoryRoot = repositoryRoot()
        val tracker = Files.readString(repositoryRoot.resolve("docs/desktop-parity/PARITY_TRACKER.md"))
        val parent = Files.readString(repositoryRoot.resolve("docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md"))
        val child = Files.readString(repositoryRoot.resolve("docs/superpowers/plans/2026-07-24-task-14-product-parity-closure.md"))
        assertTrue(markdownFrontmatter(child)["status"] in setOf("planned", "completed"))
        assertFalse("active-task" in markdownFrontmatter(child), "child progress must derive from its first unchecked checkbox")
        val task14Ids = setOf(3, 4, 32, 39, 69, 70, 87, 88)
        val expected =
            manifestItems(repositoryRoot)
                .map { it.jsonObject }
                .filter { validatedId(it) in task14Ids }
                .associate { item ->
                    val id = validatedId(item)
                    id to
                        Triple(
                            requiredText(item.getValue("unclassifiedDebtResolution").jsonObject, "decision", id, "unclassifiedDebtResolution"),
                            requiredText(item, "status", id),
                            requiredText(item.getValue("statusDecision").jsonObject, "followUp", id, "statusDecision"),
                        )
                }
        assertEquals(task14Ids, expected.keys, "Task 14 manifest ID set must stay exact")
        val problems = mutableListOf<String>()
        if ("## Task 14 governance snapshot" !in tracker) problems += "tracker Task 14 governance snapshot is missing"
        if (markdownFrontmatter(parent)["active-task"] !in setOf("Task 15", "Task 16A", "Task 16B", "Task 16C", "Task 16D", "Task 17")) problems += "parent active-task has not advanced to Task 15 or later"
        if (!Regex("""(?m)^- \[x] Task 14[：:]""").containsMatchIn(parent)) problems += "parent Task 14 is not checked"
        if (!Regex("""(?m)^  - \[x] Task 14C[：:]""").containsMatchIn(parent)) problems += "Task 14C is not checked"
        assertTrue(problems.isEmpty(), problems.joinToString("; "))

        val snapshot = tracker.substringAfter("## Task 14 governance snapshot").substringBefore("\n## ")
        assertTrue("manifest is the only machine-readable status authority" in snapshot)
        val rowEntries =
            Regex("""(?m)^\| (\d+) \| `([^`]+)` \| `([^`]+)` \| `([^`]+)` \|$""")
                .findAll(snapshot)
                .map { match ->
                    match.groupValues[1].toInt() to Triple(match.groupValues[2], match.groupValues[3], match.groupValues[4])
                }
                .toList()
        assertEquals(8, rowEntries.size, "Task 14 governance snapshot must contain exactly eight rows")
        assertEquals(8, rowEntries.map { it.first }.distinct().size, "Task 14 governance snapshot must not repeat IDs")
        val rows = rowEntries.toMap()
        assertEquals(expected, rows, "Task 14 governance decisions must be unique and exact")
        assertTrue(Regex("""(?m)^- \[[x ]\] Task 15[：:]""").containsMatchIn(parent), "Task 15 remains tracked")
    }

    @Tag("parity-governance")
    @Test
    fun `Task 15 preserves unaccepted platform candidates and creates finite acceptance work`() {
        val repositoryRoot = repositoryRoot()
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }
        val parent = Files.readString(repositoryRoot.resolve("docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md"))
        val childRelative = "docs/superpowers/plans/2026-07-24-task-15-platform-evidence-closure.md"
        val childPath = repositoryRoot.resolve(childRelative)
        val expectedStatuses =
            mapOf(81 to "CANDIDATE", 82 to "CANDIDATE", 83 to "CANDIDATE", 84 to "CANDIDATE", 85 to "EXEMPT", 86 to "CANDIDATE", 92 to "CANDIDATE")
        val expectedFollowUps =
            mapOf(
                81 to "$childRelative#task-151-current-commit-uri-and-host-share-acceptance",
                82 to "$childRelative#task-151-current-commit-uri-and-host-share-acceptance",
                83 to "$childRelative#task-152-credential-and-capture-os-matrix",
                84 to "$childRelative#task-152-credential-and-capture-os-matrix",
                85 to "NONE",
                86 to "$childRelative#task-153-signed-artifact-and-installer-handoff",
                92 to "$childRelative#task-152-credential-and-capture-os-matrix",
            )
        val gapTerms =
            mapOf(
                81 to setOf("148594c", "BUILD 45", "cold", "running"),
                82 to setOf("148594c", "host share", "non-interactive"),
                83 to setOf("DPAPI", "Keychain", "Secret Service"),
                84 to setOf("capture", "macOS", "Linux"),
                86 to setOf("signed", "MSI", "DMG", "handoff"),
                92 to setOf("credential", "capture", "Unsupported"),
            )
        val problems = mutableListOf<String>()
        if (!Files.isRegularFile(childPath)) problems += "platform evidence child plan is missing"
        expectedStatuses.forEach { (id, status) ->
            val item = items.getValue(id).jsonObject
            if (requiredText(item, "status", id) != status) problems += "ID $id status changed without terminal evidence"
            val decision = item.getValue("statusDecision").jsonObject
            if (requiredText(decision, "task", id, "statusDecision") != "Task 15") problems += "ID $id current decision is not Task 15"
            if (requiredText(decision, "followUp", id, "statusDecision") != expectedFollowUps.getValue(id)) problems += "ID $id follow-up is not finite"
        }
        if (markdownFrontmatter(parent)["active-task"] !in setOf("Task 16A", "Task 16B", "Task 16C", "Task 16D", "Task 17")) problems += "parent active-task has not advanced to Task 16A or later"
        if (!Regex("""(?m)^- \[x] Task 15[：:]""").containsMatchIn(parent)) problems += "Task 15 is not checked"
        assertTrue(problems.isEmpty(), problems.joinToString("; "))

        expectedStatuses.keys.forEach { id ->
            val item = items.getValue(id).jsonObject
            val decision = item.getValue("statusDecision").jsonObject
            val expectedPreviousTask = if (id == 92) "Task 13" else "Task 12"
            statusDecisionForTask(item, id, expectedPreviousTask)
            assertEquals(if (id == 85) "KEEP_EXEMPT" else "KEEP_GAP", requiredText(decision, "decision", id, "statusDecision"))
            if (id == 85) {
                assertEquals("NONE", requiredText(decision, "gap", id, "statusDecision"))
                assertEquals(
                    "docs/superpowers/specs/2026-07-12-mihon-desktop-upstream-parity-design.md:217",
                    requiredText(item.getValue("exemptionApproval").jsonObject, "approvalSource", id, "exemptionApproval"),
                )
            } else {
                assertEquals("NONE", requiredText(item, "platformExemptionEvidence", id))
                assertFalse("exemptionApproval" in item, "ID $id has no user-approved exemption")
                val gap = requiredText(decision, "gap", id, "statusDecision")
                gapTerms.getValue(id).forEach { term -> assertTrue(term in gap, "ID $id gap must record `$term`") }
            }
        }

        val child = Files.readString(childPath)
        assertEquals("planned", markdownFrontmatter(child)["status"])
        assertFalse("active-task" in markdownFrontmatter(child), "child progress must derive from its first unchecked checkbox")
        assertEquals(
            setOf("151", "152", "153"),
            Regex("""(?m)^- \[ ] Task (\d+)[：:]""").findAll(child).map { it.groupValues[1] }.toSet(),
            "platform evidence child plan must remain finite",
        )
        val task151 = child.substringAfter("### Task 151 ").substringBefore("\n### Task ")
        val task152 = child.substringAfter("### Task 152 ").substringBefore("\n### Task ")
        val task153 = child.substringAfter("### Task 153 ").substringBefore("\n## ")
        mapOf(
            "Task 151" to
                task151 to
                setOf(
                    "git rev-parse 'HEAD^{tree}'",
                    "scripts/task15-platform-evidence-test.ps1",
                    "scripts/task15-platform-evidence-test.sh",
                    "Get-FileHash",
                    "shasum -a 256",
                    "uri-cold",
                    "uri-running",
                    "host-share",
                ),
            "Task 152" to
                task152 to
                setOf(
                    "credential-roundtrip",
                    "capture",
                    "security find-generic-password",
                    "secret-tool",
                    "org.freedesktop.secrets",
                ),
            "Task 153" to
                task153 to
                setOf(
                    "Get-AuthenticodeSignature",
                    "codesign --verify",
                    "spctl -a",
                    "installer-handoff",
                    "build/task15-platform-evidence",
                ),
        ).forEach { (taskAndBody, markers) ->
            val (task, body) = taskAndBody
            markers.forEach { marker -> assertTrue(marker in body, "$task must define repeatable `$marker` evidence") }
        }
        assertFalse("app-desktop/src/main/kotlin/" in task153, "Task 153 must not pre-authorize product changes")
        assertFalse("DesktopUpdateInstallerTest.kt" in task153, "Task 153 must not pre-authorize product-test changes")
        assertTrue("另建 TDD 产品修复计划" in task153, "Task 153 must stop and replan when a real probe finds a product defect")
        assertTrue(Regex("""(?m)^- \[[x ]\] Task 16A[：:]""").containsMatchIn(parent), "Task 16A remains tracked")
    }

    @Tag("parity-governance")
    @Test
    fun `Task 16A closes symbol scoped compat and historical format removal evidence`() {
        data class RemovalExpectation(
            val previousTask: String,
            val decision: String,
            val disposition: String,
            val symbols: Set<String>,
            val fixtures: Set<String>,
            val removalTerms: Set<String>,
            val symbolInventory: String = "NONE",
        )

        val repositoryRoot = repositoryRoot()
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }
        val parent = Files.readString(repositoryRoot.resolve("docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md"))
        val expected =
            mapOf(
                35 to
                    RemovalExpectation(
                        previousTask = "Task 8",
                        decision = "PROMOTE_VERIFIED",
                        disposition = "RETAIN_ADAPTER",
                        symbols =
                            setOf(
                                "ExtensionLoader.loadExtension",
                                "DesktopExtensionInstallPort.apkConverter.convert",
                                "DesktopExtensionManager.loader.loadExtensions/loadPackage",
                                "DesktopExtensionLoader.loadFromJar/loadByClassName",
                                "ExtensionClassLoader.loadClass",
                            ),
                        fixtures =
                            setOf(
                                "app-desktop/src/test/kotlin/mihon/desktop/extension/RealExtensionCompatEvidenceTest.kt#immutable ManHuaGui APK loads through the production converter and loader",
                            ),
                        removalTerms = setOf("immutable APK", "production converter", "loader", "per symbol"),
                    ),
                74 to
                    RemovalExpectation(
                        previousTask = "Task 12",
                        decision = "KEEP_VERIFIED",
                        disposition = "RETAIN_READER_WRITER",
                        symbols =
                            setOf(
                                "BackupCodec.encode",
                                "BackupCodec.decode",
                                "BackupCreator.encodeForBackup",
                                "BackupDecoder.decode",
                                "DesktopBackupCreator.encodeToBytes",
                                "DesktopBackupCreator.decodeFromBytes",
                            ),
                        fixtures =
                            setOf(
                                "data/src/commonTest/kotlin/tachiyomi/data/backup/BackupCodecContractTest.kt#fixed-main Android full fixture decodes and reencodes with canonical schema",
                                "app/src/test/java/eu/kanade/tachiyomi/data/backup/BackupAndroidCodecIntegrationTest.kt#decoder reads common codec backup through content uri",
                                "app-desktop/src/test/kotlin/mihon/desktop/backup/DesktopBackupCompatibilityTest.kt#first Desktop protobuf writer fixture restores every historical field",
                            ),
                        removalTerms = setOf("fixed-main", "current Android", "Desktop first-writer", "decode", "writer"),
                    ),
                96 to
                    RemovalExpectation(
                        previousTask = "Task 13",
                        decision = "PROMOTE_VERIFIED",
                        disposition = "RETAIN_ADAPTER",
                        symbols =
                            setOf(
                                "DesktopExtensionInstallPort.apkConverter.convert",
                                "DesktopExtensionLoader.loadFromJar",
                                "AndroidCompat.initialize/startApp",
                                "DesktopAppModule.initAndroidCompatApplication",
                            ),
                        fixtures =
                            setOf(
                                "app-desktop/src/test/kotlin/mihon/desktop/extension/CompatEvidenceContractTest.kt#inventory covers the complete public compat adapter surface",
                                "app-desktop/src/test/kotlin/mihon/desktop/extension/RealExtensionBuildCompatTest.kt#real MangaDex headers use host version and Android release ABI",
                                "app-desktop/src/test/kotlin/mihon/desktop/extension/RealExtensionWebViewUnsupportedCompatTest.kt#real Comix WebView path fails fast with the explicit desktop boundary",
                                "app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt#desktop DI binds the started Android compat Application exact type",
                            ),
                        removalTerms = setOf("45", "44 required", "WebView", "per symbol", "immutable APK"),
                        symbolInventory = "app-desktop/src/test/resources/extensions/compat-inventory.json",
                    ),
            )

        expected.forEach { (id, expectation) ->
            val item = items.getValue(id).jsonObject
            assertEquals("VERIFIED", requiredText(item, "status", id))
            statusDecisionForTask(item, id, expectation.previousTask)
            val decision = item.getValue("statusDecision").jsonObject
            assertEquals("Task 16A", requiredText(decision, "task", id, "statusDecision"))
            assertEquals(expectation.decision, requiredText(decision, "decision", id, "statusDecision"))
            assertEquals("NONE", requiredText(decision, "followUp", id, "statusDecision"))
            assertEquals("NONE", requiredText(decision, "gap", id, "statusDecision"))

            val audit = item.getValue("compatRemovalAudit").jsonObject
            assertEquals(
                setOf("task", "disposition", "auditedSymbols", "fixtureEvidence", "symbolInventory", "removalCondition"),
                audit.keys,
                "ID $id Task 16A audit schema",
            )
            assertEquals("Task 16A", requiredText(audit, "task", id, "compatRemovalAudit"))
            assertEquals(expectation.disposition, requiredText(audit, "disposition", id, "compatRemovalAudit"))
            assertEquals(expectation.symbols, audit.getValue("auditedSymbols").jsonArray.map { it.jsonPrimitive.content }.toSet())
            assertEquals(expectation.fixtures, audit.getValue("fixtureEvidence").jsonArray.map { it.jsonPrimitive.content }.toSet())
            assertEquals(expectation.symbolInventory, requiredText(audit, "symbolInventory", id, "compatRemovalAudit"))
            val removalCondition = requiredText(audit, "removalCondition", id, "compatRemovalAudit")
            expectation.removalTerms.forEach { term -> assertTrue(term in removalCondition, "ID $id removal condition must record `$term`") }
        }

        val compat = items.getValue(96).jsonObject
        val bootstrapPath = "app-desktop/src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt"
        assertTrue(
            bootstrapPath in compat.getValue("desktopConsumerAdapterPaths").jsonArray.map { it.jsonPrimitive.content },
            "ID 96 must declare the production bootstrap adapter path",
        )
        val bootstrapConsumers =
            compat.getValue("roleEvidence").jsonObject.getValue("DESKTOP_CONSUMER").jsonArray
                .map { it.jsonObject }
                .filter { requiredText(it, "path", 96, "roleEvidence.DESKTOP_CONSUMER") == bootstrapPath }
        assertEquals(
            setOf("AndroidCompat.initialize" to "192", "AndroidCompat.startApp" to "194"),
            bootstrapConsumers.map {
                requiredText(it, "symbol", 96, "roleEvidence.DESKTOP_CONSUMER") to
                    requiredText(it, "line", 96, "roleEvidence.DESKTOP_CONSUMER")
            }.toSet(),
            "ID 96 must bind both production bootstrap calls",
        )
        val bootstrapTest = "app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt"
        val bootstrapMethod = "desktop DI binds the started Android compat Application exact type"
        assertTrue(bootstrapTest in compat.getValue("protectionTests").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(
            bootstrapMethod in
                compat.getValue("statusDecision").jsonObject.getValue("behaviorMethods").jsonObject
                    .getValue(bootstrapTest).jsonArray.map { it.jsonPrimitive.content },
            "ID 96 production bootstrap caller must stay executable through DI wiring",
        )

        val inventory =
            Json.parseToJsonElement(
                Files.readString(repositoryRoot.resolve(expected.getValue(96).symbolInventory)),
            ).jsonObject
        assertEquals(fixedOriginalMihonRef, requiredText(inventory, "authorityRef", 96, "compat inventory"))
        val inventoryEntries = inventory.getValue("entries").jsonArray.map { it.jsonObject }
        assertEquals(45, inventoryEntries.size)
        assertEquals(45, inventoryEntries.map { requiredText(it, "symbol", 96, "compat inventory") }.distinct().size)
        assertEquals(
            mapOf("required" to 44, "unsupported" to 1),
            inventoryEntries.groupingBy { requiredText(it, "status", 96, "compat inventory") }.eachCount(),
        )

        assertTrue(markdownFrontmatter(parent)["active-task"] in setOf("Task 16B", "Task 16C", "Task 16D", "Task 17"))
        assertTrue(Regex("""(?m)^- \[x] Task 16A[：:]""").containsMatchIn(parent), "Task 16A must be checked")
        assertTrue(Regex("""(?m)^- \[[x ]\] Task 16B[：:]""").containsMatchIn(parent), "Task 16B remains tracked")
    }

    @Tag("parity-governance")
    @Test
    fun `Task 16B extracts the only duplicated business state machine into a finite child plan`() {
        val repositoryRoot = repositoryRoot()
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }
        val task16bCandidates =
            items.filterValues { element ->
                val item = element.jsonObject
                val decisions =
                    listOfNotNull(item["statusDecision"]?.jsonObject) +
                        item["statusDecisionHistory"]?.jsonArray.orEmpty().map { it.jsonObject }
                decisions.any {
                    requiredText(it, "task", validatedId(item)) == "Task 16B" ||
                        requiredText(it, "followUp", validatedId(item)) == "Task 16B"
                }
            }.keys
        assertEquals(setOf(10), task16bCandidates, "Task 16B duplicate-rule inventory must stay exact")

        val item = items.getValue(10).jsonObject
        assertEquals("VERIFIED", requiredText(item, "status", 10))
        statusDecisionForTask(item, 10, "Task 6")
        val decision = item.getValue("statusDecision").jsonObject
        assertEquals("Task 163", requiredText(decision, "task", 10, "statusDecision"))
        assertEquals("PROMOTE_VERIFIED", requiredText(decision, "decision", 10, "statusDecision"))
        val childPlan = "docs/superpowers/plans/2026-07-24-task-16b-background-task-state-machine-closure.md"
        assertEquals("NONE", requiredText(decision, "followUp", 10, "statusDecision"))
        assertEquals("NONE", requiredText(decision, "gap", 10, "statusDecision"))

        val audit = item.getValue("duplicateBusinessRuleAudit").jsonObject
        assertEquals(
            setOf(
                "task",
                "disposition",
                "fixedOriginalSemantics",
                "crossPlatformBugfixes",
                "desktopProductDeviations",
                "platformSideEffectAdapters",
                "productionCallers",
                "writerOwnership",
                "behaviorEvidence",
                "missingBehaviorEvidence",
                "childPlan",
            ),
            audit.keys,
        )
        assertEquals("Task 16B", requiredText(audit, "task", 10, "duplicateBusinessRuleAudit"))
        assertEquals("EXTRACT", requiredText(audit, "disposition", 10, "duplicateBusinessRuleAudit"))
        assertEquals(childPlan, requiredText(audit, "childPlan", 10, "duplicateBusinessRuleAudit"))

        fun strings(field: String) = audit.getValue(field).jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(
            setOf(
                "Periodic scheduling maps interval, network, Wi-Fi, charging and battery restrictions to one unique WorkManager job with linear backoff.",
                "Manual refresh refuses a second tagged running update and enqueues one unique manual job with KEEP semantics.",
                "Stopping cancels running tagged work and restores the periodic job; worker cancellation is reported as a non-error terminal result.",
            ),
            strings("fixedOriginalSemantics"),
        )
        assertEquals(
            setOf(
                "Explicit idempotency keys, checkpoints, typed task states and single-terminal notifications are fork reliability improvements consumed by current Android and Desktop through one shared transition core.",
            ),
            strings("crossPlatformBugfixes"),
        )
        assertEquals(
            setOf(
                "Desktop persists worksets, completed manga IDs, checkpoints and structured partial failures for crash recovery.",
                "Desktop startup recovery and concurrent runNow occurrence sharing remain Desktop runtime behavior layered on the shared lifecycle.",
                "Desktop batch migration retains queued and failed cancellation as an explicit product adapter; library update cancellation remains Running-only through the shared lifecycle.",
            ),
            strings("desktopProductDeviations"),
        )

        val adapters = audit.getValue("platformSideEffectAdapters").jsonObject
        assertEquals(
            setOf("WorkManager constraints, unique-work enqueue/cancel, foreground execution and Android notifications"),
            adapters.getValue("android").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertEquals(
            setOf("FileTaskCheckpointStore atomic persistence, coroutine runtime ownership and Desktop notifications"),
            adapters.getValue("desktop").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )

        val callers = audit.getValue("productionCallers").jsonObject
        assertEquals(
            setOf(
                "app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateJob.kt#setupTask/startNow/stop",
                "app/src/main/java/mihon/core/migration/migrations/SetupLibraryUpdateMigration.kt#LibraryUpdateJob.setupTask",
                "app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/restorers/PreferenceRestorer.kt#LibraryUpdateJob.setupTask",
                "app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsLibraryScreen.kt#LibraryUpdateJob.setupTask",
                "app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt#LibraryUpdateJob.startNow",
                "app/src/main/java/eu/kanade/tachiyomi/ui/updates/UpdatesScreenModel.kt#LibraryUpdateJob.startNow",
                "app/src/main/java/eu/kanade/tachiyomi/data/notification/NotificationReceiver.kt#LibraryUpdateJob.stop",
            ),
            callers.getValue("currentAndroid").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertEquals(
            setOf("domain/src/commonMain/kotlin/mihon/domain/task/BackgroundTask.kt#BackgroundTaskLifecycle/TaskLifecycleEvent/TaskLifecycleDecision"),
            callers.getValue("shared").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertEquals(
            setOf(
                "app-desktop/src/main/kotlin/mihon/desktop/task/DesktopTaskScheduler.kt#register/lifecycleTransition/cancelRunning",
                "app-desktop/src/main/kotlin/mihon/desktop/domain/LibraryUpdateScheduler.kt#start/runNow/cancelUpdate/cancelRunning",
                "app-desktop/src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt#DesktopTaskScheduler/LibraryUpdateScheduler bindings",
            ),
            callers.getValue("desktop").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )

        val writers = audit.getValue("writerOwnership").jsonObject
        assertTrue(requiredText(writers, "currentAndroid", 10, "writerOwnership").contains("WorkManager"))
        assertTrue(requiredText(writers, "shared", 10, "writerOwnership").contains("legal transition"))
        assertTrue(requiredText(writers, "desktop", 10, "writerOwnership").contains("FileTaskCheckpointStore"))

        val behavior = audit.getValue("behaviorEvidence").jsonObject
        assertEquals(
            setOf(
                "app/src/test/java/eu/kanade/tachiyomi/data/library/LibraryUpdateJobSharedLifecycleIntegrationTest.kt#startNow uses shared register and start decisions to control enqueue and return value",
                "app/src/test/java/eu/kanade/tachiyomi/data/library/LibraryUpdateJobSharedLifecycleIntegrationTest.kt#rejected shared completion changes the real worker result",
                "app/src/test/java/eu/kanade/tachiyomi/ui/updates/UpdatesScreenModelLibraryUpdateWiringTest.kt#updateLibrary exposes started and already running results through its production event",
            ),
            behavior.getValue("currentAndroid").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertEquals(
            setOf(
                "domain/src/commonTest/kotlin/mihon/domain/task/BackgroundTaskContractTest.kt#register creates one pending occurrence and only terminal tasks accept a new key",
                "domain/src/commonTest/kotlin/mihon/domain/task/BackgroundTaskContractTest.kt#complete fail and cancel have explicit legal state matrices",
                "domain/src/commonTest/kotlin/mihon/domain/task/BackgroundTaskContractTest.kt#one terminal result cannot be repeated or rewritten",
            ),
            behavior.getValue("shared").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertEquals(
            setOf(
                "app-desktop/src/test/kotlin/mihon/desktop/task/DesktopTaskSchedulerIntegrationTest.kt#production scheduler delegates lifecycle transitions to shared policy",
                "app-desktop/src/test/kotlin/mihon/desktop/task/DesktopTaskSchedulerIntegrationTest.kt#rejected shared start leaves the persisted task pending",
                "app-desktop/src/test/kotlin/mihon/desktop/domain/LibraryUpdateRecoveryIntegrationTest.kt#new instance resumes after cursor and never repeats successful manga",
                "app-desktop/src/test/kotlin/mihon/desktop/migration/DesktopBatchMigrationControllerTest.kt#cancelling a paused queue persists a desktop cancelled terminal",
                "app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt#测试配置入口使用隔离内存存储并解析实际依赖",
            ),
            behavior.getValue("desktop").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertEquals("NONE", requiredText(audit, "missingBehaviorEvidence", 10))

        val child = Files.readString(repositoryRoot.resolve(childPlan))
        val metadata = markdownFrontmatter(child)
        assertEquals("Task 16B", metadata["parent-task"])
        assertEquals(fixedOriginalMihonRef, metadata["original-ref"])
        assertEquals("completed", metadata["status"])
        assertFalse("active-task" in metadata, "child progress must derive from its first unchecked checkbox")
        setOf("Task 161", "Task 162", "Task 163").forEach { task ->
            assertEquals(1, Regex("""(?m)^### $task(?:\s|$)""").findAll(child).count(), "$task must be finite and unique")
            assertTrue(Regex("""(?m)^- \[x] $task[：:]""").containsMatchIn(child), "$task must be checked off")
        }
        assertFalse("Task 16C" in child, "Task 16B child plan must not absorb the architecture guard")

        val parent = Files.readString(repositoryRoot.resolve("docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md"))
        assertTrue(markdownFrontmatter(parent)["active-task"] in setOf("Task 16C", "Task 16D", "Task 17"))
        assertTrue(Regex("""(?m)^- \[x] Task 16B[：:]""").containsMatchIn(parent))
        assertTrue(Regex("""(?m)^- \[[x ]\] Task 16C[：:]""").containsMatchIn(parent))
    }

    @Tag("parity-governance")
    @Test
    fun `Task 16C binds compiled dependency guards and finite product remediation`() {
        val repositoryRoot = repositoryRoot()
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }
        val candidates = items.filter { (id, element) ->
            statusDecisionRecords(element.jsonObject, id).any {
                requiredText(it, "task", id) == "Task 16C" || requiredText(it, "followUp", id) == "Task 16C"
            }
        }.keys
        assertEquals(setOf(8, 95), candidates)

        val childPlan = "docs/superpowers/plans/2026-07-24-task-16c-ui-dependency-boundary-closure.md"
        val expected =
            mapOf(
                8 to Triple("SHARED", "$childPlan#task-169-compiled-boundary-closeout", 0),
                95 to Triple("WIRED", "$childPlan#task-169-compiled-boundary-closeout", 0),
            )
        val guardTest = "app-desktop/src/test/kotlin/mihon/desktop/architecture/DesktopArchitectureGuardTest.kt"
        fun JsonObject.strings(field: String) = getValue(field).jsonArray.map { it.jsonPrimitive.content }.toSet()
        expected.forEach { (id, expectation) ->
            val item = items.getValue(id).jsonObject
            assertEquals(expectation.first, requiredText(item, "status", id))
            statusDecisionForTask(item, id, if (id == 8) "Task 6" else "Task 13")
            val decision = item.getValue("statusDecision").jsonObject
            assertEquals("Task 16C", requiredText(decision, "task", id, "statusDecision"))
            assertEquals("REMEDIATE", requiredText(decision, "decision", id, "statusDecision"))
            assertEquals(expectation.second, requiredText(decision, "followUp", id, "statusDecision"))
            assertTrue(requiredText(decision, "gap", id, "statusDecision") != "NONE")
            assertTrue(guardTest in item.getValue("protectionTests").jsonArray.map { it.jsonPrimitive.content })

            val audit = item.getValue("architectureBoundaryAudit").jsonObject
            assertEquals("Task 16C", requiredText(audit, "task", id, "architectureBoundaryAudit"))
            assertEquals("JDK ToolProvider jdeps over compiled production and fixture classes", requiredText(audit, "mechanism", id))
            assertEquals(childPlan, requiredText(audit, "childPlan", id, "architectureBoundaryAudit"))
            assertEquals(expectation.third, audit.getValue("forbiddenCompiledEdges").jsonArray.size)
            audit.getValue("permittedPlatformAdapters").jsonArray.forEach { adapter ->
                requiredText(adapter.jsonObject, "edge", id)
                requiredText(adapter.jsonObject, "reason", id)
            }
            assertEquals(2, audit.strings("mutationEvidence").size)
            assertTrue(audit.strings("mutationEvidence").all { it.startsWith("$guardTest#") })
        }

        val networkAudit = items.getValue(8).jsonObject.getValue("architectureBoundaryAudit").jsonObject
        assertEquals(
            setOf(
                "eu.kanade.tachiyomi.network.AndroidNetworkResponseAdapter -> mihon.domain.network.NetworkErrorMapperKt",
                "mihon.desktop.source.MangaDexSource -> mihon.domain.network.NetworkErrorMapperKt",
                "mihon.desktop.di.DesktopAppModuleKt -> mihon.desktop.platform.DesktopNetworkHelper",
            ),
            networkAudit.strings("requiredCompiledEdges"),
        )
        assertTrue(networkAudit.strings("missingRequiredEdges").isEmpty())
        val network = items.getValue(8).jsonObject
        assertTrue(
            setOf(
                "app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt",
                "app/src/main/java/eu/kanade/tachiyomi/network/AndroidNetworkResponseAdapter.kt",
                "app/src/main/java/eu/kanade/tachiyomi/extension/api/ExtensionApi.kt",
            ).all(network.getValue("currentAndroidConsumerPaths").jsonArray.map { it.jsonPrimitive.content }::contains),
        )
        val androidMapperTest = "app/src/test/java/eu/kanade/tachiyomi/extension/api/ExtensionApiSharedCatalogTest.kt"
        val androidDiTest = "app/src/test/java/eu/kanade/tachiyomi/ui/browse/source/SourceSharedQueryWiringTest.kt"
        assertTrue(setOf(androidMapperTest, androidDiTest).all(network.getValue("protectionTests").jsonArray.map { it.jsonPrimitive.content }::contains))
        val networkBehavior = network.getValue("statusDecision").jsonObject.getValue("behaviorMethods").jsonObject
        assertEquals(
            setOf(
                "Android raw repository responses execute shared network error mapper",
                "Android malformed repository executes shared payload parser",
                "Android no arg API resolves AppModule adapter for raw repository response",
            ),
            networkBehavior.getValue(androidMapperTest).jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertEquals(
            setOf("Android app DI resolves production shared network response adapter"),
            networkBehavior.getValue(androidDiTest).jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )

        val moduleAudit = items.getValue(95).jsonObject.getValue("architectureBoundaryAudit").jsonObject
        assertEquals(
            setOf(
                "mihon.desktop.ui.library.LibraryScreenModel -> tachiyomi.domain.manga.interactor.GetLibraryManga",
                "mihon.desktop.ui.library.LibraryScreenModel -> tachiyomi.domain.category.interactor.GetCategories",
                "mihon.desktop.ui.library.LibraryScreenModel -> tachiyomi.domain.chapter.interactor.GetChaptersByMangaId",
                "mihon.desktop.ui.library.LibraryScreenModel -> tachiyomi.domain.chapter.interactor.GetBookmarkedChaptersByMangaId",
                "mihon.desktop.ui.library.LibraryScreenModel -> tachiyomi.domain.chapter.interactor.SetChapterReadStatus",
                "mihon.desktop.ui.library.LibraryScreenModel -> tachiyomi.domain.history.interactor.GetNextChapters",
                "mihon.desktop.ui.library.LibraryScreenModel -> tachiyomi.domain.manga.interactor.UpdateManga",
                "mihon.desktop.ui.library.LibraryScreenModel -> tachiyomi.domain.track.interactor.GetTracksPerManga",
                "mihon.desktop.ui.library.MangaDetailScreenModel -> tachiyomi.domain.category.interactor.GetCategories",
                "mihon.desktop.ui.library.MangaDetailScreenModel -> tachiyomi.domain.chapter.interactor.UpdateChapter",
                "mihon.desktop.ui.library.MangaDetailScreenModel -> tachiyomi.domain.chapter.interactor.SetChapterReadStatus",
                "mihon.desktop.ui.library.MangaDetailScreenModel -> tachiyomi.domain.creator.interactor.LinkMangaCreator",
                "mihon.desktop.ui.library.MangaDetailScreenModel -> tachiyomi.domain.manga.interactor.UpdateManga",
                "mihon.desktop.ui.library.MangaDetailScreenModel -> tachiyomi.domain.manga.interactor.SetMangaChapterFlags",
                "mihon.desktop.ui.library.MangaDetailScreenModel -> tachiyomi.domain.manga.interactor.UpdateLibraryMembership",
                "mihon.desktop.ui.authors.AuthorsRootScreen -> tachiyomi.domain.creator.interactor.GetCreators",
                "mihon.desktop.ui.authors.AuthorDetailScreen -> tachiyomi.domain.creator.interactor.GetCreatorDetails",
                "mihon.desktop.ui.authors.AuthorDetailScreen -> tachiyomi.domain.creator.interactor.DiscoverCreatorWorks",
                "mihon.desktop.ui.authors.AuthorDetailScreen -> tachiyomi.domain.creator.interactor.SetCreatorFollow",
                "mihon.desktop.ui.authors.WorkCompareScreen -> tachiyomi.domain.creator.interactor.GetCreatorDetails",
                "mihon.desktop.ui.tracking.TrackingScreenModel -> tachiyomi.domain.track.interactor.GetTracks",
                "mihon.desktop.ui.tracking.TrackingScreenModel -> tachiyomi.domain.track.interactor.InsertTrack",
                "mihon.desktop.ui.tracking.TrackingScreenModel -> tachiyomi.domain.track.interactor.DeleteTrack",
                "mihon.desktop.ui.tracking.TrackingSettingsScreen -> tachiyomi.domain.track.interactor.GetTracks",
                "mihon.desktop.ui.tracking.TrackingSettingsScreen -> tachiyomi.domain.track.interactor.InsertTrack",
                "mihon.desktop.ui.tracking.TrackingSettingsScreen -> tachiyomi.domain.track.interactor.DeleteTrack",
                "mihon.desktop.ui.browse.DesktopSourceCookieHeaderParser -> mihon.desktop.network.DesktopSourceLoginAdapter",
                "mihon.desktop.ui.browse.DesktopSourceLastUsedRecorder -> mihon.desktop.extension.DesktopSourceExtensionLookup",
                "mihon.desktop.ui.browse.DesktopSourceLoginController -> mihon.desktop.network.DesktopSourceLoginAdapter",
                "mihon.desktop.ui.browse.DesktopSourceLoginUiActions -> mihon.desktop.network.DesktopSourceLoginAdapter",
                "mihon.desktop.ui.browse.SourceBrowseScreen -> mihon.desktop.extension.DesktopSourceExtensionLookup",
                "mihon.desktop.ui.cloudflare.DesktopChallengeLoginController -> mihon.desktop.network.DesktopChallengeRecoveryPort",
                "mihon.desktop.ui.extension.DesktopExtensionPresentationPort -> mihon.desktop.extension.DesktopExtensionPresentationService",
                "mihon.desktop.ui.extension.ExtensionDetailsScreen -> mihon.desktop.network.DesktopExtensionCookiePort",
                "mihon.desktop.ui.extension.SourcePreferencesScreenKt -> mihon.desktop.extension.DesktopSourcePreferenceContextFactory",
                "mihon.desktop.ui.home.HomeScreen -> mihon.desktop.network.DesktopChallengeUiPort",
                "mihon.desktop.ui.library.LibraryRootScreen -> mihon.desktop.download.DesktopDownloadQueuePort",
                "mihon.desktop.ui.settings.AboutScreen -> mihon.desktop.extension.DesktopExtensionPresentationService",
                "mihon.desktop.ui.settings.AdvancedSettingsScreen -> mihon.desktop.network.DesktopNetworkMaintenancePort",
                "mihon.desktop.ui.settings.MoreRootScreen -> mihon.desktop.download.DesktopDownloadQueuePort",
            ),
            moduleAudit.strings("requiredCompiledEdges"),
        )
        assertTrue(moduleAudit.getValue("missingRequiredEdges").jsonArray.isEmpty())
        assertEquals(
            emptyMap<String, Int>(),
            moduleAudit.getValue("forbiddenCompiledEdges").jsonArray
                .map { requiredText(it.jsonObject, "category", 95, "forbiddenCompiledEdges") }
                .groupingBy { it }
                .eachCount(),
        )

        val child = Files.readString(repositoryRoot.resolve(childPlan))
        val metadata = markdownFrontmatter(child)
        assertEquals("Task 16C", metadata["parent-task"])
        assertFalse("active-task" in metadata, "child progress must derive from its first unchecked checkbox")
        val overview =
            Regex("""(?m)^- \[([ xX])\] Task (\d+)[：:]""").findAll(child.substringBefore("### Task 164"))
                .associate { it.groupValues[2] to it.groupValues[1].lowercase() }
        assertEquals((164..169).map(Int::toString).toSet(), overview.keys)
        assertEquals("x", overview.getValue("164"))
        assertEquals("x", overview.getValue("165"))
        assertEquals("x", overview.getValue("166"))
        assertEquals("x", overview.getValue("167"))
        assertEquals("x", overview.getValue("168"))
        assertEquals(listOf("169"), overview.filterValues { it == " " }.keys.toList())
        assertEquals("planned", metadata["status"])
        (164..169).forEach { task ->
            assertEquals(1, Regex("""(?m)^### Task $task(?:\s|$)""").findAll(child).count(), "Task $task must be unique")
        }

        val parent = Files.readString(repositoryRoot.resolve("docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md"))
        assertTrue(markdownFrontmatter(parent)["active-task"] in setOf("Task 16D", "Task 17"))
        assertTrue(Regex("""(?m)^- \[x] Task 16C[：:]""").containsMatchIn(parent))
        assertTrue(Regex("""(?m)^- \[[x ]\] Task 16D[：:]""").containsMatchIn(parent))
    }

    @Tag("parity-governance")
    @Test
    fun `Task 16D binds exact Test Mode inventory gaps and handoff`() {
        val repositoryRoot = repositoryRoot()
        val id3 = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }.getValue(3).jsonObject
        statusDecisionForTask(id3, 3, "Task 14A")
        val decision = id3.getValue("statusDecision").jsonObject
        assertEquals("Task 16D", requiredText(decision, "task", 3))
        assertEquals("REMEDIATE", requiredText(decision, "decision", 3))
        val childPlan = "docs/superpowers/plans/2026-07-24-task-16d-test-mode-scenario-closure.md"
        val productPlan = "docs/superpowers/plans/2026-07-24-task-14-product-parity-closure.md"
        assertEquals("$productPlan#task-141-a1-id-3-shared-screen-state", requiredText(decision, "followUp", 3))
        assertEquals(setOf("$productPlan#task-141-a1-id-3-shared-screen-state", "$productPlan#task-142-a2-id-3-desktop-screen-state-consumer"), decision.getValue("productClosureFollowUps").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals("$childPlan#task-173-browse-search-and-source-login", requiredText(decision, "coverageFollowUp", 3))
        assertTrue(requiredText(decision, "gap", 3) != "NONE")

        val audit = id3.getValue("testModeCoverageAudit").jsonObject
        assertEquals("Task 16D", requiredText(audit, "task", 3))
        assertEquals("app-desktop/src/test/resources/parity/test-mode-coverage-inventory.json", requiredText(audit, "inventory", 3))
        assertEquals(listOf(13, 4, 9, 5, 64, 0), listOf("families", "coveredFamilies", "gapFamilies", "permanentProtections", "mappedCapabilities", "unmappedCapabilities").map { audit.getValue(it).jsonPrimitive.content.toInt() })
        assertEquals(childPlan, requiredText(audit, "childPlan", 3))

        val child = Files.readString(repositoryRoot.resolve(childPlan))
        val metadata = markdownFrontmatter(child)
        assertEquals("Task 16D", metadata["parent-task"])
        assertEquals("planned", metadata["status"])
        assertFalse("active-task" in metadata, "child progress must derive from its first unchecked checkbox")
        val overview = Regex("""(?m)^- \[ ] Task (17[1-7])[：:]""").findAll(child.substringBefore("### Task 171")).map { it.groupValues[1] }.toList()
        assertEquals((171..177).map { it.toString() }, overview)

        val parent = Files.readString(repositoryRoot.resolve("docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md"))
        assertEquals("Task 17", markdownFrontmatter(parent)["active-task"])
        assertTrue(Regex("""(?m)^- \[x] Task 16D[：:]""").containsMatchIn(parent))
        assertTrue(Regex("""(?m)^- \[ ] Task 17[：:]""").containsMatchIn(parent))
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
        val proposal = Files.readString(repositoryRoot.resolve("docs/superpowers/plans/2026-07-12-mihon-desktop-upstream-parity-roadmap-main-authority.md"))

        listOf("Android authoritative", "Android original", "Android 原版").forEach { ambiguousAuthority ->
            assertFalse(manifest.contains(ambiguousAuthority), "Active manifest must not use ambiguous authority term: $ambiguousAuthority")
            assertFalse(proposal.contains(ambiguousAuthority), "Completed source-extension plan must not use ambiguous authority term: $ambiguousAuthority")
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
                                    if (deviation.getValue("classification").jsonPrimitive.content == "MIGRATION_OUTPUT") {
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

        assertTrue(failure.message.orEmpty().contains("PRODUCT_GAP"), failure.message)
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

        assertEquals("VERIFIED", item.getValue("status").jsonPrimitive.content)
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
        assertEquals("VERIFIED", migration.getValue("status").jsonPrimitive.content)
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
    fun `reader parity entries report only evidenced states`() {
        val items = manifestItems(repositoryRoot()).associateBy { validatedId(it.jsonObject) }
        val expectedStatuses = mapOf(
            9 to "VERIFIED",
            43 to "VERIFIED",
            44 to "VERIFIED",
            45 to "VERIFIED",
            47 to "VERIFIED",
            49 to "VERIFIED",
            51 to "VERIFIED",
            54 to "WIRED",
        )

        expectedStatuses.forEach { (id, expectedStatus) ->
            val item = items.getValue(id).jsonObject
            assertEquals(expectedStatus, item.getValue("status").jsonPrimitive.content, "ID $id status")
            if (id in structuredProvenanceIds || item["roleEvidence"] != null) {
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
        Files.writeString(evidence, "| 84 | `PLATFORM-EXEMPT` | approved boundary |")
        val approval =
            buildJsonObject {
                put("approvalSource", "${evidence.fileName}:1")
                put("approvalDate", "2026-07-24")
                put("capabilityBoundary", "Synthetic platform boundary")
                put("userVisibleBoundary", "Synthetic unavailable state")
                put("failureFeedback", "synthetic_unavailable")
            }
        validateItem(syntheticItem(84, "EXEMPT", evidence.toString(), approval), tempDir)

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
    fun `platform capability candidates have fixed-main provenance and production evidence`() {
        val repositoryRoot = repositoryRoot()
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }

        platformCapabilityEvidenceIds.forEach { id ->
            val item = items.getValue(id).jsonObject
            assertEquals("CANDIDATE", requiredText(item, "status", id))
            assertEquals(fixedOriginalMihonRef, requiredText(item, "upstreamRef", id))
            val symbols = item.getValue("upstreamSymbols").jsonArray
            assertTrue(symbols.isNotEmpty(), "ID $id requires fixed-main path and symbol")
            symbols.forEach { symbol ->
                val path = requiredText(symbol.jsonObject, "path", id)
                assertTrue(path.endsWith(".kt"))
                assertTrue(path in platformCapabilityFixedMainPaths, "ID $id upstream path is not fixed-main verified: $path")
                requiredText(symbol.jsonObject, "symbol", id)
            }
            assertEquals(exactPlatformCapabilitySymbols.getValue(id), symbols.map { it.jsonObject.getValue("symbol").jsonPrimitive.content }.toSet())
            assertEquals(exactPlatformCapabilityUpstream.getValue(id), symbols.map { it.jsonObject.getValue("path").jsonPrimitive.content to it.jsonObject.getValue("symbol").jsonPrimitive.content }.toSet())
            assertEquals(exactPlatformCapabilityShared.getValue(id), item.getValue("sharedImplementationPaths").jsonArray.map { it.jsonPrimitive.content }.toSet())
            assertEquals(exactPlatformCapabilityAndroid.getValue(id), item.getValue("currentAndroidConsumerPaths").jsonArray.map { it.jsonPrimitive.content }.toSet())
            listOf("sharedImplementationPaths", "currentAndroidConsumerPaths", "desktopConsumerAdapterPaths", "protectionTests")
                .forEach { field ->
                    val paths = item.getValue(field).jsonArray
                    assertTrue(paths.isNotEmpty(), "ID $id requires $field")
                    paths.forEach { path -> assertTrue(Files.isRegularFile(repositoryRoot.resolve(path.jsonPrimitive.content)), "ID $id missing $field path") }
                }
            assertEquals(
                exactPlatformCapabilityConsumers.getValue(id),
                item.getValue("desktopConsumerAdapterPaths").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                "ID $id must retain exact Desktop production consumers",
            )
            assertEquals(
                exactPlatformCapabilityProtectionPaths.getValue(id),
                item.getValue("protectionTests").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                "ID $id must retain exact production protection tests",
            )
            assertBehaviorEvidence(id, repositoryRoot)
            val deviations = item.getValue("deviations").jsonArray
            assertTrue(deviations.isNotEmpty(), "ID $id requires an honest deviation")
            assertTrue(requiredText(item, "verificationScope", id).contains("CANDIDATE"), "ID $id must remain limited until OS acceptance")
        }
        val widget = items.getValue(85).jsonObject
        assertEquals("EXEMPT", requiredText(widget, "status", 85))
        assertExactPaths(widget, "sharedImplementationPaths", 85, exactPlatformCapabilityShared.getValue(85))
        assertExactPaths(widget, "currentAndroidConsumerPaths", 85, exactPlatformCapabilityAndroid.getValue(85))
        assertExactPaths(widget, "desktopConsumerAdapterPaths", 85, exactPlatformCapabilityConsumers.getValue(85))
        assertExactPaths(widget, "protectionTests", 85, exactPlatformCapabilityProtectionPaths.getValue(85))
        assertEquals(
            "app-desktop/src/test/kotlin/mihon/desktop/parity/WidgetPrivacyBoundaryTest.kt",
            requiredText(widget, "platformExemptionEvidence", 85),
        )
        assertBehaviorEvidence(85, repositoryRoot)
        val update = items.getValue(86).jsonObject
        assertTrue(requiredText(update, "fixedMainSemantics", 86).contains("throttle"))
        assertTrue(requiredText(update, "fixedMainSemantics", 86).contains("version"))
        assertTrue(requiredText(update, "fixedMainSemantics", 86).contains("visible"))
        assertTrue(requiredText(update, "desktopSecurityHardening", 86).contains("checksum"))
        assertTrue(requiredText(update, "desktopSecurityHardening", 86).contains("signature"))
        assertTrue(requiredText(update, "desktopSecurityHardening", 86).contains("size"))
        assertTrue(requiredText(update, "desktopSecurityHardening", 86).contains("redirect"))
        assertTrue(requiredText(update, "desktopSecurityHardening", 86).contains("not fixed-main provenance"))
        val privacy = items.getValue(92).jsonObject.getValue("privacyCapabilities").jsonObject
        assertEquals(setOf("appLock", "delay", "screenPrivacy", "nativeNotificationContent", "telemetry"), privacy.keys)
        privacy.forEach { (name, value) ->
            val capability = value.jsonObject
            assertTrue(requiredText(capability, "androidConsumer", 92, name).isNotBlank())
            assertTrue(requiredText(capability, "desktopConsumer", 92, name).isNotBlank())
            val expectedStatus = if (name in setOf("nativeNotificationContent", "telemetry")) "UNSUPPORTED" else "CANDIDATE"
            assertEquals(expectedStatus, requiredText(capability, "status", 92, name))
            if (expectedStatus == "UNSUPPORTED") assertEquals("NONE", requiredText(capability, "desktopConsumer", 92, name))
            requiredText(capability, "reason", 92, name); requiredText(capability, "uiBehavior", 92, name)
        }
    }

    @Test
    fun `settings capabilities bind exact fixed-main ownership and production behavior methods`() {
        val repositoryRoot = repositoryRoot()
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }
        assertSettingsInventory(fixedMainPathInventory(repositoryRoot))
        settingsParityIds.forEach { id -> assertSettingsCapability(id, items.getValue(id).jsonObject, repositoryRoot) }
    }

    @Test
    fun `settings capability contract rejects cross id authority method protection and inventory ownership`() {
        val repositoryRoot = repositoryRoot()
        val items = manifestItems(repositoryRoot).associateBy { validatedId(it.jsonObject) }
        val accessibility = items.getValue(88).jsonObject
        val search = items.getValue(90).jsonObject
        listOf("upstreamSymbols", "behaviorMethods", "protectionTests").forEach { field ->
            val mutation = JsonObject(accessibility.toMutableMap().apply { put(field, search.getValue(field)) })
            assertThrows(AssertionError::class.java) { assertSettingsCapability(88, mutation, repositoryRoot) }
        }
        val deletedAuthority = JsonObject(search.toMutableMap().apply { put("upstreamSymbols", buildJsonArray {}) })
        assertThrows(AssertionError::class.java) { assertSettingsCapability(90, deletedAuthority, repositoryRoot) }
        val licenses = items.getValue(94).jsonObject
        val androidBehaviorPath = "app/src/test/java/eu/kanade/presentation/more/settings/screen/about/OpenSourceLicensesConsumerBehaviorTest.kt"
        listOf("behaviorMethods", "protectionTests").forEach { field ->
            val deleted = JsonObject(
                licenses.toMutableMap().apply {
                    put(
                        field,
                        if (field == "behaviorMethods") {
                            JsonObject(getValue(field).jsonObject.toMutableMap().apply { remove(androidBehaviorPath) })
                        } else {
                            buildJsonArray {
                                getValue(field).jsonArray.forEach {
                                    if (it.jsonPrimitive.content != androidBehaviorPath) add(it)
                                }
                            }
                        },
                    )
                },
            )
            assertThrows(AssertionError::class.java) { assertSettingsCapability(94, deleted, repositoryRoot) }
        }
        val exchangedMethods = JsonObject(
            licenses.toMutableMap().apply {
                put(
                    "behaviorMethods",
                    JsonObject(getValue("behaviorMethods").jsonObject.toMutableMap().apply {
                        put(
                            androidBehaviorPath,
                            search.getValue("behaviorMethods").jsonObject.getValue(
                                "domain/src/commonTest/kotlin/mihon/domain/settings/SettingsSearchPolicyTest.kt",
                            ),
                        )
                    }),
                )
            },
        )
        assertThrows(AssertionError::class.java) { assertSettingsCapability(94, exchangedMethods, repositoryRoot) }
        val inventory = Files.readString(repositoryRoot.resolve(fixedMainPathInventoryResource))
            .replaceFirst("\"capabilityIds\": [88]", "\"capabilityIds\": [90]")
        assertThrows(AssertionError::class.java) {
            fixedMainPathInventory(Json.parseToJsonElement(inventory).jsonObject)
        }
        val exactInventory = fixedMainPathInventory(repositoryRoot)
        val searchPath = exactSettingsUpstream.getValue(90).first().first
        assertThrows(AssertionError::class.java) { assertSettingsInventory(exactInventory - searchPath) }
        val wrongBlob = exactInventory + (searchPath to exactAuthorityBlobIds.getValue(exactSettingsUpstream.getValue(91).first().first))
        assertThrows(AssertionError::class.java) { assertSettingsInventory(wrongBlob) }
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

    @Test
    fun `behavior parser rejects declarations that exist only in comments or strings`() {
        listOf(
            """class Evidence { // fun `target behavior`() { assertTrue(productionMarker) }
            }""",
            """class Evidence { val decoy = "fun `target behavior`() { assertTrue(productionMarker) }" }""",
        ).forEach { source ->
            assertThrows(AssertionError::class.java) {
                kotlinTestMethod(source, "target behavior", "synthetic evidence")
            }
        }
    }

    @Test
    fun `behavior parser requires one direct test class member`() {
        val invalidSources = listOf(
            """class Evidence { class Nested { @Test fun `target behavior`() { assertTrue(true) } } }""",
            """class Evidence { @Test fun wrapper() { @Test fun `target behavior`() { assertTrue(true) } } }""",
            """class First { @Test fun `target behavior`() { assertTrue(true) } }
                class Second { @Test fun `target behavior`() { assertTrue(true) } }""",
        )
        invalidSources.forEach { source ->
            assertThrows(AssertionError::class.java) {
                kotlinTestMethod(source, "target behavior", "synthetic evidence")
            }
        }
    }

    @Test
    fun `behavior parser removes comment-only assertions and markers`() {
        val source = """
            class Evidence {
                @Test
                fun `target behavior`() {
                    // assertTrue(productionMarker)
                    /* assertEquals(productionMarker, actual) */
                }
            }
        """.trimIndent()

        assertThrows(AssertionError::class.java) {
            val method = kotlinTestMethod(source, "target behavior", "synthetic evidence")
            assertTrue("assert" in method)
            assertTrue("productionMarker" in method)
        }
    }

    @Test
    fun `behavior parser does not borrow a later member block for an expression body`() {
        val source = """
            class Evidence {
                @Test fun `target behavior`() = Unit

                @Test fun decoy() {
                    assertTrue(productionMarker())
                }

                private fun productionMarker() = true
            }
        """.trimIndent()

        assertThrows(AssertionError::class.java) {
            val method = kotlinTestMethod(source, "target behavior", "synthetic evidence")
            assertTrue("assert" in method)
            assertTrue("productionMarker" in method)
        }
    }

    @Test
    fun `behavior parser does not borrow init or constructor blocks from coroutine expressions`() {
        val sources = listOf(
            """
                annotation class Test
                fun assertTrue(value: Boolean) = check(value)
                fun runBlocking(block: () -> Unit) = block()
                class Evidence {
                    private val noOp: () -> Unit = {}
                    @Test fun `target behavior`() = runBlocking(block = noOp)
                    init { assertTrue(productionMarker()) }
                    private fun productionMarker() = true
                }
            """.trimIndent(),
            """
                annotation class Test
                fun assertTrue(value: Boolean) = check(value)
                fun runTest(value: Unit) = value
                class Evidence {
                    @Test fun `target behavior`() = runTest(Unit)
                    constructor(ignored: Boolean) { assertTrue(productionMarker()) }
                    private fun productionMarker() = true
                }
            """.trimIndent(),
        )

        val borrowed = sources.mapNotNull { source ->
            runCatching { kotlinTestMethod(source, "target behavior", "synthetic evidence") }.getOrNull()
        }
        assertTrue(borrowed.none { "assert" in it && "productionMarker" in it })
    }

    private fun validateItem(item: JsonObject, repositoryRoot: Path) {
        val id = validatedId(item)
        statusDecisionRecords(item, id)
        val status = item["status"]?.jsonPrimitive?.content
        assertTrue(status in validStatuses, "ID $id: status must be one of $validStatuses")
        val exemptionEvidence = item["platformExemptionEvidence"]?.jsonPrimitive?.content
        if (status == "EXEMPT") {
            assertTrue(exemptionEvidence != null && exemptionEvidence != "NONE", "ID $id: EXEMPT requires real platform evidence")
            assertTrue(Files.isRegularFile(repositoryRoot.resolve(exemptionEvidence!!)), "ID $id: missing exemption evidence $exemptionEvidence")
            val approval =
                item["exemptionApproval"]?.jsonObject
                    ?: throw AssertionError("ID $id: EXEMPT requires traceable approval")
            val approvalSource = requiredText(approval, "approvalSource", id, "exemptionApproval")
            val sourcePath = approvalSource.substringBeforeLast(':', "")
            val sourceLine = approvalSource.substringAfterLast(':', "").toIntOrNull()
            assertTrue(sourcePath.isNotBlank() && sourceLine != null && sourceLine > 0, "ID $id: approvalSource must be path:line")
            val approvalLines = Files.readAllLines(repositoryRoot.resolve(sourcePath))
            assertTrue(sourceLine!! <= approvalLines.size, "ID $id: approvalSource line is outside $sourcePath")
            assertTrue(
                approvalLines[sourceLine - 1].contains("| $id |") &&
                    approvalLines[sourceLine - 1].contains("PLATFORM-EXEMPT"),
                "ID $id: approvalSource must identify the approved platform exemption",
            )
            assertTrue(
                requiredText(approval, "approvalDate", id, "exemptionApproval").matches(Regex("\\d{4}-\\d{2}-\\d{2}")),
                "ID $id: approvalDate must be YYYY-MM-DD",
            )
            listOf("capabilityBoundary", "userVisibleBoundary", "failureFeedback")
                .forEach { requiredText(approval, it, id, "exemptionApproval") }
        } else {
            assertEquals("NONE", exemptionEvidence, "ID $id: non-EXEMPT evidence must be NONE")
        }
    }

    private fun validateRoleEvidence(
        item: JsonObject,
        repositoryRoot: Path,
        fixedMainPathInventory: Map<String, String>,
    ) {
        val id = validatedId(item)
        val evidenceByRole =
            item["roleEvidence"]?.jsonObject
                ?: throw AssertionError("ID $id: terminal status requires roleEvidence")
        assertTrue(
            evidenceByRole.keys.containsAll(requiredTerminalEvidenceRoles),
            "ID $id: terminal roleEvidence must include $requiredTerminalEvidenceRoles",
        )
        val upstreamPaths = item.getValue("upstreamSymbols").jsonArray.map { it.jsonObject.getValue("path").jsonPrimitive.content }.toSet()
        val currentPaths =
            mapOf(
                "CURRENT_ANDROID" to item.getValue("currentAndroidConsumerPaths").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                "SHARED_OR_ADAPTER" to item.getValue("sharedImplementationPaths").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                "DESKTOP_CONSUMER" to item.getValue("desktopConsumerAdapterPaths").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            )
        val protectionTests = item.getValue("protectionTests").jsonArray.map { it.jsonPrimitive.content }.toSet()

        requiredTerminalEvidenceRoles.forEach { role ->
            val entries = evidenceByRole.getValue(role).jsonArray
            assertTrue(entries.isNotEmpty(), "ID $id: terminal role $role must have evidence")
            entries.forEachIndexed { index, element ->
                val entry = element.jsonObject
                if (role == "FIXTURE") {
                    val artifact = requiredText(entry, "artifact", id, "roleEvidence.$role[$index]")
                    val (path, method) = artifact.split('#', limit = 2).takeIf { it.size == 2 }
                        ?: throw AssertionError("ID $id: FIXTURE artifact must be path#test-method")
                    assertTrue(path in protectionTests, "ID $id: FIXTURE artifact is not declared protection evidence: $path")
                    val methodSource =
                        kotlinTestMethod(Files.readString(repositoryRoot.resolve(path)), method, "ID $id FIXTURE artifact $artifact")
                    assertTrue(
                        listOf("assert", " shouldBe ", ".shouldContain").any(methodSource::contains),
                        "ID $id: FIXTURE artifact must execute assertions",
                    )
                    val productionMarkers = entry["productionMarkers"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                    assertTrue(productionMarkers.isNotEmpty(), "ID $id: FIXTURE artifact requires productionMarkers")
                    productionMarkers.forEach { marker ->
                        assertTrue(marker in methodSource, "ID $id: FIXTURE artifact must execute production marker $marker")
                    }
                } else {
                    val path = requiredText(entry, "path", id, "roleEvidence.$role[$index]")
                    val symbol = requiredText(entry, "symbol", id, "roleEvidence.$role[$index]")
                    val line = requiredText(entry, "line", id, "roleEvidence.$role[$index]").toIntOrNull()
                    assertTrue(line != null && line > 0, "ID $id: $role evidence line must be a positive integer")
                    if (role == "FIXED_ORIGINAL") {
                        assertEquals(fixedOriginalMihonRef, requiredText(entry, "ref", id, "roleEvidence.$role[$index]"))
                        assertTrue(path in upstreamPaths, "ID $id: fixed-original evidence must use a declared upstream path")
                        assertTrue(path in fixedMainPathInventory, "ID $id: fixed-original evidence must use the fixed-main inventory")
                        assertEquals(
                            fixedMainBlobId(repositoryRoot, path),
                            fixedMainPathInventory.getValue(path),
                            "ID $id: fixed-main inventory blob must match the fixed ref path",
                        )
                        val lines = fixedMainBlobLines(repositoryRoot, fixedMainPathInventory.getValue(path))
                        assertTrue(line!! <= lines.size, "ID $id: FIXED_ORIGINAL evidence line $line is outside fixed blob $path")
                        assertTrue(lines[line - 1].contains(symbol), "ID $id: FIXED_ORIGINAL symbol is not present in fixed blob $path:$line")
                    } else {
                        val allowedPaths =
                            if (role == "SHARED_OR_ADAPTER") {
                                when (requiredText(entry, "kind", id, "roleEvidence.$role[$index]")) {
                                    "SHARED" -> currentPaths.getValue(role)
                                    "PLATFORM_ADAPTER" -> {
                                        val tags = item.getValue("tags").jsonArray.map { it.jsonPrimitive.content }
                                        assertTrue("PLATFORM-ADAPTER" in tags, "ID $id: adapter evidence requires PLATFORM-ADAPTER")
                                        currentPaths.getValue("CURRENT_ANDROID") + currentPaths.getValue("DESKTOP_CONSUMER")
                                    }
                                    else -> throw AssertionError("ID $id: SHARED_OR_ADAPTER kind must be SHARED or PLATFORM_ADAPTER")
                                }
                            } else {
                                currentPaths.getValue(role)
                            }
                        assertTrue(path in allowedPaths, "ID $id: $role evidence must use its declared current path")
                        val lines = Files.readAllLines(repositoryRoot.resolve(path))
                        assertTrue(line!! <= lines.size, "ID $id: $role evidence line $line is outside $path")
                        assertTrue(lines[line - 1].contains(symbol), "ID $id: $role evidence symbol is not present at $path:$line")
                    }
                }
            }
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

    private fun syntheticItem(
        id: Int,
        status: String = "NOT_STARTED",
        evidence: String = "NONE",
        exemptionApproval: JsonObject? = null,
    ) =
        buildJsonObject {
            put("id", id)
            put("status", status)
            put("platformExemptionEvidence", evidence)
            exemptionApproval?.let { put("exemptionApproval", it) }
        }

    private fun fixedMainBlobLines(repositoryRoot: Path, blobId: String): List<String> {
        val process =
            ProcessBuilder("git", "cat-file", "blob", blobId)
                .directory(repositoryRoot.toFile())
                .redirectErrorStream(true)
                .start()
        val lines = process.inputStream.bufferedReader().readLines()
        assertEquals(0, process.waitFor(), "Unable to read fixed-main blob $blobId: ${lines.joinToString("\n")}")
        return lines
    }

    private fun fixedMainBlobId(repositoryRoot: Path, path: String): String {
        val revision = "${fixedOriginalMihonRef.substringAfter('@')}:$path"
        val process =
            ProcessBuilder("git", "rev-parse", revision)
                .directory(repositoryRoot.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        assertEquals(0, process.waitFor(), "Unable to resolve fixed-main path $revision: $output")
        return output
    }

    private fun kotlinTestMethod(
        source: String,
        methodName: String,
        context: String,
    ): String {
        val (structural, commentFree) = kotlinSourceViews(source)
        val methods = Regex("@(?:Test|ParameterizedTest)\\b").findAll(structural).mapNotNull { annotation ->
            var cursor = annotation.range.first
            while (cursor < structural.length && structural[cursor] == '@') {
                cursor++
                while (cursor < structural.length && (structural[cursor].isLetterOrDigit() || structural[cursor] in "._:")) cursor++
                while (cursor < structural.length && structural[cursor].isWhitespace()) cursor++
                if (cursor < structural.length && structural[cursor] == '(') {
                    var depth = 1
                    cursor++
                    while (cursor < structural.length && depth > 0) {
                        if (structural[cursor] == '(') depth++ else if (structural[cursor] == ')') depth--
                        cursor++
                    }
                }
                while (cursor < structural.length && structural[cursor].isWhitespace()) cursor++
            }
            val modifier = Regex("(?:public|private|protected|internal|suspend|inline|operator|tailrec|open|final|override)\\b\\s*")
            while (true) {
                val match = modifier.find(structural, cursor)?.takeIf { it.range.first == cursor } ?: break
                cursor = match.range.last + 1
            }
            if (!structural.startsWith("fun", cursor) || structural.getOrNull(cursor + 3)?.isLetterOrDigit() == true) return@mapNotNull null
            val funStart = cursor
            cursor += 3
            while (cursor < structural.length && structural[cursor].isWhitespace()) cursor++
            if (structural.getOrNull(cursor) != '`') return@mapNotNull null
            val nameEnd = structural.indexOf('`', cursor + 1)
            if (nameEnd < 0 || structural.substring(cursor + 1, nameEnd) != methodName) return@mapNotNull null
            val memberDepth = structural.take(funStart).fold(0) { depth, char -> depth + if (char == '{') 1 else if (char == '}') -1 else 0 }
            if (memberDepth != 1) return@mapNotNull null
            var parentheses = 0
            var bodyStart: Int? = null
            var scan = nameEnd + 1
            val memberKeywords = listOf("fun", "val", "var", "class", "object", "interface", "init", "constructor")
            while (scan < structural.length && bodyStart == null) {
                when (structural[scan]) {
                    '(' -> parentheses++
                    ')' -> parentheses--
                }
                val nextMember = memberKeywords.any { keyword ->
                    structural.startsWith(keyword, scan) && structural.getOrNull(scan - 1)?.let { !it.isLetterOrDigit() && it != '_' } != false &&
                        structural.getOrNull(scan + keyword.length)?.let { !it.isLetterOrDigit() && it != '_' } != false
                }
                val coroutineBody = if (parentheses == 0 && structural[scan] == '=') {
                    val expression = structural.substring(scan + 1).trimStart()
                    listOf("runTest", "runBlocking").any { expression.startsWith(it) && expression.getOrNull(it.length)?.let { next -> !next.isLetterOrDigit() && next != '_' } != false }
                } else {
                    false
                }
                if (parentheses == 0 && ((structural[scan] == '=' && !coroutineBody) || structural[scan] == '}' || structural[scan] == '@' || nextMember)) break
                if (parentheses == 0 && structural[scan] == '{') bodyStart = scan
                scan++
            }
            bodyStart ?: return@mapNotNull null
            var bodyDepth = 1
            var bodyEnd = bodyStart + 1
            while (bodyEnd < structural.length && bodyDepth > 0) {
                if (structural[bodyEnd] == '{') bodyDepth++ else if (structural[bodyEnd] == '}') bodyDepth--
                bodyEnd++
            }
            if (bodyDepth == 0) commentFree.substring(funStart, bodyEnd) else null
        }.toList()
        assertEquals(1, methods.size, "$context must contain one direct class-member behavior test `$methodName`")
        return methods.single()
    }

    private fun markdownFrontmatter(source: String): Map<String, String> {
        val lines = source.lineSequence().toList()
        require(lines.firstOrNull() == "---") { "Markdown frontmatter must start at the first line" }
        val fields = lines.drop(1).takeWhile { it != "---" }
        require(lines.getOrNull(fields.size + 1) == "---") { "Markdown frontmatter must have a closing delimiter" }
        return fields.associate { line ->
            val separator = line.indexOf(':')
            require(separator > 0) { "Invalid frontmatter field: $line" }
            line.take(separator).trim() to line.drop(separator + 1).trim()
        }
    }

    private fun kotlinSourceViews(source: String): Pair<String, String> {
        val structural = source.toCharArray()
        val commentFree = source.toCharArray()
        var index = 0
        var state = 0
        var blockDepth = 0
        fun blank(target: CharArray, start: Int, count: Int) = repeat(count) { offset ->
            if (target[start + offset] !in "\r\n") target[start + offset] = ' '
        }
        while (index < source.length) {
            val previousState = state
            val width = when {
                state == 0 && source.startsWith("//", index) -> 2.also { state = 1 }
                state == 0 && source.startsWith("/*", index) -> 2.also { state = 2; blockDepth = 1 }
                state == 0 && source.startsWith("\"\"\"", index) -> 3.also { state = 3 }
                state == 0 && source[index] == '"' -> 1.also { state = 4 }
                state == 0 && source[index] == '\'' -> 1.also { state = 5 }
                state == 1 && source[index] in "\r\n" -> 1.also { state = 0 }
                state == 2 && source.startsWith("/*", index) -> 2.also { blockDepth++ }
                state == 2 && source.startsWith("*/", index) -> 2.also { if (--blockDepth == 0) state = 0 }
                state == 3 && source.startsWith("\"\"\"", index) -> 3.also { state = 0 }
                state in 4..5 && source[index] == '\\' && index + 1 < source.length -> 2
                state == 4 && source[index] == '"' -> 1.also { state = 0 }
                state == 5 && source[index] == '\'' -> 1.also { state = 0 }
                else -> 1
            }
            if (previousState != 0 || state != 0 || width > 1) blank(structural, index, width)
            if (previousState in 1..2 || state in 1..2) blank(commentFree, index, width)
            index += width
        }
        return structural.concatToString() to commentFree.concatToString()
    }

    private fun assertBehaviorEvidence(id: Int, repositoryRoot: Path) {
        exactPlatformCapabilityProtection.getValue(id).forEach { (testPath, methods) ->
            val source = Files.readString(repositoryRoot.resolve(testPath))
            methods.forEach { (methodName, markers) ->
                val method = kotlinTestMethod(source, methodName, "ID $id protection in $testPath")
                assertTrue(method.contains("assert"), "ID $id protection method must execute assertions: $methodName")
                markers.forEach { marker ->
                    assertTrue(method.contains(marker), "ID $id protection method `$methodName` must execute production marker `$marker`")
                }
            }
        }
    }

    private fun assertSettingsCapability(id: Int, item: JsonObject, repositoryRoot: Path) {
        assertEquals(exactSettingsStatuses.getValue(id), requiredText(item, "status", id))
        assertEquals(fixedOriginalMihonRef, requiredText(item, "upstreamRef", id))
        assertTrue(requiredText(item, "authoritativeImplementation", id).startsWith("Fixed-main original Mihon capability #$id:"))
        val upstream = item.getValue("upstreamSymbols").jsonArray.map {
            it.jsonObject.let { symbol -> symbol.getValue("path").jsonPrimitive.content to symbol.getValue("symbol").jsonPrimitive.content }
        }.toSet()
        assertEquals(exactSettingsUpstream.getValue(id), upstream, "ID $id fixed-main path/symbol ownership")
        assertExactPaths(item, "sharedImplementationPaths", id, exactSettingsShared.getValue(id))
        assertExactPaths(item, "currentAndroidConsumerPaths", id, exactSettingsAndroid.getValue(id))
        assertExactPaths(item, "desktopConsumerAdapterPaths", id, exactSettingsDesktop.getValue(id))
        assertExactPaths(item, "protectionTests", id, exactSettingsBehavior.getValue(id).keys)
        val methods = item.getValue("behaviorMethods").jsonObject.mapValues { (_, value) ->
            value.jsonArray.map { it.jsonPrimitive.content }.toSet()
        }
        assertEquals(exactSettingsBehavior.getValue(id), methods, "ID $id behavior method binding")
        listOf("sharedImplementationPaths", "currentAndroidConsumerPaths", "desktopConsumerAdapterPaths", "protectionTests")
            .flatMap { field -> item.getValue(field).jsonArray.map { it.jsonPrimitive.content } }
            .forEach { path -> assertTrue(Files.isRegularFile(repositoryRoot.resolve(path)), "ID $id missing production evidence $path") }
        assertTrue(requiredText(item, "verificationScope", id).contains(exactSettingsStatuses.getValue(id)))
        if (id == 88) assertEquals("false", item.getValue("dedicatedScreenPresent").jsonPrimitive.content)
    }

    private fun assertSettingsInventory(inventory: Map<String, String>) {
        exactSettingsUpstream.values.flatten().forEach { (path, _) ->
            assertEquals(exactAuthorityBlobIds.getValue(path), inventory[path], "Settings fixed-main inventory has the wrong or missing blob for $path")
        }
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
                (path.endsWith(".kt") || path.endsWith(".kts")) && !Path.of(path).isAbsolute && path.split('/').none { it == ".." },
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
                88 -> setOf("presentation-core/src/main/")
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

    private fun statusDecisionRecords(item: JsonObject, id: Int): List<JsonObject> {
        assertFalse("task14StatusDecision" in item, "ID $id must not have a second task decision authority")
        val records =
            listOfNotNull(item["statusDecision"]?.jsonObject) +
                item["statusDecisionHistory"]?.jsonArray.orEmpty().map { it.jsonObject }
        val tasks = records.map { requiredText(it, "task", id, "statusDecision") }
        assertEquals(tasks.size, tasks.toSet().size, "ID $id must not repeat a task across current decision and history")
        return records
    }

    private fun statusDecisionTasks(item: JsonObject, id: Int) =
        statusDecisionRecords(item, id).map { requiredText(it, "task", id, "statusDecision") }.toSet()

    private fun statusDecisionForTask(item: JsonObject, id: Int, task: String) =
        statusDecisionRecords(item, id).singleOrNull { requiredText(it, "task", id, "statusDecision") == task }
            ?: throw AssertionError("ID $id must have exactly one status decision for $task")

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
                exactSettingsOwners[path]?.let { expected ->
                    val owners = entry["capabilityIds"]?.jsonArray?.map { it.jsonPrimitive.content.toInt() }.orEmpty().toSet()
                    assertEquals(setOf(expected), owners.intersect(settingsParityIds), "Fixed-main inventory owner for $path")
                }
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

    private fun duplicateJsonPropertyNames(source: String): List<String> {
        val scopes = mutableListOf<MutableSet<String>?>()
        val duplicates = mutableListOf<String>()
        var index = 0
        while (index < source.length) {
            when (source[index]) {
                '{' -> scopes.add(mutableSetOf())
                '[' -> scopes.add(null)
                '}', ']' -> if (scopes.isNotEmpty()) scopes.removeAt(scopes.lastIndex)
                '"' -> {
                    val start = ++index
                    while (index < source.length && source[index] != '"') {
                        index += if (source[index] == '\\') 2 else 1
                    }
                    var next = index + 1
                    while (next < source.length && source[next].isWhitespace()) next++
                    val scope = scopes.lastOrNull()
                    if (next < source.length && source[next] == ':' && scope != null) {
                        val name = source.substring(start, index)
                        if (!scope.add(name)) duplicates += name
                    }
                }
            }
            index++
        }
        return duplicates
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
