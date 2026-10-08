# Mihon Desktop 自动化测试指南

双端原生 UI 审阅窗口的同步面板试点已[封存](archive/NATIVE_UI_REVIEW_EXPLORATION_2026-09-29.md)；历史启动方法保留在[试点指南](NATIVE_UI_REVIEW.md)，不作为全应用 UI 审阅的默认流程。

验证范围与退出条件以仓库 [AGENTS.md](../../AGENTS.md) 为准：默认按本次行为和风险完成日常体验验证；只有用户明确指定发布里程碑时才执行完整发布矩阵。TDD、真实 production 接线和高风险专项仍在本批完成。

```mermaid
flowchart TD
    A[确定本次行为、风险和受影响平台] --> B{用户明确要求发布里程碑?}
    B -- 否，默认 --> C[行为变更 focused 红绿重构\n文案和文档直接核对]
    C --> D[受影响集成与格式检查\n高风险独立审查]
    D --> E[需要体验包时 preview 或 Android debug]
    E --> F[只读预检精确候选、设备和会话]
    F --> G{前置可用?}
    G -- 是 --> H[受影响用户路径原生验收]
    G -- 尚待准备或观察 --> R[执行已授权的有界平台准备\nMac 统一入口亮屏并定位精确候选]
    R --> S{目标就绪?}
    S -- 是 --> H
    S -- 否 --> I[按实际失败层级保留证据\n继续其他独立平台]
    G -- 已确认不可自动恢复 --> I
    H --> J[交付真人体验并提交\n本轮范围通过即停止]
    I --> J
    B -- 是 --> K[固定提交范围、风险与平台矩阵\n首次完整验证和正式产物验收]
    K --> L{发现相关失败?}
    L -- 是 --> M[保留基线，修复并定向补验\n只有具体缺口才申请扩大]
    M --> N[必要项全部关闭才声明发布就绪]
    L -- 否 --> N
```

对比旧流程：roadmap 勾选完不再自动触发完整发布矩阵；日常体验包不递增正式版本；已通过且未受影响的证据可以复用；平台阻塞不会要求其他平台重测。详细审计与两个历史会话的证据见[流程实施与试点证据](../evidence/testing-workflow-rollout-2026-10-06.md)。

## 快速开始

### 阅读器翻页动画回归

在「设置 → 阅读器 → 翻页动画」中切换开关。单页、双页和左右阅读方向下，
鼠标点击、键盘、滚轮与页码跳转共用此设置；常规设置里的既有开关使用相同偏好值。
条漫保留连续滚动方式。

分页视图保留前后各一个视口的图片持有，使已就绪相邻页在关闭动画时直接切换，
避免重新挂载图片期间露出背景。相邻视口预热必须等到当前章节可见页实际绘制后才开始，
避免额外解码抢占首帧；章节代际切换后重新等待绘制确认。此窗口复用现有解码与释放链路，不保证尚未下载、
未解码或加载失败的页面瞬间显示；这些页面继续提供加载或重试反馈。
快速连续请求必须以最后一次请求为准，鼠标操作中断动画后不得残留页码反馈屏蔽。
分页点击监听挂在稳定的 Pager 容器上，以便按下和松开跨越动画帧时仍接收同一次手势。
外部跳页尚在动画中时，首次用户点击以先前停稳页为基准；随后连续点击以最新用户目标为基准。
拖动或新的外部跳页会清除该用户目标。此规则同时适用于单页、双页及左右阅读方向。

分页背景点击监听必须尊重子控件在 Main 阶段消费的指针事件；点击图片错误状态中的 Retry 只重试对应页，不同时触发背景翻页。回归须派发实际按钮矩形内的公开 Press／Release，断言真实尝试代际增长、当前位置不变及原图恢复；直接调用 Semantics 动作不能覆盖父子手势竞争。Scene 指针验证仍不代替正式窗口的鼠标验收。

`ReaderPageTurnPresentationTest` 挂载真实 ReaderContent 与图片 owner，以离屏帧检查
动画、无黑帧、设置实时生效、跨帧点击与快速请求；mounted production 测试同时约束相邻窗口
之外不得提前解码、可见页不得重复解码。

`ReaderCriticalPathProductionTest` 与 `ReaderIoProductionWiringTest` 保护首帧前仅当前页
打开和解码的约束。离屏夹具应注入真实偏好、有界推进渲染与后台 I/O，并释放每帧图片；
不能依赖不推进虚拟时间的循环触发 `withTimeout`，也不能通过放宽首帧计数接纳预热回归。

定向验证示例：

```powershell
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
python scripts/gradle-coordinator.py run --key reader-page-turn -- .\gradlew.bat :app-desktop:jvmTest --tests mihon.desktop.ui.reader.ReaderPageTurnPresentationTest
```

### 阅读器基础图片绘制观察

当前观察接线覆盖双页分页视图中的基础图片，单页与条漫未接入；不能用观察开关成功推断所有阅读模式均有记录。

包含新观察接口的 Test Mode 候选可用 `POST /test/reader/draw_observation/start?capacity=120` 开始一次有界捕获，通过 `GET /test/reader/state` 读取 `slotDrawObservation`，最后用 `POST /test/reader/draw_observation/stop` 关闭。端口和进程须为本次已核对的候选；实际章节、图片与 tracker 仍从真实产品入口进入。HTTP 只负责观察控制和读取，不替代原生键鼠或用户阅读动作。字段和失败边界见 [API Reference](API_REFERENCE.md)。

