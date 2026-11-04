# 阅读器 O1–O11 逐项实施与实测记录

日期：2026-11-04，交接更新于2026-11-05。执行入口：[完整计划与新Sol会话交接](../roadmap/2026-11-04-reader-open-o-series-plan.md)。起始主干 `2425aca5e5`；此前采用版本为R2。用户因主控会话成本要求停止本会话实施，交新Sol会话继续。O1尚未完成采用判断，O2仅有未运行测试草稿；本页不将设计估算写成实测收益，不代表全部优化或双端性能目标已经完成。

## 条件与测量口径

用户要求Sol/Luna执行、主代理监管和实现评审。Sol负责生产/测试/Gradle，Luna负责唯一设备操作、采集和离线汇总；正式性能采集与Gradle串行。官方 `app.mihon`、正式fork及数据保留。启动时用户已有两份主线程审查文档未提交，不属于本轮可暂存内容。

初次设备盘点：USB `5JB0224111000992`，PCE-W30、Android12/API31，已授权；平板Asleep。留装隔离R2 APK SHA256为 `259c0d98b00deeca6a1254f753f2ffe0be2e6a7d8f541db74b117de6cfdadfc6`，probe关闭、targetPage=4。本轮O1的A臂使用此已采用的历史R2-B产物；A是本轮控制臂标签，不是历史R2-A。实际OAT读取未成功，有限诊断确认shell无法读取当前odex；compiler filter记为UNKNOWN，不用旧状态或compile返回值代签。原件为本轮measurement目录的 `oat-preflight.json` 与 `oat-diagnostic-once.json`。

新协议将升级后首次、统一准备/预热后新进程首次、同进程再次分别记录。默认逐项净收益使用同条件的后两种；不将有意预热后的数据写作升级首开。正式样本仍取真实输入、内部首绘/完整单元、主线程Running和原视频近黑边界；MKV未取得绝对时钟时不计算输入到屏幕可见。

A1重装同一控制APK后，`conditions-o1-a1-install.json` 成功读回当前odex的 `compiler-filter=verify`、`compilation-reason=install`，故本块状态已知；这不能追溯覆盖重装前UNKNOWN记录。主代理查看single设置及目标页原图，确认RTL、裁边OFF、横图放大OFF、source4世界地图5/187。准备截图仍有红青方向提示；源码确认该提示经触摸才淡出，不会定时自然消退，且修改导航设置可独立触发它。正式样本须看新进程初开、尚未重新打开设置时的reader图，不能用设置后的提示层推断启动开关；若新进程仍显示，先通过正常全局“点按区域提示”设置核OFF，再重复统一准备。

## 主代理独立复核：源码级确认的耗时来源

以下不是设计估算，是本轮主代理直接对照上游分叉点 `77a86d486fc1` 与当前工作树源码确认的机制，用于给 O1–O11 排优先级。行号为核对时的位置。

