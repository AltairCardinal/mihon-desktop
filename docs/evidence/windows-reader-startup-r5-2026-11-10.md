# Windows 首开 R5：完整链路诊断实施与采集

日期：2026-11-10。状态：研究已停止并完成文档收口；R5-SH不采用。四份有效普通ON探针未通过整体端到端门槛，故未执行OFF矩阵或双页负控。最终交付以已验收的 `eb58065b5d` 阅读器源码为准，排除R5-SH及R5新增诊断实现；下文的源码/测试/包状态属于历史实验记录。最新结论见[研究收口记录](windows-reader-research-closeout-2026-11-10.md)。执行计划见[唯一R5计划](../roadmap/2026-11-10-windows-reader-startup-r5.md)。

## 基线、职责与产品边界

工作树 `D:/Codex/worktrees/mihon-windows-reader-source-driven-20261109`，分支 `codex/windows-reader-source-driven-20261109`，起点 `eb58065b5d`，初始工作区干净。继续隔离R4研究树，保护主目录其他任务。root负责文档、独立审查和唯一原生输入；Sol medium负责production/test及串行Gradle；Luna medium负责 `.test-tmp/r5/` 的限定工具和数据复算。

现行产品仍等待真实viewport需求，R3解码collector及R4初始encoded需求均已撤回。本轮只增加默认关闭的观测和必要JFR打包能力，不优化调度、缓存、位图、配对、恢复、进度或取消语义。整个流程仍未达到官方体验目标，不将诊断落地算作提速。

## 输入与已确认的采集限制

内容为真实《史莱姆》第04卷，187页，初始index4地图，RTL单页，关闭裁边/拆页/自动匹配。CBZ为41,380,343 bytes，SHA256 `b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677`。seed scaffold来自 `.test-tmp/source-driven-v1/seed`；初版准备误断言seed没有CBZ，实际prepare发现已有同hash原件，现改为存在时校验、缺失时只从已校验R4 B0i1r复制该文件。只清理重做了本任务H1部分副本，不整体复制已运行profile或携带目标encoded缓存。四份新副本的DB、LF marker、相同阅读偏好和 `cache/network/reader-encoded` 空状态均已核验。

旧B0i1r/B0i2记录说明约250ms链路，但不作为本轮插桩后的性能结果。旧 `mechanism.py` 的stage字典会覆盖重复事件；R5提取器保留事件列表，以本次click/open、目标及请求身份选择首条，缺少直接字段时明确离线连接。原生intent细事件可无chapterId，不能按空字段漏掉或误报到点击之前。

R4真实preview runtime/release仅含java.base/desktop/sql等模块，**没有jdk.jfr**。本轮增加JFR模块是实际线程采样的前置，不默认录制；独立无界面实例验证attach/录制/停止，随后才用不同profile和PID采普通阅读器首开。不能把预检实例当冷态测量进程。

本机JDK为21.0.11.10-hotspot。配置 `.test-tmp/r5/root/reader-startup.jfc` 已由该JDK的 `jfr configure` 生成并XML解析核验；启用2ms方法样本、monitor/park/sleep、Java文件IO、类加载、GC/编译和自定义单调时钟锚点。JFR可能改变时序、Java文件事件不涵盖全部原生IO；无采样不代表方法未执行，不能固定扣除采样开销。线程CPU有约15.625ms量化，不能精确分摊短区间。

## RED、实现及focused

`r5-red` 在编译成功后，真实factory/store/Skia/Screen挂载因缺少 `RUNTIME_NORMALIZE_BEGIN` 失败；`r5-native-red` 在编译成功后因真实observed open缺少 `NATIVE_ARCHIVE_FILE_OPEN` 失败。原XML在 `.test-tmp/r5/red/`。首次GREEN有Skia括号编译错误，不计行为RED；下一轮的目标writer误选邻页及JFR测试字段未冻结属于测试问题，已保留失败日志并纠正。

`r5-green-final` 退出0，共22项：Observation4、JFR1、native观察1、Skia14、真实首屏2，原XML已保存在 `.test-tmp/r5/green-final/`。审查修复先运行 `r5-review-red`，真实失败为缺少TOKEN_READY及native成功/拒绝detail；随后 `r5-review-green` 退出0，Observation4/JFR1/native2共7项全过，XML在 `.test-tmp/r5/review-green/`。未受修复影响的Skia14和首屏2复用前轮有效结果，合计23个不同方法的组合证据，不声称最终代码重新跑过完整模块。

格式仅对本次文件核查，新违规0；HEAD原有未变行问题按精确文本映射保留，见 `.test-tmp/r5/review-format-review.json`。`git diff --check`通过。没有运行完整模块、全仓库、Android或正式发布矩阵。

## 审查及观测语义

