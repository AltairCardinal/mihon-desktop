# Mihon Desktop 原版实现完全对齐实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 64 个“原版更优”条目的非平台特有实现收敛到原版/共享实现，同时保持全部 Desktop 独有产品能力零回退。

**Architecture:** 以原版行为为权威契约，把平台无关的规则、状态机、数据格式和错误模型迁入 common source set；Android 与 Desktop 只保留操作系统适配器。迁移采用特征测试、共享抽取、双轨比较、入口切换、数据迁移、删除旧路径的顺序。

**Tech Stack:** Kotlin Multiplatform、Compose Multiplatform、Voyager、Injekt、SQLDelight、OkHttp/MockWebServer、kotlinx.serialization/Protobuf、JUnit、Gradle、Desktop Test Mode。

## Global Constraints

- 原版能力完全对齐，Desktop 独有产品能力零回退。
- 非平台必需的 Desktop 重写不得继续作为第二套业务实现存在。
- Android 专属 API 使用 Desktop 等价适配器，不机械复制 WorkManager、Intent、PackageInstaller 或 Android View。
- 每项功能变化严格执行红—绿—重构；没有对应测试的功能代码不得提交。
- 导航、DI、HTTP、数据库和后台任务接点必须有集成测试。
- 数据迁移不得要求用户清空数据库、偏好、下载、备份或扩展目录。
- 一次发布只切换一个可独立验收的能力链。
- 每个 Desktop 迭代必须使用 `scripts/build-desktop.sh`，并启动 `app-desktop/tmp/mihon-dist/main/app/Mihon Desktop/Mihon Desktop.exe` 核验完整版本号。
- 不自动提交；计划中的提交步骤只在用户明确授权执行阶段提交时启用。
- 权威设计：`docs/superpowers/specs/2026-07-12-mihon-desktop-upstream-parity-design.md`。

---

## 1. 文件与模块目标结构

以下结构是全部项目群共同遵守的目标，不要求一次性创建空模块：

| 位置 | 单一职责 |
|---|---|
| `domain/src/commonMain/kotlin/...` | 跨端业务模型、用例、状态机、任务定义和错误分类 |
| `data/src/commonMain/kotlin/...` | 跨端 repository、mapper、序列化与 SQLDelight 访问 |
| `core/common/src/commonMain/kotlin/...` | 与领域无关的调度、结果、时间、网络契约工具 |
| `source-api/src/commonMain/kotlin/...` | 扩展/源的跨端契约与 preference schema |
| `app/src/main/java/...` | Android UI、WorkManager、Intent、通知、PackageManager 等薄适配 |
| `app-desktop/src/main/kotlin/mihon/desktop/platform/...` | 文件、通知、调度、URI、凭据、窗口等 Desktop 适配 |
| `app-desktop/src/main/kotlin/mihon/desktop/ui/...` | Desktop UI 与永久产品增强，不承载共享业务规则 |
| `app-desktop/src/test/...` | Desktop wiring、平台适配和产品增强回归 |
| 各模块 `commonTest`/`jvmTest` | 原版与 Desktop 共用的契约测试和 fixture |

依赖方向固定为：`UI → shared use case → shared repository contract → common data/platform adapter`。Composable 禁止直接调用数据库 query、HTTP client、下载 manager 或 ClassLoader。

## 2. 里程碑与完成度