1. **holder 的 I/O 与 Main 往返次数**：上游 `PagerPageHolder.setImage()` 是「一段 `withIOContext` 读字节 → 一次 `withUIContext` 显示」。fork 改造前是「`withIOContext` 读 header → 回 Main → `withIOContext` 读完整字节 → 回 Main」，双页还多一次「在 `withUIContext` 内部再 `withIOContext` 取 `getImageDimensions`」。⇒ O1 消除的往返是真实存在的结构差异，不是推测。
2. **O4 的下载状态查询是回退而非上游行为**：上游把 `downloadManager.isChapterDownloaded(...)` 放在 `||` 短路链里，只有 `manga.downloadedFilterRaw` 真的按下载状态筛选时才会求值。fork 的 `isChapterFiltered` 把它作为**值参数**传给 `isReaderChapterFiltered(..., chapterIsDownloaded = downloadManager.isChapterDownloaded(...))` ⇒ Kotlin 先求值实参 ⇒ **每一章都做一次下载状态查询**（含多个候选目录名与 md5），与是否启用下载筛选无关。`domain/.../ReaderNavigation.kt` 的 `isReaderChapterFiltered` 收 `Boolean` 而非惰性 lambda，所以必须在调用点修。
3. **O4 的章节列表被查了两遍**：`ReaderViewModel` 第 472 行为「只取 selected」执行了一次 `getChaptersByMangaId.await(...)`，`getChapterList()` 内又执行一次同一查询。
4. **O2 的冗余重建点在调用方，不在 session**：`SharedDualPageSession.acceptOriginalFact` 对同一 request 的**相同事实**会在 `accepted[request.requestId]?.let { return it == fact }` 处提前返回 true 且**不**重建。但 `DualPageViewerAdapter.acceptFact`（及 `acceptDecodedDimensions`）只要 session 返回 true 就**无条件**调用 `rebuildItems()` ⇒ 事实未变同样整窗重建。⇒ O2 要改的是 adapter 的「接受」与「改变呈现输入」语义区分。
5. **O3 的重绑与阅读模式无关**：`restoreChapterPairingInBackground` 成功后在 `withUIContext` 里无条件 `eventChannel.trySend(Event.ReloadViewerChapters)` ⇒ 单页/条漫也会走一次完整 `setChapters` 重新绑定，而单页并不消费配对布局。
6. **O8 的收集器是每页一个**：`PagerViewer.bindSingleFacts` 对前/当前/后三章的**每一页**各 launch 一个收集器（`pages.filter { it !in factCollectors }.forEach { ... }`）⇒ 187 页章节即 187 条消息排在 holder 加载之前。
7. **规模对照**：fork 在 6 个开屏关键文件（`ReaderActivity`、`ReaderViewModel`、`PagerViewer`、`PagerPageHolder`、`DualPagerPageHolder`、`DualPageViewerAdapter`）上相对上游新增 **4,165 行**；`ui/reader` 路径文件数 51 → 75；新增 41 文件的 `domain/reader` 模块。⇒ 开屏变慢不是个别慢点，而是整套双页配对机制默认挂在开屏关键路径上的累积结果。

## 设备预检与采集工具自检

原件目录：`.test-tmp/reader-open-o-series-20261104/preflight/`（含 README、preflight-summary.json、各读回原件与脚本副本；未覆盖既有文件）。

**设备身份（当次重新枚举）**：USB serial `5JB0224111000992`，PCE-W30 / HUAWEI / HWPCE-L，Android 12 / API 31，arm64-v8a；物理 2880×1920、override **1920×2880**、density 320；`mWakefulness=Asleep`；锁屏 `showing=true` 但 `secure=false`（无密码锁屏）；前台焦点在 launcher，无应用有焦点。

**隔离包状态**：已安装 `app.mihon.desktop.fork.benchmark`，versionName `0.19.4-aex.116-benchmark`、versionCode 134、非 debuggable。设备 `base.apk` SHA256 = `259c0d98…dadfc6`，与 `measurement/manifest.json` 的 `arms.A.apkSha256`、本地产物 `app/artifacts/android/diagnostics/reader-open-r2-b-20261104/Mihon-Fork-reader-r2-b-benchmark-universal.apk` **三方一致** ⇒ arm A（控制）就是当前留装的 R2 基线。

**oat header 本轮读不出（计划要求「读回实际OAT」未满足，如实记录）**：`base.odex` 存在（218,024 B）但 mode `0640 system:all_a198`，shell(2000) 不在该组 ⇒ 同文件 `cat` 与 `adb pull` 均 Permission denied，`oatdump --header-only` 返回 rc=1 且无输出。**已排除工具与路径形状问题**：同一形状在 mode `0644` 的另一包上 rc=0 并读出 header。其它只读来源均不可用（`dumpsys package dexopt` 0 行、`dumpsys package <pkg>` 无 Dexopt 段、设备无 `su`、`otadexopt` 为厂商私有实现、logcat 无 dex2oat 历史）。
⇒ **协议调整**：编译状态改由**程序化对齐**保证 —— 两臂用**同一安装命令形状**，且**安装后立刻读一次 header**（安装期产物为 0644 可读，R4H 12:02 即如此），之后**不得再执行 `cmd package compile`**；确实需要强制编译时，另行约定可核验的编译状态记录方式。这是对计划第 2 条的部分让步，已在此显式记录，不当作已满足。

