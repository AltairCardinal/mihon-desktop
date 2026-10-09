# 历史页上游对齐实施与验收证据

唯一实施计划：[history upstream parity roadmap](../roadmap/2026-10-04-history-upstream-parity-roadmap.md)。固定契约：[设计 P01–P18](../2026-10-04-history-upstream-parity-design.md)。本文只记录实际证据，未运行的测试与原生验收不视为通过。

## 当前状态

HP01 已提交，后续同步生命周期修复、焦点等待校准与 P16 名称修复也已提交；P16 修复源码为 `05fdb81a6ad077e5db6a2c30cf9282d33637290d`。最后一次完整 Desktop 复验基线为 `8ffddcf5389bf9ab65f66b8d01449c8dd926275b`，451 类 / 3322 项 / 0 失败 / 0 错误 / 3 跳过；data JVM / data Android / Android App 的批准复验无失败。未受影响结果保留原来源，P16 另有真实 Android/Desktop 六方法红绿重构及限定格式检查通过。这是组合证据，未重新执行最终源码全量。跳过项、UP-TO-DATE、历次失败与修复分别记录。

当前正式候选为 Desktop `0.11.19.75.05fdb81`、Android `0.19.4-aex.26` / versionCode 44，实际构建成功；Android 44 为本轮获批的唯一正式重建。旧候选 43 的下述证据保留其版本来源。Android 43 的名称、详情、复选框和分类补验通过；本轮另以实际原生事件完成重复取消、短点进入迁移选择并返回取消、长按打开已有作品、依然添加后两作品共存四分支。Windows/macOS 75 已分别完成受控延迟目录九观测及同 profile 冷启动缓存复验；明确单页模式下 initial/current 均为请求页，完整目录重启后不再次请求图源。旧 AUTO 双页外部断言失败仍保留，未改写成通过。

HP02 的剩余 Windows/macOS 原生项已于 2026-10-09 关闭：用户分别确认正式版本75的搜索中间编辑/清空关闭、封面详情返回、删除弹窗 Tab/Shift+Tab/Escape 取消和双页续读目标可见全部通过。来源为用户手动验收；Windows 自动输入的剪贴板错误及随后退出保留原记录，其原因没有由手动通过得到解释。Mac 统一保护实际完成息屏恢复与目标身份检查，截图权限不足的具体链路也保留，不把它推断为锁屏或产品失败。

Android 同步稀疏、延迟目录成败、冷缓存及原生前后章已在私有模拟器正式44完成；外部ABI正确红绿、首次助手边界失败和单独导航补验均见后文。本轮没有追加全量测试或正式构建。最终收口使用首次完整矩阵、已获批复验及后续受影响范围补验的组合证据，不能描述为最终源码重新全量通过。HP02 checkoff 与本报告、Mac维护经验和历史manifest局部维护随同最终验收批次提交；64-capability全局 `finalParityAudit` 不属于这次历史专项的收口验证，不用于批量推进其他capability或父roadmap。下文历史临时状态不替代本节最终结论。

## 执行基线与归属

- 授权：2026-10-04 用户要求实现该 roadmap；执行从 HP01 开始，当前已进入 HP02，最终完整复跑及新范围审批不由原实施授权自动涵盖。
- 本地：`10758b91ec`，`codex/history-reader-context-repair`，`D:/Codex/worktrees/f235/mihon`。
- 官方：`mihonapp/mihon@4c88f02646aa1a358611e5b3b37ef7a62909b8d9`。主代理通过本机代理只读获取该提交的 `HistoryViewModel.kt`、`HistoryTab.kt`、`GetNextChapters.kt`、`HistoryScreen.kt`、`HistoryItem.kt`、`HistoryDialogs.kt`，核对设计中的续读、删除、收藏、分类、迁移方向及列表界面。
- 原有未提交改动：`AppVersion.kt`、`MACOS_ACCEPTANCE.md`、前序历史修复证据报告，保持用户所有权，不混入 HP01 提交。
- 实施中新增并行修改：`presentation-sync/src/commonMain/kotlin/mihon/presentation/sync/SyncPanelContent.kt` 与 `presentation-sync/src/jvmTest/kotlin/mihon/presentation/sync/SyncPanelContentTest.kt`，实施者确认未写入这两文件，作为用户/其他任务改动保护，排除本批编辑、格式化和提交。HP02 前重新核对未提交产品输入，必要时对 HP01 已提交源码使用隔离 checkout 验收，不能混入候选。
- 03:48 左右只读复核：其他工作已提交 `467ae4a0aa`，随后 `bd8165ca4a` 撤销该同步 panel 提交，HEAD 因此前进，presentation-sync 已无未提交改动。本任务不修改或撤销这两个提交；上述三个起始 protected 文件 SHA-256 均与启动值完全相同。提交及候选以实际 HEAD 重新核对，不宣称工作期间仓库没有并行活动。
- 实施：一个子代理串行负责 A–E 及 focused 验证；主代理只读核对、独立审查和整合。父 roadmap 作者专项指针保持原样。
- 三个窄接口：共享 history 状态/事件、已选目标的 Reader 打开上下文、Reader 后台非删除目录补全。数据安全门已在同一轮独立审查内通过，再接入 D 消费者；整体批次与原生运行状态见本节及后文。

## 启动检查

本机 `D:/Android/Sdk` 下 `platforms/android-36/android.jar`、`build-tools/36.0.0/aapt2.exe` 和 `platform-tools/adb.exe` 均存在。启动核对时协调器没有 `STARTING` / `RUNNING` 任务；不终止其他 Java 或 SSH 进程。

已核对构建入口：Desktop `full-tests` 传入 `includeIntegrationTests=true`，Windows/macOS `build-only` 跳过完整 Desktop JVM 测试，但仍构建正式产物。以上仅为脚本核对，尚未执行 full 或正式构建。

本机 shell 只读核验：PATH 的 `bash.exe` 指向 WindowsApps；实际 `C:/Program Files/Git/bin/bash.exe` 存在且 `uname -s` 返回 `MINGW64_NT-10.0-28000`。HP02 Windows 入口使用该绝对路径，避免误走 WSL 平台；目前只执行 uname，没有运行全量或构建。

Desktop UI README 的“受影响模块完整验证”表述与根 AGENTS 分层验证约束有冲突；本次遵循根规则，HP01 运行 focused 和受影响测试集合，完整模块验证留至 HP02。

## 契约与实际验证

P01–P18 的必做范围保持固定，没有因平台、预算或旧证据改变验收。下文记录已实际执行的红/绿/重构及尚未关闭的失败；部分用例通过不代表整个契约或 HP01 已完成。最终逐项映射在批次集成验收完成后汇总。

### P01–P18 证据索引

下表是已执行 focused 证据的入口，包括 HP01 已完成的稳定批次，不代替 HP02 正式运行矩阵。共享 contract 由 JVM/Android wrapper 发现；具体红绿、夹具失败和撤回见下文。`History*` 为 `mihon.desktop.history` 包；Android 接线为 `eu.kanade.tachiyomi.ui.history` / `ui.reader` 包。

| 契约 | 实际测试入口及关键断言 | 当前边界 |
|---|---|---|
| P01 | `HistoryControllerContract` nullable/live/calendar；`HistoryGroupingTest`；`HistoryContentProductionTest` relative labels；双端 `HistoryDateContract` | focused 与稳定批次已绿；正式三端仍待 |
| P02 | `HistorySearchStateTest` typing、旧结果资格、same-query Test Mode；`HistoryContentProductionTest#real input keeps text selection and clearing separate from closing search`；双端 `HistorySearchInputContract` composition | 真实共享文本框编辑已绿；原生键盘/Android IME 留 HP02 |
| P03 | `HistoryActionsComposeIntegrationTest#cover pushes exact detail`；`HistoryAccessibilityIntegrationTest#native detail return retains query scroll and the originating cover focus` | 实际导航/返回查询、滚动、封面焦点已绿；原生证据留 HP02 |
| P04 | 双端 `HistoryNextChapterContract` 两项；`HistoryLocalEntryParityTest` 三分支；`HistoryNavigationPolicyIntegrationTest` 真实过滤 | 上游固定分支已绿，不按作品级同步位置改选目标 |
| P05 | `HistoryHomeReselectIntegrationTest` actual Home/reselect；Android `ReaderSyncEntryWiringTest` actual detached/global/repeated | 已绿；首次仅切标签、重选不受搜索限制 |
| P06 | `HistoryActionsComposeIntegrationTest` 确认/取消；`HistoryDeletePersistenceIntegrationTest` single/all 两项 | 文件库水位、较早记录回现及章节进度已绿；不删除作品/下载 |
| P07 | `HistorySearchStateTest#clear failure retains the database driven list`；`HistoryPreparationLifecycleTest#failed clear cannot reopen delivery while the reader is already mounted` | false 不伪清或成功、不重复 Reader；实际成功与取消分支已随稳定批次通过 |
| P08 | 双端 `HistoryFavoriteContract`；`HistoryFavoriteComposeIntegrationTest` default/uncategorized/chooser；`HistoryFavoritePersistenceIntegrationTest` rollback/outbox | 实际原子入库、取消、默认分类及重复确认已绿 |
| P09 | `HistoryFavoriteComposeIntegrationTest` duplicates / migration / category manager 四类实际流程 | 真实目标及生产 parser/迁移已绿；不把开空页面当接入 |
| P10 | `HistoryEnhancedTrackingHttpIntegrationTest` 九项；Android existing enhanced-binding wrapper；双端 `HistoryEnhancedProgressContract` | 隔离 HTTP 与真实 track 持久链已绿；不访问真实账号 |
| P11 | `HistoryLocalEntryParityTest` 禁用目录 port；`HistoryReaderComposeIntegrationTest` 实际 Reader；`HistoryCatalogHttpIntegrationTest` downloaded identity | 已绿；当前章页面错误由 Reader 承接，历史无目录预检 |
| P12 | `HistoryReaderOpenContextIntegrationTest` 四项；`HistoryReaderSortContextIntegrationTest` 历史/详情四策略；实际 catalog mounted 的前后章、键盘、HTTP | 实际邻接保方向已绿；旧 synced fixture 已按官方选定章复验，稳定批次已通过 |
| P13 | 双端文件库 `HistoryReaderOpenContextContract` 四项；Desktop actual opening 四项；Android `ReaderSyncResumeWiringTest` / `ReaderSyncEntryWiringTest` | 原子五字段身份、页/snapshot、fresh baseline、incognito 保持；受影响 Reader 批次已通过 |
| P14 | `HistoryReaderCatalogCompletionIntegrationTest` delayed/failures/closed 三项；双端 completion/flight contracts；Android preparer/actual Reader consumer | 先可读、30 秒一次、真实 refs、失败/取消和 late 资格已 focused 绿；正式产物留 HP02 |
| P15 | 双端 `NonDeletingChapterCatalogContract`；`SourceChapterCatalogIntegrationTest`；`HistoryCatalogHttpIntegrationTest`；Android preparer complete/identity | 原子回滚、稳定 ID/页/书签/下载及 COMPLETE 观测已绿；冷启动正式复验留 HP02 |
| P16 | `HistoryAccessibilityIntegrationTest` 双语言/主题、350px/200%；`HistoryContentAndroidInsetsTest` 真实 owner status inset | 离屏及 Android 页面测已绿；不宣称正式三端视觉/触摸已验 |
| P17 | shared delivery 两端；Desktop lifecycle/navigation actual events；Android actual entry；可达性实际 Tab/Shift+Tab/Enter/Space/Escape/详情返回 | 后台更新保手动/编辑焦点、消失触发器 fallback 已绿，原生输入留 HP02 |
| P18 | Android `HistorySharedControllerWiringTest` / actual HistoryTab；Desktop factory/actual navigation；`HistoryTestModeHttpTest` / timeline hydration / production Reader bridge | 共同 controller、原子 opening、mounted refs 已使用；旧 API 已返回 unsupported，稳定批次已通过 |

HP01 稳定集成通过，完整结果见下节；HP02 未执行的范围不从表中删除。

### A：首次红测

协调器 `hp01-a-red` 仅运行 Desktop `HistorySearchStateTest`，2 项失败，日志 `.gradle-coordinator/hp01-a-red.log`，26 秒。`typing updates text before a database query completes` 在真实 model 和可控仓库闸门上期望 `abc`，实际为空，确认输入被数据库等待阻塞。

另一项初始红测只断言清空失败后弹窗仍打开；主代理对照上游发现该断言不属于固定契约（上游确认即 dismiss），要求改为“仓库失败不伪造空列表、不发送完成事件”。初始弹窗断言不计作 P07 的有效修复证据。

初次命令路径 `./gradlew.bat` 在 Windows 协调器启动失败；改为 `.\gradlew.bat` 后才执行上述行为测试。启动失败不计作行为红测。

正确清空红测为 `hp01-a-clear-red`：2 项中 1 项失败，已有列表在仓库清空返回 false 时被旧 model 伪造为空；这是 P07 的有效红测。

A 跨模块首次绿测尝试先遇到共享 UI 编译错误（跨模块 nullable 属性的智能转换、不存在的字符串资源），不计绿测。修正后，真实 Compose 编辑测试在选择事件后立即调用旧语义闭包，插入位置不符；增加一帧 render 并断言实际 `TextSelectionRange`，保留 `aXbc` / `a中c` 原预期，随后该 UI 测试通过。这里是测试事件时序修正，没有放宽产品行为。

`hp01-a-green-input` 最终 BUILD SUCCESSFUL，7 分 40 秒：共享 UI 1 项、Desktop 搜索/清空 2 项、Desktop Reader 点击 1 项、Android 实际 `HistorySharedControllerWiringTest` 1 项通过。Android domain wrapper 在前次命令中被失败的 UI task 阻断，待 A 重构命令实际 XML 验证；不据此提前宣布 A 整组完成。

Android 验证等待期间观察到 executor PID 持续轮换；项目 `:app:testReleaseUnitTest` 设置 `forkEvery=1`。通配符测试筛选增加发现成本是推断，不是挂死证据；没有全局清理 Java/Gradle，也没有并发重开命令。后续 focused 使用完整测试类名。

`hp01-a-refactor` PASSED，61 秒，worker 38936 / process 42572，均退出。主代理读取实际测试 XML 核对：

| 实际测试类 | Target | 测试 / 失败 / 错误 / 跳过 |
| --- | --- | --- |
| `tachiyomi.domain.history.JvmHistoryControllerContractTest` | `:domain:jvmTest` | 2 / 0 / 0 / 0 |
| `tachiyomi.domain.history.AndroidHistoryControllerContractTest` | `:domain:testDebugUnitTest` | 2 / 0 / 0 / 0 |
| `mihon.presentation.history.HistoryContentProductionTest` | `:presentation-history:jvmTest` | 1 / 0 / 0 / 0 |
| `mihon.presentation.history.JvmHistorySearchInputContractTest` | `:presentation-history:jvmTest` | 2 / 0 / 0 / 0 |
| `mihon.presentation.history.AndroidHistorySearchInputContractTest` | `:presentation-history:testDebugUnitTest` | 2 / 0 / 0 / 0 |
| `mihon.desktop.history.HistorySearchStateTest` | `:app-desktop:jvmTest` | 2 / 0 / 0 / 0 |
| `mihon.desktop.history.HistoryReaderComposeIntegrationTest` | `:app-desktop:jvmTest` | 1 / 0 / 0 / 0 |
| `eu.kanade.tachiyomi.ui.history.HistorySharedControllerWiringTest` | `:app:testReleaseUnitTest` | 1 / 0 / 0 / 0 |

共 13 项通过，且所有命令使用明确 `--tests`；未运行完整模块。A 的临时续读、直接删除、收藏转详情 adapter 仍须在 B/C 收敛，不作为 P03/P06/P08 的完成证据。

### B：本地选章与实际动作红测

`hp01-b-red` 因新增 Android 测试编译错误失败，不计作行为红测。修正后 `hp01-b-red-behavior` 使用明确测试类及 `--continue` 在一次串行命令收集相关范围的真实失败，22 秒：

- `HistoryLocalEntryParityTest` 三项全部失败：旧历史会调用禁止参与导航的目录服务、已读章没有续紧邻下一章、已读末章仍被重开。
- `HistoryActionsComposeIntegrationTest` 两项全部失败：真实封面点击实际进入 `DesktopReaderScreen` 而非精确 `MangaDetailScreen`；删除没有确认弹窗。主代理读取 XML 核对了这两项失败原因。
- `HistoryContentProductionTest#shared history keeps official relative day labels` 失败，确认相对日期缺口；原真实输入编辑测试仍通过。
- Android `HistorySharedControllerWiringTest#Android history follows official target instead of another synced chapter` 失败，原即时输入接线测试仍通过。App 既有 retry 插件重试同一失败两次，日志显示 4 次执行 / 3 次失败；实际独立用例为 2 项，不将重试算作新增覆盖。

上述命令仅为红测，B 尚未取得绿和重构证据。

`hp01-b-green` 在测试编译阶段失败：新增 `getNextChapters` 参数误插入 `TestModeTimelineHydrationTest` 的 Updates 构造调用。原实施者仅修正该误插，不改变 Updates 产品行为；编译失败不计作绿测。

`hp01-b-reselect-red` FAILED，58 秒，1 项 / 1 失败。真实 Home 首次切换历史及搜索输入均已执行，第二次点击历史未产生 production Reader binding，确认 P05 重选接线缺失。

`hp01-b-query-revision-red` FAILED，15 秒，搜索类 3 项 / 1 失败：已有 Old 列表后，Current 查询闸门未打开，`loadHistory(Current)` 已返回。该失败确认 Test Mode 等待条件缺口。

`hp01-b-green-fixed` / `hp01-b-green-framed` 分别 FAILED（23 / 31 秒）。相对日期 UI 及共享日期契约已通过，但真实选区测试未通过。后续 XML 定位到选区操作读取系统剪贴板时，`AwtPlatformClipboard.openClipboard` 抛出 `cannot open system clipboard`，并出现 JVM 缺失 Android exception handler 的次级异常。原实施者将离屏测试的 `LocalClipboard` 适配为隔离空剪贴板，仍执行 production 文本框及原编辑/选区断言；该测试环境隔离不能代替 HP02 原生输入验收。修复后结果待登记。

