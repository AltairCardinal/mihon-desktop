## Why

桌面阅读器双页模式在从右向左阅读时，第一页作为单页显示到了物理右侧。现有双页状态层已经定义了 `SinglePageSide.TRAILING` 在 RTL 下应落到物理左侧，但渲染层手写了相反的 `Alignment` 映射，绕过了该模型。

同一处渲染链路还把图片对齐和加载提示对齐复用了同一个 `imageAlignment`。因此单页图片需要靠边贴书脊时，加载转圈也跟着跑到边缘；双页两页都加载中时，每个半屏也可能各自显示一个加载提示，而不是屏幕中央一个提示。

## What Changes

- 双页渲染层改为通过统一策略函数复用 `singlePageBoxOnRight` 的物理侧规则。
- `ZoomablePageBox` 拆分图片对齐和加载提示对齐，让加载提示策略由阅读器 viewer 决定。
- 双页 viewer 汇总左右页加载状态：
  - 单页画面加载中：屏幕中央显示加载提示。
  - 双页两页都加载中：屏幕中央显示一个加载提示。
  - 双页仅一页加载中：在加载页所在半屏中央显示加载提示。

## Capabilities

### New Capabilities

- 无。

### Modified Capabilities

- 桌面阅读器双页模式的 RTL 单页位置和加载提示位置修正为既有设计行为。

## Impact

- 影响桌面端阅读器双页布局与页面加载占位显示。
- 不涉及数据库、HTTP API、导航、DI 或 public API。
