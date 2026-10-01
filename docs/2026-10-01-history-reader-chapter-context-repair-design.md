---
status: planned
date: 2026-10-01
---

# Windows 历史续读与同步后章节目录修复设计

## 1. 目标与事实边界

用户反馈：Windows Desktop 从其他设备同步来的【阅读记录】打开作品，只能阅读该章，不能跳转前后章节。目标是让历史入口使用真实作品目录；接收端尚未加载目录时按需补载，并保留同步进度、历史、本机下载及当前阅读会话。

源码基线：`main`，`5affb165495dff62dfbadf420c0f3e94bb971ace`。本轮是代码诊断和规划，尚未做发布运行时复现、代码修复或产品测试。未读取用户实际数据库，因此不能断言用户作品的完整目录曾被删除。执行前核对分支、HEAD、用户实际版本和工作树；本仓库已有其他任务的原型与文档改动，禁止覆盖或混入提交。

关联文件：[执行 roadmap](roadmap/2026-10-01-history-reader-chapter-context-repair-roadmap.md)、[Desktop UI 规范](design/mihon-desktop-ui/README.md)、[组件事实表](design/mihon-desktop-ui/desktop-reference.md)、[页面契约字段](design/mihon-desktop-ui/page-contracts.md)、[同步技术方案](2026-09-13-multi-device-sync-technical-proposal.md)。本设计不激活或修改其他 roadmap。

| 分类 | 已读取依据 | 结论与限制 |
|---|---|---|
| SOURCE：直接缺陷 | `HistoryScreenModel.readerRequestFor` 与 `HistoryRootScreen.Content` | 只解析目标章；请求不含作品章节列表，导航也未传 `chapters/currentChapterIndex`。本机有完整目录仍会受影响 |
| SOURCE：阅读器消费 | `DesktopReaderScreen.chapters` 默认为空；`readerNav` 只从传入列表构造；`requestAdjacentChapterTransition` 无目标返回 false | 不自行补载目录；前后章按钮、键盘及章末转场都依赖相同导航对象 |
| SOURCE：同步写入 | `SyncRemoteProjectionWriter.ensureManga/ensureChapter/applyHistory` | 准确重建阅读效果涉及的作品与章节，不承诺传输完整源目录；已有自然键记录复用。该链路未发现删除完整目录的操作 |
| SOURCE：详情补载 | `MangaDetailScreen` 的首次加载 effect 在章节非空时直接返回 | 同步生成一章即可阻止自动目录加载；`SaveSourceMangaForDetails.awaitListedForDetails` 已有另一套 `!initialized || empty` 判断，入口不一致 |
| SOURCE：顺序更新 | `SaveSourceMangaForDetails.await` 与 `LibraryUpdateChecker.checkForUpdates` | 新章节赋 `sourceOrder`，已有章当前只按需更新章号/memo；不能保证旧同步章在补载后有正确前后章顺序 |
| SOURCE：已有观测 | `ChapterCatalogCompleteness`、`author_archive_source_works`、`CreatorArchiveRepository.updateSourceWorkCatalog` | 已有 UNKNOWN/PARTIAL/COMPLETE 与目录章数。写入目前是 UPDATE，不保证未归档作品有行；`last_seen_at` 也不是专用目录抓取时间 |
| SOURCE：测试旁路 | `HistoryTestModeController.select` 调 `TestNavigationController.openReader`；后者传空目录、合成 tracker 和模拟边界 | 现有 history_select 成功不等于真实前后章可用；本轮必须补真实 production 路由验收 |
| SOURCE：Android | `ReaderViewModel.getChapterList` 查询数据库；`SyncChaptersWithSource` 按 URL 匹配并更新 sourceOrder | Android 历史入口不采用 Desktop 的空列表传参方式；Android 刷新也有空目录判断，不据此宣称 Android 补载不存在类似风险 |
| 已证实范围 | 上述源码与同步接入前历史入口的 Git 版本对照 | 历史漏传目录是既有 Desktop 缺陷；同步后的稀疏数据暴露了另一处补载缺口。用户设备中两者各自影响仍需隔离运行验证 |

## 2. 范围与复用决定

### 2.1 本次必须完成

- Windows/Desktop 历史阅读按钮传入完整、正确排序的本机目录及当前章索引；同步续读章页与会话快照保持现有规则。
- 历史进入与详情打开共用按需目录准备能力，修复“有一章就不加载”的判定；同作品并发请求合并，失败可重试。
- 从完整源响应补齐缺章，并校正已有章 `sourceOrder`；稳定 URL 对应的本机 ID、阅读状态和资源关联保留。
- 旧稀疏记录不要求删除历史、取消收藏、重新同步或全库修复；第一次访问该作品即可恢复。
- 真实同步投影 → 本机数据库 → 历史点击 → production 阅读器 → 前后章切换的集成与正式运行证据。
- Desktop 共用代码在 macOS 做正式构建和运行验收；共享 data/domain 修改在 Android 上运行契约与最终回归。