**采集工具自检（全部只读，原件零改写）**：`record_sources.py` 通过（17 个被引源脚本齐全并算出 SHA）；`oat_preflight.py`、`read_device_conditions.py` 部分通过（APK SHA/电量/温度/电源可读，`compilerFilter`/`compilationReason` 因上述权限为 null ⇒ status=UNKNOWN）；`validate_probe.py` 用 arm A 真 APK + 真设备条件 + 合成 probe **通过全部门禁**，其中已验证 O1 的事件顺序 `OPEN_PAGE < ENCODED_READ_COMPLETE < TARGET_HEADER_BEGIN < TARGET_HEADER_END < DECODE_SUBMIT` 被接受、双页需 `UNIT_DRAWN`；`prepare_mode.py` 因缺 `preparation-state.json` 预期失败（未发生任何设备动作，该文件现已就位，mode=dual-off、matching=false）；`wake_p4.py` 通过；10 个脚本 `py_compile` 全部通过。

**采集启动前记录的前置**：① B候选SHA/路径写入测量manifest；②交付本地候选APK；③脚本定稿后记录源SHA；④两臂同安装形状、立即读header且不强制编译；⑤排除preflight工作副本的两条合成夹具；⑥每种mode经真实UI准备。正式A1/B1/B2已经执行，前两项已落实；脚本后续有校验/唤醒修正，交接后应记录最终脚本SHA及已知差异，不把早期自检清单误认为仍未提供B包，也不把preflight夹具计入样本。

**如实说明的两处副作用**：`run-selfcheck.ps1` 对 `wake_p4.py` 传 `--help`，该脚本无 `--help` 分支而是把 `argv[1]` 当 label，于是真实执行了**一次唤醒**；本轮共 2 次唤醒，均已被最后的 `KEYCODE_SLEEP` 收尾（一次有界只读复核确认 `Asleep/false`）。收尾后设备仍在线 authorized、隔离包保持安装。

## 逐项结果

| 项目 | 状态 | 有效性能样本 | 实测净收益 | 采用状态 |
| --- | --- | --- | --- | --- |
| O1 合并图片准备IO与Main往返 | 候选/必要focused已完成；采集及交接最终状态见末段 | A1/B1各6，B2为4；A2见末段 | 全组分析未完成，不判稳定收益 | 尚未采用 |
| O2 双页事实变化与冗余重建 | 真实测试草稿已写，尚未编译/RED；无production修改 | 0 | 尚未测量 | 待执行 |
| O3 配对恢复按需重绑 | 未实施 | 0 | 尚未测量 | 待执行 |
| O4 列表复用与惰性下载过滤 | 未实施 | 0 | 尚未测量 | 待执行 |
| O5 AUTO首次布局 | 未取证 | 0 | 尚未测量 | 待执行 |
| O6 邻单元准备优先级 | 未取证 | 0 | 尚未测量 | 待执行 |
| O7 匹配与首图竞争 | 未取证 | 0 | 尚未测量 | 待执行 |
| O8 索引/监听 | 未实施 | 0 | 尚未测量 | 待执行 |
| O9 浮层状态订阅 | 未实施 | 0 | 尚未测量 | 待执行 |
| O10 Activity归因 | 未取证 | 0 | 尚未测量 | 待执行 |
| O11 profile可比性 | APK含profile；安装块实际filter为verify，profile应用收益未测 | 0 | 尚未测量 | 待执行 |

## O1：先确认真实冗余读取

第一轮focused RED为 `reader-o1-red`，两个新增生产holder用例；仓库自动retry使XML出现6个失败记录，不算6个不同用例。双页真实流工厂计数期望1、实际2，确认额外读取。单页首次夹具Config尚为空而失败，不认定为性能RED；先补真实viewer章节绑定和实际图像准备等待，再复跑旧production。初次XML已保存于本轮O1临时原件目录，后续GREEN不能覆盖或掩盖这一夹具修正。

