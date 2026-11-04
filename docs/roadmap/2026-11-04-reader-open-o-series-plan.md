# 阅读器首开 O1–O11：逐项实施与收益测量

日期：2026-11-04。用户明确授权执行主线程审查提出的优化方案并逐项测量，Sol/Luna执行，主代理监管与独立实现审查。起点 `2425aca5e53112a4d3214cf398f62c04e6b6ff88`；已采用产品仍为R2。旧R3/R4H候选未采用。此次范围为Android真实生产路径，Windows只运行共享变更直接影响的focused契约，不另做Windows性能宣称。

active-task: O2去重候选构建与OFF/ON测量（O1已完成20样本分析、不采用并归档恢复）

## 新 Sol 会话从这里接手

**这是完整执行计划及唯一交接入口。用户因主控会话成本过高，要求改由新 Sol 会话继续；不要重新调查历史、重建已完成候选或重录有效样本。** 配套结果只维护[同一份证据报告](../evidence/reader-open-o-series-2026-11-04.md)。本计划未勾选的项目仍未完成。新会话已完成O1的20样本分析，决定不采用并归档候选、恢复源码；详见证据报告“O1最终裁决”。下方交接段保留为历史起点，不代表最新状态。

### 工作位置与改动保护

- **源码、测试和本文的权威工作区**：`D:/Codex/worktrees/mihon-reader-o-series-20261104`，分支`codex/reader-open-o-series-20261104`。候选起点为`2425aca5e53112a4d3214cf398f62c04e6b6ff88`；交接时只提交文档，不提交未完成采用判断的产品代码或未运行的O2测试。
- **采集脚本、全部原始测量文件的工作目录**仍是`D:/Shell/Github/mihon`；测量根目录`.test-tmp/reader-open-o-series-20261104/measurement/`。运行这些旧脚本时使用原目录作为cwd，因为部分工具路径相对它解析。不要把两处目录当同一工作树。
- 原目录保留隔离前本任务的7个production/test文件及probe说明的旧副本，**不是当前候选最终源码**。10个复制文件的原始SHA在原目录`.test-tmp/reader-open-o-series-20261104/isolation-copy.json`。整合时逐项核SHA确认未被别人再改，再处理本任务旧副本；不得覆盖不同SHA的新改动。
- 原目录已有用户的`docs/research/reader-pairing/README.md`改动及`2026-11-04-main-thread-open-latency-review.md`，不属于本任务，禁止代为暂存、回滚或提交。另一MiniMax Code会话已由用户确认暂停本轮优化及设备操作，但恢复前仍核当前进程/工作区。
- 本次交接文档只在隔离工作区提交；原目录同名计划/报告是较早副本。新会话请明确从上述隔离工作区读取本文。无推送授权；当前本地主干历史相对缓存`origin/main`已有20个提交，不能顺手一并推送。

### 成本约束与分工

1. 新会话以 **gpt-6.1-sol、medium** 直接主控和实施，Luna只承担明确的批量代码读取、样本采集和数据提取；设备始终只有一个操作者。默认最多一个Luna子代理，不再维持一个只等待和反复读结果的高成本主控。
2. 不重复读取本报告已核实的全量历史；先读当前Git状态、本文、证据报告的O1与交接收尾段，以及即将执行动作命中的项目规则。只在具体证据冲突时查原件。
3. 每项按真实RED→最小实现→同一focused范围GREEN/格式→必要审查→构建→受影响模式对照→采用或拒绝收口。无完整模块/全仓库测试；不因提交、阶段结束或“更放心”扩大验证。
4. Sol亲自实施涉及共享协议/生命周期的高风险部分时，按项目规则临时委派一次有背景的Sol独立审查，审查完即结束；Luna的机械核查不能冒充高风险独立审查。不要为每个低风险局部项固定双重审查。
5. 采集与重型Gradle串行；等待设备时可写下一项测试源码，但未得到真实RED不得先改对应production。离线分析可与不操作设备的工作并行，避免大量TP/FFmpeg进程。失败先核已启动进程，不重复启动同一任务。
6. 每个候选先用下文最小交替样本。小于波动时报告“不确定”，不无限追加样本追逐显著性，不把推算收益当实测；显著增加成本前说明具体未决问题。O1已经追加的两份单页暖态样本不自动推广到后续每项。