| 阶段 | 项目群 | 覆盖原编号 | 条目数 | 退出条件 |
|---|---|---|---:|---|
| 0 | 契约与保护网 | 全部 64 项的追踪基础 | 64 | 矩阵校验、Desktop 独有特征测试清单、统一门禁可执行 |
| 1A | 共享架构、状态与模块边界 | 3, 4, 7, 12, 93, 95, 96 | 7 | 共享状态/错误/偏好契约可用，重复 wiring 有迁移路径 |
| 1B | 网络、后台任务与通知 | 8, 10, 11, 61 | 4 | 任务可恢复、通知事件共享、网络错误统一 |
| 2A | 备份与跨端兼容 | 71–74 | 4 | 双读单写原版 protobuf，跨端 fixture 通过 |
| 2B | 下载、更新与历史 | 53, 56, 57, 59, 62, 64 | 6 | 队列重启恢复，进度事务统一，旧 manager 业务规则删除 |
| 3A | 书库与漫画详情 | 16, 17, 19, 22, 24, 26, 66 | 7 | UI 接入共享用例，批量/筛选/封面/统计对齐 |
| 3B | 迁移与追踪 | 67–70 | 4 | 单部/批量迁移对齐，Desktop tracker 可登录绑定同步 |
| 4A | 阅读器核心 | 9, 43–45, 47, 49, 51, 54 | 8 | 页面模型/算法共享，Desktop 增强全绿，大图内存验收通过 |
| 4B | 源、扩展与挑战 | 28–40, 87 | 13 | 源/扩展状态共享，安全与登录闭环，APK→JAR 保留 |
| 5A | 系统集成、隐私与发布 | 81–86, 92 | 7 | 支持项等价实现，不支持项明确平台豁免 |
| 5B | 设置、外观、无障碍与合规 | 88, 90, 91, 94 | 4 | 设置可搜索、资源国际化、焦点/许可入口完整 |
| 6 | 删除兼容债务与最终审计 | 全部 | 64 | 重复业务实现归零，64 项全部对齐或获准豁免 |

总数校验：`7 + 4 + 4 + 6 + 7 + 4 + 8 + 13 + 7 + 4 = 64`。

## 3. 统一任务模板

每个能力链必须按以下小步骤执行，不允许跳过红测或先删除旧实现：

- [ ] **Step 1: 写原版行为契约测试**

  从原版现有测试/实现提取真实输入与期望结果；测试应能在 Desktop 简化实现上暴露差异。

- [ ] **Step 2: 运行契约测试并确认 RED**

  运行目标模块的精确测试任务；预期因 Desktop 尚未接入共享契约而失败，不得因 fixture、DI 或编译错误失败。

- [ ] **Step 3: 写 Desktop 独有能力特征测试**

  固定本能力链会触达的永久增强。测试在迁移前必须 PASS，作为功能零回退基线。

- [ ] **Step 4: 提取最小共享接口与实现**

  移动原版纯逻辑；Android side effect 改为接口注入。不得复制为新的 Desktop 类。

- [ ] **Step 5: 运行 common、Android 与 JVM 测试并确认 GREEN**

  三端测试必须使用相同 fixture；平台 adapter 测试只验证映射和 side effect。

- [ ] **Step 6: 加入新旧 Desktop 双轨比较**

  对旧路径与共享路径输入同一 fixture，断言领域结果、状态转换和错误分类一致。

- [ ] **Step 7: 先改 DI，再改 UI wiring**

  为 binding 解析、Screen 实例化、导航上下文和后台任务注册写集成测试，然后切换入口。

- [ ] **Step 8: 验证旧数据迁移**

  用真实旧版 fixture 验证偏好、数据库、队列、备份或扩展元数据可读取，升级不要求清数据。

- [ ] **Step 9: 运行 Desktop 产品增强回归**

  执行本计划指定的增强测试和 Test Mode 场景。

- [ ] **Step 10: 删除旧业务路径**

  只删除已被共享实现覆盖的 `TEMP-COMPAT`/重复规则；保留有明确技术理由的平台 adapter 和 `DESKTOP-PRODUCT`。

- [ ] **Step 11: 重构并运行完整验证**

  运行 Spotless、目标单元/集成测试、`:app-desktop:jvmTest` 和必要 E2E。

- [ ] **Step 12: 构建和人工验收**

  使用仓库 Desktop 构建脚本，启动固定 EXE，核对版本并按用户路径验收。

## 4. Task 0：建立对齐追踪与 Desktop 保护网

**Files:**
- Create: `docs/desktop-parity/PARITY_TRACKER.md`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/parity/DesktopProductCapabilityContractTest.kt`
- Modify: `docs/MIHON_ANDROID_DESKTOP_FEATURE_IMPLEMENTATION_COMPARISON.md`
- Modify: `docs/automation/TASK_TRACKER.md`

**Interfaces:**
- Consumes: 设计文档中的 64 项编号和第 6 节保护清单。
- Produces: 每项唯一状态 `NOT_STARTED | CHARACTERIZED | SHARED | WIRED | VERIFIED | EXEMPT`；Desktop 产品能力测试套件入口。

- [ ] **Step 1: 写失败的追踪矩阵校验测试**

  在 `DesktopProductCapabilityContractTest` 中读取项目资源内的 parity manifest，断言包含 64 个唯一编号、合法状态和 owner；初始因 manifest 不存在而失败。

- [ ] **Step 2: 运行 RED**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.parity.DesktopProductCapabilityContractTest"`

  Expected: FAIL，原因是 parity manifest/64 项映射尚未提供。

