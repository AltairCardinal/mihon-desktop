# Windows 阅读器 D5：初始内容准备实验与撤回

2026-11-06，D5已按计划完成；性能候选未达到采用门槛，已精确撤回。先写[完整计划](../roadmap/2026-11-06-desktop-reader-d5-plan.md)，再由原Sol medium实施、原Luna medium整理，根独立验收/唯一桌面输入。独立worktree mihon-desktop-reader-first-open-20261105，基线86c56288d29ba542a1d46922dc91efefbdaf054d；不含主目录后续Android或扩展兼容改动。

单页正向首次显示约提前34ms，反向顺序没有重现；关闭匹配的双页首次候选反而晚约100ms。开启匹配首次改善不能抵消其他条件的不利结果。保留正常点击的同仪器测量接线、真实失败测试证据和全部对照，最终生产加载行为回到E0/D4；本轮不申报首开性能收益或追平官方。

## 已知事实与待证假设

[D4](desktop-reader-d4-2026-11-06.md)的关闭细观测单页first/warm内部总308.453/74.791ms，页表Ready→OPEN_PAGE150.204/28.651ms。约121.554ms是两个样本的区间差，尚非已证实可回收成本。实际header IO约0.6ms；旧DECODE是调用前，不是解码完成。D4解决无进展发布器空转，但没有证明F稳定改善。

本轮先统一正常章行回调锚点，沿真实调用与现有诊断定位150ms，再选择一个真实RED支持的候选。正式对照细诊断关闭，正常callback与工具输入包络分列；WGC保留相邻原帧界限和长gap，不当作物理扫描。相同SHA史莱姆04卷、187页、index4、RTL及隔离设置沿计划，不跨轮混减。

## 测量接线与E0诊断

已在正常详情章行回调协程前记录detailChapterClicks（最多64条最近记录，sequence/chapterId/monotonicNanos/openIntentNanos），screen临时sequence不进入data class equality、导航key或持久化。构建runtime时沿既有probe.bind关联首次同章OPEN；测试API、取消及错章不能借用旧点击。仅Test Mode记录，细观测关闭仍导出；普通运行不读该时钟。真实正常入口first/warm及普通mode、controller关联/取消/重置/关闭/容量等14项focused通过，未跑模块全量。原件sol/click-anchor-focused-{ui,controller,observation}.xml及coordinator日志。

E0仅测量改动，preview20261106T025455Z-23e6a21a，源码基线86c5628加冻结测量差异，productSource 889551df123de1690d5a882c183132cffab857e11388e962efd53e7922d2a908，完整app tree adf29ae03ef8a527f4916e9121ccf88142d4d007f1f60c40e3a733b687d66cc2。build/sourceIntegrity/productionRuntime PASS，nativeInteraction构建时NOT_RUN保留；实际[控制包EXE](D:/Codex/worktrees/mihon-desktop-reader-first-open-20261105/app-desktop/artifacts/preview/windows/20261106T025455Z-23e6a21a/Mihon-Desktop-0.11.24.6.86c5628-unpacked/Mihon%20Desktop.exe)。未称从后续提交构建或主干交付。

根对该E0新隔离profile做单页fine ON正常章行first/warm诊断，无WGC、不首图前GET，确认完整地图后才导出。原件actual/diag-single/{first,warm}/state.json，分别190/累计374细事件、drop0；点击sequence1/2各唯一关联相应OPEN，根独立校验QPC及目标4成立。两次均正常退出保存进度、POST shutdown，实际路径无残留进程。细诊断只归因，不算关闭细诊断的正式E0/E1对照，也不与D4旧样本混算收益。

## 有界线程诊断与候选门槛

E0 slim runtime未包含jdk.jfr；另以同一冻结E0的207项原序classpath及全部JavaOptions、同版本Temurin21.0.11+10完整JDK做开发诊断。完整JDK和E0的jvm.dll SHA均为9dac8ae1dfe88cb107e27ee952071682f48ab67266920cbecf03b15bb02ce181，但多出的JFR/attach等模块仍属诊断差异，不当作正式E0性能数据。root从创建使用CREATE_NO_WINDOW启动javaw，隔离profile，按实际窗口正常章行first/warm，无WGC、完整地图后GET。

