# Desktop 阅读器首开诊断与无进展发布器

## 默认关闭的观测

仅 Test Mode 且环境变量 `MIHON_READER_STARTUP_OBSERVATION=1` 时，`ReaderTestModeController` 安装 `ReaderStartupObservation`。其线程时钟设施在 controller 准备期初始化；普通运行不创建事件、不读观测时钟、不安装 AWT listener。事件只存内存，容量默认 1024，满时保留最早事件并累计 dropped；GET `/test/reader/state` 随现有状态导出，退出移除自有窗口的鼠标监听。没有后台文件写入、图片重放或新控制接口。

AWT DOWN/UP 是自有 ComposeWindow 的 EDT 监听执行时刻；CHAPTER_CLICK 是正常详情章行回调进入、协程启动之前。它们不是操作系统原始输入时间。IO begin/end 跟随真实内容读取；DECODER_CALL_END 是 decoder 返回，RASTER_PIXELS_END 是同步像素读取返回；旧 DECODE 事件仅为 decode 调用前标记。Compose remember/apply、holder ready、prepare 与实际 Painter draw acknowledgement 分开记录，均不能替代外部可见画面或显示器扫描输出。

线程 CPU 累计值仅适用于同线程区间，Windows 量化可使短区间的 CPU 差大于墙钟差；不得将跨线程或嵌套区间相加为端到端耗时。字段缺席可能是调用分支未覆盖；dropped 非零时也不能声称全程完整。正式 C/D 性能对照关闭细观测，避免把诊断负担混入收益。

### R3：准备与 Ready 交接边界

默认关闭观测补充 `INITIAL_PROJECTION_BEGIN/END`、`RUNNER_WORK_TAKEN`、`PREPARE_BEGIN/END`、`ENCODED_STORE_RETURN`、`READY_PUBLISH_BEGIN/END` 与 `COMPOSE_READY_SEEN`。prepare 早退时可能没有 END，不将缺席事件解释为完成。store return 位于原子缓存提交及收尾返回之后；Ready 发布前后包围 session StateFlow 发布，不能把 copy end 至 store return 算作 Ready 后的界面等待。

session 分配进程内递增 `openingToken`，结合章、generation、目标页和 attempt 区分同进程再次打开；其 generation 仍可能重新为1。带 `contentIdentity` 的解码事件在观测开启时记录完整 decode key，包含 ref、provenance 和尺寸；关闭时不构造该字符串、不读诊断时钟或写文件。旧 copy、OPEN 等没有 token 的事件只能结合最新正常点击/OPEN 时间窗口及可核对的 ref 关联，不声称全部事件都能直接 join。

以下仅为已撤回候选的历史事件定义；现行production没有该运行时接续注册或collector。R3 候选的 `RUNTIME_READY_REGISTERED` 只证明真实 composition 注册生效；`RUNTIME_READY_SEEN` 是运行时消费合法 Ready，`RUNTIME_READY_LAUNCHED` 与 `RUNTIME_READY_REUSED` 分别区分本次启动原 holder 任务和幂等接用，撤销/拒绝另记。机制仍须与同目标实际 OPEN/解码和外部正确漫画核对；候选因冷态净收益未分辨已精确撤回，不表示体验达标。执行与最终裁决见 [R3 证据](../evidence/windows-reader-startup-r3-2026-11-10.md)。

共享 Desktop 图片内容读取 await 返回 null 时，先在原锁下复核关闭状态，再报告普通“内容不可用”失败，沿 region/presentation holder 的既有 Failed 反馈。真正的读取取消及关闭仍传播 CancellationException；缺失缓存不再冒充取消而使挂载 holder 一直 Loading。该错误分类不改变解码、共享内容租约或配对规则。

## Desktop 发布器的无进展终止

真实 187 页 CBZ 在固定位置、持续正常 Compose 帧且没有新 I/O、fact 或 sample revision 时，可反复启动没有结果的 pairing capability。若当前轮没有发布结果、没有活动任务及待消费完成项，完成本身不能成为下一轮的输入，否则持续争抢 ReaderScreenModel 生命周期锁。

此出口等待已有生产流的真实变化，不引入定时器、延迟或共享协议修改。模型在 publisher collect 的锁内增加输入 revision；规划时捕获 revision，完成时若已有后续输入到达，保留一次重臂，避免晚到 fact、重试、模式/内容或 viewport 需求变化被活动任务吞掉。既有 busy 状态实际退出的重臂、预算释放、取消清理和有界失败恢复保持原链路。该规则不把尚在运行或尚未消费的 capability 视为无进展，不改变共享配对判定、图像质量或资源预算。

定向验证包含真实 CBZ 的静止生产回归、短章节负控、当前真实双页 viewport 输入在旧任务终止窗口已被 collector 消费后的重臂竞态，以及缓存复用、邻页 header、重试/配置变化、实际资源退出与内容替换接线。收益由正常 UI 的首漫画外部采集裁决；内部不再空转不自动等于用户首开达到目标。当前原生验证限 Windows，其他平台未据此宣称性能结论。

