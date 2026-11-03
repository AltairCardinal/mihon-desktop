# 下载章节首开优化方案与执行 roadmap

日期：2026-11-03。状态：**方案已交接，产品实现尚未开始**。

active-task: A0

这是给新会话的执行入口，包含实现方案、必要测试、真机复测方法和后续阶段。首次执行只完成 **A0→A3，先交付优化结果**；B、C留待结果报告后进入，不把完整测试提前放到首份结果之前。本文件不建立新的manifest或自动化状态系统。

## 1. 用户要求与本轮范围

用户明确要求：“给出完整优化方案和执行roadmap，我让新会话进行。精简掉一切不必要的测试，我们先出优化结果再去完整测试。”

据此调整前一份[分析报告](../research/reader-pairing/2026-11-03-downloaded-open-latency-analysis.md)第6节的执行顺序：先最小行为红绿、优化构建、原场景三次测量；较完整的正确性回归、增加样本和探针开关对照后置。性能目标不放宽，但**“首轮优化结果”不宣称“全部模式验收通过”**。

- 首轮产品范围：Android下载/本地页表发布前的全章header读取依赖。涉及的同一adapter也服务其他本地来源，不能虚称只影响测试CBZ；这些来源的扩展回归后置。
- 首轮不修改Desktop、共享配对规则、调度器、图像解码器、EXIF完整性规则、下载格式或数据库。
- 使用已有诊断安装和真机；不重测模拟器，不重新给官方Mihon埋探针，不重做前几轮原因调查。
- 沿用用户指定分工：`gpt-6.1-sol` / `medium`负责实现、focused执行、构建及唯一真机输入；`gpt-6-luna` / `medium`负责限定范围读取和离线数据核算。主代理负责范围、一次差异审查及汇总。最多两个子代理，共享工作区不得互相回滚；Gradle与真机始终单一操作者。
- 预计首份有效结果60–100分钟，主要成本为构建和真机采集；这是估算，不是超时即重启构建的理由。若设备不可用，先完成可独立完成的代码与构建，再报告实际阻塞，不用模拟器速度替代真机。

## 2. 已有结论与目标，禁止重新混用端点

分析基线 `2eb64f5470efc219bfe10be3c5786db140011e92`，其中探针实现为 `b3efe1469e4a0dbda798c121891ba1fe6c36f48e`；本次准备文档时main无产品新增修改。新会话先核对HEAD及相关diff，不强制回退这个SHA。

| 指标，ms | 上游原版探针，首①/暖/首② | 主干探针，首①/暖/首② |
| --- | --- | --- |
| 初始化→首次漫画绘制 | 243.313 / 196.883 / 222.295 | 646.843 / 632.686 / 651.699 |
| 第一段：初始化→页表就绪 | 110.901 / 101.639 / 116.635 | 511.640 / 524.822 / 532.627 |
| 主干整章header遍历，含于第一段 | 无同样的全章循环 | 397.032 / 412.359 / 403.196 |
| 原表近黑范围 | 19–54 / 0–36 / 0–36 | 398–467 / 397–465 / 382–448 |

**核心假设：** 主干新增平均404.2ms全章header屏障，是平均422.9ms额外首绘等待的主要来源。移除该依赖应明显改善首图，但404.2ms不是已证明的可回收量。算术扣除后约239.5ms只是成本预算，不可作为改动后的测量值。

真实优化目标沿用[性能基准](../evidence/reader-startup-performance-baseline-2026-11-02.md)：登记场景无内容间隔≤60ms；输入→首漫画可见不慢于同条件官方参考；快速响应≤100ms须核实实际内容，不能只看窗口首TX。官方旧表近黑上界33–54ms；另轮源PTS协议官方首漫画约284/289ms。后者与FIRST_DRAW不是同一终点，不能直接相减。

首轮先比较改动前后相同原表协议及内部四阶段。原版数据是性能目标；包、库、ART状态等差异仍保留，不能据跨包差值推算探针净开销。

## 3. 完整实现方案：先做P0，其余按结果选择

### 3.1 P0：页表与整章图片元数据解耦

