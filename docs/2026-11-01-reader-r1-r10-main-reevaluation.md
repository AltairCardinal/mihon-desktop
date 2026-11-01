# R1～R10 历史保留项：当前主干逐项复评

日期：2026-11-01（Asia/Shanghai）。用户要求：逐一重新评估，收益明确为正才合入本地主干；不确定则交付数据。不推送远端。

## 固定范围与接手状态

- 起始本地 main 基线：`e8beb212a0ead306f6441b130c40d4b0a6c660f6`。逐项实验冻结于该基线；共享组合前安全快进至 `ffaca69a9bde4dbe0c3bd5876697351bd4e0d89f`；共享通过后、原生检查前再安全快进至本地 main `4d80f3b01d58034f15db33bb6dbf29f8a909ca0e`（后次仅批量书签与对应测试／文档4文件）。期间其他会话未修改本轮四个生产文件或验证源，没有构建依赖变化；原共享证据仍适用，不为无关文件重跑。
- 隔离工作树：`D:/Codex/worktrees/reader-r1-r10-reevaluation-20261101`，分支 `codex/reader-r1-r10-reevaluation-20261101`。隔离用于避免构建触及主工作区用户已有的 AppVersion 改动及与代理并行工作互相覆盖。
- 主工作区已有 `app-desktop/src/main/kotlin/mihon/desktop/AppVersion.kt` 修改和 `.codex-remote-attachments/` 未跟踪目录；均不纳入、不覆盖。
- Sol medium 独占本树相关 production／行为测试；Luna xhigh 独占本树 ignored 验证工具、重型任务串行执行及原件整理；根代理独占本文、独立审查、采用裁定和 Git 整合。
- 等待期间 main 再前进至 `b983d924b2559fae56654f9cdd7370d48501179e`，包含新的读取诊断及 Desktop 显示接线。本轮在该 SHA 冻结最终集成基线：134个主干变动路径与本任务14路径仅架构文档相交，四个生产文件的基线 blob逐项未变。本任务架构追加单独备份／暂移后安全快进，再原样追加；新主干原文及本任务12个 Kotlin 字节均保留。原公式／采样／调度证据复用，仅补新真实 IO 路径 C-app4 和最新显示接线 native2，不扩大为外部功能验收。
- C补验通过后再快进至 `8b16f1d61ca8d2f16418b6727cfd287ab8f2a39f`：仅6个说明／候选版本metadata文件，不含任何产品 Kotlin 或依赖变化；不因此重复有效 C／共享／成本结果，原生检查从此最新快照执行。
- 不纳入扩窗、C-SL、Strong、checkpoint、NoOp 清理、弱缓存、UI 调度及绘制协议实验；不新增公共能力说明，不打包、不安装、不运行完整模块或正式 50 条矩阵。

## 采用标准与证据边界

1. 先在当前 production 上确认相应真实行为 RED，再做最小实现；正确性、身份、取消／实际退出和真实接线 focused 范围必须通过。旧研究证据只作来源，不冒充当前通过。
2. 每项保留独立对照，固定输入与协议，不按结果搜索参数。成本测量区分准备、核心操作、完整生命周期、线程分配、IO/API 次数和完整正确 draw；不同指标不能相互代替。
3. 明确正收益指：当前实现有可复现的省工／省资源效果，并经过相关生命周期及消费者验证；不能把少一次循环直接称为翻页更快。若新增维护、回退或真实消费成本抵消收益，或数据方向不稳定，则标为不确定，保持隔离。
4. 核心成本以三次独立 JVM 的同轨迹对照为主要数据；任何正确性失败、后台异常、未退出任务或来源漂移都不算有效性能样本。已有效结果不因阶段结束重复运行。
5. 小规模真实绘制仅覆盖当前相关集成与明显退化风险，不替代原 50 条、100ms 目标或设备／发布验收；无法分离的绘制收益明确记未知。
6. 只有完成根代理独审并裁为保留的 production 净差异及其测试进入本地 main。不确定项保留原件和隔离来源，禁止整支研究合并。主干若被其他会话推进，先核实际差异再整合。

## 逐任务清单

