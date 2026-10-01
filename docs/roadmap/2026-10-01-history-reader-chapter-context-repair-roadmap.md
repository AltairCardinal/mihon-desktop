---
status: in-progress
date: 2026-10-01
---

# 历史续读与同步后章节目录修复 roadmap

## 1. 目标、范围与执行状态

规格唯一入口：[修复设计](../2026-10-01-history-reader-chapter-context-repair-design.md)。目标：Desktop 历史打开已同步阅读记录后，能按真实目录跳转前后章；没有完整本机目录时按需补载，保留既有进度、历史和下载。验收固定为 H01–H15，不能在实施后因未完成而改为不适用。

规划基线：`main@5affb165495dff62dfbadf420c0f3e94bb971ace`。最初规划轮仅创建设计与 roadmap，未执行实施、测试或发布。2026-10-01 用户随后授权实施，本计划在 `D:/Codex/worktrees/f235/mihon`、实施基线 `5f13b080547ab2fcdcd6deb7ecdfa463e2765421` 激活；原设计基线与 H01–H15 冻结契约保持不变。产品 child plan 仍从第一个未勾选项推导进度，不声明 active-task，不改父 roadmap 的 active-child-plan。HR01 实现、独立审查、一次修复复审及相关验证已通过，checkoff 随本功能提交完成；HR02 为当前第一个未勾选项；实际证据只维护[唯一聚合报告](../evidence/history-reader-chapter-context-repair-2026-10-01.md)。

一个功能批次涵盖目录准备、历史导航和生产集成；这些部分共同交付同一个用户能力，分开提交孤立 helper、单个测试类或只补传参不能验收完整修复。采用两个任务：HR01 完整功能批次、HR02 最终交付。HR01 内按接口和写入边界串行实施，不把内部步骤当成各自完成的产品任务。

## 2. 流程预算与委派

| 项目 | 计划预算与边界 |
|---|---|
| 原规划轮 | 主代理，0 子代理；设计与 roadmap 各 1 份，一次 UTF-8/链接/内容/diff 核验，只提交两份新文档；该轮未运行 Gradle/构建 |
| 实施技能 | 无必需专门技能；遵守 TDD、Desktop UI 规范、现有架构与构建规范。macOS 执行前读 MACOS_ACCEPTANCE；不引入 HTML、媒体生成或新插件 |
| 实施者 | 1 名实施子代理承担 HR01 主实现与 focused 验证；主代理冻结接口、协调 Gradle、独立审查、整合和最终验收。连续修复复用同一代理，不重复实施其文件 |
| 真实并行 | 实施者写代码时，主代理可只读核对 SDK/脚本/当前平台可用性；不同文件读检查可并行。共享 data/domain/UI 落库与 wiring 串行，不并发跑重型 Gradle |
| 审查 | 独立审查 1 轮，覆盖 HR01 稳定整体 diff，重点 H09/H10/H11/H15；由未实施代码的主代理执行。阻塞修复复审最多 1 轮，只看修复及影响路径 |
| focused 验证 | HR01 按 A/B/C 三个行为范围，各一组红→绿→重构运行；扩写失败用例仍属于相应 focused 范围。批次完成后一次受影响回归+格式；必要失败诊断/修复只复跑受影响 focused，不提前全模块 |
| 最终全量 | HR01 审查、相关验证、提交全部完成后，HR02 执行一次冻结矩阵；包含完整 Android/Desktop 相关模块。失败保留证据并 focused 修复，再次 full 必须申请，不自动重跑 |
| 交付/过程 | 功能代码/测试、必要文档、聚合证据报告 1 份、Windows EXE 与 macOS .app；过程使用现有协调器日志，不建逐任务快照、报告或巨型 diff 包 |
| Git | HR01 原则一个功能提交，审查修复最多追加一个；HR02 版本/构建证据与必要 checkoff 一并提交。没有纯 close/advance 状态提交；仅本任务文件 |
| 预计墙钟 | HR01 5–9 小时；独立审查与必要修复 1–2 小时；HR02 2–4 小时；合计 8–15 小时，取决于缓存、测试夹具和 macOS 可用性 |
| 主要成本 | 真实 Compose/文件 SQLite/跨设备夹具、Gradle 编译、完整测试、Windows/macOS 正式构建。无需 Docker、付费生成服务或真实 GitHub 写入 |

