---
parent-plan: 2026-06-30-mihon-desktop-refactor-roadmap.md
status: completed
date: 2026-09-08
---

# Mihon Desktop 书架页交互对齐 Roadmap

## 1. 目标、状态与权威

本计划将 Desktop 书架页的入口、选择状态、查询结果、设置生效范围、批量操作和反馈对齐原版 Mihon。
**作者页是本范围内唯一获准保留的独有产品逻辑。** 宽屏布局、鼠标、键盘、窗口生命周期属于平台适配，
不能据此另定搜索、分类、章节选择或删除规则。已有 Desktop 行为不能仅因已经存在就被认定为合法差异。

2026-09-08 对实现提交 `77d18569c` 的复审发现，原 LI-01～LI-08 完成声明超出了代码和测试能证明的范围，
当时撤回相关 checkoff，恢复为 `in-progress`。正式 EXE `0.11.19.23.8113c7d` 已包含该实现，问题不是用户打开了旧包。
已确认缺口包括搜索展开/选择工具栏、分类排序回读、仅封面角标/继续入口、批量操作离页取消、删除下载未清队列，
以及真实分类/删除对话框的接线证据不足。历史测试通过记录保留，但不能替代这些行为的失败测试与修复。
`mbp-lan` 当前可达；其旧 checkout 含用户改动，后续使用隔离 checkout 验收同一修复版本，不修改该用户工作树。

### 本轮补修进度（随提交更新）

截至 2026-09-09，**5/5 个补修行为批次及 LI-09 发布收口均已完成**。第 5 批为 `7a4a25bb5`，
测试夹具及证据修正为 `ea61d6188`；双平台正式版本为 **`0.11.19.25.ea61d61`**。
这是按已审查、验证和提交的可验收批次数计数，不是工时百分比；迁移端口的范围外缺口仍单列。
以下是本轮提交和验证的阅读索引；capability 机器状态仍以 manifest 为准。旧 LI 项跨越多个补修批次，
其总复选框只在该项全部关闭条件满足时勾选，已提交的部分不应因此被隐藏。

- [x] **补修 1：分类排序回读与仅封面控件**——提交 `7aaea9f`；分类 A→B→A 保持已保存排序，
  仅封面保留角标和继续阅读，并保持原版未读角标门禁。真实页面/数据库等 73 项通过，已独立审查。
- [x] **补修 2：搜索、选择工具栏与偏好消费**——提交 `0337e7a40`；搜索展开/清空/关闭、Escape 优先级、
  选择工具栏、空态/分类计数、各角标门禁、筛选作用域、重选书架打开当前设置、初始分类恢复和
  Test Mode 消费接线已修复。Desktop 165 项中 164 通过、1 项按构建类型跳过；该构建类型正例单独通过；
  Android 4 项、共享模块 3 项通过，已独立审查。完整命令及限制见 LI-02 的补修证据。
- [x] **补修 3：分类、移除和迁移入口的确认事务**——提交 `be44ddd6e`。修复混合态循环、加载失败可取消、
  编辑入口、逐本错误统计、迁移重复确认及参数快照；真实 Root→SQLDelight 验证分类 CRUD、
  三种移除选择、本地混选、Escape 取消及迁移入口。整批 135 项通过，补充验收所在类 12 项通过，
  已独立审查；不扩展迁移端口。完整红绿证据见 LI-04 后的补修证据。
- [x] **补修 4：批量操作生命周期与下载清理**——提交 `6b204c085`。已修复离页取消、
  离页失败反馈、下载目标身份/队列去重、安全退出后删文件及失败统计；真实 worker→Root 的下载角标/
  仅下载筛选→只删下载确认→保留收藏已通过。相关 124 项回归的唯一失败修正后，其所在类 19 项无失败
  （1 个构建门禁跳过），最终新增验收所在类 15 项全部通过；已独立审查及复审。
- [x] **补修 5：当前分类/全库刷新接线**——提交 `7a4a25bb5`。显式分类绕过全库设置、全库排除优先并识别
  默认分类 0；重复启动使用原版提示，离页返回/完成后的状态跟随实际 job。Desktop 88 项、Android 11 项、
  共享范围 2 项无失败；真实 Root 组合路径已通过，完成独立审查及修复复审，不重做后台执行器。
- [x] **发布收口**——完整矩阵及失败范围复验、manifest 审计、格式检查、双平台官方构建和正式应用
  Test Mode 书架验收完成。测试夹具修正未改变产品实现；保留首次失败和平台跳过记录，详见 6.4。

| 原 LI 项 | 已提交并验收的行为 | 关闭状态及边界 |
| --- | --- | --- |
| LI-01 | 分类恢复、查询展开/关闭、空态、过滤后计数及 Test Mode 消费；随机→真实详情→返回恢复 | 已关闭，见批次证据及 6.4 |
| LI-02 | 分类排序回读、偏好作用域、角标/仅封面、重选设置、实际下载/删除后的计数与筛选刷新 | 已关闭，见批次证据及 6.4 |
| LI-03 | 选择工具栏、返回优先级、跨分类全选/反选；迁移真实确认/取消和现有参数传递 | 书架入口已关闭；完整迁移端口保留 MG-01 PARTIAL |
| LI-04 | 三态、加载失败、目标快照、取消、编辑及真实 CRUD 接线 | 已关闭，见批次证据及 6.4 |
| LI-05/06 | 确认默认值、三种选择、本地混选、Escape、离页生命周期/失败反馈、队列与实际文件清理；按已读偏好仅删新标记章节 | 已关闭，见批次证据及 6.4 |
| LI-07 | 仅封面继续入口、未读角标耦合、下载资格/去重、运行中删除及可见结果刷新 | 已关闭，Reader 内部状态机保持范围外 |
| LI-08 | 分类/全库范围、搜索不误裁剪、重复请求、实际 job 生命周期及离页返回 | 已关闭，自动调度与更新执行器差异仍属 LU-01 |
| LI-09 | 全量矩阵及局部复验、双平台正式构建、实际产物书架运行验收 | 已关闭，产物与验证限制见 6.4 |

| 依据 | 固定值及用途 |
| --- | --- |
| 原版权威 | `6fbf6dfca203d99d6dd32137f2df97ced40c81b8`，使用本地 Git 对象读取原版动作和默认值 |
| 本次 Fork 基线 | `e75cb88388d30ac267ef9bffd88689ee0023c303`，以下 Desktop 路径与行号均指此提交 |
| 当前 Android | 同一 Fork 的 `app/`；核对其原版谱系后用于共享代码和双端 wiring，不单凭当前 Fork 自证原版 |
| 历史跟踪点 | `d7f3ceef5c75294306d0d9495e9ebc5ffca96302`，仅作历史索引；其 ViewModel/搜索变化不自动纳入 |
| 状态权威 | [parity-manifest.json](../../app-desktop/src/test/resources/parity/parity-manifest.json)，本文是动作级缺口与实施次序，不建立第二份机器状态源 |

已用本地 Git 比较固定原版与当前 Android：`LibraryTab`、`LibraryItem`、`LibrarySettingsScreenModel` 及主要
书架组件在所查范围保留原版语义；`LibraryScreenModel` 的主要相关变化是将筛选/排序交给共享 `EvaluateLibrary`。
因此下面的分类、搜索、选择、对话框和批量操作结论均有原版代码依据，不是以 Desktop 测试的预期倒推产品规格。

现有共享查询/分类/选择/下载目标实现和已接入入口继续复用。补修按上面的五个可验收行为批次串行推进：
确认事务与异步副作用分别以真实对话框/数据库和后台服务生命周期为测试边界；刷新以现有 scheduler 为边界。
每批补真实 production 红绿测试，主代理审查后提交；本轮已完成 LI-09 矩阵与正式发布。

与既有计划的关系：

- [父路线](./2026-06-30-mihon-desktop-refactor-roadmap.md) 的唯一执行指针在本轮完成后清为 `none`，不自动激活相邻计划。
- [非 Reader 计划](./2026-08-02-mihon-desktop-non-reader-upstream-core-roadmap.md) 的 `LB-01` 是本范围的历史总项；本文只细化其中书架交互，不继承整个 `NR0-01`、`MD-03`、`LU-01` 的施工依赖。
- 激活时在原计划注明本文接管的动作边界；非 Reader 计划其余部分及作者归档计划继续暂停，不能并行执行两个重叠书架任务。
- 历史 `16/17/19 VERIFIED` 证明的是当时登记的证据，不等于本次交互无缺口。执行时仅给受影响既有 action 补准确证据与状态裁决；不批量重建 inventory，不借机推进全仓语义映射。
- 旧 `LB-01` 所写“作者/Upcoming 入口保留”不能成为新增书架入口的依据。当前作者入口在主导航及漫画详情作者字段，按真实链路保护。

## 2. 范围锁定

### 2.1 本计划必须完成

1. 书架首页及其筛选/排序/显示设置、分类管理和批量对话框。
2. 从书架发起的详情、全局搜索、继续阅读、迁移请求，验收到现有目标流程正确接收输入。
3. 书架批量分类、已读/未读、下载、移除与下载删除的资格、目标集合、确认、持久化及必要副作用。
4. 当前分类/全库手动刷新入口、重复请求反馈及与现有更新服务的接线。
5. 以上行为直接需要的共享规则、Android 消费接线、Desktop adapter 和真实页面集成测试。

### 2.2 不纳入本计划

