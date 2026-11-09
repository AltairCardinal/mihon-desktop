# Windows 首开 R4：入口内容需求前移与准备前段归因

日期：2026-11-10。状态：R4.0–4完成；四份ON与十二份OFF及正确性已核验，不采用E1；R4.5待精确撤回与局部补验。对应[首开父 roadmap](2026-11-05-desktop-reader-first-open.md)的D14，从首个未勾选任务继续。

## 1. 目标与基线

将固定单页 opening 的普通 encoded 内容需求从实际 viewport 提交前移到已授权的 reader entry/session 生命周期：页表可解析、下载事实可靠后，沿原 scheduler 准备恢复页，使已有准备工作与首屏组合重叠。并拆清约10–12ms拷贝前段的真实调用，防止把它误判为解压或原子提交成本。

设计基线为 `d66d37b13332a0c36b8a9ff0bebccdcf8e274c48`，工作树 `D:/Codex/worktrees/mihon-windows-reader-source-driven-20261109`。该基线保留 R2 输入修复、R3 默认关闭诊断及缓存缺失反馈修复，R2/R3 性能候选均已撤回。实施前重新固定 SHA、未提交差异、配置与实例身份；若采用更新 main，只适配相关差异，不覆盖其他功能或混合提交。

本批范围：正常 DesktopReaderScreen 新建的 reader entry，已确认下载/本地归档，固定 RTL/LTR 单页，非 AUTO、无自动拆页，同一初始 opening。在线、未知内容路由、双页 OFF/ON、条漫、临时文件、直接创建完整 model 的其他调用者和后续跨章沿原链。固定场景优先 CBZ，其他格式不为本实验扩展适配。

继续使用原 session/core/scheduler、materializer、encoded store、内容 owner 和显示管线。并发3、forward4/back1、长度/CRC/配额/原子提交、完整请求身份、图像质量、正常输入、进度及资源退出不变；不提前 holder、内容 lease、解码或 FIRST。调度可能同时带有限邻页，必须报告真实请求集合，不能称“只读取一张图”。不改共享调度协议、归档库或全局偏好缓存。

## 2. 已有证据与候选为何值得再试

依据[R3报告](../evidence/windows-reader-startup-r3-2026-11-10.md)、其 `.test-tmp/r3/{Bi1,Bi2,Ci1,Ci2}/state.json` 与 `r3-mechanism.json`。同目标906004/index4的区间由同次原始单调时钟相减，单位ms：

| 样本 | prepare退出 → copy开始 | copy本身 | copy结束 → store返回 | prepare退出 → store返回 |
| --- | ---: | ---: | ---: | ---: |
| Bi1 | 11.450 | 1.558 | 1.398 | 14.407 |
| Bi2 | 9.781 | 1.627 | 3.856 | 15.264 |
| Ci1 | 11.879 | 3.380 | 0.536 | 15.795 |
| Ci2 | 11.578 | 3.462 | 0.691 | 15.731 |

这些事件在各样本中记录于同一个 worker；同线程不代表没有挂起、排队或其他工作。copy包括lease校验、归档读取/解压和partial文件写入；copy结束后还存在长度读取、提交及清理。最大空档位于copy之前，尚未细分到单个函数，不证明缓存协议应删减。

R3两份控制的目标准入→RUNNER_WORK_TAKEN约32.3/39.3ms，存在让普通准备与首屏组合重叠的机会。这个窗口包含必要执行，不是可回收承诺。特别是 RUNNER_WORK_TAKEN 早于 COMPOSE_SLOT_APPLY；后者不是所有viewport调度的触发点，不能用错marker构造等待。

历史限制及本轮新增条件：

