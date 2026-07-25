---
parent-plan: docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md
parent-task: Task 14
task-base: 0c6d360441c6ba64613063db7b197c0f88fa3d08
original-ref: main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8
status: completed
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
- [x] Task 145A：B1a ID 69 shared provider-neutral core
- [x] Task 145B1：B1b-1 ID 69 Android provider adapter
- [x] Task 145B2：B1b-2 ID 69 Android tracking UI actions
- [x] Task 146A：B2a ID 69 Desktop public provider lifecycle
- [x] Task 146B：B2b ID 69 Desktop production OAuth ingress
- [x] Task 146C：B2c ID 69 Desktop tracking edit and unbind capability
- [x] Task 146D：B2d ID 69 Desktop enhanced tracker auto-match
- [x] Task 147：B3 ID 70 Android delayed tracker sync
- [x] Task 148：B4 ID 70 Desktop delayed sync consumer
- [x] Task 149：C1 ID 87 Desktop language

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

### Task 145A B1a ID 69 shared provider-neutral core

**Risk axis:** android-tracker-provider-core
**Platform boundary:** shared
**Estimated scope:** 5 files, 360 lines

**Files:**
- Modify: `domain/src/commonMain/kotlin/tachiyomi/domain/track/service/TrackerService.kt`
- Modify: `domain/src/commonMain/kotlin/tachiyomi/domain/track/service/TrackerProviderContracts.kt`
- Modify: `domain/src/commonMain/kotlin/tachiyomi/domain/track/service/TrackerProviderProtocol.kt`
- Modify: `domain/src/commonTest/kotlin/tachiyomi/domain/track/service/TrackerProviderContractTest.kt`
- Modify: `domain/src/commonTest/kotlin/tachiyomi/domain/track/service/TrackerProviderProtocolTest.kt`

**User entry:** 为 Android Manga detail → Tracking 与 Settings → Tracking 提供共享语义，不新增独立 UI。
**Feedback:** configuration/session、refresh-before-update、edit/delete request/result/error 返回稳定分类。
**RED:** 固定 provider configuration、session、refresh-before-update、edit/delete 与 request/error contract。
**GREEN:** provider-neutral 请求、状态、错误和 workflow 进入现有 domain contract，不包含 Android 网络或 credential 类型。
**Mutation:** 绕过 refresh、initial status/date 或错误映射，确认 shared tests 失败后恢复。
**Verification:** shared provider tests、domain JVM/Android compile、Spotless。
**Desktop zero-regression:** 本 Task 不改 Desktop 或 Android production；145B 和 146 分别消费同一 shared contract。

**Scope correction:** 原 Task 145 的 8 文件 GREEN 草案达到 `429 additions + 31 deletions = 460 touched`，
超过 400 硬门禁；代码未压缩、未提交、未勾选时按可独立验收边界拆为 145A/145B。现有 WIP
中 145A 初始草案为 5 文件/235 touched，145B 为 3 文件/225 touched；145A 经格式化、自审与
unsupported-delete RED 修复后为 255 touched，独立审查修复预算校正为 340 touched。两批串行复用
相同原版取证与 RED，不得在 Android consumer 中复制 145A 的 workflow 或错误规则。
修复复审后将剩余日期差异收口到 Protocol 及其测试：手工 0→1 不写 startDate，手工末章
无视 `supportsReadingDates` 写 finishDate，AUTO_COMPLETE 才按能力写日期；为保留 false/true
对照而不压缩，145A 预算校正为 360 touched，硬门禁仍为 400。
**Execution evidence（已完成）：** fixed original 仅为
`main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`。shared contract 统一 provider
configuration/session、request/result/error、refresh-before-update 与 DELETE 零 refresh；
章节进度由 `INITIAL_ONLY`、`AUTO_COMPLETE`、`ALWAYS_READING` 三策略表达 BaseTracker、
常规 provider 与 MangaUpdates 原版差异。初始 RED 精确编译失败于缺失 shared API；审查修复
又分别以 unsupported DELETE、session bypass、paused existing progress、MangaUpdates final、
手工 startDate 与无日期支持的手工 finishDate mutation 精确 RED，全部恢复。最终 focused
`10/10`，JVM/Android release compile、domain Spotless 与 diff-check GREEN；范围为 5 shared
files/345 touched，Android 145B WIP 未纳入。独立最终确认 `APPROVED`，P0/P1/P2/P3 均为 0。