- 作者身份、归档、发现、索引升级或新入口；仅回归保护现有作者页及其数据关系。
- 漫画详情、阅读器、迁移、全局搜索、下载队列或设置中心的全面对齐；不改变它们的内部状态机。
- 后台更新 executor、调度约束、源解析、代理、网络客户端、下载存储协议、partial 阅读和恢复机制重构。
- 新增推荐、统计、筛选条件、快捷键业务能力、自动分类或额外书架聚合页；不做视觉重设计和逐像素复刻。
- 默认入库分类、重复新章已读策略、章节滑动手势和缺章显示等相邻设置的全面实现：它们属于入库/章节同步/详情链路，不能因为位于“书架设置”就混入本计划。
- 全仓架构清理、泛化动作总线、通用状态框架、新测试平台、基准测试项目或测试报告生成器。

2026-09-08 用户明确选择“保持书架范围，迁移端口缺口单列”。因此 LI-03 的本轮验收边界为原选择集
进入现有迁移确认页、已有选项正确传递、确认才提交、取消无提交。固定原版的来源选择/顺序、额外搜索词、
自定义封面和删除下载标志，以及隐藏未匹配/无更新结果、深度搜索、按章节数优先等迁移搜索配置，
现有 `BatchMigrationOptions` 和目标搜索链未完整承载；登记为范围外迁移端口缺口，不扩展迁移引擎或搜索链。
现有入口只能配置章节读状态、分类和笔记复制，替换行为沿用执行端口默认值。
本轮完成不得表述为“迁移配置已完整对齐原版”，manifest 中该缺口也不得被书架验收覆盖。

允许的外部改动必须同时满足：有本计划中的具体失败场景；现有入口无法以现有端口实现；修改局限于相应
端口/adapter 及其测试。比如刷新服务增加可选分类参数可以在本批次内完成；重做全部更新 executor 不可以。
Reader 内部的 partial/local-first 能力保持现有输入契约，作者功能保持当前链路；两者不是新的书架业务例外。

## 3. 已确认差异与代码证据（实施前基线）

下列短路径以仓库根为起点；行号为上述 Fork 基线定位辅助，执行时同时按 symbol 核对。
`A` = `app/src/main/java/`，`D` = `app-desktop/src/main/kotlin/mihon/desktop/`。

| 交互面 | 原版契约与证据 | Desktop 当前行为与证据 | 批次 |
| --- | --- | --- | --- |
| 分类页与恢复 | `A/eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt:213` 的分类投影；系统默认分类只在未过滤收藏中有默认归属时出现，普通空分类保留；活动 index 持久化并钳制 | `D/ui/library/LibraryTab.kt:197` 固定 `listOf(null) + categories`，额外增加“全部”；model `:304` 只写内存 index，未实现相同恢复规则 | LI-01 |
| 搜索及空态 | Android `LibraryItem.kt:28` 支持 `id:`、`src:`/`src:local`，搜标题、作者、画师、简介，另含来源/题材、逗号组合及负向匹配；`LibraryTab.kt:171` 区分加载、真空库与结果为空，`:211` 接全局搜索 | `D/domain/LibrarySearchFilter.kt:13` 只做 trim 后标题子串；`LibraryTab.kt:402` 只分空库/无匹配，没有相同搜索展开状态和全局搜索入口 | LI-01 |
| 随机与详情 | Android `LibraryTab.kt:131` 从当前分类结果随机打开详情，无条目有 Snackbar；普通点击进入详情 | Desktop `LibraryTab.kt:361` 已从可见结果随机，但空集合静默；详情已有嵌套 Navigator，继续复用 | LI-01 |
| 筛选与持久化 | Android `LibrarySettingsDialog.kt:80` 三态、全局仅下载时锁定该项、tracker 登录依赖、custom interval 的发布/约束门禁；`LibrarySettingsScreenModel.kt:40` 写共享偏好 | Desktop model `:227–270` 在 State 内改 filter；toolbar 暴露全局仅下载/发布期开关，未遵循相同持久化与可用性。共享 evaluator 存在不代表 UI 参数正确 | LI-02 |
| 排序/显示作用域 | Android `LibrarySettingsDialog.kt:165` 包含完整排序集合及随机重排；`SetSortModeForCategory` 根据 categorized setting 决定分类 flags/全局；`SetDisplayMode` 只写全局显示模式 | Desktop `LibrarySearchFilter.kt:9` 只有四种 SortMode；model `:298` 传空 trackerMeans；`LibraryCategoryPrefs.kt:10` 独立 `lib_cat_*` 同时按分类保存排序和显示，语义不同 | LI-02 |
| 显示控制 | Android `LibrarySettingsDialog.kt:243` 四种布局、列数、下载/未读/本地/语言角标、继续按钮、分类 tabs/数量 | Desktop `LibraryDisplayMode`/toolbar 只有三种布局；`LibraryComponents.kt:389` 起使用固定宽度和局部角标/按钮规则，缺完整偏好消费 | LI-02 |
| 选择与返回 | Android model `:518–585` 维护分类感知范围选择、当前页全选/反选及跨分类选择；`LibraryTab.kt:258` 返回先退出选择，再关闭搜索 | Desktop `LibrarySelectionState.kt:15–86` 是独立 anchor 状态；`LibraryTab` 未接同等返回优先级，顶部普通操作在选择时仍可用。不能只验证 Ctrl/Shift helper | LI-03 |
| 动作资格/迁移 | Android `LibraryTab.kt:150–161` 仅全非本地选择允许下载，迁移传 selection 进入配置流程 | Desktop selection bar 未按同等条件约束下载；`LibraryTab.kt:119` 先过滤 local 再直接 submit 批迁移队列，入口阶段及目标集合不等价 | LI-03 |
| 分类编辑与批量归属 | Android model `:596–617` 三态初值；`:481–491` 对每本书做 add/remove，混合态未改则保留；原版分类删除有确认 | Desktop `LibraryComponents.kt:735–742` 只加载首本分类；model `:542` 使用同一分类集合覆盖所有书；`CategoryManagementDialog` 行删除直接调用 | LI-04 |
| 标记已读/未读 | Android model `:430` 调 `SetReadStatus`；`A/eu/kanade/domain/chapter/interactor/SetReadStatus.kt:29–58` 包含已读后按偏好删除下载等副作用 | Desktop model `:408–415` 只调用共享 `SetChapterReadStatus` 修改章节数据；不能把 DB 读状态正确视作整条动作已对齐 | LI-05 |
| 删除确认与双选项 | Android `DeleteLibraryMangaDialog.kt:24–49` 默认都不选、至少一项才确认、含 local 隐藏删下载；model `:451–473` 分别移出书架/清封面及删除下载 | Desktop `LibraryTab.kt:268,328` 单本右键和批量均直接移除；model `:417` 只改 favorite，没有同等确认、独立删下载和副作用路径 | LI-06 |
| 下载目标 | Android model `:389–400` 先排除已下载/队列再取 N；动作菜单来自既有 DownloadAction | Desktop model `:464–480` 先 `take(N)` 后排除，首章已下载时“接下来 1 章”不会补选下一章；单本右键另走独立 helper | LI-07 |
| 继续阅读 | Android `LibraryTab.kt:196–205` 下一可读章或明确无下一章反馈，三个布局使用同一入口资格 | Desktop model `:512` 已有 Reader 请求基础，但 `LibraryTab.kt:438,468` null 直接返回，列表分支未传继续阅读回调；不应重写 Reader | LI-07 |
| 手动刷新 | Android `LibraryTab.kt:97–110,129–130` 分当前分类/全库，已在更新时提示 already running | Desktop `LibraryTab.kt:368` 传 allItems，factory `:73` 接无参数 `runNow`；model `:344` 运行中再次调用会尝试 cancel，但 toolbar 正在运行时只呈现进度。真实入口/状态与原版不一致 | LI-08 |

两处不能根据名称推断的细节：

1. 原版 `categorizedDisplaySettings` **并不意味着显示布局按分类保存**。固定版本的 `SetDisplayMode` 为全局；
   分类 flags 的分支用于排序。不能沿用旧 manifest 的笼统说明实现另一套规则。
2. 固定原版 item renderer 用受未读角标偏好影响的 `unreadCount > 0` 判断继续按钮。LI-02/07 应先用同一
   characterization fixture 锁定该耦合，本计划默认保留；若要修正原版本身，必须单独提出产品变更，不能暗中修改双端。
3. 原版搜索前缀判断忽略大小写，但 `substringAfter("id:")`/`substringAfter("src:")` 用小写字面量。
   对大写前缀的特殊结果也按固定版本建立 fixture，不借提取搜索之机修正规则或引入新查询 DSL。

### 3.1 现有测试能证明什么

| 现有资产 | 可复用的证据 | 不能作为本计划完成证据的部分 |
| --- | --- | --- |
| `domain/src/commonTest/.../library/interactor/EvaluateLibraryTest.kt` | 三态、组合筛选、排序方向与 tie-break 等真实共享算法 | 未证明 UI 提供全部选项、真实 tracker 分数、偏好重启恢复 |
| `app/src/test/.../ui/library/LibrarySharedEvaluationWiringTest.kt` | 反射调用 Android production 的 filter/sort 消费方法 | 绕过构造器的实例不能证明完整生命周期、DI 或页面交互 |
| `app-desktop/src/test/.../ui/library/LibraryScreenModelTest.kt`、`LibraryCategoryBehaviorTest.kt` | 部分 model、CRUD、下载、继续阅读行为 | 部分预期本身固化 Desktop 简化规则，先改正确红测，不盲目维护旧预期 |
| 同目录 `LibraryParityIntegrationTest.kt` | 离屏组合 selection bar、实际按钮回调与部分批量结果 | 单独组件不等于 `LibraryRootScreen` 把正确 selection、model 和 Navigator 接入 |
| 同目录 `LibraryPageCompositionTest.kt` | 真实页面 projection/context | `LibraryTab.kt:220–222` 的 probe 提前 return，跳过所有交互 UI；必须移除这种捷径后再验收交互 |
| `test-desktop/.../robot/LibraryRobot.kt:112–122` | 可沿用客户端接口 | `getMangaCount()` 返回 screen 数量，`assertMangaVisible()` 无断言，均不能证明书目显示 |
| `D/test/http/LibraryMangaTestModeController.kt:345` | 现成 model/controller 接入点 | `categories[index]` 与 UI 的虚拟 All 偏移不同；UI 与 Test Mode 必须消费同一个分类投影 |