- [D5](../evidence/desktop-reader-d5-2026-11-06.md)全下载路由提前准备，单页反向未重现、双页OFF首次退化。当前固定单页轻量首屏已经把完整model移至首图之后，这是新的竞争环境；仍须重新证明净收益，不能照搬D5或只删不利模式样本。
- [R2](../evidence/windows-reader-startup-r2-2026-11-09.md)只在已提交pending分支注册，真实页表在首次投影中已到，机制未触发。本轮授权安装在entry/session初次启动的生命周期中，不依赖pending分支或某一轮UI是否恰好看到Loading。
- R3后台确实抢先调用原acquire，但未证稳定冷态收益。原始 `HOLDER_ACQUIRE_LAUNCH` 和 `RUNTIME_READY_LAUNCHED` 位于同一worker、属于同一次调用前后标记，不能当成“UI先启动、后台后启动”两个竞争者；必须看threadName和真正UI的ACQUIRE_REQUEST。本轮不靠增加collector再次尝试这个方向。
- [D7](../evidence/desktop-reader-d7-2026-11-07.md)反例仍有效：不移动完整model订阅，不拆raw session与派生呈现的原子性。
- R3隔离文件Preferences与普通Windows注册表后端不同。本批两臂固定相同后端，结论限该环境，不能从组件值扣除固定偏好成本或直接计算对官方的净差。

## 3. 最小排查：把10–12ms闭合到具体调用

首先复用现有 SOURCE/RAW，不重新采集整套官方或重跑偏好组件校准。沿以下原链确认真实分支：

`ReaderPageMaterializeRunner.runWork` → `CanonicalReaderMaterializeExecutor.materializePage` → resolve/findEncodedPage → Downloading发布 → Desktop fetchEncodedPage → cacheRef/store → writer/extractArchivePage → copy → 原子提交 → store返回 → Ready发布。

在现有默认关闭的有界 `ReaderStartupObservation` 内补最少Desktop边界：findEncodedPage开始/返回及hit/miss；fetchEncodedPage入口；store调用前；writer入口。结合已有prepare、copy、store返回、Ready发布事件，分别界定URL/缓存查询、fetch前置、写门/staging和实际copy；只有这些区间仍无法解释主要成本时，再定向增加一个对应内层边界。不要默认给全部函数、文件API或共享runner增加探针。

E1另记录授权ARMED、真正计划ACCEPTED、拒绝/撤销，以及真实UI viewport需求到达的边界。首次activate时把授权绑定到实际generation，不写死1；初始需求与UI需求必须区分来源，不能拿共同settle helper的第一条WAIT充当“UI已到达”。ACCEPTED须携带原scheduler分配的请求key，并与目标WORK_TAKEN关联；不以计划创建证明物理工作已经开始。

关联至少包含本次opening/session token、章、generation、pageId、attempt、runner requestId与实际ref；在Desktop工作绑定/port处用可选诊断上下文透传，不用跨协程ThreadLocal，不为记录修改共享请求语义。记录原内容路由、backend及已有lease指向的路径摘要，确认实际读的是哪个规范副本；不得新增下载位置查找或额外预读来证明路径。无法精确join的旧事件继续标注时间窗关联。

新事件仅ON时生成关联字符串/读时钟，内存有界，测量区间后导出，不同步写日志。`PREPARE_END`位于finally，表示包括早退/异常/取消在内的退出，不等于成功；成功与否看返回/后续store及Ready事件。线程CPU粗粒度及嵌套/并行wall不能相加。两臂保留相同诊断设施，正式外测OFF。

本批诊断用于说明既有成本与是否成功重叠。即使发现某次文件检查较慢，也不自动实施缓存旁路、减少原子提交、切换库或追加第二候选；留下该函数、条件与后续真实失败测试入口即可。

## 4. 唯一候选E1：entry拥有的一次初始普通内容需求

### 4.1 安装与触发