`hp01-b-green-clipboard` FAILED，18 秒：离屏输入通过；两项历史动作测试未及时观测到异步弹窗 owner，保留确认可见及取消无写断言，改为等待实际弹窗状态。

`hp01-b-green-dialog` PASSED，48 秒，worker 38480 / process 40032 已退出。主代理读取时间对应的 8 份 XML：domain JVM / Android 选章各 2 项；Desktop 搜索 3、本地入口 3、动作 2、Home 重选 1、Reader 挂载 1；Android history wrapper 2。共 16 项，失败 / 错误 / 跳过均为 0。B 的重构、两种删除范围及排序样本辨别性仍待补验，未据此关闭整个 HP01。

`hp01-b-refactor-fixtures` PASSED，20 秒，worker 3356 / process 16244 已退出。主代理核对 14 份有效 XML（包含本次 task 的 up-to-date 证据）：domain controller 两端各 2、选章两端各 2；共享 UI 2、日期两端各 1；Desktop 搜索 3、本地入口 3、动作 2、重选 1、Reader 挂载 1、文件库删除 `HistoryDeletePersistenceIntegrationTest` 2；Android wrapper 2。共 26 项，失败 / 错误 / 跳过均为 0。此前新 fixture 误以 SQL reset 水位应为 null，而真实行为为 Date(0)，并且本地 fake 未种入用于导航资格复核的历史；修正 fixture，未删除 production 的迟到资格保护。排序样本已改为独立字段及分别明确期望；真实数据库扫描组过滤仍待 E 集成证据。

### C：收藏关联流程

`hp01-c-ui-red` FAILED，35 秒，worker 13576 / process 44976 已退出。`HistoryFavoriteComposeIntegrationTest` 的 4 项均失败：真实历史按钮点击后，无分类 / 默认分类分支未产生收藏数据库结果，每次询问 / 重复作品分支未出现对应弹窗。主代理读取 XML 核对超时位置分别位于数据库收藏订阅和期望弹窗，而非初始化夹具失败。C 尚未取得绿测。

共享接口方向保持固定：共同 controller 处理收藏、分类、重复对象与时机；两端通过现有 `UpdateLibraryMembership` 原子写入，平台执行原有迁移 / 增强追踪。可选测试依赖不能成为 production 空回调，取消分类不能被解释为撤销已启动的官方追踪工作。

C 同轮核对的两项具体风险交原实施者处理：追踪 I/O 不能持有分类提交的决策锁，重复确认不能重复 outbox。已要求闸门与文件库验证，尚未作为通过证据。

`hp01-c-decision-green-preferences` PASSED，20 秒，worker 31760 / process 34436 已退出。主代理核对 XML：共享 `JvmHistoryFavoriteContractTest` / `AndroidHistoryFavoriteContractTest` 各 3 项；Desktop 实际收藏 4 分支；Android 既有 wrapper 2 项，共 12 / 0 失败 / 0 错误 / 0 跳过。共享闸门证明分类确认不等追踪 I/O，共享 fixture 证明重复确认只写一次；真实文件库 outbox / 失败原子性仍待补验，Android 这两项既有 wrapper 不冒充新增收藏接线证据。前次决策验证失败因 common `InMemoryPreferenceStore.getInt` 新对象不保留 fixture 设置，改为构造初值，未改变产品偏好；分类确认现在读取共同当前 dialog，并保留 checkbox On 和具体 category ID 断言。

主代理读取现有 Android `AddTracks.bindEnhancedTrackers`，确认首次绑定还会调用 `SyncChapterProgressWithTrack`，协调远端已读与本地连续进度，并逐服务捕获异常继续。Desktop 现有 `EnhancedTrackerWorkflow` / `TrackingScreenModel.load` 仅 match → bind → insert，后续阅读上报队列不能替代首次协调。原实施者核对后提出窄复用方案：提取 Android 的共同章节 / track 逻辑，Android 旧 wrapper 委托，Desktop 仅历史首次绑定调用；远端更新保留平台 port。不重构其他追踪页面，不新增 schema 或跨网络原子性承诺。该方案已纳入固定 P10，仍须先取得真实 HTTP / 数据库红绿证据。

`hp01-c-enhanced-progress-red` FAILED，11 秒，`HistoryEnhancedTrackingHttpIntegrationTest` 2 / 2 失败：真实生产 registry、Komga HTTP parser 和文件库中，远端已读章未投影，本地连续第 3 章进度未协调到追踪。提取共同 `SyncEnhancedChapterProgress` 后，`hp01-c-enhanced-progress-green` PASSED，38 秒，worker 41392 / process 37764；命令覆盖该 HTTP 类、收藏 UI 和 Android wrapper。首次 HTTP 成功只代表这两种进度分支，HTTP 失败矩阵、共享 wrapper 和其他 C 验收仍待完成。

共享提取的同轮审查发现新的持久性风险：先读取 chapter，再等待远端更新，最后用 `toChapterUpdate` 回写整份旧对象，会覆盖等待期间产生的页码、书签及元数据。已要求原实施者先以真实文件库 / HTTP 闸门取得红测，再改为窄读状态 patch；保持官方远端已读投影语义，不顺带重构全站追踪。该风险尚未关闭。

`hp01-c-enhanced-late-write-red` FAILED，18 秒，该 HTTP 类 3 / 1 失败；主代理读取 XML 确认 `late tracker response preserves newer page bookmark and source metadata` 中，等待期间新写的页码 17 被旧对象改回 0。这是持久性风险的有效红测，另外两项成功进度分支仍通过。

`hp01-c-category-refresh-red` FAILED，22 秒，Android 共享收藏契约 5 / 2 失败：取消后的迟到结果复活 chooser，刷新期间新选择被旧选择覆盖。原实施者声明 refresh 草稿曾先修后测，故仅将自己未验的资格检查恢复到初稿，再写并执行上述失败测试，随后按最小修复复验；不把先前草稿修复顺序计作已完成红绿。两端最终绿及实际返回接线待登记。

`hp01-c-refresh-late-write-green` PASSED，28 秒，worker 48460 / process 7752 已退出。主代理核对 5 份 XML：共享收藏契约 JVM / Android 各 5、真实 HTTP 3、Desktop 收藏 UI 4、Android wrapper 2，共 19 / 0 失败 / 0 错误 / 0 跳过。共同进度 helper 在远端响应后重读章身份，只提交 `ChapterUpdate(id, read=true)`，不回写旧页码 / 书签 / 元数据；分类刷新以 dialog revision 保留取消资格及当前选择。此轮关闭上述两项已复现竞态的 focused 缺口，C 的其余固定验收仍待完成。

`hp01-c-duplicate-manager-green` PASSED（worker 36132 / process 1976），Desktop 收藏 UI 集合扩展至 9 个用例，包含重复作品仍加入、打开精确详情、迁移取消 / 真实源 parser 迁移确认、分类管理返回后的查询及选择保留。随后定向 task 替换了 XML，主代理已核对实际代码与回执；最终 E 集合仍须读取该类的稳定 XML，不为补统计重跑。

`hp01-c-persistence-green` PASSED（worker 44004 / process 24972），主代理读取文件 SQLite 集成 XML，2 / 0 失败 / 0 错误 / 0 跳过。真实 `reject_history_category` SQL trigger 验证收藏、dateAdded、分类和 journal 全部回滚并反馈 InternalError。原重复确认断言仅比较前后增长，不能排除两次重复事件；同轮审查要求加强为两次并发确认后 journal 恰好 +1、FAVORITE 类别恰好 +1，第三次确认不增长。

`hp01-c-progress-wrappers-green` PASSED（worker 49096 / process 47872）；主代理读取 XML，共享增强进度契约 JVM / Android 各 2、Android 实际旧 `SyncChapterProgressWithTrack` wrapper 委托 1，共 5 项通过。`hp01-c-enhanced-failure-green` 在测试编译阶段因缺少 Flow.first import 失败，不计作绿测。修正后 `hp01-c-failure-persistence-green` PASSED，28 秒，实施者回执包含加强的 journal 计数与 HTTP 失败分支；缺失字段 HTTP fixture 当时只有一次响应，不能当作精确 missing-parser 证据。

主代理对照 Komga production parser 发现 metadata 缺字段后仍需请求 progress 才进行 title 校验，单个 `{}` 响应会让第二请求等待至超时。原实施者为 missing 分支补合法 progress，并保留真实 parser，未通过改变产品校验顺序迁就测试。

`hp01-c-production-branches-green` 协调器 PASSED，28 秒（worker 32660 / process 39976）。主代理读取 Desktop HTTP XML：8 / 0 失败 / 0 错误 / 0 跳过，覆盖真实 registry / Komga 成功、403 / 429 / 500、缺失字段（明确 2 个请求）、空体、畸形体、未登录 / 不接受源、无匹配、分类 chooser 出现即启动绑定及取消后绑定继续、首个服务失败后第二个 production provider 仍执行。Android wrapper XML 为 4 次执行 / 1 失败：新增收藏接线用例首次因虚拟 5 秒与真实 IO scheduler 竞跑而超时，retry 通过；不能记为稳定全绿。已要求原实施者以真实时间等待调整 fixture，保留共同 membership 写入先于现有 AddTracks adapter 的断言，并在 C 重构集合复验。

`hp01-c-refactor-discovered` PASSED，56 秒（worker 47612 / process 15668）。主代理核对有效 XML：共享收藏两端各 5、增强进度两端各 2；Desktop 收藏 UI 9、文件库原子性 / 强 journal 计数 2、真实增强 HTTP 8。Android 新增接线的等待修正后，`hp01-c-android-wait-refactor` PASSED，13 秒（worker 29912 / process 19372），XML 为 3 / 0 失败 / 0 错误 / 0 跳过，不再依赖 retry。

同轮 SOURCE 核对另发现 Desktop 初稿按 existing trackerId 跳过已关联增强服务，Android 原 `AddTracks.bindEnhancedTrackers` 则对全部已登录、接受源的服务执行 match → bind → insert → 首次进度协调。已关联但未收藏的历史作品会因此遗漏进度协调；已交原实施者补真实预存 track 分支的红绿测试并移除无来源差异，C 尚不能整体关闭。

`hp01-c-preexisting-track-red` FAILED，27 秒，真实预存 track 的新用例 1 / 1 失败；`hp01-c-preexisting-track-green` PASSED，26 秒（worker 37920 / process 45160）。主代理核对 Desktop HTTP XML 为 9 / 0 失败 / 0 错误 / 0 跳过，product adapter 已移除 existing trackerId 的无来源跳过，预关联作品按同一 production provider 协调进度。该项 SOURCE 缺口的 focused 验证已关闭。

### D：Reader 数据接口门

已固定窄接口方向：调用者仍由唯一上游用例选章；传入作品 source / URL、chapter ID / URL 等精确身份。data 在同一短事务内重读作品、所选章、读状态、页和快照；仅同一未读续读章采用同步页 / snapshot，其余目标采用合法新会话因果基线。不能清空快照，也不能在事务中等待网络或 bootstrap。非删除 writer 迁入 data common，保持生产事务、身份复核、读回校验及原观测；平台只负责 fetch。实际实现与定向红绿仍待交付，尚未批准接入 Reader 消费者。

`hp01-d-reader-context-red` FAILED，46 秒：Android 3 项失败，但其中同步续读用例漏传 `SyncMutationContext.User`，首先失败在 resumePosition 基准，不能算 openChapter 的有效红测。`hp01-d-data-interface-green` 同样因该夹具失败（51 秒），不计数据接口全绿。原实施者更正用户上下文后，仅撤回自己未审的 openChapter 草稿到 default null，再执行 `hp01-d-reader-context-correct-red`：50 秒，Android 3 / 3 失败，位置为所选 API 返回 null；JVM task 尚未取得执行 XML，不虚构两端首红。随后恢复最小实现。

`hp01-d-catalog-identity-red` FAILED，14 秒，Desktop production DI / 文件库的新用例 1 / 1 失败：存储作品 source / URL 已改变，旧非删除 writer 仍允许迟到目录合并。共享 writer 现已在事务内重读 manga 并核对精确身份；未改变 schema 或同步协议。JVM / Android 同一共享目录契约及 Reader 上下文的定向绿测运行中，尚未作为安全门通过。

`hp01-d-shared-data-green` PASSED，62 秒（worker 40928 / process 39192 已退出）。主代理读取 5 份实际 XML：`JvmHistoryReaderOpenContextContractTest` / `AndroidHistoryReaderOpenContextContractTest` 各 3；`JvmNonDeletingChapterCatalogContractTest` / `AndroidNonDeletingChapterCatalogContractTest` 各 3；Desktop production writer 迟到身份 1；共 13 / 0 失败 / 0 错误 / 0 跳过。

同轮独立数据安全检查已核对实际产物：Reader 以 5 字段精确身份进入唯一 SQLDelight transaction，直接查询 / 内部 resume 与 snapshot 读取，不调用另一个悬挂 port；未读同章才采用 resume，其他目标使用 syncSnapshot 合法基线。chapter 映射是既有唯一 mapper 的机械提取。writer 保留 metadata-only patch、插入数量 / 读回 / observation 校验、bootstrap 事务外等待，新增事务内重读 manga source / URL。真实双端文件库覆盖稳定 ID、页 / 书签 / 首取水位、journal 不增加、吞写异常后回滚、bootstrap 等待时 handler 仍可用、迟到作品身份变化拒绝。当前数据接口安全门通过；实施者补同范围无 scope 契约 / 重构和既有 H09/H11 后，可接 Reader 消费者。消费者生命周期、双文件真实接收、目录闸门与失败矩阵尚未通过，P13–P15 不据此整体完成。

数据接口补查发现一个生产兼容边界：Desktop `persistManga` 在新作品发现时会以 `Manga.create(id=-1)` 调用原自然键检查，存储真实 ID 后才合并目录。初稿将新 DB 身份核验放入通用 validateWorkIdentity，会拒绝合法发现。`hp01-d-new-discovery-red` FAILED，13 秒，实际发现入口 1 / 1 失败于 `Source manga identity conflict`；最小修复把现存 DB 核验限制到 merge，通用 validateWorkIdentity 保持原 archive 自然键检查。主代理读取实际修改及既有 persistManga 的 expected 身份核验，确认未放松已有对象的迟到校验。

`hp01-d-data-refactor` FAILED，61 秒：新增普通本地无 scope 用例的 fixture 通过 disconnect 后仍保留 active scope；这不是 openChapter 的因果基线错误。改为 `seed(connect=false)`，创建真实从未接入同步的文件库，不改变 production scope 语义。`hp01-d-data-boundary-green` PASSED，73 秒（worker 47372 / process 7664）。主代理读取两端 context 各 4、nonDeleting 各 3，共 14 项 / 0 失败 / 0 错误 / 0 跳过；实施者回执及明确命令筛选保留 Desktop 7 个方法（新发现、迟到身份、H09 插入 / 更新 / observation / 吞写回滚、自然键 rebind、非收藏 COMPLETE 重开）。Desktop XML 已被后续 task 替换，E 再核稳定集合，不为补统计额外重跑。当前实际数据边界检查通过，已允许原实施者接入两端 Reader；目录阻塞、当前页可读与会话安全仍需实际消费者红绿证据。

`hp01-d-reader-consumer-red` FAILED，15 秒，Desktop `HistoryReaderOpenContextIntegrationTest` 2 / 2 失败：真实隔离双文件同步 fixture 后，经实际历史 Factory 请求仍没有所选章对应 resumeSnapshot，包含未读同章及已读续下一章。`hp01-d-android-read-context-red` FAILED，82 秒，Android `ReaderSyncResumeWiringTest#history selected read chapter cannot reuse its old sync page` 独立 1 项，retry 造成 3 次失败执行；已读所选章仍采用旧同步页，属于实际 ReaderViewModel 消费缺口。

请求合并的同轮接口检查要求保留既有 `SourceUpdateMemoContractTest` 的 combined flags / opaque memo / 错误传播语义，并实际观察 source finally，验证最后等待者取消后网络终止、另有详情等待者时不被 Reader 取消破坏。不能仅以 caller 的 cancelled 标记证明网络已结束。

主代理核对 Android 实际 intent 调用：`resume=true` 唯一生产入口是旧 HistoryTab；MangaScreen 继续阅读走 `newContinueIntent → resumeWithinChapter=true`，指定章为普通 intent。因此旧历史无条件同步覆盖被本计划替换，不能把该旧 fixture 误当仍需保留的非历史入口；继续阅读同章未读页码能力仍须保护。Reader loader 应消费原子上下文中的 manga，避免采用事务前旧下载身份元数据。

`hp01-d-reader-consumer-green` PASSED，30 秒（worker 45100 / process 3276）。主代理读取 Desktop 所选上下文集成 XML 2 / 0，Android 本次已读 history 消费用例 1 / 0；错误 / 跳过均为 0。Desktop Factory 已通过同一所选身份 adapter 消费共同 openChapter，精确同章恢复与已读续下一章快照已验证，历史目录请求数仍为 0；Android ReaderViewModel 已消费共同原子上下文而不以同步章覆盖历史目标。后台目录消费者尚未接完，该结果不能代替挂载后的非阻塞 / 邻接验收。

`hp01-d-catalog-consumer-red` FAILED，37 秒，`HistoryReaderCatalogCompletionIntegrationTest` 1 / 1 失败。主代理读取实际 XML 与 production UI 测试：两个文件库投影仅中间章，目录 hold 闸门期间 Reader 已 Loaded、真实图片请求已发生、经 mounted Reader 动作翻至页 2；随后在等目录调用数变为 1 的位置超时。失败确认后台接线缺失，先读当前章的前置已满足，没有以历史前置 awaitPrepared 代替。

固定 Test Mode fixture 增加本隔离目录源的 `hold` / `release`，供最终真实候选验收目录阻塞；沿用 profile marker 验证，不接收任意 SQL、URL、目录、账号或令牌。候选仍须以实际 production Reader 观测与动作验收，不能用 fixture 合成邻章结果。

`hp01-d-source-flight-red` FAILED，22 秒，Android 新共享请求契约 2 项 / 1 失败：详情已启动 combined 更新，Reader 的第二调用仍重新发起 source 请求；单等待者取消对未引入 flight 的旧默认 await 已通过。新 `awaitSharedCatalog` 是窄 opt-in，原 `await` 的取消 / flags / memo 语义保持，实际最小实现与双端绿测待交付。

