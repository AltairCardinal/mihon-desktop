# 历史续读与章节目录修复：聚合证据（2026-10-01）

本报告唯一维护 HR01 实现、审查与 HR02 交付证据。[设计与冻结 H01–H15](../2026-10-01-history-reader-chapter-context-repair-design.md)；[当前 roadmap](../roadmap/2026-10-01-history-reader-chapter-context-repair-roadmap.md)。本节由实施者维护；主代理补独立审查、提交和正式平台交付。HR01 整体独立审查及本轮唯一修复复审通过；实现、相关验证与 checkoff 随本功能提交完成。HR02 尚未执行，保持未勾选。

## HR01：实施范围与当前边界

- 实施基线 `5f13b080547ab2fcdcd6deb7ecdfa463e2765421`，任务 worktree `D:/Codex/worktrees/f235/mihon`；原设计规划基线保留。工作开始时干净，未操作用户原 `D:/Shell/Github/...` checkout、日常数据库或运行实例。
- 一名实施子代理串行 A→B→C；主代理只读核对接口、环境与证据，整体独立审查一轮已指出详情预检失败反馈阻塞，其余核心路径未发现新增阻塞；本轮唯一修复复审已通过。无新增代理，无提前提交或任务勾选。
- A 复用源更新、作品/章节仓库、归档 bootstrap 和现有表，新增只读精确目录观测接口及 Desktop 非删除事务 adapter；B 复用历史 model、Voyager、共享章节筛选/导航和 mapper；C 复用 production MangaDex HTTP/parser、两个文件 SQLite、真实 sync journal/inbox/projector、已挂载 reader runtime 与既有 Test Mode 动作。
- 实际改动 44 个文件（21 production Kotlin、17 test Kotlin、2 字符串 XML、4 维护文档），超过预估文件/行数，仍内聚于同一历史续读能力：窄读契约、共享落库、导航/DI、SQLite/HTTP/Compose/同步行为验证与必要维护文档共同可验收。未改 schema/migration、同步协议、Android 产品 UI、下载删除或文件更名，不创建独立验收系统，不改 parity capability 状态。HI-01 宏观 GAP 不因本修复改为完成。
- 网络在事务外，归档 bootstrap 也在事务外准备。短事务先验证固定作品和确切 source-work 绑定，再写必要作品元数据/creator index、章节与 COMPLETE；异绑定、作品删除、部分写入均回滚。发现行 manga_id=null 允许真实关联，非空异 ID 拒绝。
- 同作品历史/详情复用刷新 flight；网络共享获取详情，但历史默认不持久既有标题，保护实际下载目录身份。手动意图允许提升详情更新：短事务尚未决定时一起提交，决定后复用已抓详情作另一短事务，目录仍只请求/合并一次。详情入口同时考虑 !initialized，COMPLETE 不替代详情初始化。
- 返回时以真实 production tracker 的最新续读结果核对，不硬编码最后访问章或页；正常实际阅读可以生成用户进度。纯目录补载前后 outgoing 用户事件不增加，已打开会话不被后来同步候选跳转。

## 红绿重构与受影响验证

所有日志位于 worktree 忽略目录 `.gradle-coordinator/`，不用日志副本或逐任务快照。Gradle 由实施者唯一协调者串行执行。每次验证设置 UTF-8/PYTHONDONTWRITEBYTECODE/ErrorActionPreference=Stop，SDK 为 `D:/Android/Sdk`，本地 HTTP 绕过代理。

