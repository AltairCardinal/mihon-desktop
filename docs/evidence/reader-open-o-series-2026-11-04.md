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
| O1 合并图片准备IO与Main往返 | 测量/分析收口，候选补丁归档并恢复基线 | 20（A2为隔夜补充控制） | 未证明跨模式稳定净收益；详见最终裁决 | 不采用 |
| O2 双页事实变化与冗余重建 | 10项Android/1项共享/格式与独立审查通过；12份真机对照及正常OFF往返收口 | 12 | 同值回调认证增量4→0；真实时延方向混合 | 保留低成本去冗余，不承诺稳定体验收益 |
| O3 配对恢复按需重绑 | 真实RED/GREEN、12份有效记录对照、精确恢复收口 | 12（single/dual ON） | 冗余Reload消除，但首开／近黑无稳定净收益 | 不采用门控，保留记录诊断 |
| O4 列表复用与惰性下载过滤 | 真实VM接线／格式与正常single往返通过 | 12（single/dual ON） | 初始章节查询2→1；SHOW_ALL下载判断1→0；时延／近黑方向混合 | 采用低成本去冗余 |
| O5 AUTO首次布局 | 无效single→dual已取证，候选红绿／画面通过，已恢复 | 完整性能0；构造资格见O5 | 候选消除已观察无效构造，但方向重建使完整首绘端点不能对齐 | 不采用等待，保留六槽诊断 |
| O6 邻单元准备优先级 | 红绿／独立审查／首开与有限首翻完成，已恢复 | 首开12；晚首翻4 | OFF首次395.231→400.078ms、ON356.966→373.454ms；近黑无一致改善且邻页就绪后移 | 不采用读取门 |
| O7 匹配与首图竞争 | 真实调用诊断／租约红绿／对照完成，已恢复租约 | 正式12；资格4 | UNIT前确定重叠5/6→0/6，但ON工作后移；首绘／近黑混合 | 保留诊断，不采用租约 |
| O8 索引/监听 | 六项共享／Android接线与格式、正常OFF/ON往返完成 | 正式12；另排除1／conditioning1 | 8/187页查询扫描72/35,156→0；首开无稳定改善；监听覆盖不足支撑聚合 | 采用现有索引复用，不实施章级信号 |
| O9 浮层状态订阅 | 五方法／格式与12份对照、Probe OFF正常往返完成 | 正式12；资格2 | UNIT前wrapper3→1、parent仍3；首次完整双图均值偏快，暖态变慢，受诊断开销／trace限制 | 保留窄参数与默认关闭诊断 |
| O10 Activity归因 | 未取证 | 0 | 尚未测量 | 待执行 |
| O11 profile可比性 | 实际release／benchmark profile已核，当前B9目标Reader七类未命中；设备条件待核 | 0 | profile存在不证明ART应用或首开收益 | 待收口 |

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

## O1 最终裁决（2026-11-05续跑）

**不采用。** 普通WebP首绘前少一次开流已经真实测试确认，但当前小样本没有证明跨模式稳定的体验净收益。候选单页首次较快、暖态重叠且部分较慢；双页OFF两臂区间重叠，ON候选FIRST_DRAW方向较慢。两个首次样本不能据此认定统计回归，也不能宣布稳定改善。四个production文件引入的读取身份/生命周期协调，以及EXIF可能两次完整读取的字节成本，不值得在当前证据下保留。下一项以真正已采用的R2源码为基线，不叠加O1。

### 条件、原件与限制

- 只补了A2缺少的4个请求，未重录已有16份。request级VALID按captureLabel/requestOrdinal去重为20；capture级CAPTURED不是额外样本，原输入前失败和中断联合采集仍EXCLUDED。A1/B1各6、B2/A2各4。
- A2沿用中断前已安装的A包，**没有重新安装**，因此是隔夜补充控制，不伪装成紧接B2的完整同条件安装块。现场逐driver读回SHA、独立读回OAT均与A相符（verify/install）；安装条件温度28℃，后验现场27℃，USB供电/100%保留。后验温度不代表逐样本前状态。补充条件见测量根`conditions-o1-a2-supplemental-postcapture.json`及四条样本的captureContext。该差异进一步限制稳定性能结论，不追加采样追逐显著性。
- 校验路径和A/B候选旗标的本轮误用保留validationHistory后重验；一次manifest尾部多余字面\n导致JSON解析失败，修正这两个非JSON字符并读回解析成功。原probe/trace/MKV未改，不将校验错误当作产品故障。
- 本轮Luna只提取数字，主代理独立看20段原PTS连续帧、全部较大边界图及6处更正图。近黑含少量渐隐残留、排除页码/系统栏/微小spinner；以中心内容约2/255灰度辅助统一边界，仍有约一帧判定不确定性。双页首张正确漫画即结束近黑，目标source4地图与完整双页另计；最终均确认左地图/右卷4。区间为末近黑−首近黑至首漫画−最后可辨详情的采样界限。未校准视频PTS绝对输入，不报告伪精确输入→屏幕时间。
- `native-metrics-20261104T174330Z.json`与20份`*-thread-running-formal.json`分别保留系统端点、同trace时钟映射及实际launch PID/upid/main tid裁剪统计；A1single原误标validation的文件保留，另以formal重提。完整数字另见`o1-combined-metrics-20.csv/json`、主代理联合`o1-joint-results-20.json`、`video-selections-review-final.json`；video v1/v2更正历史保留。
- 20份trace均按实际stats schema核验drop/overrun/overwrite/discard/error/data_loss/packet_loss/overflow。19份无对应非零error/data-loss/drop；A2 dual-off有`trace_sorter_negative_timestamp_dropped=2`，该份系统/CPU值加†，不能定位这两条丢弃事件对区间的影响。全部另有info级`traced_chunks_discarded=133–155`、description为空，保留计数而不等同于丢失包数，不宣布零丢失。SQL、schema及逐样本输出在analysis中。

### 完整逐样本端点（毫秒）

FIRST_DRAW/UNIT是probe绘制回调，非物理呈现；CPU是PAGE_LIST_READY→目标FIRST_DRAW/UNIT的主线程Running（双页CPU终点UNIT），不可与wall或多线程工作相加。A2双页标签没有first后缀，仍是新进程首次。

| 样本 | native DOWN→目标首绘 | →完整UNIT | VM→首绘 | 页表后main Running | 视频近黑界限 |
| --- | ---: | ---: | ---: | ---: | ---: |
| o1-a1-single-retry-first | 316.732 | — | 253.007 | 61.298 | 34–67 |
| o1-a1-single-retry-warm | 256.276 | — | 209.393 | 48.685 | 0–34 |
| o1-a1-dual-off-first | 439.849 | 440.046 | 378.222 | 192.631 | 99–133 |
| o1-a1-dual-off-warm | 352.565 | 352.707 | 305.070 | 143.233 | 84–116 |
| o1-a1-dual-on-first | 410.540 | 410.729 | 337.893 | 181.297 | 49–86 |
| o1-a1-dual-on-warm | 337.270 | 337.388 | 290.089 | 150.788 | 67–100 |
| o1-b1-single-first | 288.418 | — | 233.091 | 60.332 | 17–50 |
| o1-b1-single-warm | 275.548 | — | 227.181 | 52.655 | 17–51 |
| o1-b1-dual-off-first | 404.396 | 404.579 | 344.262 | 180.139 | 66–100 |
| o1-b1-dual-off-warm | 355.487 | 355.651 | 308.840 | 148.567 | 84–117 |
| o1-b1-dual-on-first | 426.487 | 426.682 | 355.023 | 182.937 | 100–129 |
| o1-b1-dual-on-warm | 345.219 | 345.349 | 297.584 | 136.605 | 67–100 |
| o1-b2-single-first | 307.623 | — | 245.282 | 62.209 | 17–51 |
| o1-b2-single-warm | 266.011 | — | 218.796 | 48.506 | 0–34 |
| o1-b2-dual-off-first | 437.205 | 437.461 | 378.610 | 188.881 | 115–149 |
| o1-b2-dual-on-first | 413.449 | 413.622 | 351.497 | 179.818 | 99–131 |
| o1-a2-final-single-first | 322.227 | — | 257.603 | 60.985 | 34–68 |
| o1-a2-final-single-warm | 269.318 | — | 218.368 | 45.904 | 0–34 |
| o1-a2-final-dual-off† | 407.796 | 407.951 | 346.782 | 174.478 | 83–116 |
| o1-a2-final-dual-on | 402.478 | 402.685 | 338.109 | 176.258 | 99–133 |

### 旧表三个系统端点（同一次native DOWN，毫秒）

| 样本 | →StartActivity | →performCreate | →Reader首TX完成 |
| --- | ---: | ---: | ---: |
| o1-a1-single-retry-first | 33.399 | 51.154 | 124.641 |
| o1-a1-single-retry-warm | 23.395 | 38.765 | 95.404 |
| o1-a1-dual-off-first | 27.740 | 48.695 | 120.674 |
| o1-a1-dual-off-warm | 23.231 | 38.094 | 87.518 |
| o1-a1-dual-on-first | 32.277 | 52.929 | 131.042 |
| o1-a1-dual-on-warm | 23.739 | 39.901 | 80.314 |
| o1-b1-single-first | 26.294 | 43.234 | 110.566 |
| o1-b1-single-warm | 22.511 | 40.424 | 97.063 |
| o1-b1-dual-off-first | 28.708 | 47.652 | 111.897 |
| o1-b1-dual-off-warm | 24.235 | 39.366 | 92.808 |
| o1-b1-dual-on-first | 35.910 | 56.374 | 110.966 |
| o1-b1-dual-on-warm | 23.303 | 40.151 | 79.487 |
| o1-b2-single-first | 30.543 | 50.314 | 112.523 |
| o1-b2-single-warm | 24.670 | 40.104 | 89.135 |
| o1-b2-dual-off-first | 27.642 | 45.986 | 115.754 |
| o1-b2-dual-on-first | 29.265 | 51.605 | 115.922 |
| o1-a2-final-single-first | 30.256 | 52.802 | 118.596 |
| o1-a2-final-single-warm | 28.735 | 43.938 | 109.622 |
| o1-a2-final-dual-off | 28.816 | 50.117 | 116.065 |
| o1-a2-final-dual-on | 33.172 | 52.423 | 122.790 |

### 相同探针区间（毫秒）

不比较O1前后已移动的header小段作为净收益。holder→image、image→decode、decode→draw使用共同端点；双页另列decode→UNIT。

| 样本 | holder→image | image→decode | decode→draw | decode→UNIT |
| --- | ---: | ---: | ---: | ---: |
| o1-a1-single-retry-first | 18.587 | 22.073 | 101.335 | — |
| o1-a1-single-retry-warm | 15.724 | 9.676 | 85.841 | — |
| o1-a1-dual-off-first | 35.681 | 81.504 | 145.118 | 145.315 |
| o1-a1-dual-off-warm | 18.917 | 57.322 | 135.532 | 135.674 |
| o1-a1-dual-on-first | 25.613 | 74.388 | 118.455 | 118.644 |
| o1-a1-dual-on-warm | 15.925 | 47.063 | 133.780 | 133.898 |
| o1-b1-single-first | 18.259 | 15.623 | 86.463 | — |
| o1-b1-single-warm | 10.903 | 13.761 | 105.602 | — |
| o1-b1-dual-off-first | 24.295 | 42.924 | 153.521 | 153.704 |
| o1-b1-dual-off-warm | 12.890 | 73.904 | 129.306 | 129.470 |
| o1-b1-dual-on-first | 25.535 | 41.641 | 170.161 | 170.356 |
| o1-b1-dual-on-warm | 18.173 | 42.370 | 146.398 | 146.528 |
| o1-b2-single-first | 14.124 | 24.243 | 87.119 | — |
| o1-b2-single-warm | 16.180 | 10.009 | 100.118 | — |
| o1-b2-dual-off-first | 21.929 | 64.772 | 161.998 | 162.254 |
| o1-b2-dual-on-first | 30.690 | 71.717 | 138.701 | 138.873 |
| o1-a2-final-single-first | 19.598 | 16.018 | 102.020 | — |
| o1-a2-final-single-warm | 9.801 | 12.881 | 88.341 | — |
| o1-a2-final-dual-off | 26.616 | 54.578 | 141.540 | 141.695 |
| o1-a2-final-dual-on | 28.848 | 84.083 | 107.566 | 107.773 |

