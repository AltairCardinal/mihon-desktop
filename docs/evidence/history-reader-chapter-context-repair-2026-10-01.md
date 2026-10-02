# 历史续读与章节目录修复：聚合证据（2026-10-01）

本报告唯一维护 HR01 实现、审查与 HR02 交付证据。[设计与冻结 H01–H15](../2026-10-01-history-reader-chapter-context-repair-design.md)；[当前 roadmap](../roadmap/2026-10-01-history-reader-chapter-context-repair-roadmap.md)。本节由实施者维护；主代理补独立审查、提交和正式平台交付。HR01 整体独立审查及本轮唯一修复复审通过；实现、相关验证与 checkoff 随本功能提交完成。HR01 提交为 `c1e13c47283a2c7d7b12621a3df4507a2beb3042`。HR02 首次 Desktop full 已失败，已授权必要 focused 修复及格式验证通过，作者独立问题随后获授权实施，focused 修复、格式验证与追加整体审查通过，第二次 full 与 Mac focused 仍有具体阻塞，作者后续修复继续；HR02 保持未勾选。

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

## HR02 首次 Desktop full 与必要 focused 修复（2026-10-01）

HR01 提交后，主代理执行唯一已授权 Desktop full：`history-reader-final-desktop`，实际 **3265 tests / 58 failures / 3 skipped**，Gradle 8 分 59 秒（协调器生命周期 541 秒），worker/process 为 **51692 / 56904**。状态/日志与完整失败 trace 保留在忽略目录 `.gradle-coordinator/history-reader-final-desktop.json`、同名 `.log` 和 `history-reader-final-desktop-failures.json`，focused 覆盖 XML 后仍可核对原始 58 项，不能把首次 full 写成通过。

原始失败按实际保存记录为：迁移 1 类/9 项；下载 **6 类/46 项**（早期称 7 类已纠正）；历史 lifecycle 1 项；manifest 证据锚点 1 项；作者 mounted wiring 1 项。以下修复不重复 full、不构建、不提交；独立作者能力及额外复审/full 授权由主代理处理。

- **迁移兼容已证实回归**：原 generic SaveSourceMangaForDetails.await 接受空章节并保存作品，HR01 把 generic 调用也收紧成 NoChaptersException，阻断实际单项/批量迁移的合法无章目标。最小修复让 generic 空列表只在原短事务内保存必要元数据，保留已有章节及目录观测，不执行 merge、不写 COMPLETE。非空仍严格合并；真实 fetchFromSource 与 LibraryUpdateChecker 网络入口继续在调用前 validate，空源响应仍明确失败。没有补假章节或改迁移 flags/分类/笔记策略。
- **本批夹具残留偏好已由实际 worker 证实**：SQLite/DI context.closeAndJoin 后 removeNode，但全局 Injekt 的 downloadPreferences 仍引用该已删除节点。受控探针随后启动生产 DesktopDownloadManager，正确红捕获 `Node has been removed`；full 中 17 个 failure trace 直接含该错误，其余下载超时/断言不能仅凭时间相邻全部归为同因。原样独立 focused 下载 73 项及历史 lifecycle 4 项通过，说明 full 上下文是必要诊断维度。
- 仅 7 个本批 DI 集成夹具改为隔离内存后端，复用既有测试 helper 工厂返回**真实 DesktopPreferenceStore**；AbstractPreferences 只替代本机 registry 存储，字符串集合、子节点、序列化与真实 appLanguage/locale provider 继续由 production 实现消费。没有改公共 InMemoryPreferenceStore、生产 Download 或旧下载测试；所有历史、HTTP/parser、两库同步、中文固定断言和真实页面事件保持。
- 尝试公共 InMemoryPreferenceStore 的 `history-final-fixture-green` 因 getStringSet 尚为 TODO 导致 21 个 DI 初始化 NotImplementedError，另有下一处证据锚点失败，共 99/22；这是诊断失败，**不作业务绿证据**。改真实 DesktopPreferenceStore 的完整内存后端后，探针实际下载完成（队列为空且 HTTP 1 请求）、相关组合及中英文 UI 断言通过。
- **证据仅机械重定位**：manifest 现有 roleEvidence 中 6 个格式/行偏移锚点（ID 11 后继续发现 ID 22 等）按旧基线精确行与当前实际 symbol 重定位；JSON 比较确认仅这 6 个 line 字段改变，任何 capability/status/actionInventory/HI-01 GAP 均未改。此源码定位是维护现有证据，不能作为产品行为验收。
- **作者首次 full 与独立复现事实（后续获授权修复见下节）**：原样独立 focused 精准复现 All 切回未恢复 Late Author 50，selected=true、scrollRange=100、仅头部卡片。现有测试已经分别对 scope 与目标文本做真实 render 轮询 5 秒；不能无证据把它说成过早断言或放宽 Late Author 50/非零滚动/返回期望。夹具 indexer 未启动，Preparing 文案来自 Idle，不能证明有 bootstrap 工作尚未结束。分开发布 scope/cards 可能影响 scroll 恢复仅为待证实推断；该次 History focused 修复未修改作者 production/test，当时独立能力修复等待明确授权。

| key | focused 实际结果 | worker / process |
|---|---|---|
| history-final-migration-red | 既有迁移 11 项/9 失败，新 production DI/SQLite 契约 1/1；正确 NoChaptersException 红 | 59256 / 54672 |
| history-final-migration-green | 6 类实际 52 项/0 失败；无章迁移不造 COMPLETE，既有章/历史/用户状态和观测保留，网络空响应严格失败 | 63872 / 59752 |
| history-final-runtime-diagnostic | 原样 8 类实际 78 项/1 失败；下载 73/0 + lifecycle 4/0，作者 1/1 | 18532 / 49888 |
| history-final-fixture-red | 探针实际 1/1，夹具关闭后生产 worker 明确 Node has been removed | 61412 / 58512 |
| history-final-fixture-green | 99/22，公共 InMemory TODO 初始化与后续证据行号失败，不作业务绿 | 64064 / 55160 |
| history-final-fixture-green-confirm | 15 个限定 target 实际 99 项/0 失败，生产 worker、SQLite/parser、实际 locale、旧下载与 lifecycle 通过 | 19044 / 64176 |
| history-final-repair-format | 明确 FileCollection 实际 9 个 Kotlin 文件格式化通过，沿用已披露 Desktop max-line-length 例外 | 33496 / 60888 |
| history-final-repair-refactor | 合并上述受影响范围，19 个限定 target 实际 138 项/0 失败与 9 文件格式检查通过；73 秒 | 61256 / 41512 |

