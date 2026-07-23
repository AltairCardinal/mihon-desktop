---
original-roadmap: docs/superpowers/plans/2026-07-12-mihon-desktop-upstream-parity-roadmap.md
correction-list: docs/superpowers/plans/2026-07-12-mihon-desktop-upstream-parity-roadmap-concept-confusions.md
original-branch: main
original-ref: 6fbf6dfca203d99d6dd32137f2df97ced40c81b8
completed-child-plan: docs/superpowers/plans/2026-07-21-mihon-desktop-platform-integration.md
active-child-plan: docs/superpowers/plans/2026-07-22-mihon-desktop-settings-accessibility.md
resume-order: complete the active Task 5B child plan through Task 20, then continue with parent Task 6
---

# Mihon Desktop 对齐原版 Mihon 实施计划（原版基线修正版）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

## 概念与证据边界

本计划中的角色具有唯一含义，后续子计划和报告不得改用模糊简称：

- **原版 Mihon**：`main` 的固定快照 `6fbf6dfca203d99d6dd32137f2df97ced40c81b8`。原版实现只能通过 `git show 6fbf6dfca203d99d6dd32137f2df97ced40c81b8:<path>`、该提交构建的真实产物或带 provenance 的 fixture 取证。
- **当前 Android 构建版**：当前重构分支的 `app/`。它是 Mihon Desktop fork 的 Android 构建目标，也是迁移后 shared core 的消费者；它不是原版行为的自证来源。
- **Desktop JVM 实现**：当前重构分支的 `app-desktop/` 生产实现。
- **Desktop Android 兼容 shim**：`app-desktop/src/main/kotlin/android/`，只用于在 Desktop 承载有真实扩展证据的 Android API；它不是原版 Mihon 或当前 Android 构建版。
- **共享实现**：当前重构分支的 common/shared 生产代码。它是迁移目标，不是原版来源；必须通过原版 fixture 证明默认语义一致。

如果后续需要升级原版基线，必须先更新 `original-ref`，重新核验受影响的原版实现与 fixture，再更新计划和 parity evidence；不得让移动中的 `main` 静默改变任务语义。

当前设置、外观、无障碍与许可施工由 `active-child-plan` 承载。恢复执行时必须先完成父 Task 5B 子计划至子 Task 20，再回到本修正版父计划的 Task 6；保留的 `original-roadmap` 仅供历史对照，不得重新作为可执行权威入口。

**Goal:** 以固定原版 Mihon 的成熟行为、流程和工程边界为权威契约，消除 Mihon Desktop 当初为快速完成功能而产生的非平台必要简化与重复实现；将适合共用的规则迁入当前分支 shared core，让当前 Android 构建版和 Desktop JVM 实现共同消费，同时保持全部 Desktop 独有产品能力零回退。

**Architecture:** 原版 Mihon 只作为不可变行为来源。平台无关的规则、状态机、数据格式和错误模型进入当前分支 common source set；当前 Android 构建版与 Desktop JVM 实现只保留必要的平台 adapter、production wiring 和各自产品能力。迁移采用原版取证、特征测试、共享抽取、双轨比较、入口切换、数据迁移、删除旧路径的顺序。

**Tech Stack:** Kotlin Multiplatform、Compose Multiplatform、Voyager、Injekt、SQLDelight、OkHttp/MockWebServer、kotlinx.serialization/Protobuf、JUnit、Gradle、Desktop Test Mode。

## Global Constraints

- 所有“原版”结论必须绑定本计划的 `original-ref`；不得用当前工作树 `app/` 代替原版证据。
- 原版成熟能力完全对齐，Desktop 独有产品能力零回退。
- 非平台必需的 Desktop 重写不得继续作为第二套业务实现存在。
- 当前 Android 构建版只作为迁移后 shared core 的消费者和 Android 平台 adapter；它与 shared 实现的相互一致不能反向证明原版一致。
- Android 专属 API 使用 Desktop 等价适配器，不机械复制 WorkManager、Intent、PackageInstaller 或 Android View。
- 每项功能变化严格执行红—绿—重构；没有对应测试的功能代码不得提交。
- 导航、DI、HTTP、数据库和后台任务接点必须有集成测试。
- 数据迁移不得要求用户清空数据库、偏好、下载、备份或扩展目录。
- 一次发布只切换一个可独立验收的能力链。
- 普通 Task 使用定向测试或测试模式；Desktop change 的 verify/阶段交付使用 `scripts/build-desktop.sh`，并启动 `app-desktop/tmp/mihon-dist/main/app/Mihon Desktop/Mihon Desktop.exe` 核验完整版本号。
- 提交行为遵守当前 `AGENTS.md`；本路线图不另行扩大或收紧自动提交权限。
- 权威设计：`docs/superpowers/specs/2026-07-12-mihon-desktop-upstream-parity-design.md`；若其原版定义与本计划冲突，以本计划的固定 `original-ref` 和概念边界为准并同步修正文档。

---

## 1. 文件与模块目标结构

以下结构是全部项目群共同遵守的目标，不要求一次性创建空模块：

| 位置 | 单一职责 |
|---|---|
| `domain/src/commonMain/kotlin/...` | 当前分支跨端业务模型、用例、状态机、任务定义和错误分类 |
| `data/src/commonMain/kotlin/...` | 当前分支跨端 repository、mapper、序列化与 SQLDelight 访问 |
| `core/common/src/commonMain/kotlin/...` | 与领域无关的调度、结果、时间、网络契约工具 |
| `source-api/src/commonMain/kotlin/...` | 扩展/源的跨端契约与 preference schema |
| 当前分支 `app/src/main/java/...` | 当前 Android 构建版的 UI、WorkManager、Intent、通知、PackageManager adapter 与 production wiring |
| `app-desktop/src/main/kotlin/mihon/desktop/platform/...` | 文件、通知、调度、URI、凭据、窗口等 Desktop adapter |
| `app-desktop/src/main/kotlin/mihon/desktop/ui/...` | Desktop UI 与永久产品增强，不承载共享业务规则 |
| `app-desktop/src/main/kotlin/android/...` | 有真实扩展证据的 Desktop Android API 兼容 shim；不得作为原版或当前 Android 实现引用 |
| `app-desktop/src/test/...` | Desktop wiring、平台适配和产品增强回归 |
| 各模块 `commonTest`/`jvmTest` | 从固定原版行为提取的契约 fixture，以及当前 Android 构建版与 Desktop JVM 实现的共享契约测试 |

依赖方向固定为：`UI → shared use case → shared repository contract → common data/platform adapter`。Composable 禁止直接调用数据库 query、HTTP client、下载 manager 或 ClassLoader。

## 2. 里程碑与完成度

| 阶段 | 项目群 | 覆盖原编号 | 条目数 | 退出条件 |
|---|---|---|---:|---|
| 0 | 契约与保护网 | 全部 64 项的追踪基础 | 64 | 原版 provenance、矩阵校验、Desktop 独有特征测试清单、统一门禁可执行 |
| 1A | 共享架构、状态与模块边界 | 3, 4, 7, 12, 93, 95, 96 | 7 | 共享状态/错误/偏好契约可用，重复 wiring 有迁移路径 |
| 1B | 网络、后台任务与通知 | 8, 10, 11, 61 | 4 | 任务可恢复、通知事件共享、网络错误统一 |
| 2A | 备份与跨端兼容 | 71–74 | 4 | 固定原版备份可被 Desktop 读取，当前 Android 构建版与 Desktop JVM 实现写出同一 canonical protobuf，跨端 fixture 通过 |
| 2B | 下载、更新与历史 | 53, 56, 57, 59, 62, 64 | 6 | 队列重启恢复，进度事务统一，旧 manager 业务规则删除 |
| 3A | 书库与漫画详情 | 16, 17, 19, 22, 24, 26, 66 | 7 | UI 接入共享用例，批量/筛选/封面/统计对齐 |
| 3B | 迁移与追踪 | 67–70 | 4 | 单部/批量迁移对齐，Desktop tracker 可登录绑定同步 |
| 4A | 阅读器核心 | 9, 43–45, 47, 49, 51, 54 | 8 | shared 默认语义通过原版向量，当前 Android 构建版与 Desktop JVM 实现均消费，Desktop 增强全绿，大图内存验收通过 |
| 4B | 源、扩展与挑战 | 28–40, 87 | 13 | 原版源/扩展语义有 provenance，shared 状态被两端消费，安全与登录闭环，APK→JAR 保留 |
| 5A | 系统集成、隐私与发布 | 81–86, 92 | 7 | 支持项等价实现，不支持项明确平台豁免 |
| 5B | 设置、外观、无障碍与合规 | 88, 90, 91, 94 | 4 | 设置可搜索、资源国际化、焦点/许可入口完整 |
| 6 | 删除兼容债务与最终审计 | 全部 | 64 | 重复业务实现归零，64 项全部对齐或获准豁免，原版/shared/两端证据可追溯 |

