# 阅读器权威、上游跟踪与 Fork 偏差

## 权威边界

阅读器迁移同时使用三条不能混淆的证据线：

| 证据 | 固定引用 | 用途 |
| --- | --- | --- |
| 固定原版快照 | `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8` | 冻结迁移必须保持的状态、加载、过渡和进度语义 |
| 较新上游跟踪点 | `upstream/main@55be95dd5df7ac985bbc68ea62a5a525611a732f` | 捕获固定点之后已经修正的 reader 缺陷与生命周期变化；`d7f3ceef5…` 到该点没有 reader 路径变更 |
| 当前 Fork 兼容基线 | `9111d70a85565e20940fa4736c97eea8c1a44a0d` | 保护当前 Android/Desktop 已有增强，并登记相对原版的缺口 |

固定快照 `6fbf6df…` 本身是 Fork merge；它的 reader blobs 与第二父提交
`8e0c911f93e60db35dcbe2a9103ac6ea0d803e29` 相同。它是仓库选定的内容权威，不代表可从其
Git ancestry 推断“原版”来源。当前 `app/`、`domain/` 和 `app-desktop/` 都是消费者或迁移产物，
不得反向用来证明某项行为存在于固定原版。

机器可执行证据位于：

- `app-desktop/src/test/resources/parity/fixed-main-reader-fixtures.json`：固定 ref、跟踪 ref、逐 blob
  symbol、行为向量、上游变化和 Fork 偏差；
- `app-desktop/src/test/resources/parity/fixed-main-path-inventory.json`：不依赖运行时 Git 历史的固定
  blob ID 清单；
- `app-desktop/src/test/kotlin/mihon/desktop/parity/ReaderFixedMainAuthorityTest.kt`：精确路径/blob/symbol、
  变异、行为向量和 manifest 范围守卫。

## 固定原版运行链

### 章节与页面状态

- `ReaderViewModel` 原子替换 `ViewerChapters(current, previous, next)`：先 `ref()` 新窗口，再
  `unref()` 旧窗口。
- `ReaderChapter` 以引用计数拥有 `PageLoader`，状态为 `Wait / Loading / Loaded / Error`；引用归零
  时 recycle loader 并回到 `Wait`。
- `ReaderPage` 继承 source `Page` 的稳定 `index` 和
  `Queue / LoadPage / DownloadImage / Ready / Error`，另持有 chapter 与 encoded stream supplier。
- URL 在固定原版可以为空，但空 URL 不是状态。迁移后的 core 必须用显式状态表达加载，不能延续
  Desktop 的 `""` slot 约定。

### loader 分派与 materialize

`ChapterLoader.loadChapter` 负责唯一一次 page-list 建立，并按真实来源选择：

- 已下载章节 → `DownloadPageLoader`；
- 本地目录 → `DirectoryPageLoader`；
- 本地归档 → `ArchivePageLoader`；
- EPUB → `EpubPageLoader`；
- online `HttpSource` → `HttpPageLoader`。

所有 loader 都实现 `PageLoader.getPages/loadPage/retryPage/recycle`。page list 为空时章节进入明确错误，
成功后一次性发布完整稳定的 `ReaderPage` 列表；单页随后独立改变状态。

### 当前页与后四页

固定 `HttpPageLoader` 使用单个串行 `PriorityBlockingQueue`：

1. 显式 Retry 优先级最高；
2. 当前选中页为默认优先级；
3. 当前页之后最多四页为低优先级 adjacent 请求；
4. current + 4 是前向窗口，不包含向后预取。

Fork 的 generation、取消旧 job 和拒绝迟到结果是可靠性增强，不得伪装成固定原版已有能力。

### 末五页只预取相邻章 page list

Pager 与 Webtoon 都使用 `pages.size - page.number < 5`。生产调用链是：

```text
PagerViewer / WebtoonViewer
  → ReaderActivity.requestPreloadChapter
  → ReaderViewModel.preload
  → ChapterLoader.loadChapter
  → PageLoader.getPages
```

