# Windows 阅读器下一轮优化分析：安卓经验与收益排序

日期：2026-11-06。状态：分析与roadmap已拟定，未实施任何候选、未新增性能样本。生产基线为独立工作树 `mihon-desktop-reader-first-open-20261105` 的 `42531460dfeb08cc0bec2356f0951cd332913094`，即保留D3/D4、撤回D5性能候选并保留正常点击锚点的E0产品逻辑。主目录的其他改动不属于此基线。

执行入口：[D6 roadmap](../roadmap/2026-11-06-desktop-reader-d6-roadmap.md)。前置证据：[D5报告](desktop-reader-d5-2026-11-06.md)、[22行原始端点表](desktop-reader-d5-2026-11-06.csv)。本文件记录候选和判断依据，不把静态复杂度、诊断样本或工程估计写成已经取得的用户收益。

## 1. 目标、口径与可比较边界

目标仍是正常点击后的可辨响应R≤100ms、无内容K≤60ms，以及正确漫画首见F提前；双页分别报告首张与两页齐全。图像质量、恢复位置、正常翻页、重试、代次/内容身份、资源关闭和进度是硬条件。

正式D5样本关闭细诊断、保留同一点击锚点。下表起点为应用章行callback，终点为应用FIRST_PAGE_PRESENTED；不是系统DOWN，也不是物理扫描。阶段取同一打开的相邻边界，双页末段从最早目标开流到两页都确认，包含其他目标的后续工作，不是某一页独占成本。

| 当前E0样本，ms | callback→会话激活 | →页表就绪 | →首目标开流 | →完整应用首绘 | 总计 |
|---|---:|---:|---:|---:|---:|
| 单页首次① | 36.907 | 71.333 | 154.760 | 61.847 | 324.846 |
| 单页首次②，反向顺序 | 33.911 | 67.584 | 132.227 | 65.607 | 299.329 |
| 单页再次 | 5.751 | 10.056 | 25.079 | 41.500 | 82.386 |
| 双页关闭匹配首次，技术失效后fresh替代 | 36.468 | 70.082 | 263.476 | 125.993 | 496.019 |
| 双页关闭匹配再次 | 13.949 | 9.993 | 23.575 | 71.251 | 118.768 |
| 双页开启匹配首次 | 40.321 | 71.998 | 148.369 | 369.879 | 630.567 |
| 双页开启匹配再次 | 4.786 | 9.683 | 21.272 | 63.387 | 99.128 |

单页首次外部F_callback为342.878–359.546、308.786–342.120ms，K分别225.001–325.001、191.669–308.337ms。双页OFF first完整F_callback为482.775–532.775ms，ON为576.451–651.451ms。OFF实际页对[4,3]，ON首次[5,4]、再次[6,5]；不同页对不能直接相减成自动匹配算法耗时。样本少，不是P95或统计置信区间。

单页首次相对本轮warm多216.943–242.460ms，其中页表后到开流多107.148–129.681ms，是最大额外区间。它包含实际工作和状态/界面交接，不能将整个区间视为纯等待或可回收收益。

## 2. 生产加载链与已排除的误判

1. 正常详情点击经 `selectedDesktopReaderOpenContext` 原子读取恢复进度、构建章节引用，再导航和创建runtime；session激活前后建立内容store、图片pipeline、owner及调度器。
2. session先打开进度会话，再定位下载内容、解析路由、打开归档、取得条目快照并生成descriptor，接受到共享core后报告PAGE_LIST_READY。普通CBZ已经使用D3路径；没有全章逐图解码或P0前的全章header门槛。
3. 页表状态进入ReaderScreenModel，准备共享呈现；实际Compose视口提交调用 `beginPagerViewport`，经 `scheduleViewportWithoutProgress` 才给普通内容准备提供需求。归档条目进入encoded store，descriptor Ready后，holder在实际挂载时申请内容和解码。
4. decoder返回后还要发布holder Ready、接入呈现frame、执行实际draw、确认当前身份并更新进度。确认不是额外等待所有图片后才允许任何子图出现；D5 ON确有先地图后另一页的原帧。

