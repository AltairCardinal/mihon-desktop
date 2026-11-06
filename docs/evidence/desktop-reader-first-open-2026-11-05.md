# Windows 已下载章节首开：首轮执行结果

日期 2026-11-05。执行[首开计划](../roadmap/2026-11-05-desktop-reader-first-open.md)，工作树 D:/Codex/worktrees/mihon-desktop-reader-first-open-20261105，基线 2b13d0c35d，计划提交 a264a6c55d。最终裁决为**撤回提前解码候选，保留基线**。本轮找到并干预了呈现消费者依赖，单页新进程首次的总收益方向混合。两种模式暖态及双页首次内部绘制端点改善，尚不足以采用覆盖单/双页的整体候选。

## 已证实的机制和候选

真实 DesktopReaderRuntimeFactory 创建本地目录章节，恢复到索引1的第2页。无Compose消费者时，页面已Ready，生产DECODE事件为0；新增测试期望1，真实RED失败。原件 .test-tmp/desktop-first-open-20261105/sol/red.xml（1测试1失败，无跳过）。这证明解码启动依赖呈现holder.acquire，不能据此把旧255ms全部归因于Compose或某一个函数。

候选只对本地/已下载章节的恢复目标，在Ready时由原presentation image owner提前申请1个holder，完整单页槽接用相同holder，split槽仍通过原pipeline共享full-page解码。首次绘制、切代、重试和关闭处理其释放。未降低2048px基本呈现边界、区域精读、质量或改变配对算法。

最终focused GREEN为24/24，0错误/失败/跳过，37秒。真实factory测试1项、DesktopReaderPresentationImageOwnerTest14项、ReaderPresentationPipelineProductionTest9项；后者覆盖单/双页/条漫与3种存储的实际挂载。覆盖UI接用一次开流/解码、不提前报告绘制、split复用、未挂载便切章/关闭释放、重试/代次拒绝旧结果。协调器日志键 desktop-first-open-red、desktop-first-open-green、desktop-first-open-green-final。选择器和XML原件在sol目录，独立复核其实际计数。git diff --check通过。Desktop未应用spotlessKotlin插件，该任务名实际不可用，不能记录格式任务PASS；Kotlin编译和diff空白检查通过。

## 同一候选的两批窗口运行

均为同规格180页2400×3500 JPEG下载目录，独立Test Mode profile。A/B每模式各1个新进程，首次之后用同进程新夹具测暖态。由生产API read_chapter驱动真实ReaderScreen；计时起点为生产OPEN_READER_INTENT，终点为第0页FIRST_PAGE_PRESENTED绘制回调。HTTP时间另存但不用作输入计时。状态原件核实单页RTL，双页开关及自动配对均实际读回。双页自动配对关闭，表内只报告第0页的首呈，不代替双页完整可读时间。

原批外部捕获误绑定打包启动器PID，八条全部无法找到可见AWT窗口。补充只读诊断证明实际JVM窗口属于该启动器的同EXE子进程。为修复测量绑定，追加一次使用该已核路径/父子关系PID的窗口采集，原批完整保留；追加不是独立随机反序验证，两批均为A→B，不能混池或挑较优一批。每条FIRST_PAGE_PRESENTED都是应用draw回调，不是物理面板。全部16条在[逐样本CSV](desktop-reader-first-open-2026-11-05.csv)，其模式、身份和限制逐条登记。

| 模式/打开 | 原批 A→B 首绘 ms | 修正窗口PID批 A→B 首绘 ms |
|---|---:|---:|
| 单页首次 | 634.861→289.021 | 249.297→261.684 |
| 单页暖态 | 60.194→51.821 | 60.640→50.582 |
| 双页首次（首张） | 365.784→308.824 | 313.796→251.567 |
| 双页暖态（首张） | 70.726→55.687 | 72.875→56.886 |

候选所有样本的页表→当前页开流缩到0.160–10.703ms，控制为13.246–544.773ms，证明早申请的机制按设计工作。机制减少不保证总首绘同量提前。

