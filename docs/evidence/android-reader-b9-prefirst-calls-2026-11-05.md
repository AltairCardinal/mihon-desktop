# B9 普通单页首开：首个 Reader 窗口帧前的具体调用

日期：2026-11-05。范围严格限定 PCE-W30、已下载《史莱姆》第04卷、单页 RTL、5/187，从各应用主线程收到详情章节 DOWN，到首个 Reader layer `actual_frame_timeline_slice.ts+dur`。该帧不是首漫画像素。本轮交付调用归因和可复核样本表。产品沿用 B9。

**在这六份无方法采样的记录中，已证明 B9 多一次内存压力查询、多一次包信息查询，以及多一至两次服务查询；主要十余毫秒差距仍不能归为“B9 多调了某个函数”。创建、XML inflate、窗口 IPC、单页 Pager 构造与 Compose overlay 都有上游对应。同次数调用更贵，与额外调用必须分开。** 今日新增两份现存 B9 的函数采样及同次系统 trace，已把宽泛创建区间推进到 `ReaderViewModel` / `ReaderConfig` 构造、`updateViewer→PagerViewer`、配置 Flow 与 overlay/AppBars 方法链；官方明确拒绝 shell 方法采样，不能获得逐方法包间差值或把官方未采到的方法记为零。

## 样本身份与证据层次

工作树 `D:/Codex/worktrees/mihon-reader-o-series-20261104`，HEAD `c8d8a7880a17180c35766a58ee7accb50a9b559e`。实际 B9 APK SHA-256 始终为 `4c3c6f82eea657aecd0c261972fa04c965a99ac5468f21309b87b032f23ccc59`，隔离包 `app.mihon.desktop.fork.benchmark`，非 debuggable、R8、profileable shell。未构建、安装或改动 APK；probe 维持 OFF。

1. **六份无方法采样原件**：沿用 [A1](android-reader-single-first-open-2026-11-05.md) 的三份 B9 / 三份官方首次。PID、UPID、主线程 UTID、DOWN、Reader 帧端点与 sched 均逐份绑定。离线过程文件为 `.test-tmp/b9-prefirst-calls-20261105/luna/{samples,ipc,named-slices}.csv`、`extract.py` 和 README。原件仍在 `D:/Shell/Github/mihon/.test-tmp/reader-open-o-final-20261105/`，没有混入其它页或暖态。
2. **今日两份 B9 单页函数采样**：`sol/b9-single-method-1-valid/`、`sol/b9-single-method-2/`，1 ms ART sampled method trace 与同次 Perfetto，采样前正常 Main→详情，输入后正常 Reader→Back。两张原图均为地图单页 5/187，XML 同证，未翻页或改设置。它们受采样扰动，只用于方法归因，不与第一层做耗时减法。
3. **旧 O10**：APK 与 mapping 同 B9，但旧场景为双页 OFF/ON、2 ms 采样，窗口为 create→pages/unit，没有本次 DOWN→Reader actual frame 的同构边界。因此只复用解析格式、原脚本与符号 mapping，不借其耗时或方法命中代替今日单页证据。

官方源对照是本地 annotated `v0.19.9` 指向的 commit `7a917968e3bf71c4a665e6655a550877d81ead1d`，该 commit 明确 versionCode 22 / versionName 0.19.9；tag 没有 `gpgsig`。这是 release 源语义对照，未声称仅凭 tag 或包名已证明官方安装 APK 与此 commit 的可复现字节绑定。无官方 mapping 的 R8 符号不做跨包强行配对。

## 六条原件：哪些更多，哪些同次数更贵