root完成一轮独立审查及一次修复复审。已修复两处诊断问题：runtime总开始标记原先晚于token预留，现先记录outer BEGIN再记录TOKEN_READY；原生异常路径现先保留pending Java异常再返回，不再继续写JNI数组，且拒绝/成功以detail=0/1区分。未涉及诊断的Commons/SevenZip格式噪声已恢复，不改生产算法。

OFF无新增观测时钟/事件/字符串/ThreadLocal访问或帧job；同步解码上下文只包非suspend调用并finally恢复嵌套值。普通native open与observed共享同一算法，真实metadata及拒绝/IOException/错误参数语义已验证。layout每mounted node首次记录，draw最多4次尝试；连续prepare拒绝超出预算时可能没有新增成功drawEnd，原FIRST仍保留，不能据标记缺席判断未绘制。JFR锚点携带source时间及begin前后Java单调时钟包络，不假称零误差或物理present。

## Preview与预检

`r5-preview` 通过项目production Preview脚本串行执行，43秒、退出0；跳过测试和正式版本分配。build/sourceIntegrity/productionRuntime均PASS，构建manifest的nativeInteraction保留NOT_RUN；实际原生结果另记，不回改manifest。CDS因既有Windows启动命令行长度问题跳过，未重建或声称CDS通过。

历史诊断EXE当时已核存在，收口时不保留实验包；[归档manifest](D:/Codex/archives/mihon/windows-reader-source-driven-20261109/preview-manifests/20261110T001304Z-85479fd3.json)记录来源为 `eb58065b5da54365330c6c1fcc5eed66d6a69702` 加本轮dirty改动。生产digest为 `0f692a7498c93217d04b6a6b6e303f51fdb13adb851e1637999eb5a4abfef7de`；完整应用397文件、277,549,613 bytes，摘要 `d717c9b3be9739a6f19df5cb0763b8091f933f26f62081e8384d55a445864d5b`，实施者复算一致。它是本地诊断预览，不冒充主干/最终提交构建或发布。

runtime/release已实际确认包含jdk.jfr。独立H1（HTTP49286、launcher58456/runtime61020）实际JFR.start/stop成功，生成约134kB、1秒的 `.test-tmp/r5/H1/preflight.jfr`；summary含355条NativeMethodSample、87条Compilation及2条ClassLoad。这只验证包内采样能力，不是阅读性能样本。

H1 POST shutdown后超过helper原20秒等待，随后只读核对两PID均已自然退出、jcmd报告no such process；未强杀或再次发送shutdown。记录在H1/process.json。后续helper先落盘shutdown响应，再允许45秒自然退出，超时仍保留原件并精确核实，不误报成功或重启进程。原生前不再修改production/test或重建包。

## 原生批次

已完成普通ON两份O1/O2、ON+JFR一份J1，均新profile/新进程，root逐次正常书架→详情→章行并目视确认第5/187页地图。三份真实窗口均3888/1344/1024/768、完全位于本次副屏。无WGC，样本只作调用归因，不与官方或历史OFF样本相减计算净收益。

四份profile（含H1）完整应用摘要均重新校验一致；O1/O2/J1的HTTP/JMX为49280/81、49282/83、49284/85，准备与启动均重核端口。新库DB摘要 `83fdf1bf4c875d84862a8a107a2e18b5213f8c417241a558908a4b428bca3d90`，偏好摘要 `fad10e5eeb0b8553c1573e1c41581fdc4b28813be12b33a4c1955513da7d3fc5`；窗口3888/1344/1024/768已逐次核验。对应显示元数据为主屏3840×2160/150%，副屏(3840,1296,1920,1080)/100%；是否可截图及目标漫画正确另有本批实际观察，不单靠元数据判断。

R5提取器已对R4旧原件复算，primary click→draw为254.492/252.612ms，fine intent为44.143/49.275ms，且保留晚数微秒的fine FIRST；输出仅写R5/root/replay-B0i*.json，旧原件未改。该复算验证提取语义，不算本轮新测量。

项目desktop-acceptance要求原生操作后若需深入分析、修改或构建，精确关停并随即final结束本轮。因此采集结束只落盘简短结果；JFR深入关联、候选选择及实现保持后续任务，不把它们标为本轮完成。

## 收尾及后续裁决

本轮不推送、不发布、不更新公共能力说明。原件留在本机 `.test-tmp/r5/`，研究语义及必要验证记录随本报告提交。后续先基于新数据解释入口、原生open、解码附加工作和三个界面交接区间，再只选择一个有证据支持的候选；不能原样重启已撤回R3/R4或预填5–15ms/100ms收益。

## 本批即时核验与收口

下表为探针内应用draw端点，非外部可见或物理present，单位ms；原生四段依次为文件/ZIP初始化/枚举/指纹，其和不是完整open。