### 2.2 边界

不扩展同步协议，不上传/下载整部作品目录，不在收件事务、后台同步或应用启动时批量抓取源，不自动安装扩展或登录源。不修改因果合并、同步安全、历史屏蔽、阅读进度协议、书架续读目标选择或阅读引擎。Android 历史/详情产品入口修复不在本次交付范围；共享接口兼容及数据契约必须验证，如证实需改 Android 用户行为，先提交证据和追加范围。

【更新】入口有同类漏传目录迹象，但属于另一个操作入口，本计划只记录关联风险，不顺带修复。跨源迁移、URL 变更匹配、章节删除策略、下载目录更名及旧库重复自然键清理也不加入本次修复。

### 2.3 复用清单

| 需要的能力 | 决定 |
|---|---|
| 目录读取 | 复用 `GetChaptersByMangaId.awaitOrThrow`、`GetMangaWithChapters`；读取失败不能通过吞异常变成空目录 |
| 前后章与跳章 | 复用 `toReaderChapterRefs`、`ReaderNavigator.indexForId`、共享 `ReaderAdjacentChapterPolicy`；与详情/书架相同顺序和筛选语义 |
| 源获取与错误 | 复用 `SourceManager`、`SourceMangaUpdateService`、`safeSourceCall` 和现有 `AppError`；源仍调用自己的真实 parser/扩展 adapter |
| 落库与刷新反馈 | 扩展 `SaveSourceMangaForDetails` 的现有链路；历史与详情均调用它，不再建历史专属 HTTP 客户端或 parser |
| 目录完整性 | 复用现有归档作品目录字段；给现有 repository 增加按确切作品自然键读取的窄接口，未建行时走现有 `upsertSourceWork` |
| 章节更新 | 在现有持久化链路中补 URL 去重和 sourceOrder 更新；相同逻辑供 `SaveSourceMangaForDetails` 与 `LibraryUpdateChecker` 共用，不保留两份落库算法 |
| 事务 | 复用 `DatabaseHandler`；Desktop adapter 管理生产事务，网络调用置于事务之外；不发明新的通用事务框架 |
| 续读 | 保留 `RecordReadingProgress.resumePosition` 与 `ReadingSyncSnapshot`，复用实际进度 tracker，不重建另一套位置选择算法 |
| 导航与验证 | 沿用 Injekt、Voyager、现有 Compose 测试宿主及 Test Mode；真实历史路由与纯合成阅读 fixture 明确分开 |

Android 的 `SyncChaptersWithSource` 依赖 Android 下载器、文件更名及删除/重建副作用，不能整类注入 Desktop。本次只修 Desktop 已有非删除式目录补载：按同源作品 URL + 章节 URL 匹配，校正顺序，并保护状态。`ShouldUpdateDbChapter`、章号识别与共享导航规则可直接复用；不为了本缺陷抽取整套 Android 下载/删除流程。

本次既有章的名称、扫描组、下载路径继续沿用现有 Desktop 行为，只追加必要的章号/memo/sourceOrder 元数据更新；它们不作为读进度或身份依据。下载更名的上游对齐是既有 adapter 限制，不能借本轮暗改名称造成已下载内容丢失，也不能声称全部章节刷新语义已与 Android 对齐。

## 3. 目录准备契约

以下类型/方法名是设计建议，实施时沿用现有命名；必须保留行为契约。

### 3.1 本机状态判断

输入：确切 `mangaId/sourceId/url`、未应用显示筛选的本机章集合、同作品的持久目录观测，以及当前入口操作 ID。先完成 creator bootstrap，再进入短查询/写事务；不在事务内等待迁移或源网络。

| 状态 | 判定与行为 |
|---|---|
| 本地源 | 使用现有本地作品/文件链路，不对本地文件按网络目录补载；无法对应的同步本地记录沿用待匹配语义 |
| 已有已观测目录 | 确切作品观测为 COMPLETE，原始本机集合无重复自然键、数量与观测一致、sourceOrder 是连续唯一的 0..N-1，且每章有实际源首取时间 → 直接复用本机目录；不因只有一章发请求 |
| 未知或不完整 | 无观测、UNKNOWN/PARTIAL、空集合，或观测与本机集合不一致 → 按需请求一次；`initialized=true` 也不能跳过 |
| 查询失败/身份不一致 | 报告数据库/身份错误；不归类为空目录，不请求另一个来源或同名作品 |
| 源缺失/不可用 | 保留已知章和记录，给出源不可用反馈及降级阅读路径；不伪装成完整目录 |