这条链建立相邻章的稳定 page list；它不调用 `PageLoader.loadPage`、`HttpSource.getImageUrl` 或
`HttpSource.getImage`，也不等同于把下一章全部图片下载完成。

### 过渡呈现没有 Continue/Cancel

固定 `PagerTransitionHolder` 与 `WebtoonTransitionHolder` 的实际语义是：

- `Loading`：进度与加载文案；
- `Error`：错误与 Retry；
- `Wait` / `Loaded`：不增加任何按钮；
- 相邻章已 `Loaded` 且没有 gap/强制 transition 时，adapter 移除 transition item，把相邻页直接接入
  viewer，实现 seamless 续读。

固定原版 transition 没有 Continue、Cancel 或 Dismiss 按钮。任何设计或测试不得再把这些按钮归因于
固定原版。

### 进度与完成

`ReaderViewModel.updateChapterProgress` 只处理用户选中的非错误逻辑页：先写
`last_page_read = page.index`，仅当 `pages.lastIndex == page.index` 时完成章节。同章节号 duplicate 标记
受独立 preference 控制，不能扩展成“阅读后序章节时批量完成前序章节”。预加载、解码和创建相邻章
都不是进度事件。

## 跟踪点必须吸收的上游变化

固定点到 `55be95dd5…` 没有改变 current + 4、末五页 page-list-only、末页完成和 transition 无按钮语义；
RA-01 开始时复核的 `d7f3ceef5…55be95dd5` 区间也没有 reader 路径变更。
迁移仍必须吸收以下较新修复：

- `bc7f7e70a1de65f1f966e2e31f97457f8ac16ce6`：`ChapterCache.isImageInCache` 同时检查 journal 和
  实体文件，避免 journal 命中但文件已丢失时触发 `FileNotFoundException`。RC-02/RC-03 的 encoded store
  adapter 必须保留这一语义。
- `98bb731b4ddbba9743debeb4442e9346bac48d68`：Reader 初始化不再强制 NonCancellable；RA-01 必须
  保留 cooperative cancellation。
- `c3b99aea…` 及 app-scope/revert 提交改变 Android 生命周期归属；它们需要生命周期审查，但不改变
  本文固定的 page/session 状态向量。
- vertical chapter navigator 与 app-bar 修复属于 presentation 变化；进入 RP 阶段前单独审查，不进入
  shared session core。

完整提交清单及分类保存在 reader fixture，缺任一跟踪提交会使 provenance 测试失败。

## Fork 增强与缺口处置

| 项目 | 分类 | 当前处理 |
| --- | --- | --- |
| generation 取消、迟到结果拒绝 | `CROSS_PLATFORM_RELIABILITY_ENHANCEMENT` | RC-03 已纳入唯一 `ReaderRequestScheduler`，Android 与 Desktop adapter 均消费该策略 |
| adjacent portrait pairing | `CROSS_PLATFORM_PRODUCT_ENHANCEMENT` | 作为 presentation 能力保留；固定原版只拆一张宽源图 |
| Desktop 完整下一章预取 | `DESKTOP_PRODUCT_ENHANCEMENT` | RD-02 的显式 policy，不改变 Android 默认流量 |
| cached Error 的 Retry 不再强制重抓 | `PRODUCT_GAP`（RC-02 已关闭） | RC-02 已恢复显式 Retry 强制重抓；shared executor contract 与 Android production wiring 测试共同保护 |
| Android 双页只上报 `firstPage` | `PRODUCT_GAP` | RC-05 的 shared policy 已支持 settled 可见逻辑页集合；Android 双页 presentation 的完整集合上报仍由 RP-03 关闭 |

相邻 portrait pairing 的 fork 起点为 `bef51fc6924c6a9de185fa0fb2a56ce76309dc19`；固定
`6fbf6df…` 不含 `PagePairingAlgorithm`、`PairingState` 或 `DualPageViewerAdapter`。odd-width split 保留
中心像素是明确 correctness fix。Desktop cover-single、manual spread、edge matching 与 landscape parity
继续作为 Desktop presentation options，不进入 session core。

