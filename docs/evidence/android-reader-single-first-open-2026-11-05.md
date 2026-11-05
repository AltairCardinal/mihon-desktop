# Android 已下载普通单页首开：分阶段执行结果

日期：2026-11-05。执行计划：[单页首开计划](../roadmap/2026-11-05-android-reader-single-first-open-plan.md)。本轮在指定工作树 `D:/Codex/worktrees/mihon-reader-o-series-20261104`、分支 `codex/reader-open-o-series-20261104` 执行，初始工作区干净，HEAD `12fa5558f8ff441ff0a501a8c4494cb06c8edcf2`，`198c2fc7fa84ab19c62ab8b1f0e49c34fd93f7d7` 为祖先。原目录 main 的 O1 草稿未修改。

本报告区分机制证据、正确漫画像素的真机端点和采用决定。窗口帧不是漫画首图；不同阶段或嵌套区间不相加。本轮不构成完整发布、P95、无采集日常耗时或跨设备承诺。

**最终结果：A 无合格候选；B 真实归档首像素开流 2→1，但首图收益未通过同轮对照及近黑门槛，完整撤回 B，源码与设备均保留 B9。** 两阶段按停止规则收口，没有新增采用的产品优化。

## A：首个 Reader 窗口帧之前的工作

**决定：A1 完成，A2 无合格可执行候选，不实施产品改动，不增加诊断包。真机首图收益未测得。** 因而 B 控制固定为上述 HEAD 的 B9 production，与已留存 B9 APK 对应，未叠加 A 优化。

六份既有首次原件来自 `D:/Shell/Github/mihon/.test-tmp/reader-open-o-final-20261105/`。按 capture、独立 trace check、CSV、前后设备 helper 的 boot 和 PID 交叉核对；通过 PID/UPID/主线程 UTID 约束 sched 与 slice。以下均为毫秒；`→` 从目标应用主线程 DOWN 开始。创建列是 performCreate 起点，窗口帧列是 DOWN 后首个对应 actual frame 的 `ts+dur`，六条 dur 均为正，未换用后续帧。

| 样本 | →StartActivity | →创建 | →窗口帧完成 | Running | create | traversal | recompose | inflate |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| B9 r1 | 28.946 | 48.348 | 108.465 | 61.505 | 17.654 | 20.794 | 5.118 | 1.173 |
| B9 r2 | 29.227 | 52.750 | 121.560 | 73.185 | 22.649 | 24.945 | 5.538 | 1.434 |
| B9 r3 | 26.652 | 43.809 | 107.373 | 56.392 | 17.769 | 19.918 | 4.967 | 1.381 |
| 官方 r1 | 20.001 | 36.419 | 79.267 | 27.987 | 7.632 | 7.991 | 1.311 | 0.765 |
| 官方 r2-equivalent | 21.457 | 38.104 | 83.103 | 26.636 | 6.281 | 7.240 | 0.910 | 0.779 |
| 官方 r3 | 21.337 | 36.181 | 77.649 | 29.564 | 8.158 | 8.149 | 1.555 | 0.927 |
| B9 中位数 | 28.946 | 48.348 | 108.465 | 61.505 | 17.769 | 20.794 | 5.118 | 1.381 |
| 官方中位数 | 21.337 | 36.419 | 79.267 | 27.987 | 7.632 | 7.991 | 1.311 | 0.779 |

后四列为同名 slice 区间并集与 Running 的交集，彼此可嵌套，不能相加。[六行原始端点及完整分类 CSV](reader-single-first-open-a1-2026-11-05.csv) 另保存创建起止 ns、互斥分类、线程状态和 IPC 非运行时间。互斥分类采用 binder > touch > recompose > inflate > layout > draw > traversal > create > 未归属的明确优先级，逐条总和等于 sched Running。thread_state 总和等于窗口 wall；R/R+ 排队与 S/D 等待另列，IPC 内非运行区间与这些状态重叠，不再相加，也不据此全归因于系统服务。

通用 traversal、draw 和 Compose 没有唯一窗口标识：创建之前的遍历不能属于尚未创建的 Reader，最后一次遍历也保留“窗口未知”，不凭时间接近把旧详情淡出计为 Reader。官方 ART 状态未知，包间差异只能定位机会。

