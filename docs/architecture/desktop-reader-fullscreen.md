# Desktop 阅读器全屏切换

全屏入口沿用阅读器按钮、F11 和 Escape，由 `ReaderScreenModel` 保存阅读器状态，
`ReaderViewport` 将状态交给 `DesktopReaderFullscreenController`。切换不重建阅读会话，
不改变当前页、已解码图片或双页配对；真实视口改变仍须更新双页几何能力。

窗口操作留在 Desktop adapter 中：Android 的系统窗口 API 不能用于 AWT／Compose Desktop。
Windows 优先保留原生窗口与绘制表面的无边框实现；其他平台或 adapter 不可用时，
保留 Compose 的 `WindowPlacement` 路径和原窗口状态恢复逻辑。
无边框 adapter 必须同时更新 HWND 的物理边界和 AWT 的逻辑边界，退出时恢复两者；
仅调用 Win32 改变边界会让 Skiko 的 heavyweight Canvas 继续消费旧 AWT 尺寸。

进入、退出全屏及离开全屏阅读器时，控制器在事件队列中执行窗口变更，
等待原生尺寸事件回到 AWT 后，在下一次事件处理周期调用 `invalidate()`、`validate()`
和 `renderImmediately()`，再恢复系统过渡效果。失效标记不能省略：有效容器的 `validate()`
不会重新布局。连续切换时仅由最新窗口变更完成重绘和恢复过渡，旧回调不覆盖新结果。
绘制失败时仍在 `finally` 中恢复系统过渡效果；过期请求、已关闭或未创建的窗口不执行切换。

Windows 进入全屏时，在改动原生尺寸前通过 SkiaLayer 的已记录绘制画面生成临时快照，
以不获取焦点的 owned JWindow 保留当前漫画，按比例适配目标屏幕。快照只包含本应用已渲染内容，
不读取桌面屏幕，也不重新解码漫画。过渡窗口先隐藏准备并完成绘制；前台阅读器才显示该窗口，
后台／隐藏阅读器的快照保持隐藏。全屏首帧绘制与 DWM flush 完成后再关闭快照。
退出全屏不创建快照，沿用既有恢复路径。捕获或显示不可用时回退到原切换；原生变更失败、
关闭阅读器或过期切换被替代时均释放临时窗口与像素，不保留跨切换缓存。

阅读器自动单／双页布局还有独立的稳定等待期（当前为 150ms），可能晚于窗口首帧完成。
`ReaderContent` 因此在两种 pager 之上保存当前挂载图片的 movable content：按页、阅读会话代际、
重试代际、拆页区域及实际内容解码键识别，移动既有图片 holder 和已准备的绘图结果，避免布局交接
重新进入异步加载的空白帧。几何、导航与已读能力仍由新布局重新建立，不随图片移动继承。
真实尺寸变化立即撤销旧几何能力；新组合生效前，仅允许仍属当前内容且已经画过的槽位继续绘图，
不引入未显示的伙伴页，也不报告新视口已读。重试／内容替换仍校验实际内容身份。
条目只随已挂载槽位存活，移出 pager、换章或退出阅读器时沿用既有 Compose 释放流程，
不建立章节级图片缓存，也不延长窗口快照的显示时间。

验证边界：

- `ReaderFullscreenFramePresentationTest` 覆盖进入、退出、关闭阅读器的绘制顺序及绘制失败清理。
- `ReaderFullscreenEntryPreviewTest` 通过真实 Compose／Skia 绘制与默认 controller 接线，
  检查快照先于原生放大存在、底层画面更换后仍保留原漫画像素，并在首帧完成后释放。
  它使用隐藏且不激活的窗口，不能单独证明前台合成器逐帧无黑屏。
- `ReaderFullscreenNativeGeometryTest` 使用不激活且由 DWM 隐藏的真实窗口，覆盖最多两块
  已连接屏幕、普通／最大化窗口各六次全屏往返，等待切换完成后比较客户区与实际原生 Canvas 的四条边；
  不能用仅创建但从未显示的 peer 代替该回归，因为它不执行同样的窗口尺寸事件链。
- `SharedDualPageNativeStablePresentationTest` 的全屏用例挂载真实 `ReaderViewport`，
  发送 F11／Escape，验证窗口调用与图片保留；单页／双页连续尺寸变化用例检查实际图片像素、
  页码与解码次数，并保留原有双页几何更新回归。自动布局用例推进真实稳定等待期，
  逐帧检查三次单／双页往返、图片 holder 与像素身份复用，以及退出后的槽位释放。
- `SharedDualPageDesktopWiringTest` 的尺寸交接用例检查旧几何仅能保留已经显示的当前内容，
  不显示新伙伴、不报告旧视口，且重试后旧内容与已关闭挂载仍被拒绝。
- 离屏测试的窗口是替身，不能证明 Windows 合成器的逐帧显示；原生体验结果单独记录，
  不将体验包构建通过当作无闪烁的视觉验收。

2026-10-29 定向验证：自动布局像素回归先在进入双页后的第 10 帧失败，修复后覆盖三次往返、
图片身份复用、实际翻页像素与退出释放；原生 presenter 的尺寸交接回归也完成红绿验证。
同轮另有三项既有失败，在修改前基线 `f6278704a5e8ebb102e687d6272cd32680dc1746` 的 production
代码中定向复现且失败一致：`AdaptiveReaderViewportTest.production reader resize...` 预期配对
`[2, 3]`、实际 `[1, 2]`；`SharedDualPageNativeStablePresentationTest.explicit crop...` 预期
两次已读报告、实际无报告；同类 `partner error and actual retry...` 在最初缩放后无漫画矩形。
它们未计入本次通过范围，未改写这些既有断言，也未将本次定向结果宣称为整个阅读器测试通过。
