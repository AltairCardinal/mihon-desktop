# Desktop 阅读器全屏切换

全屏入口沿用阅读器按钮、F11 和 Escape，由 `ReaderScreenModel` 保存阅读器状态，
`ReaderViewport` 将状态交给 `DesktopReaderFullscreenController`。切换不重建阅读会话，
不改变当前页、已解码图片或双页配对；真实视口改变仍须更新双页几何能力。

窗口操作留在 Desktop adapter 中：Android 的系统窗口 API 不能用于 AWT／Compose Desktop。
Windows 优先保留原生窗口与绘制表面的无边框实现；其他平台或 adapter 不可用时，
保留 Compose 的 `WindowPlacement` 路径和原窗口状态恢复逻辑。

进入、退出全屏及离开全屏阅读器时，控制器在事件队列中执行窗口变更，
并在恢复系统过渡效果前调用 `validate()` 和 `renderImmediately()`，
让最终客户区完成布局与绘制。此顺序沿用窗口启动时的处理方式，避免等待普通异步重绘。
绘制失败时仍在 `finally` 中恢复系统过渡效果；过期请求、已关闭或未创建的窗口不执行切换。

验证边界：

- `ReaderFullscreenFramePresentationTest` 覆盖进入、退出、关闭阅读器的绘制顺序及绘制失败清理。
- `SharedDualPageNativeStablePresentationTest` 的全屏用例挂载真实 `ReaderViewport`，
  发送 F11／Escape，验证窗口调用与图片保留；单页／双页连续尺寸变化用例检查实际图片像素、
  页码与解码次数，并保留原有双页几何更新回归。
- 离屏测试的窗口是替身，不能证明 Windows 合成器的逐帧显示；原生体验结果单独记录，
  不将体验包构建通过当作无闪烁的视觉验收。