| 样本 | 仪器 | 点击至应用draw | runtime | 原生四段 | decoder | 原图采样 | 输出位图 |
| --- | --- | ---: | ---: | --- | ---: | ---: | ---: |
| O1 | 普通ON | 181.281 | 21.636 | 0.172/0.042/2.502/0.056 | 28.766 | 2.717 | 3.877 |
| O2 | 普通ON | 173.028 | 20.202 | 0.107/0.036/2.427/0.083 | 26.321 | 2.559 | 3.434 |
| J1 | ON+JFR | 665.284 | 153.124 | 0.216/0.047/5.851/0.061 | 33.003 | 3.911 | 3.817 |

三份细观测分别457/461/466事件，dropped均0；原始state、完整JFR、锚点和进程/副屏记录保存在 `.test-tmp/r5/{O1,O2,J1}`。J1录制33秒、约2.1MB，含2个ReaderStartupAnchor、13个ExecutionSample、2174个NativeMethodSample、2787个ThreadPark、26个JavaMonitorEnter和1221个ClassLoad；这些计数是整个录制窗口，不是点击到首绘窗口。两锚点字段及begin包络已验证可读。

JFR组665.284ms明显偏离普通ON的181.281/173.028ms，提示较大采样扰动；不是同进程随机对照，不能把差值固定扣除、用其比例外推普通运行或据此宣布提速。新数据仅初步量化原图采样约2.6–2.7ms及显示输出约3.4–3.9ms，余下链路需后续离线关联。便捷timeline摘要漏掉READY_PUBLISH_END，但raw中保留了目标事件；完整分析须以raw按章节代/runner代及内容身份连接，不能把摘要缺席当未执行。

采集后均Escape返回详情保存第5页，再POST shutdown；O1 launcher58484/runtime15088、O2 57264/29600、J1 15348/34320均已确认自然退出。JFR已显式停止；无自建WGC或QPC进程，未操作另一路径的用户Mihon实例。研究不包含完整双页/Android/物理屏幕验收。

R5.0–3完成；R5.4–5保持未完成。按桌面归还规则本轮到此结束，不继续长时间分析或构建。下一轮先检查JFR扰动和摘要代次过滤，再解释普通ON的关键路径，只有证据成立才选择一个候选。提交只含本任务23个代码/测试/内部文档文件，不推送。

## R5.4：离线复算与唯一候选裁决

本节为用户恢复工作后的最新进展；前节是采集结束时状态。原始state/JFR保持不变，无新GUI或采样。新输出 `state.r5-timeline-v2.json` 保留stage重复、原字段及请求桥。旧提取器的主窗口过滤错误地要求event generation等于OPEN章节代1，丢掉runner代2；随后修复中又一度把仅带openingToken的UI demand误要求为完整runner请求三元组，已纠正。不是事件丢失或循环覆盖end。普通ON均以token1、request0、attempt0及同一encoded URI连接UI代1和runner代2，不能将两代数值混同。

以下六段从正常detailChapterClick到production FIRST，单位ms，连续不重叠。页表端点是实际PAGE_LIST_READY，不冒充每个发布函数返回。

| 连续区间 | O1 | O2 |
| --- | ---: | ---: |
| 点击→页表ready | 41.999 | 39.848 |
| 页表ready→首个UI需求 | 41.032 | 41.572 |
| UI需求→encoded Ready发布结束 | 15.437 | 15.631 |
| encoded Ready→decoder开始 | 21.602 | 25.643 |
| decoder | 28.766 | 26.321 |
| decoder结束→应用draw确认 | 32.445 | 24.013 |
| 合计 | 181.281 | 173.028 |

runtime总段21.636/20.202ms，最大子段为session-start4.877/3.986、pipeline3.765/3.446、session构造3.172/3.169ms；不能将子段与总段再相加。Kotlin archive open3.562/3.307、snapshot0.382/0.348、descriptors1.632/1.664ms；原生四段见前表。decoder内raster16.450/15.811、原图采样2.717/2.559、输出3.877/3.434ms，其余包括peek、动画检查、codec、分配和asset。holder lease wait38.370/33.626ms及pipeline await32.086/29.088ms包住解码和取图，是重叠区间，不是额外可回收等待。Windows粗线程CPU差不用于细分CPU/休眠。

### JFR能支持什么

使用当前JDK的streaming RecordingFile consumer读取2.1MB原件，不整体输出巨大JSON。一次性过程源码在 `.test-tmp/r5/analysis/JfrWindow.java`，结果为 `analysis/jfr-window/{clock,summary,events}.tsv`。两锚点用JFR startTime减beginAfter/beginBefore构造offset包络，交集宽500ns。关键窗口为fine CHAPTER_CLICK至fine FIRST共665.2626ms；与production表665.284ms有输入/记录边界的微秒差异，不混作相同端点。

