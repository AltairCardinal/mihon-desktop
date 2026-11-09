# Windows 首开 R2 实施与实验

日期 2026-11-09，实施起点 `539e297eec`。范围、依据与采用条件见 [R2 计划](../roadmap/2026-11-09-windows-reader-startup-r2.md)。本页逐步收口同一轮修复与一个性能候选，不代表正式发布或普通注册表后端性能。

## 早期控制事件修复

旧实现把轻量入口的所有早期输入统一等待页表 Loaded；页表慢时 F11 无响应，失败时不再执行。现在由原键鼠 handler 区分需要页表的导航，其他控件操作在完整入口挂载后立即执行；按事件索引消费一次，并用原章与 generation 阻止旧输入跨章重放。无额外输入的首图路径不进入该队列处理，不提前初始化全屏 adapter。

真实 Screen RED 为 `.gradle-coordinator/early-input-controls-red.xml`：页表被阻塞时首次 F11 的 `isFullscreen` 为 false。修复后同一 Screen 21 项、Fullscreen 20 项、KeyboardFocus 2 项通过；真实场景覆盖 F11/Space/Ctrl 缩放各自的页表成功和失败，以及关闭、换代和延后导航。根独立检查默认 handler 分支与队列生命周期，未发现阻塞。三个 Kotlin 文件全文件 ktlint 1.8.0 零违规，`git diff --check` 通过。

同批 `ReaderPageTurnPresentationTest` 50 项中48通过、2失败，为既有 `rapid taps ... dual=true`，调用 fixture.tap，不经过本次键鼠 handler；D5 报告已有对应双页快速点击失败记录。保留本次失败，不扩大修复，也不宣称所选总范围全绿。原命令/退出码见 `early-input-controls-focused.json/.log`，四类 XML 固定在 `.gradle-coordinator/early-input-controls-focused-xml/`。没有完整模块/全量测试。

## 性能候选

输入修复已提交 `5aed3286efebb44674cc5d576edd6695ba98d242`。性能候选仅在本轮准入的 pending 轻量视口首帧已经 apply 后注册一次 opening 内容需求。注册、撤销、Loaded 接收与目标核对使用同一 session lock；页表到达后沿现有 `rereadFirstCompositionState`/恢复规则解析目标，再复用私有无进度 settle helper 和原 scheduler。注册时页表已先到的竞态也支持；旧 token 的 dispose 不能撤销后来注册。

原并发3、forward4/back1保留，故这是单页入口优化，不是声称只读一张图。没有提前解码、隐藏 holder、进度、共享协议、缓存算法或双页入口改动。未注册、切入完整入口、设置不符、非法恢复、失败、旧章代及关闭沿原链；可选 resolver 的普通异常不阻止正常页表发布，CancellationException 仍重新抛出。根独立审查后以真实失败复验了异常路径。

真实 RED：`startup-r2-demand-red.xml` 证明旧实现没有在下一轮复杂视口 apply 前准备目标；`startup-r2-resolver-red.xml` 证明异常 resolver 会让外部仍为0页而非187页。最终 `startup-r2-resolver-green` 三类 Screen 26、SessionIntegration 28、StartupObservation 2，共56/56通过、零失败/错误/跳过；固定 XML 在 `.gradle-coordinator/startup-r2-resolver-green-xml/`。最后仅将过长的测试断言提取为同值 local，复用该行为证据。ktlint 修改行违规0，6条未改旧行诊断保留；diff空白检查通过。没有模块全量或Android验证。

采用与收益仍由最后一次集中副屏正反对照决定，不由内部等待区间推定。B复用产品源码 `06287cf3c3` 的 V1 包；候选包含输入修复，但无额外输入的首图路径未处理早期队列，比较中不触发该修复。两臂固定相同文件后端、17项配置、规范CBZ与恢复页；不能外推普通注册表后端的绝对耗时。准备时只读核对主屏scale1.5、副屏bounds=(3840,1296,1920,1080)/scale1，正式每份仍须核实际外窗完整位于副屏。
