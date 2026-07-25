---
parent-plan: docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md
parent-task: Task 14
task-base: 0c6d360441c6ba64613063db7b197c0f88fa3d08
original-ref: main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8
status: planned
---

# Task 14 产品 parity 缺口收口计划

## 固定边界与执行顺序

本计划只关闭 Task 14A 已分类的 ID 3、32、69、70、87 产品缺口。固定原版仅为 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`。shared Task 先固定领域语义及一个平台 consumer，后续 Task 再接另一平台；单 Task 不得同时修改 Android 与 Desktop consumer。

- 顺序为 A1 → A2 → A3 → A4 → B1 → B2 → B3 → B4 → C1；后项可复用前项已提交的 shared contract。
- 不创建第二套 repository、tracker 或 preference 链；优先扩展现有 domain service/interactor。
- Desktop credential、session isolation、restart checkpoint、test-mode feedback 与现有 provider 必须零回退。
- 任一 Task 超过文件或行数预算时停止并修订本计划，不得把相邻 Task 静默并入。
- 每个 Task 独立执行红绿重构、mutation、focused tests、独立审查与精确提交；父 Task 14C 最后回收 manifest 状态。

## Task 总览

- [x] Task 141：A1 ID 3 Android shared screen state
- [x] Task 142：A2 ID 3 Desktop screen state consumer
- [x] Task 143：A3 ID 32 Android extension repository wiring
- [x] Task 144：A4 ID 32 Desktop extension repository wiring
- [ ] Task 145：B1 ID 69 Android provider-neutral core
- [ ] Task 146：B2 ID 69 Desktop provider adapters
- [ ] Task 147：B3 ID 70 Android delayed tracker sync
- [ ] Task 148：B4 ID 70 Desktop delayed sync consumer
- [ ] Task 149：C1 ID 87 Desktop language

### Task 141 A1 ID 3 shared screen state

**Risk axis:** android-source-state
**Platform boundary:** shared+android
**Estimated scope:** 5 files, 340 lines
**Scope correction:** 仅新增本进度计划持久化文件，产品与测试边界仍为原 4 文件。

**Files:**
- Create: `domain/src/commonMain/kotlin/mihon/domain/source/model/SourceScreenState.kt`
- Create: `domain/src/commonTest/kotlin/mihon/domain/source/model/SourceScreenStateTest.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/SourcesScreenModel.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/ui/browse/source/SourcesScreenModelSharedStateTest.kt`

**User entry:** Android Browse → Sources。
**Feedback:** loading、content、empty、failure 与一次性禁用/置顶结果保持可见，失败可重试。
**RED:** 先写 shared 状态转换与 Android production consumer 测试；绕过 reducer 时失败。
**GREEN:** 抽 immutable state/event reducer，Android 保留 Voyager 生命周期并消费 shared 输出。
**Mutation:** 断开 reducer 或重复消费 event，确认对应测试精确变红后恢复。
**Verification:** domain state test、Android consumer test、父 parity contract、Spotless。
**Desktop zero-regression:** 本 Task 不改 Desktop；A2 必须保留现有最近使用、置顶、语言和本地源行为。
**Execution evidence（已完成）：** fixed original 仅为 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`；shared reducer 统一 loading/content/empty/retryable failure 与 disabled/pinned/retryable failure 一次性事件，Android production `SourcesScreenModel` 保留 Voyager lifecycle，并从 reducer 的 domain `Source` 输出生成原有 last-used/pinned/lang/local-source UI 分组。原始 domain 测试精确编译 RED 于全部 shared 类型缺失；Android 首次可运行失败只是 IO dispatcher 时序，不计 wiring RED。随后断开 production reducer 精确 timeout RED，重复消费 mutation 精确 RED 于 pending event 未清；repair 再以错误 ID 仍清事件 mutation 精确 RED 于 state identity，并以 disable 参数 `false→true` mutation 精确 RED 于 `ActionFailed/Disabled` 类型。恢复后 domain `3/3`、Android production `2/2` GREEN。
**Runtime evidence：** `assembleDebug` GREEN；x86_64 APK 在 Android 16 `emulator-5556` 安装成功，`aapt` 与 PackageManager 均列出 `MainActivity` launcher，但 runtime resolver 返回 `No activity found`、显式启动返回 `Error type 3`，因此未虚报 Browse→Sources UI 通过，交 Task142 前保留该环境验收缺口。