| ID | 机制与最小实现边界 | 必要逐任务验证 | 状态／决定 |
|---|---|---|---|
| A1 | R8：`SharedSpreadMatching.score` 定长数组与非负部分和提前拒绝；不改变阈值、颜色归一化、方向和亮暗／方差规则 | 当前 score 样本读取量 RED、独立旧公式逐值对照、阈值／透明度／方向反例；真实评分成本及代表性无早退输入 | 逐项及最终组合通过；裁保留 |
| A2 | R7：认证与证书复核复用当前 owner 已有 content／ordinal 映射；不缓存动态事实 | 真实 Session 额外遍历 RED，身份／内容／重绑失效；冷目标及完整生命周期成本 | 不确定，不合入；最小生产 patch／完整原件隔离保留 |
| A3 | R7：直接精确比较已记录读值，减少临时 wrapper；不缩小读集或信任范围 | 各类读键、Absent／错配和维度字段反例；精确复核分配与生命周期成本 | 不确定，不合入；默认完整服务成本未过门槛，原件隔离保留 |
| A4 | R7：同 owner／identity 且完整证据版本未变时跳过重复校验；所有可见写入推进饱和戳 | 稳定输入检查量 RED；逐写入口、header 更新、失败／清除／驱逐、metadata、设置、重绑、关闭及 Long.MAX_VALUE 回退；暖命中／频繁更新／生命周期成本 | 不确定，不合入；930/930有效，稳定受益／频繁写入回退，完整原件留独立树 |
| B | R10 08a：方图与宽图一样无边缘采样计划，保留真实几何与合法空样本接纳 | 当前域／Desktop／Android 方图 RED→GREEN、四旋转和清理／失败路径；比较采样／region 调用与原图解码，不能把无方图场景算收益 | 域2/Desktop2/Android1及最终组合通过；裁保留 |
| C | R10：Android 已绑定普通 HTTP 请求仅提升优先级而不重建；保持原 jobKey、绑定和实际退出 | 当前 held body 请求数 RED→GREEN；forceRefresh、retry、fact permit、URL／内容／page／generation 改变拒绝复用；真实 HTTP body 次数及退出账本 | 最新接线app4／domain2通过；真实请求3→2、body18354→12236B；裁保留 |
| I | 对通过的独立机制做当前共享／双端必要组合验证；其余不混入 | 复用未受影响结果，补相关实际接线与完整正确 draw；只复验真实变动风险 | 格式、共享8/8、最新HTTP4/4、真实窗口2/2及13次正确完整draw通过 |
| G | 根代理独审，逐项采用／不确定裁定；只将保留项提交并整合本地主干 | 冻结净差异、原件／源码身份、用户改动保护、main 实际包含关系；不推送 | 根独审验收通过；A1+B+C随本文同一提交整合本地main，完整SHA及包含关系记最终Git回执 |

## 结果数据

最终结果：保留 A1／B／C，逐项及必要组合检查通过；A2／A3／A4 均为不确定，不合入，保留数据及隔离代码。最终仅 A1+B+C 四个生产文件净差异、必要回归与内部文档进入本地主干，不推送。A1～A4 固定成本已全部执行并裁定，不再补采。历史来源：A 为 `9701f70764c8de6749e5780225a4e11a11346ebb`，B 为 `a5289fee36cb190c04d57fc89d7d5a693683eb85`，C 为 `07114ecf015dd28c84e57b5782840bb1c6f75244` 的最小请求提升部分。

首轮有效 RED：`reevaluation-red-a-20261101-02`，9 项、4 失败、0 error／skip；根代理独立读 XML，四个失败分别为 A1 每侧样本读取 50（期望 2）、A2 额外遍历 160（期望 0）、A3 三批均 3168 B/validation（预算 512）、A4 重复校验读值 21（期望 0）。其余 5 项语义／失效回归通过。资源 guard 正常，测试自动重试关闭。第一次 PowerShell 参数拆分导致 coordinator ORPHANED，未进入 Gradle，单列为 NOT_RUN，不计作 RED 或性能样本；已改为 Python 独立 argv／CREATE_NO_WINDOW 入口。

有效 baseline：`costs/baseline-attempt-02/raw/samples.jsonl`，3 个测试 JVM、930/930 PASS、30 个场景摘要，0 skip。根独立核分母与 PID，并按进程复算 query 与完整服务成本。前一尝试仅有 ignored harness 的 Set／List 编译错误，0 计量行，原件保留，不加入样本。

