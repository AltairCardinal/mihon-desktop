# 阅读器共享核心架构

## 双页规则统一计划

2026-10-01 用户要求 Windows Desktop 与 Android 的全部双页业务规则只维护一套。
本轮增量以[稳定呈现设计v3](../2026-10-03-dual-page-stable-presentation-design.md)
及[配套 roadmap](../roadmap/2026-10-01-shared-dual-page-rules-and-repair-roadmap.md)为准：
分类、配对、宽图后策略、槽位、自动匹配、定位意图与异步重排决策均由共享 presentation 层拥有，
平台仅提供事实、执行端口和原生呈现。新策略修复旧奇偶推测覆盖匹配的问题，保留自动对齐及手动调整能力。

DP01已完成共享规则与接线；v3共享接口已通过SP01.A，Desktop协议/几何/导航及SQL开发定向验证已通过。
Android逐槽呈现、源页输入、当前绘制进度及可见内容lease已通过开发ART验收；
B两端原图／EXIF与受影响原生边界门禁已通过，C资源和操作链仍在实施，不能据此勾选完整SP01。
已通过的C窄范围包括共享五页准备窗口、Desktop仅沿正常Ready内容贡献资料、原图片／下载任务真实完成计数；
Desktop跨runtime资源门禁及采样恢复、Android原SSIV／Coil初始解码／系统内存门禁均已通过开发定向验收；
两端慢配对记录不挡图片及原恢复按钮已通过，剩余操作闭环和固定性能对照仍待C，正式三端验收仍归DP02。
下文的Desktop landscape parity option、宽图封面特例、旧恢复门禁和整组绘制门禁等保留为历史迁移记载，
不再授权以旧门禁阻塞首图或重新安排已显示图像。实现进度与正式证据见唯一roadmap及聚合报告，
不能以共享契约代替两端真实呈现验收。单页、Webtoon、同步、进度格式和其他reader架构边界继续有效。

## 状态与目的

本文描述 reader migration 的最终架构和当前边界。当前状态是
`CORE_MIGRATION_CLOSED / NON_UPSTREAM_AUTHORITY_CORRECTED`：

- 已共享并由 Android 生产消费：稳定 session/page 状态、page-list 与单页 materialize executor、唯一
  priority/generation scheduler、encoded store contract、current/previous/next 章节窗口、跨章激活及 settled
  viewport 进度决策；Desktop canonical session 也直接消费同一 scheduler；
- 另已共享：解码/cache contract、宽图拆分/配对纯算法、输入导航、章节过滤、reader entry resolver 和滤镜参数；
- RP-01～RP-03 已建立 Desktop `ReaderPresentationStrategy`、稳定 `DisplayUnitId`/`VisiblePageSet` 与 mode
  registry，单页、Webtoon 和 Dual production selector 均已迁移；
- RD-01/RD-02 已收口 Desktop：一个 Voyager ScreenModel 持有一个 canonical session，四类内容 route、
  encoded store、三种 presentation、进度事务与下一章 P4 policy 均消费 shared contract；
- RA-01 已收口 Android：`ReaderViewModel`、`ChapterLoader`、`HttpPageLoader` 与 `ReaderChapter` 只负责
  lifecycle、source/download/local/cache 和旧 View 状态投影；page-list、page-state、scheduler、window 与
  progress 决策全部来自 shared 实现；
- RG-01 已删除 Desktop Continue/Dismiss 兼容控件与遗留私有进度 helper，并以完整 Desktop reader root 扫描、
  canonical decision 声明/调用 owner 清单、平台禁入、presentation I/O 禁入、`store::read` decoded-only wiring
  和 manifest 关闭字段建立可执行架构守卫。

因此 `domain/src/commonMain/kotlin/mihon/domain/reader/` 是 Android 与 Desktop reader runtime 的唯一
业务决策来源；图形、文件、source、生命周期和输入差异限制在 adapter。剩余 `RV-01` 只做全量、运行时与
正式发布产物验收，不再接受功能实现或第二套兼容链。

固定原版行为、上游修复和 Fork 偏差以
[`reader-authority.md`](./reader-authority.md) 为准；执行顺序和门禁以
[`2026-08-02-reader-core-migration-and-presentation-roadmap.md`](../roadmap/2026-08-02-reader-core-migration-and-presentation-roadmap.md)
为准。

## 目标依赖方向

```text
Android ReaderActivity / DesktopReaderScreen
                    │
                    ▼
           presentation strategy
          Single / Webtoon / Dual
                    │
          DisplayUnit + VisiblePageSet
                    │
                    ▼
             ReaderSessionCore
    session / page state / scheduler / window /
      retry / adjacent policy / progress effect
                    │
                    ▼
          platform-neutral reader ports
                    │
          ┌─────────┴─────────┐
          ▼                   ▼
    Android adapters      Desktop adapters
```

依赖只能向下：core 不知道 presentation mode；presentation 不获取内容或写进度；adapter 不决定业务状态。

## 当前已验证的窄 contract

| 文件 | 当前可引用的证据范围 | 不能据此宣称的内容 |
| --- | --- | --- |
| `ReaderPageModel.kt` | page/chapter DTO、decode/cache contract | 章节窗口、完整 session executor |
| `reader/session/`、`reader/materialize/` | 稳定逻辑页状态、page-list、单页 materialize、三章窗口 retain/release 与幂等跨章激活；Android/Desktop production 已接线 | 平台 I/O、UI 与 decoded bitmap 生命周期 |
| `reader/scheduler/ReaderRequestScheduler.kt` | P0～P4、原版 current +4、稳定 PageId、有界并发、抢占、Retry 与 generation 拒收；Android/Desktop adapter 已接线 | 平台执行、物理 I/O 与 decoded cache |
| `reader/storage/EncodedPageStore.kt` | 生命周期、物理存在性、配额/淘汰结果和诊断；Android `ChapterCache` 与 Desktop encoded store adapter 已接线 | 平台文件格式与 decoded cache |
| `PageTransform.kt` | 宽图尺寸/切片、纯配对算法、滤镜参数 | session core；pairing 属 presentation |
| `ReaderNavigation.kt`、`reader/progress/ReaderEntryResolver.kt` | tap command、inversion、章节过滤、adjacent result，以及不依赖 UI 方向的故事最早未读入口；Android/Desktop 入口已接线 | 跨章 session 激活、进度 |
| `ReaderProgressPolicy` / `ReadingProgressEvent` / `RecordReadingProgress` | settled active viewport → identity-bearing progress effect、末页完成和幂等事务；Android/Desktop production 已接线 | history 计时与平台 tracker adapter |

parity manifest 9/43/44/45/47/49/51/53/54 通过 `readerCoreMigrationScope` 锁定这些范围，并统一记录
`legacyCleanupTask = RG-01`、`legacyReaderExecutors = REMOVED` 与 `readerArchitectureGuard = ENFORCED`。

## Canonical session core

目标 `domain/.../reader/session/` 至少包含：

- `ReaderSessionSnapshot`：active chapter、previous/next reference、settled viewport、boundary 和一次性反馈；
- `ReaderChapterSession`：`Wait / LoadingPageList / Loaded / Error`；page list 成功前页数为 0；
- `ReaderPageSession`：稳定 `PageId` 与
  `Queued / ResolvingImage / Downloading / Ready(encodedRef) / Error`；
- intent：打开章节、报告 settled viewport、Retry 页/章、打开相邻章、关闭 session；
- effect：进度提交、可见错误、边界反馈和 adapter 副作用，且具备幂等 key；
- current/previous/next window 的 retain/release；
- 有界 priority/generation scheduler 和 adjacent policy。

页状态变化只能替换稳定 item 内的内容层，不能替换 pager/Lazy item 或 zoom container。URL 和
`EncodedPageRef` 是内容，不是身份。

## 调度与数据层边界

优先级从高到低：

1. `INTERACTIVE`：settled presentation 当前可见的所有逻辑页和显式 Retry；
2. `NEARBY`：当前章 active page 之后四页，保持固定原版默认；
3. `CURRENT_BACKGROUND`：Desktop 可选的当前章其余 encoded 内容；
4. `ADJACENT_METADATA`：距末尾不足五页时只建立相邻章 page list；
5. `ADJACENT_BACKGROUND`：Desktop 可选的下一章 encoded 内容。

`ReaderRequestScheduler` 已实现有界的当前 generation 并发、P0 抢占和 generation 迟到拒绝，请求身份使用
稳定 `ChapterId + sourcePageIndex`，不会把相邻章的同索引页合并。Android 默认 policy 保持固定原版串行
current +4，不增加正常请求的网络量；adapter 另以真实 Job completion 释放物理 permit，最多容纳一个不响应
取消的 stale 请求，所以连续快速翻页的真实 I/O 上限为“当前 policy 并发 + 1”，不会随 generation 无界增长。
Desktop adapter 使用同一条物理边界：图片与章节 page-list materialize 共用 permit，只有真实 Job 结束才释放，
因此连续 settle、切换下一章目标或关闭 reader 时，峰值同样不超过“当前 policy 并发 + 1 个 stale 请求”。P3
metadata 在 page list 尚无稳定 `PageId` 时不伪装成图片请求，而由相邻目标 sequence 管理；它必须先完成才会产生
P4 图片请求，激活/换目标会取消旧 sequence。后台请求不能饿死可见页。

RD-02 在 Desktop adapter 中实现 `OFF / FIRST_VIEWPORT / FULL_NEXT_CHAPTER` 三档相邻章图片 decorator，默认
`OFF`。固定原版 canonical metadata 独立读取 shared viewport anchor：进入末五页即可取得下一章 page list，
不依赖首帧绘制且不获取图片。只有用户显式选择 `FIRST_VIEWPORT` 或 `FULL_NEXT_CHAPTER`，当前 generation 已
收到 `FIRST_PAGE_PRESENTED`、当前章全部逻辑页均为 `Ready(encodedRef)` 且 shared scheduler idle 后，才会
为下一章发出 encoded-only P4；`FIRST_VIEWPORT` 只覆盖 presentation 提供的首屏页数，`FULL_NEXT_CHAPTER`
覆盖完整 page list。用户激活下一章时先取消该章仍在运行或排队的 P4，再由 active viewport 以 P0 请求尚未
Ready 的可见页。切到 `OFF`、跳向其他章节或关闭 session 都会取消不再有用的 decorator 请求；stale
generation/target 与重复首帧事件不能释放旧目标工作。

