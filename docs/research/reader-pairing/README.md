# 阅读器配对研究档案

本目录保存已经测过但尚未确认净收益的方案、反例和可复查原件，供后续维护先查证据再提出新假设。暂缓不是永久禁令，也不是数学上否定方案；改变采用决定需要记录新的适用条件和真实证据。

## 全历史 R1～R10 处置

- [历史总清单与逐组结论](HISTORY_INDEX.md)：覆盖早期数学轮次、九份返回包、修订、工程报告及旧未提交材料。
- [R1～R5与旧未提交源码](history/legacy-code/README.md)、[R6～R9逐项裁定](history/engineering/DECISIONS-R6-R9.md)、[R10工程处置](history/engineering/R10.md)及[150题对照](history/R10_QUESTIONS.md)。
- [原包与版本关系](history/packages/README.md)、[工程来源及恢复限制](history/engineering/sources/SOURCE_INDEX.md)、[过程文件与遗漏扫描](history/PROCESS_DISPOSITION.md)。

“已处理”表示来源已登记、去留明确并保全必要原件；不表示所有历史问题都已解决。100ms可达性、真实缓存上界等未知项继续保留研究价值，需新证据才重启。2026-11-02已撤销5个旧研究工作树，必要输入和未公开证据去重后在Git外保全，见[退役与数据恢复记录](history/RETIREMENT-2026-11-02.md)。暂缓方案继续保留本目录。更早的[17项副本清理](history/CLEANUP-2026-11-02.md)是前一阶段；其中隔离备份和本次临时索引的额外删除被工具拒绝，具体残余已列明。

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