原 BitmapPainter 仍执行实际绘制；观察读取 Image 裁剪记录层中的原命令、矩阵和附着坐标，不按 fit 算法重建预期矩形，不制造 prepare／draw 回执，不持有图片 lease。基础图片命令的根坐标是未裁剪几何；缩放／平移后的像素比对必须在实际槽和视口可见交集中取原图样本，不能把正常裁剪判为产品失败。启用造成的正常 Painter 重新安装不计为匹配性能数据。

`base-image-draw-command` 记录基础图片实际命令；`compose-frame-geometry` 在显式捕获时用公共帧时钟读取最近已应用的 Image 布局，关联最近真实命令、资产与挂载，不制造新绘制回执。其 `coordinatePhase` 明确为 `last-applied-layout-at-compose-clock-callback`；回调发生在本 tick 布局工作之前，不能将读到的上一布局称为本 tick 新 draw 或 GPU 呈现。region tile、系统屏幕每帧与 GPU 继续需要相应证据。`frameId` 是可复用的 display ticket 标识，`sequence` 是观察顺序。容量耗尽的 `dropped`、坐标域不支持、旧候选404或未挂载409都必须保留，不能改成通过。Windows Computer Use、Mac 原生生命周期与真实发布身份要求维持。

这项定向接线验证使用 `SharedDualPageOriginalImagesProductionTest` 的真实 ZIP／Factory／Skia／HTTP 场景；只选择本次观察行为方法和具体守卫，不因此运行整类、完整模块、正式构建或发布矩阵。关闭观察和普通启动继续走原绘制链。

### 阅读器前台读取的显式控制

仅包含新接口的 Test Mode 候选支持 `/test/reader/io_control/arm`、`state` 及 `/{id}/release`、`/{id}/fail`。控制绑定当前实际 reader 的内容 owner、章节、资产代际、零基源页及 full／prefix 范围；不会模拟图片 Ready、配对事实、绘制或保存。先确认真实产品入口、实际 owner 与章节，再按 [API Reference](API_REFERENCE.md) 设置最长30秒的有界规则。`HOLDING` 及 `enteredNanos` 才证明真实读取已进入门闩，`ARMED` 不证明读取发生；full 挂起不同时挂起 prefix。

释放后继续原 encoded reader；显式失败或门闩自身超时产生 IOException，进入现有图片错误／重试路径。调用方作用域取消仍是取消，不转为加载错误；关闭 owner 只取消自身规则。重试通过原按钮及真实 attempt 递增验收，不用 HTTP 写页状态。离屏 Semantics 按钮动作只能验证动作接线，不能替代原生鼠标命中。普通启动绑定 None，不安装这些控制；最多16个活动规则、32条历史，未进入规则到期在状态／匹配／新增检查时回收，不建立后台定时服务。

### 构建与测试

按仓库 [AGENTS.md](../../AGENTS.md) 为当前行为选 focused 测试。以下命令运行完整 Desktop JVM 模块与 Robot 客户端测试，只在用户明确选择的发布里程碑或经授权的具体扩大验证中使用：

```bash
./gradlew :app-desktop:jvmTest :test-desktop:test
```

日常体验构建使用 `preview`；完整发布构建只在用户明确指定的里程碑执行：

```bash
./scripts/build-desktop.sh preview
./scripts/build-desktop.sh
```

`build-only` 仅供用户明确指定的发布里程碑收口使用，并且要求已有有效的完整 Desktop JVM 证据。证据可以覆盖当前源码，也可以是完整测试基线加上后续差异的相关回归、集成补验及影响范围说明；基线中的相关失败必须关闭，未执行范围不能算通过。使用组合证据时应保留首次完整测试结果和补验依据，不能写成“最终源码已全量通过”。

满足上述条件且只需避免正式收口构建重复运行测试时，显式使用：

```bash
./scripts/build-desktop.sh build-only
```

`build-only` 仍会分配新的 BUILD 版本、构建正式未打包应用、执行生产扩展安装与运行版本验收，
但跳过脚本内的 `:app-desktop:jvmTest`。默认、`feature`、`stage`、`msi` 和 `evidence` 模式仍会运行测试；
它不代替本批要求的 focused 测试，也不授权提前进行发布收口。

Windows 默认先在 Gradle 临时目录生成未打包应用并完成运行验收，然后将完整应用发布到持久目录；
不会生成 MSI。构建成功后可直接运行、并应写入完成报告的最终 EXE 为：

```text
app-desktop/artifacts/windows/Mihon-Desktop-0.STAGE.FEATURE.BUILD.GIT_HASH-unpacked/Mihon Desktop.exe
```

构建日志会输出该次构建的准确绝对路径：

```text
Final unpacked EXE: D:\...\app-desktop\artifacts\windows\Mihon-Desktop-<完整版本>-unpacked\Mihon Desktop.exe
```

完成报告必须复制这条输出中的实际路径并在报告前确认文件存在，不能根据模板猜测版本号。
`app-desktop/tmp/` 仅供内部构建、运行验收和 Test Mode 使用，不能作为完成报告中的构建地址。
最终目录中的 launcher 必须与同级 `app/`、`runtime/` 一起保留。Windows 构建成功后还会生成可搬运的完整 ZIP：

```text
app-desktop/artifacts/windows/Mihon-Desktop-0.STAGE.FEATURE.BUILD.GIT_HASH-windows.zip
app-desktop/artifacts/windows/Mihon-Desktop-0.STAGE.FEATURE.BUILD.GIT_HASH-windows.zip.sha256
```