总数校验：`7 + 4 + 4 + 6 + 7 + 4 + 8 + 13 + 7 + 4 = 64`。

## 3. 统一任务模板

每个能力链必须按以下小步骤执行，不允许跳过红测或先删除旧实现：

- [ ] **Step 0: 固定并核验原版来源**

  在子计划记录 `original-ref`、原版符号路径、原版测试/产物来源和当前分支对应消费者。使用 `git show <original-ref>:<path>` 读取原版；先运行 `git diff <original-ref>..<task-base> -- <path>`，防止把当前 Android 构建版新增行为当成原版。

- [ ] **Step 1: 写原版行为契约测试**

  从固定原版快照的测试/实现或其真实产物提取输入与期望结果，并记录 fixture provenance；测试应能在 Desktop 简化实现上暴露差异。当前 Android 构建版生成的 fixture 不得冒充原版 fixture。

- [ ] **Step 2: 运行契约测试并确认 RED**

  运行目标模块的精确测试任务；预期因 Desktop 尚未接入原版语义对应的共享契约而失败，不得因 fixture、DI 或编译错误失败。原版期望本身必须先独立成立。

- [ ] **Step 3: 写 Desktop 独有能力特征测试**

  固定本能力链会触达的永久增强。测试在迁移前必须 PASS，作为功能零回退基线。当前 Android 构建版独有但 `main` 不存在的行为必须先分类，不能自动写入 shared 默认值。

- [ ] **Step 4: 提取最小共享接口与实现**

  将固定原版实现中的平台无关语义迁入当前分支 shared core；当前 Android 构建版的 side effect 改为接口注入，Desktop JVM 实现提供等价 adapter。不得复制为新的 Desktop 业务类。

- [ ] **Step 5: 运行 shared、当前 Android 构建版与 Desktop JVM 测试并确认 GREEN**

  当前 Android 构建版与 Desktop JVM 实现必须使用同一组带原版 provenance 的 fixture；平台 adapter 测试只验证映射和 side effect。当前 Android 测试通过只证明消费和 wiring，不单独证明原版一致。

- [ ] **Step 6: 加入旧 Desktop 与 shared 新路径双轨比较**

  对旧 Desktop 路径与 shared 新路径输入同一 fixture，断言领域结果、状态转换和错误分类；shared 结果还必须先与原版期望一致。

- [ ] **Step 7: 先改 DI，再改 UI wiring**

  为 binding 解析、Screen 实例化、导航上下文和后台任务注册写集成测试，然后分别切换当前 Android 构建版和 Desktop JVM 实现中受影响的入口。

- [ ] **Step 8: 验证旧数据迁移**

  用可追溯的真实旧版 fixture 验证偏好、数据库、队列、备份或扩展元数据可读取，升级不要求清数据；分别标明原版产物、当前 Android 历史产物和 Desktop 历史产物。

- [ ] **Step 9: 运行 Desktop 产品增强回归**

  执行本计划指定的增强测试和 Test Mode 场景。

- [ ] **Step 10: 删除旧业务路径**

  只删除已被 shared 实现覆盖的 `TEMP-COMPAT`/重复规则；保留有明确技术理由的平台 adapter 和 `DESKTOP-PRODUCT`。与原版不同但有意保留的行为必须记录分类和保护测试。

- [ ] **Step 11: 重构并运行完整验证**

  运行 Spotless、目标单元/集成测试、`:app-desktop:jvmTest` 和必要 E2E，并确认原版 provenance 断言没有被当前实现自生成的 fixture 替代。

- [ ] **Step 12: 构建和人工验收**

  使用仓库 Desktop 构建脚本，启动固定 EXE，核对版本并按用户路径验收。

## 4. Task 0：建立原版取证、对齐追踪与 Desktop 保护网

**Files:**
- Create: `docs/desktop-parity/PARITY_TRACKER.md`
- Create: `app-desktop/src/test/kotlin/mihon/desktop/parity/DesktopProductCapabilityContractTest.kt`
- Modify: `docs/MIHON_ANDROID_DESKTOP_FEATURE_IMPLEMENTATION_COMPARISON.md`
- Modify: `docs/automation/TASK_TRACKER.md`

**Interfaces:**
- Consumes: 固定 `original-ref` 下重新核验的 64 项编号和设计文档第 6 节保护清单；不得直接继承“当前工作树 app/ 是原版”的旧结论。
- Produces: 每项唯一状态 `NOT_STARTED | CHARACTERIZED | SHARED | WIRED | VERIFIED | EXEMPT`；原版 provenance；shared、当前 Android 构建版和 Desktop JVM 消费路径；Desktop 产品能力测试套件入口。

- [ ] **Step 1: 写失败的追踪矩阵校验测试**

  在 `DesktopProductCapabilityContractTest` 中读取项目资源内的 parity manifest，断言包含 64 个唯一编号、合法状态、owner、`originalRef`、`originalImplementation`、`sharedImplementation`、`androidConsumer`、`desktopConsumer`、protection tests 和 intentional deviations；初始因字段或 manifest 缺失而失败。

- [ ] **Step 2: 运行 RED**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.parity.DesktopProductCapabilityContractTest"`

  Expected: FAIL，原因是 parity manifest/64 项映射或原版 provenance 尚未提供。

- [ ] **Step 3: 创建追踪矩阵**

  `PARITY_TRACKER.md` 每行必须包含：原编号、项目群、迁移标签、原版 ref/符号、shared 实现、当前 Android 消费路径、Desktop 消费路径、保护测试、状态、平台豁免证据、有意偏差及目标版本。迁移后的 shared 类不得填写到原版字段。

- [ ] **Step 4: 为永久增强建立聚合测试入口**

  聚合现有作者、Upcoming、Desktop 阅读器双页/自动滚动、APK 转换、Test Mode 测试；缺少的能力只新增特征测试，不修改实现。仅存在于当前 Android 构建版而不在固定原版中的行为，先作为 fork 增强审计，不得自动归入原版默认。

- [ ] **Step 5: 运行 GREEN**

  Run: `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.parity.*"`

  Expected: PASS，64 项唯一，原版来源可追溯，保护能力测试全部通过。

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
- Consumes: 固定原版调用链与偏好事实、当前分支现有 PreferenceStore、Injekt 和 ScreenModel 模式。

- [ ] **Step 1: 为错误映射、偏好默认值和旧 key 迁移写失败测试**
- [ ] **Step 2: 运行 common 与 Desktop 精确测试，确认因类型/迁移缺失而 RED**
- [ ] **Step 3: 从固定原版快照的调用链提取错误与任务状态语义，映射为 `AppError` 与 `TaskState`，禁止包含 Android 类型；不得把当前 `app/` 新增状态当成原版事实**
- [ ] **Step 4: 将 `java.util.prefs` 封装成 PreferenceStore backend，保留旧 key 双读迁移**
- [ ] **Step 5: 按 settings/reader/library/network/download/backup/extension 拆分 DI 注册函数**
- [ ] **Step 6: 写 DI 全解析测试，覆盖每个 UI 中使用的依赖**
- [ ] **Step 7: 逐 ScreenModel 切换共享状态，Composable 只 collect state/发送 intent**
- [ ] **Step 8: 运行 `:domain:allTests`、`:core:common:allTests` 和 `:app-desktop:jvmTest`**
- [ ] **Step 9: 删除被 PreferenceStore 覆盖的直接 `java.util.prefs` 业务访问**
- [ ] **Step 10: 更新追踪项 3、4、7、12、93、95、96 的原版 provenance、消费证据和状态**

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
- Consumes: TaskState/AppError/PreferenceStore from Task 1A，以及固定原版快照的任务定义、约束和幂等行为。

- [ ] **Step 1: 写任务持久恢复、取消和通知映射失败测试**
- [ ] **Step 2: 用临时目录模拟进程重启，确认当前 Desktop 内存 scheduler 无法恢复而 RED**
- [ ] **Step 3: 从固定原版快照提取任务定义、约束和幂等规则到当前 shared core；当前 Android 构建版的 WorkManager 只作为消费端和平台 adapter 取证**
- [ ] **Step 4: 实现 Desktop checkpoint store 与启动恢复，不复制 WorkManager API**
- [ ] **Step 5: 实现系统通知 adapter；不可用时回退到应用内通知并记录原因**
- [ ] **Step 6: 让 LibraryUpdateScheduler 消费共享任务，不再自有业务状态机**
- [ ] **Step 7: 用 MockWebServer 验证离线、403、429、500、畸形响应的统一 AppError**
- [ ] **Step 8: 运行网络、任务、更新和 Desktop wiring 测试，并用带原版 provenance 的行为向量验证 shared 默认值**
- [ ] **Step 9: 删除重复重试/错误字符串解析和仅内存任务状态**
- [ ] **Step 10: 更新追踪项 8、10、11、61 的原版 provenance、消费证据和状态**

