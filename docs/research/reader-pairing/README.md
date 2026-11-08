# 阅读器配对研究档案

本目录保存已经测过但尚未确认净收益的方案、反例和可复查原件，供后续维护先查证据再提出新假设。暂缓不是永久禁令，也不是数学上否定方案；改变采用决定需要记录新的适用条件和真实证据。

## 全历史 R1～R10 处置

- [历史总清单与逐组结论](HISTORY_INDEX.md)：覆盖早期数学轮次、九份返回包、修订、工程报告及旧未提交材料。
- [R1～R5与旧未提交源码](history/legacy-code/README.md)、[R6～R9逐项裁定](history/engineering/DECISIONS-R6-R9.md)、[R10工程处置](history/engineering/R10.md)及[150题对照](history/R10_QUESTIONS.md)。
- [原包与版本关系](history/packages/README.md)、[工程来源及恢复限制](history/engineering/sources/SOURCE_INDEX.md)、[过程文件与遗漏扫描](history/PROCESS_DISPOSITION.md)。

“已处理”表示来源已登记、去留明确并保全必要原件；不表示所有历史问题都已解决。100ms可达性、真实缓存上界等未知项继续保留研究价值，需新证据才重启。2026-11-02已撤销5个旧研究工作树，必要输入和未公开证据去重后在Git外保全，见[退役与数据恢复记录](history/RETIREMENT-2026-11-02.md)。暂缓方案继续保留本目录。更早的[17项副本清理](history/CLEANUP-2026-11-02.md)是前一阶段；其中隔离备份和本次临时索引的额外删除被工具拒绝，具体残余已列明。

## 2026-11-03 下载章节首屏分析

2026-11-03新增[下载章节打开差异与优化分析](2026-11-03-downloaded-open-latency-analysis.md)：同真机同章节的主干单页首绘前，全章header遍历约404ms，成为优先解耦的I/O依赖。报告给出原版目标、按需方案和验证边界；尚未实施或证明可回收收益，不改变下列算法方案的历史裁定。

新会话实施入口：[下载章节首开优化方案与roadmap](../../roadmap/2026-11-03-downloaded-reader-startup-optimization-roadmap.md)。按用户要求，先完成最小改动和三次真机对照，交付优化结果，再进入较完整验证；不提前启动全量测试或历史算法研究。

P0已由`64329568ace1`完成，平均首绘由643.7降至267.5ms，见[P0实测](../../evidence/downloaded-reader-startup-p0-2026-11-03.md)。[P1执行计划](../../roadmap/2026-11-03-reader-startup-p1-plan.md)已完成两点定位：header调用约4–5ms，主要调用前等待尚无证据证明可安全删除，故未采用新优化候选，见[P1结果](../../evidence/reader-startup-p1-2026-11-03.md)。历史算法裁定不变，不据本轮诊断重启暂缓方案。

[P2计划](../../roadmap/2026-11-03-reader-startup-p2-plan.md)已完成编译条件核查和两级定位，见[P2结果](../../evidence/reader-startup-p2-2026-11-03.md)。实际OAT为speed；具体主导调用仍未证实，未采用新候选，不改变P0/P1结论或历史算法裁定，也不自动继续第三轮细分。

用户后续授权的[P3](../../roadmap/2026-11-03-reader-startup-p3-plan.md)已完成：187个初始事实通知重复整章检查得到实测与真实回归，固定已摄入首次快照后其inclusive由同条件30–34ms降至0.6–0.9ms；同时修复单页滚动停稳缺重绘导致的往返黑屏。最终三次近黑87–115/34–67/70–99ms，仍未稳定达到≤60ms；首次总耗时未证明稳定改善，实际OAT/视频纠错/验证限制见[P3结果](../../evidence/reader-startup-p3-2026-11-03.md)。这些新证据针对Android订阅与绘制接线，不改变历史配对算法裁定或启动全量研究。

2026-11-04的[P4](../../roadmap/2026-11-04-reader-startup-p4-plan.md)采用同次打开复用下载位置，消除CBZ重复一次/目录重复两次查询；9项focused、独立审查及六份USB真机对照完成。首次首绘均值320.403→283.045ms，近黑99–133/99–132→67–99/66–100ms，暖态未证明改善，≤60ms仍未达标。见[P4逐样本结果与测量限制](../../evidence/reader-startup-p4-2026-11-04.md)。无持久缓存、不改变配对协议/历史算法裁定，不以本轮结束启动完整测试或第二候选。

