# Desktop 阅读器全屏切换

全屏入口沿用阅读器按钮、F11 和 Escape，由 `ReaderScreenModel` 保存阅读器状态，
`ReaderViewport` 将状态交给 `DesktopReaderFullscreenController`。切换不重建阅读会话，
不改变当前页、已解码图片或双页配对；真实视口改变仍须更新双页几何能力。

窗口操作留在 Desktop adapter 中：Android 的系统窗口 API 不能用于 AWT／Compose Desktop。
Windows 优先保留原生窗口与绘制表面的无边框实现；其他平台或 adapter 不可用时，
保留 Compose 的 `WindowPlacement` 路径和原窗口状态恢复逻辑。
无边框 adapter 必须同时更新 HWND 的物理边界和 AWT 的逻辑边界，退出时恢复两者；
仅调用 Win32 改变边界会让 Skiko 的 heavyweight Canvas 继续消费旧 AWT 尺寸。

Windows 进入、退出前，adapter 对已经可见的 HWND 发送 `WM_SETREDRAW(FALSE)`；
进入时先保存原窗口样式、WINDOWPLACEMENT、物理窗口边界和 AWT 逻辑边界，
避免直接呈现尺寸尚未同步的画布。仅禁用 DWM 过渡动画不能阻止系统在尺寸变化期间
先显示旧缓冲区的拉伸版本。此消息会临时清除原生 `WS_VISIBLE`，但 AWT 窗口仍保持可见并继续渲染；
因此过渡中的 `SetWindowLong` 也必须保持该位清除，保存的退出样式则必须来自暂停之前。
下一事件周期的最终布局和 `renderImmediately()` 返回后，controller 调用 adapter 的
`completeTransition()`：恢复原生重绘，仅更新窗口边框，禁止再次擦背景和重画已完成的 GPU 子画布，
再沿用既有 DWM flush 和过渡恢复。
原生暂停仍不足以保持显示输出：实际 DXGI 取证已录到暂停期间整个客户区变白，虽然源窗口 WGC 正常。
因此真实可见的 Windows 阅读器在暂停前还会保留一次已完成的 Skia Picture，作为独立过渡帧；
它覆盖新旧物理窗口范围的并集，图像按原 Canvas 的物理屏幕原点绘制，不缩放、不重新居中或重投影平移。
只保留这一张图，连续反向请求复用它，最新完成回调才释放。

过渡帧复用既有 Skia screenshot，使用独立的 Win32 分层位图窗口呈现，不以主 HWND 为 native owner，
也不交给 Swing／Java2D 重画。owned 窗口会跟随源窗口退出合成，源窗口自身的 WGC 正常不能证明它仍在显示。
先在不可见状态下用 `UpdateLayeredWindow()` 一次性准备完整位图，再以不可激活、不接收鼠标的方式显示、
flush，随后才暂停主 HWND。按物理像素 1:1 复制到旧 Canvas 的屏幕原点；显示后不再修改位图或缩放窗口。
窗口过程只验证 WM_PAINT、拒绝 WM_ERASEBKGND 擦背景，避免空白默认窗口覆盖已准备像素。
主画布最终绘制后先恢复其原生状态，再完成既有 DWM flush，最后调用 adapter 的
`releaseTransitionFrame()` 移除过渡帧；绘制、恢复或 flush 失败时也必须在 finally 中释放。
不改变启动窗口、Compose fallback 或漫画 holder 的生命周期。

原生表面使用专用短生命周期消息线程，创建、消息处理、销毁均在同一线程；
DIB／DC 只用于上传，上传后在 finally 中恢复选中对象并释放，不随阅读会话累积。
无 native owner 的表面仅在过渡中短暂置顶；线程每约 5ms 检查源 HWND、前台、防截屏和可见状态，
原生 `WM_SETREDRAW` 期间允许 `SysSetRedraw` 标识的临时不可见状态。
离开前台、窗口隐藏／销毁、保护状态变化后结束；正常进入消息循环后最多保留 2 秒，
避免完成回调缺失时继续盖住桌面。准备等待上限 2 秒，主动关闭会等候所属线程销毁 HWND。
失败回退既有切换流程；原生错误写入日志，不把回退当作无闪烁成功。

该保留仅在可见、未被 DWM 隐藏的窗口上启用；非活动窗口若覆盖真实显示器则不显示过渡帧。
受防截屏保护的窗口不复制到新表面，继续原有切换路径；检查失败也跳过。
单张图片及过渡范围分别最多 64 MiB，无法取得 Picture、超限或准备失败时回退既有流程，
不据此承诺这些回退场景无白闪。图片随过渡结束释放，不写磁盘，不读取桌面。