## D5：独立于细观测的正常点击锚点

Test Mode 的正常详情章行回调在启动协程前记录 `detailChapterClicks`，最多保留最近64条。记录包括递增 sequence、chapterId、单调时钟和可空的 openIntentNanos；沿现有 `/test/reader/state` 导出，不同步落盘。普通运行不记录，也不额外读取此时钟。它与默认关闭的 CHAPTER_CLICK 细事件是两个不同用途的观测：正式对照两臂都有前者，关闭后者。

导航对象上的临时 sequence 不进入 equality、导航 key 或持久化。runtime 构建期间通过既有 probe.bind 关联同章的第一个 OPEN；取消而没有构建时保持未关联，测试 API 或其他入口不会借用上一次点击。分析必须按 chapterId 与 openIntentNanos 精确关联最新 OPEN，不能取同进程第一条点击。该起点仍是应用回调，排除了此前的系统输入处理；不得称原始输入送达或据此判断完整响应预算。

## D5：已撤回的初始内容准备候选

当前 production 仍在接受页表后等待 Compose 实际 viewport 提交，再通过普通 scheduler 准备当前页 encoded 内容。D5曾尝试在下载路由已确定时由初始物理页提前提供无进度调度需求；虽然真实行为RED/GREEN证明依赖解除，但单页反向顺序未重现收益，关闭匹配双页首次有可辨退化，候选及共享resolver已精确撤回。不能将实验设计当作现行架构，也不将此结果推定为所有提前准备方案无效。

再次研究前先读 [D5证据](../evidence/desktop-reader-d5-2026-11-06.md)，提供新的触发条件或反例。沿用资源预算、代次、真实viewport、图像质量和进度保护；注意既有 applySchedulePlanLocked 内的 runner.cancel 即使空集合也会 pump，不能假定调度都在session锁外。保留的新长期设施只有上节Test Mode点击锚点。

## D6：同序身份读取与本次下载判断

Desktop `ReaderScreenModel.ensureDualSession` 在同作品、同generation、同有序pageId列表时，按ordinal读取上一身份的content，并继续核对pageId/contentVersion；校验不符仍使用原NORMAL默认方向。其他页序沿用原查找fallback，内容替换、stamps、版本推进、方向事实、owner退役及进度协议不变。目的仅为减少稳定同序下的重复线性查询，不建立共享/跨代缓存，不改配对认证算法，也不因同序而跳过合法状态更新。

`toReaderChapterRefs` 在一次章节投影内每章只求一次downloaded值，供isDownloaded和过滤共用；source0仍短路为已下载且不调用远端判断。下次打开重新判断，不将Boolean当成已验证内容位置，不跨打开缓存位置或失败；异常继续由既有打开链处理。该局部复用通过真实detail readerRequest与ReaderNavigator过滤/下一章接线验证。

真实模型计数夹具中8页读取44→16、187页17,765→374；远端每章下载回调2→1，source0为0。采用限局部工作量减少，未证实稳定首图毫秒收益；8份Windows外部样本、三模式画面/往返、条件项不实施及限制见[D6结果](../evidence/desktop-reader-d6-2026-11-06.md)。无新增探针或等待协议，不以本批代替完整模式/平台验收。

## D7：摄入、投影与实际视口的观测边界

默认关闭的细观测新增DUAL_IDENTITY与DUAL_PROJECTION、VIEWPORT_SCHEDULE、页表发布及content pin边界，联合既有SESSION_ACCEPT入口/锁内/结束事件分析。事件关联当前generation及可用pageId；viewport结束沿用锁内执行时的generation，避免锁外换代后把旧操作错记为新请求。记录仍有界、只在内存、不同步落盘；正式A/B关闭细观测。inclusive区间、归档物理准备、显示OPEN与Compose apply分列，不能相加或把前两者当成同一次读取。

当前生产订阅仍属于Screen的LaunchedEffect，物理内容准备仍依赖真实viewport。D7曾把session元数据订阅移交model/runtime IO scope，真实夹具证明可解除未挂载时model仍Loading的依赖；但正常单页first的外部F区间重叠，双页对照亦未满足采用门槛，行为候选及其并发配套已撤回。不能将该实验的owned job或原子发布当成现行架构，也不据此宣称现行主干发生了实验并发问题。

再次研究订阅迁移，须同时覆盖取消/旧代和非空session与derived identity的逐emission一致性；原实现依赖EDT串行的中间状态不能直接移入IO线程。真实factory、StateFlow观察及外部反例见[D7结果](../evidence/desktop-reader-d7-2026-11-07.md)。不以更早OPEN或单次warm改善替代首次正确漫画、双页与反向验证。

## D8：同锁恢复入口的准备复用