### Task 145B1 B1b-1 ID 69 Android provider adapter

**Risk axis:** android-tracker-provider-consumer
**Platform boundary:** android
**Estimated scope:** 6 files, 320 lines

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/track/TrackerManager.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/track/myanimelist/MyAnimeListApi.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/track/myanimelist/MyAnimeListInterceptor.kt`
- Modify: `app/src/test/java/eu/kanade/tachiyomi/data/track/AndroidTrackerApiIntegrationTest.kt`
- Modify: `domain/src/commonMain/kotlin/tachiyomi/domain/track/service/TrackerProviderProtocol.kt`
- Modify: `domain/src/commonTest/kotlin/tachiyomi/domain/track/service/TrackerProviderProtocolTest.kt`

**User entry:** Android Manga detail → Tracking 的 provider 请求。
**Feedback:** provider adapter 对 bind/update/delete、auth 和错误返回稳定分类。
**RED:** Android manager 必须执行 145A workflow，并覆盖 fixed-main completion 与真实 MAL 错误链路。
**GREEN:** `TrackerManager` 委托 shared contract；Android 只保留 transport、credential、persist 与错误 adapter。
**Mutation:** 绕过 shared workflow、refresh、completion 或错误映射，确认 integration 精确失败后恢复。
**Verification:** Android API integration、父 parity contract、Android compile、Spotless。
**Desktop zero-regression:** 本 Task 不改 Desktop 或 Android UI；145B2 接入 UI，146 保留 Desktop 凭据与 enhanced provider 能力。

**Scope correction:** 原 3 文件/260 行边界在独立审查中暴露 fixed-main completion 丢失、
MAL `invalid_content` production 分支被测试内同名假异常冒充，以及真实 `MALTokenExpired`
被 OkHttp 包装后误分类。修复这些 production 风险需要 shared rule、真实 MAL API/interceptor
与 Android adapter 六文件，故先作为可独立验收的 145B1 收口；原 `TrackInfoDialog` WIP
移交 145B2，不混入本提交。

**Execution evidence（已完成）：** shared completion RED 精确为预期总章节 `10.0`、实际
`3.0`；修复后由 Android manager 真实验证 refresh → shared apply → `update(false)` → persist。
真实 MockWebServer `invalid_content` 经 production API 抛 `MALTitleNotApproved`；真实 interceptor
产生的两层 `IOException` wrapper 又精确 RED 于预期 AUTHENTICATION、实际 NETWORK，修复为最多
8 层且自环安全的 cause-chain 分类。最终 Android `9/9`、shared `10/10`、父 parity `34/34`、
Android compile、app/domain Spotless 与 diff-check GREEN；产品/测试范围为 6 files/295 touched，
独立修复复审 `APPROVED`，P0/P1/P2/P3 均为 0，145B2 UI WIP 未纳入。

### Task 145B2 B1b-2 ID 69 Android tracking UI actions

**Risk axis:** android-tracker-ui-wiring
**Platform boundary:** android
**Estimated scope:** 3 files, 300 lines

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/manga/track/TrackInfoDialog.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/manga/track/TrackInfoDialogActions.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/ui/manga/track/TrackInfoDialogActionWiringTest.kt`

**User entry:** Android Manga detail → Tracking 的 status、chapter、score、date、private 与 delete 操作。
**Feedback:** 所有动作复用 145B1 manager；失败显示稳定反馈，成功沿既有 track flow 刷新。
**RED:** 行为测试直接执行 production action，覆盖六类精确 request、手工 chapter
`didReadChapter=false` 与 failure feedback；断开任一 action→manager wiring 时测试失败。
**GREEN:** 七个 ScreenModel call site 只委托可注入 executor/feedback 的 production action，
不得在 UI 复制 shared workflow、provider error 或章节规则。
**Mutation:** 分别绕过 action executor、把手工 chapter 标为自动阅读、删除 failure feedback，
确认 wiring test 精确失败后恢复。
**Verification:** Android action wiring、145B1 integration、父 parity contract、Android compile、Spotless。
**Desktop zero-regression:** 本 Task 不改 Desktop；146 继续消费同一 shared contract。