退出不能在暂停后立即调用 `SetWindowPlacement()`：它会把原生可见状态重新打开，提前暴露仍按
全屏尺寸绘制的内容。adapter 先用不带显示标记的 `SetWindowPos()` 恢复保存的物理边界，
再同步 AWT 逻辑边界，让既有最终布局／绘制完成；`completeTransition()` 才恢复原
WINDOWPLACEMENT（包括最大化状态和普通窗口位置），最后释放重绘暂停。
若此时又请求进入，保留并复用最初的窗口状态，不把尚未完成的恢复阶段当成新的窗口基线；
只有最新过渡完成才恢复状态和呈现。完成恢复失败时仍在 `finally` 中释放暂停。
如果窗口在过渡期间已被隐藏，跳过会将其重新显示的 WINDOWPLACEMENT 恢复，仍释放暂停；
用户的隐藏操作优先于尚未完成的呈现回调。

最终绘制前，controller 把根 SkiaLayer 的实际 Canvas 交给 adapter，按真实 HWND 客户区核对原生
子画布的位置和尺寸。AWT 可能已报告目标逻辑矩形，但快速边框变化留下的 native child 仍有偏移，
仅重复相同的 `setBounds()` 不会修正它。Windows adapter 用 `MapWindowPoints()` 映射到子窗口父坐标，
仅在不匹配时调用不激活、不改变 Z 序的 `SetWindowPos()` 对齐。
恢复 WINDOWPLACEMENT 后再核对一次，处理最大化／工作区边界在 DPI 换算中的像素取整差异。
Canvas 引用只保留到本次完成回调，成功或失败均清除，不新增长期画布状态。

几何变更前同时保存并临时开启根 Canvas 的 `ignoreRepaint`，让尺寸变化期间的 AWT/GDI 重画
不会排在最终 GPU 帧之后清除它。手动完成最终绘制并校正原生边界后，用 `ValidateRect()`
标记这块实际已由 GPU 覆盖的区域，再恢复窗口显示；最后在 `finally` 中恢复原 `ignoreRepaint`。
连续反向切换只保存第一次的原值，由最新完成回调恢复。窗口本身仍更新非客户区边框，
不改变全局 AWT 属性，也不禁止正常阅读时的重绘。

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
- `ReaderFullscreenRetainedFrameTest` 用真实 controller／adapter 和屏幕外、不激活的窗口，
  在进入与退出的暂停中通过窗口 WGC 读取独立过渡帧，检查漫画存在、物理高度不变、背景未变白、
  原屏幕位置只允许原生 DPI 栅格舍入（150% 下最多 1 像素），完成后 native HWND 已销毁。
  额外让源窗口在暂停中离开合成，检查过渡 HWND 的 DWM cloaked 状态仍为零；
  检查所有收到的过渡帧都有漫画、无白块，而不只检查首张。
  另检查防截屏及初始 DWM 隐藏状态不会创建新表面，以及超时、隐藏、保护改变后的原生销毁。
  这些检查只使用自建屏幕外窗口和窗口 WGC，不读取桌面显示输出、不激活窗口。
- `ReaderFullscreenCompositorTest` 使用真实 Compose／Skiko、真实无边框 adapter、原生 WGC 后端，
  在屏幕外且不激活的窗口上进行三次进入／退出。绘制中加入 45ms 延迟以跨越显示刷新，
  两个方向都检查收到的目标尺寸合成帧使用正确漫画位置，退出位置以最初真实窗口帧为基线；
  所有尺寸的帧都有漫画像素，完成后原生窗口仍可见。
  未修复时曾录到 Y=80→118 的回跳，修复后同一场景通过。运行前需先构建
  `scripts/build-reader-compositor.ps1`；该测试不读取桌面、不派发输入，WGC 不代表显示器扫描输出。
  `WindowsWindowRedrawNativeTest` 另覆盖可见状态恢复、重复关闭、隐藏和原生销毁后的资源清理。
  另检查单次恢复和完整进出完成后没有新的硬件 Canvas AWT PaintEvent；红／黑受控场景
  逐帧检查大面积白像素、漫画存在、位置稳定，以及 `ignoreRepaint` 原值恢复。
- `ReaderFullscreenNativeImagePositionTest` 使用简化色块、阅读器平移变换与默认 controller，
  仅覆盖独立快照的位置差异；没有挂载真实阅读器，也没有覆盖整个过渡过程。
  它使用隐藏且不激活的窗口，不读取桌面屏幕，不能单独证明前台合成器的逐帧显示。
- `ReaderFullscreenNativeGeometryTest` 使用不激活且由 DWM 隐藏的真实窗口，覆盖最多两块
  已连接屏幕、普通／最大化窗口各六次全屏往返，等待切换完成后比较客户区与实际原生 Canvas 的四条边；
  不能用仅创建但从未显示的 peer 代替该回归，因为它不执行同样的窗口尺寸事件链。
  另在每种状态／屏幕上让一次新进入排在退出完成回调之前，检查最终窗口普通位置和最大化状态
  与最初的原生 WINDOWPLACEMENT 一致，避免延迟恢复被快速切换覆盖。
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
