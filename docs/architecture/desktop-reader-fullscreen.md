# Desktop 阅读器全屏切换

全屏入口沿用阅读器按钮、F11 和 Escape，由 `ReaderScreenModel` 保存阅读器状态，
`ReaderViewport` 将状态交给 `DesktopReaderFullscreenController`。切换不重建阅读会话，
不改变当前页、已解码图片或双页配对；真实视口改变仍须更新双页几何能力。

窗口操作留在 Desktop adapter 中：Android 的系统窗口 API 不能用于 AWT／Compose Desktop。
Windows 优先保留原生窗口与绘制表面的无边框实现；其他平台或 adapter 不可用时，
保留 Compose 的 `WindowPlacement` 路径和原窗口状态恢复逻辑。
无边框 adapter 必须同时更新 HWND 的物理边界和 AWT 的逻辑边界，退出时恢复两者；
仅调用 Win32 改变边界会让 Skiko 的 heavyweight Canvas 继续消费旧 AWT 尺寸。

Windows 进入前，adapter 在保存原窗口样式后对已经可见的 HWND 发送 `WM_SETREDRAW(FALSE)`，
让既有窗口内容保留到新画布和 D3D 帧准备完成。仅禁用 DWM 过渡动画不能阻止系统在尺寸变化期间
先显示旧缓冲区的拉伸版本。此消息会临时清除原生 `WS_VISIBLE`，但 AWT 窗口仍保持可见并继续渲染；
因此过渡中的 `SetWindowLong` 也必须保持该位清除，保存的退出样式则必须来自暂停之前。
下一事件周期的最终布局和 `renderImmediately()` 返回后，controller 调用 adapter 的
`completeTransition()`：恢复原生重绘、请求窗口及子窗口重画，再沿用既有 DWM flush 和过渡恢复。
此处理不创建快照窗口，不重投影漫画，不改启动窗口与普通退出全屏路径。

暂停资源由该 adapter 保存，只由最新过渡回调释放；原生操作、最终绘制或调度失败时同样释放，
旧回调不能提前释放新过渡。若进入尚未结束便请求退出，保留暂停直到退出画面完成。
资源关闭幂等；原本隐藏、期间隐藏、已销毁或已更换 HWND 的窗口不会被重新显示。
无法暂停时保留既有切换流程，不据此承诺该平台不会出现中间画面。
`WindowsFullscreenBounds` 的默认物理边界仍来自真实 Win32 monitor，逻辑边界在原生变更后读取 AWT；
可注入目标边界仅用于让真实 adapter 的合成回归窗口始终留在所有显示器之外。

Windows adapter 进入成功后，在同一事件处理周期先将根 `SkiaLayer.canvas` 的公开 heavyweight
子画布尺寸同步到当前客户区，再递归失效并验证窗口布局。Skiko 0.9.37.4 的 Direct3D
`SkiaLayer.reshape()` 会在 `doLayout()` 调整子画布之前立即重绘；只提前调用 `validate()`
仍会生成一帧“新客户区原点、旧画布尺寸”的漫画。先调整子画布可避免这次重绘消费旧尺寸。
此外，Compose 的 `ComposeContainer.setBounds()` 会先调整 SkiaLayer，再通知场景尺寸。
仅先同步 Canvas 仍会让首张全屏 PNG 按旧场景居中。因此在这一次同步布局期间临时包装原
renderDelegate：立即绘制入口先向已有父容器派发当前尺寸的 AWT resize 通知，再原样调用原 delegate。
通知只派发一次，不换画布、不重录图片；`finally` 恢复原 delegate，避免覆盖已有诊断观察器或留下 hook。
此处理仅用于成功的无边框 adapter 进入路径；退出、Compose fallback 和其他平台保持既有链路。

进入、退出全屏及离开全屏阅读器时，控制器在事件队列中执行窗口变更，
等待原生尺寸事件回到 AWT 后，在下一次事件处理周期调用 `invalidate()`、`validate()`
和 `renderImmediately()`，再恢复系统过渡效果。失效标记不能省略：有效容器的 `validate()`
不会重新布局。连续切换时仅由最新窗口变更完成重绘和恢复过渡，旧回调不覆盖新结果。
绘制失败时仍在 `finally` 中恢复系统过渡效果；过期请求、已关闭或未创建的窗口不执行切换。

进入全屏直接使用阅读器真实画布及其已保留的漫画，不再把旧窗口的整幅截图另外缩放到屏幕。
整窗截图会把漫画已有的平移再次缩放：例如向上平移后的漫画在过渡图中更靠上，真实画布接管时
又回到原位。图片生命周期已由阅读器自身管理，因此取消独立快照可避免这一次位置重投影，
同时保留原生尺寸事件之后的最终布局、绘制和 DWM flush 顺序。退出全屏沿用同一真实画布。

