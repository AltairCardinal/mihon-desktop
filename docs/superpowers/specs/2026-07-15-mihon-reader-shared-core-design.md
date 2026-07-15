---
comet_change: align-reader-core
role: technical-design
canonical_spec: openspec
archived-with: 2026-07-15-align-reader-core
status: final
---

# Mihon 阅读器共享核心技术设计

## 1. 背景与目标

Mihon Desktop 从 Android 原版 fork 后，为快速获得可用阅读器，在 Compose/Skia 层重新实现了页面拆分、双页配对、导航、章节跳过、滤镜与预加载。长期结果是 Android 与 Desktop 对相同章节可能产生不同页序、错误状态、跳过目标和内存行为。

本 change 将非平台特有的阅读器语义收敛为 `domain/common` 的唯一实现。Android 和 Desktop 只保留像素载体、渲染控件、文件/网络 side effect 与输入设备差异。Desktop 的 edge matching、自动滚动、键盘/鼠标和右键保存属于永久产品能力，必须继续存在但不能替代共享语义。

需求事实源是 `openspec/changes/align-reader-core/specs/shared-reader-core/spec.md`；本文只描述实现方式和取舍。

## 2. 方案选择

### 2.1 被拒绝的方案

**整体移动 Android reader 到 KMP。** Android reader 直接依赖 View、Bitmap、Coil、SubsamplingScaleImageView、Activity 与 Android 偏好，整体移动会把平台 API 泄漏进 common，并迫使 Desktop 模拟 Android UI 生命周期。

**Desktop 继续独立实现，只增加结果对比测试。** 这能发现漂移，却仍需永久维护两套规则，不能消除路线图定义的技术债。

### 2.2 采用的方案

采用“共享纯核心 + 双端薄适配 + 渐进切换”：

```text
Android UI/View ─┐
                 ├─ platform adapter ─ shared reader core ─ shared repositories/events
Desktop UI/Skia ─┘
```

共享核心决定逻辑结果；adapter 只把平台输入映射为共享请求，并执行共享命令产生的 side effect。迁移先固定契约和产品基线，再切换 production wiring，最后删除重复路径。

## 3. 组件边界

### 3.1 `ReaderPageModel`

定义平台无关页面元数据、章节状态、过渡和解码/缓存契约：

- `ReaderPageModel` 只包含 index、URL、逻辑来源与映射信息。
- `ReaderChapterState` 明确 `Wait/Loading/Loaded/Error`，过渡携带相邻章节、缺章数、Boundary 与 Retry 命令。
- `PageDecoder<S,T>`、`RegionDecoder<S,T>` 和 `PageCache<T>` 使用泛型隔离 encoded/decoded 载体；common 不认识 Bitmap 或 ImageBitmap。
- `ReaderPreloadPlanner` 只输出窗口、优先级、取消集合和 generation，不启动协程或访问网络。

### 3.2 `PageTransform`

负责旋转后尺寸、宽页判断、奇数像素拆分边界、虚拟页映射和双页配对。默认配对严格使用 Android 权威向量。Desktop 增强通过 `PagePairingOptions` 显式传入：

- cover single；
- forced single；
- edge-matched pair；
- landscape 后 parity 调整。

未提供 options 时，结果必须与 Android 原版一致，禁止把 Desktop 默认写回 shared default。

### 3.3 `ReaderNavigation`

共享层把点击/键盘输入归一为 `PreviousPage`、`NextPage`、`PreviousChapter`、`NextChapter`、`Menu` 等命令，并统一 LTR/RTL/垂直阅读与反转模式。

章节导航使用单一 `ChapterSkipPolicy(read, filtered, duplicate)`。Android `ReaderViewModel` 与 Desktop `ReaderNavigator` 都把章节映射为共享候选后调用同一查找函数。书库/详情层只补齐 filtered/duplicate 元数据；不得在该处新增另一套跳过决策。

### 3.4 滤镜参数

`ReaderColorFilterParams` 统一 enabled、brightness、tint、grayscale、invert、归一化与 `isEffective`。Android adapter 生成硬件图层 `ColorMatrix/paint`，Desktop adapter 生成 Compose/Skia `ColorMatrix`。平台层可以使用不同渲染 API，但开关组合和边界值必须一致。

## 4. 解码、预加载与缓存数据流

### 4.1 Desktop 数据流

```text
ReaderSideEffects
  → ReaderPreloadPlanner(generation/window)
  → fetch encoded bytes
  → peekSize
     ├─ within bounds → sampled PageDecoder
     └─ oversized     → RegionDecoder / bounded output
  → byte-budget DesktopPageCache
  → cacheGeneration StateFlow
  → Single/Dual/Webtoon viewer recomposition
  → cache hit: Coil model = null
```

