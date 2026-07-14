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