### O1已完成与待完成

**源码与APK已经完成，先用现有产物收口，不要重建。** 4个production文件为`ReaderPage.kt`、两个pager holder、`ReaderOpenLatencyProbe.kt`；3个测试文件为`ReaderPageFactProductionWiringTest.kt`、`DualPagerPageHolderLayoutTest.kt`、`ReaderOpenLatencyProbeTest.kt`。主代理已独立审查生产路径，无已知阻塞。

- 普通WebP真实挂载首绘前，旧实现两次开流、新实现一次。EXIF首次方向可更换未绘frame，候选仍会两次完整读取；大于512KiB的EXIF图可能比旧“有界header+完整读取”多读字节。不要声称所有图都只读一次或所有格式都更快，不为此引入缓存/改协议。
- 两个精确挂载方法GREEN；22项既有focused为19通过、3项在完整旧ReaderPage+两个旧holder上同因失败，非22全绿。三项旧失败、替代通过证据和XML路径见报告；不顺带修旧夹具。
- 既有测试原件：原目录`.test-tmp/reader-open-o-series-20261104/o1/`保留早期RED；隔离目录同路径保留`old-mounted-holder-exact.*`、`green-mounted-exact.*`、`existing-focused-*`、`three-old-source.json`等最终证据。格式检查、构建已通过，不因换会话再跑。
- A控制APK：原目录`app/artifacts/android/diagnostics/reader-open-r2-b-20261104/Mihon-Fork-reader-r2-b-benchmark-universal.apk`，SHA256`259c0d98b00deeca6a1254f753f2ffe0be2e6a7d8f541db74b117de6cfdadfc6`。这里A是本轮控制，使用历史R2-B已采用产物。
- B候选APK：隔离目录`app/artifacts/android/diagnostics/reader-open-o1-20261104/Mihon-Fork-reader-open-o1-benchmark-universal.apk`，SHA256`a78fd9065cf943b9b67dac47cd90df3aa7eef4df8152a86051ea0e0af62387a1`。目录含artifact/source-input/source.diff/mapping/二进制身份及签名证据；code134，非debug，R8，原benchmark证书。未正式发布。
- APK中的元信息计时顺序已经变为`OPEN_PAGE→ENCODED_READ_COMPLETE→TARGET_HEADER_BEGIN→TARGET_HEADER_END→DECODE_SUBMIT`；B采集须带`--o1-candidate`。完整端点未移动，不能直接拿header区间差当净收益。

**下一步按此顺序执行：**