### 调用映射与 A2 门槛

- B9 三条 performCreate 均有三个 inflate 区域、13 个 Binder transaction 子区间。XML 对应 ReaderNavigationOverlayView、ComposeView 等；余下创建 CPU 没有应用函数边界，不能命名为某项多余初始化。
- B9 最后一次通用 traversal 均有两次 recompose/applyChanges/onRemembered、两次 insets/measure、一次 layout；官方重组次数为 2/1/2。`ReaderActivity.setComposeOverlay` 确实持有 remembered 设置模型，但上游 `7a917968e3` 也如此，模型的两个 stateIn 使用 Lazily。没有 composable 身份、状态载荷或对象构造次数，无法证明“隐藏分支重复执行同一结果”。
- `onResume` 和焦点入口会调用 `refreshOptionalReaderMemoryPressure`，消费方只在双页 adapter；三条首次 prefix 均只见一次 `IActivityManager:70`，位于 performResume，wall 为 0.174/0.157/0.147 ms。没有厂商 transaction 号到函数的独立映射，首帧前也没有第二次焦点刷新证据。两个静态调用点不能证明首帧内重复调用，且该亚毫秒区间不能解释 create/traversal 的十余毫秒差。

因此两条嫌疑链均在“真实函数及冗余调用”处缺证，未达到至少两条 B9 trace 支持具体冗余的门槛；不把宽泛 Compose 差距转为优化实现。A 未构建新包、未新增性能样本、未操作官方应用。原始查询、SQL/CSV、嵌套关系、六份归因及否决详情保存在工作树 `.test-tmp/reader-single-first-20261105/a1/`；这是过程证据，不是新生产设施。

## B：已下载普通单页合并读取

控制为 B9，源码 HEAD 固定 `12fa5558f8ff441ff0a501a8c4494cb06c8edcf2`。APK SHA-256 为 `4c3c6f82eea657aecd0c261972fa04c965a99ac5468f21309b87b032f23ccc59`，production 指纹为 `4ffa9b726e52cfcf080923994f0d525169803dd1bb793eeae4337619d1b31791`。旧 O1 补丁只作为夹具与语义参考，没有整包移植。

### 候选实现与维护边界（已撤回）

`DownloadPageLoader` 只在已解析归档建页后标记原 stream factory 及 encoded version，不为识别来源再次查目录。普通 pager 从该标记捕获 factory、encoded/content version、retry sequence 和现有事实；同一 IO 任务以独立 peek 读取最多 512 KiB 的头，不移动显示流位置。NORMAL 直接继续同流全读、关闭原流，沿既有动画、裁切、背景和解码链准备。缓存已有信息时直接整读；非 NORMAL 或解析失败先关闭有界头、发布已取得的结果，再由兼容链整读，避免读完整图后丢弃重读。

回 Main 后先检查协程 active、holder 的 decode/attachment/presentation 代次和页状态，再在同一 content 锁中检查 stream、版本、重试并发布事实，捕获发布后的呈现身份。外部替换、重试、取消与自身 EXIF 方向更新分别处理；过期结果不能绑定旧图，原流及临时区域文件仍按原所有权释放。双页、条漫、在线和非下载归档来源不接入新增分支。未改质量、缓存上限、绘制许可、阅读进度或探针开关。

三份 production 与一份测试 diff 超过 400 行，主体是同一下载归档链的真实挂载／原生绘制夹具与生命周期边界；没有扩展公共 domain、导航或新增配置设施。

### 真实行为 RED / GREEN 与审查

测试真实执行 `DownloadPageLoader(resolved UniFile)` → `ArchivePageLoader` → `R2LPagerViewer` Ready 订阅 → `PagerPageHolder` → SSIV/Canvas 正确像素。Windows 不具备 Android libarchive / image decoder JNI，夹具只在这些 JNI 接口适配真实 ZipFile 和已准备图片字节，未替换生产 loader、头解析或 holder，不手塞已绘位图。