现状：`ReaderViewModel.loadChapter`等待`ChapterLoader`；`AndroidReaderChapterContentPort`取页表后，对所有Ready页执行`publishHeaderFact()`；全部完成才发布Loaded。`ChapterLoader`已有Loaded分支还会再次全页遍历。是否执行仅取决于是否PREFETCH，与单/双页无关。

改动后的顺序：**列出合法页面→绑定章节及编码内容身份→发布页表→当前页正常元数据/解码/绘制；配对资料继续走既有按需链。**

| 文件/链路 | 首轮精确动作 | 必须保留 |
| --- | --- | --- |
| [AndroidReaderMaterializePorts.kt](../../app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/AndroidReaderMaterializePorts.kt) | 去掉章级全页`publishHeaderFact()`；移除仅服务该循环的`publishHeaderFacts`参数和标记调用 | `getPages()`、`page.chapter`、Ready页的`encodedPageRef`、descriptors映射、异常/取消传播、页序 |
| [ChapterLoader.kt](../../app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/ChapterLoader.kt) | 删除已有Loaded分支的全章header循环；移除上述参数传递；新加载和复用两条路径都不得重新引入该屏障 | `awaitExistingWindowLoad`、generation、loader安装/退役、Loaded接受检查、requestedPage与窗口语义 |
| 单页/双页holder | 首轮保持原实现；核对其已有本页`publishHeaderFact()`和后续解码仍被正常调用 | 当前页真实方向、内容/附件/请求身份检查、before-draw与像素锁、真实进度 |
| 共享owner与事实消费者 | 首轮保持原实现，UNKNOWN从已有原子快照建立，事实迟到仍由原接口接收 | UNKNOWN不伪装PORTRAIT，默认分组不回灌Certified，手动约束、失败、预算与真实物理退出 |

仅上表前两个生产文件是P0预期修改面。确有直接接线障碍时可做最小必要调整并说明原因；不要顺势重构holder、会话或测试架构。共同代码未变，不运行Desktop模块回归。

这里移除的是**全章前置工作**。当前页的header仍在正常链读取；如果首屏后原有后台消费者又无必要地读取整章，应先记录真实触发和数量再处理，不能仅凭删掉循环就声称总读取已最优。

### 3.2 探针跟随实际行为，不能制造零耗时事件

- 保留原五个连续端点：`OPEN_READER_INTENT→PAGE_LIST_READY→OPEN_PAGE→DECODE_SUBMIT→FIRST_DRAW`。第一个实际是ViewModel初始化，不能改标成触摸或Intent发出。
- 删除章级扫描后，`HEADER_FACTS_BEGIN/END`可不出现；在结果中标记“章级遍历已移除/不适用”。不得为了满足旧解析器在空位置伪造事件，也不能把本页header改塞进相同名字冒充旧区间。
- 首轮不重写记录器、不增加逐页log、不扩展计时页。固定槽位可继续保留；有效样本按必要端点、目标身份和次序检查，不要求恰好29条事件。现有probe只测page0；中间页用后续行为检查，不能冒称已有内部时间。
- 当前页本身的header在`OPEN_PAGE`之前，移除全章工作后，它可能进入“页表就绪→OPEN_PAGE”段。这是工作迁移，必须公开，不能只展示第一段缩短而忽略总时长。
- 更新[探针维护说明](../architecture/reader-open-latency-probe.md)，只改受影响的事件语义与覆盖范围。历史证据文件保留原样。

### 3.3 P1：只有P0结果仍慢时才选择一个剩余瓶颈

先使用同次四阶段和原有细分事件确定剩余最大段，再选一种最小改动；不把下列候选全部并行实施。

| 测量触发条件 | 下一步候选 | 限制 |
| --- | --- | --- |
| 章级遍历已消失，但本页header/普通读取重复成为主成本 | 检查能否在同一正常读取与内容身份下复用编码数据/可信元数据 | 需要真实版本/取消/生命周期接线；不从普通显示位图反推原图证据；不新增跨会话缓存 |
| 页表准备仍慢 | 用已有download/path/archive事件定位，针对该具体路径优化重复查找或必要读取 | 不先更换整个archive backend；避免把不同格式/SAF差异统一归因JNI |
| 页表到本页链启动仍慢 | 检查实际owner建立、UI调度或挂载依赖 | 不删身份/权限验证，不人为提前曝光空背景；不得只凭阶段名猜测可删工作 |
| 解码或绘制段实际成为新主成本 | 确认目标图、采样、线程等待与帧机会 | 不降画质、不改变缩放/转场以换成绩；先取得细分证据 |

