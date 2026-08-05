# Reader 非上游能力分类与双页视口纠正 Roadmap

- 制定日期：2026-08-05
- 状态：`IN_PROGRESS`（父路线当前唯一 `active-child-plan`；在独立 worktree 中串行执行，暂停中的非 Reader 工作树不参与本计划写入）
- 来源审查：[`2026-08-05-reader-refactor-non-upstream-feature-audit.md`](./2026-08-05-reader-refactor-non-upstream-feature-audit.md)
- 原 Reader 计划：[`2026-08-02-reader-core-migration-and-presentation-roadmap.md`](./2026-08-02-reader-core-migration-and-presentation-roadmap.md)
- 上级路线：[`2026-06-30-mihon-desktop-refactor-roadmap.md`](./2026-06-30-mihon-desktop-refactor-roadmap.md)
- 固定原版权威：`main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`
- 官方 Mihon 复核点：`21c63579678be4cfe19ba0982d8a158fe300f278`
- 审查使用的 Reader 完成基线：`95b82fc1039f772d4f8688855f2b06e16f983eb5`
- 机器状态权威：[`parity-manifest.json`](../../app-desktop/src/test/resources/parity/parity-manifest.json)
- 当前进度：从第 7 节第一个未勾选顶层任务，以及该任务中第一项非 `PASS` checkpoint 推导；不另设 `active-task`

本文是已完成 Reader roadmap 的纠正子计划，不撤销已经完成的共享 session、materialize、scheduler、章节窗口、进度事务或 Desktop production cutover。本文只重新打开两类问题：

1. 固定 4:3 双页 frame 已产生可复现的页面比例缺陷，必须修改 production 实现；
2. 审查报告列出的本地产品语义、可靠性增强和平台策略必须从 fixed-main authority 中分离，并以各自证据重新验收。

父路线目前仍把非 Reader 计划作为唯一 `active-child-plan`。开始执行本文前，协调者必须显式切换父路线活动指针，或明确把本文作为不与当前工作流并行的纠正批次；不得让两个计划同时写 `parity-manifest.json`、Reader authority 测试或父路线状态。

## 1. 固定结论与产品决策

### 1.1 唯一需要改变的现有产品行为

固定 4:3 frame 不保留，也不再进行“是否值得保留”的开放式讨论。原因已经由真实截图和当前 production 公式共同证明：

- 实际阅读视口为 `3840 × 2054`，约 `1.8695:1`；
- 当前 frame 宽度由 `2054 × 4/3 ≈ 2739` 决定；
- 截图中的漫画显示区域实测为 `2740 × 1949`，漫画宽高比约 `1.4058:1`；
- 漫画比 4:3 更宽，`FIT_SCREEN` 填满 frame 宽度后必然剩余约 `105 px` 高度，即上下各约 `52–53 px` 黑边；
- 左右黑边则来自把约 `1.87:1` 的真实视口先收窄为 `1.333:1`。

目标行为固定为：

1. `DualPageDisplayUnitFrame` 使用完整可用 reader viewport，不设置固定宽高比或图片元数据驱动的动态 frame；
2. 默认继续使用 `FIT_SCREEN`，保持全部漫画像素可见，不自动裁切或拉伸；
3. 两个物理槽仍各占 viewport 的一半，并向书脊侧对齐；稳定槽位不依赖 frame 为 4:3；
4. 在真实 4:3 窗口中，如果漫画本身比 4:3 更宽，允许 `FIT_SCREEN` 出现不可避免的上下留白；“无黑边”只能由用户显式选择会裁切的 `FIT_HEIGHT`，不得偷偷改变默认值；
5. 不以修改 reader 背景色、裁掉漫画白边或增加 stretch 模式掩盖几何问题；
6. 不新增 4:3 compatibility flag，也不以加载完成后的图片宽高比重新排版 frame，避免 Loading → Ready 时布局跳变。

### 1.2 其他能力的保留决策

下表冻结本计划的取舍，执行者不得在任务中重新打开产品范围：

| 能力 | 决策 | 收益 | 代价与约束 | 正确分类 |
| --- | --- | --- | --- | --- |
| 原版单张宽图拆分 | 保留 | 保持 Mihon 宽页阅读语义 | 必须与相邻源页配对分开记录 | `FIXED_ORIGINAL` 行为向量 |
| 相邻 portrait pairing | 保留 | Desktop/当前 Fork 可按书本方式同时阅读两页 | 两页同时可见会影响进度语义 | `CROSS_PLATFORM_PRODUCT_ENHANCEMENT` |
| 稳定物理左右槽 | 保留 | LTR/RTL、Loading/Ready/Error 和 Retry 不交换页面位置 | 只约束 identity/坐标，不约束 frame 比例 | `DESKTOP_PRESENTATION_POLICY` |
| 封面固定物理左槽、右槽留空 | 保留 | 形成稳定书本开页位置 | 属于 Desktop 产品选择，不得冒充上游行为 | `DESKTOP_PRODUCT_ENHANCEMENT` |
| 固定 4:3 frame | 删除 | 消除宽屏视口中的额外上下黑边和不必要缩小 | 真实 4:3 视口中的比例留白仍可能存在 | `DESKTOP_PRESENTATION_POLICY`，历史项关闭为 `REMOVED` |
| 稳定 `DisplayUnitId` 与原位状态切换 | 保留 | 加载、失败和 Retry 不跳页、不丢 zoom/scroll 容器 | identity 模型是 Compose Desktop 机制 | `DESKTOP_PRESENTATION_POLICY` |
| Webtoon 相对几何锚点及 split/merge 回退 | 保留 | 晚到图片改变高度时不丢失阅读位置 | 需要真实 Lazy 布局测试，不能只测纯换算函数 | `DESKTOP_PRESENTATION_POLICY` |
| Webtoon 拖拽/fling 期间暂停自动滚动 | 保留 | 避免自动滚动与用户手势争抢 | 自动滚动本身及暂停状态机均非上游行为 | `DESKTOP_PRODUCT_ENHANCEMENT` |
| 双页按最大可见源页提交进度 | 保留 | 两页确实同时显示时，末页 pair 能正确完成章节 | 必须保证宽图两半仍只算同一个 source page | `CROSS_PLATFORM_PRODUCT_ENHANCEMENT` |
| latest-settlement-wins、串行事务 | 保留 | 防止旧 viewport 反向写入进度或激活章节 | 增加并发状态，但已有真实竞态价值 | `CROSS_PLATFORM_RELIABILITY_ENHANCEMENT` |
| identity 幂等键与退出前 drain | 保留 | Retry/重复回调不重复写，关闭 reader 不丢最后一次进度 | 必须覆盖失败、取消和 dispose 边界 | `CROSS_PLATFORM_RELIABILITY_ENHANCEMENT` |
| encoded store session、预算、原子写入、诊断 | 保留 | 两端有界缓存、跨 session 一致和可诊断失败 | 不是原版 `ChapterCache` 的直接语义 | `CROSS_PLATFORM_RELIABILITY_ENHANCEMENT` |
| Desktop encoded cache 默认 512 MB | 保留 | 为完整预取提供有界磁盘容量 | 是 Desktop 默认策略，不是共享或原版默认值 | `DESKTOP_CACHE_POLICY` |
| Desktop 完整下一章预取 | 保留且继续可配置 | 降低跨章等待；`OFF` 可恢复原版流量边界 | 额外网络/磁盘成本，必须保持 P0 抢占和无进度副作用 | `DESKTOP_PRODUCT_ENHANCEMENT` |