请求合并红测进一步修正为直接可辨别的调用数断言：`hp01-d-source-flight-count-red` FAILED，8 秒，Android 2 项 / 1 失败于第二个 source 调用（line 38）；不依赖第二请求的 details flag 异常替代重复请求证据。`hp01-d-source-flight-green` 因 `SMangaUpdate` 不是 data class、没有 copy 而编译失败，不计绿测。

`hp01-d-source-flight-green-constructor` PASSED，19 秒（worker 9060 / process 1716）。主代理读取 JVM / Android flight 契约各 2、原 `SourceUpdateMemoContractTest` 各 6，共 16 / 0 失败 / 0 错误 / 0 跳过。实际 opt-in flight 以 Source 对象、sourceId、mangaUrl 配对，计数等待者，最后退出时移除并取消独立 scope；原默认 await 不变。契约观察真实 source finally，证明最后等待者取消终止调用，另有详情等待者时 Reader 取消不终止它；原 combined flags、opaque memo、错误 / 取消传播保持。Reader 先发起再由详情补 metadata 的分支、Android 实际 UpdateManga 接线和 Reader 后台消费者尚需补验。

## 独立审查与提交

### E：Android 系统栏位置的真实红测

`HistoryContentAndroidInsetsTest` 用 Robolectric ComponentActivity 挂载共享 HistoryContent，向 decorView 派发 48px statusBars WindowInsets；`hp01-e-android-insets-red-proxy` XML 1 / 1，断言记录搜索按钮 `Rect(260, 8, 300, 48)`。随后加入消费端实际观测，发现 `WindowInsets.statusBars.getTop` 为 0，派发值并未送到 Compose owner。这属于夹具失败，不能作为正确产品红测；主代理此前把它称为已确认布局缺陷过早，已撤回。实施者撤下先行 padding 改动，校准 AndroidComposeView 分发 / edge-to-edge 环境，待 owner 实际读到 48px 后再确认旧裸 Row 的红测。模块已实际发现 UI 测试 1 项，但尚无该项产品修复或绿测证据。

后续已取得有效红绿：`hp01-e-android-insets-red-owner` 先断言实际 Compose owner 的 `WindowInsets.statusBars` 为 48px，再观察旧裸 Row 的搜索按钮 top=8px，1 / 1 失败，10 秒；最小修复后 `hp01-e-insets-green-reader-lifecycle-red` 中该 Insets 用例 1 / 0。夹具直接向实际 AndroidComposeView 分发 Insets，并记录 Robolectric decor 转发的边界；owner=0 的先前失败不计入产品红测。

同一 mixed 命令中，真实 Android HistoryTab 挂载的离页返回与双击用例均失败，含各三次 retry 共 6 次执行；实际观察到迟到 Reader intent 和重复 Reader intent。已要求原实施者在共同 controller 接入线程安全、一次消费的请求资格与平台生命周期，不另建事件框架；绿测尚待交付。

### D 受影响 Reader 夹具回归的待办

`hp01-d-consumer-regression` FAILED，191 秒（worker 12068 / process 38628），Android 65 次执行 / 52 失败，含 retries，不视为 65 项独立契约。实施者逐项诊断新的原子上下文 API 与旧 mock / 实际文件 seed 的作品 URL、章节 URL、sourceOrder / chapterFlags 不一致；纯 port 夹具还未提供 openChapter 回应。身份校验不能放宽；原页面、配对、书签、进度与无痕模式断言仍需在校准后通过。

随后 consumer-fixtures 任务（worker 42644 / process 37600）因配对邻章 fixture 未取得既定 gate，仍有挂起的 runBlocking 等待；实施者只停止协调器记录的该任务进程树，协调器记录 CANCELLED / exit 130。该轮不作通过或产品修复证据。新的 Insets 检查仅依赖稳定共享 UI 的 A 范围，串行进行；D 回归仍未完成，不能据此勾选 HP01。

### D：Reader 先请求与 Android 详情实际合流

`hp01-d-regression-e-shared-delivery-red` FAILED，7 分 37 秒（worker 7668 / process 27028，已退出），串行运行共同请求资格的红测和 9 个受影响 Android Reader 类。共享 closed / repeated delivery 在 JVM 和 Android 正确红：重复结果，以及关闭后的 ClosedSendChannelException。主代理读取 Android 9 份 XML 共 70 次执行 / 6 失败 / 0 错误 / 0 跳过；失败仅为 SharedParity 的页面未发布夹具及 Dual 的旧章节查询次数假设，各三次 retry，不算 6 项独立缺陷。配对类 16 / 0、会话类 6 / 0，其他进度/窗口/错误类均通过。实施者为 SharedParity loader 发布实际页面，Dual 改为明确身份选定查询与列表查询的次数，同时保留 opening 在前次写入放行前未完成、旧/新 GetManga 读次数、最终持久读状态及页码断言。修复后 focused 复验待执行；本 mixed 不作整组绿测。

新生命周期差异审查指出：Android 在 IO job 内取得请求 token 会让尚未调度的旧点击在离页返回后取得新资格，删除失效也不能等到异步任务开始。原实施者补实际点击的可控 dispatcher 闸门红测，将资格捕获和失效限制到动作入口；同步锁内不能等待网络/数据库，消费时再核 token/epoch。Desktop 同核入口与最终交付，已挂载 Reader 不被历史列表更新关闭。

`hp01-e-delivery-green-queued-red` 35 秒 FAILED 于测试夹具 scope 外 `async` 编译，不计 Android 页面红/绿；JVM 共同请求资格两项在日志明确 PASSED。修正 scope 后 `hp01-e-delivery-green-queued-red-scope` 78 秒 FAILED（worker 47864 / process 20312，已退出）：主代理读取 SharedParity skip 与 Dual prior-write XML 各 1 / 0，关闭上述两项 D 夹具修复的 focused 失败。Android actual leave / repeated 各 PASSED；queued row 与 detached HistoryTab reselect 各三次 retry 红，页面 XML 共 8 次执行 / 6 失败，正确观察旧任务重新取得返回页资格、重选发送者等待后重放。该轮 Desktop 测试编译失败，无 Desktop 绿测证据；两项新 Android 边界修复及稳定批次回归尚待完成。

`hp01-e-android-entry-green-desktop-red` 78 秒 FAILED（worker 42524 / process 33948，已退出），日志确认 Android 实际页面 leave、repeated、queued-row、detached-reselect 四项全部 PASSED。生产 Android 在点击入口同步取得 token，再把选章放入原默认 IO dispatcher；删除/清空入口同步撤销未交付资格。HistoryTab 的重选由当前挂载 callback 执行，卸载清除同一 callback，不再用等待下一次 collector 的 Unit rendezvous。Desktop `HistoryPreparationLifecycleTest` 新 global duplicate、cancel/dispose、removed row 三项正确红；Desktop adapter 尚待共用 gate 接入及清理，不作 HP01 完成证据。

`hp01-e-desktop-reader-delivery-green` FAILED，106 秒（worker 37112 / process 8220，已退出），9 项 / 2 失败。Home 实际重选、四项文件库历史/详情上下文、row/global 重复交付通过；cancel/dispose 为 UncompletedCoroutinesError，removed-row 失败在删除后立即读取旧列表。主代理发现夹具循环共用 backgroundScope、model dispose 会取消该 scope，以及同查询 revision 的 loadHistory 不能证明删除订阅已传播，已交原实施者核对并修复夹具/接线。不得伪清列表、放松迟到导航断言或无界等待仓库必定删除成功；本轮不是 Desktop 生命周期全绿。

`hp01-e-same-query-red-lifecycle-fixture-import` FAILED，8 秒（worker 10988 / process 44548，已退出）：新的 `HistorySearchStateTest#explicit same query TestMode refresh waits for a new repository result` 通过实际 HistoryTestModeController 重复执行相同文本搜索，以第二次真实仓库订阅的闸门证明旧动作提前完成，取得有效产品红测。修复限定显式刷新时推进同一查询所有者的 revision，不能另起第二个订阅或改变页面输入立即更新的语义；绿测待交付。

同一命令包含修正 scope / 订阅传播夹具后的 `HistoryPreparationLifecycleTest`；主代理读取 XML 4 / 0 失败 / 0 错误 / 0 跳过，包含 row/global duplicate、cancel/dispose、removed-row，关闭该组两项夹具失败，不为补计数重跑。整体仍因正确的 same-query red 失败，不作批次全绿。

`hp01-e-shared-state-refresh-green` PASSED，50 秒（worker 23744 / process 40096，已退出），实施者 XML 回执为 Desktop search 4 / 0、lifecycle 4 / 0，Android actual controller wrapper 3 / 0、actual entry 5 / 0；日志明确 actual entry 五项通过。两端直接暴露 `controller.state`，移除第二份异步状态和 Desktop historyLoaded 普通 Boolean；显式刷新推进 revision，再等待同一查询所有者的新结果，关闭同查询红测。主代理已读取实际生产接口；整批 E 尚未完成。

导航/弹窗的实际事件红测：Android 删除弹层已显示后仍启动旧 Reader intent，三次 retry 正确红；封面首轮被 MangaScreen 实例相等性断言挡住，不计产品红，修为实际类型/栈 size 前置后，`hp01-e-cover-red-fixture-pointer` 的三次 retry 均在旧 Reader intent 处正确红。Desktop `hp01-e-desktop-navigation-dialog-red-pointer` 25 秒，`HistoryNavigationDeliveryComposeTest` 2 / 2：封面应两层栈实际三层、删除弹层应历史根栈实际 Reader；同时真实 Press/Release 的 actions 2 / 0、favorite 9 / 0，无冒泡。

`hp01-e-navigation-dialog-green` PASSED，37 秒，实施者 XML 为 Desktop navigation 2 / 0、actions 2 / 0、favorite 9 / 0，Android actual entry 7 / 0。非 null 的共享 dialog 同步失效未交付请求，两端封面/重复作品/迁移标题/分类导航在 push 前撤旧资格；弹层关闭不重新启动旧请求。收藏/追踪时序与已挂载 Reader 不变。

排序方向核对纠正：主代理最初将 Desktop 的 sourceOrder 升序与 Android 的 `getChapterSort(manga, sortDescending=false)` 原始列表方向差异判断为 P12 缺口，未先核对消费者。实际 Desktop ReaderNavigator 明确使用 newest-first（较小索引是 NEWER/下一章），Android 使用 oldest-first；原始方向是平台 adapter 的内部约定，不能直接对齐后反转实际邻章。该 SOURCE 预期及其首轮失败撤回，不计产品红。真正缺口是 Desktop mapper 固定 sourceOrder，忽略 NUMBER/UPLOAD/ALPHABET 设置。

`hp01-e-reader-manga-sort-correct-red` 10 秒 FAILED，历史/详情真实 context 各 1 项失败：SOURCE 前置按 Desktop 内部方向通过，随后 NUMBER 策略仍输出 sourceOrder 顺序，取得有效红。最小实现直接复用 `getChapterSort(manga, sortDescending=true)`，不新增方向旗标、不改变 Navigator/会话。`hp01-e-reader-manga-sort-green-legacy` 41 秒，实施者回执 Sort 2 / 0（四种策略）、Policy 1 / 0、Grouping 2 / 0、Android actual entry 15 / 0；旧 HistoryModel 缺 history 种子导致 null，属夹具失败，不作整轮通过。

`hp01-e-reader-sort-refactor-context` 34 秒 FAILED（worker 47908 / process 43648，已退出）。主代理读取协调器限定命令和红测日志，实施者回执 Sort 2 / 0、HistoryModel 7 / 0、真实 delayed catalog mounted Reader 1 / 0、Sync final 1 / 0；既有前章 1 / 后章 3、页码/session/snapshot 和 HTTP/键盘邻接断言保留。Synced 旧测试 2 项中 1 失败，仍期望作品级 global latest resume 覆盖官方所选章；按固定 P04/P13 替换为官方目标及原子 context 后待复验。这轮不作整批全绿。

P16/P17 离屏验证：`hp01-e-keyboard-green-reader-sort-red` 中 `HistoryAccessibilityIntegrationTest` 2 / 0、failed-clear delivery 1 / 0。350×900、200% 字体的英文浅色/中文深色场景，实际 Tab/Enter 可打开删除确认、Escape 关闭一层，无数据库删除；Book 封面按像素舍入误差 ±0.5px 核对 53.333×80，动作在窗口内。另一项实际详情导航返回保持查询、非零滚动及原封面焦点。有效红分别是返回焦点缺失、Escape 留着弹层和 false clear 重新交付；初次严格浮点比值失败仅是像素舍入夹具，不作产品红。PNG 是 ImageComposeScene 离屏产物，不能代替 HP02 Windows/macOS 原生输入或 Android 现场验收。该命令整体失败源于上述撤回的 SOURCE 排序预期，不能记为整批通过；Shift+Tab/Space、后台更新和触发器消失的固定边界仍待 E 收敛。

旧 SQL 失败用例的反馈纠正：主代理此前指定 shared InternalError 没有沿完整选章链核验，属于错误预期。实际 `GetNextChapters.await` 使用 `GetChaptersByMangaId.await`，既有实现把数据库异常记录后返回空列表，HistoryTab 通过 null 结果显示无下一章；不必发 InternalError。实施者曾为该断言调整 mapper 读取顺序，这会改变原错误策略，已要求撤去该临时 preflight，恢复官方先选章顺序。正确保留无导航、章节/历史行不变，以及原目录 owner 的真实 Storage 分类；E 补实际 UI 空结果反馈，不为过时 UI 或主代理推断改变固定契约。下述曾指定 InternalError 的记录仅为当时失败历史。

`hp01-d-catalog-refactor-matrix-suspend` FAILED，69 秒（worker 48012 / process 4628）。主代理读取 Desktop actual Reader completion 3 / 0（含六类失败、关闭后迟到、成功补齐）、HTTP parser 3 / 0、目录持久化 15 / 1；后者唯一失败为旧历史 `readStatus.STORAGE` 断言，其真实存储分类及数据安全继续保留，按共享 InternalError / 无导航替换过时 UI 契约。

Android `SourceUpdateMemoBackupIntegrationTest` XML 为 6 次执行 / 3 失败，三次均同一错误传播用例的 retry：期望原 IllegalStateException 对象，实际获得同类型同消息的新对象。实施者确认共享 Deferred await 的协程栈恢复复制了异常；不放宽既有 assertSame，改为内部传递结果、在 caller 重新抛原异常。修复及 last-waiter cancellation 复验尚未完成，这次矩阵不记为通过。

`hp01-d-android-preparer-green-overlap-signature` PASSED，50 秒（worker 41804 / process 39676）。主代理读取 Android preparer XML 5 / 0、Reader 邻接与后续查询失败 XML 2 / 0；日志明确真实 HistoryTab clicked intent 1 项 PASSED。端口使用实际文件 repository、共享 writer 与 DomainModule binding；错误 source ID / 返回作品 URL 被拒绝，完整目录再次准备不发起源请求。详情合流用例先完成真实文件读取，以窄 ChapterRepository adapter 去除进入 flight 之前无关的异步悬挂，其余委托真实 repository，SyncChaptersWithSource 为真实对象的 spy，调用真实文件存储；重叠窗口内目录源调用为 1。

之前两个 mixed green 均保留为失败：首个详情 sync mock 过严未命中实际回读对象；改为真实 sync 后，文件读取造成两请求先后执行，目录计数为 2。编译错误和上述时序失败不作为通过证据；没有修改 production 来迁就 fixture。后续 D refactor 矩阵首次因新增 fixture 缺 suspend 上下文编译失败，此时还未完成矩阵。

总有界 red 在一直挂起的 preparation 上超过 30 秒虚拟时间仍未结束，JVM 4 / 1；最小实现为共享 `withTimeoutOrNull(30_000)`，主代理读到 JVM / Android各 4 / 0。超时退出后仍记录已尝试，外层取消继续传播。后台装配失败缺口已补：Android query error 用例真实观测 job 不再异常取消、active chapter / pages 保留；Desktop mapper 错误的 red 1 / 1 后，主代理读到包含 lifetime 的 Desktop XML 2 / 0，错误不再逃出后台边界。双端只吞非取消的 Exception，不吞致命 Error。

`hp01-d-catalog-consumers-green` PASSED，27 秒（worker 44412 / process 16316），命令明确限定 Desktop lifetime + actual catalog、Android active-object 用例。主代理读取 Desktop 两类 XML 各 1 / 0；Android XML 随随后 preparer 红测清理，Android 1 / 0 依实施者回执及协调器日志保留，不为计数重复执行。Desktop 用 activation epoch 拒绝离开后返回同一章的旧结果，并让导航与预取都消费当前 refs、保留 active ref；Android保留 active ReaderChapter 与页面，使用当前 readingActivation / window sequence 判断资格。Android实际 preparer / DI 此时仍未完成。

同轮消费者审查另发现后台异常边界缺口：shared completion 仅隔离 prepare 调用，Desktop 后续 mapper 和 Android 后续数据库读取、邻接装配在该边界之外。Android `viewModelScope.launchIO` 为普通 launch，无异常处理器；该处失败可能成为未捕获异常。已交原实施者按 P14 补 focused 红绿，使取消继续传播、其他后台装配失败保留当前 Reader；此项尚未关闭。

`hp01-d-completion-policy-red` JVM 3 次执行 / 2 失败，17 秒（worker 41720 / process 19584），实际原因分别为未发起后台准备、未记录单次尝试。`hp01-d-completion-policy-green` PASSED，31 秒（worker 40000 / process 46052），主代理读取 JVM / Android XML 各 3 / 0。共享政策只允许带 scope 的同章恢复，每个 completion 实例以 mutex 记录首次尝试；取消继续传播，其他准备异常返回 null，无本会话自动重试。

`hp01-d-desktop-catalog-green-adapter` PASSED，38 秒，主代理读取 `HistoryReaderCatalogCompletionIntegrationTest` 1 / 0。真实两个文件库夹具通过同步形成接收端单中间章，目录 hold 时 Reader 已 Loaded、图片请求发生并翻到第 2 页；释放后同一 production binding 获得三章 refs，页码及 resume heads 不变，稳定章 ID / history ID 保留，UI 相邻章、HTTP 当前 session 动作和键盘 transition 均可执行。目录请求保持 1。此用例尚不覆盖关闭/换作品晚到、完整/单章请求数和全部目录错误，D 仍未完成。

