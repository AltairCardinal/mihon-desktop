# Android 阅读器稀疏打开探针

用于已下载章节的定向诊断，不是正式性能门禁，也不改变阅读器调度。默认关闭。实现位于 app 的 ReaderOpenLatencyProbe，单页 PagerPageHolder 与共享双页 DualPagerPageHolder 共用实际图像绘制回调。

## 已验收签名包的源码边界

从已验收平板候选 `0.19.4-aex.118` 起，`scripts/build-android.py candidate` 通过 `android.candidateVariant=release` 选择 `app/src/noReaderProbe/java` 的常量禁用适配器。R8 移除计时方法，成品不包含 session、事件存储、配置读取和导出逻辑。适配器保留调用接口，避免诊断代码影响阅读器功能接线。

普通调试、benchmark 以及未指定上述 candidate 属性的底层 Gradle 构建继续选择 `app/src/readerProbe/java` 的诊断实现。正式无探针交付须走 candidate 入口，不能仅用运行时 enabled=false 代替成品检查。构建后应验证 DEX 中既无记录器方法，也无 Session、TargetBinding、reader-open-real 配置路径和 benchmark 准备 Activity。候选源码或签名核验失败时停止安装，不更换证书或清除用户数据。

## 配置与使用

1. 使用现有 benchmark 变体构建；它继承 release 的 R8、资源压缩和非调试运行时，使用独立的 app.mihon.desktop.fork.benchmark 身份与诊断签名。它不是正式候选，不递增正式版本，也不能覆盖官方、fork 正式或上游 benchmark。
2. 只在已授权的隔离实例准备虚构条目和相同 CBZ。benchmark sourceSet 的 ReaderLatencyPreparationActivity 仅负责准备，不打开阅读器；它校验固定的原始 CBZ SHA-256、187 张图片及复制后字节。该 Activity 不进入正式 release。
3. 若对照样本使用 SAF，须从正常存储位置设置进入系统目录选择器，为隔离应用授予专用目录，将相同 CBZ 放到 DownloadProvider 的规范路径。准备 Activity 的私有 file 路径不能直接冒充 SAF 对照。
4. 在应用外部 files/reader-open-real/config.json 写入以下字段，然后重新启动隔离应用的 MainActivity。配置只在主界面初始化时读取；不能在阅读器热路径上读配置。

~~~json
{"enabled":true,"mangaId":1,"chapterId":1,"label":"unique-run-label","targetPage":0}
~~~

正常从书架进入作品详情，再点击目标章节。离开阅读器后，异步导出 files/reader-open-real/sessions/<label>-<request>.json。每个进程使用新 label，采集入口先确认目标文件不存在；request 在同一进程跨重配置保持唯一，在新进程从头分配。读取导出时须等待完整有效 JSON。关闭时设 enabled=false 或移除配置后重新启动；配置缺失或解析失败会禁用探针。

R2起`targetPage`是零基源页索引，省略时为0；配置在Main读取后冻结到每个session。它只指定观测页，不改变阅读进度或自动打开该页。须通过正常UI恢复同一目标，并核真实当前单元。page0封面天然单图的双页模式不代表真正双图性能；例如本轮恢复index4、当前单元source3/4另作登记。不同targetPage和模式的样本分别报告。

## 时间点及真实范围

