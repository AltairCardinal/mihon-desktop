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
- [ ] Task 5B：Desktop 挑战恢复策略与 FlareSolverr 显式后备
- [ ] Task 5C：Desktop 登录设置、UI 与 production wiring
- [ ] Task 6A：Browse 共享状态 wiring
- [ ] Task 6B：Extension UI、DI 与 i18n wiring
- [ ] Task 6C：Test Mode、导航与自动化观察
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
**Estimated scope:** 7 files, 1533 lines
**Verification:** 运行真实 interceptor/challenge manager 策略测试，确认 403/503 只发布登录请求；browser、手动 Cookie 和 FlareSolverr 均由显式用户 intent 触发，取消/超时不清除或写入凭据，solver 从不由 interceptor 自动调用。
**Split waiver:** 实际 1533 changed lines（+1459/-74）分布在 7 个 shared+desktop 文件：532 行是 challenge/manager/interceptor/client 的 commit-point、单调 terminal/state、active-job 抢占、有界 timeout、同 host UA 绑定、显式 recovery intents、单次重试和 cancellable HTTP；30 行是 Task 5A shared required Cookie 非空校验及契约测试；971 行是同一真实链路的 21 项 MockWebServer→OkHttp interceptor→manager→Task 5A validation/atomic committer→DesktopCookieJar 策略/并发/HTTP/安全矩阵。shared 改动统一保护 browser/manual/solver 三条入口，避免 Desktop 复制提交规则。clear-first、自动 solver、committer 旁路、重复 retry、cancel/timeout/late completion、commit claim 双向竞态、register 窗口、阻塞 socket、UA 不匹配、HTTP 403/429/500/缺 solution 和空 required Cookie 共同决定恢复是否会误写/泄露凭据；拆开会留下虚假 terminal、不可用 clearance、共享/平台规则分叉或无法穿透 production 链的中间状态，不能独立验收。Task 5C 的 UI/设置/DI production entry wiring 未混入本 Task。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/network/CloudflareChallenge.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/network/CloudflareChallengeManager.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/network/DesktopCloudflareInterceptor.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/network/FlareSolverrClient.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/network/DesktopChallengeRecoveryPolicyTest.kt`
- Modify: `domain/src/commonMain/kotlin/tachiyomi/domain/source/service/SourceLoginSession.kt`
- Modify: `domain/src/jvmTest/kotlin/tachiyomi/domain/source/service/SourceLoginSessionTest.kt`

**Interfaces:**
- Consumes: Task 5A `SourceLoginRequest`/session completion；produces explicit `OpenBrowser`、`SubmitManualCookies`、`UseFlareSolverr`、`Cancel`/`Retry` intents。
- Interceptor 只检测挑战、等待有界 session terminal 并重试一次；不得删除已有 clearance Cookie 后再等待失败，也不得直接持有或调用 solver。

- [ ] **Step 1: 写 challenge policy/旧 Cookie 保留/solver 非自动调用 RED**
- [ ] **Step 2: 运行 RED 并确认现有 latch/clear-first 行为失败**
- [ ] **Step 3: 实现显式恢复 intents 与有界 terminal**
- [ ] **Step 4: 运行 GREEN、FlareSolverr HTTP 回归与 mutation**
- [ ] **Step 5: 提交 Task 5B**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.network.DesktopChallengeRecoveryPolicyTest" --tests "mihon.desktop.network.FlareSolverrClientTest"`
  Commit: `refactor(desktop): require explicit challenge recovery`

### Task 5C: Desktop 登录设置、UI 与 production wiring

**OpenSpec mapping:** 3.3、3.5（登录 UI、用户设置、脱敏与 i18n）

**Risk axis:** challenge-login-ui-wiring
**Platform boundary:** desktop
**Estimated scope:** 7 files, 400 lines
**Verification:** 运行 UI/DI production-wiring 测试，确认对话框展示目标域、进度、取消、超时、重试、手动导入；仅在设置启用且 URL 有效时显示并执行 FlareSolverr，所有日志/状态不包含 Cookie 值，触达文案使用 i18n。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/settings/DesktopAppPreferences.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/settings/AdvancedSettingsScreen.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/cloudflare/CloudflareBypassDialog.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/home/HomeScreen.kt`
- Modify: `i18n/src/commonMain/moko-resources/base/strings.xml`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/ui/cloudflare/DesktopChallengeLoginWiringTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt`