本修复 11 个文件（1 production Kotlin、8 test Kotlin、1 JSON、唯一报告）属于同一首次 full 失败诊断闭环；超过估计文件数仅因消除 7 个使用同种失效偏好生命周期的本批 fixture，不增加产品下载/作者能力或独立验收系统。原 58 项失败记录保留，新的 focused 通过不替代额外 full、独立复审与正式平台验收；截至该次 History focused 修复交接，用户尚未批准作者独立能力实现、额外独立复审或再次 Desktop full；当时三项均未执行。其他完整 Android/domain/data/test-desktop/spotless 验证矩阵尚未执行，正式 Windows/macOS 产物未构建。

## HR02 固定运行入口与待验

夹具路由和实际 session 字段详见[API_REFERENCE](../automation/API_REFERENCE.md)。正式运行在新专用 `--test-profile`：seed→history_select 或历史按钮→三个实际章节/四页；fixture/state 核对 COMPLETE、稳定 ID 和目录一次请求。重启同 profile 可验缓存；新 profile 的 mode=http500/empty/missing_target 后 select 可验反馈、retry/read_existing；advance 可验加载期间与已打开会话边界。history_select/retry/read_existing 使用 index 字符串，history_cancel 取消导航。

`/test/reader/state` 的 production、context currentChapterId、实际 activeChapterId、loadState、chapterIds/index、initialPage/resumeHeadIds、真实页数/边界是关键观测；必须同时看到真实 pageCalls/imageCalls。实际阅读可以改变持久化 resume，不假定返回后仍为固定章/页。

尚未完成：HR02 完整 Android/Desktop 矩阵（首次 Desktop full 已执行但失败）；未执行 Windows 正式 EXE、macOS 正式 .app、真实平台键盘/焦点/运行验收。没有正式产物可在本节交付。主代理平台只读检查 Mac macOS 14.8.4 x86_64 图形会话锁定，原生操作条件待用户值守；不影响 HR01 离屏行为验证。不将上述未执行项写为通过。

## 主代理独立审查与最终交付

HR01 文档 UTF-8、两份字符串 XML、本地文档链接、本报告引用日志存在性和 git diff --check 已核验通过；结构化实施回执通过 scripts/agent-handoff.py 验证，过程文件位于忽略目录 `.gradle-coordinator/hr01-handoff.json`。该次 HR01 交接时协调器无运行任务。

主代理完成整体独立审查，指出详情预检未捕获异常阻塞，其余核心路径未发现新增阻塞；本轮修复影响路径复审已通过。主代理已独立核对最终 44 文件写入边界、UTF-8/XML、diff、协调器命令与实际 XML：最终修复 4 类 29 项通过，先前受影响 focused 16 类 94 项与共享 JVM/Android 各 2 项证据有效。审查覆盖事务回滚、exact identity、下载身份、同步快照、过滤导航、生命周期、实际 mounted session/Test Mode wiring；详情预检及兼容 listing 阻塞已关闭，无剩余代码阻塞。HR01 checkoff 随本功能提交完成；提交 hash 及 HR02 全量/正式运行证据在最终交付节补入。冻结 H01–H15 保持不变。

当前授权修复交接：UNCOMMITTED；9 个 Kotlin 明确格式目标、manifest 仅 6 个既有 line 字段、实际 XML 138 testcase、UTF-8/JSON/本地文档链接及 diff 检查已核对；协调器无 STARTING/RUNNING。作者原失败与首次 full 58 项原 trace 保留；主代理已完成获批的额外独立审查，完整复验与正式运行尚待执行。

## 后续授权：作者切换一致状态与滚动恢复小修复

用户随后明确批准作者页小范围 TDD 修复、额外独立复审 1 轮与 Desktop full 复验 1 次，无新代理。实施者只处理作者 focused 修复，原 History 11 文件/138 focused 证据保留；额外审查/full 由主代理在交接后执行，本段不把它们写成已通过。

UI 来源与固定契约：读取 Desktop UI README、desktop-reference 和 page-contracts 的适用约束。**SOURCE** 为当前 c1e13c4728 的 AuthorsRootScreenModel、关注/全部 FilterChip、按范围独立的双 LazyListState、稳定 creator ID 卡片、分页/搜索及 Voyager 详情返回；**PROJECT_POLICY** 为切换与详情返回恢复各自非零位置、新范围不能挂旧范围卡片、搜索/过滤/重试保持既有语义。作者根页有唯一顶栏与内容列表，原返回/焦点/导航链路不新增入口；加载/错误仍用现有反馈。不新建 HTML_ADAPTER、页面或视觉规范，不改颜色/尺寸/封面 token。UI README 旧完整模块句继续按项目分层政策解释，实施者只跑限定 focused。

新真实 SQLite/root StateFlow 测试观察到明确混合发布：先 `followedOnly=false + 上一关注范围 cards + loading=false`，后才清空 cards/进入 loading，再返回真实 All 数据。最小实现去掉 showTab 的提前独立 scope 更新，把 followedOnly 作为现有 requestPages 参数，与 query、cards 和 loading 在同一次状态更新中发布；generation、query、分页数量、旧结果取消、刷新保留旧卡片、错误/重试守卫保持。双 LazyListState 的保存、恢复与详情返回 wiring 未发现新的明确阻塞，没有修改 UI listState 或增加滚动重定位流程。

因果边界：原 full 与独立 focused 的 Late Author 50 恢复失败均保留；本轮状态正确红时原 mounted 用例却通过，说明原滚动失败有时序差异。已证实并修复的是混合 scope/cards 发布，不能把它称为原所有 UI 失败的唯一原因。绿与重构后原 mounted 用例通过，Late Author 19/50、详情返回、分页尾部、搜索/清除、重入与浅/深主题的原断言均未削弱，未加 sleep 或修改等待上限；正式 full/runtime 仍待主代理。

| key | 实际结果 | worker / process |
|---|---|---|
| authors-scroll-state-red | 3 项/1 失败；真实 SQLite 原子范围契约正确红，mounted 1/0；完整失败详情保留 authors-scroll-state-red-failures.json | 3512 / 58048 |
| authors-scroll-green | 3 类实际 8 项/0 失败：root 2、原 mounted 1、既有 root/刷新失败重试/归档筛选 wiring 5 | 57008 / 39012 |
| authors-scroll-format | 明确 FileCollection 实际 2 文件格式化通过，沿用已披露 Desktop max-line-length 例外 | 60764 / 60196 |
| authors-scroll-refactor | 同 3 类实际 8 项/0 失败与 2 文件格式检查通过；新测试整理 imports 和就地 SQLite helper | 58784 / 59808 |