计时限制：Windows thread CPU 读数呈 15,625,000ns 量化，930 行仅 78 行非零，而单行 query 约 1～4ms；thread CPU 中位数不作收益依据，保留原读数，后续分开比较 wall 与线程分配。全生命周期服务 CPU 同受此限制，不宣称已测得精细 CPU 提速。

### A1 当前结果

- 当前 production 最小差异仅评分及 primitive helper，根独审阈值整数累计上限、提前返回、方向／归一化和原公式语义；两项对应 GREEN 通过。
- 三进程各 31 次：远色早拒绝 query wall 中位改善 93.1%～94.4%，query 分配减少 83.3%～83.7%；相近边完整评分改善 43.0%～78.0%，分配减少 82.3%～82.6%。各自 prepare／query／close 服务总成本同向降低。
- 原件：`costs/a1-attempt-01/raw/samples.jsonl`，930/930 PASS，0 skip；与 baseline 是跨进程同轨迹对照。该结果是评分及指定生命周期的成本收益，不是完整翻页加速比例。
- 首次 GREEN 执行在测试前触发编译进程 1638.4MiB 软线，0 JUnit；核自有进程退出、归档对应 fault 后，相同 selector／profile／源哈希的第二次执行通过，原件均保留。

### A2 当前结果与不采用决定

- 真实 RED 额外遍历 160，当前 A2 GREEN 1/1；成本 930/930 PASS，0 skip。
- 根按相邻 **A1→A1+A2** 的每进程中位数重算：176 页 query wall 比 0.542～0.619，分配比 0.162～0.166，prepare／query／close 服务 wall 比 0.617～0.846；8 页 query wall 比 1.654～2.589，分配比 0.742～0.809，服务 wall 比 1.715～2.927。
- 与此同时，逻辑未改变的 A1 评分场景 wall 也慢约 1.5～2.3 倍，多个未改 A3 场景明显漂移；不把这些变化全部归因于 A2，不以事后漂移校正制造正收益。线程分配减少可靠，但当前耗时净收益／小场景安全性仍不确定。
- 决定：不合入当前主干，不再补采或搜参数。独立 patch 在 `costs/a2-attempt-01/raw/a2-production.patch`；A2 自有 production 差异已撤下，后续 A3 以 A1 为对照，生产集合为 A1+A3。最终移除仅用于 A2 候选的失败成本断言并随原件保留，不把未采用优化变为主干测试失败。

### A3 当前结果与不采用决定

- 真实资源 RED 为 3168 B/validation；A3 GREEN 四项通过，分配预算三批为 1.872／0／0 B/validation，0 skip。首次 GREEN 在测试编译前触发 1638.4MiB 进程软线、0 JUnit；归档本任务 fault 后仅将 Gradle heap 768m 降为 640m 重跑通过，测试 heap／资源保护／selector 不变。
- 固定成本 930/930 PASS。实际捕获到 A3 后两个 Gradle Test Executor 的命令行含 `-Xmx256m`，与 raw PID 对应；第一个 worker 退出较早未捕获 argv，不冒称三个均有该原件。
- 根按 **A1→A1+A3** 重算：默认空集合 query wall 比 0.902／0.765／1.003，prepare／query／close 服务 wall 比 1.014／1.086／1.168，query 分配比均 0.404、服务分配比均 0.419。即分配下降明确，默认完整服务耗时却三进程均略慢。
- 4 页手工集合 query wall 比 0.326～0.365、query 分配比 0.103～0.157；64 页比 0.200～0.791、分配比 0.004～0.014；64 页 tail 变更比 0.283～0.574，分配比约 0.004。热 memo 服务 wall 比 1.066／1.408／0.450，仍不同向。不能仅挑有利集合判整体正收益，也不能把准备／关闭漂移直接归因于未修改的代码。
- 决定：不合入，不补采。保存 A3 独立 patch／raw，撤下本任务 A3 两个生产文件差异；A4 仅以 A1 为控制，保留原 wrapper 精确比较与 guard snapshotCopy，避免未知项叠入后项。

### A4 当前安全结果

