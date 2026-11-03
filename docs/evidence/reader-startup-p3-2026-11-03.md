# Android 阅读器首开 P3：重复通知成本与真机绘制门槛

日期：2026-11-03。入口：[P3计划](../roadmap/2026-11-03-reader-startup-p3-plan.md)。起点`a1a66067c04db70d96646983b27629564fc6bb3b`；保留P0/P1/P2。**本批完成：消除首次通知的重复整章检查，修复普通单页往返黑屏；最终三次近黑87–115/34–67/70–99ms，仍未稳定达到≤60ms。**

本轮按用户指定由Sol medium实现、测试、构建和唯一设备操作，Luna medium读取/离线核算，主代理定范围、独立审查并核对关键原件。没有运行整模块/全量、Windows或模拟器矩阵，未修改官方APK/数据，未推送或正式发布。

## 1. 已证实的额外工作

普通单页`PagerViewer.bindSingleFacts`先通过`ensureSingleOwner`摄入章节快照，再为每页启动StateFlow监听。每个初始通知又调用`ensureSingleOwner→DualPagePairingStore.ensure`，后者重新采样并比较整章页面；同一章有N个初始回调时，存在N轮整章检查。187页真机章节中实际观察到187次初始通知，不只是源码复杂度推断。

P3第一组归因控制为实际`cmdline/speed`：首次/暖态的singleFact回调inclusive时间12.464/19.380ms，内部ensure检查11.505/17.027ms。父子时间有包含关系，不相加。首次187个通知都在目标holder初始化标记前完成；暖态19个通知落在holder→load窗口。因此不能把该机制倒填成P2首次54.709ms的完整原因。

后面的同`install/verify`性能组中，页表→目标header窗口仍各有187次通知，但singleFact合计由控制的30.062/33.511ms变为候选的0.635/0.715/0.677ms；该窗口所有ReaderP3 section去重后的主线程Running由32.199/34.690ms变为2.708/2.361/2.794ms。实际通知次数没有减少，减少的是首次重复回放里的整章检查。ensure还有首次建owner、选页、capture等外部调用，不能把ensure总时长当成singleFact的唯一子项；完整inclusive/exclusive表保留在唯一JSON。

同组首次holder→load为12.572ms，其中主线程Running12.241ms；已有命名slice覆盖Running10.799ms。按父子扣重的wall分组包括Compose节点/布局3.710ms、Compose runtime2.330ms、framework布局/绘制3.448ms及其它/本轮section；它们证明具体框架工作发生，但不是某个Kotlin函数的独占CPU。剩余约1.442ms Running无命名slice。不把全部UI工作认定为可删除成本。

本地上游`v0.19.9`标签`7a917968e3bf71c4a665e6655a550877d81ead1d`具有相同的MainScope→状态收集→Ready→图片链，未包含fork这条全页事实订阅。标签名对应既往官方APK版本名，但原APK没有精确sourceCommit映射，不能声称可复现源码逐字相同。upstream后续`c46317f`不是当前HEAD祖先，其解码调度改动不能用来解释本次图片链入口之前的窗口。

## 2. 候选的边界与审查修正

候选只跳过订阅建立时已经被当前owner摄入的首次重复快照：绑定时固定entry与snapshot，第一次回调还必须满足当前entry、chapter/pages/page对象、完整事实/呈现/编码/重试版本、当前事实和owner编码身份一致。后续通知全部走原链，不使用`drop(1)`，不新增持久缓存或全局调度器。

真实R2LPagerViewer+生产store回归先在旧实现确认初始通知重复ensure而RED，再实现。独立审查发现初版动态cache判断可能吞掉“retry→另一个真实ensure先摄入→collector后执行”的重试；新增同方法交错回归真实RED（retry预期2、实际1），收窄到上述固定首次快照后GREEN。覆盖元数据、重试预算、编码替换、首次通知前内容替换、章节窗口切换和destroy取消。

既有实际像素结算方法在两次组合运行中均首次5秒绑定超时、仓库自动retry通过；单独精确方法运行首轮通过（15秒）。未放宽超时，不能声称组合运行全程无失败，也未证实具体夹具/初始化原因。后来新增真实Ready/statusFlow、挂载、DPAD往返夹具，修正测试自身未推进滚动动画后首轮通过；仍未复现真机故障，故不以它替代设备验收。