| 时间点 | 生产链路范围 |
| --- | --- |
| MAIN_TOUCH_DOWN / UP | MainActivity 实际输入分发，记录收到触摸的 System.nanoTime |
| ACTIVITY_CREATE / OPEN_READER_INTENT / INIT_IO_ENTER | 目标 ReaderActivity 创建、ViewModel 初始化入口及 IO 上下文 |
| MANGA_READY / SOURCE_READY / CHAPTER_LIST_BEGIN / READY | 真实仓库、阅读进度打开和章节列表初始化；边界间可能包含其他原有工作 |
| CHAPTER_LOAD_BEGIN / CHAPTER_IO_ENTER | 实际 ChapterLoader 进入与 IO 执行 |
| DOWNLOAD_CHECK_BEGIN / END | DownloadManager 的真实已下载位置解析；P4保留此次查得的UniFile供本次加载消费，沿用sourceManager.getOrStub来源 |
| GET_PAGES_BEGIN / END | 真实 PageLoader.getPages，包含规范路径与归档打开/列表加载 |
| CHAPTER_PATH_BEGIN / END | DownloadPageLoader取得目标目录或CBZ：P4默认链消费本次已解析位置，未提供位置的旧入口仍调用DownloadProvider；该区间不再必然包含一次目录解析 |
| ARCHIVE_OPEN_BEGIN / END、ARCHIVE_LIST_BEGIN / END | DownloadPageLoader 构造真实归档 reader，随后生成页列表 |
| HEADER_FACTS_BEGIN / END | 历史章级 Ready 页 header 遍历端点；下载首开 P0 已移除新加载和 Loaded 复用的遍历，当前样本应不出现，报告为不适用，不补造零耗时事件 |
| TARGET_HEADER_BEGIN / END | 单页 PagerPageHolder 或共享双页目标子图的原有 IO body 内，紧贴本页 `publishHeaderFact()` 调用前后；测实际调用 wall time，不含进入 IO 前排队。BEGIN 首次认领成功的同一次调用正常返回才写 END；取消／异常不补 END，后续调用不得补写 |
| ACTIVATION_UI_ENTER / PAIRING_OWNER_READY | 初始加载的 UI 激活 body 入口／真实 pairing.ensure 正常返回后 |
| READING_GATE_READY | 原无痕读取及可能的阅读 session 分支正常完成后；短路无痕分支不表示创建了session |
| CHAPTER_STATE_PUBLISH_BEGIN | 本次 viewerChapters 的 mutableState.update 调用之前，lambda外；不将可重试lambda当完成点 |
| ACTIVITY_CHAPTERS_RECEIVED | Activity setChapters 入口，必须匹配本次注册的具体 viewerChapters／chapter／pages对象及Activity捕获request |
| TARGET_IMAGE_ENTER / TARGET_HEADER_DISPATCH | 目标单页holder或双页子图setImage入口／原header withIOContext之前，呈现准备和身份快照之后 |
| VIEWER_CHAPTERS_APPLY_ENTER / PAGER_ADAPTER_READY / TARGET_HOLDER_CREATED | P2二级仅细分Activity收到章节到图片链入口：真实setChaptersInternal入口／adapter.setChapters返回／目标holder init绑定request后、启动loadJob前。调用若重入有重叠，应按真实顺序报告，不按名字重排 |
| TARGET_LOAD_ENTER / TARGET_STATUS_COLLECT / TARGET_READY_ENTER | P3目标holder原load协程body入口／原statusFlow.collectLatest调用前／首次Ready分支入口；保持原调度、supervisorScope和状态收集顺序 |
| PAGE_LIST_READY | ChapterLoader 将 materialize 页表结果接受并发布完成；已绑定章节与编码引用，不以前置全章 header 齐备为条件 |
| OPEN_PAGE / ENCODED_READ_COMPLETE | 配置目标页holder后半IO读取完整编码内容；本页header仍由既有按需链生产，可能位于PAGE_LIST_READY→OPEN_PAGE段，不能只看第一段缩短。OPEN_PAGE不是本次第一次打开文件，之前的header阶段可能已开流 |
| DECODE_SUBMIT | ReaderPageImageView提交配置目标页图像到原有解码链路 |
| FIRST_DRAW | 原有 nativeImageDrawn 实际漫画像素回调，SSIV 需要真实 bitmap 绘制且 ready；不是窗口帧、onReady 或 onImageLoaded |
| FRAME_COMMIT | FIRST_DRAW 后该视图的提交回调，仅 API 29+ 且硬件加速时记录；不是显示面板呈现时间 |
| UNIT_DRAWN | R2共享双页完整单元的内部绘制边界：目标位于当前选中且仍挂载的holder，owner/ticket/frame有效，全部required源页在同一内容身份下真实绘制完成。不是物理显示，也不替代外部双图可读画面 |