- [ ] **Step 3: 创建追踪矩阵**

  `PARITY_TRACKER.md` 每行必须包含：原编号、项目群、迁移标签、当前权威类、Desktop 重复类、保护测试、状态、平台豁免证据、目标版本。

- [ ] **Step 4: 为永久增强建立聚合测试入口**

  聚合现有作者、Upcoming、阅读器双页/自动滚动、APK 转换、Test Mode 测试；缺少的能力只新增特征测试，不修改实现。

- [ ] **Step 5: 运行 GREEN**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.parity.*"`

  Expected: PASS，64 项唯一，保护能力测试全部通过。

- [ ] **Step 6: 更新自动化追踪文档**

  将每个阶段需要的 Test Mode 场景加入 `docs/automation/TASK_TRACKER.md`，标明尚未实现的场景为计划状态而非通过状态。

## 5. Task 1A：共享架构、状态、偏好与模块边界

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/DesktopUiDependencies.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/settings/DesktopAppPreferences.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/reader/ReaderPreferences.kt`
- Modify: `core/common/src/commonMain/kotlin/tachiyomi/core/common/preference/`
- Create: `domain/src/commonMain/kotlin/mihon/domain/error/AppError.kt`
- Create: `domain/src/commonMain/kotlin/mihon/domain/task/TaskState.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/settings/DesktopPreferenceMigrationTest.kt`
- Test: `domain/src/commonTest/kotlin/mihon/domain/error/AppErrorTest.kt`

**Interfaces:**
- Produces: `sealed interface AppError`、`sealed interface TaskState<out T>`、统一 `PreferenceStore` key/default/migration；按领域分组的 Desktop DI registrar。
- Consumes: 现有 PreferenceStore、Injekt 和 ScreenModel 模式。

- [ ] **Step 1: 为错误映射、偏好默认值和旧 key 迁移写失败测试**
- [ ] **Step 2: 运行 common 与 Desktop 精确测试，确认因类型/迁移缺失而 RED**
- [ ] **Step 3: 从原版调用链提取 `AppError` 与 `TaskState`，禁止包含 Android 类型**
- [ ] **Step 4: 将 `java.util.prefs` 封装成 PreferenceStore backend，保留旧 key 双读迁移**
- [ ] **Step 5: 按 settings/reader/library/network/download/backup/extension 拆分 DI 注册函数**
- [ ] **Step 6: 写 DI 全解析测试，覆盖每个 UI 中使用的依赖**
- [ ] **Step 7: 逐 ScreenModel 切换共享状态，Composable 只 collect state/发送 intent**
- [ ] **Step 8: 运行 `:domain:allTests`、`:core:common:allTests` 和 `:app-desktop:jvmTest`**
- [ ] **Step 9: 删除被 PreferenceStore 覆盖的直接 `java.util.prefs` 业务访问**
- [ ] **Step 10: 更新追踪项 3、4、7、12、93、95、96 的证据和状态**

## 6. Task 1B：网络、后台任务与通知

**Files:**
- Modify: `core/common/src/commonMain/kotlin/eu/kanade/tachiyomi/network/`
- Modify: `core/common/src/jvmMain/kotlin/eu/kanade/tachiyomi/network/DesktopCookieJar.kt`
- Create: `domain/src/commonMain/kotlin/mihon/domain/task/BackgroundTask.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopTaskScheduler.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopSystemNotifier.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/domain/DesktopNotificationService.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/domain/LibraryUpdateScheduler.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/platform/DesktopTaskSchedulerIntegrationTest.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/platform/DesktopSystemNotifierTest.kt`
- Test: `core/common/src/jvmTest/kotlin/eu/kanade/tachiyomi/network/DesktopCookieJarTest.kt`

**Interfaces:**
- Produces: `BackgroundTask`, `TaskConstraint`, `TaskCheckpoint`, `NotificationEvent`；Desktop scheduler/notifier adapter。
- Consumes: TaskState/AppError/PreferenceStore from Task 1A。

- [ ] **Step 1: 写任务持久恢复、取消和通知映射失败测试**
- [ ] **Step 2: 用临时目录模拟进程重启，确认当前内存 scheduler 无法恢复而 RED**
- [ ] **Step 3: 提取原版任务定义、约束和幂等规则到 common**
- [ ] **Step 4: 实现 Desktop checkpoint store 与启动恢复，不复制 WorkManager API**
- [ ] **Step 5: 实现系统通知 adapter；不可用时回退到应用内通知并记录原因**
- [ ] **Step 6: 让 LibraryUpdateScheduler 消费共享任务，不再自有业务状态机**
- [ ] **Step 7: 用 MockWebServer 验证离线、403、429、500、畸形响应的统一 AppError**
- [ ] **Step 8: 运行网络、任务、更新和 Desktop wiring 测试**
- [ ] **Step 9: 删除重复重试/错误字符串解析和仅内存任务状态**
- [ ] **Step 10: 更新追踪项 8、10、11、61**

## 7. Task 2A：备份格式与跨端兼容

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/backup/`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/backup/DesktopBackupCreator.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/backup/DesktopBackupRestorer.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/backup/models/BackupModels.kt`
- Create: `data/src/commonMain/kotlin/tachiyomi/data/backup/BackupCodec.kt`
- Create: `data/src/commonTest/resources/backup/android-full.tachibk`
- Create: `data/src/jvmTest/resources/backup/desktop-legacy.tachibk`
- Test: `data/src/commonTest/kotlin/tachiyomi/data/backup/BackupCodecContractTest.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/backup/DesktopBackupCompatibilityTest.kt`

**Interfaces:**
- Produces: 共享 canonical protobuf `BackupCodec`；历史 Android/Desktop protobuf 读取；canonical protobuf 单写策略。
- Consumes: AppError/TaskState、原版 Backup schema 和历史 Desktop protobuf schema。

- [ ] **Step 1: 保存真实 Android 完整备份和旧 Desktop 备份 fixture**
- [ ] **Step 2: 写跨端读写失败测试：Desktop 读 Android、Android/shared 读 Desktop 转换结果**
- [ ] **Step 3: 运行 RED，确认历史 Android/Desktop protobuf 与 canonical protobuf 的兼容边界**
- [ ] **Step 4: 将原版 codec/validator 中的平台无关部分迁到 data common**
- [ ] **Step 5: Android/Desktop creator 统一为只写 canonical protobuf**
- [ ] **Step 6: 通过共享 codec 读取历史 Android/Desktop protobuf，并映射到 canonical 共享模型**
- [ ] **Step 7: 对漫画、章节、分类、历史、追踪、偏好、源、扩展仓库逐字段断言**
- [ ] **Step 8: 为损坏、未知版本、部分恢复、取消和磁盘不足写集成测试**
- [ ] **Step 9: UI 显示预览、进度、逐项结果和可恢复错误**
- [ ] **Step 10: 运行 Android backup tests、data tests 和 Desktop backup tests**
- [ ] **Step 11: 删除旧 Desktop writer；通过共享 codec 保留历史 Android/Desktop protobuf 读取兼容**
- [ ] **Step 12: 更新追踪项 71–74**

## 8. Task 2B：下载、更新、历史与阅读进度

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/download/`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/download/DesktopDownloadManager.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/domain/ReaderProgressTracker.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/updates/UpdatesScreenModel.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/history/HistoryScreenModel.kt`
- Create: `domain/src/commonMain/kotlin/mihon/domain/download/DownloadQueueStateMachine.kt`
- Create: `data/src/commonMain/kotlin/tachiyomi/data/download/PersistentDownloadStore.kt`
- Test: `domain/src/commonTest/kotlin/mihon/domain/download/DownloadQueueStateMachineTest.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/download/DesktopDownloadRecoveryIntegrationTest.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/domain/ReaderProgressTrackerIntegrationTest.kt`

**Interfaces:**
- Produces: 持久下载队列、共享并发/重试/自动下载规则、原子阅读进度事务。
- Consumes: BackgroundTask/NotificationEvent、共享 repositories。

- [ ] **Step 1: 为状态转换、公平调度、重启恢复和部分文件恢复写失败测试**
- [ ] **Step 2: 为进度/history/已读/tracker event 的单事务语义写失败测试**
- [ ] **Step 3: 运行 RED，确认 Desktop 内存队列和独立 progress tracker 暴露差异**
- [ ] **Step 4: 从原版提取队列状态机、自动下载规则和 retry/backoff**
- [ ] **Step 5: 实现 SQLDelight/持久 store，并迁移当前 Desktop queue snapshot**
- [ ] **Step 6: Desktop manager 收敛为文件下载 adapter，不再决定业务状态转换**
- [ ] **Step 7: Updates/History ScreenModel 直接消费共享 use case 和下载状态**
- [ ] **Step 8: ReaderProgressTracker 改为提交共享阅读事件，事务内联动 tracker**
- [ ] **Step 9: 保留 CBZ、目录选择、Upcoming 与 Test Mode 回归**
- [ ] **Step 10: 运行下载、更新、历史、进度、后台恢复和 Desktop E2E**
- [ ] **Step 11: 删除旧内存队列/重复自动下载和进度规则**
- [ ] **Step 12: 更新追踪项 53、56、57、59、62、64**

## 9. Task 3A：书库与漫画详情

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/library/LibraryScreenModel.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/library/MangaDetailScreenModel.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/domain/DesktopCategoryManager.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/domain/BatchSetCategories.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/domain/AddMangaToLibrary.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/domain/DesktopMangaCoverManager.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/more/StatsScreen.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/ui/library/LibraryParityIntegrationTest.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/ui/library/MangaDetailParityIntegrationTest.kt`

