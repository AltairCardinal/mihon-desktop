# 阅读器共享核心架构

## 状态与目的

本文描述 reader migration 的目标架构和当前边界。当前状态是 `MIGRATING`：

- 已共享并由 Android 生产消费：稳定 session/page 状态、page-list 与单页 materialize executor、唯一
  priority/generation scheduler、encoded store contract、current/previous/next 章节窗口、跨章激活及 settled
  viewport 进度决策；Desktop `PagePreloader` 也消费同一 scheduler；
- 另已共享：解码/cache contract、宽图拆分/配对纯算法、输入导航、章节过滤、reader entry resolver 和滤镜参数；
- RP-01～RP-03 已建立 Desktop `ReaderPresentationStrategy`、稳定 `DisplayUnitId`/`VisiblePageSet` 与 mode
  registry，单页、Webtoon 和 Dual production selector 均已迁移；
- 尚未共享：Desktop production materialize/session/window/progress producer wiring；
- RA-01 已收口 Android：`ReaderViewModel`、`ChapterLoader`、`HttpPageLoader` 与 `ReaderChapter` 只负责
  lifecycle、source/download/local/cache 和旧 View 状态投影；page-list、page-state、scheduler、window 与
  progress 决策全部来自 shared 实现。Desktop `DesktopReaderPageLoader/ReaderScreenModel` 的私有运行决策
  仍待 RD-01 删除。

因此 `domain/src/commonMain/kotlin/mihon/domain/reader/` 现在不是完整的唯一 reader runtime。迁移目标是让
Android 与 Desktop 消费从固定原版 Android 提取的同一个 `ReaderSessionCore`，同时把图形、文件、source、
生命周期和输入差异限制在 adapter。

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
| `reader/session/`、`reader/materialize/` | 稳定逻辑页状态、page-list、单页 materialize、三章窗口 retain/release 与幂等跨章激活；Android production 已接线 | Desktop production materialize/window |
| `reader/scheduler/ReaderRequestScheduler.kt` | P0～P4、原版 current +4、稳定 PageId、有界并发、抢占、Retry 与 generation 拒收；Android/Desktop adapter 已接线 | 相邻章何时进入 P3/P4（由后续 window/policy 产生请求） |
| `reader/storage/EncodedPageStore.kt` | 生命周期、物理存在性、配额/淘汰结果和诊断；Android `ChapterCache` adapter 已接线 | Desktop encoded store 实现 |
| `PageTransform.kt` | 宽图尺寸/切片、纯配对算法、滤镜参数 | session core；pairing 属 presentation |
| `ReaderNavigation.kt`、`reader/progress/ReaderEntryResolver.kt` | tap command、inversion、章节过滤、adjacent result，以及不依赖 UI 方向的故事最早未读入口；Android/Desktop 入口已接线 | 跨章 session 激活、进度 |
| `ReaderProgressPolicy` / `ReadingProgressEvent` / `RecordReadingProgress` | settled active viewport → identity-bearing progress effect、末页完成和幂等事务；Android production 已接线，Desktop 已消费事务 | Desktop viewport/session producer 已切到同一 policy |

parity manifest 9/43/44/45/47/49/51/53/54 通过 `readerCoreMigrationScope` 锁定这些范围；在 RD-01
关闭前，它们不能作为 canonical `ReaderSessionCore` 已接线的证据。

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

RD-02 在 Desktop adapter 中实现 `OFF / FIRST_VIEWPORT / FULL_NEXT_CHAPTER` 三档相邻章图片策略，默认
`FULL_NEXT_CHAPTER`。只有当前章全部逻辑页均为 `Ready(encodedRef)` 后，才会为下一章发出 P4；
`FIRST_VIEWPORT` 只覆盖 presentation 提供的首屏页数，`FULL_NEXT_CHAPTER` 覆盖完整 page list。用户激活
下一章时先取消该章仍在运行或排队的 P4，再由 active viewport 以 P0 请求尚未 Ready 的可见页。切到 `OFF`、
跳向其他章节或关闭 session 都会取消不再有用的策略请求；若尚未进入末五页，`OFF` 还会取消仅由完整预取
触发的 page-list 网络请求。

相邻章 Storage 失败的降级副作用还必须同时匹配当前 target sequence 与仍被 scheduler 接受的 request identity；
旧目标即使在取消后迟到返回，也不能设置新目标的 quota block 或取消新目标 P4。

固定原版的末五页 page-list-only 策略与上述图片策略相互独立：进入末五页后，即使 `OFF` 也可以获取下一章
page list，但不会获取图片。Android 继续使用固定原版串行 current +4 与末五页 page-list-only 流量，不消费
Desktop preference。

`Ready` 只表示 encoded 数据可用。decoded bitmap 属于 viewport 附近的有界平台缓存，完整下一章预取
不能线性保留整章 decoded bitmap。相邻章写入若因 encoded-store 配额或 storage 失败而拒绝，会停止该章
剩余后台请求，不改变当前章状态，也不产生 history、`last_page_read` 或 read effect。

RD-01 已删除 Desktop 主 loader 与网络型 `PagePreloader` 的双获取链；canonical session 产出的 encoded ref
是 presentation decode 的唯一输入。`PagePreloader` 只保留 viewport 附近的有界解码/cache 职责。

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

