# 历史页上游对齐实施与验收证据

唯一实施计划：[history upstream parity roadmap](../roadmap/2026-10-04-history-upstream-parity-roadmap.md)。固定契约：[设计 P01–P18](../2026-10-04-history-upstream-parity-design.md)。本文只记录实际证据，未运行的测试与原生验收不视为通过。

## 当前状态

HP01 A–E 实现、唯一一轮独立审查、受影响批次及限定格式/元数据检查完成，随本批提交勾选；HP02 尚未进入。稳定批次八个目标共 348 项，失败/错误/跳过均为 0。共享数据接口、两端实际 Reader 消费、共同历史状态与请求资格、排序、收藏追踪及焦点契约已核验；正式三平台验收仍待 HP02。下文保留各阶段的有效红绿、夹具失败和明确撤回，历史段落中的“待完成”不替代本节当前结论。

## 执行基线与归属

- 授权：2026-10-04 用户要求实现该 roadmap；本次从 HP01 开始，HP02 尚未进入。
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