作者新增写入仅 AuthorsScreenModels.kt、AuthorsScreenModelsTest.kt 与本唯一报告；原 AuthorCardProductionWiringTest/AuthorsProductionWiringTest 未改。格式包含常规 import/缩进整理；manifest roleEvidence 没有引用该 model 的行号（0 条），作者修复未进一步改 manifest。refactor 外层 run 曾过早返回 NOT_STARTED；随后 status 确认同一 worker/process 实际 RUNNING，仅 wait 同 key，未重复启动 Gradle，最终 PASSED。

作者稳定交接：UNCOMMITTED；实际 XML 8 testcase、两文件格式、UTF-8/diff 检查完成，协调器无 STARTING/RUNNING。主代理接管协调权并按已批准预算完成整体独立审查、分开功能提交及一次 Desktop full 复验；实施者未运行额外 full、正式构建、审查或 checkoff。

主代理追加独立审查（获批 1 轮）已通过：真实空迁移 metadata-only 与严格网络空目录校验分界、短事务/已有章与历史保留、7 夹具偏好生命周期和真实 worker、manifest JSON 仅 6 个 line 字段已独立核对；作者最终 production 全 diff、真实 SQLite StateFlow 红、未修改的 mounted 滚动/详情返回/搜索断言、generation/query/分页与双 LazyListState wiring 亦已核对。有效证据为 History 138 项/0 失败+9 文件格式，以及 Authors 8 项/0 失败+2 文件格式；没有剩余代码审查阻塞。原 58 项失败与作者时序差异保留，不将 focused 通过替代 full/runtime。实施回执已验证，协调权交回主代理，当前无运行 Gradle。

History 必要回归修复提交为 `865579f2c6`；作者独立能力及本轮授权/审查证据在后续功能提交中一并保存。HR02 尚未执行获批的完整复验或正式平台构建，保持未勾选。

## HR02 已批准 Desktop 完整复验与首次 macOS focused：仍有阻塞

History 回归修复提交 `865579f2c6`，作者原子范围状态修复提交 `af47156b285dcb85754242f768dc4d0190101f4e`。两项相关验证与追加独立审查通过、工作树干净后执行获批的一次 Desktop full 复验 `history-reader-final-desktop-recheck`：实际 XML **3268 tests / 1 failure / 0 errors / 3 skipped**，6 分 33 秒，worker/process **57756 / 52736**，FAILED。完整失败 trace 与实际计数保留 `.gradle-coordinator/history-reader-final-desktop-recheck-failures.json`；首次 58 项失败仍单独保留，不能把本轮写为完整通过。

本轮唯一失败在 `AdaptiveReaderViewportTest` 的 mounted settings 用例开始前，报告 `UncaughtExceptionsBeforeTest`。suppressed 链为真实 `NoSuchFileException`，迁移后台协程在 `DesktopBatchMigrationController.run` 处理 WaitingForUser 时调用 scheduler.pause，FileTaskCheckpointStore 移动 `background-tasks.json.<uuid>.tmp` 到同目录文件失败；路径属于 JUnit TempDir，full 完成后定点核对该目录已不存在。已观察到下一测试开始前残留协程错误；目录清理与后台写入竞态的具体来源仍待受控定位，不能只据 NoSuchFileException 断定删除时点。源码核对：DesktopBatchMigrationController.stop 取消并清空 jobs，未实现 awaitStopped，真实 DesktopAppRuntime.closeAndJoin 因此调用默认空等待；迁移 scope 独立于 runtime scope。该关停等待缺口已在源码确认，不能仅凭这一异常认定所有关停故障或本次 History production 引入了它。

同一提交经 bundle 进入干净独立 Mac 工作树 `/Users/altair/github/mihon-history-reader-20261001-f235`；macOS 14.8.4 x86_64，项目 JDK 21.0.10+7，Android 36 SDK 存在。`history-reader-mac-focused` 限定 6 类，`--max-workers=2 --no-parallel` 且 Gradle heap 2 GiB，不是完整模块；4 分 42 秒，worker/process **16658 / 16659**，实际 XML **19 tests / 1 failure / 0 errors**，FAILED。原始各类/trace 保存本机 `.gradle-coordinator/history-reader-mac-focused-results.json`，远端协调器日志保留。

其中真实 SourceChapterCatalog 11、History reader Compose 1、accessibility 2、两库 synced reader 2 共 **16 项**全部通过；Authors root state 2 项通过；未修改的 AuthorCardProductionWiringTest 1 项仍为 All 切回未恢复 Late Author 50，selected=true、scrollRange=100、仅头部卡片。再次证明原子 scope/cards 修复不足以关闭原非零滚动缺陷，不能以 Windows focused/本轮 full 中该项通过宣称跨平台作者 bug 已修复。原 Late Author 19/50 强断言保留，同一实施者继续已批准的 Authors 最小修复。

Windows/Mac 上述进程均已结束。未运行剩余 Android/domain/data/test-desktop/spotless 完整组合，未构建或启动任何正式 EXE/.app，未发送原生输入。用户随后明确回复“批准”，批准新增迁移关停修复范围、限定独立复审 1 轮与下一次 Desktop full 1 次（45–75 分钟、无新代理）；复用原实施者，作者剩余修复与迁移真实 runtime/文件测试串行交付，不改变迁移策略。相关验证、独立复审及提交通过后才运行获批的 full；HR02 继续未勾选。


## 作者布局恢复后续实施里程碑（待 Mac 复验）

- `SOURCE`：Mac原 mounted 场景连续两次 Late Author50 恢复失败；`authors-restore-mac-diagnostic.xml`/16行位置序列证明切回All前位置49/1/51、实际cards52均完整，挂回layout后才保存为0/0/1。VerticalScrollAxisRange不能作为分页数量证据。此前scope/cards原子发布是必要独立修复，未充分关闭这个布局恢复问题。
- `PROJECT_POLICY`：保留Followed/All各自非零位置及详情返回；重新激活Authors根页使用新model/saveable身份。最小实现按scope隔离LazyColumn实例，并使AuthorsRootScreen.key及两份rememberLazyListState身份消费既有activationToken；没有滚动补偿、下载/迁移策略或新作者UI能力。真实Screen实例新token仍同类名key的正确红为`authors-root-identity-red`（1/1，62128/63868）。
- 新mounted回归复用原SQL fixture及所有Late19/50、详情返回、搜索/分页/重入强断言；真实factory callOriginal仅观察挂载model，仓库闸门只控制实际SQLite查询时序，在实际空loading界面就绪后放行。Windows快速/空加载帧诊断均绿；Mac test-only首轮2/1仅卸挂前置未就绪，修正编排后2/0，均不得计作位置行为红。原Mac两个真实Late50红仍保留。
- 最小scope key首绿及加入列表activation key后，Windows原重入首行断言仍失败；显式Screen身份修复后`authors-activation-green`实际3类10/0通过（48440/58484，26秒）。`authors-layout-format`实际FileCollection匹配3个Kotlin文件通过（65460/49504，11秒）；`authors-layout-refactor`同3类10/0/0/0及这3文件spotlessCheck通过（55784/40372）。旧强断言没有增加超时或放宽目标。
- 稳定产物为AuthorsTab.kt、AuthorCardProductionWiringTest.kt、AuthorsScreenModelsTest.kt及本报告；现有parity manifest没有AuthorsTab.kt roleEvidence锚点，本轮未改manifest。最终UTF8/LF三源码补丁在忽略过程目录`authors-layout-final.patch`，正式提交由主代理负责。Windows无运行Gradle；主代理接续Mac相同3类10项focused，实际Mac通过前不宣称原恢复bug已关闭。迁移关停TDD随后按最新明确批准范围串行实施。


