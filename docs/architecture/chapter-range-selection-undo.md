# 章节区间选择的单步撤销

## 目的与状态归属

作品详情的章节多选工具栏复用同一个按钮：首次点击补齐当前显示顺序中最前与最后选中章节之间的可见章节；有效时切换为虚线图标与“撤销区间选择”，再次点击恢复此前的选择并消费快照。第三次点击重新补齐。

`LibrarySelectionPolicy.kt` 的 `toggleChapterRangeSelection` 是双端共享契约。快照持有此前选中 ID、平台锚点、完整可见 ID 顺序和补齐后的选中 ID。集合在捕获时复制，平台锚点必须是不可变值。Desktop 使用 `LibrarySelectionAnchor`，Android 使用原始 `selectedPositions` 的值对。无选中、单个可见选中项或已经补齐时不产生快照。

## 有效性与失效边界

- 恢复前同时校验当前选中 ID 与补齐结果相等、可见 ID 的内容与顺序完全相同。失效快照不能恢复；此时按当前列表执行一次普通区间选择。
- 手动选择、Shift／长按范围、全选、反选和清空均立即清除快照，包括没有改变选中集合的显式操作。
- 筛选、排序、增删章节造成可见 ID 或顺序变化时清除快照；即使之后恢复旧顺序也不能重新获得撤销资格。
- 元数据、阅读／下载进度刷新在选中集合及可见 ID 顺序不变时保留快照；滚动、重组和主题变化不影响撤销。
- 退出多选或离开详情清除快照，不持久化、不跨作品。Android 在详情 composition 离开和 ScreenModel 销毁时清除；Desktop 的状态由当前详情 composition 的 `remember(mangaId)` 持有。

## 平台接线与异步结果

Desktop 的 `ChapterSelectionState` 同时恢复 ID 和 Shift 锚点；撤销作为用户编辑推进选择修订，之前接收的批量回调不得覆盖它。批量完成实际移除选中项时清除快照；没有选中变化的完成不使快照失效。

Android 通过 `State.Success` 发布快照和按钮状态；所有既有 `updateSuccessState` 更新统一检查有效性。区间动作在恢复时同步 `selectedChapterIds` 缓存，避免下一次章节刷新重新选中已撤销的章节。缓存改动限定在选择动作中，不在普通进度／对话框等每次状态更新时清空再重建，以免引入额外的 IO 投影窗口。原始长按锚点同时恢复。

共享 `undoSelectRangeIcon` 使用审核通过的 SVG 几何：横线方块边长 2px，斜线方块边长约 1.829132px，沿折线相邻块的边缘净距离约 1.497717px；折角保留原轮廓，并用分别垂直于两条线的切面形成五边形。由主题 tint 着色，无内嵌文字。

## 定向回归

- `ChapterRangeSelectionUndoTest`：双端共享的补齐／撤销／再补齐、无变化操作、可见身份和顺序校验、集合快照隔离。
- `ChapterSelectionStateTest`：Desktop 锚点、无变化选择动作、投影变化、异步完成和修订保护。
- `MangaDetailInteractionTest.chapter range button…`：真实桌面工具栏点击、选择反馈、撤销入口与图标渲染。
- `MangaScreenModelSharedMutationWiringTest.Android…detail range button…`：真实 phone/tablet 点击与离页，模型锚点、缓存刷新、无变化选择动作及可见顺序／删除边界。

这些证据属于定向行为和接线验证；离屏 Compose／Robolectric 不等于 Windows 原生输入或 Android 真机验收。
