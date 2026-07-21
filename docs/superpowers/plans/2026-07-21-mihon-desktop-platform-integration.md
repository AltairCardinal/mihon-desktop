---
change: align-desktop-platform
design-doc: docs/superpowers/specs/2026-07-21-mihon-desktop-platform-integration-design.md
base-ref: 952be2f7897f9221b2e07bf7e52891a8fdaa8696
original-ref: 6fbf6dfca203d99d6dd32137f2df97ced40c81b8
status-source: this-file
---

# Mihon Desktop 系统集成、隐私与发布实施计划

> 本计划恢复父 roadmap 的 Task 5A `align-desktop-platform`，不创建平行 change。此前工具生成的 change 目录只作为需求输入；从本计划建立后，施工状态以本文件、父 roadmap、任务提交和验证证据为准。

> 固定原版唯一权威为 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`。当前 `app/` 是 fork 后 Android consumer；`app-desktop/src/main/kotlin/android/` 是 Desktop Android API compatibility shim，二者均不得冒充原版实现。

**目标：** 对齐 parity 81–86、92：把固定原版的外部动作、分享、安全、隐私和发布语义提取为共享契约；让当前 Android consumer 与 Desktop production wiring 消费它；用真实 Windows/macOS/Linux adapter 实现系统 side effect，并为无法等价支持的 Widget/窗口/安装能力提供明确边界。

**架构：** common 只拥有 parser、policy、payload 和 state machine；Android 保留 Intent/Biometric/Window/WorkManager/APK adapter；Desktop 保留 argv/loopback IPC、Voyager、AWT/OS credential/window/installer adapter。所有 capability 都返回结构化结果并进入用户可见反馈。

**技术栈：** Kotlin Multiplatform、Coroutines/Flow、Voyager、Compose Desktop、OkHttp/MockWebServer、Injekt、Android JVM/Emulator、Windows/macOS/Linux 系统命令与仓库构建脚本。

## 执行状态

以下编号全部是父 roadmap **Task 5A** 内部的子 Task，不是父 roadmap 在 Task 6 之后新增的同级任务。

- [x] Task 1：固定原版 fixture 与 shared 外部动作/安全契约
- [x] Task 2：当前 Android 外部动作与分享消费 shared
- [x] Task 3：当前 Android 应用锁与屏幕安全消费 shared
- [x] Task 4：Desktop 源 URI/备份/仓库动作解析
- [x] Task 5：Desktop 外部动作导航、入口与可见反馈
- [x] Task 5R：Desktop 外部动作非阻塞反馈收口
- [x] Task 6：Desktop 单实例安全转发
- [x] Task 7：Windows/macOS/Linux URI scheme 注册
- [x] Task 8A：Desktop 分享 fallback、Reader/Manga wiring 与真实反馈
- [x] Task 8B：macOS 原生分享异步生命周期
- [x] Task 8C：production JXA 分享终态可执行验证
- [x] Task 9A：Desktop credential namespace 与安全 CharArray API
- [x] Task 9B：Desktop credential-backed 应用锁核心
- [x] Task 10：Desktop Security 设置与 unlock UI
- [x] Task 10A：Desktop 通知隐私、telemetry 与 Widget capability 边界
- [x] Task 11：Desktop 窗口隐私能力与真实反馈
- [x] Task 12：固定原版发布语义与当前 Android 兼容
- [x] Task 13A：Desktop release discovery 与 canonical 包契约
- [x] Task 13B：无兼容包 shared 结果与当前 Android 兼容
- [x] Task 13C：Desktop 安全下载、临时路径与 SHA-256
- [x] Task 13D：Desktop 签名验证与平台安装交接
- [x] Task 13E：Desktop 更新控制器状态机
- [x] Task 14：Desktop 更新 UI、DI 与 Test Mode wiring
- [x] Task 14A：Desktop verifier 可取消进程边界
- [x] Task 14B：Desktop updater 应用生命周期所有权
- [ ] Task 15：Widget 豁免、parity 证据与维护文档
- [ ] Task 16：独立最终审查与三平台 change verify

## 全局任务门禁

以下规则适用于 Task 1–15，不为每项重复创建“准备/审查/收尾”子任务：

1. 协调者先从本计划复制当前 Task 的最小上下文给一个实现代理；实现代理先执行指定 RED，并证明失败来自缺失/错误 production 行为。
2. 实现代理完成最小 GREEN 和重构，运行定向测试、`git diff --check` 与范围检查；协调者独立复跑关键命令。
3. 只显式暂存当前 Task 文件并提交。始终排除既有用户改动 `AGENTS.md`、`AppVersion.kt`、`DownloadQueueScreen.kt`、SDK/Gradle/构建目录和无关未跟踪文件。
4. 一个未参与实现的审查代理检查固定原版 provenance、shared/platform 边界、production wiring、测试有效性、用户反馈、安全与提交范围。
5. 审查若有 Critical/Important，交回同一实现代理按 RED→GREEN 修复并提交，随后最多复审一次；仍未通过则在本计划中重新拆分当前产品风险，不无限追加 closure/review Task。
6. 只有实现、测试、提交和审查全部通过，协调者才勾选当前 Task，并在 `.superpowers/sdd/progress.md` 记录提交和验证证据；随后进入下一 Task。
7. 普通 Task 不递增 Desktop 版本、不运行全量构建；全量测试、版本构建和真实三平台验收集中在 Task 16。

## 父 roadmap 映射

| 父 Task 5A Step | 本计划 Task |
|---|---|
| Step 1 固定原版 fixture / shared RED | 1、12 |
| Step 2 OS capability / 豁免 RED | 7、10A、11、15 |
| Step 3 shared URI/share/security/release | 1–3、12 |
| Step 4 scheme / 单实例 | 4–7 |
| Step 5 share / credential app lock | 8A、8B、9–10 |
| Step 6 窗口隐私 | 11 |
| Step 7 更新状态机与 side effect | 12、13A–13E、14–14B |
| Step 8 Widget 豁免 | 3、10A、15 |
| Step 9 UI / 确认 / 错误 / 导航 / DI | 5、8A、8B、10、10A、14、14B |
| Step 10 三 OS 与 Windows 集成 | 16 |
| Step 11 parity 81–86、92 | 15、16 |

### Task 1：固定原版 fixture 与 shared 外部动作/安全契约

**Risk axis:** platform-contract

**Platform boundary:** shared

**Estimated scope:** 6 files, 360 lines

**Verification:** `./gradlew :domain:jvmTest --tests "mihon.domain.platform.PlatformParityContractTest" --tests "mihon.domain.security.AppSecurityPolicyTest"`

**Files:**

- Create: `domain/src/commonMain/kotlin/mihon/domain/platform/ExternalAction.kt`
- Create: `domain/src/commonMain/kotlin/mihon/domain/platform/ExternalShare.kt`
- Create: `domain/src/commonMain/kotlin/mihon/domain/security/AppSecurityPolicy.kt`
- Create: `domain/src/commonTest/kotlin/mihon/domain/platform/PlatformParityContractTest.kt`
- Create: `domain/src/commonTest/kotlin/mihon/domain/security/AppSecurityPolicyTest.kt`
- Create: `domain/src/commonTest/resources/parity/task5a/fixed-main-platform-fixtures.json`

**Consumes:** fixed-main 的 `MainActivity.handleIntentAction`、`DeepLinkScreenModel`、`IntentExtensions.toShareIntent`、`SecureActivityDelegate`、`SecurityPreferences` 和 `Window.setSecureScreen`。

**Produces:** 不依赖 OS/UI 的 ExternalAction、SharePayload、AppLockPolicy、SecureScreenPolicy 和带 ref/path/symbol 的 fixture。

1. RED：fixture 覆盖搜索 query 优先级、空输入 no-op、canonical `tachiyomi://add-repo?url=<https-url>`、未知 scheme/host/path、缺/重复/非 HTTP(S) query、`.tachibk`、source URL 作为搜索文本、无结果 fallback、HTTP/content 分享、锁延迟 `-1/0/>0`、首次必锁、关闭时间清除和 secure-screen 三态组合；Android SEARCH/SEND 明确标为 action 而非 URI scheme。
2. 先运行两个新测试，确认因 shared 类型/行为缺失失败；不能以 fixture JSON 存在或字符串扫描作为 RED。
3. GREEN：实现纯模型和纯函数；不得引用 Intent、URI grant、Window、Compose、Voyager、AWT 或 Desktop OS 类型。
4. 对原版未定义的非法输入补安全拒绝属于 cross-platform correctness；必须在 fixture 中标记为显式加固，不得反写成原版行为。
5. 重构后运行 Verification、`:domain:jvmTest` 相关回归和 `git diff --check`。

**Evidence:** 实现提交 `39c99e93d`，审查修复提交 `355122cff`；RED 由缺失 `onUnlockAuthentication` 等 shared contract 符号触发，GREEN 为 `PlatformParityContractTest` 3/3 与 `AppSecurityPolicyTest` 6/6。`:domain:spotlessCheck`、focused `:domain:jvmTest`、`git diff --check` 和 fixed-main 六条 ref/path `git cat-file -e` 均通过；独立修复复审 APPROVED，Critical/Important/Minor `0/0/0`。最终范围 6 files / 400 lines。

### Task 2：当前 Android 外部动作与分享消费 shared

**Risk axis:** android-external-action

**Platform boundary:** shared+android

**Estimated scope:** 6 files, 340 lines

**Verification:** `./gradlew :app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.ui.deeplink.AndroidExternalActionSharedWiringTest" --tests "eu.kanade.tachiyomi.util.system.AndroidSharePayloadAdapterTest"`

**Files:**

- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/deeplink/DeepLinkActivity.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/deeplink/DeepLinkScreenModel.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/util/system/IntentExtensions.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/ui/deeplink/AndroidExternalActionSharedWiringTest.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/util/system/AndroidSharePayloadAdapterTest.kt`

**Consumes:** Task 1 shared contracts；fixed-main Android Intent/Activity 行为。

**Produces:** 当前 fork Android 作为真实 consumer/adapter，默认结果与 fixed-main fixture 一致。

1. RED：用真实 production mapping 验证 QUERY 优先于 EXTRA_TEXT、空值 no-op、source URI NoResults 回退、backup/add-repo 目标和 HTTP/content 分享字段；破坏 shared delegate 时测试必须失败。
2. GREEN：Intent 只负责读取 action/extras/Uri、授权和 Activity flags；业务分类与 share payload 委托 Task 1。
3. 保留 Android ReaderActivity、Content URI、chooser、ClipData 与读权限；不为了 Desktop 把平台 side effect 移入 common。
4. 重构后运行 Verification、相关 DeepLink/Intent 回归和 `git diff --check`。

**Evidence:** 实现提交 `50ffcb07c`，审查修复提交 `b3df26ef0`；RED 由缺失 `toExternalAction`、`navigateExternalAction`、分享和 forwarding production seam 触发。最终 focused 测试 `AndroidExternalActionSharedWiringTest` 3/3、`AndroidSharePayloadAdapterTest` 4/4，`:app:spotlessCheck` 与 `git diff --check` 通过；shared parser/share delegate 调用次数、普通 Navigator push、fixed-main unsupported share envelope 与原 Intent 转发均有 production-path 保护。独立修复复审 APPROVED，Critical/Important/Minor `0/0/0`；最终范围 6 files / 339 touched lines。

### Task 3：当前 Android 应用锁与屏幕安全消费 shared

**Risk axis:** android-security-policy

**Platform boundary:** shared+android

**Estimated scope:** 9 files, 500 lines

**Split waiver:** `presentation-widget` 当前没有 JVM 单测依赖；本 Task 的 production Widget 隐私门禁与其真实 production-wiring 测试必须在同一编译单元内交付，因此需同时修改模块构建文件。独立审查还要求 Android lifecycle/window/settings 与 Widget Base/Manager 的 consumer wiring 可被行为测试杀死，使最终范围增至约 500 行。上述 adapter、consumer 与测试反复修改同一组 production 文件且共同闭合单一安全边界，拆分会产生无法独立验证的中间态，或重复加载同一上下文与运行同一测试矩阵。

**Verification:** `./gradlew :app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.ui.security.AndroidSecuritySharedPolicyTest" --tests "eu.kanade.tachiyomi.ui.security.AndroidSecuritySettingsWiringTest" && ./gradlew :presentation-widget:testReleaseUnitTest --tests "tachiyomi.presentation.widget.WidgetPrivacyProductionWiringTest"`

