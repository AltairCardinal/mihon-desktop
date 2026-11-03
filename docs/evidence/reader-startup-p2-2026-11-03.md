# Android 阅读器首开 P2：编译条件与首图前等待

日期：2026-11-03。执行入口为[P2计划](../roadmap/2026-11-03-reader-startup-p2-plan.md)，起始主干 `3376c5314e5f5d2101f0bc2fe698aa1ac0a5c875`；保留P0产品成果与P1诊断。本轮不重复P0/P1实测，不自动进入B、Windows或全量验证，不推送或正式发布。

本轮未采用新的启动优化，继续保留P0按需读取成果；正确首图与正常翻页已确认，仍未确认无内容间隔≤60ms。

## 编译条件核查

核对设备PCE-W30/API31、隔离包及实际安装APK SHA256 `f9b44407e8abb9e023d090d50967cc26fe291c826cdc75671071fd529afd0bfe`（P1控制版）后，只停止该包，保留数据。读取本机 `cmd package help`、logcat帮助、目标包完整dump和dexopt查询原始输出，再进行一次 `cmd package compile -m speed -f app.mihon.desktop.fork.benchmark`；未清设备日志、应用数据、官方编译状态或重启平板。

该次请求实际为 **exit0 / Success**。设备时间窗口原件及未只按包名过滤的all-buffer短窗日志保存在 `.test-tmp/reader-open-p2-20261103/compile-diagnostic/`。日志51,784B，含一条 `-T 1` 的前置上下文；请求期目标包 `dex2oat64` PID28264，命令为 `--compiler-filter=speed`，随后目标APK `DexInv END (success)`，dex2oat约32.695秒。自有logcat主机／设备PID均按身份清理，回执 `logger-cleanup.json`。

PMS `dumpsys package dexopt <package>` 前后均exit0但输出为空，不能当状态查询成功；目标完整dump只给出Compiler stats base.apk 878→32925，不是filter。因当前系统提供oatdump且目标文件可读，读取目标 `oat/arm64/base.odex` 的实际头，得到 **compiler-filter=speed、compilation-reason=cmdline**（`oat-header-after.json`）。这确认本次目标编译产物状态，不回填P0/P1历史未知状态或等同官方运行条件。

本次编译时设备为Asleep/锁屏，未进行UI输入。系统明确记录“The screen is off, Dex2oatControl does not take effect.”；另有open file error、MemLeak库警告及 `DexToOpt has run out of time 30000` 等日志，但它们与实际Success并存，不能当成已证实的失败原因。P1旧Failure本轮未复现，缺少旧失败窗口系统日志，故不能宣称根因已修复或断言屏幕状态造成旧失败；没有具体修复动作，也没有第二次诊断请求。

## 一级接线及验证

保持产品顺序，在P1固定事件数组末尾追加七点。VM传初始init捕获request，在既有最多32个Session中只保存章节、页表、首页及待发布ViewerChapters的弱身份；Activity要求自身捕获request与具体对象一致，单页holder通过所属Activity及同对象取该捕获request，不回退全局active。关闭时不建立绑定，重配置／导出／弱引用失效后拒收；没有诊断字段进入共享业务状态或持久数据。

原有单方法先确认旧记录器拒绝新 `ACTIVATION_UI_ENTER`（期望true、实际false），再扩展真实helper检查七新点首次命中／重复拒收、错误页／请求／章节／页表／首页、相同chapter/pages再次激活的旧ViewerChapters拒收，以及关闭与重配置失效。最终同方法GREEN和受影响格式通过；中间仅修正新增调用／测试的排版，未扩大验证范围。XML及协调器日志在本轮原件根和 `.gradle-coordinator/reader-open-p2-level1-probe-*.log`。

## 本轮结论与样本条件

**只采用有价值的诊断，未实施产品优化候选。** 一级把主要区间定位到Activity接收章节之后；二级确认两次均直接应用、没有等待pager idle，adapter更新本身不足1ms。首次主要剩余为目标holder创建后到setImage入口54.709ms，暖态该段9.373ms；主线程执行与首帧工作混合，具体主导调用仍未证实。不能由这些wall区间推导某个事实collector、owner校验或协程派发是根因，故不删除保护或强行更改启动方式。P0成果保留，P2-3/4不适用；两级预算已完成，不继续第三轮加点。

