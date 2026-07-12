# Mihon Android / macOS / Windows 正式 Roadmap 与进度追踪表

来源审计报告：`/Volumes/File/OpenClaw/workspace/mihon/memory/2026-06-30-pensive-vaughan-audit-report.md`

正式版日期：2026-06-30

## 0. 产品路线

本 roadmap 固定以下产品路线，后续重构不得偏离：

- Android：以原版 Mihon `app/` 为产品基线，不基于 `app-desktop` 改造。
- macOS / Windows：以当前 `app-desktop` 为产品基线，继续推进 Compose Desktop 版本。
- 共享范围：优先共享 domain、data、source、backup、纯算法、测试资产和功能规格。
- 不共享范围：不共享 Desktop UI、Desktop DI、Desktop scheduler、Desktop ClassLoader、Android stubs、Swing/AWT 交互和桌面打包脚本。
- 当前执行方针：先以 macOS 上的 `app-desktop` 为主平台做功能迭代和核心重构；Windows 实际构建发布、Android 实际合并发布不阻塞当前主线，统一移入延期队列。

目标：

1. 可持续维护迭代：代码边界清晰，业务逻辑可测试，平台能力可替换，发布问题可诊断。
2. 长期可发布 Android / Windows 版本：Android 保持原版 Mihon 架构，Windows 基于 `app-desktop` 发布化；实际构建发布在 macOS/Desktop 主线稳定后处理。

## 1. 当前已验证事实

| 领域 | 当前事实 | 风险 |
| --- | --- | --- |
| UI 注入 | Desktop UI 中 `Injekt.get<...>` 已清零，由根级 `DesktopUiDependencies` 和 ScreenModel/factory 承接 | 已偿还 |
| 大文件 | `MangaDetailScreen.kt` 已降至 907 行，`LibraryTab.kt` 已降至 452 行；组件文件纳入行数守卫 | 已偿还 |
| UI 直连数据层 | Desktop UI 中 Repository 方法直连已清零；少量 Repository 依赖仍由根级依赖容器转接，后续随 ScreenModel 拆分继续下沉 | 已偿还 |
| 生命周期 | `DesktopAppRuntime` 统一启动/关闭后台服务，`Main.kt` 不再使用启动期 `runBlocking` | 已偿还 |
| 调度 | 后台调度进入 runtime 生命周期，可取消并有测试覆盖 | 已偿还 |
| 扩展 | `DesktopExtensionLoader` 已记录失败分类诊断，兼容性测试覆盖损坏扩展 | 已偿还 |
| 备份 | Desktop protobuf+gzip 已补齐关键字段和 excluded scanlators 往返；Android 往返样本移入 Phase X | 已偿还 |
| History | Desktop 已有搜索、删除、清空、空状态 | 已降级 |
| Migration | Desktop 已有 UI 流程，但仍需架构化 | P1 |

## 2. 技术债台账与关闭标准

本 roadmap 把技术债偿还作为发布阻塞项，而不是发布后的优化项。任何发布工程任务如果依赖尚未关闭的 P0/P1 技术债，必须先偿债或明确登记豁免理由、影响范围和到期时间。

### 2.1 技术债状态

| 状态 | 含义 |
| --- | --- |
| `OPEN` | 已确认存在，尚未处理 |
| `CONTAINED` | 已用测试、adapter、允许名单或文档限制影响范围 |
| `PAYING` | 正在偿还 |
| `PAID` | 已偿还，测试和验证证据齐全 |
| `ACCEPTED` | 明确接受的债务，必须有到期复查日期 |

### 2.2 技术债关闭标准

技术债不能只靠“代码看起来更好”关闭，必须满足：

- 有失败测试或架构守护能暴露原问题。
- 有最小实现修复或隔离原问题。
- 有重构后验证命令和结果。
- 有用户可见行为说明，哪怕行为不变也要说明“入口和反馈不变”。
- 有防回归机制，例如架构测试、DI wiring 测试、集成测试、文档规则或允许名单收敛。

### 2.3 技术债台账