### Mac 作者最终候选 focused 与原提交对照

主代理将相同三源码最终补丁（SHA256 `c0aed79d15f9a51b0907ac430f131a032b8b4b8c224601abf47a58167e1f8cc0`）应用于干净 Mac `af47156b28`。调用误选了完整 AuthorsProductionWiringTest 类而非 Windows 的五个限定方法，实际 `authors-layout-mac-green` **27 tests / 2 failures**（65025/65026，35 秒）；只属于作者相关 focused，不是完整模块或获批 full。原 mounted 与空加载帧两项均通过，包括 Late19/50、详情返回与重入。失败两项是既有 root failure/retry 的 retryNode 多匹配、projection refresh/retry 初始卡片未可见；它们本就属于 Windows 五项限定 wiring，不能当成无关新增选择而省略。实际 XML 22/2 与 mounted 2/0 保存 `authors-layout-mac-green-wiring.xml`、`authors-layout-mac-green.xml`。

精确反向撤销本轮三源码补丁、核对 Mac 干净 `af47156b28` 后，`authors-wiring-mac-baseline` 仅运行上述两项：**2 tests / 2 failures**（68075/68126，19 秒），失败原因与候选一致；实际 XML 保存 `authors-wiring-mac-baseline.xml`。这证明本轮列表/Screen key 不是引入两项失败的必要条件；尚未确定根因。只读发现 Mac AppleLanguages 为 zh-Hans-CN，真实 factory 显式传首选文字形式，而两夹具只匹配六个参数（第七参数默认为 null），为待验证的窄夹具匹配原因。不得更改 production 语言或放宽重试/可见性断言换取通过。完整 Mac 相关 scope 尚未宣称通过。


## 最新批准范围实施交接：迁移关停等待与作者夹具协议

- **迁移关停SOURCE/边界**：原controller.stop仅取消并清空句柄，继承默认awaitStopped=Unit。真实runtime+真实FileTaskCheckpointStore的门控原子写用例证明closeAndJoin曾在实际写入未结束时返回；具体第二full临时目录删除由哪一个旧case触发仍未定位，不能把本用例当该case归因证据。修复按现有tracker模式，仅等待controller拥有的运行/暂停/替换worker；stopped禁止关停后新launch，LAZY先登记后启动，同ID完成回调以对象身份清理。没有等待/取消整个外传共享scope，没有改迁移选择、复制、恢复策略或持久化格式。
- **迁移RFG/验证**：`migration-shutdown-red`真实1/1（59128/54748），因runtime在文件写入阻塞时提前返回失败；`migration-shutdown-red-confirm`真实2/2（28344/39964），追加暂停/同ID替换worker用例在漏等暂停worker处失败。`migration-shutdown-green`controller6/0（48016/7152）。重构复用小runtime fixture；`migration-shutdown-format`实际2文件格式通过（56436/54252），`migration-shutdown-refactor`实际5类79/0/0/0及2文件spotless通过（63060/57780，37秒）：Controller6、DesktopAppRuntime35、LibraryBatchMigrationConfigScreen2、AdaptiveReaderViewport2、CapabilityContract34。Mac独立worktree相同稳定controller/test补丁6/0/0/0（80040/80041，主代理执行），XML已保留；与第二full实际原失败事实分开记录。
- **机器证据机械维护**：递归核对迁移controller路径，仅已有MG DESKTOP_CONSUMER class硬line由76→78；semantic locator沿用。删除line字段后JSON与HEAD完全一致，未改capability/status/MG或HI-01上游GAP。
- **作者Mac相关夹具SOURCE**：候选27项/2失败的两用例在精确撤销后的af基线仍2/2失败，属于原有fixture协议缺口。production factory按平台Locale显式传第七preferredDisplayScript，但两个mock只写六个matcher，隐式限定第七null；Mac中文Locale不匹配时引入额外projection错误，多重Retry或首次卡片超时。
- **夹具RFG**：新增中文Locale用例直接复用这两个真实factory/mounted重试场景，try/finally恢复全局Locale；`authors-locale-fixture-red`Windows正确1/1（63760/61256），同真实多重Retry失败。仅补齐两处第七any()后`authors-locale-fixture-green`原两项+中文复用共3/0（59216/49644）。不修改production locale/query，不减弱旧错误/卡片/重试断言；这不是新增作者能力。
- **最终作者验证**：`authors-locale-format`实际4个源码/测试文件格式通过（59176/45420）；`authors-locale-refactor`实际3类11/0/0/0及4文件spotless通过（55388/55376，19秒）：Root models3、原及新mounted2、限定Wiring6，精确case清单在忽略过程目录。原三源码补丁SHA未变，额外第四文件仅夹具协议/中文用例及整文件格式。此前新loading测试的基线绿仍不计作红，原Mac两次真实Late50红与导航身份正确红仍是原恢复修复依据。主代理随后执行Mac精确11项；Mac完整相关scope尚不能在本次交接写通过。
- **内聚性与风险**：本次最终9个工作树文件（含主代理维护roadmap）超过估算行数，主要来自实际AuthorsTab/既有长Wiring测试格式及真实生命周期门控用例；行为边界仍为作者范围/返回/重入与迁移关停两个独立功能批次。统一格式不改变旧业务断言；独立审查按功能收口，不按格式行数拆微任务。所有源码现在稳定、未提交，Windows无STARTING/RUNNING Gradle；主代理接续已批准一次限定审查、分批提交及一次Desktop full。正式产物、其他最终矩阵与runtime交付仍未执行。


### 本轮限定审查：迁移关停通过，作者快速恢复尚未闭合