## 2. 范围、非目标与权威边界

### 2.1 本计划允许修改的范围

- capability `43`：presentation transforms、稳定 identity、Webtoon 与 Dual 的本地策略；
- capability `45`：fixed-original queue、shared scheduler/encoded store、Desktop P4 和 cache policy 的分类；
- capability `53`：原版末页完成语义、双页最大可见页产品语义、settlement/idempotency/drain 可靠性契约；
- [`fixed-main-reader-fixtures.json`](../../app-desktop/src/test/resources/parity/fixed-main-reader-fixtures.json)、`parity-manifest.json`、Reader authority/parity 测试和两份 Reader architecture 文档；
- `DualPageDisplayUnitFrame` 及其真实 Compose/production 行为测试；
- 为证明保留项而补充的 focused production wiring、离屏布局和事务测试。

capability `9`、`44`、`47`、`49`、`51`、`54` 只做不变性复核。没有新的相反证据时，不改实现、不改状态，也不把本计划扩张为第二次完整 Reader 迁移。

### 2.2 明确非目标

- 不重写 `ReaderSessionCore`、materialize executor、scheduler、章节窗口或数据库进度模型；
- 不删除相邻页配对、edge matching、manual spread、封面槽、自动滚动、下一章预取或 Desktop 键鼠功能；
- 不让 Android UI 采用 Desktop Compose 布局，也不在 shared core 中加入窗口尺寸、左右槽或 4:3 概念；
- 不改变数据库、备份、下载目录或用户原图；encoded cache 仍是可丢弃派生数据；
- 不创建新的全量 inventory、语义映射器、自动分类器、词语匹配器或第二份机器状态文件；
- 不把截图临时文件提交为测试资产。截图只提供已固化为数值的回归向量；测试使用程序生成的确定性 bitmap；
- 不为消除黑边默认裁切、拉伸或根据图片比例动态改变 display identity；
- 不顺手处理审查报告没有列出的 Reader UX、设置或性能想法。新发现只登记 follow-up，不静默加入当前任务。

### 2.3 四层权威模型

| 层 | 可以证明什么 | 机器分类 | 不能证明什么 |
| --- | --- | --- | --- |
| 原版可观察行为 | 页状态、原版 +4、章节窗口、过渡、最后逻辑页完成、单张宽图拆分 | fixture `behaviorVectors[].classification = FIXED_ORIGINAL` | 不能为 Fork 后加入的容器、缓存或双页策略背书 |
| 跨平台可靠性增强 | generation、迟到拒收、settlement ordering、幂等、drain、encoded store 生命周期 | `CROSS_PLATFORM_RELIABILITY_ENHANCEMENT` | 不能改写为原版产品功能 |
| Fork/Desktop 产品增强 | adjacent pairing、双页进度、封面槽、自动滚动暂停、完整下一章预取 | `CROSS_PLATFORM_PRODUCT_ENHANCEMENT` 或 `DESKTOP_PRODUCT_ENHANCEMENT` | 不能只凭“用户体验更好”省略入口、反馈或行为测试 |
| 平台实现与呈现策略 | `DisplayUnitId`、物理槽、Webtoon anchor、Compose renderer、512 MB cache 默认值 | `PLATFORM_ADAPTER`、`DESKTOP_PRESENTATION_POLICY` 或 `DESKTOP_CACHE_POLICY` | 不能成为 fixed-main parity 完成条件 |

`MIGRATION_OUTPUT` 可以继续描述“共享提取/生产切换已经发生”，但不得作为本报告所列机制的唯一分类。每项本地语义必须另有稳定 deviation ID。

### 2.4 本次固定的 deviation 清单

RNC-01 只人工登记下列已知条目，不搜索或生成更多条目：

