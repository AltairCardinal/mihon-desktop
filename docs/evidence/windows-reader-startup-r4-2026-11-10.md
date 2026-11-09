# Windows 首开 R4：机制、调用归因与外测裁决

日期：2026-11-10。状态：R4全部完成；机制与调用归因已确认，十二份正式外测未达到采用门槛，E1已精确撤回；撤回后32项focused与格式通过。整体体验预算仍未通过。

## 固定输入与区间复算

读取 `.test-tmp/r3/{Bi1,Bi2,Ci1,Ci2}/state.json` 与同目录 `r3-mechanism.json`。重算结果落在 `.test-tmp/r4/interval-recalculation.json`。四份均为 chapterId=906004、index=4，初始页/显示页为[4]；startupObservation及机制观测 dropped=0，目标 `PREPARE_END`、目标 copy 与 `ENCODED_STORE_RETURN` 在每个样本中同一 worker。实际单调时钟差，单位ms：

| 样本 | prepare退出→copy开始 | copy | copy结束→store返回 | prepare退出→store返回 |
| --- | ---: | ---: | ---: | ---: |
| Bi1 | 11.4506 | 1.5586 | 1.3981 | 14.4073 |
| Bi2 | 9.7812 | 1.6264 | 3.8560 | 15.2636 |
| Ci1 | 11.8792 | 3.3798 | 0.5360 | 15.7950 |
| Ci2 | 11.5779 | 3.4618 | 0.6908 | 15.7305 |

这是目标页事件的同线程墙钟区间。COPY标记仅包住archive copy及partial写入边界；copy前空档尚未分解到函数，copy结束至store返回还含store其余处理。Bi2 copy结束后3.856ms是当前最大后段；不能仅凭一个wall样本归因为CPU或缓存协议。Ci样本同一时间另有邻页 worker 的copy活动，wall区间不相加。R3报告所述约32.3/39.3ms窗口只代表准入到runner取走工作的机会，不等价可回收时间；不能用 `COMPOSE_SLOT_APPLY` 替代真实UI需求到达事件。

## 原件、路由与内容身份

四个独立profile下载副本均为 `profile/home/AppData/Local/Mihon/downloads/0/Slime First Open/Volume 04_2081d3.cbz`，41,380,343 bytes，SHA256 `b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677`。ZIP第5个自然数字顺序成员 `005.webp` 为357,852 bytes，SHA256 `3b43ed0beb3f6f1b832e57ac5ad7c830c00850c8b79fa0c9a708ebf08ccbce14`。四份结果一致。

实际startup事件记有 `ARCHIVE_BACKEND_MINIZ`，故本组所观察归档后端是Miniz；不是从EXE名推定。state的route字段为null、`onlineImageRequests=0`，同章已下载且完成archive页表；这支持离线路由/未发起在线图片请求的观察。productionEvents含目标encoded内容ref，形式为各profile下 `cache/network/reader-encoded/chapter-906004-page-4-a437b96b.encoded`。这证明encoded结果身份与缓存路径，不证明原始页从下载副本还是local-source副本读取。R3报告曾只读核过source=0及两副本相同，但R4本轮未重读数据库/lease，实际副本来源仍待用本轮实际lease/路径证据确认。

## 可复用采集材料及差距

本树可用 `.test-tmp/r3/run.py`、`capture_reader_window.py`、`qpc-server.py`、`mechanism.py`、`accept.py`，及 `.test-tmp/r3/capture-preparation.json`。其可复用能力包括：从既有seed复制profile并验证CBZ；显式隔离profile与偏好后端；准备B/C preview身份；以 `CREATE_NO_WINDOW` 启动限时WGC，需传本轮确切EXE/PID/profile/window参数；使用常驻无窗口QPC helper进行时间映射；采集器准备只负责arming/等待首帧，未包含允许主代理点击的安全门控。具体命令先核参数：`python .test-tmp/r3/run.py --help`、`python .test-tmp/r3/capture_reader_window.py --help`、`python .test-tmp/r3/mechanism.py --help`；本轮未启动这些流程。

R3包提供B/C同版本内部ON机制采样和OFF外测的身份记录范式；R4需要沿用而不得复用历史PID、端口、profile、窗口坐标、preview产物或manifest。B/C须在同一实现与测试产物冻结后由主代理准备新鲜preview/profile。若ON开关只存在于历史R3脚本/产品配置，需在开始采集前确认新候选依然可用；不能通过静态历史state声称采集已就绪。已有工具没有自动识别授权ARMED/ACCEPTED与真实UI viewport到达的完整判定器，须主代理读取实时state/事件并人工按roadmap门槛逐次裁决。