**Execution evidence（已完成）：** action facade 永久 RED 于 production class 缺失，GREEN 后
executor bypass、chapter `false→true` 与 failure feedback 删除三项 mutation 均精确失败并恢复。
独立审查发现 facade 测试尚不能保护 UI call site 后，七个真实 production Model 改为注入同一
Actions，并由行为测试直接执行 private/status/chapter/score、双 date、双 remove 与 delete；
真实 `setChapter` call site 错接 `setStatus` mutation 精确出现缺 chapter、多 status 后恢复。
最终 UI `2/2`、145B1 integration、父 parity `34/34`、Android compile、Spotless 与 diff-check
GREEN；范围 3 files/272 touched，修复复审 `APPROVED`，P0/P1/P2/P3 均为 0。

### Task 146A B2a ID 69 Desktop public provider lifecycle

**Risk axis:** desktop-public-tracker-lifecycle
**Platform boundary:** shared+desktop
**Estimated scope:** 11 files, 790 lines

**Scope correction:** 原 Task 146 首轮实现形成 10 文件、1145 touched 的 GREEN 草案；独立审查证明它同时混合
public provider 远端记录生命周期、OAuth 平台入口、编辑/解绑 UI 与 enhanced auto-match 四条可独立验收的产品链。
本拆分由实际架构与产品风险触发，不是文件/行数预算触发；保留现有草案，按 146A→146B→146C→146D
依次收敛，每一批均有独立 production wiring 与行为测试。

**Files:**
- Modify: `domain/src/commonMain/kotlin/tachiyomi/domain/track/service/TrackerProviderProtocol.kt`
- Modify: `domain/src/commonTest/kotlin/tachiyomi/domain/track/service/TrackerProviderProtocolTest.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/tracking/DesktopTrackerServiceRegistry.kt`
- Delete after migration: `app-desktop/src/main/kotlin/mihon/desktop/tracking/api/TrackerHttpException.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/tracking/TrackingScreenModel.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/tracking/TrackingSettingsScreen.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/tracking/TrackingTestModeController.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/tracking/DesktopProviderTrackerServiceTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/tracking/TrackingScreenModelTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/tracking/TrackingTestModeControllerTest.kt`
- Modify: `app-desktop/src/test/resources/parity/parity-manifest.json`（仅维护因 DI wiring 行数变化产生的既有证据行号）

**User entry:** Desktop Manga detail → Tracking → 搜索并绑定 MAL、AniList、Kitsu、Shikimori、Bangumi 或 MangaUpdates。
**Feedback:** 已存在远端记录时保留远端 ID、进度、评分、隐私与日期；新记录按是否已有阅读章节选择原版初始状态；provider 错误与退避时间可见且可重试。
**RED:** 先固定 original Mihon 六家 provider 的 existing/new bind 分支、`hasReadChapters`、Kitsu `ratingTwenty=null`、MAL `invalid_content` 和 `Retry-After` 语义；API 与 OAuth 使用不同 MockWebServer，账户隔离至少覆盖两个账户；真实 ScreenModel 从 production chapter state 计算 `hasReadChapters`，不得由测试直接调用三参数接口冒充 wiring。
**GREEN:** shared contract 表达现有/新增远端记录与退避元数据，Desktop adapter 只保留 HTTP、凭据、endpoint 和序列化差异；删除第二套 `TrackerHttpException`；ScreenModel 对 public provider 传递真实已读状态，对 enhanced/provider-neutral 服务保留原契约。
**Mutation:** 绕过远端查询、丢失 remote library ID、把 Kitsu null 写成 0、丢弃 `Retry-After` 或把 MAL title rejection 映射为 UNKNOWN，确认对应测试精确失败后恢复。
**Verification:** shared protocol、Desktop public provider、ScreenModel/TestMode production caller、父 parity contract、Spotless。
**Desktop zero-regression:** 保留每 provider 独立 endpoint override、账户 session isolation、日志 redaction 与 MangaUpdates rating。

**Execution evidence（已完成）：** fixed original 仅取
`main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8` 的六家 public provider
`bind/find/update/refresh` 与 `AddTracks`；shared contract 新增保留 `Retry-After` 的统一错误模型及
`hasReadChapters` bind 输入，Desktop production ScreenModel 通过 required `ChapterRepository`
把真实章节已读状态传给 public provider，enhanced/provider-neutral 服务仍走原两参数契约。六家
existing-first/new 分支分别保留原版的 completed、rereading、remote ID/library ID、进度、评分、
private 与日期语义；AniList 持久化并迁移 Viewer ID，Kitsu 使用 `filter[self]=true`，Bangumi
强制网络读取，Kitsu null score、MAL `invalid_content`、MangaUpdates rating 均有 production
fixture。旧 `TrackerHttpException` 已删除；enhanced 在本提交中只迁移 shared HTTP/network error
与非负 `Retry-After`，其余 146D WIP 保持未暂存。