| Deviation ID | Owner capability | 分类 | 真实引入提交 | 处置 |
| --- | --- | --- | --- | --- |
| `DISPLAY_UNIT_STABLE_IDENTITY` | `43` | `DESKTOP_PRESENTATION_POLICY` | `464cbefafc2153de286963224694eafceda5e0d1` | 保留 |
| `WEBTOON_RELATIVE_ANCHOR_RECOVERY` | `43` | `DESKTOP_PRESENTATION_POLICY` | `71aa3c458ff5b9a24b04a14820d6d2bb636c3e04` | 保留 |
| `WEBTOON_AUTOSCROLL_INTERACTION_PAUSE` | `43` | `DESKTOP_PRODUCT_ENHANCEMENT` | `71aa3c458ff5b9a24b04a14820d6d2bb636c3e04` | 保留 |
| `DUAL_PHYSICAL_SLOT_IDENTITY` | `43` | `DESKTOP_PRESENTATION_POLICY` | `c01771573034d2ed3492db3ddd109533bb631d99` | 保留 |
| `DUAL_COVER_LEFT_SLOT` | `43` | `DESKTOP_PRODUCT_ENHANCEMENT` | `c01771573034d2ed3492db3ddd109533bb631d99` | 保留 |
| `DUAL_FIXED_4_3_FRAME` | `43` | `DESKTOP_PRESENTATION_POLICY` | `c01771573034d2ed3492db3ddd109533bb631d99` | RNC-01 登记 `OPEN`；RNC-02 删除后记为 `REMOVED` |
| `DUAL_MAX_VISIBLE_PAGE_PROGRESS` | `53` | `CROSS_PLATFORM_PRODUCT_ENHANCEMENT` | `6330c6198e827622a74dc48b1ac50f15e7470380`、`c01771573034d2ed3492db3ddd109533bb631d99` | 保留 |
| `LATEST_SETTLEMENT_ORDERING` | `53` | `CROSS_PLATFORM_RELIABILITY_ENHANCEMENT` | `6330c6198e827622a74dc48b1ac50f15e7470380` | 保留 |
| `PROGRESS_IDEMPOTENCY_AND_DRAIN` | `53` | `CROSS_PLATFORM_RELIABILITY_ENHANCEMENT` | `6330c6198e827622a74dc48b1ac50f15e7470380`、`dcb2dfb24cb4181d3703a5bf02c5b9e535adb0f8` | 保留 |
| `ENCODED_STORE_LIFECYCLE` | `45` | `CROSS_PLATFORM_RELIABILITY_ENHANCEMENT` | `06a1138d2deefb67a565c1c33450d69e734029f7`、`dcb2dfb24cb4181d3703a5bf02c5b9e535adb0f8` | 保留 |
| `DESKTOP_ENCODED_CACHE_POLICY` | `45` | `DESKTOP_CACHE_POLICY` | `dcb2dfb24cb4181d3703a5bf02c5b9e535adb0f8` | 保留 512 MB 默认值 |
| `DESKTOP_FULL_NEXT_CHAPTER_PREFETCH` | `45` | `DESKTOP_PRODUCT_ENHANCEMENT` | `6ee9473cde839fe5ee932bdb5263c78567c2ff12` | 保留现有正确分类 |

以下历史条目继续保留，不由本文改写其事实：`GENERATION_HARDENING`、`ADJACENT_PORTRAIT_PAIRING`、`HTTP_RETRY_FORCE_DRIFT`、`DUAL_PAGE_PROGRESS_FIRST_ONLY`。其中最后两项是历史 gap；关闭状态不能反向抹掉当时的 drift，也不能替代上表对当前本地语义的分类。

### 2.5 证据字段与禁止证据

上述 12 个条目在 Reader fixture 与 manifest 中必须分别记录：

- `id`：上表中的稳定 ID；
- `classification`：上表固定分类；
- `introductionRefs`：一个或多个完整 40 位 Git commit；
- `behaviorEvidenceRefs`：至少一个会执行真实 production 行为或 wiring 的测试 `path#method`；
- `description`：说明用户结果、可靠性目标或平台边界，不只复述类名；
- `resolutionStatus`：`ACTIVE`、`OPEN` 或 `REMOVED`；只有历史缺口可使用 `CLOSED`；
- `closureTask` 与 `resolutionEvidence`：仅在 `REMOVED/CLOSED` 时必填。

字段只对本文列出的 Reader deviations 收敛，不在本计划中迁移全部 64 个 capability 的 JSON schema。

以下证据一律不能关闭任务：

1. 纯文案、注释、类名、常量或 symbol 存在；
2. 仅扫描源码字符串确认 `4f / 3f` 消失；
3. 测试中复制 production 的比例计算、锚点算法或 settlement 判定，再让两份复制逻辑互相证明；
4. 用 fixed-main 路径或原版 symbol 为 fixed-main 之后才出现的本地行为提供 `behaviorEvidenceRefs`；
5. 仅以“官方没有同名类”证明官方没有对应语义；
6. 使用当前 `HEAD` 代替真实引入提交；
7. 用 domain 单元测试代替 Compose mounted、Android production、Desktop session 或数据库真实消费者；
8. 让生成器、关键词、路径相似度或 capability ID 自动选择 classification、owner 或 source 关系。

## 3. 状态、checkoff 与执行纪律

### 3.1 状态枚举

| 状态 | 含义 |
| --- | --- |
| `TODO` | 尚未开始或本文尚未激活 |
| `NEXT` | 前置条件满足，可作为下一批次 |
| `DOING` | 正在进行 RED/GREEN/重构或证据迁移 |
| `BLOCKED` | 存在已记录且无法在本任务内解决的真实阻塞 |
| `REVIEW` | 实现完成，等待一轮独立审查、验证或提交 |
| `DONE` | 状态卡全部关闭且已有提交 |

- `[ ]` 在 `TODO/NEXT/DOING/BLOCKED/REVIEW` 时保持未勾选；`[x]` 只表示实现、审查、验证、证据和提交全部完成。
- 一个顶层任务原则上一个原子提交，包含 RED、production、重构、fixture/manifest、必要文档和 checkoff；审查修复最多增加一个提交。
- checkpoint 是任务内 stop-gate，不单独提交。前一 checkpoint 未 `PASS` 时不得扩大到后一 checkpoint。
- 每个顶层任务只进行一轮独立审查；审查修复后最多一轮复审。不得因为文档数量多而启动额外审查流程。
- 所有重型 Gradle 由同一协调者串行运行；focused 测试只覆盖当前任务，完整 Desktop/Android/发布验证只在 RNC-07 运行一次。
- 制定本文时存在的未提交工作树改动属于其他批次，不得纳入本计划提交、当作实现证据或被回滚。

### 3.2 每个任务的完整状态卡

每个详细任务都预留以下字段：

> 状态卡：`状态` · 权威/范围 · RED/基线 · Shared · Android · Desktop/UI · Legacy · Review · Verify · Evidence · Commit
>
> 记录：阻塞原因 · 审查结论 · 验证命令/结果 · 运行产物 · Commit hash

字段为 `[x]` 或明确的 `N/A：理由` 才能关闭；“已有测试大概覆盖”“只改文档”“代码看起来正确”均不能替代记录。

### 3.3 强制停止条件

执行过程中遇到以下情况必须停止当前扩张，先记录并回到本文边界：

1. RNC-02 需要改变 pairing、`DisplayUnitId`、进度或缓存才能移除 4:3；这说明实现路径错误，应只重构 frame modifier；
2. 为确定 classification 准备创建脚本、词典、override、自动 anchor 或自动 source graph；本文固定清单只有 12 项，必须人工登记；
3. 破坏 production wiring 后测试仍绿；先修复测试，不得继续补 manifest；
4. 某保留项确有新产品缺陷；登记独立 follow-up，不在分类任务中顺手重写行为；
5. 官方 Mihon 在执行前出现等价新语义；先记录新的 upstream ref 和实际调用链，再决定是否调整分类；
6. 其他工作流正在修改同一 manifest、authority 测试或 Reader 文件；串行等待或切换独立 worktree，不合并混杂 diff。

