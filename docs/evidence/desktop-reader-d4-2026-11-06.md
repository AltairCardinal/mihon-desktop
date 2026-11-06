# Windows 首开 D4：页表之后的实际等待

2026-11-06，D4执行收口，体验目标仍未达标。接续[首开roadmap](../roadmap/2026-11-05-desktop-reader-first-open.md)的D4，起点36896c7627054f784d474ce8a3fdb71c21ee4b04。只在独立工作树D:/Codex/worktrees/mihon-desktop-reader-first-open-20261105执行；主目录、Android及未完成的扩展兼容改动不纳入。Sol主要实施/定向验证，Luna读取和数据提取，主代理操作窗口并独立验收。

## 固定条件与证据边界

控制为上一轮冻结C，preview清单20261105T222432Z-f9e696d4；内容和原件见[上一轮报告](desktop-reader-first-open-2026-11-06.md)。同SHA史莱姆04卷、187页、首开index4世界地图、RTL；单页与双页开关匹配分别核实际mountedReader。只做本轮受影响范围，复用现有控制包，最多新增一个候选preview，不执行完整模块/全量或正式发布。

原始DECODE是decoder调用前事件，不能作为解码完成时间。D4补充默认关闭的MIHON_READER_STARTUP_OBSERVATION=1诊断：内存有界，不同步落盘、不方法采样、不picture replay；仅Test Mode准备时初始化CPU观测与自有窗口AWT DOWN/UP监听。CPU只在同一线程的配对点之间相减；异步和并行区间不相加。新诊断只用于归因，正式C/D外部对照关闭，沿用原productionEvents与WGC。

正常UI诊断三份位于.test-tmp/desktop-first-open-d4-20261106/actual/diag-single、diag-dual-off、diag-dual-on，各自state.json。单页204事件、关闭匹配415事件，无溢出；开启匹配保最早1024事件、丢弃后续7409事件。开启匹配的DOWN/UP、CHAPTER_CLICK、目标4/5的decoder/holder/prepare及两条FIRST_PAGE_PRESENTED均保留，末条保留事件晚于两页首次绘制；只解释保全的首绘前区间，不宣称完整后台过程。三份采集均未启WGC，全部正常保存进度、POST shutdown退出。

Test Profile隔离仍使用--test-profile：Main导入platform.DesktopTestProfile，在启动前configure修改user.home和PreferencesFactory；DesktopPlatformPaths.current在root非空时忽略系统APPDATA，落独立home/AppData。另一个test.DesktopTestProfile的--test-profile-dir入口不能代替该启动路径；本轮未更改profile实现。

## 调用归因、候选与验证

三份诊断以CHAPTER_CLICK为0，AWT DOWN约早19ms、UP约早1–2ms。新增FIRST_PAGE_PRESENTED观测为单页310.152ms，关闭匹配目标4为580.574ms/完整两页590.711ms，开启匹配目标4为668.391ms/完整两页710.558ms；均是应用绘制确认，不是窗口首见，也不与关闭诊断的C包直接相减来计算探针开销。

目标4真实header IO单页0.613ms、关闭匹配0.643ms、开启匹配0.615ms；decoder调用分别约34.9/27.5/66.1ms。开启匹配目标4在370.571ms结束decoder、475.248ms holder Ready、668.391ms首次绘制，说明旧DECODE之后的间隔混合了实际解码和多段后续等待，不能全归给图片头IO或纯解码。

CPU读数呈15.625ms级粒度，短span可出现CPU差大于wall；只作粗粒度辅助，不用来相减计算IO等待。开启匹配的EDT约94ms跨度同时增加约93.75ms CPU，只支持该段有较多线程工作，具体原因仍需真实production回归证明。关闭匹配部分header enqueue/admit/parse/accept未被当前hook覆盖，即使无drop也不能解释为未发生；不为补齐所有空栏重造诊断包。