对外分发和人工安装可使用 ZIP；直接运行验收可使用上述最终未打包 EXE。脚本会在成功前检查压缩包内
同时包含 launcher、应用文件和 Java runtime，并生成 SHA-256。

构建脚本会先启动临时构建 EXE，确认窗口标题中的运行版本与本轮
`0.STAGE.FEATURE.BUILD.GIT_HASH` 完全一致，再发布最终目录。只有发布时才显式执行
`./scripts/build-desktop.sh msi`；MSI 不能替代未打包版本的开发验收。

### 日常体验包

日常体验使用隔离预览入口：

```bash
./scripts/build-desktop.sh preview
```

该模式复用 production 打包链，不跑产品测试、不修改 AppVersion，也不覆盖既有产物。Windows 产物位于
`app-desktop/artifacts/preview/windows/<id>/`，macOS `.app` 与 manifest 位于
`app-desktop/artifacts/preview/macos/<id>/`。使用 `preview-manifest.json` 和构建日志记录的真实候选路径；Windows 完成报告仍应链接该轮输出的 `Final unpacked EXE:`，并确认文件存在。manifest 的 `productionRuntime=PASS` 仅表示 Windows 既有扩展运行时检查，`nativeInteraction=NOT_RUN` 仍需外部原生验收；macOS 预览只表示应用包构建完成，`productionRuntime` 与 `nativeInteraction` 都保持 `NOT_RUN`，须另行执行 LaunchServices/原生验收。体验构建不能代替完整发布矩阵。

### 候选与设备只读预检

`acceptance-preflight.py` 只检查给定候选、设备或会话，不启动应用、不安装、不发送输入：

```powershell
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONDONTWRITEBYTECODE = '1'
python scripts/acceptance-preflight.py --platform windows --artifact 'D:\path\Mihon Desktop.exe'
python scripts/acceptance-preflight.py --platform macos --artifact '/path/Mihon Desktop.app'
python scripts/acceptance-preflight.py --platform android --artifact 'D:\path\candidate.apk' --adb 'D:\Android\Sdk\platform-tools\adb.exe' --serial '<本轮确认的设备>'
```

输出为 JSON，只读范围标记为 `read-only-preflight`，含平台、总状态和逐项检查的层级、状态、原因、下一步及事实；`PASS` 退出码为 0，其他状态退出码为 2。Android 可附 `--package` 指定预期前台包（这不验证 APK 签名），也可用 `--min-free-gib` 提供调用者选择的磁盘余量阈值；未指定时不设默认阈值。该预检不验证签名或完整 provenance，不执行 Computer Use，也不能把 `nativeInteraction=NOT_RUN` 改成通过。

Windows 的 `native-tool=NOT_RUN` 是 CLI 无法代探当前会话 Computer Use 的边界，不能解释成未安装该能力。接下来按 AGENTS 初始化实际 Node 工具，枚举并只选择本轮 EXE 路径对应的窗口。截图只有桌面背景、无法观察应用内容或激活失败时，不沿用猜测坐标点击；重新选择并有界恢复一次，仍失败则记录具体 `TOOL_FAIL` 并继续其他独立项。正常 HTTP 状态或应用内部 `active=true` 不能替代外部原生/视觉证据，也不据此更改产品的安全/隐私设置。

Windows 输入桌面、显示拓扑和 Computer Use 是独立检查。WTS 会话 Active、`OpenInputDesktop` 为 Default、窗口 visible，均不证明当前存在可捕获的显示输出。`display-topology` 只读调用 `GetDisplayConfigBufferSizes(QDC_ONLY_ACTIVE_PATHS)`：成功但活动路径容量为 0 时记录 `NOT_RUN` 与 `NO_ACTIVE_DISPLAY_PATH`，引导核对显示连接和实际捕获；调用失败保留错误码，不能把初始化为 0 的输出解释为无显示器。容量为正只表示当时的元数据，不能直接宣称原生输入或截图通过；本 API 返回的是缓冲容量，可能大于实际路径数，见[微软 API 说明](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-getdisplayconfigbuffersizes)。

真实 Computer Use 同时报 `CreateForMonitor 0x80070057` / 无法激活时，结合本次显示拓扑、候选和会话证据诊断，不仅凭 `WinDisc` 名称、历史崩溃日志或某一个标志推断原因。已授权验收可做一次短时 `SetThreadExecutionState(ES_DISPLAY_REQUIRED)` 亮屏请求并释放，再复核显示拓扑；这是[临时电源请求](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-setthreadexecutionstate)，不会替用户解锁或修复物理断开。未恢复时停止相同条件的捕获/激活重试，记录需要恢复有效显示输出的具体前置；不反复重建应用、换 renderer、全量测试或擅自安装驱动/修改安全设置。条件恢复后重新选择真实窗口，再补原生路径和关停，不复用陈旧坐标或旧窗口对象。

### Computer Use 集中操作与归还桌面

目标是减少用户桌面被占用的次数和时长。需要原生交互的验收项仍在原有最小充分范围内执行；只读 API、无界面测试和构建不因此改为 Computer Use。Windows、通过本机窗口操作的远程 Mac、模拟器等共享桌面操作遵守同一安排；直接在远端运行的原生验收也应及时释放对应桌面。

