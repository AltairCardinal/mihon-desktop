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

验证边界：

- `ReaderFullscreenFramePresentationTest` 覆盖进入、退出、关闭阅读器的绘制顺序及绘制失败清理。
- `ReaderFullscreenNativeGeometryTest` 使用不激活且由 DWM 隐藏的真实窗口，覆盖最多两块
  已连接屏幕、普通／最大化窗口各六次全屏往返，等待切换完成后比较客户区与实际原生 Canvas 的四条边；
  不能用仅创建但从未显示的 peer 代替该回归，因为它不执行同样的窗口尺寸事件链。
- `SharedDualPageNativeStablePresentationTest` 的全屏用例挂载真实 `ReaderViewport`，
  发送 F11／Escape，验证窗口调用与图片保留；单页／双页连续尺寸变化用例检查实际图片像素、
  页码与解码次数，并保留原有双页几何更新回归。
- 离屏测试的窗口是替身，不能证明 Windows 合成器的逐帧显示；原生体验结果单独记录，
  不将体验包构建通过当作无闪烁的视觉验收。