后续[持续优化R2](../../evidence/reader-startup-autonomous-2026-11-04.md)将单页P3的固定首快照准入用于Android双页接线；真实重复ensure187→0，十二份同仪器对照中完整双页内部绘完缩短25.1%–28.1%，首次近黑仍100–133ms。目标4与完整UNIT计时已补齐，单页首次未证实改善。probe OFF正常ON往返另暴露错单元，已冻结A/B均复现且未修；不以首开通过代替正常翻页验收，不归因本轮guard。原件／边界与后续最小定位入口保留在唯一报告及持续计划，不重启历史算法方案。

[持续优化R3](../../evidence/reader-startup-autonomous-2026-11-04.md)核查自动匹配独有机制：本地CBZ不经过HTTP请求提升/双可见准入；采样与显示共享事实，不共享显示encoded buffer或decoder。已有事实免读header各模式均可命中，ON提前发布事实可能帮助后续显示，但没有证据证明它造成既有样本领先。另确认双页ON符合未裁边/无region条件时关闭当前SSIV decoder的可选hardware bitmap copy，单页/OFF未共享该策略；这是独立后续候选，实际copy次数/成本和净收益未测，不能直接推广或认定模式因果。本轮候选只针对单/双holder本页header与编码重复开流，未迁移历史暂缓算法；方案与实测采用状态以持续记录为准。

后续独立[持续优化R4H](../../evidence/reader-startup-autonomous-2026-11-04.md)已测试该hardware-copy候选：8份同仪器真机首开确认控制实际HARDWARE、候选实际ARGB_8888且都GPU绘制；静态/放大/蓝色双页对应像素一致。单页首绘/近黑未改善，OFF近黑上界均值名义快25.5ms但两对为+52/−1ms，未证实稳定收益；候选Native保持量更高，4组短流程帧没有额外优势。**不推广**，源码与设备均恢复R2，完整补丁/真实失败/原PTS及资源边界在唯一报告；这不否定其它设备或后续具体条件下重新评估，也不重启历史配对算法。首次目标bitmap观测不等于全tile的copy次数/自耗时。

## 2026-11-06 Desktop 新的调用条件与D6裁决

[D6结果](../../evidence/desktop-reader-d6-2026-11-06.md)、[优化分析](../../evidence/desktop-reader-d6-optimization-analysis-2026-11-06.md)与[roadmap](../../roadmap/2026-11-06-desktop-reader-d6-roadmap.md)已完成本批实施和有界裁决。C1只在Desktop adapter的同序身份查找使用ordinal并核pageId/version，其他情况保留原fallback；真实模型8页读取44→16、187页17,765→374，短章/方向/版本/重排/换代/关闭通过。它不是旧A2共享certifier补丁，共享O8原已具备，不恢复A2/A3/A4暂缓方案。

C3仅每次投影每远端章Boolean判断2→1，source0=0、下次打开重查。C2没有完整可复用输入键/真实冗余RED，C4/C5/C6无安全可删依赖证据，位置复用/C7未启动。8份Windows新raw、三模式正确漫画/往返及6项focused完成；单页首次正反向均观察候选较早，但中断/环境漂移未控制、warm重叠、双页无同轮控制，只按局部工作量采用，未证实稳定首开ms或≤60ms近黑。D5提前准备仍撤回，本轮不改变历史算法裁定，也不把条件不实施解释为永久无优化空间。

## 2026-11-07 Desktop D7订阅迁移反例

[D7结果](../../evidence/desktop-reader-d7-2026-11-07.md)新增“session页表先于Screen挂载”的真实factory条件，证明model摄入依赖首次composition；另以逐emission观察证明迁入IO后必须原子发布非空session及派生身份。该条件与D5提前物理准备不同，但12份三模式正常点击未满足采用门槛：single first区间重叠，OFF first候选更晚，ON first较早/warm重叠。候选及并发配套已精确撤回，只保留默认OFF诊断，不修改共享配对算法。