机械提取在本轮luna/diag-reader-events.csv、diag-reader-spans.csv、diag-reader-extract-summary.json；原始state保留。主代理独立核对CHAPTER_CLICK、对应DOWN/UP、目标两页首次绘制及ON保留尾点，确认首绘端点均在保全区间内。

### 真实生产反例

先前静止测试仅看certificationRounds，不能计到没有真正进入认证的采样启动，因此合成12页、187记录配前12真实图片以及真实CBZ的该断言均未复现。持续render时还遇到合法后续IO由29到33，原件保留，不把它当成实现依据。

最终回归使用真实187页CBZ、细探针OFF、无HTTP GET，持续正常Compose render tick但不改变输入/几何，等待I/O和identity/fact/sample revision稳定后，以MockK调用原生产方法（callOriginal）计startSpreadSamplesLocked实际入口。主代理核对的实际XML为150ms内无新工作仍进入49次，期望0，形成真实RED；合成12页负控通过。次数会随调度变化，不是固定成本。这证明有不必要的重复触发，排除纯状态接口读出导致该反例；它本身不证明49次都位于首绘前或能回收等量wall时间，最终收益仍由关闭细探针的窗口对照裁决。

诊断runtime追加的快速翻页测试有2项双页expected5/actual4失败。在36896c原始production和原测试下，同一参数化方法4例的两个双页同样失败；本轮原文件逐字节保存/恢复并核hash。因此作为基线既有问题保留，不将未通过说成全部测试全绿，也不借D4扩修。

### 唯一候选及独立检查

真正反例不是“分类列表为空”，而是本轮已admit的capability没有发布结果，且已无active任务或待消费completion的终止出口。原实现退出后仍无条件自重臂。最小候选只在此出口等待真实流输入；分类时在生命周期锁内捕获输入revision，完成时若collector已消费过晚到的新输入，仍重臂一次。既有busy实际退出的唤醒、预算、内容身份、shared配对协议和Android均未改。空分类及预算出口的试探保护未产生该RED的修复，已撤去。

首轮相关focused9项GREEN；独立检查确认原token、取消、资源释放及绘制准入门槛保持，并要求补“分类至完成之间真实新需求已被collector消费”这一竞态。最终用当前真实unit demand、无后续render，受控hold无结果round并在其活动期间注入新token需求，排空已排队流后释放。含revision条件GREEN，单独移除条件的变异RED明确超时于等候后续真实production round；finally恢复源码。该验证不使用不一致的远页unit，也不直接篡改revision。原件late-input-drained-mutation-red.xml保留。

最终focused10/10 GREEN，0失败/跳过，32秒；真实CBZ用例明确设置MIHON_CBZ_DIAGNOSTIC执行，原件sol/final-focused10-green.xml、desktop-d4-final-focused10-budget.log/json。此前诊断接线13/13通过。组合复验还暴露缓存测试的旧假设：它将automaticMatching切换前后的attempted集合比较相等；SharedDualPageSession.configure现有协议明确close旧budget并创建新budget，调度不同会使旧集合偶然非空。主代理直接核共享源码后，将测试改为确认budget确已更换、新budget没有新attempt，保留缓存命中不增加open/decode及释放断言；共享production不改。失败/基线与修正原件保留，不删失败记录。

本轮独立检查一次、针对输入唤醒的补验一次；git diff --check通过，Desktop未接Spotless，未以其他模块NO-SOURCE冒称格式检查覆盖。代码、测试与默认关闭诊断已冻结，候选preview通过项目入口构建，外部收益待下节裁决。

## 原生对照的条件与原件

上一轮开始C/D对照时，用户按实体Esc停止Computer Use；当时只有未点击的C-single实例，无有效C/D样本，按要求停止。用户随后明确恢复电脑操作。本轮将中断目录另存actual/interrupted-C-single-20261106，复用冻结包、源码和已有focused结果，新建六份profile，完成12条正常详情点击。无覆盖中断原件、因结果慢而补采择优、重建候选或重复全量测试。

