# 原版 Mihon 阅读核心迁移与 Desktop 阅读呈现解耦 Roadmap

- 制定日期：2026-08-02
- 状态：`IN_PROGRESS`
- 上级路线：[`2026-06-30-mihon-desktop-refactor-roadmap.md`](./2026-06-30-mihon-desktop-refactor-roadmap.md) 的 Phase R
- 固定原版权威：`main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`
- 本次核对的上游跟踪点：`upstream/main@55be95dd5df7ac985bbc68ea62a5a525611a732f`（2026-08-02；`d7f3ceef5…` 之后无 reader 路径变更）
- 当前 Fork 兼容基线：`9111d70a85565e20940fa4736c97eea8c1a44a0d`
- 当前进度：从第 9 节“任务清单”的第一个未勾选任务推导；本文不另设活动任务字段

## 1. 结论与目标

可以把双页阅读完全解耦为上层阅读呈现抽象，并让 Android 与 Desktop 复用同一个阅读器运行核心；但“复用原版核心”不能理解为让 Desktop 直接依赖 Android 的 `Activity`、`View`、`Bitmap`、Coil holder 或文件 API。正确路径是：

1. 以固定原版 Mihon 的状态转换、章节窗口、页加载队列、重试、相邻章预取和阅读进度语义为权威，逐段从 Android 实现提取平台无关部分。
2. 每次提取后立即让当前 Android 生产链路成为共享实现的第一个消费者，避免根据行为描述在 `domain` 中重写一套“看起来相同”的新核心。
3. Desktop 只实现源调用、下载/本地文件、encoded cache、图片解码、生命周期和输入等平台 adapter，然后切换到同一个共享 session executor。
4. 单页、条漫、双页成为 Desktop 呈现层的三个同级策略。它们只把稳定的逻辑页映射为显示单元并上报可见页，不负责获取页面、切换章节、持久化进度或标记已读。
5. 迁移结束时，页列表加载、单页状态机、调度、重试、章节窗口和进度提交各只允许存在一个生产实现；不允许保留 Android/Desktop 两套 executor 再套一层同名 facade。

目标结构不是“Desktop 仿照 Android 再实现一次”，而是“Android 和 Desktop 都消费从原版 Android 提取出的同一核心，平台差异收口在 adapter，显示方式收口在 presentation”。

## 2. 权威、术语与产品分类

### 2.1 两类上游基线

| 基线 | 用途 | 更新规则 |
| --- | --- | --- |
| 固定原版 `6fbf6df…` | 证明某项行为是否来自原版 Mihon；固定 blob inventory 和 provenance 不随本 Fork 改写 | 只有经过单独 provenance 审查才能替换 |
| 上游跟踪点 `d7f3ce…` | 发现固定点之后 Mihon 对 reader 的修复，防止迁移一开始就复制已被上游淘汰的缺陷 | 每个迁移阶段开始前 fetch 一次并记录新 commit；语义变化先进入契约审查 |

当前 `app/` 是 Fork 消费者，不能因为它仍是 Android 模块就自动当作“原版证据”。共享核心的每个行为都要注明属于：固定原版、较新上游修复、Fork 跨平台增强或 Desktop 产品增强。

### 2.2 术语

| 术语 | 本文含义 |
| --- | --- |
| 逻辑页 | 漫画源 page list 中的一页，身份由 `ChapterId + sourcePageIndex` 稳定确定 |
| encoded page | 已获取并落入平台缓存/文件的原始图片数据引用；共享核心不得持有 `Bitmap`、Skia 或 Compose 类型 |
| shared reader core | session、chapter/page state、加载 executor、优先级队列、重试、章节窗口、导航结果和进度决策 |
| presentation | 把逻辑页映射成单页、连续条漫、左右双页、宽图切片等显示单元，并把可见逻辑页集合回报给核心 |
| platform adapter | Android/Desktop 的源桥接、下载/本地/归档读取、encoded store、解码、生命周期、DI 和输入适配 |

### 2.3 双页能力的正确分类

- 固定原版 Mihon 支持“把同一张宽源图拆成两半显示”，但没有“把相邻两张 portrait 源页拼成左右跨页”的默认算法。
- 当前 Fork 的 Android 也包含后加入的相邻页配对能力，因此不能把所有 adjacent pairing 证据都标成原版或一概标成 Desktop 独有。
- 目标架构把相邻页配对归类为可选 presentation capability。Desktop 注册 `DualPagedPresentation` 并保留封面单页、手动 spread、edge matching 和 landscape parity；Android 若继续保留 Fork 双页能力，也通过自己的 presentation adapter 使用纯配对算法。
- shared reader core 永远只认识逻辑页，不认识“双页模式”“左页槽”“封面”“spread”或屏幕宽度。

## 3. 当前实现审计与迁移原因

仓库已有 `domain/src/commonMain/kotlin/mihon/domain/reader/`，但目前主要包含 DTO、拆页/配对纯算法、导航规则、预加载窗口 planner 和解码/cache contract；它还不是 Android/Desktop 共用的 reader session executor。

| 维度 | 固定原版/Android 权威行为 | 当前 Desktop 行为 | 迁移缺口 |
| --- | --- | --- | --- |
| Session 所有权 | `ReaderViewModel` 持有 current/prev/next `ViewerChapters`，引用计数保护相邻章 | 每个 `DesktopReaderScreen` 建 runtime；跨章用 `navigator.replace()` 重建 Screen | 章节切换丢 session、队列和缓存，无法平滑复用相邻章 |
| 页列表 | `ChapterLoader` 先取完整稳定 `ReaderPage` 列表，再逐页改变状态 | `ReaderScreenModel` 用 `List<String>` 和 `""` 占位，URL 逐个回填 | URL 同时承担身份、内容和加载状态；无法稳定渲染 |
| 页状态 | `Queue → LoadPage → DownloadImage → Ready/Error`，每页可单独重试 | 第一张任意图片到达就把全章 `isLoadingPages` 置为 false | 全章状态和单页状态混淆，晚到页只能表现为空白或闪烁 |
| 调度 | 当前页高优先级，向后 4 页低优先级，原版队列串行取任务 | `DesktopReaderPageLoader` 对全章 `async/awaitAll`，可见页没有绝对优先权 | 快速翻页时旧请求竞争网络、磁盘和解码资源 |
| 图片链路 | loader 产出 cache stream，holder 观察稳定 page state | 主 loader 产出临时 `file://`；`PagePreloader` fetcher 只按 HTTP URL 再取一次 | 主加载和预解码不是同一数据链，存在重复获取和 cache miss |
| Compose 身份 | Android holder 绑定稳定 `ReaderPage` | Dual viewer 的 `remember`/pager key 依赖 `pageUrls` 或其内容；`ZoomablePageBox` 在空 URL 与真实 painter 间替换子树 | 当前页或下一页从空 URL 变为真实 URL 时可见闪烁 |
| 相邻章预取 | 接近末尾 5 页时加载下一章 page list；没有“当前章全部图片完成后下载完整下一章” | 已有 adjacent loader API，但生产调用链不统一 | 需保留原版 page-list 预取，并把完整下一章预取作为显式 Desktop 策略 |
| 进度 | 仅当前被选中的逻辑页提交 `last_page_read`，到最后一页才完成；可选同章节号 duplicate 规则另算 | 当前 Fork 已有末页完成、圆环和最前未读章节修复，但仍绑定 Screen 生命周期和 URL 数量 | 这些修复必须迁入共享 session 事件，不能在 core cutover 时回归 |

当前 Fork 的 `9111d70…` 是迁移的兼容基线，而不是目标架构完成证据。尤其是加载下一章时不显示取消/继续按钮、直接跨章、圆环进度、只在末页已读和“从列表最下方未读章节开始”等已修正用户行为，后续每个批次都必须保持。

## 4. 目标架构