录制在详情ready后由精确PID66476的jcmd启动，1ms采样、park/monitor阈1ms、duration60s/maxsize32m，最终2,600,478字节。60s自动到期后手动stop提示找不到D5，原日志和文件保留，未重新录制/覆盖。正常退出阅读器与实例，CIM确认该PID退出。前后各8组QPC括取Precise FILETIME保存于actual/diag-jfr/clock-{before,after}.json；Sol用RecordingFile流式限定两次OPEN→FIRST区间，核录制QPC227970007635200..228030301875800覆盖first227980593996100..227981386982900及warm228002685030500..228002824050600。时钟offset保守union宽约13.8微秒，仍保留未知JFR内部换算误差，不声称纳秒精度。

采样发现一个具体候选门槛：该first的OPEN+511.122ms，EDT栈沿Compose DisposableEffect→ReaderPagerNativePresentation.begin→ReaderScreenModel.beginPagerViewport→onViewportLayoutSettled→session.scheduleViewportWithoutProgress→pageRunner.cancel/pump→PageFetchPort构造；目标archive copy到+545.363ms才开始。结合源码，普通encoded内容准备目前等真实Compose viewport提交才被调度。此栈只支持阶段/触发关系，不把511ms全归为该函数自耗时。随后细诊断OFF的真实factory/session RED确认：界面尚未挂载时，接受页表没有开始恢复页的普通内容准备。

这与此前撤回B（descriptor Ready后提前图片lease/解码）不同，改变的是descriptor成为Ready之前的普通内容调度触发。它证明存在可解除的依赖，不能证明提前触发在所有模式下有可见净收益。

## 实施、验证与采用裁决

唯一候选E1在PAGE_LIST_READY之后，对route resolver已确定下载的非Ready初始物理页调用原core.settleViewport(recordProgress=false)，通过既有scheduler准备encoded内容。下载权威来自DOWNLOAD/LOCAL_DIRECTORY/LOCAL_ARCHIVE/LOCAL_EPUB路由，不猜sourceId。初始页解析与UI共用helper，真实viewport随后接用；Ready目录页跳过、在线负控不提前HTTP、并发及forward4/back1预算不变。不提前lease/解码/记已读，不改图像质量、配对、内容身份或进度确认。

真实生成187页PNG CBZ的factory RED中，恢复页4在没有Compose viewport时仍Queued；GREEN为Ready、归档copy一次、DECODE/FIRST与进度tracker调用为0，随后两个可见消费者共用一次开流/解码。该行为夹具不是正式性能测试的史莱姆WebP原件。另覆盖末页/负值/越界恢复、在线不提前请求、不配合取消的旧CBZ在切章后实际退出而不污染新状态、资源释放与既有可见消费者复用。没有新增整章预热或第二套调度器。

主代理独立核对代码、真实core/runner接线和XML一次：core的无进度调度不更新呈现snapshot或进度；runner.pollNext释放runner锁后才进入session。审查中纠正了一项描述：applySchedulePlanLocked里的runner.cancel即使空集合也会pump，不能称所有pump均在session锁外。沿用原锁链，无新锁顺序重构。冻结10文件与E1包的源码一致。

定向验证均经同worktree Gradle协调器串行执行，Windows UTF-8/无窗口；未跑模块全量或仓库全量：

- 点击锚点14/14 GREEN，24秒，`:app-desktop:jvmTest --offline --tests '*MangaDetailInteractionTest.normal chapter callback exports*' --tests '*ReaderTestModeControllerTest' --tests '*ReaderStartupObservationTest' --console=plain`。原件sol/click-anchor-focused-{ui,controller,observation}.xml及desktop-d5-click-anchor-focused.{json,log}。真实接线RED另存click-anchor-red.xml和behavior-red日志；之前缺少import的编译失败不冒充行为RED。
- 候选及受影响接线9/9 GREEN，27秒。RuntimeFactory精确选择器为 `local archive prepares the restored*`、`local initial preparation*`、`initial archive preparation*`、`selected cold native viewport*`、`production visible consumers share*`；另为 `ReaderStartupObservationTest.natural mounted production*`、`DesktopReaderSessionIntegrationTest.same chapter reactivation releases*`、`non cooperative page cancellation*`、`cancelled noncurrent gate*`。完整命令/环境/退出码在sol/desktop-d5-initial-focused.{json,log}，XML为initial-focused-{factory,session,observation}.xml，RED/GREEN为initial-materialize-{red,green}.xml。
- E0、E1两个preview的build/sourceIntegrity/productionRuntime均PASS；git diff --check通过，Desktop未配置Spotless任务，不声称执行了不存在的格式任务。D4已有两项快速双页点击失败不扩修，也不声称完整模块通过。
- E1撤回后，6个保留测量文件逐字节匹配E0冻结记录，4个纯性能文件回到HEAD的Git内容；产品输入指纹回到E0。复用已通过的E0 14项测试及实际构建/原生结果，不因提交再运行同样Gradle。候选9项及patch保留ignored实验原件，不把已撤回行为的GREEN当作最终加载策略的验收。