三模式均按实际mountedReader与画面核验；顺序为C-single/D-single、D-dual-off/C-dual-off、C-dual-on/D-dual-on，每份first、warm一对。单页[4]，关闭匹配两次均[4,3]，开启匹配首次[5,4]、正常保存进度后warm[6,5]；C/D对应phase一致，不能跨phase声称同页。fresh process不等于磁盘冷态；不声称充分随机化、控制JIT或给出P95。

正式样本移除MIHON_READER_STARTUP_OBSERVATION，保留原productionEvents。WGC实际首帧READY后，sky正常章行点击，六次QPC往返交集校准宽17.9–26.9微秒；sky调用包络96.1–112.6ms，保留为输入不确定范围。看到正确完整漫画后先采0.5秒实际JVM子PID的进程CPU，再GET状态、stop，避免首图前GET争锁。CPU窗不是固定首绘后时刻，不是纯空转或整机功率。

原件在.test-tmp/desktop-first-open-d4-20261106/actual/{C-single,D-single,C-dual-off,D-dual-off,C-dual-on,D-dual-on}/{first,warm}/：input.json、state.json、cpu-idle.json、ready.json、wgc/events.jsonl与原PNG/manifest。12条均主动stop保留PARTIAL/native-capture-exit:1，帧序连续、文件无缺失；只报告已覆盖的局部转换，不将告警改写为完整成功。首开局部最大帧隙约91.7–175.0ms，不能拿首匹配点值当精确转换。WGC不是物理显示扫描输出。

## 关闭细观测的内部阶段

下表均以最新OPEN_READER_INTENT为0，单位ms。HEADER旧事件并非纯IO；DECODE仍是调用前。双页完整确认取两目标页FIRST_PAGE_PRESENTED的最大值。这是应用确认，外部首见另列，不能嵌套求和。

| 模式/次数 | C页表 | D页表 | C开流→DECODE→完整确认 | D开流→DECODE→完整确认 | 完整确认D−C |
|---|---:|---:|---|---|---:|
| 单页first [4] |110.110|87.210|261.078→267.208→327.818|237.415→242.641→308.453|−19.365|
| 单页warm [4] |9.269|8.753|35.280→35.784→77.020|37.403→38.045→74.791|−2.229|
| 关闭匹配first [4,3] |72.257|78.932|目标4：372.070→387.393→519.701|目标4：386.330→400.536→544.416|+24.715|
| 关闭匹配warm [4,3] |9.647|11.323|目标4：37.521→37.917→122.567|目标4：54.975→60.596→139.933|+17.366|
| 开启匹配first [5,4] |70.689|70.288|目标4：219.931→226.826→598.947|目标4：226.212→232.841→580.859|−18.088|
| 开启匹配warm [6,5] |11.666|9.553|目标5：30.127→30.701→88.615|目标5：28.998→29.745→112.944|+24.329|

单页first的页表本身减少22.899ms，该环节不受无进展发布器修复影响，不能将完整确认减少19.365ms全部归为候选收益。开启匹配目标4的DECODE→确认由331.064降至301.909ms，但两者仍混合真实解码、调度、owner及UI工作；不能用约29ms差值证明整个延迟来源已解释。关闭匹配first及warm、开启匹配warm的本次内部确认更晚，代价保留。每mode/phase每臂只有1条，不能声称稳定提速或统计退化。

## 可见端点与采用范围

F按同一参考纹理、RGB平均绝对误差≤8的首个匹配窗口帧计算；前一帧为未匹配原帧。K使用单页章行ROI、双页每侧独立ROI与亮度均值<17/255，避开工具栏/提示。单位ms，F报相邻原帧与完整输入包络的转换区间；K报连续黑采样段的边界估计，长QPC间隙不拆段、不当作已经有内容，但也不能证明间隙内物理屏幕一直黑。各侧最大段与累计值在本轮相同，首图后直到stop未观察到再黑。

