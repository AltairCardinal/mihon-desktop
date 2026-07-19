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
- [ ] Task 6E：Test Mode、导航与自动化观察
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
- Produces: 真实 Android/Desktop 权威类映射、代表性 JAR/APK fixture 清单、compat evidence schema、后续共享类型的测试输入。
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
- Estimated scope: `4 files, 180 lines`
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

- [ ] RED：真实 HTTP server 仍返回空扩展状态，空 action 不改变 production model。
- [ ] GREEN：暴露稳定 DTO 并转发真实 controller intent。
- [ ] Verify: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.test.http.SourceExtensionTestModeHttpTest" --tests "mihon.desktop.test.http.TestHttpServerJsonTest"`
- [ ] Commit: `test(desktop): expose extension production state`

#### Task 6E3B3: Extension Test Mode client 与 Robot contract

- Risk axis: `extension-testmode-client-contract`
- Platform boundary: `desktop`
- Estimated scope: `3 files, 220 lines`
- Verification: client 能安全序列化包含引号/反斜杠的 query，解析 nested extension DTO；Robot 按 `packageName` 发送全部真实动作且不得吞掉 `ActionResult.success=false`。
- Files: `MihonDesktopTestClient.kt`、`MoreRobot.kt`，新增 `SourceExtensionClientContractTest.kt`。
- Boundary: 删除 server/Robot 中 legacy `extension_select/enable/disable` 空操作与 index API；select 已由真实导航契约覆盖，enable/disable 在 fixed main 属于 Source 管理，后续只能以 `source_toggle/source_pin` 调用真实 Source model。

- [ ] RED：legacy index/no-op API 与手拼 JSON 不符合新契约。
- [ ] GREEN：客户端与 Robot 仅暴露稳定、真实的 production action。
- [ ] Verify: `./gradlew :test-desktop:test --tests "*SourceExtensionClientContractTest"`
- [ ] Commit: `test(desktop): align extension automation client`

#### Task 6E4: Source 状态与登录取消 Test Mode wiring

- Risk axis: `source-login-automation-wiring`
- Platform boundary: `desktop`
- Estimated scope: `6 files, 320 lines`
- Verification: Source query/loading/error/recovery 取自真实 query state；活跃登录 host/feedback/terminal 取自真实 `DesktopSourceLoginUiState`；HTTP cancel 经真实 `DesktopSourceLoginUiActions`/controller 取消当前 attempt，取消后 UI 与 HTTP 状态同时消失。错误 attempt、无活跃登录和已终止登录不得伪造成功。原版 Source toggle/pin 若纳入自动化，必须使用明确的 `source_toggle/source_pin` 并调用真实 Source model，不得伪装成 extension action。
- Files: `SourceBrowseScreen.kt`、6E2 的 test-mode controller/server/client DTO、app-desktop HTTP/Compose wiring 测试、test-desktop 客户端契约测试。
- Boundary: 只在当前 `SourceBrowseScreen` 生命周期注册/注销薄 observation port；不得把 Desktop 登录状态移入 shared authority，也不得用 Android platform shim 代替 fixed main 的登录语义。

- [ ] RED：真实 Screen 有登录 attempt 时 HTTP 不可见/不可取消。
- [ ] GREEN：composition 生命周期注册真实 UI port，HTTP 仅转发 cancel 并读取 UI state。
- [ ] Verify: `./gradlew :app-desktop:jvmTest --tests "*SourceLogin*TestMode*" :test-desktop:test --tests "*SourceLogin*"`
- [ ] Commit: `test(desktop): expose source login cancellation`

#### Task 6E closure

- [ ] 串行运行 6E1–6E4 focused tests，再运行 `./gradlew :app-desktop:jvmTest --tests "*Navigation*ContractTest" --tests "*I18n*" :test-desktop:test`。
- [ ] 独立审查确认 HTTP interface 真实触达 production wiring，且 fixed main、当前 Android consumer 与 Desktop adapter 没有概念混淆。
- [ ] Check off OpenSpec 2.3、3.4、3.5 的自动化观察部分并提交证据。

### Task 7: compat 去重、parity 证据、全量审查与跨平台运行时验收

**OpenSpec mapping:** 4.1、4.2、4.3、4.4、4.5、4.6

**Risk axis:** parity-evidence
**Platform boundary:** verification
**Estimated scope:** 47 files, 1950 lines
**Verification:** 运行 compat 契约、全量 Gradle/桌面测试构建和 Android/Windows/macOS 运行时验收，并完成 thorough 独立审查。
**Execution split（C11）:** 当前 Desktop Android/AndroidX compat 至少 40 个文件、41 个顶层 public 类型，而 `compat-evidence.json` 只有一条且为 `unsupported`；`AndroidCompatTest` 和 parent-classpath `MinimalTestSource` 只能证明 adapter 自测，不能证明真实扩展需要某 shim。固定 main 的 `Source` ABI 与 Android `ExtensionLoader` 是兼容目标，Desktop shim 永远只属于平台 adapter。原 7 files/350 lines 不能承担逐符号证据与删除，拆分如下。
**Split waiver:** 47 files/1,950 lines 是 inventory、真实 fixture、多个 package prune 批次与最终运行验收的聚合上界；任何单次调度仍不得超过 8 files/400 lines，且删除批次必须逐包消费前序真实证据，无法作为一个原子 Task 执行。

#### Task 7A: Compat public surface inventory

- Risk axis: `compat-public-inventory`
- Platform boundary: `verification`
- Estimated scope: `4 files, 250 lines`
- Verification: 每个 public compat symbol 在清单中恰有一项；清单明确 `required`/`unsupported`、fixture 与 production 调用测试，单元自测不得升级为真实扩展证据。