原始 RED `task146a-red` 精确编译失败于三参数 bind 与 retry metadata 缺失；GREEN
`task146a-green-2` 后，`task146a-mutation-red` 以绕过 MAL existing 查询、保留 Kitsu stale
score、丢弃 `Retry-After` 三项 mutation 精确 RED 并恢复。独立审查发现用户身份、真实 caller
wiring、force-network 与分支/endpoint 测试缺口后，repair RED `task146a-repair-red` 精确失败于
required chapter repository wiring；`task146a-repair-green-2` GREEN，真实 hasRead wiring
mutation `task146a-repair-mutation-red` 精确 RED 后恢复。修复复审确认全部 provider 语义和
146A/146D 暂存边界，仅指出 staged UI 构造参数错序；随后改为命名参数并由主代理重新验证。
最终 `task146a-main-final-focused` 的 shared/provider/enhanced/ScreenModel/TestMode/UI caller
49 项 GREEN；`task146a-main-gates-2` 的 ordinary parity 34 项与根 `spotlessCheck` GREEN；
`git diff --cached --check`、工作树 `git diff --check` 均 clean。DI wiring 新增一行导致既有
ID 59 evidence 行号由 825 漂移到 826，manifest 仅维护该位置，未改变 capability 状态。
精确暂存为 15 files、1926 touched；超出初始估算来自六家 provider 的真实请求/响应 fixture、
production caller 与审查补证，未按行数压缩或混入 146C/146D 产品行为。

### Task 146B B2b ID 69 Desktop production OAuth ingress

**Risk axis:** desktop-tracker-oauth-production-wiring
**Platform boundary:** desktop-platform+desktop
**Estimated scope:** 11 files, 620 lines

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/tracking/DesktopTrackerServiceRegistry.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/tracking/DesktopTrackerOAuthCallbackBroker.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/DesktopUiDependencies.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/Main.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/tracking/TrackingSettingsScreen.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/tracking/DesktopProviderTrackerServiceTest.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/tracking/DesktopTrackerOAuthCallbackBrokerTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/tracking/DesktopTrackingIntegrationTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/DesktopAppRuntimeTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt`

**User entry:** Desktop Settings → Tracking → 登录对应 provider。
**Feedback:** MAL、AniList、Kitsu、Shikimori、Bangumi 五家需客户端配置的 provider 在真实 production wiring 中可登录；四家 OAuth provider 的启动参数和运行中 `mihon://...-auth` 回调均只完成当前 state/provider 的一次登录，过期、错 provider 或重复回调显示安全失败且不进入普通导航。
**RED:** 先让 production `DesktopAppModule` 解析五家可用 provider，并用独立 OAuth/API server 固定 authorization/token 路径；覆盖启动 URI、热 URI、query/fragment、错误 state、重复交付与 token redaction。
**GREEN:** 使用 fixed original 的 provider client/callback 语义；既有 Desktop URI scheme/single-instance ingress 先交给 OAuth broker，未消费的 URI 才进入 `ExternalActionNavigator`。loopback server 只作为确有平台需求的 adapter，不再是五家 provider 的默认前提。
**Mutation:** 清空 production client config、交换 API/OAuth base、让 OAuth URI落入普通导航、跳过 state 或重复消费，确认 production wiring/integration test 精确失败后恢复。
**Verification:** provider、OAuth broker、Main ingress、DI wiring、父 parity contract、Spotless。
**Desktop zero-regression:** 保留单实例、普通 deep link、search/backup/repository URI 行为；凭据和 token 不进入日志或普通导航反馈。