## 7. Task 2A：备份格式与跨端兼容

**Files:**
- Modify: 当前分支 `app/src/main/java/eu/kanade/tachiyomi/data/backup/`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/backup/DesktopBackupCreator.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/backup/DesktopBackupRestorer.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/backup/models/BackupModels.kt`
- Create: `data/src/commonMain/kotlin/tachiyomi/data/backup/BackupCodec.kt`
- Create: `data/src/commonTest/resources/backup/android-full.tachibk`（必须由固定 `original-ref` 对应原版 Mihon 生成并记录 provenance）
- Create: `data/src/jvmTest/resources/backup/desktop-legacy.tachibk`
- Test: `data/src/commonTest/kotlin/tachiyomi/data/backup/BackupCodecContractTest.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/backup/DesktopBackupCompatibilityTest.kt`

**Interfaces:**
- Produces: 共享 canonical protobuf `BackupCodec`；固定原版备份格式与历史 Desktop 格式读取；当前 Android 构建版和 Desktop JVM 实现的 canonical protobuf 单写策略。
- Consumes: AppError/TaskState、固定 `original-ref` 中的 Backup schema/codec/validator、原版真实备份 fixture 和历史 Desktop schema/fixture。

- [ ] **Step 1: 使用固定原版 Mihon 构建保存真实完整备份并记录 commit/版本/生成路径；另保存旧 Desktop 备份 fixture**
- [ ] **Step 2: 写跨端读写失败测试：Desktop 读取原版备份；当前 Android 构建版和 shared codec 读取 Desktop 转换结果**
- [ ] **Step 3: 运行 RED，确认固定原版 protobuf、历史 Desktop protobuf 与 canonical protobuf 的兼容边界**
- [ ] **Step 4: 将固定原版 codec/validator 中的平台无关语义迁到 data common；不得从当前 Android writer 的新行为反推原版 schema**
- [ ] **Step 5: 当前 Android 构建版 creator 与 Desktop creator 统一为只写 canonical protobuf**
- [ ] **Step 6: 通过共享 codec 读取固定原版备份、具有明确版本 provenance 的其他原版历史备份与历史 Desktop protobuf，并映射到 canonical 共享模型**
- [ ] **Step 7: 对漫画、章节、分类、历史、追踪、偏好、源、扩展仓库逐字段断言，期望值以原版 fixture 和显式 Desktop legacy 迁移规则为来源**
- [ ] **Step 8: 为损坏、未知版本、部分恢复、取消和磁盘不足写集成测试**
- [ ] **Step 9: UI 显示预览、进度、逐项结果和可恢复错误**
- [ ] **Step 10: 运行当前 Android backup tests、data tests 和 Desktop backup tests；另验证原版 fixture provenance，不能把当前 Android 测试称为原版测试**
- [ ] **Step 11: 删除旧 Desktop writer；通过共享 codec 保留固定原版、其他有 provenance 的原版历史备份与历史 Desktop protobuf 读取兼容**
- [ ] **Step 12: 更新追踪项 71–74 的原版 schema/fixture、当前 Android consumer、Desktop consumer 和状态**

## 8. Task 2B：下载、更新、历史与阅读进度

**Files:**
- Modify: 当前分支 `app/src/main/java/eu/kanade/tachiyomi/data/download/`
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
- Produces: 持久下载队列、与固定原版语义一致的共享并发/重试/自动下载规则、原子阅读进度事务。
- Consumes: 固定原版下载/历史/进度行为，BackgroundTask/NotificationEvent、共享 repositories。

- [ ] **Step 1: 从固定原版快照建立状态转换、公平调度、重试、自动下载和阅读进度 fixture，并为当前 Desktop 差异写失败测试**
- [ ] **Step 2: 为进度/history/已读/tracker event 的单事务语义写失败测试；期望结果必须追溯到原版行为或单独批准的 bugfix**
- [ ] **Step 3: 运行 RED，确认 Desktop 内存队列和独立 progress tracker 暴露差异**
- [ ] **Step 4: 从固定原版快照提取队列状态机、自动下载规则和 retry/backoff；先比较当前 `app/` 与原版，禁止从当前 Android 构建版新增逻辑反推原版**
- [ ] **Step 5: 实现 SQLDelight/持久 store，并迁移当前 Desktop queue snapshot**
- [ ] **Step 6: Desktop manager 收敛为文件下载 adapter，不再决定业务状态转换**
- [ ] **Step 7: Updates/History ScreenModel 直接消费共享 use case 和下载状态**
- [ ] **Step 8: ReaderProgressTracker 改为提交共享阅读事件，事务内联动 tracker**
- [ ] **Step 9: 保留 CBZ、目录选择、Upcoming 与 Test Mode 回归**
- [ ] **Step 10: 运行 shared、当前 Android 下载/历史测试、Desktop 集成测试和 E2E；当前 Android 测试只证明当前消费者 wiring**
- [ ] **Step 11: 删除旧内存队列/重复自动下载和进度规则**
- [ ] **Step 12: 更新追踪项 53、56、57、59、62、64 的四方证据和状态**

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
- Consumes: 固定原版快照中的 domain use case 行为和 LibraryFlags，当前分支共享 repositories、TaskState/AppError。

- [ ] **Step 1: 从固定原版快照提取分类排序、筛选组合、范围选择和部分失败 fixture，写共享契约测试**
- [ ] **Step 2: 写 Desktop 鼠标/Shift 多选、宽屏布局、作者入口保护测试**
- [ ] **Step 3: 运行 RED，记录 Desktop 重写与固定原版结果差异；当前 Android 构建版只作为额外差分对象**
- [ ] **Step 4: 将固定原版中缺失于 shared 的规则迁入共享 use case；当前 shared 中已与原版一致的 use case 直接接线**
- [ ] **Step 5: ScreenModel 只组合共享 state，UI 只发送 intent**
- [ ] **Step 6: 封面文件选择通过 adapter，缓存失效/错误使用共享模型**
- [ ] **Step 7: StatsScreen 消费共享统计聚合，不在 Composable 查询/计算**
- [ ] **Step 8: 补 Screen 实例化、导航和 DI wiring 测试**
- [ ] **Step 9: 运行 library/detail/stats 与 Desktop 产品保护测试**
- [ ] **Step 10: 删除 DesktopCategoryManager 等已被共享实现完全覆盖的业务类**
- [ ] **Step 11: 更新追踪项 16、17、19、22、24、26、66 的原版 provenance、shared 和两端消费证据**

## 10. Task 3B：迁移与追踪

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/domain/DesktopMigrateMangaUseCase.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/migration/`
- Modify: 当前分支 `app/src/main/java/eu/kanade/tachiyomi/data/track/`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopOAuthCallbackServer.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopCredentialStore.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/ui/tracking/TrackingSettingsScreen.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/migration/DesktopMigrationParityTest.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/tracking/DesktopTrackingIntegrationTest.kt`

**Interfaces:**
- Produces: 与固定原版语义一致的共享单部/批量迁移编排；Desktop OAuth callback、凭据存储和 tracker UI。
- Consumes: 固定原版快照中的 tracker manager/API/domain 和迁移/自动同步行为；当前分支阅读进度事件与共享任务系统。

- [ ] **Step 1: 从固定原版快照为迁移选项、章节匹配、分类/阅读状态复制写契约测试**
- [ ] **Step 2: 为批量取消、恢复点和逐项失败写 RED 测试**
- [ ] **Step 3: 从固定原版快照抽取迁移编排语义，Desktop use case 改为薄调用器；先审计当前 Android 构建版是否已偏离原版**
- [ ] **Step 4: 将固定原版 tracker API/domain 中平台无关逻辑迁到当前 shared source set；当前 `app/.../track` 改为 Android adapter/consumer**
- [ ] **Step 5: 实现 loopback OAuth callback 和 OS credential store adapter**
- [ ] **Step 6: 新增 Tracker 设置、登录、搜索绑定、状态/分数/章节编辑 UI**
- [ ] **Step 7: ReaderProgress 事件接入从固定原版提取的自动同步策略和重试任务**
- [ ] **Step 8: MockWebServer 覆盖每类认证、刷新 token、429、500 和畸形响应**
- [ ] **Step 9: 保护作品比较、宽屏迁移队列和 Test Mode**
- [ ] **Step 10: 运行 migration/tracker/reader shared、当前 Android consumer 和 Desktop integration tests**
- [ ] **Step 11: 删除 Desktop 重复迁移业务规则**
- [ ] **Step 12: 更新追踪项 67–70 的原版 tracker provenance、shared 和两端消费证据**

## 11. Task 4A：阅读器共享核心

**Files:**
- Modify: 当前分支 `app/src/main/java/eu/kanade/tachiyomi/ui/reader/`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/reader/`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/reader/`
- Create: `domain/src/commonMain/kotlin/mihon/domain/reader/ReaderPageModel.kt`
- Create: `domain/src/commonMain/kotlin/mihon/domain/reader/ReaderNavigation.kt`
- Create: `domain/src/commonMain/kotlin/mihon/domain/reader/PageTransform.kt`
- Test: `domain/src/commonTest/kotlin/mihon/domain/reader/ReaderParityContractTest.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/ui/reader/DesktopReaderProductRegressionTest.kt`

**Interfaces:**
- Produces: 与固定原版默认行为一致的共享页面/章节/过渡状态、宽页拆分、配对、预加载窗口、导航区域、跳过和滤镜参数。
- Consumes: 固定原版 reader 页面模型、viewer/loader 行为和真实测试向量；当前分支 AppError/TaskState、阅读进度事件、平台 decoder/cache adapter。

- [ ] **Step 1: 只从固定原版快照的页面模型、viewer/loader 和测试建立真实测试向量；运行 `git diff <original-ref>..<task-base>` 排除当前分支新增的 Android 双页/配对行为**
- [ ] **Step 2: 写宽页反转/旋转、缺章、重试、预加载取消、导航反转、灰度/反色 RED 测试；每项期望标明原版符号或另行批准的 bugfix**
- [ ] **Step 3: 写 Desktop 双页、edge matching、自动滚动、键鼠、右键保存保护测试；仅存在于当前 Android 构建版而不在原版中的行为也先归为 fork 增强，不进入 shared 默认**
- [ ] **Step 4: 将固定原版中的纯页面模型/算法语义迁到 domain common，不移动 Android View；原版没有的增强通过显式 options 或独立 bugfix 表达**
- [ ] **Step 5: 定义 `PageDecoder`/`RegionDecoder`/`PageCache` 平台接口**
- [ ] **Step 6: Desktop 使用 Skia codec/tiles 实现区域解码和内存预算**
- [ ] **Step 7: 当前 Android 构建版 viewer 与 Desktop JVM viewer 同时消费共享状态/命令；当前 Android wiring 不得被称为原版实现**
- [ ] **Step 8: 用同一原版章节 fixture 比较原版期望、shared 结果、当前 Android consumer 与 Desktop consumer 的页序、过渡、跳过和错误结果**
- [ ] **Step 9: 运行内存/大图测试，验证不会全尺寸长期驻留**
- [ ] **Step 10: 运行 reader common、当前 Android reader、Desktop reader 和 Test Mode 测试；当前 Android 测试只作为消费者证据**
- [ ] **Step 11: 删除 Desktop 重复拆页、导航和跳过规则；保留渲染/输入增强及显式分类的 fork 增强**
- [ ] **Step 12: 更新追踪项 9、43、44、45、47、49、51、54 的原版向量、shared 默认、当前 Android/Desktop 消费及有意偏差**

## 12. Task 4B：源、扩展与挑战处理

**当前执行映射：** 本节是父 roadmap 的 Task 4B；详细施工位于
`docs/superpowers/plans/2026-07-15-mihon-source-extension-shared-core.md`。下列 Task 1–7 是
Task 4B 的子计划编号，不是父 roadmap 在 Task 6 之后新增的同级任务。

- [x] 子计划 Task 1：权威 fixture、调用链清单与产品保护网
- [x] 子计划 Task 2：共享源查询状态、分页与错误语义
- [x] 子计划 Task 3：共享扩展目录、版本、仓库部分失败与信任模型
- [x] 子计划 Task 4A–4D：共享安装事务及 Android/Desktop 平台事务 adapter
- [x] 子计划 Task 5A–5C：共享登录会话、Desktop 挑战恢复、设置与 UI wiring
- [x] 子计划 Task 6A–6E：Browse/Extension 双端 production wiring、i18n、导航与 Test Mode
- [x] 子计划 Task 7：compat 去重、parity 证据、最终审查与 Windows/macOS 验收

子计划 Task 7 已完成逐符号 compat 真实扩展证据、无使用 shim/重复规则删除、parity 28–40/87 更新、
Android 模拟器、Windows/macOS 全量与运行时验收及最终独立审查。最终精确 HEAD 为 `84e386c49`：
Windows `0.11.14.43.84e386c` 固定 EXE 与 macOS `0.11.14.44.84e386c` 部署应用均通过 smoke 88/88
及 Browse→Extension→search Test Mode；macOS 1817 项仅保留已限定的 SSH Keychain prerequisite skip，
零 failure/error。原版权威仍为 fixed main；当前 Android 与 Desktop 仅作为 consumer/adapter 验收证据。

**Files:**
- Modify: 当前分支 `app/src/main/java/eu/kanade/tachiyomi/extension/`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/extension/`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/source/`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/network/`
- Modify: `app-desktop/src/main/kotlin/android/`（Desktop Android API 兼容 shim，不是原版或当前 Android 构建版）
- Modify: `source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/`
- Create: `domain/src/commonMain/kotlin/mihon/domain/extension/ExtensionInstallState.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopWebLoginAdapter.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/extension/ExtensionParityIntegrationTest.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/network/DesktopWebLoginIntegrationTest.kt`