所有时间戳只记录一次。阶段区间是观测范围，不能自动解释为单个 API 的独占 CPU 耗时或缺陷原因。TARGET_HEADER 仅在同目标请求、正常内容可见且 PAGE_LIST_READY≤BEGIN≤END≤OPEN_PAGE≤FIRST_DRAW 时用于拆分：P=BEGIN−PAGE_LIST_READY，H=END−BEGIN，Q=OPEN_PAGE−END，三者应等于页表后的原第二段。H可能包含读取、解析、锁或缓存命中，不等于独占CPU；重复holder／换内容／取消存在归属歧义时不使用这对事件归因。R2已将目标holder/load/ready/image/header及viewer apply/adapter ready接入共享双页，仍未覆盖条漫；这些点只跟随配置目标页，不代表第二张图的所有内部阶段。

P4只在一次ChapterLoader加载内传递已解析下载位置，默认factory以ReaderChapter对象身份限定它，不能把它用于其它章节。它不是持久缓存或已打开文件描述符；新加载仍重新解析，取消/被替代的加载沿用原generation与loader回收。目录枚举、候选图片策略、排序与页面流仍执行原生产实现。位置随后消失或访问失败时发布存储错误，不另找其它下载位置或在线回退；原Boolean下载查询及其它下载消费者保持原契约。控制与P4使用相同事件槽与记录器，比较CHAPTER_PATH时必须明确上述语义变化，不能将消费已解析值描述为又执行一次查找。

## 浮层 composition body 提交诊断

O9 在实际消费阅读器状态的 Box body 与亮度 ContentOverlay body 直接放置条件 SideEffect，沿用 ReaderDiagnostics，记录 `overlay_composed`，context 分别为 ROOT 和 BRIGHTNESS_WRAPPER。process 与 Activity diagnosticSession 定位会话；它们不是可跳过的独立 Composable 探针，也不增加子组件 callback 参数。只有 Probe 开启时注册 SideEffect，执行时再次检查；关闭时不创建该分支 SideEffect 或字段 Map。开启状态不是 Compose State，配置须在本次 Reader 创建前完成；中途打开不保证立即触发 composition。

计数说明这些 body 成功提交，不能代替叶节点重组、实际绘制次数、CPU 成本或物理呈现。毫秒量化、日志轮转、队尾强停与丢弃边界仍适用，缺事件时不能无条件填零。对照两臂须携带相同诊断；实际提交次数不同也会改变诊断写入量，Probe ON 的小幅耗时差不能直接当作关闭探针后的产品净收益。真实亮度及菜单／错误反馈仍由生产状态与原偏好订阅驱动，诊断不参与显示决策。

## 开销、隔离与失败边界

O7另在既有ReaderDiagnostics滚动日志中记录实际采样调用，不新增稀疏时间槽。仅`ReaderOpenLatencyProbe.enabled`为true时创建调用上下文：`sample_begin/end`包围`sampleOriginal`，`sample_region_begin/end`紧贴真实`decoder.decode`。以process、Activity session、chapter、sourceIndex及进程内serial关联；context为本调用内从1开始的region序号，调用级为0。serial溢出后从1重新开始，不是持久身份。关闭时不创建每调用上下文、序号或字段Map；已开启的调用即使中途关闭仍闭合边界。

`sample_end`的COMPUTED仅表示返回采样结果，不表示共享owner接受；NO_PLAN、NO_RESULT、CANCELLED、ERROR分别保留无计划、无结果、取消和异常边界。region的RETURNED仅表示decode返回，不保证后续尺寸、资源或身份校验通过。自动匹配OFF与Probe关闭不同：缺失原图几何的必需事实任务仍可到达采样链，不能先验要求OFF零调用。普通read与采样span重叠只能证明wall-time重叠，不能证明独占CPU争用或因果收益。

这些日志使用量化到毫秒的System.nanoTime，和稀疏probe的纳秒端点比较时保留边界量化误差；它们不是物理显示时间。判断没有采样前，须核该session探针确实启用、进程/会话起止存在、所有滚动文件范围完整且无dropped/write_failure；强停可能使队尾未写出。缺事件或文件轮转不完整时结论为未知，不能填零。

