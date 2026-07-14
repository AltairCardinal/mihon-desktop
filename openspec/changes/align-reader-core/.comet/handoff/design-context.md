# Comet Design Handoff

- Change: align-reader-core
- Phase: design
- Mode: compact
- Context hash: 163e83a92a17adb3e758e9ba37c836a02accadd2247884de0fb1c067669b7671

Generated-by: comet-handoff.sh

OpenSpec remains the canonical capability spec. This handoff is a deterministic, source-traceable context pack, not an agent-authored summary.

## openspec/changes/align-reader-core/proposal.md

- Source: openspec/changes/align-reader-core/proposal.md
- Lines: 1-25
- SHA256: 0f2bc159fd32a3c1462837e5ee0da60f66639761b9e59dcc94c8179f45746a26

```md
## Why

Mihon Desktop 的阅读器仍保留了多套为了快速交付而重写的拆页、配对、导航、跳过、滤镜和预加载逻辑，导致它与 Android 原版的权威行为持续分叉。现在需要把这些非平台特有语义收敛到共享核心，同时保护 Desktop 的双页边缘匹配、自动滚动、键鼠和右键保存等产品能力。

## What Changes

- 将页面、章节、过渡、宽页拆分、双页配对、导航区域、章节跳过、滤镜参数和预加载规划提取到 `domain/common`。
- 让 Android 与 Desktop 生产阅读器共同消费共享状态、命令和缓存契约。
- 以 Android Bitmap/区域解码和 Desktop Skia/Compose 作为薄平台适配器，统一取消、错误、内存预算和淘汰语义。
- 保留并集中保护 Desktop 的 edge matching、自动滚动、键盘/鼠标和右键保存增强。
- 删除 Desktop 已被共享实现覆盖的重复业务规则，并更新 parity 9、43、44、45、47、49、51、54 的证据。

## Capabilities

### New Capabilities

- `shared-reader-core`: 定义 Android 与 Desktop 共用的阅读器模型、算法、状态、命令、解码缓存契约和产品回归边界。

### Modified Capabilities

<!-- 无既有 OpenSpec capability 的需求发生变化。 -->

## Impact

影响 `domain` common reader、Android reader、Desktop reader/UI、Skia 与 Android 解码适配器、reader 测试、Desktop Test Mode、parity manifest 和阅读器架构文档。数据库和备份格式不变，用户无需清理现有数据。
```

## openspec/changes/align-reader-core/design.md

- Source: openspec/changes/align-reader-core/design.md
- Lines: 1-47
- SHA256: 87faddd7252a7e895940ff93a74576bb7e2163487fd8b1f5d32c5a5acef749d7

```md
## Context

Android 原版阅读器拥有成熟的页面/章节状态、配对、导航、缺章、跳过、滤镜与大图处理行为；Desktop fork 为快速交付曾在 Compose/Skia 层重新实现这些规则。当前 Task 4A 已有未提交的 TDD 工作树，必须把它归入本 change，并以真实 Android/Desktop production wiring、内存预算和 Desktop 产品保护证明收敛完成。

## Goals / Non-Goals

**Goals:**

- 在 `domain/common` 建立唯一的阅读器语义和可复用契约。
- Android 与 Desktop viewer 均通过薄 adapter 消费共享模型、命令、预加载和滤镜参数。
- 对大图实施有界区域解码、取消、代次防陈旧回填和字节预算缓存。
- 保持 Desktop edge matching、自动滚动、键鼠与右键保存零回退。

**Non-Goals:**

- 不把 Android View/Bitmap 或 Desktop Compose/Skia 类型放入 common。
- 不要求两端渲染控件或输入设备实现相同。
- 不改变备份、数据库和阅读进度持久格式。

## Decisions

1. 共享层使用纯数据模型和命令，平台层只映射像素载体与 side effect。选择共享抽取而非复制 Android 类，可消除两套业务规则；不选择直接移动 Android View，因为它会污染 KMP 边界。
2. 配对默认严格采用 Android 权威向量；Desktop 封面单页、edge matching 与 landscape parity 通过显式产品选项叠加，避免反向改变 Android 默认语义。
3. `PageDecoder`、`RegionDecoder`、`PageCache` 只约束请求、尺寸、错误、取消和预算；Android/Skia 分别实现载体。大图先探测尺寸，再以向上取整采样或 region decode 保证缓存上限。
4. 预加载使用 generation/cancellation 与可观察 cache generation；三种 Desktop viewer 共同消费，命中缓存时停止重复 Coil 全图请求。
5. 迁移顺序为契约测试 → Desktop 产品基线 → shared core → 两端 adapter → production wiring → 删除重复路径，任何旧路径只在共享路径全绿后删除。
6. Android 运行时验收由开发流程自行创建和部署模拟器，使用当前提交构建 APK 并验证真实 reader 页面、手势、滤镜、重试和大图行为。

## Risks / Trade-offs

- [未知尺寸或损坏图片绕过内存预算] → 探测失败不写缓存，UI 显示明确错误并允许重试。
- [快速翻页旧协程回填陈旧页面] → generation 校验、完整淘汰遍历和取消集成测试。
- [共享默认被 Desktop 增强污染] → 增强必须显式启用，并保留 Android 权威向量测试。
- [跨模块修改导致局部绿灯掩盖 wiring 缺口] → Android/Desktop production source 与集成测试、完整 reader 矩阵和固定 EXE 验收共同门禁。
- [JVM 测试无法证明 Android 图形运行时行为] → 自动部署模拟器执行代表性章节与大图验收，不要求用户提供实体设备。

## Migration Plan

1. 归因并审计当前 Task 4A 未提交工作树，补齐所有 RED/GREEN 证据。
2. 切换 Android 与 Desktop production viewer 到 shared core，同时保留回归测试覆盖的 Desktop 增强。
3. 删除已无调用的 Desktop 拆页、导航和跳过规则。
4. 更新 parity manifest/架构文档，提交后用构建脚本生成新 BUILD 并启动固定 EXE。
5. 若验证失败，回滚本 change 的入口切换提交；共享纯模型可在不改变用户数据的情况下保留或一并回滚。

## Open Questions

- macOS 上是否需要额外验证 Skia region decoder 的平台差异；若 JVM 契约不足，则通过 `ssh mbp` 运行同一 fixture。
```

