---
change: align-sources-extensions
design-doc: docs/superpowers/specs/2026-07-15-mihon-source-extension-shared-core-design.md
base-ref: 852221f42863d2f3f6519313b11956e807fdf6d1
---

# Mihon 源与扩展共享核心实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Android 与 Desktop 的源查询、扩展目录、版本/信任、安装事务和错误反馈使用同一套共享业务实现，只保留必要的平台 adapter，并保护 Desktop 独有产品能力。

**Architecture:** 复用 `SourceMangaSearchService`、`AppError`、`ExtensionRepoRepository` 和现有平台 loader，把共享状态/规则放入 domain common；Android 保留 PackageManager/PackageInstaller/WebView，Desktop 保留目录、ClassLoader、APK→JAR、浏览器会话和系统文件操作。迁移期用同 fixture 双轨比较，production wiring 通过后删除旧业务路径。

**Tech Stack:** Kotlin Multiplatform、Coroutines/Flow、OkHttp MockWebServer、kotlinx.serialization、Voyager、Compose Multiplatform、Injekt、JUnit/Kotest/AssertJ、Android Emulator、Skiko/JVM。

## Global Constraints

- 所有功能变化严格执行 RED → GREEN → REFACTOR；没有对应测试的功能代码不得提交。
- 优先复用现有 `SourceMangaSearchService`、`AppError`、`ExtensionRepoRepository`、`DesktopExtensionApi`/loader adapter、`DesktopCookieJar` 与现有 Screen/导航入口，禁止另起第二套实现。
- Android 与 Desktop 共享源列表、单源浏览、全局搜索、分页、空状态和错误语义。
- 安装/更新遵循 `prepare → validate → commit → reload → rollback`；只有 reload 成功后才能发布 Installed。
- Desktop 仓库身份/摘要连续性不得冒充 Android APK 签名等价。
- compat stub 必须有真实受支持扩展调用证据与回归测试；无证据 API 不得扩张。
- FlareSolverr 仅为用户显式选择的后备，不得静默接管正常请求。
- 保留 Desktop APK→JAR、宽屏源 UI、扩展详情/文件工具、键鼠交互与 Test Mode，且保护测试必须在回退时失败。
- 本 change 触达的源、扩展和挑战登录业务文案必须进入 i18n，Kotlin 不新增硬编码业务提示。
- UI 必须覆盖入口、加载、空、错误、取消、权限/数据缺失和可执行恢复反馈。
- Desktop 非测试构建只能使用 `./scripts/build-desktop.sh`，Windows 验收固定未打包 EXE；Android 运行时由当前任务自行部署模拟器验证。
- 每个 Task 单独提交；implementer 不勾选本计划或 OpenSpec tasks，勾选与进度提交由主协调者完成。

## 执行状态

- [x] Task 1：权威 fixture、调用链清单与产品保护网
- [x] Task 2：共享源查询状态、分页与错误语义
- [x] Task 3：共享扩展目录、版本、仓库部分失败与信任模型
- [x] Task 4A：共享安装事务状态机
- [x] Task 4B：Desktop install port 与 reload 回滚
- [x] Task 4C：Android 安装事务/session 生命周期
- [x] Task 4D：Android 信任、receiver 可见性与精确回滚
- [x] Task 5A：共享登录会话与 Desktop Cookie 原子提交
- [x] Task 5B：Desktop 挑战恢复策略与 FlareSolverr 显式后备
- [x] Task 5C：Desktop 登录设置、UI 与 production wiring
- [x] Task 6A：Browse 共享状态 wiring（6A1/6A2/6A3 已完成；C8 已闭合）
- [x] Task 6B：从固定 main 原版提取扩展呈现契约（6B1a/6B1b/6B2a/6B2b/6B2c 已完成；C9 已闭合）
- [x] Task 6C：Desktop 扩展 adapter、ScreenModel 与 DI wiring
- [x] Task 6D：Desktop Extension UI、详情/设置与 i18n wiring
- [x] Task 6E：Test Mode、导航与自动化观察
- [ ] Task 7：compat 去重、parity 证据、全量审查与跨平台运行时验收

---

### Task 1: 权威 fixture、调用链清单与产品保护网

**OpenSpec mapping:** 1.1、1.4

**Files:**
- Create: `docs/roadmap/source-extension-authority-baseline.md`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/extension/DesktopExtensionProductBaselineTest.kt`
- Create: `app-desktop/src/test/resources/extensions/compat-evidence.json`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/parity/DesktopProductCapabilityContractTest.kt`
- Reuse: `app-desktop/src/test/kotlin/mihon/desktop/extension/ApkToJarConverterTest.kt`
- Reuse: `app-desktop/src/test/kotlin/mihon/desktop/extension/ExtensionArtifactReplacementTest.kt`

**Interfaces:**
- Produces: 固定 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8` 的原版权威符号/调用链，以及迁移后 shared output、当前 Android consumer、Desktop consumer/adapter 的分层映射；同时产出代表性 JAR/APK fixture 清单、compat evidence schema 和后续共享类型的测试输入。当前 `app/`、shared output 与 Desktop shim 均不得填入原版权威层。
- Evidence JSON shape: `{"symbol":"fully.qualified.Api","fixture":"path-or-package@version","test":"repo/test/path","status":"required|unsupported","removalCondition":"text"}`。

- [x] **Step 1: 写会失败的权威证据与产品基线测试**

  `DesktopExtensionProductBaselineTest` 先要求尚不存在的 authority baseline 与 compat evidence 资源，并直接调用 production 的 APK→JAR、原子替换和扩展详情路由/文件工具逻辑：

  ```kotlin
  @Test
  fun `每个 compat API 都有真实 fixture 和保护测试`() {
      val evidence = loadCompatEvidence("extensions/compat-evidence.json")
      assertThat(evidence).isNotEmpty
      evidence.forEach {
          assertThat(repoFile(it.test)).exists()
          assertThat(it.fixture).isNotBlank()
      }
  }
  ```

- [x] **Step 2: 运行 RED 并记录正确失败原因**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.extension.DesktopExtensionProductBaselineTest"`
  Expected: FAIL，原因是 authority baseline/evidence 文件尚不存在或缺必要映射；不得因 Gradle 配置或测试初始化失败。

- [x] **Step 3: 锁定 Desktop 产品基线与 compat 证据 schema**

  `DesktopExtensionProductBaselineTest` 必须直接实例化或调用 production 的 APK→JAR、原子替换、扩展详情路由/文件工具逻辑；`DesktopProductCapabilityContractTest` 校验 manifest #34/#40 的保护测试真实存在。`compat-evidence.json` 首批只列现有真实 fixture 已触达 API，禁止预填“未来可能需要”的符号。

- [x] **Step 4: 写权威实现清单**

  文档逐项记录 Android `ExtensionApi`/`ExtensionManager`/`ExtensionLoader`、Sources/GlobalSearch/Extensions ScreenModel 与 Desktop 对应类、可直接复用能力、必须抽取能力、必须平台适配能力及真实 fixture 来源。

- [x] **Step 5: 运行产品保护测试**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.extension.ApkToJarConverterTest" --tests "mihon.desktop.extension.ExtensionArtifactReplacementTest" --tests "mihon.desktop.extension.DesktopExtensionProductBaselineTest" --tests "mihon.desktop.parity.DesktopProductCapabilityContractTest"`
  Expected: 全部 GREEN；分支上不保留待后续任务修复的失败测试。

- [x] **Step 6: 提交 Task 1**

  Commit: `test(extension): characterize source and extension authority`

### Task 2: 共享源查询状态、分页与错误语义

**OpenSpec mapping:** 1.2、2.1

**Files:**
- Modify: `domain/src/commonMain/kotlin/tachiyomi/domain/source/service/SourceMangaSearchService.kt`
- Create: `domain/src/commonMain/kotlin/tachiyomi/domain/source/service/SourceQueryState.kt`
- Modify: `domain/src/jvmTest/kotlin/tachiyomi/domain/source/service/SourceMangaSearchServiceTest.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/source/SourceHttpParityIntegrationTest.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/browse/SourceBrowseScreen.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/browse/GlobalSearchScreen.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreenModel.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/globalsearch/SearchScreenModel.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/ui/browse/source/SourceSharedQueryWiringTest.kt`

**Interfaces:**
- Produces: `SourceQuery`, `SourcePageRequest(sourceId, page, generation, query)`, `SourcePageResult.Content/Empty/Failure`, `SourceRecoveryAction`。
- Consumes: existing `SourceMangaSearchRequest`, `CatalogueSource`, `AppError` mapper。

- [x] **Step 1: 扩充 RED 契约**

  覆盖 popular/latest/search、第一页空、后续页失败保留旧内容、403→OpenLogin、429/500→Retry、畸形解析→稳定 `AppError`、取消以及旧 generation 结果被丢弃：

  ```kotlin
  @Test
  fun `晚到的旧 generation 不覆盖新查询`() = runTest {
      val reducer = SourceQueryReducer()
      val current = reducer.start(SourcePageRequest(1, 1, 2, SourceQuery.Search("new")))
      val stale = reducer.reduce(current, generation = 1, page = MangasPage(listOf(old), false))
      assertThat(stale).isEqualTo(current)
  }
  ```

- [x] **Step 2: 运行 RED**

  Run: `./gradlew :domain:jvmTest --tests "tachiyomi.domain.source.service.SourceMangaSearchServiceTest"`
  Expected: FAIL，缺少共享 result/reducer 或错误映射。

- [x] **Step 3: 实现最小共享查询核心**

  保留 `loadPage()` 为唯一源调用；新增包装方法返回 `SourcePageResult`，异常统一交给现有 `AppError` 映射。`SourceQueryReducer` 只接受等于当前 generation 的结果；后续页失败保留 items 并附带 page error。

- [x] **Step 4: 写 MockWebServer 真实解析集成测试**

  代表性 `HttpSource` 从服务器读取真实形状 JSON，覆盖 success、empty、403、429、500、malformed；不得 mock parser。断言 HTTP→source parser→共享结果的完整链路。

- [x] **Step 5: 接入 Android/Desktop production 查询链**

  Desktop `SourceBrowseScreen`/`GlobalSearchScreen` 移除自行拼接异常字符串和重复翻页终止规则；Android ScreenModel 使用同一 result/reducer。UI 保留现有页面和宽屏布局，只消费 Loading/Content/Empty/Failure 与 recovery action。

- [x] **Step 6: 运行 GREEN 与 wiring 测试**

  Run: `./gradlew :domain:jvmTest --tests "tachiyomi.domain.source.service.SourceMangaSearchServiceTest" :app-desktop:jvmTest --tests "mihon.desktop.source.SourceHttpParityIntegrationTest" --tests "mihon.desktop.ui.browse.*" :app:testReleaseUnitTest --tests "*BrowseSource*" --tests "*GlobalSearch*"`
  Expected: 全部 PASS，HTTP 6 类场景无失败。

- [x] **Step 7: 提交 Task 2**

  Commit: `refactor(source): share query state and errors`

### Task 3: 共享扩展目录、版本、仓库部分失败与信任模型

**OpenSpec mapping:** 1.3（版本/信任/损坏产物契约部分）、2.2（目录/版本/安全部分）、3.2（信任部分）

**Risk axis:** trust-continuity
**Platform boundary:** verification
**Estimated scope:** 17 files, 1332 lines
**Verification:** 审核既有实现提交 `0502b755fb`，并重跑共享目录/信任契约、Android/Desktop production wiring、版本、更新与兼容性测试。
**Split waiver:** 实现已作为单一历史提交 `0502b755fb` 存在；事后拆成平台子任务会伪造提交与任务证据边界。Task 继续保持未勾选，直到协调者完成该提交的验证与 checkoff。

**Files:**
- Create: `domain/src/commonMain/kotlin/mihon/domain/extension/model/ExtensionArtifact.kt`
- Create: `domain/src/commonMain/kotlin/mihon/domain/extension/model/ExtensionCatalog.kt`
- Create: `domain/src/commonMain/kotlin/mihon/domain/extension/service/ExtensionCatalogService.kt`
- Create: `domain/src/commonMain/kotlin/mihon/domain/extension/service/ExtensionTrustPolicy.kt`
- Create: `domain/src/jvmTest/kotlin/mihon/domain/extension/ExtensionSharedContractTest.kt`
- Create: `domain/src/jvmTest/kotlin/mihon/domain/extension/ExtensionCatalogServiceTest.kt`
- Modify: `domain/src/commonMain/kotlin/mihon/domain/extensionrepo/service/ExtensionRepoDto.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/extension/DesktopAvailableExtension.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/extension/api/ExtensionApi.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/extension/DesktopExtensionApi.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/extension/ExtensionMeta.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/extension/ExtensionUpdateUtils.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/extension/DesktopExtensionApiSharedCatalogTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/extension/ExtensionUpdateDetectionTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/extension/ExtensionVersionMetaTest.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/extension/ExtensionManager.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/extension/api/ExtensionApiSharedCatalogTest.kt`

**Interfaces:**
- Produces: `ExtensionArtifact`, `RepositoryIdentity`, `ExtensionCatalogEntry`, `ExtensionCompatibility`, `RepositoryFetchResult`, `ExtensionTrustDecision`。
- `ExtensionCatalogService.refresh(repositories, fetch): ExtensionCatalogResult` 保留成功条目并逐仓库返回失败。

- [x] **Step 1: 写 RED 目录/版本/信任测试**

  在 `ExtensionSharedContractTest` 构造 `ExtensionArtifact` 并反射断言 common 模型不包含 `File`/Android 类型；同时覆盖相同 index 在 Android/Desktop mapper 结果一致、lib version 边界、更新可用、所有仓库空、多仓库部分失败、摘要不符、仓库身份切换、旧 sidecar 缺身份进入 TrustRequired。

- [x] **Step 2: 运行 RED**

  Run: `./gradlew :domain:jvmTest --tests "mihon.domain.extension.ExtensionSharedContractTest" --tests "mihon.domain.extension.ExtensionCatalogServiceTest"`
  Expected: FAIL，原因是共享模型/service/policy 缺失。

- [x] **Step 3: 实现共享 DTO mapper 与目录聚合**

  将 Android/Desktop 重复 `ExtensionJsonObject` 映射迁到 common；仓库请求仍由平台 HTTP client 提供。结果必须区分 `entries.isEmpty() && failures.isEmpty()` 与部分失败，不能 catch 后返回 `emptyList()`。

- [x] **Step 4: 实现明确的信任决策**

  ```kotlin
  sealed interface ExtensionTrustDecision {
      data object Trusted : ExtensionTrustDecision
      data class ConfirmationRequired(val reasons: Set<TrustMismatch>) : ExtensionTrustDecision
      data class Rejected(val error: AppError) : ExtensionTrustDecision
  }
  ```

  校验仓库 identity、声明 SHA-256、实际 SHA-256 和已安装来源连续性；Android signature 作为 Android adapter 附加 evidence，Desktop UI 不显示“APK 签名已验证”。

- [x] **Step 5: Android/Desktop API 改为薄 HTTP/平台 adapter**

  两端 API 复用共享 mapper、版本与聚合；删除 `DesktopExtensionApi.fetchExtensionsFromRepo()` 的吞错空列表语义。为 production wiring 写回归测试，确保改坏 shared service 时两端测试失败。

- [x] **Step 6: 运行 GREEN**

  Run: `./gradlew :domain:jvmTest --tests "mihon.domain.extension.*" :app-desktop:jvmTest --tests "mihon.desktop.extension.ExtensionVersionMetaTest" --tests "mihon.desktop.extension.ExtensionUpdateDetectionTest" --tests "mihon.desktop.extension.ExtensionCompatibilityTest" :app:testReleaseUnitTest --tests "*ExtensionApi*"`
  Expected: 全部 PASS，部分失败保留成功仓库结果。

- [x] **Step 7: 提交 Task 3**

  Commit: `refactor(extension): share catalog version and trust rules`

### Task 4A: 共享安装事务状态机

**OpenSpec mapping:** 1.3（JAR/APK→JAR/回滚/不兼容 API 部分）、2.2（事务/回滚部分）、2.3、3.1、3.2（原子回滚部分）

**Risk axis:** install-state-machine
**Platform boundary:** shared
**Estimated scope:** 3 files, 350 lines
**Verification:** 运行共享 coordinator 状态机测试，覆盖阶段顺序、取消、互斥、reload 失败回滚与 rollback 失败优先级。

**Files:**
- Create: `domain/src/commonMain/kotlin/mihon/domain/extension/service/ExtensionInstallCoordinator.kt`
- Create: `domain/src/commonMain/kotlin/mihon/domain/extension/service/ExtensionInstallPort.kt`
- Create: `domain/src/jvmTest/kotlin/mihon/domain/extension/ExtensionInstallCoordinatorTest.kt`

**Interfaces:**
- `ExtensionInstallPort.prepare/validate/commit/reload/rollback/cleanup`；port token 使用共享 opaque ID，不暴露 `File`。
- `ExtensionInstallCoordinator.install(request): Flow<ExtensionInstallState>`；同 package 单飞，不同 package 可并行。

- [x] **Step 1: 写 RED 状态机测试**

  覆盖成功阶段顺序、Prepare/Validate 失败不提交、Commit 后 reload 失败回滚旧 artifact+metadata、rollback 失败高优先级错误、取消临时文件清理、同 package 去重、不同 package 并行。

- [x] **Step 2: 运行 RED**

  Run: `./gradlew :domain:jvmTest --tests "mihon.domain.extension.ExtensionInstallCoordinatorTest"`
  Expected: FAIL，缺少 coordinator/port/state。

- [x] **Step 3: 实现最小共享 coordinator**

  coordinator 只编排阶段、取消、互斥与错误；不读取文件、不转换 APK、不加载 class。只有 `reload()` 成功才 emit `Installed`；commit 后任何异常必须调用 rollback，并验证旧 runtime 恢复。

- [x] **Step 4: 运行共享 GREEN**

  Run: `./gradlew :domain:jvmTest --tests "mihon.domain.extension.ExtensionInstallCoordinatorTest"`
  Expected: 全部 PASS；只有 reload 成功后发布 Installed。

- [x] **Step 5: 提交 Task 4A**

  Commit: `refactor(extension): share install state machine`

### Task 4B: Desktop install port 与 reload 回滚

**OpenSpec mapping:** 1.3（JAR/APK→JAR/回滚/不兼容 API 部分）、2.2、2.3、3.1、3.2（Desktop 原子回滚部分）

**Risk axis:** desktop-artifact-rollback
**Platform boundary:** shared+desktop
**Estimated scope:** 11 files, 2383 lines
**Verification:** 运行 Desktop 事务集成、APK→JAR 与原子替换保护测试，确认 reload 失败后旧 artifact、metadata 和 runtime 均恢复。
**Split waiver:** thorough review 证明原 5 文件边界无法同时满足“API 只提供 artifact”与“真实 DI Manager 在共享事务内 reload/rollback”；API 签名、DI 单例、UI 调用、长生命周期 coordinator、路径/文件 journal 及其 production wiring 测试必须原子迁移才能保持编译与产品链有效。拆成独立 Task 会产生临时 Manager、事务外 reload、未接线 adapter 或不能击穿 production wiring 的测试半成品，无法独立调度与验收；因此保留为同一 `desktop-artifact-rollback` 风险轴的 review repair。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/extension/DesktopExtensionApi.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/extension/DesktopExtensionLoader.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/extension/DesktopExtensionManager.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/extension/DesktopExtensionInstallPort.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/extension/DesktopExtensionInstallTransactionTest.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/extension/ExtensionListScreen.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/extension/DesktopExtensionApiSharedCatalogTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/extension/ExtensionArtifactReplacementTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/extension/DesktopExtensionProductBaselineTest.kt`

**Interfaces:**
- Consumes: Task 4A 的 `ExtensionInstallPort` 与 `ExtensionInstallCoordinator`。
- Produces: Desktop 文件、APK→JAR、ClassLoader、sidecar 与 runtime 恢复 adapter。

- [x] **Step 1: 写 Desktop 事务集成 RED**

  用临时目录制造 JVM JAR、DEX APK、损坏 ZIP、错误 package、转换失败、摘要错误和 fake loader reload 失败；断言旧 JAR/sidecar hash 不变、无 `.tmp/.backup` 残留、旧 source 可重新获取。

- [x] **Step 2: 运行 RED**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.extension.DesktopExtensionInstallTransactionTest"`
  Expected: FAIL，原因是 Desktop 仍自行编排事务或 reload 失败未恢复旧 runtime。

- [x] **Step 3: 收敛 Desktop installer/loader**

  `DesktopExtensionInstallPort` 承担文件、APK→JAR、ClassLoader 和原子 side effect；`DesktopExtensionApi` 只下载/提供 artifact，`DesktopExtensionManager` 只映射共享状态与刷新 runtime。

- [x] **Step 4: 运行 GREEN 与产品保护**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.extension.DesktopExtensionInstallTransactionTest" --tests "mihon.desktop.extension.ApkToJarConverterTest" --tests "mihon.desktop.extension.ExtensionArtifactReplacementTest"`
  Expected: 全部 PASS；reload 失败可见且旧版本仍工作。

- [x] **Step 5: 提交 Task 4B**

  Commit: `refactor(desktop): adapt transactional extension install`

### Task 4C: Android 安装事务/session 生命周期

**OpenSpec mapping:** 2.3、3.1、3.2（Android PackageInstaller 事务关联、取消与有界结束）

**Risk axis:** android-install-session-lifecycle
**Platform boundary:** shared+android
**Estimated scope:** 9 files, 2016 lines
**Verification:** 执行真实 Android session/callback seam，覆盖 success、error、abort、PendingUserAction、duplicate/late callback、cancel-before-enqueue、service destroy、timeout、同包重试与 hash collision；每个事务只能有一个 terminal，超时/取消后 session、receiver 和 flight 必须释放。
**Split waiver:** 五轮审查修复后累计 2016 changed lines 中，1396 行是同一 Android lifecycle production-wiring 契约矩阵；另 36 行为 shared coordinator 的最后订阅者取消/flight completion 契约及测试。production UUID 必须原子贯穿 manager → intent/activity/service → base queue → PackageInstaller session → callback/deferred；process-wide durable tombstone、queue 线性化、startup cancellation/platform handoff 原子交接、`NEW → HANDED_OFF → FINISHING → COMPLETE` 持久阶段、PackageInstaller commit identity、cleanup acknowledgement、Shizuku 延迟 callback 与最后订阅者等待真实 flight completion 必须在同一事务生命周期内共同收口。shared 改动仅强化最后订阅者的内部取消完成时序，不新增业务 capability；若留在独立 Task，Android lifecycle 仍会在 rollback/cleanup/flight 完成前提前 COMPLETE 或永久 FINISHING，无法独立验收。collision、late callback、tombstone/TTL、timeout、abandon、PendingUserAction、Shizuku、unsubscribe 与 terminal CAS 共同定义单一 session-lifecycle 风险轴，测试矩阵不能在不丢失端到端 production-wiring mutation 审查闭环的前提下再独立调度。

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/extension/ExtensionManager.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionInstaller.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionInstallService.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/extension/installer/Installer.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/extension/installer/PackageInstallerInstaller.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionInstallActivity.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/extension/ExtensionInstallSessionLifecycleTest.kt`
- Modify: `domain/src/commonMain/kotlin/mihon/domain/extension/service/ExtensionInstallCoordinator.kt`
- Modify: `domain/src/jvmTest/kotlin/mihon/domain/extension/ExtensionInstallCoordinatorTest.kt`

**Interfaces:**
- Consumes: 当前 Task 4C 基线提交 `9965e2257` 已接入的共享 coordinator/Android install port。
- Produces: 贯穿 intent、queue、session、deferred 和 callback 的 UUID transaction ID；同时匹配 transaction/session 的 exactly-once terminal；有界 wait、abandon/unregister 与 cancel tombstone。Task 4D 必须复用该 system install/restore 原语。
- Boundary: Shizuku AIDL callback 协议不在本 Task 扩展，但基类 queue entry 必须兼容现有 Shizuku 串行路径。

- [x] **Step 1: 用 production seam 写 session 生命周期 RED**

  新建 `ExtensionInstallSessionLifecycleTest`，覆盖 success、error、abort、PendingUserAction、duplicate、late-after-cancel、cancel-before-enqueue、service destroy/no callback、timeout、同包重试和两个 hash-collision 包。断言取消后不再出现 Installing，每个事务只有一个 terminal。

- [x] **Step 2: 运行 RED 并确认失败原因**

  Run: `./gradlew :app:testReleaseUnitTest --tests "*ExtensionInstallSessionLifecycleTest" --tests "*ExtensionInstallCoordinatorWiringTest"`
  Expected: 至少因同包迟到回调或 cancel-before-enqueue 失败，不得是夹具初始化错误。

- [x] **Step 3: 实现 transaction/session 关联与有界终止**

  用 UUID 取代 `packageName.hashCode()`；intent、queue entry、cancel broadcast、legacy activity result 和 PackageInstaller callback 全程携带 transaction ID。PackageInstaller terminal/PendingUserAction 同时匹配 active transaction ID 与 session ID；duplicate/late callback 忽略。超时、取消或 service destroy 时通过 exactly-once CAS 完成并 abandon session、注销 receiver；enqueue 前检查短期 cancel/complete tombstone。

- [x] **Step 4: 运行 GREEN、回归与 mutation 义务**

  Run: `./gradlew :app:testReleaseUnitTest --tests "*ExtensionInstallSessionLifecycleTest" --tests "*ExtensionInstallCoordinatorWiringTest"`
  Run: `./gradlew :app:testReleaseUnitTest --tests "*ExtensionManager*" --tests "*PackageInstaller*" --tests "*ShizukuInstaller*"`
  Run: `./gradlew spotlessCheck`
  Mutations: UUID 改回 package hash、去掉 transaction/session 任一校验、去掉 timeout/abandon、删除 cancel tombstone、绕过 terminal CAS，对应 collision/late/no-callback/cancel-before-enqueue/terminal-count 测试必须失败。

- [x] **Step 5: 提交 Task 4C**

  Commit: `fix(android): bind extension install sessions to transactions`

### Task 4D: Android 信任、receiver 可见性与精确回滚

**OpenSpec mapping:** 2.2、2.3、3.1、3.2（Android artifact 信任、PackageInstaller/签名边界与原子回滚）

**Risk axis:** android-install-trust-rollback
**Platform boundary:** android
**Estimated scope:** 7 files, 2926 lines
**Verification:** 执行真实 `AndroidInstallPort` 与 Manager/receiver production wiring，验证 repository fingerprint、declared/downloaded SHA、APK signer、Untrusted 终态、receiver 可见性，以及 fresh/private/system/双安装/跨侧切换/downgrade/expected-absent 的物理与 runtime 精确回滚。
**Split waiver:** 实际 2926 changed lines（+2680/-246）分布在 7 个 Android 产品/测试文件：931 行承载共享 trust policy、双侧 `InstallPreState`、冻结 commit plan、private 原子替换、Task 4C parent/child system session 与 stale-child cancellation fallback、有界 system uninstall、APK identity/feature 绑定、分侧 trust sidecar、cleanup journal 与结构化错误的单一 production gateway 链路；1995 行是同一链路的 trust/topology/storage/session/failure production-wiring 测试矩阵。本 Task 的同一 commit 前状态同时决定旧 metadata 的信任连续性、receiver 是否可暴露新 runtime、private/system 哪一侧需删除或恢复，以及 restore reload 应期待旧包还是无包。再拆分 trust/visibility、session identity 与 rollback/storage 会产生不安全的中间 production 状态，或无法用真实 Default gateway 证明 Task 4C bridge、卸载超时与物理回滚属于同一事务。超额行数来自四轮审查中 1 Critical + 13 Important 的闭环和可杀死这些缺陷的生产链路测试，而非无关重构。

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/extension/model/Extension.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/extension/api/ExtensionApi.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/extension/ExtensionManager.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionInstaller.kt`
- Modify: `app/src/test/java/eu/kanade/tachiyomi/extension/ExtensionInstallCoordinatorWiringTest.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/extension/AndroidExtensionInstallSecurityRollbackTest.kt`
- Modify: `app/src/test/java/eu/kanade/tachiyomi/extension/ExtensionInstallSessionLifecycleTest.kt`

**Interfaces:**
- Consumes: Task 4C 的 transaction ID、有界 session wait、abandon/unregister 与 system restore 原语。
- Produces: 无损 `ExtensionArtifact` 身份；基于 `ExtensionTrustPolicy`/`SharedExtensionUpdatePolicy` 的校验；真实 Android package/file/runtime gateway seam；双侧 `InstallPreState` 与幂等精确回滚。
- Boundary: 用户手动信任 untrusted 扩展仍由既有 UI 入口处理；`ConfirmationRequired` 不得被静默转换为安装成功。

- [x] **Step 1: 写信任、可见性与拓扑回滚 RED**

  从真实 MockWebServer catalog 响应断言 fingerprint、repo name、declared SHA 和 download URL 保留到 install request。用 production Android port seam 覆盖 repo/SHA/signer、Untrusted、receiver 提前广播、fresh-private、fresh-system、existing-system、private→system、system→private、双安装、system downgrade、expected-absent、readonly、copy/delete failure retry、恶意 path、403/429/500/断网/写盘失败。

- [x] **Step 2: 运行 RED 并确认失败原因**

  Run: `./gradlew :app:testReleaseUnitTest --tests "*AndroidExtensionInstallSecurityRollbackTest" --tests "*ExtensionInstallCoordinatorWiringTest" --tests "*ExtensionApiSharedCatalogTest"`
  Expected: 至少 catalog metadata 和一个混合拓扑用例因 production 行为失败，不得靠反射向 private map 注入 token 制造 RED。

- [x] **Step 3: 实现信任校验、receiver gate 和精确回滚**

  `Extension.Available` 无损保存 repository identity 与 declared SHA；validate 校验 downloaded SHA、repository continuity、APK package/version/signers 与共享版本策略。下载只落 UUID 事务目录并校验 canonical containment，HTTP 复用 auth/rate/server taxonomy，本地 IO 映射 `AppError.Storage`。`InstallPreState` 记录 private/system 两侧存在性、只读 APK snapshot、version/signers、loader origin、commit target 与 expected-absent；private 用 temp → readonly → atomic replace，system 复用 Task 4C 受控 session。active transaction 的 package/private 广播不得直接修改 runtime map，仅 `LoadResult.Success` 可发布 Installed。

- [x] **Step 4: 运行 GREEN、回归与 mutation 义务**

  Run: `./gradlew :app:testReleaseUnitTest --tests "*AndroidExtensionInstallSecurityRollbackTest" --tests "*ExtensionInstallCoordinatorWiringTest" --tests "*ExtensionApiSharedCatalogTest"`
  Run: `./gradlew :app:testReleaseUnitTest --tests "*ExtensionManager*" --tests "*ExtensionInstallSessionLifecycleTest"`
  Run: `./gradlew spotlessCheck`
  Mutations: 丢弃 fingerprint/SHA/signer 校验、将 Untrusted 当成功、允许 receiver 提前改 map、token 退化为单 snapshot、遗漏 fresh 侧删除/system downgrade、将 expected-absent 当 loader error、忽略 delete=false/readonly/containment，或把 HTTP/本地 IO 错误折叠为 Network/Unknown，对应行为测试必须失败。

- [x] **Step 5: 提交 Task 4D**

  Commit: `fix(android): enforce trusted atomic extension installs`

### Task 5A: 共享登录会话与 Desktop Cookie 原子提交

**OpenSpec mapping:** 3.3（会话状态、取消/超时、Cookie 回传原子性）

**Risk axis:** login-session-atomicity
**Platform boundary:** shared+desktop
**Estimated scope:** 6 files, 1430 lines
**Verification:** 运行共享登录会话、Desktop browser adapter 与真实 `DesktopCookieJar` 行为测试；确认 success 只提交目标域完整 Cookie set 一次，cancel/timeout/browser unavailable 不写 jar 且保留旧 Cookie。
**Split waiver:** 实际 1430 changed lines（+1389/-41）中，898 行是 shared session、initiator-bound Desktop completion ticket、PSL-aware domain validation 及其状态/并发/安全行为矩阵，532 行是既有 `DesktopCookieJar` 的 canonical identity、Cookie.matches/hostOnly、完整 target delivery-scope 跨 bucket 替换、事务持久化、late-replace 精确回滚与 barrier 回归。共享 success/cancel/timeout/commit 边界只有穿过真实 committer 与 jar 的整组验证、内存替换、临时文件原子落盘、失败恢复和锁可见性后才构成可交付能力；若拆开，shared/adapter 子任务可独立全绿但仍无法证明 public-suffix 凭据不会跨源泄漏，或取消、持久化失败、并发读取不会半写/暴露凭据。超额来自同一 `login-session-atomicity` 风险轴及四轮审查要求关闭的 10 个 Important，而非 UI、challenge policy 或无关重构。

**Files:**
- Create: `domain/src/commonMain/kotlin/tachiyomi/domain/source/service/SourceLoginSession.kt`
- Create: `domain/src/jvmTest/kotlin/tachiyomi/domain/source/service/SourceLoginSessionTest.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/network/DesktopBrowserLoginAdapter.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/network/DesktopBrowserLoginAdapterTest.kt`
- Modify: `core/common/src/jvmMain/kotlin/eu/kanade/tachiyomi/network/DesktopCookieJar.kt`
- Modify: `core/common/src/jvmTest/kotlin/eu/kanade/tachiyomi/network/DesktopCookieJarTest.kt`

**Interfaces:**
- Produces: `SourceLoginRequest`、`SourceLoginState`、`AuthenticatedSession`、`BrowserLoginAdapter.open(request)`；Desktop 外部浏览器无法读取浏览器私有 Cookie，adapter 通过受控 completion seam 接收 UI/平台捕获的完整 session。
- `DesktopCookieJar` 提供目标域 session 的一次性验证与原子替换；磁盘持久化失败不得留下内存半提交或覆盖既有 Cookie。

- [x] **Step 1: 写 shared session 与 Cookie 原子性 RED**
- [x] **Step 2: 运行 RED 并确认 success/cancel/timeout/unavailable/domain-filter/atomic-persist 的失败原因**
- [x] **Step 3: 实现最小 shared session、Desktop adapter 与 jar 原子提交**
- [x] **Step 4: 运行 GREEN、旧 Cookie jar 持久化回归与 mutation**
- [x] **Step 5: 提交 Task 5A**

  Run: `./gradlew :domain:jvmTest --tests "tachiyomi.domain.source.service.SourceLoginSessionTest" :app-desktop:jvmTest --tests "mihon.desktop.network.DesktopBrowserLoginAdapterTest" :core:common:jvmTest --tests "eu.kanade.tachiyomi.network.DesktopCookieJarTest"`
  Commit: `feat(desktop): add atomic source login sessions`

### Task 5B: Desktop 挑战恢复策略与 FlareSolverr 显式后备

**OpenSpec mapping:** 3.3（挑战恢复动作与显式后备策略）

**Risk axis:** challenge-recovery-policy
**Platform boundary:** shared+desktop
**Estimated scope:** 8 files, 4400 lines
**Verification:** 运行真实 interceptor/challenge manager 策略测试，确认 403/503 只发布登录请求；browser、手动 Cookie 和 FlareSolverr 均由显式用户 intent 触发，取消/超时不清除或写入凭据，solver 从不由 interceptor 自动调用。
**Split waiver:** 实际 2534 changed lines（+2458/-76）分布在 7 个 shared+desktop 文件：714 行是 challenge/manager/interceptor/client 的 immutable per-attempt lifecycle、commit-point、单调 terminal/state、active-job 抢占、有界 timeout、真实 jar 校验的 UA+clearance identity/expiry 生命周期、稳定快照、同 host striped commit 序列化、IO dispatcher、显式 recovery intents、单次重试和 cancellable HTTP；183 行是 Task 5A shared required Cookie 非空量词、canonical normalized commit session、有限本地 committer 契约及测试；1637 行是同一真实链路的 36 项 MockWebServer→OkHttp interceptor→manager→Task 5A validation/atomic committer→DesktopCookieJar 策略/并发/HTTP/安全矩阵。shared 改动统一保护 browser/manual/solver 三条入口，避免 Desktop 复制提交规则。clear-first、自动 solver、committer 旁路、重复 retry、cancel/timeout/late completion、commit claim 双向竞态、register/self-cancel 窗口、阻塞 socket/UI dispatcher、UA 不匹配/过期/替换/lookup交错、HTTP 403/429/500/缺 solution、mixed required Cookie、真实持久化失败→Retry、old waiter/new deadline 与无界 host lock 共同决定恢复是否会误写/泄露凭据；拆开会留下虚假 terminal、不可用 clearance、共享/平台规则分叉或无法穿透 production 链的中间状态，不能独立验收。Task 5C 的 UI/设置/DI production entry wiring 未混入本 Task。

**Scope update:** 最终累计 8 个 shared+desktop 文件，+3908/-88（3996 changed lines）；本数值取代上方 waiver 的历史 7 文件/2534 行计数。新增范围仅为 `DesktopNetworkHelper` 的 production application/network interceptor wiring，以及同一 policy 测试中的 final outbound Cookie-header/UA 配对、显式 header provenance、严格 parser、真实 helper 503→recovery→单次 retry 和 timeout 确定性交错矩阵；原 waiver 关于单一 challenge-recovery-policy 风险轴、不可拆分 production 链和 Task 5C 边界的理由保持不变。
**Final timeout closure:** provenance 终审确认此前 Cookie provenance 与真实 helper wiring 两项 Important 均已关闭，但暴露一个新的确定性问题：solver 内层 `withTimeout` 与 `awaitTerminal` 使用同一绝对 deadline，先后顺序会令已确认 in-flight 的 recovery 有时正常返回、有时被取消。最后一轮窄修必须让已注册 solver 超时先发布唯一 `TimedOut` terminal，再确定性传播 cancellation；action 注册前已经过期仍正常返回 `TimedOut`。测试必须分别以确定性路径覆盖两种语义，不能依赖两个同 deadline 定时器的调度顺序。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/network/CloudflareChallenge.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/network/CloudflareChallengeManager.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/network/DesktopCloudflareInterceptor.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/network/FlareSolverrClient.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopNetworkHelper.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/network/DesktopChallengeRecoveryPolicyTest.kt`
- Modify: `domain/src/commonMain/kotlin/tachiyomi/domain/source/service/SourceLoginSession.kt`
- Modify: `domain/src/jvmTest/kotlin/tachiyomi/domain/source/service/SourceLoginSessionTest.kt`

**Interfaces:**
- Consumes: Task 5A `SourceLoginRequest`/session completion；produces explicit `OpenBrowser`、`SubmitManualCookies`、`UseFlareSolverr`、`Cancel`/`Retry` intents。
- Interceptor 只检测挑战、等待有界 session terminal 并重试一次；不得删除已有 clearance Cookie 后再等待失败，也不得直接持有或调用 solver。

- [x] **Step 1: 写 challenge policy/旧 Cookie 保留/solver 非自动调用 RED**
- [x] **Step 2: 运行 RED 并确认现有 latch/clear-first 行为失败**
- [x] **Step 3: 实现显式恢复 intents 与有界 terminal**
- [x] **Step 4: 运行 GREEN、FlareSolverr HTTP 回归与 mutation**
- [x] **Step 5: 提交 Task 5B**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.network.DesktopChallengeRecoveryPolicyTest" --tests "mihon.desktop.network.FlareSolverrClientTest"`
  Commit: `refactor(desktop): require explicit challenge recovery`

### Task 5C: Desktop 登录设置、UI 与 production wiring

**OpenSpec mapping:** 3.3、3.5（登录 UI、用户设置、脱敏与 i18n）

**Risk axis:** challenge-login-ui-wiring
**Platform boundary:** desktop
**Estimated scope:** 11 files, 1750 lines
**Verification:** 运行 UI/DI production-wiring 测试，确认对话框展示目标域、进度、取消、超时、重试、手动导入；仅在设置启用且 URL 有效时显示并执行 FlareSolverr，所有日志/状态不包含 Cookie 值，触达文案使用 i18n。
**Execution split:** 原 7 文件估算遗漏了既有 Task 5A initiator-bound browser completion seam、Task 5B 动态 solver provider、真实 CookieJar committer 与 `DesktopUiDependencies` 接点。为满足每个调度单元不超过 8 文件/400 行，Task 5C 连续拆为：5C-A1 `challenge-login-runtime-wiring`（desktop，7 files/400 lines：偏好契约、per-challenge browser bridge、动态 solver provider、真实 jar committer 与 DI）；5C-A2 `challenge-login-runtime-review-closure`（desktop，4 files/100 lines：独立审查要求的 exact-jar credential lookup、真实 outbound Cookie/UA、IDN canonical URL 与 interface identity）；5C-B `challenge-login-dialog-flow`（desktop，4 files/400 lines：Home/Dialog 状态与所有恢复动作、基础 i18n、行为测试）；5C-B2 `challenge-login-dialog-review-closure`（desktop，4 files/240 lines：独立审查要求的根路径 clearance、timeout 纯 UI 关闭、可见成功反馈与可杀死的 Home action adapter wiring）；5C-C `challenge-login-settings-i18n`（desktop，3 files/250 lines：Advanced 设置入口、持久反馈、资源完整性与回归）；5C-C2 `challenge-login-settings-review-closure`（desktop，3 files/240 lines：Cloudflare 手工登录/清理文案 i18n 与可杀死的 Compose settings-item wiring）；5C-C3 `challenge-login-i18n-proof-closure`（desktop，2 files/120 lines：资源身份 token resolver 证明等价英文硬编码也会失败）。全部通过独立审查后才勾选本 Task；OpenSpec 3.3/3.5 也只在全部完成后 checkoff。
**Split waiver:** 本 Task 顶层的 11 files/1750 lines 是七个已独立调度、独立 TDD/修复验证、独立提交和独立审查的单元聚合值，并非交给一个实现者的实际 scope；5C-A1 为 7/400、5C-A2 为 4/100、5C-B 为 4/400、5C-B2 为 4/240、5C-C 为 3/250、5C-C2 为 3/240、5C-C3 为 2/120，均未超过门槛。5C-B2、5C-C2 与 5C-C3 只关闭对应独立审查确认的 Important，不扩张 capability；C3 只补强验证证据，不改变业务语义。把修复硬塞回已完成的原调度单元会破坏单次 scope 和审查证据边界。保留一个顶层 Task 是因为这些单元共同交付同一个 OpenSpec 3.3/3.5 用户能力且只能在全部 production wiring、UI 与设置资源完成后 checkoff；将任一单元单独视为完整 capability 会产生无入口的基础设施或无真实 committer/provider 的假 UI。

- [x] **Task 5C-A: runtime、动态后备与 DI wiring**
- [x] **Task 5C-B: 挑战对话框、Home flow 与基础 i18n**
- [x] **Task 5C-B2: 对话框审查闭环、终态反馈与 Home action wiring**
- [x] **Task 5C-C: 高级设置、持久反馈与资源完整性**
- [x] **Task 5C-C2: 设置审查闭环、完整 Cloudflare i18n 与 Compose wiring**
- [x] **Task 5C-C3: 等价硬编码 mutation 与 i18n production-usage 证明**

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/settings/DesktopAppPreferences.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/network/CloudflareChallengeManager.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/network/DesktopBrowserLoginAdapter.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/DesktopUiDependencies.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/settings/AdvancedSettingsScreen.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/cloudflare/CloudflareBypassDialog.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/home/HomeScreen.kt`
- Modify: `i18n/src/commonMain/moko-resources/base/strings.xml`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/ui/cloudflare/DesktopChallengeLoginWiringTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt`

**Interfaces:**
- Consumes: Task 5A/5B session、recovery intents、Cookie commit 与 solver client；production UI 是所有用户可见 recovery intent 的唯一触发入口。
- FlareSolverr 默认关闭；启用开关与 URL 都持久化，URL 无效时 UI 给出可执行反馈而不发网络请求。
- AWT 外部浏览器不能读取其私有 Cookie store；`OpenBrowser` 必须通过 Task 5A initiator-bound ticket 与具体 `CloudflareChallenge` 绑定，UI 提交完整 session 时只完成该 challenge 的 ticket。相同 host 并发不得按 URL 或“latest”查找而串线，取消/超时必须移除 pending ticket，late completion 必须失败。
- DI 必须让 browser/manual/solver 三条成功路径复用同一 `DesktopAuthenticatedSessionCommitter` 和 `DesktopNetworkHelper.cookieJar`；FlareSolverr client 在每次显式 intent 时从当前偏好动态解析，禁用或 URL 非法时不得创建请求。

- [x] **Step 1: 写设置、UI 状态、DI 与脱敏 production-wiring RED**
- [x] **Step 2: 运行 RED 并确认入口/反馈/显式后备缺失**
- [x] **Step 3: 实现 i18n 设置与对话框 intents，接通 HomeScreen production session**
- [x] **Step 4: 运行 GREEN、Screen/DI/资源完整性与 mutation**
- [x] **Step 5: 提交 Task 5C**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.cloudflare.DesktopChallengeLoginWiringTest" --tests "mihon.desktop.di.DesktopDiWiringTest" --tests "mihon.desktop.network.CloudflareCookieImportTest" --tests "mihon.desktop.network.FlareSolverrClientTest"`
  Commit: `feat(desktop): wire recoverable source browser login`

### Task 6A: Browse 共享状态 wiring

**OpenSpec mapping:** 3.4、3.5（源浏览、全局搜索、恢复反馈部分）

**Risk axis:** source-browse-wiring
**Platform boundary:** shared+desktop
**Estimated scope:** 5 files, 620 lines
**Verification:** 运行 Browse ScreenModel 与 `SourceSharedStateWiringTest`，确认 Loading、Empty、分页保留内容、403 登录和 Retry 均来自共享状态。
**Execution split:** 删除三 Screen 各自复制的 query/loading/items/error/page 状态本身会同时产生大量删除与替换，实际完整实现无法在 320 changed lines 内保持有效 production-wiring 测试。Task 先拆为 6A1 `source-browse-shared-state`；6A2 审计又确认原 260 行估算遗漏 Global 聚合 coordinator 的 StateFlow、per-source exact retry/recovery 基础设施，完整范围预计 325–440 changed lines，因此继续顺序拆为：6A2A `global-search-stateflow-core`（2 files/170 lines）、6A2B `global-search-ui-wiring`（2 files/180 lines）、6A2C `browse-missing-source-route`（2 files/80 lines）。
**Split waiver:** 顶层 5 files/620 lines 是多个独立 TDD、提交和审查单元的聚合值，不是单个实现者一次调度范围。单源 coordinator 必须先独立全绿；Global 聚合核心再复用同一 per-source coordinator；Global UI 与 Browse 缺源导航最后分别消费。把 Global core 塞入 Screen 会复制 6A1 reducer/recovery，把三部分压回一个 260 行任务则会迫使删除行为测试或保留复制状态，违反有效验证和共享状态唯一事实源要求。

- [x] **Task 6A1: 单源共享状态、exact recovery 与 production action wiring**
  - [x] **Task 6A1R: 审查闭环——区分通用登录与 Cloudflare，并证明 Screen 重放 exact request**
  - [x] **Task 6A1R2a: 重规划——以 StateFlow 无锁外部回调保证 generation 发布顺序**
    - [x] **Task 6A1R2aR: 审查闭环——stamped StateFlow 锁外单调发布**
  - [x] **Task 6A1R2b: 重规划——真实 Desktop WebView 登录、Cookie 回传与 exact retry**
    - [x] **Task 6A1R2b1: 通用登录会话核心、Cookie Header 与真实 CookieJar**
      - [x] **Task 6A1R2b1R: 审查闭环——intent identity、attempt lifecycle 与真实出站 Cookie**
    - [x] **Task 6A1R2b2: Source Browse 登录 UI、DI、MR 与 production wiring**
      - [x] **Task 6A1R2b2a: Source login DI、generic/Cloudflare 路由与 Screen production seam**
      - [x] **Task 6A1R2b2b: Source login 对话框、MR、取消与终态反馈**
        - [x] **Task 6A1R2b2b1: Source login UI state/action lifecycle**
          - [x] **Task 6A1R2b2b1R: attempt-aware start/completion race closure**
        - [x] **Task 6A1R2b2b2: Source login Compose Dialog、MR 与 Screen render wiring**
          - [x] **Task 6A1R2b2b2a: Source login MR copy 与反馈映射**
            - [x] **Task 6A1R2b2b2aR: 五类终态反馈映射测试闭环**
          - [x] **Task 6A1R2b2b2b: Source login Compose Host 与 Screen 事件 wiring**
            - [x] **Task 6A1R2b2b2bR: stale attempt 事件隔离与真实 Dialog/Screen wiring 测试闭环**
              - [x] **Task 6A1R2b2b2bR2: cancel 拒绝保留当前 UI 状态契约**
- [x] **Task 6A2: Global 共享状态消费与 Browse 缺源入口**
  - [x] **Task 6A2A: Global StateFlow 聚合与 per-source coordinator 复用**
    - [x] **Task 6A2AR: cancellation-safe session retirement 与独立发布门禁验证**
      - [x] **Task 6A2AR2: typed retirement cause 与外部取消传播契约**
        - [x] **Task 6A2AR3: late-register typed cancel 与有界 cause-chain**
  - [x] **Task 6A2B: Global Search production projector、exact recovery 与 Dialog wiring**
    - [x] **Task 6A2B1: 当前 child 生命周期持续聚合与 recovery 状态回流**
      - [x] **Task 6A2B1R: 长期 collector 解除 per-search callback 与旧 Screen closure**
        - [x] **Task 6A2B1R2: query callback 单一路径与 session/generation 隔离**
          - [x] **Task 6A2B1R3: StateFlow 锁外 CAS 发布与 collector 跨线程重入**
    - [x] **Task 6A2B2: Global authoritative state/projector 与 per-source exact Retry**
      - [x] **Task 6A2B2R: 动态 source 列表与真实 Content collect/dispose wiring**
    - [x] **Task 6A2B3: Global generic login Dialog、反馈与删除 AWT 路径**
  - [x] **Task 6A2C: Browse 缺失 source 的 ExtensionListScreen 导航入口**
    - [x] **Task 6A2CR: 真实 BrowseTab nested Navigator 黑盒保护**

**Authority correction（C8）:** 上述 6A1/6A2 checkoff 只证明 query/page/error、聚合、恢复和现有 UI 状态 wiring，不证明固定 main 的 global-search 结果生产、完整结果行或带 query 的源导航已经对齐。Task 6A 只有在以下两个独立 Task 通过后才闭合。

#### 6A3A phase: Global Search canonical result wiring

**Execution split:** 只按 6A3A1→6A3A2 调度。批量物化必须先通过真实 Global Search production 链路建立 canonical 记录；逐卡观察随后只消费该记录，不新建第二套映射或入库逻辑。

##### 6A3A1 phase: Global Search canonical batch persistence

**Execution split:** 初始 4 files/230 lines 估算不足以同时容纳 fixed-main batch contract、真实 SQLDelight production wiring、generation 门控、失败重试与给 6A3A2 复用的数据库夹具。只按 6A3A1a→6A3A1b 调度；前者建立唯一 batch contract，后者必须直接接入该 contract，不得复制映射。

###### Task 6A3A1a: Canonical batch contract

- Risk axis: `global-search-canonical-batch`
- Platform boundary: `desktop`
- Estimated scope: `2 files, 60 lines`
- Verification: 固定 main fixture 证明 `SManga.toDomainManga(sourceId)` 保留完整字段、同源 URL 去重、不同 source 不合并，并由真实 `NetworkToLocalManga` 保留已有 favorite/initialized；恢复简化手工映射或逐项写入时测试必须失败。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/domain/SaveSourceMangaForDetails.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/domain/SaveSourceMangaForDetailsTest.kt`

###### Task 6A3A1b: Canonical production materializer wiring

- Risk axis: `global-search-canonical-materializer`
- Platform boundary: `desktop`
- Estimated scope: `5 files, 390 lines`
- Verification: 真实 Global Search → `awaitSearchResults` → 内存 SQLDelight/MangaRepository production 链证明不点击卡片也会入库，物化完成前不显示 raw 结果，已发布旧结果与未完成旧任务在 generation 切换后都不能污染新搜索，失败产生明确行级错误与 exact retry；Task 4 Authority fixture 必须消费 canonical batch API，测试夹具可被 6A3A2 复用。本 Task 保持原有点击等待/导航/刷新语义，立即 canonical 导航由 6A3A2 连同最新数据库观察统一验证。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/browse/GlobalSearchScreen.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/ui/browse/GlobalSearchResultProductionWiringTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/browse/GlobalSearchAuthorityProjectionTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/browse/GlobalSearchAuthorityWiringTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/browse/SourceSharedStateWiringTest.kt`

##### Task 6A3A2: Global Search visible-card database observation

- Risk axis: `global-search-card-observation`
- Platform boundary: `desktop`
- Estimated scope: `5 files, 145 lines`
- Verification: 以固定 main `SearchScreenModel.getManga()` 为预言机，仅为进入 composition 的结果卡按 `(sourceId, url)` 订阅 production `GetManga` flow；真实内存 SQLDelight/repository 测试在不重新搜索时更新标题、封面或收藏状态，卡片必须刷新且源搜索调用次数仍为一次；点击必须立即使用最新 canonical ID 导航，并只将对应 `listedByUrl` 原始 `SManga` 交给后台详情/章节刷新。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/browse/GlobalSearchScreen.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/browse/GlobalSearchResultProductionWiringTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/browse/GlobalSearchAuthorityWiringTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/browse/GlobalSearchSourceFilterWiringTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/browse/SourceSharedStateWiringTest.kt`

###### Task 6A3A2V: Card observation verification closure

- Risk axis: `global-search-card-observation-proof`
- Platform boundary: `desktop`
- Estimated scope: `2 files, 135 lines`
- Verification: 真实 SQL/Compose 场景证明订阅数严格小于当前实际候选数、相同 URL 的两个 source 保持独立观察且更新互不串线；捕获并阻塞原始 `SManga` 刷新参数，双击期间只导航/刷新一次；更新后的 thumbnail URL 必须通过 production `AsyncImage` 的非用户可见 test tag 可观察，不能只断言标题或收藏。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/browse/GlobalSearchScreen.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/browse/GlobalSearchResultProductionWiringTest.kt`

#### Task 6A3B: Global Search result navigation

- Risk axis: `global-search-result-navigation`
- Platform boundary: `desktop`
- Estimated scope: `4 files, 260 lines`
- Verification: 以固定 main 的 presentation `GlobalSearchScreen`/`GlobalSearchCardRow` 为预言机，真实 Compose/Voyager 测试证明源标题进入携带当前 query 的 `SourceBrowseScreen`、每源结果不再 `.take(10)` 截断，并保留 Desktop 防重复打开与后台详情刷新增强。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/browse/GlobalSearchScreen.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/browse/SourceBrowseScreen.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/ui/browse/GlobalSearchResultNavigationTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/browse/SourceSharedStateWiringTest.kt`

**完成证据（`20fd5dd41`）：** 固定 main 的带当前 query 源导航、目标页首载 Search/fresh filters、默认 Popular 与不截断 `GlobalSearchCardRow` 语义已接入 Desktop；Compose/Voyager/真实 SQL 证据同时保留 `(sourceId, url)` 观察、最新 canonical ID、原始 `SManga` 后台刷新与防重复打开。无截断后为隔离 headless 双源批量调度，A2 双源同 URL 场景收敛为每源 1 项，另由 12 项真实 Compose 探针证明滚动到索引 11、惰性订阅及第 12 项可达；真实 SQL batch/generation 场景仍保持默认 12 项。最终 9 类 focused tests 为 107/107，独立审查与唯一测试增强复审均为 Approved。实际范围为 5 files/257 changed lines；新增的第 5 个文件修改是既有 A2 production 测试证据拆分，仍低于 8 files/400 lines 门槛。

**6A2B1R2 scope adjustment:** 原 70 changed lines 估算遗漏了移除旧全局 observer 与 direct callback 路径本身产生的约 28 行删除；最小 production 替换约 62 changed lines，真实 duplicate/cross-session/recovery RED 约 39 行。该行为闭环不可再独立拆分，调整为 2 files/110 changed lines，仍远低于项目 400 行拆分门槛；不得为满足旧估算压缩掉行为断言。

**6A2B1R3 review closure:** B1R2 为判断 accepted winner 把 `StateFlow.value` 写入放回 global monitor，可能同步恢复 collector 并与跨线程 `coordinatorFor/search` 重入死锁。新子任务仍仅 coordinator+测试两文件、≤90 changed lines：global lock 内只接受/盖章 immutable candidate，锁外用 ordinal-aware `MutableStateFlow.compareAndSet` 或等价 stamped publisher 原子选 winner，且只有 winner 调 session callback。必须以 Unconfined/阻塞 collector + 另一线程重入的确定性测试击穿锁内 setter mutation。

**6A2B2R review closure:** B2 首轮审查确认无 key `remember` 固化 source 列表，且 helper-only 测试无法在删除 `Content()` 的 Flow collect 或 `DisposableEffect` 时失败。修复仍限制 `GlobalSearchScreen.kt` 与 `SourceSharedStateWiringTest.kt`；真实 Compose A→B、Loading/result semantics、dispose close 及动态 source fixture 的最小可读实现为 289 changed lines，因此 B2 聚合范围调整为 2 files/300 changed lines，仍低于项目 400 行拆分门槛：后续搜索必须读取当前 installed sources；测试使用真实 Compose `GlobalSearchScreen.Content`、可注入 coordinator factory 与 production dependencies，证明状态 collect 和 dispose close。不得以源码扫描或仅调用 helper 代替。

**6A2CR review closure:** 6A2C 首轮测试手工创建 `Navigator(BrowseSourceListScreen())`，删除 production `BrowseTab.Content()` 的 nested Navigator 后仍会通过；唯一修复轮又以 Voyager child 反射观察层级，并被无关既有测试抢先失败，未形成有效 RED。重规划为仅测试文件、≤70 changed lines 的黑盒保护：只提供 `TabNavigator(BrowseTab)`，通过 `CurrentTab()` 调用真实 `BrowseTab.Content()`，点击空源 Extensions action 后观察 `ExtensionListScreen` 的真实 UI；mutation 精确运行该测试并删除 production nested Navigator，此时必须因缺失普通 `LocalNavigator` 而失败。禁止反射 navigator children、测试自建等价普通 Navigator 或 test-only production seam。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/browse/DesktopSourceQueryCoordinators.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/browse/BrowseTab.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/browse/SourceBrowseScreen.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/browse/GlobalSearchScreen.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/ui/browse/SourceSharedStateWiringTest.kt`

**Interfaces:**
- Consumes: Task 2 共享 query/page/error state 与 Task 5 登录 intent。
- Produces: Browse UI intents（`Retry`、`OpenLogin`、`OpenSettings`）和既有宽屏入口。

- [x] **Step 1: 写 Browse wiring RED**

  实例化 Browse/Source/Global Search 页面并驱动共享状态，覆盖 Loading、真正 Empty、翻页失败保留内容、403 登录、缺配置设置入口和 Retry。

- [x] **Step 2: 运行 RED**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.browse.SourceSharedStateWiringTest" --tests "*Source*ScreenModelTest"`
  Expected: FAIL，原因是 Browse UI 仍自行维护状态或直接查询 repository/network。

- [x] **Step 3: 最小接线 Browse 状态与 intents**

  ScreenModel 只组合共享 state 和发送 intent；Composable 保留现有导航入口与宽屏布局，不直接访问 repository/network。

- [x] **Step 4: 运行 GREEN**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.browse.*"`
  Expected: 全部 PASS；共享状态或 Browse production wiring 断线会失败。

- [x] **Step 5: 提交原 6A1/6A2 范围**

  Commit: `refactor(desktop): wire shared browse state`

### Task 6B: 从固定 main 原版提取扩展呈现契约

**OpenSpec mapping:** 2.3、3.4（固定 main 权威扩展状态、操作与共享呈现契约部分）

**Risk axis:** extension-presentation-authority
**Platform boundary:** shared+android
**Estimated scope:** 9 files, 760 lines
**Verification:** 权威只来自固定 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8` 的 `GetExtensionsByType`、`ExtensionsScreenModel`、presentation `ExtensionsScreen`、`ExtensionManager`、`GetExtensionSources`、`ToggleSource`、`ToggleIncognito`、`ExtensionDetailsScreenModel` 与 presentation `ExtensionDetailsScreen`。当前 `app/` 是被测 Android consumer，不得生成 expected value；shared catalog/install 类型是迁移输出。Desktop Tab/宽屏/package-name 搜索、文件/仓库详情，以及 fingerprint/SHA/snapshot/rollback/runtime restore 作为显式增强保留。
**Execution split:** C9 审计确认分类/search 与安装/详情动作是两个可独立验证的风险轴。原 8 files/400 lines 不足以同时建立 fixed-main fixture、共享 reducer 和 Android production wiring，因此拆为 6B1 分类核心与 6B2 动作生命周期；下层通过不能替代 fixed-main fixture。
**Split waiver:** 9 files/760 lines 是 6B1 与 6B2 两个顺序 Task 的聚合值，不会作为单一实现者范围调度；二者分别低于 8 files/400 lines，分类 reducer 与动作生命周期可独立验收，不能合并成一次变更。

#### Task 6B1: Extension presentation classification core

- Risk axis: `extension-presentation-classification`
- Platform boundary: `shared+android`
- Estimated scope: `6 files, 480 lines`（仅为 6B1a/6B1b 聚合，不作为单次调度范围）
- Verification: 固定 main fixture 覆盖逗号子查询、名称/source name/baseUrl/id、全局 NSFW、enabled language、多 source 拆分、obsolete/name 排序及 updates/installed/available/untrusted 分区；shared store 与当前 Android consumer 必须产生相同结果，package-name 搜索只能作为显式增量字段。

**6B1 scope correction:** 实际 fixed-main fixture、shared 泛型 contract/store 与可删除复制逻辑后仍会失败的 Android injectable wiring 证据合计约 475 changed lines；机械压缩到旧 380 行估算会降低测试可读性。只按 6B1a→6B1b 顺序调度，前者先提交 shared contract，后者再接入 Android consumer。
**Split rationale:** 6 files/480 lines 是两个顺序 Task 的聚合值，不会交给单一实现/审查范围；6B1a 与 6B1b 分别低于 8 files/400 lines。shared fixed-main reducer 与 Android production wiring 可独立验证，禁止以任一方通过替代另一方。

##### Task 6B1a: Fixed-main shared classification/search contract

- Risk axis: `extension-presentation-shared-classification`
- Platform boundary: `shared`
- Estimated scope: `3 files, 250 lines`
- Verification: common fixture 以行为 mutant 证明 installed 不按语言过滤、NSFW 显示开关、obsolete/name/update 分区、untrusted 不受 NSFW/语言过滤、available 去重/语言/多源拆分，以及逗号 OR 搜索与 package-name opt-in。

**Files:**
- Create: `domain/src/commonMain/kotlin/mihon/domain/extension/presentation/ExtensionPresentationContract.kt`
- Create: `domain/src/commonMain/kotlin/mihon/domain/extension/presentation/ExtensionPresentationStore.kt`
- Create: `domain/src/commonTest/kotlin/mihon/domain/extension/presentation/ExtensionPresentationStoreTest.kt`

##### Task 6B1b: Android classification/search production wiring

- Risk axis: `extension-presentation-android-classification-wiring`
- Platform boundary: `android`
- Estimated scope: `3 files, 230 lines`
- Verification: 真实 `GetExtensionsByType.subscribe()` 使用 Android `Extension`/Flow 产生 fixed-main 分类与 synthetic source；injectable classifier/search seam 证明删除 shared 调用后 `GetExtensionsByType` 与 `ExtensionsScreenModel` wiring 测试失败，默认搜索不含 package name，opt-in 才包含。

**Files:**
- Modify: `app/src/main/java/eu/kanade/domain/extension/interactor/GetExtensionsByType.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/browse/extension/ExtensionsScreenModel.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/ui/browse/extension/ExtensionPresentationWiringTest.kt`

**6B1 completion evidence:** `3dc50793a` 建立 fixed-main shared classification/search contract，corrective behavior RED 证明 installed 不得按 enabled language 过滤，相关 domain tests 46/46；`eb37d645d` 删除当前 Android consumer 的复制分类/matcher，以 injectable shared seam 接入真实 `GetExtensionsByType` 与 `ExtensionsScreenModel`。Android mutation RED 在绕过注入 classifier 时以 “classify was not called” 精确失败，最终 wiring 2/2 与 Release Kotlin 编译通过。6B1a 为 3 files/227 lines，6B1b 为 3 files/309 changed lines；两轮独立审查及各自唯一修复复审均为 Approved。

#### Task 6B2a: Shared extension action contract

- Risk axis: `extension-presentation-shared-actions`
- Platform boundary: `shared`
- Estimated scope: `3 files, 120 lines`
- Verification: 固定 main fixture 覆盖刷新、逐 package 安装状态归约、Installed 终态清理、继续收集边界和详情源 enabled-first 排序；排序键由平台 consumer 按固定 main 规则提供，shared 不私自改写大小写语义。

**Files:**
- Modify: `domain/src/commonMain/kotlin/mihon/domain/extension/presentation/ExtensionPresentationContract.kt`
- Modify: `domain/src/commonMain/kotlin/mihon/domain/extension/presentation/ExtensionPresentationStore.kt`
- Modify: `domain/src/commonTest/kotlin/mihon/domain/extension/presentation/ExtensionPresentationStoreTest.kt`

**6B2a completion evidence:** `9401b363a` 从 fixed main 提取共享刷新/逐 package 安装状态归约、唯一 Installed 终止边界与 enabled-first 排序。行为 RED 分别以未实现 lifecycle 与错误排序精确失败；最终 focused tests 4/4，通过同 package 覆盖、refresh 与 install state 隔离、全枚举终态和 disabled-first 反例保护实现。3 files/105 changed lines，唯一修复复审 Approved。

#### Task 6B2b: Android extension manager action adapters

- Risk axis: `extension-manager-android-actions`
- Platform boundary: `android`
- Estimated scope: `2 files, 220 lines`
- Verification: 当前 Android consumer 保持 fixed-main 的 install/update/cancel/trust/uninstall side-effect 顺序；trust 持久化后必须经 injected loader adapter reload，卸载命令后等待 receiver 再移除 installed state。当前 fork 的异步初始化、事务 ID、receiver 去重和 reload/rollback callback 作为安全超集保留。

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/extension/ExtensionManager.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/extension/ExtensionManagerTest.kt`

**6B2b completion evidence:** `ed577a8c2` 保持 fixed-main install/update/cancel/uninstall/trust 与 receiver 顺序，并让 trust reload 使用 injected loader adapter；事务 ID、active receiver 去重和 rollback 等安全超集保留。行为 RED 证明静态 loader 会绕过 seam；失败原子性测试以预先激活的 disappearance observer 证明 trust persistence/private cleanup 抛错时状态不提前移除。focused tests 4/4，2 files/220 changed lines，唯一修复复审 Approved。

#### Task 6B2c: Android extension UI action lifecycle wiring

- Risk axis: `extension-presentation-android-ui-actions`
- Platform boundary: `android`
- Estimated scope: `4 files, 210 lines`
- Verification: 当前 Android ScreenModel 通过 Task 6B2a shared reducer 驱动 refresh 与逐 package install state，按 fixed main 在 Installed 后停止并清理；详情页通过 injectable shared enabled-first seam 排序，同时覆盖 source 单个/全部启停、incognito 和仅在 installed flow 移除后退出。

**Files:**
- Modify: `app/src/main/java/eu/kanade/domain/extension/interactor/GetExtensionsByType.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/browse/extension/ExtensionsScreenModel.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/browse/extension/details/ExtensionDetailsScreenModel.kt`
- Modify: `app/src/test/java/eu/kanade/tachiyomi/ui/browse/extension/ExtensionPresentationWiringTest.kt`

**6B2c completion evidence:** `8dc608675` 让当前 Android ScreenModel 真实消费 Task 6B2a shared reducer/终态/排序 seam：refresh 与逐 package install state 回灌 shared reducer，Installed 后停止并清理；详情源 enabled-first、单个/全部启停、incognito 与 installed flow 移除后退出均按 fixed main。断线 mutation 证明移除 `takeWhile` 会继续收集 Error；最终 focused tests 4/4、`:app:spotlessKotlinCheck` 通过，4 files/200 changed lines，唯一修复复审 Approved。`d1af3551f` 修正 6B2b 测试格式阻塞。

### Task 6C: Desktop 扩展 adapter、ScreenModel 与 DI wiring

**OpenSpec mapping:** 2.3、3.4（Desktop 消费从固定 main 提取的共享状态、安装反馈与 DI 部分）

**Risk axis:** desktop-extension-presentation-wiring
**Platform boundary:** shared+desktop
**Estimated scope:** 13 files, 1410 lines
**Verification:** Task 6C1 独立闭合 metadata 连续性、Manager authoritative state、typed catalog/trust/install port 与取消 rollback；Task 6C2a 闭合 projection/update/obsolete/raw-step adapter；Task 6C2b1 闭合只消费该 port 和 Task 6B shared store 的 ScreenModel/jobs/trust lifecycle；Task 6C2b2 再闭合 DI singleton/reinit ownership。四个子 Task 分别验收，任一方通过不能替代另一方。
**Execution split:** 预审确认已安装 sidecar 缺少 fixed-main 分类所需的 name/language/isNsfw，且 Manager 没有 authoritative installed StateFlow；6C2a 已独立固定 projection/update/raw mapping。6C2b 预审又确认 ScreenModel reducer/jobs/trust 与 DI owner 是两组可独立失效风险，原 320 行估算无法保留高杀伤 mutation，因此按 6C1→6C2a→6C2b1→6C2b2 顺序施工。
**Split waiver:** 聚合文件数/行数不会作为一个 Task 调度；6C1、6C2a、6C2b1、6C2b2 分别不超过 8 files/400 lines。6C2b1 先产出稳定的 `closeAndJoin()` lifecycle contract，6C2b2 才注册并验证 DI ownership。

#### Task 6C1: Desktop metadata、Manager state 与 typed presentation port

- Risk axis: `desktop-extension-presentation-port`
- Platform boundary: `desktop`
- Estimated scope: `8 files, 400 lines`
- Verification: 新旧 sidecar 均可读取，安装后 name/language/isNsfw 可在 catalog 部分失败与重启后稳定恢复；Manager 暴露 authoritative installed StateFlow 与原生 install Flow；catalog 保留 typed per-repo failures；TrustRequired 使用稳定 requestId 恢复同一 pending request；取消 collector 仍触发既有 NonCancellable rollback 并保留旧 runtime。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/extension/ExtensionMeta.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/extension/DesktopExtensionInstallPort.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/extension/DesktopExtensionManager.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/extension/DesktopExtensionApi.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/ui/extension/DesktopExtensionPresentationPort.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/ui/extension/DesktopExtensionPresentationPortTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/extension/DesktopExtensionApiSharedCatalogTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/extension/DesktopExtensionInstallTransactionTest.kt`

- [x] **Step 1: 写 typed port / metadata / transaction RED**

  覆盖旧 sidecar 默认值与新字段 round-trip、两仓库一成功一失败、raw install state、cancel rollback、TrustRequired→ConfirmTrust 同一 request identity，以及 installed flow 仅随成功 commit/rollback/uninstall 更新。

- [x] **Step 2: 运行 RED**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.extension.DesktopExtensionPresentationPortTest" --tests "mihon.desktop.extension.DesktopExtensionApiSharedCatalogTest" --tests "mihon.desktop.extension.DesktopExtensionInstallTransactionTest"`
  Expected: FAIL，原因是 Desktop 仍只有 terminal API、sidecar 缺字段且 Manager 无 authoritative flow。

- [x] **Step 3: 实现 metadata、Manager Flow 与 typed port**

  sidecar 向后兼容地持久化 artifact presentation metadata；Manager 直接暴露 coordinator Flow 和 installed StateFlow；port 只映射 JAR/APK/文件 side effect，保留 typed catalog/trust/install state。legacy terminal wrapper 仅为 6D 前现有 UI 编译暂留，新链路禁止消费。

- [x] **Step 4: 运行 GREEN 与取消/rollback mutation**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.extension.DesktopExtensionPresentationPortTest" --tests "mihon.desktop.extension.DesktopExtensionApiSharedCatalogTest" --tests "mihon.desktop.extension.DesktopExtensionInstallTransactionTest"`
  Expected: 全部 PASS；改回 `.last()`、重建 trust request、丢 catalog failure 或在 cancel 时不 rollback，至少一个行为测试失败。

**6C1 completion evidence:** `5c018153d` 为旧/new sidecar 补齐向后兼容的 name/language/isNsfw，并由真实 install port 写入；Manager 暴露 raw install Flow 与 authoritative installed StateFlow，在成功、取消 rollback、卸载和删除失败恢复后原子发布；typed port 保留 partial catalog、同一 pending trust request identity 与 discard，legacy wrapper 立即 discard 避免过渡期泄漏。focused tests 53/53，8 files/364 changed lines，独立首审 Approved。`app-desktop` 无 Spotless/ktlint task；root `spotlessCheck` 仅被范围外已知 `GlobalSearchSourcePolicyTest.kt:53` 阻塞，本 Task `diff --check` clean。

#### Task 6C2: Desktop ScreenModel、shared store 与 DI lifecycle wiring

- Risk axis: `desktop-extension-presentation-consumer`
- Platform boundary: `shared+desktop`
- Estimated scope: `7 files, 1050 lines`
- Verification: 6C2a 的 Desktop adapter 独立消费 shared classifier/update policy 并保留 raw state；6C2b1 的 ScreenModel 消费该稳定 adapter 与 shared reducer并拥有 jobs/trust；6C2b2 注册同一长生命周期实例并证明 test DI reinit/close 后旧 jobs 停止。
- Split waiver: 6C2a、6C2b1、6C2b2 分别独立验收且均不超过 400 lines；projection/update/raw mapping、ScreenModel jobs/trust 与 DI ownership 是三条可独立断线测试的风险轴，不能由同一实现者批次压缩。

##### Task 6C2a: Desktop projection、update/obsolete 与 raw-step adapter

- Risk axis: `desktop-extension-projection-rules`
- Platform boundary: `shared+desktop`
- Estimated scope: `3 files, 310 lines`
- Verification: Desktop item adapter 使用 Task 6B shared classifier/search 与 `SharedExtensionUpdatePolicy`；完整 raw install state 只在 port 单点映射并保留原始 state/AppError；partial repo failure 不误标 obsolete，custom JAR 保守，bundled package 不重新显示为可安装/更新，多 source projection action 保留原始 package。

**6C2a scope adjustment:** 断线 mutation 强制重新编译 production 后暴露 `DesktopExtensionManager` 的 StateFlow 属性 getter 与旧 List 快照方法生成同名 JVM getter，MockK 无法稳定插桩，原 GREEN 被增量缓存掩盖。为避免修改 Manager 产品 API，port 允许显式注入同一 authoritative StateFlow，两个端口测试直接传入该 flow；增加 1 个既有测试文件和最多 10 changed lines，不改变 production 默认 wiring。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/extension/DesktopExtensionPresentationPort.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/ui/extension/DesktopExtensionPresentationProjectionTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/extension/DesktopExtensionPresentationPortTest.kt`

- [x] **Step 1: 写 projection/update/raw mapping RED**

  覆盖 package-name opt-in、逗号 OR、source name/baseUrl/id、shared classifier 返回值、update/obsolete、partial repo failure、custom/bundled package、多 source 原始 action package，以及全部 `ExtensionInstallState`→presentation step 映射并保留 raw state/error identity。

- [x] **Step 2: 运行 RED**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.extension.DesktopExtensionPresentationProjectionTest"`
  Expected: FAIL，原因是 typed port 尚未建立 shared projection/update/raw adapter。

- [x] **Step 3: 实现单点 projection adapter**

  port 负责 Desktop model→shared item 的薄映射，update 只调用 shared policy；obsolete 仅在能证明所属 repo refresh 成功时设置；raw state 映射集中一个函数，ScreenModel 不得出现第二个 `when (ExtensionInstallState)`。

- [x] **Step 4: 运行 GREEN 与断线 mutation**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.extension.DesktopExtensionPresentationProjectionTest"`
  Expected: 全部 PASS；绕过 shared store/policy、丢 raw state、错误 obsolete 或用 projected package 执行动作时至少一个测试失败。

**6C2a completion evidence:** Desktop port 已消费 shared classifier/search/update policy，并集中完成 projection、update/obsolete 与 raw install state 映射；partial failure、custom/bundled、多 source action package 与 raw identity 均有行为断言。重新编译暴露的 MockK 同名 getter 夹具缺陷通过显式 authoritative StateFlow seam 修复，production 默认仍来自 Manager。focused Port/Projection tests 4/4 PASS；`includePackageName=true→false` mutation 精确杀死 1 项测试；3 files/308 changed lines，首次审查及唯一修复复审均 Approved。

##### Task 6C2b1: Desktop ScreenModel core、package jobs、trust 与自有 scope

- Risk axis: `desktop-extension-screenmodel-lifecycle`
- Platform boundary: `shared+desktop`
- Estimated scope: `3 files, 590 lines`
- Verification: authoritative installed flow 触发重新 projection；partial failure identity、shared reducer 连续回灌、fixed-main intents、post-Installed cutoff、逐包 job、Error/raw identity、pending trust 与 child-scope close-and-join 均由真实 port/flow 行为覆盖。
- Split waiver: 本聚合项不作为一个 Task 调度；6C2b1a 与 6C2b1b 分别低于 400 lines，先稳定 state/intents contract，再追加 jobs/trust/close lifecycle。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/extension/DesktopExtensionPresentationPort.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/ui/extension/ExtensionsScreenModel.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/ui/extension/ExtensionSharedStateWiringTest.kt`

**Interfaces:**
- Consumes: Task 6B `ExtensionPresentationStore`/actions 与 Task 6C2a typed projection/raw adapter。
- Produces: 稳定的 `ExtensionsScreenModel.closeAndJoin()`；refresh/install/update-all/cancel/uninstall/trust intents 与 fixed main 一致。
- Boundary: 不 new Manager/API，不调用 legacy terminal API，不复制分类、搜索、版本、信任、回滚或错误映射；只取消/等待自身 child jobs，不取消外部 scope。

###### Task 6C2b1a: Desktop ScreenModel state、shared reducer 与 fixed-main intents

- Risk axis: `desktop-extension-screenmodel-core`
- Platform boundary: `shared+desktop`
- Estimated scope: `3 files, 260 lines`
- Verification: authoritative installed flow 使用最近 typed catalog 重新 projection/classify；refresh partial failure identity 与 finally、shared reducer 连续返回回灌、update-all candidate 选择及 typed uninstall intent 均由行为测试覆盖。

**6C2b1a scope adjustment:** 首轮审查发现全量 mock port 不能证明 production shared reducer/classifier 与 manager uninstall wiring，且手工 update item 不是 C2a 可产出的真实状态。唯一修复轮改为只 mock API/Manager 平台边界并使用真实 port、authoritative installed flow 与 typed catalog；增加最多 20 changed lines，同时补首次 refresh 前 options 公开反馈。

- [x] **Step 1: 写 state/reducer/intents RED**

  覆盖 manager flow 更新后重投影、partial failure exact identity、RefreshFinished 输入继承 RefreshStarted 返回值、refresh 异常也结束、update-all 按 `operationPackageName` 从最近 catalog 取原始 artifact、uninstall 只走 typed port。

- [x] **Step 2: 运行 RED**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.extension.ExtensionSharedStateWiringTest"`
  Expected: FAIL，原因是尚无 production ScreenModel state/reducer 与 fixed-main intents。

- [x] **Step 3: 实现 state/reducer 与 typed intent surface**

  ScreenModel 持有最近 typed catalog，只消费 C2a projection/classifier 和 shared reducer；port 只补卸载等平台 side-effect adapter，不增加业务规则。

- [x] **Step 4: 运行 GREEN 与断线 mutation**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.extension.ExtensionSharedStateWiringTest"`
  Expected: 全部 PASS；忽略 reducer 返回值、停止订阅 installed flow、丢 partial failure identity、从 projected available 选 update 或绕开 typed uninstall 时至少一项失败。

###### Task 6C2b1aR: Desktop refresh failure proof closure

- Risk axis: `desktop-extension-refresh-failure-proof`
- Platform boundary: `verification`
- Estimated scope: `1 file, 10 lines`
- Verification: 真实 port 的 `api.refreshCatalog()` 抛出同一异常时，ScreenModel 必须保留 exact error identity，并由 shared reducer 的 finally 路径清除 refreshing；将 `RefreshFinished` 移出 finally 时测试必须失败。

**Files:**
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/extension/ExtensionSharedStateWiringTest.kt`

- [x] **Step 1: 补 refresh failure RED/GREEN 与 finally mutation**

  在现有真实 port 测试末尾令 refresh 抛出同一 `IllegalStateException`，断言 `refreshError === error` 且 `actions.isRefreshing == false`；focused 单类通过后临时把 `RefreshFinished` 移出 finally，确认该断言失败，再恢复并重跑 GREEN。

**6C2b1a/1aR completion evidence:** production ScreenModel 订阅 authoritative installed flow，以最近 typed catalog 调用 C2a projection/classifier，并连续回灌 shared reducer；真实 port 测试保留 partial failure/error identity、update policy 的原始 candidate identity 与 typed uninstall manager identity，首次 refresh 前 options 也立即反馈。focused tests 2/2 PASS；忽略 reducer 返回值与把 `RefreshFinished` 移出 finally 的 mutation 均精确失败；聚合 3 files/228 changed lines。6C2b1a 首审触发唯一修复复审后仍缺异常分支，因此按门禁停止并新建 1aR；1aR 独立审查 Approved、无 P0/P1/P2。

###### Task 6C2b1b: Desktop package jobs、trust 与 close lifecycle

- Risk axis: `desktop-extension-package-trust-lifecycle`
- Platform boundary: `shared+desktop`
- Estimated scope: `2 files, 320 lines`
- Verification: install/update flow 在 Installed 后停止，Error/raw identity 保留；同包 job 替换、取消、pending trust confirm/dismiss/replace/close 与 child-scope close-and-join 均由真实 flow/finally 证明。

**6C2b1b scope adjustment:** RED 的三个并发场景与必要 helper 实际为 145 changed lines；child scope、owner-guarded job map、shared terminal reducer 与 trust 双重 drain 的最小可读 production 预计 100–110 行。为保留 NonCancellable barrier、late TrustRequired race 与 parent sibling 证据，调整到 2 files/260 lines，不压缩长行或删除 mutation。

**6C2b1b implementation correction:** 最小 production 落盘后为 155 additions/1 deletion，额外行来自 raw/error terminal state、package owner identity guard 与 close 后 late trust 的二次 drain；聚合实际 298 changed lines。该关闭协议不能独立调度，最终校正为 2 files/320 lines，仍低于 400 行门槛。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/extension/ExtensionsScreenModel.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/extension/ExtensionSharedStateWiringTest.kt`

- [x] **Step 1: 写 jobs/trust/close RED**

  覆盖 onEach→takeWhile→cleanup、Error/raw identity、同包 cancel+join 且只移除自身、pending exact request confirm/dismiss/replace/close，以及 model close 后 parent sibling 仍 active。

- [x] **Step 2: 运行 RED**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.extension.ExtensionSharedStateWiringTest"`
  Expected: FAIL，原因是 core 尚无 package/trust/close lifecycle。

- [x] **Step 3: 实现 package/trust/close lifecycle**

  每包至多一个自有 Job；终态与 trust 清理保持 exact identity；`closeAndJoin` 禁止新操作、等待自身 jobs、drain pending，且不取消外部 scope。

- [x] **Step 4: 运行 GREEN 与断线 mutation**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.extension.ExtensionSharedStateWiringTest"`
  Expected: 全部 PASS；清掉 Error、同包不取消、漏 discard、取消 parent scope 或 Installed 后继续收集时至少一项失败。

**6C2b1b completion evidence:** ScreenModel 的自有 child scope 管理 per-package owner jobs；真实 typed flow 依 fixed-main 顺序 dispatch→shared `shouldContinue`→terminal cleanup，Installed 后截止、Error/raw identity 保留，retry/Cancelled/Installed 清理。同包替换先 cancel-and-join 且旧 owner 不删除新 job，不同包隔离；单可见 trust 槽在 replace/confirm/dismiss/late-close 中 exact-once discard，`closeAndJoin` 双重 drain、等待 NonCancellable finally 且不取消 parent sibling。focused tests 5/5 PASS；保留 raw state mutation 精确失败；2 files/319 changed lines，唯一修复复审 Approved、无 P0/P1/P2。

##### Task 6C2b2: Desktop extension DI singleton 与 reinit ownership

- Risk axis: `desktop-extension-di-lifecycle`
- Platform boundary: `desktop`
- Estimated scope: `2 files, 150 lines`
- Verification: production 默认 port flow、port/ScreenModel singleton、test context 构造时捕获旧实例、reinit 先 close-and-join old model 再关闭 manager/network；old/new context 相互隔离。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt`

- [x] **Step 1: 写 DI singleton/reinit RED**

  覆盖默认 `manager.installedExtensions` identity、重复 Injekt get 同实例、old model closed/new model distinct active、旧 context close 不影响新实例，以及 context 不在 close 阶段重新 Injekt get。

- [x] **Step 2: 运行 RED**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.di.DesktopDiWiringTest"`
  Expected: FAIL，原因是 port/ScreenModel 尚未注册且 test context 未捕获其 owner。

- [x] **Step 3: 注册并实现 reinit ownership**

  DI 使用默认 production port 构造并注册单一 ScreenModel；`DesktopTestDIContext` 构造时捕获该实例，`closeAndJoin` 在 manager/network 前关闭并等待它。

- [x] **Step 4: 运行 GREEN 与断线 mutation**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.di.DesktopDiWiringTest"`
  Expected: 全部 PASS；重复 new、显式假 flow、close 时重新 Injekt get、遗漏 model close 或关闭新实例时至少一项失败。

- [x] **Step 5: 提交 Task 6C**

  Commit: `refactor(desktop): adapt shared extension presentation`

**6C2b2/Task 6C completion evidence:** `b2ec94cf6` 以 production 默认构造注册同一 Desktop presentation port/ScreenModel singleton；port flow 与 Manager authoritative flow 引用一致。`DesktopTestDIContext` 在构造时捕获 owner，reinit 在 manager/network/DB 前 close-and-join 旧 model，old/new context 与幂等 close 相互隔离。focused DI tests 8/8 PASS；省略 model shutdown 的 mutation 精确失败；2 files/62 changed lines，独立审查 Approved、无 P0/P1/P2。至此 6C1、6C2a、6C2b1a/1aR、6C2b1b、6C2b2 全部闭合，Desktop extension production ScreenModel 已消费 Task 6B shared state/actions，Desktop 独有 JAR/APK→JAR、trust request 与 runtime side effect 保留在 adapter/lifecycle 边界。

### Task 6D: Desktop Extension UI、详情/设置与 i18n wiring

**OpenSpec mapping:** 3.4、3.5（扩展列表/详情/设置入口、反馈与本地化部分）

**Risk axis:** extension-ui-wiring
**Platform boundary:** desktop
**Estimated scope:** 9 files, 1410 lines
**Verification:** 三个 Desktop Screen 只收集 Task 6C ScreenModel/shared state 并发送 intents；加载、空、部分失败、安装阶段、TrustRequired、回滚保留旧版本、详情缺失和源设置不可用均有可执行反馈，且 base/zh-rCN 资源完整。
**Execution split:** 只读评估确认列表 state/classification、列表 action lifecycle、详情 Desktop 独有入口与 JVM preference availability 是四条可独立失效风险；合并预计 650–850 changed lines，且现有源码扫描 CopyContract 不能替代真实行为测试。因此按 6D1→6D2→6D3→6D4 顺序调度。
**Split waiver:** 本聚合项不作为一个 Task 调度；6D1a、6D1b、6D2、6D3、6D4 各自不超过 8 files/400 lines，前一段的 production/test seam 稳定后才允许后一段施工。

#### Task 6D1: Extension list state、classification 与恢复 UI

- Risk axis: `extension-list-state-ui`
- Platform boundary: `desktop`
- Estimated scope: `6 files, 450 lines`
- Verification: Screen 实例化；真实 Compose 收集 Task 6C state，区分 loading/empty/partial failure 并以同一 failure identity Retry；分类/search/options 来自 shared presentation，base/zh-rCN key 均可加载。
- Split waiver: 落盘前估算遗漏了删除旧 catalog/filter/load block 与替换 tab/card model 的 churn；测试+ListScreen+i18n 实际约 422 行。D1 不整体调度，顺序拆为 6D1a projection/copy 与 6D1b Compose rendering。

##### Task 6D1a: Extension list projection adapter 与 copy contract

- Risk axis: `extension-list-projection-copy`
- Platform boundary: `desktop`
- Estimated scope: `4 files, 170 lines`
- Verification: `DesktopExtensionsState` 只消费 shared presentation/search/options 生成 Installed/Available tab projection；partial failures 保留 identity；base/zh-rCN 同时具备 6D1/6D2 所需 copy，行为契约不扫描 production 源码。

**Files:**
- Create: `app-desktop/src/main/kotlin/mihon/desktop/ui/extension/ExtensionListUiProjection.kt`
- Modify: `i18n/src/commonMain/moko-resources/base/strings.xml`
- Modify: `i18n/src/commonMain/moko-resources/zh-rCN/strings.xml`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/extension/ExtensionListCopyContractTest.kt`

- [x] **Step 1: 写 projection/copy RED**

  覆盖 shared updates+installed→Installed tab、available→Available tab、package/source search、options/filter 与 partial failure identity；资源 key 通过真实资源 API/生成 accessor 加载，不读取 Kotlin 源文本。

- [x] **Step 2: 实现独立 projection adapter 与双语 copy**
- [x] **Step 3: 运行 GREEN 与 classifier/i18n mutation**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.extension.ExtensionListCopyContractTest"`

  Evidence: commit `0e4a90394`；最终 `2 tests / 0 failures / 0 errors / 0 skipped`。断开 `updates + installed` 合并时测试在 line 36 失败；关闭 `includePackageName` 时测试在 line 40 失败，恢复后全绿。独立 thorough review 经一次测试数据修复后 APPROVED；实际范围 `4 files, 151 lines`。

##### Task 6D1b: Extension list Compose state、partial failure 与 Retry

- Risk axis: `extension-list-compose-state`
- Platform boundary: `desktop`
- Estimated scope: `2 files, 400 lines`
- Verification: production `ExtensionListScreen.Content` 经 Voyager/Injekt 收集 singleton ScreenModel，首次 refresh；真实 Compose 区分 loading/empty、data+exact partial failure 同屏，Retry 再触发 refresh；updates 与 available 同屏且仅有 update 时不丢失 Update/Update All；语言与 NSFW 同次应用不互相覆盖，source language 与 Clear 使用完整 inventory；保留双 tab、宽屏与仓库入口，动作生命周期留给 6D2。
- Estimate correction: 初稿 `296/300` 的审查证明原估算遗漏了真实 Screen→Injekt route、update-only 可达性和多 source filter 断线场景；唯一修复轮仍限制在同一 2 files 且不超过项目 400-line Task 上限，不新增调度单元。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/extension/ExtensionListScreen.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/ui/extension/ExtensionPresentationUiTest.kt`

- [x] **Step 1: 写 list state/partial failure/Retry RED**

  使用真实 Compose/wiring fixture 覆盖 Screen 实例化、loading/empty、成功列表与 exact partial failure 同屏、Retry 发送 refresh、shared classification/search/options；替换源码字符串扫描断言。

- [x] **Step 2: 运行 RED**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.extension.ExtensionPresentationUiTest" --tests "mihon.desktop.ui.extension.ExtensionListCopyContractTest"`
  Expected: FAIL，原因是列表仍维护本地 catalog/filter/error 状态且恢复文案未资源化。

- [x] **Step 3: 接入 list state/classification**

  Composable 只收集 production ScreenModel state 并渲染 shared presentation；保留 Desktop 宽屏/仓库入口，不迁移 install/trust action（留给 6D2）。

- [x] **Step 4: 运行 GREEN 与断线 mutation**

  Expected: 断开 ScreenModel collect、把 partial failure 折叠成 empty、恢复本地 classifier 或删除任一 locale key 时至少一项失败。

  Evidence: commit `0b00f1628`（与 6D1bR 组合提交）；真实 Navigator/Injekt Screen 测试覆盖 loading、empty、data+exact partial failure、Retry、update-only、原子 language+NSFW Apply 与完整 inventory Clear。Retry 断线时 `exactly 3 refresh` 仅收到 2 次并失败；combined focused 为 Ui `1/0/0/0`、Copy `2/0/0/0`、DI singleton `1/0/0/0`。

**Review status:** 初审四项经唯一修复轮已关闭，但复审发现 Available UI 将 shared 逐-source `DesktopExtensionItem` 按 operation package 去重并降回 raw extension，仍会丢失 fixed-main 的 source 名称、语言和分组。按门禁停止 6D1b，不追加第二修复轮；由 6D1bR 独立闭环后再统一 checkoff。

##### Task 6D1bR: Available source projection closure

- Risk axis: `extension-list-source-projection`
- Platform boundary: `desktop`
- Estimated scope: `2 files, 100 lines` incremental on the reviewed 6D1b worktree
- Verification: AvailableTab/Card 端到端消费 `DesktopExtensionItem.presentation`，不按 operation package 合并逐-source projection；side effect 仅从 item 取 typed raw candidate。真实 Screen 测试以一个未安装、多 source candidate 断言两个 source 名称/语言同时可见；恢复 raw mapping 或 package distinct 时必须失败。
- Carry-forward waiver: 6D1b 的 `373/400` 两文件修复仍未提交，6D1bR 只计复审新发现的增量 closure；最终提交前同时报告组合 diff，若组合 diff 超过 400 lines 则先机械收缩测试，不扩大文件范围。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/extension/ExtensionListScreen.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/extension/ExtensionPresentationUiTest.kt`

- [x] **Step 1: 写 multi-source available RED 与 raw-deprojection mutation**
- [x] **Step 2: Available UI 保留 shared projected item**
- [x] **Step 3: 运行 focused GREEN、CopyContract 与 singleton DI 回归**

  Evidence: 旧 raw/distinct UI 精确失败 `missing production extension UI: Beta available source`；修复后 Alpha/Beta 逐-source projection 同屏，Filtered source 仍由 shared classifier 排除。6D1bR thorough review APPROVED；组合范围 `2 files, 399 lines`，closure 增量 `26/100`。

#### Task 6D2: Extension list install、trust 与 error action UI

- Risk axis: `extension-list-action-ui`
- Platform boundary: `desktop`
- Estimated scope: `6 files, 400 lines`
- Verification: 真实 Compose 点击发送 install/update/update-all/cancel/confirm/dismiss/retry intents；单项 update 从 latest typed catalog 按 operation package 取得 exact raw candidate；逐包 shared step、TrustRequired、Error 与旧 installed 版本并存反馈可观察，不调用 legacy terminal API。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/extension/ExtensionsScreenModel.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/extension/DesktopExtensionPresentationPort.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/extension/ExtensionListScreen.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/extension/ExtensionSharedStateWiringTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/extension/ExtensionPresentationUiTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/extension/ExtensionListCopyContractTest.kt`

6D2 所需用户文案 key 在 6D1 同步预置到 base/zh-rCN，避免动作 Task 再扩大到 7 files；若实现发现缺失 key，必须如实调整到 7 files/≤400，不得硬编码。

初审 correction：单项 update 与 C2a projection 必须共用 canonical candidate 选择，因此唯一修复轮纳入 `DesktopExtensionPresentationPort.kt`；同时删除无法从 Job 正确推断 TrustRequired/Cancelled 的批量成功汇总，逐阶段与逐包终态按 fixed-main/shared state 呈现。范围修正为 6 files/400 lines，不新增调度单元。

- [x] **Step 1: 写 list action lifecycle RED**
- [x] **Step 2: 删除本地 jobs/reducer/legacy API 并接入 ScreenModel intents**
- [x] **Step 3: 运行 GREEN 与断线 mutation**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.extension.ExtensionPresentationUiTest" --tests "mihon.desktop.ui.extension.ExtensionListCopyContractTest"`
  Expected: 绕过 ScreenModel、恢复本地 install map、丢 trust/error/旧版本反馈或删除 locale key 时至少一项失败。

  Evidence: commit `241c61188`（与 6D2R 组合提交）；Install/Update/Retry/Cancel/Update All/Trust confirm+dismiss 全部经 ScreenModel typed intents。Pending/Downloading/Installing 分阶段 MR copy；失败更新保留同一 installed identity/version；MalformedData 与 Network 文案类型边界有行为契约。断开 `onUpdate` 时 exact canonical `beginInstall` 未调用，phase mutation 把 Downloading 映射为 Installing 时 Copy 测试失败。

**Review status:** 初审五项在唯一修复轮关闭后，复审发现 `project()` 仍把 raw duplicate package candidates 暴露给 UI，而动作已选择 canonical last candidate；这会产生重复卡片、数量与 key。按门禁停止 6D2，由 6D2R 对齐 fixed-main `associateBy(pkgName).values` 后统一 checkoff。

##### Task 6D2R: Canonical catalog projection closure

- Risk axis: `extension-canonical-ui-projection`
- Platform boundary: `desktop`
- Estimated scope: `3 files, 60 lines` incremental on the reviewed 6D2 worktree
- Verification: `project()`、single update 与 updateAll 共用同一 last-wins canonical candidate map；同包双仓库输入在 projection 与真实 UI 中只出现一次，identity 为 last candidate，badge/key/action 数量一致。恢复 raw catalog projection 时契约测试必须失败。
- Carry-forward waiver: 6D2 的 `317/400` 六文件修复仍未提交；6D2R 只计复审新发现的 projection closure，最终组合 diff 继续保持不超过 400 lines。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/extension/DesktopExtensionPresentationPort.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/extension/ExtensionSharedStateWiringTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/extension/ExtensionPresentationUiTest.kt`

- [x] **Step 1: 写 duplicate-package projection/UI RED**
- [x] **Step 2: project available 只消费 canonical values**
- [x] **Step 3: 运行 fresh focused GREEN 与 raw-projection mutation**

  Evidence: raw duplicate-package projection 在 Shared/UI 均 `expected 1 / was 2`；修复后 projection/update/retry/updateAll 共用 last-wins canonical map，UI 只保留 exact last candidate 且 en/fr 两个 source key/action 唯一。6D2R thorough review APPROVED；fresh XML `Shared 5 / UI 1 / Copy 2` 全绿，组合范围 `6 files, 337 lines`，closure 增量 `20/60`。

#### Task 6D3: Extension details authoritative lifecycle 与 Desktop adapters

- Risk axis: `extension-details-ui`
- Platform boundary: `desktop`
- Estimated scope: `6 files, 560 lines`
- Verification: 6D3a 关闭 authoritative loading/missing/uninstall；6D3b 独立关闭 Desktop origin/metadata/platform action/navigation wiring。
- Split waiver: 初审证明 lifecycle 与 Desktop adapter 入口可独立失效，且真实入口 side-effect 测试会使原单 Task 超过 300 lines；聚合项不直接调度，按 6D3a→6D3b 顺序执行，各子项不超过 5 files/300 lines。

##### Task 6D3a: Authoritative details lifecycle 与 typed uninstall

- Risk axis: `extension-details-ui`
- Platform boundary: `desktop`
- Estimated scope: `5 files, 300 lines`
- Verification: projection 初始为空时主动 refresh 并显示 loading、不误 pop；refresh 后按 authoritative installed flow 显示条目或在 missing 时 pop；typed uninstall 失败留页反馈，成功后等待 flow removal 再 pop；既有 incognito fixture 迁移后不回归。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/extension/ExtensionDetailsScreen.kt`
- Modify: `i18n/src/commonMain/moko-resources/base/strings.xml`
- Modify: `i18n/src/commonMain/moko-resources/zh-rCN/strings.xml`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/ui/extension/ExtensionDetailsPreferencesWiringTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/extension/ExtensionIncognitoPreferenceWiringTest.kt`

Scope correction: Details 改为从 Injekt singleton authoritative state 取值后，既有 incognito 真实 Compose fixture 会因仍按 manager snapshot 架构而抛 `InjektionException`；该测试必须同步注册同一 ScreenModel 并恢复 Injekt scope。预计组合仍低于 300 lines，不新增生产文件。

- [x] **Step 1: 写 details authoritative/uninstall RED**
- [x] **Step 2: 接入 ScreenModel authoritative lifecycle**
- [x] **Step 3: 运行 GREEN 与 refresh/pop 断线 mutation**

  Evidence: commit `e7ad5fc7d`；Details 初始 projection 为空时自行 refresh 并显示 spinner + `MR.loading`，阻塞期间不 pop；refresh 后 empty installed 才 pop，authoritative item 出现可重入。typed uninstall false 留页反馈，true 等待 installed flow removal。断开 refresh effect 或 missing pop 均使真实 Navigator 测试超时；fresh `Details 1 / Incognito 2 / DI singleton 1` 全绿，6D3a review APPROVED，范围 `5 files, 288 lines`。

##### Task 6D3b: Desktop details adapter 与独有入口 wiring

- Risk axis: `extension-details-platform-adapters`
- Platform boundary: `desktop`
- Estimated scope: `2 files, 300 lines`
- Verification: COMPILED_JAR/CONVERTED_APK origin、exact path/size/SHA/repo/fingerprint、Explorer/Finder directory、repo/source URL、source enable、SourcePreferences/Browse navigation、incognito 与 cookie clear 均通过 production Screen 的真实 adapter/side-effect 行为验证；平台失败有可见反馈。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/extension/ExtensionDetailsScreen.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/extension/ExtensionDetailsPreferencesWiringTest.kt`

- [x] **Step 1: 写 Desktop adapter/entry RED**
- [x] **Step 2: 增加可测试的薄 platform adapter seam 并保留默认实现**
- [x] **Step 3: 运行 GREEN 与 directory/URL/navigation/cookie 断线 mutation**

  Evidence: commit `2b3ae65a2`；默认 seam 仍委托 `DesktopDirectoryOpener` 与系统浏览器，真实 Screen/provider 测试覆盖 COMPILED_JAR/CONVERTED_APK、exact metadata、目录成功/失败、repo/source URL、source toggle、SourcePreferences/Browse 导航参数、incognito 与 cookie exact domains/反馈。断开 directory adapter 后 exact parent 捕获失败；fresh `Details 1 / Incognito 2` 全绿，6D3b review APPROVED，增量 `2 files, 134 lines`。

#### Task 6D4: Source preferences typed availability UI

- Risk axis: `source-preferences-availability-ui`
- Platform boundary: `desktop`
- Estimated scope: `4 files, 280 lines`
- Verification: production typed availability 区分 missing、non-configurable、setup-failure、empty 与 content；真实 Compose 显示可区分反馈和 exact setup error identity，JVM 控件 adapter 保留。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/extension/SourcePreferencesScreen.kt`
- Modify: `i18n/src/commonMain/moko-resources/base/strings.xml`
- Modify: `i18n/src/commonMain/moko-resources/zh-rCN/strings.xml`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/ui/extension/ExtensionDetailsPreferencesWiringTest.kt`

- [x] **Step 1: 写五态 availability RED**
- [x] **Step 2: 实现 typed availability 与 JVM adapter UI**
- [x] **Step 3: 运行 GREEN、i18n mutation 并提交 Task 6D**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.extension.ExtensionPresentationUiTest" --tests "mihon.desktop.ui.extension.ExtensionDetailsPreferencesWiringTest" --tests "mihon.desktop.ui.extension.ExtensionListCopyContractTest"`
  Commit: `refactor(desktop): wire shared extension UI`

  Evidence: commit `b9fcf0d1e`；production typed state 明确 Missing/NonConfigurable/SetupFailure(exact Throwable)/Empty/Content，Context compatibility 的 Exception/LinkageError 失败继续 setup，真实 setup 的 Exception/LinkageError 保留同一 identity，未捕获 VM 级 Throwable。五态真实 Screen、本地化 base/zh 与 JVM Switch 持久化有行为测试；SetupFailure→Empty 与 LinkageError 逃逸 mutation 均失败。fresh `Presentation 1 / Details 2 / Copy 2` 全绿，6D4 review APPROVED，范围 `4 files, 198 lines`。

### Task 6E: Test Mode、导航与自动化观察

**OpenSpec mapping:** 2.3、3.4、3.5（导航类型、Test Mode 与自动化观察部分）

**Risk axis:** automation-observability
**Platform boundary:** desktop
**Estimated scope:** 38 files, 2400 lines
**Verification:** 串行运行导航、i18n、扩展 ScreenModel、真实 Test HTTP server、production DI/Compose wiring 与 test-desktop 客户端契约测试；任一 production state/intent/wiring 断线时对应测试必须失败。
**Split waiver:** 这是 6E1–6E4 的聚合上界，横跨 Voyager 导航、Moko 资源、Extension ScreenModel、Ktor server、DI、Compose 生命周期和独立 test-desktop 客户端，不能作为单个风险轴安全调度；下列每个实际子 Task 均不超过 8 files/400 lines，并按依赖顺序提交。

**Execution split:** 原估算把导航、i18n、扩展 ScreenModel 自动化和 Source 登录 UI 局部状态混为 4 文件；但当前 HTTP 扩展动作是空操作，客户端 DTO 没有对应状态，登录取消又只存在于真实 `SourceBrowseScreen` composition。只改 `TestState`/server 会复制第二套业务状态，无法证明 production wiring。另经 fixed main 审计确认，Desktop 把扩展搜索错误留在 Compose-local，而原版 `ExtensionsScreenModel.search(query)` 持有搜索状态；遗留 `extension_enable/disable` 也把原版 Source toggle 错标成 Extension action。按下列风险轴顺序实施，每次调度仍不超过 8 files/400 lines。

#### Task 6E1: Source/Extension 导航契约

- Risk axis: `source-extension-navigation`
- Platform boundary: `desktop`
- Estimated scope: `5 files, 240 lines`
- Verification: 直接实例化实际 Screen，并通过 production destination/push callback 验证普通 `Navigator` 只接收 `Screen`、不接收 `Tab`；目标参数必须与实际点击路径一致。
- Files: `ExtensionListScreen.kt`、`ExtensionDetailsScreen.kt`、`MoreRootScreen.kt`、`SourceExtensionNavigationContractTest.kt`。
- Authority: destination 类型和 push 层级优先复用 fixed `main@6fbf6dfc` 的 Screen 语义；Desktop 路径参数只保留在 Desktop destination adapter。

- [x] RED：先让 Screen 类型、destination 参数或 production 导航回调断线时失败。
- [x] GREEN：接入 production destination callback，所有实际点击路径消费同一 callback。
- [x] Verify: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.extension.SourceExtensionNavigationContractTest"`
- [x] Commit: `test(desktop): verify source extension navigation`

  Evidence: commits `b9815b6e5`、`733df4771`；挂载后的 `open_extensions` 已改为响应式消费并 clear，More 真实点击、automation、destination 类型和参数 focused tests 全绿，聚合 `4 files, 179 lines`。唯一修复复审关闭 automation 与 `initialQuery`，但发现 ExtensionList 测试直接渲染 `ExtensionCard`，未触达 `ExtensionListScreen.Content → ExtensionListContent → InstalledTab → ExtensionCard`；按“修复后复审最多一轮”规则停止原 Task，并拆 6E1C 闭合测试证据。

#### Task 6E1C: ExtensionList 真实点击 wiring 证据

- Risk axis: `extension-list-navigation-evidence`
- Platform boundary: `desktop`
- Estimated scope: `2 files, 220 lines`
- Verification: 挂载真实 `ExtensionListScreen.Content` 与 production `ExtensionsScreenModel`/DI fixture，点击已安装扩展卡片与 configurable source 设置，分别断言精确 `jarPath`、`sourceId`、`sourceName`；断开 Content、ExtensionListContent、InstalledTab 或 ExtensionCard 任一 callback 传递层时测试必须失败。
- Files: `SourceExtensionNavigationContractTest.kt`；仅当无法通过现有 DI fixture 注入真实 model 时，允许在 `ExtensionListScreen.kt` 增加不改变行为的 typed model provider seam，总数不超过 2。
- Boundary: 这是 test-evidence closure，不重写导航、不再修改响应式 automation，不以测试自造 lambda 代替 production wiring。

- [x] 按现有 production 行为直接增加集成证据；若当前真实链路断开才进入 RED/GREEN 修复。
- [x] Verify: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.extension.SourceExtensionNavigationContractTest" --tests "mihon.desktop.ui.extension.ExtensionDetailsPreferencesWiringTest"`
- [x] Review: 新 reviewer 只审查真实 ExtensionList 点击链和精确参数，不重复已关闭的 automation 修复轮。
- [x] Commit: `test(desktop): close extension list navigation wiring`

  Evidence: commit `7c12cb04e`；真实 `ExtensionListScreen.Content` 通过 production `ExtensionsScreenModel`、Injekt 与 Desktop dependencies 呈现 installed configurable source，点击卡片与设置分别断言 exact `jarPath`、`sourceId`、`sourceName`。focused `SourceNavigation 3 + DetailsPreferences 2` 全绿，`2 files, 114 lines`；新 reviewer APPROVED，无 P0/P1/P2，确认移除直接渲染 private `ExtensionCard` 和测试自造 lambda 的绕路。

#### Task 6E2A: ExtensionList rendered copy

- Risk axis: `extension-list-i18n`
- Platform boundary: `desktop`
- Estimated scope: `5 files, 220 lines`
- Verification: 以 Locale.US/zh-CN 挂载真实 `ExtensionListScreen.Content`，从 Compose semantics 读取 Text/ContentDescription/Dialog；点击 filter、uninstall 等入口。期望只来自生成 MR accessor，中文代表值必须非 base fallback。禁止扫描 Kotlin/XML 或复制 production 文案常量。
- Files: `ExtensionListScreen.kt`、base/zh-rCN `strings.xml`、`DesktopExtensionListRenderedCopyTest.kt`、`ExtensionPresentationUiTest.kt`（现有点击必须使用同一 MR accessor，不得硬编码英文或强制 Locale.US 掩盖本地化回归）。
- Authority: 优先复用 fixed main 的 `ext_confirm_remove`、`ext_uninstall`、`action_cancel`、`action_bar_up_description`、`action_filter`、`ext_nsfw_short`、`action_apply`；卸载所有 sources、Desktop loader reload、filter/可访问性等原版无同义行为才新增 Desktop formatted key，不误用 WebView refresh。

- [x] RED：真实 List 的 dialog/filter/accessibility 仍使用硬编码 base 文案时，zh-CN rendered copy 失败。
- [x] GREEN：复用上游 key；仅为 Desktop 特有语义补 base/zh formatted key，并由真实 UI 消费。
- [x] Verify: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.i18n.DesktopExtensionListRenderedCopyTest" --tests "mihon.desktop.ui.extension.ExtensionPresentationUiTest"`
- [x] Commit: `refactor(desktop): localize extension list actions`

  Evidence: commits `5af2671ec`、`eec81ff63`；zh-CN 真实 filter/uninstall 首先因硬编码英文 RED，随后真实 List 与旧 Presentation UI 各 1 test 全绿。通用操作复用 fixed main MR key，Desktop 独有格式才新增 key；旧测试也改用同一 accessor。唯一修复复审确认测试 expected 全由 MR/locale/动态参数生成、筛选标题复用 `action_filter` 且冗余 key 已删除，APPROVED；最终 `5 files, 220 lines`。

#### Task 6E2B1: ExtensionDetails metadata/source rendered copy

- Risk axis: `extension-details-metadata-i18n`
- Platform boundary: `desktop`
- Estimated scope: `5 files, 220 lines`
- Verification: 以 Locale.US/zh-CN 挂载真实 Details，覆盖 version/origin、JAR/Windows artifact metadata、sources、browse/settings、incognito 等静态/格式化渲染；断开任一 production MR accessor 或 zh key 时测试失败。
- Files: `ExtensionDetailsScreen.kt`、base/zh-rCN `strings.xml`、`DesktopExtensionDetailsMetadataCopyTest.kt`、`ExtensionDetailsPreferencesWiringTest.kt`（既有 metadata/click 断言必须消费同一 MR accessor，不得硬编码英文或强制 Locale.US）。
- Authority: 复用 fixed main 的 back/version/unknown/sources/browse/incognito 等 MR key；JAR/Windows artifact origin、file/size/hash/fingerprint 属于 Desktop adapter，只新增其外层 label/format，不本地化动态路径、URL、版本、hash、fingerprint、名称或语言代码。

- [x] RED：zh-CN 真实 Details metadata/source copy 仍回退硬编码英文时失败。
- [x] GREEN：复用上游语义并补齐 Desktop platform metadata formatted key，不复制 Android PackageInstaller/WebView 文案。
- [x] Verify: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.i18n.DesktopExtensionDetailsMetadataCopyTest" --tests "mihon.desktop.ui.extension.ExtensionDetailsPreferencesWiringTest"`
- [x] Commit: `refactor(desktop): localize extension details metadata`

  Evidence: commits `8a289ffd8`、`e0f10d96b`；zh-CN 真实 Details metadata/source 首先因硬编码英文 RED，随后 MetadataCopy 1 + DetailsPreferences 2 全绿。通用语义复用 fixed main MR，Desktop origin/file/size/hash/fingerprint 仅本地化外层格式；唯一修复复审确认版本精确使用 `ext_info_version`，真实渲染保留 raw `1.2.3-raw` 与 `source.lang=en`，empty version 独立回退 localized unknown，APPROVED；最终 `5 files, 218 lines`。

#### Task 6E2B2: ExtensionDetails action/dialog feedback copy

- Risk axis: `extension-details-feedback-i18n`
- Platform boundary: `desktop`
- Estimated scope: `5 files, 220 lines`
- Verification: 以 Locale.US/zh-CN 挂载真实 Details，真实点击 folder failure、cookie clear、uninstall，读取 Snackbar/Dialog/ContentDescription；expected 只来自 MR accessor 与动态参数。
- Files: `ExtensionDetailsScreen.kt`、base/zh-rCN `strings.xml`、`DesktopExtensionDetailsActionCopyTest.kt`、`ExtensionDetailsPreferencesWiringTest.kt`（现有 action/feedback selector 与断言必须使用同一 MR accessor，不得硬编码英文或强制 Locale.US）。
- Authority: 复用 fixed main 的 clear-cookies、uninstall、cancel/open-repo 等同义 MR key；Desktop folder open/failure、cookie count、metadata removal 与带动态 source/pkg 的 accessibility 新增 Desktop formatted key。原始 error cause/message、数量和名称只作参数。

- [x] RED：zh-CN folder/cookie/uninstall 真实反馈仍渲染硬编码英文时失败。
- [x] GREEN：接入 MR accessor，不改变 Desktop platform action 或卸载语义。
- [x] Verify: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.i18n.DesktopExtensionDetailsActionCopyTest" --tests "mihon.desktop.ui.extension.ExtensionDetailsPreferencesWiringTest"`
- [x] Commit: `refactor(desktop): localize extension details feedback`

  Evidence: commit `05fc49e67`；zh-CN 真实 Details 首先因 folder/repository/cookie 动作仍为英文 RED，随后 ActionCopy 1 + DetailsPreferences 2 全绿。真实 US/zh 点击 folder failure、cookie clear、uninstall 并读取 Snackbar/Dialog/buttons；通用动作复用 fixed main，Desktop 仅新增 folder action/failure、cookie count、metadata removal body 4 key。独立 review APPROVED，无残留 B2 硬编码或 authority 混淆；`5 files, 177 lines`。

#### Task 6E2C: More source/extension entry copy

- Risk axis: `more-source-extension-i18n`
- Platform boundary: `desktop`
- Estimated scope: `5 files, 180 lines`
- Verification: 以 Locale.US/zh-CN 挂载真实 `MoreRootScreen`，断言 source/extension 相关入口 title/summary 来自生成资源并能点击；不扫描源码，不扩大到 More 其他功能。
- Files: `MoreRootScreen.kt`、base/zh-rCN `strings.xml`、`MoreSourceExtensionRenderedCopyTest.kt`、`SourceExtensionNavigationContractTest.kt`（既有真实点击 selector 使用同一 MR accessor，不得硬编码英文或强制 Locale.US）。
- Authority: 复用 fixed main 的 `label_extensions`、`label_extension_repos`；两个 Desktop More 导航 summary 新增 Desktop key。扩展名、仓库名等动态产品数据不本地化。

- [x] RED：zh-CN 真实 More 仍渲染 source/extension 英文入口时失败。
- [x] GREEN：真实 SettingsEntry 消费 MR accessor，保留既有点击 callback。
- [x] Verify: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.i18n.MoreSourceExtensionRenderedCopyTest" --tests "mihon.desktop.ui.extension.SourceExtensionNavigationContractTest"`
- [x] Commit: `refactor(desktop): localize extension navigation entries`

  Evidence: commit `721204f1d`；zh-CN 真实 More 两入口 4 段 copy 首先因硬编码英文 RED，随后 More rendered/click 1 + Navigation 3 全绿。title 复用 fixed main `label_extensions/label_extension_repos`，仅新增 2 个 Desktop summary；独立 review APPROVED，确认未扩展到 More 其他条目、无翻译常量复制或 authority 混淆；`5 files, 97 lines`。

#### Task 6E3A: 扩展搜索状态向原版 ScreenModel 对齐

- Risk axis: `extension-search-authority`
- Platform boundary: `desktop`
- Estimated scope: `3 files, 160 lines`
- Verification: `DesktopExtensionsState` 持有 query，`ExtensionsScreenModel.search(query)` 更新状态，production `ExtensionListContent` 只发送 intent 并消费 state；Screen 重组后 query 与过滤结果不丢失。移除 ScreenModel search 或重新引入 Compose-local query 时测试失败。
- Files: `ExtensionsScreenModel.kt`、`ExtensionListScreen.kt`、`ExtensionPresentationUiTest.kt`。
- Authority: 语义来自 fixed `main@6fbf6dfc` 的 `ExtensionsScreenModel.search(query)`；不得以当前 Android consumer 或 Desktop legacy Test Mode 为权威。

- [x] RED：证明当前搜索只存在于 composition，无法由 ScreenModel/Test Mode 控制。
- [x] GREEN：最小上收 query/intent，不改变共享分类与 Desktop 独有安装能力。
- [x] Verify: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.extension.ExtensionPresentationUiTest"`
- [x] Commit: `refactor(desktop): align extension search state`

  Evidence: commit `405d3e0d3`；RED 精确复现同一 model 重挂载后 EditableText 由 `Beta` 变为空，GREEN 将 query 上收为 `DesktopExtensionsState` 的唯一搜索业务状态，UI 只消费 state 并发送 `model::search`。真实 Compose 测试 2/2 通过，覆盖输入、过滤、重挂载和 model intent；独立 review APPROVED；`3 files, 63 lines`。

#### Task 6E3B1: Extension Test Mode production controller contract

- Risk axis: `extension-testmode-controller`
- Platform boundary: `desktop`
- Estimated scope: `4 files, 400 lines`
- Verification: 薄 controller 的 snapshot 只消费真实 `ExtensionsScreenModel.state`，复用 production projection 与 `AppError.toStoredAppError()`；refresh/search/install/update/cancel/retry/updateAll/uninstall/trust confirm/dismiss 均按稳定 `packageName` 调用真实 model intent。未知 package、无 pending trust 或无可用动作时必须明确失败。
- Files: 新增 `SourceExtensionTestModeController.kt` 与 `SourceExtensionTestModeControllerTest.kt`，并修改共享 `ExtensionPresentationContract.kt` 与 Desktop `ExtensionListScreen.kt`。
- Boundary: 不得复制搜索 predicate、分类/reducer、安装状态机、错误字符串或信任规则；Desktop 两阶段指纹信任是平台安全增强，必须保留。动作可用性必须复用 fixed main `InstallStep.isCompleted()` 语义的共享契约，UI 与 Test Mode 不得各自维护一套。

- [x] RED：断言 controller 不存在，并以真实 model 覆盖状态、动作、失败与取消契约。
- [x] GREEN：实现不持有第二份业务状态的 controller。
- [x] Verify: `./gradlew :app-desktop:jvmTest --tests "*SourceExtensionTestModeControllerTest"`
- [x] Commit: `test(desktop): bridge extension production model`

  Evidence: commits `d3685df05` + `489dda981`；初始 RED 为 controller unresolved，真实 model 契约 1/1 与 production UI 2/2 全绿。首轮 review 发现缺 query、动作资格、bridge 并发清理与 repository error 证据四项 P1；修复后 UI/controller 共用 fixed-main 式 enum member `isCompleted()` 语义，bridge 使用 `AtomicReference.compareAndSet`，repository identity + `StoredAppError` 受真实 projection 测试保护，唯一复审 APPROVED。累计 `4 files, 322 lines`；根 `spotlessCheck` 仅被范围外既有 `GlobalSearchSourcePolicyTest.kt` 阻断，本轮文件无违规。

#### Task 6E3B2A: Extension Test Mode DI 与 bridge 生命周期

- Risk axis: `extension-testmode-di-lifecycle`
- Platform boundary: `desktop`
- Estimated scope: `2 files, 120 lines`
- Verification: Injekt 注册的 controller、`SourceExtensionTestModeBridge.controller` 与 `DesktopTestDIContext` 都消费同一个 `ExtensionsScreenModel`；二次初始化必须替换 controller 并关闭旧 model，context 关闭必须先 identity-safe clear bridge 再关 model。删除 DI 注册、替换或 clear 任一处时测试失败。
- Files: `DesktopAppModule.kt`、`DesktopDiWiringTest.kt`。
- Boundary: bridge 仅保存 controller 指针，不缓存 snapshot；`TestMode.stop()` 不清 bridge，以便同一 DI 下重启 Test Mode。

- [x] RED：DI 中无 controller 绑定，bridge 不随 context 重建/关闭替换。
- [x] GREEN：用同一 model 注册 controller，并实现 identity-safe 生命周期。
- [x] Verify: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.di.DesktopDiWiringTest"`
- [x] Commit: `test(desktop): wire extension controller lifecycle`

  Evidence: commit `3a083ff50`；RED 因 Injekt 缺 controller 绑定精确失败，GREEN 后 `DesktopDiWiringTest` 8/8 通过。同一 model 被 Injekt/controller/bridge/context 共用，重建替换实例，关闭顺序为 identity-safe clear 再 close model；独立 review APPROVED；`2 files, 33 lines`。

#### Task 6E3B2B: Extension Test Mode HTTP production bridge

- Risk axis: `extension-testmode-http`
- Platform boundary: `desktop`
- Estimated scope: `2 files, 400 lines`
- Verification: embedded HTTP `/test/state` 来自 bridge 中的真实 controller；POST search 改变同一 Injekt model 与后续 state，安装失败经真实 model 暴露 `Error + StoredAppError.type`，cancel 清理真实 operation。缺 bridge、缺参数、未知 package 和 context 关闭后必须明确失败。
- Files: `TestHttpServer.kt`，新增 `SourceExtensionTestModeHttpTest.kt`。
- Boundary: server 只校验参数、用 kotlinx serialization `JsonObject/encodeToJsonElement` 序列化并转发 controller；不得手拼 extension JSON 或推演任何扩展状态。保留现有 action 响应的 `success/action/error/timestamp` 顶层兼容形状，扩展 snapshot 放入 nested `extension`。删除 legacy `extension_select/enable/disable` 空操作。

- [x] RED：真实 HTTP server 仍返回空扩展状态，空 action 不改变 production model。
- [x] GREEN：暴露稳定 DTO 并转发真实 controller intent。
- [x] Verify: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.test.http.SourceExtensionTestModeHttpTest" --tests "mihon.desktop.test.http.TestHttpServerJsonTest"`
- [x] Commit: `test(desktop): expose extension production state`

  Evidence: commit `56f108dec`；RED 证明 state 缺 extension 且 legacy action 伪成功，GREEN 后 HTTP production 测试 2/2、既有 JSON 测试 1/1 通过。state/action 全部使用 `JsonObject + encodeToJsonElement`，覆盖真实 DI search、Network 安装失败、retry/cancel、特殊字符串及 context 关闭后 503；legacy extension no-op 已删除；独立 review APPROVED；`2 files, 299 lines`。

#### Task 6E3B3: Extension Test Mode client 与 Robot contract

- Risk axis: `extension-testmode-client-contract`
- Platform boundary: `desktop`
- Estimated scope: `4 files, 400 lines`
- Verification: client 能安全序列化包含引号/反斜杠的 query，解析 state/action 共用的 nested extension DTO 与非 2xx 结构化错误；Robot 按 `packageName` 发送全部真实动作且不得吞掉 `ActionResult.success=false`。
- Files: `test-desktop/build.gradle.kts`、`MihonDesktopTestClient.kt`、`MoreRobot.kt`，新增 `SourceExtensionClientContractTest.kt`。
- Boundary: `test-desktop` 必须应用项目已有 Kotlin serialization 编译插件，使客户端的 `@Serializable` DTO 由生成 serializer 解析；不得为规避插件缺失而手写第二套 `JsonObject` 字段映射。删除 server/Robot 中 legacy `extension_select/enable/disable` 空操作与 index API；select 已由真实导航契约覆盖，enable/disable 在 fixed main 属于 Source 管理，后续只能以 `source_toggle/source_pin` 调用真实 Source model。

- [x] RED：legacy index/no-op API 与手拼 JSON 不符合新契约。
- [x] GREEN：客户端与 Robot 仅暴露稳定、真实的 production action。
- [x] Verify: `./gradlew :test-desktop:test --tests "*SourceExtensionClientContractTest"`
- [x] Commit: `test(desktop): align extension automation client`

  Evidence: commits `07fec9822` + `fc778cad6`；RED 证明 client/Robot/DTO 契约缺失，并暴露 `test-desktop` 未应用 serialization plugin 导致既有 DTO 运行时不可解析。GREEN 后契约测试 1/1 通过，安全编码、非 2xx 结构化错误、10 个真实 action 与 Robot 失败传播均覆盖。首轮 review 发现 stub 与 client DTO 自洽 round-trip；修复为独立固定 server-shaped JSON，并覆盖非空递归 `PartialFailure/failures/failedUnits`，唯一复审 APPROVED；累计 `4 files, 323 lines`。

#### Task 6E4A: SourceBrowse production observation 与 Compose 生命周期

- Risk axis: `source-login-observation-lifecycle`
- Platform boundary: `desktop`
- Estimated scope: `3 files, 400 lines`
- Verification: snapshot 每次直接读取 `SourceBrowseQueryCoordinator.state` 的 phase/request/itemCount/loading/hasNextPage/error/recovery，并用 `AppError.toStoredAppError()`；login host/feedback/terminal 每次直接读取当前 Compose-local `sourceLoginUiState`，不序列化 cookieHeader。取消携带当前 attempt 对象身份对应的临时 UUID token，回到 composition scope 后调用真实 `DesktopSourceLoginUiActions.cancel()`；错 token、无活跃登录、terminal 与 operation rejected 必须失败。
- Files: 新增 `SourceBrowseTestModeObservation.kt`、修改 `SourceBrowseScreen.kt`，新增 `SourceLoginTestModeWiringTest.kt`。
- Boundary: attempt token 仅存在于 automation port，不写回 UI/业务状态，不使用 `identityHashCode/toString`；只在当前 `SourceBrowseScreen` 的 `DisposableEffect` 注册并 compare-and-set 注销，不在 GlobalSearch 共用的 dialog host 注册，不写 shared/global TestState。authority 是 shared `SourceQueryState/SourceRecoveryAction` 与 Desktop 平台 `DesktopSourceLoginController/UiActions`；当前 `app/` consumer 与 Desktop Android shim 不是权威。

- [x] RED：真实 SourceBrowse 403/OpenLogin 产生的 attempt 不可观察/不可经 port 取消。
- [x] GREEN：composition 生命周期注册真实 UI port，取消后 ticket/UI/snapshot 同时终止，旧 token 不得取消新 attempt，旧 screen 注销不得清新 port。
- [x] Verify: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.browse.SourceLoginTestModeWiringTest"`
- [x] Commit: `test(desktop): observe source login lifecycle`

  Evidence: commits `83eb3c81b` + `d9bf01cba`；RED 先证明 production observation 缺失，再用确定性 `CountDownLatch` 复现 close 后 snapshot 仍泄露 login/重生 token。GREEN 将最终 closed 检查、DTO/token 发布和 cancel 资格线性化，真实 Compose 403/OpenLogin、取消、旧 token、CAS 注销与 finally 清理同测通过。focused XML `1/0/0`，唯一修复复审的功能、竞态、清理、authority 与 `3 files, 399 lines` 均通过；复审只留下格式门禁，转入 6E4AF。

#### Task 6E4AF: Source login observation 格式门禁收口

- Risk axis: `source-login-observation-format`
- Platform boundary: `desktop`
- Estimated scope: `2 files, 260 lines`
- Verification: 仅对 6E4A 的 observation 与 wiring test 做机械换行、缩进和 Spotless 收口，不改变竞态、token、取消或清理语义；格式化后原 focused test 必须仍通过。
- Files: `SourceBrowseTestModeObservation.kt`、`SourceLoginTestModeWiringTest.kt`。
- Boundary: 本项是 6E4A 唯一复审发现的非行为阻塞；约 90 行 `try` 正文需整体补一级缩进，且复审要求拆分残余的 9 个超长行，因此接受不超过 260 行的纯空白 churn；不得借机修改生产语义或扩大 6E4B HTTP/client 范围。

- [x] GREEN：两文件满足仓库 Spotless/ktlint，且 6E4A focused test 保持通过。
- [x] Verify: `./gradlew :app-desktop:spotlessKotlinCheck :app-desktop:jvmTest --tests "mihon.desktop.ui.browse.SourceLoginTestModeWiringTest"`
- [x] Commit: `style(desktop): format source login observation`

  Evidence: commits `faeb8c126` + `4ba884bb`；两个目标文件去除全部空白后与基线逐字等价，所有行均不超过 120 列，`try/finally` 缩进与 `git diff --check` 通过，累计 `2 files, 259 changed lines`。focused XML `1/0/0`，唯一复审 APPROVED。模块级 Spotless task 不存在；根 `spotlessCheck` 未报告本项文件，仅被任务外既有 `ExtensionPresentationContract.kt` 与 `GlobalSearchSourcePolicyTest.kt` 阻断。

#### Task 6E4B: Source/Login HTTP 与 client contract

- Risk axis: `source-login-http-client-contract`
- Platform boundary: `desktop`
- Estimated scope: `5 files, 400 lines`
- Verification: GET `/test/state` 暴露 port 的 nested source snapshot；POST `source_login_cancel` 必须携 GET 取得的 `attemptToken` 并仅转发 active port。app HTTP 测试证明 HTTP 取消真实 ticket/UI state；client 解析同一 DTO，Robot 传播结构化失败。bridge 缺失为 503，missing token 为 400，NO_ACTIVE/ATTEMPT_MISMATCH/TERMINAL/REJECTED 为 409。
- Files: `TestHttpServer.kt`，新增 `SourceLoginTestModeHttpTest.kt`，`MihonDesktopTestClient.kt`、实际承载 browse automation 的 `LibraryRobot.kt`，新增 `SourceLoginClientContractTest.kt`。
- Boundary: server/client 不得复制 query/login 状态机或暴露 cookieHeader；删除 `browse_search` 遗留空操作/Robot API，不得伪装已接入 query coordinator。Source toggle/pin 不在本项范围，后续若实现必须使用 `source_toggle/source_pin` 调用真实 Source model。

- [x] RED：HTTP/client 不可见 active source/login state，`browse_search` 仍伪成功。
- [x] GREEN：HTTP 只读取/转发 active port，client/Robot 使用稳定 token 契约并不吞失败。
- [x] Verify: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.test.http.SourceLoginTestModeHttpTest" :test-desktop:test --tests "mihon.test.desktop.SourceLoginClientContractTest"`
- [x] Commit: `test(desktop): expose source login cancellation`

  Evidence: commit `e4e56ba0c`；清洁 RED 为 App `2/2` 因 nested source/action production contract 缺失而失败，client 仅因目标 DTO/Robot API 缺失而编译失败。GREEN 后 App HTTP `2/0/0`、client `1/0/0`：真实 `DesktopSourceLoginController → SessionFactory → BrowserLoginTicket → UiActions.cancel → port → HTTP` 链路终止 ticket/UI/job；GET/action envelope 只读取 active port，token 安全编码，非 2xx 结构化失败与独立 server-shaped fixture 均覆盖，`browse_search` 伪成功删除。独立 review APPROVED；范围 `5 files, 399 lines`，无 current app consumer/Desktop Android shim authority 混淆。

#### Task 6E closure

##### Task 6E5: Extension presentation contract 格式门禁收口

- Risk axis: `extension-presentation-contract-format`
- Platform boundary: `shared`
- Estimated scope: `1 file, 5 lines`
- Verification: 仅将 6E3B1 修改的 enum 末项收敛为仓库 Spotless 要求的尾逗号与独立分号，不改变共享状态或 eligibility 语义；6E3B1 focused test 保持通过，根 Spotless 不再报告本文件。
- Files: `domain/src/commonMain/kotlin/mihon/domain/extension/presentation/ExtensionPresentationContract.kt`。
- Boundary: 纯格式修复，不处理任务外既有 `GlobalSearchSourcePolicyTest.kt`，不得扩大到其他 domain 文件。

- [x] GREEN：本文件满足 Spotless，且 extension production controller contract 保持通过。
- [x] Verify: `./gradlew :app-desktop:jvmTest --tests "*SourceExtensionTestModeControllerTest" spotlessCheck`
- [x] Commit: `style(domain): format extension presentation contract`

  Evidence: commit `2affee53f`；仅将 enum 末项改为 Spotless clean output 要求的尾逗号与独立分号，归一化后语义等价，`1 file, 3 changed lines`。controller focused XML `1/0/0`；根 Spotless 不再报告本文件，唯一剩余阻断是任务外既有 `GlobalSearchSourcePolicyTest.kt`。独立 review APPROVED，并确认 6E closure 的唯一 blocker 已解除。

- [x] 串行运行 6E1–6E4 focused tests，再运行 `./gradlew :app-desktop:jvmTest --tests "*Navigation*ContractTest" --tests "*I18n*" :test-desktop:test`。
- [x] 独立审查确认 HTTP interface 真实触达 production wiring，且 fixed main、当前 Android consumer 与 Desktop adapter 没有概念混淆。
- [x] Check off OpenSpec 2.3、3.4、3.5 的自动化观察部分并提交证据。

  Closure evidence: App focused 13 类 `26/0/0`，client focused `2/0/0`，原样 closure 组合 App `55/0/0` + client `17/0/0`，累计执行 100 tests（含组间重复）且 0 failures/0 errors，总墙钟 104.688 秒，最终 Java 进程 0。独立 closure review 确认 navigation、真实 Compose i18n、extension DI/model/controller/HTTP/client 与 source login Compose/controller/ticket/HTTP/client 均触达 production wiring；fixed authority 为 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8` 的原版 Mihon 语义，当前 Android consumer 与 Desktop Android shim 均未被误作 authority，Desktop 差异只保留在 adapter/automation token。OpenSpec 2.3、3.4、3.5 已为完成状态并由本轮证据复核。

### Task 7: compat 去重、parity 证据、全量审查与跨平台运行时验收

**OpenSpec mapping:** 4.1、4.2、4.3、4.4、4.5、4.6

**Risk axis:** parity-evidence
**Platform boundary:** verification
**Estimated scope:** 47 files, 1950 lines
**Verification:** 运行 compat 契约、全量 Gradle/桌面测试构建和 Android/Windows/macOS 运行时验收，并完成 thorough 独立审查。
**Execution split（C11）:** 当前 Desktop Android/AndroidX compat 为 39 个文件、43 个顶层 public 类型，而 `compat-evidence.json` 只有一条且为 `unsupported`；`AndroidCompatTest` 和 parent-classpath `MinimalTestSource` 只能证明 adapter 自测，不能证明真实扩展需要某 shim。固定 main 的 `Source` ABI 与 Android `ExtensionLoader` 是兼容目标，Desktop shim 永远只属于平台 adapter。原 7 files/350 lines 不能承担逐符号证据与删除，拆分如下。
**Split waiver:** 47 files/1,950 lines 是 inventory、真实 fixture、多个 package prune 批次与最终运行验收的聚合上界；任何单次调度仍不得超过 8 files/400 lines，且删除批次必须逐包消费前序真实证据，无法作为一个原子 Task 执行。

#### Task 7A: Compat public surface inventory

- Risk axis: `compat-public-inventory`
- Platform boundary: `verification`
- Estimated scope: `4 files, 250 lines`
- Verification: 每个 public compat symbol 在独立 inventory 中恰有一项；7A 允许诚实的暂态 `unverified`，但只有真实 fixture production 调用才能在 `compat-evidence.json` 中解析为 `required`/`unsupported`，单元自测不得升级为真实扩展证据。7D 完成门禁要求 `unverified` 为 0。

#### Task 7B: Compat real fixture evidence

- Risk axis: `compat-real-fixture-evidence`
- Platform boundary: `verification`
- Estimated scope: `11 files, 1,050 lines plus one 70,062-byte binary fixture`
- Verification: 真实 APK/JAR 必须通过 production converter/loader 并实际调用所声明 symbol；parent classpath fixture、仅加载 class 或网络调查输出不算 `required` 证据。每条真实证据负责将对应 inventory 项从 `unverified` 解析为 `required` 或具备真实失败证据的 `unsupported`。
- Split waiver: 二进制 fixture/provenance/loader outcome、production DI wiring、dex2jar Kotlin ABI 修复与 evidence/baseline 清理需要不同的 RED 和审查，按 7B1/7B2/7B2K/7B3 调度；任何单次实现仍不超过 4 files/350 lines。

##### Task 7B1: Immutable real APK 与 production loader outcome

- Risk axis: `compat-real-fixture-provenance`
- Platform boundary: `verification`
- Estimated scope: `4 files, 300 lines plus one 70,062-byte APK`
- Verification: 本地 fixture 固定为 Keiyoushi ManHuaGui 1.4.28、repo commit `7d5052fb895d086ae2ec6e3cca861146ee3ea0ec`、blob `4529f7017f762a70d52bc15ff70e6260fae17d98`、SHA-256 `200cfc4b3b9e98f387824e3cecb13f97f4b0971f8fb678ce49c60aab6856c0c8`，来源 Apache-2.0。根 `.gitattributes` 必须在全局 text 规则后声明 `*.apk binary`，普通 stage/fresh checkout 不得改变 blob/大小。测试必须离线校验 hash/manifest/package/version，经 production `ApkToJarConverter` 与 `DesktopExtensionLoader`，并证明 loaded class 来自 converted JAR；若失败，只接受精确、结构化的首个真实 compat gap，不把 classpath/self-test 当成功。

##### Task 7B2: Exact compat invocation 与 evidence resolution

- Risk axis: `compat-exact-invocation-resolution`
- Platform boundary: `desktop`
- Estimated scope: `4 files, 350 lines`
- Verification: 只消费 7B1 的真实 loader outcome；固定 main `AppModule` 明确 `addSingleton(app)`，而 7B1 证明真实 extension 构造通过 Injekt 精确请求 `android.app.Application`，因此先将 fixture 期望改为 success 取得 RED，再让 Desktop 初始化现有 Application adapter 并注册同一 contract。成功必须返回 converted-JAR Source；只有该因果链闭合后才能把 Application/Source evidence 解析为 `required`。仅 linkage/class-load 不得升级为 `required`，不得批量新增 shim；若出现下一 gap，停止并继续拆分。

  7B1 evidence: commit `cf9835804`；RED1 精确失败于本地 fixture 缺失，RED2 在真实 Desktop DI、manifest、converter、meta 与 production loader 后精确得到 `InjektionException: No registered instance or factory for type class android.app.Application`。GREEN 将该唯一 outcome 固定为 provenance 中的结构化 `unsupported`，任意其他 Throwable 均失败；XML `1/0/0`。APK 固定上游 commit/blob/70,062 bytes/SHA-256，Git binary attrs 保证普通 stage/fresh checkout 不改字节。独立 review APPROVED；范围 `4 files, 160 text lines plus APK`。

  7B2 evidence: commit `2e17f259f`；真实 fixture success 期望与 production DI exact Application 解析分别取得 RED。GREEN 复用 Desktop 现有 Application adapter，按 `AndroidCompat.initialize → Application → attach/onCreate → Injekt.addSingleton(application)` 初始化，并在 production/test scope 的其他模块前调用，精确对齐 fixed main `AppModule.addSingleton(app)`；DI XML `1/0/0`。真实 loader 已越过 Application，严格推进到唯一后续 `NoSuchMethodError: Duration$Companion.getZERO_UwyO8pc()`，Real XML `1/0/0`，未猜测注册 Context/新增 shim/修改 loader。独立 review APPROVED；范围 `4 files, 31 changed lines`。

##### Task 7B2K: dex2jar Kotlin mangled ABI 修复

- Risk axis: `dex2jar-kotlin-mangled-abi`
- Platform boundary: `desktop`
- Estimated scope: `4 files, 350 lines`
- Verification: 7B2 注册 Application 后真实 fixture 推进到 `Duration.Companion.getZERO_UwyO8pc`，而宿主 Kotlin 真实 JVM 方法名为 `getZERO-UwyO8pc`，证明 gap 来自 dex2jar 将合法 JVM `-` 净化为 `_`，不是原版业务差异或缺少 Android shim。Bytecode post-process 必须按外部 owner + 完整 descriptor 查询宿主真实方法并只恢复唯一匹配名称；不得硬编码 Duration/方法名，不得改写 extension JAR 自有 owner 或无唯一宿主匹配的方法。单元 RED/GREEN 与真实 fixture success 必须同时通过；若出现下一 gap，停止并继续拆分。

  Evidence: commits `902960ce8` + `84ffeb86f`；RED 证明 dex2jar 输出调用 `getZERO_UwyO8pc`，宿主唯一真实方法为同 descriptor 的 `getZERO-UwyO8pc`，真实 fixture 同时精确失败。GREEN 使用 input-JAR owner 边界、外部 host class 无初始化加载、完整 descriptor 与 conversion-scope cache 做通用唯一恢复，production 无 Duration/方法名硬编码；真实 APK 已通过 converter/loader、converted-JAR CodeSource 与 Source id/name/lang。首轮 review 发现精确 underscore 方法与 hyphen 候选并存时可能误改；唯一修复先保持原测试全绿，再以 exact-priority 单一 RED 补齐精确优先、ambiguous、constructor/clinit 和 descriptor 隔离，最终 Bytecode `10/0/0`、Real `1/0/0`，复审 APPROVED；范围 `4 files, 246 touched lines`。

##### Task 7B3: Resolved evidence 与旧 baseline 收口

- Risk axis: `compat-resolved-evidence-baseline`
- Platform boundary: `verification`
- Estimated scope: `4 files, 250 lines`
- Verification: 只在 7B2 真实 fixture 成功后，将 `android.app.Application` inventory 与 fixed-main `Source` ABI evidence 解析为本地 fixture 的 `required`；同步移除 `DesktopExtensionProductBaselineTest` 对单一 ManHuaGui 条目和测试源码字符串的假验证，改由真实 fixture 行为测试与 `CompatEvidenceContractTest` 保护。其他 42 个 inventory 项保持 `unverified`，不得借成功加载一个扩展批量升级。

  Evidence: commit `a1b65a746`；inventory 仅 `android.app.Application=required`，其余 42 项保持 `unverified`；compat evidence 恰有 Application 与 fixed-main Source 两条 unique required，均指向同一 immutable local APK@SHA 与 Real test，removalCondition 分别限定 exact Application binding 与 Source ABI。旧 ProductBaseline 的单一 package@version/unsupported 假设和读取测试源码 `contains` 验证已删除，改为 repo 内 artifact/test/schema 与两个 resolved 元数据门禁；Real test 继续保护 production converter/loader 行为。Compat `2/0/0`、Baseline `5/0/0`、Real `1/0/0`，独立 review APPROVED；范围 `3 files, 74 touched lines`。

#### Task 7C: Compat 真实行为闭环与有证据去重

- Risk axis: `compat-behavior-pruning`
- Platform boundary: `desktop`
- Estimated scope: `8 files, 400 lines per independently reviewed batch`
- Split waiver: 本项覆盖设置 ABI、页面 ABI 与多个互不相同的 compat 包；它们不能在一个提交或一次调度内安全闭合，因此以下子任务分别实施、测试、审查和提交，每个实际批次仍不超过 8 files/400 lines。
- Verification: 先用 immutable 真实 APK 执行 fixed-main 行为链，再决定 shim 为 `required`、明确 `unsupported` 或删除。不得把当前 `app/` consumer、Desktop Android 构建或静态源码引用当 authority；不得仅因单一 fixture 未调用就删除旧扩展常见 ABI。所有平台差异只留在 Desktop adapter，不修改 shared contract 来冒充原版 Android ABI。

##### Task 7C1: 真实 AndroidX source settings bridge

- Risk axis: `real-androidx-settings-bridge`
- Platform boundary: `desktop`
- Estimated scope: `8 files, 390 lines`
- Verification: immutable ManHuaGui APK 必须经 production converter/loader 后，从 Desktop production settings resolver 调用其真实 `setupPreferenceScreen(androidx.preference.PreferenceScreen)`，得到 4 个真实设置项（`preferred_mirror`、`mainSiteRatelimitPreference`、`imgCDNRatelimitPreference`、`showR18Default`）及原版 `setDefaultValue` 语义。RED 先固定当前 `AbstractMethodError`，桥接旧 descriptor 后继续固定 `addPreference(Preference):Z` ABI gap；若 ABI 闭合后 JVM descriptor 仍因只读取 `ListPreference.value` 而丢失 inherited `defaultValue`，必须以同一真实 fixture 取得第三个精确 RED，且只允许在 `source-api` JVM adapter 修复转换，不改 common/Android authority。GREEN 将反射兼容限定在 Desktop adapter、让 `addPreference` 返回 AndroidX 兼容 `Boolean`，并证明 Desktop UI 写入与真实 extension `Application.getSharedPreferences("source_$id")` 读取同一 `/mihon/source_$id` 节点。只有行为成功后才将 Context/SharedPreferences/AndroidX 继承闭包解析为 `required`。

  Evidence: implementation commit `ec9309ab8`、review repair `5de09713a`。真实 immutable APK 依次取得 `AbstractMethodError`、`NoSuchMethodError: addPreference(Preference):Z`、ListPreference default `expected 0 but null` 三个精确 RED；GREEN 以 Desktop legacy descriptor adapter、AndroidX Boolean ABI、JVM defaultValue fallback 和统一 `/mihon/source_$id` 节点闭合 4 个真实设置项及持久化。Real `1/0/0`、Compat contract `2/0/0`、settings wiring `2/0/0`；inventory 为 11 required + 32 unverified，evidence 12/12 unique required。首轮 review 唯一 Important 为测试写删真实业务 key；修复改用每次唯一 sentinel 且 finally 证明两侧清理，复审 APPROVED。范围 8 files/208 touched + repair 1 test file，Java0；root Spotless 唯一阻塞仍为提交外既有 `GlobalSearchSourcePolicyTest.kt`。

##### Task 7C2: Fixed-main Page(Uri) ABI 与真实 page-list 行为

- Risk axis: `real-page-uri-abi`
- Platform boundary: `desktop`
- Estimated scope: `4 files, 300 lines`
- Verification: fixed main `Page` 的旧扩展 ABI 使用主构造 `(I,String,String,android.net.Uri)V` 与默认参数构造 `(I,String,String,android.net.Uri,I,DefaultConstructorMarker)V`，真实 ManHuaGui `pageListParse` 调用后者；当前 common `Object` descriptors 不能作为 authority。immutable APK 经 production converter/loader 后，必须由本地 MockWebServer 返回真实 Dean-Edwards packed-script HTML，再反射执行 extension 自身 protected parser；RED 精确解包到 `NoSuchMethodError`，不得停在输入解析错误。GREEN 只允许 Desktop `BytecodeEditor` 对 owner `eu/kanade/tachiyomi/source/model/Page` 的上述两个 `<init>` descriptors 做 exact allowlist `Uri→Object` 重写，其他 owner/方法/descriptor 原样保留；不得修改 common `Page`、不得将 `android.net.Uri` 下沉到 shared authority。真实 parser 必须返回 parent-loaded host Page 及 `https://i.hamreus.com/comic/123/001.jpg?e=1700000000&m=sig`，并继续通过 Desktop reader Page 消费回归。该调用的 Uri 实参为 `null`，只证明 fixed-main Page binary ABI 兼容需求，不足以将 Uri shim 行为解析为 `required`。

  Evidence: commit `57462ea71`。ASM RED 为 BytecodeEditor `11 tests/1 failed`，精确证明 fixed-main Page 主 descriptor 仍为 Uri；真实 immutable APK RED 经 production converter/loader、MockWebServer packed HTML 与 extension 自身 protected parser，精确解包到默认参数 Page(Uri) `NoSuchMethodError`，不是 parser input failure。GREEN 仅 exact allowlist 两个 Page `<init>` descriptors，Bytecode `11/0/0`、real parser `1/0/0`、Desktop reader `2/0/0`，共 `14/0/0`；返回 exact parent-loaded host Page、index 0 与完整 i.hamreus URL。范围 4 files/190 touched，Java0；Uri inventory 保持 unverified、evidence 未新增 Uri，独立 review APPROVED。root Spotless 唯一阻塞仍为提交外既有 `GlobalSearchSourcePolicyTest.kt`。

##### Task 7C3: Representative fixture matrix 与 compat prune batches

- Risk axis: `compat-fixture-matrix-pruning`
- Platform boundary: `desktop`
- Estimated scope: `8 files, 400 lines per prune batch`
- Split waiver: 31 个非 Application public compat 类型分属独立 ABI 族；每批只处理一个依赖闭包并单独审查，避免一次删除跨包能力。后续每个实际批次必须在施工前把 fixture、产品边界、文件与 verification 写回本计划。
- Verification: 对 `android.content/pm`、`android.os`、`android.text`、`android.util`、graphics/drawable、webkit 与剩余 AndroidX 类型，先选择能执行真实调用的本地可追溯 extension fixture；无法支持的平台能力必须有明确用户边界与 production 不可达证据。`required` 必须由真实 artifact 的 production invocation 证明；`unsupported` 必须由真实精确失败与产品边界证明；删除必须同时满足无 production consumer、代表性 fixture 不需要或产品明确不支持，并在删除前取得 public-surface RED。不得用 compat 自测或源码扫描代替行为证据；保留 Desktop-native EditText/MultiSelect/Switch 设置能力。

##### Task 7C3a0a: Fixed-main ContextWrapper 继承 ABI

- Risk axis: `android-context-inheritance-abi`
- Platform boundary: `desktop`
- Estimated scope: `5 files, 280 lines plus one 96,835-byte APK`
- Verification: 固定 Comix 1.4.34 fixture/provenance 与下述 commit/blob/hash/package/class 完全相同。RED 必须由 production converter/loader 精确得到 JVM verifier 拒绝 `android.app.Application` 传给需要 `android.content.Context` 的 preference 构造器，根因是 Desktop `ContextWrapper` 未按 fixed main/Android API 继承 `Context`；不得把 loader 空结果当充分诊断。GREEN 只将 Desktop `ContextWrapper` 恢复为 `Context` 子类并正确 override 委托方法，同时用 unit ABI 断言与真实 Comix invocation 证明 Application 可作为 Context、原 VerifyError 已消失。若真实 fixture 随后推进到新的独立 linkage gap，本 Task 应把该精确 root 固定为结构化下一 gap 后提交，不把后续平台 ABI 偷塞进同一修复；本 Task 不要求 settings Content、不修默认值/dialog title、不更新 compat evidence。

  Evidence: implementation `6fdb493f8`、review repair `6ee0f04b5`。unit RED `14 tests/1 failed` 与真实 Comix production loader VerifyError 同时证明 Application/ContextWrapper 不可赋给 Context；GREEN 恢复 `ContextWrapper : Context()` 与委托 overrides，AndroidCompat `14/0/0`、ManHuaGui `1/0/0`。真实 Comix 经 immutable APK/provenance、production converter/metadata/loader 后越过原 VerifyError，并将 next-gap 固定为 `ClassNotFoundException: android.view.View`；provenance 记录 unsupported/root type/message，loader empty 且 diagnostics empty排除 outer wiring，direct helper只诊断per-class swallow。Real Comix `1/0/0`，合计 `16/0/0`、Java0。首审两个关联 Important 为 outcome 写 success 与 production empty 原因未约束；修复后复审 APPROVED。范围 5 files/164 text touched + APK，repair 2 files。

##### Task 7C3a0b: Android View verifier ABI token

- Risk axis: `android-view-verifier-token`
- Platform boundary: `desktop`
- Estimated scope: `7 files, 260 lines`
- Verification: 消费 7C3a0a 的 immutable Comix fixture，RED 精确为 production-loaded source superclass 在 verifier 阶段缺 `android.view.View`，该引用来自同一 source class 中未执行的 WebView 内容解析方法，不是 preference listener。GREEN 只提供 `android.view.View`、`View.MeasureSpec`、`ViewGroup` 与 `ViewGroup.LayoutParams` 的 exact class/descriptors：`setLayoutParams(LayoutParams)`、`measure(II)`、`layout(IIII)`、`makeMeasureSpec(II)I` 与 `LayoutParams(II)`；`makeMeasureSpec` 保留 Android size/mode 位编码，其他需要真实 UI 引擎的操作必须 fail-fast，禁止 no-op 冒充 WebView 支持。真实 Comix loader 必须越过 View root，并将 provenance/test 的 root type/message更新为实际出现的下一 WebView linkage gap；production loader outer diagnostics 仍须为空。新增 top-level public adapter 后必须同步 compat contract 的真实 file/symbol 计数。本 Task 不运行 WebView 分支、不更新 compat evidence，新 top-level inventory 项保持 unverified，直到真实产品行为有证据。

  Evidence: commit `4ce4619b3`。reflection unit RED `1/1` 精确缺 `android.view.View`；GREEN 固定 View/MeasureSpec/ViewGroup/LayoutParams binary names、constructors/descriptors、EXACTLY 位编码，三个 UI engine 操作均实际断言 fail-fast。真实 Comix RED2 从旧 provenance View推进到实际 `ClassNotFoundException: android.webkit.WebView`，production loader empty + diagnostics empty、direct root与更新后的 provenance一致。Android View `1/0/0`、Real Comix `1/0/0`、AndroidCompat `14/0/0`、ManHuaGui `1/0/0`、Compat contract `2/0/0`，共 `19/0/0`、Java0。surface 41 files/45 symbols；View/ViewGroup仅新增为 unverified，无 evidence。范围 7 files/104 touched，独立 review APPROVED。

##### Task 7C3a0c: Comix WebView verifier closure

- Risk axis: `comix-webview-verifier`
- Platform boundary: `desktop`
- Estimated scope: `7 files, 380 lines`
- Verification: 消费 immutable Comix fixture，RED 精确为 production source superclass 缺 `android.webkit.WebView`。GREEN 必须提供 exact top-level binary classes `WebView : ViewGroup`、`WebSettings`、`WebViewClient`、`WebResourceRequest` interface、`WebResourceResponse`，并覆盖固定 APK 实际引用的 constructors/method descriptors；可在一个 `WebViewCompat.kt` 声明多个 top-level 类型，但不得写成嵌套类。`CookieManager.setAcceptThirdPartyCookies` 参数必须是 exact `WebView`，不能用 `Any/Object`；`ValueCallback` 保持现有 erased ABI。所有 WebView 构造/执行、WebSettings setters、WebViewClient callbacks 与第三方 Cookie 联动必须 fail-fast 并说明 Desktop 尚无 WebView engine；WebResourceRequest 仅定义 interface，WebResourceResponse 只保存 mime/encoding/InputStream。真实测试必须显式初始化并关闭 Desktop production DI，禁止依赖全局 Injekt 测试顺序；若 loader 在完整 DI 下成功，则 provenance 记录 success、移除临时 root 字段，并断言 source 来自 converted JAR，不得用空 DI 制造 NetworkHelper 假 gap。新增 5 个 public symbols 以 unverified 进入 inventory，contract 更新为 42 files/50 symbols；CookieManager/ValueCallback/Uri及新类型均不得写 required evidence，本 Task 仍不调用 settings 或 WebView 行为。

  Evidence: implementation `2911aefda`、review repair `225db002e`。reflection RED `1/1` 精确缺 WebView；GREEN 固定 5 个 top-level WebKit binary classes、全部 fixed-APK descriptors、CookieManager exact WebView 参数，并逐项执行验证 WebView/WebSettings/WebViewClient/third-party Cookie fail-fast，response ctor参数保留。显式 production DI 后真实 Comix loader 成功、diagnostics empty、source class与converted-JAR CodeSource精确，证明先前空 Injekt 的 NetworkHelper gap是假证据；provenance纠正为 success。Real单跑 `1/0/0`、组合回归 `39/0/0`、Java0；inventory 50=11 required+39 unverified，5个新WebKit项仅unverified。首审唯一 Important 为关闭资源后未恢复global Injekt；修复保存/最外层恢复并加 `@Isolated`，异常路径清理顺序 classloader→DI context→Injekt，复审 APPROVED。范围7 files/337 touched + repair1 test file。

##### Task 7C3a: Comix 真实 EditText/MultiSelect/Switch 设置语义

- Risk axis: `androidx-preference-default-semantics`
- Platform boundary: `desktop`
- Estimated scope: `7 files, 360 lines`
- Verification: 消费 7C3a0 已固定的 Keiyoushi Comix 1.4.34 本地 APK/provenance：artifact repo commit `7d5052fb895d086ae2ec6e3cca861146ee3ea0ec`、blob `ebade6b9ed19d1ba02ac67c377cef31caa0bb0c7`、SHA-256 `5d46a6ef98c1ac4f2ab22a29347748a36eb32b6995fb8a08e092446424e366d8`、96,835 bytes、Apache-2.0，package `eu.kanade.tachiyomi.extension.en.comix`，extension class `eu.kanade.tachiyomi.extension.en.comix.ExtensionGenerated`。测试必须由本地 APK 经 production converter/loader 取得真实 source，再经 production settings resolver/`DesktopAndroidPreferenceAdapter` 调用 APK 自身 `setupPreferenceScreen`。固定 APK 已证明不引用 `OnBindEditTextListener`，禁止按候选源码添加无证据 widget/listener shim。RED 应精确证明 AndroidX→JVM descriptor 把 `pref_default_types`/`pref_default_demographics` 默认全选变成空集、把 `pref_show_extra_info=true` 变成 false，且 `pref_scanlator_blacklist` 无法表达原版 dialog title `Exclude groups`；独立 UI wiring RED 必须证明 Compose 仍错误显示行标题。GREEN 中 Switch/MultiSelect descriptor 的 default 必须优先来自 inherited AndroidX `Preference.defaultValue`，仅在未设置 default 时回退当前 checked/values；JVM EditText descriptor 增加可空 dialog title，转换器读取 `DialogPreference.getDialogTitle()`，Compose 对话框显示 `dialogTitle ?: title`，并由渲染/点击集成测试保护。真实结果必须断言 3 个 MultiSelect、4 个 Switch、1 个 EditText 的关键 key/title/entries/default/value；只有成功后才将三个 public symbols以该 immutable APK/test解析为 required。该批次不承诺或修改 Comix 的 WebView、Cookie、Handler/Looper、OnBindEditText 与图像处理路径。

  Evidence: implementation `1feb0e07e`、ABI repair `18a6a708b`。真实 Comix RED 精确证明 3 个 MultiSelect 的上游默认集合和 `pref_show_extra_info=true` 被 Desktop 转换为空/false，UI RED 精确证明点击黑名单设置后仍显示行标题而非 `Exclude groups`；GREEN 从 inherited AndroidX `defaultValue` 读取默认值并保留仅 null 时回退当前状态，EditText descriptor 读取 dialog title，Compose 使用 `dialogTitle ?: title`。真实 APK 经 production DI/converter/loader/settings resolver 后断言 3 MultiSelect、4 Switch、1 EditText，组合回归 `38/0/0`。首审发现给公开 data class 主构造器增加默认参数仍删除旧 JVM descriptors；唯一修复先取得缺 `(String,String,String)V` 的精确 RED，再把 dialog title 改为 body property，反射同时保护旧三参数构造与 default-mask 构造，Real/ABI `2/0/0`、UI/JVM descriptor 使用方 `20/0/0`，复审 APPROVED。inventory 50=14 required+36 unverified，evidence 15 条 unique required；WebKit、Uri 等未执行行为保持 unverified。范围 implementation 7 files/148 touched，repair 4 files/26 touched，Java0；source-api Spotless 通过，root Spotless 唯一阻塞仍为提交外既有 `GlobalSearchSourcePolicyTest.kt`。

##### Task 7C3b-pre: Mangalix default client Cloudflare identity

- Risk axis: `cloudflare-interceptor-identity`
- Platform boundary: `desktop`
- Estimated scope: `2 files, 120 lines`
- Verification: 7C3b0 的 immutable Mangalix fixture 在 production DI/loader 中首先真实失败于 `CloudflareInterceptor must be present in default client`；固定扩展按 `client.interceptors.any { it.javaClass.simpleName == "CloudflareInterceptor" }` 检查上游默认客户端，而 Desktop 注入的同功能 adapter 运行时类名为 `DesktopCloudflareInterceptor`。不得用测试替身或修改 fixture 绕过。RED 在 production `DesktopNetworkHelper.client.interceptors` 断言 exact runtime simple name；GREEN 只把现有 Desktop challenge adapter 的运行时类名恢复为 `CloudflareInterceptor`，并用 source-level alias 保留 `DesktopCloudflareInterceptor` 调用方，既不复制 Android WebView 实现，也不改变 challenge manager、cookie provenance 或 recovery 语义。现有策略测试必须全绿，真实 Mangalix 链路必须越过该错误并暴露下一实际 JsonReader ABI gap。

  Evidence: commit `24f15ea45`，2 files/22 touched。production runtime simple-name 契约先在 `DesktopChallengeRecoveryPolicyTest.kt:62` 精确 RED；GREEN 将实际 class 恢复为 `CloudflareInterceptor`，repo 源码调用通过 typealias 保留，`javap DesktopNetworkHelper -c` 证明 production helper 字节码实际 `new CloudflareInterceptor`，不是测试或 alias 假象。挑战恢复策略 `57/57/0`，真实 Mangalix 越过原 Cloudflare 错误并推进到独立的 default-client compression 兼容性检查；后者不属于本 Task，也未被修改。独立审查确认 challenge/cookie provenance/recovery 无语义变化；旧 binary class 不再生成，但全 tree 无 FQCN/反射/配置/持久化消费者，类型位于不发布的 application 模块而非 source-api，故无受支持 ABI 回退。Java0、diff check通过；root Spotless 仍仅被提交外既有 `GlobalSearchSourcePolicyTest.kt` 阻塞。

##### Task 7C3b0: Mangalix fixture authority quarantine

- Risk axis: `mangalix-fixture-authority`
- Platform boundary: `verification`
- Estimated scope: `4 files, 120 lines plus removal of one untracked 84,906-byte APK`
- Verification: Mangalix 1.6.1 及其全部源码版本从首次提交起即继承 ext-lib 1.6 `KeiSource`，在进入 JsonReader parser 前强制默认 client 存在 exact `CloudflareInterceptor` 且不存在 `IgnoreGzipInterceptor`/`BrotliInterceptor`；fixed `main@6fbf6dfc` 的 `NetworkHelper` 明确保留后两者，因此该 APK 不可能作为 fixed-main production wiring 证据。移除本轮尚未提交的 APK、provenance 与 page-list test，不修改 Desktop client、JsonReader shim 或 inventory；`android.util.JsonReader` 继续保持 unverified，不创建 JsonToken/SystemClock 伪证据。扫描现有 Comix 1.4.34、MangaDex 1.4.211、ManhuaRM 1.4.76、ManHuaGui 1.4.28、FavComic 1.4.1、TCBScans 1.4.12 的 APK/转换 JAR 后，若仍无可执行 JsonReader 引用，则未来只有获得 fixed-main-compatible、可追溯且能经 production loader 执行确定输入的真实 artifact 才能另开实现 Task。

  Evidence: commit `00deccd5e`。Mangalix 源码历史只有首次加入与一次更新，全部使用 ext-lib 1.6 client-shape gate；artifact 仓库没有更早 1.6.0 APK。固定 main 的 `NetworkHelper` 与该 gate 在两个 network interceptors 上直接冲突。对现有六个可追溯 1.4 APK/转换 JAR逐项执行 `jdeps -verbose:class` 与定点 `javap -c -p`，JsonReader/JsonToken 可执行引用为 0；只有其中四个有 SystemClock 静态依赖，未借此升级行为证据。未提交的 84,906-byte APK、provenance 与 page-list test 已从工作树移除，status 无 Mangalix 产物；production、inventory 与 compat evidence 零修改，`android.util.JsonReader` 保持 unverified。历史 blobless pickaxe 首次超时后即停止，没有以扩大网络搜索替代证据。独立复核源码历史、真实 1.6.1 字节码、fixed-main client、六个 1.4 JAR 与现场 status/guard 后 APPROVED；范围 1 plan file/22 touched。

##### Task 7C3b1: ManHuaGui 真实 SystemClock rate-limit ABI

- Risk axis: `real-system-clock-rate-limit`
- Platform boundary: `desktop`
- Estimated scope: `5 files, 300 lines`
- Verification: 复用已固定的 Keiyoushi ManHuaGui 1.4.28 APK/provenance 与 production DI/converter/loader，取得真实 HttpSource 后直接通过该 source 的实际 client 向 MockWebServer 发起请求；network rewrite 只改写目标 host，保留扩展自身 rate-limit interceptor。RED 必须精确失败于 `android.os.SystemClock.elapsedRealtime()J`，GREEN 只新增 exact static ABI，以 `System.nanoTime()/1_000_000` 提供单调毫秒值，并证明真实 source client 请求到达 MockWebServer。成功后只将 `android.os.SystemClock` 加入 inventory 并以同一 immutable APK/test解析为 required；不得把该证据扩张到 JsonReader、JsonToken、Uri、WebKit 或未执行的 parser。contract 同步真实 public surface 计数；测试必须 `@Isolated`、保存并在最外层 finally 恢复 Injekt、关闭 classloader/DI/HTTP 资源。

  Evidence: implementation `2e6787336`、test-isolation repair `be7c74683`。首轮 network-interceptor 改 host 的测试装配只得到 ConnectException，未冒充 ABI 证据；改为保留 ManHuaGui target host、以限定 DNS 路由到 MockWebServer 后，真实 immutable APK 经 production DI/converter/loader 的 `f0.intercept` 精确 RED 为 `NoClassDefFoundError: android/os/SystemClock`，堆栈同时保留 production IgnoreGzip/Compression/Desktop credential。GREEN 新增 `SystemClock.elapsedRealtime()`，`javap -s -p` 为 `public static final ... descriptor: ()J`，实现 `System.nanoTime()/1_000_000L`；真实 source client 到达 exact `/codex-rate-limit` 并返回 200/ok。surface 42 files/50 symbols→43/51，inventory 15 required+36 unverified，compat evidence 16 条；只新增 SystemClock required，JsonReader/Uri/WebKit 未升级。focused Real+contract 首次 `2/0/0`。首审唯一 Important 为同类既有 parser 测试未恢复 global Injekt；唯一修复统一 classloader→DI context→最外层 Injekt 恢复，复跑 `2/0/0`，复审 APPROVED。范围 implementation 5 files/112 touched，repair 1 test file/69 touched，Java0；root Spotless 仍仅被提交外既有 `GlobalSearchSourcePolicyTest.kt` 阻塞。

##### Task 7C3c: TCBScans 真实 ApplicationInfo/Log 旧偏好清理

- Risk axis: `tcbscans-legacy-cleanup`
- Platform boundary: `desktop`
- Estimated scope: `8 files, 250 lines plus one 29,544-byte APK`
- Verification: 固定 Keiyoushi TCBScans 1.4.12：artifact repo commit `04bd989e5ff1f9dda0148c0aad6bac0889e03edb`、blob `12ed843aee2449b8b8793857b874efac0cf98957`、SHA-256 `bf5a2bfd907d54c1ab5438f09a3a45693b597fcc27fc914241d9cd3e491ce1d2`、29,544 bytes、ext-lib 1.4、package `eu.kanade.tachiyomi.extension.en.tcbscans`、version 1.4.12、class `eu.kanade.tachiyomi.extension.en.tcbscans.ExtensionGenerated`。测试以临时 user.home 初始化 production DI/preferences/converter/loader，把真实 `source_1435116756378369709/legacy_updateTime_removed` 置 false，并在 `${dataDir}/shared_prefs` 创建匹配的 legacy XML；构造真实 extension 必须删除文件并输出 `D/TCB Scans: Deleting...`。RED 必须依次固定 `ContextWrapper.getApplicationInfo()Landroid/content/pm/ApplicationInfo;` 缺失、`ApplicationInfo.dataDir:Ljava/lang/String;` 非 public field、`Log.d(String,String):Int` 非 static 的真实首 gap。GREEN 只在 Desktop Android adapter 增加 exact API：dataDir 来自 `getFilesDir().parentFile.absolutePath` 的 Desktop app root，`dataDir` 以 `@JvmField` 暴露，Log 的现有 Android 形状方法生成 static descriptors且保留 stderr/0 语义。成功后仅将 ApplicationInfo、Log 以同一 APK/test解析为 required；ContextWrapper 已有唯一 evidence，不追加重复条目，未执行字段/重载不宣称行为已验证。测试必须 `@Isolated`，按 classloader→DI→Injekt/user.home/stderr 顺序恢复；fixture/provenance/test、三个 adapter 文件与 inventory/evidence合计不超过 8 文件，contract只运行不修改。

  Evidence: commit `376095b02`，8 files/211 text touched + 29,544-byte APK。固定 artifact 的 commit/blob/SHA/size/package/version/class 全部复验。真实 production loader 首先返回 empty 且 diagnostics empty，测试仅在该 RED 分支用同一 production classloader 解包 constructor root；依次得到 `NoSuchMethodError getApplicationInfo`、`IllegalAccessError dataDir`、`IncompatibleClassChangeError Expected static Log.d` 三个精确 RED，GREEN 时 production loader 直接 loaded size=1且不走诊断 fallback。真实构造器删除临时 `.mihon/shared_prefs/source_..._updateTime.xml` 并输出 exact `D/TCB Scans: Deleting ...`；全局 flag/user.home/Injekt/stderr与classloader/DI异常路径均恢复。`javap` 三项 descriptors 精确，Log stderr/0语义不变。inventory/contract仍43 files/51 symbols，仅ApplicationInfo与Log升级required，ContextWrapper evidence不重复。fresh Real `1/0/0`、AndroidCompat `14/0/0`、contract `2/0/0`，独立审查 APPROVED；Java0，root Spotless仍仅被提交外既有`GlobalSearchSourcePolicyTest.kt`阻塞。

##### Task 7C3d: FavComic 真实 Base64 图片解密证据

- Risk axis: `favcomic-base64-evidence`
- Platform boundary: `verification`
- Estimated scope: `5 files, 240 lines plus one 61,951-byte APK`
- Verification: 固定 Keiyoushi FavComic 1.4.1：artifact repo commit `7d5052fb895d086ae2ec6e3cca861146ee3ea0ec`、APK blob `a3937a5f16f2a6c7c1f58d4bddff1e28695ed4a9`、SHA-256 `bc6f5d1b01e62baeeba44f4e7b259eb33b1c76ad261e403ad512b3ff73fe67ab`、61,951 bytes、ext-lib 1.4、package `eu.kanade.tachiyomi.extension.zh.favcomic`、version 1.4.1、class `eu.kanade.tachiyomi.extension.zh.favcomic.ExtensionGenerated`。这是 direct verification，不虚构产品行为 RED：缺少本地 fixture/provenance/test/evidence 即门禁未闭合。真实 production converter/loader 构造 ExtensionGenerated 时必须执行 ImageDecryptInterceptor 的 `Base64.decode(Ljava/lang/String;I)[B`、DEFAULT=0，解出 exact key；随后 loaded HttpSource.client 使用带 `#true` fragment 的 URL，MockWebServer 实际接收 `/cover.jpg`，以 IV `000102030405060708090a0b0c0d0e0f` + cipher `ba7209b41d2d82d8e0fa995afb9b5f0f8c7f832ccb4fe225aa424c90a2c222f6` 经 APK 自身 interceptor 解出明文 `89504e470d0a1a0a466176436f6d6963`。真实 APK 以包含 fragment 的完整 URL 判断后缀，因此 Content-Type 应精确为 `application/octet-stream`，不得改 Desktop 或 fixture 迎合原先错误的 `image/jpeg` 预期。只将 inventory 的 `android.util.Base64` 解析为 required并绑定该 APK@SHA/test；不修改 Base64 production，不借此声称未执行的 flags、offset overload、encoder wrapping或宽容 decoder 已验证。测试 `@Isolated`，保存/恢复 Injekt并关闭classloader/DI/server；contract只运行不修改。

  Evidence: implementation `eb1271d8f`、loader-cleanup repair `40484b4fa`，5 files/185 text touched + 61,951-byte APK，repair 1 test file/4 touched。fixture commit/blob/SHA/size/package/version/class 全部复验；direct verification 未虚构产品 RED。真实 production converter/loader 构造 FavComic 时执行 `Base64.decode(String, DEFAULT=0)`，loaded HttpSource.client 以 `#true` 触发 APK 自身 interceptor；server exact `/cover.jpg`，固定 IV/cipher 解出 exact PNG 明文，真实 MIME 为 `application/octet-stream`。最初计划误写 image/jpeg，在写测试前基于真实 APK/OkHttp URL语义暂停并纠正，未修改产品迎合计划。inventory 18 required+33 unverified，evidence 19/19 unique；仅Base64升级required，production与未执行flags/overloads零修改。Real `1/0/0`、contract与AndroidCompat通过。首审唯一 Important 为loaded后的断言位于classloader finally外；唯一修复把全部loader/HTTP/AES断言纳入try，清理顺序classloader→DI→server→Injekt，复审 APPROVED。Java0；root Spotless仍仅被提交外既有`GlobalSearchSourcePolicyTest.kt`阻塞。

##### Task 7C3e: Comix 真实 Bitmap/Canvas 图片解扰语义

- Risk axis: `extension-graphics-semantics`
- Platform boundary: `desktop`
- Estimated scope: `5 files, 400 lines per behavior batch`
- Split waiver: 真实 XOR/grid 像素 TDD 的三个 production adapter、真实 fixture test 与旧假成功测试修正约 391 touched lines；inventory/evidence/contract 账本另需约 50 行。继续压缩会牺牲像素断言和异常清理可读性，因此按 7C3e1 行为与 7C3e2 证据两个独立审查批次实施，每批仍不超过 8 files/400 lines。
- Verification: 复用 immutable Comix 1.4.34 APK/provenance，经 production DI/converter/loader取得真实 HttpSource.client；MockWebServer 返回固定 scrambled PNG/JPEG bytes 与 APK 实际识别的 `x-enc-*`/`x-scramble-*` headers，由 extension 自身 Descrambler interceptor 执行，禁止直接调用 shim 或在测试复制扩展算法。RED 必须证明当前 BitmapFactory 1×1占位/缺 `Bitmap.CompressFormat`、`Bitmap.compress`、Canvas/Paint/Rect 等会产生错误尺寸、LinkageError或错误像素；GREEN 只实现该真实链使用的 Android graphics ABI，以 Skia adapter 支持真实 decode、width/height/config、createBitmap、Rect、Canvas 两种 drawBitmap、Paint token、JPEG/PNG/WebP compress 与 recycle。测试用固定编码输入和独立 ImageIO oracle断言输出尺寸与各目标色块（JPEG允许明确容差），不得以“未抛异常”代替像素行为。Bitmap、BitmapFactory以及新增 top-level Canvas/Paint/Rect 只有真实 XOR/grid路径成功后才标 required；同步 inventory/evidence与 public surface contract真实计数，不升级 Color/Drawable/Html/WebKit。复跑 RealExtensionPreferenceCompatTest，确保无WebView引擎仍不影响Comix设置。文件限定 Bitmap.kt、BitmapFactory.kt、一个Canvas/Paint/Rect文件、真实Comix descrambler测试、固定文本内嵌或单一图片fixture、inventory、evidence、CompatEvidenceContractTest；若使用独立图片文件则总文件仍不得超过8。

###### Task 7C3e1: Comix graphics production behavior

- Risk axis: `comix-graphics-behavior`
- Platform boundary: `desktop`
- Estimated scope: `5 files, 400 lines`
- Verification: 只修改 Bitmap.kt、BitmapFactory.kt、CanvasCompat.kt、RealExtensionComixDescramblerCompatTest.kt 与 AndroidStubsPhase27Test.kt；固定图片以 base64 内嵌测试，释放文件名额。XOR-only 与 XOR+grid 必须是两次独立真实 source.client 请求，分别断言 decode/compress 与 Canvas/Rect/Paint tile mapping；旧随机无效字节返回1×1的自证测试改为 Android null 语义。该批次必须取得完整像素 GREEN、相关 settings/compat 回归与独立审查，但不得修改 inventory/evidence/contract或宣称 required。

  Evidence: behavior commit `467a0c514`，5 files/395 touched。真实 immutable Comix source.client 的 XOR-only 与 XOR+grid 两次独立请求分别从缺 `Bitmap$CompressFormat`、缺 `Canvas` 精确 RED，GREEN 后均输出 50×50 JPEG；独立 ImageIO oracle 对 25 个目标色块逐块断言，固定 scrambled fixture SHA-256 为 `b5454afef4dbd1ceac4fbea18ce791275b386aa60450b14d0bc08b4fb16ceae0`。首审确认真实链/ABI/oracle有效，但因 Skia 生命周期与 Android 参数语义未闭合转入 7C3e1r，未提前更新 ledger。

###### Task 7C3e1r: Skia lifecycle 与 Android 参数语义修复

- Risk axis: `skia-lifecycle-parameter-semantics`
- Platform boundary: `desktop`
- Estimated scope: `4 files, 180 lines`
- Verification: 这是 7C3e1 的唯一修复轮，只修改 Bitmap.kt、BitmapFactory.kt、CanvasCompat.kt、AndroidStubsPhase27Test.kt。所有临时 Skia Canvas 必须确定性关闭；decode/allocate/scale 任意异常路径关闭已分配 native Bitmap，Canvas 每次draw重新通过目标 Bitmap 的 recycle guard取得native，禁止use-after-close。`decodeByteArray` 的负offset/length或尾部越界抛 `ArrayIndexOutOfBoundsException`；`Bitmap.compress` quality不在0..100抛 `IllegalArgumentException`；`createScaledBitmap` 必须按filter选择nearest与linear采样，并由真实像素测试区分。复跑7C3e1两条真实Comix链和相关adapter测试；仍不得更新ledger。

  Evidence: commit `9805c5bc4`，4 files/167 touched。RED 为 adapter `19 tests/4 failed`，分别固定非法 range 异常、quality 越界、recycle 后绘制与 filter 无效；GREEN 为 adapter、真实 Comix 解扰、真实设置与 WebView verifier 合计 `23/0/0`。所有临时 Skia Canvas 与异常路径 native Bitmap 已确定性关闭，nearest/linear采样已由像素测试区分。唯一复审确认这些原问题闭合，但发现构造已回收目标的检查被延迟到 draw；按单 Task 修复/复审上限停止，并重规划为 7C3e1s。

###### Task 7C3e1s: Canvas recycled-target construction contract

- Risk axis: `canvas-recycled-construction`
- Platform boundary: `desktop`
- Estimated scope: `2 files, 50 lines`
- Verification: 7C3e1r 的唯一复审发现，为避免长期持有 Skia Canvas 而把 recycle guard 全部推迟到 draw，导致 `Canvas(recycledBitmap)` 未按 Android 在构造阶段立即失败；原修复轮到此停止并重规划为本独立 Task。只修改 CanvasCompat.kt 与 AndroidStubsPhase27Test.kt：构造时调用目标 Bitmap 的 guard 但不持有 native/Skia Canvas，draw 时仍再次 guard；测试同时覆盖“先 recycle 后构造立即失败”和“构造后 recycle 再 draw 失败”，并复跑真实 Comix XOR/grid 链。不得修改 ledger 或其他 graphics 语义。

  Evidence: commit `55432f2dc`，2 files/13 touched。新增测试先以 `20 tests/1 failed` 精确证明 pre-recycled target 构造未立即失败；GREEN 只在构造时执行一次 target guard，不持有 native/Skia Canvas，draw 仍逐次 guard。adapter `20/0/0`、真实 Comix `1/0/0`；`javap` 确认唯一实例字段仍为 Bitmap target、构造与两个 draw descriptors 不变，独立 review APPROVED，Java0。

###### Task 7C3e2: Comix graphics evidence ledger

- Risk axis: `comix-graphics-evidence`
- Platform boundary: `verification`
- Estimated scope: `3 files, 80 lines`
- Verification: 只在 7C3e1 独立审查通过后，修改 compat-inventory、compat-evidence 与 CompatEvidenceContractTest；scanner 真实 surface 应为 44 files/54 symbols，Bitmap、BitmapFactory、Canvas、Paint、Rect分别以 immutable Comix APK@SHA与同一真实像素测试解析为 required。不得改 production/test行为，不得升级Color/Drawable/Html/WebKit。

  Evidence: commit `75107d5c7`，3 files/53 touched。scanner 依次以 `expected 43 files, was 44` 与 `expected 51 symbols, was 54` 取得真实 RED；GREEN 为 44 files/54 unique symbols，inventory集合完全一致、evidence 24/24 unique。仅 Bitmap、BitmapFactory、Canvas、Paint、Rect各以 immutable Comix 1.4.34 APK SHA-256 `5d46a6ef98c1ac4f2ab22a29347748a36eb32b6995fb8a08e092446424e366d8` 与 `RealExtensionComixDescramblerCompatTest` 解析为 required；Color/Drawable/Html/Uri/WebKit 保持 unverified。contract `2/0/0`、真实 Comix `1/0/0`，独立 review APPROVED，Java0。

##### Task 7C3f0: Compat product baseline 动态证据契约修复

- Risk axis: `compat-baseline-evidence-drift`
- Platform boundary: `verification`
- Estimated scope: `1 file, 60 lines`
- Verification: 7C3f 首次删除后的 31-test fixture 集合暴露 `DesktopExtensionProductBaselineTest` 仍把早期 ManHuaGui 两符号证据固定为整个 resolved symbol 集合；恢复全部裁剪后，在 HEAD `1ccb2518a` 单跑仍稳定为 `5 tests/1 failed`，期望2项而当前真实 ledger 为24项，证明与裁剪无关。只修改该测试文件：移除 `RESOLVED_SYMBOLS`、单一 `REAL_FIXTURE`/`REAL_FIXTURE_TEST` 的重复全集假设，保留 evidence 非空、symbol唯一、schema/status、仓库内本地 artifact 与 protection test 存在检查；动态 surface/inventory/evidence一一对应继续由 `CompatEvidenceContractTest` 唯一负责，不在此复制实现。运行 product baseline、compat contract 与 immutable fixture集合；不得修改 JSON ledger 或 production。

  Evidence: commit `ab7099df0`，1 file/13 deletions。恢复后的 HEAD 先单跑稳定复现 product baseline `5 tests/1 failed`，精确证明旧固定2-symbol集合与当前24项真实evidence冲突且与裁剪无关；GREEN 删除重复全集/单一fixture假设，但保留 evidence 非空、symbol唯一、schema/status、artifact-path@digest、仓库内本地artifact/protection test存在与removalCondition门禁。product baseline `5/0/0`、compat contract与6个immutable fixture `10/0/0`，独立审查确认动态surface/inventory/evidence一致性仍只由contract负责，APPROVED、Java0。

##### Task 7C3f: AsyncTask/JsonWriter 无消费者 compat prune

- Risk axis: `unused-compat-prune`
- Platform boundary: `desktop`
- Estimated scope: `5 files, 300 lines`
- Verification: 本项目的支持边界是当前可追溯、fixed-main-compatible 1.4 artifact集合，不承诺任意历史 APK。fixed main/current production/source-api、本地 Keiyoushi全源码 `900d108c`、六个已下载raw JAR与七个去重APK descriptor 对 `android.os.AsyncTask`、`android.util.JsonWriter` 的消费者均为0；两者只来自批量补桩提交和 `AndroidStubsPhase27Test` 自证。该direct prune不虚构产品行为RED：删除两个实现、对应stub自测与inventory条目，更新public surface contract计数；运行剩余Phase27、全部immutable fixture production converter/loader tests、contract和clean compile。若删除导致任何真实fixture或production编译失败则回滚本批并恢复unverified，不用新增shim绕过。该结论不自动扩张到Handler/Looper/Intent/Bundle/Uri，也不声称兼容任意历史扩展。

  Evidence: commit `63431fceec`，5 files/263 touched（2 additions/261 deletions）。首次删除后31-test集合唯一失败为7C3f0已证明的陈旧基线，按规则完整恢复、修复并审查该独立问题后重新施工。最终 clean `compileKotlinJvm`/`compileTestKotlinJvm` BUILD SUCCESSFUL（4m30s），Phase27、contract、product baseline与6个真实fixture共 `31/31`。全仓库production/source-api/test源码与4个全部已跟踪immutable APK的DEX descriptor、slash/dotted反射字符串均无AsyncTask/JsonWriter消费者；inventory 52/52 unique、scanner 42 files/52 symbols集合完全一致，evidence 24/24 unique且零改。Handler/Looper/Intent/Bundle/Uri保持存在且diff为0，独立review APPROVED，Java0。

##### Task 7C3g: JsonReader 无有效消费者 compat prune

- Risk axis: `json-reader-prune`
- Platform boundary: `desktop`
- Estimated scope: `4 files, 270 lines`
- Verification: fixed main production、current production/source-api、4个已跟踪immutable APK与当前有效1.4 fixture集合均无 `android.util.JsonReader` 消费者；唯一命中是已隔离的 Mangalix 1.6.1，它超出 fixed-main ext-lib 1.5支持上限，且字节码要求当前不存在的 top-level `android.util.JsonToken`，不能作为兼容证据。direct prune 删除 JsonReader.kt、Phase27中对应自证/import、inventory条目并把contract真实surface从42 files/52 symbols更新为41/51；运行剩余Phase27、contract、全部immutable fixture与clean compile。若有效fixture或production compile因删除失败则恢复本批，不新增shim。

  Evidence: commit `d23d782761`，4 files/246 touched（2 additions/244 deletions）。删除JsonReader实现、Phase27两项自证/import与inventory项，surface 41 files/51 symbols；evidence、JsonToken、Mangalix与其他shim零改。fresh clean compile成功（一次10分钟外层工具超时后Java0，同命令唯一重试1m59s成功），Phase27、contract、product baseline与6个真实fixture共 `29/29`。独立全量archive复核仅Mangalix1.6.1同时含JsonReader/top-level JsonToken，fixed-main的`LIB_VERSION_MAX=1.5`且当前无top-level JsonToken；有效1.4 artifacts均零命中、无dotted反射字符串。独立scanner 41/51、inventory51且set delta=0，review APPROVED、Java0；review自身focused命令仅因120秒工具窗口超时，无失败输出。

##### Task 7C3h: Intent/Bundle dormant Activity compat prune

- Risk axis: `dormant-activity-token-prune`
- Platform boundary: `desktop`
- Estimated scope: `6 files, 250 lines`
- Verification: fixed main 的 Intent/Bundle 属于 Activity、broadcast 与 saved-state 平台链；有效扩展中的命中只在随包 `UrlActivity`，Desktop没有Activity/manifest dispatch且production loader不加载该类。direct prune 删除 Intent.kt、Bundle.kt、Phase6/Phase2自证、两项inventory并把surface从41/51更新为39/49；全部immutable loader/converter/product tests与clean compile必须通过。若真实Desktop产品链执行该Activity token则恢复并重规划平台入口，不得以空实现保留。

  Evidence: commit `57a829f6f5`，6 files/197 touched（4 additions/193 deletions）。删除Intent/Bundle、Phase6/Phase2仅自证/import与两项inventory，surface 39 files/49 symbols；evidence、Activity与其他shim零改。clean compile成功（2m36s），Phase2、Phase6、contract、product baseline与6个真实fixture共 `38/38`。全部raw JAR命中均精确归属 `keiyoushi/source/UrlActivity.class`，有效源码亦仅该共享Activity导入；Desktop ManifestClassExtractor只读`tachiyomi.extension.class`，loader只加载声明的ExtensionGenerated/SourceFactory，不解析或dispatch manifest Activity。独立scanner 39/49、inventory49且set delta=0，review APPROVED、Java0。

##### Task 7C3i: ComponentCallbacks 生命周期占位 prune

- Risk axis: `component-callbacks-prune`
- Platform boundary: `desktop`
- Estimated scope: `5 files, 120 lines`
- Verification: ComponentCallbacks/ComponentCallbacks2没有fixed-main显式消费者、Keiyoushi源码或有效artifact调用；Desktop仅由Application shim自身实现/派发，真实ManHuaGui加载只发生类型链接而未注册或执行回调。删除接口文件，简化Application继承/注册占位，移除Phase1自证与两项inventory，contract从39/49更新为38/47；真实ManHuaGui Application loader、全部immutable fixture和clean compile必须通过，否则恢复。

  Evidence: commit `e13339c1be`，5 files/107 touched（4 additions/103 deletions）。删除两个接口、Application内部占位回调链、Phase1四项自证/import与两项inventory；Context/ContextWrapper/Application真实evidence零改，surface 38 files/47 symbols。clean compile成功（3m18s），Phase1/7、contract、baseline与6个真实fixture共 `34/34`，ManHuaGui production loader通过。独立 `javap` 确认 Application 仍继承ContextWrapper并保留onCreate/onTerminate/attach；fixed-main/current/Keiyoushi及22个本地真实/原始APK/JAR均零消费者。独立scanner/inventory 38/47、set delta与duplicates均0，review APPROVED、Java0。

##### Task 7C3j: PackageInfo/PackageManager Android系统占位 prune

- Risk axis: `package-manager-prune`
- Platform boundary: `desktop`
- Estimated scope: `7 files, 150 lines`
- Verification: fixed main 对两类型的调用属于Android包发现、签名、权限、WebView、图标与安装安全；Desktop只有 `AndroidCompat.packageManager` 持有无方法调用的占位实例，有效扩展源码/DEX/反射均无消费者。删除两个shim与该占位属性，清理Phase6/Phase7自证、inventory并把surface从38/47更新为36/45；运行全部immutable loader/converter、DI/AndroidCompat相关测试与clean compile。任何production调用失败即恢复并重规划真实平台adapter。

  Evidence: commit `e1660f40fd`，7 files/94 touched（3 additions/91 deletions）。删除两个空包管理shim、AndroidCompat占位、Phase6/7对应自证/import与两项inventory；ApplicationInfo、Context/preferences/files/DI、TCBScans evidence零改，surface 36 files/45 symbols。clean compile成功（3m15s），Phase6/7、AndroidCompat、Desktop DI、contract、baseline与6个真实fixture共 `47/47`。独立复核fixed-main引用均为Android系统安装包/签名/权限/Activity/WebView平台链；7个真实raw JAR对PackageManager/PackageInfo/getPackageInfo均零命中，TCBScans只消费保留的ContextWrapper.getApplicationInfo→ApplicationInfo.dataDir。独立scanner/inventory 36/45且集合差/重复0，review APPROVED、Java0。

##### Task 7C3k: Environment/TextUtils/Pair 无消费者 prune

- Risk axis: `utility-token-prune`
- Platform boundary: `desktop`
- Estimated scope: `7 files, 200 lines`
- Verification: 三类型在fixed-main/current production/source-api、完整Keiyoushi源码及有效artifact descriptor/反射字符串中均无Desktop扩展消费者；fixed-main Environment仅服务Android外部存储，Desktop应使用自身文件系统，仓库其他 `Pair` 均为Kotlin Pair。删除三个shim、Phase2/Phase3自证、inventory并把surface从36/45更新为33/42；运行全部immutable fixture、文件工具回归、contract与clean compile，明确证明Kotlin Pair未受影响。

  Evidence: commit `1e85642e2c`，7 files/186 touched（4 additions/182 deletions）。删除三个shim、Phase2/3仅自证/import与三项inventory，surface 33 files/42 symbols；evidence、DesktopPlatformPaths/DirectoryOpener与Kotlin Pair生产用法零改。clean compile成功（2m1s），Phase2/3、contract、baseline、6个真实fixture、Desktop路径/目录工具、loader/converter共 `48/48`。独立复核fixed-main Environment只用于Android外部存储，7个真实raw JAR对三个Android owner/dotted名均零命中，同时6个JAR明确含kotlin/Pair。独立scanner/inventory 33/42且集合差/重复0，review APPROVED、Java0。

##### Task 7C3l: PreferenceManager 未消费属性 prune

- Risk axis: `preference-manager-prune`
- Platform boundary: `desktop`
- Estimated scope: `3 files, 50 lines`
- Verification: fixed main PreferenceManager只提供Android默认SharedPreferences；Desktop `PreferenceScreen.preferenceManager` 仅自动构造但Keiyoushi和真实偏好fixture均不读取，产品设置已使用Desktop preference bridge/store。删除该公开类型/属性与inventory条目，surface保持33 files但symbols从42降为41；真实Comix/ManHuaGui preference fixture、contract与clean compile必须通过，否则恢复。

  Evidence: commit `4191993d71`，3 files/17 touched（2 additions/15 deletions）。只删除PreferenceManager类型/自动属性与inventory项，surface 33 files/41 symbols；PreferenceScreen继承/构造/容器方法、其他preference、evidence与production adapter零改。clean compile成功（2m10s），Phase5、loader ABI、contract、baseline与6个真实fixture共 `39/39`，Comix production preference无NoSuchMethod/Field。独立复核7个raw JAR对manager owner/dotted/getter/field/createPreferenceScreen均0，而5个JAR真实消费保留的PreferenceScreen/addPreference；scanner/inventory33/41且集合差/重复0，review APPROVED、Java0。

##### Task 7C3m: Drawable verification-only family prune

- Risk axis: `drawable-family-prune`
- Platform boundary: `desktop`
- Estimated scope: `4 files, 80 lines`
- Verification: Drawable、BitmapDrawable、ColorDrawable在fixed main仅服务Android reader/resources/icon链，Desktop loader/UI与完整Keiyoushi/有效artifact均无消费者；当前单文件三个类型仅由Phase27自证。删除该文件与自证、三项inventory，contract从33/41更新为32/38；运行Comix graphics、全部immutable loader/product fixture、contract与clean compile。不得把该结论扩张到已有真实消费者但尚缺fixture的 Color/Html。

  Evidence: commit `54ba9045e7`，4 files/48 touched（2 additions/46 deletions）。删除单文件三个drawable类型、Phase27三项自证与三项inventory，surface 32 files/38 symbols；Bitmap/Factory/Canvas/Paint/Rect/Color、graphics evidence与Desktop图标链零改。clean compile成功（1m54s），graphics adapter/ABI、contract、baseline、6个真实fixture、loader、IconLoading与UI Presentation共 `48/48`。独立复核fixed-main引用只在Android Reader/resources/icon平台链，7个真实raw JAR对三owner/dotted名均0；Desktop图标继续ByteArray+Compose/Coil，真实binary仍命中保留的Bitmap/Canvas。独立scanner/inventory32/38且集合差/重复0，review APPROVED、Java0。

##### Task 7C3n: Comix WebView 平台边界真实证据

- Risk axis: `real-webview-platform-boundary`
- Platform boundary: `verification`
- Estimated scope: `4 files, 260 lines`
- Verification: 复用immutable Comix 1.4.34 APK/provenance，经production converter/loader取得真实 source，并反射调用其父类私有 `p0.P(Document,String,Function1):String` 最短runInWebView入口；固定Document base URI为 `https://example.invalid/comix-webview`，禁止HTTP。真实顺序必须执行 `Looper.getMainLooper`、`Handler`构造/post、`WebResourceResponse(String,String,InputStream)`，再在DesktopHandler线程构造 `WebView(Application)`并由扩展包装为 `Exception("Failed to start WebView (url=...)")`，cause为 `UnsupportedOperationException("Desktop WebView engine unavailable")`。先在contract写RED要求Handler/Looper/WebResourceResponse=required、WebView=unsupported且各有唯一同fixture/test evidence；GREEN新增真实测试并只更新inventory/evidence，surface仍32/38，不改production。不得把仅父类链接的View/ViewGroup，或未执行的CookieManager/ValueCallback/WebResourceRequest/WebSettings/WebViewClient标resolved。测试必须@Timeout(10s)、关闭classloader/DI并恢复Injekt，复跑Comix preference/graphics链，Java0。

  Scope correction: 首次GREEN真实调用连续三次触及10秒timeout；临时诊断证明 `new WebView` 的UOE位于Comix `p0.b`异常保护区之前，又被Desktop Handler的Future吞掉，导致extension semaphore等待120秒。原“production零改即可得到包装异常”假设不成立，原批停止并按7C3n0行为与7C3n1账本拆分；临时线程/反射诊断不得提交。

  Converter root correction: n0把fail-fast移到getSettings后仍超时；production converted `p0.b` 没有任何Exception table。根因不是BytecodeEditor主动删除，而是ApkToJarConverter误用dex2jar `.skipExceptions(true)`：该API设置 `DexFileReader.SKIP_EXCEPTION (0x100)`，会在raw JAR生成时跳过DEX异常处理表，现有注释却误写为“不要因单类转换错误中止”。先执行7C3n00恢复converter异常表，再继续n0；不得用Handler或测试绕过。

###### Task 7C3n00: Dex2jar exception table preservation

- Risk axis: `dex2jar-exception-table-preservation`
- Platform boundary: `desktop`
- Estimated scope: `2 files, 180 lines`
- Verification: 在ApkToJarConverterTest中用immutable Comix APK经production converter生成JAR，ASM读取 `p0.b` 并取得当前0个try/catch block的精确RED；同一固定字节码预期8个handler。GREEN只移除ApkToJarConverter中错误的 `.skipExceptions(true)` 及误导注释，让dex2jar保留DEX异常表，BytecodeEditor仍负责frames/既有精确调用修复。断言production输出 `p0.b` 恢复8个try/catch blocks且类可加载，复跑全部converter、BytecodeEditor、真实Page/Comix/ManHuaGui fixture；不得修改WebView或ledger。

  Evidence: commit `ec15976f65`，2 files/63 touched（62 additions/1 deletion）。真实tracked Comix APK经production converter的 `p0.b` handler从RED `expected 8/actual 0` 恢复为8，ExtensionClassLoader可加载并解析精确descriptor；测试不依赖untracked raw JAR。生产只删除 `.skipExceptions(true)`，BytecodeEditor零改。独立反编译dex2jar2.4.28确认true会设置readerConfig `0x100`=`DexFileReader.SKIP_EXCEPTION`且跳过findTryCatch，并非容忍单类错误。converter/editor、PageList、Comix graphics、ManHuaGui真实fixture通过，独立review APPROVED；n0/ledger未混入，Java0。

###### Task 7C3n0: WebView 构造 shell 与真实引擎操作 fail-fast

- Risk axis: `webview-constructor-fail-fast-placement`
- Platform boundary: `desktop`
- Estimated scope: `3 files, 230 lines`
- Verification: RED复用真实Comix `p0.P`链，证明当前WebView构造UOE被Handler Future吞掉并触及10秒timeout。GREEN只修改WebViewCompat.kt：构造器允许创建ABI shell且不声称浏览器可用，`getSettings`及其余所有真实引擎方法继续抛 `UnsupportedOperationException("Desktop WebView engine unavailable")`；不得改变Handler全局调度。Comix字节码在构造后进入异常保护区，故getSettings UOE必须被extension写入errorRef/release semaphore并快速包装为指定Exception/cause。更新AndroidWebViewVerifierAbiTest，断言构造可完成、首次engine方法fail-fast；真实测试、ABI、Comix preference/graphics通过。不得修改inventory/evidence/contract。

  Evidence: commit `5879b1a836`，3 files/151 touched（137 additions/14 deletions）。在7C3n00恢复exception handlers后，真实Comix `p0.P`链由原三次10秒timeout变为1.84秒内返回exact `Exception("Failed to start WebView...")`，cause为exact Desktop engine UOE；bridge与稳定栈均断言。WebView ctor仅成为ABI shell，getSettings及全部engine方法仍fail-fast，private helper未扩大ABI；View layout三方法零改，其UOE只由extension原runCatching吞下。ABI、真实P、Comix preference/graphics、converter全绿，ledger零diff，独立review APPROVED、Java0。

###### Task 7C3n1: Comix WebView 平台边界 evidence ledger

- Risk axis: `webview-boundary-evidence-ledger`
- Platform boundary: `verification`
- Estimated scope: `3 files, 100 lines`
- Verification: 仅在7C3n0独立审查通过后，修改CompatEvidenceContractTest、inventory与evidence：Handler/Looper/WebResourceResponse=required，WebView=unsupported，各自唯一绑定同一immutable Comix真实测试；surface保持32/38。View/ViewGroup与未执行的CookieManager/ValueCallback/WebResourceRequest/WebSettings/WebViewClient仍unverified。

  Evidence: commit `33eedb52f6`，3 ledger files/71 touched（63 additions/8 deletions）。contract RED精确为Handler expected required/actual unverified；GREEN把真实执行成功的Handler/Looper/WebResourceResponse标required，把明确Desktop engine边界的WebView标unsupported，各自唯一绑定tracked Comix APK SHA与RealExtensionWebViewUnsupportedCompatTest，并反向约束该test恰好只覆盖四项。surface仍32 files/38 symbols；View/ViewGroup与CookieManager/ValueCallback/WebResourceRequest/WebSettings/WebViewClient保持unverified。contract、真实WebView、Comix preference/graphics通过，独立review APPROVED、Java0。

##### Task 7C3o0: MangaDex fixture authority freeze

- Risk axis: `mangadex-fixture-authority`
- Platform boundary: `verification`
- Estimated scope: `3 files, 150 lines plus one 111,390-byte APK`
- Verification: 固定 artifact repository snapshot commit `7d5052fb895d086ae2ec6e3cca861146ee3ea0ec`（root tree `35127622c9911a3f7e50c809a71dfc0057843e34`、parent `0dae9cf45bef459a60cefb1f3ad1b4eedea3554b`）、APK blob `2110eaccdbce98e2bf10c827f1136b63c9c35481`、SHA-256 `eff4ee157380f0cd4f19a2150f93220ca7a9bcd4e5d570736f639230ef338236`、111390 bytes、package `eu.kanade.tachiyomi.extension.all.mangadex`、version 1.4.211/ext-lib1.4、entry `ExtensionGenerated`。提交 tracked APK、provenance 与只验证 authority/ref/blob/SHA/size/manifest 的 `MangaDexFixtureProvenanceTest`；不执行 factory、不修改 production 或 compat ledger。本 Task 将二进制权威与后续行为修复分离，避免 fixture、loader、ABI adapter 混在同一提交。

  Evidence: commit `d07c74519`，严格 3 files/85 text lines plus 111,390-byte APK。focused provenance test 1/1 通过；本地与 GitHub 上游独立交叉核对 commit/root tree/parent/blob/raw URL/SHA/size，manifest package/version/ext-lib/entry 全部匹配。测试未调用或宣称 converter、loader、factory 行为；独立 review APPROVED，Java0，用户 DownloadQueue 与其他 dirty 未进入提交。

##### Task 7C3o1a: Desktop SourceFactory 完整展开语义

- Risk axis: `desktop-source-factory-expansion`
- Platform boundary: `desktop`
- Estimated scope: `2 files, 160 lines`
- Verification: 仅在 7C3o0 独立审查通过后继续。synthetic loader test 先 RED 证明 manifest `SourceFactory` 未展开；最小 loader GREEN 必须与 fixed main 的 `obj.createSources()` 语义一致，保持 host SourceFactory ABI parent-first，并返回 factory 完整列表，不得只反射构造某个 source 或在 loader 内提前按语言筛选。覆盖单 Source、SourceFactory、多 manifest class 与无效类隔离，复跑现有 loader suite；本 Task 不引入 MangaDex compat 类型或 ledger 结论。

  Evidence: commit `4444405a7`，严格 2 files/74 touched。RED 为 synthetic factory expected `[2,3]`/actual `[]`；GREEN 与 fixed main 一致地将 Source 包成单项、将 SourceFactory 完整 `createSources()` 结果 flatMap，并保持 manifest/factory 内顺序与逐项失败隔离。SourceFactory host parent-first、单 Source、多 manifest、missing/非 Source/构造失败均受测；loader 13/13 与 ManHuaGui/converter 回归通过。独立 review APPROVED，diff clean、Java0，其他 dirty 未入提交。

##### Task 7C3o1b: MangaDex 61-source 与 preference link closure

- Risk axis: `mangadex-source-factory-link`
- Platform boundary: `desktop`
- Estimated scope: `8 files, 380 lines`
- Verification: 仅在 7C3o1a 独立审查通过后继续。真实 MangaDex 测试必须走 production converter/meta/loader 并断言 61、diagnostics empty、可选中 English。实际 RED 已依次暴露 `EditTextPreference.OnBindEditTextListener`、`android.text.TextWatcher` 与 `android.widget.TextView`；真实 `a4/b4` verifier closure 还精确要求 TextView error getter/setter、EditText.addTextChangedListener、View rootView/findViewById/enabled 与 Button checkcast。最小 GREEN 在一个 android.text compat 文件内提供 Editable/TextWatcher，在 EditText compat 文件内提供 TextView/EditText/Button 精确继承和内存状态，并补齐 View 所需成员及 EditTextPreference listener storage。AndroidX 1.2.1、真实 `j2/l2/a4/b4` descriptor 必须一致，不得用空接口或 `Any`。inventory/evidence 必须明确这里只证明 production loader 所需 verifier closure 与状态模型，不宣称 preference setup、callback 或 Android widget 渲染已完成；contract 同提交更新。文件限定 android.text compat、EditText.kt、View.kt、EditTextPreference.kt、RealExtensionMangaDexFactoryCompatTest.kt、inventory、evidence、contract。

  Evidence: commit `9ead1876e`，严格 8 files/243 text touched。production tracked MangaDex APK → converter/meta/loader 真实返回 61 sources、diagnostics empty 并取得唯一 English MangaDex；RED 依次为 missing OnBindEditTextListener、TextWatcher、TextView，GREEN host descriptor 与真实 j2/l2/a4/b4 字节码一致。surface 34 files/43 symbols，新状态模型仅保存 watcher/error/enabled，ledger 明确不宣称构造、callback 或渲染，EditTextPreference 原 Comix 强证据未降级。focused/contract 与 loader/preference/UI wiring/provenance/全部 RealExtension fixtures 回归通过；独立 review APPROVED，Java0、其他 dirty 未入提交。

##### Task 7C3o1c: APK assets preservation 与 MangaDex preference setup

- Risk axis: `apk-assets-preference-wiring`
- Platform boundary: `desktop`
- Estimated scope: `3 files, 260 lines`
- Verification: 仅在 7C3o1b 独立审查通过后继续。真实 RED 已证明 production converter 输出 JAR 缺少 APK 中存在的 `assets/i18n/messages_*.properties`，使 MangaDex `setupPreferenceScreen` 在 `InputStreamReader` 构造时 NPE。先用 synthetic APK asset test 锁定 converter 必须保留安全的 `assets/` classpath entries，再最小修改 ApkToJarConverter 将原 APK assets 合并进最终 edited JAR，拒绝路径穿越且不得覆盖转换后的 class/meta。真实 MangaDex 测试随后必须通过 `resolveSourcePreferencesState` → `DesktopAndroidPreferenceAdapter` 成功建立 preference Content，并验证原版 i18n 文案可读取；不得直接 classloader 注入测试资源绕过 production converter。文件限定 ApkToJarConverter.kt、ApkToJarConverterTest.kt、RealExtensionMangaDexFactoryCompatTest.kt。

  Evidence: commits `a78ff97c4` + repair `860cf9b6a`。RED1 为最终 JAR 缺 safe asset；GREEN 保留安全 assets 字节并拒绝 traversal/absolute/backslash/class/meta/manifest/signature/DEX，真实 MangaDex production loader 仍为 61 且 preference `Content(12)` 读取 APK 原版 `Cover quality`/`Block groups by UUID`。首轮 review 发现 post-edit asset 冲突会遗留 raw/partial final 并覆盖既有产物；修复 RED 复现两种失败，GREEN 改为同输出目录唯一 workdir，全链成功后才 publish deterministic final，失败清理本轮产物并保留既有 final。唯一修复复审 APPROVED；13 classes/51 tests 0 failure/error，Java0。root Spotless 仍只被范围外既有 `GlobalSearchSourcePolicyTest.kt` 阻塞。

##### Task 7C3o2: MangaDex AppInfo 与 Build.RELEASE 真实 header 链

- Risk axis: `mangadex-build-release-abi`
- Platform boundary: `desktop`
- Estimated scope: `5 files, 220 lines`
- Verification: 仅在 7C3o1b 独立审查通过后继续。真实 61-source production loader 中选 English source，公开 `headers/getHeaders` 链先精确 RED 于缺 `eu.kanade.tachiyomi.AppInfo`；新增 fixed-main ABI 形状的 production `object AppInfo`，只实现实际执行的 `getVersionName() = APP_VERSION`，不虚构 getVersionCode/MIME API。GREEN 断言 User-Agent、Referer、Origin 与 `Extra="Android/9 Tachiyomi/<APP_VERSION> MangaDex/1.4.211 Keiyoushi"`，设置并 finally 恢复 `http.agent`。inventory/evidence 只将 Build 标 required 且限定为 RELEASE，不宣称 SDK_INT；contract 同提交更新。文件限定 AppInfo.kt、RealExtensionBuildCompatTest.kt、inventory、evidence、contract。

  Evidence: commit `991071194`，严格 5 files/144 text touched。RED 为真实 English MangaDex public headers 链 `NoClassDefFoundError: eu.kanade.tachiyomi.AppInfo`；GREEN 通过 tracked APK → production converter/meta/loader 61 → `HttpSource.getHeaders()` 得到精确 UA/Referer/Origin/Extra。Desktop AppInfo 编译 ABI 为 Kotlin object INSTANCE + `getVersionName():String` 且仅返回 APP_VERSION；无 direct source/mock/network，http.agent/Injekt/DI/shared classloader 均 finally 恢复。Build ledger 只绑定 VERSION.RELEASE，不覆盖 SDK_INT，surface 保持 34/43；独立 review APPROVED，focused/contract 与 12 classes/29 tests 回归通过，Java0。

##### Task 7C3o3b1: MangaDex 真实 listener 到 JVM validator

- Risk axis: `mangadex-listener-validator`
- Platform boundary: `shared+desktop`
- Estimated scope: `5 files, 360 lines`
- Verification: RED 由 tracked MangaDex → converter/loader → production preference adapter 证明两个 UUID EditText descriptor 的 validator 当前为空。GREEN 在 Android text shim 中提供无渲染的文本更新/TextWatcher 派发与最小 Editable 值对象，由 AndroidX EditTextPreference 使用已保存的真实 OnBind listener 创建 `(String)->String?` validator；JVM EditTextPreference 只在类体新增可空 validator 属性，不改主构造器，source-api 不依赖 app-desktop 类型。DesktopAndroidPreferenceAdapter 在现有 conversion 后附加 validator。真实测试直接调用该 production validator，覆盖空值、单 UUID、逗号分隔多 UUID 与无效值返回扩展原版错误；不得复制 regex。文件限定 EditText.kt、AndroidX EditTextPreference.kt、JvmPreferenceItems.kt、DesktopAndroidPreferenceAdapter.kt、RealExtensionMangaDexFactoryCompatTest.kt。

  Evidence: commit `c482d5429`，严格 5 files/105 text touched。真实 MangaDex RED 为 blockedGroups validator null；GREEN production adapter 在每次 conversion 新增 slice 中以类型+key 唯一匹配，JVM descriptor 类体 nullable validator 不改 constructor/copy/component 且无 Desktop 依赖。每次验证使用保存的真实 listener + 新 EditText(context)，实际派发 before/on/after；空/单UUID/逗号多UUID/invalid 均走扩展规则，invalid 返回真实 i18n，无 regex 复制。独立 review APPROVED；contract/MangaDex/preference/UI wiring focused 全绿，Java0。

##### Task 7C3o3b2: Compose validator 实时反馈与保存门禁

- Risk axis: `desktop-validator-feedback`
- Platform boundary: `desktop`
- Estimated scope: `2 files, 260 lines`
- Verification: RED 证明 Desktop edit dialog 对无效输入仍可确认并落盘。GREEN 让 EditTextRow 在初值和每次 onValueChange 调用 descriptor validator，OutlinedTextField 显示真实错误，错误存在时禁用确认且不写 preference，有效后恢复确认；无 validator 保持既有行为。UI/state test 使用 sentinel validator 验通用 wiring，不复制 UUID 规则；真实 UUID 语义由 7C3o3b1 的 MangaDex integration test保护。文件限定 SourcePreferencesScreen.kt、ExtensionDetailsPreferencesWiringTest.kt。

  Evidence: commit `f3de0de74`，严格 2 files/111 text touched。RED 为初始 invalid 未显示 feedback；GREEN 打开及每次 SetText 均执行 validator，isError/supportingText 可见，confirm disabled 且 onClick 二次守卫阻止 invalid 落盘，valid 恢复保存，validator=null 保持旧路径。UI test 只用 sentinel，不复制 UUID；新增 UI 4/4、真实 MangaDex 1/1、contract 4/4 通过。独立 review APPROVED；Incognito 2/2 NoSuchElement 经零diff/零调用路径/执行顺序证据判为范围外既有失败（未另切父基线复跑），Java0。

##### Task 7C3o3c: MangaDex validator evidence ledger

- Risk axis: `mangadex-validator-evidence`
- Platform boundary: `verification`
- Estimated scope: `3 files, 100 lines`
- Verification: 仅在 7C3o1b/3b1/3b2 独立审查通过后修改 inventory/evidence/contract；按真实产品链实际执行结果标记 Editable/TextWatcher/TextView/EditText/Button/View 相关边界，区分 required callback semantics 与 unsupported Android widget rendering。不得把仅被 descriptor 链接但未执行的 UI engine 行为写成已支持；反向契约必须唯一绑定 tracked MangaDex fixture 与真实 preference validation test。

  Evidence: commit `8d164048d`，严格 3 ledger files。contract RED 为 Editable 尚无真实执行边界；GREEN 将 MangaDex reverse 固定为 Editable/TextWatcher/View/Button/EditText/TextView 六项，均唯一绑定 tracked SHA+real validator test。Editable/三回调/View root+find/EditText构造/TextView watcher+error均实际执行；Button仅nullable checkcast token，不宣称构造/setEnabled/rendering。AndroidX EditTextPreference 原 Comix 强证据逐字段不变，surface 32/41、状态38/1/2。独立 review APPROVED；contract5/5、MangaDex1/1、Comix preference2/2全绿，Java0。

##### Task 7C3p1: verifier-only CookieManager shim prune

- Risk axis: `cookie-manager-shim-prune`
- Platform boundary: `desktop`
- Estimated scope: `6 files, 240 lines`
- Verification: 本 Task 与 7C3o3b 独立并先行调度。当前 `android.webkit.CookieManager` 只有自证测试与 verifier 反射消费者，既不接 fixed-main `AndroidCookieJar` 的 WebView cookie store，也不接 Desktop production `DesktopCookieJar`；真实 Comix 链在 `WebView.getSettings` 明确平台边界前尚未触达 CookieManager。先删除 production shim 建立 prune RED，确认编译失败只来自 AndroidCompatPhase4/Phase7 与 AndroidWebViewVerifierAbiTest 的 test-only consumer；GREEN 删除/清理这些自证测试、inventory entry 并更新 contract surface。复跑真实 Comix WebView/preference/graphics、全部 immutable fixture、DesktopCookieJar 与 loader；任何 production 或真实 fixture 回归即恢复并重规划，不得将当前独立内存仓标 required。文件限定 CookieManager.kt、AndroidCompatPhase4Test.kt、AndroidCompatPhase7Test.kt、AndroidWebViewVerifierAbiTest.kt、inventory、contract。

  Evidence: commit `3c6edd9cf`，严格 6 files（229 deletions/2 additions）。删除 production shim 后 Desktop production compile 通过，test compile 失败仅来自纯 Cookie Phase4 与 Phase7 自证，另一个命中仅为 WebView verifier 反射；无 production/真实 fixture consumer。GREEN 删除/清理 test-only 依赖，surface 33 files/42 symbols，inventory/evidence 无 CookieManager 或孤儿；Android 原版 AndroidCookieJar 与 DesktopCookieJar 均未改。app 14 suites/37 tests、core DesktopCookieJar 29 tests 全绿；独立 review APPROVED，Java0，root Spotless 仍仅受既有 domain 文件阻塞。

##### Task 7C3p2a: verifier-only ValueCallback prune

- Risk axis: `value-callback-shim-prune`
- Platform boundary: `desktop`
- Estimated scope: `5 files, 180 lines`
- Verification: 原批量 prune probe 已证明同时删除 Uri/WebViewClient/WebResourceRequest 会使真实 Comix production loader 返回 0；精确字节码根因为 `l0 extends WebViewClient`、override descriptor 依赖 WebResourceRequest→Uri，且 `p0` 调用 `WebView.setWebViewClient`，因此三者不得在本 Task 删除。恢复该组后只保留 ValueCallback/evaluateJavascript 删除状态，重新运行 production compile 与真实 Comix/ManHuaGui/PageList；若全绿，清理 AndroidWebViewVerifierAbiTest 对应反射，删除 inventory ValueCallback 并更新 contract surface。文件限定 ValueCallback.kt、WebViewCompat.kt、AndroidWebViewVerifierAbiTest.kt、inventory、contract。

  Evidence: commit `750031f27`，严格 5 files。原批量 probe 中真实 Comix 3 项因删除 WebViewClient/WebResourceRequest/Uri 而 loader=0；恢复该组后只删除 ValueCallback/evaluateJavascript 及自证，production 与真实链全绿。Uri 行为测试、Uri parent-first、generic android.* parent-first、两种 PAGE_URI rewrite 及测试全部保留；surface 32 files/41 symbols，inventory/evidence 无 ValueCallback。BytecodeEditor/PageList/Comix/ManHuaGui/loader/all immutable 共 15 classes/55 tests 全绿；独立 review APPROVED，Java0。

##### Task 7C3p2b: Uri 与 Web callback verifier-token evidence

- Risk axis: `web-callback-verifier-tokens`
- Platform boundary: `desktop`
- Estimated scope: `7 files, 320 lines`
- Verification: 以 immutable Comix production loader 与 RealExtensionWebViewUnsupportedCompatTest 写契约，证明删去 Uri、WebViewClient、WebResourceRequest 或 `setWebViewClient` 任一 exact descriptor 会使真实 source 在到达明确 getSettings UOE 前加载失败。最小化 Uri/WebViewClient/WebResourceRequest 到该真实 verifier closure 所需的 fixed Android 形状，移除无真实证据的 Uri 编解码/Builder 自测及 WebViewClient callback UOE 自证；WebViewClient 的 Android 默认 no-op/null 行为可保留，但不得写成真实 callback 产品证据。保留 BytecodeEditor Page(Uri)→Object rewrite、DesktopExtensionLoader Uri token parent-first 与 generic android.* parent-first。inventory/evidence/contract 将三者绑定到真实 Comix fixture，明确只证明 source verifier token，不宣称 callback、Uri 解析或 WebView engine 行为。文件限定 Uri.kt、WebViewCompat.kt、AndroidCompatTest.kt、AndroidWebViewVerifierAbiTest.kt、inventory、evidence、contract。

  Evidence: commit `3ada81206`，严格 7 files/197 text touched。mutation probe 删除 Uri/WebViewClient/WebResourceRequest/setWebViewClient 时真实 Comix production loader=0；恢复后可稳定到达预期 WebView.getSettings UOE。GREEN 将 Uri 收窄为 fixed Android abstract token（唯一 abstract toString），WebViewClient 三个默认 callback 为 no-op/no-op/null，engine methods 继续 UOE；PAGE_URI rewrite、Uri/generic parent-first 均保留。三项 ledger 仅声明 verifier token，真实 Comix 反向集合为 7 项，surface 32/41。15 classes/52 tests 通过；独立 review APPROVED，Java0。

##### Task 7C3p3a: View hierarchy 与 WebSettings boundary shape

- Risk axis: `view-websettings-boundary-shape`
- Platform boundary: `desktop`
- Estimated scope: `6 files, 300 lines`
- Verification: 先以 fixed Android ABI 写 focused RED：View 由 Context 构造，ViewGroup 为 abstract 且由 Context 构造，WebView 必须将收到的 Context 传给 ViewGroup，LayoutParams width/height 为可变 public fields，TextView/EditText/Button 同样经 Context 构造；WebSettings 为 abstract verifier return type，不可由自证测试直接实例化。GREEN 只对齐类型/构造/字段形状；View layout 与 WebView engine 方法继续 fail-fast，WebViewClient 默认 callback 继续 no-op/null。真实 Comix WebView test 必须仍到达 exact getSettings UOE，MangaDex verifier/preference setup 不回归。文件限定 View.kt、ViewGroup.kt、EditText.kt、WebViewCompat.kt、AndroidViewVerificationAbiTest.kt、AndroidWebViewVerifierAbiTest.kt。

  Evidence: commits `5050c401c` + repair `4de695245`。RED 2 tests 精确命中 ViewGroup/WebSettings 非 abstract；GREEN 对齐 View(Context)、abstract ViewGroup(Context)、WebView super(context)、mutable LayoutParams fields、三 widget Context ctor 与 abstract WebSettings，engine UOE 保持。首轮 review 发现缺 public View.getContext；修复 RED 为 NoSuchMethodException，GREEN javap 为 public overridable `()Landroid/content/Context;` 且返回构造同一实例。唯一修复复审 APPROVED；全部 RealExtension/MangaDex/contract 通过，Java0。

##### Task 7C3p3b: ViewGroup 与 WebSettings evidence ledger

- Risk axis: `view-websettings-boundary-evidence`
- Platform boundary: `verification`
- Estimated scope: `3 files, 100 lines`
- Verification: 仅在 7C3p3a 独立审查通过后修改 inventory/evidence/contract。ViewGroup 标 required verifier/layout shell，WebSettings 标 required getSettings return token，均唯一绑定 tracked Comix 与 RealExtensionWebViewUnsupportedCompatTest；文案必须说明 View layout 与 WebView engine 仍 unsupported，不宣称 WebSettings 实例/setter 已执行。反向集合与 surface 必须精确，删除任一 token 时真实测试应在到达既定 UOE 前失败。

  Evidence: commit `4054a54b5`，严格 3 ledger files。contract RED 精确为 ViewGroup expected required/actual unverified；GREEN 将 ViewGroup/WebSettings 唯一绑定 tracked Comix + real WebView test，reverse 9 exact（8 required + WebView unsupported），surface 32/41。边界明确 ViewGroup 只覆盖 superclass/layout shell且layout UOE被扩展吞掉，WebSettings 只覆盖getSettings return token，不证明实例/setter/engine。独立 review APPROVED；contract/Comix/MangaDex全绿，Java0。

##### Task 7C3q0: ComicFury fixture authority freeze

- Risk axis: `comicfury-fixture-authority`
- Platform boundary: `verification`
- Estimated scope: `3 files, 100 lines plus one 41,496-byte APK`
- Verification: 固定 keiyoushi/extensions commit `7d5052fb895d086ae2ec6e3cca861146ee3ea0ec` 的 APK `apk/tachiyomi-all.comicfury-v1.4.8.apk`，blob `8660ce4c0366cd14c031731bf2b90febc5a24d3f`、41496 bytes、SHA-256 `9403d439eefec8ccff3fa7a3edd810046a12206d944302013bc3f94538b3def7`；提交 APK、provenance 与 authority/integrity test，不执行或宣称 Html/Color 行为。raw JAR 只用于审计，固定 blob `2a9e1e7ac8ab089fd0a2f6544c27319f2f14f672`、SHA-256 `1fc1b0fc1a3c9c974ca0ef399658da2b9b3d74561ef79c78a1bc77957ec80d65`，不得作为未追踪测试依赖。

  Evidence: fixture/provenance commit `671626c53`；唯一修复 commit `8d968b7a5`。严格 3 个交付文件（测试、provenance、41,496-byte APK）。测试先因缺 provenance RED，后对 APK SHA-256、size、manifest extension class GREEN；首审发现 package/versionCode/versionName/extensionLibVersion 仍为 JSON 自证，唯一修复以测试内、无 SDK/网络依赖的 AXML reader 直接解析 tracked APK 的二进制 `AndroidManifest.xml`，再次完成 RED（actual null）→ GREEN 1/1。固定 Git commit/root/parent、APK path/blob、raw URL 以及仅审计的 raw JAR blob/SHA 均核实；独立复审 APPROVED。根 `spotlessCheck` 仍仅被范围外既有 `GlobalSearchSourcePolicyTest.kt:53` 阻断，Java0；未执行 converter/loader/Html/Color 行为。

##### Task 7C3q1: Html Spanned ABI 与 fixed behavior

- Risk axis: `html-spanned-abi`
- Platform boundary: `desktop`
- Estimated scope: `3 files, 180 lines`
- Verification: 真实 ComicFury TextInterceptor 要求 `Html.fromHtml(String):Spanned` 与 `Html.fromHtml(String,int):Spanned`，当前 Desktop 错误返回 CharSequence 且缺 Spanned。先写 descriptor/代表性实体、标签、换行 RED，再新增最小 Spanned 类型并按 fixed Android 调用语义修正 Html；该 Task 只完成可独立验证的 ABI/文本语义，不升级 ledger。文件限定 Spanned.kt、Html.kt、focused Html test。

  Evidence: commit `52e706f8e`，严格 3 files / 107 touched lines。fixed main 使用 Android framework Html/Spanned；Android API 与 tracked ComicFury DEX 分别确认一/两参数方法均返回 Spanned，Desktop SDK_INT=28 实际走 flags=0 两参数分支。RED 中原 7 项通过、新 4 项分别因一参数 NoSuchMethod、两参数错误返回 CharSequence、缺 copy/数字实体与 legacy block newline 不符而失败；GREEN 后 Phase2 11/11。新增最小 Spanned marker，flags=0 覆盖代表性实体、inline tag、p/div 双换行、br 单换行与 trailing newline；未扩张四参数、toHtml、COMPACT、样式/CSS、渲染、Color 或 ledger。独立 authority audit/review APPROVED，diff-check clean、Java0；根 Spotless 复用已知范围外 blocker。

##### Task 7C3q2: ComicFury author-note 文本转 PNG 真实链

- Risk axis: `comicfury-text-image-pipeline`
- Platform boundary: `desktop`
- Estimated scope: `6 files, 400 lines`
- Verification: tracked ComicFury APK 必须经 production converter/meta/loader 展开 14 sources；由公开 page-list 链生成 host=`tachiyomi-lib-textinterceptor` 的 author-note Page，并经公开 `HttpSource.getImage(Page)` 进入真实 TextInterceptor。补齐该链实际执行的 TextPaint、Typeface、Layout.Alignment/StaticLayout 与 Canvas drawColor/save/translate/restore，复用已有 Bitmap create/compress；最终断言 HTTP 200、image/png、可解码非空 PNG 与代表性布局结果。不得只 loadClass、扫描常量池或直接调用私有 helper；若缺口超过 6 文件/400 行必须再拆。

  Evidence: implementation `9264f53a4`、唯一修复 `7b1dd5d45`；累计严格 6 files / 373 touched lines。真实 RED 为 tracked APK 经 production converter/meta 后 SourceFactory 缺 `android.text.Layout`，继续补齐预审 ABI 后暴露 TextPaint 构造；完整 GREEN 由 MockWebServer HTML 经 public page-list 生成真实 author-note Page，再经 public getImage 进入 TextInterceptor，14 sources/no diagnostics、HTTP 200 image/png、可解码 1000px PNG、长文折行增高、白底深色字形。发现 page-list 无条件调用 `Uri.encode(String)`，因此在同一预算内合并 Layout/StaticLayout 文件并新增 Android UTF-8 percent-encoding；emoji `%F0%9F%98%80` 由真实 Page URL 证明。首审发现错误 `<span>` 未执行标题/DEFAULT_BOLD、setTypeface 返回新值及新 Canvas 比较不能保护 restore；唯一修复取得真实 `NoSuchFieldError: Typeface.DEFAULT_BOLD` RED，以真实 `<a>`、public static DEFAULT_BOLD、旧值返回契约及同一长图下半区 minX 45..75 修复，real 1/1、Phase2 11/11。独立复审 APPROVED，diff-check clean、Java0；Bitmap/Html/Spanned/Color/ledger/loader/converter 零改。

##### Task 7C3q3a: ComicFury render public surface evidence

- Risk axis: `comicfury-render-surface-evidence`
- Platform boundary: `verification`
- Estimated scope: `3 files, 140 lines`
- Verification: 7C3q2 新增 4 个 compat 文件与 5 个 public symbols 后，surface contract 的真实 RED 为 36 files / 46 symbols，而 inventory 仍为 32/41，缺 `android.graphics.Typeface`、`android.text.Layout`、`android.text.Spanned`、`android.text.StaticLayout`、`android.text.TextPaint`。仅修改 inventory/evidence/contract，将这 5 项分别以 tracked ComicFury 与 `RealExtensionComicFuryTextCompatTest` 的实际执行边界登记为 required：Spanned 只覆盖 Html 返回 descriptor/实例，Typeface 覆盖 DEFAULT/DEFAULT_BOLD，TextPaint 覆盖构造与实际 setters，Layout 覆盖 ALIGN_NORMAL token，StaticLayout 覆盖固定构造/getHeight/draw。反向集合、required/unverified 计数和 36/46 surface 必须精确；不得改 Html/Uri/Color 状态，不得把未执行的其他字段、alignment 或排版语义写入证据。

  Evidence: commit `b8e361f71`，严格 3 files / 106 touched lines。contract RED 为 required expected 43/actual 38 且五项反向集合为空；GREEN 后 surface 36/46、required 43、unsupported 1、unverified exact `{Color, Html}`。五项均唯一绑定 tracked ComicFury SHA 与真实文本链，removalCondition 分别限定 Spanned descriptor/instance、Typeface DEFAULT/BOLD、TextPaint 实际 setters、Layout ALIGN_NORMAL token、StaticLayout fixed ctor/getHeight/draw 并明确排除 Android pixel/font-metrics parity；reverse exact 5。contract 6/6、real 1/1、独立 review APPROVED、diff-check clean、Java0；Html/Uri/Color/production 零改。

##### Task 7C3q3b: Html 与 Uri real-chain evidence ledger

- Risk axis: `comicfury-html-uri-evidence`
- Platform boundary: `verification`
- Estimated scope: `3 files, 100 lines`
- Verification: 仅在 7C3q1/q2 独立审查通过后，将 Html 标 required 并唯一绑定 tracked ComicFury 与真实文本转 PNG test；同时把 Uri 从仅 verifier-token 证据升级为“verifier token + `encode(String)` UTF-8 percent-encoding 真实执行”，仍不得宣称 parse/decode/Builder 或一般 URI 行为。更新 inventory/evidence/contract 反向集合与 surface；不得把未执行的 Color 或其他图文 API 顺带标 required。

  Evidence: commit `0152f1e7a`，严格 3 files / 67 touched lines。RED 为 required expected 44/actual 43、unverified expected 1/actual 2 及 ComicFury Uri evidence expected 1/actual 0；GREEN 后 surface 36/46、required 44、unsupported 1、unverified exact `{Color}`。Html 唯一绑定 ComicFury 的 SDK28 两参数 flags=0 Spanned author-note 实际链，一参数只说明由 q1 Phase2 descriptor 测试保护、未冒充真实执行；Uri 采用两条独立 evidence，contract 精确允许 Uri=2/其他 required=1，Comix 仍仅 getUrl token，ComicFury 仅 exact encode(String) UTF-8 percent/emoji并排除 parse/decode/Builder/construction/general behavior。ComicFury reverse exact 7、Comix reverse仍9；contract 6/6、real 1/1、独立 review APPROVED、diff-check clean、Java0，production/Color零改。

##### Task 7C3q4: verifier-only Color shim prune

- Risk axis: `color-shim-prune`
- Platform boundary: `desktop`
- Estimated scope: `4 files, 125 lines`
- Verification: ComicFury raw 字节码将 BLACK/WHITE 编译为整数常量，不链接 `android.graphics.Color`；仓库当前只有 shim 自测。删除 Color.kt 建立 prune probe，确认 production/全部 tracked fixture 无消费者后删除对应 AndroidCompatPhase2Test 片段、inventory entry并更新contract surface。若出现真实链接回归则恢复并重规划，不得用 ComicFury 作为 Color 假证据。文件限定 Color.kt、AndroidCompatPhase2Test.kt、inventory、contract。

  Evidence: scope correction `5be843b3b` 将纯机械删除从估算 100 调整为 125 lines；implementation `42e222d91` 严格 4 files / 122 touched。production/source-api 与全部 6 个 fixed fixture 字节码均为 Color 零消费者，ComicFury BLACK/WHITE 为 LDC ints；删除 Color 与 5 个 self-tests 后 contract RED 唯一为旧 surface expected 36/actual 35，无真实链接错误。GREEN 更新 inventory/contract 为 35 files/45 symbols、required 44、unsupported 1、unverified 0，evidence/reverse零改。一次组合 Gradle 覆盖 Html 6、contract 6、9 个 RealExtension*CompatTest classes/11 tests，总 23/23；独立 review APPROVED、diff-check clean、Java0。app-desktop 未应用 Spotless plugin，故无模块级 spotless task；未重复已知 root blocker。

##### Task 7C4: Source/extension authority baseline 与恢复入口纠偏

- Risk axis: `authority-resume-pointer`
- Platform boundary: `tooling`
- Estimated scope: `4 files, 150 lines`
- Verification: 更新 source/extension authority baseline 中已被 6C/6D 与真实 ManHuaGui Application evidence supersede 的陈述；把本计划中“Android/Desktop 权威类映射”改为 fixed-main authority 与当前双端 consumer/adapter 映射；让活动 `.superpowers/sdd/progress.md` 恢复入口指向 `2026-07-12-mihon-desktop-upstream-parity-roadmap-main-authority.md`，并在受版本控制的修正版父计划记录当前 source/extension 子计划。不得修改保留作历史对照的原路线图，不得把当前 `app/`、shared output 或 Desktop shim 写成原版权威。验证必须检查 live pointer、父子计划互指、旧 unsupported 文案消失以及 Comet plan guard 通过。

- [x] **Step 1: 纠正 source/extension baseline 的 6C/6D 与 ManHuaGui superseded 陈述**
- [x] **Step 2: 固定父子计划与 live progress 的恢复顺序**
- [x] **Step 3: 运行文本、路径、互指、diff 与 Comet guard 核验**

  Evidence: commit `a2a4fd416`，四文件/63 touched lines。baseline 现记录 Task 6C/6D 已接入的 Desktop presentation 链，并将 ManHuaGui Application 的旧 unsupported 结论明确标为由 `2e17f259f`/`a1b65a746` supersede。live progress 与受版本控制的修正版父计划互指本 child plan，恢复顺序为先完成本计划至 Task 7D，再返回修正版父计划的首个未完成 Task；fixed `main@6fbf6dfc` 仍是唯一原版权威，current app/shared/Desktop 仅为 consumer/output/adapter，保留的原路线图未修改。私密信息扫描、过时文案扫描、路径存在、`git diff --check` 与 Comet guard 均通过，独立审查 APPROVED；按 tooling/docs 边界未运行 Gradle。

#### Task 7D: Parity evidence and runtime verification

- Risk axis: `parity-runtime-evidence`
- Platform boundary: `verification`
- Estimated scope: `20 files, 700 lines across independently gated child Tasks`
- Split waiver: Task 7D 是汇总结构化证据、自动验证、三平台运行时验收与最终审查的 umbrella；Step 3 与 Step 4 修复均继续拆成下列单 risk-axis child Tasks，每个 child Task 不超过 8 files/400 lines，无法作为一个可独立调度的单提交执行。
- Verification: 仅消费 7A–7C 已闭合证据更新 parity 28–40、87，再运行全量测试、Android/Windows/macOS 验收与 thorough review；结构化 provenance 必须区分 fixed main、shared output、当前 Android consumer 与 Desktop adapter。

**Files:**
- Modify/Delete: 由 `compat-evidence.json` 审计确认无调用的 Desktop compat 符号；不得凭猜测删除。
- Create: `app-desktop/src/test/kotlin/mihon/desktop/extension/CompatEvidenceContractTest.kt`
- Create: `app-desktop/src/test/resources/extensions/compat-inventory.json`
- Modify: `app-desktop/src/test/resources/parity/parity-manifest.json`
- Modify: `docs/desktop-parity/PARITY_TRACKER.md`
- Modify: `docs/roadmap/extension-diagnostics-baseline.md`
- Modify: `docs/automation/TASK_TRACKER.md`（仅当 Test Mode 场景变化）
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/AppVersion.kt`（只由 build script 自动更新）

**Interfaces:**
- Consumes: 全部 Tasks 1–6 production/test evidence。
- Produces: parity 28–40、87 的真实状态/实现路径/保护测试，Windows/macOS/Android 运行时证据，完整独立审查结论。

- [x] **Step 1: 写 compat public surface RED 测试**

  扫描 compat 包 public 符号，要求每个符号在 `compat-inventory.json` 恰有一项；inventory 允许 `unverified|required|unsupported`，但 resolved 项必须在 `compat-evidence.json` 有唯一的真实 fixture/test，`required` 项测试可触发真实调用。`compat-evidence.json` 继续只记录真实观察结果，不得为凑齐 inventory 预填假 fixture；清单外 public API、重复项或伪造 resolved evidence 必须失败。

- [x] **Step 2: 运行 RED 并建立诚实 inventory**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.extension.CompatEvidenceContractTest"`
  Expected: 初次 FAIL 并列出 43 个 inventory 缺项。7A GREEN 只建立完整、可解析的诚实 inventory；逐项真实 fixture 判定与删除分别由 7B/7C 完成。

  Evidence: commit `a8aa3be07`；RED 在先确认 scanner 得到 39 files/43 public top-level symbols 后，精确列出 43 个 inventory 缺项；GREEN XML `2/0/0`。最终 inventory 43/43 unique 且全为诚实 `unverified`，固定 authority 为 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`，Desktop android/androidx roots 仅为 adapter。resolved 负例拒绝无 evidence、URL、parent classpath、AndroidCompat self-test 与 MinimalTestSource，未使用源码字符串扫描冒充行为证据。独立 review APPROVED；范围 `2 files, 247 lines`。

- [x] **Step 3: 更新 parity 28–40、87**

  IDs 28–40 的 manifest completion gate 使用结构化 provenance：`upstreamRef` 必须精确固定到 main，`upstreamSymbols` 的每个 path 在该 git tree 中存在，shared/current Android/Desktop 路径数组逐项验证，每个 deviation 对象独立携带允许 classification 和非空说明。`authoritativeImplementation` / `desktopImplementation` 仅保留兼容，不能作为完成证据；`protectionTests` 仍必须引用真实测试路径。状态只提升到证据支持的 CHARACTERIZED/SHARED/WIRED/VERIFIED，不把平台 adapter 当作业务豁免。

  Evidence: implementation `c629ca506`、唯一修复 `91f976ea5`，累计严格 5 files / 248 task touched（最终 range净 touched 246）。RED 23 tests中2项精确为 ID29 expected WIRED/actual NOT_STARTED 与 ID87 upstreamRef blank；GREEN 后契约 24/24。最终 28/32 保持 NOT_STARTED，29/30/33–40 为 WIRED，87 为 SHARED，无 VERIFIED、ID31按设计不存在；87 以 AppLanguage/SettingsAppearance/Localize 三条 fixed-main path/blob 为权威，ID40 的 current Android interceptor/NetworkHelper 保持 androidMain consumer而非shared。首审发现 diagnostics 旧枚举残留和 shared path gate 只验存在；唯一修复改为真实四枚举与 per-JAR/class-level remaining limitation，并新增 commonMain 强制门禁及 platform-path 负例。tracker 要求所有已提升项绑定 production behavior/wiring 测试；独立复审 APPROVED，JSON/path/blob static核验、diff-check、Java0。root Spotless 仍被范围外既有 `GlobalSearchSourcePolicyTest.kt` 格式问题阻断。

- [x] **Step 4: 运行全量自动验证**

  Run:

  ```bash
  ./gradlew spotlessCheck
  ./gradlew :domain:allTests
  ./gradlew :app:testReleaseUnitTest
  ./gradlew :app-desktop:jvmTest
  ./gradlew :test-desktop:test
  ./scripts/build-desktop.sh test-only
  ```

  Expected: 全部 BUILD SUCCESSFUL；报告精确测试数、失败数与既有 skipped，不用缓存结果冒充 fresh evidence。

  Initial full-run evidence: root Spotless 首次唯一失败为 `GlobalSearchSourcePolicyTest.kt` 的格式，单文件 commit `de3a17730` 修复后 61 tasks GREEN；`:domain:allTests` 首次因进程缺 ANDROID_HOME 未进入测试，改用 repo-local SDK 后 130 tasks GREEN；`:app:testReleaseUnitTest` 209 tasks GREEN。`:app-desktop:jvmTest` fresh 运行 1763 tests / 9 failures / 2 skipped，故 Step 4 暂不勾选并进入以下独立修复 Tasks；`GlobalSearchResultProductionWiringTest` 随后单类 GREEN，暂不以增大超时掩盖。

##### Task 7D4a: Extension UI model dependency boundary

- Risk axis: `desktop-ui-model-dependency-boundary`
- Platform boundary: `desktop`
- Estimated scope: `4 files, 100 lines`
- Verification: `ExtensionListScreen` 与 `ExtensionDetailsScreen` 当前在 UI 内直接 `Injekt.get<ExtensionsScreenModel>()`，全量 ArchitectureGuard 精确 RED 为 baseline 0 / actual 2。将同一 application singleton 暴露为中央 CompositionLocal/desktop dependency boundary，两个 Screen 只消费该 boundary；focused wiring 必须提供一个与全局 Injekt 不同的 local model并证明 UI 使用 local model，防止只把 service locator 藏到 helper。生产默认仍解析 `DesktopAppContext.extensionScreenModel`/同一 Injekt singleton，不创建第二套状态。不得提高 architecture baseline 或新增白名单。
  Evidence: implementation `d913ab29f`、唯一修复 `34ccbb90e`；累计仍严格 4 files，初始 50 touched + 4 个单行替换。新增中央 `LocalExtensionScreenModel` boundary；首版直接缓存默认 model，在顺序 Compose 测试中复用前一轮已关闭 singleton，表现为首项通过、后项 5s 无 UI。修复后 CompositionLocal 只缓存无状态 provider lambda，两个真实 Screen 每次组合从 boundary 调用当前 Injekt application singleton，不构造第二 model/state；UI test 用不同 local/global provider证明 local被读取且global零读取。ArchitectureGuard baseline/白名单零改；RED 为 local boundary unresolved及顺序测试旧 model 超时，最终 GREEN 为 Incognito 2/2 + presentation 3/3 + architecture 4/4；独立修复复审 APPROVED、diff-check clean、Java0。

##### Task 7D4b: Compat multi-evidence contract refresh

- Risk axis: `compat-multi-evidence-contract`
- Platform boundary: `verification`
- Estimated scope: `2 files, 40 lines`
- Verification: 更新陈旧的 verifier/product baseline：Uri declared methods 必须精确为 abstract `toString` + public static `encode(String):String`，并以代表性 UTF-8/emoji percent encoding保护；product baseline 不再错误要求 symbol 全局唯一，而要求 evidence identity `(symbol, fixture, test)` 唯一。Uri 两条正交 evidence 继续由 `CompatEvidenceContractTest` 精确约束为 Comix return token 与 ComicFury encode execution，其他 resolved symbol仍恰好一条；不得删除或合并真实 evidence。
  Evidence: commit `5e80153be`，严格 2 files / 20 touched lines。`AndroidWebViewVerifierAbiTest` 将 Uri ABI 精确约束为 abstract `toString` 与 public static `encode(String): String` 两个 declared methods，并验证默认 allow-list、空格 `%20` 与 UTF-8 emoji 大写 percent encoding；`DesktopExtensionProductBaselineTest` 改为 `(symbol, fixture, test)` evidence identity 唯一，并用真实重复 tuple 负例证明门禁。`CompatEvidenceContractTest` 继续要求 Uri 恰好两条正交 evidence、其他 resolved symbol 恰好一条；focused 12/12、独立 review APPROVED、diff-check clean、Java0。

##### Task 7D4c: Localized extension Compose semantics tests

- Risk axis: `localized-extension-compose-semantics`
- Platform boundary: `verification`
- Estimated scope: `3 files, 80 lines`
- Verification: `SourceSharedStateWiringTest` 与 `ExtensionIncognitoPreferenceWiringTest` 不再硬编码英文 contentDescription，使用 MR 当前 locale 文案并直接读取 `SemanticsProperties.ContentDescription`；在 zh-CN/current locale 下保护导航落点与 incognito 开关真实点击。`ExtensionPresentationUiTest` 对 StateFlow→collectAsState 的异步传播使用有界 render/yield 等待目标语义，替换“model state已更新后只render一次”的竞态，不改 production reducer或用无限等待掩盖错误。
  Evidence: commit `dd677ad43`，严格 3 test files / 60 touched lines，无 production 变更。Source reload 与 Incognito toggle 均从 MR 取得 current-locale 文案并结构化读取 `SemanticsProperties.ContentDescription`；Incognito 与 Presentation 仅以 5s 有界 render/yield 等待真实 Compose 语义传播，错误文案、缺失节点或错误状态仍会超时/断言失败，未复制过滤、刷新或状态转换逻辑。RED 为 zh-CN 下英文硬编码失配及单帧语义竞态；在 7D4a provider 修复后最终 SourceShared 49/49、Incognito 2/2、Presentation 3/3，共54/54。独立 review APPROVED、diff-check clean、Java0。

##### Task 7D4d: Desktop preference listener removal safety

- Risk axis: `desktop-preference-listener-cleanup`
- Platform boundary: `shared+desktop`
- Estimated scope: `2 files, 60 lines`
- Verification: full Desktop suite 捕获 `DesktopPreference.changes()` 在 backing Preferences node 已删除后执行 `removePreferenceChangeListener` 的 `IllegalStateException: Node has been removed`，异常泄漏到后续 coroutine test。先在 core/common JVM test 建立“active changes collector→外部 removeNode→cancel collector 不产生 cleanup failure” RED，再让 awaitClose 只吞掉 node-removed 的 cleanup exception；其他 listener 注册/移除异常仍传播。运行 core focused、GlobalSearchAuthority+Challenge 顺序组合、extension UI focused与 full Desktop JVM。
  Evidence: commit `ece4035d3`，严格 2 files / 23 touched lines。新增测试先启动 active `changes()` collector并 `runCurrent()` 确认 listener 已注册，再由外部删除 backing node、取消 collector；旧实现精确 RED 为 awaitClose 泄漏 `IllegalStateException: Node has been removed.`。GREEN 仅包围 `removePreferenceChangeListener` cleanup，只忽略该 exact ISE message；注册异常、其他 ISE及其他异常仍传播。focused 单测与完整 `DesktopPreferenceStoreTest` 20/20；独立 review APPROVED、diff-check clean。

##### Task 7D4e: Extension navigation test dependency isolation

- Risk axis: `extension-navigation-test-isolation`
- Platform boundary: `verification`
- Estimated scope: `1 file, 80 lines`
- Scope correction: self-contained fixture 必须显式构造 production presentation port/model并在 finally 关闭 Compose scene与 model；保留完整导航及语义断言后实际为 79 touched lines，仍远低于 Task 拆分阈值。
- Verification: full Desktop JVM 在 1764 tests 顺序中只剩 `SourceSharedStateWiringTest.browse tab extensions action renders extension list screen` 于 mock setup 读取未初始化的真实 `DesktopExtensionManager.installedExtensions`，证明该导航测试既依赖 final-class mock interception，也未显式提供 7D4a 新 boundary 的 model。保留真实 Browse action→Navigator→ExtensionListScreen 链，但以真实 `ExtensionsScreenModel` + production presentation port、可控空 catalog/installed flow与 relaxed manager建立自包含 fixture，并通过 `LocalExtensionScreenModel` 显式提供；不得回退全局 Injekt、不得 mock UI content或删除本地化 reload 语义断言。focused 后重跑 full Desktop JVM；只有 Global Search 仍失败才启用下述条件 Task。
  Evidence: commit `29de21b4c`，严格 1 test file / 79 touched lines。保留真实 Browse action语义点击→Voyager导航→`ExtensionListScreen`→MR localized reload `ContentDescription`；fixture 改为真实 `ExtensionsScreenModel` + production `DesktopExtensionPresentationPort`、可控 empty catalog/installed flow与 relaxed manager，并显式提供 local model，不再依赖 global Injekt或 final-class getter mock。RED 为 full-suite setup NPE，GREEN focused 1/1；独立 review APPROVED、diff-check clean、Java0。

##### Task 7D4f: Global search Compose await under full-suite load

- Risk axis: `global-search-compose-await`
- Platform boundary: `verification`
- Estimated scope: `1 file, 75 lines`
- Scope correction: 统一该测试类 10 个异步边界与 4 个 Compose pump，并移除固定 delay import 后初始为 31 touched lines；后续 full-build RED 证明单槽 latest-row 探针受多个真实 collector 乱序覆盖，最终单文件累计约 48 touched，仍保持在一个机械测试修复 Task 内。
- Verification: 7D4e 后 full Desktop 1764 tests 仅 `GlobalSearchResultProductionWiringTest.only composed cards observe canonical database rows without another search` 在初始 2s render/delay轮询超时；同类 focused 曾通过且前一次 full suite亦通过，证明是 suite负载下的有界调度竞态，不是稳定的 production state失败。统一该类异步等待的非零但负载容忍上限，并将 Compose pump 的固定10ms睡眠改为 render/yield，让等待释放调度而不累计人为延迟；所有状态/DB/导航/错误断言保持不变。focused 连续验证后重跑 full Desktop JVM；不得改 production、删除断言或使用无限等待。
  Evidence: commit `f1f97d84b`，严格 1 test file / 31 touched lines。统一 10 个异步边界为有界 5s，4处 Compose固定 delay改为 render/yield；DB写入/订阅集合、导航栈幂等、详情输入、查询次数、新旧结果隔离与错误反馈断言全部保留。RED 为 full-suite 2s timeout；focused 2/2连续两轮 GREEN（第二轮 `--rerun-tasks`），随后 full Desktop 1764 tests / 0 failed / 2 skipped。独立 review APPROVED、diff-check clean、Java0。
  Follow-up evidence: later full Desktop first exceeded the 5s suite-load budget, so commit `8c3c1ff6f` raised the still-bounded timeout to 15s; a subsequent `build-desktop.sh` RED still timed out at the wrapped repository's single-slot `latestRows` probe, disproving timeout-only sufficiency. Final commit `0545cbb04` replaces that non-monotonic probe with a concurrent set of observed `(source,url,title)` emissions and removes a test-only extra unwrapped `GetManga.subscribe().first` collector. Database update/current read, production wrapped Flow emission, and final Compose title/favorite/cover/navigation assertions all remain. Focused 2/2, full Desktop 1769/0/2, BUILD24 packaging, and smoke 88/88 passed; final independent review APPROVED.
  Final-load evidence: Windows `build-desktop.sh` and the independent macOS run each later observed one initial-render timeout while the same focused class immediately passed, and all subsequent full-suite assertions remained green. Commit `848be99cc` therefore keeps the shared 15s budget for every later boundary, gives only the initial combined repository-observation plus two-row Compose materialization boundary a bounded 30s budget, and emits observed rows, active subscriptions, and rendered text on failure. It does not change production, collection topology, or assertions. A fresh direct full Desktop run passed 1770/0/2; the final build-script run remains the delivery gate.

  Conditional flow: 完成 7D4a–7D4d 后重跑 full Desktop JVM；若 `GlobalSearchResultProductionWiringTest` 仍只在 full-suite 负载下超时，另立 ≤2 files/60 lines 的 `global-search-compose-await` Task，先以有界重复/组合复现证明 frame propagation root cause，再调整测试 pump；若不再失败则不创建该 Task。

  Final full-run evidence: root `spotlessCheck` 61 tasks GREEN；`:domain:allTests` 130 tasks GREEN；`:app:testReleaseUnitTest` 209 tasks GREEN；`:app-desktop:jvmTest` fresh 1764 tests / 0 failed / 2 skipped；`:test-desktop:test` 17/17 GREEN；`build-desktop.sh test-only` 在版本不变 `0.11.14.21.f1f97d8` 下 GREEN。首次 wrapper 执行异常无输出并遗留单个 Gradle daemon，10分钟后终止且 `gradlew --stop` 清理；设置仅本进程 `GRADLE_OPTS=-Dorg.gradle.daemon=false` 后同一脚本20.4s成功，未修改全局配置或版本号。

- [x] **Step 5: Android 模拟器运行时验收**

  自行启动 API 36 x86_64 AVD，`assembleDebug` 后安装匹配 ABI APK 与代表性纯 HTTP 扩展。验收：扩展发现/安装/加载、源列表、单源浏览、全局搜索、空/403/失败反馈和设置入口；收集 UI dump、截图与 logcat，FATAL/OOM/SIGSEGV 必须为 0。

  Evidence: repo-local API 36 `google_apis;x86_64` AVD `mihon-api36` cold boot完成，`:app:assembleDebug` GREEN并安装 `app-universal-debug.apk`（package `app.mihon.dev`）与 tracked `keiyoushi-tcbscans-1.4.12.apk`。Android Mihon先显示 TCB Scans `UNTRUSTED`，经真实 Trust确认后 Sources显示 English/TCB Scans，Popular实际解析出 Jujutsu Kaisen等漫画；不存在查询显示 `No results found` + Retry/WebView/Help，airplane-mode显示 `No Internet connection` + Retry/WebView/Help，恢复网络后 Global search `jujutsu` 在 All过滤下显示 TCB Scans/Jujutsu Kaisen/Jujutsu Kaisen Modulo。Settings→About显示 `Mihon Debug 752875f53`；UI dump与截图保存在本地 `.test-tmp/mihon-android-*`（不纳入Git）。尝试以Android系统HTTP proxy注入403时确认production OkHttp不采用该代理，故未把无限loading冒充403；403仍由Step4已通过的真实production MockWebServer失败矩阵覆盖。清除proxy、关闭app/emulator后 logcat `FATAL EXCEPTION=0`、`OutOfMemoryError=0`、`SIGSEGV=0`、`Fatal signal=0`。

- [ ] **Step 6: Windows 固定 EXE 与 macOS 验收**

  Windows 只运行 `./scripts/build-desktop.sh`，启动 `D:\Shell\Github\mihon\app-desktop\tmp\mihon-dist\main\app\Mihon Desktop\Mihon Desktop.exe`，核对窗口完整版本、mtime、安装/更新/失败回滚、浏览/搜索、登录后备和文件工具；运行 `./scripts/desktop-smoke-test.sh`。通过 `ssh mbp` 在安全临时 clone 运行相关测试/构建，部署并启动 `/Applications/Mihon Desktop.app`，不覆盖远端用户仓库。

  Windows evidence: the first post-repair build consumed BUILD23 but stopped at the now-superseded Global Search probe RED and produced no accepted artifact. The next required `scripts/build-desktop.sh` run advanced to and validated `Mihon Desktop 0.11.14.24.0545cbb`; full Desktop was 1769 tests / 0 failed / 2 skipped, and the canonical unpackaged EXE is `D:\Shell\Github\mihon\app-desktop\tmp\mihon-dist\main\app\Mihon Desktop\Mihon Desktop.exe`. The same packaged EXE in `--test-mode --test-http-port=8080 --headless` remained alive after HTTP readiness plus 8 seconds, returned health `ok`, `testMode=true`, 9 screens and 18 actions, then was cleaned up. `scripts/desktop-smoke-test.sh` was routed through Git Bash with process-local JDK21 because the default `bash` was WSL and mangled Windows `JAVA_HOME`; the real smoke run passed 8 suites / 88 tests / 0 failures.

##### Task 7D6a: Headless Test Mode process lifetime

- Risk axis: `desktop-headless-test-lifecycle`
- Platform boundary: `desktop`
- Estimated scope: `3 files, 260 lines`
- Scope correction: macOS packaged runtime proved that Ktor's `start(wait = true)` can return after binding, so the repair must give each start generation an independent termination signal, retain and explicitly stop the engine, and cover stop/restart isolation in the existing lifecycle test file; this remains three files and below the Task split threshold.
- Verification: Windows canonical EXE `0.11.14.22.df46bdd` 已通过build script窗口版本验收，但按文档以 `--test-mode --test-http-port=8080 --headless` 启动后约4秒正常退出且8080无listener；production `Main` 在异步 `TestMode.start()` 后遇headless直接return，JVM只剩daemon线程。先以可控await/stop与真实 `DesktopAppRuntime` 建立生命周期RED：test-mode+headless必须阻塞、释放后stop TestMode并close runtime；非headless不得阻塞或关闭。GREEN让 TestMode暴露等待server job结束的边界，Main只在test-mode headless进入该边界并finally清理；不得用sleep/无限轮询或让普通GUI启动阻塞。focused/full tests后重新完整构建固定EXE，实启headless并读取 `/test/state`，再运行desktop smoke。
  Evidence: commits `f1b32eee6`, `43682df5a`, `970ecdd10`, and `302b1fb17`, cumulatively 3 files / 236 touched. RED was the canonical EXE exiting in about four seconds plus unresolved production helper compilation. GREEN gives each start generation an immutable `TestModeRun`, retains the actual Ktor engine after non-blocking start, releases only on startup failure or stop, explicitly stops the engine, and guarantees every cleanup step plus termination even when engine stop throws. Regression tests cover headless wait/cleanup, GUI non-blocking behavior, old-run isolation, and throwing-stop release; focused 12/12 and fresh full Desktop 1769/0/2 passed. Two repair reviews found and drove the cross-generation and stop-exception fixes; final independent review APPROVED.

##### Task 7D6b: Test Mode state capability response

- Risk axis: `desktop-test-state-capabilities`
- Platform boundary: `desktop`
- Estimated scope: `2 files, 80 lines`
- Verification: macOS packaged GUI test mode proves `/test/state` reports `testMode=true` while `screens` and `actions` are always empty because the production HTTP route hard-codes empty arrays instead of reading the lists registered by `TestMode.start`. First add a production HTTP integration RED that registers sentinel capabilities and observes the state endpoint, then serialize `applicationState.screens/actions` without copying registration rules into the route. Preserve reset semantics and existing endpoint fields; focused/full tests and packaged Windows/macOS `/test/state` must show non-empty registered capabilities.
  Evidence: commit `743ffffef`, strictly 2 files / 40 touched. The real embedded production HTTP route RED returned empty arrays after sentinel registration; GREEN serializes `applicationState.screens/actions` directly without copying capability rules. Focused 2/2 and fresh full Desktop 1769/0/2 passed; independent review APPROVED.

##### Task 7D7: Touched source browse UI localization

- Risk axis: `desktop-source-ui-localization`
- Platform boundary: `desktop`
- Estimated scope: `5 files, 260 lines`
- Scope correction: the existing end-to-end filter scenario is intentionally kept as one behavior test and wrapped in an explicit locale restoration boundary; together with exact structured ContentDescription checks this produced 204 touched lines across the same four files, still below the Task split threshold.
- Verification: final thorough review found newly touched SourceBrowse/Browse controls and Pin/Unpin semantics hard-coded in English while existing MR resources already provide popular/latest/filter/apply/reset/cancel/ascending/descending/pin/unpin. First change the existing production Compose wiring tests to run under zh-CN and assert current-locale MR strings through rendered semantics, proving the English constants fail. Then replace only the touched production literals with MR accessors; do not add duplicate resources or change filter/query/pin behavior. Focused source browse/filter tests, full Desktop, and final review must pass.
  Evidence: commit `211b50ad3`, strictly 2 production + 2 existing test files / 204 touched lines. The zh-CN RED had exactly the two target tests fail because localized labels could not be found; GREEN reuses the fixed-main MR resources for Popular/Latest/Filter/Apply/Reset/Cancel/Ascending/Descending and Pin/Unpin, while retaining the source name in accessibility descriptions. Pin/Unpin tests read exact `ContentDescription` list values so Chinese `取消置顶` containing `置顶` cannot create a substring false positive. Focused 2 suites / 50 tests / 0 failures and root Spotless 61 tasks passed; independent re-review APPROVED with 0 Critical/Important/Minor.

##### Task 7D8: Fixed-main global-search source ordering

- Risk axis: `global-search-source-order`
- Platform boundary: `shared`
- Estimated scope: `2 files, 100 lines`
- Verification: the current shared `GlobalSearchSourcePolicy.select` only filters and explicitly preserves candidate order, while fixed main `SearchScreenModel.getEnabledSources()` orders pinned sources first and then by normalized name plus language. First add a fixed-main-derived contract RED with interleaved pinned/unpinned and name/language inputs, then implement the same comparator after filtering. `PinnedOnly` must still exclude unpinned sources; `All` must retain them after pinned sources. Do not use the current Android consumer as expected-value authority.
  Evidence: commit `cc223a10a`, strictly shared policy + existing contract test. The interleaved candidate RED failed only the new fixed-main ordering assertion; GREEN filters first, then orders pinned sources before unpinned and normalizes name/language exactly as fixed-main `SearchScreenModel`. Focused 2/2 and shared `:domain:spotlessCheck` passed; diff-check clean.

##### Task 7D9: Fixed-main extension presentation comparator

- Risk axis: `extension-presentation-sort`
- Platform boundary: `shared`
- Estimated scope: `2 files, 100 lines`
- Verification: shared extension projection currently sorts through `name.lowercase()`, whereas fixed main uses `String.CASE_INSENSITIVE_ORDER` for installed, untrusted, and available lists. Add mixed-case/non-ASCII comparator fixtures derived from fixed main and prove the current ordering differs where relevant; then use one locale-independent case-insensitive comparator for all three lists without changing obsolete/update partitioning, NSFW filtering, language projection, or Desktop-only metadata.
  Evidence: commit `f0534755e`, strictly shared store + existing contract test. The Unicode RED proved lowercase-key reordered `İ/i` and `Σ/ς` that fixed-main's case-insensitive comparator treats as equal with stable input order. GREEN uses common `compareTo(ignoreCase = true)` for installed after the obsolete key, untrusted, and available; focused 5/5 and shared `:domain:spotlessCheck` passed, diff-check clean.

##### Task 7D10: Fixed-main empty-page and append-retry semantics

- Risk axis: `source-empty-page-recovery`
- Platform boundary: `shared`
- Estimated scope: `8 files, 180 lines`
- Scope correction: the explicit shared `AppError.NoResults` correctly forced one mechanically unreachable extension-install exhaustive branch, while the two consumers each need their existing presentation adapter and focused test; the final implementation is exactly the 8-file Task ceiling but only 142 touched lines. The global AppError variant inventory was repaired separately in one test-only follow-up.
- Verification: fixed main converts every empty source page to `NoResultsException`; the presentation renders an empty first page but an empty append remains a visible retryable append error. Current shared `SourcePageResult.Empty` silently terminates append pagination and current Android/Desktop consumers inherited that rewrite. Add shared reducer RED plus current Android and Desktop consumer assertions: first-page empty remains the localized no-results product state, while page>1 empty preserves existing rows and exposes a retry action for the same request. Implement an explicit shared no-results error/state instead of `Unknown` or copied UI rules, and keep 403/429/500/malformed/cancellation behavior unchanged.
  Evidence: implementation `6557cdb22` plus variant-contract follow-up `3c3fa6881`. Shared/desktop REDs proved empty append had no page error or retry. GREEN adds explicit `AppError.NoResults`; the reducer keeps rows and same-page Retry, current Android exposes fixed-main `NoResultsException`, and Desktop maps to localized `no_results_found`. Domain 12/12, Desktop 14/14, Android 5/5 with repo SDK, AppError contract 3/3, root/domain Spotless, and diff-check passed. First-page Desktop rendered copy was intentionally closed in 7D11 because that Task owns `SourceBrowseScreen`.

##### Task 7D11: Desktop source-browse canonical result wiring

- Risk axis: `source-browse-canonical-result`
- Platform boundary: `desktop`
- Estimated scope: `3 files, 360 lines`
- Scope correction: reuse was stronger than planned: generalizing the existing Global Search materializer let Source Browse consume the same canonical persistence, generation/attempt CAS, retry, and close behavior without a new helper or dependency file. The dedicated real-DB Compose test brings the final range to 339 touched lines across only three files.
- Verification: fixed main `BaseSourcePagingSource` performs URL de-duplication, `SManga.toDomainManga(sourceId)`, and `NetworkToLocalManga` before publishing rows; `BrowseSourceScreenModel` then observes `GetManga.subscribe(url, source)` so favorite/title/cover changes propagate. Desktop currently renders raw `SManga` and only persists on click. First add a real repository/Compose RED proving rows are canonical before click and a DB update changes the mounted card without a new network search. Reuse the existing repository/interactors and dependency boundary; preserve Desktop wide-grid layout, login recovery, generation/CAS behavior, de-duplicated detail navigation, raw-source background refresh, and all file tools.
  Evidence: commit `c221ac0b1`, 3 files / 339 touched lines. The mounted real-SQLite RED timed out because no row existed before click. GREEN reuses generalized `SourceResultMaterializer` for URL de-duplication, `toDomainManga`/`NetworkToLocalManga`, and generation+attempt publication; `GetManga` flows update DB title/cover/favorite without another source request, while raw `SManga` remains only for detail background refresh. A separate mutation RED proved a NonCancellable old attempt could publish after close until close invalidated attempts. The same zh-CN mounted test also replaced the wrong “no sources” empty copy with fixed-main `no_results_found`. Final dedicated + Global materializer/projection matrix 7/7, root Spotless and diff-check passed.
  Fixture follow-up: commit `76a053338` adds the now-consumed `getManga` dependency to three pre-existing strict mounted-screen fixtures. The combined SourceBrowseFilter/SourceLogin/SourceShared matrix compiled production and passed; no production behavior or mock implementation replaced the canonical repository test.

##### Task 7D12: Reactive Desktop source membership

- Risk axis: `source-membership-reactivity`
- Platform boundary: `desktop`
- Estimated scope: `5 files, 300 lines`
- Verification: fixed main `AndroidSourceManager.sourcesMapFlow` is rebuilt from installed-extension flow and `SourcesScreenModel` continuously collects it; Desktop still returns `flowOf(getCatalogueSources())` and `BrowseTab` snapshots via `remember`. Add a production wiring RED that installs/reloads/uninstalls a controlled extension while the same mounted Browse screen remains alive and observes source membership changes. Drive the flow from `DesktopExtensionManager.installedExtensions`, keep built-in/local sources and Desktop loader/repository behavior, and do not poll or rebuild the Screen manually.
  Evidence: commit `e577fb235`, strictly 2 production files + 1 dedicated mounted wiring test / about 115 touched lines. RED timed out waiting for an installed extension source in the same scene. GREEN derives `DesktopSourceManager.catalogueSources` from `installedExtensions` and makes Browse collect it; the same mounted screen observes install, replacement reload, and uninstall while built-in/local entries remain. Reactive + affected Browse + manager/repository matrix 10/10, root Spotless and diff-check passed.

##### Task 7D13: Fixed-main source list projection and last-used boundary

- Risk axis: `source-list-upstream-projection`
- Platform boundary: `desktop`
- Estimated scope: `6 files, 360 lines`
- Verification: fixed main projects last-used first, then pinned, then language groups, and records last-used only outside incognito. Desktop currently renders pinned then alphabetic and never records last-used. Add projection contract REDs from fixed-main `GetEnabledSources`/`SourcesScreenModel`, plus a real navigation/wiring RED proving entry into a source updates last-used only when incognito is off and reorders the still-mounted list. Reuse Desktop preferences and reactive membership from 7D12; preserve wide-screen cards, pin buttons, hidden/language filters, Extensions empty action, and long-click pin behavior.
  Evidence: RED `87efbf3a9` 固定 last-used/pinned/language 投影及真实导航写入边界；core `50d9bef77` 接入 `last_catalogue_source`、reactive projector 与 Browse/SourceBrowse production wiring，fixture follow-up `7e77a9a1b` 只为既有严格测试提供新增依赖。首轮 8 类 62/62、root Spotless 61/61；review repair `ef571737c` 将分组标题改为 MR 本地化并通过 focused 54/54；short-circuit repair `9acf0d8c4` 让 global/extension-package incognito 在持久化前返回，LastUsed 3 + Projector 3 = 6/6。最终 review APPROVED、0 Critical/Important。Global login render/Channel 的改动仅是矩阵稳定性 fixture follow-up，不作为 last-used 或 incognito 业务行为证据。

##### Task 7D14: Extension-details source state ownership

- Risk axis: `extension-details-source-state`
- Platform boundary: `desktop`
- Estimated scope: `6 files, 360 lines`
- Verification: fixed main `GetExtensionSources` continuously observes disabled-source preferences, sorts enabled-first/display-name, and exposes single plus enable-all/disable-all actions through `ExtensionDetailsScreenModel`. Desktop details currently stores enabled flags in per-row `remember`. Add external-preference-update and enable-all/disable-all production wiring REDs, then move ownership into the existing shared presentation model/port and real preference adapter. Keep Desktop repository metadata, cookie tools, folder actions, uninstall behavior, and wide layout unchanged.
  Evidence: commit `787366153`, 4 production + 1 existing real wiring test / 177 touched lines. The RED failed only external preference propagation. GREEN makes the model collect disabled-source IDs, projects sources through shared `enabledFirst`, removes Composable `remember`/direct manager calls, and routes single plus enable-all/disable-all through one atomic preference adapter update. The same mounted test retains metadata, cookie, folder, uninstall, settings/browse navigation, and source website assertions; focused 4/4, root Spotless and diff-check passed.

##### Task 7D15: Fixed-main obsolete and NSFW details feedback

- Risk axis: `extension-details-upstream-feedback`
- Platform boundary: `desktop`
- Estimated scope: `5 files, 300 lines`
- Verification: fixed-main extension details presents obsolete and NSFW/age-rating warnings with user-visible feedback; Desktop details omits both despite having the projection data and existing MR resources. Add zh-CN rendered-semantics REDs for representative obsolete and NSFW extensions, then wire the fixed-main copy and acknowledgement behavior through the Desktop screen/model. Do not remove Desktop SHA/repository/folder/cookie metadata or invent a platform exception.
  Evidence: RED `bd2a8862a` 在 zh-CN rendered semantics 中固定 obsolete banner、NSFW age-rating 入口与确认反馈；GREEN `f609d182a` 复用既有 MR 文案接入详情页，repair `f81566d05` 加固真实 projection/wiring，保留 SHA、仓库、目录、Cookie 与源设置入口。Metadata 1 + Preferences 4 = 5/5，root Spotless 61/61；final review APPROVED、0 Critical/Important/Minor。

##### Task 7D16: Extension-list ScreenModel action routing

- Risk axis: `extension-list-action-routing`
- Platform boundary: `desktop`
- Estimated scope: `5 files, 320 lines`
- Verification: fixed main routes refresh/install/update/cancel/trust/uninstall actions through `ExtensionsScreenModel`; Desktop list still calls the manager directly for reload and uninstall, bypassing typed state/error feedback already present in its model/port. Add mutation-sensitive wiring REDs proving the visible actions call the model and surface failure without direct manager calls, then remove only the bypass. Preserve Desktop transactional installer, rollback, repository identity, update-all, cancellation, and terminal diagnostics.
  Evidence: RED `c05f914e9` 证明 reload/uninstall 的可见动作必须经 model 并保留 typed failure；GREEN `7c26a059e` 将两个 bypass 收口到 `ExtensionsScreenModel` / `DesktopExtensionPresentationPort`，Desktop manager 继续只承担 reload/uninstall side effect，事务安装、rollback、仓库 identity、update-all、取消及 diagnostics 均保留。focused 1/1、`ExtensionPresentationUiTest` 4/4、root Spotless 通过；review APPROVED、0 Critical/Important/Minor。

##### Task 7D17: Closed-behavior status and provenance bookkeeping

- Risk axis: `source-extension-status-provenance`
- Platform boundary: `docs`
- Estimated scope: `5 files, 300 lines`
- Verification: 仅以 7D13/7D15/7D16 已通过的 fixed-main fixture、shared output、当前 Android consumer 与 Desktop production wiring 四层证据纠正活动计划、authority audit、OpenSpec、parity manifest IDs 29/37 和 authority baseline；状态最高保持 `WIRED`，最新 Windows/macOS Desktop 运行验收未完成前不得勾选 OpenSpec 3.4 父项或提升为 `VERIFIED`。当前 Android consumer 的既有模拟器证据只用于运行核对，不构成原版权威。JSON 变更运行 `DesktopProductCapabilityContractTest`，并检查 JSON parse、`git diff --check` 与 Comet plan guard。
  Evidence: commit `e2533edc4`，严格 5 files / 82 touched lines。OpenSpec 3.4 父项恢复未完成，只勾具备四层证据的 source query/result 与 Desktop extension presentation 子项；列表 projection 明确为两端从同一 fixed-main fixture 分别实现，不冒充 shared output。manifest IDs 29/37 保持 `WIRED`，29 补齐 `GetEnabledSources`/`SourcesScreenModel`/`GetIncognitoState`/`BrowseSourceScreenModel` 的 fixed-main blob 与双端 consumer provenance，37 补齐本轮真实保护测试并明确 shared reducer 只是 migration output。focused capability contract 首轮仅因一个测试路径目录写错失败，修正为真实 `desktop/i18n` 路径后 24/24；两个 JSON parse、`git diff --check`、OpenSpec strict validation 与 Comet plan guard 通过。authority baseline 未重复改写：commit `a2a4fd416` 已在现有行 30/51/62 闭合 6C/6D 与 ManHuaGui Application 的 superseded 状态。

##### Task 7D18: Remaining authority terminology and stale design cleanup

- Risk axis: `authority-terminology-cleanup`
- Platform boundary: `docs`
- Estimated scope: `5 files, 180 lines`
- Verification: 将 OpenSpec design 中未固定的 “Android authority” 改为 fixed-main original Mihon authority，纠正 shared contract 测试的歧义命名，核对 source design 中尚未实现的 recovery action 描述，并给保留的旧比较文档增加 superseded 指引；不得改写历史原文证据、不得把当前 `app/`、shared output 或 Desktop shim 写成 authority。运行文本扫描、相关 focused contract（仅测试名/契约变化时）、`git diff --check` 与 Comet plan guard。
  Evidence: commit `7ad05831a`，严格 5 files / 11 touched lines。OpenSpec design、source/extension design 与 parity tracker 统一 fixed-main authority/current consumer/adapter 术语；保留的旧比较文档增加 superseded 指引；shared contract 测试名不再把当前 Android mapper 称为 authority。本 Task 仅改变文档与测试显示名，按机械文档流程未运行 Gradle、未进行独立代码审查；协调者随后完成逐项只读核验，定点文本复扫与 `git diff --check` 通过。

##### Task 7D19: Desktop artifact signer authenticity

- Risk axis: `desktop-extension-artifact-authenticity`
- Platform boundary: `desktop`
- Estimated scope: `8 files, 400 lines`
- Verification: fixed main `TrustExtension` compares the downloaded APK's actual signer fingerprint with the repository signing fingerprint. Desktop currently trusts repository identity and an index-provided digest without authenticating the artifact signer, so an attacker controlling the repository can replace both index and executable bytes. First characterize APK and native-JAR artifact formats with real production download/install seams. APK acceptance must verify an actual signer bound to `RepositoryIdentity.signingKeyFingerprint`; native JAR must use a cryptographically verifiable signer or authenticated detached/index signature bound to the same identity, and must fail closed when authenticity cannot be established. A matching digest from the same unauthenticated index is insufficient. Add attack REDs where index and digest are replaced together but the artifact signer is wrong. Preserve Desktop APK→JAR conversion, native-JAR support only when its authenticity is provable, trust prompts, atomic rollback and installed-sidecar continuity. If a secure native-JAR protocol cannot fit this boundary, split characterization/protocol tasks before implementation rather than weakening the requirement.
- Evidence: RED `e455d55b0`, GREEN `f8a054a04`; real APK signer mismatch/correct signer, unsigned/signed native JAR and digest-order focused tests 5/5, adjacent install/trust/rollback 51/51, root Spotless GREEN. Independent review APPROVED with Critical 0 / Important 0 / Minor 0.

##### Task 7D20: Android extension/source initialization atomicity

- Risk axis: `android-extension-initialization-atomicity`
- Platform boundary: `android`
- Estimated scope: `4 files, 300 lines`
- Verification: fixed main exposes installed/untrusted extensions synchronously before source initialization completes. Add a delayed real loader integration RED proving `AndroidSourceManager.isInitialized` cannot become true while the extension snapshot is still the initial empty value. Either restore atomic initialization or explicitly gate SourceManager on `ExtensionManager.isInitialized`; the first initialized source snapshot must already contain extension sources. Preserve asynchronous follow-up updates, receiver reloads and cancellation behavior.
- Status: implementation commits `6058fbd58`, `9f7ebef9c`, `550edea9b`, `2aff28df3` fixed synchronous first-snapshot projection and atomic concurrent map updates, but the single allowed repair review found a suspend-loader receiver-registration gap and an insufficient first-chain test barrier. Do not treat Task 7D20 as approved; the remaining work is replanned as Task 7D20B.

##### Task 7D20B: Android initialization event handoff

- Risk axis: `android-extension-initialization-event-handoff`
- Platform boundary: `android`
- Estimated scope: `4 files, 360 lines`
- Verification: preserve the current suspend extension loader without creating a package-event blind window. Register a production buffering listener when the manager is constructed; while the initial snapshot is loading, record install/update/untrusted/uninstall events in arrival order. Under one lock or serialized actor, publish the loader snapshot, replay the buffered events, switch the same listener to live application and only then publish `isInitialized = true`. Add a delayed-loader behavior RED that captures the real production listener, injects events after the loader snapshot is fixed but before publication, and proves the initialized installed/untrusted snapshots reflect ordered replay. Replace the AndroidSourceManager negative timing assertion with a deterministic barrier on the actual installed-extension collection chain. Preserve the single Map StateFlow and atomic update work from Task 7D20, receiver reload/update behavior and fixed-main load-before-initialized semantics.
- Evidence: initial RED/GREEN `385b87283` / `8380f0807`, repair RED/GREEN `51e74c60a` / `cbef7e0ba`; focused 8/8, adjacent install/session/coordinator/security rollback 57/57 and root Spotless GREEN. Construction-time receiver buffering, ordered snapshot replay, deterministic source collection and active runtime reload all share the initialization/live gate. Repair review APPROVED with Critical 0 / Important 0 / Minor 0.

##### Task 7D21: Extension refresh failure presentation

- Risk axis: `extension-refresh-failure-feedback`
- Platform boundary: `desktop`
- Estimated scope: `5 files, 320 lines`
- Verification: `ExtensionsScreenModel.refreshError` is currently stored but neither list nor details consumes it; an initial catalog failure leaves `projection == null` and a permanent spinner. Add real mounted REDs for list error/retry and locally installed details under remote-catalog failure. List must show localized failure plus retry; details must continue from authoritative installed state and must not require a successful remote catalog to render local metadata/actions. Preserve Desktop details capabilities and 7D15/7D16 routing.
- Evidence: initial RED/GREEN `c4d7b27db` / `3be2de3b1`, repair RED/GREEN `db8c902e8` / `c2d4517b3`; focused 5/5, adjacent production UI/state 14/14 and root Spotless GREEN. First catalog failure keeps local installed state and retry feedback, refresh is single-flight, and installed details automatically return on uninstall with a root-screen fallback. Repair review APPROVED with Critical 0 / Important 0 / Minor 0.

##### Task 7D22: Android shared-query production wiring evidence

- Risk axis: `android-shared-query-production-wiring`
- Platform boundary: `shared+android`
- Estimated scope: `4 files, 300 lines`
- Verification: remove source-text scanning as completion evidence. Add an injectable production page-loader/service boundary whose integration test returns a unique sentinel/error while the direct source path fails; the real Android ScreenModel must call the shared query service, publish the shared result/recovery action and never execute a duplicate direct source rule. A mutation that bypasses the shared service must fail behaviorally. Keep source HTTP execution inside the shared service and current Android UI semantics unchanged.
- Evidence: commit `53affec75`; production Browse Pager and Global Search behavior tests replace all source-text scanning, related regression 13/13 and root Spotless GREEN. A temporary direct-source mutation made the new Browse test fail and was restored byte-for-byte before commit. Independent review APPROVED with Critical 0 / Important 0 / Minor 0.

##### Task 7D23: Final provenance manifest and audit reconciliation

- Risk axis: `final-source-extension-provenance`
- Platform boundary: `verification`
- Estimated scope: `7 files, 350 lines`
- Verification: correct parity ID 28 as source-list membership/projection, keep ID 29 for single-source canonical persistence/incognito write, and complete ID 30/37 fixed-main presentation symbols plus current-consumer/Desktop-adapter mappings and inventory blobs. Append—not rewrite—OpenSpec 2.3 closure to the authority audit, remove remaining active-manifest/proposal “Android authoritative/original” ambiguity, and keep all entries at most `WIRED` until current runtime verification. Contract tests must reject missing upstream symbols, wrong fixed-main blobs, current-app authority, and evidence assigned to the wrong capability.
- Evidence: commit `9487514ce`; capability contract 25/25, manifest 64 entries, fixed-main inventory 71/71 real blobs, JSON parse, OpenSpec strict, Comet guard and root Spotless GREEN. IDs 28/29/30/37 now have distinct fixed-main/current-consumer/Desktop-adapter evidence, active authority terminology is unambiguous, and runtime/checkoff items remain pending. Independent review APPROVED with Critical 0 / Important 0 / Minor 0.

##### Task 7D24: Latest Android source/extension runtime verification

- Risk axis: `latest-android-source-extension-runtime`
- Platform boundary: `verification`
- Estimated scope: `2 files, 120 lines`
- Verification: build and deploy the latest current Android consumer to the project-managed emulator after shared ordering/error/query changes. Reverify representative extension loading plus global-search source order, first-page empty feedback, append-empty retry/recovery and the affected browse path. Record the exact HEAD/APK/device/API/evidence; this runtime evidence validates the current consumer only and must not be described as original Mihon authority.
- Evidence: complete current-consumer Android runtime evidence is recorded in `docs/superpowers/reports/2026-07-20-align-sources-extensions-android-runtime.md`. The hashed base APK, two real extension fixtures, Trust→source membership, source/global ordering, affected Browse entry, failure→Retry wiring and real MangaDex first-page `No results found` all passed. Task 7D24R/7D24F supply the remaining deterministic production-wiring append-empty recovery evidence. OpenSpec 4.4 is complete; 3.4.3 remains pending only for the Windows/macOS Desktop verification and cross-check. None of this evidence is fixed-main original authority.

##### Task 7D24R: Deterministic Android empty-page recovery verification

- Risk axis: `android-empty-page-runtime-verification`
- Platform boundary: `verification`
- Estimated scope: `3 files, 180 lines`
- Verification: use the existing Android Browse instrumentation and the production `SharedSourcePagingSource`/Compose/Pager wiring to prove localized first-page no-results plus page-2 empty preserving page-1 rows, exposing Retry, retrying the same page and rendering recovered content. The test must fail if production append-empty mapping or visible retry wiring is bypassed; real-extension loading remains separately evidenced and the controlled source must not be described as an original Mihon fixture.
- Evidence: real MangaDex first-page empty passed through the verified ADB reverse/global-proxy path. Task 7D24R produced the corrected four-test APK against real production `SharedSourcePagingSource`/Pager/Compose/Scaffold wiring, but its original AVD remained offline; Task 7D24F then ran that exact hashed APK unchanged on a fresh AVD and obtained `OK (4 tests)`. The controlled source is deterministic runtime wiring evidence, not an original Mihon fixture.

##### Task 7D24F: Fresh-emulator Android empty-page runtime verification

- Risk axis: `fresh-android-emulator-verification`
- Platform boundary: `verification`
- Estimated scope: `5 files, 240 lines`
- Verification: take over the already compiled Task 7D24R base/test APKs without running Gradle or changing production/test design. Create a fresh project-managed API 36 AVD with cold boot and snapshots disabled to isolate the prior offline emulator failure, install both APKs, and run all four `BrowseSourceUiWiringTest` cases. Record exact APK hashes, device/API and instrumentation output. The controlled source proves current Android consumer production `SharedSourcePagingSource`/Pager/Compose/Scaffold wiring only; it is not an original Mihon fixture or fixed-main authority. The single repair review adds one tracked raw-evidence file so the claimed fresh run is independently auditable; the resulting five-file scope remains below the split threshold and does not need a split waiver.
- Evidence: repair-review fresh cold/no-snapshot AVD `mihon-7d24f-repair-api36` (`emulator-5556`, `sdk_gphone64_x86_64`, Android 16 / API 36) reached `device` and `sys.boot_completed=1`. Base SHA-256 `8e1892fe68cdcd1138ccce96517391b22401d49894ab04af76e8069be82b3460` and test SHA-256 `1fce797f052d35834e6cd56df58bd2946ae5c8958462be301b3c0f321ec74773` both installed with `Success`; the unchanged targeted instrumentation finished in 17.567s with `OK (4 tests)`. The complete commands, device identity, install output and instrumentation stdout are tracked in `docs/superpowers/reports/evidence/2026-07-20-7d24f-android-runtime.txt`. It proved localized first-page empty plus append-empty preserving rows, visible Retry, a second request for the same page and recovered content through production wiring. OpenSpec 4.4 is checked; 3.4.3 remains pending for Desktop.

##### Task 7D25: Android SYSTEM downgrade rollback closure

- Risk axis: `android-system-downgrade-rollback`
- Platform boundary: `android`
- Estimated scope: `5 files, 360 lines`
- Verification: write a production-gateway RED that installs a higher-version candidate, forces post-commit reload or cleanup failure, and proves the lower-version signed snapshot is physically restored before runtime reload. The recovery path must use bounded PackageInstaller/uninstall callbacks, validate package/signers/version after restoration, preserve trust metadata, and fail visibly if uninstall, reinstall, verification or reload fails. Fake file-copy gateways are supplementary only; the completion evidence must include a project-managed emulator PackageInstaller/instrumentation run. Reopen Task 4D until this test and its independent review pass.
- Evidence: implementation `f02fd92e9` plus repair `d0183b84e`. The API 36 RED reached real PackageInstaller and failed with `INSTALL_FAILED_VERSION_DOWNGRADE`; GREEN physically restored the same-signer v2 snapshot after v3 reload failure and externally recorded PackageManager v2/signer evidence. The repair RED proved pre-commit rollback removed a healthy package; GREEN compares topology, package/extension identity, version, signers, artifact digest, trust and loader origin before deciding whether recovery is a no-op or a physical restore. Security/rollback 23/23, session/coordinator 34/34, Spotless and device instrumentation passed. Independent review APPROVED with Critical 0 / Important 0 / Minor 0, so Task 4D is restored to complete.

##### Task 7D26: Android same-package retry flight handoff

- Risk axis: `android-same-package-retry-flight`
- Platform boundary: `shared+android`
- Estimated scope: `4 files, 320 lines`
- Verification: add a public `downloadAndInstall` RED that holds request A at platform handoff, immediately submits a same-package request B with a distinct immutable artifact/version, and proves B cannot subscribe to A's coordinator flight or publish A's terminal. The implementation must await A's bounded cancel/rollback/cleanup completion before B starts, or use an equivalent request-identity-safe flight key, while preserving same-request subscriber sharing and different-package concurrency.
- Evidence: commit `5cdcfbada`. Shared RED returned request A's v1 artifact to request B; the public Android RED left B `Idle` after it subscribed to A. GREEN stores the complete immutable request on each flight, shares only equal requests, and waits outside the mutex for a different same-package request's rollback/cleanup completion before retrying acquisition. Coordinator 22/22, Android session/wiring 35/35 and Spotless passed. Independent review APPROVED with Critical 0 / Important 0 / Minor 0.

##### Task 7D27: Desktop update-all trust request queue

- Risk axis: `desktop-update-all-trust-queue`
- Platform boundary: `desktop`
- Estimated scope: `3 files, 280 lines`
- Verification: add a production ScreenModel/UI RED with at least two update-all candidates that concurrently reach `TrustRequired`. Every candidate must remain reachable through a FIFO confirmation flow (or receive an explicit visible cancellation terminal), and confirm/reject must clear that package's action/install step before advancing. No package may remain indefinitely `Pending`/`Installing`; single-update and Desktop-specific file/transaction actions must remain unchanged.
- Evidence: implementation `cd041553d` plus repair `bf4aa2bb5`. The original RED showed the second trust request overwrote the first; repair REDs proved early next-dialog publication, missing active-request cleanup on throw/cancel/close, and a non-atomic StateFlow update that erased a concurrent storage error. GREEN separates `activeTrust` from the FIFO queue, advances only after terminal cleanup, drains active and queued requests on close, and uses atomic updates. Focused repair 5/5, ScreenModel 12/12, UI 7/7 and Spotless passed. Independent review APPROVED with Critical 0 / Important 0 / Minor 0.

##### Task 7D28: Final verification determinism and stale evidence cleanup

- Risk axis: `final-verification-determinism`
- Platform boundary: `verification`
- Estimated scope: `7 files, 180 lines`
- Verification: the first final `build-desktop.sh full-tests` run completed 1806 tests with 46 failures. Focused reruns proved the Reader/network contract failures were socket-resource cascades, while three deterministic failures came from a refresh test accepting the initial empty projection, a DI transaction fixture bypassed by the new signer requirement, and a baseline missing fixed-main `BrowseTab`. Keep the HTTP behavioral assertions but wait for the requested package; isolate artifact authentication in the already dedicated authenticity suite while the DI test continues through the production manager transaction; restore `BrowseTab` to the fixed-main baseline. Remove the obsolete live ManHuaGui “Application unsupported” hypothesis already superseded by immutable real-fixture evidence, and make the assertion-free live repository survey opt-in via `-PincludeNetworkSurveyTests=true` so full verification uses deterministic fixtures and does not consume unbounded host sockets. Run the focused regression set, Spotless, then the exact full-tests wrapper again.
- Evidence: commit `77927e90c`; focused signer/immutable-fixture/DI/baseline/HTTP matrix 22/22, Spotless and Comet guard passed. Independent review APPROVED with Critical 0 / Important 0 / Minor 0. The exact full-tests run reduced the original 46 failures to two and exposed another unclassified online survey plus a mutable live chapter-count assertion, which were split into 7D28R.

##### Task 7D28R: Remaining live-integration determinism repair

- Risk axis: `live-integration-determinism`
- Platform boundary: `verification`
- Estimated scope: `3 files, 75 lines`
- Verification: the first post-7D28 exact full-tests run reduced failures from 46 to 2. Tag the second assertion-free Keiyoushi whole-repository download/conversion survey with the same explicit `network-survey` opt-in. The live MangaDex large-feed call first passed with the upstream total changed from 1899 to 1901, then timed out in a focused rerun, proving both data and latency are unsuitable as fixed evidence. Replace only that large-feed fixture with a localhost 1901-chapter/four-page HTTP integration that still executes production OkHttp, JSON parsing, pagination and the unchanged 30-second `safeSourceCall` boundary, and assert exact `0/500/1000/1500` offsets plus all 1901 results. Keep the smaller live MangaDex product smoke tests. Run the focused test, Spotless and the exact full-tests wrapper; no production code or timeout changes are allowed.
- Evidence: commit `2a675747d`; the deterministic 1901-chapter/four-page production integration passed in 7 seconds, Spotless and Comet guard passed, and the single repair re-review APPROVED with Critical 0 / Important 0 / Minor 0. The next exact full-tests run completed 1803 tests with two failures: a remaining live MangaDex smoke could not connect, while `GlobalSearchResultProductionWiringTest` still had only the B-source subscription after 30 seconds. Because the repair/re-review allowance was exhausted, work stopped and was replanned as 7D29/7D30 rather than extending 7D28 again.

##### Task 7D29: Deterministic versus live network verification layers

- Risk axis: `network-test-classification`
- Platform boundary: `verification`
- Estimated scope: `5 files, 100 lines`
- Verification: classify localhost/MockWebServer and immutable APK fixtures as deterministic integration, real MangaDex assertions as `live-network`, and assertion-free Keiyoushi repository downloads as both `live-network` and `network-survey`. `full-tests` must continue to include all deterministic integration while excluding public-service availability. An explicit live run must opt in with `-PincludeLiveNetworkTests=true`, pass proxy host/port from `MIHON_LIVE_PROXY_HOST`/`MIHON_LIVE_PROXY_PORT` (default `127.0.0.1:10808`) into the test JVM's HTTP/HTTPS proxy properties, bypass localhost, and pass the two MangaDex smoke tests. Survey tests additionally require `-PincludeNetworkSurveyTests=true`. Network failure gets one proxy check/retry and remains a separate environment/runtime gate, not a unit-suite regression.
- Evidence: commit `141102a89`. The configured `127.0.0.1:10808` proxy returned HTTP 200 from MangaDex `/ping`. The explicit live class run executed all 7 deterministic plus live MangaDex cases with 0 failures in 45.272 seconds, and its Gradle worker command line contained the HTTP/HTTPS proxy properties and localhost bypass. Re-running the same class without `-PincludeLiveNetworkTests=true` executed only the 5 deterministic cases with 0 failures. Both Keiyoushi surveys now require the combined live-network and network-survey opt-ins. Root Spotless and Comet plan guard passed; independent review APPROVED with Critical 0 / Important 0 / Minor 0.

##### Task 7D30: Original-Mihon transaction safety at the Desktop JDBC boundary

- Risk axis: `global-search-persistence-serialization`
- Platform boundary: `shared+desktop`
- Estimated scope: `5 files, 250 lines`
- Verification: fixed main `6fbf6dfc` no longer contains the former custom Android `TransactionContext`, because upstream commit `8e0c911f9` moved current Android transaction safety to the AndroidX SQLDelight driver. The earlier original-Mihon implementation at `0024278f4` serialized outer transactions at the data-handler boundary and reused the active context for nested transactions. Desktop cannot reuse the AndroidX driver and its JVM JDBC handler currently lacks equivalent protection, allowing source materialization and any other handler consumer to overlap outer transactions. Add a deterministic real-JDBC handler RED that blocks transaction A, attempts transaction B and proves B cannot enter until A releases, while a nested transaction inside A completes without deadlock. GREEN puts a per-handler mutex around only outer `inTransaction` dispatch and carries an owner context element for nested reuse; do not serialize non-transactional work or source HTTP requests. Keep the mounted GlobalSearch regression, add terminal result/cause diagnostics, and directly assert stale-generation rejection after the old transaction completes but before the new transaction is allowed to publish. Run handler, GlobalSearch and SourceBrowse focused tests, the data JVM suite, then exact full-tests. Do not add sleeps or single-thread the fixture.
- Status: initial commit `2c65dfb87` placed the mutex in one materializer and its first thorough review correctly rejected both the architecture boundary and a weakened stale-generation assertion. Repair commit `9e04b9c40` moved the mutex to `JvmDatabaseHandler`, corrected provenance and restored the stale-result gate; handler 2/2, GlobalSearch 2/2, SourceBrowse 3/3, data JVM 15/15 and Spotless passed. The single allowed repair re-review confirmed both original findings closed but found the JDBC transaction body could move away from the driver's thread-local transaction, nested rollback semantics were lost, caller cancellation was not propagated and A-handler → B-handler → A-handler could deadlock. Task 7D30 is therefore not approved and must not be treated as closure; remaining work is replanned as Task 7D30B.

##### Task 7D30B: Thread-confined Desktop JDBC transaction context

- Risk axis: `desktop-jdbc-transaction-context`
- Platform boundary: `shared+desktop`
- Estimated scope: `4 files, 320 lines`
- Verification: replace the incomplete 7D30 handler element with a Desktop/JVM adaptation of original Mihon's mature suspending transaction context. Acquire the per-handler outer mutex before a transaction thread; bind `transactionWithResult`, all transaction-body SQL and suspension resumes to that same acquired thread; carry a registry of active handler transaction states so same-handler nested transactions retain SQLDelight child rollback semantics and A-handler → B-handler → A-handler nesting returns to A's active thread without reacquiring its mutex. Tie each acquired thread's control job to caller cancellation and release it on every success, failure and cancellation path. Non-transactional dispatch must remain concurrent and distinct handlers must not share transaction state. REDs must use a deterministic alternating dispatcher plus real JDBC SQL/currentTransaction to detect thread escape, verify an inner failure rolls back the outer write even when caught, verify caller cancellation rolls back and releases the mutex for the next transaction, prove non-transactional work can enter while a transaction is suspended, and complete cross-handler nesting without deadlock. Re-run GlobalSearch/SourceBrowse production wiring, full data JVM tests, Spotless, then exact full-tests. Do not use sleeps, a permanently dedicated thread per handler, or test-only serialization.
- Evidence: commit `65c546b70`. The thread-escape RED first passed before suspension, then deterministically deadlocked after `yield()` resumed the old `runBlocking(queryDispatcher)` body on the alternating dispatcher's other thread; the timed-out Gradle process tree was explicitly terminated before continuing. GREEN ports the original acquired-thread/control-job pattern and adds a coroutine registry keyed by handler, so real JDBC transaction state survives suspension and cross-handler nesting. Seven handler tests pass real SQL/currentTransaction, outer serialization, caught-child rollback, caller-cancel rollback plus next-entry release, non-transactional concurrency and A→B→A nesting. The complete data JVM suite passed 20/20; GlobalSearch 2/2 and SourceBrowse 3/3 passed; root Spotless passed. Independent thorough review APPROVED with Critical 0 / Important 0 / Minor 0. The final deterministic Desktop suite then passed 1801 tests with 0 failures and 2 skipped tests.

##### Task 7D31: Tracking preference Compose convergence fixture

- Risk axis: `tracking-preference-test-convergence`
- Platform boundary: `verification`
- Estimated scope: `2 files, 45 lines`
- Verification: the post-7D30 deterministic full-tests run completed 1802 tests with one unrelated failure: `TrackingAutoSyncPreferenceWiringTest` observed the production preference already set to false but asserted the Compose `collectAsState` projection after only one render, before its Flow emission was scheduled. A focused rerun passed unchanged, confirming a scheduling-dependent fixture. Keep the real production preference and mounted `TrackingSettingsScreen`; after the real click, assert the preference mutation synchronously, then pump rendering with a bounded timeout until the real toggle semantics becomes Off. Always close the scene in `finally`. Do not change production code, add sleeps or relax the final state assertion. Run the focused test, Spotless and the exact full-tests wrapper once.
- Evidence: commit `84f789ddf`. The unchanged focused rerun passed after the full-suite-only failure, confirming scheduler sensitivity. The fixture now asserts the real preference flips synchronously, then pumps the mounted production screen until the real `ToggleableState.Off` semantics converges under a 15-second bound, with unconditional scene cleanup. The modified focused test passed; root Spotless covered the change, and independent review APPROVED with Critical 0 / Important 0 / Minor 0. The exact final deterministic Desktop suite passed 1801 tests with 0 failures and 2 skipped tests.

##### Task 7D32: Download queue ScreenModel dependency boundary

- Risk axis: `download-queue-screen-model-boundary`
- Platform boundary: `desktop`
- Estimated scope: `5 files, 340 lines`
- Verification: the latest Step 6 build allocated `0.11.14.28.70c5b4c` but stopped before packaging because the fresh Desktop suite ran 1810 tests with one failure: `DesktopArchitectureGuardTest` observed `DownloadQueueScreen.kt` calling `ChapterRepository` directly. This is committed production code, not the unrelated uncommitted whitespace hunk. Follow fixed-main `DownloadQueueScreen -> DownloadQueueScreenModel -> DownloadManager` ownership: first retain the architecture failure as RED, then add a Desktop ScreenModel outside the Composable/UI package that owns the existing queue controls and canonical chapter-metadata sort, construct it through `DesktopUiDependencies`, and make the Screen emit intents only. Do not whitelist the file, move the repository call into a UI helper, duplicate the original sort rule, or remove Desktop pause/resume/retry/clear/grouping behavior. Update the existing order-policy and real Compose wiring tests so bypassing the ScreenModel or repository-backed ordering fails. Run the focused order/Compose tests, `DesktopArchitectureGuardTest`, root Spotless and the Comet guard; after independent review, rerun Step 6 from a newly allocated BUILD because BUILD28 produced no accepted EXE.
- Evidence: implementation `00846fd7b` and lifecycle repair `9cd771774`. The architecture guard first failed 1/4 on the direct UI repository call; boundary/Compose tests then failed compilation only because the required ScreenModel/factory did not exist. GREEN routes the real Compose screen through `rememberScreenModel` and `DesktopUiDependencies`; the non-UI model owns canonical chapter-metadata sorting and every queue intent while the manager retains source-local ordering, active-download stability and cross-source drag rejection. Review found one detached injected test scope; its mutation RED changed state from expected `[1]` to `[1, 2]` after caller cancellation, and the repair binds a child `SupervisorJob` to the caller without allowing dispose to cancel the caller. Final lifecycle 1/1 and affected order/Compose/architecture/manager matrix 26/26 passed; root Spotless, diff and Comet guard passed; the independent repair re-review APPROVED with Critical/Important/Minor `0/0/0`. Cumulative scope is 5 files and 337 touched lines. BUILD28 remains an intentionally unaccepted failed build, so Step 6 must allocate a newer BUILD.

##### Task 7D33A: Windows transient atomic-replace policy

- Risk axis: `windows-atomic-replace-retry`
- Platform boundary: `desktop`
- Estimated scope: `4 files, 220 lines`
- Verification: Step 6 BUILD29 ran 1811 Desktop tests and failed `ExtensionArtifactReplacementTest` when Windows returned `AccessDeniedException` for the atomic `.replace.tmp -> extension.jar` move. Reuse the existing bounded three-attempt/10ms `AccessDeniedException` retry semantics from `FileTaskCheckpointStore` by extracting one Desktop platform helper and routing both checkpoint and extension replacement through it; do not duplicate retry loops. RED must inject the first two access-denied failures and prove the third atomic move succeeds without consuming the snapshot or leaving `.replace.tmp`; another case must exhaust the bound, preserve the destination/snapshot, delete the temporary replacement and rethrow the last access-denied error. `AtomicMoveNotSupportedException` and non-access-denied failures must remain immediate failures, because silently falling back would weaken the extension transaction's atomicity. Run extension replacement/transaction tests, checkpoint-store tests, root Spotless and the Comet guard, then independent review.
- Evidence: commit `7d668b0ee`. Two injected tests first failed because the old extension filesystem stopped on the first transient access denial and attempted the persistent failure only once. GREEN extracts `retryTransientAccessDenied` as the single existing Desktop three-attempt/10ms policy and routes both checkpoint persistence and extension atomic replacement through it. The real NIO extension tests prove third-attempt success, three-attempt exhaustion with the last exception identity, snapshot/destination preservation and temporary cleanup; unsupported atomic move and ordinary I/O remain one-attempt failures, and extension replacement has no non-atomic fallback. Focused 6/6 and replacement/transaction/checkpoint matrix 74/74 passed; root Spotless, diff and Comet guard passed; independent review APPROVED with Critical/Important/Minor `0/0/0`. Scope: 4 files, 117 touched lines.

##### Task 7D33B: Extension-details Compose convergence fixture

- Risk axis: `extension-details-compose-convergence`
- Platform boundary: `verification`
- Estimated scope: `1 file, 30 lines`
- Verification: the same BUILD29 run failed only after the real localized uninstall click because `DesktopExtensionDetailsActionCopyTest` rendered one frame and immediately required the confirmation dialog while the test's other asynchronous UI boundaries already use a bounded `awaitText`. Preserve the real `ExtensionDetailsScreen`, click, localized title/body/actions and uninstall failure assertions; replace only the single-frame dialog assertion with the existing five-second render/yield convergence helper, keep unconditional scene/model cleanup, and prove a wrong/missing localized dialog still times out. No production code, sleeps, timeout inflation or relaxed copy assertions. Run the focused i18n/extension UI tests, root Spotless and the Comet guard, then independent review.
- Evidence: commit `5c6b95320`. BUILD29 supplied the real full-suite RED: after the localized uninstall click, one render still exposed only the details page and missed `确定删除？`. The one-line repair waits for the localized confirmation title through the existing five-second render/yield helper, then retains exact title/body/uninstall/cancel and uninstall-failure assertions for both locales. Focused 1/1 and target-plus-presentation 8/8 passed; root Spotless, diff and Comet guard passed; independent review APPROVED with Critical/Important/Minor `0/0/0`. One unchanged `ExtensionPresentationUiTest` trust-confirm MockK failure appeared only on the first adjacent combination and did not reproduce in the unchanged single or combined reruns; it has no call-path relationship to this details/uninstall test and becomes a separate Task only if it recurs.

##### Task 7D34: Global-login retry fixture convergence

- Risk axis: `global-login-retry-convergence`
- Platform boundary: `verification`
- Estimated scope: `1 file, 80 lines`
- Verification: Step 6 BUILD30 ran 1814 Desktop tests and only `SourceSharedStateWiringTest.global login action uses the shared dialog and retries its failed child request` timed out after two seconds while waiting for `SourceQueryState.Content`. The test performs a real localized dialog interaction, commits a cookie and retries through localhost MockWebServer, but its final wait neither pumps the mounted Compose scene nor records whether the second request started. Treat the full-suite failure as RED and keep production unchanged. Replace that boundary with a five-second bounded render/yield convergence that requires both the second localhost request and the exact child state to reach Content, and include request count/current state/rendered semantics in any failure. Preserve the same child identity, failed request identity, first request without Cookie, second request with the exact committed Cookie, and final `Routed (1)` UI assertions. Put scene cleanup in `finally` so a failed wait cannot pollute later tests. Run the focused class with `--rerun-tasks`, an adjacent source/global-search combination, root Spotless and the Comet guard, then independent review; no sleeps, production changes, unbounded waits or relaxed assertions.
- Evidence: commit `6f03f9422`. BUILD30 supplied the full-suite RED: 1814 tests with this single failure and two skips; the target class was 49 tests with one two-second timeout. The test-only repair keeps the real 403-to-200 server flow, localized dialog, invalid-header feedback, cookie commit and state Flow; a five-second render/yield boundary now requires request count two, global Content and the same captured child Content, with request/state/semantics diagnostics. Same child/request, no-Cookie then exact-Cookie requests and final `Routed (1)` remain asserted, and all scene/content jobs are cleaned in `finally`. Focused 49/49 and adjacent source/global-search 51/51 passed; root Spotless, diff and Comet guard passed; independent review APPROVED with Critical/Important/Minor `0/0/0`. Scope: 1 verification file.

##### Task 7D35: Cross-platform real-extension fixture isolation

- Risk axis: `cross-platform-extension-fixture-isolation`
- Platform boundary: `verification`
- Estimated scope: `5 files, 180 lines`
- Verification: Step 6 BUILD31 passed the Windows deterministic suite (1814 tests, 0 failed, 2 skipped) and smoke suite (88/88), but the same fresh macOS suite failed four immutable real-extension tests. The three localhost failures used the default `DesktopPreferenceStore()` and therefore inherited the installed user's `/mihon/doh_provider`; on this Mac the production client used `DnsOverHttps`, which correctly rejects MockWebServer private hosts. The ManHuaGui target-host fixture cloned the production client and replaced DNS but still inherited the host OS proxy route, returning HTTP 503 instead of the queued 200. Treat the macOS suite as RED. Give every affected test an isolated preference node with deterministic DoH OFF, remove that node in `finally`, and for the public-host-to-MockWebServer route override only fixture transport DNS/proxy while retaining the loaded extension's production interceptors and original target host. Do not change production DoH/proxy behavior, replace the extension client with a plain client, use public network, or weaken real converter/loader/parser/interceptor assertions. Run the four focused tests on Windows and macOS, the adjacent immutable-extension matrix, root Spotless and Comet guard, then independent review. BUILD31 remains rejected; after closure rerun Step 6 with a newly allocated BUILD on both platforms.
- Evidence: commits `550bd0722` + `f9a76b4b0`. Four affected tests now construct a UUID-backed `DesktopPreferenceStore` node, so production DI reads deterministic DoH OFF and removes the child node after each test. ManHuaGui retains the loaded extension client's interceptors, original target host and production DNS for every non-fixture host while overriding only the fixture host plus `Proxy.NO_PROXY`. Windows focused evidence remained 5/5 on the identical staged patch; the exact patch-id macOS snapshot passed focused 5/5 and the adjacent immutable `RealExtension*CompatTest` matrix 11/11. The repair focused PageList test executed 2/2; root Spotless, diff check and Comet guard passed. Independent review initially found one Important DNS-fallback scope violation; the single repair restored `sourceClient.dns`, and re-review APPROVED with Critical/Important/Minor `0/0/1`. The remaining Minor is test-diagnostic only: a Preferences backend cleanup exception could mask the primary test exception; normal-path cleanup and isolation are correct, so it is recorded without blocking. BUILD31 remains rejected; Step 6 must allocate BUILD32 from the post-repair HEAD on Windows and macOS.

##### Task 7D36: Source-list Compose preference lifecycle isolation

- Risk axis: `source-list-preference-lifecycle-isolation`
- Platform boundary: `verification`
- Estimated scope: `1 file, 60 lines`
- Verification: BUILD32 passed the Windows build, JVM suite, fixed-EXE title, smoke 88/88 and source/extension Test Mode path, but the same fresh macOS suite completed 1814 tests with one failure before packaging. `DesktopChallengeLoginWiringTest` received `UncaughtExceptionsBeforeTest`; the underlying exception came from the preceding `SourceLastUsedWiringTest`, whose `finally` called `scene.close()` and immediately removed its Preferences node while a Compose preference collector could still start and call `addPreferenceChangeListener`, producing `IllegalStateException: Node has been removed`. Treat this macOS full-suite failure as RED. Give every mounted source-list fixture a dedicated child coroutine lifecycle, close the real scene, cancel and join that lifecycle before removing its Preferences node, and keep the real `BrowseSourceListScreen`, navigation, last-used preference reactivity and incognito assertions unchanged. Do not catch or suppress the listener-add failure in production, delay/sleep, retain the node, serialize the whole suite, or modify Challenge Login. Run the affected source-last-used focused class on Windows and an exact-patch macOS clone, root Spotless and Comet guard, then independent review. BUILD32 remains rejected; after closure allocate a new BUILD from the post-repair HEAD for the single final Windows/macOS Step 6 rerun.
- Evidence: commit `2cd33545e`. Each mounted source-list fixture now owns a child Job of the test coroutine, passes that Job into the real `ImageComposeScene`, and closes the scene plus `cancelAndJoin`s the child before removing Preferences. Both mounted navigation/reactivity and incognito scenario cleanup paths use the same awaited boundary; production preference handling, real screen/navigation and assertions are unchanged. Windows focused passed 3/3; a fresh macOS clone at the exact parent plus a SHA-256-identical single-file overlay passed 3/3. Root Spotless, diff check and Comet guard passed. Independent review APPROVED with Critical/Important/Minor `0/0/0`, confirming the child replaces only the Job element, cannot cancel the parent and covers all mounted fixture paths. BUILD32 remains rejected; Step 6 must allocate BUILD33 from the post-repair evidence HEAD.

##### Task 7D37: Global-search Compose filter convergence fixture

- Risk axis: `global-search-compose-fixture-lifecycle`
- Platform boundary: `verification`
- Estimated scope: `1 file, 80 lines`
- Verification: BUILD33 executed the Windows fresh Desktop suite and failed one of 1814 tests before packaging: `GlobalSearchAuthorityWiringTest` clicked the real localized Has Results filter, rendered one frame and immediately required selected semantics, observing false. The subsequent exact row filtering and persisted preference assertions did not run. Treat this full-suite failure as RED. Keep the real `GlobalSearchScreen`, coordinator, click, localized semantics, four source outcomes, row filtering and remount-from-persisted-preference assertions; replace single-frame assumptions after the click and remount with a bounded render/yield wait for exact selected semantics. Because this fixture removes a Preferences node and remounts a scene in the same test, give each scene a child Job and close plus cancel/join it before replacing the scene or removing the node, matching the proven Task 7D36 boundary. Do not add sleeps, enlarge global timeouts, suppress assertion failures, catch production exceptions, serialize the suite or change production UI/state. Run the focused class on Windows and an exact-file macOS clone, root Spotless and Comet guard, then independent review. BUILD33 remains rejected; after closure allocate a new BUILD from the post-repair evidence HEAD for the final Windows/macOS Step 6 rerun.
- Evidence: commit `ceb0200a5`. The real localized Has Results click and persisted remount now wait up to two seconds for exact `Selected=true` semantics by rendering and yielding; all four source outcomes, exact visible/hidden rows, progress and preference assertions remain. Each scene uses a child Job and is closed plus joined before replacement or Preferences removal. Windows focused passed 1/1; an exact-parent macOS clone with a SHA-256-identical single-file overlay passed 1/1. Root Spotless, diff check and Comet guard passed. Independent review APPROVED with Critical/Important/Minor `0/0/0`, confirming the wait is bounded and cannot replace row/persistence evidence, while child Jobs retain the test dispatcher and cannot cancel the parent. BUILD33 remains rejected; Step 6 must allocate BUILD34 from the post-repair evidence HEAD.
- Repair verification: BUILD34 rejected the initial closure after the Windows fresh suite again completed 1814 tests with one failure and two skips. The selected/row/persistence assertions now passed, but after the fixture removed its Preferences root, a delayed `globalSearchFilterState.changes().onStart { get() }` from the real Compose tree threw `Node has been removed` and was reported at the next Challenge test's `runTest` entry. The child Job alone does not prove the Compose tree applied disposal before Preferences deletion. Keep the same Task/risk axis and use its one repair round: explicitly replace the scene content with an empty composition and render that disposal before closing/cancel-joining and removing the node. Use an adjacent `GlobalSearchAuthorityWiringTest` + `DesktopChallengeLoginWiringTest` run as the regression boundary on Windows and macOS, in addition to preserving the focused assertions. Do not catch the preference error, delay after cleanup, retain the node or change production.
- Repair evidence: commit `d3527f6e6`. `closeScene` now installs and renders an empty composition while the Preferences root is still valid, then closes the scene and cancel/joins its child Job; callers remove the node only afterward. The same Windows invocation passed Authority 1/1 followed by Challenge Login 14/14, and the exact-parent/SHA macOS clone passed the same 15/15 boundary. Spotless, diff check and Comet guard passed. Repair re-review APPROVED with Critical/Important/Minor `0/0/0`, confirming the render applies old-tree `collectAsState`/`DisposableEffect` disposal without weakening selected, rows, progress or persistence evidence. BUILD34 remains rejected; Step 6 must allocate BUILD35 from the final D37 evidence HEAD.

##### Task 7D38: Extension reload failure feedback fixture

- Risk axis: `extension-reload-feedback-convergence`
- Platform boundary: `verification`
- Estimated scope: `1 file, 40 lines`
- Verification: BUILD35 passed Windows JVM tests, distributable, exact EXE title, smoke and source/extension Test Mode paths, but the macOS fresh suite completed 1814 tests with one failure before packaging. `ExtensionPresentationUiTest` first proved the real uninstall failure Snackbar and authority-manager routing, then immediately clicked Reload Installed while the first Snackbar was still active; the reload failure Snackbar queued behind it and did not appear within the existing five-second bound. Treat this macOS full-suite failure as RED. Preserve the real `ExtensionListContent`, `ExtensionsScreenModel`, authority/bypass managers, localized uninstall/reload copy and exact call assertions. After proving uninstall feedback, invoke the real Snackbar `SemanticsActions.Dismiss` and bounded-render until it is gone, following the repository's existing Snackbar fixture pattern; only then click Reload Installed and await its exact localized exception feedback. Do not lengthen timeouts, sleep, bypass the ScreenModel, directly mutate reload state, relax call assertions or change production Snackbar behavior. Run the focused class on Windows and an exact-file macOS clone, plus the adjacent extension presentation/copy feedback matrix, root Spotless and Comet guard, then independent review. BUILD35 remains rejected; after closure allocate a new BUILD from the post-repair evidence HEAD for Step 6.
- Evidence: commit `011662ee1`. After the real uninstall failure feedback and exact authority/bypass routing assertions, the fixture invokes the actual Snackbar `SemanticsActions.Dismiss`, requires a true action result and uses the existing five-second render/yield boundary until Dismiss semantics disappears. It then performs the unchanged Reload Installed click and exact Simplified Chinese exception feedback plus authority/bypass assertions. Windows adjacent extension UI classes passed; an exact-parent/SHA macOS clone passed ExtensionPresentation 7/7, DetailsActionCopy 1/1 and DetailsPreferences 4/4 (12/12 total). Root Spotless, diff check and Comet guard passed. Independent review APPROVED with Critical/Important/Minor `0/0/0`, confirming the Dismiss node is the active Snackbar and the change separates two real user actions without bypassing ScreenModel/manager or production queue behavior. BUILD35 remains rejected; Step 6 must allocate BUILD36 from the D38 evidence HEAD.

##### Task 7D39: Desktop cleanup-cancellation fixture linearization

- Risk axis: `extension-cleanup-cancellation-linearization`
- Platform boundary: `verification`
- Estimated scope: `1 file, 35 lines`
- Verification: BUILD36 passed the Windows fresh Desktop suite, distributable, exact fixed-EXE title, smoke 88/88 and source/extension Test Mode paths, but the same fresh macOS suite completed 1814 tests with two failures before packaging. `DesktopExtensionInstallTransactionTest.real cancellation during cleanup restores old runtime and files` timed out waiting for the old runtime after the test called `install.cancel()` and immediately released a blocking `CountDownLatch`. The shared `ExtensionInstallCoordinatorTest` already proves that last-subscriber cancellation waits for rollback, runtime restoration and cleanup; the Desktop fixture instead permits the blocked IO cleanup to complete successfully before the collector coroutine has propagated cancellation to the independently scoped install flight. Treat the macOS full-suite failure as RED. Establish a deterministic coroutine handoff proving cancellation has reached the collector/flight before releasing injected cleanup, then retain the real manager, blocked filesystem phase, unchanged old/new runtime assertions, byte-for-byte file snapshot and zero transaction-file checks. Do not sleep, inflate the two-second bound, weaken cancellation/rollback assertions or change production solely to expose a test hook. If a deterministic reproduction instead proves the coordinator can acknowledge cancellation yet return before restoration, promote this Task to `shared+desktop` and repair that production lifecycle under the existing contract test. Run the focused Desktop transaction cancellation matrix and shared coordinator cancellation tests on Windows and an exact-patch macOS clone, root Spotless and Comet guard, then independent review.
- Evidence: commit `90b6f989d`. The real artifact provider captures its coordinator-owned install-flight `Job`; after cancelling the collector, the fixture keeps the original two-second bound and does not release the blocked cleanup until that flight is observably cancelled. The existing cleanup `ensureActive` therefore enters the unchanged rollback, old-runtime restoration and second cleanup path before `install.join()` returns. Byte-for-byte artifact/metadata snapshots, old/new runtime identities and zero transaction files remain asserted. Windows focused evidence passed the Desktop transaction matrix 42/42 and three shared coordinator cancellation contracts 3/3; root Spotless, diff check and Comet guard passed. Independent review APPROVED with Critical/Important/Minor `0/0/1`; the only Minor is verification scope because the exact-patch macOS snapshot found only a runtime-only JBR and could not start Gradle, so the next fresh macOS full verify must cover this test. No production file, sleep, timeout increase or test hook was added.

##### Task 7D40: Source-browse background signal visibility

- Risk axis: `source-browse-background-signal-visibility`
- Platform boundary: `verification`
- Estimated scope: `1 file, 45 lines`
- Verification: the second BUILD36 macOS failure was `GlobalSearchResultNavigationTest.default source browse still loads popular`, whose two-second render/yield loop continued observing `popularCalls == 0`. The real `CatalogueSource` mock is invoked from the production source query dispatcher, while the fixture records `popularCalls`, filter calls and query lists in unsynchronized mutable fields and reads them from the Compose test coroutine, so the test has no cross-thread visibility guarantee. Treat the full-suite failure as RED. Replace every background-written signal used by this fixture's waits/assertions with concurrency-safe counters/collections or completion signals, while preserving the real `SourceBrowseScreen`, production search service, initial popular-vs-search routing, fresh filter identity, twelve canonical results, lazy-row reachability and subscription assertions. Keep the existing bounded render/yield behavior and exact call/query assertions. Do not increase timeouts, serialize the suite, move production work onto the test thread, mock the ScreenModel, or relax navigation/result assertions. Run the focused class and its adjacent source-browse/global-search navigation matrix on Windows and an exact-patch macOS clone, root Spotless and Comet guard, then independent review. BUILD36 remains rejected; after both tasks close, allocate the next BUILD from their evidence HEAD for Step 6.
- Evidence: commits `ab4616fc2` + `5530a00c7`. Every background-written query/filter list now uses `CopyOnWriteArrayList`, and popular/filter invocation counts use `AtomicInteger`; the second search boundary also waits for both independently published lists to contain two elements before asserting order and fresh `FilterList` identity. The real `SourceBrowseScreen`, production search service and navigator, original two-second render/yield budget, popular-vs-search routing, twelve canonical results, lazy-row reachability and subscription assertions are unchanged. Focused passed 3/3 after the repair; the initial adjacent matrix passed 4 suites / 9 tests with zero failures, errors or skips; Spotless, diff check and Comet guard passed. Independent review first found one Important cross-container publication gap; the one-line repair closed it, and repair re-review APPROVED with Critical/Important/Minor `0/0/0`. The exact-patch macOS focused run was not repeated because the host currently exposes only a runtime-only JBR; the next fresh macOS full verify must cover this test together with 7D39.

##### Task 7D41: Headless macOS Keychain integration prerequisite

- Risk axis: `macos-keychain-headless-prerequisite`
- Platform boundary: `verification`
- Estimated scope: `1 file, 50 lines`
- Verification: BUILD37 passed the fresh Windows suite (1814 tests, zero failures/errors and two skips), distributable, exact fixed-EXE title, smoke 88/88 and source/extension Test Mode paths. The exact HEAD plus BUILD37 macOS snapshot then completed 1817 tests with one unrelated failure: `PlatformCredentialBackendTest.macOS Keychain credential round trip overwrite and delete on current machine` failed on the first save. A separate non-secret diagnostic proved the default login Keychain exists, but both `security show-keychain-info` and the production-identical `security add-generic-password -U ... -w` two-line stdin protocol return exit 36 with `User interaction is not allowed` in the SSH session; no item was created. This is an unavailable interactive Keychain prerequisite, not a source/extension regression or command-protocol failure. Before the real macOS round trip, probe only the current user's default Keychain accessibility and abort that integration test through a JUnit assumption only for the exact exit-36/user-interaction-denied condition. Every other probe/save/load/overwrite/delete failure must remain a test failure, and an accessible Keychain must still execute the complete real backend round trip. Do not catch arbitrary `PlatformCredentialException`, disable integration tests globally, alter production credential behavior, unlock the user's Keychain, request credentials, or treat the skip as proof of GUI Keychain operation. Run the focused credential class on Windows and the exact-patch macOS snapshot, confirm macOS reports precisely this integration case skipped while command-shape/error tests pass, then run Spotless, Comet guard and independent review. BUILD37 remains rejected; after closure allocate the next BUILD for the source/extension Step 6 rerun. The later platform-integration change must still perform interactive GUI Keychain acceptance.
- Evidence: commits `15c4afce9` + `6472e4f2c`. The macOS real-round-trip test now probes `security show-keychain-info` immediately before the production backend call. Only exit 36 whose stderr contains `User interaction is not allowed` triggers a fixed, redacted JUnit assumption; command absence propagates as failure, and a redacted `exitCode == 0` assertion makes every other probe error RED before the unchanged save/load/overwrite/load/delete/finally-delete sequence. Windows integration-focused passed 9/9 with no skips. The exact committed snapshot plus SHA-identical single-file macOS overlay passed 9 tests with exactly the real round-trip skipped and the other eight command-shape/error/compatibility cases green. Spotless, diff check and Comet guard passed. Independent review found one Important gap because the initial helper ignored non-exact probe errors; the repair closed it, and repair re-review APPROVED with Critical/Important/Minor `0/0/0`. This evidence proves only that the SSH session lacks interactive Keychain access; it does not prove GUI Keychain operation, which remains required in the later platform-integration change.

##### Task 7D42: Source last-used Compose convergence diagnostics

- Risk axis: `source-last-used-compose-convergence`
- Platform boundary: `verification`
- Estimated scope: `1 file, 80 lines`
- Verification: BUILD38 passed the fresh Windows suite (1814 tests, zero failures/errors and two skips), fixed distributable/title, smoke 88/88, source/extension Test Mode and the Windows credential integration 9/9. The exact HEAD plus BUILD38 macOS bundle snapshot completed 1817 tests with one failure and one expected Keychain skip: `SourceLastUsedWiringTest.real navigation records last used except for matching global or extension incognito` timed out at a two-second boundary. The class mounts the real `BrowseSourceListScreen` and navigator, but its browse-details and recorder waits still render then sleep a fixed 10 ms inside two-second budgets, while the same class already gives reactive row convergence five seconds. Treat the macOS full-suite failure as RED and keep production unchanged. Replace every fixed-delay Compose pump in this fixture with render/yield scheduling, give the real navigation/recorder boundaries the same bounded five-second convergence budget as row projection, and emit phase/scenario diagnostics containing only rendered state, extension lookup count and last-used value when convergence fails. Preserve all four global/extension-incognito scenarios, exact short-circuit/lookup counts, preference result, back navigation, reactive reordering, localized Last Used label and lifecycle cleanup. Do not sleep, use unbounded waits, serialize the suite, catch assertion/production errors, remove scenarios, relax final assertions or change production state/navigation. Run the focused class and its adjacent source-list lifecycle/challenge-login boundary on Windows and the exact-patch macOS snapshot, Spotless, Comet guard and independent review. BUILD38 remains rejected; after closure allocate the next BUILD for Step 6.
- Evidence: commit `5740cd3b2`. Browse-details, recorder and row projection now share one bounded five-second convergence helper that renders and yields without fixed sleeps. Only timeout cancellation is converted to an `AssertionError` retaining its cause; diagnostics include phase, boolean/index scenario properties, extension lookup count, current last-used ID and at most 1000 normalized rendered-semantics characters. The four global/extension-incognito cases, exact lookup short-circuit/counts, preference result, real back navigation, reactive reordering, localized Last Used label and child lifecycle cleanup remain unchanged. Windows passed SourceLastUsed 3/3 plus adjacent ChallengeLogin 14/14; the exact committed snapshot plus SHA-identical macOS overlay passed the same 17/17, with the former full-suite timeout case completing in 0.385 seconds. Spotless, diff check and Comet guard passed. Independent review APPROVED with Critical/Important/Minor `0/0/0`, confirming the change improves scheduling and diagnostics rather than merely enlarging a timeout. BUILD38 remains rejected; the next fresh full verify is still required.

- [ ] **Step 7: 独立批次与最终审查**

  `review_mode: thorough`：每个高风险边界或最多 3 个 Task 运行合并 spec+quality review，最后对 `852221f42..HEAD` 运行完整审查。Critical/Important 必须修复并重新运行覆盖测试；Minor 记录到持久进度并交最终审查裁定。

  Evidence: the full review found three Important issues and no Critical issue. Tasks 7D25/7D26/7D27 closed real SYSTEM downgrade rollback, same-package request/flight confusion and Desktop multi-trust queue loss. The single final repair review APPROVED with Critical 0 / Important 0 / Minor 1. The remaining Minor is evidence formatting only: the tracked Android transcript contains all four test statuses, `OK (4 tests)` and `INSTRUMENTATION_CODE: -1`, but does not separately print the host adb process exit code; final reporting must retain that limitation.

- [ ] **Step 8: 提交 Task 7**

  Commit: `chore(extension): verify cross-platform source parity`

## 完成门槛

- 本计划与 `openspec/changes/align-sources-extensions/tasks.md` 全部勾选并通过 Comet task-checkoff。
- OpenSpec strict validation 0 issue；独立最终审查 0 Critical/Important。
- Compat inventory 的 `unverified` 为 0；每个 `required`/`unsupported` 均有本地可追溯真实 artifact 与 production invocation/failure 证据，或在 7C 删除后不再属于 public surface。
- Android 与 Desktop production wiring 都消费共享业务类型，旧并行规则与无证据 compat stub 已删除。
- Windows 报告完整版本与固定 EXE 绝对路径；macOS 与 Android 运行时证据可追溯。
- 用户可从 Browse/Extensions 实际完成浏览、搜索、安装/更新、错误恢复、设置与登录挑战；空、加载、错误、取消和数据缺失均有明确反馈。