```text
DesktopReaderScreen / Android ReaderActivity
                    │
                    ├── Presentation registry
                    │     ├── SinglePagedPresentation
                    │     ├── WebtoonPresentation
                    │     └── DualPagedPresentation (可选增强)
                    │              │
                    │              └── DisplayUnit + visible PageId set
                    │
                    ▼
        ReaderSessionCore（唯一运行核心）
          ├── ReaderSessionState / ChapterSession / PageSession
          ├── current / previous / next chapter window
          ├── page-list loader + per-page state machine
          ├── priority scheduler + generation/cancellation/retry
          ├── adjacent chapter policy
          └── progress/read-completion decisions
                    │
                    ▼
        平台无关 ports（接口，不含 UI/图片类型）
          ├── ReaderChapterContentPort
          ├── ReaderPageFetchPort
          ├── ReaderEncodedPageStore
          ├── ReaderProgressPort
          └── ReaderClock / diagnostics
                    │
          ┌─────────┴─────────┐
          ▼                   ▼
 Android adapters       Desktop adapters
 Context/Source         SourceManager/ClassLoader
 Download/Local         Download/local/archive
 ChapterCache           Desktop encoded cache
 Bitmap/Coil            Skia/Coil/Compose
 Lifecycle              Desktop runtime/Voyager
```

### 4.1 模块落点

- 共享核心优先落在现有 `domain/src/commonMain/kotlin/mihon/domain/reader/session/`，复用现有 KMP、协程、错误模型和 reader 纯算法，不先创建第三个 reader Gradle 模块。
- presentation contract 与 Desktop 三种策略落在 `app-desktop/.../ui/reader/presentation/`；纯 `DisplayUnit`/pairing policy 可与 Compose renderer 分包，但不得放入 session core。
- `PageTransform.kt` 等纯宽图切片/配对算法可以继续共享；它们是 presentation 使用的算法库，不是加载核心。
- 只有依赖图审查证明 `domain` 无法在不反向依赖平台模块的情况下承载 executor 时，才单独提出 `reader-core` 模块 ADR；不得在实现中静默扩张模块数。

### 4.2 不可违反的依赖规则

1. shared core 不得引用 Android、Compose、Skia、Coil、Voyager、屏幕尺寸或 `ReadingMode.DUAL`。
2. presentation 不得直接调用 source、HTTP、download provider、repository 或进度持久化。
3. platform adapter 不得自行决定页序、相邻章、已读完成或预加载优先级。
4. UI 不再接收 `List<String>` 作为 reader 状态；渲染 key 必须来自稳定 `PageId/DisplayUnitId`，URL/encoded handle 变化不能改变身份。
5. `Ready` 的核心含义是 encoded 数据可用。decoded bitmap 只允许保留在当前 presentation viewport 的平台有界缓存中。
6. 网络预取、解码预热和显示组合必须是三层不同职责，不得让 Coil 与自建 preloader 同时下载同一页。

### 4.3 核心状态与事件

建议的最小状态面：

- `ReaderSessionSnapshot`：active chapter、previous/next reference、当前稳定 viewport、边界和一次性反馈。
- `ReaderChapterSession`：`Wait / LoadingPageList / Loaded / Error`，page count 在 page list 成功前为 0。
- `ReaderPageSession`：稳定 `PageId` 与 `Queued / ResolvingImage / Downloading(progress?) / Ready(encodedRef) / Error`。
- `ReaderSessionIntent`：打开章节、报告稳定 viewport、重试页/章、请求前/后一章、关闭 session。
- `ReaderSessionEffect`：进度提交、用户可见错误、边界反馈和平台副作用请求；effect 必须可去重。

进入下一章时先原子地替换 active chapter 为 `LoadingPageList(pageCount = 0)`，随后 page list 到达并一次性建立稳定 page identities，最后各页独立变化为 Ready/Error。页面状态变化只更新内容，不替换 pager item、Lazy item 或 zoom container。

### 4.4 加载与预取优先级

| 优先级 | 请求 | 平台策略 |
| --- | --- | --- |
| P0 `INTERACTIVE` | 当前 presentation 报告的所有可见逻辑页；双页可同时为两页；用户显式 Retry | Android/Desktop 必须一致，立即抢占后台任务 |
| P1 `NEARBY` | 当前章从 active page 向后 4 页 | 原版 Mihon 默认语义 |
| P2 `CURRENT_BACKGROUND` | 当前章其余未 materialize 页 | Desktop 默认启用；Android 默认关闭，不能改变原版流量行为 |
| P3 `ADJACENT_METADATA` | 接近末尾 5 页时获取下一章 page list | 原版 Mihon 语义，只得到稳定页列表，不等于全章图片已下载 |
| P4 `ADJACENT_BACKGROUND` | 当前章所有逻辑页均 `Ready(encodedRef)` 后，获取下一章剩余 encoded 内容 | Desktop 显式增强；进入下一章后其可见页自动提升到 P0 |

调度器必须有界、可抢占并按 generation 拒绝过期结果。具体并发数是 policy 配置和性能测试结果，不写死在 UI；原版兼容 policy 保留串行优先队列语义，Desktop background policy 可以提高有界并发，但 P0 永不被 P2/P4 饿死。

### 4.5 Presentation contract

`ReaderPresentationStrategy` 至少完成三件事：

1. 把 `ReaderChapterSession.pages` 映射为稳定的 `DisplayUnit` 序列。
2. 把 UI 的 pager/scroll settled 状态转换为 `VisiblePageSet`，交给核心调度和进度逻辑。
3. 在加载、Ready、Error、Retry 之间保持同一个 `DisplayUnitId` 和容器，只替换容器内状态层。

三个同级实现：

| 策略 | DisplayUnit | 特有职责 |
| --- | --- | --- |
| `SinglePagedPresentation` | 一页或同一宽页的一个切片 | LTR/RTL、宽页切片、稳定 pager key |
| `WebtoonPresentation` | 连续逻辑页/切片 | 可见区多页集合、滚动锚点、自动滚动和 side padding |
| `DualPagedPresentation` | 固定双槽 frame，槽内为逻辑页、切片或空槽 | adjacent pairing、封面单页、forced single、spread、edge matching、landscape parity |

双页封面必须占“以阅读器中心为中心的标准双槽画布”的物理左槽，右槽保留为空；不能把封面贴窗口最左边，也不能因只有一页就把双槽 frame 缩成单槽。物理槽位与阅读顺序分离，并分别覆盖 LTR/RTL 测试，避免方向切换再次改变封面位置。

### 4.6 阅读进度与已读约束

- 只有 presentation 选定并 settled 的 active 逻辑页可以提交进度；预加载完成、decode 完成、创建下一章 session 都不能提交进度。
- Pager 在目标页稳定后提交；Webtoon 使用与原版一致的 active-page resolver，不以“最后一页出现 1 个像素”作为完成。
- 双页 settled 时提交该 display unit 中实际可见的最大阅读进度；包含末页的最终跨页才可完成本章。
- 只有当前章到达最后逻辑页才标记已读。打开或阅读后面的章节不得批量把前序章节标为已读；原版可选的“同章节号 duplicate 标记”必须作为独立 preference 规则测试，不能误扩展为前序章节。
- 存在已持久化的非末页进度时，章节列表显示圆环和页码；完成后才显示已读勾选。
- 漫画主阅读入口使用原版章节排序语义选择故事顺序最前的未完成章节，即当前倒序列表最下方的未读章节；不得直接取 UI 列表第一项。
- 继续沿用现有数据库、备份和 `last_page_read` 格式，不通过批量数据迁移解决运行时问题。

## 5. 原版行为与 Desktop 增强的共存方式

| 能力 | 原版默认 | Desktop 目标 | 放置层 |
| --- | --- | --- | --- |
| 当前页 + 后 4 页 | 有 | 完全复用 | shared scheduler policy |
| 距末尾 5 页加载下一章 page list | 有 | 完全复用 | shared adjacent policy |
| 当前章全 Ready 后加载完整下一章 | 无 | 默认启用的 Desktop 增强，可在设置中选“关闭/首屏/完整下一章” | scheduler policy + Desktop preference/UI |
| 相邻章 transition 呈现 | Loading 显示进度，Error 显示 Retry，Wait/Loaded 无额外按钮；Loaded 后 seamless 接入 | 翻过末页后直接进入下一章 0 页状态，同样不显示 Continue | presentation/navigation adapter |
| 加载相邻章时 Cancel/Dismiss | 原版 transition 不显示该按钮 | Desktop 同样不显示取消按钮；失败时只显示 Retry/返回 | Desktop UI |
| 同一宽图切半 | 有 | 保留 | shared transform + presentation |
| 相邻 portrait 双页 | 固定原版无 | Fork/desktop 可选增强 | presentation |