修正夹具后的 `reader-o1-red2` 已在旧production同时确认单页、双页实际开流2次，期望1次；独立原件为 `o1/red-holder.xml`。`reader-o1-probe-red` 同样确认旧startup采样窗口在OPEN_PAGE之后仍开启，原件为 `o1/red-probe.xml`。首次GREEN中的三个失败属于新增夹具的实际pager接线、替代holder类型过约束和协程取消异常对象恒等断言，尚不能计作通过或产品回归；正在保留真实契约的前提下修正并复验。

另外发现coordinator键 `o1-green-holder` 的22:08–22:10运行，发起者未确认、已经终态FAILED，无存活worker。其运行发生于实现和夹具仍在修改期间，故不作为基线或本轮验收证据；本轮使用带 `reader-o1-` 前缀的独立命令及保存原件。核查时仅有Gradle/Kotlin常驻daemon，未发现当前并行测试进程。

22:22同键再次运行，进程树确认发起者为另一个MiniMax Code会话，本线程执行父进程则是ChatGPT/codex。因此冻结原目录写入，按项目并发规则建立独立worktree `D:/Codex/worktrees/mihon-reader-o-series-20261104`，对本任务7个production/test及3个文档逐文件核SHA复制，原目录未撤销。复制清单在原测量根的 `isolation-copy.json`。不终止其他会话进程、不采用其测试结果。设备采集在确认无并发操作后恢复。

用户随后明确确认另一个会话已暂停本轮优化与设备操作，故恢复本线程测试与设备采集安排，继续使用隔离worktree。此前带类名前置通配符的诊断触发大量无关发现JVM，已仅停止本任务进程并精确恢复候选；取消时残留的旧XML明确标为无效。后续全部使用完整包名和精确方法名。

实现约束：仅复用正常读取的原图Buffer及原有有界元信息解析，不为了避免小块copy扩展共享orientation解析器；保留512KiB元信息读取边界和失败重试语义。Main一次续体校验捕获身份，提交正确fact后建立最新呈现身份，再接入首次图片显示。自发方向version更新与外部内容替换/重试必须可区分，不能因此重复读取或吞掉真实变化。

OPEN_PAGE仍位于实际开流前；O1的header解析事件可移到完整编码读取之后，四段之间的工作转移不算净收益。原startup sections仅以HEADER_BEGIN截止会扩大诊断窗口，正在检查兼容的“HEADER_BEGIN或OPEN_PAGE先到即截止”；旧链header先到、新链open先到，主要总时长和真实draw端点不改变。

真实挂载补验发现EXIF首次方向事实可能使未显示的frame更换，候选因此仍有两次完整读取；在前台门控隔离采样后该现象仍存在，不能归咎采样。同夹具旧holder测试中，默认WebP的期望一次读取真实RED；EXIF则以两次读取、正确TRANSPOSE/40×60/镜像/首绘通过。候选的同条件结果待最终核验。若旧路径为有界header加完整显示读取、新路径为两次完整读取，大于512KiB的EXIF图可能增加字节量；本项不宣称这一条件的I/O收益，也不为强求次数引入跨holder缓存或放宽旧frame拒收。

精确对照 `reader-o1-mounted-old-exact` 已完整结束：两个实际方法，默认WebP重复三次均以期望1/实际2开流失败，EXIF方法通过。调用栈明确两次分别来自 `publishHeaderFact` 和holder完整读图；XML四条记录是三个重试加一个通过，不是四个不同方法。独立原件 `old-mounted-holder-exact.xml/log` 与source manifest记录旧holder SHA、候选SHA、fresh XML检查及finally恢复哈希一致。该对照仅将DualPagerPageHolder换回起点版本，其余ReaderPage新接口为编译测试而保留，不能描述为整个旧APK性能对照。

这些读取次数的截止点是测试中的真实首绘及紧随的runCurrent，不是完整holder生命周期。原日志还存在teardown后的开流，不能把“首绘前2次”改写为“永远只开流2次”。

