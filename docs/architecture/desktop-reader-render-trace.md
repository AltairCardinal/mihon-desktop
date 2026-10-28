# 阅读器全屏渲染取证

用于调查实际阅读器的全屏瞬态：保存应用绘制帧、窗口几何、漫画绘制范围及状态切换。
默认关闭；不使用 Computer Use，不读取桌面屏幕，不上传数据，也不改变全屏切换规则。
窗口适配、共用图片观察、保存和离线分析共同实现这一条取证链；主要风险集中在帧关联、Skiko 私有字段、
资源释放和插桩开销，不能靠增加无关阅读器测试替代这些专项验证。

## 启用与使用

关闭旧实例，以正常用户配置启动本轮体验包并传入：

```text
--reader-render-trace=D:\absolute\reader-render-traces
```

仅传 `--reader-render-trace` 时写入当前 profile 的 logs/reader-render-traces。
每次挂载实际阅读器创建独立目录。正常打开漫画，在发生问题的页按 F11；初始阅读器帧及每次全屏请求
后约 1.8 秒进入截帧窗口。进入前最后完成的绘制内容也作为基线。离开阅读器或退出应用会排空写入队列。
诊断模式会增加栅格化和编码开销；没有观察到问题不能证明原问题已消失。

目录包含 `events.jsonl`、`manifest.json` 和 PNG。每个成功捕获保留 renderId、时间和元数据。
相同尺寸且逐字节完全相同的 RGBA 像素可共享 PNG，但仍为每个 renderId 写一条 frame-written；
它不是省略帧。CRC 只用于快速筛选，复用前还检查全部像素相等。

```powershell
python scripts/analyze-reader-render-trace.py "D:\absolute\reader-render-traces\session-directory"
```

分析器依赖 Pillow，输出同目录 `analysis.json` 和 `analysis.html`：原始帧逐张浏览、漫画四角覆盖层、
窗口/画布/DPI 数据、帧间像素差异、几何回跳候选，以及不依赖坐标日志的 PNG 纵向对齐与回位候选。
候选只是定位线索，不能直接当作原因或修复结论。PNG 与命令缺失、尺寸不匹配、事件丢失和仍在写入
均会使记录不完整；没有漫画几何的帧也单独反映在 geometryComplete 中。

## 记录阶段和关联

- 真实 `ReaderViewport` 绑定当前窗口；单页和双页共用 `ZoomablePageBox` 原有 BitmapPainter 观察链。
  记录页/资源代际、原始绘制矩形、实际变换、根坐标四角，区分 actual-painter-command 与已应用布局采样。
- 全屏请求、原生尺寸变更前后、最终布局/绘制返回、AWT 移动/尺寸事件、阅读器视口与模式状态使用同一
  System.nanoTime 时间域。原生 HWND 客户区/Canvas 是物理屏幕坐标，AWT 矩形是逻辑坐标；同时保留 DPI。
- 包装原 Skiko renderDelegate，仅调用一次原 delegate，传入原 Canvas 和原参数。没有另换画布或嵌套录制。
- Skiko 0.9.37.4 的 delegate 回调早于该次 Picture 完成。不能在回调中把 screenshot() 结果标为本帧。
  诊断只读取私有 `SkiaLayer.picture` 的对象身份作为版本适配边界：下一次回调入口或受序号保护的 EDT
  完成回调中，确认 holder 已替换，再截取已完成 Picture；截取后再次核对身份。旧 Picture 不被访问或释放。
  重入、holder 未完成或关联无法确认时写 capture-gap，不能补造对应关系。
- `captureStage=completed-layer-picture` 表示将实际完成的应用绘制指令栅格化。它不是 GPU backbuffer、
  swap、DWM 合成或显示器扫描输出；`presentationObserved` 始终为 false。回调编号也不是 vsync 编号。
  若应用帧正常但用户仍看到跳动，需另查未观测的呈现阶段，不能反推用户观察错误。

## 资源和失败处理

每个阅读器会话最多请求 180 帧、排队像素 256 MiB、独立 PNG 512 MiB。排队像素限制不等于进程内存上限：
栅格化、RGBA 副本、相同像素缓存和编码缓冲还会占用内存。PNG 使用无损快速编码；事件通过 4096 项有界
队列异步写入，主渲染线程不逐条刷盘。截图重放仍同步发生，captureStarted/Finished 记录其耗时。

超限、编码/写盘失败、事件队列溢出、错误线程、重入或帧身份不明均明确报告 PARTIAL。不得以缺记录推断
没有显示或没有跳动。关闭后仅在 PNG worker 真正退出、事件排空后关闭日志；超时保持未完成状态并提示。
普通模式不安装这些观察器。Skiko 私有结构变化时诊断失效须显式报告，不能阻断真实绘制或全屏。

## 验证边界

`ReaderRenderTraceNativeTest` 使用真实 ReaderViewport、真实 PNG 解码、单页/自动布局、原生窗口及全屏
adapter，检查捕获的像素、renderId 与图片尺寸、所有请求帧写入及超限 PARTIAL。窗口隐藏且不获取焦点。
它证明取证链路有效，不证明用户漫画的上跳已复现或已修复。启动参数另由 production bootstrap 接线测试覆盖。
分析器用真实 PNG 文件测试位移回位、无坐标时的像素分析、缺图、错误尺寸与不完整记录。