固定PCE-W30/API31、1920×2880、隔离包 `app.mihon.desktop.fork.benchmark`、版本134、原诊断证书；R8／资源压缩开启、non-debuggable。两份均从本轮起始提交加对应冻结diff构建，`source-input.json`记录源码文件hash、diff SHA及fingerprint。签名SHA256均为 `fb141e4e749dc634a3d5d79e179a1057ab81fa1fd7cb5077b6dfeccfe11f3079`。

| 仪器版本 | APK SHA256 | 同进程样本身份 |
| --- | --- | --- |
| 一级七点 | `b743cdf65a441bad5a958502739a78dae0e9bc722293097c7ef90aa40c6f109d` | PID26820、request1/2、各36事件 |
| 二级追加三点 | `f9080c59c7dcd8b9a74f2e17b76a29a86d580d93202e1c18d4c575daf2749221` | PID24353、request1/2、各39事件 |

两份安装仅 `-r` 保留数据；各自正常安装后固定一次speed请求均Success，并读回实际OAT `compiler-filter=speed`。原件在 `level1-compile/`、`level2-compile/`。请求策略及可读状态一致，不宣称运行时缓存、设备负载或历史P0/P1／官方编译条件相同。二级是新增仪器版本，不能与一级混池或扣除固定探针成本。

目标仍为 `Real CBZ 04→Volume 04`，同SAF CBZ SHA256 `b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677`；单页RTL、横图自动放大OFF、hint OFF、黑背景、原动画。脚本从P1校正版复制，一级及二级各自适配实际APK白名单；版本、解锁、焦点、PID、page0、必要事件和onStop导出守卫保留，未执行prep或改数据库。`script-provenance.json`及各APK manifest保留来源。四次均有效，无剔除慢样本：chapter1/page0/request一致、事件唯一且单调、UI1/187、采集status OK、离开Reader前JSON不存在，onStop后完整导出。

## 一级：八段闭合

以下为同一请求的相邻时间差，单位ms；计算先用整数ns核验恒等式，表格显示六位小数。它们是wall区间，不是独占CPU。

| 区间 | 首次 | 同进程再次 |
| --- | ---: | ---: |
| PAGE_LIST_READY→ACTIVATION_UI_ENTER | 0.360938 | 0.207292 |
| →PAIRING_OWNER_READY | 2.068229 | 0.947396 |
| →READING_GATE_READY | 0.190104 | 0.031771 |
| →CHAPTER_STATE_PUBLISH_BEGIN | 0.250000 | 0.081250 |
| →ACTIVITY_CHAPTERS_RECEIVED | 0.113021 | 0.073958 |
| →TARGET_IMAGE_ENTER | **44.141146** | **37.282812** |
| →TARGET_HEADER_DISPATCH | 0.103125 | 0.126042 |
| →TARGET_HEADER_BEGIN | 0.172396 | 0.048958 |
| 精确总和P | **47.398959** | **38.799479** |

VM／pairing owner、进度门及状态发布不是这两个样本的主要P成本。READING_GATE_READY仅表示原分支已完成，不假定无痕分支也建立session；原隐私、进度和有效性检查保留。对应trace含通用UI帧／布局工作，没有可将44／37ms归给单一生产调用的栈证据，因此使用计划内唯一二级细分。

## 二级：实际应用、布局创建与holder首发

只在上述最大区间增加实际viewer应用入口、adapter返回、目标holder完成诊断绑定三个时间点；首次实际应用来源另记为非时间点布尔值。相同记录器方法先RED（旧实现拒绝 `VIEWER_CHAPTERS_APPLY_ENTER`），再GREEN；验证真实identity helper、首次来源不可覆盖及错身份拒收。STATE绑定失败时留缺点，不把未绑定发布记为有效链。所有原idle／adapter／holder顺序保留。

| 区间（ms） | 首次 | 同进程再次 |
| --- | ---: | ---: |
| Activity接收→实际viewer应用 | 0.841667 | 0.628125 |
| 应用→adapter更新返回 | 0.918750 | 0.531771 |
| adapter返回→目标holder创建点 | 3.383854 | 14.721875 |
| holder创建点→setImage入口 | **54.708854** | **9.373437** |
| 四段精确和＝原Activity→Image | **59.853125** | **25.255208** |
| 首次实际应用 pagerAwaitedIdle | false | false |