## 4. 双页视口验收规格

### 4.1 默认 `FIT_SCREEN` 几何矩阵

以下是 RNC-02 的固定行为向量，容许最多 `2 px` 的取整误差：

| Reader viewport | 漫画/双页组合比例 | 预期 |
| --- | --- | --- |
| `3840 × 2054` | `1406 × 1000`（约 `1.406:1`） | 漫画填满可用高度；上下黑边为 0；左右自然留白约 `476 px/侧` |
| `1920 × 1080` | `1406 × 1000` | 漫画填满高度；上下黑边为 0；两页在书脊相接，空白位于外侧 |
| `2520 × 1080` | `1406 × 1000` | 漫画填满高度；超宽屏只增加左右自然留白 |
| `1600 × 1200`（真实 4:3） | `1406 × 1000` | 完整图片宽度可见；允许上下各约 `31 px` 留白；不得自动裁切 |
| `1600 × 1200` | `1333 × 1000`（约 4:3） | 图片在取整误差内填满 viewport |

“上下黑边为 0”指 reader 内容 viewport，不包括操作系统窗口装饰、悬浮工具栏或漫画源文件自身的白/黑边。

### 4.2 双槽与图片规则

- 两张相邻 portrait 页：各自位于稳定半屏槽，左页向右对齐、右页向左对齐，书脊无人为 gap；
- 同一张宽图拆分：两个 slice 使用同一个 source page identity，拼接后不丢中心像素，不重复计算阅读进度；
- 封面：物理左槽保留封面，物理右槽为空；LTR/RTL 只改变阅读顺序，不被环境 `LayoutDirection` 偷换物理坐标；
- forced single：继续使用稳定双槽策略，但不重新引入固定 frame 比例；
- Loading/Ready/Error/Retry 和窗口 resize：不得改变 `DisplayUnitId`、`DisplaySlotId` 或已挂载 zoom state；
- `FIT_HEIGHT`、`FIT_WIDTH`、`ORIGINAL_SIZE`、`SMART_FIT` 的现有用户选择继续生效，但不参与 frame 几何决策。

## 5. 任务清单与依赖顺序

- [x] `RNC-01` 建立 Reader 本地 deviation 负例门禁并登记固定清单
- [ ] `RNC-02` 删除固定 4:3 frame，改用完整可用 viewport
- [ ] `RNC-03` 重新验收稳定 display identity、物理双槽与封面策略
- [ ] `RNC-04` 重新验收 Webtoon 锚点恢复与自动滚动暂停
- [ ] `RNC-05` 分离双页进度产品语义与 settlement/idempotency/drain 可靠性
- [ ] `RNC-06` 分离 encoded store 生命周期、Desktop cache policy 与完整下一章预取
- [ ] `RNC-07` 收口 authority/manifest 治理并完成跨平台验证

固定执行顺序为：

```text
RNC-01 → RNC-02 → RNC-03 → RNC-04 → RNC-05 → RNC-06 → RNC-07
```

虽然 RNC-03～RNC-06 的产品代码上下文部分独立，但都会修改 Reader fixture、manifest 和 authority 测试，为避免同文件冲突必须串行执行。

## 6. 任务内 checkpoint 总表

| Checkpoint | 初始状态 | 固定交付物 | 通过门禁 | Timebox |
| --- | --- | --- | --- | --- |
| `RNC-01.A` 基线与 owner 冻结 | `PENDING` | 只确认 capability 43/45/53 和上表 12 项 | owner、分类、引入提交与当前 production path 人工核对完成 | 0.5 日 |
| `RNC-01.B` 独立负例 | `PENDING` | 分类、provenance、行为证据的破坏性 mutation | 每个错误按预期 RED，测试不读取词义或复制业务逻辑 | 0.5–1 日 |
| `RNC-01.C` fixture/manifest 登记 | `PENDING` | 12 项写入唯一机器权威，4:3 状态为 `OPEN` | exact ID/owner/classification 通过，其他 capability 未变化 | 0.5–1 日 |
| `RNC-01.D` 审查与提交 | `PENDING` | focused authority 验证和原子提交 | 无生成器、无自动语义、无 production diff | 0.5 日 |
| `RNC-02.A` 几何 RED | `PENDING` | 第 4 节 viewport 与 bitmap mounted tests | 当前 4:3 production 因正确原因失败 | 0.5–1 日 |
| `RNC-02.B` 最小 GREEN | `PENDING` | frame 使用完整 viewport，删除固定比例和无依据 frame inset | 宽屏上下黑边消失，默认无裁切/拉伸 | 0.5 日 |
| `RNC-02.C` 双槽回归 | `PENDING` | cover、pair、wide split、LTR/RTL、resize、identity | 既有保留行为全部通过 | 0.5–1 日 |
| `RNC-02.D` deviation 关闭 | `PENDING` | `DUAL_FIXED_4_3_FRAME` 记为 `REMOVED` | closure evidence 指向 production mounted test | 0.5 日 |
| `RNC-03.A` identity 破坏验证 | `PENDING` | Loading/Ready/Error/Retry/resize mutations | 任一 identity 替换都会失败 | 0.5 日 |
| `RNC-03.B` 双槽/封面破坏验证 | `PENDING` | 物理槽、环境 RTL、空槽和书脊对齐 mutations | 槽位与 frame 几何完全解耦 | 0.5–1 日 |
| `RNC-03.C` 分类与生产证据 | `PENDING` | 相关 deviation 的 behavior evidence | 真实 mounted renderer 被断开时测试失败 | 0.5 日 |
| `RNC-04.A` Webtoon anchor | `PENDING` | Ready 高度变化、split→merge、merge→split、NO_POSITION | 相对阅读位置有界恢复，不使用固定像素假绿 | 0.5–1 日 |
| `RNC-04.B` 手势暂停 | `PENDING` | drag→fling→settled 状态序列 | fling 未结束时 auto-scroll 不恢复，settled 后恢复 | 0.5 日 |
| `RNC-04.C` 分类与接线 | `PENDING` | 两项分别登记 presentation/product 证据 | production `LazyListState`/auto-scroll loop 被断开时失败 | 0.5 日 |
| `RNC-05.A` 双页进度 | `PENDING` | pair/wide split/final page 的 producer→policy 测试 | 两源页取最大值；同源两 slice 只算一次 | 0.5–1 日 |
| `RNC-05.B` latest settlement | `PENDING` | 旧/新 viewport 与串行事务竞态 | 旧 settlement 永不反向激活或落库 | 0.5–1 日 |
| `RNC-05.C` 幂等与 drain | `PENDING` | replay、失败、dispose、最后写入 | 无重复副作用且关闭 reader 不丢已接收写入 | 0.5–1 日 |
| `RNC-05.D` 分类与双端证据 | `PENDING` | product/reliability deviation 分开 | Android/Desktop production consumer 都有行为证据 | 0.5 日 |
| `RNC-06.A` store 生命周期 | `PENDING` | session、retained ref、原子写、LRU/reconcile、diagnostics | shared contract 与两端 adapter 真实 I/O 通过 | 1 日 |
| `RNC-06.B` Desktop cache policy | `PENDING` | 512 MB 默认值与 production DI 绑定 | 明确只属于 Desktop encoded cache，不借 decoded cache provenance | 0.5 日 |
| `RNC-06.C` 下一章预取边界 | `PENDING` | OFF/FIRST/FULL、P0 抢占、配额、取消、无进度 | 不使用原版 current+4 或末五页 page-list 冒充整章预取 | 0.5–1 日 |
| `RNC-06.D` 分类与提交 | `PENDING` | 三类机制分别登记 | exact classification 与 production tests 通过 | 0.5 日 |
| `RNC-07.A` authority 文档 | `PENDING` | 目标、共享可靠性、平台策略三层分开 | 不再把本地机制写成 fixed-main 完成条件 | 0.5–1 日 |
| `RNC-07.B` 最终治理守卫 | `PENDING` | exact deviation set、owner、provenance、evidence mutations | 误标 fixed-original、漏项、跨 capability 串线均会失败 | 0.5–1 日 |
| `RNC-07.C` 全量与运行验收 | `PENDING` | 一次全量、Test Mode、Windows/macOS 实际 viewport 验收 | 第 10 节矩阵全部完成 | 1–2 日 |
| `RNC-07.D` 最终审查与关闭 | `PENDING` | 审查、证据表、提交与 roadmap 状态 | 无 P0/P1/P2，所有顶层 checkbox 有 commit | 0.5–1 日 |