相邻章 Storage 失败的降级副作用还必须同时匹配当前 target sequence 与仍被 scheduler 接受的 request identity；
旧目标即使在取消后迟到返回，也不能设置新目标的 quota block 或取消新目标 P4。

固定原版的末五页 page-list-only 策略与上述图片策略相互独立：进入末五页后，即使 `OFF` 也可以获取下一章
page list，但不会获取图片。Android 继续使用固定原版串行 current +4 与末五页 page-list-only 流量，不消费
Desktop preference。

`Ready` 只表示 encoded 数据可用。decoded bitmap 属于 viewport 附近的有界平台缓存，完整下一章预取
不能线性保留整章 decoded bitmap。相邻章写入若因 encoded-store 配额或 storage 失败而拒绝，会停止该章
剩余后台请求，不改变当前章状态，也不产生 history、`last_page_read` 或 read effect。

RD-01/RUA-04D5 已删除 Desktop 主 loader 与 `PagePreloader` 第二 owner；canonical session 产出的 encoded ref
是 `DesktopReaderPageImagePipeline` 的唯一解码输入。`DesktopReaderPresentationImageOwner` 只持有 pipeline
返回的引用计数 lease，edge matcher 只消费 `retainCachedFullPageAssets()` 返回的 cached FULL 资产，不拥有
fetch、materialize、decode 或 queue。

## Presentation SPI

`ReaderPresentationStrategy` 只做三件事：

1. 把稳定逻辑页映射为稳定 `DisplayUnit`；
2. 把 settled pager/scroll 状态转换为 `VisiblePageSet`；
3. 在 Loading/Ready/Error/Retry 间保留同一 `DisplayUnitId` 和容器。

Single、Webtoon 和 Dual 是 registry 中同级策略：

- Single：方向、宽页切片和稳定 pager key；
- Webtoon：多页可见集合、滚动锚点、auto-scroll 与 side padding；
- Dual：双槽 frame、adjacent pairing、cover-single、forced single、spread、edge matching 和 landscape
  parity。

### 章节手动双页边界（CP-01 Desktop）

2026-10-09增量：当前章级模型在原独占边界上增加开篇方式和明确相邻配对，见[本轮修复契约与验证](../evidence/reader-manual-pairing-fix-2026-10-09.md)。下述CP-01仅forced-single的描述保留历史来源；新状态仅在边界、明确配对均为空且开篇方式为自动时清除，revision继续保留。显式布局经同一CAS、共享owner及两端应用级协调器消费，不改变同步/备份或阅读进度格式。旧格式兼容与数据库升级结果以增量报告为准。

`ChapterPairingRepository` 是手动 forced-single 边界的章级持久接口；SQLDelight 文件库保存格式版本、
页数、边界及独立的单调 revision。读取和写入都核对真实章节与漫画归属；边界清空时删除配对状态及边界，
但保留 revision 元数据以防旧会话在清空后重建时通过过期 CAS。删除章节由外键级联清除三张表。
记录版本、页数或边界损坏时，Reader 不应用该记录，并提示用户；页数相同但内容变动无法自动识别。

Desktop runtime 通过 Injekt 注入共享仓库和 `DesktopChapterPairingCoordinator`。协调器按章串行受理读取与
写入，生命周期独立于 ScreenModel；正常应用退出等待已受理保存结束。Reader 首次显示双页和首次 settled
viewport 前等待本章恢复，旧章节与旧 generation 的迟到结果不得改写当前状态。调整按钮先保存成功再更新
当前排版；保存失败保留原排版并允许重试，读取失败可重试或仅在本次会话使用默认排版，且默认状态不能
覆盖未知的持久记录。只有直接打开且没有数据库章节身份的临时文件使用会话限定调整并显示提示。
该配对状态仅保存在本机数据库，不进入阅读进度、同步或备份链。Android 的生产接线由 CP-02 完成。

Pairing、双槽、封面和屏幕宽度禁止进入 `ReaderSessionCore`。双页 settled 时必须上报实际可见的全部
`PageId`；`firstPage` 仅作为导航与预加载锚点，只上报它会让末页 pair 的进度不完整。Android 双页
任一可见成员错误时拒绝整组进度；显示模式或尺寸引发的纯布局重排不触发进度提交。
纵向独占页的物理槽由共享 `portraitSinglePageSlot` 依阅读方向及组位置决定：RTL 首页左/末页右，
LTR 首页右/末页左；一页章节占满视口。此规则仅用于 presentation，不改变配对或持久进度。

当前 RP-01～RP-03 已在同一 registry 注册 `SinglePagedPresentation`、`WebtoonPresentation` 与
`DualPagedPresentation`。Single 将 canonical `ReaderPageSession` 映射为单页或宽页切片 `DisplayUnit`；
Webtoon 将逻辑页/切片映射为连续 `DisplayUnit`；Dual 调用共享 `ReaderPagePairing`，把封面、相邻 portrait、
forced single、spread、edge match 与 landscape parity 映射为一槽或固定两槽 display unit。三者均以
`PageId + splitHalf + mode` 组成稳定 key，Loading、Ready 和 Error + Retry 在同一 Compose 容器内切换，
URL/encoded ref 不参与 identity。

Single 仅在 pager settled 后写回完整 `DisplayUnitId`；Webtoon 仅在滚动停止后写回全部可见 `PageId`、按固定
原版 last-end-visible/NO_POSITION 规则选出的 active `PageId`，以及首个可见 unit 的 offset 与测量高度。
Ready 内容改变高度时按相对位置重放锚点；宽页 split/merge 后若旧 unit 不再存在，会回退到同一逻辑页并服从
Lazy 边界，不会跳到后续页或先发布错误 viewport。Dual 的 settled display unit 上报去重后的全部可见
`PageId`，并以最大 source index 作为 active progress；最终 pair 只有实际包含末页时才完成章节。

Dual renderer 始终在完整 reader content viewport 中挂载两个各占一半并向书脊对齐的稳定物理槽，不使用固定
4:3 frame 或只为该比例服务的水平 inset。封面无论横竖比例、章节长度、阅读方向或环境 locale 方向，都占
绝对物理左槽，右槽为空；普通 pair 只按阅读方向改变两页的物理分配，环境 RTL 不得再次翻转槽位。单页继续
保留双槽身份。窗口 resize 只改变布局尺寸，pair/slot identity 与 zoom container 不变；默认 `FIT_SCREEN` 不
裁切或拉伸，漫画自身比例造成的留白不由 frame 隐藏。宽图切片继续通过统一 `ZoomablePageBox` 的
`splitHalf/sourceBounds` 渲染，所以右键保存消费与屏幕相同的实际切片。自动 edge matching 只读取
`DesktopReaderPresentationImageOwner` 从唯一 pipeline 保留的 cached FULL lease；matcher 没有 URL、网络、
materialize 或 decode 入口，也不拥有 fetch job。production observer 收集 cache revision：晚到解码会触发重算，
新发现与本章已确认 pair 做并集；缓存淘汰不会让当前章节的排版来回跳变。

side padding、crop、双页组合选项与覆盖 drag/fling、只在滚动 settled 后恢复的 auto-scroll 都是 renderer /
presentation option。Desktop 三种策略直接消费 canonical `ReaderSessionCore` snapshot；`resolvedUrls`、空 URL
slot 与迁移 presentation adapter 已删除。架构守卫禁止 presentation 重新获得 source、HTTP、repository 或
materialize port。

## Platform adapters

Core ports 使用平台无关引用，不暴露 `Context`、`File`、`InputStream`、`Bitmap`、Skia、Coil 或 Compose：

- `ReaderChapterContentPort`：online/download/local/archive 的 page descriptor；
- `ReaderPageFetchPort`：把一页 materialize 为 opaque `EncodedPageRef`；
- `ReaderEncodedPageStore`：session 生命周期、物理存在性、配额、淘汰与诊断；
- `ReaderProgressPort`：提交 core 产生的进度 effect；
- clock/diagnostics/lifecycle ports。

Android 保留 Context/Source/Download/Local、ChapterCache、Bitmap/Coil、View 与 Activity 生命周期；Desktop
保留 SourceManager/ClassLoader、download/local/archive、Skia/Compose、Voyager 和键鼠。adapter 只能映射，
不能重新实现页序、优先级、Retry、相邻章或完成规则。

### Desktop partial 下载 overlay

Desktop 下载队列与持久化 store 仍是下载状态权威；`PartialDownloadIndexSupport` 只是按
`chapter identity + attempt generation` 派生的可重建页索引。Downloader 只有在单页完成写入并以正式扩展名提交后才
发布 candidate，`*.tmp`、0 字节、旧 generation 和未能一一对应 source page 的 legacy 文件都不能进入 Reader。

Reader 继续只使用一个 page-list/materialize executor。加载顺序固定为：

1. session encoded cache 命中直接复用；
2. 当前 generation 有已提交 candidate 时，通过 manager-owned read lease 按需复制到 session encoded store；
3. candidate 缺失、过期、不可读或被同 revision 解码拒绝时，才回到原 Source/HTTP 单页链路。

新版 indexed metadata 可以直接构造稳定页表，不调用 `Source.getPageList()`；legacy metadata 最多调用一次 source page list，
且只有证明 ordinal/index 一一对应后才 overlay。Reader 不持有 `_tmp` 长寿命 URI，不删除 Downloader 文件；章节目录 rename
或 CBZ publish 交错时只允许一次有界 re-probe，不轮询。文件名的非 ASCII 策略在 initial、切章和相邻章预取入口统一冻结，
避免 lookup identity 分叉。

完整下载目录仍保持 direct-file 零复制，完整 CBZ/local/普通 online 路径不挂 partial I/O。默认关闭的 operation probe 在
构造事件前短路；Test Mode 才启用页级 probe/open/copy 与网络计数。该 decorator 只在 Desktop composition root 接线，
Android 的 download/source route 与 scheduler 参数不变。打开确有部分已提交页的章节时，Desktop 仅显示一次非阻塞
“已下载 X/Y 页”提示；无 partial 的普通 Reader 不挂载对应 Snackbar host/effect。