### 分组判断与源码收口

- single首次：A native→draw 316.732/322.227，B 288.418/307.623；暖态A 256.276/269.318，B 275.548/266.011。首次有改善方向，暖态没有稳定改善。页表后CPU总体重叠，不把减少开流的操作证据外推成所有条件的时延保证。
- OFF首次：A 439.849/407.796†，B 404.396/437.205；暖态A/B 352.565/355.487。两臂波动重叠，无已证实净收益。
- ON首次：A 410.540/402.478，B 426.487/413.449；暖态A/B 337.270/345.219。该组方向不支持采用，但样本少且A2隔夜，不宣布统计回归。视频近黑也有明显波动，不以中位数掩盖单份长等待。
- 不把这组边际/预热后测量称为升级首开、无扰动基准、P95或性能整体达标。O1不采用，因此不为采用门禁重装B做probe OFF复核，也不扩大到既有ON回翻错单元修复。
- 最终候选8文件（7个代码/测试+probe维护说明）的完整内容、SHA及patch已存权威worktree `.test-tmp/reader-open-o-series-20261104/o1/rejected-candidate/`，恢复前后逐文件校验；原候选APK/source.diff/mapping与红绿证据保留。恢复只覆盖本任务O1所有权文件，O2测试和实现、原目录用户改动均未回滚。没有未采用产品代码提交。
- 最终设备读回probe enabled=false、隔离包force-stop、Asleep且mWakefulnessChanging=false；实际留装A控制SHA `259c0d98b00deeca6a1254f753f2ffe0be2e6a7d8f541db74b117de6cfdadfc6`、OAT verify/install。无本任务采集/trace进程，ADB server保留。

O1以“不采用且证据/补丁归档、源码恢复”的路径完成；其余清单继续执行。

## O2 最终结果（2026-11-05）

**采用低成本去冗余；未证实稳定的体验净收益。** 真实adapter的同值事实仍返回接受成功；旧实现两次同值回调使认证entries从4增至8，新实现保持4，消除了这组回调的4次真实认证入口。该计数是focused夹具中的认证工作，不是精确rebuild次数，也不外推真机整次打开的节省次数。

实现仅在当前owner还有未实际投影输入时重建，输入含layout/fact/sample/original header版本；ensure先摄入、Computed事实保留而header变化、重试、手势pending、owner替换及notify重入均有真实接线覆盖。主动导航/设置/显式重试仍走既有入口，不加异步合批或图片缓存。共享新增只读header revision不改变接受Boolean/认证/持久化语义。

### 验证、产物与条件

- 真实RED：`.test-tmp/reader-open-o-series-20261104/o2/duplicate-red.xml/log`，expected4/actual8，三个失败记录是同一方法的自动重试。最终10个O2 Android真实接线方法、1个共享header revision契约通过，app/domain格式通过；原件`o2/focused-green.xml/log`、`header-revision-green.xml`。首轮因方法名行宽失败仅缩短名称；第二轮GREEN后O1候选8文件已恢复，再运行全部10项通过。
- 独立Sol审查PASS，覆盖header所有写入、owner/window清理、手势与重入边界，没有阻塞发现。七个交付文件属于同一个事实投影批次，主体测试是10个真实adapter边界，不按行数拆成不完整协议。
- 冻结benchmark包：权威worktree `app/artifacts/android/diagnostics/reader-open-o2-20261104/Mihon-Fork-reader-open-o2-benchmark-universal.apk`，SHA `84e3be9b15c1c94acdc451a94a0e83583e43ba1a82517833588f849a564f4cb3`。code134、非debug、R8/资源压缩、原benchmark证书已核；构建2m17s，源基线220ec6f586加O2两production文件与测试diff，冻结29文件及production fingerprint。O1不在此包。后来准备的O3/O4测试/实现不属于本冻结包。
- 对照原件独立位于原目录 `.test-tmp/reader-open-o-series-20261104/measurement-o2/`，不覆盖O1。OFF/ON每模式A1首次/暖、B1首次/暖、B2首次、A2首次，共12份request级VALID；各安装块正常重装、实际SHA及OAT均verify/install，每模式一次无录制预热后强停进入首次。A2本轮确实新重装，非O1的隔夜续接。
- A1在正式采样前的准备错组、一次双预热、唤醒/参数和JSON尾字面换行错误均保留过程原件与历史；A1正式前已重新安装并恢复一次预热协议，未重录有效样本。标签/config不能代替真实设置：主代理核A2 OFF空框、ON勾选及正式原图。
- 健康信息保留：A1 ON首次、B1 ON暖态各有negative_timestamp_dropped=1；这些行加†，不能定位被丢事件影响。其余匹配error/drop等为0/null；所有样本还有info级traced_chunks_discarded=156–167，不能把它当作每份丢包数或声称零丢失。完整schema/SQL/输出在analysis中。
- 主代理独立核12段连续原PTS及较大四/五边界图；Luna另核原PTS/算术。`analysis/video-reviewed.json`保存逐帧选择和视频SHA。A1 ON暖态先出现地图，targetFrame40、完整41，其余先标题再地图。近黑由首部分正确漫画结束，视频绝对输入映射未建立；所有完整单元后至录制结束的原生帧扫描均未再近黑，中心灰度最小约83/255，仅是无再黑筛查，不等同物理呈现证明。

### 完整逐样本净端点（毫秒）

CPU是PAGE_LIST_READY→完整UNIT的main Running；target首绘与UNIT分列，尤其A1 ON暖态不可混用。

| 样本 | DOWN→target | DOWN→UNIT | VM→target | 页表后CPU | 近黑界限 |
| --- | ---: | ---: | ---: | ---: | ---: |
| o2-a1-dual-off-first | 455.663 | 455.890 | 389.113 | 174.857 | 147–184 |
| o2-a1-dual-off-warm | 350.558 | 350.708 | 300.692 | 147.963 | 83–116 |
| o2-a1-dual-on-first† | 401.730 | 401.940 | 338.140 | 172.626 | 67–97 |
| o2-a1-dual-on-warm | 308.861 | 326.085 | 263.389 | 128.247 | 50–84 |
| o2-b1-dual-off-first | 401.609 | 401.890 | 340.764 | 166.394 | 82–116 |
| o2-b1-dual-off-warm | 367.238 | 367.397 | 316.534 | 136.835 | 83–117 |
| o2-b1-dual-on-first | 394.634 | 394.880 | 332.281 | 164.080 | 67–99 |
| o2-b1-dual-on-warm† | 346.425 | 346.643 | 299.092 | 134.318 | 66–100 |
| o2-b2-dual-off | 402.932 | 403.157 | 341.485 | 170.558 | 67–100 |
| o2-b2-dual-on | 375.648 | 375.832 | 314.526 | 153.200 | 66–100 |
| o2-a2-dual-off | 411.263 | 411.479 | 347.127 | 178.858 | 83–115 |
| o2-a2-dual-on | 391.762 | 391.926 | 336.792 | 172.453 | 83–115 |

### 系统端点与相同probe区间（毫秒）

| 样本 | DOWN→StartActivity | →performCreate | →Reader TX | holder→image | image→decode | decode→target | decode→UNIT |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| o2-a1-dual-off-first | 34.318 | 55.965 | 118.525 | 20.424 | 95.084 | 139.766 | 139.993 |
| o2-a1-dual-off-warm | 27.244 | 41.035 | 99.282 | 14.550 | 55.416 | 130.540 | 130.690 |
| o2-a1-dual-on-first | 30.767 | 51.230 | 122.699 | 23.852 | 66.361 | 134.434 | 134.644 |
| o2-a1-dual-on-warm | 21.626 | 37.802 | 79.544 | 11.064 | 41.051 | 111.449 | 128.672 |
| o2-b1-dual-off-first | 29.729 | 48.879 | 111.710 | 35.572 | 50.062 | 134.786 | 135.067 |
| o2-b1-dual-off-warm | 23.738 | 42.414 | 101.857 | 16.408 | 34.092 | 156.963 | 157.122 |
| o2-b1-dual-on-first | 30.856 | 52.505 | 113.307 | 18.557 | 46.815 | 143.325 | 143.571 |
| o2-b1-dual-on-warm | 24.017 | 39.006 | 87.099 | 13.591 | 58.039 | 122.995 | 123.213 |
| o2-b2-dual-off | 30.740 | 48.343 | 111.752 | 18.845 | 56.898 | 136.613 | 136.838 |
| o2-b2-dual-on | 29.876 | 48.819 | 115.061 | 27.740 | 45.417 | 115.002 | 115.186 |
| o2-a2-dual-off | 27.904 | 49.523 | 125.935 | 28.068 | 48.204 | 134.044 | 134.259 |
| o2-a2-dual-on | 26.764 | 43.130 | 103.268 | 21.230 | 55.005 | 133.044 | 133.208 |

### 分组与正确性裁决

- OFF首次target：A 455.663/411.263，B 401.609/402.932；首次CPU A174.857/178.858，B166.394/170.558，有较快方向。暖态A/B target 350.558/367.238，候选反而多16.680ms。
- ON首次target：A401.730†/391.763，B394.634/375.648，候选有较快方向；暖态A/B target308.861/346.425†，UNIT326.085/346.643†。不能用A1提前画出地图的target直接替代完整双页，也不忽略暖态变慢。
- 每臂两个首次、一个暖态且trace有上述限制，方向混合，小样本不能宣称稳定净收益或P95。保留依据是明确低成本去冗余、原接受/生命周期语义保持、真实接线及正常OFF路径正确；不为追显著性无限追加样本，不把各优化数字相加。
- 关闭probe的候选正常OFF核验，主代理先以实际空复选框确认OFF：起始5地图左/标题右→6角色/目录→返回5原双图，0.7s后仍同图；正常退出详情第5页、再打开仍同图。`correctness-root-b2-off/reader-open|return|return-stable|reopen.png` SHA均`b8defc586a6cf2f6ab6a65c92b03aaec39c8d88374437cc102747a91ee7face4`。此为实际画面稳定旁证，不冒称native IDLE。
- 先前`correctness-b2-dual-off`仅目录标签OFF，脚本未切实际开关，前置B2 ON设置一直保持ON。其5→6→4、左标题右蓝页记录已明确更正为**实际ON**，撤回OFF回归说法；不能拿探针enabled=false当匹配OFF。该ON现象与已有同一A/R2控制及此前候选的异常一致（既有P4/本报告O1交接证据），保留为未修复的既有ON边界，不宣称正常ON往返通过，也不扩大修配对算法。
- 最终两份A2原图仍与固定正确双图SHA一致。`root-final-cleanup/verified.json`实际读回probe=false、Asleep且mWakefulnessChanging=false，实际留装A控制259c0d98…dadfc6；无本任务采集进程，ADB server保留。

采用不等于性能整体达标、正式发布或用户验收。后续以本O2版本作为已采用基线；O3当前仅TDD完成，尚未测量/提交。

## O3 当前取证：成功恢复后的实际 viewer 门控