阅读器自动单／双页布局还有独立的稳定等待期（当前为 150ms），可能晚于窗口首帧完成。
`ReaderContent` 因此在两种 pager 之上保存当前挂载图片的 movable content：按页、阅读会话代际、
重试代际、拆页区域及实际内容解码键识别，移动既有图片 holder 和已准备的绘图结果，避免布局交接
重新进入异步加载的空白帧。几何、导航与已读能力仍由新布局重新建立，不随图片移动继承。
真实尺寸变化立即撤销旧几何能力；新组合生效前，仅允许仍属当前内容且已经画过的槽位继续绘图，
不引入未显示的伙伴页，也不报告新视口已读。重试／内容替换仍校验实际内容身份。
条目只随已挂载槽位存活，移出 pager、换章或退出阅读器时沿用既有 Compose 释放流程，
不建立章节级图片缓存。

验证边界：

- `ReaderFullscreenFramePresentationTest` 覆盖进入、退出、关闭阅读器的绘制顺序及绘制失败清理。
- `ReaderFullscreenCompositorTest` 使用真实 Compose／Skiko、真实无边框 adapter、原生 WGC 后端，
  在屏幕外且不激活的窗口上进行三次进入／退出。绘制中加入 45ms 延迟以跨越显示刷新，
  检查收到的全屏合成帧始终使用目标漫画位置，所有尺寸的帧都有漫画像素，完成后原生窗口仍可见。
  未修复时曾录到 Y=80→118 的回跳，修复后同一场景通过。运行前需先构建
  `scripts/build-reader-compositor.ps1`；该测试不读取桌面、不派发输入，WGC 不代表显示器扫描输出。
  `WindowsWindowRedrawNativeTest` 另覆盖可见状态恢复、重复关闭、隐藏和原生销毁后的资源清理。
- `ReaderFullscreenNativeImagePositionTest` 使用简化色块、阅读器平移变换与默认 controller，
  仅覆盖独立快照的位置差异；没有挂载真实阅读器，也没有覆盖整个过渡过程。
  它使用隐藏且不激活的窗口，不读取桌面屏幕，不能单独证明前台合成器的逐帧显示。
- `ReaderFullscreenNativeGeometryTest` 使用不激活且由 DWM 隐藏的真实窗口，覆盖最多两块
  已连接屏幕、普通／最大化窗口各六次全屏往返，等待切换完成后比较客户区与实际原生 Canvas 的四条边；
  不能用仅创建但从未显示的 peer 代替该回归，因为它不执行同样的窗口尺寸事件链。
- `SharedDualPageNativeStablePresentationTest` 的全屏用例挂载真实 `ReaderViewport`，
  发送 F11／Escape，验证窗口调用与图片保留；单页／双页连续尺寸变化用例检查实际图片像素、
  页码与解码次数，并保留原有双页几何更新回归。自动布局用例推进真实稳定等待期，
  逐帧检查三次单／双页往返、图片 holder 与像素身份复用，以及退出后的槽位释放。
- `SharedDualPageDesktopWiringTest` 的尺寸交接用例检查旧几何仅能保留已经显示的当前内容，
  不显示新伙伴、不报告旧视口，且重试后旧内容与已关闭挂载仍被拒绝。
- `ReaderRenderTraceNativeTest` 挂载真实阅读器并保存 PNG，覆盖进入过程中的每次原生客户区／
  绘制画布尺寸匹配，以及首张完整全屏 PNG 与稳定 PNG 的漫画像素中心一致；同时覆盖连续三次
  往返超过 180 帧仍完整记录，以及显式小预算耗尽后的 PARTIAL。它不观测系统最终呈现。
- 离屏测试的窗口是替身，不能证明 Windows 合成器的逐帧显示；原生体验结果单独记录，
  不将体验包构建通过当作无闪烁的视觉验收。

2026-10-29 定向验证：自动布局像素回归先在进入双页后的第 10 帧失败，修复后覆盖三次往返、
图片身份复用、实际翻页像素与退出释放；原生 presenter 的尺寸交接回归也完成红绿验证。
同轮另有三项既有失败，在修改前基线 `f6278704a5e8ebb102e687d6272cd32680dc1746` 的 production
代码中定向复现且失败一致：`AdaptiveReaderViewportTest.production reader resize...` 预期配对
`[2, 3]`、实际 `[1, 2]`；`SharedDualPageNativeStablePresentationTest.explicit crop...` 预期
两次已读报告、实际无报告；同类 `partner error and actual retry...` 在最初缩放后无漫画矩形。
它们未计入本次通过范围，未改写这些既有断言，也未将本次定向结果宣称为整个阅读器测试通过。

同日的位置回归在旧独立快照路径中测得漫画中心 `633`、真实全屏中心 `693`，先以此差异失败；
移除快照后，上移／居中／下移三种平移状态的首帧与稳定帧位置一致。32 项定向检查通过，
包括原生普通／最大化窗口在最多两块屏幕上各六次全屏往返的画布边界检查；未代替前台视觉验收。

用户随后确认 `ed03aa1061` 候选仍有进入全屏时上跳再恢复的现象，故该问题未关闭。
简化测试通过不足以支持修复结论。后续按[真实阅读器渲染取证](desktop-reader-render-trace.md)保留帧与事件，
明确应用绘制帧、几何样本与系统呈现的边界，不使用 Computer Use，也不以未复现作为修复证据。

后续实际记录与对应红绿回归见[进入全屏的旧画布帧取证](../evidence/desktop-reader-fullscreen-entry-2026-10-29.md)。
应用绘制阶段的回归通过仍不替代用户对上跳现象的确认。