**Interfaces:**
- Produces: Desktop UI 对共享分类、筛选、批处理、收藏、章节动作、封面和统计用例的 wiring。
- Consumes: 原版 domain use cases、LibraryFlags、repositories 和 TaskState/AppError。

- [ ] **Step 1: 用原版 fixture 写分类排序、筛选组合、范围选择和部分失败契约测试**
- [ ] **Step 2: 写 Desktop 鼠标/Shift 多选、宽屏布局、作者入口保护测试**
- [ ] **Step 3: 运行 RED，记录 Desktop 重写与原版结果差异**
- [ ] **Step 4: 将缺失原版规则抽到共享 use case；已有 use case 直接接线**
- [ ] **Step 5: ScreenModel 只组合共享 state，UI 只发送 intent**
- [ ] **Step 6: 封面文件选择通过 adapter，缓存失效/错误使用共享模型**
- [ ] **Step 7: StatsScreen 消费共享统计聚合，不在 Composable 查询/计算**
- [ ] **Step 8: 补 Screen 实例化、导航和 DI wiring 测试**
- [ ] **Step 9: 运行 library/detail/stats 与 Desktop 产品保护测试**
- [ ] **Step 10: 删除 DesktopCategoryManager 等已被共享实现完全覆盖的业务类**
- [ ] **Step 11: 更新追踪项 16、17、19、22、24、26、66**