**Files:**

- Modify: `presentation-widget/build.gradle.kts`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/base/delegate/SecureActivityDelegate.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSecurityScreen.kt`
- Create: `presentation-widget/src/main/java/tachiyomi/presentation/widget/WidgetPrivacyDataSource.kt`
- Modify: `presentation-widget/src/main/java/tachiyomi/presentation/widget/WidgetManager.kt`
- Modify: `presentation-widget/src/main/java/tachiyomi/presentation/widget/BaseUpdatesGridGlanceWidget.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/ui/security/AndroidSecuritySharedPolicyTest.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/ui/security/AndroidSecuritySettingsWiringTest.kt`
- Create: `presentation-widget/src/test/java/tachiyomi/presentation/widget/WidgetPrivacyProductionWiringTest.kt`

**Consumes:** Task 1 AppLockPolicy/SecureScreenPolicy；fixed-main SecureActivityDelegate 与设置可用性矩阵。

**Produces:** 当前 Android lifecycle/Biometric/Window adapter 不再私有复制锁延迟和 secure-screen 决策；真实 Widget 数据链在锁启用时拒绝查询/展示更新内容。

1. RED：覆盖首次启动、`-1/0/>0`、已锁不覆盖关闭时间、恢复后删除时间、认证失败保持锁定、Unsupported 关闭开关和三态 window flag；Widget production data source 在锁开启时必须对 `GetUpdates` 保持 0 调用，manager 的刷新 identity 必须包含 lock state。
2. GREEN：shared 决定锁/保护布尔结果；Android 只执行生命周期、BiometricPrompt、Activity finish 和 `FLAG_SECURE`。
3. 设置页仍在变更锁开关/延迟前认证；通知隐藏和 telemetry 项不因本 Task 回退。
4. Widget gate 必须被 `BaseUpdatesGridGlanceWidget` 真实消费；不得用 Desktop 测试、源码扫描或测试内复制逻辑证明 Android Glance 隐私。
5. 运行 Verification、相关 Android security/widget 回归、`git diff --check`。

**Evidence:** 实现提交 `8fa6fa9a4`，独立审查修复提交 `f072c3fd1`；初始 RED 由缺失 shared security adapter、Widget privacy data source 和模块测试依赖触发，审查修复 RED 则证明旧 production 缺少 lifecycle/window/settings 与 Widget Base/Manager 的可杀死 consumer seam。最终 `AndroidSecuritySharedPolicyTest` 4/4、`AndroidSecuritySettingsWiringTest` 2/2、`WidgetPrivacyProductionWiringTest` 2/2；Widget 强制重跑验证 `Locked → Content → Locked`、refresh identity `true → false → true`、初始锁定时 `GetUpdates` 0 调用及解锁后恢复查询。`:app:spotlessCheck`、`:presentation-widget:spotlessCheck`、提交范围与 diff check 通过；独立修复复审 APPROVED，Critical/Important/Minor `0/0/0`。最终范围 9 files / 449 touched lines，符合具体 Split waiver；全量与运行时验证留在 Task 16。

### Task 4：Desktop 源 URI、备份与仓库动作解析

**Risk axis:** desktop-action-resolution

**Platform boundary:** shared+desktop

**Estimated scope:** 7 files, 400 lines

**Verification:** `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.platform.DesktopDeepLinkHandlerTest"`

**Files:**

- Create: `app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopDeepLinkHandler.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopExternalActionTarget.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/domain/SaveSourceMangaForDetails.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/browse/GlobalSearchScreen.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/backup/BackupRestoreScreenModelFactory.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/platform/DesktopDeepLinkHandlerTest.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/platform/DesktopDeepLinkProductionWiringTest.kt`

**Consumes:** Task 1 ExternalAction；现有 SourceManager/ResolvableSource、漫画落地、章节同步、备份恢复和扩展仓库能力。

**Produces:** 类型安全的 GlobalSearch/Manga/Chapter/Backup/ExtensionRepo/Rejected 目标，不包含 Voyager 或 OS 注册。

1. RED：用代表性 CatalogueSource 验证首个可解析源、漫画/章节解析、数据库落地、NoResults → GlobalSearch、非法 backup 路径拒绝和 add-repo 参数。
2. 测试必须经过真实 handler 与已有 use case/repository wiring；不能在测试复制 URI 分类。
3. GREEN：复用现有服务，不另建第二套源搜索、漫画写入或章节同步；错误映射为结构化结果。
4. 运行 Verification、相关 source/browse/backup 回归和 `git diff --check`。

**Evidence:** 实现提交 `770b59b0a`。首轮 RED 因缺失 handler、target 与 production consumer seam 编译失败；固定 main 复核后的第二轮 RED 在 5 项 Handler 测试中准确暴露 Manga 额外详情/章节请求、Chapter 重复同步和取消被吞 3 个偏差。最终新测试 8/8；强制重跑 `DesktopDeepLinkHandlerTest`、`DesktopDeepLinkProductionWiringTest`、`SaveSourceMangaForDetailsTest`、`BackupRestoreScreenModelTest`、`GlobalSearchResultNavigationTest`、`GlobalSearchSourceFilterWiringTest` 共 31/31，0 failure/error/skip。根 `spotlessCheck`、提交范围与 diff check 通过；独立审查 APPROVED，Critical/Important/Minor `0/0/0`。Manga 只走既有落地，Chapter 先查本地且缺失才同步，取消继续传播；备份仅接受真实存在、非符号链接的本地普通 `.tachibk` 文件。最终范围 7 files / 387 touched lines；导航与 OS 注册留后续 Task。

### Task 5：Desktop 外部动作导航、入口与可见反馈

**Risk axis:** desktop-action-navigation

**Platform boundary:** desktop

**Estimated scope:** 10 files, 590 lines

**Split waiver:** Task 4 的 Backup target 只有由现有 `BackupSettingsScreen` 调用 target-aware factory 才能显示所选文件，ExtensionRepo target 也只有由现有 `ExtensionRepoScreen` 打开并预填原有确认对话框才不会丢失 URL 或绕过用户确认。因此本 Task 必须同时修改这两个真实 consumer；它们与单一 navigator/pending/feedback 状态机及同一导航测试矩阵反复修改相同边界，拆分会产生“已导航但 payload 丢失”或“自动添加绕过确认”的不可验收中间态。独立审查进一步证明单槽 pending 会在 Task 6 的连续转发入口静默丢失动作；FIFO、取消回队首与既有单一 consumer/恰好一次测试必须在本 Task 内共同修复，否则下一 Task 会建立在已知丢动作的基础上。

**Verification:** `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.ExternalActionNavigationTest" --tests "mihon.desktop.ui.ScreenInstantiationSmokeTest"`

**Files:**

- Modify: `app-desktop/src/main/kotlin/mihon/desktop/Main.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/DesktopUiDependencies.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/ui/ExternalActionNavigator.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/home/HomeScreen.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/settings/BackupSettingsScreen.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/settings/ExtensionRepoScreen.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/test/state/TestState.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/ui/ExternalActionNavigationTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/ScreenInstantiationSmokeTest.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/ui/ExternalActionFeedbackWiringTest.kt`

**Consumes:** Task 4 类型安全目标；现有根 Navigator、Screen、Snackbar/TestState。

**Produces:** 冷启动 argv 动作进入 production Navigator，运行结果和拒绝错误对用户/Test Mode 可观察。

1. RED：实例化每个目标 Screen；验证普通 Navigator/TabNavigator 类型兼容；测试启动前 pending action 在根 Navigator 就绪后只消费一次。
2. GREEN：Main 只提交原始 action；handler/resolver 后由单一 navigator adapter 执行，不能在多个 Screen 重复分类。
3. NoResults 导航 GlobalSearch；Rejected/Failed 显示本地化错误且不部分导航；成功动作清除 pending state。
4. 运行 Verification、导航/DI/TestState 回归和 `git diff --check`。

**Evidence:** 实现 `6dd8bdfb0`，FIFO 修复 `6cd48ccee`。冷启动原始 argv 经共享 parser/Task 4 resolver 进入 Home 内层普通 Voyager Navigator；五类 target 保留 query/ID/文件/URL payload，Chapter 复用真实 MangaDetail reader request，Backup 使用 target-aware factory，add-repo 只首次预填并保留确认。首轮审查发现单槽会覆盖连续动作，RED 证明就绪前、解析挂起和取消三类丢动作；修复后 FIFO、单 consumer、取消回队首与 Rejected/Failed 后继续 drain 均通过。该 Task 的反馈生命周期残余按规则拆到 Task 5R；最终联合验证与 Task 5R 一并通过。

### Task 5R：Desktop 外部动作非阻塞反馈收口

**Risk axis:** desktop-action-feedback

**Platform boundary:** desktop

**Estimated scope:** 4 files, 300 lines

**Verification:** `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.ExternalActionNavigationTest" --tests "mihon.desktop.ui.ExternalActionFeedbackWiringTest" --rerun-tasks`

**Files:**

- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/ExternalActionNavigator.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/home/HomeScreen.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/ExternalActionNavigationTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/ExternalActionFeedbackWiringTest.kt`

**Consumes:** Task 5 FIFO drain、终态记录与 Home `SnackbarHostState`。

**Produces:** 终态动作与 Snackbar 生命周期解耦；反馈显示期间后续动作继续消费，已终态动作不因 UI scope 取消而重放。

1. RED：使用会挂起的反馈消费者证明 A Rejected 后 B Success 不等待反馈；取消反馈并重建 consumer 后 A 不重复。真实 Home Compose 场景在错误 Snackbar 仍显示时继续消费后续成功动作。
2. GREEN：navigator 只发布非挂起反馈事件，并在发布前记录动作终态；Home 使用有界反馈队列和单一 lifecycle consumer 显示 Snackbar，满载时丢弃最旧反馈，动作 drain 不等待 Snackbar 生命周期且不会创建无界挂起 job。
3. 保持 resolver/destination 取消时当前动作回队首、后续动作保留；终态后的 UI 取消不得重新入队或重复反馈。
4. 运行 Verification、既有 50 项 Task 5 回归、根 Spotless 和 `git diff --check`。

**Evidence:** 非阻塞反馈实现 `e880ab5d8`，有界队列修复 `a28e6d2bc`。Task 5 复审先证明 await `showSnackbar` 会阻塞 drain，Task 5R 首轮审查再发现逐消息 launch 会积累无界挂起 job；最终 Home 使用容量 8、`DROP_OLDEST` 的 Channel 和单一 `LaunchedEffect` consumer，navigator 只 `tryPublish`，dispose 关闭 channel。RED/变异测试分别杀死 await-feedback 与 per-message-launch 旧 wiring；协调者强制重编译 Navigation 8 + Feedback 7 + Screen 40 = 55/55，0 failure/error/skip，根 Spotless 与 diff check 通过。独立修复复审 APPROVED，Critical/Important/Minor `0/0/0`；最终范围 4 files/296 touched lines。

### Task 6：Desktop 单实例安全转发

**Risk axis:** single-instance-ipc

**Platform boundary:** desktop

**Estimated scope:** 6 files, 850 lines

**Split waiver:** owner 选举、认证/有界传输协议、ACK、崩溃接管、Runtime 生命周期和 Main 的 secondary 退出判断构成同一个原子安全边界。拆分会产生“已监听但未绑定真实 ingress”“secondary 仍启动服务/UI”或“新 owner 状态被旧 owner 清理”的不可验收中间态；安全与并发 RED 也必须共同作用于同一 broker/state codec，因此保留为一个 Task、严格限制在列出的 6 个文件内。

**Verification:** `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.platform.DesktopExternalActionBrokerTest" --tests "mihon.desktop.DesktopAppRuntimeTest"`