Android 的 `ReaderChapter.State` 是旧 pager/webtoon 观察者使用的只读投影；唯一写入方向是
`ReaderSessionReducer → ReaderChapter`。当在线章节在运行中变为已下载章节时，adapter 通过 canonical
`ResetChapter` 失效旧 generation 并回收旧 loader，再由同一 `ChapterLoader` 五路 route factory 选择
download/local/archive/EPUB/online I/O。任何 production 调用都不能从 legacy state 反向发布 session。

较新上游 `bc7f7e70…` 的 cache journal + 实体文件存在性检查已由 Android encoded store adapter 保留；
RC-02 已恢复 cached Error 的显式 Retry 强制重抓，RC-03 又由 shared scheduler 把 Retry 提升为 P0 并
启动新 generation。encoded store 只有在 journal 与实体文件均存在后才提交逻辑索引；配额淘汰先确认
实体删除，再推进逻辑 LRU，写后异常和 session 结束期间的迟到写入会清理未索引实体。物理删除失败会
作为 storage failure 暴露，不能返回 `Stored` 或 `Ready`。由于 `ChapterCache` 自身也有物理 LRU，每次
逻辑 commit 前会对全部 tracked ref 做物理存在性 reconcile；物理层先行淘汰的 ref 会进入本次
`evictedRefs`，而不会残留 phantom diagnostics。session 启动失败同样分类为 Storage，而不是网络错误。

## 章节过渡与进度

固定原版 transition 只有 Loading、Error + Retry 和无附加控件的 Wait/Loaded；没有 Continue、Cancel 或
Dismiss。相邻章 page list 已 Loaded 且无 gap 时，adapter 把其页面 seamless 接到当前 viewer。

`ReaderChapterWindowReducer` 是 current/previous/next 身份、retain/release 顺序、相邻 page-list 请求、
Boundary 和 active transition 的唯一决策点。窗口替换先产生新章 `RetainChapter`，再发布窗口，最后
`ReleaseChapter` 离窗 session；跨章 intent 携带预期 from/target，首次激活后重放同一 intent 必须无 effect。
`BeginPageListLoad` 对 Wait/Error 发布 0 页 Loading，对已经 Loading/Loaded 的 retained session 不重启；
Android 在与 `ref/unref` 相同的锁内再次确认目标仍被窗口持有，因此 release 后恢复的旧预取 effect 只会
取消，不会在窗口外重启 loader。`ReaderChapterWindowOwner` 只把生命周期 effect 映射到
`ReaderChapter.ref/unref`，并保持固定原版
“加载完成后提交 active 窗口”的 UI 时序；激活撞上正在进行的相邻预取时等待同一 page-list 终态，不创建
第二个 loader。pager/webtoon holder 的 Loading、Error + Retry、Boundary 和无额外 Continue/Cancel 控件
保持不变。

Desktop 生产行为是翻过末页后立即激活下一章 `LoadingPageList(pageCount = 0)`，随后一次性发布稳定 page
identity，再逐页 Ready/Error；失败显示 Retry/返回，边界显示明确结束反馈。这个产品策略属于
presentation/navigation adapter，不进入 core 文案或按钮决策。若相邻章 page list 或 encoded 内容已由 RD-02
准备，激活会复用它们；尚未准备的可见页仍按正常 0 页/逐页状态和 P0 优先级加载。

进度只来自 settled visible logical page：

- 非末页只更新 `last_page_read`；
- 实际最后逻辑页才完成；
- prefetch、decode、创建相邻章和 dispose 都不是进度来源；
- duplicate chapter-number 标记是独立 preference；
- 阅读后序章节不能批量完成前序章节。

`ReaderProgressPolicy` 只接受 `ViewportSettled(activeChapterId, chapterId, visiblePageIds)`；章节打开、页准备和
非 active 章节 settled 都不产生 effect。多页可见集合取实际可见逻辑页的最大索引，因此最终双页只有确实
包含末页时才完成。v3双页使用共享显示票据：第一像素前逐槽锁定，实际绘制后才形成显示记录，
原生视口停稳时提交本次实际页集合。Android取当前holder的actual paint与共享frame.drawn交集，
Desktop由原Compose draw及idle链提交；解码完成、隐藏挂载、拖动曝光和history旧记录均不能单独计读。
Desktop运行时的进度anchor必须取本次实际已画集合；逻辑目标尚未画而伙伴已画时，
model仍保原逻辑目标，正常加载和源页导航继续，不把逻辑目标冒充actual或放宽core的可见页约束。
健康首张不等待另一张成功；同一定位的后来伙伴可追加一次，重复回执去重，离屏旧定位不再追加。
实际错误页、混入旧章节或 `layoutOnly` 不提供新的读取资格；单页显示单元沿用原有报告链。
`firstPage` 保留组锚点职责，导航与进度必须使用对应真实源页身份。Desktop 的 `layoutOnly` 视口仍须进入
可见页调度，尤其是配对恢复后第一次呈现在线章节；它只跳过进度 effect 与 settlement sequence 递增，
不能跳过图片请求。effect 携带 session、chapter、page 与 settlement sequence 组成的幂等 key，Android
`ReaderViewModel.onPageSelected` 通过 `ReaderViewportSettlementArbiter` 为每次 viewport settlement 分配单调
token；相邻章加载完成后只有最新 token 可以提交 active window。尚未受理的旧回调仍须通过 token、activation、
页面列表身份与图片错误门槛；已受理的 effect 不再因后续选页或切章失效。旧加载可以保留为相邻章 page-list
预取，但不能在用户返回当前章后反向激活或写进度。Android 当前章在主线程同步捕获 effect、原
`ReadingProgressSession`、隐私许可及完成处理所需的原章节身份，随后由 AppModule 单例
`AndroidReaderProgressCoordinator` 按漫画 ID、受理顺序串行写入；邻章在激活成功后回到主线程重新核验再受理。
同书旧章末页和新章首页不会因 IO 调度逆序，切章 UI 不等待旧章事务。队列命令不保留 Reader 页面、holder 或
Activity；`onActivityFinish`/`onCleared` 同步封闭 handle 并把删除清理标记排在已受理命令后。新 Reader
初始化读取续读位置前等待打开时捕获的旧队列 barrier，等待不阻塞主线程。保证限于进程仍存活且存储可用的
正常退出；进程终止前未提交的内存命令没有持久保证。
首次关闭还捕获已取消的当前章节下载，待先前写入排空后按 SQL 已读状态决定是否恢复；写入或状态查询失败时
保守恢复，随后才运行删除清理。该关闭回调只持有下载快照与服务依赖，不持有 Reader/ViewModel/Activity。
`AcceptedReaderProgressContract` 由 Android mounted Reader 和 Desktop 生产 session 共用：两端都验证完成后回翻的
read/页码语义，也都在写入尚未完成时关闭，再释放受控写入并断言已受理命令恰好提交一次。

`RecordReadingProgress` 继续写入现有 SQLDelight 章节行、reading_events 和同步 journal，不新增 schema 或
同步格式；`recordHistory = false`，history 仍走原阅读计时链。受理后的同章 read 投影为后续回翻提供
`wasRead = true`，实际 `last_page_read` 允许下降；该投影不代表 SQL 成功。事务提交后才执行原章节的跟踪、
重复章节标已读和下载删除。重复章节列表继续按既有语义在提交后读取，但查询使用命令捕获的漫画与完成章，
不会改取当前章；删除候选与偏好在受理时固定，先前受理的已读投影可保留快速 A→B 的 A 候选身份，
但跨章候选在完成事务后还需经 SQL 确认为已读，才入持久删除队列；`ReaderChapter.read` 也仅在事务成功
回执后更新。存储失败不执行这些
副作用，活跃 Reader 通过现有 eventFlow/Toast 提示一次；已关闭 Reader 仅记录错误，下一次打开以数据库
真实状态为准。网络 tracker 单独后台执行，不阻塞后续进度事务。

RA-01 的 online→download route reset 只接受由同一 canonical Wait/Error generation 捕获的 token。token
同时绑定原在线 `PageLoader` 身份；下载检查期间若 activation 安装了新 generation 或 local/download loader，
旧 preload 的 reset 必须返回失败且立即退出，不能回收较新的 loader。

`ReaderEntryResolver` 接收已经按漫画原始排序配置排列的候选与显式方向：升序取第一个未读，降序取最后一个
未读。Android `getNextUnread`、Desktop 详情页阅读入口和书库“继续阅读”都通过该 resolver，因此不会把 UI
第一项误当作故事最早未完成章节；Desktop 仍保留现有 reader navigation 列表顺序。普通“继续阅读”先在入口
原有筛选范围内确定目标未读章，再恢复该章 `lastPageRead`；只有同步位置指向同一目标章时才沿用同步页码和
`resumeSnapshot`，全部已读时不回退到旧同步章。Android 普通继续入口以 `resumeWithinChapter` 锁定目标章，
与历史或显式同步恢复的漫画级 `resume` 意图区分；显式点选章节仍按已读重开规则处理。

## 迁移门禁

| 门禁 | 完成后才允许的声明 |
| --- | --- |
| RC-01～RC-05 | shared state、materialize、scheduler、window、progress contract 完整，Android 是首个生产消费者 |
| RA-01 | Android 不再保留第二套 session/loader 决策 |
| RP-01～RP-03 | Single/Webtoon/Dual 通过同一 SPI，core 无 presentation 分支 |
| RD-01 | Desktop production 只创建 canonical session，空 URL/双 loader/Screen replace 已删除 |
| RD-02 | Desktop 图片 decorator 默认 OFF；显式首屏/完整模式经首帧、all-Ready、shared-idle 门禁进入 encoded-only P4，canonical anchor 仍保留末五页 page-list-only |
| RG-01 | legacy bridge/executor 删除，架构守卫与文档一致 |

