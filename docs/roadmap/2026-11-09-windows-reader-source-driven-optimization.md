# Windows 首开优化方案与 roadmap

日期 2026-11-09。依据[源码与第三方实现调查](../evidence/desktop-reader-call-chain-source-analysis-2026-11-09.md)，产品基线为 `1c9e511257`，调查文档提交为 `230d307c6a`。实施时核对最新 main 的产品差异，保留其已验收功能。P0–P3已完成实现、独立审查及定向验证，随本批提交固定为候选；当前待执行V1集中B/C外测，尚不代表性能采用。校准见[组件记录](../evidence/desktop-reader-p0-preferences-calibration-2026-11-09.md)，实现与组合验证见[首批证据](../evidence/windows-reader-source-driven-implementation-2026-11-09.md)。

## 目标与收益依据

主线是让已确认下载的固定单页先完成当前页显示，随后交接完整阅读器；Ready 消费持续连接 runtime，静态整页直接使用已拥有的位图。原生全屏 adapter 延迟到真正需要改变窗口 placement 时初始化。

| 顺序 | 项目 | 预计收益的依据 | 优先级 |
|---|---|---|---|
| P0 | 校准偏好读取后端 | 隔离配置每次读盘，普通 Windows 读注册表。收益首先是测量可信度，当前毫秒差未知。 | 前置 |
| P1 | 稳定轻量首屏，解除完整 model/UI 前置依赖 | 旧隔离样本中 holder apply→取图84–87 ms，包含约28 ms model构造及38 ms完整UI组合；这两个阶段适合移出首图关键链，实际回收量取决于当前页Ready时间。 | 最高 |
| P2 | 静态整页 no-op transform 直接呈现 | Ready后两次BOUND相隔13.7–16.5 ms，源码存在一次额外异步往返。预计主要减少一次重组机会；37–41 ms整个区间不能都算收益。 | 高 |
| P3 | 按需初始化 fullscreen/JNA adapter | 可确定普通非全屏无需构造原生adapter；与P1移出完整UI的收益重叠，仍可减少交接或原路径的同步准备。具体耗时依赖Native/User32已初始化程度。 | 低成本，收益待测 |
| P4 | 按证据处理 EDT 原子打开查询 | SQLDelight/JDBC同步运行在调用线程，可能影响响应；切IO与端到端缩短的关系需实测。 | 条件启动 |
| P5 | 有界投影复用、原图证据／缩放优化 | 有重复O(N)工作及像素读完后约7 ms混合区间，完整复用键／必要原图事实尚需证明。 | 后续研究 |

P0–P3为首批实现范围，完成后集中做一组候选对照。P4、P5根据新基线决定是否形成下一批。上述区间来自不同阶段和少量旧样本，不能相加为提速承诺。

工程参考保持归档≤19.620 ms、页表→目标holder开始≤43.065 ms、页表→显示取图约77.968 ms、响应R≤100 ms、近黑K≤60 ms。对普通Windows或官方原版的绝对差距，须有相应后端、输入起点和内容条件的实际数据。

## P0　偏好读取校准与新基线

**交付**为一个小型组件校准记录、实际Factory/读取次数和新采样条件。复用现有Preferences/ReaderPreferences/initialReaderState，校准与产品优化分别记账。

1. 从归档恢复相同的17项持久设置、默认值与读取场景，固定目标章节、模式、裁边、滤镜和恢复页。核对配置快照，不沿用曾误设的测试默认值。
2. 两种后端各用独立子JVM并核对实际root类型，避免Preferences静态Factory缓存使后端混用。文件后端调用实际隔离Factory；Windows后端使用默认JDK Preferences，并仅在本次唯一的私有探测节点保存测试键，向ReaderPreferences显式注入该store及私有legacy节点。校准不访问普通 `/mihon` 数据节点。
3. 每个子JVM先完成同样的ReaderPreferences构造，再计真实initialReaderState及现有复读设置检查，区分首次与同进程后续。顺序为文件/注册表、注册表/文件，共四个子进程。读取次数与读取线程另用一次真实调用计数，计数和无观测计时分轮。
4. 保存后端类名、键值、次数、耗时区间及进程条件。清理仅限本次私有节点和临时profile。既有持久化、legacy读取、进程重启和外部修改语义由现有 `DesktopTestProfileTest` / `DesktopPreferenceStoreTest` 作为约束。
5. 这份组件校准解释偏好成本，不能从旧端到端数据扣掉一个固定值。首批产品A/B采用同一明确后端；普通启动的绝对结论依赖其实际端到端样本。