| ID | 状态 | 优先级 | 平台 | 债务 | 影响 | 关闭标准 |
| --- | --- | --- | --- | --- | --- | --- |
| TD-01 | PAID | P0 | Desktop | Composable 内大量 `Injekt.get()` | UI 难测试，依赖缺失运行时才暴露 | UI 包 `Injekt.get<...>` 计数为 0，`DesktopArchitectureGuardTest` 防回归 |
| TD-02 | PAID | P0 | Desktop | UI 直接调用 Repository | 业务规则绕过 domain，Android 回流风险高 | UI 包 Repository 方法直连计数为 0，核心页面已迁入 ScreenModel/factory |
| TD-03 | PAID | P1 | Desktop | `MangaDetailScreen`、`LibraryTab` 等上千行 UI | 修改冲突高，状态和业务混杂 | Screen 与组件拆分，行数守卫覆盖主文件和组件文件 |
| TD-04 | PAID | P0 | Desktop | `Main.kt` 启动和后台服务无统一生命周期 | 退出、headless、后台任务不可控 | `DesktopAppRuntime` 管理启动、scope、shutdown |
| TD-05 | PAID | P0 | Desktop | 固定轮询调度和异常吞噬 | 后台失败不可诊断，资源浪费 | 后台服务由 runtime 可控启动/关闭，相关调度测试通过 |
| TD-06 | PAID | P0 | Desktop | 扩展加载 fallback 和 ClassLoader 复杂且弱诊断 | Windows 发布兼容风险高 | 失败分类、日志、扩展兼容性测试覆盖 |
| TD-07 | PAID | P1 | All | 备份字段兼容不完整 | Android/Windows 往返可能丢数据 | Desktop 往返测试覆盖关键字段；Android 发布往返移入 Phase X |
| TD-08 | PAID | P1 | Desktop | 硬编码路径和 Swing/AWT 散落 | Windows 发布体验和可维护性差 | 平台 adapter 收口，设置页可打开目录；`/tmp`/`/Applications` 硬编码基线为 0 |
| TD-09 | PAID | P1 | Desktop | Crash/Debug 日志写临时目录且无轮转 | 用户无法稳定反馈问题 | Crash/Reader Debug 日志已迁入平台目录，Crash 日志已轮转 |
| TD-10 | CONTAINED | P1 | Android | Desktop 独有功能缺少 Android 合并边界 | 容易误把 Desktop 技术债带入 Android | 四类资产清单和禁止项守护 |

## 3. 任务拆分与追踪规则

### 3.1 状态枚举

| 状态 | 含义 |
| --- | --- |
| `TODO` | 尚未开始 |
| `NEXT` | 下一批准备执行，依赖已明确 |
| `DOING` | 正在实现，一次只允许少量任务处于此状态 |
| `BLOCKED` | 被外部依赖、设计决策或失败验证阻塞 |
| `REVIEW` | 实现完成，等待复核、验证或合并 |
| `DONE` | 已完成，验收与验证记录齐全 |
| `DEFERRED` | 已明确延后，不阻塞当前 macOS/Desktop 主线 |

### 3.2 优先级

| 优先级 | 含义 |
| --- | --- |
| `P0` | 阻碍发布或长期维护，必须优先处理 |
| `P1` | 重大技术债，影响扩展能力或数据安全 |
| `P2` | 应在稳定后优化 |
| `P3` | 可持续记录的小问题 |

### 3.3 单个任务必须包含的信息

每个任务拆分时必须补齐以下字段：

| 字段 | 要求 |
| --- | --- |
| `ID` | 格式为 `A-01`、`W-03`、`D-02` 等，阶段前缀固定 |
| `状态` | 使用 3.1 中的状态 |
| `优先级` | 使用 3.2 中的优先级 |
| `平台` | `Android`、`Desktop`、`Windows`、`Shared`、`All` |
| `用户可见变化` | 必须说明用户入口、反馈、空状态、错误状态或发布行为 |
| `技术范围` | 涉及的模块、主要文件、禁止越界内容 |
| `TDD 红` | 先写的失败测试，必须说明失败原因 |
| `TDD 绿` | 最小实现与通过测试 |
| `重构验证` | 重构后再次运行的测试 |
| `验收命令` | 具体命令或人工验收步骤 |
| `证据` | 测试输出、截图、构建产物、文档链接或 PR |

### 3.4 进度更新模板

后续每次更新本 roadmap 时，在对应任务表中修改状态，并在任务下方补一条进度记录：

```markdown
#### 进度记录

- 2026-06-30：`TODO -> DOING`，开始编写失败测试：`./gradlew :app-desktop:jvmTest --tests "..."`
- 2026-06-30：`DOING -> REVIEW`，实现完成；验证：`./gradlew :app-desktop:jvmTest`
- 2026-06-30：`REVIEW -> DONE`，验收通过；证据：PR/commit/test log
```

### 3.6 当前执行记录