## 当前迁移状态

`domain/src/commonMain/kotlin/mihon/domain/reader/` 当前验证的是若干已接线的窄切片：页面/章节 DTO、解码与
缓存 contract、配对/拆页纯算法、输入导航、章节过滤和滤镜参数。RC-02 已增加 page-list 与单页 materialize
executor，并由 Android `ChapterLoader`/`HttpPageLoader` 生产链消费；核心只传稳定 descriptor 和 opaque
`EncodedPageRef`，download/local/archive/EPUB/online 的具体 I/O 仍留在 Android adapter。RC-03 已以
`ReaderPageId(ChapterId + sourcePageIndex)` 为请求身份建立唯一 `ReaderRequestScheduler`：P0～P4 顺序、
当前 generation 有界并发、抢占、Retry、generation 取消与迟到拒收均在 shared core 决定；Android
`HttpPageLoader` 使用原版串行 current +4 policy，并在 adapter 层保留一个 stale 物理 permit，使连续
不合作请求的真实 I/O 最多为“当前 policy 并发 + 1”而不会跨 generation 无界增长。Desktop
`PagePreloader` 也消费同一调度器而只负责协程和解码执行。旧
`ReaderPreloadPlanner` 及两端私有优先级解释已删除。`ReaderEncodedPageStore` 同时冻结生命周期、物理
存在性、配额/淘汰和诊断结果，Android 通过 `AndroidReaderEncodedPageStore` 把它接到 `ChapterCache`；
物理写入/删除确认先于逻辑提交，并在每次提交前 reconcile `ChapterCache` 自身 LRU 已删除的 tracked ref。
editor 竞争、缺失文件、session 启动失败和删除失败都发布 storage failure，而不是 Ready 或 Network。
RC-04 已增加唯一 `ReaderChapterWindowReducer`，冻结 current/previous/next 身份、先 retain 后 release、相邻
page-list 预取、Boundary 与幂等跨章激活；Android `ReaderViewModel`/`ReaderChapterWindowOwner` 已消费该
生产决策，并继续保持固定原版“目标章加载完成后再提交 active 窗口”的 UI 时序。相邻预取与激活撞车时复用
同一 retained session 并等待 page-list 终态，不重启 loader；effect 执行与 `ref/unref` 共用锁内 retained
门禁，release 后恢复的旧 effect 会取消且不会创建窗口外 loader。RC-05 已增加
`ReaderProgressPolicy`：只有 active chapter 的 settled visible `ReaderPageId` 集合产生进度，集合内最大逻辑页
决定 `last_page_read`，只有实际末页完成；打开章节、materialize、decode 和预取都不产生进度。Android
`ReaderViewModel.onPageSelected` 在相邻章激活完成后消费该 policy，并由 `ReaderViewportSettlementArbiter`
以最新 settlement token 同时仲裁 active window、UI/saved-page 与串行事务写入；被较新 viewport 取代的旧相邻加载只能留下可复用 page list，不能反向
激活或提交进度。有效事件通过现有 `RecordReadingProgress` 与 SQLDelight 事务写当前目标行；`wasRead` 防止重读部分页清除既有已读状态，history 继续由原计时链单独写入。
同章节号 duplicate 标记仍受 `MARK_DUPLICATE_CHAPTER_READ_EXISTING` 独立控制，并排除当前章自身。

RC-05 同时提取 `ReaderEntryResolver`。Android 原 `getNextUnread` 和 Desktop 详情页/书库继续阅读入口都先按
漫画配置排序，再显式传入升序或降序；两种 UI 输入均选择故事顺序最早的未完成章，而不是直接取列表第一项。
Desktop 已消费 `RecordReadingProgress` 事务，但其 viewport/session progress producer 仍待 RD-01 切换；
Desktop production materialize/window wiring 同样尚未完成。

