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