**最小验证**为两种后端读取相同生产配置得到相同ReaderState、设置值来源与线程计数正确、私有节点生命周期正确；没有时间阈值断言。若新增的是校准helper，只执行helper相关用例。现有测试后端的缓存改造如另有必要，先提出其真实行为失败与跨进程边界。

## P1　下载单页持续由轻量视口加载到首图

### 适用条件与状态接续

范围限定为打开上下文已经确认下载的固定RTL/LTR单页，无自动布局、自动拆页。正常详情入口的 `ReaderChapterRef.isDownloaded` 已进入 initialContext，可在页表到来前确定资格；本地文件按已有明确路由判断。下载事实未知或其他模式沿现有完整阅读器入口。

复用 `ReaderFirstCompositionAdmission`、`DesktopReaderScreenEntry`、同一runtime、registry和holder。轻量host持续观察同一session；页表晚到时显示既有加载反馈，Loaded后按现有打开意图解析当前页并挂载真实Queued holder、提交当前页需求。取消基于两次即时读取决定永久fallback的时序依赖。

首次、恢复、末页和跨章位置均用现有解析结果。generation/章节变更丢弃旧结果；Failed/非法恢复位置及时进入现有错误／恢复反馈。模式及相关设置变化使当前资格重新按原规则处理，复用已有偏好通知或在真实holder准入时核对已捕获配置。

### 完整模型与UI的交接

正常无额外输入的打开路径先由轻量视口完成真实图片draw，再创建完整model和chrome。runtime已有加载、进度、取消与关闭职责，可继续独立运行。连接目标Ready与holder的链路保持有效，完整UI初始化不再是首次取图的前置步骤。

首图仍须满足当前请求、内容lease及既有单页准入契约。确有必要的单页准备复用现有runtime/core/owner，不能通过略过认证或提前上报进度达到顺序断言；实际像素与回调一起验收。

用户请求完整控件、首图前翻页／切章或其他必须依赖model的动作时，可提前启动既有完整入口；既有requestedPage、zoom和UI意图只消费一次。退出始终有效，活动指针手势在release/cancel前保留原owner。

模型开始创建与视觉接手分开处理。完整model的pairing恢复、初始投影或正常Pager尚未具备呈现条件时，继续保留当前正确图像。交接条件包含同章/代次/目标/内容身份与可用呈现状态，不能仅凭firstPresentedGeneration立刻切到尚未就绪的视口。

同一movable content只在一个位置挂载。完整视口接手时移交同holder及其lease，保留已显示像素；模型初始化、Loading反馈或LazyPager首轮测量不能重新遮黑。完整UI使用的session更新桥应在其初始化阶段就连接，复用现有accept/state协议，禁止把不一致的raw页表与旧派生呈现分开发布。

### 真实红绿与修改边界

主要文件为 `ReaderFirstComposition.kt`、`DesktopReaderScreen.kt`、`DesktopReaderScreenEntry.kt` 及必要的现有viewport接线。修改围绕此入口和交接，按实际依赖复用Factory/Session。

- 延迟真实页表到首次组合之后，Loaded仍进入同一轻量路径，首个真实目标holder不会因读取时刻不同而被完整UI挡住。
- 使用真实session、encoded store、Skia和Screen绘制。旧实现的红测为完整model开始先于目标真实draw；新实现证明目标像素draw及进度回调先发生，随后才构造完整model。
- Ready、首绘、完整UI接手贯穿同一holder。接手后继续显示正确页，无额外读取/解码和近黑回退；首图后立即连续翻页，确认初始化不吞事件、目标与恢复页正确，并记录交互等待。
- 覆盖旧代/退出、页表失败、恢复页不可用、早期翻页及活动拖动跨首绘；验证正常进度、单次资源释放和无model时取消。
- 在线、条漫、双页、AUTO/拆页至少各有真实Screen分支回归，确认现有入口语义。共享核心行为未变化的验证复用既有结果。

