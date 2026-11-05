# Android Compose rc01 单页首次打开实验

日期：2026-11-05。结论：**保留 B9，撤回候选依赖约束。** C0/C1 完成，C2 只执行 A1→B1 两次计时首次，C3 按首轮门槛早停，C4 已完成。未执行反序、暖态、官方重录、第二个候选或发布/推送。适用范围仅为本机已预热后的 Android 单页 RTL 新进程首次，不代表升级首次、冷磁盘、其他设备或 P95。

## 结果与裁决

单位 ms；F 为应用主线程收到 DOWN 到正确地图首见的保守原生相邻帧界限，点值取上界。K 为同视口、同 YAVG<17 判据的近黑区间；单个近黑帧的下界为零，不能据此宣称没有黑屏。下表区间按 0.001 ms 向外取整，未取整值和全部身份见[逐样本表](android-reader-compose-rc01-first-open-2026-11-05.csv)。

| 样本 | 末次遍历 CPU T | 正确地图首见 F [L,H] | 近黑 K [L,H] | DOWN→Reader 创建 / 窗口完成帧 |
|---|---:|---:|---:|---:|
| A1：B9 首次 | 19.923 | [320.047,337.315] | [0,34.445] | 53.769 / 126.797 |
| B1：rc01 首次 | 20.656† | [303.490,320.971] | [0,35.510] | 49.509 / 118.455 |

B−A：T 的计算差为 **+0.732815 ms**，F 点值为 **−16.343694 ms**，F 差距区间为 **[−33.823777,+0.923388] ms**，跨零，正确漫画没有可分辨地提前。两份 K 上界均≤60 ms，首图后未见再次近黑；地图、菜单、5→6→5、正常返回与恢复均通过。

首轮未满足“B1 的 T 小于 A1”这一必要条件，因此**不进入反序和暖态**。†B1 另有一条未定位来源的 trace 丢弃告警，T 仅作带限制的计算值，不能据此宣称 Compose 导致 CPU 回退；将它视为机制证据未确认，仍不满足进入反序的门槛。即使暂时采信该计算值，也没有 T 减少。F 的独立视频区间仍跨零，不能宣称首图收益。采集链路的固定偏差及联合采集开销未在本轮重新校准，F 是同口径视频中的首见界限，不是物理面板扫描时间。本轮也不符合“布局工作减少”的表述；窗口完成帧点值较早不能替代 CPU 和正确漫画首见。

这次只保留控制 B9；没有从历史官方差距减去本轮点值，没有把两个单次样本推断为稳定退化或普遍收益。B2 首次、B2 暖态、A2 首次、A2 暖态均未执行，原因是首轮早停，不是缺失的待补采项目。

## C0：单变量与依赖身份

工作树 `D:/Codex/worktrees/mihon-reader-o-series-20261104`，分支 `codex/reader-open-o-series-20261104`，开始 HEAD `5ae58d536556187756d156f9c18458a35b7394a9`，工作区干净。前一产品/归因基线 `91adf3169ec7a91f00ae25a4030bb8dd960a2d8c` 后只有本计划文档。B9 `source-input.json` 中全部列出的源文件逐个 SHA 核对无差异，冻结 `source.diff` 已读取核其范围及 SHA；本次最终没有保留产品源码变更。

控制与官方归档 APK 的实际 SHA 分别是：

- A：`4c3c6f82eea657aecd0c261972fa04c965a99ac5468f21309b87b032f23ccc59`；复用已有 B9，没有重建控制。
- 官方归档：`7a7969564a604bf74608758b540730e53dfe63e11782a718d32380dc9febed58`；仅读本机归档的 `res/M7.json`，没有操作设备官方应用。

先在未改源码时离线导出完整 `benchmarkRuntimeClasspath` 依赖树、benchmark/release 的组件/选中变体 JSON，并执行 runtime 的 dependencyInsight。实际路径为 `configureCompose` → `implementation(platform(androidCompose.bom))` → app/模块及其他 Compose 请求。BOM `2026.03.00` 约束 runtime 为 1.10.5，但冲突选择实际为 1.11.0-alpha05；runtime 原子组约束和 JetBrains Compose 1.11.0-alpha03 的 Android 依赖参与选择，不能把 BOM 标签作为实际版本。

采用更窄的 **app Android implementation constraints**，没有替换共享 BOM 或 buildSrc。目标全组为：

- animation：animation、animation-core、animation-graphics。
- foundation：foundation、foundation-layout。
- material：material、material-ripple。
- runtime：runtime、runtime-annotation、runtime-retain、runtime-saveable。
- ui：ui、ui-geometry、ui-graphics、ui-text、ui-tooling-preview、ui-unit、ui-util。

以上 18 个逻辑坐标全部选择 1.11.0-rc01；A 的 material:material 为 1.10.5，其余 17 个为 1.11.0-alpha05。解析差异共 36 个坐标更新，正好是这 18 个元数据坐标及对应的 18 个 `-android` 实体；无新增或移除组件，无额外内部传递项升级。BOM 本身仍为 `androidx.compose:compose-bom:2026.03.00`，未采用官方的 `compose-bom-beta:2026.04.00`，因为窄约束已直接满足单变量要求。