| 时机 | 执行要求 |
|---|---|
| 进入前 | 先完成实现、构建、数据准备、可离线执行的预检、审查与工具文档读取，准备报告和提交内容。列出本轮必要原生路径和停止条件即可，复用已有计划，不另建任务台账。将当前已就绪的兼容场景集中安排在本轮末尾；需要独立 profile 的场景仍分别隔离，存在依赖的场景不强行合并，也不等待其他不就绪任务。 |
| 开始时 | 简短告知将操作的应用、验收范围和预计占用时间，已有授权不重复申请。同一桌面只有一个主代理或子代理派发输入，交接前由原执行者停止控制。使用本次候选和最新返回的窗口对象，不沿用历史坐标。 |
| 操作中 | 只做必要原生动作和即时结果核对，保持“观察 → 一次动作 → 刷新观察”。集中的是连续操作时段，不是无观察的输入串。短暂动画等待按平台指南有界观察；原生证据仍与 HTTP/离屏证据分开。 |
| 需要离开界面工作时 | 编译、修复代码、长下载或网络等待、深入诊断、等待用户答复、写长报告之前，先执行下面的收尾。当前 Windows 版本未找到受支持的中途释放接口，须随即用 `final` 结束本轮，说明未完成项，后续工作留到下一轮；不能在 reset 后继续长时间运行并宣称已归还桌面。 |
| 用户接管或停止时 | 立即停止后续输入和自动激活，不抢回焦点，不把用户接管当成普通工具故障执行重试。仅做不抢占桌面的精确收尾，记录未完成项；需用户明确恢复后才继续输入。 |
| 完成、失败或中断时 | 所有退出路径都执行收尾；脚本在 `finally` 中安排已有的精确清理，人工工具步骤也遵守同一顺序。强制中止来不及清理时，下次恢复先核对本次记录的实例并收尾，再安排新操作。 |

收尾顺序：

1. 停止本批后续输入、截图、自动激活及自行安排的轮询，不让异步 GUI 操作在交接后继续运行。
2. 使用当前工具文档明确提供且已验证的结束/释放方式；当前 Windows 的边界与替代流程见下节。通过已有非交互关停入口清理本次拥有的测试实例、辅助进程和临时电源请求，核对精确 PID、路径、profile 与端口。Windows 沿用 `/test/shutdown`，Mac 沿用统一 runner 的清理流程，Android 只处理本次创建的实例。正常关停失败只记录具体残留，不全局终止同名应用或共享工具进程，也不为收尾重新抢占用户桌面。
3. 用户要求留作体验的应用保持打开并交给用户，停止继续控制；记录哪些实例已退出、哪些有意保留。保留应用不等于保留 Computer Use 控制。
4. 立即说明已停止输入、清理结果及残留问题。当前 Windows 仅做简短证据落盘和已准备好的必要提交，随后直接输出 `final` 结束本轮；其余平台也须先有实际释放依据，才能继续离线工作。没有释放依据时不能只凭停止发出工具调用、重置内核或关闭应用宣称已完全退出。

#### Windows 退出边界与本轮末尾收口