关键源码：[打开上下文](../../app-desktop/src/main/kotlin/mihon/desktop/reader/DesktopReaderOpenContext.kt)、[会话](../../app-desktop/src/main/kotlin/mihon/desktop/reader/DesktopReaderSession.kt)、[内容路由/提取](../../app-desktop/src/main/kotlin/mihon/desktop/reader/DesktopReaderMaterializePorts.kt)、[模型与呈现](../../app-desktop/src/main/kotlin/mihon/desktop/ui/reader/ReaderScreenModel.kt)、[图片pipeline](../../app-desktop/src/main/kotlin/mihon/desktop/reader/DesktopReaderPageImagePipeline.kt)。

现有E0单页细诊断仅用于判断结构和局部工作：目标归档copy约5.2ms，header实际读取约0.47–0.63ms，完整内容读取约0.53–1.17ms；decoder总调用约40.6–47.3ms，其中readPixels约22.8–26.5ms。首次holder Ready到呈现准备开始约52.5ms，而准备开始到首绘约2.3ms。这不是与上表同次、同仪器的数据，不能跨表相减计算剩余成本；尤其不能把52.5ms直接当作正常运行必可省时间。

首次归档/类加载、EDT工作、状态传播、锁/预算准入和帧调度均可能影响区间，但目前未逐项因果分摊。JFR出现类加载/链接栈只证明它发生过；普通worker park、CPU计数或DECODE开始标记不能证明线程阻塞原因、解码完成或用户已看到漫画。

## 3. 记录最值得借鉴的三处

### C1：旧页身份查找复用，避免逐页线性搜索

- 安卓依据：O8已用owner现有content索引消除事实请求的重复扫描。Desktop所用SharedDualPageSession、ReaderSpreadSamplesOwner、ReaderDualPagePresentationOwner和ReaderSessionCore与B9工作树逐字节一致；共享O8收益不是待移植收益。
- 新的桌面条件：`ReaderScreenModel.ensureDualSession` 在 `ids.map` 内对 `previous.identity.content.firstOrNull` 查询旧方向。N个目标逐个线性搜索，稳定同序同版本时有N(N+1)/2次身份匹配的结构，187页为17,578次/调用；这是源码推导，不是实测计数或毫秒。该方法还在生命周期锁内做stamps、content、map和状态发布。
- 最小方向：在已证明sameOrder时按对应ordinal复用旧身份；对重排/窗口变化可用本次调用的有界索引。保留pageId与contentVersion双重检查，版本不符仍回到原默认方向，不按sourceIndex假定数组位置，不新增跨代永久map。
- 历史边界：旧[A2](../research/reader-pairing/2026-11-01-main-reevaluation/A2.md)是共享certifier索引复用，大章更快但小章wall反向；本项仅针对Desktop adapter的嵌套查询，不恢复旧补丁、不改评分/认证核心。没有实际访问次数、短章负控和production回归不得推进。

### C2：相同输入不重复做整章转换、认证和呈现投影

- 安卓依据：P3/R2跳过已经摄入的首次重复快照；R1在reducer返回同对象时跳过旧模型映射；O2按layout/fact/sample/header revision只投影尚未投影的输入。不是简单 `drop(1)` 或只看accept返回true。
- 桌面事实：MutableStateFlow会抑制同值发布，但 `ensureDualSession`、`acceptSessionState`、`publishDualSession` 在发布前仍可能遍历页面、构建身份、调用certifyPairing及preparePresentationFrame。D4只修无进展publisher自重臂，未证明这些合法输入下的重复工作都已消失。
- 最小方向：先记录当前打开窗口中的实际调用次数、输入版本及inclusive/exclusive工作，找到确定重复的一个入口。仅复用该入口上已完成的相同结果；门槛须覆盖章节/代次、顺序与内容版本、方向/单双页模式、配对开关、位置、重试、原始header/采样、呈现可绘及手势pending等真实依赖，按实际消费缩小键，不能拍脑袋用几个revision全局跳过。
- 首次原快照被其他ensure提前摄入、notify重入、metadata变化和资源真正退出仍必须触发需要的后续工作。不吞通知、不取消现有旧图保护，不建第二个scheduler。若完整依赖不能用既有稳定身份表达，本候选停止，不通过新增多套版本系统凑出缓存。
- C1/C2重叠：C2可能减少C1调用次数；两项收益不能相加，也不能一轮混改后分别记账。