Material3 固定 1.5.0-alpha14，Activity 1.13.0，Android Lifecycle 2.10.0，Kotlin stdlib/编译插件 2.3.10；AGP、R8、图片库、所有业务源码未变。实际 app 约束与试解析的 benchmark/release 图逐个组件/变体一致，两变体目标库一致。仅修改 app Android 声明，没有触及跨平台版本目录或桌面运行时；未构建桌面。本轮没有 Kotlin 升级或阅读器源码冲突。

完整解析、选择原因与差异在 `.test-tmp/compose-rc01-20261105/{a-*,b-*,b-built-*,dependency-diff.json}`，协调器日志键为 `compose-rc01-a-graph`、`compose-rc01-a-runtime`、`compose-rc01-b-narrow-graph`、`compose-rc01-b-focused-green`。这些大原件不提交。

## C1：唯一候选和真实验证

唯一候选通过协调器 `compose-rc01-b-build` 执行 `:app:assembleBenchmark --offline --no-parallel --max-workers=2`，exit 0，7m11s；R8/资源压缩开启。候选 SHA：`c6794d50d8f71da094b72b4f8de6238718fe2776a2629705cb05033b40213c6b`。

A/B 身份均为 `app.mihon.desktop.fork.benchmark`、code 134、`0.19.4-aex.116-benchmark`，minSdk 26、targetSdk 36、非 debuggable，universal 同 ABI；证书 SHA-256 为 `fb141e4e749dc634a3d5d79e179a1057ab81fa1fd7cb5077b6dfeccfe11f3079`。已留 APK、mapping、manifest、badging、签名验证、源码快照/差异和构建时间。A 保留其原 sourceRevision `615c8ea67075b15fda63ed3473ea64fee998b0d4`；B 的源码 revision 为开始 HEAD，生成身份差异仅作构建身份记录。

**许可证清单的边界**：B 包的 `res/M7.json` 仍列 A 版本，构建的 `prepareLibraryDefinitionsBenchmark` 显示 UP-TO-DATE；仅凭该清单无法证明真实库。按计划的后备路径，核对了 B 的实际解析图、18 个 rc01 Android AAR 文件/SHA，并对每个 AAR 的全部 `.class` 名称及内容计算摘要，与 R8 输入 jar 的相同摘要逐一匹配，**18/18 相符**。jar 容器本身因重打包不同，未把容器 hash 不同当成 class 不同。证据 `b-runtime-artifacts.json`、`b-r8-input-files.json`、`b-aar-r8-proof.json`。没有为修清单再构建候选，也没有把旧清单谎报为 rc01。

指定两项测试真实执行生产 Reader/Compose 接线，XML 为 tests=2、failures=0、errors=0、skipped=0：

- `eu.kanade.tachiyomi.ui.reader.ReaderChapterErrorDialogIntegrationTest.O9 unrelated state keeps brightness wrapper idle while actual brightness still draws`
- `eu.kanade.tachiyomi.ui.reader.ReaderChapterErrorDialogIntegrationTest.initial chapter error has a visible close action which finishes the reader`

最终测试/产物诊断协调器 `compose-rc01-b-tests-artifacts` exit 0（24s）；`compose-rc01-b-focused-green` 中 `:app:spotlessKotlinCheck` 实际通过，其整体任务因临时诊断脚本的 artifact variant 查询失败，并非格式或测试回归。修改查询使用明确 AAR artifact view 后成功，没有改产品。归档 `focused-test-results.xml`。`git diff --check` 通过。未执行模块全量、方法采样、探针或正式构建。

过程失败原件保留：首次误用不存在的 `spotlessKotlinGradleCheck`，未执行测试；离线缺 `ui-test-junit4:1.11.0-rc01`，随后经本机代理仅补缺失固定测试依赖，未刷新缓存；一次格式失败为本次 app/build.gradle.kts 写入 CRLF，核对 Spotless 输出与原文本相同后只规范 LF。没有修改任何阅读器 Kotlin 或测试断言。候选构建被冻结时的换行仍在原快照，LF 规范化只改文件编码表示，未改变已构建的依赖声明，也未重建第二包。R8 的 window extensions/sidecar/re2j missing-class 警告保留于成功构建日志，未为本实验追加 keep 或依赖。

候选原件：[APK](D:/Codex/worktrees/mihon-reader-o-series-20261104/app/artifacts/android/diagnostics/reader-compose-rc01-20261105/Mihon-Fork-compose-rc01-benchmark-universal.apk)、[产物清单](D:/Codex/worktrees/mihon-reader-o-series-20261104/app/artifacts/android/diagnostics/reader-compose-rc01-20261105/artifact.json)。它是已撤回的实验包，不是交付推荐包。

## C2/C3：条件、原生帧和限制