RA-01 已完成 Android 全链收口：`ReaderViewModel` 的相邻预取只读取 canonical
`ReaderChapterLoadState`，在线内容转为已下载内容时通过绑定 loader 身份与 generation 的原子令牌执行
`ResetChapter`；旧 preload 若与较新的 activation 交错，令牌会失效，不能回收新 loader 或推进新 generation。
`ReaderChapter.State` 只保留为 pager/webtoon 的只读 Android 投影，不再能反向驱动 session。production
契约从真实 `ReaderViewModel` 进入 `ChapterLoader`/`HttpPageLoader`，验证 online source、current +4 shared
scheduler、`AndroidReaderEncodedPageStore` 和 `RecordReadingProgress` 同链执行；download/local/archive/EPUB
分派继续由五路 concrete factory 行为测试保护。初始化仍传播 `CancellationException`，没有恢复
NonCancellable。Android 保留的 `Context`、Source/Download/Local I/O、ChapterCache、View/Bitmap/Coil、触摸和
Activity 生命周期均为 adapter/presentation 边界，不再拥有 page-list、page-state、scheduler、window 或
progress 的第二套生产决策。

RP-01/RP-02 已在 Desktop 建立两个同级 production presentation consumer：`SinglePagedPresentation` 与
`WebtoonPresentation` 通过统一 registry 把稳定 `ReaderPageId` 映射为 `DisplayUnitId`。LTR/RTL 宽页切片只
改变显示顺序，不改变 source identity；单页 settled unit 回报其逻辑页，Webtoon settled viewport 回报全部
可见页，并按固定原版 `WebtoonLayoutManager.findLastEndVisibleItemPosition` 的 last-end-visible/NO_POSITION
规则明确 active 页。两种 renderer 的 key 与固定 Compose 容器均不再
使用 URL，Loading、Ready、Error 和原位 Retry 在同一 identity 下切换。Single 只有 settled pager unit 才
写回完整 `DisplayUnitId`；Webtoon 只有滚动停止后才写回 active/visible PageId 与首个可见 unit 的 offset +
测量高度。Ready 几何变化按相对位置重放，split/merge 后按同一逻辑页回退并服从 Lazy 边界，恢复完成前不发布
错误 viewport。条漫的 side padding、crop 和覆盖 drag/fling、只在 settled 后恢复的 auto-scroll 保持为
presentation option，不持有 page fetch job。由于 Desktop canonical session 尚待 RD-01 接线，当前 URL slot
仍只经无 I/O 临时 adapter 投影为 `ReaderChapterSession`；该 adapter 不代表 Desktop loader/session 已迁移。
Dual 留给 RP-03，因此 ID 43 当前只新增“Desktop Single + Webtoon 已接线”的窄证据。

因此 parity manifest 9/43/44/45/47/49/51/53/54 的 `VERIFIED` 只表示各自窄 capability 已验证；每项的
`readerCoreMigrationScope.canonicalSessionExecutor` 在 RD-01 前必须保持 `NOT_WIRED`。删除或绕过当前
窄能力仍应让各自测试失败，但这些测试不能被引用为“Android/Desktop 已经共用完整 reader core”的证据。

## 维护规则

1. 每个 RC/RA/RP/RD 阶段开始前更新上游跟踪点，先分类语义变化，再修改 shared contract。
2. 固定原版路径/blob/symbol、reader fixture、本文和 manifest 必须同批更新；不能只改自然语言说明。
3. authority/provenance 测试用于证明来源，production behavior/wiring 测试用于证明真实调用链；二者不能
   互相替代。
4. shared core 禁止引用 Android、Compose、Skia、Coil、Voyager、`ReadingMode.DUAL` 或屏幕尺寸。
5. presentation 禁止调用 source/repository；platform adapter 禁止重新决定页序、调度、完成或相邻章。
6. 只有 RC-01～RC-05、RA-01、RP-01～RP-03 和 RD-01 的 production wiring 全部关闭后，才能把
   canonical session executor 标记为 `WIRED`。
