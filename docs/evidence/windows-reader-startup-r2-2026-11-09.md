# Windows 首开 R2 实施与实验

日期 2026-11-09，实施起点 `539e297eec`。范围、依据与采用条件见 [R2 计划](../roadmap/2026-11-09-windows-reader-startup-r2.md)。本页逐步收口同一轮修复与一个性能候选，不代表正式发布或普通注册表后端性能。

## 早期控制事件修复

旧实现把轻量入口的所有早期输入统一等待页表 Loaded；页表慢时 F11 无响应，失败时不再执行。现在由原键鼠 handler 区分需要页表的导航，其他控件操作在完整入口挂载后立即执行；按事件索引消费一次，并用原章与 generation 阻止旧输入跨章重放。无额外输入的首图路径不进入该队列处理，不提前初始化全屏 adapter。

真实 Screen RED 为 `.gradle-coordinator/early-input-controls-red.xml`：页表被阻塞时首次 F11 的 `isFullscreen` 为 false。修复后同一 Screen 21 项、Fullscreen 20 项、KeyboardFocus 2 项通过；真实场景覆盖 F11/Space/Ctrl 缩放各自的页表成功和失败，以及关闭、换代和延后导航。根独立检查默认 handler 分支与队列生命周期，未发现阻塞。三个 Kotlin 文件全文件 ktlint 1.8.0 零违规，`git diff --check` 通过。

同批 `ReaderPageTurnPresentationTest` 50 项中48通过、2失败，为既有 `rapid taps ... dual=true`，调用 fixture.tap，不经过本次键鼠 handler；D5 报告已有对应双页快速点击失败记录。保留本次失败，不扩大修复，也不宣称所选总范围全绿。原命令/退出码见 `early-input-controls-focused.json/.log`，四类 XML 固定在 `.gradle-coordinator/early-input-controls-focused-xml/`。没有完整模块/全量测试。

## 性能候选

待可行性核查、真实 RED/GREEN 和独立审查后冻结候选；仅在本轮计划准入条件内尝试提前普通内容需求。采用与收益由最后一次集中副屏正反对照决定，不由内部等待区间推定。