**Interfaces:**
- Consumes: Task 5A/5B session、recovery intents、Cookie commit 与 solver client；production UI 是所有用户可见 recovery intent 的唯一触发入口。
- FlareSolverr 默认关闭；启用开关与 URL 都持久化，URL 无效时 UI 给出可执行反馈而不发网络请求。

- [ ] **Step 1: 写设置、UI 状态、DI 与脱敏 production-wiring RED**
- [ ] **Step 2: 运行 RED 并确认入口/反馈/显式后备缺失**
- [ ] **Step 3: 实现 i18n 设置与对话框 intents，接通 HomeScreen production session**
- [ ] **Step 4: 运行 GREEN、Screen/DI/资源完整性与 mutation**
- [ ] **Step 5: 提交 Task 5C**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.cloudflare.DesktopChallengeLoginWiringTest" --tests "mihon.desktop.di.DesktopDiWiringTest" --tests "mihon.desktop.network.CloudflareCookieImportTest" --tests "mihon.desktop.network.FlareSolverrClientTest"`
  Commit: `feat(desktop): wire recoverable source browser login`

### Task 6A: Browse 共享状态 wiring

**OpenSpec mapping:** 3.4、3.5（源浏览、全局搜索、恢复反馈部分）

**Risk axis:** source-browse-wiring
**Platform boundary:** shared+desktop
**Estimated scope:** 4 files, 320 lines
**Verification:** 运行 Browse ScreenModel 与 `SourceSharedStateWiringTest`，确认 Loading、Empty、分页保留内容、403 登录和 Retry 均来自共享状态。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/browse/BrowseTab.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/browse/SourceBrowseScreen.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/browse/GlobalSearchScreen.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/ui/browse/SourceSharedStateWiringTest.kt`

**Interfaces:**
- Consumes: Task 2 共享 query/page/error state 与 Task 5 登录 intent。
- Produces: Browse UI intents（`Retry`、`OpenLogin`、`OpenSettings`）和既有宽屏入口。

- [ ] **Step 1: 写 Browse wiring RED**

  实例化 Browse/Source/Global Search 页面并驱动共享状态，覆盖 Loading、真正 Empty、翻页失败保留内容、403 登录、缺配置设置入口和 Retry。

- [ ] **Step 2: 运行 RED**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.browse.SourceSharedStateWiringTest" --tests "*Source*ScreenModelTest"`
  Expected: FAIL，原因是 Browse UI 仍自行维护状态或直接查询 repository/network。

- [ ] **Step 3: 最小接线 Browse 状态与 intents**

  ScreenModel 只组合共享 state 和发送 intent；Composable 保留现有导航入口与宽屏布局，不直接访问 repository/network。

- [ ] **Step 4: 运行 GREEN**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.browse.*"`
  Expected: 全部 PASS；共享状态或 Browse production wiring 断线会失败。

- [ ] **Step 5: 提交 Task 6A**

  Commit: `refactor(desktop): wire shared browse state`

### Task 6B: Extension UI、DI 与 i18n wiring

**OpenSpec mapping:** 2.3、3.4、3.5（扩展状态、安装反馈、DI 与本地化部分）

**Risk axis:** extension-ui-wiring
**Platform boundary:** shared+desktop
**Estimated scope:** 8 files, 400 lines
**Verification:** 运行 Extension shared-state 与 Desktop DI wiring 测试，确认部分仓库失败、TrustRequired、安装失败旧版本可用及本地化恢复操作均由 production wiring 提供。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/extension/ExtensionListScreen.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/extension/ExtensionDetailsScreen.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/extension/SourcePreferencesScreen.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt`
- Modify: `i18n/src/commonMain/moko-resources/base/strings.xml`
- Modify: `i18n/src/commonMain/moko-resources/zh-rCN/strings.xml`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/ui/extension/ExtensionSharedStateWiringTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt`

**Interfaces:**
- Consumes: Tasks 3–5 catalog/trust/install/login state。
- Produces: Extension UI intents（`Install`、`CancelInstall`、`ConfirmTrust`、`Retry`）与可解析的 Desktop DI bindings。