插入三点后的全链11段依次为：首次 `0.386459 / 2.065625 / 0.265104 / 0.419271 / 0.157812 / 0.841667 / 0.918750 / 3.383854 / 54.708854 / 0.104688 / 0.112500`，暖态 `0.193750 / 0.938542 / 0.044271 / 0.052604 / 0.071875 / 0.628125 / 0.531771 / 14.721875 / 9.373437 / 0.203125 / 0.245834`；整数ns之和分别精确等于P `63.364584 / 27.005209ms`。所有目标对象／请求及节点顺序有效，无跨尝试拼接。

具体链路已闭合到：`ArchivePageLoader.getPages`在页表阶段将页置Ready；下载归档的 `loadPage`仅检查未recycle。目标holder的创建点后，设置绘制回调、`MainScope.launch`启动 `loadPageAndProcessStatus`，其中 `supervisorScope`启动IO loader任务，并由状态Flow的首次Ready进入setImage；其间还可能经过attach／beginPresentation、其它布局与主线程任务。因此本样本不能解释为等待下载或页数据变Ready。

Perfetto映射后的holder→image区间：首次主线程Running约42.744791ms、Sleeping11.649480ms、Runnable0.181249ms、抢占Runnable0.133334ms；暖态分别7.849478／1.367187／0.062500／0.094271ms。通用slice可见Compose recompose／applyChanges、AndroidOwner measure/layout/draw及Choreographer/traversal；它们不提供目标holder方法栈或每个collector归属。**已确认**两次未走awaitingIdle、主要成本位于创建后的主线程混合区间；**仅相关、未确认**具体主导函数及可消除部分。Sleeping不能命名成pager idle，Running不能全部算作构造CPU，也不能把54.7ms归给187个事实collector或ensureSingleOwner。缺口是该区间内可关联具体调用／重复次数的执行栈证据，现有两个仪器版本不足以识别安全削减点。没有证据支持一个最小产品候选，故未做候选红绿／构建／三采样，也未新增第三仪器。

## 内部及外部端点逐次结果

| 版本／样本 | intent→页表 | 页表→OPEN_PAGE | OPEN_PAGE→DECODE_SUBMIT | decode→FIRST_DRAW | 四段和 | P / H / Q |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 一级首次 | 131.468750 | 58.557813 | 3.710416 | 75.885938 | 269.622917 | 47.398959 / 8.831250 / 2.327604 |
| 一级暖态 | 103.888021 | 43.275000 | 5.306771 | 78.179167 | 230.648959 | 38.799479 / 3.316146 / 1.159375 |
| 二级首次 | 129.395312 | 74.583334 | 4.479687 | 89.156771 | 297.615104 | 63.364584 / 10.340104 / 0.878646 |
| 二级暖态 | 101.961458 | 32.114584 | 2.119271 | 88.316666 | 224.511979 | 27.005209 / 4.374479 / 0.734896 |

单位ms。P＝页表→header BEGIN，H＝同一真实header调用，Q＝header END→OPEN_PAGE；P/H/Q和均回到对应第二阶段。首次二级慢于一级、暖态较快，保留波动，不能因未改产品逻辑而声称新增探针造成差值。历史P0均值267.500ms、P1首次／暖态327.496／283.932ms仅作跨轮参考，不证明本轮净收益或编译修复收益。

外部按原协议取同一真实触摸eventTime的**较早**主线程 `deliverInputEvent src=0x1002` DOWN span起点；同输入出现的较晚重复span不替换起点。表内TX使用首Reader actual-frame slice的 `ts+dur`，本轮四次dur有效，均Late Present。

| 样本 | DOWN→StartActivity | →performCreate | →首Reader TX end | →映射app FIRST_DRAW |
| --- | ---: | ---: | ---: | ---: |
| 一级首次 | 28.109895 | 47.984895 | 110.592187 | 331.273958 |
| 一级暖态 | 23.047917 | 39.803646 | 75.942189 | 277.778908 |
| 二级首次 | 25.918230 | 71.704167 | 126.416667 | 379.138021 |
| 二级暖态 | 26.632291 | 51.289583 | 88.198437 | 283.160677 |

Probe使用MONOTONIC，通过每条trace的clock_snapshot `BOOTTIME−MONOTONIC` 映射后才能与DOWN span相减。一级首次offset为230720111512987ns；暖态230720111511946／230720111512467ns（采用均值，跨度521ns）；二级首次230729102219947／230729102219946ns；暖态230729102219946／230729102220467ns（均值，跨度521ns）。这是有clock证据的跨钟映射，host时钟只作采集顺序，不作延迟起点。一级首次trace有1项negative_timestamp_dropped健康警告，查询的事件／clock／frame可用；未将P1旧无效TX替换成新结果。