- 单页首次原批提前345.840ms，修正批反而晚12.387ms。原批A也经历新工作树构建后的第一次native合成夹具运行，类加载、JIT、fixture准备、调度/缓存均未单独控制，不把该慢样本抹掉或全当可省下时间。
- 双页首次两批提前56.960/62.229ms，但自动配对和完整两页端点未测，不作全双页结论。暖态4对方向均改善。
- 修正PID后的8条捕获中5条RECORDED；B单页首次、A/B双页首次仍因捕获准备时看不到目标AWT窗口而无外部帧。正常主线程/生产呈现后续成立，不把“捕获启动时窗口不可见”说成应用未绘图或系统无显示器。3条失败没有再追采。
- 已实际查看候选单页暖态WGC原图，显示夹具白页和蓝边，窗口内容正常；合成夹具各页纹理相同，该图不能验证史莱姆页码。没有完整可比的外部首次窗口帧，故未计算首次近黑或外部首见收益。
- WGC读取额外负载与新进程顺序不同，两批数据分别报告。未获得置信区间或P95，未按固定百分比许诺日常提速。

## 官方参照和本轮缺口

找到Windows原件 D:/Shell/Github/mihon/.test-tmp/slime-reader-remeasure-20261102/slime-vol04.cbz，41,380,343字节，SHA256 b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677，与平板相同。但现有fixture API生成合成JPEG，尚未准备好该原件的隔离正常本地导入/作品详情点击。没有改HTTP设施塞入路径，也没有将合成fixture说成同书同页。

官方同页外部首见中位数首次289.6ms、暖态282.2ms，来源[原三方对照](D:/Codex/worktrees/mihon-reader-o-series-20261104/docs/evidence/reader-open-o-series-2026-11-04.md#三方真机对照)。本轮既缺同书同页，又缺真实点击和完整外部首见，不与289.6直接相减。响应100ms、无内容60ms及整体追平官方均**未确认**。

本轮最有价值的后续入口是把真实史莱姆CBZ通过隔离profile正常导入，补实际点击→内部open→可见画面链，并在捕获目标窗口实际存在后就绪再点击。已有WGC后端复用即可，重点核对子进程而非启动器。该测量准备应先于第二个产品候选。章节重复下载检查和共享身份索引本轮未实施。

## 构建、撤回与交付状态

控制和候选均走项目preview入口和Gradle协调器，无版本分配、正式发布或全量测试。两包实际完成扩展运行时检查。A源为a264a6c55d加首个RED测试（未改production）；B源为同commit加冻结候选diff，preview标记dirty。源码指纹A为1b7b9ed92ce0c2d9785dfae7f218108f569f0ef03dffefa7db05214b00aa329c，B为5e839de11a463f3280c3bb8f3f194f9985278e715e286363b775fa7c8bfba3b4。两包完整应用摘要分别c4d1880511170ef3d066f80e3e71b609d36c866c7e5be3524534440745a65a0e与684862bad9328fd20962ed177847940da725c63c29843d8b038a36828ce7a43a。

- [控制preview](D:/Codex/worktrees/mihon-desktop-reader-first-open-20261105/app-desktop/artifacts/preview/windows/20261105T151929Z-c5ac9b7d/Mihon-Desktop-0.11.24.6.a264a6c-unpacked/Mihon%20Desktop.exe)
- [已撤回的实验preview](D:/Codex/worktrees/mihon-desktop-reader-first-open-20261105/app-desktop/artifacts/preview/windows/20261105T152418Z-07bdaf23/Mihon-Desktop-0.11.24.6.a264a6c-unpacked/Mihon%20Desktop.exe)

两次preview共有CDS生成失败/跳过提示，构建和production runtime验收成功；这说明本轮两个包没有据此确认共享类数据归档生效，不把CDS作为性能原因。未为此扩大构建范围。

最终将2个production文件、2个测试文件和架构文件按本任务开始HEAD原始字节恢复，候选完整patch与RED/GREEN保留在 .test-tmp/desktop-first-open-20261105/sol/。源码撤回后没有重跑产品测试，不把已撤回候选24项GREEN冒称基线重新测试。所有本次测试进程已退出，系统中的其他Mihon实例和用户profile未操作。此目录仅提交计划、结果和样本CSV，不推送或修改公共功能说明。

## 【功能特性】

保留基线桌面阅读体验，提供可复核的首轮性能诊断与候选归档。

## 【BUG 修复】

本轮未确认稳定改善单页首开，未采用产品改动。

## 【验收清单】

- [x] D0生产Ready→无呈现消费者反例与源码链确认。
- [x] 单候选真实RED/GREEN和定向24项验证，基线/候选preview冻结。
- [x] 两批实际窗口运行、16条内部端点、5条成功WGC及失败信息保留。
- [x] 按收益和证据边界撤回本任务候选，精确关停测试实例。
- [ ] 真实史莱姆同页、正常点击、外部首次帧/近黑以及自动匹配双页完整端点，等待下一批测量准备。