1. 读测量根`manifest.json`的`measurementRules.samples`，只计request级`VALID`，按captureLabel/requestOrdinal去重；保留两个输入前`EXCLUDED`及capture级`CAPTURED`总记录，后者不是额外样本。最新设备收尾及A2进度见证据报告末段。A1/B1各6份、B2为4份已完成；不要重录。
2. 只补A2确实未完成的模式；若隔夜或重启后设备条件已有变化，不偷偷续接旧块。记录变化后以最小补充控制解决可比性，不重做整套。实际安装SHA和当前OAT须重读；曾经的`verify/install`不能代签将来状态。
3. 离线提取native DOWN→target FIRST_DRAW、dual UNIT_DRAWN、旧表三个系统端点、页表后main Running及原始视频近黑。A1单页已有analysis文件但其`sampleRole=validation`标错：以新文件名重提为formal，保留旧文件，不重录。clock映射使用同trace的BOOTTIME−MONOTONIC，不混用视频PTS。
4. `trace-health.sql`目前只查negative_timestamp；读取实际stats schema后补有界drop/overrun/overwrite/discard/error/data_loss核验并留原件，不直接因某非零计数重录。CPU统计已正确限定实际launch PID→upid→main tid并裁剪Running区间，勿重新发明算法。
5. 视频`extract_boundaries.py`固定70–145帧及每4帧contact仅是初筛。按每段原始PTS找真正过渡，检查连续帧，再选“最后可辨详情、首近黑、末近黑、首正确漫画”。A1单页首次在88–100附近；其warm的70帧已是3.114s、晚于过渡，应查约1–2.2s而不是沿用帧号。双页第一张正确漫画出现就结束近黑，另记完整单元；不得等完整两页才结束黑屏。
6. 对现有probe直接算共同区间：holder创建→image进入、image进入→decode提交、decode提交→目标首绘/完整单元。先给完整逐样本表，再按首次/暖态、single/OFF/ON分组。A1/B1单页VM→draw分别253.007/209.393与233.091/227.181ms，方向不一致，当前不能宣布稳定改善。
7. 结论可判“采用”“未证实体验收益但保留明确低成本去冗余”“不采用”。O1的生命周期复杂度和EXIF额外读取必须纳入决定；不因已经花费成本强行采用。采用前做probe OFF代表性阅读/翻页复核；已知R2原有ON回翻错单元在A/B都出现，单列，不擅自纳入本次修复。
8. 采用则提交O1这7个代码/测试文件及必要probe说明、结果更新；不采用则先保留已有产物/source.diff和失败/通过证据，再只撤销本项拥有的候选修改。保留独立O2测试草稿，不用reset/checkout覆盖他人内容。后续候选以真正采用的版本为基线。

### O2已准备的设计与测试草稿

目前只有测试准备，**没有O2生产实现、没有RED/GREEN、没有构建或收益**。实际文件与selectors以交接收尾段和`git diff`为准，不把草稿当验证通过。

Sol最终回执：只新增`app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/SharedDualPageAndroidWiringTest.kt`的10个方法及共用fixture；原拟`SharedDualPageSessionContractTest.kt`尚未编写。采样完成条件、同步计数窗口、手势/notify重入夹具仍待首轮验证；production及O1文件未因此改变，无活动测试/构建进程。先仅运行第一个真实RED，确认失败原因再扩大到本项确需保护的其他方法，不一次启动整个模块。

以下selector均加完整前缀`eu.kanade.tachiyomi.ui.reader.viewer.pager.SharedDualPageAndroidWiringTest.`，不得使用类名前置通配符：

```text
O2 equal dimension callbacks stay accepted without another certification request
O2 a fact consumed by ensure still reaches the native adapter projection
O2 changed dimensions project once while their repeated accepted value does no new certification
O2 changed original header still projects when computed original facts retain their value
O2 replacement rejects old facts and projects new owner before deduplication
O2 retry sequence still refreshes equal facts before later duplicates are skipped
O2 changed facts remain pending throughout a native gesture and project at idle
O2 duplicate accepted facts preserve the drawn frame ticket and location
O2 explicit sample failure refresh remains active before equal fact callbacks are skipped
O2 notify reentry cannot mark a later ensured fact as already projected
```