## 视频四边界与体验目标

沿用P0/P1同一“残余细节／近黑”人工阈值，保存原生帧及其PTS，不fps重采样；节点为最后可辨详情、首近黑、末近黑、首正确漫画。无内容间隔下界＝末近黑−首近黑，上界＝首漫画−最后详情。

| 样本 | 四个零基帧号 | 四个原生PTS（秒） | 近黑下／上界ms | ≤60ms |
| --- | --- | --- | --- | --- |
| 一级首次 | 26 / 27 / 31 / 32 | 1.619 / 1.636 / 1.704 / 1.720 | 68–101 | 明确未达 |
| 一级暖态 | 36 / 37 / 39 / 40 | 1.722 / 1.738 / 1.771 / 1.791 | 33–69 | 跨60，未确认 |
| 二级首次 | 37 / 38 / 42 / 43 | 1.770 / 1.783 / 1.850 / 1.867 | 67–97 | 明确未达 |
| 二级暖态 | 38 / 39 / 41 / 42 | 1.782 / 1.799 / 1.833 / 1.849 | 34–67 | 跨60，未确认 |

四次首图均为目标蓝色封面，设置截图确认单页RTL。MKV归零PTS没有绝对时钟映射（capture记录UNAVAILABLE），故没有“DOWN→视频首漫画”数字；系统首TX、应用FIRST_DRAW、视频首漫画和物理显示仍为不同端点，不能互相冒充。本轮没有新的同条件官方视频对照，未宣称“不慢于官方”或正式达标。

## 验证、产物与清理

本次12个文件为同一条诊断链：捕获request需跨VM／Activity／viewer／holder接线，另含一个focused测试、维护语义、两份roadmap、已有索引状态和唯一结果报告；没有独立产品能力或验证范围扩张。

- focused仅 `:app:testReleaseUnitTest --tests eu.kanade.tachiyomi.ui.reader.ReaderOpenLatencyProbeTest.targetHeaderCallIsObservableAndFirstEventWins` 与 `:app:spotlessKotlinCheck`：一级及二级各自真实RED→最终GREEN。最终一级key `reader-open-p2-level1-probe-green-finalformat-20261103`，二级 `reader-open-p2-level2-probe-green-20261103`；没有运行整类或全模块。
- 两次 `:app:assembleBenchmark` 均经协调器串行PASS，key为 `reader-open-p2-level1-benchmark-20261103`、`reader-open-p2-level2-benchmark-20261103`。主代理一次独立差异审查覆盖两级，无阻断，不追加测试矩阵。仅诊断辅助及维护说明改变，没有产品候选的收益承诺。
- [一级APK](D:/Shell/Github/mihon/app/artifacts/android/diagnostics/reader-open-p2-20261103/level1/Mihon-Fork-reader-p2-level1-benchmark-universal.apk)与[最终二级诊断APK](D:/Shell/Github/mihon/app/artifacts/android/diagnostics/reader-open-p2-20261103/level2/Mihon-Fork-reader-p2-level2-benchmark-universal.apk)，对应目录含manifest、源码输入、签名、badging和mapping；这是隔离诊断包，不是新优化／正式发布包。
- 原件根 `D:/Shell/Github/mihon/.test-tmp/reader-open-p2-20261103/`，有效label `p2-level1-aligned` 与 `p2-level2-aligned`，每个UI目录含probe／设置／观察，每次目录含capture／原始MKV／Perfetto；`boundary-candidates-level1/2.json`及contact保存人工判读。原始大文件及APK按现有规则保存在Git外，没有建立新采集平台。
- 正式采样后关闭probe并读回enabled=false；正常UI检查正确首图、1→2→1及像素切换，证据 `normal-flip.json`／`normal-flip-contact.png`。仅对合成Volume04通过正常UI标已读再标未读清恢复位置，退出并force-stop隔离包；`cleanup.json`确认pid为空、自有scrcpy／perfetto／logcat均无残留，日志采集器各有明确退出回执。官方和正式fork安装／数据未改动。

本轮未执行B受影响模式／格式／恢复路径、同APK开关探针对照、官方绝对视觉端点、Windows、全量矩阵或正式版本分配。C1新优化保持未完成；下一项未解问题是holder创建后主线程执行的具体主导调用及安全削减条件，不自动续第三轮诊断。B仍按总体roadmap单独授权启动。