关键窗ExecutionSample为0，48个NativeMethodSample均没有AWT36或目标decode109样本。样本只计数，瞬时事件不累计500ns时钟包络作执行时长。AWT36有515个ClassLoad，逐事件wall上界和392.714ms、区间并集约386.941ms，不能当排他CPU、静态初始化或对应constructor耗时。AWT文件读取累计仅6.306ms；无达到本配置阈值的AWT JavaMonitorEnter，9个ThreadPark约70.904ms的栈为EventQueue正常等事件，不能据此排除其他未覆盖锁或断言reader锁争用。其他worker的park不可加到AWT关键路径。

GC关键窗pause合计约7.518ms，而GC cycle合计26.796ms包含并发工作；ClassLoad区间亦与GC/编译重叠。J1的大量真实reader/Compose类加载和编译与膨胀重叠，但现有分组不能证明其中多少由采样本身导致，禁止固定扣除。202ms的ReaderScreenModel类加载不代表构造：真正COMPOSE_MODEL_CREATE为677.784ms，晚于FIRST；O1/O2也在首绘之后才创建完整model。JFR不足以定位普通运行函数CPU热点，后续候选以普通ON的可重复结构边界为依据。

### 唯一候选R5-SH及边界

普通ON holder Ready为152.478/152.407ms；随后Ready组合166.331/158.236，DIRECT_RETAIN只0.083/0.065ms，但apply直到178.862/170.733，再layout/draw。约18–27ms与Ready后新建Image/菜单及应用、布局相关；不是异步transform或等待完整模型，也不将这段全部视为某一个菜单函数CPU。R3只把Ready到decode接续，仍保留本段；R4只提前encoded准备。因此本次不重启旧collector/需求方案，选择新的decode后结构依赖。

R5-SH在现有轻入口的Queued阶段挂好固定静态图像host，Ready像素通过同holder状态触发draw失效，复用原asset lease、几何及prepare/ack；不提前需求/解码，不新建缓存或资源权威。准入与真实RED/GREEN、早期输入/关闭/替换/handoff合同详见计划R5.4子节。候选已委派Sol实施，机制RED/GREEN成立，窗口外测尚未进行；不能据内部跨度宣布收益，不能绕过未知结果采用。

### 机制测试的夹具修正

首个真实RED在生产Screen/runtime/session/store/Skia接线上取得：decode已Ready、尚无Ready apply，旧树单独draw仍为黑色。候选初次测试同样黑色，后续有界诊断确认是同一个已布局640×480节点；不能用第二次attach晚于首次layout事件就断言未布局，因为layout事件只记录节点首次回调。

当前Compose Desktop 1.10.2的ImageComposeScene有独立的effectDispatcher及snapshot失效CommandList。测试协程的runCurrent与Snapshot.sendApplyNotifications不足以派发这两类Scene任务；直接root LayoutNode.draw会复用旧graphicsLayer，而不是重新执行已失效节点。测试现仅派发真实Scene的performScheduledEffects与sendAndPerformSnapshotChanges，再draw原树；不执行performScheduledRecomposerTasks、render、measure或layout，也不手工invalidate节点。该环境下候选已画出正确BLUE，且Ready composition/apply尚未发生；真实ReaderIoEvent FIRST一次与未创建完整model断言通过。仍须相同环境的最终baseline RED、受影响行为回归及窗口OFF对照，不将修正测试环境算作产品提速。

相同Scene派发条件下的最终对照已完成：`r5-sh-ui-dispatch-baseline-red`编译成功、实际BLUE断言得到BLACK；候选`r5-sh-mechanism-green`通过。临时baseline仅对Sol独占的7个production文件保存字节、切换及finally恢复，每文件candidate SHA复核一致，不涉及root文档或其他任务。XML在 `.test-tmp/r5/sh-baseline-proof/{baseline-red,candidate-green}.xml`。

root独审确认了首图后早期触摸的真实再黑风险：`r5-sh-input-red`经原Touch press复现BLUE→BLACK，且没有Ready apply。最小修复是在原fallback接管前继续绘制已显示、仍属于当前同asset的像素；不是允许活动输入下开始显示新asset。必要复审又发现retain不等于实际绘出：`r5-sh-prepare-guard-red`用真实native presenter先拒绝prepare、再触摸并放行prepare，复现错误首绘。现以独立presentedAsset只在实际draw及samePixels验证后建立事实，release/换asset清空；旧章/旧代仍经原owner拒绝。该两处候选问题已有真实RED，最终GREEN与外测结果继续记于下节。

本候选跨7个production文件与真实接线测试、内部文档，累计超过400行；它们分别传递同一轻入口资格/State、挂载一个绘制节点及稳定现有菜单，属于同一不可分交接机制。未改变owner协议、配对、解码或Android。额外风险限定为节点生命周期、输入连续性及像素/进度确认，按上述真实RED与focused验证覆盖；不据文件数扩大成模块/全仓库测试。

### 最终focused与测试边界