USB serial 当次枚举为 `5JB0224111000992`，PCE-W30 / API31；所有设备命令显式绑定该 serial，目标仅隔离 benchmark。相同 USB 连接，电量 100%、电池 26.0°C，四份安装前后条件均 Thermal Status 0；实际编译头均 `compiler-filter = verify` / `compilation-reason = install`。未清数据/缓存，未强编。窗口/转场动画读回 1.0/1.0，animator/peak/min 刷新设置均为 null（未指定，未声称为 1）；display 读回当前 60.000004 Hz，override 1920×2880。设置均未写入。

两块均保数据重装、相同一次未计时预热：真实书架→Real CBZ 04→Volume 04，菜单打开/设置/关闭、单页 RTL、fit、横图自动放大 OFF、正确地图第5/187页、5→6→5，正常返回详情保存第5页，强停；正式首次均从新进程经相同真实导航再点章，没有深链或 prep 替代真实入口。章节实际 SHA 均 `b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677`。两块设置、目标地图、6页、回到5页的同视口 RGB 摘要完全相同，原截图已保存。背景为原黑色、probe OFF；未切模式或动画做“优化”。

复用旧 `run_ui_pair.py`、`capture-raw.py`、时钟 helper、原生 PTS/包读取及视频离线分析；只在新忽略目录的副本加入 first-only 开关，保留采集时序及返回/清理，不使用旧安装分支。T 复用既有通用 traversal 与全核主线程 sched Running 的交集算法，窗口为 DOWN→首个 Reader 关联 actual frame 的 ts+dur，两份均 4 次遍历，末次均在 Reader 创建完成后开始。通用 traversal 本身窗口身份仍标 unknown，不能冒充已证明该 slice 属于 Reader 窗口。逐 slice/Running/帧 SQL 和 CSV 均保留。

A1 PID 15206、B1 PID 20596，boot 均 `b3b19a6e-5242-4646-b7dc-ef62d12da9f9`。两份均核唯一 Reader StartActivity、同一 PID、设备输入包络、原生包/解码帧数一致、PTS 严格递增、无 B 帧、同 boot 前后 helper 包络覆盖视频。A 的 trace clock snapshot 比 helper 包络最多超出 **520 ns**，在最终 F 两端各额外扩宽 520 ns（原始 summary 和 helper 不改写）；B 为 0 ns。原生 PTS 自身按既有函数计 ±1µs，F 用前一帧下界和首正确帧上界。

A1 无 trace error；B1 有 `trace_sorter_negative_timestamp_dropped=1`，具体丢弃事件与原因 **UNKNOWN**。输入/Reader/时钟/视频一致性核验通过，保留视频 F/K；CPU T 和辅助 trace 端点附警告，不作可靠性已完全证实的机制结论。没有重录来抹掉警告；即使剔除该 T，仍因必要证据未确认而不能进入反序或采用。

A 的最后可辨详情/首近黑/末近黑/首正确帧为 **106/107/107/108**，B 为 **97/98/98/99**；完整原生帧及 PTS 原件保存在各样本 `boundary-originals/`，与整段 raw video/trace 同存。纹理以 World Map、中国字注记及页内图形核对，亮度只定位近黑候选；连续原生 contact、全帧信号和后段抽帧核无再次近黑。精确 PID/boot、输入时间、offset、所有帧号/PTS、trace/video SHA 见逐样本 CSV。

## C4：归档、撤回及收尾

统一原件根目录：[compose-rc01-20261105](D:/Codex/worktrees/mihon-reader-o-series-20261104/.test-tmp/compose-rc01-20261105/)。大依赖图、临时工具副本、原视频、trace、PNG 和包均忽略归档；本次只提交本报告、必要的逐样本 CSV 以及执行计划的状态/结果链接。

本次 22 行 Android 依赖约束已仅撤回自己的修改，app/build.gradle.kts 与开始 HEAD 完全相同。B9 已保数据恢复并读回实际 SHA `4c3c6f82eea657aecd0c261972fa04c965a99ac5468f21309b87b032f23ccc59`；未计时正常打开恢复第5页、正常返回详情保存通过。最终 probe OFF、隔离 PID 为空、无本任务 scrcpy/Perfetto 采集进程、`mWakefulness=Asleep`、`mWakefulnessChanging=false`，见 `final-device-cleanup.json`。平板官方 APK、数据、设置和编译状态从未操作。

分支仍为用户指定实验分支；开始时相对本地 main 为左2/右15（非远端新鲜查询），未整合或切换主干。本轮只本地提交，没有推送授权或发布动作。

## 【功能特性】

- 维持原 B9 单页阅读体验；未采用 rc01 实验包，不增加产品能力承诺。

## 【BUG 修复】

- 本轮是依赖归因实验，未宣称修复首图延迟或其他 bug。

## 【验收清单】

- [x] 控制与候选依赖解析 → 18 个逻辑坐标、18 个 Android AAR 变化，无无关运行库升级；实际 AAR class 与 R8 输入 18/18 对应。
- [x] 两项指定生产接线测试 → XML 2/2 通过，格式与 diff 检查通过。
- [x] 同平板 A1→B1 真实首次打开 → 逐样本 T/F/K、原始帧与告警如实保留，总计2次；不进入反序/暖态。
- [x] 撤回候选 → 保数据恢复 B9，正常地图/进度、probe OFF、采集退出及熄屏读回通过。