当前完整性规则对WebP要求RIFF全长、PNG要求IEND；“读取前几KB就确认方向”不在P0范围。格式级最小读取需单独设计并验证完整EXIF。P0足够接近目标时停止扩大优化。

### 3.4 保持的产品契约

目标图可读、已绘像素稳定、源页不被自动遗漏优先于自动配对完成。未知前缀/迟到伙伴不阻断当前页；默认配对不成为认证事实。当前页仍可为恢复位置，不以首页特例改变业务。

流替换、内容代次、旧回调拒收、取消与物理资源退出、逐图失败重试、手动布局revision/CAS保持原样；不把配对恢复失败改成基本阅读门禁。条漫保留原显示语义，较完整回归时覆盖切换模式后的元数据补齐。

不增加整章后台预读、持久header索引、新模式开关或无界并发；不按URI/mtime/长度认定旧元数据永远可信。当前storage reset只处理online→downloaded，不能冒称支持任意SAF原地替换。已暂缓A2/A3/A4、Strong、全信息索引和弱位图复用不因本次I/O证据自动重启，参见[研究入口](../research/reader-pairing/README.md)。

## 4. 首轮 roadmap：A0至A3先交付结果

### [ ] A0：核对现成输入并固定边界（约5–10分钟）

读取本文件、上份分析、根AGENTS及按动作所需指南。检查工作区/HEAD/已有Gradle任务；保护用户改动。只确认下节本机输入是否存在及隔离安装身份，不扫描磁盘、不重做历史实验。

固定本次source diff、包名、设置、章节和输出新label。旧证据足够作首次优化筛选基线，**不先重装旧包或重测官方**。Luna可离线准备旧三列和解析适配；Sol独占写代码、Gradle与设备。若现有临时工具缺失，先查指定原目录及仓库时钟工具，报告缺件，不建设新基准平台。

完成标准：明确基线和两处生产修改点，知道当前设备连接/解锁状况或真实阻塞；不要求先把所有历史测试重新跑绿。

### [ ] A1：两个红测→最小P0→三个focused检查（约25–45分钟）

复用[ReaderOpenLatencyWiringTest](../../app/src/test/java/eu/kanade/tachiyomi/ui/reader/loader/ReaderOpenLatencyWiringTest.kt)已有ReaderChapter、PageLoader和真实ChapterLoader/materialize接线。替换旧“必须先读187页header”的时序测试，新增/组织成以下两个行为方法：

1. **新页表发布不触发全章header。** 187个Ready页的stream带计数；实际调用ChapterLoader，确认页表完成、页序/章节/编码引用与generation仍有效，但章级header没有打开这些流。测试使用现成小图片字节，不复制187份真实大图。原实现应因实际读取次数非零而红；不以源码字符串扫描代替行为，也不靠永久挂起线程制造红测。
2. **已有Loaded路径不重新扫描，目标仍可按需取得事实。** 使用新建且尚无header的Loaded页对象进入实际复用分支，确认章级不读图；随后调用既有本页事实生产者，仅目标取得真实几何/方向及稳定内容身份，未要求远页齐备。旧实现应因全章读取而红。

红测确认原因后，实施3.1的两文件改动。新测试覆盖的是章级读取，不禁止归档列表枚举自身为识别未知扩展名进行的必要工作。

用简短维护说明同步[共享阅读器架构](../architecture/reader-shared-core.md)中Android页表与事实取得时序，以及前述探针文档；不改历史实测结论，不为本次优化重写整个架构文档。

首轮GREEN只执行以上两个方法，加现有 `ReaderImageOrientationWiringTest.first content probe requires real bitmap draw and rejects old request`；合计三个行为检查。第三项是既有真实bitmap/canvas端点保护，不单独证明整个holder链；A3真机正常UI到目标漫画负责补真实用户链证据。

执行一次同范围GREEN和受影响格式检查；必要修复只重跑失败/受影响范围。记录器未变，不重复跑其三个单测。已有大型wiring类、全EXIF组合、全模式矩阵和全模块都不在此步骤。