**Execution evidence（已完成）：** fixed original 仅取
`main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`。production registry 以原版 client
ID/secret、OAuth/API endpoint 和 grant 参数启用 MAL、AniList、Kitsu、Shikimori、Bangumi；
MAL 保留 plain PKCE 且不强制 redirect，AniList 只从 fragment 接收 implicit token，
Shikimori/Bangumi 使用固定 custom redirect，Bangumi refresh 同样携带该 redirect。共享
OAuth broker 以单 pending session、provider/state、query/fragment、错误、超时、取消和一次性交付
约束 startup、single-instance hot event 与 AWT OpenURI 三条 production ingress；错 host/provider/state、
过期、重复或畸形回调均被消费且不会进入普通导航或诊断泄密。初始 broker/config/ingress 编译与行为
RED、69 项 focused GREEN、ingress fallthrough mutation RED 均成立；独立审查发现 wrong-host、
跨 query/fragment 和 Bangumi refresh 三个缺口后，repair RED 为 5 项中 3 项精确失败，
GREEN 后删除 Bangumi redirect 的 mutation 精确失败，恢复后 34 项 repair focused 全绿，唯一复审
`APPROVED`（P0/P1/P2=0）。主代理最终按 146B 边界运行 67 项 provider/broker/integration/runtime/
DI/login UI focused 全绿；一次包含未暂存 146C 对话框草案的扩大集合出现 1 个 Compose 场景失败，
该测试隔离运行通过，未据此修改或提前收口 146C。父 parity contract 34 项与根 `spotlessCheck`
最终全绿；OAuth import/wiring 造成既有 manifest 六处 evidence 行号漂移，仅更新位置，未改变
capability 状态。精确暂存不含 146C/146D 或用户下载队列改动。

### Task 146C B2c ID 69 Desktop tracking edit and unbind capability