| 项目 | B9 三条 | 官方三条 | 可得结论 |
|---|---|---|---|
| DOWN→首 Reader 帧完成，ms | 108.465 / 121.560 / 107.373 | 79.267 / 83.103 / 77.649 | 原始窗口差，非方法差 |
| 同窗口主线程 Running，ms | 61.505 / 73.185 / 56.392 | 27.987 / 26.636 / 29.564 | 中位差约 33.517 ms；并非全部属于 Reader |
| Reader `performCreate` | 每条一次；CPU 17.654 / 22.649 / 17.769 ms | 每条一次；7.632 / 6.281 / 8.158 ms | 同一生命周期更贵，尚不能拆为某个新增方法 |
| 创建内 `inflate` / Binder transaction descendant | 每条 3 / 13 | 每条 3 / 13 | 没有数量增加证据 |
| `ReaderNavigationOverlayView` 类名标记 | 每条一次；CPU 0.271 / 0.373 / 0.369 ms | 每条一次；0.041 / 0.042 / 0.040 ms | 同次数更贵；不是额外 view，也不能把类名标记全当构造函数独占成本 |
| 创建后最后一次 generic traversal 的 recompose | 2 / 2 / 2 | 2 / 1 / 2 | 只在第二对多一次；无窗口/composable 身份，不能认定 Reader 重复工作 |
| Resume 下 `getMemoryInfo` (`IActivityManager:70`) | 每条一次；wall 0.174479 / 0.157292 / 0.146875 ms；main CPU 0.057813 / 0.051042 / 0.037500 ms | 每条零次对应事务 | 新增系统查询；没有第二次焦点刷新证据 |
| `getApplicationInfo` (`IPackageManager:9`) | 每条一次；wall 0.116666 / 0.114062 / 0.124480 ms；main CPU 0.029166 / 0.026562 / 0.028126 ms | 每条零次对应事务 | 新增包信息事务，应用调用者未知 |
| `checkService` (`IServiceManager:2`) | 4 / 4 / 4 次；wall 总 1.106251 / 1.178647 / 1.886460 ms；main CPU 总 0.181251 / 0.144273 / 0.238025 ms | 2 / 3 / 3 次；wall 总 0.422396 / 0.722918 / 0.725521 ms；main CPU 总 0.060938 / 0.125523 / 0.109896 ms | 多 2 / 1 / 1 次；具体服务名未知 |
| `setTaskDescription` (`IActivityClientController:36`) | 每条 3 次 | 每条 5 次 | 官方反而多两次，不能把整个窗口 IPC 归为 B9 增项 |

[具体事务次数及原始精确 CPU/wall 小表](android-reader-b9-prefirst-calls-2026-11-05.csv) 可逐份复核；0 只表示该完整观测窗口内没有该具名事务，不表示官方没有等价语义或方法成本为零。表的 main CPU 使用包含 CPU0 的全部 sched Running，裁切窗口后逐条交集，已与 A1 六份总量及全部 IPC 独立复核。

三条 B9 与三条官方都有 Main pause→Reader create→Reader resume；B9 没有多创建一次 Activity。`ReaderNavigationOverlayView` 初始 `gone` 在两版 `reader_activity.xml` 都存在；隐藏 view 的 inflate 不等于 fork 新增调用。上表 CPU 是 slice 与真实 app-main Running 的交集；父子和不同 family 可能嵌套，不能相加。

明确的源码新增入口在 `ReaderActivity.kt:510–552`：`onResume` 和 `onWindowFocusChanged(true)` 调 `refreshOptionalReaderMemoryPressure`，其实现创建 `ActivityManager.MemoryInfo`，取 ActivityManager 并调用 `getMemoryInfo`、更新 StateFlow。消费在 `DualPageViewerAdapter`，普通 `R2LPagerViewer` 没有该消费者。因此它在单页是可指出的小开销，不能解释创建和遍历的十余毫秒差，也不能从两个静态入口推导首帧前执行两次。三份实际 trace 都只支持上述一次 Resume 事务。