RD-01 已以 Android、Desktop production wiring 与行为测试把 manifest 的 `canonicalSessionExecutor` 收口为
`WIRED`；RD-02 的 preference、P4/P0、配额降级和原版 OFF 边界证据归属 capability 45。RG-01 已删除剩余
Continue/Dismiss 兼容面及未接线的私有进度 helper，并固定 canonical decision 声明/调用 owner、已知 legacy
owner、legacy 文件零清单、core/presentation 依赖边界与唯一 session/pipeline/presentation/region owner wiring；
后续变更若恢复这些兼容面/旧 owner、增加 Reader-named scheduler/progress/window/session/queue/completion
decision family，或新增第二个 fetch/materialize/decode/cache owner，架构门禁会失败。

## 验证与失败处理

### 章节入口、边界与配对稳定性

显式选择章节时，两端使用 `resolveReaderChapterEntryPage`：已读章节从第一页打开，未读章节恢复本地进度；
显式同步恢复位置优先，不能被已读重开策略覆盖。此规则不改写数据库进度，也不改变“继续阅读”的选章策略。
普通“继续阅读”的跨章目标仅来自筛选后的最早未读章；其他章节的同步位置既不改选目标，也不传给该目标的
Reader 初始化。若没有未读目标，入口隐藏或沿用现有“没有下一章”反馈。

`readerChapterBoundary` 是两端唯一的章界显示分类：无相邻章时显示终端提示；相邻章未就绪时仅保留加载、
错误和重试反馈；就绪后直接连接页面，不受章节编号缺口或旧“总是显示过渡页”偏好影响。
Android 的当前加载槽被移除时，按页面身份定位上一章末页或下一章首页，不能沿用过渡槽索引。

Android 双页以章节对象及其实际页面对象列表为缓存身份，三章窗口整体只反转一次。尺寸尚未返回时和 Desktop
一样预占纵向双槽；真实宽图和奇数尾页仍可为单页。异步尺寸只更新所属章节；同页列表刷新保留尺寸和手动配对，
页列表失效则丢弃配对缓存，旧页面回调不得污染新窗口。窗口像素几何仍不进入共享 session。

章级手动双页边界由共享 `ChapterPairingRepository` 保存，稳定键是数据库章节 ID 与漫画归属，记录包含格式版本、
页数和全部边界；事务和单调 revision 防止同章多会话覆盖，清空边界删除配对状态。Android 的
`DualPagePairingStore` 和 Desktop 的呈现状态只是会话缓存。两端先按原入口规则确定页码，再等待当前章页面列表
与配对读取，验证版本、页数和边界后一次组页并定位包含续读页的显示单元。Android 相邻章预取也在交给 RTL
adapter 前恢复该章，尚未完成读取的页列表不得以默认配对先进入可见窗口。异步宽图尺寸只重新计算自动配对，
不删除手动边界；仅恢复或调整布局不写阅读进度。

Android `AndroidChapterPairingCoordinator` 与 Desktop `DesktopChapterPairingCoordinator` 都以应用拥有的
scope 按章排队读写；同章新读排在已受理保存之后，跨章可并行，保存成功后才改变显示。两个平台 adapter
保留各自实现：当前 KMP commonMain 没有可直接复用的 `Monitor` 同步原语，而 Desktop 还须实现
`DesktopRuntimeService` 的停止、拒绝新任务和排空协议。Android coordinator 由 AppModule 注册为单例，
不随 Activity 或 ViewModel 销毁取消已受理写入；正常 Activity 重建的新 ViewModel 在同一进程内复用队列。
Android 返回阅读器或 Activity 重建时，队列在存活的应用进程中继续运行；当前没有可靠的进程退出排空钩子，
系统结束进程或断电前未提交的写入只保证上次成功状态。Desktop 的 runtime 停止流程会拒绝新任务并等待已受理
任务排空。两端均使用仓库 CAS，旧会话保存冲突须失败或重新读，不能静默覆盖。

配对读取 I/O 失败时保留阅读器容器并提供重试或本次默认；本次默认不删除旧记录，未成功重试前禁止调整保存。
相邻章仅在后台预取时读取失败不抢当前章的焦点，也不弹恢复对话框；该章页列表仍受恢复门禁保护。
Android RTL 双页在此状态保留章节过渡页；用户滑到过渡页或明确进入该章时重新读取配对，若仍失败再提供
上述恢复选择。过渡页异步结果须确认用户仍停留在同一 viewer 的目标过渡页，避免迟到弹窗抢焦点。
页数变化或损坏记录整体判无效，默认组页并允许从已知快照重新调整。保存失败维持旧显示，反馈失败并恢复操作；
晚到的读写结果按漫画、章节、页列表与会话代际检查，不能修改新章或发出过期通知。以上状态只影响配对呈现，
不修改同步、备份、历史或进度格式。

进入或恢复 Android 阅读界面不自动显示模式气泡；仅当前 Activity 内实际切换阅读模式且提示偏好开启时反馈。
同模式元数据刷新保持静默；Webtoon 与连续竖向模式即使共用 viewer 类也按模式值区分。

### 双页显示契约与平台布局边界

Android `DualPagerPageHolder` 与 Desktop `DualPagePagerViewer` 的普通双页使用完整阅读视口、固定物理左右半屏槽。
默认 Fit 保持原图宽高比、完整显示、垂直居中并朝书脊对齐。同宽高比的图片无论原始像素分辨率如何均显示为
相同尺寸；不同宽高比允许自然留白，不能靠拉伸或隐式裁切强制等高。Desktop 显式选择其他缩放模式、封面槽、
拆图等平台能力仍按既有设置工作；Android 单页保留完整视口及原有独立图片手势。

槽位必须在图片就绪前建立。Android 由 View 测量生命周期计算槽内 Fit 尺寸，首次测量前缓存命中、左右到达顺序、
重试及窗口 resize 都重新使用当前约束，不依赖某一张图的加载回调补做布局。图像尺寸通知仍交给既有配对 adapter；
像素坐标与窗口尺寸不进入 `ReaderSessionCore`。双页缩放、拖动与双击重置由整组容器接收，子图不能独立放大；
内容加载不能重置整组变换。放大拖动需阻止外层 pager 抢占，回到默认倍率后恢复普通翻页。

共享验收向量位于 `domain/src/commonTest/resources/reader/dual-page-fit.csv`，仅是测试输入与预期矩形，不是 domain
运行时几何模型。Android 测试执行真实 holder 字节绑定、Ready 状态收集与 View 测量，Desktop 测试挂载真实
`DualPagePagerViewer`、图片 pipeline 与 `ZoomablePageBox` 并检验离屏像素；不能用源码扫描或测试内另写 Fit 算法
替代这两条生产链路。Windows Robolectric 仅替代不可用的 Android 原生格式嗅探，尺寸解析及 View 实现保持真实；
正式 APK 在实机上补验原生解码与交互。

1. 产品行为严格红→绿→重构；source/HTTP 变更覆盖成功、空、403、429、500、畸形响应和 cached Retry。
2. 每个平台 adapter 使用同一 shared contract 向量；production wiring 被绕过时集成测试必须失败。
3. Compose/pager/Lazy 测试验证 stable key/container identity，而不是扫描源码字符串证明行为。
4. authority fixture 和源码边界扫描只用于 provenance/架构守卫，不能替代 production 行为验收。
5. 数据库、备份和 `last_page_read` 格式保持不变；encoded cache 是可丢弃派生数据。
6. 迁移失败时回滚入口 wiring，不恢复长期双 scheduler、双 progress policy 或 renderer 内 source fallback。

### v3在线显示接口（SP01.A）

显示稳定性与源页导航以[稳定呈现设计v3](../2026-10-03-dual-page-stable-presentation-design.md)为准。
`SharedDualPageSession` 持有轻量的 `ReaderDualPagePresentationOwner`：只保存源页身份、固定物理槽、
显示历史、预留和逐定位已上报集合，不持有图像、不启动I/O。当前共享接口已通过定向契约与接口独审；
Android/Desktop原生消费、资源调度和正式运行证据仍按SP01.B/C与DP02取得，不能用共享契约代替。

平台须以本次真实可画页调用 `captureViewport(preparingPageIds = ...)`，并使用 `frame(ticket).id`
保持挂载身份。默认参数中的known事实不等于平台图像已可画，不能据此授权预取图片显示。
新增可画槽必须先通过 `preparePage` 锁定，再发出首像素；`reportDrawn` 只记录真实曝光，
只有当前用户定位、native idle及实际绘制页集合才能进入 `settle`。同一定位允许追加第二张图，
去重后向原进度策略传累计真实集合，以保留最大可见页及实际末页完成语义。
邻屏动画曝光也要锁定几何，但不能记作当前停稳阅读；预取、placeholder和纯布局变更不产生进度。
离屏后调用 `releaseViewport` 撤销追加资格。反馈身份须保留原票据，不能用晚到的native索引重建导航原因。

普通纵页先使用固定半槽，不等未知前缀、伙伴、评分或配对记录恢复。当前未显示窗口仅使用已有局部事实；
选择下一对时，孤页和预留在同一owner事务发布，后来的更强边不能取消已有承诺。
已锁槽和历史页归属优先；UNKNOWN仍是UNKNOWN，不伪装纵页或负匹配。
事实回调只重算活动窗口及有缓存投影的局部窗口；显式导航可创建章级pager投影。
该实现仍可能复制投影列表，不能把局部业务重算误称为所有平台UI操作均为常数复杂度。

冷跳与反向阅读从实际目标附近开始；回翻使用已显示历史。缺伙伴时按源页身份推进，不静默吞掉未画页。
视口或方向显式改变可建立新几何context并撤旧票据，但保留已承诺源组合；设置开关和晚恢复只影响未显示部分。
手动保存沿原仓库CAS：失败保留旧显示，成功只使局部冲突历史失效；已导航后的成功不回跳，
尚可见的冲突帧延到release后删除。配对恢复未完成时关闭保存，不能覆盖未知旧记录，也不能阻塞普通阅读。

真实内容替换先调用 `canRebindContent`。涉及仍可见的锁定图时，平台保留原内容lease至离屏或用户显式reload，
不能让旧像素与新内容身份混成一帧。远处内容变化只继承未变页历史；跨owner旧票据拒绝。
失效、关闭及图像释放仍沿原平台owner，不能增加第二套cache或解码链。