## openspec/changes/align-reader-core/tasks.md

- Source: openspec/changes/align-reader-core/tasks.md
- Lines: 1-27
- SHA256: 30c79443df6b124b44331265d4b8a365424a5b9b7852c7fa9add7be7985367b0

```md
## 1. 共享契约与产品基线

- [ ] 1.1 归因并审计当前 Task 4A 未提交工作树，记录每轮 RED/GREEN 命令与结果
- [ ] 1.2 用 Android 原版 fixture 完成页面、拆分、配对、导航、跳过、过渡、滤镜和预加载共享契约测试
- [ ] 1.3 集中保护 Desktop 双页/edge matching、自动滚动、键鼠和右键保存产品行为

## 2. Shared core 与双端生产接线

- [ ] 2.1 完成 common 页面/章节/过渡、PageTransform、ReaderNavigation 与 skip policy
- [ ] 2.2 完成 PageDecoder、RegionDecoder、PageCache、预加载代次/取消/淘汰与字节预算契约
- [ ] 2.3 让 Android pairing、transition、navigation、filter、skip、preload 和 decoder/cache 生产路径委托 shared core
- [ ] 2.4 让 Desktop 三种 viewer、ScreenModel、设置和 Skia decoder/cache 生产路径委托 shared core

## 3. 内存、错误与用户体验

- [ ] 3.1 验证真实大图走有界采样/region decode，普通缓存不长期驻留全尺寸 bitmap
- [ ] 3.2 验证快速翻页取消旧请求、拒绝陈旧回填并淘汰旧窗口全部页面
- [ ] 3.3 验证阅读器入口、加载、错误、重试、缺章和章节边界反馈可见可操作
- [ ] 3.4 验证 grayscale/invert 持久化并在 Android/Desktop 实际渲染路径生效

## 4. 去重、追踪与验证

- [ ] 4.1 删除 Desktop 已由 shared core 覆盖的拆页、导航和跳过规则，保留有证据的产品/平台层
- [ ] 4.2 更新 parity 9、43、44、45、47、49、51、54 与 reader 架构文档
- [ ] 4.3 运行 domain、Android reader、Desktop reader/UI/parity、Test Mode、Spotless 和 diff 检查，并自行部署 Android 模拟器完成 reader 运行时验收
- [ ] 4.4 提交功能并通过独立规格/代码质量 review，修复全部 Critical/Important 问题
- [ ] 4.5 使用 `scripts/build-desktop.sh` 构建并启动固定 EXE，核对完整版本、mtime 和窗口标题
```

## openspec/changes/align-reader-core/specs/shared-reader-core/spec.md