真实 single 首绘后释放成功恢复的 RED：预期 Reload 0，实际 1。四项新接线和五项既有恢复/窗口回归共9项行为 GREEN；该轮最终仅格式失败，随后单独 app 格式通过，组合证据不冒称一次执行全通过。进一步记录存在性诊断的真实 RED 后，最终同一轮 Android 5 项接线、Probe 1 项与 app 格式全部通过（`reader-o3-record-green2`）。证据在隔离工作区 `.test-tmp/reader-open-o-series-20261104/o3/`。

生产候选只在后台成功恢复末尾按完成时真实 `DualPageR2LPagerViewer` 保留 Reload；普通单页/条漫保留恢复数据、可用性与错误反馈，AUTO 按实际 viewer 判断。三种固定记录 marker 在真实 acceptRestore 成功后分类同一 snapshot，不增加仓库 I/O，捕获原 request；无 marker 不能推断空记录。

A3/B3 诊断一致，源码清单只 ReaderViewModel 不同，A3 临时撤掉门控后按备份哈希精确恢复候选再构建 B3。A3 SHA `629de93bf8d7532af91e95e6dec67611505a0834f8fd268fd5392138bfdf7982`；B3 SHA `a2f42a3d9c2d55a3720dbd467f2f02befbf5ce7b65fff7583f82e84a591a5993`。两包 code134、非调试、R8、同原 benchmark 证书，source capture 与 artifact 清单保留在 `app/artifacts/android/diagnostics/reader-open-o3-{a,b}-20261104/`；不含 O4 production。尚未完成正式采样、采用裁决或提交，不能视为 O3 完成。

### O3 最终裁决：本轮不采用重载门控

普通成功恢复后的冗余Reload消除已由真实RED/GREEN证明；12份有效保存记录样本未证明稳定首开收益，双页组的时延和部分CPU结果向慢。不能将双页变慢确认为门控所致（双页分支仍保留Reload），也不能未经证实全部归为波动。本轮不采用门控，不追加样本追显著性；保留候选及测试证据，后续O4以真正采用的O2加相同记录诊断为基线。记录诊断本身保留用于区分真实接受的有效非空、空及无效snapshot，不改变业务恢复或增加仓库I/O。

采样：A1/B1各模式首次与再次，B2/A2各模式首次；single和dual-on各6份，共12份按capture/request去重的VALID。每块正常保留数据重装，实际SHA和OAT均独立读回为verify/install，每模式一次probe OFF预热。仅测安装后已预热的新进程首次，不是升级首次或冷磁盘。两臂均为code134、同证书/R8/非debug，两臂诊断一致，唯一生产差异是O3门控。原件位于原目录`.test-tmp/reader-open-o-series-20261104/measurement-o3/`；combined CSV/JSON、完整health CSV和native/thread提取原件保留。

原测试章节最初真实接受EMPTY（`o3-qual-single-probe-ui/probe-1.json`）；通过正常Adjust page pairing两次保存、正常单页7→6→5导航恢复目标，随后`o3-qual-valid-single-ui/probe-1.json`确认VALID。前置变化两臂共享，不与O2空记录组混算收益。中间双页资格检查因4/187页码不符被拒，未列为性能样本。第一正式`o3-a1-single`在计时后PNG拉取20s超时，未完成正常onStop导出，两预注册请求均EXCLUDED；capture原件保留。无已知残留进程，连接/内存检查无已确认故障，同一PNG随后0.146s拉取成功，只能确认一次超时，原因未明。新label `o3-a1-single-r1`重试取得两份VALID，未覆盖旧尝试。

时间单位ms。表中DOWN是原生`deliverInputEvent src=0x1002`（同trace时钟映射）；VM是`OPEN_READER_INTENT`。Probe MAIN_TOUCH_DOWN另列在原CSV，不混称同一起点。CPU为页表就绪至single FIRST_DRAW或dual UNIT_DRAWN的主线程Running，不能与wall相加。近黑由主代理逐段看连续原PTS帧，沿用中央灰度约2/255近黑约定，约一原生帧不确定性；首部分漫画即结束近黑，完整单元单列，视频PTS不映射为输入至物理屏幕时长。

| 样本 | DOWN→target | DOWN→UNIT | VM→target | 页表后CPU | 近黑界限 |
| --- | ---: | ---: | ---: | ---: | ---: |
| o3-a1-single-r1-first | 305.495 | — | 244.899 | 61.343 | 17–50 |
| o3-a1-single-r1-warm | 290.041 | — | 245.579 | 67.030 | 34–69 |
| o3-a1-dual-on-first | 351.769 | 368.708 | 295.324 | 146.518 | 67–100 |
| o3-a1-dual-on-warm | 326.898 | 327.077 | 277.934 | 144.741 | 16–50 |
| o3-b1-single-first | 311.791 | — | 255.270 | 55.798 | 33–69 |
| o3-b1-single-warm | 253.431 | — | 207.728 | 46.478 | 0–33 |
| o3-b1-dual-on-first | 376.868 | 377.073 | 322.132 | 167.510 | 84–116 |
| o3-b1-dual-on-warm | 350.346 | 350.500 | 303.921 | 139.055 | 67–99 |
| o3-b2-single | 309.679 | — | 254.725 | 56.763 | 68–105 |
| o3-b2-dual-on | 394.868 | 395.145 | 333.038 | 170.514 | 49–83 |
| o3-a2-single | 288.098 | — | 232.423 | 59.224 | 34–68 |
| o3-a2-dual-on | 369.948 | 386.671 | 307.564 | 152.315 | 84–116 |

| 样本 | DOWN→StartActivity | →performCreate | →Reader TX | holder→image | image→decode | decode→target | decode→UNIT |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| o3-a1-single-r1-first | 31.499 | 50.300 | 111.010 | 15.130 | 18.905 | 91.361 | — |
| o3-a1-single-r1-warm | 18.982 | 36.390 | 75.631 | 35.180 | 26.039 | 95.648 | — |
| o3-a1-dual-on-first | 25.954 | 45.888 | 109.646 | 22.839 | 39.407 | 112.606 | 129.544 |
| o3-a1-dual-on-warm | 21.834 | 40.389 | 98.088 | 18.326 | 72.530 | 98.575 | 98.754 |
| o3-b1-single-first | 24.953 | 43.574 | 106.641 | 16.567 | 23.337 | 97.466 | — |
| o3-b1-single-warm | 23.045 | 38.185 | 94.170 | 9.760 | 17.217 | 86.791 | — |
| o3-b1-dual-on-first | 24.290 | 42.878 | 93.026 | 23.956 | 54.861 | 126.660 | 126.865 |
| o3-b1-dual-on-warm | 22.934 | 38.746 | 86.134 | 17.931 | 66.424 | 126.584 | 126.739 |
| o3-b2-single | 26.828 | 43.257 | 105.951 | 18.584 | 18.021 | 104.754 | — |
| o3-b2-dual-on | 27.891 | 47.978 | 116.482 | 20.855 | 45.365 | 139.994 | 140.271 |
| o3-a2-single | 27.094 | 44.261 | 110.000 | 19.065 | 21.489 | 85.241 | — |
| o3-a2-dual-on | 29.699 | 50.270 | 124.916 | 34.083 | 41.868 | 114.106 | 130.830 |

- single首次A 305.495/288.098，B 311.791/309.679；暖态A290.041、B253.431。页表后CPU首次A61.343/59.224，B55.798/56.763，局部CPU减少未转为稳定首绘收益。
- dual首次target A351.769/369.948，B376.868/394.868；完整UNIT A368.708/386.671，B377.073/395.145；暖态UNIT A327.077、B350.500。首次CPU A146.518/152.315，B167.510/170.514。不得报告双页无回归，也不据小样本确认因果或P95。
- 双页A1首次和A2首次先画出目标地图，target领先完整单元约16.9/16.7ms；其余双页先标题后地图，完整单元不等于首部分漫画。12段从full至视频末的逐帧中央ROI筛查均无再次近黑，帧数与原PTS逐项核对，仅作screening旁证。
- 完整health每trace194行，12份未见非零error/data_loss severity或非info丢弃项；info `traced_chunks_discarded`169–180原样保留，不能称零丢失或把该值当packet loss。
- B3在probe OFF下single 5→6→5→退出详情第5页→重开5通过，opened/returned/reopened PNG SHA均`3ecbd2ce09a1eef7c019edb2cf652894a03e2f6c811e0c964ba05560a03b6b95`。根已独立核页面，像素相同仅作画面一致旁证。最后留装B3，probe=false、Asleep/Changing=false；已知ON往返边界仍沿用既有记录，不声称本轮修复。

门控已按精确边界撤回；四项gate测试与专用helper归档于`o3/rejected-gate-tests.kt`，SHA256 `08cfe16736cbaebe13f9d6d1de3117ec72112690792c3784739c32fe0057acf4`。无gate的三项真实record wiring、一项Probe slots及app格式通过，最终仅移除无引用import后单独格式复验通过；O4六项组合结果不受此尾部恢复影响，可复用。独立PTS算术核验`analysis/o3-video-pts-verification-12.json`为12/12通过。O3与O4内聚初始化批次一同提交并checkoff。本裁决不表示整体首开体验达标或用户验收。

## O4 候选：初始化查询与下载判断

真实ReaderViewModel.init RED确认GetChaptersByMangaId调用2次而要求1次；SHOW_ALL单章仍调用DownloadManager一次而要求0。两方法自动各重试三次是同两项RED，不是六种行为。最小实现复用第一次原始Domain章节列表；仍在原子openChapter后按opened.manga和opened.chapter处理，catalog refresh不使用该初始列表；下载状态只在实际下载过滤时求值，独立downloadedOnly/DownloadCache链不变。真实SQL权威数据与0/300额外章节、10个下载过滤组合、catalog成功/失败及共享skip接线组成6方法focused。

最终GREEN为组合证据：green2的SHOW_ALL、catalog成功、catalog失败、shared skip四项通过；另外两项失败来自DbChapter.last_page_read的Int/Long期望和InMemoryPreferenceStore未注册skip_filtered夹具。仅修夹具后两项及app格式通过，production未因此改变。不冒称最终代码重新跑整六项，也未执行全模块/全仓库。撤O3尾部gate不影响O4列表/过滤路径，此六项证据可复用；同诊断无gate的4项record/Probe补验及格式亦通过。原件在隔离目录`o4/`。

B4构建2m3s，APK `app/artifacts/android/diagnostics/reader-open-o4-20261104/Mihon-Fork-reader-open-o4-benchmark-universal.apk` SHA `e7ec6fdaf30d7dbbcd2dc09722701c4618ce24573acceeaf6d214ff60a35a03f`；A4复用A3 SHA `629de93bf8d7532af91e95e6dec67611505a0834f8fd268fd5392138bfdf7982`。两臂均无O3gate，同record诊断；`o4/a3-to-b4-production.diff`核对唯一产品差异为O4查询复用和按需下载判断。B4冻结28个源码文件及production指纹，O5后续诊断测试草稿不在其冻结输入。当前12份正式样本尚在完成，不预先宣布采用/收益或checkoff。

### O4 测量与裁决：采用低成本去冗余，未证实稳定首开收益

沿用独立measurement-o4目录。A1/B1各模式首次和再次、B2/A2各模式首次，共12份去重VALID；每块正常保留数据重装后读回实际SHA/OAT为verify/install，再每模式一次probe OFF预热。真实有效保存record与O3准备后同一数据，两臂同诊断且均无O3gate。O4无EXCLUDED采集，未追加择优样本。仅覆盖安装后已预热的新进程首次，不是升级首次、冷盘或P95。

