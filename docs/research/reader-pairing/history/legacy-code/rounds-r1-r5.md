# R1–R5理论与候选在当前main的位置

本页的R编号是2026-10-20～22数学轮次，不是后来R1～R10工程复评编号。当前比较基线固定 `6b5831c64dd9f09a7d79c93fac9a75a548c740fa`。判定基于分支条件、读值／失败语义和现有测试，不以文件hash不同判断功能缺失。以下数量均为旧原件记载，本轮未新运行。

| 轮次／机制 | 有界历史证据 | 当前main核对与明确判定 |
|---|---|---|
| 完整数学模型／信息下界 | 用户U1–U5、同知识K的可能世界与完整unit投影、曝光与进度约束 | **仍有研究价值的理论背景**，不是待cherry-pick产品功能；不同知识可对应不同unit的反例不等于当前必须放宽用户体验要求 |
| R1 forced边界skip-left入口剪枝 | 合法持久记录1232状态中16个Need→Certified；完整API域1536，容量2见证 | **main有等价**：Certification通用入口中skip-left只有相邻两页均非forced才枚举；保留真实可达后继 |
| R2 forced target isolated | 96真实owner状态，6项消除无关需求、18真缺口保留 | **main有等价**：target为SPREAD或forced时直接从targetIndex枚举两opening；不是同一页被固定成猜测单页 |
| R2 五态可组合函数树 | 完整事实下384初始/更新对、3456查询；冷建及标签/供数成本保留 | **仍有研究价值、暂缓**。当前memo为有限target的完整readset重读复用，不是五态段函数树；旧研究仅覆盖完整快照，未证明partial事实、生产供数、Session生命周期或整体CPU收益 |
| R3 A：非负provider的near=0支配 | 72域8个新增认证；任意负callback保持原默认，非负参数只隔离certifier启用 | **仍有研究价值、暂缓**。当前scan仍读取两个distance，无distancesNonNegative开关。不能全局假设公共callback非负；重启还须覆盖当前失败可见性、source identity、Memo和平台接线 |
| R3 B：leading侧同步入口 | 290域4新增认证；incoming manual／FIRST_PAIR／两opening不一致回退 | **仍有研究价值、暂缓**。当前isolated条件仅SPREAD/forced，没有该专门leading入口快捷分支。现有一般扫描仍可自然收敛，不能把缺专门快捷分支等同缺少全部正确性能力；D之后的边际收益须另评 |
| R4 C：later-null先比较 | 338状态中64新增；后来真实契约发现near失败可被跳过 | **仍有研究价值但原补丁不可采用**。当前main没有该提示。必须先补失败guard，再重评成本；见[七项核对](research-next-dirty.md) |
| R4 D：结构不可达opening／reservation | D独立48新增，保留无forced反例 | **main有等价**，当前生产与旧dirty D相同，专门回归仍在且强转前多显式assert；不重算为待交付优化 |
| R5 E：独立更宽入口重试 | R4-CD+默认lookbehind2 hook上，222状态20新增、42重试额外298step | **仍有研究价值、暂缓**。当前NeedFacts立即返回，既有扩大窗口发生在其他分支，不等于E的Need后独立重试。E基线含未采用C及hook，不能当当前main直接收益，也不是“最多多扫一页”或通用最小支持求解器 |

## 原件／证明定位

- [完整模型](theory/2026-10-20-reader-pairing-full-algorithm-mathematical-model.md.txt)、[R1](theory/2026-10-20-reader-pairing-mathematical-analysis.md.txt)、[R1精确候选patch](proofs/candidate.patch)、[R1结果](proofs/result.json)。
- [R2论证](theory/2026-10-21-reader-pairing-mathematical-analysis-round2.md.txt)、[函数树夹具](math-tools/PairingFunctionExperiment.kt.txt)、[forced patch](proofs/round2/forced-candidate.patch)、[结果](proofs/round2/result.json)。
- [R3论证](theory/2026-10-21-reader-pairing-mathematical-analysis-round3.md.txt)、[demand夹具](math-tools/PairingDemandExperiment.kt.txt)、[A patch](proofs/round3/A/candidate.patch)、[B patch](proofs/round3/B/candidate.patch)、[结果](proofs/round3/result.json)。
- [R4论证](theory/2026-10-22-reader-pairing-mathematical-analysis-round4.md.txt)、[evidence夹具](math-tools/PairingEvidenceExperiment.kt.txt)、[C patch](proofs/round4/C/candidate.patch)、[D patch](proofs/round4/D/candidate.patch)、[结果](proofs/round4/result.json)。
- [R5论证](theory/2026-10-22-reader-pairing-mathematical-analysis-round5.md.txt)、[confluence夹具](math-tools/PairingConfluenceExperiment.kt.txt)、[独立入口](math-tools/run_confluence.py.txt)、[R4-CD patch](proofs/confluence/baseline-R4-CD.patch)、[lookbehind hook](proofs/confluence/lookbehind-hook.patch)、[结果](proofs/confluence/result.json)。

## 恢复边界

旧树HEAD68a／f38以及报告中的05dd、1820、11ea、8e74、3d52均不能仅凭现有e8beb bundle假定远端可达；本次已对e8祖先关系作只读核查，不是其祖先。这里保存关键已提交理论／研究程序、R1/R2/R3各12份按原manifest逐项SHA精确恢复的基线源、12份3d524基线源、12份实际round5 instrumented-before源及12份实际R4-CD+hook执行源，便于脱离旧目录阅读与恢复关键算法；完整Git图和原始包由历史总档案另行记录。

恢复不能只按报告HEAD：R3报告同时记11ea与9b2复跑，实际manifest为9b2；10/22实现报告写3d524，而round5实际manifest为6feecff。需要按源SHA、实际patch、外围jar及编译器依赖核对；instrumented-before源含计数注入，不能当原始产品代码。R1原candidate.patch为CRLF，直接git apply可能因context换行失配；本次只在临时副本将patch转LF后逆向恢复，得到的baseline源SHA与原manifest完全相同，归档原patch字节未变。原runner某些模式从HEAD读取源码，原round4 anchors在D加入后失配是预期；不要在当前树直接执行历史命令。

保留程序均为`.kt.txt`／`.py.txt`，不会进入生产构建。没有复制外围jar、编译器、classes或原图；这些依赖若未由历史原始包覆盖，须报告恢复缺口，不能声称本次已在新机器复现实验。旧算法域PASS只证明记录的闭包与断言；C/D未覆盖失败组合、R3公共负值、R5含未采用前提均需要保留。