`reader-o1-mounted-green-exact` 新鲜XML确认两个方法均通过，格式检查通过。普通WebP从旧路径header+显示两次开流变为一次完整准备；EXIF仍两次完整准备，首次方向、镜像及旧frame替换均正确。主代理核对了旧/新XML、源文件恢复manifest及生产diff；剩余22项为方向/裁剪临时文件/内容替换/重试/生命周期/探针端点的实际既有回归，未运行全模块。当前批次超过常规行数提示，主要由真实生产接线及边界测试构成；四个production文件属于同一读取与呈现顺序变更，不为行数拆成无法独立验收的小提交。

22项既有focused终态为19项通过、3项失败，未称全绿。随后 `reader-o1-three-old-exact` 在起点ReaderPage及两个holder、同一三个既有夹具下复跑，三项均以相同原因失败，分别是：`cancelled local header...`仍期待页表发布阶段读取header（与已采用P0契约冲突）；`old native child listener...`在实际paint准备阶段被拒绝；`ordinary single page actual pixels...`等待图像绑定5秒超时。后三项各有三次失败重试，不计为新增不同用例。主代理核对两份fresh XML的时间戳、方法及错误一致，未发现这些失败由O1引入；不为本项扩大为旧夹具修复。取消、内容替换、生命周期、真实方向/绘制等采用已通过的新增与其余既有focused组合证据，并在后续真机补受影响阅读路径；报告保留这三项既有失败，不写“22项通过”。

O1诊断候选构建 `reader-o1-benchmark-build` 成功，耗时2分46秒。APK为 `app/artifacts/android/diagnostics/reader-open-o1-20261104/Mihon-Fork-reader-open-o1-benchmark-universal.apk`，SHA256 `a78fd9065cf943b9b67dac47cd90df3aa7eef4df8152a86051ea0e0af62387a1`；实际包名 `app.mihon.desktop.fork.benchmark`、code134、非debug、原benchmark证书v2签名、R8和资源压缩均已核验。来源为起点2425aca加本任务diff，diff SHA `89b917fca12afe4c71cfddb5a8142f90ac024b5981fb65b2fb87c877c67e67ba`。主代理另重算APK SHA并检查二进制manifest；这是待测候选，不是正式发布或已采用优化。

首批A1单页两份正式原始数据（新进程首次／同进程再次）：native DOWN→FIRST_DRAW为316.732／256.276ms，VM→FIRST_DRAW为253.007／209.393ms，页表后主线程Running为61.298／48.685ms。主代理另直接核probe差值、APK身份、reader原图及初筛视频；正式reader是无方向提示的世界地图。A1双页OFF亦已核真实设置空框及左地图／右标题、5/187、无提示的正式画面。尚无完整A/B/B/A结果，不能将这些控制数据写成优化收益。

B1安装条件同样读回 `verify/install`。首组单页内部VM→draw为233.091／227.181ms，分别相对A1少19.916ms、多17.788ms；冷暖方向不一致，尚不足以判断稳定收益或暖态回归。为解决这一具体不确定性，B2/A2仅single各增加一次同进程再次打开（各opens=2），共补两份；双页两模式仍各opens=1，不额外安装或扩大矩阵。完整native DOWN、CPU及视频分析完成后再做采用判断。

采集过程保留了两类记录问题：最初漏唤醒使脚本在应用输入之前停止，保留EXCLUDED且不计样本；随后两份实际完整采样被校验器因安装条件label与capture label不同而误排除。条件本来按安装块读取，改为manifest显式关联A1安装块、核arm/SHA/执行顺序且保留原validationHistory后，原样本验证为VALID；没有改动probe/trace/MKV或伪造同名条件，也没有为这项记录错误重录有效样本。后续同协议按块关联条件。

## O11先行只读核实

直接读取本轮控制臂APK（上述SHA）的ZIP目录确认包含 `assets/dexopt/baseline.prof`（22,142字节、格式头`pro\\0/010`）和 `baseline.profm`（2,460字节、格式头`prm\\0/002`）。因此不能把“补上缺失profile”当作当前原因。携带profile不证明其覆盖当前首开路径或已经由设备应用。重装前实际filter为UNKNOWN；A1/B1/B2/A2装后条件文件均已读回verify/install，但尚未做profile干预对照，不能声称其收益。