- [ ] **Step 1: 写 Extension UI/DI/i18n RED**

  覆盖 Screen 实例化、所有新增 DI 类型解析、部分仓库失败、TrustRequired、安装失败后旧版本仍可用，以及 base/zh-rCN 恢复操作 key 可加载。

- [ ] **Step 2: 运行 RED**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.extension.ExtensionSharedStateWiringTest" --tests "mihon.desktop.di.DesktopDiWiringTest"`
  Expected: FAIL，原因是 Extension UI 仍绕过共享状态、DI 缺绑定或资源 key 缺失。

- [ ] **Step 3: 接线 Extension 状态与 DI**

  Composable 只消费共享 state/intent，保留扩展列表、详情和源偏好入口；注册 production bindings，错误状态提供 Retry、设置或信任确认。

- [ ] **Step 4: 迁移本切片文案**

  将触达的源/扩展/挑战恢复文案同时加入 base 与 zh-rCN，禁止在上述 Kotlin 文件新增硬编码业务提示。

- [ ] **Step 5: 运行 GREEN**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.extension.*" --tests "mihon.desktop.di.DesktopDiWiringTest"`
  Expected: 全部 PASS；共享状态、DI 或资源 wiring 任一断线会失败。

- [ ] **Step 6: 提交 Task 6B**

  Commit: `refactor(desktop): wire extension UI and DI`

### Task 6C: Test Mode、导航与自动化观察

**OpenSpec mapping:** 2.3、3.4、3.5（导航类型、Test Mode 与自动化观察部分）

**Risk axis:** automation-observability
**Platform boundary:** desktop
**Estimated scope:** 4 files, 280 lines
**Verification:** 运行导航、i18n 约束与 Test Mode 客户端集成测试，确认 source/extension 状态、安装失败和登录取消可通过真实 HTTP 测试接口观察。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/test/state/TestState.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/test/http/TestHttpServer.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/ui/extension/SourceExtensionNavigationContractTest.kt`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/i18n/DesktopSourceExtensionI18nTest.kt`

**Interfaces:**
- Consumes: Tasks 6A/6B 的 Screen、intents、DI 与资源 key。
- Produces: 类型安全导航契约及稳定 Test Mode source/extension state/actions。

- [ ] **Step 1: 写导航/Test Mode/i18n RED**

  验证每个 Screen 可实例化、Voyager push 类型正确；通过真实 Test HTTP server 观察导航、安装失败、登录取消；扫描触达 Kotlin 文件的硬编码业务文案和资源完整性。

- [ ] **Step 2: 运行 RED**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.extension.SourceExtensionNavigationContractTest" --tests "mihon.desktop.i18n.DesktopSourceExtensionI18nTest" :test-desktop:test`
  Expected: FAIL，原因是导航契约、Test Mode 状态/action 或 i18n 约束尚未覆盖新链路。

- [ ] **Step 3: 接入 Test Mode 状态与动作**

  增加或调整 source/extension 可观察状态与动作，复用 production Screen/intents，不在测试 server 复制业务逻辑。

- [ ] **Step 4: 补齐导航和 i18n 契约**

  直接实例化 Screen 并验证导航上下文；资源测试对 Tasks 6A/6B 的文件与 key 执行完整性检查。

- [ ] **Step 5: 运行 GREEN 与自动化集成**

  Run: `./gradlew :app-desktop:jvmTest --tests "*Navigation*ContractTest" --tests "*I18n*" :test-desktop:test`
  Expected: 全部 PASS；Screen/navigation/Test Mode/i18n 任一断线都会失败。

- [ ] **Step 6: 提交 Task 6C**

  Commit: `test(desktop): expose source extension workflow state`

### Task 7: compat 去重、parity 证据、全量审查与跨平台运行时验收

**OpenSpec mapping:** 4.1、4.2、4.3、4.4、4.5、4.6

**Risk axis:** parity-evidence
**Platform boundary:** verification
**Estimated scope:** 7 files, 350 lines
**Verification:** 运行 compat 契约、全量 Gradle/桌面测试构建和 Android/Windows/macOS 运行时验收，并完成 thorough 独立审查。

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

  manifest 的 `authoritativeImplementation`、`desktopImplementation` 与 `protectionTests` 必须引用真实 production/test 路径；状态只提升到证据支持的 CHARACTERIZED/SHARED/WIRED/VERIFIED，不把平台 adapter 当作业务豁免。

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
