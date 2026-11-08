# Desktop 首次组合直接消费已发布页表实验

本页记录单一实验候选，不表示采用或首开性能通过。窗口指标及最终裁决由协调者另外核验；本实施者不控制窗口，不提交漫画、截图或采样视频。

## 固定输入与假设

- 基线 `73fb2590d8c81ca288881f26f450f94c54282fda`，独立树 `D:/Codex/worktrees/mihon-reader-first-compose-20261108`，分支 `codex/reader-first-compose-20261108`。
- 目标场景为已下载 Volume 04，187 页、恢复 index 4、RTL、单页、不拆页；准入另覆盖有效 index 186。
- 唯一假设：真实首次 Compose 组合若已能读取 Loaded 页表和确定的恢复页／模式，可先挂载真正的显示 holder，再创建完整模型；未就绪则沿用原链。
- 沿用 `904bc8d34308`、`3b2040111964` 的两阶段 holder；旧 C 已修复的拖动和退休逻辑从本机 `.test-tmp/d10-20261107/format/` 格式副本对照移植。该目录仅是局部历史输入，不能冒充完整冻结包源码。
- 当前主干章节按钮／阅读流入口与 miniz 修复保留，不更改归档、页表发布、请求优先级、缓存及解码协议。

## 实现与失败边界

Voyager 的轻量 `DesktopReaderScreenEntry` 保留既有同一个 runtime、session 和 IO owner。它只把完整 `ReaderScreenModel` 的创建时机移至准入后的下一次组合，原直接创建 API 仍走同一工厂；模型接手后的 catalog、进度和生命周期继续由原实现负责。初始状态投影从模型抽为同一生产函数，避免复制恢复／模式解析。

实际 Screen 的首次组合仅采样一次 `session.state.value`。已下载、Loaded、章节身份相符、恢复页有效且已定位、显式 RTL/LTR、非自动布局、非双页、非拆页才准入。任何条件不满足立即走完整模型原路径，之后即使 Loaded 也不回头补启动容器。不等待页表，不提前开章节，不修改发布时机，不作全局预热。

成功路径先挂载 Queued 的真实 holder。真实 Compose apply 后才让完整模型接手；同一 movable-content registry 让该对象跨轻量视口／完整分页器边界保留，Ready 到达后才绑定内容和 acquire。Ready 前没有 region 或内容读取；旧代次、重试、退出均拒绝迟到绑定。初始未绑定取消时 entry 直接释放 runtime，接手后由原模型释放。

真实 draw 才报告阅读进度，布局需求只调度不计进度。首个 Press 至 Release／取消保留手势所有者，Ready 首绘不能在活动拖动中卸载。释放后的 RTL/LTR 翻页、早期边界切章及初始缩放交给既有模型；页码或模式改变后本代不回到启动容器。

默认关闭的既有 `ReaderStartupTrace` 记录 `FIRST_COMPOSITION_SNAPSHOT_READY` 或 `FIRST_COMPOSITION_FALLBACK`，附目标页、reader generation 和页数。holder 构造完成、实际 apply、内容绑定带实际对象标识，既有 acquire、IO 与首绘事件继续使用其真实调用边界；不伪造提前时间戳。应用首绘仍不等于物理屏幕呈现。

## 真实行为红绿

日志保留在本机独立树 `.gradle-coordinator/`，不随克隆分发。

- `first-compose-holder-red`：在旧 production 视口组合 Queued 第五页，目标 holder 创建计数断言失败；旧路径尚未挂载真正显示对象。
- `first-compose-order-red`：顺序断言失败，记录 `holder=-1 model=0`，完整模型标记在任何目标 holder apply 前出现。
- 顺序测试最初把惰性的 HorizontalPager 当成启动视口，父 SideEffect 可先于惰性子项组合；改为与 production 相同的轻量视口后复验，保留原顺序断言。真正 Screen 的集成测试另执行真实 session、实际模型工厂、Skia 图片加载和 draw，而不以该标记夹具代替产品接线。
- `first-compose-focused-final`：7 类共 40 项通过，0 失败、0 跳过。真实 Screen 证明目标 holder apply 早于 `COMPOSE_MODEL_CREATE`，同对象贯穿 Ready／首绘／接手，并保留正常进度；首次无页表的 Screen 之后仍走原链。
- 同轮覆盖活动 Touch 横拖跨 Ready 首绘、旧 generation／retry 拒收、取消无绑定 holder、恢复页／模式准入、既有章节导航、单页身份及键盘导航。新增 entry 生命周期的未接管取消／接管后单次释放也已覆盖。最终 `first-compose-final-verified` 同 7 类 42/42 通过，0 失败、0 跳过；包括格式收尾后的最终源码。

变更超过通常文件／行数估算，是因为旧 C 的 holder、movable content、手势容器与 Screen 生命周期及真实集成测试需要作为一个可运行单元移植；没有另建请求、持久化或通用预热系统。

## 验证与交付状态

格式收尾的一次编译发现选择性应用排序时漏迁移新增 import，恢复完整排序组后重新编译并通过上述 focused；该准备失败不计行为 RED。

新文件及修改行按 ktlint 1.8.0 检查为零诊断，`git diff --check` 通过；既有未修改行的格式诊断保留，不作无关格式化。仅进行对应 Desktop focused 验证及普通 preview，不执行全模块／全量、Android、正式发布、版本分配、推送或合并。

候选构建只证明可体验产物与既有 production runtime 检查，窗口原生交互、成功分支是否在真实首开命中，以及外部响应／无内容／正确首漫画时间仍须独立验收。若真实第一轮页表未就绪，必须报告 fallback；不能用后续模型之后的容器命中替代假设。