因此完整下一章预取不伪装成“原版已有功能”，也不侵入 Android 默认 policy；它复用同一调度器、状态和 encoded store，仅由 Desktop policy 追加低优先级请求。

## 6. 非目标

- 不共享 Android View/Bitmap/Context 与 Desktop Compose/Skia renderer。
- 不修改章节数据库 schema、备份格式、历史格式或 tracker 协议。
- 不以删除 Desktop 的双页、自动滚动、右键保存、edge matching 等功能换取核心复用。
- 不在迁移期间顺手重构非 reader 页面、扩展系统或下载队列。
- 不把纯源码字符串扫描当作行为完成证据；架构扫描只能作为补充边界守卫。
- 不长期发布两个 reader executor。短期兼容 adapter 只允许在迁移分支的连续批次之间存在，必须有删除任务和截止门禁。

## 7. 需求追踪矩阵

| 已知问题/目标 | 责任任务 | 最终证据 |
| --- | --- | --- |
| 双页封面贴窗口最左侧 | `RP-03` | 双槽坐标/离屏布局测试 + Windows/macOS 人工验收 |
| “取消”误译成“Dismiss/解雇” | `RD-01` | 加载态不再渲染该按钮；其他场景保留的通用取消动作绑定正确“取消”资源，不复用 Dismiss 文案 |
| 下一章加载不显示取消按钮 | `RC-04`、`RD-01` | 下一章 0 页加载态 UI 集成测试 |
| 下一章加载完不显示继续按钮 | `RC-04`、`RD-01` | 翻过末页直接切 active chapter 的生产 wiring 测试 |
| 先进入下一章，再从 0 页逐步加载 | `RC-01`、`RC-04`、`RD-01` | session 状态序列测试和 E2E |
| 阅读后面章节不批量标记前面章节 | `RC-05`、`RD-01` | 数据库集成测试只更新 active chapter |
| 未到末页不标已读，显示圆环进度 | `RC-05`、`RD-01` | progress contract + 章节列表 mounted UI 测试 |
| 漫画阅读入口选择最前未完成章节 | `RC-05`、`RD-01` | 正序/倒序/过滤章节 fixture |
| 当前/下一页未 Ready 时翻页闪烁 | `RC-01`、`RP-01`、`RP-02`、`RP-03`、`RD-01` | stable key、节点身份和快速翻页离屏测试 |
| 当前章全 Ready 后完整预取下一章 | `RD-02` | 调度顺序、抢占、缓存预算和跨章 E2E |

## 8. 任务状态与 checkoff 规则

- `[ ]`：未完成。任务处于 TODO、DOING、BLOCKED 或 REVIEW 时都保持未勾选。
- `[x]`：实现、独立审查、相关验证和该功能批次提交全部完成，并已在第 13 节登记证据。
- 每个功能批次原则上只产生一个包含 RED 测试、production 实现、重构和必要文档/checkoff 的提交；审查修复最多追加一个提交。
- 任务超过 8 个文件或约 400 行不强拆；对应任务必须记录内聚原因和主要回归风险。
- 第一项未勾选的顶层任务就是当前进度。阻塞时在证据表记录原因，不额外维护另一份活动任务声明。
- 不在 focused 红绿循环后重复跑全量矩阵；阶段门禁和最终收口按第 12 节执行。

## 9. 任务清单

- [x] `R0-01` 冻结原版行为、跟踪上游差异并纠正现有共享核心能力声明
- [x] `RC-01` 提取稳定 ReaderSession/Chapter/Page 状态与逻辑页身份
- [x] `RC-02` 提取原版章节页列表与单页 materialize executor
- [x] `RC-03` 提取统一优先级调度、generation 取消和 encoded store 契约
- [x] `RC-04` 提取 current/previous/next 章节窗口与跨章状态转换
- [x] `RC-05` 提取进度、末页完成和阅读入口选择语义
- [x] `RA-01` 完成 Android 生产链切换并证明没有第二套执行核心
- [x] `RP-01` 建立 Desktop presentation SPI 并迁移单页模式
- [x] `RP-02` 将条漫迁移为同级 presentation 策略
- [ ] `RP-03` 将双页迁移为同级 presentation 策略并修复封面双槽布局
- [ ] `RD-01` 实现 Desktop 平台 adapters 并将生产阅读器切到 shared core
- [ ] `RD-02` 实现当前章全 Ready 后完整预取下一章的 Desktop policy
- [ ] `RG-01` 删除 legacy executor/兼容桥，收紧架构守卫并同步权威文档
- [ ] `RV-01` 完成跨平台全量、运行时和发布产物验收

## 10. 详细任务设计

### `R0-01` 冻结权威行为与差距

- 依赖：无。
- 交付：逐 symbol 对照固定原版、当前上游、当前 Android Fork 和 Desktop；覆盖 `ReaderViewModel`、`ChapterLoader`、所有 `PageLoader`、`ReaderChapter/ReaderPage`、pager/webtoon transition 与进度提交。
- 交付：把原版“当前 +4”“末 5 页只预取下一章 page list”“末页才完成”等做成可执行 fixture；把 Fork generation hardening 和双页增强明确标为 deviation。
- 交付：复核 parity manifest 9/43/44/45/47/49/51/54。现有 DTO/adapter 已验证不等于“唯一 session executor 已共享”；在新生产链完成前不得继续使用超出证据范围的描述。
- TDD：本任务不改变产品行为，不制造长期 RED；固定 blob inventory、fixture 解析和 provenance contract 必须可执行，缺少任一权威路径时失败。
- 验证：authority/parity focused tests、`git diff --check`。
- 关闭条件：审查者可以从 fixture 追到固定原版 symbol，也能区分原版默认与 Desktop 增强；证据记录 commit。
- 预计：2–3 工程日，约 4–8 个文档/fixture/test 文件。

### `RC-01` 稳定 session 状态与页身份

- 依赖：`R0-01`。
- RED：章节初始为 0 页；page list 到达后一次性建立稳定 `PageId`；页从 Queued 到 Ready/Error 时 `DisplayUnitId` 不变；旧 generation 结果不能覆盖新 session。
- GREEN：在 `domain/.../reader/session/` 提取 `ReaderSessionSnapshot`、`ReaderChapterSession`、`ReaderPageSession`、intent/effect reducer；Android `ReaderChapter/ReaderPage` 立即委托或映射到该生产状态，而不是仅添加未使用 DTO。
- 重构：删除共享 API 中把空字符串当加载态的需要；允许源 page URL 本身为空，但状态与身份不依赖其真假。
- 用户行为：不改变 Android UI；为 Desktop 的 0 页进入、逐页加载和无闪烁提供唯一状态基础。
- Focused 验证：domain session contract + Android ReaderPage/ReaderChapter 生产消费测试。
- 关闭条件：断开 Android 对 shared state 的委托会使测试失败；状态类型不引用平台 UI/图片类。
- 预计：3–5 工程日，可能 8–10 个文件/超过 400 行；内聚原因是状态、reducer 和第一生产消费者必须同批可运行，风险是状态事件重复或丢失。

### `RC-02` 章节列表与单页 materialize executor