## 3. 编译条件：请求、实际产物与可比组

归因包SHA256 `72c1a90c826df198714e55a405262ea7205c3c41f467b576bb3d58a30b688ec7`；初始候选 `47edb2b4887fe67ad8de33b536cda308160d4d7a487e60a968ed988c64a0c0eb`。均为原诊断证书、隔离包`app.mihon.desktop.fork.benchmark`、R8/资源压缩、non-debuggable，保留数据安装。源码输入与冻结diff、签名、badging在各自`app/artifacts/android/diagnostics/reader-open-p3-20261103/`目录保全。

本次失败日志明确记录`HwBackgroundDexOptServiceEx`注册并以`Foreground dex2oat confict`杀掉本次目标dex2oat，随后status0x9和PMS Failure。熄屏复核曾成功（约27.49秒，实际OAT为cmdline/speed），但候选在已熄屏数分钟后仍以相同方式失败。没有足够日志证明另一包的编译竞争或服务触发条件，不能宣称熄屏修复了问题，更不能回填P1旧失败根因。

实际OAT头以独立`compiler-filter`/`compilation-reason`字段为准：失败后是install/verify，不是后面dex2oat-cmdline请求参数中的speed-profile。PMS dexopt空输出不能作为状态通过。原件为`attribution-compile/`、`attribution-compile-screen-off/`、`candidate-compile/`。

为消除实际混杂，停止追加强制编译：重新正常安装同一控制APK补一首一暖，再正常安装同一候选APK采首①/暖/新进程首②。采样前后均读回install/verify，未增加构建或第二性能方案。前面的speed组只用于机制归因，不混入下面对照，不与历史P0/P1/P2速度直接相减。

## 4. 同install/verify的初始候选结果（正确性未通过）

设备PCE-W30/API31，史莱姆第04卷原SAF、同CBZ SHA `b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677`，合成入口manga/chapter1/1、page0（1/187）；单页RTL、横图自动放大OFF、hint OFF、黑背景和原动画。每次同采MKV、Perfetto与probe。有效慢样本全部保留。

最初`p3-control-verify-ui`在Main焦点检查阶段因短暂GrantPermissionsActivity中止，未进入Reader、不是有效打开样本；没有盲授权限。确认Main正常后使用新label，候选采取同样Main-only前置，不预热Reader。

单位ms；原四段始于ViewModel初始化，不是点击。

| 样本 | 初始化→页表 | 页表→OPEN_PAGE | OPEN_PAGE→DECODE_SUBMIT | decode→FIRST_DRAW | 总计 | P / H / Q |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 控制首次 | 142.376 | 91.723 | 14.235 | 75.854 | 324.188 | 78.247 / 6.448 / 7.028 |
| 控制暖态 | 132.152 | 83.913 | 2.249 | 84.311 | 302.626 | 70.897 / 6.973 / 6.042 |
| 初始候选首次① | 158.391 | 63.405 | 10.638 | 89.918 | 322.351 | 47.472 / 7.584 / 8.348 |
| 初始候选暖态 | 118.092 | 51.472 | 7.145 | 93.522 | 270.231 | 33.137 / 8.323 / 10.012 |
| 初始候选首次② | 148.876 | 64.751 | 9.124 | 93.469 | 316.220 | 47.803 / 5.110 / 11.838 |

P固定为页表→header BEGIN，H为真实header BEGIN→END，Q为header END→OPEN_PAGE。整数ns已逐样本核对P+H+Q等于第二阶段、四段和等于总计。holder→load与load→Ready仅用完整名称，不另占H/Q。

| 样本 | 原表DOWN→系统StartActivity | →performCreate | →首Reader TX结束 | →映射应用FIRST_DRAW | 近黑下/上界 | ≤60ms |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| 控制首次 | 23.844 | 71.628 | 150.006 | 409.237 | 98–134 | 未达 |
| 控制暖态 | 25.117 | 42.480 | 104.132 | 356.822 | 84–120 | 未达 |
| 初始候选首次① | 27.742 | 50.602 | 130.414 | 390.415 | 99–133 | 未达 |
| 初始候选暖态 | 22.434 | 40.868 | 100.457 | 322.473 | 51–84 | 跨60，未确认 |
| 初始候选首次② | 26.744 | 47.275 | 110.069 | 373.249 | 33–67 | 跨60，未确认 |