主代理已独立核对迁移最终production diff及其实际DI消费者：UI依赖与DesktopAppRuntime接收同一controller单例；runtime.awaitClosed调用其awaitStopped，即使runtime未启动也会等待controller自己的任务；不join外部scope。LAZY先登记、stop后的launch抑制、暂停与取消时保留任务句柄、完成时按对象身份移除同ID槽位已核对。实际原子文件门控与暂停/替换门控的正确红、Windows限定79/0及Mac相同controller六项6/0 XML均有效；manifest只改既有class硬line76→78，递归删除line后状态完全一致。迁移部分没有剩余独立审查阻塞，随controller/test/manifest及必要文档功能提交，不勾选HR02，不提前full。

随后Mac `authors-locale-mac-refactor` 精确11项实际 **11 tests / 1 failure / 0 errors**（85919/85920，28秒）：root3/0、Wiring6/0，mounted2/1。第七参数修正已使两原Mac失败/重试fixture及中文复用全部通过；原快速mounted场景却再次在切回All的LateAuthor50断言失败，新gated空加载帧场景通过。三份真实XML保存 `authors-locale-mac-refactor-AuthorCardProductionWiringTest.xml`、`authors-locale-mac-refactor-AuthorsScreenModelsTest.xml`、`authors-locale-mac-refactor-AuthorsProductionWiringTest.xml`。说明此前单次mounted绿不足以关闭原快速恢复问题，scope key并非充分修复；不以放宽断言、增大sleep/超时或重复full换取通过。作者源码保持未提交，同一实施者继续原已授权范围内修复；本轮作者独立审查尚未签收。新增Desktop full额度未使用，原剩余完整矩阵及正式产物/native运行仍未执行。


### 作者显式 viewport 恢复：Windows 正确红绿，待 Mac 验证

Mac 11/1 的原快速 Late Author50 失败仍保留。新确定性 mounted 契约复用同一真实 SQLite/factory/root：Following 已挂载、inactive All LazyListState 初始位置为0时，通过生产 model 保存 All49/1/51，真实切 All 并确认实际52张卡片已经加载，然后要求实际 Late Author50 行可见。`authors-saved-viewport-red` 实际1/1，失败在“保存的非零 viewport 必须应用，不能停在初始0”断言；不是分页缺失或未就绪前置失败。红日志保留，后续focused会覆盖模块当前XML。它证明已有模型目标与Compose位置分离时缺少有效恢复；结合Mac实际52卡/位置49→0序列支持本次修复，仍不能断定特定LazyColumn内部缓存是全部失败的唯一原因。

`SOURCE` 仍为现有每范围模型位置、双LazyListState、分页和Voyager身份；`PROJECT_POLICY` 仍为实际Late19/50、详情返回、搜索重置、重新激活和随后的用户滚动。此前“没有滚动补偿”只是上个未闭合候选的实现事实，不是固定验收。最小修复在当前scope/query revision的加载完成后，仅一次把捕获的模型index/offset应用到当前列表；目录变短时限定到实际最后项。恢复前、加载中及非活动范围不保存临时layout位置，手动存位与活动观察器均再次核对当前model的scope/query revision，防止旧composition覆盖搜索清零。effect不以cards/loading/后台refresh为key，后续分页或重试不能重放旧目标；原两份列表、scope实例key及activation导航身份保持。

原快速和gated用例的Late19/50、详情返回、搜索/清除、分页、重入强断言保持；没有扩大等待上限或增加sleep。新确定性用例随后执行真实ScrollToIndex(0)，调用原model.retry并断言实际顶部和保存位置仍为0、不回放49的恢复目标。两处第七matcher/中文Locale真实重试用例保持，production locale/query及迁移关停已提交代码未改。

| key | 实际结果 | worker / process |
|---|---|---|
| authors-saved-viewport-red | 新实际mounted目录52但viewport0，正确1/1；23秒 | 57572 / 50720 |
| authors-viewport-green | 原快速、gated空帧、新确定性恢复3/0 | 16076 / 55352 |
| authors-viewport-format | FileCollection实际4个作者源码/测试文件格式通过 | 63472 / 59404 |
| authors-viewport-refactor | Root3、mounted3、限定Wiring6共12/0/0/0，4文件spotlessCheck；43秒 | 58608 / 46740 |

实际testcase清单保留`authors-viewport-refactor-results.json`。相对Mac已应用f2bfd作者四文件补丁的两文件delta为`authors-viewport-delta.patch`，UTF-8/LF、CR字节0、10149B、SHA256 `4b4e645ba1883e9c67fbffe93dc2ece2849af0df2e7464c282119912a77029a4`，当前源码反向check通过；完整四文件补丁另保留，均为忽略的过程产物，不是正式交付。当前作者四文件内容差异及较大既有格式仍属于同一作者恢复/相关fixture功能批次，未增加独立UI能力或改变manifest状态。迁移已提交`07bb4e22eaff`并保持不变。Windows无运行Gradle；作者仍未提交、Mac相同12项及本轮限定独立审查待主代理完成，本段不宣称跨平台bug关闭或full通过。


### 作者最终限定独立审查与两平台恢复验证通过

Mac `authors-viewport-mac-refactor` 实际 **12 tests / 0 failures / 0 errors / 0 skipped**（95468/95469，29秒），与Windows `authors-viewport-refactor` 的12/0及四文件格式对应：root3、mounted3、限定Wiring6。实际三份XML及case清单保存在 `authors-viewport-mac-refactor-*.xml` / `authors-viewport-mac-refactor-results.json`。原快速Late19/50、空加载帧、详情返回、搜索/清除、新activation重入断言均保持；确定性保存49/1而inactive viewport0的正确红证明必须执行真实恢复，新增用户真实滚0后retry仍0证明不会随后台refresh回放旧目标。此前Mac11/1、27/2及基线2/2失败证据保留，不以单次偶然绿作为依据。

主代理完成获批本轮限定独立审查的作者部分：导航activation身份、独立范围的LazyColumn与双list state、真实model位置权威、scope/query版本一致且ready后一次恢复、恢复前瞬时0写回抑制、用户后续滚动/分页/刷新、搜索重置和详情返回均已核对；原ProductionWiring大段格式仅imports/空白/标点整理，业务差异为两处第七参数matcher及中文fixture回归，未改production语言或查询。真实factory、SQLite mounted契约和两平台12项实际XML/格式/结构回执有效；没有剩余代码审查阻塞。迁移功能已提交 `07bb4e22eaff2b05272281e53c539b8cdbc29584`；作者功能随四源码/测试及本报告提交，随后才能使用已批准的一次Desktop full。HR02继续未勾选，正式构建/native与其他最终矩阵仍未执行。


### 第三次 Desktop full 与作者 SQLite owner 关停修复