- 依赖：`RC-01`。
- RED：online success、空 page list、缺失/空 image URL、page-list error、单页 error/retry、download/local/archive 分派；HTTP adapter 覆盖成功、403、429、500 和畸形响应。
- GREEN：从原版 `ChapterLoader`/`HttpPageLoader` 提取 `ReaderChapterContentPort`、`ReaderPageFetchPort` 和 canonical executor，保留 `Queued → ResolvingImage → Downloading → Ready/Error`；Android 原 loader 变为平台 adapter/薄桥并继续走 production holder。
- 重构：核心只接收稳定 page descriptor 和 opaque `EncodedPageRef`，不接收 `InputStream`、`File`、`BufferedSource` 或 Bitmap。
- 用户行为：加载中、单页失败、Retry 和空章节错误与原版一致；预加载失败不覆盖当前可见页。
- Focused 验证：domain executor tests、Android loader tests、MockWebServer 集成测试。
- 关闭条件：Android 当前章的真实 page list 与单页请求经过 canonical executor；删除/绕过 executor 会使集成测试失败。
- 预计：4–6 工程日，约 10–14 个文件/超过 400 行；内聚原因是 port、executor、Android adapter 和 HTTP 集成点不可独立验收，风险是 source/download/local 分派回归。

### `RC-03` 调度、取消与 encoded store

- 依赖：`RC-02`。
- RED：P0 可见页先于 P1/P2/P4；原版向后 4 页顺序；快速翻页取消旧 queued job；不合作的旧请求晚到也被 generation 拒绝；retry 提升优先级；队列并发有界。
- GREEN：把优先级和 generation executor 提取为 shared scheduler；Android 使用原版兼容 policy，保留单队列和 +4 语义；定义 encoded store 生命周期、存在性检查、配额/淘汰结果和诊断接口。
- 重构：`ReaderPreloadPlanner` 并入或委托新 scheduler contract，不能继续让 planner 与两个平台 executor 各自解释一次。
- 用户行为：快速翻页时当前页先加载；旧页晚到不再把当前内容替换成空白、Ready 或 Error。
- Focused 验证：虚拟时间 scheduler contract、Android `HttpPageLoader` adapter、cache eviction/reopen tests。
- 关闭条件：生产 Android queue 的优先级来自 shared scheduler；架构测试只作为“无第二套 policy”的补充证据。
- 预计：4–6 工程日，约 7–11 个文件/可能超过 400 行；风险是取消后页面永久停在 Loading，需覆盖恢复为 Queued 和重选页。

### `RC-04` 章节窗口与跨章状态转换

- 依赖：`RC-03`。
- RED：session 始终正确 retain current/previous/next；相邻章 page list 可预取但不激活；激活下一章先发布 0 页 Loading；失败仅影响目标章且可 Retry；无相邻章发布 Boundary。
- GREEN：从 `ReaderViewModel`/`ViewerChapters` 提取 chapter window、retain/release 和 active chapter transition；Android transition holder 保持原版 UI，Desktop 后续可在翻过末页时直接发 `OpenAdjacent`。
- 重构：核心不包含 Continue/Cancel/Dismiss 文案或按钮决策；这些是 presentation 触发策略。一次 intent 只能激活一次目标章。
- 用户行为：Android 原 transition 行为不变；Desktop 将可直接进入下一章并看到从 0 页开始的真实加载状态。
- Focused 验证：domain chapter-window contract、Android ReaderViewModel production wiring、pager/webtoon transition integration。
- 关闭条件：切换章节不销毁仍在窗口内的 session；取消旧 active chapter 的任务不会取消新章可见页。
- 预计：4–6 工程日，约 9–14 个文件/超过 400 行；内聚原因是窗口生命周期和生产 ViewModel 切换必须原子迁移，风险是引用泄漏或错误回收。

### `RC-05` 进度、完成与入口选择

- 依赖：`RC-04`。
- RED：只浏览中间页只更新 `last_page_read`；settled 最后一逻辑页才完成；预取/解码/打开后续章不写进度；阅读后续章不更新前序章；duplicate preference 单独覆盖；正序/倒序 UI 输入都选择故事顺序最前未完成章。
- GREEN：提取 viewport-settled 到 `ReadingProgressEvent` 的 shared policy，并复用现有 `RecordReadingProgress`；Android 生产 `onPageSelected` 消费，Desktop 后续消费同一 effect。提取 reader entry resolver，不从 UI 列表位置推断。
- 重构：进度 effect 使用 chapter/page identity 和幂等 key，不依赖 URL 数量或 Screen dispose 才第一次提交。
- 用户行为：未到末页显示部分进度而非已读；后面的章节可以单独读；漫画点击阅读从倒序列表最下方未读章开始。
- Focused 验证：domain progress/entry contract、Android progress production test、数据库 integration fixture。
- 关闭条件：测试能证明只更新 active chapter 的目标行；圆环 presentation 所需数据来自持久化进度。
- 预计：3–5 工程日，约 7–12 个文件；超过 8 文件时保持同批的原因是 entry、progress event 和真实 DB wiring 共同构成用户能力，风险是重复 history/tracker effect。

### `RA-01` Android 全链切换审计

- 依赖：`RC-01`–`RC-05`。
- RED：构造一条从 `ReaderActivity/ReaderViewModel` 到 source/download/local、encoded cache、progress repository 的集成契约；任一 shared executor wiring 被替换为 legacy 实现时失败。
- GREEN：把剩余 Android 私有 loader/session 决策降为 adapter 或删除；保留 View、Bitmap、Coil、触摸、Activity 生命周期和 Android 特有 transition UI。
- 重构：核对固定原版和当前上游差异，确保提取没有顺手改变 Android 默认网络量、页序、错误或完成语义。
- 用户行为：Android 阅读器外观与入口不变，加载/重试/章节切换保持原版；Fork 已存在的可选双页能力不被删除。
- 阶段验证：`:domain:jvmTest`、Android reader focused tests、`:app:testReleaseUnitTest`、`spotlessCheck`。
- 关闭条件：页列表、页状态、scheduler、chapter window、progress 各只有 shared implementation；Android 类名可保留，但不得保留第二套决策。
- 预计：3–5 工程日，约 8–15 个文件/超过 400 行；这是 Android 生产 cutover 的单一内聚批次，主要风险是下载、本地归档和 process recreation。

### `RP-01` Presentation SPI 与单页模式

- 依赖：`RC-01`，可在 `RC-02`–`RA-01` 稳定接口后与 Android 收口并行，但不得同时修改 shared state contract。
- RED：同一逻辑页从 Loading 到 Ready/Error 时 pager key 和 zoom container identity 不变；LTR/RTL、宽页切片和返回当前位置稳定；presentation 无 source/repository 调用。
- GREEN：建立 `ReaderPresentationStrategy`、`DisplayUnit`、`VisiblePageSet` 和 mode registry；迁移 `SinglePagePagerViewer`。在 Desktop core cutover 前允许一个有明确删除期限的状态映射 adapter，但不允许新建 loader。
- 重构：Loading/Error/Ready 在固定容器内切换；URL/encoded ref 只作为内容，不参与 key。
- 用户行为：单页快速翻页时不因下一页 URL 晚到闪白；加载失败可在原位 Retry。
- Focused 验证：pure mapping tests、Compose 离屏节点身份测试、single-page mounted UI wiring。
- 关闭条件：Desktop 单页 production selector 使用 SPI；旧单页 viewer 不再自行解释章节/加载状态。
- 预计：2–4 工程日，约 6–9 个文件；若超过 8 文件，内聚原因是 SPI 与首个生产策略必须同时落地。

### `RP-02` 条漫同级策略

- 依赖：`RP-01`。
- RED：多页可见集合、稳定 Lazy key、滚动锚点恢复、晚到 Ready 不跳动、最后页 active resolver、自动滚动暂停/恢复和 split/merge 规则。
- GREEN：迁移 `WebtoonViewer` 为 `WebtoonPresentation` + Compose renderer，只向 core 报告可见/active PageId。
- 重构：webtoon side padding、crop、自动滚动保留为 presentation option；不持有 page fetch job。
- 用户行为：条漫加载中继续保持滚动位置，晚到页面不造成列表闪烁或错误完成章节。
- Focused 验证：webtoon mapping、Lazy layout/scroll、progress resolver mounted tests。
- 关闭条件：单页和条漫通过同一 registry/API 选择，仅 display mapping 不同。
- 预计：2–4 工程日，约 4–7 个文件。