## 收尾与未完成范围

用户于2026-11-05要求停止当前主控实施，转交新Sol会话。候选APK已存在，源码/测试留在隔离worktree且未提交，性能采用结论尚无。本文、完整计划、平板已成功操作经验作为本次文档交接提交；probe说明中的候选语义改动仍与O1代码一起保留未提交，不把未采用行为写入已完成产品契约。

当前权威源码/文档目录是`D:/Codex/worktrees/mihon-reader-o-series-20261104`；测量原件仍在原目录`.test-tmp/reader-open-o-series-20261104/measurement/`。原目录同名计划/报告及隔离前候选文件是旧副本，未覆盖；新会话必须按完整计划的路径和SHA保护规则操作。

待完成的分析：全组native/probe/CPU表、除negative_timestamp之外的trace丢失健康核验、连续原始视频PTS黑屏边界、O1采用判断及候选probe OFF阅读/翻页。原A1单页CPU分析误标sampleRole=validation，应以新文件名更正为formal，不重录原样本。manifest protocolOverrides.evidence还需核对名称：A1first/warmVmToPageListCpuMs实为PAGE_LIST→draw CPU；B1inputToStartCreateTxMs两组数字实为A1；B1warmVmToPageListMs实为PAGE_LIST→draw。修改元数据保留更正历史，不改原probe/trace/MKV。

本次中断没有触发任何全量测试、发布、推送或O2 production修改。下方设备状态只描述收尾当次；新会话重新核当前状态。

**停止点清单（2026-11-05）**：request级有效样本共16份：`o1-a1-single-retry-{first,warm}`、`o1-a1-dual-off-{first,warm}`、`o1-a1-dual-on-{first,warm}`、`o1-b1-single-{first,warm}`、`o1-b1-dual-off-{first,warm}`、`o1-b1-dual-on-{first,warm}`、`o1-b2-single-{first,warm}`、`o1-b2-dual-off-first`、`o1-b2-dual-on-first`。A2已安装控制APK，conditions确认SHA为A、verify/install，但本次中断时single-ui只有配置/安装身份/启动/详情文件，没有probe，driver.json也未生成；两条RUNNING记录不算有效。A2三模式尚未形成可用闭环，新会话只处理缺口，不重录上述16份。B1两种双页正式reader原图已由主代理独立确认左地图/右卷4、5/187、无方向提示。

O2最终只有`SharedDualPageAndroidWiringTest.kt`的10个测试及fixture草稿，未编译、未格式检查、未执行RED/GREEN；共享契约测试尚未写。精确selectors、设计风险和后续执行顺序都在完整计划内，不能把这些草稿计为通过证据。

收尾补充：Luna通过正常BACK导出了A2 request1的探针，保存为`o1-a2-single-ui/interrupted-probe-1.json`；第一段partial MKV也保留。但是缺少完整system.perfetto-trace/capture.json，request2没有采集目录，因此该块仍属残缺联合采集，不加入上述16份有效对照。不得因单独探针导出成功恢复其正式样本资格。

**最终设备收尾已完成**：测量根`o1-a2-single-interrupted.json`记录两条A2样本已排除、42事件的恢复probe及partial产物清单。隔离包配置读回`enabled=false`，cleanup label为`o1-a2-single-interrupted-cleanup`；隔离包已force-stop，平板`Asleep`且`mWakefulnessChanging=false`。无本任务主机采集/trace进程，无设备scrcpy/perfetto/隔离包进程；共享ADB server保留。实际留装为A控制APK，完整SHA仍是`259c0d98b00deeca6a1254f753f2ffe0be2e6a7d8f541db74b117de6cfdadfc6`，最近A2安装条件文件为verify/install。Sol/Luna均已停止工作，不在后台继续优化。主代理复读了中断/收尾JSON，未把排除样本计入结果。