迁移及作者恢复提交完成后，已批准的第三次 Desktop full 在`c6ed1458871a1bc7c22376ae6ad7d33de82829d5`运行。`history-reader-final-desktop-shutdown-recheck` 实际 **3274 tests / 1 failure / 0 errors / 3 skipped**，5分36秒，worker/process **49888 / 54548**，FAILED。完整原trace保留`.gradle-coordinator/history-reader-final-desktop-shutdown-recheck-failures.json`；此前两次full失败也保留。本次没有再次出现原迁移NoSuchFileException或作者LateAuthor50恢复失败，仍不能把完整套件记为通过。

唯一失败为DesktopSourcesScreenModelTest开始前的UncaughtExceptionsBeforeTest；suppressed为真实SQLite `SQLException: stmt pointer is closed`，执行栈包含GetPresentationExclusionsQuery→JvmDatabaseHandler.awaitList/dispatch，附Cancelled StandaloneCoroutine/Dispatchers.Default。Desktop production直接调用该查询的是AuthorDetailScreenModel的archive collector；身份编辑器使用同一detail scope。root/detail各自SupervisorJob的onDispose只cancel，作者挂载夹具此前在scene.close后只处理最新root，随即关闭handler，没有等待详情、旧root或实际SQL退出。完整套件中哪个旧fixture首发该异常仍无法从trace唯一确定。

**SOURCE/PROJECT_POLICY与正确红**：复用现有ScreenModel、factory及真实CreatorRepositoryImpl/JvmDatabaseHandler/SQLite；数据库owner关停前必须等自己创建的作者model查询退出，原Voyager同步onDispose及UI恢复规则保持。新增两个明确Unit返回的实际SQL契约，在生产卡片查询或presentation-exclusion查询已进入真实SQL cursor mapper时用受控闸门暂停；先dispose，再调用可编译且仅复用旧cancel行为的closeAndJoin。两项均因等待入口提前返回正确失败，红XML保存`authors-query-shutdown-red.xml`。闸门证明cancel-only不能保证真实SQL闭包退出，未把它说成full首发用例已定位。

最小实现为root/detail新增closeAndJoin，先复用onDispose取消，再等待各自scope的Job及全部子协程结束；身份editor共用detail scope，已取消/重复关闭也能等待。原同步Voyager disposal没有阻塞，viewport恢复、查询策略、Jdbc和迁移代码均未改。test-only factory helper用callOriginal记录全部真实root/detail，包括退栈与重新激活前实例；scene.close后在NonCancellable上下文逐个等待，在finally解绑全局factory mock，再关闭fixture SQLite。相同作者文件的真实SQL直建/重建、身份shared contract及挂载fixture沿用此顺序，原用户事件/断言保持。原mounted场景还确认owner确实保留多个root及被Back移除的detail。

| key | 实际结果 | worker / process |
|---|---|---|
| authors-query-shutdown-red | 两项生产SQL已进入mapper，dispose后关停提前返回，正确2/2；36秒 | 63428 / 57456 |
| authors-query-shutdown-green | test helper两处callOriginal多余泛型导致编译失败，未执行测试；不计业务红/绿 | 58180 / 55616 |
| authors-query-shutdown-green-confirm | root/detail关停2+原mounted3，共5/0/0/0 | 64144 / 3520 |
| authors-query-shutdown-format | FileCollection实际4个Kotlin文件格式化通过 | 44956 / 44068 |
| authors-query-shutdown-refactor | 四类限定26/0/0/0及4文件spotlessCheck；24秒 | 29564 / 65144 |
| authors-query-shutdown-mac-refactor | 同稳定四源码与17 selectors，26/0/0/0；33秒，主代理执行 | 40269 / 40270 |

受影响集合为AuthorsScreenModelsTest **5**、AuthorCardProductionWiringTest **3**、AuthorsProductionWiringTest的14 selectors匹配 **15** 项、DesktopSourcesScreenModelTest **3**；共26，Wiring没有执行完整类，其他模块没有执行完整测试。两份精确case清单及Mac四份XML保留在忽略过程目录；新的关停契约名称为`root close waits for its actual SQLite card query after disposal`及`detail close waits for actual presentation exclusion SQLite query after disposal`。实际四文件格式沿用已披露Desktop行长例外；这四个路径没有现有manifest锚点，manifest未改。

用户在本修复稳定后明确批准追加限定独立复审1轮及Desktop full1次，预计20–35分钟，无新代理；范围为本作者SQL owner关停及相关fixture，不扩其他能力。主代理已完成这次限定独立复审：owned SupervisorJob、同步取消与重复关闭、SQL正确红、真实factory全部实例捕获、NonCancellable等待/解绑/DB关闭顺序、身份shared contract仅bind一次、两平台实际26项及四文件格式均核对，通过且无需修复。原full首发fixture仍未确定。四Kotlin与必要文档随本修复功能提交，Windows/Mac无运行Gradle；提交及已批准Desktop full由主代理接管，本节记录时该full尚未执行。HR02未勾选，剩余最终矩阵及正式构建/native验收仍待执行，不把focused或本次复审替代它们。

### 作者关停修复提交后的 Desktop 全量通过

修复、两平台26项定向验证、限定独立复审和必要文档一并提交为 `be457511dba89d5842927fee81fbc9b30efc076d`。Windows工作树干净；Mac隔离工作区反向撤销同一已验证四文件补丁后导入该提交，tracked源码一致，保留已有测试临时文件。`history-reader-final-desktop-authors-shutdown-recheck` 经项目 `build-desktop.sh full-tests` 执行，实际 **3276 tests / 0 failures / 0 errors / 3 skipped**，5分32秒，worker/process **38220 / 51280**，PASSED。实际全部XML计数、跳过名称和源码记录保存在 `.gradle-coordinator/history-reader-final-desktop-authors-shutdown-recheck-results.json`；先前三次失败证据保留，不以该通过反向断定原始泄漏fixture唯一身份。

三项跳过为MacOsNativeSharePortTest的production JXA delegate、DesktopWindowPrivacyTest的windows frame affinity及LibraryPageCompositionTest的explicit non release custom interval；本次H01–H15对应测试没有跳过。完整Desktop证据覆盖当前已提交产品源码，后续正式构建可用 `build-only` 避免重复full，Mac仍需本轮正式运行。原计划尚未使用的一次domain/data JVM、data Android、Android app Release单元、test-desktop及全局格式组合由主代理接续执行；HR02仍未勾选，本文此节不宣称它们或正式产物/native已通过。

### 其余一次最终完整矩阵通过

`history-reader-final` 在同一提交 `be457511dba89d5842927fee81fbc9b30efc076d` 串行执行 `:domain:jvmTest :data:jvmTest :data:testDebugUnitTest :app:testReleaseUnitTest :test-desktop:test spotlessCheck --max-workers=2 --no-parallel`，最终 **PASSED**，35分24秒，413 tasks（136执行、152来自缓存、125 up-to-date），worker/process **49556 / 61016**。外层run等待曾超时并返回RUNNING；主代理未重复启动，核对原key最终退出0及进程结束后才启动正式构建。规模SQLite回归、Android编译及测试使本组合超过启动时10–30分钟的估计。