定向范围优先复用 `ReaderFirstCompositionIntegrationTest`、`ReaderFirstCompositionTest`、`StartupSingleViewportTest`、`DesktopReaderChapterTransitionIntegrationTest`、`DesktopReaderPresentationImageOwnerTest`。只为缺失的真实失败新增用例。

## P2　无需变换的位图在Ready组合中直接可呈现

主要文件为 `ReaderPresentationTransformOwner.kt`、`ReaderPresentationImage.kt` 及必要的既有draw/lease接线。

1. 依据已有no-op条件选择静态整页直通。无splitHalf、sourceBounds及裁边时直接保留当前base bitmap，不经Empty→LaunchedEffect→lazy任务→Ready往返。
2. 使用同一真实资产lease及内容身份。组合成功、放弃、重组替换、退出都释放对应lease；在composition中取得资源时覆盖onAbandoned，不能只依赖正常onDispose。
3. 原有prepareDraw、holder/transform身份确认和进度语义继续适用。返回可绘图片与实际Canvas draw后确认分别验证，旧代/旧attempt资产不能进入新请求。
4. 需要实际裁边、拆分或动画帧处理的分支继续走现有异步变换。滤镜／旋转保持当前所属绘制链，不重新定义颜色或采样算法。

**真实红测**为base asset已Ready、无需变换时，第一轮有效组合仍返回空renderedImage且需要额外任务；绿测证明该组合已有真实可绘图片，原像素和身份一致。另覆盖同代替换／旧结果、组合abandon、关闭、动画／裁边原分支、单页及双页frame接线。

复用 `ReaderPresentationTransformOwnerTest`、相关真实Screen/图片呈现测试；变更公共呈现helper时补对应单页和双页生产接线，性能采样仍集中于首批目标单页。

## P3　fullscreen adapter 按真实窗口切换创建

主要文件为 `DesktopReaderFullscreenController.kt`，按需要调整既有注入入口。

- provider/lazy只在首次真实placement mutation前求值一次，缓存实际adapter或null；普通windowed创建、syncPlacement(false)及关闭只使用轻状态。
- 初始窗口已是Fullscreen而收到退出请求时也能初始化并正确恢复。初始化须先于prepareTransition/enter/exit所需原生操作。
- 所有切换复用一个adapter，保留style/placement、retained frame、revision/disposed/target检查和既有Compose fallback。
- 通过真实controller及注入factory计数形成红绿。窗口普通打开/关闭计数0；第一次真实切换计数1，后续进出仍为1；初始全屏退出、排队失效、null/失败fallback各按既有用例验证。

focused优先使用 `ReaderFullscreenTest` 和实际受影响的retained-frame/geometry测试。最终原生批次增加一次真实进入/退出及窗口恢复核对。

## 首批测量与采用条件

### 最小观测

保留章行回调、页表Ready、holder构造/apply、OPEN_PAGE、decoder/raster边界及FIRST_PAGE_PRESENTED。补充目标Ready实际发布、轻量／完整model交接、no-op/direct transform选择及必要的frame prepare结果，均带真实页/章/代次/attempt身份。

使用默认关闭的现有观测器，诊断计数与无细探针计时分轮。动态字符串、列表扫描和采样文件导出在适当的enabled/末尾路径执行。9ce归档中的观测字段可按最小差异参考，产品行为从当前main延续。

### 一次集中副屏批次

先完成P0–P3各自真实红绿、相关focused及修改行格式检查，再构建一个同产品基线B和一个联合候选C。B/C使用同一测量后端、设置、内容、JDK、打包流程和同一副屏；各自固定源码SHA及完整manifest，只有当前优化差异进入候选。

