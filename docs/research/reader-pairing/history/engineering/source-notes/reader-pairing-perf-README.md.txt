# CP1 S4 原生绘制性能对照

本目录保存测试侧测量配置和分析脚本。当前协议见 [执行计划§15](../../docs/2026-10-26-reader-cp1-gemini-correctness-plan.md#15-完成剩余路线候选固定正式s4与最终裁定)，审查结论见 [统一审查§22](../../docs/2026-10-21-reader-cp1-consolidated-review.md#cp1-final-20261027)。正式数据只用于本次 Windows 原生窗口、既有176张WebP和固定场景；不代表 Android、原版Mihon或光学输入到显示延迟。

## 源码与输入隔离

- L1 production 固定为 `db7b0e24bc`，在独立 worktree 测量。候选使用另一个独立 worktree；记录基线提交及实际产品 patch，不能只写 HEAD。
- 双方仅移植完全相同的测试工具、必要 test fixture 和 init 配置。旧版缺少的诊断接口在 test adapter 内兼容；不向 L1 production 移植候选 API、预算、缓存或调度。
- 不在主工作树换产品源码。`l1_swap_r3.py`、`check_swap_list_completeness.py`仅保留为历史实验复现材料，当前流程不执行。
- 输入是清单顺序001～176的原WebP，而非ZIP内部顺序。每图size/SHA、manifest和CBZ在采样前后核对；CBZ SHA-256为`4b66d7c71dd3b8979d95d1bb7403804f5207694782cb1850a66e6b88e0bc7ceb`。
- 共享一个由完整 production canonicalPlan 生成的v2 oracle，含完整有序slots、position、certain和reason；旧的仅页集合oracle不能降级使用。生成在独立JVM完成，不预热正式被测reader的缓存。双方oracle字节必须相同。

## 测量边界与固定样本

入口为 `DesktopReaderNativePerformanceSmokeTest.nativeWindowRecordsFiftyProductionReaderLatencySamples`。使用真实 ComposeWindow、production本地章节、ReaderContent、runtime及Skia绘制。

每侧恰50条，顺序固定：

1. fresh-runtime：T30、T170各5次，共10条。
2. same-process-reopen：T30、T170各5次，再10条。
3. 同session从第26页附近起，真实next前进10个不同完整阅读单元，重复3轮，共30条。双页另一源页不是一次新翻页。

T30/T170是1-based读者页码，源索引为29/169。`compare_r3.py`要求全部预定sampleId、顺序、动作与目标完整配对；少一条或重复一条不能通过。每侧一个预热窗口和必要的reopen priming、序列定位不计入正式50条，单列披露。

主计时从production page/next调用前的单调时钟，到当前runtime/generation/sequence/ticket/frame下完整oracle unit的真实draw acknowledgement；target首次图像ack另报。完整unit验证真实draw回执绑定Frame的有序slots/position/reason/certain/coverage，不能只看认证或页码。实际原生物理槽位/像素及跨holder身份另由NativeStable专项作为前置门禁；这不是逐样本截图匹配，不能把逻辑Frame metadata单独当成物理像素识别。它没有观测显示器光学present。

新reader setup与setup→完整draw单列；setup包含测试DI/controller/window/mock安装，不称纯production启动。reopen使用新DI/profile/model/runtime/cache，仅OS/JVM进程温热，不称保留旧reader缓存或OS冷缓存。sequence的setup累计耗时不是每次翻页延迟。

## 执行与失败处理

一个协调者跨两个worktree串行运行。通过主工作树绝对路径的`scripts/gradle-coordinator.py`和既有`windows_memory_guard.py`调用各自wrapper；实际CWD和`--project-dir`都指向正在测的隔离worktree，状态目录指向同一个主树`.gradle-coordinator`。不裸跑Gradle，不全局清Java，不使用`--rerun-tasks`。

init支持绝对系统属性：`mihon.cp1.vol08.pages`、`manifest`、`archive`、`oracle`，以及`mihon.cp1.windows.samples`、`summary`。正式入口还必须显式提供`mihon.cp1.windows.runStatus`，无默认路径；小范围校验不需要该属性。显式传本轮唯一新目录，不能依赖指向历史oracle和旧样本的默认路径。PowerShell的`-Dorg.gradle.jvmargs=...`须作为单个argv传递。

先验证计时消费者与oracle契约，再以两个连续reader真实绘制/关闭的小用例检验owned JVM uncaught collector和清理；两侧同工具均清洁后固定hash/参数，才允许正式baseline→candidate串行采样。后台异常独立于JUnit XML和行内failure；任何未处理异常、清理未完成或缺少健康sidecar都使该运行无效。异常原栈和阶段时间必须保留，不能静默吞掉。

应先提交/固定候选HEAD，再做编译与小窗口校准。Desktop生成的BuildInfo包含Git标识；小用例通过后再提交，会改变生成输入并触发重编，源码工具hash相同也不足以复用编译峰值。必要时用现有低堆compile档单独预编译，再以双方完全相同的正式参数测量；不跳过构建任务或更改production配置。

正式运行使用当前已核定相同资源档，记录实际heap/guard/DPI/window配置及进程退出。资源守卫拒绝后先检查余量和已有负载，继续轻量工作；无环境变化证据不反复等待或排队。是否调整须基于真实诊断及已有授权，不把某个历史启动余量当硬件最低需求。

每次使用唯一coordinator key和输出目录，每条raw flush，健康sidecar以CREATE_NEW写入。失败/超时原样保留，不计零、不删除、不追加成功样本补足；工具污染记录标为NOT_COMPARABLE，不能与修复后新版拼接。2026-10-27首次L1单侧50条虽XML绿但有未捕获MockK异常，已保留并作废，候选当时尚未启动。

## 分析与门槛

严格UTF-8环境下运行本目录的`test_comparison.py`验证分析行为；它只运行标准库测试，不启动Gradle。真实比较须提供双方raw和对应run-status sidecar：

```powershell
python scripts/reader-pairing-perf/compare_r3.py --baseline <baseline.jsonl> --candidate <candidate.jsonl> --baseline-run-status <baseline-run-status.json> --candidate-run-status <candidate-run-status.json> --out <comparison.json>
```

退出码0表示报告生成；是否通过以输出`verdict.r3Passed`为准。缺失/不健康sidecar、未完成body/cleanup、uncaught非0、raw SHA不符或预定50条不完整均拒绝PASS。只有运行与样本有效时才用`paired_r3.py`补充同一数据的配对bootstrap；它不改变门槛，不解释污染样本。

| 判据 | 固定要求 |
|---|---|
| 主收益 | 全50条completeUnit中位数改善≥max(基线中位数×15%,100ms) |
| 退化护栏 | completeUnit及target两指标的所有逐目标页、逐真实动作中位数退化≤16ms，按未舍入纳秒计算 |
| 数据有效性 | 双方各50条严格配对，完整oracle一致，无failure/timeout，run-level健康 |

报告整体、各场景及动作的算术均值、中位数、nearest-rank P95、最大实测值及sampleId、失败/超时分母和配对差。成功分布是条件分布；超时最大至少达到保护阈值，不冒称全体已测到最大值。辅助bootstrap不是重复实验次数，不能证明跨设备、跨章节或所有异步时序的性能。

收益未达标也交付真实结论；没有可定位瓶颈时不盲调缓存/并发，不反复采样直到通过。正确性候选通过与性能目标通过分别报告。正式发布、构建与用户体验验收仍走各自门禁。

## 历史材料

2026-10-23脚本曾对历史R1/R3输出做一致性自检，换位清单当时覆盖18个产品文件。这仅是当日历史工具结果，不构成当前隔离候选或新测量工具的有效性证明；旧raw、日志和swap脚本保留追溯，当前协议以上述完整oracle及运行健康门禁为准。

2026-10-27最终有效比较为L1 50＋修后候选50，完整统计与失败沿革见统一审查§22。正确性候选接受、性能R3=false；原v1污染及v2候选10失败全保留。修正真实曝光/holder身份后原候选全部计时受影响，故只补一次新候选50，L1的正式操作代码/输入不变而复用；未重采100条直到碰绿。源码提交由source SHA映射，正式期间不改变HEAD/源；commit后再跑正式必须先稳定BuildInfo与低堆编译。
