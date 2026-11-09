# Windows 首开 R3：内容 Ready 到解码启动的排查与优化

日期：2026-11-10。状态：仅完成设计，实施、构建和原生采样尚未开始。本计划是[首开父 roadmap](2026-11-05-desktop-reader-first-open.md)的 D13；不把设计完成记作性能改善。

## 1. 目标、基线和范围

首要目标是解除“目标图片内容已 Ready，仍须返回 Compose 重组并提交后才能开始解码”的依赖。先证明真实触发、正确性和同条件净收益，再决定采用。保持正确页、图像质量、缩放、进度、资源预算和已有输入修复；整体体验仍按[双端基准](../evidence/reader-startup-performance-baseline-2026-11-02.md)分别报告响应 R、正确漫画首见 F、最长及累计无内容间隔 K。

设计所读源码为 `201b4cc14e5759b53605f3e290d3137ae0e454cc`，工作树 `D:/Codex/worktrees/mihon-windows-reader-source-driven-20261109`。产品及测试源码等同输入修复提交 `5aed3286efebb44674cc5d576edd6695ba98d242`；R2 性能差异已经撤回。开始实施须重新固定实际源码 SHA、未提交差异、分支和配置身份。若改用最新 main，只核对并适配本计划涉及的入口、owner、session、store 和测试，不覆盖已合入功能，也不混入其他任务提交。

本批唯一性能候选 A 限于已确认下载、固定 RTL/LTR、单页、非 AUTO、无自动拆页、同一 opening 的真实轻量首屏。双页开/关匹配、在线、条漫和不合格入口保持原路径。不得迁移完整 model 的 session 订阅，不改共享配对算法、解码尺寸/颜色/原图事实、并发3与 forward4/back1，不新增隐藏 holder、整章预热、跨打开缓存或人工转场。

## 2. 已有事实与仍缺的边界

来源为[R2 报告](../evidence/windows-reader-startup-r2-2026-11-09.md)及其工作树内 `.test-tmp/source-driven-v1/R2Ci1/state.json`、`R2Ci2/state.json`。两份均为新进程正常详情章行单击、史莱姆第04卷、第5/187页地图、共同隔离文件 Preferences 后端。下表是同一次章行回调后的非重叠区间，单位 ms：

| 区间 | R2Ci1 | R2Ci2 | 已能作出的判断 |
| --- | ---: | ---: | --- |
| 页表就绪 → holder 构造开始 | 20.482 | 22.715 | 包含首屏组合、准入及其间执行，不能全算等待 |
| holder 构造 | 0.584 | 0.763 | 不是几十毫秒的容器构造瓶颈 |
| holder 已建 → viewport 调度入口 | 12.118 | 14.607 | 普通内容需求仍等界面提交 |
| 调度入口 → 目标归档 copy 开始 | 13.535 | 21.050 | 包含约4.3/4.8ms调度及其后工作，须继续拆分 |
| 目标归档 copy | 1.600 | 1.235 | 已包含目标图片归档读取/解压与写入；并非等到 OPEN_PAGE 才读漫画 |
| copy 结束 → holder 绑定内容 | 21.080 | 10.915 | 包含缓存提交、Ready 发布和 UI 消费，不能全归 Compose |
| 绑定内容 → 显示 OPEN_PAGE | 3.460 | 4.520 | 后续 acquire、任务启动和缓存开流 |
| **页表 → 显示 OPEN_PAGE 合计** | **72.860** | **75.805** | 不是全部可回收收益，也不是相对官方的净差 |

实际调用关系已由源码确认：

`ReaderFirstComposition` 提交 viewport 需求 → 原 scheduler/materializer → 归档图片缓存提交 → session 发布目标页 Ready → Compose 再次看到 Ready 后生成 decodeKey、bindContent → DisposableEffect 调用 acquire → 原 region/content/pipeline 读缓存、解码 → holder Ready → 原绘制确认。