1. 优先在 `DesktopReaderRuntimeFactory.createScreenEntry` 正常Screen调用路径，为本次opening构造一个有界授权。必须在可能完成页表的 `session.start/activate`之前安装；若现有构造顺序无法满足，提供同锁“安装并检查当前Loaded”的一次入口。直接`createRuntime/createModel`等其他调用者默认关闭，不能只在Test Mode生效。
2. 资格复用现有fixed-single语义和真实viewer覆盖：RTL/LTR、非AUTO、非dual、非split，且正常Screen明确使用轻量首屏、初始context已确认下载。只有加载后才知道下载、初始准入不成立的入口暂不纳入，避免在原完整model首屏上重试D5。捕获本次所需最小设置/打开意图；不为资格重复构造完整ReaderState或引入全局缓存。记录额外设置读取与entry同步耗时，使新增前置成本进入B/C总时间。用户设置更新仍由原UI链处理。
3. 安装不等于允许I/O。只有目标页表Loaded且路由确认已下载/本地CBZ，才按原恢复语义解析目标；不能以sourceId=0、旧isDownloaded Boolean或文件扩展名猜测。首次已Loaded、初始投影期间到达、安装后晚到三类时序都须处理。
4. 复用 `resolveInitialPage`/`invalidSavedPage` 的语义，覆盖首页、合法恢复页、LAST、空章和非法恢复。必要纯helper可做最小抽取共享调用，不将ReaderState或Composable依赖倒灌session。非法保存位置沿原恢复反馈，不先猜第0页读取。
5. 在session原锁内再次核对opening token、初始activation、章、generation、目标页与attempt，至多提交一次无进度普通需求；复用原core/scheduler和settle helper。已Ready/inflight不得重复物理准备；真实viewport若已先确定目标，就接用其计划，不能让迟到的初始请求覆盖用户导航。
6. 页表接受后与安装时已Loaded的竞态使用同一串行化规则，不增加另一个StateFlow轮询/collector往返。resolver及资格读取在锁外准备，锁内只做纯身份/目标与计划操作；普通异常拒绝优化并继续正常页表发布，CancellationException保留取消语义。

### 4.2 取消、接用与资源

- 授权只属于初次opening；后续activate（包括同章重开）、换代、退出、entry dispose和页表失败使旧授权失效。已发出的普通请求沿原runner真实取消/迟到拒收，不能伪称cancel后物理I/O已经退出。
- 授权前设置已改变须重新判资格；授权后发生模式切换或导航，由原真实viewport接管/替换计划，不能用捕获的单页目标回写UI或进度。完整入口提前打开也不能重放初始需求。
- 不在session锁内增加磁盘I/O或任意外部回调。现有`applySchedulePlanLocked→runner.cancel`即使取消集为空也可能pump，不得声称所有pump都在锁外；审查实际锁图、沿用或最小适配已验证顺序，不顺便重写调度器。
- 实际viewport沿原显示owner读取已经准备的同一encoded内容；保留原copy→partial→长度/配额校验→原子提交→Ready顺序。cache缺失反馈修复保留；不保留未提交partial作为可显示内容。
- 首图前没有新增decode、图片lease、FIRST、已读或进度；只有真实draw可触发原确认。资源所有者仍为原entry/runtime/session/store/holder，不建立第二个生命周期体系。

### 4.3 最小真实RED/GREEN

首个RED使用production factory/session/core/runner与真实CBZ/文件store：创建符合资格的正常entry，但暂不挂载Compose；页表Loaded后应通过原scheduler准备同一恢复页，而旧代码仍Queued。断言目标物理copy一次、原内容OPEN/DECODE/FIRST/进度均为0；随后真实Screen/viewport接用同一个ref，核目标像素、只开流/解码一次及正常进度。合成CBZ可作功能夹具，不能冒充正式史莱姆性能内容。

在同一focused集合增加/复用：

- 三种页表到达时序，首次合法恢复、LAST、空章/非法位置；真正普通Screen接线启用，直接完整model调用和不合格模式/路由不提前请求。
- UI目标先到、重复安装/重复Loaded、同章重开、切章、retry、退出及非协作迟到；章/代/attempt/ref替换不污染当前结果，不重复copy/解码。
- 在线未知路由不提前HTTP；双页OFF/ON、AUTO、条漫、拆页负控不装新需求；原scheduler优先级/并发/有限预取仍在预算内，列出实际邻页请求。
- 文件提交失败/内容缺失沿原反馈；F11/Space/Ctrl的早期输入、真实单页往返、完整model交接及释放不退化。