| 日期 | 任务 | 状态变化 | TDD 红 | TDD 绿/重构 | 验证证据 |
| --- | --- | --- | --- | --- | --- |
| 2026-06-30 | W3-05 备份文件名唯一化和并发锁 | `TODO -> DONE` | 连续写入和并发写入测试先暴露同名覆盖风险 | `writeBackupFile` 加毫秒时间戳、同步写入和同名后缀 | `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.backup.*"` |
| 2026-06-30 | W3-06 Crash 日志平台目录、轮转和设置页入口 | `TODO -> DONE` | `CrashHandlerTest`、`DebugLoggerTest`、`TestArgumentsTest`、`DesktopDirectoryOpenerTest` 先暴露 `/tmp` 路径、无轮转、无目录打开器 | Crash/Reader Debug 日志和测试截图迁入平台目录，Crash 日志超过阈值轮转；Advanced 设置页增加打开日志目录入口 | `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.CrashHandlerTest" --tests "mihon.desktop.reader.DebugLoggerTest" --tests "mihon.desktop.test.TestArgumentsTest" --tests "mihon.desktop.ui.settings.DesktopDirectoryOpenerTest"` |
| 2026-07-01 | W2-03 Windows 路径 adapter | `TODO -> DONE` | `DesktopPlatformPathsTest` 先暴露缺少 Windows `%APPDATA%`/`%LOCALAPPDATA%` 路径分发 | 新增 `DesktopPlatformPaths`，DI、网络缓存、cookie、下载、扩展、封面、日志、备份、测试截图接入统一路径；mac/Linux 数据目录保持兼容 | `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.platform.DesktopPlatformPathsTest" --tests "mihon.desktop.di.DILayerSplitContractTest"` |
| 2026-07-01 | W3-04 备份字段兼容 | `TODO -> DOING` | `DesktopBackupCreatorTest`/`DesktopBackupRestorerTest` 先暴露 `updateStrategy`、`favoriteModifiedAt`、`version` 缺失 | 已补 Desktop backup protobuf 字段号 105/107/109，并在导出和新 manga 恢复中保留；`excludedScanlators` 和 Android 样本往返仍待完成 | `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.backup.*"` |
| 2026-07-01 | W1-04a HistoryTab 分层 | `TODO -> DONE` | `HistoryScreenModelTest` 先覆盖历史搜索、删除、清空和 reader request 组装 | 新增 `HistoryScreenModel`/factory，`HistoryTab` 不再直接获取历史/章节/漫画 UseCase，也不再在 UI 层注入 reader tracker | `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.history.HistoryScreenModelTest" --tests "mihon.desktop.architecture.DesktopArchitectureGuardTest" --tests "mihon.desktop.ui.PhaseDNavigationContractTest" --tests "mihon.desktop.ui.ScreenInstantiationSmokeTest.HistoryTab is Tab"` |
| 2026-07-01 | W1-04b UpdatesTab 分层 | `TODO -> DONE` | `UpdatesScreenModelTest` 先覆盖过滤偏好、下载过滤、批量已读、reader request 和下载入队 | 新增 `UpdatesScreenModel`/factory，`UpdatesTab` 不再直接获取 Updates/Chapter/Manga/Download 依赖；下载过滤保留 raw items，切换过滤不丢列表 | `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.updates.UpdatesScreenModelTest" --tests "mihon.desktop.ui.updates.UpdatesFilterTest" --tests "mihon.desktop.architecture.DesktopArchitectureGuardTest" --tests "mihon.desktop.ui.ScreenInstantiationSmokeTest.UpdatesTab is Tab"` |
| 2026-07-01 | W1-01 LibraryTab 分层 | `TODO -> DONE` | `LibraryScreenModelTest` 先覆盖分类、筛选、排序、批量选择、错误状态和分类操作 | `LibraryTab` 通过 `LibraryScreenModelFactory` 获取业务能力，分类管理对话框改为回调驱动 | `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.library.LibraryScreenModelTest" --tests "mihon.desktop.architecture.DesktopArchitectureGuardTest"` |
| 2026-07-01 | W1-02 MangaDetailScreen 分层 | `TODO -> DONE` | `MangaDetailScreenModelTest` 先覆盖详情流、章节操作、下载、收藏、分类、迁移和 reader request | `MangaDetailScreen` 通过 `MangaDetailScreenModelFactory` 承接详情页业务动作，UI 不再直连主要 repository/use case | `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.library.MangaDetailScreenModelTest" --tests "mihon.desktop.ui.ScreenInstantiationSmokeTest.MangaDetailScreen is Screen not Tab"` |
| 2026-07-01 | W1-03 Reader 分层 | `TODO -> DONE` | `ReaderScreenModelTest` 先覆盖阅读进度、viewer flags 持久化和章节导航 | 新增 `DesktopReaderRuntimeFactory`/`DesktopReaderPageLoader`，Reader UI 不再直接获取 repository 和阅读运行时依赖 | `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.ui.reader.ReaderScreenModelTest" --tests "mihon.desktop.ui.reader.ReaderChapterNavigationInitialPageTest"` |
| 2026-07-01 | W1-05a UI 依赖债务清零 | `TODO -> DONE` | `DesktopArchitectureGuardTest` 先以基线 0 暴露 UI 中剩余 59 个 `Injekt.get`/Repository 方法直连 | 新增根级 `DesktopUiDependencies`，`Main.kt` 统一提供 UI 依赖；UI 包直接 DI/Repository 方法调用计数降为 0 | `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.architecture.DesktopArchitectureGuardTest"` |
| 2026-07-01 | W2-01/W2-02 runtime 生命周期 | `TODO -> DONE` | `DesktopAppRuntimeTest` 先覆盖后台服务启动/关闭顺序和重复关闭 | 新增 `DesktopAppRuntime`，统一管理 LibraryUpdate、LocalSourceScan、AutoBackup 和 reader 清理；`Main.kt` 移除启动期 `runBlocking` | `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.DesktopAppRuntimeTest" --tests "mihon.desktop.domain.LibraryUpdateSchedulerTest" --tests "mihon.desktop.backup.AutoBackupSchedulerTest"` |
| 2026-07-01 | W3-01/W3-02 扩展诊断 | `TODO -> DONE` | `ExtensionCompatibilityTest` 先用损坏 jar 暴露无诊断记录问题 | `DesktopExtensionLoader` 记录 `ExtensionLoadDiagnostic` 和失败分类，加载前清理旧诊断 | `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.extension.ExtensionCompatibilityTest" --tests "mihon.desktop.extension.JarScanSourceDiscoveryTest" --tests "mihon.desktop.extension.DesktopExtensionLoaderTest"` |
| 2026-07-01 | W3-04 备份字段兼容 | `DOING -> DONE` | `DesktopBackupCreatorTest`/`DesktopBackupRestorerTest` 先暴露 `excludedScanlators` 缺失 | Backup protobuf 新增 excluded scanlators 字段，导出/恢复接入 `GetExcludedScanlators`/`SetExcludedScanlators`，自动备份同步保留字段 | `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.backup.*" --tests "mihon.desktop.di.DILayerSplitContractTest"` |
| 2026-07-01 | W1-05 大文件职责拆分 | `PAYING -> DONE` | `DesktopArchitectureGuardTest` 行数基线先暴露 `MangaDetailScreen.kt`/`LibraryTab.kt` 仍过大 | 纯 UI 组件拆到 `MangaDetailComponents.kt`/`LibraryComponents.kt`，原 Screen 文件分别降至 907/452 行；新组件文件纳入行数守卫 | `./gradlew :app-desktop:jvmTest --tests "mihon.desktop.architecture.DesktopArchitectureGuardTest" --tests "mihon.desktop.ui.library.LibraryScreenModelTest" --tests "mihon.desktop.ui.library.MangaDetailScreenModelTest"` |