### `RP-03` 双页同级策略与封面布局

- 依赖：`RP-01`、`RP-02`。
- RED：封面在固定双槽 frame 的物理左槽且 frame 居中；LTR/RTL 均不贴窗口边；两张 portrait 的 pair key 在任一页 Loading→Ready 时不变；双页同时上报两个可见 PageId；spread/forced single/edge match/landscape parity 改变组合但不改变 source identity。
- GREEN：迁移 `DualPagePagerViewer`/`DualPageState` 为 `DualPagedPresentation` 和双槽 renderer；配对、宽页切片与 Desktop options 作为 decorators/policy 输入。
- 重构：edge matching 读取 encoded ref/有界 decode，不通过 URL 再发网络请求；右键保存根据 display slice 保存实际可见区域。
- 用户行为：封面位于阅读器中心左页位置；双页快速翻页无 URL 回填闪烁；现有手动 spread 和匹配设置保留。
- Focused 验证：pairing contract、物理坐标/约束离屏测试、稳定 key/zoom state、右键保存切片、Desktop 双页 mounted test。
- 关闭条件：Single/Webtoon/Dual 三者是 registry 中同级策略；core 和 source adapter 均无 dual 分支。
- 预计：4–6 工程日，约 10–15 个文件/超过 400 行；内聚原因是 pairing、双槽布局、稳定身份和现有增强必须一起验收，风险是 RTL、奇数页和宽图组合爆炸。

### `RD-01` Desktop adapters 与生产 cutover

- 依赖：`RA-01`、`RP-03`。
- RED：真实 production factory/DI 能解析；online/download/local/archive page list 到 encoded ref；初始 0 页→稳定页数→逐页状态；跨章不 `navigator.replace()`；快速翻页时可见页抢占且 composition identity 不变；错误可 Retry。
- GREEN：实现 Desktop source/ClassLoader、download/local/archive、encoded store、Skia/Coil decode、progress/lifecycle adapters；`DesktopReaderScreen` 在一次 Voyager Screen 生命周期内持有一个 shared session，并把 snapshot 直接交给 presentation registry。
- GREEN：删除 `resolvedUrls`、`""` slot 和“第一张到达即全章不再 loading”的 UI 决策；主 loader 与 decode preloader 共享同一个 encoded ref，`file://` 与 HTTP 不再走两套获取链。
- GREEN：翻过末页立即激活下一章的 0 页 Loading 状态；不显示取消或继续按钮。目标章失败只显示 Retry/返回，边界显示明确结束反馈；其他仍需取消动作的界面使用正确“取消”资源，不复用 Dismiss 文案。
- 集成：新增/修改 Screen、DI、HTTP wiring 时分别覆盖 Screen 实例化、Voyager 类型、DI 解析和 MockWebServer 成功/空/403/429/500/畸形响应。
- 用户行为：Desktop 获得与原版一致的核心加载/重试/相邻章状态，且保留三种阅读方式和 Desktop 增强。
- 阶段验证：`:domain:jvmTest`、`:app-desktop:jvmTest`、`:test-desktop:test` reader subset、`spotlessCheck`。
- 关闭条件：Desktop 真实入口只创建 `ReaderSessionCore`；断开 production factory 会使集成测试失败；临时 presentation state adapter 已移除。
- 预计：5–8 工程日，约 15–24 个文件/明显超过 400 行；这是不可拆开发布的 Desktop 生产 cutover，主要风险是 extension ClassLoader、下载/本地格式、生命周期清理和 DI。

### `RD-02` 完整下一章后台预取

- 依赖：`RD-01`。
- RED：当前章任一页未 Ready 时不启动下一章完整图片；全部 Ready 后按 P4 启动；用户翻到下一章时其 visible pages 提升 P0 并抢占；退出/跳到其他章取消无用后台请求；失败不阻断当前章、不写阅读进度；encoded 配额不足时有界降级。
- GREEN：在 shared scheduler 的 policy 扩展点实现 Desktop `OFF / FIRST_VIEWPORT / FULL_NEXT_CHAPTER` preference，默认 `FULL_NEXT_CHAPTER`；设置页提供入口和简洁说明。
- GREEN：原版末 5 页 page-list preload 继续独立生效；完整下一章只缓存 encoded 数据，不把整章 decoded bitmap 留在内存。
- 用户行为：当前章已全部加载时，下一章在后台逐步准备；真正翻入下一章无需 Continue，尚未准备完时仍先进入并正常显示 0 页/逐页加载。
- Focused 验证：scheduler policy、cache quota、preemption、settings persistence/UI wiring、跨章 E2E。
- 关闭条件：预取从不触发 history、last page 或 read effect；关闭设置后网络行为退回原版 policy。
- 预计：3–5 工程日，约 6–10 个文件；超过 8 文件时内聚原因是 policy、设置入口和 production E2E 必须同批交付。

### `RG-01` Legacy 清理、守卫和文档同步

- 依赖：`RD-02`。
- RED：架构测试先列出仍可从 production 到达的 `DesktopReaderPageLoader` 全章 `async/awaitAll`、网络型 `PagePreloader`、`resolvedUrls`、reader `navigator.replace()` 和 Android 私有决策实现。
- GREEN：删除或降级为纯平台 adapter；移除迁移 feature flag/兼容桥/dead adjacent loader；清理不再使用的取消/继续字符串调用。
- 文档：同步 `docs/architecture/reader-authority.md`、`reader-shared-core.md`、parity manifest 与 fixed-main inventory；manifest 只在 shared core、Android consumer、Desktop consumer 和行为测试都齐全后声明完整 wiring。
- 守卫：禁止 core 引用 platform/presentation；禁止 presentation 获取 source/repository；禁止平台新增独立 scheduler/read-completion policy。源码扫描仅作边界守卫，完成证据仍来自行为测试。
- 用户行为：无新增入口；保护既有单页、条漫、双页、加载、进度与预取行为不再分叉。
- 验证：相关 domain/Android/Desktop tests、parity tests、`spotlessCheck`、`git diff --check`。
- 关闭条件：生产调用图只有一个 executor；可丢弃缓存升级，不存在用户数据迁移；证据记录删除清单和 commit。
- 预计：2–4 工程日，约 8–15 个文件，主要为删除和文档；超过 400 行时说明删除量，不为降低行数保留死代码。

### `RV-01` 最终跨平台验收

- 依赖：`RG-01`。
- 自动验证：按第 12 节只运行一次最终全量矩阵，并保留测试报告、构建日志和 commit。
- 运行时矩阵：online/download/local/archive；单页/条漫/双页；LTR/RTL；宽图/portrait/奇数页；慢网/错误/Retry；快速翻页；跨章；进度/完成；下一章完整预取开/关。
- Windows/macOS：使用正式 `scripts/build-desktop.sh` 构建并运行固定产物；Windows 完成报告只能引用日志 `Final unpacked EXE:` 指向且实际存在的 EXE。
- Android：debug/release 构建和 reader 关键流程运行验收；确认默认网络行为仍是原版 policy。
- 性能证据：可见页请求不被后台任务饿死、快速翻页无旧 generation 回填、decoded cache 不因完整下一章预取线性增长。
- 关闭条件：所有任务均已独立审查/提交，发布矩阵通过或对真实外部阻塞有明确记录；roadmap 顶层全部勾选。
- 预计：2–4 工程日，范围以测试/构建/证据为主，不再接受功能实现混入。

## 11. 依赖顺序、可并行边界与迁移门禁

```text
R0-01
  └─ RC-01 ─ RC-02 ─ RC-03 ─ RC-04 ─ RC-05 ─ RA-01
          └──────────── RP-01 ─ RP-02 ─ RP-03 ─────┐
                                                    ├─ RD-01 ─ RD-02 ─ RG-01 ─ RV-01
                                      RA-01 ────────┘
```