| 行为 | 原始失败／最终结果 |
|---|---|
| 普通 WebP 首正确像素 | B9 RED：2 次开流、字节 `[518,518]`、关闭 `[1,1]`；候选 GREEN：1 次、`[518]`、`[1]`，像素正确 |
| 非 NORMAL EXIF 原件 615535 B | GREEN：头 524288 B + 整图 615535 B，共两次、各关闭一次；首次方向 TRANSPOSE，未多读整图 |
| 读取中外部替换 | 先绘出新红图，再释放旧绿图 IO，旧结果不得覆盖 |
| 单独推进 retry sequence | 不借用取消/Queue 混淆因果，拒绝旧读取结果 |
| 读取中取消 | 原流关闭，不发布事实、不绑定已准备旧图 |
| 首次 factory open 失败 | 审查新增 RED：尝试 1 次、0 B、没有正确像素；修复后 GREEN：尝试 2 次、字节 `[0,518]`、关闭 `[0,1]` 并正确绘图。仅首 open 失败走兼容回退，取消继续传播，不捕获整图读取／准备失败来额外重读 |

一次独立审查发现 B-R1 首开异常过早报错且遗漏 HEADER_END，已先写真挂载失败测试再修复，并完成一次限定复核；最终无未关闭阻塞项。初期 fixture 未绑定 loader、初始偏好未排空及一次 suspend 编译错误均保留，不计作有效行为 RED。更新夹具顺序后重新在冻结 B9 核得相同 2 次开流 RED，没有修改老断言凑绿。

最终 `reader-single-download-final-green` 协调器任务 exit 0，58 秒；9 个不同方法（holder 7、fact 2）无失败／错误／跳过，`:app:spotlessKotlinCheck` 和 `git diff --check` 通过。选择范围如下，方法前缀明确只选择本轮五个 `download archive ordinary` 用例：

```powershell
python scripts/gradle-coordinator.py run --key reader-single-download-final-green -- .\gradlew.bat :app:testReleaseUnitTest `
  --tests "eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPagerPageHolderLayoutTest.download archive ordinary*" `
  --tests "eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPagerPageHolderLayoutTest.actual ordinary holder consumes encoded direction before its native image binding" `
  --tests "eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPagerPageHolderLayoutTest.ordinary ready status lifecycle keeps drawable tickets through forward and return navigation" `
  --tests "eu.kanade.tachiyomi.ui.reader.loader.ReaderPageFactProductionWiringTest.malformed image metadata consumes at most a bounded header and closes its stream" `
  --tests "eu.kanade.tachiyomi.ui.reader.loader.ReaderPageFactProductionWiringTest.late header of replaced content cannot publish dimensions for the new stream" `
  :app:spotlessKotlinCheck --offline --no-parallel --max-workers=2
```

已知未关闭的基线失败：`ReaderPageFactProductionWiringTest.cancelled local header propagates cancellation without publishing a failed fact` 在冻结的三个 B9 production 原始字节上也因 `cancelled metadata must not finish chapter materialization` 失败；P0 后该 materialization 已无预读头路径。该证据单列，没有修老断言，也没有把它称为本次回归或最终通过。新增真实 holder 读取取消用例已通过。Windows XML stderr 另有缺失 image decoder JNI 的异步异常，故 focused 结果不冒充完整设备 native decoder 验收；真机另行验证真实包。

RED/GREEN XML、精确选择器、最终四文件 SHA、基线旧失败及原始字节、实现回执保存在 `.test-tmp/reader-single-download-20261105/`；独立审查保存在 `.test-tmp/reader-single-first-20261105/b-review.json`。未运行全模块、全量或正式发布矩阵。

### 独立真机对照

冻结候选：[benchmark APK](D:/Codex/worktrees/mihon-reader-o-series-20261104/app/artifacts/android/diagnostics/reader-single-first-download-20261105/Mihon-Fork-reader-single-first-download-benchmark-universal.apk)，SHA-256 `a4091a05470df4a34fa374df020e7b09a3c6d75b988e13c0c838452c67eef08d`。基础 revision `12fa5558f8ff441ff0a501a8c4494cb06c8edcf2` 加冻结四文件 diff，diff SHA `13dc436ad4c7747be6fc4364d1e4973a07aa9525c30e5ef48e58fd748f17310a`，production 指纹 `2391709079bd606b0994d46360bf180ac2cdab19dc9a765f46c55fbef877efb4`。`:app:assembleBenchmark --offline --no-parallel --max-workers=2` 经协调器 `reader-single-download-benchmark`，2 分 36 秒成功，构建前后源码摘要一致，仅构建一个候选包。