末尾桌面批次的流程：先完成所有代码、focused验证、preview及四份材料；主代理为唯一输入者，重新核窗口真实PID/EXE/profile、bounds及副屏/DPI；内部机制ON，每臂2份新进程，不开WGC；核实候选目标经原runner真实取出、早于真实UI viewport需求、目标ref/身份接用及无提前decode/FIRST/进度。两份候选不命中即停止外测。仅机制和正确性门槛通过，才按B0/C4、C4/B0、B0/C4执行12份OFF首次/暖态WGC样本。每次留存完整capture raw、QPC映射和输入包络；停止录制并产出终态manifest后才离线读帧。结束时只精确关闭本批应用及自有采集/helper。

## 限制与待主代理执行

- 当前仅做原件读取、区间复算和准备审计；没有新采样、GUI输入、WGC、Gradle、产品验证或进程启动。
- 本工作树未发现旧路径 `.test-tmp/desktop-first-open-20261106/luna/`，但R3本树自身的限定脚本存在；不跨盘寻找替代材料。
- 本轮没有把旧WGC脚本当成R4已配置完成。B/C冻结产物、当前窗口/显示身份、后端与lease源路径、ON采样开关及输入门槛均须按当前会话重新确认。
- 未执行测试。证据草稿与离线JSON只是R4.0过程材料，不能勾选R4 roadmap或作为功能完成报告。


## 实施基线与真实 RED

本轮在指定独立工作树 `D:/Codex/worktrees/mihon-windows-reader-source-driven-20261109` 执行，分支 `codex/windows-reader-source-driven-20261109`，实际起点 `c373f34af8e4357df656983c7bdf7e3541ebfc51`（设计文档提交，产品沿用d66d37b），初始工作树及暂存区干净。不推送、不发布、不更新公共能力说明。

`r4-red-2` 经协调器执行 `gradlew.bat :app-desktop:jvmTest --tests '*DesktopReaderRuntimeFactoryTest.screen entry prepares restored CBZ*' --tests '*ReaderStartupObservationTest' --offline --console=plain`，退出1，4项中2项失败。正常production factory创建符合资格的CBZ entry后，页表已Loaded，未挂载Compose的恢复页在3秒观察窗内始终未Ready，失败为 `Normal screen entry must submit the initial ordinary content demand`；同批真实挂载诊断失败为缺少 `ENCODED_FIND_BEGIN`。这是行为/观测缺口的真实RED，非编译失败。原XML保留在 `.test-tmp/r4/red/`，原日志在 `.gradle-coordinator/r4-red-2.log`。

前一次Windows命令拼写导致启动失败、GREEN-1诊断类型可见性与GREEN-2测试extension调用导致编译失败，分别记录为工具/编译问题，不作为行为RED或产品回归证据。后续focused结果单独记录，不覆盖初次RED。

## 接线、独立审查与验证解释

候选仅在正常Screen `createScreenEntry` 的轻量入口安装；直接 `createRuntime` 与 `createScreenModel` 默认关闭。资格捕获和后续设置重判均在session锁外，首次activate将授权绑定实际章节代；页表已Loaded的安装和晚到页表接收在同一原锁内核验身份及计划。无授权入口不遍历整章页表，目标恢复纯helper与UI共用。entry提前创建完整model、真实viewport、后续activate、失败和close撤销旧授权；已发的普通工作依赖原runner/core取消与迟到拒收，不把取消请求等同物理退出。

原锁图保持：factory生命周期锁→session安装/start；页表resolver/设置重判在session锁外；session锁内的 `applySchedulePlanLocked→runner.cancel` 沿既有同步pump可能性执行，不能声称全部pump在锁外。真实写门、partial、长度/配额检查、原子提交、Ready发布、原holder开流/解码/实际draw与进度权威均沿原链。候选只提供无进度需求，不另建内容lease、decode或FIRST生命周期。

主代理对授权/session/factory/entry部分完成一轮独立审查，要求修复无授权额外页表遍历、OFF额外诊断查页/调度快照、Ready误报新计划、ARM未解析目标时的伪页号和退出撤销等边界；实施者已纳入本轮修正。实施者另只读审查主代理编写的归档资格事实、实际lease路径及诊断透传，无阻塞发现；其参与的route短路不自称独审。主代理随后核该短路只减少不适用路径上的纯元数据查询。没有共享core/scheduler修改，没有重启R3解码collector或缓存旁路。