**Files:**

- Create: `app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopExternalActionBroker.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/DesktopAppRuntime.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/Main.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopPlatformPaths.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/platform/DesktopExternalActionBrokerTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/DesktopAppRuntimeTest.kt`

**Consumes:** Task 5 action ingress；Desktop runtime/paths。

**Produces:** 只监听 loopback、带 owner/token/长度限制/ACK 的单实例 broker，关闭时无残留 owner 状态。

1. RED：真实 loopback 测试 owner 选举、第二实例转发、并发动作顺序、伪 token、超长/畸形消息、owner crash 后接管和 runtime close。
2. GREEN：只有 owner 启动 UI/runtime；secondary 等待明确 ACK 后退出。token/port 文件权限尽可能收紧，日志不记录 payload secret。
3. broker 不解析业务动作，只传输有界字符串；所有验证仍由 shared parser/handler 完成。
4. 运行 Verification、重复启动/关闭压力测试和 `git diff --check`。

**Evidence:** 实现提交 `cccd277299`。broker 仅绑定 IPv4 loopback，使用随机 token、有界长度帧和 requestId 匹配 ACK；单 owner 文件锁、原子状态写入、stale endpoint 有界重试接管、ownerId 防旧实例误删、权限 best-effort、临时文件清理与重复 close 均由真实 loopback/文件测试覆盖。Main 仅在 owner 回调中初始化 DI/runtime/UI，secondary 收到 ACK 后直接退出；production helper 将 broker 原始字符串接入 Task 5 `ExternalActionInput.ViewUri`，runtime close 释放 broker。实现代理最终组合 30/30、broker 另行压力 3 轮、根 Spotless 与 diff check 通过；协调者强制重跑 Broker 11 + Runtime 11 + Task 5 Navigation 8 = 30/30，根 Spotless 通过。独立审查 APPROVED，Critical/Important/Minor `0/0/0`；最终范围 6 files/808 touched lines，未运行全量 Desktop 套件或递增版本构建，留待 Task 16。

### Task 7：Windows、macOS、Linux URI scheme 注册

**Risk axis:** uri-scheme-registration

**Platform boundary:** desktop

**Estimated scope:** 8 files, 750 lines

**Split waiver:** 三 OS 元数据、可探测注册 adapter 与 owner-only 启动接线共同组成同一个 URI scheme capability；如果把 `Main` production wiring 拆到后续 Task，本 Task 会留下只能被测试直接实例化、Windows/Linux 实际从不注册的死基础设施。8 个文件反复共享同一 scheme/当前可执行文件契约，且必须用同一 mutation test 证明删除启动接线会失败，因此保留为单 Task。

**Verification:** `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.platform.DesktopUriSchemeRegistrationTest"`

**Files:**

- Create: `app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopUriSchemeRegistration.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/Main.kt`
- Modify: `app-desktop/build.gradle.kts`
- Create: `app-desktop/src/main/resources/platform/windows/tachiyomi-url-protocol.reg.template`
- Create: `app-desktop/src/main/resources/platform/linux/mihon-desktop.desktop`
- Create: `app-desktop/src/main/resources/platform/macos/tachiyomi-url-types.plist`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/platform/DesktopUriSchemeRegistrationTest.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/platform/DesktopUriSchemeCapabilityTest.kt`

**Consumes:** Task 6 broker；Compose Desktop native distribution 配置。

**Produces:** 三 OS 可探测注册 adapter 与打包元数据；不把“写入成功”当作“动作链成功”。

1. RED：fixture 精确要求三 OS 注册 canonical `tachiyomi` scheme，并只接受 `tachiyomi://add-repo?url=<https-url>`；覆盖缺/重复/非 HTTP(S) query、未知 host/path、未注册 alias、Windows HKCU URL Protocol、macOS bundle URL types、Linux desktop entry/xdg-mime，以及无权限、命令缺失、非打包运行。
2. GREEN：生产 adapter 仅注册当前可执行文件/应用包；路径参数严格转义，卸载/重装不会留下指向旧 BUILD 的入口。只有 Task 6 选出的 owner 执行注册，删除 `Main` 的 production 调用时 wiring 测试必须失败。
3. capability/result 必须结构化，不能把“命令返回成功”冒充完整动作链成功；真实 OS 协议启动留到 Task 16，未验证平台不得标记完成。
4. 运行 Verification、打包配置静态检查和 `git diff --check`。

**Evidence:** 实现提交 `2ab6c0eb3`，打包判定/模板防漂移修复 `f7ae98520`。Windows owner 以 HKCU `URL Protocol` 和覆盖式 `reg add /f` 注册 canonical `tachiyomi`，资源模板渲染后的完整 registry entry 集合必须与实际命令等价；Linux 原子写入带 `%u`/`x-scheme-handler/tachiyomi` 的 desktop entry 并执行 desktop database/xdg-mime；macOS plist 片段通过 Compose `infoPlist.extraKeysRawXml` 进入打包配置。只允许真实 jpackage launcher 文件及三平台 marker/runtime 布局，普通 binary、错误目录、缺 marker/runtime、命令缺失、权限/命令/意外失败均返回结构化结果；只有 Task 6 owner 执行注册，registration/report 异常不阻止 owner 应用。`Configured.endToEndActionVerified` 保持 `false`，未冒充真实 OS 动作链验收。实现与修复最终 Task 7 12/12 + Runtime 11/11，根 Spotless/diff/scope 通过；协调者强制重跑同一 23/23、根 Spotless 与 diff check 通过。唯一修复复审 APPROVED，Critical/Important/Minor `0/0/0`；累计范围 8 files/743 touched lines，真实 Windows/macOS/Linux URI→broker→导航留待 Task 16。

### Task 8A：Desktop 分享 fallback、Reader/Manga wiring 与真实反馈

**Risk axis:** desktop-share-fallback

**Platform boundary:** shared+desktop

**Estimated scope:** 8 files, 800 lines

**Split waiver:** clipboard/save fallback、结构化结果、Reader 的 Share/Copy/Save 入口和 Manga production wiring 共同决定同一次用户动作的真实反馈；service 与两个入口反复共享同一 action/result 契约，拆开会形成“有 UI 无 side effect”或“有 adapter 无消费者”的不可验收中间态。macOS native helper 的异步生命周期不再混入本 Task，独立交给 Task 8B。

**Verification:** `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.platform.DesktopShareServiceTest" --tests "mihon.desktop.ui.library.MangaShareWiringTest"`

**Files:**

- Create: `app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopShareService.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/DesktopUiDependencies.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/library/MangaDetailComponents.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/library/MangaDetailScreen.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/reader/PageContextMenu.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/platform/DesktopShareServiceTest.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/ui/library/MangaShareWiringTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/reader/PageContextMenuActionTest.kt`

**Consumes:** Task 1 SharePayload；现有 Manga/Reader 分享入口和 clipboard 能力。

**Produces:** copy/save/cancel/unavailable/failed 的真实结构化结果、Reader/Manga UI 反馈和供 Task 8B 消费的 native-share port 边界；在 Task 8B 完成前 production native capability 必须明确 unavailable。

1. RED：覆盖 headless、clipboard busy、save cancel/成功/失败、HTTP 文本和本地文件 payload；移除 production service 注入、Manga action 或真实 Reader ContextMenu 消费时 wiring 测试必须失败。
2. GREEN：UI 不直接调用 Toolkit/Desktop；service 在 native unavailable 时选择 clipboard/save fallback，只有确认 side effect 成功才显示成功。
3. Reader 保留独立 Share/Copy/Save/可选封面、受控最后分享图片缓存、`share_page_info`、默认保存目录和 best-effort reveal；fallback 文案必须说明实际发生了复制或保存。
4. 运行 Verification、详情/reader action 回归和 `git diff --check`。

**Evidence:** 实现提交 `cdb0f25ad`。production 与 DI 在所有 OS 均使用明确 unavailable 的 native port，macOS picker/process 生命周期留给 Task 8B；Manga action row 与真实 Reader `ContextMenuRepresentation` 通过 `LocalDesktopUiDependencies` 消费同一 service。Reader 保留独立 Share/Copy/Save/可选封面、`share_page_info`、最后共享图片缓存、默认目录保存和 best-effort reveal，fallback 只报告实际复制/保存/取消/失败。实现代理 focused 18/18、相关回归 24/24、根 Spotless 和 cached diff 通过；协调者单次强制验证 7 类 42/42、Spotless 和 diff 通过。独立审查 APPROVED，Critical/Important/Minor `0/0/0`；最终范围 8 files/792 touched lines。

**Replan evidence:** 原合并 Task 8 首审因 production native adapter 缺失和 wiring 测试过弱被拒绝；唯一修复复审又证明 macOS helper 把 picker `READY` 误报为分享完成，且取消/完成后的窗口与进程生命周期没有证据。已通过的 fallback/wiring 与未闭合的 macOS 异步生命周期属于两个可独立验证的产品风险，因此按门禁拆为 8A/8B，不在原 Task 无限追加修复轮。

### Task 8B：macOS 原生分享异步生命周期

**Risk axis:** macos-share-lifecycle

**Platform boundary:** desktop

**Estimated scope:** 10 files, 700 lines

**Split waiver:** picker 打开、选择、系统服务完成/失败、用户取消、helper 超时/退出、DI 单例和 UI 终态反馈组成一个异步会话；若把 process/session、production DI 与 Manga/Reader terminal feedback 分开，会暂时重新引入“已打开即成功”、macOS native adapter 静默断线或无人消费终态的错误链路。独立审查要求用真实 DI identity 测试保护 `DesktopAppModule → DesktopUiDependencies`，因此范围增至 10 个反复共享同一 native-share/session 契约的文件；Windows/Linux 不实现伪 native adapter，继续消费 8A 的诚实 fallback。exact production JXA 终态的可执行 macOS 证据因修复复审仍不足，独立拆为 Task 8C，不再扩大本 Task 的修复轮次。

**Verification:** `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.platform.MacOsNativeSharePortTest" --tests "mihon.desktop.platform.DesktopShareServiceTest" --tests "mihon.desktop.di.DesktopDiWiringTest" --tests "mihon.desktop.ui.library.MangaShareWiringTest"`

**Files:**

- Create: `app-desktop/src/main/kotlin/mihon/desktop/platform/MacOsNativeSharePort.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopShareService.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/DesktopUiDependencies.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/library/MangaDetailComponents.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/reader/PageContextMenu.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/platform/MacOsNativeSharePortTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/platform/DesktopShareServiceTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/library/MangaShareWiringTest.kt`

**Consumes:** Task 8A native-share port、SharePayload/local-file message、notification wiring；Apple `NSSharingServicePickerDelegate` 与 `NSSharingServiceDelegate` 生命周期。

**Produces:** macOS picker `Opened`、真正 `Shared`、`Cancelled`、`Failed` 的异步会话与确定的 helper/window/process cleanup；Windows/Linux 保持 unavailable fallback。