| 完整目标 | tests | failures / errors | skipped |
|---|---:|---:|---:|
| domain JVM | 568 | 0 / 0 | 0 |
| data JVM | 754 | 0 / 0 | 1 |
| data Android Debug 单元 | 345 | 0 / 0 | 0 |
| Android app Release 单元 | 672 | 0 / 0 | 7 |
| test-desktop | 52 | 0 / 0 | 0 |
| 合计 | 2391 | 0 / 0 | 8 |

全局spotlessCheck通过；Desktop本次实际修改文件另有对应FileCollection格式证据。8项跳过属于SyncGitCompareAcceptanceTest的真实Git导入/重放、AndroidSyncPanelTest的三个失败报告系统入口及AndroidLegacySyncMigrationTest的四个旧版回调测试；精确名称保存在 `.gradle-coordinator/history-reader-final-results.json`，本次H01–H15共享目录契约及阅读器目标没有跳过。此矩阵没有构建或安装Android APK，也没有运行finalParityAudit。

Windows/Mac已开始相同源码的项目脚本 `build-only`，各自将BUILD从68分配为69，完整版本 `0.11.19.69.be45751`；Windows协调器 `history-reader-windows-build`（59656/58172）、Mac协调器 `history-reader-mac-build`（97406/97407）。Mac部署目标解析并核对在本任务独立缓存内、与dist不重叠且启动前不存在，日常 `/Applications/Mihon Desktop.app` 保留。此节仅记录构建启动，实际产物、production运行及原生输入尚待完成；HR02保持未勾选。


### 正式候选构建与运行中的新增阻塞（尚未完成 HR02）

Windows `history-reader-windows-build` 与显式入口重试 `history-reader-windows-build-entry-retry` 均在 Bash→PowerShell 分派阶段退出127，未进入脚本主体；原因尚未定位，不能归因于扩展名或 PATH。沿用项目支持的 `scripts/build-windows.ps1`，用 PowerShell `-Command` 调用及已分配版本参数，`history-reader-windows-build-script-entry` 最终 PASSED（64492/56460，约2分34秒）。没有直接调用 Gradle 打包，未重新执行完整测试。脚本实际核对正式运行版本和 production APK 安装，扩展真实安装链路通过。

正式版本 **0.11.19.69.be45751**；`Final unpacked EXE:` 为 `D:/Codex/worktrees/f235/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.69.be45751-unpacked/Mihon Desktop.exe`，已确认存在；Windows ZIP SHA-256 `bf35d20dbfd81045f2ec5a227a4bb0c2bc1b9c4badcb18e5055d3332d8578c0a`。Mac `history-reader-mac-build` 同源码通过，正式 `.app` 为 `/Users/altair/Library/Caches/mihon-history-reader-20261001-f235/app/Mihon Desktop.app`，实际包可执行文件存在且 Info.plist 版本11.19.69；日常应用保留。两平台源码为 be457511 + BUILD69 元数据，产品代码与全量通过时一致。

Windows 正式 EXE 非headless、隔离 profile `runtime-profiles/windows-native`、HTTP58931/JMX58932，实际PID59008/包装器55952，已核对进程路径和启动参数。真实同步接收最初只创建章2/页1，chapterId1、historyId1、未收藏、UNKNOWN；真实 history_select 完成一次源目录请求后目录 COMPLETE3，refs为[2,1,3]，索引1、initialPage1与同步 heads 保留。实际 page list、图片 HTTP、DECODE 和 FIRST_PAGE_PRESENTED 已通过；随后真实阅读写页2来自双页呈现，属于正常 tracker 行为，不是目录准备伪造进度。

真实 incoming advance 已经由 journal/inbox/projector 接收章3/页2，打开中的中间章1/页1与 resumeHeadIds 保持不变，证据 `history-windows-incoming.json`。新的同步历史项可以改变当前最新 historyId，此变化不能误报为初次目录准备重建原ID。

**未通过项**：正式实例 `/test/reader/prev_chapter` 连续返回500空body，当前session仍Loaded。当前推断指向本批 ProductionReaderBinding 的 Dispatchers.Main；精确候选只读 jcmd classloader 实际看到 AndroidDispatcherFactory 与 MissingMainCoroutineDispatcher 已加载，但此项尚待因果诊断，不把它当作最终根因。打包运行时没有 jdk.jfr，异常录制不可用。原实施者仅接到限定只读诊断任务，未修改代码、未追加full或构建。

Windows 原生工具检查到前台PID57632属于另一任务隔离实例，未发送任何原生按键。Mac候选经LaunchServices启动，实际PID20338，独立mac-native profile，HTTP58941/JMX58942；用户明确确认已解锁、测试作品与候选可见。输入工具重新检查onConsole、精确窗口1024×768、输入权限与前台PID，通过；仅发送空按键列表预检，尚无键盘通过证据。外部AXFocusedUIElement读取先后返回-25202/-25204，不能稳定获得焦点几何，未将其认定为权限失败；已请求现场进行取消与焦点观察。HR02仍保持未勾选，以上构建通过不能替代未完成的正式切章和原生验收。


同 Windows 候选正常 shutdown 后确认原实际PID59008与包装器55952退出，精确同profile重启为包装器34204/实际39812。启动fixture COMPLETE3、dateFetch正、连续sourceOrder、chapterCalls0；真实历史再次进入采用最新同步章3、initialPage2与sender:1:2 heads，图片实际呈现。但 chapterCalls 升为1，跨进程“不重新请求目录”的runtime断言失败，保留 `history-windows-cache-runtime.json`。只读真实SQLite确切source-work行随后为/manga/history-catalog、manga_id1、COMPLETE3；仍需核对生产bootstrap/DI/调用顺序，未据此将重复请求归为正常或已修复。原实施者只读诊断范围增加这项，未开始新的修复或full。


### Windows 正式候选中已完成的失败、重试及详情路径

同一正式69候选、独立标记profile，逐项串行启动和正常关停；从未触碰其他任务实例或日常profile。HTTP500、empty、missing_target 三项真实source parser→准备→SQLite→history_select均拒绝（409）；原chapterId1/historyId1/页1保留，准备阶段outgoingUserEvents0。显式 history_read_existing 均挂载production reader并实际呈现四页图片；missing_target只提供refs[1]、两侧无邻章，源返回2章的COMPLETE观测不被伪装成包含旧目标的3章目录。固定过程证据为 `history-windows-http500-runtime.json`、`history-windows-empty-runtime.json`、`history-windows-missing_target-runtime.json`。