| 模式/次数 | C完整漫画F | D完整漫画F | C近黑K（逐页） | D近黑K（逐页） |
|---|---|---|---|---|
|单页/first|385.751–519.876|369.898–497.985|p4 250.003–375.003|p4 233.333–349.998|
|单页/warm|103.379–229.023|94.718–216.129|p4 49.999–83.332|p4 50.001–83.337|
|关闭匹配/first|554.511–691.035|569.995–721.459|p4 433.330–549.998；p3 433.330–549.998|p4 449.995–583.328；p3 449.995–583.328|
|关闭匹配/warm|130.127–251.231|162.919–277.878|p3 50.003–83.337；p4 83.337–125.005|p3 50.001–83.335；p4 116.671–150.007|
|开启匹配/first|637.054–782.947|612.572–752.044|p4 449.997–599.998；p5 516.665–633.331|p4 449.999–583.332；p5 500.000–616.666|
|开启匹配/warm|95.380–249.555|145.365–265.752|p6 50.001–116.670；p5 50.001–116.670|p6 50.001–108.336；p5 91.669–125.004|

可辨响应R单独按明确阅读器载入反馈标注，不把旧详情涟漪计入。主代理直接检查全部12份R边界；11份为loading文字与spinner，C-single/warm没有文字，全自动提取先退化成漫画上界，人工确认原帧1180仍为旧详情、1181已为中央spinner后修正为该转换。原自动提取保留，人工覆盖单列root-R-manual.json，不重跑/重采。

| 模式/次数 | C可辨响应R | D可辨响应R |
|---|---:|---:|
|单页/first|44.082–236.539|36.567–247.986|
|单页/warm|36.714–162.358|28.049–149.460|
|关闭匹配/first|37.846–224.370|36.666–221.465|
|关闭匹配/warm|30.123–142.893|29.582–144.537|
|开启匹配/first|37.056–232.948|29.240–218.710|
|开启匹配/warm|28.711–149.553|37.029–157.416|

12样本、20页面的输入/原事件QPC、F相邻帧、K逐页两端及原始计时由主代理独立复算通过，六份first的F边界原帧与全部R边界另作视觉核对；实际完整漫画及翻页也已原生确认。完整逐页数值及源帧号见[配套CSV](desktop-reader-d4-2026-11-06.csv)。离线提取的逐页nearBlack接线错误在交付前修正，失败原件不删；未将错误空K当成“零黑屏”。

所有C/D完整F区间都重叠，不能证明稳定首开提速或暖态无代价。单页first本次C385.751–519.876、D369.898–497.985ms；其可能差包含0。D近黑233.333–349.998ms，仍远高于60ms目标，R上界也未通过100ms预算。官方PCE-W30未修改0.19.9参考单页地图272.9–289.6ms、近黑16.8–50.9ms；与本次D的地图端点相减约80.3–225.1ms，仅为跨硬件/输入/采集的体验目标差，不能归因成软件净差。不得将旧轮C数值与本轮D混减来放大收益。


### 稳定画面后的进程CPU辅助观察

| 模式/次数 | C CPU ms / 约500ms墙钟 | D CPU ms / 约500ms墙钟 |
|---|---:|---:|
| 单页first |46.875|93.750|
| 单页warm |46.875|46.875|
| 关闭匹配first |78.125|46.875|
| 关闭匹配warm |93.750|78.125|
| 开启匹配first |578.125|31.250|
| 开启匹配warm |15.625|31.250|

开启匹配first的CPU窗分别在完整内部确认后7018.4/7329.2ms开始，约500.460/500.099ms，观察差546.875 CPU ms（94.6%）；多线程进程CPU可以超过墙钟。这一对样本支持候选减少该场景的后台负担，与实际无新工作RED/GREEN方向一致，但CPU窗含合法工作、时点不完全相同且只有一对，不等于所有差值都是空转、不换算wall提速或功耗。warm及其他模式不一致，不报告全模式CPU统一下降。

