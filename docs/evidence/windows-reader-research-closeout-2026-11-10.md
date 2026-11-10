# Windows 阅读器性能研究收口

记录日期：2026-11-10；实际收口：2026-11-11。状态：用户已验收当前体验包性能OK，并要求停止研究。此记录汇总 R5 结论，不启动新测量或候选。

## 决策与有效证据

- R3、R4 性能候选已撤回；其原始实验结论仍按各自报告保留，不据此声称所有候选路径都已清理。
- R5-SH 的机制/focused 验证成立，但只代表限定条件下的内部机制结果，不构成端到端收益。
- 四份有效普通ON新进程探针为 SH_Bi4、SH_Ci1、SH_Bi5、SH_Ci2，每臂各两次，均为普通探针开启状态；不是OFF对照，也不是物理可见端点。
- B/C 总 callback→FIRST 均值为 177.69695/205.31160ms，C 慢 27.61465ms，预设整体门槛 FAIL。holderReady→FIRST 为 18.84640/5.93385ms，局部快 12.91255ms；此局部改善不能证明净收益。上游额外跨度约 40.5272ms，仅是样本间观察，原因未证实，不能归因于SH或断言总体回归。
- 六段连续区间均值（B/C，ms）：click→PAGE_LIST_READY 42.584/77.009（差异原因未证实）；pageList→UI demand 38.863/46.095；UI demand→encodedReady 19.303/21.601；encodedReady→decoder 28.860/22.501；decoder 26.853/29.922；decoderEnd→FIRST 21.234/8.184。具体边界和原字段见[R5证据报告](windows-reader-startup-r5-2026-11-10.md)。

## 未运行与解释边界

- 因整体门槛失败，未执行OFF十二开矩阵及双页负控；不得把未运行配置写成通过或失败样本。
- WGC只核实FIRST后的正确像素。所有原始WGC记录保持 `PARTIAL/native-exit1`，不改成PASS；没有覆盖打开关键区间，不能作为端到端可见时延证据。
- 普通探针数据不等于无探针净性能，也没有外部R/F/K、官方绝对差或P95结论。点击至PAGE_LIST_READY的34.425ms臂间均值差仅为观察，来源待证；若未来重启研究，须同包受控对比，并使用普通探针边界，避免用高扰动JFR记录代替。
- 新增上游时间的来源仍属未解问题/推测。本次结论不指认代码原因。用户要求停止，因此不安排后续研究。

## 交付身份与清理状态

用户验收包来自源码 `eb58065b5da54365330c6c1fcc5eed66d6a69702`，source clean。完整包SHA-256：`0a36669e66d3c1b4201b860550a97335917b9d5956a28f9513d24db467da8984`；392文件、275,964,911字节。build、sourceIntegrity、productionRuntime 为PASS；nativeInteraction 为NOT_RUN。CDS因Windows命令行长度限制跳过。清单：[preview-manifest.json](D:/Codex/worktrees/mihon-windows-beneficial-preview-20261110/app-desktop/artifacts/preview/windows/20261110T155358Z-439c609c/preview-manifest.json)。

最终交付采用 `eb58065b5da54365330c6c1fcc5eed66d6a69702`，并整合远端主干 `547ab31a855fac33750f5fa6a3b9a795244fe297` 的既有版本/回归修复。保留V1已验收的晚到页表轻入口、稳定像素交接、静态整页直接呈现、fullscreen adapter按需初始化，以及R2早期输入和R3缓存缺失反馈修复；R3/R4性能候选已撤回。R5新增诊断/JFR打包和R5-SH实验及专用测试均不导入；最终阅读器production相对已验收eb快照无差异。合入提交以包含本页的主干merge commit为准，精确SHA由交付回复及Git历史定位；不在提交内自引用尚未生成的SHA。

研究工作树的 `.test-tmp`、`.gradle-coordinator` 已原样移动到 `D:/Codex/archives/mihon/windows-reader-source-driven-20261109/` 下同名目录。历史记录里的 `D:/Codex/worktrees/mihon-windows-reader-source-driven-20261109/.test-tmp/...` 对应归档 `D:/Codex/archives/mihon/windows-reader-source-driven-20261109/.test-tmp/...`；日志同理。四份原始state、汇总、构建日志存在性与SHA复核见 [archive-checks.json](D:/Codex/archives/mihon/windows-reader-source-driven-20261109/archive-checks.json)。预览清单另存归档 `preview-manifests/`，捕获争议、遮挡样本和PARTIAL日志保留原状。大型帧、设备profile及原始数据只保留本机，不提交Git。已验收的beneficial-preview工作树/体验包原路径保留。

研究分支及R4冻结构建快照在主干推送核验后清理；其他任务的未提交内容与活动分支不在清理范围。停止研究不等于达成官方延迟、R≤100ms或K≤60ms目标，所有仍未证实推测保持未证实。

## 主干整合核验

无冲突整合；实际阅读器production与已验收eb58065无差异，远端版本/回归修复保留。沿用V1真实首图/交接、R2输入、R3撤回44项及R4撤回32项的有效证据，不重跑模块/仓库全量或窗口性能矩阵。

新增整合focused为真实 `DesktopReaderRuntimeFactoryTest` 的 entry等待视口、独立header lease及cold-tail三项。首次组合2通过、cold-tail在10秒上限超时（该项10.604秒）；仅为该用例的六处等待增加阶段、耗时、位置/单元诊断，没有改变条件、超时或产品逻辑，随后单cold-tail 4.117秒通过。最终是2+1组合通过，不称三项一次全绿；首次间歇超时根因未证实，未宣称bug已修复。RED XML在主干工作树 `.test-tmp/closeout-reader-merge-red/`，PASS XML另存 `.test-tmp/closeout-reader-merge-green/`；两轮日志为 `.gradle-coordinator/closeout-reader-merge.log` 和 `closeout-cold-tail-stage.log`，均退出且无后台实例。该未解测试时序风险保留，不能以复测通过抹掉首次失败。

本次唯一额外测试改动是上述诊断；ktlint 1.8.0对整个修改文件无诊断，报告 `.test-tmp/closeout-reader-format/ktlint.json`；237个证据定位检查零失败，`git diff --check`通过。未改产品逻辑，不新增优化或声称新的毫秒收益。