源码入口：`ui/reader/ReaderFirstComposition.kt:155,268,296`、`ui/reader/ReaderPresentationImage.kt:83,103,121`、`reader/DesktopReaderSession.kt:779,815`、`reader/DesktopReaderEncodedPageStore.kt:263`、`reader/DesktopReaderPresentationImageOwner.kt:244,480`；均相对 `app-desktop/src/main/kotlin/mihon/desktop/`。行号只描述本设计基线，执行时核对实际内容。

必须吸收的反例：

- [D5](../evidence/desktop-reader-d5-2026-11-06.md)：全下载路由提前 encoded 准备没有稳定净收益，双页 OFF 首次退化。不能把它作为本批默认实现。
- [D7](../evidence/desktop-reader-d7-2026-11-07.md)：完整 model 订阅迁入 IO 未通过外测，还涉及 raw session 与派生身份逐 emission 一致性。本批不移动这条订阅。
- R2：只给已提交的 pending 分支注册，真实页表在首次投影期间已到，ARMED/TRIGGERED 均未出现。A 必须适用于首次组合已经 Loaded 的真实入口，而非依赖 pending 分支。
- [偏好校准](../evidence/desktop-reader-p0-preferences-calibration-2026-11-09.md)：隔离文件后端每次 get 都读盘，普通 Windows 是注册表。组件差值不能从端到端直接扣除；本批同后端 A/B 只支持该环境内的收益，不宣称普通启动绝对延迟或已追平官方。

## 3. 排查设计：只补会改变决策的边界

复用默认关闭、有界内存记录的 `ReaderStartupObservation`。以本次 runtime/open token、章、generation、目标页和 attempt 关联；涉及 encoded key/ref/provenance 时用可核对的本地身份，不能只用 pageIndex。避免同进程再次打开 generation 再次为1时混合事件，也不能用旧 copy 的 chapter/detail 强行推定新任务的调度因果。

补齐以下逻辑边界；可复用现有事件就不增加，最终以最少事件闭合区间，不对所有函数埋点：

1. **首屏投影**：initialReaderState 开始/结束、目标准入；分清设置读取/准入之前的执行与之后的 UI 工作。
2. **内容准备**：目标工作被 Desktop runner port 取出、进入 prepare、prepare 返回，以及 encoded store 调用返回。已有目标 copy begin/end 保留。copy end → store return 是缓存提交收尾，不能混入 Ready 后 UI 等待。
3. **Ready 交接**：同目标 materialization Ready 的 session 发布前/后、Compose 首次看到该目标 Ready。与已存在的 CONTENT_BOUND、ACQUIRE、OPEN 配对，直接计算发布 → UI 消费 → 绑定/启动。
4. **候选 A**：真实 holder 注册生效、运行时收到目标 Ready、成功绑定并发起 acquire、撤销/拒绝。事件须在实际动作位置记录，不能靠注册函数被调用就声称机制触发。

初始新增事件上限按每个目标约12–16条控制；不新增永久采样系统、同步文件日志、热路径堆栈或独立 HTTP 接口。正常运行仍不读诊断时钟。记录自身持锁时间和 CPU 只能按同线程、非嵌套区间解释，不能把不同线程工作相加为总耗时。

基线 B 与候选 C 带相同诊断设施；内部采样细观测 ON，正式 F/K 对照 OFF。复用已有 dropped、production 锚点和采集能力，不假设探针无扰动、不扣固定“探针成本”。若 prepare 前仍有显著空档，只先报告调度/permit/初始化的合并区间；只有该空档影响下一决策且现有边界不足时，才考虑一次已有 JFR 工具的有界线程诊断。不在本批默认增加共享 runner hook、替换库或下载工具。

## 4. 首选优化 A：已提交 holder 的运行时 Ready 接续

### 4.1 行为与所有权