- `RP-01` 最早在 `RC-01` API 冻结后开始；若 Android 核心迁移仍在修改同一 shared contract，则必须串行，不能让两个任务同时写共享状态模型。
- `RA-01` 与已冻结 contract 上的 `RP-02/RP-03` 可以由不同工作流并行；`RD-01` 必须等待两边完成。
- `RD-01` 是唯一 Desktop executor 切换门禁。在它完成前，不发布“presentation 已抽象但 core 仍双轨”的中间版本。
- `RG-01` 前允许的兼容桥必须命名为 migration-only、列入删除清单且不包含加载/调度决策。
- 任一门禁发现权威假设失效、数据库格式必须变化或平台 port 无法表达原版语义时，暂停并新增 ADR/重规划；不得在 adapter 内偷偷分叉。

粗略工作量为 45–69 工程日：单人连续执行约 9–14 周；在 shared contract 冻结后由 Android 收口与 Desktop presentation 两条无冲突工作流并行，预计可压缩到 6–10 周。该估算不包含等待上游决策、真实 macOS/Windows 构建机或扩展源修复的外部时间。

## 12. 测试与发布验证计划

### 12.1 红绿循环

- 每个产品行为任务先运行本任务 focused test，确认因目标缺口 RED；最小实现后变绿；重构后再跑同一组。
- HTTP/page source 变更必须使用 MockWebServer 经过真实 production adapter，最低覆盖成功、空/缺失、403、429、500、畸形内容。
- DI、Screen、Voyager 和 UI wiring 变化分别使用 DI resolution、Screen 实例化、导航类型和 mounted/离屏 UI 测试。
- shared contract test 对 Android/Desktop adapter 使用同一向量；平台特有双页、ClassLoader、Skia 和设置 UI 另设集成测试。

### 12.2 阶段门禁

| 门禁 | 最小验证 |
| --- | --- |
| `RA-01` Android 核心收口 | `:domain:jvmTest`、Android reader focused、`:app:testReleaseUnitTest`、Spotless |
| `RP-03` 呈现收口 | Desktop reader presentation/Compose tests、三模式 production selector wiring |
| `RD-01` Desktop cutover | `:domain:jvmTest`、`:app-desktop:jvmTest`、reader Test Mode/E2E subset、Spotless |
| `RG-01` 清理 | reader/parity/architecture focused、diff check |

### 12.3 最终一次全量

在同一 worktree 使用 Gradle coordinator 串行运行重型验证；Windows PowerShell 先设置 UTF-8 环境：

```powershell
$ErrorActionPreference = 'Stop'
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
python scripts/gradle-coordinator.py run --key reader-core-final -- ./gradlew :domain:jvmTest :app:testReleaseUnitTest :app-desktop:jvmTest :test-desktop:test spotlessCheck assembleDebug
```

随后运行运行时与正式构建门禁：

```bash
./scripts/desktop-smoke-test.sh
./scripts/build-desktop.sh
```

在支持的 Windows/macOS 环境分别执行构建脚本和真实产物验收。不得用 `app-desktop/tmp/`、Gradle `build/` 或系统 JDK 辅助客户端代替最终应用调用链。

## 13. 进度与证据记录

每次状态变化追加一行；任务 checkbox 只有在 `REVIEW -> DONE` 且 commit 已存在后才能勾选。

