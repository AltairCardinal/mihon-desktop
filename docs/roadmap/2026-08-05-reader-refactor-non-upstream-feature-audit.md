# Reader 重构期间非上游 Mihon 特性审查报告

日期：2026-08-05

审查对象：`2026-08-02-reader-core-migration-and-presentation-roadmap.md` 及其实现提交

性质：provenance、parity authority 与 capability 分类审查

## 1. 结论

Reader 重构在迁移共享核心和 Desktop presentation 的同时，引入或正式确立了若干原版 Mihon 不具备的产品行为、平台策略和可靠性契约。

其中，固定双槽、封面固定物理左槽和固定 4:3 frame 已确认不是原版 Mihon 行为。除此之外，本次审查还发现以下需要纠正 provenance 或 capability 分类的内容：

1. Webtoon 相对几何锚点恢复及 split/merge 回退；
2. Webtoon 自动滚动在拖拽和惯性滚动期间暂停；
3. 相邻双页组合按最大可见源页提交阅读进度；
4. 稳定 `DisplayUnitId` 与 Loading/Ready/Error 原位状态切换；
5. latest-settlement-wins、串行提交、幂等键与 drain 契约；
6. encoded page store 的 session、字节预算、诊断与原子写入契约；
7. Desktop 下一章整章预取及其调度策略。

这些能力不一定应被删除。多数属于有价值的 Desktop 产品增强、Compose presentation 策略或跨平台可靠性增强。需要纠正的是：不得把本地实现机制或产品决策作为 fixed-main authority、原版 parity 或上游 presentation 迁移证据。

## 2. 审查范围与方法

### 2.1 对比基线

| 角色 | Git revision | 用途 |
|---|---|---|
| Reader roadmap 起点 | `0b74079e67f3521d2fe977d0787bd9f85167ccd1` | 判断能力是否在本轮重构前已存在 |
| 固定上游基线 | `6fbf6dfc` | 核对 roadmap 所声明的 fixed-main authority |
| 官方 Mihon 当前版本 | `21c63579` | 排除能力只是在固定基线之后进入官方 main 的可能性 |
| Reader 重构完成状态 | `95b82fc1039f772d4f8688855f2b06e16f983eb5` | 检查最终实现和治理材料 |

审查覆盖 RC-01 至 RG-01 的 production 提交，重点提交包括：

| 提交 | 内容 |
|---|---|
| `06a1138d` | Reader scheduler 与 encoded cache |
| `6330c619` | 共享进度与入口语义 |
| `464cbefa` | Single-page presentation 迁移 |
| `71aa3c45` | Webtoon presentation 迁移 |
| `c0177157` | Dual-page presentation 与固定封面双槽 |
| `dcb2dfb2` | Desktop 切换到共享 reader session |
| `6ee9473c` | 有界下一章预取 |

### 2.2 判断标准

每项能力分别检查：

1. 官方 Mihon 是否存在相同用户语义；
2. 官方是否存在相同状态转换和失败处理；
3. 重构前 Desktop 是否已经具备该能力；
4. 本轮是迁移既有能力、引入新产品行为，还是新增平台实现机制；
5. roadmap、architecture 文档和 `parity-manifest.json` 是否给出了准确的 deviation 分类。

仅凭类名或符号不存在不能单独证明行为不存在。本报告同时核对了官方 reader 的页面加载、Webtoon 可见页计算、进度提交、缓存和宽图拆分流程。

## 3. 已确认的固定双槽 provenance 问题

### 3.1 实际来源

`DualPageDisplayUnitFrame`、固定物理双槽和 `DUAL_PAGE_FRAME_ASPECT_RATIO = 4f / 3f` 首次出现在 `c0177157`：

- [`DualPagePagerViewer.kt`](../../app-desktop/src/main/kotlin/mihon/desktop/ui/reader/DualPagePagerViewer.kt)

原版 Mihon 的 pager 使用可用视口，并通过 scale type 控制图片缩放。原版所谓“双页拆分”是把单张横向宽图拆成两个顺序显示部分，不包括：

- 两张相邻 portrait 源页配对；
- 固定左右物理槽；
- 封面固定左槽、右槽留空；
- 固定 4:3 双页 frame；
- 相邻页边缘匹配和人工配对。

### 3.2 正确分类