预计 HR01 约 12–20 个文件，涉及 domain/data 窄接口、Desktop adapter/UI/DI、共享契约及平台测试；行数可能超过 400。内聚性理由是同一历史续读能力的目录、状态与真实 wiring 必须联合验证；风险集中于数据保留和目录完整性，不为满足估算拆开不可验收的 helper。执行前报告实际范围和预算；不因估算增长自动重规划。

2026-10-01 HR02 首次 Desktop full 实际 3265 项/58 失败后，用户明确批准追加：同一实施代理以红绿重构修复作者页“关注/全部”切换的非零滚动恢复，主代理对 History 必要回归修复与作者修复执行额外独立审查 1 轮，再执行 Desktop full 1 次；预计增加 45–90 分钟，不新增代理。原始失败证据保留，原 Android/domain/data/test-desktop/格式完整矩阵额度尚未使用。作者范围限定现有筛选状态、分页与双列表位置保持，保留搜索、关注、详情导航和既有 mounted 验收，不能用放宽断言替代修复；已证实混合 scope/cards 状态与单次滚动失败的因果边界分开记录。该项是首次 full 发现的独立用户能力，随必要测试和文档独立提交。再次 full 后若仍失败，不自动追加完整测试。

新增 schema/同步协议、Android 产品修复、下载更名/删除、额外代理/审查轮次、再次 full、实体设备安装/操作或不可逆发布均不属于本预算。出现需要时说明具体证据、成本和替代方案，暂停扩展并等待决定；范围内必要诊断继续。工具或签名/平台不可用记录真实阻塞，不把未执行验收标为通过。

## 3. HR01：完整修复历史续读、目录补载与生产导航

- [x] **HR01 完成：H01–H15 对应功能与相关自动化覆盖已实现、独立审查通过、受影响验证通过并提交。正式平台运行证据由 HR02 补齐。**

### 启动前置

1. 核对 HEAD、工作树、当前实际安装/运行版本与问题作品属于网络源还是本地源；未经读取不把用户版本等同于规划 HEAD。保护现有未提交变更，不卸载、不清数据，不借用日常用户 profile 做夹具。
2. 核对相关代码仍与设计事实一致、既有 `SaveSourceMangaForDetails`/DI/归档 bootstrap 的生命周期和共享实例；列出现有测试类与准备新增的类，不先修改功能代码。
3. 本轮接口冻结：目录准备有显式成功/失败/降级结果；历史请求有完整 refs/索引；观测有确切身份和完整性读取。默认复用现有表，不新增 schema；`initialized` 不重新定义为章节目录标志。
4. 首先把下面 A/B/C 和完整验收矩阵交给实施子代理。主代理不提前实现功能，只规划和协调；子代理必须自行核对真实当前入口，不把历史行号当事实。

### A. 目录准备与安全落库（先做）

**目标**：同步章已存在时仍能按需补齐作品目录；相同 URL 的已有章校正顺序且保留用户状态。此能力在本批必须接入真实详情入口，不交付孤立服务。

**修改入口**：`SaveSourceMangaForDetails`、`LibraryUpdateChecker`、`CreatorArchiveRepository/CreatorRepositoryImpl` 和对应 SQL 查询、`MangaDetailScreen`/model、相关 Injekt 绑定。建议窄接口放既有 repository，具体类型名由实施者沿用项目规范。

**红**：先扩展现有 `SaveSourceMangaForDetailsTest`、`MangaDetailSourceRefreshTest`，新增真实文件数据库集成契约，确认下面失败来自目标行为：initialized=true + 一条同步章不会补载、已存在章的 sourceOrder 不更新、无作者作品 COMPLETE 写入 0 行、落库中途失败留下半目录。不能只用 fake repository 返回值验证事务。

**最小实现**：