这些发现是本轮代码审阅结论，并非已运行失败测试的记录。不要为它们单独建立“大测试设施修复阶段”：
LI-01 随真实页面红测补最小挂载能力和分类投影；后续各批次随行为补断言，最终仅补实际使用的 Robot 查询。

## 4. 实现原则与测试拆分依据

### 4.1 复用裁决

| 能力 | 选择与边界 |
| --- | --- |
| 筛选/排序 | 直接复用 `EvaluateLibrary`、`LibrarySort`、`LibraryPreferences`、`SetSortModeForCategory`、`SetDisplayMode`；补全真实输入，删除被替换的 Desktop enum/偏好决策 |
| 搜索、分类投影、选择 | 原版还在 Android model/item 中的纯决策按当前批次提取到 shared，并让 Android/Desktop 都消费；不整搬 Android UI，也不先创建通用 reducer 框架 |
| 分类修改/章节读状态 | 复用 `GetCategories`、`SetMangaCategories`、CRUD interactors、`SetChapterReadStatus` 等；确需新增三态增量/副作用编排时供两端共用，保留底层原子写入及真实错误 |
| 收藏移除/下载删除 | 先比较 `UpdateLibraryMembership`、`UpdateManga`、下载/封面端口的语义；只复用兼容路径，不能强行使用会改变日期、归属或作者关系的操作 |
| 阅读/下载目标 | 复用 `GetNextChapters`、现有章节过滤排序与 Reader entry resolver；纯目标选择可共享，Reader 构造、文件及队列操作留平台 adapter |
| 刷新/导航/键鼠 | 使用现有 scheduler、Voyager 嵌套 Navigator、Root factory/DI；仅适配参数、状态、输入和反馈，不另写业务客户端 |

平台独立保留的原因限于 Android Context/Activity/WorkManager 与 Desktop Voyager/窗口/文件服务不同。
产品体验上需要鼠标/键盘可操作以及宽屏展示，但同一动作的资格、目标集合、结果和持久化必须一致。
不因新增可测试 seam 而让测试与真实 UI 各走一条路径。

### 4.2 固定测试层级

每个功能批次都是“一个用户行为族 + 一个可证伪的测试边界 + production 接线 + 必要文档”的完整提交，
不按文件、测试类或红/绿阶段拆任务。不要求每个断言运行一次 Gradle；同一失败原因的一组场景合并运行。

- **红**：共享契约/fixture 固定原版输入输出，Desktop 真实 consumer 或 UI 操作按正确行为失败；不把编译失败当语义红测。
- **绿**：最小实现复用既有链路；**重构**：只清理本批次替换的重复决策，并复跑 focused tests。
- **双端证明**：共享测试验证纯规则，Android production consumer 测试证明实际调用；Desktop 必须从真实 factory/页面/动作到实际 use case。只跑 shared JVM 不等于双端 wiring 完成。
- **UI 证明**：复用 `ImageComposeScene` 等离屏能力挂载真实 `LibraryRootScreen`、对话框和嵌套导航。替换外部 I/O 边界可以，替换受测 handler、parser 或复制业务实现不可以。
- **副作用证明**：分类/章节/favorite 使用真实 repository 与隔离临时数据库；下载清理用临时目录与 production 下载服务；外部源可用 fixture。新增 HTTP 解析才要求对应 MockWebServer 成功、空/缺失、403/429/500、畸形响应矩阵。
- **接线敏感性**：审查确认若移除实际按钮回调、换错分类/章节 ID 或断开 factory 依赖，对应测试会失败；必要时作一次局部反证，不引入全仓 mutation 工程。
- **运行时证明**：最终在正式发布应用验证组合路径，Test Mode 与离屏 UI 互补；不得将辅助客户端或独立系统 JDK 的通过当作应用运行证据。

## 5. 实施批次与完成门禁

以下 8 个功能批次加 1 个收口批次构成唯一实施清单。依赖顺序明确，不将预计文件数用作强制拆分标准。
预估合计约 13–22 个工程日，取决于现有测试挂载与平台发布环境；不是工时承诺。代码行数增加本身不触发扩容。

- [x] **LI-01 分类、搜索、空态与详情入口**（原估 2–3 日）
- [x] **LI-02 筛选、排序和显示偏好闭环**（原估 3–4 日）
- [x] **LI-03 选择模式、返回优先级与迁移入口**（原估 1–2 日；仅本轮约定的迁移端口边界）
- [x] **LI-04 分类管理与批量三态归属**（原估 1–2 日）
- [x] **LI-05 已读/未读及其副作用**（原估 1–2 日）
- [x] **LI-06 移出书架与删除下载的确认事务**（原估 1–2 日）
- [x] **LI-07 继续阅读与下载目标选择**（原估 2–3 日）
- [x] **LI-08 当前分类/全库手动刷新**（原估 1–2 日）
- [x] **LI-09 组合回归、发布与证据收口**（原估 1–2 日）

### LI-01 分类、搜索、空态与详情入口

- 前置：激活本文并确认基线未变；不依赖旧 NR 全仓审计。
- RED：无分类、有系统默认、空自定义分类、删除/重排后活动页钳制及重启恢复；搜索关闭/null 与展开/空串；`id:`、`src:`/`src:local`、标题/作者/画师/简介、来源/题材、逗号及排除语法；真空库、加载和分类内搜索/筛选无匹配分别反馈。加入 UI 与 Test Mode 同一分类 ID/可见 ID 的契约测试。
- 实现与入口：原版搜索/分类投影提取共享，两端接入；Desktop 去掉额外 All 业务分支；搜索进入现有全局搜索并携带原 query，当前可见集合点击/随机进入详情，随机空集合提示。空库保留原版入门引导的可操作等价入口。
- 验证：shared 查询契约 + Android model consumer + 真实 Desktop page/nav/factory；关闭详情回到书架，query/分类状态一致。仅补本批次需要的离屏挂载 seam，不提前开发整个 Robot。
- 关闭：不靠 probe 提前 return 仍能操作真实页面；分类 ID、条目、标题/计数反馈同步正确。限于查询/导航，不改变收藏、阅读器或全局搜索内部行为。

> 历史完成声明（2026-09-08，复审已撤回，以下保留追溯，不作为本轮完成证据）：`TODO -> DONE`。RED 固定了原版查询、分类投影、加载/空态和真实页面挂载反例；GREEN/重构提取共享 query/category projection，移除页面 probe 提前返回，并接通嵌套 Navigator、详情与全局搜索。验证覆盖 `LibraryQueryAndCategoryProjectionTest`、`LibrarySearchFilterTest`、`LibraryCategoryBehaviorTest`、`LibraryPageCompositionTest`、`LibraryParityIntegrationTest` 与 Test Mode 书架行回读；独立审查通过。

### LI-02 筛选、排序和显示偏好闭环

- 前置：LI-01 的分类身份/查询投影稳定；固定原版偏好 key/default/scope。复用现有 evaluator，不新增查询引擎。
- RED：全部原版三态项及组合、重启恢复、全局仅下载锁定、tracker 登录/退出/分数与 UI 显隐（已登录但没有 Track 也可选）、发布构建 custom interval 门禁；全部排序/方向/tie-break/随机 seed，切换排序字段保留方向；全局布局与条件分类排序作用域；四布局、自动/指定列数、角标、继续按钮和分类 tabs/计数。
- 实现与入口：使用已有共享偏好/interactors，把选项→保存→响应式订阅→可见结果串起来。保持 settings 的原版作用域；隐藏 tabs 后仍能切分类，非空搜索时显示分类 tabs/计数。Desktop 将窗口尺寸映射到既有列数语义，手机旋转不模拟为新的业务状态。重选书架打开设置的等价入口在此接通。
- 旧偏好过渡：仅按下表处理 `lib_cat_*` 被替代 key，不建立 schema/备份格式迁移项目。RED 包含各冲突/非法值与重复启动场景，不能只测空偏好。
- 验证：共享偏好/排序契约、Android settings consumer、Desktop 真实设置交互→重建 model/偏好 store→显示结果；用真实 tracker aggregation 和下载数量输入，不以布尔下载值代替计数角标。
- 关闭：设置可操作且重启仍有效；临时禁用项不清掉已保存用户选择；继续按钮/未读角标的固定原版耦合有显式测试。此批次可能跨较多组件，按同一偏好传播链审查，不机械拆文件。

| 过渡情况 | 本计划固定裁决 |
| --- | --- |
| 共享键已设置 | 以 store 中实际存在 key 且可合法反序列化为准，不能用“读取值等于默认值”判断未设置；有效共享值始终优先 |
| 无有效共享全局布局/排序 | 分别只从旧 All 的 `lib_cat_-1_display`、`lib_cat_-1_sort`/`lib_cat_-1_sort_asc` 导入；三布局同名映射到共享类型，TITLE/UNREAD_COUNT/DATE_ADDED/LAST_READ 对应 Alphabetical/UnreadCount/DateAdded/LastRead，方向映射 Ascending/Descending；缺失或非法字段用固定原版默认，不任取某个分类的值 |
| 分类排序 | 只有共享 `categorized_display` 已明确为 true、该分类缺少已持久化 flags 记录且旧分类排序值有效时才导入；既有分类 flags（含合法零值）优先。其余旧分类值仅保留，不自动开启 categorized setting 或覆盖 DB |
| 多个分类布局相互冲突 | 原版只有全局布局，不能全部继续生效；按前述共享值/旧 All/原版默认的优先级决定。旧分类布局保留在旧键但停止消费，不能暗选最近活动分类或继续双轨 |
| 幂等与失败 | 用现有偏好迁移入口登记单一版本标记，完成所有所需写入后才记完成；中途失败不写完成标记，再试时有效目标值优先。旧键不删除，不产生逐任务快照；未知格式/不可表达的新增数据按第 7 节停在对应项 |