### Task 142 A2 ID 3 Desktop screen state consumer

**Risk axis:** desktop-source-state
**Platform boundary:** shared+desktop
**Estimated scope:** 8 files, 400 lines

**Files:**
- Modify: `domain/src/commonMain/kotlin/mihon/domain/source/model/SourceScreenState.kt`
- Modify: `domain/src/commonTest/kotlin/mihon/domain/source/model/SourceScreenStateTest.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/ui/browse/DesktopSourcesScreenModel.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/ui/browse/DesktopSourcesScreenModelTest.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/browse/BrowseTab.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/browse/DesktopSourceListProjectorTest.kt`

**User entry:** Desktop Browse → Sources。
**Feedback:** 与 A1 相同的状态和动作结果由真实 `BrowseTab` 渲染。
**RED:** 先写 Desktop model 与 UI projector consumer 测试；未接 shared reducer 时失败。
**GREEN:** Desktop model 复用 A1 reducer，`BrowseTab` 只收集和渲染平台状态。
**Mutation:** 断开 model 注入或 event 消费，确认 model/UI 测试失败后恢复。
**Verification:** shared state、Desktop model/projector、父 parity contract、Spotless。
**Desktop zero-regression:** 最近使用、置顶、语言分组、禁用源、本地源入口与排序完全保留。

**Execution evidence（已完成）：** Desktop production `DesktopSourcesScreenModel` 直接收集真实 `SourceManager.catalogueSources` 与 language/disabled/pinned/last-used preferences，复用 A1 `SourceScreenReducer` 生成 loading/content/empty/retryable failure 和 pin 一次性结果；`BrowseSourceListScreen` 仅收集 model state，经 shared-aware projector 保留 last-used→pinned→language 排序、disabled/language 过滤、本地源、源选择与搜索，并以稳定 Snackbar 反馈后消费 event。RED 精确编译失败于 model/projector shared consumer 缺失，接线前 mounted Screen 精确 timeout RED 于 pin feedback；event-consume no-op mutation 精确 RED 于 pending `Pinned` 未清，恢复后 model `2/2`、projector `3/3`、mounted wiring `1/1` GREEN。修复验证用真实已移除 Preferences 节点触发首次 pin 读取失败，model 断言 `ActionFailed(PIN, "source pin failed")`，mounted Screen 断言同文案 Snackbar；删除 `onFailure` 时两项精确 RED，恢复后 `2/2` GREEN。
### Task 143 A3 ID 32 Android extension repository wiring

**Risk axis:** android-extension-repo-crud
**Platform boundary:** shared+android
**Estimated scope:** 5 files, 340 lines
**Scope correction:** 第 5 个文件仅为本进度计划持久化；产品与测试范围仍是原定 4 文件。

**Files:**
- Modify: `domain/src/commonMain/kotlin/mihon/domain/extensionrepo/service/ExtensionRepoService.kt`
- Create: `domain/src/commonTest/kotlin/mihon/domain/extensionrepo/service/ExtensionRepoServiceContractTest.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/more/settings/screen/browse/ExtensionReposScreenModel.kt`
- Create: `app/src/test/java/eu/kanade/presentation/more/settings/screen/browse/ExtensionReposScreenModelWiringTest.kt`
- Modify: `docs/superpowers/plans/2026-07-24-task-14-product-parity-closure.md`

**User entry:** Android Settings → Browse → Extension repositories。
**Feedback:** create/replace/delete 显示 pending、success、validation、fingerprint conflict 和 failure。
**RED:** 先让真实 Android ScreenModel 执行 shared create/replace/delete；断开任一调用时失败。
**GREEN:** 复用当前 repository/service/interactors，统一结果模型与 fingerprint continuity。
**Mutation:** 分别删除 create、replace、delete 委托，确认 consumer 测试失败后恢复。
**Verification:** domain service contract、Android ScreenModel wiring、父 parity contract、Spotless。
**Desktop zero-regression:** 本 Task 不改 Desktop；A4 必须保留 normalization、确认和即时反馈。