## 7. 详细任务设计

### `RNC-01` Reader 本地 deviation 负例门禁与固定清单

> 状态卡：`DONE` · 权威/范围 `[x]` · RED/基线 `[x]` · Shared `N/A：不改变共享 production` · Android `N/A：只读取现有消费者` · Desktop/UI `N/A：不改变呈现` · Legacy `N/A：不替换实现` · Review `[x]` · Verify `[x]` · Evidence `[x]` · Commit `[x]`
>
> 记录：阻塞 `无；使用独立 worktree 隔离暂停中的 NR0-01 manifest 改动` · 审查 `PASS：12 项人工固定清单、4 个历史条目、owner/classification/provenance/evidence 均一致；无 production diff、无自动语义逻辑` · 验证 `ReaderDeviationGovernanceTest + ReaderFixedMainAuthorityTest + DesktopProductCapabilityContractTest：47 tests / 0 failures；JSON parse PASS；git diff --check PASS` · 产物 `Reader deviation 显式字段门禁、fixture/manifest capability 43/45/53 登记` · Commit `本批次提交（见 Git 历史）`

- 依赖：父路线活动指针/执行权明确；与正在修改 `parity-manifest.json` 的工作流串行。
- 固定范围：只登记第 2.4 节 12 项；保留现有四个历史/已正确分类条目，不生成新 inventory。
- RED：在独立 fixture mutation 中分别把 `DUAL_FIXED_4_3_FRAME` 改为 `FIXED_ORIGINAL`、删除 `introductionRefs`、把 introduction 改成当前 `HEAD`、只给源码路径不提供 production test、把 owner 从 `43` 改成 `45`、让两个条目复用同一 ID；每项必须按对应原因失败。
- GREEN：为 Reader deviation 增加稳定 ID、`introductionRefs`、`behaviorEvidenceRefs` 和 resolution 字段；只扩展允许分类为 `DESKTOP_PRESENTATION_POLICY`、`DESKTOP_CACHE_POLICY`，不创建全局 schema 迁移器。
- GREEN：把 12 项人工写入 `fixed-main-reader-fixtures.json` 与 manifest 的 `43/45/53`；`DUAL_FIXED_4_3_FRAME` 初始状态必须为 `OPEN`，不得在 production 尚未改变时先写 `REMOVED`。
- 重构：若现有 `ReaderFixedMainAuthorityTest` helper 不能表达字段，做最小结构抽取；helper 只能验证明确字段，不允许根据描述文本推断分类。
- Focused 验证：`ReaderFixedMainAuthorityTest`、`DesktopProductCapabilityContractTest` 的 Reader/deviation 部分和 JSON parse。
- 关闭条件：12 项 owner/classification/provenance 精确一致；错误分类、缺失 provenance、非行为证据和跨 capability 串线均有负例；无 production 文件 diff。
- 预计：1–2 工程日，约 4–6 个 fixture/test 文件；超过范围说明测试结构仍耦合，不得借机重写整个 parity 系统。