### C3：同一次打开复用下载判断及已解析位置

- 安卓依据：P4把本次已定位的章节位置传给默认loader，O4复用初始列表并按需判断下载状态。安卓P4约37ms的历史均值收益不是Windows的预计扣除量。
- 已确认的桌面点：`toReaderChapterRefs` 将同一 `isChapterDownloaded(chapter)` 分别用于isDownloaded和过滤，非source0每章调用两次。可在本次投影每章求一次，两个字段复用。isDownloaded本身仍为导航所需，不能照搬Android SHOW_ALL整条路径0次的断言。
- 同次位置复用：入口可能只保留Boolean，`DesktopReaderChapterContentPort`再定位实际下载对象；provider实现不同，先证明正常UI同次重复物理定位，再考虑传递本次已验证的match。必须完整身份/有效期绑定，下次打开重查，失败仍按原路由处理；不以目录mtime、全局缓存或旧的Boolean代替实际内容权威。
- 当前夹具限制：source0的章节引用下载判断会短路，故“2→1”不改善D5史莱姆样本。实际内容路由仍要定位，不能因此说所有下载IO为0。一般来源/长列表可能比当前单章夹具更受益，必须另用小型真实接线夹具验证，不能换场景后冒称解决当前黑屏。

依据：[安卓P3](reader-startup-p3-2026-11-03.md)、[P4](reader-startup-p4-2026-11-04.md)、[R1/R2](reader-startup-autonomous-2026-11-04.md)、[O2/O4/O8/O9](reader-open-o-series-2026-11-04.md)。对应桌面入口：[ensureDualSession/acceptSessionState](../../app-desktop/src/main/kotlin/mihon/desktop/ui/reader/ReaderScreenModel.kt)、[章节引用](../../app-desktop/src/main/kotlin/mihon/desktop/ui/library/ReaderChapterRefs.kt)、[下载provider](../../app-desktop/src/main/kotlin/mihon/desktop/download/DesktopDownloadProvider.kt)。

## 4. 全链候选按预期绝对收益排序

排序针对现有史莱姆CBZ首开，同时保留单页和双页；是立项前的工程判断，不是已测收益。没有数据支持有置信度的毫秒预测，故用数量级/条件表达，允许收益为0；窗口大小不等于可回收量。前两项尚不能确定谁更大，暂按明确可减少工作与跨模式覆盖先后。双页关闭/开启匹配使用不同另一页，不能把两列差值当某算法贡献。

| 排名 | 候选 | 预计收益及置信度 | 实测窗口/机制依据 | 进入实现的前提 |
|---|---|---|---|---|
| 1 | C2：重复全章转换、认证/呈现投影去重 | 中高机会，可能数十ms；收益置信低 | 单页页表后132–155ms、OFF263ms；代码有重复遍历/投影入口，实际次数/独占成本未定 | 至少一条真实相同输入重复工作及可控反例，不把整个窗口归它 |
| 2 | C4：图片Ready到可绘帧的状态/界面交接 | 中高机会，约一个至数个帧机会；收益置信低 | 单页诊断Ready后约52.5ms才开始准备；ON开流到完整首绘370ms混合区间 | 证明是哪次可避免的排队/往返或重复布局，保留第一张与整组端点 |
| 3 | C5：点击后运行时、进度/页表首次工作去重复 | 中等机会，个位至数十ms；收益置信低 | 首次激活前34–40ms、其后页表68–72ms，warm明显短 | 定位具体重复或无必要依赖；不能把首次类加载/DB全部叫可删除成本 |
| 4 | C1：Desktop旧身份N次线性查找改为有界查找 | 低至中，单次可能很小，多次累计决定；结构证据强、wall证据缺 | 187页嵌套查找/锁内分配；C2可同时减少次数 | production计数RED、短章/换代/方向负控，先不为小改动单独重型发包 |
| 5 | C6：decoder内原图/输出位图工作的等价简化 | 低至中，仅总decoder的一部分；收益置信低 | 单页诊断decoder约41–47ms，readPixels约23–26ms；其余有分配、方向/采样/输出等 | 同一真实图片证明哪一项重复；不得把全部非像素差值当冗余 |
| 6 | C3：下载判断与位置结果复用 | 当前source0“重复Boolean求值”收益为0；一般来源长列表可能上移 | 章节引用有两次调用；实际重复物理定位尚未测 | 小型非source0真实接线，复用范围与失败策略清楚；单列通用场景收益 |
| 7 | C7：减少纯header/内容小读IO | 当前极低优先级 | 单次纯header约0.5–0.6ms、完整内容约0.5–1.2ms；header队列/解析另算 | 只有出现新的重复大读证据才立项，否则不单独实验 |