- 核心真实行为 RED 来自本轮最初的 9 项测试；A1 只改等价评分，不修改 memo／header／Session，该证据仍适用。新增 owner 生命周期与饱和测试只声明安全 GREEN，不声称各自有旧实现 RED，不为缺新内部符号重复编译旧实现。
- 根独审 owner 所有可见写入、四处 header 写入、guard 先行顺序、same-owner／identity、closed 与 Long.MAX_VALUE 禁用条件；饱和测试先刷新 stored MAX 戳，再确认下一次仍完整校验。
- 精确 7/7 GREEN，0 failure／error／skip，实际两个 worker argv 均含 `-Xmx256m`。Gradle 编译 heap 640m，系统及进程保护未降低。成本对照仍为 A1；在外部构建／测试清空前不采样。
- 为避免等待冻结 B/C，新增独立成本树 `D:/Codex/worktrees/reader-stamp-reevaluation-20261101`／`codex/reader-stamp-reevaluation-20261101`，基线仍 e8beb；只复制已冻结 A1+A4 必要源码／测试／固定成本工具，不复制 build 或数据包。Sol 生成、Luna 独核 `a4-worktree-equivalence.json` 的 14 个 SHA／2 个 blob；production／harness／profile／guard 字节一致，仅 runner/init repo 字面路径适配。主树 A4 三文件净差异保存 `a4-isolation-production.patch` 后撤下。
- 新树只负责原 A4 930 行，不新增样本；与主树 A1 原件作显式跨目录对照，目录迁移及工具路径变化记录为来源差异。所有本任务重型执行仍唯一 Luna 串行，外部全空时优先 A4，内存足够时可先推进主树 B/C 正确性。
- 成本已执行完整 930/930 PASS、3 JVM、0 skip。根逐进程复算 A1→A1+A4：稳定 query wall 比 0.088／0.119／0.019，服务 wall 比 0.284／0.341／0.061，query 分配约 710～750→158 B/op；精确 validation delta 全为 0。频繁合法 header 写 query wall 比 1.467／1.605／2.730，服务 wall 比 1.475／1.773／2.423，query 分配约 1308～1353→1401 B/op；validation delta 全为 1000，真实回退发生。未改负控墙钟同样漂移，不能把全部耗时变化归因于戳，也不能按未知真实调用比例合成净收益。
- 决定：不确定，不采用，不补采。A4 原始 930 行及最小生产差异留在独立树，主树不恢复戳。完整候选测试源码另保留 `rejected-candidate-tests/ReaderPairingAdoptionCostTest.kt`，SHA256 `2c9ee9ae0ac8788de3f795d294720fe80fcd5b60ec852f464465fa3aae9f4928`；主树 AdoptionCost 类只保留 A1 必需回归，避免未采用机制造成全测试编译缺内部符号。

### B 当前结果与保留决定

- 域两方法 2/2 预期失败、0 error／skip：旧逻辑仍有 3200 次样本读取／400 次原坐标回调（各测试预期 0）；测试范围没有扩至整类。
- Desktop 两方法 2/2 预期失败，Android 一方法 1/1 预期失败；当前测试源闭包确认为 Desktop 单文件、Android 单文件，真实 Test argv 分别含 `-Xmx512m`／`-Xmx1g`，retry0。首次完整生产依赖编译未裁剪生产。
- Desktop 最初一次因 init 读取 write-only `source` 失败、0 XML；通过本机 KGP 2.3.10 `javap` 实证 `getSources()` 后改读全部 `sources.files`，完整原件保留，第二次有效 RED 不覆盖第一次。JUnit 保留 rawName 并只规范化已知 KMP `()[jvm]`／`()` 尾缀；不把准备失败当产品 RED。
- 主树最小实现仅 `SharedSpreadMatching.plan` 的 `>` 改 `>=`，A4 已隔离；域2／Desktop2／Android1 精确 GREEN 已齐，不把无方图原176输入计为收益。
- 域 GREEN 2/2 已通过，0 failure／error／skip。Desktop GREEN 首次因系统余量 4086MiB 低于 4096MiB 保留线而软停，0 XML，不计产品失败；本任务 job／process 未超帽，归档精确自有 fault、确认进程退出且资源恢复后同范围重跑，保护不降低。
- 恢复记录：该 fault 原锁与归档均为 SHA256 `f0ce0a70b3e7855a52ddf9ee684a0f10e7e59c8f5caf2adb0e3ec0b0242f4876`、45930 bytes，已核验原 PID 全退出。执行工具自动审批拒绝带完整归属／hash／PID／资源检查的单文件清除命令，返回 `blocked by policy`；用户明确授权“清除此锁并继续”后，根在同工具重试仍被拒绝，均未实际执行。随后用户手动删除中央 `luna-resource-fault.json` 并要求继续；根与 Luna 只读确认锁不存在、归档仍在，B Desktop 同范围 attempt02 已恢复。没有改工具、重命名或改 state-dir 绕过锁；不将工具拒绝算作 B 不通过。若后续新 fault 再被工具拒绝，仍不能自行绕过。
- 最终 B GREEN：域2／Desktop2／Android1 全部通过，0 failure／error／skip，根独立核 4 份 XML 的 5 个方法。Desktop attempt02 为同范围重跑、实际 `-Xmx512m`；Android actual `-Xmx1g`、retry0。方图读取／原坐标回调归零，Full Skia 路径仍保留一次真实 bitmap 解码、四旋转同对象复用与释放断言；未宣称所有 FULL 解码消失。裁保留 B，后续与 A1/C 做最小组合接线。