1. C内部新进程两次，证明晚到页表仍可进轻量路径、Ready持续被消费、同holder真实draw、完整model/UI接手顺序正确，保存关键阶段。
2. 细观测关闭，B/C、C/B两对外测，各新进程。记录R、F、连续与累计K的真实原帧界限和QPC；正式区间结束后一次性导出状态。
3. 候选同批做单页往返；公共呈现路径受影响时双页OFF/ON各核一次完整正确页对与往返；另核一次真实全屏进出/窗口恢复。身份与实际画面共同验证。

副屏每次查询当前拓扑/DPI并预置独立profile窗口位置，实测完整bounds落在非primary屏；1024×768仅作当前已验证参考。使用规范CBZ及正确恢复页，章行单击一次；计时前统一经真实作品详情入口，避免History双击。基线与候选按各自实际画面核对同一目标地图及ROI，保留实际采样空隙。

Windows B/C以真实章行回调作为共同t0，保留输入包络；能取得关联AWT输入端点时另列。与官方的比较采用共同基准约定，后台／窗口／应用绘出及物理呈现分别记。原生操作沿[Desktop验收规则](../agent-guides/desktop-acceptance.md)集中执行和收尾。

**采用**要求真实像素、恢复、进度和资源生命周期正确，且F或K至少一项在正反两对中都有可辨改善，其他指标未出现可辨退化。可辨改善按B下界大于C上界计算；区间重叠报告为未分辨。预算尚未通过可记录局部收益，不能宣称稳定追平或P95。

机制红绿和内部顺序未通过的候选，先定位该失败；同一无效入口不重复录制。修复范围按新增失败确定，保留原件，不用删除慢样本代替修复。

## P4、P5的启动条件

P4在新基线仍显示章行到Reader响应由同步事务主导时启动。整个原子打开事务使用现有IO调度，UI仍await同一结果后导航；保持恢复/同步基线原子性，覆盖退出取消、重复点击和迟到结果的真实详情接线。事务后台执行的时间与UI响应分别计量。

P5须先用新记录证明剩余热调用及可复用的完整输入身份。投影复用遵循[D6](../evidence/desktop-reader-d6-2026-11-06.md)、[D7](../evidence/desktop-reader-d7-2026-11-07.md)、[D8](../evidence/desktop-reader-d8-2026-11-07.md)反例；原图采样/缩放优化保留原图事实、旋转、颜色及内存上界。证据不足的方向记录为暂缓研究，首批采用不依赖其实施。

## 执行分工与成本

实施由一名Sol medium连续负责P0–P3及定向红绿；Luna负责数据读取、校准批处理、采样与结果提取；主协调者独立审查生命周期/状态/接线，集中一轮，发现问题后聚焦修复复审。同一目录由一个实施者写代码，Gradle由coordinator串行执行。

P0为小组件校准；主要工程成本在P1状态交接与P2 lease生命周期。P3为局部controller改动。原生测量在两包就绪后集中一次，通常需数十分钟，异常准备耗时另记。API成本按实际用量记录，不预报固定金额。

每项局部修复只复验对应真实行为和受影响接线。全模块、全量、Android或正式发布矩阵按项目验证规则另行决定。采用后提交仅含本次差异，按已获授权范围整合/推送，原始数据保留去重归档。

## Roadmap

- [x] **P0 测量校准**。完成四个组件子进程及一次读取计数，输出后端/设置/线程/次数/耗时记录，固定首批B/C协议。
- [x] **P1 稳定轻量首屏**。通过晚到页表、真实draw早于完整model、持续Ready消费、同holder交接、首图后无新增黑屏、早期输入与退出等focused。
- [x] **P2 no-op直接呈现**。通过首个Ready组合可绘、无额外变换任务、lease放弃/替换/关闭和原异步分支回归。
- [x] **P3 按需fullscreen adapter**。通过provider实际调用次数及切换生命周期focused。
- [ ] **V1 联合采用裁决**。完成一次B/C双包、副屏两份内部和正反四份外部、必要模式/全屏正确性核对；报告通过项、实际收益及剩余预算。
- [ ] **R1 剩余方向评估**。按新基线决定P4/P5的实施或暂缓，记录原因和下一项具体失败条件。

P0–P3及V1每项的勾选包含其按风险所需的审查、定向验证与提交。R1完成的是证据评估，不代表P4/P5功能已经实现。