外部DOWN是同次trace较早的deliverInputEvent，FIRST_DRAW经同trace BOOTTIME−MONOTONIC快照映射；应用MAIN_TOUCH_DOWN另列在原件，不能同名替换。系统启动取与官方旧表同名的异步`StartActivity:<包名>/<ReaderActivity>`，8份trace各在对应DOWN至performCreate之间唯一命中；不是另存的startActivityInner。首TX均dur有效、Late Present，只是图层端点。MKV归零PTS不能生成DOWN→视频首漫画；物理面板显示也未测量。

近黑仍按最后可辨详情、首近黑、末近黑、首正确漫画四原生帧界定。五组零基帧分别30/31/37/38、34/35/40/41、27/28/34/35、38/39/42/43、30/31/33/34；ffprobe原生PTS与选择表复核一致。主代理另看了代表拼图，不用画面均值替代漫画内容确认。

这一小组显示页表后区间缩短，但首次总计改善很小，暖态总计减少约32.395ms；不能把重复工作的理论预算直接当作用户净收益。初始候选所有近黑样本都未确认≤60ms，且正常阅读失败，故不作为已采用结果。

## 5. 真机黑屏硬门槛与定位

初始候选probe关闭后的正常1→2→1：首页封面正确、第二页正确，返回1/187持续纯黑，额外约30秒仍不恢复，见`normal-flip/`。最小旧控制正常install/verify对照：首页正确、第二页黑屏、返回首页恢复，见`control-normal-flip/`。两版均失败，不能直接归因候选跳过通知，也不能据此放行。

真实生命周期测试没有复现后，只在临时benchmark包增加shell/本UID可用的按需只读snapshot入口及必要字段keep；固定逻辑章节1/1、有焦点Reader、最多3holder、安全label和不覆盖输出。UI线程只读已有字段/票据map，调用线程写JSON；不调用可能改变呈现的frame/prepare/capture，不做热路径日志。该仪器改变了构建输入，耗时不混入性能组，源码/manifest/keep保全于`runtime-state/source/`等目录。

首轮黑页快照确认：selected页Ready，native存在/可见/Ready/Loaded，两个current谓词成立，boundContent匹配，generation不变；scope存在、ticket仍映射、身份/location/layout相符，但locked/drawn均false。排除了“未解码/未绑定”和这些已核身份失效；仍需其它可见性与呈现谓词。

正常双击后未恢复且上述字段不变；没有确认缩放生效时不能把它等同有效重绘干预，也未据此盲加invalidate。

第二轮完整谓词快照在返回首页重现黑屏：native/holder/pager均全屏可见，rect为`[0,0][1920,2880]`，viewportTransform为空；singleMode=true，record visible/appendable=true、stale=false，active/location/layout/identity相符，unit包含当前page0。native源尺寸1960×867、scale约0.97959、center(980,433.5)，current/content条件也成立。locked/drawn此时为true，但可能继承首次显示历史，不能据此证明返回后真的绘制。原件`runtime-predicates-flip/`。这些证据排除了采样时仍在屏外、未解码及已核守卫不满足；该轮快照本身不能证明黑屏当时具体哪次draw或硬件显示列表出了问题，后续采用判断依赖下面的真实回归与真机修复前后对照。

同一shell内连续`cmd input tap`后，黑页的scale/center仍完全未变，故这次也不能当作已识别的双击缩放或有效重绘反证；原件`runtime-fast-doubletap/`，没有继续碰运气发送输入。源码已确认SSIV拒画分支不自行调度重绘，而Pager停稳只上报视口，beginPresentation也只在ticket对象变化时让子view失效。随后仅针对“已就绪子view屏外拒画→真实滚动停稳”建立调度回归；它需要与真机修复前后结果结合，不能单凭代码可能性宣称根因。