1. 沿现有单页 startup 接线取得真实 Queued holder；只有该 holder 的 composition 已提交，才授予本次有限接续资格。注册不依赖 `pending == true`。首次已 Loaded、投影期间到达、提交后才 Ready 三种时序都须成立。
2. 在既有 runtime IO scope 内，为这个已提交目标观察 session StateFlow。直接消费 StateFlow 当前值及后续值，不能用“先读 value，再另订阅事件”制造漏接窗口；不用轮询、定时器或 `CoroutineStart.UNDISPATCHED` 把工作带入 session 发布锁。
3. 只接受同 opening、章、generation、pageId、attempt 的合法 Ready，并复用 `readerPresentationDecodeKey` 所需完整身份、尺寸和 provenance。通过原 owner 的 `bindContent` / `acquire` 启动；不另建缓存、内容读取器、解码器或第二 holder。
4. 保留原 Compose 绑定/启动路径作为幂等接用。运行时和 UI 竞争绑定必须使用同一 holder 的原锁与内容身份校验；不一致的 key 不得覆盖已有绑定或将旧资产记成新图。若现有 owner API 无法安全覆盖内容替换竞态，先以真实失败测试证明缺口，再做该接线需要的最小适配，不能默认接口已经线程安全。
5. 注册令牌在 dispose、换章、换代、换 attempt、离开限定入口或完整入口交接时先失效，再取消自有观察任务。回调实际 bind/acquire 前再次验证当前令牌、owner 和完整请求；不能靠一次旧状态采样授权后续操作。观察任务取消不能误关仍由原真实 viewport 持有的图片 lease。
6. 绑定与启动不得在 session/lifecycle 锁内执行；列出实际锁顺序，避免新增 session→owner→session 的环。继续使用原预算、当前请求取消与失败重试。普通异常走原失败/回退反馈，CancellationException 沿原取消语义传播，不静默吞掉。
7. 目标 materialization Ready 必须晚于原 store 原子提交完成；不能读取 partial 文件。实际绘制前不报告 FIRST、不记录已读/进度。完整 model 创建和 visual handoff 仍由原实际 draw/身份条件决定。

该方案与 D5 的区别是**没有更早调度 encoded 准备**；与 D7 的区别是**只接续一个真实已提交 holder，不迁移完整 model 或派生状态投影**。它使后台准备完成后可以继续原解码链，绘图仍由正常 UI 负责。

### 4.2 真实 RED/GREEN 与最小验证

首个 RED 使用真实 Screen/session/materializer、文件 encoded store、owner 与 Skia：让 Queued holder 确实 apply 后，暂缓下一轮 Compose 重组，同时让目标 encoded 准备完成。旧实现须因“未启动原目标 OPEN/解码”失败；候选应在 UI 下一次重组之前，沿同一 holder 进入原读取/解码链。恢复正常帧后核对目标真实像素、同 holder、一次内容开流/解码以及正常进度。用生产事件/计数和显式异步闸门证明，不用源码扫描或固定 sleep 当性能证据。

随后以同一 focused 范围覆盖：

- 安装前已 Ready、安装后 Ready、页表在初始投影期间到达；重复 emission、UI 与观察者竞争不重复请求。
- 错章/代/页/attempt，以及同页同 attempt 的 ref/provenance 替换；退出、retry、完整入口提前打开、非协作旧任务迟到，均不绑定旧图、不泄漏任务/lease。
- 缓存提交失败/缺失内容沿原错误反馈；FIRST/进度仍晚于真实绘出；正常单页往返与 full model 交接保持。
- 在线、双页 OFF/ON、AUTO、拆页等入口不安装此接续；已 Ready 的目录页/缓存命中不重复读取；原 F11、Space、Ctrl 缩放的早期输入不被重新延后。

优先扩展 `ReaderFirstCompositionIntegrationTest`，复用 `DesktopReaderPresentationImageOwnerTest`、`presentation.StartupSingleViewportTest` 及观测测试的受影响用例。真实 factory/owner 接线不能被只测独立 helper 替代。只运行新增及实际受影响的 focused 类/选择器、修改文件格式和 `git diff --check`，不跑 reader 模块全量、仓库全量、Android 或正式发布矩阵。已有两项 rapid taps 双页失败先按路径判关联，不自动扩修或宣称全绿。

## 5. 测量、采用与停止

### 5.1 冻结与集中采集