Pairing、双槽、封面和屏幕宽度禁止进入 `ReaderSessionCore`。双页 settled 时必须上报实际可见的全部
`PageId`；只上报 `firstPage` 会让末页 pair 的进度不完整。

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

Dual renderer 始终挂载一个带水平安全边距、相对窗口居中的固定双槽 frame：封面无论横竖比例、章节长度、
阅读方向或环境 locale 方向，都占绝对物理左槽，右槽为空；普通 pair 只按阅读方向改变两页的物理分配，
环境 RTL 不得再次翻转槽位。单页不会退化为贴边单槽。pair/slot identity 不随任一页的
Loading→Ready/Error 改变。宽图切片继续通过统一 `ZoomablePageBox` 的 `splitHalf/sourceBounds` 渲染，所以
右键保存消费与屏幕相同的实际切片。自动 edge matching 只读取 `PagePreloader` 的有界 decoded cache；matcher
没有 URL 或网络入口，也不拥有 fetch job。production observer 收集 cache revision：晚到解码会触发重算，
新发现与本章已确认 pair 做并集；缓存淘汰不会让当前章节的排版来回跳变。

side padding、crop、双页组合选项与覆盖 drag/fling、只在滚动 settled 后恢复的 auto-scroll 都是 renderer /
presentation option。现有 Desktop `resolvedUrls` 进入三种策略前仍只经过一个无 I/O 的迁移 adapter；该 adapter
不拥有 loader，必须在 RD-01 直接接入 `ReaderSessionCore` snapshot 时删除。三模式 presentation 门禁已关闭，
但这不能证明 Desktop canonical session executor 已接线。

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
包含末页时才完成。effect 携带 session、chapter、page 与 settlement sequence 组成的幂等 key，Android
`ReaderViewModel.onPageSelected` 通过 `ReaderViewportSettlementArbiter` 为每次 viewport settlement 分配单调
token；相邻章加载完成后只有最新 token 可以提交 active window，UI/saved-page/事务写入也在同一串行仲裁中再次检查 token 与 active chapter。旧加载
可以保留为相邻章 page-list 预取，但不能在用户返回当前章后反向激活或写进度。有效 settlement 再通过
`RecordReadingProgress` 写入现有 SQLDelight 章节行；不新增 schema，不改变备份或 `last_page_read`。history 仍由 Android 原阅读计时链负责，
该逐页事务设置 `recordHistory = false`，避免重复 history。已读章节被再次部分阅读时通过 `wasRead` 保留已读
状态；同章节号 duplicate 更新仍只在独立 preference 开启时执行。

RA-01 的 online→download route reset 只接受由同一 canonical Wait/Error generation 捕获的 token。token
同时绑定原在线 `PageLoader` 身份；下载检查期间若 activation 安装了新 generation 或 local/download loader，
旧 preload 的 reset 必须返回失败且立即退出，不能回收较新的 loader。

`ReaderEntryResolver` 接收已经按漫画原始排序配置排列的候选与显式方向：升序取第一个未读，降序取最后一个
未读。Android `getNextUnread`、Desktop 详情页阅读入口和书库“继续阅读”都通过该 resolver，因此不会把 UI
第一项误当作故事最早未完成章节；Desktop 仍保留现有 reader navigation 列表顺序。

## 迁移门禁

| 门禁 | 完成后才允许的声明 |
| --- | --- |
| RC-01～RC-05 | shared state、materialize、scheduler、window、progress contract 完整，Android 是首个生产消费者 |
| RA-01 | Android 不再保留第二套 session/loader 决策 |
| RP-01～RP-03 | Single/Webtoon/Dual 通过同一 SPI，core 无 presentation 分支 |
| RD-01 | Desktop production 只创建 canonical session，空 URL/双 loader/Screen replace 已删除 |
| RD-02 | Desktop 已接入默认完整、可选首屏/关闭的 encoded-only P4 policy；OFF 保留末五页 page-list-only |
| RG-01 | legacy bridge/executor 删除，架构守卫与文档一致 |

RD-01 已以 Android、Desktop production wiring 与行为测试把 manifest 的 `canonicalSessionExecutor` 收口为
`WIRED`；RD-02 的 preference、P4/P0、配额降级和原版 OFF 边界证据归属 capability 45。后续 RG-01 只能删除
不可达 legacy bridge，不能重建第二套 loader、调度器或网络型 decode preloader。

## 验证与失败处理

1. 产品行为严格红→绿→重构；source/HTTP 变更覆盖成功、空、403、429、500、畸形响应和 cached Retry。
2. 每个平台 adapter 使用同一 shared contract 向量；production wiring 被绕过时集成测试必须失败。
3. Compose/pager/Lazy 测试验证 stable key/container identity，而不是扫描源码字符串证明行为。
4. authority fixture 和源码边界扫描只用于 provenance/架构守卫，不能替代 production 行为验收。
5. 数据库、备份和 `last_page_read` 格式保持不变；encoded cache 是可丢弃派生数据。
6. 迁移失败时回滚入口 wiring，不恢复长期双 scheduler、双 progress policy 或 renderer 内 source fallback。