### 3.5 Android 合并守则

从 `app-desktop` 向 Android 合并功能时，必须先拆成四类资产：

| 资产类型 | Android 合并方式 | 禁止做法 |
| --- | --- | --- |
| 用户功能规格 | 写成入口、状态、反馈、边界 | 直接复制 Desktop Composable |
| 共享业务逻辑 | 提取到 domain/data/source/core 或复用原版 UseCase | Android UI 直连 Repository |
| Android 平台实现 | 使用原版 Mihon ScreenModel、DI、WorkManager、Notification、WebView、权限模型 | 引入 Desktop runtime、Swing/AWT、JVM 路径策略 |
| 测试资产 | 转成 Android 单元测试、DI wiring、导航/集成测试 | 只保留 Desktop JVM 测试 |

明确禁止合并到 Android：

- `app-desktop/src/main/kotlin/mihon/desktop/ui/**`
- `DesktopAppModule.kt`、Desktop runtime、Desktop scheduler
- `DesktopExtensionLoader`、`ApkToJarConverter`、child-first ClassLoader
- `app-desktop/src/main/kotlin/android/**`、`androidx/**` stubs
- Swing/AWT 文件选择、剪贴板、`java.awt.Desktop`
- macOS/Windows 打包脚本和桌面路径策略

## 4. 发布准入标准

### 4.1 共同标准

- Library、Browse、Manga Detail、Reader、Downloads、History、Migration、Backup/Restore、Settings 有可用入口。
- P0 技术债必须 `PAID`；P1 技术债必须 `PAID` 或有明确 `ACCEPTED`/`DEFERRED` 记录、复查日期和发布影响说明。
- 每个入口有加载中、空状态、错误状态和权限/数据缺失处理。
- Android -> Windows -> Android 备份往返不丢关键字段。
- 数据库 migration 在 Android 和 Windows 两端可重复执行。
- Crash 日志可发现、可导出、可限额轮转。
- 自动更新、自动备份、自动库更新均可关闭。
- 扩展加载失败有用户可理解提示和开发者可诊断日志。

### 4.2 Android 发布标准

- 使用原版 Mihon `app/` 架构发布 Android，不以 `app-desktop` 为基线。
- 从 `app-desktop` 合并来的功能必须按原版 Mihon ScreenModel、UseCase、DI、WorkManager、Notification、WebView 模式实现。
- 不允许 Desktop UI、Desktop DI、Desktop ClassLoader、Desktop Android stubs 进入 Android。
- Android debug/release 构建通过。
- Android 核心流程回归通过：库、浏览、阅读、下载、备份恢复、扩展安装。

### 4.3 Windows 发布标准