### `RNC-02` 删除固定 4:3 frame

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `N/A：frame 只属于 Desktop presentation` · Android `N/A：原版 Android 已使用完整 viewport` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`RNC-01`。
- RED：使用真实 `DualPageDisplayUnitFrame` 和程序生成的白色 `1406 × 1000` bitmap，在黑色 `3840 × 2054`、`1920 × 1080` 和 `1600 × 1200` scene 中执行第 4.1 节矩阵；当前 4:3 frame 必须在宽屏用例因上下黑边而失败。
- RED：现有“frame 左右小于 viewport”断言必须先改为完整 viewport 契约；恢复 `minOf(maxWidth, maxHeight * 4f / 3f)` 后测试必须失败。源码零命中只能作为补充守卫。
- GREEN：`DualPageDisplayUnitFrame` 的语义容器使用 reader content viewport 的完整宽高；删除 `DUAL_PAGE_FRAME_ASPECT_RATIO`，并删除只为固定 frame 服务、没有独立 UX 依据的水平 inset。
- GREEN：继续使用每侧半屏槽和当前书脊对齐；不读取图片尺寸、不改变 `ContentScale.Fit`、不增加 crop/stretch、不改变 zoom/gesture/navigation。
- 重构：frame 只负责挂载稳定槽和状态层；图片缩放继续由 `ZoomablePageBox` 的 `ScaleType` 负责。禁止新增第二个 geometry policy/helper 来复刻同一决策。
- 回归：封面空槽、相邻 portrait、同源宽图双 slice、forced single、LTR/RTL、环境 `LayoutDirection`、Loading/Ready/Error/Retry、窗口 resize。
- 证据：将 `DUAL_FIXED_4_3_FRAME.resolutionStatus` 从 `OPEN` 更新为 `REMOVED`，记录 `closureTask = RNC-02` 和 mounted/pixel behavior test；不删除历史引入提交。
- Focused 验证：`DualPagePresentationIdentityTest`、新/扩展的 viewport geometry test、`DualPagedPresentationTest`、相关 `ZoomablePageBox` tests、`git diff --check`。
- 关闭条件：宽屏默认 `FIT_SCREEN` 无额外上下黑边；真实 4:3 的不可避免留白符合矩阵；所有稳定槽和 identity 行为保持。
- 预计：1–2 工程日，约 3–6 个 production/test/fixture 文件。若需要修改 shared core、progress 或 pairing，立即停止并重选实现路径。

### `RNC-03` 稳定 display identity、物理双槽与封面策略

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `N/A：只验证 presentation identity` · Android `N/A：不要求 Android 采用 Compose identity` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`RNC-02`；必须以 full-viewport frame 为几何前提。
- RED：分别破坏 `DisplayUnitId`、`DisplaySlotId`、composition identity 和 zoom container，使 Loading→Ready、Ready→Error、Retry、late content 或窗口 resize 任一场景重新挂载；现有/新增 mounted tests 必须捕获。
- RED：让环境 RTL 交换物理槽、让封面占据 full span/右槽、让空槽卸载、或把稳定槽断言重新绑定到固定 frame 宽度；对应测试必须失败。
- GREEN：原则上复用现有 production；只有测试证明真实缺口时才做最小修复。固定物理槽、封面左槽、相邻页 pairing、manual spread、edge matching 和 landscape parity 继续保留。
- 证据：为 `DISPLAY_UNIT_STABLE_IDENTITY`、`DUAL_PHYSICAL_SLOT_IDENTITY`、`DUAL_COVER_LEFT_SLOT` 分别绑定真实 mounted behavior test；不得用同一个“文件存在”测试代替三项语义。
- Legacy：删除或改写仍把“固定 4:3/左右留边”当作 identity 完成条件的旧断言和 authority 描述；不删除 identity 模型本身。
- Focused 验证：Single/Dual presentation identity、Dual pairing、Desktop reader mounted product tests；断开 renderer 的实际语义 key 后测试必须失败。
- 关闭条件：identity 与几何尺寸彻底解耦；窗口 resize 只改变布局，不改变逻辑/显示身份；三项 deviation 有各自 provenance 和行为证据。
- 预计：1–2 工程日，约 4–7 个测试/fixture 文件；如 production 已满足，允许没有 production diff，但仍需有效破坏性证据和分类提交。

### `RNC-04` Webtoon 锚点恢复与自动滚动暂停

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `N/A：Webtoon 几何属于 Desktop presentation` · Android `N/A：不移植 Compose 锚点状态机` · Desktop/UI `[ ]` · Legacy `N/A：保留现有能力` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`RNC-03`；继续使用稳定 `DisplayUnitId`。
- RED：在真实 mounted `LazyListState` 中覆盖 Ready 改变 item 高度、相对 offset 恢复、split→merge、merge→split、目标 identity 缺失和 `NO_POSITION`；删除相对比例或 fallback 后必须失败。
- RED：以完整状态序列验证 `idle → drag → fling → settled`；drag 结束但 fling 仍进行时恢复 auto-scroll 必须失败，settled 后不恢复也必须失败。
- GREEN：原则上保留当前 production。若测试暴露缺口，只修复 anchor/pause 边界，不改变自动滚动速度、入口、Webtoon padding 或核心页状态。
- 证据：`WEBTOON_RELATIVE_ANCHOR_RECOVERY` 绑定 mounted geometry test；`WEBTOON_AUTOSCROLL_INTERACTION_PAUSE` 同时绑定 pause state test 与实际 auto-scroll loop wiring，不能只测状态类。
- Authority：原版 `WebtoonLayoutManager` 只支持原版 active-page/position 语义，不再作为相对锚点或自动滚动暂停的来源。
- Focused 验证：`WebtoonPresentationIdentityTest`、`WebtoonAutoScrollTest`、Webtoon production selector/mounted tests。
- 关闭条件：两项分别以 presentation policy 和 Desktop product enhancement 完成分类；断开真实 `LazyListState` 或 auto-scroll consumer 时测试失败。
- 预计：1–2 工程日，约 3–6 个测试/fixture/文档文件。

### `RNC-05` 双页进度与进度可靠性分层

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`RNC-03`、`RNC-04`；presentation producer 身份和可见集合已稳定。
- RED（产品语义）：两张相邻 source pages 组成一个 settled pair 时上报两个 `ReaderPageId` 并取最大 source index；同一宽源图的两个 slice 去重为一个 source page；末页确实可见时才完成章节。
- RED（wiring）：从 `DualPagedPresentation.resolveDualVisiblePages` 经 Desktop session/shared policy 到真实 progress port；断开任一生产连接必须失败，不能只直接调用 `ReaderProgressPolicy`。
- RED（可靠性）：较旧 settlement 在新 viewport 之后完成、两次并发事务、相同幂等事件 replay、storage/tracker 失败、reader dispose 紧邻最后一次 settlement；覆盖 latest-only、串行、无重复副作用和 drain。
- GREEN：原则上不改变产品行为；只在破坏测试证明当前 production 缺口时最小修复。不得改变数据库 schema、history 计时、duplicate preference 或 reader entry resolver。
- 分类：`DUAL_MAX_VISIBLE_PAGE_PROGRESS` 单独归为产品增强；`LATEST_SETTLEMENT_ORDERING` 与 `PROGRESS_IDEMPOTENCY_AND_DRAIN` 分别归为可靠性增强。原版“最后逻辑页才完成”继续是独立 `FIXED_ORIGINAL` 行为向量。
- Legacy：`DUAL_PAGE_PROGRESS_FIRST_ONLY` 继续记录历史 gap 与关闭事实；不得把它改写成“原版要求双页最大值”。
- Focused 验证：domain progress policy、Android settlement race/arbiter/production wiring、Desktop session/progress tracker integration、SQLDelight progress repository。
- 关闭条件：产品选择与可靠性机制有不同 deviation/evidence；两端 production 都执行共享进度契约；旧 settlement 和重复事件不会落库。
- 预计：2–3 工程日，约 5–8 个 test/fixture/doc 文件；若发现真实产品 bug，保持本任务内聚，但不得顺手重构其他 reader state。