最终 `r5-sh-final-green` 17秒、9/9通过且无跳过。与此前 `r5-sh-focused-final` 中未受后续guard修复影响的26项组合，合计35个不同方法：SH9、FirstComposition23、owner关闭后的render lease1、同身份替换1及真实菜单接线1。SH包含真实CBZ双页负控，验证提前像素、拒绝prepare、重复绘制只确认一次、Ready前关闭/换章、实际退出卸载、完整视口同holder交接、早期触摸、菜单及页码语义。两处候选问题已修复；未运行完整模块、全仓库、Android或正式发布矩阵。

关闭后的旧夹具失败保留：`r5-sh-prepare-guard-green`、`r5-sh-scene-context-green`及`r5-sh-owner-draw-green`均为8/9，失败是手工runtime.close但Screen仍挂载后的BLUE像素，holder Closed断言通过。单项 `r5-sh-close-audit`又通过，不能把不一致归因owner未关闭。最终绘制入口改为正常CanvasLayersScene的mainOwner.draw（先由OwnedLayerManager更新dirty display-list，再LayoutNode.draw），并在Scene既有同步snapshot线程上下文派发effect/失效任务，仍不重组或布局。即使如此，孤立关闭runtime但保留旧Screen的display-list像素也不代表真实退出。最终该项改为生产DisposableEffect驱动Screen/Voyager卸载，核持有者关闭、资源最终一次释放和FIRST/settlement不再增长；未为测试新增产品刷新设施。

首版格式命令虽退出0，但Windows Java argfile反斜杠使test glob未命中，不能声称该轮覆盖新测试；实施者已改用正斜杠重新执行，最终格式与构建结果以下节为准。没有忽略新增违规。

最终同owner.draw环境的baseline RED再次成立，原XML在 `.test-tmp/r5/sh-final-baseline-proof/baseline-red.xml`，9项候选GREEN在同目录 `candidate-green-nine.xml`。7个production文件按保存的SHA精确恢复；8个明确Kotlin路径的格式只读检查和git diff --check通过。root完成一轮独审及上述必要修复复审，无剩余阻塞发现。

### C预览与副屏对照准备

`r5-sh-preview`经coordinator及既有生产Preview入口完成，退出0。脚本不提供offline参数，因此未注入全局init脚本，而是按网络规则设置本进程代理、沿现有缓存构建，不刷新依赖。C为`bf5b676b0e32988ed14b5e0f68a50788eabac114`加本候选dirty开发预览；不是主干包或发布。build/sourceIntegrity/productionRuntime PASS，nativeInteraction仍NOT_RUN；CDS与B同因既有命令行长度跳过。

历史C EXE当时存在，收口时不保留实验包；[归档manifest](D:/Codex/archives/mihon/windows-reader-source-driven-20261109/preview-manifests/20261110T103745Z-e1d75491.json)生产digest `8d587dcd6f4d8ae21f1361c4b965fbff85a6bb5da91127d371c254297ee9b489`，完整包SHA `d92e6410a74cb19d9f94211ce73c8b9ac3f77e86c6ea3c7d64c2220bb483b84f`，397文件、277,566,983字节，实施者独立流式复核一致。[构建日志](D:/Codex/archives/mihon/windows-reader-source-driven-20261109/.gradle-coordinator/r5-sh-preview.log)。B固定使用前节20261110T001304Z包及其digest，不以新代码重新包装基线。

Luna已准备B五份和C七份独立profile，未预先launch/读取目标页。B端口49310/12为ON、49314/16/18为OFF；C端口49320/22为ON、49324/26/28为OFF、49330/32为双页关闭/开启匹配。每对端口prepare实际bind通过；CBZ、seed DB、17偏好、LF marker、空encoded缓存及3888/1344/1024/768窗口几何均核对，完整身份在各自process.json。root预检本次主屏3840×2160/150%，副屏(3840,1296,1920,1080)/100%，几何仍适用。实际窗口、画面和输入另验，不能用此元数据冒充通过。

### 副屏SH批次结果

本次只启动SH_Bi1（B，普通ON），launcher23940/runtime10980，HTTP49310；实际窗口3888/1344/1024/768完全在本次副屏。正常书架→详情→Volume04章行后，root目视确认正确World Map。原始callback→应用FIRST为255.0156ms、dropped=0，已保存state-initial及anchors-initial；这一个B样本不是候选收益。

随后再次观察时窗口全黑，补一次观察仍全黑。即时保存state-after-reblack.json与native-anomaly.json：mounted仍为906004/gen1、RTL单页、index4、187页、model/runtime attempt均0且Ready、pairing READY。这是画面与业务状态不一致的观察，原因未知，不能仅凭Ready推断图片已经绘出或判断库/代码/GPU因果。C尚未启动，不能归因为R5-SH，也不能用该B作稳定性能对照。