`hp01-d-android-detail-flight-red` FAILED，49 秒（worker 48284 / process 44912）；真实 `UpdateMangaCreatorArchiveIntegrationTest` 的 joins-reader 用例因重复目录调用失败，含 Android 重试共 4 次执行 / 3 失败，不把重试当成独立契约。随后混合 flight green 协调器于 20:27:21 UTC 返回 PASSED，17 秒（worker 47236 / process 37408）。主代理读到 Android flight 3 / 0、Android 实际 UpdateManga wrapper 2 / 0；JVM XML 已由下一个 focused task 清理，日志仍明确记录三种 flight 顺序均 PASSED。既有 combined 更新契约包含在命令中，首组 16 / 0 证据已在上节核验。

新增顺序真实观察 flags：Reader 先请求 `(false, true)`，晚加入详情共用该目录，再以 `(true, false)` 补 metadata，返回原 opaque chapter memo。Android production `UpdateManga.awaitFromRemote` 仅在 fetchChapters 时选择 opt-in flight，其后的原详情更新、章节同步、archive 流程保留；默认 source service await 未改变。该接口检查通过，Reader 后台 completion 与生命周期消费者仍在实施，不能据此认定 D 完成。

尚未完成 HP01 整体审查或提交。HP01、HP02 保持未勾选。

唯一独立审查提前核对 A 稳定部分时发现 SOURCE/P01 缺口：共享 `relativeHistoryDate` 仅使用 Today/Yesterday/固定日期，而 Android 被替代的 `relativeDateText` 消费已有 `UiPreferences.relativeTime` / `dateFormat`，近期过去和未来日期使用复数资源。初稿忽略偏好，属于既有显示行为回归；已交原实施者补 focused 红测、共有日期决策与平台偏好 adapter。不增设置、不放宽验收，尚待修复证据。

同轮检查发现 P02/P18 等待条件风险：Desktop `loadHistory` 只等待 query 相同且 list 非 null，已有列表时可能把旧查询结果当成新结果返回，影响 Test Mode 的搜索与随后选中。已要求以可控查询闸门补红测，区分输入值与结果所属查询，不用 sleep 或第二个订阅所有者修饰时序。此项尚未关闭。

上述查询等待风险的绿测已在 `hp01-b-green-dialog` 核验；共享输入/结果 revision 配对避免旧结果资格混入。后续 E 仍需检查 Test Mode 最终消费共同状态及生命周期接线。P04 排序初始样本字段相互相关、各策略输出相同，已要求改为互不相关字段及独立期望，防止错误排序策略仍通过；mock 过滤结果不充当实际数据库过滤证据。

## E 最后 focused 收敛

`hp01-e-official-select-refactor` 26 秒，实施者回执 Synced actual mounted 1 / 0、Android shared wrapper 3 / 0，旧 global-latest 覆盖期望已按真实官方所选章替换；当前页与同步 baseline 断言保留。`hp01-e-background-focus-retired-actions-red` 15 秒，两个正确产品红分别为数据库后台条目更新把焦点从手动选择的收藏按钮抢回封面，以及已撤销的 history_cancel 仍返回 success；不使用单纯源码匹配作行为证据。

`hp01-e-background-focus-retired-actions-green` 23 秒 PASSED，主代理读取 XML：Accessibility 2 / 0、HTTP 4 / 0。共享内容记录实际获得焦点的动作；编辑器获焦清除旧条目 anchor，clear 有独立触发器，消失条目回到有效顶栏。HTTP 测试实际执行 production controller，旧 retry/read-existing/cancel 均 UNSUPPORTED_ACTION，数据行保留；API_REFERENCE 对应局部段落已同步。

`hp01-e-keyboard-focus-refactor` 15 秒 PASSED（worker 40812 / process 38296，已退出），限定 Accessibility 与 Actions。实施者回执各 2 / 0；主代理读取实际测试与协调器记录：真实 Shift+Tab 从删除退到收藏，Space 经真实 membership 入库且不冒泡续读；背景作品更新时手动按钮焦点与当前文本编辑器焦点分别保留；clear cancel 回自身按钮，原封面条目删除后详情返回回 search-close。独立查看两张 ImageComposeScene PNG，标题两行省略、200% 动作和双主题可见；没有读取系统桌面像素。本轮仍没有执行完整模块/full/audit/正式构建。

限定格式以明确 FileCollection 选定 135 个本任务 kt/kts（Desktop 51、Android 28、data 11、domain 27、共享 UI 18），排除 protected AppVersion 与其他两份 md。root settings 因 Gradle 项目目录边界，以临时副本格式化后校验/回写该同一文件；未建立永久 lint 配置或修改 root task。Desktop 沿用已有定向 ktlint 的 max-line-length 关闭方式，不将其描述为全仓默认规则；Android/domain/data/共享 UI 保留 120 列规则。初期 init buildSrc/clean 冲突与通配符导入、过长行失败均不算通过。

`hp01-e-scope-format-apply-controller-types` PASSED 6 秒；`hp01-e-scope-format-check` PASSED 7 秒（worker 12824 / process 47836，已退出），主代理读取命令/log：五模块全部限定 KotlinCheck 通过，部分有效 up-to-date；不是 IdeHook 导致 SKIPPED。两端薄 wrapper 清理确定未用 imports，`git diff --check` 为 0。manifest 只局部更新 id64 当前 Android/Desktop consumer 行号和 shared Controller 角色，保留 Task10 状态决定、行为方法及 actionInventory 历史 provenance，不升级整份 HI-01 状态；metadata focused 与唯一受影响批次待执行。

metadata 的两次 `jvmTest --tests` 首先没有发现指定方法；实际默认排除 `parity-governance`，并非起初推断的 integration 标签。改用既有 `:app-desktop:parityGovernanceCheck --tests "mihon.desktop.parity.DesktopProductCapabilityContractTest.task 4 provenance batch resolves fixed and current role evidence"`，没有运行 finalParityAudit。实际红在 id54 的 ReaderViewModel 行号漂移；同批只读定位本任务修改文件中的其他 current-role 共十处漂移，主代理逐处核对原 symbol 后机械校准 id11/22/47/51/53/54/59 的行号。没有变更这些 capability 的状态、行为方法或历史 provenance。原方法复验 XML 1 / 0，metadata 已绿。

唯一受影响批次由 `python .gradle-coordinator/hp01-affected-batch.py` 组装固定 `.gradle-coordinator/hp01-affected-filters.json`：domain JVM/Android 7+7、data 3+3、共享 UI 3+3、Android 16、Desktop 37 个明确类/方法 filters，总 79。以 key `hp01-e-affected-batch-once` 串行协调，worker 40952 / process 45364，日志 `.gradle-coordinator/hp01-e-affected-batch-once.log`；实际 6 分 14 秒，协调器 PASSED / exit 0，于 2026-10-03 23:09:15 UTC 结束，worker / process 均退出。每个目标均带 `--tests`；不是通配整个 history、完整模块/full 或 audit。主代理独立读取八个目标实际 XML：domain JVM/Android各 26，data 各 9，共享 UI JVM 5 / Android 4，Android app 107，Desktop 162，合计 348，失败/错误/跳过均 0。79 个 filters 均发现实际类/方法，不能把 0 测试当通过。

## 正式候选与运行

HP02 尚未进入；未生成本轮 Windows/macOS/Android 正式候选，未实施实体 Android 安装或操作。旧候选不能作为本轮验收证据。

macOS 只读连通预检：`mbp` SSH 8 秒超时，按有界重试使用已配置 `mbp-lan`，读取 `uname` / `sw_vers` 成功，系统为 macOS 14.8.4（23J319）。未启动应用、构建或发送原生输入；SSH 可用不视为图形会话、窗口或输入验收通过。

## HP01 稳定批次独立审查结论

主代理未实施产品代码，同一轮审查覆盖 A–E，先完成原子上下文/非删除存储/flight 的数据安全门，再核对真实 Reader、UI 与 Test Mode 消费者。最终稳定差异及 XML 复核通过，无待解决的审查阻塞。已撤回错误 SOURCE 排序、严格像素比和 SQL InternalError 预期，保持实际官方选章与平台 adapter；不放松页码、配对、书签、隐私或原异常身份断言。

正式原生输入验证须使用真实窗口/输入法：Test Mode factory 与 UI 使用共同 production 实现，但可能是不同 controller 实例；HTTP 查询快照不能冒充原生文本框输入观测。HP02 必做范围保持固定。三个 protected 文件 SHA-256 与起始完全一致，未写入、格式化或暂存。

提交规模：140 个本任务文件，约 7920 行新增 / 1742 行删除（含共享契约、双端发现 wrapper、必要文档与既有夹具校准）。超过估算仍为同一历史能力的内聚交付，没有新 schema、同步协议或独立产品能力；主要风险通过双端文件库/HTTP/真实 Compose/Reader 批次覆盖。

## HP02 一次最终矩阵

HP01 提交后由主代理接管唯一 Gradle 协调权。`hp02-desktop-full-once` 经项目 `scripts/build-desktop.sh full-tests` 执行完整 Desktop JVM 含集成，worker 30836 / process 41636，日志 `.gradle-coordinator/hp02-desktop-full-once.log`。第一次 `start` 命令被自动执行规则阻止，没有启动进程或运行测试；按 AGENTS 指定改 `run` 后启动原唯一目标，不算重复测试。

运行中观察 `DesktopArchitectureGuardTest#desktop ui DI and repository debt does not grow beyond baseline` 失败（42 行）；原批次已 FAILED / exit 1，6 分 40 秒，原 worker / process 均结束，没有重启或强制终止。已交原实施者只读诊断，禁止扩大债务 baseline 或用源码扫描代替产品行为测试。终态及最小修复、focused 复验待记录；再次完整 Desktop 目标须另获批准。

Android `scripts/build-android.py check --signing` 实际成功：JDK 21.0.11、SDK36/build-tools36.0.0、正式原证书可用；只做预检，没有安装/候选版本分配。当前正式元数据 35 / 0.19.4-aex.17；正式候选待源码稳定与完整矩阵通过。

完整 Desktop 实际 XML 汇总：451 类 / 3321 项，4 失败、0 错误、3 跳过。失败 XML 与摘要在 `.gradle-coordinator/hp02-desktop-full-failures/` 留存，原协调器日志不覆盖。4 项为零新增 UI DI 债务守卫、MangaDetailLibraryEntryWiringTest 的真实续读按钮与章节指针、DesktopReaderChapterTransitionIntegrationTest 的真实组合切换。后三项分别观察到 5 秒导航等待超时和精确 MockK 参数不匹配；已沿实际 mapper 查明旧 ReadingProgressRepository 夹具缺少 openChapter（默认 null），旧 runtime mock 则仍匹配六参数调用的空 refs，生产已传实际 refs/opening。不能由这些夹具失败推导发布应用已修复，也不能修改生产 fallback、排序或会话保持来迁就夹具。

3 个跳过分别为 MacOsNativeSharePortTest 的 production JXA、DesktopWindowPrivacyTest 的 Windows frame native affinity 条件、LibraryPageCompositionTest 的 explicit non-release custom interval 条件。按实际平台/编译条件记录，不将它们计为通过。尚无完整 Desktop 绿证据。

原实施者接管这一修复的 focused 协调权，范围限定三处 UI DI 边界和上述两类旧夹具；主代理只读审查，修复复审使用计划唯一 1 轮。再次完整 Desktop 目标待具体修复/绿测后申请；其他最终目标尚未执行，不能把它们当重复目标。

`hp02-four-failures-focused-green` 实际 FAILED，1 分 3 秒，worker 8256 / process 7128 已退出。主代理读取 XML：guard 7 / 0、Favorite 实际 9 / 0、Accessibility 2 / 0、detail 原两入口 2 / 0；transition 1 / 1，MockK 参数错误已消除后，原预取断言期望 `[2]` 实际 `[null]`，说明尚未闭合。不能把这一轮记录为全绿，也不能删除原预取/组合退出时 runtime 保持断言。继续只修真实模型 refs 的夹具或已证实生产缺口。日期另补既有实际页面的偏好 wiring 断言，防止固定默认 getter 仍通过默认 Today 测试；属于同一窄修复的必要验证，不新增功能或审查轮次。

HP02 最小修复：UI 迁移使用现有 `LocalDesktopUiDependencies` sourceManager / migrateManga，日期偏好移入原 HistoryScreenModelFactory，每次重组读取原两 key/default，未增加 baseline。详情 fake repository 实现五字段原子 opening，原目标/页码/真实按钮与指针断言保留，过时 null snapshot 替为精确 fresh baseline；transition fake factory 返回模型时装配与生产工厂一致的 refs，原前章 2 预取及组合退出 runtime 保持不变。无生产 Reader fallback、导航或存储政策变化。

日期新增实际页面 wiring 用例先在临时固定默认 getter 上取得有效 mutation 红：`hp02-date-consumer-mutation-red` 26 秒，实际 UI 已挂载，但 `Owner date A` header 缺失，1 / 1；立即恢复真实 getter。`hp02-date-transition-focused-green` PASSED 19 秒，主代理读到日期 1 / 0、mounted 1 / 0；日期格式 A→B 和相对时间 true→Today 通过实际 search open/close 重组，证明不是固定默认值。

六个修复文件专用 scopedApply PASSED 7 秒；`hp02-four-failures-refactor-check` PASSED 16 秒（worker 43524 / process 14108 已退出），主代理独立读取明确六文件 KotlinCheck 通过，日期 / mounted XML 各 1 / 0，diffcheck0，protected 三文件 SHA-256 原样。前轮 guard7、favorite9、accessibility2、detail2 已绿且未受后续夹具/格式影响，合计 22 个唯一受影响用例通过；没有为汇总计数重复前四组。唯一修复复审通过，修复随代码/测试/本证据提交；再次完整 Desktop 目标尚未执行或获得批准。

正式候选独立 checkout 已创建在 `D:/Codex/worktrees/f235/hp02-history-candidate`，当前仅 HP01 commit，未构建/分配版本，将在修复提交后快进同一源码；不复制 protected 未提交文件。macOS 旧 checkout HEAD 为 c4adc8ad2afaefe1f7a77ee8edeadb5cdf7e3ecf，确认它是本次提交祖先，可传送小范围 bundle 到新 checkout，旧工作目录/日常应用不改。

修复提交 `c1b4a757b09f444ea7cd308cd432b224f5663494` 后，主代理已向用户申请仅追加一次完整 Desktop JVM 含集成（预计 7–10 分钟），依据 AGENTS 的全量失败追加验证审批要求；当前尚未收到答复，不自动重跑。尚未执行的其余最终矩阵按原额度启动，key `hp02-shared-android-client-final-once`，worker 43792 / process 38552，原日志同 key，targets 为 domain/data 双端、presentation-history 双端、app Release、test-desktop 及 spotlessCheck；当前仍运行，未记录全绿。

Windows 独立 checkout 已快进修复提交；macOS 新 checkout `/Users/altair/github/mihon-history-parity-20261004-f235` 由旧 repository 只读本地 clone 后接收 bundle，校验 SHA-256 `24f740b6764c6d25724cc6e518d126b48fe73333e2905c0569f16e700b4abf79`，精确 HEAD 为同一 c1b4a757b0、status 干净。旧 checkout / 应用未写入，双方尚未进行正式构建或版本分配。bundle/checkouts 只是过程产物，不能代替候选或原生运行证据。

其余第一次完整矩阵终态 FAILED / exit1，16 分 45 秒，2026-10-04 00:01:50 UTC 结束，原 worker/process 退出。主代理读取本轮 XML与实际 task 日志，domain JVM 103 类/588项、Android 87类/493项全绿；共享 UI JVM 3类/5项、Android3类/4项全绿。data JVM 81类/761项，1失败、0错误、1跳过；失败XML保存 `.gradle-coordinator/hp02-shared-final-failures/data-TEST-mihon.data.sync.SyncS2ContractTest.xml`，不覆盖第一次日志。data Android、app Release、test-desktop 尚未实际执行（文件时间仍属于旧focused/旧轮），总体spotlessCheck未完成，不能拿旧非空XML当本轮完整通过。

原实施者仅读取 data 实际 `SyncS2ContractTest` XML：6 / 1，在 runTest入场前抛 UncaughtExceptionsBeforeTest；suppressed 是已关闭 SQL statement / database 的 pending-import、category订阅查询，经 SQLDelight Flow/Combine与Dispatchers.IO传播。同步刷新业务断言尚未开始。调用点 `SyncPanelController`89–101与stop175、`SyncRuntime.stopPanel`215等文件没有被本次修改；时间相邻不足定位具体泄漏实例，尚不能证明baseline可复现或与本任务绝对无关。当前没有新修复或追加复审授权，保留该受限结论。

主代理仅继续尚未执行目标的原一次额度：`hp02-unexecuted-android-client-format-once`，data Android / app Release / test-desktop / spotlessCheck，每个尚未执行测试目标第一次完整运行。此前已完成的 domain、共享UI和失败 data JVM不重复；独立审查与修复复审额度不重新计算，新增修复需说明具体成本并等待决定。


### 首次矩阵最终核对与待决定事项

`hp02-unexecuted-android-client-format-once` PASSED / exit0，18 分 28 秒，2026-10-04 00:25:06 UTC 结束，worker44272 / process12232 已退出。data Android XML 为30类/352项/0失败/0错误/0跳过；Android App Release XML 为123类/692项/0失败/0错误/7跳过，均为本轮时间戳，没有 flakyFailure/rerunFailure 节点，日志没有测试 FAILED 或重试执行记录。App 七项跳过：AndroidSyncPanelTest 三项报告打开要求 Unix host 的真实 FileProvider 路径，在本次 Windows 主机跳过；AndroidLegacySyncMigrationTest 四项真实 SQLite 迁移按已有 BuildConfig.DEBUG assumption 在 Release JVM 跳过。不计为通过，真实平台验收也不能由它们推导。

test-desktop 的实际 task 是 UP-TO-DATE（该日志381行），8类/52项/0失败/0错误/0跳过 XML 为2026-10-01 09:35:49 UTC。Gradle 对当前任务输入判定未变化，复用既有结果；不是本轮新执行52项。全仓 spotlessCheck 的适用模块 checks 通过/UP-TO-DATE，没有以旧 XML 冒充格式证据。本次不为刷新日期而追加完整测试。