- Windows 基于当前 `app-desktop` 发布化。
- 支持 Windows 用户数据目录，不硬编码 macOS/Linux 路径。
- 支持安装、升级、卸载；卸载默认不删除用户数据。
- EXE/MSI/ZIP 至少一种产物可重复构建，版本号可追溯。
- 代码签名计划明确；未签名阶段必须在发布说明中说明风险。
- 设置页能打开崩溃日志、备份、下载、扩展目录。
- 后台任务在窗口关闭/应用退出时可控停止。

## 5. 阶段总览

| 阶段 | 目标 | 状态 | 发布阻塞 |
| --- | --- | --- | --- |
| Phase A | 冻结产品基线、平台边界、发布标准 | DONE | 是 |
| Phase 0 | 建立测试基线和架构守护 | DONE | 是 |
| Phase D | 技术债偿还和防回归机制 | DONE | 是 |
| Phase W1 | Desktop UI/UseCase 分层 | DONE | 否 |
| Phase W2 | Desktop 生命周期和平台 adapter | DONE | 否 |
| Phase W3 | Desktop 扩展系统、备份、Crash、通知收敛 | DONE | 否，通知平台 adapter 降级为后续体验优化 |
| Phase R | 功能 parity 与体验补齐 | TODO | 否 |
| Phase X | Windows/Android 构建发布与 Android 合并 | DEFERRED | 否 |

## 6. Phase A：产品基线与架构决策冻结

目标：冻结“Android 原版 Mihon、macOS/Windows app-desktop”的路线，避免后续任务反复变更方向。

| ID | 状态 | 优先级 | 平台 | 任务 | 用户可见变化 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| A-01 | DONE | P0 | All | 写入产品基线 ADR：Android 基于原版 Mihon，Windows/macOS 基于 `app-desktop` | 无直接变化，后续发布路线明确 | `docs/architecture/adr/0001-product-baseline-and-platform-boundaries.md` |
| A-02 | DONE | P0 | All | 写入共享/禁止共享边界 | 无直接变化，避免错误复用 | ADR-0001 和 Android 合并模板 |
| A-03 | DONE | P0 | All | 定义平台接口最小集合：文件、通知、Crash、Web/Auth、更新器、浏览器、剪贴板、生命周期 | 无直接变化 | `docs/architecture/adr/0002-platform-interface-boundaries.md` |
| A-04 | DONE | P0 | All | 定义统一版本策略和发布矩阵 | 用户看到版本号一致且可追溯 | `docs/architecture/versioning-and-release-matrix.md` |
| A-05 | DONE | P0 | All | 定义 TDD 与验收证据格式 | 无直接变化 | 本文档任务表和 `docs/roadmap/verification-commands.md` |

## 7. Phase 0：基线测试与架构守护

目标：重构前先建立回归保护，不改变用户行为。

| ID | 状态 | 优先级 | 平台 | 任务 | 用户可见变化 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| B-01 | DONE | P0 | Desktop | 建立 Desktop UI 禁止直连 Repository 的架构守护测试，先用允许名单过渡 | 无直接变化 | `DesktopArchitectureGuardTest` |
| B-02 | DONE | P0 | Android | 建立 Android 合并守护清单模板 | 无直接变化 | `docs/roadmap/android-desktop-feature-merge-template.md` |
| B-03 | DONE | P0 | All | 固定验证命令清单：格式、单元、集成、smoke、发布构建 | 无直接变化 | `docs/roadmap/verification-commands.md` |
| B-04 | DONE | P0 | Desktop | 为 Library、Manga Detail、Reader、Migration、Backup、Extension 建立 smoke 基线 | 用户路径后续不会被重构破坏 | `docs/roadmap/smoke-and-regression-baseline.md` |
| B-05 | DONE | P0 | Android | 为待合并功能建立 Android 回归基线 | Android 原有功能不被破坏 | `docs/roadmap/smoke-and-regression-baseline.md` |

## 8. Phase D：技术债偿还和防回归机制

目标：先把 P0/P1 技术债转成可测试、可关闭、可防回归的任务，再继续扩大功能面或发布面。