#### Task 7B: Compat real fixture evidence

- Risk axis: `compat-real-fixture-evidence`
- Platform boundary: `verification`
- Estimated scope: `6 files, 350 lines`
- Verification: 真实 APK/JAR 必须通过 production converter/loader 并实际调用所声明 symbol；parent classpath fixture、仅加载 class 或网络调查输出不算 `required` 证据。

#### Task 7C: Compat package prune batches

- Risk axis: `compat-package-pruning`
- Platform boundary: `desktop`
- Estimated scope: `8 files, 400 lines`
- Verification: 按 `android.content`、`android.os/util`、`androidx.preference` 等包分批；只有 production/真实 fixture 均无调用才能删除，每批运行对应 loader/fixture 回归，不把 shim 移入 shared authority。

#### Task 7D: Parity evidence and runtime verification

- Risk axis: `parity-runtime-evidence`
- Platform boundary: `verification`
- Estimated scope: `7 files, 350 lines`
- Verification: 仅消费 7A–7C 已闭合证据更新 parity 28–40、87，再运行全量测试、Android/Windows/macOS 验收与 thorough review；结构化 provenance 必须区分 fixed main、shared output、当前 Android consumer 与 Desktop adapter。

**Files:**
- Modify/Delete: 由 `compat-evidence.json` 审计确认无调用的 Desktop compat 符号；不得凭猜测删除。
- Create: `app-desktop/src/test/kotlin/mihon/desktop/extension/CompatEvidenceContractTest.kt`
- Modify: `app-desktop/src/test/resources/parity/parity-manifest.json`
- Modify: `docs/desktop-parity/PARITY_TRACKER.md`
- Modify: `docs/roadmap/extension-diagnostics-baseline.md`
- Modify: `docs/automation/TASK_TRACKER.md`（仅当 Test Mode 场景变化）
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/AppVersion.kt`（只由 build script 自动更新）

**Interfaces:**
- Consumes: 全部 Tasks 1–6 production/test evidence。
- Produces: parity 28–40、87 的真实状态/实现路径/保护测试，Windows/macOS/Android 运行时证据，完整独立审查结论。

- [ ] **Step 1: 写 compat public surface RED 测试**

  扫描 compat 包 public 符号，要求每个符号在 `compat-evidence.json` 恰有一项，fixture/test 路径存在，`required` 项测试可触发真实调用；清单外 public API 或不存在测试必须失败。

- [ ] **Step 2: 运行 RED 并删除无证据重复实现**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.extension.CompatEvidenceContractTest"`
  Expected: 初次 FAIL 并列出清单外/无证据项。逐项用 production 引用和真实 fixture 判定：有证据则补测试，确认无调用才删除；同时删除已经由共享 core 覆盖的搜索、版本、错误字符串和事务规则。

- [ ] **Step 3: 更新 parity 28–40、87**

  IDs 28–40 的 manifest completion gate 使用结构化 provenance：`upstreamRef` 必须精确固定到 main，`upstreamSymbols` 的每个 path 在该 git tree 中存在，shared/current Android/Desktop 路径数组逐项验证，每个 deviation 对象独立携带允许 classification 和非空说明。`authoritativeImplementation` / `desktopImplementation` 仅保留兼容，不能作为完成证据；`protectionTests` 仍必须引用真实测试路径。状态只提升到证据支持的 CHARACTERIZED/SHARED/WIRED/VERIFIED，不把平台 adapter 当作业务豁免。

- [ ] **Step 4: 运行全量自动验证**

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

- [ ] **Step 5: Android 模拟器运行时验收**

  自行启动 API 36 x86_64 AVD，`assembleDebug` 后安装匹配 ABI APK 与代表性纯 HTTP 扩展。验收：扩展发现/安装/加载、源列表、单源浏览、全局搜索、空/403/失败反馈和设置入口；收集 UI dump、截图与 logcat，FATAL/OOM/SIGSEGV 必须为 0。

- [ ] **Step 6: Windows 固定 EXE 与 macOS 验收**

  Windows 只运行 `./scripts/build-desktop.sh`，启动 `D:\Shell\Github\mihon\app-desktop\tmp\mihon-dist\main\app\Mihon Desktop\Mihon Desktop.exe`，核对窗口完整版本、mtime、安装/更新/失败回滚、浏览/搜索、登录后备和文件工具；运行 `./scripts/desktop-smoke-test.sh`。通过 `ssh mbp` 在安全临时 clone 运行相关测试/构建，部署并启动 `/Applications/Mihon Desktop.app`，不覆盖远端用户仓库。

- [ ] **Step 7: 独立批次与最终审查**

  `review_mode: thorough`：每个高风险边界或最多 3 个 Task 运行合并 spec+quality review，最后对 `852221f42..HEAD` 运行完整审查。Critical/Important 必须修复并重新运行覆盖测试；Minor 记录到持久进度并交最终审查裁定。

- [ ] **Step 8: 提交 Task 7**

  Commit: `chore(extension): verify cross-platform source parity`

## 完成门槛

- 本计划与 `openspec/changes/align-sources-extensions/tasks.md` 全部勾选并通过 Comet task-checkoff。
- OpenSpec strict validation 0 issue；独立最终审查 0 Critical/Important。
- Android 与 Desktop production wiring 都消费共享业务类型，旧并行规则与无证据 compat stub 已删除。
- Windows 报告完整版本与固定 EXE 绝对路径；macOS 与 Android 运行时证据可追溯。
- 用户可从 Browse/Extensions 实际完成浏览、搜索、安装/更新、错误恢复、设置与登录挑战；空、加载、错误、取消和数据缺失均有明确反馈。