| 行为范围 | 正确红证据 | 绿/重构证据 |
|---|---|---|
| A 稀疏目录、顺序、真实无作者观测及回滚 | `history-catalog-red-confirm`：19 项/5 失败，包含应抛异常但实际未抛、未补目录/顺序/观测 | `history-catalog-green`：19 项通过；`history-catalog-refactor` 通过 |
| B 真实历史 Compose 点击及完整 refs/index | `history-entry-red`：8 项/1 失败，真实 push 仍为空邻接 refs | `history-entry-green-confirm` 通过 |
| B 连点/失效请求、导航最多一次 | `history-entry-lifecycle-red-confirm`：4 项/1 失败，重复完成请求 | `history-entry-refactor-confirm` 通过；历史回执曾以声明估计数量，实际完整 XML 已被后续覆盖，不沿用旧计数。当时 data 每平台仅 1 项被发现（见下方纠正） |
| C Test Mode 实际历史请求 mapper | `history-sync-red`：3 项/1 失败，HTTP 打开仍丢目录/production tracker 默认 | 后续 mounted reader 与同步集成通过，见最终回归 |
| H07 空目录错误语义 | `history-catalog-http-red-stable`：空目录被误分为 Storage | `history-catalog-http-green`：源响应矩阵、重试和关联行为通过 |
| H11 真实下载目录标题身份 | `history-download-identity-red-confirm`：旧标题被详情覆盖，真实 download resolver 失去已下载关联 | `history-catalog-http-green`：保留标题及真实路径/PNG/manager 通过；手动详情刷新仍更新 |
| 详情 COMPLETE+initialized=false；H10 加载期间新同步历史 | `history-details-complete-red-confirm`：6 项/2 失败，首次详情被跳过、历史视图最新行变化被误作删除 | `history-sync-policy-refactor` 与 `history-c-integration-final` 通过 |
| H08/H09 事务内异作品绑定 | `history-identity-transaction-red`：11 项/2 失败；`history-identity-favorite-red`：8 项/1 失败，真实已收藏刷新没有拒绝异绑定 | `history-identity-reader-green` 的事务、HTTP、无障碍项通过，阅读器重入夹具另有时序失败，最终回归确认 |

早期 `history-catalog-red` 的回滚项为 DI 夹具缺失 SqlDriver，不能作 H09 正确红；正确回滚红为 `history-catalog-red-confirm`。其他编译/fixture 失败、HTTP 并发响应排队错误、gate 委托绕过导致的等待及控件切章时隐藏均保留原日志，不算行为红。后续修复只复跑对应 focused 范围。`history-c-translation-session-confirm` 的 27 项/1 失败为 reader 控件/会话等待；`history-identity-reader-green` 的重入目标硬编码失败已改为真实最新持久化续读目标。

格式：Desktop 原来不注册 spotless 任务，直接命令 `history-reader-format` 失败。早期 init 的绝对 glob 未实际选中 Desktop 文件，`history-reader-format-fixed` 的 PASSED 不作为 Desktop 格式证据。最终忽略目录内一次性 `history-reader-format.init.gradle` 在 Desktop 配置后应用现有 lint 插件，使用明确 FileCollection，日志逐项列出本任务 **31 个 Desktop Kotlin 文件**。`history-reader-format-actual-confirm` 真实格式通过；随后 `history-reader-focused-refactor` 的同范围格式检查通过。data/domain/i18n 用既有任务和全部规则。Desktop 原始 DI、HTTP、详情、reader 等文件已有多处超长行而未注册 lint，本次临时 Desktop 格式仅关闭 max-line-length，保留 ktlint 1.8.0 其他规则、导入、缩进、尾空白和换行；不把这一结果称为原始全部规则通过。中间 init 生命周期、重复 step、wildcard 与超长行失败保留，未扩展产品构建配置或治理无关模块。

**一次受影响回归**：`history-reader-affected-regression`：Desktop **158 项/1 失败**，其余 157 项通过；data 当时 JVM/Android 各实际 1 项通过（先前 2 项说法不正确）。唯一失败为既有 `DesktopSyncWiringTest`：source=42 不存在、目录无 COMPLETE，旧断言仍期待直接进入。修正为同一 production model 首先断言 null+SOURCE_UNAVAILABLE，再显式 useExisting=true，保留页 2 与 resume heads 强断言。真实格式后只复跑 A/B/C 与受影响 wiring 的 **16 类/94 项，全部通过**（`history-reader-focused-refactor`），没有重复全部 158 或 full。命令原来限定以下 21 个 Desktop 类以及 data 的两个共享契约 wrapper；不是完整模块测试：

`SaveSourceMangaForDetailsTest`、`SourceChapterCatalogIntegrationTest`、`LibraryUpdateCheckerTest`、`MangaDetailSourceRefreshTest`、`HistoryScreenModelTest`、`HistoryPreparationLifecycleTest`、`HistoryReaderComposeIntegrationTest`、`SyncedHistoryReaderIntegrationTest`、`HistoryCatalogHttpIntegrationTest`、`HistoryAccessibilityIntegrationTest`、`HistoryNavigationPolicyIntegrationTest`、`DesktopDiWiringTest`、`DesktopSyncWiringTest`、`HistoryTestModeHttpTest`、`TestModeTimelineHydrationTest`、`TestHttpServerJsonTest`、`ReaderNavigatorTest`、`DesktopReaderRuntimeFactoryTest`、`ReaderProgressTrackerTest`、`ReaderProgressTrackerIntegrationTest`、`DesktopReaderChapterTransitionIntegrationTest`；data `JvmSourceWorkCatalogContractTest` 与 `AndroidSourceWorkCatalogContractTest`。