| ID | 状态 | 优先级 | 平台 | 任务 | 用户可见变化 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| D-01 | DONE | P0 | Desktop | 为 `TD-01`/`TD-02` 建立架构守护：Desktop UI 禁止新增 `Injekt.get()` 和 Repository 直连 | 无直接变化，后续 UI 改动更稳定 | `DesktopArchitectureGuardTest` |
| D-02 | DONE | P0 | Desktop | 为 `TD-03` 建立大文件拆分门槛和职责边界 | 无直接变化，降低维护风险 | `DesktopArchitectureGuardTest` 行数基线 |
| D-03 | DONE | P0 | Desktop | 为 `TD-04`/`TD-05` 建立后台任务生命周期测试 | 自动更新/备份/扫描退出时更可靠 | `runBlocking` 债务基线守护 |
| D-04 | DONE | P0 | Desktop | 为 `TD-06` 建立扩展加载失败分类和诊断日志基线 | 扩展失败时用户能看到原因 | `docs/roadmap/extension-diagnostics-baseline.md` |
| D-05 | DONE | P0 | All | 为 `TD-07` 建立备份字段往返失败测试 | 备份恢复不丢关键字段 | `DesktopBackupCreatorTest` viewer 字段红绿测试 |
| D-06 | DONE | P1 | Desktop | 为 `TD-08`/`TD-09` 建立平台路径和日志目录 adapter 基线 | 设置页能找到日志/备份/下载目录 | `DesktopArchitectureGuardTest` 路径基线 |
| D-07 | DONE | P1 | Android | 为 `TD-10` 建立 Android 合并守护模板和审查清单 | Android 不引入 Desktop 技术债 | Android 合并模板和 Android 禁止导入守护 |

## 9. Phase W1：Desktop UI 与业务分层

目标：在 `app-desktop` 内先解决长期维护问题，重点移除 UI 直连 DI/Repository。

| ID | 状态 | 优先级 | 平台 | 任务 | 用户可见变化 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| W1-01 | DONE | P0 | Desktop | `LibraryTab` 迁移到完整 ScreenModel + UseCase | Library 入口不变，错误反馈更明确 | `LibraryScreenModelTest` 和架构守护通过 |
| W1-02 | DONE | P0 | Desktop | `MangaDetailScreen` 迁移到完整 ScreenModel + UseCase | 详情页入口不变，批量操作、过滤、下载反馈稳定 | `MangaDetailScreenModelTest` 通过 |
| W1-03 | DONE | P0 | Desktop | `DesktopReaderScreen` 迁移阅读状态和持久化动作 | Reader 行为不变，退出和进度保存更可靠 | Reader 状态测试和 smoke 通过 |
| W1-04a | DONE | P1 | Desktop | `HistoryTab` 移除直接 `Injekt.get()` 和业务散落 | 历史入口、搜索、删除、清空、继续阅读行为不变 | `HistoryScreenModelTest` + 架构守护 |
| W1-04b | DONE | P1 | Desktop | `UpdatesTab` 移除直接 `Injekt.get()` 和业务散落 | 更新入口、过滤、批量已读、下载、继续阅读行为不变；下载过滤切换不丢原始列表 | `UpdatesScreenModelTest` + 架构守护 |
| W1-05 | DONE | P1 | Desktop | 拆分 1000 行级 UI 文件为 Screen、State、Actions、Dialogs、Rows | 无功能变化，降低维护成本；UI DI/Repository 直连子项已清零 | 行数守卫和相关 ScreenModel 测试通过 |

## 10. Phase W2：Desktop 平台 adapter 与生命周期

目标：先让 macOS 主线的 `app-desktop` 启动、路径、日志、后台任务具备可维护边界；Windows 实际打包发布任务移入 Phase X。

| ID | 状态 | 优先级 | 平台 | 任务 | 用户可见变化 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| W2-01 | DONE | P0 | Desktop | 新增 Desktop runtime/lifecycle，统一启动和 shutdown | 退出应用后后台任务停止 | `DesktopAppRuntimeTest` 覆盖启动、headless、退出 |
| W2-02 | DONE | P0 | Desktop | 移除启动期阻塞 `runBlocking`，后台任务受 scope 管理 | 启动更稳定，错误可见 | `runBlocking` 架构守卫基线为 0 |
| W2-03 | DONE | P0 | Desktop | 新增平台路径 adapter：配置、数据库、缓存、下载、扩展、日志、备份 | About/Advanced 显示和操作平台路径；mac/Linux 数据目录保持兼容，Windows 路径规则已具备单元测试 | 平台路径单元测试 |

## 11. Phase W3：Desktop 扩展、备份、Crash、通知收敛

目标：先处理 Desktop 数据安全、扩展诊断和用户反馈风险，避免在单平台功能迭代中继续扩大技术债。

| ID | 状态 | 优先级 | 平台 | 任务 | 用户可见变化 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| W3-01 | DONE | P0 | Desktop | 扩展加载失败记录诊断日志，不再静默吞异常 | 扩展失败时用户看到明确提示 | 失败扩展集成测试通过 |
| W3-02 | DONE | P0 | Desktop | 建立真实扩展兼容性基线：成功、失败、部分可用 | 扩展列表更可靠 | 扩展兼容性测试通过 |
| W3-03 | TODO | P1 | Desktop | 明确 `MangaDexSource` 去向：移除、测试源或 bundled extension | 源入口边界清楚 | 策略文档和测试 |
| W3-04 | DONE | P0 | Desktop | 补齐备份字段兼容：viewer、updateStrategy、favoriteModifiedAt、version、excludedScanlators、tracking | Desktop 备份往返不丢数据；Android 往返样本验证移入 Phase X | Desktop 备份测试通过 |
| W3-05 | DONE | P1 | Desktop | 备份文件名唯一化和并发锁 | 连续备份不覆盖 | 并发测试通过 |
| W3-06 | DONE | P1 | Desktop | Crash 日志改为平台路径、轮转、设置页打开目录 | 用户能在 Advanced 设置页打开 Crash 日志目录 | UI 入口测试和日志测试通过 |
| W3-07 | TODO | P1 | Desktop | 通知服务升级为平台 adapter | 后台更新/备份反馈更可靠 | 通知测试和手工验收 |