**Risk axis:** desktop-tracker-user-actions
**Platform boundary:** desktop
**Estimated scope:** 5 files, 460 lines

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/tracking/TrackingScreenModel.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/tracking/TrackingSettingsScreen.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/tracking/TrackingScreenModelTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/tracking/TrackingSettingsKeyboardDialogTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/tracking/DesktopTrackingIntegrationTest.kt`

**User entry:** Desktop Manga detail → Tracking → 编辑或删除绑定。
**Feedback:** provider 支持时可编辑 status、score、chapter、private、开始日期与完成日期；本地解绑立即生效，远端删除是显式可选动作，远端失败/超时不阻塞本地结果并给出反馈。
**RED:** 先覆盖 private/date 的真实 UI→ScreenModel→registry adapter 链；覆盖 logged-out、unavailable、远端挂起与远端失败时 local-first unbind；确认删除行只有一个 checkbox operable semantics，Space/Enter 每次只切换一次。
**GREEN:** UI 穷尽 provider config 输出可编辑字段；先删除本地绑定并刷新可见状态，再独立尝试可选远端删除；`Row.toggleable(Role.Checkbox)` 配合只读 `Checkbox`。
**Mutation:** 删除任一 edit 字段、恢复 remote-first 顺序、重新要求登录才能本地解绑或制造双 checkbox semantics，确认 production integration/UI test 精确失败后恢复。
**Verification:** ScreenModel、mounted Compose dialog、真实 registry adapter integration、父 parity contract、Spotless。
**Desktop zero-regression:** 默认仍仅解绑本地；危险远端删除必须显式勾选并保留失败反馈，不静默删除远端记录。

**Execution evidence（已完成）：** `task146c-red-local-checkbox-2` 先以 18 项中的 3 个精确失败固定
local-first、logged-out/unavailable 本地解绑与单一 checkbox semantics，`task146c-red-all-fields`
再以 27 项中的 5 个精确失败补齐 private/date 与真实 registry 六字段链；实现 GREEN 后恢复
remote-first 的 mutation 精确 RED。独立首审发现自由 ISO 日期输入和已绑定但登出/不可用条目无法从
mounted UI 进入解绑两项 P1；修复 RED 为 12 项中的 2 项精确失败，改为 Material3
`DatePicker`/`DatePickerDialog`/`SelectableDates`、显式 Remove→`0`、UTC picker 日历日与
system-local epoch 双向转换，并让 bound 条目优先进入 Manage。未来日期边界 mutation 精确 RED
后恢复，最终 repair focused `31/31` GREEN；修复复审 `APPROVED`，P0/P1/P2 均为 0。
主代理强制重新执行 ScreenModel、mounted dialog、registry integration `31/31`，父 parity contract
`34/34` 与根 `spotlessCheck` 均 GREEN，0 failure/0 error/0 skipped。精确范围为计划内
5 files/885 touched，不含 146D enhanced auto-match、用户 DownloadQueue 改动或环境噪声。

### Task 146D B2d ID 69 Desktop enhanced tracker auto-match

**Risk axis:** desktop-enhanced-tracker-auto-match
**Platform boundary:** shared+desktop
**Estimated scope:** 7 files, 520 lines

**Files:**
- Modify: `domain/src/commonMain/kotlin/tachiyomi/domain/track/service/TrackerProviderProtocol.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/tracking/DesktopEnhancedTrackerServices.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/tracking/DesktopEnhancedTrackerContextProvider.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/tracking/TrackingScreenModel.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/tracking/TrackingSettingsScreen.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/tracking/DesktopEnhancedTrackerServiceTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/tracking/TrackingScreenModelTest.kt`

**User entry:** Desktop Manga detail → Tracking。
**Feedback:** 当前 manga 的 source 被 Komga、Kavita 或 Suwayomi 接受时自动匹配并绑定；无匹配、认证失败与服务失败均显示稳定结果，仍可使用现有手动搜索。
**RED:** 用真实 manga source/id/url 和 production context provider 固定 fixed original `accept(source)→match(manga)→bind`；覆盖 Komga、Kavita、Suwayomi 的成功、无匹配、认证失败及 Suwayomi remote-download delete flag。
**GREEN:** shared/provider contract 承载 accept/match/bind 语义，Desktop 仅提供 source client、endpoint、credential 与 URL/ID adapter；ScreenModel 从真实 manga 详情上下文触发自动匹配并持久化结果。
**Mutation:** 绕过 source accept、改用标题猜测、断开真实 manga URL、破坏 Komga discovery 或 Suwayomi delete flag，确认 production integration test 精确失败后恢复。
**Verification:** shared protocol、enhanced provider/context、ScreenModel integration、父 parity contract、Spotless。
**Desktop zero-regression:** 保留每 source 独立 client/session、restart checkpoint、手动搜索入口和 Desktop 独有远端下载删除选项；四批全部通过后才视为原 Task 146 完成。

**Execution evidence（已完成）：** 既有 refresh-before-update 与 Suwayomi download-delete 草案先由
`task146d-baseline` 证明可运行；shared `EnhancedTrackerWorkflow` 的 clean compile RED 仅缺
`EnhancedTrackerManga`/`EnhancedTrackerService`/workflow API，随后以 domain `8/8` GREEN
固定 `accept→match→bind` 与 rejected/no-match 短路。Desktop wiring RED 先缺真实
`MangaRepository` 输入；GREEN 后由 production registry、真实 manga source/id/url、MockWebServer
和持久化 repository 覆盖 Komga/Kavita/Suwayomi accepted-source match/bind、无标题猜测与既有
track checkpoint。补证 RED 精确暴露取消传播、Kavita 配置、Suwayomi remote URL 与
no-match/auth/server 反馈缺口，修复后保留手动搜索、每 source client、Komga discovery、Kavita
认证、Suwayomi delete flag 及 refresh-before-update。移除 sourceId 隔离的 mutation 使 9 项中
2 项精确失败，恢复后独立审查 `APPROVED`，P0/P1/P2 均为 0。主代理强制重新执行 domain `8/8`
与 Desktop `23/23`，父 parity contract `34/34`、根 `spotlessCheck` 和 diff-check 均 GREEN，
0 failure/0 error/0 skipped。精确范围为 8 files/643 touched；第 8 文件是 shared workflow
必需的 domain TDD，不含用户 DownloadQueue 改动或环境噪声。

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

**Execution evidence（已完成）：** fixed original 仅使用
`main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8` 的 `DelayedTrackingStore`、
`DelayedTrackingUpdateJob` 与 `TrackChapter` 语义；shared `DelayedTrackerSyncQueue`
统一登录/进度过滤、provider workflow 的 refresh-before-update、并行更新、失败原因、
有界重试与 cleanup，Android Store/Job/`TrackChapter` 仅保留持久化、WorkManager 与
`TrackerManager.execute` adapter。初始 domain/app RED 分别精确缺失 shared queue 与
production seam，GREEN 后四项 mutation 分别断开最高进度、Store reason、CONNECTED
constraint 和 `TrackChapter` queue wiring，均精确失败并恢复。独立审查随后发现跨 queue
实例可发生低进度覆盖或低成功删除高进度，以及 Worker 委派、旧 Float 迁移和 provider
consumer 分支证据不足；修复 RED 四组精确失败后，将原子 `upsertMax` 与条件
`removeUpTo` 提升为 persistence 契约，补齐 production worker runner、旧 Float fixture
及真实 `TrackerProviderWorkflow` consumer 测试。唯一修复复审 `APPROVED`，P0/P1/P2
均为 0。主代理最终重跑 shared/domain `8/8`、Android integration `4/4`、父 parity
contract `34/34` 与根 `spotlessCheck` 全绿；Android 首次重跑仅因 shell 未设置 SDK
路径在测试发现前失败，显式使用仓库 `.android-sdk` 后通过。精确范围为计划内 8 files /
771 touched，不含 Desktop、用户 DownloadQueue 改动或环境噪声。

### Task 148 B4 ID 70 Desktop delayed sync consumer

**Risk axis:** desktop-delayed-tracker-sync
**Platform boundary:** shared+desktop
**Estimated scope:** 6 files, 400 lines
**Scope correction:** 实际范围为 10 files / +1043/-50。除计划内 shared queue、Desktop scheduler
与 Reader 测试外，为保证跨实例文件事务和真实 production wiring，内聚增加
`DesktopTaskScheduler`、shared `SyncReadingProgressWithTrack`、`DesktopAppModule`、
DI wiring test，以及一条因 AppModule 行移动产生的 manifest evidence 行号维护；未扩张到
相邻 capability。

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

**Evidence:** network gate、attempt 0..3、跨 scheduler 原子 merge/cleanup、new/legacy malformed
隔离与完整 DI restart 均先取得精确 RED；修复后 Desktop 使用真实 JVM connectivity gate、
共享文件锁内单事务和 shared `queue.drain` consumer。补充 mutation/受控并发证明 missing
track 与已完成进度会清理、logged-out/provider failure 每次 invocation 恰增一次、读取前替换
的新 checkpoint 会计次而读取后替换不会误计，并证明移除
`DesktopAppRuntime → trackerSyncScheduler` production wiring 时测试精确超时失败。唯一独立
修复复审最终 `APPROVED`（P0/P1/P2 均为 0）。主代理最终重跑 Reader/scheduler/真实 DI
focused、shared queue/interactor、父 parity contract `34/34` 与根 `spotlessCheck` 全绿；
manifest 仅更新一条证据行号，未改变 capability 状态。用户 `DownloadQueueScreen.kt` 与环境
噪声均未进入提交。

### Task 149 C1 ID 87 Desktop language

**Risk axis:** desktop-locale-selection
**Platform boundary:** desktop
**Estimated scope:** 7 files, 360 lines
**Scope correction:** 实际产品、测试与机器证据范围为 10 files / +923/-13。为保护真实
production startup、稳定 Navigator/反馈宿主和父 parity 证据，内聚增加
`DesktopUiDependencies`、`DesktopAppRuntimeTest` 与仅三处 `Main.kt` evidence 行号维护；
未扩张到 Android locale API、source language 或相邻 capability。

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

**Evidence:** 固定 main 生成的 67 项应用语言清单（包括权威旧标签 `in`）、默认跟随系统、
BCP47/中文 Hans-Hant 显示、持久化与启动恢复、即时 MR 刷新、写入/JVM apply/二次 rollback
失败协调均先取得精确 RED；production 现把 locale `key` 限制在既有 Navigator 内，并把
Snackbar host 保持在 key 外，切换后保留 Appearance 路由与状态。唯一独立审查和同一修复
复审最终 `APPROVED`（P0/P1/P2 均为 0）；其中反馈生命周期强化测试先以 2/2 精确 RED 证明
提前 ack 会自取消，再以 2/2 GREEN 证明跨额外重组持续可见、Dismiss 后仅消费一次。主代理
最终完整关联矩阵（adapter、完整 Settings resources、runtime、migration、父 parity contract）
`226/226` 与根 `spotlessCheck` 全绿，`diff --check` 通过；manifest 只维护三处因 Main import
移动产生的证据行号，未改变 capability 状态。用户 `DownloadQueueScreen.kt` 与环境噪声未进入
本 Task。

## 最终回收

九个 Task 全部提交并通过各自独立审查后，把 hash、focused test 数量、mutation 证据和剩余边界交给父计划 Task 14C。Task 14C 才能更新 manifest 状态；本计划不得提前宣称 ID 3、32、69、70、87 已终态。