### C 当前结果与保留决定

- `platform/c-app-red-attempt-01`：真实 HTTP／wiring 4 方法中 2 项按预期失败、2 项拒绝向量通过，0 error／skip。held body 场景旧实现共3请求、目标页2请求，响应 body 共18354B、关闭3响应；目标 requestId 从1替换为2。
- `platform/c-app-green-attempt-01`：相同4方法全部通过，0 failure／error／skip，实际 worker `-Xmx1g`、retry0。共2请求、目标页1请求，响应 body 共12236B、关闭2响应；原／提升 requestId 同为1。两版本最终 Ready 均为2且物理工作剩余0，GREEN 同时核对缓存真实原字节，不能只凭 Ready 判断。
- `platform/c-domain-green-attempt-01`：调度拒绝／保留原 key 两方法全部通过，0 failure／error／skip，实际 `-Xmx512m`、retry0。覆盖非升级、显式重试、refresh capability、旧 generation 和完成后 key。app 同时覆盖 URL、内容、page 对象变化及 fact permit 不复用。
- 根独立核对 XML 与实际 HTTP 账本，并审查 preload／runner 锁顺序、发布权与取消／实际退出：提升只改 kind，保留 jobKey 和原绑定；未通过守卫沿用原替换路径，不把观察到的 runningWork 当新授权。
- 决定：保留 C。在该受控实际 HTTP 场景确定少1请求／6118B响应 body；它不是设备网络线缆流量或普遍翻页耗时的测量。

### 最终组合与交付边界