1. RED：`READY` 只能得到 `Opened`，不得得到完成；覆盖选择服务后 `didShare`、`didFail`、取消/空 service、picker/helper EOF、启动超时、终态超时、重复/乱序输出和进程销毁。
2. GREEN：JXA 通过 picker delegate 和 sharing-service delegate 输出单一终态；关闭 picker/window 并终止 helper。runner 持有 process/session 直到终态或超时，任何非成功路径均清理。
3. Manga/Reader 在 `Opened` 时只依赖可见系统面板或中性“已打开”反馈；只有 `didShare` 才发布完成，取消/失败发布对应终态。payload 继续以独立 argv 传入，禁止插值、mail/browser/open 冒充 share。
4. 运行 Verification、`git diff --check`、根 Spotless；通过 `ssh mbp`（失败再 `mbp-lan`）验证真实 picker 的打开、取消后 helper 退出和一次可执行分享终态，记录 PID/exit/输出，正式打包验收仍留 Task 16。

**Evidence:** 实现提交 `07bf54191`。launch contract 只有 `Opened(session) / Unavailable / Failed`，`SharedNatively` 只来自 session 的 `Shared` 终态；Manga/Reader 对 Shared/Cancelled/Failed 分别反馈。`DesktopAppModule` 单例注册 native port/service，`DesktopUiDependencies.fromInjekt()` 解析同一实例；Windows/Linux 保持明确 unavailable fallback。首轮审查发现 JXA selector 参数和 DI wiring 盲区，唯一修复关闭两项后又把 exact production JXA 终态证据拆到 8C。协调者强制执行 8 类 54 tests、补跑 PageContextMenu 6 tests，合计实际执行 59、跳过 mac-only 1、failure/error 0；根 Spotless 与 diff 通过。原生 AppKit 无副作用 probe 分别得到 `READY→SHARED→EXIT 0→PID dead` 与真实 picker `READY→CANCELLED→EXIT 0→PID dead`；打包应用交互仍留 Task 16。

### Task 8C：production JXA 分享终态可执行验证

**Risk axis:** macos-share-jxa-terminal-proof

**Platform boundary:** verification

**Estimated scope:** 2 files, 180 lines

**Verification:** Windows 执行 `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.platform.MacOsNativeSharePortTest"`；macOS 通过 `ssh mbp`（失败再 `mbp-lan`）执行同一测试类中的 tagged native probe，并记录真实 production JXA `READY → SHARED/FAILED → exit → PID dead`。

**Files:**

- Modify: `app-desktop/src/main/kotlin/mihon/desktop/platform/MacOsNativeSharePort.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/platform/MacOsNativeSharePortTest.kt`

**Consumes:** Task 8B 的固定 production JXA 脚本、`MihonSharingDelegate`、runner/session/protocol cleanup；首轮修复复审确认的唯一剩余 Important 缺口。

**Produces:** 可重复、无外部 side effect、仅在 macOS 执行的 exact production JXA bridge 终态探针；证明真实 `ObjC.registerSubclass` delegate 能输出 runner 消费的单一终态并回收 helper。它不创建第二套分享实现，也不替代 Task 16 的打包 UI 交互验收。

1. RED：macOS native probe 必须执行 production JXA 中同一个 `MihonSharingDelegate`；只伪造 stdout、扫描脚本文本或仅运行 Swift/AppKit 等价实现均不算通过。
2. GREEN：测试只向固定 production script 注入本地、无外部副作用的确定性终态触发点，仍通过真实 `/usr/bin/osascript`、production runner/session 和真实 registered delegate；不得发送邮件/消息、修改用户数据或留下临时文件。
3. 断言 `Opened` 不等于完成、终态只交付一次、真实输出为 `MIHON_SHARE:READY` 后 `MIHON_SHARE:SHARED` 或 `FAILED`、进程在有界时间内退出且 PID 不存活；Windows/Linux 测试继续只验证 unavailable 和脚本构造，不伪报 native runtime。
4. 运行 Verification、macOS tagged probe、根 Spotless 和 `git diff --check`；独立审查通过后与 Task 8B 一并勾选。

**Evidence:** 与 8B 共用实现提交 `07bf54191`，本 Task 在同两个文件净增 33 行。macOS-only integration test 从同一 `MAC_OS_NATIVE_SHARE_SCRIPT` 只在 READY 后注入 `sharingDelegate.sharingServiceDidShareItems(null, items)`，真实执行 production `ObjC.registerSubclass` delegate、runner/session/protocol 与 cleanup；Windows focused 编译及非 mac runner tests 通过。`ssh mbp` 无现有 clone，按计划用 stdin 执行当前 production script 的同一 probe，得到 `PID 90848 / MIHON_SHARE:READY / MIHON_SHARE:SHARED / EXIT 0 / ALIVE:no`，无文件、外部发送或用户数据修改。独立审查 APPROVED，Critical/Important/Minor `0/0/0`；Task 8B/8C 累计范围 10 files/698 touched lines。

### Task 9A：Desktop credential namespace 与安全 CharArray API

**Risk axis:** desktop-credential-namespace

**Platform boundary:** desktop

**Estimated scope:** 2 files, 140 lines

**Verification:** `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.platform.PlatformCredentialBackendTest"`

**Files:**

- Modify: `app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopCredentialStore.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/platform/PlatformCredentialBackendTest.kt`

**Consumes:** 现有 tracker 使用的 DPAPI/Keychain/Secret Service backend。

**Produces:** 向后兼容 tracker 的 CharArray API，以及 Windows、macOS、Linux 均真实隔离的 versioned app-lock credential namespace。

1. RED：同 account 的 tracker 与 app-lock secret 必须落入不同 Windows preference node、macOS Keychain service 和 Linux Secret Service attribute；删除任一 namespace 不得影响另一方。
2. GREEN：保留 tracker 的既有 service/node 和 String API；新增 app-lock v1 namespace 与会清零副本的 CharArray API，不增加 plaintext/file/preference fallback。
3. 运行 Verification、真实 Windows backend tagged round-trip/isolation（本机可用时）、tracker credential 回归和 `git diff --check`。

**Evidence:** 实现提交 `95aea97b4`。tracker 默认 Windows `v2`、macOS/Linux `mihon-desktop-tracker` 保持兼容，app-lock 使用独立 Windows `app-lock/v1` 与三平台 service/attribute；CharArray save/withSecret 的正常和异常清零由 mutation RED 证明。协调者强制执行 credential + tracker 31/31、0 failure/error/skip，真实 Windows DPAPI 用例实际运行 1.421s；根 Spotless 61 tasks 与 diff 通过。唯一修复复审 APPROVED，Critical/Important/Minor `0/0/0`；最终范围 2 files/200 touched lines。

### Task 9B：Desktop credential-backed 应用锁核心

**Risk axis:** desktop-app-lock

**Platform boundary:** shared+desktop

**Estimated scope:** 6 files, 488 lines

**Split waiver:** verifier/shared-policy state core 若不与 Desktop runtime、production DI 和 identity/namespace integration test 同时交付，会形成没有真实产品消费者的内部基础设施，违反 production wiring 完成条件，也不能独立验收。首审要求异常下完整 runtime cleanup 与可杀死的 DI namespace composition，新增约 130 行行为证据；六个文件反复共享同一 `DesktopAppLock`/verifier/runtime identity，拆分只会制造死中间态并重复同一测试矩阵，故保留为单一风险闭环。

**Verification:** `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.security.DesktopAppLockTest" --tests "mihon.desktop.di.DesktopDiWiringTest"`

**Files:**

- Create: `app-desktop/src/main/kotlin/mihon/desktop/security/DesktopAppLock.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/security/DesktopPassphraseVerifier.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/DesktopAppRuntime.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/security/DesktopAppLockTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt`

**Consumes:** Task 1 `AppLockPolicy`；单一 `SecurityPreferences`；Task 9A 的 app-lock v1 protected credential namespace。

**Produces:** passphrase verifier、启动/离开/恢复/关闭状态机和 fail-closed DI service。

1. RED：首次必锁、`-1/0/>0`、关闭时间写入/清除、成功/错误/取消、backend unavailable、重设/删除、并发验证和 secret 清零。
2. GREEN：复用单一 SecurityPreferences；不能在 DesktopAppPreferences 复制 key。verifier 存入 OS protected backend，普通偏好不含明文、可逆 secret 或 hash material。
3. 运行 Verification、Desktop runtime/DI focused 回归、根 Spotless 和 `git diff --check`。

**Evidence:** 实现提交 `aa62b6882`。Desktop 复用 shared `AppLockPolicy` 与单一 `SecurityPreferences`，verifier 只消费 Task 9A 的 app-lock v1 backend；首次启动、`-1/0/>0`、非成功认证 fail-closed、set/reset/delete、并发与 secret 清零均由行为测试覆盖。首审发现 runtime 异常会中断既有清理及 DI namespace 不可杀死；唯一修复加入尽力逆序 cleanup/首异常与 suppressed 传播，以及真实 DI backend factory composition。协调者强制执行 Desktop lock/runtime/DI 28/28 与 shared policy 6/6，0 failure/error/skip；Spotless/diff 通过。最终复审 APPROVED，Critical/Important/Minor `0/0/0`；最终范围 6 files/488 touched lines，split waiver 如上。

### Task 10：Desktop Security 设置与 unlock UI

**Risk axis:** security-ui-wiring

**Platform boundary:** shared+desktop

**Estimated scope:** 10 files, 1519 lines

**Split waiver:** Security 设置、credential capability probe、passphrase 创建/确认/变更、凭据与偏好的非原子补偿、根窗口锁覆盖层、focus lifecycle、production bootstrap 顺序与 Test Mode 真实状态共同组成一个不可拆的用户闭环。只交付设置会产生“可启用但无法解锁”，只交付覆盖层会产生“有锁但无配置入口”，只改 `TestState` 又不能证明真实 `/test/state` 在服务开放时已经观察到 `runtime.start()` 后的锁状态。上述入口反复消费同一 `DesktopAppLock`/verifier/state，必须由同一 Compose/DI/Test Mode 矩阵证明锁定时 Home 从未构造、成功认证后恢复，并证明 headless HTTP 不暴露启动瞬态。首轮 650 行估算遗漏了四类非原子失败补偿、production bootstrap 可杀死 seam 及其故障注入测试；重规划前为 1424 touched lines，补齐可杀死的生产启动顺序与异常清理测试后最终为 1519 touched lines，不能拆成会暂时留下 enabled-without-verifier、假 lock state 或不可解锁根窗口的独立交付。

**Verification:** `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.settings.SecuritySettingsWiringTest" --tests "mihon.desktop.ui.ScreenInstantiationSmokeTest"`

**Files:**