### Desktop原生接线开发里程碑（SP01.B）

`ReaderPagerNativePresentation` 按原生页保存自己的共享票据；实际曝光页面的原图片及region tile共同经过
prepare边界，真实draw才回报，当前定位且native idle才提交累计集合。仅挂载的预取页可以decode，
不能因此发出FIRST_PAGE_PRESENTED或阅读进度。伙伴实际可画时只追加该槽，不重新构造已显示图的呈现对象。

`ReaderContent` 的呈现epoch由generation、方向/模式及显式geometry上下文组成，
不使用fact/layout revision或用户定位sequence作重挂载key。tap、相邻key、wheel与真实drag
消费共享源页导航；gesture保留其原逻辑位置，避免动态binding在同一次输入内多次消费导航。
新定位先到而旧页面仍曝光时，旧票据继续保护其几何，但不得追加旧屏阅读。
非滚动时，presenter按原pager实际visiblePagesInfo的稳定源key重新映射当前单元，
避免RTL列表局部拆组后旧物理index选中邻页；滚动中仍用原索引，idle仍须真实settledPage匹配。
重新映射不伪造用户导航、滚动或停稳，不移动已曝光图像。

`ReaderScreenModel` 先用实际未裁边位图类型准许槽位绘制；实际wide与远端portrait猜测冲突时，
先纠正未显示候选，再准许FULL像素。后台替换仍可见内容时暂存新snapshot，离屏后再消费；
未锁伙伴的正常Retry只更新该页，不重建当前已锁另一图。crop、scale或真实viewport变化
显式更新geometry上下文，保留已承诺的源页组合，不伪造新阅读。

实际 drawable 的规范未裁边尺寸经 `SharedDualPageSession.acceptDecodedFact` 分类，
该事件仅接受当前定位、有效frame及同版内容，不签发或消费编码header能力。
因此先画出的Skia图像不会撤销仍在途的原header请求；迟到EXIF更新原内容方向及采样身份，
不移动已经锁定/绘制的slot。旧采样能力失效，新能力使用实际方向；重复header、旧内容及旧定位拒绝。
已经锁定的离场frame仍保留原几何，旧定位不能借新decode补锁未画伙伴或追加进度。

该开发里程碑以真实Compose离屏事件、完整逐帧像素矩形/对象身份及runtime→SQL链取得证据；
Ready先画而原EXIF头晚到已通过原Factory/HTTP/encoded store/Skia/region sampler的开发验证，
原B1/B2经正常LocalArchive CBZ到Skia/Compose的六个条件已取得首prepare、真实像素及12帧身份/矩形证据；
B1原图17→未知前页16→迟到15→返回17的反向补链也已通过；B受影响93项定向重构复验通过。
旧可选读取／当前强制重组三项预期留C处理，C及正式三端S17不能由这些开发结果代替。

Android JVM 宿主出现逐槽 Compose Loading 后，Robolectric 自动 vsync 与无限动画会使无界 `idle()` 无法退出。
测试须保存并暂停自动 vsync，以有界真实帧推进 ViewRoot traversal，释放同步屏障并消费原 View.post 绘制回调；
收尾销毁测试 View/Activity 并恢复原暂停状态及帧间隔。窗口使用真实 ComponentActivity 生命周期，重新挂载时采用宿主合法 LayoutParams。
手动测量须消费 onSizeChanged 发出的原布局请求；同一测量规格缓存了旧宽度时，以 View.forceLayout 后的原 measure/layout 复测，不能直接给反馈容器赋测试期望尺寸。
这些是测试时钟和窗口约束，不是 production 的识别等待或呈现宽限；不得关闭 Loading、伪造 ACK、直接补 SQL，或用 JVM 结果替代 ART/JNI 像素验收。

### Android可见内容与几何生命周期（SP01.B窄里程碑）

`DualPagePairingStore` 先核共享 `canRebindContent`；可见图换版时保留旧entry，不把新事实送入旧identity。
holder分别保存绑定内容、可见图像lease和当前context的actual paint。新context可清当前绘制集合，
不能据此撤销仍可见旧图的回调资格；旧原生图沿相同绑定重绘，实际ACK再形成本context的绘制证据。
真正离屏即使holder仍挂载也须释放票据，再消费新snapshot；补载按页去重并在detach取消。
显式几何设置沿原encoded Buffer快照重配，不能用重挂adapter借机把后台新版换入当前图；主题不更新context。

每个新图片绑定都创建本槽加载反馈，包括状态流首次收集已经Ready的情况；只有原child实际绘制且共享ACK成功才隐藏。
反馈保持物理槽位，不能随图像zoom/pan移动，也不能在setImage或metadata回调时提前消失。可见旧lease早退不重新挂提示。
原生手势期间，事实及曝光票据继续更新，adapter只延后无显式源目标的结构投影，不通知分页器重设位置。
idle先消费原源页导航意图，再发布最新待处理投影；取消拖动也通过该收尾释放投影，不能用scrollTo掩盖重组。
触摸验收须满足实际DirectionalViewPager的释放阈值；只露出邻页不等于已经完成翻页，曝光本身不能计读。

普通Single保留其原内容替换与onLoaded进度语义：原替图入口先释放旧scope，再正常rebind，
不能把新版交给旧content frame。其兼容验证不作为Single真实像素证据，也不把双页keep-old策略扩为Single新能力。
上述边界已有真实View/SQL、11项开发ART窗口及Exposure3/受影响导航4项证据，详见唯一聚合报告。
原B1/B2经正常扩展HTTP、原WebP/JPEG与JNI取得冷暖、乱序、真实源页导航及逐槽几何开发证据；
早结构的首实际prepare及后续像素保持同一frame，不能用预挂载票据或最终槽位代替首帧。
共用原Single图片View的EXIF1–8／split／crop／fit／原生触摸24项和受影响Dual逐槽Error单项已过；
不把这些开发宿主结果当作正式R8或三端S17，旧受影响测试适配、Desktop剩余范围及C仍待完成。

## Authority 分层维护规则

### 双页方向与内容版本（DP01 接口）

编码头部最多读取 512 KiB，以 `ReaderImageOrientation` 表示 EXIF 1–8；其坐标约定是先旋转，再水平镜像。
`sourcePixel` 和 `splitBounds` 是规范显示坐标到原编码坐标的唯一映射，奇数宽度的 LEFT 使用 floor、RIGHT 使用 ceil。
分类采用未裁边的规范宽高；裁边、窗口旋转、zoom 和 rotate-to-fit 不改变该事实。Skia 已归一的像素只保留源方向身份，剩余变换为 NONE；
Android 原固定 decoder 的原始像素由 SSIV 的旋转和窄镜像 adapter 归一，仍使用有界分块，不能重复归一或引入整页 Bitmap 路径。

非 NORMAL 图像的 split+crop 使用窄条带 PNG adapter：先按共享规范半页的逆映射取得原始 region，
由原 ImageDecoder 按单次最多 4 MiB 解码并逐行流式写入临时 PNG，再交给原 SSIV 在该半页局部坐标裁边。
这避免原 native decoder 裁边后不可取得非对称原点的限制；NORMAL 历史拆图路径保留。
巨大单行超过预算会失败，不能扩大 Bitmap 上限。临时编码增加 CPU 和磁盘写入，只适用于非 NORMAL 的 split+crop 路由；
不聚合整半页 Bitmap 或完整 PNG 缓冲，剩余方向只归一一次。中间像素按 sRGB 解码且 PNG 显式声明 sRGB，
最终 SSIV 才转换到显示 profile，避免二次颜色转换。实际 presentation owner 负责失败、取消、替换和回收时关闭并删除文件；
原 decoder 释放失败时不交付文件，旧内容回调不能删除新 presentation 的文件。
临时 PNG 在交给 SSIV 前由呈现 owner 打开并登记原输入流；回收在 finally 关闭该原流，再删除文件。
不能交给 URI provider 重新打开文件而失去在途初始化的句柄所有权；SSIV 尚未安装 decoder 或回收抛错时也必须关闭原流。
Windows 测试可验证流式 PNG、实际 BitmapFactory、SSIV 旋转与 Bitmap source region；原 JNI 的编码分块、ICC 和裁边必须在 ART 验收。

Android `ReaderPageContentFact` 在同一内容锁内发布 encodedVersion、呈现 version、事实和 retrySequence。
encodedVersion 只随真实 stream/ref 替换递增；呈现 version 还随首次可信方向确认递增，以撤销默认方向画出的像素。
头部 I/O 开始前一次捕获 stream、两个版本、重试和可信方向，完成时核对原快照；失败和重试保留同一编码内容已经确认的方向。
当前缓存事实也只能通过同一原子快照准入，不能将迟到的 Flow 值与当下内容拼接。

`SharedDualPageSession.acceptOrientedFact` 只接纳该 owner 仍签发的原事实请求，核 generation、页表版本、完整有序页集合和本页物理内容。
首次确认本页方向更新其身份、撤本页原事实票据及所有旧视口票据，保留逻辑定位、已消费阅读原因、持久化状态和原补齐预算。
其他未改变页的原事实请求继续有效；普通 `acceptFact` 要求方向与原请求一致，不能绕过身份更新。
真实 bytes 替换仍走完整 rebind，不允许旧请求跨 owner 或同身份重建。
Desktop 头部工作按 owner 实例、workRevision 和物理 ContentOpenRequest 标识，其他页方向确认不得撤销正常或可选消费者。
v3 C 的 Desktop header 不再等待 firstPresentedGeneration：当前逻辑页及单方向最多五页准备窗口内
已经 Ready 的普通编码可贡献资料，仍使用每次最多 512 KiB 的原 header lease。邻页 header 与可选 region
共用一个 permit；邻页取得 permit 后重核资源，当前逻辑页资料及正常图片解码不因可选识别暂停。
不再调用 model 的主动 loadFactPage 补读，不为匹配扩展正常预取窗口；collector 随 model dispose 取消。
原下载 runner 的 physicalWorkCount 在原 Job 实际完成回调撤销资格，包含尚未执行的排队任务和取消后
仍在运行的旧物理任务；Desktop 同时观察原 scheduler requestRevision 与图片 Entry foregroundWork。
它们是原资源链的只读活动状态，不建立第二任务队列或下载／解码 owner。