## 12. Phase R：功能 parity 与体验补齐

目标：在 macOS/Desktop 主线稳定后补齐长期体验，不阻塞当前核心重构。

| ID | 状态 | 优先级 | 平台 | 任务 | 用户可见变化 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| R-01 | TODO | P1 | Desktop | Library update restrictions 完整 parity | 自动更新更可控 | 规则测试一致 |
| R-02 | TODO | P2 | All | Tracking 可行性设计：MAL/AniList/Kitsu/Bangumi | 多端进度同步规划明确 | 设计文档 |
| R-03 | DEFERRED | P2 | Windows | WebView/Cloudflare 从手动 cookie 走向可维护方案 | 登录受保护源体验改善 | 技术选型和 PoC |
| R-04 | TODO | P2 | Desktop | 本地源 mtime 缓存和增量扫描 | 本地源重启后扫描更快 | 性能测试 |
| R-05 | TODO | P2 | Desktop | Rhino JS 引擎替换评估 | 扩展 JS 性能和兼容性更好 | 性能数据和兼容性报告 |

## 13. Phase X：延期队列，Windows / Android 构建发布与 Android 合并

目标：这些任务不阻塞当前 macOS/Desktop 单平台功能迭代；等 W1/W2/W3 核心债务继续收敛后，再作为独立发布计划处理。

### 13.1 Windows 构建与发布

| ID | 状态 | 优先级 | 平台 | 任务 | 用户可见变化 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| XW-01 | DONE | P0 | Windows | 新增 Windows 构建脚本：MSI 至少一种可重复构建产物 | 用户可在 Windows 构建机生成可安装 MSI | `scripts/build-windows.ps1` 已通过，生成 `app-desktop\tmp\mihon-dist\main\msi\Mihon Desktop-1.11.12.msi`；干净 Windows 安装 smoke 进入 XW-02 验收 |
| XW-02 | DEFERRED | P1 | Windows | 安装、升级、卸载规则和用户数据保留策略 | 升级不丢数据，卸载说明明确 | 安装升级验收记录 |
| XW-03 | DONE | P1 | Windows | 统一版本号：Windows 安装包版本从 `AppVersion` 派生并可追溯 | 关于页和安装包版本使用同一 stage/feature 来源 | `WindowsReleaseConfigurationTest` 通过；应用版本 `0.11.12.1dd3e83`，原生包版本 `1.11.12` |
| XW-04 | DEFERRED | P2 | Windows | Windows WebView/Cloudflare 可维护方案 | 登录受保护源体验改善 | 技术选型和 PoC |

#### 进度记录

- 2026-07-08：`DEFERRED -> DOING`，开始 Windows 可构建包闭环；先更新 roadmap 并编写发布配置守卫测试：`WindowsReleaseConfigurationTest`。
- 2026-07-08：实现 `scripts/build-windows.ps1`，脚本在 Windows 上先运行 `:app-desktop:jvmTest`，再运行 `:app-desktop:packageMsi`；`app-desktop` 原生包版本改为从 `AppVersion.STAGE/FEATURE` 派生。
- 2026-07-09：修正 native distribution 版本规则：应用展示版本仍为 `0.STAGE.FEATURE.GIT_HASH`，Windows/macOS 原生包版本使用 `1.STAGE.FEATURE`，满足 MSI/DMG 对 MAJOR > 0 的要求。
- 2026-07-09：`DOING -> DONE`，Windows 构建脚本完整通过；验证：`powershell -ExecutionPolicy Bypass -File scripts/build-windows.ps1`；产物：`app-desktop\tmp\mihon-dist\main\msi\Mihon Desktop-1.11.12.msi`。

### 13.2 Android 功能资产剥离清单

目标：从 `app-desktop` 中识别可回流 Android 的功能资产，但不复制 Desktop 实现。