> 历史完成声明（2026-09-08，复审已撤回，以下保留追溯，不作为本轮完成证据）：`TODO -> DONE`。RED 覆盖三态筛选、tracker 登录恢复、全局下载锁、十种排序/方向、分类排序作用域、四种布局、列数、角标、继续按钮与旧偏好迁移；GREEN/重构接入 `LibraryPreferences`、`SetSortModeForCategory`、`SetDisplayMode` 及真实设置页。验证覆盖 `LibraryPreferenceMigrationTest`、`LibraryFilterUiTest`、`LibrarySortUiTest`、`LibraryScreenModelTest`、设置页离屏/可访问性测试和最终 Desktop 全量；独立审查通过。

> 补修批次 1（2026-09-08）：Root 持续订阅真实分类仓储，修复分类排序 A→B→A 回退；仅封面布局只隐藏标题，
> 保留角标/继续入口，并保留原版“关闭未读角标同时隐藏继续入口”的耦合。有效 RED 为 `sol-li-pref-red3`
>（DB flags 已更新但回到 A 显示 TITLE）和 `sol-li-cover-red2`（其余布局通过、CoverOnly 缺少下载角标）。
> `sol-li-pref-green2`、`sol-li-pref-final` 通过；后者覆盖 `LibraryCategoryBehaviorTest`、`LibraryPageCompositionTest`、
> `LibraryParityIntegrationTest`、`LibraryScreenModelTest`，合计 73 项、0 失败/错误/跳过。主代理核对 production diff、
> 真实页面点击→持久 DB→显示次序和 Reader 导航边界；早期调度器/测试夹具失败不计语义 RED。
> `sol-li-pref-spotless` 的根 `spotlessCheck` 通过，提交前 `git diff --check` 通过。
> 本记录只覆盖上述修复，其他筛选显隐、角标偏好和 Test Mode consumer 缺口仍须补齐，LI-02/07 不据此整项勾选。

2026-09-08 第二批补修与审查：搜索关闭/展开/清空、空格 query 原样转发、空态与入门入口、选择工具栏及
Escape 优先级、过滤后的分类计数、标题/角标门禁已接入真实 Root。重选书架复用当前筛选菜单；固定原版
打开的是 `LibrarySettingsDialog`，不能以自动更新等全局 `LibrarySettingsScreen` 替代。Test Mode 订阅实际
分类和偏好，排序写回分类 flags，分类 A→B→A 及重建后恢复；依赖订阅失败保持失败并在关闭时等待订阅退出。
另修复初始分类先于收藏到达时过早钳制保存页码的问题，待恢复值在内部保存，页面当前索引始终合法。

- 有效 RED 包括 `sol-li-ui-search-details-red2`、`sol-li-ui-search-focus-default-red`、
  `sol-li-ui-gates-badges-red`、`sol-li-ui-global-download-state-red`、`sol-li-ui-testmode-category-red`、
  `sol-li-ui-home-reselect-sheet-red2` 和 `sol-li-ui-initial-category-order-red`；编译、DI 夹具失败不计产品 RED。
- 最终正式参数验证 `sol-li-ui-desktop-final-focused3`：`:app-desktop:jvmTest -PincludeIntegrationTests=true`，
  分别以 `--tests` 指定 `LibraryPageCompositionTest`、`LibraryCategoryBehaviorTest`、`LibraryFilterUiTest`、
  `LibraryParityIntegrationTest`、`LibraryScreenModelTest`（均位于 `mihon.desktop.ui.library`），以及
  `mihon.desktop.ui.NavigationContractTest`、`mihon.desktop.ui.ExternalActionFeedbackWiringTest`、
  `mihon.desktop.test.http.LibraryMangaTestModeControllerTest`、`mihon.desktop.ui.ScreenInstantiationSmokeTest`：
  165 项，164 通过、1 项非发布构建专用场景跳过。该正例另以 `-PmihonNonReleaseBuild=true` 在
  `sol-li-ui-nonrelease-final` 通过，随后恢复正式参数并执行上述最终验证。
- Android `:app:testReleaseUnitTest --tests eu.kanade.tachiyomi.ui.library.LibrarySharedEvaluationWiringTest`
  在 `sol-li-ui-android-after-format` 4 项通过；其中新增实际 favorites、query/grouping/toolbar/selection
  consumer，既有字段检查不能单独作为行为证据。共享 `:domain:jvmTest --tests
  tachiyomi.domain.library.LibraryPresentationProjectionTest` 在 `sol-li-ui-domain-after-format` 3 项通过。
- App/Domain 的已配置 Spotless 检查通过；Desktop 未配置该 task，不能把根检查称为 Desktop 格式覆盖。
  本批 Desktop 新增代码人工整理，提交前 `git diff --check` 通过。主代理已核对 production 调用、真实
  Root/数据库测试及最终 XML；分类排序测试的异步保存等待与调度器清理问题在整组回归中修正后通过。
- 本批超过 8 文件/400 行：共享展示规则需两端消费，Root、Home 和 Test Mode 共用状态但入口不同，
  较多改动来自实际页面矩阵与异步恢复测试；保持同一交互批次有利于验证状态联动。风险集中于初始加载、
  偏好作用域和导航生命周期，已用对应 consumer/集成测试覆盖。分类弹窗事务、批量生命周期、下载清理及
  刷新服务仍待后续批次，不能据此勾选整个 LI-01～08。

执行偏差记录：`sol-li-ui-shared-nonrelease-green2` 的多 task 命令将 `--tests` 放在最后一个 task 后，
意外提前执行 2917 项 Desktop 测试，2 项失败、2 项跳过，额外约 3 分钟；已向用户说明，不作为收口证据。
后续 focused 改为单 test task，最终全量矩阵仍留在所有修复完成后执行。

### LI-03 选择模式、返回优先级与迁移入口

- 前置：LI-01/02，排序后的当前分类可见集合稳定。
- RED：长按/键鼠等价动作进入或扩展选择、选择时单击不进详情、分类感知范围选择（同分类最后选择为锚，跨分类第一次仅加入当前项，全选/反选重置范围分类锚）、跨分类选择保留、当前页全选 union/反选、取消选择与数量；返回先处理当前对话框，再按原版清选择/关闭搜索，普通工具栏和继续阅读不能绕开选择规则。
- 实现与入口：共享原版选择决策；键鼠事件只是 adapter，保留可操作的范围选择，不赋予 Ctrl/Shift 新的目标规则；动作显隐跟随原版资格。迁移薄入口接收原选择集，呈现现有端口支持的配置，确认后才调用迁移执行端口；取消不得 submit，不静默过滤 local，也不因点击菜单直接开始执行。原版完整配置按 2.2 节用户裁决单列缺口。
- 验证：相同选择序列喂双端 consumer + 真正页面鼠标/键盘事件 + action bar + Navigator；断开 selection 或目标传递会失败。实例化受影响 Screen/Tab，验证普通 Screen 进入嵌套 Navigator。
- 关闭：三种已存在布局及新增 cover-only 布局使用同一选择语义；迁移入口的选择集、受支持参数、确认及取消经过测试。目标搜索、引擎与队列状态机不变；原版配置缺口明确保留，不能仅以“已导航”关闭，也不得自动扩成完整迁移对齐。

> 历史完成声明（2026-09-08，复审已撤回，以下保留追溯，不作为本轮完成证据）：`TODO -> DONE`。RED 覆盖范围锚点、跨分类选择、全选/反选、返回优先级、动作资格和迁移确认；GREEN/重构将选择策略提取到 shared，并让 Desktop selection bar、迁移配置入口和 Android consumer 使用同一规则。验证覆盖 `LibrarySelectionPolicyTest`、`LibrarySelectionState`、`LibraryParityIntegrationTest`、迁移配置页实例化/导航测试；独立审查后补齐“清空最后选择同时清 anchor”的边界并复审通过。

### LI-04 分类管理与批量三态归属

- 前置：LI-03。该批次以 category repository/事务为共同测试边界，CRUD 和批量对话框一并验证。
- RED：共同分类为勾选、混合为中间态、未触碰混合不变、显式加入/移除、默认分类 0 排除；确认后每本保留自己的未改分类；取消不写；无自定义分类引导编辑；创建/重命名合法性、重排、删除确认与归属更新。
- 实现与入口：复用分类 CRUD、`GetCategories`、`SetMangaCategories`，共享三态 delta 编排；用户从选择菜单进入分类弹窗，管理入口复用已有分类 UI。删除分类的结果沿原版规则回流 LI-01 投影。
- 验证：原版三态 fixture + 双端 consumer + 真实弹窗点击到隔离 SQLDelight DB；一书失败时不把未成功书显示为成功，沿现有结果反馈恢复，取消无写入。
- 关闭：不再用第一本分类覆盖整组；不新增分类模型、批量任务引擎或默认入库策略。失败反馈需真实，不能凭空增加原版没有的自动重试或跨书回滚规则。

> 历史完成声明（2026-09-08，复审已撤回，以下保留追溯，不作为本轮完成证据）：`TODO -> DONE`。RED 覆盖共同/混合/未触碰三态、显式增删、取消无写入、CRUD 失败及删除确认；GREEN/重构复用真实分类 interactor，按每本书计算 delta，并保留未改分类。验证覆盖 `LibraryCategoryProjectionTest`、`LibraryCategoryBehaviorTest`、Desktop/Android consumer 与隔离数据库集成；独立审查通过。