某些旧测试依赖“无holder时全章几何已齐备”，本轮先登记这些预期变化，放到B迁移到真实按需生产者。不能批量删除、加ignore或谎称它们已经通过。`failed metadata retry preserves the confirmed direction of the same encoded stream` 已有未加探针基线失败，不为这次P0反复建立新基线；若实际修改触及其行为，则按新证据处理，不能用“历史红”掩盖新回归。

### [ ] A2：一次优化诊断构建与隔离安装（约10–15分钟）

保留benchmark继承release的R8、资源压缩、非debuggable和原诊断证书，构建`:app:assembleBenchmark`；不改正式versionCode、不申请正式候选、不覆盖官方或fork正式安装。同一worktree经Gradle协调器串行执行，超时查状态，不重复启动。

保存APK、SHA、badging、签名和source清单到新的 `app/artifacts/android/diagnostics/`子目录；不覆盖旧APK和旧manifest。只对已确认的`app.mihon.desktop.fork.benchmark`进行保留数据更新安装，先核包名/证书。保留现有专用SAF目录及合成条目，**不要重新运行prep把路径重置成私有file**。

沿用旧编译请求策略，仅对隔离包请求`speed -f`并保存输出；能读回实际filter则记录，不能则仍为UNKNOWN。Success不证明与官方编译状态相同。

### [ ] A3：三次真机采样，立即报告优化结果（约20–30分钟）

只测原场景：史莱姆第04卷、第1/187页、单页RTL、横图自动放大OFF、模式提示OFF、相同SAF与黑背景。每次在真实UI确认关键设置/目标；不能以prep默认值推断。脚本只操作已解锁、焦点属于隔离包的设备。

沿用原表顺序：**新进程首次①→同进程再次→另一个新进程首次②**，共三次有效打开，不先扩成每模式3冷+3暖。使用原MKV+Perfetto联合采集，同时保留探针；不在这一轮增加第二套视频采集。记录退出阅读器后的完整probe JSON，再强停进程。

同一次样本报告：

- 原表四项：输入DOWN送达→StartActivity、→performCreate、→首Reader TX结束，以及原视频近黑四边界/区间。
- 四个连续内部阶段与总计；额外报告输入→FIRST_DRAW时，使用同trace时钟映射。不要把较晚应用hook当原表输入span。
- APK/源/内容身份、PID/request、设置、有效/失败样本及排除原因。目标必须是正确漫画像素，不能只认页码或loading消失。
- 章级header事件缺席的真实原因，保留未适用标记；计数测试证明调用结构，不能凭缺marker就宣称真机读取字节为0。

使用同样的视频人工口径：允许极淡残字/页码，不要求纯黑；保留最后可辨详情、首近黑、末近黑、首正确漫画及原始PTS。MKV归零PTS不能算输入→视频首漫画；这项绝对视觉端点留到B的既有源PTS协议，不新造时钟映射。

三次正式采样完成后，可在一次正常阅读中顺手检查首次翻页是否可用；不提前翻页污染三次page0条件，不把一次顺手检查称性能矩阵。若首图错误、空白或错方向，先作为真实失败报告并修最小直接回归，不能只交更快的数字。

**A3完成后立刻交付，不等B/C：** 优化APK链接、源提交、逐次前后差值、近黑区间、是否接近原版、仍欠多少、未执行的验证。若性能未改善，也交付真实结果和下一处已定位区间，不追加无目标实验。包含测试、产品、必要维护说明、精简结果和roadmap进度的改动统一提交；不推送或正式发布，除非另有明确指令。

## 5. 后续 roadmap：效果出来之后再验证或继续优化

### [ ] B：对选定方案做完整的受影响路径验证

这是结果出来后的阶段，**不是A3的前置条件**。用户转入此阶段时执行；“完整”先指本次改变的路径，不自动等于仓库全量。