按预设正确性及桌面归还规则停止本批；未启动其余3个ON、6个OFF进程及双页负控，未启动WGC/QPC/JFR，未替换异常样本。POST shutdown成功，runtime10980与launcher23940均已确认自然退出，未操作其他应用。下一轮先离线核对本样本首图到完整视口交接/绘制状态与日志，确定再黑原因，再决定修复或恢复固定批次。R5.5未完成，候选仍仅在隔离研究分支，不采用、不合入或推送。此次仅本地提交已验证的候选实现、真实测试及研究记录；窗口净收益仍未知。

### R5.5续行：再黑离线核查

两份SH_Bi1状态224,912B，除timestamp（10:41:41.231590100Z / 10:42:51.256342300Z）外完全一致，startupObservation均452事件、dropped=0。Luna输出 `.test-tmp/r5/root/shbi1-identity-timeline.json` 和 `shbi1-reblack-forensics.json`，按原token/request/ref桥保留UI代1与runner代2。production FIRST后12.703ms解锁model、48.570ms构造结束、149.394ms visual handoff；之后仍同holder，181.249ms时目标IMAGE_DRAW_END，再后来PAGER_READY_LOCKED明确返回true。标记预算有限，缺少黑窗口时段的连续绘制/资源观测，不能据记录未增长判断没有绘制。日志仅SLF4J/Netty notice，无Skia/bitmap/decode异常；两份nativeInput均windowShowing/focused/ownerFocused=true。

Sol沿真实GET reader/state入口核查，值读取、counter及窗口坐标序列化没有图片/lease读取或关闭、刷新/动作派发，尚无GET破坏性副作用证据。旧composition lease被forgotten也不足以单独证明底层图片释放，region holder仍持previewLease。100ms延迟为请求焦点；3s教程需fullscreenEligible，本样本windowed不适用。这些事实不证明窗口呈现正常，也不作为已修复结论。

只执行一次有针对性的离屏诊断 `r5-bi1-offscreen-diagnostic`：临时bf5基线，真实005.webp（980×1410）及1008×729 Scene，100次正常render跨full handoff、frame40/80调用真实CIO GET，推进TestDispatcher虚拟4s，目标中心像素持续非黑，单项PASS。Default延迟未被虚拟时间推进；夹具pairingStatus=TEMPORARY、没有真实AWT/GPU/window-nativeInput，不能替代SH_Bi1或证明物理画面无故障。原XML/源码/HTTP/state/恢复SHA在 `.test-tmp/r5/reblack-diagnostic/`，日志 `.gradle-coordinator/r5-bi1-offscreen-diagnostic.log`。7个production文件和test逐SHA恢复，未产生行为RED，未改production；coordinator及自有子进程退出。

下一批仅将尚未启动的SH_Bi2用于B再黑诊断，不作替代性能样本：复用已验证WGC从章行点击前持续捕获，正确地图后先不GET reader/state，记录一段稳定帧，再单次GET并比较后续WGC/原生观察。只有实际再黑时才开启既有draw observation作为一次有记录的干预，区分绘制命令与呈现/捕获缺口；该开关可能重组，不能当作原始时序。结束精确关停即归还桌面，不启动C或OFF矩阵，不追加猜测性修复。

### SH_Bi2真窗诊断：两路捕获不一致

SH_Bi2仍为冻结B、单页原设置，launcher42336/runtime47028，HTTP49312，实际窗口完全位于本次副屏。书架第一次点击未导航，补观察后经一次明确激活窗口再点击才正常进入详情；保留为输入/焦点现象，不据此判断用户接管或产品故障。章行点击前启动既有WGC，root确认正确World Map；首次GET前，独立WGC `capture/wgc/window-003315.png`及Sky均显示完整地图。随后只调用一次reader/state（GET包络114732796542400–114732931310000ns），保存state-initial/anchors-initial及diagnostic-reader-get.json；应用FIRST为234.884ms、dropped=0，此为诊断值，不替换性能样本。

首次GET后Sky返回大片黑区、底部残留约31px漫画条。第一段WGC已到90秒自动结束，没有覆盖GET瞬间，不能按先后顺序证明GET致黑。为区分画面来源，补录同一打开过程的post-GET段，复用helper目录名 `capture-warm`，**没有再次打开章节，不是warm性能样本**。

关键配对在 `diagnostic-wgc-sky-pair.json`：同一次Sky黑块观察前后，独立WGC最新记录均为frame1498、QPC114860940273100、文件 `capture-warm/wgc/window-000001.png`，root实际打开该原件为完整地图。后续frame5374仍指向同文件，见diagnostic-postsky-checkpoint.json。这证实两路捕获结果存在差异；不能仅凭Sky黑块认定阅读器停止绘图，也不能据WGC直接证明物理scanout。未开启draw observation、未触发额外重绘、未改production。