验收补核读取了**同一平板** `/system/framework/framework.jar`（41,632,515 B，SHA-256 `5b15c071e771230c0480427d7dfdd23eaeec394eba10ec30a1c9c3474d5c73dd`），直接解析 DEX Stub 的 static field/encoded_value，而非套用其它 Android 版本的 AIDL 序号。`classes.dex` 的 `IActivityManager$Stub.TRANSACTION_getMemoryInfo` field #10220 值为 70；因此现在可以把事务准确命名为 getMemoryInfo。该事务在三份 B9 各一次、三份官方各零次；与 B9 onResume 源调用结合，可确定这一新增系统调用。`IPackageManager$Stub.TRANSACTION_getApplicationInfo` field #24799 值为 9，B9 各一次、官方各零次；三个原件中它的直接 parent 都为 root `activityResume`（slice 6798 / 6479 / 7671），无更细应用方法，调用者仍 UNKNOWN。不能据此猜测为 `isPackageInstalled` 或某个 fork 配置检查。

同机 DEX 另证明 `IServiceManager:2=checkService`，B9 次数 4/4/4，官方 2/3/3，即多 2/1/1 次服务查询，但服务名与每次调用者没有 transaction 参数证据。今日第1份 ART 有一条 `startActivity→HwPCUtils.hookStartActivityOptionsInVrMode→HwVrServiceManagerImpl` 初始化 `→ServiceManager.getService→checkService` 栈，sampled CPU 0.807 ms；它证明厂商启动链确实能产生此查询，不能把所有原件增加的次数都归这一栈。作为反例，`IActivityClientController:36=setTaskDescription` B9 3/3/3，官方 5/5/5，不能把所有窗口调用都列作 B9 增项。原始 DEX 值、26个映射与复算脚本为 `.test-tmp/b9-prefirst-calls-20261105/acceptance/{vendor-stub.json,read-vendor-stub.py}`。

当前 `MangaScreen.kt:351` 与 tag `:295` 都是 `ReaderActivity.newIntent` 后 `startActivity`；两版 `newIntent` 的 manga/chapter/resume extras 与 `FLAG_ACTIVITY_CLEAR_TOP` 同义。两版 onCreate 都有 binding inflate、`setComposeOverlay`、ViewModel 初始化、ReaderConfig、状态 Flow 与 `updateViewer`。不能把这些共同链路整体列作 B9 额外工作。

## 窗口创建与最后一轮界面遍历分别慢在哪里

将每次通用 `traversal` 按开始时点与 `StartActivity`、`performCreate` 排序，再把其区间和该应用主线程在所有 CPU 核上的 Running 取交集。六条记录中，最后一次遍历都在 Reader 创建结束后开始。它的 trace 标记没有窗口身份，不能直接指定为 Reader 窗口或某个 Compose 组件。之前的遍历都开始于 Reader 创建前。[逐次遍历、相对时点及 CPU](android-reader-b9-prefirst-traversals-2026-11-05.csv)

| 已有无方法采样记录 | B9 三次 | 官方三次 | 两组中位数之差 |
|---|---|---|---:|
| 创建前的遍历次数 | 3 / 3 / 1 | 1 / 2 / 1 | — |
| `performCreate(ReaderActivity)` 主线程 CPU，ms | 17.654 / 22.649 / 17.769 | 7.632 / 6.281 / 8.158 | +10.137 ms |
| 创建后最后一次 `traversal` 主线程 CPU，ms | 16.943 / 19.655 / 17.843 | 6.404 / 5.540 / 6.993 | **+11.439 ms** |
| 其中 `Compose:recompose` 主线程 CPU，ms | 5.118 / 5.538 / 4.967 | 1.311 / 0.910 / 1.555 | +3.807 ms |
| 其中 `layout` 主线程 CPU，ms | 1.788 / 1.909 / 1.881 | 0.361 / 0.289 / 0.379 | +1.520 ms |
| 其中 `dispatchApplyInsets` 主线程 CPU，ms | 1.376 / 1.580 / 1.367 | 0.597 / 0.461 / 0.577 | +0.799 ms |