- 已证实adapter接受同值事实后仍调用`rebuildItems()`。不要把共享`acceptFact`的Boolean由“接受成功”改成“发生变化”。
- 事实collector会先`store.ensure`，它可能已经摄入新事实；在随后的accept前后只比较revision会漏掉真正变化。`originalHeaders`也可能改变而retained事实不变，因此单看layout/sample revision不完备。
- 最小首段只去重，不加异步合批：adapter按当前owner记录**已经真正投影消费的输入戳**；覆盖layout/fact工作与独立header变化。仅在未消费输入存在时重建。手势pending提前返回不得标为消费；notify/holder同步回调可能重入，记录真正消费的旧快照而非回调后的更高revision。只保留当前窗口owner，及时清理退休owner。
- `failOriginalAnalysis`可能只改变sampleWorkRevision、不改layout；输入戳边界须解释并用真实路径覆盖。导航、设置、显式重试、刷新保持既有主动路径，不把所有重建一律门控。
- 使用真实adapter公开`updatePageDimensions`、store.ensure、已存在的certificationMetrics/DataSetObserver等证据；覆盖同值去重、真变化、ensure先摄入、header独立变化、owner替换/重试、手势pending及已绘票据。指标是实际认证工作次数，不能冒称精确重建次数。

### O3/O4已完成的只读设计

- **O3**：优先在`AndroidChapterPairingPersistenceWiringTest`建立成功的受控repository.load回调，single/webtoon实际首绘后释放成功恢复，真实eventFlow→activity.handleReaderEvent应不再收到无效Reload；现有失败回调用例不能充当该RED。门控仅限恢复成功结尾，按完成时真实viewer判断，不用保存readingMode或发起时模式。保留数据恢复、可用性、INVALID_RECORD反馈、旧请求拒收及后续切双页。dual即使物理分组相同也可能需要RESTORING→READY后的认证/原图准备，不能仅凭layoutRevision或“same items”删Reload；先保留，再有真实轻刷新接线证据才优化。
- **O4**：用真实ReaderViewModel.init接线断言初始章节查询2→1、SHOW_ALL且非downloadedOnly时逐章下载判断归零。复用第一次得到的原始Domain列表，仍在原子openChapter返回后按opened.manga和更新的当前章排序/过滤；不预先缓存按旧manga处理的ReaderChapter。catalog refresh仍真实再查，downloadedOnly的独立DownloadCache过滤仍保留。下载惰性先在Android调用点只为SHOW_DOWNLOADED/SHOW_NOT_DOWNLOADED求值，不改共享Boolean API或复制整个过滤算法。

### 工具与续跑入口

- Python：`C:/Users/feeli/AppData/Local/Programs/Python/Python314/python.exe`；先设置`PYTHONUTF8=1`、`PYTHONIOENCODING=utf-8`，测试另设`PYTHONDONTWRITEBYTECODE=1`。后台子进程CREATE_NO_WINDOW，禁止隐藏共享终端。
- ADB：`D:/Android/Sdk/platform-tools/adb.exe`，最近USB serial `5JB0224111000992`；每轮重新核身份。仅`app.mihon.desktop.fork.benchmark`，官方`app.mihon`及正式fork不改。无密码锁屏按平板指南自行正常唤醒，等待/结束息屏。
- 内容：既有史莱姆第04卷、187页CBZ，SHA256`b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677`；single目标source4世界地图，dual为source3/4左地图右卷4。RTL、裁边OFF、横图放大OFF、黑背景、原动画。
- 测量根中的`prepare_mode.py`负责正常UI设置；`tablet.py --recorder off`做不计入性能的预热；`run_sample.py`负责唤醒、焦点核对及正式采集；`read_device_conditions.py`读实际安装/OAT；`validate_probe.py`核事件/安装块关联；`extract_native.py`和`extract_thread_running.py`离线提取；`record_sources.py`收尾记录脚本SHA。先读现有driver/参数定义，再用唯一新label，不对没有help分支的脚本随手传`--help`。
- 正式driver参数形状：`run_sample.py --arm A --mode dual-off --label <唯一label> --expected-sha256 <上述A-SHA> --block A2 --device-conditions <实际本块conditions.json> --opens 1`。dual-on另开一份；B加`--o1-candidate`。不要对缺失模式配置、息屏或错误图片盲跑。
- CPU提取形状：`extract_thread_running.py <trace> <probe> <唯一分析label> <single|dual-off|dual-on> --launch-pid <capture目录/launch-pid.txt> --sample-role formal --out <新文件>`。TP在原目录`.test-tmp/upstream-original-trace-20261102/tools/trace_processor_shell.exe`。
- 重型Gradle全部经`scripts/gradle-coordinator.py`串行；精确完整包名+方法selector，**禁用类名前置星号**，此前曾触发大量无关发现JVM。恢复时先查协调器状态和本任务进程，已完成的O1测试不重跑；O2先实际RED。