C4不能借名重试D5：保持当前普通内容触发点不变，先分析decoder返回→holder Ready→呈现准备→draw的真实依赖，区分事实不足、状态传播、EDT忙和帧机会。候选只能消除已证明多余的交接/工作，不能直接放行不属于当前请求的图片、提前记已读、把完整两页确认当首张或用旧画面掩盖等待。

C5涵盖打开上下文的原子进度读取、runtime创建、下载定位、归档/descriptor及可选catalog完成；前三者的正确性不能通过移到启动/书库偷偷预热而绕开。只对已验证重复调用复用本次结果，或用既有依赖结构消除无必要阻塞；不能在分析阶段承诺JIT、缓存、CDS或依赖升级收益。

C6当前普通静态路径可能先读完整原图、提取各方向原图采样，再生成显示输出位图。需核采样、方向、色彩/透明度、实际分辨率、缩放精读和内存峰值；区域tile/动画不能随普通静态优化被误改。Android硬件位图候选、SSIV策略和ART编译不能直接迁到Skia。现有低扰动正式样本没有decoder内部细分，C6排名可被新证据下调。

## 5. 不计入本轮预计收益的项目

- P0全章header门槛、共享O8索引及窄浮层参数已经具备同类行为，不能重复记收益；O2/O4/O8/O9在Android多数只证实少做工作，不代表每项都证明首开稳定变快。
- D5提前encoded准备、旧提前image lease候选B均已撤回；反向单页未重现、OFF首次明显更慢，不能组合重试、择优模式或删负样本。
- 邻页/配对可选工作竞争目前只有嫌疑。Android O6/O7调度候选没有稳定净收益，Desktop D5也有反例。只有同一实际请求链的CPU/锁/预算阻塞证据成立，才另定一个受限候选；不先暂停所有邻页/配对或改全局并发。
- 章级缓存、旧A2/A3/A4、配对算法/采样公式、Compose版本、GPU/驱动及CDS不因“最大窗口很大”而自动进入本批。黑底出现时机是体验事实，改背景、动画、保留旧图不计加载收益。
- 首绘前不要GET、录制仍在运行才点击、严格锚点关联和分帧误差是测量质量要求，不是性能优化；官方Android仍只是跨设备体验目标。

## 6. 排名与执行顺序为何不同

按预计绝对收益：C2 → C4 → C5 → C1 → C6 → C3 → C7。按低成本取得可失败证据：先D6.0核基线/有界归因，再C1的真实计数与C2的调用账；只选择一个机制进入首轮候选。若C1实测微小，不单独发包追噪声；转C2归因，C1仅可按roadmap明确的局部工作量标准收口。未授权“一次推进所有7项”，C4/C5/C6分别受进入门槛限制，C3先以通用场景局部修复评估，C7默认不执行。

总收益不累加：C1/C2共享调用，C2/C4共享界面机会，C3/C5可能共享定位，decoder提早也可能不跨越下一帧。每次采用后必须用新基线重算剩余排名，不把本报告的窗口永远当成尚未优化的预算。实测反向、成本过高或机制无证据时允许“完成调查但不实施”，不能用全量测试代替归因。