采样比必须向上取整，确保目标宽高不超过上限。缓存按实际 decoded bytes 计量并执行 LRU 淘汰。三种 viewer 都观察 `cacheGeneration`；否则 late preload 只写缓存却不会更新屏幕。

### 4.2 Android 数据流

Android Coil/reader image path 通过 `AndroidReaderPageDecoder` 和共享 cache policy 选择解码/缓存策略；子采样或动画路径仍由 Android 控件实现。`HttpPageLoader` 使用共享前向窗口与取消集合，不能保留私有 `preloadNextPages` 业务规则。

### 4.3 并发正确性

每次页面位置变化创建新 generation：

1. 取消不再需要的 job；
2. 完整遍历并淘汰旧窗口所有 key，不能用短路 `any`；
3. job 完成时核对 generation；
4. 过期结果丢弃，不更新 cache 或 cacheGeneration；
5. 当前 generation 写入后递增可观察代次。

未知或损坏图片尺寸返回明确失败，不进入缓存。预加载失败不得阻断当前可见页，当前页仍显示共享 Error/Retry 状态。

## 5. UI 与用户反馈

入口保持漫画详情页打开章节。三种阅读模式必须具备：

- 当前页加载与错误状态；
- 可操作 Retry；
- 上一章/下一章过渡、目标标题与缺章数；
- 无可用相邻章的 Boundary 反馈；
- grayscale/invert 等设置即时预览与持久化；
- URL/缓存缺失时显示错误而不是空白。

Desktop 继续保留 edge matching、自动滚动、键盘/鼠标与右键保存。右键保存虚拟拆分页时必须保存用户看到的正确半页，而不是原图错误区域。

## 6. 迁移与回滚

1. 将当前未提交 Task 4A 工作树归因到本 change，并逐文件排除无关修改。
2. 从 Android 原版 fixture 建立 common RED，再实现 shared core。
3. 先接 Android/Desktop adapter 测试，再切 production viewer/ScreenModel。
4. 双端与 Desktop 产品回归全绿后删除重复 Desktop 规则。
5. 更新 parity 9、43、44、45、47、49、51、54 和架构文档。
6. 功能提交后独立复审；通过后运行 Android 模拟器、Desktop build/EXE 和适用 macOS 验证。

本 change 不迁移持久数据。若 production wiring 失败，可回滚入口切换提交；不得通过恢复长期双轨业务实现结束任务。

## 7. 测试与验收

### 7.1 自动化层级

- Common：拆分/旋转/方向、配对、状态/Retry、导航反转、组合 skip、滤镜边界、预加载窗口/取消/淘汰/预算。
- Android JVM：production delegate、ReaderViewModel skip、滤镜 adapter、HttpPageLoader、decoder/cache、pairing/transition/navigation。
- Desktop JVM：真实 PNG region decode、byte-budget LRU、late cache recomposition、三 viewer wiring、ScreenModel 状态和 parity manifest。
- Desktop 产品：双页、edge matching、Webtoon 自动滚动、键鼠、右键保存与现有 reader 回归集合。
- Test Mode：阅读器导航、状态和错误路径可由外部测试观察。

### 7.2 运行时验收

- 自行部署 Android 模拟器，安装当前提交构建的 APK，验证代表性章节的打开、翻页、LTR/RTL、滤镜、错误重试、章节边界和大图。
- 使用 `scripts/build-desktop.sh` 生成新 BUILD，启动固定未打包 EXE，核对文件 mtime、完整版本和可见窗口标题。
- Skia native 行为若存在平台疑点，使用 `ssh mbp` 或 `ssh mbp-lan` 在当前提交快照运行相同 region/cache fixture。

### 7.3 审查门禁

实现代理完成后，独立 reviewer 必须检查：

- OpenSpec spec compliance；
- shared/platform/product 边界；
- production 调用链而非仅类型存在；
- TDD 与集成测试证据；
- 无关文件和用户改动未混入；
- Critical/Important 问题清零。

## 8. 完成定义

只有同时满足以下条件才完成：

- Android/Desktop production reader 共同消费共享语义；
- Desktop 重复拆页、导航和跳过规则已删除；
- 大图与快速翻页内存/并发门禁通过；
- Desktop 永久产品能力零回退；
- parity 8 项证据与真实调用链一致；
- Android 模拟器、Windows 固定 EXE 和必要 macOS 验证有当前提交证据；
- 独立 reviewer 批准且无未解决 Critical/Important。