Desktop 原 Entry 另发布只读 optionalWork：新 optional Entry 在创建时登记，直到实际 Deferred completion 才撤销；
同 key 单飞、缓存命中不重复登记，升格为真实 foreground 后仍保留原 optional 资格，取消／generation／close 不提前归零。
model 启动门禁等待 optionalWork 为零并沿同 Flow 恢复；edge canContinue 不读取自身计数。
foregroundWork／optionalWork 的观察范围为同进程所有原 DesktopReaderPageImagePipeline 实例；各 Entry 的资格仍局部，真正 Deferred completion 才线程安全撤销，close／切换 Runtime 不提前抹掉旧工作。
不同 Runtime 的本地 lock 不互斥，因此新 optional Entry 在原创建点以同 StateFlow.compareAndSet(0,1) 原子登记；先保 cache 和同 key 单飞，正常图片仍可创建，不额外排队或增加资源 owner。
开发真实 Factory／Skia／Compose 链已证明关闭旧 Runtime 时在途 Deferred／encoded lease 保观察、新 Runtime 普通 cover 可画、旧工作真正退出后同 owner 待评分自动恢复；这是原 decoder 前透明门闩并在放行后委托 Skia，不冒称门闩期间已进入 JNI 内部。
原 ReaderPageMaterializeRunner 另发布只读 processPhysicalWorkCount，既有 shouldStart 后在原 lock 外登记，先于 instance Flow 通知与原 Job.start；原 invokeOnCompletion finally 才一次撤销，instance 计数、队列、permits 和调度不改。
DesktopSession 的既有 physicalWorkCount／idle 与 Model Flow 消费改读此 process 观察；旧 Session 已关闭但正常物理任务仍在途时暂停可选 IO，普通新 cover 仍可绘制，真正退出后同 owner 自然恢复 header／样本。
真实 Factory／原 normal 执行器 gate 的跨 Runtime 反例已红绿，十项共享 JVM／Android 镜像与二十五项 Desktop focused 支持此边界；原 task gate 不冒称正在真实网络传输。观察仅限原 reader 执行器，Android 消费者保持原同 adapter loader 观察范围。
实际 Entry 创建也在原 Pipeline lock 中原子重核上限，先复用缓存／同 key，冲突抛原 typed Pause，不建立排队，正常 Entry 不受此上限约束。
设置页可先于新呈现消费 Retry 内容；可选 region 打开前仅以 model 已接受 snapshot 捕获的原 ContentOpenRequest 同步 beginPageAttempt，
旧 generation／attempt 仍拒绝，不从 raw incoming 提前撤销可见旧图。开发门禁已覆盖真实原生旧工作跨 Off／On／Retry、
当前页像素仍可画及仅释放旧 Entry 后自动恢复采样；Android 原生资源窄段见下节，完整 C／正式性能仍待验收。
Desktop 内存压力继续由同一个 DesktopReaderImageMemoryAuthority 的原租约／缓存账本判断：
原变更在锁外发布按 revision 防倒序的只读 snapshot Flow，reader 仅观察 overBudget 是否发生变化，
受压时不新增邻页 header 或可选 region；真实租约释放后沿同原 collector 恢复。正常解码／已持有图片不被强制回收。
压力测试只缩小原 soft budget 并持有真实 Skia 图片；它不代表系统 OOM 或本机实际低内存。Android 原生资源与系统压力接线／证据见下节。
完整内容 open 与有界 header 是独立的实际 lease/I/O，分别记录 OPEN_PAGE 和 HEADER_READ；后者携带
实际 page/generation 与 byteLimit，不以改名隐藏首帧前的非当前读取。Android metadata-only PREFETCH
只获得页表，不在 ChapterContentPort 自动打开图片；实际当前/已物化页的正常内容链仍发布 header facts。
邻章 Loaded 不等于配对已恢复，但 v3 C 的双页 adapter 不再用恢复状态挡住正常内容投影。
冷 setChapters 立即由原 store 创建同页表 owner；已有 Load 请求和 RESTORING／未知 revision 仍保留，未知记录不能被保存覆盖。
adapter 不再调用主动 Supplement／loadFactPage，附近资料沿正常内容链贡献，不为匹配另行物化图片。
此门禁已通过开发 focused 及真实 HTTP PNG→原 View 几何；Android native／压力窄段证据见下节。
v3 C 的 ReaderViewModel 初始和邻章双页激活不再等待配对记录：明确 DUAL_PAGE_R2L 或 AUTO 的实际 Dual viewer
在原 Main callback scope 中恢复，实际读取仍由原 application coordinator 的 IO scope 执行；显式单页／条漫保留原路径。
原 store 继续签发并校验 owner、页表和内容身份；迟到记录只更新未显示区域，未知 revision 不授权保存。
State.pairingRecordReady 只投影当前 owner 的实际状态，不由某个后台 Job 结束直接解禁；原 Activity→AppBars→BottomBar
在恢复期间禁用调整，不显示额外 Retry、不借用“正在保存”反馈，实际 Ready 后恢复按钮。
真 SQL／原 ViewModel／原 Compose 按钮的八项 focused 已通过；迟到记录另经开发 ART 的实际 HTTP／JNI／PixelCopy 验证：
真实记录读取被扣留时两槽可画且能双击缩放，恢复同 owner 后 12 次矩形、挂载、2 倍缩放和平移不变，
原 SQL 进度不重复，真实下一页键仍进入源页 3。测试先核实际 ViewModel 的 coordinator 身份，
使用本机 Injekt 的原 addFactory API 透明包装 repository，不能用会保留旧 singleton cache 的替换制造未扣住读取的通过。
该 ART 使用开发 APK／合法合成 JPEG／原 Copy 解析链，不冒称正式 R8 或 B1／B2 原图验收。
Desktop 的原 ReaderScreenModel 恢复已是异步；v3 C 仅修正 ReaderViewport：双页正常页表非空时保持 CONTENT，
配对读取中仍绘制原图片和处理源页导航。读取失败在原内容上复用 PairingRestoreError 的局部面板及 Retry／本次默认按钮，
不另建恢复 owner、字符串或 Screen；显式 Single／Webtoon 及正常图片错误仍用原路径。
未知配对 revision 继续禁用保存／调整；Default 保留 DEFAULT_UNVERIFIED，不能覆盖原 SQL 记录。
真实 Factory／SQL／ComposeScene／Skia 的慢读取和失败按钮两项已通过：读取扣留时首像素可画、真实 tap 可进入源页 3，
原迟到记录接纳后 frame／composition mount 和蓝色图片的联合像素矩形连续 12 次相同。
这里的联合矩形不等于逐槽完整 transform；两槽几何边界仍按 B 的原生证据复用。
两端原调整按钮已通过真实未知伙伴／记录 READY 的合法双页测试：保存当前边界而伙伴仍未 Ready，自动匹配继续开启。
Desktop 使用原 ONLINE 内容请求的 source 2 响应门闩；Android 使用原 encoded-region Robolectric adapter、实际 Activity.AppBars 和 SQL，
此 Android 操作证据不冒称 JNI。未知记录不允许持久写入；原 DEFAULT_UNVERIFIED 的共享会话内局部操作语义仍保留，
不能把直接 Model 调用的局部变化误判为原 UI 授权保存。
重开恢复记录时保护已显示 frame，冷跳仍可按局部信息分组；远处 forced-single 不承诺恢复整章奇偶相位。
真实原设置事件和回到该保存源页的原 previous tap 已验证手动独占生效及 SQL 记录保持。
操作簇相关 refactor／格式已通过：Compose 六项、正确 Model 九项及 Android 二十二项以未变通过结果和失败项定向修复组合覆盖，
没有声称最终源码重新执行了全部三类。Android 原 raw Activity 夹具不启动实际 Reload collector，相关转场用例显式消费原加载事件到原 adapter；
真正 Activity collector／native 绘制沿 B 的 ART 证据复用。完整固定性能仍待 C。
Android PageLoader 的只读 hasForegroundWork／Flow 不提交加载请求；Http 实现合并原 scheduler pending／active 与 SharedRunner 物理完成计数。
原 scheduler 状态改变只发 revision，adapter 按真实 loader 身份在 idle 重评并在启动采样前重核 busy。
退出章节窗口立即失去旧呈现 owner；若旧 loader 的实际工作仍忙，只保留只读资源观察，直到真正 idle 后唤醒当前 owner 并撤除观察。
启动门禁检查当前及仍忙的旧 loader；导航逻辑取消、recycle 或换章不能提前把旧物理任务视为完成。
同 loader 取消后仅剩 physical busy，以及旧 loader 换章后实际完成自动恢复，均已通过开发 HTTP focused。
Main 管理订阅，并在窗口更新、旧 idle 撤观察和 close 时发布 @Volatile 的不可变 loader 集合；IO 每次读取该集合及原 loader 实际 getter，不遍历 Main 可变表或只捕获启动时列表。
Android sampler 在 encoded open、decoder 创建和每个有界 edge 开始前重核资源，忙时抛 typed Pause 取消，不返回坏图结果。
普通物理任务先 idle、旧 decoder 关闭仍未完成时不重启；原 Job 真正 completion 后沿同 owner 自动补完需求，已通过真实 HTTP／编码开发 focused。
以上 HTTP 接线证据不替代 JNI ART；平台原生资源／压力窄段见下节，完整 C 验收仍待。


### 自动匹配的共同样本 owner 与双端接线（DP01 D）

`SharedSpreadMatching` 定义原编码坐标上的 50 个书脊侧采样点、straight-alpha sRGB 到固定白底的合成、
严格色差与白／暗／低方差排除，以及完整候选的强边优先、同分较早页优先选择。
可生成正方图采样坐标不表示可配对；生产候选还必须具有共同 PORTRAIT 事实。手动边界先屏蔽冲突候选，
再重新选择其余候选，不能屏蔽整章，也不能取历次已选关系的并集。

