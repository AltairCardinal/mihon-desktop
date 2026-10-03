---
status: in_progress
date: 2026-10-04
---

# 历史页上游行为对齐与双端共享设计

## 1. 需求、基线与完成边界

用户要求：Desktop 历史页的功能、操作和反馈与官方 Mihon 一致；能够共享的底层代码由本仓库 Android/Desktop 共用，消除没有必要的独立实现。历史条目关联到本机作品和章节后，应按原版续读规则进入阅读器，不在历史页先检查源、补全远端目录或提供另一套阅读失败流程。

本文件是固定设计，[执行 roadmap](roadmap/2026-10-04-history-upstream-parity-roadmap.md)是唯一实施计划。原规划轮只编写文档；用户随后要求实现本计划，实际实施与验收结果记录于[聚合证据](evidence/history-upstream-parity-2026-10-04.md)。设计中的必做契约保持固定，状态推进不表示未执行的测试或正式交付已经通过。

| 基准 | 固定内容 |
|---|---|
| 官方行为来源 | `mihonapp/mihon@4c88f02646aa1a358611e5b3b37ef7a62909b8d9`，2026-10-03 的固定 main 提交；不是对某个稳定发行版的等同声明 |
| 本地源码 | `codex/history-reader-context-repair@92f1617fd3350a8c98eda2f4e2830551dfb5b64f`；产品改动最近基线包含 `c4adc8ad2a` |
| 规划时已有改动 | `AppVersion.kt`、`MACOS_ACCEPTANCE.md`、旧历史修复聚合报告已有未提交内容；本轮不编辑或提交这些内容 |
| 原计划 | [旧设计](2026-10-01-history-reader-chapter-context-repair-design.md)与[旧 roadmap](roadmap/2026-10-01-history-reader-chapter-context-repair-roadmap.md)保留历史证据，由本设计接管后续行为与交付要求；不把旧 HR02 改成完成 |
| 规范 | [Desktop UI 入口](design/mihon-desktop-ui/README.md)、[组件事实](design/mihon-desktop-ui/desktop-reference.md)、[页面契约字段](design/mihon-desktop-ui/page-contracts.md)、[项目规则](../AGENTS.md) |
| 状态权威 | [parity manifest](../app-desktop/src/test/resources/parity/parity-manifest.json)继续管理 capability 状态；本设计不是新的状态登记系统 |

SOURCE 表示下述固定源码事实；PROJECT_POLICY 表示用户要求及本设计选择。没有 HTML_ADAPTER：本轮不创建浏览器原型，HTML 或源码扫描不能替代原生行为验收。后续上游变更须显式更新基线和受影响契约，不能在实施中追随浮动 main 改变验收。

### 1.1 已核对的官方来源