| 完整目标 | 实际结果 | 当前交付限制 |
|---|---|---|
| Desktop JVM 含集成 | 首次451类/3321项/4失败/0错误/3跳过；失败保留，修复定向22项通过 | c1b4 修复后完整复跑未批准、未执行 |
| domain JVM / Android | 103类/588项与87类/493项，均0失败/错误/跳过 | 本轮首次完整通过 |
| presentation-history JVM / Android | 各3类，5项与4项，均0失败/错误/跳过 | 本轮首次完整通过 |
| data JVM | 81类/761项/1失败/0错误/1跳过 | SyncS2ContractTest 测试入场前异常未闭合 |
| data Android | 30类/352项，0失败/错误/跳过 | 本轮首次完整通过；新增共享 data 修复后需有效复验 |
| app Release | 123类/692项，0失败/错误/7跳过 | 本轮首次完整无失败；跳过不计通过；新增共享 data 修复后需有效复验 |
| test-desktop | 8类/52项，0失败/错误/跳过，task UP-TO-DATE | 复用未变输入的既有结果，不声称新执行 |
| spotlessCheck | 整组 BUILD SUCCESSFUL，适用模块通过/UP-TO-DATE | 未扩大 UI DI baseline 或排除新文件 |
| 三平台正式候选及原生运行 | 尚未执行 | 完整矩阵仍有失败，未分配版本或发布产物 |

只读生命周期诊断进一步核对 SyncPanelController 的真实编译产物：构造器参数与成员都名为 scope，init 的六处不限定 `scope.launch` 实际绑定到构造器参数；javap 显示这些 launch 的接收者是参数（首处 offset276 aload_3 → offset293 launch$default），没有加入成员 scope 的 lifetime。stop 仅 cancelAndJoin lifetime，因此这些初始化监听不受该关闭路径所有。这是已证实的所有权缺口；本次失败具体由哪个实例泄漏仍未确定，未声称 baseline 必然复现或与历史变更绝对无关。javap 只作诊断，不能代替新增真实生命周期红绿测试。

最小候选修复仅六处改为 `this.scope.launch`，保留构造器 API、调用者 scope 和同步协调器独立任务；补双端真实监听退出/数据库关闭契约，不吞 SQL 错误、不放宽失败断言、不扩大同步产品能力。该修复尚未编辑或启动，超出原历史实施批次且需额外独立审查。已向用户申请复用原实施者、增加一轮独立审查，以及 data JVM / data Android / Android App 各一次完整复验，预计40–60分钟。已申请的 Desktop 完整复跑若批准，安排在共享修复之后只执行一次，避免先复跑再被新共享源码失效。

两个申请均尚未收到答复；依 AGENTS 追加规则，不把等待时间当授权，不自动修复新范围、重复完整目标或开始正式构建。唯一聚合报告的本轮记录保留在工作区，待实际修复/最终证据同批提交，不另建纯状态推进提交。起始三个 protected 文件 SHA-256 均保持不变。


用户明确回复“批准”后，新增范围与追加额度生效：复用原实施者进行最小同步监听生命周期红绿重构；主代理独立审查一轮；通过后主代理串行协调 data JVM/data Android/Android App/Desktop 含集成各一次完整复验。Desktop 安排在共享修复之后，避免源码变化使先跑结果失效；预计追加50–70分钟，三平台正式候选及原生验收仍按原HP02预算。当前没有进行上述完整复验或正式构建。


### 已批准的同步监听最小修复与独立审查

复用原实施者新增 `SyncPanelLifecycleContract`，由 `JvmSyncPanelLifecycleContractTest` / `AndroidSyncPanelLifecycleContractTest` 运行同一真实行为。使用临时文件 SQLite、平台 JvmDatabaseHandler/AndroidDatabaseHandler；观察器只委托包装实际 SQLDelight 订阅 Flow，记录进入和 finally 收尾，不复制数据库查询/取消实现。第一例显式 callerScope，观察 active-space 与三条 inbox 查询，并验证 stop 后为0、callerJob与等待中的callerWork仍活、caller完成且关闭库后无新增订阅。第二例通过生产 `SyncRuntime.panel` getter、真实 onboarding 与 MockWebServer HTTP闸门，交换请求已经进入后 stopPanel，监听为0、外部交换继续并上传成功，再关闭数据库。

首红 `hp02-panel-lifecycle-red` FAILED28秒，worker32588/process47304已退出；Android第一例有效红，expected0 actual4，第二例 Windows 文件清理 FileSystemException 掩盖主断言，不计产品红，JVM目标尚未执行。夹具改为独立单线程 queryDispatcher 创建/查询/关闭真实 JDBC，managed driver幂等关闭并释放executor；未改变production。`hp02-panel-lifecycle-correct-red` FAILED32秒，worker38548/process43604已退出，--continue下两个target均实际执行：JVM2/2与Android2/2四项主失败都为expected0 actual4；原XML保存 `.gradle-coordinator/hp02-panel-lifecycle-correct-red-results/{jvm,android}.xml`。

产品仅init六处改 `this.scope.launch`，构造器API和方法内原member scope.launch保持不变。`hp02-panel-lifecycle-green` PASSED37秒，worker13412/process1864已退出，双端各2项、0失败/错误/跳过。随后 `hp02-panel-lifecycle-refactor-affected` PASSED39秒，worker45864/process4768已退出，主代理独读7份XML：JVM14/0与Android11/0，共25项，0错误/跳过；包含原SyncS2双端各6、既有panel暂停恢复/后台收起/返回设置、ticker和两项真实重开runtime wiring。原full异常的具体泄漏实例尚未证实，这组绿不能冒充完整复验。

主代理本轮独审已核真实委托Flow、平台handler、queryDispatcher/JDBC关闭、parent子任务，以及production六处最小差异。文档按真实owner收窄：SyncCoordinator的Flight.owner是当前调用者job，测试仅证明由独立调用者启动的交换不受stopPanel影响；面板scope启动的调用仍随其scope取消。普通Close只是收起面板，不调用stop。Desktop宿主Scheduler.awaitStopped先coordinator.cancelAndJoin，再调用已接线onStopped=runtime::stopPanel，最后才允许关库。没有修改coordinator或UI产品边界。

四文件scoped格式脚本曾clearSteps并禁用max-line-length，不能作为data默认120行长规则的完成证据。主代理发现后交原实施者仅整理新测试长行、保留项目formatter步骤，只限定目标四文件；默认规则检查与新双端四例refactor复验待回执。已通过的其它21项不无故重复。此为当前追加独审中未完成的格式验收，不另起审查轮次或完整测试。protected三文件SHA-256再次核对保持起始值，未暂存。


最后默认格式未通过项已关闭：`hp02-panel-lifecycle-default-format-refactor` PASSED37秒，worker40596/process44956已退出。scope init仅target四文件，保项目原formatter，没有clearSteps或editorConfigOverride；日志127行实际执行data:spotlessKotlinCheck（非UP-TO-DATE）。六条长行正常换行，真实生命周期双端各2/0/0/0再次通过，其它21项没有无故重跑。主代理读取实际init/四文件/两个XML及git diff --check，确认默认格式和文档owner边界通过；本次新增范围唯一独立审查完成，无待解决阻塞，未追加复审轮次。代码/测试/必要文档/本报告合并一个同步退出功能修复提交，随后执行已批准完整复验；HP02仍未勾选。


同步退出修复提交 `5565ec273771b933f3fba01b94b34ed5bf95740b` 已包含上述6路径。主代理接管唯一协调权后启动 `hp02-shared-approved-retest-once`，worker29896/process18380，真实命令 `:data:jvmTest :data:testDebugUnitTest :app:testReleaseUnitTest --continue --console=plain`；这是用户批准的三个完整目标各一次复验，目前运行中。之后才执行批准的Desktop完整含集成一次；不重跑已经有效的domain/共享UI完整目标。测试客户端当前输入未变的UP-TO-DATE结果保留。

Windows隔离candidate与Mac隔离checkout均已快进同一5565源码。新的最小bundle SHA-256 `afa9d82cc009e10230917241b2a880a3c355ed469897fe62ef01c518ed958071`，Mac核一致，双方未构建/分配版本。只读核对主仓库当前Desktop BUILD73、Android versionCode41/name aex.23，高于本分支69/35；正式分配前须再次核对，以更高现有版本为基准，不回退或借用旧产物。

Mac受影响focused按原HP02预算，在独立主机并行执行，不增加代理或Desktop full。真实来源macOS14.8.4，JDK21.0.10+7，SDK36，同一5565checkout；四类为 HistoryAccessibilityIntegrationTest / HistoryNavigationDeliveryComposeTest / HistoryHomeReselectIntegrationTest / HistoryReaderCatalogCompletionIntegrationTest，includeIntegrationTests=true，max-workers2、Gradle heap2g。Mac没有可用的已确认本地代理入口，本轮经SSH独立loopback17408有界转发到本机已配置HTTP代理10808，HTTP/HTTPS显式设置、Java代理参数明确、本地地址bypass；未探测远程代理或改系统代理。

首个Mac key `hp02-mac-focused-once` FAILED/exit1，worker53339/process53340：PowerShell stdin传输CRLF导致最终Gradle参数为 `--console=plain\r`，立即拒绝参数，未进入测试；保留原JSON/log，不计产品红或正式构建。主代理改为显式UTF-8/LF保存脚本后scp，启动 `hp02-mac-focused-transport-fixed`（worker53367/process53368），仍是原四类首次实际运行，目前编译中。这是命令传输校准，不改变production源码。


`hp02-shared-approved-retest-once` 最终PASSED/exit0，33分9秒，2026-10-04 03:22:49 UTC结束，worker29896/process18380已退出。主代理实际XML独核：data JVM82类/763项/0失败/0错误/1跳过，跳过仍为需要显式local comparison的SyncGitCompareAcceptanceTest；data Android31类/354项/0失败/错误/跳过；app Release123类/692项/0失败/0错误/7跳过，均本轮时间戳，XML无flakyFailure/rerunFailure且日志无测试FAILED。App七项仍为前述三Unix FileProvider与四Release JVM SQLite assumptions，不计通过。原S2入场前异常本轮没有重现；修复真实性由新的四项红绿证明，原full具体泄漏实例仍未定，不升级因果结论。

随后启动批准的唯一Desktop完整复验 `hp02-desktop-approved-retest-once`，项目脚本full-tests，worker796/process11300，2026-10-04 03:23:31 UTC开始；目前运行中，版本没有递增或正式构建。domain/共享UI完整结果、test-desktop未变输入复用与此前全仓格式检查仍有效，新四文件默认格式另已通过。

Mac四类首次实际focused运行 `hp02-mac-focused-transport-fixed` PASSED/exit0，5分9秒，2026-10-04 03:06:09 UTC结束，worker53367/process53368已退出。实际XML9项/0失败/0错误/0跳过：Accessibility3、NavigationDelivery2、HomeReselect1、ReaderCatalogCompletion3；includeIntegrationTests=true且没有重复Desktop full。350x900/200%字体英文浅色与中文深色PNG已保存到本地 `.gradle-coordinator/hp02-mac-focused-results/`，主代理查看，两行省略和动作位置在界面内；这是Mac ImageComposeScene离屏结果，空白封面槽由测试fixture提供，不是实际OS窗口截图或原生输入验收。

Android runtime只准备一个独立AVD配置 `mihon-history-hp02-api36`，位于Windows隔离candidate的 `.gradle-coordinator/hp02-android-avd`，API36/google_apis/x86_64，1536MB/2core、独立userdata与注册目录；avdmanager仅本地已安装package，未安装SDK或修改用户AVD目录。其扫描提示其它image devices.xml缺失，但退出0且新ini/config实际存在；尚未启动，不能计运行通过。虚构本地 `History acceptance` 三章各四PNG页只用于隔离本地阅读验收，完全离线；不连接账号/远端，也不能用它冒充同步稀疏目录验收。现有emulator-5586仍未操作、安装或关停。


### 批准复验的 Desktop 最终结果与焦点失败定位

`hp02-desktop-approved-retest-once` 最终 FAILED / exit1，6分25秒，2026-10-04 03:29:58 UTC结束，worker796/process11300已退出。主代理实际XML汇总451类/3322项/1失败/0错误/3跳过；失败XML时间戳2026-10-04T03:25:49.403Z，已保留到 `.gradle-coordinator/hp02-desktop-approved-failures/`，summary.json记录完整统计。三项平台跳过仍按首次矩阵解释，不计通过。

唯一失败为 `HistoryAccessibilityIntegrationTest.native detail return retains query scroll and the originating cover focus` 的252行：`Removing the originating row restores a valid toolbar focus`，expected true / actual false。182行只是runBlocking方法入口；此前进度消息把该失败称为原封面焦点，最终XML已纠正为删除原行后的工具栏回退焦点，封面返回断言不是本轮失败点。原断言前的settle只等待工具栏存在和原封面消失，不等待焦点状态。尚未证实是单帧等待资格过早或真实production恢复缺口，不称flaky、不放宽断言、不重复full。复用原实施者，仅让本步骤等待既定焦点并保留强断言/超时诊断，由主代理协调一次实际method定向验证。用户此次批准的Desktop完整额度已使用；正式构建仍未开始，HP02未勾选。


焦点夹具最小校准已经通过：仅该方法最后一步的settle增加关闭搜索按钮已Focused条件，保留返回HistoryRootScreen、原条目消失、原强断言与既有10秒上界；超时仍输出tags/focused。未改任何production、其它断言或helper，不吞失败。`hp02-toolbar-focus-wait-focused` PASSED/exit0，24秒，worker39496/process13528已退出，实际XML为本轮单方法1项/0失败/0错误/0跳过。主代理读取实际差异、命令和XML并运行git diff --check，确认校准直接等待既定行为而非替换预期；这是一处低风险测试等待校准，按风险自查，不增加产品独审轮次。该次focused表明本轮可在真实scene中交付回退焦点，不足以推断所有负载下行为或把完整失败称为flaky。

目前只剩Desktop完整结果未绿；用户本轮新增完整额度已经用完，再次完整Desktop含integration需单独批准，预计6–10分钟。没有共享production变化，已经通过的data/domain/共享UI/Android完整结果不无故重跑；没有生成正式三平台候选，不分配版本、不勾HP02。待本次夹具修复与证据同批提交后请求这一项额外验证；不创建纯状态提交。三个起始protected文件SHA-256再次核对保持不变。


### 工具栏焦点等待校准后的获批完整复验

用户再次明确回复“批准”，仅新增一次Desktop完整含integration复验，预计6–10分钟；不重复已通过共享/Android完整目标。主代理启动 `hp02-desktop-focus-approved-full-once`（worker40704/process44428），HEAD `8ffddcf5389bf9ab65f66b8d01449c8dd926275b`，唯一协调者串行执行项目full-tests入口。Windows/Mac隔离candidate均已同步同一8ffdd源码，bundle SHA-256 `b50082c2ca36fbc59bcc965c7dccc2c249dda4a0f5609f2711143d80effd0a62` 双方一致，均未分配版本/构建。Android同一candidate的check --signing通过，SDK36/build-tools36.0.0/JDK21和原证书可用；该预检仍读旧版本35/aex.17，不是正式候选或运行通过。当前主仓库较高版本仍Desktop BUILD73、Android41/aex.23，正式分配前以真实较高版本为基准。


`hp02-desktop-focus-approved-full-once` 最终PASSED/exit0，worker40704/process44428已退出，主代理实际XML核451类/3322项/0失败/0错误/3跳过；本轮日期且没有通过重试吞失败。上述三项平台skip仍不计通过。测试代码校准后原删除行工具栏焦点用例在完整负载中通过；这不是移除产品焦点要求。用户本轮批准的唯一追加full已用，前两次Desktop失败证据保留。现在完整矩阵已无失败，进入HP02正式构建；共享/Android已有有效完整结果，不重复。


### 正式候选构建及当前运行边界

正式版本依据主仓库较高现有版本：Desktop seed73由项目入口分配74，两个平台完整版本 `0.11.19.74.8ffddcf`；Android candidate metadata分配42 / `0.19.4-aex.24`，applicationId及原证书保持。以上只在隔离candidate修改，不覆盖root起始AppVersion70用户字节。源提交8ffdd，新增差异均为必要版本常量/metadata。

Mac `hp02-macos-formal-build-once` PASSED/exit0，worker54183/process54184已退出，项目build-only跳过已有Desktop full，Gradle53秒；正式 `.app` 真实路径 `/Users/altair/Library/Caches/mihon-history-parity-20261004-f235/app-74/Mihon Desktop.app` 已确认可执行文件存在。配置独立dist-74与app-74，未替换/Applications或旧日常包；启动前两个端口58961/58962和新profile均空。通过LaunchServices启动本次独立profile mac-74-runtime，包装器54355/实际应用54361，路径与参数已核；testMode=true/appLocked=false，版本由构建路径/日志证明，后续运行版本仍需接口独核。

Windows首入口 `hp02-windows-formal-build-once` FAILED/exit127，worker41720/process42308，31秒，日志停在PowerShell dispatch，尚无PS构建banner/Gradle。版本74已分配。明确校准到系统PowerShell且保VersionAllocated/ExpectedVersion，同一项目Windows脚本，`hp02-windows-formal-dispatch-corrected` FAILED/exit4294967295，worker9352/process47804，30秒，日志0字节，仍未生成产物/进入Gradle。停止追加Windows启动并进行只读诊断；PowerShell版本与candidate git前置单独probe成功不能替代正式入口验收。系统当时约9GBfree；Application/Defender指定最近事件查询没有有效故障输出（查询非0），不据此断言没有安全事件或故障。原因未证实，不归咎内存或代理。

Mac first fresh driver等待首图20秒超时：actualproduction Reader Loaded、当前章1/page1/initial1/refs1、目录held且请求1、页面请求1/图片4；FIRST_PAGE_PRESENTED未产生，不计先可读当前章通过。外部输入工具只读console报告锁屏字段存在且true/onConsole=true；已请求用户点亮并确认本轮候选，期间未发送原生按键，不把该标志直接作首图未呈现因果说明。保留本轮进程窗口供现场确认，未复用旧用户“只是息屏”答复。