本轮采用边界是：保留有真实反例、独立竞态保护且消除无效自重臂的最小修复和默认关闭诊断；不把小幅内部变动或重叠的外部区间包装成稳定首开收益。无结果轮仍有在途任务/待消费完成时不提前休眠；新的需求、真实busy退出、内容/配置重试及取消按原链路继续。未改共享配对、Android、图片解码质量、恢复页或旧图拒收。D4任务收口不表示响应≤100ms、近黑≤60ms或追平官方的体验目标达成。

## 验证、产物与后续边界

本批超过8文件是同一默认关闭观测链的跨adapter/owner/Compose/Test Mode接线及一个发布器修复，另含真实行为测试、架构和证据；不以静态符号扫描代替production测试。已独立核对冻结清单17项源码/测试hash完全一致。最终focused10/10 GREEN，Gradle约32秒；诊断接线13/13沿用同一源码证据。两个既有双页快速点击失败在36896c基线同样复现，未扩修，不声称仓库全绿。未跑完整模块/全量；Desktop未接Spotless，编译、独立检查与git diff --check是实际完成的检查。

focused复验命令与环境权威原件为sol/desktop-d4-final-focused10-budget.json及.log；MIHON_CBZ_DIAGNOSTIC指向同SHA真实CBZ。选择器只覆盖SharedSpreadDesktopResourceWiringTest中的stationary、input consumed while no progress、exact normal full page、normal ready neighbor、publisher binding single、same page retry and configuration、idle consumed while original、replacement runtime waits for original non cooperative、content rebind on same position，不改为全类/全模块。诊断接线见ReaderStartupObservationTest及其原始XML。

D候选preview20261106T000419Z-1d640e92，source commit36896c加冻结差异，production输入2404文件、digest e46d89b711109fef08717cfb7871f7916a9fad5f830eab69e37c4017f9776108；build/sourceIntegrity/productionRuntime均PASS，构建时nativeInteraction保留NOT_RUN，本报告后续原生验收独立记录。完整包SHA256 435e336a8d52dde84d86526399735763ea17a7a612d360cc316aff5de8b7e281。

[实际D预览EXE](D:/Codex/worktrees/mihon-desktop-reader-first-open-20261105/app-desktop/artifacts/preview/windows/20261106T000419Z-1d640e92/Mihon-Desktop-0.11.24.6.36896c7-unpacked/Mihon%20Desktop.exe)，实际路径已核存在，d/build.log保留Final unpacked EXE。它从上述源码和冻结差异构建，不声称从本轮后续提交或主干构建。未整合主干、推送或正式发布，不更新尚未获用户验收的公共能力说明。

三种模式均实际看到正确漫画；候选单页[4]→[5]→[4]、关闭匹配[4,3]→[6,5]→[4,3]、开启匹配warm[6,5]→[7,6]→[6,5]翻页已核画面及状态。无错图、残留黑页或请求卡住。正常退出阅读器保存进度，关闭全部本任务实例/采集，QPC helper exit0，用户实例/配置保留。成功步骤补入[Windows延迟采集](../automation/DESKTOP_READER_LATENCY.md)，根AGENTS已有该入口；长期边界见[内部架构](../architecture/desktop-reader-startup-observation.md)。

后续若继续缩短首开，应把fresh JVM时的Compose/owner初始化和真实图像完成→UI提交分别归因；本轮已证明header IO不足1ms，继续盲砍header IO难以解释约300ms的DECODE之后区间。先用已有关闭诊断样本和默认关闭细观测提出可失败假设，避免重复修已排除的图片头IO或把单个CPU差值抵扣用户等待。新候选仍需真实RED和相同端点验证，不自动增加全量或新平台测试。