- 启用时在主线程预分配 32 个 session，每个 session 有固定54个 AtomicLong 时间槽（历史44槽位、R2追加UNIT_DRAWN、O3追加三个记录存在性槽、O5追加六个AUTO构造槽）；超出预算拒绝继续采样，不增长集合。
- O3的`PAIRING_RECORD_EMPTY / VALID / INVALID`只在真实`acceptRestore`接受成功后记录：同一repository snapshot的record为空、非空且通过原校验、非空且原校验产生INVALID_RECORD。恢复入口捕获request，完成时不借用新请求；无额外仓库读取或写入。每类事件只保留首次时间，同请求显式重试可能产生多种分类，须结合动作与时序解释；没有marker不能反推记录为空，也可能未接受、已关闭或请求过期。该诊断不证明屏幕已绘制，不改变既有JSON结构；对照两臂必须携带相同诊断。
- O5的`AUTO_{SINGLE|DUAL}_CREATED_{INVALID|PORTRAIT|LANDSCAPE}`只在真实`ReadingMode.toViewer`成功返回后记录实际类型；仅AUTO且Probe启用时在调用前读取容器扣padding的内容尺寸。任一边不大于0标INVALID，有效宽大于高标LANDSCAPE，其余有效尺寸（包括正方形）标PORTRAIT；这是几何标签，不是Adaptive策略阈值或设备朝向。沿用该Activity创建时捕获的request及原intent章节，旧Activity不能借用新请求。各类只记第一次，顺序可证明同request出现过两类构造，不能统计精确总次数；无marker也不能独立反证没构造。人工尺寸夹具只验证诊断接线，是否存在真实首开重复构造须看实际AUTO样本。该取证不改变布局决策、JSON结构或计时端点，不增加I/O/provider；关闭时不读尺寸或写事件。
- 热路径只校验目标 chapter/page/request 并对固定槽执行首次写入 CAS；不加旧版持锁日志、不做快照、文件写入或后台导出调度。capturedEvents/contains 只供测试和导出使用。
- P2跨层关联只在既有32个session中保存章节、页表、首页和待发布viewerChapters的弱身份；未启用时不建立绑定，不写业务／持久状态，重配置和导出后失效，弱引用失效即拒收。VM只传初始init已捕获request；Activity用自身已捕获request及具体发布对象核验，单页holder从该Activity和同对象绑定捕获request，禁止迟到时回退全局active request。绑定setup仅在诊断生命周期执行，事件仍走原CAS槽。
- P2一级仅对全链身份有效且非递减的样本相减，PAGE_LIST_READY→ACTIVATION_UI_ENTER→PAIRING_OWNER_READY→READING_GATE_READY→CHAPTER_STATE_PUBLISH_BEGIN→ACTIVITY_CHAPTERS_RECEIVED→TARGET_IMAGE_ENTER→TARGET_HEADER_DISPATCH→TARGET_HEADER_BEGIN的八个相邻wall区间之和应精确等于P；不重排名字、不跨尝试补洞，也不把区间全部当CPU。
- P2二级另外导出 `pagerAwaitedIdle`（非时间点）：只在首次合法viewer apply被认领时保存其实际调用来源；原idle回调为true，直接调用为false，未命中则字段缺席。相同弱身份和request验证、首次CAS保护及session重配置边界仍适用；不能仅凭viewer时间间隔猜测idle路径。
- P3另有四种固定名同步Perfetto section：`ReaderP3.singleFact`、`ReaderP3.ensureSingleOwner`、`ReaderP3.capturePresentation`、`ReaderP3.holderAttach`。只在已绑定当前目标request且TARGET_HEADER_BEGIN尚未记录时启用；viewer通过当前Activity及目标首页弱身份解析request，章节内其它页仅计该请求窗口的回调。每次begin在同线程finally配对end，不跨suspend或创建任务；关闭时直接执行原body。用于调用次数、嵌套与调度归属；父子section不能相加，开启时trace自身开销不为零，不以新旧仪器差值校正成本。
- page取session冻结的targetPage，默认0；VM绑定实际ReaderPage的弱引用。单页与双页holder在绑定时捕获chapter/page/request，迟到旧请求与其他章节/页不能写入当前session。双页子图额外检查当前选中且挂载的presentation，防预取图抢先记录FIRST_DRAW；关闭探针时不执行该诊断判定，原业务绘制回调顺序不变。异步提交回调捕获同一身份。
- R2的`ReaderR2.dualFact`仅包裹双页adapter实际同步事实处理，同线程finally配对；通过当前Activity、目标弱页和request检查，且仍沿用TARGET_HEADER_BEGIN前的startup守卫。计数/inclusive时间只代表这个窗口，不能叫整章全部通知成本，也不能与包含它的父区间直接相加。没有新增全局日志、后台采样或状态provider。
- ReaderActivity.onStop 用该 Activity 的 request 导出；快照、label 与目录在调度前绑定，后续活跃请求不能改写其归属。强停前未离开阅读器会丢失该样本；必要端点缺失或不完整导出必须判无效，不能补造时间点；HEADER_FACTS 等可选事件缺席不使样本无效，也不证明真机图像读取字节为零。
- 默认关闭的探针仍保留轻量入口判断；既有 ReaderDiagnostics 的产品开销保持原样，不能把这些开销归因于新增稀疏探针，也不能声称测量时它被禁用。
- 本探针覆盖分页单页/共享双页每次配置的一个目标页及当前双页单元，不同时遍历跟踪整章，不宣称覆盖Webtoon、所有存储类型、跨进程连续请求或完整发布性能矩阵。
- 方向切换触发ReaderActivity重建时，每次onCreate都会分配独立request，而保留的ViewModel/页表可能仍属于旧请求绑定；当前完整首绘端点可能缺席。各Activity的构造marker可分别作为生命周期证据，但不能把旧请求输入/页表与新请求绘制拼成完整耗时；这类样本不得通过缺失端点的性能校验。真实画面正确不等于探针完整，需保留每份原始导出并明确测量缺口。
- System.nanoTime、Perfetto trace clock、设备 uptime 和 MKV 原始相对 PTS 必须分别保存。跨时钟对齐需同次 trace clock snapshot 等证据；MKV 从零开始不能靠主机启动时间强行映射。
- 外部原表对照仍须同时录制原 MKV/Perfetto，保留原始逐帧 PTS，人工给出响应/近黑/首漫画的上下界。探针 FIRST_DRAW 不能直接替代原表视觉端点。