| 日期 | 任务 | 状态变化 | RED 证据 | GREEN/重构证据 | 独立审查 | 验证/产物 | Commit |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-08-02 | `R0-01` | `TODO -> DOING -> REVIEW -> DONE` | `r0-reader-authority-red-cmd`：新 authority contract 因缺少 reader fixture 与 manifest migration scope 出现 3 个预期失败 | `r0-reader-review-fix-final2`：authority + manifest contract + Spotless 全绿；22 个固定/lineage blob、18 个 tracked commit、方法体调用链与 deviation provenance 可执行 | 1 轮独立审查 + 1 轮修复复审；3 个 P2 已关闭，复审发现的 ID 40 scope 污染已移除，并由“scope 只能归属 ID 45”断言保护 | `git diff --check`；本批次不改变产品行为，未运行发布构建 | 本批次提交（由本行所在 Git commit 解析） |
| 2026-08-02 | `RC-01` | `TODO -> DOING -> REVIEW -> DONE` | `rc01-domain-red`、`rc01-android-red2`：缺少 session reducer 与 Android production flow；`rc01-review-domain-red`、`rc01-review-android-red`、`rc01-loader-install-red`、`rc01-loader-ownership-red2`：依次复现重复终态、旧 generation 成功/失败、晚构造 loader 和所有权移交竞态 | `rc01-close-domain`：6 项 session reducer contract 全绿；`rc01-close-android`：真实 `ChapterLoader` 的 5 项 generation/loader 竞态以及 Android session、transition、shared parity 全绿 | 1 轮独立审查 + 1 轮修复复审；初审 P1/P2 和复审发现的 loader 所有权窗口均已关闭，未追加第二轮代理复审 | `rc01-close-static`：`:source-api:jvmTest` 与 `domain/source-api/app` Spotless 全绿；`git diff --check`；本阶段未运行全量/发布构建 | 本批次提交（由本行所在 Git commit 解析） |
| 2026-08-02 | `RC-02` | `TODO -> DOING -> REVIEW -> DONE` | `rc02-domain-red`、`rc02-android-red`、`rc02-adapter-red`：缺少 canonical executor、Android production port/wiring 与 HTTP adapter；`rc02-review-storage-red`、`rc02-review-dispatch-red`、`rc02-authority-red`、`rc02-review-app-error-red`、`rc02-review-manifest-red`、`rc02-review-default-factory-red`：依次复现 Storage 误分类、真实 route/factory 缺口、Retry 缺口未闭环、已有 AppError 被重包、manifest capability 污染和默认 concrete factory 未执行 | `rc02-close-domain`：materialize/session/parity contract 全绿；`rc02-final-android`：cache、page-list、五路分派、HTTP 403/429/500/畸形响应、Retry、同页旧 generation 与 production wiring 全绿；`rc02-final-authority`：完整 reader authority 类全绿 | 1 轮独立审查 + 1 轮修复复审；初审 4 项与复审 3 个 P2 均已关闭，复审后的修复由主代理通过 AppError/取消、默认 concrete factory 与 capability 唯一归属行为测试核验，未追加第二轮代理复审 | `rc02-final-spotless`：domain/app Spotless 全绿；`git diff --check`；`app-desktop` 未配置 Spotless task，以 Kotlin 编译、完整 authority 测试和 diff 校验覆盖；本阶段未运行全量/发布构建 | 本批次提交（由本行所在 Git commit 解析） |
| 2026-08-02 | `RC-03` | `TODO -> DOING -> REVIEW -> DONE` | `rc03-domain-red`、`rc03-android-red`、`rc03-desktop-red`：缺少 shared scheduler/store 与两端生产消费；`rc03-review-physical-bound-red`、`rc03-review-store-red`、`rc03-review-eviction-transaction-red2`、`rc03-review-cache-api-red`、`rc03-review-startup-diagnostics-red`、`rc03-review-reconcile-classify-red`：依次复现真实 I/O 跨 generation 无界、缺失实体仍 Ready、删除失败后逻辑先行、editor 竞争静默成功、启动诊断漏记、双 LRU phantom 与 lifecycle 错误误分为 Network | `rc03-review-domain-final`：scheduler、store、固定原版 +4 contract 全绿；`rc03-review-android-final3`：生产抢占/迟到拒收、真实 I/O bound、editor 竞争、两阶段淘汰、reopen、startup diagnostics、双 LRU reconcile 与 Storage 分类全绿；`rc03-review-desktop-final2`：Desktop scheduler consumer 与完整 authority/manifest 全绿 | 1 轮独立审查 + 1 轮修复复审；初审 2 个 P1、2 个 P2 及复审期间指出的 lifecycle 分类/双 LRU 候选点均已关闭；最终结论 `APPROVED / NO_P0_P1_P2` | `rc03-review-format-final2`：domain/app Spotless 全绿；`git diff --check`、manifest JSON 解析与 legacy policy guard 通过；本阶段按分层策略未运行全量/发布构建 | 本批次提交（由本行所在 Git commit 解析） |
| 2026-08-02 | `RC-04` | `TODO -> DOING -> REVIEW -> DONE` | `rc04-domain-red`、`rc04-android-red2`：缺少 shared chapter-window reducer 与 Android production owner/wiring；`rc04-loader-wait-red`、`rc04-review-stale-prefetch-red`：依次复现激活未等待相邻预取，以及 release 后旧 effect 重启窗口外 loader | `rc04-final-domain`：chapter-window/session contract 全绿；`rc04-final-android-related`：ViewModel、owner、loader/generation、pager/webtoon transition 与 shared parity 全绿 | 1 轮独立审查 + 1 轮修复复审；初审 P1 已通过与 `ref/unref` 同锁的 retained 门禁及 production 交错测试关闭，最终结论 `APPROVED / NO_P0_P1_P2` | `rc04-final-authority`、`rc04-final-spotless-check` 全绿；manifest JSON 解析与 `git diff --check` 通过；本阶段按分层策略未运行全量/发布构建 | 本批次提交（由本行所在 Git commit 解析） |
| 2026-08-02 | `RC-05` | `TODO -> DOING -> REVIEW -> DONE` | `rc05-domain-red`、`rc05-android-red`、`rc05-data-red`、`rc05-desktop-entry-red`、`rc05-authority-red`：缺少 shared progress/entry 与真实生产/数据库/入口证据；`rc05-settlement-race-red2` 复现旧相邻 settle 反向激活并提交 B 章进度；`rc05-arbiter-red` 固定 latest token 与串行事务仲裁 | `rc05-android-final-gate`：进度、末页、旧 settle 竞态、仲裁器、duplicate、window/错误与入口组全绿；`rc05-domain-final-gate`、`rc05-data-final-gate`：shared contract 与真实目标行事务全绿 | 1 轮独立审查 + 1 轮修复复审；初审 P1 通过 active-window latest token guard 关闭，复审 P2 通过独立 `ReaderViewportSettlementArbiter` 并发测试与最终证据行校准关闭；未追加第二轮代理复审 | `rc05-desktop-authority-final-gate`、`rc05-spotless-check-final` 全绿；manifest JSON、`git diff --check` 通过；本阶段按分层策略未运行全量/发布构建 | 本批次提交（由本行所在 Git commit 解析） |
| 2026-08-02 | `RA-01` | `TODO -> DOING -> REVIEW -> DONE` | `ra01-session-reset-red`：缺少 canonical storage-route reset；`ra01-stale-storage-reset-red`：缺少绑定 loader/generation 的原子令牌，旧 preload 可回收较新的 activation loader | `ra01-production-contract-final-focused-2`、`ra01-default-composition-focused`：真实默认 `ReaderViewModel -> ChapterLoader -> HttpPageLoader -> shared scheduler/encoded store/progress` 同链全绿；`ra01-stale-storage-reset-green`、`ra01-review-fixes-focused`：storage reset 竞态、窗口与真实 loader 资源清理全绿 | 1 轮独立审查 + 1 轮修复复审；初审 P1/P2 通过原子 reset token 与 `finally` 回收关闭；复审确认两项关闭，并发现 manifest 精确集合 P2，由 `ra01-task3-parity` RED、`ra01-task3-parity-final` GREEN 关闭，未追加第二轮代理复审 | `ra01-domain-full`、`ra01-android-reader-focused`、`ra01-app-full`、`ra01-authority-final`、`ra01-spotless-green` 全绿；Task 3 为 274 项测试；manifest/fixture JSON 与 `git diff --check` 通过；本阶段未运行发布构建 | 本批次提交（由本行所在 Git commit 解析） |
| 2026-08-02 | `RP-01` | `TODO -> DOING -> REVIEW -> DONE` | `rp01-red`、`rp01-manifest-red`、`rp01-product-contract-red`：缺少 presentation SPI、稳定 identity 与 production/authority 证据；`rp01-review-red`：复现未 settled 页误报、宽页第二切片丢失、错误/重试卸载 Pager 及旧产品回归断言失效 | `rp01-green-2`、`rp01-product-contract-green-2`：Single strategy、固定容器与 production selector 首次全绿；`rp01-contract-green-2`：切片恢复、settled-only 上报、原位 Retry、model/viewport、产品回归及两项权威合同共 108 项全绿 | 1 轮独立审查 + 1 轮修复复审；初审 2 个 P1 与 1 个 P2 通过完整 `DisplayUnitId` 写回、`pagerState.settledPage`、单页 viewport 持续挂载和回归契约更新关闭；复审结论 `PASS / NO_P0_P1_P2` | `rp01-related-green`：`:domain:jvmTest` + `:app-desktop:task3ParityVerification` 全绿；`rp01-spotless-check-2`、manifest JSON 与 `git diff --check` 通过；Webtoon/Dual 与 Desktop canonical executor 仍分别留给 RP-02/RP-03/RD-01 | 本批次提交（由本行所在 Git commit 解析） |
| 2026-08-02 | `RP-02` | `TODO -> DOING -> REVIEW -> DONE` | `rp02-red`、`rp02-evidence-red`：缺少 Webtoon 同级策略、稳定 Lazy identity 与 production/authority 证据；`rp02-review-red`、`rp02-layout-authority-red`：复现尺寸变化锚点跳动、固定原版末页 resolver 偏差、拖拽后 fling 期间自动滚动抢占及 layout authority 缺口 | `rp02-green-2`、`rp02-product-1`、`rp02-evidence-green-3`：Webtoon strategy、固定容器、production selector 与权威证据全绿；`rp02-review-green-3`、`rp02-review-contract-green`：真实 `LazyListState` 几何恢复、split/merge 回退、`NO_POSITION`、拖拽到 fling 暂停及完整修复合同全绿 | 1 轮独立审查 + 1 轮修复复审；初审 1 个 P1 与 2 个 P2 已由实测 item size 相对锚点、固定原版 resolver 语义和跨 fling 暂停状态关闭；复审结论 `PASS / NO_P0_P1_P2` | `rp02-related-final`：`:domain:jvmTest` + `:app-desktop:task3ParityVerification` 全绿；`rp02-review-spotless`、manifest/inventory JSON 与 `git diff --check` 通过；Dual 与 Desktop canonical executor 仍分别留给 RP-03/RD-01 | 本批次提交（由本行所在 Git commit 解析） |

`R0-01` 范围说明：本批次保持 8 个内聚文件，但逐 symbol/blob/marker 夹具、18 项上游分类、变异测试和
权威文档合计超过 400 行。它们共同构成一个不可拆分的 provenance 关闭条件；拆开会让 manifest 或文档
暂时失去机器证据。主要风险是固定证据陈旧、错误 ref 或窄能力被描述成完整 session，已由 Git 对象/
谱系、限定方法体、deviation evidence 与 manifest scope 测试控制。

`RC-01` 范围说明：本批次保持 8 个内聚 production/test 文件，但 shared session 状态、reducer、Android
`ReaderChapter/ReaderPage` 即时映射、`ChapterLoader` generation 所有权与真实并发测试合计超过 400 行。
这些内容必须同批运行才能证明 Android 不是只消费未使用 DTO。主要风险是重复终态、旧 page-list 回填、
旧 loader 覆盖或泄漏，以及 URL 为空时身份漂移；已由首终态门禁、`ChapterId + sourcePageIndex`、原子
loader 移交/单次回收和 5 项 production 竞态测试控制。Android UI、页加载优先级与持久化行为未改变。

`RC-02` 范围说明：本批次约 20 个内聚 production/test/authority 文件并超过 400 行，原因是 shared port、
canonical executor、Android adapter、download/local/archive/EPUB/online 分派、真实 HTTP/缓存错误边界与
机器权威必须同批可执行；拆开会产生未消费 core 或无法证明 production wiring 的中间状态。主要风险是
Retry 未强制重抓、旧 generation 回填、平台流对象泄漏进 core、local/storage 错误误分类及 factory 路由
漂移；已由 opaque `EncodedPageRef`、原子 materialize event、generation 发布门禁、默认 concrete factory、
MockWebServer 与 capability 唯一归属测试控制。Android UI、当前 +4 调度策略和 Desktop presentation 未改。