### `RNC-06` Encoded store、Desktop cache policy 与完整下一章预取分层

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`RNC-05`；预取无进度副作用的契约已经独立。
- RED（共享可靠性）：session open/close、retained refs、字节预算、两阶段/原子写入、删除失败、reopen、实体丢失、物理/逻辑 LRU reconcile、diagnostics；shared contract 以及 Android/Desktop 真实文件 adapter 都必须执行。
- RED（Desktop policy）：production DI 创建的 `DesktopReaderEncodedPageStore` 使用 512 MB encoded budget；误绑到 decoded bitmap cache、更新下载器 cache 或测试 fake 必须失败。
- RED（预取）：`OFF/FIRST_VIEWPORT/FULL_NEXT_CHAPTER`、当前章全 Ready 门禁、P0 抢占 P4、目标切换取消、配额停止、不合作 I/O 有界、Storage 迟到隔离和无进度副作用。
- GREEN：原则上保留当前实现；分类任务不能借机调整 512 MB、改变默认预取档位或新增缓存设置。如果行为测试暴露真实故障，先记录产品影响，再做最小修复。
- 分类：共享 store 生命周期为 `ENCODED_STORE_LIFECYCLE`；512 MB 与 Desktop 驱逐默认值为 `DESKTOP_ENCODED_CACHE_POLICY`；整章预取继续使用 `DESKTOP_FULL_NEXT_CHAPTER_PREFETCH`。三者不得合并成“原版有 ChapterCache”。
- Authority：原版 `ChapterCache` 只证明存在章节磁盘缓存；原版 current+4 和末五页 page-list 只证明对应原版网络边界，不能证明整章图片预取。
- Focused 验证：`EncodedPageStoreContractTest`、Android/Desktop encoded store integration、Desktop session/runtime factory/reader settings tests、相关 parity authority tests。
- 关闭条件：shared 可靠性、Desktop 容量默认值和 Desktop 产品预取分别有真实 provenance/behavior evidence；关闭 FULL 后网络行为退回已记录原版边界。
- 预计：2–3 工程日，约 5–8 个测试/fixture/doc 文件；无证据不得修改 production 默认值。

### `RNC-07` Authority、治理守卫与最终验收

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`RNC-01`～`RNC-06` 全部完成。
- 文档：更新 [`reader-authority.md`](../architecture/reader-authority.md) 与 [`reader-shared-core.md`](../architecture/reader-shared-core.md)，明确“上游用户语义、跨平台可靠性、产品增强、平台策略”四层；不得把实现机制继续写进 fixed-main 完成证明。
- 历史：在已完成 Reader roadmap 顶部增加本纠正计划链接和有限 supersede 说明；保留原任务/提交/RED-GREEN 历史，不回写成当时已经正确分类。
- Manifest：capability `43/45/53` 的 deviation ID、owner、classification、introduction refs、behavior evidence 和 resolution 必须与第 2.4 节精确一致；`9/44/47/49/51/54` 无意外漂移。
- RED：mutation 至少覆盖误标 `FIXED_ORIGINAL`、缺 introduction、只给源码证据、owner 串线、重复/漏 ID、把 `DUAL_FIXED_4_3_FRAME` 改回 `ACTIVE`、把 512 MB 归为共享默认、把整章预取绑定原版 +4。
- 守卫：源码扫描只补充禁止重新出现固定 4:3 常量/计算；完成证明仍来自 RNC-02 mounted/pixel tests 和各任务 production tests。
- 阶段验证：运行第 10.1 节完整 domain/Android/Desktop/parity/format 矩阵一次，不在此之前重复全量。
- 运行验收：Windows 与 macOS 都使用正式 Desktop 构建脚本；在 4:3、16:9、当前 `3840 × 2054` 近似视口和超宽窗口中人工/外部验收双页。Test Mode 不截图，视觉几何以离屏像素测试和实际应用窗口验收为准。
- 独立审查：只进行一轮，重点检查 fixed-main authority 污染、4:3 回归、测试是否执行 production、各保留项是否被误删；修复后最多一轮复审。
- 关闭条件：第 9 节完成定义全部满足，顶层任务均有 commit，父路线/Reader roadmap/manifest 不存在互相矛盾的活动状态。
- 预计：2–3 工程日，约 5–9 个治理/文档文件，加一次完整测试和双平台构建运行成本。

## 8. 审查清单

每个任务的一轮独立审查都使用以下问题，超出当前任务的发现只登记 follow-up：

- [ ] 是否把用户结果、可靠性契约和平台机制分开描述？
- [ ] 是否存在 fixed-main 之后才出现、却仍由 fixed-main evidence 背书的条目？
- [ ] `introductionRefs` 是否是真实引入提交，而不是当前 HEAD、重构完成提交或测试提交？
- [ ] `behaviorEvidenceRefs` 是否执行 production implementation/wiring？破坏 wiring 后会不会失败？
- [ ] 测试是否复制 production 算法、依赖源码字符串或只证明 symbol 存在？
- [ ] 本任务是否误删了第 1.2 节决定保留的能力？
- [ ] RNC-02 是否只改变 frame geometry，没有改变 pairing、identity、progress、zoom 或 scale preference？
- [ ] manifest、fixture、authority 文档和 production 实际行为是否一致？
- [ ] 是否混入其他工作流的未提交文件？

## 9. 风险、回滚与完成定义

### 9.1 主要风险