| 能力 | 建议分类 |
|---|---|
| 原版宽图拆分 | `UPSTREAM_MIHON_BEHAVIOR` |
| 相邻 portrait pairing | `CROSS_PLATFORM_PRODUCT_ENHANCEMENT` |
| 固定物理槽 identity | `DESKTOP_PRESENTATION_POLICY` |
| 封面固定左槽 | `DESKTOP_PRODUCT_ENHANCEMENT` |
| 固定 4:3 frame | `DESKTOP_PRESENTATION_POLICY`，且需要重新进行 UX 论证 |

稳定槽位 identity 与 frame 几何尺寸是两个独立约束。即使保留稳定左右槽，也不能由此推出 frame 必须为 4:3。

## 4. 其他非上游能力

### 4.1 Webtoon 相对几何锚点恢复

RP-02 新增了基于 `DisplayUnitId`、相对像素偏移和页面映射的滚动锚点恢复：

- [`WebtoonViewer.kt`](../../app-desktop/src/main/kotlin/mihon/desktop/ui/reader/WebtoonViewer.kt)
- [`ReaderPresentation.kt`](../../app-desktop/src/main/kotlin/mihon/desktop/ui/reader/presentation/ReaderPresentation.kt)

该实现会在 Ready 状态改变项目几何尺寸时恢复相对位置，并对图片 split/merge 后身份变化提供回退。官方 Mihon 使用 RecyclerView/LayoutManager 维护 Webtoon 位置，没有相同的 `DisplayUnitId`、相对几何锚点或 split/merge fallback 契约。

判断：这是 Compose Desktop 的 presentation/可靠性策略，不是上游 reader presentation 语义。

建议分类：`DESKTOP_PRESENTATION_POLICY` 或 `DESKTOP_RELIABILITY_ENHANCEMENT`。

### 4.2 Webtoon 自动滚动交互暂停

RP-02 引入 `WebtoonAutoScrollPauseState`，使 Desktop 自动滚动在以下阶段暂停：

- 用户主动拖拽；
- fling 或其他滚动尚未 settlement；
- 用户交互结束但列表仍在滚动。

自动滚动本身在 roadmap 开始前已经存在于 Desktop fork；本轮新增的是交互暂停和恢复状态机。原版 Mihon 不具备这套 Desktop 自动滚动能力，也没有对应暂停契约。

建议分类：`DESKTOP_PRODUCT_ENHANCEMENT`。

### 4.3 双页组合按最大可见源页提交进度

共享进度策略接收 `visiblePageIds`，并以最大 `sourcePageIndex` 作为 settled page：

- [`DualPagedPresentation.kt`](../../app-desktop/src/main/kotlin/mihon/desktop/ui/reader/presentation/DualPagedPresentation.kt)
- [`ReaderProgressPolicy.kt`](../../domain/src/commonMain/kotlin/mihon/domain/reader/progress/ReaderProgressPolicy.kt)

这项行为适用于“两张相邻源页组成一个 display unit”。原版宽图拆分的左右两半仍属于同一个源页，因此不能为该规则提供上游依据。

该规则还影响章节完成条件：双页中第二张源页可能在一次 settlement 中推进进度或触发完成。因此它不是纯 presentation 细节，而是 fork 产品语义。

建议分类：`CROSS_PLATFORM_PRODUCT_ENHANCEMENT`，并与原版单源页宽图拆分分别记录。

### 4.4 稳定 DisplayUnit 身份与原位状态切换

RP-01 至 RP-03 建立了以下 presentation 契约：

- `DisplayUnitId` 和 `DisplaySlotId`；
- Loading、Ready、Error 共用稳定展示身份；
- Retry 在原容器内发生；
- 页面内容迟到时不替换 display unit identity；
- Single、Webtoon 和 Dual 使用统一 registry。

官方 Mihon 的 ViewPager/PageHolder 没有对应的数据模型。官方实现可能通过 View 层生命周期达到相似的视觉稳定性，但不能因此把 Desktop 的 identity 模型认定为上游 authority。

建议分类：

- 用户结果“不因加载或重试发生可见跳动”：可作为 parity/UX 目标；
- `DisplayUnitId`、registry 和原位容器机制：`DESKTOP_PRESENTATION_POLICY` 或 `PLATFORM_ADAPTER`。

### 4.5 Latest-settlement arbiter 与进度幂等契约

RC-05 引入了以下共享进度可靠性语义：