E1为preview20261106T032635Z-8f0a493e，productSource d35d64b65106b8589a83b657a12f01f21bad064482d36eadda697545026b12c1；app tree 630cad69263ae9295b2e8356d0fab4a036e48868d4dfb11a805a4a37f3e40c27，387文件/275,127,877字节。源码为86c5628加冻结差异，不称从最终提交构建。[已撤回候选EXE，仅作复现实验](D:/Codex/worktrees/mihon-desktop-reader-first-open-20261105/app-desktop/artifacts/preview/windows/20261106T032635Z-8f0a493e/Mihon-Desktop-0.11.24.6.86c5628-unpacked/Mihon%20Desktop.exe)。可选CDS未生成警告保留，不扩修。两个包构建manifest的nativeInteraction NOT_RUN不改写，实际原生验收单列如下。

## 正式同仪器对照

共15次尝试，14份有效样本、22个页面端点、1份技术失效。单页先E0/E1各first/warm，初步有方向后反向先E1再E0补fresh first；关闭匹配先E1后E0，开启匹配先E0后E1，各first/warm。仅因明确捕获失效补一份E0双页关闭匹配first，不因慢而替换。fresh JVM不等于磁盘冷态，也没有充分随机化或足够样本估计P95。

同SHA史莱姆04卷，源0/manga906001/chapter906004，初始index4，RTL及其余设置相同；每臂隔离profile。先确认首WGC帧READY，再正常章行点击；完整正确漫画后才GET，不在首图前争锁，不同时离线读图。正式细观测OFF，两臂相同点击锚点与productionEvents。根逐样本核state、唯一latest OPEN/点击、模式/有序页对、原WGC QPC、连续frame及PNG，独立复算全部22页F/K边界。检查14份R的前帧/命中原图，均从原详情背景变为“正在加载页面…”加旋转指示；另核全部8份first的F边界原图，命中正确地图/完整页对，部分E1 ON先出地图再出另一页。正常恢复与三模式候选往返翻页成立：single[4]→[5]→[4]、OFF[4,3]→[6,5]→[4,3]、ON warm[6,5]→[7,6]→[6,5]。

下表单位ms。R是捕获到可辨载入反馈，F是正确漫画纹理；双页F为两页齐全，各页分列于[22行CSV](desktop-reader-d5-2026-11-06.csv)。回调与工具输入是不同起点；K独立于起点，页码为0基index。端点区间保留相邻原帧间隙，K是按采样界限估计，不能保证长间隙内连续黑或连续显示。

|样本|R回调|F完整单元回调|F完整单元工具输入|各页K首图前累计|
|---|---:|---:|---:|---|
|E0-single/first|34.545–117.878|342.878–359.546|345.877–460.992|4:225.001–325.001|
|E0-single/warm|28.000–44.667|94.668–111.335|96.182–205.735|4:50.001–83.334|
|E1-single/first|33.589–116.921|308.586–325.253|311.816–429.886|4:191.665–291.664|
|E1-single/warm|27.134–43.800|93.802–110.471|95.258–208.159|4:50.002–83.337|
|E1-single-reverse/first|41.311–124.644|332.978–349.645|336.351–451.428|4:208.334–308.334|
|E0-single-reverse/first|33.783–117.117|308.786–342.120|311.903–453.614|4:191.669–308.337|
|E1-dual-off/first|33.015–124.717|583.022–633.041|586.017–754.008|4:458.305–600.025；3:458.305–600.025|
|E1-dual-off/warm|15.169–40.169|106.841–131.844|109.709–235.389|4:66.671–116.675；3:66.671–116.675|
|E0-dual-off-recovery/first|32.781–116.111|482.775–532.775|485.823–634.318|4:366.664–499.995；3:366.664–499.995|
|E0-dual-off/warm|26.658–43.325|109.999–143.333|111.855–247.666|3:50.005–83.341；4:66.675–116.675|
|E0-dual-on/first|34.785–118.117|576.451–651.451|579.276–759.802|4:458.334–616.666；5:458.334–616.666|
|E0-dual-on/warm|17.658–42.658|92.661–125.996|94.706–229.928|6:50.004–108.338；5:50.004–108.338|
|E1-dual-on/first|33.760–117.092|458.762–508.761|461.821–616.470|4:258.337–425.002；5:341.670–475.002|
|E1-dual-on/warm|42.713–59.379|117.714–159.381|119.852–804.534|6:58.335–116.668；5:58.335–116.668|