该回归真实RED：Ready/statusFlow已创建native子view，内容回调有效；DPAD选页后、滚动开始前，真实可见性守卫拒画。推进真实ViewPager.computeScroll至IDLE并确认已进入视口，子view仍没有收到失效请求。旧实现三次仓库自动retry均失败于这个断言。最小修复仅在IDLE对adapter当前同一非Insert ReaderPage的现存普通PagerPageHolder调用原invalidateImageDraw；不改变ticket、内容身份、守卫或动画。独立审查确认双页holder不命中该分支。

原RED断言随后通过，首次重复快照、真实status往返和IDLE调度三个focused首轮全PASS（`idle-invalidation-green.xml`，3 tests/0 failures/0 errors）。本次曾附加一个直接Canvas绿色像素断言：停稳/可见/已失效都成立，但expected=-16711936、actual=0，原因未证实，原件`idle-invalidation-green-extra-pixel-failure.xml`。最终将调度测试限定到其承诺的失效契约，并补直接调用真实prepareNativeImageDraw确认屏外返回false；没有把这个未解释的模拟画布失败改称产品根因或环境已修复。设备正确像素与往返验收仍是采用门槛。

## 6. 最终候选与真机正确性

最终[诊断APK](../../app/artifacts/android/diagnostics/reader-open-p3-20261103/final/Mihon-Fork-reader-p3-final-benchmark-universal.apk)为69,963,389字节，SHA256 `4d1ea1532076ff4aac9fd3c7de937854934828fbdc7618ce76311470c8f79d6c`，同证书、隔离包、non-debuggable、R8/资源压缩。临时provider/manifest/keep/build配置已全部移除；源码原件保全在`runtime-predicates/final-source-archive/`。来源为起点SHA加冻结diff `fcd312f11569f7a6b763848368b068a2b091818a9d53f50c892e64759e08cf83`，逐文件输入、构建key与签名见同目录`artifact.json`；这是诊断候选，不是正式发布包。

正常保留数据安装后读回实际install/verify，probe关闭，用同样DPAD路径连续三轮1→2→1全部通过。`final-normal-flip3/normal-flip.json`及`contact.png`保存7个画面：首页横向封面、第二页竖图均正确；三次返回首页的漫画区域SHA均与首次相同，三次第二页也彼此相同。主代理独立看过拼图，未仅以亮度均值判断漫画正确。

旧控制/旧候选同条件出现确定黑页，最小IDLE补绘后同一场景三轮消除，加上真实调度RED→GREEN，支持此次共有黑屏由停稳后缺少原生重绘请求造成。**本次单页往返黑屏已修复**；不外推成所有模式、所有黑屏或完整阅读器验收。附加模拟Canvas断言原因仍未证实，不能用真机通过倒填其失败原因。

最终三次仍使用同套P3探针和install/verify，原件为`p3-final-verify-1`、`p3-final-verify-2`、`p3-final-verify-second-1`；唯一汇总为`analysis/p3-final-summary.json`。每份42个事件，主代理从原probe重新计算四段、总计及P/H/Q，均与汇总逐ns相符。

| 最终包样本，ms | 初始化→页表 | 页表→OPEN_PAGE | OPEN_PAGE→解码提交 | 解码提交→首次绘制 | 总计 | P / H / Q |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 新进程首次① | 148.803 | 72.418 | 10.532 | 93.931 | 325.685 | 52.707 / 7.445 / 12.266 |
| 同进程再次 | 120.443 | 40.181 | 10.717 | 73.876 | 245.217 | 31.494 / 3.718 / 4.968 |
| 新进程首次② | 139.202 | 58.408 | 8.439 | 88.225 | 294.274 | 46.437 / 4.503 / 7.468 |

| 最终包样本，ms | 原表DOWN→系统StartActivity | →performCreate | →首Reader TX结束 | →映射应用FIRST_DRAW | 近黑下/上界 | ≤60ms |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| 新进程首次① | 31.846 | 52.898 | 135.257 | 393.238 | 87–115 | 未达 |
| 同进程再次 | 19.259 | 34.834 | 82.110 | 288.102 | 34–67 | 跨60，未确认 |
| 新进程首次② | 28.168 | 48.593 | 112.778 | 354.022 | 70–99 | 未达 |