- Create: `app-desktop/src/main/kotlin/mihon/desktop/ui/settings/SecuritySettingsScreen.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/ui/security/DesktopUnlockSurface.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/security/DesktopPassphraseVerifier.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/settings/MoreRootScreen.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/Main.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/test/state/TestState.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/test/http/TestHttpServer.kt`
- Modify: `i18n/src/commonMain/moko-resources/base/strings.xml`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/ui/settings/SecuritySettingsWiringTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/ScreenInstantiationSmokeTest.kt`

**Consumes:** Task 9 DesktopAppLock；原版 SettingsSecurityScreen 可用性矩阵。

**Produces:** More → Security、设置/确认/错误反馈和根级锁定覆盖层；Test Mode 可观察真实 lock state。

1. RED：导航类型/实例化、backend unavailable 禁用、启用/关闭/改延迟前验证、passphrase mismatch、锁定时受保护 Home 不渲染、成功解锁恢复。
2. RED：通过 production bootstrap seam 证明 `runtime.start()` 完成后才开放 Test Mode HTTP，并证明删除或错放 `DesktopAppLock`→`TestState` binding 会让 headless `/test/state` 行为测试失败；不得以手工 set state 或只组合 `DesktopProtectedRoot` 代替。
3. GREEN：根窗口只在 unlocked 时构造应用内容；锁 surface 不通过导航 back 绕过。设置变化失败恢复旧值并显示原因；production 启动顺序不得让 HTTP 观察到 runtime 初始化前的瞬态锁状态。
4. 所有新文案进入 MR；不把 Android “Biometric” 文案直接用于 Desktop passphrase/OS credential。
5. 运行 Verification、DI wiring、Compose wiring mutation 和 `git diff --check`。

**Replan evidence:** 首审发现 headless lock state 仅由 Compose `SideEffect` 写入，以及偏好写失败没有结构化补偿。唯一修复已用 persistence seam、四类故障注入、双回滚 fail-closed 与 CharArray 清零关闭设置事务风险；修复复审仍证明测试手工安装 binding，无法杀死 Main production wiring，且 Test Mode HTTP 早于 `runtime.start()` 开放。Task 10 保持未完成，不新增 closure Task；本次重规划只补上述 production bootstrap 风险和最终精确范围。

**Evidence:** 重规划提交 `d926f4a8d`，实现提交 `1319022cd`。首轮 RED 由缺失 Security Screen/controller/capability、More 导航、根锁覆盖层、focus listener 与真实 `appLocked` HTTP 状态触发；首审进一步发现 headless 假状态和 credential/preference 非原子失败。修复用 production persistence seam 覆盖 enable/disable/delay/change-passphrase 的写失败、补偿、双回滚 fail-closed 与 CharArray 清零；修复复审又证明手工 binding 测试无法杀死 Main 启动顺序。重规划后的 RED 因缺少 `bootstrapDesktopRuntime` 正确编译失败，GREEN 让 production 按 broker wiring/attach → lock-state binding → `runtime.start()` → Test Mode HTTP 开放，GUI/headless 共用可幂等关闭 session，并覆盖最终 locked、初始 locked→最终 unlocked 及 Test Mode 启动异常清理。协调者强制复跑 Security 17、Screen 41、AppLock 6、Runtime 11、DI 11、HTTP JSON 2，共 88/88，shared policy 6/6，0 failure/error/skip；根 Spotless、diff 与 TestState/TestHTTP secret scan 通过。重规划独立审查 APPROVED，Critical/Important/Minor `0/0/0`；最终范围 10 files/1519 touched lines。完整版本构建和 Windows/macOS 运行验收留至 Task 16。

### Task 10A：Desktop 通知隐私、telemetry 与 Widget capability 边界

**Risk axis:** desktop-privacy-capabilities

**Platform boundary:** shared+desktop

**Estimated scope:** 7 files, 230 lines

**Verification:** `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.security.DesktopPrivacyCapabilitiesTest" --tests "mihon.desktop.ui.settings.SecuritySettingsWiringTest"`

**Files:**

- Create: `app-desktop/src/main/kotlin/mihon/desktop/security/DesktopPrivacyCapabilities.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/settings/SecuritySettingsScreen.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/DesktopUiDependencies.kt`
- Modify: `i18n/src/commonMain/moko-resources/base/strings.xml`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/security/DesktopPrivacyCapabilitiesTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/settings/SecuritySettingsWiringTest.kt`

**Consumes:** Task 10 Security UI；fixed-main `hideNotificationContent` 和 telemetryIncluded 可见性规则；当前 DesktopNotificationService/构建依赖事实。

**Produces:** ID 92 每个设置项的真实 Desktop consumer/capability 归宿，不显示无消费者开关。

1. RED：production capability 明确区分 native system notification、应用内 Snackbar、telemetry runtime 和 system Widget provider；DI 缺失或 UI 错误显示开关时测试失败。
2. GREEN：`hideNotificationContent` 只在真实 native system notifier 能消费时显示；当前应用内 Snackbar 不冒充系统通知，不被错误静默脱敏。
3. Desktop 未包含 crashlytics/analytics runtime 时沿用 fixed-main `telemetryIncluded=false` 语义，不注册或显示无消费的 PrivacyPreferences switches。
4. Widget capability 明确 Unsupported，并指向现有 Desktop `GetUpdates` consumer；不新增伪 Widget provider。所有“不适用”都有 MR-backed 用户可见边界和 parity 可消费的结构化原因，不得硬编码英文说明。
5. 运行 Verification、DI/Screen 回归和 `git diff --check`。

**Evidence:** 范围修订提交 `14196e2`，实现提交 `8e6e63483`。固定原版 `SettingsSecurityScreen`/`telemetryIncluded` 取证确认通知内容开关只服务真实系统通知 consumer，Crashlytics/Analytics 只在 telemetry runtime 存在时显示。RED 因缺少结构化 capability、DI singleton、`DesktopUiDependencies` 字段和 Security UI 边界正确编译失败；GREEN 明确 native notification/in-app feedback/telemetry/system Widget/shared Updates 五类能力及稳定 reason slug。Production 如实声明 native notification、telemetry、system Widget Unsupported，in-app feedback 与 shared Updates Supported；不注册 `PrivacyPreferences`，不显示无消费者开关，以 MR 信息说明不适用边界，且真实 `GetUpdates` DI 可构造 `UpdatesScreenModelFactory`。协调者强制复跑 capability 2、Security 18、DI 11、Screen 41、Updates 5，共 77/77，0 failure/error/skip；根 Spotless、diff 与 production privacy runtime scan 通过。独立审查 APPROVED，Critical/Important/Minor `0/0/0`；最终范围 7 files/230 touched lines。

### Task 11：Desktop 窗口隐私能力与真实反馈

**Risk axis:** window-privacy

**Platform boundary:** desktop

**Estimated scope:** 9 files, 913 lines

**Split waiver:** Windows JNA ABI/query adapter、真实 AWT window handle 生命周期、`SecureScreenPolicy(mode, incognito)` reconciliation、设置回滚和 Supported/Limited/Unsupported/Failed 的 MR-backed 用户反馈共同决定同一个截图保护承诺。只交付 native adapter 会留下没有 production window consumer 的死基础设施；只交付 UI/lifecycle 会把未验证的系统能力暴露给用户。两个部分反复共享同一 result/capability/state，且 macOS/Linux 的诚实降级必须与 Windows 状态使用同一 UI；因此保留为单一 Task，并用独立 bridge 测试与 production wiring 测试分别杀死 ABI 和接线错误。

**Verification:** `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.privacy.DesktopWindowPrivacyTest" --tests "mihon.desktop.privacy.WindowPrivacyWiringTest"`

**Files:**

- Create: `app-desktop/src/main/kotlin/mihon/desktop/privacy/DesktopWindowPrivacy.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/Main.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/settings/SecuritySettingsScreen.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt`
- Modify: `app-desktop/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `i18n/src/commonMain/moko-resources/base/strings.xml`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/privacy/DesktopWindowPrivacyTest.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/privacy/WindowPrivacyWiringTest.kt`

**Consumes:** Task 1 SecureScreenPolicy；Task 10 Security UI；真实 Compose window handle。

**Produces:** Windows/macOS/Linux capability/result adapter，以及 Supported/Limited/Unsupported/Failed 的准确 UI。

1. RED：fake native bridge 覆盖 apply/clear/query、窗口未就绪、调用失败、OS 不支持、mode×incognito 变化和设置回滚。
2. GREEN：通过版本锁定的 JNA/JNA Platform `5.19.1` 实现 Windows HWND、`SetWindowDisplayAffinity`/query 与错误清理；native handle 绑定 Window 生命周期。macOS 只声明实际可执行、可查询的窗口共享限制；Linux 默认 Unsupported，不能只凭 OS 名称标成功，也不能在本 Task 静默扩张第二套 JNI。
3. 应用锁的 Compose 遮挡保持独立；不能用遮挡冒充系统截图保护，也不能用有限 macOS 能力声称等价 Android FLAG_SECURE。
4. 所有 capability、失败与有限支持说明使用 MR 文案，不得把 native error 或英文 reason slug 直接暴露给用户。
5. 运行 Verification、Windows 本机 focused integration（有 tagged gate 时）和 `git diff --check`；真实跨 OS 结论留 Task 16。

**Evidence:** 实现提交 `742a8e511`。Windows 使用真实 AWT `Window`/HWND 调用版本锁定的 JNA/JNA Platform `5.19.1`；`WDA_EXCLUDEFROMCAPTURE` 失败时只在 `WDA_MONITOR` 设置且 query 精确确认后报告 Limited，其他失败会 best-effort 清理并返回结构化 Failed。Production `BindDesktopWindowLifecycle` 同时绑定 focus 与窗口隐私生命周期，消费 shared `SecureScreenPolicy`；状态区分期望保护与实际已应用保护，Security UI 只显示 MR-backed 的真实 active/off/limited/unsupported/failed 反馈，设置失败执行补偿回滚，macOS/Linux 不伪报支持。协调者强制复跑 Windows native integration 6/6（0 skipped）、focused 11/11、Task 10 安全链路回归 87/87，根 Spotless 与 diff/secret scan 通过。首审 3 项 Important 经同一实现者修复，唯一复审 APPROVED，Critical/Important/Minor `0/0/0`；最终范围 9 files，913 insertions/1 deletion。

### Task 12：固定原版发布语义与当前 Android 兼容

**Risk axis:** release-contract

**Platform boundary:** shared+android

**Estimated scope:** 8 files, 400 lines

**Verification:** `./gradlew :domain:jvmTest --tests "tachiyomi.domain.release.interactor.GetApplicationReleaseParityTest" && ./gradlew :data:jvmTest --tests "tachiyomi.data.release.ReleaseServiceImplTest"`

**Files:**

- Modify: `domain/src/commonMain/kotlin/tachiyomi/domain/release/model/Release.kt`
- Modify: `data/src/commonMain/kotlin/tachiyomi/data/release/PlatformInfo.kt`
- Modify: `data/src/androidMain/kotlin/tachiyomi/data/release/AndroidPlatformInfo.kt`
- Modify: `data/src/commonMain/kotlin/tachiyomi/data/release/ReleaseServiceImpl.kt`
- Modify: `data/src/commonMain/kotlin/tachiyomi/data/release/GithubRelease.kt`
- Modify: `data/build.gradle.kts`
- Create: `domain/src/jvmTest/kotlin/tachiyomi/domain/release/interactor/GetApplicationReleaseParityTest.kt`
- Create: `data/src/jvmTest/kotlin/tachiyomi/data/release/ReleaseServiceImplTest.kt`

**Consumes:** fixed-main GetApplicationRelease/ReleaseService；Task 1 provenance 规则。

**Produces:** 保留三日节流/force/version 的 shared 发布结果，显式 target/asset/checksum metadata，并保持 Android APK/FOSS 选择兼容。

1. RED：确认新 shared 契约测试位于 `domain/src/jvmTest` 且由 Verification 精确执行；MockWebServer 覆盖成功、无兼容 asset、空 asset、403/429/500、畸形 JSON、缺少 checksum；domain 覆盖节流时 service 0 调用、force check、preview/release 同/新/旧版本。
2. GREEN：asset 选择使用结构化 target，不用任意文件名子串 map 覆盖；Android ABI/FOSS 行为由 AndroidPlatformInfo adapter 保持。旧 `domain/src/test/java/.../GetApplicationReleaseTest.kt` 不作为本 Task 执行证据。
3. 若 fixed-main 版本比较对位数不一致会越界，先以 fixed-main fixture记录，再将安全处理标为 cross-platform bugfix；不能悄悄换成不同 SemVer 规则。
4. 运行 Verification、Android updater compile/test 回归和 `git diff --check`。

