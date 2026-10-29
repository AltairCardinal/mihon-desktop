# 阅读器全屏渲染取证

用于调查实际阅读器的全屏瞬态：保存应用绘制帧、窗口几何、漫画绘制范围及状态切换。
默认关闭；不使用 Computer Use，不上传数据，也不改变全屏切换规则。
应用 Picture 和默认窗口 WGC 不读取桌面屏幕；显式显示输出区域取证的范围与限制见文末。
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

直接打开普通 EXE 不会携带上述参数。交付专用诊断包时，可在其 `app/Mihon Desktop.cfg` 的
`[JavaOptions]` 中添加 `java-options=-Dmihon.reader.renderTraceDir=<绝对输出目录>`，使直接运行
该 EXE 也启用采集；仅修改本次诊断包，不修改用户原有快捷方式。配置覆盖须在包清单中保留原始
artifact 摘要和配置文件前后摘要，更新实际 artifact 摘要，避免把已配置的包误报为未经修改的构建产物。
交付前通过该 EXE 的隔离、无界面实例核对运行中 JVM 的实际属性；只检查快捷方式内容不足以证明
用户打开的入口已启用。此检查仅证明开关生效，实际 PNG/几何链路仍由阅读器集成测试覆盖。

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
- 同一捕获时间窗内，额外记录根硬件 Canvas 的 AWT `PaintEvent` 为 `awt-canvas-paint`，包含
  事件编号、AWT 逻辑更新矩形、Canvas 背景色、`ignoreRepaint` 和背景擦除系统属性。
  仅监听当前阅读器的该画布，不监听输入；trace 关闭时移除 Toolkit 观察器。
  它用于对齐最终 GPU 绘制之后是否仍有 AWT 绘画请求，不代表已观察实际 GDI 擦除或显示器扫描；
  背景擦除属性也不能替代现有 peer 行为的证据。

## 资源和失败处理

每个阅读器会话最多请求 900 帧、排队像素 256 MiB、独立 PNG 512 MiB。原 180 帧上限在一次实际连续
切换中丢失了后 120 帧，因此提高帧记录上限；单次采集时间窗、内存和落盘限额不变。可用
`mihon.reader.renderTraceMaxFrames` 在 1–900 内进一步降低帧预算。排队像素限制不等于进程内存上限：
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

## 窗口合成帧补充

应用 Picture 正常、用户仍看到跳动时，用 `scripts/capture-reader-compositor.py` 补录单个 Mihon 窗口。
它复用现有 reader trace 的会话和 PID，核对候选源码、完整应用内容摘要、进程可执行文件和 HWND 所有者，
仅通过 Windows Graphics Capture `CreateForWindow` 取该窗口，不抓显示器／桌面，不发送输入，不改变窗口位置。
这是外部诊断设施，不进入产品依赖或全屏控制逻辑。Python 仅复用既有 Pillow；原生后端使用本机 Windows SDK。

```powershell
./scripts/build-reader-compositor.ps1
python scripts/tests/reader-compositor-capture-test.py -v
python scripts/capture-reader-compositor.py --candidate <本轮候选绝对目录> --expected-commit <候选源码完整SHA>
```

构建物位于 `app-desktop/artifacts/diagnostics/compositor-native/`；`backend.json` 保存源码和 EXE 摘要，
采集前核对。后台运行使用隐藏进程，先确认候选目录的 `compositor-watch.json` 为 ARMED，再运行同一 EXE，
打开漫画后稍等一秒再切换全屏。进入真正的窗口帧回调后状态才变为 CAPTURING；完整结束为 FINISHED，不完整为 PARTIAL，启动失败为 FAILED，
等候默认 20 分钟后 EXPIRED。默认只捕获一个新阅读器会话，最多 60 秒；窗口关闭时结束。

原生后端提前按窗口所在屏幕分配固定 capture frame pool，避免全屏过程中重建仍在使用的捕获缓冲。
纹理 readback 使用实际 RowPitch 按行复制到自有字节，异步回调持有 shared state，关闭时撤销回调并排空在途绘制。
数据通过有界帧协议送到 Python 编码，最多 1800 帧、128 MiB 排队像素、512 MiB PNG；这些限制独立于 Picture 取证，
不是进程总内存上限。单帧或固定捕获缓冲不得超过 64 MiB；移到更大屏幕而超过已有缓冲时明确失败，不保存裁剪帧。
完全相同的 BGRA 字节可复用 PNG，但每个接收帧保留单独记录。失败、资源超限、原生异常退出和短读均记 PARTIAL。

结果位于同一 reader trace 的 `compositor/`：`events.jsonl`、`window-*.png`、`native-stderr.log` 和 `manifest.json`。
`captureStage=windows-graphics-capture-window` 与 Picture 阶段独立，不能冒用 renderId。`systemRelative100ns` 是
Windows 提供的合成帧 QPC 时间，乘 100 得 qpcNanos；Windows HotSpot 21 的 `System.nanoTime()` 同样使用 QPC，
应与应用的 timeNanos／renderStartedNanos 对齐，不与 Skiko 的 renderClockNanos 或 Compose 帧时钟对齐。
窗口边界另存为 observedWindowAtReceive，带查询前后 QPC；它是接收时的观察，不能冒充合成帧发生时的窗口边界。