**Interfaces:**
- Produces: 与固定原版默认语义一致的共享源列表/浏览/搜索状态，扩展发现/安装/安全/更新契约；Desktop web login/challenge adapter。
- Consumes: 固定原版快照中的 ExtensionApi/Manager/Loader、Sources/GlobalSearch/Extensions UI state 和 index 行为；当前分支 extensionrepo domain、网络/任务/通知、PreferenceStore、AppError。

- [x] **Step 1: 从固定原版快照及其真实产物建立 index、已安装包、source、状态与操作 fixture；每项记录 commit、符号、包版本和测试来源**
- [x] **Step 2: MockWebServer 覆盖原版源分页成功/空/403/429/500/畸形响应，并证明 shared 解析路径消费同一 fixture**
- [x] **Step 3: 写 JAR、APK→JAR、损坏产物、版本替换、回滚和不兼容 API 测试；APK→JAR 是 Desktop 产品能力，不冒充原版流程**
- [x] **Step 4: 写 Desktop Android API 兼容 shim 使用清单测试，未被真实扩展引用的 stub 不得扩张；该 shim 不参与原版权威实现映射**
- [x] **Step 5: 从固定原版快照提取源/扩展状态、版本、安全和 preference schema 到 shared；先比较当前 Android 构建版与原版，分离已发生的 fork 改动**
- [x] **Step 6: Desktop loader/installer 只实现目录、ClassLoader、转换和进程隔离 side effect**
- [x] **Step 7: 以固定原版安装/信任/更新语义为默认实现签名/哈希信任、仓库信任、更新事务和失败回滚；若修复原版 bug，另建 cross-platform bugfix 并记录偏差**
- [x] **Step 8: 实现 Desktop 浏览器登录/Cookie 回传；FlareSolverr 保留为可选后备**
- [x] **Step 9: 当前 Android 构建版与 Desktop 的源列表、单源浏览、全局搜索、扩展详情/设置分别接入 shared state/intent；不得用当前 Android UI 行为反向生成原版 fixture**
- [x] **Step 10: 将所有 Desktop 文案迁入 i18n 资源并测试缺 key**
- [x] **Step 11: 运行 shared、当前 Android extension/source、Desktop network/DI/navigation/Test Mode 全链测试，并单独核验原版 provenance**
- [x] **Step 12: 删除 Desktop 重复搜索、版本判断、错误字符串和无使用证据的 compat shim；保留有真实扩展证据的平台 API**
- [x] **Step 13: 更新追踪项 28–40、87 的原版来源、shared、当前 Android/Desktop 消费、Desktop 增强和 shim 证据**

## 13. Task 5A：系统集成、隐私与应用更新

**Executable child plan:** `docs/superpowers/plans/2026-07-21-mihon-desktop-platform-integration.md`

**Technical design:** `docs/superpowers/specs/2026-07-21-mihon-desktop-platform-integration-design.md`

子计划恢复既有 `align-desktop-platform` change，并把本节 11 个父步骤拆为有 TDD、范围、审查和验证门禁的 Task；本节只在相应子计划证据完成后同步勾选。

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
- Produces: 与固定原版产品语义一致的 shared deep-link parser/share payload/lock policy/update state；各 OS adapter。
- Consumes: 固定原版快照中的 URI parser、分享、安全和更新状态；当前分支 AppError/TaskState、ReleaseService、PreferenceStore、Desktop task/notifier。