### 可直接用于新会话的指令

> 使用gpt-6.1-sol medium继续。先在D:/Codex/worktrees/mihon-reader-o-series-20261104读取docs/roadmap/2026-11-04-reader-open-o-series-plan.md及其证据报告，核当前工作区和设备收尾，再从O1剩余分析/采用判断继续，随后逐项执行O2–O11并测边际收益。Luna只承担明确的批量采集/读取；不重做已完成调查、构建和有效样本，不跑全量测试。保护原目录用户改动，按项目规则验证后自动提交，不推送。

执行目录变更：22:22后确认另一个MiniMax Code会话在原目录并发运行O1 Gradle。为避免测试输出与源码竞争，本线程转入 `D:/Codex/worktrees/mihon-reader-o-series-20261104`，分支 `codex/reader-open-o-series-20261104`；起点不变。本任务10个文件逐一比对SHA后复制，未清理或回滚原目录。旧RED原件与Luna测量目录仍在 `D:/Shell/Github/mihon/.test-tmp/reader-open-o-series-20261104/`，新测试原件在隔离目录同名路径。并发结束后的主干整合须重新核对当前改动，不能覆盖另一会话结果。

## 范围、所有权与交付

- Sol medium：唯一production/tests写入者与Gradle协调者，逐项真实RED→最小实现→相同focused GREEN/格式→主代理审查→隔离benchmark构建。
- Luna medium：唯一ADB操作者，复用脚本采集、有限代码读取及离线汇总；正式性能采集不与Gradle并行。原件置于 `.test-tmp/reader-open-o-series-20261104/`。
- 主代理：维护本计划和唯一结果报告，验收真实行为及计时口径，独立审查共享/生命周期变化；验证后仅提交本任务内容。不推送、不正式发布、不更新未获用户验收的公共功能说明。
- 保护启动时已有的未提交 `docs/research/reader-pairing/2026-11-04-main-thread-open-latency-review.md` 及该目录README改动，不代为改写、暂存或提交。
- 最多两个子代理。各内聚候选一轮实现审查及必要修复复审；只做真实受影响focused测试。无完整模块/全仓库验证。预计数小时，主要成本为构建、真机采集与分析，不以达到预计时间冒充完成。
- 用户已授权无密码锁屏正常唤醒。只改隔离 `app.mihon.desktop.fork.benchmark`；官方和正式fork APK/数据不改。需要前台时亮屏，等待、构建和结束时息屏。详情见平板指南。

## 每项测量与采用办法