WGC 不保证每个显示刷新都有回调。RECORDED 只表示所有接收到的帧完整落盘，displayScanoutObserved 始终为 false；
它仍不能证明显示器最终扫描稳定。原有窗口防截屏策略不会被关闭；策略阻止捕获时明确报告失败。

验证使用不激活且位于所有显示器之外的自建分层窗口，提供真实不透明像素，并改变实际窗口位图尺寸。
覆盖真实 WGC 像素、原生行填充、尺寸变化、QPC 时钟、双重 HWND/PID 和 EXE 校验，以及无损复用、缓冲区所有权和资源失败。
普通离屏 GDI 窗口只得到空画面，未把它记作像素验证通过。试验中的 windows-capture 2.0.1 与 2.0.0 在尺寸变化时
发生 0xc0000005，故交付实现不使用该库；异常只发生在隔离采集进程，未据此判断 Mihon 自身崩溃或原跳动原因。
交付原生后端与落盘链路的 11 项 focused 检查通过，另用真实 AWT Frame 验证 owner/PID 选择接线；
窗口始终位于显示器外且不可激活。没有为这次外部诊断设施运行产品模块或仓库全量测试。

本批文件共同构成一条窗口取证链（原生获取、跨进程传输、落盘、候选绑定与失败回归），未扩大到修复 GPU 或 DWM 的猜测改动。
接口依据：[窗口捕获](https://learn.microsoft.com/en-us/windows/win32/api/windows.graphics.capture.interop/nf-windows-graphics-capture-interop-igraphicscaptureiteminterop-createforwindow)、
[合成帧时间](https://learn.microsoft.com/en-us/uwp/api/windows.graphics.capture.direct3d11captureframe.systemrelativetime)、
[HotSpot 21 Windows 时钟](https://github.com/openjdk/jdk21u/blob/master/src/hotspot/os/windows/os_windows.cpp)。

## 显式显示输出区域取证

窗口 WGC 可以继续交付正常的源窗口内容，不能据此排除用户看到整个阅读器区域闪白。
需要观察这一边界时，复用同一候选绑定、原生后端与有界落盘链路，加 `--output-region`：

```powershell
python scripts/capture-reader-compositor.py --candidate <候选绝对目录> --expected-commit <源码完整SHA> --output-region --capture-seconds 30
```

该模式通过 DXGI Desktop Duplication 获取阅读器所在输出，GPU 只复制启动时核验的阅读器客户区固定矩形，
CPU、管道与 PNG 不包含矩形外的像素。固定范围避免切换期间把“当前窗口几何”套到较早的显示帧上。
范围会在 `source` 和 manifest 的 `captureRegion=[物理屏幕x,y,width,height]` 中明确记录。
窗口短暂退出合成时，此范围可能包含后方画面；这正是与源窗口帧对照的目的，不能称为仅抓取源窗口内容。
不发送输入、不上传文件、不移动或激活窗口，也不拼接或保存整幅显示器画面。

仅接受精确 HWND/PID/EXE 绑定的可见、前台阅读器，其初始客户区必须完整位于同一块未旋转显示器。
不切换目标屏幕；用户隐藏、最小化、离开该窗口，或正常状态的客户区不再覆盖固定矩形时结束采集。
全屏原生 `WM_SETREDRAW` 暂停期间 `WS_VISIBLE` 会被清除，允许在其 `SysSetRedraw` 属性仍有效时
继续观察，但这一例外最多 500ms。阻塞等待显示帧后、GPU readback 前再次检查范围。
窗口防截屏策略仍生效；初始化失败、访问丢失、旋转／跨屏、超限与保护内容屏蔽均明确报告。

输出位于同一 reader 会话的 `output-region/`，与默认 `compositor/` 分开。
`captureStage=dxgi-output-duplication-reader-region`；帧时间来自 `LastPresentTime` 的 QPC，换算为
`lastPresentQpc100ns`／`qpcNanos`，可与应用 `timeNanos` 对照。它仍不是显示器物理扫描测量，
`displayScanoutObserved=false`；DXGI 报告累积多帧时记录 `CAPTURE_GAP` 并将结果标为 PARTIAL。
进程、保护策略和固定范围的退出另记 `capture-stopped`；ARMED 仅表示等候新阅读器会话，
CAPTURING 仍以第一张实际帧为准。默认等候 20 分钟，指定采集 30 秒或提前离开范围后结束。

PNG 写入前先识别相同像素，重复帧可引用尚在编码的 PNG，不占第二个编码槽；
其他帧仍受原有像素／磁盘／帧数预算约束，不靠扩大内存上限掩盖缺帧。
16 项 focused 检查覆盖原窗口 WGC、真正 GPU 子矩形复制／原生行填充、范围越界拒绝、
Python 到原生输出模式的真实接线，以及慢编码期间重复帧保留。
GPU 裁剪测试使用合成纹理，输出拒绝测试使用屏幕外自建窗口，均不读取桌面、不派发输入。
这些测试不代替用户前台复现，也不证明本机输出复制的实际像素采集已经成功。

接口依据：[Desktop Duplication](https://learn.microsoft.com/en-us/windows/win32/direct3ddxgi/desktop-dup-api)、
[按显示器 adapter 创建复制接口](https://learn.microsoft.com/en-us/windows/win32/api/dxgi1_2/nf-dxgi1_2-idxgioutput1-duplicateoutput)。