`RC-03` 范围说明：本批次约 24 个内聚 production/test/authority/文档文件并超过 400 行，原因是 shared
scheduler、encoded store/index、Android 物理 cache adapter、Desktop 首个 scheduler consumer、真实并发/
磁盘测试和 capability 证据必须同批闭环；拆开会留下未消费 policy、无法分类的 storage failure 或逻辑/
物理缓存双真相。主要风险是 P0 被后台任务饿死、取消页永久停在 Loading、旧 generation 回填、真实 I/O
跨 generation 无界、editor 竞争发布空 Ready、删除失败产生孤儿、物理/逻辑 LRU 分叉及 diagnostics 失真；
已由稳定 `ChapterId + sourcePageIndex`、one-current-plus-one-stale permit、generation 接受门禁、两阶段物理
淘汰、commit 前 reconcile、Storage 分类和 production/authority 行为测试控制。current/previous/next window、
进度 policy 与 Desktop materialize executor 仍分别留给 `RC-04`、`RC-05` 和 `RD-01`。

`RC-04` 范围说明：本批次约 16 个内聚 production/test/authority/文档文件并超过 400 行，原因是 shared
chapter-window reducer、Android 引用所有权、ViewModel 跨章提交、page-list generation 复用、原版 transition
观察器和 capability 证据必须同批闭环；拆开会留下未被生产消费的窗口或无法证明的引用生命周期。主要风险是
相邻章重复激活、仍在窗口内的 session 被错误回收、预取与激活创建第二个 loader，以及 release 后旧 effect
恢复并泄漏窗口外资源；已由预期 from/target、先 retain 后 release、同 generation 终态等待、与 `ref/unref`
同锁的 retained 门禁和 production 交错测试控制。Android 原 transition UI/时序保持不变，Desktop production
window/完整 session executor 仍留给 `RD-01`，进度 policy 留给 `RC-05`。

`RC-05` 范围说明：本批次 26 个内聚 production/test/authority/文档文件并超过 400 行，原因是 settled
viewport policy、Android latest-settlement 仲裁、现有 SQLDelight 事务、duplicate preference、两端 reader
entry 与 capability 证据共同构成同一用户能力；拆开会留下未消费 policy、无真实目标行证明或入口语义分叉。
主要风险是旧相邻加载反向激活、连续写入乱序、部分重读清除已读、末页前提前完成、阅读后序章批量完成前序章、
history 重复以及升/降序列表选择不同故事章节；已由 `ReaderViewportSettlementArbiter`、active chapter 门禁、
identity-bearing 幂等 key、`wasRead`、独立 duplicate fixture、真实内存数据库与两端 production 入口测试控制。
数据库 schema、备份和 `last_page_read` 格式未改变；Desktop viewport/session producer 仍明确留给 `RD-01`。

`RA-01` 范围说明：本批次 19 个内聚 production/test/authority/文档文件并超过 400 行，原因是 Android
`ReaderViewModel` 默认 composition、canonical session 投影、source/cache/progress 真实链、storage route
竞态和 manifest 精确证据必须同批关闭；拆开会留下可回写的 legacy state、无法证明的默认 factory 或证据与
production 漂移。主要风险是在线失败转下载时旧 preload 回收新 activation loader、异常路径泄漏真实 loader、
取消被吞掉和原版 current +4 网络量漂移；已由同锁 loader/generation token、`finally` 回收、取消传播与默认
`HttpPageLoader` 端到端契约控制。Android View/Bitmap/Coil、触摸、Activity/process 生命周期和可选双页 UI
仍是平台 adapter/presentation 边界；Desktop 完整 session executor 仍明确留给 `RD-01`，最终发布验收留给
`RV-01`。

`RP-01` 范围说明：本批次 17 个内聚 production/test/authority/文档文件，SPI、首个 Single strategy、稳定
Pager/Compose identity、settled 位置写回、旧 Desktop 状态临时 adapter、production selector 与 capability
证据必须同批落地；拆开会留下未消费策略或无法证明的挂载行为。主要风险是拖拽中提前提交进度、宽页返回错到
第一切片、URL 晚到替换 key、错误/Retry 卸载 Pager，以及误称 Desktop executor 已迁移；已由 settled-only
离屏测试、完整 `DisplayUnitId`、固定容器 identity、outer viewport/model 测试和 `NOT_WIRED` scope 守卫控制。
临时 adapter 不执行 I/O，明确由 RD-01 删除；Webtoon 与 Dual 仍留给 RP-02、RP-03。

`RP-02` 范围说明：本批次 21 个内聚 production/test/authority/文档文件并超过 400 行，原因是 Webtoon
strategy、真实 Lazy 几何锚点、固定容器 renderer、production wiring、设置项、固定原版 layout provenance
与行为测试必须同批闭环；拆开会留下未消费策略、无法证明的滚动恢复或 presentation/authority 状态漂移。
主要风险是页面尺寸晚到导致跳动、错误 active page 提前完成章节、自动滚动与用户 fling 冲突，以及 split/merge
后丢失逻辑页；已由真实 `LazyListState` 离屏测试、固定原版 `NO_POSITION` 语义、跨 fling 暂停状态、稳定
`DisplayUnitId` 和同页有界恢复控制。临时 URL adapter 仍由 RD-01 删除；Dual 保持留给 RP-03。

建议状态记录使用 `TODO / DOING / BLOCKED / REVIEW / DONE`；状态只用于解释，checkbox 仍是唯一完成标记。

## 14. 风险、回滚与完成定义

### 14.1 主要风险

| 风险 | 控制 |
| --- | --- |
| “共享”仅停留在 DTO，平台仍各跑各的 | 每项 shared 能力必须有 Android 与 Desktop production wiring 测试；最终调用图审计 |
| 直接搬 Android 类型导致 Desktop stub 扩散 | core API 平台类型禁入守卫，所有文件/图片能力走 port |
| 为避免大提交而长期双轨 | 只按可独立验收的功能批次拆分；Desktop cutover 不发布中间双轨版本 |
| 后台完整预取抢占当前页或耗尽内存 | P0 抢占、encoded-only、decoded viewport cache、配额和取消测试 |
| 双页组合在状态更新时重排 | `PageId/DisplayUnitId` 稳定，尺寸/配对变化通过受控 presentation transaction 提交 |
| 上游 reader 在迁移期间变化 | 每阶段记录 upstream ref，先更新 contract 再 cherry-pick/重写 shared implementation |
| 进度 side effect 重复 | settled event + chapter/page identity + 幂等 key；session 切换和 dispose 测试 |

### 14.2 回滚

- 数据库和备份格式不变，因此代码回滚不需要数据降级；encoded cache 是可丢弃派生数据。
- 每个功能批次保留一个可回滚提交边界。失败时回滚入口 wiring 到上一已验证批次，不恢复长期双 scheduler/双 progress policy。
- `RD-01` 切换失败时整体回滚 Desktop composition root，不在 renderer 中增加特殊 source fetch fallback。
- 已发布版本若需紧急回滚，可清理新版 encoded cache namespace；不得删除下载、数据库或用户原图。

### 14.3 Roadmap 完成定义

只有同时满足以下条件，本文才可标记完成：

1. Android 与 Desktop 的 page list、单页状态、调度、重试、章节窗口和进度完成由同一个 shared reader core 执行。
2. Single、Webtoon、Dual 是同一 presentation registry 下的同级策略，双页逻辑不渗入 core。
3. Desktop 不再以空 URL 表示加载，不再因章节切换重建整个 reader Screen，不再由 URL 变化决定 item identity。
4. 本文需求追踪矩阵全部有 production 行为测试和真实运行时证据。
5. 原版默认与 Desktop 增强在代码、设置、测试、authority 文档和 parity manifest 中都有明确分类。
6. legacy executor、迁移 bridge 和未使用 API 已删除，最终全量与 Windows/macOS/Android 发布门禁通过。