COMPLETE 表示曾收到并成功保存该源的完整响应，不证明源今天仍没有新章；日常检查更新仍由原入口负责。章数和 `initialized` 都不能单独证明完整，也不从 `last_seen_at` 推断目录刷新时间。`dateFetch == 0` 只表示缺少实际源首取证据，不能单独证明这部作品只有稀疏章；与目录观测/顺序结合保守准备。同步重建章默认首取时间为 0，旧补载链路又不补写该字段，因此这一检查也覆盖旧 COMPLETE 观测下尚未验证的同步章。历史旧数据无观测但实际完整时，首次在线访问允许保守刷新一次，成功记录后跨进程复用；离线不阻断已知内容阅读。

确切观测读取应复用 `CreatorSourceWorkKey`，只允许现有 `legacy-manga:` + 同 mangaId 的明确兼容关系；不按作品标题、作者或章号关联。使用有界自然键查询，不枚举整个作者归档。先确保 source-work 行存在，再写 COMPLETE，写后读回核对章数和身份。无作者、未收藏作品也必须落到真实行；不能调用成功但 UPDATE 0 行仍返回已准备。仅取章节时不伪造 `detailsFetchedAt`，建立作品行不生成关注/收藏或推断作者关系。

### 3.2 获取、校验和落库

1. 相同 `(sourceId, mangaUrl)` 的历史、详情和手动刷新共享现有刷新 owner 内的一次执行；返回可等待的结果，不用 `Job.join()` + 无 error 的猜测当成功。同作品并发单次获取/落库，不同作品结果隔离；不额外创建应用级后台任务。
2. 源获取复用 `SourceMangaUpdateService`；需要元数据时取详情+章节，已有详情时可只取章节。沿用 `safeSourceCall` 的真实超时预算；不增加无界重试。加载失败只在用户点击重试或重新进入时重试，不因 Compose 重绘形成循环。
3. 按 Android 的 stable URL 语义处理重复：合法同 URL 重复项保留首次出现，去重后赋连续 sourceOrder；空白/非法身份、畸形响应视为失败。网络源空目录沿用 `NoChaptersException` 的用户语义，不把它标为完整成功并清除旧数据；本地源的合法空集合走本地链路。
4. 在短事务内重读作品及现有章，按 URL 增加缺章，更新已有章的必要源元数据和 sourceOrder。网络等待期间发生的新阅读/同步进度不能被旧内存快照覆盖；`ChapterUpdate` 不携带 read/bookmark/lastPageRead，更不能用整个旧对象替换。
5. 保留既有 chapterId/mangaId、history 外键、阅读进度与快照、已读/书签、memo 的既有源合并语义、非零首取时间、分类、收藏、阅读模式和下载身份。仅已在本次真实响应中匹配、且 `dateFetch == 0` 的章补写本机第一次实际源获取时间；已有非零值不覆盖，未匹配旧章仍保留原值。不删除不在返回目录的同步章或旧章，不生成收藏/取消或阅读 outbox，不自动下载。
6. 按源响应校正已匹配章的顺序，即使章号/memo 未变也必须更新 sourceOrder；不能按章号或远端设备的旧顺序重新排序。任意存储步骤失败整体回滚，不留半目录或虚假的 COMPLETE。
7. `ChapterRepositoryImpl.addAll` 当前会捕获异常并返回空集合；事务 adapter 必须验证新增结果/最终 URL 集合，让吞掉的失败重新成为事务失败。只在需要时增加严格生产写入入口，不全局改变无关 repository 错误策略。
8. 成功后写目录观测，并返回经过本次验证的章集合。归档观测、章节写入及必要作品更新同一事务完成；按需准备不重新定义 `Manga.initialized`，不把它当章节标志。

若源返回目录已不包含目标章，保留旧记录，反馈“本章不在最新目录中”，不猜测替代章节。已有下载或仍可直接读取时允许显式打开当前章；不能把旧章插入新目录后伪造邻接关系。其他保留但未在本次源目录中确认的章不参与本次已验证邻接列表；跨进程遇到本机数量与观测不一致时重新验证，离线则降级。对同 URL 的历史重复本机行只报告身份冲突，不自动合并或删行。

## 4. 历史 → 阅读器契约

### 4.1 请求与快照