**Execution evidence（已完成）：** 现有 DI `ExtensionRepoService` 以 domain-owned outcome 和单向 suspend operations 统一 create/replace/delete 的 pending/success/validation/fingerprint conflict/failure，不再引用三类 interactors；Android production ScreenModel 穷尽适配既有 create result，replace 保留旧 fingerprint 原值。shared operation API 编译 RED、各 mutation 断开与 continuity 破坏均精确 RED；恢复后 domain `2/2`、Android `1/1`、父 parity `1/1` GREEN，Android 表驱动覆盖全部 validation/conflict/failure `stringRes`，仅 3 次成功刷新。进程级 SDK `assembleDebug` 与 5038 `emulator-5556` 安装 GREEN；`aapt` 列出 `app.mihon.dev/MainActivity`，但 resolver 仍为 `No activity found`、显式启动 `Error type 3`，故未虚报设置页 runtime 通过。
### Task 144 A4 ID 32 Desktop extension repository wiring

**Risk axis:** desktop-extension-repo-crud
**Platform boundary:** shared+desktop
**Estimated scope:** 6 files, 380 lines
**Scope correction:** 第 6 个文件仅为本进度计划持久化；产品与测试范围仍是原定 5 文件。

**Files:**
- Modify: `domain/src/commonMain/kotlin/mihon/domain/extensionrepo/service/ExtensionRepoService.kt`
- Modify: `domain/src/commonTest/kotlin/mihon/domain/extensionrepo/service/ExtensionRepoServiceContractTest.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/settings/ExtensionRepoScreen.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/domain/ExtensionRepoUseCaseTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/settings/ExtensionRepoScreenFeedbackTest.kt`
- Modify: `docs/superpowers/plans/2026-07-24-task-14-product-parity-closure.md`

**User entry:** Desktop Settings → Browse → Extension repositories。
**Feedback:** pending/result、replace/delete 确认与失败原因立即显示并刷新列表。
**RED:** 先让真实 Screen 执行 A3 shared contract；绕过 production dependency 时失败。
**GREEN:** 复用现有 DI、interactors 与 repository，不新增 Desktop CRUD 实现。
**Mutation:** 断开 replace 或 delete wiring，确认 use-case/feedback tests 失败后恢复。
**Verification:** shared contract、Desktop use-case/feedback、父 parity contract、Spotless。
**Desktop zero-regression:** 保留 URL normalization、fingerprint replacement 和 partial failure。
**Execution evidence（已完成）：** shared `ExtensionRepoService.Actions` 持有 create/replace/delete/execute 的唯一实现，实例 API 与 Desktop production coordinator 均委托该 contract；Desktop Screen 继续使用现有 DI interactors/repository，mutation 后仅由 repository Flow 刷新列表，并保留 replace/delete 确认、URL normalization、旧 fingerprint 原值和 partial failure。shared/desktop 缺失 API 的编译 RED 后，domain `3/3`、Desktop use-case `8/8`、feedback `3/3` GREEN；replace、delete、fingerprint continuity 与 failure 误分类 4 个 mutation 均精确 RED 后恢复，其中误分类断言为预期成功动作 `[CREATE, REPLACE, DELETE]`、实际 `[CREATE, REPLACE, REPLACE, DELETE]`。headless production Screen 渲染验证覆盖 create outcomes 与空列表 delete 回归；未启动 GUI。独立审查 `APPROVED`，P0/P1/P2/P3 均为 0；主代理提交前复验 domain `3/3`、Desktop Screen/use-case/feedback/DI `45/45`、父 governance `1/1`，均 0 failure/0 error/0 skipped；根 `spotlessCheck` GREEN。

### Task 145 B1 ID 69 provider neutral core

**Risk axis:** android-tracker-provider-core
**Platform boundary:** shared+android
**Estimated scope:** 8 files, 400 lines