采用依据是低成本、局部、明确的真实调用去冗余和语义保护：RED单章查询2次/下载判断1次，GREEN在0和300额外章节时初始化查询均1次、SHOW_ALL下载判断均0；较大列表的旧实现没有独立RED计数，不将推导301次写成实测。原子opened权威、目录刷新再次查询、下载过滤和独立downloadedOnly原链均有接线验证。本轮时延/CPU方向混合，不能宣称稳定首开加速；与O3持续向慢的双页结果分别裁决，不把功能调用减少自动换算为体验收益。

下面单位ms，DOWN仍为原生deliverInputEvent，VM为OPEN_READER_INTENT；Probe MAIN_TOUCH_DOWN另列原CSV。CPU为页表后至single FIRST_DRAW/dual UNIT_DRAWN的主线程Running。†为trace质量受限：完整health每份194行；A1 dual首次丢2、B1 dual首次丢1、B1 dual再次丢1、A2 dual丢2个negative_timestamp，计4/12 trace、6条。尚未证明是否与端点/CPU范围重叠，不把它们默认为无影响，也不仅凭该计数重录。12份无非零data_loss级统计；info traced_chunks_discarded181–192保留，不能称零丢失。

| 样本 | DOWN→target | DOWN→UNIT | VM→target | 页表后CPU | 页表后shader | 近黑界限 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| o4-a1-single-first | 325.868 | — | 264.981 | 60.628 | 0.000 | 35–69 |
| o4-a1-single-warm | 255.998 | — | 210.081 | 50.699 | 0.000 | 0–36 |
| o4-a1-dual-on-first† | 351.353 | 351.663 | 295.547 | 152.527 | 0.000 | 34–66 |
| o4-a1-dual-on-warm | 356.301 | 356.472 | 310.885 | 149.953 | 0.000 | 67–100 |
| o4-b1-single-first | 330.082 | — | 272.755 | 61.912 | 0.000 | 66–98 |
| o4-b1-single-warm | 274.585 | — | 231.753 | 51.604 | 0.000 | 18–52 |
| o4-b1-dual-on-first† | 386.884 | 387.096 | 322.991 | 167.489 | 0.000 | 33–67 |
| o4-b1-dual-on-warm† | 331.661 | 331.817 | 282.861 | 132.058 | 0.000 | 33–66 |
| o4-b2-single | 288.519 | — | 231.575 | 59.593 | 0.000 | 17–50 |
| o4-b2-dual-on | 394.609 | 394.820 | 336.703 | 170.631 | 0.000 | 50–84 |
| o4-a2-single | 334.306 | — | 259.917 | 67.264 | 0.000 | 33–67 |
| o4-a2-dual-on† | 390.904 | 391.190 | 331.724 | 176.774 | 0.251 | 67–100 |

| 样本 | DOWN→StartActivity | →performCreate | →Reader TX | holder→image | image→decode | decode→target | decode→UNIT |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| o4-a1-single-first | 24.669 | 44.556 | 120.714 | 16.969 | 23.952 | 94.974 | — |
| o4-a1-single-warm | 23.075 | 37.253 | 95.455 | 14.554 | 17.738 | 92.285 | — |
| o4-a1-dual-on-first† | 25.719 | 45.579 | 110.399 | 20.514 | 45.411 | 107.612 | 107.922 |
| o4-a1-dual-on-warm | 23.436 | 37.792 | 91.476 | 21.510 | 83.187 | 112.074 | 112.245 |
| o4-b1-single-first | 23.827 | 46.253 | 117.757 | 22.667 | 28.756 | 92.319 | — |
| o4-b1-single-warm | 20.043 | 35.705 | 83.970 | 18.742 | 36.514 | 87.269 | — |
| o4-b1-dual-on-first† | 32.861 | 51.919 | 122.774 | 30.708 | 72.609 | 103.731 | 103.942 |
| o4-b1-dual-on-warm† | 23.676 | 41.041 | 101.135 | 16.922 | 43.670 | 118.639 | 118.794 |
| o4-b2-single | 27.771 | 46.911 | 105.898 | 18.390 | 15.621 | 86.394 | — |
| o4-b2-dual-on | 24.731 | 45.131 | 113.091 | 26.065 | 51.471 | 128.271 | 128.483 |
| o4-a2-single | 31.411 | 52.140 | 124.248 | 33.633 | 34.415 | 86.459 | — |
| o4-a2-dual-on† | 29.913 | 48.605 | 100.965 | 19.917 | 55.604 | 131.845 | 132.131 |

- single首次A325.868/334.306、B330.082/288.519，一慢一快；暖态A255.998、B274.585。首次CPU A60.628/67.264、B61.912/59.593，不能只选B2报告稳定改善。
- dual首次target A351.353†/390.904†、B386.884†/394.609；UNIT A351.663†/391.190†、B387.096†/394.820，首次候选较慢；暖态UNIT A356.472、B331.817†方向较快。首次CPU A152.527†/176.774†、B167.489†/170.631方向混合。小样本和上述trace限制不足以定位变慢原因或证明无回归。
- 主代理逐段核12段连续原PTS帧：single均为正确地图；dual均先显示右标题，再补目标地图与完整双图。首部分漫画即结束近黑，不把等地图的时间继续算黑。近黑约定及一原生帧误差沿用O3。full至末每帧中央ROI screening均无再近黑，不能冒称物理显示证明。
- 候选probe OFF正常single 5→6→5→退出详情第5页→重开5通过；opened/returned/reopened PNG SHA同为`3ecbd2ce09a1eef7c019edb2cf652894a03e2f6c811e0c964ba05560a03b6b95`，前翻画面不同。最终probe=false、Asleep/Changing=false，留装B4，仅本任务隔离包；未修改官方/正式fork。

本批产品变更、取证及测试集中在Reader初始化/恢复边界，文档同步说明；文件数含必要接线/诊断/报告，未扩展其他能力。O3不采用门控、O4采用去冗余与同诊断保留将按B4冻结source.diff提交，不带入后续尚未验证的O5草稿；不推送、不正式发布，不更新公共功能说明。

O4独立PTS索引/区间算术核验为12/12 PASS（`analysis/o4-video-pts-verification-12.json`），不替换主代理画面分类。

## O5 实际AUTO构造取证与候选准备

仅取证机制先取得真实RED（实际toViewer已构造但缺marker），7项真实Activity/Probe/viewport/可读性focused及格式通过。B5 probe-only APK SHA `7c0fdd5232cace04d2ffc1cdb455c4adba437cc5c5924f1c91bbbff3ab75c98a`，sourceRevision为O4提交a0320af1b5，code134/原benchmark证书/R8/非debug，冻结27源码文件；没有AUTO优化。原件在原目录`measurement-o5/`及隔离目录`o5/`。

通过正常设置确认AUTO、横屏、matching ON，设备Main实际1920×2880 portrait，Reader为2880×1920 landscape。第一次方向切换焦点短暂null，前置守卫停止并保留prepare-auto-landscape；仅增加O5脚本对null焦点的1秒有界只读等待，意外其它应用仍立即拒绝，未重发方向输入。第一资格脚本又在章节点击前因辅助display grep无匹配退出，该attempt保留，不是性能/构造样本。

两次新进程真实正常点击均触发同request的实际构造：`o5-auto-landscape-r1/probe.json` request1 SINGLE_CREATED_INVALID 173274890266267→DUAL_CREATED_LANDSCAPE 173274999996475（109.730208ms）；`o5-auto-landscape-2/probe.json` request1对应173516843423001→173516947249042（103.826041ms）。这是两类真实构造之间的wall间隔，不是构造CPU耗时；固定slot不能统计精确总构造次数。实际设置和漫画图已由主代理看图确认，均为地图左/标题右、5/187。

方向改变期间ReaderActivity发生重建：新onCreate分配request2，而保留VM/页表仍来自旧请求，原request1到PAGE_LIST_READY止，新request2有ACTIVITY_CREATE/AUTO_DUAL_CREATED_INVALID/PAIRING_RECORD_VALID但缺完整FIRST_DRAW/UNIT。r1最初脚本因要求完整首绘而拒绝；原失败及raw保留，另写construction-evidence-review.json只确认构造证据，不改成有效性能样本。第二次脚本明确只核构造范围，未录屏/Perfetto，不计算输入至首图。不能跨request拼接或把正常画面当完整探针。最终Probe OFF且Asleep/Changing=false。

最小候选仅针对本Activity尚无installedViewer的非MaterialContainerTransform路径；先请求原方向，再等待有效匹配视口，同layout立即恢复，不用延时/新帧。MCT保留原构造→doOnEnd方向→startPostponed流程。真实RED已证明旧production等待时仍安装一次viewer，候选实现/生命周期focused尚在验证，不能提前采用。审查要求覆盖首个有效单页、保留VM旧viewer、加载反馈、最新章节、监听取消及同layout双回调。