测试发现诊断：本批 Desktop 新增类的 @Test/实际 XML 对应为 SourceChapterCatalogIntegration 8/8、Accessibility 2/2、HTTP 3/3、NavigationPolicy 1/1、Lifecycle 4/4、ReaderCompose 1/1、SyncedReader 2/2；修改类 SaveSourceMangaForDetails 16/16、DI 25/25、HistoryScreenModel 7/7、DesktopSyncWiring 2/2、HistoryTestModeHttp 3/3，均无失败。只是发现数诊断，行为证据仍来自真实测试执行。

共享契约的精确身份测试原来表达式函数以 assertThrows 结束，推断返回 Exception，JUnit 未发现；过去“每平台 2 项”的描述纠正为 1 项，不能作精确身份覆盖证据。已追加真实读回不变断言，使返回 Unit；第一次修正替换未命中已格式化代码且造成换行格式失败，`history-reader-shared-contract-confirm` 不算通过。修正与 `history-reader-contract-format` 通过后，`history-reader-shared-contract-green` 已通过，实际 XML 确认 JVM **2 项**、Android **2 项**全部被发现并执行、0 失败，data 格式检查通过；正式证据只取实际 testcase 数。

Desktop UI README 的旧“稳定后完整模块”要求与项目 AGENTS 分层政策冲突，本次遵循 AGENTS：HR01 不执行未限定测试类的模块测试、full、finalParityAudit 或正式构建。最终完整组合与正式 EXE/.app 验收留 HR02。

## HR01 独立审查阻塞修复：详情预检与兼容入口

审查发现真实详情首次进入在安全源刷新之前裸调目录检查：异作品归档绑定、重复 URL 或数据库查询失败会逃出 LaunchedEffect，旧列表保留但没有既有 Failure/原因/重试。Browse/Search 的兼容 awaitListedForDetails 也暴露同一新增预检异常。本轮沿用原 owner、刷新状态和详情失败栏，不改作者决策、页面导航体系或全局 archive upsert 策略。

- 详情改用 prepareForDetails 的显式 SourceCallResult；固定 ID/source/url 重读失败发布既有 Failure(Storage)，身份错误显示既有身份冲突文案，其他本机查询显示存储失败。既有列表、作品及用户历史保持，手动刷新/Retry 仍走真实源和事务。
- 兼容 awaitListedForDetails 在已经获得本机作品后返回原 Listed 对象身份，检查失败携带 preparationError 并发布相同 Failure；needsRefresh=false 表示不在预检失败后自动刷新，不能视为目录成功或 COMPLETE。原 Browse/Search 可继续既有导航，由详情失败栏显示原因。取得作品 ID 之前原 listing 查询/创建失败没有被静默改为成功；取消异常在两个入口均继续传播。
- 手动刷新源请求前、成功后的本机目录查询通过既有存储错误转换，避免实际 SQLite 查询失败被 safeSourceCall 误分为 MalformedData；源 HTTP/解析分类未改。
- 文档澄清 history_read_existing 的 UI 入口仅失败且可降级时显示，而 Test Mode 显式动作无需先前 Failure、会跳过目录准备且不能证明完整性。roadmap 的正式构建模板改为实际脚本外层协调器包装；没有修改构建脚本。

红绿证据（均为 focused，不重复原 94/158 项）：

| key | 实际结果/正确原因 | workerPid / processPid |
|---|---|---|
| history-review-detail-red-confirm | 挂载 production MangaDetailScreen：1 项/1 失败；未发布 Failure，实际 uncaught=Source catalogue manga identity conflict | 61512 / 41916 |
| history-review-detail-query-red | 9 项/2 失败；上述详情失败及真实 SQLite 查询错误被误分为 MalformedData | 62032 / 54296 |
| history-review-detail-green | 4 类实际 28 项/0 失败：真实详情原因/旧章保留、手动刷新失败、修正身份后真实 Retry 成功、本机查询错误分类与取消 | 48144 / 62116 |
| history-review-detail-refactor | 同 28 项及 5 文件真实格式检查通过；随后兼容入口追加范围来自同一复审 | 50244 / 44596 |
| history-review-listed-red | 真实 SQLite 的兼容 awaitListedForDetails：9 项/1 失败，异绑定异常逃逸而非返回原 ID+Failure | 61464 / 57248 |
| history-review-listed-green | 4 类实际 29 项/0 失败；兼容方法返回固定 ID、preparationError、Failure且原章/外来绑定不变，取消继续传播 | 50200 / 54784 |
| history-review-listed-format | 明确 FileCollection 的 5 文件实际格式化通过，日志打印每个相对路径 | 57092 / 23712 |
| history-review-listed-refactor | 同 4 类实际 29 项/0 失败与 5 文件格式检查通过，26 秒 | 60864 / 39828 |