主要修改限定Desktop factory/entry/session及必要纯恢复helper和诊断port。现有类为 `mihon.desktop.reader.DesktopReaderRuntimeFactoryTest`、`mihon.desktop.reader.DesktopReaderSessionIntegrationTest`、`mihon.desktop.ui.reader.ReaderFirstCompositionIntegrationTest`；选择实际受影响的方法，并复用观测及缓存缺失相关用例。必须测试真实production wiring，不复制实现或扫描源码断言功能成立。

只跑新增/真实受影响的focused、修改文件格式及diff检查。不执行模块/仓库全量、Android、正式发布矩阵或不受影响的算法回归。共享core/scheduler若实际需要改变，先停止扩大实现并说明为何Desktop局部方案不足，不能默认获得跨平台改写授权。

## 5. 冻结、实测与裁决

先完成真实红绿、一次独立审查和必要修复复审，冻结控制B0（本基线加相同诊断）与C4（仅加E1）的两个production preview。正常场景之外的真实bug若必须修复，独立证明并让两臂共有。coordinator同树串行构建，不临时替换构建目录源文件选臂；优先固定提交/独立快照，记录源码、完整差异、manifest、EXE、JDK/CDS及配置身份。不分配发布版本。

规范内容沿用史莱姆第04卷、187页、index4地图、RTL；CBZ SHA256 `b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677`，005.webp SHA256 `3b43ed0beb3f6f1b832e57ac5ad7c830c00850c8b79fa0c9a708ebf08ccbce14`。核实际路由及两份副本摘要，固定同种PreferencesFactory、等价独立profile/数据库、全部阅读设置。初始化和候选新增资格读取都计入正常章行回调后的时间；不提前打开目标/解码器或专门预热。隔离文件后端的结果继续限定该环境。

所有GUI集中在末尾、仅副屏、主代理唯一输入；重新核窗口/PID/profile、bounds、DPI/scale，不沿用旧ID或拓扑。正常书架→详情→章行单击；首图前不GET、不离线读帧、不等待编译。开始前备齐采集/分析/退出材料。最后一次原生观察后按项目规则精确关停并立即报告；需继续改代码/构建/深入分析则先结束桌面批次，后续执行回合从冻结证据继续。

先B0/C4各2份新进程内部ON采样，无WGC。必须同时确认：授权真实安装并消费；候选目标工作实际被原runner取出且早于第一条真实UI viewport需求；同章/代/attempt/ref接用；无提前解码/FIRST/进度。只出现ARM/计划或只比COMPOSE_SLOT_APPLY早不算成功。若两份候选不命中，或新增entry成本抵消关键路径前移，停止尚未开始的正式外测，不自动切换第二候选。

机制成立且正确性通过后，正式OFF外测按三组顺序B0/C4、C4/B0、B0/C4；每个新进程各一次首次打开及一次同进程再次打开，共12次。保留每份有效/无效/中断原件；技术失败可注明后补完整配对，不因慢而重采择优。中断后如实记录跨批次顺序，不把替代后远隔样本描述为紧邻正反对照。

使用现有WGC原PTS/QPC和章行锚点；工具输入包络与callback分别列，WGC不称物理扫描。逐份核正确地图的相邻原帧，R/F/K及最长/累计无内容分列，采到F后至少1秒；gap跨越预算则记未确认，不插值。最后单页往返、F11进出和双页OFF/ON页对/往返作限定正确性负控，不扩成三模式性能矩阵。状态/错误/进度以真实reader与持久值核对。

采用需真实机制成立、focused/实际正确性通过，且首次F或K在正反次序均有可辨改善，其他指标及暖态无可辨退化。配对差仍用 `[C下界−B上界, C上界−B下界]`，跨0为未分辨；不将内部OPEN提前当作外部收益，也不报告P95。全体验另按[共同基准](../evidence/reader-startup-performance-baseline-2026-11-02.md)的R≤100ms/K≤60ms及首漫画要求判定，不宣称普通Windows已追平官方。