**Files:**
- Modify: `domain/src/commonMain/kotlin/tachiyomi/domain/track/service/TrackerService.kt`
- Modify: `domain/src/commonMain/kotlin/tachiyomi/domain/track/service/TrackerProviderContracts.kt`
- Modify: `domain/src/commonMain/kotlin/tachiyomi/domain/track/service/TrackerProviderProtocol.kt`
- Modify: `domain/src/commonTest/kotlin/tachiyomi/domain/track/service/TrackerProviderContractTest.kt`
- Modify: `domain/src/commonTest/kotlin/tachiyomi/domain/track/service/TrackerProviderProtocolTest.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/track/TrackerManager.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/manga/track/TrackInfoDialog.kt`
- Modify: `app/src/test/java/eu/kanade/tachiyomi/data/track/AndroidTrackerApiIntegrationTest.kt`

**User entry:** Android Manga detail → Tracking 与 Settings → Tracking。
**Feedback:** bind/search/update/delete、initial status/date、auth 和 provider error 返回稳定分类。
**RED:** 先固定 provider configuration、session、refresh-before-update、edit/delete 与 request/error contract，并由 Android production consumer 执行。
**GREEN:** provider-neutral 请求、状态和错误进入现有 domain contract；Android 只保留网络/credential adapter。
**Mutation:** 绕过 refresh、初始状态/date 或错误映射，确认 shared/Android tests 失败后恢复。
**Verification:** shared provider tests、Android API integration、父 parity contract、Spotless。
**Desktop zero-regression:** 本 Task 不改 Desktop；B2 必须保留凭据与 enhanced provider 能力。

### Task 146 B2 ID 69 Desktop provider adapters

**Risk axis:** desktop-tracker-provider-adapters
**Platform boundary:** shared+desktop
**Estimated scope:** 8 files, 400 lines

**Files:**
- Modify: `domain/src/commonMain/kotlin/tachiyomi/domain/track/service/TrackerProviderProtocol.kt`
- Modify: `domain/src/commonTest/kotlin/tachiyomi/domain/track/service/TrackerProviderProtocolTest.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/tracking/DesktopTrackerServiceRegistry.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/tracking/DesktopEnhancedTrackerServices.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/tracking/TrackingScreenModel.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/tracking/DesktopProviderTrackerServiceTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/tracking/DesktopEnhancedTrackerServiceTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/tracking/TrackingScreenModelTest.kt`

**User entry:** Desktop Manga detail → Tracking 与 Settings → Tracking。
**Feedback:** bind/search/edit/delete、enhanced auto-match、Suwayomi delete、Komga discovery、Kitsu/MangaUpdates request failure 均可见。
**RED:** 先让 registry/enhanced services/ScreenModel 重放 B1 shared contract 和 provider-specific fixture。
**GREEN:** 只在 Desktop adapter 保留 transport、DNS、credential 与 endpoint 差异。
**Mutation:** 破坏 delete flag、discovery endpoint、request field 或 error mapping，确认测试失败后恢复。
**Verification:** shared protocol、Desktop registry/enhanced/ScreenModel tests、父 parity contract、Spotless。
**Desktop zero-regression:** 保留每 provider 独立凭据、session isolation、endpoint override、redaction 与 checkpoint。

### Task 147 B3 ID 70 delayed tracker sync

**Risk axis:** android-delayed-tracker-sync
**Platform boundary:** shared+android
**Estimated scope:** 6 files, 400 lines

**Files:**
- Create: `domain/src/commonMain/kotlin/tachiyomi/domain/track/service/DelayedTrackerSyncQueue.kt`
- Create: `domain/src/commonTest/kotlin/tachiyomi/domain/track/service/DelayedTrackerSyncQueueTest.kt`
- Modify: `domain/src/commonMain/kotlin/tachiyomi/domain/track/interactor/SyncReadingProgressWithTrack.kt`
- Modify: `domain/src/commonTest/kotlin/tachiyomi/domain/track/interactor/SyncReadingProgressWithTrackTest.kt`
- Modify: `app/src/main/java/eu/kanade/domain/track/service/DelayedTrackingUpdateJob.kt`
- Modify: `app/src/main/java/eu/kanade/domain/track/store/DelayedTrackingStore.kt`
- Modify: `app/src/main/java/eu/kanade/domain/track/interactor/TrackChapter.kt`
- Create: `app/src/test/java/eu/kanade/domain/track/service/DelayedTrackingUpdateJobSharedQueueTest.kt`