#### 本轮确认事务补修证据（已提交，覆盖 LI-03/04/06）

- 分类组件与逐本更新：`sol-li-txn-category-red` 的 4 项分别复现混合态循环/输入重组、加载失败不可取消、
  缺编辑入口、单本读取失败中断整批；`sol-li-txn-category-green2` 对应 4 项通过。
  实现复用 `initialLibraryCategorySelections` 与原版 `CheckboxState.next()`，确认时固定目标并立即关闭；
  加载失败禁用确认，保留取消/编辑，正常协程取消继续传播；逐本读写错误分别计入已有批量结果。
- `sol-li-txn-category-root-green6` 验证真实 Root→production factory→SQLDelight：初始 A、A+B，
  未触碰混合 B 并添加 C 后分别为 A+C、A+B+C；确认清选择，弹窗类别保持打开时快照。
  前几次 root fixture 失败来自编译、异步加载等待、系统分类及底层 tab 文本误判，**不是产品 RED**。
  复查已确认页面投影保留空自定义分类；撤回无因果的 `allCategories` 替换，没有修改该投影规则。
- 迁移薄入口：有效 RED 为 `sol-li-txn-migration-red`（快速确认生成两条队列）、
  `sol-li-txn-migration-error-red2`（真实存储失败无可见反馈）和 `sol-li-txn-migration-snapshot-red`
  （确认后的 checkbox 变化修改待提交参数）。同步提交保护、点击时冻结请求/选项和可重试错误反馈已接入，
  `sol-li-txn-migration-green4` 的配置页测试通过；端口和迁移搜索/执行链未扩展。
- `sol-li-txn-removal-characterization4` 已验证真实 Root 的未选禁用确认、取消无写入以及仅移出/仅删下载/
  两项都选的结果。运行中队列与生产者清理、离页生命周期仍属于下一补修批，不能用本组静态文件验证代替。
- `sol-li-txn-category-root-crud` 补齐真实分类取消、编辑入口清选择和分类删除确认；
  `sol-li-txn-migration-root-characterization` 验证跨分类远程/本地混选、全选/反选、原目标进入迁移及取消无队列。
- 收口命令：`python scripts/gradle-coordinator.py run --key sol-li-txn-final-focused -- ./gradlew.bat
  :app-desktop:jvmTest -PincludeIntegrationTests=true`，仅以 `--tests` 选择 CategoryBehavior、ParityIntegration、
  RemovalPolicy、ScreenModel、LibraryBatchMigrationConfigScreen、DesktopBatchMigrationController 和
  ScreenInstantiationSmoke 七个测试类；135 项通过，无失败/跳过。迁移失败使用真实文件存储错误，
  验证可重试及没有残留队列，并断言确认导航到实际创建的队列 ID。
- 修复复审补验 `sol-li-txn-review-focused` 仅运行 `LibraryCategoryBehaviorTest`，12 项通过：真实 Root
  混选本地书时隐藏删下载，勾选后按 Escape 取消，收藏/真实 PNG/选择均保留；弹窗打开后新增的未选书
  不受后续三种删除选择影响。补验旧 production 直接通过，属于接线表征，不虚构产品 RED。
- 已完成主代理独立审查、补验复核和 `git diff --check`；Desktop 未配置 Spotless task，按现有风格核验。
  本批 9 个文件、超过 400 行，主要增量为复用现有离屏 Root/DI/临时 SQLDelight 的真实事务测试；
  保持确认输入与持久化验证在同一可交付批次，无新增框架。风险边界为下一批尚未修复的异步生命周期和运行中下载清理。

### LI-05 已读/未读及其副作用

- 前置：LI-03；复用现有章节和下载服务，与 LI-06 共用端口但不并行改同一文件。
- RED：选中多本、部分阅读进度、重复触发、未读清进度、已读只更新必要章节、已读后删下载偏好开/关、无变化章节不触发删除、执行失败与选择退出时机。原版发起操作后即清选择，任务不因离开页面而取消；以 `SetReadStatus` 为谱系确认副作用，不能自行加追踪上传。
- 实现与入口：单本右键和批量菜单均调用同一共享编排；`SetChapterReadStatus` 继续负责基础状态修改，平台 adapter 执行已存在的下载清理能力。Android 接入时保持非取消处理语义。
- 验证：shared chapter 契约 + Android/Desktop consumer + 真实 UI→DB/临时下载目录的效果；没有下载服务接线时测试必须失败。验证未读计数与筛选结果随更新变化。
- 关闭：读状态、进度、下载副作用及可见结果一致；不扩展 Reader 自动进度、追踪策略或下载存储结构。

> 历史完成声明（2026-09-08，复审已撤回，以下保留追溯，不作为本轮完成证据）：`TODO -> DONE`。RED 覆盖多书/部分章节、读后删除开关、无变化章节、失败反馈和选择退出时机；GREEN/重构通过共享 `SetChapterReadStatus` 与 Desktop 下载删除端口接入，Android 保持原版 consumer。验证包含 Android `testReleaseUnitTest`、Desktop library tests 和离屏副作用场景；独立审查通过。

### LI-06 移出书架与删除下载的确认事务

- 前置：LI-03；采用 LI-05 已明确的下载端口。两个选项属于同一个原版删除对话框，不能拆成彼此不验证的按钮补丁。
- RED：初始两项皆未选时禁用确认；取消/Escape 不变；仅移出、仅删下载、两者都选的效果矩阵；任何 local 混选隐藏下载选项；选中对象在弹窗期间变化时不误删其他书；封面清理、favorite 与实际文件结果。
- 实现与入口：单本右键/批量移除统一进入同一确认流程；确认使用该弹窗原目标快照，按原版关闭弹窗、清选择，非取消操作不因离开页面停止。复用现有 membership/update/cover/download adapter，不添加跨 DB/文件系统的“全局事务”承诺。
- 验证：共享决定/eligibility 契约 + 真对话框→production use case→临时 DB/目录；失败通过仍可见的既有反馈渠道报告，不显示全成功，不要求保留/重新打开确认弹窗，不自动重试。回归作者索引/关系不受非预期破坏。
- 关闭：危险动作无法绕过确认；仅移出不删除下载，仅删下载保留收藏；不增加磁盘整理、回收站或文件格式迁移。

> 历史完成声明（2026-09-08，复审已撤回，以下保留追溯，不作为本轮完成证据）：`TODO -> DONE`。RED 覆盖双选项确认矩阵、local 混选、取消、目标快照、封面/收藏/下载副作用和 partial failure；GREEN/重构统一单本与批量入口，并在异步任务前捕获目标、清选择。验证覆盖 `LibraryRemovalPolicyTest`、真实对话框/临时目录与 `LibraryParityIntegrationTest`；独立审查发现并修复清选择时序后复审通过。

#### 本轮生命周期与下载清理补修证据（已审查、验证并随本批提交，覆盖 LI-02/04/05/06/07）

- `sol-li-life-identity-red` 两项有效失败：单本入队没有漫画 ID、仍在错误队列的章节被再次选择。
  `sol-li-life-identity-green` 对应两项通过；单本记录目标漫画 ID，全部队列状态参与章 ID 去重。
- 下载删除以 `sol-li-life-retire-corrected-red` 为有效 RED：真实 factory/SQLDelight 取得实际生成的章 ID，
  删除目标文件后旧 production 仍保留目标和无关队列 `[1, 2]`，期望仅保留无关 `[2]`。
  较早 retirement 夹具误把预设章 ID 当作数据库生成 ID，相关失败不作为匹配或清理的证据。
- 实现边界：复用现有 manager 的任务停止、生产者退出及文件占用释放，再删除固定目标文件；
  原有 `mangaId=0` 队列仅借目标书的真实 DB 章 ID 识别，不按书名匹配或迁移整份队列。
  运行中多目标清理、离页完成/失败反馈、单本右键和页面角标/筛选变化均已取得下述证据。
- `sol-li-life-retire-generation-green3` 的真实 factory 和两个并发/代际用例共 3 项通过；
  先发起全部目标清理，再等待退出，已有旧任务清理不能遮蔽当前任务的取消。
  测试用显式执行顺序消除“后台 async 尚未执行就断言队列”的夹具竞态。
- `sol-li-life-root-leave-red2` 用真实 Root 按钮和受控仓储暂停复现离页取消，1 秒有界断言未到达完成点；
  `sol-li-life-root-leave-green` 通过。此组使用假仓储和独立通知订阅，仅证明 Root 接线的取消原因；
  随后 `ExternalActionFeedbackWiringTest` 的 `accepted library success and failure survive tab switches with Home feedback`
  已通过真实 Home→Root→production factory/SQLDelight：成功操作切到浏览后仍落库，失败操作切页后由 Home 显示提示。
  DI 仅在原仓储注册点加受控延迟/失败装饰，不重建依赖绑定，正常运行路径不变。
- `sol-li-life-final-focused` 共 124 项，122 通过、1 个正式构建门禁用例跳过、1 个右键测试失败。
  唯一失败是虚拟时钟轮询真实 IO 的夹具超时；改用真实等待后 `sol-li-life-page-final` 整类 19 项中
  18 通过、1 个同构建门禁跳过，无失败。该修正没有改动产品代码；不将初次失败隐去或说成全绿。
- 文件删除失败以 Windows 实际占用的 PNG 验证，provider 返回失败后页面不能计为成功；此测试按 OS 跳过
  允许删除已打开文件的系统。其他实际文件删除、legacy ID 与无关书目保护用例跨平台运行。