`SharedDualPageSession` 唯一持有 `ReaderSpreadSamplesOwner`。平台在现有 encoded lease／有界解码开始前签原
`ReaderSpreadSampleRequest`；接纳核本 owner 签发实例、generation、页表版本、完整页序和本页物理内容／方向。
同 owner 其他页首次方向确认不撤本页原 work；本页方向确认、真 bytes rebind、关闭、禁用匹配或切单页撤销相关原票据。
重新开启不能复活已取消票据；方向切换使用已采两侧重新评分，已确认同内容样本／评分继续有效。
布局改变不建立导航或新阅读原因，header 请求与采样请求各自独立。

样本只保留防御复制后的两组 50 色标量，最多缓存 16 页，不持有 Bitmap；缓存淘汰保留已确认相邻关系的
RTL／LTR 标量评分。`requiredSamples` 每次推进一条未评分的 PORTRAIT 相邻边，最多需求两页；接受时先评分再淘汰，
完成一次需求就确认一条边，避免有限缓存导致无进展重读。重采样只复用已有 encoded，不能新发 HTTP；
16 页样本缓存与每次显式定位 16 页新增 materialize 上限是不同额度，已有编码重采样不消耗新增 materialize 页数。
实际生产采样仍使用既有解码资源和主动等待／并发限制，平台租约在完成、失败、取消和关闭时释放。
Desktop 的既有 GeneralTab 开关经 ReaderPreferences、ReaderScreenModel 和 runtime factory 接入该 owner；
Android 的 ReadingModePage 双页分页设置经 ReaderPreferences、PagerConfig 和实际 DualPageViewerAdapter 接入。
两端默认关闭，Desktop 已保存的 true 继续迁移保留。Desktop 只从 Ready 的原 ContentOpenRequest 开 region session，
Android 在原 ReaderPage 内容锁内捕获 encoded stream；都不依赖显示 Bitmap，也不为匹配 materialize 或发网络请求。
Android 采样完成还须在同一内容锁下消费原事实快照，核对方向、呈现版本和 retrySequence，防止尚未消费的新方向被旧样本覆盖。

DP01 历史实现的两端采样 pass 复用可选事实预算的主动等待时间。v3 C 两端均已移除整 pass 截止门禁，
每个原 region 单位仍受 4 MiB 上限约束；资源忙时通过独立 Pause 状态退出，不能把暂停记为坏图或要求用户 Retry。
原 publisher Job 真正完成后发布重评通知，以覆盖 idle 通知早于 Job 完成的时序；
同一原 model 的真实 consumer／内容 lease 关闭红绿测试已确认该接线：idle 已消费但旧关闭尚在途时不并发启动，
关闭真正结束后无需 Retry 自动完成样本评分。只断言静态候选组合不构成恢复证据。
SharedDualPageSession.configure 对同方向／同自动选项幂等；重复配置不增加布局或采样版本、不重建缓存或撤销有效票据。
真实方向／开关变化、事实／手调更新仍分别走原上下文和 rebuild。否则 ensure→configure→publish→状态监听
会反复制造布局事件，既增加普通阅读开销，又掩盖完成通知丢失；此链路由共同契约及真实 Desktop wiring 保护。
真正解析／采样失败仍记录本页完整内容身份，普通布局重建停止重复尝试；原内容改变、显式图片 retry 或关闭后重新开启才允许重新尝试。
Android 原 matching Job 在关闭／重试／owner 变化时只取消，不提前清除仍执行 native／IO 的 Job；实际 isCompleted 才允许同 adapter 的下一次采样。
LAZY Job 先登记再启动，实际 completion 沿原 MainScope 重评当前 owner；启动前检查首依赖的失败身份与 Ready，避免完成通知形成无进展循环。
原 AndroidReaderSpreadSampler singleton 仅被动观察现有 LAZY matching Job 的实际生命周期；optionalWork 使用线程安全 StateFlow.update，销毁 viewer 或取消不提前减，原 invokeOnCompletion 才撤登记。
Main 连续执行 admission、observe 和 start，无挂起或新队列；新 adapter 读同一计数并订阅归零，旧实际工作未退时只暂停匹配，普通 Ready 源页导航仍可用。
同 adapter 及销毁旧 viewer 后新 adapter 的源页导航、重新开启与真正退出后自动恢复，已通过真实 encoded／DisplayPage 开发 focused；原 HTTP 进行中忙闲见前节。
以上观察不创建任务执行 owner，不把自身 optional 计数用于阻塞当前 edge。
ReaderPageImageView 对当前版本 SSIV 的原默认 AsyncTask.THREAD_POOL_EXECUTOR 仅作透明委托，入队即登记 queued/running，原 Runnable finally 或提交异常才撤销；AtomicBoolean 保证拒绝／同步异常只撤一次。recycle 和逻辑取消不提前归零，不创建新线程池或工作队列。
Adapter 在 Main admission 与每条 bounded edge 前核对该只读线程安全计数，并订阅 boolean distinct idle 自动恢复；普通图片仍走原池。开发 ART 已验证原任务被扣留时不注册 optional，原任务结束后同 owner 的真实 JNI 采样与原图片 PixelCopy 自动完成；JVM 九项相关复验通过。
原 ReaderPageImageView 的两个 Coil enqueue 同样仅透明观察原 Disposable.job：提交前登记，原 Deferred 实际 completion 才撤销；dispose／逻辑取消不提前归零，同步提交异常只撤一次。原请求、cache、dispatcher、PhotoView 和 Webtoon Bitmap 分支不改；只覆盖初始加载／解码，动画每帧仍走既有链路。
开发 ART 已验证原 GIF 首次真实 read 被扣留时不注册 optional，实际退出后原 Animatable、MAGENTA PixelCopy 及同 owner JNI 样本自然完成，并复验原 SSIV 场景。取消和拒绝边界由四项 JVM focused 保护；末次 refactor UP-TO-DATE 复用有效结果，不称 fresh 全部重跑。
Android 动态可选工作压力由原 ReaderActivity 观察实际系统 RUNNING_LOW／CRITICAL、BACKGROUND 以上 trim 和 onLowMemory；UI_HIDDEN 不作为内存压力。原 resume／focus 和原 normal loader／native idle 用 ActivityManager.MemoryInfo.lowMemory 刷新，不以总 RAM／低端设备身份永久关闭匹配。
Adapter 在 Main admission 和每条 bounded edge 动态读同只读 StateFlow；压力暂停保留未知样本，普通图片不随之停。解除沿原 scope 唤醒，但旧 optional Job／region close 尚未完成时原上限继续生效，不额外排队或创建资源 owner。
实际 ART 证明系统 trim 来源、无 OOM 的受压 admission、真实窗口失焦／恢复和原 JNI／首图继续；中途 region／close 顺序另由原生产 Wiring focused 验证，两种宿主证据区分记录。十二项相关复验已通过；不把测试发送 trim 等同本机发生 OOM。
这些证据不替代完整 C、动画每帧性能或正式发布运行验收；这些仍待。
16 页样本淘汰后的补边从已有编码逐边重采，不创建另一条补齐队列。原生不可中断解码可在取消后结束，
但已撤销票据的结果不发布；流、region/像素 lease 和 decoder 在原 finally/use 路径释放。正常可见消费者不随匹配取消。
平台已删除旧显示 Bitmap 的 EdgePixelMatcher、历次 matchedPairs 并集和整章 manual 禁用；
Session 只消费共同 owner 当前完整候选，手动状态继续局部遮蔽。Desktop presentation 只消费实际 shared units；旧独立 pairing facade、索引推槽、visible-only single/dual settle 和兼容 matchedPairs 决策已删除。
Windows Robolectric 仅替换不能加载的 JNI region 边界，执行真实编码像素、共享评分和实际 DisplayPage 接线；
原 JNI 的 EXIF/sRGB/alpha 验收入口为 SharedSpreadNativeAcceptanceTest，结果以 DP02 正式 ART 记录为准。

正式 R8 候选的 instrumentation 不直接调用可能被消除或改签名的内部 sampler、Config、枚举 getter。
`HostReaderArtFixture` 通过 Android 公共 Intent 启动真实 ReaderActivity，以正常偏好和隔离数据库输入驱动
原扩展、ChapterLoader、holder、SSIV 与采样 owner；测试包不得为保留内部 ABI 修改产品 keep 规则。
实际硬件 View 的像素使用公共 PixelCopy(window)和当前可见控件几何读取，不能用软件 Canvas 的透明结果
判定窗口空白。共享采样需以自动匹配关闭/开启的非默认可见分组作因果对照；仅显示图片不足以证明 sampler 接线。
EXIF 候选一侧变换、另一侧保持 NORMAL 黄金，保留非对称边缘与 alpha 对照，避免双方同错仍相等。
EXIF split/fit/crop/触摸及源响应向量独立报告，首个失败不能隐藏后续未执行项。裁边夹具保留可识别的暗色内容边界，
并通过同图开启/关闭裁边后的实际内容尺寸差确认裁边生效；亮色黄金本身不能充当灰度裁边算法的内容边界。
受控 HTTP 路由保留原扩展自行处理的虚拟图片拦截器，未知真实请求显式失败，不将章节 JSON 作为图片返回。
错误验收按原 source 的实际反馈区分章节错误与扩展生成的说明图片：前者执行真实 Retry，后者执行既有产品恢复入口，
两者最终都须重新取得合法章节图片并绘制。不能仅凭“重试”文字识别错误层级，也不能直接删除缓存文件代替用户恢复操作。
源响应向量使用独立instrumentation进程运行；原扩展可能保留上一夹具的lazy HTTP client，跨向量复用宿主进程会访问已关闭的本地server，
不能把这种零请求失败判为产品 parser 失败。测试命令限定专用serial和具体参数化用例，保留每个用例的自然终态。
这些入口仅运行在本次独立 emulator：会安装两原扩展、写入两条 fixture 漫画/章节及临时偏好，并清理其图片缓存。
不得指向日常 emulator 或实体设备。旧 ABI、加载、像素和输入失败分别保留；编译或入口冒烟不替代完整行为验收。

- fixed-main 只证明固定原版用户语义；共享提取完成、可靠性加固或平台实现不能反向成为上游来源证据。
- latest settlement、进度幂等/drain 与 encoded store 生命周期属于跨平台可靠性；它们需要两端 production
  consumer 或真实 adapter I/O 证据，但分类仍不是 `FIXED_ORIGINAL`。