此前P0–R1真机只对单页模式计时；当时对照通过正常UI设为RTL，准备Activity初始LTR样本不混入对齐表。R2新增真正双图评估，来源、设置及完成状态以[持续结果](../evidence/reader-startup-autonomous-2026-11-04.md)为准，不能用仪器接线通过冒充真机性能达标。

## 定向验证

ReaderOpenLatencyProbeTest 覆盖默认禁用、32 session 上界、首次写入、错误页/章节、跨重配置旧请求拒绝。ReaderOpenLatencyWiringTest 执行真实 ChapterLoader/materialize：187 页新加载及新建 Loaded 页复用均不打开章级图像流；复用后仅目标页通过既有生产者取得真实方向与尺寸，编码身份和恢复页保持有效。ReaderImageOrientationWiringTest 中的 first content probe requires real bitmap draw and rejects old request 使用真实 SSIV bitmap/canvas，证明未绘制与旧请求不会产生 FIRST_DRAW，有效实际绘制才记录。

R2的Probe目标4回归和`ReaderChapterWindowProductionWiringTest`使用真实VM／187页绑定，拒绝默认页0和旧request误记。`DualPagerPageHolderLayoutTest`以真实双PNG／Canvas验证：当前目标绘制后有FIRST_DRAW但另一图未绘时没有UNIT_DRAWN，全部required图完成才有UNIT_DRAWN；未挂载的非选中holder和旧request均不能抢记。视频仍需独立确认首部分漫画和完整双图，不能用回调代替像素分类。

按根规则通过 Gradle 协调器运行这些 focused 测试与受影响格式检查；本文不授权完整模块或全量测试。真机验证须核实际 APK 身份、非调试标志、签名与哈希，且只操作当前已授权、确认解锁的设备及隔离包。
