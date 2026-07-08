## Design

### Root Cause

现有架构中，双页分页状态层已经有方向无关的 `SinglePageSide` 和方向感知的 `singlePageBoxOnRight(side, isRtl)`。这部分抽象本身是正确的，问题是 `DualPagePagerViewer` 没有复用它，而是在 Composable 分支中手写了 `Alignment.CenterStart` / `Alignment.CenterEnd` 映射，导致 RTL 下 `TRAILING` 单页被放到物理右侧。

加载提示的根因是职责耦合：`ZoomablePageBox.imageAlignment` 同时控制图片和加载提示。图片贴书脊是页面布局需求，加载提示是占位反馈需求，两者不能共用同一策略。

### Approach

1. 在双页 viewer 层提供可测试的布局策略：
   - `singlePageImageAlignment(side, isRtl)` 只负责把双页状态层的物理侧规则转换为 Compose `Alignment`。
   - `dualPageLoadingIndicatorPlacement(leftLoading, rightLoading)` 只负责双页加载提示的位置决策。
2. `ZoomablePageBox` 保持作为单页渲染基础组件，但新增内部参数：
   - `loadingAlignment`
   - `showLoadingIndicator`
   - `onLoadingStateChange`
3. 双页 viewer 汇总左右页加载状态，在两页都加载时由外层显示屏幕中央提示；只有一页加载时允许对应半屏子组件显示半屏中央提示。

### Boundaries

- 不改变页面分组规则、翻页方向、自动切分、裁边或右键菜单能力。
- 不新增用户入口；用户可见变化仅限现有桌面阅读器双页模式的页面位置和加载提示位置。
- `ZoomablePageBox` 的新增参数保持默认值，单页阅读器继续保持屏幕中央加载提示。