ON控制/候选均已有重叠单元；候选精确往返身份未完整确认，不把index相同当成原页对恢复。后续重启须取得新的适用条件及跨模式安全证据，不能将摄入inclusive或slot等待混合wall直接当可回收收益，也不重启历史暂缓算法。

## 2026-11-07 Desktop D8同锁恢复入口

[D8结果](../../evidence/desktop-reader-d8-2026-11-07.md)把复用边界缩到“同一次调用、同一生命周期锁内刚准备的owner”，没有重启D6的全入口投影缓存或D7订阅迁移。真实模型/coordinator回归先验证保存布局与恢复状态，再以轮次RED/GREEN确认identity准备2→1、held阶段projection3→2；恢复状态改变后的必要发布与独立重试ensure保留。按局部工作量采用，不声称外部F或近黑改善，不改变历史配对算法裁定。

## 2026-11-01 当前主干复评

- [A2：复用owner索引，暂缓](2026-11-01-main-reevaluation/A2.md)
- [A3：紧凑精确读值比较，暂缓](2026-11-01-main-reevaluation/A3.md)
- [A4：保守变更戳，暂缓](2026-11-01-main-reevaluation/A4.md)
- [原整轮裁定与集成验证](../../2026-11-01-reader-r1-r10-main-reevaluation.md)
- [逐文件SHA256清单](SHA256SUMS.json)、[来源与推导](2026-11-01-main-reevaluation/provenance.json)

本轮保留A1+B+C，实现提交为 `f04ec49bd06df934281886885f463b0028813071`，远端独立交付历史中的等价移植为 `ac188545a43df74024358a5d016319825bfd5fe0`，归档准备基线为 `b59e851498ee507618ec1dcf947e98bd2c53fa6f`：