两段分别received=written=5364/5374、missing=0，但manifest均为PARTIAL、native-capture-exit:1，原始状态与stderr保留，不改写PASS。捕获wrapper4616/3612已退出，shutdown成功并确认runtime47028/launcher42336自然退出。无QPC/JFR助手，C/OFF/双页均未运行。下一轮先离线审核捕获原件、失败边界及判据，避免把观察工具黑块当产品回归，再恢复固定B/C批次；本轮未取得候选净收益，也未确认或修复应用再黑bug。

### 捕获审核与恢复条件

Sol核实际后端源码/EXE身份：原生自然deadline可正常返回0；外层90秒停止信号在下一完整帧后触发process.terminate，而传给原生为100秒。两段约90.065/90.040秒、stderr为空，与此停止路径一致，但未直接记录终止分支，不能绝对排除其他退出原因。writer在完整header/pixels读取后才入队，关闭等待PNG线程排空，received=written、missing=0、queue=0支持已接收前缀完整，不保证被终止后的尾帧。尺寸+CRC+完整BGRA字节相等才复用PNG，没有过滤黑帧，也没有Python主动回放旧图；WGC取到新surface不等于应用提交了新画面或物理scanout。

Luna复算原件 `.test-tmp/r5/root/shbi2-wgc-audit.json`：两段frameId连续、QPC严格递增，最大相邻gap49.9986/41.6317ms，所有引用存在。首段12个不同PNG、后段1个，后段PNG SHA与首段稳定地图相同。既有ROI阈值下首次地图frame1102，callback→该帧266.6934ms，随后接收帧没有黑/非Map文件；数值分类不替代人眼。首段结束距GET开始4.5125028秒，GET结束距后段首帧102.9426849秒，空白覆盖区间不能解释为无黑。

修正配对表述：frame1498前后是同一条已落盘记录，且日志默认缓冲，不能当作两份独立、零误差的同期帧。preGET tail年龄2140.976ms，postSky检查时末帧已过去22408.8714ms。完整录制前缀支持WGC所接收的后续画面仍为Map，但不能据此排除SH_Bi1的真实故障或宣布Sky内部原因已定位。审核未发现需要改产品或捕获算法的证据。

本批恢复使用新B SH_Bi3/SH_Bi4（49334/49336）与未运行的C SH_Ci1/Ci2；原SH_Bi1/Bi2诊断保留，不替换性能样本。普通ON关键路径不启WGC，首绘后才用短WGC核目标画面；门槛仍检查全部连续区间和整体callback→draw。只有平均整体前移且相关下游改善未被上游抵消，才继续原OFF十二开及双页负控。

既有wrapper捕获时长只适配到110秒（实际工具上限），帧数保留10000；R4原件/生产代码不变。每次点击前核进程、未终态及最近落盘帧，首图后及时导出并停止，不在录制中长分析。原始PARTIAL保持；仅在实际覆盖click、R/F边界及F后1秒稳定区间、引用完整且无接收丢失时使用该有界前缀，并披露采样间隙。callback与应用draw、WGC可见边界、Sky观察分别报告，QPC输入调用包络不是硬件DOWN送达。未校准采集开销、不作P95或官方绝对差的推算。

### SH_Bi3：确认本次窗口遮挡并停止输入

新B SH_Bi3启动launcher45184/runtime35616、HTTP49334，native window仍(3888,1344,1024,768)，完全在副屏。root正常书架→详情→章行；下一次Sky观察返回其他程序画面，但返回window对象仍为Mihon、id2625564，截图origin(3895,1344)、1010×761。发现内容不符后立即停止输入，未根据该截图继续点击、激活或操作其他程序。

随后只读Win32元数据（当前线程Per-Monitor-V2，WindowFromPoint物理点4400/1739→GetAncestor root，读取PID/rect及foreground）确认：命中PID11556，矩形(3839,1347,1616,939)，foreground为同窗口；覆盖Mihon测试区域，预期目标PID为35616。记录 `.test-tmp/r5/SH_Bi3/diagnostic-capture-mismatch.json`。这是本次明确的遮挡证据，解释当前截图含其他程序像素；不是将SH_Bi1历史再黑原因追溯确证，也不证明Sky内部具体实现。

不再做原生动作，保存state-after-mismatch.json及diagnostic-exclusion.json；Mihon仍isOpen、目标906004/gen1/index4/187页、Ready并有FIRST，但没有有效目标画面核验，所以本次排除于机制/性能门槛。未启WGC、QPC、JFR，未启动C或后续矩阵。shutdown成功，runtime35616/launcher45184自然退出。已异步询问用户当时使用的显示区域，以区别主动操作和其他程序自动覆盖；不以该问题替代已记录的窗口几何事实。