- 根据确切本机目录观测决定是否补载，原始集合判定先于扫描组/显示筛选；旧 UNKNOWN/无观测，以及旧 COMPLETE 但缺少源首取证据或顺序不连续的数据访问时保守刷新，成功跨进程复用，单章作品不重复请求。
- 扩展已有刷新 owner，提供可等待结果并合并同作品并发；详情与历史将共用，手动刷新和后台检查更新继续调用既有源获取路径。
- 提取两个 Desktop 刷新调用方中共同的非删除落库段，去重有效 URL、连续 sourceOrder；更新既有章的章号/memo/sourceOrder，仅本次源匹配且 dateFetch=0 时补写真实首取时间，不覆盖非零首取值，不替换 read/bookmark/lastPageRead 和 ID。
- 网络在事务外；bootstrap 在事务前；严格写入/最终集合核对、source-work upsert、章节与目录观测在真实短事务内完成。添加、更新、观测失败均回滚；处理 `addAll` 吞异常边界。
- 源失败、空网络目录、目标消失或重复本机身份时保留现有记录；沿用设计的失败/降级分类，不加源安装、账号授权或后台批量加载。
- 详情首次进入改用相同准备判定，已有稀疏列表保留可见，加载/失败/重试有反馈。维护原刷新按钮语义、下载、分类与筛选。

**绿→重构**：验证失败用例通过，再清理两处重复落库/判断，复跑同一范围。共享观测查询/状态保留契约同时由 JVM 与 Android wrapper 执行；不直接复用 Android 带下载删除副作用的整类实现。

**A 范围验收**：H03–H09/H11；文件数据库关闭重开、注入真实 SQL 写入失败、事务中进度状态保留、同作品请求计数、无作者/未收藏观测写后读取均有证据。安全接口稳定后实施者提交给主代理做接口核对；正式整体独立审查在 C 后进行，此前不引入依赖新协议/迁移的下游能力。

### B. 历史请求、失败反馈与真实阅读器导航（依赖 A）

**目标**：历史选择采用最新有效续读目标和真实目录；前后章按钮/键盘/转场使用相同 production 导航。

**修改入口**：`history/HistoryScreenModel.kt`、`HistoryScreenModelFactory.kt`、`ui/history/HistoryTab.kt`、最小 reader entry mapper、必要文案与现有 UI dependencies。复用 `ReaderChapterRefs`、`ReaderNavigator` 和 `RecordReadingProgress`；阅读器引擎及普通书架目标选择不重写。

**红**：以三章本机目录、中间历史章和另一同步续读目标写 `HistoryScreenModelTest`，并挂载真实历史 Compose 页面/嵌套 Navigator。测试实际点击 → pushed `DesktopReaderScreen` → mounted production reader，断言 refs/当前索引/章页/快照；再实际执行前后章动作。只断言 Screen 接口或请求 chapterId 不足以覆盖本缺陷。

**最小实现**：

- 历史读取完整目录，并在必要时调用 A 的准备链路；请求带 chapters/currentChapterIndex/chapterNumber 和既有 viewerFlags/resumeSnapshot，真实按钮使用同一 mapper。
- 准备结束、导航发布前解析最终有效目标及快照；目录数据不以旧对象覆盖新阅读状态。跨作品、排除源 URL、失效目标和索引不能默默退到 0。
- 真实同类 refs 保留扫描组过滤、skipRead/skipFiltered/skipDuplicate、下载和外部章节行为；正确处理“因过滤无邻章”与“目录没加载”的区别。
- 操作状态在历史 model 内按稳定对象 ID 隔离；加载防连点，成功只 push 一次。失败展示原因、重试和“使用已有章节阅读”，源缺失/已消失章按设计降级，不误删历史。
- 取消/页面退出/历史条目被删使导航资格失效；并发刷新其他使用者不被一起取消。返回恢复历史查询/滚动/焦点，临时反馈不回放。
- 真实 DI 初始化必须能解析新依赖，新增 Composable 取依赖纳入 wiring 测试；普通 Screen 使用嵌套 Navigator，保持 Tab 类型安全与实例化测试。