| 风险 | 控制 |
| --- | --- |
| 移除 4:3 时顺手重写双页算法 | RNC-02 只允许 frame modifier 最小变化，pairing/identity/progress 均有回归门禁 |
| full viewport 导致两页在中缝分离 | 两半槽保持书脊对齐，mounted/pixel tests 检查中缝和外侧留白 |
| 为“零黑边”自动裁切漫画 | 默认 `FIT_SCREEN` 固定；真实 4:3 的不可避免留白写入验收规格 |
| 分类工作再次膨胀为自动语义项目 | 清单固定 12 项、无生成器、checkpoint 串行、发现新项只登记 follow-up |
| authority 测试与 manifest 互相自证 | 独立 mutation + production behavior tests；源码/fixture 只能证明 provenance/结构 |
| 把可靠性增强误删为非上游代码 | 第 1.2 节全部保留项为硬约束，任务默认不改 production |
| 旧 gap 被关闭后历史消失 | 保留历史 deviation、引入基线和 closure evidence，另建当前语义条目 |
| 其他 active plan 同改 manifest | 激活门禁和串行提交；不从混杂工作树取证或提交 |

### 9.2 回滚

- RNC-02 不改变数据库、偏好 schema 或 cache namespace，代码回滚不需要数据迁移；
- 若 full-viewport renderer 出现阻塞性回归，回滚整个 RNC-02 功能提交，不恢复长期 feature flag 或同时保留两套 frame；
- 没有裁切/拉伸默认值变化，因此回滚不涉及用户偏好迁移；
- 分类/文档提交可以独立回滚，但不得在 production 已移除 4:3 后把 manifest 回滚为 `ACTIVE`；应修复一致性而不是制造相反权威。

### 9.3 Roadmap 完成定义

只有同时满足以下条件，本文才可标记 `DONE`：

1. 双页 production 不再包含固定 4:3 frame 或等价硬编码比例；
2. 第 4.1 节矩阵由真实 mounted/pixel behavior tests 保护，当前截图比例在宽屏下无额外上下黑边；
3. 稳定槽、封面、identity、Webtoon anchor/暂停、双页进度、settlement、store 和预取均按第 1.2 节保留；
4. 第 2.4 节 12 项在 fixture/manifest 中拥有稳定 ID、真实 provenance、production behavior evidence 和准确状态；
5. fixed-main authority 只证明原版行为，不再证明本地 presentation、缓存或可靠性机制；
6. capability `43/45/53` 完成纠正，其他 Reader capability 无意外漂移；
7. focused、完整测试、Spotless、Test Mode 和 Windows/macOS 实际构建运行验收完成；
8. 每个顶层任务完成一轮独立审查、必要的一轮修复复审和原子提交；
9. 父路线、原 Reader roadmap、本计划和 parity manifest 的状态一致。

## 10. 验证计划

### 10.1 最终一次全量

Windows PowerShell 先设置 UTF-8 环境，并由 Gradle coordinator 串行运行重型验证：

```powershell
$ErrorActionPreference = 'Stop'
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
python scripts/gradle-coordinator.py run --key reader-non-upstream-corrective-final -- ./gradlew :domain:jvmTest :data:jvmTest :app:testReleaseUnitTest :app-desktop:jvmTest :test-desktop:test spotlessCheck
```

随后运行一次治理与 Desktop 运行验收：

```bash
./gradlew :app-desktop:finalParityAudit
./scripts/desktop-smoke-test.sh
./scripts/build-desktop.sh
```

macOS 使用同一提交和正式构建脚本生成/运行应用；不得用 Gradle `build/`、`app-desktop/tmp/`、系统 JDK 辅助客户端或 Test Mode 截图代替正式产物与 production 调用链。

### 10.2 分层验证原则

- RNC-01：只运行 Reader authority/deviation focused tests、JSON parse、`git diff --check`；
- RNC-02：只运行 Dual Compose geometry/identity/pairing focused tests；
- RNC-03：只运行 Single/Dual identity 与 mounted renderer focused tests；
- RNC-04：只运行 Webtoon presentation/auto-scroll focused tests；
- RNC-05：只运行 progress domain、Android arbiter/wiring、Desktop tracker/session、data integration focused tests；
- RNC-06：只运行 encoded store、Desktop runtime/prefetch/settings focused tests；
- RNC-07：运行一次第 10.1 节最终矩阵和正式构建；此前不得重复完整 Desktop、`finalParityAudit` 或发布构建。

## 11. 进度与证据记录模板

每个 checkpoint 状态变化追加一行；checkpoint 只记 stop-gate，不单独提交。顶层任务处于 `REVIEW` 但尚未提交时仍保持 `[ ]`。

| 日期 | 任务/checkpoint | 状态变化 | 权威/范围 | RED/基线证据 | GREEN/生产结果 | 独立审查 | 验证/产物 | Commit |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-08-05 | `RNC-01.A` | `PENDING → PASS` | capability 43/45/53 与固定 12 项 owner/classification/introduction refs 人工核对 | 当前匿名/粗粒度 deviation 不满足字段契约 | N/A：只冻结权威与范围 | 随 RNC-01.D 统一审查 | production/test 路径逐项存在 | N/A（随 RNC-01 提交） |
| 2026-08-05 | `RNC-01.B` | `PENDING → PASS` | 仅校验显式字段，不读取描述词义 | 真实 fixture/manifest 因缺失稳定 ID/字段 RED；6 类独立 mutation 均按对应原因失败 | 负例门禁自身通过 | 随 RNC-01.D 统一审查 | `ReaderDeviationGovernanceTest` RED/GREEN 记录 | N/A（随 RNC-01 提交） |
| 2026-08-05 | `RNC-01.C` | `PENDING → PASS` | 12 项写入 fixture 与 manifest；4:3 保持 `OPEN` | 现有 authority/contract 测试先因旧 schema RED | 12 项 fixture/manifest 精确一致，4 个历史条目保留 | 随 RNC-01.D 统一审查 | JSON parse PASS；47 tests / 0 failures | N/A（随 RNC-01 提交） |
| 2026-08-05 | `RNC-01.D` | `PENDING → PASS；RNC-01 DONE` | 无 production 文件变化 | RED 原因与路线图一致 | 无生成器、自动分类或范围扩张 | PASS：无 owner 串线、fixed-main 污染或非行为证据 | focused tests、`git diff --check` PASS | 本批次提交（见 Git 历史） |
| — | `RNC-02` | `TODO` | — | — | — | — | — | — |
| — | `RNC-03` | `TODO` | — | — | — | — | — | — |
| — | `RNC-04` | `TODO` | — | — | — | — | — | — |
| — | `RNC-05` | `TODO` | — | — | — | — | — | — |
| — | `RNC-06` | `TODO` | — | — | — | — | — | — |
| — | `RNC-07` | `TODO` | — | — | — | — | — | — |