- [x] **Step 1: 从固定原版快照为 URI 解析、无效链接、分享 payload、锁定超时、版本比较建立 fixture，并写共享 RED 测试**
- [x] **Step 2: 为当前 OS 能力探测和平台豁免显示写 Desktop RED 测试**
- [x] **Step 3: 将固定原版 URI parser、分享模型、安全策略和 release 状态中的平台无关语义迁入 shared；当前 Android 构建版仅作为 Android adapter/consumer**
- [x] **Step 4: 实现 Windows/macOS/Linux scheme 注册与单实例转发 adapter**
- [x] **Step 5: 实现系统分享/剪贴板后备和 OS credential-backed app lock**
- [x] **Step 6: 在支持的平台实现窗口隐私；不支持时 UI 明确说明而非静默成功**
- [x] **Step 7: 对齐固定原版的下载、校验、安装、失败回滚更新状态机；平台安装 side effect 由各端 adapter 实现**
- [x] **Step 8: 将 Widget 标记为平台豁免，仅共享更新数据 provider 契约**
- [x] **Step 9: 补设置 UI、确认对话框、错误反馈和导航/DI 测试**
- [x] **Step 10: 运行三 OS 可执行的单元测试矩阵及当前 Windows 集成验收**
- [x] **Step 11: 更新追踪项 81–86、92 的原版 provenance、shared/adapter 证据和豁免说明**

**Completion status:** 父 Task 5A 与 `align-desktop-platform` 子计划 Tasks 1–16 已完成。whole-change 独立审查对 `952be2f789..2e94748f7` 给出 Critical/Important/Minor `0/0/0`；集中验收及唯一修复提交 `d2132c3b9` 的复审同样为 `0/0/0`。固定原版 provenance、shared/current Android/Desktop production wiring、Desktop 独有能力、IDs 81–86/92、全量 JVM/Android 模拟器/Windows/macOS/Linux-WSL 能力矩阵均有证据；最终 Desktop 版本为 `0.11.14.44.6062ebe`，固定 Windows EXE 为 `D:\Shell\Github\mihon\app-desktop\tmp\mihon-dist\main\app\Mihon Desktop\Mihon Desktop.exe`。macOS SSH 与 WSL 无法替代的 GUI/Keychain/Secret Service 人工交互边界已在 `docs/superpowers/reports/2026-07-21-align-desktop-platform-verify.md` 明确保留，不虚报通过。父 Steps 1–11 全部勾选，下一项为父 Task 5B。

## 14. Task 5B：设置、外观、无障碍与许可

**Executable child plan:** `docs/superpowers/plans/2026-07-22-mihon-desktop-settings-accessibility.md`