**绿→重构**：最小功能绿后收敛重复映射和错误分支，复跑同一 model/UI/navigation/wiring 范围。至少一次证明去掉实际 chapters 传参或 production 绑定会使行为测试失败；不得用源码字符串扫描代替。

**B 范围验收**：H01/H04/H07/H08/H10–H14；切换章节后断言 session 的当前 chapterId 和实际页面，不只看按钮 enabled。原有进度页失效归零提示、无痕、历史清除与会话基线仍保持原规则。

### C. 跨设备闭环、Test Mode 与批次回归（依赖 A/B）

**目标**：证明修复适用于真实同步稀疏接收数据，且正式包测试路径没有合成目录/进度旁路。

**修改入口**：现有 `DesktopSyncWiringTest`/接收数据库契约、`HistoryTestModeController`、`TestNavigationController`、`HistoryTestModeHttpTest` 及 reader production 测试。新增一个最小端到端类，如 `SyncedHistoryReaderIntegrationTest`；这只是建议名。

**红**：两个隔离文件数据库使用真实 journal/descriptor/inbox/projection 形成阅读记录，接收端只有中间章；真实源 adapter 从 MockWebServer 返回三章、页列表和图片。通过历史按钮打开后断言前后章能载入；当前 Test Mode 合成 openReader 旁路必须在真实历史路径用例中失败。

**最小实现**：

- `history_select` 的真实历史路径消费与 UI 相同的 request/mapper 和 production runtime/tracker；合成 reader fixture 单独保留，不影响其原用途。hasNext/hasPrev 及当前章取实际导航/session，不能手工写 true 冒充修复。
- HTTP/parser 用真实响应成功、空/缺失、403/429/500、畸形和恢复响应覆盖到数据对象/落库；不联网真实漫画站或写用户 GitHub 空间。
- 夹具覆盖源加载期间收到新进度、成功后数据库重开、已下载章/书签/时长/清除屏蔽保留、不生成伪阅读 outbox。实际阅读仍按原规则产生真实进度，不错误禁止正常用户日志。
- 深浅主题、中英、窄窗与大字体在真实 Compose 宿主核对 H14；截图限少量离屏候选并注明环境，不作为上游视觉基准。

**绿→重构及批次收口**：复跑 C 的 focused 范围；然后一次受影响回归集合+模块格式检查，主代理独立核对 A/B/C 产物与 H01–H15 自动化映射，重点数据失败/并发/导航旁路。实现者返回结构化回执 `status/diff/tests/commit/process/next`，保留首个正确红测和最终结果，不回传全文日志。

主代理完成独立审查后，阻塞问题交原实施者加回归修复，最多一轮复审；说明失败项、证据、上一轮未解决原因及本轮成本。通过后测试/production/必要文档/checkoff 随同一个功能提交；只有审查、相关验证与提交均完成才勾选 HR01。不得在此运行完整模块测试、finalParityAudit 或正式发布构建。

## 4. HR02：最终完整验证、正式构建与运行交付

- [ ] **HR02 完成：HR01 已完成；一次最终全量矩阵、Windows/macOS 正式运行、聚合证据、产物路径及最终提交齐全。**

### 前置与一次全量矩阵

只有 HR01 实施/审查/相关验证/提交全部完成后进入。执行前冻结当前 diff、测试 target 和平台环境；先核对协调器没有 STARTING/RUNNING，报告预计命令、时间、PID/日志。单任务或会话结束不能触发本步骤。