扩展 `HistoryReaderRequest`，带作品完整上下文：`chapters`、`currentChapterIndex`、`chapterNumber` 及已有 mangaViewerFlags/resumeSnapshot。提取最小复用映射，把请求转换为 `DesktopReaderScreen`，真实历史点击与 Test Mode 的真实历史动作调用同一 mapper，移除其任一字段应让集成测试失败。

顺序沿用 Desktop 详情/书架的 sourceOrder 从小到大（新到旧）；较低索引是下一阅读章，较高索引是上一阅读章。构造 refs 时沿用扫描组、跳过已读/筛选/重复、下载状态及外部章节排除规则。完整性判断必须先于显示筛选；当前目标必须合法属于该作品，并在可用章集合中定位索引，不准静默以索引 0 打开别章。

历史按现有规则优先使用可用同步续读目标，再回退所点历史章；无可用目标时说明原因。目录准备结束后、请求发布前取得最终续读章页和同步快照；与生产进度仓库的事务快照契约一致。此后打开中的会话不因后续同步或目录刷新而替换当前章页/因果基线，避免加载途中或阅读中跳章；下次进入才重新选择续读位置。

同一操作生成一次导航，点击连发不叠加阅读器。关闭页面、取消准备、移除该历史项、切换目标作品后，晚到结果不能导航；切换查询不改变已确认操作的对象 ID，若条目已不可用则取消导航并反馈。主题/尺寸变化不重启请求、不抢焦点。

### 4.2 Desktop 页面契约（PROJECT_POLICY）

本次不做 HTML 原型；HTML_ADAPTER 不作为任何验收证据。复用现有历史页、详情刷新入口、Material 控件、通知与主题，不新增独立同步页面或复杂设置。

| 页面/状态 | 所有者、入口与反馈 | 返回、焦点及副作用 |
|---|---|---|
| 历史根页，空闲 | `HistoryScreenModel`；点击既有阅读按钮 | 保留搜索和列表位置，实体按 mangaId/chapterId/historyId 隔离 |
| 准备目录 | 该次历史操作；行附近显示“正在加载章节”，阅读按钮防重复，可取消 | 取消只取消该入口等待和导航资格；共享刷新仍有其他使用者时不全局中断。不阻塞清除/搜索等既有页面能力 |
| 成功 | 一次导航到既有阅读器；保存原返回目标 | 上一/下一章、键盘及章末切换一致；返回历史恢复查询/滚动，焦点回原可用阅读按钮 |
| 目录加载失败 | 原历史页保留条目；显示具体源/网络/存储原因及“重试加载章节” | 有可用已知章时提供“使用已有章节阅读”，标明章节列表可能不完整；不使用“仅当前章”误述已有多章情况 |
| 源不存在 | “源不可用”；保留记录，已有下载可继续阅读 | 不自动安装、不弹授权、不把失败当历史项已删除 |
| 目标已不在源目录 | “本章不在最新目录中”；重试或显式打开可用当前内容 | 无可证明前后章时不提供假的邻接；无内容则留在历史，用户可主动从作品其他入口选择 |
| 详情首次进入 | 使用同一目录状态判断和刷新 owner；非空稀疏列表仍加载，旧列表可见 | 加载/失败与既有刷新提示一致；手动重试后列表更新，不重置选择或定位到另一作品 |
| 正在阅读时收到新同步 | 沿用当前已采用会话 | 不主动关 reader、跳章、跳页或改变模式；返回再进入使用最新候选 |

加载/失败消息生命周期归本次操作；返回后不重播成功通知。失败时重试、降级和取消都是安全非删除动作，不加危险操作确认。既有清除历史确认与本机屏蔽水位保持原语义；加载或源补载不能重新显示已清除的旧历史。

## 5. 冻结验收矩阵