应用MAIN_TOUCH_DOWN→FIRST_DRAW另为392.619/287.624/351.917ms，不替换上表原表native输入起点。异步StartActivity区间长度为476.718/434.314/457.893ms，不能当成全程黑屏。最终singleFact仍各187次，inclusive仅0.729/0.595/0.929ms；重复整章工作确实被消除，但总首开耗时还受其它阶段影响。

对照第4节同条件控制：最终首次①总计增加1.497ms，暖态减少57.409ms（约19.0%）；首次②为294.274ms，缺第二个匹配控制首次，不能制造成对差值。首次还未证明稳定改善，不把局部约30ms预算、单个暖态改善或不同ART历史值包装为普遍净收益。近黑三个样本仍未全部达到≤60ms，继续保留原目标。

视频判读纠错：初次把仍完整显示详情页的触摸涟漪帧误选成“首近黑”，产生285–315/199–233/251–284ms错误区间。主代理查看四图后拒绝使用，原错误选择留在各`boundary/invalid-touch-boundaries.json`及汇总superseded记录，不作为结果。只从同一原视频补提连续原生帧、没有重采或删慢样本。最终四帧为103/104/109/110、35/36/38/39、36/37/41/42，PTS分别1.790/1.805/1.892/1.905、1.590/1.606/1.640/1.657、1.634/1.648/1.718/1.733秒。主代理亲看三份连续拼图，并与旧五组四图的暗淡程度对照，确认同一“最后可辨详情→首近黑→末近黑→首正确漫画”口径；触摸响应单独保留，不混入近黑。

## 7. 原件、操作经验与收尾状态

原件根：`D:/Shell/Github/mihon/.test-tmp/reader-open-p3-20261103/`。匹配性能组label为`p3-control-verify-aligned`、`p3-candidate-verify`、`p3-candidate-verify-second`（逐样本source见`analysis/p3-verify-final-summary.json`）；每次保留probe/request/PID、APK SHA、trace、MKV、设置与onStop导出守卫。汇总脚本`analysis/build-p3-verify-summary.ps1`保留整数校验及端点定义；早期临时口头估算不作为报告数据。

focused红绿、真实交错修复红绿、隔离像素复验、真实生命周期尝试及每次Gradle协调器日志分别保留。最终补强guard方法首轮通过，见`idle-invalidation-guard-green.xml`；格式与benchmark构建通过，key为`reader-open-p3-final-benchmark-build-20261103`。一次类名前置通配符导致大量空JVM启动的任务已按归属停止，改精确选择器；另有测试import缺失和纯格式失败修复，均不冒充产品行为RED或完整模块通过。

平板已实测无密码（secure=false），KEYCODE_WAKEUP后仍showing=true时，wm dismiss-keyguard正常成功；另一次状态约0.4秒后才收敛，期间只读复核、不重复输入。各前台小场景后退出/force-stop隔离包并确认Asleep，后台构建/分析保持熄屏。用户要求的成功操作与限制已写入[Android平板实测指南](../automation/ANDROID_TABLET_ACCEPTANCE.md)，根AGENTS已登记。

最终17份生产/构建输入的当前SHA均与冻结artifact清单相符，主代理已核验APK大小与SHA。`final-condition-audit.json`保存条件审计，`final-verify-install/oat-header-*.json`保存安装前后/采样后的实际install/verify。清理原件`final-cleanup/commands.json`、`final-sleep.json`与`host-scrcpy-processes.json`确认probe配置已关闭、隔离包已停止、无本任务采集进程，设备Asleep；`final-cleanup-wake/commands.json`保存无需人工解锁的最终成功操作。

本批包含三个生产文件、两个定向测试文件及必要证据/架构/roadmap/操作入口文档，围绕首开冗余、实测中暴露的直接绘制缺口和可复用采集流程内聚，不修改共享配对规则、数据库或下载格式。采用上述两项最小修复并随本报告提交本地main，未推送；B较完整受影响路径、Windows、全量及正式发布仍未执行。后续先复用本轮已有42点和trace，定位初始化→页表与解码提交→首绘的具体成本，再选择一个有真实失败测试的最小候选，不能重复削减已消除的首次通知工作或靠改动画取得达标。