| 最终范围 | 执行与证据 |
|---|---|
| Desktop 完整 JVM，含集成 | Windows 项目脚本 `bash scripts/build-desktop.sh full-tests` 一次；当前脚本传 includeIntegrationTests=true |
| domain/data 完整 JVM | 一次最终协调器调用中运行 `:domain:jvmTest :data:jvmTest`，包含本批共享窄接口/状态保留与目录查询契约 |
| Android 完整单元回归 | 同轮运行 `:app:testReleaseUnitTest` 及受影响 data Android 完整单元目标；规划基线 data 例行契约目标为 `:data:testDebugUnitTest`，执行前核对 actual target，不靠 Android app 编译代替 data wrapper 执行 |
| Desktop E2E 客户端与格式 | 同轮 `:test-desktop:test spotlessCheck`；不是阶段提前 full，仅 HR02 最终执行 |
| Windows 正式构建 | 全量证据覆盖当前待发布产品代码时，`bash scripts/build-desktop.sh build-only`；仍执行版本分配、构建、自带 production runtime 与最终发布，避免重复 JVM full |
| macOS 正式构建/运行 | 相同源码/产品 diff、隔离 profile；先读 MACOS_ACCEPTANCE，使用项目脚本构建。等价完整 Desktop JVM 证据有效时用 build-only；Mac 受影响平台 focused/实际运行仍必须验证 |
| Android 交付边界 | 本次不修改 Android 产品入口，不产 APK、不递增 Android 正式版本、不安装实体设备；共享契约/完整回归必做。若实际扩展到 Android 产品改动，先调整范围和候选验收计划 |

完整测试组合是一次最终验证矩阵，可分平台/串行命令执行，不以命令条数重复预算。macOS 正式应用是同一 Desktop 代码的第二个平台产物；完整 JVM 不重复运行，平台 focused 与正式 runtime 不可省略。普通版本/证据变化不任意重跑全量；相关产品代码在全量后变化使证据失效时，先 focused 修复并申请追加 full。

### 发布运行与用户验收

1. 构建用项目脚本，不直接 Gradle 打包部署。核对实际 EXE/.app、版本、源码 provenance、隔离 profile 和端口，不启动日常用户实例替代验收。
2. 正式应用使用隔离夹具：先接收同步中间章，再从历史打开、等待目录补载，点击上一/下一章、键盘切章、章末转场；返回历史再进入。真实调用必须走 production source adapter、SQLite、DI、Screen 和 session；系统 JDK/独立客户端成功不能代替。
3. 测试缓存完整目录与首次稀疏目录，成功后重启、离线降级、重试、同作品并发和阅读中到达新同步。失败矩阵主要靠自动化，正式 runtime 至少验证 H01/H02/H03/H05/H07/H10/H13/H15 的关键路径。
4. 原生键盘/焦点/返回验收使用真实平台输入，Test Mode HTTP 业务动作只作链路/状态补充；不读取桌面像素，不依赖不存在的截图 API。视觉用离屏候选图；macOS 图形会话或权限不可用时保留待验项，不绕过。
5. Windows 完成报告只引用构建日志 `Final unpacked EXE:` 的实际绝对路径，先确认文件存在；不能交付 tmp 或 Gradle build 内 EXE。macOS 引用本轮实际 `Final macOS app:`，注明来源与环境。
6. 聚合证据报告唯一建议路径：`docs/evidence/history-reader-chapter-context-repair-2026-10-01.md`。汇总基线、H01–H15 测试名与首红/绿、独立审查、命令结果、平台/截图环境、版本/产物、风险和待验；不复制日志全文。维护既有 parity capability 证据时以 manifest 为权威，不另建重复状态表。
7. 必要版本、证据和 HR02 checkoff 一并提交。最终面向用户使用项目【功能特性】【BUG 修复】【验收清单】格式，以中文明确 BUG 已修复；证据不足时只报告已通过范围和待验，不能宣称全面完成。

## 5. 命令与实施回执模板

以下为执行模板；实际命令和结果以唯一聚合报告及协调器日志为准。新增测试类名称以实现为准，执行前确认存在并实际被运行。所有重型 Gradle 由同一协调者串行执行，协调器还在运行时不启动第二份。