先完成诊断接线、A 的真实红绿、一次独立审查和必要修复复审，再冻结两个 production preview：B 为基线加相同观测，C 只增加 A。使用项目 coordinator 串行构建，不分配正式版本；固定源码/工作树差异、manifest、完整应用身份、JDK、CDS、实际 PreferencesFactory、配置和窗口。不能把带已撤回 R2 候选的旧包当本批控制。

内容沿用原件：史莱姆第04卷、187页、index4地图、RTL；CBZ SHA256 `b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677`，目标 `005.webp` SHA256 `3b43ed0beb3f6f1b832e57ac5ad7c830c00850c8b79fa0c9a708ebf08ccbce14`。准备前核验原件存在和摘要，缺失先按既有研究归档恢复；不得换合成图冒充同内容性能结果。

GUI 统一放在本轮末尾、只在副屏。每份重新核对当前主/副屏 bounds、scale/DPI、实际窗口完整落在副屏，不能沿用旧窗口ID/PID/拓扑。经正常书架/详情进入后，对章节行单击一次；不提前打开目标或预热阅读器，首图前不 GET state，不同时跑离线帧分析。

先各取 B/C 两份新进程内部样本，细观测 ON、不运行 WGC。原件全部保留，核对实际 target/attempt、dropped、缓存提交→Ready→bind/acquire→OPEN/解码、首次实际绘出。C 必须真实命中 A，并在原件中看到运行时成功接续；不把未触发、截图正确或合成 RED/GREEN当作首开收益。

机制命中且正确性成立，才继续预备好的正式外测，细观测 OFF：三组新进程对照，顺序 B/C、C/B、B/C；每个进程先正常首次打开，再正常返回详情并打开同章同页一次。合计 B/C 各3次首次、3次同进程再次，共12次打开，满足共同基准的最小冷/暖次数，不估算 P95。两臂分别使用同种后端、等价独立数据副本和相同设置，核对退出保存后仍恢复 index4。进程冷态与磁盘缓存分列，不要求或伪称清空系统缓存。

沿用 WGC 原帧/QPC、章行回调及工具输入包络；R/F/K 分列，WGC不称物理扫描。必须关联实际目标像素及原帧间隙，不以内部 draw 或窗口背景首帧替代 F。采集到首图后至少1秒以检查再次消失。最后集中核对单页往返、F11进出和双页 OFF/ON原有页对/往返；双页为负控正确性，不扩为双页性能矩阵。若改动后来实际影响双页请求准入，停止沿用这个范围，先重新限定传播风险。

按 Desktop 桌面归还规则，最后一次原生观察后只做精确关停、简短数据落盘与已准备好的收口，立即报告。需要再写代码、构建或深入分析时先结束该桌面批次；下一执行回合续做，不能在占用桌面时等待。应用、WGC及QPC helper按本批进程身份收尾，不全局杀Java或用户程序。

### 5.2 裁决规则

- **采用**：真实接续已触发、focused和实际目标/恢复/输入/资源正确；F或K至少一项在正反次序均有可辨改善，其他指标与暖态无可辨退化。保存逐样本区间及配对差范围，不能只报平均值或最优值。新旧帧区间重叠则记未分辨。
- **整体达标另判**：R≤100ms、K≤60ms按各有效样本及误差界限判定；未满足仍可报告本地机制收益，但不能宣布追平官方。现有文件后端数据不能计算普通Windows对官方的精确净差。
- **机制不成立/无净收益**：保留全部失败和慢样本，精确撤回本批性能代码；保留有维护价值的默认关闭诊断、行为证据和研究结论。不得因慢而补采择优，也不自动换第二个候选。
- **无法分辨**：明确剩余空档与采样分辨率；追加样本/更重诊断须针对具体未解决问题说明最小成本，不以“更放心”启动扩大验证。

## 6. 后续方向与收益预期

下列数字只用于排序，均为待证假设；观察窗口含必要工作，不是收益承诺，也不能相加：