1. **一次只测一项变化。** A为当前已采用基线，B仅增加当前候选；采用后成为下一项基线，未采用恢复本项拥有的修改并保留补丁/证据。报告边际收益，不将不同条件差值累加为原版差距。O8内部独立改动也区分，不以合包收益冒称分别收益。
2. **控制安装、shader和ART。** A/B同安装策略、同隔离身份和签名/R8/非debug，读回实际OAT，不能凭compile Success。默认每次安装后同样执行一次不计入性能的Reader准备/预热，然后强停进入新进程；明确称“安装后已预热的新进程首次”，不称升级后首次。升级首开与同进程再次单列；不重装官方。trace核shader成本是否相近，不能统一扣20ms。强制编译失败不无限重试。
3. **固定内容。** 既有史莱姆第04卷同CBZ、同USB设备。单页与真正双页统一source4；双页核source3/4左右内容。RTL、横图自动放大OFF、黑背景、原动画；匹配与横图放大须核实际设置截图，不能凭历史本地state文件推断。提示控件若未保存则记未复核，并确认实际首图无可见教学浮层。O4另用有界的数百章虚构夹具验证列表规模，O5另测AUTO横屏；这些不能与旧单章固定模式混池。
4. **最小交替样本。** 对受影响模式先按A1首次/暖、B1首次/暖、B2首次、A2首次，共6份；每个包各两份首次一份暖。样本用于逐项诊断，不声称可靠P95或整体达标。结果小于自然波动时先检查关键路径/仪器，确有判断需求才扩到每臂4份首次，最多按本项具体不确定性扩展，不机械把所有模式跑满。
5. **同次端点。** 保存probe、Perfetto和原视频PTS。主收益为native输入→目标FIRST_DRAW/完整UNIT及视频近黑；另报VM→draw、页表后主线程Running和操作计数。FIRST_DRAW不是物理屏幕呈现；视频未校准绝对时钟时不报伪精确输入→屏幕值。双页首部分、目标图和完整单元分别记录。
6. **不靠移动探针得收益。** OPEN_PAGE保持实际开流前，DECODE_SUBMIT保持请求提交前。O1工作合并后header事件可以移到OPEN_PAGE之后，必须按真实操作更新断言并说明阶段工作迁移；完整端点不变。探针关闭时做代表性的正常阅读/可见体验核验，不能把开关差直接扣成无探针值。
7. **保持正确性。** 每项覆盖其真实风险，成功候选至少检查受影响模式正确页/方向、翻页返回、恢复位置和无再黑。已有ON回翻错单元与新回归分开，不随意放宽断言，不顺带扩大修复。主代理看原图/连续边界，Luna核原PTS，不靠单独数值或脚本PASS代签像素正确。
8. **采用条件。** 真实行为正确且可确认消除冗余工作；性能未定时如实报告“不确定”，不写0ms或收益保证。显著复杂化的候选无净收益则不保留；明确局部冗余消除如保留，须说明未证明体验改善。结束关闭probe、停本任务进程、平板Asleep，记录实际留装包。

O1定向追加：A1/B1单页首次与暖态差值方向不同，故B2/A2仅single各增加一次同进程再次打开，分别opens=2；双页OFF/ON仍opens=1。共20份有效样本，不增加安装块。其余项目不自动继承这一追加，按实际不确定性决定。

## 执行清单

### [x] O1：一次图片读取与最少Main往返

2026-11-05收口：20份有效样本（A2为隔夜补充控制）、完整native/CPU/probe/连续帧分析后不采用；候选8文件归档并逐SHA恢复，保留APK和所有测试/测量原件。普通WebP读取减少成立，跨模式稳定体验净收益未证实，EXIF字节及生命周期代价不保留。见唯一证据报告最终裁决。

只改单/双holder图像准备和必要的ReaderPage元信息接口。读取一次完整编码Buffer，从同一数据取得真实原图尺寸/完整EXIF方向、动画、背景等；避免header独立IO→Main→再读IO及双页额外尺寸往返。首次显示之前先建立正确方向/内容绑定，不能先显示错误朝向再补事实。保留失败重试、取消、stream/encoded身份变化和已绘伙伴。先真实测试旧实现的额外开流/往返及生产接线，再实现；与R3只复用流的已否决候选区分。测single/OFF/ON，报告净端点与动作迁移。

### [ ] O2：避免无变化事实引发冗余双页重建

区分事实“接收成功”和“改变呈现输入”，不直接把公共Boolean接受语义改成变化语义。同值事实不使plan失效，现有items相等返回/拖动延迟仍保留；测试实际adapter调用及同值/真变化/迟到/手势交错。若同一轮Main合并重建，保证主动导航、源页锚点、已绘锁定与完整票据一致。测OFF/ON，操作计数与首绘分别报告，不将所有rebuild入口算作完整算法重算。