两臂均隔离 `app.mihon.desktop.fork.benchmark`、code 134、`0.19.4-aex.116-benchmark`、非调试、R8/资源缩减、原 benchmark 证书 SHA-256 `fb141e4e749dc634a3d5d79e179a1057ab81fa1fd7cb5077b6dfeccfe11f3079`。控制包来源仍保留 B9 原始构建 revision，不改标为本轮 HEAD。包、mapping、签名、badging、源码 diff 和元数据均留在各自不可覆盖 artifact 目录。

设备为同一 PCE-W30 / Android 12 / API31、USB、1920×2880 当前视口，单页 RTL、5/187 地图、横图自动放大关闭、原动画/黑背景。章节 SHA 与计划一致。控制与候选每个安装块都保数据正常重装，实际 APK SHA 与实际 OAT 读回后，做相同的设置核对／预热、正常新进程 5→6→5 及退出保存，再强停进入计时首次，随后同进程再次。场景是安装后已预热的新进程首次，不是升级首次或冷磁盘。原官方包及其设置/数据未操作。

首轮安装与采后 OAT 都为 `verify/install`，USB/100% 电量；控制安装温度 23℃、采后 24℃，候选安装 24℃。设置目标区域 hash 完全一致，地图／第 6 页／回翻地图 ROI 与控制逐像素相同。动画读回 window=1.0、transition=1.0、animator=null（未显式覆盖）；控制只保留采后读回，不能伪称逐样本前读回。候选采前同值，本任务未修改动画。

复用原生 PTS 和同次设备 helper 时钟包络，逐份核 PID、boot、唯一 Reader 启动、trace error、clock residual、帧数、无 B 帧、严格递增 PTS 和目标地图纹理。局部复算直接复用 `reader_open_replay_summary.frame_bound/signal_runs`，以旧已审 `final-b-single-r1-first` 复核首图区间 `302.470767–336.456809 ms` 与原 CSV 一致。新批次使用独立显式清单，没有混入原有 44 条／14 组统计。

首轮首次首图：控制 `[316.593102,332.922665]`、候选 `[310.630138,326.822701]` ms；候选减控制区间为 `[-22.292527,+10.229599]` ms，首正确帧点值约提前 6.10 ms，跨零不可判。候选近黑 `[34.880,67.644]` ms，跨 60 ms；暖态首图与控制重叠，无可分辨退化。由于点值、机制与正确性均支持且结论会影响采用，按计划仅追加一次反序两臂冷暖对照，先候选后控制，4 次打开；理由在原件目录 `reverse-reason.json`，不追样本。

### 逐样本端点与采用决定

本轮正式记录共 8 次打开；两组分别是控制→候选，以及唯一一次反序候选→控制。没有排除慢样本，没有第三轮、官方重录或跨轮合池。下表首图是相邻原生帧的输入相对区间，近黑是原生 PTS 视觉区间；无独立近黑帧时仍保留帧间隙。精确 DOWN、原生帧号/PTS、前帧 PTS、视频/trace SHA 和告警见[逐样本 CSV](reader-single-first-open-b-2026-11-05.csv)。

| 顺序／样本 | 首正确地图帧 | DOWN→首图区间 ms | 近黑区间 ms | ≤60 ms |
|---|---:|---:|---:|---|
| 首轮 B9 first | 96 | 316.593–332.923 | 34.464–67.191 | 未确认 |
| 首轮 B9 warm | 38 | 259.833–276.780 | 0–16.944 | 通过 |
| 首轮 B first | 101 | 310.630–326.823 | 34.880–67.644 | 未确认 |
| 首轮 B warm | 37 | 253.660–271.396 | 0–17.733 | 通过 |
| 反序 B first | 32 | 319.481–354.385 | 17.930–70.402 | 未确认 |
| 反序 B warm | 38 | 247.306–264.441 | 0–17.131 | 通过 |
| 反序 B9 first* | 29 | 296.538–316.146 | 16.320–53.020 | 通过 |
| 反序 B9 warm | 38 | 256.455–274.958 | 0–35.257 | 通过 |

候选减控制的差距界限按 `[候选低−控制高,候选高−控制低]`，逐对保留：