- [HistoryViewModel](https://github.com/mihonapp/mihon/blob/4c88f02646aa1a358611e5b3b37ef7a62909b8d9/app/src/main/java/eu/kanade/tachiyomi/ui/history/HistoryViewModel.kt)：搜索订阅、续读、删除、收藏、分类、重复作品和追踪调用。
- [HistoryTab](https://github.com/mihonapp/mihon/blob/4c88f02646aa1a358611e5b3b37ef7a62909b8d9/app/src/main/java/eu/kanade/tachiyomi/ui/history/HistoryTab.kt)：封面进详情、行续读、重选标签、弹窗和 ReaderActivity 导航。
- [HistoryScreen](https://github.com/mihonapp/mihon/blob/4c88f02646aa1a358611e5b3b37ef7a62909b8d9/app/src/main/java/eu/kanade/presentation/history/HistoryScreen.kt)、[HistoryItem](https://github.com/mihonapp/mihon/blob/4c88f02646aa1a358611e5b3b37ef7a62909b8d9/app/src/main/java/eu/kanade/presentation/history/components/HistoryItem.kt)、[HistoryDialogs](https://github.com/mihonapp/mihon/blob/4c88f02646aa1a358611e5b3b37ef7a62909b8d9/app/src/main/java/eu/kanade/presentation/history/components/HistoryDialogs.kt)：操作入口、空状态、列表与删除确认。
- [GetNextChapters](https://github.com/mihonapp/mihon/blob/4c88f02646aa1a358611e5b3b37ef7a62909b8d9/domain/src/main/java/tachiyomi/domain/history/interactor/GetNextChapters.kt)：本地章节过滤、排序、当前章已读与未读分支。
- [ReaderViewModel](https://github.com/mihonapp/mihon/blob/4c88f02646aa1a358611e5b3b37ef7a62909b8d9/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt)：阅读器自行取得本地章节上下文；历史入口不负责源目录获取。

### 1.2 规划基线差异与证据性质

此表记录规划时的代码与诊断，作为改造动机保留；实施后的接口见第 8 节，实际红绿与剩余验收见聚合证据。表中旧链路不表示当前实现仍在使用。

| 当前代码入口 | SOURCE 事实 | 影响 |
|---|---|---|
| [Desktop HistoryScreenModel](../app-desktop/src/main/kotlin/mihon/desktop/history/HistoryScreenModel.kt) | `loadHistory` 等 `subscribe(query).first()` 后才写输入值，只消费一次结果 | 输入状态依赖查询完成；没有持续接收数据库变化。用户报告光标回到首字符前，诊断指向此链路；本轮不重新执行复现测试 |
| [Desktop HistoryTab](../app-desktop/src/main/kotlin/mihon/desktop/ui/history/HistoryTab.kt) | `onValueChange` 和 `LaunchedEffect(searchQuery)` 都发起查询；独立卡片布局、播放按钮、直接单条删除，无封面详情和收藏动作 | 与官方的搜索、点击及删除语义不同；旧查询晚到覆盖新查询属于需用闸门测试覆盖的竞态风险 |
| [HistoryScreenModelFactory](../app-desktop/src/main/kotlin/mihon/desktop/history/HistoryScreenModelFactory.kt) | 注入 `SaveSourceMangaForDetails.awaitPrepared`，历史请求等待目录成功 | 源目录失败会阻止打开本来可读的已知章节，产生历史行加载、重试与降级 UI |
| [本地 GetNextChapters](../domain/src/commonMain/kotlin/tachiyomi/domain/history/interactor/GetNextChapters.kt) | 已在共享模块，并由 Desktop DI 注册；Desktop 历史页没有使用它 | 另写的目标选择绕过原版已读章续下一章规则 |
| [本地 Android HistoryScreenModel](../app/src/main/java/eu/kanade/tachiyomi/ui/history/HistoryScreenModel.kt) | 已有响应式搜索，但先选同步续读章，再调用原版用例 | 当前 fork Android 不是未经修改的官方基准；不能直接复制它并声称完全对齐 |
| [历史仓库](../data/src/commonMain/kotlin/tachiyomi/data/history/HistoryRepositoryImpl.kt)、[历史 SQL](../data/src/commonMain/sqldelight/tachiyomi/view/historyView.sq) | 查询、每作品最近记录投影、删除及同步历史屏蔽已共享 | 无需建立新历史表、缓存、查询或删除算法 |
| [presentation-core](../presentation-core/build.gradle.kts) | 仍是 Android 模块 | Android 页面不能直接导入 Desktop；限制在 UI 依赖，不是另写业务规则的理由 |

## 2. 范围与复用决定

### 2.1 本次交付

1. 共享历史状态、响应式查询、日期分组、动作和结果事件；Android/Desktop 同时接入并删除被替代的重复逻辑。
2. 恢复官方历史页全部入口：搜索、封面详情、行续读、重选标签续读最近记录、收藏及关联分类/重复作品/迁移、两种范围的删除和清空确认。
3. 历史选章复用官方规则，平台阅读器共用必要的打开上下文组装；已知当前章不等待远端目录。
4. 保留本项目同步续读、稳定阅读会话、下载识别、删除屏蔽和安全目录落库。同步目录补全按第 5 节作为阅读器扩展处理，不能再反向增加历史页业务。
5. 共享契约、真实双端 wiring、Windows/macOS 原生输入与正式产物验收；Android 共享页面接入须有实际运行证据。

### 2.2 复用清单

| 能力 | 实施决定 |
|---|---|
| 历史读取、续读、删除 | 直接使用 `GetHistory`、`GetNextChapters`、`RemoveHistory` 与已有 `HistoryRepository`，保留 SQL 和持久化语义 |
| 历史状态与动作 | 从官方行为和本地 Android 实现提取纯 Kotlin 共享 controller/state/events，放入 `domain/commonMain` 既有 history 包下；不是复制一份 Desktop presenter |
| 历史 UI | 新建窄范围 `presentation-history` KMP 模块，沿用 `presentation-sync`/`presentation-theme` 的 Android + JVM 配置模式；只迁移历史所需无平台依赖内容和组件 |
| 收藏、分类、重复作品 | 复用 `GetDuplicateLibraryManga`、`GetCategories`、`LibraryPreferences`、`UpdateLibraryMembership` 与既有迁移服务。补齐共同编排，不在历史页新写数据库更新 |
| 增强追踪 | 复用 Android `AddTracks` 和 Desktop 已有 `EnhancedTrackerWorkflow`/registry，通过窄平台 port 连接；共同决定触发时机，平台处理认证和源适配 |
| Reader 上下文 | 复用 `GetMangaWithChapters`/`GetChaptersByMangaId`、章节排序与共享邻接规则、`toReaderChapterRefs`、现有进度仓库；历史和详情共用 Desktop mapper/工厂 |
| 目录补全 | 复用 `SaveSourceMangaForDetails`、`SourceChapterCatalogWriter` 的合并/观测/事务与同作品请求合并；可共享的非删除落库核心移入 `data/commonMain`，网络和平台源调用留在 adapter |
| 生命周期与导航 | 保留当前 Injekt、Voyager、Android Activity/ScreenModel 包装及 Desktop 嵌套 Navigator；共享核心不直接调用平台导航或全局 UI 单例 |

`presentation-history` 不依赖 `app`、`app-desktop` 或 Android 专有 `presentation-core`。公共状态不引用 SnackbarHostState、Activity、Navigator、平台图片对象。封面加载、导航执行、键盘/触摸与追踪 I/O 通过少量明确参数或 port 适配；可共享的规则只保留一份 production 实现。

模块范围限定历史页；不把全体 `presentation-core` 多平台化，不迁移整套 DI，不升级整个 fork 到官方新框架。同步协议/schema、全站导航、章节删除/下载更名策略、作者页面和全部迁移引擎不纳入改造。本地已有其他页面的封面尺寸不跟随本次历史组件改变。

## 3. 共享历史行为契约

### 3.1 状态、搜索和列表

- 查询 `null` 表示搜索未打开，`""` 表示打开但为空；清空文本不关闭搜索，关闭搜索回到完整历史。输入值在输入事件中立即更新，不等待数据库、网络或 debounce；控件保留选区和 IME composition。
- controller 只有一个查询订阅所有者：输入流去重后 `flatMapLatest(GetHistory.subscribe)`。旧查询完成不能写回输入值或覆盖新结果；取消异常不转成错误。UI 不另建 effect 重复查询。
- 列表未获得数据、真正无历史、搜索无匹配分别使用原版加载/空状态。仓库结果保持原版每作品最近记录与时间倒序；删除当前最近章后，同作品较早历史可能重新成为展示项，不应错误断言整部作品消失。
- 分组依据本地时区的日历日期，复用原版相对日期、章号和时间格式化语义；稳定实体键不依赖格式化日期文本。保留源章节未知章号显示规则。
- 实际阅读、删除、收藏和同步投影写入后，持续订阅使列表更新；失败通知走既有临时消息生命周期。保留上游错误处理边界，不另加历史网络重试 UI。

### 3.2 导航与续读

| 动作 | 共享决定 | 平台执行 |
|---|---|---|
| 点封面 | 打开确切 mangaId 的详情；不触发行点击续读 | Android MangaScreen；Desktop 现有 MangaDetailScreen，使用正确嵌套 Navigator |
| 点条目非按钮区域 | `GetNextChapters.await(mangaId, chapterId, onlyUnread=false).firstOrNull()` | 有本地目标后打开阅读器；无目标显示原版“没有下一章” |
| 再次点击已选历史标签 | `GetNextChapters.await(onlyUnread=false).firstOrNull()` | 从全局最近历史续读，不受当前搜索结果限制；未选中历史时第一次点击仅切标签 |
| 详情点指定章节 | 打开该章，不能执行历史的自动下一章选择 | 与历史共用打开上下文与 Reader 工厂，保留“指定章”意图 |

历史续读使用已有用例的排序和扫描组过滤，不能改成“总是点中的章”“第一未读章”或“总是同步记录章”。当前章已读时取其下一章，不额外筛成下一未读章。目标被过滤/不存在时也执行固定上游用例的真实边界；其 `fromChapterId` 未命中分支需要共享契约覆盖，不能凭直觉发明回退算法。

历史事件携带稳定对象 ID 和明确意图；事件消费一次。Reader adapter 严格核对目标所属作品和实际章节索引，不能默认 index=0。仅本地查询失败或没有符合规则的本地目标时，沿用历史内部错误/无下一章反馈；源是否安装、网络是否可用、远端目录是否完整不参与历史导航资格。

### 3.3 删除与清空

- 删除按钮打开官方确认弹窗，默认仅移除本条章节历史；勾选后移除该作品全部历史。保存触发时对象 ID，确认时核对有效对象，取消/遮罩/Escape 无写入。
- 直接调用 `RemoveHistory` 的对应 overload，保留重置历史而不是删除作品、章节、文件或重置已读状态的语义。同步屏蔽水位和业务变更保持已有事务，不产生远端历史删除事件。
- 清空历史入口保留原版可达性，不随当前搜索结果为空消失。确认后根据 `awaitAll()` 的实际成功结果发布完成消息，不能忽略 false 并清空内存列表伪造成功；列表以仓库结果为准。
- 单条/整作品删除的仓库目前捕获并记录异常；不借本次全局重写错误策略。测试失败时数据库与 UI 仍保持真实状态，不新增未获上游支持的成功提示。

### 3.4 收藏、分类、重复作品与追踪

- 仅未收藏作品显示加入书架按钮；点击不传播成续读。先按既有重复作品规则检查，存在重复项时显示原版分支：仍然加入、打开已有作品、迁移、取消。
- 采用有效默认分类时直接入库；默认分类为系统默认或没有用户分类时进入未分类；否则显示分类选择。确认所选分类才提交对应收藏/分类操作，取消不提交这些操作。分类管理跳转和返回复用现有页面。
- 共享 `UpdateLibraryMembership` 的原子写入与同步 outbox 语义是项目既有能力，Android 历史入口一并接入；不把新编排扩散为所有页面的收藏重构。失败不留下半份收藏/分类更新，列表收藏图标由真实数据变化驱动。
- 重复作品迁移把官方的 current/target 对象准确传给现有迁移流程，保留原确认和失败处理；不能仅打开无关源选择器或做空回调冒充接入。
- 增强追踪沿用上游触发点：默认分类分支入库成功，或进入“选择分类”分支后，调用可用且接受当前源的增强追踪服务。上游可能在分类确认之前启动此工作；分类取消不等于撤销已启动追踪。此时序是 SOURCE，不在本轮顺带改成另一产品规则。
- 追踪异常按原链路处理，不回滚成功收藏；重复按钮操作避免重复写入。真实认证、匹配和状态更新复用现有平台服务，测试使用隔离服务/HTTP 夹具，不连接真实账号。

## 4. 界面与平台契约

共享 `HistoryContent`、历史条目、分组与删除弹窗；顶栏复用或迁移原版所需最小组件。平台壳只挂接状态、生命周期和事件，不保留第二套列表业务、选章或删除规则。

| 场景 | 固定入口和反馈 | 返回、焦点及副作用 |
|---|---|---|
| 历史根页 | 原版顶栏标题、搜索及清空动作；历史分组列表 | 根页不强加返回；再次选标签续读最近记录 |
| 搜索 | 展开后聚焦输入框；清空/关闭分离；结果实时更新 | Desktop Escape 退出搜索，Android 沿用平台返回/IME 行为；刷新结果不抢光标 |
| 条目 | 封面独立进详情；正文续读；未收藏按钮和删除按钮 | 移除历史专属播放、准备、取消准备、重试目录和降级按钮；可访问名称表达真实动作 |
| 弹窗 | 单条/整作品删除、清空、分类、重复作品使用明确确认与取消 | Tab/Shift+Tab 留在弹层，Escape 只关闭顶层；背景不响应操作；关闭还焦触发器 |
| 从详情/阅读器返回 | 保留查询、搜索开关、列表锚点与滚动位置 | 焦点回原动作对应的有效条目/封面；条目消失则回合理列表或顶栏位置，不抢正在编辑的文本焦点 |
| 请求完成前离页/删除/切换对象 | 本地导航事件失去原会话资格，不迟到 push | 禁止连点堆叠阅读器；已打开的阅读器不因历史订阅变化被关闭 |

视觉 SOURCE：原版条目常规高度 96dp、标题最多两行、Book 封面 2:3、章号/时间组合、副操作为收藏和删除。PROJECT_POLICY：共享历史条目采用这些基线，移除当前 Desktop 独立卡片样式；仅历史封面采用共享 Book 比例，不把现有书架/详情 7:10 全局改写。大字体时允许条目按内容增长，双端采用同一规则；这是明确的无障碍适配，不伪称原版固定高度会自动增长。

主题、图标和本地化复用项目现有资源；不新增固定颜色体系。Windows/macOS 原生焦点、鼠标、键盘与 Android 触摸/IME 是允许的平台差异，不能作为缺少业务功能的理由。验收记录真实平台、语言、主题、窗口尺寸与缩放；没有原生视觉证据不能宣称像素一致。

## 5. Reader 与同步扩展边界

### 5.1 已知章节立即进入

调用链固定为：历史共享 controller → 官方本地选章 → 平台 Reader 入口 → 已挂载阅读器加载当前章。历史 controller 不持有 SourceManager、目录准备服务、下载管理器或远端请求状态。下载与内容路由由已有 Reader adapter 解析。

Desktop 历史和详情共用一次本地上下文组装，保留本机全部合格章节 refs、实际目标索引、viewerFlags、下载资格及阅读模式。历史与详情可以具有不同打开意图，但不会因为入口不同而遗漏 refs。内容加载失败交给现有阅读器错误链路；目录接口失败不能被当作当前章页面失败。

### 5.2 同步页码与因果快照

- 本地已经投影的已读状态参与官方选章。取消历史页先用 `resumePosition` 覆盖所选章节的特例，Android/Desktop 同时调整。
- 官方选中的目标与有效同步续读章相同、且应继续未完成阅读时，才采用该章的同步页码和对应 snapshot；已转到下一章时不能携带前一章的页码或快照。
- “打开指定章”遵守原有手动选章/已读重开页规则；不得被作品级同步恢复重定向。需要新会话基线时复用 `beginSyncSession`，不伪造或清空合法因果信息。
- 目标、初始页和 snapshot 必须对应同一有效阅读上下文。复用现有事务读取；若需增加窄的快照读取组合接口，仍调用唯一选章规则，不复制 `GetNextChapters`。同步在边界处到达时允许下一次进入采用新结果，不拼接两个版本的章页。
- Reader 已打开后，新同步和目录刷新只能更新允许的非会话元数据，不能自动跳章、跳页或更换已采用因果基线；正向阅读继续走生产 tracker。

### 5.3 同步目录补全的明确选择（PROJECT_POLICY）

原版不包含本项目跨设备同步后只落入部分章节的状态。本设计保留原修复的目录补全能力，但限定为两端共用规则的 Reader 扩展：

1. 只有阅读器已经挂载并开始独立加载当前章，且存在与当前作品/章节精确匹配的已采用同步上下文，才检查既有原始目录观测。没有同步上下文的普通历史阅读不新增远端目录请求。
2. 完整性使用现有确切自然键、COMPLETE、章数、首取与顺序证据；不是“少于两章”或 `initialized` 判断。完整目录不请求；有同步上下文且证据不完整时，每次阅读器会话最多发起一次有界准备。同作品与详情刷新复用已有 in-flight 执行，失败不因重组循环重试。
3. 准备与当前章页请求独立：用闸门挡住目录响应时，阅读器必须已经出现并能加载/阅读当前章。源目录 403、超时、空响应或存储错误不覆盖当前章页面状态，不把用户送回历史。
4. 成功仅更新邻接上下文：按当前有效 chapterId 重建索引，保留页、配对、阅读模式与 snapshot，不重新创建 reader session。跨作品、已关闭会话及迟到结果不应用；目标不在已验证目录中时保留当前内容，不凭插入位置猜邻章。
5. 失败保留本机已知章及下载。当前页面本身失败时使用 Reader 原有错误与重试；只有目录准备失败时不另造历史通知。用户可使用详情现有刷新入口重试目录，再进入阅读器。取消阅读器等待不破坏仍被详情使用的请求。
6. 非删除合并与目录观测核心抽至共享 data，Android/Desktop 的这一窄扩展共用它；平台源 fetch/下载身份继续适配。Android 常规 `SyncChaptersWithSource` 含下载更名和删除副作用，不能整体搬入该后台补全链路，也不改变它原来的详情刷新功能。
7. 如果原版本地选章返回无下一章，历史页仍显示无下一章；不能为了启动补全而偷偷重读已完成章。此时使用现有详情刷新补齐目录后再续读。这是本地原版选章与同步扩展的明确边界。

网络不进入同步接收事务，不在应用启动/后台同步中全库抓取，不新增来源探测、扩展安装、账号授权或设置项。不确定是否存在同步上下文时按普通本地打开处理，不从标题、章号、章数或空闲时长猜来源。

### 5.4 既有数据安全不变量

保留原 HR01 的确切身份校验、URL 去重、已有 chapterId/historyId、读状态/页码/书签、下载身份、非零首取时间、收藏/分类、历史屏蔽和事务回滚。网络在事务外，bootstrap 在短事务外等待；作品重读、章节合并及观测提交须原子完成，吞掉的写入异常通过实际读回校验变成失败。

未收藏/无作者作品的 COMPLETE 观测必须真实存在；书架索引解除 `manga_id` 关联不能使同自然键缓存失效。补全不产生伪用户阅读/收藏 outbox，不自动删除源已移除的旧章。无需 schema、同步协议升级或全库数据修复；若现有接口不能保证这些不变量，先保留失败证据并界定最小缺口，不扩大为同步引擎重构。

## 6. 固定验收矩阵

以下是固定的必做契约，均交付于 roadmap 的 HP01，HP02 补最终矩阵和原生运行证据；实际进度与失败边界见聚合证据。测试名称由实施时沿用实际文件，不能仅扫描符号或复制 production 算法。

| ID | 前置与真实操作 | 必须断言 |
|---|---|---|
| P01 | 本地列表初始加载、无历史、搜索无结果；阅读/收藏/同步更新数据库 | 加载/两类空状态和日历分组正确；每作品最近记录、时间顺序与源文案一致，列表持续更新 |
| P02 | 搜索连续输入英文、中间插入/删除、选区替换、中文 IME；旧查询延迟后到 | 输入即时、选区正确、无首字倒插；结果只对应最新查询；清空/关闭分离，无重复订阅 |
| P03 | 点击封面 → 详情 → 返回；点击正文 | 封面只进指定详情，正文只触发续读；返回保留查询/滚动，导航类型正确 |
| P04 | 当前章未读/已读，下一章已读/未读，无下一章，排序/扫描组过滤/目标未命中 | 完整执行固定上游 `GetNextChapters` 契约，无 Desktop 特例、无虚假 index=0 |
| P05 | 非历史标签点击历史；已在历史再次点击，当前搜索有/无结果 | 首次只导航；再次按全局最近历史续读，无目标有原版反馈，一次动作至多一次 Reader |
| P06 | 单条删除弹窗默认/勾选全部/取消，作品有多章历史 | 正确调用对应范围；单条删除后较早记录允许出现；取消不写、章节及已读不被重置 |
| P07 | 搜索无结果时清空；取消、真实成功、注入存储失败 | 清空动作可达；仅成功反馈完成，失败不伪造空列表；既有屏蔽和事务保持 |
| P08 | 未收藏加入：默认分类、无分类、每次询问、取消、写入失败 | 分类与收藏遵循官方流程及共享原子写入；图标由数据库驱动，无半写或重复 outbox |
| P09 | 重复作品弹窗：取消/仍加入/打开已有/迁移；分类管理往返 | 真实目标正确、导航可返回、迁移确认和结果接线有效，不为空回调 |
| P10 | 匹配/不匹配增强追踪、未登录、HTTP 失败、选择分类后取消 | 两端采用官方触发点；复用真实服务链，错误不回滚已成功收藏，未授权真实账号不访问 |
| P11 | 普通本地三章历史、缺源、离线；目录端点失败但当前页或下载可读 | History 不调用目录端点；有本地目标就进入 Reader；当前页加载/失败属于 Reader |
| P12 | 历史续读和详情指定同一章，执行前后章按钮、键盘与章末转场 | 使用同一 Reader 上下文/工厂、实际 refs 与目标索引，指定章不被改成下一章 |
| P13 | 同步未读页、已读章转下一章、选章期间新同步、阅读中新同步 | 页码/snapshot 只属所选目标；已打开会话不跳章/页，tracker 与因果基线正确 |
| P14 | 两个文件库真实同步只投影中间未读章；目录响应闸门延迟、成功/失败、退出后迟到 | Reader 先可读；一次有界补全，成功增加真实邻章且不重建当前会话；失败/迟到不影响当前章 |
| P15 | 完整/真实单章目录；无作者未收藏冷启动；真实 SQL 写失败、元数据期间阅读更新 | 完整不重取；观测可重开复用；失败回滚、稳定 ID/阅读状态/下载/清除水位保留，无伪同步操作 |
| P16 | 双端浅/深色、中/英文、长标题、窄窗和 200% 字体 | 共享原版列表动作和层级，Book 比例及图标正确，内容/确认动作可达，无 Desktop 专属预检 UI |
| P17 | Tab/Shift+Tab/Enter/Space/Escape、弹窗取消、连点、返回、后台列表更新 | 焦点不穿透或抢输入；触发对象稳定，晚到事件不导航；关闭只消费一层 |
| P18 | 两端真实 DI/页面挂载及 Desktop `history_select` | 共同 controller 真正被使用；破坏注入、动作、目标或 refs 映射时行为测试失败，Test Mode 不走合成 Reader |

共享契约同时由 JVM 与 Android wrapper 执行；真实持久化用文件 SQLDelight，HTTP 用 MockWebServer 和 production parser。受影响 HTTP adapter 覆盖成功、空/缺失数据、403/429/500、畸形响应；目录阻塞不依赖任意 sleep 阈值判断，使用可控闸门和可观察 Reader 状态。

Desktop Test Mode 删除旧历史专属 `history_retry`/`history_read_existing` 的产品绑定并同步 API 文档/客户端测试；遗留调用明确报告不支持，不继续偷偷走旧预检。新的语义动作复用既有测试基础设施，不新增任意 SQL、源 URL、用户目录或桌面像素接口。

## 7. 对旧计划的承接与维护

| 旧 H 契约 | 新处理 |
|---|---|
| H01/H12/H15：真实 refs、邻接、production wiring | 保留并纳入 P04/P12/P18，统一历史与详情上下文 |
| H02/H03/H04/H05/H06：稀疏补全、缓存与请求合并 | 数据能力保留；历史前置准备改为第 5.3 节 Reader 扩展，对应 P14/P15 |
| H07/H08：源/目录失败、身份、目标丢失 | 数据保留与错误分类保留；历史行重试/降级被本次用户要求替代，对应 P11/P14/P15 |
| H09/H10/H11：事务、同步与状态安全 | 保留，纳入 P06/P07/P13/P15；同步“无条件优先选章”改为官方选章后的同章页码恢复 |
| H13/H14：生命周期与可达性 | 改验原版动作与平台必要焦点，不再验被移除的准备按钮，对应 P03/P16/P17 |
| 旧 HR01 完成、HR02 未完成 | 保留历史状态和原证据；后续不再按旧 UX 收口，不复用旧通过结论代替新行为验收 |

本次替代依据是用户明确提出的原版一致要求，不是降低测试标准。有效旧数据测试可继续复用；相反行为的测试必须替换为新契约的正确红测，不能简单删除断言获得通过。

维护顺序：固定上游行为 → 共享契约 → 双端 production 接线 → 平台原生验收。需要平台差异时记录具体 API/输入设备限制、最小 adapter 与对应测试；不允许以实现省事、旧 Desktop 已这样工作或测试已绿作为差异理由。

故障诊断优先核对真实动作、选章结果、Reader 上下文与持久状态；日志只含脱敏身份关联、阶段和错误类别。用户内容、令牌和完整数据库不得进入证据。回退采用正常 Git revert/兼容正式候选，不回滚用户数据，不删除补全的正确章节。

## 8. 已落地接口与维护边界

以下记录已经通过接口审查的实现结构，不代表 HP01/HP02 已完成；剩余接线、回归和正式运行状态以聚合证据为准。

- `domain/.../history/service/HistoryController.kt` 是查询、分组和历史对话框的唯一状态所有者。输入 revision 与已加载结果 revision 配对，平台不得另订阅一个列表或以旧列表表示新查询已完成。收藏编排通过 `HistoryFavoriteActions` 调用已有原子会员写入，增强追踪仍由平台 adapter 执行。
- Reader 请求资格在实际点击入口同步捕获，选章可以异步等待，实际导航前消费同一 token 一次。离页、封面/分类等其他导航、弹窗和删除使未交付请求失效；返回页重新激活，不能重放旧点击或关闭已挂载 Reader。短锁只保护 epoch/资格，不等待数据库或网络；Android 标签重选只调用当前挂载的 callback，不能用等待返回页 collector 的 rendezvous。
- `presentation-history` 是范围限定历史页的共享 Compose 模块；两端壳消费同一内容和动作。日期显示保留已有相对日期、日期格式偏好，历史 Book 比例不改变其他 Desktop 页的封面规则。
- 共享内容保留查询、列表位置与动作焦点 anchor。实际键盘移动会更新 anchor，编辑器获焦清除旧条目 anchor；关闭弹层或页面返回恢复有效触发器，条目消失则回顶栏。数据库更新不得抢走当前编辑/手动选择的焦点。Escape 由当前弹层消费一层，不能同时关闭背景搜索。
- `RecordReadingProgress.openChapter(ReaderChapterIdentity)` 委托 `SqlDelightReadingProgressRepository`。作品 ID/source/URL 与章节 ID/URL 在同一事务内核对；只在未读同章恢复时采用同步位置，否则使用所选章的本地页和合法 snapshot。接口只读，不创建阅读事件、历史、outbox 或会话。拒绝身份不符时返回 null，不以 index=0 或另一章代替。
- Desktop 历史和详情通过 `selectedDesktopReaderOpenContext` 组装同一 production Reader；Android ReaderViewModel 消费同一原子上下文。章节排序共用 `getChapterSort`：Desktop 使用 `sortDescending=true` 保持 ReaderNavigator 的 newest-first 内部约定，Android 使用 oldest-first；原始数组方向可以不同，实际前后章含义与用户排序设置必须一致。当前章对象、初始页和已采用 snapshot 是会话状态；后续同步与目录结果不得覆盖这些状态。
- `ReaderCatalogCompletion` 每个已打开 Reader 只尝试一次，仅允许带有效 scope 的同章同步恢复，等待上限为 30 秒。完整性判断由平台 preparation port 读取既有观测；COMPLETE 不请求源。准备失败或超时保留当前内容；外层取消继续传播。用户通过详情既有刷新入口重试，不增加历史行重试按钮。
- 共享 `data/.../chapter/SourceChapterCatalogWriter.kt` 保留非删除合并、真实读回和观测事务。Desktop 原名称为 adapter/typealias；Android `AndroidReaderCatalogPreparation` 使用相同核心，不借用含下载更名、删除副作用的常规章节刷新。bootstrap 等待和源抓取在短事务外，合并时再次核对当前作品身份及目标章。
- `SourceMangaUpdateService.awaitSharedCatalog` 是 Reader 补全与 Android 详情更新的窄 opt-in。按源实例、sourceId、作品 URL 合并重叠目录请求，最后等待者退出才取消共享执行；Reader 先取目录时，晚加入详情只补 metadata。原 `await` 的 flags、opaque memo 与错误/取消语义保持不变；内部传递 `Result` 保留原 source 异常对象，不能放宽既有异常契约来迁就 Deferred 栈恢复。
- 邻接装配与后续本地查询也属于后台失败边界。Desktop 用 activation epoch、Android 用当前阅读激活/window sequence 拒绝关闭、换章及返回同章后的旧结果；只隔离非取消的 Exception，保留现有当前章对象、页面、模式、配对和因果基线。导航、预取与 Test Mode 应从实际挂载 Reader 的最新 refs 读取，不能另行合成邻章。

本次没有 schema 或同步协议变更。后续修改上述接口时，先运行双端共享文件库契约，再运行受影响 Reader 消费者和实际导航/DI 测试；完整模块测试与正式构建仍只在当前 roadmap 最终收口执行。旧测试若与固定上游行为冲突，应以新的正确红测替换；若只是 fixture 身份与真实种子不一致，应修夹具，保留原页码、书签、配对、隐私和持久化断言。