**Current progress:** 固定原版 ID 88/90/91/94 与当前 shared/Android/Desktop 的两份只读盘点已完成。子计划初审 `0/7/1`、唯一修复复审 `0/2/0` 后，按门禁只重规划剩余的 palette 模块迁移顺序/可见性风险：Base 在第一批先迁移，每批提供可独立编译的跨模块 API，最后统一 selector；其余 finding 均已关闭。项目 guard 已识别并通过内部子 Task 正文。子 Task 1 由实现 `c10e22a83`、唯一修复 `d0a069bde` 完成 fixed-main provenance 与 exact fixture 契约，复审 APPROVED `0/0/0`，focused `38/38`、严格 JSON、Spotless 与范围门禁通过，manifest 保持 `NOT_STARTED`。子 Task 2 由实现 `7d0a368f7`、唯一测试修复 `325f9568e` 建立 fixed-main shared 搜索纯策略；三项 mutation 均取得精确 RED，复审 APPROVED `0/0/0`，focused `7/7`、Task 1 provenance、Spotless 与范围门禁通过。子 Task 3 实现 `14a3f5bf7`、唯一修复 `8f01dcb91` 完成 Android projection、shared 行为、anchor/滚动与单双栏导航；其唯一复审发现的 production 默认 shared identity seam 已由重规划子 Task 3R 提交 `ea02ddfa2` 消除。3R 以设备 caller→helper RED 和 JVM helper→shared policy RED 锁定完整 production 链，API 36 `5/5`、consumer `2/2`、shared `7/7`、provenance `6/6` 及 Spotless/diff/guard 全部通过，独立审查 APPROVED `0/0/0`；子 Task 3 与 3R 已同时关闭。子 Task 4A 由实现 `4975cca27`、唯一修复 `914b49336` 完成 More/General/Appearance 文案 MR 同源化，纠正 Tracking 能力描述并锁定零队列 title→subtitle；最终回归 `57/57`、累计 `6 files/371 touched`，唯一修复复审 APPROVED `0/0/0`。原子 Task 4B 的四页 GREEN 实际为 `7 files/416 touched`，已拒绝通过格式压缩掩盖超限；修订子 Task 4B 由实现 `1de3bee43`、唯一测试修复 `ddb910099` 完成 Reader/Library/Download 三页 MR 同源化和全部更新频率状态证据；必要回归 `73/73`、复审限定重跑 `31/31`，累计 `6 files/229 touched`，唯一修复复审 APPROVED `0/0/0`。子 Task 4C–4F 的 Backup 本地化与 production wiring 已全部闭合：4C 实现 `9e94d417f` 完成主屏/预览/进度/基础终态和 interval；4D 实现 `8273e0482` 保留 typed Failure/原始 AppError/PartialFailure 并统一纯 formatter；4E 实现 `714afd1ae`、唯一测试修复 `3f2db5843` 完成 required picker、真实按钮/反馈、Swing config、DI identity 与死分支删除；4F 实现 `abc347e1b` 让六种 typed preview reason 由真实 Model→Screen 在 en/zh 共 12 组合验证。最终组合回归 `58/58`、4F 独立审查 APPROVED `0/0/0`，并确认 4C/4D 遗留拒绝点全部关闭；4C–4F 均已勾选。原 Security/Advanced 合并 GREEN 工作树为 `5 files/380 touched`，但默认 JUnit 并行暴露进程 Locale 泄漏；正确 `@Isolated` 修复会超限，已拆为 4G Advanced 与 4H Security/locale isolation，原 About/Extension/Tracking 已顺延，并在实作前按独立 Screen 与 Tracking typed-message/其余 UI 的稳定边界拆为 4I–4L。子 Task 4G 由实现 `99ee9dea9`、唯一修复 `81888828d` 完成 Advanced MR 同源化，并以 production platform actions 在 en/zh 真实 Screen 中锁定缓存计算瞬态及崩溃日志目录成功/失败反馈；focused `6/6`、默认并行相关回归 `102/102`，累计 `4 files/291 touched`，唯一修复复审 APPROVED `0/0/0`。子 Task 4H 由实现 `3ee98f9dd`、唯一测试修复 `bc4aa0f6d` 完成 Security MR 同源化与默认并行安全的 Locale 隔离；credential 字段与 native/telemetry/widget capability 三类 identity mutation 均精确 RED，Identity `7/7`、默认并行 `103/103`，累计产品/测试/资源 `4 files/353 touched`，唯一修复复审 APPROVED `0/0/0`。原合并子 Task 4I 的只读盘点为 `920–1,130 touched`，未产生实现改动；现拆为 4I About/updater、4J ExtensionRepo、4K Tracking typed message/formatter 与 4L Tracking UI/dialog，单项均不超过 400。子 Task 4I 由实现 `fa8a89468`、唯一测试修复 `85c1cd430` 完成 About/updater/诊断 MR 同源化；真实 controller/model 与 About Screen 覆盖全部 updater presentation、诊断和 manual fallback，focused `11/11`、相关回归 `120/120`，累计 `5 files/371 touched`，唯一修复复审 APPROVED `0/0/0`。子 Task 4J 由实现 `3649d64f9`、唯一修复 `7254180b4` 完成 ExtensionRepo MR 同源化与真实 repository/interactor wiring；open/copy/delete 按钮 identity、剪贴板 index URL 与 replace 参数均有 mutation 证据，focused `12/12`、相关回归 `88/88`，累计 `4 files/357 touched`，唯一修复复审 APPROVED `0/0/0`。子 Task 4K 由实现 `ebefc7eba`、唯一修复 `e214cee15` 完成 Tracking typed message 与唯一 formatter；四类 app-owned failure 已本地化，External 仅保留真实外部文本，真实 Screen 的 Status/Score 双语 production mutation 精确 RED，focused `18/18`、相关回归 `51/51`，累计 `6 files/399 touched`，唯一修复复审 APPROVED `0/0/0`。子 Task 4L 实现 `3c88a436` 完成 Tracking UI/dialog MR 迁移，但首审 `0/2/0` 发现未隔离 Locale 与真实动作/fallback 证据不足；唯一修复完整可读方案为 `403 touched`，按范围门禁停止并重规划：4L 只关闭状态/模式/editor 渲染与 Locale 隔离，新增测试专用 4M 关闭认证/确认动作副作用及三类双语 fallback，production 不再扩张。4L 修复 `3aa49af05` 删除 Locale 污染与资源枚举自证，真实 Screen 的 field/参数 mutation 精确 RED；focused `13/13`、指定回归 `70/70`、Spotless/diff/guard 通过，累计 `5 files/375 touched`，复审 APPROVED `0/0/0`。额外全模块的两个 `WindowPrivacyWiringTest` 失败可在隔离单类独立复现，与 4L diff 无依赖关系，不阻塞本 Task。子 Task 4M 由 test-only 实现 `661666c59` 以真实 editable/action 节点闭合认证参数、确认/取消副作用及 search/bind/update 三类双语 fallback；五种 mutation 精确 RED，相关回归 `71/71`，范围 `1 file/152 touched`，独立审查 APPROVED `0/0/0`，production/resources/4K 零差异。子 Task 5 由实现 `19c962bac`、唯一修复 `58497587c` 完成 Desktop catalog、搜索 Screen 与 More 入口；前九页/route/shared top10、Desktop-only 后置、真实焦点/键盘/IME/反馈/replace 均有 production mutation 证据，Locale 正常/异常路径恢复。搜索/导航/实例化 `53/53`、资源/More `14/14`、shared `7/7`、provenance `6/6`，累计 `8 files/400 touched`，唯一复审 APPROVED `0/0/0`。原子 Task 6 盘点发现 `SettingsSearchScreen` 当前 replace 时丢弃既有 `anchorTitle`，但计划文件范围未包含该 production 交接点；完整实现至少 9 files/365–485 touched，已在改代码前重拆为 6A（核心、搜索交接、General/Appearance）和 6B（Reader/Library），禁止用全局推断绕过。子 Task 6A 由实现 `e7c6d9598`、唯一测试修复 `6fec99d28` 完成 route-owned one-shot anchor、搜索交接、General/Appearance 真实滚动/高亮；exact/prefix、首重复项、错误 route、one-shot 与 focus 非依赖均有 mutation 证据，focused `7/7`、Desktop/provenance `80/80`、shared `7/7`，累计 `7 files/323 touched`，唯一复审 APPROVED `0/0/0`。子 Task 6B 由实现 `78533b61d` 让 Reader/Library 复用同一核心；两页真实滚动/可见/highlight/one-shot 与 PAGER/EVERY_6H 写入均受保护，focused `9/9`、相关 `106/106`、shared `7/7`，范围 `3 files/113 touched`，独立审查 APPROVED `0/0/0`。原子 Task 7 的四页真实 fixture 实测约 310 行，加 production/catalog 后预计 `395–405 touched` 超过 380，草稿已撤销并在代码零 diff 时重拆为 7A（Download/Backup）和 7B（Advanced/Security），两批串行修改 Catalog。子 Task 7A 由实现 `fd43e132d` 完成 Download/Backup catalog-page MR 同源与真实 anchor；Download preference、Backup picker cancel/snackbar、wrong route/title 与 one-shot 受保护，Desktop 相关 `137/137`、shared `7/7`，范围 `4 files/176 touched`，独立审查 APPROVED `0/0/0`。原 7B 的 Advanced+Security 真实场景成形后为 `284 touched`，超过 260；未提交该草案，已继续拆为 7B Advanced 与 7C Security，两批串行修改 Catalog 并分别保留平台 action/capability 证据。子 Task 7B 由实现 `b5c892c3c` 完成 Advanced catalog-page MR 同源、真实滚动/highlight/one-shot 与 openCrashLogFolder 成功/失败 Snackbar；Desktop 相关 `85/85`、shared `7/7`，范围 `3 files/178 touched`，独立审查 APPROVED `0/0/0`，Security 零差异。子 Task 7C 由实现 `b9c1f51a5` 完成 Security catalog-page MR 同源与真实 anchor；supported native toggle 写入真实 preference，telemetry/widget Unsupported copy 与仅 2 个合法 toggle 的边界受保护，相关 `106/106`、shared `7/7`，范围 `3 files/178 touched`，独立审查 APPROVED `0/0/0`。原子 Task 8 盘点估算 `430–520 touched` 超过 380，且 ExtensionRepo/Tracking 使用 LazyColumn、现有 ScrollState host 无法提供真实 LazyList 滚动证据；在代码零 diff 时重拆为 8A About、8B 共享 LazyList adapter+ExtensionRepo、8C Tracking 复用 adapter，禁止两个 Lazy 页面复制状态机。子 Task 8A 由实现 `6b82a2307` 完成 About catalog-page MR 同源与真实 anchor；固定九页/Desktop尾部/top10、真实清缓存及 updater/诊断回归受保护，相关 `113/113`、shared `7/7`，范围 `3 files/137 touched`，独立审查 APPROVED `0/0/0`。状态只以该子计划 checklist 和本父 roadmap 阶段摘要为准，不恢复 Comet/OpenSpec 或旧 progress 状态。子 Task 8B 由实现 `13d31e55a` 完成可复用 LazyList anchor 与 ExtensionRepo 空/列表分支接线；未预组合项滚动可见、exact/首重复项/wrong route/one-shot、真实删除 first URL 及 add/replace/open/copy/delete 保留均有 production mutation 证据，Desktop focused `96/96`、shared `7/7`，范围 `5 files/258 touched`，独立审查 APPROVED `0/0/0`。状态只以该子计划 checklist 和本父 roadmap 阶段摘要为准，不恢复 Comet/OpenSpec 或旧 progress 状态。子 Task 8C 由实现 `9e90cc5c9` 让 Tracking 复用同一 LazyList anchor；真实搜索跨 18 个未预组合服务定位首个 Login，并以 auto-sync preference 写入及 registry/auth/model/typed-message 回归保护 Desktop 能力，四项 mutation 精确 RED。Tracking/设置回归 `115/115`、shared `7/7`，范围 `4 files/212 touched`，独立审查 APPROVED `0/0/0`。状态只以该子计划 checklist 和本父 roadmap 阶段摘要为准，不恢复 Comet/OpenSpec 或旧 progress 状态。子 Task 9 由实现 `781b0f87a` 新建 Compose MPP `presentation-theme`，以 fixed-main R100 移动 AppTheme 并共享 ThemeMode、canonical key/default/codec 和 picker 可见性；Android UiPreferences 消费共享合同而平台动态色 adapter 保留。六类 mutation 与移除模块依赖的真实 Android compile RED 闭合，shared `7/7`、provenance `6/6`、Android/JVM 编译通过，范围 `8 files/167 touched`，独立审查 APPROVED `0/0/0`。状态只以该子计划 checklist 和本父 roadmap 阶段摘要为准，不恢复 Comet/OpenSpec 或旧 progress 状态。原子 Task 10A 的只读盘点确认五个 palette blob 与 fixed-main 完全一致，但共享 module 必须新增 Material3/UI classpath，完整最小范围为 8 文件，超过 7 文件门禁；代码零差异时按独立可编译边界重拆为新 10A（Base/Tachiyomi/GreenApple+build+两类测试）与新 10B（Lavender/Yotsuba+扩展两类测试），原 10B–10D 顺延为 10C–10E；编号已符合项目 guard 的“数字+至多一个字母”结构，不省略 wiring 证据。状态只以该子计划 checklist 和本父 roadmap 阶段摘要为准，不恢复 Comet/OpenSpec 或旧 progress 状态。子 Task 10A 由实现 `1ccf94908` 完成 Base/Tachiyomi/GreenApple 高相似 move、共享 classpath 与 Android production wiring；首审 `0/1/0` 的 exact-role 缺口由唯一测试修复 `9a7a91d4a` 补齐 36-role/AMOLED container 快照，三类 mutation 精确 RED，production 零差异。复审 APPROVED `0/0/0`，shared `10/10`、Android wiring `2/2`、provenance `6/6`、跨端编译通过，累计 `6 files/189 touched`。状态只以该子计划 checklist 和本父 roadmap 阶段摘要为准，不恢复 Comet/OpenSpec 或旧 progress 状态。子 Task 10B 由实现 `b22f80a87` 将 Lavender/Yotsuba 以 R98 高相似 move 到共享模块，完整 36-role light/dark 快照与 Android singleton/runtime origin 保护真实共享消费；缺失 type、旧 local origin 与 token mutation 均精确 RED。独立审查 APPROVED `0/0/0`，shared `12/12`、Android wiring `2/2`、provenance `6/6`、跨端编译通过，范围 `4 files/50 touched`。状态只以该子计划 checklist 和本父 roadmap 阶段摘要为准，不恢复 Comet/OpenSpec 或旧 progress 状态。子 Task 10C 由实现 `9720357f0` 将 Catppuccin/MidnightDusk/Monochrome/Nord 四套 palette 以 R98 高相似 move 到共享模块；完整 36-role light/dark 快照、Android singleton/runtime origin、缺 type/旧 local/token mutation 证据闭合。独立审查 APPROVED `0/0/0`，shared `16/16`、Android wiring `2/2`、provenance `6/6`、跨端编译通过，范围 `6 files/100 touched`。状态只以该子计划 checklist 和本父 roadmap 阶段摘要为准，不恢复 Comet/OpenSpec 或旧 progress 状态。子 Task 10D 由实现 `64dfbbd34` 将 Strawberry/Tako/TealTurqoise/TidalWave 四套 palette 以 R98 高相似 move 到共享模块；历史 enum/resource/type/map identity、完整 36-role 快照及 Android singleton/runtime origin 均保持，四项 mutation 精确 RED。独立审查 APPROVED `0/0/0`，shared `20/20`、Android wiring `2/2`、provenance `6/6`、跨端编译通过，范围 `6 files/100 touched`。状态只以该子计划 checklist 和本父 roadmap 阶段摘要为准，不恢复 Comet/OpenSpec 或旧 progress 状态。子 Task 10E 由实现 `edab97e54` 完成 YinYang R98 move、共享 static selector/AMOLED 与 Android Monet 注入；首审 `0/1/0` 的 null-adapter dark AMOLED container 错误由唯一修复 `3f9bdefdb` 改为依据实际 Monet adapter，并以 `0C/13/1B` RED 闭合。复审 APPROVED `0/0/0`，shared `26/26`、Android wiring `3/3`、provenance `6/6`、双端/consumer 编译通过，累计 `6 files/347 touched`，Task 11/Desktop 零 repair 差异。状态只以该子计划 checklist 和本父 roadmap 阶段摘要为准，不恢复 Comet/OpenSpec 或旧 progress 状态。子 Task 11 由实现 `32f35d1ef` 完成 Desktop shared ThemeMode/AppTheme/selector、canonical key/default/codec、legacy `theme_mode` 迁移、SYSTEM adapter 与 Appearance static theme/AMOLED/grid UI；MONET/deprecated 诚实隐藏，Catalog/Page anchor 同源。10 类 mutation 精确 RED，独立审查 APPROVED `0/0/0`，Desktop focused `254/254`、shared `26/26`、跨端编译与 Spotless 通过，范围 `8 files/280 touched`。状态只以该子计划 checklist 和本父 roadmap 阶段摘要为准，不恢复 Comet/OpenSpec 或旧 progress 状态。子 Task 12 由实现 `40e68eb89` 建立 shared immutable dependency notice/result 与 LicenseNoticePolicy；fixed-main first-license、blank website、locale-invariant 稳定排序、empty success/malformed failure 边界均由五类 mutation 闭合。独立审查 APPROVED `0/0/0`，common `9/9`、domain JVM/Android compile 与 Spotless 通过，范围 `3 files/205 touched`。状态只以该子计划 checklist 和本父 roadmap 阶段摘要为准，不恢复 Comet/OpenSpec 或旧 progress 状态。子 Task 13 由实现 `56b986d75` 复用 AboutLibraries 与真实 Desktop resolved JVM dependencies/POM 生成确定性 packaged resource；generated/processed SHA-256 相同，192 项含 coroutines/okio，反序声明字节一致，malformed POM 可诊断，删除输入/断 resource wiring mutation 闭合。独立审查 APPROVED `0/0/0`，functional `2/2`、integration `1/1`、offline export/resource/compile 与 Spotless 通过，范围 `5 files/222 touched`。状态只以该子计划 checklist 和本父 roadmap 阶段摘要为准，不恢复 Comet/OpenSpec 或旧 progress 状态。子 Task 14 由实现 `255fce295` 完成真实 generated resource→root license content→Task12 policy→DI singleton 链；首审 `0/1/0` 的完整 inventory 证据缺口由唯一测试修复 `2f537aaea` 锁定 192 项并以截断 mutation 闭合，production 零差异。复审 APPROVED `0/0/0`，provider/resource/DI `22/22`、offline Desktop compile 与 Spotless 通过，累计 `6 files/248 touched`。状态只以该子计划 checklist 和本父 roadmap 阶段摘要为准，不恢复 Comet/OpenSpec 或旧 progress 状态。子 Task 15 由实现 `b6a649de9` 完成 About→真实 192 项许可证列表→first-license 详情 Voyager UI；URI、blank/null content、provider Failure 双语反馈与列表/详情滚动闭合，About updater/诊断等独有能力保持。五类 mutation 精确 RED，独立审查 APPROVED `0/0/0`，focused `92/92`、Desktop compile 与 Spotless 通过，范围 `8 files/298 touched`。状态只以该子计划 checklist 和本父 roadmap 阶段摘要为准，不恢复 Comet/OpenSpec 或旧 progress 状态。子 Task 16 由实现 `38ffa700ce` 完成 Desktop 设置 accessibility primitives 与 More/Search/Appearance/General 入口接线；Enter/NumPadEnter 仅 KeyDown exact once，Space 限支持 action 的 role，整行单 action、状态/禁用语义、搜索自动聚焦/Tab 顺序与 anchor 非依赖均由真实 Compose 场景及六类 mutation 保护。独立审查 APPROVED `0/0/0`，focused `31/31`、Desktop compile 与 Spotless 通过，范围 `7 files/395 touched`。状态只以该子计划 checklist 和本父 roadmap 阶段摘要为准，不恢复 Comet/OpenSpec 或旧 progress 状态。子 Task 17 由实现 `3fd0707e12` 与收尾修复 `c4d34a5ae` 完成 Reader/Library/Download/Backup accessibility 第一批；Library Checkbox 整行单 action、Backup 两个真实按钮的 Enter/NumPad/Space KeyDown exact once、四页 role/state/focus 与 anchor 非依赖均由 production Compose 场景及断 helper mutation 保护，原 preference/picker/restore/download 行为保持。独立审查 APPROVED `0/0/0`，focused `106/106`、Desktop compile 与 Spotless 通过，累计 `4 files/360 touched`。状态只以该子计划 checklist 和本父 roadmap 阶段摘要为准，不恢复 Comet/OpenSpec 或旧 progress 状态。原子 Task 18 的五页只读盘点估算为 `9–11 files/650–850 touched`，超过 `7 files/400 touched`；代码零差异时按独立上下文簇重拆为 18A Security/Advanced、18B About/ExtensionRepo 与 18C Tracking，分别保护 credential/privacy/challenge/危险清理、updater/repository/确认反馈及 source-managed/身份字段/logout/unbind/LazyList，不压缩真实 Compose 证据。子 Task 18A 由实现 `0710e3a4f` 完成 Security/Advanced role/state/敏感字段/危险按钮键盘接线；首审 `0/1/0` 的默认 zh-CN WindowPrivacy 英文硬编码由唯一测试隔离修复 `435383d5cf` 以 PER_METHOD locale 保存/恢复关闭。复审 APPROVED `0/0/0`，默认 zh-CN WindowPrivacy `6/6`、联合 `113/113`、Desktop compile 与 Spotless 通过，累计 `5 files/389 touched`。状态只以该子计划 checklist 和本父 roadmap 阶段摘要为准，不恢复 Comet/OpenSpec 或旧 progress 状态。子 Task 18B 在 production 零提交的 RED/GREEN 草案范围复核中为 `3 files/361 touched`，超过原自定 350 但低于项目 400 硬门禁；行为与页面边界未扩张，拒绝格式压缩或拆分重复 Button/dialog 上下文，计划预算校正为 `3 files/380 touched`。子 Task 18B 首审 `0/2/0`：真实键盘测试通过 `Desktop.browse` 打开 `repo.example`，且 refresh/FAB add/add cancel/conflict cancel 缺具体物理键路径。唯一修复采用正式 URL opener adapter 隔离外部副作用并补齐 production exact-once 证据，保守累计 `3 files/396–400 touched`；计划上限最终校正为项目硬门禁 400，超过即停止重规划。18B 合并唯一修复草案实测 `3 files/439 touched`，在 GREEN 前按 400 硬门禁停止；现重规划为 18B 只关闭正式 URL opener/系统浏览器隔离与 open exact-once，新增 18C 独立关闭 ExtensionRepo refresh/FAB add/add cancel/conflict cancel，原 Tracking 顺延为 18D。子 Task 18B 由实现 `b0b223153` 完成 About/ExtensionRepo 基础键盘接线；首审 `0/2/0` 发现真实测试经 `Desktop.browse` 打开 `repo.example` 与四条具体键盘路径缺口。合并修复草案 439 行触发重规划，后者拆至 18C；唯一修复 `a65abec2a` 以正式 URL opener CompositionLocal 隔离系统副作用并锁定 open exact-once。复审 APPROVED `0/0/0`，安全相关 `35/35`、ArchitectureGuard `4/4`、Desktop compile 与 Spotless 通过，复审系统浏览器副作用为 0，累计 `3 files/390 touched`。状态只以该子计划 checklist 和本父 roadmap 阶段摘要为准，不恢复 Comet/OpenSpec 或旧 progress 状态。子 Task 18C 由 test-only `6dd0174ce` 与唯一修复 `186f7c355` 完成四条真实键盘路径及 production FAB/dialog counter 合同，范围 `2 files/122 touched`、浏览器副作用 0；首审与唯一复审均 `0/1/0`，最终仅剩组合回归中 `scope.launch` 未调度即 `coVerify` 的可复现竞态（44 项中 1 失败，隔离类 5/5）。按门禁停止第二修复，18C 保持未勾选，新增 18D 以可观察等待关闭验收阻塞，原 Tracking 顺延 18E；18D 通过后同时关闭 18C/18D。下一项为父 Task 5B / 子 Task 18D。