2026-10-07 核对本机 Computer Use `26.803.41515`、`@oai/sky 0.7.6`：公开 `sky` 不暴露退出方法。源码中的内部客户端虽有原型方法 `close()`，可信服务代理只导出自有方法，不能将内部方法当成公开 API 调用，也不得杜撰 `sky.stop()` 或自行访问内部管道。官方[说明](https://learn.chatgpt.com/docs/computer-use#windows-foreground-use)确认 Windows 使用前台桌面；官方页面没有给出本插件的 agent 主动退出 API。

**同一轮现场对照结论：`node_repl.js_reset` 没有让控制提示提前消失。** 本次 kernel/trusted-worker 已退出、测试应用随后也已正常关停，但用户观察到提示直到根会话最终回复出现才消失。进程清理通过不等于主动释放通过；详见[本次验收记录](../evidence/computer-use-session-release-2026-10-07.md)。当前观察支持将结束本轮作为退出边界，不足以断言宿主内部 native pipe 或键鼠拦截机制。

当前版本的日常流程：

1. Computer Use 前完成可独立完成的代码、构建、focused 验证、审查和资料读取；准备简短报告及提交内容。将精确关停需要的候选、profile、端口和归属信息保存在 Node 会话之外。不要提前导入 `sky` 后等待其他任务。
2. 连续完成已就绪的必要原生操作。最后一次观察后停止输入与截图，仅做必要关停、简短证据落盘和本批提交，立即输出 `final`。不在这时再展开长报告、搜索、审查或等待代理；这些准备应在进入前完成。
3. 若原生验收失败或需要重新编译、深入修复，记录真实失败和未完成项，精确收尾并结束本轮；下一轮再继续。不得为了维持同一轮开发而让用户长时间保留控制提示，也不能为了结束而宣称未完成工作通过。
4. `node_repl.js_reset`（本机工具名 `mcp__node_repl__js_reset`）仅可用于有需要的执行端清理或工具恢复，不是每批必跑的释放验证。使用时先保存必要结果、确认本任务独立会话归属及无待完成操作，避免误清其他工作的绑定；不能以 reset 成功代替宿主控制释放，也不反复 reset、杀共享进程或重新导入 `sky`“检查退出”。

不要把子代理结束当成根会话控制释放：现有公开文档没有此保证，本项目也未实测通过。内部 `end_turn` 协议和轮次结束回调不是公开主动退出 API，不自行调用私有协议或修改宿主状态。版本更新后若出现受支持的释放接口，应以同一轮前后对照验证：保留测试应用、主会话继续运行，确认提示提前消失后再更新流程；不把这项版本核查变成每次验收都需用户确认的门禁。

### Windows 原生小场景与正常关停

显示条件恢复后，使用新的 profile 和当前空闲端口续验；既有实例及失败记录保持独立。先按上节集中安排原生操作及收尾，再执行以下顺序：

执行上下文也须单独确认：CLI 沙箱可能将应用启动到 `CodexSandboxDesktop-*`，与 Computer Use 所在的交互桌面不同。正常输入桌面、有效显示路径和 visible 窗口不能证明二者一致；无法枚举精确候选时只读核对本次进程及窗口所属桌面，保留具体缺口，不改权限或绕开隔离。流程迁移后由用户在原 Codex 应用会话启动开发，不以另起 CLI 会话替代。

| 步骤 | 操作与通过依据 |
|---|---|
| 核对候选及条件 | 核对实际 EXE、launcher 哈希、preview manifest 的源码/差异指纹；记录当次显示连接条件。正的显示容量只是元数据，实际截图另验。 |
| 隔离启动 | 确认 profile 尚不存在、HTTP/声明端口空闲，以 `Start-Process -WindowStyle Hidden` 启动包装器，参数包括 `--test-mode`、精确 `--test-profile`、`--test-http-port`，需要声明 JMX 时附该参数；真实 GUI 由应用创建，原生场景不加 `--headless`。 |
| 绑定身份 | 从只读 `/test/sync/ui` 的 PID 核对实际 EXE/命令行/profile、launcher 父子关系及 HTTP 监听者；不接续未知旧服务。 |
| 选择与观察 | 在实际 Node REPL 导入 `@oai/sky`，读取当前插件 SKILL、guidance/api/confirmations。`list_windows` 按精确候选路径筛选到唯一返回对象，再用其 id/app 调用 `get_window` 和 `get_window_state`；不重造窗口句柄，不沿用上轮对象。必须看到实际应用内容。 |
| 一次动作再观察 | 从当次截图选取既有入口坐标，或从当次原生可访问性树选索引；只用 `sky.click` 等公开 API。动作后立即刷新并观察；返回前核对本轮实际前台 PID，用 `sky.press_key` 执行真实 Escape，再观察稳定关闭状态。 |
| 精确关停 | 重新核对本轮 PID/EXE/profile/HTTP 归属，仅对其本地 `/test/shutdown` 发一次 POST，记录响应并等待 runtime 与 launcher 自然退出；不全局终止同名进程。需要独立复验时另用新 profile/端口重复。 |

动画会让动作后的即时截图仍保留旧画面，或显示面板正在进入/退出。此时保持本次动作已经派发的事实，同一动作最多补两次观察；仍未稳定则记录结果未确认并停止该项，不重复输入或无限等待。布局未稳定时不复用旧截图 ID、坐标或索引。只读业务状态可辅助判断已经打开/关闭，但不能替代实际截图或真实输入。

2026-10-07 已在物理显示器连接时，用两个全新 profile 完成书架截图 → 同步入口单击 → 同步面板稳定截图 → 单次 Escape → 书架稳定截图及精确关停。首轮坐标与窗口对象只是该次观察结果，后续仍须重新选取；物理关屏、仅欺骗器/虚拟显示条件仍未验证。preview manifest 的原生层保持 `NOT_RUN`，通过证据单列，详见[双轮记录](../evidence/testing-workflow-rollout-2026-10-06.md#windows-物理显示器连接后的双轮收口)。

### Android 模拟器的日常验收

日常默认使用任务专属模拟器；只有真实硬件、厂商行为或无法在模拟器复现的风险需要时才补真机。使用已有 SDK/system image 和独立 userdata，先检查资源、空闲端口，再通过 `adb -s <serial> emu avd name` 核对本次 AVD；不复用或清空其他实例。

APK 继续走 `scripts/build-android.py debug` 或已有候选的 `verify` / `install --serial`，同一有效候选重复验收不重新构建。启动实际 APK 声明的正常 MainActivity，不把多入口产生的系统选择器误报为安装失败。API 36 的 `mWakefulness=Awake` 与已知 `mInteractive` 形状可分别提供电源观察，未知状态保留未确认。

输入前及读取 hierarchy 前后，复用 `scripts/android-sync-native-acceptance.py` 的 `Device.guard()` 核对当前设备、未锁屏和目标包前台。从当次唯一、启用、实际 clickable 节点取得 bounds；点击后重新读取结果并观察截图。该脚本自带的是全新、无旧授权 Debug 实例的同步导航小场景；双页、正式候选或其他功能继续使用各自的真实行为契约，不把同步导航通过代签其验收。

基础通路可选正常欢迎页 Next → 存储步骤 → 系统 Back。各步骤共用 `Welcome!` 标题，应以 `Select a folder` / `Storage guide` 与 System/Light/Dark 主题控件的出现和消失判断，不按标题是否改变判失败，也不为该场景授予存储或账号权限。脚本只读检查与实际原生结果分层记录。

收尾再次核对 AVD 名称，仅关闭本次 serial，确认进程退出和 serial 消失。独立复验使用另一个新 AVD；Android 无窗口模式仍有自己的真实 framebuffer，不以 Windows 物理显示器状态判断模拟器截图能力。当前 DP02 已有 Android 正式 72 样本及剩余边界保持，不因本次迁移重跑。

### macOS 原生与无界面验收入口

Mac 原生验收使用下文统一入口 `scripts/mac-acceptance.py`。即使通过 SSH 执行，也由 LaunchServices 启动精确隔离应用，不直接执行应用包内 launcher。只读预检中的待亮屏/待目标核对属于统一入口的后续动作，不是要求用户介入的最终结论。

无界面模式仅适合 HTTP 状态/API 测试：

```bash
open -n -W -a '/absolute/path/to/validated/Mihon Desktop.app' --args \
  --test-mode --test-profile=/absolute/path/to/dedicated-mihon-test-profile \
  --test-http-port=8080 --headless
```

### macOS 桌面会话的安全存储验收

验收正式应用的 Keychain 读写时，通过 macOS 应用启动服务启动已核对的 `.app`，并使用独立 profile。
SSH 中直接执行 `Contents/MacOS/Mihon Desktop` 的结果须记录为该启动上下文的证据；若安全存储失败，
再核对桌面应用启动路径，不能仅凭 SSH 的默认钥匙串状态断言用户桌面钥匙串不可用。
不要修改钥匙串设置或收集用户密码来绕过失败。

下例将 `MIHON_ACCEPTANCE_APP` 替换为本轮构建日志 `Final macOS app:` 的实际绝对路径；
先确认 HTTP/JMX 端口空闲，旧 Test Mode 服务存在时停止本次启动，而非复用旧服务。

```bash
MIHON_ACCEPTANCE_APP='/absolute/path/to/validated/Mihon Desktop.app'
MIHON_ACCEPTANCE_PROFILE="${TMPDIR%/}/mihon-keychain-acceptance-$(uuidgen)"
open -n -W -a "$MIHON_ACCEPTANCE_APP" --args \
  --test-mode --test-profile="$MIHON_ACCEPTANCE_PROFILE" \
  --test-http-port=49163 --test-jmx-port=49164 --headless
```

验收原生窗口时去掉 `--headless`，并单独核对本次应用进程的窗口元数据或取得用户现场确认。
启动命令返回成功、HTTP health 正常及同步 state 的 `visible=true` 都不能证明窗口已呈现；
其中 `visible` 只表示产品面板状态。原生验收入口按核对后的精确 PID 激活并检查实际前台，
避免同 bundle 的其他实例被误选。窗口元数据检查只核对目标进程的窗口存在性、屏幕列表和尺寸，不读取屏幕像素。
等待现场检查期间保持本次窗口打开；未收到反馈的窗口/键盘/视觉项目不记为通过。

`open -W` 等待应用退出，其 PID 是启动包装器，不能当作 Mihon 的 PID。通过本地 HTTP、显式绕过代理，
核对该实例的 health、production 同步面板打开/设置/关闭，再调用已有 `/test/sync/probe/write`。
保留返回的虚构 probe ID；正常 shutdown 后，复用同一 profile 再启动并调用
`/test/sync/probe/verify/{id}`，必须完成跨进程读回和删除。仅调用保留前缀的测试接口，不读取真实授权或空间秘密。
每次完成后调用 `/test/shutdown`，等待包装器退出并核对本次应用进程已退出；异常时只处理本次精确实例。
若有系统访问提示，由值守用户核对程序并决定授权；没有提示也不能代替真实读写断言。
`--headless` 的证据仅覆盖 HTTP/production controller/系统安全存储，不代表原生窗口、键盘或视觉验收。

### macOS 同步面板原生交互自动化

先阅读 [Mac 验收经验与统一入口](MACOS_ACCEPTANCE.md)。本轮正式应用必须包含只读 `GET /test/sync/ui` 接口。
统一入口在 Mac 本机或已有 SSH 会话中执行，使用全新隔离 profile，使同步面板初始关闭且未连接；不需要先手动启动或置前台：

```bash
python3 scripts/mac-acceptance.py \
  --app "$MIHON_ACCEPTANCE_APP" \
  --profile "$MIHON_ACCEPTANCE_PROFILE" \
  --http-port 49163 --jmx-port 49164 \
  --output '/absolute/path/to/mac-acceptance.json'
```

入口完成一次有界亮屏复核、LaunchServices 启动及精确实例绑定，再调用既有 `mac-sync-native-acceptance.py` 场景。预检、统一入口和该原生脚本共用 `mac_acceptance_session.py`；息屏、锁定字段缺失、SSH 连接本身不单独判成无法执行 GUI。亮屏后仍有明确锁定信号时停止输入；缺失字段保留为未知，通过目标前台、窗口、权限和命中继续判断。
脚本核对实际应用 PID、应用包路径、profile、窗口激活与控件坐标，并在原生操作前重新检查会话及目标。
CoreGraphics 窗口矩形仅用于确认目标窗口存在；Dock 等窗口可能报告覆盖全屏的矩形，不能单凭矩形顺序推导鼠标命中。
外部工具通过系统辅助功能的坐标命中接口只读取目标 PID，确认属于本次应用后才发送 CoreGraphics HID 鼠标事件；键盘事件发送给核对后的应用 PID。
它通过只读接口验证鼠标打开同步面板、Tab/Shift+Tab 完整正反回环、Escape 关闭与同步入口还焦、Enter/Space 重开。
HTTP 控制动作不能代替这些原生事件。同一 AWT 窗口中的底层 Compose owner 可能在弹层挂载后保留工具栏局部 Focus 标记。
面板挂载时只核对面板作用域，卸载后核对工具栏；同时要求 `ownerFocused` 和实际 `focusedWindow`，不把背景标记算作当前焦点。
脚本不读取屏幕像素，不登录 GitHub，不创建空间或读取输入内容；只验未登录 MAIN 场景，不覆盖密码页视觉或真实远端同步。
若恢复后仍锁定、系统拒绝原生输入或辅助功能命中权限，或场景发生变化，记录具体层级、原始证据及下一步，不概括为“SSH 不能操作图形界面”，不更改权限绕过验证。
统一入口只关停本次已验证身份的实例，并核对实际应用 PID 与 `open -W` 包装器退出；临时防息屏辅助进程随任务释放。关停未完成保留为失败，不静默强制结束应用。需要留给用户体验的包另按日常体验流程交付。

### 隔离验收配置

在个人电脑验收时始终显式传 `--test-mode --test-profile=<绝对目录>`。Windows 使用本轮正式未打包 EXE，
参数相同；不要仅修改 `user.home` 或指定 JDK 内部 `FileSystemPreferencesFactory`，前者不能隔离
Windows APPDATA/注册表，后者不保证在 macOS 发布运行时可用。

- 首次使用不存在或空目录；程序写入 `.mihon-test-profile` 标记，后续可复用以验证冷启动持久化。
- 作者身份正式运行验收可使用 `authors_state`、`author_resolve`、`author_add_aliases`、
  `author_set_display_name`、`author_set_frequency`，参数见 `API_REFERENCE.md` 的作者身份验收动作。
  `author_resolve` 仅解析现有漫画的真实署名。固定离线 `author_sync_fixture` 必须在显式隔离
  profile 中运行，不能对普通用户配置执行；它验证生产 inbox/projector/journal，不能代替真实
  远端同步验收。重启复验继续使用同一 profile，并重新读取当前 revision 后再修改。
  非空且无标记、根目录、普通 home、符号链接路径被拒绝。不要将日常数据目录伪装成测试目录。
- 启动入口在 crash handler、实例选举和 DI 之前选择 profile。数据库、缓存、日志、扩展、下载默认目录
  及历史 `user.home` 路径均隔离；Java Preferences 全局切换到该 profile 的可持久化后端，涵盖旧偏好和扩展设置。
  偏好已提前初始化时拒绝启动，不退回普通用户后端。普通启动及未传 profile 的历史 Test Mode 行为不变。
- Windows 凭据仍通过 DPAPI 加密，密文保存到隔离偏好；macOS Keychain/Linux Secret Service 使用 profile
  路径派生的专用 service 名，保留生产安全存储实现。不会注册或覆盖系统 URI handler。
- 这是默认状态隔离，不是恶意扩展沙箱。测试主动传入的导入、导出或存储路径仍须位于专用目录，勿登录真实账号。
  一个 profile 只供一个应用 owner 使用；不要在运行期间手动编辑 profile 文件。删除 profile 文件不会自动清除
  OS 安全存储中的测试凭据，应先在该 profile 内正常退出测试账号/关闭测试应用锁。
- `--headless` 只验收 HTTP/状态，不替代真实 Reader Compose 窗口验收；Test Mode 自身不提供桌面截图 API。

## 测试分层

| 层级 | 位置 | 目标 |
|---|---|---|
| JVM 单元测试 | `app-desktop/src/test/kotlin` | 业务逻辑、导航契约、HTTP parser、状态模型 |
| 桌面自动化 API | `app-desktop/src/main/kotlin/mihon/desktop/test` | 测试模式 HTTP server 与状态回读 |
| Robot 客户端 | `test-desktop/src/main/kotlin` | 面向场景的测试 DSL |
| Robot 测试 | `test-desktop/src/test/kotlin` | 客户端序列化、Robot API、场景 smoke |

## 编写规则

- 禁止“成功或失败都算通过”的断言。
- HTTP/API 变更必须覆盖成功、空数据、错误状态和 malformed body。
- 导航变更必须验证 Tab 与 Screen 类型，以及 pending 状态不会互相覆盖。
- Reader API 必须验证 UI 状态与 `/test/reader/state` 一致。
- Test Mode 自身不提供桌面截图 API，也不得通过其接口读取桌面像素。视觉问题可由 Compose 离屏测试覆盖；外部 Computer Use 与 Test Mode 的边界及工具预检遵循 [AGENTS.md](../../AGENTS.md)。

## 常用命令

```bash
# 全部桌面 JVM 测试
./gradlew :app-desktop:jvmTest

# Robot 客户端测试
./gradlew :test-desktop:test

# 指定测试类
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.test.navigation.TestNavigationControllerTest"

# 冒烟测试脚本
./scripts/desktop-smoke-test.sh
```

## 测试模式 API

基础地址：

```text
http://localhost:8080/test
```

高频端点：

- `GET /health`：测试服务健康检查
- `GET /state`：应用状态
- `POST /navigate/{screen}`：导航
- `POST /action/{action}`：执行动作
- `GET /reader/state`：阅读器状态
- `POST /reader/next_page`：下一页
- `POST /reader/prev_page`：上一页
- `POST /reader/close`：关闭阅读器
- `POST /reset`：重置测试状态

完整字段见 [API_REFERENCE.md](./API_REFERENCE.md)。

## 最终对齐固定 EXE Runner

本轮构建已产出固定的 Windows 未打包应用后，运行：

```bash
./scripts/desktop-final-parity-test.sh
```

Runner 固定使用 `app-desktop/tmp/mihon-dist/main/app/Mihon Desktop/Mihon Desktop.exe`，并要求 `./scripts/build-desktop.sh evidence` 生成的 Task151 provenance sidecar。启动前会复用既有 provenance verifier 同时核对当前已提交 product source identity 和完整未打包应用哈希；EXE、sidecar 缺失或任一身份不匹配都会 fail-closed，不以 mtime 猜测 freshness。有效产物默认以带真实窗口的 `--test-mode` 启动，必须运行在可创建 Compose 窗口的 Windows 图形桌面或 macOS Aqua 会话中；`--headless` 不会创建 Reader Compose、Navigator 或 ScreenModel 生命周期，只适合 HTTP 控制面测试，不能作为 `FIRST_PAGE_PRESENTED`、关闭弹栈或 production resource disposal 的最终证据。若 `/test/health` 在启动前已响应则拒绝覆盖旧实例，启动后还会同时确认 health 与本次 PID 存活，并在成功、失败或超时时关闭本次启动的精确进程。

Reader 场景依次执行 downloaded directory、downloaded CBZ、local archive、online 与 partial download 五条 production 路径，验证真实页面 I/O、decode、首帧请求上限，以及 `closeRequested → productionClosed` 的两阶段关闭。partial fixture 由 production `DesktopDownloadManager` 实际提交若干页后打开 Reader：已提交当前页必须恰好走一次 probe/open/copy 且图片网络为 0，附近预取不能越过已提交边界，所有下载 I/O 都不得发生在 manager/index/coordinator/lifecycle 锁内。场景主体失败后仍会请求关闭，但清理失败不得覆盖原始首帧、I/O 或 decode 失败；场景主体成功时，关闭未获 production 确认仍使验收失败。

`test-desktop` 客户端通过 `MIHON_FINAL_PARITY_SUMMARY_FILE` 写入汇总。Runner 将它与既有 `test-mode-coverage-inventory.json` 对比，逐项输出 family 和 permanent protection，并要求：

```text
Families: 13/13
Permanent protections: 5/5
Capabilities: 64/64 unmapped=0
```

产物错误会给出固定路径和 `evidence` 构建命令；启动错误会区分旧 health 占用、本次进程提前退出与超时，并给出进程或启动日志。provenance、health command 和 test command override 仅用于隔离 runner fixture，正常验收不得用它们替换真实 verifier、固定路径或默认 `test-desktop` client。


## Windows 默认自适应阅读模式

入口：设置 → 阅读器 → 默认；或漫画详情／阅读器设置 → 阅读模式 → 默认。
“跟随全局设置”单独表示继承全局方向与单双页设置；阅读器选择漫画模式不会改写全局阅读方向。
全新偏好采用默认模式，已保存的手动方向保持原值；仅保存过单双页的旧配置保留手动 RTL 布局。
显式保存的 DEFAULT 不受历史单双页值影响。Android 阅读器本轮不变。

默认模式复用现有 RTL 单页／双页 presentation，以实际阅读内容 viewport 的宽高比判断。
首次有效尺寸或主动选择默认时，比例 ≥ 1.35 为双页，否则单页；后续单页在 ≥ 1.35 进入双页，
双页在 ≤ 1.25 退出。跨阈值后的目标须连续保持 150 ms；同一目标的连续 resize 不重置计时，
回到当前布局区间则取消待切换。无效尺寸取消待切换并保持布局；离开自动模式、销毁阅读器时取消计时。
共享策略位于 domain 的 `AdaptiveReaderLayout`，当前只有 Desktop adapter 接入。
临时工具栏和弹窗属于 overlay，不改变内容 viewport；图片比例变化不触发自动布局。

设置面板显示“默认 · 单页／双页”及说明，并禁用手动双页开关。双页配对、横向跨页图和章节末尾
继续按现有 presentation 处理，因此双页布局不保证每个视口都显示两张图。
双页当前页统一取逻辑阅读顺序的第一张：RTL 为右页，LTR 为左页；封面唯一页仍在左槽，当前页为封面。
单页进入双页时定位包含原页的配对，并将当前页归一到该配对的第一张；返回单页显示归一后的当前页。
底栏“调整跨页”原子更新配对边界与定位目标，例如 [1, 2] → [2, 3] → [1, 2]，不会停在新增边界的单页。
封面不调整，已有横向跨页与末尾唯一页仍按 presentation 处理。自动模式切换保留强制单页配对设置。
布局调整的 settled 回调只更新显示状态，不因新增伴页推进阅读进度；当前位置归一与进度抑制分别处理。
单页下自动匹配清理、跨页图检测等元数据更新不清除待完成的布局保护。用户跳页、选中其他显示单元或翻到不包含保护页的视口后，
恢复正常进度上报。双页 reporter 在显式定位请求后重新确认已显示页，配对往返不依赖数字页索引变化。

Desktop 自动标记保存于 viewerFlags 第 34 位，低 8 位仍为 Android RTL 值 2；显式方向覆盖清除自动标记，
继承全局同时清除 Desktop 双页覆盖位。Android 尚未接入自动策略；跨端使用本轮数据时仍以 RTL 解释。

回归证据使用共享 `AdaptiveReaderLayoutContractTest`、Desktop `DefaultReaderModeTest`、
真实 `ReaderContent` 离屏挂载的 `AdaptiveReaderViewportTest`、真实阅读器与底栏挂载的
`DualPageCurrentPageWiringTest`（默认／手动 RTL／LTR、连续调整、封面、后续翻页进度），
以及 `ReaderPageTurnPresentationTest` 的动画与快速翻页回归、设置搜索和漫画详情 persistence 测试。
手工验收：选择默认，拖动内容比例跨过 1.35／1.25，确认方向、单双页、当前页与阅读进度；
打开／关闭设置和工具栏不切换；选择手动方向后拉伸窗口不再自动切换；重开漫画仍保留选择。