外部driver只在ignored目录校准：loaded同时检查当前章/页的真实FIRST_PAGE_PRESENTED事件，避免全局布尔沿用前章；held→release加强邻居flags/index/current/initialPage/heads/真实数据库目录断言，cache要求同profile新进程且无源目录调用。没有改production、新增测试端点或把HTTP业务动作当作原生入口。

Windows没有重型Gradle运行时，启动原计划唯一Android正式candidate，由脚本内置coordinator `android-candidate`（worker7548/process43144）串行执行；未外层重复嵌套，R8/外部原证书签名/manifest校验保持。构建不安装实体设备；当前运行中，尚无正式APK交付。

### 三平台正式候选的实际终态

Windows 启动诊断仅执行有界轻量探针：PowerShell 文件语法、前置路径/版本/Python/Git 核对均成功，原空日志启动失败没有复现，原因仍未证实。随后经协调器 foreground 模式启动一个先输出标记再调用原 Windows 构建脚本的 ignored 包装器；保留 VersionAllocated/ExpectedVersion，不重新递增或重跑完整测试。`hp02-windows-formal-foreground-launch` PASSED，2026-10-04 06:46:28–06:48:07 UTC，worker43504/process39124已退出；实际 `createDistributable --rerun-tasks`、运行版本及漫画柜扩展生产安装校验通过。此前两次启动失败均没有进入 Gradle，不是第二次实际候选构建，也不声称其根因已修复。

Windows 日志两处 `Final unpacked EXE:` 均为同一正式地址，文件已实际核对存在（472576字节）：[Mihon Desktop.exe](<D:/Codex/worktrees/f235/hp02-history-candidate/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.74.8ffddcf-unpacked/Mihon Desktop.exe>)。完整 [Windows ZIP](D:/Codex/worktrees/f235/hp02-history-candidate/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.74.8ffddcf-windows.zip) 的 SHA-256 为 `6c1d3e634d184e7b25f3bcb7c7b333873f5b45cc5fe3013ece43cb0db0acc4c5`。这是有下述未闭合验收项的正式候选，不能标为最终全部验收通过。

Mac `hp02-macos-formal-build-once` 实际终态 PASSED，06:31:13–06:32:08 UTC；[正式应用包](</Users/altair/Library/Caches/mihon-history-parity-20261004-f235/app-74/Mihon Desktop.app>) 内可执行文件已再次确认存在，候选GUI PID54361与LaunchServices包装器54355仍对应本次路径。未改日常应用、旧工作目录或其它运行实例。07:35左右只读核对剩余磁盘约1.9GiB；如获准重建，只能处理本任务自己的过程产物，不能清理其它业务缓存。

Android `android-candidate` 实际 PASSED，06:40:05–06:43:26 UTC，worker7548/process43144已退出。正式 [APK](D:/Codex/worktrees/f235/hp02-history-candidate/app/artifacts/android/0.19.4-aex.24-vc42-8ffddcf538-release/Mihon-Fork-0.19.4-aex.24-vc42-release-universal.apk) 已存在（68575212字节），外部签名及独立 `verify --artifact` 通过；[产物清单](D:/Codex/worktrees/f235/hp02-history-candidate/app/artifacts/android/0.19.4-aex.24-vc42-8ffddcf538-release/artifact.json) 记录源码8ffdd、版本42/aex.24、4 ABI、debuggable=false、R8/资源收缩开启、telemetry/updater关闭、v2/v3签名及连续原证书。APK SHA-256 `7162296d3c5a63e56af8a0fc432f84203bc68df4f210c0905d7020181b05f1ca`；productionInputs `e8ed292c250e3408a12bbfdc3f4cc129bb6ccb3c0d4897209aaf22715cbaf909`；版本差异哈希 `e5fb5e9b66e2939867996d539878e8215c752b7b4f8733c51e69d5c764b6e50a`。R8有optional类警告但任务成功，没有为警告新增规则或改源码。未安装/操作用户实体设备，不能推导真实设备升级验收。

### 本轮正式运行证据与未验边界

| 操作 / 证据 | Windows | macOS | Android |
|---|---|---|---|
| 真实搜索输入与编辑 P01/P02 | 原生前台未取得，0按键；未验 | console锁屏字段true，0原生输入；未验 | 实际Gboard软键输入abc，光标中插x得到axbc，选区替换得到b，Reset清空、Close退出，无结果/列表恢复均通过 |
| 封面详情、正文续读、重选 P03–P05 | focused真实导航已绿，正式原生输入仍待现场 | 同左，正式原生仍待现场 | 原生封面只进入3章详情，正文进入Chapter2；查询b无结果时重选History仍进入全局最近Chapter2；返回保查询b |
| 收藏 P08/P09 | 正式原生分类/重复分支未验 | 正式原生分类/重复分支未验 | 无分类默认收藏实际进入Library；创建隔离分类a后历史加入→取消仍未收藏，重开选中确认→Library分类a实际出现Second acceptance；重复/迁移分支未在候选运行 |
| 删除 / 清空取消 P06/P07 | focused持久性通过，正式原生未验 | 正式原生未验 | 两部本地样本分别默认删除与勾选整作品删除，确认后对应项移除；重建一条历史再清空→Cancel，记录仍在；删除后收藏作品/三章目录及已有Page:3仍保留 |
| 本地邻章 P11/P12 | 正式Test Mode实际前后章及边界通过 | 新候选邻章仍未验 | 原生Next Chapter从2到3，Previous Chapter回2，页面可读；纯本地源样本，不代替同步稀疏场景 |
| 稀疏同步补全 P13–P15/P18 | 正式EXE fresh 9观测通过，目录held时当前章先呈现且可翻页；补全3章不改会话/页/基线，远端推进不跳页，后续真实前后章及边界正确 | 首20秒driver超时，约80秒后同代当前章首图事件实际产生；未在该次30秒后台请求有效窗口内放行旧闸门，未计补全通过 | 当前APK仅运行本地源；共享文件库/HTTP接线测试有效，正式APK稀疏同步原生场景未验 |
| 冷启动完整目录 P15 | 同profile正常退出后重启，新GUI PID39016：目录COMPLETE/3、源目录0次、目标Ch3、initialPage2与两条baseline正确；双页viewport未原生确认，属部分通过 | 未执行 | 未执行 |
| 原生视觉 / 可达性 P16/P17 | 离屏有效；正式UIA仅Window/Pane，没有图像或焦点节点，不算原生视觉/焦点通过 | Mac离屏9项有效；系统锁屏字段仍true，本轮现场确认未回复 | 私有API36模拟器英文浅/深色、font_scale=2.0已查看，历史Delete及清空Cancel/Remove可达；中文浅色及中文深色200%清空确认/取消也已验；英文底栏部分文案省略，不宣称无截断；长标题等仍未验，名称缺口见下节 |

Windows fresh实际9条观测保存在 `.gradle-coordinator/hp02-windows-fresh-observations.json`，各次读取实际production Reader/fixture而非合成Reader。fresh进程44788/launcher39996由HTTP正常关闭并确认退出；冷启动launcher18248/GUI39016监听58951，同一隔离profile。冷启动driver原末尾要求initialPage==currentPage==2失败，实际initial2/current1；只读源核及已有 `DualPageCurrentPageWiringTest` 证明双页组以较小页索引写currentPage，initial2→current1可合法表示含请求页2的组。未修改production或把原失败改写为通过：状态没有提供实时可见组/dual模式，首图事件也可能来自邻组预绘制，所以不能只凭page2事件宣称当前viewport含page2；保留 `.gradle-coordinator/hp02-windows-cache-observations.json` 原实际观测与原生待确认限制。

Mac保留本轮候选窗口与mac-74-runtime profile。07:32 UTC读取实际Reader仍为Ch2/current1/initial1/单章refs、同代FIRST_PAGE_PRESENTED、原sender两条baseline；console为locked=true/lockFlagPresent=true/onConsole=true。按macOS验收规则停止输入，不把工具TCP/HTTP可用、旧现场回复或息屏推断覆盖该当前状态，也不将锁屏字段未经证实归因为呈现延迟。

Android唯一自建AVD为mihon-history-hp02-api36 / emulator-5560，独立注册目录/userdata、1536MB/2核，无真实账号；其它emulator-5586未操作。首次install尾部设备offline、命令exit1保留，恢复后pm/dumpsys实际确认42/aex.24和firstInstallTime06:51:50，之后真实启动/上述原生操作成功。无ADB input text替代IME：软键坐标取本轮私有模拟器图像，光标/选区用真实输入事件，层级检查读取实际EditText/页面。一次返回Reader后搜索栏已保留但输入框未聚焦，旧软键坐标未产生输入；重选检查失败属于driver前置不满足，不计产品红。重新触摸实际EditText聚焦、确认软键盘、输入b并核对No results found，隐藏IME后才成功重选；不据此声称自动聚焦已全部验收。

Android原生图像均来自本任务私有模拟器，没有读取桌面屏幕像素。已查看[历史页](D:/Codex/worktrees/f235/hp02-history-candidate/.gradle-coordinator/hp02-android-current-history.png)、[深色200%字体](D:/Codex/worktrees/f235/hp02-history-candidate/.gradle-coordinator/hp02-android-dark-200percent.png)、[大字体清空弹窗](D:/Codex/worktrees/f235/hp02-history-candidate/.gradle-coordinator/hp02-android-large-dialog.png)。最后取消弹窗并将本VM字体/主题恢复1.0/浅色；未修改用户系统设置。

### 固定 P16 的实际缺口与限定追加申请

实际Android可访问层级：历史封面为clickable/focusable的 `android.widget.Button`，text和content-desc均空、NAF=true、bounds `[42,442][182,652]`。真实 `HistoryScreen` 的Book adapter没有传contentDescription，`MangaCover.Book` 默认空字符串；共享cover modifier没有名称/动作label。Desktop adapter已传item.title，但真实clickable没有动作label。兄弟标题不能为独立可点击封面逆向提供名称。这是产品接线缺口；已有几何/可达与导航绿测没有覆盖名称，不作为此项通过证据。

实际整作品复选框：`android.widget.CheckBox` 的text/content-desc均空、NAF=true；点击兄弟Text没有切换，点击Checkbox本身才Off→On。共享 `HistoryDeleteDialog` 使用无merge语义的Row，Checkbox只有testTag，旁边Text不能命名该独立节点。仅要求补其现有文案的可访问名称，不借此扩大Text/Row点击范围或改删除规则。

分类原生验收继续确认同样名称缺口：实际分类a的Checkbox空text/content-desc、NAF=true；点击真实Checkbox后checked=true，确认后对应作品实际出现在分类a，而取消保留历史收藏入口。共享 `HistoryCategoryDialog` 同样plain Row/Checkbox(testTag)/兄弟Text，源码接线与原生证据一致。名称修复须覆盖封面、删除和分类，不能只修前两项后宣称P16闭合。

原实施者已只读核定最终最小方案：产品仅 `HistoryScreen.kt` 给Book传item.title，`HistoryContent.kt` 给共享cover modifier添加现有action_show_manga的label-only点击语义，`HistoryDialogs.kt` 给Checkbox关联现有dialog_with_checkbox_reset描述，`HistoryFavoriteDialogs.kt` 给分类Checkbox关联真实category.name。复用Android `ReaderSyncEntryWiringTest`、Desktop `HistoryActionsComposeIntegrationTest` / `HistoryFavoriteComposeIntegrationTest`，共7文件/6方法；挂真实HistoryTab/AndroidBook/平台导航与两个弹窗，名称、真实action、精确详情/无Reader误开、Checkbox Off→On、取消无写入及实际分类关系均为强断言。Android分类方法需窄参数化现有实际页面夹具的未收藏作品/类别和生产membership port，真实收藏动作产生chooser，不能手工setDialog；Desktop已有真实文件库负责持久性证据。不得用假cover、字节码或源码扫描代替真实红绿。

此前异步申请先列封面、再合并删除复选框；后续原预算内分类验收确认同类缺口后，最终申请范围一并覆盖分类复选框，以上7文件/6方法取代早先5文件/4方法估算，不增加测试或审查轮数。最终预算：复用原实施者做focused红→绿→重构，主代理追加独审1轮，Desktop完整1次、共享历史UI两端完整各1次、AndroidApp Release完整1次，三平台正式候选各重建1次及受影响原生复验；预计50–70分钟。未变domain/data结果复用，无新增代理/付费服务；再次失败或扩大范围停止相关追加并报告。用户尚未答复，以上只是方案，尚无新源码/测试修改或Gradle/审查/候选重建执行。

上段是当时尚未批准的历史申请，不是当前执行安排。用户质疑反复完整复验后，已撤回其中四个完整目标；随后“继续”授权定向修复与候选更新。2026-10-04 用户替换验证规则，明确首次完整证据可以与修复后的定向补验组合使用，不能把组合结论写成最终源码重新全量通过。

中文正式运行通过应用外观→应用语言选择简体中文，实际历史显示今天、第1章、中文时间和搜索/删除/清除文案；[中文浅色历史](D:/Codex/worktrees/f235/hp02-history-candidate/.gradle-coordinator/hp02-android-chinese-history.png) 与[中文深色200%清空弹窗](D:/Codex/worktrees/f235/hp02-history-candidate/.gradle-coordinator/hp02-android-chinese-large-dialog.png) 已查看。取消后两条历史仍在，最后本VM字体/主题恢复1.0/浅色，应用语言保留中文。仅本任务独立数据目录有这些偏好变化。

等待追加决定前，以adb实际AVD name再次核对emulator-5560后正常 `emu kill`，保留隔离userdata便于续验；再核PID32172/ssh.exe/17408反向入口/mbp-lan与启动时间，停止本任务临时proxy隧道。Windows/Mac候选窗口仍保留供现场确认，旧应用/其它SSH/其它AVD未关闭；后续若获准Mac重建需重新建立本任务有界隧道。

HP02与capability验证状态保持未完成，不运行finalParityAudit，不创建纯状态推进提交。当前候选版本只在独立checkout，root用户原AppVersion70及两份受保护文档字节SHA-256均仍为启动值；唯一聚合报告留待后续修复/最终版本与有效证据同批提交，不混入用户改动。

## P16 名称与动作标签修复：收窄验证

本轮预算：复用原实施者 1 名，主代理唯一独审 1 轮；7 个代码/测试文件、6 个实际页面用例，默认格式规则，三平台正式候选各更新一次及受影响原生补验，预计 40–60 分钟。没有新代理或完整目标。另在本报告和 roadmap 校准长期验证规则，避免后续仅因代码变化机械申请 full。代码格式器对两份旧 Desktop 测试长行统一换行，及删除已确定未使用 import，使代码 diff 超过原行为补丁行数；内聚性仍是同一可访问节点与真实操作验收，未改变其它分支逻辑。

基线 `8ffddcf5389bf9ab65f66b8d01449c8dd926275b`，用户未提交 AppVersion BUILD70 和两份文档按原 SHA-256 保护。本轮只改变 Android Book 的显式作品标题、shared cover 的 label-only OnClick 语义和两类 Checkbox 的名称；没有第二个 click handler、没有扩大 Row/Text 的命中范围，没有分类、删除、追踪、同步、目录或 DI 接口变化。共享封面不提供名称，从而真实 Android adapter 缺少标题时测试仍会失败。

| 实际阶段 / 协调器 key | 证据 |
|---|---|
| `hp02-p16-red` | 08:10:47–08:11:27 UTC，FAILED。Desktop 3 个用例均正确红：cover 的动作标签 null，删除/分类 Checkbox 名称为空。Android 夹具编译失败，没有进入行为测试，不计有效红 |
| `hp02-p16-android-correct-red` | 08:13:13–08:14:00 UTC，FAILED。校准真实 GetDuplicateLibraryManga 构造器后，Android 3 方法因实际 title/reset/category 名称为空正确失败；项目重试各 3 次，XML 9 失败不是 9 个独立用例。正确红阶段 production 尚未修改 |
| `hp02-p16-green` | 08:18:55–08:19:20 UTC，PASSED，25 秒；实际 XML Android 3、Desktop Actions 2、Favorite 1，全部 0 失败/错误/跳过 |
| `hp02-p16-format` | 08:19:55–08:20:04 UTC，PASSED；现有 mihon.code.lint 默认 steps，仅 target 限定本 7 文件，未 clearSteps 或放宽 ktlint |
| `hp02-p16-refactor` | 08:20:25–08:20:49 UTC，PASSED，同六方法与三个模块限定 KotlinCheck；期间实施者完成未用 import 清理，因此另以 `hp02-p16-final-focused` 确认最终输入，不扩大范围 |
| `hp02-p16-final-focused` | 08:22:24–08:22:51 UTC，PASSED，26 秒；最后一次源码清理完成后 Android 3、Desktop 3 实际重新执行均无失败/错误/跳过；限定格式结果有效，无相关未解释失败 |

命令为协调器串行 `:app:testReleaseUnitTest` 的三个 `ReaderSyncEntryWiringTest` 方法和含 `-PincludeIntegrationTests` 的 `:app-desktop:jvmTest` 三个方法；每个目标都使用独立 `--tests` 过滤，准确 argv 在上述 key 的 JSON 中。最终三个 KotlinCheck 通过 ignored init 把目标限定为 app 2、presentation-history 3、app-desktop 2 个文件。正确红 XML 已保留在 `.gradle-coordinator/hp02-p16-red-results/` 和 `hp02-p16-android-correct-red-results/`，不以历史残留 XML 替代本轮执行。

测试执行实际 Android HistoryTab→HistoryScreen→Book、Desktop HistoryTab 与共享弹窗。除了名称和动作非空，还检查精确 MangaScreen 身份、导航栈长度、不发 Reader intent、Checkbox Role 与 Off→On、取消零删除/收藏写入，以及真实 Desktop 文件库分类提交。主代理独立检查了四份 production 与三份测试的差异；label-only 语义与真实 adapter 的点击 action 合并已由双端绿测证明，不靠源码字符串扫描。尚未用旧候选声称修复后的正式原生验收通过。

主代理本轮独立审查通过：共享状态、selection、点击资格、确认/取消及持久化接口无行为扩张；Android 名称必须由真实 Book adapter 提供，Desktop 复用原封面名称；两端 OnClick 合并后仍含真实 action。默认 formatter 对旧长行的修改为机械换行。没有新增资源、依赖、DI 或导航调用点；现有真实导航和文件库证据覆盖受影响的接线。三个用户文件 SHA-256 仍与启动值一致，未纳入本修复提交。