- 保留集合为 A1+B+C；A2／A3／A4 的最小 patch、候选测试与原始结果留在上述隔离路径，不进入主干。最终四个生产文件之外均为必要行为回归和内部架构／本报告，改动内聚于评分、采样和请求复用；文件数主要来自双端真实接线测试。
- 最终格式仅处理本任务12个 Kotlin 文件，复用项目现有 `mihon.code.lint` 规则；Desktop 通过临时 init 应用同一规则，不修改永久构建配置。`platform/i-format-apply-attempt-01` 和 `i-format-check-attempt-01` 均 exit0／validEvidence=true，分别29.97／25.32秒；没有改变源码字节，19项冻结源码映射均 identity。随后共享8方法核对最终组合，不重复固定成本矩阵。
- 最终共享8方法仅作正确性检查：无外部重型进程时，可用物理内存约8.7GiB仍低于平台启动预算，故为该组复用已验证的域档（Gradle640m／meta384m、job3072／process2048MiB、启动物理7168／commit11264MiB），worker仍512m。系统保留物理4096／commit8192MiB及soft0.8不变；原生及其余平台组不改档，不把资源档变化当性能比较。
- 共享组合 attempt01 为执行异常／NOT_RUN：外层 exec session59324 返回1073807364且无输出，随后会话已不存在；原 PID51632／worker26816与对应 Windows identity 均不再活跃，无本树残留 python／cmd／java。日志17716B，最后停在2026-11-01 01:19:17UTC的 core:common 配置；无测试 XML、launcher-result 或 fault latch。协调器原生 `status` 据实恢复为 ORPHANED／125，不是产品失败或通过；实际中断原因未知，原件不覆盖，待同范围 attempt02。
- 共享组合 `platform/i-domain-green-attempt-02` 有效通过：根独立解析4份 fresh XML，合计8/8、0 failure／error／skip，changedSources／changedInputs 均空；4个实际 Test Executor均512m、单fork、retry0。guard通过，job峰1975.28MiB／process峰1055.2MiB，44.5秒。attempt01不混入通过数量，不扩大测试范围。
- 原生检查限定 `:app-desktop:nativeWindowTest` 两方法：fresh T170 与 T30 reopen+10 next，原176页／93单元完整 oracle、真实槽位与完整 draw ack；自然产生13次请求／绘制观测。只作正确性验收，禁止计作新的50条或端到端性能对照；skip 不算通过。
- 原生 `platform/i-native-green-attempt-01` 在准备阶段被 `SYSTEM_HEADROOM` 停止，coordinator FAILED／125、0 XML／0 cases，尚未进入窗口断言，不计产品失败或通过。guard启动物理12644MiB、结束4080MiB（低于4096保留线）；job峰2137.26／5120MiB、process峰1916／4096MiB，未超过任务上限。原PID5088／34716已退出，新fault latch SHA256 `dba33580153818aa8b4e23567e1767fd4a2e71b10906bf4297f4d67f4ed9e43e`，归档后才能恢复；保留线及源码不改。
- 新fault原件及归档均45946B、同SHA。首个安全清除命令在删除前因资源仅6673MiB而停止，未删除；随后连续物理11152→16310→16840MiB、无实际活跃外部编译／测试，根在同一工具重核路径、hash、归属、已退出进程身份及资源后成功清除该任务锁。没有绕过工具／保护或清理外部进程；同范围 attempt02 待实际结果。
- 原生 attempt02 在4d80旧基线启动后，根发现上述 b983 主干新接线，立即通过现有 coordinator stop 仅取消本任务。终态 CANCELLED／130，launcher validEvidence=false、0 XML／0 cases，停在 compileTestKotlinJvm，尚未进入 Test Executor／窗口断言；原guard33388／worker18492已退出，无本树残留进程、无fault。原件不覆盖，不冒充新基线通过。最新基线仅计划 native attempt03，仍固定2方法及原输入。
- 为避免外部任务接续造成无限饥饿，最终原生正确性检查以连续两点物理至少12288MiB、commit至少原13312MiB作为额外启动缓冲，允许既有外部focused构建共存。原guard、系统保留线4096／8192MiB、job5120／process4096MiB、512m worker及正确性断言均不变；仅撤销临时“必须全部外部空闲”的排队条件，不产生性能比较。前述固定成本矩阵的空闲采样协议完全不变。
- b983 的 AndroidReaderMaterializePorts 仅为原 sourceCall 增加诊断及HTTP状态记录，保持 Response.use、permit、写入和原异常／取消重抛；根独审确认 DiagnosticEvents.record 自身隔离异常，C绑定字段与请求身份不变。由于真实接线和测试辅助源变化，C-app 编译闭包仅补 `mihon/feedback/DiagnosticTestFixture.kt` 为第5源，仍只运行原4方法。新 presentation-theme 仅增加已在图中的 core.common 项目依赖，无新外部坐标；由最终 --offline 完整生产编译核验，不以只读检查宣称编译通过。
- 最新基线 `c-app-green-attempt-02` 实际4方法中1通过／3失败、0 error／skip：真实 HTTP 用例通过，requests2／target1／body12236B／closed2／原及提升 requestId1／ready2／remaining0 均与前次GREEN一致；三条模拟响应方法因新接线读取 `Response.code()`、旧mock没有该字段答案，期望Ready而得MockKException。记录为测试夹具不兼容，不抹去失败。Sol仅为文件中两处成功响应mock显式提供200，保留所有行为断言／诊断开关／生产实现；根独审并限定原4方法复验，先做同范围格式检查。真实 worker均1g／retry0，guard无资源fault，原件保留。
- 夹具修正后 `i-format-check-attempt-02` 有效通过，0源码改写，33.04秒；仅格式组也复用域低内存档，规则及12目标不变，C／原生仍原平台档。`c-app-green-attempt-03` 在 b983 上4/4通过，0 failure／error／skip，73.4秒、无source／input漂移、guard通过；根独立核2份fresh XML及实际HTTP账本，仍requests2／target1／body12236B／closed2／原和提升key同requestId1／ready2／remaining0。新增IO接线兼容性已闭环，不把之前三项夹具失败算通过。
- 四个原生输入绑定原成功执行的 pages、manifest、archive、oracle：manifest SHA256 `d19f1744a4ac4f111f86765f2ee4fe999ef6b9da5541121e5e6d4e9430806122`，archive `4b66d7c71dd3b8979d95d1bb7403804f5207694782cb1850a66e6b88e0bc7ceb`，oracle `0f0702257e24625be8a8413bcb85acf442b94ebdb42a1ccb2d511b96dc877b38`；176页逐文件与 manifest 一致。原176输入无方图，不能为 B 记收益。
- 最终原生 `i-native-green-attempt-03` 有效通过：8b16快照、1份fresh XML／2方法、0 failure／error／skip，235.3秒，changedSources／changedInputs均空。根用JSON decoder读取首个JSON对象（T170行另有diagnostic尾缀），独立核13次观测：期望／实际页组和槽位全一致、均有真实完整draw ack、无timeout或failure。只作正确性证据，不把这些耗时作为优化收益或100ms达标结论。
- 原生通过后安全快进至 `c70672a9cbf0b1067e255b2c30e3fb2550da7be7`：相对8b16仅书库／全局搜索、测试、文案及说明22路径，阅读器生产／UI／验证源与依赖无净变化。有效原生证据仍适用，不为无关变化重复测试。本报告与保留代码同一提交，最终本地主干包含关系和用户AppVersion原SHA保护由Git交付回执核验；不推送、不打包，不宣称全量测试或正式发布完成。