```powershell
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:ANDROID_HOME = 'D:\Android\Sdk'
$env:ANDROID_SDK_ROOT = 'D:\Android\Sdk'
$env:HTTP_PROXY = 'http://127.0.0.1:10808'
$env:HTTPS_PROXY = 'http://127.0.0.1:10808'
$env:NO_PROXY = 'localhost,127.0.0.1,::1'

# A focused：既有类 + 本批实际新增文件数据库类，后者执行前补入 --tests
python scripts/gradle-coordinator.py run --key history-catalog-focused -- .\gradlew.bat :app-desktop:jvmTest --tests "mihon.desktop.domain.SaveSourceMangaForDetailsTest" --tests "mihon.desktop.ui.library.MangaDetailSourceRefreshTest" -PincludeIntegrationTests=true

# B focused：必须补入实际新增的 Compose/production 导航测试类
python scripts/gradle-coordinator.py run --key history-entry-focused -- .\gradlew.bat :app-desktop:jvmTest --tests "mihon.desktop.history.HistoryScreenModelTest" --tests "mihon.desktop.di.DesktopDiWiringTest" -PincludeIntegrationTests=true

# C focused：建议新类名；沿用真实同步和 history HTTP 类
python scripts/gradle-coordinator.py run --key history-sync-focused -- .\gradlew.bat :app-desktop:jvmTest --tests "mihon.desktop.sync.SyncedHistoryReaderIntegrationTest" --tests "mihon.desktop.sync.DesktopSyncWiringTest" --tests "mihon.desktop.test.http.HistoryTestModeHttpTest" -PincludeIntegrationTests=true

# HR02 才可执行：除 Desktop full 外的一次最终组合
python scripts/gradle-coordinator.py run --key history-reader-final -- .\gradlew.bat :domain:jvmTest :data:jvmTest :data:testDebugUnitTest :app:testReleaseUnitTest :test-desktop:test spotlessCheck

# HR02 Desktop full 与正式 Windows 构建；当前入口内部没有协调器，从外层串行包装
python scripts/gradle-coordinator.py run --key history-desktop-full -- bash scripts/build-desktop.sh full-tests
python scripts/gradle-coordinator.py run --key history-desktop-build -- bash scripts/build-desktop.sh build-only
```

data 新增共享契约必须同时有 JVM 与 Android focused 类，命令在类冻结后加入 A 的执行集合；不为缺少 wrapper 偷跑整个模块。检查 Android SDK 的 android.jar/aapt2.exe/adb.exe，用户级环境历史记录不能替代当前存在性。境外依赖请求遵守代理和一次追加重试；本地 MockWebServer/Test Mode 绕过代理。网络不可替代依赖仍失败时记录真实阻塞，继续独立文档/诊断工作。

建议子代理委派内容：目标=完整 HR01；背景=设计基线和 H01–H15；前置=干净任务写入边界及现有用户 diff 清单；修改范围=A/B/C 指定入口；禁止=协议/schema/Android UI/下载删除/提前 full/正式构建；验收=三组 focused TDD、一次批次回归、真实 wiring 与数据安全；交付=相关 diff、首红和绿证据、风险、进程状态及结构化回执。尚未独立审查/提交时 `status` 明确为待验，不勾选 HR01。

## 6. 原规划轮核验与后续手动清单

原规划轮只核验源码事实、设计与任务映射、链接、UTF-8 和 Git diff；未运行功能测试、构建、同步或读取用户实际数据库。激活后的 HR01 自动化证据见唯一聚合报告，两项产品任务在完成各自审查、验证与提交前均保持未勾选。后续最小手动清单如下，不能代替 H 矩阵自动化：

- [ ] Windows 既有完整目录作品 → 历史阅读中间章 → 上一/下一章均正确，返回历史保留查询与位置。
- [ ] Windows 首次接收其他设备的中间章记录 → 历史阅读 → 加载目录后切前后章，原进度与书签不丢。
- [ ] 同步作品 → 详情页 → 稀疏目录自动补载；手动刷新后已有章顺序正确。
- [ ] 单章作品 → 重复从历史进入 → 两侧自然不可切换，不反复请求目录。
- [ ] 源不可用或离线 → 历史阅读 → 说明原因，可重试或使用已有章节/下载阅读，历史不被删除。
- [ ] 打开阅读器后收到新的同步进度 → 当前章页和模式不变；返回后重新进入使用最新有效续读目标。
- [ ] 本轮正式 EXE/.app → 同样关键路径通过；完成报告提供真实可点击产物地址。