| ID | 状态 | 优先级 | 平台 | 任务 | 用户可见变化 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| XA-01 | DEFERRED | P0 | Android | 建立 Desktop 独有功能清单：保留 Desktop、共享、Android 原生实现、丢弃 | 无直接变化 | 清单覆盖 app-desktop 独有功能 |
| XA-02 | DEFERRED | P0 | Android | 为备份兼容功能编写 Android 合并规格和失败测试 | 备份恢复更可靠 | Android 失败测试确认正确原因 |
| XA-03 | DEFERRED | P1 | Android | 为 library update 限制和分类过滤写共享规则规格 | 自动更新规则与 Desktop 一致 | domain 测试覆盖规则 |
| XA-04 | DEFERRED | P1 | Android | 为 reader 设置/阅读模式规则写共享规格 | 阅读设置行为一致 | 规则测试覆盖 |
| XA-05 | DEFERRED | P2 | Android | 为本地源增量扫描、章节清洗、迁移核心算法建立迁移候选 | 后续功能增强有依据 | 候选清单评审通过 |

### 13.3 Android 按原版 Mihon 架构合并功能

目标：把 XA 功能资产清单中选定的内容按原版 Mihon 架构实现到 Android。

| ID | 状态 | 优先级 | 平台 | 任务 | 用户可见变化 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| XA-06 | DEFERRED | P0 | Android | 合并备份字段兼容修复 | Android 与 Desktop 备份往返不丢字段 | Android 单元/集成测试通过 |
| XA-07 | DEFERRED | P1 | Android | 合并 library update 共享规则，Android 用 WorkManager 调度 | 自动更新过滤更完整 | WorkManager/UseCase 测试 |
| XA-08 | DEFERRED | P1 | Android | 合并 reader 设置纯规则，Android UI 保持原版交互 | 阅读设置重启后行为稳定 | Reader 设置测试 |
| XA-09 | DEFERRED | P1 | Android | 合并 Crash/日志导出入口，使用 Android 平台目录 | 用户可导出日志 | 设置页验收 |
| XA-10 | DEFERRED | P0 | Android | Android 发布回归：构建、核心流程、扩展、备份 | Android 版本可独立发布 | debug/release 构建和核心测试通过 |

## 14. 每轮开发完成报告格式

每轮开发完成后，必须按以下格式更新任务状态，并在最终回复中同步说明：

```markdown
## 【功能特性】
- [功能名称]：用户能看到/使用的变化，说明操作路径和边界。

## 【BUG 修复】
- [bug 描述]：修复前现象 -> 修复后行为。

## 【验收清单】
- [ ] 操作路径 -> 预期结果。

## 【验证】
- 格式检查：
- 单元测试：
- 集成测试：
- 构建/发布：
- 未运行项及原因：
```

## 15. 默认验证命令

按任务范围选择最小充分验证，不得在未说明验证状态时宣称完成。

| 范围 | 命令 |
| --- | --- |
| 格式检查 | `./gradlew spotlessCheck` |
| Android debug | 延期队列执行时运行：`./gradlew assembleDebug` |
| Android release | 延期队列执行时运行：`./gradlew assembleRelease -Pinclude-telemetry -Penable-updater` |
| Android 单元测试 | 延期队列执行时运行：`./gradlew testReleaseUnitTest` |
| Desktop 单元测试 | `./gradlew :app-desktop:jvmTest` |
| Desktop E2E/Robot | `./gradlew :test-desktop:test` |
| Desktop 冒烟 | `./scripts/desktop-smoke-test.sh` |
| macOS Desktop 构建部署 | `./scripts/build-desktop.sh` |
| Windows 测试验证 | 在 Windows 构建机运行 `powershell -ExecutionPolicy Bypass -File scripts/build-windows.ps1 -TestOnly` |
| Windows 完整集成验证 | 需要真实网络扩展样本时运行 `powershell -ExecutionPolicy Bypass -File scripts/build-windows.ps1 -TestOnly -FullTests` |
| Windows 包 | 发布产包时运行 `powershell -ExecutionPolicy Bypass -File scripts/build-windows.ps1` |

## 16. 当前状态摘要

| 项目 | 状态 |
| --- | --- |
| 产品路线 | 已确认：当前以 macOS `app-desktop` 为主线；Android 原版 Mihon 和 Windows 发布化进入延期队列 |
| 正式 roadmap | 已建立 |
| 进度追踪表 | 已建立，W1-01/W1-02/W1-03/W1-04a/W1-04b/W2-01/W2-02/W2-03/W3-01/W3-02/W3-04/W3-05/W3-06、XW-01、XW-03 已关闭并记录验证证据 |
| 技术债台账 | TD-01 至 TD-09 已偿还到 `PAID`；TD-10 Android 合并边界已由 Phase X 延期队列继续约束 |
| 下一步建议 | Windows 已可构建 MSI；下一步按 XW-02 处理安装、升级、卸载和用户数据保留验收，Android 构建发布继续按 Phase X 独立处理 |

## 17. 不建议事项

- 不建议先拆 Gradle 多模块；先收敛边界和测试。
- 不建议把 `app-desktop` 当成 Android 新基线。
- 不建议强制共享 Android 与 Desktop UI。
- 不建议在扩展系统稳定前承诺 Windows 完整扩展兼容。
- 不建议在 Phase W1/W2/W3 完成前优先做 Tracking。