| 保留项 | 当前实现与维护说明 |
|---|---|
| A1：数组评分与非负部分和提前拒绝 | [SharedSpreadMatching](../../../domain/src/commonMain/kotlin/mihon/domain/reader/SharedSpreadMatching.kt)，原公式精确回归在[AdoptionCostTest](../../../domain/src/commonTest/kotlin/mihon/domain/reader/ReaderPairingAdoptionCostTest.kt) |
| B：方图无边缘采样计划 | 同一SharedSpreadMatching.plan；真实几何／Computed／默认FULL缓存边界见[共享架构](../../architecture/reader-shared-core.md#2026-11-01-评分方图采样与-android-同绑定请求提升) |
| C：Android同绑定普通请求原位提升 | [HttpPageLoader](../../../app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoader.kt)、[Runner](../../../domain/src/commonMain/kotlin/mihon/domain/reader/scheduler/ReaderPageMaterializeRunner.kt)、[Scheduler](../../../domain/src/commonMain/kotlin/mihon/domain/reader/scheduler/ReaderRequestScheduler.kt) |

## 固定协议与材料边界

实验从 `e8beb212a0ead306f6441b130c40d4b0a6c660f6` 开始。baseline、A1、A1+A2、A1+A3、A1+A4各运行固定10场景、3个独立JVM、每场景每JVM31行，每行1000操作；先5×1000预热。每版本930行全PASS，另有30个场景摘要。A2/A3/A4各自以A1为控制，不叠加暂缓项。

输入由[固定harness源码](2026-11-01-main-reevaluation/harness/primary/ReaderPairingReevaluationBenchmark.kt.txt)中的虚构PageId、合成色样、固定尺寸及seed739产生，没有读取用户原图、CBZ或个人图片。原JSONL保留PID及runtime机器标签作为进程来源；它们不是配对身份，也不证明不同运行同一JVM。未归档原图、缓存包、机器凭据、完整Gradle／进程日志；没有对未纳入的全部日志作隐私保证。

目录内`.gitattributes`仅关闭本档案的换行转换，保证checkout后原字节哈希可复核。所有成本JSONL、原summary、focused XML及必要源manifest均按原字节保存；A2旧summary确实以baseline为对照，本轮A2页按A1 raw重算，未悄改旧汇总。15份重复包含计量行的成本JUnit XML用标准gzip无损压缩、mtime固定0，清单同时保存压缩SHA、解压后原字节数和SHA，不再重复保存明文。可用标准工具解压，例如 `gzip -dc file.xml.gz > file.xml`，再将解压结果SHA256与清单的uncompressedOriginal.sha256比对。

wall与线程分配分别观察，服务是prepare+query+close；连续生命周期wall含harness开销，原始列仍保留。Windows线程CPU的15,625,000ns量化使多数短query为0，不用于精细CPU收益结论。不同运行进程序号的中位数比值不是同进程A/B；负控漂移、未知真实场景权重、未覆盖条件必须和有利结果一起读。

## 恢复与重启

1. 先读本索引、目标A2/A3/A4页和原raw；查已有假设、已观察反例及未覆盖条件。只有新的结构、调用形态或证据能说明为何旧结论不再充分时，才提出新实验；记录改变了什么，不从旧方案名称重新盲探。
2. 完整实验基线通过[增量Git bundle](2026-11-01-main-reevaluation/source/baseline-e8beb.bundle)恢复，不依赖旧本机分支。[恢复核验](2026-11-01-main-reevaluation/source/baseline-bundle.json)记录前置远端04a265395d1a115eeb492b0ed13d149a9f093c62历史；在包含此前置历史的克隆中，先 `git bundle verify <bundle路径>`，再 `git fetch <bundle路径> refs/research/reader-pairing-baseline-e8beb:refs/research/reader-pairing-baseline-e8beb`，即可在新隔离工作树检出e8beb。已在仅先抓取此前置远端历史的新bare仓库实际验证、导入并恢复精确e8beb。归档另含[8个必要基线源码](2026-11-01-main-reevaluation/source/baseline-e8beb/)、原harness／生成输入代码、原profile／init／runner文本快照及候选测试；无需访问旧D盘临时目录即可复查关键实现与计量。文本快照中的旧绝对路径只作来源，不可直接执行，恢复时将repo/central/output路径显式绑定到新隔离工作树，保留已声明资源保护与测试JVM，并记录适配差异。
3. 恢复历史实验时，只在baseline上先应用[A1控制补丁](2026-11-01-main-reevaluation/patches/A1-control.patch)，再单独应用A2、A3或A4补丁。A1从f04已采用文件只撤B的`>=`得出，与实际A1成本源raw SHA完全相同，见[推导核对](2026-11-01-main-reevaluation/source/A1-derivation.json)。迁到最新main时重新识别所需最小diff、先写真production失败测试；不要merge旧研究整树，不把旧源码覆盖新main。
4. 测试编译有边界：[A1-only主干快照](2026-11-01-main-reevaluation/source/tests/ReaderPairingAdoptionCostTest.a1-only.kt.txt)可作A1窄回归；[早期9方法快照](2026-11-01-main-reevaluation/source/tests/ReaderPairingAdoptionCostTest.early-9.kt.txt)通过CRLF字节恢复，SHA与当时baseline/A1/A2/A3冻结值完全一致。它含A4核心性能断言，在A1/A2/A3只选对应focused方法；`--tests`仍会编译整个测试源，不能解决缺符号。[完整11方法](2026-11-01-main-reevaluation/source/tests/ReaderPairingAdoptionCostTest.a4-full-11.kt.txt)含A4内部API，只能配A4。必要MemoTest及focused frozen-metadata给出原selector和依赖，不能把选择器过滤误当成编译隔离。
5. 恢复源码、harness及依赖后，先在新环境完成focused RED/GREEN和原条件核对，再决定是否需要新性能协议。本次归档只做材料完整性与补丁适用性检查，未在归档恢复树重新运行Gradle、未宣称已复现工程测试通过。

新证据应追加到本目录的相应假设／新一轮记录，保留原暂缓条件和来源；采用决定与产品功能说明仍遵循项目验证和用户验收门槛。

## 2026-11-08 Windows 首开阶段收口

Windows 包内 miniz CBZ 后端与条件性的首次组合/最终入口复读已进入主干整合；当前路径仍可能错过页表准入，近黑预算尚未达到。实现审查、整合验证、研究数据保全和后续任务统一见[Windows 首开成果与研究收口](../../evidence/desktop-reader-startup-closeout-2026-11-08.md)。未命中的同步 Ready 补接留作实验材料，后续先解决一次性准入的时序依赖，再评估准备队列与过渡呈现。此项不重启已有配对算法研究。