章节/session generation与scheduler窗口generation本来独立。`E1_ACCEPTED.generation` 是章节代，`runnerGeneration` 是原计划代；WORK/PREPARE/port的generation保持原request代，UI marker为章节代。通过opening token、page、attempt、requestId及两代桥关联，不能要求数值相等。GREEN-3新增测试中的错误同代断言、无效未Loaded viewport、fake-ref/refresh fixture与旧mock缺stub已定位为测试问题；最终结果须以随后真实store和合法时序的复验为准，不把修改错误断言称作产品bug修复。

正常Screen真实factory下载CBZ用例验证同一入口的目标像素、原OPEN/DECODE各一次及原tracker链；早期F11/Space/Ctrl复用有page-table gate的真实entry/session用例并安装同E1授权，验证完整model先取得入口后不再重判/重放初始需求。这是两段真实接线证据，不声称一个测试从真实factory贯穿pending页表全链。合成CBZ只作行为夹具，不作为史莱姆正式性能样本。

本批超过8文件/400行主要来自同一授权契约、恢复helper抽取、真实factory/Screen/session边界测试与最少诊断的内聚改动，不扩成另一用户能力或共享协议改造。主要风险为异步身份/取消、设置资格与真实UI接管；对应focused覆盖与最终源码状态另列。

### 最终 focused 组合证据

`r4-green-4` 完整执行本批明确选择的34个方法：33通过；唯一失败是新正常Screen测试错误地对旧HOLDER_READY事件的缺失ref字段执行非空断言。只将该测试改为读取真实 `COMPOSE_READY_SEEN` 完整decode key，核章节代、attempt及实际store ref；最终production未改。`r4-green-5` 只复验该方法与ReaderStartupObservationTest，共4项通过、退出0。GREEN4其余33项与GREEN5修正方法构成34个不同focused方法的有效组合证据，不冒充最终代码重新执行完整模块测试。

范围包括5个session授权方法、3个production factory/Screen方法、3个诊断方法及23个原轻量首屏/早期输入/恢复/交接/错误方法，方法内枚举三种页表时序及资格、取消边界。8页CBZ恢复index4实际准备集合为{3,4,5,6,7}，目标物理copy一次、并发预算3；不能称只读取一张图，187页正式内容的真实集合另按采样列出。

15个修改Kotlin文件的限定ktlint只读核验退出0，沿HEAD原有逐行文本用SequenceMatcher映射baseline，未压掉新变更区错误或重排旧ContentAdapter数百行。日志 `.test-tmp/r4/format-check-final.log`（成功无错误输出）、参数 `ktlint-check.args`、原/映射baseline及各协调器日志保留。`git diff --check`通过。未执行模块/仓库全量、Android、finalParityAudit或正式发布矩阵。

## 固定两包与采集配置

两臂通过临时Git index/独立detached快照冻结，未改开发分支HEAD、暂存区或在构建目录换源选臂。B0仅原基线加相同默认关闭诊断；C4仅在此基础加入E1及其纯恢复helper。冻结来源、完整差异和每个相关文件hash见 `.test-tmp/r4/frozen-sources.json`、`b0-source/`；两臂production差异为8个相关文件，没有共享core/scheduler/归档库变化。主开发树10个已改production文件与C4固定blob身份逐一核对一致。