## 10. Task 3B：迁移与追踪

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/domain/DesktopMigrateMangaUseCase.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/migration/`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/track/`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopOAuthCallbackServer.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopCredentialStore.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/ui/tracking/TrackingSettingsScreen.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/migration/DesktopMigrationParityTest.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/tracking/DesktopTrackingIntegrationTest.kt`

**Interfaces:**
- Produces: 共享单部/批量迁移编排；Desktop OAuth callback、凭据存储和 tracker UI。
- Consumes: 原版 tracker manager/API/domain、阅读进度事件、共享任务系统。

- [ ] **Step 1: 为迁移选项、章节匹配、分类/阅读状态复制写原版契约测试**
- [ ] **Step 2: 为批量取消、恢复点和逐项失败写 RED 测试**
- [ ] **Step 3: 抽取原版迁移编排，Desktop use case 改为薄调用器**
- [ ] **Step 4: 将 tracker API/domain 中 Android 无关逻辑移到共享 source set**
- [ ] **Step 5: 实现 loopback OAuth callback 和 OS credential store adapter**
- [ ] **Step 6: 新增 Tracker 设置、登录、搜索绑定、状态/分数/章节编辑 UI**
- [ ] **Step 7: ReaderProgress 事件接入原版自动同步策略和重试任务**
- [ ] **Step 8: MockWebServer 覆盖每类认证、刷新 token、429、500 和畸形响应**
- [ ] **Step 9: 保护作品比较、宽屏迁移队列和 Test Mode**
- [ ] **Step 10: 运行 migration/tracker/reader integration tests**
- [ ] **Step 11: 删除 Desktop 重复迁移业务规则**
- [ ] **Step 12: 更新追踪项 67–70**

## 11. Task 4A：阅读器共享核心

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/reader/`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/reader/`
- Create: `domain/src/commonMain/kotlin/mihon/domain/reader/ReaderPageModel.kt`
- Create: `domain/src/commonMain/kotlin/mihon/domain/reader/ReaderNavigation.kt`
- Create: `domain/src/commonMain/kotlin/mihon/domain/reader/PageTransform.kt`
- Test: `domain/src/commonTest/kotlin/mihon/domain/reader/ReaderParityContractTest.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/ui/reader/DesktopReaderProductRegressionTest.kt`

**Interfaces:**
- Produces: 共享页面/章节/过渡状态、宽页拆分、配对、预加载窗口、导航区域、跳过和滤镜参数。
- Consumes: AppError/TaskState、阅读进度事件、平台 decoder/cache adapter。

- [ ] **Step 1: 从原版页面模型和算法建立真实测试向量**
- [ ] **Step 2: 写宽页反转/旋转、缺章、重试、预加载取消、导航反转、灰度/反色 RED 测试**
- [ ] **Step 3: 写 Desktop 双页、edge matching、自动滚动、键鼠、右键保存保护测试**
- [ ] **Step 4: 提取纯页面模型/算法到 domain common，不移动 Android View**
- [ ] **Step 5: 定义 `PageDecoder`/`RegionDecoder`/`PageCache` 平台接口**
- [ ] **Step 6: Desktop 使用 Skia codec/tiles 实现区域解码和内存预算**
- [ ] **Step 7: Android 与 Desktop viewer 同时消费共享状态/命令**
- [ ] **Step 8: 对相同章节 fixture 双轨比较页序、过渡、跳过和错误结果**
- [ ] **Step 9: 运行内存/大图测试，验证不会全尺寸长期驻留**
- [ ] **Step 10: 运行 reader common、Android reader、Desktop reader 和 Test Mode 测试**
- [ ] **Step 11: 删除 Desktop 重复拆页、导航和跳过规则；保留渲染/输入增强**
- [ ] **Step 12: 更新追踪项 9、43、44、45、47、49、51、54**

## 12. Task 4B：源、扩展与挑战处理

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/extension/`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/extension/`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/source/`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/network/`
- Modify: `app-desktop/src/main/kotlin/android/`
- Modify: `source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/`
- Create: `domain/src/commonMain/kotlin/mihon/domain/extension/ExtensionInstallState.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopWebLoginAdapter.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/extension/ExtensionParityIntegrationTest.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/network/DesktopWebLoginIntegrationTest.kt`

**Interfaces:**
- Produces: 共享源列表/浏览/搜索状态，扩展发现/安装/安全/更新契约，web login/challenge adapter。
- Consumes: extensionrepo domain、网络/任务/通知、PreferenceStore、AppError。

- [ ] **Step 1: 用原版 index、已安装包和 source fixture 写状态契约测试**
- [ ] **Step 2: MockWebServer 覆盖源分页成功/空/403/429/500/畸形响应**
- [ ] **Step 3: 写 JAR、APK→JAR、损坏产物、版本替换、回滚和不兼容 API 测试**
- [ ] **Step 4: 写当前 compat stub 使用清单测试，未被真实扩展引用的 stub 不得扩张**
- [ ] **Step 5: 抽取源/扩展状态、版本、安全和 preference schema 到共享层**
- [ ] **Step 6: Desktop loader/installer 只实现目录、ClassLoader、转换和进程隔离 side effect**
- [ ] **Step 7: 实现签名/哈希信任、仓库信任、更新事务和失败回滚**
- [ ] **Step 8: 实现 Desktop 浏览器登录/Cookie 回传；FlareSolverr 保留为可选后备**
- [ ] **Step 9: 源列表、单源浏览、全局搜索、扩展详情/设置接入共享 ScreenModel**
- [ ] **Step 10: 将所有 Desktop 文案迁入 i18n 资源并测试缺 key**
- [ ] **Step 11: 运行 extension/source/network/DI/navigation/Test Mode 全链测试**
- [ ] **Step 12: 删除重复搜索、版本判断、错误字符串和无使用证据的 compat stub**
- [ ] **Step 13: 更新追踪项 28–40、87**

## 13. Task 5A：系统集成、隐私与应用更新

**Files:**
- Create: `domain/src/commonMain/kotlin/mihon/domain/platform/ExternalAction.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopDeepLinkHandler.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopShareService.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopAppLock.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopWindowPrivacy.kt`
- Modify: `data/src/commonMain/kotlin/tachiyomi/data/release/ReleaseServiceImpl.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/settings/AdvancedSettingsScreen.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/platform/DesktopSystemIntegrationTest.kt`

**Interfaces:**
- Produces: 共享 deep-link parser/share payload/lock policy/update state；各 OS adapter。
- Consumes: AppError/TaskState、ReleaseService、PreferenceStore、Desktop task/notifier。

- [ ] **Step 1: 为 URI 解析、无效链接、分享 payload、锁定超时、版本比较写共享 RED 测试**
- [ ] **Step 2: 为当前 OS 能力探测和平台豁免显示写 Desktop RED 测试**
- [ ] **Step 3: 提取原版 URI parser、分享模型、安全策略和 release 状态**
- [ ] **Step 4: 实现 Windows/macOS/Linux scheme 注册与单实例转发 adapter**
- [ ] **Step 5: 实现系统分享/剪贴板后备和 OS credential-backed app lock**
- [ ] **Step 6: 在支持的平台实现窗口隐私；不支持时 UI 明确说明而非静默成功**
- [ ] **Step 7: 对齐下载、校验、安装、失败回滚的更新状态机**
- [ ] **Step 8: 将 Widget 标记为平台豁免，仅共享更新数据 provider 契约**
- [ ] **Step 9: 补设置 UI、确认对话框、错误反馈和导航/DI 测试**
- [ ] **Step 10: 运行三 OS 可执行的单元测试矩阵及当前 Windows 集成验收**
- [ ] **Step 11: 更新追踪项 81–86、92，并附豁免证据**

## 14. Task 5B：设置、外观、无障碍与许可

**Files:**
- Modify: `app/src/main/java/eu/kanade/presentation/more/settings/`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/settings/`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/theme/DesktopTheme.kt`
- Create: `domain/src/commonMain/kotlin/mihon/domain/settings/SearchablePreference.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/ui/settings/SettingsSearchScreen.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/ui/settings/OpenSourceLicensesScreen.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/ui/settings/DesktopSettingsParityTest.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/ui/accessibility/DesktopAccessibilityContractTest.kt`

**Interfaces:**
- Produces: 共享可搜索 preference model、主题语义、许可数据；Desktop 搜索/主题/许可 UI。
- Consumes: PreferenceStore、i18n、Voyager navigator、构建生成的许可 metadata。

- [ ] **Step 1: 为设置索引、关键词、隐藏项和结果路由写 RED 测试**
- [ ] **Step 2: 为主题 key/default/migration 和许可 metadata 写 RED 测试**
- [ ] **Step 3: 提取原版 SearchableSettings/主题语义为共享模型**
- [ ] **Step 4: Desktop 新增设置搜索并映射到现有 Screen/锚点**
- [ ] **Step 5: 将 Desktop 专属外观项叠加到共享主题模型，不复制原版 key**
- [ ] **Step 6: 构建阶段生成 Desktop 依赖许可数据并提供详情页**
- [ ] **Step 7: 为所有交互控件补语义标签、焦点顺序和纯键盘操作**
- [ ] **Step 8: 用屏幕阅读器可观察语义树/Compose 测试验证关键页面**
- [ ] **Step 9: 补 Screen 实例化、导航、DI 和资源完整性测试**
- [ ] **Step 10: 运行 settings/theme/i18n/accessibility 测试**
- [ ] **Step 11: 更新追踪项 88、90、91、94**

## 15. Task 6：删除重复实现与最终审计

**Files:**
- Modify: `docs/desktop-parity/PARITY_TRACKER.md`
- Modify: `docs/MIHON_ANDROID_DESKTOP_FEATURE_IMPLEMENTATION_COMPARISON.md`
- Modify: `docs/automation/TEST_COVERAGE_REPORT.md`
- Modify: `docs/automation/TASK_TRACKER.md`
- Modify: `app-desktop/build.gradle.kts`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/parity/DesktopProductCapabilityContractTest.kt`