| 顺序 | 方向 | 预计净收益假设 | 启动条件及边界 |
| --- | --- | --- | --- |
| A，本批 | Ready 后已提交 holder 在运行时接续原解码 | 约5–15ms，也可能0 | 用新边界确认减少 UI 折返且未被其他阶段抵消；不改变 encoded 准入 |
| B，后续 | 更早授权固定单页普通 encoded 需求 | 约10–25ms，也可能退化 | 新记录证明首屏提交前需求尚未发出且为主导空档；另设计覆盖首次已Loaded/晚页表的生命周期授权，复用原scheduler，吸收D5/R2反例，不照搬全路由提前准备 |
| C，后续 | 精简准入到 holder 的同步工作 | 约0–10ms，尚无函数独占证据 | 先细分现存20–23ms；只有实际冗余才做单次快照/投影复用，保留设置变更语义；隔离文件后端专属收益单列，不直接改全局偏好缓存 |

B/C不是本批自动实施任务；A完成后按新基线重新决定，不为凑计划保留无收益代码。Skia解码与实际绘出另有成本，本批不删除原图证据/旋转/颜色处理；若将来研究须先补其必要性与真实成本证据并读取配对研究档案。

## 7. 执行 roadmap、分工与交付

以下均未执行；从第一个未勾选项继续。任务完成表示本批约定交付及验证完成，采样停止/候选撤回也须如实写裁决，不把未执行外测勾成通过。

- [ ] **R3.0 固定输入与补齐诊断边界**：核对源码/实例/内容与旧反例；在现有默认关闭观测内闭合第3节区间，补受影响观测/接线 focused，验证 OFF 不新增热路径时钟/文件工作；固定 B 的源码。交付事件表、关联键与精确控制差异，不重做全套官方/偏好组件校准。
- [ ] **R3.1 A 的真实行为 RED**：已有真实 queued holder 提交后暂缓 UI 重组，目标后台 Ready；旧生产路径必须因未启动目标读取/解码而失败。固定失败原因、真实原链及同 holder 断言；测试环境/编译失败不算 RED。
- [ ] **R3.2 A 的最小实现与 GREEN**：只在限定单页已提交 holder 上接续 Ready；完成第4节时序、幂等、身份、取消、错误、负控和实际像素/进度回归，修改文件格式通过。交付实际命令/退出码/结果、锁和任务归属图，不扩大算法或模式。
- [ ] **R3.3 独立审查与两包冻结**：主代理一次检查 production wiring、锁顺序、内容替换、注册失效、lease与正常输入；必要问题交原Sol聚焦修复复审。串行生成 B/C preview，固定精确源码和实际EXE/manifest，备齐原生采集与退出方案。
- [ ] **R3.4 副屏机制核验及有条件外测**：先 B/C 各2份内部样本；只有A实际触发且正确才继续三组冷/暖外测和限定正确性。逐份保留原件/异常/区间、记录R/F/K与完整状态，不为未触发路径继续外测。
- [ ] **R3.5 裁决与提交收口**：按第5节采用或精确撤回，更新唯一执行报告 `docs/evidence/windows-reader-startup-r3-2026-11-10.md`、本计划及父roadmap；清理自建进程，仅提交本任务改动，报告hash/实际产物/未通过项。没有用户验收与推送指令时不推送、不发布、不更新公共能力说明；B/C后续方向保留其启动条件。

实施分工：一名 GPT-6.1 Sol medium 负责主要产品/测试修改和串行Gradle；一名 Luna medium 负责旧数据复算、限定采集准备及离线批处理，不与Sol并发改同一产品文件。主代理负责范围、一次独立审查和唯一桌面输入；子代理不操作同一桌面。所有人保护已有未提交改动，不全历史重读或另建验收系统。

实施成本预估45–90分钟，主要为真实接线红绿、两个preview和一批副屏采集，环境准备另记。最多两个子代理、一次独立审查及一次必要修复复审；focused失败可在同一范围修复复验，不触发模块全量。API token/金额只能按实际可取得的用量与计价报告，未知项不记为0。本次设计阶段只做文档链接/内容和diff核验，不执行上述实施预算。