| ID | 输入/真实操作 | 必须断言 |
|---|---|---|
| H01 | 本机已有三章，历史点击中间章，不涉及同步 | 请求携带三章及真实中间索引；点击前后按钮、键盘、章末动作能进入正确目标，边界无假目标 |
| H02 | 同步在干净接收库新建作品+中间章历史，源返回完整三章 | 按需加载一次，原中间 chapterId 保留；historyId/阅读页/快照保留，前后章均可读；没有新增伪用户同步事件 |
| H03 | 旧稀疏数据 initialized=false/true，含旧 COMPLETE+dateFetch=0，章号/memo 不变但远端顺序旧 | 不以非空/initialized/旧 COMPLETE 跳过；已有章 sourceOrder 更新、缺失首取证据在真实匹配后补齐；重开使用已成功观测，不要求清理用户数据 |
| H04 | 真正单章作品，首次载入后重新进入 | 一章是合法目录，两侧自然禁用；有 COMPLETE 观测时不每次联网，不按“少于两章”刷新 |
| H05 | 未收藏、无作者作品 → 成功加载 → 数据库关闭/重开 | source-work 观测真实存在、精确关联，不因 UPDATE 0 行每次重复抓取，不隐式收藏/关注 |
| H06 | 历史+详情并发，同一作品；另一个作品同时请求 | 同作品一次源调用/一次落库；取消一个使用者不破坏另一个；结果与焦点不串作品 |
| H07 | 403/429/500、离线、超时、畸形体、空目录 | 保留章和记录、不写虚假 COMPLETE；显示正确原因，重试走真实请求，已有下载/已知目录可显式降级阅读 |
| H08 | 源缺失、目标从源消失、身份冲突、数据库查询失败 | 不猜匹配、不假索引 0、不删除历史；确切失败与降级边界可见 |
| H09 | 章添加/更新/归档观测写入失败，含 addAll 吞异常 | 整体回滚；旧 ID、进度与目录观测不损坏；重试可恢复，没有“成功但半目录” |
| H10 | 源加载期间新阅读进度/同步投影；阅读器已打开时新候选到达 | 写元数据不覆盖新状态；打开采用一致章页/快照；已打开会话不跳页、不改基线 |
| H11 | 已读、书签、页码、时长、阅读模式、下载、分类、历史清除水位 | 补载后状态与资源关联保留；正向实际阅读仍走既有 production tracker，清除的历史不因补载重现 |
| H12 | 扫描组排除、skipRead/skipFiltered/skipDuplicate、外部章节 | 与共享导航契约一致；当前目标仍可正确定位，因筛选确实无邻章时自然无跳转 |
| H13 | 连点、取消、返回、删除目标历史项、晚到异步结果 | 导航最多一次；失效操作不 push；返回恢复历史局部状态，不重复播报 |
| H14 | 深浅主题、中英、窄窗、大字体、Tab/Enter/Escape | 加载、失败、重试、降级入口可达；焦点和返回契约成立，真实 Compose 事件验证，不只检查字符串 |
| H15 | 真实 history_select 的 Test Mode 路径及正式 EXE/.app | 使用与历史按钮相同的请求/mapper/production runtime；前后章状态取真实 session，不依赖模拟 hasNext/hasPrev |

HTTP 验证用 MockWebServer 与最小真实测试源 adapter，覆盖原始响应 → 实际 parser → `SourceMangaUpdateService` → 数据库，不 mock parser。跨设备造数复用现有 journal/descriptor、接收存储及投影，至少两个独立文件数据库；不能只手工 seed 三章冒充同步验收。data/domain 预期双端一致的保留/查询契约由 JVM 与 Android wrapper 运行；Desktop UI、DI、导航是独立平台集成。

## 6. 验证、风险和完成边界

逐项 focused 红→绿→重构；功能批次稳定后受影响单元/集成/wiring/格式检查，并由未实施代码的主代理独立审查。H09/H10/H11 数据安全是阻塞项，失败不可放宽断言。正式 Windows/macOS 运行只能在实施完成、审查和提交后开展；全量 Android/Desktop 组合只在 roadmap 最终验收执行一次。

主要风险：归档观测不落行、sourceOrder 错乱、写入异常被吞、源网络长期等待、同步期间旧快照覆盖、合成 Test Mode 掩盖 wiring 缺陷。以上分别有 H03/H05/H09/H07/H10/H15 对应，不通过即不能宣称修复完成。无观察依据的网络/设备原因保持未知。

默认不增加 schema、同步协议或新状态表；若现有目录观测无法真实表达本契约，保留失败证据并提出最小替代及迁移风险，暂停相关扩展等待决定，不把章数启发式作为伪完整性证据。最终交付包含代码与测试、必要设计更新、一份聚合证据报告和正式 Desktop 产物；无不可替代平台证据时列明待验，不用浏览器/系统 JDK/独立客户端替代生产链路。

后续维护诊断按顺序核对：历史请求实际 refs/索引 → 原始本机章集合与观测身份 → sourceOrder/首取证据 → 刷新结果与事务提交 → 实际 reader session。日志只记录脱敏操作关联、状态、章数和错误类别，不输出真实账号、章节内容、令牌或整个数据库。失败时保留原始数据并让用户重试；不以清空历史、批量改 initialized 或删除同步章修复状态。若正式版本需要回退，使用正常 Git revert/旧正式候选并核对数据库兼容，本设计没有 schema 升级或不可逆数据删除；源补载已增加的正确章节不在回退时清除。