- 复用真实 writer 回归：`DownloadManagerTest` 的 `cancelled worker cannot remove or overwrite an immediate same id reenqueue`、
  `blocked page and chapter finalization do not delay cancellation` 与 `same id replacement waits for asynchronous cancellation cleanup`
  均纳入上述 focused；结合批量先取消后等待、旧/新任务代际和取消持久化失败的新增测试，验证退出完成前不删目标文件。
- 已读操作在写库前冻结漫画身份；封面失败与下载清理独立处理，每本只统计一次失败；下载完成/移出队列按章 ID
  和状态触发重新读取，纯进度变化不触发；只删下载无 DB 变化时也显式刷新。真实右键→同一移除确认→SQL/文件结果
  与单本下载/标记接线已通过。
- 最终复审把 `factory queue identity changes refresh visible download counts` 升级为真实页面验收：
  真实 DI/SQLDelight、Root 和 worker 从 MockWebServer 下载有效 PNG，完成后在“仅下载”中显示漫画和数字角标，
  再经实际移除对话框仅删下载，确认书目从筛选结果消失而 favorite 保留。
  `sol-li-life-visible-refresh-focused2` 通过，`sol-li-life-category-final` 整类 15 项全部通过。
  最初漏开默认关闭的下载角标导致的断言失败属于夹具前提，不改变产品默认值；关闭未读角标避免数字来源混淆。
- 本批跨 12 个源码/测试文件，约千行，主要增量来自真实 UI/DB/worker 夹具。Root 接受动作、manager 退出、
  文件结果与页面/离页反馈构成同一次已确认操作的生命周期，因此保持同一可编译验收批次；不按文件拆散。
  风险集中于下载取消/文件清理顺序，已覆盖原 writer、代际、持久化失败及真实文件结果；未改变下载协议或后台执行器。
  主代理完成独立审查及一次修复复审，检查实际 XML 与 production 接线；`git diff --check` 通过，
  Desktop 未注册 Spotless task，因此核对本批格式/新增 import，未新增 lint 基础设施。

### LI-07 继续阅读与下载目标选择

- 前置：LI-02/03；输入来自既有 manga/chapter flags、下载状态与 selection。
- RED：章节排序/过滤、已读和部分进度、本地/下载状态；下载 next N 必须先排除已下载/排队再取足 N，书签/未读动作沿原版目标语义；相同名称但不同 chapter ID、已有 queue 状态、空结果及失败反馈。继续阅读无下一章要反馈，不静默；列表和各网格都按偏好接同一入口。
- 实现与入口：复用章节选择和 Reader entry resolver，提取原版剩余纯下载选择策略供两端消费；单本右键也经过同一动作策略。真实下载身份用已存在 resolver/manager，不重造存储查找。Reader 请求包含正确 manga/chapter/initialPage，交给既有 Reader。
- 验证：共享章节 fixture + Android consumer + UI 点击到真实 factory/queue port/Reader route；已有 local/partial 请求保持兼容，以 request 和既有 Reader 边界测试证明，不把 Reader 全部回归放入红绿循环。
- 关闭：首章已下载时“接下来 1 章”下载下一合格章；继续按钮、空反馈和入队反馈可见。继续阅读与下载各保留独立场景，禁止因同批次而误用一个目标函数替代两者不同的原版条件。

> 历史完成声明（2026-09-08，复审已撤回，以下保留追溯，不作为本轮完成证据）：`TODO -> DONE`。RED 覆盖已下载/排队先排除后补足 N、chapter ID 去重、继续阅读无下一章反馈、列表/网格资格和真实 queue 状态；GREEN/重构提取 shared download selection，接入 Desktop manager/reader request 与 Android consumer。验证覆盖 `LibraryDownloadSelection`、`LibraryScreenModelTest`、页面集成和 Android library tests；独立审查补齐真实 queued-id seam 后复审通过。

### LI-08 当前分类/全库手动刷新

- 前置：LI-01/03；现有 `LibraryUpdateScheduler` 和 runtime 为唯一执行链。
- RED：当前分类与全库的准确目标、空分类、不受当前文本搜索结果误裁剪、运行中重复请求只反馈、不隐式取消；开始/运行/结束/失败状态从 production 服务获取，离开再返回页面不显示虚假空闲。
- 实现与入口：书架当前分类刷新和全库刷新分别传明确 scope。必要时在现有 scheduler 请求/目标选择端口增加范围，复用已有执行器与并发控制；Android 平台继续用 `LibraryUpdateJob.startNow`。已有显式取消能力只在明确取消入口调用，不把“再次刷新”解释成取消。
- 验证：共享手动请求/范围规则 + 双端启动 consumer + Desktop 真实 toolbar→factory→scheduler/隔离 repository，覆盖 already-running 与失败反馈；不以未使用的 fallback 更新循环作生产证据。
- 关闭：页面显示的状态与 runtime 一致。自动更新约束、章节同步、作者发现调度和系统通知重构保持范围外；若没有重写 executor 就无法满足某项，记录具体阻塞，不执行旧 `LU-01` 整包。

> 历史完成声明（2026-09-08，复审已撤回，以下保留追溯，不作为本轮完成证据）：`TODO -> DONE`。RED 覆盖当前分类/全库 scope、搜索不误裁剪、空分类、运行中重复请求和失败状态；GREEN/重构为现有 scheduler 增加可选 category scope，刷新按钮在运行中仍可见并反馈 already-running。验证覆盖 `LibraryUpdateSchedulerTest`、`LibraryParityIntegrationTest`、Desktop 全量和正式 EXE Test Mode；独立审查通过。

#### 本轮刷新补修证据（已审查、验证并随本批提交，覆盖 LI-01/05/08）

- `sol-li-refresh-scope-red` 已运行 `LibraryUpdateCategoryFilterTest` 与 `LibraryUpdateSchedulerTest`，
  18 项中 16 通过、2 项按预期失败：包含/排除重叠仍按包含优先；显式刷新分类仍被全库包含/排除设置裁剪。
  主代理已核对测试差异和真实 Gradle 输出，失败来自原 production 行为，非编译或夹具错误。
- 中断恢复时仅上述两个测试文件存在未提交实现端改动；随后提取 `selectLibraryMangaForUpdate`，
  Android worker 与 Desktop scheduler 共用显式分类/全库规则。`sol-li-refresh-scope-green-proxy3` 通过，
  Desktop 直接使用书架投影的分类（含默认分类 0），删除不再被 production 消费的旧分类筛选 helper。
- `sol-li-refresh-root-combo-green4` 在真实 Root→factory→scheduler→详情→返回链路暴露运行状态缺口：
  scheduler 的实际 job 尚未结束，返回后的书架却显示 `isUpdating=false`。据此补实际 job 接线和完成观察，
  不把周期调度存活或持久化的旧 Running 状态当作当前刷新；重复点击复用原版已有本地化反馈。
- `sol-li-refresh-root-combo-green7` 的 4 个 focused 场景全部通过：真实页面覆盖搜索不裁剪刷新范围、
  默认分类全库设置、随机→详情→实际返回、空分类反馈，以及按已读偏好清理实际章节下载而保留既有已读/
  无关章节；model 场景覆盖创建后才出现外部 job、重复请求附着观察、完成后允许再次刷新。
- `sol-li-refresh-desktop-final` 完整运行本批受影响的 `LibraryUpdateSchedulerTest`（13）、
  `LibraryScreenModelTest`（58）及 `LibraryCategoryBehaviorTest`（17），88 项全部通过，无跳过。
  `sol-li-refresh-domain-final` 运行共享 `LibraryUpdateScopeTest`，2 项通过；
  `sol-li-refresh-domain-format-check` 与 `sol-li-refresh-app-format-check` 分别通过对应模块格式检查。
  `sol-li-refresh-android-consumer-green3` 及格式整理后的 `sol-li-refresh-android-final` 运行实际 Android
  worker 所在整类，最终 11 项首次全部通过，无重试失败。
- Android `green2` 虽命令返回成功，但有 1 项先失败再重试成功，主代理读取 XML 后拒绝将其视为全绿；
  失败实际读到前一用例的分类，修正为项目已有的每用例 Injekt scope 保存/恢复。Root 菜单时序及虚拟
  Main dispatcher 的夹具失败也单独处理，均不作为产品 RED。未改产品逻辑迎合这些夹具。
- 本批跨共享规则、Android consumer、Desktop scheduler/factory/Root 及其测试；增量主要是真实 UI/SQL/
  后台 job 组合夹具，保持一个可编译、可独立验收的刷新行为批次。清除被替代 helper 及其未使用依赖，
  未扩展更新执行器。主代理已独立核对 production diff、真实 XML 与固定原版语义，完成一次修复复审；
  配置的格式检查及 `git diff --check` 通过，Desktop 沿既有未配置 Spotless 的边界检查本批格式。

### LI-09 组合回归、发布与证据收口

- 前置：LI-01～08 各自完成实现、独立审查、批次验证和提交；不把本批次当作补做全部 UI 测试的兜底。
- 组合场景：分类切换→搜索/筛选→排序→选择→混合分类赋值→已读→继续/下载→取消删除/确认删除→刷新→返回/重启恢复；作者主入口及详情作者链接仍可达。
- Test Mode 只扩展实际验收需用的分类、query、可见 ID/count 和操作结果，复用 production 状态；修正所调用的 Robot 占位断言，不修全套客户端或新增截图 API。
- 完成第 6 节最终验证矩阵。真实网络不可用时使用正式应用的可控 fixture/既有 Test Mode，不能替换为独立客户端；无法取得的平台发布证据明确留缺口，不能勾选收口。
- 关闭时更新受影响 manifest action 的准确证据、本文 checkoff 与父指针，和当前功能/发布提交一起落地；不为“advance/close/record”单独提交。报告 Windows 构建日志 `Final unpacked EXE:` 的实际路径并确认文件存在。