| 风险 | 最小对应验证，优先复用现有方法 |
| --- | --- |
| 旧测试把整章header当业务前提 | 迁移 `ReaderPageFactProductionWiringTest` 的本地目录/下载/归档事实及取消用例到真实按需入口；保留能力、失败和取消断言。只选择受影响方法 |
| 晚方向/版本影响配对与呈现 | 选择 `SharedDualPageAndroidWiringTest`、`DualPagerPageHolderLayoutTest` 中相关方法，检查UNKNOWN owner、方向迟到、当前页像素锁和旧身份拒收，不跑整类大矩阵 |
| 实际阅读模式与恢复 | 单页、双页、条漫代表路径；恢复第126页、伙伴延迟、晚宽图、单↔双/AUTO切换、继续翻页。按风险组合用例，不做模式×格式×设备全排列 |
| 真实元数据和失败生命周期 | 有界读取、EXIF、流替换、旧回调、取消关闭与逐图重试；ReaderPage本身未改时复用已有有效证据，仅补时序改变的集成点 |
| 性能是否达标及探针影响 | 对声明达标的代表场景补足3次独立首次+3次同进程再次；同APK开/关探针外部对照，复用源PTS/设备时钟工具，并按相同外部方法对照未修改官方APK。保留编译/负载差异，不扣固定探针开销 |

只在发现共享协议真实变化、跨模块回归或focused无法覆盖的具体风险时，说明最小扩大范围和成本，再决定模块/全量测试。正式发布矩阵、两端正式包和版本分配仍属于后续发布里程碑，不提前执行。

### [ ] C1：Android仍未达目标时，执行一个P1候选

输入必须是A3/B的有效剩余耗时；按3.3选择一项，声明具体改动、一个对应红测和可比较端点，再做小范围改动与定向复测。P0成功不授权无限迭代其他算法；如果需要改EXIF完整性、持久缓存或共享协议，先给新的具体方案及成本，不在本roadmap下悄悄扩大。

### [ ] C2：Windows同章节首开定位与优化

Windows没有同一全章header屏障，不移植Android删循环方案。进入该阶段先读取Desktop构建/验收指南，确认实际预览版路径、配置与进程身份；固定同CBZ/起始页/模式，分别取启动后首次与暖态，核对图片首绘事件和真实画面。

只用新的Windows实测确定其首图关键路径，复用现有加载/绘制观测；若发现具体额外依赖，单独给最小实现和一个真实行为回归，再测首图与首次翻页。没有新Windows证据时，不能把Android改善写成双端优化完成，也不重启历史暂缓的配对研究。

Windows仍以官方短无内容间隔和正常可读体验为目标；跨硬件绝对时间只作体验参照，不能将两台设备的内部时间差认作实现开销。双端全部模式验收与发布状态分别报告。

## 6. 本机交接清单与命令入口

### 已核对存在的输入（2026-11-03）

| 用途 | 本机位置/身份 |
| --- | --- |
| 仓库 | `D:/Shell/Github/mihon`；当前本地main，未授权把其他待推送提交一起发布 |
| 真机 | 最近地址`192.168.50.51:5555`，PCE-W30/API31；连接及解锁每轮重新核实，不假定永久在线 |
| 隔离包 | `app.mihon.desktop.fork.benchmark`；vc134 / `0.19.4-aex.116-benchmark`为旧实测身份，新包以实际manifest为准 |
| 必须保留的其他安装 | 官方`app.mihon`、fork正式`app.mihon.desktop.fork`、上游探针`app.mihon.benchmark` |
| 旧主干探针基线APK | `app/artifacts/android/diagnostics/fork-reader-tablet-probe-20261103/Mihon-Fork-reader-probe-benchmark-universal.apk`；SHA256 `ae1e407aa4ae0a912606094cbb8e93991ad3a8f20f65c68c12ad7e55fa4f4fa9` |
| 旧APK清单 | 同目录`artifact.json`、`signature.txt`、`apk-badging.txt`；诊断证书SHA256 `fb141e4e749dc634a3d5d79e179a1057ab81fa1fd7cb5077b6dfeccfe11f3079` |
| 源CBZ | `.test-tmp/slime-reader-remeasure-20261102/slime-vol04.cbz`；41,380,343B，SHA256 `b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677` |
| 真机规范CBZ | `/sdcard/MihonLatencyForkProbe20261103/downloads/610004/Real CBZ 04/Volume 04_c77087.cbz`；既有SAF授权需读回核验 |
| 虚构书架条目 | `Real CBZ 04`→`Volume 04`；mangaId=1、chapterId=1、page=0，不是上游probe的2/2 |
| 原采集脚本 | `.test-tmp/fork-reader-tablet-probe-20261103/tablet_old_run.py` 与同目录`capture-old.py` |
| 最终旧RTL原件 | 同根`fork-saf-rtl-first-warm-1`、`fork-saf-rtl-first-warm-2`、`fork-saf-rtl-second-process-1`；probe位于对应`*-ui`目录 |
| 源PTS后续工具 | `scripts/reader_video_clock.py`、`scripts/reader-clock/ClockSample.java`及`.test-tmp/slime-clock-gpu-20261102`；首轮不强制切换协议 |
| 旧基线已知红 | `.test-tmp/fork-reader-tablet-probe-20261103/metadata-head-baseline.xml`；不再跑完整类去重复发现它 |