目标消失场景的profile正常关停并恢复默认成功源后，history_retry走真实请求，目录观测恢复COMPLETE3，refs[2,1,3]、当前原chapterId1正确、图片呈现，source目录调用1次；证据 `history-windows-retry-runtime.json`。这一证据属于失败恢复；不会把它说成冷启动缓存复用已经通过。

H03详情专项也从真实两个文件数据库同步接收初始中间章。为进入已有Library详情入口，仅在该隔离实例关停后，将固定测试作品的SQLite favorite设为1；明确属于fixture前置数据，不作为收藏业务或同步收藏验收。重启时仍一章、dateFetch0、目录调用0；实际open_manga_detail进入既有生产详情链路，自动补成三章、源调用1次、COMPLETE3，原chapterId/historyId/页1与outgoingUserEvents保留，favorite仍true。证据 `history-windows-details-runtime.json` 同时保存前置数据说明、入口动作、真实detail state和补载结果。没有用手工三章代替源目录补载。

进一步冷启动诊断 `history-windows-cache-diagnostic.json` 完整保存 select前、精确SQLite观测和select后：COMPLETE3且章节连续/正首取时间，启动时真实 source-work.manga_id=null，打开后源调用0→1；因此 needsRefresh 保护性拒绝复用是直接触发条件。清空绑定的启动producer尚待定位，不能放宽判定或声称缓存修复。Main调度必要修复已交原实施者先做无setMain的真实HTTP/mounted定向红绿；未授权新的full、复审或候选重建，旧69候选实际失败证据保留。


Mac外部焦点诊断补充：实际 AXIsProcessTrusted=true；改用精确应用 AXUIElementCreateApplication(PID20338) 的只读查询后，FocusedUIElement/owner PID可取得，但AXPosition仍返回-25202。没有发送原生按键或改变系统权限，焦点观察继续待现场回复，不把只读接口失败解释为输入权限缺失。


### 正式运行必要修复：两平台定向通过，追加验收待批准

只复用原实施者，未新增代理。Main路径正确红：移除真实 SyncedHistoryReaderIntegrationTest 的setMain覆盖、保持两个文件数据库/真实source HTTP/实际挂载reader及强断言，`history-reader-desktop-dispatch-red` 1/1失败（66492/57856，21秒），实际HTTP next_chapter期望200但返回500；生产代码未改。三处ProductionReaderBinding动作改为显式已有 Dispatchers.Swing 后，同1项最小绿（63464/56728），再Synced2+HistoryHTTP3及两文件格式重构5/0。候选真实MissingMain类加载、打包Android factory/stub字节码与该机制一致；正式应用完整异常因果链没有抓获，JFR模块不可用的限制保持。

缓存正确红：真实CreatorLibraryMangaIndexer.start/backfill→removeStaleLibraryMangaIndexes→detachStaleArchiveSourceWorksFromLibrary对favorite=false清manga_id，目录自然键/COMPLETE3保留；关停fixture后同DB重开真实DI与准备owner，sourceCalls应0但实际1。`history-reader-cache-detached-red` 新2项/1失败（57160/5316，18秒）；完整性边界项原代码已绿。只在SourceChapterCatalogWriter.needsRefresh允许null关联的确切自然键观测，保留COMPLETE/raw count/连续顺序/正首取时间全部约束，非空异manga仍Storage且无源调用；同2项最小绿（65604/57564）。不改library detach producer、共享协议、schema、源parser或详情字段。维护设计已同步说明书架关联可解除、自然键目录仍可按严格证据复用。

| 受影响重构范围 | 实际结果 | worker / process |
|---|---|---|
| Windows history-reader-runtime-repair-refactor | Synced2、HistoryHTTP3、Catalog13，共18/0/0/0；实际4文件spotlessCheck通过，25秒 | 50668 / 64260 |
| Mac history-reader-runtime-repair-mac-refactor | 相同三类、实际18/0/0/0 | 58337 / 58338 |

四源码/测试diff110增加/13删除；两平台实际patch SHA-256一致 `f860ba7248a2d893e040f78d437c0d8a1fabf2fa05cb5ec1ba29991e6224b3f6`。真实XML、case清单和结构回执保留 `.gradle-coordinator/history-reader-runtime-repair-*`，回执校验PASS；Windows/Mac无运行Gradle，协调权交回主代理。Windows本轮隔离详情候选实际64988/包装器61192已正常退出；Mac69候选20338保留等待现场观察。旧69候选没有上述两个源码修改，不能作为新代码最终验收或交付。

主代理现申请 **限定独立复审1次 + Desktop完整测试1次 + Windows/macOS各正式重建1次 + 必要正式运行复验**，预计20–35分钟、不含现场等待、无新代理；此段记录时尚未批准，未开始复审/full/重建或提交。此前定向/full没有覆盖真实Main默认选择及生产startup cleanup后的缓存重开，两项新契约关闭这两个验证缺口；不是因为预算用完才追加。共享domain/data/Android/test-desktop产品源码未改变，原完整矩阵证据可保持；仅Desktop需更新完整证据。HR02仍未勾选，Mac原生取消、Tab/Shift+Tab及焦点观察仍待回复。


### 2026-10-03 获批追加验收与限定独立复审

用户在说明前次full3276/0已经通过、随后正式运行发现的两项验证缺口及不能保证下一轮绝无新问题后，明确回复“批准”。授权范围为限定独立复审1次、Desktop完整测试1次、Windows/macOS各正式重建1次及相关正式运行复验，预计20–35分钟、不含现场原生等待；不新增代理，失败不自动追加full。原domain/data/Android/test-desktop源码未改，已有最终矩阵继续有效。

主代理本轮独立复审核对四文件稳定diff、生产DI及Screen binding、真实SQLite归档自然键读取与legacy兼容、fixture关停重开、两个正确红XML及两平台各18/0/0/0实际case清单。通过：Swing三动作仅改变既有Desktop适配线程，不改变业务导航或进度；null缓存仅接受真实确切sourceId/URL观测，非空异manga仍拒绝，非空raw章节/count/COMPLETE/连续order/dateFetch检查全部保留，不重绑书架关联。新增测试执行真实indexer清理、文件DB重开和production owner，不替换Main，不放宽原业务断言。未发现剩余审查阻塞；未进行第二轮复审。

本功能批次包含4个Kotlin文件及必要设计、Mac经验、唯一报告、roadmap与已有69版本分配元数据，共9文件；超过8文件提示的理由是代码修复、因果测试和正式运行验证边界必须联合记录，未扩产品能力或schema。修复及必要文档先提交，再执行此次批准的唯一Desktop full；HR02仍未勾选。