冻结成本协议：每版本三个独立 JVM、固定 10 场景，每场景 5×1000 预热、31×1000 计量，预期每版本 930 行。记录 prepare／query／close 各自线程 CPU、wall、线程分配、逐行服务合计、连续生命周期 wall 与 harness 开销；所有 operation 结果交给同一 volatile sink。目标场景与频繁失效／无早退／默认空集合及 tail 变更场景均在观察结果前固定；频繁 header 写入保持同内容合法几何。相邻源码消融不冒称同进程 A/B；执行顺序与跨进程限制保留。目标场景若三进程未同向改善至少 5%，或相关完整成本有未解释退化，不自动采用。Android 自动重试关闭，真实失败不通过重跑补成成功样本。

物理内存保留至少 4096MiB、commit 保留至少 8192MiB；不沿用旧低余量档。本任务所有重型任务通过共享中央 coordinator 串行执行，不触及用户进程。成本采样额外等待外部 Gradle 编译／测试退出；域成本档启动余量为物理7168MiB／commit11264MiB，平台与最终组合档为物理9216MiB／commit13312MiB。focused 正确性可在外部任务存在但达到对应启动线及全部 guard 条件时执行，其时间绝不作为性能数据。

首次基线成本采样前，检测到另一工作树 `detached-test-recovery-20261101` 的真实 guard／Gradle worker 仍活跃，尽管中央状态没有活动记录；验证代理按实际 PID／creationTime 等待其正常退出。此等待不产生性能样本，不改变测试或采用门槛。

多个其他会话随后持续编译／测试，阻挡 A4 独立成本和平台测试启动。用户已明确选择“继续排队等待”，不干预其他会话；本任务保持原保护与门槛，满足资源条件后自动继续，不把等待或未执行项算作完成。平台入口编程交 Sol medium、执行和原件核验交 Luna xhigh；平台纯 RED 可在内存门槛满足时提前执行，但 A4 采样仍要求外部重型任务空闲。

本轮原始结果放在隔离工作树 ignored `.gradle-coordinator/`；最终仅将可审查的紧凑数据表、必要行为测试和实际保留的 production 变更提交。