原件在actual/<样本>/input.json、callback-anchor.json、state.json及wgc/{events.jsonl,manifest.json,PNG}；Luna的d5-analysis.json含完整每页K分段/最长/累计、再黑、ROI及gap，d5-final-compact.json含输入包络/clock映射与对照。根复算root-audit.json和代表原图联系表root-visual保留。22页首见后未采到再黑，Kafter累计为0；这只覆盖已录时间。首图前本轮各页均一个近黑段，故累计与最长相同。

所有有效样本保留PARTIAL/native-capture-exit:1主动停止告警，received==written、missing0且局部输入到稳定图覆盖成立。最大未观测间隙为E1 OFF first241.582ms、E1 ON first183.336ms、E0 ON first158.333ms；不人为切断黑段，不把缺测当已显示。WGC窗口合成帧不是物理扫描。

时钟六次往返交集宽34.3–64.3微秒；包含映射不确定度的工具输入边界总宽92.886–643.015ms，sky调用自身92.852–642.978ms。callback比输入上界晚1.455–3.373ms，所有正常锚点位置成立。最后E1 ON warm宽642.978ms使工具起点F上界804.534ms，不能把这个上界当实际等待，更不能替换成典型100ms。新callback主表排除了输入前段，不用于证明整体R≤100ms或抵扣官方差距。

失效原件E0-dual-off/first：90秒录制上限先到，末帧QPC230697577785600，实际输入下界230714712519500，间隔17.134734秒；5187帧引用同一旧PNG。虽missing0，不能提供输入后F/R/K。保留于Luna/invalid-samples.json；以fresh E0-dual-off-recovery/first替代，原warm仍使用真实E0 warm。此前QPC helper两次空闲超时也保留日志，均在失败校准后未派发输入；不能声称所有辅助进程都是exit0。

## 为什么撤回、还剩什么

正向单页first的可辨F改善区间为17.625–50.960ms，两个端点各约34.3ms；不能只报这一对。反向first候选端点比控制晚7.525–24.192ms，整体区间重叠，不重现改善。单页warm区间重叠，约0.86ms端点差不能当可辨收益。

OFF first候选两个端点晚约100.25ms，保守可辨退化至少50.247ms；warm完整页对区间重叠且两侧方向不一。ON first候选两个端点提前约117.7–142.7ms，warm端点反而晚约25.1–33.4ms且区间仍有重叠。按预先写明的跨次序首开收益及无退化门槛，不采用E1，不通过限定模式或忽略不利样本挽留候选。

内部数据支持首轮目标依赖确实改变：单页正向callback→PAGE_LIST_READY为108.239/107.609ms，→OPEN_PAGE为262.999/225.022ms，→FIRST为324.846/288.596ms，收益在页表之后；反向的页表已经不同（E0 101.495、E1 132.292ms），→FIRST为299.329/312.553ms。早发工作可能与后续初始化/双页工作争用，但本轮没有因果隔离，不能将OFF退化确诊为锁竞争、JIT或磁盘。可解除依赖不等于提前触发一定更快，后续须重新提出有新证据的候选，不能照搬E1。

最终撤回初始准备调度、共享resolver及其3项新行为测试，只保留E0测量6文件及其14项GREEN。D3 ZIP与D4无进展修复保留。整体近黑≤60ms仍明显未达标，输入响应≤100ms也未由当前外部边界证明；官方Android不同硬件/采集起点的数据仍只作体验目标，不直接减出跨平台“慢多少”。本轮没有新的稳定毫秒收益，不自动开展第二个候选、扩大测试或创建D6。

## 交付与边界

源码测量接线、计划、报告/CSV及内部文档在同批本地提交，不推送；不跑完整模块/全量，不分配正式版本或宣称发布就绪。原始过程材料限定本树.test-tmp/desktop-first-open-d5-20261106/。全部实例正常退出阅读器、POST shutdown，当前QPC helper发stop；CIM按完整EXE与本轮capture/helper命令确认自有进程无残留。用户窗口/配置保留。构建成功、任务checkoff或候选撤回不代替用户功能验收。