挂载详情测试使用真实临时 SQLite、production DI、MangaDex source/parser 与 MockWebServer；初次失败没有网络请求，手动刷新仍失败时不改变用户数据，真实按钮 Retry 在修正 archive 身份后得到三章并清除错误。新增 MangaDetailPreparationIntegrationTest 实际发现 1 项；本轮 SaveSourceMangaForDetailsTest 17 项、SourceChapterCatalogIntegrationTest 9 项、MangaDetailSourceRefreshTest 2 项，合计实际 29 项。测试未扫描源码或复制合并逻辑，未运行完整模块、全量或正式构建。

## H01–H15 固定契约证据映射

下表记录自动化覆盖；HR01 整体审查与修复影响路径复审已通过，正式平台尚未验收。测试均执行 production 行为和 wiring，没有源码扫描或测试内复制合并算法。

| ID | 实际测试/事件与核对 | 平台交付边界 |
|---|---|---|
| H01 | `HistoryReaderComposeIntegrationTest` 真实点击/pushed Screen 类型/三章中间索引；`SyncedHistoryReaderIntegrationTest` 在完整目录上实际前后按钮、Home→Left、End→Right，核对 context 与 active session ID、Loaded、四页和两端无假邻章 | HR02 再验真实平台输入 |
| H02 | 两个文件 SQLite 真实 sender journal/outbox→receiver inbox/projector 产生稀疏中间章；真实源补三章，原 ID、historyId、初始页/快照、纯补载 outgoing 不变，真实 PNG 请求与 mounted session | 正式 runtime 夹具已接入，未在 EXE/.app 执行 |
| H03 | `MangaDetailPreparationIntegrationTest` 挂载详情首次身份失败、旧章/用户数据保持、手动失败及修正后 Retry；`SaveSourceMangaForDetailsTest` 与 SQLite 集成：initialized true/false、旧 COMPLETE/首取不足、匹配旧顺序修复、原用户状态保留 | 旧库无迁移，不要求清空 |
| H04 | 单章 COMPLETE 关闭/重开缓存验证；共享 `ReaderNavigatorTest` 单章自然无前后目标 | 正式冷启动待验 |
| H05 | `SourceChapterCatalogIntegrationTest` 无作者未收藏真实 source-work COMPLETE、精确 mangaId、detailsFetchedAt=null；关闭/重开有效；data JVM/Android 读契约 | 正式冷启动待验 |
| H06 | `SaveSourceMangaForDetailsTest` 同作品 flight、取消一使用者不取消 owner、异作品并发独立；HTTP gated history+manual 仅一目录请求并保持详情刷新语义 | runtime 并发可用 |
| H07 | `HistoryCatalogHttpIntegrationTest` 原始 HTTP 403/429/500、离线 socket、超时、空/缺失、畸形 parser、真实重试，不变章/记录且不虚假 COMPLETE；真实下载解析保留；UI 错误/重试/显式已有章反馈 | HR02 覆盖关键成功/失败路径 |
| H08 | 挂载详情身份失败可见且可重试、兼容 listing 返回原 ID 与显式 Failure、本机查询 Storage；真实源响应身份变化、等待期间作品删除/观测改绑、已收藏 direct 改绑、SQLite 查询失败；model 源缺失/目标消失拒绝假索引，显式已有目标降级 | 不猜其他作品；消失目标降级不构造远端邻章 |
| H09 | SQLite trigger 章节 add/update（原顺序及首取已经正确）、归档 COMPLETE 失败；吞 addAll 异常读回失败；作品详情和新建作品整体回滚，重试恢复 | 同事务真实 DI/handler，不靠 fake 回滚 |
| H10 | loading gate 期间真实同步 advance，最终章/页/快照一致；元数据补载保留新用户状态；mounted reader 打开后同步不跳当前章/基线，重入与真实 resume 一致 | HR02 原生会话再验 |
| H11 | ID/已读/书签/页、raw 历史时长、viewerFlags、分类/收藏/笔记保留；实际 download resolver/provider/manager 的目录/PNG 可读；真实清除后补载不复活；正常阅读 tracker 回归 | 无下载更名/搬移；手动详情刷新保留原有语义 |
| H12 | `HistoryNavigationPolicyIntegrationTest` SQLite scanlator 排除、当前目标恢复、skipRead/skipFiltered/skipDuplicate 与共享 ReaderNavigator、external 目标拒绝、筛选无邻章自然边界 | 不扩大 Android UI |
| H13 | lifecycle：连点只一次、cancel/remove/clear/dispose 迟到不 push；Compose cancel/Escape、返回 query、非零 scroll、resume 按钮焦点和无重复反馈 | 平台原生焦点待验 |
| H14 | `HistoryAccessibilityIntegrationTest` 真实 locale provider 的 en/zh-CN 固定文案断言、浅/深 420×900 fontScale=1.8，Tab/Enter/Escape；loading 中宽度/主题/字号变化不重复请求，取消后切真实语言无 late push | 两张离屏候选；EXE/.app 原生事件待验 |
| H15 | `HistoryTestModeHttpTest` 相同 mapper 与 production tracker 默认；真实两库/HTTP/Compose TestNavigationController 挂载 actual runtime，既有 HTTP 前后/关闭动作读取 active session，不以模拟 hasNext/Prev 验收 | 正式 EXE/.app 仍待 HR02 |