> 历史收口记录（2026-09-08，仅对应修复前提交，不能覆盖本轮 diff）：`TODO -> BLOCKED`。共享完整测试、Android 完整单元测试、Desktop 全量（2899 项，2 skipped）、Test Mode 客户端、manifest `finalParityAudit`、Spotless、Windows 正式构建与正式 EXE Test Mode 均通过；Windows 产物为 `0.11.19.23.8113c7d`。macOS 的 `mbp` 连接超时，`mbp-lan` 虽可达但 checkout 为旧的 `c84ed331fa` 且含无关用户文件，未取得同一 diff 的 macOS 构建/运行证据，故按门禁保留阻塞。

本轮收口状态（2026-09-09）：五个补修行为批次及发布验收已完成，最终证据见 6.4。
最初带环境重置的 coordinator `start` 命令被
工具拒绝，未产生进程；当时仅收到 `blocked by policy`，不能据此推断用户设置了限制。核查本地规则与
实际 SDK/JVM 环境后，保留现有正确环境，改用项目协调器前台 `run`，同一完整矩阵于 18:32 正常启动
（`library-repair-final-matrix`）。未修改权限配置，也未绕过测试。Mac 隔离目录在同一提交
`7a4a25bb5` 上执行官方脚本；原 Mac 用户工作树保持原样。此前的命令拒绝不计为一次全量测试。

## 6. 验证预算与可执行入口

### 6.1 每批次固定预算

- focused：同一行为族 RED 1 次、GREEN 1 次、重构后 1 次；正常合计 3 次。若确无重构可并用最后一次 green 作为最终证据，并注明未修改。
- 批次收尾：相关 unit/integration/wiring 与 `spotlessCheck` 合并 1 次；独立审查 1 轮，发现阻塞只修复并复审 1 轮。
- 不在每个批次运行全量 Android/Desktop、`finalParityAudit` 或发布构建。若用户要求中途发布一个完整迭代，再按仓库要求单独说明预算并使用构建脚本。
- Gradle 由主代理通过 `scripts/gradle-coordinator.py` 串行协调；超时先查原进程，不启动第二份重型验证。

以下是后续复验入口；修复前历史记录在 6.3，本轮最终命令与结果在 6.4。测试类通配为现有书架集合；每批次选择自己新增/受影响的类，
不要将示例 wildcard 当成固定全跑要求。KMP Android 测试 task 以实际注册的 variant 为准。Desktop 默认
排除 `integration` 标签，命中该标签的 focused 必须加 `-PincludeIntegrationTests=true`，不能接受零用例通过。

Desktop 正式构建默认 `BuildInfo.IS_NON_RELEASE_BUILD=false`。仅开发验证可显式传
`-PmihonNonReleaseBuild=true`，与原版“非发布构建且启用发布期限制”共同控制自定义间隔筛选入口；
不从 Test Mode 或版本阶段号推断构建类型。收口前必须恢复默认参数并验证正式构建隐藏该入口。
跨模块 focused 命令每次只指定一个 test task，并为该 task 显式传 `--tests`，避免末尾筛选参数只作用于
最后一个 task、让前面的模块意外执行全量。

```powershell
$ErrorActionPreference = 'Stop'
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:ANDROID_HOME = 'D:\Android\Sdk'
$env:ANDROID_SDK_ROOT = 'D:\Android\Sdk'
$env:HTTP_PROXY = 'http://127.0.0.1:10808'
$env:HTTPS_PROXY = 'http://127.0.0.1:10808'
$env:NO_PROXY = 'localhost,127.0.0.1,::1'

python scripts/gradle-coordinator.py run --key li01-shared-red -- .\gradlew.bat :domain:jvmTest --tests 'tachiyomi.domain.library.*'
python scripts/gradle-coordinator.py run --key li01-desktop-red -- .\gradlew.bat :app-desktop:jvmTest -PincludeIntegrationTests=true --tests 'mihon.desktop.ui.library.Library*'
python scripts/gradle-coordinator.py run --key li01-android -- .\gradlew.bat :app:testReleaseUnitTest --tests 'eu.kanade.tachiyomi.ui.library.*'
```

每批次记录实际命令/结果/失败原因/提交于该批次段落下即可，不额外生成每任务报告。不得把本示例键名用于
尚在运行的 coordinator；执行其他批次时用对应唯一 key。网络失败依仓库代理规则最多重试一次。

### 6.2 最终矩阵只运行一次

| 验证 | 频次与条件 |
| --- | --- |
| 共享受影响模块完整测试 | LI-09 阶段收口 1 次；包含 Android/JVM 对应目标及 DB 集成，不逐批次全跑 |
| 全量 Android/Desktop 测试与格式 | 批次修复全部结束后最终 1 次：根 `testReleaseUnitTest`、`:app-desktop:jvmTest -PincludeIntegrationTests=true`、`:test-desktop:test`、`spotlessCheck`；live-network/network-survey 等可选标签按既有门禁单列范围，不把默认排除项称为已通过 |
| 受影响 manifest/action 审计 | 最终 1 次；如既有门禁要求 `finalParityAudit`，在此统一运行，不触发全仓人工 inventory 修订 |
| Windows 发布与运行验收 | 使用 `scripts/build-desktop.sh`；同一未提交 diff 已有等价完整 Desktop JVM 证据时才用 `build-only` 避免重复全量测试；确认正式 artifacts 中 EXE，不以 tmp/build 地址交付 |
| macOS 发布与运行验收 | 通过既有 macOS 构建脚本/宿主完成 1 次；机器/连接不可用则明确阻塞及缺失证据，不复制 Windows 结论 |
| Test Mode + 离屏组合验收 | 各 1 组固定书架路径；只读状态需能反映真实条目/选择结果，不采集桌面像素 |

最终测试失败后，先用 focused 定位。若仅属环境问题且受测 diff 未变，可重跑失败范围并合并同一 diff 的完整
证据；若修改了受测行为，原全量证据对新 diff 不再等价，须说明失效范围并按第 7 节申请必要的额外完整验证/
构建预算。不得用局部通过调用 `build-only`，也不得默认重跑构建脚本从而静默突破全量测试上限。

Android 在本文主要保护共享核心及 consumer；原版 Android 页面交互不重新设计。最终按仓库收口要求运行全量
Android 测试；不将新功能 APK、真机覆盖所有页面或 Android 产品升级隐含加入本文范围。

### 6.3 修复前历史验证记录

| 层级 | 实际验证 | 结果 |
| --- | --- | --- |
| RED/GREEN/重构 | 历史记录曾声明全部完成；复审发现 helper 和孤立组件测试没有覆盖实际页面/服务链路 | 完成证据不足，按重开批次补测 |
| 共享模块 | `python scripts/gradle-coordinator.py run --key library-final-shared -- .\gradlew.bat :domain:jvmTest :data:jvmTest` | PASS |
| Android | `python scripts/gradle-coordinator.py run --key library-final-android -- .\gradlew.bat testReleaseUnitTest` | PASS |
| Desktop | `python scripts/gradle-coordinator.py run --key library-final-desktop-final -- .\gradlew.bat :app-desktop:jvmTest -PincludeIntegrationTests=true` | PASS：2899 项，2 skipped |
| Test Mode client | `python scripts/gradle-coordinator.py run --key library-final-test-desktop -- .\gradlew.bat :test-desktop:test` | PASS |
| Manifest/格式 | `finalParityAudit`；`python scripts/gradle-coordinator.py run --key library-final-format -- .\gradlew.bat spotlessCheck` | PASS |
| Windows build | `bash scripts/build-desktop.sh build-only`；构建日志 `.gradle-coordinator/library-build.log` | PASS；扩展 runtime acceptance PASS |
| Windows runtime | 正式 EXE `--test-mode --test-http-port=18080 --headless`；`/test/health`、`/test/state`、`POST /test/action/search` | PASS：READY/8 rows，搜索 query 正确回读 |
| macOS | `ssh mbp` 超时；`ssh mbp-lan` 仅确认旧 checkout `c84ed331fa`，未运行本次 diff | BLOCKED：缺同一 diff 的发布与运行证据 |

修复前历史 Windows 产物（仅保留溯源；本轮交付使用 6.4 的新地址）：
[Mihon Desktop.exe](D:/Shell/Github/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.23.8113c7d-unpacked/Mihon%20Desktop.exe)。
`app-desktop/tmp/` 仅作为构建内部目录，未作为交付地址。

### 6.4 本轮收口验证（已完成，2026-09-09）

- `library-repair-final-matrix` 在 Windows 执行第 6.2 节完整矩阵，耗时 5 分 38 秒。
  Domain JVM 397、Data JVM 127、各模块 Android release 单元测试合计 693、Test Mode 客户端 52 项通过；
  配置的 Spotless 检查通过。Desktop 2942 项中 2936 通过、3 失败、3 跳过，未将该命令记为全绿。
- Mac 隔离目录以同一提交 `7a4a25bb5` 执行 `scripts/build-desktop.sh`，包含 integration 标签，
  Desktop 2942 项中 2930 通过、同样 3 失败、9 跳过；首次脚本在测试阶段停止，未生成正式应用。
- 两端同样的三项失败均不涉及产品修补：历史 manifest/Task7 索引仍引用选择栏测试旧名称；HTTP 测试未
  配置主线程调度器导致排序返回 500；仅验证详情部分下载失败的夹具未提供新增分类订阅，导致入口返回 503。
  保留原历史测试 ID 并注明顶部反选的真实 Root 保护用例，补齐两个 HTTP 夹具的环境/订阅，未降低断言。