**Interfaces:**
- Consumes: 所有项目群的共享接口、adapter、测试和追踪证据。
- Produces: 64 项最终状态、剩余平台 adapter 清单、零重复业务实现证明和维护文档。

- [ ] **Step 1: 扫描 64 项追踪矩阵，确认每项为 VERIFIED 或 EXEMPT**
- [ ] **Step 2: 对每个 EXEMPT 检查 OS 能力证据、UI 边界说明和用户批准记录**
- [ ] **Step 3: 建立重复实现审计表，逐项对照原版权威类与 Desktop 类职责**
- [ ] **Step 4: 为不允许的 UI→data/network/manager 依赖增加架构测试**
- [ ] **Step 5: 删除已无调用的临时 adapter、旧 writer、重复状态机和无证据 compat stub**
- [ ] **Step 6: 运行 `./gradlew spotlessCheck`**
- [ ] **Step 7: 运行相关 Android 单元/集成测试及共享模块全部测试**
- [ ] **Step 8: 运行 `./gradlew :app-desktop:jvmTest` 和 `./gradlew :test-desktop:test`**
- [ ] **Step 9: 运行 Desktop smoke test/Test Mode 全场景，核验保护清单零回退**
- [ ] **Step 10: 使用 `scripts/build-desktop.sh` 生成新 BUILD**
- [ ] **Step 11: 启动固定未打包 EXE，核对窗口版本、文件时间和所有核心用户路径**
- [ ] **Step 12: 更新比较报告：64 项改为已对齐或有证据的平台豁免**
- [ ] **Step 13: 在完成报告中列出完整版本、EXE 路径、测试命令、失败数和剩余豁免**