初始化和页表摄入在model生命周期锁内准备当前SharedDualPageSession后，可将本次owner直接传给私有恢复helper，避免进入恢复时再次构建身份/投影。preparedOwner不保存在字段中、不跨调用/异步边界复用；helper仍检查dispose、owner对象当前性及关闭状态。独立重试和保存冲突恢复不传该参数，沿原入口重新ensure当前owner。

恢复请求建立后的RESTORING发布及成功/失败回调发布仍为必要操作，不能随重复准备一起删除。内容/代次/恢复请求的原有失效与迟到处理保持。真实模型/coordinator回归确认两个入口准备2→1、held阶段投影3→2、读取仍1且保存布局正确；这是局部工作量优化，没有证明外部首开毫秒收益。证据及验证边界见[D8结果](../evidence/desktop-reader-d8-2026-11-07.md)。

## 2026-11-09：持续轻量首屏与模型交接

已确认下载的固定 RTL/LTR 单页（非 AUTO、双页或拆页）由 `ReaderFirstComposition` 在同一 runtime 捕获入口资格并持续观察 session。页表尚未发布时保留加载反馈；Loaded 后复用既有恢复解析和设置核对，挂载真实 Queued holder 并提交当前页需求。旧章、旧代、页表失败或恢复位置不可用转入原完整阅读器反馈，不依赖两个即时采样是否恰好遇到 Loaded。

目标实际 draw 只解锁完整 model 创建。创建时即在当前 Screen composition 接上 `acceptSessionState` 桥；配对恢复/初始投影未准备好时，轻量视口仍唯一持有当前正确图片。视觉交接另外核对章、代次、目标页、attempt、encoded ref、内容状态和配对准备，并通过同一 movable-content registry 移交同 holder/lease。父级重组不重置 runtime 捕获的资格；活动 pinch/pan 或拖动在 release/cancel 前保留原 owner。此桥仍属于现有 Screen composition，不恢复 D7 的 IO 订阅迁移，也不拆开 raw session 与派生呈现发布。

早期控件、键盘、滚轮及相对翻页可提前创建 model。轻量入口捕获的队列沿既有 handler 按事件一次消费；原 handler 的非导航分支立即执行，只有依赖页表的导航延至 Loaded。全屏、显隐控件或缩放不因页表失败而丢失，已消费事件不再次执行；队列绑定原章与代次，退出或换代丢弃旧意图。无 model 退出仍由 entry 释放 runtime，有 model 后由 model 释放，不双重关闭。

静态整页无 split/source bounds/裁边时，Ready 组合直接 retain 原位图；组合 lease 同时在 forgotten 和 abandoned 释放。捕获的 draw/currentPixels 回调检查 composition lease 及实际资产身份，不能借同 identity 的新资产确认旧图。裁边、拆页和动画继续走异步 transform 与原 prepare/draw 协议。

默认关闭的细事件补充 `FIRST_COMPOSITION_WAIT_PAGE_LIST`、`FIRST_COMPOSITION_TARGET_ADMITTED`、`FIRST_COMPOSITION_MODEL_UNLOCK`、`FIRST_COMPOSITION_VISUAL_HANDOFF`、`TRANSFORM_DIRECT`/`TRANSFORM_ASYNC`。`HOLDER_READY` 在实际 Ready 赋值后记录；适用事件带可选 `attemptGeneration`，并沿用真实页、章和 generation。无页表的等待事件仅有章身份，不能伪造目标页。模型创建和绘图确认均不代表物理显示；本批未作端到端性能采用承诺，外部 B/C 裁决另记。

### R2：已提交轻量等待的普通内容需求实验

本节为已撤回实验记录，现行产品不注册此需求。两份真实新进程中页表已在初始投影期间到达，没有pending分支提交，故候选没有实际触发；不外推到所有用户/冷态。保留实现及反例，后续须先覆盖首次已Loaded的新条件，不能原样重启。

仅该固定单页 opening 的 pending 分支在 DisposableEffect 注册一次需求；不在未提交 composition 内调度。session 在同锁 Loaded 接收或已 Loaded 注册竞态中重新解析完整 opening 身份和目标，复用无进度 settle 与现有 scheduler，保留原预取/并发预算。onDispose 同步撤销尚未触发的 token，activate/close 清除；旧 token 不能删除新注册。可选 resolver 的普通异常拒绝本次优化而继续正常页表发布，取消仍沿原语义传播。

`FIRST_COMPOSITION_DEMAND_ARMED` 带真实章/代次，`FIRST_COMPOSITION_DEMAND_TRIGGERED` 带实际目标页/代次；两者只用于诊断，不代表图片已解码或呈现。真实视口继续持有原 holder 和呈现/进度权威。D5全路由提前准备的反例仍有效，此处仅在P1稳定轻量单页的新条件下实验，采用结果见[R2证据](../evidence/windows-reader-startup-r2-2026-11-09.md)。
