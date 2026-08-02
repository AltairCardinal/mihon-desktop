# 阅读器权威、上游跟踪与 Fork 偏差

## 权威边界

阅读器迁移同时使用三条不能混淆的证据线：

| 证据 | 固定引用 | 用途 |
| --- | --- | --- |
| 固定原版快照 | `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8` | 冻结迁移必须保持的状态、加载、过渡和进度语义 |
| 较新上游跟踪点 | `upstream/main@d7f3ceef5c75294306d0d9495e9ebc5ffca96302` | 捕获固定点之后已经修正的 reader 缺陷与生命周期变化 |
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

固定点到 `d7f3ce…` 没有改变 current + 4、末五页 page-list-only、末页完成和 transition 无按钮语义。
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

## Fork 增强与已知缺口

| 项目 | 分类 | 当前处理 |
| --- | --- | --- |
| generation 取消、迟到结果拒绝 | `CROSS_PLATFORM_RELIABILITY_ENHANCEMENT` | 保留并在 RC-03 纳入唯一 scheduler |
| adjacent portrait pairing | `CROSS_PLATFORM_PRODUCT_ENHANCEMENT` | 作为 presentation 能力保留；固定原版只拆一张宽源图 |
| Desktop 完整下一章预取 | `DESKTOP_PRODUCT_ENHANCEMENT` | RD-02 的显式 policy，不改变 Android 默认流量 |
| cached Error 的 Retry 不再强制重抓 | `PRODUCT_GAP` | 当前 Fork generation 改造丢失 fixed `force`；RC-02 先写失败测试后修复 |
| Android 双页只上报 `firstPage` | `PRODUCT_GAP` | 最终 pair 可能不能按真实末页完成；RC-05/RP-03 改为 settled 可见逻辑页集合 |

相邻 portrait pairing 的 fork 起点为 `bef51fc6924c6a9de185fa0fb2a56ce76309dc19`；固定
`6fbf6df…` 不含 `PagePairingAlgorithm`、`PairingState` 或 `DualPageViewerAdapter`。odd-width split 保留
中心像素是明确 correctness fix。Desktop cover-single、manual spread、edge matching 与 landscape parity
继续作为 Desktop presentation options，不进入 session core。

## 当前迁移状态

`domain/src/commonMain/kotlin/mihon/domain/reader/` 当前验证的是若干窄切片：页面/章节 DTO、解码与缓存
contract、窗口 planner、配对/拆页纯算法、输入导航、章节过滤和滤镜参数。它尚未拥有 page-list executor、
单页 materialize executor、唯一 scheduler、current/previous/next window、跨章激活或进度 effect。

因此 parity manifest 9/43/44/45/47/49/51/54 的 `VERIFIED` 只表示各自窄 capability 已验证；每项的
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