第三对两端都只经历一轮创建前遍历和一轮创建后遍历，最后一轮 B9 仍用 17.843 ms，官方用 6.993 ms。仅凭 B9 前两条多经历几帧无法解释这个差异。六条最后一轮均做一次 `layout` 和两次 `dispatchApplyInsets`，重组次数为 B9 2/2/2、官方 2/1/2。后三行是遍历的子区间，各自取中位数，不累加，也不折算为可省下的首图时间。这里能区分额外调度和单次工作变贵，仍无法把两版同名区间的差额分给具体业务函数。

## 今日方法级证据

命令入口：`python .test-tmp/b9-prefirst-calls-20261105/sol/run.py <唯一label>`，内部复用既有采集器，以 `am profile start --sampling 1000 <B9包> <唯一trace>` / `am profile stop` 录 ART，同时记录 Perfetto sched、gfx/view/wm/am/input/Binder 与 FrameTimeline。分析入口 `sol/analyze.py`；每份保留 SQL、查询 CSV、`methods.trace`、`method-attribution.json`、采集结果、原图及正常返回详情 XML。[精简方法原值 CSV](android-reader-b9-prefirst-methods-2026-11-05.csv) 保留 raw class/method/descriptor 和样本方法 ID，不只保留人工命名。

| 对齐项 | 第一次 | 第二次 |
|---|---:|---:|
| PID / UPID / main UTID | 15203 / 3 / 7 | 15204 / 3 / 16 |
| DOWN BOOTTIME ns | 466923771093194 | 466969428758812 |
| StartActivity ns | 466923805060381 | 466969463505687 |
| performCreate 起 / 止 ns | 466923829615069 / 466923868070277 | 466969481882249 / 466969508243187 |
| 首 Reader frame 完成 ns | 466923931491111 | 466969570461416 |
| ART 记录 / main changed-stack snapshot | 9601 / 223 | 9422 / 229 |
| 前缀 sampled 完全落内 CPU / 跨界 CPU 上界，ms | 64.007 / 3.485 | 60.914 / 2.291 |
| 同前缀 sched Running，ms | 65.355 | 62.166 |

方法表是完全落在前缀内的相邻 observed-stack 区间，以 ART dual thread CPU 增量归给前一采样栈。**inclusive 包含子方法，leaf 只是采样末帧归属估计，不是插桩精确独占时间；下列行互相嵌套，不能求和。**

| 具体方法/链 | 第一次 inclusive / leaf，ms | 第二次 inclusive / leaf，ms | 解释 |
|---|---:|---:|---|
| `ReaderActivity.onCreate` | 18.505 / 0 | 16.955 / 0 | 共同入口；0 leaf 不表示自身零成本 |
| `ReaderViewModel.<init>(SavedStateHandle)` | 1.921 / 1.105 | 1.071 / 1.071 | 构造/DI 区域已命中，不能把整段归某个 fork 新字段 |
| `ReaderActivity$ReaderConfig.<init>` | 1.126 / 0 | 2.824 / 0 | Flow/协程配置链；两个样本叶栈不同 |
| `ReaderActivity.updateViewer` | 5.372 / 0 | 5.059 / 0 | 由 manga state Flow 驱动；已命中具体单页 viewer 构造 |
| `PagerViewer.<init>` | 4.259 / 0 | 3.963 / 0 | 下含配置订阅、Pager/控件创建 |
| `R2LPagerViewer.createPager` | 2.062 / 0.762 | 1.780 / 0 | 第一次含任务信息/Locale 解包；第二次含 Scroller 的 Huawei DynBuf/IAware 调用 |
| overlay `ExternalSyntheticLambda3.invoke` | 2.288 / 0 | 3.416 / 0 | mapping 对应 `setComposeOverlay` lambda；有 inline/重载候选，不强行唯一反混淆 |
| `ReaderActivity.AppBars` | 1.128 / 0 | 2.257 / 0 | 共同 Compose UI 方法，不能凭 sampled CPU 声称比官方多调 |
| `BinderProxy.transactNative` | 10.849 / 10.849 | 8.916 / 8.916 | 包含不同接口/系统交互，不能整体归新增内存查询或 system_server CPU |