旧脚本有**旧APK SHA白名单、版本校验、固定serial、ROOT输出目录**。适配前先读脚本；复制到新的本轮临时目录，按实际候选身份改白名单/路径，保留包名、焦点、解锁和导出隔离检查，不能为跑通删除守卫。保存适配脚本身份与来源，不覆盖旧原件。脚本支持`--recorder sparse --export stop --external --opens 2`，随后新label运行`--opens 1`，对应三样本；每次调用会重启隔离包。不要在这里使用legacy/draw模式。

配置文件为`/sdcard/Android/data/app.mihon.desktop.fork.benchmark/files/reader-open-real/config.json`，包含`enabled=true,mangaId=1,chapterId=1,label=本轮唯一值`；由MainActivity启动时读入。最终返回阅读器触发onStop，等JSON完整导出；强停之前没有返回会丢样本。收尾只停本轮采集进程及隔离包，关闭probe，保留用户安装和SAF内容。

### 精简命令模板

以下供执行者使用，本次文档任务没有运行。启动前按根规则读取Windows、Android与验证说明。固定Python为`C:/Users/feeli/AppData/Local/Programs/Python/Python314/python.exe`，Java21为`C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot`，SDK为`D:/Android/Sdk`；核实路径后设置UTF-8及相应环境。不要用普通Debug或关闭R8替代benchmark。

```powershell
# 新增两个方法后，该类应只包含此次两个必要行为检查；否则改为精确方法selector。
# RED仅选这两个新方法；GREEN才加下列已有真实绘制方法及格式检查。
python scripts/gradle-coordinator.py run --key reader-open-p0-green -- gradlew.bat :app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.ui.reader.loader.ReaderOpenLatencyWiringTest" --tests "eu.kanade.tachiyomi.ui.reader.viewer.ReaderImageOrientationWiringTest.first content probe requires real bitmap draw and rejects old request" :app:spotlessKotlinCheck --offline --no-parallel --max-workers=2

# GREEN通过后一次诊断构建；正式候选不是本轮目标。
python scripts/gradle-coordinator.py run --key reader-open-p0-benchmark -- gradlew.bat :app:assembleBenchmark --offline --no-parallel --max-workers=2
```

命令里的`python`须解析到上述Python或改用绝对路径。每次任务key唯一，先查已有任务状态；离线缺依赖先说明具体缺项并按网络规则补齐，不盲目换镜像/装新SDK。只格式化本次改动，避免全库Apply产生无关diff。

## 7. 交付与停止规则

首次交付包含：一个最小优化提交、可安装的隔离优化APK、同场景三样本前后表和原始证据索引，以及B/C尚未执行的清单。结果正文必须先讲用户能感受到的变化，再列数字；不把平均值或约90ms窗口帧写成漫画已秒开。

完成A3即向用户报告，保留B/C未勾选；不以完整回归未做为由扣住首轮结果，也不把首轮结果标成正式发布通过。新问题先交原实施者修最小范围，修复后仅复验受影响项；禁止无新证据反复构建、重复全面审查、扩成全量矩阵。

后续功能改动完成必要验证后按根规则提交，只暂存本任务文件；公开README/网站/changelog等能力说明等待用户明确验收。共享契约/数据/发布身份若真的改动，则相关必要验证不可用“以后完整测试”跳过，应缩回P0或说明新的范围。

本文件编写时已核对本机交接文件、文档链接、任务状态和文本差异；没有执行上述产品测试、构建、安装或真机采样。所有任务保持未完成。