| 对照 | first 差距 ms | warm 差距 ms | first 点值变化 |
|---|---:|---:|---:|
| 首轮 | −22.293～+10.230 | −23.120～+11.563 | 提前约 6.10 ms，区间跨零 |
| 反序 | +3.335～+57.847 | −27.652～+7.985 | 延后约 38.24 ms |

*反序控制首次有 `trace_sorter_negative_timestamp_dropped=1`，其丢弃事件归属未知，不声称与目标路径无关。该次应用 PID、正值 DOWN 的两条输入送达、唯一 Reader 启动、同 boot helper 夹逼、源 packet/frame 数、PTS 和 clock snapshot 均通过，snapshot 包络残差 0 ns；故保留输入→录像端点并显式带告警，不从该 trace 做主线程 CPU 归因。其它七次无 trace 告警。即使保守排除此对照控制，首轮首图区间仍跨零，两次候选首次近黑上界仍超过 60 ms，采用门槛依然没有满足。没有为告警再采择优样本。

反序候选首次的首正确图前一个原生帧间隔为 34.901 ms，区间如实保留，没有插帧缩小误差。八次录像没有首图后再近黑段。四安装块的设置区域均同 hash，初始地图、第 6 页及回翻地图各自逐像素一致；八份采后地图也与已审参考 ROI 一致。四块实际 OAT 安装后及采后均 `verify/install`、USB/100%，电池温度按块为控制1 23→24℃、候选1 24→25℃、候选2 25→26℃、控制2 26→26℃，不据温度变化猜测此次差距原因。

**B 不采用。** 机制减少已证实，正确性用例、普通真机首图及往返通过；首轮提前量不可分辨，反序没有支持改善，暖态均未出现可分辨退化，但两次候选首次近黑均跨预算。未证明净首图收益，不能因一次开流结构更简洁而保留数百行异步生命周期修改，也不能抵扣旧 46.9 ms 差距。本结论限定本轮设备、内容、设置和采样，不否定不同条件的新证据。

### 精确撤回与最终现场

先保留冻结候选 diff/APK、最终 GREEN 与独立审查证据，再逐一核对候选四文件 SHA，仅按本任务保存的原始 bytes 恢复；四文件与 HEAD blob 完全一致，产品／测试 diff 为空。未使用 checkout、reset、stash 或覆盖原目录用户改动。撤回后仅做 SHA 与只读核验，复用未改变的 B9 证据，不宣称撤回后重新通过了 9 方法、全模块或全量。

广义 `source_snapshot` 恢复值为 `7cef813aa327eca5f9a55b83dfde296a54c8abce6b0f82a819ca6f5efd797680`。最初沿用旧 B9 广义指纹断言失败后，已核实唯一新增输入是旧轮离线 `scripts/reader_open_replay_summary.py`；排除恰好该非 APK 分析脚本记录，即精确复现 B9 artifact 口径 `4ffa9b726e52cfcf080923994f0d525169803dd1bb793eeae4337619d1b31791`。两种口径分别登记，未把不同哈希伪称相同；四个被撤回文件的原始字节核验不受此口径修正影响。

最终实际留装 [B9 APK](D:/Codex/worktrees/mihon-reader-o-series-20261104/app/artifacts/android/diagnostics/reader-open-o9-overlay-narrow-20261104/Mihon-Fork-reader-open-o9-overlay-narrow-benchmark-universal.apk)，设备实际 SHA 与控制一致，单页 RTL，第 5 页正常退出保存，probe enabled=false，隔离包无 PID，采集 server 无残留，Asleep/Changing=false；所有本任务 Gradle 协调器为终态，保留共享 ADB 服务。现场与撤回证据为 `.test-tmp/reader-single-first-20261105/{final-device-cleanup.json,restoration-verification.json,final-evidence-checks.json,summary-final.json,batch-manifest.json,trace-warning-review.json}`。原录像/trace/截图和查询只在忽略目录保留，仓库不提交私人原图或整份系统 trace。

本轮交付为计划收口、本报告及 A/B 精简原始 CSV。本地提交，不推送、不发布、不更新公共功能说明。A 的具体冗余仍未证实，B 无净收益采用；全章监听、绘制许可、共享代次与双页方案均未扩大立项。