`updateViewer` 的实际下游栈还命中 `ViewerConfig/PagerConfig.register→Flow.launchIn→协程创建/Handler.post`、`PagerViewerAdapter→createReaderThemeContext`、`ReaderProgressIndicator` 构造和 Compose 内容。第一份还有 `setOrientation→Activity.getRequestedOrientation→IActivityClientController.Proxy`。这些给出具体“执行在哪里”的毫秒级证据，但官方没有方法采样，不能证明其调用次数或 CPU 的包间差额。

输入至创建之前还命中 MainActivity/章节点击 lambda、Compose measure/状态读取、取消协程、输入完成和 RenderNode 操作。这一段至少含旧详情窗口处理，不能统称 Reader 初始化。Huawei 控件、ART/反射、Locale/资源处理与系统 IPC 单列为平台链，不命名为 fork 冗余。

## 解析核验、不可观测部分与现场

ART 两份均 SLOW v3 / dual、32 B header、14 B record，字典/记录数匹配，overflow=false，main PID/线程匹配，栈出入与 thread CPU/wall 单调检查通过。实际 R8 mapping 是同 B9 artifact 的冻结文件，流式按观察符号读取，保留所有 inline/overload 候选。ART startTime 为 MONOTONIC，同次 Perfetto clock snapshot 转 BOOTTIME；第一次 offset 恒为 `234727154339043 ns`，第二次两 snapshot 范围相差 521 ns，取最后 snapshot 同 offset，保留该误差范围。没有用主机时钟或另一次 trace 替代。

第一次 Perfetto 没有 error/drop；第二次有 `trace_sorter_negative_timestamp_dropped=1`，丢弃事件归属未知。目标输入、启动、创建和首 Reader layer 帧都是正值并有身份约束；仍保留告警，第二次不能当无缺损系统 trace。两份 ART 自身结构核验独立通过。没有为告警再采择优。ART记录数的头字段名虽叫 num-method-calls，采样模式下它仍是栈差分记录，不能翻译成真实调用次数。最初一次唤醒异步尚未收敛即在任何章节输入前停止，保留为 `b9-single-method-1-ui/`，不列作首次样本；改为有界只读等待，不重复发送解锁。

对现有官方 PID 25299 实际执行一次同类命令，返回 exit 1：`SecurityException: Process not debuggable, and not profileable by shell: app.mihon`。记录在 `sol/official-profile-denied.txt`。未开始官方方法采样，未重新签名、安装、编译、清数据、改设置或操作原页。官方未观测的方法和成本为 UNKNOWN，绝非 0。

采样只有栈差分；snapshot/enter 记录都不是真实调用次数。两次稳定命中不等于没有短方法漏采；未命中 `refreshOptionalReaderMemoryPressure`、共享 session 或某个新 DI 方法不能证明其未运行。ReaderViewModel 额外字段/依赖、恢复状态/进度设施、共享初始化中到底分摊多少毫秒，当前方法粒度和官方限制仍不足以分开。原三对约 33.517 ms 的 main Running 中位差，不能由本轮采样表直接相减或被上述小查询完整解释；原 A1 的互斥“未归属”CPU 仍为 B9 11.375 / 15.319 / 9.463 ms，官方 6.790 / 7.154 / 6.362 ms，这个分类也不是所有尚未方法归因的差值。

最终读回 APK SHA 未变、probe enabled=false、隔离 B9 无 PID、官方原 PID 25299 保留、设备 `Asleep/Changing=false`。所有本任务采集子进程退出，无 Gradle 或诊断包。现场入口 `sol/final-device.txt`。源码/测试未修改，无产品回归测试或全量构建；本轮验证是原始边界、方法格式/栈/时钟、实际目标页和精确现场核验。报告与事务、方法采样、遍历三份 CSV 为交付物，系统 trace/漫画图/大 mapping/设备 framework 原件只保留忽略目录。