**Evidence:** 范围/测试依赖修订提交 `f98b2a33c`，实现提交 `9f40548c3`。直接从 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8` 的 `GetApplicationRelease`、`ReleaseServiceImpl`、`GithubRelease`、`Release`、原测试与 `release.yml` 固化三日节流、force、preview/release 比较，以及 universal/四 ABI/FOSS canonical asset 命名。Shared `ReleaseTarget(os, arch, packageType, variant)`、`ReleaseAsset`、可空 SHA-256 metadata 保留旧 `downloadLink` consumer；Android adapter 明确 `ANDROID/APK`，选择 exact ABI 后仅回退 universal，FOSS 不回退 standard，空/不兼容 asset 返回 null，HTTP/JSON 失败保持失败。MockWebServer 覆盖成功、4 ABI、4 universal fallback、FOSS、空/不兼容、403/429/500、畸形 JSON、缺失及三类无效 digest；mutation RED 分别杀死 fallback 与 checksum validation 回归。协调者强制复跑 domain 3/3、data 7/7、Android `app:compileDebugKotlin` 成功，根 Spotless 与 diff/secret scan 通过。首审 2 项 Important 经唯一修复轮关闭，复审 APPROVED，Critical/Important/Minor `0/0/0`；最终范围 8 files/364 touched。

### Task 13A：Desktop release discovery 与 canonical 包契约

**Risk axis:** desktop-release-discovery

**Platform boundary:** shared+desktop

**Estimated scope:** 4 files, 220 lines

**Verification:** `./gradlew :data:jvmTest --tests "tachiyomi.data.release.ReleaseServiceImplTest"`

**Files:**

- Modify: `data/src/jvmMain/kotlin/tachiyomi/data/release/DesktopPlatformInfo.kt`
- Modify: `data/src/commonMain/kotlin/tachiyomi/data/release/GithubRelease.kt`
- Modify: `data/src/jvmTest/kotlin/tachiyomi/data/release/ReleaseServiceImplTest.kt`
- Modify: `data/src/jvmTest/kotlin/tachiyomi/data/release/DesktopPlatformInfoTest.kt`

**Consumes:** Task 12 structured release target/asset/checksum；Compose Desktop 的 MSI/DMG package format；当前 release workflow 的真实 APK-only 事实。

**Produces:** Windows/MSI、macOS/DMG 的平台 target 与 anchored canonical 远端文件名契约；Linux 和当前 APK-only release 如实返回 no compatible asset。

1. RED：表驱动覆盖 Windows x86_64 MSI、macOS x86_64/arm64 DMG 的 exact target，拒绝错误 OS/arch/扩展名、空格和 `.bak` 伪装；当前仅含 fixed-main APK 的 release 必须返回 null。
2. GREEN：`DesktopPlatformInfo` 只映射真实 OS/arch/package type；`GitHubAsset.parse` 增加稳定、无空格、含 OS/arch/tag 的 anchored desktop 名称（如 `mihon-desktop-windows-x86_64-<tag>.msi`、`mihon-desktop-macos-arm64-<tag>.dmg`），不把本地 jpackage 名称或尚不存在的 CI asset 冒充已发布事实。
3. release workflow 当前没有 Desktop job，因此本 Task 只能建立 future-compatible discovery 契约并证明当前无兼容包；Task 15 必须记录发布流水线需按同一契约重命名、生成 checksum 并配置签名信任根。
4. 运行 Verification、Task 12 Android asset 回归、DesktopPlatformInfo 回归和 `git diff --check`。

**Evidence:** 范围修订提交 `4514ff5e4`，实现提交 `09b6e730f`。`DesktopPlatformInfo` 通过 production 可测试 seam 将 Windows→WINDOWS/x86_64/MSI、macOS/Darwin→MACOS/x86_64|arm64/DMG、Linux→LINUX/UNKNOWN，消除 Desktop ARM 被写成 Android `arm64-v8a` 的混淆；Darwin RED 证明 OS 判断顺序能杀死 `darwin` 被 `win` 子串误判。`GitHubAsset.parse` 只接受 anchored future canonical MSI/DMG 名称并对 release tag 使用 `Regex.escape`，拒绝错误 OS/arch/扩展、空格、`.bak`、substring 和本地 jpackage 名；Task 12 Android ABI/universal/FOSS/checksum 保持。真实 MockWebServer production 链证明 canonical Desktop asset 可选中，而当前 APK-only release 对 Desktop target 返回 null，不冒充已发布能力。协调者强制复跑 ReleaseService 10/10、PlatformInfo 2/2，根 Spotless 与 diff/secret scan 通过；独立审查 APPROVED，Critical/Important/Minor `0/0/0`。最终范围 4 files/214 touched；release workflow、checksum 发布与签名信任根仍是明确后续边界。

### Task 13B：无兼容包 shared 结果与当前 Android 兼容

**Risk axis:** no-compatible-release

**Platform boundary:** shared+android

**Estimated scope:** 5 files, 280 lines

**Verification:** `./gradlew :domain:jvmTest --tests "tachiyomi.domain.release.interactor.GetApplicationReleaseParityTest" && ./gradlew :app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.data.updater.AppUpdateCheckerCompatibilityTest"`

**Files:**

- Modify: `domain/src/commonMain/kotlin/tachiyomi/domain/release/interactor/GetApplicationRelease.kt`
- Modify: `domain/src/jvmTest/kotlin/tachiyomi/domain/release/interactor/GetApplicationReleaseParityTest.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/more/settings/screen/about/AboutScreen.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/data/updater/AppUpdateCheckerCompatibilityTest.kt`

**Consumes:** Task 13A 中 `ReleaseService.latest() == null` 表示 release 存在但当前 target 无兼容 asset 的 production 事实；fixed-main throttle/force/version 结果。

**Produces:** shared `NoCompatiblePackage` 结果，以及当前 Android adapter 将其映射为既有 `NoNewUpdate` 可见行为的兼容层；Desktop 可在 Task 14 显示准确状态。

1. RED：service null 必须产生 `NoCompatiblePackage`，但 throttle 仍是 `NoNewUpdate` 且 service 0 调用；Android production checker 必须把新结果映射回既有 no-update 行为，About exhaustive consumer 显式复用同一无更新反馈。
2. GREEN：只区分“检查被节流/版本不新”和“release 存在但 target 不兼容”；三日节流、force、preview/release 比较保持 fixed-main，不借机改 SemVer。
3. Android adapter 测试必须经过 production mapping seam；断开 mapping 时失败。运行 Verification、Android app compile 与 `git diff --check`。

**Evidence:** 范围修订提交 `58372164b`，实现提交 `96ec59b13`。shared `GetApplicationRelease` 现在只在 release service 找不到当前 target 的兼容资产时返回 `NoCompatiblePackage`；节流仍返回 `NoNewUpdate` 且不调用 service，既有 force、preview/release 比较和 `lastChecked` 时序未改变。当前 Android production checker 将新结果映射回既有 `NoNewUpdate` 行为，About 页面继续显示同一“无更新”反馈，新版本仍只触发一次真实 notifier。RED 证明 service null 原先错误折叠为 `NoNewUpdate`，Android mapping 缺失时兼容测试失败；mutation 临时断开 mapping 后同样被测试杀死。协调者强制复跑 shared parity 4/4、Android production adapter 3/3，Android `:app:compileDebugKotlin`、根 Spotless、diff 与 secret scan 均通过；独立审查 APPROVED，Critical/Important/Minor `0/0/0`。最终范围 5 files/110 touched；Desktop 对该状态的独立可见反馈留给 Task 14。

### Task 13C：Desktop 安全下载、临时路径与 SHA-256

**Risk axis:** desktop-update-download

**Platform boundary:** desktop

**Estimated scope:** 2 files, 400 lines

**Verification:** `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.update.DesktopUpdateDownloaderTest"`

**Files:**

- Create: `app-desktop/src/main/kotlin/mihon/desktop/update/DesktopUpdateDownloader.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/update/DesktopUpdateDownloaderTest.kt`

**Consumes:** Task 12/13A release asset 与 checksum；DesktopNetworkHelper；可注入安全临时根。

**Produces:** 仅在安全临时根内生成、通过 SHA-256 的 `VerifiedDownload`；所有非成功终态删除临时文件。

1. RED：真实 MockWebServer 覆盖进度、取消、redirect 次数/跨 scheme 限制、Content-Length 与流式超限、连接中断、SHA mismatch、缺/无效 checksum、symlink/path escape、终态 cleanup 和 retry。
2. GREEN：下载使用独立 cacheless client/call，只写 containment 验证后的临时文件；先限制响应与字节数，再验证 Task 12 的 SHA-256。缺可信 metadata 返回 ManualOnly/release-page fallback，不产生“已验证”对象。
3. 该门禁是 Desktop security enhancement / cross-platform hardening，不冒充 fixed-main APK 行为。运行 Verification、mutation/cleanup 回归、Spotless 与 `git diff --check`。

**Evidence:** 实现提交 `b0c10552f`。Downloader 从现有 Desktop client 派生独立 `cache(null)` client，关闭自动重定向并手动限制次数/拒绝跨 scheme；只有合法 SHA-256 metadata、响应与流式累计均未超限、digest 匹配且临时文件位于 real-path/非 symlink 安全根内时才返回 `VerifiedDownload`。缺失或非法 checksum 在发请求前返回 `ManualOnly`；取消保持传播，连接、本地存储、HTTP、重定向、超限和 checksum 失败均为结构化结果并清理局部文件，失败后可重试。初始 RED 7 项中 6 项失败，首轮 GREEN 的 1 个取消残留失败被修复后 7/7；禁用 finally cleanup 的 mutation 使 4 项失败。独立审查发现 Long 加法溢出和本地 I/O 被误分类为网络错误两个 Important；唯一修复轮 RED 9/2，旧加法和旧 I/O 分类 mutation 分别为 9/1，修复后 9/9，open/write/close 均为 `STORAGE` 而网络 body 中断仍为 `CONNECTION`。协调者以 `--rerun-tasks` 强制复跑 9/9，根 Spotless、diff 与 secret scan 通过；修复复审 APPROVED，Critical/Important/Minor `0/0/0`。最终范围 2 files/399 touched。当前 APK-only workflow 没有 Desktop checksum，因此 production 将诚实停在 ManualOnly，不虚报自动下载能力。

### Task 13D：Desktop 签名验证与平台安装交接

**Risk axis:** desktop-update-install-handoff

**Platform boundary:** desktop

**Estimated scope:** 4 files, 400 lines

**Verification:** `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.update.DesktopUpdateInstallerTest"`

**Files:**

- Modify: `app-desktop/src/main/kotlin/mihon/desktop/update/DesktopUpdateDownloader.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/update/DesktopUpdateDownloaderTest.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/update/DesktopUpdateInstaller.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/update/DesktopUpdateInstallerTest.kt`

**Consumes:** Task 13C 仅在传输期间使用的 `.part` staging 与 `VerifiedDownload`；Task 13A current target/canonical asset；可注入 Authenticode/codesign 与 process launcher。

**Produces:** Windows MSI、macOS DMG 的结构化 signature/publisher verification 与确认/取消/hand-off 结果；Linux 和缺失信任根的平台为 ManualOnly。

1. RED：fake command/process seam 覆盖 target/package/canonical name mismatch、缺签名、错误 publisher/team-id、无信任根、确认取消、启动失败和成功 handoff；真实 downloader→installer 测试必须从 MockWebServer 产生与 production 相同的 staging/final 路径，不能手工伪造 `.msi/.dmg`，并精确断言 prepare 与 handoff 复验的完整调用次数及每次 final path，不得用空集合也为真的谓词代替。不得只检查“存在任意签名”。
2. GREEN：Downloader 只在传输期间使用安全根内的 `.part`，SHA-256 成功后在同一目录原子定型为当前 package type 的 `.msi/.dmg`，再构造 `VerifiedDownload`；失败不留下 staging 或 final 文件。Windows 只允许匹配配置可信 publisher 的 Authenticode MSI，macOS 只允许匹配配置 team-id/notarization policy 的 DMG；仓库当前无签名/信任根配置，生产默认必须 ManualOnly，不能伪报自动安装。
3. handoff 只启动外部安装 side effect，不覆盖或删除当前应用；失败保留 verified artifact 供手动后备。运行 Verification、真实 downloader→installer 形状测试、Windows 可执行 verifier probe、Spotless 与 `git diff --check`。

