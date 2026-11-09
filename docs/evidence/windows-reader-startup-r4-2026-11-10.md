# Windows 首开 R4：原件复算与采集准备（草稿）

日期：2026-11-10。状态：实现、focused、冻结及四份ON机制/调用归因完成；OFF仅一份控制，续测遇目标窗口用户输入后停止，尚无采用结论。

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

四份ON为B0i1/C4i1/B0i2/C4i2；只有机制成立才启用OFF顺序B01/C41、C42/B02、B03/C43，每进程first/warm两次，共12条。另备C4off/C4on作限定正确性，不计性能样本。材料和精确归属在各process.json与sample-plan.json；当前尚未启动这些GUI实例。前台操作前已备齐采样、时钟、健康门控、原帧联系表/区间提取及精确退出工具。区间分析在复制的R3 B1原件上复核R784/F790及F/K相同，原件未改；实际新端点仍由主代理目视确认，不能只凭机器候选。

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
