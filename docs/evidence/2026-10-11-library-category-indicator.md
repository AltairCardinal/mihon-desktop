# Windows 书架分类指示器越界修复

基线：`d5d6982bd9`；用户报告版本：`0.11.19.91.e7fdbf1`。

## 原因与边界

2026-10-11 20:28:33 的崩溃日志指向 Material3 `ScrollableTabRow` 默认指示器，异常为
`Index 1 out of bounds for length 1`。真实书架 Compose 测试复现了相同异常与堆栈：
只有 H 标签时增加未分类作品，分类投影将 H 的选中索引从 0 更新为 1；指示器子组合仍可能
收到上一帧只有一项的布局位置。状态模型中的分类与选中索引本身保持有效。

书架现在显式绘制同样的 SecondaryIndicator，布局位置尚未就绪时暂缓绘制，下一次布局恢复。
不改变分类选择、同步协议、持久化或书架数据。截图中的“书架更新失败”没有被此专项确认为同一原因。

## 验证

- 红：协调器 `category-indicator-red2` 的新页面测试因上述越界失败。前一轮测试夹具遗漏系统分类，
  断言失败不作为产品缺陷证据；补齐夹具后才确认红阶段。
- 绿：`category-indicator-green` 的同一复现测试通过。
- 补验：`category-indicator-regression2` 运行 `LibraryPageCompositionTest`、
  `LibraryCategoryProjectionTest` 和 `LibraryCategoryBehaviorTest.last used category*`，
  实际通过 28 项、跳过 1 项。跳过的是仅适用于显式 non-release 配置的既有更新间隔测试。
  新测试包含反复显隐系统分类及点击两个分类，验证可见作品和选择。
- 格式：Desktop 未配置 Spotless task；首次任务名及后续 Spotless hook 尝试不计格式证据。
  `category-indicator-format` 用项目 ktlint 1.8.0 实际检查两个修改文件，通过；`git diff --check` 通过。
- 构建：`category-indicator-windows-preview` 通过，production 扩展运行时验收通过。
- 打包后的 Windows 原生窗口：独立 `--test-profile`、虚构 H 分类及两本作品；通过 production
  分类 setter 将作品移入/移出 H，同时保留书架页面，验证标签从 1 → 2 → 1、指示器正确移动，
  原生鼠标点击两分类显示各自作品，最终两作品均回到 H，无崩溃日志。测试实例已关闭。
  截图保存在 `.gradle-coordinator/category-indicator-native-{grown,default-selected,restored}.png`。

交付入口（已确认存在）：
`app-desktop/artifacts/preview/windows/20261011T123437Z-2640d9be/Mihon-Desktop-0.11.19.91.d5d6982-unpacked/Mihon Desktop.exe`。
构建发生于提交前，manifest 记录基线和本次源码差异。原生验收仅覆盖本缺陷，
不把 preview manifest 的完整原生交互矩阵 `NOT_RUN` 改成通过；未跑全量测试，Android 无代码改动。