- Source: openspec/changes/align-reader-core/specs/shared-reader-core/spec.md
- Lines: 1-70
- SHA256: bb273255ef12a2136556b86d763630c6c376215008b7ad3b55bbc6ceeb2b5e9d

```md
## ADDED Requirements

### Requirement: Android and Desktop share reader semantics
系统 SHALL 在 common 层定义页面、章节、过渡、拆页、配对、导航、跳过和滤镜语义，并让 Android 与 Desktop 生产阅读器共同消费这些语义。

#### Scenario: Same chapter fixture on both platforms
- **WHEN** Android 与 Desktop 读取相同章节、页面尺寸、方向和偏好 fixture
- **THEN** 两端产生相同的逻辑页序、章节边界、跳过结果和错误分类

#### Scenario: Platform renderer remains isolated
- **WHEN** 两端渲染共享页面状态
- **THEN** Android Bitmap/View 与 Desktop Skia/Compose 仅存在于各自 adapter，common API 不暴露平台类型

### Requirement: Wide pages and pairing follow the authoritative contract
系统 SHALL 以 Android 原版行为作为宽页拆分、旋转、方向反转、双页配对和未知尺寸处理的默认权威契约。

#### Scenario: Wide page is split in reading order
- **WHEN** 宽页在 LTR、RTL 或旋转模式下需要拆分
- **THEN** 虚拟页边界覆盖全部像素、顺序符合阅读方向且能映射回原始页

#### Scenario: Desktop product pairing is enabled
- **WHEN** Desktop 启用封面单页、edge matching 或 landscape parity 增强
- **THEN** 增强仅通过显式选项改变 Desktop 布局，Android 默认配对结果不变

### Requirement: Reader transitions and navigation are explicit
系统 SHALL 表达 Wait、Loading、Loaded、Error、缺章、上一章/下一章边界与 Retry 命令，并统一点击区和方向反转语义。

#### Scenario: Chapter load fails
- **WHEN** 当前或相邻章节加载失败
- **THEN** 阅读器显示可见错误与重试操作，重试重新发起同一共享命令

#### Scenario: No adjacent chapter exists
- **WHEN** 用户在章节边界继续导航且没有符合跳过规则的目标
- **THEN** 系统返回明确 Boundary 结果并显示边界反馈，不越界或打开错误章节

### Requirement: Chapter skipping uses one shared policy
系统 SHALL 使用可组合的 read、filtered、duplicate 策略寻找相邻章节，Android 与 Desktop 不得保留独立判定分支。

#### Scenario: Multiple skip reasons apply
- **WHEN** 相邻章节分别已读、被过滤或为重复章节
- **THEN** 系统跳过所有命中策略的章节并返回最近的有效目标或 Boundary

### Requirement: Image preloading is cancellable and memory bounded
系统 SHALL 共享预加载窗口、优先级、取消、代次和淘汰契约，并通过平台 decoder/cache adapter 限制缓存字节和图片尺寸。

#### Scenario: User changes pages quickly
- **WHEN** 新预加载代次在旧请求完成前开始
- **THEN** 旧请求被取消或拒绝回填，旧窗口的全部页面被淘汰

#### Scenario: Oversized image is loaded
- **WHEN** 页面尺寸或字节量超过缓存预算
- **THEN** 平台使用有界采样、区域解码或 tile，普通缓存不长期保留全尺寸 bitmap

#### Scenario: Preloaded image arrives after composition
- **WHEN** viewer 首次组合时缓存未命中但随后预加载完成
- **THEN** 可观察缓存代次触发重组，三种 viewer 使用缓存且不并行保留重复全图请求

### Requirement: Desktop reader product capabilities do not regress
Desktop 阅读器 MUST 在共享迁移后保留双页 edge matching、Webtoon 自动滚动、键盘/鼠标导航和右键保存。

#### Scenario: Shared core is wired into Desktop
- **WHEN** 用户使用任一 Desktop 阅读模式和产品增强
- **THEN** 操作路径、反馈和保存目标与迁移前一致，并由集中产品回归测试保护

### Requirement: Android reader runtime is verified on a deployed emulator
当 reader change 触及 Android production viewer、decoder 或 UI wiring 时，验证流程 MUST 自行部署 Android 模拟器并运行当前提交的应用。

#### Scenario: Android runtime acceptance is required
- **WHEN** shared reader core 完成 JVM/Android 单元测试
- **THEN** 自动化在模拟器安装当前 APK，并验证章节打开、翻页、滤镜、错误重试、章节边界和代表性大图路径
```

