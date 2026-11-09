# Windows 首开 R2 实施与实验

日期 2026-11-09，实施起点 `539e297eec`。范围、依据与采用条件见 [R2 计划](../roadmap/2026-11-09-windows-reader-startup-r2.md)。本页逐步收口同一轮修复与一个性能候选，不代表正式发布或普通注册表后端性能。

最终裁决：保留输入修复，撤回性能候选。本轮结束；两份真实内部采样均未命中新注册分支，因此停止尚未开始的外部对照，未确认新增性能收益。

## 早期控制事件修复

旧实现把轻量入口的所有早期输入统一等待页表 Loaded；页表慢时 F11 无响应，失败时不再执行。现在由原键鼠 handler 区分需要页表的导航，其他控件操作在完整入口挂载后立即执行；按事件索引消费一次，并用原章与 generation 阻止旧输入跨章重放。无额外输入的首图路径不进入该队列处理，不提前初始化全屏 adapter。

真实 Screen RED 为 `.gradle-coordinator/early-input-controls-red.xml`：页表被阻塞时首次 F11 的 `isFullscreen` 为 false。修复后同一 Screen 21 项、Fullscreen 20 项、KeyboardFocus 2 项通过；真实场景覆盖 F11/Space/Ctrl 缩放各自的页表成功和失败，以及关闭、换代和延后导航。根独立检查默认 handler 分支与队列生命周期，未发现阻塞。三个 Kotlin 文件全文件 ktlint 1.8.0 零违规，`git diff --check` 通过。

同批 `ReaderPageTurnPresentationTest` 50 项中48通过、2失败，为既有 `rapid taps ... dual=true`，调用 fixture.tap，不经过本次键鼠 handler；D5 报告已有对应双页快速点击失败记录。保留本次失败，不扩大修复，也不宣称所选总范围全绿。原命令/退出码见 `early-input-controls-focused.json/.log`，四类 XML 固定在 `.gradle-coordinator/early-input-controls-focused-xml/`。没有完整模块/全量测试。

## 性能候选

输入修复已提交 `5aed3286efebb44674cc5d576edd6695ba98d242`。性能候选仅在本轮准入的 pending 轻量视口首帧已经 apply 后注册一次 opening 内容需求。注册、撤销、Loaded 接收与目标核对使用同一 session lock；页表到达后沿现有 `rereadFirstCompositionState`/恢复规则解析目标，再复用私有无进度 settle helper 和原 scheduler。注册时页表已先到的竞态也支持；旧 token 的 dispose 不能撤销后来注册。

原并发3、forward4/back1保留，故这是单页入口优化，不是声称只读一张图。没有提前解码、隐藏 holder、进度、共享协议、缓存算法或双页入口改动。未注册、切入完整入口、设置不符、非法恢复、失败、旧章代及关闭沿原链；可选 resolver 的普通异常不阻止正常页表发布，CancellationException 仍重新抛出。根独立审查后以真实失败复验了异常路径。

真实 RED：`startup-r2-demand-red.xml` 证明旧实现没有在下一轮复杂视口 apply 前准备目标；`startup-r2-resolver-red.xml` 证明异常 resolver 会让外部仍为0页而非187页。最终 `startup-r2-resolver-green` 三类 Screen 26、SessionIntegration 28、StartupObservation 2，共56/56通过、零失败/错误/跳过；固定 XML 在 `.gradle-coordinator/startup-r2-resolver-green-xml/`。最后仅将过长的测试断言提取为同值 local，复用该行为证据。ktlint 修改行违规0，6条未改旧行诊断保留；diff空白检查通过。没有模块全量或Android验证。

采用与收益仍由最后一次集中副屏正反对照决定，不由内部等待区间推定。B复用产品源码 `06287cf3c3` 的 V1 包；候选包含输入修复，但无额外输入的首图路径未处理早期队列，比较中不触发该修复。两臂固定相同文件后端、17项配置、规范CBZ与恢复页；不能外推普通注册表后端的绝对耗时。准备时只读核对主屏scale1.5、副屏bounds=(3840,1296,1920,1080)/scale1，正式每份仍须核实际外窗完整位于副屏。

## 真实适用性采样与撤回

候选源码 `9f626d844cd5c12cb0e355d1c831e1c687886d16`，preview `20261109T123331Z-5a1139a4`，build/sourceIntegrity/productionRuntime通过、dirty=false；同控制包一样因既有命令行长度限制跳过可选CDS。该[实验复现包](../../app-desktop/artifacts/preview/windows/20261109T123331Z-5a1139a4/Mihon-Desktop-0.11.24.6.9f626d8-unpacked/Mihon%20Desktop.exe)包含已撤回候选，不作为最终日常包。manifest原有nativeInteraction=NOT_RUN不回改。

在副屏经正常详情章行单击第5/187页地图，两个新JVM均确认正确漫画，细记录dropped=0。相对同次真实章行回调，毫秒如下：

| 阶段 | R2Ci1 | R2Ci2 |
|---|---:|---:|
| 页表就绪 |39.552|43.170|
| 首次初始状态投影记录结束 |39.819|43.561|
| 轻量视口apply |72.514|80.902|
| 显示OPEN_PAGE |112.412|118.974|
| 目标holder Ready |144.132|150.892|
| 应用目标draw（production事件） |163.990|175.654|
| DEMAND_ARMED / TRIGGERED |均无|均无|

初始状态采样时仍未就绪，但投影结束前后台页表已到达，同一轮最新读取直接获得Loaded；没有提交pending等待分支，故新registration完全未运行。诊断脚本最初错误假设必有ARMED并断言报TypeError，保留其输出；这是分析脚本前提错误，不是产品崩溃。随后保存完整缺失标记和时间，未删除或重采样。原件在本工作树 `.test-tmp/source-driven-v1/R2Ci1/`、`R2Ci2/` 的state/anchors/input/r2-mechanism。

合成阻塞页表夹具证明了机制可以成立，但这两次真实场景没有其触发条件；不能把163.990/175.654ms与V1旧227ms直接相减给候选记收益。按计划的机制前提停止，B/C、C/B四份外测与WGC均未启动，R/F/K没有本轮新结果，也不宣称普通用户都无法命中。本轮没有第二个候选或追加样本。

同一候选实际核对单页[4]→[5]→[4]，正确地图恢复；F11进入副屏1920×1080、退出恢复原1010×761捕获边界（origin3895,1344）及地图。只记录稳定结果，不承诺切换过程零黑帧。两个本轮应用实例及QPC helper均精确退出，无WGC进程创建。最后撤回本轮性能提交的产品/测试差异，并核对最终产品与测试源码逐字节等同已验证修复提交 `5aed3286ef`，因此复用R2.1行为证据；实验红绿、源码提交、包和原件保留用于研究。未推送。

下一方向仅记录，不自动执行第二轮：覆盖“页表已在首次投影期间到达”的真实入口，细分其页表→轻量apply约33–38ms与apply→显示OPEN约38–40ms；寻找合法已提交入口下更早建立目标需求/holder的方式。不能继续只改pending分支，也不能把这两个wall区间全归为CPU成本或可回收收益。