**Replan evidence:** 首轮实现与唯一修复已完成 target/hash/trust/confirmation、canonical asset 和 PowerShell stdin 安全门禁，但修复复审发现 Task 13C production 固定返回 `.part`，而 Task 13D 测试手工构造 `.msi/.dmg`，使 macOS DMG 签名与 `/usr/bin/open` 交接没有覆盖真实输入形状。该问题跨越 downloader finalization 与 installer verification，不能拆成可独立完成的后置 UI/state Task；因此在不新增编号的前提下把当前 Task 扩为上述 4 文件，并要求真实链测试。此前未提交的 13D 实现保留为重规划后的起点，完成状态仍未勾选。

**Evidence replan:** 4 文件真实链实现已通过 Downloader/Installer 20/20，但新边界的修复复审发现路径断言使用 `all {}`，无法区分空集合、仅 prepare 验证和 prepare + handoff 完整复验。当前 Task 再次原位收紧验收证据：Windows 必须精确记录 2 次 stdin final path，macOS 必须精确记录 4 次 codesign/spctl final path；两组均须逐项等于同一个 production Downloader 产物。该修改只强化既有集成风险的可证伪性，不新增产品能力、文件或 Task 编号，完成状态继续保持未勾选。

**Evidence:** 重规划提交 `c3db21ff2`、范围字段纠正 `56cb7438e`、证据门禁收紧 `b19f644f5`，实现提交 `7e6e91da0`。Downloader 只为 MSI/DMG 在安全根内写随机 `.part`，SHA-256 成功后同目录 `ATOMIC_MOVE` 为随机 `.msi/.dmg`，后缀只来自结构化 package type；unsupported 在建根和发请求前 `ManualOnly`，move/containment/取消/失败会清理 staging 与 final 候选。Installer 分离 `prepare(download, releaseTag)` 与确认后的 `handoff`，后者再次完整验证 exact target/standard variant/arch/Task 13A canonical asset、文件 size/hash、Windows Authenticode exact publisher 或 macOS exact team-id + notarization；默认无 trust/Linux 为 `ManualOnly`，成功只参数化启动 `msiexec /i` 或 `open`，所有取消/失败保留 artifact。Windows 路径与 publisher 只以 UTF-8 Base64 stdin 进入固定 PowerShell 脚本，真实含空格未签名 probe 到达 `SIGNATURE_INVALID`。重规划真实链 RED 为联合 19/3；`.part` finalization mutation 19/2、unsupported mutation 20/1，move 部分成功后异常仍清空。证据修复把真实 MockWebServer 产物贯穿 prepare、handoff 二次 verifier 和 launcher，精确证明 Windows 2 次、macOS 4 次验证路径均为同一 final artifact；断开二次复验为 10/2，删一次 stdin 记录为 10/1。协调者以 `--rerun-tasks` 强制复跑 Downloader 10/10 + Installer 10/10，根 Spotless、diff 与 secret scan 通过；最终独立审查 APPROVED，Critical/Important/Minor `0/0/0`。最终范围 4 files/400 touched。仓库仍无 Desktop trust root，production 默认不会虚报自动安装，发布信任配置留给 Task 15 的流水线边界记录。

### Task 13E：Desktop 更新控制器状态机

**Risk axis:** desktop-update-state

**Platform boundary:** desktop

**Estimated scope:** 3 files, 400 lines

**Verification:** `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.update.DesktopUpdateControllerTest" --tests "mihon.desktop.update.DesktopUpdateDownloaderTest" --tests "mihon.desktop.update.DesktopUpdateInstallerTest"`

**Files:**

- Create: `app-desktop/src/main/kotlin/mihon/desktop/update/DesktopUpdateState.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/update/DesktopUpdateController.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/update/DesktopUpdateControllerTest.kt`

**Consumes:** Task 13B `NoCompatiblePackage`；Task 13C downloader；Task 13D installer；既有 TaskState/AppError 可复用语义。

**Produces:** `Idle → Checking → UpToDate | UpdateAvailable | NoCompatiblePackage | CheckFailed → Downloading → Verifying → ReadyToInstall → HandingOff → HandedOff | InstallFailed`，以及 Cancelled/RetryableFailure/ManualOnly。

1. RED：fake release/downloader/installer 覆盖每条允许转换、非法重入、progress、取消、retry、确认拒绝、download/verify/install failure 和 release-page fallback；断开任一 production delegate 时测试失败。
2. GREEN：controller 只编排 shared release + 两个平台 adapter，不重复 HTTP、hash、签名或进程规则；取消传播并清理下载，失败不改变当前可启动应用。
3. 运行 Verification、Task 12/13B release 回归、Spotless 与 `git diff --check`。

**Evidence:** 实现提交 `ee9382ac0`、审查修复 `878a11dda`。Controller 通过 `StateFlow` 编排 shared `GetApplicationRelease`、Desktop downloader 与 installer，覆盖检查、下载进度、验证、确认交接、手动后备、结构化失败和精确阶段重试；`OsTooOld` 保留为不可重试的 EOL 检查结果。首轮独立审查发现迟到 progress 可覆写终态及取消证据只覆盖下载两个 Important；唯一修复轮加入每次下载唯一 `AtomicLong` generation，并在 `MutableStateFlow.update` CAS 路径内校验 active token，取消、失败或完成前先失效 token。测试覆盖取消后、Ready 后和同 release retry 期间的旧 progress，以及 check/download/prepare/handoff 四阶段同实例取消传播、`Cancelled` 发布与 retry 清空；固定 token 和逐 catch 删除 cancel 的变异均被精确杀死。协调者强制复跑 Controller 7/7 + Downloader 10/10 + Installer 10/10、shared release parity 4/4、当前 Android consumer 3/3，根 Spotless、diff 与 secret scan 通过；修复复审 APPROVED，Critical/Important/Minor `0/0/0`。最终范围 3 files/396 touched。Task 14 仍负责 production DI、About UI 与 Test Mode wiring。

### Task 14：Desktop 更新 UI、DI 与 Test Mode wiring

**Risk axis:** desktop-update-ui

**Platform boundary:** shared+desktop

**Estimated scope:** 8 files, 480 lines

**Split waiver:** 独立审查证明 Test Mode 取消、About 手动后备失败反馈和阻塞式签名验证必须共用同一个 `DesktopUpdateScreenModel` 作业所有权：若把取消单独放入 Test Mode 或 controller，只能改变状态而不能取消 UI/Test Mode 实际持有的协程；若把后台调度另拆，取消测试又无法证明它作用于同一 production job。因此本轮修复必须同时调整既有 ScreenModel、About/Test Mode consumer 与对应行为测试，不新增第二套状态机或生产文件。累计触达仍限定在原 8 个文件内。

**Verification:** `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.settings.AboutUpdateWiringTest" --tests "mihon.desktop.di.DesktopDiWiringTest" --tests "mihon.desktop.test.http.DesktopPlatformTestModeControllerTest"`

**Files:**

- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/settings/AboutScreen.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/ui/settings/DesktopUpdateScreenModel.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/DesktopUiDependencies.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/test/http/TestHttpServer.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/ui/settings/AboutUpdateWiringTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/test/http/DesktopPlatformTestModeControllerTest.kt`

**Consumes:** Task 12/13A/13B ReleaseService/GetApplicationRelease；Task 13E DesktopUpdateController；APP_VERSION。

**Produces:** About 显示真实版本、Check for updates、确认/进度/取消/重试/手动后备，以及 DI/Test Mode production observation。

1. RED：About 必须显示 APP_VERSION；手动 force check、无更新、新版本、无兼容包、网络失败、下载/验证/安装状态和确认取消都经真实 ScreenModel/controller。
2. GREEN：生产 DI 注册 ReleaseService、GetApplicationRelease、DesktopUpdateController；UI 不直接发 HTTP、写文件或启动安装器。
3. Test Mode 只暴露 production 状态/动作，不复制 updater 状态机；断开 DI/controller 时测试必须失败。
4. 运行 Verification、Screen 实例化/导航回归、MockWebServer 集成和 `git diff --check`。

**Review status（已完成）：** 初始实现 `ff3811a0f`、范围重规划 `440d1a513`、唯一修复 `87e109527` 完成 About/DI/Test Mode wiring、同一 job 取消和发布页失败反馈；审查遗留的可取消 verifier 与 runtime 生命周期所有权分别由子 Task 14A、14B 关闭。14A 的实现/修复为 `2afcc9af5`、`e62ccf035`；14B 的实现/唯一审查修复为 `c3eb53a04`、`e3ce84dee`。两项修复复审均 APPROVED，Critical/Important/Minor `0/0/0`，因此本 Task 与其两个 closure 子 Task 统一完成。

### 子 Task 14A：Desktop verifier 可取消进程边界

**Risk axis:** desktop-update-verifier-cancel

**Platform boundary:** desktop

**Estimated scope:** 7 files, 400 lines

**Verification:** `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.update.DesktopUpdateProcessRunnerTest" --tests "mihon.desktop.update.DesktopUpdateInstallerTest" --tests "mihon.desktop.update.DesktopUpdateControllerTest" --tests "mihon.desktop.ui.settings.AboutUpdateWiringTest"`

**Files:**

- Modify: `app-desktop/src/main/kotlin/mihon/desktop/update/DesktopUpdateController.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/update/DesktopUpdateInstaller.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/update/DesktopUpdateProcessRunner.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/update/DesktopUpdateControllerTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/update/DesktopUpdateInstallerTest.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/update/DesktopUpdateProcessRunnerTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/settings/AboutUpdateWiringTest.kt`

**Consumes:** Task 13D 的 exact signature/notarization 与二次复验契约；Task 13E/14 的 controller、ScreenModel job owner 和 `Cancelled` 终态。

**Produces:** 只供 updater verifier 使用的 suspend、可取消进程 runner；取消时关闭 stdin/stdout/stderr、终止并有界回收子进程，controller 不再在取消后推进 Ready/Failed。

1. RED：用当前 JVM 启动可阻塞、可记录 PID 的真实 helper process；进入 Windows/macOS verifier 等价命令后取消 ScreenModel job，旧 production runner 必须因进程仍存活或终态被覆盖而失败。fake 自行调用 `runInterruptible` 不可作为证据。
2. RED：覆盖 stdout/stderr 同时输出、stdin 写入、正常退出、启动失败、取消和强制终止；测试必须证明取消异常保持原实例、PID 在有界时间内死亡且没有 reader thread 遗留。
3. GREEN：将 installer verifier 边界改为 suspend runner；controller 的 prepare/handoff 二次验证沿同一可取消链传播 `CancellationException`。不得修改 credential/URI registration 共用的同步 `CommandRunner` 语义。
4. 在 verifier 返回后、写入 Ready/Failed 前保留 cancellation checkpoint；Cancel 后 `Cancelled` 不得被迟到结果覆盖，artifact 保留/清理继续遵守 Task 13C/13D 契约。
5. 运行 Verification、Downloader 真实 MockWebServer 链、Spotless、`git diff --check` 和子进程/PID 清理检查。