- 双页最大可见页进度、自动滚动暂停和完整下一章图片预取属于产品增强；原版末页完成、current + 4 或末五页
  page-list 仅作为相邻但不同的上游行为向量。
- display/slot identity、Webtoon 相对锚点和 Desktop 512 MiB encoded cache 默认值属于平台策略，只能由平台
  production/mounted 测试证明；不得进入 shared core 或借 decoded cache 证据完成验收。
- manifest 是 capability 状态与 deviation 证据的机器权威。architecture 文档解释目的与边界，源码零命中只
  防止已删除的固定 4:3 实现回归，不能代替真实几何和 wiring 测试。

### 保存、输入与验收桥接（DP01 E）

共享 owner 是 manual committed、preview 和持久化状态的唯一权威。保存开始不改变 committed；真实 repository 成功才提交，失败释放 shared SAVING 并保留原通知与再次点击，CAS 冲突经原 coordinator reload。已接受写入继续串行完成，beginRestore 可用新 Load 票据替代尚未返回 Save 票据；旧回调不能覆盖 RESTORING/READY，新读等待原写终态。恢复期间保留 committed 并关闭调整。首开/重开/邻章沿同一次 entry resolver 事件向 shared 提交真实入口页，不另算恢复位置。

Desktop tap 在动画前记录逻辑 PageId，key/drag 继续沿原 navigation intent；尺寸或匹配重排用新 owner units 映射同一目标，不把 delayed native index 变成新用户意图。实际 renderer 只有原 viewport ticket、完整槽位已画和 native idle 才能记录 single/dual 阅读；布局反馈不制造进度。Webtoon 单独保留原恢复 anchor，仅条漫恢复/模式入口设置，由实际条漫 settle 消费，不能给 single/dual 绕过 ticket。

Test Mode 读取 actual mounted model 并在 EventQueue 调用原动作。分页几何来自真实 modifier 和本 publisher 生命周期，pager 卸载时清除；实际窗口/owner focus 缺失时不能报告可原生输入。HTTP 动作与外部 physical key/mouse 证据分开。DP02 Android instrumentation 使用上文的公共入口与实际像素边界；Windows 编译和受控 HTTP 响应路由不能替代正式 ART、TLS/代理或真实源产物验收。

Desktop 原生 tap/drag intent 与 composition binding 都保存原共享逻辑位置，
不把 native index 或布尔 userNavigation 当作当前操作权威。intent 选择和 model 写回分别核对
原 generation/sequence，避免旧动画在新 goto 后重新选中过渡页；同页 goto 推进 sequence，
因此原 pager 定位 effect 同时消费逻辑位置。事实或 matching 重排不改变该位置，
仍保留动画中的用户目标。旧反馈拒绝不阻止新发生的 tap/drag 正常进入章节过渡，
且过渡与 terminal 回调不产生阅读进度。

### Desktop 响应体配额与预览缓存的 JVM 边界

小图预览重挂载先获取已有 decoded lease，避免确认分组时仅为缓存命中重新打开 encoded 内容；
缓存未命中仍保留原 pin→decode 路径。缓存中的静态大图仍 pin 原内容，直到最后 region holder/tiles
释放；generation/attempt 的原缓存准入和旧 lease 拒绝规则不变。双页恢复仍沿原动画路径与共享位置
门禁；原生 Compose 离屏夹具必须推进实际帧时间并在同一线程 render/effect/close，不能用 time=0
的历史呈现帧判定重排后的动画已经停稳。

可选事实 HTTP 读取通过 DesktopReaderFactBodySource Java 叶适配器，在 Okio JVM
RealBufferedSource 的公开 source 字段之前施加原 permit，避免 buffered refill 超出剩余字节。
原 buffer 前缀按原字节顺序先消费并计额度，timeout/close 回到同一原响应；部分文件仍由原失败/取消
路径删除。普通 visible、encoded cache、HttpSource.getImage/child-loader 反射、源 client 与 headers
不改。此 adapter 依赖当前锁定的 Okio 3.17.0 JVM 公开字段，不通过反射或抑制 Kotlin internal；
升级 Okio 必须复验真实 chunked、已 buffered 前缀、opaque、预消费超额和 deadline 原向量。

量测与限制对象是宿主响应 body 的实际源读取，不声称限制 TCP/socket 预取，也不能撤回或计量源在
返回 Response 之前自行消费且未留下的字节。已 buffered 前缀超过剩余额度时禁止继续上游读取并
取消可选工作、删除 partial；既已发生的预消费必须独立记录，不能称其被 adapter 消除。
非 RealBufferedSource 的 opaque 响应明确失败，不静默改为无界可选读取；正常 visible 请求仍可
使用原 source 链。这些限制不能替代真实源、代理/TLS 或 ART 验收。


### v3 开发性能观测及系统压力边界

Desktop 开发对照使用原 B2 encoded payload、正常 Factory／图片 owner 与实际 Skia draw；只在原 Ready 接纳和绘制回执成功后计时，第二槽按同 frame 判定。scene.render 成本、render-start 间隔及测试虚拟定时器分别报告，不能换算成 native vsync。300 页固定输入重复 22 张原 payload 并保独立源身份，冷跳源 index 199；软 memory ledger 压力也不等于物理 OOM。72 个基本样本与一次针对性 12 个 300 场景事件补证支持目标图不等待后台样本返回，仍保离散波动和正式体验／微小争用限制。

Android 的 trim 回调设置 pressure，而原普通 HTTP／native 工作转 idle、Activity resume 或 focus 回来时，会查询真实 ActivityManager.MemoryInfo.lowMemory 并据此解除。这是自动恢复边界，不能在测试中把瞬时 trim 当成持续低内存。实际 false 区间可以启动可选工作；再次 true 后不能新增可选 IO／解码，已入物理 decoder 的有界工作按原每条 edge 的 canContinue 检查退出，计数只在实际 completion 清除。开发采样须记录此真实压力波形；持续 foreground busy 的原 SSIV／Coil 门闩证据另行复用，不能声称波形覆盖持续 lowMemory。私有字段／callback 观测仅为开发宿主证据，正式 R8 需核对应产物的实际入口，不能靠 bulk keep／关闭 shrink 替代。

原生观察器只可包装已有非空回调并转发原函数；重绑定清空回调后，不得补造 Ready、绘制或 prepare 回调。ReaderPageImageView 在 beforeImageDraw 与 onImageDrawn 都为空时直接允许原绘制，补入观察函数会改变此分支，因而不能称为透明观测。回调修正后的定向绿色仅证明实际执行场景，不能自动解释旧图片 Error 或旧上下文下未绘制的失败；完整对照、原异常原因和正式运行须分别记录。

### Android 同图缓存写入与响应所有权

原 Android ChapterCache 使用 DiskLruCache，同一个 URL 的 editor 已被占用时，第二次写入返回 false。ReaderPageMaterializeRunner 的逻辑取消不保证旧 HTTP 正文或写入已经物理退出；仅更换请求 generation 不能防止同图重定位产生写入冲突。不能等待旧 materialize job 整体结束：旧 source.getImage 尚未返回时，新的请求仍须能正常完成，迟到的旧失败不得覆盖当前页。

AndroidReaderEncodedPageStore 在原实例内按 EncodedPageRef 租用 Mutex，只串行化 writer、原 index commit 与该 writer 的失败／late-session cleanup。HTTP 请求、encoded 内容读取和匹配解码在锁外，不同 ref 独立。等待者可取消；未进入 writer 的取消不得执行 writer 或删除别人的字节。endSession 立即关闭会话，已进入的写入完成清理后才释放租用，排队者重新核对 session 后拒绝写入。租用引用计数覆盖等待和真实工作退出，归零后移除；不能在逻辑取消或关闭时清空在途租用，制造另一把同 ref 锁。该策略保护同一 store 内的同图写入，不提供跨 loader／store 同 URL 的全局互斥，不增加缓存层、队列、schema 或匹配等待。

AndroidReaderPageFetchPort 持有 source 返回的原 Response，用 use 包住 permit 包装和 store 调用。即使等待同图写入时被取消、writer 尚未读取正文，响应也必须关闭；异常和 CancellationException 保持原传播及 suppressed 规则。维护时必须覆盖真实 HTTP／ChapterCache 与公开重定位、同 ref cleanup 竞争、不同 ref 并行、等待取消、session 关闭及旧 HTTP 迟到失败；仅 mock put 返回 true 无法发现原 editor 冲突。

开发原生冷缓存夹具须先结束 Activity，等待真实普通 materialize／native 工作退出，再调用原 ChapterCache.clear 并验证本次文件和索引清除；不得直接删除 `.0` 文件留下 journal 残留。此准备不等待 optional matcher，挂起旧任务的场景须保持其真实计数到正常目标绘制后才放行。公开 clear 不迁移历史无文件索引项，须如实记录；白盒 journal／私有字段取证不自动适用于正式 R8。

### 未承诺提案与快速导航

原生 pager 可以先挂载尚未绘制的相邻组，并用空 preparing 集合取得 viewport ticket。此时 record 存在不代表漫画已经出现在窗口；locked 与 drawn 都为空的提案不具有必须延续的几何承诺。导航目标进入该提案的伙伴页、且目标不同于原 origin 时，ReaderDualPagePresentationOwner.move 不复用该 incoming record，由新目标按当前事实决策。旧提案沿原离场逻辑取消 appendable 资格，不能凭旧 ticket 再锁定或记录新像素。

已锁定或已绘制的组仍复用原 frame／槽位和历史，不能仅因某页未收到 drawn 回执就重排。preparePage 对未知前页的保护保持：不能猜纵页让后一页先画左侧；新目标在未知前页时选择右侧兜底，随后已就绪图片独立绘制。维护时须同时覆盖空提案快速导航与已锁未画的保护，并通过真实 HTTP 门闩、原按键、目标 JNI／窗口像素及 SQL 进度验证平台接线。进度沿用实际绘制集合最大页码；目标和伙伴均画出时，不要求进度恰好等于目标页码。