### [ ] O3：恢复配对记录不无故重绑普通阅读器

成功且仍有效的恢复结果仅在双页布局实际需要更新时触发viewer重新绑定；单页/条漫保留恢复结果、错误提示/重试可用性，后续切双页正确生效。覆盖成功/失败/旧窗口拒收/切模式接线，不删除恢复逻辑。测single及确有保存记录的dual场景，空记录跳过效果不能冒称真实恢复收益。

### [ ] O4：列表一次查询、下载过滤惰性判断

用同一次列表定位selected并建立chapter缓存；保持缺章错误、排序、当前章保留及过滤语义。仅下载过滤实际需要时求isChapterDownloaded；测试真实VM/仓库/下载接口调用次数与结果，不复制过滤算法。单章及数百章夹具分别测，统计查询/下载状态调用数；不把目录命名hash描述为图片全文件hash。

### [ ] O5：AUTO首次viewer只在有效视口下决定

先证明横屏首布局前实际发生single→dual重复构造；无复现则记录不适用，不强行实现。若成立，等待首次有效布局仅延迟mode选择，不能新增整帧无必要等待；覆盖竖/横、尺寸变动、恢复锚点及窗口重新配置。单独AUTO横屏A/B，不从固定模式样本推收益。

### [ ] O6：首开邻单元准备让当前单元优先

先核实际屏外holder/完整读取数量；只延后不影响当前显示的邻单元准备，保持正常取消与滑动预取。当前单元失败/部分成功时不得永久阻塞邻页；不能把完整UNIT成功作为永不释放的唯一门。双页OFF/ON同时测首开与第一次翻页就绪时间，不能把等待挪到翻页来宣称成功。

### [ ] O7：匹配采样与首图竞争取证后处理

先用已有trace/窄计数确认普通读取阶段是否存在采样竞争，以及OFF是否真的发生原图采样。遵守配对研究索引，不重启历史评分算法。只在证实条件下调整有界前台繁忙/采样准入，保留当前图失败与取消后的可恢复性、默认/认证分组和不会永久饿死采样。若未触发则给出未触发证据和无可测收益结论。

### [ ] O8：事实监听与索引成本

先处理requestFact等逐页线性查找，保留身份匹配/旧请求拒收并单独测量。再依据剩余队列证据评估章节级变化信号替代逐页collector；不能丢连续重试、订阅前变化、窗口替换或P3初次快照保护。不把“每章一个信号”当自动更快，需真实生产事件顺序与计数测试。两项不能合为一个收益数字。

### [ ] O9：缩小浮层状态订阅

确定当前首开发生的实际重组路径，仅订阅组件消费的字段，保持菜单、提示、错误及进度及时更新。不凭父composition重执行就假设全部子树重组。验证真实Compose接线与关键状态变化，采同位置帧/主线程与完整端点。

### [ ] O10：Activity剩余成本先归因

在前述冗余消除后，从实际新增Activity/首帧工作中选有依据的单一候选；需要方法采样时只增加最小、可关闭的诊断并单列扰动。没有方法级证据不随意调协程启动或删初始化。每个采用候选独立红绿与对照，不把取证视为已优化。

### [ ] O11：baseline profile条件核实与可选对照

核对release/benchmark实际携带profile及设备编译能力，区分已预热进程首开与升级后首开。只有能建立同APK/同实际filter的可比干预才实施/测量；不将未知filter写成一致，不改官方安装。若现有构建/设备不支持有效对照，记录具体限制；profile不能替代消除前述冗余。

## 完成状态

每项在实现/必要验证/测量或明确不适用证据/本地提交完成后才勾选。仅阶段完成时不宣称整个清单结束。最终报告给逐项表：条件、采用状态、总耗时与近黑变化、主线程/调用计数变化、正确性、样本数与不确定性；不相加重叠收益。