不满足采用条件则精确撤回E1的产品/仅候选测试，保留已有修复及必要默认关闭诊断、真实数据和裁决。未改最终代码可按源码身份复用有效focused；有最终变化只补受影响focused，不因收口再跑全量。观测不足与没有收益区分报告；本批不自动追加JFR、样本、候选或缓存协议改造。

## 6. 收益预期、分工和roadmap

候选E1预计净收益暂按**10–25ms**排序，可能为0或退化。已观察32–39ms“准入→工作取出”是机会窗口；约10–12ms拷贝前段主要是被尝试提前重叠的既有工作，不是承诺删除的时间。两个窗口重叠，不能相加。正式收益只取新同条件外测；日常注册表后端绝对效果仍需另有代表性证据，不在本批重写测试配置后端。

- [x] **R4.0 固定基线与归因边界**：核源码/原件/实际路由，复算第2节；补第3节最少事件及关联身份、验证OFF开销边界。交付明确的区间表与调用链，不凭一次wall判断单函数CPU原因。
- [x] **R4.1 真实入口RED与授权契约**：正常entry未挂载UI时，目标页应由原scheduler准备；旧实现真实失败。固定三个到达时序、纯目标解析、默认关闭调用者和取消/接用断言，不把编译/环境错当RED。
- [x] **R4.2 E1最小实现与focused GREEN**：在entry/session首次生命周期安装一次授权；仅可靠下载固定单页触发，保持原预算/身份/提交/显示/进度链。完成第4节focused和格式；若需扩大共享机制先报告具体边界。
- [x] **R4.3 独立审查和两包冻结**：主代理审真实接线、资格读取成本、授权失效、实际锁/pump、物理退出与用户输入；必要修复复审一次。串行生成B0/C4，固定源码/manifest/EXE，准备全部副屏材料。
- [x] **R4.4 副屏机制核验与有条件外测**：先四份内部样本，按真实UI需求而非slot apply判前移；门槛成立才做12次正式冷/暖对照和限定正确性。逐次保留区间、实际顺序、异常与所有原件。
- [ ] **R4.5 采用或撤回及交付**：更新唯一报告 `docs/evidence/windows-reader-startup-r4-2026-11-10.md`、本页与父roadmap；报告各真实调用区间、可确认净收益/限制、最终源码与测试证据。精确清理自建进程，提交本任务文件并报告hash；未获本次功能验收及推送指令时不推送、不发布、不更新公共能力说明。

由一名GPT-6.1 Sol medium负责production、真实红绿和串行Gradle；一名Luna medium负责限定原件提取、采集准备/离线复算；主代理规划、一次独立审查及唯一桌面输入。不复制整轮历史、不并发改相同文件或抢同一桌面；新证据形成后只传必要差异。预计实施60–90分钟，主要成本是focused接线、两包和一批采集；环境异常另记。最多两名子代理、一次独立审查与一次必要修复复审，默认无模块全量。用量/金额按实际可得数据报告，不预填或将未知记0。本次设计阶段仅文档核验，不执行上述实施预算。

中断记录：[唯一R4报告](../evidence/windows-reader-startup-r4-2026-11-10.md#副屏中断与当前交付)。B0i1未进入章节；四份ON和正式OFF均未完成。仅在用户明确恢复后续验，不因任务结束重跑focused/构建；候选仍待采用或精确撤回。

续测：四份ON完成，候选工作早于真实UI44.0631/50.2605ms，真实同ref接用。B01首次外部原件有效，但暖态遇目标窗口用户输入，结果未知；配对矩阵与正确性负控未完成，详见唯一报告续测段。

执行记录：[唯一R4报告](../evidence/windows-reader-startup-r4-2026-11-10.md)。不采用E1：机制成立且本次正确性检查通过，但首次打开改善未同时在正反顺序分辨。候选源码目前仍在实验分支，尚未撤回；R4.5与父D14保持未完成。按原生验收收尾规则，下一轮只精确撤回E1并补受影响focused，不再重跑本轮性能矩阵。