未受影响的首次完整矩阵、已批准复验保留原版本和实际数量，本轮定向结果仅关闭 P16 缺口；目录/同步/domain/data/测试客户端没有新产品输入。正式候选更新使用项目 build-only 路径，避免再跑已撤回的完整目标。这是首次完整结果与后续受影响范围补验的组合证据，**不是最终源码重新全量通过**；候选来源、运行结果和剩余原生边界将在下文记录。

## P16 修复后的正式候选与原生补验

来源为修复提交 `05fdb81a6ad077e5db6a2c30cf9282d33637290d`，Windows/Android 隔离候选 checkout 和 macOS 专用 checkout 均 fast-forward 到该提交，未复制 root 用户改动。跨机 bundle SHA-256 `d871f04caa1731203093faa7e9152659749fa39f9e2bf6a31ffc424bf323ebff`；首次 fetch 用错 bundle ref，未修改 Mac checkout，随后核对唯一 HEAD ref 并正确 fast-forward。三项重型构建串行，无额外完整测试。

| 正式候选 | 实际构建 / 产物 |
|---|---|
| Windows 75 | `hp02-p16-windows-formal` PASSED，08:24:47–08:26:21 UTC，worker12296/process5840退出。经可靠 foreground 包装器调用原 `scripts/build-windows.ps1 -SkipTests -ExpectedVersion 0.11.19.75.05fdb81`，由项目脚本分配74→75、正式构建、运行版本和漫画柜扩展生产安装校验、发布。实际 `Final unpacked EXE:` 为 [Mihon Desktop.exe](<D:/Codex/worktrees/f235/hp02-history-candidate/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.75.05fdb81-unpacked/Mihon Desktop.exe>)，已确认存在472576字节；[ZIP](D:/Codex/worktrees/f235/hp02-history-candidate/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.75.05fdb81-windows.zip) SHA-256 `ddbdf6f2ab67c4cfd751b18f3a0e90f777f0e0ec7c8e0db18a3995f69ac20deb` |
| macOS 75 | `hp02-p16-macos-formal` PASSED，08:26:43–08:27:18 UTC，worker54814/process54815退出。原 `bash scripts/build-desktop.sh build-only`，57任务16执行41UP-TO-DATE，实际发布 [Mihon Desktop.app](</Users/altair/Library/Caches/mihon-history-parity-20261004-f235/app-75/Mihon Desktop.app>)，包内正式可执行文件已核存在；保留旧app74，仅新增本任务app75/dist75，剩余空间1.3GiB，未清其它缓存 |
| Android 43 | 原证书签名预检通过；第一次 candidate 在Gradle前拒绝已占用42，没有构建或采用旧APK。按规范明确分配43/aex.25后，`android-candidate` PASSED，08:28:39–08:31:21 UTC，worker49028/process38692退出，377任务33执行1cache343UP-TO-DATE；[正式 APK](D:/Codex/worktrees/f235/hp02-history-candidate/app/artifacts/android/0.19.4-aex.25-vc43-05fdb81a6a-release/Mihon-Fork-0.19.4-aex.25-vc43-release-universal.apk) 已存在68575212字节，独立verify通过，SHA-256 `4423e2a6d33ade76ef85dd89fd465f1491d5d667a9e759998dc411ee74f40bef`；[清单](D:/Codex/worktrees/f235/hp02-history-candidate/app/artifacts/android/0.19.4-aex.25-vc43-05fdb81a6a-release/artifact.json) 记录productionInputs `c70435ad182bf12a9a6fb119ca30b103197febf720ed4a9bd040112d2dfe941a`、sourceDiff `5dc640f83afb95d7c76349cf31ffe8240ae6cb0df61da6fc05f7b628961771fc`，原连续证书/v2/v3、4ABI、R8及资源收缩与禁用遥测/更新器均有效 |

Android AVD仍为私有 `mihon-history-hp02-api36` / emulator-5560 / 独立userdata，1536MB、2核、headless；其它 emulator-5586 未操作。通过项目独立 `install --artifact … --serial emulator-5560` 实际原位升级，dumpsys核versionCode43/versionNameaex.25/lastUpdateTime08:33:49 UTC，再启动真实应用。先前42的收藏、历史、分类和中文设置保留，没有操作实体设备。

新正式 APK 的实际 UIAutomator 节点和原生坐标动作记录在 `.gradle-coordinator/hp02-p16-android-observations.json`：

- 历史两封面分别为 `Second acceptance` / `History acceptance`，实际Button的content-desc正确、clickable/focusable=true且无NAF；点击前者只进入对应作品详情“共1章”，返回历史仍有两条记录。
- 真实删除弹窗Checkbox名称“重置此作品的所有章节”与相邻文案一致，默认checked=false，无NAF；点击后true，取消后两历史仍在，未确认删除。
- 在仅本任务样本详情取消收藏，再由真实历史“添加到书架”打开分类：Checkbox名称a、默认false且无NAF；取消后历史添加入口仍在；重开默认false，点击true→添加，真实书架分类a再次出现Second acceptance，恢复该样本收藏。
- [新候选中文历史](D:/Codex/worktrees/f235/hp02-history-candidate/.gradle-coordinator/hp02-p16-android-chinese-history.png) 来自该私有AVD；本轮中文/正常字体/浅色未改变既有偏好。旧候选的真实IME、中文/英文深色200%等未受名称补丁影响的证据保留原版本，不声称全部在43重跑。

Windows旧GUI39016/launcher18248经原HTTP正常关闭并确认退出；新正式GUI48960/launcher43648，profile为`hp02-p16-windows-profile`，HTTP58971/JMX58972。有界原生Tab前置检查仍报前台PID2228不等于48960，**0按键**。新候选fresh闸门下当前章实际首图已呈现，单章refs、initial1/current1与sender两条基线正确；外部脚本`go_to_page2`后等待currentPage2超时，实际双页组current1，符合前文已核对的双页较小索引语义。保留原失败，没有修改production或把它改为通过；该次30秒目录闸门窗口已过去，不记目录补全通过。旧74九观测仍有其版本/场景有效证据。

macOS旧74正常shutdown请求已收到；PowerShell管道传输here-doc的CRLF终止符导致包装脚本额外NameError，未再次发送shutdown；稍后核旧实际进程已退出。新75经精确LaunchServices路径启动，GUI55017、HTTP58981/JMX58982、独立mac-75-runtime profile。第一次driver在异步挂载前立即断言production导致失败，随后只读有界观察原导航，未重新造数据或复制实现：真实Reader target1/Loaded，refs[2,1,3]、initial1/current1、FIRST_PAGE_PRESENTED、catalogCOMPLETE/3、目录请求1次。该场景没有延迟闸门，不替代P13/P14的受控延迟验收。系统console仍`locked=true/lockFlagPresent=true/onConsole=true`，**0原生输入**，没有把HTTP appLocked=false当作图形会话解锁。

本轮受影响 Android 原生名称和操作补验已经通过；Windows/macOS原生键盘/焦点、Windows可见双页组，以及Android同步稀疏/重复迁移正式原生场景仍待验。已再次请用户确认两端新候选可见前台；现场回复尚未到达，不把等待当作批准或验收。保留两端新窗口供现场确认，不勾HP02，不运行finalParityAudit。本次不是roadmap全部完成的报告。

## 继续执行：正式候选运行补验

本轮复用原实施者完成 Android 原生重复分支；主代理执行两端正式产物的延迟目录与缓存补验，未增加代理、生产源码变更或完整测试。三个 protected 文件 SHA-256 与启动值一致。以下证据均属 Desktop75/Android43，不能与旧产物结果混称一次最终全量通过。

### Desktop75：单页受控目录及冷启动

复用 production 隔离偏好 factory 的真实 values.properties，阅读模式 LTR、dualPage=false，仅配置本任务专有 profile。未调用 Reader Test Mode 中不改变 production 模式的 reader_mode 空分支，未改 production 双页语义或既有断言。

| 平台 | 真实过程与结果 |
|---|---|
| Windows75 | 旧48960/43648正常退出；新单页profile hp02-remaining-win-single，fresh GUI30724/launcher42884，正式发布EXE，HTTP58971/JMX58972。fresh九观测PASS；正常退出后同profile重启launcher44480，cache一观测PASS。证据 .gradle-coordinator/hp02-remaining-win-single.json 与 hp02-remaining-win-cache.json |
| macOS75 | 旧55017正常退出；LaunchServices启动同正式app75，profile mac-75-single，fresh GUI55928、HTTP58981/JMX58982。fresh九观测PASS；shutdown收到后20秒观察仍存活，未另起实例，随后只读确认进程退出及包装器45171 exit0，再同profile重启GUI55981/包装器34656。cache一观测PASS。证据 .gradle-coordinator/hp02-remaining-mac-single.json 与 hp02-remaining-mac-cache.json，已复制root同名路径 |

两端实际观测一致：目录闸门关闭时 Reader 已 Loaded/FIRST_PAGE_PRESENTED，refs仅中间章，initial/current1，sender两条基线；目录释放前翻至page2；释放后COMPLETE/3、请求1次，当前章/page2/initial1/heads均未变化；新同步结果不覆盖挂载会话；关闭后历史打开最新Ch.3，initial/current2及新原子基线正确；真实前后章2→1→2→3和两端边界成功。冷启动actual initial/current2、目录COMPLETE/3、chapterCalls0。每项来自 mounted production Reader 与实际文件库/HTTP，不是合成 refs。

外部夹具启动配置错误保留事实：Windows Python write_text 默认把标记LF变CRLF，正式应用拒绝 Unrecognized test profile marker；仅修 owned 标记精确LF，未生成DB或产品修复。macOS Python3.9不支持 write_text newline 参数，创建空目录后TypeError；只移除本任务确认空目录，夹具改 write_bytes 固定LF。受控fresh闸门没有延长30秒 production超时。Mac zsh PATH没有rg，后续精确ps路径核对改用Python筛选，不重复启动。

### Android43：重复作品原生分支

私有 AVD mihon-history-hp02-api36 / emulator-5560，本轮launcher17604，正式应用PID2899；每次驱动均核AVD及versionCode43，未操作用户 emulator-5586。新增唯一虚构本地作品 History / Chapter 1.cbz（复用本任务虚构zip），既有收藏 History acceptance 触发原版模糊重名检查。

13组实际 UIAutomator XML/截图及 observations.json 位于隔离候选 .gradle-coordinator/hp02-android-duplicates/，驱动 hp02-android-duplicates.py。主代理独立读取13条真实节点记录，确认以下结果：

- 重复提示取消后，History仍显示添加到书架，3条历史保留。
- 短点候选进入选择需要迁移的数据（章节、分类、显示作品、共存、迁移）；原生Back取消后，新作品仍未收藏，未执行迁移。最初短点误判为打开详情已在原记录更正标签及说明，不作打开已有证据。
- 长按候选打开 History acceptance 详情：在书架中、共3章；对象正确。返回后重复弹层仍在。
- 依然添加→分类a勾选→添加；分类a实际显示History与Second acceptance，默认分类保留原History acceptance；新History详情在书架中/共1章，历史3条均无添加入口。两个作品共存，无取消写入或原作品覆盖。

本轮未改文件库SQL、未复制收藏/迁移实现，也没有使用注入导航替代这些Android真实事件。

### 尚未完成的真实门槛与追加方案

Windows前台PID2228进程路径已核为系统LockApp，与候选同Session1；Mac console locked=true/lockFlagPresent=true/onConsole=true。这是当前观测的输入前置条件，不能从HTTP appLocked=false推断系统解锁。已请求现场解锁并置前台；未收到回复前，0原生按键，不把HTTP补验计入P01/P02/P16/P17原生通过。

Android正式43的DI使用默认GitHub认证端点，无本地Test Mode播种入口。ReadingProgressRepository需真实active sync scope与inbox resume heads才能触发稀疏目录补全，普通本地CBZ/备份还原不满足。现有 sync-android-acceptance.init.gradle 改成 app.mihon.syncacceptance 和debug证书，额外keep规则只能证明隔离ART产物，不能替代正式43。正式43 mapping中SyncLocalJournal主体仅保留构造、disconnect、renewIdentity，connect已被内联至lambda；仅有源码类名不能证明外部测试可调用。

上轮建议的最小追加范围（提出时尚未授权；2026-10-05 用户已明确批准，本轮执行结果见后文）：仅私有5560，沿用原实施者；补充独立外部 instrumentation APK，绑定本fork正式签名目标，不向生产应用新增HTTP/UI播种入口；以真实journal/inbox/projector形成虚构同步稀疏作品，以本地夹具扩展控制目录闸门，实际点击历史与前后章。补充精确R8测试ABI保留规则及相关回归，正式候选递增重建一次，保留R8/资源收缩/证书连续性，不使用另一应用身份冒充发布包。独立检查签名目标、fixture隔离与production接线；失败只做相关focused补验。预计新增60–90分钟，一次正式Android候选构建，零完整模块/全量重跑。外部助手签名、AGP测试依赖与精确保留接口须先校准，不能宣称方案已经执行。

提出方案时的替代路径为已有真实同步测试环境手动执行；2026-10-05 已选择并批准外部夹具，不再因缺少真机停止可由隔离模拟器完成的验收。HP02仍按实际剩余门槛判定。待验项不会下调为不适用。本节补验归入HP02最终批次，不为单独record evidence创建状态提交；最终验收和checkoff尚待完成。

正式版本75/43来自本任务独立候选，随本批产物证据记录进入提交；root用户AppVersion草稿70按原字节保留，提交正式版本时从独立分配结果形成仅BUILD75的Git index内容，不暂存用户草稿70。两份受保护文档亦不暂存。后续候选应以已分配75/43为基线，不用工作区的旧用户计数草稿重新分配。


## 2026-10-05：外部 Android 正式夹具与息屏校正

用户明确确认 Mac 是息屏而非锁屏，要求核对经验文档、将模拟器优先验收写入 AGENTS，并批准上轮外部夹具、限定独审和一次正式 Android 重建。预算为复用原实施者一名、主代理一次限定独审、60–90分钟、零完整模块/全量重跑；局部助手失败仅补验对应路径。本批超过8文件/400行，内聚范围为正式 R8 产物的独立外部验收边界、虚构夹具、版本及必要治理/证据文档，没有新增产品播种入口、DI替换、数据库迁移或用户能力。

### Mac 与模拟器规则

MACOS_ACCEPTANCE 原已写有“息屏与锁屏分别记录”及用户2026-10-03的现场纠正；上轮仍描述“待解锁”不当，本次已更正当前状态。只执行一次 `caffeinate -u -t 5`，实际 `locked=true` 字段随后缺失，`onConsole=true`；字段缺失记未知，未声称机器证明解锁。用户本次息屏确认配合精确正式 PID55981 的窗口/前台/权限预检通过，窗口1024×768。AX焦点位置仍报-25202；事件发送成功不计搜索/焦点通过。经验表只新增本轮记录，原用户未提交的关停排查段落与2026-10-03关停记录仍保留其归属。

Windows旧进程已退出，本轮复用同正式75EXE及专有single profile，新launcher10956/GUI48824、HTTP58971/JMX58972，原生空按键预检通过。Windows UIAutomation只能取得窗口/Pane，不能提供实际编辑器状态；Mac AX也不能提供焦点几何。HistoryTestModeController与真实HistoryRootScreen分别创建model，HTTP history_search snapshot不能替代原生编辑器输入结果，不将这种观测边界误判成产品缺陷。两端搜索、键盘焦点和可见双页仍待实际界面结果，不因窗口前置检查通过勾选P01/P02/P16/P17。

AGENTS已追加：已授权隔离模拟器能够覆盖的真实 production、原生 UI/IME、持久化、升级和集成应继续，不因缺少真机停工；只对明确依赖硬件/厂商或计划指定真机的项目保留具体缺口。当前仅私有 `mihon-history-hp02-api36` / `emulator-5560`，API36、2核/1536MB、独立userdata；用户 `emulator-5586` 未操作。

### 外部助手边界与正确红绿

新增 `app/src/historyFormalAndroidTest/` 的三个Kotlin文件和测试manifest，只由 `scripts/history-android-formal.init.gradle` 加入 androidTest source set；普通正式host不编入这些夹具类。测试APK身份固定 `app.mihon.desktop.fork.test` /160001/1.6.0，使用已有签名脚本的Instrumentation分支和本fork连续证书。没有改变host应用身份、非debug属性、R8或资源收缩，也没有放宽签名入口。

助手同时作为普通扩展，经真实“浏览→插件→信任→图源”加载 `HP02 local history fixture`/English；主代理独读真实UIAutomator节点确认，未向SourceManager替换DI或注入假源。外部Python服务仅127.0.0.1:18464，固定success/failure作品、四张真实PNG和最多28秒目录闸门；只将观察写入本worktree的ignored协调器目录。fixture AST及实际HTTP/PNG/目录/500/404自检通过。服务不打开产品数据库；助手SQLite仅只读防护与观察。所有数据变更走正式 SyncBatchCodec→SyncInboxStore→SyncRuntime.projector；journal连接只允许本任务SPACE/actor，拒绝其他active space。

每个运行首先核AVD名、fork身份、非debug、versionCode、**已安装base.apk SHA256**及连续证书；仅在本任务虚构同步作品触发。成功场景是未收藏、未读中间章、pageIndex1、UNKNOWN/1章/1条历史、两个来自remote sender的heads。实际HistoryTab文本条目点击打开真实ReaderActivity。首图证据为mount中的PagerPageHolder恰为所选ReaderPage、在窗口中心可见，且SSIV已解码并经过实际onDraw；不以下载状态或预读页代替。目录完成前后直接断言currentChapter/pages/activation/session/openContext及snapshot对象身份相同，保持当前页，不重开会话。