另据[Android 16官方行为说明](https://developer.android.com/about/versions/16/behavior-changes-16#adaptive-layouts)，target36大屏系统可能忽略应用方向限制；实际B5 merged manifest target36且无相关optout。候选不能要求系统永远服从方向请求，否则多窗口或大屏会一直加载；该直接风险正用定向RED/GREEN处理，不通过改manifest规避。此处是平台边界证据，不是新性能收益或公共功能承诺。

### O5 最终裁决：保留取证，不采用启动等待

候选真实行为RED为初始viewport无效时viewerContainer实际安装1次而要求0；多窗口/API36大屏/窄content另有候选RED，default继承AUTO等待改global manual也另取真实RED。候选12个唯一focused方法以初次/窗口/销毁/default三项补验等组合通过并有app格式，未重新全跑，也没有模块/全量。夹具DI顺序、等值StateFlow与partial Activity缺SavedState/Fragment初始化导致的失败原件保留，均未算产品RED。API36策略在现有API35 fixture暂设SDK_INT=36并finally恢复以执行production判断及Activity接线，不冒称API36系统实测；MCT真实Transition listener用fixtureSDK35验证，未冒称API26硬件验收。

Root独立审查关闭了多窗口/大屏方向请求忽略造成死等、窄content、等待Reload触碰旧VM viewer/丢spinner、首个单页同false不唤醒、同layout旧listener重入、手动/销毁、DEFAULT全局订阅遗漏等直接风险。普通方向匹配不再强制content轴，系统忽略方向时按正尺寸viewport；MCT明确保持旧流程。未添加postDelayed或调度整帧，但这不证明不会发生额外布局/等待帧。

候选APK SHA `568689140a499d39e4b209ba3b5e1d8afb6374e0946c5efd3310cdb73dc8c0b9`，位于`app/artifacts/android/diagnostics/reader-open-o5-startup-20261104/`。相对probe-only控制，冻结27源码中仅Activity生产实现与PairingTest变化，Probe六槽及其它源码相同。两次正常新进程`o5-startup-1/2`均在request1/2各见AUTO_DUAL_CREATED_LANDSCAPE，未见旧INVALID/SINGLE事件，真实地图左/标题右与5/187恢复正确、正常Back导出，Probe关闭且Asleep/Changing=false。固定槽只记各类首次，不能由此给精确总构造数；两臂资格流程未建立完整相同ART/缓存性能协议，因此只报告构造/画面事实，不相减间隔当净收益。

**不采用理由**：构造变化已证实，不能说候选无效；但当前方向重建场景没有可对齐的完整输入→目标首绘/UNIT端点，未证明完整首绘净收益或无额外等待帧。新增方向、加载反馈、监听取消与平台例外的生命周期成本不能仅凭去掉已观察无效构造来接受。本轮停止追加当前不能完整对齐的样本，不将未测时间写零；未来取得可信跨Activity输入/目标像素端点后可重新评估，不是永久禁令。

候选两文件及优化/逆diff归档`o5/rejected-startup-candidate/`，优化diff SHA `8105d5a124bfd16f78100cdf4384ff65b29e0934303b3a46239335895cec475a`；restoration-verification.json SHA `ad3863f5627561c2688ef2c9052dcc5941d99d891caa619a94b71eba2b781a86`。核原最终SHA后仅应用两文件的优化逆diff，无checkout/reset；四个O5源逐SHA精确恢复B5 probe-only，production指纹回到`dd0a4130b5b238d7c5c2b44030c69bedb75ccc48d7478191de574d05e09912d5`，O4不变。复用该完全相同源码的原7focused GREEN/格式，撤回仅作diff/SHA只读核验，不冒称新执行。六诊断槽及维护边界留在本批提交，不保留startup等待代码/预期测试。

### O6/O7 既有日志取证入口（未实施候选）

在Gradle暂停窗口只读拉取隔离包现有reader-0.log（3,119,725字节/13,193行），其它三个滚动日志不存在，保留stat/pull记录；全程不亮屏/输入，读回Asleep/Changing=false。流式分析O2的6份OFF与O4的6份ON各自有效样本，ACTIVITY_CREATE±3ms内reader_start全部唯一匹配且差值<1ms，按同System.nanoTime毫秒量化到UNIT窗口。12份各有6组holder/source/generation对应的read_start/complete；source3/4各1，其它source1/2/5/6共4次，全部complete在严格内窗。rebuild visible=3,4和ticket_bound原样保留，但read事件没有原子current/visible字段，只称非目标单元读取，不据此宣称严格屏外或精确并发。两个历史组代码/时序不同，不比较模式耗时或池化收益。

汇总`measurement-o6-evidence/analysis/reader-diagnostic-read-window-review.json`含原始窄证据/日志SHA/完整性限制。未见显式dropped/write_failure不等于证明日志零丢失；没有原图sampling start/complete marker，prepare.sample=true不当采样调用，因此O7采样竞争和OFF采样仍未证实。O6将仅评估延后非目标display Buffer读取，原loader/header/sample保持原链；hasRenderedVisiblePages是ALL，不作唯一放行门，必须保护部分绘制/错误/取消及第一次翻页就绪。

### O6 候选红绿与冻结（尚未采用、尚无设备收益）

补充既有日志`analysis/reader-holder-drawn-next-unit-decode-ready.json`：12份最早目标accepted drawn均为source3，之前四份非目标读取已complete；source5/6各首个accepted decode_ready均能按holder/source/generation匹配，在ACTIVITY_CREATE至UNIT+1500ms内存在。两者较晚ready相对MAIN_TOUCH_DOWN为337.561–437.929ms，相对UNIT为−17.617至+25.575ms。decode_ready是原生onImageLoaded接受回调，不是物理显示或首翻终点；不跨O2/O4代码组解释模式收益。

真实RED在挂载的pager记录`read_start`，目标3/4尚无accepted draw时实际邻页5/6/1/2已开始完整display Buffer读取（`o6/read-order-red.xml/log`）。最小候选只在header/身份校验之后、完整Buffer读取之前，让非当前单元于Main可取消等待；当前真实单元通行。每Viewer仅首次一次，任一当前accepted子图draw或可见错误即放行，不等完整UNIT；初始chapter/owner/location/frame/content变化撤销优先，destroy取消并拒绝迟到内容事件，解除后清理初始快照引用。等待后重核attachment/content/encoded/generation，不修改普通loader、采样、共享配对、进度结算或原有post入口。

组合focused证据为3个O6新方法与4个既有Layout回归。`reader-o6-final-focused-green`中的初次读取/预就绪后真实首翻回翻，以及部分绘制后立刻Queue、status/decoder失败、双图绑定前错误和成功槽保留retry两个方法通过；取消/导航/内容替换方法最后在`reader-o6-cancel-final-green`与格式检查通过。原4个Layout回归通过于`reader-o6-focused-green2`，未因收尾重新全跑。同步ANY出口另有真实Canvas draw→Queue的RED（`o6/draw-event-red.xml/log`），实现只同步释放等待信号，原post结算不变；destroy迟到事件及未建owner过早放行亦保留`o6/lifecycle-red.*`证据。

既有Shared detached capability用例在候选及HEAD production下均失败，基线原件`o6/detach-baseline.xml/log`，不纳入修复。新取消夹具曾把移除后合法重挂产生的新任务也拒绝，并额外要求票据持续null；最终按本项真实契约核对取消前旧holder/source/generation三元组不能开始迟到read，允许合法新任务，不把无关票据边界改成O6功能。首次编译nullable frame及测试等待checkpoint失败原件保留；上述组合证据不是最终源码重跑所有7方法。

主代理独立审查两production文件及新测试，`git diff --check`通过。三个文件共428行新增/1行删除，主要是同一准入边界的真实诊断/原生Canvas/生命周期夹具；保持内聚，不拆出新框架或能力。冻结product指纹`acf19ec0ef85d24934c47d1826d92f3c24be62cabae50d8cfb8d3e5b012f8c48`，`reader-o6-benchmark-build`于2m11s通过。冻结前后逐SHA一致；B6 APK为`app/artifacts/android/diagnostics/reader-open-o6-20261104/Mihon-Fork-reader-open-o6-benchmark-universal.apk`，SHA `db7bfd391ff31e9ab02f8bae71b09e0dad6728df2667083d8a8b88382d9e68cc`，code134、原benchmark证书、R8/资源缩减/非debug。A6沿用B5 probe-only APK，独立`measurement-o6/`准备OFF/ON各6份。APK冻结不代表采用、首开改善或首翻无代价。

O6设备预检重新确认USB PCE-W30/API31、secure=false及Asleep。A1正常重装A6后实际ART为verify/install，OFF两份已VALID；ON预热后一次正式唤醒前置检查在正常dismiss返回0.4s后仍showing=true而停止，尚未登记性能样本。随后仅只读复核已showing=false；保留`o6-a1-dual-on-wake/commands.json`和`settled-readback.json`，未归因应用故障。前置脚本仅将dismiss后的只读等待改为6×200ms，不重复唤醒或解除输入；新标签`o6-a1-dual-on-r1`复用已完成的一次预热，两个request均VALID，不覆盖失败原件或重录OFF。脚本旧/新SHA与失败边界写入manifest；正式计时入口不变。

## O6 最终裁决：不采用初次显示读取门

同一A6/B6诊断、187页CBZ及目标source4世界地图；A1/B1每模式首次+同进程再次，B2/A2每模式各首次，共12个VALID request。每安装块均正常重装并读回verify/install，每模式Probe OFF预热一次；不是冷盘、升级后首次或P95。A1 ON唤醒失败使用已记录的新r1标签，其余不重录。12份目标区域与主代理审阅的地图左/标题右参考逐像素匹配。

下表单位ms。输入是原表最早native触摸deliver事件；另补12/12实际launch PID主线程身份核验，均与原选中时间戳完全一致（`analysis/o6-startup-input-identity-12.json`）。目标4/UNIT是接受的应用绘制回调，非物理显示；首部分漫画与完整双页分别按原视频帧/PTS看图，视频PTS不映射到输入时钟。

| 样本 | target4 | UNIT | 主线程Running至UNIT | 视频近黑采样界 |
|---|---:|---:|---:|---:|
| A1 OFF 首次 | 393.126 | 393.332 | 250.993 | 50–83 |
| A1 OFF 再次 | 330.170 | 330.368 | 200.321 | 16–50 |
| A1 ON 首次 | 358.240 | 358.489 | 228.170 | 33–67 |
| A1 ON 再次† | 297.487 | 314.690 | 179.885 | 33–67 |
| B1 OFF 首次† | 387.667 | 387.855 | 241.988 | 85–116 |
| B1 OFF 再次 | 339.536 | 339.717 | 198.257 | 17–50 |
| B1 ON 首次 | 377.432 | 377.639 | 246.926 | 50–85 |
| B1 ON 再次 | 336.453 | 336.701 | 204.515 | 33–67 |
| B2 OFF 首次† | 412.490 | 412.711 | 262.076 | 65–101 |
| B2 ON 首次 | 369.476 | 369.702 | 246.838 | 33–66 |
| A2 OFF 首次† | 397.336 | 397.546 | 255.661 | 66–100 |
| A2 ON 首次 | 355.692 | 371.716 | 236.028 | 66–99 |

完整逐样本、8个独立分组及两对first差值见`analysis/o6-startup-comparison.json/.csv`。每arm/mode首次n=2、再次n=1；描述均值：OFF首次target4为395.231→400.078、UNIT为395.439→400.283，两个first的target4差为−5.459/+15.154；ON首次target4为356.966→373.454、UNIT为365.102→373.671，target4两对均向慢（+19.192/+13.784），但第二对UNIT略快2.014。再次OFF target4为330.170→339.536，ON为297.487→336.453。近黑未形成一致改善，不把这些小样本外推为稳定退化或收益，不叠加不同阶段百分比。

**健康统计更正**：旧query/解析每trace仅保留41项，原`o6-native-cpu-health-12.json`中“非零均0”不完整，不能引用为完整健康证明。新`analysis/o6-startup-nonzero-health-12.json`与逐trace原件通过`--no-agent`重新窄查：12份`traced_chunks_discarded`为info/source=trace、累计193–204，不直接视为本次计时丢包；†四份`trace_sorter_negative_timestamp_dropped`依次为A1 ON再次1、A2 OFF1、B1 OFF首次1、B2 OFF2。其它样本未返回该非零项；筛选所得其它非零/error/data_loss为空，android_logs_total均0。所选输入和外部draw日志/时钟对齐完整，但异常原件仍保留，不保证CPU统计无遗漏，不依其细小差值宣称优化，也不重录择优。

读取机制已证实生效：首个目标3/4 accepted drawn之前，A6每份非目标read_start/read_complete为4/4，B6为0/0；到target4/UNIT前两臂又均为4/4，故不是持续禁止预取。首个drawn在OFF均为source3；ON A6为3/4/4，B6均3。source5/6较晚accepted decode_ready相对UNIT，A6 OFF 1.129–3.162、ON 5.334–24.899，B6 OFF 82.527–103.477、ON 71.896–85.395ms。完整三元组、量化边界及原日志在`analysis/o6-reader-read-order-and-readiness.json`。这只证明非目标完整读取被后移，不证明严格屏外或精确资源争用；尤其5/6较晚ready不能直接当作ON真实下一单元终点。

12段连续视频及每份firstPartial/firstFull的单张原索引图均已审。首轮拼图列错读曾提出三个错误改值，单张原图反证后全部撤回，错误JSON另存`video-reviewed-rejected-montage-reading.json`，不用于结果；最终`video-reviewed.json`经`o6-video-pts-verification-12.json`逐索引/PTS/SHA/算术核验12/12通过。完整双页后逐帧近黑扫描均0。近黑约2/255允许很暗旧UI残影，边界约一帧不确定，不把完整单元前已有一张漫画的时间继续算黑屏。

### 独立首翻：四份有限条件观察

另在相同实际verify/install下，正常每模式预热一次，由截图目标像素匹配后立即发送一次前翻；四份均两次截图轮询，实际章节输入→前翻输入已达2.183–2.245秒。因此不覆盖首图刚出就翻，不能用这四份证明更早首翻无代价。OFF实际成员5/6（目录左、角色表右）；ON实际成员仅5（角色表左槽、右空），均6/187。A/B按模式的最终全幅RGB完全相等；这是已观察基线行为对照，不新增对旧配对算法正确性的承诺，也未修复既有ON回翻错单元。

| 首翻条件 | 输入→首成员accepted drawn | 输入→全部成员accepted drawn | 首翻视频近黑界 |
|---|---:|---:|---:|
| A6 OFF | 365.629 | 392.629 | 16–48 |
| B6 OFF† | 359.542 | 391.542 | 0–32 |
| A6 ON† | 474.772 | 474.772 | 17–50 |
| B6 ON† | 471.222 | 471.222 | 17–51 |

每条件n=1，不给稳定加速或无回归结论。OFF实际holder在触摸前1.71–1.79秒已decode_ready；ON最终新holder的source5在触摸后约457.8/453.2ms ready，不能挪用初始邻接holder已ready作该单元终点。起点来自保存的`/proc/uptime`包围区间内、精确PID=TID主线程第二组tap pair首条deliver，按单次tap序列推定DOWN；trace无action参数，虽然config声明标签，android_logs实际0，不能声称标签已采到。各trace两个clock snapshot将独立draw日志映射到trace时钟，drawn非物理显示；视频只给内部PTS近黑区间，不与输入混算。四份累计chunks_discarded为205/206/207/208；†的负时间戳丢弃为A ON1、B OFF1、B ON2，不推断它恰好丢失本次端点，但必须保留计量限制。原件见`analysis/o6-first-turn-readiness-and-accepted-draw.json`及`o6-first-turn-video-review.json`。

### 采用与恢复边界

**不采用**：真实读取优先机制有效，正确性focused和独立审查通过，但当前条件没有稳定首开收益，且邻接就绪明确后移。约90行生命周期/身份准入设施的维护成本不能以“首绘前少读四页”替代体验证据。四份较晚首翻仅支持有限观察，不抵消更早预取的后移成本；不再追加本项候选或样本，不把未测/异常指标写成0。

冻结B6 artifact/source.diff/映射、真实RED/GREEN和全部测量均保留。核最终SHA后仅逆应用本任务三文件的精确diff，逐文件SHA与A6元数据一致，production指纹恢复`dd0a4130b5b238d7c5c2b44030c69bedb75ccc48d7478191de574d05e09912d5`；记录在隔离工作区`o6/rejected-initial-read-candidate/restoration-verification.json`。源码无O6准入/测试残留，O2/O4与O5六槽诊断不变；撤回复用完全相同基线的已有验证，只新做逆diff/SHA/只读核验，未声称恢复后重跑Gradle。

设备已正常恢复A6/B5 probe-only APK SHA `7c0fdd5232cace04d2ffc1cdb455c4adba437cc5c5924f1c91bbbff3ab75c98a`，读回verify/install；Probe OFF正常界面核对双页RTL、默认方向、matching ON、地图左/标题右5/187，正常Back保存第5页，最终Asleep/Changing=false。证据`measurement-o6/restore-adopted-baseline/final-verification.json`。未推送、未正式发布、未更新公共能力说明；O7–O11仍待执行。

## O7 实际原图采样取证（保留诊断，不采用调度候选）

新增默认关闭的调用诊断：Probe开启时，`sample_begin/end`包围真实`sampleOriginal`，`sample_region_begin/end`紧贴真实region decoder；按process/Activity session/chapter/sourceIndex/serial及region ordinal关联。COMPUTED表示sampler返回，RETURNED表示decode返回，均不能代替owner接受或物理显示。关闭时没有每调用上下文、serial分配或字段Map；中途关闭仍闭合已开启调用。维护边界见[探针说明](../architecture/reader-open-latency-probe.md)。

真实RED为采样已执行但诊断调用数期望2、实际0；两个sampler方法及独立Android接线方法组合GREEN，app格式通过，原件/最终SHA见worktree `o7/source-files-accepted-contract.json`。接线验证已知headers初始matching OFF零调用、ON五次调用/十次region并进入共享owner；消费OFF、旧任务结束及FIFO排空后，替换source4流使几何失效，真实必需任务仍新增一次调用/两次region。计划要求核实OFF是否采样，并未要求OFF一律零调用；早期额外零调用假设已被反例纠正，未改变生产策略迁就断言。旧matching分组测试同一失败已在严格HEAD复现，原断言未改，不称修复。Windows JNI类型检测限制使夹具只能验证普通PNG完整读取及关闭，不能冒充read_complete或像素验证。

诊断APK位于`app/artifacts/android/diagnostics/reader-open-o7-probe-20261104/`，SHA `e8b07de11997c806cdce2ba0c6eeac7550567730cbc5835c90c5033c2e3fc530`，sourceRevision `b494a0d0c658ffec5790f4ff1b9aa55088defb41`加冻结diff，production指纹`db143a54e082e8e22205094ce8ce3cc46f55414e61f1670fa6e7c9a15eb4a642`。保持code134、原诊断证书、非debug/R8/shrink；构建130.6秒PASS。首次启动命令路径写法错误未执行Gradle，`o7/build-launch-failed.log`保留，不当产品失败或行为RED。

同USB PCE-W30/API31、同CBZ/source4，正常保留数据安装后按OFF/ON各一次Probe OFF预热，再各两次新进程单次首开，四份资格位于原目录`measurement-o7/`，不混入后续A/B。未录视频/Perfetto，不报告近黑或净收益。root独立查看实际OFF/ON设置与正常双图；六份预热/资格图片中央漫画ROI均相同（`target-roi-check.json`），5/187、地图左/标题右，无教学浮层。四份完整probe含真实read/decode/FIRST_DRAW/UNIT，补足Windows显示限制。最终日志快照`reader-diagnostics-qualification-final/`保留两个实际滚动文件及SHA。

`analysis/o7-qualification-four.json`四次reader_start均唯一匹配ACTIVITY_CREATE（≤3ms），各有reader_end；按启动窗内begin选中采样span数2/4/3/3、region数1/1/2/3，各完整session内均闭合，普通准备各六对。先裁窗导致ON跨UNIT span被误列未闭合的旧版旁存`o7-qualification-four-window-clipped.json`，已撤回该解释。OFF两次及ON第二次存在超过1ms量化边界的region与普通准备窗重叠；ON第一次未观察该重叠。普通read_start→read_complete含IO排队/解析，不能解释为精确物理读取或独占CPU竞争。无显式dropped/write_failure不证明绝对无损。资格结束Probe OFF、Asleep/Changing=false；下一步仅试验复用现有前台计数覆盖普通准备，不新增首绘等待门。

### O7 普通准备租约候选与验证

候选仅三行：`DualPagerPageHolder.setImage`在既有read_start前借用`ReaderPageImageView.beginForegroundWork`，外层finally释放；范围覆盖普通编码Buffer、背景/几何处理到native提交，不等FIRST_DRAW。复用既有计数、幂等释放和adapter空闲重试，不改loader、sampler算法、预算或O6已拒门控。root独立审查物理读取退出、取消/异常/旧身份返回的finally及native任务接管路径；正在运行的JNI region不可抢占，不保证消除所有竞争窗口。

真实RED为普通PNG物理读取已阻塞而计数期望1、实际0（`o7/read-admission-red.xml/log`）。两个新方法与两个原executor回归、原真实双child draw/旧presentation拒收共五方法GREEN，app格式通过（`read-admission-source-files.json`）；受控read区间内region不启动，释放后无需额外输入恢复，异常/取消在物理退出前仍busy、退出后归零。Windows COMPLETE只证明普通stream完成及JNI边界finally，不冒充正常native提交；旧targetdraw回归绕开提交，该缺口留真机。补跑O7诊断接线时，第六次采样期望6实际5：新租约下普通准备先供给header可能消除需求，原并发计数夹具不再可靠，原件`admission-wiring-regression-failed.xml/log`保留。随后明确等待新content摄入并调用生产`requirePairing`确认NeedFacts，先完成必需第六次调用/两region，再独立普通读取；保留所有计数、同session及span断言，单方法与格式PASS，见`read-admission-diagnostic-wiring-source.json`。不是通过放宽生产策略或删除失败断言收口。

B7候选APK位于`app/artifacts/android/diagnostics/reader-open-o7-admission-20261104/`，SHA `65fbe0fdebd87e6345586a3342e8b379e09f7797ff8a21be8c3f423746e45809`，同基线revision加冻结六文件diff，production指纹`a5f1781635e785f39a232e709a89a2846f8c51d20d98c33c66403f42a6d3d1e2`，132.1秒R8构建PASS、原证书/非debug。A7为上述O7probe诊断包；两臂诊断相同，仅B7有准备租约。A1/B1首次与暖、B2/A2首次的OFF/ON十二份对照位于新`measurement-o7-admission/`，与资格四份分开，逐安装块实际读回OAT并逐mode预热；候选去留待完整端点、实际工作顺序及正确性共同评估。

### O7 最终测量与裁决

**不采用准备租约，保留默认关闭的真实调用诊断。** 十二份正式记录全部VALID，四块安装后初始OAT均为verify/install，B1/B2/A2采集后仍相同；A1采集结束后的OAT未留存，不能从后续重装追溯填补。该缺口不等于A1安装后初始读回缺失。每块每mode一次Probe OFF预热，首次表示安装后已预热的新进程首次；每臂每mode两次首次、一次同进程再次，没有升级首次/P95或跨任务合池结论。

下表单位ms。起点为各trace中精确launch PID且TID=PID主线程最早`deliverInputEvent src=0x1002`，十二份均与原native down_ts一致；两个clock snapshot映射MONOTONIC→BOOTTIME，差值0–1041ns。Running为页表发布到UNIT的主线程CPU时间，不是wall时间。FIRST_DRAW是目标source4地图，先显示的source3卷标题由视频首部分端点另计；均不是物理屏幕present。†表示该trace有负时间戳丢弃，不能据细小CPU差作因果推断。

| 样本 | 输入→目标draw | 输入→UNIT | Main Running | 视频近黑采样界限 |
| --- | ---: | ---: | ---: | --- |
| A1 OFF 首次 | 413.266 | 413.471 | 183.976 | 50–83 |
| A1 OFF 暖 | 317.953 | 318.122 | 131.492 | 0–34 |
| A1 ON 首次 | 379.506 | 379.703 | 162.658 | 67–101 |
| A1 ON 暖† | 337.344 | 337.538 | 145.331 | 33–65 |
| B1 OFF 首次 | 422.061 | 422.283 | 165.941 | 99–133 |
| B1 OFF 暖† | 350.135 | 350.298 | 146.727 | 49–83 |
| B1 ON 首次† | 390.422 | 390.644 | 159.305 | 34–66 |
| B1 ON 暖 | 321.975 | 322.138 | 131.613 | 17–50 |
| B2 OFF 首次† | 398.716 | 398.931 | 170.955 | 82–114 |
| B2 ON 首次† | 378.435 | 378.705 | 171.035 | 33–67 |
| A2 OFF 首次 | 420.655 | 420.844 | 176.431 | 67–99 |
| A2 ON 首次† | 376.163 | 376.369 | 171.361 | 16–50 |

首次目标首绘均值OFF 416.961→410.389、ON 377.835→384.428，逐次方向不一致；暖态OFF 317.953→350.135、ON 337.344→321.975，各仅一份。OFF首次Main Running均值180.204→168.448，但ON仅167.010→165.170，且trace健康限制不能保证CPU无遗漏。OFF候选两次近黑均更长，ON方向混合；不把减少重叠解释为稳定体验收益，也不从这些小样本称整体达标。

`analysis/o7-reader-sampling-and-readiness.json`以真实process/session及量化毫秒匹配十二份probe，全session共74次sampleOriginal、76次region；43 COMPUTED/31 CANCELLED，全部调用/region/普通read成对，12个reader_end，无显式loss事件，但不证明绝对无损。UNIT前确定region/普通准备内段重叠A为5/6、B为0/6；B ON的UNIT后新调用10/9/10、各全session均10次，后续仍分别2/1/1个region与普通准备重叠。它改变时序，没有证明全程竞争消失或减少总工作。source5/6 accepted decode_ready均绑定同holder/generation的真实read，保留相对UNIT时间，但不能当作ON实际首翻成员或首翻完成。本轮未另做快速首翻计时，不能称首翻无代价；首开无稳定净收益且工作后移已足以拒绝候选，不继续扩大采样或准入机制。

root独立查看十二份原PTS连续边界，首部分卷标题与完整地图左/标题右分列，保留原provisional选择；近黑使用既有内容ROI灰度均值≤2/255代理，可能残留极暗旧UI，阈值与帧采样至少一帧不确定，不能当literal全黑或精确无内容时间。`o7-video-reviewed.json`逐帧流式核完整单元之后的全部原视频，解码帧数等于ffprobe、后续近黑均0；`o7-video-pts-verification-12.json`十二份索引/PTS/公式/视频SHA独立通过。二十份预热/正式截图的漫画ROI均为`cd4b1e2a37b0eeeb0264ea098a321eb84f01a2ec15c4d9c0f23e0f36a1ed6bf4`，现场OFF/ON及默认方向/双页RTL由root逐准备截图核实，无教学浮层。视频没有校准输入时钟，不提供伪精确输入→屏幕值。

原始native/clock/health查询全部`--no-agent`保存，`o7-startup-input-identity-12.json`与`o7-native-cpu-health-12.json/.csv`保留身份和CPU范围；每trace的info级traced_chunks_discarded为209–220，不能自动解释成相关丢包。六份error级trace_sorter_negative_timestamp_dropped共7条（A1 ON暖1、B1 OFF暖1、B1 ON首次2、B2 OFF1、B2 ON1、A2 ON1）；其它未返回该非零项。android_logs_total为0只是查询结果，不能证明trace无数据。详见完整`o7-startup-nonzero-health-12.json`及逐trace raw；这些限制随数值保留，不归因编译、采样或产品缺陷。生成汇总曾把post-block字段误命名postInstall，已更正并保留旧版，raw未改。

最终仅逆应用`admission-only.diff`（SHA `abf81bc8b01ca75b07e7676507f54a0cfb52abbf78d22fe07a630522a09d2389`）撤回holder三行与两专属测试；两文件逐SHA回到A7，production指纹恢复`db143a54e082e8e22205094ce8ce3cc46f55414e61f1670fa6e7c9a15eb4a642`。恢复后新的显式必需任务诊断接线方法及app格式PASS，两个未变sampler方法复用既有GREEN；组合证据不是最终三方法合跑或模块全量。原候选五项测试、接线补验、全部失败和冻结APK/完整diff/独立两文件diff保留；最终源码与测试索引`o7/final-adopted-diagnostics-source.json`、恢复证明`o7/rejected-preparation-lease/restoration-verification.json`。本批超过400行来自同一诊断接线、真实失败夹具及完整证据，保持内聚提交，不增加产品能力范围。

实际留装A7诊断控制包，SHA与源码指纹对应；Probe配置已实际读回OFF、隔离包无进程、Asleep/Changing=false，设置为双页RTL/默认方向/matching ON，正常Back保存第5页。原件`startup-cleanup/final-verification.json`。未推送、未发布、未更新公共能力说明。O7本地收口，O8–O11继续。

## O8 当前事实请求索引（采用；章节信号不实施）

先读[历史A2](../research/reader-pairing/2026-11-01-main-reevaluation/A2.md)及A1/A2原raw：其对象是certifier重复建map/ordinal，176页分配下降但8页wall反向、未改评分负控漂移，暂缓结论保留。本轮新条件是当前`requestFact/isCurrentFactRequest`各自线性扫描content，Android初次逐页请求/接受187页真实路径有该调用形态；不恢复旧A2补丁或借用其收益。O2已减少重复投影，后续实际调用权重仍须用当前样本，不套旧比例。

新失败测试用真实`SharedDualPageSession`及8/187页计数型只读列表、构造后清计数，逆序页身份防止错误地把sourceIndex当ordinal；真实请求与owns校验访问72/35,156次，断言≤4n失败（`o8/fact-index-red.xml/log`）。候选只给现有`ReaderSpreadSamplesOwner.contentByPage`增加internal nullable只读lookup，并替换两处查找；不新建map、不改certifier或监听。关闭/缺页异常类、generation/revision/完整页序/location及票据引用检查原样，metadata/rebind后每次读取当前owner索引，索引和身份生命周期由root独立审查。

四项domain能力/metadata/navigation契约、两项实际Android replacement/retry接线与domain/app格式组合PASS，计数8/187均0；完整请求仍保留pageIds相等比较，不能宣称整个request O(1)。原始selectors/日志/XML及三文件SHA见`o8/fact-index-source-files.json`。首次准备GREEN启动后在编辑范围守卫复核阶段取消，最终只保留两处目标，不能把该准备中止当行为RED。有效identity要求发布后的稳定快照；外部原地修改MutableList已有pageIds/map不一致边界未修，不顺带防御复制或承诺此异常用法兼容。当前仅索引候选，章节级信号另按剩余队列证据判断，二者不合包计收益。


### O8 测量与最终裁决

**采用现有索引复用这一低成本去冗余，未证实稳定整体首开加速；不实施章节级信号。** A8为O7保留诊断包，B8为`app/artifacts/android/diagnostics/reader-open-o8-index-20261104/Mihon-Fork-reader-open-o8-index-benchmark-universal.apk`，SHA `b2c9d59f56cb135aa0633f124be12983dbf6d359a26eca302b3bbda9b975e21e`，sourceRevision `43b9a32cc4db00db9f1e94f1f12f6e17441df1e8`加冻结三文件diff，production指纹`1cc691935d4e4fffb8e352714c8a3c5005fbcdd9314e992b8c122752a611fd44`。非debug、R8/shrink、code134、原证书，构建及输入清单保留在artifact目录。此处revision不能替换成本次后续提交SHA。

独立`measurement-o8-index/`按A1/B1首次+暖、B2/A2首次，OFF/ON共12份VALID；每块每mode一次Probe OFF预热，四块安装后和采后实际OAT均verify/install。first指预热后新进程首次，warm是同进程再次，不是升级冷启动。计时起点为精确launch PID/TID主线程最早src=0x1002 deliverInputEvent，12份均匹配native down；同trace两个snapshot转换时钟，spread为0–521ns。Running为PAGE_LIST_READY→UNIT的主线程CPU时间，不是wall。目标FIRST_DRAW为source4地图；先出现的右侧卷标题由视频部分端点另记，均不冒充物理present。

| 样本 | 输入→目标draw ms | 输入→UNIT ms | Main Running ms | 视频近黑采样界限 ms |
| --- | ---: | ---: | ---: | --- |
| a1 off 首次† | 409.894 | 410.109 | 174.172 | 83–115 |
| a1 off 暖 | 339.549 | 339.689 | 128.786 | 49–85 |
| a1 on 首次 | 405.709 | 405.960 | 173.537 | 67–99 |
| a1 on-retry 暖 | 307.740 | 307.888 | 121.035 | 33–66 |
| b1 off 首次 | 401.266 | 401.510 | 171.608 | 66–100 |
| b1 off 暖† | 326.828 | 326.977 | 125.007 | 33–68 |
| b1 on 首次† | 378.904 | 379.145 | 159.083 | 50–83 |
| b1 on 暖 | 333.931 | 334.101 | 142.842 | 50–83 |
| b2 off† | 416.055 | 416.318 | 178.358 | 66–99 |
| b2 on | 413.157 | 413.383 | 181.937 | 66–100 |
| a2 off | 441.561 | 441.787 | 178.420 | 133–166 |
| a2 on† | 403.351 | 403.576 | 163.114 | 85–116 |

首次目标draw均值OFF 425.727→408.660ms、ON 404.530→396.030ms，但ON B2比相邻A2慢，Main Running首次OFF 176.296→174.983ms、ON 168.326→170.510ms。暖态OFF 339.549→326.828ms、ON 307.740→333.931ms，各仅一份，且ON控制暖态有下述恢复偏离。近黑与完整端点的小样本不能证明稳定净收益，不合池或累加O2/O4收益。采用理由是请求/校验真实扫描明确消除、仅复用已有索引和原语义，而非宣称体验目标已达标。

A1 ON原request2在14秒视频结尾仍显示正确目标，但采后UI捕获系统通知权限弹窗，5/187后置检查失败且缺probe2，保持EXCLUDED。原request1数据完整，root核raw并保留原排除历史后恢复CAPTURED，独立校验VALID；没有重录择优。新label `o8-a1-dual-on-retry` request1仅CONDITIONING、request2才作暖态；共14attempt=12VALID+1EXCLUDED+1CONDITIONING。额外打开及恢复进程使该暖态偏离原流程，不将其差值归因产品。预检未见弹窗，未授予／拒绝通知权限。采集脚本仅在计时段结束后对已观察、归属benchmark且唤醒解锁的通知dialog允许正常BACK，再执行原后置检查；本轮没有证据确认该guard实际触发，不称此分支已实测修复。旧脚本SHA及失败视频、恢复记录均保留。

root逐份查看12段原生PTS连续边界，卷标题部分先出现、随后完整地图左／标题右；原provisional选择均保留。近黑为原ROI灰度均值≤2/255的采样代理，可能残留极暗UI，至少一帧不确定，不能当literal全黑或精确无内容时间。`o8-video-reviewed.json`全视频逐原帧流式解码，帧数与ffprobe一致，完整单元后近黑均0；独立`o8-video-pts-verification-12.json`十二份索引／PTS／公式／MKV SHA PASS。正式12及预热8份漫画ROI均`cd4b1e2a37b0eeeb0264ea098a321eb84f01a2ec15c4d9c0f23e0f36a1ed6bf4`，root逐设置图核OFF/ON、双页RTL、默认方向及zoom OFF。

`o8-startup-comparison.json/.csv`以正式12精确清单join原native/CPU/video/health，保留逐样本raw及实际clock。†五份负时间戳丢弃共6条：A1 OFF首次1、B1 OFF暖1、B1 ON首次1、B2 OFF2、A2 ON1，限制细小CPU差的解释。info级chunks_discarded十二份221–234；patches_discarded五份各2；非零统计不自动证明相关启动区间丢失或产品原因。query exit均0、stderr保留、无agent截断；android_logs_total=0不证明trace为空。

章节信号评估另见`o8-collector-covered-cost-12.json/.csv`及串行TP原query：精确PID/main、PAGE_LIST_READY→UNIT内每份187个ReaderR2.dualFact入口，裁剪后inclusive合计0.459369–1.221872ms、单段最大0.008855–0.086979ms，无无效／边界裁剪段。这是已覆盖入口wall成本，不是完整collector或重算成本；没有区分初始skip、有效处理、stale、retry的marker。traceStartup在TARGET_HEADER_BEGIN后不再开启新段，之后152.427–222.642ms区域缺该覆盖，不能填零；ProbeON wrapper在section外还扫描到target source4，OFF不执行。P3单页tag缺失不作单页结论，嵌套tag不求和。现有证据不足支持引入章级聚合复杂性，保留P3固定初始快照、连续重试、窗口替换等既有协议，不声称全程无成本或章级方案永远无收益。

Probe OFF正常阅读检查原件`normal-roundtrip-dual-off/`和`normal-roundtrip-dual-on/`：本次均5→6→5，退出详情第5页、重开地图左／标题右，三个目标ROI相同；相邻截图间0.7秒中心像素相同只说明观测区间稳定，不等于native IDLE。OFF前翻目录左／角色表右；ON前翻角色表左／右侧留黑，原件如实保留，未把它解释成正确双图配对或新回归。历史ON 5→6→4问题本次未复现，不称修复。此检查不是快速首翻计时，不承诺首翻无代价。

最终实际留装B8，Probe OFF、无benchmark进程、Asleep/Changing=false，matching ON、双页RTL、正常Back保存第5页，读回证据`correctness-final/final-verification.json`。本项新增内部架构说明索引生命周期与稳定快照前提；O9草案／诊断不入本提交。未推送、未正式发布、未更新公共能力说明；继续O9–O11。


## O9 浮层实际提交与窄亮度参数（采用）

先区分父层、wrapper与叶节点：原`ContentOverlay(State)`只消费brightnessOverlayValue，原叶节点已经传基本值；SettingsScreenModel已有map/distinct，不能笼统声称所有浮层全树重组。最小诊断为真实Box状态消费body及ContentOverlay body直接条件SideEffect，沿用ReaderDiagnostics的process／Activity diagnosticSession，event=overlay_composed、context=ROOT/BRIGHTNESS_WRAPPER。不抽取可能自身被skip的Composable helper、不改变子组件callback参数，也不把state读取外移。Probe关闭不创建该分支SideEffect和字段Map；执行时再检查开启状态。计数只代表成功composition body提交，不等于叶重组、draw或CPU成本。

Stage1真实RED为原生产overlay及按钮已挂载、ROOT marker期望>0实际0；最小marker后单方法与app格式PASS，真实菜单事件让原ROOT/wrapper均增加、亮度事件仍增加、ProbeOFF不再记录、真实关闭仍生效。Stage2先启用独立方法取得真实RED：无关菜单后wrapper预期1实际2（三次框架retry同因）。之后仅把完整State参数改为brightness Int及其三处既有引用；五方法（诊断、无关状态skip/真实亮度像素、初始关闭／后续错误关闭／同章重试）与app格式PASS。真实overlay Canvas像素变化不需复制算法或测试底色；Stage1原断言/完整两文件SHA与Stage2原件、narrow-only diff均保存在worktree `o9/brightness-scope-source-files.json`，不是放宽生产行为迁就夹具。AppBars/其余状态仍原链路，不扩展重构。

A9诊断控制基于O8采用源码，保留完整State参数，APK `reader-open-o9-overlay-control-20261104/Mihon-Fork-reader-open-o9-overlay-control-benchmark-universal.apk`，SHA `af599287f164c5e1330b7443d03ce4565904d26692d296b5e8363b74399adea4`，production指纹`57036c1605069b0cb75f88da028408a1a03eb3da6021b0b1be5f41e40183ebb0`，sourceRevision `615c8ea67075b15fda63ed3473ea64fee998b0d4`加冻结diff。原证书、code134、nondebug/R8/shrink，构建140.9秒PASS；冻结源码按SHA从Stage1原件恢复后构建，再逐SHA换回Stage2，不使用破坏性Git回滚。

原目录独立`measurement-o9-overlay/`两份A9资格（无视频／Perfetto，不混入正式性能）：同USB/CBZ/5页，root独立核OFF/ON截图与正确双图。日志按reader_start距ACTIVITY_CREATE≤3ms唯一关联process/session，UNIT前ROOT/wrapper次数OFF4/4、ON3/3，全session分别8/8和7/7，均有reader_end；精确marker及量化边界保留在`reader-diagnostics-qualification-a/qualification-overlay-counts.json`。这确认实际首开重复提交存在，不证明整树或CPU开销。两份资格所属安装块采前／采后OAT均verify/install，设备已ProbeOFF／Asleep。B9同诊断的窄参数候选构建201.9秒PASS，APK `reader-open-o9-overlay-narrow-20261104/Mihon-Fork-reader-open-o9-overlay-narrow-benchmark-universal.apk`，SHA `4c3c6f82eea657aecd0c261972fa04c965a99ac5468f21309b87b032f23ccc59`，production指纹`4ffa9b726e52cfcf080923994f0d525169803dd1bb793eeae4337619d1b31791`，同revision加冻结diff；尚无A/B收益或最终采用裁决。



### O9 最终测量与裁决

**保留窄亮度参数及默认关闭的提交诊断。** 同USB／同CBZ／双页RTL／默认方向／zoom OFF，A1/B1各mode首次+暖、B2/A2各mode首次共12份VALID，无重录、无样本排除；各块逐mode一次Probe OFF预热。正式四块安装及采后actual OAT均verify/install、SHA匹配。A1重装前一次旧安装OAT不可读，保留UNKNOWN和权限观察，未归因；按既定install-r后读到新路径的verify/install才开始正式块，不使用资格旧header代签。A1首次日志stat格式不兼容留下仅listing的未完成目录，r2按既有成功形状完整拉取两滚动文件并保留SHA；原件未覆盖，非性能采样失败。

每份精确launch PID／主TID的最早src=0x1002输入与原native down一致，同trace两个snapshot的clock spread为0–522ns。下面Running仍为PAGE_LIST_READY→UNIT主线程CPU，近黑是视频采样区间。FIRST_DRAW仅目标地图，UNIT仅生产绘制回调，均非物理present；†为error级负时间戳丢弃。

| 样本 | 输入→目标draw ms | 输入→UNIT ms | Main Running ms | 视频近黑采样界限 ms |
| --- | ---: | ---: | ---: | --- |
| a1 off 首次 | 443.860 | 444.097 | 169.709 | 115–150 |
| a1 off 暖 | 321.001 | 321.154 | 119.512 | 50–84 |
| a1 on 首次† | 411.778 | 412.018 | 174.139 | 49–84 |
| a1 on 暖 | 324.064 | 324.270 | 139.702 | 33–67 |
| b1 off 首次 | 361.812 | 380.762 | 158.737 | 66–100 |
| b1 off 暖 | 327.655 | 327.787 | 138.104 | 34–66 |
| b1 on 首次 | 355.893 | 356.149 | 144.449 | 34–67 |
| b1 on 暖 | 344.439 | 344.583 | 116.941 | 66–99 |
| b2 off | 392.107 | 392.302 | 170.791 | 33–66 |
| b2 on† | 383.884 | 384.083 | 162.393 | 49–84 |
| a2 off | 413.722 | 413.983 | 168.286 | 82–116 |
| a2 on | 391.296 | 391.529 | 159.994 | 67–100 |

本组首次UNIT均值OFF 429.040→386.532ms、ON 401.774→370.116ms，两次候选首次各模式均偏快；目标draw均值OFF 428.791→376.959ms、ON 401.537→369.889ms。B1 OFF先地图后标题，target早于UNIT约18.95ms，因此不能只选target给完整双图收益。首次Main Running均值OFF 168.998→164.764ms、ON 167.067→153.421ms；暖态UNIT却OFF 321.154→327.787ms、ON 324.270→344.583ms（各仅1份），CPU又方向混合。首次近黑偏短，暖态ON变长；小样本、trace限制及不同提交次数造成的不同诊断写入开销，使这些差值不能直接归因窄参数或外推关闭Probe后的稳定净收益。保留理由是少量参数／引用改动明确消除真实无关提交，原状态／偏好／绘制语义及反馈仍受真实接线保护，不把调用减少当作整体体验达标。

`o9-overlay-commits-12.json`按ACTIVITY_CREATE附近唯一reader_start匹配process／Activity session，每session均有reader_end。十二份ROOT在UNIT前都是3次、全session都是7次；控制六份wrapper分别3／7，候选六份分别1／1。所有计入UNIT前的记录都严格早于其毫秒量化边界，无same-bin不定序记录。它证明wrapper提交减少，不能称整个浮层树或叶节点重组下降；父层继续完整State订阅，AppBars、提示与页码原状态链不变。

root独立查看12段原PTS连续边界并保留原索引：B1 OFF首次是左地图先，其余11份为右标题先，完整单元均地图左／标题右，无教学浮层。`o9-video-reviewed.json`逐原生帧流式解码与ffprobe帧数一致，完整后近黑均0；`o9-video-pts-verification-12.json`十二份独立索引／PTS／公式／视频SHA／inclusive帧数PASS。20份正式／预热截图漫画ROI全为`cd4b1e2a37b0eeeb0264ea098a321eb84f01a2ec15c4d9c0f23e0f36a1ed6bf4`，设置逐图由采集代理核实，root另核资格与候选OFF设置。近黑阈值≤2/255可残留极暗旧UI，至少一帧不确定；没有视频PTS到输入时钟校准，不能当精确无内容或输入到屏幕耗时。

192份native／CPU／identity／完整health/schema/severity raw query全部exit0、--no-agent未截断，每trace保留565条stats。两份error级trace_sorter_negative_timestamp_dropped共3条（A1 ON首次2、B2 ON1）；info级chunks_discarded235–246、patches各2，data_loss级未返回非零值也不构成无损证明。android_logs_total=0只作计数事实。原件`o9-native-cpu-health-12.json/.csv`、`o9-startup-input-identity-12.json`、`o9-startup-nonzero-health-12.json`及逐capture raw；一次性join在`o9-startup-comparison.json/.csv`，不合池历史或累加跨项收益。

候选Probe OFF正常OFF路径5→6→5，前翻目录左／角色表右，回翻及重开地图左／标题右、同ROI，退出详情保存第5页；root独立核原图，正常菜单和进度随操作更新。间隔0.7秒中心像素相同仅说明所采区间稳定，不冒称native IDLE或快速首翻计时。原件`normal-roundtrip-dual-off/observations.json`；此处不复测既有ON回翻错单元、不称其修复。读回`correctness-final-o9.json`为B9、ProbeOFF、无目标进程、Asleep/Changing=false，暂留matching OFF供后续O10诊断；不是整个任务最终状态。最终源码与Stage2 GREEN两项SHA一致，内部架构／诊断维护说明同步，未推送／正式发布／更新公共能力说明。O9本地收口，继续O10/O11。

## O11 baseline profile 只读条件预检（尚未收口）

本机真实SDK `cmdline-tools/latest/bin/profgen.bat`、邻近classpath及依赖Jar均存在；当前JAVA_HOME为Temurin21，launcher要求Java17+。非标准`profgen help`退出1但给usage，随后`--help`及dumpProfile/print子命令help均退出0；B8内嵌profile的dumpProfile与print实际执行退出0、无stderr。命令、输出和SHA在原目录`o10-o11-tools/`，不是只因文件存在就称工具可执行。已检查SDK platform-tools、cmdline-tools、NDK/legacy tools指定入口，没有找到dmtracedump/simpleperf，不扩大成机器或设备不支持；apkanalyzer/dexdump已找到但未执行。

`release-and-B9-profile-inventory.json`只读核两份真实APK：历史正式release vc126（SHA `35969ac31b8045cb97408620ee7be752627988f352822a3d764f048ab0bcf13b`、revision a59cfab401）携带baseline.prof 22,177B及profm 2,460B；当前B9 benchmark携带prof 22,156B及profm 2,460B。均为pro/010与prm/002 header，实际ZIP CRC/SHA保留；正式release是不同版本／源码／包身份，未安装，不作为O系列时延对照，也不宣称本轮构建了release。B8的22,157B及旧资料其它大小各属于相应产物，不互相覆盖或猜原因。

B8与当前B9分别用自己的APK、二进制profile和冻结R8 mapping运行profgen dumpProfile。两份HRF均22,069行：原始flag前缀HSP15,658、HP58、P215、SP374，另有5,764类项。ReaderActivity、ReaderViewModel、DualPageViewerAdapter、DualPagerPageHolder、SharedDualPageSession、ReaderSpreadSamplesOwner、AndroidReaderSpreadSampler七个目标类在这些方法／类项中均未命中；Reader包仅ReaderPreferences与Companion构造／初始化的四行。B8的print输出22,073行是类导向展示，方法／flag统计实际来自dumpProfile HRF，不混用两者。详情`B8-reader-profile-coverage.json`、`B9-reader-profile-coverage.json`及raw HRF；未查到只限对应冻结profile文本，不能说目标方法运行时绝不执行、整个profile无用或ART未采用。实际设备编译能力及可比干预仍待O9结束后核实，O11不据此勾选。