coordinator分别在两个独立worktree**串行**调用 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/build-windows.ps1 -Preview`，`r4-preview-b0/c4`均退出0。两包build/sourceIntegrity/productionRuntime PASS，nativeInteraction仍保留构建时NOT_RUN；后续原生结果另写，不回改manifest。未分配正式版本。两臂同JDK 21.0.11.10-hotspot，可选CDS均因既有Windows命令行长度限制跳过，不当作CDS通过。local.properties仅复制原工具链配置、SHA256均b83da39e6a8a93f71f36de3b4c1bea08aadf821ef8de4edba8f7521bc5b5f1ce，不输出内容。

| 冻结项 | B0 | C4 |
| --- | --- | --- |
| 源码SHA | 773bd746c9d99ca67e1cc20a3a42daa6aacb29d5 | 3362b34b7aa213eee804825c7da51c55116f36c8 |
| product digest | be5afdfefb1a7b17d8ca0696cd779d3e456dffdbbea5d51b39e78f81a31a2dab | 96308aa6981ad507d51b412a7a8d2c5c2b97874c025007b3024a49882e944079 |
| 完整应用digest | 32bc3efad1c77154d814313c7a73a35f1a298ffe205477e9f42a0faf4c127695 | dc2f4c21c9abacc0360a539ca18d949b5294c162364b34b19b7f6429eaf392f5 |
| EXE SHA256 | 1f6754f0fc836fd7f107d596f8da5d8ba8d8717746795c8134967df68e98afe8 | 1f6754f0fc836fd7f107d596f8da5d8ba8d8717746795c8134967df68e98afe8 |
| 源码dirty | False | False |

[B0 EXE](D:/Codex/worktrees/mihon-r4-b0-frozen-20261110/app-desktop/artifacts/preview/windows/20261109T211129Z-d19335ce/Mihon-Desktop-0.11.24.6.773bd74-unpacked/Mihon%20Desktop.exe)；manifest为 `D:\Codex\worktrees\mihon-r4-b0-frozen-20261110\app-desktop\artifacts\preview\windows\20261109T211129Z-d19335ce\preview-manifest.json`。

[C4 EXE](D:/Codex/worktrees/mihon-r4-c4-frozen-20261110/app-desktop/artifacts/preview/windows/20261109T211347Z-fa4f1b24/Mihon-Desktop-0.11.24.6.3362b34-unpacked/Mihon%20Desktop.exe)；manifest为 `D:\Codex\worktrees\mihon-r4-c4-frozen-20261110\app-desktop\artifacts\preview\windows\20261109T211347Z-fa4f1b24\preview-manifest.json`。

两个Miniz构建记录的source、ABI、compiler、SDK、JNI头和构建脚本摘要相同，DLL整体hash不同；`.text`、`.pdata`、`.fptable`和`.reloc`逐段摘要相同，`.rdata/.data`及COFF字段不同。只记录该观察，不把差异猜作性能原因，也不声称两包DLL逐字节相同；记录在 `native-section-comparison.json`，实际后端仍由新样本事件核对。

新profile均从同一合法seed复制，marker精确LF、同文件PreferencesFactory、等价独立数据库、同一CBZ和全部阅读设置。主屏当前只读元数据为3840×2160、DPI144/150%；副屏bounds(3840,1296,1920,1080)、DPI96/100%。各profile初始化窗口设置为x4488/y1344/1024×768，仍须启动后逐窗口核对身份和实际bounds。最初建议497xx端口因本机排除范围49672–49971在bind时收到10013，未启动应用；改为已逐个bind核验的49220–49245，QPC49260，未修改系统保留范围。

四份ON为B0i1/C4i1/B0i2/C4i2；只有机制成立才启用OFF顺序B01/C41、C42/B02、B03/C43，每进程first/warm两次，共12条。另备C4off/C4on作限定正确性，不计性能样本。材料和精确归属在各process.json与sample-plan.json；实际启动与停止结果见以下批次记录。前台操作前已备齐采样、时钟、健康门控、原帧联系表/区间提取及精确退出工具。区间分析在复制的R3 B1原件上复核R784/F790及F/K相同，原件未改；实际新端点仍由主代理目视确认，不能只凭机器候选。

## 副屏中断与当前交付

仅启动B0i1，已核launcher36380/runtime54992、精确EXE/profile及副屏bounds。首次截图出现其他应用残影，按既有流程只读取目标窗口可访问性并最小化自己的窗口；随后恢复时Computer Use明确报告用户physical Escape，立即停止全部输入/截图/激活。尚未点击书架作品或章节，无新ON机制样本、无WGC、无正式OFF样本，不能裁决收益或采用。其他11份预备profile均未启动。

B0i1经精确HTTP shutdown正常退出，时钟helper按本任务命令行/端口核身份后停止；没有全局关闭Java或用户应用。记录 `.test-tmp/r4/native-batch-interrupted.json`。R4.0尚缺真实新样本归因，R4.4/5未完成；E1保留在隔离研究分支和C4实验包，**尚未采用、未推送、未发布**。后续须用户明确恢复原生操作后，从新的未启动profile继续四份ON机制核验，再按门槛决定OFF外测；不重做已适用的34项focused或两包构建。

## 用户恢复后的续测批次

用户明确恢复后复核两包完整artifact hash未变，开发树干净、HEAD为7d7f638281。旧B0i1原件保持，使用未进入阅读器的新B0i1r替代；随后按B0i1r/C4i1/B0i2/C4i2顺序完成四份新进程ON，均dropped=0、实际下载副本的Miniz后端、地图第5/187页正确，同encoded ref接用。每份真实普通请求集合均为{3,4,5,6,7,8}，不是只读一张图。精确关联opening/request/runner代，旧copy与compose按实际ref/正常点击时间窗关联。

| 样本 | entry资格读取ms | entry同步前段ms | 设置重判ms | runner取出早于真实UI需求ms | find查询ms | store→writer ms | copy ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| B0i1r | 0.0082 | 33.2446 | — | -3.8017 | 4.3178 | 2.0167 | 3.2055 |
| C4i1 | 2.8658 | 39.9153 | 0.5225 | 44.0631 | 4.3994 | 2.7761 | 3.0013 |
| B0i2 | 0.0109 | 39.1426 | — | -4.9359 | 3.4509 | 3.1121 | 2.7498 |
| C4i2 | 2.7049 | 31.3268 | 0.5152 | 50.2605 | 2.5333 | 2.3885 | 4.5989 |

entry同步前段为ENTRY_ELIGIBILITY_BEGIN至RUNTIME_CREATE_END，不冒充完整entry返回；所有新增资格工作计入正常章行callback之后总时间，不扣除。两份候选分别早44.0631/50.2605ms，资格及重判合计3.3883/3.2201ms；机制与同ref成立，允许进入预定OFF外测，但不等于外部净收益。目标OPEN/decoder均晚于首次真实UI需求，非挂载前后台解码；物理准备沿原scheduler。

本轮copy前空档已定位到原findEncodedPage内缓存ref/查询、fetch前置及原store写门/staging调用区间：prepare退出→find约0.104–2.212ms，find约2.533–4.399ms，find返回→fetch约0.098–1.134ms，fetch→store约0.083–0.140ms，store→writer约2.017–3.112ms，writer→copy约0.032–1.112ms。copy本身2.750–4.599ms，copy后至store返回0.515–2.297ms。区间与完整边界在四份r4-mechanism.json中；这是调用级inclusive wall归因，不能判某文件API/门锁/解压的单独CPU原因，不修改缓存协议或追加探针。

正式OFF先完成B01首次：主代理检查原帧联系表，358仍详情、359首次阅读器加载反馈，367仍无漫画、368为正确地图；F后至少1秒覆盖且未观测到再黑。R=[40.0191, 115.0173]ms、F=[281.6788, 323.3441]ms，最长/累计K=[166.6615, 283.325]ms，最大gap=74.9982ms。工具输入包络宽101.0293ms；输入包络→R/F另在metrics.json中。received=written=1549、missing=0，保留PARTIAL/native-capture-exit:1，不称完整扫描。单份控制没有配对候选，不能计算收益。

B01暖态采集健康后，sky.click报告 `user input was detected in this window; call get_window_state before continuing`。没有重发输入或继续激活，点击是否已经送达未知；暖态原始WGC保留、不得计为有效样本。随后精确停止本份WGC、通过已核EXE/profile/端口关闭B01并核launcher/runtime退出，按本次路径/端口核身份停止QPC helper57072；其余预备实例未启动。记录native-batch-resume-interrupted.json。

R4.0调用区间归因完成；R4.4正式配对矩阵和限定正确性仍未完成，R4.5采用/撤回待续。下一批从新的完整B0/C4配对开始，保留本次B01首次作为额外控制原件，不将远隔补样伪装紧邻正反对照；不重跑已适用focused或重建两包。当前E1仍是未采用实验，不推送、不发布。

## 本轮副屏批次与内部机制

本轮完整顺序为B01r、C41、C42、B02、B03、C43，各含首次和同进程暖开，共12次；配对为B01r/C41、C42/B02、B03/C43。先前被用户输入中断的B01暖开不计入，额外B01首次及全部中断原件保留。四份ON仅作机制/归因，正式计时细诊断OFF。

| 样本 | eligibility读取 | entry同步前段 | eligibility重判 | runner早于UI | 同ref接用 | 实际请求页集合 |
| --- | ---: | ---: | ---: | --- | --- | --- |
| B0i1r | 0.008 | 33.245 | — | False | True | [3, 4, 5, 6, 7, 8] |
| C4i1 | 2.866 | 39.915 | 0.522 | True | True | [3, 4, 5, 6, 7, 8] |
| B0i2 | 0.011 | 39.143 | — | False | True | [3, 4, 5, 6, 7, 8] |
| C4i2 | 2.705 | 31.327 | 0.515 | True | True | [3, 4, 5, 6, 7, 8] |

entry同步前段是ENTRY_ELIGIBILITY_BEGIN至RUNTIME_CREATE_END，包含新增资格读取与正常runtime构建，未冒充entry返回后工作；首回调到外部端点包含全部新增前置成本，不从测量结果中扣除。章节代与runner代通过ACCEPTED双代桥核对。旧copy/compose/OPEN没有完整token时按本次正常callback/open窗口和实际ref关联，不假称所有旧事件直接join。

| 调用区间(ms) | B0i1r | C4i1 | B0i2 | C4i2 |
| --- | ---: | ---: | ---: | ---: |
| PREPARE_END -> ENCODED_FIND_BEGIN | 0.104 | 2.212 | 1.772 | 2.047 |
| ENCODED_FIND_BEGIN -> ENCODED_FIND_RETURN | 4.318 | 4.399 | 3.451 | 2.533 |
| ENCODED_FIND_RETURN -> ENCODED_FETCH_BEGIN | 0.102 | 0.098 | 1.134 | 0.730 |
| ENCODED_FETCH_BEGIN -> ENCODED_STORE_CALL | 0.127 | 0.083 | 0.140 | 0.094 |
| ENCODED_STORE_CALL -> ENCODED_WRITER_BEGIN | 2.017 | 2.776 | 3.112 | 2.389 |
| ENCODED_WRITER_BEGIN -> ENCODED_STORE_RETURN | 5.534 | 5.411 | 4.684 | 6.226 |
| writer -> copy begin (time-window) | 0.032 | 1.108 | 0.668 | 1.112 |
| copy (time-window) | 3.205 | 3.001 | 2.750 | 4.599 |
| copy end -> store return (time-window) | 2.297 | 1.301 | 1.266 | 0.515 |

已有调用区间仅证明本次路径时间分布，不推断CPU、单一系统API或无故障情况下的因果；完整DLL元数据差异已记录。

实际归档lease/backend路径：

- B0i1r: `MinizZipArchiveLease:file:/D:/Codex/worktrees/mihon-windows-reader-source-driven-20261109/.test-tmp/r4/B0i1r/profile/home/AppData/Local/Mihon/downloads/0/Slime%20First%20Open/Volume%2004_2081d3.cbz`。
- C4i1: `MinizZipArchiveLease:file:/D:/Codex/worktrees/mihon-windows-reader-source-driven-20261109/.test-tmp/r4/C4i1/profile/home/AppData/Local/Mihon/downloads/0/Slime%20First%20Open/Volume%2004_2081d3.cbz`。
- B0i2: `MinizZipArchiveLease:file:/D:/Codex/worktrees/mihon-windows-reader-source-driven-20261109/.test-tmp/r4/B0i2/profile/home/AppData/Local/Mihon/downloads/0/Slime%20First%20Open/Volume%2004_2081d3.cbz`。
- C4i2: `MinizZipArchiveLease:file:/D:/Codex/worktrees/mihon-windows-reader-source-driven-20261109/.test-tmp/r4/C4i2/profile/home/AppData/Local/Mihon/downloads/0/Slime%20First%20Open/Volume%2004_2081d3.cbz`。

两份候选均证明runner普通encoded需求早于UI需求，UI接用同一ref，dropped=0；实际请求页集合为3至8，并发3，没有提前解码。

## 正式OFF外部区间

单位ms，主起点为正常章行callback；工具输入包络结果另列。相邻实采原帧给区间，不插值，不当物理扫描，不报告P95。process-cold未清OS文件缓存。

| 样本 | 状态 | R | F | 最长K | 累计K | 最大gap | R/F原帧 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| B01r | initial | 49.105–99.104 | 240.767–257.433 | 141.663–208.328 | 141.663–208.328 | 74.998 | 769/777 |
| B01r | warm | 50.089–66.755 | 133.422–158.421 | 66.666–108.332 | 66.666–108.332 | 66.666 | 1354/1358 |
| C41 | initial | 31.538–114.870 | 256.537–289.870 | 141.667–258.331 | 141.667–258.331 | 83.332 | 361/369 |
| C41 | warm | 32.150–48.816 | 107.148–132.148 | 58.333–99.998 | 58.333–99.998 | 25.000 | 363/367 |
| C42 | initial | 40.942–90.939 | 240.935–257.602 | 149.996–216.660 | 149.996–216.660 | 83.331 | 363/370 |
| C42 | warm | 39.412–64.411 | 106.077–131.076 | 41.666–91.664 | 41.666–91.664 | 25.000 | 360/363 |
| B02 | initial | 32.608–107.605 | 265.934–282.600 | 158.329–249.993 | 158.329–249.993 | 74.997 | 365/374 |
| B02 | warm | 42.033–58.700 | 125.367–142.033 | 66.668–100.001 | 66.668–100.001 | 25.000 | 368/373 |
| B03 | initial | 56.974–90.305 | 248.636–265.301 | 158.330–208.328 | 158.330–208.328 | 74.997 | 366/375 |
| B03 | warm | 32.084–48.749 | 115.415–132.083 | 66.666–99.999 | 66.666–99.999 | 25.000 | 364/369 |
| C43 | initial | 38.739–105.404 | 263.732–280.398 | 158.327–241.659 | 158.327–241.659 | 66.666 | 356/365 |
| C43 | warm | 38.996–55.663 | 122.327–138.993 | 66.665–99.997 | 66.665–99.997 | 25.000 | 363/368 |

| 样本 | 状态 | 工具包络宽 | 输入包络→R | 输入包络→F | F后至少1秒覆盖/再黑 |
| --- | --- | ---: | ---: | ---: | --- |
| B01r | initial | 95.717 | 52.299–198.015 | 243.961–356.344 | True/False |
| B01r | warm | 103.100 | 51.605–171.371 | 134.938–263.037 | True/False |
| C41 | initial | 109.995 | 35.750–229.077 | 260.749–404.076 | True/False |
| C41 | warm | 102.265 | 35.020–153.951 | 110.018–237.284 | True/False |
| C42 | initial | 104.147 | 43.717–197.861 | 243.710–364.523 | True/False |
| C42 | warm | 111.741 | 42.865–179.605 | 109.530–246.270 | True/False |
| B02 | initial | 106.008 | 36.478–217.483 | 269.804–392.479 | True/False |
| B02 | warm | 106.197 | 44.053–166.917 | 127.387–250.251 | True/False |
| B03 | initial | 103.351 | 59.537–196.220 | 251.199–371.216 | True/False |
| B03 | warm | 99.896 | 35.165–151.727 | 118.496–235.060 | True/False |
| C43 | initial | 103.632 | 43.163–213.462 | 268.156–388.455 | True/False |
| C43 | warm | 98.726 | 41.934–157.326 | 125.265–240.657 | True/False |

配对差为C−B：[C下界−B上界,C上界−B下界]，跨0未分辨。

| 对照 | 状态 | R差 | F差 | K差 |
| --- | --- | ---: | ---: | ---: |
| B01r/C41 | initial | -67.566–65.765 | -0.896–49.102 | -66.661–116.668 |
| B01r/C41 | warm | -34.605–-1.273 | -51.273–-1.273 | -50.000–33.332 |
| B02/C42 | initial | -66.663–58.331 | -41.665–-8.332 | -99.996–58.331 |
| B02/C42 | warm | -19.288–22.378 | -35.957–5.709 | -58.335–24.997 |
| B03/C43 | initial | -51.567–48.431 | -1.569–31.762 | -50.000–83.329 |
| B03/C43 | warm | -9.753–23.579 | -9.755–23.578 | -33.334–33.331 |

12份原帧端点均已逐份目视确认，received=written且missing=0，F后覆盖至少1秒且无再黑。主动停止后的采集清单为PARTIAL/native-capture-exit:1，保留原始警告；区间含实采gap，不插值。两组正向首次F与K差均跨0，反向首次F可分辨改善；暖态部分改善不能替代首次正反顺序门槛。未清OS文件缓存，不作P95、物理扫描、官方对比或整体预算达标声明。

## 裁决、正确性与交付状态（撤回前记录）

不采用E1：机制成立且本次正确性检查通过，但首次打开改善未同时在正反顺序分辨。候选源码目前仍在实验分支，尚未撤回；R4.5与父D14保持未完成。按原生验收收尾规则，下一轮只精确撤回E1并补受影响focused，不再重跑本轮性能矩阵。

C43单页[4]→[5]→[4]，前后图像与mountedReader一致，F11进出均保持index4，窗口全屏及恢复均完全位于副屏。C4off双页RTL、autoSpreadMatching=false：[4,3]→[6,5]→[4,3]；C4on双页RTL、autoSpreadMatching=true：[5,4]→[7,6]→[5,4]。三组chapterId=906004、generation=1，pairingStatus=READY、图像尝试Ready，locationSequence=0/1/2正常增长；ON的sharedUnits按既有配对动态修订，最终currentPage=5（同一[4,5]组锚点），不误报为index4不变。进度持久化及进程退出另存cleanup-final.json。

所有本轮实例均经自身shutdown确认归属并退出；所有WGC已停止，QPC时钟已请求退出并核验。未关闭用户其他终端或程序。

只提交本任务源码、真实测试及内部文档，不推送、不发布、不更新公共能力说明。原始profile、帧与大型过程材料留在本机.test-tmp/r4；固定快照仅为实验来源，不冒充主干交付。

最终只读进度核对：六份正式样本与C4off均持久化last_page_read=4；C4on为5（相同[4,5]配对组的既有锚点）。八份process均有closedAt，QPC PID25832已退出，见本机cleanup-final.json。全屏检查含已有桌面叠层，仅对目标地图、阅读位置及副屏边界作正确性判定，不计入性能矩阵。

## R4.5 最终撤回与验证

本节取代前述各批次的“待撤回/待续”即时状态；历史中断和原件不删除。E1机制成立，但首次F或K未同时在正反顺序分辨改善，因此不采用，整体体验预算仍未通过。

从干净HEAD `5f5e8a8acfc9b95ec8ebd60dfc78eacd3953b2f3` 精确撤回12个仅候选路径：entry资格读取/授权安装、session初始需求与取消接用分支、归档资格字段、纯恢复helper及其UI调用改写、候选专用测试与fixture。恢复既有恢复解析和正常视口调度；不回滚R2输入修复、R3缓存缺失反馈修复或默认关闭诊断。最终 `app-desktop/src/main` 与冻结B0 `773bd746c9d99ca67e1cc20a3a42daa6aacb29d5` 经Git内容比较无差异，唯一额外测试为本次真实撤回回归。路径较多源于同一候选贯穿factory/session/UI与接线测试，未扩大到共享core/scheduler或其他功能。

撤回先写真实production回归 `DesktopReaderRuntimeFactoryTest.screen entry waits for viewport before preparing restored CBZ`：同一真实CBZ/文件store的正常entry在未挂载视口时不得准备恢复页；提交真实视口后通过原scheduler准备同一ref，copy一次且没有提前OPEN/DECODE/FIRST。`r4-withdraw-red` 退出1，唯一断言失败为Entry creation must not submit speculative encoded demand，实际已Ready；非编译或工具错误。RED XML留在 `.test-tmp/r4/withdraw-red`。

最终 `r4-withdraw-green` 经协调器离线执行下列focused，退出0，32项全部通过：factory四项、session两项、ReaderFirstCompositionIntegrationTest 23项、ReaderStartupObservationTest三项。覆盖恢复页需求、原factory/core/model接线、store读取及单次开流解码、零页→视口/进度、切章/重试、真实轻量首屏与模型交接、早期键盘/控件、错误与退出。完整选择与日志为 `.gradle-coordinator/r4-withdraw-green.json/.log`，XML留在 `.test-tmp/r4/withdraw-green`。

```text
gradlew.bat :app-desktop:jvmTest --tests *DesktopReaderRuntimeFactoryTest.screen entry waits for viewport* --tests *DesktopReaderRuntimeFactoryTest.production factory creates one shared core* --tests *DesktopReaderRuntimeFactoryTest.production runtime image pipeline reads its own* --tests *DesktopReaderRuntimeFactoryTest.production visible consumers share one* --tests *DesktopReaderSessionIntegrationTest.one session opens at zero* --tests *DesktopReaderSessionIntegrationTest.adjacent activation reuses core* --tests *ReaderFirstCompositionIntegrationTest --tests *ReaderStartupObservationTest --offline --console=plain
```

14个现存相关Kotlin文件的ktlint检查退出0；仅复用原HEAD已登记问题并按未变行精确映射，不为新增代码豁免。映射及日志为 `.test-tmp/r4/withdraw-format-baseline.xml`、`withdraw-ktlint.args`、`withdraw-format.log`。`git diff --check`通过。本次未运行模块/仓库全量，未重新构建或重复原生性能矩阵：最终production与已冻结实测B0一致，32项补验针对撤回影响，旧C4原生结果仅保留作候选裁决，不冒充最终候选验收。

前轮已精确关停应用、WGC与自建QPC，进度持久化见cleanup-final.json；本轮未启动应用。R4.0–5和父D14完成，active-child-plan清空；保留数据、可确认调用区间与反例，不追加第二候选、采样、缓存改造或公共能力说明。最终源码以包含本节的撤回提交为准，提交hash在交付回复报告；本次仅本地提交，不推送、不发布。