## 离屏候选与语言事实

环境：Windows JVM `ImageComposeScene`，420×900、Density=1、fontScale=1.8；真实 MaterialTheme 浅/深色、`DesktopAppPreferences.appLanguage` 与 `DesktopLocaleAdapter.Provide`。搜索 keyboard、列表非零滚动，展示 source unavailable 反馈及重试/降级/取消。

- [浅色英语候选](../../app-desktop/build/history-reader-candidates/light-en-failure.png)
- [深色中文候选](../../app-desktop/build/history-reader-candidates/dark-zh-failure.png)

仅过程产物，位于忽略的 build 目录，不入 Git。第一版中文候选实际为英语，原因是只设 Locale.setDefault，但真实 appLanguage 为空，provider 重设系统英语；旧图不能作中文证据。已改真实 appLanguage=zh-CN +固定中文“源不可用/重试加载章节/使用已有章节阅读”断言，并在布局展开后重新保存。候选图不代替实际业务事件或原生平台验收。

## HR02 固定运行入口与待验

夹具路由和实际 session 字段详见[API_REFERENCE](../automation/API_REFERENCE.md)。正式运行在新专用 `--test-profile`：seed→history_select 或历史按钮→三个实际章节/四页；fixture/state 核对 COMPLETE、稳定 ID 和目录一次请求。重启同 profile 可验缓存；新 profile 的 mode=http500/empty/missing_target 后 select 可验反馈、retry/read_existing；advance 可验加载期间与已打开会话边界。history_select/retry/read_existing 使用 index 字符串，history_cancel 取消导航。

`/test/reader/state` 的 production、context currentChapterId、实际 activeChapterId、loadState、chapterIds/index、initialPage/resumeHeadIds、真实页数/边界是关键观测；必须同时看到真实 pageCalls/imageCalls。实际阅读可以改变持久化 resume，不假定返回后仍为固定章/页。

未执行：HR02 完整 Android/Desktop 矩阵、Windows 正式 EXE、macOS 正式 .app、真实平台键盘/焦点/运行验收。没有正式产物可在本节交付。主代理平台只读检查 Mac macOS 14.8.4 x86_64 图形会话锁定，原生操作条件待用户值守；不影响 HR01 离屏行为验证。不将上述未执行项写为通过。

## 主代理独立审查与最终交付

HR01 文档 UTF-8、两份字符串 XML、本地文档链接、本报告引用日志存在性和 git diff --check 已核验通过；结构化实施回执通过 scripts/agent-handoff.py 验证，过程文件位于忽略目录 `.gradle-coordinator/hr01-handoff.json`。协调器当前无运行任务。

主代理完成整体独立审查，指出详情预检未捕获异常阻塞，其余核心路径未发现新增阻塞；本轮修复影响路径复审已通过。主代理已独立核对最终 44 文件写入边界、UTF-8/XML、diff、协调器命令与实际 XML：最终修复 4 类 29 项通过，先前受影响 focused 16 类 94 项与共享 JVM/Android 各 2 项证据有效。审查覆盖事务回滚、exact identity、下载身份、同步快照、过滤导航、生命周期、实际 mounted session/Test Mode wiring；详情预检及兼容 listing 阻塞已关闭，无剩余代码阻塞。HR01 checkoff 随本功能提交完成；提交 hash 及 HR02 全量/正式运行证据在最终交付节补入。冻结 H01–H15 保持不变。