**Evidence:** 实现提交 `2afcc9af5`、唯一审查修复 `e62ccf035`。首轮 RED 通过 ScreenModel→Controller→Installer→真实 JVM helper 证明旧同步 `CommandRunner.waitFor()` 令 Cancel 两秒内无法进入 `Cancelled`；新增 updater 专用 suspend runner 并发排空约 2 MiB stdout/stderr、清零 stdin，在取消时按 stdin close→graceful destroy→forced destroy→PID wait→stream close→reader join 的顺序清理。Controller 的 prepare/handoff delegate 改为 suspend，并在 Ready/Failed 前执行 cancellation checkpoint；Task 13D 的 Windows/macOS 首次与 handoff 二次复验、exact path/call-count、hash/name/trust/artifact 断言保持。首审发现 forced wait Boolean 被忽略 1 个 Important；唯一修复在两次 wait=false 且 PID 仍 alive 时生成明确 termination-timeout，并在 NonCancellable 清理完三流/reader 后把失败 suppressed 到同一个主 `CancellationException`，false 后已死亡不误报。确定性 seam 测试与真实 block/resist PID 用例共同证明同一取消实例、普通/强杀、超时诊断、PID 两秒内死亡和 reader=0；迟到 success/failure 均不能覆盖 `Cancelled`。协调者强制复跑 Runner 4 + Installer 10 + Controller 8 + About 3 + Downloader 10 = 35/35，0 failure/error/skip，helper 残留 0；根 Spotless、diff 与 secret scan 通过。唯一修复复审 APPROVED，Critical/Important/Minor `0/0/0`。最终范围 7 files/400 touched；Task 14B 仍负责 runtime 生命周期所有权。

### 子 Task 14B：Desktop updater 应用生命周期所有权

**Risk axis:** desktop-update-lifecycle

**Platform boundary:** desktop

**Estimated scope:** 8 files, 400 lines

**Verification:** `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.DesktopAppRuntimeTest" --tests "mihon.desktop.di.DesktopDiWiringTest" --tests "mihon.desktop.test.http.DesktopPlatformTestModeControllerTest" --tests "mihon.desktop.ui.settings.AboutUpdateWiringTest"`

**Files:**

- Modify: `app-desktop/src/main/kotlin/mihon/desktop/DesktopAppRuntime.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/Main.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/settings/DesktopUpdateScreenModel.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/DesktopAppRuntimeTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/test/http/DesktopPlatformTestModeControllerTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/settings/SecuritySettingsWiringTest.kt`

**Consumes:** Task 14A 可取消 production runner；Task 14 中 About 与 Test Mode 共用的 singleton ScreenModel。

**Produces:** 由 `DesktopAppRuntime` 唯一拥有的 updater parent scope/model；GUI、headless Test Mode、应用退出和测试 DI 重建都执行同一有界取消/关闭链。

1. RED：启动真实可挂起 update job 后调用 `DesktopAppRuntime.close()`，旧实现必须因 delegate 未取消、旧 model 仍可接收动作或 test-DI 重建后旧状态继续推进而失败。
2. GREEN：为 updater 创建 runtime-owned parent scope；ScreenModel 使用有父 Job 的子 owner，不取消调用者 scope。`close()` 幂等地阻止新 intent、取消当前 job，并由 runtime 聚合清理异常。
3. `DesktopTestDIContext.closeAndJoin()` 必须关闭并等待 updater；下一次 DI 初始化不得复用旧 model/controller/job。About 与 Test Mode 仍解析同一新实例。
4. 覆盖 GUI close、headless shutdown、重复 close、未启动 runtime close、取消异常和旧实例不可再驱动；断开 runtime→updater cleanup 时测试必须失败。
5. 运行 Verification、Screen/Navigation 回归、Spotless、`git diff --check` 与 scope/job 泄漏检查。

**Evidence:** 实现提交 `c3eb53a04`、唯一审查修复 `e3ce84dee`。RED 先证明 runtime close 和 Test DI 重建后旧 singleton model 仍能接收 CHECK；GREEN 让每次 updater operation 使用绑定唯一 application scope 的临时 child owner，operation `finally` 完成 owner，`close()` 先永久拒绝新 intent，再用同一取消实例终止当前 operation，`closeAndJoin()` 等待 owner 与 application scope。DI 重建会先关闭并等待旧 runtime/updater，About 与 Test Mode 始终解析同一新实例，`dispose()` 仍只取消页面 operation 且可重用，不夺取 caller scope。首审发现 GUI/headless 真实 bootstrap 只调用非等待式 `runtime.close()`；唯一修复用 production bootstrap RED 证明 NonCancellable updater cleanup 未释放时旧路径会提前返回，并让 `DesktopRuntimeBootstrapSession.close()` 同步等待 `runtime.closeAndJoin()`，GUI `onCloseRequest` 与 headless `runHeadlessMode` 共用该链。协调者强制复跑 Runtime 12 + DI 13 + Test Mode 1 + About 3 + Security/bootstrap 19 = 48/48，ScreenInstantiation 41 + Navigation 6 = 47/47，根 Spotless、diff、secret 与残留进程检查通过；唯一修复复审 APPROVED，Critical/Important/Minor `0/0/0`。最终范围 8 files/266 touched。Task 14、14A、14B 已统一关闭；下一项为 Task 15。

### Task 15：Widget 豁免、parity 证据与维护文档

**Risk axis:** platform-parity-evidence

**Platform boundary:** verification

**Estimated scope:** 8 files, 400 lines

**Verification:** `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.parity.DesktopProductCapabilityContractTest" --tests "mihon.desktop.parity.WidgetPrivacyBoundaryTest"`

**Files:**

- Modify: `app-desktop/src/test/resources/parity/parity-manifest.json`
- Modify: `app-desktop/src/test/resources/parity/fixed-main-path-inventory.json`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/parity/DesktopProductCapabilityContractTest.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/parity/WidgetPrivacyBoundaryTest.kt`
- Modify: `presentation-widget/src/test/java/tachiyomi/presentation/widget/WidgetPrivacyProductionWiringTest.kt`
- Create: `docs/architecture/desktop-platform-integration.md`
- Modify: `docs/superpowers/plans/2026-07-21-mihon-desktop-platform-integration.md`
- Modify: `.superpowers/sdd/progress.md`

**Consumes:** Tasks 1–14B production wiring/test evidence；Task 3 的 Android Widget production test；Task 10A 的 Desktop Widget/notification/telemetry capability；fixed-main WidgetManager/GetUpdates/SecurityPreferences；三 OS capability 结论。

**Produces:** IDs 81–84、86、92 的 VERIFIED 候选证据，ID 85 的 roadmap-approved EXEMPT 证据，以及维护边界。

1. RED：parity contract 要求每项包含 fixed-main ref/path/symbol、shared contract、当前 Android/Desktop consumer、adapter、保护测试和偏差；空字段、源码字符串测试或断开的 production wiring 必须失败。
2. ID 85 引用 Task 3 的 `presentation-widget` production test 证明 Android Widget 使用 GetUpdates 且锁开启不泄露；Desktop 测试只证明 Updates 页面消费同一 GetUpdates 与 Task 10A 的 Widget Unsupported capability。不要让 Desktop 测试冒充 Android Glance 证据，也不要新增无 production consumer 的 Widget abstraction。
3. ID 86 把 fixed-main 节流/版本/可见状态与 Desktop checksum/signature/size/redirect hardening 分栏记录；后者不得标成原版 provenance。
4. ID 92 逐项记录 app lock、delay、screen privacy、native notification content 和 telemetry：有 consumer 才 VERIFIED；Task 10A 判定不适用的项记录能力原因和 UI 行为。
5. 架构文档说明入口、状态、adapter、能力探测、安全失败、维护/新增 OS 方法和 updater 回滚。
6. 只有相应 Task 审查通过才更新状态；真实 OS 尚未验证的条目标为 CANDIDATE/有限，不预先写 VERIFIED。
7. 运行 Verification、`git diff --check` 和文档路径/链接检查。

**Review status（修复中）：** 初始实现提交 `c57383a23`、结构化证据修订 `d1f068de6`、权威路径修复 `9981e2929`；协调者强制复跑 Desktop parity/Widget/Updates/privacy 35/35、Android Widget 2/2、根 Spotless、JSON/current/fixed-main 路径与 diff 均通过。独立首审仍以 Critical/Important/Minor `2/4/0` 拒绝：ID 81 把 fork 的 `ExternalActionParser` 倒写为 fixed-main 行为；平台契约未接入 fixed-main blob inventory、精确 per-ID 路径/符号与可杀死 production wiring 的行为证据；多个 ID 只列 adapter 而漏 UI/DI consumer；Android Widget 测试未实例化/执行 `BaseUpdatesGridGlanceWidget` production wiring；ID 86 缺 fixed-main `GetApplicationRelease` 节流/版本路径；ID 92 无 Desktop consumer 的 notification/telemetry 错标为 CANDIDATE。唯一修复轮因此在同一 Task 内加入 fixed-main inventory 与 Android Widget production 测试，范围调整为 8 files/400 lines；不新增 Task。

### Task 16：独立最终审查与三平台 change verify

**Risk axis:** platform-change-verify

**Platform boundary:** verification

**Estimated scope:** 6 files, 220 lines

**Verification:** shared/Android/Desktop 全量测试、Windows 固定 EXE、macOS 应用包和可用 Linux matrix 均基于同一最终提交

**Files/Artifacts:**

- Modify: `docs/superpowers/plans/2026-07-21-mihon-desktop-platform-integration.md`
- Modify: `docs/superpowers/plans/2026-07-12-mihon-desktop-upstream-parity-roadmap-main-authority.md`
- Modify: `.superpowers/sdd/progress.md`
- Create: `docs/superpowers/reports/2026-07-21-align-desktop-platform-verify.md`
- Verify: `app-desktop/src/test/resources/parity/parity-manifest.json`
- Artifact: `app-desktop/tmp/mihon-dist/main/app/Mihon Desktop/Mihon Desktop.exe`

**Consumes:** Tasks 1–15 已审查提交和当前平台 adapter。

**Produces:** whole-change 独立审查、三 OS/Android 必要验证、完整版本/产物/失败数和父子计划最终一致性证据。

1. 由未参与实现的 reviewer 审查 `base-ref..HEAD`：逐 ID 对照 fixed-main、shared/Android/Desktop production chain、安全模型、Desktop 独有功能、测试有效性和提交范围。Critical/Important 按全局门禁只允许一轮修复复审。
2. 在同一最终提交运行：

   ```powershell
   ./gradlew spotlessCheck
   ./gradlew :domain:allTests
   ./gradlew :data:allTests
   ./gradlew testReleaseUnitTest
   ./gradlew :app-desktop:jvmTest
   ./gradlew :test-desktop:test
   ```

3. 部署 Android 模拟器并执行最小强制矩阵：入站 `ACTION_SEARCH`、`ACTION_SEND text/plain`、`tachiyomi://add-repo?url=...`；lock delay `-1/0/>0` 与认证失败保持锁定；secure-screen `ALWAYS/INCOGNITO/NEVER`；手动 update 的 force/no-update/new-update。记录设备/API、APK 和结果，不把它当作 fixed-main provenance。
4. Windows：通过 `scripts/build-desktop.sh` 生成一个新 BUILD，启动固定未打包 EXE，核对 mtime、完整版本和窗口标题；真实验证冷启动/运行中 URI、分享 fallback、DPAPI app lock、窗口 capability、About 更新状态和 Test Mode/smoke。
5. macOS：用 `ssh mbp`，失败再用 `ssh mbp-lan`，在同一提交运行 full-tests/build，部署 `/Applications/Mihon Desktop.app`；验证 bundle scheme、Keychain、分享、窗口能力、更新 handoff 和 smoke/Test Mode。GUI Keychain 若 SSH 明确拒绝交互，记录精确有限边界，不能外推成功。
6. Linux：使用可用本机/CI/容器执行不依赖 GUI 的 broker、desktop entry、secret-tool capability、clipboard/portal probe 和 ManualOnly updater；没有真实 GUI/Secret Service 证据的项保持有限/豁免，不伪造通过。
7. 报告每条命令、测试数/失败/跳过、OS/版本、完整 Desktop 版本、固定 EXE 绝对路径、剩余有意偏差和豁免。清理本轮进程/临时文件。
8. 只有 review 清零、所有必需验证通过、parity 状态真实、子计划全部勾选后，才勾选父 roadmap Task 5A Steps 1–11，并把 progress 切到 Task 5B；否则保持对应项未完成并继续修复。
