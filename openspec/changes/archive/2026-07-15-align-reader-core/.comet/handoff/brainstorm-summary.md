# Brainstorm Summary

- Change: align-reader-core
- Date: 2026-07-15

## 确认的技术方案

采用“共享契约 + 双端薄适配 + 渐进切换”。页面/章节/过渡、拆页/配对、导航、跳过、滤镜参数、预加载规划和缓存预算进入 `domain/common`；Android Bitmap/View/Coil/子采样与 Desktop Skia/Compose/输入设备保留在平台 adapter。Android 原版向量是默认权威语义，Desktop 封面单页、edge matching、landscape parity、自动滚动、键鼠和右键保存通过显式产品层叠加。

方案比较：

1. 一次性移动 Android reader：平台类型污染 common，回归面过大，拒绝。
2. Desktop 仅模拟 Android 结果：仍有两套规则，不能消除技术债，拒绝。
3. 共享纯核心并渐进切换：可用相同 fixture 证明双端一致，同时隔离平台渲染，采用。

用户已明确授权此类流程/设计选择由 agent 自行决定并持续推进，因此按推荐方案视为确认。

## 关键取舍与风险

- 默认语义不能被 Desktop 增强反向污染；增强只接受显式 options。
- 未知/损坏图片不得绕过预算；尺寸探测失败返回错误且不写缓存。
- 快速翻页通过 generation、协程取消、陈旧回填检查和完整淘汰遍历解决。
- `LibraryScreenModel`/`MangaDetailScreenModel` 仅允许补充 shared skip policy 所需的 filtered/duplicate 元数据，不得夹带书库行为重构。
- JVM/源码断言不足以证明 production wiring；必须覆盖真实调用链、Desktop 固定 EXE 与 Android 模拟器。
- Desktop Skia region 行为在 Windows 验证，并在 macOS 当前提交快照运行适用测试以降低 native 差异风险。

## 测试策略

- TDD：保留每轮 RED 原因和 GREEN 结果。
- `domain/common`：Android 权威 pairing、split、rotation、navigation、skip、transition、filter、preload/cache fixture。
- Android：production delegate/wiring、decoder/cache contract、reader 单元/集成测试；自行部署模拟器安装当前 APK，验收打开章节、翻页、滤镜、重试、边界与大图。
- Desktop：reader/ui.reader/parity 定向矩阵、真实 Skia region/预算、stale cancellation、三种 viewer cache wiring、产品回归、Test Mode。
- 平台：Windows 固定 EXE 版本/mtime/窗口标题；必要的 macOS Skia 测试通过 SSH 当前快照。
- 审查：功能提交后由独立 reviewer 同时审查 spec compliance 与 code quality，Critical/Important 清零后才进入正式构建。

## Spec Patch

无。当前 `shared-reader-core` delta spec 已包含双端 production wiring、大图预算、陈旧预加载、Desktop 产品保护和 Android 模拟器验收场景。