**User entry:** Android 阅读章节退出后的自动同步。
**Feedback:** 不符合登录/进度条件时跳过；网络失败排队；成功清理，耗尽重试保留原因。
**RED:** 先用 production integration test 执行 store、job `setupTask` 与 `TrackChapter`，固定单调最高进度、network constraint、unique work、指数退避、有界重试与 cleanup。
**GREEN:** shared queue 决定 refresh/filter/parallel update、合并、重试和清理；store/job/`TrackChapter` 只提供 Android persistence 与调度边界。
**Mutation:** 分别断开 store、job `setupTask`、`TrackChapter` 或 shared queue，确认 production integration test 精确失败后恢复。
**Verification:** shared queue/interactor、Android store/job/TrackChapter integration、父 parity contract、Spotless。
**Desktop zero-regression:** 本 Task 不改 Desktop；B4 必须复用现有 checkpoint。

### Task 148 B4 ID 70 Desktop delayed sync consumer

**Risk axis:** desktop-delayed-tracker-sync
**Platform boundary:** shared+desktop
**Estimated scope:** 6 files, 400 lines

**Files:**
- Modify: `domain/src/commonMain/kotlin/tachiyomi/domain/track/service/DelayedTrackerSyncQueue.kt`
- Modify: `domain/src/commonTest/kotlin/tachiyomi/domain/track/service/DelayedTrackerSyncQueueTest.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/tracking/DesktopTrackerSyncScheduler.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/tracking/DesktopTrackerSyncSchedulerTest.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/domain/ReaderProgressTracker.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/domain/ReaderProgressTrackerTest.kt`

**User entry:** Desktop 阅读章节退出后自动同步；Settings → Tracking 控制自动同步。
**Feedback:** shared queue 的排队、重试、成功清理与 terminal failure 由现有 Desktop 状态反馈。
**RED:** 先让 scheduler/progress tracker 执行 B3 queue；绕过 shared merge/retry 时失败。
**GREEN:** Desktop 仅提供 JVM scheduler、network constraint 与 persistence adapter。
**Mutation:** 断开 queue 注入、restart restore 或 cleanup，确认 scheduler/tracker tests 失败后恢复。
**Verification:** shared queue、Desktop scheduler/tracker、父 parity contract、Spotless。
**Desktop zero-regression:** 保留 restart checkpoint、取消隔离、caller cancellation 后完成与失败原因持久化。

### Task 149 C1 ID 87 Desktop language

**Risk axis:** desktop-locale-selection
**Platform boundary:** desktop
**Estimated scope:** 7 files, 360 lines

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/settings/DesktopAppPreferences.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopLocaleAdapter.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/Main.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/settings/AppearanceSettingsScreen.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/platform/DesktopLocaleAdapterTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/i18n/DesktopSettingsResourceIdentityTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/settings/DesktopPreferenceMigrationTest.kt`

**User entry:** Desktop Settings → Appearance → App language。
**Feedback:** 选择 System/具体语言后立即显示选择与应用结果；写入失败保留旧 locale 并显示错误。
**RED:** 先覆盖 system default、持久化、即时资源刷新、重启恢复与写入失败。
**GREEN:** JVM locale adapter 接入现有 preferences 与 MR consumer；Android locale API 不进入 Desktop。
**Mutation:** 断开 preference write、startup restore 或 UI apply，确认 adapter/rendered-copy tests 失败后恢复。
**Verification:** locale adapter、preference migration、Appearance resources、父 parity contract、Spotless。
**Desktop zero-regression:** 主题、纯黑、reader 与现有 MR consumers 不变；语言切换不重置其他 preference。

## 最终回收

九个 Task 全部提交并通过各自独立审查后，把 hash、focused test 数量、mutation 证据和剩余边界交给父计划 Task 14C。Task 14C 才能更新 manifest 状态；本计划不得提前宣称 ID 3、32、69、70、87 已终态。