**Files:**
- Modify: 当前分支 `app/src/main/java/eu/kanade/presentation/more/settings/`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/settings/`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/ui/theme/DesktopTheme.kt`
- Create: `domain/src/commonMain/kotlin/mihon/domain/settings/SearchablePreference.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/ui/settings/SettingsSearchScreen.kt`
- Create: `app-desktop/src/main/kotlin/mihon/desktop/ui/settings/OpenSourceLicensesScreen.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/ui/settings/DesktopSettingsParityTest.kt`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/ui/accessibility/DesktopAccessibilityContractTest.kt`

**Interfaces:**
- Produces: 与固定原版产品语义一致的共享可搜索 preference model、主题语义、许可数据；Desktop 搜索/主题/许可 UI。
- Consumes: 固定原版快照中的 SearchableSettings、主题 key/default 和迁移行为；当前分支 PreferenceStore、i18n、Voyager navigator、构建生成的许可 metadata。

- [ ] **Step 1: 从固定原版快照为设置索引、关键词、隐藏项和结果路由建立期望，写 RED 测试**
- [ ] **Step 2: 从固定原版快照为主题 key/default/migration 建立期望，并为许可 metadata 写 RED 测试**
- [ ] **Step 3: 将固定原版 SearchableSettings/主题语义迁为共享模型；先区分当前 Android settings 已有的 fork 改动**
- [ ] **Step 4: Desktop 新增设置搜索并映射到现有 Screen/锚点**
- [ ] **Step 5: 将 Desktop 专属外观项叠加到共享主题模型，不复制固定原版 key，也不把当前 Android 构建版新增 key 冒充原版 key**
- [ ] **Step 6: 构建阶段生成 Desktop 依赖许可数据并提供详情页**
- [ ] **Step 7: 为所有交互控件补语义标签、焦点顺序和纯键盘操作**
- [ ] **Step 8: 用屏幕阅读器可观察语义树/Compose 测试验证关键页面**
- [ ] **Step 9: 补 Screen 实例化、导航、DI 和资源完整性测试**
- [ ] **Step 10: 运行 settings/theme/i18n/accessibility shared、当前 Android consumer 和 Desktop 测试**
- [ ] **Step 11: 更新追踪项 88、90、91、94 的原版 provenance、shared 和两端消费证据**

## 15. Task 6：删除重复实现与最终审计

**Files:**
- Modify: `docs/desktop-parity/PARITY_TRACKER.md`
- Modify: `docs/MIHON_ANDROID_DESKTOP_FEATURE_IMPLEMENTATION_COMPARISON.md`
- Modify: `docs/automation/TEST_COVERAGE_REPORT.md`
- Modify: `docs/automation/TASK_TRACKER.md`
- Modify: `app-desktop/build.gradle.kts`
- Test: `app-desktop/src/test/kotlin/mihon/desktop/parity/DesktopProductCapabilityContractTest.kt`

**Interfaces:**
- Consumes: 固定原版证据、所有项目群的 shared 接口、当前 Android/Desktop consumer、adapter、测试和追踪证据。
- Produces: 64 项最终状态、原版/shared/两端职责审计、剩余平台 adapter 清单、零重复业务实现证明和维护文档。

- [ ] **Step 1: 扫描 64 项追踪矩阵，确认每项为 VERIFIED 或 EXEMPT，且 `originalRef` 与本计划一致**
- [ ] **Step 2: 对每个 EXEMPT 检查 OS 能力证据、UI 边界说明和用户批准记录**
- [ ] **Step 3: 建立重复实现审计表，逐项对照固定原版 ref/符号、迁移后 shared 实现、当前 Android consumer、Desktop consumer 与平台 adapter 职责**
- [ ] **Step 4: 为不允许的 UI→data/network/manager 依赖增加架构测试**
- [ ] **Step 5: 删除已无调用的临时 adapter、旧 writer、重复状态机和无证据 Desktop Android API shim**
- [ ] **Step 6: 运行 `./gradlew spotlessCheck`**
- [ ] **Step 7: 运行相关 shared 与当前 Android 单元/集成测试；测试输入必须有原版 provenance，当前 Android 测试不得单独充当原版一致性证明**
- [ ] **Step 8: 运行 `./gradlew :app-desktop:jvmTest` 和 `./gradlew :test-desktop:test`**
- [ ] **Step 9: 运行 Desktop smoke test/Test Mode 全场景，核验保护清单零回退**
- [ ] **Step 10: 使用 `scripts/build-desktop.sh` 生成新 BUILD**
- [ ] **Step 11: 启动固定未打包 EXE，核对窗口版本、文件时间和所有核心用户路径**
- [ ] **Step 12: 更新比较报告：重新以固定原版快照比较，64 项改为已对齐、有意 cross-platform bugfix 或有证据的平台豁免**
- [ ] **Step 13: 在完成报告中列出原版 ref、完整 Desktop 版本、EXE 路径、测试命令、失败数、剩余有意偏差和豁免**

## 16. 每阶段统一验证命令

按改动范围选择精确测试；阶段退出前至少运行：

```bash
./gradlew spotlessCheck
./gradlew :domain:allTests
./gradlew :data:allTests
./gradlew :app-desktop:jvmTest
./gradlew :test-desktop:test
```

当前 Android 构建版消费了从原版迁移的 shared 行为时，补充：

```bash
./gradlew testReleaseUnitTest
```

该命令验证当前分支 Android consumer 和 wiring，不单独证明原版一致。原版一致性还必须由以下证据成立：

1. 子计划记录的固定 `original-ref` 与原版符号；
2. `git show <original-ref>:<path>` 或原版真实产物；
3. 带 provenance 的原版 fixture；
4. shared 默认结果与原版期望的精确断言。

触及 Desktop UI wiring、后台任务或真实窗口行为时，补充：

```bash
./scripts/desktop-smoke-test.sh
```

每个 Desktop 非测试阶段交付最终只能通过以下脚本构建，不得直接用 Gradle 部署：

```bash
./scripts/build-desktop.sh
```

Windows 验收固定路径：

```text
app-desktop/tmp/mihon-dist/main/app/Mihon Desktop/Mihon Desktop.exe
```

## 17. 执行纪律

- 开始一个项目群前，从本路线图复制该 Task 到独立计划文件，必须补充固定 `original-ref`、原版符号/产物 provenance、当前 Android consumer、Desktop consumer、准确行号、测试代码和各类 fixture；“当前版本 fixture”不得替代原版 fixture。
- 每次开始 Task 都先运行 `git diff <original-ref>..<task-base> -- <原版相关路径>`，识别当前 Android 构建版在 Task 前已经存在的 fork 改动。
- 不并行修改共享基础接口与其多个消费者；先用原版 fixture 稳定接口和契约测试，再并行迁移独立消费者。
- 不允许为通过 Desktop 测试而改变固定原版权威行为。
- 若固定原版行为本身有 bug，建立独立 cross-platform bugfix change：当前 Android 构建版和 Desktop JVM 实现共同消费修正，并在 parity tracker 记录相对原版的有意偏差；不得把修正后的 shared/current Android 行为重新称为原版行为。
- 当前 Android 构建版与 Desktop JVM 实现使用同一 shared 类、同一自生成 fixture 或彼此一致，只能证明内部一致，不能替代原版 provenance。
- 未通过 Desktop 独有能力回归，不得删除旧路径。
- 未完成原版、当前 Android 历史产物和 Desktop 历史产物的数据兼容验证，不得切换 writer。
- 未具备真实系统能力，不得把平台豁免条目标记为 VERIFIED。
- 提交、推送和 PR 行为遵守当前 `AGENTS.md`、活动 `/goal` 与当前 Task 计划的权限和门禁，本路线图不覆盖它们。