- settlement token；
- 仅最新 viewport settlement 可以提交；
- Mutex 串行化事务；
- identity-bearing idempotency key；
- session/reader 退出前 drain。

实现入口：

- [`ReaderViewportSettlementArbiter.kt`](../../app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewportSettlementArbiter.kt)
- [`ReaderProgressPolicy.kt`](../../domain/src/commonMain/kotlin/mihon/domain/reader/progress/ReaderProgressPolicy.kt)

官方 `ReaderViewModel.onPageSelected()` 直接异步写入 `last_page_read` 和章节完成状态，没有上述 arbiter、token、显式 drain 或相同幂等键契约。

当前 parity 记录只显式承认了 identity-bearing event key，但 verification scope 同时使用 latest ordering 和 drain 作为完成依据，分类不完整。

建议分类：`CROSS_PLATFORM_RELIABILITY_ENHANCEMENT`。

### 4.6 Encoded page store 生命周期与容量策略

RC-03/RD-01 新增了共享 encoded page store 契约：

- 逻辑字节预算；
- session lease 与 retained refs；
- `beginSession` / `endSession`；
- 原子写入和协调写入；
- LRU/驱逐索引；
- diagnostics 与 reconcile；
- Desktop 默认 512 MB encoded cache。

实现入口：

- [`EncodedPageStore.kt`](../../domain/src/commonMain/kotlin/mihon/domain/reader/storage/EncodedPageStore.kt)
- [`DesktopReaderEncodedPageStore.kt`](../../app-desktop/src/main/kotlin/mihon/desktop/reader/DesktopReaderEncodedPageStore.kt)

官方 Mihon 确实存在基于 `DiskLruCache` 的 `ChapterCache`，但其存在只能证明“官方有章节磁盘缓存”，不能证明本轮新增的共享 store 生命周期、逻辑容量模型和诊断契约属于上游行为。

重构前 Desktop 的 512 MB decoded cache 也不能作为新的 encoded disk store 的 provenance。

建议分类：

- store 生命周期与并发安全：`CROSS_PLATFORM_RELIABILITY_ENHANCEMENT`；
- 512 MB 默认值及 Desktop 驱逐策略：`DESKTOP_CACHE_POLICY`。

### 4.7 下一章整章预取

RD-02 新增：

- `OFF`；
- `FIRST_VIEWPORT`；
- `FULL_NEXT_CHAPTER`；
- Desktop 默认 `FULL_NEXT_CHAPTER`；
- 当前章节全部 Ready 后才开始整章预取；
- 预取章节被激活时提升到可见页优先级；
- 非协作任务和陈旧写入的有界处理。

配置入口：

- [`ReaderPreferences.kt`](../../app-desktop/src/main/kotlin/mihon/desktop/reader/ReaderPreferences.kt)

官方 Mihon 会准备相邻章节元数据，并对当前页之后有限数量的页面进行预加载，但没有“编码并缓存下一章全部页面”的模式。

该项已经在现有治理材料中分类为 `DESKTOP_PRODUCT_ENHANCEMENT`，结论正确。需要继续避免把官方 current+4 预加载作为整章预取的上游依据。

## 5. 新架构机制与新增产品能力的边界

以下实现也不是原版 Mihon 的具体机制，但不应直接报告为新增用户功能：

| 本地机制 | 对应的合理目标 | 正确治理方式 |
|---|---|---|
| P0–P4 scheduler taxonomy | 当前可见页优先、有限预加载 | 目标可对齐上游，精确优先级属于共享核心实现 |
| generation 失效与陈旧结果拒收 | 快速切页不显示过期结果 | 已分类为可靠性增强 |
| 同一 Reader Screen 激活相邻章节 | 无缝跨章 | 用户结果可对齐上游，Desktop 导航实现属于平台 adapter |
| 零页 session 后渐进 materialize | 避免等待整章加载才进入 reader | 共享 session 实现机制，不是官方 presentation authority |
| Retry 保持同一 display unit | 重试不跳页、不替换容器 | UX 目标可保留，identity 实现属于 Desktop presentation policy |

治理文档应明确区分三层：

1. **上游用户语义**：用户最终观察到的行为；
2. **跨平台共享契约**：为可靠性或可测试性新增的本地规则；
3. **平台实现策略**：Compose Desktop 的 identity、布局、滚动和缓存实现。

只有第一层可以直接作为上游 parity authority。第二、三层必须声明 deviation 或 adapter 边界。