恢复要求是副屏测试区域保持无遮挡，逐动作核对目标身份和画面来源；若截图标签与内容矛盾，停止输入，不能盲点或操作遮挡程序。SH_Bi3原件保留，不作为慢样本删除或被有效样本替换。SH_Bi4及两C仍未运行，固定四个有效样本及后续OFF门槛尚未完成。产品代码未改，无新的测试/构建，不宣称修复或取得净收益。

### 用户恢复副屏后的固定批次

用户已确认上一轮也使用了副屏，并明确允许现在继续操作；不再把当次遮挡作为尚待确认事实。新有效机制批次为SH_Bi4（B/49336）、SH_Ci1（C/49320）、SH_Bi5（B/49338）、SH_Ci2（C/49322）。B5新建干净profile，其他均核未启动、encoded空、原包与设置一致；旧诊断不替换或覆盖。产品/预览包不变，35项有效focused继续适用，不重跑测试。

本批内部FIRST后才启短WGC核正确地图，不能据该短录帧计算外部首图或采集扰动。通过既定均值与上下游门槛才继续原六进程OFF冷暖矩阵，WGC在点击前启动；未通过则停止，不继续无收益候选的外测。实际采集及清理结果记于下节。

### 四份有效ON门槛结果：FAIL

按B4→Ci1→B5→Ci2正常书架/详情/章行操作，四份新进程均实际完全位于副屏，目标均为906004/gen1/index4、同CBZ及原设置。root逐份确认正确World Map，并在内部FIRST之后启动短WGC再核原件，未发生本轮遮挡。原始state-initial、anchors-initial、placement、capture及process在各自目录；没有覆盖旧异常或换掉慢样本。

| 样本 | callback→应用FIRST(ms) | holder Ready→应用FIRST(ms) |
| --- | ---: | ---: |
| SH_Bi4 | 182.1453 | 19.1411 |
| SH_Ci1 | 212.9882 | 4.5841 |
| SH_Bi5 | 173.2486 | 18.5517 |
| SH_Ci2 | 197.6350 | 7.2836 |
| B均值 | 177.69695 | 18.84640 |
| C均值 | 205.31160 | 5.93385 |

候选局部均值改善12.91255ms，但整体均值多27.61465ms（本批+15.54%），即holder Ready以前的跨度均值多约40.5272ms，局部收益被完全抵消。四份dropped=0、目标身份桥一致；B无STATIC_HOST_ATTACH，C各2条，C的ReadyApply均仍发生于首draw之前。这里只能确认本批结果和局部边界，不把额外上游时间全归因于某个新增函数、固定成本或统计显著的总体回归。

root即时汇总 `.test-tmp/r5/root/sh-gate-instant-summary.json` 与Sol一次提取 `.test-tmp/r5/sh-on-gate-summary.json` 一致；六连续段及原字段在各 `state-initial.r5-timeline.json`。预先设定的整体均值门槛未通过，因此停止OFF十二开及双页负控；没有无探针净收益、外部R/F/K、官方绝对差或P95结论，不能把本表当作物理可见延迟。

四份WGC只核FIRST后的正确像素：received=written分别933/937/907/2678、missing=0，主动停止后的manifest均保留PARTIAL/native-exit1。它们没有覆盖打开关键区间，不用于外部端点或采集开销计算。对应launcher/runtime为24436/20944、50780/48272、19840/45404、49204/55784，capture wrapper为9000/45564/55184/45272，均已精确关停/确认退出，无QPC/JFR进程。Ci1初次verify早于监听就绪，出现编码/端口查询错误；未重启实例，在首次打开章节前复核同一进程成功，保留这一工具前置失败，不计产品失败。

本轮不采用R5-SH，不合入/推送，不扩大测试或另开候选。用户已验收体验包当前性能OK，并要求停止研究。R5-SH原型仍位于研究HEAD `a263490865`；最终交付不得包含该原型及其专用试验路径，直到root核实撤回并补充最终整合SHA前，不声称已清除。过程原始数据保留 `.test-tmp`，不在本次清理。结论、限制及已验收包身份见[研究收口记录](windows-reader-research-closeout-2026-11-10.md)。

## 用户验收后的停止与交付边界（2026-11-11）

用户已确认保留版本性能OK，停止研究。主干整合选择 `eb58065b5da54365330c6c1fcc5eed66d6a69702`，不导入 `bf5b676b0e` 的R5新增诊断/JFR打包或 `de10d213e7` 的SH产品/专用测试；相对该已验收快照，最终阅读器production内容逐字节一致。原始 `.test-tmp` 与 `.gradle-coordinator` 已原样移动至 `D:/Codex/archives/mihon/windows-reader-source-driven-20261109/` 下同名目录，旧绝对路径按此前缀映射查找；四份原始state及汇总/构建日志存在性和SHA记录在归档的 `archive-checks.json`。未重测性能，不把合入收口解释为新收益或追平官方。完整交付与后续研究条件见[收口记录](windows-reader-research-closeout-2026-11-10.md)。