| 阶段 | 实际证据与结果 |
|---|---|
| 夹具接线校准 | 首次PowerShell未引用点号属性导致release guard拒绝；随后Google Maven DNS失败，JVM代理配置一次实际下载成功；错误nonProxyHosts竖线被bat解析及缺抽象chapterPageParse均属夹具/环境失败，不计产品红 |
| 正确红 | 对已安装正式43运行 `HistoryFormalInstrumentationTest#formalHistoryFixtureAbiIsCallable`，1测试/1失败；`NoSuchMethodError SyncLocalJournal.connect(...)` 来自正式base.apk classes3.dex。hash/cert/nondebug/AVD防护已实际通过；此时尚无production keep变更。日志 `hp02-android-history-abi-red.log` |
| 最小实现 | `app/proguard-rules.pro` 精确保留25类的36成员，allowoptimization，无新增whole-class keep，保留production R8。当前dex成员预核通过，仅作预检，真实44运行才算绿 |
| 默认格式/编译 | 初次3Kotlin格式失败及一处>120长行已修；未禁用默认steps。最终 `hp02-history-formal-helper-green` PASS，2分11秒，393任务20执行373UP-TO-DATE；assembleReleaseAndroidTest及3文件默认KotlinCheck通过 |
| 正式ABI绿 | 正式44实际调用同connect入口，1测试/0失败，runner0.069秒，日志 `hp02-android-history-abi-green.log`。修复已确认；这是发布产物外部测试接口保留修复，没有新增用户UI |
| 延迟成功 | 正式44，1测试/0失败，3.751秒；PID9757。目录阻塞期间第二页真实呈现，原生翻至第三页并写db page2；释放后COMPLETE/3章、prev1/next3、1次目录请求，当前章/页和会话保持。日志 `hp02-android-history-success.log` |
| 延迟失败 | 正式44，1测试/0失败，3.383秒；PID9869。相同首图/原生翻页成立；释放后HTTP500，UNKNOWN/1章、无邻章，当前第三页及会话保持，1次目录请求。日志 `hp02-android-history-failure.log` |
| 冷缓存与首个原生边界检查 | 精确host force-stop后新ART PID10003，缓存三观察全部成立：COMPLETE/3章、未读db page2/实际第三页、0目录请求，身份与旧进程不同。后续原生2→1成功，但测试读Icon child enabled=true而非真实clickable Button父节点，整体测试1失败；保留 `hp02-android-history-cache.log`，不声称该整测试绿，也不SQL恢复旧进度 |

### 正式候选44

来源checkout `05fdb81a6ad077e5db6a2c30cf9282d33637290d` 加本批精确keep/版本及脚本输入，已核复制文件hash；正式候选输入快照在artifact.json，后续仅改外部助手不会改变这份APK。统一 `python scripts/build-android.py candidate --offline`，协调器 `android-candidate` 19:26:37–19:28:48 UTC，PASS，2分11秒，377任务31执行346UP-TO-DATE。唯一获批正式重建使用44/aex.26，连续原证书、v2/v3、4ABI、R8和资源收缩、无遥测/更新器。

- [正式APK](D:/Codex/worktrees/f235/hp02-history-candidate/app/artifacts/android/0.19.4-aex.26-vc44-05fdb81a6a-release/Mihon-Fork-0.19.4-aex.26-vc44-release-universal.apk)：68591596字节，SHA256 `cc5ffd4096e7b43bde3bae065488e385a344ed676f378a53f5cf84ccd49a6ca6`。
- [产物清单](D:/Codex/worktrees/f235/hp02-history-candidate/app/artifacts/android/0.19.4-aex.26-vc44-05fdb81a6a-release/artifact.json)：productionInputs `43d2ecd30530cef93ca7fd71f3b2e487cd4dc4c303a95041178f9c5532b033d7`；sourceDiff `78d3a64644bcd1532d14e2141c6ae5fc31e13b128046efeacb1c722f41de4474`。签名/身份独立verify及统一 `install --serial emulator-5560`通过；原43数据库与插件信任保留。

独立verify包装首次误写文件名、随后误设不存在的JAVA_HOME而失败；纠正至已安装Adoptium21后真实verify通过。没有为此重建，也不将这些调用错误描述成签名失效。此前正式43证据保留原artifact与hash，未覆盖为44。


### 原生导航助手修复与唯一限定验收

第一次cache测试已保留其失败。production ChapterNavigator 的启用/禁用属性属于外层 FilledIconButton，描述属于内部Icon；首次助手错将子Icon的enabled作为按钮enabled。诊断的parent-chain XML来自误开的旧Second acceptance，只证明父/子节点结构，不作为success首章证据；误开可能产生真实阅读/历史写入，未SQL恢复、删除样本或宣称零写入。

修复只在外部Reader助手：从description节点上溯到实际clickable按钮，fresh查询并有界等待其启用/禁用。保留false和同ReaderChapter强断言。新增navigation独立phase，跳过播种及旧未读/page2冷缓存前置，使用既有success完整作品，以真实历史点击进入、实际按钮归一到中间章，再验证前后章。它不下调原cache条件，也不冒充cache整测试重跑通过；合法导航改变进度后不伪造旧数据。

`hp02-history-formal-navigation-helper` PASS，19:38:19–19:40:38 UTC，2分18秒，394任务27执行4cache363UP-TO-DATE，3Kotlin默认Apply/Check与助手assemble通过。已有连续签名入口生成新的外部助手SHA256 `ff2c3925e6404b61a0431cd3c127ecb77836d44919d6e0d7b5a43cfa672436b4`，仅安装test APK，**没有第二次正式host重建或安装非正式Gradle host产物**。`hp02-history-formal-navigation-final-inputs` 对最后源码编译和默认格式补核PASS，compileReleaseAndroidTestKotlin实际UP-TO-DATE，确认签名助手对应最终输入。

正式44唯一navigation补验1测试/0失败、15.201秒，日志 `hp02-android-history-navigation.log`。真实2→1→2→3→2，各章所选页面在真实viewport解码并onDraw；首章上一章/末章下一章实际按钮禁用，原生点击后当前ReaderChapter同对象；终尾目录请求0。按钮描述使用本私有AVD已核对的中文资源，不作为所有语言验证。原先success4/failure4/cache部分3/失败native1加本次navigation9，总21观察；`.gradle-coordinator/hp02-android-formal-observations.json` SHA256 `7b38526c223363e687199fbc20216bb273de0c2910b1cbc1dd8fc163c7ac283b`。

主代理完成本批唯一限定独审并独核真实日志/21观察/正式artifact hash与证书；核对只读数据库、防外部active scope、普通扩展加载、精确keep、真实历史入口/所选page绘制、会话snapshot对象身份及原生导航。Icon选择修复后仅复验该导航路径，未增加代理、广泛审查或full。最终Reader助手源码SHA256 `fecb1b6a2c20b917d438b7f56aa20a771119b8b96a446419082d99b34f0bb4bb`；此后无产品或助手变更。

### 复现与维护边界

重型助手构建同样经协调器串行，普通release保持R8；外部源码不进入正式host。例：

```powershell
python scripts/gradle-coordinator.py run --key history-formal-helper -- .\gradlew.bat :app:assembleReleaseAndroidTest '-Pmihon.testBuildType=release' -I scripts/history-android-formal.init.gradle --max-workers=2 --offline
& scripts/sign-android-fork-release.ps1 -Instrumentation -InputApk app/build/outputs/apk/androidTest/release/app-release-androidTest.apk -OutputApk .gradle-coordinator/history-formal-helper-signed.apk
python scripts/history-android-formal-fixture.py --output .gradle-coordinator/history-formal-observations.json
# 仅已授权并核身份的本任务AVD：外部助手安装和reverse是独立动作，不隐式操作真机。
& D:/Android/Sdk/platform-tools/adb.exe -s emulator-5560 install -r .gradle-coordinator/history-formal-helper-signed.apk
& D:/Android/Sdk/platform-tools/adb.exe -s emulator-5560 reverse tcp:18464 tcp:18464
& D:/Android/Sdk/platform-tools/adb.exe -s emulator-5560 shell am instrument -w -r -e expectedVersionCode 44 -e expectedApkSha256 cc5ffd4096e7b43bde3bae065488e385a344ed676f378a53f5cf84ccd49a6ca6 -e historyScenario navigation -e class mihon.history.fixture.HistoryFormalReaderInstrumentationTest#syncedSparseChapterIsReadableBeforeCatalogueAndRetainsActivation app.mihon.desktop.fork.test/androidx.test.runner.AndroidJUnitRunner
```

首次success/failure必须是无同名固定fixture、无其他active sync scope的已初始化专有数据；现有AVD已播种，重跑会主动拒绝，不直接SQL改库/清除用户数据。cache必须在success后新ART进程、保留原未读page2和COMPLETE目录；navigation只使用合法现有完整目录，适用于导航已改变进度后的收窄补验。这里使用固定本地HTTP及独立remote协议输入，**不证明真实GitHub认证、跨设备远端传输或实体硬件行为**。保留精确R8成员是为了此签名外部验收ABI；后续内部重命名/签名变化须同步夹具和保留规则，并在真实正式产物上补验，不能单靠mapping中符号存在。

实施者结构化回执已交付。自建服务PID37016已按PID/commandLine核对停止，只移除5560本任务tcp18464映射；instrumentation/Gradle均已终态，无host运行PID。保留私有AVD/userdata/helper/正式44，未操作5586。服务停止后虚构图源离线，这是测试夹具边界，不将其当作正常用户图源失败。

本批AGENTS、外部ABI/夹具、Android版本44及本报告一起提交；Mac仅暂存本轮2026-10-05记录，原用户两个段落保持未提交。AppVersion用户草稿70和前序报告按原字节保护。本轮约定的Android同步稀疏、目录成败、缓存及前后章正式模拟器缺口已由上述组合补验关闭，不表示P11–P15所有契约在新APK逐项重跑；Windows/macOS原生搜索/焦点及可见双页仍待实际界面结果，因此HP02保持未勾选、不执行finalParityAudit。本轮零完整模块/全量重跑，一次正式Android候选；旧完整矩阵与相关补验仍为组合证据，不宣称最终代码重新全量通过。


## 2026-10-09：Windows 原生搜索补验中断证据

复用正式75 EXE与任务专有 hp02-remaining-win-single profile；前轮实例已退出，本轮 launcher18632/runtime11260，HTTP58971/JMX58972，实际进程路径和参数一致。只读 fixture 为 COMPLETE/3章/1条历史，未重新播种。Computer Use 重新枚举并激活精确候选，实际截图确认书架→历史→搜索入口，搜索编辑区点击后有可见 caret。

`sky.type_text` 输入 abc 后出现原生错误提示 `cannot open system clipboard`，文本未出现，不计搜索输入通过。对当前任务窗口发送 Return 后，截图返回 `no screenshot targets found`；有界重新枚举已无候选窗口，独立进程与58971监听检查均为空。应用已退出，具体退出原因尚未诊断，不能据此归因产品搜索实现或工具。停止后续输入，未运行全量、未重建、未更改产品代码。按当前 TEST_GUIDE 的 Computer Use 收尾规则结束根轮次，后续先离线调查再安排原生补验；HP02保持未勾选。本节为未完成验收的现场记录，尚未形成最终验收提交。


## 2026-10-09：Windows 正式75用户手动验收通过

交付并只读确认存在的正式产物为 [Mihon Desktop.exe](<D:/Codex/worktrees/f235/hp02-history-candidate/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.75.05fdb81-unpacked/Mihon Desktop.exe>)。用户在收到该路径及以下四项清单后明确回复“你列出的验收条目全部成功验收完毕”。来源为用户手动验收，不是 Computer Use 自动通过；用户本轮没有另报 profile、阅读模式枚举、系统输入法或窗口尺寸，不补造这些环境事实。

| 实际给出的操作清单 | 用户反馈 | 关闭的Windows缺口 |
| --- | --- | --- |
| 历史→搜索输入abc→光标移中间再输入→字符顺序正确；清空、关闭搜索正常 | 全部通过 | 原生搜索编辑及清空/关闭，P01/P02相关剩余项 |
| 点击封面→详情→返回→搜索与列表位置保留 | 全部通过 | 封面导航与返回状态，P03/P04相关剩余项 |
| 打开删除弹窗→Tab/Shift+Tab切换→Escape取消→历史记录保留 | 全部通过 | 弹窗键盘与取消路径，P16/P17相关剩余项；未要求实际删除 |
| 阅读器开启双页→从历史续读→恢复的目标页实际可见 | 全部通过 | 双页目标可见；模式名称未单独报告，旧AUTO断言失败保留原记录 |

本反馈关闭Windows上述原生缺口；跨平台不合并判定，macOS相应原生项仍待实际证据。当前没有新增产品代码或测试/构建需求，不重复Windows输入或全量矩阵。用户手动通过不解释此前剪贴板提示或退出原因，也不将这两个现象描述为产品bug已修复。报告与HP02最终批次一并提交，不单独创建record-evidence状态提交。


## 2026-10-09：Mac原生结果观测边界及用户接管

原实施者复用当前统一保护，`mbp-lan`实际可达macOS14.8.4；精确既有app75/runtime55981/open包装器55980、mac-75-single及58981/58982启动参数确认。SessionGuard对自然息屏做单次8秒亮屏，恢复为awake、锁定字段缺失、前台55981，nativePermission=true；目标window18366为on-screen1024×768。AXTrusted=true，AXFocusedWindow及AXFocusedUIElement查询error0、owner55981。没有输入密码或更改系统设置。

实际`CGPreflightScreenCaptureAccess=false`，不执行截图或请求权限；没有另一条已授权的Mac精确窗口画面观测链。上述前置成功不能替代搜索编辑、弹层键盘/回焦或双页可见结果，HTTP独立history controller也不能作为原生结果。当前统一CLI仅sync-main/新profile且依赖旧75没有的syncUI；新历史场景应复用共同保护，不把此场景能力差异解释为锁屏或SSH失败。本轮没有Mac原生事件、产品改动、构建或测试重跑。

用户明确选择“可以，我手动验收 Mac”。停止后续自动输入、截图和激活；临时wake helper已由SessionGuard.close清理，无新增常驻进程。保留既有 [正式Mihon Desktop.app](</Users/altair/Library/Caches/mihon-history-parity-20261004-f235/app-75/Mihon Desktop.app>) 给用户执行与Windows相同的四项清单，结果尚未收到。若需打开，在Mac终端使用`open -a`该精确路径，不加`-n`，避免新开重复实例。HP02仍未勾选，报告及本轮Mac经验维护待最终验收批次提交。


## 2026-10-09：Mac手动通过与HP02最终收口

在用户选择Mac手动验收、获知复用同正式候选及与Windows相同四项清单后，用户明确回复“验收通过”。本次记录为macOS正式75的用户手动通过：搜索abc及中间编辑、清空/关闭，封面详情返回保查询/位置，删除弹窗Tab/Shift+Tab及Escape取消保历史，双页续读目标实际可见。没有另报模式枚举、输入法、显示尺寸或新增截图，不补造这些事实；先前自动检查只证明前置，不追溯改记为原生通过。

| 平台与正式产物 | 本轮最终证据与范围 | 结论 |
| --- | --- | --- |
| [Windows75 EXE](<D:/Codex/worktrees/f235/hp02-history-candidate/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.75.05fdb81-unpacked/Mihon Desktop.exe>) | 构建日志Final unpacked EXE实际路径；fresh9观测、同profile冷启动缓存1观测；2026-10-09用户四项原生手动反馈 | 已通过计划要求的相关运行及剩余原生项；保留自动剪贴板失败，不声称其原因已修复 |
| [macOS75 app](</Users/altair/Library/Caches/mihon-history-parity-20261004-f235/app-75/Mihon Desktop.app>) | 项目build-only正式构建；fresh9观测、同profile冷启动缓存1观测；统一保护息屏恢复/身份；2026-10-09用户同四项手动反馈 | 已通过计划要求的相关运行及剩余原生项；外部录屏链权限不足保留，不新增权限要求 |
| [Android44 APK](<D:/Codex/worktrees/f235/hp02-history-candidate/app/artifacts/android/0.19.4-aex.26-vc44-05fdb81a6a-release/Mihon-Fork-0.19.4-aex.26-vc44-release-universal.apk>) | 连续正式证书、非debug、R8/资源收缩，统一candidate/verify；私有API36模拟器5560实际生产History/Reader/目录成败/缓存/前后章和真实IME/重复分类路径证据 | 已完成对应模拟器可覆盖验收；未操作用户5586或实体设备，不声明硬件专项通过 |

P01–P18索引继续指向已提交production/共享契约和平台接线测试，正式运行与用户结果补齐对应原生边界。数据完整性、共享协议/DI、目录事务及发布身份独立审查已在HP01/Android限定复审记录完成；本轮仅核对最终产物、有效证据和局部状态，不创建额外产品能力、迁移或复审轮次。

保留边界：目录准备是Reader挂载后的限定后台扩展，当前页面不等待远端目录，失败不污染当前页面；已读历史按官方规则续下一章，无下一章给出既定反馈；删除历史不删除作品/章节/下载或重置阅读状态；重复迁移只有显式确认才执行。AUTO组较小current索引的旧助手断言失败仍保留，其目标可见边界由用户双页结果补齐，不将旧失败改写成自动通过。

用户选择手动接管后，无自动输入/截图/激活；Mac既有候选保留供体验，自建临时wake helper已释放，Windows失败实例已退出，不结束用户自行启动的应用。受保护AppVersion草稿70、前序修复报告和Mac原有两段未提交内容保留；仅暂存本次新增Mac经验行，最终提交不混入用户改动。


### 历史manifest局部维护边界

仅维护capability64的history动作，不推进其他63项或非Reader父计划。原actionInventory的三方provenance与sourceEntryIds是冻结在95b82fc等revision的基线来源，继续保留原定位及上下文hash；不把新实现回填为旧版本事实。十项当前实现状态与入口/反馈按本批真实行为更新，并以currentDesktopEvidence分别记录本次production的ENTRY/EFFECT/FEEDBACK、当前提交、路径及定位，与聚合验收证据关联。该字段用于区分当前实施与冻结来源，不取代原source graph，也不充当行为测试；状态判断来自已执行的production行为/wiring测试和正式运行及用户验收。元数据核验只检查范围、结构和定位一致性，不被描述为新一轮产品测试。后续源码改变这些行为时必须重新评估证据，不能仅重算hash就保持已验状态。


最终局部元数据核验：十项PRESENT/NONE、30个当前production定位与hash、冻结provenance/trackedUpstream和cap64其余字段保持原样、其他63项完全不变，主代理独立核验通过。最终五文件变更超过400行，主要为十动作各三类必要证据记录；内聚于一个已验历史能力，不拆为机械任务。未运行Gradle/full/finalParityAudit，不将定位扫描作行为通过证据。UTF-8及git diff --check通过，最终同批提交。