## 6. 已存在但并非本轮首次引入的 fork 能力

以下能力不是原版 Mihon，但在 `0b74079e` 之前已经存在，不能误报为 Reader roadmap 首次新增：

- 相邻 portrait 页面组合；
- 自动边缘匹配；
- forced single/manual spread；
- landscape parity；
- Webtoon 自动滚动本身；
- 键盘、鼠标和右键阅读操作；
- grayscale/invert 等 Desktop 显示能力；
- 本地文件和压缩包阅读；
- Desktop 直接跨章及不显示额外 continue page 的行为。

本轮重构可能迁移、强化或改变了这些能力的实现，但其产品 provenance 仍然是 Desktop fork，而不是原版 Mihon。

## 7. 当前治理缺口

### 7.1 Manifest 分类不完整

现有 `parity-manifest.json` 已记录：

- adjacent portrait pairing；
- Desktop 下一章整章预取；
- generation hardening；
- identity-bearing progress event key；
- Desktop 键盘和指针操作。

但以下内容仍被绑定在 fixed-main verification scope 中，或没有单独 deviation：

- 固定物理双槽与 4:3 frame；
- Webtoon 相对锚点与交互暂停；
- 双页最大可见源页进度；
- `DisplayUnitId` 稳定容器契约；
- encoded store session/预算/diagnostics；
- latest settlement ordering 与 drain。

### 7.2 Authority 文档混合了目标和机制

`reader-shared-core.md` 与 `reader-authority.md` 当前把以下不同性质的内容放入同一完成证据：

- 原版可观察行为；
- Desktop 本地 presentation 设计；
- 共享核心可靠性增强；
- 具体缓存、身份和几何实现。

这会让本地设计获得不可更改的上游权威，并使针对 Desktop 窗口、页面比例和交互体验的独立验证被跳过。

## 8. 建议的纠正任务

建议新增一个独立 corrective task，而不是直接撤销整个 Reader 重构：

1. 将每条 reader capability 拆成“上游行为、共享可靠性增强、Desktop 产品增强、平台 adapter”四类；
2. 为固定槽 identity 与 frame 几何建立两个不同的 capability 条目；
3. 从 fixed-main authority 中移出本报告列出的本地机制；
4. 在 parity manifest 中补齐 deviation 类型和真实引入提交；
5. 对固定 4:3 frame 重新进行窗口比例与常见漫画页面比例的 UX 验收；
6. 保留稳定槽位、稳定 identity、锚点恢复等有价值能力，但改用 Desktop 体验和可靠性证据验收；
7. 增加治理检查：如果 capability 的 production evidence 只存在于 fixed-main 之后，且官方实现不存在对应语义，则必须声明 deviation，不能直接标记为 upstream parity。

### 8.1 建议重新打开的范围

| 阶段 | 是否需要重新打开实现 | 需要纠正的内容 |
|---|---|---|
| RP-01 | 通常不需要 | 稳定 display identity 的 provenance |
| RP-02 | 通常不需要 | Webtoon 锚点与自动滚动暂停分类 |
| RP-03 | 需要重新打开设计验收 | 固定槽、封面位置、4:3 与双页进度分类 |
| RC-03/RD-01 | 通常不需要 | encoded store 契约和容量策略分类 |
| RC-05 | 通常不需要 | latest settlement、幂等和 drain 分类 |
| RD-02 | 不需要 | 已正确标记为 Desktop 产品增强 |

“重新打开”首先表示重新打开 provenance、分类和验收权威，不代表预设必须删除 production 实现。只有固定 4:3 这类已经产生明显页面比例问题、又缺乏独立设计依据的策略，需要进一步决定是否修改实现。

## 9. 最终判断

Reader shared core 与 presentation 重构的总体架构成果仍然可以成立，但其完成证明必须从“所有行为均来自原版 Mihon”调整为：

- 原版 Mihon 用户语义得到迁移或保持；
- Desktop fork 原有能力得到保留；
- 新增可靠性契约有独立的工程依据；
- Desktop 产品和 presentation 策略有明确 provenance 与 UX 验收。

固定双槽和 4:3 不是孤立错误。它暴露了当前治理闭环中“用户结果、共享契约和平台实现机制没有分层”的系统性问题。本报告列出的其他能力应一并完成分类纠正，避免继续使用原版 Mihon parity 为本地设计背书。