- `sol-li-evidence-http-red` 原样复现 HTTP 失败；`sol-li-evidence-focused-green` 修正后运行 HTTP 整类
  5 项与书架交互整类 16 项，21 项全部通过。产品实现不变，因此保持完整行为测试证据，仅补失败范围与
  manifest 审计；不重新运行全部 Desktop 测试。夹具/证据修正提交 `ea61d6188`；随后两端采用官方
  `build-only` 完成构建与运行验收，未用局部测试替代发生产品变化后的完整矩阵。
- `sol-li-evidence-roadmap-contract-green` 精确复验失败的 manifest 契约，1 项通过；
  `sol-li-evidence-final-parity-audit` 执行独立最终审计，1 项通过。期间两次误选普通 JVM task 排除的
  governance 标签方法返回 `No tests found`，未作为验证证据，随后以正确的 task/filter 完成。
- 跳过边界沿既有平台/环境门禁：正式构建隐藏非发布筛选入口（该入口正例已在补修 2 单独验证），
  Windows 原生隐私窗口测试依赖相应桌面环境；Mac 额外跳过 Windows 专属打包/签名/占用文件测试和
  Keychain 环境用例。跨平台的真实 Root→数据库、下载成功/清理及刷新组合正常执行；Windows 文件占用
  失败场景在 Windows 执行，不能把它的 Mac 跳过记录表述为跨平台通过。
- Mac 在同一提交 `ea61d6188` 上完成 `library-repair-macos-focused`：HTTP 5 项、书架交互 16 项、
  manifest 契约 1 项，共 22 项全部通过；`library-repair-macos-audit` 的 `finalParityAudit` 1 项通过。
  主代理逐项核对两端 XML。将首次完整矩阵与修正后的失败范围合并，Windows 为 2939 通过/3 跳过，
  Mac 为 2933 通过/9 跳过；这是同一产品实现的合并证据，不声称首次完整命令零失败。
- Windows 官方构建 `library-repair-windows-build` 通过，约 2 分 34 秒；真实打包 JRE、扩展 APK
  安装和 source 解析运行验收通过，日志明确输出 `Validated Mihon Desktop 0.11.19.25.ea61d61`。
  Mac 官方构建 `library-repair-macos-release` 通过，部署到 `/Applications/Mihon Desktop.app`，
  输出同一完整版本；Mac 使用隔离 checkout `/Users/altair/github/mihon-library-review-20260908`。
- 两端直接启动正式应用，使用 `--test-mode --test-http-port=<空闲回环端口> --headless`：
  `/test/health` 为 200、`/test/state` 的书架为 READY（Windows 8 条/Mac 2 条），
  `POST /test/action/search` 保留原始前后空格，随后恢复初始 query 并核对。没有修改筛选、排序、
  分类、收藏、章节或下载数据。实际 Root/对话框/导航交互由本轮离屏 production fixtures 验证，
  Test Mode 无窗口验收不冒充人工视觉检查。
- `/test/shutdown` 两端均返回 202，Mac 直接以 0 退出。Windows 首次 20 秒等待超时后终止了本次
  测试进程；有界复验确认 HTTP 已关闭，实际打包 JVM 已进入 `DestroyJavaVM`，仍有空闲的
  非 daemon `OkHttp Dispatcher` 线程，延长等待后自然以 0 退出。该退出等待现象没有被记为首次通过，
  未据此扩展网络客户端生命周期改造；最终确认本次测试进程全部退出。

实际命令（均由项目协调器持有单 worktree Gradle 锁；Mac 的两次局部复验串行完成后才构建）：

```powershell
python scripts/gradle-coordinator.py run --key library-repair-final-matrix -- .\gradlew.bat --offline :domain:jvmTest :data:jvmTest testReleaseUnitTest :app-desktop:jvmTest -PincludeIntegrationTests=true :test-desktop:test :app-desktop:finalParityAudit spotlessCheck
python scripts/gradle-coordinator.py run --key library-repair-windows-build -- 'C:/Program Files/Git/bin/bash.exe' scripts/build-desktop.sh build-only
```

```bash
# mbp-lan，隔离 checkout
python3 scripts/gradle-coordinator.py run --key library-repair-macos-focused -- ./gradlew --offline :app-desktop:jvmTest -PincludeIntegrationTests=true --tests mihon.desktop.test.http.LibraryMangaTestModeHttpTest --tests mihon.desktop.ui.library.LibraryParityIntegrationTest --tests 'mihon.desktop.parity.DesktopProductCapabilityContractTest.parity manifest defines the exact roadmap contract'
python3 scripts/gradle-coordinator.py run --key library-repair-macos-audit -- ./gradlew --offline :app-desktop:finalParityAudit
python3 scripts/gradle-coordinator.py run --key library-repair-macos-release -- bash scripts/build-desktop.sh build-only
```

对应构建/验证日志位于各 checkout 的 `.gradle-coordinator/<key>.log`；正式应用启动日志为
`library-repair-windows-runtime.log`、`library-repair-windows-runtime-recheck.log` 和
`library-repair-macos-runtime.log`，HTTP 结果和退出码由本次工具回执及上文记录。
Desktop 本身未配置 Spotless；本轮执行项目已配置的格式检查，并检查 Desktop 改动格式及 `git diff --check`。

本轮 Windows 交付（已核对构建日志 `Final unpacked EXE:` 原样路径及文件存在）：
[Mihon Desktop.exe](D:/Shell/Github/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.25.ea61d61-unpacked/Mihon%20Desktop.exe)。
配套 [Windows ZIP](D:/Shell/Github/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.25.ea61d61-windows.zip)
的 SHA-256 为 `997212a8acc9bd1db4151edca5a03e9c59dc187fb3bc7440783008ce7982c420`。
Mac 正式应用为该 Mac 上的 [Mihon Desktop.app](/Applications/Mihon%20Desktop.app)。
产物源提交是 `ea61d6188`；最终发布提交仅包含官方脚本分配的 BUILD 25 和必要的文档/manifest 证据收口，
不再修改受测产品实现，也不触发重复完整测试。

## 7. 防止执行扩张的硬约束

1. **批次冻结**：每批次开始前只列“本批次差异 ID、允许触及的生产入口、RED 场景、结束条件和流程预算”；不另写层层子计划。新增工作必须能指向该批次某个已列场景。
2. **只有语义依赖才重规划**：发现新用户能力、未知数据/格式迁移、安全边界或当前架构无法承载时，暂停受影响部分，说明最小新增范围、时间与替代方案，等待用户决定；其他独立部分可以继续。不因文件多、格式化或测试类长而扩张。
3. **相邻问题只登记**：在本文对应批次补一行范围外发现及影响；不自动新增任务、接管旧计划、开新追踪系统。范围外问题阻止本目标时，将该验收项标为阻塞而非降低标准。
4. **限制提取范围**：只提取本批次真实消费者需要的纯规则；两端接入和旧分支退出在同一批次完成。既有独立能力不随手移除；没有原版依据的 Desktop 书架分支不得包装成永久增强。
5. **共享状态串行**：默认主代理实现，最多 2 个子代理；确有不共享文件/接口前置已满足的流才并行。书架 Tab/model、factory、偏好与 manifest 不允许多个实现代理并写；测试和相应实现不拆给互相等待的代理。
6. **审查与验证有限**：每批次独立审查 1 轮、阻塞修复复审最多 1 轮；持续失败只调查直接原因，不重跑整套发布矩阵。新增流程或超出默认次数先说明预算，不静默扩容。
7. **一次功能提交**：一个批次含 production、测试和必要记录；审查修复最多再 1 个提交。提交前检查工作树，只 stage 本批次文件。`testfile/` 是已有用户内容，不读取、修改或提交。
8. **完成含义不变**：checkbox 仅在实现、独立审查、验证、提交全部具备后勾选；不以命令启动、测试名称、源码字符串扫描或 helper 返回值代替行为证据。没有交互入口或失败反馈不得关闭。
9. **版本不漂移**：实施期间不 fetch 新上游并吸收行为、不主动升级依赖。若用户切换原版目标版本，先局部比较本文动作，再改契约；不重启全项目对齐。

## 8. 最终用户验收路径

以下路径已由各批次的共享契约、真实 consumer、Root/SQL/文件/导航集成及 6.4 正式应用验收共同覆盖。
勾选表示已具备本轮自动化证据并随发布提交落地，不表示用户已逐项手动点击；仍可按这些路径手动复核。

- [x] 打开书架→只出现原版规则下的分类；切分类/重启→页、标题及条目对应，空分类不变成“全部”。
- [x] 搜索作者/题材组合→结果与原版 fixture 一致；无结果→有明确反馈；转全局搜索→保留 query；返回→恢复书架状态。
- [x] 筛选/排序/显示设置→选项、显隐和作用范围正确；重启→偏好保存；列表及各网格都遵循角标/继续按钮配置。
- [x] 选书、范围选择、换分类、全选/反选→数量与目标正确；返回→先退出选择，再关闭搜索；迁移→进入配置并带原选择集。
- [x] 选中分类分别为 A、A+B 的两本书→不修改混合 B，加入 C→结果为 A+C、A+B+C；取消→原样保留。
- [x] 批量标记已读/未读→读状态、进度、未读数及按偏好的下载清理一致；失败→显示实际结果。
- [x] 移除对话框→未选任何选项不能确认；仅移出/仅删下载/两者均选→各自效果正确；取消→书架与文件均不变。
- [x] 第一章已下载→“接下来 1 章”入队下一合格章；继续阅读→进入正确章节/进度；无下一章→显示反馈。
- [x] 当前分类刷新/全库刷新→准确提交对应范围；再次刷新→已运行提示；完成/失败→与后台实际状态一致。
- [x] 书架→漫画详情→作者字段，以及主导航作者页→原有入口、数据与返回链路可用，未新增作者业务。
- [x] LI-09 自动化矩阵及 Windows/macOS 正式应用路径验收完成→记录真实命令、日志、提交和可点击产物；缺失的平台不标为通过。