## 16. 每阶段统一验证命令

按改动范围选择精确测试；阶段退出前至少运行：

```bash
./gradlew spotlessCheck
./gradlew :domain:allTests
./gradlew :data:allTests
./gradlew :app-desktop:jvmTest
./gradlew :test-desktop:test
```

触及 Android 原版共享抽取时，补充：

```bash
./gradlew testReleaseUnitTest
```

触及 Desktop UI wiring、后台任务或真实窗口行为时，补充：

```bash
./scripts/desktop-smoke-test.sh
```

每个 Desktop 非测试迭代最终只能通过以下脚本构建，不得直接用 Gradle 部署：

```bash
./scripts/build-desktop.sh
```

Windows 验收固定路径：

```text
app-desktop/tmp/mihon-dist/main/app/Mihon Desktop/Mihon Desktop.exe
```

## 17. 执行纪律

- 开始一个项目群前，从本路线图复制该 Task 到独立计划文件，补充当时准确的符号、行号、测试代码和当前版本 fixture；路线图负责长期顺序，子计划负责具体实现。
- 不并行修改共享基础接口与其多个消费者；先稳定接口和契约测试，再并行迁移独立消费者。
- 不允许为通过 Desktop 测试而改变原版权威行为；若发现原版本身有 bug，建立独立 bugfix 变更，两端共同修复。
- 未通过 Desktop 独有能力回归，不得删除旧路径。
- 未完成数据双读，不得切换 writer。
- 未具备真实系统能力，不得把平台豁免条目标记为 VERIFIED。
- 用户未明确要求时不提交、不推送、不创建 PR。
